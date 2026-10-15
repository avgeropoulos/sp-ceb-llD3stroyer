package com.bouzouki.core.song

import com.bouzouki.core.Dromos
import com.bouzouki.core.Instrument
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    private class Line(val midis: List<Int>, val starts: List<Double>, val legato: List<Boolean>? = null)

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
            Line(it, timed(it, 0.2), listOf(false, true, false, true, true, false, false, true, false, true, false, true, false, false, true, true))
        },
        // From a synthesized evaluation song: the fingering before this one played most of it on F and C around fret 11.
        "hitzaz phrase in E" to Line(
            listOf(64, 62, 65, 64, 62, 64, 59, 60, 59, 60, 64, 65, 64, 65, 68, 69, 68, 60, 61, 59),
            listOf(0.0, 0.249, 0.499, 0.774, 1.009, 2.251, 2.377, 2.514, 2.641, 2.749, 2.879, 2.989, 3.133, 3.256, 3.376, 3.511, 3.641, 4.254, 4.504, 5.26),
        ),
    )

    private fun cells(l: Line, inst: Instrument) = SongAnalyzer.fingering(l.midis, l.starts, inst, l.legato)

    @Test
    fun typicalLinesStayOnTheTopTwoCourses() {
        for (inst in Instrument.entries) for ((name, l) in typical) {
            val r = FingeringMetrics.of(l.midis, l.starts, inst, l.legato)
            assertEquals(0, r.invalid, "$inst $name: $r")
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
    fun aMinorePhraseSitsUpTheNeckOnOneHandPosition() {
        val l = typical.getValue("minore phrase")
        for (inst in Instrument.entries) {
            val c = cells(l, inst)
            assertTrue(c.all { it.first == inst.courses.lastIndex }, "$inst $c")
            assertTrue(c.all { it.second in 6..14 }, "$inst $c") // around frets 7-12, not open position on the bass courses
        }
    }

    @Test
    fun legatoNotesStayOnTheirCourse() {
        val l = typical.getValue("legato phrase")
        for (inst in Instrument.entries) {
            val c = cells(l, inst)
            for (i in 1 until c.size) if (l.legato!![i]) assertEquals(c[i - 1].first, c[i].first, "$inst note $i: $c")
            assertEquals(7, c[1].second - c[0].second, "$inst slide A4 to E5 along one string: $c")
            assertEquals(inst.courses.lastIndex to 0, c.last(), "$inst pull-offs F4 E4 D4 down to the open D: $c")
        }
        // A legato note the previous course can't play still gets a valid place.
        val odd = SongAnalyzer.fingering(listOf(64, 52), listOf(0.0, 0.2), tetra, listOf(false, true))
        assertEquals(52, tetra.midiAt(odd[1].first, odd[1].second))
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
    }

    @Test
    fun theTrichordoPlaysTheSameSong() {
        // A tetrachordo song reaching below the trichordo (C3, C#3), with hammers, pulls, a slide and tremolo.
        val midis = listOf(48, 50, 49, 50, 53, 55, 57, 62, 64, 62, 66, 67, 69, 74, 72, 70, 69, 67, 66, 62, 60, 48)
        val arts = midis.indices.map { i ->
            when (i) { 2, 9 -> Articulation.PULL; 8 -> Articulation.HAMMER; 13 -> Articulation.SLIDE; 19 -> Articulation.TREMOLO; else -> Articulation.PICK }
        }
        val starts = timed(midis, 0.2, mapOf(6 to 0.6))
        val cells = SongAnalyzer.fingering(midis, starts, tetra, arts.map { it.isLegato })
        val song = SongAnalysis("id", "t", tetra, midis.indices.map { i ->
            SongNote(starts[i], starts[i] + 0.2, midis[i], cells[i].first, cells[i].second, arts[i])
        }, Dromos.HITZAZ, 2)
        val t = song.forInstrument(tri)
        assertEquals(tri, t.instrument)
        t.notes.forEachIndexed { i, n ->
            assertTrue(n.midi == midis[i] || n.midi == midis[i] + 12, "note $i: ${n.midi} from ${midis[i]}")
            assertEquals(n.midi, tri.midiAt(n.course, n.fret), "note $i")
            assertEquals(arts[i], n.articulation)
            if (arts[i].isLegato) assertEquals(t.notes[i - 1].course, n.course, "legato note $i: ${t.notes}")
        }
        assertEquals(listOf(60, 50, 61), t.notes.take(3).map { it.midi }) // C3 and C#3 up an octave, D3 stays
        val r = FingeringMetrics.of(t.notes.map { it.midi }, starts, t.notes.map { it.course to it.fret }, tri, arts.map { it.isLegato })
        assertTrue(r.topTwoInRange >= 0.85, "$r ${t.notes}")
        // Picked notes below A3 on the trichordo's low D course, from D4 up on A and D.
        t.notes.forEachIndexed { i, n ->
            if (!arts[i].isLegato && n.midi < 57) assertEquals(0, n.course, "$n")
            if (!arts[i].isLegato && n.midi >= 62) assertTrue(n.course >= 1, "$n")
        }
        // And back: every note on the tetrachordo again, with its articulation.
        val back = t.forInstrument(tetra)
        back.notes.forEachIndexed { i, n ->
            assertEquals(n.midi, tetra.midiAt(n.course, n.fret))
            if (arts[i].isLegato) assertEquals(back.notes[i - 1].course, n.course)
        }
    }

    @Test
    fun everyPositionIsOnTheNeck() {
        val rnd = java.util.Random(7)
        for (inst in Instrument.entries) repeat(40) {
            val n = 1 + rnd.nextInt(60)
            var m = 40 + rnd.nextInt(60)
            val midis = List(n) { m = (m + rnd.nextInt(15) - 7).coerceIn(30, 100); m }
            var t = 0.0
            val starts = List(n) { val s = t; t += 0.05 + rnd.nextDouble() * 0.6; s }
            val legato = List(n) { rnd.nextInt(4) == 0 }
            val cells = SongAnalyzer.fingering(midis, starts, inst, legato)
            assertEquals(n, cells.size)
            val low = inst.courses.first().openMidi
            val high = inst.courses.last().openMidi + inst.frets
            cells.forEachIndexed { i, (c, f) ->
                assertTrue(c in inst.courses.indices && f in 0..inst.frets, "$inst $c/$f")
                val sounds = inst.midiAt(c, f)
                if (midis[i] in low..high) assertEquals(midis[i], sounds) else assertEquals(0, Math.floorMod(sounds - midis[i], 12))
                // Legato stays on the course whenever that course has the note.
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
}

/**
 * The fingering of the synthesized evaluation songs' lines (see [EvalTest]): the Kotlin line on the
 * tetrachordo and, through [SongAnalysis.forInstrument], on the trichordo. Prints the metrics of each
 * song; only runs where EVAL_DIR points at the exported songs.
 */
class FingeringEvalTest {
    private val dir = System.getenv("EVAL_DIR")?.let { File(it) }

    private fun floats(f: File, cols: Int): Array<FloatArray> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return Array(b.remaining() / cols) { FloatArray(cols).also { row -> b.get(row) } }
    }

    @Test
    fun evalSongsAreFingeredOnTheTopCourses() {
        val d = dir ?: return
        var notes = 0
        var onTop = 0.0
        for (song in d.listFiles()!!.filter { File(it, "gt.csv").exists() }.sortedBy { it.name }) {
            val act = BasicPitch.Activations(floats(File(song, "frames.f32"), 88), floats(File(song, "onsets.f32"), 88))
            val a = SongAnalyzer.build(song.name, song.name, SongAnalyzer.candidates(act), Instrument.TETRACHORDO, act)
            for (s in listOf(a, a.forInstrument(Instrument.TRICHORDO))) {
                val r = FingeringMetrics.of(s.notes.map { it.midi }, s.notes.map { it.start }, s.notes.map { it.course to it.fret }, s.instrument,
                    s.notes.map { it.articulation.isLegato })
                println("%-28s %-11s %s".format(song.name, s.instrument, r))
                assertEquals(0, r.invalid + r.legatoBroken, "${song.name} ${s.instrument}: $r")
                assertTrue(r.topTwoInRange >= 0.9, "${song.name} ${s.instrument}: $r")
                notes += r.notes
                onTop += r.topTwoInRange * r.notes
            }
        }
        println("all songs: %.1f%% of the notes the top two courses can play are on them".format(100 * onTop / notes))
    }
}
