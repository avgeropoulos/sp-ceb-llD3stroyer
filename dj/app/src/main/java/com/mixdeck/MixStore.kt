package com.mixdeck

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A saved mix. */
data class Mix(val uri: Uri, val name: String, val durationMs: Long, val size: Long, val dateSec: Long, val file: File? = null)

/**
 * Saved mixes live in Music/MixDeck, where music players and file managers can see them and
 * they survive uninstalling the app. (On Android 9 and older they stay in the app's own folder.)
 */
class MixStore(private val ctx: Context) {
    private val legacyDir get() = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Mixes").apply { mkdirs() }
    private val relPath = Environment.DIRECTORY_MUSIC + "/MixDeck/"

    fun list(): List<Mix> {
        if (Build.VERSION.SDK_INT < 29) {
            return legacyDir.listFiles().orEmpty().filter { it.isFile }.map {
                Mix(Uri.fromFile(it), it.nameWithoutExtension, durationOf(it), it.length(), it.lastModified() / 1000, it)
            }.sortedByDescending { it.dateSec }
        }
        val out = ArrayList<Mix>()
        val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED)
        ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?", arrayOf(relPath), "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                val name = (c.getString(1) ?: "Mix").substringBeforeLast('.')
                out += Mix(uri, name, c.getLong(2), c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    /**
     * Saves a recorded WAV as "[name].m4a" (AAC, 192 kbps: about 1.4 MB a minute). Falls back to
     * keeping the WAV if this phone can't encode AAC. Deletes [wav] once it's saved; if saving
     * fails, [wav] stays (it's saved next time MixDeck opens), so the mix is never lost.
     */
    fun save(wav: File, name: String, progress: (Float) -> Unit): Mix {
        val clean = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "-").ifEmpty { "Mix" }
        val m4a = File(ctx.cacheDir, "mix-encode.m4a")
        var saved = false
        val encoded = runCatching { AacEncoder.encode(wav, m4a, progress) }.isSuccess
        val src = if (encoded) m4a else wav
        val ext = if (encoded) "m4a" else "wav"
        val mime = if (encoded) "audio/mp4" else "audio/wav"
        try {
            if (Build.VERSION.SDK_INT < 29) {
                val dest = uniqueFile(legacyDir, clean, ext)
                try { src.copyTo(dest) } catch (e: Exception) { dest.delete(); throw e }
                saved = true
                return Mix(Uri.fromFile(dest), dest.nameWithoutExtension, durationOf(dest), dest.length(), dest.lastModified() / 1000, dest)
            }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$clean.$ext")
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
                put(MediaStore.Audio.Media.ALBUM, "MixDeck mixes")
                put(MediaStore.Audio.Media.ARTIST, "MixDeck")
                put(MediaStore.Audio.Media.TITLE, clean)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Couldn't create the file")
            try {
                r.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                r.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                r.delete(uri, null, null); throw e
            }
            saved = true
            return Mix(uri, clean, durationOf(src), src.length(), System.currentTimeMillis() / 1000)
        } finally {
            m4a.delete()
            if (saved) wav.delete()
        }
    }

    fun rename(m: Mix, newName: String): Boolean {
        val clean = newName.trim().replace(Regex("""[\\/:*?"<>|]"""), "-").ifEmpty { return false }
        if (m.file != null) {
            val dest = uniqueFile(m.file.parentFile!!, clean, m.file.extension)
            return m.file.renameTo(dest)
        }
        val ext = if (Build.VERSION.SDK_INT >= 29) extensionOf(m) else "m4a"
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$clean.$ext")
            put(MediaStore.Audio.Media.TITLE, clean)
        }
        return ctx.contentResolver.update(m.uri, v, null, null) > 0
    }

    fun delete(m: Mix): Boolean =
        if (m.file != null) m.file.delete() else ctx.contentResolver.delete(m.uri, null, null) > 0

    private fun extensionOf(m: Mix): String {
        ctx.contentResolver.query(m.uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: "").substringAfterLast('.', "m4a")
        }
        return "m4a"
    }

    private fun uniqueFile(dir: File, name: String, ext: String): File {
        var f = File(dir, "$name.$ext"); var n = 2
        while (f.exists()) { f = File(dir, "$name ($n).$ext"); n++ }
        return f
    }

    private fun durationOf(f: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (e: Exception) { 0 } finally { r.release() }
    }
}

/** Encodes a 16-bit stereo WAV to AAC in an .m4a file with Android's built-in encoder. */
object AacEncoder {
    fun encode(wav: File, out: File, progress: (Float) -> Unit) {
        val (sampleRate, dataStart, dataLen) = readWavHeader(wav)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        codec.start()
        val info = MediaCodec.BufferInfo()
        val input = wav.inputStream().buffered(1 shl 16)
        input.skip(dataStart)
        var fed = 0L
        var inputDone = false
        val chunk = ByteArray(8192)
        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        // In Long: past 2 GB (over 3 hours) an Int would wrap and end the input at once.
                        val want = minOf(buf.capacity().toLong(), chunk.size.toLong(), dataLen - fed).coerceAtLeast(0L).toInt() and 3.inv()
                        val n = if (want > 0) input.read(chunk, 0, want) else -1
                        val ptsUs = fed / 4 * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(chunk, 0, n)
                            codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                            fed += n
                            progress((fed.toFloat() / dataLen).coerceIn(0f, 1f))
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            input.close()
            runCatching { codec.stop() }; codec.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
        if (!muxing || out.length() == 0L) throw IOException("AAC encoding failed")
    }

    /** Returns (sample rate, offset of the PCM data, its length in bytes) for our own WAV files. */
    private fun readWavHeader(f: File): Triple<Int, Long, Long> {
        RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(44); raf.readFully(h)
            fun i32(o: Int) = (h[o].toLong() and 0xFF) or ((h[o + 1].toLong() and 0xFF) shl 8) or
                ((h[o + 2].toLong() and 0xFF) shl 16) or ((h[o + 3].toLong() and 0xFF) shl 24)
            val sr = i32(24).toInt()
            // If the app was killed mid-recording the header sizes are 0; use the file length.
            val len = i32(40).takeIf { it > 0 } ?: (f.length() - 44)
            return Triple(sr, 44L, minOf(len, f.length() - 44))
        }
    }
}
