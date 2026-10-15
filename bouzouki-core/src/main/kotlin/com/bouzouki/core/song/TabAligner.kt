package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** One pass through part of a tab in the recording: tab notes [from] to [to] (inclusive), heard from [start] to [end] seconds. */
data class TabPass(val from: Int, val to: Int, val start: Double, val end: Double)

/**
 * A tab lined up with the recording: [song] is the song's line made of the tab's notes at the times
 * they're heard; [matched] of the tab's [total] notes were heard at their own pitch, [missing] lists the
 * indices (in the tab) of the others: not heard at all (still in [song] when inside a part that was found,
 * where the notes around them put them), or heard only at another pitch ([differs]: a fret or two off, shown
 * at the pitch heard as the tab's fret is likely wrong; an octave off or another note of its chord, shown as
 * written). Show the [missing] notes as less sure.
 * [sources] gives, for each note of [song], the index in the tab it comes from, or -1 for a note of the
 * prepared line: kept where the tab isn't heard, and where the line hears a note clearly that the tab leaves
 * out (an ornament, a fill). [passes] are where the tab (or a part of it, repeated or not) was found. The
 * recording sits [transpose] semitones above the tab (a song in another key: the notes are moved by it) and
 * [octave] octaves above it (kept as the tab writes it).
 */
class TabAlignment(
    val song: SongAnalysis,
    val matched: Int,
    val total: Int,
    val missing: List<Int>,
    val sources: List<Int> = emptyList(),
    val passes: List<TabPass> = emptyList(),
    val transpose: Int = 0,
    val octave: Int = 0,
    val differs: List<Int> = emptyList(),
)

/**
 * Lines a [ParsedTab] up with a prepared song's [AnalysisData].
 *
 * The recording is reduced to candidate note starts: onset peaks of Basic Pitch at each pitch (and, for
 * hammer-ons, pull-offs and slides, places where a pitch's activation jumps up without one), each worth its
 * note and onset activation, more where the prepared line has a note at that pitch too. A dynamic programme
 * over the candidates in time order then finds the passes through the tab that collect the most evidence:
 * each tab note is placed on a candidate at its pitch (or an octave off, cheaply on an octave-pair course, or
 * at another note of its chord), after the tab note before it; a note or two may be skipped, at a cost, and
 * line notes left unexplained between two notes cost too. The time between two notes is compared with their
 * distance in the tab's columns at the pass's tempo so far (a soft prior: tabs keep rhythm roughly), and a
 * longer pause than that costs a fixed amount (tabs often leave out rests). At the ends of the tab's parts
 * (sections and systems) a pass may stop and go on later with the next part, a part the tab says comes next
 * (x2, a part named again), or, at a higher cost, any part; at a higher cost still from inside a part. So a
 * tab of the intro only, or with the chorus written once, is found wherever the recording plays it. A pass
 * the tab doesn't lead to (a repeat it doesn't mark) is kept only if it explains most of the line's notes
 * where it lies, as many as the passes through the tab do, so parts of the song the tab doesn't cover keep
 * the prepared line. A tab in another key is found by trying the transposition its pitch classes suggest;
 * one written an octave off by trying that octave.
 * Tab notes a pass skips go where their columns put them between their neighbours, moved to a nearby
 * candidate at their pitch (or a fret or two off: a wrong fret in the tab, played at the pitch heard) or a
 * rise of its activation; one heard nowhere takes a prepared-line note a fret or two off close by. Inside the
 * passes, the prepared line's notes no tab note explains stay when their onsets are clear (tabs leave out
 * ornaments and fills). Each note lasts while its pitch's activation stays up, until the next note.
 *
 * A four-minute song takes a few hundred ms on a desktop JVM, up to about 1.5 s with a long tab of a figure repeated
 * over and over, and several times that on a phone: call [align] off the main thread, with progress shown.
 */
object TabAligner {
    /** Where the aligner says what it found (for tests and tuning); null normally. */
    internal var log: ((String) -> Unit)? = null

    /**
     * [base] is the prepared song (for the bouzouki the player plays: notes keep the tab's course and fret
     * where it has them, else they are fingered for it; show [TabAlignment.song] as it is). With [keepLine], the
     * prepared line's notes stay where the tab isn't heard (outside its passes) and where the tab leaves out a note
     * the line hears clearly. Takes a while on long songs: call it off the main thread.
     */
    fun align(tab: ParsedTab, data: AnalysisData, base: SongAnalysis, keepLine: Boolean = true): TabAlignment =
        align(tab, data, base, keepLine, Params())

