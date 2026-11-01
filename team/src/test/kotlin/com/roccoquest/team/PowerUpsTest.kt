package com.roccoquest.team

import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Storage
import com.roccoquest.game.TILE
import com.roccoquest.game.tileOf
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Wonder power-ups: Elephant, Drill and Bubble Rocco. */
class PowerUpsTest {
    private val dt = 1f / 60f
    private val input = TeamInput()
    private val sounds = ArrayList<Sound>()
    // (The one-time File 1 welcome-back gift is already given, so it doesn't change the tests.)
    private val storage = Storage.Memory().apply { save("gift1", 1); save("gift2", 1) }
    private val game = TeamGame(input, SoundSink { sounds += it }, storage)

    private fun run(seconds: Float, each: () -> Unit = {}) = repeat((seconds / dt).toInt()) { each(); game.update(dt) }

    private fun startPlaying(level: Int = 0) {
        game.newGame(0, Mon.SPARKY)
        if (level > 0) game.startLevel(level)
        run(3.2f)
        assertEquals(TeamGame.State.PLAY, game.state)
        game.foes.clear()
    }

    private fun press() {
        input.action = true; game.update(dt); input.action = false
    }

    /** Puts Rocco on the ground with his right edge just before column [tx], facing right. */
    private fun standBefore(tx: Int) {
        val r = game.rocco
        r.x = tx * TILE - r.w - 1f
        r.facing = 1
        r.vx = 0f
        run(0.4f) { game.hurtProof() }
        r.x = tx * TILE - r.w - 1f
        assertTrue(r.onGround)
    }

    @Test fun powerBlocksAreSpreadThroughTheLevels() {
        val withBlock = Levels.all.indices.filter { i -> Levels.all[i].rows.any { PowerUps.CHAR in it } }
        assertTrue(withBlock.size > Levels.all.size / 3, "power blocks in ${withBlock.size} levels")
        assertFalse(0 in withBlock, "the first level stays simple")
        val kinds = HashSet<Power>()
        for (i in withBlock) {
            val d = Levels.all[i]
            assertEquals(1, d.rows.sumOf { r -> r.count { it == PowerUps.CHAR } }, "${d.name}: one power block")
            val y = d.rows.indexOfFirst { PowerUps.CHAR in it }
            val x = d.rows[y].indexOf(PowerUps.CHAR)
            // There's room under it to jump up and bonk it.
            assertTrue(d.rows[y + 1][x] in ".c" && d.rows[y + 2][x] in ".c", "${d.name}: room under the block")
            kinds += PowerUps.at(x, i)
        }
        assertEquals(PowerUps.ALL.toSet(), kinds, "all three come out somewhere")
        // The same block always holds the same power.
        assertEquals(PowerUps.at(37, 5), PowerUps.at(37, 5))
    }

    @Test fun bonkingAPowerBlockGivesThePowerUp() {
        val i = Levels.all.indices.first { i -> Levels.all[i].rows.any { PowerUps.CHAR in it } }
        startPlaying(i)
        val lv = game.level
        val (bx, by) = (0 until lv.w).flatMap { x -> (0 until ROWS).map { x to it } }.first { (x, y) -> lv[x, y] == Tile.QPOWER }
        val r = game.rocco
        r.x = bx * TILE + 2f
        r.y = (by + 1) * TILE + 1f
        r.vy = -250f
        game.update(dt)
        assertEquals(Tile.USED, lv[bx, by], "bonked")
        val item = game.thingsForTest().filterIsInstance<PowerItem>().single()
        assertEquals(PowerUps.at(bx, i), item.power)
        run(0.2f) { game.hurtProof() }
        r.x = item.x; r.y = item.y - 4f; r.vy = 0f
        run(0.2f) { game.hurtProof() }
        assertEquals(item.power, game.power, "grabbed it")
        assertTrue(Sound.POWERUP in sounds)
    }

