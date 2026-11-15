package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TabEditTest {
    private val inst = Instrument.TETRACHORDO
    private val song = SongAnalysis("id", "t", inst, listOf(
        SongNote(0.0, 0.4, 62, 3, 0), SongNote(0.5, 0.9, 64, 3, 2), SongNote(1.0, 1.4, 66, 3, 4),
    ), null, 2)

    @Test
    fun editsKeepPitchAndPlaceTogether() {
        val moved = TabEdit.place(song, 1, 2, 7)
        assertEquals(SongNote(0.5, 0.9, 64, 2, 7), moved.notes[1])
        assertTrue(moved.edited)
        // Up a semitone on its course; past its course's last fret, onto another.
        assertEquals(SongNote(1.0, 1.4, 67, 3, 5), TabEdit.transpose(song, 2, 1).notes[2])
        val high = SongAnalysis("id", "t", inst, listOf(SongNote(0.0, 0.4, 62 + 27, 3, 27)), null, 2)
        assertEquals(high, TabEdit.transpose(high, 0, 1)) // nowhere higher: unchanged
        assertEquals(Articulation.TREMOLO, TabEdit.articulate(song, 0, Articulation.TREMOLO).notes[0].articulation)
        assertEquals(listOf(62, 66), TabEdit.delete(song, 1).notes.map { it.midi })
        val added = TabEdit.insertAfter(song, 0)
        assertEquals(listOf(0.0, 0.25, 0.5, 1.0), added.notes.map { it.start })
        assertEquals(0.25, added.notes[0].end)
        // Edited songs keep their notes through the cache.
        assertEquals(moved, SongAnalysis.fromJson(moved.toJson()))
        assertEquals(listOf(0 to 14, 1 to 9, 2 to 5, 3 to 0), TabEdit.places(inst, 62))
    }

    @Test
    fun aNoteSetByHandIsWithoutDoubt() {
        // A line the analysis is not sure of: the notes the player sets are sure from then on, the others as they were.
        val doubted = song.copy(notes = song.notes.map { it.copy(confidence = 0.3f) })
        assertEquals(listOf(0.3f, 1f, 0.3f), TabEdit.place(doubted, 1, 2, 7).notes.map { it.confidence })
        assertEquals(listOf(0.3f, 0.3f, 1f), TabEdit.transpose(doubted, 2, 1).notes.map { it.confidence })
        assertEquals(listOf(1f, 0.3f, 0.3f), TabEdit.articulate(doubted, 0, Articulation.HAMMER).notes.map { it.confidence })
        assertEquals(listOf(0.3f, 1f, 0.3f, 0.3f), TabEdit.insertAfter(doubted, 0).notes.map { it.confidence })
        assertEquals(listOf(0.3f, 0.3f), TabEdit.delete(doubted, 1).notes.map { it.confidence })
        // Saved and read back, and fingered for the other bouzouki, every note is as sure as it was.
        val edited = TabEdit.place(doubted, 1, 2, 7)
        assertEquals(edited, SongAnalysis.fromJson(edited.toJson()))
        assertEquals(listOf(0.3f, 1f, 0.3f), edited.forInstrument(Instrument.TRICHORDO).notes.map { it.confidence })
        assertEquals(listOf(0.3f, 0.3f, 0.3f), SongAnalysis.fromJson(doubted.forInstrument(inst).toJson())!!.notes.map { it.confidence })
    }
}
