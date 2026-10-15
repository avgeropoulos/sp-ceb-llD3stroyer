package com.bouzouki.core

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
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
 * tuning up to the 27th fret. A [legato] note moves the ringing string to its new pitch
 * instead of picking it again (the waveguide then reads its delay line at a moving,
 * interpolated length). Notes sound at A = 440 Hz, or [tuningCents] off it. [pluck], [legato],
 * [muteAll] and [tuningCents] are safe to use from any thread; [render] is called from the audio thread.
 */
class BouzoukiSynth(private val sampleRate: Int) {
    /** How a legato note leaves the note before it on the same course. */
    enum class Legato {
        /** A finger hammers down on a higher fret: there at once, with a soft knock. */
        HAMMER,
        /** The finger pulls off to a lower fret (or the open string), plucking it lightly. */
        PULL,
        /** The finger slides along the string: the pitch glides there. */
        SLIDE,
    }

    /** [ratio]: the tuning's pitch ratio when the note was asked for. */
    private class Event(val type: Int, val course: Int, val midi: Int, val octavePair: Boolean, val velocity: Float, val legato: Legato?, val ratio: Double = 1.0)

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

    /**
     * How far from A = 440 Hz the notes [pluck]ed and played [legato] from now on sound (cents, + sharp, - flat): a
     * song's tuning when playing along with a record that is off A = 440 (a note already ringing keeps its pitch).
     */
    @Volatile
    var tuningCents: Double = 0.0

    /** Pluck [midi] on [course]. Any note still ringing on that course is damped, like a real string. */
    fun pluck(course: Int, midi: Int, octavePair: Boolean, velocity: Float = 0.85f) {
        events.add(Event(PLUCK, course, midi, octavePair, velocity.coerceIn(0.05f, 1f), null, tuningRatio()))
    }

    /**
     * Moves the note ringing on [course] to [midi] without picking it again, so there is no
     * plectrum click: a [Legato.SLIDE] glides there in [slideSeconds], a hammer-on or pull-off
     * gets there within a few milliseconds and adds the small excitation of the finger.
     * If nothing rings on the course any more, the note is picked softly instead.
     */
    fun legato(course: Int, midi: Int, octavePair: Boolean, kind: Legato, velocity: Float = 0.8f) {
        events.add(Event(LEGATO, course, midi, octavePair, velocity.coerceIn(0.05f, 1f), kind, tuningRatio()))
    }

    private fun tuningRatio(): Double = tuningCents.let { if (it.isFinite()) cents(it.coerceIn(-100.0, 100.0)) else 1.0 }

    /** Damp every string, like resting the palm on the strings. */
    fun muteAll() {
        events.add(Event(MUTE, -1, 0, false, 0f, null))
    }

