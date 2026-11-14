package com.mixdeck.ui

import com.mixdeck.engine.Analyzer
import com.mixdeck.engine.AutoMix
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.ShortArrayPcm
import com.mixdeck.ui.PickCheck.Option
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PickCheckTest {
    private fun song(secs: Int): ShortArrayPcm {
        val sr = 8000; val n = sr * secs; val x = FloatArray(n)
        for (i in 0 until n) { val t = i.toDouble() / sr; val tb = t % 0.5; x[i] = (sin(2 * PI * 60 * tb) * exp(-tb * 8) * 0.7).toFloat() }
        return ShortArrayPcm.fromFloats(x, x, sr)
    }

    private fun Mixer.load(i: Int, secs: Int, title: String) {
        val p = song(secs)
        decks[i].load(p, Analyzer.analyze(p), title, "Nelly", -14.0)
    }

    /** Puts deck [i] [left] seconds before its end. */
    private fun Mixer.leave(i: Int, left: Double) {
        val d = decks[i]
        d.seekFraction((d.durationSec - left) / d.durationSec)
    }

    @Test fun targetNotPlayingAsksNothing() {
        val m = Mixer(8000)
        assertNull(PickCheck.question(m, 0, "Grillz"))
        m.load(0, 30, "Air Force Ones")
        assertNull(PickCheck.question(m, 0, "Grillz"))
        // The other deck playing doesn't matter: the target is free.
        m.load(1, 30, "Yeah!"); m.decks[1].play()
        assertNull(PickCheck.question(m, 0, "Grillz"))
    }

    @Test fun holdingCueIsNotPlaying() {
        val m = Mixer(8000)
        m.load(0, 30, "Air Force Ones")
        m.decks[0].cueDown(quantize = false)
        assertTrue(m.decks[0].previewing)
        assertNull(PickCheck.question(m, 0, "Grillz"))
    }

    @Test fun targetPlayingOtherEmptyOffersTheOtherDeck() {
        val m = Mixer(8000)
        m.load(0, 120, "Air Force Ones"); m.leave(0, 72.4); m.decks[0].play()
        val q = PickCheck.question(m, 0, "Grillz")!!
        assertEquals("Deck A is playing (1:12 left)", q.title)
        assertEquals("Load \"Grillz\" on deck B instead? Deck B isn't playing.", q.message)
        assertEquals(listOf(Option.OTHER_DECK, Option.REPLACE, Option.CANCEL), q.options)
        assertEquals(listOf("Load on deck B", "Stop A and load", "Cancel"), q.labelList)
    }

    @Test fun targetPlayingOtherHoldsASong() {
        val m = Mixer(8000)
        m.load(0, 120, "Yeah!")
        m.load(1, 120, "Air Force Ones"); m.leave(1, 72.4); m.decks[1].play()
        val q = PickCheck.question(m, 1, "Grillz")!!
        assertEquals("Deck B is playing (1:12 left)", q.title)
        assertEquals("Load \"Grillz\" on deck A instead? Deck A isn't playing; \"Yeah!\" comes off it.", q.message)
        assertEquals(listOf("Load on deck A", "Stop B and load", "Cancel"), q.labelList)
    }

    @Test fun bothPlayingOffersWhenItEnds() {
        val m = Mixer(8000)
        m.load(0, 120, "Air Force Ones"); m.leave(0, 40.5); m.decks[0].play()
        m.load(1, 120, "Yeah!"); m.decks[1].play()
        val q = PickCheck.question(m, 0, "Grillz")!!
        assertEquals("Both decks are playing", q.title)
        assertEquals("Deck A ends in 0:40. Load \"Grillz\" there when it ends?", q.message)
        assertEquals(listOf(Option.WHEN_ENDS, Option.REPLACE, Option.CANCEL), q.options)
        assertEquals(listOf("Load when A ends", "Stop A and load now", "Cancel"), q.labelList)
    }

    /**
     * A song that still has to download can't stop A now. With deck B free, "Stop A and load"
     * becomes "Load when A ends" (no random cut later); with both decks playing it stops A after
     * the download, and says so (deck B keeps the music going).
     */
    @Test fun aSongToDownloadSaysWhenItLoads() {
        val m = Mixer(8000)
        m.load(0, 120, "Air Force Ones"); m.leave(0, 72.4); m.decks[0].play()
        val one = PickCheck.question(m, 0, "Grillz", needsDownload = true)!!
        assertEquals(listOf(Option.OTHER_DECK, Option.WHEN_ENDS, Option.CANCEL), one.options)
        assertEquals(listOf("Load on deck B", "Load when A ends", "Cancel"), one.labelList)
        m.load(1, 120, "Yeah!"); m.decks[1].play()
        val both = PickCheck.question(m, 0, "Grillz", needsDownload = true)!!
        assertEquals(listOf(Option.WHEN_ENDS, Option.REPLACE, Option.CANCEL), both.options)
        assertEquals(listOf("Load when A ends", "Stop A after the download", "Cancel"), both.labelList)
    }

    @Test fun timeLeftCountsTheTempo() {
        val m = Mixer(8000)
        m.load(0, 120, "Air Force Ones"); m.leave(0, 57.9); m.decks[0].pitch = 0.05; m.decks[0].play()
        // 57.9 s of song at +5% is 55.1 s.
        assertEquals("Deck A is playing (0:55 left)", PickCheck.question(m, 0, "Grillz")!!.title)
    }

    @Test fun countdownNamesThePlayingDeck() {
        val m = Mixer(8000)
        assertNull(PickCheck.countdown(m, 1))
        m.load(0, 120, "Air Force Ones"); m.leave(0, 43.5); m.decks[0].play()
        val other = PickCheck.countdown(m, 1)!!
        assertEquals("Deck A ends in 0:43", other.text); assertFalse(other.urgent)
        assertEquals("it ends in 0:43", PickCheck.countdown(m, 0)!!.text)
        m.leave(0, 29.5)
        assertTrue(PickCheck.countdown(m, 1)!!.urgent)
    }

    /**
     * During a MIX, the deck going out ends when MIX stops it, not at the end of its song. While
     * MIX waits for the bar, the deck it is about to start counts as playing, so a pick for the
     * old deck never lands on the incoming song.
     */
    @Test fun duringAMixTheOldDeckEndsWhenMixStopsIt() {
        val m = Mixer(8000)
        m.load(0, 120, "Air Force Ones"); m.decks[0].play()
        m.load(1, 120, "Grillz")
        val buf = FloatArray(512)
        repeat(8000 / 256) { m.render(buf, 256) }
        m.mixPressed()
        assertEquals(AutoMix.State.ARMED, m.autoMix.state)
        val armed = PickCheck.question(m, 0, "Hot in Herre")!!
        assertEquals("Both decks are playing", armed.title)
        assertEquals(listOf("Load when A ends", "Stop A and load now", "Cancel"), armed.labelList)
        // 8 bars at 120 BPM is 16 s, plus up to a bar's wait: under 30 s, so the countdown is red.
        val left = PickCheck.secondsLeft(m, 0)
        assertTrue(left in 16.0..18.1, "A ends in $left s")
        val cd = PickCheck.countdown(m, 0)!!
        assertEquals("it ends in ${Console.fmtTime(left)}", cd.text); assertTrue(cd.urgent)
        var guard = 0
        while (m.autoMix.state != AutoMix.State.RUNNING && guard++ < 100) m.render(buf, 256)
        repeat(4 * 8000 / 256) { m.render(buf, 256) }
        val running = PickCheck.question(m, 0, "Hot in Herre")!!
        val now = PickCheck.secondsLeft(m, 0)
        assertTrue(now in 10.0..12.5, "A ends in $now s, 4 s into the 16 s blend")
        assertEquals("Deck A ends in ${Console.fmtTime(now)}. Load \"Hot in Herre\" there when it ends?", running.message)
        // The song MIX brings in keeps its own time.
        assertTrue(PickCheck.secondsLeft(m, 1) > 60)
    }
}
