package com.bouzouki.core.song

import com.bouzouki.core.Dromos
import com.bouzouki.core.Instrument
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The song fingering plays a melody the Greek way: up and down the A and D courses, not across the neck. */
class FingeringTest {
    private val tetra = Instrument.TETRACHORDO
    private val tri = Instrument.TRICHORDO

    /** Onsets [step] seconds apart, with [pauses] (extra seconds) after some notes. */
    private fun timed(midis: List<Int>, step: Double, pauses: Map<Int, Double> = emptyMap()): List<Double> {
        var t = 0.0
        return midis.indices.map { i -> val s = t; t += step + (pauses[i] ?: 0.0); s }
    }

    private class Line(val midis: List<Int>, val starts: List<Double>, val arts: List<Articulation>? = null)

    /** Articulations from a pattern: '.' picked, 'h' hammer-on, 'p' pull-off, 's' slide, 't' tremolo. */
    private fun arts(pattern: String) = pattern.map {
        when (it) { 'h' -> Articulation.HAMMER; 'p' -> Articulation.PULL; 's' -> Articulation.SLIDE; 't' -> Articulation.TREMOLO; else -> Articulation.PICK }
    }

    private val hitzazUp = listOf(62, 63, 66, 67, 69, 70, 72, 74, 75, 78, 79, 81, 82, 84, 86) // D Hitzaz, two octaves
    private val minore = listOf(69, 71, 72, 74, 76, 74, 72, 71, 69, 68, 69, 71, 72, 71, 69, 69) // A minore
    private val ousak = listOf(74, 72, 70, 69, 67, 65, 63, 62, 60, 58, 57, 55, 57, 58, 60, 62) // D ousak down to G3

    /** Typical laiko lines: runs, phrases with pauses, leaps, legato. */
    private val typical = mapOf(
        "hitzaz run up in 16ths" to Line(hitzazUp, timed(hitzazUp, 0.125)),
        "hitzaz run down in 8ths" to Line(hitzazUp.reversed(), timed(hitzazUp, 0.25)),
        "minore phrase" to Line(minore, timed(minore, 0.18, mapOf(7 to 0.3))),
        "ousak down to G3" to Line(ousak, timed(ousak, 0.2)),
        "matzore in 8ths" to listOf(67, 69, 71, 72, 74, 76, 74, 72, 71, 69, 67, 66, 67).let { Line(it, timed(it, 0.3)) },
        "two phrases an octave apart" to listOf(62, 64, 65, 67, 69, 67, 65, 64, 62, 74, 76, 77, 79, 81, 79, 77, 76, 74)
            .let { Line(it, timed(it, 0.15, mapOf(8 to 0.8))) },
        "leaps of a fifth and an octave" to listOf(69, 76, 74, 72, 81, 79, 77, 76, 64, 62, 74, 72, 70, 69)
            .let { Line(it, timed(it, 0.25)) },
        "legato phrase" to listOf(69, 76, 74, 76, 74, 72, 64, 62, 67, 69, 70, 69, 67, 65, 64, 62).let {
            Line(it, timed(it, 0.2), arts(".s.hp..p.h.p..pp"))
        },
        // From a synthesized evaluation song: the fingering before this one played most of it on F and C around fret 11.
        "hitzaz phrase in E" to Line(
            listOf(64, 62, 65, 64, 62, 64, 59, 60, 59, 60, 64, 65, 64, 65, 68, 69, 68, 60, 61, 59),
            listOf(0.0, 0.249, 0.499, 0.774, 1.009, 2.251, 2.377, 2.514, 2.641, 2.749, 2.879, 2.989, 3.133, 3.256, 3.376, 3.511, 3.641, 4.254, 4.504, 5.26),
        ),
    )

    /** Slides from and to notes that are open strings (D4, A3), with hammer-ons and pull-offs from and to them. */
    private val slidesAroundOpen = listOf(62, 69, 67, 69, 64, 62, 57, 60, 62, 64, 65, 64, 62).let {
        Line(it, timed(it, 0.18, mapOf(5 to 0.3)), arts(".s.h.s.s.h.pp"))
    }

