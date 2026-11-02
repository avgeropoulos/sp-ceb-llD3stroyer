package com.javelinstrike.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * Draws ancient Olympia: a sandy stadium between grassy banks packed with spectators,
 * the Temple of Zeus and the wooded hill of Kronos beyond, the target, the javelins
 * and the athlete.
 */

/** The sand ends here, where the far bank rises: just past one stadion (192 m). */
private const val END_Z = 205f
/** Half the width of the sand; the grassy banks rise from here. */
private const val SAND_HALF_W = 40f
private const val BANK_TOP_X = 66f
private const val BANK_H = 9f
private const val STUCK_ANGLE = 1.2f
private const val PI_F = 3.1415927f

private const val SKY_TOP = 0xFF3F86C8.toInt()
private const val SKY_LOW = 0xFFF6DFAE.toInt()
private const val GRASS = 0xFF7E9B4E.toInt()
private const val GRASS_DARK = 0xFF6F8C44.toInt()
private const val SAND_A = 0xFFDDBB80.toInt()
private const val SAND_B = 0xFFD3AF73.toInt()
private const val SAND_LINE = 0x55704A20
private const val MARBLE = 0xFFF0EADB.toInt()
private const val MARBLE_SHADE = 0xFFCFC6B2.toInt()
private const val ROOF = 0xFFB0532F.toInt()
private const val SHADOW = 0x50000000
private const val OUTLINE = 0xFF2A1A0E.toInt()
private const val WOOD = 0xFFC08A50.toInt()
private const val BRONZE = 0xFFCC8F35.toInt()
private const val THONG = 0xFF6B3A1C.toInt()

private const val SKIN = 0xFFC98B5B.toInt()
private const val SKIN_SHADE = 0xFFB0754A.toInt()
private const val HAIR = 0xFF2B1A10.toInt()
private const val HAIR_LIGHT = 0xFF3E2818.toInt()
private const val SKIN_LIGHT = 0xFFD9A276.toInt()
private const val LAUREL_LIGHT = 0xFF8DB042.toInt()
private const val CLOTH_FOLD = 0xFFD8CDB2.toInt()
private const val OUTLINE_SOFT = 0x902A1A0E.toInt()
private const val LAUREL = 0xFF6E8F2E.toInt()
private const val CLOTH = 0xFFF3EBD6.toInt()
private const val SANDAL = 0xFF6B3E1E.toInt()

/** A spectator: where they sit and the colour of their robe. */
private class Fan(val x: Float, val y: Float, val z: Float, val robe: Int, val skin: Int)

private val ROBES = intArrayOf(
    0xFFF5EFE0.toInt(), 0xFFF5EFE0.toInt(), 0xFFE2B04A.toInt(), 0xFFB5482E.toInt(),
    0xFF3F5E9A.toInt(), 0xFF7A8F3A.toInt(), 0xFF8E3B5F.toInt(), 0xFFD9CDB0.toInt(),
)
private val SKINS = intArrayOf(0xFFC98B5B.toInt(), 0xFFB97A4C.toInt(), 0xFFDDA578.toInt(), 0xFF9A6239.toInt())

/** Everyone sitting on the banks, sorted far to near so nearer fans overlap further ones. */
private val fans: List<Fan> = run {
    val r = Random(11)
    val list = ArrayList<Fan>()
    fun fan(x: Float, y: Float, z: Float) {
        list += Fan(x, y, z, ROBES[r.nextInt(ROBES.size)], SKINS[r.nextInt(SKINS.size)])
    }
    // Side banks
    for (side in intArrayOf(-1, 1)) {
        for (row in 0 until 4) {
            val f = 0.12f + row * 0.21f
            var z = 4f + r.nextFloat() * 2f
            while (z < END_Z) {
                if (r.nextFloat() < 0.85f) {
                    fan(side * (SAND_HALF_W + (BANK_TOP_X - SAND_HALF_W) * f), BANK_H * f, z)
                }
                z += 1.6f + r.nextFloat() * 1.4f
            }
        }
    }
    // Far bank
    for (row in 0 until 4) {
        val f = 0.12f + row * 0.21f
        var x = -BANK_TOP_X + r.nextFloat()
        while (x < BANK_TOP_X) {
            if (r.nextFloat() < 0.8f) fan(x, BANK_H * f, END_Z + 15f * f)
            x += 1.5f + r.nextFloat() * 1.2f
        }
    }
    list.sortByDescending { it.z }
    list
}

/** Dark pines on the hill of Kronos, as (x, y) on its face. */
private val pines: List<Pair<Float, Float>> = run {
    val r = Random(5)
    List(70) {
        val a = r.nextFloat() * Math.PI.toFloat()
        val d = r.nextFloat()
        (-150f + cos(a) * 95f * d) to (sin(a) * 70f * d)
    }
}

internal val projXs = FloatArray(48)
internal val projYs = FloatArray(48)

