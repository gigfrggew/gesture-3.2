package com.example.myapplication1.activity

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication1.R
import com.example.myapplication1.service.OverlayService

class OverlaySettingsActivity : AppCompatActivity() {

    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var positionsRecyclerView: RecyclerView
    private lateinit var adapter: PositionsAdapter
    private lateinit var snapRadiusSeekBar: SeekBar
    private lateinit var snapRadiusText: TextView
    private lateinit var markerCountSpinner: Spinner

    private val PREFS_NAME = "overlay_positions"
    private val REQUEST_OVERLAY_PERMISSION = 1000

    data class SavedPosition(
        val id: Int,
        val x: Float,
        val y: Float,
        var isEnabled: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_settings)

        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        initViews()
        setupRecyclerView()
        loadSettings()

        checkOverlayPermission()
    }

    private fun initViews() {
        positionsRecyclerView = findViewById(R.id.positionsRecyclerView)
        snapRadiusSeekBar = findViewById(R.id.snapRadiusSeekBar)
        snapRadiusText = findViewById(R.id.snapRadiusText)
        markerCountSpinner = findViewById(R.id.markerCountSpinner)

        snapRadiusSeekBar.max = 200
        snapRadiusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val radius = progress + 50
                snapRadiusText.text = "Snap Radius: ${radius}px"
                if (fromUser) {
                    sharedPrefs.edit().putInt("snap_radius", radius).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val markerCountAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.marker_count_options,
            android.R.layout.simple_spinner_item
        )
        markerCountAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        markerCountSpinner.adapter = markerCountAdapter
        markerCountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val count = (position + 1) * 2
                sharedPrefs.edit().putInt("marker_count", count).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnTestOverlay).setOnClickListener {
            startOverlayService()
        }

        findViewById<Button>(R.id.btnClearAll).setOnClickListener {
            clearAllPositions()
        }

        findViewById<Button>(R.id.btnResetDefaults).setOnClickListener {
            resetToDefaults()
        }
    }

    private fun setupRecyclerView() {
        adapter = PositionsAdapter { position ->
            togglePositionEnabled(position)
        }
        positionsRecyclerView.layoutManager = LinearLayoutManager(this)
        positionsRecyclerView.adapter = adapter
    }

    private fun loadSettings() {
        val savedRadius = sharedPrefs.getInt("snap_radius", 100)
        snapRadiusSeekBar.progress = savedRadius - 50
        snapRadiusText.text = "Snap Radius: ${savedRadius}px"

        val savedCount = sharedPrefs.getInt("marker_count", 5)
        markerCountSpinner.setSelection((savedCount / 2) - 1)

        loadSavedPositions()
    }

    private fun loadSavedPositions() {
        val positions = mutableListOf<SavedPosition>()
        val markerCount = sharedPrefs.getInt("marker_count", 5)

        for (i in 1..markerCount) {
            if (sharedPrefs.contains("marker_${i}_x")) {
                val position = SavedPosition(
                    id = i,
                    x = sharedPrefs.getFloat("marker_${i}_x", 0f),
                    y = sharedPrefs.getFloat("marker_${i}_y", 0f),
                    isEnabled = sharedPrefs.getBoolean("marker_${i}_enabled", true)
                )
                positions.add(position)
            }
        }

        adapter.updatePositions(positions)
    }

    private fun togglePositionEnabled(position: SavedPosition) {
        position.isEnabled = !position.isEnabled
        sharedPrefs.edit()
            .putBoolean("marker_${position.id}_enabled", position.isEnabled)
            .apply()
        adapter.notifyDataSetChanged()
    }

    private fun clearAllPositions() {
        sharedPrefs.edit().clear().apply()
        adapter.updatePositions(emptyList())
        Toast.makeText(this, "All positions cleared", Toast.LENGTH_SHORT).show()
    }

    private fun resetToDefaults() {
        sharedPrefs.edit()
            .putInt("snap_radius", 100)
            .putInt("marker_count", 5)
            .apply()
        loadSettings()
        Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
    }

    private fun startOverlayService() {
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, OverlayService::class.java)
            startService(intent)
            Toast.makeText(this, "Overlay service started", Toast.LENGTH_SHORT).show()
        } else {
            requestOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class PositionsAdapter(
        private val onToggleClick: (SavedPosition) -> Unit
    ) : RecyclerView.Adapter<PositionsAdapter.ViewHolder>() {

        private var positions = listOf<SavedPosition>()

        fun updatePositions(newPositions: List<SavedPosition>) {
            positions = newPositions
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_position, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(positions[position])
        }

        override fun getItemCount() = positions.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val markerText: TextView = itemView.findViewById(R.id.markerText)
            private val positionText: TextView = itemView.findViewById(R.id.positionText)
            private val enabledSwitch: Switch = itemView.findViewById(R.id.enabledSwitch)

            fun bind(position: SavedPosition) {
                markerText.text = "Marker ${position.id}"
                positionText.text = "Position: (${position.x.toInt()}, ${position.y.toInt()})"
                enabledSwitch.setOnCheckedChangeListener(null)
                enabledSwitch.isChecked = position.isEnabled
                enabledSwitch.setOnCheckedChangeListener { _, _ ->
                    onToggleClick(position)
                }
                itemView.alpha = if (position.isEnabled) 1.0f else 0.6f
            }
        }
    }
}


