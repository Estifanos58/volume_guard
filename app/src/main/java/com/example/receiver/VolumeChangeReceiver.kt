package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import com.example.BuildConfig
import com.example.core.GuardManager

/**
 * Ultra-low latency dynamic broadcast receiver for reactive media volume protection.
 *
 * Dispatched on a dedicated background HandlerThread to eliminate main-thread queue delay.
 *
 * Latency & Auditory Leak Prevention Architecture:
 * 1. Checks if operational protection is active; bails out immediately if inactive.
 * 2. Ignores broadcasts for non-music streams directly from [EXTRA_VOLUME_STREAM_TYPE].
 * 3. Extracts new volume directly from [EXTRA_VOLUME_STREAM_VALUE].
 * 4. When newVolume > 0: Immediately issues exactly ONE setStreamVolume(STREAM_MUSIC, 0, 0)
 *    without an unnecessary pre-check getStreamVolume() IPC.
 * 5. When newVolume == 0: Updates cached state without taking audio action.
 * 6. Timestamps latency (in microseconds) in debug builds for real-device benchmarking.
 */
class VolumeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != VOLUME_CHANGED_ACTION) return

        val tReceived = if (BuildConfig.DEBUG) System.nanoTime() else 0L

        val guardManager = GuardManager.instance
        if (!guardManager.isOperationalActive.value) return

        // 1. Verify stream type directly from extras
        val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
        if (streamType != -1 && streamType != AudioManager.STREAM_MUSIC) {
            // Ignore non-music streams (ring, notification, alarm, etc.)
            return
        }

        // 2. Read new volume directly from extras to avoid a Binder query IPC
        val newVolume = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)

        if (newVolume > 0) {
            val tCorrectionStart = if (BuildConfig.DEBUG) System.nanoTime() else 0L

            // Exactly ONE audio correction call
            guardManager.onMusicVolumeIncreaseDetected(context, newVolume)

            if (BuildConfig.DEBUG) {
                val tCorrectionEnd = System.nanoTime()
                val totalMicros = (tCorrectionEnd - tReceived) / 1000
                val correctionMicros = (tCorrectionEnd - tCorrectionStart) / 1000
                Log.d(TAG, "Reactive volume correction: total=${totalMicros}µs (audio_ipc=${correctionMicros}µs)")
            }
        } else if (newVolume == 0) {
            // Volume is 0: update cached volume, zero audio IPC
            guardManager.updateCachedVolume(0)
        } else {
            // Extras missing (-1): safe fallback to AudioManager query
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val actual = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (actual > 0) {
                guardManager.onMusicVolumeIncreaseDetected(context, actual)
            } else {
                guardManager.updateCachedVolume(0)
            }
        }
    }

    companion object {
        private const val TAG = "VolumeChangeReceiver"
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    }
}
