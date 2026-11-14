package com.bouzouki.core.song

import com.bouzouki.core.Course
import com.bouzouki.core.Instrument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuningsAndEnginesTest {
    @Test
    fun theTwoBouzoukiaAndCustomTunings() {
        assertEquals("C3/C4 F3/F4 A3 D4", Instrument.TETRACHORDO.tuning)
        assertEquals("D3/D4 A3 D4", Instrument.TRICHORDO.tuning)
        assertEquals(Instrument.TETRACHORDO, Instrument.valueOf("TETRACHORDO"))
        // A tuning as a player writes it, kept by its name; the standard ones come back as themselves.
        val gdad = Instrument.parseTuning("G2/G3 D3/D4 A3 D4", 24)!!
        assertEquals(listOf(Course(43, true), Course(50, true), Course(57, false), Course(62, false)), gdad.courses)
        assertEquals(gdad, Instrument.valueOf(gdad.name))
        assertEquals(24, Instrument.valueOf(gdad.name).frets)
        assertEquals(-1, gdad.ordinal)
        assertEquals(Instrument.TRICHORDO, Instrument.parseTuning("D3/D4 A3 D4", 26))
        assertEquals(46, Instrument.midiOf("Bb2"))
        assertNull(Instrument.parseTuning("C3/D4 F3", 20)) // an octave pair is an octave apart
        assertNull(Instrument.parseTuning("H3", 20))
        // A song keeps its tuning through the cache.
        val song = SongAnalysis("id", "t", gdad, listOf(SongNote(0.0, 0.5, 45, 0, 2)), null, 2)
        assertEquals(gdad, SongAnalysis.fromJson(song.toJson())!!.instrument)
    }

    @Test
    fun anyTuningIsFingeredWithinItsRange() {
        // Five courses, 20 frets: every note lands on a course and fret that sound it.
        val five = Instrument.parseTuning("C3/C4 G3/G4 D4 A4 E5", 20)!!
        val midis = listOf(48, 55, 62, 69, 76, 79, 83, 90, 60, 52)
        for (engine in Fingering.Engine.entries) {
            Fingering.engine = engine
            val cells = SongAnalyzer.fingering(midis, midis.indices.map { it * 0.25 }, five)
            cells.forEachIndexed { i, (c, f) -> assertTrue(f in 0..20 && five.midiAt(c, f) == midis[i], "$engine: note $i at $c/$f") }
        }
        Fingering.engine = Fingering.Engine.HMM
    }

    @Test
    fun everyWeightCanBeSetByName() {
        val w = Fingering.TETRACHORDO
        val text = w.text()
        assertTrue("cross.2.3 = 0.65" in text && "span = 4" in text, text)
        // Read back unchanged; a changed value changes only that weight.
        assertEquals(w.toMap(), w.with(Fingering.Weights.parse(text)).toMap())
        val changed = w.with(Fingering.Weights.parse("# my bouzouki\nshift = 0.8\ncourse.0 = 5\nnonsense = 3"))
        assertEquals(0.8, changed.shift)
        assertEquals(5.0, changed.course[0])
        assertEquals(w.toMap().filterKeys { it != "shift" && it != "course.0" }, changed.toMap().filterKeys { it != "shift" && it != "course.0" })
        // The player's own weights are what the songs are fingered with.
        Fingering.custom = mapOf(4 to changed)
        assertEquals(0.8, Fingering.weights(Instrument.TETRACHORDO).shift)
        Fingering.custom = emptyMap()
        val t = TuttutFingering.Weights().with(mapOf("b" to 0.5, "course.3" to 0.1), 4)
        assertEquals(0.5, t.b)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.1), t.course!!.toList())
    }

    @Test
    fun tuttutMovesAlongACourseAndKeepsLegatoOnIt() {
        // A run up the D course: tuttut keeps it there (crossing costs, moving along a course costs little).
        val run = (62..74).toList()
        val cells = TuttutFingering.place(run, Instrument.TETRACHORDO)
        assertTrue(cells.count { it.first == 3 } >= run.size - 2, "$cells")
        // A hammer-on stays on the course of the note before.
        val legato = TuttutFingering.place(listOf(57, 59), Instrument.TETRACHORDO, listOf(false, true))
        assertEquals(legato[0].first, legato[1].first)
    }
}
