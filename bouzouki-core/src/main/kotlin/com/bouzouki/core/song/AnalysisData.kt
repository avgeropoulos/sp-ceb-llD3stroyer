package com.bouzouki.core.song

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * What a song's bouzouki line is derived from: Basic Pitch's activations and the attack tracks.
 * It is saved next to the song so the line can be derived again (with more or fewer notes, or lined
 * up with a tab) without preparing the song again. Activations are kept as bytes (1/255 steps) and the
 * file is deflated: they are mostly near zero, so a 4-minute song takes about a megabyte or two.
 */
class AnalysisData(val act: BasicPitch.Activations, val attacks: Attacks?) {
    fun write(out: OutputStream) {
        DataOutputStream(DeflaterOutputStream(out, Deflater(6))).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(VERSION)
            val n = act.frames.size
            d.writeInt(n)
            d.writeInt(BasicPitch.N_KEYS)
            val row = ByteArray(BasicPitch.N_KEYS)
            for (t in 0 until n) {
                for (k in row.indices) row[k] = BasicPitch.quantize(act.frames[t][k])
                d.write(row)
                for (k in row.indices) row[k] = BasicPitch.quantize(act.onsets[t][k])
                d.write(row)
            }
            val contours = act.contours
            d.writeBoolean(contours != null)
            if (contours != null) {
                d.writeInt(contours.firstOrNull()?.size ?: 0)
                for (c in contours) d.write(c)
            }
            val a = attacks
            d.writeBoolean(a != null)
            if (a != null) {
                d.writeInt(a.rises.size)
                for (v in a.rises) d.writeFloat(v)
                for (v in a.energies) d.writeFloat(v)
            }
        }
    }

    companion object {
        private const val MAGIC = 0x425A4144 // "BZAD"
        const val VERSION = 1

        /** Reads what [write] wrote; null if it's another version or damaged. */
        fun read(input: InputStream): AnalysisData? = try {
            DataInputStream(InflaterInputStream(input)).use { d ->
                if (d.readInt() != MAGIC || d.readInt() != VERSION) return null
                val n = d.readInt()
                val keys = d.readInt()
                if (keys != BasicPitch.N_KEYS || n < 0) return null
                val row = ByteArray(keys)
                val frames = Array(n) { FloatArray(keys) }
                val onsets = Array(n) { FloatArray(keys) }
                for (t in 0 until n) {
                    d.readFully(row)
                    for (k in 0 until keys) frames[t][k] = (row[k].toInt() and 0xFF) / 255f
                    d.readFully(row)
                    for (k in 0 until keys) onsets[t][k] = (row[k].toInt() and 0xFF) / 255f
                }
                val contours = if (d.readBoolean()) {
                    val bins = d.readInt()
                    Array(n) { ByteArray(bins).also { d.readFully(it) } }
                } else null
                val attacks = if (d.readBoolean()) {
                    val m = d.readInt()
                    val rises = FloatArray(m) { d.readFloat() }
                    val energies = FloatArray(m) { d.readFloat() }
                    Attacks(rises, energies)
                } else null
                AnalysisData(BasicPitch.Activations(frames, onsets, contours), attacks)
            }
        } catch (_: Exception) { null }
    }
}

/** How many notes the line keeps: [NORMAL] is the analysis default; [MORE] also keeps weaker, shorter notes. */
enum class Detail { FEWER, NORMAL, MORE }
