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
            // In tune: the wave repeats every 1/55 s. Find the repeat length that fits best.
            val x = FloatArray(8000) { out[(11025 + it) * 2] }
            val best = (600..1000).maxByOrNull { lag -> var c = 0.0; for (i in 0 until 6000) c += x[i] * x[i + lag]; c }!!
            assertTrue(abs(44100.0 / best - 55.0) < 0.4, "$sound plays ${44100.0 / best} Hz")
            // A held string keeps ringing (except a muted one).
            if (sound == BassSound.FINGER || sound == BassSound.PICK) assertTrue(peak(out.copyOfRange(40000, 44100)) > 0.05f, "$sound died")
            s.noteOff(33)
            val tail = FloatArray(44100 * 2)
            // In blocks, as the sound card asks for it.
            val block = FloatArray(2048)
            for (b in 0 until 43) { block.fill(0f); s.render(block, 1024, 1f); block.copyInto(tail, b * 2048) }
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
