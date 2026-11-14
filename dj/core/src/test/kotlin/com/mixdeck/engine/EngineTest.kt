package com.mixdeck.engine

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthesises a simple dance loop: kick on every beat, hi-hat off-beats, snare on 2 and 4, bass line. */
fun synthBeat(bpm: Double, seconds: Double, sr: Int = 44100, offsetSec: Double = 0.0, seed: Int = 1): ShortArrayPcm {
    val n = (seconds * sr).toInt()
    val out = FloatArray(n)
    val rnd = Random(seed)
    val beat = 60.0 / bpm
    for (i in 0 until n) {
        val t = i.toDouble() / sr - offsetSec
        if (t < 0) { out[i] = (rnd.nextDouble() * 2 - 1).toFloat() * 0.01f; continue }
        val bi = (t / beat).toLong()
        val tb = t - bi * beat
        var v = 0.0
        // Kick: falling sine.
        v += sin(2 * PI * (50 * tb + 60 * (1 - exp(-tb * 30)) / 30)) * exp(-tb * 9) * 0.8
        // Snare on beats 2 and 4.
        if (bi % 2 == 1L) v += (rnd.nextDouble() * 2 - 1) * exp(-tb * 25) * 0.35
        // Hi-hat on the off-beat.
        val th = tb - beat / 2
        if (th >= 0) v += (rnd.nextDouble() * 2 - 1) * exp(-th * 80) * 0.2
        // Sustained bass note so the waveform has bass content.
        v += sin(2 * PI * 55 * t) * 0.15
        out[i] = v.toFloat() * 0.8f
    }
    return ShortArrayPcm.fromFloats(out, out, sr)
}

class AnalysisTest {
    @Test fun detectsCommonDanceTempos() {
        for (bpm in listOf(128.0, 124.0, 100.0, 140.0, 92.0)) {
            val a = Analyzer.analyze(synthBeat(bpm, 40.0))
            assertEquals(bpm, a.bpm, 0.15, "detected ${a.bpm} for $bpm")
        }
    }

    @Test fun findsBeatPhase() {
        val bpm = 120.0
        val offset = 0.137
        val a = Analyzer.analyze(synthBeat(bpm, 40.0, offsetSec = offset))
        assertEquals(bpm, a.bpm, 0.15)
        val beat = 60.0 / bpm
        var d = (a.firstBeatSec - offset) % beat
        if (d > beat / 2) d -= beat
        if (d < -beat / 2) d += beat
        assertTrue(abs(d) < 0.03, "beat grid is ${d * 1000} ms off")
    }

    @Test fun handlesNonIntegerTempo() {
        val a = Analyzer.analyze(synthBeat(126.5, 60.0))
        assertEquals(126.5, a.bpm, 0.15)
    }

    @Test fun noiseHasNoTempo() {
        val rnd = Random(3)
        val n = 44100 * 20
        val x = FloatArray(n) { (rnd.nextDouble() * 2 - 1).toFloat() * 0.3f }
        val a = Analyzer.analyze(ShortArrayPcm.fromFloats(x, x, 44100))
        assertEquals(0.0, a.bpm)
    }

    @Test fun waveformHasBassAndPeaks() {
        val a = Analyzer.analyze(synthBeat(128.0, 10.0))
        assertEquals(10 * Analyzer.BINS_PER_SECOND, a.peak.size)
        assertTrue(a.peak.maxOrNull()!! > 0.5f)
        assertTrue(a.bass.average() > 0.2)
    }
}

class DeckTest {
    private val sr = 44100

    private fun loaded(bpm: Double = 120.0, seconds: Double = 30.0): Deck {
        val pcm = synthBeat(bpm, seconds)
        val d = Deck(0, sr)
        d.load(pcm, Analyzer.analyze(pcm), "t", "a", Analyzer.loudnessDb(pcm))
        return d
    }

    private fun run(d: Deck, seconds: Double) {
        val buf = FloatArray(512)
        repeat((seconds * sr / 256).toInt()) { d.render(buf, 256) }
    }

    @Test fun playsAtNormalSpeed() {
        val d = loaded()
        val start = d.positionSec
        d.play(); run(d, 2.0)
        assertEquals(2.0, d.positionSec - start, 0.05)
    }

