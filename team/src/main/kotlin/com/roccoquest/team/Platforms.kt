package com.roccoquest.team

import com.roccoquest.game.Body
import com.roccoquest.game.Gfx
import kotlin.math.abs
import kotlin.math.cos

/**
 * A MOVING PLATFORM (level chars '-' goes side to side across a gap, '\' is a lift that goes up and down).
 * You land on it from above (like a cloud) and it carries you along. Each level keeps its platforms in
 * [Level.platforms] so [Level.moveY] can land things on them.
 */
class MovingPlatform(x: Float, y: Float, val vertical: Boolean) : Thing(x, y, W, 8f) {
    companion object {
        const val W = 48f
        /** How far a side-to-side platform travels (a platform gap is W + RANGE wide: 8 tiles). */
        const val RANGE = 80f
        const val LIFT = 72f
    }

    /** Where it starts (a side-to-side one goes from here to [startX] + [RANGE] and back). */
    val startX = x
    private val x0 = x
    private val y0 = y
    private var t = 0f
    private val period = if (vertical) 4f else 4.5f
    /** Which way it's going (for the arrows). */
    var dir = 1
        private set

    init { active = true }

    /** True if [b] is standing on top of it. */
    fun carries(b: Body) = b.onGround && abs(b.bottom - y) < 2.5f && b.x + b.w > x + 1f && b.x < x + w - 1f

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        val k = (1f - cos(t * 2f * Math.PI.toFloat() / period)) / 2f
        val nx = if (vertical) x0 else x0 + RANGE * k
        val ny = if (vertical) y0 - LIFT * k else y0
        val dx = nx - x
        val dy = ny - y
        if (abs(dx) > 0.001f) dir = if (dx > 0) 1 else -1 else if (abs(dy) > 0.001f) dir = if (dy > 0) 1 else -1
        // Carry whoever is standing on it.
        for (h in g.allHeroes) if (carries(h)) { g.level.moveX(h, dx); h.y += dy }
        for (f in g.foes) if (!f.dying && carries(f)) { g.level.moveX(f, dx); f.y += dy }
        x = nx
        y = ny
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        gfx.rect(px, y, w, h, 0xFF2A2A34.toInt())
        gfx.rect(px + 1, y + 1, w - 2, h - 3, 0xFFE8A82A.toInt())
        for (k in 0 until 4) gfx.rect(px + 4 + k * 12f, y + 1, 5f, h - 3, 0xFF2A2A34.toInt())
        gfx.rect(px + 1, y + 1, w - 2, 1.5f, 0x88FFFFFF.toInt())
        // Little arrows show which way it's moving.
        val c = 0xFFFFF2C8.toInt()
        if (vertical) {
            val ay = if (dir < 0) y - 6f else y + h + 2f
            gfx.poly(floatArrayOf(px + w / 2 - 4, px + w / 2 + 4, px + w / 2), floatArrayOf(ay + (if (dir < 0) 4f else 0f), ay + (if (dir < 0) 4f else 0f), ay + (if (dir < 0) 0f else 4f)), c)
        } else {
            val ax = if (dir > 0) px + w + 2f else px - 6f
            gfx.poly(floatArrayOf(ax + (if (dir > 0) 0f else 4f), ax + (if (dir > 0) 0f else 4f), ax + (if (dir > 0) 4f else 0f)), floatArrayOf(y, y + h, y + h / 2), c)
        }
    }
}
