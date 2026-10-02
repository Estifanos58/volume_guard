package com.example.observer

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.BuildConfig
import com.example.core.GuardManager

/**
 * Narrowly scoped System Settings ContentObserver for media volume.
 *
 * Runs on the dedicated monitor HandlerThread to avoid main looper latency.
 * Scoped strictly to media volume URIs (e.g. "volume_music_speaker", "volume_music").
 * Operates as a secondary interrupt-driven fallback to the primary VOLUME_CHANGED_ACTION broadcast.
 * Bails out immediately if volume is already zero or protection is inactive.
 */
class VolumeContentObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        val guardManager = GuardManager.instance
        if (!guardManager.isOperationalActive.value) return

        val am = guardManager.audioManager
            ?: (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?: return

        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVol > 0) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "ContentObserver caught volume change ($currentVol) -> clamping to 0")
            }
            guardManager.onMusicVolumeIncreaseDetected(context, currentVol, detector = "observer")
        } else {
            guardManager.updateCachedVolume(0)
        }
    }

    companion object {
        private const val TAG = "VolumeContentObserver"
    }
}
