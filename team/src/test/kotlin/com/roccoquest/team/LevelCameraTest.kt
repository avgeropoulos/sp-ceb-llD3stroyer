package com.roccoquest.team

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LevelCameraTest {
    private val dt = 1f / 60f
    private val viewW = 427f
    private val heroW = 12f
    private val maxX = 4000f

    /** Records the camera every frame and checks it never jolts and never loses the hero. */
    private inner class Watch(val cam: LevelCamera) {
        var lastX = cam.x
        var lastStep = 0f
        var biggestStep = 0f
        var biggestJolt = 0f
        fun frame(heroX: Float, lock: Float? = null) {
            cam.update(dt, heroX, heroW, viewW, maxX, lock)
            val step = cam.x - lastX
            biggestStep = maxOf(biggestStep, abs(step))
            biggestJolt = maxOf(biggestJolt, abs(step - lastStep))
            lastStep = step
            lastX = cam.x
            assertTrue(heroX >= cam.x - 0.01f && heroX + heroW <= cam.x + viewW + 0.01f, "the hero is on the screen: hero $heroX, camera ${cam.x}")
        }
    }

    @Test fun runningStoppingAndTurningIsSmooth() {
        val cam = LevelCamera()
        var x = 300f
        cam.snap(x, heroW, 1, viewW, maxX, null)
        val w = Watch(cam)
        var vx = 0f
        // Speed up to a sprint, run, then bump into a wall (an instant stop), then run back left.
        for (f in 0 until 600) {
            val want = when {
                f < 200 -> 180f
                f < 320 -> 0f
                else -> -120f
            }
            vx = if (f == 200) 0f else approach(vx, want, 800f * dt)
            x += vx * dt
            w.frame(x)
        }
        assertTrue(w.biggestStep <= 180f * 1.8f * dt, "never faster than a little over the hero: ${w.biggestStep}")
        // The old camera was glued to the hero: hitting the wall stopped the whole picture dead (3 px a frame at once).
        assertTrue(w.biggestJolt < 0.8f, "no jolts: ${w.biggestJolt}")
    }

    @Test fun looksAheadTheWayYoureRunning() {
        val cam = LevelCamera()
        var x = 1500f
        cam.snap(x, heroW, 1, viewW, maxX, null)
        repeat(240) { x += 2f; cam.update(dt, x, heroW, viewW, maxX, null) }
        val right = x + heroW / 2 - cam.x
        assertTrue(right < viewW * 0.45f, "running right, there's more room in front: hero at $right of $viewW")
        repeat(360) { x -= 2f; cam.update(dt, x, heroW, viewW, maxX, null) }
        val left = x + heroW / 2 - cam.x
        assertTrue(left > viewW * 0.55f, "running left, the view slides over to show what's coming: hero at $left")
        // Turning around on the spot doesn't swing the picture.
        val before = cam.x
        repeat(60) { cam.update(dt, x, heroW, viewW, maxX, null) }
        assertTrue(abs(cam.x - before) < 20f, "standing still, the camera just settles")
    }

    @Test fun suddenJumpsGlideOverSmoothly() {
        val cam = LevelCamera()
        var x = 1000f
        cam.snap(x, heroW, 1, viewW, maxX, null)
        repeat(60) { cam.update(dt, x, heroW, viewW, maxX, null) }
        val w = Watch(cam)
        // SWAP: the Pokemon behind takes over, 40 pixels back.
        x -= 40f
        w.frame(x)
        assertTrue(abs(w.lastStep) < 1f, "no snap on the first frame: ${w.lastStep}")
        repeat(120) { w.frame(x) }
        assertTrue(w.biggestJolt < 0.5f, "the glide speeds up and slows down gently: ${w.biggestJolt}")
        val settled = cam.x
        repeat(60) { w.frame(x) }
        assertTrue(abs(cam.x - settled) < 1.5f, "and it settles")
    }

    @Test fun farTeleportsCutStraightThere() {
        val cam = LevelCamera()
        cam.snap(500f, heroW, 1, viewW, maxX, null)
        cam.update(dt, 2500f, heroW, viewW, maxX, null)
        assertTrue(2500f in cam.x..cam.x + viewW, "the hero is on the screen right away")
    }

    @Test fun bossArenaLockHoldsTheCameraAndKeepsTheHeroOnScreen() {
        val cam = LevelCamera()
        var x = 1000f
        cam.snap(x, heroW, 1, viewW, maxX, null)
        val lock = 1100f
        val w = Watch(cam)
        // The hero walks around inside the arena while the camera glides over to it.
        for (f in 0 until 240) {
            x = (x + if (f / 60 % 2 == 0) 2f else -2f).coerceIn(lock, lock + viewW - heroW)
            w.frame(x, lock)
        }
        assertEquals(lock, cam.x, 0.5f, "the camera holds the arena")
        assertTrue(w.biggestJolt < 1f, "it glides there smoothly: ${w.biggestJolt}")
        // The boss is beaten: the camera glides back to following the hero.
        repeat(120) { x += 2f; w.frame(x) }
        assertTrue(w.biggestJolt < 1f, "and glides back smoothly: ${w.biggestJolt}")
    }

    @Test fun neverPastTheEndsOfTheLevel() {
        val cam = LevelCamera()
        var x = 30f
        cam.snap(x, heroW, -1, viewW, maxX, null)
        assertEquals(0f, cam.x)
        repeat(600) { x -= 3f; x = x.coerceAtLeast(0f); cam.update(dt, x, heroW, viewW, maxX, null); assertTrue(cam.x >= 0f) }
        x = maxX + viewW - 40f
        repeat(600) { x = (x + 3f).coerceAtMost(maxX + viewW - heroW); cam.update(dt, x, heroW, viewW, maxX, null); assertTrue(cam.x <= maxX) }
        assertEquals(maxX, cam.x, 0.5f)
    }

    @Test fun springGlidesWithoutOvershooting() {
        val s = Spring(0f)
        var highest = 0f
        repeat(120) { s.step(100f, 0.2f, dt); highest = maxOf(highest, s.pos) }
        assertEquals(100f, s.pos, 0.5f)
        assertTrue(highest <= 100.01f, "never goes past: $highest")
    }
}
