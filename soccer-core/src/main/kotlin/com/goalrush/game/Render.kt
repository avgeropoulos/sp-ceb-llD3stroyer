package com.goalrush.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Draws the 3D stadium, players and ball for a [Match] with a software camera. */
class SceneRenderer {
    private val p0 = Proj()
    private val p1 = Proj()
    private val qx = FloatArray(4)
    private val qy = FloatArray(4)
    private val qz = FloatArray(4)

    private class Fan(val x: Float, val y: Float, val z: Float, val color: Int, val phase: Float)

    private val fans: List<Fan> = Random(7).let { r ->
        val colors = intArrayOf(
            0xFFE53935.toInt(), 0xFFFFFFFF.toInt(), 0xFF1E88E5.toInt(), 0xFFFFD54F.toInt(),
            0xFF43A047.toInt(), 0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFFE53935.toInt(),
            0xFFF5F5F5.toInt(), 0xFF212121.toInt(),
        )
        List(1500) {
            val s = r.nextFloat()
            Fan(
                -75f + r.nextFloat() * 150f, 1.6f + s * 13f, 9f + s * 16f,
                colors[r.nextInt(colors.size)], r.nextFloat() * 6.28f,
            )
        }
    }

    // Pentagon centres of a football: the 12 vertices of an icosahedron.
    private val spots: List<Vec3> = run {
        val t = (1f + sqrt(5f)) / 2f
        listOf(
            Vec3(-1f, t, 0f), Vec3(1f, t, 0f), Vec3(-1f, -t, 0f), Vec3(1f, -t, 0f),
            Vec3(0f, -1f, t), Vec3(0f, 1f, t), Vec3(0f, -1f, -t), Vec3(0f, 1f, -t),
            Vec3(t, 0f, -1f), Vec3(t, 0f, 1f), Vec3(-t, 0f, -1f), Vec3(-t, 0f, 1f),
        ).map { it.norm() }
    }

    var time = 0f

    fun draw(g: Gfx, m: Match, viewH: Float, celebrate: Float) {
        val cam = m.cam
        drawSky(g, m.level.sky, viewH)
        drawStand(g, cam, m.level.sky, celebrate)
        drawPitch(g, cam, m.level.sky)
        drawBoards(g, cam)
        drawShadows(g, m)

        // Depth-sorted objects, far to near.
        val items = ArrayList<Pair<Float, () -> Unit>>()
        items += depth(cam, 0f, 1.2f, 1.1f) to { drawNet(g, m) }
        items += depth(cam, 0f, 1.2f, 0f) to { drawFrame(g, m) }
        items += depth(cam, m.keeper.x, 1f, KEEPER_Z) to { drawKeeper(g, cam, m.keeper) }
        m.wall.forEachIndexed { i, w ->
            items += depth(cam, w.x, 1f, w.z) to { drawFigure(g, cam, w.body, WALL_KIT, SKINS[i % SKINS.size]) }
        }
        items += depth(cam, m.ball.pos.x, m.ball.pos.y, m.ball.pos.z) - 0.01f to {
            drawTrail(g, m)
            drawBall(g, cam, m.ball)
        }
        items.sortByDescending { it.first }
        for ((_, f) in items) f()
    }

    private fun depth(cam: Camera, x: Float, y: Float, z: Float) = cam.project(x, y, z, p0).depth

    // ---- background ----

    private fun drawSky(g: Gfx, sky: Sky, h: Float) {
        when (sky) {
            Sky.DAY -> g.gradient(0f, 0f, VIEW_W, h, 0xFF2F7BD6.toInt(), 0xFFB5DEF7.toInt())
            Sky.SUNSET -> g.gradient(0f, 0f, VIEW_W, h, 0xFF3B2A6B.toInt(), 0xFFF79B4B.toInt())
            Sky.NIGHT -> {
                g.gradient(0f, 0f, VIEW_W, h, 0xFF03061A.toInt(), 0xFF1B2850.toInt())
                val r = Random(3)
                repeat(40) {
                    val tw = 0.5f + 0.5f * sin(time * 2f + it)
                    g.circle(r.nextFloat() * VIEW_W, r.nextFloat() * h * 0.3f, 0.6f + tw * 0.5f, alpha(0xFFFFFFFF.toInt(), 0.4f + tw * 0.4f))
                }
            }
        }
    }