/** Draws the stadium. With [javelinScene] false, leaves out the javelin event's target, javelins and athlete. */
internal fun Game.drawWorld(
    g: Gfx, c: Camera, main: Boolean, x0: Float, y0: Float, w: Float, h: Float,
    javelinScene: Boolean = true,
) {
    val horizon = c.horizon()
    // Sky, warm and hazy near the horizon
    if (horizon > y0) {
        val bands = 18
        val skyH = horizon - y0
        for (i in 0 until bands) {
            g.rect(x0, y0 + skyH * i / bands, w, skyH / bands + 1f, mixColor(SKY_TOP, SKY_LOW, i / (bands - 1f)))
        }
    }
    g.rect(x0, max(y0, horizon), w, h - max(0f, horizon - y0), GRASS)

    if (main) drawBackdrop(g, c)

    // The sand track, raked into stripes
    val nearZ = c.nearGroundZ()
    var z = -40f
    var stripe = 0
    while (z < END_Z) {
        val z1 = z + 4f
        if (z1 > nearZ) groundQuad(g, c, -SAND_HALF_W, SAND_HALF_W, max(z, nearZ), z1, if (stripe % 2 == 0) SAND_A else SAND_B)
        z = z1
        stripe++
    }
    // Faint lines every 10 m
    var d = 10
    while (d < END_Z) {
        groundLine(g, c, -SAND_HALF_W, d.toFloat(), SAND_HALF_W, d.toFloat(), 0.1f, SAND_LINE)
        d += 10
    }
    // The stone throwing line (the ancient "balbis")
    if (0.6f > nearZ) groundQuad(g, c, -3f, 3f, max(0f, nearZ), 0.6f, MARBLE)
    groundLine(g, c, -3f, 0.3f, 3f, 0.3f, 0.04f, MARBLE_SHADE)

    drawBanks(g, c)
    if (main) {
        for (f in fans) drawFan(g, c, f)
        drawMarkers(g, c)
    }

    if (javelinScene) {
        drawTarget(g, c, main)
        if (screen == Game.Screen.PLAY && throwState == Game.ThrowState.READY && hintLevel() < 2) drawPowerMarkers(g, c, main)
        for (s in stuck.sortedByDescending { it.z }) drawStuck(g, c, s)
    }

    if (javelinScene) javelin?.let { j ->
        val sp = j.speed.coerceAtLeast(0.01f)
        val ux = j.vx / sp
        val uy = j.vy / sp
        val uz = j.vz / sp
        val tx = j.x - ux * JAVELIN_LEN
        val ty = j.y - uy * JAVELIN_LEN
        val tz = j.z - uz * JAVELIN_LEN
        seg(g, c, j.x, 0f, j.z, tx, 0f, tz, 0.08f, SHADOW, 1f)
        drawShaft(g, c, j.x, j.y, j.z, tx, ty, tz)
    }

    for (p in particles) {
        if (!c.project(p.x, p.y, p.z)) continue
        val s = max(1.2f, p.size * c.ppm)
        val a = min(1f, p.life * 2f)
        g.oval(c.sx - s / 2f, c.sy - s / 3f, s, s * 0.66f, argb((a * 255).toInt(), p.color))
    }

    if (main && javelinScene && screen == Game.Screen.PLAY && flickActive()) drawFlightDots(g, c)
    if (main && javelinScene) drawAthlete(g, c)
}

/** Sun, clouds, mountains, the hill of Kronos, cypresses and the Temple of Zeus. */
private fun Game.drawBackdrop(g: Gfx, c: Camera) {
    if (c.project(260f, 260f, 1200f)) {
        g.oval(c.sx - 22f, c.sy - 22f, 44f, 44f, 0x50FFF4C8)
        g.oval(c.sx - 13f, c.sy - 13f, 26f, 26f, 0xFFFFF4D0.toInt())
    }
    for (i in 0 until 5) {
        val cx = ((i * 230f + clock * 3f) % 1300f) - 650f
        if (c.project(cx, 120f + (i % 3) * 40f, 1100f)) {
            val s = c.ppm
            // Soft layered puffs: a shaded base, then brighter tops
            g.oval(c.sx - 75f * s, c.sy - 8f * s, 150f * s, 20f * s, 0x60E8E4F0)
            g.oval(c.sx - 70f * s, c.sy - 14f * s, 140f * s, 24f * s, 0x90FFFFFF.toInt())
            g.oval(c.sx - 45f * s, c.sy - 26f * s, 70f * s, 30f * s, 0xA0FFFFFF.toInt())
            g.oval(c.sx - 5f * s, c.sy - 22f * s, 55f * s, 24f * s, 0xA0FFFFFF.toInt())
            g.oval(c.sx - 30f * s, c.sy - 24f * s, 40f * s, 12f * s, 0x60FFFFFF)
        }
    }
    // Distant mountains in the haze
    val mx = floatArrayOf(-900f, -700f, -520f, -380f, -200f, -40f, 120f, 300f, 460f, 640f, 820f, 900f, 900f, -900f)
    val my = floatArrayOf(60f, 120f, 80f, 150f, 95f, 140f, 70f, 130f, 100f, 160f, 90f, 60f, -10f, -10f)
    val mz = FloatArray(mx.size) { 700f }
    if (projectPoly(c, mx, my, mz, mx.size)) g.poly(projXs, projYs, 0xFFA9B7B4.toInt(), mx.size)
    val hx = floatArrayOf(-600f, -420f, -260f, -90f, 60f, 220f, 380f, 600f, 600f, -600f)
    val hy = floatArrayOf(25f, 45f, 30f, 40f, 22f, 48f, 28f, 35f, -5f, -5f)
    val hz = FloatArray(hx.size) { 380f }
    if (projectPoly(c, hx, hy, hz, hx.size)) g.poly(projXs, projYs, 0xFF93A578.toInt(), hx.size)

    // The hill of Kronos, covered in pines
    val n = 18
    val kx = FloatArray(n + 2)
    val ky = FloatArray(n + 2)
    val kz = FloatArray(n + 2) { 330f }
    for (i in 0..n) {
        val a = i * Math.PI.toFloat() / n
        kx[i] = -150f + cos(a) * 95f
        ky[i] = sin(a) * 70f
    }
    kx[n + 1] = -150f
    ky[n + 1] = -5f
    if (projectPoly(c, kx, ky, kz, n + 2)) g.poly(projXs, projYs, 0xFF6F8A4A.toInt(), n + 2)
    for ((px, py) in pines) {
        if (!c.project(px, py, 329f)) continue
        val s = max(1.5f, 4f * c.ppm)
        g.oval(c.sx - s / 2f, c.sy - s, s, s * 1.6f, 0xFF3E5A2C.toInt())
    }

    drawTemple(g, c, -48f, 275f)

    // Cypress trees beyond the far bank
    for (i in 0 until 14) {
        val tx = -110f + i * 17f + (i * 37 % 7)
        val tz = 240f + (i * 53 % 25)
        if (abs(tx + 48f) < 22f) continue
        if (!c.project(tx, 0f, tz)) continue
        val bx = c.sx
        val by = c.sy
        val th = (11f + (i % 3) * 3f) * c.ppm
        val tw = 2.4f * c.ppm
        g.rect(bx - tw * 0.08f, by - th * 0.15f, tw * 0.16f, th * 0.15f, 0xFF4A3420.toInt())
        g.oval(bx - tw / 2f, by - th, tw, th * 0.95f, 0xFF253D1F.toInt())
        g.oval(bx - tw * 0.4f, by - th * 0.96f, tw * 0.55f, th * 0.85f, 0xFF35552B.toInt())
    }
}