    /**
     * The aligner's weights, chosen on synthesized songs with tabs written from their notes (scratch evaluation).
     * Evidence: a candidate is worth its salience + [onsetWeight] × onset − [bias] (+ [lineBonus] on the line).
     */
    internal data class Params(
        // Candidates: onset peaks from onsetMin, rises of riseMin to a note of riseSalience, worth at least minEvidence.
        val onsetMin: Float = 0.12f,
        val riseMin: Float = 0.15f,
        val riseSalience: Float = 0.3f,
        val minEvidence: Float = -0.2f,
        val onsetWeight: Float = 0.5f,
        val bias: Float = 0.7f,
        val lineBonus: Float = 0.5f,
        val legatoBonus: Float = 0.15f,
        /** A line note within this (s) of a candidate is the same note. */
        val explained: Double = 0.04,
        // Pitch: heard an octave off (on an octave-pair course), at another note of the chord, a fret or two off.
        val octave: Float = 0.35f,
        val octavePair: Float = 0.05f,
        val stack: Float = 0.25f,
        val wrongFret: Float = 0.45f,
        // Costs of a pass: a note skipped, a line note it leaves unexplained (in a rest), a rest after pauseAfter s.
        val skip: Float = 0.9f,
        val unexplained: Float = 0.2f,
        val pauseUnexplained: Float = 0.1f,
        val pause: Float = 0.8f,
        val pauseAfter: Double = 0.8,
        // Starting afresh, going on to the next part (or one the tab leads to), the same part again, any other part.
        val fresh: Float = 1f,
        val jumpNext: Float = 0.2f,
        val jumpWritten: Float = 0.2f,
        val jumpSelf: Float = 3f,
        val jumpOther: Float = 2f,
        val jumpInside: Float = 0.3f,
        val jumpMid: Float = 1.5f,
        // Tempo: no cost within e^tempoFree of the pass's tempo, then tempoWeight × (log ratio)², at most tempoCap.
        val tempoFree: Float = 0.22f,
        val tempoWeight: Float = 0.5f,
        val tempoCap: Float = 2f,
        val tempoAlpha: Float = 0.25f,
        val crossWeight: Float = 0.3f,
        val gapTempo: Float = 2.5f,
        val minScan: Double = 1.0,
        val minGap: Double = 0.035,
        val beam: Float = 6f,
        // A note's state far behind its best one within noteWindow s before (the note placed late, for a worse path).
        val noteWindow: Double = 3.0,
        val noteBeam: Float = 0.5f,
        // Passes kept: explaining keepTrusted of the line, or (repeats) keepOther or keepRelative of what the others do.
        val keepTrusted: Float = 0.5f,
        val keepOther: Float = 0.65f,
        val keepRelative: Double = 0.8,
        // Placing skipped notes (a candidate worth at least accept), and ends (activation under endLevel).
        val accept: Float = -0.1f,
        val endLevel: Float = 0.25f,
        // The line's notes inside passes: kept with no tab note within keepNear s and evidence keepEvidence; one a fret
        // or two from a tab note heard nowhere, within correctWithin s, corrects it.
        val keepNear: Double = 0.06,
        val keepEvidence: Float = 0.35f,
        val keepSame: Float = 0.5f,
        val correctWithin: Double = 0.15,
    )

    internal fun align(tab: ParsedTab, data: AnalysisData, base: SongAnalysis, keepLine: Boolean, par: Params): TabAlignment {
        val n = tab.notes.size
        val act = data.act
        if (n == 0 || act.frames.size < 3) {
            return TabAlignment(if (keepLine) base else base.copy(notes = emptyList()), 0, n, (0 until n).toList(),
                if (keepLine) List(base.notes.size) { -1 } else emptyList())
        }
        val prep = Prep(tab)
        val times = DoubleArray(act.frames.size) { BasicPitch.frameTime(it) }
        val lowKey = max(0, prep.lowest - 20 - BasicPitch.MIDI_OFFSET)
        val highKey = min(BasicPitch.N_KEYS - 1, prep.highest + 20 - BasicPitch.MIDI_OFFSET)
        val cands = Candidates.find(act, lowKey, highKey, times, par)
        val line = Line.of(base, prep, cands, par)

        var best = Dp(prep, cands, 0, par, line).run()
        log?.invoke("${cands.count} candidates, best path ${best.note.size} notes, score %.1f".format(best.score))
        val shift = transposition(prep, cands)
        if (shift != 0) {
            val alt = Dp(prep, cands, shift, par, line).run()
            log?.invoke("transposed by $shift: score %.1f".format(alt.score))
            if (alt.score > best.score * 1.2f + 2f) best = alt
        }
        // Mostly heard an octave off: the tab is written an octave away from the recording.
        for (dir in intArrayOf(1, -1)) {
            val off = best.type.count { it == (if (dir > 0) T_UP else T_DOWN) }
            if (off > best.type.count { it == T_EXACT } && off >= 4) {
                val alt = Dp(prep, cands, best.delta + 12 * dir, par, line).run()
                log?.invoke("an octave ${if (dir > 0) "up" else "down"}: score %.1f".format(alt.score))
                if (alt.score > best.score) best = alt
            }
        }
        return Placement(tab, prep, cands, act, times, best, base, keepLine, par, line).result()
    }

    // How a candidate's pitch relates to the tab note's.
    private const val T_EXACT = 0
    private const val T_UP = 1 // heard an octave above
    private const val T_DOWN = 2 // heard an octave below
    private const val T_STACK = 3 // another note of its chord
    private const val T_WRONG = 4 // a fret or two off (only when placing skipped notes)
    private const val T_NONE = -1

    private const val NEG = -1e30f

    /** Notes skipped at a pass's ends or between its notes: at most this many in a row (less one). */
    private const val EDGE = 3

    /** The slowest tempo (seconds per tab column) looked for, and the one assumed before a pass has two notes. */
    private const val TAU_MAX = 0.4
    private const val TAU_FIRST = 0.3
    private const val MAX_SCAN = 8.0

    /**
     * The tab as arrays, and its units (cut at sections and systems) where passes may stop and go on. A pass may go
     * on from the end of a unit to the next unit, to one the tab's sections lead to ([writtenFrom]: marked repeats, a
     * part named again), the same unit again, or the start of any unit (cheaper at a section's start, [sectionStart]).
     */
    private class Prep(val tab: ParsedTab) {
        val n = tab.notes.size
        val midi = IntArray(n) { tab.midi(it) }
        val col = IntArray(n) { tab.notes[it].column }
        val system = IntArray(n) { tab.notes[it].system }
        val pair = BooleanArray(n) { tab.instrument.courses[tab.notes[it].course].octavePair }
        val legato = BooleanArray(n) { tab.notes[it].articulation.isLegato }
        val stack = Array(n) { i -> tab.notes[i].stack.map { (c, f) -> tab.instrument.midiAt(c, f) }.toIntArray() }
        val lowest = midi.min()
        val highest = midi.max()
        val unitStart: IntArray
        val unitEnd: IntArray
        val unitOf = IntArray(n)
        val writtenFrom: Array<IntArray>
        val sectionStart: BooleanArray

