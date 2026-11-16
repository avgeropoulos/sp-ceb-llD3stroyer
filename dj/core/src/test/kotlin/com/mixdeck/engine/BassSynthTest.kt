package com.mixdeck.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BassSynthTest {
    private fun peak(b: FloatArray) = b.maxOf { abs(it) }

    @Test fun scalesFollowTheSongsKey() {
        // A minor: A B C D E F G A, from A1 (33).
        assertContentEquals(intArrayOf(33, 35, 36, 38, 40, 41, 43, 45), BassSynth.scale(MusicalKey(9, true)))
        // C major from C2 (36); the high octave is 12 up; no key = A minor.
        assertContentEquals(intArrayOf(36, 38, 40, 41, 43, 45, 47, 48), BassSynth.scale(MusicalKey(0, false)))
        assertEquals(48, BassSynth.scale(MusicalKey(0, false), 1)[0])
        assertContentEquals(BassSynth.scale(MusicalKey(9, true)), BassSynth.scale(null))
        assertEquals("A", BassSynth.noteName(33))
    }

    @Test fun aNoteSoundsAtItsPitchAndFadesWhenLetGo() {
        for (sound in BassSound.values()) {
            val s = BassSynth(44100).apply { this.sound = sound }
            val out = FloatArray(44100 * 2)
            s.noteOn(33)                         // A1 = 55 Hz
            s.render(out, 22050, 1f)
            assertTrue(peak(out) > 0.1f && peak(out) <= 1f, "$sound peak ${peak(out)}")
            assertTrue(out.all { it.isFinite() })
            // No click at the start: the first samples are quiet.
            assertTrue(abs(out[0]) < 0.05f, "$sound starts at ${out[0]}")
            // About 55 Hz: count upward zero crossings over the second quarter second (SUB is the cleanest).
            if (sound == BassSound.SUB) {
                var n = 0
                for (i in 11025 until 22049) if (out[i * 2] <= 0 && out[i * 2 + 2] > 0) n++
                assertTrue(n in 13..15, "crossings $n")
            }
            s.noteOff(33)
            val tail = FloatArray(44100 * 2)
            s.render(tail, 44100, 1f)
            assertTrue(peak(tail.copyOfRange(44100, 88200)) < 0.001f, "$sound still sounds")
            assertFalse(s.active)
        }
    }

    @Test fun mixerAddsTheBassAndRecordsIt() {
        val m = Mixer(44100)
        var tapped = 0f
        m.tap = { buf, frames -> tapped = maxOf(tapped, peak(buf.copyOf(frames * 2))) }
        val out = FloatArray(4096)
        m.bass.noteOn(40); m.render(out, 2048)
        assertTrue(peak(out) > 0.05f); assertTrue(tapped > 0.05f)
    }

    @Test fun moreNotesThanVoicesNeverBreaks() {
        val s = BassSynth(44100)
        val out = FloatArray(2048)
        for (n in 30..45) { s.noteOn(n); s.render(out, 1024, 1f) }
        s.allOff()
        repeat(100) { s.render(out, 1024, 1f) }
        assertFalse(s.active)
    }
}
