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
        /** Fast picked pairs (both fretted) further apart than the hand reaches: the hand would have to leap. */
        val spanViolations: Int,
        /** Hand positions left (greedy windows of [REACH] + 1 frets over the fretted notes), per phrase. */
        val shiftsPerPhrase: Double,
        val phrases: Int,
        /**
         * Legato notes off their predecessor's course although that course has the note, slides (on one course)
         * from or to an open string, and cells that aren't on the neck or don't sound the note.
         */
        val legatoBroken: Int,
        val openSlides: Int,
        val invalid: Int,
        /** Legato notes, and those that had to leave the course (it doesn't have the note), so they are picked. */
        val legato: Int,
        val legatoForced: Int,
    ) {
        override fun toString() = "n %4d | top2 %5.1f%% (in range %5.1f%%) | strings/note %.2f | jump mean %.2f max %2d | span viol %2d | shifts/phrase %.2f (%d phrases) | legato %d%s".format(
            notes, 100 * topTwo, 100 * topTwoInRange, stringChanges, meanJump, maxJump, spanViolations, shiftsPerPhrase, phrases, legato,
            (if (legatoForced > 0) " ($legatoForced off the course)" else "") +
                if (legatoBroken + openSlides + invalid > 0) " | legato broken $legatoBroken, open slides $openSlides, invalid $invalid" else "")
    }

    fun of(midis: List<Int>, starts: List<Double>, cells: List<Pair<Int, Int>>, inst: Instrument, arts: List<Articulation>? = null): Report {
        val n = midis.size
        val top = inst.courses.size - 2
        val inRange = midis.indices.filter { midis[it] >= inst.courses[top].openMidi }
        val art = { i: Int -> arts?.getOrNull(i) ?: Articulation.PICK }
        var changes = 0
        val jumps = ArrayList<Int>()
        var violations = 0
        var broken = 0
        var forced = 0
        var openSlides = 0
        for (i in 1 until n) {
            val (c0, f0) = cells[i - 1]
            val (c1, f1) = cells[i]
            if (c0 != c1) changes++
            val leg = art(i).isLegato
            if (leg && c0 != c1) { if (midis[i] - inst.courses[c0].openMidi in 0..inst.frets) broken++ else forced++ }
            if (art(i) == Articulation.SLIDE && c0 == c1 && (f0 == 0 || f1 == 0)) openSlides++
            if (f0 > 0 && f1 > 0) {
                jumps += abs(f1 - f0)
                if (starts[i] - starts[i - 1] < FAST && !leg && abs(f1 - f0) > REACH) violations++
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
            openSlides = openSlides,
            invalid = invalid,
            legato = (1 until n).count { art(it).isLegato },
            legatoForced = forced,
        )
    }

    /** The current fingering of a line, measured. */
    fun of(midis: List<Int>, starts: List<Double>, inst: Instrument, arts: List<Articulation>? = null) =
        of(midis, starts, fingering(midis, starts, inst, arts), inst, arts)

    /** A song's fingering of a line played with [arts]. */
    fun fingering(midis: List<Int>, starts: List<Double>, inst: Instrument, arts: List<Articulation>?) =
        SongAnalyzer.fingering(midis, starts, inst, arts?.map { it.isLegato }, arts?.map { it == Articulation.SLIDE })

    /** A song's notes, measured. */
    fun of(s: SongAnalysis) = of(s.notes.map { it.midi }, s.notes.map { it.start }, s.notes.map { it.course to it.fret }, s.instrument,
        s.notes.map { it.articulation })
}
