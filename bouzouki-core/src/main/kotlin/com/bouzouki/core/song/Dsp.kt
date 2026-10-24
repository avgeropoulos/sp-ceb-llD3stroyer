package com.bouzouki.core.song

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * In-place complex FFT for sizes 2^k and 3·2^k (the separation model uses 6144 = 3·2048).
 * Not thread-safe: each thread needs its own instance.
 */
class Fft(val n: Int) {
    private val m: Int = if (n and (n - 1) == 0) n else n / 3
    private val radix3 = m != n
    private val cosT = DoubleArray(m / 2) { cos(-2 * PI * it / m) }
    private val sinT = DoubleArray(m / 2) { sin(-2 * PI * it / m) }
    private val rev = IntArray(m).also { r ->
        val bits = Integer.numberOfTrailingZeros(m)
        for (i in 0 until m) r[i] = if (bits == 0) 0 else Integer.reverse(i) ushr (32 - bits)
    }
    private val subRe = if (radix3) Array(3) { DoubleArray(m) } else emptyArray()
    private val subIm = if (radix3) Array(3) { DoubleArray(m) } else emptyArray()
    private val wRe = DoubleArray(n) { cos(-2 * PI * it / n) }
    private val wIm = DoubleArray(n) { sin(-2 * PI * it / n) }

    init {
        require(Integer.bitCount(m) == 1 && (m == n || m * 3 == n)) { "FFT size must be 2^k or 3*2^k, got $n" }
    }

    /** Forward transform (e^{-i...}) of [re]/[im], in place. */
    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, false)

    /** Inverse transform, scaled by 1/n, in place. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, true)
        val s = 1.0 / n
        for (i in 0 until n) { re[i] *= s; im[i] *= s }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        if (!radix3) { radix2(re, im, inverse); return }
        // Split into three interleaved sequences, transform each, then combine with radix-3 butterflies.
        for (r in 0 until 3) {
            val sr = subRe[r]; val si = subIm[r]
            for (k in 0 until m) { sr[k] = re[3 * k + r]; si[k] = im[3 * k + r] }
            radix2(sr, si, inverse)
        }
        val sign = if (inverse) -1.0 else 1.0
        for (k in 0 until n) {
            val kk = k % m
            var accRe = subRe[0][kk]
            var accIm = subIm[0][kk]
            for (r in 1..2) {
                val idx = (r * k) % n
                val cr = wRe[idx]; val ci = sign * wIm[idx]
                val xr = subRe[r][kk]; val xi = subIm[r][kk]
                accRe += xr * cr - xi * ci
                accIm += xr * ci + xi * cr
            }
            re[k] = accRe; im[k] = accIm
        }
    }

    private fun radix2(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val size = m
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) -1.0 else 1.0
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var t = 0
                for (j in i until i + half) {
                    val cr = cosT[t]; val ci = sign * sinT[t]
                    val xr = re[j + half]; val xi = im[j + half]
                    val tr = xr * cr - xi * ci
                    val ti = xr * ci + xi * cr
                    re[j + half] = re[j] - tr; im[j + half] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    t += step
                }
                i += len
            }
            len *= 2
        }
    }
}

/** Periodic Hann window, as torch.hann_window(n) uses. */
fun hann(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }

/**
 * Windowed-sinc resampling between any two rates (e.g. 48000 to 44100, 44100 to 22050),
 * with the cutoff lowered when downsampling so nothing folds back.
 */
fun resample(input: FloatArray, fromRate: Int, toRate: Int, halfTaps: Int = 16): FloatArray {
    if (fromRate == toRate) return input.copyOf()
    val ratio = toRate.toDouble() / fromRate
    val outLen = floor(input.size * ratio).toInt()
    val cutoff = min(1.0, ratio) * 0.97
    val out = FloatArray(outLen)
    val reach = halfTaps / cutoff
    for (i in 0 until outLen) {
        val t = i / ratio // position in input samples
        val first = max(0, (t - reach).toInt() + 1)
        val last = min(input.size - 1, (t + reach).toInt())
        var acc = 0.0
        var norm = 0.0
        for (j in first..last) {
            val x = (j - t) * cutoff
            val w = 0.5 + 0.5 * cos(PI * (j - t) / reach) // Hann taper
            val s = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
            val k = s * w
            acc += input[j] * k
            norm += k
        }
        out[i] = if (norm != 0.0) (acc / norm).toFloat() else 0f
    }
    return out
}
