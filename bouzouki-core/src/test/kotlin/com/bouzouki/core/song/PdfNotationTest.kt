package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import com.bouzouki.core.song.TestScore.Line
import com.bouzouki.core.song.TestScore.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PdfTab reading sheet music (standard notation, no tab) through PdfNotation, on scores written here in the style of
 * Sibelius's and MuseScore's exports ([TestScore]). Every melody is made up for these tests.
 */
class PdfNotationTest {
    private val inst = Instrument.TETRACHORDO

    /** The melody's pitches as the tab plays them (its notes' course and fret on [tab]'s bouzouki). */
    private fun midis(tab: ParsedTab) = tab.notes.indices.map { tab.midi(it) }

    /** Every note's and every chord note's fret is on the bouzouki, and the stacks are on other courses than their melody note. */
    private fun assertPlayable(tab: ParsedTab) {
        for (n in tab.notes) {
            assertTrue(n.fret in 0..tab.instrument.frets && n.course in tab.instrument.courses.indices, "$n")
            for ((c, f) in n.stack) assertTrue(c != n.course && f in 0..tab.instrument.frets, "$n")
        }
    }

    // A melody in G major (one sharp), 4/4, over two systems: accidentals held through a bar, a grace note, a dotted
    // note, a triplet, ledger lines, a chord, a tie inside the system and one into the next, a whole-bar rest.
    private val gMajor = listOf(
        Line("F4/8 Fn4/8 F4/8 G4/8 A4/4 r/4 | gC5/8 B4/4. A4/8 3:G4/8 F4/8 E4/8 r/4 | D4/8 C4/8 D4/8 E4/8 E4/2~ | E4/4 D4+F#4+A4/4 G4/2~> |",
            key = 1, time = 4 to 4, tempo = 96),
        Line("G4/4 A4/8 B4/8 C5/16 D5/16 E5/16 F5/16 G5/4 | R | A5/1 |", key = 1, tieIn = true),
    )
    private val gMajorMidis = listOf(66, 65, 65, 67, 69, 72, 71, 69, 67, 66, 64, 62, 60, 62, 64, 64, 69, 67, 69, 71, 72, 74, 76, 78, 79, 81)
    private val gMajorColumns = listOf(0, 2, 4, 6, 8, 15, 16, 22, 24, 25, 27, 32, 34, 36, 38, 40, 52, 56, 68, 70, 72, 73, 74, 75, 76, 96)