/** The Temple of Zeus: a marble front of six columns under a painted pediment. */
private fun drawTemple(g: Gfx, c: Camera, cx: Float, z: Float) {
    if (!c.project(cx, 0f, z)) return
    val s = c.ppm
    val bx = c.sx
    val by = c.sy
    fun box(x: Float, y: Float, w: Float, h: Float, color: Int) = g.rect(bx + x * s, by - (y + h) * s, w * s, h * s, color)
    val half = 15f
    // Steps
    box(-half - 1.5f, 0f, 2 * half + 3f, 0.8f, MARBLE_SHADE)
    box(-half - 1f, 0.8f, 2 * half + 2f, 0.8f, MARBLE)
    box(-half - 0.5f, 1.6f, 2 * half + 1f, 0.8f, MARBLE_SHADE)
    // Dark interior behind the columns
    box(-half + 1f, 2.4f, 2 * half - 2f, 10f, 0xFF6B5A44.toInt())
    for (i in 0 until 6) {
        val x = -half + 0.8f + i * (2 * half - 3.2f) / 5f
        box(x, 2.4f, 1.6f, 10f, MARBLE)
        box(x + 1.1f, 2.4f, 0.5f, 10f, MARBLE_SHADE)
    }
    // Entablature with a painted frieze
    box(-half - 0.3f, 12.4f, 2 * half + 0.6f, 1.4f, MARBLE)
    box(-half - 0.3f, 13.8f, 2 * half + 0.6f, 0.8f, 0xFF3D5C8C.toInt())
    for (i in 0 until 10) box(-half + i * 3f + 0.6f, 13.9f, 1.2f, 0.6f, 0xFFB5482E.toInt())
    // Pediment
    g.poly(
        floatArrayOf(bx - (half + 0.8f) * s, bx + (half + 0.8f) * s, bx),
        floatArrayOf(by - 14.6f * s, by - 14.6f * s, by - 19.5f * s),
        ROOF, 3,
    )
    g.poly(
        floatArrayOf(bx - (half - 1f) * s, bx + (half - 1f) * s, bx),
        floatArrayOf(by - 15f * s, by - 15f * s, by - 18.5f * s),
        MARBLE, 3,
    )
    // A gilded statue on the peak
    g.rect(bx - 0.4f * s, by - 21f * s, 0.8f * s, 1.6f * s, 0xFFE8B730.toInt())
}

/** The grassy banks along both sides and across the far end. */
private fun drawBanks(g: Gfx, c: Camera) {
    // Far end first
    val ex = floatArrayOf(-BANK_TOP_X, BANK_TOP_X, BANK_TOP_X, -BANK_TOP_X)
    val ey = floatArrayOf(0f, 0f, BANK_H, BANK_H)
    val ez = floatArrayOf(END_Z, END_Z, END_Z + 15f, END_Z + 15f)
    if (projectPoly(c, ex, ey, ez, 4)) g.poly(projXs, projYs, GRASS_DARK, 4)
    val nearZ = c.z + 3f
    var z1 = END_Z + 15f
    var i = 0
    while (z1 > -20f) {
        val z0 = max(z1 - 10f, -20f)
        if (z1 > nearZ) {
            val za = max(z0, nearZ)
            for (side in intArrayOf(-1, 1)) {
                val bx = floatArrayOf(side * SAND_HALF_W, side * SAND_HALF_W, side * BANK_TOP_X, side * BANK_TOP_X)
                val by = floatArrayOf(0f, 0f, BANK_H, BANK_H)
                val bz = floatArrayOf(za, z1, z1, za)
                if (projectPoly(c, bx, by, bz, 4)) g.poly(projXs, projYs, if (i % 2 == 0) GRASS else GRASS_DARK, 4)
            }
        }
        z1 = z0
        i++
    }
}

private fun Game.drawFan(g: Gfx, c: Camera, f: Fan) {
    val bounce = if (cheerT > 0f) abs(sin(clock * 9f + f.x + f.z)) * 0.5f else 0f
    if (!c.project(f.x, f.y + bounce, f.z)) return
    val s = c.ppm
    if (c.sy < -10f) return
    val bw = max(1f, 0.55f * s)
    val bh = max(1.3f, 0.8f * s)
    g.rect(c.sx - bw / 2f, c.sy - bh, bw, bh, f.robe)
    if (s > 3f) {
        val hr = 0.17f * s
        g.oval(c.sx - hr, c.sy - bh - hr * 1.8f, hr * 2f, hr * 2f, f.skin)
        if (cheerT > 0f) g.line(c.sx + bw / 2f, c.sy - bh * 0.8f, c.sx + bw / 2f + hr, c.sy - bh - hr * 2.5f, max(1f, hr * 0.7f), f.skin)
    }
}

