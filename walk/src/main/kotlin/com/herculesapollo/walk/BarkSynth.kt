package com.herculesapollo.walk

import com.roccoquest.game.Chiptune
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * The dogs' barks. A real bark is short and explosive: a puff of air, a rough, noisy
 * voice that jumps up in pitch and drops away, and a harsh edge. Each one here is built
 * like that, and shaped by the resonances of the mouth and throat (the way a speech
 * synthesizer makes vowels) so that it barks the word: Hercules' deep, gruff "GAV" and
 * Apollo's high, bright "ARP".
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

        /** The same curve with every value multiplied by [k]. */
        fun times(k: Float) = Curve(*FloatArray(keys.size) { if (it % 2 == 1) keys[it] * k else keys[it] })
    }

    /** A puff of air or a little pop, at [at] seconds, [seconds] long, [level] loud, centred on [hz]. */
    class Burst(val at: Float, val seconds: Float, val level: Float, val hz: Float)

    /**
     * One bark. [f0] is the pitch; [f1]..[f3] the resonances that make the vowel; [amp]
     * the loudness; [breath] how much rough noise is in the voice. [rough] wobbles each
     * buzz and [sub] makes every other one quieter (a gravelly growl). [drive] pushes it
     * into a harsh, saturated edge. [chestHz] adds a low thump at the start for a big
     * dog; [fric] a hiss (the "v") around [fricHz].
     */
    class Voice(
        val seconds: Float,
        val f0: Curve, val f1: Curve, val f2: Curve, val f3: Curve,
        val amp: Curve, val breath: Curve,
        val rough: Float, val sub: Float, val drive: Float,
        val bursts: List<Burst>,
        val chestHz: Float = 0f, val chestSeconds: Float = 0.09f, val chestLevel: Float = 0f,
        val fric: Curve? = null, val fricLevel: Float = 0f, val fricHz: Float = 2400f,
        val seed: Int = 1,
    ) {
        fun withPitch(k: Float, seed: Int) = Voice(
            seconds, f0.times(k), f1, f2, f3, amp, breath, rough, sub, drive, bursts,
            chestHz, chestSeconds, chestLevel, fric, fricLevel, fricHz, seed,
        )
    }

    /** Hercules: a deep, gruff "GAV!" (about 150 Hz), with a thump from the chest. */
    val gav = Voice(
        0.25f,
        f0 = Curve(0f, 110f, 0.02f, 158f, 0.05f, 150f, 0.15f, 112f, 0.25f, 80f),
        f1 = Curve(0f, 380f, 0.02f, 600f, 0.05f, 680f, 0.15f, 620f, 0.19f, 360f, 0.25f, 280f),
        f2 = Curve(0f, 1250f, 0.04f, 1150f, 0.15f, 1080f, 0.25f, 950f),
        f3 = Curve(0f, 2300f, 0.25f, 2150f),
        amp = Curve(0f, 0f, 0.004f, 1f, 0.05f, 0.95f, 0.14f, 0.7f, 0.19f, 0.35f, 0.25f, 0f),
        breath = Curve(0f, 1f, 0.02f, 0.45f, 0.12f, 0.4f, 0.25f, 0.55f),
        rough = 0.08f, sub = 0.45f, drive = 2.6f,
        bursts = listOf(Burst(0f, 0.025f, 0.55f, 900f)),
        chestHz = 140f, chestSeconds = 0.09f, chestLevel = 0.45f,
        fric = Curve(0f, 0f, 0.17f, 0f, 0.2f, 1f, 0.24f, 0.5f, 0.25f, 0f), fricLevel = 0.08f, fricHz = 2400f,
    )

    /** Apollo: a high, bright "ARP!" (about 640 Hz), sliding down through the "r" to a little "p". */
    val arp = Voice(
        0.17f,
        f0 = Curve(0f, 500f, 0.015f, 690f, 0.04f, 650f, 0.09f, 540f, 0.13f, 460f, 0.17f, 420f),
        f1 = Curve(0f, 800f, 0.015f, 1000f, 0.06f, 950f, 0.1f, 700f, 0.13f, 480f, 0.17f, 420f),
        f2 = Curve(0f, 1600f, 0.06f, 1550f, 0.11f, 1350f, 0.17f, 1300f),
        f3 = Curve(0f, 3100f, 0.06f, 3000f, 0.1f, 1900f, 0.13f, 1750f, 0.17f, 1850f),
        amp = Curve(0f, 0f, 0.003f, 1f, 0.07f, 0.85f, 0.12f, 0.55f, 0.135f, 0f, 0.17f, 0f),
        breath = Curve(0f, 0.9f, 0.015f, 0.35f, 0.12f, 0.4f, 0.17f, 0.4f),
        rough = 0.05f, sub = 0f, drive = 2.2f,
        bursts = listOf(Burst(0f, 0.015f, 0.45f, 2000f), Burst(0.148f, 0.012f, 0.35f, 2600f)),
    )

    /** Renders [v] as mono samples at Chiptune.RATE, peaking at [peak]. */
    fun render(v: Voice, peak: Float = 0.8f): FloatArray {
        val n = (v.seconds * SR).toInt()
        val rnd = Random(v.seed)
        fun gauss() = (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 1.5f) * 1.6f
        // The voice: one glottal pulse per period (its slope, which is what the ear hears),
        // mixed with rough breath noise.
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
                pulse = (if (count % 2 == 1) 1f - v.sub else 1f) * (1f + 1.5f * v.rough * (rnd.nextFloat() * 2f - 1f))
            }
            val g = when {
                phase < 0.5f -> 0.5f * (1f - cos(PI.toFloat() * phase / 0.5f))
                phase < 0.7f -> cos(PI.toFloat() * (phase - 0.5f) / 0.4f)
                else -> 0f
            }
            src[i] = ((g - prev) * pulse * 6f + v.breath.at(t) * gauss() * 0.6f) * v.amp.at(t)
            prev = g
        }
        var y = resonate(src, v.f1, 110f)
        y = resonate(y, v.f2, 150f)
        y = resonate(y, v.f3, 220f)
        normalize(y, 1f)
        // A big dog's bark starts with a thump from the chest.
        if (v.chestLevel > 0f) {
            var p = 0f
            for (i in 0 until n) {
                val t = i.toFloat() / SR
                val e = (1f - t / v.chestSeconds).coerceAtLeast(0f)
                p += v.chestHz * (1f - 0.4f * t / v.chestSeconds) / SR
                y[i] += sin(2f * PI.toFloat() * p) * e * e * v.chestLevel
            }
        }
        v.fric?.let { fric ->
            val hiss = resonate(FloatArray(n) { gauss() }, Curve(0f, v.fricHz), 1800f)
            normalize(hiss, v.fricLevel)
            for (i in 0 until n) y[i] += hiss[i] * fric.at(i.toFloat() / SR)
        }
        for (b in v.bursts) {
            val i0 = (b.at * SR).toInt()
            val len = (b.seconds * SR).toInt().coerceAtMost(n - i0)
            val puff = FloatArray(n)
            for (k in 0 until len) {
                val fade = 1f - k.toFloat() / len
                puff[i0 + k] = gauss() * fade * sqrt(fade)
            }
            val shaped = resonate(puff, Curve(0f, b.hz), 1500f)
            normalize(shaped, b.level)
            for (i in 0 until n) y[i] += shaped[i]
        }
        // The harsh edge of a real bark: drive it into soft saturation.
        if (v.drive > 0f) {
            normalize(y, 1f)
            for (i in 0 until n) y[i] = tanh(v.drive * y[i])
        }
        // A touch of room: two short echoes.
        val out = y.copyOf()
        for ((delay, gain) in listOf(0.023f to 0.18f, 0.041f to 0.1f)) {
            val k = (delay * SR).toInt()
            for (i in k until n) out[i] += y[i - k] * gain
        }
        normalize(out, peak)
        return out
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
    fun twice(v: Voice, gapSeconds: Float = 0.07f): FloatArray =
        render(v) + FloatArray((gapSeconds * SR).toInt()) + render(v.withPitch(1.07f, v.seed + 1))
}