    @Test fun eachPowerCanBePickedUpAndAHitKnocksItOff() {
        for (p in PowerUps.ALL) {
            startPlaying()
            // Rocco has the Fire Flower on.
            game.inventory[Ore.GOLD.ordinal] = 5
            game.craft(game.recipes(0).indexOfFirst { it.icon == Power.FIRE }, 0)
            assertEquals(Power.FIRE, game.power)
            val r = game.rocco
            game.spawnForTest(PowerItem(r.x, r.y + 4f, p))
            run(0.3f)
            assertEquals(p, game.power, "picked up $p")
            assertTrue(game.flowerLevel[p.ordinal] >= 1, "and it's on the crafting table now")
            // A hit knocks the power-up off instead of costing a heart: back to the Fire Flower.
            val hearts = game.hearts
            game.noInvulnForTest()
            game.hurt()
            assertEquals(Power.FIRE, game.power, "$p was knocked off")
            assertEquals(hearts, game.hearts, "no heart lost")
            // The next hit costs a heart as usual.
            game.noInvulnForTest()
            game.hurt()
            assertEquals(hearts - 1, game.hearts)
            // It's free to put back on at the crafting table.
            val tab = TeamGame.TABS.indexOf("WONDER")
            val row = game.recipes(tab).indexOfFirst { it.icon == p }
            assertEquals("EQUIP", game.recipes(tab)[row].button)
            game.craft(row, tab)
            assertEquals(p, game.power)
        }
    }

    @Test fun theWonderTabMakesThem() {
        startPlaying()
        val tab = TeamGame.TABS.indexOf("WONDER")
        assertEquals(PowerUps.ALL, game.recipes(tab).map { it.icon })
        game.craft(game.recipes(tab).indexOfFirst { it.icon == Power.ELEPHANT }, tab)
        assertEquals(Power.NONE, game.power, "no ruby, no fruit")
        game.inventory[Ore.RUBY.ordinal] = 10
        game.inventory[Ore.IRON.ordinal] = 5
        game.inventory[Ore.SAPPHIRE.ordinal] = 5
        game.craft(game.recipes(tab).indexOfFirst { it.icon == Power.ELEPHANT }, tab)
        assertEquals(Power.ELEPHANT, game.power)
        game.craft(game.recipes(tab).indexOfFirst { it.icon == Power.ELEPHANT }, tab)
        assertEquals(2, game.flowerLevel[Power.ELEPHANT.ordinal], "levels up like a flower")
        game.craft(game.recipes(tab).indexOfFirst { it.icon == Power.DRILL }, tab)
        assertEquals(Power.DRILL, game.power)
        game.craft(game.recipes(tab).indexOfFirst { it.icon == Power.BUBBLE }, tab)
        assertEquals(Power.BUBBLE, game.power)
    }

    @Test fun elephantTrunkWhacksBadGuysAndSmashesBricks() {
        startPlaying()
        game.givePowerUp(Power.ELEPHANT)
        val r = game.rocco
        val tx = tileOf(r.x) + 3
        standBefore(tx)
        assertEquals("TRUNK", PowerUps.button(game.power))
        val g = Grumbler(r.x + r.w + 4f, r.bottom, false).also { it.active = true; it.vx = 0f }
        game.foes += g
        press()
        assertTrue(g.dying, "WHAM!")
        run(0.6f) { game.hurtProof() }
        // A brick right in front of his trunk gets smashed.
        val ty = tileOf(r.cy)
        game.level[tx, ty] = Tile.BRICK
        r.x = tx * TILE - r.w - 1f
        press()
        assertEquals(Tile.EMPTY, game.level[tx, ty], "smashed")
        assertTrue(Sound.BREAK in sounds)
        // Stone too (with the stone to show for it), but plain ground stays.
        run(0.6f) { game.hurtProof() }
        game.level[tx, ty] = Tile.STONE
        r.x = tx * TILE - r.w - 1f
        val stone = game.inventory[Ore.STONE.ordinal]
        press()
        assertEquals(Tile.EMPTY, game.level[tx, ty])
        assertEquals(stone + 1, game.inventory[Ore.STONE.ordinal])
        run(0.6f) { game.hurtProof() }
        game.level[tx, ty] = Tile.GROUND
        r.x = tx * TILE - r.w - 1f
        press()
        assertEquals(Tile.GROUND, game.level[tx, ty])
    }

