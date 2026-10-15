package com.bouzouki.core.song

import com.bouzouki.core.Instrument

/**
 * One note of a tab a player pasted in: its course and fret, its column (position across the tab,
 * left to right, used as a rough rhythm), and how it's played.
 */
data class TabNote(val course: Int, val fret: Int, val column: Int, val articulation: Articulation = Articulation.PICK)

/** A tab read from text: its notes in playing order, and anything that couldn't be read. */
data class ParsedTab(val instrument: Instrument, val notes: List<TabNote>, val warnings: List<String> = emptyList())

/** Reads bouzouki tabs as people post them online (plain-text tab lines, one per course). */
object TabParser {
    fun parse(text: String, instrument: Instrument): ParsedTab =
        ParsedTab(instrument, emptyList(), listOf("Tab reading isn't available yet"))
}

/**
 * A tab lined up with the recording: [song] is the song's line made of the tab's notes at the times
 * they're heard, for the bouzouki of the song given to [TabAligner.align] (the player's), to show as it
 * is: its notes keep the tab's course and fret where that bouzouki has the tab's string.
 * [matched] of the tab's [total] notes were found in the recording, [missing] lists the indices (in the
 * tab) of the others: those inside a part that was found are still in [song], where the notes around
 * them put them. [sources] gives, for each note of [song], the index in the tab it comes from, or -1 for
 * a note of the prepared line kept where the tab isn't heard. The recording sits [transpose] semitones
 * above the tab (a song in another key: the notes are moved by it) and [octave] octaves above it (kept
 * as the tab writes it). [differs] lists the tab notes heard only at another pitch (a wrong fret, or an
 * octave off).
 */
class TabAlignment(
    val song: SongAnalysis,
    val matched: Int,
    val total: Int,
    val missing: List<Int>,
    val sources: List<Int> = emptyList(),
    val transpose: Int = 0,
    val octave: Int = 0,
    val differs: List<Int> = emptyList(),
)

/** Lines a [ParsedTab] up with a prepared song's [AnalysisData]. */
object TabAligner {
    /**
     * [base] is the prepared song, fingered for the bouzouki the player plays: notes keep the tab's course
     * and fret where it has them, else they're fingered for it, and the prepared line's notes stay where
     * the tab isn't heard.
     */
    fun align(tab: ParsedTab, data: AnalysisData, base: SongAnalysis): TabAlignment =
        TabAlignment(base, 0, tab.notes.size, tab.notes.indices.toList(), List(base.notes.size) { -1 })
}
