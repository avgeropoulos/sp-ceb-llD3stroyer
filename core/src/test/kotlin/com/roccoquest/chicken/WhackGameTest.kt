package com.roccoquest.chicken

import com.roccoquest.game.AwtGfx
import com.roccoquest.game.Storage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhackGameTest {
    private val dt = 1f / 60f

    private fun run(g: WhackGame, seconds: Float) = repeat((seconds * 60).toInt()) { g.update(dt) }

    private fun started(storage: Storage = Storage.Memory(), seed: Int = 1): WhackGame {
        val g = WhackGame(storage = storage, rng = Random(seed))
        g.viewH = 520f
        run(g, 0.5f)
        g.tap(120f, 300f)
        g.update(dt)
        assertEquals(WhackGame.State.PLAYING, g.state)
        return g
    }

    /** Waits until something of [kind] is up in a hole and returns that hole. */
    private fun waitFor(g: WhackGame, kind: WhackGame.Kind): Int {
        repeat(60 * 30) {
            g.update(dt)
            val i = g.poppers.indexOfFirst { it != null && it.kind == kind && it.hittable }
            if (i >= 0) return i
        }
        error("No $kind showed up")
    }

    @Test fun whackingTheChickenManScores() {
        val g = started()
        val i = waitFor(g, WhackGame.Kind.MAN)
        g.tap(g.holeX(i), g.targetY(i))
        g.update(dt)
        assertEquals(10, g.score)
        assertEquals(1, g.combo)
        assertEquals(WhackGame.Phase.HIT, g.poppers[i]!!.phase)
        // A dazed Chicken Man can't be hit twice.
        g.tap(g.holeX(i), g.targetY(i))
        g.update(dt)
        assertEquals(10, g.score)
        assertEquals(0, g.combo)
    }

    @Test fun missingBreaksTheComboAndCombosMultiply() {
        val g = started()
        repeat(8) {
            val i = waitFor(g, WhackGame.Kind.MAN)
            g.tap(g.holeX(i), g.targetY(i))
            g.update(dt)
        }
        assertEquals(8, g.combo)
        assertEquals(3, g.multiplier)
        // Four hits at x1, four at x2 (or golden bonuses on top).
        assertTrue(g.score >= 4 * 10 + 4 * 20, "score ${g.score}")
        // Tap empty grass far from any hole.
        g.tap(5f, 60f)
        g.update(dt)
        assertEquals(0, g.combo)
    }

    @Test fun realChickensCostTime() {
        val g = started()
        val i = waitFor(g, WhackGame.Kind.CHICKEN)
        val before = g.timeLeft
        val score = g.score
        g.tap(g.holeX(i), g.targetY(i))
        g.update(dt)
        assertTrue(g.timeLeft < before - WhackGame.CHICKEN_PENALTY + 0.1f)
        assertEquals(score, g.score)
    }

    @Test fun roundEndsAndHighScoresAreSaved() {
        val storage = Storage.Memory()
        val g = started(storage)
        repeat(3) {
            val i = waitFor(g, WhackGame.Kind.MAN)
            g.tap(g.holeX(i), g.targetY(i))
            g.update(dt)
        }
        val first = g.score
        run(g, WhackGame.ROUND_TIME + 1f)
        assertEquals(WhackGame.State.OVER, g.state)
        assertEquals(0, g.newRank)
        assertEquals(first, g.highScores[0])

        // Play again with a bigger score: it goes on top and the old one moves down.
        run(g, 1.5f)
        g.tap(120f, 300f)
        g.update(dt)
        assertEquals(WhackGame.State.PLAYING, g.state)
        repeat(6) {
            val i = waitFor(g, WhackGame.Kind.MAN)
            g.tap(g.holeX(i), g.targetY(i))
            g.update(dt)
        }
        val second = g.score
        assertTrue(second > first)
        run(g, WhackGame.ROUND_TIME + 1f)
        assertEquals(0, g.newRank)

        // A new game (like reopening the app) loads the table from storage.
        val reopened = WhackGame(storage = storage)
        assertEquals(listOf(second, first, 0, 0, 0), reopened.highScores.toList())
    }

    @Test fun zeroScoreIsNotAHighScore() {
        val g = started()
        run(g, WhackGame.ROUND_TIME + 1f)
        assertEquals(WhackGame.State.OVER, g.state)
        assertEquals(-1, g.newRank)
        assertEquals(0, g.highScores[0])
    }

    /** Renders a few frames to build/screens for eyeballing. */
    @Test fun screenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        val scale = 3f
        fun shot(g: WhackGame, name: String) {
            val img = BufferedImage((WHACK_W * scale).toInt(), (g.viewH * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
            g.render(AwtGfx(img, scale))
            ImageIO.write(img, "png", File(dir, "whack_$name.png"))
        }
        val title = WhackGame(rng = Random(3)).apply { viewH = 520f }
        run(title, 1.0f)
        shot(title, "title")

        val g = started(seed = 7)
        val i = waitFor(g, WhackGame.Kind.MAN)
        g.tap(g.holeX(i), g.targetY(i))
        repeat(4) { g.update(dt) }
        shot(g, "bonk")
        run(g, 20f)
        shot(g, "playing")
        waitFor(g, WhackGame.Kind.CHICKEN)
        shot(g, "chicken")

        run(g, WhackGame.ROUND_TIME)
        run(g, 1.5f)
        shot(g, "over")
    }
}
