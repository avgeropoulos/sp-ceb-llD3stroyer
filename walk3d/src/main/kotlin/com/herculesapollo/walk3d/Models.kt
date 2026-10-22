package com.herculesapollo.walk3d

import com.herculesapollo.walk.Kind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Builds the 3D scene out of boxes, balls, cones and cylinders. Models face +z with their feet at y = 0. */
object Models {
    private const val HALF_PI = (PI / 2).toFloat()
    private const val BLACK = 0xFF151515.toInt()
    private const val WHITE = 0xFFF4F4F4.toInt()
    private const val PINK = 0xFFFF7A9A.toInt()
    private const val GOLD = 0xFFFFD21F.toInt()
    const val HERC_COLLAR = 0xFFE52521.toInt()
    const val APOLLO_COLLAR = 0xFF2E7CF6.toInt()

    /** Each dog's colors: [body] curls, [curl] highlights, [head], [ear], [muzzle], [beard] and [tail]. */
    class Coat(val body: Int, val curl: Int, val head: Int, val headCurl: Int, val ear: Int, val muzzle: Int, val beard: Int, val tail: Int, val collar: Int)

    /** Hercules: black curls with a white beard and a red collar. */
    val HERC = Coat(0xFF221E20.toInt(), 0xFF3A3437.toInt(), 0xFF262123.toInt(), 0xFF3E373A.toInt(), 0xFF1A1618.toInt(), 0xFF2C2729.toInt(), 0xFFEDE8E0.toInt(), 0xFF1E1A1C.toInt(), HERC_COLLAR)
    /** Apollo: tan curls, a silvery-grey head, reddish-brown ears and a dark tail. */
    val APOLLO = Coat(0xFFC9A27A.toInt(), 0xFFD4B188.toInt(), 0xFF9C958E.toInt(), 0xFFBDB6AE.toInt(), 0xFFA0552E.toInt(), 0xFFB9905F.toInt(), 0xFFD2B286.toInt(), 0xFF2A2018.toInt(), APOLLO_COLLAR)

    private val base = FloatArray(16)
    private val tmp = FloatArray(16)

    private lateinit var f: Frame
    /** While baking a level's scenery, parts go into this instead of the frame. */
    private var batch: MeshBatch? = null

    /** Sets [base] to a model placed at (x, y, z), turned by [yaw] and uniformly scaled by [s]. */
    private fun place(x: Float, y: Float, z: Float, yaw: Float = 0f, s: Float = 1f): FloatArray {
        Mat4.identity(base)
        Mat4.translate(base, x, y, z)
        Mat4.rotateY(base, yaw)
        if (s != 1f) Mat4.scale(base, s, s, s)
        return base
    }

    /**
     * Adds [mesh] relative to [parent]: moved to (px, py, pz), turned by rx/ry/rz and
     * stretched to (sx, sy, sz). Boxes and cylinders sit on py; balls are centred on it.
     */
    private fun part(
        mesh: Mesh, color: Int, parent: FloatArray,
        px: Float, py: Float, pz: Float, sx: Float, sy: Float, sz: Float,
        rx: Float = 0f, ry: Float = 0f, rz: Float = 0f,
    ) {
        val b = batch
        val m = if (b != null) Mat4.copy(parent, tmp) else f.add(mesh, color, parent).model
        Mat4.translate(m, px, py, pz)
        if (ry != 0f) Mat4.rotateY(m, ry)
        if (rx != 0f) Mat4.rotateX(m, rx)
        if (rz != 0f) Mat4.rotateZ(m, rz)
        Mat4.scale(m, sx, sy, sz)
        b?.add(mesh, m, color)
    }

    private fun box(c: Int, p: FloatArray, px: Float, py: Float, pz: Float, sx: Float, sy: Float, sz: Float, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f) =
        part(Meshes.box, c, p, px, py, pz, sx, sy, sz, rx, ry, rz)

    private fun ball(c: Int, p: FloatArray, px: Float, py: Float, pz: Float, sx: Float, sy: Float = sx, sz: Float = sx) =
        part(Meshes.sphere, c, p, px, py, pz, sx, sy, sz)

