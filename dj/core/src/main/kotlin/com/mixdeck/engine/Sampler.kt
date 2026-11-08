package com.mixdeck.engine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Four one-shot DJ sounds, synthesised at start-up so the app ships no audio files. */
class Sampler(private val sr: Int) {
    val names = arrayOf("HORN", "SIREN", "LASER", "DROP")
    private val sounds: Array<FloatArray> = arrayOf(airHorn(), siren(), laser(), drop())
    private val pos = IntArray(4) { -1 }
    var volume = 0.7f

    fun trigger(i: Int) { pos[i] = 0 }
    fun stop(i: Int) { pos[i] = -1 }
    fun isPlaying(i: Int) = pos[i] >= 0

    fun render(out: FloatArray, frames: Int, master: Float) {
        for (s in sounds.indices) {
            var p = pos[s]
            if (p < 0) continue
            val snd = sounds[s]
            val g = volume * master
            for (i in 0 until frames) {
                if (p >= snd.size) { p = -1; break }
                val v = snd[p++] * g
                out[i * 2] += v; out[i * 2 + 1] += v
            }
            pos[s] = p
        }
    }

    private fun len(sec: Double) = (sec * sr).toInt()

    private fun envelope(i: Int, n: Int, attack: Double, release: Double): Double {
        val t = i.toDouble() / sr
        val left = (n - i).toDouble() / sr
        return minOf(1.0, t / attack, left / release)
    }

    /** Classic reggae air horn: a stack of detuned saws with a pitch drop at the start. */
    private fun airHorn(): FloatArray {
        val n = len(1.6)
        val out = FloatArray(n)
        val freqs = doubleArrayOf(466.0, 470.0, 587.0, 700.0)
        val ph = DoubleArray(freqs.size)
        val lp = Biquad().apply { lowPass(sr, 3200.0, 0.9) }
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            // Three blasts: short, short, long.
            val gate = when {
                t < 0.18 -> 1.0
                t < 0.24 -> 0.0
                t < 0.42 -> 1.0
                t < 0.48 -> 0.0
                else -> 1.0
            }
            val bendUp = 1 - 0.08 * exp(-((t % 0.24) * 30))
            var v = 0.0
            for (k in freqs.indices) {
                ph[k] = (ph[k] + freqs[k] * bendUp / sr) % 1.0
                v += 2 * ph[k] - 1
            }
            out[i] = (lp.process(v * 0.18 * gate * envelope(i, n, 0.005, 0.15), 0)).toFloat()
        }
        return out
    }

    private fun siren(): FloatArray {
        val n = len(2.4)
        val out = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 700 + 500 * sin(2 * PI * 1.6 * t)
            ph = (ph + f / sr) % 1.0
            val sq = if (ph < 0.5) 1.0 else -1.0
            out[i] = ((sin(2 * PI * ph) * 0.6 + sq * 0.15) * 0.5 * envelope(i, n, 0.02, 0.3)).toFloat()
        }
        return out
    }

    private fun laser(): FloatArray {
        val n = len(0.45)
        val out = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 3000 * exp(-t * 9) + 120
            ph = (ph + f / sr) % 1.0
            out[i] = (sin(2 * PI * ph) * 0.5 * envelope(i, n, 0.002, 0.1)).toFloat()
        }
        return out
    }

    /** A sub-bass drop with a noise sweep, for the moment the beat comes back in. */
    private fun drop(): FloatArray {
        val n = len(2.0)
        val out = FloatArray(n)
        var ph = 0.0
        val rnd = Random(7)
        val hp = Biquad()
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 30 + 90 * exp(-t * 2.5)
            ph = (ph + f / sr) % 1.0
            if (i % 64 == 0) hp.highPass(sr, 200 + 8000 * exp(-t * 3), 0.8)
            val noise = hp.process(rnd.nextDouble() * 2 - 1, 0) * 0.15 * exp(-t * 2)
            val sub = kotlin.math.tanh(sin(2 * PI * ph) * 2.5) * 0.55
            out[i] = ((sub + noise) * envelope(i, n, 0.003, 0.5)).toFloat()
        }
        return out
    }
}
