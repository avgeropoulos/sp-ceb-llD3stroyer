package com.mixdeck.desktop

import com.mixdeck.engine.Mixer
import com.mixdeck.engine.ShortArrayPcm
import com.mixdeck.engine.WavWriter
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FrameGrabber
import java.io.File
import java.io.IOException
import java.nio.ShortBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Plays the mixer through the computer's default audio output. */
class DesktopAudioOut(private val mixer: Mixer) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var error: String? = null; private set

    fun start() {
        running = true
        thread = Thread({ loop() }, "MixDeck audio").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
    }

    fun stop() { running = false; thread?.join(500) }

    companion object {
        /** 48 kHz if the computer's output supports it, otherwise 44.1 kHz. */
        fun pickSampleRate(): Int = listOf(48000, 44100).firstOrNull { sr ->
            runCatching {
                AudioSystem.isLineSupported(javax.sound.sampled.DataLine.Info(SourceDataLine::class.java,
                    AudioFormat(sr.toFloat(), 16, 2, true, false)))
            }.getOrDefault(false)
        } ?: 48000
    }

    private fun loop() {
        val frames = 256
        val format = AudioFormat(mixer.sampleRate.toFloat(), 16, 2, true, false)
        val line: SourceDataLine = try {
            AudioSystem.getSourceDataLine(format).apply { open(format, frames * 4 * 8); start() }
        } catch (e: Exception) {
            error = "MixDeck couldn't open your computer's audio output (${e.message}). " +
                "Check that speakers or headphones are connected and not in use by another app, then restart MixDeck."
            return
        }
        val buf = FloatArray(frames * 2)
        val bytes = ByteArray(frames * 4)
        try {
            while (running) {
                synchronized(mixer.lock) { mixer.render(buf, frames) }
                for (i in buf.indices) {
                    val s = (buf[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    bytes[i * 2] = s.toByte(); bytes[i * 2 + 1] = (s shr 8).toByte()
                }
                line.write(bytes, 0, bytes.size)
            }
        } finally {
            line.drain(); line.stop(); line.close()
        }
    }
}

/** Records the master output to a WAV in the background, like the phone app. */
class DesktopRecorder(private val mixer: Mixer, private val tempDir: File) {
    private var writer: WavWriter? = null
    private var file: File? = null
    private var thread: Thread? = null
    private val queue = ArrayBlockingQueue<FloatArray>(4096)
    @Volatile var recording = false; private set
    val seconds get() = writer?.seconds ?: 0.0

    fun start() {
        if (recording) return
        val f = File(tempDir, "recording-${System.currentTimeMillis()}.wav")
        val w = WavWriter(f, mixer.sampleRate)
        file = f; writer = w; recording = true
        thread = Thread({
            while (recording || queue.isNotEmpty()) {
                val b = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                w.write(b, b.size / 2)
            }
        }, "MixDeck recorder").apply { start() }
        synchronized(mixer.lock) { mixer.tap = { buf, n -> queue.offer(buf.copyOf(n * 2)) } }
    }

    fun stop(): File? {
        if (!recording) return null
        synchronized(mixer.lock) { mixer.tap = null }
        recording = false
        thread?.join(3000)
        writer?.close()
        val f = file
        writer = null; file = null
        return f
    }
}

/** Audio file decoding and encoding with FFmpeg, so any format plays. */
object Ffmpeg {
    init {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
    }

    /** Decodes [file] to 16-bit stereo at its own sample rate. */
    fun decode(file: File, progress: (Float) -> Unit = {}): ShortArrayPcm {
        FFmpegFrameGrabber(file).use { g ->
            g.sampleMode = FrameGrabber.SampleMode.SHORT
            g.audioChannels = 2
            g.start()
            val sr = g.sampleRate.takeIf { it > 0 } ?: throw IOException("Not an audio file")
            val total = g.lengthInTime.takeIf { it > 0 }
            var out = ShortArray(((total ?: 300_000_000L) / 1e6 * sr * 2 + sr * 4).toInt().coerceAtLeast(sr * 20))
            var n = 0
            while (true) {
                val frame = g.grabSamples() ?: break
                val sb = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
                val count = sb.remaining()
                if (n + count > out.size) out = out.copyOf(maxOf(out.size * 3 / 2, n + count + sr * 2))
                sb.get(out, n, count)
                n += count
                if (total != null) progress((g.timestamp.toFloat() / total).coerceIn(0f, 1f))
            }
            g.stop()
            if (n == 0) throw IOException("Couldn't decode any audio")
            progress(1f)
            return ShortArrayPcm(out.copyOf(n - n % 2), sr)
        }
    }

    /** Encodes a recorded WAV to AAC in an .m4a file (about 1.4 MB a minute). */
    fun wavToM4a(wav: File, out: File, progress: (Float) -> Unit) {
        FFmpegFrameGrabber(wav).use { g ->
            g.start()
            val total = g.lengthInTime.coerceAtLeast(1)
            FFmpegFrameRecorder(out, 2).use { r ->
                r.format = "mp4"
                r.audioCodec = avcodec.AV_CODEC_ID_AAC
                r.audioBitrate = 192_000
                r.sampleRate = g.sampleRate
                r.audioChannels = 2
                r.start()
                while (true) {
                    val f = g.grabSamples() ?: break
                    r.record(f)
                    progress((g.timestamp.toFloat() / total).coerceIn(0f, 1f))
                }
                r.stop()
            }
            g.stop()
        }
    }

    /** Duration in milliseconds, or 0. */
    fun durationMs(file: File): Long = runCatching {
        FFmpegFrameGrabber(file).use { g -> g.start(); val d = g.lengthInTime / 1000; g.stop(); d }
    }.getOrDefault(0L)
}
