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
 * Latency & Resource Optimizations:
 * 1. [lastKnownMediaVolume]: Thread-safe volatile cache avoiding unnecessary getStreamVolume()
 *    or setStreamVolume() calls during physical Volume Down presses.
 * 2. Removed `isSelfAdjustingVolume` lock: Broadcasts with volume == 0 are naturally no-ops.
 * 3. Single `setStreamVolume(STREAM_MUSIC, 0, 0)` call without redundant explicit mute.
 * 4. Asynchronous/non-blocking monitor cleanup on physical Volume Up.
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
            forceMediaVolumeZero(context, reason = "Service connected with desired ON")
        }
    }

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
    }

    /**
     * Ultra-fast hot-path handler for physical Volume Up key event.
     * Disengages guard immediately, updates persistent state, and returns without audio IPCs.
     */
    fun onPhysicalVolumeUpFast(context: Context) {
        _desiredGuardEnabled.value = false
        _isOperationalActive.value = false
        lastKnownMediaVolume = 0
        GuardPreferences.getInstance(context).isGuardEnabled = false
        if (BuildConfig.DEBUG) {
            logDebug("Physical Volume Up -> Disengaged guard")
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
     */
    fun onMusicVolumeIncreaseDetected(context: Context, newVolume: Int) {
        lastKnownMediaVolume = newVolume
        _currentMediaVolume.value = newVolume
        if (_isOperationalActive.value && newVolume > 0) {
            if (BuildConfig.DEBUG) {
                logDebug("Reactive volume increase ($newVolume) -> Clamping to 0")
            }
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
     * Forces AudioManager.STREAM_MUSIC to volume 0 using a single setStreamVolume call.
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