    private fun cells(l: Line, inst: Instrument) = FingeringMetrics.fingering(l.midis, l.starts, inst, l.arts)

    @Test
    fun typicalLinesStayOnTheTopTwoCourses() {
        for (inst in Instrument.entries) for ((name, l) in typical) {
            val r = FingeringMetrics.of(l.midis, l.starts, inst, l.arts)
            assertEquals(0, r.invalid + r.legatoBroken + r.legatoForced + r.openSlides, "$inst $name: $r ${cells(l, inst)}")
            assertTrue(r.topTwoInRange >= 0.85, "$inst $name: $r ${cells(l, inst)}")
            assertTrue(r.stringChanges <= 0.4, "$inst $name: $r ${cells(l, inst)}") // fifths cross A-D, as in position
            assertEquals(0, r.spanViolations, "$inst $name: $r ${cells(l, inst)}")
        }
    }

    @Test
    fun aRunClimbsTheDCourseInsteadOfCrossingTheNeck() {
        val l = typical.getValue("hitzaz run up in 16ths")
        for (inst in Instrument.entries) {
            val c = cells(l, inst)
            val top = inst.courses.lastIndex
            assertTrue(c.all { it.first >= top - 1 }, "$inst $c")
            // From the first notes up it is all one string, the hand shifting up the neck.
            val fromD = c.drop(3)
            assertTrue(fromD.all { it.first == top }, "$inst $c")
            assertTrue(fromD.zipWithNext().all { (a, b) -> b.second > a.second }, "$inst $c")
        }
    }

    @Test
    fun aMinorePhraseStaysUpTheNeckOnTheDCourse() {
        val l = typical.getValue("minore phrase")
        for (inst in Instrument.entries) {
            val c = cells(l, inst)
            assertTrue(c.all { it.first == inst.courses.lastIndex }, "$inst $c")
            assertTrue(c.all { it.second in 6..14 }, "$inst $c") // G#4 to E5 along the D course, not open position on the bass courses
        }
    }

    @Test
    fun legatoNotesStayOnTheirCourse() {
        val l = typical.getValue("legato phrase")
        for (inst in Instrument.entries) {
            val c = cells(l, inst)
            for (i in 1 until c.size) if (l.arts!![i].isLegato) assertEquals(c[i - 1].first, c[i].first, "$inst note $i: $c")
            assertEquals(7, c[1].second - c[0].second, "$inst slide A4 to E5 along one string: $c")
            assertEquals(inst.courses.lastIndex to 0, c.last(), "$inst pull-offs F4 E4 D4 down to the open D: $c")
        }
        // A legato note the previous course can't play still gets a valid place.
        val odd = SongAnalyzer.fingering(listOf(64, 52), listOf(0.0, 0.2), tetra, listOf(false, true))
        assertEquals(52, tetra.midiAt(odd[1].first, odd[1].second))
        // However long a legato run, it stays on its course (here the only one with its first note).
        val run = listOf(48, 50, 52, 53, 55, 57, 59, 60, 62, 64, 65, 67, 69, 71, 72)
        assertEquals(setOf(0), SongAnalyzer.fingering(run, timed(run, 0.15), tetra, run.indices.map { it > 0 }).map { it.first }.toSet())
    }

