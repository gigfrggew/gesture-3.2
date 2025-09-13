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
import android.widget.Toast
import kotlin.random.Random


class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var cursorView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var displayMetrics: DisplayMetrics? = null
    private val prefs by lazy { getSharedPreferences("overlay_targets_prefs", Context.MODE_PRIVATE) }

    // Setup mode for numbered blocks
    private var isSetupMode: Boolean = false
    private val numberedBlocks: MutableList<NumberedBlock> = mutableListOf()
    private var setupButton: View? = null
    private var saveButton: View? = null
    private var undoButton: View? = null
    
    // Smooth cursor movement
    private var lastUpdateTime = 0L
    private val smoothingFactor = 0.3f // Lower = smoother movement
    private var targetX = 0f
    private var targetY = 0f
    private var currentX = 0f
    private var currentY = 0f

    private data class NumberedBlock(
        val id: Int,
        var x: Float,
        var y: Float,
        var isLocked: Boolean = false,
        var view: View? = null
    )

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
            setupControlButtons()
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
        
        // Clean up cursor view
        cursorView?.let {
            try {
                windowManager.removeView(it)
                Log.d("OverlayService", "Overlay view removed successfully")
            } catch (e: Exception) {
                Log.w("OverlayService", "Error removing overlay: ${e.message}", e)
            }
        }
        cursorView = null
        
        // Clean up control buttons
        setupButton?.let { try { windowManager.removeView(it) } catch (e: Exception) { Log.w("OverlayService", "Error removing setup button: ${e.message}") } }
        saveButton?.let { try { windowManager.removeView(it) } catch (e: Exception) { Log.w("OverlayService", "Error removing save button: ${e.message}") } }
        undoButton?.let { try { windowManager.removeView(it) } catch (e: Exception) { Log.w("OverlayService", "Error removing undo button: ${e.message}") } }
        
        // Clean up numbered blocks
        hideNumberedBlocks()
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
     * Updates cursor position to follow finger with smooth movement and snap to numbered blocks
     */
    fun updateCursorPosition(x: Float, y: Float) {
        runOnUiThread {
            cursorView?.let { view ->
                try {
                    val params = view.layoutParams as WindowManager.LayoutParams
                    val halfWidth = (params.width / 2f)
                    val halfHeight = (params.height / 2f)

                    // Ensure coordinates are within screen bounds
                    val screenWidth = displayMetrics?.widthPixels ?: resources.displayMetrics.widthPixels
                    val screenHeight = displayMetrics?.heightPixels ?: resources.displayMetrics.heightPixels

                    // Check for snapping to numbered blocks
                    val nearestBlock = findNearestNumberedBlock(x, y)
                    val snapRadius = 100f // pixels
                    
                    val finalTargetX: Float
                    val finalTargetY: Float
                    
                    if (nearestBlock != null && !isSetupMode) {
                        val distance = kotlin.math.sqrt(
                            ((nearestBlock.x - x) * (nearestBlock.x - x) + (nearestBlock.y - y) * (nearestBlock.y - y)).toDouble()
                        ).toFloat()
                        
                        if (distance <= snapRadius) {
                            // Snap to the numbered block
                            finalTargetX = nearestBlock.x.coerceIn(halfWidth, (screenWidth - halfWidth))
                            finalTargetY = nearestBlock.y.coerceIn(halfHeight, (screenHeight - halfHeight))
                        } else {
                            // Normal movement
                            finalTargetX = x.coerceIn(halfWidth, (screenWidth - halfWidth))
                            finalTargetY = y.coerceIn(halfHeight, (screenHeight - halfHeight))
                        }
                    } else {
                        // Normal movement
                        finalTargetX = x.coerceIn(halfWidth, (screenWidth - halfWidth))
                        finalTargetY = y.coerceIn(halfHeight, (screenHeight - halfHeight))
                    }

                    // Set target position
                    targetX = finalTargetX
                    targetY = finalTargetY

                    // Smooth movement interpolation
                    val currentTime = System.currentTimeMillis()
                    val deltaTime = currentTime - lastUpdateTime
                    lastUpdateTime = currentTime

                    if (deltaTime > 0) {
                        val lerpFactor = kotlin.math.min(1f, smoothingFactor * (deltaTime / 16f)) // 16ms = 60fps
                        currentX += (targetX - currentX) * lerpFactor
                        currentY += (targetY - currentY) * lerpFactor
                    } else {
                        currentX = targetX
                        currentY = targetY
                    }

                    // Update cursor position
                    params.x = (currentX - halfWidth).toInt()
                    params.y = (currentY - halfHeight).toInt()

                    windowManager.updateViewLayout(view, params)

                    // Sync position with Accessibility Service
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

    // ===== Setup Mode for Numbered Blocks =====
    private fun setupControlButtons() {
        try {
            val density = displayMetrics?.density ?: resources.displayMetrics.density
            val buttonSize = (80 * density).toInt()
            
            // Setup Button
            setupButton = createControlButton("Setup", buttonSize, 0, 0) { 
                toggleSetupMode() 
            }
            
            // Save Button (initially hidden)
            saveButton = createControlButton("Save", buttonSize, 0, buttonSize + 20) { 
                saveNumberedBlocks() 
            }
            
            // Undo Button (initially hidden)
            undoButton = createControlButton("Undo", buttonSize, buttonSize + 20, buttonSize + 20) { 
                undoLastBlock() 
            }
            
            saveButton?.visibility = View.GONE
            undoButton?.visibility = View.GONE
            
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to setup control buttons: ${e.message}", e)
        }
    }
    
    private fun createControlButton(text: String, size: Int, x: Int, y: Int, onClick: () -> Unit): View? {
        return try {
            val button = Button(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#4CAF50"))
                textSize = 12f
                
                // Make button touchable
                setOnClickListener { onClick() }
            }
            
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
            
            windowManager.addView(button, params)
            Log.d("OverlayService", "Control button '$text' added at ($x, $y)")
            button
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to create control button '$text': ${e.message}", e)
            null
        }
    }
    
    private fun toggleSetupMode() {
        isSetupMode = !isSetupMode
        
        if (isSetupMode) {
            showSaveUndoButtons()
            createNumberedBlocks()
        } else {
            hideSaveUndoButtons()
            hideNumberedBlocks()
        }
        
        Log.d("OverlayService", "Setup mode: $isSetupMode")
    }
    
    private fun showSaveUndoButtons() {
        saveButton?.visibility = View.VISIBLE
        undoButton?.visibility = View.VISIBLE
    }
    
    private fun hideSaveUndoButtons() {
        saveButton?.visibility = View.GONE
        undoButton?.visibility = View.GONE
    }
    
    private fun createNumberedBlocks() {
        // Create 5 numbered blocks initially
        for (i in 1..5) {
            val block = NumberedBlock(
                id = i,
                x = 100f + (i * 100),
                y = 300f,
                isLocked = false
            )
            
            val blockView = createNumberedBlockView(block)
            block.view = blockView
            numberedBlocks.add(block)
        }
    }
    
    private fun createNumberedBlockView(block: NumberedBlock): View? {
        return try {
            val density = displayMetrics?.density ?: resources.displayMetrics.density
            val size = (60 * density).toInt()
            
            val view = TextView(this).apply {
                text = block.id.toString()
                setTextColor(Color.BLACK)
                gravity = android.view.Gravity.CENTER
                textSize = 16f
                setBackgroundColor(Color.parseColor("#FFD700")) // Yellow color
                setPadding(0, 0, 0, 0)
                
                // Make it draggable when in setup mode
                setOnTouchListener { _, event ->
                    if (isSetupMode && !block.isLocked) {
                        handleBlockDrag(event, block)
                        true
                    } else {
                        false
                    }
                }
            }
            
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = block.x.toInt()
                y = block.y.toInt()
            }
            
            windowManager.addView(view, params)
            Log.d("OverlayService", "Numbered block ${block.id} created at (${block.x}, ${block.y})")
            view
        } catch (e: Exception) {
            Log.e("OverlayService", "Failed to create numbered block view: ${e.message}", e)
            null
        }
    }
    
    private fun handleBlockDrag(event: MotionEvent, block: NumberedBlock): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Start dragging
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Update block position
                block.x = event.rawX
                block.y = event.rawY
                
                // Update view position
                block.view?.let { view ->
                    val params = view.layoutParams as WindowManager.LayoutParams
                    params.x = block.x.toInt()
                    params.y = block.y.toInt()
                    windowManager.updateViewLayout(view, params)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                // Check if block is over an app icon and lock it
                checkAndLockBlock(block)
                return true
            }
        }
        return false
    }
    
    private fun checkAndLockBlock(block: NumberedBlock) {
        // Simulate checking if block is over an app icon
        // In a real implementation, you'd check against actual app icon positions
        val isOverApp = Random.nextFloat() < 0.3f // 30% chance for demo
        // 30% chance for demo
        
        if (isOverApp) {
            lockBlock(block)
        }
    }
    
    private fun lockBlock(block: NumberedBlock) {
        block.isLocked = true
        
        // Make the block transparent
        block.view?.let { view ->
            view.alpha = 0.3f
            view.setBackgroundColor(Color.parseColor("#80000000")) // Semi-transparent black
        }
        
        Toast.makeText(this, "Block ${block.id} locked to app", Toast.LENGTH_SHORT).show()
        Log.d("OverlayService", "Block ${block.id} locked at (${block.x}, ${block.y})")
    }
    
    private fun findNearestNumberedBlock(x: Float, y: Float): NumberedBlock? {
        if (numberedBlocks.isEmpty()) return null
        
        var nearest: NumberedBlock? = null
        var minDistance = Float.MAX_VALUE
        
        for (block in numberedBlocks) {
            if (block.isLocked) {
                val distance = kotlin.math.sqrt(
                    ((block.x - x) * (block.x - x) + (block.y - y) * (block.y - y)).toDouble()
                ).toFloat()
                
                if (distance < minDistance) {
                    minDistance = distance
                    nearest = block
                }
            }
        }
        
        return nearest
    }
    
    private fun hideNumberedBlocks() {
        numberedBlocks.forEach { block ->
            block.view?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    Log.w("OverlayService", "Failed to remove numbered block view: ${e.message}")
                }
            }
        }
        numberedBlocks.clear()
    }
    
    private fun saveNumberedBlocks() {
        // Save the current positions of numbered blocks
        val editor = prefs.edit()
        numberedBlocks.forEach { block ->
            editor.putFloat("block_${block.id}_x", block.x)
            editor.putFloat("block_${block.id}_y", block.y)
            editor.putBoolean("block_${block.id}_locked", block.isLocked)
        }
        editor.apply()
        
        Toast.makeText(this, "Numbered blocks saved", Toast.LENGTH_SHORT).show()
        Log.d("OverlayService", "Numbered blocks saved")
    }
    
    private fun undoLastBlock() {
        if (numberedBlocks.isNotEmpty()) {
            val lastBlock = numberedBlocks.removeAt(numberedBlocks.size - 1)
            lastBlock.view?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    Log.w("OverlayService", "Failed to remove last block: ${e.message}")
                }
            }
            Toast.makeText(this, "Last block removed", Toast.LENGTH_SHORT).show()
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