    private fun quad(
        g: Gfx, cam: Camera,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float, color: Int,
    ) {
        qx[0] = x0; qy[0] = y0; qz[0] = z0
        qx[1] = x1; qy[1] = y1; qz[1] = z1
        qx[2] = x2; qy[2] = y2; qz[2] = z2
        qx[3] = x3; qy[3] = y3; qz[3] = z3
        val n = cam.projectPoly(qx, qy, qz, 4)
        if (n >= 3) g.poly(cam.outX, cam.outY, n, color)
    }

    /** A flat strip on the ground from (x0, z0) to (x1, z1), [w] metres wide. */
    private fun groundLine(g: Gfx, cam: Camera, x0: Float, z0: Float, x1: Float, z1: Float, w: Float, color: Int) {
        val dx = x1 - x0; val dz = z1 - z0
        val l = sqrt(dx * dx + dz * dz)
        if (l < 1e-4f) return
        val nx = -dz / l * w / 2; val nz = dx / l * w / 2
        quad(g, cam, x0 + nx, 0.005f, z0 + nz, x1 + nx, 0.005f, z1 + nz, x1 - nx, 0.005f, z1 - nz, x0 - nx, 0.005f, z0 - nz, color)
    }

    private fun drawStand(g: Gfx, cam: Camera, sky: Sky, celebrate: Float) {
        val standColor = if (sky == Sky.NIGHT) 0xFF151B2E.toInt() else 0xFF39424E.toInt()
        quad(g, cam, -90f, 0f, 8f, 90f, 0f, 8f, 90f, 15.5f, 26f, -90f, 15.5f, 26f, standColor)
        // Tier edges.
        for (i in 1..3) {
            val s = i / 4f
            val y = 1f + s * 14.5f; val z = 8f + s * 18f
            quad(g, cam, -90f, y, z, 90f, y, z, 90f, y + 0.25f, z + 0.3f, -90f, y + 0.25f, z + 0.3f, 0xFF59636F.toInt())
        }
        // Roof.
        quad(g, cam, -90f, 15.5f, 26f, 90f, 15.5f, 26f, 90f, 19f, 22f, -90f, 19f, 22f, 0xFF262B33.toInt())
        val dim = if (sky == Sky.NIGHT) 0.75f else 1f
        for (f in fans) {
            val jump = if (celebrate > 0f) abs(sin(time * 9f + f.phase)) * 0.5f * celebrate else abs(sin(time * 1.3f + f.phase)) * 0.06f
            cam.project(f.x, f.y + jump, f.z, p0)
            if (!p0.visible || p0.x < -5f || p0.x > VIEW_W + 5f) continue
            val r = (0.22f * p0.s).coerceIn(0.6f, 4f)
            g.circle(p0.x, p0.y - r, r, if (dim < 1f) mix(f.color, 0xFF000000.toInt(), 0.25f) else f.color)
        }
        if (sky == Sky.NIGHT) {
            for (x in floatArrayOf(-45f, -15f, 15f, 45f)) {
                cam.project(x, 19.5f, 22f, p0)
                if (!p0.visible) continue
                val r = (1.2f * p0.s).coerceIn(3f, 12f)
                g.circle(p0.x, p0.y, r * 2.2f, 0x22FFFFE0)
                g.circle(p0.x, p0.y, r * 1.5f, 0x44FFFFE0)
                g.roundRect(p0.x - r, p0.y - r * 0.6f, r * 2, r * 1.2f, r * 0.3f, 0xFFFFFFE8.toInt())
            }
        }
    }

