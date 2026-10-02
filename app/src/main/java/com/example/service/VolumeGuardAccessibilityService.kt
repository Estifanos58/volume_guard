package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.BuildConfig
import com.example.core.GuardManager
import com.example.observer.VolumeContentObserver
import com.example.receiver.VolumeChangeReceiver

/**
 * Ultra-low latency Accessibility Service responsible for physical volume-key filtering
 * and deterministic multi-channel reactive volume protection.
 *
 * Protection Channels (all on single HandlerThread):
 * 1. Primary Broadcast: [VolumeChangeReceiver] for instant event extras reading.
 * 2. Targeted Fallback: [VolumeContentObserver] observing media volume setting URIs.
 * 3. Continuous 10 ms Safety Sampler: Runs continuously while Guard is operational,
 *    providing deterministic tens-of-milliseconds recovery regardless of OEM broadcast throttling.
 *
 * Supplemental Privacy Mask:
 * - Managed independently by dedicated foreground service [PrivacyMaskService].
 * - Remains continuously active while Guard is ON even if AccessibilityService is paused or reconnected.
 *
 * Hot Path:
 * - Volume Up: Sets volatile flag false, disengages guard, posts async cleanup, returns false.
 * - Volume Down: Consumed with true; zero audio Binder calls if volume is already known to be 0.
 */
class VolumeGuardAccessibilityService : AccessibilityService() {

    @Volatile
    var isOperationalFast: Boolean = false
        private set

    private val asyncCleanupHandler = Handler(Looper.getMainLooper())
    private var monitorThread: HandlerThread? = null
    private var monitorHandler: Handler? = null

    private var volumeChangeReceiver: VolumeChangeReceiver? = null
    private var volumeContentObserver: VolumeContentObserver? = null

    private var cachedAudioManager: AudioManager? = null
    private var isSamplerScheduled = false

    // Reusable continuous 10 ms safety sampler Runnable (zero allocations per tick)
    private val samplerRunnable = object : Runnable {
        override fun run() {
            if (!isOperationalFast) {
                isSamplerScheduled = false
                return
            }

            val am = cachedAudioManager
                ?: GuardManager.instance.audioManager
                ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)

            if (am != null) {
                val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (vol > 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "[sampler] Caught volume increase ($vol) -> clamped to 0")
                    }
                    GuardManager.instance.onMusicVolumeIncreaseDetected(
                        this@VolumeGuardAccessibilityService,
                        vol,
                        detector = "sampler"
                    )
                }
            }

            // Reschedule next tick at 10 ms interval
            if (isOperationalFast) {
                monitorHandler?.postDelayed(this, SAMPLER_INTERVAL_MS)
            } else {
                isSamplerScheduled = false
            }
        }
    }

    public override fun onServiceConnected() {
        super.onServiceConnected()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onServiceConnected()")
        }

        cachedAudioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        GuardManager.instance.setAudioManager(cachedAudioManager)

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
        // Fast O(1) hot path: immediately pass through if null or guard is inactive
        if (event == null || !isOperationalFast) {
            return false
        }

        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // 1. Immediately flip local fast flag to false
                isOperationalFast = false
                // 2. Synchronous state update and async preference persistence (ZERO audio IPC)
                GuardManager.instance.onPhysicalVolumeUpFast(this)
                // 3. Post monitor and privacy mask cleanup asynchronously off the critical hot path
                asyncCleanupHandler.post {
                    PrivacyMaskService.stop(this)
                    setMonitorsActive(false)
                }
            }
            // CRITICAL: Return false ASAP so Android processes physical Volume Up normally
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // Zero audio IPC if cached volume is already known to be 0
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
        GuardManager.instance.onServiceDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onDestroy()")
        }
        setMonitorsActive(false)
        GuardManager.instance.onServiceDisconnected(this)
    }

    /**
     * Activates or deactivates monitors and continuous 10 ms safety sampler.
     */
    fun setMonitorsActive(active: Boolean) {
        isOperationalFast = active
        if (active) {
            registerMonitors()
            startContinuousSampler()
        } else {
            stopContinuousSampler()
            unregisterMonitors()
        }
    }

    private fun registerMonitors() {
        try {
            // Start dedicated monitor HandlerThread with THREAD_PRIORITY_AUDIO
            if (monitorThread == null) {
                monitorThread = HandlerThread("VolumeGuardMonitor", Process.THREAD_PRIORITY_AUDIO).apply {
                    start()
                }
                monitorHandler = Handler(monitorThread!!.looper)
            }

            val handler = monitorHandler ?: Handler(Looper.getMainLooper())

            // 1. Primary monitor: VOLUME_CHANGED_ACTION broadcast receiver on background handler
            if (volumeChangeReceiver == null) {
                volumeChangeReceiver = VolumeChangeReceiver()
                val filter = IntentFilter(VolumeChangeReceiver.VOLUME_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(volumeChangeReceiver, filter, null, handler, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(volumeChangeReceiver, filter, null, handler)
                }
            }

            // 2. Fallback monitor: Narrowly scoped ContentObserver for media volume settings
            if (volumeContentObserver == null) {
                volumeContentObserver = VolumeContentObserver(this, handler)
                val musicSpeakerUri = Settings.System.getUriFor("volume_music_speaker")
                val musicUri = Settings.System.getUriFor("volume_music")

                if (musicSpeakerUri != null) {
                    contentResolver.registerContentObserver(musicSpeakerUri, false, volumeContentObserver!!)
                }
                if (musicUri != null && musicUri != musicSpeakerUri) {
                    contentResolver.registerContentObserver(musicUri, false, volumeContentObserver!!)
                }
                if (musicSpeakerUri == null && musicUri == null) {
                    contentResolver.registerContentObserver(Settings.System.CONTENT_URI, false, volumeContentObserver!!)
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
            monitorThread?.quitSafely()
            monitorThread = null
            monitorHandler = null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error unregistering volume monitors", e)
            }
        }
    }

    private fun startContinuousSampler() {
        if (!isSamplerScheduled && isOperationalFast) {
            isSamplerScheduled = true
            monitorHandler?.post(samplerRunnable)
        }
    }

    private fun stopContinuousSampler() {
        isSamplerScheduled = false
        monitorHandler?.removeCallbacks(samplerRunnable)
    }

    companion object {
        private const val TAG = "VolumeGuardService"

        /**
         * Continuous safety sampling interval in milliseconds while Guard is operational.
         * Guarantees volume leaks are caught within 10 ms even under OEM broadcast delays.
         */
        const val SAMPLER_INTERVAL_MS = 10L
    }
}
