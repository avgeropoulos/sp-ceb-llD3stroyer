package com.mixdeck.ui

import com.mixdeck.engine.AutoMix
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.ShortArrayPcm
import com.mixdeck.engine.TrackAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The console's MIX button: its labels, tap and hold, and MIX's messages on the notice line. */
class MixButtonTest {
    private object NoText : TextMetrics {
        override fun width(s: CharSequence, start: Int, end: Int, size: Float, face: Typeface) = (end - start) * size * 0.55f
        override fun ascent(size: Float, face: Typeface) = -size * 0.8f
        override fun descent(size: Float, face: Typeface) = size * 0.2f
    }

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

    private var saved = 0
    /** What the host says is on its way to each deck: loading / downloading, and up next. */
    private val loading = arrayOfNulls<String>(2)
    private val upNext = arrayOfNulls<String>(2)
    private val host = object : Console.Host {
        override fun openLibrary(deck: Int) {}; override fun deckMenu(deck: Int) {}; override fun mainMenu() {}
        override fun toggleRecord() {}; override fun recording() = false; override fun recordSeconds() = 0.0
        override fun loadingText(deck: Int): String? = loading[deck]; override fun savingText(): String? = null
        override fun settingsChanged() { saved++ }
        override fun upNext(deck: Int) = upNext[deck]
    }

    private val sr = 8000
    private val buf = FloatArray(512)

    private fun setup(computer: Boolean = false): Pair<Mixer, Console> {
        TextMetrics.current = NoText
        val m = Mixer(sr)
        val c = Console(m, host, computer)
        c.layout(1600, 820)
        return m to c
    }

    /** A silent song with a made-up tempo (no analysis needed). */
    private fun Mixer.load(i: Int, title: String, bpm: Double) {
        val secs = 120
        val a = TrackAnalysis(bpm, 0.0, 150, FloatArray(150 * secs), FloatArray(150 * secs), FloatArray(150 * secs))
        decks[i].load(ShortArrayPcm(ShortArray(sr * secs * 2), sr), a, title, "Nelly", -14.0)
    }

    private fun Console.drawn() = TextCanvas().also { draw(it) }.texts

    private fun Console.tap(key: String) {
        val r = rectOf(key)!!
        pointerDown(0, r.centerX(), r.centerY(), first = true)
        pointerUp(0)
    }

    @Test fun labelsFollowTheMix() {
        val (m, c) = setup()
        assertTrue("MIX" in c.drawn(), "nothing to blend")
        m.load(0, "Air Force Ones", 93.0); m.load(1, "Grillz", 95.0)
        m.decks[0].play()
        assertTrue("MIX ▸ B" in c.drawn(), "a song waits on B")
        c.tap("mix")
        assertEquals(AutoMix.State.ARMED, m.autoMix.state, "fires on release")
        val n = m.autoMix.beatsToStart()
        val texts = c.drawn()
        assertTrue("IN $n" in texts, "drawn $texts")
        assertEquals("Grillz starts on deck B in $n beats · tap MIX to cancel", c.noticeText)
        while (m.autoMix.state != AutoMix.State.RUNNING) m.render(buf, 256)
        assertTrue("BAR 1/8" in c.drawn())
        assertEquals("Mixing into Grillz over 8 bars · touch the crossfader to take over", c.noticeText)
        c.tap("mix")
        assertEquals(AutoMix.State.IDLE, m.autoMix.state, "a second tap takes over")
        c.drawn()
        assertEquals("You've got it: MIX stopped at bar 1 of 8", c.noticeText)
    }

