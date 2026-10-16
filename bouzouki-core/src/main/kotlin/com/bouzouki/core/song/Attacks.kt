package com.bouzouki.core.song

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Where the plectrum strikes, from the audio Basic Pitch hears (mono, 22050 Hz). A pick's click and bright attack
 * raise the energy above 2 kHz at once; a hammer-on, pull-off or slide only moves the string's harmonics, so the
 * energy in each 2 kHz band hardly changes and little is added.
 *
 * Every [HOP] samples (frame i covers samples [HOP·i, HOP·i + [FFT]), centred at (HOP·i + FFT/2) / 22050 s) two
 * tracks are kept, both comparing a frame with the one two hops (5.8 ms) before: [rise] sums the dB rises of four
 * bands (2-4, 4-6, 6-8 and 8-11 kHz), [energy] is how much the power above 2 kHz grew.
 */
class Attacks(internal val rises: FloatArray, internal val energies: FloatArray) {
    private val riseMedian: Double = median(rises)

    /**
     * The sharpest attack within [WINDOW] of [time] (seconds), relative to the song's median frame. Where most frames
     * rise not at all (silence, a clean render) any rise is infinitely sharp: a note counts as picked unless nothing
     * rises at its start.
     */
    fun rise(time: Double): Double {
        val p = peak(rises, time)
        return if (riseMedian > 0) p / riseMedian else if (p > 0) Double.POSITIVE_INFINITY else 0.0
    }

    /** The most energy an attack adds within [WINDOW] of [time] (seconds); compare it with other notes of the song. */
    fun energy(time: Double): Double = peak(energies, time)

    /**
     * Where the plectrum strikes from [from] until [to] (seconds): the frames whose rise peaks at [level] times the
     * song's median frame or more, at least [PICK_SPACING] apart (of two closer peaks the sharper one counts).
     */
    fun picks(from: Double, to: Double, level: Double): List<Double> {
        val out = ArrayList<Double>()
        if (riseMedian <= 0) return out
        val centre = FFT / 2.0 / HOP
        val i0 = max(1, ceil(from * SAMPLE_RATE / HOP - centre - 1e-9).toInt())
        val i1 = min(rises.size - 1, ceil(to * SAMPLE_RATE / HOP - centre - 1e-9).toInt())
        var last = -1.0
        var lastValue = 0f
        for (i in i0 until i1) {
            val v = rises[i]
            if (v < level * riseMedian || v < rises[i - 1] || v <= rises[i + 1]) continue
            val t = (i * HOP + FFT / 2.0) / SAMPLE_RATE
            if (last >= 0 && t - last < PICK_SPACING) {
                if (v > lastValue) { out[out.size - 1] = t; last = t; lastValue = v }
                continue
            }
            out += t
            last = t
            lastValue = v
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = BasicPitch.SAMPLE_RATE
        const val FFT = 256
        const val HOP = 64

        /** How far from a note's start its attack is looked for (onsets and frames are a few ms apart). */
        const val WINDOW = 0.015

        /** Two picks [picks] tells apart are at least this far apart (seconds): tremolo goes up to about 25 a second. */
        const val PICK_SPACING = 0.035

        private val BANDS = intArrayOf(2000, 4000, 6000, 8000, 11025)

        /** The attack tracks of [audio] (mono, 22050 Hz). */
        fun of(audio: FloatArray): Attacks {
            val n = if (audio.size >= FFT) (audio.size - FFT) / HOP + 1 else 0
            val fft = Fft(FFT)
            val window = DoubleArray(FFT) { 0.5 - 0.5 * cos(2 * PI * it / FFT) }
            val df = SAMPLE_RATE.toDouble() / FFT
            val edges = IntArray(BANDS.size) { min(FFT / 2 + 1, ceil(BANDS[it] / df).toInt()) }
            val bands = BANDS.size - 1
            val re = DoubleArray(FFT)
            val im = DoubleArray(FFT)
            val level = Array(3) { DoubleArray(bands) } // dB of each band, for this frame and the two before (ring)
            val total = DoubleArray(3)
            val rises = FloatArray(n)
            val energies = FloatArray(n)
            for (i in 0 until n) {
                val off = i * HOP
                for (k in 0 until FFT) { re[k] = audio[off + k] * window[k]; im[k] = 0.0 }
                fft.forward(re, im)
                val slot = i % 3
                var sum = 0.0
                for (b in 0 until bands) {
                    var p = 0.0
                    for (k in edges[b] until edges[b + 1]) p += re[k] * re[k] + im[k] * im[k]
                    level[slot][b] = 10 * log10(p + 1e-9)
                    sum += p
                }
                total[slot] = sum
                if (i >= 2) {
                    val before = (i - 2) % 3
                    var r = 0.0
                    for (b in 0 until bands) r += max(0.0, level[slot][b] - level[before][b])
                    rises[i] = r.toFloat()
                    energies[i] = max(0.0, sum - total[before]).toFloat()
                }
            }
            return Attacks(rises, energies)
        }

        /** The largest value of [track] in the frames centred within [WINDOW] of [time]. */
        private fun peak(track: FloatArray, time: Double): Double {
            val centre = FFT / 2.0 / HOP
            val i0 = max(0, ceil((time - WINDOW) * SAMPLE_RATE / HOP - centre - 1e-9).toInt())
            val i1 = min(track.size, ceil((time + WINDOW) * SAMPLE_RATE / HOP - centre - 1e-9).toInt())
            var m = 0.0
            for (i in i0 until i1) m = max(m, track[i].toDouble())
            return m
        }

        /** The median (the mean of the middle two for an even count), 0 if empty. */
        internal fun median(values: FloatArray): Double {
            if (values.isEmpty()) return 0.0
            val s = values.sortedArray()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m].toDouble() else (s[m - 1].toDouble() + s[m]) / 2
        }
    }
}
