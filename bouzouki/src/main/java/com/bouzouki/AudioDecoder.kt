package com.bouzouki

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.bouzouki.core.song.Separator
import com.bouzouki.core.song.resample
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/** Decodes a song (m4a, webm/opus, mp3, …) into 44.1 kHz stereo floats for the analysis. */
object AudioDecoder {
    class Stereo(val left: FloatArray, val right: FloatArray)

    /** Longest stretch analysed, to keep memory in check (12 minutes). */
    private const val MAX_SECONDS = 12 * 60

    fun decode(file: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Stereo =
        decode(MediaExtractor().apply { setDataSource(file.path) }, progress, cancelled)

    fun decode(context: Context, uri: Uri, progress: (Float) -> Unit, cancelled: () -> Boolean): Stereo =
        decode(MediaExtractor().apply { setDataSource(context, uri, null) }, progress, cancelled)

    private fun decode(extractor: MediaExtractor, progress: (Float) -> Unit, cancelled: () -> Boolean): Stereo {
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio in this file")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var left = FloatArray(1 shl 20)
            var right = FloatArray(1 shl 20)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (cancelled()) throw InterruptedException()
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                                if (durationUs > 0) progress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (outIdx >= 0) {
                        if (info.size > 0 && n < MAX_SECONDS * rate) {
                            val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val frames = info.size / (channels * if (pcmFloat) 4 else 2)
                            if (n + frames > left.size) {
                                val grow = maxOf(left.size * 2, n + frames)
                                left = left.copyOf(grow); right = right.copyOf(grow)
                            }
                            if (pcmFloat) {
                                val fb = buf.asFloatBuffer()
                                for (i in 0 until frames) {
                                    left[n + i] = fb.get(i * channels)
                                    right[n + i] = if (channels > 1) fb.get(i * channels + 1) else left[n + i]
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                for (i in 0 until frames) {
                                    left[n + i] = sb.get(i * channels) / 32768f
                                    right[n + i] = if (channels > 1) sb.get(i * channels + 1) / 32768f else left[n + i]
                                }
                            }
                            n += frames
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            if (n == 0) throw IOException("Couldn't decode the audio")
            left = left.copyOf(n); right = right.copyOf(n)
            val target = Separator.SAMPLE_RATE
            return if (rate == target) Stereo(left, right)
            else Stereo(resample(left, rate, target, 8), resample(right, rate, target, 8))
        } finally {
            extractor.release()
        }
    }
}
