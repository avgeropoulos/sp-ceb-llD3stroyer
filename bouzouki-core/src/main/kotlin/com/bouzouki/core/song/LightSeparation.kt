package com.bouzouki.core.song

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A cheap stand-in for Demucs when the phone is too slow or the model isn't downloaded:
 * plain DSP on one STFT (2048 points, hop 512) that leans the mix towards the bouzouki.
 *
 *  1. Centre cancel: bins where left and right agree (a centre-panned voice) are turned down,
 *     by how alike they are (2·Re(L·R̄)/(|L|²+|R|²), near 1 for the centre).
 *  2. Harmonic/percussive separation (Fitzgerald 2010): medians of the magnitude across 17
 *     frames (harmonic) and across 17 bins (percussive) give a soft mask H²/(H²+P²); the
 *     harmonic part is kept, which removes most drums and strum noise.
 *  3. Band emphasis: 110–3000 Hz as is, rolling off over an octave on either side to [floor].
 *
 * It streams with a 17-frame look-ahead, so memory stays small however long the song is.
 * As a [Separator], the bass is only turned down (to [floor]), not removed: [keepsBass].
 */
class LightSeparator(
    private val sampleRate: Int = 44100,
    private val centreDepth: Double = 0.8,
    private val low: Double = 110.0,
    private val high: Double = 3000.0,
    private val floor: Double = 0.25,
) : Separator {
    private val fft = Fft(N)
    private val window = hann(N)
    private val re = DoubleArray(N)
    private val im = DoubleArray(N)
    private val band = DoubleArray(BINS) { k -> bandGain(k.toDouble() * sampleRate / N) }

    override val keepsBass: Boolean get() = true

    override fun instrumentalMono(left: FloatArray, right: FloatArray, progress: (Float) -> Unit, cancelled: () -> Boolean): FloatArray =
        bouzoukiMono(left, right, progress, cancelled)

    /** The bouzouki-leaning mono signal of a stereo song (same length). [progress] gets 0..1. */
    fun bouzoukiMono(left: FloatArray, right: FloatArray, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): FloatArray {
        val n = left.size
        val out = FloatArray(n)
        if (n == 0) return out
        val frames = n / HOP + 1
        val half = K / 2
        // Ring buffers of the centre-cancelled mid spectrum and its magnitude.
        val mRe = Array(K) { DoubleArray(BINS) }
        val mIm = Array(K) { DoubleArray(BINS) }
        val mag = Array(K) { DoubleArray(BINS) }
        val col = DoubleArray(K)
        val row = DoubleArray(K)
        val acc = DoubleArray(n)
        val norm = DoubleArray(n)
        for (t in 0 until frames + half) {
            if (t % 256 == 0) {
                if (cancelled()) throw InterruptedException()
                progress(t.toFloat() / (frames + half))
            }
            val slot = t % K
            if (t < frames) analyse(left, right, t, mRe[slot], mIm[slot], mag[slot]) else mag[slot].fill(0.0)
            val u = t - half
            if (u < 0) continue
            val us = u % K
            val m = mag[us]
            for (k in 0 until BINS) {
                // Harmonic: median over time of the frames u-8..u+8 that exist.
                var c = 0
                for (d in -half..half) {
                    val f = u + d
                    if (f in 0 until frames) col[c++] = mag[((f % K) + K) % K][k]
                }
                val h = median(col, c)
                // Percussive: median over frequency around bin k.
                var r = 0
                for (d in -half..half) {
                    val kk = k + d
                    if (kk in 0 until BINS) row[r++] = m[kk]
                }
                val p = median(row, r)
                val mask = h * h / (h * h + p * p + 1e-20) * band[k]
                val zr = mRe[us][k] * mask; val zi = mIm[us][k] * mask
                re[k] = zr; im[k] = zi
                if (k in 1 until BINS - 1) { re[N - k] = zr; im[N - k] = -zi }
            }
            im[0] = 0.0; im[BINS - 1] = 0.0
            fft.inverse(re, im)
            val pos = u * HOP - N / 2
            for (j in max(0, -pos) until min(N, n - pos)) {
                acc[pos + j] += re[j] * window[j]
                norm[pos + j] += window[j] * window[j]
            }
        }
        for (i in 0 until n) out[i] = (if (norm[i] > 1e-8) acc[i] / norm[i] else 0.0).toFloat()
        progress(1f)
        return out
    }

    /** Frame [t] of both channels in one FFT, centre-cancelled into the mid spectrum. */
    private fun analyse(left: FloatArray, right: FloatArray, t: Int, outRe: DoubleArray, outIm: DoubleArray, outMag: DoubleArray) {
        val pos = t * HOP - N / 2
        for (j in 0 until N) {
            val idx = pos + j
            val inside = idx in left.indices
            re[j] = if (inside) left[idx] * window[j] else 0.0
            im[j] = if (inside) right[idx] * window[j] else 0.0
        }
        fft.forward(re, im)
        for (k in 0 until BINS) {
            val nk = if (k == 0) 0 else N - k
            val zr = re[k]; val zi = im[k]; val wr = re[nk]; val wi = im[nk]
            val lr = (zr + wr) / 2; val li = (zi - wi) / 2
            val rr = (zi + wi) / 2; val ri = -(zr - wr) / 2
            val pl = lr * lr + li * li; val pr = rr * rr + ri * ri
            val sim = max(0.0, 2 * (lr * rr + li * ri) / (pl + pr + 1e-20))
            val s2 = sim * sim
            val g = (1 - centreDepth * s2 * s2) / 2
            val mr = (lr + rr) * g; val mi = (li + ri) * g
            outRe[k] = mr; outIm[k] = mi; outMag[k] = sqrt(mr * mr + mi * mi)
        }
    }

    private fun bandGain(f: Double): Double {
        // Raised-cosine shoulders an octave wide, in log frequency.
        fun shoulder(octaves: Double) = if (octaves >= 1) floor else floor + (1 - floor) * (0.5 + 0.5 * cos(PI * octaves))
        return when {
            f <= 0 -> floor
            f < low -> shoulder(ln(low / f) / LN2)
            f > high -> shoulder(ln(f / high) / LN2)
            else -> 1.0
        }
    }

    companion object {
        const val N = 2048
        const val HOP = 512
        const val BINS = N / 2 + 1
        private const val K = 17
        private val LN2 = ln(2.0)

        /** Median of the first [n] values of [a] (reordered in place). */
        internal fun median(a: DoubleArray, n: Int): Double {
            if (n == 0) return 0.0
            // Insertion sort: n is at most 17.
            for (i in 1 until n) {
                val v = a[i]
                var j = i - 1
                while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j-- }
                a[j + 1] = v
            }
            return if (n % 2 == 1) a[n / 2] else (a[n / 2 - 1] + a[n / 2]) / 2
        }
    }
}
