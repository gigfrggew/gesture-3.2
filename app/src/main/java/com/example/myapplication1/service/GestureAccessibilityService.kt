package com.example.myapplication1.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import android.view.Display


class GestureAccessibilityService : AccessibilityService() {

    companion object {
        private val instanceRef = AtomicReference<GestureAccessibilityService?>()

        var instance: GestureAccessibilityService?
            get() = instanceRef.get()
            private set(value) = instanceRef.set(value)

        const val TAG = "GestureAccessibilitySvc"

        // 🔧 Store last known cursor position
        var lastCursorX: Int = 500
        var lastCursorY: Int = 1000
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")

        // Configure service
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            notificationTimeout = 100
        }

        if (!checkAccessibilityPermission()) {
            Log.e(TAG, "Accessibility permission not enabled")
            stopSelf()
            return
        }

        // Note: OverlayService is started by MainActivity to avoid conflicts
        // This service only handles accessibility actions
        Log.d(TAG, "Accessibility service ready - OverlayService should be managed by MainActivity")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            Log.d(TAG, "Received event: ${it.eventType}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    // ===== Swipe helpers =====
    private fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Swipe failed: ${e.message}", e)
            false
        }
    }

    fun swipeLeft(): Boolean {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val y = (height * 0.5f).toInt()
        val startX = (width * 0.8f).toInt()
        val endX = (width * 0.2f).toInt()
        return performSwipe(startX, y, endX, y, 400)
    }

    fun swipeRight(): Boolean {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val y = (height * 0.5f).toInt()
        val startX = (width * 0.2f).toInt()
        val endX = (width * 0.8f).toInt()
        return performSwipe(startX, y, endX, y, 400)
    }

    // ===== Screenshot helpers (API 33+) =====
    @Volatile private var lastScreenshotFile: File? = null

    fun takeScreenshotAndSave(): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Screenshot requires Android 13+", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val executor = Executors.newSingleThreadExecutor()
            val cancel = CancellationSignal()

            // ✅ Call without named arguments
            takeScreenshot(
                Display.DEFAULT_DISPLAY,   // displayId (usually 0)
                executor,                  // executor
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hb: HardwareBuffer = screenshot.hardwareBuffer
                            val cs = screenshot.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(hb, cs)

                            if (bitmap == null) {
                                Log.e(TAG, "Failed to wrap hardware buffer to bitmap")
                                Toast.makeText(
                                    this@GestureAccessibilityService,
                                    "Screenshot failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                                hb.close()
                                return
                            }

                            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close() // ✅ always close HardwareBuffer

                            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Gestura")
                            if (!dir.exists()) dir.mkdirs()
                            val file = File(dir, "screenshot_${System.currentTimeMillis()}.png")

                            FileOutputStream(file).use { out ->
                                softwareBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }

                            lastScreenshotFile = file
                            Toast.makeText(
                                this@GestureAccessibilityService,
                                "Screenshot saved",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.d(TAG, "Screenshot saved to ${file.absolutePath}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot save failed: ${e.message}", e)
                            Toast.makeText(
                                this@GestureAccessibilityService,
                                "Screenshot save failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with code $errorCode")
                        Toast.makeText(
                            this@GestureAccessibilityService,
                            "Screenshot failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot exception: ${e.message}", e)
            false
        }
    }



    fun dropLastScreenshot(): Boolean {
        return try {
            val file = lastScreenshotFile
            if (file != null && file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Drop screenshot: ${file.absolutePath} deleted=$deleted")
                Toast.makeText(this, if (deleted) "Screenshot discarded" else "Failed to discard", Toast.LENGTH_SHORT).show()
                if (deleted) lastScreenshotFile = null
                deleted
            } else {
                Toast.makeText(this, "No screenshot to discard", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to drop screenshot: ${e.message}", e)
            false
        }
    }
    
    /**
     * Trigger click on currently highlighted app
     */
    fun triggerCurrentAppClick() {
        Log.d(TAG, "triggerCurrentAppClick called — clicking at ($lastCursorX, $lastCursorY)")
        performClickAt(lastCursorX, lastCursorY)
    }

    /**
     * Perform click at specific coordinates
     */
    fun performClickAt(x: Int, y: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val path = Path().apply {
                    moveTo(x.toFloat(), y.toFloat())
                    lineTo(x.toFloat(), y.toFloat())
                }

                val stroke = GestureDescription.StrokeDescription(path, 0, 100)
                val builder = GestureDescription.Builder()
                builder.addStroke(stroke)

                val gesture = builder.build()
                val result = dispatchGesture(gesture, null, null)

                Log.d(TAG, "Click dispatched at ($x, $y): $result")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform click: ${e.message}", e)
                false
            }
        } else {
            Log.w(TAG, "Gesture dispatch not supported on pre-Nougat")
            false
        }
    }


    fun goHome(): Boolean {
        return try {
            val result = performGlobalAction(GLOBAL_ACTION_HOME)
            Log.d(TAG, "Home button pressed: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to press Home: ${e.message}", e)
            false
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expected = "$packageName/.service.GestureAccessibilityService"
            enabled.split(":").any {
                it.equals(expected, ignoreCase = true) ||
                        it.endsWith(".${this::class.java.simpleName}", ignoreCase = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Check failed: ${e.message}", e)
            false
        }
    }
}