package com.bouzouki.core

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A plucked-string synthesizer tuned to sound like a Greek bouzouki: steel strings in
 * pairs (unison or octave courses) picked hard with a plectrum close to the bridge,
 * a long bright sustain, a metallic jangle from the slightly detuned pair, and the
 * nasal ring of the small staved bowl body.
 *
 * Each string is a Karplus-Strong waveguide with a fractional-delay all-pass for exact
 * tuning up to the 27th fret. [pluck] and [muteAll] are safe to call from any thread;
 * [render] is called from the audio thread.
 */
class BouzoukiSynth(private val sampleRate: Int) {
    private class Event(val course: Int, val midi: Int, val octavePair: Boolean, val velocity: Float, val mute: Boolean)

    private val events = ConcurrentLinkedQueue<Event>()
    private val voices = ArrayList<Voice>()
    private var seed = 0x2545F491L

    private val body = arrayOf(
        Biquad.highPass(sampleRate, 75.0, 0.7),
        Biquad.peak(sampleRate, 260.0, 0.9, 3.5),   // bowl-back air resonance
        Biquad.peak(sampleRate, 1000.0, 1.2, -1.5),  // scooped mids
        Biquad.peak(sampleRate, 3200.0, 1.2, 3.0),  // metallic plectrum jangle
        Biquad.lowPass(sampleRate, 9500.0, 0.7),
    )
    private val reverb = Reverb(sampleRate)

    /** Pluck [midi] on [course]. Any note still ringing on that course is damped, like a real string. */
    fun pluck(course: Int, midi: Int, octavePair: Boolean, velocity: Float = 0.85f) {
        events.add(Event(course, midi, octavePair, velocity.coerceIn(0.05f, 1f), mute = false))
    }

    /** Damp every string, like resting the palm on the strings. */
    fun muteAll() {
        events.add(Event(-1, 0, false, 0f, mute = true))
    }

    /** Fills [out] with [frames] mono samples in -1..1, starting at [offset]. */
    fun render(out: FloatArray, offset: Int = 0, frames: Int = out.size - offset) {
        while (true) {
            val e = events.poll() ?: break
            if (e.mute) {
                for (v in voices) v.damp(0.12)
                continue
            }
            for (v in voices) if (v.course == e.course) v.damp(0.025)
            voices.add(Voice(e.course, e.midi, e.octavePair, e.velocity))
            while (voices.size > MAX_VOICES) voices.removeAt(0)
        }
        for (i in 0 until frames) {
            var s = 0.0
            for (v in voices) s += v.tick()
            for (f in body) s = f.process(s)
            s += reverb.process(s) * 0.16
            s *= 0.42
            out[offset + i] = (s / (1.0 + abs(s))).toFloat() * 1.15f // soft limiter
        }
        voices.removeAll { it.finished }
    }

