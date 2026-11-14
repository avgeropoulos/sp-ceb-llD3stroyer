package com.bouzouki.core.song

import java.util.Locale
import kotlin.math.abs

/**
 * How closely one transcription of a tune matches another, e.g. the same song recorded by the player alone (the
 * reference) and heard in a band's mix (🎧 Listen): the two recordings start at different moments, so the second is
 * first shifted to line up best ([offset]), then their notes are matched one to one as the evaluation does (same pitch,
 * onsets within [TOLERANCE]; [octave]: an octave off allowed).
 */
class SongCompare private constructor(
    val offset: Double,
    val reference: Int,
    val other: Int,
    val matched: Int,
    val matchedOctave: Int,
    val sameFret: Int,
) {
    val f get() = if (reference + other == 0) 1.0 else 2.0 * matched / (reference + other)
    val octave get() = if (reference + other == 0) 1.0 else 2.0 * matchedOctave / (reference + other)
    val precision get() = if (other == 0) 1.0 else matched.toDouble() / other
    val recall get() = if (reference == 0) 1.0 else matched.toDouble() / reference

    override fun toString() = String.format(Locale.US,
        "F %.3f (precision %.3f, recall %.3f), octave-F %.3f; %d of %d notes found, %d on the same course and fret; lined up %+.2f s",
        f, precision, recall, octave, matched, reference, sameFret, offset)

    companion object {
        const val TOLERANCE = 0.05

        fun of(reference: List<SongNote>, other: List<SongNote>, maxShift: Double = 120.0): SongCompare {
            val d = offset(reference, other, maxShift)
            val moved = other.map { it.copy(start = it.start + d, end = it.end + d) }
            val exact = match(reference, moved) { a, b -> a.midi == b.midi }
            val octave = match(reference, moved) { a, b -> Math.floorMod(a.midi - b.midi, 12) == 0 }
            val fret = exact.count { (i, j) -> reference[i].course == moved[j].course && reference[i].fret == moved[j].fret }
            return SongCompare(d, reference.size, other.size, exact.size, octave.size, fret)
        }

        /**
         * The shift (seconds, within ±[maxShift]) that puts [other]'s onsets on [reference]'s: the best of a coarse
         * vote over onset differences of notes of the same pitch class (10 ms bins), refined by their median.
         */
        fun offset(reference: List<SongNote>, other: List<SongNote>, maxShift: Double): Double {
            if (reference.isEmpty() || other.isEmpty()) return 0.0
            val votes = HashMap<Int, Int>()
            val byPc = reference.groupBy { Math.floorMod(it.midi, 12) }
            for (o in other) for (r in byPc[Math.floorMod(o.midi, 12)] ?: emptyList()) {
                val d = r.start - o.start
                if (abs(d) <= maxShift) { val b = Math.round(d / 0.01).toInt(); votes[b] = (votes[b] ?: 0) + 1 }
            }
            // A bin and its neighbours (onset jitter spreads a true shift over a few 10 ms bins).
            val best = votes.keys.maxByOrNull { b -> (b - 3..b + 3).sumOf { votes[it] ?: 0 } } ?: return 0.0
            val near = ArrayList<Double>()
            for (o in other) for (r in byPc[Math.floorMod(o.midi, 12)] ?: emptyList()) {
                val d = r.start - o.start
                if (abs(d - best * 0.01) <= TOLERANCE) near += d
            }
            near.sort()
            return if (near.isEmpty()) best * 0.01 else near[near.size / 2]
        }

        private fun match(a: List<SongNote>, b: List<SongNote>, same: (SongNote, SongNote) -> Boolean): List<Pair<Int, Int>> {
            val pairs = ArrayList<Triple<Int, Int, Double>>()
            val order = b.indices.sortedBy { b[it].start }
            var lo = 0
            for ((i, r) in a.withIndex()) {
                while (lo < order.size && b[order[lo]].start < r.start - TOLERANCE) lo++
                var k = lo
                while (k < order.size && b[order[k]].start <= r.start + TOLERANCE) {
                    val j = order[k]
                    if (same(r, b[j])) pairs += Triple(i, j, abs(b[j].start - r.start))
                    k++
                }
            }
            val usedA = HashSet<Int>(); val usedB = HashSet<Int>()
            val out = ArrayList<Pair<Int, Int>>()
            for ((i, j, _) in pairs.sortedBy { it.third }) if (i !in usedA && j !in usedB) { usedA += i; usedB += j; out += i to j }
            return out
        }
    }
}
