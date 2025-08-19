// OverlayService.kt
package com.example.myapplication1.service

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ImageView
import android.view.View
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.app.NotificationCompat
import com.example.myapplication1.R

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var appRowOverlayView: LinearLayout? = null
    private var focusOverlayView: View? = null

    private val appList = mutableListOf<AppInfo>()
    private var selectedAppIndex = -1
    private var isPinching = false
    private var showFocus = false

    private val handler = Handler(Looper.getMainLooper())
    data class AppInfo(val name: String, val packageName: String, val icon: Drawable)

    inner class LocalBinder : android.os.Binder() {
        fun getService(): OverlayService = this@OverlayService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        
        if (!Settings.canDrawOverlays(this)) {
            Log.e("OverlayService", "Overlay permission not granted. Stopping service.")
            stopSelf()
            return
        }
        
        ensureNotificationChannel()
        startForeground(OVERLAY_NOTIFICATION_ID, buildNotification())
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        setupFocusOverlay()
        setupAppRowOverlay()
        loadInstalledApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            appRowOverlayView?.let { windowManager.removeView(it) }
            focusOverlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.w("OverlayService", "Failed to remove overlay views: ${e.message}", e)
        }
    }

    private fun setupFocusOverlay() {
        // Create a simple circular view for the focus indicator
        focusOverlayView = View(this).apply {
            val size = (50 * resources.displayMetrics.density).toInt()
            layoutParams = WindowManager.LayoutParams(size, size)
            
            // Create circular background
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#80FFFF00")) // Semi-transparent cyan
                setStroke((3 * resources.displayMetrics.density).toInt(), Color.CYAN)
            }
            background = drawable
            visibility = View.GONE // Initially hidden
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        
        try {
            windowManager.addView(focusOverlayView, params)
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to add focus overlay: ${e.message}", e)
        }
    }

    private fun setupAppRowOverlay() {
        appRowOverlayView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        
        try {
            windowManager.addView(appRowOverlayView, params)
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to add app row overlay: ${e.message}", e)
        }
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .mapNotNull {
                try {
                    AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm))
                } catch (e: Exception) { null }
            }
            .sortedBy { it.name.lowercase() }
        
        appList.clear()
        appList.addAll(apps)
        updateAppRowDisplay()
    }

    private fun updateAppRowDisplay() {
        runOnUiThread {
            appRowOverlayView?.removeAllViews()
            
            appList.forEachIndexed { index, app ->
                val iconView = createAppIcon(app, index)
                appRowOverlayView?.addView(iconView)
                
                // Add spacing between icons
                if (index < appList.size - 1) {
                    val spacer = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (16 * resources.displayMetrics.density).toInt(), 1
                        )
                    }
                    appRowOverlayView?.addView(spacer)
                }
            }
        }
    }

    private fun createAppIcon(app: AppInfo, index: Int): ImageView {
        val iconSize = (64 * resources.displayMetrics.density).toInt()
        
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            
            // Create background with border
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (16 * resources.displayMetrics.density)
                setColor(Color.WHITE)
                setStroke(0, Color.TRANSPARENT) // Initially no border
            }
            background = drawable
            
            val padding = (3 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
    }

    fun updateHandPosition(x: Float, y: Float) {
        runOnUiThread {
            focusOverlayView?.let { view ->
                try {
                    val params = view.layoutParams as? WindowManager.LayoutParams
                    if (params == null) return@runOnUiThread
                    
                    val halfW = view.width / 2
                    val halfH = view.height / 2
                    params.x = (x - halfW).toInt()
                    params.y = (y - halfH).toInt()
                    windowManager.updateViewLayout(view, params)
                    updateSelectedApp(x, y)
                } catch (e: Exception) {
                    Log.w("OverlayService", "Failed to update hand position: ${e.message}", e)
                }
            }
        }
    }

    private fun updateSelectedApp(x: Float, y: Float) {
        val appRowView = appRowOverlayView ?: return
        if (appList.isEmpty()) return

        try {
            val screenPos = IntArray(2)
            appRowView.getLocationOnScreen(screenPos)
            val appRowTop = screenPos[1]
            
            if (appRowTop == 0) return

            if (y < appRowTop) {
                if (selectedAppIndex != -1) {
                    updateAppSelection(-1)
                }
                return
            }

            val screenWidth = resources.displayMetrics.widthPixels
            val appIconTotalWidth = (80 * resources.displayMetrics.density).toInt() // 64 + 16 spacing
            val totalRowWidth = appIconTotalWidth * appList.size
            val startOffset = (screenWidth - totalRowWidth) / 2
            val newIndex = ((x - startOffset) / appIconTotalWidth).toInt().coerceIn(0, appList.size - 1)

            if (selectedAppIndex != newIndex) {
                updateAppSelection(newIndex)
            }
        } catch (e: Exception) {
            Log.w("OverlayService", "updateSelectedApp failed: ${e.message}", e)
        }
    }

    private fun updateAppSelection(newIndex: Int) {
        val oldIndex = selectedAppIndex
        selectedAppIndex = newIndex
        
        // Update old selection
        if (oldIndex >= 0 && oldIndex < appRowOverlayView?.childCount ?: 0) {
            val oldView = appRowOverlayView?.getChildAt(oldIndex * 2) as? ImageView // *2 because of spacers
            oldView?.let { updateAppIconAppearance(it, false, false) }
        }
        
        // Update new selection
        if (newIndex >= 0 && newIndex < appRowOverlayView?.childCount ?: 0) {
            val newView = appRowOverlayView?.getChildAt(newIndex * 2) as? ImageView // *2 because of spacers
            newView?.let { updateAppIconAppearance(it, true, isPinching) }
        }
    }

    private fun updateAppIconAppearance(imageView: ImageView, isSelected: Boolean, isPinching: Boolean) {
        val drawable = imageView.background as? GradientDrawable ?: return
        
        val borderColor = when {
            isPinching && isSelected -> Color.GREEN
            isSelected -> Color.CYAN
            else -> Color.TRANSPARENT
        }
        
        val borderWidth = if (isSelected) (3 * resources.displayMetrics.density).toInt() else 0
        drawable.setStroke(borderWidth, borderColor)
        
        // Scale animation
        val scale = if (isSelected) 1.2f else 1.0f
        imageView.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(150)
            .start()
    }

    fun launchSelectedApp() {
        runOnUiThread {
            if (selectedAppIndex == -1) return@runOnUiThread

            val appToLaunch = appList.getOrNull(selectedAppIndex) ?: return@runOnUiThread

            try {
                packageManager.getLaunchIntentForPackage(appToLaunch.packageName)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it)
                } ?: Toast.makeText(this, "Cannot launch ${appToLaunch.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w("OverlayService", "Error launching app ${appToLaunch.packageName}: ${e.message}", e)
                Toast.makeText(this, "Error launching app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun showHandFocus() {
        runOnUiThread {
            showFocus = true
            focusOverlayView?.visibility = View.VISIBLE
        }
    }

    fun hideHandFocus() {
        runOnUiThread {
            showFocus = false
            focusOverlayView?.visibility = View.GONE
            updateAppSelection(-1)
        }
    }

    fun updatePinchState(pinching: Boolean) {
        runOnUiThread {
            isPinching = pinching
            
            // Update focus indicator appearance
            focusOverlayView?.let { view ->
                val scale = if (pinching) 0.8f else 1.0f
                view.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(150)
                    .start()
            }
            
            // Update selected app appearance
            if (selectedAppIndex >= 0) {
                val selectedView = appRowOverlayView?.getChildAt(selectedAppIndex * 2) as? ImageView
                selectedView?.let { updateAppIconAppearance(it, true, pinching) }
            }
        }
    }

    private fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post(action)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                OVERLAY_CHANNEL_ID,
                "Gestura Overlay",
                NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, OVERLAY_CHANNEL_ID)
            .setContentTitle("Gestura Active")
            .setContentText("Overlay cursor is running.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val OVERLAY_CHANNEL_ID = "gestura_overlay_channel"
        private const val OVERLAY_NOTIFICATION_ID = 2
    }
}