    private fun random(): Double {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17)
        return ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
    }

    /** One course: two strings struck together plus the click of the plectrum. */
    private inner class Voice(val course: Int, midi: Int, octavePair: Boolean, velocity: Float) {
        private val strings: Array<KsString>
        private var clickLeft = (sampleRate * 0.006).toInt()
        private val clickDecay = 0.9993.pow(48000.0 / sampleRate)
        private var clickAmp = 0.22 * velocity
        private var clickPrev = 0.0
        private var age = 0

        init {
            val f = midiToHz(midi)
            strings = if (octavePair) arrayOf(
                KsString(f * cents(-1.5), velocity, 1.0),
                KsString(f * 2 * cents(2.0), velocity, 0.62),
            ) else arrayOf(
                KsString(f * cents(-2.2), velocity, 0.8),
                KsString(f * cents(2.2), velocity, 0.8),
            )
        }

        val finished get() = age > sampleRate / 10 && strings.all { it.silent }

        fun damp(t60: Double) = strings.forEach { it.damp(t60) }

        fun tick(): Double {
            age++
            var s = 0.0
            for (st in strings) s += st.tick()
            if (clickLeft > 0) {
                clickLeft--
                val n = random() * clickAmp
                s += n - clickPrev // high-passed noise burst
                clickPrev = n
                clickAmp *= clickDecay
            }
            return s
        }
    }

    private fun cents(c: Double) = 2.0.pow(c / 1200.0)

    /** A Karplus-Strong string with a 3-tap loss filter and an all-pass for fractional delay. */
    private inner class KsString(private val freq: Double, velocity: Float, amp: Double) {
        private val buf: DoubleArray
        private var idx = 0
        private val apC: Double
        private var apX = 0.0
        private var apY = 0.0
        private var p1 = 0.0
        private var p2 = 0.0
        private var gain: Double
        private val a: Double // loss filter side tap: smaller is brighter
        private var level = 1.0

        init {
            val period = sampleRate / freq
            // Loop delay = n (buffer) + 1 (linear-phase loss filter) + d (all-pass, 0.1..1.1).
            val n = max(2, (period - 1.1).toInt())
            val d = period - 1.0 - n
            apC = (1 - d) / (1 + d)
            buf = DoubleArray(n)

            // Steel strings ring long; high notes die sooner.
            val t60 = (7.5 * sqrt(130.0 / freq)).coerceIn(1.6, 9.0)
            gain = 10.0.pow(-3.0 / (t60 * freq))
            a = (0.06 + 0.10 * (1 - velocity) + 0.04 * min(1.0, freq / 1200.0))

            // Excitation: plectrum-filtered noise, then a comb for the picking point
            // near the bridge, which gives the bouzouki its nasal, cutting tone.
            val lp = 0.12 + 0.38 * velocity
            var y = 0.0
            val ex = DoubleArray(n)
            for (i in 0 until n) { y += lp * (random() - y); ex[i] = y }
            val pick = max(1, (n * 0.14).roundToInt())
            var mean = 0.0
            for (i in 0 until n) { buf[i] = ex[i] - ex[(i - pick + n) % n]; mean += buf[i] }
            mean /= n
            var peak = 1e-9
            for (i in 0 until n) { buf[i] -= mean; peak = max(peak, abs(buf[i])) }
            val scale = amp * (0.35 + 0.65 * velocity) / peak
            for (i in 0 until n) buf[i] *= scale
        }

        val silent get() = level < 2e-4

        fun damp(t60: Double) {
            gain = min(gain, 10.0.pow(-3.0 / (t60 * freq))) // gain is applied once per period
        }

        fun tick(): Double {
            val x = buf[idx]
            val lossed = a * x + (1 - 2 * a) * p1 + a * p2
            p2 = p1; p1 = x
            val y = apC * lossed + apX - apC * apY
            apX = lossed; apY = y
            buf[idx] = y * gain
            if (++idx == buf.size) idx = 0
            level = level * 0.9995 + abs(x) * 0.0005
            return x
        }
    }

    companion object {
        const val MAX_VOICES = 16
    }
}

/** RBJ cookbook biquad. */
internal class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun peak(fs: Int, f: Double, q: Double, db: Double): Biquad {
            val amp = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val al = sin(w) / (2 * q)
            return make(1 + al * amp, -2 * cos(w), 1 - al * amp, 1 + al / amp, -2 * cos(w), 1 - al / amp)
        }

        fun lowPass(fs: Int, f: Double, q: Double): Biquad {
            val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
            return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
        }

        fun highPass(fs: Int, f: Double, q: Double): Biquad {
            val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
        }
    }
}

/** A small Schroeder room so the notes don't sound dry. Returns only the wet signal. */
internal class Reverb(fs: Int) {
    private val combs = intArrayOf(1116, 1188, 1277, 1356).map { DelayLine((it * fs / 44100.0).toInt()) }
    private val allpasses = intArrayOf(556, 441).map { DelayLine((it * fs / 44100.0).toInt()) }
    private val damp = DoubleArray(combs.size)

    fun process(x: Double): Double {
        var s = 0.0
        for ((i, c) in combs.withIndex()) {
            val y = c.read()
            damp[i] = y * 0.6 + damp[i] * 0.4
            c.write(x * 0.25 + damp[i] * 0.78)
            s += y
        }
        for (ap in allpasses) {
            val d = ap.read()
            val y = -s + d
            ap.write(s + d * 0.5)
            s = y
        }
        return s
    }

    private class DelayLine(size: Int) {
        private val buf = DoubleArray(max(1, size))
        private var i = 0
        fun read() = buf[i]
        fun write(v: Double) { buf[i] = v; if (++i == buf.size) i = 0 }
    }
}
