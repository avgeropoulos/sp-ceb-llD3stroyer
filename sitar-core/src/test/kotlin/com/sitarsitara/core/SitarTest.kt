package com.sitarsitara.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SA = 61 // C#4

class SargamTest {
    @Test fun saSitsWhereASitarIsTuned() {
        assertEquals(61, Sargam.saMidi(1))
        assertEquals(62, Sargam.saMidi(2))
        assertTrue((0 until 12).all { Sargam.saMidi(it) in 55..66 })
    }

    @Test fun swarasAreNamedFromSa() {
        assertEquals("SrRgGmMPdDnN".map { it.toString() }, (0 until 12).map { Sargam.swara(SA + it, SA).text() })
        assertEquals("S'", Sargam.swara(SA + 12, SA).text())
        assertEquals("N,", Sargam.swara(SA - 1, SA).text())
        assertEquals("r''", Sargam.swara(SA + 25, SA).text())
        assertEquals("N,,", Sargam.swara(SA - 13, SA).text())
        assertEquals("tivra Ma", Sargam.swara(SA + 6, SA).name)
        assertEquals("komal Ni (mandra)", Sargam.swara(SA - 2, SA).name)
    }

    @Test fun devanagariMarksKomalTivraAndOctaves() {
        assertEquals("सा", Swara(0, 0).text(Script.DEVANAGARI))
        assertEquals("रे॒", Swara(1, 0).text(Script.DEVANAGARI)) // komal Re: line below
        assertEquals("म॑", Swara(6, 0).text(Script.DEVANAGARI)) // tivra Ma: stroke above
        assertEquals("सां", Swara(0, 1).text(Script.DEVANAGARI)) // taar Sa: dot above
        assertEquals("ऩि", Swara(11, -1).text(Script.DEVANAGARI)) // mandra Ni: dot below
        // Marks sit on their letter and take no column of their own.
        assertEquals(1, TaalGrid.width(Swara(8, 1).text(Script.DEVANAGARI)))
        assertEquals(2, TaalGrid.width("S'"))
    }

    @Test fun notesBelowTheOpenMainStringGoToTheLowerStrings() {
        assertEquals(SitarString.BAJ, Sargam.string(SA - 7, SA)) // mandra Ma: the open main string
        assertEquals(SitarString.BAJ, Sargam.string(SA + 19, SA))
        assertEquals(SitarString.JOD, Sargam.string(SA - 8, SA))
        assertEquals(SitarString.JOD, Sargam.string(SA - 12, SA))
        assertEquals(SitarString.PANCHAM, Sargam.string(SA - 13, SA))
        assertEquals(SitarString.KHARAJ, Sargam.string(SA - 24, SA))
        assertNull(Sargam.string(SA - 25, SA))
    }

    @Test fun fitToRangeMovesByOctaves() {
        assertEquals(SA - 18 to true, Sargam.fitToRange(SA - 30, SA))
        assertEquals(SA + 17 to true, Sargam.fitToRange(SA + 29, SA))
        assertEquals(SA + 5 to false, Sargam.fitToRange(SA + 5, SA))
    }

    @Test fun taalsAddUp() {
        assertEquals(16, Taal.TEENTAAL.matras)
        assertEquals(listOf(3, 2, 2), Taal.RUPAK.vibhags)
        for (t in Taal.entries) { assertEquals(t.vibhags.size, t.marks.size); assertEquals("X", t.marks[0]) }
        assertEquals("Jhaptaal (10 matras, 2+3+2+3)", Taal.JHAPTAAL.label)
        assertEquals(Taal.TEENTAAL, Taal.byName("nope"))
    }
}

class ThaatTest {
    private fun phrase(degrees: List<Int>, sa: Int = SA) = degrees.map { sa + it } to degrees.map { 0.25 }

    @Test fun bhairaviOnD() {
        val (m, d) = phrase(listOf(0, 1, 3, 5, 7, 8, 10, 12, 10, 8, 7, 5, 3, 1, 0), sa = 62)
        val top = Thaat.detect(m, d)[0]
        assertEquals("D", top.sa)
        assertEquals(Scale.BHAIRAVI, top.scale)
        assertTrue("Bhairavi" in top.scale.ragas)
    }

    @Test fun fiveNoteRagaBeatsTheThaatsThatContainIt() {
        val (m, d) = phrase(listOf(0, 2, 4, 7, 9, 12, 9, 7, 4, 2, 0, 2, 0))
        val top = Thaat.detect(m, d)[0]
        assertEquals("C#" to Scale.BHUPALI, top.sa to top.scale)
    }

    @Test fun sevenNoteScaleIsNotMistakenForAFiveNoteRaga() {
        // Yaman leaning hard on Sa, Re, Ga and Pa is still not Hamsadhwani.
        val (m, d) = phrase(listOf(0, 2, 4, 6, 7, 9, 11, 12, 11, 9, 7, 6, 4, 2, 0, 2, 4, 4, 7, 2, 0))
        assertEquals(Scale.KALYAN, Thaat.detect(m, d)[0].scale)
    }

