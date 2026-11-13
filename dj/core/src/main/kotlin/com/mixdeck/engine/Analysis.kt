package com.mixdeck.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the app needs to know about a track before it is mixed: its tempo, where the beats
 * are, and a coloured waveform (bass in one colour, highs in another, like DJ software).
 */
class TrackAnalysis(
    /** Beats per minute at normal speed, or 0 when no steady beat was found. */
    val bpm: Double,
    /** Position of a beat, in seconds from the start (the beat grid is anchored here). */
    val firstBeatSec: Double,
    /** Waveform bins per second of audio. */
    val binsPerSecond: Int,
    /** Peak level per bin, 0..1. */
    val peak: FloatArray,
    /** Share of the bin's energy that is bass, 0..1 (used to colour the waveform). */
    val bass: FloatArray,
    /** Share of the bin's energy that is treble, 0..1. */
    val treble: FloatArray,
    /** The song's key at normal speed, if known. */
    val key: MusicalKey? = null,
    val keyConfidence: Float = 0f,
) {
    fun withBpm(newBpm: Double, newFirstBeatSec: Double = firstBeatSec) =
        TrackAnalysis(newBpm, newFirstBeatSec, binsPerSecond, peak, bass, treble, key, keyConfidence)

    fun withKey(newKey: MusicalKey?, confidence: Float) =
        TrackAnalysis(bpm, firstBeatSec, binsPerSecond, peak, bass, treble, newKey, confidence)
}

object Analyzer {
    const val BINS_PER_SECOND = 150
    private const val ENV_RATE = 100 // onset envelope frames per second

    fun analyze(pcm: Pcm, progress: (Float) -> Unit = {}): TrackAnalysis {
        val sr = pcm.sampleRate
        val binLen = max(1, sr / BINS_PER_SECOND)
        val bins = (pcm.frames + binLen - 1) / binLen
        val peak = FloatArray(bins); val bass = FloatArray(bins); val treble = FloatArray(bins)

        val hop = max(1, sr / ENV_RATE)
        val hops = pcm.frames / hop
        val envLow = DoubleArray(hops); val envMid = DoubleArray(hops); val envHigh = DoubleArray(hops)

        val lp = Biquad().apply { lowPass(sr, 180.0, 0.707) }
        val hp = Biquad().apply { highPass(sr, 2500.0, 0.707) }
        var bLow = 0.0; var bHigh = 0.0; var bAll = 0.0; var bPeak = 0f
        var hLow = 0.0; var hHigh = 0.0; var hAll = 0.0
        for (i in 0 until pcm.frames) {
            val x = (pcm.left(i) + pcm.right(i)) * 0.5
            val lo = lp.process(x, 0)
            val hi = hp.process(x, 0)
            val ax = abs(x).toFloat()
            if (ax > bPeak) bPeak = ax
            bLow += lo * lo; bHigh += hi * hi; bAll += x * x
            hLow += lo * lo; hHigh += hi * hi; hAll += x * x
            if ((i + 1) % binLen == 0 || i == pcm.frames - 1) {
                val b = i / binLen
                peak[b] = bPeak.coerceAtMost(1f)
                val tot = bAll + 1e-12
                bass[b] = (bLow / tot).toFloat().coerceIn(0f, 1f)
                treble[b] = (bHigh / tot * 4).toFloat().coerceIn(0f, 1f)
                bLow = 0.0; bHigh = 0.0; bAll = 0.0; bPeak = 0f
            }
            if ((i + 1) % hop == 0) {
                val h = i / hop
                if (h < hops) {
                    envLow[h] = hLow; envHigh[h] = hHigh; envMid[h] = max(0.0, hAll - hLow - hHigh)
                }
                hLow = 0.0; hHigh = 0.0; hAll = 0.0
            }
            if (i and 0xFFFF == 0) progress(i.toFloat() / pcm.frames * 0.8f)
        }

        val onset = onsetEnvelope(envLow, envMid, envHigh)
        val (bpm, phaseHops) = detectTempo(onset)
        progress(1f)
        val firstBeat = if (bpm > 0) phaseHops * hop / sr.toDouble() else 0.0
        return TrackAnalysis(bpm, firstBeat, BINS_PER_SECOND, peak, bass, treble)
    }

