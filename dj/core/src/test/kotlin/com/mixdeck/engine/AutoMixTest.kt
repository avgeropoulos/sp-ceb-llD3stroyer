package com.mixdeck.engine

import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MIX: the one-tap blend from the playing deck into the waiting one. */
class AutoMixTest {
    private val sr = 44100
    private val block = 256

    private companion object {
        val pcmA = synthBeat(93.0, 60.0)
        val pcmB = synthBeat(95.0, 60.0, seed = 2)
        val anA = Analyzer.analyze(pcmA)
        val anB = Analyzer.analyze(pcmB)
    }

    private val buf = FloatArray(block * 2)
    private fun Mixer.run(blocks: Int) = repeat(blocks) { render(buf, block) }
    private val Mixer.a get() = decks[0]
    private val Mixer.b get() = decks[1]

    /** "Air Force Ones" (93 BPM) plays on A for a second; "Grillz" (95 BPM) waits on B. */
    private fun setup(bAnalysis: TrackAnalysis = anB): Mixer {
        val m = Mixer(sr)
        m.a.load(pcmA, anA, "Air Force Ones", "Nelly", -14.0)
        m.b.load(pcmB, bAnalysis, "Grillz", "Nelly", -14.0)
        m.a.play()
        m.run(sr / block)
        return m
    }

    /** Runs block by block until MIX is idle again (at most [maxSec] seconds), calling [each] after every block. */
    private fun Mixer.runMix(maxSec: Double = 60.0, each: () -> Unit = {}): Int {
        var blocks = 0
        while (autoMix.state != AutoMix.State.IDLE && blocks < maxSec * sr / block) { run(1); blocks++; each() }
        assertEquals(AutoMix.State.IDLE, autoMix.state, "MIX finished")
        return blocks
    }

    private fun phaseErrorSec(m: Mixer): Double {
        val d = abs(m.a.beatPhase() - m.b.beatPhase()).let { minOf(it, 1 - it) }
        return d * 60 / m.a.effectiveBpm
    }

    /** (a) B starts on one of A's bar lines and stays in phase. */
    @Test fun startsOnTheBarInPhase() {
        val m = setup()
        m.mixPressed()
        assertEquals(AutoMix.State.ARMED, m.autoMix.state)
        val n = m.autoMix.beatsToStart()
        assertEquals("Grillz starts on deck B in $n beat${if (n == 1) "" else "s"} · tap MIX to cancel", m.autoMix.message)
        assertTrue(n in 1..4)
        var fbAtStart = 0.0
        var blocks = 0
        while (!m.b.playing && blocks < 5 * sr / block) { fbAtStart = m.a.beatPosition(); m.run(1); blocks++ }
        assertTrue(m.b.playing, "B started")
        assertEquals(AutoMix.State.RUNNING, m.autoMix.state)
        val offBar = fbAtStart - 4 * round(fbAtStart / 4)
        assertTrue(abs(offBar) < 0.05, "B started ${"%.3f".format(offBar)} beats from A's bar line")
        assertEquals("Mixing into Grillz over 8 bars · touch the crossfader to take over", m.autoMix.message)
        m.run(2 * sr / block)
        val err = phaseErrorSec(m)
        assertTrue(err < 0.001, "beats ${"%.2f".format(err * 1000)} ms apart after 2 s")
    }

    /** (b) The crossfader only moves one way, from A's end to B's. */
    @Test fun crossfaderMovesOneWay() {
        val m = setup()
        m.crossfader = 0f
        m.mixPressed()
        assertEquals(-1f, m.crossfader, "from the centre to A's end before B comes in")
        var last = m.crossfader
        m.runMix {
            assertTrue(m.crossfader >= last, "never moves back (${m.crossfader} after $last)")
            last = m.crossfader
        }
        assertEquals(1f, m.crossfader)
    }

