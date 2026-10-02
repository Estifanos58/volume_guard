package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
 * and high-priority reactive audio protection.
 *
 * Latency & Auditory Leak Prevention Architecture:
 * 1. Physical Key Hot Path:
 *    - Uses [@Volatile isOperationalFast] for O(1) decision making with ZERO allocations.
 *    - Volume Up (Override): Flips local flag OFF, lifts proactive mute, persists preference,
 *      schedules monitor cleanup asynchronously, and immediately returns FALSE.
 *    - Volume Down: Consumes key (returns TRUE) with ZERO audio IPC if cached volume is already 0.
 * 2. Dedicated Background Monitor Thread:
 *    - Reactive monitors run on a high-priority [HandlerThread] (Process.THREAD_PRIORITY_URGENT_AUDIO)
 *      to eliminate main looper queue latency.
 * 3. Proactive Mute & Targeted Fallback:
 *    - Operates with proactive STREAM_MUSIC mute on activation to minimize auditory leaks.
 *    - Narrowly scoped [VolumeContentObserver] on the same dedicated background thread.
 *    - [AudioDeviceCallback] reasserting volume 0 when Bluetooth/headsets route audio.
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
    private var audioDeviceCallback: AudioDeviceCallback? = null

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
                // 2. Synchronous state update, lifts proactive mute, and async preference persistence
                GuardManager.instance.onPhysicalVolumeUpFast(this)
                // 3. Post monitor cleanup asynchronously off the critical hot path
                asyncCleanupHandler.post {
                    setMonitorsActive(false)
                }
            }
            // CRITICAL: Return false ASAP so Android processes physical Volume Up normally
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // Zero IPC if volume is already known to be 0
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
            // Start dedicated high-priority HandlerThread for reactive audio monitoring
            if (monitorThread == null) {
                monitorThread = HandlerThread("VolumeGuardMonitor", Process.THREAD_PRIORITY_URGENT_AUDIO).apply {
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

            // 2. Fallback monitor: Narrowly scoped ContentObserver on background handler
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

            // 3. AudioDeviceCallback for Bluetooth/headset routing changes on background handler
            if (audioDeviceCallback == null) {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null) {
                    audioDeviceCallback = object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                            if (isOperationalFast) {
                                GuardManager.instance.forceMediaVolumeZero(
                                    this@VolumeGuardAccessibilityService,
                                    reason = "Audio device connected"
                                )
                            }
                        }
                    }
                    am.registerAudioDeviceCallback(audioDeviceCallback!!, handler)
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
            if (audioDeviceCallback != null) {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioDeviceCallback?.let { am?.unregisterAudioDeviceCallback(it) }
                audioDeviceCallback = null
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

    companion object {
        private const val TAG = "VolumeGuardService"
    }
}
