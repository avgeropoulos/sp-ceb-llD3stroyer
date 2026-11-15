package com.sitarsitara.core

import com.bouzouki.core.Course
import com.bouzouki.core.Instrument
import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.SongAnalysis

/**
 * The sitar's side of Μπουζούκι's song analysis, which finds the notes: Basic Pitch on the song without its singer,
 * following the melodic line and telling notes struck from those the left hand sounds. It is asked for a line in a
 * sitar's range ([RANGE]); the course and fret it also works out are a bouzouki's and go unused.
 */
object SitarAnalysis {
    /**
     * The range the line is looked for in: from G#2 (the pancham string's open note below a Sa of C#) to C6 (a
     * fourth above the top fret), as single strings: a sitar has no octave courses for the analysis to merge.
     */
    val RANGE: Instrument = Instrument.custom(listOf(Course(44, false), Course(49, false), Course(54, false)), 30)

    /**
     * The sitar line of [song]: a note sounded without a new stroke is a meend, a tremolo is fast strokes, and the
     * pitches a meend passes through are folded into the note it comes to rest on ([foldGlides]).
     */
    fun line(song: SongAnalysis): List<LineNote> = foldGlides(song.notes.map {
        LineNote(
            it.start, it.end, it.midi,
            meend = it.articulation.isLegato,
            fast = it.articulation == Articulation.TREMOLO,
            confidence = if (song.solo) 1f else it.confidence,
        )
    })

    /** A note this short that a glide passes through is on its way, not a note (seconds). */
    const val PASSING = 0.08

    /** No more than this between one note's end and the next's start, for the pitch to be gliding (seconds). */
    const val JOINED = 0.03

    /**
     * Folds a meend's passing pitches into the note it comes to rest on. Pulling the string up from Re to Ga, the
     * analysis hears the komal Ga it passes for a few hundredths of a second, and often takes Ga for a new stroke;
     * a sitar player reads one stroke on Re and a meend to Ga. A note is a passing one when it is that short
     * ([PASSING]), the analysis didn't hear it struck or is in doubt of it, the pitch runs straight on from the note
     * before into the note after ([JOINED]), and it lies between them. The note after is then reached by meend.
     * Quick runs of struck notes, each heard clearly, are left alone.
     */
    fun foldGlides(line: List<LineNote>): List<LineNote> {
        val out = line.toMutableList()
        var i = 1
        while (i < out.size - 1) {
            val (prev, n, next) = Triple(out[i - 1], out[i], out[i + 1])
            val passing = n.duration < PASSING && (n.meend || n.confidence < 0.5f) &&
                n.start - prev.end < JOINED && next.start - n.end < JOINED &&
                (n.midi - prev.midi) * (next.midi - n.midi) > 0
            if (passing) {
                // The string sounds on through the glide: the note before lasts until it arrives.
                out[i - 1] = prev.copy(end = n.end)
                out[i + 1] = next.copy(meend = true)
                out.removeAt(i)
                i = maxOf(1, i - 1) // the note before may now be passing on the way to this one
            } else {
                i++
            }
        }
        return out
    }
}
