package com.bouzoukihero.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Synthesizes the built-in songs, so the game needs no audio files. The bouzouki is
 * additive synthesis of a bright plucked string, doubled with a slightly detuned
 * second string like a real paired course. The lead and the backing come out
 * separately, so the game can mute the bouzouki when you miss a note.
 */
object Synth {
    const val RATE = 22050

    class Rendered(val lead: FloatArray, val backing: FloatArray, val notes: List<SourceNote>) {
        val seconds get() = lead.size.toDouble() / RATE
    }

    private const val TABLE = 4096
    private val sine = FloatArray(TABLE) { sin(2 * PI * it / TABLE).toFloat() }

    fun render(tune: Tune): Rendered {
        val melody = tune.melody()
        val bars = tune.bars().size
        val unit = tune.unitSec
        val start = Tune.LEAD_IN
        val melodyStart = start + tune.introBars * tune.barSec
        // Intro bars, the melody, then one bar to ring out on the last chord.
        val total = ((melodyStart + (bars + 1) * tune.barSec + 1.0) * RATE).toInt()
        val lead = FloatArray(total)
        val backing = FloatArray(total)

        for (n in melody) {
            val t0 = melodyStart + n.start * unit
            val len = n.len * unit
            val f = Tune.freq(n.midi)
            if (n.tremolo) {
                // Tremolo: fast alternate picking, about 13 strokes a second.
                val strokes = max(2, (len * 13).toInt())
                val step = len / strokes
                for (k in 0 until strokes) {
                    pluck(lead, t0 + k * step, f, step + 0.02, if (k % 2 == 0) 0.32f else 0.26f, bright = 1f)
                }
            } else {
                pluck(lead, t0 + 0.004, f, len + 0.15, 0.36f, bright = 1f)
            }
        }

        // Backing: the intro, every melody bar, and a final bar on the last chord.
        val chords = tune.barChords()
        val allBars = tune.introBars + bars + 1
        for (b in 0 until allBars) {
            val chord = when {
                b < tune.introBars -> chords.first()
                b - tune.introBars < chords.size -> chords[b - tune.introBars]
                else -> chords.last()
            }
            val (root, minor) = parseChord(chord)
            val barT = start + b * tune.barSec
            val last = b == allBars - 1
            for (u in 0 until tune.unitsPerBar) {
                val t = barT + u * unit
                if (last && u > 0) break
                when (tune.bass.getOrElse(u) { '.' }) {
                    'R' -> bass(backing, t, root + 36, unit * 4)
                    'F' -> bass(backing, t, root + 43, unit * 4)
                    'O' -> bass(backing, t, root + 48, unit * 4)
                }
                if (tune.strum.getOrElse(u) { '.' } == 'x' || (last && u == 0)) {
                    val third = if (minor) 3 else 4
                    val ring = if (last) 1.6 else unit * 2.2
                    for ((i, iv) in intArrayOf(0, 7, 12, 12 + third).withIndex()) {
                        pluck(backing, t + i * 0.012, Tune.freq(root + 48 + iv), ring, 0.075f, bright = 0.35f)
                    }
                }
                if (!last) drum(backing, t, tune.drums.getOrElse(u) { '.' })
            }
        }
        if (bars > 0) drum(backing, start + (tune.introBars + bars) * tune.barSec, 'D')

        // One gain for both parts keeps their balance and avoids clipping.
        var peak = 0f
        for (i in 0 until total) peak = max(peak, kotlin.math.abs(lead[i] + backing[i]))
        val g = if (peak > 0.95f) 0.95f / peak else 1f
        for (i in 0 until total) {
            lead[i] *= g
            backing[i] *= g
        }
        return Rendered(lead, backing, tune.sourceNotes())
    }

    private fun parseChord(c: String): Pair<Int, Boolean> {
        val minor = c.endsWith("m")
        val name = c.removeSuffix("m")
        // Root as a pitch class 0..11.
        return (Tune.midiOf(name + "4") - 60) to minor
    }

