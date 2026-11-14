package com.mixdeck.ui

import com.mixdeck.engine.Mixer
import com.mixdeck.engine.ShortArrayPcm
import com.mixdeck.engine.TrackAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The notice line, the load notices, Btn's tap-on-release, and SYNC's light. */
class ConsoleNoticeTest {
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

    private val host = object : Console.Host {
        override fun openLibrary(deck: Int) {}; override fun deckMenu(deck: Int) {}; override fun mainMenu() {}
        override fun toggleRecord() {}; override fun recording() = false; override fun recordSeconds() = 0.0
        override fun loadingText(deck: Int): String? = null; override fun savingText(): String? = null
    }

    private fun setup(computer: Boolean = false): Pair<Mixer, Console> {
        TextMetrics.current = NoText
        val m = Mixer(8000)
        val c = Console(m, host, computer)
        c.layout(1600, 820)
        return m to c
    }

    /** A silent song with a made-up tempo (no analysis needed). */
    private fun Mixer.load(i: Int, title: String, bpm: Double) {
        val sr = 8000; val secs = 60
        val a = TrackAnalysis(bpm, 0.0, 150, FloatArray(150 * secs), FloatArray(150 * secs), FloatArray(150 * secs))
        decks[i].load(ShortArrayPcm(ShortArray(sr * secs * 2), sr), a, title, "Nelly", -14.0)
    }

    /** During the tutorial only the controls it lights work, from a click or a computer key alike. */
    @Test fun duringTheTutorialOnlyItsControlsWork() {
        val (_, c) = setup(computer = true)
        assertTrue(c.allows("load0") && c.allows("mix"))
        val t = Tutorials.nelly()
        c.tutorial = t
        assertFalse(c.allows("load0"), "step 1 waits for its songs")
        assertTrue(c.allows("info0"))
        t.failed = true
        assertTrue(c.allows("load0") && c.allows("load1"), "the songs couldn't be fetched: the lit LOAD buttons work (A and B too)")
        t.index = t.steps.indexOfFirst { it.title == "Do they fit?" }
        assertTrue(c.allows("next"), "N works where NEXT is lit")
        assertFalse(c.allows("mix") || c.allows("load0"))
        t.index = t.steps.size - 1
        assertTrue(c.allows("rec") && c.allows("next") && c.allows("mix"))
        c.tutorial = null
        assertTrue(c.allows("play0"))
    }

    @Test fun sayShowsTheTextThenItGoes() {
        val (_, c) = setup()
        assertNull(c.noticeText)
        c.say("Hello", ms = 60)
        assertEquals("Hello", c.noticeText)
        val canvas = TextCanvas()
        c.draw(canvas)
        assertTrue("Hello" in canvas.texts, "drawn on the console")
        Thread.sleep(120)
        assertNull(c.noticeText)
        val later = TextCanvas()
        c.draw(later)
        assertTrue("Hello" !in later.texts, "gone after its time")
    }

    @Test fun aNewNoticeReplacesTheOld() {
        val (_, c) = setup()
        c.say("One", deck = 0); c.say("Two", deck = 1)
        assertEquals("Two", c.noticeText)
    }

    @Test fun errorsStayLonger() {
        val (_, c) = setup()
        c.say("Broken", error = true, ms = 10)
        Thread.sleep(60)
        assertEquals("Broken", c.noticeText, "errors last at least 8 s")
    }

    @Test fun hiddenUnderTheLabelsAndTheTutorial() {
        val (_, c) = setup()
        c.say("Hidden")
        c.helpMode = true
        assertTrue("Hidden" !in TextCanvas().also { c.draw(it) }.texts)
        c.helpMode = false
        c.tutorial = Tutorials.nelly()
        assertTrue("Hidden" !in TextCanvas().also { c.draw(it) }.texts)
    }

    /** The tutorial's last step (REC and MIX speak through notices) and errors show the notice after all. */
    @Test fun theTutorialShowsErrorsAndItsLastStepsNotices() {
        val (_, c) = setup()
        val t = Tutorials.nelly()
        c.tutorial = t
        c.say("Couldn't download it", error = true)
        assertTrue("Couldn't download it" in TextCanvas().also { c.draw(it) }.texts, "errors show in step 1")
        t.index = t.steps.size - 1
        c.say("Recording 0:12 · hold REC to stop")
        assertTrue("Recording 0:12 · hold REC to stop" in TextCanvas().also { c.draw(it) }.texts, "the last step shows notices")
    }

    /** A tap on the notice dismisses it and never seeks the song strip hidden under it. */
    @Test fun aTapOnTheNoticeDismissesIt() {
        val (m, c) = setup()
        m.load(0, "Air Force Ones", 93.0); m.load(1, "Grillz", 95.0)
        m.decks[0].play()
        val at = m.decks[0].positionSec
        c.say("\"Grillz\" is on deck B, synced (−2.1%). Tap MIX, or press ▶ when the moment's right.")
        val r = c.keyBanner
        for (x in listOf(r.left + r.width() * 0.25f, r.centerX() - 10f)) {
            c.say("Hello")
            c.pointerDown(0, x, r.centerY(), first = true); c.pointerUp(0)
            assertNull(c.noticeText, "dismissed")
            assertEquals(at, m.decks[0].positionSec, 1e-9, "deck A didn't jump")
        }
        // With no notice there, the strip under it works as before.
        c.pointerDown(0, r.left + r.width() * 0.25f, r.centerY(), first = true); c.pointerUp(0)
        assertTrue(m.decks[0].positionSec > 30, "the strip seeks deck A")
    }

