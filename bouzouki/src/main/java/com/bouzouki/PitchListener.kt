package com.bouzouki

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.bouzouki.core.NoteTracker
import kotlin.concurrent.thread

/**
 * Reads audio from an [AudioRecord] (the microphone, or a video's sound) on a background
 * thread, follows its melody with a [NoteTracker], and reports notes on the main thread.
 */
class PitchListener(private val record: AudioRecord, private val onEvent: (NoteTracker.Event, DoubleArray) -> Unit) {
    @Volatile private var running = false
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "pitch-listener") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val tracker = NoteTracker(record.sampleRate)
            val pcm = ShortArray(1024)
            val samples = FloatArray(pcm.size)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(pcm, 0, pcm.size)
                    if (n <= 0) { if (n < 0) break else continue }
                    for (i in 0 until n) samples[i] = pcm[i] / 32768f
                    for (e in tracker.process(samples, n)) {
                        val tally = tracker.pitchClassTime.copyOf()
                        main.post { if (running) onEvent(e, tally) }
                    }
                }
            } catch (_: Exception) {
                // The source went away (permission revoked, capture stopped); just stop listening.
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }

    companion object {
        const val SAMPLE_RATE = 44100

        fun bufferBytes() = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            8192,
        )

        val format: AudioFormat get() = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        /** The microphone, without the voice-call processing that would mangle music. Needs RECORD_AUDIO. */
        @SuppressLint("MissingPermission")
        fun microphone(): AudioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes())
            .build()
    }
}
