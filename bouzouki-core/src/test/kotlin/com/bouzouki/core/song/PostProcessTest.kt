package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.test.Test
import kotlin.test.assertEquals

class PostProcessTest {
    private val tetra = Instrument.TETRACHORDO
    private fun n(t: Double, midi: Int, salience: Double = 0.8, onset: Double = 0.9) =
        NoteEvent(t, t + 0.3, midi, salience, onset, salience)

    @Test
    fun anOctavePairsPluckIsOneNoteOnTheCoursesPitch() {
        // C3 + C4 (the C course, open), then F3 + F4 a little apart (the F course), then D4 alone (the D course).
        val c = listOf(n(0.0, 60, 0.7), n(0.01, 48, 0.8), n(0.5, 53, 0.6), n(0.52, 65, 0.9), n(1.0, 62))
        val merged = PostProcess.mergeOctaves(c, tetra, null, PostConfig(octaveMerge = true))
        assertEquals(listOf(48, 53, 62), merged.map { it.midi })
        // The merged note keeps the stronger evidence of the two.
        assertEquals(0.9, merged[1].salience, 1e-9)
    }

    @Test
    fun aGhostAnOctaveBelowIsNotAPair() {
        // A loud A4 with a faint A3 at its onset: the ghost doesn't pull the note down (it is ratio-checked).
        val c = listOf(n(0.0, 69, 0.9), n(0.0, 57, 0.2))
        assertEquals(listOf(57, 69), PostProcess.mergeOctaves(c, tetra, null, PostConfig(octaveMerge = true)).map { it.midi }.sorted())
        // Two octaves apart, or apart in time: not a pair either.
        val far = listOf(n(0.0, 72), n(0.0, 48), n(1.0, 60), n(1.2, 48))
        assertEquals(4, PostProcess.mergeOctaves(far, tetra, null, PostConfig(octaveMerge = true)).size)
    }

    @Test
    fun notOnAPairCourseOrSwitchedOff() {
        // A3 + A4: the F course's fret 4 on a tetrachordo, the D course's fret 7 on a trichordo; switched off, two notes.
        val c = listOf(n(0.0, 57), n(0.0, 69))
        assertEquals(1, PostProcess.mergeOctaves(c, tetra, null, PostConfig(octaveMerge = true)).size)
        assertEquals(1, PostProcess.mergeOctaves(c, Instrument.TRICHORDO, null, PostConfig(octaveMerge = true)).size)
        assertEquals(2, PostProcess.mergeOctaves(c, tetra, null, PostConfig(octaveMerge = false)).size)
        // In a strum (four notes at once) the octave is two courses', and an unplucked lower note is a ghost.
        assertEquals(4, PostProcess.mergeOctaves(c + n(0.0, 64) + n(0.0, 61), tetra, null, PostConfig(octaveMerge = true)).size)
        assertEquals(2, PostProcess.mergeOctaves(listOf(n(0.0, 69), n(0.0, 57, onset = 0.1)), tetra, null, PostConfig(octaveMerge = true)).size)
    }

    @Test
    fun calibrationSetsTheRatioAndTheNoiseFloor() {
        val p = PostConfig().calibrated(octaveRatio = 1.4, noise = 0.05)
        assertEquals(2.8, p.octaveMaxRatio, 1e-9)
        assertEquals(0.05, p.noiseFloor, 1e-9)
        assertEquals(PostConfig(), PostConfig().calibrated(null, null))
        // Merged by default only where nothing else sounds an octave below.
        assertEquals(true, PostConfig(solo = true).resolvedFor(NoSeparation).octaveMerge)
        assertEquals(false, PostConfig().resolvedFor(NoSeparation).octaveMerge)
        val quiet = listOf(n(0.0, 62, 0.04), n(0.5, 64, 0.5))
        assertEquals(listOf(64), PostProcess.aboveNoise(quiet, p).map { it.midi })
    }

    @Test
    fun tremoloNeedsAsManyPicksAsConfigured() {
        // Six fast picks of D4 (12 a second): tremolo by default; not when 8 picks are asked for.
        val picks = (0 until 6).map { NoteEvent(it / 12.0, it / 12.0 + 0.08, 62, 0.8, 0.9, 0.8) }
        fun line(p: PostConfig) = SongAnalyzer.bouzoukiLine(picks, tetra, post = p)
        assertEquals(listOf(Articulation.TREMOLO), line(PostConfig(octaveMerge = true)).map { it.articulation })
        assertEquals(6, line(PostConfig(tremoloPicks = 8)).size)
    }
}
