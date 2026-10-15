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
 * written; [moved] are those shown at the pitch heard). Show the [missing] notes as less sure.
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
    val moved: List<Int> = emptyList(),
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
 * line notes left unexplained between two notes cost too. (The line counts only its clear notes, with an onset
 * at their pitch, and not a note heard again at the same pitch just after: a line finding more notes has more of
 * those, and how a pass is judged shouldn't depend on it.) The time between two notes is compared with their
 * distance in the tab's columns at the pass's tempo so far (a soft prior: tabs keep rhythm roughly), and a
 * longer pause than that costs a fixed amount (tabs often leave out rests). At the ends of the tab's parts
 * (sections and systems) a pass may stop and go on later with the next part, a part the tab says comes next
 * (x2, |: :|, a jump of a score's D.S. or Coda, a part named again), or, at a higher cost, any part; at the end
 * of a bar too, to go on at the start of a bar a few bars back or ahead, at a higher cost still inside a long bar.
 * So a tab of the intro only, or with the chorus written once, is found wherever the recording plays it. A pass
 * may also leave out a stretch of the tab's notes and go on later in the tab, after as long as those notes would
 * take: a singer carrying the tune where the bouzouki plays only the fills between its phrases.
 * Paths are kept moving forward through the tab: going back where the tab doesn't say to, and each note played
 * again then until the path is back where it left, cost a little, as does jumping from there further ahead than
 * where it left (tab notes never played), while going back to just after where it left is cheap. So a part played
 * again just as the tab writes it later (a second verse with the tune of the first) is found where the tab writes it,
 * and an earlier copy is played again only where the tab leaves that part out (an intro played again before the last
 * verse), the path then going on where the tab was.
 * A pass the tab doesn't lead to (a repeat it doesn't mark) is kept only if it explains most of the line's notes
 * where it lies, as many as the passes through the tab do, and most of the tab notes it spans are heard at their
 * own pitch, so parts of the song the tab doesn't cover keep the prepared line; one going on after notes left out
 * needs that, or its own notes heard clearly (the line holds much else where a singer carries the tune). One right
 * after a pass left out must pass together with it too, and such a pass is placed only from where its own notes are
 * clearly heard to where they last are (not the singer's notes coming through the vocal removal before and after
 * the fill a bouzouki plays). A tab in another key is found by trying the transposition its pitch classes suggest;
 * one written an octave off by trying that octave. Tab notes a pass skips go where their columns put them between
 * their neighbours, moved to a nearby candidate at their pitch (or a fret or two off: a wrong fret in the tab, played
 * at the pitch heard) or a rise of its activation; one heard nowhere takes a prepared-line note a fret or two off
 * close by, or is left out where the time between its neighbours is much longer than its columns say (a stretch not
 * played as written).
 * Inside the passes, the prepared line's notes no tab note explains stay when their onsets are clear (tabs leave
 * out ornaments and fills). Each note lasts while its pitch's activation stays up, until the next note.
 *
 * A four-minute song takes a few hundred ms on a desktop JVM, up to about 2 s with a long tab of a figure repeated
 * over and over (every candidate fits hundreds of tab notes, each going on or played again), several seconds with
 * 1500 notes of those or of chords (a tab of another song, tried in another key too), and several times that on a
 * phone: call [align] off the main thread, with progress shown, and cancel it when it's no longer wanted.
 */
object TabAligner {
    /** Where the aligner says what it found (for tests and tuning); null normally. */
    internal var log: ((String) -> Unit)? = null