    @Test
    fun slidesNeverStartOrEndOnAnOpenString() {
        val l = slidesAroundOpen
        for (inst in Instrument.entries) {
            val c = cells(l, inst)
            val r = FingeringMetrics.of(l.midis, l.starts, c, inst, l.arts)
            assertEquals(0, r.invalid + r.legatoBroken + r.legatoForced + r.openSlides, "$inst $r $c")
            // A3 slid up to C4 goes on the course below (F or the low D), the one place it can be slid.
            assertEquals(listOf(inst.courses.size - 3), c.subList(6, 8).map { it.first }.distinct(), "$inst $c")
            // A laiko slide from D4 up to A4: on the A course (A5 to A12), not up the D course from the open string.
            val top = inst.courses.lastIndex
            assertEquals(listOf(top - 1 to 5, top - 1 to 12), SongAnalyzer.fingering(listOf(62, 69), listOf(0.0, 0.3), inst, listOf(false, true), listOf(false, true)))
            // A hammer-on from an open string and pull-offs down to one are fine: D4 E4 F4 E4 D4 on the D course.
            val hp = listOf(62, 64, 65, 64, 62)
            assertEquals(listOf(0, 2, 3, 2, 0).map { top to it }, SongAnalyzer.fingering(hp, timed(hp, 0.18), inst, listOf(false, true, false, true, true)))
        }
    }

    @Test
    fun lowNotesTakeTheBassCoursesOnlyWhenNeeded() {
        val l = typical.getValue("ousak down to G3")
        for (inst in Instrument.entries) {
            val c = cells(l, inst)
            l.midis.forEachIndexed { i, m ->
                if (m >= 57) assertTrue(c[i].first >= inst.courses.size - 2, "$inst note $i ($m) on the top two: $c")
                else assertEquals(inst.courses.size - 3, c[i].first, "$inst note $i ($m) on the next course down: $c")
            }
        }
        // A low passage in Rast on C3: C D E only on C, F and G on F, never C for what F reaches.
        val low = listOf(48, 50, 52, 53, 55, 57, 59, 60, 59, 57, 55, 53, 52, 50, 48)
        val c = SongAnalyzer.fingering(low, timed(low, 0.25), tetra)
        low.forEachIndexed { i, m -> if (m >= 53) assertTrue(c[i].first >= 1, "note $i ($m) off the C course: $c") }
        assertTrue(c.filter { it.first == 0 }.size == 6, "only C3 D3 E3 on the C course: $c")
        // A short low figure around F3: what F reaches stays on F (open and low frets), not up the C course.
        for (fig in listOf(listOf(52, 53, 54, 52, 48, 48), listOf(52, 53, 55, 53, 52, 48))) {
            val f = SongAnalyzer.fingering(fig, timed(fig, 0.2), tetra)
            fig.forEachIndexed { i, m -> if (m >= 53) assertEquals(1, f[i].first, "note $i ($m) on F: $f") }
        }
    }

    private fun song(midis: List<Int>, starts: List<Double>, arts: List<Articulation>, inst: Instrument = tetra): SongAnalysis {
        val cells = FingeringMetrics.fingering(midis, starts, inst, arts)
        return SongAnalysis("id", "t", inst, midis.indices.map { i ->
            SongNote(starts[i], starts[i] + 0.15, midis[i], cells[i].first, cells[i].second, arts[i])
        }, Dromos.HITZAZ, 2)
    }

    @Test
    fun theTrichordoPlaysTheSameSong() {
        // A tetrachordo song reaching below the trichordo (C3, C#3), with hammers, pulls, a slide and tremolo.
        val midis = listOf(48, 50, 49, 50, 53, 55, 57, 62, 64, 62, 66, 67, 69, 74, 72, 70, 69, 67, 66, 62, 60, 48)
        val arts = arts("..p.....hp...s.....t..")
        val starts = timed(midis, 0.2, mapOf(6 to 0.6))
        val t = song(midis, starts, arts).forInstrument(tri)
        assertEquals(tri, t.instrument)
        assertEquals(arts, t.notes.map { it.articulation })
        // The first phrase, which starts on C3 and moves by step, goes up an octave whole (the pull-off from D4 to C#4
        // stays one); the last note, an octave leap below the rest, goes up alone; the rest stays where it was.
        assertEquals(midis.take(7).map { it + 12 } + midis.subList(7, 21) + 60, t.notes.map { it.midi })
        val r = FingeringMetrics.of(t)
        assertEquals(0, r.invalid + r.legatoBroken + r.legatoForced + r.openSlides, "$r ${t.notes}")
        assertTrue(r.topTwoInRange >= 0.85, "$r ${t.notes}")
        assertTrue(t.notes.all { it.course >= 1 }, "from C4 up all on A and D: ${t.notes}")
        // And back: the same notes on the tetrachordo, every legato note still on its course.
        val back = t.forInstrument(tetra)
        assertEquals(t.notes.map { it.midi }, back.notes.map { it.midi })
        val b = FingeringMetrics.of(back)
        assertEquals(0, b.invalid + b.legatoBroken + b.legatoForced + b.openSlides, "$b ${back.notes}")
    }

