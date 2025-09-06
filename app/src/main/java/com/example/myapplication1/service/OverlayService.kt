package com.example.myapplication1.service

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Button
import android.widget.FrameLayout
import android.graphics.Color
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication1.R
import android.content.Context


class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var cursorView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var displayMetrics: DisplayMetrics? = null
    private val prefs by lazy { getSharedPreferences("overlay_targets_prefs", Context.MODE_PRIVATE) }

    // Snap targets and marker management
    private var snapModeEnabled: Boolean = false
    private val snapTargets: MutableList<SnapTarget> = mutableListOf()
    private val markerViews: MutableMap<Int, View> = mutableMapOf()
    private var activeTargetId: Int? = null
    // Basic UI controls
    private var setupButton: View? = null
    private var exitSetupButton: View? = null
    private var isSetupMode: Boolean = false
    private var overlayRoot: FrameLayout? = null
    // Interactive setup state
    private var setupTouchView: View? = null
    private var setupDesiredCount: Int = 0
    private var setupPlacedCount: Int = 0
    private var setupNextId: Int = 1

    private data class SnapTarget(val id: Int, var x: Int, var y: Int)

    inner class LocalBinder : android.os.Binder() {
        fun getService(): OverlayService = this@OverlayService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.d("OverlayService", "onCreate() called")

        // Create notification channel and start foreground first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
        }

        // Check overlay permission after starting foreground service
        if (!Settings.canDrawOverlays(this)) {
            Log.e("OverlayService", "Overlay permission not granted. Service will retry.")
            // Don't stop self immediately, retry after a delay
            handler.postDelayed({
                if (!Settings.canDrawOverlays(this@OverlayService)) {
                    Log.e("OverlayService", "Overlay permission still not granted after retry. Stopping service.")
                    stopSelf()
                } else {
                    Log.d("OverlayService", "Overlay permission granted on retry. Initializing overlay.")
                    initializeOverlay()
                }
            }, 2000) // Retry after 2 seconds
            return
        }

        initializeOverlay()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun initializeOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            // Initialize display metrics safely
            initializeDisplayMetrics()

            setupFloatingCursor()
            // Load saved targets and enable snap mode if available
            loadSnapTargetsFromPrefs()
            if (snapTargets.isNotEmpty()) {
                enableSnapMode(true)
                // Move cursor to first target
                moveCursorToTargetId(snapTargets.first().id)
            }
            // Create setup/exit buttons
            createSetupButton()
            createExitSetupButton()
            Log.d("OverlayService", "Overlay initialized successfully")
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to initialize overlay: ${e.message}", e)
            // Retry initialization after a delay
            handler.postDelayed({
                try {
                    initializeOverlay()
                } catch (retryException: Exception) {
                    Log.e("OverlayService", "Retry initialization failed: ${retryException.message}", retryException)
                    stopSelf()
                }
            }, 3000)
        }
    }

    /**
     * Safely initialize display metrics without requiring visual context
     */
    private fun initializeDisplayMetrics() {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val defaultDisplay = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            // Create a Context tied to the display
            val windowContext = createDisplayContext(defaultDisplay)
            displayMetrics = windowContext.resources.displayMetrics
        } else {
            displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
        }
        Log.d("OverlayService", "Display metrics initialized: ${displayMetrics?.widthPixels}x${displayMetrics?.heightPixels}")
    } catch (e: Exception) {
        Log.w("OverlayService", "Failed to get display metrics, using fallback", e)
        displayMetrics = resources.displayMetrics
    }
}


    override fun onDestroy() {
        super.onDestroy()
        Log.d("OverlayService", "onDestroy() called")
        
        // Remove all pending callbacks
        handler.removeCallbacksAndMessages(null)
        
        cursorView?.let {
            try {
                windowManager.removeView(it)
                Log.d("OverlayService", "Overlay view removed successfully")
            } catch (e: Exception) {
                Log.w("OverlayService", "Error removing overlay: ${e.message}", e)
            }
        }
        cursorView = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupFloatingCursor() {
        try {
            // Double-check overlay permission before adding view
            if (!Settings.canDrawOverlays(this)) {
                Log.e("OverlayService", "Overlay permission lost during setup")
                return
            }

            // Get density safely
            val density = displayMetrics?.density ?: resources.displayMetrics.density
            val boxSize = (60 * density).toInt()

            cursorView = View(this).apply {
                layoutParams = WindowManager.LayoutParams(boxSize, boxSize)
                background = ContextCompat.getDrawable(this@OverlayService, R.drawable.icon_highlight_background)
                visibility = View.VISIBLE // Show by default
            }

            val params = WindowManager.LayoutParams(
                boxSize,
                boxSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100  // Start at a visible position
                y = 200  // Start at a visible position
            }

            windowManager.addView(cursorView, params)
            Log.d("OverlayService", "Floating cursor added to window at position (${params.x}, ${params.y}) with visibility: ${cursorView?.visibility}")
            
            // Start a periodic check to ensure overlay stays visible
            startOverlayHealthCheck()
            
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to add cursor: ${e.message}", e)
            // Retry setup after a delay instead of stopping immediately
            handler.postDelayed({
                try {
                    setupFloatingCursor()
                } catch (retryException: Exception) {
                    Log.e("OverlayService", "Retry setup failed: ${retryException.message}", retryException)
                    stopSelf()
                }
            }, 2000)
        }
    }

    private fun startOverlayHealthCheck() {
        handler.post(object : Runnable {
            override fun run() {
                try {
                    // Check if overlay is still visible and permission is still granted
                    if (cursorView?.visibility != View.VISIBLE) {
                        Log.w("OverlayService", "Overlay became invisible, attempting to restore")
                        cursorView?.visibility = View.VISIBLE
                    }
                    
                    if (!Settings.canDrawOverlays(this@OverlayService)) {
                        Log.e("OverlayService", "Overlay permission lost during health check")
                        stopSelf()
                        return
                    }
                } catch (e: Exception) {
                    Log.w("OverlayService", "Health check failed: ${e.message}", e)
                }
                
                // Run health check every 5 seconds
                handler.postDelayed(this, 5000)
            }
        })
    }

    /**
     * Updates cursor position to follow finger (centers box on coordinates)
     */
    fun updateCursorPosition(x: Float, y: Float) {
        runOnUiThread {
            cursorView?.let { view ->
                try {
                    val params = view.layoutParams as WindowManager.LayoutParams
                    // Use layout params as source of truth for size to avoid 0 width/height during first frames
                    val halfWidth = (params.width / 2f)
                    val halfHeight = (params.height / 2f)

                    // Ensure coordinates are within screen bounds
                    val screenWidth = displayMetrics?.widthPixels ?: resources.displayMetrics.widthPixels
                    val screenHeight = displayMetrics?.heightPixels ?: resources.displayMetrics.heightPixels

                    if (snapModeEnabled && snapTargets.isNotEmpty()) {
                        // Snap to nearest saved target
                        val nearest = findNearestTarget(x.toInt(), y.toInt())
                        activeTargetId = nearest?.id
                        val targetX = (nearest?.x ?: x.toInt()).toFloat()
                        val targetY = (nearest?.y ?: y.toInt()).toFloat()

                        val clampedX = targetX.coerceIn(halfWidth, (screenWidth - halfWidth))
                        val clampedY = targetY.coerceIn(halfHeight, (screenHeight - halfHeight))

                        params.x = (clampedX - halfWidth).toInt()
                        params.y = (clampedY - halfHeight).toInt()
                    } else {
                        val clampedX = x.coerceIn(halfWidth, (screenWidth - halfWidth))
                        val clampedY = y.coerceIn(halfHeight, (screenHeight - halfHeight))

                        params.x = (clampedX - halfWidth).toInt()
                        params.y = (clampedY - halfHeight).toInt()
                    }

                    windowManager.updateViewLayout(view, params)

                    // ✅ Sync position with Accessibility Service
                    GestureAccessibilityService.lastCursorX = (params.x + halfWidth).toInt()
                    GestureAccessibilityService.lastCursorY = (params.y + halfHeight).toInt()
                } catch (e: Exception) {
                    Log.w("OverlayService", "Failed to update position", e)
                }
            }
        }
    }

    /**
     * Shows the cursor when hand is detected
     */
    fun showCursor() {
        runOnUiThread {
            cursorView?.visibility = View.VISIBLE
            Log.d("OverlayService", "Cursor shown")
        }
    }

    /**
     * Hides the cursor when hand is lost
     */
    fun hideCursor() {
        runOnUiThread {
            cursorView?.visibility = View.GONE
            Log.d("OverlayService", "Cursor hidden")
        }
    }

    /**
     * Visual feedback when pinch is active
     */
    fun updatePinchState(isPinching: Boolean) {
        runOnUiThread {
            cursorView?.let { view ->
                try {
                    if (isPinching) {
                        // Pinching: green highlight with scaling
                        view.background = ContextCompat.getDrawable(this, R.drawable.icon_highlight_background)?.apply {
                            setTint(0x8000FF00.toInt()) // Semi-transparent green
                        }
                        view.animate().scaleX(0.8f).scaleY(0.8f).setDuration(100).start()
                    } else {
                        // Default: original background
                        view.background = ContextCompat.getDrawable(this, R.drawable.icon_highlight_background)
                        view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    }
                } catch (e: Exception) {
                    Log.w("OverlayService", "Failed to update pinch state", e)
                }
            }
        }
    }

    /**
     * Called when pinch gesture is detected — launches app at current position
     */
    fun onPinchDetected() {
        runOnUiThread {
            Log.d("OverlayService", "Pinch detected — triggering app launch")

            try {
                // Visual feedback
                cursorView?.let { view ->
                    view.animate()
                        .scaleX(1.4f)
                        .scaleY(1.4f)
                        .setDuration(80)
                        .withEndAction {
                            view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                        }
                        .start()
                }

                // Trigger accessibility service action at snapped or current cursor center
                val params = cursorView?.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    val halfW = (params.width / 2)
                    val halfH = (params.height / 2)
                    val clickX = (params.x + halfW)
                    val clickY = (params.y + halfH)
                    showClickFeedback(clickX.toFloat(), clickY.toFloat())
                    GestureAccessibilityService.instance?.performClickAt(clickX, clickY)
                } else {
                    GestureAccessibilityService.instance?.triggerCurrentAppClick()
                }
            } catch (e: Exception) {
                Log.e("OverlayService", "Error in pinch detection handler", e)
            }
        }
    }

    // ===== Snap Mode and Target Management =====
    fun enableSnapMode(enable: Boolean) {
        snapModeEnabled = enable
        Log.d("OverlayService", "Snap mode: $enable")
    }

    fun moveCursorToTargetId(id: Int) {
        val target = snapTargets.firstOrNull { it.id == id } ?: return
        activeTargetId = id
        updateCursorPosition(target.x.toFloat(), target.y.toFloat())
    }

    fun cycleToNextTarget() {
        if (snapTargets.isEmpty()) return
        val list = snapTargets.sortedBy { it.id }
        val currentIndex = list.indexOfFirst { it.id == activeTargetId }
        val next = if (currentIndex == -1 || currentIndex == list.lastIndex) list.first() else list[currentIndex + 1]
        moveCursorToTargetId(next.id)
    }

    private fun findNearestTarget(x: Int, y: Int): SnapTarget? {
        if (snapTargets.isEmpty()) return null
        val configuredRadius = prefs.getInt("snap_radius", 100)
        val maxDistSquared = (configuredRadius * configuredRadius).toFloat()
        var best: SnapTarget? = null
        var bestDist = Float.MAX_VALUE
        for (t in snapTargets) {
            val dx = (t.x - x).toFloat()
            val dy = (t.y - y).toFloat()
            val d = (dx * dx + dy * dy)
            if (d < bestDist && d <= maxDistSquared) {
                bestDist = d
                best = t
            }
        }
        return best
    }

    fun startTargetSetup(numberOfTargets: Int) {
        // Remove existing markers if any
        hideAndRemoveMarkers()

        val density = displayMetrics?.density ?: resources.displayMetrics.density
        val size = (44 * density).toInt()
        val screenWidth = displayMetrics?.widthPixels ?: resources.displayMetrics.widthPixels
        val screenHeight = displayMetrics?.heightPixels ?: resources.displayMetrics.heightPixels

        // Seed from saved or spaced grid
        if (snapTargets.isEmpty()) {
            for (i in 1..numberOfTargets) {
                val px = (screenWidth * (i.toFloat() / (numberOfTargets + 1))).toInt()
                val py = (screenHeight * 0.4f).toInt()
                snapTargets.add(SnapTarget(i, px, py))
            }
        } else if (snapTargets.size < numberOfTargets) {
            val nextId = (snapTargets.maxOfOrNull { it.id } ?: 0) + 1
            for (i in 0 until (numberOfTargets - snapTargets.size)) {
                val px = (screenWidth * (0.2f + 0.15f * i)).toInt().coerceIn(0, screenWidth)
                val py = (screenHeight * 0.5f).toInt()
                snapTargets.add(SnapTarget(nextId + i, px, py))
            }
        }

        snapTargets.forEach { target -> addMarkerView(target) }
    }

    fun finishTargetSetup() {
        hideAndRemoveMarkers()
        enableSnapMode(true)
        // Move cursor to first saved target if available
        if (snapTargets.isNotEmpty()) moveCursorToTargetId(snapTargets.first().id)
    }

    private fun hideAndRemoveMarkers() {
        markerViews.values.forEach { v ->
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        markerViews.clear()
    }

    // ===== Simple UI controls for setup mode =====
    private fun createSetupButton() {
        if (setupButton != null) return
        val btn = Button(this).apply {
            text = "Setup Positions"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            setOnClickListener { toggleSetupMode(true) }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 120
        }

        try {
            windowManager.addView(btn, params)
            setupButton = btn
        } catch (e: Exception) {
            Log.w("OverlayService", "Failed to add setup button: ${e.message}")
        }
    }

    private fun createExitSetupButton() {
        if (exitSetupButton != null) return
        val btn = Button(this).apply {
            text = "Exit Setup"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#F44336"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { toggleSetupMode(false) }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 200
        }

        try {
            windowManager.addView(btn, params)
            exitSetupButton = btn
        } catch (e: Exception) {
            Log.w("OverlayService", "Failed to add exit button: ${e.message}")
        }
    }

    private fun toggleSetupMode(enable: Boolean) {
        isSetupMode = enable
        if (enable) {
            setupButton?.visibility = View.GONE
            exitSetupButton?.visibility = View.VISIBLE
            // Default to placing 5 targets; can be parameterized by caller
            startInteractiveTargetSetup(5)
            hideCursor()
        } else {
            finishInteractiveSetup()
            setupButton?.visibility = View.VISIBLE
            exitSetupButton?.visibility = View.GONE
            showCursor()
        }
    }

    private fun showClickFeedback(x: Float, y: Float) {
        try {
            val view = View(this).apply {
                setBackgroundColor(Color.parseColor("#4CAF50"))
                alpha = 0.8f
            }
            val params = WindowManager.LayoutParams(
                60,
                60,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = (x - 30).toInt()
                this.y = (y - 30).toInt()
            }
            windowManager.addView(view, params)
            view.animate().scaleX(1.5f).scaleY(1.5f).alpha(0f).setDuration(300).withEndAction {
                try { windowManager.removeView(view) } catch (_: Exception) {}
            }.start()
        } catch (e: Exception) {
            Log.w("OverlayService", "Feedback failed: ${e.message}")
        }
    }

    // Create a draggable numbered marker for a target
    private fun addMarkerView(target: SnapTarget) {
        val density = displayMetrics?.density ?: resources.displayMetrics.density
        val size = (44 * density).toInt()
        val screenWidth = displayMetrics?.widthPixels ?: resources.displayMetrics.widthPixels
        val screenHeight = displayMetrics?.heightPixels ?: resources.displayMetrics.heightPixels

        val tv = TextView(this).apply {
            text = target.id.toString()
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            background = ContextCompat.getDrawable(this@OverlayService, R.drawable.focus_circle_background)
            gravity = Gravity.CENTER
        }

        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (target.x - size / 2).coerceIn(0, screenWidth - size)
            y = (target.y - size / 2).coerceIn(0, screenHeight - size)
        }

        tv.setOnTouchListener(object : View.OnTouchListener {
            var lastX = 0
            var lastY = 0
            var startRawX = 0f
            var startRawY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = lp.x
                        lastY = lp.y
                        startRawX = event.rawX
                        startRawY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startRawX).toInt()
                        val dy = (event.rawY - startRawY).toInt()
                        lp.x = (lastX + dx).coerceIn(0, screenWidth - size)
                        lp.y = (lastY + dy).coerceIn(0, screenHeight - size)
                        windowManager.updateViewLayout(v, lp)
                        // Update model center
                        target.x = lp.x + size / 2
                        target.y = lp.y + size / 2
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Persist after drop
                        saveSnapTargetsToPrefs()
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(tv, lp)
        markerViews[target.id] = tv
    }

    // Interactive tap-to-place setup
    fun startInteractiveTargetSetup(desiredCount: Int) {
        hideAndRemoveMarkers()
        enableSnapMode(false)
        setupDesiredCount = desiredCount.coerceAtLeast(1)
        setupPlacedCount = 0
        // Continue numbering from existing highest id
        setupNextId = (snapTargets.maxOfOrNull { it.id } ?: 0) + 1

        // Full-screen touch capture view
        if (setupTouchView != null) {
            try { windowManager.removeView(setupTouchView) } catch (_: Exception) {}
            setupTouchView = null
        }

        val screenWidth = displayMetrics?.widthPixels ?: resources.displayMetrics.widthPixels
        val screenHeight = displayMetrics?.heightPixels ?: resources.displayMetrics.heightPixels

        val lp = WindowManager.LayoutParams(
            screenWidth,
            screenHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        setupTouchView = View(this).apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val x = event.rawX.toInt()
                    val y = event.rawY.toInt()
                    placeNextInteractiveTarget(x, y)
                    true
                } else true
            }
        }
        windowManager.addView(setupTouchView, lp)
        Log.d("OverlayService", "Interactive setup started for $setupDesiredCount targets")
    }

    private fun placeNextInteractiveTarget(x: Int, y: Int) {
        if (setupPlacedCount >= setupDesiredCount) return
        val id = setupNextId
        val target = SnapTarget(id, x, y)
        snapTargets.add(target)
        addMarkerView(target)
        saveSnapTargetsToPrefs()
        setupPlacedCount += 1
        setupNextId += 1
        if (setupPlacedCount >= setupDesiredCount) {
            finishInteractiveSetup()
        }
    }

    fun finishInteractiveSetup() {
        // Remove touch layer
        setupTouchView?.let { v ->
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        setupTouchView = null
        enableSnapMode(true)
        if (snapTargets.isNotEmpty()) moveCursorToTargetId(snapTargets.first().id)
        Log.d("OverlayService", "Interactive setup finished with ${snapTargets.size} targets")
    }

    private fun saveSnapTargetsToPrefs() {
        val encoded = snapTargets.joinToString("|") { "${it.id},${it.x},${it.y}" }
        prefs.edit().putString("targets", encoded).apply()
        Log.d("OverlayService", "Saved targets: $encoded")
    }

    private fun loadSnapTargetsFromPrefs() {
        snapTargets.clear()
        val encoded = prefs.getString("targets", null) ?: return
        encoded.split('|').forEach { item ->
            val parts = item.split(',')
            if (parts.size == 3) {
                val id = parts[0].toIntOrNull()
                val x = parts[1].toIntOrNull()
                val y = parts[2].toIntOrNull()
                if (id != null && x != null && y != null) {
                    snapTargets.add(SnapTarget(id, x, y))
                }
            }
        }
        Log.d("OverlayService", "Loaded targets: ${snapTargets.size}")
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

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gestura Overlay Service",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Provides gesture control overlay"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gestura Running")
            .setContentText("Following your finger for gesture control")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gestura_overlay"
        private const val NOTIFICATION_ID = 2
    }
}