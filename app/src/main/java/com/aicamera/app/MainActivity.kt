package com.aicamera.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aicamera.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private var cameraId: String = ""
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var captureRequestBuilder: CaptureRequest.Builder

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private lateinit var imageReader: ImageReader

    // AI Control variables
    private var currentIso = 100
    private var currentExposureTime = 1000000000L / 30 // ~1/30s
    private var currentRGain = 1.5f
    private var currentBGain = 1.5f
    private var currentFocusDistance = 0.0f // 0.0f = infinity
    private var sensorArraySize: android.graphics.Rect? = null
    private var sensorOrientation = 0
    private var isFocusing = false

    private lateinit var stillImageReader: ImageReader
    private val capturedHdrImages = mutableListOf<ByteArray>()

    private var isHdrEnabled = true
    private var isAeAfLocked = false
    private var isUltraHighResSensor = false
    private lateinit var gestureDetector: GestureDetector

    /** A [Semaphore] to prevent the app from exiting before closing the camera. */
    private val cameraOpenCloseLock = Semaphore(1)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        binding.btnCapture.setOnClickListener {
            takePhoto()
        }

        binding.switchHdr.setOnCheckedChangeListener { _, isChecked ->
            isHdrEnabled = isChecked
        }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleFocusTap(e.x, e.y, binding.textureView.width, binding.textureView.height)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handleFocusLock(e.x, e.y, binding.textureView.width, binding.textureView.height)
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (binding.textureView.isAvailable) {
            startCamera()
        } else {
            binding.textureView.surfaceTextureListener = textureListener
        }
        
        binding.textureView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler?.removeCallbacksAndMessages(null)
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while stopping background thread", e)
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            startCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        if (!allPermissionsGranted() || isFinishing || isDestroyed) return
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    cameraId = id
                    break
                }
            }

            if (cameraId.isEmpty()) {
                cameraOpenCloseLock.release()
                return
            }
            
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access camera", e)
            cameraOpenCloseLock.release()
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing camera permission", e)
            cameraOpenCloseLock.release()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error opening camera", e)
            cameraOpenCloseLock.release()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            finish()
        }
    }

    private fun getJpegOrientation(): Int {
        val rotation = getSystemService(WindowManager::class.java).defaultDisplay?.rotation ?: Surface.ROTATION_0
        val jpegOrientation = (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360
        return jpegOrientation
    }

    private fun createCameraPreviewSession() {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            var largestJpeg: Size? = null

            // 1. Android 12+ Ultra High Resolution Sensor (50MP Unbinned Mode - IMX890/JN1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val maxResMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                if (maxResMap != null) {
                    val maxSizes = maxResMap.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                    val largestMaxRes = maxSizes.filter { 
                        val aspect = it.width.toFloat() / it.height.toFloat()
                        Math.abs(aspect - 4f/3f) < 0.05 || Math.abs(aspect - 3f/4f) < 0.05
                    }.maxByOrNull { it.width * it.height } ?: maxSizes.maxByOrNull { it.width * it.height }
                    
                    // Verify it's actually an ultra-high resolution (e.g., > 16 Megapixels)
                    if (largestMaxRes != null && largestMaxRes.width * largestMaxRes.height > 16000000) {
                        largestJpeg = largestMaxRes
                        isUltraHighResSensor = true
                        Log.d(TAG, "50MP Maximum Resolution Output Activated: ${largestJpeg.width}x${largestJpeg.height}")
                    }
                }
            }
            
            // 2. Fallback to standard binned resolutions (e.g. 12.5MP) if 50MP not supported
            if (largestJpeg == null) {
                val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
                largestJpeg = sizes.filter { 
                    val aspect = it.width.toFloat() / it.height.toFloat()
                    Math.abs(aspect - 4f/3f) < 0.05 || Math.abs(aspect - 3f/4f) < 0.05
                }.maxByOrNull { it.width * it.height } ?: sizes.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
            }

            // Find best 4:3 resolution for Preview
            val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            val bestPreview = previewSizes.filter {
                val aspect = it.width.toFloat() / it.height.toFloat()
                Math.abs(aspect - 4f/3f) < 0.05 || Math.abs(aspect - 3f/4f) < 0.05
            }.maxByOrNull { it.width * it.height } ?: Size(1440, 1080) // Fallback standard 4:3

            val texture = binding.textureView.surfaceTexture!!
            texture.setDefaultBufferSize(bestPreview.width, bestPreview.height)
            configureTransform(binding.textureView.width, binding.textureView.height, bestPreview.width, bestPreview.height)
            
            val previewSurface = Surface(texture)

            // Setup ImageReader for AI analysis (lower res for speed)
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)

            // Setup ImageReader for HDR Burst Capture (capacity 3)
            stillImageReader = ImageReader.newInstance(largestJpeg.width, largestJpeg.height, ImageFormat.JPEG, 3)
            stillImageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    if (isHdrEnabled) {
                        capturedHdrImages.add(bytes)
                        if (capturedHdrImages.size == 3) {
                            val imagesToProcess = capturedHdrImages.toList()
                            capturedHdrImages.clear()
                            CoroutineScope(Dispatchers.Default).launch {
                                processHdrAndSave(imagesToProcess)
                            }
                        }
                    } else {
                        // Single shot mode - direct save bypassing memory heavy Bitmap decoding
                        CoroutineScope(Dispatchers.Default).launch {
                            saveJpegBytes(bytes)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Photo saved.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring still image", e)
                }
            }, backgroundHandler)

            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(previewSurface)
            captureRequestBuilder.addTarget(imageReader.surface)

            setManualControlSettings(captureRequestBuilder)

            @Suppress("DEPRECATION")
            cameraDevice!!.createCaptureSession(
                listOf(previewSurface, imageReader.surface, stillImageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            captureSession!!.setRepeatingRequest(
                                captureRequestBuilder.build(),
                                null,
                                backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Failed to start camera preview", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(this@MainActivity, "Configuration change", Toast.LENGTH_SHORT).show()
                    }
                }, null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed creating capture session", e)
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int, previewWidth: Int, previewHeight: Int) {
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewHeight.toFloat(), previewWidth.toFloat()) // Swapped for portrait
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        
        val scale = Math.max(
            viewHeight.toFloat() / previewHeight,
            viewWidth.toFloat() / previewWidth
        )
        matrix.postScale(scale, scale, centerX, centerY)
        
        // Account for rotation (assuming portrait lock for this simple app)
        val rotation = getSystemService(WindowManager::class.java).defaultDisplay?.rotation ?: Surface.ROTATION_0
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scaleLandscape = Math.max(
                viewHeight.toFloat() / previewWidth,
                viewWidth.toFloat() / previewHeight
            )
            matrix.postScale(scaleLandscape, scaleLandscape, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        
        binding.textureView.setTransform(matrix)
    }

    private fun setManualControlSettings(builder: CaptureRequest.Builder) {
        // Disable auto exposure and auto white balance
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)

        // Enable Hardware Optical Image Stabilization (OIS)
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)

        if (!isFocusing) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance) 
        }

        // Set initial manual values
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
        
        // Manual White Balance (AI Controlled)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(currentRGain, 1.0f, 1.0f, currentBGain))
    }

    private fun handleFocusTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        if (cameraDevice == null || captureSession == null || sensorArraySize == null) return
        isFocusing = true
        isAeAfLocked = false // Unlock AE/AF on single tap

        // 1. Animate UI Ring
        val ring = binding.root.findViewById<ImageView>(R.id.ivFocusRing)
        ring.setColorFilter(android.graphics.Color.WHITE) // Normal focus ring color
        ring.translationX = x - (ring.width / 2)
        ring.translationY = y - (ring.height / 2)
        ring.visibility = View.VISIBLE
        ring.alpha = 1f
        ring.scaleX = 1.5f
        ring.scaleY = 1.5f
        ring.animate().scaleX(1f).scaleY(1f).setDuration(300).withEndAction {
            ring.animate().alpha(0f).setStartDelay(1000).setDuration(300).withEndAction {
                ring.visibility = View.GONE
            }.start()
        }.start()

        // 2. Calculate coordinates on sensor
        val sensorX = (x / viewWidth * sensorArraySize!!.width()).toInt()
        val sensorY = (y / viewHeight * sensorArraySize!!.height()).toInt()
        val halfTouchWidth = 150 
        val halfTouchHeight = 150 
        
        val focusRect = android.graphics.Rect(
            (sensorX - halfTouchWidth).coerceAtLeast(0),
            (sensorY - halfTouchHeight).coerceAtLeast(0),
            (sensorX + halfTouchWidth).coerceAtMost(sensorArraySize!!.width()),
            (sensorY + halfTouchHeight).coerceAtMost(sensorArraySize!!.height())
        )
        val meteringRectangle = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)

        // 3. Command Camera to Focus
        try {
            captureSession!!.stopRepeating()
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle))
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            
            captureSession!!.capture(captureRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                 override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                     isFocusing = false
                     
                     // Retrieve the actual focus distance the lens just locked onto
                     val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                     if (focusDistance != null) {
                         currentFocusDistance = focusDistance
                     }

                     // Return to repeating our manual exposure stream with the new locked focus
                     captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                     updateCameraPreview()
                 }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed manual focus", e)
        }
    }

    private fun handleFocusLock(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        if (cameraDevice == null || captureSession == null || sensorArraySize == null) return
        isFocusing = true
        isAeAfLocked = true // Lock AE/AF on long press

        // 1. Animate Lock UI Ring (Yellow indicates lock)
        val ring = binding.root.findViewById<ImageView>(R.id.ivFocusRing)
        ring.setColorFilter(android.graphics.Color.YELLOW)
        ring.translationX = x - (ring.width / 2)
        ring.translationY = y - (ring.height / 2)
        ring.visibility = View.VISIBLE
        ring.alpha = 1f
        ring.scaleX = 2f
        ring.scaleY = 2f
        ring.animate().scaleX(1f).scaleY(1f).setDuration(300).withEndAction {
            ring.animate().alpha(0f).setStartDelay(1500).setDuration(300).withEndAction {
                ring.visibility = View.GONE
            }.start()
        }.start()

        // 2. Calculate coordinates on sensor
        val sensorX = (x / viewWidth * sensorArraySize!!.width()).toInt()
        val sensorY = (y / viewHeight * sensorArraySize!!.height()).toInt()
        val halfTouchWidth = 150 
        val halfTouchHeight = 150 
        
        val focusRect = android.graphics.Rect(
            (sensorX - halfTouchWidth).coerceAtLeast(0),
            (sensorY - halfTouchHeight).coerceAtLeast(0),
            (sensorX + halfTouchWidth).coerceAtMost(sensorArraySize!!.width()),
            (sensorY + halfTouchHeight).coerceAtMost(sensorArraySize!!.height())
        )
        val meteringRectangle = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)

        // 3. Command Camera to Focus and lock
        try {
            captureSession!!.stopRepeating()
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle))
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            
            captureSession!!.capture(captureRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                 override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                     isFocusing = false
                     
                     val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                     if (focusDistance != null) {
                         currentFocusDistance = focusDistance
                     }

                     captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                     updateCameraPreview()
                 }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed manual focus lock", e)
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            binding.tvAiStatus.text = "AI Status: AE/AF Locked"
        }
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        try {
            val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
            try {
                analyzeImage(image)
            } finally {
                image.close()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "ImageReader acquired too many images or closed", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception accessing image", e)
        }
    }

    private fun analyzeImage(image: android.media.Image) {
        if (image.planes.size < 3) return

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Extract Luminance
        val yBuffer = yPlane.buffer
        val yData = ByteArray(yBuffer.remaining())
        yBuffer.get(yData)
        var sumY = 0L
        for (byte in yData) {
            sumY += (byte.toInt() and 0xFF)
        }
        val avgLuminance = sumY / yData.size.toDouble()

        // Extract U and V for Color/White Balance
        val uBuffer = uPlane.buffer
        var sumU = 0L
        for (i in 0 until uBuffer.remaining() step uPlane.pixelStride) {
            sumU += (uBuffer[i].toInt() and 0xFF)
        }
        val avgU = if (uBuffer.remaining() > 0) sumU / (uBuffer.remaining() / uPlane.pixelStride.toDouble()) else 128.0

        val vBuffer = vPlane.buffer
        var sumV = 0L
        for (i in 0 until vBuffer.remaining() step vPlane.pixelStride) {
            sumV += (vBuffer[i].toInt() and 0xFF)
        }
        val avgV = if (vBuffer.remaining() > 0) sumV / (vBuffer.remaining() / vPlane.pixelStride.toDouble()) else 128.0

        // This runs on background thread, adjust settings based on luminance and color
        adjustSettingsBasedOnAI(avgLuminance, avgU, avgV)
    }
    
    private fun adjustSettingsBasedOnAI(luminance: Double, uCol: Double, vCol: Double) {
        if (isAeAfLocked) {
            return // Do not automatically adjust if locked
        }

        // Target luminance (approx 128 for mid-grey)
        val targetLuminance = 120.0
        // User requested lower sensitivity/higher threshold before bouncing settings
        val tolerance = 25.0 
        
        var needsUpdate = false
        
        if (luminance < (targetLuminance - tolerance)) {
            // Unexdeposed: Increase ISO or Exposure Time
            if (currentIso < 800) {
                currentIso += 25 // Smoother ramping
                needsUpdate = true
            } else if (currentExposureTime < 1000000000L / 10) { // Max 1/10s
                currentExposureTime += 5000000L
                needsUpdate = true
            }
        } else if (luminance > (targetLuminance + tolerance)) {
            // Overexposed: Decrease ISO or Exposure Time
            if (currentIso > 50) {
                currentIso -= 25 // Smoother ramping
                needsUpdate = true
            } else if (currentExposureTime > 1000000000L / 1000) { // Min 1/1000s
                currentExposureTime -= 2000000L
                if (currentExposureTime < 1000000L) currentExposureTime = 1000000L
                needsUpdate = true
            }
        }

        // White Balance Adjustments
        // In YUV, neutral gray means U and V are close to 128
        val colorTolerance = 5.0
        
        if (vCol > 128.0 + colorTolerance && currentRGain > 1.0f) {
            currentRGain -= 0.05f // Too warm, reduce Red
            needsUpdate = true
        } else if (vCol < 128.0 - colorTolerance && currentRGain < 3.0f) {
            currentRGain += 0.05f 
            needsUpdate = true
        }

        if (uCol > 128.0 + colorTolerance && currentBGain > 1.0f) {
            currentBGain -= 0.05f // Too cool, reduce Blue
            needsUpdate = true
        } else if (uCol < 128.0 - colorTolerance && currentBGain < 3.0f) {
            currentBGain += 0.05f
            needsUpdate = true
        }

        if (needsUpdate) {
            updateCameraPreview()
            CoroutineScope(Dispatchers.Main).launch {
                binding.tvAiDetails.text = "ISO: ${currentIso} | Shutter: 1/${1000000000L / currentExposureTime}s"
                binding.tvAiStatus.text = "AI Status: Adjusting..."
            }
        } else {
             CoroutineScope(Dispatchers.Main).launch {
                binding.tvAiStatus.text = "AI Status: Optimal"
            }
        }
    }

    private fun updateCameraPreview() {
        if (cameraDevice == null || captureSession == null) return
        try {
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
            captureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(currentRGain, 1.0f, 1.0f, currentBGain))
            val handler = backgroundHandler
            if (handler != null) {
                captureSession!!.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Update preview failed", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Session closed during preview update", e)
        }
    }

    private fun applyMaxResolutionIfAvailable(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isUltraHighResSensor) {
            builder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
        }
    }

    private fun takePhoto() {
        if (cameraDevice == null) return
        try {
            val jpegOrientation = getJpegOrientation()

            if (!isHdrEnabled) {
                // Request 1: Normal Exposure only
                val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureBuilder.addTarget(stillImageReader.surface)
                setManualControlSettings(captureBuilder)
                applyMaxResolutionIfAvailable(captureBuilder)
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
                captureBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(currentRGain, 1.0f, 1.0f, currentBGain))
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

                captureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        super.onCaptureCompleted(session, request, result)
                        Log.d(TAG, "Single Capture complete")
                    }
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                        super.onCaptureFailed(session, request, failure)
                        Log.e(TAG, "Camera HAL rejected capture. Reason: ${failure.reason}")
                        CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "Capture Failed. Disabling 50MP.", Toast.LENGTH_LONG).show() }
                        isUltraHighResSensor = false
                    }
                }, null)
                
                CoroutineScope(Dispatchers.Main).launch {
                    val resString = if (isUltraHighResSensor) "50MP " else ""
                    Toast.makeText(this@MainActivity, "Capturing ${resString}Photo...", Toast.LENGTH_SHORT).show()
                }
                return
            }

            // HDR Logic:
            // Request 1: Highlight Recovery (Underexposed - 1/2 exposure time)
            val captureBuilder1 = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder1.addTarget(stillImageReader.surface)
            setManualControlSettings(captureBuilder1)
            applyMaxResolutionIfAvailable(captureBuilder1)
            captureBuilder1.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            captureBuilder1.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime / 2)
            captureBuilder1.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(currentRGain, 1.0f, 1.0f, currentBGain))
            captureBuilder1.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

            // Request 2: Normal Exposure
            val captureBuilder2 = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder2.addTarget(stillImageReader.surface)
            setManualControlSettings(captureBuilder2)
            applyMaxResolutionIfAvailable(captureBuilder2)
            captureBuilder2.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            captureBuilder2.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
            captureBuilder2.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(currentRGain, 1.0f, 1.0f, currentBGain))
            captureBuilder2.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

            // Request 3: Shadow Recovery (Overexposed - 2x exposure time)
            val captureBuilder3 = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder3.addTarget(stillImageReader.surface)
            setManualControlSettings(captureBuilder3)
            applyMaxResolutionIfAvailable(captureBuilder3)
            captureBuilder3.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            captureBuilder3.set(CaptureRequest.SENSOR_EXPOSURE_TIME, (currentExposureTime * 2).coerceAtMost(1000000000L / 10)) // Max 1/10s
            captureBuilder3.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(currentRGain, 1.0f, 1.0f, currentBGain))
            captureBuilder3.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

            capturedHdrImages.clear()

            captureSession?.captureBurst(
                listOf(captureBuilder1.build(), captureBuilder2.build(), captureBuilder3.build()),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                        super.onCaptureSequenceCompleted(session, sequenceId, frameNumber)
                        Log.d(TAG, "HDR Burst Capture Sequence Complete")
                    }
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                        super.onCaptureFailed(session, request, failure)
                        Log.e(TAG, "HDR Burst capture failed. Reason: ${failure.reason}")
                        CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "Burst Failed. Disabling 50MP.", Toast.LENGTH_LONG).show() }
                        isUltraHighResSensor = false
                    }
                }, null
            )
            
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(this@MainActivity, "Capturing HDR Burst...", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Capture Burst failed", e)
        }
    }

    private suspend fun processHdrAndSave(images: List<ByteArray>) {
        withContext(Dispatchers.Main) {
            binding.tvAiStatus.text = "AI Status: Processing HDR Pipeline..."
        }
        
        try {
            // Decode with downsampling (inSampleSize = 2 normally, 4 for 50MP) to prevent OutOfMemoryError
            val options = BitmapFactory.Options()
            options.inSampleSize = if (isUltraHighResSensor) 4 else 2 
            
            val bitmap1 = BitmapFactory.decodeByteArray(images[0], 0, images[0].size, options)
            val bitmap2 = BitmapFactory.decodeByteArray(images[1], 0, images[1].size, options)
            val bitmap3 = BitmapFactory.decodeByteArray(images[2], 0, images[2].size, options)
            
            if (bitmap1 != null && bitmap2 != null && bitmap3 != null) {
                val width = bitmap1.width
                val height = bitmap1.height
                val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                
                // Simple AI HDR logic: Exposure Fusion Averaging
                val pixels1 = IntArray(width * height)
                val pixels2 = IntArray(width * height)
                val pixels3 = IntArray(width * height)
                
                bitmap1.getPixels(pixels1, 0, width, 0, 0, width, height)
                bitmap2.getPixels(pixels2, 0, width, 0, 0, width, height)
                bitmap3.getPixels(pixels3, 0, width, 0, 0, width, height)
                
                val resultPixels = IntArray(width * height)
                for (i in resultPixels.indices) {
                    val p1 = pixels1[i]
                    val p2 = pixels2[i]
                    val p3 = pixels3[i]
                    
                    val r = ((android.graphics.Color.red(p1) + android.graphics.Color.red(p2) + android.graphics.Color.red(p3)) / 3).coerceIn(0, 255)
                    val g = ((android.graphics.Color.green(p1) + android.graphics.Color.green(p2) + android.graphics.Color.green(p3)) / 3).coerceIn(0, 255)
                    val b = ((android.graphics.Color.blue(p1) + android.graphics.Color.blue(p2) + android.graphics.Color.blue(p3)) / 3).coerceIn(0, 255)
                    
                    resultPixels[i] = android.graphics.Color.rgb(r, g, b)
                }
                
                resultBitmap.setPixels(resultPixels, 0, width, 0, 0, width, height)
                
                // Save Result Bitmap
                saveProcessedBitmap(resultBitmap)
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "HDR Magic applied! Photo saved.", Toast.LENGTH_LONG).show()
                }
                
                bitmap1.recycle()
                bitmap2.recycle()
                bitmap3.recycle()
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during HDR processing", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Phone memory limits exceeded during HDR fusion.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing HDR", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "HDR Processing Failed.", Toast.LENGTH_SHORT).show()
            }
        } finally {
            if (!isAeAfLocked) {
                withContext(Dispatchers.Main) {
                    binding.tvAiStatus.text = "AI Status: Optimal"
                }
            }
        }
    }

    private fun saveJpegBytes(bytes: ByteArray) {
        val resolver = contentResolver
        val filename = "AI_CAM_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AICamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        
        var uri: android.net.Uri? = null
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                return // Success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving JPEG bytes", e)
            if (uri != null) resolver.delete(uri, null, null)
        }

        // Failsafe: Android 13/14/15/16 App-Specific External Directory
        try {
            val appDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            if (appDir != null) {
                if (!appDir.exists()) appDir.mkdirs()
                val file = java.io.File(appDir, filename)
                file.writeBytes(bytes)
                CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "Saved to App Folder: ${file.absolutePath}", Toast.LENGTH_LONG).show() }
            }
        } catch (e: Exception) {
             Log.e(TAG, "Total save failure", e)
             CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "Storage Write Error.", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun saveProcessedBitmap(bitmap: Bitmap) {
        val resolver = contentResolver
        val filename = "AI_HDR_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AICamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        
        var uri: android.net.Uri? = null
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                return // Success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving HDR Bitmap", e)
            if (uri != null) resolver.delete(uri, null, null)
        }

        // Failsafe: Android 13/14/15/16 App-Specific External Directory
        try {
            val appDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            if (appDir != null) {
                if (!appDir.exists()) appDir.mkdirs()
                val file = java.io.File(appDir, filename)
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "Saved to App Folder: ${file.absolutePath}", Toast.LENGTH_LONG).show() }
            }
        } catch (e: Exception) {
             Log.e(TAG, "Total save failure", e)
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            
            cameraDevice?.close()
            cameraDevice = null
            
            if (::imageReader.isInitialized) {
                 imageReader.setOnImageAvailableListener(null, null)
                 imageReader.close()
            }
            if (::stillImageReader.isInitialized) {
                 stillImageReader.setOnImageAvailableListener(null, null)
                 stillImageReader.close()
            }
            
            capturedHdrImages.clear()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera resources", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "AICamera"
        private val ORIENTATIONS = android.util.SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
