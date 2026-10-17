package com.bouzouki.core.song

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightSeparationTest {
    private val fs = 44100

    /** Amplitude of the [f] Hz component of [x] over its middle half. */
    private fun level(x: FloatArray, f: Double): Double {
        var c = 0.0; var s = 0.0
        val from = x.size / 4; val to = 3 * x.size / 4
        for (i in from until to) { c += x[i] * cos(2 * PI * f * i / fs); s += x[i] * sin(2 * PI * f * i / fs) }
        return 2 * hypot(c, s) / (to - from)
    }

    @Test
    fun keepsThePannedInstrumentAndDropsTheCentreBassAndDrums() {
        val n = 4 * fs
        val rnd = Random(5)
        val l = FloatArray(n); val r = FloatArray(n)
        for (i in 0 until n) {
            val bouzouki = 0.3 * sin(2 * PI * 587.33 * i / fs) // a D panned left
            val voice = 0.3 * sin(2 * PI * 784.0 * i / fs) // a G in the centre
            val bass = 0.3 * sin(2 * PI * 55.0 * i / fs) // centre, below the band anyway
            l[i] = (0.8 * bouzouki + voice + bass).toFloat()
            r[i] = (0.2 * bouzouki + voice + bass).toFloat()
        }
        // A drum hit every half second.
        for (b in 0 until 8) for (j in 0 until 300) {
            val v = (rnd.nextDouble() * 2 - 1).toFloat() * 0.8f
            l[b * fs / 2 + j] += v; r[b * fs / 2 + j] += v
        }
        val out = LightSeparator().bouzoukiMono(l, r)
        assertEquals(n, out.size)
        val keep = level(out, 587.33) / 0.15 // the input mid has 0.5·(0.8+0.2)·0.3
        val voice = level(out, 784.0) / 0.3
        val bass = level(out, 55.0) / 0.3
        assertTrue(keep > 0.7, "bouzouki kept $keep")
        assertTrue(voice < 0.3, "centre voice $voice")
        assertTrue(bass < 0.3, "bass $bass")
        // The clicks: far less energy right after each hit than in the input.
        var hit = 0.0; var hitIn = 0.0
        for (b in 1 until 8) for (j in 0 until 300) {
            val i = b * fs / 2 + j
            val m = (l[i] + r[i]) / 2 - (0.5 * 0.3 * sin(2 * PI * 587.33 * i / fs) + 0.3 * sin(2 * PI * 784.0 * i / fs) + 0.3 * sin(2 * PI * 55.0 * i / fs))
            hitIn += m * m
            val o = out[i] - 0.15 * keep * sin(2 * PI * 587.33 * i / fs)
            hit += o * o
        }
        println("light: bouzouki %.2f, voice %.2f, bass %.2f, drums %.2f".format(keep, voice, bass, hit / hitIn))
        assertTrue(hit < 0.3 * hitIn, "drum energy ${hit / hitIn}")
    }

    @Test
    fun median() {
        assertEquals(3.0, LightSeparator.median(doubleArrayOf(5.0, 1.0, 3.0, 9.0, 2.0), 5))
        assertEquals(2.5, LightSeparator.median(doubleArrayOf(4.0, 1.0, 2.0, 3.0), 4))
        assertEquals(0.0, LightSeparator.median(DoubleArray(3), 0))
    }
}