    @Test fun pitchChangesSpeed() {
        val d = loaded()
        d.pitch = 0.08
        d.play(); val start = d.positionSec; run(d, 5.0)
        assertEquals(5.4, d.positionSec - start, 0.06)
        assertEquals(129.6, d.effectiveBpm, 0.2)
    }

    @Test fun autoLoopRepeats() {
        val d = loaded()
        d.play(); run(d, 1.1)
        d.autoLoop(4.0, quantize = true) // 4 beats at 120 BPM = 2 s
        assertTrue(d.loopOn)
        assertEquals(2.0, (d.loopOut - d.loopIn) / sr, 0.01)
        run(d, 6.0)
        assertTrue(d.position >= d.loopIn && d.position < d.loopOut, "play head stays in the loop")
        d.loopResize(0.5)
        assertEquals(1.0, (d.loopOut - d.loopIn) / sr, 0.01)
    }

    @Test fun cueButtonWorksLikeACdj() {
        val d = loaded()
        d.play(); run(d, 1.0)
        d.cueDown(quantize = false) // playing → back to cue and stop
        assertTrue(!d.playing)
        assertEquals(d.cuePoint, d.position, 0.5)
        run(d, 0.2)
        d.jogMove(0.5) // move away while paused
        d.cueDown(quantize = false) // paused away from cue → sets a new cue
        val newCue = d.cuePoint
        assertEquals(d.position, newCue, 0.5)
        d.cueDown(quantize = false) // at cue → preview while held
        assertTrue(d.playing)
        run(d, 0.5)
        d.cueUp()
        assertTrue(!d.playing)
        assertEquals(newCue, d.position, 0.5)
    }

    @Test fun hotCuesSetThenJump() {
        val d = loaded()
        d.play(); run(d, 3.0)
        d.hotCue(0, quantize = false)
        val hc = d.hotCues[0]
        run(d, 2.0)
        d.hotCue(0, quantize = false)
        assertEquals(hc, d.position, 600.0)
    }

    @Test fun syncMatchesTempoAndPhase() {
        val a = loaded(124.0); val b = loaded(120.0)
        a.play(); b.play()
        run(a, 1.3); run(b, 0.4)
        b.syncTo(a)
        assertEquals(124.0, b.effectiveBpm, 0.05)
        val dp = abs(a.beatPhase() - b.beatPhase())
        assertTrue(dp < 0.02 || dp > 0.98, "phases ${a.beatPhase()} vs ${b.beatPhase()}")
    }

    @Test fun syncUsesHalfTimeWhenCloser() {
        val a = loaded(170.0); val b = loaded(86.0)
        b.syncTo(a)
        assertEquals(85.0, b.bpm * (1 + b.pitch), 0.1)
    }

    @Test fun scratchFollowsTheHand() {
        val d = loaded()
        val start = d.position
        d.jogTouch(true)
        d.jogMove(0.25) // a quarter turn = 0.45 s forward
        run(d, 0.3)
        assertEquals(0.45, (d.position - start) / sr, 0.02)
        d.jogMove(-0.25)
        run(d, 0.3)
        assertEquals(0.0, (d.position - start) / sr, 0.02)
        d.jogTouch(false)
    }

    @Test fun eqKillRemovesBass() {
        val sr = 44100
        val n = sr * 2
        val bass = FloatArray(n) { (sin(2 * PI * 60 * it / sr) * 0.5).toFloat() }
        val pcm = ShortArrayPcm.fromFloats(bass, bass, sr)
        fun levelWith(low: Float): Float {
            val d = Deck(0, sr)
            d.load(pcm, Analyzer.analyze(pcm), "", "", -14.0)
            d.eqLow = low
            d.play()
            val buf = FloatArray(512)
            var peak = 0f
            repeat(200) { d.render(buf, 256); if (it > 50) buf.forEach { v -> peak = maxOf(peak, abs(v)) } }
            return peak
        }
        assertTrue(levelWith(-1f) < levelWith(0f) * 0.1f)
    }

