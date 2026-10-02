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
 * Interface for registering and unregistering the volume monitors on demand.
 */
fun interface VolumeMonitorController {
    fun setMonitorsActive(active: Boolean)
}

/**
 * Centralized, thread-safe coordinator for Volume Guard state and actions.
 *
 * Streamlined Architecture:
 * 1. Cached [AudioManager] reference eliminates repeated getSystemService() lookups.
 * 2. Pure reactive correction: exactly one setStreamVolume(STREAM_MUSIC, 0, 0) on violation.
 * 3. Physical Volume Up has ZERO audio Binder calls and returns false immediately.
 * 4. Physical Volume Down checks [lastKnownMediaVolume] and performs ZERO audio Binder calls
 *    when volume is already 0.
 * 5. Disabling Guard never restores any previous volume.
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

    // Cached AudioManager reference to avoid repeated getSystemService() lookups
    @Volatile
    var audioManager: AudioManager? = null
        private set

    // Volatile cached media volume for O(1) checks without Binder IPC
    @Volatile
    var lastKnownMediaVolume: Int = 0
        private set

    @Volatile
    private var monitorController: VolumeMonitorController? = null

    @Synchronized
    fun initialize(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val am = audioManager
        if (am != null) {
            _maxMediaVolume.value = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            _currentMediaVolume.value = current
            lastKnownMediaVolume = current
        }

        val prefs = GuardPreferences.getInstance(context)
        _desiredGuardEnabled.value = prefs.isGuardEnabled
        updateOperationalState(context)
    }

    @Synchronized
    fun setAudioManager(am: AudioManager?) {
        this.audioManager = am
    }

    @Synchronized
    fun onServiceConnected(context: Context, controller: VolumeMonitorController) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        monitorController = controller
        _isServiceConnected.value = true

        val prefs = GuardPreferences.getInstance(context)
        _desiredGuardEnabled.value = prefs.isGuardEnabled
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "AccessibilityService connected (desired=${prefs.isGuardEnabled})")
        }

        updateOperationalState(context)

        if (_isOperationalActive.value) {
            forceMediaVolumeZero(context, reason = "Service connected with desired ON")
        }
    }

    @Synchronized
    fun onServiceDisconnected(context: Context? = null) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "AccessibilityService disconnected -> operational protection deactivated")
        }
        _isServiceConnected.value = false
        monitorController?.setMonitorsActive(false)
        monitorController = null
        updateOperationalState(null)
    }

    @Synchronized
    fun setDesiredGuardEnabled(context: Context, enabled: Boolean) {
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
        _desiredGuardEnabled.value = enabled
        GuardPreferences.getInstance(context).isGuardEnabled = enabled
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "User desired guard changed to $enabled")
        }

        updateOperationalState(context)

        if (enabled && _isOperationalActive.value) {
            forceMediaVolumeZero(context, reason = "Guard activated by user")
        }
        // When disabled: leaves volume untouched at whatever value Android currently has
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
            Log.d(TAG, "Physical Volume Up -> Disengaged guard")
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
     * Executes exactly one setStreamVolume(STREAM_MUSIC, 0, 0) call using cached AudioManager.
     */
    fun onMusicVolumeIncreaseDetected(context: Context, newVolume: Int, detector: String = "broadcast") {
        lastKnownMediaVolume = newVolume
        _currentMediaVolume.value = newVolume
        if (_isOperationalActive.value && newVolume > 0) {
            forceMediaVolumeZero(context, reason = "Reactive correction via $detector ($newVolume -> 0)")
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
     * Forces AudioManager.STREAM_MUSIC to volume 0 using cached AudioManager.
     */
    fun forceMediaVolumeZero(context: Context, reason: String = "") {
        val am = audioManager ?: (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager) ?: return
        try {
            am.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                0,
                0 // 0 flags: no UI slider popup
            )
            lastKnownMediaVolume = 0
            _currentMediaVolume.value = 0
            if (BuildConfig.DEBUG && reason.isNotEmpty()) {
                Log.d(TAG, "Volume 0 enforced ($reason)")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error setting stream volume to 0", e)
            }
        }
    }

    fun syncSystemVolume(context: Context) {
        val am = audioManager ?: (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager) ?: return
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
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
                Log.d(TAG, "Operational protection: $newState (desired=${_desiredGuardEnabled.value}, connected=${_isServiceConnected.value})")
            }
        }
    }

    companion object {
        private const val TAG = "VolumeGuard"

        val instance: GuardManager by lazy { GuardManager() }
    }
}
