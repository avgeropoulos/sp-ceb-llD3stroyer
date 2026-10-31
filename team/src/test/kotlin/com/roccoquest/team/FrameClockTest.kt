package com.roccoquest.team

import kotlin.random.Random
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

    /** Refresh times like a real phone gives them: on a steady beat, but each one off by a little. */
    private fun wobbly(hz: Double, frames: Int, wobbleNanos: Long, seed: Int = 7): LongArray {
        val r = Random(seed)
        return LongArray(frames) { i -> 5_000_000_000L + (i * 1e9 / hz).toLong() + r.nextLong(-wobbleNanos, wobbleNanos + 1) }
    }

    /** The steps for each refresh, after the first few (while the clock works out the screen's speed). */
    private fun stepsAfterWarmUp(times: LongArray, warmUp: Int = 12): List<Int> {
        val clock = FrameClock()
        val all = times.map { clock.steps(it) }
        return all.drop(warmUp)
    }

    @Test fun wobblyRefreshTimesAtSixtyHzGiveOneUpdateEveryTime() {
        // Up to 1 ms early or late: the old clock skipped an update and then did two (a visible hitch).
        for (wobble in listOf(2_000L, 300_000L, 1_000_000L)) {
            val steps = stepsAfterWarmUp(wobbly(60.0, 1200, wobble))
            assertTrue(steps.all { it == 1 }, "60 Hz, wobble ${wobble}ns: $steps")
        }
        // A screen that is really 59.94 Hz still gets exactly one update per refresh (no hitch every 16 s).
        val slow = stepsAfterWarmUp(wobbly(59.94, 3000, 200_000L))
        assertTrue(slow.all { it == 1 }, "59.94 Hz")
    }

    @Test fun wobblyRefreshTimesAtHigherRatesStayEven() {
        // 120 Hz: exactly every other refresh.
        val s120 = stepsAfterWarmUp(wobbly(120.0, 1200, 500_000L))
        assertTrue(s120.all { it <= 1 }, "120 Hz never doubles up")
        assertTrue(s120.zipWithNext().all { (a, b) -> a + b == 1 }, "120 Hz alternates: $s120")
        // 90 Hz: two updates every three refreshes, never two at once.
        val s90 = stepsAfterWarmUp(wobbly(90.0, 900, 400_000L))
        assertTrue(s90.all { it <= 1 }, "90 Hz never doubles up")
        assertTrue(s90.windowed(3).all { it.sum() == 2 }, "90 Hz: two out of every three: $s90")
        // 144 Hz: 5 updates every 12 refreshes.
        val s144 = stepsAfterWarmUp(wobbly(144.0, 1440, 300_000L))
        assertTrue(s144.all { it <= 1 })
        assertTrue(s144.windowed(12).all { it.sum() == 5 }, "144 Hz: five out of every twelve")
    }

    @Test fun aRefreshThePhoneMissedStillCatchesUp() {
        val clock = FrameClock()
        val period = 1_000_000_000L / 60
        var t = 0L
        repeat(30) { clock.steps(t); t += period }
        // The phone was too busy for one refresh: the next frame does both updates...
        t += period
        assertEquals(2, clock.steps(t))
        // ...and then it's back to one each.
        repeat(30) { t += period; assertEquals(1, clock.steps(t)) }
    }

    @Test fun switchingFrom120To60HzSettlesQuickly() {
        val clock = FrameClock()
        var t = 0L
        repeat(240) { t += 1_000_000_000L / 120; clock.steps(t) }
        // The phone drops the screen to 60 Hz (the app asks for 60).
        val after = ArrayList<Int>()
        repeat(240) { t += 1_000_000_000L / 60; after += clock.steps(t) }
        assertTrue(after.all { it == 1 }, "one update per refresh at 60 Hz: $after")
    }

    @Test fun oddScreenSpeedsCountRealTime() {
        // 57 Hz isn't close to a simple fraction of 60: count real time instead (still 60 updates a second).
        val clock = FrameClock()
        var updates = 0
        for (i in 0 until 570) updates += clock.steps((i * 1e9 / 57).toLong())
        assertTrue(updates in 599..602, "10 seconds at 57 Hz: $updates updates")
    }

    @Test fun resetStartsFresh() {
        val clock = FrameClock()
        var t = 0L
        repeat(20) { clock.steps(t); t += 1_000_000_000L / 60 }
        clock.reset()
        // Back after a long pause: one normal update, not a burst.
        assertEquals(1, clock.steps(t + 30_000_000_000L))
        assertEquals(1, clock.steps(t + 30_000_000_000L + 1_000_000_000L / 60))
    }
}
