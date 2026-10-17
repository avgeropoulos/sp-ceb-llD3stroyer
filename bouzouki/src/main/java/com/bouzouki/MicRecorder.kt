package com.bouzouki

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Records from the microphone into memory (never a file): mono floats at [RATE]. It asks for the unprocessed source
 * where the phone has one (no automatic gain or noise suppression, which would change what calibration measures),
 * else the plain microphone. The caller must hold the RECORD_AUDIO permission.
 */
object MicRecorder {
    const val RATE = 44100

    /** Whether this phone offers unprocessed microphone audio. */
    fun unprocessed(context: Context): Boolean =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    /**
     * [seconds] of audio; [level] hears the level (RMS, 0..1) about 20 times a second; [cancelled] stops early (what was
     * recorded until then is returned). Blocks: call it off the main thread.
     */
    @SuppressLint("MissingPermission")
    fun record(context: Context, seconds: Double, level: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): FloatArray {
        val source = if (unprocessed(context)) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(min, RATE / 5 * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("The microphone isn't available") }
        val total = (seconds * RATE).toInt()
        val out = FloatArray(total)
        val chunk = FloatArray(RATE / 20)
        var n = 0
        try {
            rec.startRecording()
            while (n < total && !cancelled()) {
                val got = rec.read(chunk, 0, minOf(chunk.size, total - n), AudioRecord.READ_BLOCKING)
                if (got <= 0) break
                System.arraycopy(chunk, 0, out, n, got)
                n += got
                var s = 0.0
                for (i in 0 until got) s += chunk[i] * chunk[i]
                level(sqrt(s / got).toFloat())
            }
        } finally {
            try { rec.stop() } catch (_: IllegalStateException) {}
            rec.release()
        }
        return if (n == total) out else out.copyOf(n)
    }
}
