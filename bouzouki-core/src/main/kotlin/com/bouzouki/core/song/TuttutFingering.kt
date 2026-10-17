package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Fingering after tuttut (github.com/natecdr/tuttut, Copyright (c) 2023 Nathan Candre, MIT License): a hidden Markov
 * model whose hidden states are the places a note can be played and whose observations are the notes, decoded with
 * Viterbi. Ported from tuttut/logic/graph_utils.py and difficulty.py for a single line of notes (a fingering of one
 * note: no chord span), with the bouzouki's courses as its strings (an octave or unison pair is one string here).
 *
 * As in tuttut, moving from place p to place q is as likely as it is easy:
 *   laplace(|height(q) − height(p)| / frets, [Weights.b]) · 1/(1 + height(q)/frets · [Weights.height])
 *     · 1/(1 + changed · [Weights.changed])
 * where height is the fret (an open string keeps the hand where it was) and changed is 1/courses when q is on another
 * course than p's fretted one (else 0), normalised over every place of every note of the line (tuttut's vocabulary).
 * Moving along one course is cheap, across courses dearer, as a bouzouki player moves.
 *
 * The bouzouki's fork adds, all optional ([Weights]): a cost per course ([Weights.course], the melody belongs on the top
 * courses; zeros: tuttut's own model), legato notes kept on their course ([Weights.legatoBreak]), and notes out of range
 * moved by octaves. Two departures from tuttut: its initial distribution weighs each place by its difficulty, here by
 * its ease; and after an open string the hand stays where it was (tuttut takes it back to the nut).
 */
object TuttutFingering {
    data class Weights(
        /** tuttut's "b": the scale of the Laplace distribution of hand movement (smaller: moving costs more). */
        val b: Double = 1.0,
        /** tuttut's "height": how much playing high up the neck costs. */
        val height: Double = 1.0,
        /** tuttut's "n_changed_strings": how much changing course costs. */
        val changed: Double = 1.0,
        /** A cost (in log-probability) for each course, low to high; null: none (tuttut's own model). */
        val course: DoubleArray? = null,
        /** A legato note moved off the course of the note it is sounded from (log-probability). */
        val legatoBreak: Double = 25.0,
    ) {
        fun toMap(): LinkedHashMap<String, Double> = LinkedHashMap<String, Double>().also { m ->
            m["b"] = b; m["height"] = height; m["changed"] = changed; m["legatoBreak"] = legatoBreak
            course?.forEachIndexed { i, v -> m["course.$i"] = v }
        }

        fun with(values: Map<String, Double>, courses: Int): Weights = Weights(
            b = values["b"]?.takeIf { it > 0 } ?: b,
            height = values["height"] ?: height,
            changed = values["changed"] ?: changed,
            course = if ((0 until courses).any { "course.$it" in values } || course != null)
                DoubleArray(courses) { values["course.$it"] ?: course?.getOrNull(it) ?: 0.0 } else null,
            legatoBreak = values["legatoBreak"] ?: legatoBreak,
        )

        override fun equals(other: Any?) = other is Weights && other.toMap() == toMap()
        override fun hashCode() = toMap().hashCode()
    }

    /** tuttut's weights (all 1) with the bouzouki's course costs: the melody on the top two courses (the HMM engine's). */
    fun bouzouki(inst: Instrument) = Weights(course = Fingering.weights(inst).course.copyOf())

    /** Places for each note (course, fret), as [Fingering.place] gives them. */
    fun place(
        midis: List<Int>,
        inst: Instrument,
        legato: List<Boolean>? = null,
        w: Weights = bouzouki(inst),
    ): List<Pair<Int, Int>> {
        val n = midis.size
        if (n == 0) return emptyList()
        val low = inst.courses.first().openMidi
        val high = inst.courses.last().openMidi + inst.frets
        val pitches = midis.map { var m = it; while (m < low) m += 12; while (m > high) m -= 12; m }
        fun options(m: Int) = inst.courses.indices.mapNotNull { c -> (m - inst.courses[c].openMidi).takeIf { it in 0..inst.frets }?.let { c to it } }
        val opts = pitches.map { options(it) }
        // tuttut's vocabulary: every place of every pitch of the piece.
        val vocabulary = pitches.distinct().flatMap { options(it) }.distinct()
        val frets = inst.frets.toDouble()
        val courses = inst.courses.size.toDouble()

        /** tuttut's easiness of going from place [p] (its hand at [pHeight]) to [q]. */
        fun easiness(p: Pair<Int, Int>, pHeight: Double, q: Pair<Int, Int>): Double {
            val qHeight = if (q.second != 0) q.second.toDouble() else pHeight
            val dheight = abs(qHeight - pHeight) / frets
            val changed = (1 - if (p.second != 0 && p.first == q.first) 1 else 0) / courses
            val laplace = 1 / (2 * w.b) * exp(-dheight / w.b)
            var e = laplace * 1 / (1 + q.second / frets * w.height) * 1 / (1 + changed * w.changed)
            w.course?.getOrNull(q.first)?.let { e *= exp(-it) }
            return e
        }
        fun heightOf(p: Pair<Int, Int>) = p.second.toDouble()
        // The normaliser of each place's transitions (over the whole vocabulary), cached per place and hand height.
        val norm = HashMap<Pair<Pair<Int, Int>, Double>, Double>()
        fun logTransition(p: Pair<Int, Int>, pHeight: Double, q: Pair<Int, Int>): Double {
            val z = norm.getOrPut(p to pHeight) { vocabulary.sumOf { easiness(p, pHeight, it) } }
            return ln(easiness(p, pHeight, q) / z)
        }
        // Viterbi over (note, place), with where the hand is (an open string leaves it where it was).
        val score = ArrayList<DoubleArray>(n)
        val hand = ArrayList<DoubleArray>(n)
        val back = ArrayList<IntArray>(n)
        val first = opts[0]
        val ease0 = first.map { (c, f) -> 1 / (1 + f / frets) * (w.course?.getOrNull(c)?.let { exp(-it) } ?: 1.0) }
        score += DoubleArray(first.size) { ln(ease0[it] / ease0.sum()) }
        hand += DoubleArray(first.size) { heightOf(first[it]) }
        back += IntArray(first.size) { -1 }
        for (i in 1 until n) {
            val prev = opts[i - 1]
            val cur = opts[i]
            val s = DoubleArray(cur.size) { Double.NEGATIVE_INFINITY }
            val h = DoubleArray(cur.size)
            val b = IntArray(cur.size)
            val leg = legato?.getOrNull(i) == true
            for ((qi, q) in cur.withIndex()) for ((pi, p) in prev.withIndex()) {
                var v = score[i - 1][pi] + logTransition(p, hand[i - 1][pi], q)
                if (leg && p.first != q.first && cur.any { it.first == p.first }) v -= w.legatoBreak
                if (v > s[qi]) { s[qi] = v; b[qi] = pi; h[qi] = if (q.second != 0) q.second.toDouble() else hand[i - 1][pi] }
            }
            score += s; hand += h; back += b
        }
        val out = ArrayList<Pair<Int, Int>>(n)
        var k = score.last().indices.maxBy { score.last()[it] }
        for (i in n - 1 downTo 0) { out += opts[i][k]; k = back[i][k] }
        return out.asReversed()
    }
}
