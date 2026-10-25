package com.roccoquest.team

/** Fixed 60 Hz simulation driven by vsync time, independent of callback scheduling jitter. */
class FrameClock {
    private var last: Long? = null
    private var units = 0L

    fun steps(frameTimeNanos: Long): Int {
        val previous = last
        last = frameTimeNanos
        if (previous == null) return 1
        // Units are nanoseconds * 60; this avoids accumulating a rounded 16,666,667 ns period.
        units += (frameTimeNanos - previous).coerceIn(0L, 100_000_000L) * 60L
        var count = 0
        // Vsync timestamps round to whole nanoseconds. Keep the tiny remainder, including debt.
        while (units >= 1_000_000_000L - 60L && count < 3) {
            units -= 1_000_000_000L
            count++
        }
        if (count == 3) units = units.coerceAtMost(0L)
        return count
    }
}