    @Test fun holdChangesTheLength() {
        val (m, c) = setup()
        m.load(0, "Air Force Ones", 93.0); m.load(1, "Grillz", 95.0)
        m.decks[0].play()
        val r = c.rectOf("mix")!!
        c.pointerDown(0, r.centerX(), r.centerY(), first = true)
        Thread.sleep(700)
        c.drawn()          // the long press shows up while drawing
        c.pointerUp(0)
        assertEquals(16, m.autoMix.bars)
        assertEquals(AutoMix.State.IDLE, m.autoMix.state, "a hold doesn't start a mix")
        assertEquals("MIX length: 16 bars (41 s at 93 BPM)", c.noticeText)
        assertEquals(1, saved, "the host saves the setting")
        m.decks[0].pause()
        m.autoMix.bars = 32
        c.pointerDown(0, r.centerX(), r.centerY(), first = true)
        Thread.sleep(700)
        c.drawn()
        c.pointerUp(0)
        assertEquals(4, m.autoMix.bars, "32 wraps round to 4")
        assertEquals("MIX length: 4 bars", c.noticeText, "nothing playing: no seconds")
    }

    @Test fun nextBlinksEarlierForALongMix() {
        val (m, c) = setup()
        m.load(0, "Air Force Ones", 93.0)
        m.decks[0].play()
        // 75 s left: past the 60 s it used to wait for, inside 60 s + an 8-bar MIX (about 21 s).
        m.decks[0].seekFraction(45.0 / 120)
        val nextLit = (0 until 30).any { c.drawn(); Thread.sleep(25); isLit(c) }
        assertTrue(nextLit, "NEXT blinks")
    }

    /** A song downloading for the other deck is on its way: NEXT doesn't ask for one. */
    @Test fun nextDoesNotBlinkWhileTheNextSongIsOnItsWay() {
        val (m, c) = setup()
        m.load(0, "Air Force Ones", 93.0)
        m.decks[0].play()
        m.decks[0].seekFraction(100.0 / 120)
        loading[1] = "Downloading Grillz… 42%"
        assertFalse((0 until 30).any { c.drawn(); Thread.sleep(25); isLit(c) }, "NEXT stays dark while it downloads")
        loading[1] = null
        assertTrue((0 until 30).any { c.drawn(); Thread.sleep(25); isLit(c) }, "NEXT blinks once nothing is coming")
    }

    /** MIX's last message leaves out "tap NEXT" when a song is already up next for the deck it stopped. */
    @Test fun finishMessageKnowsAboutUpNext() {
        for (coming in listOf(false, true)) {
            val (m, c) = setup()
            m.autoMix.bars = 4
            m.load(0, "Air Force Ones", 93.0); m.load(1, "Grillz", 95.0)
            m.decks[0].play()
            upNext[0] = if (coming) "Hot in Herre" else null
            m.mixPressed()
            var guard = 0
            while (m.autoMix.state != AutoMix.State.IDLE && guard++ < 20 * sr / 256) { m.render(buf, 256); c.drawn() }
            assertEquals(AutoMix.State.IDLE, m.autoMix.state)
            assertEquals(if (coming) "Now playing Grillz" else "Now playing Grillz · tap NEXT for what fits after it", c.noticeText)
        }
        upNext[0] = null
    }

    /**
     * "Stop A and load now" mid-MIX puts a new song on A, which finishes the MIX: its last message
     * doesn't say "tap NEXT", as the next song is already waiting there (MIX ▸ A).
     */
    @Test fun aSongLoadedOnTheOldDeckIsNotAskedForAgain() {
        for (noticeFirst in listOf(true, false)) {
            val (m, c) = setup()
            m.autoMix.bars = 4
            m.load(0, "Air Force Ones", 93.0); m.load(1, "Grillz", 95.0)
            m.decks[0].play()
            c.drawn()
            m.mixPressed()
            var guard = 0
            while (m.autoMix.state != AutoMix.State.RUNNING && guard++ < 20 * sr / 256) { m.render(buf, 256); c.drawn() }
            synchronized(m.lock) { m.load(0, "Hot in Herre", 96.0) }
            if (noticeFirst) c.say("\"Hot in Herre\" is on deck A", 0)
            m.render(buf, 256)
            if (!noticeFirst) c.say("\"Hot in Herre\" is on deck A", 0)
            val texts = c.drawn()
            assertEquals(AutoMix.State.IDLE, m.autoMix.state)
            assertEquals("Now playing Grillz", c.noticeText, "no \"tap NEXT\": the next song is on A")
            assertTrue("MIX ▸ A" in texts, "Hot in Herre waits on A")
        }
    }

