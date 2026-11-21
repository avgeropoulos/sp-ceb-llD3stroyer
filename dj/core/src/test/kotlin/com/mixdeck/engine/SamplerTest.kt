package com.mixdeck.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SamplerTest {
    @Test fun everySoundPlaysCleanly() {
        val s = Sampler(44100)
        for (n in Sampler.BANK.indices) {
            s.assign(0, n)
            assertEquals(Sampler.BANK[n], s.name(0))
            s.trigger(0)
            val out = FloatArray(2048)
            var peak = 0f; var blocks = 0
            while (s.isPlaying(0) && blocks < 400) {
                out.fill(0f); s.render(out, 1024, 1f); blocks++
                for (v in out) { assertTrue(v.isFinite()); peak = maxOf(peak, abs(v)) }
            }
            assertFalse(s.isPlaying(0), "${Sampler.BANK[n]} never ends")
            assertTrue(peak in 0.05f..1f, "${Sampler.BANK[n]} peak $peak")
        }
        assertEquals(Sampler.BANK.size, Sampler.ABOUT.size)
    }

    @Test fun padSoundsAreSaved() {
        val s = Sampler(44100)
        s.assign(2, 9); s.assign(3, 4)
        assertContentEquals(intArrayOf(0, 1, 9, 4), Sampler.decode(s.encode()))
        assertContentEquals(intArrayOf(0, 1, 2, 3), Sampler.decode("1,2,99,3"))
        assertContentEquals(intArrayOf(0, 1, 2, 3), Sampler.decode(null))
    }
}
