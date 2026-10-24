package com.bouzoukihero.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabTest {
    private fun tab(p: List<Fretboard.Position>) = p.joinToString(",") { "${it.course}:${it.fret}" }

    /** Answers from Bouzouki Tabs' own fretboard.py for the same melodies. */
    @Test fun sameTabAsBouzoukiTabs() {
        val mel = listOf(62, 63, 66, 67, 69, 70, 72, 74, 75, 74, 72, 70, 69, 67, 66, 63, 62, 57, 55, 53, 50, 48, 60, 64, 67, 72, 76, 79, 84, 86)
        assertEquals(
            "3:0,3:1,3:4,3:5,3:7,3:8,3:10,3:12,3:13,3:12,3:10,3:8,3:7,3:5,3:4,3:1,3:0,2:0,1:2,1:0,0:2,0:0,2:3,3:2,3:5,3:10,3:14,3:17,3:22,3:24",
            tab(Fretboard.assign(mel, mel.indices.map { it * 0.25 })),
        )
        val mel2 = listOf(69, 69, 70, 69, 67, 66, 67, 62, 74, 72, 70, 69, 70, 69, 67, 66, 67, 63, 66, 62)
        val t2 = listOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 1.5, 1.6, 1.7, 1.8, 2.0, 2.1, 2.2, 2.3, 2.4, 2.6, 2.8, 3.0, 3.2)
        assertEquals("3:7,3:7,3:8,3:7,3:5,3:4,3:5,3:0,3:12,3:10,3:8,3:7,3:8,3:7,3:5,3:4,3:5,3:1,3:4,3:0", tab(Fretboard.assign(mel2, t2)))
        assertEquals(52, Fretboard.fitToRange(40))
        assertEquals(83, Fretboard.fitToRange(95))
    }

    @Test fun tabButtonsAreFingers() {
        // D Hitzaz up the D course: D(0) Eb(1) F#(4) G(5) A(7) Bb(8).
        val notes = listOf(62, 63, 66, 67, 69, 70).mapIndexed { i, p -> SourceNote(1.0 + i * 0.5, 0.2, p) }
        val gems = Charts.build(notes, Difficulty.HARD, buttons = Buttons.TAB)
        assertEquals(listOf("D0", "D1", "D4", "D5", "D7", "D8"), gems.map { it.label })
        // Open string, finger 1 at fret 1, then the hand shifts up: F#(4) is finger 4 with
        // the hand at 1, G(5) moves it to 2, A(7) to 4, Bb(8) to 5.
        assertEquals(listOf(0, 1, 4, 4, 4, 4), gems.map { it.lane })
        assertEquals(listOf(1, 1, 1, 2, 4, 5), gems.map { it.base })
        assertEquals("0", Charts.tabButtonLabel(0, 5, 5))
        assertEquals("7", Charts.tabButtonLabel(3, 5, 5))
        assertEquals("0-5", Charts.tabButtonLabel(0, 3, 5))
        assertEquals("7-8", Charts.tabButtonLabel(2, 3, 5))
        for (d in Difficulty.entries) {
            val g = Charts.build(Tunes.all[1].sourceNotes(), d, buttons = Buttons.TAB)
            assertTrue(g.all { it.lane in 0 until d.lanes && it.base >= 1 }, "$d")
            assertTrue(g.map { it.lane }.distinct().size >= minOf(3, d.lanes), "$d uses the buttons: ${g.groupingBy { it.lane }.eachCount()}")
        }
    }

    @Test fun recordedChartsKeepTheirLanes() {
        val notes = listOf(0, 4, 2).mapIndexed { i, p -> SourceNote(i.toDouble(), 0.1, p) }
        val g = Charts.build(notes, Difficulty.EXPERT, labels = false, buttons = Buttons.TAB)
        assertEquals(listOf(0, 4, 2), g.map { it.lane })
        assertTrue(g.all { it.base == -1 })
    }
}
