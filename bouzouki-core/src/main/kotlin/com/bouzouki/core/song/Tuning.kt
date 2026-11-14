package com.bouzouki.core.song

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How far a recording is tuned from A = 440 Hz, and Basic Pitch's view of it put back in tune.
 *
 * Old records (rebetiko, laiko) are often 10-50 cents off: the tape ran at another speed, or the band tuned to an older
 * pitch. Basic Pitch hears notes on a grid tuned to A = 440 and finds far fewer of them in such a recording (on the test
 * songs, 20 cents flat: over a quarter fewer; 35 sharp: a sixth fewer; 45 flat: almost none). So the analysis measures
 * the tuning ([estimate]) and, from [MIN_CORRECTION] off, resamples the audio so it plays in tune ([retune]: pitch and
 * speed change by the same small ratio, as if the tape ran at the right speed), runs Basic Pitch on that, and maps its
 * activations back onto the recording's own time ([toRealTime]), so the notes, beats and everything derived from them
 * later line up with the recording.
 */
object Tuning {
    /**
     * A recording measured closer than this to A = 440 (cents) is transcribed as it is, exactly as before the tuning was
     * measured. Closer than this, putting it in tune changes about as much for the worse as for the better: on test
     * songs with known notes, 3 to 5 cents off, from 1.5% F worse to 2.5% better (Basic Pitch heard some best a few
     * cents sharp, some a few flat).
     */
    const val MIN_CORRECTION = 5.0

    /**
     * Data saved before the tuning was measured is reported out of tune ([SongAnalyzer.transcribedOutOfTune]) when its
     * contours ([fromContours]) read this far off (cents). Records in tune read within 2 cents (1 to 2 flat), 10 flat 4
     * to 9 flat, 15 sharp 2.5 to 4 sharp, 20 sharp 5 to 7 sharp: so it tells records 10 cents flat or more and about 15
     * sharp or more, where Basic Pitch missed many notes (of a real recording 10 flat, 40% of them). It misses 5 flat
     * (read 2.5 to 4.5 flat) and up to about 12 sharp (read within 2 sharp), which lose less: that recording 5 flat a
     * fifth of its notes, test songs with known notes up to 3% F.
     */
    const val RETUNE_HINT = 3.0

    /**
     * The tuning of [audio] (mono, 22050 Hz) in cents from A = 440 Hz, -50 until 50 (+ sharp, - flat); 0 when it can't
     * tell (too few steady tones). Every spectral peak in the bouzouki's range (130-1400 Hz, the instrument's notes and
     * their low partials) is placed between frequency bins (a parabola through the log magnitudes) and its distance in
     * cents from the nearest equally tempered note goes into a histogram (1 cent bins, around the circle: 50 cents sharp
     * is 50 flat). The tuning is the histogram's peak, refined by the mean of the peaks near it. Percussion, noise and a
     * leaking voice spread over the circle, so they hardly move the peak, and partials off the tempered scale (the fifth
     * harmonic is 14 cents below its tempered note) make smaller peaks of their own instead of pulling it. Every peak
     * counts alike, though, so a steady accompaniment tuned off on purpose can pull it: an accordion holding chords
     * through the song with its second reed 15 cents sharp moves it about 6 cents (its two reeds are heard as one peak
     * between them), 8 or 25 cents sharp 2 to 4 cents, reeds either side of the middle one less than 1. Spectra are
     * 186 ms (zero-padded twice), every 93 ms, at most [MAX_SPECTRA] of them spread over the song.
     */
    fun estimate(audio: FloatArray): Double = estimateOrNull(audio) ?: 0.0

