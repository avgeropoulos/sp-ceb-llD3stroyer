package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The bouzouki's post-processing of the transcribed notes, and its settings (all of them here, none hard-coded
 * elsewhere): the octave-paired courses' two pitches made one note ([mergeOctaves]), tremolo collapsed into one held
 * note (in [SongAnalyzer.bouzoukiLine]), and what calibration measured ([PostConfig.calibrated]).
 */
data class PostConfig(
    /**
     * Merge the two pitches an octave-paired course sounds into one note (on that course's pitch). Null: when nothing
     * else in the recording may sound an octave below the bouzouki (a solo take, or the bouzouki's stem once separation
     * has taken the bass out: [Separator.keepsBass]); in a band's mix the bass, a voice or another plucked instrument
     * an octave below are too common.
     */
    val octaveMerge: Boolean? = null,
    /** Seconds between the two pitches' onsets for them to be one pluck. */
    val octaveWindow: Double = 0.04,
    /**
     * Move an upper note down when only it was transcribed but the lower pitch's onset is there too (null: in a solo
     * take only: even without a bass, a band's stem may have a second plucked instrument an octave below).
     */
    val octaveFromOnset: Boolean? = null,
    /** The onset activation the lower pitch needs at the pluck, when only the upper one was transcribed as a note. */
    val octaveLowerOnset: Double = 0.3,
    /** ... and its note activation. */
    val octaveLowerLevel: Double = 0.2,
    /**
     * How much louder (note activation) the octave string may sound than the course's own string and still be taken
     * for the pair: calibration measures the bouzouki's own ratio ([calibrated]). On the synthetic set a pair's pluck
     * is 0.24-0.95 (10th to 90th percentile, median 0.52), a note on a unison course over the pitch below it 0.98-8.4
     * (median 6.8), with the lower pitch's onset at a median 0.85 against 0.14.
     */
    val octaveMaxRatio: Double = 2.0,
    /** Tremolo: this many picks of one pitch or more ... */
    val tremoloPicks: Int = 5,
    /** ... each within this many seconds of the last ... */
    val tremoloReach: Double = 0.4,
    /** ... at a median interval of this many seconds or less, become one held note marked tremolo. */
    val tremoloGap: Double = 0.1,
    /** Candidates whose loudness (note activation) stays under this are left out: noise, as calibration measured it. */
    val noiseFloor: Double = 0.0,
    /**
     * The bouzouki plays alone (a practice take, not a band recording): the band's cues, which keep the line off the
     * accompaniment's low register, are off, so a melody on the bass courses is followed.
     */
    val solo: Boolean = false,
) {
    /**
     * These settings with what calibration measured: the octave strings' loudness against their courses' (the merge
     * then allows up to twice that), and the noise floor (activation) of the room and the microphone.
     */
    fun calibrated(octaveRatio: Double?, noise: Double?): PostConfig = copy(
        octaveMaxRatio = octaveRatio?.takeIf { it.isFinite() && it > 0 }?.let { max(1.5, 2 * it) } ?: octaveMaxRatio,
        noiseFloor = noise?.takeIf { it.isFinite() && it >= 0 }?.let { min(0.25, it) } ?: noiseFloor,
    )

    /** These settings for a recording made through [separator]: whether octaves merge decided ([octaveMerge] not null). */
    fun resolvedFor(separator: Separator): PostConfig = if (octaveMerge != null) this else copy(octaveMerge = solo || !separator.keepsBass)

    companion object {
        val DEFAULT = PostConfig()
    }
}

