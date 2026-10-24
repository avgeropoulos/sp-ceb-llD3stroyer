package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import java.util.Random
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Writes notes as a plain-text tab, the way people post them, for tests and the tab evaluation: one line per course
 * (highest on top), "D|" names (or none, or Greek), dashes, h p / \ marks for legato and ~ after a tremolo note.
 * Columns follow the times at [step] seconds a column; with [rnd] the rhythm is only roughly kept (the spacing
 * stretches and shrinks every dozen notes or so and notes move a column now and then), as hand-written tabs do.
 */
internal object TabText {
    /** A note to write: where it's played, how, when (s), and [ref], what the evaluation knows it as (-1: none). */
    data class Note(val course: Int, val fret: Int, val articulation: Articulation, val time: Double, val ref: Int = -1)

    enum class Names { LATIN, NONE, GREEK }

    fun write(notes: List<Note>, inst: Instrument, step: Double, width: Int = 56, rnd: Random? = null, names: Names = Names.LATIN): String {
        if (notes.isEmpty()) return ""
        // Columns from the times, roughly when rnd is given, at least a dash apart.
        val cols = IntArray(notes.size)
        var density = 1.0
        var x = 0.0
        for (k in notes.indices) {
            if (rnd != null && k % 12 == 0) density = 0.75 + rnd.nextDouble() * 0.6
            if (k > 0) x += (notes[k].time - notes[k - 1].time) / step * density
            var c = x.roundToInt()
            if (rnd != null && rnd.nextDouble() < 0.25) c += if (rnd.nextBoolean()) 1 else -1
            if (k > 0) c = max(c, cols[k - 1] + digits(notes[k - 1].fret) + 1)
            cols[k] = c
        }
        val out = StringBuilder()
        var k = 0
        while (k < notes.size) {
            val start = cols[k] - 2
            var end = k
            while (end < notes.size && cols[end] + digits(notes[end].fret) - start <= width - 2) end++
            if (end == k) end = k + 1
            val len = (cols[end - 1] + digits(notes[end - 1].fret) - start + 3).coerceAtLeast(8)
            val rows = Array(inst.courses.size) { CharArray(len) { '-' } }
            for (j in k until end) {
                val n = notes[j]
                val row = rows[n.course]
                val c = cols[j] - start
                val s = n.fret.toString()
                for ((i, ch) in s.withIndex()) row[c + i] = ch
                val prev = if (j > k) notes[j - 1] else null
                if (prev != null && n.articulation.isLegato && prev.course == n.course) {
                    row[c - 1] = when (n.articulation) {
                        Articulation.HAMMER -> 'h'
                        Articulation.PULL -> 'p'
                        else -> if (n.fret > prev.fret) '/' else '\\'
                    }
                }
                if (n.articulation == Articulation.TREMOLO) {
                    val next = if (j + 1 < end) cols[j + 1] - start - 1 else len - 1
                    for (i in c + s.length until minOf(next, c + s.length + 3)) row[i] = '~'
                }
            }
            for (course in inst.courses.indices.reversed()) {
                out.append(label(inst, course, names)).append(String(rows[course])).append("|\n")
            }
            out.append('\n')
            k = end
        }
        return out.toString()
    }

    private fun digits(fret: Int) = if (fret >= 10) 2 else 1

    private fun label(inst: Instrument, course: Int, names: Names): String {
        val pc = inst.courses[course].openMidi % 12
        val top = course == inst.courses.size - 1
        return when (names) {
            Names.NONE -> "|"
            Names.LATIN -> (when (pc) { 0 -> "C"; 2 -> if (top && inst == Instrument.TRICHORDO) "d" else "D"; 5 -> "F"; else -> "A" }) + "|"
            Names.GREEK -> (when (pc) { 0 -> "Ντο"; 2 -> if (top && inst == Instrument.TRICHORDO) "ρε" else "Ρε"; 5 -> "Φα"; else -> "Λα" }) + "|"
        }
    }
}