    @Test
    fun legatoKeepsItsDirectionWhenTheTuneMovesAnOctave() {
        // C3 D3 (hammer) C3 (pull) D3 (hammer) F3: the trichordo plays it all an octave up, hammers rising, pulls falling.
        val midis = listOf(48, 50, 48, 50, 53)
        val arts = arts(".hph.")
        val t = song(midis, timed(midis, 0.15), arts).forInstrument(tri)
        assertEquals(listOf(60, 62, 60, 62, 65), t.notes.map { it.midi })
        assertEquals(arts, t.notes.map { it.articulation })
        for (i in 1 until 4) assertEquals(t.notes[0].course, t.notes[i].course, "${t.notes}")
        // A hammered run wider than the trichordo can't move whole: its notes move one by one, so C3 goes up to C4
        // and F3 stays, and the hammer-on from one to the other (now falling) is picked instead.
        val wide = listOf(48, 53, 58, 63, 68, 73, 78, 83, 88)
        val w = song(wide, timed(wide, 0.15), arts(".hhhhhhhh")).forInstrument(tri)
        assertEquals(listOf(60) + wide.drop(1), w.notes.map { it.midi })
        assertEquals(arts("..hhhhhhh"), w.notes.map { it.articulation })
    }

    @Test
    fun everyPositionIsOnTheNeck() {
        val rnd = java.util.Random(7)
        for (inst in Instrument.entries) repeat(400) { k ->
            val n = 1 + rnd.nextInt(60)
            var m = 40 + rnd.nextInt(60)
            val midis = List(n) { m = (m + rnd.nextInt(15) - 7).coerceIn(30, 100); m }
            var t = 0.0
            val starts = List(n) { val s = t; t += 0.05 + rnd.nextDouble() * 0.6; s }
            val density = if (k % 2 == 0) 0.25 else 0.8 // some lines almost all legato
            val legato = List(n) { rnd.nextDouble() < density }
            val slide = List(n) { legato[it] && rnd.nextInt(3) == 0 }
            val cells = SongAnalyzer.fingering(midis, starts, inst, legato, slide)
            assertEquals(n, cells.size)
            val low = inst.courses.first().openMidi
            val high = inst.courses.last().openMidi + inst.frets
            cells.forEachIndexed { i, (c, f) ->
                assertTrue(c in inst.courses.indices && f in 0..inst.frets, "$inst $c/$f")
                val sounds = inst.midiAt(c, f)
                if (midis[i] in low..high) assertEquals(midis[i], sounds) else assertEquals(0, Math.floorMod(sounds - midis[i], 12))
                // Legato always stays on the course when that course has the note.
                if (i > 0 && legato[i] && sounds - inst.courses[cells[i - 1].first].openMidi in 0..inst.frets) assertEquals(cells[i - 1].first, c)
            }
        }
        assertEquals(emptyList(), SongAnalyzer.fingering(emptyList(), emptyList(), tetra))
    }

    @Test
    fun theHandMovesUpTheNeckAtThePause() {
        val l = typical.getValue("two phrases an octave apart")
        for (inst in Instrument.entries) {
            val c = cells(l, inst)
            assertTrue(c.all { it.first >= inst.courses.size - 2 }, "$inst $c")
            assertTrue(c.take(9).all { it.second <= 8 }, "$inst the first phrase low on the neck: $c")
            assertTrue(c.drop(9).all { it.second >= 10 }, "$inst the second phrase an octave up the neck: $c")
            assertEquals(0, FingeringMetrics.of(l.midis, l.starts, c, inst).spanViolations, "$inst $c")
        }
    }

