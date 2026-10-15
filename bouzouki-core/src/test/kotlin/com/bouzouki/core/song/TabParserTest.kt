package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reading tabs as people post them. Every tab here is a made-up phrase written for these tests. */
class TabParserTest {
    private val tri = Instrument.TRICHORDO
    private val tetra = Instrument.TETRACHORDO

    private fun ParsedTab.pitches() = notes.indices.map { midi(it) }

    @Test
    fun aTrichordoTabWithStringNamesLegatoAndTremolo() {
        val tab = TabParser.parse(
            """
            d|--0--2h3--5p3--2-----7/9--10~~~--|
            A|------------------3--------------|
            D|---------------------------------|
            """.trimIndent(), tri,
        )
        assertEquals(tri, tab.instrument)
        assertEquals(listOf(62, 64, 65, 67, 65, 64, 60, 69, 71, 72), tab.pitches())
        assertEquals(
            listOf(Articulation.PICK, Articulation.PICK, Articulation.HAMMER, Articulation.PICK, Articulation.PULL, Articulation.PICK,
                Articulation.PICK, Articulation.PICK, Articulation.SLIDE, Articulation.TREMOLO),
            tab.notes.map { it.articulation },
        )
        assertEquals(listOf(2, 5, 7, 10, 12, 15, 18, 21, 23, 26), tab.notes.map { it.column - tab.notes[0].column + 2 })
        assertEquals(1, tab.notes[6].course)
        assertTrue(tab.warnings.isEmpty(), "${tab.warnings}")
        assertTrue(!tab.forOtherBouzouki)
    }

