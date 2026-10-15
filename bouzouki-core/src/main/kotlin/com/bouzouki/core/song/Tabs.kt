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
 * they're heard; [matched] of the tab's [total] notes were found in the recording, [missing] lists the
 * indices (in the tab) of the others.
 */
class TabAlignment(val song: SongAnalysis, val matched: Int, val total: Int, val missing: List<Int>)

/** Lines a [ParsedTab] up with a prepared song's [AnalysisData]. */
object TabAligner {
    fun align(tab: ParsedTab, data: AnalysisData, base: SongAnalysis): TabAlignment =
        TabAlignment(base, 0, tab.notes.size, tab.notes.indices.toList())
}
