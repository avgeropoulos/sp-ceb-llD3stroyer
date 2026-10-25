package com.roccoquest.game

import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeSongTest {
    /** The sung theme song renders cleanly: not too loud (no crackle) and not too quiet. */
    @Test fun singingIsCleanAndAudible() {
        val pcm = Chiptune.render(Songs.theme)
        val clipped = pcm.count { it >= 29990 || it <= -29990 }
        assertTrue(clipped < pcm.size / 1000, "clipped $clipped samples")
        val rms = kotlin.math.sqrt(pcm.map { it.toDouble() * it }.average())
        assertTrue(rms in 2500.0..9000.0, "loudness $rms")
        assertTrue(Songs.themeLyrics.size == Songs.theme.vowels.length, "a vowel for every sung syllable")
    }
}
