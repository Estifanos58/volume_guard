package com.example

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.core.GuardManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Main Activity for Volume Guard.
 *
 * Minimal, ultra-lightweight native Android View interface:
 * - App title & controls with zero Compose overhead.
 * - Guard toggle switch.
 * - Clear operational status indicator.
 * - Current media volume readout.
 * - Accessibility Settings button when service is not connected.
 */
class MainActivity : ComponentActivity() {

    private lateinit var switchGuard: Switch
    private lateinit var textStatus: TextView
    private lateinit var textVolume: TextView
    private lateinit var buttonSettings: Button

    private var isUpdatingSwitchProgrammatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Find views
        switchGuard = findViewById(R.id.switch_guard)
        textStatus = findViewById(R.id.text_operational_status)
        textVolume = findViewById(R.id.text_current_volume)
        buttonSettings = findViewById(R.id.button_open_settings)

        // Initialize GuardManager
        val guardManager = GuardManager.instance
        guardManager.initialize(this)

        // Switch toggle listener
        switchGuard.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingSwitchProgrammatically) {
                guardManager.setDesiredGuardEnabled(this, isChecked)
            }
        }

        // Accessibility settings button listener
        buttonSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        // Observe reactive StateFlows using lifecycleScope
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    guardManager.desiredGuardEnabled.collect { desired ->
                        if (switchGuard.isChecked != desired) {
                            isUpdatingSwitchProgrammatically = true
                            switchGuard.isChecked = desired
                            isUpdatingSwitchProgrammatically = false
                        }
                    }
                }

                launch {
                    combine(
                        guardManager.desiredGuardEnabled,
                        guardManager.isServiceConnected,
                        guardManager.isOperationalActive
                    ) { desired, connected, operational ->
                        Triple(desired, connected, operational)
                    }.collect { (desired, connected, operational) ->
                        when {
                            operational -> {
                                textStatus.text = "PROTECTED (Active)"
                                textStatus.setTextColor(Color.parseColor("#4CAF50")) // Green
                                buttonSettings.visibility = View.GONE
                            }
                            !connected -> {
                                textStatus.text = "SERVICE DISCONNECTED"
                                textStatus.setTextColor(Color.parseColor("#FF9800")) // Orange
                                buttonSettings.visibility = View.VISIBLE
                            }
                            !desired -> {
                                textStatus.text = "GUARD OFF"
                                textStatus.setTextColor(Color.parseColor("#9E9E9E")) // Grey
                                buttonSettings.visibility = View.GONE
                            }
                        }
                    }
                }

                launch {
                    combine(
                        guardManager.currentMediaVolume,
                        guardManager.maxMediaVolume
                    ) { current, max ->
                        Pair(current, max)
                    }.collect { (current, max) ->
                        textVolume.text = "Media Volume: $current / $max"
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GuardManager.instance.syncSystemVolume(this)
    }
}
