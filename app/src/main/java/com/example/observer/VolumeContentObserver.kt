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
 * Runs on a dedicated background HandlerThread to avoid UI thread delays.
 * Scoped strictly to media volume URIs (e.g. "volume_music_speaker", "volume_music").
 * Operates as a secondary fallback to the primary VOLUME_CHANGED_ACTION broadcast.
 * Bails out immediately if volume is already zero or protection is inactive.
 */
class VolumeContentObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        val guardManager = GuardManager.instance
        if (!guardManager.isOperationalActive.value) return

        val am = audioManager ?: return
        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVol > 0) {
            guardManager.onMusicVolumeIncreaseDetected(context, currentVol)
        } else {
            guardManager.updateCachedVolume(0)
        }
    }
}
