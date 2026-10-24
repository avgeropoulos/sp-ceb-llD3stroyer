package com.bouzouki.core.song

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Where in a song the bouzouki plays, and how much each candidate note sounds like it, from Basic Pitch's activations
 * and the attack tracks: the line keeps out of an intro or a verse the bouzouki sits out instead of following the
 * loudest instrument (a keyboard pad, a strummed guitar, a bass), and a note under the melody's register costs the line
 * less the more it sounds picked ([SongAnalyzer.bouzoukiLine]).
 *
 * A candidate sounds like the bouzouki ([like], 0..1) when its attack adds bright energy (a plectrum's click and
 * bright attack, against the song's typical candidate; in a quiet passage, where a pick adds little, an attack far
 * sharper than the song's typical frame counts the same), its pitch's tone is short around it (a picked note dies away
 * or is stopped for the next; a pad, a held keyboard or bowed note, and a chord strummed again and again keep one
 * pitch sounding for seconds), it is the loudest of the notes starting with it (a strum starts several at once, the
 * melody stands out of it), and it lies where the melody does: the bouzouki's melody stays on the D and A courses (A3
 * up), and long stretches lower down, on the C and F courses, are the accompaniment's register.
 *
 * The bouzouki plays around a time when its candidates within [REACH] seconds bring at least [LEVEL] of that per
 * second ([plays]); in a song whose busiest stretches bring little (a quiet or blurred recording), less is enough.
 *
 * [all] are the candidates of every pitch ([SongAnalyzer.candidates] with [Detail.NORMAL], so every detail level judges
 * alike and its lines nest).
 */
internal class Presence(all: List<NoteEvent>, private val act: BasicPitch.Activations, private val attacks: Attacks) {
    private val notes = all.filter { !it.onset.isNaN() && it.frame >= 0 }.sortedBy { it.start }
    private val starts = DoubleArray(notes.size) { notes[it].start }

    /** [like] by candidate (its first frame and pitch). */
    private val liked = HashMap<Long, Double>()

    /** The energy the typical candidate's attack in the bouzouki's range adds. */
    private val energyRef: Double = run {
        val e = notes.filter { it.midi in RANGE }.map { attacks.energy(it.start) }.sorted()
        if (e.isEmpty()) 0.0 else e[e.size / 2]
    }

    /** [like] of each candidate in the bouzouki's range, by start, and their running sums (for [density]). */
    private val inRange = notes.filter { it.midi in RANGE }
    private val rangeStarts = DoubleArray(inRange.size) { inRange[it].start }
    private val sums = DoubleArray(inRange.size + 1).also { s -> for (i in inRange.indices) s[i + 1] = s[i] + like(inRange[i]) }
    private val songEnd = BasicPitch.frameTime(act.frames.size)

    /** The level [plays] asks for in this song. */
    private val threshold: Double = run {
        if (inRange.isEmpty()) 0.0 else {
            val d = inRange.map { density(it.start) }.sorted()
            min(LEVEL, RELATIVE * d[min(d.size - 1, (0.9 * d.size).toInt())])
        }
    }

    /** Whether the bouzouki plays, every [STEP] seconds: where it seems not to for less than [QUIET] seconds, it does. */
    private val grid: BooleanArray = run {
        val g = BooleanArray((songEnd / STEP).toInt() + 1) { density(it * STEP) >= threshold }
        var i = 0
        while (i < g.size) {
            if (g[i]) { i++; continue }
            var j = i
            while (j < g.size && !g[j]) j++
            if ((j - i) * STEP < QUIET) for (k in i until j) g[k] = true
            i = j
        }
        g
    }

    /**
     * Whether the bouzouki seems to play around [t] (seconds): it sits out only where its candidates bring too little
     * for [QUIET] seconds or more (an intro, a verse), not over a pause or a few weak notes.
     */
    fun plays(t: Double): Boolean = grid[(t / STEP).toInt().coerceIn(0, grid.size - 1)]

    /** How much [n] sounds like the bouzouki's own picked note, 0..1 (a note without onset evidence: 1). */
    fun like(n: NoteEvent): Double {
        if (n.onset.isNaN() || n.frame < 0 || energyRef <= 0) return 1.0
        return liked.getOrPut(n.frame.toLong() * 256 + n.midi) { measure(n) }
    }

    private fun measure(n: NoteEvent): Double {
        // A pick adds bright energy, or (in a quiet passage, where it adds little) rises far above the quiet around it.
        val energy = max(ln(attacks.energy(n.start) / energyRef + ENERGY_OFFSET), ln(attacks.rise(n.start) / RISE_CLEAR))
            .coerceIn(-ENERGY_FLOOR, ENERGY_CAP)
        val low = max(0, MELODY_LOW - n.midi) / 12.0
        var z = BIAS + ENERGY_WEIGHT * energy - SPAN_WEIGHT * min(SPAN_CAP, span(n)) - LOW_WEIGHT * low
        val (others, top) = company(n)
        z += -POLY_WEIGHT * min(POLY_CAP, others) + if (top) TOP_WEIGHT else 0.0
        return 1 / (1 + exp(-z))
    }

