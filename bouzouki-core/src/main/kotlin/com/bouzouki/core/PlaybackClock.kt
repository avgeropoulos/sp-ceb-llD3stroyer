package com.bouzouki.core

import kotlin.math.abs
import kotlin.math.min

/**
 * The playback position between a player's reports, smooth enough to draw at every frame.
 *
 * Players report their time coarsely: YouTube's getCurrentTime() moves in steps inside a
 * WebView and the page polls it on a jittery timer, and MediaPlayer's position moves per
 * audio buffer. So the clock runs on its own, as `time + elapsed × rate` from the last anchor,
 * and each report only nudges it: small differences are slewed away over a few reports, big
 * ones (a seek) jump at once, and pausing or buffering freezes it. A report that repeats the
 * previous value is stale and ignored, and if the value stops changing altogether while
 * "playing" (a stall), the clock stops shortly after it.
 *
 * Times are in seconds of media; instants are nanoseconds on one monotonic clock
 * (SystemClock.elapsedRealtimeNanos() on Android). Not thread-safe: use it from one thread.
 */
class PlaybackClock {
    /** Whether the media is advancing. */
    var playing = false
        private set
    /** Media seconds per real second. */
    var rate = 1.0
        private set

    private var base = 0.0     // media time at baseNanos
    private var baseNanos = 0L
    private var lastValue = Double.NaN
    private var freshNanos = 0L // when the reported value last changed
    private var lastOut = 0.0

    /**
     * A report from the player, taken at [nanos]. Returns true when the clock jumped instead of
     * gliding (it started, stopped, or was moved by a seek), so followers can resynchronise.
     */
    fun report(time: Double, isPlaying: Boolean, rate: Double, nanos: Long): Boolean {
        val fresh = time != lastValue
        lastValue = time
        if (fresh) freshNanos = nanos
        if (!isPlaying) {
            val jumped = playing || abs(time - base) > 1e-6
            playing = false
            anchor(time, nanos)
            return jumped
        }
        val r = if (rate > 0.0) rate else 1.0
        if (!playing) {
            playing = true
            this.rate = r
            anchor(time, nanos)
            freshNanos = nanos
            return true
        }
        if (r != this.rate) { anchor(rawAt(nanos), nanos); this.rate = r }
        if (!fresh) return false
        val err = time - rawAt(nanos)
        if (abs(err) > JUMP) { anchor(time, nanos); return true }
        // Glide: the anchor takes part of the difference; now() keeps it from stepping back.
        base += err * if (abs(err) > SLEW_FAST_ABOVE) GAIN_FAST else GAIN
        return false
    }

    /** Moves the clock to [time] at once (a seek the app asked for), keeping it playing or not. */
    fun jump(time: Double, nanos: Long) {
        anchor(time, nanos)
        freshNanos = nanos
        lastValue = Double.NaN
    }

    /**
     * The media time at [nanos]. While playing it moves forward smoothly and never steps back
     * by less than a jump, so a small correction holds it still for a moment instead.
     */
    fun now(nanos: Long): Double {
        if (!playing) return base
        var t = rawAt(min(nanos, freshNanos + STALL_NANOS))
        if (t < lastOut && lastOut - t < JUMP) t = lastOut
        lastOut = t
        return t
    }

    private fun anchor(time: Double, nanos: Long) {
        base = time
        baseNanos = nanos
        lastOut = time
    }

    private fun rawAt(nanos: Long) = base + (nanos - baseNanos) / 1e9 * rate

    companion object {
        /** Differences bigger than this (seconds) are seeks: jump instead of gliding. */
        const val JUMP = 0.3
        private const val SLEW_FAST_ABOVE = 0.08
        private const val GAIN = 0.15
        private const val GAIN_FAST = 0.5
        /** With no new value for this long while playing, the player has stalled. */
        private const val STALL_NANOS = 1_500_000_000L
    }
}
