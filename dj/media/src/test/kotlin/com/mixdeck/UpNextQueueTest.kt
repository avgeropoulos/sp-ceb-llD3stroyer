package com.mixdeck

import com.mixdeck.engine.AutoMix
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.ShortArrayPcm
import com.mixdeck.engine.TrackAnalysis
import com.mixdeck.ui.Canvas
import com.mixdeck.ui.Console
import com.mixdeck.ui.Paint
import com.mixdeck.ui.Path
import com.mixdeck.ui.PickCheck
import com.mixdeck.ui.RectF
import com.mixdeck.ui.TextMetrics
import com.mixdeck.ui.Typeface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "Up next" and MIX together: during a blend, NEXT → pick → "Load when A ends" waits while MIX
 * plays the old song out, and the song loads by itself (the hosts' 250 ms poll) as soon as MIX
 * has stopped that deck, without disturbing the song MIX brought in.
 */
class UpNextQueueTest {
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

    private val sr = 8000
    private val block = 256
    private val buf = FloatArray(block * 2)
    /** About 250 ms of audio: one tick of the hosts' poll. */
    private val pollBlocks = sr / 4 / block + 1

    /** A silent two-minute song with a made-up tempo (no analysis needed). */
    private fun song(bpm: Double): Pair<ShortArrayPcm, TrackAnalysis> {
        val secs = 120
        val a = TrackAnalysis(bpm, 0.0, 150, FloatArray(150 * secs), FloatArray(150 * secs), FloatArray(150 * secs))
        return ShortArrayPcm(ShortArray(sr * secs * 2), sr) to a
    }

    private fun Mixer.load(i: Int, title: String, bpm: Double) {
        val (p, a) = song(bpm)
        decks[i].load(p, a, title, "Nelly", -14.0)
    }

    private fun Mixer.run(blocks: Int) = repeat(blocks) { render(buf, block) }

    /** "Air Force Ones" (93 BPM) plays on A, "Grillz" (95 BPM) waits on B, and MIX is blending into it. */
    private fun mixing(bars: Int = 4): Mixer {
        val m = Mixer(sr)
        m.autoMix.bars = bars
        m.load(0, "Air Force Ones", 93.0); m.load(1, "Grillz", 95.0)
        m.decks[0].play()
        m.run(sr / block)
        m.mixPressed()
        var guard = 0
        while (m.autoMix.state != AutoMix.State.RUNNING && guard++ < 10 * sr / block) m.run(1)
        assertEquals(AutoMix.State.RUNNING, m.autoMix.state)
        return m
    }

    @Test fun songPickedDuringAMixLoadsWhenMixStopsTheOldDeck() {
        TextMetrics.current = NoText
        val m = mixing()
        val queue = UpNextQueue(m)
        val console = Console(m, object : Console.Host {
            override fun openLibrary(deck: Int) {}; override fun deckMenu(deck: Int) {}; override fun mainMenu() {}
            override fun toggleRecord() {}; override fun recording() = false; override fun recordSeconds() = 0.0
            override fun loadingText(deck: Int): String? = null; override fun savingText(): String? = null
            override fun upNext(deck: Int) = queue[deck]?.title
        })
        console.layout(1600, 820)

        // NEXT during the MIX replaces the song going out, and the library asks "Load when A ends".
        val plan = console.nextPlan()
        assertEquals(0, plan.target)
        val q = assertNotNull(PickCheck.question(m, plan.target, "Hot in Herre"))
        assertEquals("Both decks are playing", q.title)
        // A ends when MIX stops it (4 bars at 93 BPM: about 10 s), not at the end of its song (1:58).
        val mixLeft = assertNotNull(m.autoMix.secondsToFinish())
        assertTrue(mixLeft in 5.0..11.0, "MIX ends in $mixLeft s")
        assertEquals("Deck A ends in ${Console.fmtTime(mixLeft)}. Load \"Hot in Herre\" there when it ends?", q.message)
        assertEquals("Load when A ends", q.label(q.options.first()))
        val p = assertNotNull(Placement.of(q.options.first(), plan.target, sync = true))
        assertEquals(Placement(0, true, Placement.When.ENDS), p)

        // The host queues it: "Up next" on deck A while MIX carries on.
        assertTrue(queue.mustWait(p))
        assertEquals("Up next: \"Hot in Herre\" loads on deck A when \"Air Force Ones\" ends",
            queue.add(0, UpNextQueue.Entry("yt_hot", "Hot in Herre", p.sync)))
        assertTrue(queue.waiting)

        // The hosts' poll, every 250 ms, while the console draws.
        var loaded: Pair<Int, UpNextQueue.Entry>? = null
        var polls = 0
        while (loaded == null && polls++ < 80) {
            m.run(pollBlocks)
            console.draw(NoCanvas)
            val due = queue.due()
            if (m.autoMix.state != AutoMix.State.IDLE) {
                assertTrue(due.isEmpty(), "nothing loads while MIX still plays A out (poll $polls)")
                assertTrue(m.decks[0].playing)
            } else loaded = due.singleOrNull()
        }
        val (deck, e) = assertNotNull(loaded, "the song loaded once MIX finished")
        assertEquals(0, deck)
        assertEquals("Hot in Herre", e.title)
        assertFalse(queue.waiting)
        // MIX finished cleanly: A paused, B alone, and no "tap NEXT" (a song is already on its way).
        assertFalse(m.decks[0].playing)
        assertTrue(m.decks[1].playing)
        assertEquals(1f, m.crossfader)
        assertEquals("Now playing Grillz", console.noticeText)

        // What the host does next: load it (synced to the song now playing) and say it's ready.
        val (pcm, an) = song(100.0)
        synchronized(m.lock) {
            m.decks[0].load(pcm, an, e.title, "Nelly", -14.0)
            if (e.sync) m.sync(0)
        }
        assertEquals("Up next is ready: \"Hot in Herre\" on deck A", UpNextQueue.readyNotice(deck, e))
        val seq = m.autoMix.messageSeq
        m.run(2 * sr / block)
        console.draw(NoCanvas)
        assertEquals(AutoMix.State.IDLE, m.autoMix.state)
        assertEquals(seq, m.autoMix.messageSeq, "loading the old deck after MIX is no take-over")
        assertTrue(m.decks[1].playing, "Grillz plays on")
        assertEquals(1f, m.crossfader)
        val grillz = m.decks[1].effectiveBpm
        assertTrue(abs(m.decks[0].effectiveBpm - grillz) < 0.01, "synced to Grillz: ${m.decks[0].effectiveBpm} vs $grillz")
        assertEquals(1, console.nextPlan().after, "and the next NEXT follows Grillz")
    }

    @Test fun afterATakeOverTheSongWaitsForTheOldSongToStop() {
        val m = mixing()
        val queue = UpNextQueue(m)
        queue.add(0, UpNextQueue.Entry("yt_hot", "Hot in Herre", true))
        synchronized(m.lock) { m.crossfader = 0.3f } // the user grabs the crossfader
        m.run(pollBlocks)
        assertEquals(AutoMix.State.IDLE, m.autoMix.state)
        assertTrue(queue.due().isEmpty(), "A still plays: \"when A ends\" still holds")
        synchronized(m.lock) { m.decks[0].pause() }
        assertEquals(listOf(0), queue.due().map { it.first })
    }

    @Test fun whenToLoadRules() {
        val m = Mixer(sr)
        val queue = UpNextQueue(m)
        m.load(0, "Air Force Ones", 93.0)
        // Nothing plays: every placement loads at once.
        for (w in Placement.When.values()) assertFalse(queue.mustWait(Placement(0, false, w)), "$w on a stopped deck")
        // Holding CUE: ENDS waits, NOW doesn't (it isn't really playing), REPLACE never waits.
        m.decks[0].cueDown(quantize = false)
        assertTrue(queue.mustWait(Placement(0, false, Placement.When.ENDS)))
        assertFalse(queue.mustWait(Placement(0, false, Placement.When.NOW)))
        m.decks[0].cueUp()
        m.decks[0].play()
        assertTrue(queue.mustWait(Placement(0, false, Placement.When.NOW)))
        assertFalse(queue.mustWait(Placement(0, false, Placement.When.REPLACE)))
        // A newer pick replaces the waiting one; clearing forgets it.
        queue.add(0, UpNextQueue.Entry("a", "Grillz", false))
        queue.add(0, UpNextQueue.Entry("b", "Hot in Herre", false))
        assertEquals("Hot in Herre", queue[0]?.title)
        queue.clear(0)
        assertFalse(queue.waiting)
    }

    /**
     * "Stop A after the download": the other deck is checked again once the song is decoded (as the
     * hosts' loadTrack does with fromDownload), so if B stopped while it decoded, A plays on and the
     * song waits for it to end.
     */
    @Test fun stopAAfterTheDownloadIsCheckedAgainWhenTheSongIsReady() {
        val m = Mixer(sr)
        val queue = UpNextQueue(m)
        m.load(0, "Air Force Ones", 93.0); m.load(1, "Grillz", 95.0)
        m.decks[0].play(); m.decks[1].play()
        fun atLoad() = Placement(0, false, Placement.When.REPLACE).afterDownload(PickCheck.live(m, 1))
        // Download done, B still plays: A is stopped.
        val placed = Placement(0, false, Placement.When.REPLACE).afterDownload(PickCheck.live(m, 1))
        assertEquals(Placement.When.REPLACE, placed.whenToLoad)
        assertFalse(queue.mustWait(atLoad()), "B still plays when it's decoded: A stops")
        // B stopped while the song decoded: A keeps the music going.
        m.decks[1].pause()
        assertTrue(queue.mustWait(atLoad()), "nothing else plays: A isn't stopped")
    }
}
