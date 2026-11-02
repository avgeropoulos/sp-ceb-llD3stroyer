package com.javelinstrike.game

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameTest {
    private val dt = 1f / 60f

    private class Sounds : SoundSink {
        val played = ArrayList<Sound>()
        override fun play(s: Sound) { played += s }
    }

    private fun newGame(sounds: SoundSink = Sounds()): Pair<Game, Input> {
        val input = Input()
        val store = Storage.Memory().apply { save(Game.KEY_SEEN_HOWTO, 1) }
        return Game(input, sounds, store, seed = 42).apply { viewH = 780f } to input
    }

    private fun run(g: Game, seconds: Float) = repeat((seconds * 60).toInt()) { g.update(dt) }

    private fun tap(g: Game, input: Input, r: FloatArray) {
        input.push(Input.DOWN, r[0] + r[2] / 2, r[1] + r[3] / 2, g.clock)
        input.push(Input.UP, r[0] + r[2] / 2, r[1] + r[3] / 2, g.clock)
        g.update(dt)
    }

    private fun tapMiddle(g: Game, input: Input) = tap(g, input, floatArrayOf(180f, 680f, 0f, 0f))

    private fun startPlaying(g: Game, input: Input) {
        tap(g, input, g.titleButton(0))
        assertEquals(Game.Screen.EVENTS, g.screen)
        run(g, 0.3f)
        tap(g, input, g.eventButton(0))
        assertEquals(Game.Screen.INTRO, g.screen)
        run(g, 0.5f)
        tapMiddle(g, input)
        assertEquals(Game.Screen.PLAY, g.screen)
    }

    /**
     * Finds the power and aim that land on the target from where the athlete is now (leading a
     * moving one), the way a perfect player would.
     */
    private fun perfectThrow(g: Game): Pair<Float, Float> {
        var (tx, tz) = g.target.x to g.target.z
        var power = 0f
        var yaw = 0f
        val from = g.runnerZ
        val run = g.runSpeed
        repeat(4) {
            yaw = atan2(tx - RELEASE_X, tz - from)
            var lo = 0f
            var hi = 1f
            repeat(30) {
                val mid = (lo + hi) / 2
                if (Javelin.landing(mid, yaw, 0f, RELEASE_ANGLE, run, from).second < tz) lo = mid else hi = mid
            }
            power = lo
            val (ax, az) = g.target.positionAt(flightTime(power, yaw, run, from) / Game.FLIGHT_SPEED)
            tx = ax; tz = az
        }
        return power to yaw
    }

    private fun flightTime(power: Float, yaw: Float, run: Float = 0f, from: Float = 0f): Float {
        val j = Javelin.launch(power, yaw, RELEASE_ANGLE, run, from)
        var t = 0f
        while (!j.step(1f / 240f, 0f)) t += 1f / 240f
        return t
    }

    /** Sends gyro readings at 200 Hz: spinning about [axis] at [rate](time) rad/s. */
    private fun spin(d: ThrowDetector, t0: Long, seconds: Float, axis: FloatArray, rate: (Float) -> Float): Long {
        val n = (seconds * 200).toInt()
        var t = t0
        for (i in 0 until n) {
            val w = rate(i / 200f)
            d.gyro(t, axis[0] * w, axis[1] * w, axis[2] * w)
            t += 5_000_000L
        }
        return t
    }

    private fun throwWithGyro(d: ThrowDetector, peak: Float, axis: FloatArray = floatArrayOf(0.6f, 0f, 0.8f)): Float? =
        throwRelease(d, peak, axis)?.power

    private fun throwRelease(d: ThrowDetector, peak: Float, axis: FloatArray = floatArrayOf(0.6f, 0f, 0.8f)): Release? {
        d.enabled = true
        d.grip(true)
        var t = spin(d, 0L, 0.1f, axis) { 0f }
        t = spin(d, t, 0.3f, axis) { -4f }                      // pull the arm back ~70°
        t = spin(d, t, 0.15f, axis) { 0f }                      // pause
        spin(d, t, 0.25f, axis) { s -> peak * sin(s / 0.25f * Math.PI.toFloat()) } // whip forward
        return d.takeThrow()
    }

    /**
     * An overhand throw with the phone upright at the grip: leaning back as the arm draws, then
     * whipping forward. The thumb lets go after [releaseAfter] of the 50 swing samples (or the
     * swing finishes on its own), while the hand accelerates at [accel] m/s².
     */
    private fun overhand(releaseAfter: Int = 99, accel: Float = 25f, peak: Float = 15f): Release? {
        val d = ThrowDetector().apply { enabled = true }
        d.gravity(0f, 9.81f, 0f)
        d.grip(true)
        var t = 0L
        fun sample(spin: Float, push: Float) {
            d.gyro(t, spin, 0f, 0f)
            d.push(t, 0f, 0f, -push)
            t += 5_000_000L
        }
        repeat(50) { sample(-4f, 0f) }                        // draw: the top leans back about 57°
        repeat(20) { sample(0f, 0f) }                         // pause
        for (i in 0 until 50) {                               // whip forward
            if (i == releaseAfter) d.grip(false)
            if (d.phase == ThrowDetector.Phase.IDLE) break
            sample(peak * sin(i / 50f * Math.PI.toFloat()), accel)
        }
        return d.takeThrow()
    }

    @Test fun leaningBackAndLettingGoEarlyThrowsHigher() {
        val early = assertNotNull(overhand(releaseAfter = 15))
        val late = assertNotNull(overhand())
        val e = Math.toDegrees(early.elevation!!.toDouble())
        val l = Math.toDegrees(late.elevation!!.toDouble())
        assertTrue(e > l + 20, "early release $e° should be well above late release $l°")
        assertTrue(e in 45.0..70.0, "early $e°")
        // A faster hand throws harder.
        val soft = assertNotNull(overhand(accel = 10f))
        val hard = assertNotNull(overhand(accel = 40f))
        assertTrue(hard.power > soft.power, "hard ${hard.power} vs soft ${soft.power}")
        // A higher throw at the same power lands shorter once it's steep.
        assertTrue(Javelin.landing(0.6f, elevation = MAX_ELEVATION).second < Javelin.landing(0.6f).second)
        // Without knowing gravity the phone can't tell its lean; the game uses its guide instead.
        assertNull(assertNotNull(throwRelease(ThrowDetector(), 15f)).elevation)
    }

    @Test fun bringingThePhoneForwardWithoutThrowingResets() {
        val d = ThrowDetector().apply { enabled = true }
        d.gravity(0f, 9.81f, 0f)
        d.grip(true)
        var t = 0L
        fun spin(rate: Float, n: Int) = repeat(n) { d.gyro(t, rate, 0f, 0f); t += 5_000_000L }
        spin(-4f, 60)
        assertEquals(ThrowDetector.Phase.DRAWN, d.phase)
        assertTrue(d.drawAmount > 0.9f)
        // Slowly forward again: the draw bar drops as it comes forward...
        spin(2f, 60)
        assertTrue(d.drawAmount in 0.4f..0.6f, "half way: draw ${d.drawAmount}")
        // ...and once it's back where it started it's no longer drawn.
        spin(2f, 60)
        assertTrue(d.drawAmount < 0.1f, "draw ${d.drawAmount}")
        assertEquals(ThrowDetector.Phase.GRIP, d.phase)
        assertNull(d.takeThrow())
        // Pull back again, then a quick but gentle push forward: still not a throw.
        spin(-4f, 60)
        assertEquals(ThrowDetector.Phase.DRAWN, d.phase)
        for (i in 0 until 40) { d.gyro(t, 4.5f * sin(i / 40f * Math.PI.toFloat()), 0f, 0f); t += 5_000_000L }
        assertNull(d.takeThrow())
        assertEquals(0f, d.livePower)
        assertTrue(d.phase == ThrowDetector.Phase.GRIP || d.phase == ThrowDetector.Phase.DRAWN)
    }

    @Test fun pullingBackThenWhippingForwardThrows() {
        val d = ThrowDetector()
        val p = assertNotNull(throwWithGyro(d, 15f))
        assertTrue(p in 0.45f..0.75f, "power $p")
        assertEquals(ThrowDetector.Phase.IDLE, d.phase)
        val weak = assertNotNull(throwWithGyro(ThrowDetector(), 7f))
        assertTrue(weak < p, "weaker swing should be weaker: $weak vs $p")
        // Works the same whichever way the phone is held.
        val other = assertNotNull(throwWithGyro(ThrowDetector(), 15f, floatArrayOf(0f, -1f, 0f)))
        assertEquals(p, other, 0.02f)
        val gentle = ThrowDetector().apply { sensitivity = ArmStrength.GENTLE.sensitivity }
        assertEquals(1f, assertNotNull(throwWithGyro(gentle, 15f)), 0.001f)
    }

    @Test fun noThrowWithoutPullingBackOrGripping() {
        val d = ThrowDetector().apply { enabled = true }
        val axis = floatArrayOf(1f, 0f, 0f)
        // Swinging without gripping does nothing.
        var t = spin(d, 0L, 0.3f, axis) { 15f }
        assertNull(d.takeThrow())
        // Gripping and lifting the thumb without pulling back is silent.
        d.grip(true)
        t = spin(d, t, 0.05f, axis) { 0f }
        assertEquals(ThrowDetector.Phase.GRIP, d.phase)
        d.grip(false)
        assertNull(d.takeThrow())
        assertTrue(!d.takeCancel())
        // A swing with no pull back first just counts as the pull back...
        d.grip(true)
        spin(d, t, 0.3f, axis) { 15f }
        assertEquals(ThrowDetector.Phase.DRAWN, d.phase)
        // ...so letting go then is an early release, not a throw.
        d.grip(false)
        assertNull(d.takeThrow())
        assertTrue(d.takeCancel())
    }

    @Test fun soundsAreAudibleAndNeverClip() {
        for (snd in Sound.entries) {
            val samples = SoundSynth.render(snd)
            assertTrue(samples.isNotEmpty(), "$snd is empty")
            val peak = samples.maxOf { abs(it) }
            assertTrue(peak in 0.05f..1f, "$snd peak $peak")
        }
    }

    @Test fun updateButtonOnTheTitleScreen() {
        var taps = 0
        val updater = object : Updater {
            override val label = "UPDATE TO BUILD 200"
            override val detail = "You have build 190. Tap to download and install."
            override val available = true
            override fun tap() { taps++ }
        }
        val input = Input()
        val g = Game(input, Sounds(), Storage.Memory(), updater, seed = 1).apply { viewH = 780f }
        tap(g, input, g.titleButton(4))
        assertEquals(1, taps)
        assertEquals(Game.Screen.TITLE, g.screen)
        val img = BufferedImage(720, 1560, BufferedImage.TYPE_INT_ARGB)
        g.render(AwtGfx(img, 2f))
        ImageIO.write(img, "png", File(File("build/screens").apply { mkdirs() }, "0_update.png"))
    }

    @Test fun javelinPhysics() {
        val (_, weak) = Javelin.landing(0f)
        val (_, strong) = Javelin.landing(1f)
        assertTrue(weak in 25f..40f, "weakest throw $weak m")
        assertTrue(strong in 125f..150f, "strongest throw $strong m")
        var last = 0f
        for (i in 0..10) {
            val z = Javelin.landing(i / 10f).second
            assertTrue(z > last)
            last = z
        }
        assertTrue(Javelin.landing(0.5f, wind = 5f).first > 2f, "wind should push it right")
        assertTrue(Javelin.landing(0.5f, yaw = -0.3f).first < -5f, "aiming left goes left")
    }

    @Test fun throwAngleChangesTheFlight() {
        val (_, flat) = Javelin.landing(0.5f, elevation = MIN_ELEVATION)
        val (_, normal) = Javelin.landing(0.5f)
        val (_, best) = Javelin.landing(0.5f, elevation = 0.76f)
        assertTrue(flat < normal && normal < best, "flat $flat, normal $normal, 44° $best")
        // Leaning the phone back throws higher; the angle locks when you grip.
        val (g, input) = newGame()
        startPlaying(g, input)
        input.hasTilt = true
        input.lean = 0.5f
        run(g, 1f)
        assertTrue(g.aimElev > 0.85f, "angle ${g.aimElev}")
        assertTrue(g.ruler[9] > g.ruler[7] && g.ruler[7] > g.ruler[3] && g.ruler[3] > g.ruler[1], "markers should go further with more power")
        assertTrue(g.powerForTarget() in 0.05f..0.95f, "power for target ${g.powerForTarget()}")
        input.push(Input.DOWN, 180f, 450f, g.clock)
        run(g, 0.1f)
        input.lean = -0.5f
        run(g, 0.5f)
        assertTrue(g.aimElev > 0.85f, "angle should stay locked while gripping")
    }

    @Test fun runningThrowGoesFurther() {
        val standing = Javelin.landing(0.5f).second
        val running = Javelin.landing(0.5f, runSpeed = RUN_TOP).second
        val tooEarly = Javelin.landing(0.5f, runSpeed = RUN_TOP, fromZ = -6f).second
        assertTrue(running > standing + 10f, "running $running vs standing $standing")
        assertEquals(running - 6f, tooEarly, 0.5f)
    }

    @Test fun runUpWhileGrippingThenThrowOrFoul() {
        val (g, input) = newGame()
        startPlaying(g, input)
        assertTrue(g.runUp)
        assertEquals(RUN_START, g.runnerZ)
        val markerAtStart = g.ruler[5]
        // Hold the screen: the athlete runs and speeds up, and the markers move with them.
        input.push(Input.DOWN, 180f, 450f, g.clock)
        run(g, 2f)
        assertTrue(g.runSpeed > 4f, "speed ${g.runSpeed}")
        assertTrue(g.runnerZ > RUN_START + 4f && g.runnerZ < 0f, "at ${g.runnerZ}")
        assertTrue(g.ruler[5] > markerAtStart + 8f, "50% marker should move out as you run")
        // Keep holding past the line: foul, back to the start.
        run(g, 4f)
        assertEquals(RUN_START, g.runnerZ)
        assertEquals("FOUL!", g.msg)
        input.push(Input.UP, 180f, 450f, g.clock)
        run(g, 0.1f)
        // Run, then throw before the line: it flies further than the same throw standing.
        input.push(Input.DOWN, 180f, 450f, g.clock)
        run(g, 2.5f)
        val z = g.runnerZ
        val speed = g.runSpeed
        g.throwJavelin(0.5f, 0f, RELEASE_ANGLE)
        assertTrue(g.javelin!!.z > z, "released from the runner's spot")
        val expected = Javelin.landing(0.5f, 0f, 0f, RELEASE_ANGLE, speed, z).second
        input.push(Input.UP, 180f, 450f, g.clock)
        run(g, 6f)
        assertEquals(expected, g.stuck.last().z, 2f)
        // After the throw the athlete walks back to the start.
        assertEquals(RUN_START, g.runnerZ)
    }

    @Test fun runUpCanBeTurnedOff() {
        val (g, input) = newGame()
        g.startLevel(1)
        run(g, 0.5f)
        val b = g.runUpButton()
        input.push(Input.DOWN, b[0] + 5f, b[1] + 5f, g.clock)
        run(g, 0.1f)
        assertEquals(Game.Screen.INTRO, g.screen)
        assertTrue(!g.runUp)
        assertEquals(0f, g.runnerZ)
        tapMiddle(g, input)
        input.push(Input.DOWN, 180f, 450f, g.clock)
        run(g, 1f)
        assertEquals(0f, g.runnerZ, "standing throws stay at the line")
    }

    @Test fun everyLevelCanBeReached() {
        val maxRange = Javelin.landing(1f).second
        for (n in 1..40) {
            val s = levelSpec(n)
            assertTrue(s.distance * 1.1f + s.swayZ + s.radius < maxRange - 5f, "level $n too far")
            assertTrue(s.time >= 30f && s.hits >= 1 && s.radius >= 3f, "level $n: $s")
            assertTrue(s.distance >= 45f, "level $n is closer than the weakest throw lets you hit")
        }
        assertTrue(levelSpec(LEVELS.size + 1).name.startsWith("Endless"))
    }

    @Test fun hittingTheTargetClearsTheLevel() {
        val sounds = Sounds()
        val (g, input) = newGame(sounds)
        startPlaying(g, input)
        val (power, yaw) = perfectThrow(g)
        g.throwJavelin(power, yaw)
        assertEquals(Game.ThrowState.FLIGHT, g.throwState)
        run(g, 6f)
        assertEquals(Game.Screen.CLEAR, g.screen)
        assertTrue(Sound.BULLSEYE in sounds.played)
        assertEquals(250 + g.levelBonus, g.score)
        run(g, 1.2f)
        tapMiddle(g, input)
        assertEquals(Game.Screen.INTRO, g.screen)
        assertEquals(2, g.levelNum)
    }

    @Test fun missingTellsYouByHowMuch() {
        val (g, input) = newGame()
        startPlaying(g, input)
        g.throwJavelin(0.05f, 0f)
        run(g, 3f)
        assertTrue(g.msg.endsWith("SHORT"), g.msg)
        assertEquals(0, g.hits)
        assertEquals(Game.ThrowState.READY, g.throwState)
        assertEquals(1, g.stuck.size)
    }

    @Test fun targetMovesAfterEachHitOnMultiHitLevels() {
        val (g, input) = newGame()
        g.startLevel(3)
        run(g, 0.5f)
        tapMiddle(g, input)
        val first = g.target
        val (p, yaw) = perfectThrow(g)
        g.throwJavelin(p, yaw)
        run(g, 6f)
        assertEquals(1, g.hits)
        assertEquals(Game.Screen.PLAY, g.screen)
        assertTrue(g.target !== first, "a new target should be placed")
    }

    @Test fun leadingAFarMovingTargetHitsIt() {
        val (g, input) = newGame()
        g.startLevel(9)
        run(g, 0.5f)
        tapMiddle(g, input)
        assertTrue(g.target.moving)
        assertTrue(g.target.z > 60f)
        // Throwing straight at where it is now misses...
        val now = g.target.x to g.target.z
        val (p, yaw) = perfectThrow(g)
        assertTrue(abs(yaw - atan2(now.first - RELEASE_X, now.second)) > 0.01f, "should need to lead the target")
        g.throwJavelin(p, yaw)
        run(g, 6f)
        assertEquals(1, g.hits)
    }

    @Test fun runningOutOfTimeCostsALife() {
        val (g, input) = newGame()
        startPlaying(g, input)
        run(g, g.spec.time + 0.5f)
        assertEquals(Game.Screen.TIME_UP, g.screen)
        assertEquals(2, g.lives)
        run(g, 1.2f)
        tapMiddle(g, input)
        assertEquals(Game.Screen.INTRO, g.screen)
        assertEquals(1, g.levelNum)
        // Score on level 1, then time out twice more on level 2: game over, with the score saved.
        run(g, 0.5f); tapMiddle(g, input)
        val (p, yaw) = perfectThrow(g)
        g.throwJavelin(p, yaw)
        run(g, 6f)
        assertEquals(Game.Screen.CLEAR, g.screen)
        val score = g.score
        run(g, 1.2f); tapMiddle(g, input)
        repeat(2) {
            run(g, 0.5f); tapMiddle(g, input)
            run(g, g.spec.time + 0.5f)
            if (g.screen == Game.Screen.TIME_UP) { run(g, 1.2f); tapMiddle(g, input) }
        }
        assertEquals(Game.Screen.GAME_OVER, g.screen)
        assertTrue(score > 0)
        assertEquals(score, g.highScores[0])
        assertEquals(0, g.newHighRank)
    }

    @Test fun motionThrowThroughTheGame() {
        val (g, input) = newGame()
        startPlaying(g, input)
        g.update(dt)
        assertTrue(g.detector.enabled)
        input.push(Input.DOWN, 180f, 450f, 0f)
        g.update(dt)
        assertEquals(ThrowDetector.Phase.GRIP, g.detector.phase)
        val axis = floatArrayOf(0f, 0f, 1f)
        var t = spin(g.detector, 0L, 0.3f, axis) { -4f }
        g.update(dt)
        assertEquals(ThrowDetector.Phase.DRAWN, g.detector.phase)
        spin(g.detector, t, 0.25f, axis) { s -> 12f * sin(s / 0.25f * Math.PI.toFloat()) }
        g.update(dt)
        assertEquals(Game.ThrowState.FLIGHT, g.throwState)
        assertTrue(g.lastPower > 0.3f)
    }

    @Test fun touchSlingshotThrows() {
        val (g, input) = newGame()
        tap(g, input, g.titleButton(1))
        assertEquals(ControlMode.TOUCH, g.controls)
        startPlaying(g, input)
        // Pull down 260 px and a little right, then let go.
        input.push(Input.DOWN, 180f, 300f, 0f)
        input.push(Input.MOVE, 190f, 420f, 0.1f)
        input.push(Input.MOVE, 200f, 560f, 0.2f)
        g.update(dt)
        val live = g.flickPower()
        assertTrue(live in 0.6f..0.8f, "live power $live")
        input.push(Input.UP, 200f, 560f, 0.3f)
        g.update(dt)
        assertEquals(Game.ThrowState.FLIGHT, g.throwState)
        assertEquals(live, g.lastPower, 0.01f)
        assertTrue(g.aimYaw > 0.02f, "aims right")
        // A shorter pull throws softer; the scale has a lot of room.
        run(g, 8f)
        input.push(Input.DOWN, 180f, 300f, g.clock)
        input.push(Input.MOVE, 180f, 400f, g.clock)
        input.push(Input.UP, 180f, 400f, g.clock)
        g.update(dt)
        assertTrue(g.lastPower in 0.15f..0.35f, "short pull ${g.lastPower}")
        // Pushing back up to the start cancels.
        run(g, 8f)
        input.push(Input.DOWN, 180f, 300f, g.clock)
        input.push(Input.MOVE, 180f, 450f, g.clock)
        input.push(Input.UP, 180f, 305f, g.clock)
        g.update(dt)
        assertEquals(Game.ThrowState.READY, g.throwState)
    }

    @Test fun touchGuidesFadeInLaterLevels() {
        val (g, input) = newGame()
        tap(g, input, g.titleButton(1))
        g.startLevel(2)
        assertEquals(0, g.hintLevel())
        g.startLevel(5)
        assertEquals(1, g.hintLevel())
        g.startLevel(8)
        assertEquals(2, g.hintLevel())
        g.startLevel(12)
        assertEquals(3, g.hintLevel())
        val (m, _) = newGame()
        m.startLevel(12)
        assertEquals(0, m.hintLevel(), "motion controls keep every guide")
    }

    @Test fun rendersScreenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        val (g, input) = newGame()
        fun shot(name: String) {
            val scale = 2f
            val img = BufferedImage((g.viewW * scale).toInt(), (g.viewH * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
            g.render(AwtGfx(img, scale))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        run(g, 1f)
        shot("1_title")
        tap(g, input, g.titleButton(3))
        run(g, 0.5f)
        shot("2_howto")
        tapMiddle(g, input)
        g.startLevel(5)
        run(g, 0.5f)
        shot("3_intro")
        tapMiddle(g, input)
        input.push(Input.DOWN, 180f, 450f, 0f)
        g.update(dt)
        spin(g.detector, 0L, 0.3f, floatArrayOf(1f, 0f, 0f)) { -3f }
        run(g, 0.3f)
        shot("4_drawn")
        val (p, yaw) = perfectThrow(g)
        g.detector.enabled = false
        g.throwJavelin(p, yaw)
        run(g, 0.5f)
        shot("5_flight")
        run(g, 1.4f)
        shot("6_flight_late")
        repeat(400) { if (g.throwState == Game.ThrowState.FLIGHT) g.update(dt) }
        run(g, 0.2f)
        shot("7_landed")
        run(g, 3f)
        shot("8_ready_again")
        g.startLevel(12)
        run(g, 0.5f)
        shot("9_intro12")
        tapMiddle(g, input)
        run(g, 1f)
        shot("10_level12")
    }
}