object PostProcess {
    /**
     * One pluck of an octave-paired course sounds its pitch and the octave above: such pairs become one note, on the
     * course's pitch (the lower), with the stronger evidence of the two. Two candidates an octave apart whose onsets
     * are within [PostConfig.octaveWindow], the lower one playable on an octave-paired course and plucked (its onset
     * [PostConfig.octaveLowerOnset] or more), are merged, unless other notes start with them (a strum); an upper
     * candidate alone is moved down when the lower pitch's onset and level are there at the same moment
     * ([PostConfig.octaveLowerOnset], [PostConfig.octaveLowerLevel]). Either way the upper pitch may sound at most
     * [PostConfig.octaveMaxRatio] times louder than the lower: a weaker lower pitch is a ghost of the upper note.
     */
    fun mergeOctaves(c: List<NoteEvent>, instrument: Instrument, act: BasicPitch.Activations?, post: PostConfig): List<NoteEvent> {
        if (post.octaveMerge != true) return c
        val pairs = instrument.courses.filter { it.octavePair }
        if (pairs.isEmpty()) return c
        fun onPair(m: Int) = pairs.any { m - it.openMidi in 0..instrument.frets }
        val notes = c.sortedBy { it.start }.toMutableList()
        val gone = BooleanArray(notes.size)
        val out = arrayOfNulls<NoteEvent>(notes.size)
        for (i in notes.indices) out[i] = notes[i]
        for ((i, u) in notes.withIndex()) {
            if (gone[i] || u.onset.isNaN() || !onPair(u.midi - 12)) continue
            // The lower pitch as a candidate of its own, starting with this one.
            var best = -1
            var k = i - 1
            while (k >= 0 && u.start - notes[k].start <= post.octaveWindow) { if (!gone[k] && notes[k].midi == u.midi - 12) best = k; k-- }
            k = i + 1
            while (k < notes.size && notes[k].start - u.start <= post.octaveWindow) {
                if (!gone[k] && notes[k].midi == u.midi - 12 && (best < 0 || abs(notes[k].start - u.start) < abs(notes[best].start - u.start))) best = k
                k++
            }
            // A strum (two or more other notes starting with them) isn't one course's pluck: its octaves are two courses'.
            var others = 0
            k = i - 1
            while (k >= 0 && u.start - notes[k].start <= post.octaveWindow) { if (k != best && notes[k].midi != u.midi) others++; k-- }
            k = i + 1
            while (k < notes.size && notes[k].start - u.start <= post.octaveWindow) { if (k != best && notes[k].midi != u.midi) others++; k++ }
            if (others >= 2) continue
            if (best >= 0) {
                val l = out[best]!!
                // A lower note much weaker than the upper one, or without a pluck of its own, is a ghost of it (or
                // another instrument's), not the pair.
                if (u.salience > post.octaveMaxRatio * l.salience || l.onset < post.octaveLowerOnset) continue
                out[best] = l.copy(
                    end = max(l.end, u.end), amplitude = max(l.amplitude, u.amplitude),
                    onset = max(l.onset, u.onset), salience = max(l.salience, u.salience),
                    endFrame = max(l.endFrame, u.endFrame),
                )
                gone[i] = true
                continue
            }
            // Only the upper pitch was transcribed: the lower one plucked with it shows as an onset at its key.
            if (act == null || u.frame < 0 || !(post.octaveFromOnset ?: post.solo)) continue
            val low = u.midi - 12 - BasicPitch.MIDI_OFFSET
            if (low < 0) continue
            val n = act.onsets.size
            var lowerOnset = 0f
            for (f in max(0, u.frame - 2)..min(n - 1, u.frame + 2)) lowerOnset = max(lowerOnset, act.onsets[f][low])
            if (lowerOnset < post.octaveLowerOnset) continue
            var lowerLevel = 0f
            var upperLevel = 0f
            for (f in u.frame until min(n, u.frame + 6)) {
                lowerLevel = max(lowerLevel, act.frames[f][low])
                upperLevel = max(upperLevel, act.frames[f][low + 12])
            }
            if (lowerLevel < post.octaveLowerLevel || upperLevel / lowerLevel > post.octaveMaxRatio) continue
            out[i] = u.copy(midi = u.midi - 12)
        }
        return notes.indices.filter { !gone[it] }.map { out[it]!! }.sortedBy { it.start }
    }

    /** Candidates louder than the noise floor (calibration's); notes made by hand are kept. */
    fun aboveNoise(c: List<NoteEvent>, post: PostConfig): List<NoteEvent> =
        if (post.noiseFloor <= 0) c else c.filter { it.onset.isNaN() || it.salience > post.noiseFloor }
}