        init {
            val cuts = sortedSetOf(0, n)
            for (s in tab.sections) { cuts += s.from.coerceIn(0, n); cuts += s.to.coerceIn(0, n) }
            for (i in 1 until n) if (system[i] != system[i - 1]) cuts += i
            val c = cuts.toIntArray()
            unitStart = IntArray(c.size - 1) { c[it] }
            unitEnd = IntArray(c.size - 1) { c[it + 1] }
            for (u in unitStart.indices) for (i in unitStart[u] until unitEnd[u]) unitOf[i] = u
            val units = unitStart.size
            val written = Array(units) { HashSet<Int>() }
            sectionStart = BooleanArray(units)
            sectionStart[0] = true
            val secs = tab.sections.filter { it.to > it.from && it.from >= 0 && it.to <= n }
            for ((k, s) in secs.withIndex()) {
                sectionStart[unitOf[s.from]] = true
                if (s.times > 1) written[unitOf[s.from]] += unitOf[s.to - 1]
                val next = secs.getOrNull(k + 1) ?: continue
                written[unitOf[next.from]] += unitOf[s.to - 1]
            }
            writtenFrom = Array(units) { u -> written[u].filter { it != u - 1 }.toIntArray() }
        }
    }

    /** Candidate note starts, in time order (then by key): model frame, key, time (s) and evidence. */
    private class Candidates(
        val frame: IntArray, val key: IntArray, val time: DoubleArray, val ev: FloatArray, val count: Int, val lo: Int, val hi: Int,
    ) {
        /** The first candidate at or after [t]. */
        fun firstAt(t: Double): Int {
            var a = 0
            var b = count
            while (a < b) { val m = (a + b) ushr 1; if (time[m] < t) a = m + 1 else b = m }
            return a
        }

        companion object {
            fun find(act: BasicPitch.Activations, lo: Int, hi: Int, times: DoubleArray, par: Params): Candidates {
                val on = act.onsets
                val fr = act.frames
                val t = fr.size
                val frames = Ints(1024)
                val keys = Ints(1024)
                var tm = DoubleArray(1024)
                var evs = FloatArray(1024)
                fun add(f: Int, k: Int, time: Double, onset: Float, rise: Boolean) {
                    var s = 0f
                    for (x in f until min(t, f + 5)) s = max(s, fr[x][k])
                    val ev = s + par.onsetWeight * onset - par.bias
                    if (ev < par.minEvidence || (rise && s < par.riseSalience)) return
                    if (frames.size == tm.size) { tm = tm.copyOf(tm.size * 2); evs = evs.copyOf(evs.size * 2) }
                    tm[frames.size] = time
                    evs[frames.size] = ev
                    frames.add(f)
                    keys.add(k)
                }
                val peak = BooleanArray(t)
                for (k in lo..hi) {
                    java.util.Arrays.fill(peak, false)
                    for (f in 0 until t) {
                        val o = on[f][k]
                        if (o < par.onsetMin) continue
                        if ((f > 0 && o < on[f - 1][k]) || (f + 1 < t && o <= on[f + 1][k])) continue
                        peak[f] = true
                        add(f, k, BasicPitch.onsetTime(act, f, k), o, false)
                    }
                    // A note sounded without a pick shows as its activation jumping up, often without an onset peak.
                    var prev = 0f
                    for (f in 2 until t) {
                        val d = min(fr[f][k] - fr[f - 1][k], fr[f][k] - fr[f - 2][k])
                        val next = if (f + 1 < t) min(fr[f + 1][k] - fr[f][k], fr[f + 1][k] - fr[f - 1][k]) else 0f
                        if (d >= par.riseMin && d >= prev && d > next) {
                            var near = false
                            for (x in max(0, f - 3)..min(t - 1, f + 3)) if (peak[x]) { near = true; break }
                            if (!near) add(f, k, times[f] + BasicPitch.ONSET_LATENCY, on[f][k], true)
                        }
                        prev = d
                    }
                }
                val c = frames.size
                val idx = (0 until c).sortedWith(compareBy<Int>({ tm[it] }, { keys[it] }))
                return Candidates(IntArray(c) { frames[idx[it]] }, IntArray(c) { keys[idx[it]] }, DoubleArray(c) { tm[idx[it]] },
                    FloatArray(c) { evs[idx[it]] }, c, lo, hi)
            }
        }
    }

    /**
     * The transposition (semitones, -5..6) that best matches the tab's pitch classes (weighted by their columns) with
     * the recording's candidates (weighted by their evidence); 0 unless another fits clearly better.
     */
    private fun transposition(p: Prep, c: Candidates): Int {
        val tab = DoubleArray(12)
        for (i in 0 until p.n) tab[Math.floorMod(p.midi[i], 12)] += if (i + 1 < p.n) (p.col[i + 1] - p.col[i]).coerceIn(1, 8).toDouble() else 2.0
        val heard = DoubleArray(12)
        for (j in 0 until c.count) if (c.ev[j] > 0) heard[Math.floorMod(c.key[j] + BasicPitch.MIDI_OFFSET, 12)] += c.ev[j].toDouble()
        fun centred(v: DoubleArray): DoubleArray {
            val m = v.average()
            val d = v.map { it - m }
            val norm = kotlin.math.sqrt(d.sumOf { it * it }).takeIf { it > 0 } ?: 1.0
            return DoubleArray(12) { d[it] / norm }
        }
        val a = centred(tab)
        val b = centred(heard)
        fun corr(s: Int) = (0 until 12).sumOf { a[it] * b[Math.floorMod(it + s, 12)] }
        val best = (0 until 12).maxBy { corr(it) }
        if (best == 0 || corr(best) < corr(0) + 0.15) return 0
        return if (best > 6) best - 12 else best
    }

    /**
     * The prepared line's notes counted at each candidate: [before] start more than [Params.explained] before it,
     * [upTo] start before it or within that much after it; [on] says whether one of them, at the candidate's pitch
     * (or an octave off), starts within that much of it: the line heard that note too.
     */
    private class Line(val before: IntArray, val upTo: IntArray, val on: BooleanArray) {
        /** Without a prepared line (none was given), passes may start anywhere and are all kept. */
        val empty = on.none { it }