    // Made-up phrases in the way a real player fingers a tune: up and down the D course, between A and D in one
    // position where the tune dips below D4, the open D ringing under it. A player fingers a tune the same way
    // whatever its tempo, so each runs at steps from a fast sixteenth-note run to slow quarter notes.
    private val tempos = listOf(0.13, 0.2, 0.3, 0.4, 0.5)
    private val ousakClimb = listOf(62, 63, 65, 67, 69, 70, 72, 74, 74, 72, 70, 69, 67, 65, 63, 62) // D4 to D5 and back
    private val thirds = listOf(59, 62, 60, 64, 62, 65, 64, 67, 65, 64, 62, 60, 59) // B3 D4 C4 E4 … up in thirds to G4 and back
    private val overTheOpenD = listOf(62, 69, 62, 70, 62, 72, 62, 70, 62, 69, 62, 67, 62, 65, 62) // a tune over the open D
    private val hitzazDown = listOf(69, 70, 72, 70, 69, 67, 66, 63, 62, 63, 66, 67, 66, 63, 62) // around B♭4, then down to D4
    private val hitzazOnA = listOf(57, 58, 61, 62, 64, 65, 67, 69, 67, 65, 64, 62, 61, 58, 57) // A3 to A4 and back
    private val rastOnG = listOf(55, 57, 59, 60, 62, 64, 66, 67, 66, 64, 62, 60, 59, 57, 55) // G3 to G4 and back
    private val closingOnD = listOf(64, 62, 64, 65, 67, 65, 64, 62, 60, 62, 64, 62) // dips to C4, ends on D4
    private val openingOnD = listOf(62, 63, 65, 63, 62, 60, 58, 57, 58, 60, 62) // starts on D4, down to A3

    private fun crossings(c: List<Pair<Int, Int>>) = c.zipWithNext().count { (a, b) -> a.first != b.first }

    @Test
    fun aPhraseClimbsTheDCourseToTheTwelfthFretAndBack() {
        for (inst in Instrument.entries) for (step in tempos) {
            val c = SongAnalyzer.fingering(ousakClimb, timed(ousakClimb, step), inst)
            val top = inst.courses.lastIndex
            // One string from the open D up to D5 at the twelfth fret and back, the hand shifting along it.
            assertTrue(c.all { it.first == top }, "$inst $step: $c")
            assertEquals(listOf(0, 12, 0), listOf(c.first().second, c.maxOf { it.second }, c.last().second), "$inst $step: $c")
            val peak = c.indexOfFirst { it.second == 12 }
            assertTrue(c.take(peak + 1).zipWithNext().all { (a, b) -> b.second >= a.second }, "$inst $step up: $c")
            assertTrue(c.drop(peak).zipWithNext().all { (a, b) -> b.second <= a.second }, "$inst $step down: $c")
        }
    }

    @Test
    fun aPhraseBelowD4MovesBetweenAAndDInOnePosition() {
        for (inst in Instrument.entries) for (step in tempos) {
            val starts = timed(thirds, step)
            val c = SongAnalyzer.fingering(thirds, starts, inst)
            val top = inst.courses.lastIndex
            // B3 and C4 on A, E4 to G4 on D, all under one hand: no shift, and across only where the tune passes D4,
            // however slow the tune (when a shift costs little, climbing the A course is still not how it's played).
            thirds.forEachIndexed { i, m ->
                if (m < 62) assertEquals(top - 1, c[i].first, "$inst $step note $i: $c")
                if (m > 62) assertEquals(top, c[i].first, "$inst $step note $i: $c")
            }
            val fretted = c.map { it.second }.filter { it > 0 }
            assertTrue(fretted.max() - fretted.min() <= 3, "$inst $step one hand position: $c")
            assertEquals(0.0, FingeringMetrics.of(thirds, starts, c, inst).shiftsPerPhrase, "$inst $step $c")
            assertTrue(crossings(c) <= 2, "$inst $step $c")
        }
    }

