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

    private fun tapMiddle(g: Game, input: Input) = tap(g, input, floatArrayOf(180f, 400f, 0f, 0f))

    private fun startPlaying(g: Game, input: Input) {
        tap(g, input, g.titleButton(0))
        assertEquals(Game.Screen.INTRO, g.screen)
        run(g, 0.5f)
        tapMiddle(g, input)
        assertEquals(Game.Screen.PLAY, g.screen)
    }

    /** Finds the power and aim that land on the target, leading a moving one, the way a perfect player would. */
    private fun perfectThrow(g: Game): Pair<Float, Float> {
        var (tx, tz) = g.target.x to g.target.z
        var power = 0f
        var yaw = 0f
        repeat(4) {
            yaw = atan2(tx - RELEASE_X, tz)
            var lo = 0f
            var hi = 1f
            repeat(30) {
                val mid = (lo + hi) / 2
                if (Javelin.landing(mid, yaw).second < tz) lo = mid else hi = mid
            }
            power = lo
            val (ax, az) = g.target.positionAt(flightTime(power, yaw) / Game.FLIGHT_SPEED)
            tx = ax; tz = az
        }
        return power to yaw
    }

    private fun flightTime(power: Float, yaw: Float): Float {
        val j = Javelin.launch(power, yaw)
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

    private fun throwWithGyro(d: ThrowDetector, peak: Float, axis: FloatArray = floatArrayOf(0.6f, 0f, 0.8f)): Float? {
        d.enabled = true
        d.grip(true)
        var t = spin(d, 0L, 0.1f, axis) { 0f }
        t = spin(d, t, 0.3f, axis) { -4f }                      // pull the arm back ~70°
        t = spin(d, t, 0.15f, axis) { 0f }                      // pause
        spin(d, t, 0.25f, axis) { s -> peak * sin(s / 0.25f * Math.PI.toFloat()) } // whip forward
        return d.takeThrow()
    }

    @Test fun pullingBackThenWhippingForwardThrows() {
        val d = ThrowDetector()
        val p = assertNotNull(throwWithGyro(d, 15f))
        assertTrue(p in 0.6f..0.9f, "power $p")
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
        assertTrue(weak in 6f..12f, "weakest throw $weak m")
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

    @Test fun everyLevelCanBeReached() {
        val maxRange = Javelin.landing(1f).second
        for (n in 1..40) {
            val s = levelSpec(n)
            assertTrue(s.distance * 1.1f + s.swayZ + s.radius < maxRange - 5f, "level $n too far")
            assertTrue(s.time >= 30f && s.hits >= 1 && s.radius >= 1.5f, "level $n: $s")
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

    @Test fun touchFlickThrows() {
        val (g, input) = newGame()
        startPlaying(g, input)
        // Switch to touch controls the way a player would: through the title screen.
        val (g2, input2) = newGame()
        tap(g2, input2, g2.titleButton(1))
        assertEquals(ControlMode.TOUCH, g2.controls)
        startPlaying(g2, input2)
        input2.push(Input.DOWN, 180f, 400f, 0f)
        input2.push(Input.MOVE, 100f, 440f, 0.1f)
        input2.push(Input.MOVE, 100f, 460f, 0.2f)
        input2.push(Input.MOVE, 105f, 400f, 0.25f)
        input2.push(Input.MOVE, 110f, 320f, 0.3f)
        input2.push(Input.UP, 112f, 300f, 0.32f)
        g2.update(dt)
        assertEquals(Game.ThrowState.FLIGHT, g2.throwState)
        assertTrue(g2.lastPower > 0.5f, "power ${g2.lastPower}")
        assertEquals(Game.ThrowState.READY, g.throwState)
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
