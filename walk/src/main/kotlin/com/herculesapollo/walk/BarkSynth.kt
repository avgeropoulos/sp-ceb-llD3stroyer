package com.herculesapollo.walk

import com.roccoquest.game.Chiptune
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.random.Random

/**
 * The dogs' barks, made the way a speech synthesizer says vowels: a buzzing "voice" at
 * the dog's pitch (with breath noise and a little roughness, so it sounds like a bark
 * and not a beep) shaped by three resonances of the mouth and throat, which turn it into
 * "gahv" or "arp". Hercules' GAV is deep and gruff; Apollo's ARP is high and bright.
 */
object BarkSynth {
    private const val SR = Chiptune.RATE

    /** A value changing over time: pairs of (seconds, value), joined by straight lines. */
    class Curve(vararg val keys: Float) {
        fun at(t: Float): Float {
            if (t <= keys[0]) return keys[1]
            var i = 0
            while (i + 3 < keys.size) {
                val t0 = keys[i]; val t1 = keys[i + 2]
                if (t <= t1) return keys[i + 1] + (keys[i + 3] - keys[i + 1]) * (t - t0) / (t1 - t0)
                i += 2
            }
            return keys[keys.size - 1]
        }
    }

    /**
     * One bark. [f0] is the pitch; [f1]..[f3] the resonances that make the vowel; [amp]
     * the loudness; [breath] how much noise is in the voice. [rough] wobbles each buzz
     * a little and [sub] makes every other one quieter (a gravelly growl). [fric] adds a
     * hiss (the "v") around [fricHz]; [burstAt] a little pop (the "g" or the "p").
     */
    class Voice(
        val seconds: Float,
        val f0: Curve, val f1: Curve, val f2: Curve, val f3: Curve,
        val amp: Curve, val breath: Curve,
        val rough: Float, val sub: Float,
        val fric: Curve? = null, val fricHz: Float = 3000f,
        val burstAt: Float = -1f, val burstSeconds: Float = 0.015f, val burstLevel: Float = 0.3f, val burstHz: Float = 1500f,
        val seed: Int = 7,
    )

    /** Hercules: a deep, gruff "GAV" (about 130 Hz). */
    val gav = Voice(
        0.36f,
        f0 = Curve(0f, 95f, 0.04f, 128f, 0.09f, 140f, 0.22f, 112f, 0.36f, 88f),
        f1 = Curve(0f, 300f, 0.04f, 520f, 0.07f, 640f, 0.22f, 600f, 0.27f, 320f, 0.36f, 260f),
        f2 = Curve(0f, 1250f, 0.05f, 1080f, 0.22f, 1020f, 0.36f, 900f),
        f3 = Curve(0f, 2200f, 0.36f, 2100f),
        amp = Curve(0f, 0f, 0.012f, 0.35f, 0.03f, 1f, 0.2f, 0.9f, 0.26f, 0.45f, 0.33f, 0.2f, 0.36f, 0f),
        breath = Curve(0f, 0.5f, 0.04f, 0.3f, 0.22f, 0.25f, 0.36f, 0.35f),
        rough = 0.035f, sub = 0.3f,
        fric = Curve(0f, 0f, 0.24f, 0f, 0.29f, 0.07f, 0.34f, 0.04f, 0.36f, 0f), fricHz = 2600f,
        burstAt = 0.004f, burstSeconds = 0.018f, burstLevel = 0.35f, burstHz = 1200f,
    )

    /** Apollo: a high, bright "ARP" (about 550 Hz), sliding down through the "r" to a little "p". */
    val arp = Voice(
        0.26f,
        f0 = Curve(0f, 470f, 0.03f, 600f, 0.08f, 560f, 0.16f, 470f, 0.2f, 430f, 0.26f, 400f),
        f1 = Curve(0f, 700f, 0.03f, 950f, 0.11f, 920f, 0.17f, 650f, 0.21f, 450f, 0.26f, 400f),
        f2 = Curve(0f, 1500f, 0.11f, 1500f, 0.17f, 1300f, 0.26f, 1250f),
        f3 = Curve(0f, 3000f, 0.11f, 2900f, 0.16f, 1800f, 0.21f, 1700f, 0.26f, 1800f),
        amp = Curve(0f, 0f, 0.006f, 1f, 0.12f, 0.85f, 0.19f, 0.6f, 0.205f, 0f, 0.26f, 0f),
        breath = Curve(0f, 0.45f, 0.03f, 0.2f, 0.2f, 0.25f, 0.26f, 0.25f),
        rough = 0.02f, sub = 0f,
        burstAt = 0.222f, burstSeconds = 0.012f, burstLevel = 0.3f, burstHz = 2600f,
    )