    @Test
    fun aScaleFromA3StaysInTheLowPositionAtAnyTempo() {
        // A Hitzaz scale from A3 and a Rast one from G3: the A course in the first position, the D course above it,
        // not the whole scale up the A course to the tenth or twelfth fret.
        for (inst in Instrument.entries) for (step in tempos) for (line in listOf(hitzazOnA, rastOnG)) {
            val c = SongAnalyzer.fingering(line, timed(line, step), inst)
            val top = inst.courses.lastIndex
            line.forEachIndexed { i, m -> if (m >= 64) assertEquals(top, c[i].first, "$inst $step note $i: $c") }
            assertTrue(c.filter { it.first == top - 1 }.all { it.second <= 5 }, "$inst $step: $c")
            assertTrue(c.all { it.second <= 7 }, "$inst $step: $c")
        }
    }

    @Test
    fun aPhraseAroundD4StartsAndEndsOnTheOpenD() {
        for (inst in Instrument.entries) for (step in tempos) {
            val top = inst.courses.lastIndex
            val end = SongAnalyzer.fingering(closingOnD, timed(closingOnD, step), inst)
            assertEquals(top to 0, end.last(), "$inst $step: $end")
            val start = SongAnalyzer.fingering(openingOnD, timed(openingOnD, step), inst)
            assertEquals(listOf(0, 1, 3, 1, 0).map { top to it }, start.take(5), "$inst $step: $start")
        }
    }

    @Test
    fun aFastLineTakesTheOpenDRatherThanLeaping() {
        // Down to C4 or A3 on A, the open D, then up the neck: the open string frees the hand, so no picked jump
        // (E4 D4 C4 D4 G4 D5 all on A, say, with a leap from the fifth fret to the tenth).
        val fast = listOf(listOf(64, 62, 60, 62, 67, 74), listOf(60, 59, 57, 62, 69, 71, 72))
        for (inst in Instrument.entries) for (step in listOf(0.11, 0.13, 0.15)) for (line in fast) {
            val starts = timed(line, step)
            val c = SongAnalyzer.fingering(line, starts, inst)
            assertEquals(0, FingeringMetrics.of(line, starts, c, inst).spanViolations, "$inst $step: $c")
            assertTrue(c.any { it == inst.courses.lastIndex to 0 }, "$inst $step: $c")
        }
    }

    @Test
    fun theOpenDRingsUnderATuneOnTheSameCourse() {
        for (inst in Instrument.entries) for (step in tempos) {
            val c = SongAnalyzer.fingering(overTheOpenD, timed(overTheOpenD, step), inst)
            val top = inst.courses.lastIndex
            overTheOpenD.forEachIndexed { i, m -> if (m == 62) assertEquals(top to 0, c[i], "$inst $step note $i: $c") }
            assertEquals(0, crossings(c), "$inst $step: $c")
        }
    }

    @Test
    fun theHandComesBackDownAlongTheDCourse() {
        // From around B♭4 down to D4 the hand shifts down the D course; it doesn't cross to A to stay up the neck.
        for (inst in Instrument.entries) for (step in tempos) {
            val c = SongAnalyzer.fingering(hitzazDown, timed(hitzazDown, step), inst)
            val top = inst.courses.lastIndex
            assertTrue(c.all { it.first == top }, "$inst $step: $c")
            assertEquals(top to 0, c.last(), "$inst $step: $c")
        }
    }
}

/**
 * The fingering of the synthesized evaluation songs (see [EvalTest]), on both bouzoukis through
 * [SongAnalysis.forInstrument]: the Kotlin line, with legato added the way a player might (the analysis
 * marks every note picked), and the ground-truth line with its own articulations where the export has
 * them. Prints the metrics of each song; only runs where EVAL_DIR points at the exported songs.
 */
class FingeringEvalTest {
    private val dir = System.getenv("EVAL_DIR")?.let { File(it) }

