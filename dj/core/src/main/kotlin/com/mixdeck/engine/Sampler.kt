package com.mixdeck.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The four sampler pads. Each plays one of [BANK]'s one-shot DJ sounds, all synthesised at
 * start-up so the app ships no audio files; holding a pad lets you pick its sound ([assign]).
 */
class Sampler(private val sr: Int) {
    companion object {
        /** Every sound a pad can play, in the picker's order. The first four are the pads' sounds at first. */
        val BANK = listOf("HORN", "SIREN", "LASER", "DROP", "KICK", "CLAP", "ROLL", "RISER", "IMPACT", "SCRATCH", "REWIND", "COWBELL")
        val ABOUT = listOf(
            "Reggae air horn", "Police-style siren", "Laser zap", "Sub drop for when the beat comes back",
            "Deep 808 kick", "Hand clap", "Snare roll that builds up", "Noise riser before a drop",
            "Big boom", "Record scratch", "Rewind (spin-back)", "808 cowbell",
        )

        /** Reads [encode]'s text: four bank numbers (bad or missing ones get the first four). */
        fun decode(text: String?): IntArray {
            val p = text.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n in BANK.indices } }
            return if (p.size == 4) p.toIntArray() else intArrayOf(0, 1, 2, 3)
        }
    }

    private val sounds: List<FloatArray> = listOf(
        airHorn(), siren(), laser(), drop(), kick(), clap(), roll(), riser(), impact(), scratch(), rewind(), cowbell(),
    )
    /** Which bank sound each pad plays. */
    private val pads = intArrayOf(0, 1, 2, 3)
    private val pos = IntArray(4) { -1 }
    var volume = 0.7f

    /** Pad [i]'s sound name, e.g. "HORN". */
    fun name(i: Int) = BANK[pads[i]]
    fun soundOf(i: Int) = pads[i]

    /** Pad [pad] plays bank sound [sound] from now on (it stops if it was playing). */
    fun assign(pad: Int, sound: Int) { if (sound in BANK.indices) { pads[pad] = sound; pos[pad] = -1 } }
    fun assignAll(p: IntArray) { for (i in 0..3) assign(i, p[i]) }
    fun encode() = pads.joinToString(",")

    fun trigger(i: Int) { pos[i] = 0 }
    fun stop(i: Int) { pos[i] = -1 }
    fun isPlaying(i: Int) = pos[i] >= 0

    fun render(out: FloatArray, frames: Int, master: Float) {
        for (s in 0..3) {
            var p = pos[s]
            if (p < 0) continue
            val snd = sounds[pads[s]]
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

    /** A deep 808 kick: a low tone that drops fast in pitch. */
    private fun kick(): FloatArray {
        val n = len(0.7)
        val out = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            ph = (ph + (48 + 110 * exp(-t * 28)) / sr) % 1.0
            out[i] = (kotlin.math.tanh(sin(2 * PI * ph) * 2.2) * 0.75 * exp(-t * 5.5) * envelope(i, n, 0.001, 0.05)).toFloat()
        }
        return out
    }

    /** A hand clap: three quick bursts of filtered noise and a short tail. */
    private fun clap(): FloatArray {
        val n = len(0.45)
        val out = FloatArray(n)
        val rnd = Random(11)
        val bp = Biquad().apply { peaking(sr, 1200.0, 1.2, 12.0) }
        val hp = Biquad().apply { highPass(sr, 700.0, 0.7) }
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val burst = listOf(0.0, 0.011, 0.023).sumOf { st -> if (t >= st) exp(-(t - st) * 180) else 0.0 }
            val tail = if (t > 0.023) exp(-(t - 0.023) * 14) * 0.5 else 0.0
            val x = hp.process(bp.process(rnd.nextDouble() * 2 - 1, 0), 0)
            out[i] = (x * (burst + tail) * 0.45 * envelope(i, n, 0.0005, 0.05)).toFloat()
        }
        return out
    }

    /** A snare roll: hits that come faster and louder for two seconds. */
    private fun roll(): FloatArray {
        val n = len(2.2)
        val out = FloatArray(n)
        val rnd = Random(5)
        var next = 0.0; var gap = 0.25; var hitAt = -1.0
        var ph = 0.0
        val hp = Biquad().apply { highPass(sr, 1500.0, 0.7) }
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            if (t >= next && t < 2.0) { hitAt = t; next = t + gap; gap = maxOf(0.035, gap * 0.82) }
            val since = t - hitAt
            val loud = 0.25 + 0.75 * minOf(1.0, t / 2.0)
            val e = if (hitAt >= 0) exp(-since * 38) else 0.0
            ph = (ph + 190.0 / sr) % 1.0
            val x = hp.process(rnd.nextDouble() * 2 - 1, 0) * 0.8 + sin(2 * PI * ph) * 0.4
            out[i] = (x * e * loud * 0.45 * envelope(i, n, 0.001, 0.15)).toFloat()
        }
        return out
    }

    /** A riser: noise and a tone sweeping up for four seconds, to build into a drop. */
    private fun riser(): FloatArray {
        val n = len(4.0)
        val out = FloatArray(n)
        val rnd = Random(3)
        val bp = Biquad()
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val k = t / 4.0
            if (i % 64 == 0) bp.peaking(sr, 300 + 6000 * k * k, 2.0, 18.0)
            ph = (ph + (200 + 1400 * k * k) / sr) % 1.0
            val x = bp.process(rnd.nextDouble() * 2 - 1, 0) * 0.12 + sin(2 * PI * ph) * 0.15 * k
            out[i] = (kotlin.math.tanh(x * 2) * 0.5 * (0.15 + 0.85 * k) * envelope(i, n, 0.05, 0.02)).toFloat()
        }
        return out
    }

    /** A big boom: a deep hit with a burst of noise and a long rumble. */
    private fun impact(): FloatArray {
        val n = len(2.5)
        val out = FloatArray(n)
        val rnd = Random(9)
        val lp = Biquad().apply { lowPass(sr, 900.0, 0.7) }
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            ph = (ph + (38 + 70 * exp(-t * 12)) / sr) % 1.0
            val boom = kotlin.math.tanh(sin(2 * PI * ph) * 3) * exp(-t * 1.6) * 0.7
            val crack = lp.process(rnd.nextDouble() * 2 - 1, 0) * (exp(-t * 25) * 0.9 + exp(-t * 2.2) * 0.12)
            out[i] = ((boom + crack) * 0.75 * envelope(i, n, 0.0008, 0.4)).toFloat()
        }
        return out
    }

    /** A record scratch: a burst of sound pushed forward and pulled back, twice. */
    private fun scratch(): FloatArray {
        val n = len(0.75)
        val out = FloatArray(n)
        val rnd = Random(4)
        // A short "record": a tone with grit, read back at a speed that swings forward and back.
        val rec = DoubleArray(len(0.5)) { i -> val t = i.toDouble() / sr; sin(2 * PI * 220 * t) * 0.6 + (rnd.nextDouble() * 2 - 1) * 0.35 }
        val bp = Biquad().apply { peaking(sr, 1800.0, 0.9, 9.0) }
        var p = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val speed = sin(2 * PI * t / 0.375) * 2.4
            p = (p + speed).coerceIn(0.0, rec.size - 2.0)
            val k = p.toInt(); val fr = p - k
            val x = rec[k] * (1 - fr) + rec[k + 1] * fr
            val loud = minOf(1.0, abs(speed) / 1.2)
            out[i] = (bp.process(x, 0) * loud * 0.55 * envelope(i, n, 0.003, 0.05)).toFloat()
        }
        return out
    }

    /** A rewind: the sound winding backwards, fast then slowing, like a record spun back by hand. */
    private fun rewind(): FloatArray {
        val n = len(1.4)
        val out = FloatArray(n)
        val rnd = Random(8)
        var ph = 0.0; var ph2 = 0.0
        val bp = Biquad()
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val sp = 6 * exp(-t * 1.8)
            if (i % 64 == 0) bp.peaking(sr, 400 + 2500 * sp / 6, 1.5, 12.0)
            ph = (ph + 140 * sp / sr) % 1.0
            ph2 = (ph2 + 9 * sp / sr) % 1.0
            val chop = 0.55 + 0.45 * sin(2 * PI * ph2)
            val x = sin(2 * PI * ph) * 0.4 + bp.process(rnd.nextDouble() * 2 - 1, 0) * 0.25
            out[i] = (x * chop * 0.6 * envelope(i, n, 0.01, 0.3)).toFloat()
        }
        return out
    }

    /** The 808 cowbell: two square tones through a bright filter, short. */
    private fun cowbell(): FloatArray {
        val n = len(0.5)
        val out = FloatArray(n)
        var a = 0.0; var b = 0.0
        val bp = Biquad().apply { peaking(sr, 2600.0, 1.0, 8.0) }
        val hp = Biquad().apply { highPass(sr, 500.0, 0.7) }
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            a = (a + 540.0 / sr) % 1.0; b = (b + 800.0 / sr) % 1.0
            val x = (if (a < 0.5) 1.0 else -1.0) + (if (b < 0.5) 1.0 else -1.0)
            val e = if (t < 0.02) 1.0 else exp(-(t - 0.02) * 9)
            out[i] = (hp.process(bp.process(x, 0), 0) * e * 0.16 * envelope(i, n, 0.0005, 0.05)).toFloat()
        }
        return out
    }
}
