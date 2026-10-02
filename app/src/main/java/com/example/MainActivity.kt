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
 * Minimal native View interface:
 * - Guard toggle switch.
 * - Concise operational/service status.
 * - Current media volume.
 * - Accessibility Settings button when service is unavailable.
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

        switchGuard = findViewById(R.id.switch_guard)
        textStatus = findViewById(R.id.text_operational_status)
        textVolume = findViewById(R.id.text_current_volume)
        buttonSettings = findViewById(R.id.button_open_settings)

        val guardManager = GuardManager.instance
        guardManager.initialize(this)

        switchGuard.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingSwitchProgrammatically) {
                guardManager.setDesiredGuardEnabled(this, isChecked)
            }
        }

        buttonSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

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
                                textStatus.setTextColor(Color.parseColor("#4CAF50"))
                                buttonSettings.visibility = View.GONE
                            }
                            !connected -> {
                                textStatus.text = "SERVICE DISCONNECTED"
                                textStatus.setTextColor(Color.parseColor("#FF9800"))
                                buttonSettings.visibility = View.VISIBLE
                            }
                            !desired -> {
                                textStatus.text = "GUARD OFF"
                                textStatus.setTextColor(Color.parseColor("#9E9E9E"))
                                buttonSettings.visibility = View.GONE
                            }
                        }
                    }
                }

                launch {
                    guardManager.currentMediaVolume.collect { current ->
                        textVolume.text = "Media Volume: $current"
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
