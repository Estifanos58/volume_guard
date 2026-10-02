package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.example.BuildConfig

/**
 * Lightweight, continuous privacy masking audio player.
 *
 * Purpose:
 * Pre-plays a low-overhead, speech-shaped broadband noise signal using [AudioTrack.MODE_STATIC].
 * While Guard is active (STREAM_MUSIC = 0), this audio is inaudible.
 * If another app programmatically raises STREAM_MUSIC, this masking audio bursts through
 * simultaneously with the rogue audio, obscuring speech intelligibility during the
 * 10 ms detection-and-clamping window.
 *
 * Operational Contract:
 * - Uses exactly one static AudioTrack with pre-generated 1-second speech-shaped noise.
 * - Mono, 16-bit PCM, 16 kHz sample rate (only 32 KB memory footprint).
 * - Zero runtime allocations or continuous sample generation.
 * - Does NOT request audio focus (relies on Android's native stream mixing).
 * - NOT a protection or volume-checking mechanism.
 * - Robust: If AudioTrack fails on any device/emulator, protection continues uninterrupted.
 */
class PrivacyMaskPlayer {

    @Volatile
    var isStarted: Boolean = false
        private set

    private var audioTrack: AudioTrack? = null
    private var pcmBuffer: ShortArray? = null

    /**
     * Starts continuous looping masking playback if not already started.
     * Thread-safe and idempotent.
     */
    @Synchronized
    fun start() {
        if (isStarted && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) return
        isStarted = true

        try {
            ensureTrackInitialized()
            val track = audioTrack ?: return

            track.reloadStaticData()
            track.setLoopPoints(0, pcmBuffer?.size ?: 0, -1)
            track.play()

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Privacy mask audio track started (looping)")
            }
        } catch (e: Throwable) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "AudioTrack hardware playback unavailable; masking inactive", e)
            }
        }
    }

    /**
     * Pauses/stops masking playback. Idempotent.
     */
    @Synchronized
    fun stop() {
        isStarted = false
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
                Log.d(TAG, "Privacy mask audio track stopped")
            }
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
            isStarted = false
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Privacy mask audio track released")
            }
        }
    }

    private fun ensureTrackInitialized() {
        if (audioTrack != null) return

        val buffer = pcmBuffer ?: generateSpeechShapedNoise(
            sampleRate = SAMPLE_RATE,
            durationSeconds = DURATION_SECONDS,
            gain = DEFAULT_MASK_GAIN
        ).also { pcmBuffer = it }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val bufferSizeBytes = buffer.size * 2 // 16-bit = 2 bytes per sample

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

    companion object {
        private const val TAG = "PrivacyMaskPlayer"

        const val SAMPLE_RATE = 16000
        const val DURATION_SECONDS = 1.0f

        /**
         * Conservative amplitude gain (0.0 to 1.0) to prevent clipping/distortion
         * while effectively obscuring speech comprehension.
         */
        const val DEFAULT_MASK_GAIN = 0.45f

        /**
         * Pre-generates deterministic speech-shaped broadband noise.
         * Uses a 1st-order IIR low-pass filter (~1 kHz cutoff) over pseudo-random noise
         * to approximate conversational speech frequency distribution (250 Hz - 3500 Hz).
         */
        fun generateSpeechShapedNoise(
            sampleRate: Int,
            durationSeconds: Float,
            gain: Float
        ): ShortArray {
            val numSamples = (sampleRate * durationSeconds).toInt()
            val buffer = ShortArray(numSamples)
            var seed = 987654321L
            var filterState = 0.0
            val alpha = 0.28 // Low-pass filter coefficient (~1 kHz at 16 kHz sample rate)
            val maxAmplitude = 32767.0 * gain.coerceIn(0.05f, 1.0f)

            for (i in 0 until numSamples) {
                // Linear congruential generator for fast, allocation-free pseudo-random numbers
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