    @Test fun loadResetsChannel() {
        val d = loaded()
        d.pitch = 0.05; d.eqLow = -1f; d.eqMid = 0.4f; d.eqHigh = -0.3f; d.filter = 0.5f; d.trimDb = 3.0
        d.volume = 0.2f; d.fx.type = FxType.REVERB; d.fx.amount = 0.9f; d.fx.on = true
        d.headphone = true; d.pitchRange = 0.16
        val pcm = synthBeat(100.0, 10.0)
        d.load(pcm, Analyzer.analyze(pcm), "next", "b", -14.0)
        assertEquals(0.0, d.pitch); assertEquals(0f, d.eqLow); assertEquals(0f, d.eqMid); assertEquals(0f, d.eqHigh)
        assertEquals(0f, d.filter); assertEquals(0.0, d.trimDb); assertEquals(1f, d.volume)
        assertFalse(d.fx.on, "effect off")
        assertEquals(FxType.REVERB, d.fx.type, "effect type kept"); assertEquals(0.9f, d.fx.amount, "amount kept")
        assertTrue(d.headphone, "🎧 kept"); assertEquals(0.16, d.pitchRange, "tempo range kept")
        assertFalse(d.playing); assertFalse(d.pendingStart)
    }

    @Test fun cueSkipsSilenceOnABar() {
        val bpm = 120.0
        val pcm = synthBeat(bpm, 30.0, offsetSec = 4.0)
        val a = Analyzer.analyze(pcm)
        assertEquals(bpm, a.bpm, 0.15)
        val d = Deck(0, sr)
        d.load(pcm, a, "t", "a", Analyzer.loudnessDb(pcm))
        val cue = d.cuePoint / sr
        assertTrue(cue in 2.0..4.05, "cue at $cue s")
        val beat = 60.0 / a.bpm
        val beats = (cue - a.firstBeatSec) / beat
        val offBar = abs(beats / 4 - Math.round(beats / 4)) * 4
        assertTrue(offBar < 0.01, "cue is $beats beats from the grid anchor, not on a bar")
        assertEquals(d.cuePoint, d.position, "the play head starts on the cue")
    }

    @Test fun noSilenceKeepsTheOldCue() {
        val pcm = synthBeat(120.0, 30.0)
        val a = Analyzer.analyze(pcm)
        val beat = 60.0 / a.bpm
        val old = ((a.firstBeatSec % beat) + beat) % beat
        assertEquals(old, a.startCueSec(), 1e-9)
        val d = Deck(0, sr)
        d.load(pcm, a, "t", "a", -14.0)
        assertEquals(old * sr, d.cuePoint, 1e-6)
    }

    @Test fun noBeatCuesAtTheStart() {
        val a = TrackAnalysis(0.0, 0.3, 150, FloatArray(1500) { if (it > 600) 0.5f else 0f }, FloatArray(1500), FloatArray(1500))
        assertEquals(0.0, a.startCueSec())
    }

    @Test fun startAtHasNoSpinUpLag() {
        val d = loaded()
        val from = d.cuePoint + 1000
        d.startAt(from)
        assertTrue(d.playing)
        val buf = FloatArray(512)
        val blocks = sr / 256
        repeat(blocks) { d.render(buf, 256) }
        val expected = from + blocks * 256.0
        assertTrue(abs(d.position - expected) < sr * 0.001, "${(d.position - expected) / sr * 1000} ms off after 1 s")
    }

    @Test fun beatPositionCountsBeatsFromTheGrid() {
        val d = loaded(120.0)
        val a = d.analysis!!
        d.startAt(a.firstBeatSec * sr + 10.5 * d.framesPerBeat)
        assertEquals(10.5, d.beatPosition(), 1e-9)
        assertEquals(10L, d.beatIndex())
    }
}

/** ▶ with quantize: the deck waits for the other deck's beat and starts in phase with it. */
class QuantizedPlayTest {
    private val sr = 44100

    private companion object {
        val pcmA = synthBeat(124.0, 60.0)
        val pcmB = synthBeat(120.0, 60.0, seed = 2)
        val anA = Analyzer.analyze(pcmA)
        val anB = Analyzer.analyze(pcmB)
    }

    private val buf = FloatArray(512)
    private fun Mixer.run(blocks: Int) = repeat(blocks) { render(buf, 256) }

    /** Deck A plays; deck B is cued and SYNCed (tempo only, as it isn't playing). */
    private fun setup(quantize: Boolean = true): Mixer {
        val m = Mixer(sr)
        m.decks[0].load(pcmA, anA, "A", "", -14.0)
        m.decks[1].load(pcmB, anB, "B", "", -14.0)
        m.quantize = quantize
        m.decks[0].play()
        m.run(sr / 2 / 256) // past A's spin-up
        m.sync(1)
        return m
    }

