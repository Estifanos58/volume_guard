package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.example.BuildConfig
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * State machine for the privacy mask player.
 */
enum class MaskState {
    STOPPED,
    STARTING,
    PLAYING,
    FAILED
}

/**
 * Lightweight, continuous privacy masking audio player.
 *
 * Operational Contract:
 * - Pre-loads a 1-second 16 kHz 16-bit mono speech-shaped noise PCM asset (32 KB).
 * - Uses exactly one static AudioTrack with [AudioTrack.MODE_STATIC] looped infinitely.
 * - Formal state machine: [MaskState.STOPPED], [MaskState.STARTING], [MaskState.PLAYING], [MaskState.FAILED].
 * - Only reports PLAYING after AudioTrack successfully initializes and verify playState == PLAYSTATE_PLAYING.
 * - Zero continuous runtime allocations or continuous random number generation.
 * - Does NOT request audio focus (relies on Android's native stream mixing).
 * - NOT a protection or volume-checking mechanism.
 * - Safe fail-over: If AudioTrack fails, reports FAILED and allows Volume Guard to continue.
 */
class PrivacyMaskPlayer(private val context: Context) {

    @Volatile
    var state: MaskState = MaskState.STOPPED
        private set

    @Volatile
    private var currentSessionId: Long = 0L

    private var audioTrack: AudioTrack? = null
    private var pcmBuffer: ShortArray? = null

    /**
     * Starts continuous looping masking playback.
     * Synchronized and idempotent.
     */
    @Synchronized
    fun start(): Boolean {
        if (state == MaskState.PLAYING && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            return true
        }

        currentSessionId++
        val session = currentSessionId
        state = MaskState.STARTING

        try {
            ensureTrackInitialized()
            val track = audioTrack ?: run {
                state = MaskState.FAILED
                return false
            }

            if (session != currentSessionId) {
                // Superseded by newer session
                return false
            }

            track.reloadStaticData()
            track.setLoopPoints(0, pcmBuffer?.size ?: 0, -1)
            track.play()

            // Verify playback state before claiming PLAYING
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                state = MaskState.PLAYING
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Privacy mask AudioTrack PLAYING (session=$session)")
                }
                return true
            } else {
                state = MaskState.FAILED
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "AudioTrack play() succeeded but playState is not PLAYING: ${track.playState}")
                }
                return false
            }
        } catch (e: Throwable) {
            state = MaskState.FAILED
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Failed to start privacy mask AudioTrack; masking unavailable", e)
            }
            return false
        }
    }

    /**
     * Pauses/stops masking playback. Idempotent.
     */
    @Synchronized
    fun stop() {
        currentSessionId++
        state = MaskState.STOPPED

        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
            }
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Error stopping privacy mask AudioTrack", e)
            }
        } finally {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Privacy mask AudioTrack STOPPED")
            }
        }
    }

    /**
     * Checks if the track is still healthy and playing; attempts recovery if stalled.
     * Called by the 200 ms watchdog.
     */
    @Synchronized
    fun checkHealthAndRecover() {
        if (state != MaskState.PLAYING) return

        val track = audioTrack
        if (track == null || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Watchdog detected stalled AudioTrack (state=$state, playState=${track?.playState}) -> recovering")
            }
            // Attempt restart
            start()
        }
    }

    /**
     * Releases the AudioTrack completely (e.g. on service destruction).
     */
    @Synchronized
    fun release() {
        stop()
        try {
            audioTrack?.release()
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Error releasing privacy mask AudioTrack", e)
            }
        } finally {
            audioTrack = null
            pcmBuffer = null
            state = MaskState.STOPPED
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Privacy mask AudioTrack released")
            }
        }
    }

    private fun ensureTrackInitialized() {
        if (audioTrack != null) return

        val buffer = pcmBuffer ?: loadOrGenerateBuffer().also { pcmBuffer = it }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val bufferSizeBytes = buffer.size * 2 // 16-bit PCM = 2 bytes per sample

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        val written = track.write(buffer, 0, buffer.size)
        if (written < 0) {
            track.release()
            throw IllegalStateException("Failed to write PCM buffer to AudioTrack: $written")
        }

        audioTrack = track
    }

    private fun loadOrGenerateBuffer(): ShortArray {
        // 1. Try loading pre-generated 1-second 16kHz asset
        try {
            val assetStream: InputStream = context.assets.open(ASSET_FILE_NAME)
            val bytes = assetStream.readBytes()
            assetStream.close()

            if (bytes.size >= 32000) {
                val shortBuffer = ShortArray(bytes.size / 2)
                ByteBuffer.wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(shortBuffer)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Loaded pre-generated PCM asset (${bytes.size} bytes)")
                }
                return shortBuffer
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Could not load asset $ASSET_FILE_NAME, falling back to static generator", e)
            }
        }

        // 2. Deterministic fallback generator
        return generateSpeechShapedNoise(SAMPLE_RATE, DURATION_SECONDS, DEFAULT_MASK_GAIN)
    }

    companion object {
        private const val TAG = "PrivacyMaskPlayer"
        private const val ASSET_FILE_NAME = "privacy_mask_16k.pcm"

        const val SAMPLE_RATE = 16000
        const val DURATION_SECONDS = 1.0f
        const val DEFAULT_MASK_GAIN = 0.45f

        fun generateSpeechShapedNoise(
            sampleRate: Int,
            durationSeconds: Float,
            gain: Float
        ): ShortArray {
            val numSamples = (sampleRate * durationSeconds).toInt()
            val buffer = ShortArray(numSamples)
            var seed = 987654321L
            var filterState = 0.0
            val alpha = 0.28
            val maxAmplitude = 32767.0 * gain.coerceIn(0.05f, 1.0f)

            for (i in 0 until numSamples) {
                seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
                val white = (seed.toDouble() / 0x7fffffffL) * 2.0 - 1.0
                filterState += alpha * (white - filterState)
                val sampleVal = (filterState * maxAmplitude).coerceIn(-32768.0, 32767.0).toInt().toShort()
                buffer[i] = sampleVal
            }
            return buffer
        }
    }
}