    /** A download that failed during a MIX: its error stays until it's read, MIX's messages wait. */
    @Test fun anErrorIsNotPushedAsideByMix() {
        val (m, c) = setup()
        m.load(0, "Air Force Ones", 93.0); m.load(1, "Grillz", 95.0)
        m.decks[0].play()
        c.drawn()
        val error = "No internet connection. Check Wi-Fi and try again. (couldn't download \"Hot in Herre\")"
        c.say(error, 0, error = true)
        c.mixTapped()
        c.drawn()
        assertEquals(error, c.noticeText)
        // Tapped away: MIX's newest message shows.
        c.pointerDown(0, c.keyBanner.centerX(), c.keyBanner.centerY(), first = true); c.pointerUp(0)
        c.drawn()
        assertTrue(c.noticeText!!.startsWith("Grillz starts on deck B"), "${c.noticeText}")
    }

    /** After a MIX the grey MIX doesn't blend back into the song that just played: it says what to do. */
    @Test fun greyMixAsksForTheNextSong() {
        val (m, c) = setup()
        m.autoMix.bars = 4
        m.load(0, "Air Force Ones", 93.0); m.load(1, "Grillz", 95.0)
        m.decks[0].play()
        c.drawn()
        c.tap("mix")
        var guard = 0
        while (m.autoMix.state != AutoMix.State.IDLE && guard++ < 20 * sr / 256) { m.render(buf, 256); c.drawn() }
        assertTrue("MIX" in c.drawn(), "grey: nothing new waits on A")
        c.tap("mix")
        assertEquals(AutoMix.State.IDLE, m.autoMix.state, "Air Force Ones doesn't come back")
        assertFalse(m.decks[0].playing)
        assertEquals("Load the next song first: tap NEXT", c.noticeText)
        loading[0] = "Downloading Hot in Herre… 40%"
        c.tap("mix")
        assertEquals("Wait for the next song to load", c.noticeText)
        loading[0] = null
        assertEquals(AutoMix.State.IDLE, m.autoMix.state)
    }

    /** Pre-listening the waiting song in the headphones doesn't make it "played": MIX still blends into it. */
    @Test fun preListeningKeepsTheSongWaiting() {
        val (m, c) = setup()
        m.load(0, "Air Force Ones", 93.0); m.load(1, "Grillz", 95.0)
        m.crossfader = -1f
        m.decks[0].play()
        c.drawn()
        m.decks[1].headphone = true
        m.decks[1].play()
        c.drawn()
        m.decks[1].pause()
        assertTrue("MIX ▸ B" in c.drawn(), "Grillz is still waiting")
        assertTrue(c.nextPlan().waiting)
        // Once the room hears it, it has played.
        m.crossfader = 0f
        m.decks[1].play(); c.drawn(); m.decks[1].pause()
        assertTrue("MIX ▸ B" !in c.drawn())
    }

    /** On a computer MIX's messages say click and move, not tap and touch. */
    @Test fun computerWordsForMix() {
        val (m, c) = setup(computer = true)
        m.load(0, "Air Force Ones", 93.0); m.load(1, "Grillz", 95.0)
        m.decks[0].play()
        c.mixTapped()
        c.drawn()
        val n = m.autoMix.beatsToStart()
        assertEquals("Grillz starts on deck B in $n beats · click MIX to cancel", c.noticeText)
        while (m.autoMix.state != AutoMix.State.RUNNING) m.render(buf, 256)
        c.drawn()
        assertEquals("Mixing into Grillz over 8 bars · move the crossfader to take over", c.noticeText)
    }

    /** NEXT draws a black label when lit. */
    private fun isLit(c: Console): Boolean {
        var black = false
        val canvas = object : Canvas by TextCanvas() {
            override fun drawText(s: String, x: Float, y: Float, p: Paint) { if (s == "NEXT" && p.color == Color.BLACK) black = true }
        }
        c.draw(canvas)
        return black
    }
}
