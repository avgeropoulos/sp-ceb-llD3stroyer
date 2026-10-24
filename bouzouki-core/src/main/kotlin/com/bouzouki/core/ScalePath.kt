package com.bouzouki.core

/** A place on the neck: a course and a fret (0 = open). */
data class Cell(val course: Int, val fret: Int)

/** How the scale player moves: across the courses in one position, or down the neck on one course. */
enum class Direction { ACROSS, DOWN }

/** Works out where on the neck the scale player plays a scale or an arpeggio. */
object ScalePath {
    /** The arpeggio of a dromos: its 1st, 3rd, 5th and 7th degrees. */
    fun arpeggio(d: Dromos) = intArrayOf(d.semitones[0], d.semitones[2], d.semitones[4], d.semitones[6])

    /**
     * Frets covered by the hand at [handFret] when playing across the neck. The span is the
     * largest gap between neighbouring courses, so no note is missing between courses:
     * five frets on the tetrachordo (courses a fourth apart), seven on the trichordo (D to A is a fifth).
     */
    fun window(inst: Instrument, handFret: Int): IntRange {
        val span = inst.courses.zipWithNext { a, b -> b.openMidi - a.openMidi }.max()
        val lo = handFret.coerceIn(0, maxHandFret(inst))
        return lo..(lo + span - 1)
    }

    fun maxHandFret(inst: Instrument): Int {
        val span = inst.courses.zipWithNext { a, b -> b.openMidi - a.openMidi }.max()
        return inst.frets - span + 1
    }

    /** Where the hand starts for [rootPc]: the first root on the lowest course. */
    fun defaultHandFret(inst: Instrument, rootPc: Int) = Math.floorMod(rootPc - inst.courses[0].openMidi, 12)

    /** First root on [course] to the highest root on it, every note of [intervals] in between. */
    fun downTheNeck(inst: Instrument, course: Int, rootPc: Int, intervals: IntArray): List<Cell> {
        val open = inst.courses[course].openMidi
        val first = Math.floorMod(rootPc - open, 12)
        var last = first
        while (last + 12 <= inst.frets) last += 12
        return (first..last)
            .filter { Math.floorMod(open + it - rootPc, 12) in intervals }
            .map { Cell(course, it) }
    }

    /** Ascending through every course in the hand position at [handFret], from its lowest root to its highest root. */
    fun acrossTheNeck(inst: Instrument, rootPc: Int, intervals: IntArray, handFret: Int): List<Cell> {
        val frets = window(inst, handFret)
        val cells = ArrayList<Cell>()
        var lastMidi = Int.MIN_VALUE
        for (c in inst.courses.indices) for (f in frets) {
            val midi = inst.midiAt(c, f)
            if (midi > lastMidi && Math.floorMod(midi - rootPc, 12) in intervals) {
                cells.add(Cell(c, f))
                lastMidi = midi
            }
        }
        val isRoot = { cell: Cell -> Math.floorMod(inst.midiAt(cell.course, cell.fret) - rootPc, 12) == 0 }
        val first = cells.indexOfFirst(isRoot)
        val last = cells.indexOfLast(isRoot)
        return when {
            first < 0 -> cells
            last > first -> cells.subList(first, last + 1)
            else -> cells.subList(first, cells.size)
        }
    }

    /** Up, then back down without repeating the top note. */
    fun upAndBack(up: List<Cell>) = up + up.reversed().drop(1)
}
