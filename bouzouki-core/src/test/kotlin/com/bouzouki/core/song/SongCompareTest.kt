package com.bouzouki.core.song

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SongCompareTest {
    @Test
    fun linesUpTwoRecordingsOfATuneAndScoresThem() {
        val rnd = java.util.Random(4)
        val solo = (0 until 60).map { k -> val t = 0.3 + k * 0.25; SongNote(t, t + 0.2, 62 + rnd.nextInt(12), 3, 0) }
        // The same tune heard 7.43 s later in a mix, a few notes missed, one an octave off, one extra.
        val mix = solo.filterIndexed { k, _ -> k % 10 != 3 }.mapIndexed { k, n ->
            val t = n.start - 7.43 + 0.004 * rnd.nextGaussian()
            n.copy(start = t, end = t + 0.2, midi = if (k == 5) n.midi + 12 else n.midi)
        } + SongNote(20.0, 20.2, 50, 0, 2)
        val c = SongCompare.of(solo, mix)
        assertTrue(abs(c.offset - 7.43) < 0.01, "offset ${c.offset}")
        assertEquals(53, c.matched)
        assertEquals(54, c.matchedOctave)
        assertEquals(2.0 * 53 / (60 + 55), c.f, 1e-9)
        println(c)
    }
}