    private fun floats(f: File, cols: Int): Array<FloatArray> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return Array(b.remaining() / cols) { FloatArray(cols).also { row -> b.get(row) } }
    }

    /** The ground-truth line (start,end,midi,kind,articulation rows), or null where the file has no articulations. */
    private fun truth(song: File): SongAnalysis? {
        val rows = File(song, "gt.csv").readLines().filter { it.isNotBlank() }.map { it.split(",") }
        if (rows.isEmpty() || rows.any { it.size < 5 }) return null
        val notes = rows.map { SongNote(it[0].toDouble(), it[1].toDouble(), it[2].toInt(), 0, 0, Articulation.valueOf(it[4])) }
        return SongAnalysis(song.name, song.name, Instrument.TETRACHORDO, notes.sortedBy { it.start }, null, 2)
    }

    /**
     * [a] with every second quick step on one course made legato: a tone or semitone a hammer-on or
     * pull-off, a third or a fourth between fretted notes a slide.
     */
    private fun withLegato(a: SongAnalysis): SongAnalysis {
        var every = false
        return a.copy(notes = a.notes.mapIndexed { i, n ->
            val p = a.notes.getOrNull(i - 1) ?: return@mapIndexed n
            val step = n.midi - p.midi
            val art = when {
                p.course != n.course || n.start - p.start > 0.3 -> return@mapIndexed n
                abs(step) in 1..2 -> if (step > 0) Articulation.HAMMER else Articulation.PULL
                abs(step) in 3..5 && p.fret > 0 && n.fret > 0 -> Articulation.SLIDE
                else -> return@mapIndexed n
            }
            every = !every
            if (every) n.copy(articulation = art) else n
        })
    }

    private class Total { var notes = 0; var onTop = 0.0; var leaps = 0; var legato = 0 }

    @Test
    fun evalSongsAreFingeredOnTheTopCourses() {
        val d = dir ?: return
        val totals = LinkedHashMap<String, Total>()
        val problems = ArrayList<String>()
        for (song in d.listFiles()!!.filter { File(it, "gt.csv").exists() }.sortedBy { it.name }) {
            val act = BasicPitch.Activations(floats(File(song, "frames.f32"), 88), floats(File(song, "onsets.f32"), 88))
            val line = withLegato(SongAnalyzer.build(song.name, song.name, SongAnalyzer.candidates(act), Instrument.TETRACHORDO, act))
            for ((kind, src) in listOfNotNull("line" to line, truth(song)?.let { "truth" to it })) for (inst in Instrument.entries) {
                val s = src.forInstrument(inst)
                val r = FingeringMetrics.of(s)
                println("%-28s %-5s %-11s %s".format(song.name, kind, inst, r))
                // A low passage (G3 slid to A3, say) rightly takes the course below, so a song may have a few.
                if (r.invalid + r.legatoBroken + r.openSlides > 0 || r.topTwoInRange < 0.85) problems += "${song.name} $kind $inst: $r"
                val t = totals.getOrPut("$kind on the ${inst.name.lowercase()}") { Total() }
                t.notes += r.notes; t.onTop += r.topTwoInRange * r.notes; t.leaps += r.spanViolations; t.legato += r.legato
            }
        }
        for ((k, t) in totals) {
            println("all songs, %-25s %.1f%% of the notes the top two courses can play on them, %d legato, %d fast leaps in %d notes"
                .format("$k:", 100 * t.onTop / t.notes, t.legato, t.leaps, t.notes))
            if (t.onTop < 0.95 * t.notes) problems += "$k: only %.1f%% on the top two".format(100 * t.onTop / t.notes)
            // A fast picked jump beyond the hand's reach can't be played cleanly: only a few, where the tune itself
            // leaps (an octave in a fast run, often an octave slip of the transcription) and nothing else fits.
            if (t.leaps > t.notes * 0.0075) problems += "$k: ${t.leaps} fast leaps in ${t.notes} notes"
        }
        assertEquals(emptyList(), problems)
    }
}
