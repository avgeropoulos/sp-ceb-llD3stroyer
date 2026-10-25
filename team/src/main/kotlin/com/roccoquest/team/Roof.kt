package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import kotlin.math.sin
import kotlin.random.Random

/**
 * The ROOF on top of the house: a deck with a railing that looks out over a big city. Reached by the ladder at the
 * end of the top floor (or the elevator). Backyard decorations can go up here too (rooftop pool!).
 */
object Roof {
    /** The telescope (LOOK) is on the left; decorations go between it and the ladder hatch on the right. */
    const val SCOPE_X = 40f
    const val DECOR_MIN_X = 86f
    val DECOR_MAX_X = Homes.STAIRS_X - 86f
    /** The BUILD A FLOOR sign next to the hatch (build the house taller from up here). */
    val SIGN_X = Homes.STAIRS_X - 46f

    val SIGHTS = listOf(
        "a pizza delivery bird!", "King Krag stuck in a traffic jam!", "a cat napping on a skyscraper!",
        "the stadium lights turning on!", "your garden, way down below!", "a hot air balloon race!",
        "a window washer waving at you!", "a giant donut sign!", "Dash the dino at the park!",
        "a rainbow over the river!", "the moon saying hi!", "a train zooming over the bridge!",
    )

    /** City skylines drawn once into sprites (far = misty, near = darker with lit windows). */
    private fun skyline(seed: Int, w: Int, h: Int, body: Int, lit: Int, dim: Int, minH: Int): Sprite {
        val px = IntArray(w * h)
        val r = Random(seed)
        var x = 0
        while (x < w) {
            val bw = 10 + r.nextInt(18)
            val bh = minH + r.nextInt(h - minH)
            val spire = r.nextInt(5) == 0
            for (yy in h - bh until h) for (xx in x until minOf(w, x + bw - 1)) {
                val wx = xx - x; val wy = yy - (h - bh)
                val window = wx % 4 in 1..2 && wy % 5 in 2..3 && wx in 1 until bw - 2
                px[yy * w + xx] = if (window) (if (r.nextInt(3) == 0) lit else dim) else body
            }
            if (spire) for (yy in maxOf(0, h - bh - 8) until h - bh) px[yy * w + x + bw / 2] = body
            x += bw + r.nextInt(3)
        }
        return Sprite(w, h, px)
    }

    val far by lazy { skyline(7, 480, 80, 0xFF6A6A9A.toInt(), 0xFFB8B0D8.toInt(), 0xFF7A7AAA.toInt(), 20) }
    private val near by lazy { skyline(11, 480, 110, 0xFF2A2A44.toInt(), 0xFFFFE07A.toInt(), 0xFF3A3A58.toInt(), 30) }

    /** The roof and the city, for a house with [floors] floors (the higher it is, the lower the city looks). */
    fun draw(gfx: Gfx, ox: Float, viewW: Float, viewH: Float, t: Float, floors: Int, fireworksT: Float, planet: HomePlanet = HomePlanet.EARTH) {
        drawSky(gfx, ox, viewW, viewH, t, floors, fireworksT, planet)
        if (floors < Homes.MAX_FLOORS) Homes.drawBuildSign(gfx, ox + SIGN_X, "A FLOOR")
    }

