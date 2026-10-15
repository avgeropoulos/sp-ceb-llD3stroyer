package com.bouzouki.core

import kotlin.math.abs
import kotlin.math.max
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
 * "playing" (a stall), the clock stops a little after the player's usual step.
 *
 * A seek the app asks for ([jump]) holds the clock at the target until the player shows it is
 * there: paused or buffering at it, or playing on from it. Until then nothing runs ahead of the
 * player (see [holding]).
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
    /**
     * True from a [jump] until the player has got there: the time stays at the target, and
     * whatever plays along (the bouzouki sound) should wait rather than run ahead.
     */
    var holding = false
        private set

    private var base = 0.0     // media time at baseNanos
    private var baseNanos = 0L
    private var lastValue = Double.NaN
    private var freshNanos = 0L // when the reported value last changed
    private var gapFrom = -1L   // the fresh value before, for learning the player's step; -1 after a jump
    private var step = FIRST_STEP // usual seconds between fresh values
    private var holdTarget = 0.0
    private var holdSeen = Double.NaN // the first value reported while holding
    private var lastOut = 0.0

    /**
     * A report from the player, taken at [nanos]. Returns true when the clock jumped instead of
     * gliding (it started, stopped, or was moved by a seek), so followers can resynchronise.
     */
    fun report(time: Double, isPlaying: Boolean, rate: Double, nanos: Long): Boolean {
        val fresh = time != lastValue
        lastValue = time
        val r = if (rate > 0.0) rate else 1.0
        if (holding) return settle(time, isPlaying, r, nanos)
        if (!isPlaying) {
            val jumped = playing || abs(time - base) > 1e-6
            playing = false
            anchor(time, nanos)
            return jumped
        }
        if (!playing) {
            start(time, r, nanos)
            return true
        }
        if (r != this.rate) { anchor(rawAt(min(nanos, runsUntil())), nanos); this.rate = r }
        if (!fresh) return false
        if (gapFrom >= 0) learnStep(nanos - gapFrom)
        gapFrom = nanos
        freshNanos = nanos
        val err = time - rawAt(nanos)
        if (abs(err) > JUMP) { anchor(time, nanos); return true }
        // Glide: the anchor takes part of the difference; now() keeps it from stepping back.
        base += err * if (abs(err) > SLEW_FAST_ABOVE) GAIN_FAST else GAIN
        return false
    }

    /**
     * Moves the clock to [time] at once (a seek the app asked for) and holds it there until a
     * report shows the player has got there. Playing or not stays as it was.
     */
    fun jump(time: Double, nanos: Long) {
        anchor(time, nanos)
        holding = true
        holdTarget = time
        holdSeen = Double.NaN
        lastValue = Double.NaN
        gapFrom = -1L
    }

    /**
     * The media time at [nanos]. While playing it moves forward smoothly and never steps back
     * by less than a jump, so a small correction holds it still for a moment instead.
     */
    fun now(nanos: Long): Double {
        if (!playing || holding) return base
        var t = rawAt(min(nanos, runsUntil()))
        if (t < lastOut && lastOut - t < JUMP) t = lastOut
        lastOut = t
        return t
    }

    /**
     * A report while holding at a seek's target. Paused or buffering there: the seek has landed.
     * Playing: a first value at the target may be the player echoing it while it still loads, so
     * the clock waits until the value moves on, and starts from there. A value already past the
     * target (or somewhere else) needs no waiting.
     */
    private fun settle(time: Double, isPlaying: Boolean, r: Double, nanos: Long): Boolean {
        val far = abs(time - holdTarget) > JUMP // the player went somewhere else
        if (!isPlaying) {
            holding = false
            val jumped = playing || far
            playing = false
            anchor(time, nanos)
            return jumped
        }
        val maybeEcho = holdSeen.isNaN() && !far && time <= holdTarget + ECHO
        if (maybeEcho || time == holdSeen) {
            if (holdSeen.isNaN()) { holdSeen = time; base = time; lastOut = time } // show where it landed
            return false
        }
        holding = false
        val wasPlaying = playing
        start(time, r, nanos)
        // The seek itself was the jump; playing on from the target is not another one.
        return !wasPlaying || far
    }

    private fun start(time: Double, r: Double, nanos: Long) {
        playing = true
        rate = r
        anchor(time, nanos)
        freshNanos = nanos
        gapFrom = nanos
    }

    /** Learns the player's usual step from the time between two fresh values (not a stall). */
    private fun learnStep(gapNanos: Long) {
        val gap = gapNanos / 1e9
        if (gap > MAX_RUN_ON || gap > 4 * max(step, 0.05)) return
        step += (gap - step) * STEP_GAIN
    }

    /** With no fresh value since [freshNanos], the clock runs on until this instant, then waits. */
    private fun runsUntil(): Long = freshNanos + ((2 * step + 0.1).coerceIn(MIN_RUN_ON, MAX_RUN_ON) * 1e9).toLong()

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
        /** After a seek, a first value within this (seconds) past the target may be an echo of it. */
        private const val ECHO = 0.05
        /** The player's step assumed until it has been seen (YouTube in a WebView: about this). */
        private const val FIRST_STEP = 0.25
        private const val STEP_GAIN = 0.2
        /** Bounds (seconds) on how long the clock runs on without a fresh value: 2 steps + 0.1 s. */
        private const val MIN_RUN_ON = 0.2
        private const val MAX_RUN_ON = 1.5
    }
}