/** Little marble posts along the left edge every 10 m, with the distance carved in. */
private fun drawMarkers(g: Gfx, c: Camera) {
    var d = 10
    while (d < END_Z) {
        val x = -SAND_HALF_W + 1.5f
        if (c.project(x, 0f, d.toFloat())) {
            val s = c.ppm
            if (s >= 1.2f) {
                val bx = c.sx
                val by = c.sy
                g.rect(bx - 0.35f * s, by - 1.2f * s, 0.7f * s, 1.2f * s, MARBLE)
                g.rect(bx + 0.15f * s, by - 1.2f * s, 0.2f * s, 1.2f * s, MARBLE_SHADE)
                if (s * 1.6f >= 8f) g.text("$d", bx, by - 1.5f * s, min(13f, s * 1.6f), 0xFF4A3420.toInt(), 1)
            }
        }
        d += 10
    }
}

private fun Game.drawTarget(g: Gfx, c: Camera, main: Boolean) {
    val t = target
    // Painted rings in black-figure pottery colours
    val rings = intArrayOf(0xFFF2E3BF.toInt(), 0xFFC1502E.toInt(), 0xFFE9B730.toInt())
    groundCircle(g, c, t.x, t.z, t.radius + 0.3f, OUTLINE)
    for (i in 0 until 3) {
        val r = t.radius * (3 - i) / 3f
        if (i > 0) groundCircle(g, c, t.x, t.z, r + 0.15f, OUTLINE)
        groundCircle(g, c, t.x, t.z, r, rings[i])
    }

    // A wooden post with a bronze shield on top, and a ribbon showing the wind
    val postH = 3.6f
    groundShadow(g, c, t.x + 0.6f, t.z, 1.2f)
    body(g, c, t.x, 0f, t.z, t.x, postH, t.z, 0.18f, 0xFF7A5230.toInt(), 1.5f)
    if (c.project(t.x, postH, t.z)) {
        val r = max(3f, 1.1f * c.ppm)
        drawShield(g, c.sx, c.sy, r)
        // Ribbon
        val flap = sin(clock * 7f) * 0.25f
        val len = 1.4f + min(2f, abs(wind) * 0.3f)
        val dir = if (wind < 0f) -1f else 1f
        val fx = floatArrayOf(t.x, t.x + dir * len, t.x)
        val fy = floatArrayOf(postH + 1.3f, postH + 1.0f + flap, postH + 0.8f)
        val fz = floatArrayOf(t.z, t.z, t.z)
        seg(g, c, t.x, postH + 0.9f, t.z, t.x, postH + 1.4f, t.z, 0.08f, 0xFF7A5230.toInt(), 1f)
        if (projectPoly(c, fx, fy, fz, 3)) g.poly(projXs, projYs, 0xFFD32F2F.toInt(), 3)
    }
    if (main && c.project(t.x, postH + 2.2f, t.z)) {
        val size = (c.ppm * 2.5f).coerceIn(10f, 16f)
        g.shadowText("${t.z.toInt()} m", c.sx, c.sy, size, Game.GOLD, 1)
    }
}

/**
 * A round hoplite shield (aspis) facing the thrower: a thick bronze rim with rivets, a
 * painted black-figure emblem on a terracotta face, and a shine across the metal.
 */
private fun drawShield(g: Gfx, sx: Float, sy: Float, r: Float) {
    g.oval(sx - r - 1f, sy - r - 1f, r * 2f + 2f, r * 2f + 2f, OUTLINE)
    g.oval(sx - r, sy - r, r * 2f, r * 2f, 0xFF8A5A1E.toInt())
    g.oval(sx - r * 0.95f, sy - r * 0.95f, r * 1.9f, r * 1.9f, BRONZE)
    g.oval(sx - r * 0.78f, sy - r * 0.78f, r * 1.56f, r * 1.56f, 0xFF7A3A1A.toInt())
    g.oval(sx - r * 0.72f, sy - r * 0.72f, r * 1.44f, r * 1.44f, 0xFFC1502E.toInt())
    if (r > 6f) {
        // Rivets around the rim
        for (k in 0 until 12) {
            val a = k * PI_F / 6f
            val rx = sx + cos(a) * r * 0.86f
            val ry = sy + sin(a) * r * 0.86f
            g.oval(rx - r * 0.05f, ry - r * 0.05f, r * 0.1f, r * 0.1f, 0xFF5C3A12.toInt())
        }
        // A black-figure emblem: a horse's head and neck, simply drawn
        g.poly(
            floatArrayOf(sx - r * 0.35f, sx + r * 0.05f, sx + r * 0.42f, sx + r * 0.3f, sx + r * 0.02f, sx - r * 0.2f),
            floatArrayOf(sy + r * 0.45f, sy - r * 0.25f, sy - r * 0.35f, sy - r * 0.12f, sy + r * 0.05f, sy + r * 0.48f),
            0xFF1E140C.toInt(), 6,
        )
        g.oval(sx + r * 0.12f, sy - r * 0.45f, r * 0.12f, r * 0.2f, 0xFF1E140C.toInt())
    } else {
        g.oval(sx - r * 0.3f, sy - r * 0.3f, r * 0.6f, r * 0.6f, OUTLINE)
    }
    // Shine
    g.oval(sx - r * 0.75f, sy - r * 0.8f, r * 0.7f, r * 0.35f, 0x50FFFFFF)
}

