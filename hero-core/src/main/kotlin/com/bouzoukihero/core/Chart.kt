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

/** A note on the neck: tap [lane] at [time], and hold it until [end] if it's a hold note. */
class Gem(val time: Double, val end: Double, val lane: Int, val label: String, val tremolo: Boolean) {
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

object Charts {
    private val names = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    fun noteName(midi: Int): String = names[((midi % 12) + 12) % 12]

    /**
     * Turns a melody into notes for one difficulty: thins out fast passages, maps
     * pitches onto lanes (higher notes to the right) and marks long or tremolo notes
     * as hold notes. [labels] puts the note name on each note.
     */
    fun build(notes: List<SourceNote>, difficulty: Difficulty, labels: Boolean = true): List<Gem> {
        val sorted = notes.filter { it.duration >= 0 && it.time >= 0 }.sortedBy { it.time }
        if (sorted.isEmpty()) return emptyList()

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