    private fun drawPitch(g: Gfx, cam: Camera, sky: Sky) {
        val dark = when (sky) {
            Sky.DAY -> 0xFF2E8B3A.toInt()
            Sky.SUNSET -> 0xFF2D7A33.toInt()
            Sky.NIGHT -> 0xFF1F6E2E.toInt()
        }
        val light = mix(dark, 0xFFB8F0A0.toInt(), 0.16f)
        quad(g, cam, -60f, 0f, -70f, 60f, 0f, -70f, 60f, 0f, 8.1f, -60f, 0f, 8.1f, dark)
        var z = -70f
        var i = 0
        while (z < 8f) {
            val z2 = z + 5.5f
            if (i % 2 == 0) quad(g, cam, -60f, 0.001f, z, 60f, 0.001f, z, 60f, 0.001f, minOf(z2, 8f), -60f, 0.001f, minOf(z2, 8f), light)
            z = z2; i++
        }
        val white = 0xF2FFFFFF.toInt()
        val w = 0.12f
        groundLine(g, cam, -34f, 0f, 34f, 0f, w, white)
        groundLine(g, cam, -34f, 0f, -34f, -70f, w, white)
        groundLine(g, cam, 34f, 0f, 34f, -70f, w, white)
        groundLine(g, cam, -60f, -52.5f, 60f, -52.5f, w, white)
        // Penalty box and six-yard box.
        for (hw in floatArrayOf(9.16f, 20.16f)) {
            val d = if (hw < 10f) 5.5f else 16.5f
            groundLine(g, cam, -hw, 0f, -hw, -d, w, white)
            groundLine(g, cam, hw, 0f, hw, -d, w, white)
            groundLine(g, cam, -hw, -d, hw, -d, w, white)
        }
        // Penalty spot and the "D".
        val seg = 24
        val sx = FloatArray(seg); val sy = FloatArray(seg); val sz = FloatArray(seg)
        for (k in 0 until seg) {
            val a = (2 * PI * k / seg).toFloat()
            sx[k] = sin(a) * 0.11f; sy[k] = 0.005f; sz[k] = -11f + cos(a) * 0.11f
        }
        val n = cam.projectPoly(sx, sy, sz, seg)
        if (n >= 3) g.poly(cam.outX, cam.outY, n, white)
        val arcA = kotlin.math.acos(5.5f / 9.15f)
        for (k in 0 until seg) {
            val a0 = -arcA + 2 * arcA * k / seg; val a1 = -arcA + 2 * arcA * (k + 1) / seg
            groundLine(g, cam, sin(a0) * 9.15f, -11f - cos(a0) * 9.15f, sin(a1) * 9.15f, -11f - cos(a1) * 9.15f, w, white)
        }
    }

    private fun drawBoards(g: Gfx, cam: Camera) {
        val z = Match.BOARDS_Z
        val colors = intArrayOf(0xFF1565C0.toInt(), 0xFFFFC107.toInt(), 0xFFD32F2F.toInt(), 0xFF00897B.toInt())
        val words = arrayOf("GOAL RUSH", "TOP BINS", "KICK IT", "SUPER CUP")
        var i = 0
        var x = -42f
        while (x < 42f) {
            val c = colors[i % colors.size]
            quad(g, cam, x, 0f, z, x + 7f, 0f, z, x + 7f, 0.9f, z, x, 0.9f, z, c)
            cam.project(x + 3.5f, 0.27f, z, p0)
            if (p0.visible && p0.s > 4f) {
                val tc = if (c == colors[1]) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
                g.text(words[i % words.size], p0.x, p0.y, 0.5f * p0.s, tc, 1)
            }
            x += 7f; i++
        }
    }

    private fun drawShadows(g: Gfx, m: Match) {
        val cam = m.cam
        val shade = 0x55000000
        fun shadow(x: Float, z: Float, rx: Float, rz: Float, a: Float = 1f) {
            cam.project(x - rx, 0f, z, p0)
            cam.project(x + rx, 0f, z, p1)
            if (!p0.visible) return
            val w = p1.x - p0.x
            val hz = rz * p0.s * 0.35f
            g.oval(p0.x, p0.y - hz / 2, w, hz, alpha(shade, a))
        }
        val k = m.keeper.body
        shadow((k.footL.x + k.footR.x + k.head.x) / 3f, KEEPER_Z, 0.5f + abs(k.head.x - k.pelvis.x), 0.9f)
        for (w in m.wall) shadow(w.x, w.z, 0.35f, 0.7f, 1f / (1f + w.jump * 2f))
        val b = m.ball.pos
        shadow(b.x, b.z, 0.13f, 0.3f, 1f / (1f + b.y * 0.6f))
    }

    // ---- goal ----