    /** What the candidates in range within [REACH] of [t] bring per second (the part of the window inside the song). */
    private fun density(t: Double): Double {
        val a = lowerBound(rangeStarts, t - REACH)
        val b = lowerBound(rangeStarts, t + REACH + 1e-9)
        val width = min(songEnd, t + REACH) - max(0.0, t - REACH)
        return (sums[b] - sums[a]) / max(REACH, width)
    }

    /**
     * How long (seconds) [n]'s pitch keeps sounding around its start: frames at [SPAN_LEVEL] or more before and after
     * it (a dip of up to 2 frames bridged), each way at most [SPAN_CAP].
     */
    private fun span(n: NoteEvent): Double {
        val k = n.midi - BasicPitch.MIDI_OFFSET
        val frames = act.frames
        val most = (SPAN_CAP * BasicPitch.ANNOTATIONS_FPS).toInt()
        fun extent(dir: Int): Int {
            var t = n.frame
            var gap = 0
            var len = 0
            while (t in frames.indices && gap <= 2 && abs(t - n.frame) <= most) {
                if (frames[t][k] >= SPAN_LEVEL) { gap = 0; len = abs(t - n.frame) } else gap++
                t += dir
            }
            return len
        }
        return (extent(-1) + extent(1)) / BasicPitch.ANNOTATIONS_FPS.toDouble()
    }

    /** How many other candidates start within [TOGETHER] of [n], and whether none of them sounds clearly louder. */
    private fun company(n: NoteEvent): Pair<Int, Boolean> {
        val mine = n.salience + 0.5 * n.onset
        var others = 0
        var top = true
        var i = lowerBound(starts, n.start - TOGETHER)
        while (i < notes.size && notes[i].start <= n.start + TOGETHER) {
            val o = notes[i++]
            if (o === n || (o.midi == n.midi && o.frame == n.frame)) continue
            others++
            if (o.salience + 0.5 * o.onset > mine + LOUDER) top = false
        }
        return others to top
    }

    private fun lowerBound(a: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < x) lo = m + 1 else hi = m }
        return lo
    }

    companion object {
        /** The bouzouki's range (C3 to the top of the tetrachordo's D course). */
        val RANGE = 48..89
        // How a candidate's measures weigh: a logistic model of "a bouzouki note" fitted on synthesized band songs and
        // productions with keyboards (scratch evaluation), rounded, with the strum's weight raised so the top string of a
        // strummed accompaniment counts less. Bright attack energy, a short tone, the loudest of those starting with it,
        // the register.
        private const val BIAS = -1.6
        /** Per natural-log step of the energy its attack adds against the typical candidate's ... */
        private const val ENERGY_WEIGHT = 1.2
        /** ... with this share of the typical energy added (so no attack counts as nothing), counted from -[ENERGY_FLOOR] to [ENERGY_CAP]. */
        private const val ENERGY_OFFSET = 0.05
        private const val ENERGY_FLOOR = 3.0
        private const val ENERGY_CAP = 1.5
        /** An attack this sharp (times the song's median frame) counts as a pick of the typical energy, however quiet. */
        private const val RISE_CLEAR = 70.0
        /** Per second its pitch sounds around its start (at [SPAN_LEVEL] or more), at most [SPAN_CAP] seconds each way. */
        private const val SPAN_WEIGHT = 0.9
        private const val SPAN_LEVEL = 0.2f
        private const val SPAN_CAP = 2.0
        /** Per octave under [MELODY_LOW] (A3, the open A course): the C and F courses' register. */
        private const val LOW_WEIGHT = 2.7
        const val MELODY_LOW = 57
        /** Per other candidate starting within [TOGETHER] seconds (a strum), at most [POLY_CAP] of them ... */
        private const val POLY_WEIGHT = 1.0
        private const val POLY_CAP = 4
        private const val TOGETHER = 0.03
        /** ... and what it gains for none of them sounding louder by [LOUDER] (salience and half the onset). */
        private const val TOP_WEIGHT = 2.3
        private const val LOUDER = 0.1
        /** The bouzouki plays where its candidates within this many seconds either way ... */
        private const val REACH = 3.0
        /** ... bring at least this much [like] per second, or [RELATIVE] of what the song's busiest tenth brings, if less. */
        private const val LEVEL = 0.6
        private const val RELATIVE = 0.35
        /** The bouzouki sits out a stretch of at least this many seconds ... */
        private const val QUIET = 5.0
        /** ... judged every this many seconds. */
        private const val STEP = 0.25
    }
}
