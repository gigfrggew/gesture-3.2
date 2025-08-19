// CameraService.kt
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
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.myapplication1.R
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt
import android.graphics.ImageFormat
import androidx.core.content.PermissionChecker

class CameraService : LifecycleService() {
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var handLandmarker: HandLandmarker? = null
    private var overlayService: OverlayService? = null
    private val handler = Handler(Looper.getMainLooper())

    private val CHANNEL_ID = "CameraServiceChannel"
    private val NOTIFICATION_ID = 1

    private var isClickGesture = false
    private var clickGestureStartTime: Long = 0
    private val CLICK_GESTURE_THRESHOLD = 300L
    private val PINCH_THRESHOLD = 0.08f
    private var lastHandDetectedTime = 0L
    private val HAND_LOST_TIMEOUT = 1000L // 1 second
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // Use safe cast to avoid ClassCastException
            val localBinder = service as? OverlayService.LocalBinder
            if (localBinder == null) {
                Log.w("CameraService", "Overlay service binder was not the expected type.")
                overlayService = null
                isServiceBound = false
                return
            }
            overlayService = localBinder.getService()
            isServiceBound = true
            Log.d("CameraService", "Successfully connected to OverlayService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            overlayService = null
            isServiceBound = false
            Log.d("CameraService", "Disconnected from OverlayService")
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e("CameraService", "Camera permission not granted. Stopping service.")
            stopSelf()
            return
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        cameraExecutor = Executors.newSingleThreadExecutor()
        initializeHandLandmarker()
        // Only bind overlay if overlay permission likely available. If overlay not allowed binding may fail.
        try {
            bindOverlayService()
        } catch (e: Exception) {
            Log.w("CameraService", "Could not bind overlay service: ${e.message}", e)
        }
        initializeCamera()
        startHandLostChecker()
        notifyServiceStatus(true)
        Log.d("CameraService", "CameraService created successfully")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdownNow()
        cameraProvider?.unbindAll()
        try {
            handLandmarker?.close()
        } catch (e: Exception) {
            Log.w("CameraService", "Error closing handLandmarker: ${e.message}", e)
        }
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w("CameraService", "Error unbinding overlay service: ${e.message}", e)
            }
            isServiceBound = false
        }
        handler.removeCallbacksAndMessages(null) // Stop the hand-lost checker
    }

    private fun processImage(image: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(image)
            if (bitmap != null) {
                val mpImage = BitmapImageBuilder(bitmap).build()
                // Use safe call; detectAsync may throw inside - catch in onHandLandmarkerResult wrapper
                handLandmarker?.detectAsync(mpImage, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e("CameraService", "Error processing image: ${e.message}", e)
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                Log.w("CameraService", "Failed to close image proxy: ${e.message}", e)
            }
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val yBuffer = image.planes[0].buffer // Y
            val uBuffer = image.planes[1].buffer // U
            val vBuffer = image.planes[2].buffer // V

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
            val imageBytes = out.toByteArray()
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            val matrix = Matrix()
            matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
            matrix.postScale(-1f, 1f) // Mirror for front camera

            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.e("CameraService", "imageProxyToBitmap failed: ${e.message}", e)
            null
        }
    }

    fun onHandLandmarkerResult(result: HandLandmarkerResult, image: MPImage) {
        try {
            if (result.landmarks().isEmpty() || !isServiceBound || overlayService == null) {
                return
            }
            lastHandDetectedTime = System.currentTimeMillis()
            // Process on background thread to avoid UI blocking
            cameraExecutor.execute {
                try {
                    overlayService?.let { overlay ->
                        overlay.showHandFocus()
                        val landmarks = result.landmarks()[0]
                        val indexTip = landmarks[8]
                        val thumbTip = landmarks[4]
                        // Map normalized to screen coordinates (mirror handled earlier)
                        val screenX = (1.0f - indexTip.x()) * resources.displayMetrics.widthPixels
                        val screenY = indexTip.y() * resources.displayMetrics.heightPixels
                        val distance = calculateDistance(indexTip, thumbTip)
                        // Update UI on main thread
                        handler.post {
                            try {
                                overlay.updateHandPosition(screenX, screenY)
                                detectPinchGesture(distance)
                            } catch (e: Exception) {
                                Log.w("CameraService", "UI update failed: ${e.message}", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CameraService", "Error in hand landmark processing", e)
                }
            }
        } catch (e: Exception) {
            Log.e("CameraService", "onHandLandmarkerResult top-level error: ${e.message}", e)
        }
    }

    private fun startHandLostChecker() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    if (System.currentTimeMillis() - lastHandDetectedTime > HAND_LOST_TIMEOUT) {
                        overlayService?.hideHandFocus()
                    }
                } catch (e: Exception) {
                    Log.w("CameraService", "Hand lost checker failed: ${e.message}", e)
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun detectPinchGesture(distance: Float) {
        if (distance < PINCH_THRESHOLD) {
            if (!isClickGesture) {
                isClickGesture = true
                clickGestureStartTime = System.currentTimeMillis()
                overlayService?.updatePinchState(true)
            } else {
                if (System.currentTimeMillis() - clickGestureStartTime > CLICK_GESTURE_THRESHOLD) {
                    overlayService?.launchSelectedApp()
                    isClickGesture = false
                    overlayService?.updatePinchState(false)
                }
            }
        } else {
            if (isClickGesture) {
                isClickGesture = false
                overlayService?.updatePinchState(false)
            }
        }
    }

    private fun calculateDistance(point1: NormalizedLandmark, point2: NormalizedLandmark): Float {
        val dx = point2.x() - point1.x()
        val dy = point2.y() - point1.y()
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun bindOverlayService() {
        try {
            val intent = Intent(this, OverlayService::class.java)
            // Check overlay permission before binding to reduce risk of binding a service that will immediately stop itself
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
                Log.w("CameraService", "No overlay permission - skipping bind.")
                return
            }
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e("CameraService", "Failed to bind overlay service: ${e.message}", e)
        }
    }

    private fun initializeCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        val imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, this::processImage)
            }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalyzer)
        } catch (exc: Exception) {
            Log.e("CameraService", "Use case binding failed", exc)
        }
    }

    private fun initializeHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::onHandLandmarkerResult)
                .setErrorListener { error -> Log.e("CameraService", "MediaPipe Error: ${error.message}") }
                .setNumHands(1)
                .build()
            handLandmarker = HandLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            Log.e("CameraService", "Failed to initialize hand landmarker", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Gestura Service"
            val descriptionText = "Hand tracking camera service"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gestura Active")
            .setContentText("Hand gesture control is running.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Make sure this drawable exists
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyServiceStatus(started: Boolean) {
        val intent = Intent("com.example.myapplication1.CAMERA_SERVICE_STATUS").apply {
            putExtra("started", started)
        }
        sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        // Allow LifecycleService / super binding behavior
        return super.onBind(intent)
    }
}