        companion object {
            fun of(base: SongAnalysis, p: Prep, c: Candidates, par: Params): Line {
                val notes = base.notes.filter { it.midi in p.lowest - 26..p.highest + 26 }.sortedBy { it.start }
                val t = DoubleArray(notes.size) { notes[it].start }
                fun count(x: Double): Int {
                    var a = 0
                    var b = t.size
                    while (a < b) { val m = (a + b) ushr 1; if (t[m] < x) a = m + 1 else b = m }
                    return a
                }
                val before = IntArray(c.count) { count(c.time[it] - par.explained) }
                val upTo = IntArray(c.count) { count(c.time[it] + par.explained) }
                val on = BooleanArray(c.count) { ci ->
                    val m = c.key[ci] + BasicPitch.MIDI_OFFSET
                    (before[ci] until upTo[ci]).any { Math.floorMod(notes[it].midi - m, 12) == 0 }
                }
                return Line(before, upTo, on)
            }
        }
    }

    /** A growable list of ints. */
    private class Ints(cap: Int = 8) {
        var a = IntArray(cap)
        var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
    }

    /** The best path of one dynamic programme: its states (note, candidate, pitch relation, how it was reached) in time order. */
    private class Path(val delta: Int, val score: Float, val note: IntArray, val cand: IntArray, val type: IntArray, val kind: IntArray)

    // How a state was reached (besides from the note d = 1..EDGE before it).
    private const val K_FRESH = -1
    private const val K_JUMP = -2
    private const val K_PAUSE = -3

    /**
     * The dynamic programme over candidates in time order, the recording [delta] semitones above the tab. A state is
     * a tab note placed on a candidate, with the best path's value, its tempo (seconds per column) and where it came
     * from. States far behind the best one so far are dropped (a beam), and so are notes no state before can reach,
     * and a note's states a little behind its best one just before (the same note placed later, on another candidate).
     */
    private class Dp(val p: Prep, val c: Candidates, val delta: Int, val par: Params, val line: Line) {
        private var size = 0
        private var sCand = IntArray(1 shl 14)
        private var sNote = IntArray(1 shl 14)
        private var sV = FloatArray(1 shl 14)
        private var sTau = FloatArray(1 shl 14)
        private var sBack = IntArray(1 shl 14)
        private var sType = ByteArray(1 shl 14)
        private var sKind = ByteArray(1 shl 14)

        private fun grow() {
            val cap = sCand.size * 2
            sCand = sCand.copyOf(cap); sNote = sNote.copyOf(cap); sV = sV.copyOf(cap); sTau = sTau.copyOf(cap)
            sBack = sBack.copyOf(cap); sType = sType.copyOf(cap); sKind = sKind.copyOf(cap)
        }

