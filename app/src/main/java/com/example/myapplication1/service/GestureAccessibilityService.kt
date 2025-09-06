package com.example.myapplication1.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference

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

    // ✅ ADDED HERE:
    /**
     * Simulates pressing the Home button
     */
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