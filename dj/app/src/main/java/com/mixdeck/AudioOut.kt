package com.mixdeck

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.Recordings
import com.mixdeck.engine.WavWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue

/** Runs the mixer on a high-priority thread and plays it through a low-latency AudioTrack. */
class AudioOut(private val mixer: Mixer) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "MixDeck audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sr = mixer.sampleRate
        val frames = 192
        val minBytes = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBytes, frames * 8 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buf = FloatArray(frames * 2)
        track.play()
        try {
            while (running) {
                synchronized(mixer.lock) { mixer.render(buf, frames) }
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            track.stop(); track.release()
        }
    }

    companion object {
        fun deviceSampleRate(ctx: Context): Int {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        }
    }
}

/**
 * Records the master output to a WAV file. The audio thread only copies samples into a queue;
 * a separate thread writes them to storage so recording never causes glitches. When it stops,
 * [MixStore] turns the WAV into a named, compact M4A in Music/MixDeck.
 *
 * The WAV is kept in the app's own files (not the cache, which Android may empty when storage runs
 * low) until it's saved, so one left by a run that was cut off is found and saved next time.
 * Lives in [Engine], so a recording carries on when the console is recreated.
 */
class Recorder(private val ctx: Context, private val mixer: Mixer) {
    private var writer: WavWriter? = null
    private var file: File? = null
    private var thread: Thread? = null
    private val queue = ArrayBlockingQueue<FloatArray>(4096)
    @Volatile var recording = false; private set
    var startedAt = 0L; private set
    val seconds get() = writer?.seconds ?: 0.0
    /**
     * Called on the recorder's thread when the WAV can't be written (storage full). Recording has
     * stopped taking audio by then: call [stop] to finish the file and save what was recorded.
     */
    @Volatile var onFailed: ((IOException) -> Unit)? = null

    fun start() {
        if (recording) return
        val f = Recordings.newFile(dir(ctx))
        val w = WavWriter(f, mixer.sampleRate)
        file = f; writer = w; startedAt = System.currentTimeMillis()
        recording = true
        thread = Thread({
            try {
                while (recording || queue.isNotEmpty()) {
                    val b = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    w.write(b, b.size / 2)
                }
            } catch (e: IOException) {
                // Out of space: stop taking audio (the music plays on); the host saves what was recorded.
                synchronized(mixer.lock) { mixer.tap = null }
                queue.clear()
                onFailed?.invoke(e)
            }
        }, "MixDeck recorder").apply { start() }
        synchronized(mixer.lock) {
            mixer.tap = { buf, n -> queue.offer(buf.copyOf(n * 2)) }
        }
    }

    /** Stops recording and returns the finished WAV (to be saved or discarded). */
    fun stop(): File? {
        if (!recording) return null
        synchronized(mixer.lock) { mixer.tap = null }
        recording = false
        thread?.join(3000)
        // If its last part can't be written (storage full), the file still holds the rest.
        runCatching { writer?.close() }
        val f = file
        writer = null; file = null
        return f
    }

    companion object {
        /** Where recordings are written. Older versions wrote them to the cache. */
        fun dir(ctx: Context): File = ctx.noBackupFilesDir
    }
}