    @Test fun drillDrillsUpThroughBlocksAndStompsSpikes() {
        startPlaying()
        game.givePowerUp(Power.DRILL)
        val r = game.rocco
        val tx = tileOf(r.x) + 3
        standBefore(tx)
        r.x = tx * TILE + 2f
        run(0.1f) { game.hurtProof() }
        // Two stone blocks stacked over his head: he drills right up through both.
        val ty = tileOf(r.y) - 2
        game.level[tx, ty] = Tile.STONE
        game.level[tx, ty - 1] = Tile.STONE
        val stone = game.inventory[Ore.STONE.ordinal]
        run(0.6f) { input.jump = true; game.hurtProof() }
        input.jump = false
        assertEquals(Tile.EMPTY, game.level[tx, ty], "drilled through")
        assertEquals(Tile.EMPTY, game.level[tx, ty - 1], "and the one above it")
        assertEquals(stone + 2, game.inventory[Ore.STONE.ordinal])
        run(1f) { game.hurtProof() }
        assertTrue(r.onGround)

        // A spiky Spikestack can't hurt Drill Rocco from above: he drills it.
        val spikes = Spikestack(r.x + 30f, r.bottom).also { it.active = true; it.vx = 0f }
        game.foes += spikes
        run(0.1f) { game.hurtProof() }
        val hearts = game.hearts
        game.noInvulnForTest()
        r.x = spikes.cx - r.w / 2; r.y = spikes.y - r.h - 6f; r.vy = 150f
        run(0.15f)
        assertEquals(2, spikes.hp, "one spiky ball drilled off")
        assertTrue(r.vy < 0f, "bounced up")
        assertEquals(hearts, game.hearts)
        assertEquals(Power.DRILL, game.power, "and no hit")
        game.foes.clear()
        run(1f) { game.hurtProof() }

        // DRILL spins and hits bad guys all around.
        val g = Grumbler(r.x - 20f, r.bottom, false).also { it.active = true; it.vx = 0f }
        game.foes += g
        game.hurtProof()
        press()
        assertTrue(g.hp < g.maxHp, "WHIRR")
        assertTrue(r.drillSpin > 0f)
        game.foes.clear()

        // Squatting on stone drills down through it, but plain ground stays put.
        run(1f) { game.hurtProof() }
        val gx = tileOf(r.cx)
        val gy = tileOf(r.bottom + 2f)
        assertEquals(Tile.GROUND, game.level[gx, gy])
        run(1f) { input.down = true; game.hurtProof() }
        input.down = false
        assertEquals(Tile.GROUND, game.level[gx, gy], "ground can't be drilled")
        game.level[gx, gy] = Tile.STONE
        run(0.6f) { input.down = true; game.hurtProof() }
        input.down = false
        assertEquals(Tile.EMPTY, game.level[gx, gy], "drilled down through the stone")
    }

    @Test fun bubblesPopBadGuysAndRoccoBouncesOnThem() {
        startPlaying()
        game.givePowerUp(Power.BUBBLE)
        val r = game.rocco
        r.facing = 1
        run(0.3f) { game.hurtProof() }
        val g = Grumbler(r.x + 50f, r.bottom, false).also { it.active = true; it.vx = 0f }
        game.foes += g
        val coins = game.coins
        press()
        assertNotNull(game.thingsForTest().firstOrNull { it is HeroBubble }, "a bubble")
        run(1f) { game.hurtProof() }
        assertTrue(g.dying || g.removed, "POP!")
        assertTrue(game.coins > coins, "it turned into coins")

        // A bubble right under Rocco as he falls: he bounces off it, and it pops (once each).
        run(3f) { game.hurtProof() }
        val b = HeroBubble(r.x - 1f, r.y - 40f, 1, 2)
        game.spawnForTest(b)
        game.update(dt)
        b.vx = 0f; b.vy = 0f
        r.y = b.y - r.h + 4f; r.vy = 150f
        run(0.1f) { game.hurtProof() }
        assertTrue(b.removed, "popped")
        assertTrue(r.vy < 0f, "BOING")
        assertTrue(game.thingsForTest().none { it === b })
    }

    @Test fun oldAndNewSavesKeepTheirPower() {
        // An old save (from before these existed) with the Gold Flower on still loads it.
        storage.save("slot1_level", 3)
        storage.save("slot1_power", Power.GOLD.ordinal)
        storage.save("slot1_flower_GOLD", 2)
        game.continueGame(1)
        assertEquals(Power.GOLD, game.power)
        assertEquals(0, game.flowerLevel[Power.ELEPHANT.ordinal])
        // A power-up is saved and comes back.
        startPlaying()
        game.givePowerUp(Power.BUBBLE)
        game.startLevel(0)
        val again = TeamGame(TeamInput(), SoundSink {}, storage)
        again.continueGame(0)
        assertEquals(Power.BUBBLE, again.power)
        assertEquals(1, again.flowerLevel[Power.BUBBLE.ordinal])
    }

