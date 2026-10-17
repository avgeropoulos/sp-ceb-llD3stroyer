package com.bouzouki.core.eval

import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.SongNote
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * How a transcription compares with what was played, mir_eval style: notes are matched one to one (closest onsets
 * first) when their onsets are within [ONSET_TOL] and their pitches agree. The scores:
 * - [note]: same pitch, the usual note F-measure;
 * - [octave]: same pitch class (an octave off counts), to tell octave errors (octave-pair courses) from wrong notes;
 * - [tpSemitone]: pitch within a semitone, so [tpSemitone] − [tp] counts the notes a semitone off (tuning errors);
 * - [offset]: same pitch and the end within 20% of the note's length (at least 50 ms), as mir_eval's onset-offset F;
 * - [course] and [fret]: of the notes matched by pitch whose reference says where they're played, how many the
 *   fingering put on the same course, and on the same course and fret;
 * - [tremolo]: precision and recall of the notes marked tremolo, among matched notes.
 */
class Score {
    var ref = 0; var est = 0
    var tp = 0; var tpOctave = 0; var tpOffset = 0; var tpSemitone = 0
    var placed = 0; var sameCourse = 0; var sameFret = 0
    var refTremolo = 0; var estTremolo = 0; var tremoloRight = 0
    val onsetErrors = ArrayList<Double>()

    fun f(hit: Int) = if (ref + est == 0) 1.0 else 2.0 * hit / (ref + est)
    val note get() = f(tp)
    val octave get() = f(tpOctave)
    val offset get() = f(tpOffset)
    val precision get() = if (est == 0) 1.0 else tp.toDouble() / est
    val recall get() = if (ref == 0) 1.0 else tp.toDouble() / ref
    val course get() = if (placed == 0) Double.NaN else sameCourse.toDouble() / placed
    val fret get() = if (placed == 0) Double.NaN else sameFret.toDouble() / placed

    fun add(o: Score) {
        ref += o.ref; est += o.est; tp += o.tp; tpOctave += o.tpOctave; tpOffset += o.tpOffset; tpSemitone += o.tpSemitone
        placed += o.placed; sameCourse += o.sameCourse; sameFret += o.sameFret
        refTremolo += o.refTremolo; estTremolo += o.estTremolo; tremoloRight += o.tremoloRight
        onsetErrors += o.onsetErrors
    }

    fun summary(): String {
        val ms = onsetErrors.map { it * 1000 }
        val mean = if (ms.isEmpty()) 0.0 else ms.average()
        val std = if (ms.isEmpty()) 0.0 else sqrt(ms.sumOf { (it - mean) * (it - mean) } / ms.size)
        return String.format(Locale.US, "F %.3f (P %.3f R %.3f) · octave-F %.3f · offset-F %.3f · course %s · fret %s · onset %+.1f±%.1f ms",
            note, precision, recall, octave, offset, pct(course), pct(fret), mean, std)
    }

    companion object {
        fun pct(x: Double) = if (x.isNaN()) "–" else String.format(Locale.US, "%.1f%%", 100 * x)
    }
}

object Metrics {
    const val ONSET_TOL = 0.05

    /** One-to-one matching of [est] to [ref] by onset (closest first) among pairs [same] accepts: (ref, est) indices. */
    fun match(ref: List<RefNote>, est: List<SongNote>, tol: Double = ONSET_TOL, same: (RefNote, SongNote) -> Boolean): List<Pair<Int, Int>> {
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        var lo = 0
        val order = est.indices.sortedBy { est[it].start }
        for ((i, r) in ref.withIndex()) {
            while (lo < order.size && est[order[lo]].start < r.start - tol) lo++
            var k = lo
            while (k < order.size && est[order[k]].start <= r.start + tol) {
                val j = order[k]
                if (same(r, est[j])) pairs += Triple(i, j, abs(est[j].start - r.start))
                k++
            }
        }
        val usedR = HashSet<Int>(); val usedE = HashSet<Int>()
        val out = ArrayList<Pair<Int, Int>>()
        for ((i, j, _) in pairs.sortedBy { it.third }) if (i !in usedR && j !in usedE) { usedR += i; usedE += j; out += i to j }
        return out
    }

    fun score(ref: List<RefNote>, est: List<SongNote>): Score {
        val s = Score()
        s.ref = ref.size; s.est = est.size
        val exact = match(ref, est) { r, e -> r.midi == e.midi }
        s.tp = exact.size
        s.tpOctave = match(ref, est) { r, e -> Math.floorMod(r.midi - e.midi, 12) == 0 }.size
        s.tpSemitone = match(ref, est) { r, e -> abs(r.midi - e.midi) <= 1 }.size
        s.tpOffset = match(ref, est) { r, e ->
            r.midi == e.midi && abs(e.end - r.end) <= max(0.05, 0.2 * (r.end - r.start))
        }.size
        for ((i, j) in exact) {
            val r = ref[i]; val e = est[j]
            s.onsetErrors += e.start - r.start
            if (r.course >= 0) {
                s.placed++
                if (r.course == e.course) { s.sameCourse++; if (r.fret == e.fret) s.sameFret++ }
            }
            val rt = r.articulation == Articulation.TREMOLO
            val et = e.articulation == Articulation.TREMOLO
            if (rt) s.refTremolo++
            if (et) s.estTremolo++
            if (rt && et) s.tremoloRight++
        }
        return s
    }

    /**
     * Where beat 0 of a click plan is in a recording: the shift (seconds, in [range]) of [ref] that matches the most of
     * [est] by pitch class (the middle of the shifts that do, on a 10 ms grid), then centred on the matched notes' median
     * onset error. For takes recorded to a click heard in headphones.
     */
    fun bestOffset(ref: List<RefNote>, est: List<SongNote>, range: ClosedFloatingPointRange<Double> = -1.0..8.0): Double {
        fun shifted(d: Double) = ref.map { it.copy(start = it.start + d, end = it.end + d) }
        fun same(r: RefNote, e: SongNote) = Math.floorMod(r.midi - e.midi, 12) == 0
        val steps = ((range.endInclusive - range.start) / 0.01).toInt()
        val hits = IntArray(steps + 1) { match(shifted(range.start + it * 0.01), est) { r, e -> same(r, e) }.size }
        val most = hits.maxOrNull() ?: return 0.0
        val first = hits.indexOfFirst { it == most }
        var last = first
        while (last + 1 <= steps && hits[last + 1] == most) last++
        val d = range.start + (first + last) / 2.0 * 0.01
        val r = shifted(d)
        val errs = match(r, est) { a, e -> same(a, e) }.map { (i, j) -> est[j].start - r[i].start }.sorted()
        return if (errs.isEmpty()) d else d + errs[errs.size / 2]
    }
}
