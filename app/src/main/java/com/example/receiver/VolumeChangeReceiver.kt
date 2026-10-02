package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.example.core.GuardManager

/**
 * High-performance, zero-allocation dynamic broadcast receiver for reactive volume corrections.
 *
 * Dispatched on a dedicated normal-priority HandlerThread while Guard is operational.
 *
 * Hot-Path Optimizations:
 * 1. Immediate guard check: bails out if protection is not operational.
 * 2. Directly checks stream type from [EXTRA_VOLUME_STREAM_TYPE]; ignores non-music streams.
 * 3. Reads new volume from [EXTRA_VOLUME_STREAM_VALUE].
 * 4. When newVolume > 0: executes exactly ONE setStreamVolume(STREAM_MUSIC, 0, 0) via cached
 *    AudioManager without an unnecessary getStreamVolume() Binder IPC query.
 * 5. When newVolume == 0: updates volatile cached volume without audio IPC.
 * 6. Missing extras fallback only when extras are completely absent (-1).
 */
class VolumeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != VOLUME_CHANGED_ACTION) return

        val guardManager = GuardManager.instance
        if (!guardManager.isOperationalActive.value) return

        // 1. Verify stream type directly from extras
        val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
        if (streamType != -1 && streamType != AudioManager.STREAM_MUSIC) {
            // Ignore non-music streams (ring, notification, alarm, etc.)
            return
        }

        // 2. Read new volume directly from extras to avoid an unnecessary Binder IPC
        val newVolume = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)

        if (newVolume > 0) {
            // Directly issue single correction call
            guardManager.onMusicVolumeIncreaseDetected(context, newVolume)
        } else if (newVolume == 0) {
            // Already 0: update cached state without audio IPC
            guardManager.updateCachedVolume(0)
        } else {
            // Extras missing (-1): safe fallback to AudioManager query
            val am = guardManager.audioManager
                ?: (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?: return
            val actual = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (actual > 0) {
                guardManager.onMusicVolumeIncreaseDetected(context, actual)
            } else {
                guardManager.updateCachedVolume(0)
            }
        }
    }

    companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    }
}
