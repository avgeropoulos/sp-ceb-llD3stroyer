package com.mixdeck.ui

import com.mixdeck.engine.Mixer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** REC: a tap starts recording; while recording a tap only says how to stop, and a hold stops and saves. */
class RecButtonTest {
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

    private var recording = false
    private var starts = 0
    private var stops = 0
    private val host = object : Console.Host {
        override fun openLibrary(deck: Int) {}; override fun deckMenu(deck: Int) {}; override fun mainMenu() {}
        override fun toggleRecord() { if (recording) stopRecord() else { recording = true; starts++ } }
        override fun stopRecord() { recording = false; stops++ }
        override fun recording() = recording
        override fun recordSeconds() = 754.0
        override fun loadingText(deck: Int): String? = null; override fun savingText(): String? = null
    }

    private fun setup(): Console {
        TextMetrics.current = NoText
        return Console(Mixer(8000), host).apply { layout(1600, 820) }
    }

    private fun Console.drawn() = TextCanvas().also { draw(it) }.texts

    @Test fun aTapStartsRecording() {
        val c = setup()
        val r = c.rectOf("rec")!!
        c.pointerDown(0, r.centerX(), r.centerY(), first = true)
        assertEquals(0, starts, "fires on release")
        c.pointerUp(0)
        assertEquals(1, starts)
        assertTrue(recording)
    }

    @Test fun aQuickTapWhileRecordingDoesNotStopIt() {
        val c = setup()
        recording = true
        val r = c.rectOf("rec")!!
        c.pointerDown(0, r.centerX(), r.centerY(), first = true)
        c.drawn()
        c.pointerUp(0)
        assertEquals(0, stops, "a tap doesn't stop the recording")
        assertTrue(recording)
        assertEquals("Recording 12:34 · hold REC to stop", c.noticeText)
        assertTrue("12:34" in c.drawn(), "REC shows the time")
    }

    @Test fun holdingRecStopsAndSaves() {
        val c = setup()
        recording = true
        val r = c.rectOf("rec")!!
        c.pointerDown(0, r.centerX(), r.centerY(), first = true)
        assertTrue("HOLD" in c.drawn(), "REC says HOLD while it's held")
        Thread.sleep(700)
        c.drawn()           // the long press is noticed while drawing
        assertEquals(1, stops, "held: stopped")
        c.pointerUp(0)
        assertEquals(1, stops, "letting go doesn't do it again")
        assertEquals(0, starts, "nor start a new recording")
    }

    @Test fun holdNoticedOnReleaseToo() {
        val c = setup()
        recording = true
        val r = c.rectOf("rec")!!
        c.pointerDown(0, r.centerX(), r.centerY(), first = true)
        Thread.sleep(700)
        c.pointerUp(0)      // no frame was drawn while held
        assertEquals(1, stops)
    }

    @Test fun messages() {
        assertEquals("Recording too short to keep (2 s)", Menus.tooShort(2.7))
        assertEquals("Recording 12:34 · hold REC or press Ctrl+R to stop", Menus.recordingHint(754.0, "Ctrl+R"))
        assertEquals("Saved \"Mix 14 Nov 2026 21.30\" (42:10) · rename it in ☰ → My mixes", Menus.savedMix("Mix 14 Nov 2026 21.30", 2530.4))
    }
}
