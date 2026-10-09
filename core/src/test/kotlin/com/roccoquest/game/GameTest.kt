package com.roccoquest.game

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.min

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
        assertEquals(12, Levels.all.size)
        // Every level must have a way to finish it.
        for (def in Levels.all) {
            assertTrue(def.rows.any { r -> r.any { it in "FEMR" } }, "${def.name} has an exit")
        }
        assertTrue(Levels.all[3].rows.any { 'K' in it } && Levels.all[3].rows.any { 'M' in it })
        assertTrue(Levels.all[7].rows.any { 'K' in it } && Levels.all[7].rows.any { 'M' in it })
        assertTrue(Levels.all[11].rows.any { 'K' in it } && Levels.all[11].rows.any { 'R' in it })
        assertEquals(
            listOf("1-1", "1-2", "1-3", "1-4", "2-1", "2-2", "2-3", "2-4", "3-1", "3-2", "3-3", "3-4"),
            Levels.all.map { "${it.world}-${it.num}" },
        )
        for (i in listOf(6, 9, 10, 11)) assertTrue(Levels.all[i].rows.any { 'J' in it }, "Krag Jr. in ${Levels.all[i].name}")
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

    private fun collectFromBlock(g: Game, col: Int) {
        g.entities.removeAll { it is Enemy }
        g.hero.x = col * TILE + 2f
        g.hero.y = 13f * TILE - g.hero.h
        g.input.jump = true
        run(g, 0.5f)
        g.input.jump = false
        run(g, 0.8f)
        val item = g.entities.first { it is PowerItem }
        g.hero.x = item.x
        g.hero.y = item.y + item.h - g.hero.h
        run(g, 0.05f)
    }

    @Test fun mushroomThenBlazeBlossom() {
        val g = newGame()
        collectFromBlock(g, 21) // mushroom
        assertEquals(Power.BIG, g.hero.power)
        assertEquals(Hero.BIG_H, g.hero.h)
        collectFromBlock(g, 101) // Blaze Blossom
        assertEquals(Power.FIRE, g.hero.power)
        g.input.fire = true
        g.update(dt)
        g.input.fire = false
        assertTrue(g.entities.any { it is Fireball && !it.ice })
        // Getting hit drops back to big Rocco, then small.
        g.hero.invuln = 0f
        g.hurtHero()
        assertEquals(Power.BIG, g.hero.power)
        g.hero.invuln = 0f
        g.hurtHero()
        assertEquals(Power.SMALL, g.hero.power)
    }

    @Test fun doubleTapToRun() {
        val g = newGame()
        g.entities.removeAll { it is Enemy }
        g.input.right = true; run(g, 0.1f)
        g.input.right = false; run(g, 0.1f)
        g.input.right = true; run(g, 1f)
        assertTrue(g.hero.vx > Hero.MAX_SPEED + 30, "running at ${g.hero.vx}")
        g.input.right = false; run(g, 0.3f)
        g.input.right = true; run(g, 1f)
        assertTrue(g.hero.vx <= Hero.MAX_SPEED + 0.1f, "walking at ${g.hero.vx}")
    }

    @Test fun crouchShrinksBigRocco() {
        val g = newGame()
        g.hero.power = Power.BIG
        run(g, 0.3f)
        g.input.down = true
        run(g, 0.1f)
        assertTrue(g.hero.crouching)
        assertEquals(Hero.SMALL_H, g.hero.h)
        g.input.down = false
        run(g, 0.1f)
        assertEquals(Hero.BIG_H, g.hero.h)
    }

    @Test fun buttSlamSmashesBricks() {
        val g = newGame()
        g.entities.removeAll { it is Enemy }
        // Bricks floating at row 5, columns 80-87 in 1-1.
        g.hero.x = 82f * TILE + 2
        g.hero.y = 3f * TILE - g.hero.h
        g.hero.vy = 0f
        run(g, 0.05f)
        g.input.down = true
        g.update(dt)
        g.input.down = false
        assertTrue(g.hero.slamming)
        run(g, 1.2f)
        assertEquals(T.EMPTY, g.level[82, 5], "brick smashed")
        assertTrue(g.hero.onGround && !g.hero.slamming)
        assertEquals(13f * TILE, g.hero.bottom, 0.5f)
    }

    @Test fun iceFreezesAndBoomerangReturns() {
        val g = newGame()
        g.hero.power = Power.ICE
        val e = g.entities.first { it is Grumbler && it.y > 180f } as Grumbler
        g.hero.x = e.x - 70
        g.hero.facing = 1
        run(g, 0.05f)
        g.input.fire = true; g.update(dt); g.input.fire = false
        run(g, 1f)
        assertTrue(e.removed)
        val block = g.entities.firstOrNull { it is FrozenBlock }
        assertTrue(block != null, "enemy turned to ice")

        g.hero.power = Power.BOOM
        g.input.fire = true; g.update(dt); g.input.fire = false
        assertTrue(g.entities.any { it is Boomerang })
        run(g, 3.5f)
        assertTrue(g.entities.none { it is Boomerang }, "boomerang came back")
    }

    @Test fun starMakesRoccoInvincible() {
        val g = newGame()
        g.hero.starTime = Hero.STAR_TIME
        val e = g.entities.first { it is Grumbler } as Grumbler
        g.hero.x = e.x
        g.hero.y = e.y
        run(g, 0.05f)
        assertTrue(e.dying)
        assertEquals(Game.State.PLAYING, g.state)
        assertEquals(Power.SMALL, g.hero.power)
    }

    @Test fun miniRoccoIsTinyAndFloaty() {
        val g = newGame()
        g.entities.removeAll { it is Enemy }
        g.hero.power = Power.MINI
        assertEquals(Hero.MINI_H, g.hero.h)
        run(g, 0.3f)
        val ground = g.hero.y
        var peak = ground
        g.input.jump = true
        repeat(80) { g.update(dt); peak = min(peak, g.hero.y) }
        g.input.jump = false
        assertTrue(ground - peak > 90f, "mini jumps high: ${ground - peak}")
    }

    @Test fun kragJrLocksTheCameraUntilBeaten() {
        val g = newGame()
        g.startLevel(9)
        run(g, 2.5f)
        g.entities.removeAll { it is Enemy && it !is KragJr }
        val jr = g.entities.first { it is KragJr } as KragJr
        g.hero.invuln = 1000f
        g.hero.x = 136f * TILE
        g.hero.y = 13f * TILE - g.hero.h
        run(g, 0.5f)
        assertTrue(jr.engaged)
        val lockedCam = g.camX
        g.input.right = true
        run(g, 2f)
        assertEquals(lockedCam, g.camX, "camera locked during the fight")
        while (jr.hp > 0) { jr.damage(g, 2); run(g, 1.5f) }
        run(g, 3f)
        assertTrue(g.camX > lockedCam, "camera scrolls again")
        g.input.right = false
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
        g.startLevel(11)
        run(g, 2.5f)
        g.entities.removeAll { it is KragJr }
        val krag = g.entities.first { it is Krag } as Krag
        assertEquals(14, krag.maxHp)
        // Walk up to the bridge with fire power.
        g.hero.power = Power.FIRE
        g.hero.x = 99f * TILE
        g.hero.y = 10f * TILE - g.hero.h
        run(g, 0.5f)
        assertTrue(krag.active)
        while (!g.bossDefeated) krag.fireHit(g)
        run(g, 3f)
        // Touch the axe, then the princess.
        g.hero.invuln = 10f
        g.hero.x = 123f * TILE
        g.hero.y = 10f * TILE - g.hero.h
        run(g, 0.1f)
        assertEquals(Game.State.AXE, g.state)
        run(g, 5f)
        assertEquals(Game.State.PLAYING, g.state)
        g.hero.x = 179.5f * TILE
        run(g, 0.2f)
        assertEquals(Game.State.VICTORY, g.state)
    }

    @Test fun axeDropsKragIntoLava() {
        val g = newGame()
        g.startLevel(3)
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
        // Pip is waiting at the end of the first castle and sends Rocco on to World 2.
        g.hero.x = 121.5f * TILE
        g.hero.y = 10f * TILE - g.hero.h
        run(g, 0.2f)
        assertEquals(Game.State.WORLD_CLEAR, g.state)
        run(g, 6.1f)
        assertEquals(4, g.levelIndex)
        assertEquals(Game.State.INTRO, g.state)
    }

    @Test fun cloudsAreJumpThroughPlatforms() {
        val g = newGame()
        g.startLevel(2)
        run(g, 2.5f)
        g.entities.removeAll { it is Enemy }
        // Clouds at columns 16-20, row 11. Jump up through them from below...
        g.hero.x = 17f * TILE
        g.hero.y = 12f * TILE
        g.hero.vy = -400f
        run(g, 0.6f)
        // ...and land on top.
        assertTrue(g.hero.onGround)
        assertEquals(11f * TILE, g.hero.bottom, 0.5f)
    }

    @Test fun musicFollowsTheGame() {
        val played = ArrayList<Music>()
        val g = Game(Input(), object : SoundSink {
            override fun play(s: Sound) {}
            override fun music(m: Music) { played += m }
        })
        g.viewW = 427f
        g.update(dt)
        assertEquals(Music.OVERWORLD, played.last()) // title
        repeat(30) { g.update(dt) }
        g.input.tap = true
        g.update(dt)
        assertEquals(Music.NONE, played.last()) // level intro
        repeat(140) { g.update(dt) }
        assertEquals(Music.OVERWORLD, played.last())
        g.startLevel(1); run(g, 2.5f)
        assertEquals(Music.UNDERGROUND, played.last())
        g.startLevel(2); run(g, 2.5f)
        assertEquals(Music.SKY, played.last())
        g.startLevel(3); run(g, 2.5f)
        assertEquals(Music.CASTLE, played.last())
        g.hero.invuln = 100f
        g.hero.x = 88f * TILE
        g.hero.y = 10f * TILE - g.hero.h
        run(g, 0.5f)
        assertEquals(Music.BOSS, played.last())
        // Music button toggles it off and on.
        g.input.musicToggle = true; g.update(dt); g.input.musicToggle = false; g.update(dt)
        assertEquals(Music.NONE, played.last())
        g.input.musicToggle = true; g.update(dt); g.input.musicToggle = false; g.update(dt)
        assertEquals(Music.BOSS, played.last())
    }

    @Test fun songsRender() {
        val dir = File("build/music").apply { mkdirs() }
        for (m in Music.entries) {
            val song = Songs.of(m) ?: continue
            val pcm = Chiptune.render(song)
            val seconds = pcm.size / Chiptune.RATE.toFloat()
            assertTrue(seconds > 8f, "$m loop is ${seconds}s")
            assertTrue(pcm.count { kotlin.math.abs(it.toInt()) > 2000 } > pcm.size / 4, "$m is audible")
            val bytes = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bytes.putShort(it) }
            javax.sound.sampled.AudioSystem.write(
                javax.sound.sampled.AudioInputStream(
                    java.io.ByteArrayInputStream(bytes.array()),
                    javax.sound.sampled.AudioFormat(Chiptune.RATE.toFloat(), 16, 1, true, false),
                    pcm.size.toLong(),
                ),
                javax.sound.sampled.AudioFileFormat.Type.WAVE, File(dir, "${m.name.lowercase()}.wav"),
            )
        }
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
        for ((idx, col) in listOf(2 to 60, 4 to 78, 5 to 100, 6 to 84, 8 to 108, 9 to 60, 10 to 100)) {
            g.startLevel(idx)
            run(g, 2.5f)
            g.hero.invuln = 100f
            g.hero.x = col.toFloat() * TILE
            g.hero.y = 2f * TILE
            run(g, 0.6f)
            shot(g, "new-${Levels.all[idx].world}-${Levels.all[idx].num}")
        }
        g.startLevel(3)
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
        // Krag Jr. and the new power-ups
        g.startLevel(9)
        run(g, 2.5f)
        g.hero.power = Power.ICE
        g.hero.invuln = 1000f
        g.hero.x = 138f * TILE
        g.hero.y = 13f * TILE - g.hero.h
        run(g, 1.6f)
        shot(g, "jr-fight")
        g.hero.power = Power.BOOM
        g.hero.starTime = 5f
        g.input.fire = true; g.update(dt); g.input.fire = false
        run(g, 0.2f)
        shot(g, "boomerang-star")
        g.hero.x = 117f * TILE
        g.hero.y = 10f * TILE - g.hero.h
        run(g, 0.5f)
        shot(g, "3-princess")
    }
}
