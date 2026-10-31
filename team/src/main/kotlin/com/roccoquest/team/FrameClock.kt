package com.roccoquest.team

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Turns screen refresh times (from the Choreographer) into fixed 60 Hz game updates.
 *
 * Phones report each refresh time with a tiny wobble. Counting real time exactly (the old way) put
 * the "next update is due" moment right on top of the refreshes, so a refresh that came a hair early
 * got no update and the next one got two: a little hitch you could see while running. So once the
 * clock knows how fast the screen refreshes, it counts refreshes instead of nanoseconds: at 60 Hz
 * every refresh is exactly one update, at 120 Hz one every other refresh, at 90 Hz two every three,
 * and so on. A refresh the phone really missed still counts (two updates, so the game keeps up).
 * Screens with an odd speed fall back to counting real time.
 */
class FrameClock {
    private var started = false
    private var last = 0L
    /** Updates owed: 1.0 is one whole update. Locked to the screen, it stays on a grid of 1/[q]. */
    private var owed = 0.0

    /** The last few refresh times (oldest first), to work out how fast this screen refreshes. */
    private val times = LongArray(HISTORY + 1)
    private var timesN = 0
    private val gaps = LongArray(HISTORY)

    /** About how long one screen refresh takes, in nanoseconds (from the last few gaps). */
    private var period = 0.0
    /** While locked to the screen, each refresh is worth exactly [p] / [q] updates. */
    private var p = 0
    private var q = 0

    /** Starts over (after the app was paused), so the first frame back is one normal update. */
    fun reset() {
        started = false
        owed = 0.0
        timesN = 0
    }

    fun steps(frameTimeNanos: Long): Int {
        if (!started) {
            started = true
            last = frameTimeNanos
            return 1
        }
        val raw = frameTimeNanos - last
        last = frameTimeNanos
        // The same refresh twice (or time going backwards): nothing new to do.
        if (raw <= 0L) return 0
        val delta = raw.coerceAtMost(MAX_DELTA)
        learn(frameTimeNanos, raw >= MAX_DELTA)
        val refreshes = wholeRefreshes(delta)
        if (refreshes > 0) {
            owed += refreshes.toDouble() * p / q
            // Snap back onto the 1/q grid, so tiny rounding errors can never add up to a skipped frame.
            owed = round(owed * q) / q
        } else owed += delta / STEP_NANOS
        val count = floor(owed + SLACK).toInt().coerceIn(0, MAX_STEPS)
        owed -= count
        // After a long stall, catch up a little and then carry on from now (no backlog).
        if (count == MAX_STEPS) owed = minOf(owed, 0.0)
        return count
    }

    /** How many whole refreshes [delta] is, or 0 if it doesn't look like whole refreshes (or the rate is unknown). */
    private fun wholeRefreshes(delta: Long): Int {
        if (q == 0) return 0
        val x = delta / period
        val k = round(x)
        return if (k >= 1.0 && abs(x - k) < 0.25) k.toInt() else 0
    }

    /** Remembers a refresh time and re-checks how fast the screen refreshes. */
    private fun learn(time: Long, afterPause: Boolean) {
        // A pause says nothing about the screen: start counting again from here.
        if (afterPause) timesN = 0
        if (timesN == times.size) {
            System.arraycopy(times, 1, times, 0, times.size - 1)
            timesN--
        }
        times[timesN++] = time
        val n = timesN - 1
        if (n < MIN_SAMPLES) return
        // The middle gap is roughly one refresh (a missed refresh is a longer gap, so it can't be the middle)...
        for (i in 0 until n) gaps[i] = times[i + 1] - times[i]
        gaps.sort(0, n)
        val mid = gaps[n / 2].toDouble()
        // ...which says how many refreshes each of the latest gaps was. Their whole stretch divided by that
        // is much steadier than any one gap (each refresh's little wobble only counts at the two ends).
        // Stop at a gap that isn't whole refreshes: the screen changed speed there (like 120 to 60 Hz).
        var span = 0L
        var refreshes = 0L
        for (i in n - 1 downTo 0) {
            val gap = times[i + 1] - times[i]
            val x = gap / mid
            val k = round(x)
            if (k < 1.0 || abs(x - k) > 0.25) break
            span += gap
            refreshes += k.toLong()
        }
        if (refreshes < MIN_SAMPLES) return
        period = span.toDouble() / refreshes
        val ratio = period / STEP_NANOS
        // Still the speed we locked onto? Keep it (a little wobble mustn't flip it back and forth).
        if (q > 0 && abs(p.toDouble() / q - ratio) / ratio < KEEP_TOLERANCE) return
        // How many updates one refresh is worth, as a simple fraction like 1/1, 1/2, 2/3 or 5/12.
        var bestP = 0
        var bestQ = 0
        var bestErr = TOLERANCE
        for (qq in 1..MAX_DENOMINATOR) {
            val pp = round(ratio * qq).toInt()
            if (pp < 1) continue
            val err = abs(pp.toDouble() / qq - ratio) / ratio
            if (err < bestErr) { bestErr = err; bestP = pp; bestQ = qq }
        }
        p = bestP
        q = bestQ
        if (q > 0) owed = round(owed * q) / q
    }

    private companion object {
        const val STEP_NANOS = 1_000_000_000.0 / 60.0
        /** Longer gaps than this (app paused, a long stall) count as this long. */
        const val MAX_DELTA = 100_000_000L
        /** At most this many updates per frame, so a slow phone doesn't fall further and further behind. */
        const val MAX_STEPS = 3
        /** Counting real time: rounding up by a hair (17 microseconds) so exact 60 Hz never skips. */
        const val SLACK = 0.001
        /** Refresh gaps remembered, and how many are needed before trusting them. */
        const val HISTORY = 16
        const val MIN_SAMPLES = 4
        /** Fractions up to twelfths cover 30, 48, 50, 60, 72, 75, 90, 100, 120, 144, 165, 180 and 240 Hz. */
        const val MAX_DENOMINATOR = 12
        /** How close (1.5%) the screen has to be to one of those speeds to lock onto it... */
        const val TOLERANCE = 0.015
        /** ...and how far (3%) it may wander before the clock looks again. */
        const val KEEP_TOLERANCE = 0.03
    }
}
