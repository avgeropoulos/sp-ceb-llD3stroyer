package com.bouzouki

import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.SongAnalysis
import kotlin.math.abs

/** Seconds of silence after which a legato note no longer counts as joined to the note before. */
const val LEGATO_GAP = 0.5

/** Most frets a hammer-on or pull-off spans: the fingers' reach. */
const val FINGER_REACH = 5

/** Most frets a slide covers. */
const val SLIDE_REACH = 12

/**
 * How note [i] is sounded from the note before without a new pick: [Articulation.HAMMER],
 * [Articulation.PULL] or [Articulation.SLIDE], or null when it's picked. A note marked legato is
 * only played so when the hand can: straight on from the note before (within [LEGATO_GAP]) on the
 * same course, a hammer-on to a higher fret or a pull-off to a lower one within [FINGER_REACH]
 * frets, a slide between two stopped frets within [SLIDE_REACH]. A slide to or from the open
 * string is the pull-off or hammer-on it has to be. Anything else (another course, or an interval
 * turned round when the song moved to the other bouzouki) is picked, by the tab, the neck and the
 * bouzouki sound alike.
 */
fun SongAnalysis.legatoInto(i: Int): Articulation? {
    if (i <= 0 || i >= notes.size) return null
    val n = notes[i]
    val p = notes[i - 1]
    if (!n.articulation.isLegato || n.course != p.course || n.start - p.end >= LEGATO_GAP) return null
    val d = n.fret - p.fret
    val up = d in 1..FINGER_REACH
    val down = -d in 1..FINGER_REACH
    return when (n.articulation) {
        Articulation.HAMMER -> if (up) Articulation.HAMMER else null
        Articulation.PULL -> if (down) Articulation.PULL else null
        else -> when { // a slide
            d == 0 || abs(d) > SLIDE_REACH -> null
            n.fret > 0 && p.fret > 0 -> Articulation.SLIDE
            up -> Articulation.HAMMER // from the open string
            down -> Articulation.PULL // to it
            else -> null
        }
    }
}

/** Whether note [i] is sounded from the note before without a new pick (see [legatoInto]). */
fun SongAnalysis.joinsPrevious(i: Int): Boolean = legatoInto(i) != null
