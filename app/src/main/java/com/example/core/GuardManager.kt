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

/**
 * Interface for registering and unregistering reactive volume monitors on demand.
 */
fun interface VolumeMonitorController {
    fun setMonitorsActive(active: Boolean)
}

/**
 * Centralized, thread-safe coordinator for Volume Guard state and actions.
 *
 * Latency & Audio Leak Prevention Architecture:
 * 1. Proactive Mute on Operational State Entry:
 *    - When Guard enters active operational protection, it immediately issues
 *      [setStreamVolume(STREAM_MUSIC, 0, 0)] AND [adjustStreamVolume(ADJUST_MUTE)].
 *    - This pre-mutes the hardware audio output so if another app programmatically
 *      raises the volume index, audio leakage is minimized/prevented before correction.
 * 2. Single [setStreamVolume(STREAM_MUSIC, 0, 0)] for reactive corrections:
 *    - Ordinary reactive corrections do NOT reissue ADJUST_MUTE; exactly one call is made.
 * 3. Proactive Mute Lifted on Disengage:
 *    - When Guard is disabled via UI, physical Volume Up, or service disconnect,
 *      [adjustStreamVolume(ADJUST_UNMUTE)] is called to restore normal volume responsiveness.
 * 4. Zero audio IPC on physical Volume Down when cached volume is 0.
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

    // Volatile cached media volume for O(1) checks without Binder IPC
    @Volatile
    var lastKnownMediaVolume: Int = 0
        private set

    @Volatile
    private var monitorController: VolumeMonitorController? = null

    @Synchronized
    fun initialize(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            _maxMediaVolume.value = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            _currentMediaVolume.value = current
            lastKnownMediaVolume = current
        }

        val prefs = GuardPreferences.getInstance(context)
        _desiredGuardEnabled.value = prefs.isGuardEnabled
        updateOperationalState(context)
    }

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
            proactiveMute(context)
        }
    }

    @Synchronized
    fun onServiceDisconnected(context: Context? = null) {
        if (BuildConfig.DEBUG) {
            logDebug("AccessibilityService disconnected -> operational protection deactivated")
        }
        _isServiceConnected.value = false
        monitorController?.setMonitorsActive(false)
        monitorController = null
        if (context != null) {
            liftProactiveMute(context)
        }
        updateOperationalState(null)
    }

    @Synchronized
    fun setDesiredGuardEnabled(context: Context, enabled: Boolean) {
        _desiredGuardEnabled.value = enabled
        GuardPreferences.getInstance(context).isGuardEnabled = enabled
        if (BuildConfig.DEBUG) {
            logDebug("User desired guard changed to $enabled")
        }

        updateOperationalState(context)

        if (enabled && _isOperationalActive.value) {
            proactiveMute(context)
        } else if (!enabled) {
            liftProactiveMute(context)
        }
    }

    /**
     * Ultra-fast hot-path handler for physical Volume Up key event.
     * Disengages guard immediately, lifts proactive mute, and returns without audio IPCs.
     */
    fun onPhysicalVolumeUpFast(context: Context) {
        _desiredGuardEnabled.value = false
        _isOperationalActive.value = false
        lastKnownMediaVolume = 0
        GuardPreferences.getInstance(context).isGuardEnabled = false
        liftProactiveMute(context)
        if (BuildConfig.DEBUG) {
            logDebug("Physical Volume Up -> Disengaged guard, mute lifted")
        }
    }

    /**
     * Ultra-fast hot-path handler for physical Volume Down key event.
     * Avoids audio Binder call if volume is already known to be 0.
     */
    fun onPhysicalVolumeDownFast(context: Context) {
        if (lastKnownMediaVolume > 0) {
            forceMediaVolumeZero(context, reason = "Physical Volume Down nonzero correction")
        }
        // If lastKnownMediaVolume == 0: ZERO audio IPC, immediately return true
    }

    /**
     * Primary reactive volume-change handler.
     * Called when a volume increase is reported by the broadcast receiver or fallback observer.
     * Executes exactly one setStreamVolume(STREAM_MUSIC, 0, 0) call.
     */
    fun onMusicVolumeIncreaseDetected(context: Context, newVolume: Int) {
        lastKnownMediaVolume = newVolume
        _currentMediaVolume.value = newVolume
        if (_isOperationalActive.value && newVolume > 0) {
            forceMediaVolumeZero(context, reason = "Reactive correction ($newVolume -> 0)")
        }
    }

    /**
     * Updates the volatile cached volume without performing any audio IPC.
     */
    fun updateCachedVolume(volume: Int) {
        lastKnownMediaVolume = volume
        _currentMediaVolume.value = volume
    }

    /**
     * Proactively sets volume to 0 AND mutes STREAM_MUSIC when Guard enters operational state.
     * Prevents audible leaks before another app attempts an increase.
     */
    fun proactiveMute(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            lastKnownMediaVolume = 0
            _currentMediaVolume.value = 0
            if (BuildConfig.DEBUG) {
                logDebug("Proactive mute applied to STREAM_MUSIC")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error applying proactive mute", e)
            }
        }
    }

    /**
     * Unmutes STREAM_MUSIC when Guard is turned OFF, Volume Up is pressed, or service disconnects.
     */
    fun liftProactiveMute(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            if (BuildConfig.DEBUG) {
                logDebug("Proactive mute lifted on STREAM_MUSIC")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error lifting proactive mute", e)
            }
        }
    }

    /**
     * Forces AudioManager.STREAM_MUSIC to volume 0 using exactly one setStreamVolume call.
     */
    fun forceMediaVolumeZero(context: Context, reason: String = "") {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                0,
                0 // 0 flags: no UI slider popup
            )
            lastKnownMediaVolume = 0
            _currentMediaVolume.value = 0
            if (BuildConfig.DEBUG && reason.isNotEmpty()) {
                logDebug("Volume 0 enforced ($reason)")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error setting stream volume to 0", e)
            }
        }
    }

    fun syncSystemVolume(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        lastKnownMediaVolume = vol
        _currentMediaVolume.value = vol
        if (_isOperationalActive.value && vol > 0) {
            forceMediaVolumeZero(context, reason = "Sync check violation")
        }
    }

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
                logDebug("Operational protection: $newState (desired=${_desiredGuardEnabled.value}, connected=${_isServiceConnected.value})")
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
