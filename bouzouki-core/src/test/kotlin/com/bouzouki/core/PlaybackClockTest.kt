package com.bouzouki.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackClockTest {
    private fun ns(seconds: Double) = (seconds * 1e9).toLong()

    /**
     * Plays [seconds] of media from real time 0 at [rate]: the player's value only updates every
     * [step] s, it is polled every 20 ms ± 6 ms, and each poll is reported. Returns the largest
     * error and the smallest/largest frame-to-frame advance of the clock drawn at 60 fps,
     * after the first [settle] seconds.
     */
    private fun simulate(clock: PlaybackClock, seconds: Double, step: Double, rate: Double = 1.0, settle: Double = 1.5): Triple<Double, Double, Double> {
        val rnd = java.util.Random(4)
        var nextPoll = 0.0
        var frame = 0.0
        var prev = Double.NaN
        var maxErr = 0.0
        var minStep = Double.MAX_VALUE
        var maxStep = 0.0
        val frameDt = 1.0 / 60
        while (frame < seconds) {
            while (nextPoll <= frame) {
                val truth = nextPoll * rate
                clock.report(floor(truth / step) * step, true, rate, ns(nextPoll))
                nextPoll += 0.020 + (rnd.nextDouble() - 0.5) * 0.012
            }
            val t = clock.now(ns(frame))
            if (frame > settle) {
                maxErr = maxOf(maxErr, abs(t - frame * rate))
                if (!prev.isNaN()) { minStep = minOf(minStep, t - prev); maxStep = maxOf(maxStep, t - prev) }
            }
            prev = t
            frame += frameDt
        }
        return Triple(maxErr, minStep, maxStep)
    }

    @Test
    fun glidesBetweenCoarseReports() {
        for (step in listOf(0.05, 0.1, 0.25)) {
            val (err, minStep, maxStep) = simulate(PlaybackClock(), 20.0, step)
            assertTrue(err < 0.035, "step $step: error $err s")
            // Every frame moves on by about a frame (16.7 ms): no stalls, no leaps.
            assertTrue(minStep > 0.010 && maxStep < 0.024, "step $step: frame advance $minStep..$maxStep")
        }
    }

    @Test
    fun followsTheRate() {
        val (err, minStep, maxStep) = simulate(PlaybackClock(), 20.0, 0.25, rate = 0.5)
        assertTrue(err < 0.035, "error $err")
        assertTrue(minStep > 0.005 && maxStep < 0.012, "frame advance $minStep..$maxStep")
    }

    @Test
    fun jumpsOnSeeksAndFreezesWhenPaused() {
        val c = PlaybackClock()
        assertTrue(c.report(10.0, true, 1.0, ns(0.0)))
        assertEquals(10.5, c.now(ns(0.5)), 1e-9)
        // A seek reported by the player: far from where the clock is.
        assertTrue(c.report(42.0, true, 1.0, ns(0.6)))
        assertEquals(42.1, c.now(ns(0.7)), 1e-9)
        // Paused (or buffering): stays put, at the player's time.
        assertTrue(c.report(42.2, false, 1.0, ns(0.8)))
        assertEquals(42.2, c.now(ns(5.0)), 1e-9)
        assertTrue(!c.playing)
        // Playing again starts from there.
        assertTrue(c.report(42.2, true, 1.0, ns(6.0)))
        assertEquals(42.7, c.now(ns(6.5)), 1e-9)
        // A seek the app asked for moves it at once, and it waits there for the player.
        c.jump(5.0, ns(7.0))
        assertEquals(5.0, c.now(ns(7.25)), 1e-9)
        assertTrue(c.playing && c.holding)
    }

    @Test
    fun holdsAtASeekUntilThePlayerIsThere() {
        val c = PlaybackClock()
        c.report(50.0, true, 1.0, 0)
        c.jump(10.0, ns(1.0))
        // The player echoes the target while it loads: the clock waits instead of running ahead.
        assertTrue(!c.report(10.0, true, 1.0, ns(1.1)))
        assertTrue(!c.report(10.0, true, 1.0, ns(1.6)))
        assertEquals(10.0, c.now(ns(1.8)), 1e-9)
        assertTrue(c.holding)
        // It moves on from the target: the clock runs from there, with no second jump.
        assertTrue(!c.report(10.05, true, 1.0, ns(1.9)))
        assertTrue(!c.holding)
        assertEquals(10.15, c.now(ns(2.0)), 1e-9)

        // A first value already past the target: the player is playing on, no need to wait.
        c.jump(15.0, ns(2.5))
        assertTrue(!c.report(15.2, true, 1.0, ns(2.6)))
        assertTrue(!c.holding)
        assertEquals(15.3, c.now(ns(2.7)), 1e-9)

        // Buffering at the target: the seek has landed, and the clock stops there.
        c.jump(20.0, ns(3.0))
        assertTrue(c.report(20.0, false, 1.0, ns(3.2)))
        assertTrue(!c.holding && !c.playing)
        assertEquals(20.0, c.now(ns(4.0)), 1e-9)
        // Playing again starts from there.
        assertTrue(c.report(20.0, true, 1.0, ns(4.5)))
        assertEquals(20.5, c.now(ns(5.0)), 1e-9)

        // A seek while paused lands without a jump.
        val p = PlaybackClock()
        p.report(5.0, false, 1.0, 0)
        p.jump(8.0, ns(1.0))
        assertEquals(8.0, p.now(ns(1.0)), 1e-9)
        assertTrue(!p.report(8.0, false, 1.0, ns(1.2)))
        assertTrue(!p.holding)

        // The player went somewhere else than asked: a real jump.
        c.jump(30.0, ns(6.0))
        assertTrue(c.report(42.0, true, 1.0, ns(6.1)))
        assertEquals(42.1, c.now(ns(6.2)), 1e-9)
    }

    @Test
    fun smallCorrectionsGlideAndNeverStepBack() {
        val c = PlaybackClock()
        c.report(0.0, true, 1.0, 0)
        c.report(0.5, true, 1.0, ns(0.5))
        assertEquals(1.0, c.now(ns(1.0)), 1e-9)
        // The player says it's 50 ms behind: the clock holds still rather than going back.
        assertTrue(!c.report(0.95, true, 1.0, ns(1.0)))
        val t = c.now(ns(1.0))
        assertEquals(1.0, t, 1e-9)
        val later = c.now(ns(1.1))
        assertTrue(later > t && later < 1.1, "glided to $later")
        // A repeat of a stale value is ignored.
        assertTrue(!c.report(0.95, true, 1.0, ns(1.2)))
        assertTrue(c.now(ns(1.2)) > later)
    }

    @Test
    fun stopsWhenThePlayerStalls() {
        val c = PlaybackClock()
        c.report(3.0, true, 1.0, 0)
        for (i in 1..30) c.report(3.0, true, 1.0, ns(i * 0.1)) // "playing", but stuck
        val t = c.now(ns(3.0))
        // Before it has seen the player's step, it allows 2 × 0.25 s + 0.1 s.
        assertTrue(t <= 3.6 + 1e-9, "ran on to $t")
        // It picks up from the player's time when the value moves again.
        c.report(3.05, true, 1.0, ns(3.1))
        assertEquals(3.05, c.now(ns(3.1)), 1e-9)
    }

    @Test
    fun stallsStopSoonForAPlayerWithFineSteps() {
        // A player whose value moves every 0.1 s (MediaPlayer, polled by the app), then sticks.
        val c = PlaybackClock()
        for (i in 0..30) c.report(i * 0.1, true, 1.0, ns(i * 0.1))
        for (i in 31..60) c.report(3.0, true, 1.0, ns(i * 0.1))
        val t = c.now(ns(6.0))
        assertTrue(t in 3.0..3.31, "ran on to $t") // 2 × 0.1 s + 0.1 s past the last value
        // In normal play it never waits between the values.
        val (_, minStep, _) = simulate(PlaybackClock(), 10.0, 0.1)
        assertTrue(minStep > 0.010, "frame advance $minStep")
    }

    @Test
    fun rateChangesDoNotJump() {
        val c = PlaybackClock()
        for (i in 0..20) c.report(i * 0.1, true, 1.0, ns(i * 0.1))
        val before = c.now(ns(2.0))
        assertTrue(!c.report(2.0, true, 0.5, ns(2.0)))
        assertEquals(before, c.now(ns(2.0)), 1e-9)
        c.report(2.25, true, 0.5, ns(2.5))
        assertEquals(before + 0.375, c.now(ns(2.75)), 0.01)
    }
}