    /** (c) The basses swap once, halfway (beat 16 of 32). */
    @Test fun bassSwapsOnceHalfway() {
        val m = setup()
        m.mixPressed()
        assertEquals(-1f, m.b.eqLow, "the incoming bass is cut")
        val oneBlock = block.toDouble() / sr * m.a.effectiveBpm / 60
        var swaps = 0; var lowB = m.b.eqLow
        m.runMix {
            if (m.b.eqLow != lowB) {
                lowB = m.b.eqLow
                if (lowB == 0f) {
                    swaps++
                    val done = m.autoMix.done
                    assertTrue(done >= 16 && done <= 16 + oneBlock + 1e-9, "swapped at beat $done")
                    assertEquals(-1f, m.a.eqLow, "A's bass goes out as B's comes in")
                    assertEquals("Bass swapped", m.autoMix.message)
                }
            }
        }
        assertEquals(1, swaps)
    }

    /** (d) At the end A is stopped on a clean channel and B plays alone. */
    @Test fun finishesClean() {
        val m = setup()
        m.mixPressed()
        while (m.autoMix.state != AutoMix.State.RUNNING) m.run(1)
        // Things the user may touch during the blend that aren't a take-over.
        m.a.fx.on = true; m.a.eqHigh = -0.4f; m.a.filter = 0.3f; m.a.trimDb = 2.0; m.a.autoLoop(4.0, true)
        m.runMix()
        assertFalse(m.a.playing)
        assertEquals(listOf(0f, 0f, 0f), listOf(m.a.eqLow, m.a.eqMid, m.a.eqHigh))
        assertEquals(0f, m.a.filter); assertEquals(0.0, m.a.trimDb)
        assertFalse(m.a.fx.on); assertFalse(m.a.loopOn)
        assertEquals(1f, m.crossfader)
        assertTrue(m.b.playing); assertEquals(0f, m.b.eqLow)
        assertEquals("Now playing Grillz · tap NEXT for what fits after it", m.autoMix.message)
    }

    /** (e) Touching the crossfader takes over: everything stays where it is. */
    @Test fun touchingTheCrossfaderTakesOver() {
        val m = setup()
        m.mixPressed()
        while (m.autoMix.state != AutoMix.State.RUNNING || m.autoMix.done < 6) m.run(1)
        val eq = m.decks.map { listOf(it.eqLow, it.eqMid, it.eqHigh) }
        m.crossfader = 0.3f
        m.run(1)
        assertEquals(AutoMix.State.IDLE, m.autoMix.state)
        assertEquals(0.3f, m.crossfader)
        assertEquals(eq, m.decks.map { listOf(it.eqLow, it.eqMid, it.eqHigh) })
        assertTrue(m.a.playing && m.b.playing)
        assertEquals("You've got it: MIX stopped at bar 2 of 8", m.autoMix.message)
        m.run(sr / block)
        assertEquals(0.3f, m.crossfader, "and MIX leaves it alone afterwards")
    }

    /** Turning a LOW knob or a volume fader also takes over; so does a second tap. */
    @Test fun otherControlsTakeOver() {
        for (touch in listOf<(Mixer) -> Unit>({ it.b.eqLow = -0.5f }, { it.a.volume = 0.8f }, { it.b.volume = 0.7f },
            { it.mixPressed() })) {
            val m = setup()
            m.mixPressed()
            while (m.autoMix.state != AutoMix.State.RUNNING || m.autoMix.done < 2) m.run(1)
            touch(m)
            m.run(1)
            assertEquals(AutoMix.State.IDLE, m.autoMix.state)
            assertTrue(m.autoMix.message!!.startsWith("You've got it: MIX stopped at bar 1 of 8"))
        }
        // While waiting for the bar: a second tap cancels and B stays stopped.
        val m = setup()
        m.mixPressed()
        m.mixPressed()
        assertEquals(AutoMix.State.IDLE, m.autoMix.state)
        assertEquals("MIX cancelled", m.autoMix.message)
        m.run(5 * sr / block)
        assertFalse(m.b.playing)
    }