    @Test fun longNoticeWrapsToTwoLines() {
        val (_, c) = setup()
        val long = "\"Grillz\" is on deck B, synced (−2.1%). Tap MIX, or press ▶ when the moment's right. " +
            "This sentence is here only to make it far too long for one line."
        c.say(long)
        val canvas = TextCanvas()
        c.draw(canvas)
        val lines = canvas.texts.filter { long.contains(it.removeSuffix("…")) && it.length > 10 }
        assertEquals(2, lines.size, "drawn as ${canvas.texts}")
        assertTrue(lines[0].startsWith("\"Grillz\""))
    }

    @Test fun loadNotices() {
        val (m, _) = setup()
        m.load(0, "Air Force Ones", 93.0)
        assertEquals("\"Air Force Ones\" is ready on deck A. Press ▶ to play.", Console.loadedNotice(m, 0, null))
        m.decks[0].play()
        m.load(1, "Grillz", 95.0)
        assertEquals("\"Grillz\" is on deck B at its own speed (95.0 BPM). Tap SYNC to match deck A.", Console.loadedNotice(m, 1, null))
        m.sync(1)
        assertEquals("\"Grillz\" is on deck B, synced (−2.1%). Tap MIX, or press ▶ when the moment's right.",
            Console.loadedNotice(m, 1, m.decks[1].pitch))
        m.decks[1].pitch = 0.034
        assertEquals("\"Grillz\" is on deck B, synced (+3.4%). Tap MIX, or press ▶ when the moment's right.",
            Console.loadedNotice(m, 1, m.decks[1].pitch))
        m.decks[0].pause()
        assertEquals("\"Grillz\" is ready on deck B. Press ▶ to play.", Console.loadedNotice(m, 1, 0.01))
        // On a computer: click.
        m.decks[0].play()
        assertEquals("\"Grillz\" is on deck B, synced (+3.4%). Click MIX, or press ▶ when the moment's right.",
            Console.loadedNotice(m, 1, m.decks[1].pitch, computer = true))
        assertEquals("\"Grillz\" is on deck B at its own speed (98.2 BPM). Click SYNC to match deck A.",
            Console.loadedNotice(m, 1, null, computer = true))
    }

    @Test fun tapOnUpFiresOnRelease() {
        val (_, c) = setup()
        var taps = 0; var longs = 0
        val b = c.Btn(RectF(0f, 0f, 10f, 10f), { "X" }, 0, onLong = { longs++ }, tapOnUp = true) { taps++ }
        b.down(1f, 1f)
        assertEquals(0, taps, "nothing on touch")
        b.up()
        assertEquals(1, taps, "fires on release")
        b.down(1f, 1f); b.cancel()
        assertEquals(1, taps, "a cancelled touch is no tap")
        b.down(1f, 1f); Thread.sleep(700); b.up()
        assertEquals(1, taps, "a long press is no tap")
        assertEquals(1, longs)
        var downs = 0
        val plain = c.Btn(RectF(0f, 0f, 10f, 10f), { "Y" }, 0) { downs++ }
        plain.down(1f, 1f)
        assertEquals(1, downs, "other buttons still fire on touch")
        // Sliding off before lifting changes your mind: no tap, and no long press either.
        b.down(1f, 1f); b.move(400f, 400f); b.up()
        assertEquals(1, taps, "slid off: no tap")
        b.down(1f, 1f); b.move(400f, 400f); Thread.sleep(700); b.draw(TextCanvas()); b.up()
        assertEquals(1, longs, "slid off: no long press")
        b.down(1f, 1f); b.move(2f, 2f); b.up()
        assertEquals(2, taps, "moving inside it is still a tap")
    }

    @Test fun syncLightsOnlyWhenTheBeatsLineUp() {
        val (m, c) = setup()
        m.load(0, "A", 120.0); m.load(1, "B", 120.0)
        assertEquals(Console.SyncState.IN_TIME, c.syncState(1), "same tempo, stopped")
        m.decks[0].startAt(8000.0); m.decks[1].startAt(8000.0)
        assertEquals(Console.SyncState.IN_TIME, c.syncState(1), "same tempo, same beat")
        m.decks[1].startAt(8000.0 + 0.1 * m.decks[1].framesPerBeat)
        assertEquals(Console.SyncState.DRIFTED, c.syncState(1), "beats 0.1 apart: blinks")
        m.decks[1].startAt(8000.0 + 0.02 * m.decks[1].framesPerBeat)
        assertEquals(Console.SyncState.IN_TIME, c.syncState(1), "within 0.03 of a beat")
        m.decks[1].pitch = 0.05
        assertEquals(Console.SyncState.NO, c.syncState(1), "different tempo")
        m.load(1, "Half", 60.0)
        m.decks[1].startAt(8000.0 + 0.3 * m.decks[1].framesPerBeat)
        assertEquals(Console.SyncState.IN_TIME, c.syncState(1), "half time keeps the tempo-only light")
    }
}