    private fun polyline(g: Gfx, cam: Camera, n: Int, w: Float, color: Int, pt: (Int) -> Vec3) {
        var prevOk = false
        var px = 0f; var py = 0f
        for (i in 0..n) {
            val v = pt(i)
            cam.project(v, p0)
            if (p0.visible && prevOk) g.line(px, py, p0.x, p0.y, w, color)
            prevOk = p0.visible; px = p0.x; py = p0.y
        }
    }

    private fun drawNet(g: Gfx, m: Match) {
        val cam = m.cam
        val hw = GOAL_HALF_W
        val color = 0xB0FFFFFF.toInt()
        val w = 0.7f
        val step = 0.33f
        val topBack = m.netBackZ(GOAL_H)
        // Soft shading inside the goal.
        quad(g, cam, -hw, 0f, NET_DEPTH, hw, 0f, NET_DEPTH, hw, GOAL_H, topBack, -hw, GOAL_H, topBack, 0x22FFFFFF)
        quad(g, cam, -hw, 0f, 0f, hw, 0f, 0f, hw, 0f, NET_DEPTH, -hw, 0f, NET_DEPTH, 0x18000000)
        // Back net.
        fun back(x: Float, y: Float) = Vec3(x, y, m.netBackZ(y) + m.bulgeAt(x, y))
        var x = -hw
        while (x <= hw + 1e-3f) {
            val xx = x
            polyline(g, cam, 8, w, color) { i -> back(xx, GOAL_H * i / 8f) }
            x += step
        }
        var y = 0f
        while (y <= GOAL_H + 1e-3f) {
            val yy = y
            polyline(g, cam, 14, w, color) { i -> back(-hw + 2 * hw * i / 14f, yy) }
            y += step
        }
        // Side nets.
        for (sx in floatArrayOf(-hw, hw)) {
            y = 0f
            while (y <= GOAL_H + 1e-3f) {
                cam.project(sx, y, 0f, p0); val ax = p0.x; val ay = p0.y
                cam.project(sx, y, m.netBackZ(y), p1)
                if (p0.visible && p1.visible) g.line(ax, ay, p1.x, p1.y, w, color)
                y += step
            }
            var z = step
            while (z < NET_DEPTH) {
                val zz = z
                // Vertical strands, up to where the sloping back net starts.
                val topY = if (zz <= topBack) GOAL_H else GOAL_H * (NET_DEPTH - zz) / (NET_DEPTH - topBack)
                cam.project(sx, 0f, zz, p0); val ax = p0.x; val ay = p0.y
                cam.project(sx, topY, zz, p1)
                if (p0.visible && p1.visible) g.line(ax, ay, p1.x, p1.y, w, color)
                z += step
            }
        }
        // Roof net.
        x = -hw
        while (x <= hw + 1e-3f) {
            cam.project(x, GOAL_H, 0f, p0); val ax = p0.x; val ay = p0.y
            cam.project(x, GOAL_H, topBack, p1)
            if (p0.visible && p1.visible) g.line(ax, ay, p1.x, p1.y, w, color)
            x += step
        }
        var z = step
        while (z <= topBack + 1e-3f) {
            cam.project(-hw, GOAL_H, z, p0); val ax = p0.x; val ay = p0.y
            cam.project(hw, GOAL_H, z, p1)
            if (p0.visible && p1.visible) g.line(ax, ay, p1.x, p1.y, w, color)
            z += step
        }
        // Net frame at the back.
        val frame = 0xFFDDDDDD.toInt()
        for (sx in floatArrayOf(-hw, hw)) {
            polyline(g, cam, 1, 1.4f, frame) { i -> if (i == 0) Vec3(sx, GOAL_H, 0f) else Vec3(sx, GOAL_H, topBack) }
            polyline(g, cam, 1, 1.4f, frame) { i -> if (i == 0) Vec3(sx, GOAL_H, topBack) else Vec3(sx, 0f, NET_DEPTH) }
        }
    }

