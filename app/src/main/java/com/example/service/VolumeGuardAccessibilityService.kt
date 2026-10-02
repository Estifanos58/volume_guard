package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.BuildConfig
import com.example.core.GuardManager
import com.example.observer.VolumeContentObserver
import com.example.receiver.VolumeChangeReceiver

/**
 * Ultra-low overhead Accessibility Service responsible for physical volume-key filtering.
 *
 * Performance and Correctness Optimizations:
 * 1. [isOperationalFast] volatile boolean ensures onKeyEvent() has O(1) execution with
 *    zero object allocations, zero string formatting, and zero StateFlow hops on the hot path.
 * 2. Physical Volume Up (Override):
 *    - Immediately flips [isOperationalFast] to false.
 *    - Updates persistent state asynchronously.
 *    - Returns FALSE so Android continues processing Volume Up normally and raises volume.
 * 3. Physical Volume Down:
 *    - Enforces volume 0.
 *    - Returns TRUE to consume the key event and prevent unwanted volume sliders.
 * 4. Reactive Volume Monitors:
 *    - Dynamically registered ONLY while operational protection is active.
 *    - Includes:
 *      * Hardened VolumeChangeReceiver (queries system AudioManager directly).
 *      * Narrowly scoped VolumeContentObserver (observed only for media volume URIs).
 *      * AudioPlaybackCallback (API 26+) catching rogue apps at playback spin-up.
 */
class VolumeGuardAccessibilityService : AccessibilityService() {

    @Volatile
    var isOperationalFast: Boolean = false
        private set

    private var volumeChangeReceiver: VolumeChangeReceiver? = null
    private var volumeContentObserver: VolumeContentObserver? = null
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null

    public override fun onServiceConnected() {
        super.onServiceConnected()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onServiceConnected()")
        }

        // Configure key filtering capability with minimal footprint
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
        // Hot-path filter: immediately pass through if null or guard is inactive
        if (event == null || !isOperationalFast) {
            return false
        }

        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // Synchronously disable local fast flag to eliminate latency
                isOperationalFast = false
                GuardManager.instance.onPhysicalVolumeUpFast(this)
            }
            // CRITICAL: Return false so Android processes physical Volume Up normally
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                GuardManager.instance.onPhysicalVolumeDownFast(this)
            }
            // Consume physical Volume Down so volume remains 0 and system work is skipped
            return true
        }

        return super.onKeyEvent(event)
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
    fun setMonitorsActive(active: Boolean) {
        isOperationalFast = active
        if (active) {
            registerMonitors()
        } else {
            unregisterMonitors()
        }
    }

    private fun registerMonitors() {
        try {
            // 1. Hardened broadcast receiver for VOLUME_CHANGED_ACTION
            if (volumeChangeReceiver == null) {
                volumeChangeReceiver = VolumeChangeReceiver()
                val filter = IntentFilter(VolumeChangeReceiver.VOLUME_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(volumeChangeReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(volumeChangeReceiver, filter)
                }
            }

            // 2. Narrowly scoped ContentObserver for music volume settings
            if (volumeContentObserver == null) {
                volumeContentObserver = VolumeContentObserver(this)
                val musicUri = Settings.System.getUriFor("volume_music_speaker")
                    ?: Settings.System.getUriFor("volume_music")
                if (musicUri != null) {
                    contentResolver.registerContentObserver(musicUri, false, volumeContentObserver!!)
                } else {
                    contentResolver.registerContentObserver(Settings.System.CONTENT_URI, false, volumeContentObserver!!)
                }
            }

            // 3. AudioPlaybackCallback (API 26+) for reactive media start detection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioPlaybackCallback == null) {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null) {
                    audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
                        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>?) {
                            if (isOperationalFast) {
                                val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                                if (currentVol > 0) {
                                    GuardManager.instance.onExternalVolumeChanged(
                                        this@VolumeGuardAccessibilityService,
                                        currentVol
                                    )
                                }
                            }
                        }
                    }
                    am.registerAudioPlaybackCallback(audioPlaybackCallback!!, Handler(Looper.getMainLooper()))
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error registering volume monitors", e)
            }
        }
    }

    private fun unregisterMonitors() {
        try {
            volumeChangeReceiver?.let {
                unregisterReceiver(it)
                volumeChangeReceiver = null
            }
            volumeContentObserver?.let {
                contentResolver.unregisterContentObserver(it)
                volumeContentObserver = null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioPlaybackCallback != null) {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioPlaybackCallback?.let { am?.unregisterAudioPlaybackCallback(it) }
                audioPlaybackCallback = null
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