    fun shade(c: Int, k: Float): Int {
        val r = ((c shr 16 and 0xFF) * k).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 0xFF) * k).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun build(g: Game3D, frame: Frame, aspect: Float) {
        f = frame
        f.clear()
        val look = g.level.def.look
        f.skyTop = look.skyTop
        f.fogColor = look.fog
        f.ambient = look.ambient
        f.sun = look.sun
        f.fogNear = if (look.night) 12f else 20f
        f.fogFar = if (look.night) 38f else 52f

        val d = g.dog
        if (g.state == Game3D.State.TITLE) {
            val cx = g.level.startX
            val cz = g.level.startZ - 2f
            f.setCamera(cx + sin(g.camYaw) * 5.5f, 2.2f, cz + cos(g.camYaw) * 5.5f, cx, 0.7f, cz, aspect)
        } else {
            f.setCamera(g.camX, g.camY, g.camZ, d.x, g.lookY, d.z, aspect)
        }

        // The ground and all the scenery that never moves, each baked into a single mesh.
        f.add(g.terrain, 0xFFFFFFFF.toInt())
        f.add(g.decals, 0xFFFFFFFF.toInt())
        f.add(g.scenery, 0xFFFFFFFF.toInt())
        for (p in g.level.props) when (p.type) {
            PropType.BIN -> binLid(g, p)
            PropType.WATER -> {
                val wave = sin(g.clock * 1.5f) * 0.4f
                part(Meshes.plane, 0xFFE8F8FF.toInt(), place(p.x, 0f, p.z), 0f, 0.08f, -p.d / 2 + 0.3f + wave, p.w, 1f, 0.6f)
            }
            else -> {}
        }
        if (g.goalReached && g.state == Game3D.State.PLAYING) {
            // A big bouncing arrow over your front door.
            val bob = abs(sin(g.clock * 3f)) * 0.5f
            part(Meshes.cone, GOLD, place(g.level.homeX, 3.4f + bob, g.level.homeZ), 0f, 0f, 0f, 1.2f, 1.1f, 1.2f, rx = PI.toFloat())
            box(GOLD, place(g.level.homeX, 3.4f + bob, g.level.homeZ), 0f, 0f, 0f, 0.4f, 1f, 0.4f)
        }
        for (t in g.treats) if (!t.taken) bone(t.x, t.y + sin(g.clock * 3f + t.x) * 0.1f, t.z, g.clock * 2f + t.z)
        for (c in g.critters) critter(g, c)
        for (b in g.bikes) bike(b, g.clock)

        if (g.state == Game3D.State.TITLE) {
            dogModel(true, g.level.startX - 0.8f, 0f, g.level.startZ - 2f, 0.4f, 0f, true, g.clock, look.ground)
            dogModel(false, g.level.startX + 0.8f, 0f, g.level.startZ - 2f, -0.4f, 0f, true, g.clock, look.ground)
            return
        }
        drawDog(g, look.ground)
    }

    private fun groundShadow(x: Float, z: Float, above: Float, r: Float, ground: Int, g: Game3D) {
        var top = 0f
        for (s in g.level.solids) {
            if (s.top < 30f && s.top <= above + 0.05f && x in s.x0..s.x1 && z in s.z0..s.z1) top = maxOf(top, s.top)
        }
        part(Meshes.disc, shade(ground, 0.62f), place(x, top + 0.05f, z), 0f, 0f, 0f, r * 2f, 1f, r * 2f)
    }

    private fun drawDog(g: Game3D, ground: Int) {
        val d = g.dog
        groundShadow(d.x, d.z, d.y, d.radius * 1.4f, ground, g)
        if (d.barkT > 0f) {
            val r = (0.45f - d.barkT) / 0.45f * 7f
            for (i in 0 until 16) {
                val a = i * PI.toFloat() / 8f
                ball(0xFFEFFBFF.toInt(), place(d.x + cos(a) * r, d.y + 0.4f, d.z + sin(a) * r), 0f, 0f, 0f, 0.25f)
            }
        }
        if (d.hurtT > 0f && (d.hurtT * 12f).toInt() % 2 == 0) return
        val moving = d.speed > 0.5f
        dogModel(d.herc, d.x, d.y, d.z, d.yaw, if (moving) d.stride else 0f, d.onGround, g.clock, ground)
        if (d.dashT > 0f) {
            for (i in 1..3) {
                box(WHITE, place(d.x - sin(d.yaw) * i * 0.5f, d.y + 0.2f + i * 0.12f, d.z - cos(d.yaw) * i * 0.5f, d.yaw), 0f, 0f, 0f, 0.05f, 0.05f, 0.4f)
            }
        }
        if (d.stinkT > 0f) {
            for (i in 0 until 4) {
                val a = g.clock * 2f + i * 1.6f
                val rise = (g.clock * 0.8f + i * 0.25f) % 1f
                ball(0xFF9BE15D.toInt(), place(d.x + cos(a) * 0.5f, d.y + 0.8f + rise * 0.8f, d.z + sin(a) * 0.5f), 0f, 0f, 0f, 0.2f + rise * 0.15f)
            }
        }
    }

    /** Curls on the body, relative to the dog's feet. */
    private val bodyCurls = arrayOf(
        floatArrayOf(0f, 0.66f, -0.1f), floatArrayOf(0.13f, 0.64f, 0.1f), floatArrayOf(-0.13f, 0.64f, 0.1f),
        floatArrayOf(0.16f, 0.62f, -0.24f), floatArrayOf(-0.16f, 0.62f, -0.24f), floatArrayOf(0.25f, 0.48f, 0.04f),
        floatArrayOf(-0.25f, 0.48f, 0.04f), floatArrayOf(0.23f, 0.42f, -0.26f), floatArrayOf(-0.23f, 0.42f, -0.26f),
        floatArrayOf(0f, 0.58f, -0.36f), floatArrayOf(0.2f, 0.56f, 0.28f), floatArrayOf(-0.2f, 0.56f, 0.28f),
    )
    /** Curls on top of the head, relative to the head's middle. */
    private val headCurls = arrayOf(
        floatArrayOf(0f, 0.2f, 0f), floatArrayOf(0.12f, 0.17f, 0.06f), floatArrayOf(-0.12f, 0.17f, 0.06f),
        floatArrayOf(0.1f, 0.15f, -0.1f), floatArrayOf(-0.1f, 0.15f, -0.1f), floatArrayOf(0f, 0.17f, -0.13f),
    )
    /** The tail plume: (x, y, z, size) balls arcing up and over the back from its base. */
    private val tailPuffs = arrayOf(
        floatArrayOf(0f, 0.04f, -0.06f, 0.16f), floatArrayOf(0f, 0.17f, -0.12f, 0.18f), floatArrayOf(0f, 0.3f, -0.09f, 0.2f),
        floatArrayOf(0f, 0.4f, 0f, 0.19f), floatArrayOf(0f, 0.44f, 0.11f, 0.16f),
    )
    private val leg = FloatArray(16)

    /** One of the dogs: a curly-coated doodle. [stride] drives the legs (0 = standing). */
    fun dogModel(herc: Boolean, x: Float, y: Float, z: Float, yaw: Float, stride: Float, onGround: Boolean, clock: Float, ground: Int) {
        val c = if (herc) HERC else APOLLO
        val p = place(x, y, z, yaw, if (herc) 0.8f else 0.83f).copyOf()
        val run = stride > 0f
        val bob = if (run && onGround) abs(sin(stride * 6f)) * 0.04f else 0f
        // A round, fluffy body covered in curls.
        ball(c.body, p, 0f, 0.46f + bob, -0.02f, 0.5f, 0.44f, 0.78f)
        ball(c.body, p, 0f, 0.5f + bob, 0.24f, 0.46f, 0.46f, 0.42f)
        for ((i, q) in bodyCurls.withIndex()) ball(if (i % 3 == 0) c.curl else c.body, p, q[0], q[1] + bob, q[2], 0.18f)
        // A big round mop of a head.
        val hy = 0.84f + bob
        val hz = 0.4f
        ball(c.head, p, 0f, hy, hz, 0.46f, 0.44f, 0.44f)
        for (q in headCurls) ball(c.headCurl, p, q[0], hy + q[1], hz + q[2], 0.18f)
        ball(c.muzzle, p, 0f, hy - 0.1f, hz + 0.22f, 0.24f, 0.2f, 0.24f)
        ball(c.beard, p, 0f, hy - 0.23f, hz + 0.17f, 0.2f, 0.17f, 0.18f)
        ball(BLACK, p, 0f, hy - 0.05f, hz + 0.34f, 0.09f, 0.07f, 0.07f)
        for (ex in floatArrayOf(-0.1f, 0.1f)) {
            ball(BLACK, p, ex, hy + 0.04f, hz + 0.2f, 0.07f)
            ball(WHITE, p, ex + 0.015f, hy + 0.06f, hz + 0.235f, 0.025f)
        }
        // Long floppy ears that bounce as the dog runs.
        val flop = if (run) sin(stride * 6f) * 0.25f else 0f
        for (side in floatArrayOf(-1f, 1f)) {
            part(Meshes.sphere, c.ear, p, side * 0.22f, hy - 0.12f, hz - 0.02f, 0.14f, 0.42f, 0.22f, rz = side * (0.12f + flop))
        }
        part(Meshes.cylinder, c.collar, p, 0f, 0.58f + bob, 0.3f, 0.36f, 0.07f, 0.3f, rx = -0.5f)
        ball(GOLD, p, 0f, 0.55f + bob, 0.47f, 0.06f)
        if (run) box(PINK, p, 0f, hy - 0.27f, hz + 0.27f, 0.07f, 0.02f, 0.1f, rx = 0.6f)
        // The plumed tail, curling up over the back and wagging.
        val tail = Mat4.copy(p, FloatArray(16))
        Mat4.translate(tail, 0f, 0.6f + bob, -0.38f)
        Mat4.rotateY(tail, sin(clock * 12f) * 0.35f)
        for ((i, q) in tailPuffs.withIndex()) ball(if (i % 2 == 0) c.tail else shade(c.tail, 1.35f), tail, q[0], q[1], q[2], q[3])
        // Fluffy legs swing from the hips; mid-air they stretch out.
        val swing = if (!onGround) 0.8f else if (run) sin(stride * 6f) * 0.75f else 0f
        for (i in 0 until 4) {
            val lx = if (i % 2 == 0) 0.14f else -0.14f
            val lz = if (i < 2) 0.24f else -0.26f
            val s = if (i == 0 || i == 3) 1f else -1f
            val a = if (!onGround) (if (lz > 0) -swing else swing) else swing * s
            Mat4.copy(p, leg)
            Mat4.translate(leg, lx, 0.4f, lz)
            Mat4.rotateX(leg, a)
            part(Meshes.cylinder, c.body, leg, 0f, -0.4f, 0f, 0.17f, 0.4f, 0.17f)
            ball(if (herc) c.body else c.curl, leg, 0f, -0.37f, 0.03f, 0.19f, 0.13f, 0.22f)
        }
    }

    private fun critter(g: Game3D, c: Critter3) {
        if (c.state == State3.GONE) return
        val dx = c.x - f.eyeX
        val dz = c.z - f.eyeZ
        if (dx * dx + dz * dz > f.fogFar * f.fogFar) return
        val t = c.t
        if (c.hidden) {
            when (c.kind) {
                Kind.RABBIT -> {
                    // A dirt mound with ears poking out.
                    val p = place(c.x, 0f, c.z, c.yaw)
                    ball(0xFF8A6A42.toInt(), p, 0f, 0f, 0f, 0.7f, 0.35f, 0.7f)
                    val wig = sin(g.clock * 4f) * 0.1f
                    box(0xFFB08A64.toInt(), p, 0.06f, 0.12f, 0f, 0.06f, 0.26f, 0.04f, rz = wig)
                    box(0xFFB08A64.toInt(), p, -0.06f, 0.12f, 0f, 0.06f, 0.26f, 0.04f, rz = -wig)
                }
                Kind.RACCOON -> if (sin(g.clock * 2f + c.phase) > -0.6f) {
                    val p = place(c.x, Level.BIN_TOP - 0.06f, c.z, c.yaw)
                    ball(0xFFFFE14D.toInt(), p, 0.1f, 0f, 0.42f, 0.07f)
                    ball(0xFFFFE14D.toInt(), p, -0.1f, 0f, 0.42f, 0.07f)
                }
                else -> {}
            }
            return
        }
        groundShadow(c.x, c.z, c.y, c.radius * 1.2f, g.level.def.look.ground, g)
        val moving = c.state == State3.FLEE || c.state == State3.CHASED
        when (c.kind) {
            Kind.SQUIRREL -> {
                val hop = if (c.climbing || c.state == State3.SAFE) 0f else abs(sin(t * (if (moving) 14f else 6f))) * 0.12f
                val p = place(c.x, c.y + hop, c.z, c.yaw).copyOf()
                if (c.climbing || c.state == State3.SAFE) Mat4.rotateX(p, -HALF_PI)
                val fur = 0xFF8E6E53.toInt()
                box(fur, p, 0f, 0.04f, 0f, 0.17f, 0.17f, 0.28f)
                box(0xFFD9C2A0.toInt(), p, 0f, 0.04f, 0.05f, 0.12f, 0.06f, 0.18f)
                box(fur, p, 0f, 0.12f, 0.17f, 0.14f, 0.14f, 0.14f)
                box(BLACK, p, 0.05f, 0.2f, 0.24f, 0.03f, 0.03f, 0.02f)
                box(BLACK, p, -0.05f, 0.2f, 0.24f, 0.03f, 0.03f, 0.02f)
                box(0xFF6E5038.toInt(), p, 0f, 0.12f, -0.16f, 0.15f, 0.36f, 0.12f, rx = -0.35f)
            }
            Kind.PIGEON, Kind.GULL -> {
                val gull = c.kind == Kind.GULL
                val body = if (gull) WHITE else 0xFF8A8FA0.toInt()
                val wing = if (gull) 0xFFB8C2CC.toInt() else 0xFF5A6070.toInt()
                val p = place(c.x, c.y, c.z, c.yaw).copyOf()
                val peck = if (!c.flying && sin(t * 5f + c.phase) > 0.7f) 0.06f else 0f
                ball(body, p, 0f, 0.14f, 0f, 0.2f, 0.17f, 0.3f)
                ball(if (gull) WHITE else 0xFF6A9A8A.toInt(), p, 0f, 0.27f - peck, 0.13f + peck, 0.13f)
                box(if (gull) GOLD else 0xFFFF9A30.toInt(), p, 0f, 0.25f - peck, 0.2f + peck, 0.04f, 0.03f, 0.08f)
                val flap = if (c.flying) sin(c.t * 24f) * 0.9f else 0.1f
                box(wing, p, 0.08f, 0.16f, -0.02f, 0.22f, 0.03f, 0.18f, rz = -flap)
                box(wing, p, -0.08f, 0.16f, -0.02f, 0.22f, 0.03f, 0.18f, rz = flap)
            }
            Kind.RABBIT -> {
                val hop = if (moving) abs(sin(t * 12f)) * 0.35f else 0f
                val p = place(c.x, c.y + hop, c.z, c.yaw).copyOf()
                val fur = 0xFFB08A64.toInt()
                ball(fur, p, 0f, 0.17f, 0f, 0.3f, 0.28f, 0.42f)
                ball(fur, p, 0f, 0.34f, 0.17f, 0.22f)
                box(fur, p, 0.05f, 0.42f, 0.13f, 0.06f, 0.26f, 0.04f, rx = -0.2f)
                box(fur, p, -0.05f, 0.42f, 0.13f, 0.06f, 0.26f, 0.04f, rx = -0.2f)
                box(PINK, p, 0f, 0.35f, 0.28f, 0.04f, 0.03f, 0.02f)
                ball(WHITE, p, 0f, 0.2f, -0.21f, 0.11f)
            }
            Kind.CAT -> {
                val p = place(c.x, c.y, c.z, c.yaw).copyOf()
                val fur = 0xFFE8892E.toInt()
                val hiss = c.state == State3.FLEE
                box(fur, p, 0f, 0.1f, 0f, 0.2f, if (hiss) 0.24f else 0.18f, 0.38f)
                box(0xFFB55E14.toInt(), p, 0f, if (hiss) 0.33f else 0.27f, -0.04f, 0.21f, 0.02f, 0.2f)
                for ((lx, lz) in listOf(0.07f to 0.14f, -0.07f to 0.14f, 0.07f to -0.14f, -0.07f to -0.14f)) box(fur, p, lx, 0f, lz, 0.05f, 0.12f, 0.05f)
                box(fur, p, 0f, 0.22f, 0.22f, 0.2f, 0.17f, 0.16f)
                part(Meshes.cone, fur, p, 0.06f, 0.38f, 0.22f, 0.07f, 0.09f, 0.06f)
                part(Meshes.cone, fur, p, -0.06f, 0.38f, 0.22f, 0.07f, 0.09f, 0.06f)
                box(0xFF6CD04A.toInt(), p, 0.05f, 0.31f, 0.3f, 0.04f, 0.03f, 0.02f)
                box(0xFF6CD04A.toInt(), p, -0.05f, 0.31f, 0.3f, 0.04f, 0.03f, 0.02f)
                box(fur, p, 0f, 0.2f, -0.18f, 0.05f, 0.34f, 0.05f, rx = if (hiss) -0.1f else -0.6f, rz = sin(g.clock * 2f) * 0.3f)
            }
            Kind.RACCOON -> {
                val p = place(c.x, c.y, c.z, c.yaw).copyOf()
                val fur = 0xFF8A8A8A.toInt()
                box(fur, p, 0f, 0.06f, 0f, 0.26f, 0.22f, 0.38f)
                box(fur, p, 0f, 0.18f, 0.22f, 0.22f, 0.2f, 0.18f)
                box(BLACK, p, 0f, 0.25f, 0.24f, 0.23f, 0.06f, 0.16f)
                box(0xFFE8E8E8.toInt(), p, 0f, 0.2f, 0.31f, 0.1f, 0.06f, 0.04f)
                box(fur, p, 0.07f, 0.38f, 0.2f, 0.05f, 0.07f, 0.04f)
                box(fur, p, -0.07f, 0.38f, 0.2f, 0.05f, 0.07f, 0.04f)
                for (i in 0 until 4) box(if (i % 2 == 0) 0xFF4A4A4A.toInt() else fur, p, 0f, 0.12f + i * 0.02f, -0.22f - i * 0.08f, 0.09f, 0.09f, 0.08f)
            }
            Kind.CRAB -> {
                val step = if (moving) sin(t * 20f) * 0.03f else sin(t * 6f) * 0.015f
                val p = place(c.x, c.y + abs(step), c.z, c.yaw).copyOf()
                val shell = 0xFFE04A2A.toInt()
                box(shell, p, 0f, 0.03f, 0f, 0.34f, 0.12f, 0.24f)
                box(shell, p, 0.22f, 0.04f, 0.12f, 0.1f, 0.08f, 0.12f, ry = 0.4f)
                box(shell, p, -0.22f, 0.04f, 0.12f, 0.1f, 0.08f, 0.12f, ry = -0.4f)
                ball(WHITE, p, 0.07f, 0.2f, 0.08f, 0.06f)
                ball(WHITE, p, -0.07f, 0.2f, 0.08f, 0.06f)
                ball(BLACK, p, 0.07f, 0.21f, 0.11f, 0.03f)
                ball(BLACK, p, -0.07f, 0.21f, 0.11f, 0.03f)
            }
            Kind.SKUNK -> {
                val p = place(c.x, c.y, c.z, c.yaw).copyOf()
                box(BLACK, p, 0f, 0.05f, 0f, 0.26f, 0.2f, 0.42f)
                box(WHITE, p, 0f, 0.25f, 0f, 0.08f, 0.02f, 0.42f)
                box(BLACK, p, 0f, 0.1f, 0.24f, 0.18f, 0.16f, 0.16f)
                box(WHITE, p, 0f, 0.24f, 0.26f, 0.04f, 0.02f, 0.12f)
                box(PINK, p, 0f, 0.15f, 0.33f, 0.04f, 0.04f, 0.02f)
                box(BLACK, p, 0f, 0.18f, -0.24f, 0.18f, 0.42f, 0.16f, rx = -0.25f)
                box(WHITE, p, 0f, 0.5f, -0.32f, 0.07f, 0.12f, 0.17f, rx = -0.25f)
            }
        }
    }

    private fun bone(x: Float, y: Float, z: Float, spin: Float) {
        val p = place(x, y, z, spin).copyOf()
        val c = 0xFFFFF3D6.toInt()
        box(c, p, 0f, -0.05f, 0f, 0.1f, 0.1f, 0.45f)
        for ((bx, bz) in listOf(0.06f to 0.24f, -0.06f to 0.24f, 0.06f to -0.24f, -0.06f to -0.24f)) ball(c, p, bx, 0f, bz, 0.13f)
    }

    private fun bike(b: Bike3, clock: Float) {
        val p = place(b.x, 0f, b.z, b.yaw).copyOf()
        val frame = 0xFF2E86DE.toInt()
        for (wz in floatArrayOf(-0.45f, 0.45f)) {
            part(Meshes.cylinder, 0xFF222222.toInt(), p, 0.04f, 0.34f, wz, 0.66f, 0.08f, 0.66f, rz = HALF_PI)
        }
        box(frame, p, 0f, 0.45f, 0f, 0.06f, 0.08f, 0.9f)
        box(frame, p, 0f, 0.45f, -0.15f, 0.06f, 0.5f, 0.06f)
        box(frame, p, 0f, 0.45f, 0.42f, 0.06f, 0.5f, 0.06f)
        box(0xFF333333.toInt(), p, 0f, 0.92f, 0.42f, 0.5f, 0.04f, 0.04f)
        val pedal = sin(clock * 10f) * 0.4f
        box(0xFF2D3142.toInt(), p, 0.1f, 0.5f, -0.05f, 0.1f, 0.45f, 0.1f, rx = pedal)
        box(0xFF2D3142.toInt(), p, -0.1f, 0.5f, -0.05f, 0.1f, 0.45f, 0.1f, rx = -pedal)
        box(0xFF06D6A0.toInt(), p, 0f, 0.92f, -0.05f, 0.32f, 0.5f, 0.22f, rx = 0.35f)
        box(0xFFE0A878.toInt(), p, 0.17f, 1.2f, 0.12f, 0.07f, 0.07f, 0.4f, rx = 0.3f)
        box(0xFFE0A878.toInt(), p, -0.17f, 1.2f, 0.12f, 0.07f, 0.07f, 0.4f, rx = 0.3f)
        ball(0xFFE0A878.toInt(), p, 0f, 1.58f, 0.14f, 0.26f)
        ball(0xFFFFD166.toInt(), p, 0f, 1.66f, 0.12f, 0.3f, 0.18f, 0.32f)
    }

    /** Bakes the scenery of [level] that never moves (everything but the ground) into one mesh. */
    fun buildScenery(level: Level): Mesh {
        val b = MeshBatch()
        batch = b
        try {
            for (p in level.props) prop(level, p)
            Decor.scatter(level, ::decor)
        } finally {
            batch = null
        }
        return b.build()
    }

    /** One piece of scattered decoration, for [Decor]. */
    private fun decor(kind: Decor.Kind, x: Float, y: Float, z: Float, size: Float, yaw: Float, color: Int) {
        val o = place(x, y, z, yaw).copyOf()
        when (kind) {
            Decor.Kind.TUFT -> for (i in 0 until 3) {
                val a = i * 2.1f
                box(color, o, cos(a) * 0.06f, 0f, sin(a) * 0.06f, 0.025f, 0.24f * size, 0.06f, rx = sin(a) * 0.35f, ry = a, rz = cos(a) * 0.35f)
            }
            Decor.Kind.FLOWER -> {
                box(0xFF3F8C38.toInt(), o, 0f, 0f, 0f, 0.03f, 0.22f * size, 0.03f)
                box(color, o, 0f, 0.22f * size, 0f, 0.12f, 0.06f, 0.12f, ry = 0.78f)
                box(0xFFFFE14D.toInt(), o, 0f, 0.24f * size, 0f, 0.05f, 0.05f, 0.05f)
            }
            Decor.Kind.PEBBLE -> box(color, o, 0f, -0.02f, 0f, 0.18f * size, 0.1f * size, 0.14f * size, rx = 0.2f, rz = 0.15f)
            Decor.Kind.TREE -> {
                part(Meshes.cylinder, 0xFF6B4A2A.toInt(), o, 0f, -0.2f, 0f, 0.5f * size, 3f * size, 0.5f * size)
                ball(color, o, 0f, 3.6f * size, 0f, 3f * size, 2.6f * size, 3f * size)
                ball(shade(color, 1.12f), o, 0.5f * size, 4.4f * size, 0.3f * size, 1.9f * size)
            }
            Decor.Kind.PINE -> {
                part(Meshes.cylinder, 0xFF5A3E22.toInt(), o, 0f, -0.2f, 0f, 0.4f * size, 1.2f * size, 0.4f * size)
                part(Meshes.cone, color, o, 0f, 1f * size, 0f, 2.8f * size, 2.6f * size, 2.8f * size)
                part(Meshes.cone, shade(color, 1.1f), o, 0f, 2.6f * size, 0f, 2f * size, 2.2f * size, 2f * size)
            }
        }
    }

    private fun prop(level: Level, p: Prop) {
        val night = level.def.look.night
        fun dim(c: Int) = if (night) shade(c, 0.7f) else c
        val o = place(p.x, 0f, p.z).copyOf()
        when (p.type) {
            PropType.TREE -> {
                part(Meshes.cylinder, dim(0xFF7A5230.toInt()), o, 0f, 0f, 0f, 0.62f, 3.6f, 0.62f)
                box(dim(0xFF7A5230.toInt()), o, 0.55f, 2.95f, 0f, 0.9f, 0.14f, 0.14f)
                ball(dim(0xFF3E9B3A.toInt()), o, 0f, 4.6f, 0f, 3.2f, 2.3f, 3.2f)
                ball(dim(0xFF56B84A.toInt()), o, 0.7f, 5.3f, 0.5f, 2f)
                ball(dim(0xFF4AAA42.toInt()), o, -0.9f, 4.4f, -0.6f, 2f)
            }
            PropType.BUSH -> {
                ball(dim(0xFF2F8A3A.toInt()), o, 0f, 0.45f, 0f, 1.7f, 1.1f, 1.6f)
                ball(dim(0xFF43A84A.toInt()), o, 0.3f, 0.75f, 0.2f, 1f, 0.8f, 1f)
            }
            PropType.BENCH -> {
                val wood = dim(0xFF9A6234.toInt())
                box(wood, o, 0f, 0.42f, 0f, 1.8f, 0.08f, 0.6f)
                box(wood, o, 0f, 0.55f, -0.28f, 1.8f, 0.4f, 0.06f)
                for (lx in floatArrayOf(-0.8f, 0.8f)) for (lz in floatArrayOf(-0.22f, 0.22f)) box(0xFF333333.toInt(), o, lx, 0f, lz, 0.08f, 0.42f, 0.08f)
            }
            PropType.FENCE -> {
                val c = dim(0xFFF2EEE6.toInt())
                val along = p.w >= p.d
                val len = if (along) p.w else p.d
                val n = (len / 0.9f).toInt().coerceAtLeast(1)
                for (i in 0..n) {
                    val s = -len / 2 + len * i / n
                    box(c, o, if (along) s else 0f, 0f, if (along) 0f else s, 0.12f, Level.FENCE_TOP + 0.05f, 0.12f)
                }
                val sx = if (along) len else 0.08f
                val sz = if (along) 0.08f else len
                box(dim(0xFFD8D2C4.toInt()), o, 0f, 0.45f, 0f, sx, 0.1f, sz)
                box(dim(0xFFD8D2C4.toInt()), o, 0f, 0.85f, 0f, sx, 0.1f, sz)
                box(c, o, 0f, Level.FENCE_TOP - 0.06f, 0f, p.w, 0.06f, p.d)
            }
            PropType.BIN -> {
                box(dim(0xFF3C7A4A.toInt()), o, 0f, 0f, 0f, 0.9f, Level.BIN_TOP - 0.1f, 0.9f)
                for (i in 0 until 3) box(dim(0xFF2F6A3E.toInt()), o, -0.3f + i * 0.3f, 0.1f, 0.455f, 0.08f, Level.BIN_TOP - 0.3f, 0.02f)
            }
            PropType.HOUSE, PropType.HOME -> house(p, o, night)
            PropType.LAMP -> {
                part(Meshes.cylinder, 0xFF2E2E3A.toInt(), o, 0f, 0f, 0f, 0.14f, 3.2f, 0.14f)
                ball(if (night) 0xFFFFF0A0.toInt() else 0xFFE8E8E8.toInt(), o, 0f, 3.35f, 0f, 0.45f)
                if (night) part(Meshes.disc, shade(level.def.look.ground, 1.6f), o, 0f, 0.02f, 0f, 5f, 1f, 5f)
            }
            PropType.UMBRELLA -> {
                part(Meshes.cylinder, 0xFFEEEEEE.toInt(), o, 0f, 0f, 0f, 0.1f, 2.1f, 0.1f)
                part(Meshes.cone, p.color, o, 0f, 1.65f, 0f, 2.6f, 0.65f, 2.6f)
                part(Meshes.disc, 0xFFFFFFFF.toInt(), o, 0.9f, 0.02f, 0.6f, 1.4f, 1f, 0.8f)
            }
            PropType.SANDCASTLE -> {
                val c = 0xFFE0B870.toInt()
                box(c, o, 0f, 0f, 0f, 1.4f, 0.5f, 1.4f)
                for ((tx, tz) in listOf(0.6f to 0.6f, -0.6f to 0.6f, 0.6f to -0.6f, -0.6f to -0.6f)) {
                    part(Meshes.cylinder, c, o, tx, 0f, tz, 0.38f, 0.8f, 0.38f)
                    part(Meshes.cone, 0xFFD4A85A.toInt(), o, tx, 0.8f, tz, 0.42f, 0.35f, 0.42f)
                }
                part(Meshes.cylinder, c, o, 0f, 0.5f, 0f, 0.5f, 0.6f, 0.5f)
                box(0xFFFF5D5D.toInt(), o, 0.08f, 1.3f, 0f, 0.16f, 0.1f, 0.02f)
                box(0xFF555555.toInt(), o, 0f, 1.1f, 0f, 0.02f, 0.3f, 0.02f)
            }
            PropType.HYDRANT -> {
                part(Meshes.cylinder, 0xFFD62828.toInt(), o, 0f, 0f, 0f, 0.36f, 0.55f, 0.36f)
                ball(0xFFB01E1E.toInt(), o, 0f, 0.58f, 0f, 0.36f)
                box(0xFFD62828.toInt(), o, 0f, 0.3f, 0f, 0.56f, 0.12f, 0.12f)
            }
            PropType.FLOWERS -> {
                val colors = intArrayOf(0xFFFF6B9A.toInt(), 0xFFFFE14D.toInt(), 0xFFB98CFF.toInt(), 0xFFFFFFFF.toInt())
                for (i in 0 until 7) {
                    val a = i * 2.4f
                    val r = 0.3f + (i % 3) * 0.25f
                    ball(colors[i % colors.size], o, cos(a) * r, 0.14f, sin(a) * r, 0.16f)
                }
            }
            PropType.ROCK -> ball(dim(0xFF9A9A9A.toInt()), o, 0f, 0.25f, 0f, 1.6f, 1.0f, 1.4f)
            PropType.HOLE -> {
                part(Meshes.disc, 0xFF6B4E2A.toInt(), o, 0f, 0.012f, 0f, 0.8f, 1f, 0.6f)
                part(Meshes.disc, 0xFF2A1E10.toInt(), o, 0f, 0.018f, 0f, 0.55f, 1f, 0.4f)
            }
            PropType.HEDGE -> {
                // A leafy hedge: a box with a bumpy top.
                box(dim(0xFF2F7A34.toInt()), o, 0f, 0f, 0f, p.w, 1.3f, p.d)
                val along = p.w >= p.d
                val len = if (along) p.w else p.d
                val n = (len / 1.4f).toInt()
                for (i in 0..n) {
                    val s = -len / 2 + len * i / n
                    val k = 0.85f + Terrain.noise(p.x + s * 1.7f, p.z + s) * 0.3f
                    ball(dim(shade(0xFF3A8A3C.toInt(), k)), o, if (along) s else 0f, 1.25f, if (along) 0f else s, 1.6f, 1.0f, 1.6f)
                }
            }
            PropType.WATER, PropType.PATH -> {}
        }
    }

    /** A bin's lid, propped open while a raccoon hides inside. */
    private fun binLid(g: Game3D, p: Prop) {
        val dx = p.x - f.eyeX
        val dz = p.z - f.eyeZ
        if (dx * dx + dz * dz > f.fogFar * f.fogFar) return
        val hiding = g.critters.any { it.kind == Kind.RACCOON && it.spawn.x == p.x && it.spawn.z == p.z && it.hidden }
        val c = if (g.level.def.look.night) shade(0xFF2C5A36.toInt(), 0.7f) else 0xFF2C5A36.toInt()
        box(c, place(p.x, 0f, p.z), 0f, Level.BIN_TOP - 0.1f, 0f, 1f, 0.1f, 1f, rx = if (hiding) -0.25f else 0f)
    }

    private fun house(p: Prop, o: FloatArray, night: Boolean) {
        val home = p.type == PropType.HOME
        val wall = if (home) 0xFFF1D7A0.toInt() else p.color
        val roof = if (home) 0xFFB5432F.toInt() else 0xFF8A3324.toInt()
        val wallC = if (night) shade(wall, 0.7f) else wall
        box(wallC, o, 0f, 0f, 0f, p.w, 3.2f, p.d)
        part(Meshes.roof, if (night) shade(roof, 0.7f) else roof, o, 0f, 3.2f, 0f, p.d + 0.5f, 1.8f, p.w + 0.5f, ry = HALF_PI)
        // The front faces the middle of the level.
        val front = if (home || p.z < 0f) 1f else -1f
        val fz = front * (p.d / 2 + 0.03f)
        box(if (home) 0xFFC0392B.toInt() else 0xFF7A4A24.toInt(), o, 0f, 0f, fz, 1.1f, 2f, 0.08f)
        val win = if (night) 0xFFFFE08A.toInt() else 0xFFBFE6FF.toInt()
        box(win, o, -p.w / 2 + 1.1f, 1.3f, fz, 1f, 0.9f, 0.08f)
        box(win, o, p.w / 2 - 1.1f, 1.3f, fz, 1f, 0.9f, 0.08f)
        if (home) {
            box(0xFF7A4A24.toInt(), o, 0f, 2.25f, fz + 0.05f * front, 1.6f, 0.5f, 0.06f)
            part(Meshes.plane, 0xFF8A5A2A.toInt(), o, 0f, 0.015f, fz + 0.7f * front, 1.4f, 1f, 0.8f)
            part(Meshes.cylinder, HERC_COLLAR, o, -1.6f, 0f, fz + 0.5f * front, 0.5f, 0.15f, 0.5f)
            part(Meshes.cylinder, APOLLO_COLLAR, o, 1.6f, 0f, fz + 0.5f * front, 0.4f, 0.12f, 0.4f)
        }
    }
}