    /** A plucked string: harmonics that die away, high ones faster, plus a pick click. */
    fun pluck(out: FloatArray, t: Double, freq: Double, len: Double, amp: Float, bright: Float) {
        val s0 = (t * RATE).toInt()
        if (s0 >= out.size || s0 < 0) return
        val n = min(out.size - s0, (len * RATE).toInt())
        val fade = min(n, (0.03 * RATE).toInt())
        val partials = if (bright > 0.5f) 9 else 5
        val inc = IntArray(partials * 2)
        val gains = FloatArray(partials * 2)
        val decays = FloatArray(partials * 2)
        for (k in 1..partials) {
            for (s in 0..1) {
                // Second string of the course, a few cents sharp.
                val f = freq * k * (if (s == 0) 1.0 else 1.0018)
                if (f > RATE * 0.45) continue
                val i = (k - 1) * 2 + s
                inc[i] = (f / RATE * TABLE * 65536).toInt()
                // Picked near the bridge: upper partials stay strong.
                gains[i] = (amp / k.toDouble().pow(if (bright > 0.5f) 0.75 else 1.4)).toFloat() * 0.5f
                decays[i] = exp(-(2.2 + 1.3 * k * (1.1 - bright)) / RATE).toFloat()
            }
        }
        val phase = IntArray(partials * 2) { it * 977 }
        val env = FloatArray(partials * 2) { 1f }
        var seed = (freq * 1000).toInt() or 1
        for (j in 0 until n) {
            var v = 0f
            for (i in 0 until partials * 2) {
                if (inc[i] == 0) continue
                phase[i] += inc[i]
                env[i] *= decays[i]
                v += sine[(phase[i] ushr 16) and (TABLE - 1)] * gains[i] * env[i]
            }
            if (j < 90) {
                seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
                v += ((seed and 0xFFFF) / 32768f - 1f) * amp * 0.25f * (1f - j / 90f) * bright
            }
            val attack = min(1f, j / 30f)
            val release = if (j > n - fade) (n - j).toFloat() / fade else 1f
            out[s0 + j] += v * attack * release
        }
    }

    private fun bass(out: FloatArray, t: Double, midi: Int, len: Double) {
        val s0 = (t * RATE).toInt()
        if (s0 >= out.size) return
        val n = min(out.size - s0, (len * RATE).toInt())
        val f = Tune.freq(midi)
        val inc = (f / RATE * TABLE * 65536).toInt()
        var ph = 0
        for (j in 0 until n) {
            ph += inc
            val e = exp(-3.0 * j / RATE).toFloat() * min(1f, j / 60f) * (if (j > n - 300) (n - j) / 300f else 1f)
            val p = ph ushr 16
            out[s0 + j] += (sine[p and (TABLE - 1)] + 0.35f * sine[(p * 2) and (TABLE - 1)]) * 0.2f * e
        }
    }

    private fun drum(out: FloatArray, t: Double, c: Char) {
        if (c == '.' || c == ' ') return
        val s0 = (t * RATE).toInt()
        if (s0 >= out.size) return
        val dur = if (c == 'D') 0.22 else 0.07
        val n = min(out.size - s0, (dur * RATE).toInt())
        var seed = 0x2545F491 + s0
        var prev = 0f
        var ph = 0.0
        for (j in 0 until n) {
            val x = j.toFloat() / n
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            val noise = (seed and 0xFFFF) / 32768f - 1f
            out[s0 + j] += when (c) {
                'D' -> {
                    // Doum: a deep thump that drops in pitch.
                    ph += (110.0 - 50.0 * x) / RATE
                    (sin(2 * PI * ph).toFloat() * 0.34f + noise * 0.02f) * (1 - x) * (1 - x)
                }
                else -> {
                    // Tek / ka: a bright slap on the drum's rim.
                    val hp = noise - prev
                    prev = noise
                    ph += 2200.0 / RATE
                    val vol = if (c == 't') 0.12f else 0.06f
                    (hp * vol + sin(2 * PI * ph).toFloat() * vol * 0.3f) * (1 - x).pow(3)
                }
            }
        }
    }

    /** A dull muted-string thunk for a missed note. */
    fun missSound(): FloatArray {
        val out = FloatArray((0.18 * RATE).toInt())
        pluck(out, 0.0, 98.0, 0.18, 0.5f, bright = 0.2f)
        pluck(out, 0.004, 103.8, 0.15, 0.4f, bright = 0.2f)
        for (i in out.indices) out[i] *= 1f - i.toFloat() / out.size
        return out
    }

    /** A short bright click, played for each tap while recording a chart. */
    fun tickSound(): FloatArray {
        val out = FloatArray((0.08 * RATE).toInt())
        pluck(out, 0.0, 1318.5, 0.08, 0.4f, bright = 1f)
        return out
    }
}
