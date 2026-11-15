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
    /** How sure a prepared song's analysis is that this note is played, 0..1; 1 for a note written or tapped by hand. */
    val confidence: Float = 1f,
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
    /**
     * A prepared song's notes that its analysis is less sure of than this (0..1) are left out ([Charts.sure]). The
     * easier levels play few notes, so each one that isn't the bouzouki's spoils more of the tune: they ask for more.
     * (Chosen on made-up band recordings: there, up to these values, a floor takes out several of the band's notes
     * for each of the bouzouki's; a little higher it is one for one, and on recordings with no rests it only costs.)
     */
    val confidenceFloor: Float,
) {
    EASY("Easy", 3, 0.42, 0.165, 2.3, 0.95, 0.3f),
    MEDIUM("Medium", 4, 0.25, 0.135, 1.85, 0.8, 0.25f),
    HARD("Hard", 5, 0.145, 0.11, 1.5, 0.7, 0.2f),
    EXPERT("Expert", 5, 0.075, 0.085, 1.2, 0.6, 0.1f),
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
        val kept = thin(sorted, difficulty).map { sorted[it] }

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
        return tabGems(sorted, pos, difficulty, Fretboard.labels)
    }

    /**
     * A note that already has its place on the neck, from a tab (e.g. a song prepared by the
     * Μπουζούκι app): [course] counts from the lowest course. [confidence] is how sure the app is
     * that the bouzouki plays it, 0..1 (1: no doubt, or a note set by hand or from a written tab).
     */
    data class TabNote(
        val time: Double, val duration: Double, val midi: Int, val course: Int, val fret: Int, val tremolo: Boolean = false,
        val confidence: Float = 1f,
    )

    /**
     * Notes for [difficulty] from a ready-made tab, keeping its frets; [courseLabels] lowest first.
     * The notes the tab's maker is less sure of than [floor] are left out first ([sure]).
     */
    fun fromTab(
        notes: List<TabNote>, difficulty: Difficulty, courseLabels: List<String>, floor: Float = difficulty.confidenceFloor,
    ): List<Gem> {
        val sorted = sure(notes.filter { it.duration >= 0 && it.time >= 0 && it.fret >= 0 }.sortedBy { it.time }, floor)
        if (sorted.isEmpty()) return emptyList()
        return tabGems(
            sorted.map { SourceNote(it.time, it.duration, it.midi, it.tremolo, it.confidence) },
            sorted.map { Fretboard.Position(it.course, it.fret) }, difficulty, courseLabels,
        )
    }

    /**
     * [notes] without those less sure than [floor]: a note that isn't the bouzouki's (the band's, found
     * while the bouzouki rests) is a button to press where nothing is heard. A tremolo note and a note
     * without doubt (confidence 1: also every note set by hand or from a written tab) always stay.
     * A song must still be playable: one that would lose more than half of its notes, or keep fewer
     * than [MIN_SURE], is in doubt everywhere (a hard recording), and is asked for half as much, then
     * a quarter (its least sure notes are still the likeliest to be the band's), then nothing at all.
     */
    fun sure(notes: List<TabNote>, floor: Float): List<TabNote> {
        var least = min(floor, 1f)
        repeat(3) {
            if (!(least > 0f)) return notes
            // (A confidence that isn't a number counts as no doubt, as a missing one does.)
            val kept = notes.filter { it.tremolo || !(it.confidence < least) }
            if (kept.size >= MIN_SURE && kept.size * 2 >= notes.size) return kept
            least /= 2
        }
        return notes
    }

    /** The fewest notes a confidence floor may leave of a song ([sure]). */
    const val MIN_SURE = 8

    /** Course names from their open notes, lowest first; a repeated name is lowercase on the lower course (d A D). */
    fun courseLabels(openMidi: List<Int>): List<String> {
        val names = openMidi.map { noteName(it) }
        return names.mapIndexed { i, n -> if (names.drop(i + 1).contains(n)) n.lowercase() else n }
    }

    private fun tabGems(sorted: List<SourceNote>, pos: List<Fretboard.Position>, difficulty: Difficulty, labels: List<String>): List<Gem> {
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
                    labels.getOrElse(p.course) { "" } + p.fret, n.tremolo, base,
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

    /**
     * Indexes of the notes kept for [difficulty]: a note is kept only if it's far enough after
     * the last kept one. Within a run of close notes the one that ends a phrase (a pause after
     * it, or the end of the song) wins over the one before, so a run's landing note, and the
     * song's last notes, are always there.
     * Where a prepared song's analysis is much surer of one of two such notes ([SURER]) that one
     * is kept, whichever comes first, is higher or ends the phrase: the other is likely the band's.
     * Notes it is about as sure of (and every tune written by hand) are thinned by the rules above alone.
     */
    private fun thin(sorted: List<SourceNote>, difficulty: Difficulty): List<Int> {
        fun endsPhrase(i: Int) = i == sorted.size - 1 || sorted[i + 1].time - sorted[i].time >= PHRASE_GAP
        val kept = ArrayList<Int>()
        for ((i, n) in sorted.withIndex()) {
            val lastIdx = kept.lastOrNull()
            val last = lastIdx?.let { sorted[it] }
            when {
                last == null || n.time - last.time >= difficulty.minGap -> kept.add(i)
                // Too close to play both, and one of them in doubt: the sure one.
                muchSurer(n, last) -> kept[kept.size - 1] = i
                muchSurer(last, n) -> {}
                // A chord or a double-detected onset: keep the higher (melody) note.
                n.time - last.time < 0.02 && n.pitch > last.pitch -> kept[kept.size - 1] = i
                endsPhrase(i) && !endsPhrase(lastIdx) -> kept[kept.size - 1] = i
            }
        }
        return kept
    }

    /**
     * How much surer (of 0..1) the analysis must be of one of two notes too close to play both for
     * that one to be kept. Wide on purpose: most notes are the bouzouki's whatever their confidence,
     * and choosing between two of them by a small difference only moves the buttons off the beat.
     */
    private const val SURER = 0.5f

    // (Confidences come in hundredths: half of one is left for the rounding.)
    private fun muchSurer(a: SourceNote, b: SourceNote) = a.confidence - b.confidence > SURER - 0.005f

    /** A pause at least this long after a note ends its phrase. */
    private const val PHRASE_GAP = 0.6

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
