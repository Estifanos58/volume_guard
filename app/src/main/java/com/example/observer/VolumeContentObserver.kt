package com.example.observer

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.core.GuardManager

/**
 * Narrowly scoped System Settings ContentObserver for media volume.
 *
 * Scoped strictly to media volume URIs (e.g. "volume_music_speaker", "volume_music").
 * Does not observe unrelated system settings like brightness, wallpaper, or screen timeout.
 * Provides interrupt-driven fallback for OEM devices (like Tecno/HiOS) without polling.
 */
class VolumeContentObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        val am = audioManager ?: return
        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        GuardManager.instance.onExternalVolumeChanged(context, currentVol)
    }
}