        fun run(): Path {
            val n = p.n
            val units = p.unitStart.size
            // For each key, the tab notes a candidate there can be (note shl 3 or pitch relation) and what that costs.
            val keyed = Array(BasicPitch.N_KEYS) { Ints(4) }
            val keyedCost = Array(BasicPitch.N_KEYS) { ArrayList<Float>(4) }
            for (i in 0 until n) {
                val seen = HashMap<Int, Pair<Int, Float>>()
                fun target(m: Int, type: Int, cost: Float) {
                    val k = m - BasicPitch.MIDI_OFFSET
                    if (k < c.lo || k > c.hi) return
                    val had = seen[k]
                    if (had == null || had.second > cost) seen[k] = type to cost
                }
                val m = p.midi[i] + delta
                target(m, T_EXACT, 0f)
                target(m + 12, T_UP, if (p.pair[i]) par.octavePair else par.octave)
                target(m - 12, T_DOWN, par.octave)
                for (s in p.stack[i]) target(s + delta, T_STACK, par.stack)
                for ((k, tc) in seen) { keyed[k].add(i shl 3 or tc.first); keyedCost[k] += tc.second }
            }
            val byKey = Array(BasicPitch.N_KEYS) { keyed[it].a.copyOf(keyed[it].size) }
            val costByKey = Array(BasicPitch.N_KEYS) { keyedCost[it].toFloatArray() }
            val byNote = Array(n) { Ints(8) } // each note's states, in time order
            val noteBest = FloatArray(n) { NEG }
            val recentT = DoubleArray(n) { Double.NEGATIVE_INFINITY } // each note's best state lately: when, and its value
            val recentV = FloatArray(n) { NEG }
            val oldPtr = IntArray(n) // a rest: the best state of each note at least pauseAfter ago
            val oldBest = IntArray(n) { -1 }
            // The best state ending each unit (from minGap after it), and of any unit.
            val endV = FloatArray(units) { NEG }
            val endS = IntArray(units) { -1 }
            var anyV = NEG
            var anyS = -1
            var qTime = DoubleArray(256)
            var qVal = FloatArray(256)
            val qUnit = Ints()
            val qState = Ints()
            var qHead = 0
            var bestV = NEG
            var bestS = -1
            val alpha = par.tempoAlpha
            val freeHigh = exp(par.tempoFree.toDouble())
            val freeLow = 1 / freeHigh
            for (ci in 0 until c.count) {
                val t = c.time[ci]
                while (qHead < qUnit.size && qTime[qHead] <= t) {
                    val u = qUnit[qHead]
                    if (qVal[qHead] > endV[u]) { endV[u] = qVal[qHead]; endS[u] = qState[qHead] }
                    if (qVal[qHead] > anyV) { anyV = qVal[qHead]; anyS = qState[qHead] }
                    qHead++
                }
                val key = c.key[ci]
                val list = byKey[key]
                val costs = costByKey[key]
                // Without a line to agree with, every candidate gets half its bonus.
                val onLine = if (line.empty) par.lineBonus / 2 else if (line.on[ci]) par.lineBonus else 0f
                for (e in list.indices) {
                    val i = list[e] shr 3
                    val type = list[e] and 7
                    val ev = c.ev[ci] + (if (p.legato[i]) par.legatoBonus else 0f) + onLine - costs[e]
                    var best = NEG
                    var back = -1
                    var tau = Float.NaN
                    var kind = 0
                    val u = p.unitOf[i]
                    val into = i - p.unitStart[u]
                    if (into < EDGE && (line.on[ci] || line.empty)) {
                        // Starting afresh, or going on from the end of a unit, where the line hears a note.
                        val skipIn = par.skip * into
                        best = -par.fresh - skipIn
                        kind = K_FRESH
                        var from = -1
                        if (u > 0 && endV[u - 1] - par.jumpNext - skipIn > best) { best = endV[u - 1] - par.jumpNext - skipIn; from = endS[u - 1] }
                        var selfWritten = false
                        for (w in p.writtenFrom[u]) {
                            if (w == u) selfWritten = true
                            if (endV[w] - par.jumpWritten - skipIn > best) { best = endV[w] - par.jumpWritten - skipIn; from = endS[w] }
                        }
                        if (!selfWritten && endV[u] - par.jumpSelf - skipIn > best) { best = endV[u] - par.jumpSelf - skipIn; from = endS[u] }
                        val other = if (p.sectionStart[u]) par.jumpOther else par.jumpOther + par.jumpInside
                        if (anyV - other - skipIn > best) { best = anyV - other - skipIn; from = anyS }
                        if (from >= 0) { back = from; tau = sTau[from]; kind = K_JUMP }
                    } else if (line.on[ci]) {
                        // A part repeated from inside a unit (a tab whose parts don't each start a system).
                        best = -par.fresh - par.jumpMid
                        kind = K_FRESH
                        val v = anyV - par.jumpOther - par.jumpInside - par.jumpMid
                        if (anyS >= 0 && v > best) { best = v; back = anyS; tau = sTau[anyS]; kind = K_JUMP }
                    }
                    // Nothing before can bring this near the best path: skip it at once.
                    var bound = best
                    for (d in 1..EDGE) if (i - d >= 0) bound = max(bound, noteBest[i - d] - par.skip * (d - 1))
                    if (bound + ev < bestV - par.beam) continue
                    for (d in 1..EDGE) {
                        val q = i - d
                        if (q < 0) break
                        val dc = max(1, p.col[i] - p.col[q])
                        val cross = p.system[q] != p.system[i]
                        val scan = min(MAX_SCAN, max(par.minScan, par.gapTempo * TAU_MAX * dc))
                        val skipCost = par.skip * (d - 1)
                        val lst = byNote[q]
                        var j = lst.size - 1
                        var chosen = -1
                        var chosenGap = 0.0
                        while (j >= 0) {
                            val s = lst[j--]
                            val gap = t - c.time[sCand[s]]
                            if (gap > scan) break
                            if (gap < par.minGap) continue
                            val st = sTau[s]
                            var cost = 0f
                            if (st.isNaN()) {
                                if (gap > max(par.minScan, par.gapTempo * TAU_FIRST * dc)) continue
                            } else {
                                if (gap > max(par.minScan, (par.gapTempo * st * dc).toDouble())) continue
                                val ratio = gap / (st * dc)
                                if (ratio > freeHigh || ratio < freeLow) {
                                    val x = abs(ln(ratio).toFloat()) - par.tempoFree
                                    cost = min(par.tempoCap, par.tempoWeight * x * x) * (if (cross) par.crossWeight else 1f)
                                }
                            }
                            val unexplained = line.before[ci] - line.upTo[sCand[s]]
                            if (unexplained > 0) cost += unexplained * par.unexplained
                            val v = sV[s] - cost - skipCost
                            if (v > best) { best = v; chosen = s; chosenGap = gap }
                        }
                        if (chosen >= 0) { // the best so far comes from this note
                            back = chosen
                            kind = d
                            val st = sTau[chosen]
                            tau = if (st.isNaN()) (chosenGap / dc).toFloat()
                            else st * exp((if (cross) alpha * 0.2f else alpha) * ln(chosenGap / (st * dc)).toFloat().coerceIn(-0.7f, 0.7f))
                        }
                        // A longer pause than the columns say (a rest the tab leaves out): any earlier placing of q.
                        // A rest has no line notes in it: those it holds cost a little.
                        val w = par.pauseUnexplained
                        var ptr = oldPtr[q]
                        while (ptr < lst.size && c.time[sCand[lst[ptr]]] <= t - par.pauseAfter) {
                            val s = lst[ptr++]
                            val ob = oldBest[q]
                            if (ob < 0 || sV[s] + w * line.upTo[sCand[s]] > sV[ob] + w * line.upTo[sCand[ob]]) oldBest[q] = s
                        }
                        oldPtr[q] = ptr
                        val ob = oldBest[q]
                        if (ob >= 0) {
                            val v = sV[ob] - par.pause - skipCost - w * max(0, line.before[ci] - line.upTo[sCand[ob]])
                            if (v > best) { best = v; back = ob; tau = sTau[ob]; kind = K_PAUSE }
                        }
                    }
                    val value = best + ev
                    if (value < bestV - par.beam) continue // far behind the best path so far: it won't catch up
                    // Far behind this note's best placing just before: the same note a little later, for a worse path.
                    if (t - recentT[i] <= par.noteWindow && value < recentV[i] - par.noteBeam) continue
                    if (size == sCand.size) grow()
                    val id = size++
                    sCand[id] = ci; sNote[id] = i; sV[id] = value; sTau[id] = tau; sBack[id] = back
                    sType[id] = type.toByte(); sKind[id] = kind.toByte()
                    byNote[i].add(id)
                    if (value > noteBest[i]) noteBest[i] = value
                    if (t - recentT[i] > par.noteWindow || value > recentV[i]) { recentT[i] = t; recentV[i] = value }
                    val left = p.unitEnd[u] - 1 - i
                    if (left < EDGE) {
                        if (qUnit.size == qTime.size) { qTime = qTime.copyOf(qTime.size * 2); qVal = qVal.copyOf(qVal.size * 2) }
                        qTime[qUnit.size] = t + par.minGap
                        qVal[qUnit.size] = value - par.skip * left
                        qUnit.add(u)
                        qState.add(id)
                    }
                    if (value > bestV) { bestV = value; bestS = id }
                }
            }
            log?.invoke("dp $delta: ${c.count} candidates, $size states")
            val chain = ArrayList<Int>()
            var s = bestS
            while (s >= 0) { chain += s; s = sBack[s] }
            chain.reverse()
            return Path(delta, if (bestS >= 0) bestV else 0f, IntArray(chain.size) { sNote[chain[it]] }, IntArray(chain.size) { sCand[chain[it]] },
                IntArray(chain.size) { sType[chain[it]].toInt() }, IntArray(chain.size) { sKind[chain[it]].toInt() })
        }
    }

