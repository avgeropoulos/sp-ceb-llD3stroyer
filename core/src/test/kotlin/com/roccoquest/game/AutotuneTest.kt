package com.roccoquest.game

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutotuneTest {
    @Test fun findsThePitch() {
        val f = Autotune.pitch(Autotune.tone(180f, 0.4f, 22050), 22050)
        assertTrue(abs(f - 180f) < 6f, "found $f Hz")
    }

    @Test fun retunesAWordToTheNote() {
        // A "voice" at 200 Hz recorded at 24 kHz, tuned to 220 Hz and fitted into 0.3 s.
        val sung = Autotune.fit(Autotune.tone(200f, 0.5f, 24000), 24000, 220f, 0.3f)
        val f = Autotune.pitch(sung, Chiptune.RATE)
        assertTrue(abs(f - 220f) < 8f, "sung at $f Hz")
        assertTrue(sung.size <= (0.3f * Autotune.LEGATO * Chiptune.RATE).toInt() + 1)
    }

    @Test fun squeezingKeepsThePitch() {
        val voice = Autotune.tone(200f, 0.5f, Chiptune.RATE)
        val short = Autotune.squeeze(voice, 0.6f, Chiptune.RATE / 200)
        assertEquals((voice.size * 0.6f).toInt(), short.size)
        val f = Autotune.pitch(short, Chiptune.RATE)
        assertTrue(abs(f - 200f) < 6f, "squeezed voice at $f Hz")
    }

    @Test fun aLongWordKeepsItsEnding() {
        // A two-part "word" (a low part, then a higher ending) that's too long for its note: the
        // ending must still be heard, not chopped off.
        val rate = 22050
        val word = Autotune.tone(200f, 0.35f, rate) + Autotune.tone(250f, 0.25f, rate)
        val sung = Autotune.fit(word, rate, 200f, 0.3f)
        val tail = sung.copyOfRange(sung.size - (0.11f * Chiptune.RATE).toInt(), sung.size)
        val f = Autotune.pitch(tail, Chiptune.RATE)
        assertTrue(abs(f - 250f) < 12f, "the word ends at $f Hz")
    }

    @Test fun retuningIsGentle() {
        // A voice asked to sing way above itself is only moved 4 semitones, never into a chipmunk.
        val sung = Autotune.fit(Autotune.tone(120f, 0.4f, 22050), 22050, 400f, 0.5f)
        val f = Autotune.pitch(sung, Chiptune.RATE)
        assertTrue(abs(f - 120f * Autotune.MAX_SHIFT) < 8f, "sung at $f Hz")
    }

    @Test fun lowVoicesSingAnOctaveLower() {
        val tune = Chiptune.sungNotes(Songs.theme).map { it.first }
        // A man's voice (about 115 Hz) sings three octaves down, a woman's (about 210 Hz) two.
        assertEquals(8f, Autotune.octavesDown(tune, 115f))
        assertEquals(4f, Autotune.octavesDown(tune, 210f))
        assertEquals(4f, Autotune.octavesDown(tune, 0f), "unknown voice: the old two octaves")
        // Either way most notes are within reach of a small nudge from the voice and the tuning.
        for (voice in listOf(115f, 210f)) {
            val down = Autotune.octavesDown(tune, voice)
            val reachable = tune.count { (it / down / voice) in (0.84f * Autotune.MIN_SHIFT)..(1.19f * Autotune.MAX_SHIFT) }
            assertTrue(reachable >= tune.size * 9 / 10, "$voice Hz voice reaches $reachable of ${tune.size} notes")
        }
    }

    @Test fun everySungNoteHasAWord() {
        val notes = Chiptune.sungNotes(Songs.theme)
        assertEquals(Songs.themeLyrics.size, notes.size)
        assertEquals(Songs.themeLyrics.size, Songs.themeSpoken.size)
        assertTrue(Songs.themeSpoken.none { it.isBlank() || it.any { c -> c == '-' || c == ',' } })
        // The song renders with words too, at the same length as without.
        val words = notes.map { (f, s) -> Autotune.fit(Autotune.tone(150f, 0.3f, 22050), 22050, f / 4f, s) }
        assertEquals(Chiptune.render(Songs.theme).size, Chiptune.render(Songs.theme, words).size)
    }
}
