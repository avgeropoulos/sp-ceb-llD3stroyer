package com.mixdeck

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.WavWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * a separate thread writes them to storage so recording never causes glitches.
 */
class Recorder(private val ctx: Context, private val mixer: Mixer) {
    private var writer: WavWriter? = null
    private var file: File? = null
    private var thread: Thread? = null
    private val queue = ArrayBlockingQueue<FloatArray>(2048)
    @Volatile var recording = false; private set
    val seconds get() = writer?.seconds ?: 0.0

    fun start() {
        if (recording) return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())
        val f = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "MixDeck $stamp.wav")
        val w = WavWriter(f, mixer.sampleRate)
        file = f; writer = w
        recording = true
        thread = Thread({
            while (recording || queue.isNotEmpty()) {
                val b = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                w.write(b, b.size / 2)
            }
        }, "MixDeck recorder").apply { start() }
        synchronized(mixer.lock) {
            mixer.tap = { buf, n -> queue.offer(buf.copyOf(n * 2)) }
        }
    }

    /** Stops and saves the recording where music apps can see it. Returns a description of where. */
    fun stop(): String? {
        if (!recording) return null
        synchronized(mixer.lock) { mixer.tap = null }
        recording = false
        thread?.join(3000)
        writer?.close()
        val f = file ?: return null
        writer = null; file = null
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MixDeck")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                f.delete()
                return "Music/MixDeck/${f.name}"
            }
        }
        return f.path
    }
}