    @Test fun givenSaOnlyTheScaleIsGuessed() {
        // These notes are Bhupali on C#, or Malkauns on F. Told Sa, there's no choice.
        val (m, d) = phrase(listOf(0, 2, 4, 7, 9, 7, 4, 2, 0))
        val guesses = Thaat.detect(m, d, saPc = 5)
        assertEquals(setOf("F"), guesses.map { it.sa }.toSet())
        assertEquals(Scale.MALKAUNS, guesses[0].scale)
    }

    @Test fun everyScaleStartsOnSa() {
        assertTrue(Scale.entries.all { it.degrees[0] == 0 })
        assertEquals(emptyList(), Thaat.detect(emptyList(), emptyList()))
    }
}

class ReadingTest {
    // Yaman with Sa on C#: up and down the scale, then Re pulled up to Ga, and fast strokes on Sa.
    private val yaman = listOf(0, 2, 4, 6, 7, 9, 11, 12, 11, 9, 7, 6, 4, 2, 0).mapIndexed { i, d ->
        LineNote(0.5 + i * 0.25, 0.74 + i * 0.25, SA + d)
    } + listOf(
        LineNote(4.25, 4.6, SA + 2), LineNote(4.6, 5.0, SA + 4, meend = true),
        LineNote(5.0, 6.0, SA, fast = true),
    )

    @Test fun saAndThaatAreWorkedOut() {
        val r = Reading.of(yaman)
        assertEquals("C#", r.saName)
        assertEquals(false, r.chosen)
        assertEquals(Scale.KALYAN, r.best!!.scale)
        assertEquals(listOf("S", "R", "G", "M", "P", "D", "N", "S'"), r.notes.take(8).map { it.text() })
        assertEquals(listOf("R", "~G", "S*"), r.notes.takeLast(3).map { it.text() })
        assertEquals("~ग", r.notes[16].text(Script.DEVANAGARI))
    }

    @Test fun saSetByThePlayer() {
        val r = Reading.of(yaman, saPc = 2)
        assertEquals(62, r.sa)
        assertTrue(r.chosen)
        assertEquals("N,", r.notes[0].text())
        assertTrue(r.guesses.all { it.sa == "D" })
    }

    @Test fun doubtfulNotesCanBeLeftOut() {
        val line = yaman + LineNote(7.0, 7.2, SA + 1, confidence = 0.1f)
        assertEquals(line.size, Reading.of(line).notes.size)
        assertEquals(yaman.size, Reading.of(line, minConfidence = 0.2f).notes.size)
    }

    @Test fun notesOutOfRangeAreFolded() {
        val r = Reading.of(listOf(LineNote(0.0, 1.0, SA), LineNote(1.0, 2.0, SA + 36)), saPc = 1)
        assertEquals(1, r.folded)
        assertEquals("S''", r.notes[1].text())
    }
}

class TaalGridTest {
    private val beats = (0 until 80).map { it * 0.5 } // 120 BPM

    private fun notes(vararg at: Pair<Double, Int>, meend: Set<Int> = emptySet(), fast: Set<Int> = emptySet()) =
        Reading.of(at.mapIndexed { i, (t, d) -> LineNote(t, t + 0.2, SA + d, meend = i in meend, fast = i in fast) }, saPc = 1).notes

    private fun row(lines: List<TaalGrid.Line>, n: List<SitarNote>, script: Script = Script.LATIN) =
        TaalGrid.text(lines, n, emptyList(), script).lines()

    @Test fun notesLandInTheirMatras() {
        // Sa on the first matra, Re and Ga sharing the second, Ma on the third.
        val n = notes(0.0 to 0, 0.5 to 2, 0.75 to 4, 1.0 to 5)
        val lines = TaalGrid.layout(n, Taal.TEENTAAL, beats, 120.0)
        assertEquals(1, lines.size)
        val text = row(lines, n)
        assertEquals("[0:00]", text[1])
        assertEquals(listOf("X", "2", "0", "3"), text[2].split(Regex("\\s+")))
        assertEquals("S RG m - | - - - - | - - - - | - - - - ||", text[3].split(Regex("\\s+")).joinToString(" "))
        // Each mark sits over the start of its vibhag.
        val bars = text[3].indices.filter { text[3][it] == '|' && text[3].getOrNull(it - 1) != '|' && text[3].getOrNull(it + 1) != '|' }
        assertEquals(listOf(0) + bars.take(3).map { it + 2 }, "X203".map { text[2].indexOf(it) })
    }

