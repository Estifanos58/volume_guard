package com.example.observer

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.core.GuardManager

/**
 * System Settings ContentObserver for media volume.
 *
 * Provides a secondary, interrupt-driven safety net in case an OEM ROM (like HiOS on Tecno devices)
 * suppresses or delays system broadcasts. When any component writes to audio settings,
 * the ContentResolver notifies this observer with zero polling overhead.
 */
class VolumeContentObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        if (audioManager == null) return

        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        GuardManager.instance.onExternalVolumeChanged(context, currentVol)
    }
}