private fun drawStuck(g: Gfx, c: Camera, s: Stuck) {
    // The tip digs in and the javelin stands up steeply, leaning back the way it came.
    // (Drawn at its real landing angle it would point straight at the camera and vanish.)
    val len = JAVELIN_LEN * 0.85f
    val flat = hypot(s.dx, s.dz).coerceAtLeast(0.01f)
    val hx = s.dx / flat * cos(STUCK_ANGLE) * len
    val hz = s.dz / flat * cos(STUCK_ANGLE) * len
    val tx = s.x - hx
    val ty = sin(STUCK_ANGLE) * len
    val tz = s.z - hz
    seg(g, c, s.x, 0f, s.z, s.x - hx + 0.8f, 0f, s.z - hz + 0.6f, 0.08f, SHADOW, 1f)
    drawShaft(g, c, s.x, 0f, s.z, tx, ty, tz)
}

/** A wooden javelin from its tip to its tail: bronze point, leather throwing thong in the middle. */
private fun drawShaft(g: Gfx, c: Camera, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
    // A dark outline keeps it visible against the sky, the sand and the target.
    seg(g, c, x1, y1, z1, x2, y2, z2, 0.11f, OUTLINE, 2.6f)
    seg(g, c, x1, y1, z1, x2, y2, z2, 0.07f, WOOD, 1.4f)
    val a = 0.45f
    val b = 0.58f
    seg(
        g, c,
        x1 + (x2 - x1) * a, y1 + (y2 - y1) * a, z1 + (z2 - z1) * a,
        x1 + (x2 - x1) * b, y1 + (y2 - y1) * b, z1 + (z2 - z1) * b,
        0.09f, THONG, 1.6f,
    )
    val t = 0.1f
    seg(g, c, x1, y1, z1, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, 0.08f, BRONZE, 1.5f)
}

/** A Greek athlete seen from behind: bare back, white loincloth, sandals and a laurel wreath. */
private val athleteCam = Camera()