    private fun drawFrame(g: Gfx, m: Match) {
        val cam = m.cam
        val hw = GOAL_HALF_W
        for ((i, tg) in m.level.targets.withIndex()) drawRing(g, cam, tg, i == m.ringHit)
        val bl = cam.project(-hw, 0f, 0f, Proj()); val tl = cam.project(-hw, GOAL_H, 0f, Proj())
        val br = cam.project(hw, 0f, 0f, Proj()); val tr = cam.project(hw, GOAL_H, 0f, Proj())
        if (!bl.visible || !br.visible) return
        val w = max(2f, POST_R * 2 * bl.s)
        val shade = 0xFFB9C2CC.toInt()
        g.line(bl.x + w * 0.2f, bl.y, tl.x + w * 0.2f, tl.y, w, shade)
        g.line(br.x + w * 0.2f, br.y, tr.x + w * 0.2f, tr.y, w, shade)
        g.line(tl.x, tl.y + w * 0.2f, tr.x, tr.y + w * 0.2f, w, shade)
        g.line(bl.x, bl.y, tl.x, tl.y, w * 0.8f, 0xFFFFFFFF.toInt())
        g.line(br.x, br.y, tr.x, tr.y, w * 0.8f, 0xFFFFFFFF.toInt())
        g.line(tl.x, tl.y, tr.x, tr.y, w * 0.8f, 0xFFFFFFFF.toInt())
    }

    private fun drawRing(g: Gfx, cam: Camera, t: Target, hit: Boolean) {
        val pulse = 0.5f + 0.5f * sin(time * 5f)
        val c = if (hit) 0xFF7CFF6B.toInt() else mix(0xFFFFC107.toInt(), 0xFFFFF59D.toInt(), pulse)
        cam.project(t.x, t.y, -0.02f, p0)
        val inner = alpha(c, 0.18f)
        g.circle(p0.x, p0.y, t.r * p0.s, inner)
        polyline(g, cam, 28, max(2f, 0.07f * p0.s), c) { i ->
            val a = (2 * PI * i / 28).toFloat()
            Vec3(t.x + cos(a) * t.r, t.y + sin(a) * t.r, -0.02f)
        }
    }

    // ---- players ----

    class Kit(val shirt: Int, val shorts: Int, val socks: Int, val gloves: Int = 0, val trim: Int = 0xFFFFFFFF.toInt())

    private fun drawKeeper(g: Gfx, cam: Camera, k: Keeper) = drawFigure(g, cam, k.body, KEEPER_KIT, 0xFFD9A47A.toInt(), keeper = true)

    private fun limb(g: Gfx, cam: Camera, a: Vec3, b: Vec3, width: Float, color: Int) {
        cam.project(a, p0); val ax = p0.x; val ay = p0.y; val s = p0.s
        cam.project(b, p1)
        if (!p0.visible || !p1.visible) return
        g.line(ax, ay, p1.x, p1.y, max(1.5f, width * (s + p1.s) / 2f), color)
    }