    /** The three powered-up Roccos, Blocky's shirts and the items (build/screens/power-ups.png), plus each one in action. */
    @Test fun powerUpScreenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        val s = 4f
        val img = BufferedImage((330 * s).toInt(), (190 * s).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = AwtGfx(img, s)
        g.gradient(0f, 0f, 330f, 190f, 0xFF8FD0FF.toInt(), 0xFFD8F0FF.toInt())
        g.rect(0f, 168f, 330f, 22f, 0xFF5AB848.toInt())
        for ((i, p) in PowerUps.ALL.withIndex()) {
            val x0 = 10f + i * 108f
            g.text(p.label, x0 + 48f, 12f, 7f, 0xFF1A1A3A.toInt(), 1)
            // Standing, walking, jumping and facing the other way.
            for (f in 0 until 3) PowerArt.box(g, TeamArt.rocco(p, f), x0 + f * 24f, 48f, 16f, 24f)
            PowerArt.box(g, TeamArt.rocco(p, 0), x0 + 72f, 48f, 16f, 24f, flipX = true)
            // In action: the trunk swing, the drill spin, blowing a bubble.
            val ax = x0 + 20f
            when (p) {
                Power.ELEPHANT -> {
                    PowerArt.box(g, TeamArt.rocco(p, 0), ax, 92f, 16f, 24f)
                    PowerArt.trunk(g, ax + 8f, 92f, 1, 0.5f)
                }
                Power.DRILL -> {
                    PowerArt.box(g, Outline.of(PowerArt.drillRocco(0, 2)), ax, 92f, 16f, 24f)
                    PowerArt.spin(g, ax + 8f, 106f, 0.3f)
                }
                else -> {
                    PowerArt.box(g, TeamArt.rocco(p, 0), ax, 92f, 16f, 24f)
                    PowerArt.bubble(g, ax + 13f, 98f, 6f)
                    PowerArt.bubble(g, ax + 26f, 94f, 14f)
                    PowerArt.bubble(g, ax + 46f, 84f, 14f)
                }
            }
            g.sprite(TeamArt.blocky(p, false), x0 + 4f, 140f)
            g.sprite(TeamArt.blocky(p, true), x0 + 24f, 140f)
            PowerArt.item(g, p, x0 + 52f, 148f, 0.3f)
            g.sprite(TeamArt.flowerSprite(p), x0 + 76f, 148f)
        }
        ImageIO.write(img, "png", File(dir, "power-ups.png"))

        fun shot(name: String) {
            val im = BufferedImage((game.viewW * 3).toInt(), 240 * 3, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(im, 3f))
            ImageIO.write(im, "png", File(dir, "$name.png"))
        }
        // Out in level 1, a bit past the start (clear of the touch buttons).
        val r = game.rocco
        fun walkOut(p: Power) {
            startPlaying()
            game.givePowerUp(p)
            r.x = 16 * TILE.toFloat()
            r.y = 13 * TILE - r.h
            run(1.2f) { game.hurtProof() }
            r.facing = 1
        }
        // Elephant Rocco mid-swing at a Grumbler.
        walkOut(Power.ELEPHANT)
        game.foes += Grumbler(r.x + r.w + 30f, r.bottom, false).also { it.active = true; it.vx = 0f }
        press()
        run(0.12f) { game.hurtProof() }
        shot("power-elephant")
        // Drill Rocco spinning, with the three items next to him.
        walkOut(Power.DRILL)
        for ((i, p) in PowerUps.ALL.withIndex()) game.spawnForTest(PowerItem(r.x + 160f + i * 30f, r.y - 20f, p).also { it.vx = 0f })
        run(0.6f) { game.hurtProof() }
        press()
        run(0.1f) { game.hurtProof() }
        shot("power-drill")
        // Bubble Rocco blowing bubbles.
        walkOut(Power.BUBBLE)
        press()
        run(0.45f) { game.hurtProof() }
        press()
        run(0.1f) { game.hurtProof() }
        shot("power-bubble")
    }
}
