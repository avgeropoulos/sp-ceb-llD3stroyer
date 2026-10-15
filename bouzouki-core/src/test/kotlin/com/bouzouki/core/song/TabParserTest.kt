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

    @Test
    fun aDividerNextToTheTabIsNoCourse() {
        // Dashes, = or _ right above or below a system, about as wide as it, with no blank line between.
        for (rule in listOf("-------------", "=============", "_____________")) {
            val tri3 = TabParser.parse("$rule\nd|--3--5--7--|\nA|------------|\nD|--0---------|\n$rule", tri)
            assertEquals(tri, tri3.instrument, rule)
            assertEquals(listOf(65, 67, 69), tri3.pitches(), rule)
            assertTrue(tri3.warnings.isEmpty(), "$rule: ${tri3.warnings}")
        }
        val tab = TabParser.parse(
            """
            Verse
            ====================
            D|--5--7--8--7--5--|
            A|-----------------|
            F|-----------------|
            C|-----------------|
            """.trimIndent(), tetra,
        )
        assertEquals(listOf(67, 69, 70, 69, 67), tab.pitches())
        assertTrue(tab.warnings.isEmpty(), "${tab.warnings}")
    }

    @Test
    fun trichordoSystemsWithoutBlankLinesForATetrachordoPlayer() {
        // Twelve lines (3 and 4 both divide them): the names repeating every three lines tell the trichordo.
        val tab = TabParser.parse(
            """
            d|--5--7--|
            A|--------|
            D|--------|
            d|--8-----|
            A|--------|
            D|-----2--|
            d|--3--5--|
            A|--------|
            D|--------|
            d|--7--8--|
            A|--------|
            D|--------|
            """.trimIndent(), tetra,
        )
        assertEquals(tri, tab.instrument)
        assertEquals(listOf(67, 69, 70, 52, 65, 67, 69, 70), tab.pitches())
        assertTrue(tab.forOtherBouzouki)
    }

    @Test
    fun repeatCountsWrittenInWords() {
        fun times(line: String, inst: Instrument = tri): Int {
            val tab = TabParser.parse("$line\nA|----------|\nD|----------|", inst)
            assertEquals(2, tab.notes.size, "$line: ${tab.warnings}")
            assertTrue(tab.warnings.isEmpty(), "$line: ${tab.warnings}")
            return tab.sections.single().times
        }
        assertEquals(3, times("d|--5--7--| 3 times"))
        assertEquals(3, times("d|--5--7--| (3 times)"))
        assertEquals(2, times("d|--5--7--| twice"))
        assertEquals(2, times("d|--5--7--| 2 φορές"))
        assertEquals(2, times("d|--5--7--| δις"))
        assertEquals(4, times("d|--5--7--| repeat 4 times"))
        assertEquals(2, times("d|--5--7--x2"))
        // A heading's count, not its number.
        val tab = TabParser.parse("Chorus 3 times\nd|--5--7--|\nA|---------|\nD|---------|", tri)
        assertEquals(listOf(TabSection(0, 2, 3, "chorus")), tab.sections)
    }

    @Test
    fun repeatsAcrossSystemsAndBetweenBars() {
        val across = TabParser.parse(
            """
            d||:--3--5--|
            A||:--------|
            D||:--------|

            d|--1--2--|
            A|--------|
            D|--------|

            d|--7--8--:||
            A|--------:||
            D|--------:||
            """.trimIndent(), tri,
        )
        assertEquals(listOf(TabSection(0, 6, 2)), across.sections)
        // A count between bars is a repeat of what comes before it, not a muted note and a fret.
        val between = TabParser.parse(
            """
            D|--5--7--8--| x3 |--10--8--|
            A|-----------|    |---------|
            F|-----------|    |---------|
            C|-----------|    |---------|
            """.trimIndent(), tetra,
        )
        assertEquals(listOf(67, 69, 70, 72, 70), between.pitches())
        assertEquals(listOf(TabSection(0, 3, 3), TabSection(3, 5, 1)), between.sections)
        assertTrue(between.warnings.isEmpty(), "${between.warnings}")
    }

    @Test
    fun wordsAfterTheTabAreLeftOut() {
        for (tail in listOf(" let ring", "  (slowly)", " Dm")) {
            val tab = TabParser.parse("D|--5--7--8--|$tail\nA|-----------|\nF|-----------|\nC|-----------|", tetra)
            assertEquals(listOf(67, 69, 70), tab.pitches(), tail)
            assertEquals(listOf("Left out \"${tail.trim()}\" written after the tab on line 1"), tab.warnings)
        }
        // The string names written again after the last bar.
        val both = TabParser.parse("D|--5--7--8--|D\nA|-----------|A\nF|-----------|F\nC|--3--------|C", tetra)
        assertEquals(listOf(67, 69, 70), both.pitches())
        assertEquals(listOf(listOf(0 to 3), emptyList(), emptyList()), both.notes.map { it.stack })
        assertTrue(both.warnings.isEmpty(), "${both.warnings}")
        // A heading after a row of dashes stays a heading.
        assertEquals("chorus", TabParser.parse("------ Chorus\nD|--5--|\nA|-----|\nF|-----|\nC|-----|", tetra).sections.single().name)
    }

    @Test
    fun headingsNamingSeveralPartsOrAPartAgain() {
        val verseChorus = """
            Verse 1
            D|--5--7--|
            A|--------|
            F|--------|
            C|--------|
            Verse 2
            D|--8--10--|
            A|---------|
            F|---------|
            C|---------|
            Chorus
            D|--12--|
            A|------|
            F|------|
            C|------|
        """.trimIndent()
        val again = TabParser.parse("$verseChorus\nRepeat verse 1 and chorus\nChorus (same as above)", tetra)
        assertEquals(
            listOf(TabSection(0, 2, 1, "verse 1"), TabSection(2, 4, 1, "verse 2"), TabSection(4, 5, 1, "chorus"),
                TabSection(0, 2, 1, "verse 1"), TabSection(4, 5, 1, "chorus"), TabSection(4, 5, 1, "chorus")),
            again.sections,
        )
        assertTrue(again.warnings.isEmpty(), "${again.warnings}")
        // A line starting like a heading but saying more is text, and said so.
        val text = TabParser.parse("$verseChorus\nChorus: play it softly with a light pick", tetra)
        assertEquals(3, text.sections.size)
        assertTrue(text.warnings.single().startsWith("Read line 16 as text"), "${text.warnings}")
    }

    @Test
    fun greekRepeatMarksAndHeadingsAsGreekKeyboardsTypeThem() {
        // The X key on a Greek keyboard types χ: a count after a tab line, on a heading, or between bars.
        val phrase = "D|-0-2-4-5-|-7-5-4-2-|\nA|---------|---------|\nF|---------|---------|\nC|---------|---------|"
        for (mark in listOf("  x2", "  Χ2", "  χ2", "  (Χ2)", "  2Χ")) {
            val lines = phrase.lines().toMutableList()
            lines[0] += mark
            val tab = TabParser.parse("Εισαγωγή\n" + lines.joinToString("\n"), tetra)
            assertEquals(8, tab.notes.size, "$mark: ${tab.warnings}")
            assertEquals(listOf(TabSection(0, 8, 2, "intro")), tab.sections, mark)
            assertTrue(tab.warnings.isEmpty() && tab.unread == 0, "$mark: ${tab.warnings}")
        }
        assertEquals(2, TabParser.parse("Εισαγωγή Χ2\n$phrase", tetra).sections.single().times)
        assertEquals(2, TabParser.parse("ΡΕΦΡΕΝ (Χ2)\n$phrase", tetra).sections.single().times)
        val between = TabParser.parse("D|-0-2-| Χ3 |-4-5-|\nA|-----|    |-----|\nF|-----|    |-----|\nC|-----|    |-----|", tetra)
        assertEquals(listOf(TabSection(0, 2, 3), TabSection(2, 4, 1)), between.sections)
        // Words with χ in them stay words, and a line that can't be read is counted.
        val bad = TabParser.parse("$phrase\nD|-0-1-4-5-|-7-5-4-ψ-|\nA|---------|---------|", tetra)
        assertEquals(1, bad.unread)

        // Ρεφραίν, numbers written before the part (2ο, Β'), a count with a χ, and Τέλος at the end.
        fun system(fret: Int) = "d|--$fret--${fret + 2}--|\nA|----------|\nD|----------|\n"
        val tab = TabParser.parse(
            "Εισαγωγή Χ2\n" + system(0) + "\n1ο Κουπλέ\n" + system(3) + "\nΡεφραίν χ2\n" + system(5) +
                "\nΒ' Κουπλέ\n" + system(7) + "\nΡεφρέν 2Χ\n\nΤέλος\n", tri,
        )
        assertEquals(
            listOf(TabSection(0, 2, 2, "intro"), TabSection(2, 4, 1, "verse 1"), TabSection(4, 6, 2, "chorus"),
                TabSection(6, 8, 1, "verse 2"), TabSection(4, 6, 2, "chorus")),
            tab.sections,
        )
        assertTrue(tab.warnings.isEmpty(), "${tab.warnings}")
        // A part named with no tab and nothing before it to play again: said with the heading as written.
        val lone = TabParser.parse(system(0) + "\nΡεφρέν\n", tri)
        assertEquals(listOf("\"Ρεφρέν\" on line 5 has no tab under it, and no part of that name before it to play again"), lone.warnings)
        // A short line naming a part that isn't read as a heading is said.
        val odd = TabParser.parse("Το κουπλέ αργά\n" + system(0), tri)
        assertTrue(odd.warnings.single().startsWith("Read line 1 as text"), "${odd.warnings}")
    }

    @Test
    fun spacesOrTabCharactersForDashes() {
        val spaces = TabParser.parse("D|  5  7  8  7  5  |\nA|                 |\nF|                 |\nC|                 |", tetra)
        assertEquals(listOf(67, 69, 70, 69, 67), spaces.pitches())
        // Tab characters go to the next tab stop, so the columns stay lined up.
        val tabs = TabParser.parse("D|\t5\t7\t8\t|\nA|\t-\t-\t-\t|\nF|\t-\t3\t-\t|\nC|\t-\t-\t-\t|", tetra)
        assertEquals(listOf(67, 69, 70), tabs.pitches())
        assertEquals(listOf(emptyList(), listOf(1 to 3), emptyList()), tabs.notes.map { it.stack })
    }
}
