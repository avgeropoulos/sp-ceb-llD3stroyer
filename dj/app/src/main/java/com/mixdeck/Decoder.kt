package com.mixdeck

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.mixdeck.engine.Pcm
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Stereo 16-bit audio kept outside the Java heap (a direct buffer), so two long songs fit
 * comfortably in memory.
 */
class DirectPcm(private val data: ShortBuffer, override val frames: Int, override val sampleRate: Int) : Pcm {
    override fun left(frame: Int): Float = data.get(frame * 2) * (1f / 32768f)
    override fun right(frame: Int): Float = data.get(frame * 2 + 1) * (1f / 32768f)
}

/** Decodes any audio file Android supports (AAC/m4a, Opus, MP3, FLAC, WAV, ...) with MediaCodec. */
object Decoder {
    fun decode(file: File, progress: (Float) -> Unit = {}): DirectPcm {
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        val trackIdx = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No audio in this file")
        ex.selectTrack(trackIdx)
        val fmt = ex.getTrackFormat(trackIdx)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatOut = false

        // Room for the whole song plus a little; grows if the duration was wrong.
        var cap = ((durationUs / 1e6 + 2) * sampleRate * 2).toInt().coerceAtLeast(sampleRate * 2 * 10)
        var out = ByteBuffer.allocateDirect(cap * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            if (durationUs > 0) progress((ex.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatOut = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bb = buf.slice().order(ByteOrder.nativeOrder())
                        val samples = if (floatOut) info.size / 4 else info.size / 2
                        val frames = samples / channels
                        if (out.remaining() < frames * 2) {
                            cap = maxOf(cap * 3 / 2, out.position() + frames * 2 + sampleRate * 2)
                            val bigger = ByteBuffer.allocateDirect(cap * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                            out.flip(); bigger.put(out); out = bigger
                        }
                        if (floatOut) {
                            val fb = bb.asFloatBuffer()
                            for (f in 0 until frames) {
                                val l = fb.get(f * channels); val r = if (channels > 1) fb.get(f * channels + 1) else l
                                out.put((l.coerceIn(-1f, 1f) * 32767).toInt().toShort())
                                out.put((r.coerceIn(-1f, 1f) * 32767).toInt().toShort())
                            }
                        } else {
                            val sb = bb.asShortBuffer()
                            if (channels == 2) {
                                out.put(sb)
                            } else {
                                for (f in 0 until frames) {
                                    val l = sb.get(f * channels); val r = if (channels > 1) sb.get(f * channels + 1) else l
                                    out.put(l); out.put(r)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        val frames = out.position() / 2
        if (frames == 0) throw IOException("Couldn't decode any audio")
        out.flip()
        progress(1f)
        return DirectPcm(out, frames, sampleRate)
    }
}
