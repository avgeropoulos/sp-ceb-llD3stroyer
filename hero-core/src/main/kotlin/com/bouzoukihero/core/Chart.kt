package com.bouzoukihero.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * One note of a song's melody, in seconds from the start of the audio or video.
 * [pitch] is a MIDI note number (or, for charts tapped in by hand, the lane that was tapped).
 */
data class SourceNote(
    val time: Double,
    val duration: Double,
    val pitch: Int,
    val tremolo: Boolean = false,
)

enum class Difficulty(
    val label: String,
    /** How many buttons (lanes) there are. */
    val lanes: Int,
    /** Notes closer together than this are thinned out. */
    val minGap: Double,
    /** How far from the note a tap still counts, in seconds either side. */
    val window: Double,
    /** How long a note takes to travel down the neck. */
    val lookahead: Double,
    /** Notes held at least this long become hold notes. */
    val holdMin: Double,
) {
    EASY("Easy", 3, 0.42, 0.165, 2.3, 0.95),
    MEDIUM("Medium", 4, 0.25, 0.135, 1.85, 0.8),
    HARD("Hard", 5, 0.145, 0.11, 1.5, 0.7),
    EXPERT("Expert", 5, 0.075, 0.085, 1.2, 0.6),
}

/**
 * A note on the neck: tap [lane] at [time], and hold it until [end] if it's a hold note.
 * With tab buttons, [base] is the fret under the first finger at that moment.
 */
class Gem(val time: Double, val end: Double, val lane: Int, val label: String, val tremolo: Boolean, val base: Int = -1) {
    val isHold get() = end > time
    var judged: Judgement? = null
    var holding = false
    var holdBroken = false
    var holdDone = false
    /** Time the hold was let go (or completed); the tail is drawn from here. */
    var holdTo = 0.0
}

enum class Judgement(val label: String, val points: Int, val color: Int) {
    PERFECT("PERFECT", 100, 0xFFFFE15A.toInt()),
    GREAT("GREAT", 70, 0xFF7CF29A.toInt()),
    GOOD("GOOD", 40, 0xFF7CC8FF.toInt()),
    MISS("MISS", 0, 0xFFFF5A5A.toInt()),
}

/** What the buttons stand for. */
enum class Buttons {
    /** The tab, as a player's hand plays it: open string, then fingers 1-4 in the current position. */
    TAB,

    /** Pitch: lower notes on the left, higher on the right. */
    NOTES,
}

object Charts {
    private val names = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    fun noteName(midi: Int): String = names[((midi % 12) + 12) % 12]

    /**
     * Turns a melody into notes for one difficulty: thins out fast passages, maps
     * pitches onto lanes (higher notes to the right) and marks long or tremolo notes
     * as hold notes. [labels] puts the note name on each note.
     */
    fun build(notes: List<SourceNote>, difficulty: Difficulty, labels: Boolean = true, buttons: Buttons = Buttons.NOTES): List<Gem> {
        val sorted = notes.filter { it.duration >= 0 && it.time >= 0 }.sortedBy { it.time }
        if (sorted.isEmpty()) return emptyList()
        // Charts tapped in by hand have no real pitches, so no tab.
        if (buttons == Buttons.TAB && labels) return buildTab(sorted, difficulty)

        // Thin out: keep a note only if it's far enough after the last kept one. Within a
        // run of close notes, prefer a change of pitch to a repeated one.
        val kept = ArrayList<SourceNote>()
        for (n in sorted) {
            val last = kept.lastOrNull()
            if (last == null || n.time - last.time >= difficulty.minGap) {
                kept.add(n)
            } else if (n.time - last.time < 0.02 && n.pitch > last.pitch) {
                // A chord or a double-detected onset: keep the higher (melody) note.
                kept[kept.size - 1] = n
            }
        }

        val laneOf = laneMap(kept.map { it.pitch }, difficulty.lanes)
        val gems = ArrayList<Gem>(kept.size)
        var prevLane = -1
        var prevPitch = Int.MIN_VALUE
        for ((i, n) in kept.withIndex()) {
            var lane = laneOf.getValue(n.pitch)
            // Keep the melody's shape: when the tune goes up, your finger moves right (and
            // left when it goes down), even where several notes share a lane.
            if (prevLane >= 0 && n.pitch != prevPitch) {
                val dir = (n.pitch - prevPitch).sign
                if ((lane - prevLane).sign != dir) lane = (prevLane + dir).coerceIn(0, difficulty.lanes - 1)
            }
            val next = kept.getOrNull(i + 1)?.time ?: Double.MAX_VALUE
            val longEnough = n.duration >= difficulty.holdMin || (n.tremolo && n.duration >= 0.45)
            val end = if (longEnough) min(n.time + n.duration, next - 0.12) else n.time
            gems.add(Gem(n.time, if (end - n.time >= 0.25) end else n.time, lane, if (labels) noteName(n.pitch) else "", n.tremolo))
            prevLane = lane
            prevPitch = n.pitch
        }
        return gems
    }