    @Test
    fun readsASibeliusStyleScore() {
        val tab = PdfTab.read(TestScore(Style.OPUS).pdf(listOf(gMajor)), inst)
        assertEquals(gMajorMidis, midis(tab))
        assertEquals(gMajorColumns, tab.notes.map { it.column })
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 4, 4, 4, 4, 4, 4, 4, 6), tab.notes.map { it.bar })
        assertEquals(List(18) { 0 } + List(8) { 1 }, tab.notes.map { it.system })
        // The chord: A4 the melody, F#4 and D4 on other courses.
        val chord = tab.notes[16]
        assertEquals(listOf(66, 62), chord.stack.map { (c, f) -> inst.midiAt(c, f) })
        assertPlayable(tab)
        assertEquals(listOf(TabSection(0, 26)), tab.sections)
        val summary = "Read 26 notes from the sheet music (treble clef, 1 sharp, as written); strings and frets chosen by the app"
        assertEquals(summary, PdfTab.sheetMusicSummary(tab))
        assertEquals(
            listOf(summary, "Kept 1 chord (notes played together): the top note is the melody", "Left out 2 tied notes (held on, not picked again)", "Kept 1 grace note"),
            tab.warnings,
        )
        // The melody on the top two courses, as the app fingers one.
        assertTrue(tab.notes.count { it.course >= 2 } >= 23, "${tab.notes}")
    }

    @Test
    fun readsAMuseScoreStyleScore() {
        val tab = PdfTab.read(TestScore(Style.SMUFL).pdf(listOf(gMajor)), Instrument.TRICHORDO)
        assertEquals(Instrument.TRICHORDO, tab.instrument)
        assertEquals(gMajorMidis, midis(tab))
        assertEquals(gMajorColumns, tab.notes.map { it.column })
        assertPlayable(tab)
        assertEquals("Read 26 notes from the sheet music (treble clef, 1 sharp, as written); strings and frets chosen by the app", tab.warnings[0])
    }

    // B flat major (two flats), 3/4, sloping beams: a flat written where the key has one already, an E natural held
    // through the bar and cancelled by a flat, a dotted half tied over the barline.
    private val bFlat = listOf(Line("Bb4/8 C5/8 D5/8 Eb5/8 F5/4 | E5/8 En5/8 E5/4 Eb5/4 | D5/2.~ | D5/4 C5/4 B4/4 |", key = -2, time = 3 to 4))
    private val bFlatMidis = listOf(70, 72, 74, 75, 77, 75, 76, 76, 75, 74, 72, 70)
    private val bFlatColumns = listOf(0, 2, 4, 6, 8, 12, 14, 16, 20, 24, 40, 44)

    @Test
    fun readsKeySignaturesWithFlatsAndAccidentalsInABar() {
        for (style in listOf(Style.SMUFL, Style.OPUS)) {
            val tab = PdfTab.read(TestScore(style).pdf(listOf(bFlat)), inst)
            assertEquals(bFlatMidis, midis(tab), "$style")
            assertEquals(bFlatColumns, tab.notes.map { it.column }, "$style")
            assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 3, 3), tab.notes.map { it.bar }, "$style")
            assertEquals("Read 12 notes from the sheet music (treble clef, 2 flats, as written); strings and frets chosen by the app", tab.warnings[0])
            assertTrue("Left out 1 tied note (held on, not picked again)" in tab.warnings)
        }
    }

    @Test
    fun readsNoteheadsDrawnAsShapes() {
        val tab = PdfTab.read(TestScore(Style.SHAPES).pdf(listOf(bFlat)), inst)
        assertEquals(bFlatMidis, midis(tab))
        assertEquals(bFlatColumns, tab.notes.map { it.column })
    }

    @Test
    fun choosesTheOctaveThatFitsTheBouzouki() {
        // Treble 8vb (SMuFL's clef, or a treble clef with a small 8 under it), written high: played an octave down.
        for (style in listOf(Style.SMUFL, Style.OPUS)) {
            val tab = PdfTab.read(TestScore(style).pdf(listOf(listOf(Line("D5/4 E5/4 F5/4 G5/4 | A5/2 B5/2 |", clef = "treble8", key = 1, time = 4 to 4)))), inst)
            assertEquals(listOf(62, 64, 66, 67, 69, 71), midis(tab), "$style")
            assertEquals("Read 6 notes from the sheet music (treble 8vb clef, 1 sharp, an octave lower than written); strings and frets chosen by the app", tab.warnings[0])
            assertEquals("Played an octave lower than written, as the clef's 8 says", tab.warnings[1])
        }
        // Treble 8vb written lower: an octave down puts it on the A and D courses still.
        val low = PdfTab.read(TestScore(Style.SMUFL).pdf(listOf(listOf(Line("A4/4 B4/4 C5/4 D5/4 | E5/2 D5/2 |", clef = "treble8", time = 4 to 4)))), inst)
        assertEquals(listOf(57, 59, 60, 62, 64, 62), midis(low))
        // Written in the treble clef but too low for it: as written would put it on the bass courses, so an octave up.
        val under = PdfTab.read(TestScore(Style.SMUFL).pdf(listOf(listOf(Line("D4/4 B3/4 G3/4 A3/4 | B3/2 D4/2 |", time = 4 to 4)))), inst)
        assertEquals(listOf(74, 71, 67, 69, 71, 74), midis(under))
        assertEquals("Played an octave higher than written, where the notes fit the bouzouki best", under.warnings[1])
        // A bass clef melody: an octave up.
        val bass = PdfTab.read(TestScore(Style.OPUS).pdf(listOf(listOf(Line("D3/4 E3/4 F3/4 G3/4 | A3/2 D4/2 |", clef = "bass", key = 1, time = 4 to 4)))), inst)
        assertEquals(listOf(62, 64, 66, 67, 69, 74), midis(bass))
        assertEquals("Read 6 notes from the sheet music (bass clef, 1 sharp, an octave higher than written); strings and frets chosen by the app", bass.warnings[0])
    }

    @Test
    fun readsRepeatSignsOnTheStaff() {
        val tab = PdfTab.read(TestScore(Style.OPUS).pdf(listOf(listOf(Line("|: D5/4 E5/4 F5/4 G5/4 :| A5/1 |", key = 1, time = 4 to 4)))), inst)
        assertEquals(listOf(74, 76, 78, 79, 81), midis(tab))
        assertEquals(listOf(TabSection(0, 4, 2), TabSection(4, 5)), tab.sections)
        assertEquals(listOf(0, 4, 8, 12, 16), tab.notes.map { it.column })
    }

    @Test
    fun keepsTheTabWhenATabStaffIsUnderTheNotation() {
        // The notation staff of the first system ends at 140; a 4-line tab staff under it with frets on its top (D) line.
        val font = TestScore.TEXT
        val tabStaff = StringBuilder()
        for (i in 0 until 4) tabStaff.append("0.5 w 50 ${842 - 170 - 6 * i} m 300 ${842 - 170 - 6 * i} l S\n")
        for ((k, fret) in listOf("0", "2", "4", "5").withIndex()) {
            tabStaff.append("BT /T1 7 Tf ${90 + 40 * k} ${842 - 170 - 2.45} Td ${font.encode(fret)} Tj ET\n")
        }
        val pdf = TestScore(Style.OPUS).pdf(listOf(listOf(Line("D5/4 E5/4 F5/4 G5/4 |", key = 1, time = 4 to 4)))) { _, _ -> tabStaff.toString() }
        val tab = PdfTab.read(pdf, inst)
        assertEquals(listOf(3 to 0, 3 to 2, 3 to 4, 3 to 5), tab.notes.map { it.course to it.fret })
        assertNull(PdfTab.sheetMusicSummary(tab))
        // The notation alone, as the test hook reads it.
        assertEquals(listOf(74, 76, 78, 79), midis(PdfTab.readNotation(pdf, inst)))
    }

    @Test
    fun aScannedScoreHasNoNotes() {
        val w = TestPdfWriter()
        val image = w.stream("/Type /XObject /Subtype /Image /Width 8 /Height 8 /ColorSpace /DeviceGray /BitsPerComponent 8", ByteArray(64) { (it * 4).toByte() }, compress = false)
        val content = w.stream("", "q 400 0 0 500 100 200 cm /Im1 Do Q".toByteArray())
        val pagesNode = w.reserve()
        val page = w.add("<< /Type /Page /Parent $pagesNode 0 R /MediaBox [0 0 595 842] /Contents $content 0 R /Resources << /XObject << /Im1 $image 0 R >> >> >>")
        w.set(pagesNode, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        val tab = PdfTab.read(w.build(w.add("<< /Type /Catalog /Pages $pagesNode 0 R >>")), inst)
        assertEquals(emptyList(), tab.notes)
        assertEquals(listOf(PdfTab.NO_NUMBERS), tab.warnings)
        assertNull(PdfTab.sheetMusicSummary(tab))
    }

    @Test
    fun staffLinesWithoutReadableNotesSaySo() {
        // Staves drawn, but the notes are in a font the app doesn't know (their glyphs are plain letters).
        val pdf = TestScore(Style.OPUS).pdf(listOf(listOf(Line("R |", time = 4 to 4)))) { _, _ ->
            "BT /T1 20 Tf 120 ${842 - 135} Td (oooo) Tj ET\n"
        }
        val tab = PdfTab.read(pdf, inst)
        assertEquals(emptyList(), tab.notes)
        assertEquals(listOf(PdfTab.NO_NOTES), tab.warnings)
    }

    @Test
    fun aHostileScoreIsReadInBoundedTime() {
        // One staff with 60 000 noteheads piled on it, and 150 more staves: read quickly, without failing.
        val heads = StringBuilder()
        for (i in 0 until 60_000) heads.append("BT /M1 20 Tf ${60 + (i % 480)} ${842 - 140 + (i % 13)} Td (\\317) Tj ET\n")
        for (k in 0 until 150) for (j in 0 until 5) heads.append("0.5 w 50 ${842 - 260 - 3.5 * k - 0.6 * j} m 540 ${842 - 260 - 3.5 * k - 0.6 * j} l S\n")
        val pdf = TestScore(Style.OPUS).pdf(listOf(listOf(Line("D5/4 |", time = 4 to 4)))) { _, _ -> heads.toString() }
        val t0 = System.nanoTime()
        val tab = PdfTab.read(pdf, inst)
        val seconds = (System.nanoTime() - t0) / 1e9
        assertTrue(seconds < 20, "took $seconds s")
        assertTrue(tab.notes.size <= 50_000)
    }
}
