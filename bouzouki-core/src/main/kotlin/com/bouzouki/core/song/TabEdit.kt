package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.math.abs

/**
 * The tab editor's changes to a song's line, each giving the song changed and marked [SongAnalysis.edited] (its frets
 * then stay as the player left them, see the song screen). Notes stay in time order; a note's pitch always is its
 * course's and fret's.
 */
object TabEdit {
    /** Every place [midi] can be played on [inst]: (course, fret), bass course first. */
    fun places(inst: Instrument, midi: Int): List<Pair<Int, Int>> =
        inst.courses.indices.mapNotNull { c -> (midi - inst.courses[c].openMidi).takeIf { it in 0..inst.frets }?.let { c to it } }

    private fun SongAnalysis.with(notes: List<SongNote>) = copy(notes = notes.sortedBy { it.start }, edited = true)

    /** Note [i] played at [course] and [fret] instead (its pitch follows). */
    fun place(song: SongAnalysis, i: Int, course: Int, fret: Int): SongAnalysis {
        val inst = song.instrument
        require(course in inst.courses.indices && fret in 0..inst.frets)
        return song.with(song.notes.mapIndexed { k, n -> if (k == i) n.copy(course = course, fret = fret, midi = inst.midiAt(course, fret)) else n })
    }

    /** Note [i] [semitones] higher (lower if negative), on its course if it reaches, else the nearest course that does. */
    fun transpose(song: SongAnalysis, i: Int, semitones: Int): SongAnalysis {
        val n = song.notes[i]
        val places = places(song.instrument, n.midi + semitones)
        if (places.isEmpty()) return song
        val (c, f) = places.firstOrNull { it.first == n.course } ?: places.minBy { abs(it.first - n.course) * 30 + abs(it.second - n.fret) }
        return place(song, i, c, f)
    }

    fun articulate(song: SongAnalysis, i: Int, a: Articulation): SongAnalysis =
        song.with(song.notes.mapIndexed { k, n -> if (k == i) n.copy(articulation = a) else n })

    fun delete(song: SongAnalysis, i: Int): SongAnalysis = song.with(song.notes.filterIndexed { k, _ -> k != i })

    /**
     * A new note after note [i] (or at the start, [i] = -1): halfway to the next note (or a beat after the last), the
     * same place as note [i], picked.
     */
    fun insertAfter(song: SongAnalysis, i: Int): SongAnalysis {
        val notes = song.notes
        val prev = notes.getOrNull(i)
        val next = notes.getOrNull(i + 1)
        val start = when {
            prev != null && next != null -> (prev.start + next.start) / 2
            prev != null -> prev.end.coerceAtLeast(prev.start + 0.25)
            next != null -> (next.start - 0.25).coerceAtLeast(0.0)
            else -> 0.0
        }
        val end = (next?.start ?: (start + 0.5)).coerceAtLeast(start + 0.05)
        val like = prev ?: next ?: SongNote(0.0, 0.0, song.instrument.midiAt(song.instrument.courses.lastIndex, 0), song.instrument.courses.lastIndex, 0)
        // The note before now ends where the new one starts.
        val trimmed = if (prev != null && prev.end > start) notes.mapIndexed { k, n -> if (k == i) n.copy(end = start) else n } else notes
        return song.with(trimmed + like.copy(start = start, end = end, articulation = Articulation.PICK))
    }
}
