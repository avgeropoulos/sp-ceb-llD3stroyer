package com.mixdeck.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** A stereo biquad filter (RBJ audio EQ cookbook), Direct Form I. */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private val x1 = DoubleArray(2); private val x2 = DoubleArray(2)
    private val y1 = DoubleArray(2); private val y2 = DoubleArray(2)

    fun reset() { x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0) }

    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
        x2[ch] = x1[ch]; x1[ch] = x
        y2[ch] = y1[ch]; y1[ch] = if (abs(y) < 1e-25) 0.0 else y
        return y
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
    }

    fun bypass() = set(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    fun lowPass(sr: Int, f: Double, q: Double) {
        val w = 2 * PI * f / sr; val c = cos(w); val alpha = sin(w) / (2 * q)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    fun highPass(sr: Int, f: Double, q: Double) {
        val w = 2 * PI * f / sr; val c = cos(w); val alpha = sin(w) / (2 * q)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    fun peaking(sr: Int, f: Double, q: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f / sr; val c = cos(w); val alpha = sin(w) / (2 * q)
        set(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    fun lowShelf(sr: Int, f: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f / sr; val c = cos(w)
        val alpha = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * alpha
        set(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
    }

    fun highShelf(sr: Int, f: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f / sr; val c = cos(w)
        val alpha = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * alpha
        set(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
            (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa)
    }
}

/**
 * DJ-style three-band EQ. Each knob goes from -1 (band killed) through 0 (flat) to +1 (+6 dB).
 */
class ThreeBandEq(private val sr: Int) {
    private val low = Biquad(); private val mid = Biquad(); private val high = Biquad()
    private var lowK = 0f; private var midK = 0f; private var highK = 0f
    private var flat = true

    init { update() }

    fun set(lowKnob: Float, midKnob: Float, highKnob: Float) {
        if (lowKnob == lowK && midKnob == midK && highKnob == highK) return
        lowK = lowKnob; midK = midKnob; highK = highKnob
        update()
    }

    private fun update() {
        low.lowShelf(sr, 220.0, knobToDb(lowK))
        mid.peaking(sr, 1100.0, 0.7, knobToDb(midK))
        high.highShelf(sr, 4500.0, knobToDb(highK))
        flat = lowK == 0f && midK == 0f && highK == 0f
    }

    fun process(x: Double, ch: Int): Double {
        if (flat) return x
        return high.process(mid.process(low.process(x, ch), ch), ch)
    }

    companion object {
        /** -1 → -40 dB (a kill), 0 → 0 dB, +1 → +6 dB. */
        fun knobToDb(k: Float): Double = if (k >= 0) 6.0 * k else 40.0 * k
    }
}

/**
 * One-knob DJ filter. Turn left for a low-pass sweep, right for a high-pass sweep; centre is off.
 */
class DjFilter(private val sr: Int) {
    private val f = Biquad()
    private var knob = 0f
    var active = false; private set

    fun set(k: Float) {
        if (k == knob) return
        val wasActive = active
        knob = k
        active = abs(k) > 0.02f
        if (!active) return
        if (!wasActive) f.reset()
        val amt = (abs(k) - 0.02) / 0.98
        if (k < 0) f.lowPass(sr, 18000.0 * (70.0 / 18000.0).pow(amt), 1.2)
        else f.highPass(sr, 25.0 * (9000.0 / 25.0).pow(amt), 1.2)
    }

    fun process(x: Double, ch: Int): Double = if (active) f.process(x, ch) else x
}

enum class FxType(val label: String) { ECHO("ECHO"), FLANGER("FLANGER"), REVERB("REVERB"), CRUSH("CRUSH") }

/**
 * A per-deck effect slot. The echo and reverb keep ringing out after the effect is switched
 * off, the way a DJ "echo out" works.
 */
class EffectUnit(private val sr: Int) {
    var type = FxType.ECHO
        set(v) { if (v != field) { field = v; clear() } }
    var on = false
    /** 0..1 wet amount. */
    var amount = 0.5f
    /** Echo time / flanger period in beats. */
    var beats = 0.75
    /** Beat length in seconds, kept up to date by the deck (0.5 s when the BPM is unknown). */
    var beatSeconds = 0.5

    // Echo
    private val echoLen = sr * 3
    private val echoBuf = Array(2) { FloatArray(echoLen) }
    private var echoPos = 0
    private var echoDelay = sr / 2.0
    // Flanger
    private val flBuf = Array(2) { FloatArray(2048) }
    private var flPos = 0
    private var lfo = 0.0
    // Reverb (Schroeder: 4 combs + 2 allpasses per channel)
    private val combLens = intArrayOf(1557, 1617, 1491, 1422)
    private val apLens = intArrayOf(225, 556)
    private val combs = Array(2) { ch -> Array(4) { FloatArray(scaleLen(combLens[it] + ch * 23)) } }
    private val combIdx = Array(2) { IntArray(4) }
    private val combLp = Array(2) { FloatArray(4) }
    private val aps = Array(2) { ch -> Array(2) { FloatArray(scaleLen(apLens[it] + ch * 23)) } }
    private val apIdx = Array(2) { IntArray(2) }
    private var revTail = 0
    // Crush
    private val hold = FloatArray(2)
    private var holdCount = 0

    private fun scaleLen(n: Int) = (n * sr / 44100.0).toInt().coerceAtLeast(1)

    fun clear() {
        echoBuf.forEach { it.fill(0f) }; flBuf.forEach { it.fill(0f) }
        combs.forEach { c -> c.forEach { it.fill(0f) } }; aps.forEach { a -> a.forEach { it.fill(0f) } }
        combLp.forEach { it.fill(0f) }; revTail = 0
    }

    /** Processes one interleaved stereo block in place. */
    fun process(buf: FloatArray, frames: Int) {
        when (type) {
            FxType.ECHO -> echo(buf, frames)
            FxType.FLANGER -> if (on) flanger(buf, frames)
            FxType.REVERB -> reverb(buf, frames)
            FxType.CRUSH -> if (on) crush(buf, frames)
        }
    }

    private fun echo(buf: FloatArray, frames: Int) {
        val target = (beats * beatSeconds * sr).coerceIn(sr * 0.02, echoLen - 2.0)
        val feedback = 0.35f + 0.4f * amount
        val wet = 0.4f + 0.6f * amount
        val input = if (on) 1f else 0f
        for (i in 0 until frames) {
            echoDelay += (target - echoDelay) * 0.0005
            var rp = echoPos - echoDelay
            if (rp < 0) rp += echoLen
            val r0 = rp.toInt(); val fr = (rp - r0).toFloat(); val r1 = (r0 + 1) % echoLen
            for (ch in 0..1) {
                val b = echoBuf[ch]
                val d = b[r0] + (b[r1] - b[r0]) * fr
                val x = buf[i * 2 + ch]
                b[echoPos] = x * input + d * feedback
                buf[i * 2 + ch] = x + d * wet
            }
            echoPos = (echoPos + 1) % echoLen
        }
    }

    private fun flanger(buf: FloatArray, frames: Int) {
        val periodSec = (beats * 8 * beatSeconds).coerceIn(0.5, 16.0)
        val inc = 1.0 / (periodSec * sr)
        val n = flBuf[0].size
        val mix = 0.3f + 0.5f * amount
        for (i in 0 until frames) {
            lfo += inc; if (lfo >= 1) lfo -= 1
            val delay = sr * (0.0008 + 0.0042 * (0.5 - 0.5 * cos(2 * PI * lfo)))
            var rp = flPos - delay
            if (rp < 0) rp += n
            val r0 = rp.toInt(); val fr = (rp - r0).toFloat(); val r1 = (r0 + 1) % n
            for (ch in 0..1) {
                val b = flBuf[ch]
                val d = b[r0] + (b[r1] - b[r0]) * fr
                val x = buf[i * 2 + ch]
                b[flPos] = x + d * 0.6f
                buf[i * 2 + ch] = x * (1 - mix * 0.5f) + d * mix
            }
            flPos = (flPos + 1) % n
        }
    }

    private fun reverb(buf: FloatArray, frames: Int) {
        if (on) revTail = sr * 4 else if (revTail <= 0) return
        revTail -= frames
        val input = if (on) 0.05f else 0f
        val feedback = 0.80f + 0.12f * amount
        val damp = 0.3f
        val wet = 0.25f + 0.55f * amount
        for (i in 0 until frames) {
            for (ch in 0..1) {
                val x = buf[i * 2 + ch]
                var sum = 0f
                for (c in 0 until 4) {
                    val b = combs[ch][c]; val idx = combIdx[ch][c]
                    val y = b[idx]
                    combLp[ch][c] = y * (1 - damp) + combLp[ch][c] * damp
                    b[idx] = x * input + combLp[ch][c] * feedback
                    combIdx[ch][c] = if (idx + 1 == b.size) 0 else idx + 1
                    sum += y
                }
                for (a in 0 until 2) {
                    val b = aps[ch][a]; val idx = apIdx[ch][a]
                    val bo = b[idx]
                    b[idx] = sum + bo * 0.5f
                    sum = bo - sum
                    apIdx[ch][a] = if (idx + 1 == b.size) 0 else idx + 1
                }
                buf[i * 2 + ch] = x + sum * wet
            }
        }
    }

    private fun crush(buf: FloatArray, frames: Int) {
        val bits = 12 - (amount * 9).toInt()
        val levels = (1 shl bits).toFloat()
        val step = 1 + (amount * 14).toInt()
        for (i in 0 until frames) {
            if (holdCount <= 0) {
                hold[0] = (buf[i * 2] * levels).toInt() / levels
                hold[1] = (buf[i * 2 + 1] * levels).toInt() / levels
                holdCount = step
            }
            holdCount--
            buf[i * 2] = hold[0]; buf[i * 2 + 1] = hold[1]
        }
    }
}

/** A master-bus peak limiter, so stacked decks and effects never clip harshly. */
class Limiter(sr: Int) {
    private var gain = 1.0
    private val release = 1.0 - exp(-1.0 / (0.25 * sr))
    private val ceiling = 0.97

    fun process(buf: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val l = buf[i * 2].toDouble(); val r = buf[i * 2 + 1].toDouble()
            val peak = maxOf(abs(l), abs(r))
            if (peak * gain > ceiling) gain = ceiling / peak
            else gain += (1.0 - gain) * release
            buf[i * 2] = softClip(l * gain).toFloat()
            buf[i * 2 + 1] = softClip(r * gain).toFloat()
        }
    }

    private fun softClip(x: Double): Double = if (abs(x) < 0.9) x else tanh(x)
}

fun dbToGain(db: Double): Double = 10.0.pow(db / 20)
fun gainToDb(g: Double): Double = 20 * ln(g.coerceAtLeast(1e-9)) / ln(10.0)