    /**
     * Buttons from the tab, the way Bouzouki Tabs writes it: every note gets a course and
     * fret for the whole melody at once, then the button is the finger that plays it
     * (open string, or finger 1-4 above the hand's current position).
     */
    private fun buildTab(sorted: List<SourceNote>, difficulty: Difficulty): List<Gem> {
        val pitches = sorted.map { Fretboard.fitToRange(it.pitch) }
        val pos = Fretboard.assign(pitches, sorted.map { it.time })
        val keep = thin(sorted, difficulty)
        val gems = ArrayList<Gem>(keep.size)
        var base = pos.firstOrNull { it.fret > 0 }?.fret ?: 1
        for ((k, i) in keep.withIndex()) {
            val n = sorted[i]
            val p = pos[i]
            // The hand moves only when a note is out of its four-fret reach.
            if (p.fret > 0) {
                if (p.fret < base) base = p.fret
                if (p.fret > base + 3) base = p.fret - 3
            }
            val finger = if (p.fret == 0) 0 else p.fret - base + 1
            val next = keep.getOrNull(k + 1)?.let { sorted[it].time } ?: Double.MAX_VALUE
            gems.add(
                Gem(
                    n.time, holdEnd(n, next, difficulty), FINGER_LANES[difficulty.lanes]!![finger],
                    Fretboard.labels[p.course] + p.fret, n.tremolo, base,
                )
            )
        }
        return gems
    }

    /** Which button each finger (0 = open string, 1-4) uses, for 3, 4 or 5 buttons. */
    val FINGER_LANES = mapOf(
        5 to intArrayOf(0, 1, 2, 3, 4),
        4 to intArrayOf(0, 1, 2, 3, 3),
        3 to intArrayOf(0, 0, 1, 2, 2),
    )

    /** What a tab button stands for right now, e.g. "0", "5" or "7-8", with the hand at [base]. */
    fun tabButtonLabel(lane: Int, lanes: Int, base: Int): String {
        val fingers = FINGER_LANES[lanes]!!.withIndex().filter { it.value == lane }.map { it.index }
        val frets = fingers.map { if (it == 0) 0 else base + it - 1 }
        return if (frets.size == 1) "${frets[0]}" else "${frets.first()}-${frets.last()}"
    }

    /** Indexes of the notes kept for [difficulty] (fast passages thinned out). */
    private fun thin(sorted: List<SourceNote>, difficulty: Difficulty): List<Int> {
        val kept = ArrayList<Int>()
        for ((i, n) in sorted.withIndex()) {
            val last = kept.lastOrNull()?.let { sorted[it] }
            if (last == null || n.time - last.time >= difficulty.minGap) kept.add(i)
            else if (n.time - last.time < 0.02 && n.pitch > last.pitch) kept[kept.size - 1] = i
        }
        return kept
    }

    private fun holdEnd(n: SourceNote, next: Double, difficulty: Difficulty): Double {
        val longEnough = n.duration >= difficulty.holdMin || (n.tremolo && n.duration >= 0.45)
        val end = if (longEnough) min(n.time + n.duration, next - 0.12) else n.time
        return if (end - n.time >= 0.25) end else n.time
    }

    /** Spreads the distinct pitches evenly over the lanes, lowest on the left. */
    private fun laneMap(pitches: List<Int>, lanes: Int): Map<Int, Int> {
        val distinct = pitches.distinct().sorted()
        val n = distinct.size
        return distinct.withIndex().associate { (rank, p) ->
            p to when {
                n == 1 -> lanes / 2
                n <= lanes -> (rank * (lanes - 1).toDouble() / (n - 1)).roundToInt()
                else -> min(lanes - 1, rank * lanes / n)
            }
        }
    }

    /** End of the last note, for knowing when a song is over. */
    fun lastTime(gems: List<Gem>): Double = gems.maxOfOrNull { max(it.time, it.end) } ?: 0.0
}
