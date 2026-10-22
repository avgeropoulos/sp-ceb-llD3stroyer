package com.herculesapollo.walk3d

import com.herculesapollo.walk.Kind
import com.roccoquest.game.Storage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Game3DTest {
    private val dt = 1f / 60f

    private fun run(g: Game3D, seconds: Float) = repeat((seconds * 60).toInt()) { g.update(dt) }

    private fun start(herc: Boolean, level: Int = 0, storage: Storage = Storage.Memory()): Game3D {
        val g = Game3D(Input3D(), storage = storage)
        g.viewW = 427f
        run(g, 0.5f)
        g.input.tapX = if (herc) 50f else 400f
        g.input.tapY = Controls3D.CARD_TOP + 10f
        g.input.tap = true
        g.update(dt)
        assertEquals(herc, g.dog.herc)
        if (level != 0) g.startLevel(level)
        run(g, 2.1f)
        assertEquals(Game3D.State.PLAYING, g.state)
        return g
    }

    /** Puts the dog at ([x], [z]) facing [yaw], with the camera behind. */
    private fun place(g: Game3D, x: Float, z: Float, yaw: Float = 0f) {
        with(g.dog) { this.x = x; this.z = z; y = 0f; vx = 0f; vz = 0f; vy = 0f; this.yaw = yaw }
        g.camYaw = yaw
        g.camX = x - sin(yaw) * Game3D.CAM_DIST
        g.camZ = z - cos(yaw) * Game3D.CAM_DIST
        g.update(dt)
    }

    private fun clearCritters(g: Game3D) = g.critters.forEach { it.state = State3.GONE }

    /** Pushes the stick toward world point ([x], [z]). */
    private fun steer(g: Game3D, x: Float, z: Float) {
        val dx = x - g.dog.x
        val dz = z - g.dog.z
        val l = hypot(dx, dz).coerceAtLeast(0.001f)
        // The game reads a held stick against the camera direction from when it was first pushed.
        val held = hypot(g.input.stickX, g.input.stickY) > 0.05f
        val a = if (held) g.controlYaw else g.camYaw
        g.input.stickY = (dx * sin(a) + dz * cos(a)) / l
        g.input.stickX = (-dx * cos(a) + dz * sin(a)) / l
    }

    private fun stop(g: Game3D) { g.input.stickX = 0f; g.input.stickY = 0f }

    private fun jumpPeak(g: Game3D, hold: Float): Float {
        g.input.jump = true
        var t = 0f
        var peak = 0f
        do {
            if (t >= hold) g.input.jump = false
            g.update(dt)
            t += dt
            peak = maxOf(peak, g.dog.y)
        } while (!g.dog.onGround || t < 0.1f)
        g.input.jump = false
        g.update(dt)
        return peak
    }

    @Test fun matricesProjectWhatTheCameraSees() {
        val f = Frame()
        f.setCamera(0f, 2f, -5f, 0f, 1f, 0f, 16f / 9f)
        val pt = FloatArray(2)
        assertTrue(f.project(0f, 1f, 0f, 400f, 240f, pt))
        assertTrue(abs(pt[0] - 200f) < 0.5f && abs(pt[1] - 120f) < 0.5f, "looked-at point is centered: ${pt.toList()}")
        // Looking along +z, +x world is on the left of the screen.
        f.project(1f, 1f, 0f, 400f, 240f, pt)
        assertTrue(pt[0] < 200f)
        assertTrue(!f.project(0f, 1f, -10f, 400f, 240f, pt), "behind the camera")

        val m = Mat4.identity()
        Mat4.translate(m, 1f, 2f, 3f)
        Mat4.rotateY(m, (Math.PI / 2).toFloat())
        val out = FloatArray(4)
        Mat4.transform(m, 0f, 0f, 1f, out)
        assertTrue(abs(out[0] - 2f) < 1e-5f && abs(out[2] - 3f) < 1e-5f, "+z turns to +x: ${out.toList()}")
    }

    @Test fun meshesFaceOutward() {
        for (mesh in Meshes.all) {
            val d = mesh.data
            var i = 0
            val st = Mesh.STRIDE
            while (i < d.size) {
                val ux = d[i + st] - d[i]; val uy = d[i + st + 1] - d[i + 1]; val uz = d[i + st + 2] - d[i + 2]
                val vx = d[i + 2 * st] - d[i]; val vy = d[i + 2 * st + 1] - d[i + 1]; val vz = d[i + 2 * st + 2] - d[i + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                // The winding agrees with the stored normal (so back-face culling keeps the outside).
                assertTrue(nx * d[i + 3] + ny * d[i + 4] + nz * d[i + 5] > -1e-6f, "winding at $i")
                i += 3 * st
            }
        }
    }

    @Test fun levelsLoad() {
        assertEquals(4, Levels.all.size)
        for (def in Levels.all) {
            val l = Level(def)
            assertTrue(l.total >= 8, "${def.title}: ${l.total} critters")
            for (s in l.spawns) assertTrue(abs(s.x) < l.half && abs(s.z) < l.half, "${def.title}: ${s.kind} in bounds")
            fun inside(x: Float, z: Float) = l.solids.any { it.top > 30f && x in it.x0..it.x1 && z in it.z0..it.z1 }
            assertTrue(!inside(l.startX, l.startZ), "${def.title}: start is clear")
            assertTrue(!inside(l.homeX, l.homeZ), "${def.title}: front door is clear")
            assertTrue(abs(l.homeZ) < l.half, "${def.title}: door is in bounds")
        }
    }

    @Test fun controlsAreTight() {
        val g = start(herc = false)
        clearCritters(g)
        place(g, 0f, 0f)
        g.input.stickY = 1f
        run(g, 0.15f)
        assertTrue(abs(g.dog.speed - g.dog.maxSpeed) < 0.01f, "full speed in 0.15s: ${g.dog.speed}")
        assertTrue(g.dog.vz > 0f, "forward is away from the camera")
        stop(g)
        run(g, 0.12f)
        assertEquals(0f, g.dog.speed, "stops almost at once")
        g.input.stickY = 1f
        run(g, 0.2f)
        g.input.stickY = -1f
        g.update(dt)
        assertTrue(g.dog.vz < 0f, "turns around on the spot")
        stop(g)
    }

    @Test fun holdJumpToJumpHigher() {
        val g = start(herc = false)
        clearCritters(g)
        place(g, 0f, 0f)
        val high = jumpPeak(g, 2f)
        assertTrue(abs(high - 2.58f) < 0.1f, "Apollo jumps $high")
        val low = jumpPeak(g, 0.04f)
        assertTrue(low < high * 0.45f, "a tap is a hop: $low")
        val h = start(herc = true)
        clearCritters(h)
        place(h, 0f, 0f)
        val herc = jumpPeak(h, 2f)
        assertTrue(abs(herc - 1.46f) < 0.1f, "Hercules jumps $herc")
        assertTrue(h.dog.maxSpeed > g.dog.maxSpeed, "Hercules is the fast one")
    }

    /**
     * Holding the stick to one side used to send the dog round in a slow circle, because
     * the camera swung behind it and the stick followed the camera. Now the dog runs straight.
     */
    @Test fun holdingTheStickRunsStraight() {
        val g = start(herc = true)
        clearCritters(g)
        g.bikes.clear()
        place(g, -12f, -10f, Math.PI.toFloat() / 2f)
        g.input.stickX = 1f
        run(g, 0.3f)
        val yaw0 = g.dog.yaw
        val x0 = g.dog.x; val z0 = g.dog.z
        run(g, 1.5f)
        g.input.stickX = 0f
        assertTrue(abs(wrapAngle(g.dog.yaw - yaw0)) < 0.05f, "still heading the same way: ${g.dog.yaw} vs $yaw0")
        val moved = hypot(g.dog.x - x0, g.dog.z - z0)
        assertTrue(moved > g.dog.maxSpeed * 1.4f, "ran in a straight line: $moved")
        // Meanwhile the camera has eased round behind the dog.
        assertTrue(abs(wrapAngle(g.camYaw - g.dog.yaw)) < abs(wrapAngle(g.controlYaw - g.dog.yaw)))
    }

    @Test fun theCameraDoesNotBobWhenYouJump() {
        val g = start(herc = false)
        clearCritters(g)
        place(g, 0f, 0f)
        run(g, 1f)
        val y0 = g.camY
        g.input.jump = true
        var highest = y0
        repeat(60) { g.update(dt); highest = maxOf(highest, g.camY) }
        g.input.jump = false
        run(g, 1f)
        assertTrue(highest - y0 < 0.4f, "camera rose ${highest - y0} for a ${2.58f} jump")
    }

    @Test fun walkingIntoThingsStopsYouAndYouCanJumpOnTop() {
        val g = start(herc = false)
        clearCritters(g)
        val bench = g.level.solids.first { it.top == 0.5f }
        val cx = (bench.x0 + bench.x1) / 2
        place(g, cx, bench.z1 + 1.5f, Math.PI.toFloat())
        g.input.stickY = 1f
        run(g, 1f)
        assertTrue(g.dog.z >= bench.z1 + g.dog.radius - 0.01f, "blocked by the bench")
        // Jump, then steer to land in the middle of the seat.
        g.input.jump = true
        val mid = (bench.z0 + bench.z1) / 2
        repeat(60) {
            if (abs(g.dog.z - mid) > 0.1f) steer(g, cx, mid) else stop(g)
            if (it == 20) g.input.jump = false
            g.update(dt)
        }
        stop(g)
        run(g, 0.3f)
        assertEquals(0.5f, g.dog.y, "standing on the bench")
    }

    @Test fun onlyApolloReachesSquirrelsUpTrees() {
        for (herc in listOf(true, false)) {
            val g = start(herc)
            val sq = g.critters.first { it.kind == Kind.SQUIRREL }
            place(g, sq.x + 4f, sq.z)
            run(g, 3f)
            assertEquals(State3.SAFE, sq.state)
            val tx = sq.spawn.hx
            val tz = sq.spawn.hz
            // Stand next to the trunk on the squirrel's side and jump.
            val ax = sq.x - tx; val az = sq.z - tz
            val al = hypot(ax, az)
            place(g, tx + ax / al * 0.8f, tz + az / al * 0.8f)
            steer(g, tx, tz)
            g.input.jump = true
            run(g, 0.9f)
            g.input.jump = false
            stop(g)
            run(g, 0.5f)
            if (!herc) assertEquals(1, g.chased, "Apollo got it") else assertEquals(0, g.chased, "too high for Hercules")
        }
    }

    @Test fun bunniesOutrunApolloUnlessHeBarks() {
        fun chase(herc: Boolean, bark: Boolean): Int {
            val g = start(herc)
            val bunny = g.critters.first { it.kind == Kind.RABBIT }
            g.critters.filter { it !== bunny }.forEach { it.state = State3.GONE }
            place(g, bunny.x + 5f, bunny.z)
            if (bark) { g.input.action = true; g.update(dt); g.input.action = false }
            var t = 0f
            while (t < 4f && g.chased == 0) {
                steer(g, bunny.x, bunny.z)
                g.update(dt)
                t += dt
            }
            return g.chased
        }
        assertEquals(1, chase(herc = true, bark = false), "Hercules is fast enough")
        assertEquals(0, chase(herc = false, bark = false), "too fast for Apollo")
        assertEquals(1, chase(herc = false, bark = true), "frozen by the bark")
    }

    @Test fun catsOnFences() {
        val g = start(herc = true, level = 1)
        val cat = g.critters.first { it.kind == Kind.CAT }
        place(g, cat.x, cat.z + 2.5f, Math.PI.toFloat())
        steer(g, cat.x, cat.z)
        g.input.jump = true
        run(g, 0.7f)
        g.input.jump = false
        run(g, 0.5f)
        stop(g)
        assertEquals(1, g.chased)
    }

    @Test fun skunksSpray() {
        val g = start(herc = true)
        g.score = 300
        val skunk = g.critters.first { it.kind == Kind.SKUNK }
        place(g, skunk.x, skunk.z + 3f)
        var t = 0f
        while (t < 2f && g.dog.stinkT <= 0f) { steer(g, skunk.x, skunk.z); g.update(dt); t += dt }
        stop(g)
        assertTrue(g.dog.stinkT > 0f)
        assertEquals(200, g.score)
        assertEquals(0, g.chased)
    }

    @Test fun bikesHurt() {
        val g = start(herc = true)
        clearCritters(g)
        val b = g.bikes.first()
        place(g, b.x + sin(b.yaw) * 3f, b.z + cos(b.yaw) * 3f)
        run(g, 1f)
        assertEquals(2, g.hearts)
    }

    @Test fun chaseEnoughThenGoHome() {
        val store = Storage.Memory()
        val g = start(herc = true, storage = store)
        // Pretend we've chased all but the last one needed.
        val needed = g.level.target
        place(g, g.level.homeX, g.level.homeZ + 1f)
        assertEquals(Game3D.State.PLAYING, g.state, "not enough critters yet")
        assertTrue(g.tip?.contains("more") == true, "tip: ${g.tip}")
        val keep = g.critters.filter { it.kind != Kind.SKUNK }.take(needed)
        for (c in keep) {
            c.state = State3.IDLE
            c.frozenT = 1f
            c.x = g.dog.x; c.z = g.dog.z; c.y = 0f
            g.update(dt)
        }
        assertTrue(g.goalReached)
        run(g, 0.2f)
        assertEquals(Game3D.State.HOME, g.state)
        run(g, 3f)
        assertEquals(Game3D.State.RESULTS, g.state)
        assertEquals(1, g.slots.walk("herc"))
        assertEquals(g.score, g.slots.score("herc"))
        assertTrue(store.load("best3d") > 0)
        run(g, 2f)
        g.input.tap = true
        g.update(dt)
        assertEquals(1, g.levelIndex)
    }

    @Test fun saveSlotsAndSaveAndQuit() {
        val store = Storage.Memory()
        store.save("level3dApollo", 2)
        val g = start(herc = false, storage = store)
        assertEquals(2, g.levelIndex, "old progress moved into slot 1")
        g.score = 700
        g.startLevel(3)
        run(g, 2.1f)
        g.input.pause = true
        g.update(dt)
        g.input.pause = false
        g.update(dt)
        assertTrue(g.paused)
        g.input.tapX = Controls3D.quitX(g.viewW) + 10f
        g.input.tapY = Controls3D.PAUSE_Y + 10f
        g.input.tap = true
        g.update(dt)
        assertEquals(Game3D.State.TITLE, g.state)
        assertEquals(3, g.slots.walk("apollo"))
        assertEquals(700, g.slots.score("apollo"))
        // Slot 2 is a fresh start.
        run(g, 0.5f)
        g.input.tapX = Controls3D.slotX(1, g.viewW) + 10f
        g.input.tapY = Controls3D.SLOT_TOP + 5f
        g.input.tap = true
        g.update(dt)
        assertEquals(2, g.slots.current)
        g.input.tapX = 400f
        g.input.tapY = Controls3D.CARD_TOP + 10f
        g.input.tap = true
        g.update(dt)
        assertEquals(0, g.levelIndex)
        assertEquals(0, g.score)
    }

    /** A simple bot plays each level as each dog, to check they can all be finished. */
    @Test fun everyLevelCanBeFinished() {
        for (herc in listOf(true, false)) for (i in Levels.all.indices) {
            val g = start(herc, level = i)
            var t = 0f
            var stuckT = 0f
            var lastX = g.dog.x
            var lastZ = g.dog.z
            var side = 1f
            while (g.state == Game3D.State.PLAYING && t < 300f) {
                val target = g.compassTarget()
                val near = g.critters.filter { it.catchable && !it.hidden }.minByOrNull { hypot(it.x - g.dog.x, it.z - g.dog.z) }
                if (target != null) {
                    var tx = target[0]
                    var tz = target[1]
                    // Avoid skunks.
                    val skunk = g.critters.firstOrNull { it.kind == Kind.SKUNK && hypot(it.x - g.dog.x, it.z - g.dog.z) < 2f }
                    if (skunk != null) { tx = g.dog.x + (g.dog.x - skunk.x) * 3f; tz = g.dog.z + (g.dog.z - skunk.z) * 3f }
                    if (stuckT > 0.6f) {
                        // Sidestep around whatever's in the way.
                        val dx = tx - g.dog.x; val dz = tz - g.dog.z
                        tx = g.dog.x - dz * side; tz = g.dog.z + dx * side
                        if (stuckT > 1.6f) { stuckT = 0f; side = -side }
                    }
                    steer(g, tx, tz)
                }
                val dist = if (near != null) hypot(near.x - g.dog.x, near.z - g.dog.z) else 99f
                val wantJump = (near != null && dist < 2.5f && near.y > 0.3f) || stuckT > 0.3f || g.bikes.any { hypot(it.x - g.dog.x, it.z - g.dog.z) < 2f }
                // Tap-and-hold: press for a bit, then let go so the next jump registers.
                g.input.jump = wantJump && (t % 0.9f) < 0.6f
                g.input.action = near != null && dist < 5f && (t % 0.8f) < 0.05f
                g.update(dt)
                t += dt
                val moved = hypot(g.dog.x - lastX, g.dog.z - lastZ)
                stuckT = if (moved < g.dog.maxSpeed * dt * 0.3f) stuckT + dt else maxOf(0f, stuckT - dt)
                lastX = g.dog.x; lastZ = g.dog.z
            }
            println("${g.dog.name} on ${g.level.def.title}: state ${g.state} after ${"%.0f".format(t)}s, chased ${g.chased}/${g.level.total}, hearts ${g.hearts}")
            assertEquals(Game3D.State.HOME, g.state, "${g.dog.name} finished ${g.level.def.title}")
        }
    }

    @Test fun screenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(g: Game3D, name: String) {
            val scale = 3f
            val w = (g.viewW * scale).toInt()
            val h = (VIEW_H * scale).toInt()
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            SoftRenderer.render(g.buildFrame(w.toFloat() / h), img)
            Hud3D.render(g, AwtGfx(img, scale))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        val title = Game3D(Input3D()).apply { viewW = 427f }
        run(title, 1f)
        shot(title, "0-title")
        // A close-up of the two dogs, from the front and from the side.
        for ((name, angle) in listOf("0-dogs-front" to 0f, "0-dogs-side" to 1.3f)) {
            val w = (title.viewW * 3).toInt()
            val h = (VIEW_H * 3).toInt()
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val f = title.buildFrame(w.toFloat() / h)
            val cx = title.level.startX
            val cz = title.level.startZ - 2f
            f.setCamera(cx + sin(angle) * 2.6f, 1.1f, cz + cos(angle) * 2.6f, cx, 0.55f, cz, w.toFloat() / h)
            SoftRenderer.render(f, img)
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }

        for (i in Levels.all.indices) {
            val g = start(herc = i % 2 == 0, level = i)
            run(g, 0.3f)
            shot(g, "${i + 1}-start")
        }

        val g = start(herc = false)
        val sq = g.critters.first { it.kind == Kind.SQUIRREL }
        place(g, sq.x + 6f, sq.z + 2f, -1.9f)
        steer(g, sq.x, sq.z)
        run(g, 0.5f)
        shot(g, "5-apollo-squirrel")
        g.input.action = true
        g.update(dt)
        g.input.action = false
        run(g, 0.12f)
        shot(g, "5-apollo-bark")

        val a = start(herc = true, level = 1)
        val cat = a.critters.first { it.kind == Kind.CAT }
        place(a, cat.x + 1f, cat.z + 3f, Math.PI.toFloat() - 0.3f)
        steer(a, cat.x, cat.z)
        a.input.jump = true
        run(a, 0.35f)
        shot(a, "6-herc-cat")
        a.input.jump = false
        stop(a)

        val b = start(herc = false, level = 2)
        place(b, 2f, 0f, 0.4f)
        run(b, 0.2f)
        shot(b, "7-beach")

        val n = start(herc = true, level = 3)
        val raccoon = n.critters.first { it.kind == Kind.RACCOON }
        place(n, raccoon.x - 3f, raccoon.z + 4f, 2.5f)
        run(n, 0.2f)
        shot(n, "8-night")
    }
}