    /** Cancelled before B started (nothing was heard): the crossfader and B's LOW go back to how they were. */
    @Test fun cancellingBeforeTheStartPutsThingsBack() {
        for (cancel in listOf<(Mixer) -> Unit>({ it.mixPressed() }, { it.playPressed(1) })) {
            val m = setup()
            m.crossfader = 0f; m.b.eqLow = -0.2f
            m.mixPressed()
            assertEquals(-1f, m.crossfader); assertEquals(-1f, m.b.eqLow)
            cancel(m)
            m.run(1)
            assertEquals(AutoMix.State.IDLE, m.autoMix.state)
            assertEquals("MIX cancelled", m.autoMix.message)
            assertEquals(0f, m.crossfader, "crossfader back in the middle")
            assertEquals(-0.2f, m.b.eqLow, "B's LOW back where it was")
        }
        // Moving the crossfader cancels too, and it stays where the user put it.
        val m = setup()
        m.crossfader = 0f
        m.mixPressed()
        m.crossfader = -0.5f
        m.run(1)
        assertEquals(AutoMix.State.IDLE, m.autoMix.state)
        assertEquals(-0.5f, m.crossfader)
        assertEquals(0f, m.b.eqLow, "B's LOW back to flat")
    }

    /** Mid-blend, A is paused, ejected or gets a new song: B carries on alone, at full volume with its bass. */
    @Test fun theOldDeckGoingFinishesTheMix() {
        for (gone in listOf<(Mixer) -> Unit>({ it.a.pause() }, { it.a.eject() }, { it.a.load(pcmB, anB, "Hot in Herre", "Nelly", -14.0) })) {
            val m = setup()
            m.mixPressed()
            while (m.autoMix.state != AutoMix.State.RUNNING || m.autoMix.done < 6) m.run(1)
            assertEquals(-1f, m.b.eqLow, "before the swap")
            gone(m)
            m.run(1)
            assertEquals(AutoMix.State.IDLE, m.autoMix.state)
            assertEquals(1f, m.crossfader, "all the way to B")
            assertEquals(0f, m.b.eqLow, "B's bass is back")
            assertTrue(m.b.playing)
            assertFalse(m.a.playing)
            assertEquals("Now playing Grillz · tap NEXT for what fits after it", m.autoMix.message)
        }
    }

    /** The new song ends mid-blend (shorter than the MIX): A plays on as it was before MIX. */
    @Test fun theNewSongEndingGoesBackToTheOldOne() {
        val short = synthBeat(95.0, 12.0, seed = 3)
        val m = setup()
        m.b.load(short, Analyzer.analyze(short), "Jingle", "Nelly", -14.0)
        m.autoMix.bars = 16
        m.crossfader = 0f
        m.mixPressed()
        while (m.autoMix.state != AutoMix.State.IDLE) m.run(1)
        assertFalse(m.b.playing, "the jingle ended")
        assertTrue(m.a.playing)
        assertEquals(-1f, m.crossfader, "back to A's end, where MIX started")
        assertEquals(0f, m.a.eqLow, "A has its bass")
        assertEquals("MIX stopped: Jingle isn't playing", m.autoMix.message)
    }

    /** (f) Six beats left: a short mix that finishes before A runs out. */
    @Test fun shortMixFinishesBeforeTheEnd() {
        val m = setup()
        val frames = pcmA.frames
        m.a.seekFraction((frames - 6.2 * m.a.framesPerBeat) / frames)
        m.mixPressed()
        assertEquals("Short mix: 6 beats left in Air Force Ones", m.autoMix.message)
        assertEquals(AutoMix.Kind.BEATS, m.autoMix.kind)
        var leftAtEnd = 0.0
        m.runMix(maxSec = 6.0) { if (m.autoMix.state == AutoMix.State.IDLE) leftAtEnd = (frames - m.a.position) / m.a.framesPerBeat }
        assertTrue(leftAtEnd > 0.1, "finished $leftAtEnd beats before A's end")
        assertFalse(m.a.playing)
        assertTrue(m.b.playing)
        assertEquals(1f, m.crossfader)
        assertEquals("Now playing Grillz · tap NEXT for what fits after it", m.autoMix.message)
    }

