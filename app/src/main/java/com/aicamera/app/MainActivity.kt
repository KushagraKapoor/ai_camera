package com.aicamera.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private var sensorArraySize: android.graphics.Rect? = null
    private var isFocusing = false

    private lateinit var stillImageReader: ImageReader

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
        
        binding.textureView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleFocusTap(event.x, event.y, view.width, view.height)
            }
            true
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
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
        if (!allPermissionsGranted()) return
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    cameraId = id
                    break
                }
            }

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access camera", e)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            finish()
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = binding.textureView.surfaceTexture!!
            // Use standard 1080p preview size (you'd typically query supported sizes)
            texture.setDefaultBufferSize(1920, 1080)
            val previewSurface = Surface(texture)

            // Setup ImageReader for AI analysis (lower res for speed)
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)

            // Setup ImageReader for high-quality capture
            stillImageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1)
            stillImageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                FileUtils.saveImage(this, image)
                image.close()
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(this@MainActivity, "Photo saved!", Toast.LENGTH_SHORT).show()
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

    private fun setManualControlSettings(builder: CaptureRequest.Builder) {
        // Disable auto exposure and auto white balance
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)

        if (!isFocusing) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) 
        }

        // Set initial manual values
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)
    }

    private fun handleFocusTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        if (cameraDevice == null || captureSession == null || sensorArraySize == null) return
        isFocusing = true

        // 1. Animate UI Ring
        val ring = binding.root.findViewById<ImageView>(R.id.ivFocusRing)
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
                     // Return to repeating our manual exposure stream
                     captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                     updateCameraPreview()
                 }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed manual focus", e)
        }
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            analyzeImage(image)
        } finally {
            image.close()
        }
    }

    private fun analyzeImage(image: android.media.Image) {
        // Implementation for AI logic will go here.
        // Reading Y-plane for luminance
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        
        // Simple heuristic: calculate average luminance
        var sum = 0L
        for (byte in data) {
            sum += (byte.toInt() and 0xFF)
        }
        val avgLuminance = sum / data.size.toDouble()

        // This runs on background thread, adjust settings based on luminance
        adjustSettingsBasedOnAI(avgLuminance)
    }
    
    private fun adjustSettingsBasedOnAI(luminance: Double) {
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
            captureSession!!.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Update preview failed", e)
        }
    }

    private fun takePhoto() {
        if (cameraDevice == null) return
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(stillImageReader.surface)
            
            // Apply current AI settings to the final shot
            setManualControlSettings(captureBuilder)
            captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime)

            // Orientation handling can be added here
            
            captureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    Log.d(TAG, "Capture complete")
                }
            }, null)
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Capture failed", e)
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        if(::imageReader.isInitialized) {
             imageReader.close()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "AICamera"
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