    /** Spectral-flux style onset strength from three band energies. */
    internal fun onsetEnvelope(low: DoubleArray, mid: DoubleArray, high: DoubleArray): DoubleArray {
        val n = low.size
        val out = DoubleArray(n)
        fun flux(e: DoubleArray, i: Int, w: Double): Double {
            val a = ln(1 + 1000 * e[i]); val b = ln(1 + 1000 * e[i - 1])
            return max(0.0, a - b) * w
        }
        for (i in 1 until n) out[i] = flux(low, i, 1.4) + flux(mid, i, 0.8) + flux(high, i, 1.0)
        // Remove the slowly moving average so loud and quiet sections count the same.
        val win = 50
        val res = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            sum += out[i]
            if (i >= win) sum -= out[i - win]
            val mean = sum / minOf(i + 1, win)
            res[i] = max(0.0, out[i] - mean)
        }
        return res
    }

    /**
     * Finds the tempo and beat phase of an onset envelope sampled at [ENV_RATE] Hz.
     * Returns (bpm, phase in envelope frames), or (0, 0) if there is no clear beat.
     */
    internal fun detectTempo(env: DoubleArray): Pair<Double, Double> {
        val n = env.size
        if (n < ENV_RATE * 4) return 0.0 to 0.0
        val minLag = ENV_RATE * 60 / 200
        val maxLag = ENV_RATE * 60 / 55
        var energy = 0.0
        for (v in env) energy += v * v
        if (energy <= 1e-9) return 0.0 to 0.0

        // 1. Coarse tempo from the autocorrelation, weighted towards the usual dance range.
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            var s = 0.0
            for (i in lag until n) s += env[i] * env[i - lag]
            ac[lag] = s / (n - lag)
        }
        var bestLag = -1; var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * ENV_RATE / lag
            val octaves = ln(bpm / 122.0) / ln(2.0)
            val w = exp(-0.5 * (octaves / 0.55) * (octaves / 0.55))
            // Reward lags whose multiples line up too (a real beat repeats every bar).
            val l2 = lag * 2
            val harm = if (l2 <= maxLag + 1) ac[l2] * 0.5 else 0.0
            val s = (ac[lag] + harm) * w
            if (s > bestScore) { bestScore = s; bestLag = lag }
        }
        if (bestLag < 0) return 0.0 to 0.0
        val coarse = fold(60.0 * ENV_RATE / bestLag)

        // 2. Fine tempo and phase with a comb over the whole track. Tempos a 3:2 apart are
        //    easy to confuse (a 1.5-beat period lines up with kicks and hi-hats), so try those
        //    too and keep whichever grid stands out most from its own off-grid average.
        val smooth = DoubleArray(n)
        for (i in 0 until n) smooth[i] = env[i] + 0.5 * (if (i > 0) env[i - 1] else 0.0) + 0.5 * (if (i + 1 < n) env[i + 1] else 0.0)
        var best = Comb(0.0, 0.0, 0.0, 0.0)
        for (cand in listOf(coarse, fold(coarse * 1.5), fold(coarse / 1.5))) {
            var b = Comb(0.0, 0.0, -1.0, 1.0)
            var bpm = cand * 0.975
            while (bpm <= cand * 1.025) {
                val r = combScore(smooth, bpm)
                if (r.score > b.score) b = r
                bpm += 0.02
            }
            // Prefer a whole-number tempo when it fits (almost all produced music uses one).
            val rounded = b.bpm.roundToInt().toDouble()
            if (abs(rounded - b.bpm) < 0.35) {
                val r = combScore(smooth, rounded)
                if (r.score >= b.score * 0.97) b = r
            }
            if (b.contrast > best.contrast) best = b
        }
        // No steady rhythm (speech, ambient, noise): the best grid barely beats the average.
        if (best.contrast < 2.5) return 0.0 to 0.0
        return best.bpm to best.phase
    }

    private class Comb(val bpm: Double, val phase: Double, val score: Double, val mean: Double) {
        val contrast get() = if (mean > 0) score / mean else 0.0
    }

    private fun fold(bpm: Double): Double {
        var b = bpm
        while (b < 78) b *= 2
        while (b >= 156) b /= 2
        return b
    }

    private fun combScore(env: DoubleArray, bpm: Double): Comb {
        val period = 60.0 * ENV_RATE / bpm
        var bestPhase = 0.0; var bestScore = -1.0; var sum = 0.0; var count = 0
        var phase = 0.0
        while (phase < period) {
            val s = combAt(env, period, phase)
            sum += s; count++
            if (s > bestScore) { bestScore = s; bestPhase = phase }
            phase += 0.5
        }
        return Comb(bpm, bestPhase, bestScore, sum / count)
    }

    private fun combAt(env: DoubleArray, period: Double, phase: Double): Double {
        var s = 0.0
        var t = phase
        val n = env.size - 1
        while (t < n) {
            val i = t.toInt(); val f = t - i
            s += env[i] * (1 - f) + env[i + 1] * f
            t += period
        }
        return s
    }

    /** RMS level of a whole track in dB, used for auto-gain so decks start at similar loudness. */
    fun loudnessDb(pcm: Pcm): Double {
        var sum = 0.0; var count = 0
        val step = max(1, pcm.frames / 400_000)
        var i = 0
        while (i < pcm.frames) {
            val x = (pcm.left(i) + pcm.right(i)) * 0.5
            sum += x * x; count++
            i += step
        }
        return gainToDb(sqrt(sum / max(1, count)))
    }
}
