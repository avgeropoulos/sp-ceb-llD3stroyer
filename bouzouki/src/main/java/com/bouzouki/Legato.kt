package com.bouzouki

import com.bouzouki.core.song.SongAnalysis

/** Seconds of silence after which a legato note no longer counts as joined to the note before. */
const val LEGATO_GAP = 0.5

/**
 * Whether note [i] is sounded from the note before without a new pick: a hammer-on, pull-off or
 * slide straight on from it, on the same course. One marked legato on another course can't be
 * played that way, so the tab, the neck and the bouzouki sound all treat it as picked.
 */
fun SongAnalysis.joinsPrevious(i: Int): Boolean {
    if (i <= 0 || i >= notes.size) return false
    val n = notes[i]
    val p = notes[i - 1]
    return n.articulation.isLegato && n.course == p.course && n.start - p.end < LEGATO_GAP
}
