package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.math.abs

/** How idiomatic a fingered line is: what the fingering tests check and the scratch evaluation prints. */
internal object FingeringMetrics {
    /** Two notes this close (onset to onset) are in a fast group. */
    const val FAST = 0.2

    /** The hand's reach without shifting: the index finger's fret and four more (the last a stretch). */
    const val REACH = 4

    data class Report(
        val notes: Int,
        /** Share of all notes on the top two courses, and of the notes they can play (A3 and up). */
        val topTwo: Double,
        val topTwoInRange: Double,
        /** Course changes per note. */
        val stringChanges: Double,
        /** Fret distance between consecutive fretted notes. */
        val meanJump: Double,
        val maxJump: Int,
        /** Fast picked pairs (both fretted) further apart than the hand reaches. */
        val spanViolations: Int,
        /** Hand positions left (greedy windows of [REACH] + 1 frets over the fretted notes), per phrase. */
        val shiftsPerPhrase: Double,
        val phrases: Int,
        /** Legato notes not on their predecessor's course, and cells that aren't on the neck or don't sound the note. */
        val legatoBroken: Int,
        val invalid: Int,
    ) {
        override fun toString() = "n %4d | top2 %5.1f%% (in range %5.1f%%) | strings/note %.2f | jump mean %.2f max %2d | span viol %2d | shifts/phrase %.2f (%d phrases)%s".format(
            notes, 100 * topTwo, 100 * topTwoInRange, stringChanges, meanJump, maxJump, spanViolations, shiftsPerPhrase, phrases,
            if (legatoBroken + invalid > 0) " | legato broken $legatoBroken, invalid $invalid" else "")
    }

    fun of(midis: List<Int>, starts: List<Double>, cells: List<Pair<Int, Int>>, inst: Instrument, legato: List<Boolean>? = null): Report {
        val n = midis.size
        val top = inst.courses.size - 2
        val inRange = midis.indices.filter { midis[it] >= inst.courses[top].openMidi }
        val leg = { i: Int -> legato?.getOrNull(i) == true }
        var changes = 0
        val jumps = ArrayList<Int>()
        var violations = 0
        var broken = 0
        for (i in 1 until n) {
            val (c0, f0) = cells[i - 1]
            val (c1, f1) = cells[i]
            if (c0 != c1) changes++
            if (leg(i) && c0 != c1) broken++
            if (f0 > 0 && f1 > 0) {
                jumps += abs(f1 - f0)
                if (starts[i] - starts[i - 1] < FAST && !leg(i) && abs(f1 - f0) > REACH) violations++
            }
        }
        var shifts = 0
        var lo = -1
        var hi = -1
        for ((_, f) in cells) {
            if (f == 0) continue
            if (lo < 0) { lo = f; hi = f; continue }
            val nlo = minOf(lo, f)
            val nhi = maxOf(hi, f)
            if (nhi - nlo > REACH) { shifts++; lo = f; hi = f } else { lo = nlo; hi = nhi }
        }
        val phrases = Fingering.phraseStarts(starts).count { it }
        val invalid = cells.indices.count { i ->
            val (c, f) = cells[i]
            c !in inst.courses.indices || f !in 0..inst.frets || inst.midiAt(c, f) != midis[i]
        }
        return Report(
            notes = n,
            topTwo = cells.count { it.first >= top }.toDouble() / maxOf(1, n),
            topTwoInRange = inRange.count { cells[it].first >= top }.toDouble() / maxOf(1, inRange.size),
            stringChanges = changes.toDouble() / maxOf(1, n - 1),
            meanJump = if (jumps.isEmpty()) 0.0 else jumps.average(),
            maxJump = jumps.maxOrNull() ?: 0,
            spanViolations = violations,
            shiftsPerPhrase = shifts.toDouble() / maxOf(1, phrases),
            phrases = phrases,
            legatoBroken = broken,
            invalid = invalid,
        )
    }

    /** The current fingering of a line, measured. */
    fun of(midis: List<Int>, starts: List<Double>, inst: Instrument, legato: List<Boolean>? = null) =
        of(midis, starts, SongAnalyzer.fingering(midis, starts, inst, legato), inst, legato)
}
