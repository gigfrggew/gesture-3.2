// MainActivity.kt
package com.example.myapplication1

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.example.myapplication1.service.CameraService
import com.example.myapplication1.service.OverlayService
import com.example.myapplication1.service.GestureAccessibilityService
import com.example.myapplication1.ui.theme.MyApplication1Theme

class MainActivity : ComponentActivity() {

    private var hasOverlayPermission by mutableStateOf(false)
    private var hasCameraPermission by mutableStateOf(false)
    private var hasAccessibilityPermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(false)

    // Track if receiver was registered so unregister doesn't throw
    private var isReceiverRegistered = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted && allPermissionsGranted(checkCamera = false)) {
            startServices()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        if (isGranted && allPermissionsGranted(checkNotification = false)) {
            startServices()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // After returning from settings, re-check the permission
        checkOverlayPermission()
        if (hasOverlayPermission && allPermissionsGranted(checkOverlay = false)) {
            startServices()
        }
    }

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.example.myapplication1.CAMERA_SERVICE_STATUS") {
                val started = intent.getBooleanExtra("started", false)
                if (!started) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Camera service failed. Is the camera in use by another app?",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAllPermissions()
        registerServiceStatusReceiver()

        setContent {
            MyApplication1Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions when returning to the app, especially for Accessibility
        checkAllPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        // guard unregister to avoid IllegalArgumentException
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(serviceStatusReceiver)
                isReceiverRegistered = false
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to unregister receiver: ${e.message}", e)
            }
        }
    }

    // Use the Activity's registerReceiver for simpler semantics and track registration
    private fun registerServiceStatusReceiver() {
        try {
            val filter = IntentFilter("com.example.myapplication1.CAMERA_SERVICE_STATUS")
            registerReceiver(serviceStatusReceiver, filter)
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to register service status receiver: ${e.message}", e)
            isReceiverRegistered = false
        }
    }

    private fun checkAllPermissions() {
        checkCameraPermission()
        checkOverlayPermission()
        checkAccessibilityPermission()
        checkNotificationPermission()
    }

    private fun checkCameraPermission() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PermissionChecker.PERMISSION_GRANTED
    }

    private fun checkOverlayPermission() {
        hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun checkAccessibilityPermission() {
        // Robustly detect whether our accessibility service is enabled by parsing Settings.Secure
        hasAccessibilityPermission = try {
            val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (enabledServices.isNullOrBlank()) {
                false
            } else {
                val expected = "$packageName/.service.GestureAccessibilityService"
                enabledServices.split(":").any {
                    it.equals(expected, ignoreCase = true) ||
                            it.endsWith(".service.GestureAccessibilityService", ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun checkNotificationPermission() {
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PermissionChecker.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (enabledServices.isNullOrBlank()) return false
            val expected = "$packageName/.service.GestureAccessibilityService"
            enabledServices.split(":").any {
                it.equals(expected, ignoreCase = true) ||
                        it.endsWith(".service.GestureAccessibilityService", ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Find 'Gestura' in the list and enable it.", Toast.LENGTH_LONG).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun allPermissionsGranted(
        checkCamera: Boolean = true,
        checkOverlay: Boolean = true,
        checkAccessibility: Boolean = true,
        checkNotification: Boolean = true
    ): Boolean {
        val cameraOk = if (checkCamera) hasCameraPermission else true
        val overlayOk = if (checkOverlay) hasOverlayPermission else true
        val accessibilityOk = if (checkAccessibility) isAccessibilityServiceEnabled() else true
        val notificationOk = if (checkNotification) hasNotificationPermission else true
        return cameraOk && overlayOk && accessibilityOk && notificationOk
    }

    private fun startServices() {
        if (!allPermissionsGranted()) {
            Toast.makeText(this, "Not all permissions are granted.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // Start OverlayService first as CameraService depends on it
            val overlayIntent = Intent(this, OverlayService::class.java)
            ContextCompat.startForegroundService(this, overlayIntent)

            // CameraService has its own startForeground call
            val cameraIntent = Intent(this, CameraService::class.java)
            ContextCompat.startForegroundService(this, cameraIntent)

            Log.d("MainActivity", "Services started")
            Toast.makeText(this, "Gestura service started!", Toast.LENGTH_SHORT).show()

            // Transition to Home so gesture browsing starts over the launcher
            GestureAccessibilityService.instance?.goHome()

        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start services", e)
            Toast.makeText(this, "Failed to start services. Please try again.", Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun MainScreen() {
        // Use the mutableState properties directly, they will trigger recomposition
        val allGranted by remember {
            derivedStateOf {
                hasCameraPermission && hasOverlayPermission && hasAccessibilityPermission && hasNotificationPermission
            }
        }

        // This effect will re-read the state variables whenever they change
        LaunchedEffect(
            hasCameraPermission,
            hasOverlayPermission,
            hasAccessibilityPermission,
            hasNotificationPermission
        ) {
            // This block is just to trigger recomposition, no action needed here
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Gestura", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(32.dp))

            PermissionCard("Camera", "For hand gesture detection", hasCameraPermission) { requestCameraPermission() }
            Spacer(modifier = Modifier.height(16.dp))
            PermissionCard("Draw Over Other Apps", "To show the gesture cursor", hasOverlayPermission) { requestOverlayPermission() }
            Spacer(modifier = Modifier.height(16.dp))
            PermissionCard("Accessibility", "To perform actions like launching apps", hasAccessibilityPermission) { requestAccessibilityPermission() }
            Spacer(modifier = Modifier.height(16.dp))
            PermissionCard("Notifications", "Required for background services", hasNotificationPermission) { requestNotificationPermission() }

            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = { startServices() },
                enabled = allGranted,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (allGranted) "Start Gesture Control" else "Grant All Permissions", fontSize = 16.sp)
            }
        }
    }

    @Composable
    private fun PermissionCard(
        title: String,
        description: String,
        isGranted: Boolean,
        onRequestClick: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(description, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.width(16.dp))
                if (isGranted) {
                    Text("✓ Granted", color = Color(0xFF008000), fontWeight = FontWeight.Bold)
                } else {
                    Button(onClick = onRequestClick) {
                        Text("Grant")
                    }
                }
            }
        }
    }
}