    /** Fills [out] with [frames] mono samples in -1..1, starting at [offset]. */
    fun render(out: FloatArray, offset: Int = 0, frames: Int = out.size - offset) {
        while (true) {
            val e = events.poll() ?: break
            if (e.type == MUTE) {
                for (v in voices) v.damp(0.12)
                continue
            }
            val kind = e.legato
            if (e.type == LEGATO && kind != null) {
                // The newest voice on the course is the string ringing there now.
                val v = voices.lastOrNull { it.course == e.course }
                if (v != null && v.ringing) { v.legato(e.midi, kind, e.velocity, e.ratio); continue }
            }
            for (v in voices) if (v.course == e.course) v.damp(0.025)
            val soft = if (e.type == LEGATO) 0.6f else 1f // a legato note with nothing left to move
            voices.add(Voice(e.course, e.midi, e.octavePair, e.velocity * soft, e.ratio))
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

    /** One course: two strings struck together plus the click of the plectrum; [tuning]: the pitch ratio to A = 440. */
    private inner class Voice(val course: Int, private var midi: Int, octavePair: Boolean, velocity: Float, tuning: Double) {
        /** Each string's pitch as a multiple of the note's: the octave string and the slight detuning of the pair. */
        private val ratios = if (octavePair) doubleArrayOf(cents(-1.5), 2 * cents(2.0)) else doubleArrayOf(cents(-2.2), cents(2.2))
        private val strings: Array<KsString>
        private var clickLeft = (sampleRate * 0.006).toInt()
        private val clickDecay = 0.9993.pow(48000.0 / sampleRate)
        private var clickAmp = 0.22 * velocity
        private var clickPrev = 0.0
        private var age = 0

        init {
            val f = midiToHz(midi) * tuning
            strings = if (octavePair) arrayOf(
                KsString(f * ratios[0], velocity, 1.0),
                KsString(f * ratios[1], velocity, 0.62),
            ) else arrayOf(
                KsString(f * ratios[0], velocity, 0.8),
                KsString(f * ratios[1], velocity, 0.8),
            )
        }

        val finished get() = age > sampleRate / 10 && strings.all { it.silent }

        /** Still loud enough to carry a legato note. */
        val ringing get() = strings.any { it.audible }

        fun damp(t60: Double) = strings.forEach { it.damp(t60) }

        /** Moves both strings to [newMidi]; see [BouzoukiSynth.legato]. */
        fun legato(newMidi: Int, kind: Legato, velocity: Float, tuning: Double) {
            val seconds = if (kind == Legato.SLIDE) slideSeconds(newMidi - midi) else FINGER_SECONDS
            val excite = when (kind) {
                Legato.HAMMER -> 0.16 * velocity
                Legato.PULL -> 0.24 * velocity
                Legato.SLIDE -> 0.0
            }
            // A hammer-on knocks the string with the soft fingertip; a pull-off plucks it, brighter.
            val bright = if (kind == Legato.PULL) 0.2 + 0.25 * velocity else 0.08 + 0.14 * velocity
            val f = midiToHz(newMidi) * tuning
            for (k in strings.indices) strings[k].moveTo(f * ratios[k], seconds, excite, bright)
            midi = newMidi
        }

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

    /** How long steel strings ring at [freq] Hz: high notes die sooner. */
    private fun naturalT60(freq: Double) = (7.5 * sqrt(130.0 / freq)).coerceIn(1.6, 9.0)

    /**
     * A Karplus-Strong string with a 3-tap loss filter and an all-pass for fractional delay.
     * The first legato note ([moveTo]) moves it onto a longer delay line, read at a fractional
     * and possibly gliding length with cubic (Lagrange) interpolation, which stays smooth while
     * the length changes, where the all-pass would ring.
     */
    private inner class KsString(private var freq: Double, velocity: Float, private val amp: Double) {
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
        private var t60: Double
        private var dampT60 = Double.MAX_VALUE

        // The moving delay line, made by the first legato note.
        private var line: DoubleArray? = null
        private var mask = 0
        private var w = 0                // where the next sample is written
        private var loop = 0.0           // loop length in samples: the read delay plus the loss filter's one
        private var lnFrom = 0.0         // a glide moves ln(loop) from lnFrom to lnTo
        private var lnTo = 0.0
        private var glideLen = 0
        private var glidePos = 0

        init {
            val period = sampleRate / freq
            // Loop delay = n (buffer) + 1 (linear-phase loss filter) + d (all-pass, 0.1..1.1).
            val n = max(2, (period - 1.1).toInt())
            val d = period - 1.0 - n
            apC = (1 - d) / (1 + d)
            buf = DoubleArray(n)

            t60 = naturalT60(freq)
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
        val audible get() = level > 1e-3

        fun damp(t60: Double) {
            dampT60 = min(dampT60, t60)
            this.t60 = min(this.t60, t60)
            gain = min(gain, 10.0.pow(-3.0 / (t60 * freq))) // gain is applied once per period
        }

        /**
         * Glides to [target] Hz over [seconds], evenly in pitch with an eased start and end, and if
         * [excite] > 0 adds a knock of that size (relative to the string's pick) and [bright]ness.
         */
        fun moveTo(target: Double, seconds: Double, excite: Double, bright: Double) {
            val toLoop = sampleRate / target
            if (line == null) startLine(toLoop) else if (toLoop + 8 > mask) grow(toLoop + 8)
            lnFrom = ln(loop)
            lnTo = ln(toLoop)
            glideLen = max(1, (seconds * sampleRate).toInt())
            glidePos = 0
            freq = target
            t60 = min(dampT60, naturalT60(target))
            gain = 10.0.pow(-3.0 / (t60 * freq))
            if (excite > 0) knock(toLoop.toInt(), excite, bright)
        }

        /** Moves the ringing samples onto a delay line long enough for [toLoop] too, keeping the pitch. */
        private fun startLine(toLoop: Double) {
            val n = buf.size
            loop = n + 1 + (1 - apC) / (1 + apC) // the all-pass's delay at low frequencies
            val l = DoubleArray(capacityFor(max(loop, toLoop) + 8))
            mask = l.size - 1
            w = 0
            // buf[idx] was written n samples ago, the sample read before it (p1) n + 1, and so on.
            for (j in 0 until n) l[(w - (n - j)) and mask] = buf[(idx + j) % n]
            l[(w - n - 1) and mask] = p1
            l[(w - n - 2) and mask] = p2
            l[(w - n - 3) and mask] = p2
            line = l
        }

        private fun grow(need: Double) {
            val old = line ?: return
            val l = DoubleArray(capacityFor(need))
            for (k in 1..old.size) l[(w - k) and (l.size - 1)] = old[(w - k) and mask]
            line = l
            mask = l.size - 1
        }

        private fun capacityFor(samples: Double): Int {
            var c = 64
            while (c < samples) c *= 2
            return c
        }

        /** Adds one period of soft noise to the samples about to be read, tapered at both ends so it joins smoothly. */
        private fun knock(len: Int, excite: Double, bright: Double) {
            val l = line ?: return
            if (len < 4) return
            val ex = DoubleArray(len)
            var y = 0.0
            var sum = 0.0
            var weight = 0.0
            for (i in 0 until len) {
                y += bright * (random() - y)
                val win = sin(PI * (i + 0.5) / len).let { it * it }
                ex[i] = y * win
                sum += ex[i]
                weight += win
            }
            val mean = sum / weight
            var peak = 1e-9
            for (i in 0 until len) {
                ex[i] -= mean * sin(PI * (i + 0.5) / len).let { it * it }
                peak = max(peak, abs(ex[i]))
            }
            val scale = excite * amp / peak
            for (i in 0 until len) l[(w - len + i) and mask] += ex[i] * scale
        }

        fun tick(): Double {
            val l = line
            val x: Double
            if (l == null) {
                x = buf[idx]
                val lossed = a * x + (1 - 2 * a) * p1 + a * p2
                p2 = p1; p1 = x
                val y = apC * lossed + apX - apC * apY
                apX = lossed; apY = y
                buf[idx] = y * gain
                if (++idx == buf.size) idx = 0
            } else {
                if (glidePos < glideLen) {
                    glidePos++
                    val e = 0.5 - 0.5 * cos(PI * glidePos / glideLen) // eased in and out
                    loop = exp(lnFrom + (lnTo - lnFrom) * e)
                }
                // Read loop - 1 samples back (the loss filter adds the last one): cubic Lagrange
                // through the samples m - 1 .. m + 2 back, at m + f.
                val d = loop - 1.0
                val m = d.toInt()
                val f = d - m
                val s0 = l[(w - m + 1) and mask]
                val s1 = l[(w - m) and mask]
                val s2 = l[(w - m - 1) and mask]
                val s3 = l[(w - m - 2) and mask]
                val fm1 = f - 1
                val fm2 = f - 2
                val fp1 = f + 1
                x = -f * fm1 * fm2 / 6 * s0 + fp1 * fm1 * fm2 / 2 * s1 - fp1 * f * fm2 / 2 * s2 + fp1 * f * fm1 / 6 * s3
                val lossed = a * x + (1 - 2 * a) * p1 + a * p2
                p2 = p1; p1 = x
                l[w] = lossed * gain
                w = (w + 1) and mask
            }
            level = level * 0.9995 + abs(x) * 0.0005
            return x
        }
    }

    companion object {
        const val MAX_VOICES = 16
        private const val PLUCK = 0
        private const val LEGATO = 1
        private const val MUTE = 2
        /** How long a hammer-on or pull-off takes to reach its pitch, in seconds. */
        private const val FINGER_SECONDS = 0.003

        /** How long a slide over [semitones] (either way) takes, in seconds: longer for longer slides. */
        fun slideSeconds(semitones: Int): Double = (0.04 + 0.014 * abs(semitones)).coerceAtMost(0.16)
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