    /** Renders [v] as mono samples at Chiptune.RATE, peaking at [peak]. */
    fun render(v: Voice, peak: Float = 0.75f): FloatArray {
        val n = (v.seconds * SR).toInt()
        val rnd = Random(v.seed)
        fun gauss() = (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 1.5f) * 1.6f
        // The voice: one glottal pulse per period (its slope, which is what the ear hears).
        val src = FloatArray(n)
        var phase = 0f
        var pulse = 1f
        var jitter = 1f
        var count = 0
        var prev = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            phase += v.f0.at(t) * jitter / SR
            if (phase >= 1f) {
                phase -= 1f
                count++
                jitter = 1f + v.rough * (rnd.nextFloat() * 2f - 1f)
                pulse = (if (count % 2 == 1) 1f - v.sub else 1f) * (1f + 2f * v.rough * (rnd.nextFloat() * 2f - 1f))
            }
            val g = when {
                phase < 0.55f -> 0.5f * (1f - cos(PI.toFloat() * phase / 0.55f))
                phase < 0.8f -> cos(PI.toFloat() * (phase - 0.55f) / 0.5f)
                else -> 0f
            }
            src[i] = ((g - prev) * pulse * 6f + v.breath.at(t) * gauss() * 0.35f) * v.amp.at(t)
            prev = g
        }
        var y = resonate(src, v.f1, 90f)
        y = resonate(y, v.f2, 120f)
        y = resonate(y, v.f3, 180f)
        normalize(y, 1f)
        // Consonants go on top, at a set level below the vowel.
        v.fric?.let { fric ->
            val hiss = resonate(FloatArray(n) { gauss() }, Curve(0f, v.fricHz), 1800f)
            normalize(hiss, 1f)
            for (i in 0 until n) y[i] += hiss[i] * fric.at(i.toFloat() / SR)
        }
        if (v.burstAt >= 0f) {
            val i0 = (v.burstAt * SR).toInt()
            val len = (v.burstSeconds * SR).toInt().coerceAtMost(n - i0)
            val pop = FloatArray(n)
            for (k in 0 until len) {
                val fade = 1f - k.toFloat() / len
                pop[i0 + k] = gauss() * fade * fade
            }
            val shaped = resonate(pop, Curve(0f, v.burstHz), 900f)
            normalize(shaped, v.burstLevel)
            for (i in 0 until n) y[i] += shaped[i]
        }
        normalize(y, peak)
        return y
    }

    /** A two-pole resonator (as in a formant synthesizer) whose frequency can change over time. */
    private fun resonate(x: FloatArray, freq: Curve, bandwidth: Float): FloatArray {
        val y = FloatArray(x.size)
        var y1 = 0f
        var y2 = 0f
        val c = -exp(-2.0 * PI * bandwidth / SR).toFloat()
        val r = exp(-PI * bandwidth / SR).toFloat()
        for (i in x.indices) {
            val b = 2f * r * cos(2f * PI.toFloat() * freq.at(i.toFloat() / SR) / SR)
            val a = 1f - b - c
            val v = a * x[i] + b * y1 + c * y2
            y2 = y1
            y1 = v
            y[i] = v
        }
        return y
    }

    private fun normalize(y: FloatArray, peak: Float) {
        var m = 1e-9f
        for (v in y) m = maxOf(m, abs(v))
        val k = peak / m
        for (i in y.indices) y[i] *= k
    }

    /** Two barks in a row, the second a touch higher: "ARP ARP!" */
    fun twice(v: Voice, gapSeconds: Float = 0.06f): FloatArray {
        val one = render(v)
        val higher = render(
            Voice(
                v.seconds, Curve(*FloatArray(v.f0.keys.size) { if (it % 2 == 1) v.f0.keys[it] * 1.07f else v.f0.keys[it] }),
                v.f1, v.f2, v.f3, v.amp, v.breath, v.rough, v.sub, v.fric, v.fricHz,
                v.burstAt, v.burstSeconds, v.burstLevel, v.burstHz, seed = v.seed + 1,
            ),
        )
        val gap = (gapSeconds * SR).toInt()
        return one + FloatArray(gap) + higher
    }
}