    /** (g) No beat to follow: B starts now and fades in over 10 seconds, the bass swapping at 5. */
    @Test fun noBeatFadesOverTenSeconds() {
        val m = setup(anB.withBpm(0.0))
        m.mixPressed()
        assertTrue(m.b.playing, "starts now")
        assertEquals("No beat to follow: fading over 10 seconds", m.autoMix.message)
        var swapAt = -1
        var n = 0
        val blocks = m.runMix { n++; if (swapAt < 0 && m.b.eqLow == 0f) swapAt = n }
        val secs = blocks * block.toDouble() / sr
        assertTrue(abs(secs - 10) < 0.02, "faded over $secs s")
        val swapSec = swapAt * block.toDouble() / sr
        assertTrue(abs(swapSec - 5) < 0.02, "bass swapped at $swapSec s")
        assertEquals(1f, m.crossfader)
        assertFalse(m.a.playing)
    }

    /** A short loop on A never reaches the bar line: B starts on A's next whole beat instead. */
    @Test fun loopOnTheOldDeckStartsOnTheNextBeat() {
        val m = setup()
        m.a.autoLoop(1.0, true)
        m.mixPressed()
        var blocks = 0
        while (!m.b.playing && blocks < 10 * sr / block) { m.run(1); blocks++ }
        assertTrue(m.b.playing)
        val beats = blocks * block.toDouble() / sr * m.a.effectiveBpm / 60
        assertTrue(beats <= 5.5 + 0.02, "started after $beats beats")
        m.run(sr / block)
        assertTrue(phaseErrorSec(m) < 0.001, "in phase")
    }

    @Test fun needsAPlayingDeckAndASongToMixInto() {
        val m = Mixer(sr)
        m.a.load(pcmA, anA, "Air Force Ones", "Nelly", -14.0)
        m.mixPressed()
        assertEquals("Start a song first: press ▶ on deck A or B", m.autoMix.message)
        m.a.play()
        m.mixPressed()
        assertEquals("Load the next song first: tap NEXT", m.autoMix.message)
        assertEquals(AutoMix.State.IDLE, m.autoMix.state)
        assertEquals(2, m.autoMix.messageSeq)
    }

    @Test fun bothPlayingBlendsAtOnceOutOfTheFavouredDeck() {
        val m = setup()
        m.b.play()
        m.crossfader = 0.4f // favours B
        m.mixPressed()
        assertEquals(AutoMix.State.RUNNING, m.autoMix.state)
        assertEquals(1, m.autoMix.from)
        assertEquals("Mixing into Air Force Ones over 8 bars · touch the crossfader to take over", m.autoMix.message)
        var last = m.crossfader
        m.runMix { assertTrue(m.crossfader <= last); last = m.crossfader }
        assertEquals(-1f, m.crossfader)
        assertFalse(m.b.playing); assertTrue(m.a.playing)
    }

    @Test fun lengthIsFourToThirtyTwoBars() {
        val m = Mixer(sr)
        m.autoMix.bars = 16
        assertEquals(16, m.autoMix.bars)
        m.autoMix.bars = 5
        assertEquals(8, m.autoMix.bars, "anything else is the default")
        val s = setup()
        s.autoMix.bars = 4
        s.mixPressed()
        while (s.autoMix.state != AutoMix.State.RUNNING) s.run(1)
        assertEquals(4, s.autoMix.runBars)
        val blocks = s.runMix()
        val beats = blocks * block.toDouble() / sr * s.a.effectiveBpm / 60
        assertTrue(abs(beats - 16) < 0.05, "4 bars took $beats beats")
    }
}
