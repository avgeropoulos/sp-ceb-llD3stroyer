package com.mixdeck.ui

import com.mixdeck.engine.Analyzer
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.MusicalKey
import com.mixdeck.engine.ShortArrayPcm
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextPlanTest {
    private object NoText : TextMetrics {
        override fun width(s: CharSequence, start: Int, end: Int, size: Float, face: Typeface) = (end - start) * size * 0.55f
        override fun ascent(size: Float, face: Typeface) = -size * 0.8f
        override fun descent(size: Float, face: Typeface) = size * 0.2f
    }
    private object NoCanvas : Canvas {
        override fun drawColor(color: Int) {}
        override fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) {}
        override fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) {}
        override fun drawCircle(cx: Float, cy: Float, radius: Float, p: Paint) {}
        override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) {}
        override fun drawLines(pts: FloatArray, offset: Int, count: Int, p: Paint) {}
        override fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, p: Paint) {}
        override fun drawText(s: String, x: Float, y: Float, p: Paint) {}
        override fun drawPath(path: Path, p: Paint) {}
        override fun save() {}
        override fun restore() {}
        override fun clipRect(r: RectF) {}
    }

    private val started = ArrayList<Int>()
    private val host = object : Console.Host {
        override fun openLibrary(deck: Int) {}; override fun deckMenu(deck: Int) {}; override fun mainMenu() {}
        override fun toggleRecord() {}; override fun recording() = false; override fun recordSeconds() = 0.0
        override fun loadingText(deck: Int): String? = null; override fun savingText(): String? = null
        override fun deckStarted(deck: Int) { started += deck }
    }

    private fun song(secs: Int): ShortArrayPcm {
        val sr = 8000; val n = sr * secs; val x = FloatArray(n)
        for (i in 0 until n) { val t = i.toDouble() / sr; val tb = t % 0.5; x[i] = (sin(2 * PI * 60 * tb) * exp(-tb * 8) * 0.7).toFloat() }
        return ShortArrayPcm.fromFloats(x, x, sr)
    }

    private fun setup(): Pair<Mixer, Console> {
        TextMetrics.current = NoText
        val m = Mixer(8000)
        val c = Console(m, host)
        c.layout(1600, 820)
        return m to c
    }

    private fun Mixer.load(i: Int, secs: Int) {
        val p = song(secs)
        decks[i].load(p, Analyzer.analyze(p).withKey(MusicalKey.fromCamelot("8A"), 0.8f), "Song $i", "", -14.0)
    }

    @Test fun bothEmptyHasNothingToFollow() {
        val (_, c) = setup()
        val p = c.nextPlan()
        assertEquals(0, p.target); assertNull(p.after)
    }

    @Test fun oneSongLoadsOntoTheOtherDeck() {
        val (m, c) = setup()
        m.load(1, 10)
        c.draw(NoCanvas)
        val p = c.nextPlan()
        assertEquals(0, p.target); assertEquals(1, p.after); assertFalse(p.waiting)
    }

    @Test fun playingDeckIsFollowedAndAnUnplayedSongIsWaiting() {
        val (m, c) = setup()
        m.load(0, 10); m.load(1, 10); m.decks[0].play()
        c.draw(NoCanvas)
        val p = c.nextPlan()
        assertEquals(1, p.target); assertEquals(0, p.after); assertTrue(p.waiting)
        assertEquals(listOf(0), started)
        c.draw(NoCanvas)
        assertEquals(listOf(0), started, "deckStarted fires once per song")
    }

    @Test fun holdingCueToPreListenIsNotAStart() {
        val (m, c) = setup()
        m.load(0, 10); m.load(1, 10); m.decks[0].play(); c.draw(NoCanvas)
        started.clear()
        m.decks[1].cueDown(false)                 // pre-listen the waiting song
        assertTrue(m.decks[1].previewing)
        c.draw(NoCanvas)
        assertEquals(emptyList(), started, "a CUE preview doesn't count as played")
        assertTrue(c.nextPlan().waiting, "the previewed song is still waiting")
        m.decks[1].playPause()                    // PLAY while holding CUE: now it's really playing
        c.draw(NoCanvas)
        assertEquals(listOf(1), started)
    }

    @Test fun midMixTheDeckEndingFirstIsReplaced() {
        val (m, c) = setup()
        m.load(0, 10); m.load(1, 30); m.decks[0].play(); m.decks[1].play()
        val p = c.nextPlan()
        assertEquals(0, p.target); assertEquals(1, p.after)
    }

    @Test fun nothingPlayingFollowsTheDeckThatPlayedLast() {
        val (m, c) = setup()
        m.load(0, 10); m.load(1, 10)
        m.decks[1].play(); c.draw(NoCanvas); m.decks[1].pause(); c.draw(NoCanvas)
        val p = c.nextPlan()
        assertEquals(0, p.target); assertEquals(1, p.after)
        assertTrue(p.waiting, "deck A's song was never played")
    }

    @Test fun keyLabelShowsTheKeyYouHear() {
        val (m, _) = setup()
        m.load(0, 10)
        val d = m.decks[0]
        assertEquals("8A", KeyDisplay.label(d))
        d.pitchRange = 0.16; d.pitch = 0.0595   // +1 semitone: A minor -> B-flat minor
        assertEquals("3A↑", KeyDisplay.label(d))
        d.pitch = -0.0561                         // -1 semitone: A minor -> A-flat minor
        assertEquals("1A↓", KeyDisplay.label(d))
        d.pitch = 0.03                            // half a semitone: between keys
        assertEquals("~3A↑", KeyDisplay.label(d))
        d.pitch = 0.01
        assertEquals("8A", KeyDisplay.label(d))
    }
}
