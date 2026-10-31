package com.roccoquest.team

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * A critically damped spring (like Unity's SmoothDamp): [pos] glides to a target, speeding up and
 * slowing down smoothly, and never wobbles back and forth. Stable for any frame time.
 */
class Spring(var pos: Float = 0f, var vel: Float = 0f) {
    /** Moves one frame towards [target], taking about [smoothTime] seconds to get most of the way there. */
    fun step(target: Float, smoothTime: Float, dt: Float, maxSpeed: Float = Float.MAX_VALUE) {
        if (dt <= 0f) return
        val omega = 2f / smoothTime
        val x = omega * dt
        val decay = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
        val maxChange = maxSpeed * smoothTime
        val change = (pos - target).coerceIn(-maxChange, maxChange)
        val temp = (vel + omega * change) * dt
        vel = (vel - omega * temp) * decay
        pos = pos - change + (change + temp) * decay
    }

    fun set(p: Float) {
        pos = p
        vel = 0f
    }
}

/**
 * The level camera. It glides after the leader instead of being glued to him, looks a little ahead
 * the way he's running, and never lets him leave the screen.
 *
 * Moving the camera exactly with the hero (the old way) jolted the whole picture whenever he bumped
 * into a wall, and big jumps (SWAP to the Pokemon, falling back after a pit, a boss arena locking)
 * slid over at one flat speed that started and stopped with a clunk. Now the camera follows a spring,
 * and any sudden jump of where it wants to be goes into [jumpOffset], which fades away on a slower
 * spring, so even those glide over smoothly.
 */
class LevelCamera {
    /** The left edge of the screen in the level. */
    val x get() = follow.pos
    /** The look-ahead, in pixels: positive while running right, negative while running left. */
    var lead = 0f
        private set

    private val follow = Spring()
    private val jumpOffset = Spring()
    private var lastWant = 0f
    private var lastHeroCx = 0f

    /**
     * Where the camera wants to be: the leader a bit behind the middle, with room to see ahead, or the
     * boss arena while it's locked. Never past the ends of the level.
     */
    private fun want(heroCx: Float, viewW: Float, maxX: Float, lock: Float?) =
        (lock ?: (heroCx - viewW / 2 + lead)).coerceIn(0f, max(0f, maxX))

    /** Jumps straight there (starting a level, a bonus room...): no gliding. */
    fun snap(heroX: Float, heroW: Float, facing: Int, viewW: Float, maxX: Float, lock: Float?) {
        val cx = heroX + heroW / 2
        lead = (if (facing < 0) -LEAD else LEAD) * viewW
        lastHeroCx = cx
        lastWant = want(cx, viewW, maxX, lock)
        cutTo(lastWant, heroX, heroW, viewW, maxX)
    }

    fun update(dt: Float, heroX: Float, heroW: Float, viewW: Float, maxX: Float, lock: Float?) {
        if (dt <= 0f) return
        val cx = heroX + heroW / 2
        // Look ahead the way the leader is really going. It only shifts while he moves, so turning
        // around on the spot (or pushing against a wall) doesn't swing the picture about.
        val moved = cx - lastHeroCx
        lastHeroCx = cx
        val most = LEAD * viewW
        if (abs(moved) < TELEPORT) lead = approach(lead, sign(moved) * most, abs(moved) * LEAD_RATE)
        lead = lead.coerceIn(-most, most)
        val want = want(cx, viewW, maxX, lock)
        val jump = want - lastWant
        lastWant = want
        if (abs(jump) > JUMP) {
            if (heroX < follow.pos || heroX + heroW > follow.pos + viewW) {
                // He's off the screen (a far teleport): a clean cut is nicer than a long whoosh.
                cutTo(want, heroX, heroW, viewW, maxX)
                return
            }
            // Keep aiming at the same spot for now, then let the difference fade.
            jumpOffset.pos -= jump
        }
        jumpOffset.step(0f, GLIDE, dt)
        val before = follow.pos
        follow.step(want + jumpOffset.pos, FOLLOW, dt, MAX_SPEED)
        keepOnScreen(heroX, heroW, viewW, maxX, before, dt)
    }

    private fun cutTo(want: Float, heroX: Float, heroW: Float, viewW: Float, maxX: Float) {
        jumpOffset.set(0f)
        follow.set(want)
        keepOnScreen(heroX, heroW, viewW, maxX, want, 0f)
    }

    /**
     * All of the leader always stays on the screen, and the screen never goes past the ends of the
     * level. (Normally this has nothing to do: the camera keeps him near the middle by itself. In a
     * boss arena he can walk right up to its walls, which are the screen's edges.)
     */
    private fun keepOnScreen(heroX: Float, heroW: Float, viewW: Float, maxX: Float, before: Float, dt: Float) {
        var p = follow.pos
        if (p < heroX + heroW - viewW) p = heroX + heroW - viewW
        if (p > heroX) p = heroX
        p = p.coerceIn(0f, max(0f, maxX))
        if (p != follow.pos) {
            // Pushed: carry on from where it really went, so the spring doesn't fight the push.
            follow.pos = p
            follow.vel = if (dt > 0f) (p - before) / dt else 0f
        }
    }

    companion object {
        /** How far ahead the camera looks, as a part of the screen width. */
        const val LEAD = 0.14f
        /** How fast the look-ahead shifts: this many pixels for each pixel the leader moves. */
        const val LEAD_RATE = 0.75f
        /** Seconds the camera takes to catch up with the leader (it trails a little while running). */
        const val FOLLOW = 0.15f
        /** Seconds a sudden jump (SWAP, a pit, a boss arena) takes to glide away. */
        const val GLIDE = 0.3f
        /** The camera never goes faster than this (pixels per second); the screen edges push it if needed. */
        const val MAX_SPEED = 900f
        /** Where the camera wants to be moving more than this in one frame is a jump, not running. */
        const val JUMP = 16f
        /** The leader moving more than this in one frame was a teleport: it doesn't count for look-ahead. */
        const val TELEPORT = 12f
    }
}
