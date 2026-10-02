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
 * and multi-layered reactive audio volume enforcement.
 *
 * Latency & Resource Architecture:
 * 1. Physical Key Hot Path:
 *    - Uses [@Volatile isOperationalFast] for O(1) decision making with ZERO allocations.
 *    - Volume Up (Override): Sets local flag OFF, transitions desired state OFF,
 *      schedules monitor/sampler cleanup asynchronously, and returns FALSE with ZERO audio IPC.
 *    - Volume Down: Consumed with TRUE. Performs ZERO audio IPC when cached volume is already 0.
 * 2. Dedicated Single Monitor Thread:
 *    - Single background [HandlerThread] with [Process.THREAD_PRIORITY_AUDIO].
 * 3. Multi-Layered Reactive Protection:
 *    - Primary: [VolumeChangeReceiver] for VOLUME_CHANGED_ACTION (instant extras reading).
 *    - Fallback: [VolumeContentObserver] scoped strictly to media volume setting URIs.
 *    - Adaptive Safety Sampler: Reusable [samplerRunnable] running at 20ms intervals
 *      STRICTLY while Guard is operational AND media playback is actively running.
 *      When playback stops, the sampler completely idles (0% CPU).
 */
class VolumeGuardAccessibilityService : AccessibilityService() {

    @Volatile
    var isOperationalFast: Boolean = false
        private set

    @Volatile
    var isMusicPlaying: Boolean = false
        private set

    private val asyncCleanupHandler = Handler(Looper.getMainLooper())
    private var monitorThread: HandlerThread? = null
    private var monitorHandler: Handler? = null

    private var volumeChangeReceiver: VolumeChangeReceiver? = null
    private var volumeContentObserver: VolumeContentObserver? = null
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null

    private var isSamplerScheduled = false

    // Reusable sampler Runnable to avoid per-tick allocations
    private val samplerRunnable = object : Runnable {
        override fun run() {
            if (!isOperationalFast || !isMusicPlaying) {
                isSamplerScheduled = false
                return
            }

            val am = GuardManager.instance.audioManager
                ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)

            if (am != null) {
                val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (vol > 0) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "[sampler] Caught volume increase ($vol) -> clamping to 0")
                    }
                    GuardManager.instance.onMusicVolumeIncreaseDetected(
                        this@VolumeGuardAccessibilityService,
                        vol,
                        detector = "sampler"
                    )
                }
            }

            // Reschedule next tick if still active
            if (isOperationalFast && isMusicPlaying) {
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
                // 3. Post monitor and sampler cleanup asynchronously off the critical hot path
                asyncCleanupHandler.post {
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
     * Activates or deactivates monitors and the adaptive sampler dynamically.
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
            // Start dedicated monitor HandlerThread with THREAD_PRIORITY_AUDIO
            if (monitorThread == null) {
                monitorThread = HandlerThread("VolumeGuardMonitor", Process.THREAD_PRIORITY_AUDIO).apply {
                    start()
                }
                monitorHandler = Handler(monitorThread!!.looper)
            }

            val handler = monitorHandler ?: Handler(Looper.getMainLooper())

            // 1. Primary monitor: VOLUME_CHANGED_ACTION broadcast receiver
            if (volumeChangeReceiver == null) {
                volumeChangeReceiver = VolumeChangeReceiver()
                val filter = IntentFilter(VolumeChangeReceiver.VOLUME_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(volumeChangeReceiver, filter, null, handler, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(volumeChangeReceiver, filter, null, handler)
                }
            }

            // 2. Fallback monitor: Narrowly scoped ContentObserver for music volume settings
            if (volumeContentObserver == null) {
                volumeContentObserver = VolumeContentObserver(this, handler)
                val musicUri = Settings.System.getUriFor("volume_music_speaker")
                    ?: Settings.System.getUriFor("volume_music")
                if (musicUri != null) {
                    contentResolver.registerContentObserver(musicUri, false, volumeContentObserver!!)
                } else {
                    contentResolver.registerContentObserver(Settings.System.CONTENT_URI, false, volumeContentObserver!!)
                }
            }

            // 3. Playback state detection for the adaptive sampler
            val am = GuardManager.instance.audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            if (am != null) {
                // Check initial playback state
                updatePlaybackState(am.isMusicActive)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioPlaybackCallback == null) {
                    audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
                        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>?) {
                            val playing = !configs.isNullOrEmpty() || am.isMusicActive
                            updatePlaybackState(playing)
                        }
                    }
                    am.registerAudioPlaybackCallback(audioPlaybackCallback!!, handler)
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
            stopSampler()

            volumeChangeReceiver?.let {
                unregisterReceiver(it)
                volumeChangeReceiver = null
            }
            volumeContentObserver?.let {
                contentResolver.unregisterContentObserver(it)
                volumeContentObserver = null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioPlaybackCallback != null) {
                val am = GuardManager.instance.audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                audioPlaybackCallback?.let { am?.unregisterAudioPlaybackCallback(it) }
                audioPlaybackCallback = null
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

    fun updatePlaybackState(playing: Boolean) {
        isMusicPlaying = playing
        if (isOperationalFast && isMusicPlaying) {
            startSamplerIfNeeded()
        } else {
            stopSampler()
        }
    }

    private fun startSamplerIfNeeded() {
        if (!isSamplerScheduled && isOperationalFast && isMusicPlaying) {
            isSamplerScheduled = true
            monitorHandler?.post(samplerRunnable)
        }
    }

    private fun stopSampler() {
        isSamplerScheduled = false
        monitorHandler?.removeCallbacks(samplerRunnable)
    }

    companion object {
        private const val TAG = "VolumeGuardService"

        /**
         * Adaptive safety sampling interval in milliseconds.
         * Default: 20 ms. Can be tuned to 10 ms if device profiling justifies it.
         */
        const val SAMPLER_INTERVAL_MS = 20L
    }
}
