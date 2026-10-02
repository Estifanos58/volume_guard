package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.core.GuardManager
import com.example.ui.VolumeGuardScreen
import com.example.ui.theme.MyApplicationTheme

/**
 * Main Activity for Volume Guard.
 *
 * Minimal single-screen interface to:
 * 1. View and toggle Guard state.
 * 2. Check Accessibility Service connectivity.
 * 3. Inspect real-time volume level and event logs.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize state and check accessibility permissions
        GuardManager.instance.initialize(this)

        setContent {
            MyApplicationTheme {
                VolumeGuardScreen(guardManager = GuardManager.instance)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Resync current system media volume and check accessibility settings
        GuardManager.instance.syncSystemVolume(this)
    }
}
