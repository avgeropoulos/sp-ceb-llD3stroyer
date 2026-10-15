package com.bouzouki.core.song

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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
 * up with a tab) without preparing the song again. Activations are kept as bytes (1/255 steps; [of]
 * rounds them so, and the analysis derives its line from that, so the saved data gives the same line
 * again), written key by key as changes from the frame before, then deflated: a 4-minute song takes
 * about 4 MB.
 */
class AnalysisData(val act: BasicPitch.Activations, val attacks: Attacks?) {
    /** Writes the data to [out] (and closes it). */
    fun write(out: OutputStream) {
        val deflater = Deflater(6)
        try {
            DataOutputStream(BufferedOutputStream(DeflaterOutputStream(out, deflater), 1 shl 16)).use { write(it) }
        } finally {
            deflater.end()
        }
    }

    private fun write(d: DataOutputStream) {
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        val n = act.frames.size
        d.writeInt(n)
        d.writeInt(BasicPitch.N_KEYS)
        val column = ByteArray(n)
        // Each key's (bin's) bytes over time, as the change from the frame before: they change slowly, so this deflates well.
        fun writeColumn(value: (Int) -> Int) {
            var prev = 0
            for (t in 0 until n) {
                val b = value(t)
                column[t] = (b - prev).toByte()
                prev = b
            }
            d.write(column)
        }
        for (plane in arrayOf(act.frames, act.onsets)) for (k in 0 until BasicPitch.N_KEYS) {
            writeColumn { t -> BasicPitch.quantize(plane[t][k]).toInt() and 0xFF }
        }
        val contours = act.contours
        d.writeBoolean(contours != null)
        if (contours != null) {
            val bins = contours.firstOrNull()?.size ?: 0
            d.writeInt(bins)
            for (k in 0 until bins) writeColumn { t -> contours[t][k].toInt() and 0xFF }
        }
        val a = attacks
        d.writeBoolean(a != null)
        if (a != null) {
            d.writeInt(a.rises.size)
            for (v in a.rises) d.writeFloat(v)
            for (v in a.energies) d.writeFloat(v)
        }
    }

    companion object {
        private const val MAGIC = 0x425A4144 // "BZAD"
        /** 2: columns of changes (1 wrote the frames one after another). */
        const val VERSION = 2
        /** More frames than any song has (3 hours): a damaged length isn't allocated. */
        private const val MAX_FRAMES = 3 * 3600 * BasicPitch.ANNOTATIONS_FPS

        /** [act] and [attacks] as [write] saves them and [read] gives them back: the activations rounded to bytes. */
        fun of(act: BasicPitch.Activations, attacks: Attacks?): AnalysisData =
            AnalysisData(BasicPitch.Activations(rounded(act.frames), rounded(act.onsets), act.contours), attacks)

        private fun rounded(a: Array<FloatArray>) =
            Array(a.size) { t -> val row = a[t]; FloatArray(row.size) { k -> (BasicPitch.quantize(row[k]).toInt() and 0xFF) / 255f } }

        /** Reads what [write] wrote; null if it's another version or damaged. */
        fun read(input: InputStream): AnalysisData? = try {
            DataInputStream(BufferedInputStream(InflaterInputStream(input), 1 shl 16)).use { d ->
                if (d.readInt() != MAGIC || d.readInt() != VERSION) return null
                val n = d.readInt()
                val keys = d.readInt()
                if (keys != BasicPitch.N_KEYS || n !in 0..MAX_FRAMES) return null
                val column = ByteArray(n)
                fun readColumn(store: (Int, Int) -> Unit) {
                    d.readFully(column)
                    var v = 0
                    for (t in 0 until n) {
                        v = (v + column[t]) and 0xFF
                        store(t, v)
                    }
                }
                val frames = Array(n) { FloatArray(keys) }
                val onsets = Array(n) { FloatArray(keys) }
                for (plane in arrayOf(frames, onsets)) for (k in 0 until keys) readColumn { t, v -> plane[t][k] = v / 255f }
                val contours = if (d.readBoolean()) {
                    val bins = d.readInt()
                    if (bins !in 0..BasicPitch.N_CONTOUR_BINS) return null
                    Array(n) { ByteArray(bins) }.also { c -> for (k in 0 until bins) readColumn { t, v -> c[t][k] = v.toByte() } }
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

/**
 * How many notes the line keeps ([SongAnalyzer.rebuild]): [NORMAL] is the analysis default (the best balance of
 * notes found and notes right); [FEWER] keeps only the clearer notes, a sparser line; [MORE] also keeps weaker,
 * quieter notes, finding more of the tune at the price of more wrong ones.
 */
enum class Detail { FEWER, NORMAL, MORE }
