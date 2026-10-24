package com.javelinstrike.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/* Draws the stadium, the field, the target, the javelins and the athlete. */

private const val WALL_Z = 150f
private const val FIELD_HALF_W = 260f
/** Half-angle of the javelin landing sector (about 14.5°). */
private const val SECTOR = 0.253f
private const val STUCK_ANGLE = 1.2f

private const val SKY_TOP = 0xFF2E73C9.toInt()
private const val SKY_LOW = 0xFFA9D8FF.toInt()
private const val GRASS_A = 0xFF3E9A3A.toInt()
private const val GRASS_B = 0xFF48A843.toInt()
private const val RUNWAY = 0xFFC9683E.toInt()
private const val LINE = 0xE6FFFFFF.toInt()
private const val STANDS = 0xFF3B4658.toInt()
private const val STANDS_TOP = 0xFF2A3342.toInt()
private const val SHAFT = 0xFFEDEDED.toInt()
private const val SHAFT_DARK = 0xFF9A9A9A.toInt()
private const val CORD = 0xFFD8342C.toInt()
private const val SHADOW = 0x50000000
private const val OUTLINE = 0xFF263238.toInt()

private const val SKIN = 0xFFE0A47A.toInt()
private const val HAIR = 0xFF3A2414.toInt()
private const val SINGLET = 0xFF1E5BD8.toInt()
private const val SHORTS = 0xFF1B1F3A.toInt()
private const val SHOE = 0xFFF2F2F2.toInt()

/** The crowd in the stands: x position, row and colour of each fan, made once. */
private val crowd: List<Triple<Float, Int, Int>> = run {
    val r = Random(7)
    val colors = intArrayOf(
        0xFFE53935.toInt(), 0xFFFFEB3B.toInt(), 0xFF42A5F5.toInt(), 0xFFFFFFFF.toInt(),
        0xFF66BB6A.toInt(), 0xFFFF9800.toInt(), 0xFFAB47BC.toInt(), 0xFFF5D0A9.toInt(),
    )
    buildList {
        for (row in 0 until 6) {
            var x = -150f + r.nextFloat() * 2f
            while (x < 150f) {
                add(Triple(x, row, colors[r.nextInt(colors.size)]))
                x += 1.6f + r.nextFloat() * 1.8f
            }
        }
    }
}

private val xs = FloatArray(40)
private val ys = FloatArray(40)

internal fun Game.drawWorld(g: Gfx, c: Camera, main: Boolean, x0: Float, y0: Float, w: Float, h: Float) {
    val horizon = c.horizon()
    // Sky
    if (horizon > y0) {
        val bands = 8
        val skyH = horizon - y0
        for (i in 0 until bands) {
            g.rect(x0, y0 + skyH * i / bands, w, skyH / bands + 1f, mixColor(SKY_TOP, SKY_LOW, i / (bands - 1f)))
        }
    }
    g.rect(x0, max(y0, horizon), w, h - max(0f, horizon - y0), GRASS_A)

    if (main) drawStadium(g, c)

    // Mowing stripes
    val nearZ = c.nearGroundZ()
    var z = -40f
    var stripe = 0
    while (z < WALL_Z) {
        val z1 = z + 5f
        if (z1 > nearZ && stripe % 2 == 0) groundQuad(g, c, -FIELD_HALF_W, FIELD_HALF_W, max(z, nearZ), z1, GRASS_B)
        z = z1
        stripe++
    }

    // Runway, foul line and the landing sector
    if (0f > nearZ) groundQuad(g, c, -2f, 2f, max(-40f, nearZ), 0f, RUNWAY)
    groundLine(g, c, -2f, 0f, 2f, 0f, 0.12f, LINE)
    val sectorLen = WALL_Z - 5f
    groundLine(g, c, 0f, 0f, sin(SECTOR) * sectorLen, cos(SECTOR) * sectorLen, 0.1f, LINE)
    groundLine(g, c, 0f, 0f, -sin(SECTOR) * sectorLen, cos(SECTOR) * sectorLen, 0.1f, LINE)

    // Distance lines every 10 m
    var d = 10
    while (d <= 130) {
        val half = tan(SECTOR) * d
        groundLine(g, c, -half, d.toFloat(), half, d.toFloat(), 0.08f, 0xA0FFFFFF.toInt())
        if (main && c.project(-half - 1.5f, 0f, d.toFloat()) && c.ppm * 1.8f >= 6f) {
            g.text("$d", c.sx, c.sy, min(13f, c.ppm * 1.8f), 0xD0FFFFFF.toInt(), 2)
        }
        d += 10
    }

    drawTarget(g, c, main)

    // Javelins stuck in the ground, far ones first
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
        g.rect(c.sx - s / 2f, c.sy - s / 2f, s, s, argb((a * 255).toInt(), p.color))
    }

    if (main) drawAthlete(g, c)
}