    /**
     * [base] is the prepared song (for the bouzouki the player plays: notes keep the tab's course and fret
     * where it has them, else they are fingered for it; show [TabAlignment.song] as it is). With [keepLine], the
     * prepared line's notes stay where the tab isn't heard (outside its passes) and where the tab leaves out a note
     * the line hears clearly. Takes a while on long songs: call it off the main thread. [cancelled] is asked now
     * and then; once it says yes, the work stops with a [java.util.concurrent.CancellationException].
     */
    fun align(
        tab: ParsedTab,
        data: AnalysisData,
        base: SongAnalysis,
        keepLine: Boolean = true,
        cancelled: () -> Boolean = { false },
    ): TabAlignment = align(tab, data, base, keepLine, Params(), cancelled)

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
        /** A line note at the pitch of the one before it, within this (s), isn't counted (that note heard again). */
        val repeatWithin: Double = 0.5,
        /** Line notes count with an onset activation of this at their pitch. */
        val clearOnset: Float = 0.6f,
        // Pitch: heard an octave off (on an octave-pair course), at another note of the chord, a fret or two off.
        val octave: Float = 0.35f,
        val octavePair: Float = 0.05f,
        val stack: Float = 0.25f,
        val wrongFret: Float = 0.45f,
        // Costs of a pass: a note skipped, a line note it leaves unexplained (in a rest), a rest after pauseAfter s.
        val skip: Float = 0.9f,
        val unexplained: Float = 0.15f,
        val pauseUnexplained: Float = 0.1f,
        val pause: Float = 0.8f,
        val pauseAfter: Double = 0.8,
        // Starting afresh, going on to the next part (or one the tab leads to), the same part again, any other part
        // (more inside a section), the start of a bar inside a unit (jumpBar more, barReach bars away at most), inside
        // a long bar (jumpMid more).
        val fresh: Float = 1f,
        val jumpNext: Float = 0.2f,
        val jumpWritten: Float = 0.2f,
        val jumpSelf: Float = 3f,
        val jumpOther: Float = 2f,
        val jumpInside: Float = 0.3f,
        val jumpBar: Float = 0.6f,
        val barReach: Float = 8f,
        val jumpMid: Float = 1.5f,
        // Going back where the tab doesn't say to, each note played again then (revisitMax in all), and each tab note
        // a jump from there further ahead than where it left leaves never played; going on from the end of a part played
        // again to just after where it left (jumpReturn).
        val jumpBack: Float = 0.5f,
        val revisit: Float = 0.05f,
        val revisitMax: Float = 3f,
        val aheadNote: Float = 0.05f,
        val jumpReturn: Float = 0.5f,
        // Going on after leaving out skipMin tab notes or more (a sung phrase): a fixed cost, per note and per second,
        // from a state skipDelay s before or more, as long as the notes would take at its tempo (skipFast to skipSlow).
        val skipBase: Float = 3.0f,
        val skipNote: Float = 0.02f,
        val skipSecond: Float = 0.1f,
        val skipDelay: Double = 0.5,
        val skipMin: Float = 8f,
        val skipFast: Double = 0.6,
        val skipSlow: Double = 1.6,
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
        // Passes kept: explaining keepTrusted of the line, or (repeats) keepOther or keepRelative of what the others do,
        // with keepExact of the tab notes they span heard at their own pitch; or (after notes left out) with keepSupport
        // of their own notes clear on the line. A repeat starting within joinedWithin s of a pass left out must also pass
        // with it.
        val keepTrusted: Float = 0.5f,
        val keepOther: Float = 0.65f,
        val keepRelative: Double = 0.8,
        val keepExact: Float = 0.75f,
        val keepSupport: Float = 0.8f,
        val joinedWithin: Double = 0.8,
        // A repeat placed from the first trimStart of its notes in a row heard at their pitch (all but one clear on the
        // line) to the last trimEnd such notes; its ends are trimmed only if that leaves out trimMin notes or more.
        val trimStart: Float = 4f,
        val trimEnd: Float = 3f,
        val trimMin: Float = 4f,
        // Placing skipped notes (a candidate worth at least accept; one heard nowhere only between neighbours within
        // smearTempo times the time its columns take at the pass's tempo, or smearMin s), and ends (activation under
        // endLevel).
        val accept: Float = -0.1f,
        val smearTempo: Double = 2.5,
        val smearMin: Double = 0.6,
        val endLevel: Float = 0.25f,
        // The line's notes inside passes: kept with no tab note within keepNear s and evidence keepEvidence; one a fret
        // or two from a tab note heard nowhere, within correctWithin s, corrects it.
        val keepNear: Double = 0.06,
        val keepEvidence: Float = 0.35f,
        val keepSame: Float = 0.5f,
        val correctWithin: Double = 0.15,
    )

    internal fun align(
        tab: ParsedTab,
        data: AnalysisData,
        base: SongAnalysis,
        keepLine: Boolean,
        par: Params,
        cancelled: () -> Boolean = { false },
    ): TabAlignment {
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

        var best = Dp(prep, cands, 0, par, line, cancelled).run()
        log?.invoke("${cands.count} candidates, best path ${best.note.size} notes, score %.1f".format(best.score))
        val shift = transposition(prep, cands)
        if (shift != 0) {
            val alt = Dp(prep, cands, shift, par, line, cancelled).run()
            log?.invoke("transposed by $shift: score %.1f".format(alt.score))
            if (alt.score > best.score * 1.2f + 2f) best = alt
        }
        // Mostly heard an octave off: the tab is written an octave away from the recording.
        for (dir in intArrayOf(1, -1)) {
            val off = best.type.count { it == (if (dir > 0) T_UP else T_DOWN) }
            if (off > best.type.count { it == T_EXACT } && off >= 4) {
                val alt = Dp(prep, cands, best.delta + 12 * dir, par, line, cancelled).run()
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

    /** Whether [log] also gets every state of each pass (for tuning). */
    internal var logStates = false

    /** Notes skipped at a pass's ends or between its notes: at most this many in a row (less one). */
    private const val EDGE = 3

    /** The slowest tempo (seconds per tab column) looked for, and the one assumed before a pass has two notes. */
    private const val TAU_MAX = 0.4
    private const val TAU_FIRST = 0.3
    private const val MAX_SCAN = 8.0

    /**
     * The tab as arrays, and its units (cut at sections and systems) where passes may stop and go on. A pass may go
     * on from the end of a unit to the next unit, to one the tab's sections lead to ([writtenFrom]: marked repeats, a
     * part named again), the same unit again, or the start of any unit (cheaper at a section's start, [sectionStart]);
     * and from the end of a block (the notes of one bar in one unit) to the start of a block nearby.
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
        /** Blocks: notes of one bar and one unit. A pass may also leave the tab at a block's end and join it at a block's start. */
        val blockOf = IntArray(n)
        val blockStart: IntArray
        val blockEnd: IntArray

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
            val starts = Ints()
            for (i in 0 until n) {
                if (i == 0 || tab.notes[i].bar != tab.notes[i - 1].bar || unitOf[i] != unitOf[i - 1]) starts.add(i)
                blockOf[i] = starts.size - 1
            }
            blockStart = IntArray(starts.size) { starts[it] }
            blockEnd = IntArray(starts.size) { if (it + 1 < starts.size) starts[it + 1] else n }
        }
    }

    /** Candidate note starts, in time order (then by key): model frame, key, time (s), evidence and onset activation. */
    private class Candidates(
        val frame: IntArray, val key: IntArray, val time: DoubleArray, val ev: FloatArray, val onset: FloatArray, val count: Int,
        val lo: Int, val hi: Int,
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
                var ons = FloatArray(1024)
                fun add(f: Int, k: Int, time: Double, onset: Float, rise: Boolean) {
                    var s = 0f
                    for (x in f until min(t, f + 5)) s = max(s, fr[x][k])
                    val ev = s + par.onsetWeight * onset - par.bias
                    if (ev < par.minEvidence || (rise && s < par.riseSalience)) return
                    if (frames.size == tm.size) { tm = tm.copyOf(tm.size * 2); evs = evs.copyOf(evs.size * 2); ons = ons.copyOf(ons.size * 2) }
                    tm[frames.size] = time
                    evs[frames.size] = ev
                    ons[frames.size] = onset
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
                    FloatArray(c) { evs[idx[it]] }, FloatArray(c) { ons[idx[it]] }, c, lo, hi)
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
     * (or an octave off), starts within that much of it: the line heard that note too. Only the line's clear notes
     * count, with an onset of [Params.clearOnset] at their pitch (the line keeps weaker ones too: a bend's wobble, an
     * echo, a quieter note in between), and a note at the pitch of the one just before it, within
     * [Params.repeatWithin], doesn't (it's mostly that note heard again: a held note detected twice, a tremolo
     * broken up). So how much of the line a pass explains, and what it leaves unexplained, doesn't depend on how
     * many such notes the line keeps. (Every note counts if none is clear: a line made by hand.)
     */
    private class Line(val before: IntArray, val upTo: IntArray, val on: BooleanArray) {
        /** Without a prepared line (none was given), passes may start anywhere and are all kept. */
        val empty = on.none { it }

        companion object {
            fun of(base: SongAnalysis, p: Prep, c: Candidates, par: Params): Line {
                val near = base.notes.filter { it.midi in p.lowest - 26..p.highest + 26 }.sortedBy { it.start }
                // A note with a clear onset at its pitch (the line keeps weaker ones too, mostly ornaments or nothing).
                fun clear(n: SongNote): Boolean {
                    var ci = c.firstAt(n.start - par.explained)
                    while (ci < c.count && c.time[ci] <= n.start + par.explained) {
                        if (c.key[ci] + BasicPitch.MIDI_OFFSET == n.midi && c.onset[ci] >= par.clearOnset) return true
                        ci++
                    }
                    return false
                }
                val notes = near.filter { clear(it) }.ifEmpty { near }
                val t = DoubleArray(notes.size) { notes[it].start }
                // counted[k]: how many of the first k notes count.
                val counted = IntArray(notes.size + 1)
                for (k in notes.indices) {
                    val again = k > 0 && notes[k].midi == notes[k - 1].midi && t[k] - t[k - 1] < par.repeatWithin
                    counted[k + 1] = counted[k] + if (again) 0 else 1
                }
                fun index(x: Double): Int {
                    var a = 0
                    var b = t.size
                    while (a < b) { val m = (a + b) ushr 1; if (t[m] < x) a = m + 1 else b = m }
                    return a
                }
                val from = IntArray(c.count) { index(c.time[it] - par.explained) }
                val to = IntArray(c.count) { index(c.time[it] + par.explained) }
                val on = BooleanArray(c.count) { ci ->
                    val m = c.key[ci] + BasicPitch.MIDI_OFFSET
                    (from[ci] until to[ci]).any { Math.floorMod(notes[it].midi - m, 12) == 0 }
                }
                return Line(IntArray(c.count) { counted[from[it]] }, IntArray(c.count) { counted[to[it]] }, on)
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
    private const val K_SKIP = -4 // going on after tab notes not played (sung), later in the same stretch of the tab

    /** The most tab notes a pass may leave out in a row and go on (a sung phrase the bouzouki doesn't play). */
    private const val SKIP_MAX = 48

    /** A bar of this many notes or more may be joined anywhere, not only at its start (as may a unit without barlines inside). */
    private const val LONG_BLOCK = 12

    /** A path with more than this share of its notes heard an octave off is heard so (a tab written an octave away). */
    private const val OCTAVE_SHARE = 0.15

    /** A max over notes with point updates that only raise it, by range (a segment tree), and the state holding it. */
    private class NoteMax(n: Int) {
        private val size = Integer.highestOneBit(max(1, n - 1)) * 2
        private val v = FloatArray(2 * size) { NEG }
        private val s = IntArray(2 * size) { -1 }
        var state = -1
            private set

        fun raise(i: Int, value: Float, st: Int) {
            var k = i + size
            while (k >= 1 && value > v[k]) { v[k] = value; s[k] = st; k = k shr 1 }
        }

        /** The best value over notes [from]..[to] (NEG if none), its state in [state]. */
        fun range(from: Int, to: Int): Float {
            var best = NEG
            state = -1
            var a = max(0, from) + size
            var b = min(size - 1, to) + size + 1
            while (a < b) {
                if (a and 1 == 1) { if (v[a] > best) { best = v[a]; state = s[a] }; a++ }
                if (b and 1 == 1) { b--; if (v[b] > best) { best = v[b]; state = s[b] } }
                a = a shr 1
                b = b shr 1
            }
            return best
        }
    }

    /**
     * The dynamic programme over candidates in time order, the recording [delta] semitones above the tab. A state is
     * a tab note placed on a candidate, with the best path's value, its tempo (seconds per column) and where it came
     * from. States far behind the best one so far are dropped (a beam), and so are notes no state before can reach,
     * and a note's states a little behind its best one just before (the same note placed later, on another candidate).
     *
     * Paths move forward through the tab. Going back to an earlier part (or the same one again) where the tab doesn't
     * say to costs more ([Params.jumpBack]), and so does every note played after that ([Params.revisit], up to
     * [Params.revisitMax]) until the path is back where it left; going on from there further ahead than where it left
     * costs for each tab note never played ([Params.aheadNote]). So a part the recording plays again just as the tab
     * writes it later (a verse with the tune of the first) is found where the tab writes it, and an earlier copy only
     * where the tab leaves the part out (an intro played again, then back to where the tab was: [Params.jumpReturn]). A
     * state is either going on through the tab or revisiting (keeping the bar it left from); the beam counts what a
     * revisiting path still owes. A pass may also leave out a stretch of tab notes and go on later in the tab ([Params.skipBase]): a
     * sung phrase the bouzouki doesn't play, between its fills.
     */
    private class Dp(val p: Prep, val c: Candidates, val delta: Int, val par: Params, val line: Line, val cancelled: () -> Boolean) {
        private var size = 0
        private var sCand = IntArray(1 shl 14)
        private var sNote = IntArray(1 shl 14)
        private var sV = FloatArray(1 shl 14)
        private var sTau = FloatArray(1 shl 14)
        private var sBack = IntArray(1 shl 14)
        private var sType = ByteArray(1 shl 14)
        private var sKind = ByteArray(1 shl 14)
        private var sLeave = IntArray(1 shl 14) // the block a revisiting path left from; -1 going on through the tab
        private var sPaid = FloatArray(1 shl 14) // what a revisiting path has paid for it so far

        private fun grow() {
            val cap = sCand.size * 2
            sCand = sCand.copyOf(cap); sNote = sNote.copyOf(cap); sV = sV.copyOf(cap); sTau = sTau.copyOf(cap)
            sBack = sBack.copyOf(cap); sType = sType.copyOf(cap); sKind = sKind.copyOf(cap); sLeave = sLeave.copyOf(cap); sPaid = sPaid.copyOf(cap)
        }

        /**
         * What a path revisiting since [leave], having [paid] for it, still owes at note [i]: the notes back to where it left,
         * up to [Params.revisitMax] in all.
         */
        private fun owed(leave: Int, i: Int, paid: Float): Float =
            if (leave < 0) 0f else min(par.revisit * max(0, p.blockEnd[leave] - 1 - i), max(0f, par.revisitMax - paid))

        /**
         * What jumping ahead from revisiting state [s] to note [i] costs for the tab notes it leaves never played: those
         * after where it left, before [i] (a path that played a part again and goes on further ahead than where it left
         * has likely played an earlier copy of what comes there). Going on through the tab, jumping ahead costs no more:
         * a recording heard poorly leaves many stretches of the tab without notes to place.
         */
        private fun unplayed(s: Int, i: Int): Float {
            val leave = sLeave[s]
            if (leave < 0) return 0f
            return par.aheadNote * max(0, i - max(sNote[s], p.blockEnd[leave] - 1) - 1)
        }

        // The best way found so far into the state being built, for each mode (0 going on, 1 revisiting): its value, what
        // it's judged by (the value less what it owes), back pointer, tempo, how, and the block it left from.
        private val bV = FloatArray(2)
        private val bScore = FloatArray(2)
        private val bBack = IntArray(2)
        private val bTau = FloatArray(2)
        private val bKind = IntArray(2)
        private val bLeave = IntArray(2)
        private val bPaid = FloatArray(2)
        private var note = 0 // the note of the state being built

        /** Offers a way into note [note]: value [v] from state [back], revisiting since block [leave0] (-1: going on). */
        private fun offer(v: Float, back: Int, tau: Float, kind: Int, leave0: Int) {
            val leave = if (leave0 >= 0 && p.blockOf[note] > leave0) -1 else leave0 // back where it left: going on again
            val m = if (leave >= 0) 1 else 0
            val paid = if (leave >= 0 && back >= 0 && sLeave[back] >= 0) sPaid[back] else 0f
            val score = v - owed(leave, note, paid)
            if (score > bScore[m]) { bV[m] = v; bScore[m] = score; bBack[m] = back; bTau[m] = tau; bKind[m] = kind; bLeave[m] = leave; bPaid[m] = paid }
        }

        fun run(): Path {
            val n = p.n
            val units = p.unitStart.size
            val blocks = p.blockStart.size
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
            // Each note's best state lately, per mode: when, and its score.
            val recentT = Array(2) { DoubleArray(n) { Double.NEGATIVE_INFINITY } }
            val recentV = Array(2) { FloatArray(n) { NEG } }
            val oldPtr = IntArray(n) // a rest: the best state of each note (per mode) at least pauseAfter ago
            val oldBest = Array(2) { IntArray(n) { -1 } }
            // The best state ending each unit (from minGap after it), per mode; and each block, by block (a range at once too).
            val endV = Array(2) { FloatArray(units) { NEG } }
            val endS = Array(2) { IntArray(units) { -1 } }
            val ends = Array(2) { NoteMax(blocks) } // per mode, by block
            val endB = Array(2) { FloatArray(blocks) { NEG } }
            val endBS = Array(2) { IntArray(blocks) { -1 } }
            // The best revisiting state at the end of a block, by the block it left from: it may go back there, past any reach.
            val retV = FloatArray(blocks) { NEG }
            val retS = IntArray(blocks) { -1 }
            val reach = par.barReach.toInt()
            // States a pass may go on from after leaving notes out, once skipDelay has passed, per mode; by note, worth
            // their value plus what the time and the notes since would cost (taken off again when going on).
            val skipFrom = Array(2) { NoteMax(n) }
            var skipHead = 0 // states are made in time order
            var qTime = DoubleArray(256) // states near the end of a block (or unit), from when they can be gone on from
            val qState = Ints()
            var qHead = 0
            var bestScore = NEG // the beam's reference: the best state's value less what it owes
            var bestV = NEG
            var bestS = -1
            val alpha = par.tempoAlpha
            val freeHigh = exp(par.tempoFree.toDouble())
            val freeLow = 1 / freeHigh
            for (ci in 0 until c.count) {
                if (ci and 255 == 0 && cancelled()) throw java.util.concurrent.CancellationException()
                val t = c.time[ci]
                while (qHead < qState.size && qTime[qHead] <= t) {
                    val st = qState[qHead++]
                    val i = sNote[st]
                    val b = p.blockOf[i]
                    val inBlock = sV[st] - par.skip * (p.blockEnd[b] - 1 - i)
                    val m = if (sLeave[st] >= 0) 1 else 0
                    ends[m].raise(b, inBlock, st)
                    if (inBlock > endB[m][b]) { endB[m][b] = inBlock; endBS[m][b] = st }
                    if (m == 1 && inBlock > retV[sLeave[st]]) { retV[sLeave[st]] = inBlock; retS[sLeave[st]] = st }
                    val u = p.unitOf[i]
                    val left = p.unitEnd[u] - 1 - i
                    if (left < EDGE && sV[st] - par.skip * left > endV[m][u]) { endV[m][u] = sV[st] - par.skip * left; endS[m][u] = st }
                }
                while (skipHead < size && c.time[sCand[skipHead]] + par.skipDelay <= t) {
                    val st = skipHead++
                    val m = if (sLeave[st] >= 0) 1 else 0
                    skipFrom[m].raise(sNote[st], sV[st] + par.skipSecond * c.time[sCand[st]].toFloat() + par.skipNote * sNote[st], st)
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
                    note = i
                    bScore[0] = NEG; bScore[1] = NEG
                    val u = p.unitOf[i]
                    val into = i - p.unitStart[u]
                    val b = p.blockOf[i]
                    val intoBlock = i - p.blockStart[b]
                    val other = if (p.sectionStart[u]) par.jumpOther else par.jumpOther + par.jumpInside
                    /** From state [s] at the end of a unit (or of a pass), going back: revisiting since where it left. */
                    fun back(s: Int) = max(sLeave[s], p.blockOf[sNote[s]])
                    if (into < EDGE && (line.on[ci] || line.empty)) {
                        // Starting afresh, or going on from the end of a unit, where the line hears a note.
                        val skipIn = par.skip * into
                        offer(-par.fresh - skipIn, -1, Float.NaN, K_FRESH, -1)
                        fun from(v: Float, s: Int, leave: Int) { if (s >= 0) offer(v - skipIn, s, sTau[s], K_JUMP, leave) }
                        if (u > 0) for (m in 0..1) endS[m][u - 1].let { s -> if (s >= 0) from(endV[m][u - 1] - par.jumpNext, s, sLeave[s]) }
                        var selfWritten = false
                        for (w in p.writtenFrom[u]) {
                            if (w == u) selfWritten = true
                            for (m in 0..1) endS[m][w].let { s -> if (s >= 0) from(endV[m][w] - par.jumpWritten, s, sLeave[s]) }
                        }
                        // Ahead (a part left out) from the end of a bar: going on through the tab. Back, or the same
                        // part again: revisiting.
                        for (m in 0..1) {
                            val ahead = ends[m].range(0, b - 2)
                            ends[m].state.let { s -> if (s >= 0) from(ahead - other - unplayed(s, i), s, sLeave[s]) }
                            val behind = ends[m].range(b, blocks - 1)
                            ends[m].state.let { s -> if (s >= 0) from(behind - other - par.jumpBack, s, back(s)) }
                        }
                        if (!selfWritten) for (m in 0..1) endS[m][u].let { s -> if (s >= 0) from(endV[m][u] - par.jumpSelf, s, back(s)) }
                        // Back to where a path playing a part again left (just after it): where the tab was.
                        for (w in max(0, b - 2) until b) retS[w].let { s -> if (s >= 0) from(retV[w] - par.jumpReturn - unplayed(s, i), s, sLeave[s]) }
                    } else if (intoBlock < EDGE && (line.on[ci] || line.empty)) {
                        // At the start of a bar inside a unit: from the end of a bar further back or ahead.
                        val skipIn = par.skip * intoBlock
                        val bar = par.jumpOther + par.jumpInside + par.jumpBar
                        offer(-par.fresh - par.jumpBar - skipIn, -1, Float.NaN, K_FRESH, -1)
                        for (m in 0..1) {
                            val ev0 = endB[m]
                            var best = -1
                            for (w in max(0, b - reach)..b - 2) if (endBS[m][w] >= 0 && (best < 0 || ev0[w] > ev0[best])) best = w
                            if (best >= 0) endBS[m][best].let { s -> offer(ev0[best] - bar - skipIn - unplayed(s, i), s, sTau[s], K_JUMP, sLeave[s]) }
                            best = -1
                            for (w in b..min(blocks - 1, b + reach)) if (endBS[m][w] >= 0 && (best < 0 || ev0[w] > ev0[best])) best = w
                            if (best >= 0) endBS[m][best].let { s -> offer(ev0[best] - bar - par.jumpBack - skipIn, s, sTau[s], K_JUMP, back(s)) }
                        }
                        // Back to where a path playing a part again left (just after it).
                        for (w in max(0, b - 2) until b) retS[w].let { s -> if (s >= 0) offer(retV[w] - bar - skipIn - unplayed(s, i), s, sTau[s], K_JUMP, sLeave[s]) }
                    } else if (line.on[ci] && (p.blockEnd[b] - p.blockStart[b] >= LONG_BLOCK || p.blockEnd[b] - p.blockStart[b] == p.unitEnd[u] - p.unitStart[u])) {
                        // A part repeated from inside a bar (a tab whose parts don't each start a system or a bar).
                        offer(-par.fresh - par.jumpMid, -1, Float.NaN, K_FRESH, -1)
                        val mid = par.jumpOther + par.jumpInside + par.jumpMid
                        for (m in 0..1) {
                            val ahead = ends[m].range(b - reach, b - 1)
                            ends[m].state.let { s -> if (s >= 0) offer(ahead - mid - unplayed(s, i), s, sTau[s], K_JUMP, sLeave[s]) }
                            val behind = ends[m].range(b, b + reach)
                            ends[m].state.let { s -> if (s >= 0) offer(behind - mid - par.jumpBack, s, sTau[s], K_JUMP, back(s)) }
                        }
                    }
                    if ((line.on[ci] || line.empty) && type == T_EXACT && i > par.skipMin) {
                        // Going on after leaving out the tab notes since a note a while ago (sung, not played): as long as
                        // the notes left out would take at the pass's tempo, give or take (the best such note only).
                        val since = par.skipSecond * t.toFloat() + par.skipNote * (i - 1) + par.skipBase
                        for (m in 0..1) {
                            val v = skipFrom[m].range(i - SKIP_MAX, i - par.skipMin.toInt() - 1)
                            val s = skipFrom[m].state
                            if (s < 0) continue
                            val tau = if (sTau[s].isNaN()) TAU_FIRST else sTau[s].toDouble()
                            val takes = tau * (p.col[i] - p.col[sNote[s]])
                            val gap = t - c.time[sCand[s]]
                            if (gap >= par.skipFast * takes && gap <= par.skipSlow * takes + par.pauseAfter) offer(v - since, s, sTau[s], K_SKIP, sLeave[s])
                        }
                    }
                    // Nothing before can bring this near the best path: skip it at once.
                    var bound = max(if (bScore[0] > NEG) bV[0] else NEG, if (bScore[1] > NEG) bV[1] else NEG)
                    for (d in 1..EDGE) if (i - d >= 0) bound = max(bound, noteBest[i - d] - par.skip * (d - 1))
                    if (bound + ev < bestScore - par.beam) continue
                    for (d in 1..EDGE) {
                        val q = i - d
                        if (q < 0) break
                        val dc = max(1, p.col[i] - p.col[q])
                        val cross = p.system[q] != p.system[i]
                        val scan = min(MAX_SCAN, max(par.minScan, par.gapTempo * TAU_MAX * dc))
                        val skipCost = par.skip * (d - 1)
                        val lst = byNote[q]
                        var j = lst.size - 1
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
                            val lv = sLeave[s]
                            // No better than the best way in so far (for a revisiting state, unless it's back where it left).
                            if (if (lv < 0) v <= bScore[0] else p.blockOf[i] <= lv && v - owed(lv, i, sPaid[s]) <= bScore[1]) continue
                            val tau = if (st.isNaN()) (gap / dc).toFloat()
                            else st * exp((if (cross) alpha * 0.2f else alpha) * max(-0.7f, min(0.7f, ln(gap / (st * dc)).toFloat())))
                            offer(v, s, tau, d, lv)
                        }
                        // A longer pause than the columns say (a rest the tab leaves out): any earlier placing of q.
                        // A rest has no line notes in it: those it holds cost a little.
                        val w = par.pauseUnexplained
                        var ptr = oldPtr[q]
                        while (ptr < lst.size && c.time[sCand[lst[ptr]]] <= t - par.pauseAfter) {
                            val s = lst[ptr++]
                            val m = if (sLeave[s] >= 0) 1 else 0
                            val ob = oldBest[m][q]
                            if (ob < 0 || sV[s] + w * line.upTo[sCand[s]] > sV[ob] + w * line.upTo[sCand[ob]]) oldBest[m][q] = s
                        }
                        oldPtr[q] = ptr
                        for (m in 0..1) {
                            val ob = oldBest[m][q]
                            if (ob >= 0) offer(sV[ob] - par.pause - skipCost - w * max(0, line.before[ci] - line.upTo[sCand[ob]]), ob, sTau[ob], K_PAUSE, sLeave[ob])
                        }
                    }
                    for (m in 0..1) {
                        if (bScore[m] <= NEG / 2) continue
                        val cost = if (m == 1) min(par.revisit, max(0f, par.revisitMax - bPaid[m])) else 0f
                        val value = bV[m] + ev - cost
                        val score = value - owed(bLeave[m], i, bPaid[m] + cost)
                        if (score < bestScore - par.beam) continue // far behind the best path so far: it won't catch up
                        // Far behind this note's best placing just before: the same note a little later, for a worse path.
                        // (Revisiting is never better than going on through the tab from the same place.)
                        if (t - recentT[m][i] <= par.noteWindow && score < recentV[m][i] - par.noteBeam) continue
                        if (m == 1 && t - recentT[0][i] <= par.noteWindow && score < recentV[0][i] - par.noteBeam) continue
                        if (size == sCand.size) grow()
                        val id = size++
                        sCand[id] = ci; sNote[id] = i; sV[id] = value; sTau[id] = bTau[m]; sBack[id] = bBack[m]
                        sType[id] = type.toByte(); sKind[id] = bKind[m].toByte(); sLeave[id] = bLeave[m]; sPaid[id] = bPaid[m] + cost
                        byNote[i].add(id)
                        if (value > noteBest[i]) noteBest[i] = value
                        if (t - recentT[m][i] > par.noteWindow || score > recentV[m][i]) { recentT[m][i] = t; recentV[m][i] = score }
                        if (p.blockEnd[b] - 1 - i < EDGE) { // near the end of its block (and so of its unit, if it ends there)
                            if (qState.size == qTime.size) qTime = qTime.copyOf(qTime.size * 2)
                            qTime[qState.size] = t + par.minGap
                            qState.add(id)
                        }
                        if (score > bestScore) bestScore = score
                        if (value > bestV) { bestV = value; bestS = id }
                    }
                }
            }
            log?.invoke("dp $delta: ${c.count} candidates, $size states" + if (logStates) ", revisiting ${(0 until size).count { sLeave[it] >= 0 }}, by kind " + (0 until size).groupBy { sKind[it].toInt() }.mapValues { it.value.size }.toSortedMap() else "")
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

        /**
         * A pass of the path: states [from] to [to]; [trusted] if it goes on through the tab, [skipped] if it goes on after
         * tab notes left out (sung); how many of its notes are heard at their own pitch and clear on the line ([onLine]),
         * of the line's [lineNotes] where it lies ([explained]) and of its own ([support]); how many of the [span] tab notes
         * it spans are heard at their own pitch ([exactCount], [exact]); and whether its start or end was trimmed off.
         */
        class Segment(
            val from: Int, val to: Int, val trusted: Boolean, val skipped: Boolean, val onLine: Int, val lineNotes: Int, val exactCount: Int,
            val span: Int, val trimmedStart: Boolean = false, val trimmedEnd: Boolean = false,
        ) {
            val trimmed get() = trimmedStart || trimmedEnd
            val explained get() = min(1.0, onLine.toDouble() / max(1, lineNotes))
            val exact get() = exactCount.toDouble() / max(1, span)
            val support get() = onLine.toDouble() / (to - from)
        }

        val placed = ArrayList<Placed>()

        fun result(): TabAlignment {
            val passes = ArrayList<TabPass>()
            val segments = kept()
            for ((k, seg) in segments.withIndex()) {
                val first = placed.size
                val after = segments.getOrNull(k + 1)?.from
                // Notes a pass left out on purpose to go on later (sung) aren't placed at its ends.
                val skipsOn = after != null && after == seg.to && path.kind[after] == K_SKIP
                placePass(seg.from, seg.to, passes.size, if (after != null) c.time[path.cand[after]] - par.minGap else Double.MAX_VALUE,
                    path.kind[seg.from] != K_SKIP && !seg.trimmedStart, !skipsOn && !seg.trimmedEnd)
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
            val wrong = BooleanArray(p.n) // shown at the pitch heard
            for (x in placed) if (x.type != T_NONE) {
                heard[x.note] = true
                if (x.type == T_EXACT || (x.type == T_UP && p.pair[x.note])) exact[x.note] = true
                if (x.type == T_WRONG) wrong[x.note] = true
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
                moved = (0 until p.n).filter { wrong[it] && !exact[it] },
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

        /** Whether path state [k] is heard at its tab note's own pitch (or an octave up on an octave-pair course). */
        private fun exactAt(k: Int) = path.type[k] == T_EXACT || (path.type[k] == T_UP && p.pair[path.note[k]])

        /** Path states [from, to) as a pass: what it explains of the line and how much of it is heard. */
        private fun segment(from: Int, to: Int, trusted: Boolean, skipped: Boolean, trimmedStart: Boolean = false, trimmedEnd: Boolean = false) = Segment(
            from, to, trusted, skipped, (from until to).count { line.on[path.cand[it]] && exactAt(it) },
            line.upTo[path.cand[to - 1]] - line.before[path.cand[from]], (from until to).count { exactAt(it) },
            path.note[to - 1] - path.note[from] + 1, trimmedStart, trimmedEnd,
        )

        /** Whether the path is often heard an octave off: then a note an octave off counts as heard, trimming a pass. */
        private val octaves = octave != 0 || path.type.indices.count { !exactAt(it) && (path.type[it] == T_UP || path.type[it] == T_DOWN) } > OCTAVE_SHARE * path.type.size

        /**
         * Repeat [s] trimmed to where its own notes are clearly heard: from the first [Params.trimStart] states in a row
         * (going on through the tab, a note or two skipped at most) heard at their pitch, all but one clear on the line, to
         * the end of the last [Params.trimEnd] such states (clear on the line or not). A pass the tab doesn't lead to
         * starts and ends at a bar's ends: where only a fill of a bar is played (a singer carries the rest of the tune),
         * it takes in what it can of the other notes there, the singer's notes coming through the vocal removal among them.
         */
        private fun trim(s: Segment): Segment {
            val r0 = par.trimStart.toInt()
            val r1 = par.trimEnd.toInt()
            if (r0 <= 0 || s.to - s.from < max(r0, r1)) return s
            fun heard(k: Int) = exactAt(k) || (octaves && (path.type[k] == T_UP || path.type[k] == T_DOWN))
            fun run(k0: Int, len: Int, clear: Boolean): Boolean {
                for (k in k0 until k0 + len) if (!heard(k) || (k > k0 && path.kind[k] < 1)) return false
                return !clear || (k0 until k0 + len).count { line.on[path.cand[it]] } >= len - 1
            }
            var a = (s.from..s.to - r0).firstOrNull { run(it, r0, true) } ?: return s
            var b = (s.to - r1 downTo a).firstOrNull { run(it, r1, false) }?.let { it + r1 } ?: return s
            if (a - s.from < par.trimMin) a = s.from
            if (s.to - b < par.trimMin) b = s.to
            if (a == s.from && b == s.to) return s
            return segment(a, b, s.trusted, s.skipped, a > s.from, b < s.to)
        }

        /**
         * The path's passes (broken where it starts afresh, jumps, or leaves tab notes out) worth keeping. One that goes on
         * through the tab (from the start, to the next part, or to a part the tab leads to, from a pass kept) must explain
         * [Params.keepTrusted] of the line's notes it spans (the line has extra notes; it may span fewer than 3); any
         * other (a repeat the tab doesn't mark) [Params.keepOther] of them, or [Params.keepRelative] of what those passes
         * explain if they explain less (a line with many extra notes), and have [Params.keepExact] of the tab notes it
         * spans heard (a phrase that only sounds like it, in a part the tab leaves out, has many of its notes wrong or
         * missing). Only notes heard at their own pitch count: a pass of octave matches is likely another melody.
         * Where the line holds much else (a singer carries the tune, the bouzouki plays fills between), a pass whose own
         * notes are heard clearly ([Params.keepSupport] of them, and [Params.keepExact] of the tab notes it spans) is kept
         * too; one that goes on after leaving tab notes out needs that, or what a repeat needs, and more than [EDGE] notes.
         * A repeat right after a pass left out must also pass judged together with it, and only its part where its own
         * notes are clearly heard is placed ([trim]).
         */
        private fun kept(): List<Segment> {
            val starts = path.kind.indices.filter { path.kind[it] == K_FRESH || path.kind[it] == K_JUMP || path.kind[it] == K_SKIP } + path.kind.size
            val all = (0 until starts.size - 1).map { k ->
                val from = starts[k]
                val to = starts[k + 1]
                val u = p.unitOf[path.note[from]]
                val w = if (from > 0) p.unitOf[path.note[from - 1]] else -1
                val skipped = path.kind[from] == K_SKIP
                val trusted = !skipped && path.note[from] - p.unitStart[u] < EDGE && (from == 0 || w + 1 == u || w in p.writtenFrom[u])
                segment(from, to, trusted, skipped)
            }
            val through = all.filter { it.trusted && it.explained >= par.keepTrusted && it.to - it.from >= EDGE }
                .flatMap { s -> List(s.to - s.from) { s.explained } }.sorted()
            val keepOther = if (through.isEmpty()) par.keepOther.toDouble() else min(par.keepOther.toDouble(), par.keepRelative * through[through.size / 2])
            // A pass going on from one left out is a repeat too; a repeat needs line notes to explain.
            var lastKept = true
            var prev: Segment? = null
            val out = ArrayList<Segment>()
            for (s0 in all) {
                val s = if ((s0.trusted || s0.skipped) && s0.from > 0 && !lastKept) Segment(s0.from, s0.to, false, false, s0.onLine, s0.lineNotes, s0.exactCount, s0.span) else s0
                fun heard(x: Segment) = x.support >= par.keepSupport && x.exact >= par.keepExact
                fun repeat(x: Segment) = x.explained >= keepOther && x.exact >= par.keepExact
                // Right after a pass left out, a repeat is judged with it too: a stretch of the recording the tab isn't heard in
                // can hold a few bars that sound like a part of it, and the path may break that stretch up at a bar (going on
                // elsewhere in the tab), but what's found there isn't kept where the whole stretch wouldn't be.
                val p0 = prev
                val joined = if (!s.trusted && !lastKept && p0 != null && c.time[path.cand[s0.from]] - c.time[path.cand[p0.to - 1]] <= par.joinedWithin) {
                    Segment(p0.from, s.to, false, false, p0.onLine + s.onLine, p0.lineNotes + s.lineNotes, p0.exactCount + s.exactCount, p0.span + s.span)
                } else null
                // What's placed of a repeat: where its own notes are clearly heard.
                val shown = if (s.trusted) s else trim(s)
                val keep = line.empty || when {
                    s.trusted -> s.lineNotes < 3 || s.explained >= par.keepTrusted
                    s.skipped -> s.to - s.from > EDGE && (heard(s) || repeat(s))
                    else -> repeat(s) && (joined == null || repeat(joined))
                }
                log?.invoke("pass %.2f-%.2f s: %d notes, explains %.2f of the line, %.2f exact, %.2f heard%s%s%s".format(c.time[path.cand[s0.from]],
                    c.time[path.cand[s0.to - 1]], s0.to - s0.from, s.explained, s.exact, s.support,
                    if (s.trusted) "" else if (s.skipped) " (after notes left out)" else " (a repeat)",
                    if (shown.trimmed) " trimmed to %.2f-%.2f".format(c.time[path.cand[shown.from]], c.time[path.cand[shown.to - 1]]) else "",
                    if (keep) "" else ", left out"))
                if (logStates) log?.invoke("  states: " + (s0.from until s0.to).joinToString(" ") { k ->
                    "%d@%.2f%s%s%s".format(path.note[k], c.time[path.cand[k]], "=^vs?"[path.type[k].coerceIn(0, 4)], if (line.on[path.cand[k]]) "*" else "",
                        if (path.kind[k] < 0) "k${path.kind[k]}" else if (path.kind[k] > 1) "d${path.kind[k]}" else "")
                })
                lastKept = keep
                prev = s0
                if (keep) out += shown
            }
            return out
        }

        /**
         * Path states [from, to) are one pass: places them and the tab notes they skip, after what's placed and before
         * [next]; the notes of their bar before the first and after the last if [ahead] and [behind] say so.
         */
        private fun placePass(from: Int, to: Int, pass: Int, next: Double, ahead: Boolean, behind: Boolean) {
            val lowBound = placed.lastOrNull()?.time ?: Double.NEGATIVE_INFINITY
            val mine = (from until to).map { k -> Placed(path.note[k], c.time[path.cand[k]], c.key[path.cand[k]], path.type[k], pass) }
            val out = ArrayList<Placed>()
            // Before the first: the notes of its bar it skipped, at the pass's tempo.
            val tau = tempo(mine)
            val first = mine.first()
            val before = (max(p.blockStart[p.blockOf[first.note]], first.note - EDGE) until first.note).toList()
            if (before.isNotEmpty() && ahead) {
                val t0 = first.time - tau * (p.col[first.note] - p.col[before.first()])
                fillBetween(before, t0, p.col[before.first()], first.time, p.col[first.note], max(lowBound + par.minGap, t0 - 2 * tau), first.time, pass, out, true)
            }
            for ((k, x) in mine.withIndex()) {
                out += x
                val following = mine.getOrNull(k + 1) ?: break
                if (following.note - x.note > 1) {
                    // Notes heard nowhere go where their columns put them, unless the time between is much longer than the
                    // columns say (a rest the tab doesn't show, a stretch not played): then they're left out.
                    val dc = p.col[following.note] - p.col[x.note]
                    val smear = following.time - x.time <= max(par.smearMin, par.smearTempo * tau * dc)
                    fillBetween((x.note + 1 until following.note).toList(), x.time, p.col[x.note], following.time, p.col[following.note],
                        x.time, following.time, pass, out, !smear)
                }
            }
            val last = mine.last()
            val after = (last.note + 1 until min(p.blockEnd[p.blockOf[last.note]], last.note + 1 + EDGE)).toList()
            if (after.isNotEmpty() && behind) {
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
