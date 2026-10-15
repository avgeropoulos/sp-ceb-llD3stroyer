package com.bouzouki.core.song

import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeatsTest {
    private val fps = Beats.FPS

    /** An onset envelope with a peak at each time (split between the two nearest frames) over light noise. */
    private fun envelope(seconds: Double, onsets: List<Pair<Double, Double>>, noise: Double = 0.05, seed: Long = 1): FloatArray {
        val rnd = java.util.Random(seed)
        val e = FloatArray((seconds * fps).toInt()) { (rnd.nextDouble() * noise).toFloat() }
        for ((t, a) in onsets) {
            val c = t * fps
            val i = floor(c).toInt()
            val f = c - i
            if (i in e.indices) e[i] += (a * (1 - f)).toFloat()
            if (i + 1 in e.indices) e[i + 1] += (a * f).toFloat()
            if (i + 2 in e.indices) e[i + 2] += (a * 0.3).toFloat() // a short decay, like a plucked note
        }
        return e
    }

    private fun grid(bpm: Double, from: Double, to: Double): List<Double> {
        val p = 60.0 / bpm
        return generateSequence(from) { it + p }.takeWhile { it < to }.toList()
    }

    /** F-measure of [found] against [truth], each truth beat matched at most once within ±[tol] s. */
    private fun fMeasure(found: List<Double>, truth: List<Double>, tol: Double = 0.07): Double {
        if (found.isEmpty() || truth.isEmpty()) return 0.0
        val used = BooleanArray(truth.size)
        var hits = 0
        for (b in found) {
            var best = -1
            for (i in truth.indices) if (!used[i] && abs(truth[i] - b) <= tol && (best < 0 || abs(truth[i] - b) < abs(truth[best] - b))) best = i
            if (best >= 0) { used[best] = true; hits++ }
        }
        val p = hits.toDouble() / found.size
        val r = hits.toDouble() / truth.size
        return if (hits == 0) 0.0 else 2 * p * r / (p + r)
    }

    private fun assertTempo(expected: Double, got: Double, tolerance: Double = 0.015) =
        assertTrue(abs(got - expected) / expected < tolerance, "expected $expected BPM, got $got")

    @Test
    fun tempoOfSteadyPulsesAtSeveralTempi() {
        for (bpm in listOf(66.0, 84.0, 100.0, 117.0, 132.0, 150.0)) {
            val env = envelope(30.0, grid(bpm, 0.5, 30.0).map { it to 1.0 }, seed = bpm.toLong())
            assertTempo(bpm, Beats.tempo(env, fps))
        }
    }

    @Test
    fun beatsLandOnThePulses() {
        for (bpm in listOf(76.0, 98.0, 125.0, 143.0)) {
            val truth = grid(bpm, 1.0, 40.0)
            val env = envelope(41.0, truth.map { it to 1.0 }, seed = 7)
            val beats = Beats.estimate(env, fps)
            assertTrue(fMeasure(beats, truth) > 0.95, "$bpm BPM: F ${fMeasure(beats, truth)}")
            // And to within a few milliseconds, thanks to the refinement between frames.
            val err = beats.map { b -> truth.minOf { abs(it - b) } }.average()
            assertTrue(err < 0.012, "$bpm BPM: mean error $err s")
        }
    }

    @Test
    fun weakerOffbeatsDoNotDoubleTheTempo() {
        for (bpm in listOf(90.0, 96.0, 110.0)) {
            val beats = grid(bpm, 0.5, 30.0)
            val p = 60.0 / bpm
            val onsets = beats.map { it to 1.0 } + beats.map { it + p / 2 to 0.45 }
            val env = envelope(31.0, onsets, seed = 3)
            assertTempo(bpm, Beats.tempo(env, fps))
            assertTrue(fMeasure(Beats.estimate(env, fps), beats) > 0.9, "$bpm BPM")
        }
    }

    @Test
    fun accentedDownbeatsDoNotHalveTheTempo() {
        val bpm = 128.0
        val beats = grid(bpm, 0.5, 30.0)
        val env = envelope(31.0, beats.mapIndexed { i, t -> t to if (i % 2 == 0) 1.0 else 0.65 }, seed = 5)
        assertTempo(bpm, Beats.tempo(env, fps))
    }

    @Test
    fun fastPulsesGiveTheTempoOrHalfOfItOnTheSameGrid() {
        // A bare click at 180 BPM is just as much 90 BPM in eighth notes: either is right,
        // as long as the beats fall on clicks.
        val truth = grid(180.0, 0.5, 30.0)
        val env = envelope(31.0, truth.map { it to 1.0 }, seed = 11)
        val bpm = Beats.tempo(env, fps)
        assertTrue(abs(bpm - 180) < 3 || abs(bpm - 90) < 1.5, "got $bpm")
        val beats = Beats.estimate(env, fps)
        val onClicks = beats.count { b -> truth.any { abs(it - b) < 0.05 } }
        assertTrue(onClicks >= beats.size * 0.95, "$onClicks of ${beats.size} beats on clicks")
        assertTrue(beats.size >= truth.size / 2 * 0.9)
    }

    @Test
    fun missingAndExtraOnsetsAreBridged() {
        val bpm = 112.0
        val truth = grid(bpm, 1.0, 45.0)
        val rnd = java.util.Random(42)
        val kept = truth.filter { rnd.nextDouble() > 0.25 }.map { it + (rnd.nextDouble() - 0.5) * 0.02 to 1.0 }
        val extra = List(truth.size * 3 / 10) { rnd.nextDouble() * 45.0 to 0.2 + 0.5 * rnd.nextDouble() }
        val env = envelope(46.0, kept + extra, seed = 9)
        assertTempo(bpm, Beats.tempo(env, fps), 0.02)
        val beats = Beats.estimate(env, fps)
        assertTrue(fMeasure(beats, truth) > 0.9, "F ${fMeasure(beats, truth)}")
    }

    @Test
    fun beatsStartWithTheMusicAndKeepTheGridThroughABreak() {
        val bpm = 120.0
        val truth = grid(bpm, 4.0, 50.0)
        val playing = truth.filter { it < 24.0 || it >= 30.0 } // six silent seconds in the middle
        val env = envelope(52.0, playing.map { it to 1.0 }, noise = 0.02, seed = 13)
        val beats = Beats.estimate(env, fps)
        assertTrue(beats.first() > 3.8, "first beat ${beats.first()}")
        assertTrue(beats.last() < 50.2, "last beat ${beats.last()}")
        assertTrue(fMeasure(beats, truth) > 0.93, "F ${fMeasure(beats, truth)}")
    }

    @Test
    fun beatsFollowAGentleAccelerando() {
        // 100 → 110 BPM over a minute.
        val truth = ArrayList<Double>()
        var t = 1.0
        while (t < 60.0) { truth += t; t += 60.0 / (100.0 + 10.0 * t / 60.0) }
        val env = envelope(61.0, truth.map { it to 1.0 }, seed = 17)
        val beats = Beats.estimate(env, fps)
        assertTrue(fMeasure(beats, truth) > 0.9, "F ${fMeasure(beats, truth)}")
    }

    @Test
    fun envelopePutsOnsetsAtTheirTimes() {
        val n = 1200
        val onsetFrames = listOf(100, 400, 800, 1150)
        val frames = Array(n) { FloatArray(BasicPitch.N_KEYS) }
        val onsets = Array(n) { FloatArray(BasicPitch.N_KEYS) }
        for (f in onsetFrames) {
            onsets[f][40] = 0.9f
            for (t in f until minOf(n, f + 20)) frames[t][40] = 0.8f // the note sounding after it
        }
        val env = Beats.envelope(BasicPitch.Activations(frames, onsets))
        assertEquals(kotlin.math.floor(BasicPitch.frameTime(n - 1) * fps).toInt() + 1, env.size)
        for (f in onsetFrames) {
            val expected = BasicPitch.frameTime(f) * fps
            val near = (expected.toInt() - 3..expected.toInt() + 3).filter { it in env.indices }
            val peak = near.maxBy { env[it] }
            assertTrue(abs(peak - expected) <= 1.0, "onset at frame $f: peak $peak, expected $expected")
            assertTrue(env[peak] > 0.5f)
        }
        assertTrue(env[50] == 0f && env[600] == 0f)
    }

    @Test
    fun quietOrTinyInputsGiveNoBeats() {
        assertTrue(Beats.estimate(FloatArray(0), fps).isEmpty())
        assertTrue(Beats.estimate(FloatArray(500), fps).isEmpty())
        assertTrue(Beats.envelope(BasicPitch.Activations(emptyArray(), emptyArray())).isEmpty())
        val bpm = Beats.tempo(FloatArray(3000) { (java.util.Random(it.toLong()).nextDouble() * 0.1).toFloat() }, fps)
        assertTrue(bpm in Beats.MIN_BPM..Beats.MAX_BPM)
        assertEquals(0.5, Beats.period(listOf(1.0, 1.5, 2.0, 2.6))!!, 1e-9)
    }
}
