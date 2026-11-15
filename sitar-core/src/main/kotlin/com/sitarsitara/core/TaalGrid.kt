package com.sitarsitara.core

import kotlin.math.abs

/**
 * Notes laid out on a taal, the way Bhatkhande notation writes them: one cell per matra, vibhags divided by bars
 * and marked with their clap. Notes that share a matra are written together in its cell, split only as finely as
 * they need; an empty part holds the note before ("-").
 */
object TaalGrid {
    /** One matra: when it starts and ends, and its [parts] (equal shares of the matra), each a note index or null. */
    class Matra(val time: Double, val end: Double, val parts: List<Int?>)

    class Vibhag(val mark: String, val sam: Boolean, val matras: List<Matra>)

    /** One line of notation: a cycle or two of the taal, from [start] to [end] seconds of the recording. */
    class Line(val start: Double, val end: Double, val vibhags: List<Vibhag>) {
        val matras get() = vibhags.flatMap { it.matras }
    }

    /** Parts per matra the notes are first snapped to: sixteenth notes at four to the matra. */
    const val SUBDIVISIONS = 4

    /** Beat times covering [first]..[last], continued at both ends at the beats' own pace (or [tempo]'s). */
    fun beatGrid(beats: List<Double>, tempo: Double, first: Double, last: Double): List<Double> {
        val b: List<Double>
        val period: Double
        if (beats.size >= 2) {
            b = beats
            period = beats.zipWithNext { x, y -> y - x }.sorted().let { g -> (g[(g.size - 1) / 2] + g[g.size / 2]) / 2 }
        } else {
            period = 60.0 / (if (tempo > 0) tempo else 120.0)
            b = listOf(first)
        }
        val before = generateSequence(b.first() - period) { it - period }.takeWhile { it > first - period }.toList().reversed()
        val after = generateSequence(b.last() + period) { it + period }.takeWhile { it < last + 2 * period }.toList()
        return before + b + after
    }

    private fun slotTimes(beats: List<Double>, sub: Int): DoubleArray {
        val out = ArrayList<Double>()
        for ((a, b) in beats.zipWithNext()) for (k in 0 until sub) out += a + (b - a) * k / sub
        out += beats.last()
        return out.toDoubleArray()
    }

    /** Lays [notes] (in time order) out on [taal], with the song's [beats] (seconds) or else a steady [tempo]. */
    fun layout(notes: List<SitarNote>, taal: Taal, beats: List<Double>, tempo: Double): List<Line> {
        if (notes.isEmpty()) return emptyList()
        val sub = SUBDIVISIONS
        val slots = slotTimes(beatGrid(beats, tempo, notes.first().start, notes.last().end), sub)
        val perCycle = taal.matras * sub

        // Each note to its nearest slot; when two land together, the later one moves on.
        val placed = java.util.TreeMap<Int, Int>()
        for ((i, n) in notes.withIndex()) {
            var slot = nearest(slots, n.start)
            while (slot in placed && slot + 1 < slots.size) slot++
            placed.putIfAbsent(slot, i)
        }

        // The beat tracker can't hear where sam is, so the count starts at the matra of the first note.
        val origin = placed.firstKey() / sub * sub
        val cycles = java.util.TreeMap<Int, MutableMap<Int, Int>>()
        for ((slot, i) in placed) cycles.getOrPut(Math.floorDiv(slot - origin, perCycle)) { HashMap() }[Math.floorMod(slot - origin, perCycle)] = i

        // A lone empty cycle stays as a rest; longer gaps (a verse, another instrument's solo) are skipped.
        val sections = ArrayList<MutableList<Int>>()
        for (c in cycles.firstKey()..cycles.lastKey()) {
            if (c in cycles || (c - 1 in cycles && c + 1 in cycles)) {
                if (sections.isEmpty() || sections.last().last() != c - 1) sections += ArrayList<Int>()
                sections.last() += c
            }
        }

        fun timeOf(slot: Int) = slots[(origin + slot).coerceIn(0, slots.size - 1)].coerceAtLeast(0.0)
        val perLine = maxOf(1, 16 / taal.matras)
        val lines = ArrayList<Line>()
        for (section in sections) for (chunk in section.chunked(perLine)) {
            val vibhags = ArrayList<Vibhag>()
            for (cycle in chunk) {
                var matra = 0
                for ((v, size) in taal.vibhags.withIndex()) {
                    val matras = (0 until size).map {
                        val first = cycle * perCycle + (matra + it) * sub
                        val inCycle = (matra + it) * sub
                        Matra(timeOf(first), timeOf(first + sub), parts((0 until sub).map { k -> cycles[cycle]?.get(inCycle + k) }))
                    }
                    matra += size
                    vibhags += Vibhag(taal.marks[v], v == 0, matras)
                }
            }
            lines += Line(timeOf(chunk.first() * perCycle), timeOf((chunk.last() + 1) * perCycle), vibhags)
        }
        return lines
    }

    private fun nearest(times: DoubleArray, t: Double): Int {
        var best = 0
        for (i in times.indices) if (abs(times[i] - t) < abs(times[best] - t)) best = i
        return best
    }

    /** The notes of one matra in as few equal parts as show them all. */
    private fun parts(slots: List<Int?>): List<Int?> {
        var step = slots.size
        for ((k, n) in slots.withIndex()) if (n != null && k > 0) step = gcd(step, k)
        return slots.filterIndexed { k, _ -> k % step == 0 }
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Columns [s] takes in a monospace font, not counting combining marks (Devanagari vowel signs and dots). */
    fun width(s: String): Int = s.count { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    /** [lines] as text, two rows a line (the vibhag marks, then the notes), each line headed by its time. */
    fun text(lines: List<Line>, notes: List<SitarNote>, header: List<String>, script: Script = Script.LATIN): String {
        val out = StringBuilder()
        for (h in header) out.append(h).append('\n')
        out.append('\n')
        for (line in lines) {
            out.append('[').append(clock(line.start)).append("]\n")
            val cells = line.vibhags.map { v -> v.matras.map { m -> m.parts.joinToString("") { p -> p?.let { notes[it].text(script) } ?: "-" } } }
            val cell = maxOf(3, 1 + cells.flatten().maxOf { width(it) })
            val marks = StringBuilder()
            val row = StringBuilder()
            for ((k, v) in line.vibhags.withIndex()) {
                val group = cells[k]
                for (t in group) row.append(t).append(" ".repeat(cell - width(t)))
                val last = k + 1 == line.vibhags.size || line.vibhags[k + 1].sam
                val bar = if (last) "|| " else "| "
                marks.append(v.mark.padEnd(group.size * cell + bar.length))
                row.append(bar)
            }
            out.append(marks.trimEnd()).append('\n').append(row.trimEnd()).append("\n\n")
        }
        return out.toString()
    }

    fun clock(seconds: Double): String {
        val s = maxOf(0, seconds.toInt())
        return "%d:%02d".format(s / 60, s % 60)
    }
}
