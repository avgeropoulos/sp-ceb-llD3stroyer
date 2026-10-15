package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where on the neck to play each note of a song's line, the way Greek players finger a melody: on the
 * top two courses (A and D), moving the hand up and down the neck along a string rather than across it.
 * The bass courses are left for the notes only they reach (and for drones): on the tetrachordo F for the
 * notes below A3 and the low passages around them, C hardly ever; on the trichordo the low D course.
 *
 * A dynamic programme (Viterbi) runs over every (course, fret, hand position) a note can be played at.
 * The hand covers [Weights.span] frets from where the index finger rests, one more being a stretch.
 * Moving it is a shift: cheap when there is time (a long note, a pause, a new phrase, a big interval)
 * and dear inside a fast group. Crossing to another course costs more the further it takes the hand
 * from the melody courses, and each note costs by its course and by how high up the neck it is.
 * A legato note (hammered, pulled or slid from the one before) stays on that note's course, and a
 * slide is the shift itself, so it may go any distance.
 */
object Fingering {
    /** What each choice costs; [weights] gives the ones for each bouzouki. */
    class Weights(
        /** A note on each course, low → high: the melody belongs on the top two. */
        val course: DoubleArray,
        /** Moving from one course ([from][to]) to another between two notes. */
        val cross: Array<DoubleArray>,
        /** Frets under the fingers with the hand still: the index finger's fret and the next three. */
        val span: Int = 4,
        /** Each of those frets, from the index finger's: most players fret with three fingers, so the fourth is a reach. */
        val finger: DoubleArray = doubleArrayOf(0.0, 0.0, 0.03, 0.12),
        /** A note one fret beyond the span, with the hand still; [highStretch] of that from [highFrom] up (narrower frets). */
        val stretch: Double = 0.6,
        val highStretch: Double = 0.4,
        /** Moving the hand, plus [shiftPerFret] per fret it moves, in a fast group (less with time). */
        val shift: Double = 0.5,
        val shiftPerFret: Double = 0.06,
        /** A picked jump of more than [reach] frets in a fast group, per fret beyond (the hand leaps). */
        val reach: Int = 4,
        val leap: Double = 0.4,
        /**
         * A shift costs [shiftAcross] times as much when the hand changes course at the same time, [slide] times
         * as much as a slide (a legato note), and [aroundOpen] times as much around an open string (the hand is free).
         */
        val shiftAcross: Double = 1.25,
        val slide: Double = 0.15,
        val aroundOpen: Double = 0.5,
        /** Leaving the course on a legato note: only when it cannot stay (the note isn't on that course). */
        val legatoBreak: Double = 25.0,
        /** An open string inside a phrase, and at either end of one. */
        val open: Double = 0.5,
        val openAtEdge: Double = 0.15,
        /** Each fret above [highFrom], and (much more) above [tooHighFrom]. */
        val highFrom: Int = 12,
        val high: Double = 0.03,
        val tooHighFrom: Int = 22,
        val tooHigh: Double = 0.8,
        /** A shift costs fully at [fastGap] between onsets or less, [slowFactor] of that at [slowGap] and more. */
        val fastGap: Double = 0.12,
        val slowGap: Double = 0.4,
        val slowFactor: Double = 0.5,
        /** Shifting and crossing at the start of a phrase. */
        val phraseShift: Double = 0.25,
        val phraseCross: Double = 0.6,
    )

    /** Tetrachordo C F A D: D and A carry the melody, F the notes below A3, C almost never. */
    val TETRACHORDO = Weights(
        course = doubleArrayOf(3.0, 1.2, 0.1, 0.0),
        cross = arrayOf(
            doubleArrayOf(0.0, 1.0, 2.0, 2.6),
            doubleArrayOf(1.0, 0.0, 0.9, 1.5),
            doubleArrayOf(2.0, 0.9, 0.0, 0.35),
            doubleArrayOf(2.6, 1.5, 0.35, 0.0),
        ),
    )

    /** Trichordo D A D: the high D and A carry the melody, the low D (the drone course) the notes below A3. */
    val TRICHORDO = Weights(
        course = doubleArrayOf(1.2, 0.1, 0.0),
        cross = arrayOf(
            doubleArrayOf(0.0, 0.9, 1.5),
            doubleArrayOf(0.9, 0.0, 0.35),
            doubleArrayOf(1.5, 0.35, 0.0),
        ),
    )

    fun weights(inst: Instrument): Weights = when (inst) {
        Instrument.TETRACHORDO -> TETRACHORDO
        Instrument.TRICHORDO -> TRICHORDO
    }

