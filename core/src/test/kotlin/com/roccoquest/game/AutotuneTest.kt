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
        assertTrue(sung.size <= (0.3f * Chiptune.RATE).toInt() + 1)
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