    /** Distance between the two decks' beats, in seconds. */
    private fun phaseError(m: Mixer): Double {
        val a = m.decks[0]; val b = m.decks[1]
        val d = abs(a.beatPhase() - b.beatPhase()).let { minOf(it, 1 - it) }
        return d * 60 / a.effectiveBpm
    }

    @Test fun quantizedPlayLandsOnTheBeat() {
        val rnd = Random(7)
        var waited = 0; var immediate = 0
        repeat(20) {
            val m = setup()
            m.run(rnd.nextInt(1, 400))
            val b = m.decks[1]
            m.playPressed(1)
            if (b.pendingStart) waited++ else immediate++
            var blocks = 0
            while (!b.playing && blocks < 1000) { m.run(1); blocks++ }
            assertTrue(b.playing, "B started")
            val beatSec = 60 / m.decks[0].effectiveBpm
            assertTrue(blocks * 256.0 / sr <= beatSec + 256.0 / sr, "started ${blocks * 256.0 / sr} s after the press")
            m.run(2 * sr / 256)
            val err = phaseError(m)
            assertTrue(err < 0.001, "beats ${err * 1000} ms apart")
        }
        assertTrue(waited > 0 && immediate > 0, "both cases tried ($waited waited, $immediate at once)")
    }

    @Test fun quantizeOffStartsAtOnce() {
        val m = setup(quantize = false)
        m.run(37)
        m.playPressed(1)
        assertTrue(m.decks[1].playing)
        assertFalse(m.decks[1].pendingStart)
    }

    @Test fun secondTapCancels() {
        val m = setup()
        while (m.decks[0].beatPhase() !in 0.3..0.8) m.run(1)
        m.playPressed(1)
        assertTrue(m.decks[1].pendingStart, "waits for A's next beat")
        assertFalse(m.decks[1].playing)
        m.playPressed(1)
        assertFalse(m.decks[1].pendingStart, "second tap cancels")
        m.run(3 * sr / 256)
        assertFalse(m.decks[1].playing, "and it stays stopped")
    }

    @Test fun waitingDeckStartsWhenTheOtherStops() {
        val m = setup()
        while (m.decks[0].beatPhase() !in 0.3..0.8) m.run(1)
        m.playPressed(1)
        assertTrue(m.decks[1].pendingStart)
        m.decks[0].pause()
        m.run(1)
        assertTrue(m.decks[1].playing)
    }

    /** In a loop of a beat or less A's beat never changes: B starts when A's beat comes round again. */
    @Test fun waitingDeckStartsInsideAShortLoop() {
        for (beats in listOf(1.0, 0.5)) {
            val m = setup()
            m.decks[0].autoLoop(beats, true)
            while (m.decks[0].beatPhase() !in 0.3..0.45) m.run(1)
            m.playPressed(1)
            assertTrue(m.decks[1].pendingStart, "waits for A's beat ($beats-beat loop)")
            var blocks = 0
            while (!m.decks[1].playing && blocks < 2 * sr / 256) { m.run(1); blocks++ }
            assertTrue(m.decks[1].playing, "B started inside A's $beats-beat loop")
            assertTrue(blocks * 256.0 / sr <= 60 / m.decks[0].effectiveBpm + 256.0 / sr, "within a beat")
            m.run(1)
            assertTrue(phaseError(m) < 0.001, "in phase")
        }
    }

    @Test fun notInTimeJustPlays() {
        val m = setup()
        m.decks[1].pitch = 0.0 // B at its own 120 BPM against A's 124: no quantized start
        while (m.decks[0].beatPhase() !in 0.3..0.8) m.run(1)
        m.playPressed(1)
        assertTrue(m.decks[1].playing)
        assertFalse(m.decks[1].pendingStart)
    }
}

class MixerTest {
    @Test fun crossfaderEndsAndMiddle() {
        val m = Mixer(44100)
        for (c in XfadeCurve.values()) {
            val (a0, b0) = m.crossfaderGains(-1f, c)
            assertEquals(1f, a0, 0.001f); assertEquals(0f, b0, 0.001f)
            val (a1, b1) = m.crossfaderGains(1f, c)
            assertEquals(0f, a1, 0.001f); assertEquals(1f, b1, 0.001f)
            val (am, bm) = m.crossfaderGains(0f, c)
            assertEquals(1f, am, 0.01f); assertEquals(1f, bm, 0.01f)
        }
    }

