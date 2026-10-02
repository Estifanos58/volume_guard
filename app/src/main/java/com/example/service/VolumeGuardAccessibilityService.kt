package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.example.core.GuardManager
import com.example.observer.VolumeContentObserver
import com.example.receiver.VolumeChangeReceiver

/**
 * Core Accessibility Service responsible for physical volume-key filtering.
 *
 * Android Architecture Note:
 * Android allows an AccessibilityService with [flagRequestFilterKeyEvents] to intercept
 * raw hardware key events BEFORE they reach the WindowManager or the AudioService.
 *
 * Rules implemented here:
 * 1. Physical Volume Up while Guard is ON:
 *    - Immediately disengages Guard (State -> OFF).
 *    - Returns FALSE so Android continues processing the physical Volume Up normally
 *      and raises the phone's volume.
 *
 * 2. Physical Volume Down while Guard is ON:
 *    - Keeps Guard ON.
 *    - Enforces volume 0.
 *    - Returns TRUE to consume the event and prevent unnecessary system UI/slider popups.
 *
 * 3. Reactive Monitoring:
 *    - While running, keeps reactive broadcast receiver and content observer active
 *      to guard against programmatic volume increases by third-party apps.
 */
class VolumeGuardAccessibilityService : AccessibilityService() {

    private var volumeChangeReceiver: VolumeChangeReceiver? = null
    private var volumeContentObserver: VolumeContentObserver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "VolumeGuardAccessibilityService connected")

        // Configure key filtering capability
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info

        // Register reactive programmatic monitors
        registerVolumeMonitors()

        // Notify manager
        GuardManager.instance.setAccessibilityServiceConnected(true, this)

        // If guard was enabled in preferences, enforce volume zero immediately
        if (GuardManager.instance.guardState.value.isEnabled) {
            GuardManager.instance.forceMediaVolumeZero(this, reason = "Accessibility service connected")
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        val isGuardOn = GuardManager.instance.guardState.value.isEnabled
        if (!isGuardOn) {
            // Normal Android operation when Guard is OFF
            return super.onKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // User's intentional emergency/override button
                if (event.action == KeyEvent.ACTION_DOWN) {
                    GuardManager.instance.onPhysicalVolumeUp(this)
                }
                // CRITICAL: Do NOT consume the Volume Up event.
                // Return false so Android processes the user's Volume Up normally and raises the volume!
                return false
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Volume Down must NOT disable the guard. Volume remains 0.
                if (event.action == KeyEvent.ACTION_DOWN) {
                    GuardManager.instance.onPhysicalVolumeDown(this)
                }
                // Consume event to prevent unnecessary OS processing since volume is already 0
                return true
            }

            else -> {
                return super.onKeyEvent(event)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Explicitly no-op: We do NOT inspect screen contents, passwords, or windows
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Accessibility service unbound")
        unregisterVolumeMonitors()
        GuardManager.instance.setAccessibilityServiceConnected(false, null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
        unregisterVolumeMonitors()
        GuardManager.instance.setAccessibilityServiceConnected(false, null)
    }

    private fun registerVolumeMonitors() {
        try {
            if (volumeChangeReceiver == null) {
                volumeChangeReceiver = VolumeChangeReceiver()
                val filter = IntentFilter(VolumeChangeReceiver.VOLUME_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(volumeChangeReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(volumeChangeReceiver, filter)
                }
            }

            if (volumeContentObserver == null) {
                volumeContentObserver = VolumeContentObserver(this)
                contentResolver.registerContentObserver(
                    Settings.System.CONTENT_URI,
                    true,
                    volumeContentObserver!!
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register volume monitors", e)
        }
    }

    private fun unregisterVolumeMonitors() {
        try {
            volumeChangeReceiver?.let {
                unregisterReceiver(it)
                volumeChangeReceiver = null
            }
            volumeContentObserver?.let {
                contentResolver.unregisterContentObserver(it)
                volumeContentObserver = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering volume monitors", e)
        }
    }

    companion object {
        private const val TAG = "VolumeGuardService"
    }
}
