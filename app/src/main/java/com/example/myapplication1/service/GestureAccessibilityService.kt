package com.example.myapplication1.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference

class GestureAccessibilityService : AccessibilityService() {

    companion object {
        private val instanceRef = AtomicReference<GestureAccessibilityService?>()

        var instance: GestureAccessibilityService?
            get() = instanceRef.get()
            private set(value) = instanceRef.set(value)

        const val TAG = "GestureAccessibilitySvc"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "onServiceConnected()")

        // Configure serviceInfo: keep this minimal — the real config is in res/xml/accessibility_service_config.xml,
        // but setting serviceInfo here helps while debugging.
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }

        // Ensure accessibility permission is really enabled; if not -> stop service
        if (!checkAccessibilityPermission()) {
            Log.e(TAG, "Accessibility permission not enabled — stopping service")
            handlePermissionDenied()
            return
        }

        // Start OverlayService in a safe way (use startForegroundService on O+)
        try {
            val overlayIntent = Intent(this, OverlayService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, overlayIntent)
            } else {
                startService(overlayIntent)
            }
            Log.d(TAG, "Requested OverlayService start()")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not start OverlayService: ${t.message}", t)
        }

        Log.d(TAG, "Accessibility service connected and configured")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Keep this lightweight; do not perform expensive work here.
        Log.d(TAG, "onAccessibilityEvent type=${event?.eventType}")
        // Example: if you later use accessibility events to detect app screens, do minimal checks here
    }

    override fun onInterrupt() {
        // Do NOT call onServiceConnected() manually. Just log and allow framework to manage lifecycle.
        Log.w(TAG, "onInterrupt() called")
    }

    override fun onDestroy() {
        instance = null
        Log.d(TAG, "onDestroy()")
        super.onDestroy()
    }

    /**
     * Safely perform a global action and return whether it succeeded.
     */
    fun performGlobalActionSafe(action: Int): Boolean {
        return try {
            performGlobalAction(action)
            true
        } catch (e: Exception) {
            Log.e(TAG, "performGlobalAction failed: ${e.message}", e)
            false
        }
    }

    fun goHome() {
        performGlobalActionSafe(GLOBAL_ACTION_HOME)
    }

    fun goBack() {
        performGlobalActionSafe(GLOBAL_ACTION_BACK)
    }

    /**
     * Launch an app by package name. We prefer using PackageManager -> launchIntent.
     * Attempting to find nodes by package name is brittle and generally unnecessary.
     */
    fun launchApp(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.d(TAG, "Launched app via intent: $packageName")
                return
            }

            // Fallback: try clicking an AccessibilityNode that might contain the app label (very unreliable)
            val root: AccessibilityNodeInfo? = rootInActiveWindow
            if (root != null) {
                try {
                    // Search for visible text that matches the app label (not package name). This is best-effort only.
                    val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() } catch (_: Exception) { packageName }
                    val nodes = root.findAccessibilityNodeInfosByText(label)
                    if (!nodes.isNullOrEmpty()) {
                        nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "Clicked app node with label: $label")
                        return
                    } else {
                        Log.w(TAG, "No node found by text=$label")
                    }
                } finally {
                    root.recycle()
                }
            } else {
                Log.w(TAG, "rootInActiveWindow null when attempting fallback launch")
            }

            Log.w(TAG, "Unable to launch package: $packageName (no launch intent and no node)")
        } catch (e: Exception) {
            Log.e(TAG, "launchApp failed for $packageName: ${e.message}", e)
        }
    }

    /**
     * Check whether the accessibility service is enabled for this app.
     * Properly parses the colon-separated list in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.
     */
    private fun checkAccessibilityPermission(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (enabledServices.isNullOrBlank()) return false
            val expected = "$packageName/.service.GestureAccessibilityService"
            enabledServices.split(":").any { it.equals(expected, ignoreCase = true) || it.endsWith(".${this::class.java.simpleName}", ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "checkAccessibilityPermission failed: ${e.message}", e)
            false
        }
    }

    /**
     * Best-effort check whether a service is running. This uses ActivityManager.getRunningServices,
     * which is deprecated but acceptable for a best-effort check. Wrap in try/catch to avoid crashes on restrictive OEMs.
     */
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            manager.getRunningServices(Int.MAX_VALUE).any { it.service?.className == serviceClass.name }
        } catch (e: Exception) {
            Log.w(TAG, "isServiceRunning check failed: ${e.message}", e)
            false
        }
    }

    private fun handlePermissionDenied() {
        Log.e(TAG, "Accessibility permission denied — stopping service")
        stopSelf()
    }
}
