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
import com.example.BuildConfig
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
 * Rules:
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
 * 3. Reactive Volume Monitors:
 *    - Broadcast receiver and ContentObserver are active ONLY while operational protection
 *      is actually ACTIVE. When Guard is OFF or Service is disconnected, all background
 *      monitors are completely unregistered to preserve battery.
 */
class VolumeGuardAccessibilityService : AccessibilityService() {

    private var volumeChangeReceiver: VolumeChangeReceiver? = null
    private var volumeContentObserver: VolumeContentObserver? = null

    public override fun onServiceConnected() {
        super.onServiceConnected()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onServiceConnected()")
        }

        // Configure key filtering capability
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info

        // Connect to GuardManager and provide dynamic monitor control
        GuardManager.instance.onServiceConnected(this) { active ->
            setMonitorsActive(active)
        }
    }

    public override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        val isOperational = GuardManager.instance.isOperationalActive.value
        if (!isOperational) {
            // Normal Android operation when Guard is OFF or not operational
            return super.onKeyEvent(event)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // User's intentional emergency/override button
                if (event.action == KeyEvent.ACTION_DOWN) {
                    GuardManager.instance.onPhysicalVolumeUp(this)
                }
                // CRITICAL: Do NOT consume the Volume Up event.
                // Return false so Android processes the user's Volume Up normally and raises the volume!
                false
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Volume Down must NOT disable the guard. Volume remains 0.
                if (event.action == KeyEvent.ACTION_DOWN) {
                    GuardManager.instance.onPhysicalVolumeDown(this)
                }
                // Consume event to prevent unnecessary OS processing since volume is already 0
                true
            }

            else -> {
                super.onKeyEvent(event)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Explicitly no-op: We do NOT inspect screen contents, passwords, or windows
    }

    override fun onInterrupt() {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onInterrupt()")
        }
    }

    public override fun onUnbind(intent: Intent?): Boolean {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onUnbind()")
        }
        setMonitorsActive(false)
        GuardManager.instance.onServiceDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onDestroy()")
        }
        setMonitorsActive(false)
        GuardManager.instance.onServiceDisconnected()
    }

    /**
     * Activates or deactivates reactive volume monitors dynamically based on operational state.
     */
    private fun setMonitorsActive(active: Boolean) {
        if (active) {
            registerMonitors()
        } else {
            unregisterMonitors()
        }
    }

    private fun registerMonitors() {
        try {
            if (volumeChangeReceiver == null) {
                volumeChangeReceiver = VolumeChangeReceiver()
                val filter = IntentFilter(VolumeChangeReceiver.VOLUME_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(volumeChangeReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(volumeChangeReceiver, filter)
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "VolumeChangeReceiver registered")
                }
            }

            if (volumeContentObserver == null) {
                volumeContentObserver = VolumeContentObserver(this)
                contentResolver.registerContentObserver(
                    Settings.System.CONTENT_URI,
                    true,
                    volumeContentObserver!!
                )
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "VolumeContentObserver registered")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Failed to register volume monitors", e)
            }
        }
    }

    private fun unregisterMonitors() {
        try {
            volumeChangeReceiver?.let {
                unregisterReceiver(it)
                volumeChangeReceiver = null
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "VolumeChangeReceiver unregistered")
                }
            }
            volumeContentObserver?.let {
                contentResolver.unregisterContentObserver(it)
                volumeContentObserver = null
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "VolumeContentObserver unregistered")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error unregistering volume monitors", e)
            }
        }
    }

    companion object {
        private const val TAG = "VolumeGuardService"
    }
}
