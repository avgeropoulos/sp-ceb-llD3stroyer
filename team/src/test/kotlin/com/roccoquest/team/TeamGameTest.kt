package com.roccoquest.team

import com.roccoquest.game.Music
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TeamGameTest {
    private class Ears : SoundSink {
        val played = ArrayList<Sound>()
        val said = ArrayList<String>()
        var music = Music.NONE
        override fun play(s: Sound) { played += s }
        override fun music(m: Music) { music = m }
        override fun say(text: String) { said += text }
    }

    private val dt = 1f / 60f
    private val input = TeamInput()
    private val ears = Ears()
    private val storage = Storage.Memory()
    private val game = TeamGame(input, ears, storage)

    private fun run(seconds: Float, each: () -> Unit = {}) {
        repeat((seconds / dt).toInt()) { each(); game.update(dt) }
    }

    private fun tap(x: Float = 10f, y: Float = 120f) {
        input.tapX = x; input.tapY = y; input.tap = true
        game.update(dt)
    }

    private fun startPlaying(level: Int = 0) {
        game.newGame()
        if (level > 0) game.startLevel(level)
        run(3.2f)
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    @Test fun levelsAreWellFormed() {
        assertEquals(4, Levels.all.size)
        for (def in Levels.all) {
            assertTrue(def.rows.all { it.length == def.w }, def.name)
            assertEquals(1, def.rows.sumOf { r -> r.count { it == '@' } }, "${def.name} needs one start")
        }
        assertTrue(Levels.all[2].rows.any { 'J' in it }, "Wonder Krag Jr. lives in the sky level")
        assertTrue(Levels.all[3].rows.any { 'K' in it }, "King Krag lives in the castle")
        // Every level has Ring-Ding Frog and ore to mine.
        for (def in Levels.all) {
            assertTrue(def.rows.any { 'r' in it }, "${def.name} frog")
            assertTrue(def.rows.any { r -> r.any { it in "ioDe" } }, "${def.name} ore")
        }
    }

    @Test fun theOreAddsUpForEveryRecipe() {
        // Over the whole game there must be enough of each ore for its flower and pickaxe.
        val count = HashMap<Char, Int>()
        for (def in Levels.all) for (r in def.rows) for (c in r) count[c] = (count[c] ?: 0) + 1
        assertTrue(count.getValue('i') >= 9, "iron: ${count['i']}")
        assertTrue(count.getValue('o') >= 9, "gold: ${count['o']}")
        assertTrue(count.getValue('D') >= 9, "diamond: ${count['D']}")
        assertTrue(count.getValue('e') >= 6, "emerald: ${count['e']}")
        // An iron pickaxe is possible in level 1, before the diamonds of level 2.
        val iron1 = Levels.all[0].rows.sumOf { r -> r.count { it == 'i' } }
        assertTrue(iron1 >= 6, "level 1 iron: $iron1")
    }

    @Test fun blockyMinesOreIntoTheBag() {
        startPlaying()
        game.foes.clear()
        // Put Blocky in charge, standing right next to the iron hill.
        input.swap = true; game.update(dt); input.swap = false; game.update(dt)
        assertEquals(Who.BLOCKY, game.leaderWho)
        val lv = game.level
        val oreX = (0 until lv.w).first { tx -> (0 until ROWS).any { lv[tx, it] == Tile.STONE } }
        val b = game.blocky
        b.x = oreX * TILE - b.w - 2f
        run(0.5f)
        b.facing = 1
        val before = game.inventory.sum()
        run(4f) { input.action = true }
        input.action = false
        assertTrue(game.inventory.sum() > before, "mined something: ${game.inventory.toList()}")
        assertTrue(ears.played.contains(Sound.BREAK))
    }

    @Test fun diamondsNeedAnIronPickaxe() {
        startPlaying()
        val lv = game.level
        val (dx, dy) = (0 until lv.w).flatMap { x -> (0 until ROWS).map { x to it } }.first { (x, y) -> lv[x, y] == Tile.DIAMOND }
        repeat(10) { game.mine(dx, dy) }
        assertEquals(Tile.DIAMOND, lv[dx, dy], "a wooden pickaxe can't break diamond")
        game.inventory[Ore.IRON.ordinal] = 3
        game.craft(game.recipes().indexOfFirst { it.title.endsWith("PICKAXE") })
        assertEquals(2, game.pickTier)
        repeat(4) { game.mine(dx, dy) }
        assertEquals(Tile.EMPTY, lv[dx, dy])
        assertEquals(1, game.inventory[Ore.DIAMOND.ordinal])
    }

    @Test fun craftingFlowersAndUpgrades() {
        startPlaying()
        fun row(p: Power) = game.recipes().indexOfFirst { it.icon == p }
        game.craft(row(Power.FIRE))
        assertEquals(Power.NONE, game.power, "no gold, no flower")
        game.inventory[Ore.GOLD.ordinal] = 9
        game.craft(row(Power.FIRE))
        assertEquals(Power.FIRE, game.power)
        assertEquals(1, game.flowerLevel[Power.FIRE.ordinal])
        game.craft(row(Power.FIRE))
        game.craft(row(Power.FIRE))
        assertEquals(3, game.flowerLevel[Power.FIRE.ordinal])
        assertEquals(0, game.inventory[Ore.GOLD.ordinal])
        // Grass Flower gives green armor that soaks up hits before hearts.
        game.inventory[Ore.EMERALD.ordinal] = 3
        game.craft(row(Power.GRASS))
        assertEquals(Power.GRASS, game.power)
        assertEquals(3, game.armor)
        game.hurt()
        assertEquals(2, game.armor)
        assertEquals(TeamGame.MAX_HEARTS, game.hearts)
        // Switching back to a flower you already made is free.
        game.craft(row(Power.FIRE))
        assertEquals(Power.FIRE, game.power)
        // Ice (diamond) is the strongest, iron the weakest.
        assertTrue(Power.ICE.damage > Power.FIRE.damage && Power.FIRE.damage > Power.BOOM.damage)
    }

    @Test fun craftMenuOpensFromTheButton() {
        startPlaying()
        TeamControls.layout(game.viewW)
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        assertEquals(TeamGame.State.CRAFT, game.state)
        game.inventory[Ore.IRON.ordinal] = 3
        // Tap the first row (the Boomerang Flower).
        run(0.3f)
        tap(game.viewW / 2, 32f + 22f + 15f)
        assertEquals(Power.BOOM, game.power)
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    @Test fun roccoThrowsTheCraftedPower() {
        startPlaying()
        game.inventory[Ore.DIAMOND.ordinal] = 3
        game.craft(game.recipes().indexOfFirst { it.icon == Power.ICE })
        val g = game.foes.filterIsInstance<Grumbler>().minByOrNull { it.x }!!
        val r = game.rocco
        r.x = g.x - 80f; r.y = g.y - 9f; r.facing = 1
        game.foes.retainAll(listOf(g))
        run(1.5f) { input.action = true }
        input.action = false
        assertTrue(g.dying || g.removed, "the iceball knocked out the grumbler")
    }

    @Test fun followerKeepsUp() {
        startPlaying()
        game.foes.clear()
        repeat(360) { botStep() }
        input.right = false; input.jump = false
        run(1f)
        val d = kotlin.math.abs(game.rocco.cx - game.blocky.cx)
        assertTrue(d < 120f, "Blocky stays with Rocco: $d")
        assertTrue(game.rocco.x > 300f, "Rocco got going: ${game.rocco.x}")
    }

    private var held = 0f

    /** One frame of a simple bot that holds right and jumps over walls and gaps. */
    private fun botStep() {
        val h = game.leader
        val lv = game.level
        val ahead = h.x + h.w + 6
        val wall = lv.solidAt(ahead, h.bottom - 4) || lv.solidAt(ahead, h.y + 4)
        // A gap means no floor (or cloud) a few tiles below the spot just ahead.
        val gap = (0..3).none { i ->
            val t = lv[tileOf(ahead + 8), tileOf(h.bottom + 4 + i * TILE)]
            Tile.solid(t) || t == Tile.CLOUD
        }
        if (h.onGround && (wall || gap) && held <= 0f) held = 0.45f
        input.right = true
        input.jump = held > 0f
        held -= dt
        game.update(dt)
    }

    /** The bot runs the level with every ordinary foe removed, to check the level can be finished. */
    private fun botRun(level: Int, maxSeconds: Float): Boolean {
        startPlaying(level)
        repeat((maxSeconds / dt).toInt()) {
            game.foes.removeAll { it !is KragJr && it !is KingKrag }
            botStep()
            if (game.state == TeamGame.State.CLEAR) { input.right = false; input.jump = false; return true }
        }
        input.right = false; input.jump = false
        return false
    }

    @Test fun grassyHillsCanBeFinished() = assertTrue(botRun(0, 90f), "stuck at x=${game.leader.x / TILE}")

    @Test fun diamondCaveCanBeFinished() = assertTrue(botRun(1, 90f), "stuck at x=${game.leader.x / TILE}")

    /** The bot can walk and jump all the way to each boss arena. */
    @Test fun bossArenasCanBeReached() {
        for (level in 2..3) {
            startPlaying(level)
            var reached = false
            repeat((90f / dt).toInt()) {
                game.foes.removeAll { it !is KragJr && it !is KingKrag }
                game.hurtProof()
                if (!reached) botStep()
                if (game.boss != null) reached = true
            }
            input.right = false; input.jump = false
            assertTrue(reached, "level ${level + 1}: stuck at x=${game.leader.x / TILE}")
        }
    }

    @Test fun ringDingFrogSings() {
        startPlaying()
        game.foes.clear()
        repeat(900) { botStep() }
        input.right = false; input.jump = false
        assertTrue(ears.said.any { "Ring ding" in it }, "frog sang: ${ears.said}")
    }

    @Test fun wonderKragJrFight() {
        startPlaying(2)
        val jr = game.foes.filterIsInstance<KragJr>().single()
        val h = game.leader
        // Walk up to the arena.
        h.x = jr.homeX - 150f; h.y = 100f
        run(3.5f) { game.hurtProof() }
        assertTrue(jr.engaged, "Krag Jr. noticed the team")
        assertTrue(game.wonder, "the sky went Wonder")
        // (Ring-Ding Frog may still be singing on his way past.)
        assertTrue(ears.music == Music.WONDER || ears.music == Music.FROG, "music: ${ears.music}")
        // Hit him with level 3 ice until he gives up.
        game.inventory[Ore.DIAMOND.ordinal] = 9
        repeat(3) { game.craft(game.recipes().indexOfFirst { it.icon == Power.ICE }) }
        var t = 0f
        while (jr.hp > 0 && t < 60f) {
            game.hurtProof()
            val l = game.leader
            l.facing = if (jr.cx > l.cx) 1 else -1
            input.action = true
            game.update(dt)
            t += dt
            // Keep Rocco level with Krag Jr. so the shots connect.
            l.y = (jr.cy - l.h / 2).coerceAtMost(200f - l.h); l.vy = 0f
        }
        input.action = false
        assertEquals(0, jr.hp, "beat Wonder Krag Jr. in ${t}s")
        run(3f)
        assertTrue(!game.wonder)
    }

    @Test fun kingKragFightEndsTheGame() {
        startPlaying(3)
        val krag = game.foes.filterIsInstance<KingKrag>().single()
        game.foes.retainAll(listOf(krag))
        val r = game.rocco
        r.x = krag.homeX - 150f; r.y = 160f
        run(3.5f) { game.hurtProof() }
        assertTrue(krag.awake, "King Krag woke up")
        game.inventory[Ore.GOLD.ordinal] = 9
        repeat(3) { game.craft(game.recipes().indexOfFirst { it.icon == Power.FIRE }) }
        var t = 0f
        while (game.state != TeamGame.State.VICTORY && t < 90f) {
            game.hurtProof()
            game.foes.removeAll { it is Flame }
            r.facing = if (krag.cx > r.cx) 1 else -1
            input.action = true
            game.update(dt)
            t += dt
        }
        input.action = false
        assertEquals(TeamGame.State.VICTORY, game.state, "King Krag hp ${krag.hp}")
        assertEquals(Music.FROG, ears.music, "Ring-Ding Frog sings at the end")
    }

    @Test fun progressIsSaved() {
        startPlaying(2)
        game.inventory[Ore.GOLD.ordinal] = 4
        game.startLevel(2)
        val g2 = TeamGame(TeamInput(), ears, storage)
        assertEquals(2, g2.savedLevel)
        g2.continueGame()
        assertEquals(2, g2.levelIndex)
        assertEquals(4, g2.inventory[Ore.GOLD.ordinal])
    }

    /** Renders a few frames to build/screens for eyeballing. */
    @Test fun screenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val scale = 3f
            val img = BufferedImage((game.viewW * scale).toInt(), (240 * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, scale))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        run(0.5f)
        shot("title")
        game.newGame()
        run(0.5f)
        shot("intro")
        run(3f)
        run(1.2f) { input.right = true }
        input.right = false
        shot("hills")
        input.swap = true; game.update(dt); input.swap = false
        game.inventory[Ore.IRON.ordinal] = 4
        game.inventory[Ore.GOLD.ordinal] = 3
        game.inventory[Ore.STONE.ordinal] = 7
        run(0.3f) { input.action = true }
        input.action = false
        shot("blocky-mining")
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        shot("craft")
        game.craft(1)
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        game.startLevel(1); run(3.5f); run(2f) { input.right = true }; input.right = false
        shot("cave")
        game.startLevel(2); run(3.5f)
        val jr = game.foes.filterIsInstance<KragJr>().single()
        game.leader.x = jr.homeX - 150f; game.leader.y = 100f
        run(4.5f) { game.hurtProof() }
        shot("wonder-krag-jr")
        game.startLevel(3); run(3.5f)
        val krag = game.foes.filterIsInstance<KingKrag>().single()
        game.leader.x = krag.homeX - 150f; game.leader.y = 160f
        run(4f) { game.hurtProof() }
        shot("king-krag")
        assertNotNull(krag)
    }
}