    private fun drawSky(gfx: Gfx, ox: Float, viewW: Float, viewH: Float, t: Float, floors: Int, fireworksT: Float, planet: HomePlanet) {
        val fy = Homes.FLOOR_Y
        if (planet == HomePlanet.EARTH) {
            // Sunset sky with a few early stars and the moon
            gfx.gradient(0f, 0f, viewW, fy, 0xFF2A3A8A.toInt(), 0xFFF4A878.toInt())
            for (k in 0 until 14) {
                val sx = (k * 97f + 13f) % viewW
                if ((t * 1.5f + k).toInt() % 7 != 0) gfx.rect(sx, 6f + (k * 37f) % 50f, 1.5f, 1.5f, 0xCCFFFFFF.toInt())
            }
            gfx.oval(viewW - 70f, 18f, 22f, 22f, 0xFFFFF4D0.toInt())
            gfx.oval(viewW - 64f, 15f, 20f, 20f, 0xFF3A4A94.toInt())
        } else {
            // Another planet's sky over the city.
            gfx.gradient(0f, 0f, viewW, fy, planet.skyTop, planet.skyBottom)
            planet.drawSky(gfx, viewW, fy, t)
        }
        // Fireworks (after looking through the telescope)
        if (fireworksT > 0f) for (b in 0 until 3) {
            val age = (3f - fireworksT + b * 0.4f) % 1.2f
            val bx = viewW * (0.25f + b * 0.25f); val by = 40f + b * 12f
            val c = intArrayOf(0xFFFF6A9A.toInt(), 0xFFFFE14D.toInt(), 0xFF6AE8F0.toInt())[b]
            for (k in 0 until 10) {
                val a = k * 0.628f
                gfx.rect(bx + kotlin.math.cos(a) * age * 30f, by + sin(a) * age * 30f, 2f, 2f, c)
            }
        }
        // A blimp drifting by
        val bx = ((t * 8f) % (viewW + 200f)) - 100f
        gfx.oval(bx, 30f, 50f, 16f, 0xFFE8E8F0.toInt())
        gfx.rect(bx + 18f, 44f, 14f, 5f, 0xFF8A8A9A.toInt())
        gfx.text("TEAM UP!", bx + 25f, 41f, 4.5f, 0xFFE84A4A.toInt(), 1)
        // The city: the taller the house, the further down it is
        val sink = (floors - 1) * 3f
        val farY = fy - 92f + sink
        val nearY = fy - 116f + sink * 1.6f
        var x = (ox * 0.2f) % 480f - 480f
        while (x < viewW) { gfx.sprite(far, x, farY); x += 480f }
        x = (ox * 0.45f) % 480f - 480f
        while (x < viewW) { gfx.sprite(near, x, nearY); x += 480f }
        gfx.rect(0f, nearY + 110f, viewW, fy - nearY - 110f + 1f, 0xFF22223A.toInt())
        // The roof deck
        gfx.rect(0f, fy - 6f, viewW, viewH - fy + 6f, 0xFF7A7A88.toInt())
        gfx.rect(0f, fy - 6f, viewW, 3f, 0xFF9A9AA8.toInt())
        var tx = ox % 32f
        while (tx < viewW) { gfx.rect(tx, fy - 3f, 1f, viewH, 0xFF6A6A78.toInt()); tx += 32f }
        gfx.rect(0f, fy + 30f, viewW, 1f, 0xFF6A6A78.toInt())
        // The railing along the edge
        val left = ox; val right = ox + Homes.ROOM_W
        gfx.rect(left, fy - 34f, right - left, 3f, 0xFFD8D8E0.toInt())
        gfx.rect(left, fy - 20f, right - left, 2f, 0xFFB8B8C4.toInt())
        var px = left
        while (px <= right) {
            if (px > -4f && px < viewW) gfx.rect(px, fy - 34f, 3f, 30f, 0xFFC8C8D4.toInt())
            px += 24f
        }
        drawTelescope(gfx, ox)
        // The hatch with the ladder going back down
        drawHatch(gfx, ox + Homes.STAIRS_X - 6f, fy)
    }

    fun drawTelescope(gfx: Gfx, ox: Float) {
        val fy = Homes.FLOOR_Y
        val sx = ox + SCOPE_X
        gfx.rect(sx + 8f, fy - 22f, 2f, 18f, 0xFF3A3A44.toInt())
        gfx.poly(floatArrayOf(sx + 9f, sx + 2f, sx + 16f), floatArrayOf(fy - 10f, fy - 2f, fy - 2f), 0xFF3A3A44.toInt())
        gfx.poly(floatArrayOf(sx, sx + 22f, sx + 24f, sx + 2f), floatArrayOf(fy - 24f, fy - 34f, fy - 30f, fy - 20f), 0xFF4A6AD8.toInt())
        gfx.rect(sx + 20f, fy - 35f, 5f, 6f, 0xFFF4D84A.toInt())
    }

    fun drawHatch(gfx: Gfx, x: Float, fy: Float) {
        gfx.rect(x - 2f, fy - 6f, 30f, 8f, 0xFF4A3A2A.toInt())
        gfx.rect(x, fy - 4f, 26f, 4f, 0xFF1A1420.toInt())
        gfx.rect(x + 3f, fy - 30f, 2f, 26f, 0xFFB88A4A.toInt()); gfx.rect(x + 21f, fy - 30f, 2f, 26f, 0xFFB88A4A.toInt())
        for (k in 0 until 4) gfx.rect(x + 3f, fy - 26f + k * 6f, 20f, 2f, 0xFFB88A4A.toInt())
    }

    /** The ladder up to the roof, at the end of the top floor. */
    fun drawLadderUp(gfx: Gfx, x: Float, fy: Float) {
        gfx.rect(x - 2f, 26f, 30f, 6f, 0xFF4A3A2A.toInt())
        gfx.rect(x + 3f, 30f, 2f, fy - 30f, 0xFFB88A4A.toInt()); gfx.rect(x + 21f, 30f, 2f, fy - 30f, 0xFFB88A4A.toInt())
        var y = fy - 8f
        while (y > 34f) { gfx.rect(x + 3f, y, 20f, 2f, 0xFFB88A4A.toInt()); y -= 12f }
        gfx.rect(x - 4f, 44f, 34f, 12f, 0xFF2A6AC8.toInt())
        gfx.text("ROOF", x + 13f, 53f, 6f, 0xFFFFFFFF.toInt(), 1)
    }
}