    /** [estimate], or null when it can't tell (too few steady tones), rather than 0. */
    fun estimateOrNull(audio: FloatArray): Double? {
        val sr = BasicPitch.SAMPLE_RATE.toDouble()
        val count = if (audio.size >= WINDOW) (audio.size - WINDOW) / HOP + 1 else 0
        if (count == 0) return null
        val used = min(count, MAX_SPECTRA)
        val fft = Fft(FFT_SIZE)
        val window = hann(WINDOW)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val k0 = floor(LOW_HZ * FFT_SIZE / sr).toInt() + 1
        val k1 = ceil(HIGH_HZ * FFT_SIZE / sr).toInt()
        val mag = DoubleArray(k1 - k0 + 3) // bins k0 - 1 .. k1 + 1
        val sorted = DoubleArray(mag.size)
        val dev = Deviations()
        for (s in 0 until used) {
            val off = (if (used == count) s.toLong() else s.toLong() * (count - 1) / max(1, used - 1)).toInt() * HOP
            for (i in 0 until WINDOW) { re[i] = audio[off + i] * window[i]; im[i] = 0.0 }
            java.util.Arrays.fill(re, WINDOW, FFT_SIZE, 0.0)
            java.util.Arrays.fill(im, WINDOW, FFT_SIZE, 0.0)
            fft.forward(re, im)
            var top = 0.0
            for (j in mag.indices) {
                val k = k0 - 1 + j
                mag[j] = sqrt(re[k] * re[k] + im[k] * im[k])
                top = max(top, mag[j])
            }
            if (top <= 1e-9) continue
            System.arraycopy(mag, 0, sorted, 0, mag.size)
            sorted.sort()
            val m = sorted.size / 2
            val median = if (sorted.size % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) / 2
            val level = max(top * PEAK_RANGE, PEAK_OVER_MEDIAN * median)
            for (j in 1 until mag.size - 1) {
                val b = mag[j]
                if (b <= mag[j - 1] || b < mag[j + 1] || b <= level) continue
                val la = ln(mag[j - 1] + 1e-12)
                val lb = ln(b + 1e-12)
                val lg = ln(mag[j + 1] + 1e-12)
                val den = la - 2 * lb + lg
                val d = if (den < 0) 0.5 * (la - lg) / den else 0.0
                val hz = (k0 - 1 + j + d) * sr / FFT_SIZE
                dev.add(1200 * ln(hz / 440.0) / ln(2.0))
            }
        }
        return dev.tuning(MIN_PEAKS, MIN_AGREEMENT)
    }

    /**
     * The tuning (cents, as [estimate]) read from Basic Pitch's pitch contours ([BasicPitch.Activations.contours], 3
     * bins a semitone) of a recording transcribed as it was, e.g. data saved before the tuning was measured; null
     * without contours or too few clear ones. Only good for telling a recording was far off ([RETUNE_HINT]), never a
     * tuning to show or tune to: the model leans towards its bins (a third of a semitone apart), so a recording in tune
     * reads within 2 cents of 0, but 10 flat reads 4 to 9 flat, 20 flat about 30 flat, 10 sharp 1 to 2 sharp, 20 sharp
     * 5 to 7 sharp, 35 sharp about 32 sharp, and 45 flat about 40 flat or even 40 sharp.
     */
    fun fromContours(act: BasicPitch.Activations): Double? {
        val c = act.contours ?: return null
        val dev = Deviations()
        for (row in c) {
            for (k in 1 until row.size - 1) {
                val b = row[k].toInt() and 0xFF
                if (b < CONTOUR_LEVEL) continue
                val a = row[k - 1].toInt() and 0xFF
                val g = row[k + 1].toInt() and 0xFF
                if (b <= a || b < g) continue
                val den = (a - 2 * b + g).toDouble()
                val d = if (den < 0) 0.5 * (a - g) / den else 0.0
                dev.add((k - 1 + d) * 100.0 / 3)
            }
        }
        return dev.tuning(MIN_PEAKS, MIN_AGREEMENT)
    }

    /** The rate to [retune] audio to, so a recording [cents] off plays in tune at Basic Pitch's rate. */
    fun rateFor(cents: Double): Int = (BasicPitch.SAMPLE_RATE * 2.0.pow(cents / 1200)).roundToInt()