    /**
     * The (course, fret) of each note of a line. [starts] are the onsets in seconds; [legato]`[i]` is true
     * when note i is hammered, pulled or slid from note i-1 (so it stays on that course). A note out of
     * the instrument's range is placed an octave or more nearer, where it can be played.
     */
    fun place(
        midis: List<Int>,
        starts: List<Double>,
        inst: Instrument,
        legato: List<Boolean>? = null,
        w: Weights = weights(inst),
    ): List<Pair<Int, Int>> {
        val n = midis.size
        if (n == 0) return emptyList()
        val low = inst.courses.first().openMidi
        val high = inst.courses.last().openMidi + inst.frets
        val time = List(n) { starts.getOrElse(it) { starts.lastOrNull() ?: 0.0 } }
        val phrase = phraseStarts(time)
        val layers = ArrayList<Layer>(n)
        for (i in 0 until n) {
            var m = midis[i]
            while (m < low) m += 12
            while (m > high) m -= 12
            val atEdge = phrase[i] || i == n - 1 || phrase[i + 1]
            val layer = Layer()
            for (c in inst.courses.indices) {
                val f = m - inst.courses[c].openMidi
                if (f !in 0..inst.frets) continue
                val place = w.course[c] + height(f, w) + if (f == 0) (if (atEdge) w.openAtEdge else w.open) else 0.0
                if (f == 0) {
                    for (h in 1..inst.frets) layer.add(c, f, h, place) // the hand is free: anywhere
                } else {
                    for (h in max(1, f - w.span)..f) {
                        val d = f - h
                        val finger = if (d < w.span) w.finger[min(d, w.finger.size - 1)] else if (h >= w.highFrom) w.stretch * w.highStretch else w.stretch
                        layer.add(c, f, h, place + finger)
                    }
                }
            }
            if (i > 0) {
                val prev = layers[i - 1]
                val gap = time[i] - time[i - 1]
                val slow = ((gap - w.fastGap) / (w.slowGap - w.fastGap)).coerceIn(0.0, 1.0)
                val pace = if (phrase[i]) w.phraseShift else 1.0 - (1.0 - w.slowFactor) * slow
                val fast = if (phrase[i]) 0.0 else 1.0 - slow
                val crossPace = if (phrase[i]) w.phraseCross else 1.0
                val leg = legato?.getOrNull(i) == true
                for (s in 0 until layer.size) {
                    var best = Double.MAX_VALUE
                    var from = 0
                    for (p in 0 until prev.size) {
                        val v = prev.cost[p] + move(prev, p, layer, s, leg, pace, crossPace, fast, w)
                        if (v < best) { best = v; from = p }
                    }
                    layer.cost[s] += best
                    layer.back[s] = from
                }
            }
            layers += layer
        }
        val out = ArrayList<Pair<Int, Int>>(n)
        var s = layers.last().let { l -> (0 until l.size).minBy { l.cost[it] } }
        for (i in n - 1 downTo 0) {
            out += layers[i].course[s] to layers[i].fret[s]
            s = layers[i].back[s]
        }
        return out.asReversed()
    }

    /** What going from state [p] of [a] to state [s] of [b] costs. */
    private fun move(a: Layer, p: Int, b: Layer, s: Int, legato: Boolean, pace: Double, crossPace: Double, fast: Double, w: Weights): Double {
        val sameCourse = a.course[p] == b.course[s]
        var cost = if (sameCourse) 0.0 else if (legato) w.legatoBreak else w.cross[a.course[p]][b.course[s]] * crossPace
        val frets = abs(a.hand[p] - b.hand[s])
        if (frets > 0) {
            var shift = (w.shift + w.shiftPerFret * frets) * pace
            if (!sameCourse) shift *= w.shiftAcross
            if (legato && sameCourse) shift *= w.slide
            if (a.fret[p] == 0 || b.fret[s] == 0) shift *= w.aroundOpen
            cost += shift
        }
        if (!legato && fast > 0 && a.fret[p] > 0 && b.fret[s] > 0) {
            val beyond = abs(a.fret[p] - b.fret[s]) - w.reach
            if (beyond > 0) cost += w.leap * beyond * fast
        }
        return cost
    }

    private fun height(f: Int, w: Weights) = w.high * max(0, f - w.highFrom) + w.tooHigh * max(0, f - w.tooHighFrom)

    /**
     * Whether each note starts a phrase: the first one, and each after a gap between onsets of
     * [PHRASE_GAP] or more, or of twice the pace around it (at least [PHRASE_MIN]).
     */
    internal fun phraseStarts(starts: List<Double>): BooleanArray {
        val n = starts.size
        val gap = DoubleArray(n) { if (it == 0) 0.0 else max(0.0, starts[it] - starts[it - 1]) }
        return BooleanArray(n) { i ->
            if (i == 0) return@BooleanArray true
            val near = (max(1, i - 4)..min(n - 1, i + 4)).map { gap[it] }.sorted()
            gap[i] >= PHRASE_GAP || gap[i] >= max(PHRASE_MIN, 2 * near[near.size / 2])
        }
    }

    private const val PHRASE_GAP = 0.6
    private const val PHRASE_MIN = 0.3

    /** The states one note can be played in, with the best cost of reaching each and where from. */
    private class Layer {
        var size = 0
        var course = IntArray(32); var fret = IntArray(32); var hand = IntArray(32)
        var cost = DoubleArray(32); var back = IntArray(32)

        fun add(c: Int, f: Int, h: Int, place: Double) {
            if (size == course.size) {
                course = course.copyOf(size * 2); fret = fret.copyOf(size * 2); hand = hand.copyOf(size * 2)
                cost = cost.copyOf(size * 2); back = back.copyOf(size * 2)
            }
            course[size] = c; fret[size] = f; hand[size] = h; cost[size] = place
            size++
        }
    }
}