private fun Game.drawStadium(g: Gfx, c: Camera) {
    // Stands: a sloped bank of seats at the far end of the field.
    val bx = floatArrayOf(-FIELD_HALF_W, FIELD_HALF_W, FIELD_HALF_W, -FIELD_HALF_W)
    val by = floatArrayOf(0f, 0f, 14f, 14f)
    val bz = floatArrayOf(WALL_Z, WALL_Z, WALL_Z + 12f, WALL_Z + 12f)
    if (projectPoly(c, bx, by, bz, 4)) g.poly(xs, ys, STANDS, 4)
    val topY = floatArrayOf(14f, 14f, 17f, 17f)
    val topZ = floatArrayOf(WALL_Z + 12f, WALL_Z + 12f, WALL_Z + 12f, WALL_Z + 12f)
    if (projectPoly(c, bx, topY, topZ, 4)) g.poly(xs, ys, STANDS_TOP, 4)
    // Advertising boards along the front.
    val ay = floatArrayOf(0f, 0f, 1.2f, 1.2f)
    val az = floatArrayOf(WALL_Z - 1f, WALL_Z - 1f, WALL_Z - 1f, WALL_Z - 1f)
    if (projectPoly(c, bx, ay, az, 4)) g.poly(xs, ys, 0xFF1565C0.toInt(), 4)

    // The crowd; they jump up and down when you hit the target.
    for ((x, row, color) in crowd) {
        val bounce = if (cheerT > 0f) abs(sin(clock * 9f + x)) * 0.8f else 0f
        val yy = 2.2f + row * 2f + bounce
        if (!c.project(x, yy, WALL_Z + row * 1.7f + 0.5f)) continue
        val s = max(1f, 1.1f * c.ppm)
        g.rect(c.sx - s / 2f, c.sy - s, s, s * 1.3f, color)
    }

    // Floodlights
    for (side in intArrayOf(-1, 1)) {
        val lx = side * 90f
        seg(g, c, lx, 0f, WALL_Z + 14f, lx, 34f, WALL_Z + 14f, 0.8f, 0xFF59606C.toInt(), 1f)
        if (c.project(lx, 35f, WALL_Z + 14f)) {
            val lw = 9f * c.ppm
            val lh = 4f * c.ppm
            g.rect(c.sx - lw / 2f, c.sy - lh / 2f, lw, lh, 0xFF59606C.toInt())
            g.rect(c.sx - lw / 2f + 1f, c.sy - lh / 2f + 1f, lw - 2f, lh - 2f, 0xFFFFF6C8.toInt())
        }
    }
}

