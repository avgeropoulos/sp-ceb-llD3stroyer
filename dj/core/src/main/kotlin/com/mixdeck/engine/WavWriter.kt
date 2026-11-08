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

    init { out.write(header(0)) }

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

    override fun close() {
        out.close()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(dataBytes))
        }
    }

    private fun header(data: Long): ByteArray {
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
