package com.goalrush.game

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameTest {
    private val dt = 1f / 60f
    private val viewH = 820f

    private fun newMatch(level: Int, seed: Int = 1): Match {
        val m = Match(level, rng = Random(seed))
        m.setupCamera(viewH)
        m.touchDown(0f, 0f, 0f) // skip the intro card
        assertEquals(Match.Phase.AIM, m.phase)
        return m
    }

    /** Swipes from the ball to the screen position of [target], bowing sideways by [bowPx]. */
    private fun swipe(m: Match, target: Vec3, seconds: Float = 0.18f, bowPx: Float = 0f) {
        val b = m.cam.project(m.ball.pos, Proj())
        val t = m.cam.project(target, Proj())
        val n = 12
        val dx = t.x - b.x; val dy = t.y - b.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val nx = -dy / len; val ny = dx / len
        for (i in 0..n) {
            val u = i / n.toFloat()
            val bow = 4f * u * (1f - u) * bowPx
            val x = b.x + dx * u + nx * bow
            val y = b.y + dy * u + ny * bow
            when (i) {
                0 -> m.touchDown(x, y, 0f)
                n -> m.touchUp(x, y, seconds)
                else -> m.touchMove(x, y, seconds * u)
            }
        }
    }

    private fun play(m: Match): Match.Result {
        var guard = 0
        while (m.phase == Match.Phase.FLIGHT && guard++ < 600) m.update(dt)
        return m.result
    }

    @Test fun swipeSendsBallWhereTheFingerEnds() {
        for (level in listOf(0, 2, 5, 7)) {
            val m = newMatch(level)
            val aim = Vec3(2.2f, 1.5f, 0f)
            swipe(m, aim)
            val k = assertNotNull(m.kick, "swipe on level $level should shoot")
            val c = assertNotNull(ShotSolver.crossing(m.start, k))
            assertTrue(abs(c.x - aim.x) < 0.1f && abs(c.y - aim.y) < 0.1f, "level $level crossed at $c, aimed $aim")
        }
    }

    @Test fun curvedSwipeCurlsTheBallButStillHitsTheSpot() {
        val m = newMatch(3)
        val aim = Vec3(2.5f, 1.6f, 0f)
        swipe(m, aim, bowPx = -60f) // bows to the left on screen
        val k = assertNotNull(m.kick)
        assertTrue(k.curl < -0.3f, "curl ${k.curl}")
        val c = assertNotNull(ShotSolver.crossing(m.start, k))
        assertTrue(abs(c.x - aim.x) < 0.1f && abs(c.y - aim.y) < 0.1f, "crossed at $c")
        // Halfway, the ball is left of the straight line, like the finger was.
        val b = Ball(); b.reset(m.start); b.vel = k.velocity; b.spin = k.spin
        repeat((k.time / 2 * 240).toInt()) { b.step(1f / 240f) }
        val u = (b.pos.z - m.start.z) / (aim.z - m.start.z)
        val straightX = m.start.x + (aim.x - m.start.x) * u
        assertTrue(b.pos.x < straightX - 0.5f, "mid x ${b.pos.x} vs straight $straightX")
    }

    @Test fun fasterSwipeKicksHarder() {
        val slow = newMatch(0).also { swipe(it, Vec3(-2f, 1f, 0f), seconds = 0.6f) }.kick!!
        val fast = newMatch(0).also { swipe(it, Vec3(-2f, 1f, 0f), seconds = 0.1f) }.kick!!
        assertTrue(fast.velocity.len() > slow.velocity.len() + 5f, "${fast.velocity.len()} vs ${slow.velocity.len()}")
    }

    @Test fun shortSwipeIsALowShotAndDownSwipeIsIgnored() {
        val m = newMatch(0)
        val b = m.cam.project(m.ball.pos, Proj())
        m.touchDown(b.x, b.y, 0f); m.touchMove(b.x + 5f, b.y - 30f, 0.05f); m.touchUp(b.x + 10f, b.y - 60f, 0.1f)
        val k = assertNotNull(m.kick)
        assertTrue(k.target.y < 0.2f, "low shot ${k.target}")
        val m2 = newMatch(0)
        m2.touchDown(b.x, b.y, 0f); m2.touchUp(b.x, b.y + 80f, 0.1f)
        assertNull(m2.kick)
        assertEquals(Match.Phase.AIM, m2.phase)
    }

    @Test fun shotsScoreMissAndGetSaved() {
        // Way wide.
        var m = newMatch(0)
        swipe(m, Vec3(7f, 1f, 0f))
        assertEquals(Match.Result.WIDE, play(m))
        // Over the bar.
        m = newMatch(0)
        swipe(m, Vec3(0f, 4f, 0f))
        assertEquals(Match.Result.OVER, play(m))
        // Into the corner: too far for the keeper unless he guessed right.
        var goals = 0
        for (seed in 1..10) {
            m = newMatch(0, seed)
            swipe(m, Vec3(-3.2f, 2.1f, 0f), seconds = 0.12f)
            if (play(m) == Match.Result.GOAL) goals++
        }
        assertTrue(goals >= 6, "corner goals $goals")
        // Straight at a good keeper.
        var saves = 0
        for (seed in 1..10) {
            m = newMatch(1, seed)
            swipe(m, Vec3(0.2f, 1.0f, 0f), seconds = 0.3f)
            if (play(m) == Match.Result.SAVED) saves++
        }
        assertTrue(saves >= 5, "saves $saves")
    }

    @Test fun everyLevelCanBeBeaten() {
        for ((i, lv) in Levels.all.withIndex()) {
            var goals = 0
            var tries = 0
            val aims = lv.targets.map { Vec3(it.x, it.y, 0f) }.ifEmpty {
                listOf(Vec3(-3.1f, 2.0f, 0f), Vec3(3.1f, 2.0f, 0f), Vec3(-3.1f, 0.4f, 0f), Vec3(3.1f, 0.4f, 0f))
            }
            for (aim in aims) for (bow in floatArrayOf(0f, -70f, 70f)) for (seed in 1..3) {
                val m = newMatch(i, seed)
                // Aim into the wind.
                swipe(m, Vec3(aim.x - lv.wind * 0.35f, aim.y, 0f), seconds = 0.12f, bowPx = bow)
                tries++
                if (play(m) == Match.Result.GOAL) goals++
            }
            println("Level ${i + 1} ${lv.name}: $goals/$tries goals")
            assertTrue(goals * 3 >= tries / 4, "level ${lv.name} too hard: $goals/$tries")
        }
    }

    @Test fun matchEndsAndCountsStars() {
        val m = newMatch(0)
        repeat(Levels.all[0].shots) {
            if (m.phase == Match.Phase.DONE) return@repeat
            swipe(m, Vec3(9f, 1f, 0f))
            play(m)
            repeat(200) { m.update(dt) }
        }
        assertEquals(Match.Phase.DONE, m.phase)
        assertEquals(0, m.goals)
        assertEquals(0, m.stars())
        assertTrue(m.hopeless)
    }

    @Test fun saveSlotsAndHighScores() {
        val store = Storage.Memory()
        val s = SaveData(store)
        assertTrue(!s.exists(0))
        s.create(0)
        assertEquals(0, s.unlocked(0))
        assertTrue(s.finishLevel(0, 0, 900, 2, passed = true))
        assertEquals(1, s.unlocked(0))
        assertTrue(!s.finishLevel(0, 0, 500, 1, passed = true))
        assertEquals(900, s.best(0, 0))
        assertEquals(2, s.stars(0, 0))
        s.saveSession(0, Session(1, 2, 1, 300, 1))
        assertEquals(Session(1, 2, 1, 300, 1), SaveData(store).session(0))
        s.create(1)
        s.finishLevel(1, 0, 1500, 3, passed = true)
        assertEquals(listOf(1500, 900, 500), SaveData(store).highScores().map { it.score })
        assertEquals(1, s.highScores()[0].slot)
        s.clear(0)
        assertTrue(!s.exists(0))
        assertEquals(0, s.best(0, 0))
        assertNull(s.session(0))
        // Failing a level doesn't unlock the next.
        s.create(2)
        s.finishLevel(2, 0, 100, 0, passed = false)
        assertEquals(0, s.unlocked(2))
        repeat(15) { s.addHighScore(HighScore(it * 10 + 1, 0, 2)) }
        assertEquals(SaveData.TOP, s.highScores().size)
    }

    @Test fun resumingKeepsProgress() {
        val m = Match(2, resume = Session(2, 3, 2, 450, 2))
        assertEquals(3, m.shot)
        assertEquals(2, m.goals)
        assertEquals(450, m.score)
        assertEquals(listOf(true, true, false), m.history)
    }

    // ---- whole-game flow with screenshots ----

    private fun tap(g: Game, id: String) {
        val b = g.buttons().first { it.id == id }
        g.touch(Game.DOWN, b.x + b.w / 2, b.y + b.h / 2, 0f)
        g.touch(Game.UP, b.x + b.w / 2, b.y + b.h / 2, 0.05f)
        g.update(dt)
    }

    private fun shot(g: Game, name: String) {
        val scale = 2f
        val img = BufferedImage((VIEW_W * scale).toInt(), (g.viewH * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
        g.render(AwtGfx(img, scale))
        val dir = File("build/screens").apply { mkdirs() }
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    private class FakeUpdater : Updater {
        override val currentVersion = "1.0 (5)"
        override var status = Updater.Status.IDLE
        override var latestVersion = ""
        override var message = ""
        override var progress = 0f
        var installs = 0
        override fun check() { status = Updater.Status.AVAILABLE; latestVersion = "1.0 (7)"; message = "Build 7 is ready." }
        override fun install() { installs++; status = Updater.Status.DOWNLOADING; progress = 0.4f }
    }

    @Test fun menusSavesAndScreenshots() {
        val store = Storage.Memory()
        val up = FakeUpdater()
        val g = Game(store = store, updater = up, rng = Random(4))
        g.viewH = viewH
        repeat(30) { g.update(dt) }
        shot(g, "01_title")
        tap(g, "update")
        assertEquals(Game.Screen.UPDATE, g.screen)
        shot(g, "02_update")
        tap(g, "install")
        assertEquals(1, up.installs)
        tap(g, "back")
        tap(g, "play")
        assertEquals(Game.Screen.SLOTS, g.screen)
        tap(g, "slot0")
        assertEquals(Game.Screen.LEVELS, g.screen)
        assertTrue(SaveData(store).exists(0))
        tap(g, "lvl0")
        assertEquals(Game.Screen.PLAY, g.screen)
        repeat(20) { g.update(dt) }
        shot(g, "03_intro")
        g.touch(Game.DOWN, 200f, 600f, 0f); g.touch(Game.UP, 200f, 600f, 0.01f)
        repeat(40) { g.update(dt) }
        shot(g, "04_aim")
        val m = g.match!!
        // Shoot through the game's own touch handling.
        val b = m.cam.project(m.ball.pos, Proj())
        val t = m.cam.project(Vec3(-3.2f, 2.1f, 0f), Proj())
        for (i in 0..10) {
            val u = i / 10f
            val x = b.x + (t.x - b.x) * u; val y = b.y + (t.y - b.y) * u
            g.touch(if (i == 0) Game.DOWN else if (i == 10) Game.UP else Game.MOVE, x, y, 1f + u * 0.12f)
            if (i == 6) { g.update(dt); shot(g, "05_swipe") }
        }
        g.update(dt)
        assertEquals(Match.Phase.FLIGHT, m.phase)
        repeat(16) { g.update(dt) }
        shot(g, "06_flight")
        while (m.phase == Match.Phase.FLIGHT) g.update(dt)
        repeat(20) { g.update(dt) }
        shot(g, "07_outcome_${m.result}")
        repeat(130) { g.update(dt) }
        assertEquals(Match.Phase.AIM, m.phase)
        // Autosaved after the shot.
        assertEquals(1, SaveData(store).session(0)?.shot)
        tap(g, "pause")
        shot(g, "08_pause")
        tap(g, "savequit")
        assertEquals(Game.Screen.LEVELS, g.screen)
        shot(g, "09_levels")
        tap(g, "resume")
        assertEquals(Game.Screen.PLAY, g.screen)
        assertEquals(1, g.match!!.shot)
        // Finish the level by missing.
        val m2 = g.match!!
        while (m2.phase != Match.Phase.DONE) {
            if (m2.phase == Match.Phase.INTRO) { g.touch(Game.DOWN, 200f, 600f, 0f); g.touch(Game.UP, 200f, 600f, 0f) }
            if (m2.phase == Match.Phase.AIM) {
                val bp = m2.cam.project(m2.ball.pos, Proj())
                g.touch(Game.DOWN, bp.x, bp.y, 5f); g.touch(Game.MOVE, bp.x + 80f, bp.y - 100f, 5.05f)
                g.touch(Game.UP, bp.x + 200f, bp.y - 300f, 5.1f)
            }
            g.update(dt)
        }
        repeat(100) { g.update(dt) }
        assertEquals(Game.Screen.RESULT, g.screen)
        shot(g, "10_result")
        tap(g, "levels")
        tap(g, "back")
        shot(g, "11_slots")
        tap(g, "back")
        tap(g, "scores")
        shot(g, "12_scores")
        assertTrue(g.save.highScores().isNotEmpty())
    }

    @Test fun screenshotsOfEveryLevel() {
        for (i in Levels.all.indices) {
            val store = Storage.Memory()
            SaveData(store).apply { create(0); repeat(Levels.all.size) { finishLevel(0, it, 0, 0, true) } }
            val g = Game(store = store, rng = Random(2))
            g.viewH = viewH
            g.update(dt)
            tap(g, "play"); tap(g, "slot0"); tap(g, "lvl$i")
            g.touch(Game.DOWN, 200f, 600f, 0f); g.touch(Game.UP, 200f, 600f, 0f)
            repeat(30) { g.update(dt) }
            shot(g, "level_%02d".format(i + 1))
        }
    }

    @Test fun soundsRender() {
        for (s in Sound.entries) assertTrue(Synth.render(s).isNotEmpty())
        assertTrue(Synth.crowdLoop().size > Synth.RATE)
    }
}
