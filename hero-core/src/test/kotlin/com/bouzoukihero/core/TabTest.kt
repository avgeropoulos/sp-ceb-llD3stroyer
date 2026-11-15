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

    @Test fun keepsTheFretsOfAPreparedSong() {
        // A song prepared by the Μπουζούκι app on a trichordo: its frets are used as they are.
        val labels = Charts.courseLabels(listOf(50, 57, 62))
        assertEquals(listOf("d", "A", "D"), labels)
        assertEquals(listOf("C", "F", "A", "D"), Charts.courseLabels(listOf(48, 53, 57, 62)))
        val notes = listOf(
            Charts.TabNote(1.0, 0.2, 69, 1, 12), Charts.TabNote(1.5, 0.2, 67, 2, 5),
            Charts.TabNote(2.0, 1.2, 66, 2, 4, tremolo = true), Charts.TabNote(3.5, 0.2, 62, 2, 0),
        )
        val gems = Charts.fromTab(notes, Difficulty.HARD, labels)
        assertEquals(listOf("A12", "D5", "D4", "D0"), gems.map { it.label })
        // The hand starts with finger 1 at fret 12, then moves down to 5 and 4.
        assertEquals(listOf(12, 5, 4, 4), gems.map { it.base })
        assertEquals(listOf(1, 1, 1, 0), gems.map { it.lane })
        assertTrue(gems[2].isHold && gems[2].tremolo)
    }

    @Test fun easyKeepsTheNotesThatEndAPhrase() {
        // A fast run landing on a held note, and a quick two-note ending: Easy thins the runs
        // but must keep the landing notes and the song's last note.
        val run = (0 until 8).map { SourceNote(1.0 + it * 0.12, 0.1, 62 + it) }
        val landing = SourceNote(1.96, 1.0, 74)
        val ending = listOf(SourceNote(4.0, 0.15, 69), SourceNote(4.2, 1.0, 62))
        for (b in Buttons.entries) {
            val gems = Charts.build(run + landing + ending, Difficulty.EASY, buttons = b)
            val times = gems.map { it.time }
            assertTrue(1.96 in times, "$b keeps the run's landing note: $times")
            assertEquals(4.2, times.last(), "$b keeps the last note: $times")
            times.zipWithNext().forEach { (a, c) -> assertTrue(c - a >= Difficulty.EASY.minGap - 1e-9) }
        }
    }

    private val tetra = Charts.courseLabels(listOf(48, 53, 57, 62))

    /** A note on the top course, [sure] (0..1) of it. */
    private fun note(time: Double, sure: Float, fret: Int = 0, tremolo: Boolean = false) =
        Charts.TabNote(time, 0.2, 62 + fret, 3, fret, tremolo, sure)

    @Test fun leavesOutTheNotesAPreparedSongIsNotSureOf() {
        // A note a second, slow enough for every level: only the confidence can leave one out.
        val sure = listOf(1f, 0.05f, 0.15f, 0.25f, 0.35f, 0.45f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 1f, 0.4f, 0.3f, 0.2f, 0.1f, 1f, 1f)
        val notes = sure.mapIndexed { i, c -> note(1.0 + i, c, i % 5) }
        for (d in Difficulty.entries) {
            val floor = d.confidenceFloor
            assertTrue(floor > 0f && floor < 1f, "$d: $floor")
            val times = Charts.fromTab(notes, d, tetra).map { it.time }
            // A note just as sure as the level asks stays.
            assertEquals(notes.filter { it.confidence >= floor }.map { it.time }, times, "$d")
            assertEquals(notes.size, Charts.fromTab(notes, d, tetra, floor = 0f).size, "$d without a floor")
        }
        // The fewer the buttons, the surer the notes: Easy asks for the most, Expert takes nearly everything.
        val floors = Difficulty.entries.map { it.confidenceFloor }
        assertEquals(floors.sortedDescending(), floors)
        assertTrue(Difficulty.EASY.confidenceFloor > Difficulty.HARD.confidenceFloor && Difficulty.EXPERT.confidenceFloor <= 0.15f, "$floors")
        // A tremolo note stays whatever its confidence, and so does every note without doubt.
        val held = notes + note(30.0, 0f, 3, tremolo = true)
        for (d in Difficulty.entries) assertEquals(30.0, Charts.fromTab(held, d, tetra).last().time, "$d")
        // (Whatever floor is asked for: no floor is above "no doubt".)
        val mostlySure = (0 until 16).map { note(1.0 + it, if (it < 10) 1f else 0.99f) }
        assertEquals(10, Charts.sure(mostlySure, 1f).size)
        assertEquals(10, Charts.sure(mostlySure, 5f).size)
        // A song from before notes said how sure they are (every note 1) plays as it always did.
        val before = notes.map { it.copy(confidence = 1f) }
        for (d in Difficulty.entries) assertEquals(before.map { it.time }, Charts.fromTab(before, d, tetra).map { it.time }, "$d")
    }

    @Test fun aSongInDoubtEverywhereKeepsAllItsNotes() {
        // More than half of the notes under the floor: the floor would leave too little of the song to play.
        val weak = (0 until 20).map { note(1.0 + it, if (it % 5 == 0) 0.9f else 0.05f, it % 4) }
        for (d in Difficulty.entries) assertEquals(20, Charts.fromTab(weak, d, tetra).size, "$d")
        assertEquals(weak, Charts.sure(weak, 0.4f))
        // Just half of them sure: those stay, the others go.
        val half = (0 until 20).map { note(1.0 + it, if (it % 2 == 0) 0.9f else 0.05f, it % 4) }
        assertEquals(10, Charts.sure(half, 0.4f).size)
        // A hard recording, most of its notes under what the level asks: it is asked for half as much, which
        // still leaves out the notes it is least sure of.
        val hard = (0 until 20).map { note(1.0 + it, if (it % 5 == 0) 0.9f else if (it % 5 == 1) 0.05f else 0.25f, it % 4) }
        assertEquals(hard.filter { it.confidence >= 0.2f }, Charts.sure(hard, 0.4f))
        assertEquals(16, Charts.fromTab(hard, Difficulty.EASY, tetra).size)
        // ...or a quarter.
        val harder = hard.map { if (it.confidence == 0.25f) it.copy(confidence = 0.12f) else it }
        assertEquals(16, Charts.sure(harder, 0.4f).size)
        // A short song that would keep fewer than eight notes keeps them all.
        val short = (0 until 10).map { note(1.0 + it, if (it < 7) 0.9f else 0.05f, it % 4) }
        assertEquals(10, Charts.sure(short, 0.4f).size)
        assertEquals(8, Charts.sure(short + note(20.0, 1f), 0.4f).size)
        assertEquals(emptyList(), Charts.sure(emptyList(), 0.4f))
    }

    @Test fun ofTwoNotesTooCloseToPlayBothKeepsTheOneItIsSureOf() {
        // On Easy two notes 0.2 s apart can't both be played. (No floor here: only the thinning chooses.)
        fun kept(vararg notes: Charts.TabNote) = Charts.fromTab(notes.toList(), Difficulty.EASY, tetra, floor = 0f).map { it.label }
        // In a phrase that goes on, the first of two is kept, as always, when they are about as sure...
        assertEquals(listOf("D1", "D3"), kept(note(1.0, 1f, 1), note(1.2, 1f, 2), note(1.7, 1f, 3)))
        assertEquals(listOf("D1", "D3"), kept(note(1.0, 0.5f, 1), note(1.2, 0.5f, 2), note(1.7, 0.5f, 3)))
        assertEquals(listOf("D1", "D3"), kept(note(1.0, 0.6f, 1), note(1.2, 0.9f, 2), note(1.7, 0.9f, 3)))
        // ...and the second when the first is in doubt and the second is not.
        assertEquals(listOf("D2", "D3"), kept(note(1.0, 0.3f, 1), note(1.2, 0.9f, 2), note(1.7, 0.9f, 3)))
        assertEquals(listOf("D2", "D3"), kept(note(1.0, 0.4f, 1), note(1.2, 0.9f, 2), note(1.7, 0.9f, 3)))
        // The note that ends a phrase wins over the one before, unless it is the one in doubt
        // (the band's, found just after the bouzouki's phrase).
        assertEquals(listOf("D2"), kept(note(1.0, 0.9f, 1), note(1.2, 0.9f, 2)))
        assertEquals(listOf("D2"), kept(note(1.0, 0.9f, 1), note(1.2, 0.6f, 2)))
        assertEquals(listOf("D1"), kept(note(1.0, 0.9f, 1), note(1.2, 0.2f, 2)))
        // Of a chord's notes the higher one, unless that is the one in doubt (an octave heard over the note).
        assertEquals(listOf("D12", "D3"), kept(note(1.0, 0.8f, 0), note(1.0, 0.8f, 12), note(2.0, 1f, 3)))
        assertEquals(listOf("D12", "D3"), kept(note(1.0, 0.8f, 0), note(1.0, 0.5f, 12), note(2.0, 1f, 3)))
        assertEquals(listOf("D0", "D3"), kept(note(1.0, 0.8f, 0), note(1.0, 0.2f, 12), note(2.0, 1f, 3)))
        // The notes kept are still never closer than the level allows.
        val run = (0 until 40).map { note(1.0 + it * 0.11, if (it % 3 == 0) 0.2f else 0.9f, it % 5) }
        for (d in Difficulty.entries) {
            val times = Charts.fromTab(run, d, tetra, floor = 0f).map { it.time }
            times.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= d.minGap - 1e-9, "$d: $times") }
        }
    }

    @Test fun notesAboutAsSureThinAsTheyAlwaysDid() {
        // A tune with fast runs, chords and phrase endings: the same buttons whether every note is without doubt
        // or all are in the same doubt, at every level.
        val tune = Tunes.all[1].sourceNotes().mapIndexed { i, n -> Charts.TabNote(n.time, n.duration, 62 + i % 12, 3, i % 12, n.tremolo) }
        for (d in Difficulty.entries) {
            val sure = Charts.fromTab(tune, d, tetra)
            val unsure = Charts.fromTab(tune.map { it.copy(confidence = 0.6f) }, d, tetra, floor = 0f)
            assertTrue(sure.size >= 8, "$d: ${sure.size}")
            assertEquals(sure.map { it.time to it.label }, unsure.map { it.time to it.label }, "$d")
        }
    }

    @Test fun recordedChartsKeepTheirLanes() {
        val notes = listOf(0, 4, 2).mapIndexed { i, p -> SourceNote(i.toDouble(), 0.1, p) }
        val g = Charts.build(notes, Difficulty.EXPERT, labels = false, buttons = Buttons.TAB)
        assertEquals(listOf(0, 4, 2), g.map { it.lane })
        assertTrue(g.all { it.base == -1 })
    }
}