    /**
     * [audio] (mono, 22050 Hz) resampled to [rate] samples a second, to be played at 22050 Hz: lower and slower when
     * [rate] is higher. The windowed sinc of [resample] (16 zero crossings each side, Hann taper, cutoff 0.97 of the
     * lower Nyquist rate), its kernel tabulated at [PHASES] offsets and interpolated between them: the same samples to
     * 1e-4, about 15 times quicker than working out every tap.
     */
    fun retune(audio: FloatArray, rate: Int): FloatArray {
        val from = BasicPitch.SAMPLE_RATE
        if (rate == from) return audio.copyOf()
        val ratio = rate.toDouble() / from
        val n = audio.size
        val out = FloatArray(floor(n * ratio).toInt())
        val cutoff = min(1.0, ratio) * 0.97
        val reach = HALF_TAPS / cutoff
        val r = ceil(reach).toInt()
        val width = 2 * r // taps base - r + 1 .. base + r for a position between base and base + 1
        val table = Array(PHASES + 1) { p ->
            DoubleArray(width) { i ->
                val d = (i - r + 1) - p.toDouble() / PHASES
                if (abs(d) >= reach) 0.0 else {
                    val x = d * cutoff
                    val s = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                    s * (0.5 + 0.5 * cos(PI * d / reach))
                }
            }
        }
        for (i in out.indices) {
            val t = i / ratio
            val base = floor(t).toInt()
            val pos = (t - base) * PHASES
            val p = min(PHASES - 1, pos.toInt())
            val a = pos - p
            val lo = table[p]
            val hi = table[p + 1]
            val first = max(0, r - 1 - base) // taps before the audio's start are left out (and the norm with them)
            val last = min(width, n - base + r - 1)
            var acc = 0.0
            var norm = 0.0
            for (k in first until last) {
                val w = lo[k] + a * (hi[k] - lo[k])
                acc += audio[base - r + 1 + k] * w
                norm += w
            }
            out[i] = if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
        return out
    }

    /**
     * Activations of audio [retune]d to [rate] put back on the recording's own time: [frames] frames, each at the time
     * [BasicPitch.frameTime] gives it (as for the recording itself, so everything that reads the activations lines up
     * with it), with the activations of the retuned audio at that moment, between its two frames around it (in
     * proportion to time). Basic Pitch's onset peaks are a few frames wide, so they keep their height and, through
     * [BasicPitch.onsetTime], their time: on the eval songs the notes come out as with times scaled exactly (onsets
     * within 0.1 ms of that on average, the same notes found).
     */
    fun toRealTime(act: BasicPitch.Activations, rate: Int, frames: Int): BasicPitch.Activations {
        val nt = act.frames.size
        val contours = act.contours
        if (nt == 0 || frames <= 0) {
            return BasicPitch.Activations(emptyArray(), emptyArray(), contours?.let { emptyArray() })
        }
        val scale = BasicPitch.SAMPLE_RATE.toDouble() / rate // seconds of the recording per second of the retuned audio
        val lo = IntArray(frames) // the frame of act at or before each real frame's moment, and how far on to the next
        val w = DoubleArray(frames)
        var j = 0
        for (f in 0 until frames) {
            val t = BasicPitch.frameTime(f) / scale
            while (j < nt - 2 && BasicPitch.frameTime(j + 1) < t) j++
            lo[f] = j
            w[f] = if (nt < 2) 0.0 else ((t - BasicPitch.frameTime(j)) / (BasicPitch.frameTime(j + 1) - BasicPitch.frameTime(j))).coerceIn(0.0, 1.0)
        }
        fun hi(f: Int) = min(lo[f] + 1, nt - 1)
        return BasicPitch.Activations(
            Array(frames) { between(act.frames[lo[it]], act.frames[hi(it)], w[it]) },
            Array(frames) { between(act.onsets[lo[it]], act.onsets[hi(it)], w[it]) },
            contours?.let { c -> Array(frames) { betweenBytes(c[lo[it]], c[hi(it)], w[it]) } },
        )
    }

    private fun between(x: FloatArray, y: FloatArray, a: Double): FloatArray {
        val b = a.toFloat()
        return FloatArray(x.size) { x[it] + b * (y[it] - x[it]) }
    }

    private fun betweenBytes(x: ByteArray, y: ByteArray, a: Double): ByteArray = ByteArray(x.size) {
        val p = x[it].toInt() and 0xFF
        val q = y[it].toInt() and 0xFF
        (p + a * (q - p)).roundToInt().coerceIn(0, 255).toByte()
    }

    /**
     * Pitches' distances (cents, around the circle of 100) from the equally tempered notes, and the tuning they agree
     * on: the peak of their histogram (1 cent bins, smoothed by [SMOOTH] cents), moved to the mean of those within
     * [NEAR] cents of it.
     */
    private class Deviations {
        private var values = DoubleArray(1024)
        private var n = 0

        fun add(cents: Double) {
            if (n == values.size) values = values.copyOf(n * 2)
            values[n++] = wrap(cents)
        }

        /** The tuning they agree on; null when there are fewer than [minCount], or they agree less than [minAgreement]. */
        fun tuning(minCount: Int, minAgreement: Double): Double? {
            if (n < minCount) return null
            val hist = DoubleArray(100)
            var x = 0.0
            var y = 0.0
            for (i in 0 until n) {
                val v = values[i]
                hist[Math.floorMod(floor(v + 50).toInt(), 100)] += 1.0
                x += cos(2 * PI * v / 100)
                y += sin(2 * PI * v / 100)
            }
            // How much they agree: 1 if all were the same, about 0 if spread evenly around the circle.
            if (sqrt(x * x + y * y) / n < minAgreement) return null
            val kernel = DoubleArray(100) { val d = if (it < 50) it else it - 100; exp(-0.5 * (d / SMOOTH) * (d / SMOOTH)) }
            var best = 0
            var bestValue = -1.0
            for (b in 0 until 100) {
                var s = 0.0
                for (j in 0 until 100) s += hist[j] * kernel[Math.floorMod(b - j, 100)]
                if (s > bestValue) { bestValue = s; best = b }
            }
            val peak = best - 50 + 0.5
            x = 0.0
            y = 0.0
            for (i in 0 until n) {
                val d = wrap(values[i] - peak)
                if (abs(d) > NEAR) continue
                x += cos(2 * PI * d / 100)
                y += sin(2 * PI * d / 100)
            }
            return wrap(peak + if (x == 0.0 && y == 0.0) 0.0 else atan2(y, x) * 100 / (2 * PI))
        }
    }

    /** [cents] around the circle of a semitone: -50 until 50. */
    private fun wrap(cents: Double): Double = cents - 100 * floor((cents + 50) / 100)

    private const val WINDOW = 4096
    private const val FFT_SIZE = 8192
    private const val HOP = 2048
    private const val MAX_SPECTRA = 1500
    private const val LOW_HZ = 130.0
    private const val HIGH_HZ = 1400.0
    /** A spectral peak counts within 30 dB of the strongest in the band and at least 4 times the band's median. */
    private const val PEAK_RANGE = 0.031622776601683794
    private const val PEAK_OVER_MEDIAN = 4.0
    /** Fewer peaks than this, or peaks agreeing less than this ([Deviations.tuning]), tell nothing. */
    private const val MIN_PEAKS = 200
    private const val MIN_AGREEMENT = 0.15
    private const val SMOOTH = 3.0
    private const val NEAR = 12.0
    /** A contour bin counts as a peak from this activation (0.3) up. */
    private const val CONTOUR_LEVEL = 77
    private const val HALF_TAPS = 16
    private const val PHASES = 512
}
