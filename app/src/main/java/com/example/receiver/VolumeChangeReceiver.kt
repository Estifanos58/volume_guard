package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.example.core.GuardManager

/**
 * High-performance dynamic broadcast receiver for immediate reactive volume changes.
 *
 * Listens for the system broadcast: "android.media.VOLUME_CHANGED_ACTION".
 *
 * Latency & IPC Optimizations:
 * 1. Checks if operational protection is active; returns immediately if inactive.
 * 2. Validates stream type from [EXTRA_VOLUME_STREAM_TYPE]; ignores non-music streams.
 * 3. Reads new volume directly from [EXTRA_VOLUME_STREAM_VALUE].
 * 4. If new volume > 0, immediately invokes setStreamVolume(STREAM_MUSIC, 0, 0) WITHOUT
 *    performing an unnecessary getStreamVolume() Binder IPC query first.
 * 5. If new volume <= 0, no-ops without taking redundant audio action.
 * 6. Only queries AudioManager if extras are absent.
 */
class VolumeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != VOLUME_CHANGED_ACTION) return

        val guardManager = GuardManager.instance
        if (!guardManager.isOperationalActive.value) return

        // Verify stream type directly from extras
        val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
        if (streamType != -1 && streamType != AudioManager.STREAM_MUSIC) {
            // Broadcast is for a non-music stream (e.g. ring, alarm, notification); ignore
            return
        }

        // Read new volume directly from extras to avoid an unnecessary Binder IPC
        val newVolume = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)

        if (newVolume > 0) {
            // Volume increase detected directly from broadcast extras -> clamp immediately
            guardManager.onMusicVolumeIncreaseDetected(context, newVolume)
        } else if (newVolume == 0) {
            // Already 0 -> update cached state without audio IPC
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
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    }
}
