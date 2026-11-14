package com.mixdeck.ui

import com.mixdeck.engine.Mixer
import com.mixdeck.engine.MusicalKey
import com.mixdeck.engine.ShortArrayPcm
import com.mixdeck.engine.TrackAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A song strip's second line: "Up next: …" (queued or downloading) instead of the artist. */
class UpNextLineTest {
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

    private val up = arrayOfNulls<String>(2)
    private val host = object : Console.Host {
        override fun openLibrary(deck: Int) {}; override fun deckMenu(deck: Int) {}; override fun mainMenu() {}
        override fun toggleRecord() {}; override fun recording() = false; override fun recordSeconds() = 0.0
        override fun loadingText(deck: Int): String? = null; override fun savingText(): String? = null
        override fun upNext(deck: Int) = up[deck]
    }

    /** Draws the console at [w] x [h]; with [keyed], deck A shows a key and a tempo change too (a longer right side). */
    /** Wider letters, like the bold fonts of a real window. */
    private object WideText : TextMetrics by NoText {
        override fun width(s: CharSequence, start: Int, end: Int, size: Float, face: Typeface) = (end - start) * size * 0.62f
    }

    private fun draw(w: Int, h: Int, keyed: Boolean = false, bpm: Double = 93.0, metrics: TextMetrics = NoText): List<String> {
        TextMetrics.current = metrics
        val m = Mixer(8000)
        val sr = 8000; val secs = 60
        val a = TrackAnalysis(bpm, 0.0, 150, FloatArray(150 * secs), FloatArray(150 * secs), FloatArray(150 * secs))
            .let { if (keyed) it.withKey(MusicalKey.fromCamelot("3A"), 0.9f) else it }
        m.decks[0].load(ShortArrayPcm(ShortArray(sr * secs * 2), sr), a, "Air Force Ones", "Nelly feat. Kyjuan", -14.0)
        if (keyed) m.decks[0].pitch = 0.06
        val c = Console(m, host)
        c.layout(w, h)
        return TextCanvas().also { c.draw(it) }.texts
    }

    @Test fun artistUntilSomethingIsQueued() {
        assertTrue("Nelly feat. Kyjuan" in draw(2400, 1080))
        up[0] = "Grillz"
        val texts = draw(2400, 1080)
        assertTrue("Up next: Grillz" in texts, "$texts")
        assertFalse("Nelly feat. Kyjuan" in texts)
    }

    @Test fun aLongTitleKeepsTheDownloadProgress() {
        up[0] = "Air Force Ones (feat. Kyjuan, Ali & Murphy Lee) [Official Music Video] · downloading 42%"
        val wide = draw(2400, 1080).first { it.startsWith("Up next:") }
        assertTrue(wide.endsWith("… · downloading 42%"), wide)
        // Too narrow for "downloading" and enough of the title: just the percentage.
        val narrow = draw(1100, 560).first { it.startsWith("Up next:") }
        assertTrue(narrow.startsWith("Up next: Air") && narrow.endsWith("… · 42%"), narrow)
    }

    /**
     * The top bar with MIX leaves narrower song strips: the progress stays, with as little as one
     * letter of the title, or none.
     */
    @Test fun aNarrowStripKeepsTheProgress() {
        up[0] = "Grillz feat. Akon · downloading 42%"
        for (w in listOf(1100, 800)) {
            val line = draw(w, w * 56 / 110, keyed = true).first { it.startsWith("Up next") }
            assertTrue(line.startsWith("Up next: G") && line.endsWith("… · 42%"), "$w: $line")
        }
        // Wider letters and a tempo in three figures take the last of the room.
        val line = draw(1100, 560, keyed = true, bpm = 127.2, metrics = WideText).first { it.startsWith("Up next") }
        assertEquals("Up next · 42%", line)
    }
}
