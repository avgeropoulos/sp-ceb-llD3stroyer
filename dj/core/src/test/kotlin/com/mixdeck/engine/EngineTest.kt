package com.mixdeck.engine

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