private fun Game.drawTarget(g: Gfx, c: Camera, main: Boolean) {
    val t = target
    val rings = intArrayOf(0xFFFFFFFF.toInt(), 0xFFE53935.toInt(), 0xFFFFD54F.toInt())
    groundCircle(g, c, t.x, t.z, t.radius + 0.25f, 0xFF20304A.toInt())
    for (i in 0 until 3) groundCircle(g, c, t.x, t.z, t.radius * (3 - i) / 3f, rings[i])
    groundCircle(g, c, t.x, t.z, t.radius / 10f, 0xFFE53935.toInt())

    // A flag on a pole shows where the target is and which way the wind blows.
    val poleH = 5f
    seg(g, c, t.x, 0f, t.z, t.x, poleH, t.z, 0.12f, 0xFFF5F5F5.toInt(), 1f)
    val flap = sin(clock * 6f) * 0.3f
    val len = 1.6f + min(2f, abs(wind) * 0.25f)
    val dir = if (wind < 0f) -1f else 1f
    val fx = floatArrayOf(t.x, t.x + dir * len, t.x)
    val fy = floatArrayOf(poleH, poleH - 0.6f + flap, poleH - 1.2f)
    val fz = floatArrayOf(t.z, t.z, t.z)
    if (projectPoly(c, fx, fy, fz, 3)) g.poly(xs, ys, 0xFFFF3D00.toInt(), 3)
    if (main && c.project(t.x, poleH + 1.2f, t.z)) {
        val size = (c.ppm * 2.5f).coerceIn(9f, 16f)
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

/** A javelin from its tip to its tail, with a red cord grip near the middle. */
private fun drawShaft(g: Gfx, c: Camera, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
    // A dark outline keeps it visible against the sky, the lines and the target.
    seg(g, c, x1, y1, z1, x2, y2, z2, 0.11f, OUTLINE, 2.6f)
    seg(g, c, x1, y1, z1, x2, y2, z2, 0.07f, SHAFT, 1.4f)
    val a = 0.45f
    val b = 0.6f
    seg(
        g, c,
        x1 + (x2 - x1) * a, y1 + (y2 - y1) * a, z1 + (z2 - z1) * a,
        x1 + (x2 - x1) * b, y1 + (y2 - y1) * b, z1 + (z2 - z1) * b,
        0.09f, CORD, 1.6f,
    )
    val t = 0.08f
    seg(g, c, x1, y1, z1, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, 0.06f, SHAFT_DARK, 1.2f)
}

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

    // Legs
    seg(g, c, -0.12f, 0.95f, lean * 0.3f, -0.2f, 0.5f, 0.2f, 0.17f, SKIN, 2f)
    seg(g, c, -0.2f, 0.5f, 0.2f, -0.22f, 0.06f, 0.3f, 0.14f, SKIN, 2f)
    seg(g, c, 0.12f, 0.95f, lean * 0.3f, 0.2f, 0.5f, -0.2f, 0.17f, SKIN, 2f)
    seg(g, c, 0.2f, 0.5f, -0.2f, 0.24f, 0.06f, -0.35f, 0.14f, SKIN, 2f)
    seg(g, c, -0.22f, 0.04f, 0.26f, -0.22f, 0.04f, 0.44f, 0.14f, SHOE, 2f)
    seg(g, c, 0.24f, 0.04f, -0.39f, 0.24f, 0.04f, -0.22f, 0.14f, SHOE, 2f)
    // Shorts and singlet
    seg(g, c, -0.1f, 0.95f, lean * 0.3f, 0.1f, 0.95f, lean * 0.3f, 0.3f, SHORTS, 2f)
    seg(g, c, 0f, 1.02f, lean * 0.4f, 0f, 1.42f, lean, 0.38f, SINGLET, 3f)
    // Left arm points at the target
    seg(g, c, -0.2f, 1.45f, lean, -0.34f, 1.42f, 0.3f + lean, 0.1f, SKIN, 1.5f)
    seg(g, c, -0.34f, 1.42f, 0.3f + lean, -0.28f, 1.5f, 0.6f + lean, 0.09f, SKIN, 1.5f)
    // Shoulders, neck and head
    seg(g, c, -0.21f, 1.45f, lean, 0.21f, 1.45f, lean, 0.14f, SINGLET, 2f)
    seg(g, c, 0f, 1.48f, lean, 0f, 1.58f, lean, 0.1f, SKIN, 1.5f)
    if (c.project(0f, 1.68f, lean)) {
        val r = 0.12f * c.ppm
        g.oval(c.sx - r, c.sy - r * 1.1f, r * 2f, r * 2.2f, HAIR)
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
        // Aim guide on the ground
        if (screen == Game.Screen.PLAY) {
            var k = 2f
            while (k < 14f) {
                groundLine(g, c, sin(aimYaw) * k, cos(aimYaw) * k, sin(aimYaw) * (k + 0.6f), cos(aimYaw) * (k + 0.6f), 0.12f, 0x90FFFFFF.toInt())
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

/** A line painted on the grass, clipped where it would pass behind the camera. */
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
