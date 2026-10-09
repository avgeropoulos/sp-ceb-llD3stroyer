package com.roccoquest.game

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameTest {
    private val dt = 1f / 60f

    private fun newGame(): Game {
        val g = Game(Input())
        g.viewW = 427f
        g.input.tap = true
        repeat(30) { g.update(dt) } // title -> intro
        g.input.tap = true
        g.update(dt)
        assertEquals(Game.State.INTRO, g.state)
        repeat(140) { g.update(dt) }
        assertEquals(Game.State.PLAYING, g.state)
        return g
    }

    private fun run(g: Game, seconds: Float) = repeat((seconds * 60).toInt()) { g.update(dt) }

    @Test fun spritesAndLevelsLoad() {
        assertTrue(Sprites.fireStand.h == 24)
        for (def in Levels.all) {
            assertTrue(def.rows.size == LEVEL_ROWS)
            assertTrue(def.rows.all { it.length == def.rows[0].length })
            assertTrue(def.rows.any { '@' in it }, "${def.name} has a start")
        }
        assertTrue(Levels.all[0].rows.any { 'F' in it })
        assertTrue(Levels.all[1].rows.any { 'E' in it })
        assertTrue(Levels.all[2].rows.any { 'K' in it } && Levels.all[2].rows.any { 'R' in it })
    }

    @Test fun heroWalksAndJumps() {
        val g = newGame()
        val startX = g.hero.x
        val groundY = g.hero.y
        g.input.right = true
        run(g, 0.5f)
        assertTrue(g.hero.x > startX + 20)
        g.input.jump = true
        run(g, 0.3f)
        assertTrue(g.hero.y < groundY - 50, "jumped to ${g.hero.y}")
        g.input.jump = false
        run(g, 1f)
        assertTrue(g.hero.onGround)
    }

    @Test fun powerBlockGivesFireAndFireballs() {
        val g = newGame()
        g.entities.removeAll { it is Enemy }
        // Stand under the 'P' block at column 21, row 9.
        g.hero.x = 21f * TILE + 2
        g.input.jump = true
        run(g, 0.5f)
        g.input.jump = false
        run(g, 1.2f)
        assertTrue(g.entities.any { it is Blossom }, "blossom spawned")
        // Hop up next to it so we touch it.
        val b = g.entities.first { it is Blossom }
        g.hero.x = b.x
        g.hero.y = b.y
        run(g, 0.1f)
        assertEquals(Power.FIRE, g.hero.power)
        g.input.fire = true
        g.update(dt)
        assertTrue(g.entities.any { it is Fireball })
    }

    @Test fun stompingGrumbler() {
        val g = newGame()
        val e = g.entities.first { it is Grumbler } as Grumbler
        g.hero.x = e.x - 40
        run(g, 0.05f)
        g.hero.x = e.x
        g.hero.y = e.y - 30
        g.hero.vy = 100f
        run(g, 0.3f)
        assertTrue(e.isSquashed || e.removed)
        assertEquals(Game.State.PLAYING, g.state)
    }

    @Test fun bossFightAndRescue() {
        val g = newGame()
        g.startLevel(2)
        run(g, 2.5f)
        val krag = g.entities.first { it is Krag } as Krag
        // Walk up to the bridge with fire power.
        g.hero.power = Power.FIRE
        g.hero.x = 86f * TILE
        g.hero.y = 10f * TILE - g.hero.h
        run(g, 0.5f)
        assertTrue(krag.active)
        while (!g.bossDefeated) krag.fireHit(g)
        run(g, 3f)
        // Touch the axe, then the princess.
        g.hero.invuln = 10f
        g.hero.x = 105f * TILE
        g.hero.y = 10f * TILE - g.hero.h
        run(g, 0.1f)
        assertEquals(Game.State.AXE, g.state)
        run(g, 5f)
        assertEquals(Game.State.PLAYING, g.state)
        g.hero.x = 121.5f * TILE
        run(g, 0.2f)
        assertEquals(Game.State.VICTORY, g.state)
    }

    @Test fun axeDropsKragIntoLava() {
        val g = newGame()
        g.startLevel(2)
        run(g, 2.5f)
        g.hero.invuln = 100f
        g.hero.x = 90f * TILE
        g.hero.y = 10f * TILE - g.hero.h
        run(g, 0.3f)
        g.hero.x = 105f * TILE
        g.hero.y = 10f * TILE - g.hero.h
        run(g, 0.1f)
        assertEquals(Game.State.AXE, g.state)
        run(g, 6f)
        assertTrue(g.bossDefeated)
    }

    @Test fun flagpoleAdvancesLevel() {
        val g = newGame()
        g.hero.x = 192f * TILE
        g.hero.y = 4f * TILE
        g.input.right = true
        run(g, 2f)
        g.input.right = false
        assertEquals(Game.State.FLAG, g.state)
        run(g, 6f)
        assertEquals(1, g.levelIndex)
    }

    /** Renders a few frames to build/screens for eyeballing. */
    @Test fun screenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(g: Game, name: String) {
            val scale = 3f
            val img = BufferedImage((g.viewW * scale).toInt(), (VIEW_H * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
            g.render(AwtGfx(img, scale))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        val title = Game(Input()).apply { viewW = 427f }
        title.update(dt)
        shot(title, "0-title")
        val g = newGame()
        shot(g, "1-start")
        g.hero.x = 18f * TILE
        run(g, 0.2f)
        shot(g, "1-blocks")
        g.hero.x = 60f * TILE
        g.hero.invuln = 0f
        run(g, 0.3f)
        shot(g, "1-pipes")
        g.hero.x = 188f * TILE
        g.hero.y = 3f * TILE
        run(g, 0.5f)
        shot(g, "1-end")
        g.startLevel(1)
        shot(g, "2-intro")
        run(g, 2.5f)
        g.hero.x = 40f * TILE
        run(g, 0.3f)
        shot(g, "2-caverns")
        g.hero.x = 165f * TILE
        g.hero.y = 12f * TILE
        run(g, 0.3f)
        shot(g, "2-end")
        g.startLevel(2)
        run(g, 2.5f)
        g.hero.power = Power.FIRE
        shot(g, "3-castle")
        g.hero.x = 88f * TILE
        g.hero.y = 10f * TILE - g.hero.h
        g.hero.invuln = 100f
        run(g, 1.8f)
        g.input.fire = true
        g.update(dt)
        g.input.fire = false
        run(g, 0.1f)
        shot(g, "3-boss")
        g.hero.x = 117f * TILE
        g.hero.y = 10f * TILE - g.hero.h
        run(g, 0.5f)
        shot(g, "3-princess")
    }
}
