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
 * - [tremolo]: precision and recall of the notes marked tremolo, among matched notes;
 * - [timing]: how early or late the notes found are ([Timed]), matched within the wider [Metrics.TIMING_TOL] so that
 *   a bias isn't hidden by the notes it pushes out of the scoring window.
 */
class Score {
    var ref = 0; var est = 0
    var tp = 0; var tpOctave = 0; var tpOffset = 0; var tpSemitone = 0
    var placed = 0; var sameCourse = 0; var sameFret = 0
    var refTremolo = 0; var estTremolo = 0; var tremoloRight = 0
    val onsetErrors = ArrayList<Double>()
    val timing = ArrayList<Timed>()

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
        timing += o.timing
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

/**
 * A note found at the pitch played, within [Metrics.TIMING_TOL]: [error] is its start minus the played one's (seconds:
 * + late, - early), with the pitch and how it was played ([played]) and marked by the transcription ([found]).
 */
class Timed(val error: Double, val midi: Int, val played: Articulation, val found: Articulation)

/** Signed onset errors summed up, in milliseconds (found minus played: + is late). */
class TimingStats(errors: List<Double>) {
    private val ms = errors.map { it * 1000 }.sorted()
    val n = ms.size
    val mean = if (n == 0) Double.NaN else ms.average()
    val std = if (n == 0) Double.NaN else sqrt(ms.sumOf { (it - mean) * (it - mean) } / n)
    val median = percentile(0.5)
    val p10 = percentile(0.1)
    val p90 = percentile(0.9)
    /** The share of the notes more than [limit] ms early or late. */
    fun beyond(limit: Double) = if (n == 0) Double.NaN else ms.count { abs(it) > limit }.toDouble() / n

    /** The [q] quantile, linear between the sorted errors. */
    fun percentile(q: Double): Double {
        if (n == 0) return Double.NaN
        val x = q * (n - 1)
        val i = x.toInt().coerceIn(0, n - 1)
        val j = (i + 1).coerceAtMost(n - 1)
        return ms[i] + (ms[j] - ms[i]) * (x - i)
    }

    /** A Markdown table row: `| name | n | mean | median | std | p10 | p90 | beyond 50 ms |`. */
    fun row(name: String): String = if (n == 0) "| $name | 0 | – | – | – | – | – | – |\n" else
        String.format(Locale.US, "| %s | %d | %+.1f | %+.1f | %.1f | %+.1f | %+.1f | %.1f%% |\n", name, n, mean, median, std, p10, p90, 100 * beyond(50.0))

    companion object {
        const val HEAD = "| | matched | mean ms | median ms | std ms | 10th pct ms | 90th pct ms | over 50 ms off |\n|---|---|---|---|---|---|---|---|\n"
    }
}

object Metrics {
    const val ONSET_TOL = 0.05
    /** The onset window of the timing statistics ([Score.timing]) only: wider than the scoring one. */
    const val TIMING_TOL = 0.1

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
        for ((i, j) in match(ref, est, TIMING_TOL) { r, e -> r.midi == e.midi })
            s.timing += Timed(est[j].start - ref[i].start, ref[i].midi, ref[i].articulation, est[j].articulation)
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
