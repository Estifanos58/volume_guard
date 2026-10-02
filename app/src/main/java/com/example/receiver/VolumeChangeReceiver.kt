package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import com.example.core.GuardManager

/**
 * Dynamic broadcast receiver for immediate reactive volume changes.
 *
 * Listens for the system broadcast: "android.media.VOLUME_CHANGED_ACTION".
 * This allows Volume Guard to detect and correct programmatic media volume modifications
 * made by background apps, games, social media apps, or malicious prank scripts
 * within milliseconds, without relying on battery-draining polling loops.
 */
class VolumeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == VOLUME_CHANGED_ACTION) {
            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            // Stream type 3 is AudioManager.STREAM_MUSIC
            if (streamType == AudioManager.STREAM_MUSIC || streamType == -1) {
                val newVolume = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                val resolvedVolume = if (newVolume >= 0) {
                    newVolume
                } else {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                }

                GuardManager.instance.onExternalVolumeChanged(context, resolvedVolume)
            }
        }
    }

    companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    }
}
