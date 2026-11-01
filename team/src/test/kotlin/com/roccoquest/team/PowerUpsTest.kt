package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Sprite
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
            // Knocked off means gone: it isn't a free EQUIP at the crafting table (or every hit could be undone),
            // it costs the ore again.
            val tab = TeamGame.TABS.indexOf("WONDER")
            val row = game.recipes(tab).indexOfFirst { it.icon == p }
            val recipe = game.recipes(tab)[row]
            assertEquals("MAKE", recipe.button)
            assertEquals(listOf(p.ore!! to TeamGame.COST), recipe.costs)
            game.inventory[p.ore!!.ordinal] = 0
            game.craft(row, tab)
            assertEquals(Power.FIRE, game.power, "no ore, no $p")
            game.inventory[p.ore!!.ordinal] = TeamGame.COST
            game.craft(row, tab)
            assertEquals(p, game.power)
            assertEquals(0, game.inventory[p.ore!!.ordinal], "paid for")
            assertEquals(2, game.flowerLevel[p.ordinal], "a new one levels it up")
            // Once paid for, switching away and back is free again (only a hit takes it away).
            game.craft(game.recipes(0).indexOfFirst { it.icon == Power.FIRE }, 0)
            assertEquals("EQUIP", game.recipes(tab)[row].button)
            // A power block's power-up puts it back on for free, too.
            game.noInvulnForTest()
            game.craft(row, tab)
            game.hurt()
            assertEquals("MAKE", game.recipes(tab)[row].button)
            game.spawnForTest(PowerItem(r.x, r.y + 4f, p))
            run(0.3f)
            assertEquals(p, game.power)
            assertEquals("Lv3", game.recipes(tab)[row].button)
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

    @Test fun drillNeverDrillsOutOfTheBottomOfTheLevel() {
        // The pirate ship's hull is planks with open sky under it: squatting on the deck must not drill a way out.
        val i = Levels.all.indexOfFirst { it.name == "PIRATE SHIP" }
        startPlaying(i)
        val lv = game.level
        fun floorUnder(x: Int, from: Int) = (from until lv.h).any { y -> Tile.solid(lv[x, y]) && lv.toughness(x, y) == 0 }
        // A deck column whose planks go all the way down with nothing under them (the bow or the stern).
        val tx = (1 until lv.w - 1).first { x ->
            val top = (2 until lv.h).firstOrNull { Tile.solid(lv[x, it]) } ?: return@first false
            lv[x, top] == Tile.PLANK && !Tile.solid(lv[x, top - 1]) && !Tile.solid(lv[x, top - 2]) && !floorUnder(x, top)
        }
        val top = (2 until lv.h).first { Tile.solid(lv[tx, it]) }
        game.givePowerUp(Power.DRILL)
        val r = game.rocco
        r.x = tx * TILE + 2f; r.y = top * TILE - r.h; r.vx = 0f; r.vy = 0f
        game.update(dt)
        assertTrue(r.onGround, "on the deck at column $tx")
        val hearts = game.hearts
        run(5f) { input.down = true; game.foes.clear(); game.noInvulnForTest() }
        input.down = false
        run(2f) { game.foes.clear() }
        assertEquals(TeamGame.State.PLAY, game.state)
        assertEquals(hearts, game.hearts, "never fell out")
        assertTrue(r.onGround && r.bottom <= lv.h * TILE, "still standing in the level")
    }

    @Test fun aFallNeverPutsRoccoBackOverAHole() {
        // If the safe spot got dug away (by a drill, a pickaxe or an earthquake), he comes back on solid ground.
        startPlaying()
        val r = game.rocco
        r.x = 16 * TILE + 2f; r.y = 13 * TILE - r.h
        run(0.5f) { game.hurtProof() }
        assertTrue(r.onGround)
        val sx = tileOf(game.safeXForTest() + 6f)
        for (x in sx - 1..sx + 1) for (y in 13 until ROWS) game.level[x, y] = Tile.EMPTY
        game.noInvulnForTest()
        val hearts = game.hearts
        run(3f) { game.foes.clear() }
        assertEquals(hearts - 1, game.hearts, "one fall, one heart")
        assertTrue(r.onGround, "and back on solid ground")
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

    @Test fun bubblesOnlyMakeCoinsWhereKnockingABadGuyOutWould() {
        // Krag Jr. and Puffclouds drop spike balls over and over: popping them must not be an endless coin farm.
        startPlaying()
        game.givePowerUp(Power.BUBBLE)
        val r = game.rocco
        r.facing = 1
        run(0.3f) { game.hurtProof() }
        // (The level's own coins are about too.)
        fun coinsOut() = game.thingsForTest().count { it is Coin }
        val placed = coinsOut()
        val coins = game.coins
        val ball = SpikeBall(r.x + 40f, r.bottom - 12f)
        game.foes += ball
        press()
        run(1f) { game.hurtProof() }
        assertTrue(ball.dying || ball.removed, "POP!")
        assertEquals(coins, game.coins, "no coins from a spike ball")
        assertEquals(placed, coinsOut())
        // A bubble makes coins just when knocking the bad guy out would: never for spike balls or bombs...
        val b = HeroBubble(r.x, r.y, 1, 2)
        for (f in listOf(SpikeBall(r.x + 60f, r.bottom - 12f), BombBomb(r.x + 90f, r.bottom))) {
            game.foes += f
            game.bubbleHit(b, f, 2)
        }
        assertEquals(placed, coinsOut(), "spike balls and bombs never drop a coin")
        // ...but an ordinary bad guy drops its coin, plus the bubble's.
        val g = Grumbler(r.x + 140f, r.bottom, false)
        game.foes += g
        game.bubbleHit(b, g, 2)
        assertTrue(g.dying)
        assertEquals(placed + 2, coinsOut())
    }

    /** Puts Rocco on flat ground out in the open in level 1 (past the steps at the start), facing right. */
    private fun standInTheOpen() {
        val r = game.rocco
        r.x = 16 * TILE.toFloat(); r.y = 13 * TILE - r.h; r.vx = 0f; r.vy = 0f
        run(0.4f) { game.hurtProof() }
        r.facing = 1
        assertTrue(r.onGround)
    }

    /** Rocco jumps up into block ([tx], [ty]) from right under it (JUMP held, so he goes up hard). */
    private fun bonk(tx: Int, ty: Int) {
        val r = game.rocco
        r.x = tx * TILE + (TILE - r.w) / 2f
        r.y = (ty + 1) * TILE + 1f
        r.vy = -300f
        run(0.25f) { input.jump = true; game.hurtProof() }
        input.jump = false
        run(0.4f) { game.hurtProof() }
    }

    @Test fun miniRoccoAndCapturedRoccoDontUseTheWonderMoves() {
        // Mini Rocco and a captured bad guy (Cappy's, or Wonder King Krag's magic) don't show the trunk, the drill
        // or the bubbles, so they can't use them either: nothing invisible gets whacked, drilled or blown.
        for (p in PowerUps.ALL) for (form in listOf("MINI", "CAPPY", "WONDER MAGIC")) {
            startPlaying()
            game.givePowerUp(p)
            val r = game.rocco
            val tx = tileOf(r.x) + 3
            standBefore(tx)
            when (form) {
                "MINI" -> game.startMini()
                "CAPPY" -> game.capture(Grumbler(r.x, r.bottom, false))
                else -> game.wonderTransform(r)
            }
            assertTrue(r.mini || game.capture != null, "$form")
            run(0.3f) { game.hurtProof() }
            r.facing = 1
            r.vx = 0f
            // A bad guy too far for a punch, but right where the trunk or the drill would get him.
            val g = Grumbler(r.cx + 19f, r.bottom, false).also { it.active = true; it.vx = 0f }
            game.foes += g
            press()
            assertEquals(g.maxHp, g.hp, "$p as $form: nothing invisible hit it")
            assertEquals(0f, r.drillSpin, "$p as $form: no drill spin")
            assertTrue(game.thingsForTest().none { it is HeroBubble }, "$p as $form: no bubbles")
            assertEquals(p, game.power, "the power-up is still on for later")
            game.foes.clear()
            if (p != Power.DRILL) continue
            // No drilling up through blocks...
            val ty = tileOf(r.bottom) - 4
            game.level[tx, ty] = Tile.STONE
            bonk(tx, ty)
            assertEquals(Tile.STONE, game.level[tx, ty], "$form: a bonk doesn't drill stone")
            // ...or on up after breaking a brick (Drill Rocco would go right on and bonk the ? block over it).
            game.level[tx, ty] = Tile.BRICK
            game.level[tx, ty - 1] = Tile.QCOIN
            bonk(tx, ty)
            assertEquals(Tile.EMPTY, game.level[tx, ty], "$form: bricks still break")
            assertEquals(Tile.QCOIN, game.level[tx, ty - 1], "$form: and that's as far as he goes")
            // ...or down: squatting on stone does nothing special.
            run(1.5f) { game.hurtProof() }
            val gx = tileOf(r.cx)
            val gy = tileOf(r.bottom + 2f)
            game.level[gx, gy] = Tile.STONE
            run(1f) { input.down = true; game.hurtProof() }
            input.down = false
            assertEquals(Tile.STONE, game.level[gx, gy], "$form: no drilling down")
            assertEquals(TeamGame.State.PLAY, game.state)
        }
    }

    /** Records what's drawn, to check a picture is (or isn't) there. */
    private class Drawn : Gfx {
        val ovals = ArrayList<FloatArray>()
        val colors = ArrayList<Int>()
        override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) { ovals += floatArrayOf(x, y, w, h); colors += color }
        override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {}
        override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {}
        override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) {}
        override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {}
        /** How far right the ovals of [color] reach (or NaN if there are none). */
        fun right(color: Int) = ovals.indices.filter { colors[it] == color }.maxOfOrNull { ovals[it][0] + ovals[it][2] } ?: Float.NaN
    }

    @Test fun megaRoccosWonderMovesAreGiantToo() {
        // MEGA Rocco is drawn twice as big: his trunk swing, drill whirl and bubbles are drawn (and hit) giant-sized too.
        for (p in PowerUps.ALL) {
            startPlaying()
            game.givePowerUp(p)
            val r = game.rocco
            game.startMega()
            standInTheOpen()
            // Further away than an ordinary trunk or drill reaches.
            val g = Grumbler(r.cx + 39f, r.bottom, false).also { it.active = true; it.vx = 0f }
            game.foes += g
            press()
            run(0.1f) { game.hurtProof() }
            // (Not flickering, so he's drawn.)
            game.noInvulnForTest()
            val drawn = Drawn()
            r.draw(game, drawn, 0f)
            when (p) {
                Power.ELEPHANT -> {
                    assertTrue(g.dying, "giant WHAM")
                    assertTrue(drawn.right(PowerArt.GREY) > r.cx + 40f, "and there's a giant trunk to see")
                }
                Power.DRILL -> {
                    assertTrue(g.hp < g.maxHp, "giant WHIRR")
                    assertTrue(drawn.right(0x55E8F0FF) > r.cx + 30f, "and a giant whirl to see")
                }
                else -> {
                    val b = game.thingsForTest().filterIsInstance<HeroBubble>().single()
                    assertTrue(b.cy < r.y, "the bubble comes out of his mouth, way up high")
                    assertTrue(drawn.colors.any { it == 0x40DFF8FF }, "with one at his lips")
                }
            }
        }
    }

    @Test fun babyRoccosWonderMovesAreBabySized() {
        // At the BABY MACHINE everybody is drawn little (their boxes stay the same): the trunk is a baby trunk,
        // so it only whacks what it's drawn reaching.
        startPlaying()
        game.babyMode = true
        game.givePowerUp(Power.ELEPHANT)
        val r = game.rocco
        standInTheOpen()
        val far = Grumbler(r.cx + 23f, r.bottom, false).also { it.active = true; it.vx = 0f }
        game.foes += far
        press()
        assertEquals(far.maxHp, far.hp, "out of a baby trunk's reach")
        game.foes.clear()
        run(0.6f) { game.hurtProof() }
        val near = Grumbler(r.cx + 9f, r.bottom, false).also { it.active = true; it.vx = 0f }
        game.foes += near
        press()
        assertTrue(near.dying, "baby WHAM")
        game.foes.clear()
        // Bubbles come out of a baby's mouth, low down.
        game.givePowerUp(Power.BUBBLE)
        run(0.6f) { game.hurtProof() }
        press()
        val b = game.thingsForTest().filterIsInstance<HeroBubble>().single()
        assertTrue(b.cy > r.bottom - 12f, "low down")
        // A baby wears a bonnet, but Drill Rocco's drill is his hat: the bonnet would hide it.
        val bonnet = 0xFFBFE4FF.toInt()
        game.noInvulnForTest()
        val bubbly = Drawn()
        r.draw(game, bubbly, 0f)
        assertTrue(bonnet in bubbly.colors)
        game.givePowerUp(Power.DRILL)
        val drilly = Drawn()
        r.draw(game, drilly, 0f)
        assertFalse(bonnet in drilly.colors, "no bonnet over the drill")
        game.babyMode = false
    }

    @Test fun ridingDashDrillRoccoDoesntDrillWithDashsFeet() {
        // Riding Dash, it's Dash's feet that land on a spiky bad guy, not the drill on Rocco's head.
        val lv = Levels.all.indexOfFirst { d -> d.rows.any { 'y' in it } }
        startPlaying(lv)
        val d = game.thingsForTest().filterIsInstance<Dino>().single()
        val r = game.rocco
        r.x = d.cx - 6f; r.y = d.y - 40f; r.vy = 50f
        run(0.6f) { game.hurtProof() }
        assertTrue(game.dino === d, "riding Dash")
        game.givePowerUp(Power.DRILL)
        game.foes.clear()
        run(0.5f) { game.hurtProof() }
        val spikes = Spikestack(r.cx - 8f, r.bottom).also { it.active = true; it.vx = 0f }
        game.foes += spikes
        r.y = spikes.y - r.h - 6f; r.vy = 150f
        run(0.15f) { game.hurtProof() }
        assertEquals(spikes.maxHp, spikes.hp, "not drilled")
        assertEquals(0f, r.drillSpin)
    }

    /**
     * Each power-up with Rocco's other looks, mid-move (build/screens/power-up-combos.png): Mini Rocco just punches,
     * MEGA swings a giant trunk, a baby's drill isn't hidden under a bonnet, and a captured Rocco is all bad guy.
     */
    @Test fun powerUpCombosScreenshot() {
        startPlaying()
        val s = 4f
        val img = BufferedImage((360 * s).toInt(), (230 * s).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = AwtGfx(img, s)
        g.rect(0f, 0f, 360f, 230f, 0xFF8FD0FF.toInt())
        val r = game.rocco
        val looks = listOf("NORMAL", "MINI", "MEGA", "BABY", "METAL", "SPACE", "CAPTURED")
        try {
            for ((row, p) in PowerUps.ALL.withIndex()) for ((col, look) in looks.withIndex()) {
                game.givePowerUp(p)
                val cx = 26f + col * 50f
                val bottom = 72f + row * 72f
                g.rect(cx - 25f, bottom, 50f, 2f, 0xFF5AB848.toInt())
                if (row == 0) g.text(look, cx, 10f, 6f, 0xFF1A1A3A.toInt(), 1)
                when (look) {
                    "MINI" -> game.startMini()
                    "MEGA" -> game.startMega()
                    "BABY" -> game.babyMode = true
                    "METAL" -> game.startMetal()
                    "SPACE" -> Style.spaceSuit = true
                    "CAPTURED" -> game.capture(Grumbler(r.x, r.bottom, false))
                }
                r.x = cx - r.w / 2; r.y = bottom - r.h; r.vx = 0f; r.vy = 0f; r.onGround = true; r.facing = 1
                r.swing = if (p == Power.ELEPHANT) PowerUps.TRUNK_TIME * 0.5f else 0.12f
                r.drillSpin = if (p == Power.DRILL) 0.2f else 0f
                game.noInvulnForTest()
                r.draw(game, g, 0f)
                // Back to plain Rocco for the next one.
                game.babyMode = false
                Style.spaceSuit = false
                game.megaT = 0f; game.metalT = 0f
                if (game.capture != null) game.releaseCapture()
                if (r.mini) { r.mini = false; r.w = 12f; r.h = 23f }
                r.swing = 0f; r.drillSpin = 0f
            }
        } finally {
            Style.spaceSuit = false
        }
        ImageIO.write(img, "png", File(File("build/screens").apply { mkdirs() }, "power-up-combos.png"))
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
        // One knocked off by a hit still has to be made again after the game is closed and opened.
        startPlaying()
        game.givePowerUp(Power.BUBBLE)
        game.noInvulnForTest()
        game.hurt()
        assertEquals(Power.NONE, game.power, "POOF")
        game.startLevel(0)
        val later = TeamGame(TeamInput(), SoundSink {}, storage)
        later.continueGame(0)
        val tab = TeamGame.TABS.indexOf("WONDER")
        assertEquals("MAKE", later.recipes(tab).first { it.icon == Power.BUBBLE }.button)
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
        // The WONDER tab after a hit knocked the Bubble Flower off: the drill (found, still fine) is a free EQUIP,
        // the bubble has to be made again with sapphires.
        game.givePowerUp(Power.DRILL)
        game.givePowerUp(Power.BUBBLE)
        game.noInvulnForTest()
        game.hurt()
        game.inventory[Ore.SAPPHIRE.ordinal] = 7
        game.openCraftForTest(TeamGame.TABS.indexOf("WONDER"))
        shot("power-craft-poofed")
        game.closeCraftForTest()
    }
}
