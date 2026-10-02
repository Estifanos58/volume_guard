package com.example.core

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interface for registering and unregistering reactive volume monitors on demand.
 * This avoids strong references to the AccessibilityService and prevents memory leaks.
 */
fun interface VolumeMonitorController {
    fun setMonitorsActive(active: Boolean)
}

/**
 * Centralized, thread-safe coordinator for Volume Guard state and actions.
 *
 * Core Architectural Rules:
 * 1. Desired State vs. Operational State:
 *    - [desiredGuardEnabled]: User preference persisted in SharedPreferences.
 *    - [isServiceConnected]: Hardware AccessibilityService connection status.
 *    - [isOperationalActive]: True ONLY when desiredGuardEnabled == true AND isServiceConnected == true.
 * 2. Never claim hardware protection is active when AccessibilityService is disconnected.
 * 3. Reactive monitors (broadcast receiver, content observer, audio playback callback)
 *    are active ONLY when isOperationalActive == true.
 * 4. Target volume is strictly 0. No previous or initial volume is ever tracked or restored.
 * 5. Diagnostic logging is enabled exclusively in debug builds ([BuildConfig.DEBUG]).
 */
class GuardManager private constructor() {

    private val _desiredGuardEnabled = MutableStateFlow(false)
    val desiredGuardEnabled: StateFlow<Boolean> = _desiredGuardEnabled.asStateFlow()

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _isOperationalActive = MutableStateFlow(false)
    val isOperationalActive: StateFlow<Boolean> = _isOperationalActive.asStateFlow()

    private val _currentMediaVolume = MutableStateFlow(0)
    val currentMediaVolume: StateFlow<Int> = _currentMediaVolume.asStateFlow()

    private val _maxMediaVolume = MutableStateFlow(15)
    val maxMediaVolume: StateFlow<Int> = _maxMediaVolume.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    // Flag to prevent recursive loops when our own setStreamVolume triggers system callbacks
    private val isSelfAdjustingVolume = AtomicBoolean(false)

    @Volatile
    private var monitorController: VolumeMonitorController? = null

    /**
     * Initializes state from preferences and system audio service.
     */
    @Synchronized
    fun initialize(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            _maxMediaVolume.value = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            _currentMediaVolume.value = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }

