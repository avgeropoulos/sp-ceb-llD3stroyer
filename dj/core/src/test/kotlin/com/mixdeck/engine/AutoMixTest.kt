package com.mixdeck.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

        // Two beats with no sharp edges, so anything sharp in the mix is MIX's doing: 120 and 124 BPM,
        // each with its own bass note (45 / 70 Hz) and pad (330 / 262 Hz), on a known beat grid.
        val smoothA = smoothBeat(120.0, 60.0, 45.0, 330.0)
        val smoothB = smoothBeat(124.0, 60.0, 70.0, 262.0)

        fun smoothBeat(bpm: Double, seconds: Double, bassHz: Double, padHz: Double, sr: Int = 44100): ShortArrayPcm {
            val n = (seconds * sr).toInt()
            val out = FloatArray(n)
            val beat = 60.0 / bpm
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val tb = t % beat
                // A soft 110 Hz kick: 8 ms raised-cosine attack, decaying, tapered to nothing before the next beat.
                val att = if (tb < 0.008) 0.5 - 0.5 * cos(PI * tb / 0.008) else 1.0
                val taper = if (tb > 0.8 * beat) 0.5 + 0.5 * cos(PI * (tb - 0.8 * beat) / (0.2 * beat)) else 1.0
                val kick = sin(2 * PI * 110 * tb) * att * taper * exp(-tb * 7) * 0.35
                out[i] = (kick + sin(2 * PI * bassHz * t) * 0.25 + sin(2 * PI * padHz * t) * 0.1).toFloat()
            }
            return ShortArrayPcm.fromFloats(out, out, sr)
        }

        /** A beat grid from 0 s with the music starting there (so the first downbeat, and phrase 1, is beat 0). */
        fun grid(bpm: Double, seconds: Int): TrackAnalysis {
            val n = 150 * seconds
            return TrackAnalysis(bpm, 0.0, 150, FloatArray(n) { 0.5f }, FloatArray(n), FloatArray(n))
        }
    }

    private val buf = FloatArray(block * 2)
    private fun Mixer.run(blocks: Int) = repeat(blocks) { render(buf, block) }
    private val Mixer.a get() = decks[0]
    private val Mixer.b get() = decks[1]

    /** "Air Force Ones" (93 BPM) plays on A for a second; "Grillz" (95 BPM) waits on B. */
    private fun setup(bAnalysis: TrackAnalysis = anB, style: AutoMix.Style = AutoMix.Style.SMOOTH): Mixer {
        val m = Mixer(sr)
        m.a.load(pcmA, anA, "Air Force Ones", "Nelly", -14.0)
        m.b.load(pcmB, bAnalysis, "Grillz", "Nelly", -14.0)
        m.autoMix.style = style
        m.a.play()
        m.run(sr / block)
        return m
    }

    /** The smooth beats: A (120 BPM) plays for [playSec]; B (124 BPM) waits. Quiet, so the limiter never acts. */
    private fun smoothSetup(style: AutoMix.Style = AutoMix.Style.SMOOTH, playSec: Double = 1.0): Mixer {
        val m = Mixer(sr)
        m.master = 0.4f
        m.a.load(smoothA, grid(120.0, 60), "Low Rider", "War", -14.0)
        m.b.load(smoothB, grid(124.0, 60), "Pick Up the Pieces", "AWB", -14.0)
        m.autoMix.style = style
        m.a.play()
        m.run((playSec * sr / block).toInt())
        return m
    }

    /** Collects the left channel of everything rendered through [render]. */
    private class Tape {
        var x = FloatArray(1 shl 16); var n = 0
        fun add(b: FloatArray, frames: Int) {
            if (n + frames > x.size) x = x.copyOf(maxOf(x.size * 2, n + frames))
            for (i in 0 until frames) x[n + i] = b[i * 2]
            n += frames
        }
    }

    private fun Mixer.render(tape: Tape) { render(buf, block); tape.add(buf, block) }

    /** The largest second difference: a smooth (low-pitched) signal keeps it tiny, a step in gain shows up at once. */
    private fun maxD2(x: FloatArray, from: Int, to: Int): Double {
        var mx = 0.0
        for (i in maxOf(2, from) until to) mx = maxOf(mx, abs(x[i] - 2.0 * x[i - 1] + x[i - 2]))
        return mx
    }

    /** The amplitude of [f] Hz in [n] samples of [x] from [start] (a Hann-windowed DFT bin). */
    private fun amp(x: FloatArray, start: Int, n: Int, f: Double): Double {
        val w = 2 * PI * f / sr
        var re = 0.0; var im = 0.0; var ws = 0.0
        for (i in 0 until n) {
            val h = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            re += x[start + i] * h * cos(w * i); im -= x[start + i] * h * sin(w * i); ws += h
        }
        return 2 * sqrt(re * re + im * im) / ws
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

    /** (a) B starts from its cue (its first downbeat) on one of A's bar lines and stays in phase. */
    @Test fun startsOnTheBarInPhase() {
        val m = setup()
        m.mixPressed()
        assertEquals(AutoMix.State.ARMED, m.autoMix.state)
        val n = m.autoMix.beatsToStart()
        assertEquals("Grillz starts on deck B in $n beat${if (n == 1) "" else "s"} · tap MIX to cancel", m.autoMix.message)
        assertTrue(n in 1..16, "starts within 4 bars ($n beats)")
        var fbAtStart = 0.0
        var blocks = 0
        while (!m.b.playing && blocks < 15 * sr / block) { fbAtStart = m.a.beatPosition(); m.run(1); blocks++ }
        assertTrue(m.b.playing, "B started")
        assertEquals(AutoMix.State.RUNNING, m.autoMix.state)
        val offBar = fbAtStart - 4 * round(fbAtStart / 4)
        assertTrue(abs(offBar) < 0.05, "B started ${"%.3f".format(offBar)} beats from A's bar line")
        assertEquals("Mixing into Grillz over 8 bars · touch the crossfader to take over", m.autoMix.message)
        m.run(2 * sr / block)
        val err = phaseErrorSec(m)
        assertTrue(err < 0.001, "beats ${"%.2f".format(err * 1000)} ms apart after 2 s")
    }

    /** (b) The crossfader only moves one way, from A's end to B's, on an S-curve (slow, faster, slow). */
    @Test fun crossfaderMovesOneWay() {
        val m = setup()
        m.crossfader = 0f
        m.mixPressed()
        assertEquals(-1f, m.crossfader, "from the centre to A's end before B comes in")
        var last = m.crossfader
        var steps = ArrayList<Float>()
        m.runMix {
            assertTrue(m.crossfader >= last, "never moves back (${m.crossfader} after $last)")
            if (m.autoMix.state == AutoMix.State.RUNNING) steps += m.crossfader - last
            last = m.crossfader
        }
        assertEquals(1f, m.crossfader)
        val k = steps.size
        val edge = steps.subList(0, k / 10).average(); val middle = steps.subList(k * 45 / 100, k * 55 / 100).average()
        assertTrue(middle > 3 * edge, "eased: ${"%.5f".format(edge)} per block at the start, ${"%.5f".format(middle)} in the middle")
    }

    /** (c) The basses swap gradually over the bar around the middle (beats 14 to 18 of 32), never both full. */
    @Test fun bassSwapsSmoothlyAroundTheMiddle() {
        val m = setup()
        m.mixPressed()
        assertEquals(-1f, m.b.eqLow, "the incoming bass is held back")
        var lowA = m.a.eqLow; var lowB = m.b.eqLow
        var swappedAt = -1.0; var messages = 0; var seq = m.autoMix.messageSeq
        m.runMix {
            if (m.autoMix.state != AutoMix.State.RUNNING) return@runMix
            val done = m.autoMix.done
            assertTrue(abs(m.a.eqLow - lowA) < 0.08 && abs(m.b.eqLow - lowB) < 0.08,
                "LOW knobs glide (A ${m.a.eqLow} after $lowA, B ${m.b.eqLow} after $lowB at beat $done)")
            lowA = m.a.eqLow; lowB = m.b.eqLow
            if (done < 13.9) assertEquals(-1f, lowB, "B's bass held back at beat $done")
            if (done < 13.9) assertEquals(0f, lowA, "A's bass full at beat $done")
            val power = AutoMix.knobToGain(lowA).let { it * it } + AutoMix.knobToGain(lowB).let { it * it }
            assertTrue(power <= 1.001, "bass EQs add up to ${"%.3f".format(power)} of one full bass at beat $done")
            if (m.autoMix.messageSeq != seq) {
                seq = m.autoMix.messageSeq
                if (m.autoMix.message == "Bass swapped") { messages++; swappedAt = done }
            }
        }
        assertEquals(1, messages)
        assertTrue(swappedAt in 18.0..18.1, "the swap was done at beat $swappedAt")
        assertEquals(0f, m.b.eqLow)
    }

    /** (d) At the end A is stopped on a clean channel and B plays alone. */
    @Test fun finishesClean() {
        val m = setup()
        m.mixPressed()
        while (m.autoMix.state != AutoMix.State.RUNNING) m.run(1)
        // Things the user may touch during a smooth blend that aren't a take-over (MIX doesn't move them).
        m.a.fx.on = true; m.a.filter = 0.3f; m.a.trimDb = 2.0; m.a.autoLoop(4.0, true)
        m.runMix()
        assertFalse(m.a.playing)
        assertEquals(listOf(0f, 0f, 0f), listOf(m.a.eqLow, m.a.eqMid, m.a.eqHigh))
        assertEquals(0f, m.a.filter); assertEquals(0.0, m.a.trimDb)
        assertFalse(m.a.fx.on); assertFalse(m.a.loopOn)
        assertEquals(1f, m.crossfader)
        assertTrue(m.b.playing)
        assertEquals(listOf(0f, 0f, 0f), listOf(m.b.eqLow, m.b.eqMid, m.b.eqHigh))
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
        m.run(sr / block)
        assertEquals(m.crossfaderGains(), m.channelGains, "the crossfader's own curve, 2 s later")
    }

    /** Turning a knob MIX moves (LOW, or the mids and highs it eases) or a volume fader also takes over; so does a second tap. */
    @Test fun otherControlsTakeOver() {
        for (touch in listOf<(Mixer) -> Unit>({ it.b.eqLow = -0.5f }, { it.a.volume = 0.8f }, { it.b.volume = 0.7f },
            { it.b.eqHigh = 0.3f }, { it.a.eqMid = -0.4f }, { it.mixPressed() })) {
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

    /** Cancelled before B started (nothing was heard): the crossfader and B's knobs and trim go back to how they were. */
    @Test fun cancellingBeforeTheStartPutsThingsBack() {
        for (cancel in listOf<(Mixer) -> Unit>({ it.mixPressed() }, { it.playPressed(1) })) {
            val m = setup()
            m.crossfader = 0f; m.b.eqLow = -0.2f; m.b.eqHigh = 0.1f; m.b.trimDb = 1.5
            m.mixPressed()
            assertEquals(-1f, m.crossfader); assertEquals(-1f, m.b.eqLow)
            assertEquals(0.0, m.b.trimDb, "B brought to A's loudness")
            cancel(m)
            m.run(1)
            assertEquals(AutoMix.State.IDLE, m.autoMix.state)
            assertEquals("MIX cancelled", m.autoMix.message)
            assertEquals(0f, m.crossfader, "crossfader back in the middle")
            assertEquals(-0.2f, m.b.eqLow, "B's LOW back where it was")
            assertEquals(0.1f, m.b.eqHigh); assertEquals(1.5, m.b.trimDb)
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
            m.run(2 * sr / block)
            assertEquals(1f, m.channelGains.second, "B at full volume once MIX has handed back")
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

    /** (g) No beat to follow: B starts now and fades in over 10 seconds, the basses crossing at 5. */
    @Test fun noBeatFadesOverTenSeconds() {
        val m = setup(anB.withBpm(0.0))
        m.mixPressed()
        assertTrue(m.b.playing, "starts now")
        assertEquals("No beat to follow: fading over 10 seconds", m.autoMix.message)
        var crossAt = -1
        var n = 0
        val blocks = m.runMix { n++; if (crossAt < 0 && m.b.eqLow > m.a.eqLow) crossAt = n }
        val secs = blocks * block.toDouble() / sr
        assertTrue(abs(secs - 10) < 0.02, "faded over $secs s")
        val crossSec = crossAt * block.toDouble() / sr
        assertTrue(abs(crossSec - 5) < 0.05, "basses crossed at $crossSec s")
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
        // Counted at A's tempo, block by block (A follows B's tempo glide in the second half).
        var beats = 0.0
        s.runMix { beats += block.toDouble() / sr * s.a.effectiveBpm / 60 }
        assertTrue(abs(beats - 16) < 0.05, "4 bars took $beats beats")
    }

    // ------------------------------------------------------------------ smoothness

    /**
     * No clicks: every style, rendered from two smooth beats, never has a sample-to-sample jump
     * bigger than the songs themselves have when they play alone. The check catches a 0.2% step.
     */
    @Test fun noClicksInAnyStyle() {
        // How sharp the songs are on their own: A, then B at its own tempo.
        val solo = smoothSetup()
        val base = Tape()
        repeat(3 * sr / block) { solo.render(base) }
        val baseA = maxD2(base.x, block, base.n)
        solo.crossfader = 1f; solo.a.pause(); solo.b.play()
        repeat(sr / block) { solo.render(buf, block) }
        val startB = base.n
        repeat(3 * sr / block) { solo.render(base) }
        val baseB = maxD2(base.x, startB + block, base.n)
        val limit = 1.3 * (baseA + baseB)
        // The detector works: a 0.2% step in A's level, where A is loudest, is caught.
        val stepped = base.x.copyOf(startB)
        var peak = sr; for (i in sr until 2 * sr) if (abs(stepped[i]) > abs(stepped[peak])) peak = i
        for (i in peak until stepped.size) stepped[i] *= 1.002f
        assertTrue(maxD2(stepped, peak - 4, peak + 4) > limit, "a 0.2% step stands out (limit ${"%.6f".format(limit)})")

        for (style in AutoMix.STYLES) {
            val m = smoothSetup(style)
            val tape = Tape()
            m.mixPressed()
            var guard = 0
            while (m.autoMix.state != AutoMix.State.IDLE && guard++ < 40 * sr / block) m.render(tape)
            repeat(4 * sr / block) { m.render(tape) } // and the glide and hand-back after it
            assertEquals(AutoMix.State.IDLE, m.autoMix.state, "$style finished")
            val d2 = maxD2(tape.x, block, tape.n)
            assertTrue(d2 < limit, "$style: largest jump ${"%.6f".format(d2)}, the songs alone ${"%.6f".format(baseA)} / ${"%.6f".format(baseB)}")
        }
        // A fade without a beat, too.
        val m = smoothSetup()
        m.b.setAnalysis(m.b.analysis!!.withBpm(0.0))
        val tape = Tape()
        m.mixPressed()
        while (m.autoMix.state != AutoMix.State.IDLE) m.render(tape)
        assertTrue(maxD2(tape.x, block, tape.n) < limit, "no-beat fade")
    }

    /**
     * Bass never doubled: measured in the mix, A's bass note and B's add up to no more than one
     * full bass at any moment, and both are never near full together (nor is the bass ever gone).
     */
    @Test fun bassNeverDoubled() {
        for (style in AutoMix.STYLES) {
            val m = smoothSetup(style)
            val tape = Tape()
            val pitchA = ArrayList<Double>(); val pitchB = ArrayList<Double>(); val running = ArrayList<Boolean>()
            fun rec() { m.render(tape); pitchA += m.a.pitch; pitchB += m.b.pitch; running += m.autoMix.state == AutoMix.State.RUNNING }
            repeat(sr / block) { rec() }
            val win = 8192
            val fullA = amp(tape.x, 0, win, 45.0)
            m.mixPressed()
            var guard = 0
            while (m.autoMix.state != AutoMix.State.IDLE && guard++ < 40 * sr / block) rec()
            repeat(7 * sr / block) { rec() } // Quick's tempo glide carries on after the blend, at most 1% a bar
            val fullB = amp(tape.x, tape.n - win, win, 70.0 * (1 + m.b.pitch))
            assertEquals(0.0, m.b.pitch, "$style: B back at its own tempo")
            var minPower = 9.0; var maxPower = 0.0; var windows = 0
            var s = 0
            while (s + win < tape.n) {
                val b0 = s / block; val b1 = (s + win) / block
                if ((b0 until b1).all { running[it] }) {
                    val mid = (b0 + b1) / 2
                    val ra = amp(tape.x, s, win, 45.0 * (1 + pitchA[mid])) / fullA
                    val rb = amp(tape.x, s, win, 70.0 * (1 + pitchB[mid])) / fullB
                    val power = ra * ra + rb * rb
                    minPower = minOf(minPower, power); maxPower = maxOf(maxPower, power); windows++
                    // One full bass is 1 (doubled would be 2); the kicks make the measurement wobble by about 7%.
                    assertTrue(power < 1.2, "$style: basses add up to ${"%.2f".format(power)} (A ${"%.2f".format(ra)}, B ${"%.2f".format(rb)}) at ${s / sr.toDouble()} s")
                    assertTrue(minOf(ra, rb) < 0.75, "$style: both basses near full (A ${"%.2f".format(ra)}, B ${"%.2f".format(rb)})")
                }
                s += 2048
            }
            assertTrue(windows > 5, "$style: measured $windows windows")
            assertTrue(minPower > 0.3, "$style: the bass never drops out (lowest ${"%.2f".format(minPower)})")
        }
    }

    /**
     * Phrases: B starts from its first downbeat on the A bar that makes the blend end where one of
     * A's phrases does (16 beats from its first downbeat), or where A's sound stops for an echo out.
     */
    @Test fun blendEndsOnThePhrase() {
        for ((style, playSec) in listOf(AutoMix.Style.SMOOTH to 1.0, AutoMix.Style.SMOOTH to 4.6, AutoMix.Style.ECHO to 1.0)) {
            val m = smoothSetup(style, playSec)
            m.mixPressed()
            var startBeat = Double.NaN
            var endBeat = Double.NaN
            var dryEnd = Double.NaN
            var guard = 0
            while (m.autoMix.state != AutoMix.State.IDLE && guard++ < 40 * sr / block) {
                val before = m.a.beatPosition()
                m.run(1)
                if (startBeat.isNaN() && m.b.playing) {
                    startBeat = before
                    // B starts on its own phrase line (its cue, beat 0 of its grid), as far into it as A is past the bar.
                    assertTrue(m.b.beatPosition() in 0.0..0.2, "B started at its beat ${m.b.beatPosition()}")
                }
                if (dryEnd.isNaN() && style == AutoMix.Style.ECHO && !m.a.playing) dryEnd = before
                if (m.autoMix.state == AutoMix.State.IDLE) endBeat = before
            }
            assertTrue(abs(startBeat - 4 * round(startBeat / 4)) < 0.05, "$style: started on a bar ($startBeat)")
            val phraseAt = if (style == AutoMix.Style.ECHO) dryEnd else endBeat
            val off = phraseAt - 16 * round(phraseAt / 16)
            assertTrue(abs(off) < 0.4, "$style after $playSec s: started at beat $startBeat, the phrase point at $phraseAt")
        }
        // No room for a phrase before A's song ends: the next bar, as before.
        val m = smoothSetup()
        m.a.seekFraction(82.5 * m.a.framesPerBeat / m.a.pcm!!.frames)
        m.mixPressed()
        assertEquals(2, m.autoMix.beatsToStart(), "starts on the next bar (beat 84)")
    }

    /**
     * Tempo: B starts at A's 120 BPM and glides to its own 124 over the second half, A following so
     * the beats stay together; it never jumps, and B ends at exactly its own tempo.
     */
    @Test fun tempoGlidesToTheNewSongsOwn() {
        val m = smoothSetup()
        m.mixPressed()
        while (m.autoMix.state != AutoMix.State.RUNNING) m.run(1)
        assertEquals(120.0, m.b.effectiveBpm, 1e-6, "matched to A")
        var pa = m.a.pitch; var pb = m.b.pitch
        var worstPhase = 0.0
        m.runMix {
            assertTrue(abs(m.a.pitch - pa) < 0.0005 && abs(m.b.pitch - pb) < 0.0005, "tempo glides (B ${m.b.pitch} after $pb)")
            pa = m.a.pitch; pb = m.b.pitch
            if (m.autoMix.state == AutoMix.State.RUNNING) {
                worstPhase = maxOf(worstPhase, phaseErrorSec(m))
                if (m.autoMix.done < 15.9) assertEquals(120.0, m.b.effectiveBpm, 1e-6, "no glide before halfway")
            }
        }
        assertTrue(worstPhase < 0.001, "beats ${"%.2f".format(worstPhase * 1000)} ms apart at worst")
        repeat(2 * sr / block) { m.run(1); assertTrue(abs(m.b.pitch - pb) < 0.0005); pb = m.b.pitch }
        assertEquals(0.0, m.b.pitch, "B at its own tempo")
        assertEquals(124.0, m.b.effectiveBpm)

        // A can't reach 124 BPM within its ±2% range: B keeps A's tempo through the blend, then glides alone (at most 1% a bar).
        val n = smoothSetup()
        n.a.pitchRange = 0.02
        n.mixPressed()
        n.runMix { assertTrue(abs(n.a.pitch) <= 0.02 + 1e-12) }
        assertEquals(120.0, n.b.effectiveBpm, 1e-3, "still at A's tempo when the blend ends")
        var last = n.b.pitch; var secs = 0.0
        while (n.b.pitch != 0.0 && secs < 20) {
            n.run(1); secs += block.toDouble() / sr
            val bar = 4 * 60 / n.b.bpm
            assertTrue(abs(n.b.pitch - last) <= 0.01 * 2 * block.toDouble() / sr / bar, "under 1% a bar on average, 2% at the steepest")
            last = n.b.pitch
        }
        assertEquals(0.0, n.b.pitch)
        assertTrue(secs > 6, "took $secs s for 3.2%")
    }

    /** Level: B comes in at A's loudness (A turned up 3 dB, its fader at 90%), and stays there. */
    @Test fun newSongArrivesAtTheSameLoudness() {
        val m = Mixer(sr)
        m.master = 0.3f
        m.a.load(smoothA, grid(120.0, 60), "Low Rider", "War", Analyzer.loudnessDb(smoothA))
        m.b.load(smoothB, grid(124.0, 60), "Pick Up the Pieces", "AWB", Analyzer.loudnessDb(smoothB))
        m.a.trimDb = 3.0; m.a.volume = 0.9f
        m.a.play()
        m.run(sr / block)
        val tape = Tape()
        repeat(2 * sr / block) { m.render(tape) }
        val before = rms(tape.x, 0, tape.n)
        m.mixPressed()
        assertEquals(3 + 40 * log10(0.9), m.b.trimDb, 0.051, "B's trim")
        m.runMix()
        m.run(3 * sr / block)
        val start = tape.n
        repeat(2 * sr / block) { m.render(tape) }
        val after = rms(tape.x, start, tape.n)
        val diff = 20 * log10(after / before)
        assertTrue(abs(diff) < 0.5, "B is ${"%.2f".format(diff)} dB from A")
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    /** Every style runs to the end and leaves a clean handover; each hands back without a jump when the crossfader is touched. */
    @Test fun everyStyleFinishesAndHandsBack() {
        for (style in AutoMix.STYLES) {
            val m = smoothSetup(style)
            m.a.fx.type = FxType.REVERB; m.a.fx.amount = 0.3f
            m.mixPressed()
            while (m.autoMix.state != AutoMix.State.RUNNING) m.run(1)
            assertEquals(when (style) {
                AutoMix.Style.SMOOTH -> "Mixing into Pick Up the Pieces over 8 bars · touch the crossfader to take over"
                AutoMix.Style.ECHO -> "Mixing into Pick Up the Pieces over 8 bars with an echo out · touch the crossfader to take over"
                AutoMix.Style.FILTER -> "Mixing into Pick Up the Pieces over 8 bars with a filter sweep · touch the crossfader to take over"
                AutoMix.Style.QUICK -> "Quick mix into Pick Up the Pieces · touch the crossfader to take over"
            }, m.autoMix.message)
            assertEquals(if (style == AutoMix.Style.QUICK) 4.0 else 32.0, m.autoMix.length)
            var echoed = false; var filtered = 0f
            m.runMix { if (m.a.fx.on) echoed = true; filtered = maxOf(filtered, m.a.filter) }
            assertEquals(style == AutoMix.Style.ECHO, echoed, "$style: echo")
            assertEquals(style == AutoMix.Style.FILTER, filtered > 0.7f, "$style: high-pass up to $filtered")
            assertFalse(m.a.playing); assertTrue(m.b.playing)
            assertEquals(1f, m.crossfader)
            assertEquals(listOf(0f, 0f, 0f, 0f), listOf(m.b.eqLow, m.b.eqMid, m.b.eqHigh, m.b.filter))
            assertEquals(listOf(0f, 0f, 0f, 0f), listOf(m.a.eqLow, m.a.eqMid, m.a.eqHigh, m.a.filter))
            assertFalse(m.a.fx.on)
            assertEquals(FxType.REVERB, m.a.fx.type, "$style: A's effect as it was"); assertEquals(0.3f, m.a.fx.amount)
            assertEquals(1f, m.a.dryLevel)

            // Take-over at 60% (and in the echo's tail): the gains carry on from where MIX had them.
            for (at in if (style == AutoMix.Style.ECHO) listOf(0.6, 0.95) else listOf(0.6)) {
                val t = smoothSetup(style)
                t.mixPressed()
                while (t.autoMix.state != AutoMix.State.RUNNING || t.autoMix.progress < at) t.run(1)
                val g = t.channelGains
                t.crossfader = t.crossfader + 0.1f
                t.run(1)
                assertEquals(AutoMix.State.IDLE, t.autoMix.state)
                assertTrue(t.autoMix.message!!.startsWith("You've got it"), "$style: ${t.autoMix.message}")
                val g2 = t.channelGains
                assertTrue(abs(g2.first - g.first) < 0.01 && abs(g2.second - g.second) < 0.01, "$style at $at: $g then $g2")
                var last = g2
                repeat(2 * sr / block) {
                    t.run(1)
                    val now = t.channelGains
                    assertTrue(abs(now.first - last.first) < 0.01 && abs(now.second - last.second) < 0.01, "$style: glides back ($last, $now)")
                    last = now
                }
                assertEquals(t.crossfaderGains(), t.channelGains, "$style: the crossfader's own curve after the hand-back")
                assertTrue(t.b.playing)
                if (at > 0.9) {
                    assertFalse(t.a.playing, "the echoed-out song stays out")
                    assertEquals(1f, t.a.dryLevel, "and its sound is back on for next time")
                }
            }
        }
    }

    /** Quick: one bar, starting on the next bar, whatever the length setting. */
    @Test fun quickIsFourBeats() {
        val m = smoothSetup(AutoMix.Style.QUICK)
        m.autoMix.bars = 32
        assertEquals(2.0, m.autoMix.mixSeconds(0), 1e-9)
        m.mixPressed()
        assertTrue(m.autoMix.beatsToStart() <= 4)
        while (m.autoMix.state != AutoMix.State.RUNNING) m.run(1)
        assertEquals(AutoMix.Kind.BEATS, m.autoMix.kind)
        assertNotNull(m.autoMix.message)
        val blocks = m.runMix()
        assertTrue(blocks * block.toDouble() / sr < 2.2, "about 2 s at 120 BPM")
    }
}
