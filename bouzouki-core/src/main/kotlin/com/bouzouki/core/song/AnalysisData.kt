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
        val column = ByteArray(n) // one key's (bin's) bytes over time, as changes ([encodeColumn])
        for (plane in arrayOf(act.frames, act.onsets)) for (k in 0 until BasicPitch.N_KEYS) {
            encodeColumn(column) { t -> BasicPitch.quantize(plane[t][k]).toInt() and 0xFF }
            d.write(column)
        }
        val contours = act.contours
        d.writeBoolean(contours != null)
        if (contours != null) {
            val bins = contours.firstOrNull()?.size ?: 0
            d.writeInt(bins)
            for (k in 0 until bins) {
                encodeColumn(column) { t -> contours[t][k].toInt() and 0xFF }
                d.write(column)
            }
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
        /** More frames than a song has (30 minutes): a damaged length isn't allocated. */
        private const val MAX_FRAMES = 30 * 60 * BasicPitch.ANNOTATIONS_FPS
        /** The attack track's frames to each of Basic Pitch's, at most (about 4: hop 64 against 256), and some slack. */
        private const val ATTACK_FRAMES = 5
        private const val ATTACK_SLACK = 4096

        /** [act] and [attacks] as [write] saves them and [read] gives them back: the activations rounded to bytes. */
        fun of(act: BasicPitch.Activations, attacks: Attacks?): AnalysisData =
            AnalysisData(BasicPitch.Activations(rounded(act.frames), rounded(act.onsets), act.contours), attacks)

        private fun rounded(a: Array<FloatArray>) =
            Array(a.size) { t -> val row = a[t]; FloatArray(row.size) { k -> (BasicPitch.quantize(row[k]).toInt() and 0xFF) / 255f } }

        /**
         * Reads what [write] wrote; null if it's another version or damaged. A damaged length is refused before
         * anything is allocated for it: the frames must fit a song and the file must hold their first column, and
         * the attack track must fit the frames.
         */
        fun read(input: InputStream): AnalysisData? = try {
            DataInputStream(BufferedInputStream(InflaterInputStream(input), 1 shl 16)).use { d ->
                if (d.readInt() != MAGIC || d.readInt() != VERSION) return null
                val n = d.readInt()
                val keys = d.readInt()
                if (keys != BasicPitch.N_KEYS || n !in 0..MAX_FRAMES) return null
                val column = ByteArray(n)
                d.readFully(column) // the first key's column: a file shorter than its length says ends here
                val frames = Array(n) { FloatArray(keys) }
                val onsets = Array(n) { FloatArray(keys) }
                var first = true
                for (plane in arrayOf(frames, onsets)) for (k in 0 until keys) {
                    if (!first) d.readFully(column)
                    first = false
                    decodeColumn(column) { t, v -> plane[t][k] = v / 255f }
                }
                val contours = if (d.readBoolean()) {
                    val bins = d.readInt()
                    if (bins !in 0..BasicPitch.N_CONTOUR_BINS) return null
                    Array(n) { ByteArray(bins) }.also { c ->
                        for (k in 0 until bins) {
                            d.readFully(column)
                            decodeColumn(column) { t, v -> c[t][k] = v.toByte() }
                        }
                    }
                } else null
                val attacks = if (d.readBoolean()) {
                    val m = d.readInt()
                    if (m < 0 || m > ATTACK_FRAMES.toLong() * n + ATTACK_SLACK) return null
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
 * Fills [column] with each frame's value (a byte, 0..255) as the change from the frame before: a key's activations
 * change slowly, so this deflates well. Inline, so no value is boxed.
 */
private inline fun encodeColumn(column: ByteArray, value: (Int) -> Int) {
    var prev = 0
    for (t in column.indices) {
        val b = value(t)
        column[t] = (b - prev).toByte()
        prev = b
    }
}

/** Gives [store] each frame's value back from what [encodeColumn] made of it. */
private inline fun decodeColumn(column: ByteArray, store: (Int, Int) -> Unit) {
    var v = 0
    for (t in column.indices) {
        v = (v + column[t]) and 0xFF
        store(t, v)
    }
}

/**
 * How many notes the line keeps ([SongAnalyzer.rebuild]): [NORMAL] is the analysis default (the best balance of
 * notes found and notes right); [FEWER] is that line without its weaker notes (at most about a fifth of them), a
 * sparser, cleaner line; [MORE] keeps every note of the normal line and adds weaker, quieter ones between them,
 * finding more of the tune at the price of more wrong ones.
 */
enum class Detail { FEWER, NORMAL, MORE }