/** The athlete at their spot on the runway. */
private fun Game.drawAthlete(g: Gfx, world: Camera) {
    // Draw in the athlete's own frame: move the camera instead of every point.
    val c = athleteCam.also { it.set(world); it.z = world.z - runnerZ }
    val run = (runSpeed / RUN_TOP).coerceIn(0f, 1f)
    val stride = sin(runPhase) * run
    val bob = kotlin.math.abs(sin(runPhase)) * 0.06f * run
    val draw = drawPose()
    val throwT = sinceRelease
    // Right (throwing) hand: ready by the shoulder, pulled back, or following through.
    val ready = floatArrayOf(0.34f, 1.78f, 0.2f)
    val back = floatArrayOf(0.45f, 1.62f, -0.75f)
    val follow = floatArrayOf(0.12f, 1.25f, 0.6f)
    val hand = FloatArray(3)
    val followK = if (throwT < 0.25f) throwT / 0.25f else if (throwT < 0.7f) 1f else max(0f, 1f - (throwT - 0.7f) / 0.3f)
    for (i in 0..2) {
        val drawn = ready[i] + (back[i] - ready[i]) * draw
        hand[i] = drawn + (follow[i] - drawn) * followK
    }
    val lean = -0.12f * draw + 0.15f * followK

    // Aim guide on the sand, out to where a full-power throw would land
    if (screen == Game.Screen.PLAY && holdingJavelin()) {
        val far = max(14f, ruler[9] - runnerZ)
        var k = 2f
        while (k < far) {
            groundLine(g, c, sin(aimYaw) * k, cos(aimYaw) * k, sin(aimYaw) * (k + 0.6f), cos(aimYaw) * (k + 0.6f), 0.12f, 0x80FFFFFF.toInt())
            k += if (k < 14f) 1.5f else 4f
        }
    }
    // Shadow on the sand
    groundShadow(g, c, 0f, 0f, 0.55f)
    // Legs: thighs, calves and sandals. Standing they're in a throwing stride; running they swing.
    val lf = 0.3f + stride * 0.45f
    val rf = -0.35f - stride * 0.45f
    val ll = max(0f, -stride) * 0.3f
    val rl = max(0f, stride) * 0.3f
    body(g, c, -0.12f, 0.95f, lean * 0.3f, -0.2f, 0.52f, lf * 0.6f, 0.18f, SKIN, 2f)
    body(g, c, -0.2f, 0.52f, lf * 0.6f, -0.22f, 0.08f + ll, lf, 0.13f, SKIN, 2f)
    body(g, c, 0.12f, 0.95f, lean * 0.3f, 0.2f, 0.52f, rf * 0.6f, 0.18f, SKIN, 2f)
    body(g, c, 0.2f, 0.52f, rf * 0.6f, 0.24f, 0.08f + rl, rf, 0.13f, SKIN, 2f)
    body(g, c, -0.22f, 0.04f + ll, lf - 0.06f, -0.22f, 0.04f + ll, lf + 0.14f, 0.12f, SANDAL, 2f)
    body(g, c, 0.24f, 0.04f + rl, rf - 0.06f, 0.24f, 0.04f + rl, rf + 0.13f, 0.12f, SANDAL, 2f)
    if (run < 0.1f) {
        for (h in floatArrayOf(0.14f, 0.24f)) {
            seg(g, c, -0.29f, h, 0.3f, -0.15f, h, 0.3f, 0.025f, SANDAL, 1f)
            seg(g, c, 0.17f, h, -0.34f, 0.31f, h, -0.34f, 0.025f, SANDAL, 1f)
        }
    }
    if (bob > 0f) c.y -= bob
    // A broad, tapering back with shoulder blades and the line of the spine
    val bx = floatArrayOf(-0.15f, 0.15f, 0.25f, -0.25f)
    val by = floatArrayOf(1.0f, 1.0f, 1.47f, 1.47f)
    val bz = floatArrayOf(lean * 0.4f, lean * 0.4f, lean, lean)
    if (projectPoly(c, bx, by, bz, 4)) {
        g.poly(projXs, projYs, SKIN, 4)
        outlinePoly(g, 4, OUTLINE_SOFT)
    }
    seg(g, c, 0f, 1.05f, lean * 0.4f, 0f, 1.42f, lean, 0.035f, SKIN_SHADE, 1f)
    for (side in intArrayOf(-1, 1)) {
        if (c.project(side * 0.11f, 1.33f, lean)) {
            val r = 0.07f * c.ppm
            g.oval(c.sx - r, c.sy - r * 0.8f, r * 2f, r * 1.6f, SKIN_SHADE)
            g.oval(c.sx - r * 0.75f, c.sy - r * 0.75f, r * 1.4f, r * 1.2f, SKIN_LIGHT)
        }
    }
    // The loincloth, tied with a red band, with a couple of folds
    val lx = floatArrayOf(-0.16f, 0.16f, 0.13f, -0.13f)
    val ly = floatArrayOf(1.03f, 1.03f, 0.78f, 0.8f)
    val lz = floatArrayOf(lean * 0.35f, lean * 0.35f, lean * 0.2f, lean * 0.2f)
    if (projectPoly(c, lx, ly, lz, 4)) {
        g.poly(projXs, projYs, CLOTH, 4)
        outlinePoly(g, 4, OUTLINE_SOFT)
    }
    seg(g, c, -0.05f, 0.98f, lean * 0.3f, -0.07f, 0.82f, lean * 0.2f, 0.015f, CLOTH_FOLD, 1f)
    seg(g, c, 0.06f, 0.98f, lean * 0.3f, 0.05f, 0.81f, lean * 0.2f, 0.015f, CLOTH_FOLD, 1f)
    seg(g, c, -0.16f, 1.02f, lean * 0.35f, 0.16f, 1.02f, lean * 0.35f, 0.05f, 0xFFB5482E.toInt(), 1.2f)
    // Left arm points at the target
    body(g, c, -0.22f, 1.45f, lean, -0.34f, 1.42f, 0.3f + lean, 0.11f, SKIN, 1.6f)
    body(g, c, -0.34f, 1.42f, 0.3f + lean, -0.28f, 1.5f, 0.6f + lean, 0.09f, SKIN, 1.5f)
    // Shoulders and neck
    for (side in intArrayOf(-1, 1)) {
        if (c.project(side * 0.22f, 1.45f, lean)) {
            val r = 0.085f * c.ppm
            g.oval(c.sx - r - 0.6f, c.sy - r - 0.6f, r * 2f + 1.2f, r * 2f + 1.2f, OUTLINE_SOFT)
            g.oval(c.sx - r, c.sy - r, r * 2f, r * 2f, SKIN)
            g.oval(c.sx - r * 0.7f, c.sy - r * 0.8f, r * 1.1f, r * 0.9f, SKIN_LIGHT)
        }
    }
    body(g, c, 0f, 1.47f, lean, 0f, 1.6f, lean, 0.11f, SKIN, 1.5f)
    drawHead(g, c, 0f, 1.7f, lean, wreath = true)
    // Throwing arm
    val ex = (0.21f + hand[0]) / 2f + 0.1f
    val ey = (1.45f + hand[1]) / 2f - 0.1f
    val ez = (lean + hand[2]) / 2f
    body(g, c, 0.22f, 1.45f, lean, ex, ey, ez, 0.11f, SKIN, 1.6f)
    body(g, c, ex, ey, ez, hand[0], hand[1], hand[2], 0.09f, SKIN, 1.5f)

    if (holdingJavelin()) {
        val el = aimElev - 0.15f + 0.15f * draw
        val dx = sin(aimYaw) * cos(el)
        val dy = sin(el)
        val dz = cos(aimYaw) * cos(el)
        drawShaft(
            g, c,
            hand[0] + dx * 1.5f, hand[1] + dy * 1.5f, hand[2] + dz * 1.5f,
            hand[0] - dx * 1.1f, hand[1] - dy * 1.1f, hand[2] - dz * 1.1f,
        )
        // Hand over the grip
        if (c.project(hand[0], hand[1], hand[2])) {
            val r = 0.07f * c.ppm
            g.oval(c.sx - r, c.sy - r, r * 2f, r * 2f, SKIN)
        }
    }
}

/**
 * Markers on the sand showing where 25%, 50%, 75% and 100% power would land at the current
 * aim and angle, and (in gold) where the last throw's power would. Wind still pushes it sideways.
 */
private fun Game.drawPowerMarkers(g: Gfx, c: Camera, main: Boolean) {
    for (i in 5 downTo 1) {
        val x = ruler[i * 2]
        val z = ruler[i * 2 + 1]
        if (z <= 0f) continue
        val last = i == 5
        val color = if (last) Game.GOLD else 0xE0FFFFFF.toInt()
        groundCircle(g, c, x, z, if (last) 1.2f else 0.9f, OUTLINE)
        groundCircle(g, c, x, z, if (last) 0.95f else 0.7f, color)
        seg(g, c, x, 0f, z, x, 1.6f, z, 0.08f, color, 1f)
        if (c.project(x, 2.1f, z)) {
            val label = if (last) "LAST" else "${i * 25}%"
            g.shadowText(label, c.sx, c.sy, if (main) (c.ppm * 1.4f).coerceIn(7f, 11f) else 8f, color, 1)
        }
    }
}

private val dotX = FloatArray(80)
private val dotY = FloatArray(80)
private val dotZ = FloatArray(80)

