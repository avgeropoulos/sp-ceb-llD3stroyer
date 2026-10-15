package com.bouzouki.core.song

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Beat tracking on Basic Pitch's output, so the tab can be laid out in bars:
 * 1. an onset-strength envelope from the note and onset activations,
 * 2. the tempo, by autocorrelation weighted towards common tempi,
 * 3. the beats, by dynamic programming (D. Ellis, "Beat Tracking by Dynamic Programming", 2007,
 *    the method librosa uses): every beat sits on strong onsets, one beat period after the last.
 */
object Beats {
    /** Frames per second of [envelope]'s output: Basic Pitch's hop, on a uniform time grid. */
    val FPS: Double = BasicPitch.SAMPLE_RATE.toDouble() / BasicPitch.FFT_HOP

    const val MIN_BPM = 60.0
    const val MAX_BPM = 200.0
    /** Centre of the tempo preference, and its width in octaves. */
    private const val PRIOR_BPM = 110.0
    private const val PRIOR_OCTAVES = 1.0
    /** How strongly the beat tracker keeps to the tempo (librosa's default). */
    private const val TIGHTNESS = 100.0

    /**
     * Onset strength per frame, at [FPS] frames per second from time 0: every rise of the onset
     * activations, plus half of every rise of the note activations (for notes that start without
     * a clear onset), summed over the keys. Rises rather than levels, because the activations sit
     * on a floor of about 0.1 on every key, which summed over 88 keys would drown the onsets.
     * Basic Pitch's own frames drift slightly against the clock (see [BasicPitch.frameTime]);
     * they are resampled here so that frame `j` is at exactly `j / FPS` seconds, like the notes.
     */
    fun envelope(act: BasicPitch.Activations): FloatArray {
        val n = min(act.frames.size, act.onsets.size)
        if (n == 0) return FloatArray(0)
        val raw = FloatArray(n)
        for (t in 1 until n) {
            val onset = act.onsets[t]
            val prevOnset = act.onsets[t - 1]
            val note = act.frames[t]
            val prevNote = act.frames[t - 1]
            var s = 0f
            for (k in onset.indices) {
                val rise = onset[k] - prevOnset[k]
                if (rise > 0f) s += rise
                val noteRise = note[k] - prevNote[k]
                if (noteRise > 0f) s += 0.5f * noteRise
            }
            raw[t] = s
        }
        if (n == 1) return raw
        val out = FloatArray(floor(BasicPitch.frameTime(n - 1) * FPS).toInt() + 1)
        var i = 0
        var t0 = BasicPitch.frameTime(0)
        var t1 = BasicPitch.frameTime(1)
        for (j in out.indices) {
            val tj = j / FPS
            while (i < n - 2 && t1 <= tj) { i++; t0 = t1; t1 = BasicPitch.frameTime(i + 1) }
            val a = ((tj - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
            out[j] = (raw[i] * (1 - a) + raw[i + 1] * a).toFloat()
        }
        return out
    }

    /**
     * The tempo in beats per minute, between [MIN_BPM] and [MAX_BPM]: the autocorrelation of
     * the envelope (8 s windows, each counting the same), plus half of it at twice the lag so
     * that a beat wins over its own subdivisions, weighted by a log-Gaussian preference around
     * 110 BPM, then refined over the multiples of the period.
     */
    fun tempo(envelope: FloatArray, framesPerSecond: Double): Double =
        tempoOf(normalized(envelope, framesPerSecond), framesPerSecond)

    /**
     * Beat times in seconds. The envelope is scored with a narrow window around each frame, then
     * every frame's best chain of beats is found, rewarding onsets and penalising beat gaps that
     * stray from the tempo's period (on a log scale). The best chain at the end is traced back,
     * weak beats at both ends (before the music starts, after it stops) are dropped, and each
     * beat is refined between frames.
     */
    fun estimate(envelope: FloatArray, framesPerSecond: Double): List<Double> {
        val n = envelope.size
        if (n < 8) return emptyList()
        val x = normalized(envelope, framesPerSecond)
        if (x.all { it == 0.0 }) return emptyList()
        val period = 60.0 * framesPerSecond / tempoOf(x, framesPerSecond)

        // Local score: the envelope smoothed by a Gaussian of a 32nd of the period.
        val sigma = period / 32
        val reach = max(1, ceil(4 * sigma).toInt())
        val window = DoubleArray(2 * reach + 1) { val d = (it - reach) / sigma; exp(-0.5 * d * d) }
        val local = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (k in -reach..reach) { val j = i + k; if (j in 0 until n) s += x[j] * window[k + reach] }
            local[i] = s
        }
        val maxLocal = local.max()
        if (maxLocal <= 0.0) return emptyList()

        // Best chain of beats ending at each frame; the previous beat is half to two periods back.
        val lo = max(1, (period / 2).roundToInt())
        val hi = max(lo, (2 * period).roundToInt())
        val transition = DoubleArray(hi - lo + 1) { val r = ln((lo + it) / period); -TIGHTNESS * r * r }
        val cum = DoubleArray(n)
        val back = IntArray(n) { -1 }
        var started = false
        for (i in 0 until n) {
            if (!started && local[i] < 0.01 * maxLocal) { cum[i] = local[i]; continue }
            started = true
            var best = Double.NEGATIVE_INFINITY
            var arg = -1
            for (d in lo..hi) {
                val j = i - d
                if (j < 0) break
                val v = cum[j] + transition[d - lo]
                if (v > best) { best = v; arg = j }
            }
            if (arg >= 0) { cum[i] = local[i] + best; back[i] = arg } else cum[i] = local[i]
        }

        // The chain ends at the best score in the last stretch of two periods.
        var last = n - 1
        for (i in max(0, n - 1 - hi) until n) if (cum[i] > cum[last]) last = i
        val chain = ArrayList<Int>()
        var b = last
        while (b >= 0) { chain += b; b = back[b] }
        chain.reverse()
        val kept = trim(local, chain)
        return kept.map { f -> (f + peakOffset(local, f)) / framesPerSecond }
    }

    /** The envelope minus its 1 s running mean, scaled to unit deviation. */
    private fun normalized(env: FloatArray, fps: Double): DoubleArray {
        val n = env.size
        val half = max(1, (0.5 * fps).roundToInt())
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + env[i]
        val x = DoubleArray(n) { i ->
            val a = max(0, i - half)
            val b = min(n, i + half + 1)
            env[i] - (prefix[b] - prefix[a]) / (b - a)
        }
        var energy = 0.0
        for (v in x) energy += v * v
        val sd = sqrt(energy / max(1, n))
        if (sd > 1e-9) for (i in x.indices) x[i] /= sd
        return x
    }

    private fun prior(bpm: Double): Double {
        val octaves = ln(bpm / PRIOR_BPM) / ln(2.0) / PRIOR_OCTAVES
        return exp(-0.5 * octaves * octaves)
    }

    private fun tempoOf(raw: DoubleArray, fps: Double): Double {
        // Smoothed over ~15 ms first, so a period that falls between two frames still peaks.
        val x = smoothed(raw, 0.015 * fps)
        val minLag = max(1, floor(60.0 * fps / MAX_BPM).toInt())
        val maxLag = max(minLag + 1, ceil(60.0 * fps / MIN_BPM).toInt())
        val acLen = 2 * maxLag + 2
        val ac = autocorrelation(x, acLen, max((8.0 * fps).roundToInt(), 2 * acLen), max(1, (2.0 * fps).roundToInt()))
            ?: return PRIOR_BPM
        // Between frames by Catmull-Rom interpolation: the period rarely falls on a whole frame,
        // and twice the period even less so.
        fun acAt(lag: Double): Double {
            val i = floor(lag).toInt().coerceIn(1, acLen - 2)
            val f = lag - i
            val p0 = ac[i - 1]; val p1 = ac[i]; val p2 = ac[i + 1]; val p3 = ac[i + 2]
            return p1 + 0.5 * f * (p2 - p0 + f * (2 * p0 - 5 * p1 + 4 * p2 - p3 + f * (3 * (p1 - p2) + p3 - p0)))
        }
        fun score(lag: Double) = (acAt(lag) + 0.5 * acAt(2 * lag)) * prior(60.0 * fps / lag)
        var lag = minLag.toDouble()
        var best = score(lag)
        var l = minLag.toDouble()
        while (l <= maxLag) { val s = score(l); if (s > best) { best = s; lag = l }; l += 0.25 }
        val peak = acAt(lag)
        if (peak <= 0.0) return PRIOR_BPM

        // Finer: the peaks at 2, 3, 4... periods pin the period down to a fraction of a frame
        // (each multiple k estimates k periods, so it counts k times).
        var sum = lag
        var weight = 1.0
        var k = 2
        while (k * lag + 1 < acLen) {
            val c = (k * lag).roundToInt()
            var p = c
            for (q in c - 1..c + 1) if (ac[q] > ac[p]) p = q
            if (p in 1 until acLen && ac[p] > 0.3 * peak) {
                sum += p + parabola(ac[p - 1], ac[p], ac[p + 1])
                weight += k
            }
            k++
        }
        lag = sum / weight
        return (60.0 * fps / lag).coerceIn(MIN_BPM, MAX_BPM)
    }

    private fun smoothed(x: DoubleArray, sigma: Double): DoubleArray {
        if (sigma < 0.3) return x
        val reach = ceil(3 * sigma).toInt()
        val w = DoubleArray(2 * reach + 1) { val d = (it - reach) / sigma; exp(-0.5 * d * d) }
        val total = w.sum()
        return DoubleArray(x.size) { i ->
            var s = 0.0
            for (k in -reach..reach) { val j = i + k; if (j in x.indices) s += x[j] * w[k + reach] }
            s / total
        }
    }

    /**
     * Mean autocorrelation over windows of [win] frames every [hop] frames, each normalised by its
     * energy and unbiased by the overlap at every lag; null if the envelope has no energy.
     */
    private fun autocorrelation(x: DoubleArray, maxLag: Int, win: Int, hop: Int): DoubleArray? {
        val n = x.size
        if (n < 4) return null
        val lags = min(maxLag, n - 2)
        val out = DoubleArray(maxLag + 1)
        val local = DoubleArray(lags + 1)
        var count = 0
        var start = 0
        while (true) {
            val end = min(n, start + win)
            for (lag in 0..lags) {
                var s = 0.0
                for (i in start until end - lag) s += x[i] * x[i + lag]
                local[lag] = s / max(1, end - start - lag)
            }
            if (local[0] > 1e-9) {
                for (lag in 0..lags) out[lag] += local[lag] / local[0]
                count++
            }
            if (end >= n) break
            start = if (start + hop + win > n) n - win else start + hop
        }
        if (count == 0) return null
        for (i in out.indices) out[i] /= count
        return out
    }

    /** Offset of a parabola's peak through three equally spaced values, within ±0.5. */
    private fun parabola(a: Double, b: Double, c: Double): Double {
        val d = a - 2 * b + c
        if (d >= 0.0 || b < a || b < c) return 0.0
        return (0.5 * (a - c) / d).coerceIn(-0.5, 0.5)
    }

    private fun peakOffset(local: DoubleArray, f: Int): Double =
        if (f <= 0 || f >= local.size - 1) 0.0 else parabola(local[f - 1], local[f], local[f + 1])

    /** Drops the weak beats at both ends: smoothed beat strength under half its RMS. */
    private fun trim(local: DoubleArray, beats: List<Int>): List<Int> {
        if (beats.size < 3) return beats
        val hann = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25) // scipy.signal.hann(5)'s inner shape
        val smooth = DoubleArray(beats.size) { i ->
            var s = 0.0
            for (k in -2..2) { val j = i + k; if (j in beats.indices) s += local[beats[j]] * hann[k + 2] }
            s
        }
        var energy = 0.0
        for (v in smooth) energy += v * v
        val threshold = 0.5 * sqrt(energy / smooth.size)
        val first = smooth.indexOfFirst { it > threshold }
        val last = smooth.indexOfLast { it > threshold }
        if (first < 0) return emptyList()
        return beats.subList(first, last + 1)
    }

    /** Median gap between beats, in seconds, or null with fewer than two beats. */
    fun period(beats: List<Double>): Double? {
        if (beats.size < 2) return null
        val gaps = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
        gaps.sort()
        return gaps[gaps.size / 2].takeIf { it > 0.0 }
    }
}
