package com.example.core

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized, thread-safe coordinator for Volume Guard state and actions.
 *
 * Responsibilities:
 * 1. Maintain single source of truth for Guard state (ON / OFF).
 * 2. Track connection status of the AccessibilityService.
 * 3. Immediately clamp media volume to 0 when Guard is enabled or violated.
 * 4. Handle physical Volume Up emergency disengage and Volume Down lock.
 * 5. Provide diagnostic logging for debug inspection without high overhead.
 */
class GuardManager private constructor() {

    private val _guardState = MutableStateFlow(GuardState.OFF)
    val guardState: StateFlow<GuardState> = _guardState.asStateFlow()

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _currentMediaVolume = MutableStateFlow(0)
    val currentMediaVolume: StateFlow<Int> = _currentMediaVolume.asStateFlow()

    private val _maxMediaVolume = MutableStateFlow(15)
    val maxMediaVolume: StateFlow<Int> = _maxMediaVolume.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    // Flag to prevent recursive loops when our own setStreamVolume triggers system callbacks
    private val isSelfAdjustingVolume = AtomicBoolean(false)

    // Weak/transient reference to active AccessibilityService
    @Volatile
    private var activeService: AccessibilityService? = null

    @Synchronized
    fun initialize(context: Context) {
        val prefs = GuardPreferences.getInstance(context)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            _maxMediaVolume.value = max
            _currentMediaVolume.value = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }

        // Restore persisted state only if accessibility service is actually enabled
        val isServiceConfigured = isAccessibilityServiceEnabledInSettings(context)
        if (prefs.isGuardEnabled && isServiceConfigured) {
            _guardState.value = GuardState.ON
            forceMediaVolumeZero(context, reason = "Reboot/Startup restore")
        } else {
            _guardState.value = GuardState.OFF
            if (prefs.isGuardEnabled && !isServiceConfigured) {
                // If service was disabled by user in OS settings, sync preference to OFF
                prefs.isGuardEnabled = false
                addLog("Startup: Service not enabled in OS, guard defaulted to OFF")
            }
        }
    }

    /**
     * Enables Volume Guard.
     * Returns true if successfully enabled, false if accessibility service is missing.
     */
    @Synchronized
    fun enableGuard(context: Context): Boolean {
        if (!_isServiceConnected.value && !isAccessibilityServiceEnabledInSettings(context)) {
            addLog("Enable rejected: Accessibility service not enabled")
            return false
        }

        _guardState.value = GuardState.ON
        GuardPreferences.getInstance(context).isGuardEnabled = true
        forceMediaVolumeZero(context, reason = "Guard activated by user")
        addLog("Guard enabled: Media volume locked to 0")
        return true
    }

    /**
     * Disables Volume Guard.
     *
     * IMPORTANT:
     * Does NOT restore any previous volume.
     * Does NOT touch or change the volume.
     * The phone volume remains exactly what it is at this instant.
     */
    @Synchronized
    fun disableGuard(context: Context, reason: String) {
        if (_guardState.value == GuardState.OFF) return

        _guardState.value = GuardState.OFF
        GuardPreferences.getInstance(context).isGuardEnabled = false
        addLog("Guard disabled: $reason")
    }

    /**
     * Called when a physical Volume Up key event is intercepted by AccessibilityService.
     *
     * Requirements:
     * 1. Detect Volume Up.
     * 2. Set guard state = OFF immediately.
     * 3. Update persistent state.
     * 4. Do NOT programmatically set volume.
     * 5. Allow Android to process the physical Volume Up event normally (caller returns false).
     */
    @Synchronized
    fun onPhysicalVolumeUp(context: Context) {
        if (_guardState.value == GuardState.ON) {
            disableGuard(context, reason = "Physical Volume Up override")
            addLog("Physical Volume Up intercepted -> Guard OFF (disengaged)")
        }
    }

    /**
     * Called when a physical Volume Down key event is intercepted by AccessibilityService.
     *
     * Requirements:
     * 1. Detect Volume Down.
     * 2. Keep Guard ON.
     * 3. Enforce volume remains 0.
     * 4. Caller consumes event (returns true) so unnecessary system UI/work is skipped.
     */
    @Synchronized
    fun onPhysicalVolumeDown(context: Context) {
        if (_guardState.value == GuardState.ON) {
            forceMediaVolumeZero(context, reason = "Physical Volume Down pressed")
            addLog("Physical Volume Down intercepted -> Guard remains ON, volume remains 0")
        }
    }

    /**
     * Called when an external application or system event attempts to change media volume.
     */
    fun onExternalVolumeChanged(context: Context, newVolume: Int) {
        _currentMediaVolume.value = newVolume
        if (isSelfAdjustingVolume.get()) {
            return
        }

        if (_guardState.value == GuardState.ON && newVolume > 0) {
            addLog("External volume change detected ($newVolume) -> Correcting to 0")
            forceMediaVolumeZero(context, reason = "External volume correction")
        }
    }

    /**
     * Immediately forces AudioManager.STREAM_MUSIC to volume 0.
     */
    fun forceMediaVolumeZero(context: Context, reason: String = "") {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            isSelfAdjustingVolume.set(true)
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                0,
                0 // 0 flags: no UI slider popup, zero overhead
            )
            _currentMediaVolume.value = 0
            if (reason.isNotEmpty()) {
                Log.d(TAG, "Enforced volume 0 ($reason)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting stream volume to 0", e)
        } finally {
            // Reset suppression flag shortly after OS propagates change
            isSelfAdjustingVolume.set(false)
        }
    }

    fun syncSystemVolume(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        _currentMediaVolume.value = vol
        if (_guardState.value == GuardState.ON && vol > 0) {
            forceMediaVolumeZero(context, reason = "Sync check violation")
        }
    }

    fun setAccessibilityServiceConnected(connected: Boolean, service: AccessibilityService?) {
        _isServiceConnected.value = connected
        activeService = service
        if (connected) {
            addLog("Accessibility service connected")
        } else {
            addLog("Accessibility service disconnected")
        }
    }

    /**
     * Checks if the Accessibility Service is enabled in system settings.
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

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val formatted = "[$timestamp] $message"
        val current = _recentLogs.value.toMutableList()
        if (current.size >= 30) {
            current.removeAt(0)
        }
        current.add(formatted)
        _recentLogs.value = current
        Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "VolumeGuard"

        val instance: GuardManager by lazy { GuardManager() }
    }
}
