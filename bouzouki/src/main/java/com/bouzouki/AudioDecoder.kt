package com.bouzouki

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.bouzouki.core.song.AudioStart
import com.bouzouki.core.song.MdxSeparator
import com.bouzouki.core.song.resample
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes a song (m4a, webm/opus, mp3, …) into 44.1 kHz stereo floats for the analysis. AAC audio (YouTube's m4a)
 * starts where the song does for a player: the short lead-in the file declares is dropped here if the phone's decoder
 * did not drop it ([AudioStart]), so a note's time means the same on every phone and matches the video's clock.
 * Other kinds of audio are decoded as the phone always did (their decoders trim in ways of their own).
 */
object AudioDecoder {
    /**
     * [start] says in a few words what was done about the file's lead-in (kept with the song, for its data export).
     * [lateBefore] is how many seconds late this phone's decoding used to leave such a file before the lead-in was
     * dealt with here: the lead-in dropped now, when the phone knew of none (so its decoder never dropped it).
     */
    class Stereo(val left: FloatArray, val right: FloatArray, val start: String = "", val lateBefore: Double = 0.0)

    /** Longest stretch analysed, to keep memory in check (12 minutes). */
    private const val MAX_SECONDS = 12 * 60

    fun decode(file: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Stereo =
        decode(MediaExtractor().apply { setDataSource(file.path) }, AudioStart.mp4Edit(file), progress, cancelled)

    fun decode(context: Context, uri: Uri, progress: (Float) -> Unit, cancelled: () -> Boolean): Stereo =
        decode(MediaExtractor().apply { setDataSource(context, uri, null) }, null, progress, cancelled)

    /** The lead-in the phone read from the file itself, in samples at the track's rate; 0 if it gave none. */
    private fun declaredDelay(format: MediaFormat): Int = try {
        if (format.containsKey(KEY_DELAY)) format.getInteger(KEY_DELAY) else 0
    } catch (_: Exception) { 0 }

    /**
     * [edit] is what the file itself says about its start, when it could be read ([AudioStart.mp4Edit]); otherwise
     * what the phone read from it is used.
     */
    private fun decode(extractor: MediaExtractor, edit: AudioStart.Edit?, progress: (Float) -> Unit, cancelled: () -> Boolean): Stereo {
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
            // The lead-in is dealt with here, the same on every phone: the decoder is asked to leave it alone
            // (some drop it, some don't, some older ones drop a wrong amount), and what it really did is checked below.
            val aac = mime == MediaFormat.MIMETYPE_AUDIO_AAC
            val trackRate = rate
            val phoneDelay = if (aac) declaredDelay(format) else 0
            if (aac) try {
                if (format.containsKey(KEY_DELAY)) format.setInteger(KEY_DELAY, 0)
                if (format.containsKey(KEY_PADDING)) format.setInteger(KEY_PADDING, 0)
            } catch (_: Exception) {}
            val firstSizes = IntArray(AudioStart.PIECES)
            var pieces = 0
            var firstPtsUs = -1L

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
                            if (pieces == 0) firstPtsUs = info.presentationTimeUs
                            if (pieces < firstSizes.size) firstSizes[pieces++] = frames
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
            // Drop the lead-in if the decoder handed it over; any doubt leaves the audio as it came.
            // The file's own word on its lead-in comes first; failing that, what the phone read from it.
            val fromFile = if (aac) edit?.samples(rate) ?: 0 else 0
            val start = try {
                val delay = if (fromFile > 0) fromFile
                    else if (phoneDelay in 1..AudioStart.MAX_SAMPLES && trackRate > 0) (phoneDelay.toLong() * rate / trackRate).toInt() else 0
                if (aac) AudioStart.drop(delay, rate, firstPtsUs, firstSizes.copyOf(pieces)) else AudioStart.Drop(0, "not AAC, left to the decoder")
            } catch (_: Exception) { AudioStart.Drop(0, "not checked") }
            val from = if (start.samples in 1 until n) start.samples else 0
            left = left.copyOfRange(from, n); right = right.copyOfRange(from, n)
            val note = start.why + (if (fromFile > 0) " (read from the file)" else if (phoneDelay != 0) " (the phone read $phoneDelay)" else "")
            val lateBefore = if (from > 0 && phoneDelay == 0) from.toDouble() / rate else 0.0
            val target = MdxSeparator.SAMPLE_RATE
            return if (rate == target) Stereo(left, right, note, lateBefore)
            else Stereo(resample(left, rate, target, 8), resample(right, rate, target, 8), note, lateBefore)
        } finally {
            extractor.release()
        }
    }

    // MediaFormat's keys for the lead-in and the tail an encoder adds (the constants are newer than the keys).
    private const val KEY_DELAY = "encoder-delay"
    private const val KEY_PADDING = "encoder-padding"
}