    fun drawFigure(g: Gfx, cam: Camera, b: Body, kit: Kit, skin: Int, keeper: Boolean = false) {
        val boot = 0xFF111111.toInt()
        // Legs: shorts, skin, socks, boots.
        for ((hip, knee, foot) in listOf(Triple(b.hipL, b.kneeL, b.footL), Triple(b.hipR, b.kneeR, b.footR))) {
            val midKnee = hip + (knee - hip) * 0.55f
            limb(g, cam, knee, foot, 0.12f, kit.socks)
            limb(g, cam, midKnee, knee, 0.13f, skin)
            limb(g, cam, hip, midKnee, 0.19f, kit.shorts)
            limb(g, cam, foot, foot + Vec3(0f, 0f, -0.12f), 0.11f, boot)
        }
        // Torso.
        cam.project(b.shL, p0); val ax = p0.x; val ay = p0.y; val s = p0.s
        cam.project(b.shR, p1); val bx = p1.x; val by = p1.y
        val hl = cam.project(b.hipL, Proj()); val hr = cam.project(b.hipR, Proj())
        if (p0.visible && hl.visible) {
            val xs = floatArrayOf(ax, bx, hr.x, hl.x)
            val ys = floatArrayOf(ay, by, hr.y, hl.y)
            // Fill and round off the shoulders and waist.
            g.poly(xs, ys, 4, kit.shirt)
            g.line(ax, ay, bx, by, 0.16f * s, kit.shirt)
            g.line(hl.x, hl.y, hr.x, hr.y, 0.14f * s, kit.shirt)
            g.line((ax + hl.x) / 2, (ay + hl.y) / 2, (bx + hr.x) / 2, (by + hr.y) / 2, 0.3f * s, kit.shirt)
            // A stripe across the chest.
            val cx0 = ax + (hl.x - ax) * 0.3f; val cy0 = ay + (hl.y - ay) * 0.3f
            val cx1 = bx + (hr.x - bx) * 0.3f; val cy1 = by + (hr.y - by) * 0.3f
            g.line(cx0, cy0, cx1, cy1, 0.06f * s, kit.trim)
        }
        // Arms.
        for ((sh, el, hand) in listOf(Triple(b.shL, b.elL, b.handL), Triple(b.shR, b.elR, b.handR))) {
            limb(g, cam, sh, el, 0.11f, kit.shirt)
            limb(g, cam, el, hand, 0.09f, if (keeper) kit.shirt else skin)
            cam.project(hand, p0)
            if (p0.visible) g.circle(p0.x, p0.y, (if (keeper) 0.1f else 0.055f) * p0.s, if (keeper) kit.gloves else skin)
        }
        // Neck and head.
        limb(g, cam, b.neck, b.head, 0.09f, skin)
        cam.project(b.head, p0)
        if (p0.visible) {
            val r = 0.12f * p0.s
            g.circle(p0.x, p0.y, r, skin)
            val hair = if (skin == SKINS[2] || skin == SKINS[3]) 0xFF1A1A1A.toInt() else 0xFF4E342E.toInt()
            g.oval(p0.x - r, p0.y - r * 1.05f, r * 2, r * 1.1f, hair)
            g.circle(p0.x - r * 0.35f, p0.y + r * 0.15f, r * 0.12f, 0xFF222222.toInt())
            g.circle(p0.x + r * 0.35f, p0.y + r * 0.15f, r * 0.12f, 0xFF222222.toInt())
        }
    }

    // ---- ball ----

    private fun drawTrail(g: Gfx, m: Match) {
        val n = m.trail.size
        for ((i, p) in m.trail.withIndex()) {
            m.cam.project(p, p0)
            if (!p0.visible) continue
            val a = (i + 1f) / n
            g.circle(p0.x, p0.y, max(0.8f, BALL_R * p0.s * 0.5f * a), alpha(0xFFFFFFFF.toInt(), a * 0.45f))
        }
    }

    fun drawBall(g: Gfx, cam: Camera, ball: Ball) {
        cam.project(ball.pos, p0)
        if (!p0.visible) return
        val cx = p0.x; val cy = p0.y
        val r = max(1.5f, BALL_R * p0.s)
        g.circle(cx, cy, r, 0xFFF4F4F4.toInt())
        // Rotate the panel pattern around the roll axis (Rodrigues' formula).
        val ax = ball.rollAxis
        val c = cos(ball.roll); val s = sin(ball.roll)
        for (sp in spots) {
            val rot = sp * c + (ax cross sp) * s + ax * ((ax dot sp) * (1 - c))
            // Facing the camera?
            val facing = -(rot dot cam.fwd)
            if (facing <= 0.1f) continue
            val sx = rot dot cam.right
            val sy = rot dot cam.up
            g.circle(cx + sx * r * 0.92f, cy - sy * r * 0.92f, r * 0.3f * sqrt(facing), 0xFF1C1C1C.toInt())
        }
        // Shading: darker towards the bottom right, a highlight at the top left.
        g.circle(cx + r * 0.25f, cy + r * 0.3f, r * 0.85f, 0x18000000)
        g.circle(cx - r * 0.35f, cy - r * 0.4f, r * 0.35f, 0x66FFFFFF)
    }

    companion object {
        val KEEPER_KIT = Kit(0xFFFFC21A.toInt(), 0xFF1F1F1F.toInt(), 0xFFFFC21A.toInt(), gloves = 0xFFF5F5F5.toInt(), trim = 0xFF1F1F1F.toInt())
        val WALL_KIT = Kit(0xFFD7263D.toInt(), 0xFFFFFFFF.toInt(), 0xFFD7263D.toInt())
        val SKINS = intArrayOf(0xFFF1C27D.toInt(), 0xFFE0AC69.toInt(), 0xFF8D5524.toInt(), 0xFF5C3A1E.toInt(), 0xFFC68642.toInt())
    }
}