    /** Checks the best path's passes, places every tab note of those kept, gives each an end, and builds the song. */
    private class Placement(
        val tab: ParsedTab, val p: Prep, val c: Candidates, val act: BasicPitch.Activations, val times: DoubleArray,
        val path: Path, val base: SongAnalysis, val keepLine: Boolean, val par: Params, val line: Line,
    ) {
        val delta = path.delta
        val octave = Math.floorDiv(delta + 5, 12)
        val transpose = delta - 12 * octave

        class Placed(val note: Int, val time: Double, val key: Int, val type: Int, val pass: Int)

        class Segment(val from: Int, val to: Int, val trusted: Boolean, val explained: Double, val lineNotes: Int)

        val placed = ArrayList<Placed>()

        fun result(): TabAlignment {
            val passes = ArrayList<TabPass>()
            val segments = kept()
            for ((k, seg) in segments.withIndex()) {
                val first = placed.size
                val after = segments.getOrNull(k + 1)?.from
                placePass(seg.from, seg.to, passes.size, if (after != null) c.time[path.cand[after]] - par.minGap else Double.MAX_VALUE)
                val mine = placed.subList(first, placed.size)
                passes += TabPass(mine.minOf { it.note }, mine.maxOf { it.note }, mine.first().time, mine.last().time)
            }
            placed.sortBy { it.time }
            correctFromLine()
            val ends = DoubleArray(placed.size) { k -> end(placed[k], placed.getOrNull(k + 1)?.time ?: Double.MAX_VALUE) }
            val passEnd = DoubleArray(passes.size) { Double.NEGATIVE_INFINITY }
            for ((k, x) in placed.withIndex()) passEnd[x.pass] = max(passEnd[x.pass], ends[k])
            val spans = passes.mapIndexed { k, ps -> ps.copy(end = max(ps.end, passEnd[k])) }
            // The song: the tab's notes (one heard a fret or two off at the pitch heard: the tab's fret is likely wrong),
            // and the prepared line's where no pass is heard, or where the line hears a note clearly that the tab leaves out.
            val inst = base.instrument
            val notes = ArrayList<Pair<SongNote, Int>>()
            var hand = 0
            var prevSource = -2
            var prevCourse = -1
            for ((k, x) in placed.withIndex()) {
                val heardPitch = if (x.type == T_WRONG) x.key + BasicPitch.MIDI_OFFSET - 12 * octave else p.midi[x.note] + transpose
                val (midi, cell) = cell(x.note, heardPitch, inst, hand)
                if (cell.second > 0) hand = cell.second
                var art = tab.notes[x.note].articulation
                if (art.isLegato && (prevSource != x.note - 1 || prevCourse != cell.first || x.type == T_WRONG)) art = Articulation.PICK
                notes += SongNote(x.time, max(x.time + 0.02, ends[k]), midi, cell.first, cell.second, art) to x.note
                prevSource = x.note
                prevCourse = cell.first
            }
            if (keepLine) {
                // (up to just after a pass's last note starts: the line goes on from there, cutting that note short)
                val at = DoubleArray(placed.size) { placed[it].time }
                var inside = 0
                for (bn in base.notes) {
                    val inPass = passes.any { bn.start >= it.start - 0.1 && bn.start <= it.end + 0.1 }
                    if (inPass && !leftOutByTab(bn, at)) continue
                    notes += bn to -1
                    if (inPass) inside++
                }
                log?.invoke("kept $inside of the line's notes inside passes")
            }
            notes.sortBy { it.first.start }
            val songNotes = notes.mapIndexed { k, (sn, _) ->
                val next = notes.getOrNull(k + 1)?.first?.start
                if (next != null && sn.end > next) sn.copy(end = max(sn.start + 0.01, next)) else sn
            }
            val heard = BooleanArray(p.n)
            val exact = BooleanArray(p.n)
            for (x in placed) if (x.type != T_NONE) {
                heard[x.note] = true
                if (x.type == T_EXACT || (x.type == T_UP && p.pair[x.note])) exact[x.note] = true
            }
            return TabAlignment(
                song = base.copy(instrument = inst, notes = songNotes),
                matched = exact.count { it },
                total = p.n,
                missing = (0 until p.n).filter { !exact[it] },
                sources = notes.map { it.second },
                passes = spans,
                transpose = transpose,
                octave = octave,
                differs = (0 until p.n).filter { heard[it] && !exact[it] },
            )
        }

        /**
         * Tab notes heard nowhere (placed where their columns put them) that the prepared line hears a fret or two off,
         * close by: the tab's fret is likely wrong, so they take the line's note's time and pitch.
         */
        private fun correctFromLine() {
            if (!keepLine) return
            val shift = 12 * octave
            for ((k, x) in placed.withIndex()) {
                if (x.type != T_NONE) continue
                val written = p.midi[x.note] + transpose
                val lo = (placed.getOrNull(k - 1)?.time ?: Double.NEGATIVE_INFINITY) + par.minGap
                val hi = (placed.getOrNull(k + 1)?.time ?: Double.MAX_VALUE) - par.minGap
                val bn = base.notes.filter { abs(it.start - x.time) <= par.correctWithin && it.start in lo..hi && abs(it.midi - shift - written) <= 2 }
                    .minByOrNull { abs(it.start - x.time) } ?: continue
                placed[k] = Placed(x.note, bn.start, bn.midi - BasicPitch.MIDI_OFFSET, if (bn.midi - shift == written) T_EXACT else T_WRONG, x.pass)
            }
        }

        /**
         * Whether the prepared line's note [bn], inside a pass, is one the tab leaves out (an ornament, a fill, a
         * simplified run): no tab note placed within [Params.keepNear] of it ([at], the placed times in order), and a
         * clear onset at its pitch ([Params.keepEvidence], or [Params.keepSame] at the pitch of a tab note next to it).
         */
        private fun leftOutByTab(bn: SongNote, at: DoubleArray): Boolean {
            var a = 0
            var b = at.size
            while (a < b) { val m = (a + b) ushr 1; if (at[m] < bn.start) a = m + 1 else b = m }
            if (a < at.size && at[a] - bn.start <= par.keepNear) return false
            if (a > 0 && bn.start - at[a - 1] <= par.keepNear) return false
            val key = bn.midi - BasicPitch.MIDI_OFFSET
            var ev = Float.NEGATIVE_INFINITY
            var ci = c.firstAt(bn.start - par.explained)
            while (ci < c.count && c.time[ci] <= bn.start + par.explained) {
                if (c.key[ci] == key) ev = max(ev, c.ev[ci])
                ci++
            }
            /** The pitch [x] is heard at (in the recording). */
            fun heardAt(x: Placed?) = if (x == null) -1 else if (x.key >= 0) x.key + BasicPitch.MIDI_OFFSET else p.midi[x.note] + delta
            // A note at its neighbour's pitch is often that note heard again (a held note, a re-pick): it needs more.
            val same = bn.midi == heardAt(placed.getOrNull(a - 1)) || bn.midi == heardAt(placed.getOrNull(a))
            return ev >= (if (same) par.keepSame else par.keepEvidence)
        }

        /**
         * The path's passes (broken where it starts afresh or jumps) worth keeping. One that goes on through the tab
         * (from the start, to the next part, or to a part the tab leads to, from a pass kept) must explain
         * [Params.keepTrusted] of the line's notes it spans (the line has extra notes; it may span fewer than 3); any
         * other (a repeat the tab doesn't mark) [Params.keepOther] of them, or [Params.keepRelative] of what those passes
         * explain if they explain less (a line with many extra notes). Only notes heard at their own pitch count: a pass
         * of octave matches is likely another melody.
         */
        private fun kept(): List<Segment> {
            val starts = path.kind.indices.filter { path.kind[it] == K_FRESH || path.kind[it] == K_JUMP } + path.kind.size
            val all = (0 until starts.size - 1).map { k ->
                val from = starts[k]
                val to = starts[k + 1]
                val onLine = (from until to).count {
                    line.on[path.cand[it]] && (path.type[it] == T_EXACT || (path.type[it] == T_UP && p.pair[path.note[it]]))
                }
                val lineNotes = line.upTo[path.cand[to - 1]] - line.before[path.cand[from]]
                val u = p.unitOf[path.note[from]]
                val w = if (from > 0) p.unitOf[path.note[from - 1]] else -1
                val trusted = path.note[from] - p.unitStart[u] < EDGE && (from == 0 || w + 1 == u || w in p.writtenFrom[u])
                Segment(from, to, trusted, onLine.toDouble() / max(1, lineNotes), lineNotes)
            }
            val through = all.filter { it.trusted && it.explained >= par.keepTrusted && it.to - it.from >= EDGE }
                .flatMap { s -> List(s.to - s.from) { s.explained } }.sorted()
            val keepOther = if (through.isEmpty()) par.keepOther.toDouble() else min(par.keepOther.toDouble(), par.keepRelative * through[through.size / 2])
            // A pass going on from one left out is a repeat too; a repeat needs line notes to explain.
            var lastKept = true
            return all.filter { s0 ->
                val s = if (s0.trusted && s0.from > 0 && !lastKept) Segment(s0.from, s0.to, false, s0.explained, s0.lineNotes) else s0
                val keep = line.empty || (s.trusted && s.lineNotes < 3) || s.explained >= (if (s.trusted) par.keepTrusted.toDouble() else keepOther)
                log?.invoke("pass %.2f-%.2f s: %d notes, explains %.2f of the line%s%s".format(c.time[path.cand[s.from]],
                    c.time[path.cand[s.to - 1]], s.to - s.from, s.explained, if (s.trusted) "" else " (a repeat)", if (keep) "" else ", left out"))
                lastKept = keep
                keep
            }
        }

        /** Path states [from, to) are one pass: places them and the tab notes they skip, after what's placed and before [next]. */
        private fun placePass(from: Int, to: Int, pass: Int, next: Double) {
            val lowBound = placed.lastOrNull()?.time ?: Double.NEGATIVE_INFINITY
            val mine = (from until to).map { k -> Placed(path.note[k], c.time[path.cand[k]], c.key[path.cand[k]], path.type[k], pass) }
            val out = ArrayList<Placed>()
            // Before the first: the notes of its unit it skipped, at the pass's tempo.
            val tau = tempo(mine)
            val first = mine.first()
            val before = (max(p.unitStart[p.unitOf[first.note]], first.note - EDGE) until first.note).toList()
            if (before.isNotEmpty()) {
                val t0 = first.time - tau * (p.col[first.note] - p.col[before.first()])
                fillBetween(before, t0, p.col[before.first()], first.time, p.col[first.note], max(lowBound + par.minGap, t0 - 2 * tau), first.time, pass, out, true)
            }
            for ((k, x) in mine.withIndex()) {
                out += x
                val following = mine.getOrNull(k + 1) ?: break
                if (following.note - x.note > 1) {
                    fillBetween((x.note + 1 until following.note).toList(), x.time, p.col[x.note], following.time, p.col[following.note],
                        x.time, following.time, pass, out, false)
                }
            }
            val last = mine.last()
            val after = (last.note + 1 until min(p.unitEnd[p.unitOf[last.note]], last.note + 1 + EDGE)).toList()
            if (after.isNotEmpty()) {
                val t1 = last.time + tau * (p.col[after.last()] - p.col[last.note])
                fillBetween(after, last.time, p.col[last.note], t1, p.col[after.last()], last.time, min(next, t1 + 2 * tau), pass, out, true)
            }
            placed += out
        }

        /** The pass's tempo: the median seconds per column between its notes (in one system); 0.1 s if unknown. */
        private fun tempo(mine: List<Placed>): Double {
            val r = ArrayList<Double>()
            for (k in 1 until mine.size) {
                val a = mine[k - 1]
                val b = mine[k]
                if (p.system[a.note] != p.system[b.note]) continue
                val dc = p.col[b.note] - p.col[a.note]
                if (dc > 0 && b.time > a.time) r += (b.time - a.time) / dc
            }
            if (r.isEmpty()) return 0.1
            r.sort()
            return r[r.size / 2]
        }

        /**
         * Places [notes] (consecutive tab notes) between ([tA], column [colA]) and ([tB], [colB]), within [lo, hi]:
         * each where the columns put it, moved to the best candidate near there at its pitch (or an octave or a fret
         * or two off), or to a rise of its activation. [extrapolated] notes (before or after a pass) that find
         * nothing are left out.
         */
        private fun fillBetween(
            notes: List<Int>, tA: Double, colA: Int, tB: Double, colB: Int, lo: Double, hi: Double, pass: Int,
            out: MutableList<Placed>, extrapolated: Boolean,
        ) {
            var floor = max(lo, out.lastOrNull()?.time ?: Double.NEGATIVE_INFINITY) + par.minGap
            val spacing = max(0.05, tB - tA) / (notes.size + 1)
            for ((k, j) in notes.withIndex()) {
                val star = if (colB != colA) tA + (tB - tA) * (p.col[j] - colA) / (colB - colA) else tA + spacing * (k + 1)
                val ceil = hi - par.minGap * (notes.size - k)
                if (ceil <= floor) { if (!extrapolated) out += Placed(j, (floor + ceil) / 2, -1, T_NONE, pass); continue }
                var bestScore = par.accept
                var bestT = Double.NaN
                var bestKey = -1
                var bestType = T_NONE
                val m = p.midi[j] + delta
                var ci = c.firstAt(floor)
                while (ci < c.count && c.time[ci] <= ceil) {
                    val pitch = c.key[ci] + BasicPitch.MIDI_OFFSET
                    val type: Int
                    val cost: Float
                    when (pitch - m) {
                        0 -> { type = T_EXACT; cost = 0f }
                        12 -> { type = T_UP; cost = if (p.pair[j]) par.octavePair else par.octave }
                        -12 -> { type = T_DOWN; cost = par.octave }
                        1, -1, 2, -2 -> { type = T_WRONG; cost = par.wrongFret }
                        else -> if (p.stack[j].any { it + delta == pitch }) { type = T_STACK; cost = par.stack } else { type = T_NONE; cost = 0f }
                    }
                    if (type != T_NONE) {
                        val s = c.ev[ci] - cost - abs(c.time[ci] - star).toFloat() / spacing.toFloat() * 0.5f
                        if (s > bestScore) { bestScore = s; bestT = c.time[ci]; bestKey = c.key[ci]; bestType = type }
                    }
                    ci++
                }
                if (bestT.isNaN()) {
                    val r = rise(m - BasicPitch.MIDI_OFFSET, floor, ceil, star)
                    if (!r.isNaN()) { bestT = r; bestKey = m - BasicPitch.MIDI_OFFSET; bestType = T_EXACT }
                }
                if (bestT.isNaN()) {
                    if (extrapolated) continue
                    bestT = star.coerceIn(floor, ceil)
                }
                out += Placed(j, bestT, bestKey, bestType, pass)
                floor = bestT + par.minGap
            }
            out.sortBy { it.time }
        }

        /** Where the activation of [key] rises most (by 0.15 or more) between [lo] and [hi], nearest [star]; NaN if nowhere. */
        private fun rise(key: Int, lo: Double, hi: Double, star: Double): Double {
            if (key < 0 || key >= BasicPitch.N_KEYS) return Double.NaN
            val fr = act.frames
            var f = frameAt(lo)
            var best = Double.NaN
            var bestScore = 0.0
            while (f < fr.size && times[f] <= hi) {
                if (f >= 2) {
                    val d = min(fr[f][key] - fr[f - 1][key], fr[f][key] - fr[f - 2][key])
                    if (d >= 0.15f) {
                        val s = d - abs(times[f] - star)
                        if (best.isNaN() || s > bestScore) { best = times[f] + BasicPitch.ONSET_LATENCY; bestScore = s }
                    }
                }
                f++
            }
            return best
        }

        private fun frameAt(t: Double): Int {
            var a = 0
            var b = times.size
            while (a < b) { val m = (a + b) ushr 1; if (times[m] < t) a = m + 1 else b = m }
            return a
        }

        /** When [x] stops sounding: its pitch's activation stays under the end level for 3 frames (before [next]). */
        private fun end(x: Placed, next: Double): Double {
            val fr = act.frames
            val key = if (x.key >= 0) x.key else (p.midi[x.note] + delta - BasicPitch.MIDI_OFFSET).coerceIn(0, BasicPitch.N_KEYS - 1)
            var f = frameAt(x.time)
            var last = f
            var low = 0
            f++
            while (f < fr.size && times[f] < next) {
                if (fr[f][key] >= par.endLevel) { last = f; low = 0 } else if (++low > 2) break
                f++
            }
            val e = times[min(fr.size - 1, last + 1)]
            return min(next, max(e, x.time + 0.05))
        }

        /**
         * The note's pitch on [inst] and where it's played: the tab's course and fret when [inst] has that course
         * (same open string), else the position nearest the hand ([hand], a fret), moved by octaves into range.
         */
        private fun cell(i: Int, midi: Int, inst: Instrument, hand: Int): Pair<Int, Pair<Int, Int>> {
            val open = tab.instrument.courses[tab.notes[i].course].openMidi
            val same = inst.courses.indexOfFirst { it.openMidi == open }
            if (same >= 0 && midi - open in 0..inst.frets) return midi to (same to midi - open)
            val low = inst.courses.first().openMidi
            val high = inst.courses.last().openMidi + inst.frets
            var m = midi
            while (m < low) m += 12
            while (m > high) m -= 12
            return m to (inst.position(m, hand) ?: (inst.courses.size - 1 to 0))
        }
    }
}
