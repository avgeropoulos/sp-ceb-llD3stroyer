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
import kotlin.math.abs
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
        game.newGame(0, Mon.SPARKY)
        if (level > 0) game.startLevel(level)
        run(3.2f)
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    @Test fun levelsAreWellFormed() {
        assertTrue(Levels.all.size >= 21, "a nice long game: ${Levels.all.size} levels")
        for (def in Levels.all) {
            assertTrue(def.rows.all { it.length == def.w }, def.name)
            assertEquals(1, def.rows.sumOf { r -> r.count { it == '@' } }, "${def.name} needs one start")
        }
        assertTrue(Levels.all[2].rows.any { 'J' in it }, "Krag Jr. lives in the sky level")
        assertTrue(Levels.all[3].rows.any { 'K' in it }, "King Krag lives in the castle")
        assertTrue(Levels.all[7].rows.any { 'W' in it }, "Wonder Krag Jr.")
        assertTrue(Levels.all.any { d -> d.rows.any { 'Q' in it } }, "Wonder King Krag")
        assertTrue(Levels.all.any { d -> d.rows.any { 'G' in it } }, "the Warden")
        assertTrue(Levels.all.any { d -> d.rows.any { 'Y' in it } }, "Dry Bones King Krag")
        assertTrue(Levels.all.last().rows.any { 'Z' in it }, "the Ender Dragon is the final boss")
        assertTrue(Levels.all.any { it.place == Place.WATER && it.shoreX > 0 }, "an underwater level")
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
        game.craft(0, tab = 1)
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
        // Flowers go all the way to Lv5.
        game.inventory[Ore.GOLD.ordinal] = 30
        repeat(5) { game.craft(row(Power.FIRE)) }
        assertEquals(MAX_FLOWER_LV, game.flowerLevel[Power.FIRE.ordinal])
        assertEquals(24, game.inventory[Ore.GOLD.ordinal], "upgrades stop costing once maxed")
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
        tap(game.viewW / 2, 72f + 13f)
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
        if (game.inWater(h)) {
            // Swim: aim for the open rows in the column just ahead.
            val col = tileOf(h.x + h.w + 20)
            val row = tileOf(h.cy)
            val open = (3 until ROWS - 1).filter { r -> !Tile.solid(lv[col, r]) && !Tile.solid(lv[col, r - 1]) }
            val target = open.minByOrNull { abs(it - row) } ?: row
            val ty = target * TILE.toFloat()
            input.right = true
            input.down = h.cy < ty - 10
            // Tap JUMP to swim up.
            input.jump = h.cy > ty + 2 && (game.time * 4).toInt() % 2 == 0
            game.update(dt)
            input.down = false
            return
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
            game.foes.removeAll { !it.boss && it !is EndCrystal }
            game.hurtProof()
            botStep()
            if (game.state == TeamGame.State.CLEAR) { input.right = false; input.jump = false; return true }
        }
        input.right = false; input.jump = false
        return false
    }

    /** Every level without a boss at the end can be run from start to flag. */
    @Test fun everyFlagLevelCanBeFinished() {
        for (i in Levels.all.indices) {
            if (Levels.all[i].rows.any { r -> r.any { it in BOSSES } }) continue
            assertTrue(botRun(i, 120f), "${Levels.all[i].name}: stuck at x=${game.leader.x / TILE}")
        }
    }

    /** The bot can walk and jump all the way to each boss arena. */
    private val BOSSES = "JWKQGYZ"

    @Test fun bossArenasCanBeReached() {
        val bossLevels = Levels.all.indices.filter { i -> Levels.all[i].rows.any { r -> r.any { it in BOSSES } } }
        assertTrue(bossLevels.size >= 7)
        for (level in bossLevels) {
            startPlaying(level)
            var reached = false
            repeat((90f / dt).toInt()) {
                game.foes.removeAll { !it.boss && it !is EndCrystal }
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

    private fun fightJr(level: Int, wonderStyle: Boolean) {
        startPlaying(level)
        val jr = game.foes.filterIsInstance<KragJr>().single()
        assertEquals(wonderStyle, jr.wonderStyle)
        val h = game.leader
        h.x = jr.homeX - 150f; h.y = 100f
        run(3.5f) { game.hurtProof() }
        assertTrue(jr.engaged, "Krag Jr. noticed the team")
        assertEquals(wonderStyle, game.wonder, "only the Wonder version turns the sky Wonder")
        game.inventory[Ore.DIAMOND.ordinal] = 9
        repeat(3) { game.craft(game.recipes(0).indexOfFirst { it.icon == Power.ICE }, tab = 0) }
        var t = 0f
        while (jr.hp > 0 && t < 90f) {
            game.hurtProof()
            val l = game.leader
            l.facing = if (jr.cx > l.cx) 1 else -1
            input.action = true
            game.update(dt)
            t += dt
            l.y = (jr.cy - l.h / 2).coerceAtMost(200f - l.h); l.vy = 0f
        }
        input.action = false
        assertEquals(0, jr.hp, "beat Krag Jr. in ${t}s")
        run(3f)
        assertTrue(!game.wonder)
    }

    @Test fun kragJrFight() = fightJr(2, wonderStyle = false)

    @Test fun wonderKragJrFight() = fightJr(7, wonderStyle = true)

    private fun fightKrag(level: Int): KingKrag {
        startPlaying(level)
        val krag = game.foes.filterIsInstance<KingKrag>().single()
        game.foes.retainAll(listOf(krag))
        val r = game.rocco
        r.x = krag.homeX - 150f; r.y = 160f
        run(3.5f) { game.hurtProof() }
        assertTrue(krag.awake, "King Krag woke up")
        game.inventory[Ore.GOLD.ordinal] = 15
        repeat(5) { game.craft(game.recipes(0).indexOfFirst { it.icon == Power.FIRE }, tab = 0) }
        var t = 0f
        while (game.state == TeamGame.State.PLAY && t < 120f) {
            game.hurtProof()
            game.foes.removeAll { it is Flame || it is SpikeBall }
            r.facing = if (krag.cx > r.cx) 1 else -1
            input.action = true
            game.update(dt)
            t += dt
        }
        input.action = false
        return krag
    }

    @Test fun kingKragIsNotTheEnd() {
        val krag = fightKrag(3)
        assertEquals(0, krag.hp)
        assertEquals(TeamGame.State.CLEAR, game.state, "on to the next world")
        run(4f)
        assertEquals(TeamGame.State.STICKER, game.state, "pick a sticker first")
        assertTrue(game.stickerChoices.contains(Stickers.forBoss('K')), "King Krag's shiny sticker is on offer")
        game.pickSticker(0)
        run(3f)
        assertEquals(4, game.levelIndex)
    }

    @Test fun stickersAfterEveryLevel() {
        assertEquals(40, Stickers.all.size)
        assertTrue(botRun(0, 100f))
        run(4f)
        assertEquals(TeamGame.State.STICKER, game.state)
        assertEquals(3, game.stickerChoices.size)
        assertEquals(3, game.stickerChoices.distinct().size)
        // Tap the middle card.
        tap(game.viewW / 2, 120f)
        val got = game.stickerChoices[1]
        assertTrue(game.hasSticker(got))
        assertEquals(1, game.stickerCount)
        assertTrue(ears.said.last().contains("sticker"), "it says which sticker you got")
        run(3f)
        assertEquals(1, game.levelIndex, "then on to the next level")
        // Stickers are saved with the file.
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertTrue(g2.hasSticker(got))
        // The sticker book opens from the pause screen.
        run(3.5f)
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        assertTrue(game.paused)
        tap(game.viewW / 2, 164f)
        assertEquals(TeamGame.State.BOOK, game.state)
        run(0.5f)
        tap()
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    private fun levelWith(c: Char) = Levels.all.indexOfFirst { d -> d.rows.any { c in it } }

    @Test fun wonderKingKragLeadsToTheDeepDark() {
        val krag = fightKrag(levelWith('Q'))
        assertTrue(krag.wonderStyle)
        assertEquals(TeamGame.State.CLEAR, game.state, "Wonder King Krag hp ${krag.hp}")
    }

    @Test fun kingKragGetsCrazyMusic() {
        startPlaying(levelWith('K'))
        val krag = game.foes.filterIsInstance<KingKrag>().single()
        game.rocco.x = krag.homeX - 150f; game.rocco.y = 160f
        run(3.5f) { game.hurtProof() }
        assertEquals(Music.KRAG, ears.music)
    }

    @Test fun dryBonesKragGetsBackUpOnce() {
        val krag = fightKrag(levelWith('Y'))
        assertTrue(krag.dry)
        assertEquals(0, krag.hp)
        assertEquals(TeamGame.State.CLEAR, game.state, "beat Dry Bones King Krag (twice)")
    }

    @Test fun theWardenFight() {
        startPlaying(levelWith('G'))
        val w = game.foes.filterIsInstance<Warden>().single()
        game.foes.retainAll(listOf(w))
        val r = game.rocco
        r.x = w.homeX - 150f; r.y = 160f
        run(3.5f) { game.hurtProof() }
        assertTrue(w.awake)
        assertEquals(Music.WARDEN, ears.music, "the Warden has his own music")
        game.inventory[Ore.DIAMOND.ordinal] = 15
        repeat(5) { game.craft(game.recipes(0).indexOfFirst { it.icon == Power.ICE }, tab = 0) }
        var t = 0f
        var sawBoom = false
        while (game.state == TeamGame.State.PLAY && t < 120f) {
            game.hurtProof()
            if (game.foes.any { it is SonicBoom }) sawBoom = true
            game.foes.removeAll { it is SonicBoom }
            r.facing = if (w.cx > r.cx) 1 else -1
            input.action = true
            game.update(dt)
            t += dt
        }
        input.action = false
        assertEquals(0, w.hp)
        assertTrue(sawBoom, "the Warden used his sonic boom")
        assertEquals(TeamGame.State.CLEAR, game.state)
    }

    @Test fun enderDragonEndsTheGame() {
        startPlaying(Levels.all.lastIndex)
        val d = game.foes.filterIsInstance<EnderDragon>().single()
        val r = game.rocco
        r.x = d.homeX - 120f; r.y = 160f
        run(3.5f) { game.hurtProof() }
        assertTrue(d.engaged, "the dragon woke up")
        // Break the end crystals first.
        val crystals = game.foes.filterIsInstance<EndCrystal>()
        assertEquals(2, crystals.size)
        crystals.forEach { while (!it.dying) it.hit(game, 2) }
        game.inventory[Ore.DIAMOND.ordinal] = 15
        repeat(5) { game.craft(game.recipes(0).indexOfFirst { it.icon == Power.ICE }, tab = 0) }
        var t = 0f
        while (game.state == TeamGame.State.PLAY && t < 180f) {
            game.hurtProof()
            game.foes.removeAll { it is DragonBall || it is Enderman }
            r.facing = if (d.cx > r.cx) 1 else -1
            input.action = true
            game.update(dt)
            t += dt
            r.y = (d.cy - r.h / 2).coerceIn(40f, 208f - r.h); r.vy = 0f
        }
        input.action = false
        assertEquals(0, d.hp, "beat the dragon in ${t}s")
        assertEquals(TeamGame.State.STICKER, game.state, "one last sticker")
        assertTrue(game.stickerChoices.contains(Stickers.forBoss('Z')))
        game.pickSticker(game.stickerChoices.indexOf(Stickers.forBoss('Z')))
        run(3f)
        assertEquals(TeamGame.State.VICTORY, game.state)
        assertEquals(Music.FROG, ears.music, "Ring-Ding Frog sings at the end")
    }

    @Test fun villagersTalkAndTrade() {
        startPlaying(levelWith('V').let { i -> Levels.all.indexOfFirst { d -> d.name == "VILLAGER VALLEY" } })
        val v = game.thingsForTest().filterIsInstance<Villager>().first()
        val l = game.leader
        l.x = v.cx - l.w / 2; l.y = v.bottom - l.h - 1
        game.coins = 30
        game.inventory[Ore.STONE.ordinal] = 20
        game.inventory[Ore.EMERALD.ordinal] = 5
        game.inventory[Ore.IRON.ordinal] = 5
        run(0.3f)
        input.action = true; game.update(dt); input.action = false
        assertEquals(TeamGame.State.TRADE, game.state)
        assertTrue(ears.said.any { "trade" in it.lowercase() || "hello" in it.lowercase() || "hi" in it.lowercase() }, "villager greets: ${ears.said}")
        val before = game.inventory.sum() + game.coins + game.tnt
        game.tradeForTest(0)
        assertTrue(game.inventory.sum() + game.coins + game.tnt != before, "the trade happened")
        assertTrue(ears.said.last().isNotBlank())
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        assertEquals(TeamGame.State.PLAY, game.state)
        assertTrue(ears.said.last().startsWith("Goodbye"))
    }

    @Test fun minecartRidesAndSmashes() {
        startPlaying(Levels.all.indexOfFirst { it.name == "MINECART MINE" })
        val cart = game.thingsForTest().filterIsInstance<Minecart>().first()
        val l = game.leader
        l.x = cart.cx - l.w / 2; l.y = cart.bottom - l.h - 1
        run(0.5f) { game.hurtProof() }
        val startX = cart.x
        input.action = true; game.update(dt); input.action = false
        assertNotNull(game.riding)
        run(3f) { game.hurtProof() }
        assertTrue(cart.x > startX + 300f, "the cart rolled along the rails: ${cart.x - startX}")
        assertTrue(abs(game.leader.cx - cart.cx) < 4f, "the leader rides in the cart")
        run(5f) { game.hurtProof() }
        assertEquals(null, game.riding, "the ride ends at the end of the rails")
    }

    @Test fun throwTntBlowsUpAGrid() {
        startPlaying()
        game.inventory[Ore.STONE.ordinal] = 6
        game.inventory[Ore.IRON.ordinal] = 2
        game.craft(game.recipes(1).indexOfFirst { it.title == "TNT x2" }, tab = 1)
        assertEquals(2, game.tnt)
        // Stand next to the iron hill and throw.
        val lv = game.level
        val hillX = (0 until lv.w).first { tx -> (0 until ROWS).any { lv[tx, it] == Tile.STONE } }
        val r = game.rocco
        r.x = hillX * TILE - 50f; r.facing = 1
        run(0.5f)
        val blocks = (0 until lv.w).sumOf { x -> (0 until ROWS).count { y -> lv.toughness(x, y) > 0 } }
        input.tnt = true; game.update(dt); input.tnt = false
        assertEquals(1, game.tnt)
        run(0.6f)
        assertTrue(game.thingsForTest().any { it is ThrownTnt && it.landed }, "the TNT landed and shows its grid")
        run(1.5f)
        val after = (0 until lv.w).sumOf { x -> (0 until ROWS).count { y -> lv.toughness(x, y) > 0 } }
        assertTrue(after < blocks, "blocks were blown up: $blocks -> $after")
    }

    @Test fun poseidonFloodsTheScreen() {
        startPlaying()
        game.coins = 49
        game.foes.forEach { it.active = true; it.x = game.camX + 200f }
        input.poseidon = true; game.update(dt); input.poseidon = false; game.update(dt)
        assertTrue(game.floodT <= 0f, "49 coins isn't enough")
        game.coins = 55
        input.poseidon = true; game.update(dt); input.poseidon = false
        assertTrue(game.floodT > 0f)
        assertEquals(5, game.coins)
        assertTrue(game.foes.all { it.dying || it.boss }, "simple bad guys were washed away")
        assertTrue(ears.said.any { "Poseidon" in it })
    }

    @Test fun underwaterAirAndBubbles() {
        startPlaying(Levels.all.indexOfFirst { it.place == Place.WATER })
        game.foes.clear()
        assertTrue(game.inWater(game.leader))
        val full = game.air
        run(5f)
        assertTrue(game.air < full - 4f, "air goes down underwater")
        // A bubble from a vent fills it back up.
        run(2f)
        val b = game.thingsForTest().filterIsInstance<Bubble>().firstOrNull()
        assertNotNull(b, "vents make bubbles")
        val l = game.leader
        val low = game.air
        l.x = b.x; l.y = b.y
        game.update(dt)
        assertTrue(game.air > low, "bubbles give air")
        // Running out of air costs a heart but gives you more air: not too hard.
        repeat((40f / dt).toInt()) {
            l.x = 20f * TILE; l.y = 60f; l.vy = 0f
            game.update(dt)
        }
        assertTrue(game.hearts < TeamGame.MAX_HEARTS)
    }

    @Test fun dennisBarksAndPointsTheWay() {
        startPlaying()
        game.foes.clear()
        run(12f)
        assertTrue(ears.played.contains(Sound.BARK), "woof!")
    }

    @Test fun endermenTeleportAndAreTough() {
        startPlaying(Levels.all.indexOfFirst { it.name == "ENDER PLAINS" })
        val e = game.foes.filterIsInstance<Enderman>().first()
        assertTrue(e.maxHp >= 15)
        e.active = true
        var dodged = 0
        repeat(40) { if (!e.hit(game, 1)) dodged++ }
        assertTrue(dodged > 0, "endermen dodge sometimes")
    }

    @Test fun doubleTapToSprint() {
        startPlaying()
        game.foes.clear()
        // Single press: normal speed.
        run(1f) { input.right = true }
        val walk = abs(game.rocco.vx)
        input.right = false; run(0.1f)
        // Tap, let go, tap again and hold: sprint!
        run(0.08f) { input.right = true }
        input.right = false; game.update(dt)
        run(1f) { input.right = true }
        assertTrue(game.sprinting, "sprinting after a double tap")
        assertTrue(abs(game.rocco.vx) > walk * 1.3f, "sprint is faster: ${game.rocco.vx} vs $walk")
        input.right = false
        run(0.2f)
        assertTrue(!game.sprinting)
    }

    @Test fun roccoSquatsAndButtSlams() {
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        run(0.5f)
        input.down = true; run(0.3f)
        assertTrue(r.crouching, "squat")
        run(0.5f) { input.right = true }
        input.right = false
        assertTrue(abs(r.vx) < 5f, "no walking while squatting")
        input.down = false; run(0.2f)
        assertTrue(!r.crouching)
        // Jump under the first brick row and slam down onto... a brick below us.
        val lv = game.level
        val (bx, by) = (0 until lv.w).flatMap { x -> (0 until ROWS).map { x to it } }.first { (x, y) -> lv[x, y] == Tile.BRICK }
        r.x = bx * TILE + 2f; r.y = by * TILE - 23f - 60f; r.vy = 0f
        game.update(dt)
        input.down = true; game.update(dt); input.down = false
        assertTrue(r.slamming, "butt slam in the air")
        run(1f)
        assertTrue(!r.slamming)
        assertTrue(lv[bx, by] != Tile.BRICK, "the slam broke the brick")
    }

    @Test fun roccoDoubleJumps() {
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        run(0.5f)
        fun peak(double: Boolean): Float {
            val y0 = r.y
            var top = y0
            input.jump = true; game.update(dt)
            repeat(20) { game.update(dt); top = minOf(top, r.y) }
            if (double) {
                // Let go, then press JUMP again in mid-air.
                input.jump = false; game.update(dt)
                input.jump = true; game.update(dt)
            }
            repeat(80) { game.update(dt); top = minOf(top, r.y) }
            input.jump = false
            run(2f)
            return y0 - top
        }
        val single = peak(false)
        val double = peak(true)
        assertTrue(double > single * 1.4f, "double jump goes higher: $double vs $single")
        // Only one extra jump per jump.
        input.jump = true; game.update(dt); input.jump = false; game.update(dt)
        input.jump = true; game.update(dt); input.jump = false
        assertTrue(r.doubleJumped)
        val vy = r.vy
        input.jump = true; game.update(dt); input.jump = false
        assertTrue(r.vy >= vy, "no triple jump")
    }

    @Test fun longJumpGoesFar() {
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        run(0.5f)
        val x0 = r.x
        r.facing = 1
        input.longJump = true; game.update(dt); input.longJump = false
        assertTrue(r.longJumping)
        var t = 0f
        while (!r.onGround && t < 3f) { game.update(dt); t += dt }
        val long = r.x - x0
        // A normal jump from standing still goes nowhere; the long jump goes far.
        assertTrue(long > 90f, "long jump distance: $long")
        // Crouch + jump while moving is a long jump too, like in Mario 64.
        run(0.6f) { input.right = true }
        input.down = true; game.update(dt)
        input.jump = true; game.update(dt); input.jump = false; input.down = false; input.right = false
        assertTrue(r.longJumping, "crouch + jump = long jump")
    }

    @Test fun blueShellHomesAndSpins() {
        startPlaying()
        game.inventory[Ore.DIAMOND.ordinal] = 3
        game.craft(game.recipes(0).indexOfFirst { it.icon == Power.SHELL }, tab = 0)
        assertEquals(Power.SHELL, game.power)
        // A homing shell finds a bad guy even when it's up in the air.
        val g = game.foes.filterIsInstance<Grumbler>().minByOrNull { it.x }!!
        game.foes.retainAll(listOf(g))
        val r = game.rocco
        r.x = g.x - 90f; r.facing = 1
        run(1.5f) { game.hurtProof() }
        g.active = true; g.y -= 30f
        input.action = true; game.update(dt); input.action = false
        run(2f) { game.hurtProof() }
        assertTrue(g.dying || g.removed, "the blue shell blew up the grumbler")
        // Sprinting with the blue shell turns Rocco into a spinning shell.
        game.foes.clear()
        run(0.08f) { input.right = true }
        input.right = false; game.update(dt)
        run(0.8f) { input.right = true }
        assertTrue(r.shellSpin, "spin dash")
        input.right = false
        run(0.3f)
        assertTrue(!r.shellSpin)
    }

    @Test fun miniMushroomMakesRoccoTinyAndFloaty() {
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        run(0.5f)
        // How high does normal Rocco jump?
        fun jumpHeight(): Float {
            val y0 = r.y
            var top = y0
            input.jump = true
            repeat(90) { game.update(dt); top = minOf(top, r.y) }
            input.jump = false
            run(2f)
            return y0 - top
        }
        val normal = jumpHeight()
        game.inventory[Ore.EMERALD.ordinal] = 1
        game.inventory[Ore.STONE.ordinal] = 3
        game.craft(game.recipes(1).indexOfFirst { it.title == "MINI MUSHROOM" }, tab = 1)
        assertTrue(r.mini && r.h < 10f, "tiny Rocco")
        val tiny = jumpHeight()
        assertTrue(tiny > normal * 1.4f, "mini jumps way higher: $tiny vs $normal")
        // Getting hit as Mini Rocco just makes him big again.
        val hearts = game.hearts
        run(2f)
        game.hurt()
        assertTrue(!r.mini && r.h > 20f)
        assertEquals(hearts, game.hearts)
    }

    @Test fun bombBombsChaseExplodeAndCanBeKicked() {
        val lvl = Levels.all.indexOfFirst { d -> d.rows.any { 'p' in it } }
        assertTrue(Levels.all.count { d -> d.rows.any { 'p' in it } } >= 8, "bomb-bombs in lots of levels")
        startPlaying(lvl)
        val bomb = game.foes.filterIsInstance<BombBomb>().first()
        game.foes.retainAll(listOf(bomb))
        val r = game.rocco
        r.x = bomb.x - 60f; r.y = bomb.bottom - r.h
        run(4f) { game.hurtProof() }
        assertTrue(bomb.removed, "it lit its fuse and blew up")
        // Stomp one, then kick it into a grumbler.
        startPlaying(lvl)
        val b2 = game.foes.filterIsInstance<BombBomb>().first()
        val g = Grumbler(b2.x + 60f, b2.bottom, false).also { it.active = true }
        game.foes.retainAll(listOf(b2))
        game.foes += g
        b2.active = true
        b2.stomp(game, game.rocco)
        game.rocco.x = b2.x - 14f; game.rocco.y = b2.bottom - game.rocco.h
        b2.touch(game)
        run(1.5f) { game.hurtProof() }
        assertTrue(b2.removed, "the kicked bomb exploded")
        assertTrue(g.dying || g.removed, "and took the grumbler with it")
    }

    @Test fun blockyCraftsSwords() {
        startPlaying()
        val swordsTab = TeamGame.TABS.indexOf("WEAPONS")
        fun row(sw: Sword) = game.recipes(swordsTab).indexOfFirst { it.kind == TeamGame.Kind.SWORD && it.sword == sw }
        assertEquals(5, game.recipes(swordsTab).count { it.kind == TeamGame.Kind.SWORD }, "five kinds of swords")
        game.craft(row(Sword.DIAMOND), swordsTab)
        assertEquals(Sword.NONE, game.sword, "no diamonds yet")
        game.inventory[Ore.STONE.ordinal] = 4
        game.craft(row(Sword.STONE), swordsTab)
        assertEquals(Sword.STONE, game.sword)
        game.inventory[Ore.DIAMOND.ordinal] = 3
        game.craft(row(Sword.DIAMOND), swordsTab)
        assertEquals(Sword.DIAMOND, game.sword)
        // Switching back to a sword you already made is free.
        game.craft(row(Sword.STONE), swordsTab)
        assertEquals(Sword.STONE, game.sword)
        assertEquals(0, game.inventory[Ore.STONE.ordinal])
        game.craft(row(Sword.DIAMOND), swordsTab)

        // The diamond sword sweeps: two shellbacks (3 hp each) next to Blocky both go down.
        input.swap = true; game.update(dt); input.swap = false; game.update(dt)
        val b = game.blocky
        game.foes.clear()
        val k1 = Shellback(b.x + 10f, b.bottom).also { it.active = true }
        val k2 = Shellback(b.x + 16f, b.bottom).also { it.active = true }
        game.foes += k1; game.foes += k2
        b.facing = 1
        game.hurtProof()
        input.action = true; game.update(dt); input.action = false
        assertTrue((k1.dying || k1.hp < 3) && (k2.dying || k2.hp < 3), "sweep hit both")
        assertTrue(k1.dying && k2.dying, "6 damage knocks out a shellback")

        // The amethyst sword shoots a magic slash.
        game.inventory[Ore.AMETHYST.ordinal] = 3
        game.craft(row(Sword.AMETHYST), swordsTab)
        game.foes.clear()
        run(0.5f)
        input.action = true; game.update(dt); input.action = false
        assertTrue(game.thingsForTest().any { it is SwordBeam }, "magic slash")

        // Swords are saved with the file.
        game.startLevel(game.levelIndex)
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertEquals(Sword.AMETHYST, g2.sword)
        assertTrue(g2.hasSword(Sword.STONE) && g2.hasSword(Sword.DIAMOND))
    }

    @Test fun lotsOfPointsAndCoins() {
        startPlaying()
        val r = game.rocco
        // Knocked-out bad guys drop coins.
        val g = game.foes.filterIsInstance<Grumbler>().first()
        game.foes.retainAll(listOf(g))
        g.active = true
        val coins = game.coins
        g.hit(game, 9)
        run(1f) { game.hurtProof() }
        assertTrue(game.coins > coins, "coins dropped: ${game.coins}")
        // Stomping three bad guys in a row is a combo worth way more than three single stomps.
        game.foes.clear()
        val before = game.score
        val gs = (0 until 3).map { Grumbler(r.x + it * 40f, r.bottom - 60f, false).also { f -> f.active = true; f.vx = 0f } }
        r.vy = 0f
        for (f in gs) {
            r.x = f.cx - r.w / 2; r.y = f.y - r.h + 4; r.vy = 200f; r.onGround = false
            game.foes.clear(); game.foes += f
            game.update(dt)
        }
        assertTrue(r.combo >= 3, "combo ${r.combo}")
        assertTrue(game.score - before >= (100 + 200 + 400) * TeamGame.SCORE_MULT, "combo points: ${game.score - before}")
        // Finishing a level gives a heart bonus.
        assertTrue(botRun(0, 100f))
        assertTrue(game.score >= TeamGame.MAX_HEARTS * 1000 * TeamGame.SCORE_MULT)
    }

    @Test fun saveGameFromThePauseScreen() {
        game.newGame(2, Mon.SPOOKY)
        run(3.2f)
        game.foes.clear()
        repeat(60 * 6) { botStep() }
        input.right = false; input.jump = false
        run(1f)
        val x = game.rocco.x
        assertTrue(x > 300f)
        game.inventory[Ore.GOLD.ordinal] = 7
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        assertTrue(game.paused)
        tap(game.viewW / 2, 198f)
        assertTrue(game.slotSpotSaved(2), "saved from the pause screen")
        // Load it again: same level, same spot, same stuff.
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(2)
        assertEquals(game.levelIndex, g2.levelIndex)
        assertEquals(7, g2.inventory[Ore.GOLD.ordinal])
        assertEquals(Mon.SPOOKY, g2.mon)
        assertTrue(abs(g2.rocco.x - x) < 60f, "starts where it was saved: ${g2.rocco.x} vs $x")
    }

    @Test fun roccoMinesWithTheSameHitsAsBlocky() {
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        val lv = game.level
        // Find an iron block with open air above it.
        val (ix, iy) = (0 until lv.w).flatMap { x -> (2 until ROWS).map { x to it } }
            .first { (x, y) -> lv[x, y] == Tile.IRON && lv[x, y - 1] == Tile.EMPTY && lv[x, y - 2] == Tile.EMPTY }
        // Blocky's pickaxe: how many hits does iron take?
        var blockyHits = 0
        lv[ix, iy] = Tile.IRON
        while (lv[ix, iy] == Tile.IRON && blockyHits < 20) { game.mine(ix, iy); blockyHits++ }
        // Rocco's butt slams on the same kind of block.
        lv[ix, iy] = Tile.IRON
        val iron = game.inventory[Ore.IRON.ordinal]
        var slams = 0
        while (lv[ix, iy] == Tile.IRON && slams < 20) {
            r.x = ix * TILE + 2f; r.y = iy * TILE - r.h - 40f; r.vy = 0f; r.onGround = false
            game.update(dt)
            input.down = true; game.update(dt); input.down = false
            run(0.8f) { game.hurtProof() }
            slams++
        }
        assertEquals(blockyHits, slams, "same number of hits for Rocco and Blocky")
        assertTrue(game.inventory[Ore.IRON.ordinal] > iron, "Rocco gets the ore")
        // Bonking a stone block from below takes the same number of hits too.
        lv[ix, iy] = Tile.STONE
        lv[ix, iy + 1] = Tile.EMPTY; lv[ix, iy + 2] = Tile.EMPTY
        var bonks = 0
        while (lv[ix, iy] == Tile.STONE && bonks < 20) {
            r.x = ix * TILE + 2f; r.y = (iy + 1) * TILE + 2f; r.vy = -300f; r.onGround = false
            run(0.3f) { game.hurtProof() }
            bonks++
        }
        lv[ix, iy] = Tile.STONE
        var stoneHits = 0
        while (lv[ix, iy] == Tile.STONE && stoneHits < 20) { game.mine(ix, iy); stoneHits++ }
        assertEquals(stoneHits, bonks, "bonks match pickaxe hits")
        // Diamonds need an iron pickaxe, for Rocco too.
        lv[ix, iy] = Tile.DIAMOND
        repeat(6) { game.mine(ix, iy, game.pickTier, rocco = true) }
        assertEquals(Tile.DIAMOND, lv[ix, iy])
    }

    @Test fun tanookiAndWingCap() {
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        run(0.5f)
        // Wing Cap: hold JUMP and fly way up.
        game.startWingCap()
        input.jump = true
        run(2f)
        assertTrue(r.y < 80f, "flying high: y=${r.y}")
        input.jump = false
        run(3f)
        // Tanooki: holding JUMP floats down slowly, and ACTION is a tail spin.
        game.startTanooki()
        r.y = 60f; r.vy = 0f; r.onGround = false
        input.jump = true
        run(0.6f)
        assertTrue(r.vy <= 45f, "floating: vy=${r.vy}")
        input.jump = false
        run(3f)
        val left = Grumbler(r.x - 14f, r.bottom, false).also { it.active = true; it.vx = 0f }
        val right = Grumbler(r.x + 20f, r.bottom, false).also { it.active = true; it.vx = 0f }
        game.foes += left; game.foes += right
        game.hurtProof()
        input.action = true; game.update(dt); input.action = false
        assertTrue(left.dying && right.dying, "the tail spin hit both sides")
        // Both come out of the rainbow mystery blocks.
        assertTrue(game.thingsForTest().none { it is LeafItem })
    }

    @Test fun armorWearsDownAndBreaks() {
        startPlaying()
        val armorTab = TeamGame.TABS.indexOf("ARMOR")
        game.inventory[Ore.IRON.ordinal] = 7
        game.coins = 10
        game.craft(0, armorTab)
        assertEquals(Armor.NONE, game.armorType, "armor is expensive: 8 iron + 10 coins")
        game.inventory[Ore.IRON.ordinal] = 8
        game.craft(0, armorTab)
        assertEquals(Armor.IRON, game.armorType)
        val hearts = game.hearts
        repeat(Armor.IRON.durability) {
            game.hurt()
            run(2f)
        }
        assertEquals(hearts, game.hearts, "the armor took every hit")
        assertEquals(Armor.NONE, game.armorType, "then it broke")
        game.hurt()
        assertEquals(hearts - 1, game.hearts)
    }

    @Test fun blackFlowerGetsEveryoneNearby() {
        startPlaying()
        val weapons = TeamGame.TABS.indexOf("WEAPONS")
        val row = game.recipes(weapons).indexOfFirst { it.kind == TeamGame.Kind.BLACK }
        game.craft(row, weapons)
        assertEquals(0, game.blackFlowers, "hard to get")
        game.inventory[Ore.DIAMOND.ordinal] = 3
        game.inventory[Ore.AMETHYST.ordinal] = 3
        game.inventory[Ore.EMERALD.ordinal] = 3
        game.coins = 20
        game.craft(row, weapons)
        assertEquals(1, game.blackFlowers)
        val r = game.rocco
        val near = (0 until 3).map { Shellback(r.x + 40f + it * 30f, r.bottom).also { f -> f.active = true } }
        val far = Shellback(r.x + 400f, r.bottom).also { it.active = true }
        game.foes.clear(); game.foes += near; game.foes += far
        input.blackFlower = true; game.update(dt); input.blackFlower = false
        assertTrue(near.all { it.dying }, "everything close is gone")
        assertTrue(!far.dying, "far away is safe")
        assertEquals(0, game.blackFlowers, "one use")
    }

    @Test fun enemiesHaveHealthBars() {
        startPlaying()
        val g = game.foes.filterIsInstance<Grumbler>().first()
        assertEquals(3, g.maxHp, "tougher bad guys")
        g.hit(game, 1)
        assertEquals(2, g.hp)
        g.hit(game, 1)
        assertTrue(!g.dying)
        g.hit(game, 1)
        assertTrue(g.dying)
    }

    @Test fun swapCyclesThroughTheWholeTeam() {
        startPlaying()
        val seen = ArrayList<Who>()
        repeat(3) {
            seen += game.leaderWho
            input.swap = true; game.update(dt); input.swap = false; game.update(dt)
        }
        assertEquals(listOf(Who.ROCCO, Who.BLOCKY, Who.MON), seen)
        assertEquals(Who.ROCCO, game.leaderWho)
    }

    @Test fun pokemonSuperMoveIsABigBoost() {
        for (m in Mon.entries) {
            game.newGame(0, m)
            run(3.2f)
            val g = game.foes.filterIsInstance<Shellback>().first()
            game.foes.retainAll(listOf(g))
            val r = game.rocco
            r.x = g.x - 60f; r.y = g.y; r.facing = 1
            game.partner.x = r.x - 10; game.partner.y = r.y; game.partner.facing = 1
            run(1.5f) { game.hurtProof() }
            g.active = true
            assertTrue(game.superCharge < 1f, "starts uncharged")
            game.chargeSuper()
            input.superMove = true; game.update(dt); input.superMove = false; game.update(dt)
            assertTrue(g.dying || g.removed, "${m.superName} knocked out a 3-hp Shellback")
            assertTrue(game.superCharge < 0.01f, "${m.superName} has to recharge")
        }
    }

    @Test fun megaTntAndStar() {
        startPlaying()
        game.foes.forEach { it.active = true }
        val onScreen = game.foes.filter { it.x < game.camX + game.viewW }
        assertTrue(onScreen.isNotEmpty())
        game.inventory[Ore.AMETHYST.ordinal] = 2
        game.inventory[Ore.DIAMOND.ordinal] = 1
        game.craft(game.recipes(1).indexOfFirst { it.title == "SUPER STAR" }, tab = 1)
        assertTrue(game.starT > 0)
        val hearts = game.hearts
        game.hurt()
        assertEquals(hearts, game.hearts, "stars make you invincible")
    }

    @Test fun pickYourPokemonFromTheMenus() {
        run(0.5f)
        tap(); assertEquals(TeamGame.State.SLOTS, game.state)
        run(0.4f)
        tap(game.slotCardXForTest(1) + 10f, 120f)
        assertEquals(TeamGame.State.PICK, game.state)
        run(0.4f)
        tap(game.pickCardXForTest(2) + 10f, game.pickCardYForTest(2) + 20f)
        assertEquals(TeamGame.State.PICK, game.state, "first tap just looks")
        run(0.5f)
        tap(game.pickCardXForTest(2) + 10f, game.pickCardYForTest(2) + 20f)
        assertEquals(TeamGame.State.INTRO, game.state)
        assertEquals(Mon.HYDRO, game.mon)
        assertEquals(1, game.slot)
        assertTrue(ears.said.any { "HYDRO" in it }, "I choose you!")
    }

    @Test fun saveFilesAreSeparate() {
        game.newGame(0, Mon.SPARKY)
        game.inventory[Ore.GOLD.ordinal] = 4
        game.startLevel(2)
        game.newGame(2, Mon.BLOOM)
        game.startLevel(5)
        val g2 = TeamGame(TeamInput(), ears, storage)
        assertEquals(2, g2.slotLevel(0))
        assertEquals(-1, g2.slotLevel(1))
        assertEquals(5, g2.slotLevel(2))
        assertEquals(Mon.BLOOM, g2.slotMon(2))
        g2.continueGame(0)
        assertEquals(2, g2.levelIndex)
        assertEquals(Mon.SPARKY, g2.mon)
        assertEquals(4, g2.inventory[Ore.GOLD.ordinal])
        g2.eraseSlot(2)
        assertEquals(-1, g2.slotLevel(2))
    }

    @Test fun buildAHouseAndSleepToSave() {
        game.newGame(1, Mon.BLAZE)
        run(3.2f)
        game.foes.clear()
        repeat(60 * 5) { botStep() }
        input.right = false; input.jump = false
        run(1f)
        val homeTab = 2
        game.inventory[Ore.STONE.ordinal] = 14
        game.inventory[Ore.IRON.ordinal] = 2
        game.coins = 10
        // Walk to flat ground first if needed.
        var tries = 0
        while (!game.recipes(homeTab)[0].enabled && tries++ < 200) { input.right = true; game.update(dt) }
        input.right = false
        run(0.5f)
        game.craft(0, homeTab)
        game.craft(1, homeTab)
        assertEquals(0, game.inventory[Ore.STONE.ordinal])
        assertEquals(0, game.coins)
        val bed = game.bedForTest()!!
        val l = game.leader
        l.x = bed.cx - l.w / 2; l.y = bed.bottom - l.h - 1
        run(0.3f)
        input.action = true; game.update(dt); input.action = false
        assertEquals(TeamGame.State.SLEEP, game.state)
        run(3f)
        assertEquals(TeamGame.State.PLAY, game.state)
        assertTrue(game.slotSlept(1))
        // Load the file again: the house and bed are still there and you wake up by the bed.
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(1)
        val bed2 = g2.bedForTest()
        assertNotNull(bed2)
        assertTrue(kotlin.math.abs(g2.rocco.x - bed2.x) < 40f, "woke up next to the bed")
        assertEquals(Mon.BLAZE, g2.mon)
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
        game.newGame(0, Mon.SPARKY)
        game.startLevel(4)
        game.newGame(2, Mon.BLOOM)
        tap(); run(0.2f)
        // Back to the title to show the save files and the Pokemon picker.
        val menu = TeamGame(input, ears, storage)
        menu.update(0.5f); input.tapX = 10f; input.tapY = 10f; input.tap = true; menu.update(dt)
        val img = { name: String, g: TeamGame ->
            val i = BufferedImage((g.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            g.render(AwtGfx(i, 3f)); ImageIO.write(i, "png", File(dir, "$name.png"))
        }
        img("slots", menu)
        menu.update(0.5f); input.tapX = menu.slotCardXForTest(1) + 10f; input.tapY = 120f; input.tap = true; menu.update(dt)
        menu.update(1f)
        img("pick", menu)

        game.newGame(0, Mon.BLAZE)
        run(3.2f)
        run(1.2f) { input.right = true }
        input.right = false
        shot("hills")
        input.swap = true; game.update(dt); input.swap = false
        game.inventory[Ore.IRON.ordinal] = 4
        game.inventory[Ore.GOLD.ordinal] = 3
        game.inventory[Ore.STONE.ordinal] = 20
        game.coins = 12
        run(0.3f) { input.action = true }
        input.action = false
        shot("blocky-mining")
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        shot("craft")
        input.numberKey = 8; game.update(dt)
        shot("craft-supplies")
        input.numberKey = 9; game.update(dt)
        shot("craft-home")
        input.numberKey = 1; game.update(dt)
        input.numberKey = 2; game.update(dt)
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        run(0.5f)
        shot("house")
        input.swap = true; game.update(dt); input.swap = false; game.update(dt)
        game.chargeSuper()
        run(0.2f)
        input.superMove = true; game.update(dt); input.superMove = false
        run(0.3f)
        shot("super-flamethrower")
        game.startLevel(4); run(3.5f); run(2f) { input.right = true }; input.right = false
        shot("copper-canyon")
        game.startLevel(6); run(3.5f); run(3f) { input.right = true }; input.right = false
        shot("amethyst-mines")
        game.startLevel(7); run(3.5f)
        val jr = game.foes.filterIsInstance<KragJr>().single()
        game.leader.x = jr.homeX - 150f; game.leader.y = 100f
        run(4.5f) { game.hurtProof() }
        shot("wonder-krag-jr")
        fun bossShot(c: Char, name: String, y: Float = 160f, secs: Float = 4f) {
            game.startLevel(levelWith(c)); run(3.5f)
            val b = game.foes.first { it.boss }
            val home = when (b) { is KingKrag -> b.homeX; is Warden -> b.homeX; is EnderDragon -> b.homeX; else -> b.x }
            game.leader.x = home - 150f; game.leader.y = y
            run(secs) { game.hurtProof() }
            shot(name)
        }
        bossShot('Q', "wonder-king-krag")
        bossShot('G', "warden")
        bossShot('Y', "dry-bones-krag")
        bossShot('Z', "ender-dragon", secs = 6f)

        game.startLevel(Levels.all.indexOfFirst { it.place == Place.WATER }); run(3.5f)
        run(2.5f) { input.right = true; input.jump = (game.time * 3).toInt() % 2 == 0 }
        input.right = false; input.jump = false
        shot("coral-reef")

        game.startLevel(Levels.all.indexOfFirst { it.name == "VILLAGER VALLEY" }); run(3.5f)
        game.coins = 60
        val v = game.thingsForTest().filterIsInstance<Villager>().first()
        game.leader.x = v.cx - 6; game.leader.y = v.bottom - 24
        run(0.3f)
        shot("villager")
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        shot("trade")
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        val cart = game.thingsForTest().filterIsInstance<Minecart>().first()
        game.leader.x = cart.cx - 6; game.leader.y = cart.bottom - 24
        run(0.3f)
        input.action = true; game.update(dt); input.action = false
        run(1.5f) { game.hurtProof() }
        shot("minecart")
        input.poseidon = true; game.update(dt); input.poseidon = false
        run(1.2f)
        shot("poseidon")
        game.tnt = 3
        run(2f)
        input.tnt = true; game.update(dt); input.tnt = false
        run(0.8f)
        shot("tnt-grid")
        game.dogForTest()
        run(1.2f)
        shot("dennis")
        game.startLevel(Levels.all.indexOfFirst { it.name == "COPPER CANYON" }); run(3.5f)
        val bomb = game.foes.filterIsInstance<BombBomb>().first()
        game.leader.x = bomb.x - 70f; game.leader.y = bomb.bottom - 24f
        run(1.2f) { game.hurtProof() }
        shot("bomb-bomb")
        game.inventory[Ore.DIAMOND.ordinal] = 3
        game.inventory[Ore.GOLD.ordinal] = 3
        game.inventory[Ore.STONE.ordinal] = 4
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        while (game.recipes().first().kind != TeamGame.Kind.SWORD) { input.numberKey = 8; game.update(dt) }
        input.numberKey = 4; game.update(dt)
        shot("craft-swords")
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        while (game.leaderWho != Who.BLOCKY) { input.swap = true; game.update(dt); input.swap = false; game.update(dt) }
        run(0.3f)
        input.action = true; repeat(4) { game.update(dt) }; input.action = false
        shot("blocky-sword")
        game.startLevel(Levels.all.indexOfFirst { d -> d.rows.any { 'Q' in it } }); run(3.5f)
        game.pickStickerScreenForTest()
        run(1f)
        shot("stickers")
        game.pickSticker(1)
        run(0.6f)
        shot("sticker-picked")
        repeat(25) { game.giveStickerForTest(it * 3 % 40) }
        game.openBookForTest()
        run(0.2f)
        shot("sticker-book")
        game.startLevel(0); run(3.5f)
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        tap(game.viewW / 2, 198f)
        shot("pause")
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        game.inventory[Ore.DIAMOND.ordinal] = 11
        game.inventory[Ore.AMETHYST.ordinal] = 3
        game.inventory[Ore.EMERALD.ordinal] = 3
        game.coins = 40
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        while (game.recipes().first().kind != TeamGame.Kind.ARMOR) { input.numberKey = 8; game.update(dt) }
        input.numberKey = 3; game.update(dt)
        shot("craft-armor")
        while (game.recipes().first().kind != TeamGame.Kind.SWORD) { input.numberKey = 8; game.update(dt) }
        input.numberKey = 6; game.update(dt)
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        run(0.5f)
        shot("armor-and-black-flower")
        input.blackFlower = true; game.update(dt); input.blackFlower = false
        run(0.4f)
        shot("black-flower")
        while (game.leaderWho != Who.ROCCO) { input.swap = true; game.update(dt); input.swap = false; game.update(dt) }
        run(2f)
        game.startTanooki()
        game.startWingCap()
        game.spawnForTest(LeafItem(game.rocco.x + 60f, 120f))
        game.spawnForTest(WingCapItem(game.rocco.x + 90f, 120f))
        input.jump = true; run(0.7f); input.jump = false
        run(0.1f)
        shot("tanooki-wingcap")
    }
}
