package com.example.myapplication1.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.myapplication1.R
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class CameraService : LifecycleService() {
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var handLandmarker: HandLandmarker? = null
    private var overlayService: OverlayService? = null
    private val handler = Handler(Looper.getMainLooper())

    private val CHANNEL_ID = "CameraServiceChannel"
    private val NOTIFICATION_ID = 1

    // Gesture detection variables
    private var isPinching = false
    private var lastPinchTime = 0L
    private var currentCursorX = 0f
    private var currentCursorY = 0f
    private val PINCH_THRESHOLD = 0.08f  // Adjusted threshold
    private val CLICK_COOLDOWN = 350L
    private var lastHandDetectedTime = 0L
    private val HAND_LOST_TIMEOUT = 2000L // Reduced timeout
    private var isServiceBound = false
    private var frameCount = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? OverlayService.LocalBinder
            if (localBinder != null) {
                overlayService = localBinder.getService()
                isServiceBound = true
                Log.d("CameraService", "✅ Connected to OverlayService")
            } else {
                Log.e("CameraService", "❌ Invalid overlay service binder")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            overlayService = null
            isServiceBound = false
            Log.d("CameraService", "❌ Disconnected from OverlayService")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("CameraService", "🚀 CameraService starting...")

        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e("CameraService", "❌ Camera permission not granted")
            stopSelf()
            return
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize components in sequence
        initializeHandLandmarker()
        bindOverlayService()

        // Small delay to ensure overlay service is bound
        handler.postDelayed({
            initializeCamera()
            startHandLostChecker()
        }, 1000)

        Log.d("CameraService", "✅ CameraService created successfully")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("CameraService", "🛑 CameraService destroying...")

        handler.removeCallbacksAndMessages(null)

        try {
            cameraExecutor.shutdownNow()
            cameraProvider?.unbindAll()
            handLandmarker?.close()
        } catch (e: Exception) {
            Log.w("CameraService", "Cleanup warning: ${e.message}")
        }

        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w("CameraService", "Unbind warning: ${e.message}")
            }
        }

        Log.d("CameraService", "✅ CameraService destroyed")
    }

    private fun initializeCamera() {
        Log.d("CameraService", "📹 Initializing camera...")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                Log.d("CameraService", "✅ Camera initialized successfully")
            } catch (e: Exception) {
                Log.e("CameraService", "❌ Camera initialization failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        // Use smaller resolution for better performance
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(480, 360),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val imageAnalyzer = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) // Changed format
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { image ->
                    processImage(image)
                }
            }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalyzer)
            Log.d("CameraService", "✅ Camera use cases bound successfully")
        } catch (e: Exception) {
            Log.e("CameraService", "❌ Use case binding failed: ${e.message}", e)
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImage(image: ImageProxy) {
        frameCount++

        try {
            // Log every 60 frames
            if (frameCount % 60 == 0) {
                Log.d("CameraService", "📸 Processing frame $frameCount: ${image.width}x${image.height}")
            }

            // Convert to bitmap efficiently
            val bitmap = convertImageProxyToBitmap(image)

            if (bitmap != null) {
                val mpImage = BitmapImageBuilder(bitmap).build()
                val timestampMs = System.currentTimeMillis()
                handLandmarker?.detectAsync(mpImage, timestampMs)

                if (frameCount % 60 == 0) {
                    Log.d("CameraService", "✅ Sent frame to MediaPipe")
                }
            } else {
                if (frameCount % 60 == 0) {
                    Log.w("CameraService", "⚠️ Bitmap conversion failed")
                }
            }
        } catch (e: Exception) {
            Log.e("CameraService", "❌ Error processing image: ${e.message}", e)
        } finally {
            image.close()
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun convertImageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val androidImage = image.image ?: return null

            when (image.format) {
                ImageFormat.YUV_420_888 -> {
                    yuvToBitmap(androidImage, image.imageInfo.rotationDegrees)
                }
                PixelFormat.RGBA_8888 -> {
                    rgbaToBitmap(image)
                }
                else -> {
                    Log.w("CameraService", "Unsupported image format: ${image.format}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("CameraService", "Bitmap conversion failed: ${e.message}", e)
            null
        }
    }

    private fun rgbaToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))

            // Apply rotation and mirroring for front camera
            val matrix = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f, image.width / 2f, image.height / 2f)
            }

            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.e("CameraService", "RGBA conversion failed: ${e.message}", e)
            null
        }
    }

    private fun yuvToBitmap(image: android.media.Image, rotationDegrees: Int): Bitmap? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)

            // Interleave U and V for NV21 format
            val uvPixelStride = image.planes[1].pixelStride
            if (uvPixelStride == 1) {
                uBuffer.get(nv21, ySize, uSize)
                vBuffer.get(nv21, ySize + uSize, vSize)
            } else {
                // Handle interleaved UV
                val uvSize = uSize.coerceAtMost(vSize)
                for (i in 0 until uvSize step uvPixelStride) {
                    nv21[ySize + i] = vBuffer.get()
                    if (i + 1 < uvSize) {
                        nv21[ySize + i + 1] = uBuffer.get()
                    }
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)

            val bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())

            if (bitmap != null) {
                val matrix = Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                    postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                }
                return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            null
        } catch (e: Exception) {
            Log.e("CameraService", "YUV conversion failed: ${e.message}", e)
            null
        }
    }

    private fun onHandLandmarkerResult(result: HandLandmarkerResult, image: MPImage) {
        try {
            if (result.landmarks().isEmpty()) {
                return
            }

            lastHandDetectedTime = System.currentTimeMillis()

            if (frameCount % 30 == 0) {
                Log.d("CameraService", "👋 Hand detected! Processing landmarks...")
            }

            handler.post {
                try {
                    val landmarks = result.landmarks()[0]
                    val indexTip = landmarks[8]  // Index finger tip
                    val thumbTip = landmarks[4]  // Thumb tip

                    // Convert to screen coordinates
                    val displayMetrics = resources.displayMetrics
                    // Use direct x mapping because the camera frame is already mirrored
                    val screenX = (indexTip.x()) * displayMetrics.widthPixels
                    val screenY = indexTip.y() * displayMetrics.heightPixels

                    currentCursorX = screenX
                    currentCursorY = screenY

                    overlayService?.let { overlay ->
                        overlay.showCursor()
                        overlay.updateCursorPosition(screenX, screenY)

                        // Check for pinch gesture
                        val distance = calculateDistance(indexTip, thumbTip)
                        detectAndHandlePinch(distance)
                    }
                } catch (e: Exception) {
                    Log.e("CameraService", "UI update failed: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e("CameraService", "Hand result processing error: ${e.message}", e)
        }
    }

    private fun detectAndHandlePinch(distance: Float) {
        val currentTime = System.currentTimeMillis()
        val wasPinching = isPinching

        isPinching = distance < PINCH_THRESHOLD

        if (isPinching && !wasPinching) {
            // Started pinching
            overlayService?.updatePinchState(true)
            lastPinchTime = currentTime
            Log.d("CameraService", "👌 Pinch started (distance: $distance)")
        } else if (!isPinching && wasPinching) {
            // Stopped pinching
            overlayService?.updatePinchState(false)
            val pinchDuration = currentTime - lastPinchTime

            if (pinchDuration < CLICK_COOLDOWN && pinchDuration > 50) {
                performClick()
            }
            Log.d("CameraService", "✋ Pinch ended (duration: ${pinchDuration}ms)")
        }
    }

    private fun performClick() {
        try {
            overlayService?.onPinchDetected()
            Log.d("CameraService", "🎯 Click performed at (${currentCursorX}, ${currentCursorY})")
        } catch (e: Exception) {
            Log.e("CameraService", "Click failed: ${e.message}", e)
        }
    }

    private fun calculateDistance(point1: NormalizedLandmark, point2: NormalizedLandmark): Float {
        val dx = point2.x() - point1.x()
        val dy = point2.y() - point1.y()
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun startHandLostChecker() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val timeSinceLastHand = System.currentTimeMillis() - lastHandDetectedTime

                    if (timeSinceLastHand > HAND_LOST_TIMEOUT && !isPinching) {
                        overlayService?.hideCursor()
                        if (frameCount % 120 == 0) {
                            Log.d("CameraService", "👻 Hand lost - cursor hidden")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("CameraService", "Hand checker error: ${e.message}")
                }

                handler.postDelayed(this, 500) // Check every 500ms
            }
        })
    }

    private fun bindOverlayService() {
        try {
            val intent = Intent(this, OverlayService::class.java)
            val bindResult = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d("CameraService", "🔗 Binding to OverlayService: $bindResult")
        } catch (e: Exception) {
            Log.e("CameraService", "❌ Failed to bind overlay service: ${e.message}", e)
        }
    }

    private fun initializeHandLandmarker() {
        try {
            Log.d("CameraService", "🤖 Initializing MediaPipe HandLandmarker...")

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .setDelegate(Delegate.GPU)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::onHandLandmarkerResult)
                .setErrorListener { error ->
                    Log.e("CameraService", "❌ MediaPipe Error: ${error.message}")
                }
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.7f)  // Reasonable threshold
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(this, options)
            Log.d("CameraService", "✅ HandLandmarker initialized successfully")
        } catch (e: Exception) {
            Log.e("CameraService", "❌ Failed to initialize HandLandmarker: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Gestura Camera Service"
            val descriptionText = "Hand tracking for air mouse"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gestura Active")
            .setContentText("Air mouse tracking active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }
}