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

/** The sand track ends here, where the far bank rises. */
private const val END_Z = 160f
/** Half the width of the sand; the grassy banks rise from here. */
private const val SAND_HALF_W = 40f
private const val BANK_TOP_X = 66f
private const val BANK_H = 9f
private const val STUCK_ANGLE = 1.2f

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

private val xs = FloatArray(48)
private val ys = FloatArray(48)

internal fun Game.drawWorld(g: Gfx, c: Camera, main: Boolean, x0: Float, y0: Float, w: Float, h: Float) {
    val horizon = c.horizon()
    // Sky, warm and hazy near the horizon
    if (horizon > y0) {
        val bands = 10
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

    drawTarget(g, c, main)

    for (s in stuck.sortedByDescending { it.z }) drawStuck(g, c, s)

    javelin?.let { j ->
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

    if (main) drawAthlete(g, c)
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
            g.oval(c.sx - 70f * s, c.sy - 12f * s, 140f * s, 24f * s, 0xB0FFFFFF.toInt())
            g.oval(c.sx - 35f * s, c.sy - 22f * s, 80f * s, 30f * s, 0xB0FFFFFF.toInt())
        }
    }
    // Distant mountains in the haze
    val mx = floatArrayOf(-900f, -700f, -520f, -380f, -200f, -40f, 120f, 300f, 460f, 640f, 820f, 900f, 900f, -900f)
    val my = floatArrayOf(60f, 120f, 80f, 150f, 95f, 140f, 70f, 130f, 100f, 160f, 90f, 60f, -10f, -10f)
    val mz = FloatArray(mx.size) { 700f }
    if (projectPoly(c, mx, my, mz, mx.size)) g.poly(xs, ys, 0xFFA9B7B4.toInt(), mx.size)
    val hx = floatArrayOf(-600f, -420f, -260f, -90f, 60f, 220f, 380f, 600f, 600f, -600f)
    val hy = floatArrayOf(25f, 45f, 30f, 40f, 22f, 48f, 28f, 35f, -5f, -5f)
    val hz = FloatArray(hx.size) { 380f }
    if (projectPoly(c, hx, hy, hz, hx.size)) g.poly(xs, ys, 0xFF93A578.toInt(), hx.size)

    // The hill of Kronos, covered in pines
    val n = 18
    val kx = FloatArray(n + 2)
    val ky = FloatArray(n + 2)
    val kz = FloatArray(n + 2) { 290f }
    for (i in 0..n) {
        val a = i * Math.PI.toFloat() / n
        kx[i] = -150f + cos(a) * 95f
        ky[i] = sin(a) * 70f
    }
    kx[n + 1] = -150f
    ky[n + 1] = -5f
    if (projectPoly(c, kx, ky, kz, n + 2)) g.poly(xs, ys, 0xFF6F8A4A.toInt(), n + 2)
    for ((px, py) in pines) {
        if (!c.project(px, py, 289f)) continue
        val s = max(1.5f, 4f * c.ppm)
        g.oval(c.sx - s / 2f, c.sy - s, s, s * 1.6f, 0xFF3E5A2C.toInt())
    }

    drawTemple(g, c, -48f, 230f)

    // Cypress trees beyond the far bank
    for (i in 0 until 14) {
        val tx = -110f + i * 17f + (i * 37 % 7)
        val tz = 195f + (i * 53 % 25)
        if (abs(tx + 48f) < 22f) continue
        if (!c.project(tx, 0f, tz)) continue
        val bx = c.sx
        val by = c.sy
        val th = (11f + (i % 3) * 3f) * c.ppm
        val tw = 2.4f * c.ppm
        g.oval(bx - tw / 2f, by - th, tw, th, 0xFF2F4A26.toInt())
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
    if (projectPoly(c, ex, ey, ez, 4)) g.poly(xs, ys, GRASS_DARK, 4)
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
                if (projectPoly(c, bx, by, bz, 4)) g.poly(xs, ys, if (i % 2 == 0) GRASS else GRASS_DARK, 4)
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
    seg(g, c, t.x, 0f, t.z, t.x, postH, t.z, 0.18f, 0xFF7A5230.toInt(), 1.5f)
    if (c.project(t.x, postH, t.z)) {
        val r = max(3f, 1.1f * c.ppm)
        val sx = c.sx
        val sy = c.sy
        g.oval(sx - r, sy - r, r * 2f, r * 2f, 0xFF4A2E10.toInt())
        g.oval(sx - r * 0.86f, sy - r * 0.86f, r * 1.72f, r * 1.72f, BRONZE)
        g.oval(sx - r * 0.6f, sy - r * 0.6f, r * 1.2f, r * 1.2f, 0xFFE2AE52.toInt())
        g.oval(sx - r * 0.32f, sy - r * 0.32f, r * 0.64f, r * 0.64f, OUTLINE)
        // Ribbon
        val flap = sin(clock * 7f) * 0.25f
        val len = 1.4f + min(2f, abs(wind) * 0.3f)
        val dir = if (wind < 0f) -1f else 1f
        val fx = floatArrayOf(t.x, t.x + dir * len, t.x)
        val fy = floatArrayOf(postH + 1.3f, postH + 1.0f + flap, postH + 0.8f)
        val fz = floatArrayOf(t.z, t.z, t.z)
        seg(g, c, t.x, postH + 0.9f, t.z, t.x, postH + 1.4f, t.z, 0.08f, 0xFF7A5230.toInt(), 1f)
        if (projectPoly(c, fx, fy, fz, 3)) g.poly(xs, ys, 0xFFD32F2F.toInt(), 3)
    }
    if (main && c.project(t.x, postH + 2.2f, t.z)) {
        val size = (c.ppm * 2.5f).coerceIn(10f, 16f)
        g.shadowText("${t.z.toInt()} m", c.sx, c.sy, size, Game.GOLD, 1)
    }
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
private fun Game.drawAthlete(g: Gfx, c: Camera) {
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

    // Legs and sandals
    seg(g, c, -0.12f, 0.95f, lean * 0.3f, -0.2f, 0.5f, 0.2f, 0.17f, SKIN, 2f)
    seg(g, c, -0.2f, 0.5f, 0.2f, -0.22f, 0.06f, 0.3f, 0.14f, SKIN, 2f)
    seg(g, c, 0.12f, 0.95f, lean * 0.3f, 0.2f, 0.5f, -0.2f, 0.17f, SKIN, 2f)
    seg(g, c, 0.2f, 0.5f, -0.2f, 0.24f, 0.06f, -0.35f, 0.14f, SKIN, 2f)
    seg(g, c, -0.22f, 0.04f, 0.26f, -0.22f, 0.04f, 0.44f, 0.13f, SANDAL, 2f)
    seg(g, c, 0.24f, 0.04f, -0.39f, 0.24f, 0.04f, -0.22f, 0.13f, SANDAL, 2f)
    seg(g, c, -0.21f, 0.2f, 0.29f, -0.23f, 0.2f, 0.33f, 0.15f, SANDAL, 1.5f)
    seg(g, c, 0.23f, 0.2f, -0.3f, 0.25f, 0.2f, -0.34f, 0.15f, SANDAL, 1.5f)
    // Bare back, then the loincloth
    seg(g, c, 0f, 1.0f, lean * 0.4f, 0f, 1.42f, lean, 0.38f, SKIN, 3f)
    seg(g, c, 0.05f, 1.05f, lean * 0.4f, 0.03f, 1.38f, lean, 0.08f, SKIN_SHADE, 1f)
    seg(g, c, -0.13f, 0.92f, lean * 0.3f, 0.13f, 0.92f, lean * 0.3f, 0.26f, CLOTH, 2f)
    seg(g, c, -0.14f, 1.02f, lean * 0.35f, 0.14f, 1.02f, lean * 0.35f, 0.06f, 0xFFB5482E.toInt(), 1f)
    // Left arm points at the target
    seg(g, c, -0.2f, 1.45f, lean, -0.34f, 1.42f, 0.3f + lean, 0.1f, SKIN, 1.5f)
    seg(g, c, -0.34f, 1.42f, 0.3f + lean, -0.28f, 1.5f, 0.6f + lean, 0.09f, SKIN, 1.5f)
    // Shoulders, neck and head with its laurel wreath
    seg(g, c, -0.21f, 1.45f, lean, 0.21f, 1.45f, lean, 0.14f, SKIN, 2f)
    seg(g, c, 0f, 1.48f, lean, 0f, 1.58f, lean, 0.1f, SKIN, 1.5f)
    if (c.project(0f, 1.68f, lean)) {
        val r = 0.12f * c.ppm
        g.oval(c.sx - r, c.sy - r * 1.1f, r * 2f, r * 2.2f, HAIR)
        g.oval(c.sx - r * 1.05f, c.sy - r * 0.55f, r * 2.1f, r * 0.45f, LAUREL)
        for (k in -2..2) g.oval(c.sx + k * r * 0.4f - r * 0.18f, c.sy - r * 0.75f, r * 0.36f, r * 0.3f, 0xFF86A93A.toInt())
    }
    // Throwing arm
    val ex = (0.21f + hand[0]) / 2f + 0.1f
    val ey = (1.45f + hand[1]) / 2f - 0.1f
    val ez = (lean + hand[2]) / 2f
    seg(g, c, 0.21f, 1.45f, lean, ex, ey, ez, 0.1f, SKIN, 1.5f)
    seg(g, c, ex, ey, ez, hand[0], hand[1], hand[2], 0.09f, SKIN, 1.5f)

    if (holdingJavelin()) {
        val el = 0.3f + 0.25f * draw
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
        // Aim guide on the sand
        if (screen == Game.Screen.PLAY) {
            var k = 2f
            while (k < 14f) {
                groundLine(g, c, sin(aimYaw) * k, cos(aimYaw) * k, sin(aimYaw) * (k + 0.6f), cos(aimYaw) * (k + 0.6f), 0.12f, 0xA0FFFFFF.toInt())
                k += 1.5f
            }
        }
    }
}

// ---------------------------------------------------------------- projection helpers

/** A line between two world points, at least [minPx] screen pixels wide. */
private fun seg(
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
private fun groundLine(g: Gfx, c: Camera, x1: Float, z1: Float, x2: Float, z2: Float, widthM: Float, color: Int) {
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

private fun groundQuad(g: Gfx, c: Camera, xa: Float, xb: Float, za: Float, zb: Float, color: Int) {
    val qx = floatArrayOf(xa, xb, xb, xa)
    val qy = floatArrayOf(0f, 0f, 0f, 0f)
    val qz = floatArrayOf(za, za, zb, zb)
    if (projectPoly(c, qx, qy, qz, 4)) g.poly(xs, ys, color, 4)
}

private fun groundCircle(g: Gfx, c: Camera, cx: Float, cz: Float, r: Float, color: Int) {
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
    if (projectPoly(c, px, py, pz, n)) g.poly(xs, ys, color, n)
}

/** Projects a polygon into [xs]/[ys]; false if any corner is behind the camera. */
private fun projectPoly(c: Camera, px: FloatArray, py: FloatArray, pz: FloatArray, n: Int): Boolean {
    for (i in 0 until n) {
        if (!c.project(px[i], py[i], pz[i])) return false
        xs[i] = c.sx
        ys[i] = c.sy
    }
    return true
}