/** The dotted arc of the throw being pulled back (touch controls), out in the stadium. */
private fun Game.drawFlightDots(g: Gfx, c: Camera) {
    val n = flightPath(flickPower(), dotX, dotY, dotZ, 0.1f)
    val shown = max(2, (n * arcShown()).toInt())
    for (i in 1 until min(n, shown)) {
        if (!c.project(dotX[i], dotY[i], dotZ[i])) continue
        val r = (0.18f * c.ppm).coerceIn(1.2f, 5f)
        g.oval(c.sx - r, c.sy - r, r * 2f, r * 2f, 0xE0FFFFFF.toInt())
    }
}

/** The back of a head with curly hair, ears and (for the player) a laurel wreath. */
internal fun drawHead(g: Gfx, c: Camera, x: Float, y: Float, z: Float, wreath: Boolean, hair: Int = HAIR, skin: Int = SKIN) {
    if (!c.project(x, y, z)) return
    val r = 0.12f * c.ppm
    val cx = c.sx
    val cy = c.sy
    // Ears
    for (side in intArrayOf(-1, 1)) {
        g.oval(cx + side * r * 0.95f - r * 0.22f, cy - r * 0.15f, r * 0.44f, r * 0.6f, skin)
    }
    g.oval(cx - r - 0.8f, cy - r * 1.1f - 0.8f, r * 2f + 1.6f, r * 2.2f + 1.6f, OUTLINE)
    g.oval(cx - r, cy - r * 1.1f, r * 2f, r * 2.2f, hair)
    // Curls
    if (r > 3f) {
        for (k in 0 until 7) {
            val a = 0.3f + k * 0.42f
            val px = cx + cos(a) * r * 0.62f
            val py = cy - sin(a) * r * 0.7f - r * 0.1f
            g.oval(px - r * 0.2f, py - r * 0.2f, r * 0.4f, r * 0.4f, HAIR_LIGHT)
        }
    }
    if (wreath) {
        g.oval(cx - r * 1.08f, cy - r * 0.6f, r * 2.16f, r * 0.42f, LAUREL)
        for (k in -3..3) {
            val lx = cx + k * r * 0.3f
            g.oval(lx - r * 0.17f, cy - r * 0.78f + abs(k) * r * 0.05f, r * 0.34f, r * 0.24f, LAUREL_LIGHT)
        }
    }
}

/** A soft shadow on the ground under a figure. */
internal fun groundShadow(g: Gfx, c: Camera, x: Float, z: Float, r: Float) {
    if (!c.project(x, 0.01f, z)) return
    val w = r * c.ppm
    g.oval(c.sx - w, c.sy - w * 0.28f, w * 2f, w * 0.56f, 0x38000000)
    g.oval(c.sx - w * 0.7f, c.sy - w * 0.2f, w * 1.4f, w * 0.4f, 0x30000000)
}

/**
 * A rounded limb: a dark outline, the limb itself, and a soft highlight along one side, so
 * bodies look solid instead of flat.
 */
internal fun body(
    g: Gfx, c: Camera,
    x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
    widthM: Float, color: Int, minPx: Float,
) {
    if (!c.project(x1, y1, z1)) return
    val ax = c.sx
    val ay = c.sy
    val p1 = c.ppm
    if (!c.project(x2, y2, z2)) return
    val w = max(minPx, widthM * (p1 + c.ppm) / 2f)
    g.line(ax, ay, c.sx, c.sy, w + 1.6f, mixColor(color, 0xFF000000.toInt(), 0.55f))
    g.line(ax, ay, c.sx, c.sy, w, color)
    if (w > 3f) {
        val o = w * 0.2f
        g.line(ax - o, ay - o * 0.3f, c.sx - o, c.sy - o * 0.3f, w * 0.32f, mixColor(color, 0xFFFFFFFF.toInt(), 0.22f))
    }
}

/** Traces the edge of the last projected polygon. */
private fun outlinePoly(g: Gfx, n: Int, color: Int) {
    for (i in 0 until n) {
        val j = (i + 1) % n
        g.line(projXs[i], projYs[i], projXs[j], projYs[j], 1.2f, color)
    }
}

// ---------------------------------------------------------------- projection helpers

/** A line between two world points, at least [minPx] screen pixels wide. */
internal fun seg(
    g: Gfx, c: Camera,
    x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
    widthM: Float, color: Int, minPx: Float,
) {
    if (!c.project(x1, y1, z1)) return
    val ax = c.sx
    val ay = c.sy
    val p1 = c.ppm
    if (!c.project(x2, y2, z2)) return
    g.line(ax, ay, c.sx, c.sy, max(minPx, widthM * (p1 + c.ppm) / 2f), color)
}

/** A line painted on the ground, clipped where it would pass behind the camera. */
internal fun groundLine(g: Gfx, c: Camera, x1: Float, z1: Float, x2: Float, z2: Float, widthM: Float, color: Int) {
    val near = c.nearGroundZ()
    var ax = x1
    var az = z1
    var bx = x2
    var bz = z2
    if (az < near && bz < near) return
    if (az < near) { val f = (near - az) / (bz - az); ax += (bx - ax) * f; az = near }
    if (bz < near) { val f = (near - bz) / (az - bz); bx += (ax - bx) * f; bz = near }
    // Long lines are drawn in pieces so they get thinner with distance.
    val pieces = max(1, (hypot(bx - ax, bz - az) / 6f).toInt())
    for (i in 0 until pieces) {
        val f0 = i.toFloat() / pieces
        val f1 = (i + 1f) / pieces
        seg(g, c, ax + (bx - ax) * f0, 0f, az + (bz - az) * f0, ax + (bx - ax) * f1, 0f, az + (bz - az) * f1, widthM, color, 0.8f)
    }
}

