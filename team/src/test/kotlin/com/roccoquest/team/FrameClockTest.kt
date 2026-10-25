package com.roccoquest.team

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameClockTest {
    @Test fun sixtyUpdatesPerSecondAtCommonRefreshRates() {
        for (hz in listOf(60, 90, 120, 144)) {
            val clock = FrameClock()
            assertEquals(1, clock.steps(1_000_000_000L))
            var updates = 0
            for (i in 1..hz * 10) updates += clock.steps(1_000_000_000L + i * 1_000_000_000L / hz)
            assertEquals(600, updates, "$hz Hz")
        }
    }

    @Test fun sixtyHzNeverAlternatesBetweenSkippedAndDoubleUpdates() {
        val clock = FrameClock()
        for (i in 0..600) assertEquals(1, clock.steps(i * 1_000_000_000L / 60))
    }

    @Test fun stalledFrameHasBoundedCatchupAndNoBacklog() {
        val clock = FrameClock()
        clock.steps(0)
        assertEquals(3, clock.steps(2_000_000_000L))
        assertTrue(clock.steps(2_016_666_667L) <= 1)
    }
}