    @Test fun aMatraIsSplitOnlyAsFinelyAsItNeeds() {
        val n = notes(0.0 to 0, 0.5 to 2, 0.625 to 4, 1.0 to 5, 1.375 to 7)
        val line = TaalGrid.layout(n, Taal.TEENTAAL, beats, 120.0).single()
        assertEquals(listOf("S", "RG--", "m--P"), row(listOf(line), n)[3].split(Regex("\\s+")).take(3))
        val m = line.vibhags[0].matras
        assertEquals(listOf(1, 4, 4, 1), m.map { it.parts.size })
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5), m.map { it.time })
        assertEquals(listOf(0.5, 1.0, 1.5, 2.0), m.map { it.end })
    }

    @Test fun meendAndFastStrokesAreMarked() {
        val n = notes(0.0 to 2, 0.5 to 4, 1.0 to 12, meend = setOf(1), fast = setOf(2))
        val lines = TaalGrid.layout(n, Taal.TEENTAAL, beats, 120.0)
        assertEquals(listOf("R", "~G", "S'*"), row(lines, n)[3].split(Regex("\\s+")).take(3))
        assertEquals(listOf("रे", "~ग", "सां*"), row(lines, n, Script.DEVANAGARI)[3].split(Regex("\\s+")).take(3))
    }

    @Test fun theCountStartsAtTheFirstNote() {
        val n = notes(7.0 to 0, 7.5 to 2)
        val line = TaalGrid.layout(n, Taal.KEHERWA, beats, 120.0).first()
        assertEquals(7.0, line.start, 0.01)
        assertEquals(listOf("S", "R"), row(listOf(line), n)[3].split(Regex("\\s+")).take(2))
    }

    @Test fun shortTaalsGetTwoCyclesALine() {
        val n = notes(*listOf(0, 2, 4, 5, 7, 9, 11, 12, 11, 9, 7, 5, 4, 2).mapIndexed { i, d -> i * 0.5 to d }.toTypedArray())
        val line = TaalGrid.layout(n, Taal.RUPAK, beats, 120.0).single()
        val text = row(listOf(line), n)
        assertEquals(listOf("X", "2", "3", "X", "2", "3"), text[2].split(Regex("\\s+")))
        assertEquals("S R G | m P | D N || S' N D | P m | G R ||", text[3].split(Regex("\\s+")).joinToString(" "))
        assertEquals(listOf(true, false, false, true, false, false), line.vibhags.map { it.sam })
        assertEquals(7.0, line.end, 1e-9)
    }

    @Test fun longSilencesAreSkipped() {
        val n = notes(0.0 to 0, 30.0 to 2)
        val lines = TaalGrid.layout(n, Taal.KEHERWA, beats, 120.0)
        assertEquals(2, lines.size)
        // 30 s is 60 matras in: the fourth matra of the eighth cycle.
        assertEquals(28.0, lines[1].start, 0.01)
    }

    @Test fun noBeatsFallsBackToTheTempo() {
        val n = notes(1.0 to 0, 1.6 to 2)
        val line = TaalGrid.layout(n, Taal.KEHERWA, emptyList(), 100.0).single()
        assertEquals(1.0, line.start, 1e-9)
        assertEquals(1.6, line.matras[1].time, 1e-9)
    }
}

class GlideTest {
    private fun n(start: Double, end: Double, midi: Int, meend: Boolean = false, conf: Float = 1f) = LineNote(start, end, midi, meend, confidence = conf)

    @Test fun passingPitchesFoldIntoTheNoteTheGlideReaches() {
        // As the analysis hears Ga pulled up to Pa: a sliver of Ma, of tivra Ma, then Pa taken for a stroke.
        val heard = listOf(n(1.30, 1.705, 65), n(1.705, 1.733, 66, meend = true, conf = 0.1f), n(1.744, 1.768, 67, conf = 0.18f), n(1.78, 2.23, 68))
        val line = SitarAnalysis.foldGlides(heard)
        assertEquals(listOf(65, 68), line.map { it.midi })
        assertEquals(listOf(false, true), line.map { it.meend })
        assertEquals(1.78, line[1].start)
    }

    @Test fun aQuickRunOfClearStrokesStays() {
        val run = listOf(n(0.0, 0.07, 61), n(0.07, 0.14, 63), n(0.14, 0.21, 65), n(0.21, 0.5, 66))
        assertEquals(run, SitarAnalysis.foldGlides(run))
    }

    @Test fun aTurnIsNotAGlide() {
        // Up and straight back down (a kan, a grace note) is not on its way anywhere.
        val turn = listOf(n(0.0, 0.3, 61), n(0.3, 0.35, 63, meend = true, conf = 0.2f), n(0.35, 0.7, 61))
        assertEquals(turn, SitarAnalysis.foldGlides(turn))
    }

    @Test fun aGapMeansANewStroke() {
        val apart = listOf(n(0.0, 0.3, 61), n(0.4, 0.45, 63, conf = 0.2f), n(0.55, 0.9, 65))
        assertEquals(apart, SitarAnalysis.foldGlides(apart))
    }
}