internal fun groundQuad(g: Gfx, c: Camera, xa: Float, xb: Float, za: Float, zb: Float, color: Int) {
    val qx = floatArrayOf(xa, xb, xb, xa)
    val qy = floatArrayOf(0f, 0f, 0f, 0f)
    val qz = floatArrayOf(za, za, zb, zb)
    if (projectPoly(c, qx, qy, qz, 4)) g.poly(projXs, projYs, color, 4)
}

internal fun groundCircle(g: Gfx, c: Camera, cx: Float, cz: Float, r: Float, color: Int) {
    val n = 28
    val px = FloatArray(n)
    val py = FloatArray(n)
    val pz = FloatArray(n)
    for (i in 0 until n) {
        val a = i * 2f * Math.PI.toFloat() / n
        px[i] = cx + cos(a) * r
        pz[i] = cz + sin(a) * r
        py[i] = 0.01f
    }
    if (projectPoly(c, px, py, pz, n)) g.poly(projXs, projYs, color, n)
}

/** Projects a polygon into [projXs]/[projYs]; false if any corner is behind the camera. */
internal fun projectPoly(c: Camera, px: FloatArray, py: FloatArray, pz: FloatArray, n: Int): Boolean {
    for (i in 0 until n) {
        if (!c.project(px[i], py[i], pz[i])) return false
        projXs[i] = c.sx
        projYs[i] = c.sy
    }
    return true
}

/** Colours for one athlete; rivals wear differently coloured loincloths and bands. */
internal class Look(val skin: Int = SKIN, val cloth: Int = CLOTH, val band: Int = 0xFFB5482E.toInt(), val wreath: Boolean = true)

internal val PLAYER_LOOK = Look()

/**
 * An athlete at ([ox], [oy], [oz]) facing [yaw] (0 = down the field), seen in any pose:
 * [stride] swings the legs (-1..1) for running, [armSwing] the arms, [crouch] bends them for
 * a jump, and [discus] holds the right arm out with a discus in the hand.
 */
internal fun drawFigure(
    g: Gfx, c: Camera, ox: Float, oy: Float, oz: Float, yaw: Float,
    stride: Float, armSwing: Float, look: Look = PLAYER_LOOK,
    crouch: Float = 0f, discus: Boolean = false, lean: Float = 0.1f,
) {
    val cy = cos(yaw)
    val sy = sin(yaw)
    fun limb(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, w: Float, color: Int, minPx: Float) {
        body(
            g, c,
            ox + x1 * cy + z1 * sy, oy + y1, oz - x1 * sy + z1 * cy,
            ox + x2 * cy + z2 * sy, oy + y2, oz - x2 * sy + z2 * cy,
            w, color, minPx,
        )
    }
    groundShadow(g, c, ox, oz, 0.5f)
    val drop = crouch * 0.25f
    val hipY = 0.95f - drop
    for (side in intArrayOf(-1, 1)) {
        val swing = stride * side
        val kneeZ = swing * 0.35f + crouch * 0.25f
        val footZ = swing * 0.55f
        val lift = max(0f, -swing) * 0.35f
        limb(side * 0.12f, hipY, 0f, side * 0.15f, 0.5f - drop * 0.5f, kneeZ, 0.16f, look.skin, 1.8f)
        limb(side * 0.15f, 0.5f - drop * 0.5f, kneeZ, side * 0.16f, 0.06f + lift, footZ, 0.13f, look.skin, 1.6f)
        limb(side * 0.16f, 0.05f + lift, footZ - 0.08f, side * 0.16f, 0.05f + lift, footZ + 0.12f, 0.12f, SANDAL, 1.5f)
    }
    val tz = lean
    limb(0f, hipY + 0.05f, 0f, 0f, hipY + 0.47f, tz, 0.36f, look.skin, 3f)
    limb(-0.1f, hipY + 0.42f, tz, 0.1f, hipY + 0.42f, tz, 0.16f, look.skin, 2f)
    limb(-0.13f, hipY - 0.03f, 0f, 0.13f, hipY - 0.03f, 0f, 0.25f, look.cloth, 2f)
    limb(-0.14f, hipY + 0.07f, 0f, 0.14f, hipY + 0.07f, 0f, 0.06f, look.band, 1f)
    val shY = hipY + 0.5f
    limb(-0.21f, shY, tz, 0.21f, shY, tz, 0.14f, look.skin, 2f)
    for (side in intArrayOf(-1, 1)) {
        if (discus && side == 1) {
            limb(0.21f, shY, tz, 0.75f, shY - 0.1f, tz - 0.25f, 0.09f, look.skin, 1.5f)
            if (c.project(ox + 0.8f * cy + (tz - 0.27f) * sy, oy + shY - 0.1f, oz - 0.8f * sy + (tz - 0.27f) * cy)) {
                val r = max(2f, 0.13f * c.ppm)
                g.oval(c.sx - r, c.sy - r * 0.6f, r * 2f, r * 1.2f, OUTLINE)
                g.oval(c.sx - r * 0.85f, c.sy - r * 0.5f, r * 1.7f, r, BRONZE)
            }
            continue
        }
        val swing = -armSwing * side
        val handY = shY - 0.42f + crouch * 0.2f
        limb(side * 0.21f, shY, tz, side * 0.26f, shY - 0.25f, tz + swing * 0.2f, 0.09f, look.skin, 1.5f)
        limb(side * 0.26f, shY - 0.25f, tz + swing * 0.2f, side * 0.24f, handY, tz + swing * 0.45f, 0.08f, look.skin, 1.4f)
    }
    limb(0f, shY + 0.03f, tz, 0f, shY + 0.13f, tz, 0.1f, look.skin, 1.5f)
    val hx = ox + tz * sy
    val hz = oz + tz * cy
    drawHead(g, c, hx, oy + shY + 0.23f, hz, look.wreath, skin = look.skin)
}
