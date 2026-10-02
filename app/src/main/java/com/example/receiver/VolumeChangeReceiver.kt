package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.example.core.GuardManager

/**
 * Hardened dynamic broadcast receiver for immediate reactive volume changes.
 *
 * Listens for the system broadcast: "android.media.VOLUME_CHANGED_ACTION".
 * Security & Reliability:
 * - Does not trust unverified intent extras (which could be spoofed by third-party apps).
 * - Queries the authoritative AudioManager directly for current STREAM_MUSIC level.
 * - Active strictly when operational protection is ON; unregistered when OFF.
 */
class VolumeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == VOLUME_CHANGED_ACTION) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val actualVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            GuardManager.instance.onExternalVolumeChanged(context, actualVolume)
        }
    }

    companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    }
}
