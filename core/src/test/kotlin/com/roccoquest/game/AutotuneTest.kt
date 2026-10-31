package com.roccoquest.game

import kotlin.math.abs
import kotlin.math.ln
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
        // A note a hair past the gentle tuning isn't worth more: it's moved only 4 semitones.
        assertEquals(Autotune.MAX_SHIFT, Autotune.shift(120f * Autotune.MAX_SHIFT * 1.01f, 120f))
        assertEquals(Autotune.MIN_SHIFT, Autotune.shift(120f * Autotune.MIN_SHIFT * 0.99f, 120f))
        assertEquals(1f, Autotune.shift(200f, 0f), "unknown pitch: left alone")
        // A voice asked to sing way above itself is moved about 6 semitones at most, never into a chipmunk.
        val sung = Autotune.fit(Autotune.tone(120f, 0.4f, 22050), 22050, 400f, 0.5f)
        val f = Autotune.pitch(sung, Chiptune.RATE)
        assertTrue(abs(f - 120f * Autotune.WIDE_MAX_SHIFT) < 8f, "sung at $f Hz")
    }

    @Test fun aNotePastTheGentleTuningIsStillInTune() {
        // 5 semitones up, one past the gentle 4: the tuning goes the extra step instead of singing it flat.
        val note = 150f * 1.335f
        val sung = Autotune.fit(Autotune.tone(150f, 0.4f, 22050), 22050, note, 0.5f)
        val f = Autotune.pitch(sung, Chiptune.RATE)
        assertTrue(semitonesOff(f, note) < 0.25f, "sung at $f Hz instead of $note Hz")
    }

    @Test fun mostNotesOnlyGetASmallNudge() {
        // Near the voice's own pitch: just the talking voices' small nudge.
        assertEquals(1.1f, Autotune.nudge(110f, 100f), 1e-4f)
        assertEquals(VoiceCast.MAX_TWEAK, Autotune.nudge(140f, 100f))
        assertEquals(VoiceCast.MIN_TWEAK, Autotune.nudge(70f, 100f))
        // Out of reach of the small nudge plus the gentle tuning: just enough more for the tuning to finish.
        assertEquals(1.7f / Autotune.MAX_SHIFT, Autotune.nudge(170f, 100f), 1e-4f)
        assertEquals(0.6f / Autotune.MIN_SHIFT, Autotune.nudge(60f, 100f), 1e-4f)
        // But never a robot.
        assertEquals(Autotune.SING_MAX_NUDGE, Autotune.nudge(400f, 100f))
        assertEquals(Autotune.SING_MIN_NUDGE, Autotune.nudge(25f, 100f))
        assertEquals(1f, Autotune.nudge(200f, 0f), "unknown voice: normal pitch")
    }

    @Test fun everyThemeNoteIsSungInTune() {
        // The theme spans 14 semitones (D5 to E6), more than a gentle nudge plus a gentle tuning can
        // cover. Every voice from a deep man's to a high woman's (typical phone voices: men about
        // 115-130 Hz, women about 190-220 Hz) sings every note within half a semitone, if the voice
        // speaks where it's asked to and the tuning does the rest.
        val tune = Chiptune.sungNotes(Songs.theme).map { it.first }
        for (hz in 90..280) {
            val voice = hz.toFloat()
            val down = Autotune.octavesDown(tune, voice)
            var gentle = 0
            for (note in tune) {
                val target = note / down
                val nudge = Autotune.nudge(target, voice)
                assertTrue(nudge in Autotune.SING_MIN_NUDGE..Autotune.SING_MAX_NUDGE, "$hz Hz voice nudged $nudge")
                val heard = voice * nudge
                val shift = Autotune.shift(target, heard)
                assertTrue(shift in Autotune.WIDE_MIN_SHIFT..Autotune.WIDE_MAX_SHIFT, "$hz Hz voice tuned $shift")
                val off = semitonesOff(heard * shift, target)
                assertTrue(off <= 0.5f, "$hz Hz voice sings the $note Hz note (/$down) $off semitones off")
                if (nudge in VoiceCast.MIN_TWEAK..VoiceCast.MAX_TWEAK && shift in Autotune.MIN_SHIFT..Autotune.MAX_SHIFT) gentle++
            }
            // ...and for the typical voices most notes still get only the gentle nudge and tuning.
            if (hz in 115..130 || hz in 190..220) {
                assertTrue(gentle >= tune.size * 3 / 4, "$hz Hz voice: only $gentle of ${tune.size} notes are gentle")
            }
        }
    }

    @Test fun theTopAndBottomNotesAreSungInTune() {
        // A woman's voice (200 Hz) used to sing the top note E6 ("far!") 1.5 semitones flat, and a
        // man's (125 Hz) the bottom note D5 ("We", "Jump") 2.3 semitones sharp. Here each word is a
        // recording made at the pitch the singer asks the voice for, then tuned as the game does.
        val tune = Chiptune.sungNotes(Songs.theme).map { it.first }
        for (voice in listOf(115f, 125f, 130f, 190f, 200f, 220f)) {
            val down = Autotune.octavesDown(tune, voice)
            for (note in listOf(tune.min(), tune.max())) {
                val target = note / down
                val word = Autotune.tone(voice * Autotune.nudge(target, voice), 0.5f, 24000)
                val sung = Autotune.fit(word, 24000, target, 0.4f)
                val f = Autotune.pitch(sung, Chiptune.RATE)
                assertTrue(semitonesOff(f, target) <= 0.5f, "$voice Hz voice sang $f Hz instead of $target Hz")
            }
        }
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

    /** How many semitones [sung] is from [note] (0 = exactly in tune, 1 = the next note up or down). */
    private fun semitonesOff(sung: Float, note: Float) = abs(12f * ln(sung / note) / ln(2f))
}
