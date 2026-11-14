package com.mixdeck.ui

import com.mixdeck.engine.Mixer
import com.mixdeck.engine.ShortArrayPcm
import com.mixdeck.engine.TrackAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** First run: the Start card, empty decks that lead to LOAD, tutorial step 1, 🎧's split-cue offer, the labels banner. */
class StartCardTest {
    private object NoText : TextMetrics {
        override fun width(s: CharSequence, start: Int, end: Int, size: Float, face: Typeface) = (end - start) * size * 0.55f
        override fun ascent(size: Float, face: Typeface) = -size * 0.8f
        override fun descent(size: Float, face: Typeface) = size * 0.2f
    }

    /** Remembers the text drawn. */
    private class TextCanvas : Canvas {
        val texts = ArrayList<String>()
        override fun drawColor(color: Int) {}
        override fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) {}
        override fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) {}
        override fun drawCircle(cx: Float, cy: Float, radius: Float, p: Paint) {}
        override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) {}
        override fun drawLines(pts: FloatArray, offset: Int, count: Int, p: Paint) {}
        override fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, p: Paint) {}
        override fun drawText(s: String, x: Float, y: Float, p: Paint) { texts += s }
        override fun drawPath(path: Path, p: Paint) {}
        override fun save() {}
        override fun restore() {}
        override fun clipRect(r: RectF) {}
    }

    private val calls = ArrayList<String>()
    private val loading = arrayOfNulls<String>(2)
    private val host = object : Console.Host {
        override fun openLibrary(deck: Int) { calls += "library$deck" }
        override fun deckMenu(deck: Int) { calls += "menu$deck" }
        override fun mainMenu() {}
        override fun toggleRecord() {}; override fun recording() = false; override fun recordSeconds() = 0.0
        override fun loadingText(deck: Int): String? = loading[deck]; override fun savingText(): String? = null
        override fun startTutorial() { calls += "tutorial" }
        override fun retryTutorialSongs() { calls += "retry" }
        override fun splitCueOff() { calls += "splitCueOff" }
        override fun openGuide() { calls += "guide" }
    }

    private fun setup(computer: Boolean = false): Pair<Mixer, Console> {
        TextMetrics.current = NoText
        val m = Mixer(8000)
        val c = Console(m, host, computer)
        c.layout(1600, 820)
        return m to c
    }

    private fun Mixer.load(i: Int, title: String) {
        val sr = 8000; val secs = 60
        val a = TrackAnalysis(93.0, 0.0, 150, FloatArray(150 * secs), FloatArray(150 * secs), FloatArray(150 * secs))
        decks[i].load(ShortArrayPcm(ShortArray(sr * secs * 2), sr), a, title, "Nelly", -14.0)
    }

    private fun Console.tapAt(r: RectF) { pointerDown(0, r.centerX(), r.centerY(), first = true); pointerUp(0) }
    private fun Console.tap(key: String) = tapAt(rectOf(key)!!)
    private fun Console.drawn() = TextCanvas().also { draw(it) }.texts

    @Test fun emptyDecksShowTheStartCard() {
        val (_, c) = setup()
        c.newcomer = true
        assertTrue(c.showStart())
        val texts = c.drawn()
        assertTrue("Welcome to MixDeck · how do you want to start?" in texts, "drawn $texts")
        assertTrue("▶  Learn with a guided mix" in texts)
        c.tapAt(c.startBtns[0])
        assertEquals(listOf("tutorial"), calls, "the first button starts the tutorial")
        c.tapAt(c.startBtns[1])
        assertEquals("library0", calls.last(), "the second loads a song on deck A")
        c.tapAt(c.startBtns[2])
        assertTrue(c.helpMode, "the third shows the labels")
        assertFalse(c.showStart(), "not over the labels")
    }

    @Test fun aReturningUserIsAskedToLoadFirst() {
        val (_, c) = setup()
        c.newcomer = false
        val texts = c.drawn()
        assertTrue("Both decks are empty · pick a song to start" in texts, "drawn $texts")
        c.tapAt(c.startBtns[0])
        assertEquals(listOf("library0"), calls)
        c.tapAt(c.startBtns[1])
        assertEquals("tutorial", calls.last())
    }

    @Test fun aNoticeStandsInForTheCardTitle() {
        val (_, c) = setup()
        c.newcomer = true
        val notice = "MixDeck 1.130 is downloaded · install it from ☰ now"
        c.say(notice, ms = 60_000)
        val texts = c.drawn()
        assertTrue(notice in texts.joinToString(" "), "the notice shows over the card: $texts")
        assertFalse("Welcome to MixDeck · how do you want to start?" in texts, "the title doesn't show through it")
        assertTrue("Load my first song" in texts, "the buttons stay")
        c.say(notice, ms = 0)
        assertTrue("Welcome to MixDeck · how do you want to start?" in c.drawn(), "the title is back once it's gone")
    }

    @Test fun aTapElsewhereOnTheCardDoesNothing() {
        val (_, c) = setup()
        c.tap("ov0")                                   // under the card's title
        c.tap("ov1")
        val lane = c.rectOf("lane0")!!                 // left of the first button
        c.pointerDown(0, lane.left + 2f, lane.centerY(), first = true); c.pointerUp(0)
        c.wheel(lane.left + 2f, lane.centerY(), 1f)
        assertEquals(emptyList(), calls, "the card swallows taps on what it covers")
    }

    @Test fun noCardWhileASongIsOnItsWay() {
        val (m, c) = setup()
        loading[1] = "Downloading Grillz… 37%"
        assertFalse(c.showStart())
        loading[1] = null
        assertTrue(c.showStart())
        m.load(0, "Air Force Ones")
        assertFalse(c.showStart())
        m.decks[0].eject()
        c.tutorial = Tutorials.nelly()
        assertFalse(c.showStart(), "not during the tutorial")
    }

    @Test fun anEmptyDeckLeadsToLoad() {
        val (m, c) = setup(computer = true)
        m.load(0, "Air Force Ones")
        c.tap("play1")
        assertEquals(listOf("library1"), calls, "▶ on an empty deck opens the library")
        assertFalse(m.decks[1].playing)
        c.tap("lane1")
        assertEquals("library1", calls.last(), "so does its close-up")
        c.tap("jog1")
        assertEquals(listOf("library1", "library1", "library1"), calls, "and the middle of its jog wheel")
        val texts = c.drawn()
        assertTrue("Deck B is empty · click here to load a song" in texts, "drawn $texts")
        assertTrue("Click NEXT for a song that fits" in texts)
        assertTrue("LOAD" in texts)
        calls.clear()
        loading[1] = "Downloading Grillz… 37%"
        c.tap("play1")
        assertEquals(emptyList(), calls, "not while a song is on its way")
        c.tap("play0")
        assertTrue(m.decks[0].playing, "▶ on a loaded deck plays")
    }

    @Test fun tutorialStepOneWaitsForItsSongs() {
        val (_, c) = setup()
        val t = Tutorials.nelly()
        c.tutorial = t
        loading[0] = "Downloading Air Force Ones… 37%"; loading[1] = "Up next: Grillz"
        val texts = c.drawn()
        assertTrue("Getting songs…" in texts, "drawn $texts")
        assertTrue(c.tutNext.isEmpty(), "no Next yet")
        c.tapAt(c.tutWait)
        assertEquals(0, t.index, "tapping where Next sits doesn't skip the download")
        assertEquals(emptyList(), calls)
        // The download failed: Try again, and the LOAD buttons light up.
        t.failed = true
        loading[0] = null; loading[1] = null
        assertTrue("Try again" in c.drawn())
        c.tapAt(c.tutWait)
        assertEquals(listOf("retry"), calls)
        assertEquals(0, t.index)
        c.tap("load1")
        assertEquals("library1", calls.last(), "the lit LOAD buttons work")
    }

    @Test fun tutorialStepOneMovesOnOnceBothSongsAreThere() {
        val (m, c) = setup()
        val t = Tutorials.nelly()
        c.tutorial = t
        t.failed = true; t.status = "Couldn't get the songs"
        m.load(0, "Air Force Ones"); m.load(1, "Grillz")   // picked with the lit LOAD buttons
        val texts = c.drawn()
        assertFalse(t.failed); assertEquals(null, t.status, "the error is cleared once both songs are there")
        assertFalse("Getting songs…" in texts)
        assertFalse(c.tutNext.isEmpty())
        c.tapAt(c.tutNext)
        assertEquals(1, t.index)
    }

    @Test fun headphonesOfferSplitCue() {
        val (m, c) = setup()
        m.load(0, "Air Force Ones")
        c.tap("phones0")
        assertTrue(m.decks[0].headphone)
        assertEquals(listOf("splitCueOff"), calls)
        c.tap("phones0")
        assertEquals(1, calls.size, "turning 🎧 off doesn't ask")
        m.splitCue = true
        c.tap("phones0")
        assertEquals(1, calls.size, "split cue already on")
    }

    @Test fun theLabelsBannerOpensTheGuide() {
        val (m, c) = setup()
        m.load(0, "Air Force Ones")
        c.helpMode = true
        val texts = c.drawn()
        assertTrue("BUTTON LABELS · tap anywhere to close" in texts, "drawn $texts")
        assertTrue("Tap here for the full guide ›" in texts)
        c.tap("play0")
        assertFalse(c.helpMode)
        assertEquals(emptyList(), calls, "a tap elsewhere only closes the labels")
        assertFalse(m.decks[0].playing)
        c.helpMode = true
        c.tap("ov0")
        assertEquals(emptyList(), calls)
        c.helpMode = true
        // The banner sits between the two song strips, over the notice line.
        c.tapAt(c.keyBanner)
        assertEquals(listOf("guide"), calls)
        assertFalse(c.helpMode)
    }

    @Test fun computerLabelsShowTheirKeys() {
        TextMetrics.current = NoText
        val keys = mapOf("play0" to "W", "rec" to "Ctrl+R")
        val h = object : Console.Host by host { override fun shortcut(key: String) = keys[key] }
        val m = Mixer(8000)
        val c = Console(m, h, computer = true)
        c.layout(1600, 820)
        c.helpMode = true
        val texts = c.drawn()
        assertTrue("W" in texts && "Ctrl+R" in texts, "key caps drawn: $texts")
        assertTrue("BUTTON LABELS · click anywhere or press Esc to close" in texts)
        assertTrue("Click here for the full guide and shortcuts ›" in texts)
        assertTrue("Labels on / off" in texts.joinToString(" ") || texts.any { it.startsWith("Labels") })
        // Written for a mouse: the song strips say "click", never "tap".
        val all = texts.joinToString(" ")
        assertTrue("click for BPM fixes" in all && "click to jump there" in all, all)
        assertFalse(Regex("\\btap\\b").containsMatchIn(all), all)
    }

    @Test fun phoneLabelsSayTap() {
        TextMetrics.current = NoText
        val c = Console(Mixer(8000), host)
        c.layout(2400, 1080)
        c.helpMode = true
        val all = c.drawn().joinToString(" ")
        assertTrue("tap for BPM fixes" in all && "tap to jump there" in all, all)
    }
}