    @Test
    fun anUnnamedTetrachordoTabBetweenChordNamesAndLyrics() {
        val tab = TabParser.parse(
            """
            Dm                  A7
            |--5-----8--7--5---|-------------|
            |-----6------------|--8--7--5----|
            |------------------|-----------7-|
            |------------------|-------------|
            la la the boat goes out, la la

            |--10--12--13---|
            |---------------|
            |-------------|
            |---------------|
            """.trimIndent(), tetra,
        )
        assertEquals(tetra, tab.instrument)
        assertEquals(listOf(67, 63, 70, 69, 67, 65, 64, 62, 60, 72, 74, 75), tab.pitches())
        // Columns run on across systems, so the second system comes after the first.
        assertTrue(tab.notes.zipWithNext().all { (a, b) -> b.column > a.column })
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1), tab.notes.map { it.system })
        assertTrue(tab.warnings.isEmpty(), "${tab.warnings}")
    }

    @Test
    fun greekStringNamesOfDifferentWidthsAndAChord() {
        val tab = TabParser.parse(
            """
            Ρε|--3--5--7--|
            Λα|-----------|
            Φα|-----------|
            Ντο|-----3-----|
            """.trimIndent(), tetra,
        )
        assertEquals(listOf(65, 67, 69), tab.pitches())
        // The C course's 3 sits under the 5: one chord, its top note kept, the other in the stack.
        assertEquals(listOf(0 to 3), tab.notes[1].stack)
        assertEquals(3, tab.notes[1].course)
    }

    @Test
    fun namesWithoutBarsAndUpsideDownOrder() {
        val tab = TabParser.parse(
            """
            C-------------
            F-----3-------
            A--------2----
            D--0--------5-
            """.trimIndent(), tetra,
        )
        // The names say the top line is C: read by name, not position.
        assertEquals(listOf(62, 56, 59, 67), tab.pitches())
        assertEquals(listOf(3, 1, 2, 3), tab.notes.map { it.course })
    }

    @Test
    fun aTabForTheOtherBouzoukiIsReadOnItAndSaidSo() {
        val tab = TabParser.parse(
            """
            |--2--3--5--|
            |-----------|
            |--------0--|
            """.trimIndent(), tetra,
        )
        assertEquals(tri, tab.instrument)
        assertEquals(tetra, tab.asked)
        assertTrue(tab.forOtherBouzouki)
        assertTrue(tab.warnings.any { "trichordo" in it }, "${tab.warnings}")
        assertEquals(listOf(64, 65, 67), tab.pitches()) // the D3 course's open note shares a column with the 5
        assertEquals(listOf(0 to 0), tab.notes[2].stack)
    }

    @Test
    fun repeatMarksHeadingsAndPartsNamedAgain() {
        val tab = TabParser.parse(
            """
            Intro
            D|--7--5--3--|
            A|-----------|
            F|-----------|
            C|-----------|

            Verse
            D|--3--5--|  x2
            A|--------|
            F|--------|
            C|--------|

            D|--8--7--|
            A|--------|
            F|--------|
            C|--------|
            (x3)

            Chorus x2
            D||:--10--12--:|--13--|
            A||:----------:|------|
            F||:----------:|------|
            C||:----------:|------|

            Verse
            Chorus
            """.trimIndent(), tetra,
        )
        assertEquals(10, tab.notes.size)
        val s = tab.sections
        assertEquals(TabSection(0, 3, 1, "intro"), s[0])
        assertEquals(TabSection(3, 5, 2, "verse"), s[1])
        assertEquals(TabSection(5, 7, 3, "verse"), s[2])
        // The chorus x2 holds a stretch between repeat bars: read as one part played twice.
        assertEquals(TabSection(7, 10, 2, "chorus"), s[3])
        // "Verse" and "Chorus" again without tab play those parts again.
        assertEquals(TabSection(3, 7, 1, "verse"), s[4])
        assertEquals(TabSection(7, 10, 1, "chorus"), s[5])
        assertTrue(tab.warnings.any { "one repeat" in it }, "${tab.warnings}")
    }

    @Test
    fun repeatBarsInsideASystem() {
        val tab = TabParser.parse(
            """
            d|--2--|:--3--5--:|--7--|
            A|-----|:---------:|-----|
            D|-----|:---------:|-----|
            """.trimIndent(), tri,
        )
        assertEquals(listOf(TabSection(0, 1), TabSection(1, 3, 2), TabSection(3, 4)), tab.sections)
    }

    @Test
    fun aGuitarTabIsLeftOut() {
        val tab = TabParser.parse(
            """
            e|--0--3--|
            B|--------|
            G|--------|
            D|--------|
            A|--------|
            E|--------|
            """.trimIndent(), tetra,
        )
        assertTrue(tab.notes.isEmpty())
        assertTrue(tab.warnings.any { "guitar" in it }, "${tab.warnings}")
    }

    @Test
    fun whatCantBeReadIsSaid() {
        val tab = TabParser.parse(
            """
            d|--x--5b7--27--3--|
            A|-----------------|
            D|-----------------|
            """.trimIndent(), tri,
        )
        // The muted note and the bend's target are left out, 27 is past the trichordo's 26th fret.
        assertEquals(listOf(67, 65), tab.pitches())
        assertTrue(tab.warnings.any { "muted" in it }, "${tab.warnings}")
        assertTrue(tab.warnings.any { "bend" in it }, "${tab.warnings}")
        assertTrue(tab.warnings.any { "26th" in it }, "${tab.warnings}")
    }

    @Test
    fun noTabAtAll() {
        val tab = TabParser.parse("Just some words about a song\nand a chord or two: Am E7", tetra)
        assertTrue(tab.notes.isEmpty())
        assertTrue(tab.sections.isEmpty())
        assertTrue(tab.warnings.single().startsWith("No tab"))
    }

    @Test
    fun twoSystemsWithoutABlankLineAndStringNumbers() {
        val tab = TabParser.parse(
            """
            1|--5--7--|
            2|--------|
            3|--------|
            1|--8--5--|
            2|--------|
            3|--------|
            """.trimIndent(), tri,
        )
        assertEquals(tri, tab.instrument)
        assertEquals(listOf(67, 69, 70, 67), tab.pitches())
        assertEquals(listOf(0, 0, 1, 1), tab.notes.map { it.system })
    }

    @Test
    fun repeatTheChorusAndLooseWriting() {
        val tab = TabParser.parse(
            """
            [Ρεφρέν]
            d —— 5 —— 7 —— 8 ——
            a ———————————————————
            D ———————————————————

            Στροφή:
            d|----3----2-----|
            A|---------------|
            D|---------------|

            Repeat chorus
            """.trimIndent(), tri,
        )
        // Long dashes and spaces between them, lowercase names; a "repeat" heading plays the part again.
        assertEquals(listOf(67, 69, 70, 65, 64), tab.pitches())
        assertEquals(listOf(TabSection(0, 3, 1, "chorus"), TabSection(3, 5, 1, "verse"), TabSection(0, 3, 1, "chorus")), tab.sections)
    }

    @Test
    fun aLegatoMarkAcrossCoursesIsPicked() {
        val tab = TabParser.parse(
            """
            d|--------h3--|
            A|--2---------|
            D|------------|
            """.trimIndent(), tri,
        )
        assertEquals(listOf(Articulation.PICK, Articulation.PICK), tab.notes.map { it.articulation })
    }
}