    @Test fun masterNeverClipsAndEffectsRun() {
        val sr = 44100
        val m = Mixer(sr)
        val pcm = synthBeat(128.0, 20.0)
        val an = Analyzer.analyze(pcm)
        for (d in m.decks) {
            d.load(pcm, an, "", "", -30.0) // forces +12 dB auto-gain to stress the limiter
            d.trimDb = 6.0; d.eqLow = 1f; d.play()
        }
        val out = FloatArray(512)
        for (type in FxType.values()) {
            m.decks[0].fx.type = type; m.decks[0].fx.on = true; m.decks[0].fx.amount = 1f
            m.decks[1].filter = if (type.ordinal % 2 == 0) -0.6f else 0.6f
            m.sampler.trigger(type.ordinal)
            repeat(400) {
                m.render(out, 256)
                for (v in out) assertTrue(abs(v) <= 1f && !v.isNaN(), "$type produced $v")
            }
        }
    }

    @Test fun splitCuePutsCueInRightEar() {
        val sr = 44100
        val m = Mixer(sr)
        val pcm = synthBeat(128.0, 5.0)
        val an = Analyzer.analyze(pcm)
        m.decks[1].load(pcm, an, "", "", -14.0); m.decks[1].play(); m.decks[1].headphone = true
        m.crossfader = -1f // deck B is not in the mix
        m.splitCue = true
        val out = FloatArray(512)
        var left = 0f; var right = 0f
        repeat(300) { n -> m.render(out, 256); if (n > 10) for (i in 0 until 256) { left = maxOf(left, abs(out[i * 2])); right = maxOf(right, abs(out[i * 2 + 1])) } }
        assertTrue(left < 0.01f, "mix is silent")
        assertTrue(right > 0.1f, "cue is audible")
    }

    @Test fun recordsWav() {
        val f = File.createTempFile("mix", ".wav")
        val w = WavWriter(f, 44100)
        val m = Mixer(44100)
        m.sampler.trigger(0)
        m.tap = { buf, n -> w.write(buf, n) }
        val out = FloatArray(512)
        repeat(172) { m.render(out, 256) }
        w.close()
        val bytes = f.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(44 + 172 * 256 * 4, bytes.size)
        f.delete()
    }

    /**
     * A recording cut off before it was closed (MixDeck killed, storage full) has 0 sizes in its
     * header and maybe half a frame at the end: repair makes it a WAV of what was written.
     */
    @Test fun aRecordingCutOffIsRepaired() {
        val f = File.createTempFile("recording-", ".wav")
        val w = WavWriter(f, 48000)
        w.write(FloatArray(48000 * 2) { 0.25f }, 48000)
        w.close()
        java.io.RandomAccessFile(f, "rw").use { raf ->
            raf.seek(4); raf.write(ByteArray(4)); raf.seek(40); raf.write(ByteArray(4))
            raf.seek(raf.length()); raf.write(byteArrayOf(1, 2)) // half a frame
        }
        assertEquals(1.0, WavWriter.repair(f), 1e-9)
        val h = java.nio.ByteBuffer.wrap(f.readBytes(), 0, 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(48000 * 4, h.getInt(40), "data size")
        assertEquals(36 + 48000 * 4, h.getInt(4), "RIFF size")
        assertEquals(48000, h.getInt(24), "sample rate kept")
        f.delete()
    }

    /** Recordings left behind are found by name, oldest first; one still being written isn't. */
    @Test fun leftoverRecordingsAreFound() {
        val dir = java.nio.file.Files.createTempDirectory("mixdeck").toFile()
        val now = System.currentTimeMillis()
        val older = Recordings.newFile(dir, 1_000).apply { writeBytes(ByteArray(44)); setLastModified(now - 600_000) }
        val newer = Recordings.newFile(dir, 2_000).apply { writeBytes(ByteArray(44)); setLastModified(now - 600_000) }
        Recordings.newFile(dir, 3_000).apply { writeBytes(ByteArray(44)); setLastModified(now) }
        File(dir, "mix-encode.m4a").writeBytes(ByteArray(4))
        assertEquals(2_000L, Recordings.startedAt(newer))
        assertEquals(null, Recordings.startedAt(File(dir, "mix-encode.m4a")))
        assertEquals(listOf(older, newer), Recordings.leftovers(listOf(dir), idleMs = 60_000, now = now))
        assertEquals(3, Recordings.leftovers(listOf(dir), idleMs = 0, now = now).size)
    }
}
