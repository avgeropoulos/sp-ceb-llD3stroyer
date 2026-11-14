package com.mixdeck.engine

import java.io.Closeable
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.File

/**
 * Streams 16-bit stereo PCM into a WAV file and fixes up the header sizes when closed.
 * Used to record mixes.
 */
class WavWriter(private val file: File, private val sampleRate: Int) : Closeable {
    private val out: OutputStream = file.outputStream().buffered(1 shl 16)
    private var dataBytes = 0L
    private val block = ByteArray(8192)

    init { out.write(header(sampleRate, 0)) }

    val seconds get() = dataBytes / 4.0 / sampleRate

    /** Writes interleaved stereo floats. */
    fun write(samples: FloatArray, frames: Int) {
        var n = 0
        for (i in 0 until frames * 2) {
            val s = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            block[n++] = (s and 0xFF).toByte()
            block[n++] = ((s shr 8) and 0xFF).toByte()
            if (n == block.size) { out.write(block, 0, n); n = 0 }
        }
        if (n > 0) out.write(block, 0, n)
        dataBytes += frames * 4L
    }

    /**
     * Finishes the file. If the last of it can't be written (storage full), it still throws, but
     * the header first describes what did reach the file, so the recording up to there plays.
     */
    override fun close() {
        try { out.close() } finally { repair(file) }
    }

    companion object {
        /**
         * Makes [f] a valid WAV of everything written to it: the sizes in its header are set from
         * the file's length, in whole frames. For a recording that wasn't closed (the app was
         * killed) or whose last write failed. Returns how many seconds of audio it holds.
         */
        fun repair(f: File): Double {
            RandomAccessFile(f, "rw").use { raf ->
                if (raf.length() < 44) return 0.0
                val h = ByteArray(44); raf.readFully(h)
                val sr = (0..3).fold(0L) { v, k -> v or ((h[24 + k].toLong() and 0xFF) shl (8 * k)) }.toInt()
                val data = (raf.length() - 44) / 4 * 4
                raf.seek(0)
                raf.write(header(sr, data))
                return if (sr > 0) data / 4.0 / sr else 0.0
            }
        }

        private fun header(sampleRate: Int, data: Long): ByteArray {
            val h = ByteArray(44)
            fun str(o: Int, s: String) { s.forEachIndexed { i, c -> h[o + i] = c.code.toByte() } }
            fun i32(o: Int, v: Long) { for (k in 0..3) h[o + k] = ((v shr (8 * k)) and 0xFF).toByte() }
            fun i16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v shr 8) and 0xFF).toByte() }
            val size = data.coerceAtMost(0xFFFFFFFFL - 36)
            str(0, "RIFF"); i32(4, 36 + size); str(8, "WAVE")
            str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, 2); i32(24, sampleRate.toLong())
            i32(28, sampleRate * 4L); i16(32, 4); i16(34, 16)
            str(36, "data"); i32(40, size)
            return h
        }
    }
}

/**
 * The WAV files REC writes ("recording-<start time>.wav"), and finding those a run left behind:
 * MixDeck was killed or crashed while recording, or the mix couldn't be saved. Each host saves
 * them as mixes when it starts.
 */
object Recordings {
    private const val PREFIX = "recording-"

    /** The file for a recording started at [now], in [dir]. */
    fun newFile(dir: File, now: Long = System.currentTimeMillis()) = File(dir, "$PREFIX$now.wav")

    /** When recording [f] started (from its name), or null if it isn't one. */
    fun startedAt(f: File): Long? =
        if (f.name.startsWith(PREFIX) && f.name.endsWith(".wav")) f.name.removePrefix(PREFIX).removeSuffix(".wav").toLongOrNull() else null

    /**
     * Recordings in [dirs] nobody has written to for [idleMs] (a recording still running is written
     * to all the time), oldest first. Call it before this run starts recording.
     */
    fun leftovers(dirs: List<File>, idleMs: Long, now: Long = System.currentTimeMillis()): List<File> =
        dirs.flatMap { d -> d.listFiles { f -> f.isFile && startedAt(f) != null && now - f.lastModified() >= idleMs }.orEmpty().toList() }
            .sortedBy { startedAt(it) }
}