        val prefs = GuardPreferences.getInstance(context)
        _desiredGuardEnabled.value = prefs.isGuardEnabled
        updateOperationalState(context)
    }

    /**
     * Called when the AccessibilityService connects.
     */
    @Synchronized
    fun onServiceConnected(context: Context, controller: VolumeMonitorController) {
        monitorController = controller
        _isServiceConnected.value = true

        val prefs = GuardPreferences.getInstance(context)
        _desiredGuardEnabled.value = prefs.isGuardEnabled
        if (BuildConfig.DEBUG) {
            logDebug("AccessibilityService connected (desired=${prefs.isGuardEnabled})")
        }

        updateOperationalState(context)

        if (_isOperationalActive.value) {
            forceMediaVolumeZero(context, reason = "Service connected with desired ON")
        }
    }

    /**
     * Called when the AccessibilityService disconnects or unbinds.
     */
    @Synchronized
    fun onServiceDisconnected() {
        if (BuildConfig.DEBUG) {
            logDebug("AccessibilityService disconnected -> operational protection deactivated")
        }
        _isServiceConnected.value = false
        monitorController?.setMonitorsActive(false)
        monitorController = null
        updateOperationalState(null)
    }

    /**
     * Sets the user's desired guard state.
     */
    @Synchronized
    fun setDesiredGuardEnabled(context: Context, enabled: Boolean) {
        _desiredGuardEnabled.value = enabled
        GuardPreferences.getInstance(context).isGuardEnabled = enabled
        if (BuildConfig.DEBUG) {
            logDebug("User desired guard changed to $enabled")
        }

        updateOperationalState(context)

        if (enabled && _isOperationalActive.value) {
            forceMediaVolumeZero(context, reason = "Guard activated by user")
        }
        // If disabled: volume remains whatever it is at this instant (do NOT restore or modify)
    }

    /**
     * Ultra-fast hot-path handler for physical Volume Up key event.
     *
     * Rules:
     * 1. Detect Volume Up.
     * 2. Set desired guard = OFF immediately.
     * 3. Update persistent state.
     * 4. Deactivate operational protection and monitors.
     * 5. Do NOT programmatically modify volume.
     * 6. Caller returns false so Android receives and processes Volume Up normally.
     */
    fun onPhysicalVolumeUpFast(context: Context) {
        _desiredGuardEnabled.value = false
        _isOperationalActive.value = false
        monitorController?.setMonitorsActive(false)
        GuardPreferences.getInstance(context).isGuardEnabled = false
        if (BuildConfig.DEBUG) {
            logDebug("Physical Volume Up intercepted -> Disengaged guard")
        }
    }

    /**
     * Ultra-fast hot-path handler for physical Volume Down key event.
     *
     * Rules:
     * 1. Keep Guard ON.
     * 2. Enforce volume remains 0.
     * 3. Caller consumes event (returns true).
     */
    fun onPhysicalVolumeDownFast(context: Context) {
        forceMediaVolumeZero(context, reason = "Physical Volume Down pressed")
    }

    /**
     * Called by reactive volume monitors (broadcast receiver, content observer,
     * or playback callback) when a media volume change is observed.
     */
    fun onExternalVolumeChanged(context: Context, newVolume: Int) {
        _currentMediaVolume.value = newVolume
        if (isSelfAdjustingVolume.get()) {
            return
        }

        if (_isOperationalActive.value && newVolume > 0) {
            if (BuildConfig.DEBUG) {
                logDebug("Reactive volume change detected ($newVolume) -> Clamping to 0")
            }
            forceMediaVolumeZero(context, reason = "Reactive external correction")
        }
    }

    /**
     * Forces AudioManager.STREAM_MUSIC to volume 0 and applies stream mute.
     */
    fun forceMediaVolumeZero(context: Context, reason: String = "") {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            isSelfAdjustingVolume.set(true)
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                0,
                0 // 0 flags: no UI slider popup
            )
            // Extra OEM reinforcement: apply stream mute flag if supported
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                0
            )
            _currentMediaVolume.value = 0
            if (BuildConfig.DEBUG && reason.isNotEmpty()) {
                logDebug("Volume 0 enforced ($reason)")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error setting stream volume to 0", e)
            }
        } finally {
            isSelfAdjustingVolume.set(false)
        }
    }

    fun syncSystemVolume(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        _currentMediaVolume.value = vol
        if (_isOperationalActive.value && vol > 0) {
            forceMediaVolumeZero(context, reason = "Sync check violation")
        }
    }

    /**
     * Checks if the Accessibility Service is configured in system settings.
     */
    fun isAccessibilityServiceEnabledInSettings(context: Context): Boolean {
        if (_isServiceConnected.value) return true

        val expectedComponentName = ComponentName(
            context,
            "com.example.service.VolumeGuardAccessibilityService"
        ).flattenToString()

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true) ||
                componentName.contains("VolumeGuardAccessibilityService", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    private fun updateOperationalState(context: Context?) {
        val previousState = _isOperationalActive.value
        val newState = _desiredGuardEnabled.value && _isServiceConnected.value
        _isOperationalActive.value = newState

        if (previousState != newState) {
            monitorController?.setMonitorsActive(newState)
            if (BuildConfig.DEBUG) {
                logDebug("Operational protection active: $newState (desired=${_desiredGuardEnabled.value}, connected=${_isServiceConnected.value})")
            }
        }
    }

    private fun logDebug(message: String) {
        if (!BuildConfig.DEBUG) return

        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val formatted = "[$timestamp] $message"
        val current = _recentLogs.value.toMutableList()
        if (current.size >= 30) {
            current.removeAt(0)
        }
        current.add(formatted)
        _recentLogs.value = current
        Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "VolumeGuard"

        val instance: GuardManager by lazy { GuardManager() }
    }
}
