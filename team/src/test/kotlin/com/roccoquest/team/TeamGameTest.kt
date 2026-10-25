package com.roccoquest.team

import com.roccoquest.game.Music
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Storage
import com.roccoquest.game.Voice
import com.roccoquest.game.TILE
import com.roccoquest.game.tileOf
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamGameTest {
    private class Ears : SoundSink {
        val played = ArrayList<Sound>()
        val said = ArrayList<String>()
        var music = Music.NONE
        override fun play(s: Sound) { played += s }
        override fun music(m: Music) { music = m }
        override fun say(text: String) { said += text }
        val voices = ArrayList<Voice>()
        override fun sayAs(text: String, voice: Voice) { voices += voice; say(text) }
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

    @Test fun upstairsCanBeBuiltFromTheRoomInEveryHouseStyle() {
        for (type in HouseType.entries) {
            game.newGame(0)
            game.coins = 1000
            game.inventory.fill(100)
            game.buyHouse(type)
            game.enterHome()
            run(0.4f)
            walkHomeTo(Homes.ROOM_W - 20f)
            if (type == HouseType.COTTAGE) {
                val image = BufferedImage(960, 480, BufferedImage.TYPE_INT_ARGB)
                game.render(AwtGfx(image, 2f))
                File("build/screens").mkdirs()
                ImageIO.write(image, "png", File("build/screens/teamup-build-upstairs.png"))
            }
            // Up the ladder to the roof: the BUILD A FLOOR sign is up there.
            input.action = true; game.update(dt); input.action = false; game.update(dt)
            assertTrue(game.onRoof, "ladder goes to the roof: $type")
            walkHomeTo(Roof.SIGN_X)
            input.action = true; game.update(dt); input.action = false; game.update(dt)
            assertTrue(game.homeShop, "build sign opens shop: $type")
            assertEquals(3, game.shopTab)
            assertEquals(1, game.houseFloors, "opening shop must not spend materials")
            val coins = game.coins
            tap(game.viewW / 2, 200f)
            assertEquals(1, game.houseFloors, "the first tap only picks it")
            tap(game.viewW / 2, 200f)
            assertEquals(2, game.houseFloors)
            assertEquals(coins - 150, game.coins)
            game.enterHome()
            walkHomeTo(Homes.ROOM_W - 20f)
            input.action = true; game.update(dt); input.action = false; game.update(dt)
            assertEquals(1, game.homeFloor, "stairs work: $type")
            game.changeFloor(0)
            val restored = TeamGame(TeamInput(), ears, storage)
            restored.continueGame(0)
            assertEquals(type, restored.houseType)
            assertEquals(2, restored.houseFloors)
        }
    }

    @Test fun newSaveDoesNotInheritOldThreeDimensionalHouseBlocks() {
        game.newGame(0)
        game.slotSave("b3d0n", 1)
        game.slotSave("b3d0_0", 1)
        game.newGame(0)
        game.enterHome(); game.enter3d()
        assertTrue(game.house3d.blocks.isEmpty())
    }

    @Test fun invalidSavedHouseBlocksAreIgnored() {
        game.newGame(0)
        game.slotSave("b3d0n", 2)
        game.slotSave("b3d0_0", (15 shl 12) + 1)
        game.slotSave("b3d0_1", game.house3d.key(31, 0, 0) + 1)
        game.enterHome(); game.enter3d()
        assertTrue(game.house3d.blocks.isEmpty())
    }

    @Test fun ventIndexTracksTileEdits() {
        val level = Level(Levels.all.first { it.place == Place.WATER })
        val expected = (0 until level.w * ROWS).filter { level[it % level.w, it / level.w] == Tile.VENT }.toSet()
        assertEquals(expected, level.vents)
        assertTrue(expected.isNotEmpty())
        val k = expected.first()
        level[k % level.w, k / level.w] = Tile.EMPTY
        assertTrue(k !in level.vents)
        level[k % level.w, k / level.w] = Tile.VENT
        assertEquals(expected, level.vents)
    }

    @Test fun metalRoccoDoesNotSwitchOffAirVents() {
        startPlaying(Levels.all.indexOfFirst { it.place == Place.WATER })
        game.foes.clear()
        game.startMetal()
        val tx = (game.rocco.x / TILE).toInt() + 2
        game.level[tx, 12] = Tile.VENT
        run(2f)
        assertTrue(game.thingsForTest().any { it is Bubble }, "air bubbles still spawn during metal mode")
    }

    @Test fun distantThingsAreNotDrawnInWaterAndSpace() {
        for (place in listOf(Place.WATER, Place.RAINBOW)) {
            startPlaying(Levels.all.indexOfFirst { it.place == place })
            var nearDraws = 0
            var farDraws = 0
            fun probe(x: Float, draw: () -> Unit) = object : Thing(x, 80f, 16f, 16f) {
                override fun update(g: TeamGame, dt: Float) {}
                override fun draw(g: TeamGame, gfx: com.roccoquest.game.Gfx, ox: Float) { draw() }
            }
            game.spawn(probe(game.camX + 100f) { nearDraws++ })
            repeat(200) { game.spawn(probe(game.camX + game.viewW + 1000f + it * 20f) { farDraws++ }) }
            game.update(dt)
            game.render(AwtGfx(BufferedImage(480, 240, BufferedImage.TYPE_INT_ARGB), 1f))
            assertEquals(1, nearDraws)
            assertEquals(0, farDraws, "$place should only submit nearby objects")
        }
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
        assertTrue(Levels.all.last().rows.any { '}' in it }, "Galaxy Krag is the final boss")
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
        game.inventory[Ore.IRON.ordinal] = 5
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
        game.inventory[Ore.GOLD.ordinal] = 15
        game.craft(row(Power.FIRE))
        assertEquals(Power.FIRE, game.power)
        assertEquals(1, game.flowerLevel[Power.FIRE.ordinal])
        game.craft(row(Power.FIRE))
        game.craft(row(Power.FIRE))
        assertEquals(3, game.flowerLevel[Power.FIRE.ordinal])
        assertEquals(0, game.inventory[Ore.GOLD.ordinal])
        // Flowers go all the way to Lv5.
        game.inventory[Ore.GOLD.ordinal] = 50
        repeat(5) { game.craft(row(Power.FIRE)) }
        assertEquals(MAX_FLOWER_LV, game.flowerLevel[Power.FIRE.ordinal])
        assertEquals(40, game.inventory[Ore.GOLD.ordinal], "upgrades stop costing once maxed")
        // Grass Flower gives green armor that soaks up hits before hearts.
        game.inventory[Ore.EMERALD.ordinal] = 5
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
        game.inventory[Ore.IRON.ordinal] = 5
        // Tap the first row (the Boomerang Flower).
        run(0.3f)
        tap(game.viewW / 2, 72f + 13f)
        assertEquals(Power.NONE, game.power, "the first tap only picks it")
        tap(game.viewW / 2, 72f + 13f)
        assertEquals(Power.BOOM, game.power)
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    @Test fun roccoThrowsTheCraftedPower() {
        startPlaying()
        game.inventory[Ore.DIAMOND.ordinal] = 5
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
        val d = kotlin.math.abs(game.rocco.cx - game.partner.cx)
        assertTrue(d < 120f, "the Pokemon stays with Rocco: $d")
        assertTrue(game.blocky !in game.team, "only one of Rocco or Blocky is out")
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
            // Long levels (World 10) get time in proportion to their length.
            assertTrue(botRun(i, 120f * maxOf(1f, Levels.all[i].w / 220f)), "${Levels.all[i].name}: stuck at x=${game.leader.x / TILE}")
        }
    }

    /** The bot can walk and jump all the way to each boss arena. */
    private val BOSSES = BOSS_CHARS

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
        // Level 4 is an even one: a funny scene plays first, then the map.
        assertEquals(TeamGame.State.SCENE, game.state)
        run(1f)
        tap(game.viewW / 2, 120f)
        assertEquals(TeamGame.State.MAP, game.state)
        assertEquals(4, game.mapSel, "the map is ready for the next level")
    }

    @Test fun blockysBowsAndRoccosCrazyMushrooms() {
        startPlaying()
        game.foes.clear()
        Ore.entries.forEach { game.inventory[it.ordinal] = 50 }
        game.coins = 500
        // Craft a crossbow: Blocky gets a SHOOT button.
        val tab = TeamGame.TABS.indexOf("BOWS")
        val i = game.recipes(tab).indexOfFirst { it.bow == Bow.CROSSBOW }
        game.craft(i, tab)
        assertEquals(Bow.CROSSBOW, game.bow)
        assertTrue(game.recipes(tab).first { it.bow == Bow.BOW }.button == "BETTER", "no going back to a weaker bow")
        game.switchTo(Who.BLOCKY)
        val b = game.blocky
        val g = Grumbler(b.x + 70f, b.bottom, false).also { it.active = true; it.vx = 0f }
        game.foes += g
        b.facing = 1
        input.longJump = true; game.update(dt); input.longJump = false
        run(1f) { game.hurtProof() }
        assertTrue(g.hp < g.maxHp || g.dying, "the bolt hit")
        // Crazy ice: giant ice balls freeze bad guys.
        game.foes.clear()
        game.switchTo(Who.ROCCO)
        game.startCrazy(false)
        val r = game.rocco
        val g2 = Grumbler(r.x + 40f, r.bottom, false).also { it.active = true; it.vx = 0f }
        game.foes += g2
        r.facing = 1
        input.action = true; game.update(dt); input.action = false
        run(0.6f) { game.hurtProof() }
        assertTrue(g2.frozenT > 0f || g2.dying, "frozen solid")
    }

    @Test fun weaponScreenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 240 * 3, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        startPlaying()
        Ore.entries.forEach { game.inventory[it.ordinal] = 50 }
        game.coins = 500
        game.openCraftForTest(TeamGame.TABS.indexOf("BOWS"))
        shot("craft-bows")
        game.closeCraftForTest()
        game.craft(game.recipes(TeamGame.TABS.indexOf("BOWS")).indexOfFirst { it.bow == Bow.ENCHANTED }, TeamGame.TABS.indexOf("BOWS"))
        game.switchTo(Who.BLOCKY)
        game.blocky.facing = 1
        input.longJump = true; game.update(dt); input.longJump = false
        run(0.15f) { game.hurtProof() }
        shot("blocky-enchanted-bow")
        game.switchTo(Who.ROCCO)
        game.startCrazy(true)
        game.rocco.facing = 1
        input.action = true; game.update(dt); input.action = false
        run(0.3f) { game.hurtProof() }
        game.spawnForTest(CrazyMushroom(game.rocco.x - 40f, game.rocco.y, false))
        run(0.4f) { game.hurtProof() }
        shot("rocco-crazy-fire")
    }

    @Test fun spookyWorldBosses() {
        assertEquals("SPOOKY STREET", Levels.all[105].name)
        for ((name, check) in listOf<Pair<String, (Foe) -> Boolean>>(
            "BOO-HOO'S THRONE" to { it is KingBooHoo }, "THE WITCH'S TOWER" to { it is WitchBoss }, "WARDEN TWINS" to { it is Warden },
            "ROCK STAR STAGE" to { it is RockStarKrag },
        )) {
            startPlaying(Levels.all.indexOfFirst { it.name == name })
            val bosses = game.foes.filter { check(it) }
            assertTrue(bosses.isNotEmpty(), "$name has its boss")
            game.foes.retainAll(bosses.toSet())
            val l = game.leader
            l.x = bosses.first().x - 140f; l.y = 120f
            run(4f) { game.hurtProof() }
            assertNotNull(game.boss, "$name: the boss noticed us")
            // The witch throws potions, King Boo-Hoo calls his ghosts.
            run(8f) { game.hurtProof() }
            if (name == "THE WITCH'S TOWER") assertTrue(ears.played.contains(Sound.FIRE), "potions thrown")
            if (name == "BOO-HOO'S THRONE") assertTrue(game.foes.any { it is Peekaboo }, "ghosts called")
            if (name == "ROCK STAR STAGE") assertTrue(game.foes.any { it is NoteShot }, "music notes!")
            assertEquals(when (name) { "THE WITCH'S TOWER" -> Music.WITCHY; "WARDEN TWINS" -> Music.WARDEN; "ROCK STAR STAGE" -> Music.RACE; else -> Music.SPOOKY }, ears.music)
            var t = 0f
            while (game.state == TeamGame.State.PLAY && t < 60f) {
                game.hurtProof()
                // Walk up to the last boss so the second Warden twin hears us too.
                l.x = bosses.last().x - 60f
                for (b in bosses) if (!b.dying) b.hit(game, 5)
                run(0.4f) { game.hurtProof() }
                t += 0.4f
            }
            assertTrue(bosses.all { it.dying || it.hp <= 0 }, "$name: beaten")
            assertTrue(game.state == TeamGame.State.CLEAR || game.state == TeamGame.State.STICKER, "$name: level clear (${game.state})")
        }
    }

    @Test fun spookyScreenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 240 * 3, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        startPlaying(Levels.all.indexOfFirst { it.name == "WITCH WOODS" })
        val w = game.foes.firstOrNull { it is Witch }
        if (w != null) { game.leader.x = w.x - 90f; game.leader.y = w.y - 30f }
        run(4f) { game.hurtProof() }
        shot("w6-witch-woods")
        for (name in listOf("BOO-HOO'S THRONE", "THE WITCH'S TOWER", "WARDEN TWINS")) {
            startPlaying(Levels.all.indexOfFirst { it.name == name })
            val b = game.foes.first { it.boss }
            game.leader.x = b.x - 120f; game.leader.y = 120f
            run(5f) { game.hurtProof() }
            shot("w6-" + name.lowercase().replace(" ", "-").replace("'", ""))
        }
    }

    @Test fun decorateAnywhereAndBuildIn3D() {
        startPlaying()
        game.coins = 5000
        Ore.entries.forEach { game.inventory[it.ordinal] = 90 }
        goHome()
        game.buyFloor()
        game.buyFurniture(Furniture.COUCH)
        game.buyFurniture(Furniture.TABLE)
        // Pick up the couch and carry it upstairs.
        game.toggleDecorate()
        assertTrue(game.decorating)
        walkHomeTo(Homes.spot(Furniture.COUCH) + 20f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertEquals(Furniture.COUCH, game.carrying)
        // No room on top of the table!
        walkHomeTo(Homes.spot(Furniture.TABLE) + 18f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertEquals(Furniture.COUCH, game.carrying, "it doesn't fit there")
        walkHomeTo(Homes.STAIRS_X + 8f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertEquals(1, game.homeFloor)
        walkHomeTo(600f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertNull(game.carrying)
        assertEquals(1, game.furnFloor(Furniture.COUCH))
        assertTrue(kotlin.math.abs(game.furnX(Furniture.COUCH) + Homes.width(Furniture.COUCH) / 2 - 600f) < 6f)
        game.toggleDecorate()
        // 3D: put down a block, break it, put down another; it's saved.
        game.enter3d()
        assertTrue(game.home3d)
        val h = game.house3d
        h.pitch = -0.7f
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertEquals(1, h.blocks.size, "a block was placed")
        input.jump = true; game.update(dt); input.jump = false; game.update(dt)
        assertEquals(0, h.blocks.size, "and broken")
        h.selected = Block3D.GOLD.ordinal
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        // Walk and turn around, then render (no crash).
        input.up = true; run(1f); input.up = false
        input.left = true; run(1f); input.left = false
        val img = BufferedImage((game.viewW * 2).toInt(), 480, BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img, 2f))
        game.exit3d()
        assertTrue(!game.home3d)
        game.buyBed(BedStyle.RED)
        game.sleepAtHome()
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertEquals(1, g2.furnFloor(Furniture.COUCH))
        g2.enterHome(); g2.changeFloor(1); g2.enter3d()
        assertEquals(Block3D.GOLD.ordinal, g2.house3d.blocks.values.single())
        TeamControls.mode3d = false
    }

    @Test fun house3dScreenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 240 * 3, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        startPlaying()
        game.coins = 9000
        Ore.entries.forEach { game.inventory[it.ordinal] = 99 }
        repeat(60) { game.clearForTest(it) }
        goHome()
        Furniture.entries.forEach { game.buyFurniture(it) }
        game.buyBed(BedStyle.BLUE)
        repeat(80) { game.givePlushie() }
        game.toggleDecorate()
        walkHomeTo(Homes.spot(Furniture.LAMP) + 10f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        walkHomeTo(150f)
        run(0.2f)
        shot("home-decorate")
        game.toggleDecorate()
        game.enter3d()
        val h = game.house3d
        h.x = 12f; h.z = 8.5f; h.yaw = -1.9f
        for (bx in 6..10) for (by in 0..2) h.blocks[h.key(bx, by, 5)] = if (by == 2) Block3D.GLASS.ordinal else Block3D.BRICK.ordinal
        h.blocks[h.key(11, 0, 6)] = Block3D.GOLD.ordinal
        h.blocks[h.key(12, 0, 6)] = Block3D.DIAMOND.ordinal
        run(0.3f)
        shot("home-3d")
        h.x = 20f; h.z = 8f; h.yaw = -1.3f
        run(0.3f)
        shot("home-3d-shelves")
        game.exit3d()
        TeamControls.mode3d = false
    }

    @Test fun tenFloorsAndTheRoofWithACityView() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 240 * 3, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        startPlaying()
        game.coins = 30000
        Ore.entries.forEach { game.inventory[it.ordinal] = 400 }
        goHome()
        // An old save had its backyard things on "floor 9": they stay in the backyard.
        game.slotSave("decorV", 0)
        game.slotSave("decorN", 1)
        game.slotSave("decor_0", 1 + DecorItem.GNOME.ordinal + 1000 * 128 + 9 * 128 * 8192)
        game.continueGame(0)
        assertEquals(Decor.YARD, game.decor.single().floor)
        goHome()
        game.coins = 30000
        Ore.entries.forEach { game.inventory[it.ordinal] = 400 }
        repeat(12) { game.buyFloor() }
        assertEquals(Homes.MAX_FLOORS, game.houseFloors, "10 floors at most")
        assertEquals(10, Homes.MAX_FLOORS)
        // The ladder at the end of the top floor goes up to the roof.
        repeat(9) { game.changeFloor(game.homeFloor + 1) }
        assertEquals(9, game.homeFloor)
        walkHomeTo(Homes.STAIRS_X + 8f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertTrue(game.onRoof)
        // Pools can go on the roof. Look through the telescope.
        game.buyDecor(DecorItem.ROUND_POOL)
        assertTrue(game.decor.any { it.item == DecorItem.ROUND_POOL && it.floor == Decor.ROOF })
        walkHomeTo(Roof.SCOPE_X + 10f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        run(0.5f)
        shot("roof")
        // In 3D: the roof with the city all around.
        game.enter3d()
        val h = game.house3d
        h.x = 10f; h.z = 5f; h.yaw = 1.2f; h.pitch = -0.25f
        h.blocks[h.key(12, 0, 5)] = Block3D.GOLD.ordinal
        run(0.3f)
        shot("roof-3d")
        h.yaw = -1.6f
        run(0.1f)
        shot("roof-3d-back")
        game.exit3d()
        // Down the hatch, then the elevator has a ROOF button too.
        walkHomeTo(Homes.STAIRS_X)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertFalse(game.onRoof)
        assertEquals(9, game.homeFloor)
        // The garden in 3D.
        game.enterGarden()
        walkHomeTo(400f)
        game.enter3d()
        h.x = 8f; h.z = 8f; h.yaw = -1.2f; h.pitch = -0.2f
        run(0.3f)
        shot("garden-3d")
        h.x = 40f; h.yaw = -1.9f
        run(0.1f)
        shot("yard-3d")
        h.blocks[h.key(60, 0, 3)] = Block3D.BRICK.ordinal
        h.pitch = -0.7f
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        game.exit3d()
        TeamControls.mode3d = false
        // Everything is saved: floors, the roof pool and the backyard blocks (even far down the yard).
        game.buyBed(BedStyle.RED)
        game.sleepAtHome()
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertEquals(10, g2.houseFloors)
        assertTrue(g2.decor.any { it.item == DecorItem.ROUND_POOL && it.floor == Decor.ROOF })
        assertTrue(g2.decor.any { it.item == DecorItem.GNOME && it.floor == Decor.YARD })
        g2.enterHome(); g2.enterGarden(); g2.enter3d()
        assertTrue(g2.house3d.blocks.containsKey(g2.house3d.key(60, 0, 3)), "a block far down the yard is kept")
        TeamControls.mode3d = false
        // The map shows the tall house.
        game.goToMap(-1); run(0.5f)
        shot("map-skyscraper")
    }

    @Test fun carvePumpkinsWithYourFingerAndRemoveThem() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 240 * 3, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        startPlaying()
        goHome()
        game.coins = 100
        game.enterGarden()
        walkHomeTo(Pumpkins.TABLE_X + 30f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        val c = assertNotNull(game.carver, "the carving screen opens")
        // Draw a smile with a finger: a swipe across the pumpkin.
        val bx = c.boardX(game.viewW); val by = c.boardY()
        input.touching = true
        for (k in 0..20) {
            input.fingerX = bx + 50f + k * 4.5f; input.fingerY = by + 100f + kotlin.math.sin(k / 20f * 3.14f) * 14f
            game.update(dt)
        }
        // Two eyes, one dab each.
        for ((ex, ey) in listOf(70f to 50f, 120f to 50f)) {
            input.touching = false; input.fingerX = -1f; game.update(dt)
            input.touching = true; input.fingerX = bx + ex; input.fingerY = by + ey
            repeat(3) { game.update(dt) }
        }
        input.touching = false; input.fingerX = -1f; game.update(dt)
        assertTrue(c.cuts > 30, "carved ${c.cuts} bits")
        shot("pumpkin-carving")
        // DONE: it costs a pumpkin (15 coins, none grown yet) and glows in the yard.
        tap(c.buttonX(game.viewW) + 30f, c.buttonY(0) + 10f)
        assertNull(game.carver)
        assertEquals(1, game.lanterns.size)
        assertEquals(100 - Pumpkins.COINS, game.coins)
        // A grown pumpkin is used before coins. FACE carves a classic face.
        game.garden.basket[Crop.PUMPKIN.ordinal] = 1
        walkHomeTo(Pumpkins.TABLE_X + 30f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        tap(game.carver!!.buttonX(game.viewW) + 30f, game.carver!!.buttonY(1) + 10f)
        tap(game.carver!!.buttonX(game.viewW) + 30f, game.carver!!.buttonY(0) + 10f)
        assertEquals(2, game.lanterns.size)
        assertEquals(0, game.garden.basket[Crop.PUMPKIN.ordinal])
        assertEquals(100 - Pumpkins.COINS, game.coins)
        walkHomeTo(game.lanterns[1].x + 50f)
        run(0.3f)
        shot("jack-o-lanterns")
        // Saved.
        game.buyBed(BedStyle.RED); game.sleepAtHome()
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertEquals(2, g2.lanterns.size)
        assertTrue(g2.lanterns[1].rows.contentEquals(game.lanterns[1].rows))
        // Remove one: walk to it and press twice.
        walkHomeTo(game.lanterns[0].x + 15f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertEquals(2, game.lanterns.size, "the first press only asks")
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertEquals(1, game.lanterns.size)
        // Cancel gives the pumpkin back (nothing spent).
        val before = game.coins
        walkHomeTo(Pumpkins.TABLE_X + 30f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        tap(game.carver!!.buttonX(game.viewW) + 30f, game.carver!!.buttonY(3) + 10f)
        assertNull(game.carver)
        assertEquals(before, game.coins)
    }

    @Test fun world10VolcanoesMeteorsAndSpaceWalks() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 240 * 3, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        val w10 = Hazards.WORLD10
        assertEquals("LAVA LANDING", Levels.all[w10].name)
        // Longer levels: most of World 10 is way longer than the old levels (about 200 tiles).
        assertTrue(Levels.all.drop(w10).count { it.w >= 300 } >= 14)
        assertTrue(Levels.all.drop(w10).any { it.w >= 480 }, "some are REALLY long")
        // A volcano ERUPTS: a lava wave rolls in. Standing still, it hurts.
        startPlaying(w10)
        game.foes.clear()
        assertTrue(game.hazards.eruptions)
        var t = 0f
        while (game.foes.none { it is LavaWave } && t < 15f) { game.foes.removeAll { it !is LavaWave && it !is Meteor }; game.update(dt); t += dt }
        val wave = game.foes.filterIsInstance<LavaWave>().first()
        assertTrue(wave.x > game.camX + game.viewW, "it starts off the screen, so you can see it coming")
        run(1.2f)
        shot("w10-eruption")
        val hearts = game.hearts
        run(4f)
        assertTrue(game.hearts < hearts, "standing still, the lava gets you")
        // Jumping over it is safe.
        startPlaying(w10)
        game.foes.clear()
        t = 0f
        while (game.foes.none { it is LavaWave } && t < 15f) { game.foes.removeAll { it !is LavaWave }; game.update(dt); t += dt }
        val full = game.hearts
        var jumps = 0
        repeat((5f / dt).toInt()) {
            game.foes.removeAll { it !is LavaWave }
            val w2 = game.foes.filterIsInstance<LavaWave>().firstOrNull()
            val l = game.leader
            if (w2 != null && w2.x - l.x in 0f..30f && l.onGround) { input.jump = true; jumps++ }
            game.update(dt)
            input.jump = false
        }
        assertTrue(jumps > 0)
        assertEquals(full, game.hearts, "jumped over the lava")
        // METEOR SHOWER: red X marks, then meteors.
        val m = (w10 until Levels.all.size).first { Hazards.meteorLevel(it, Levels.all[it], 1) && Levels.all[it].place == Place.ORBIT }
        startPlaying(m)
        game.foes.clear()
        assertTrue(game.hazards.meteors)
        assertTrue(Style.spaceSuit, "spacesuits on for the space walk")
        t = 0f
        while (game.foes.none { it is Meteor } && t < 40f) { game.hurtProof(); game.foes.removeAll { it !is Meteor }; game.update(dt); t += dt }
        assertTrue(game.foes.any { it is Meteor }, "a meteor shower came")
        run(0.6f) { game.hurtProof() }
        shot("w10-spacewalk-meteors")
        // Walking away from every X: no meteor hits you.
        game.goToMap(-1); run(0.3f)
        assertFalse(Style.spaceSuit, "spacesuits off on the map")
        // The last level is the big boss rush, still ending with Galaxy Krag.
        assertTrue(Levels.all.last().rows.any { '}' in it } && Levels.all.last().rows.any { '!' in it })
    }

    @Test fun pokemonEvolveAndSwitchBack() {
        startPlaying()
        game.coins = 100
        // Ember's line: Scorch unlocks at level 4.
        game.switchMon(Mon.EMBER)
        assertEquals(Mon.EMBER, game.mon)
        assertTrue(!game.monUnlocked(Mon.SCORCH))
        game.switchMon(Mon.SCORCH)
        assertEquals(Mon.EMBER, game.mon, "not yet")
        while (game.monLevel() < 4) game.gainXp(5)
        assertEquals(Mon.SCORCH, game.evolveReady())
        goHome()
        walkHomeTo(Homes.MON_X + 20f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        game.evolve()
        run(2f)
        assertEquals(Mon.SCORCH, game.mon, "evolved!")
        assertEquals(4, game.monLevel(), "evolutions share the level")
        // And back again.
        game.switchMon(Mon.EMBER)
        assertEquals(Mon.EMBER, game.mon)
        // Every Pokemon can be drawn, and evolved ones use their first form's SUPER.
        val img = BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB)
        Mon.entries.forEachIndexed { i, m -> TeamArt.mon(AwtGfx(img, 1f), m, 10f + i * 19f, 80f, 0f, 1, false) }
        assertEquals(Mon.EMBER, Mon.SCORCH.base)
        assertEquals(93, Mon.entries.size)
        // The 50 newest and the three MEGAs, all at their in-game sizes (with Rocco for scale).
        val dex = BufferedImage(1500, 1450, BufferedImage.TYPE_INT_ARGB)
        java.awt.Graphics2D::class.java.cast(dex.graphics).apply { color = java.awt.Color(0xDDEEFF); fillRect(0, 0, 1500, 1450) }
        val dg = AwtGfx(dex, 3f)
        Mon.entries.forEachIndexed { i, m ->
            val x = 25f + (i % 12) * 40f
            val y = 40f + (i / 12) * 60f
            TeamArt.mon(dg, m, x, y, 0f, 1, false)
            dg.text(m.label, x, y + 9f, 4.5f, 0xFF202020.toInt(), 1)
        }
        dg.sprite(TeamArt.rocco(Power.NONE, 0), 460f, 460f, w = 16f * ROCCO_SCALE, h = 24f * ROCCO_SCALE)
        ImageIO.write(dex, "png", File("build/screens/pokedex.png"))
        // The newer Pokemon draw too (a big picture to look at), and join one every few levels beaten.
        val big = BufferedImage(1200, 260, BufferedImage.TYPE_INT_ARGB)
        Mon.entries.drop(20).forEachIndexed { i, m ->
            TeamArt.mon(ScaledGfx(AwtGfx(big, 1f), 2.5f, 30f + (i % 10) * 115f, 110f + (i / 10) * 120f), m, 0f, 0f, 0f, 1, false)
        }
        File("build/screens").mkdirs(); ImageIO.write(big, "png", File("build/screens/newmons.png"))
        assertFalse(game.monUnlocked(Mon.EEVO))
        repeat(NEW_MON_EVERY) { game.clearForTest(it) }
        assertTrue(game.monUnlocked(Mon.EEVO))
        assertFalse(game.monUnlocked(Mon.RIBBON), "Ribbon needs Eevo at level 5")
        assertFalse(game.monUnlocked(Mon.RAYZOR))
        game.switchMon(Mon.EEVO)
        assertEquals(Mon.EEVO, game.mon)
        assertEquals(Mon.SPARKY, Mon.EEVO.base)
    }

    @Test fun cappyMegaPropellerAndGold() {
        startPlaying()
        game.foes.clear()
        game.inventory[Ore.SAPPHIRE.ordinal] = 20
        game.inventory[Ore.GOLD.ordinal] = 20
        game.inventory[Ore.DIAMOND.ordinal] = 20
        game.inventory[Ore.AMETHYST.ordinal] = 20
        val tab = TeamGame.TABS.indexOf("FLOWERS")
        game.craft(game.recipes(tab).indexOfFirst { it.icon == Power.CAPPY }, tab)
        assertEquals(Power.NONE, game.power, "Cappy is locked at first")
        repeat(TeamGame.CAPPY_LEVELS) { game.clearForTest(it) }
        game.craft(game.recipes(tab).indexOfFirst { it.icon == Power.CAPPY }, tab)
        assertEquals(Power.CAPPY, game.power)
        // Throw Cappy at a Hopper: Rocco becomes a Hopper and jumps way higher.
        val r = game.rocco
        r.facing = 1
        val hop = Hopper(r.x + 40f, r.bottom).also { it.active = true; it.vx = 0f }
        game.foes += hop
        input.action = true; game.update(dt); input.action = false
        run(0.6f) { game.hurtProof() }
        assertEquals(CaptureKind.HOPPER, game.captureKind, "captured!")
        assertTrue(hop !in game.foes)
        // Getting hit just pops Rocco out (no heart lost).
        val hearts = game.hearts
        game.noInvulnForTest()
        game.hurt()
        assertNull(game.capture)
        assertEquals(hearts, game.hearts)
        // Mega Rocco squashes bad guys and can't be hurt.
        game.startMega()
        val g = Grumbler(r.x + 10f, r.bottom, false).also { it.active = true }
        game.foes += g
        game.noInvulnForTest()
        run(0.5f)
        assertTrue(g.dying, "squashed")
        assertEquals(hearts, game.hearts)
        game.megaT = 0f
        // Propeller: JUMP in the air spins way up.
        game.startPropeller()
        input.jump = true; game.update(dt); input.jump = false
        run(0.2f)
        input.jump = true; game.update(dt); input.jump = false
        assertTrue(r.vy < -400f, "spun up: ${r.vy}")
        // Gold flower: hits make coins.
        run(2f)
        game.craft(game.recipes(tab).indexOfFirst { it.icon == Power.GOLD }, tab)
        assertEquals(Power.GOLD, game.power)
    }

    @Test fun powerScreenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 240 * 3, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        val g = Grumbler(r.x + 10f, r.bottom, false).also { it.active = true }
        game.foes += g
        game.capture(g)
        run(0.3f) { game.hurtProof() }
        shot("power-capture")
        game.releaseCapture()
        game.startMega()
        run(0.3f) { game.hurtProof() }
        shot("power-mega")
        game.megaT = 0f
        game.startPropeller()
        game.spawnForTest(MegaItem(r.x + 30f, r.y)); game.spawnForTest(PropellerItem(r.x + 60f, r.y))
        run(0.2f) { game.hurtProof() }
        shot("power-propeller")
    }

    @Test fun winterScreenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 240 * 3, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        for (name in listOf("HAMMER HILLS", "BLIZZARD PASS", "NORTHERN LIGHTS", "CANDY LANE", "CHOMPY CHASE")) {
            startPlaying(Levels.all.indexOfFirst { it.name == name })
            val f = game.foes.firstOrNull { it is Snowman || it is HammerHank || it is Chompy || it is Gummy || it is Penguin }
            if (f != null) { game.leader.x = f.x - 100f; game.leader.y = f.y - 20f }
            run(2.5f) { game.hurtProof() }
            shot("w7-" + name.lowercase().replace(" ", "-"))
        }
        startPlaying(Levels.all.indexOfFirst { it.name == "ROCK STAR STAGE" })
        val b = game.foes.first { it is RockStarKrag }
        game.leader.x = b.x - 140f; game.leader.y = 120f
        run(6.3f) { game.hurtProof() }
        shot("w7-rock-star")
        game.unlockForTest(Levels.all.size)
        game.goToMap(140)
        run(0.5f)
        shot("w7-map")
    }

    @Test fun funnyScenesAndSpotty() {
        // Every scene plays all the way through without trouble, then goes to the map.
        startPlaying()
        for (k in Scenes.titles.indices) {
            game.startScene(1)
            assertEquals(k, game.sceneKind)
            run(Scenes.length(k) + 0.2f)
            assertEquals(TeamGame.State.MAP, game.state)
        }
        // Spotty runs up and yells something silly.
        startPlaying()
        game.foes.clear()
        game.spottyForTest()
        run(3f) { game.hurtProof() }
        assertTrue(game.thingsForTest().any { it is Spotty }, "Spotty showed up")
        assertTrue(Voice.TOAD in ears.voices, "he yelled")
    }

    @Test fun stickersAfterEveryLevel() {
        assertEquals(300, Stickers.all.size)
        assertEquals(60, Stickers.BASE)
        assertEquals(300, Stickers.all.map { it.name }.toSet().size, "all different")
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
        assertEquals(1, game.mapSel, "then on to the next level")
        // Stickers are saved with the file.
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertTrue(g2.hasSticker(got))
        // The sticker book opens from the pause screen.
        input.jump = true; game.update(dt); input.jump = false
        run(3.5f)
        assertEquals(TeamGame.State.PLAY, game.state)
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        assertTrue(game.paused)
        tap(game.viewW / 2, 150f)
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
        assertEquals(TeamGame.Tunes.bossMusic(game.levelIndex, fighting = true), ears.music, "a scary boss song")
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

    @Test fun enderDragonOpensWorld2() {
        startPlaying(WORLD2_START - 1)
        assertEquals("THE END", game.level.def.name)
        fightDragon()
        assertEquals(TeamGame.State.CLEAR, game.state, "World 1's dragon is not the end any more")
        run(3.6f)
        assertEquals(TeamGame.State.STICKER, game.state)
        game.pickSticker(0)
        run(3f)
        assertEquals(TeamGame.State.MAP, game.state)
        assertEquals(WORLD2_START, game.mapSel, "on to World 2")
        assertEquals(1, game.mapWorld, "the map shows World 2")
        assertEquals("SUNSET MEADOW", Levels.all[game.mapSel].name)
    }

    @Test fun finalBattleIsABossRushThatEndsTheGame() {
        assertTrue(Levels.all.size >= 84, "four worlds: ${Levels.all.size}")
        val big = Levels.all.indexOfFirst { it.name == "THE FINAL BATTLE" }
        startPlaying(big)
        val q = game.foes.filterIsInstance<KingKrag>().single()
        assertTrue(q.wonderStyle, "round 1 is Wonder King Krag")
        val r = game.rocco
        r.x = q.homeX - 150f
        run(3.5f) { game.hurtProof() }
        assertTrue(game.boss === q, "Wonder King Krag woke up")
        var t = 0f
        while (q.hp > 0 && t < 60f) { game.hurtProof(); q.hit(game, 3); game.update(dt); t += dt }
        assertEquals(0, q.hp)
        run(4f) { game.hurtProof() }
        assertEquals(TeamGame.State.PLAY, game.state, "the level keeps going after round 1")
        assertTrue(game.boss == null, "ready for the next boss")
        // A checkpoint after round 1 doesn't bring Wonder King Krag back.
        val cp = game.thingsForTest().filterIsInstance<Checkpoint>().maxBy { it.x }
        r.x = cp.x + 4f; run(1f) { game.hurtProof() }
        game.startLevel(game.levelIndex, fromCheckpoint = true)
        run(3.2f)
        assertTrue(game.foes.none { it is KingKrag }, "Krag stays beaten")
        fightDragon()
        assertEquals(TeamGame.State.CLEAR, game.state, "World 2's big battle opens World 3")
    }

    private fun fightDragon() {
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
        game.inventory[Ore.STONE.ordinal] = 8
        game.inventory[Ore.IRON.ordinal] = 3
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
        assertTrue(game.foes.none { it.dying }, "the wave starts behind the team and rolls forward")
        run(1.6f) { game.hurtProof() }
        assertTrue(game.foes.all { it.dying || it.boss || it.removed }, "simple bad guys were washed away")
        assertTrue(ears.said.any { "Ποσειδώνας" in it }, "Poseidon speaks Greek")
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
        val bubbles = game.thingsForTest().filterIsInstance<Bubble>().filter { game.inWater(it) }
        val b = bubbles.filter { it.y > 60f }.minByOrNull { it.y } ?: bubbles.firstOrNull()
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

    @Test fun quickTapBetweenFramesStillJumps() {
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        run(0.5f)
        assertTrue(r.onGround)
        // Finger goes down and up before the game even gets to look.
        input.jump = true; input.jump = false
        game.update(dt)
        assertTrue(r.vy < 0f, "a super-quick tap still jumps")
        // ...and goes a decent way up, not just a tiny hop.
        fun peak(holdFrames: Int): Float {
            run(1f)
            val y0 = r.y
            var top = y0
            input.jump = true
            if (holdFrames == 0) input.jump = false
            repeat(90) { i -> if (i == holdFrames) input.jump = false; game.update(dt); top = minOf(top, r.y) }
            input.jump = false
            return y0 - top
        }
        val quick = peak(0)
        val full = peak(40)
        assertTrue(quick > full * 0.4f, "a quick tap jumps a fair bit: $quick vs $full")
    }

    @Test fun jumpPressedJustBeforeLandingStillJumps() {
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        run(0.5f)
        val ground = r.y
        // Jump and double jump, then press JUMP again a tiny bit before touching down.
        input.jump = true; game.update(dt); input.jump = false; run(0.2f)
        input.jump = true; game.update(dt); input.jump = false
        var pressed = false
        var jumpedAgain = false
        repeat(240) {
            if (!pressed && !r.onGround && r.vy > 0f && r.y > ground - 8f) {
                pressed = true
                input.jump = true; game.update(dt); input.jump = false
            } else game.update(dt)
            if (pressed && r.vy < -100f) jumpedAgain = true
        }
        assertTrue(pressed, "got close to the ground")
        assertTrue(jumpedAgain, "the early press jumps as soon as Rocco lands")
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
        game.inventory[Ore.DIAMOND.ordinal] = 5
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
        assertTrue(g.dying || g.removed || g.hp < g.maxHp, "the blue shell hit the grumbler (it's not as strong as it was)")
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
        game.inventory[Ore.EMERALD.ordinal] = 2
        game.inventory[Ore.STONE.ordinal] = 4
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
        assertEquals(7, game.recipes(swordsTab).count { it.kind == TeamGame.Kind.SWORD }, "seven kinds of swords, with ruby and obsidian")
        game.craft(row(Sword.DIAMOND), swordsTab)
        assertEquals(Sword.NONE, game.sword, "no diamonds yet")
        game.inventory[Ore.STONE.ordinal] = 8
        game.craft(row(Sword.STONE), swordsTab)
        assertEquals(Sword.STONE, game.sword)
        game.inventory[Ore.DIAMOND.ordinal] = 5
        game.craft(row(Sword.DIAMOND), swordsTab)
        assertEquals(Sword.DIAMOND, game.sword)
        // You can't waste stuff making a weaker sword than the one you're holding.
        game.inventory[Ore.STONE.ordinal] = 8
        game.craft(row(Sword.STONE), swordsTab)
        assertEquals(Sword.DIAMOND, game.sword)
        assertEquals(8, game.inventory[Ore.STONE.ordinal], "nothing was used up")
        assertEquals("BETTER", game.recipes(swordsTab)[row(Sword.STONE)].button)

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
        game.inventory[Ore.AMETHYST.ordinal] = 5
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
        assertEquals(game.swordHp, g2.swordHp, "how worn the sword is gets saved too")
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
        // A super long combo (Wing Cap!) never wraps around to negative points.
        val (sx, sb) = r.x to r.bottom
        for (c in listOf(26, 31, 40)) {
            val f = Grumbler(sx, sb - 60f, false).also { f -> f.active = true; f.vx = 0f }
            r.combo = c
            r.x = f.cx - r.w / 2; r.y = f.y - r.h + 4; r.vy = 200f; r.onGround = false
            game.foes.clear(); game.foes += f
            val s0 = game.score
            game.update(dt)
            assertEquals(c + 1, r.combo)
            assertTrue(game.score > s0, "stomp number ${c + 1} still scores: ${game.score - s0}")
        }
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
        tap(game.viewW / 2, 180f)
        assertTrue(game.slotSpotSaved(2), "saved from the pause screen")
        // Load it again: the map is on the same level, and playing it puts you on the same spot.
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(2)
        assertEquals(game.levelIndex, g2.mapSel)
        g2.startLevel(g2.mapSel, restoreHome = true)
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
        game.inventory[Ore.IRON.ordinal] = 11
        game.coins = 25
        game.craft(0, armorTab)
        assertEquals(Armor.NONE, game.armorType, "armor is expensive: 12 iron + 25 coins")
        game.inventory[Ore.IRON.ordinal] = 12
        game.craft(0, armorTab)
        assertEquals(Armor.IRON, game.armorType)
        val hearts = game.hearts
        repeat(Armor.IRON.durability) {
            game.hurt()
            run(TeamGame.HURT_SAFE_TIME + 0.4f)
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
        assertEquals(5, g.maxHp, "tougher bad guys (3 x 1.4)")
        g.hit(game, 1)
        assertEquals(4, g.hp)
        g.hit(game, 3)
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
        game.inventory[Ore.AMETHYST.ordinal] = 3
        game.inventory[Ore.DIAMOND.ordinal] = 2
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

    @Test fun onlyRoccoOrBlockyIsOutWithThePokemon() {
        startPlaying()
        game.foes.clear()
        assertEquals(listOf(game.rocco, game.partner), game.team)
        run(0.5f) { input.right = true }
        input.right = false
        val spot = game.rocco.cx
        input.swap = true; game.update(dt); input.swap = false; game.update(dt)
        assertEquals(Who.BLOCKY, game.leaderWho)
        assertEquals(listOf(game.blocky, game.partner), game.team, "Blocky swaps in for Rocco")
        assertTrue(kotlin.math.abs(game.blocky.cx - spot) < 12f, "Blocky pops out where Rocco was")
        input.swap = true; game.update(dt); input.swap = false; game.update(dt)
        assertEquals(Who.MON, game.leaderWho)
        assertEquals(listOf(game.blocky, game.partner), game.team, "Blocky follows the Pokemon")
        input.swap = true; game.update(dt); input.swap = false; game.update(dt)
        assertEquals(Who.ROCCO, game.leaderWho)
        assertEquals(listOf(game.rocco, game.partner), game.team)
    }

    private fun levelWithChar(c: Char) = Levels.all.indexOfFirst { d -> d.rows.any { c in it } }

    @Test fun newMonstersAreInTheLevels() {
        for (c in "AHUT") assertTrue(levelWithChar(c) >= 0, "monster $c is in a level")
        for (c in "REx") assertTrue(levelWithChar(c) >= 0, "material $c is in a level")
        assertTrue(Levels.all.count { d -> d.rows.any { 'P' in it } } >= 8, "bonus portals every few levels")
    }

    @Test fun skellyArcherShootsArrows() {
        startPlaying()
        game.foes.clear()
        game.rocco.x = 122 * TILE.toFloat(); game.rocco.y = 100f; run(0.6f) { game.hurtProof() }
        val r = game.rocco
        val a = SkellyArcher(r.x + 150f, r.bottom).also { it.active = true }
        game.foes += a
        var arrows = 0
        run(4f) { game.hurtProof(); arrows = maxOf(arrows, game.foes.count { it is Arrow }) }
        assertTrue(arrows > 0, "the archer shot an arrow")
    }

    @Test fun shieldBlocksArrowsFromTheFront() {
        startPlaying()
        game.foes.clear()
        game.rocco.x = 122 * TILE.toFloat(); game.rocco.y = 100f; run(0.6f)
        game.inventory[Ore.IRON.ordinal] = 8
        game.coins = 10
        game.craft(game.recipes(TeamGame.TABS.indexOf("SHIELDS")).indexOfFirst { it.shield == Shield.IRON }, tab = TeamGame.TABS.indexOf("SHIELDS"))
        assertEquals(Shield.IRON, game.shieldType)
        val r = game.rocco
        r.facing = 1
        val hearts = game.hearts
        val arrow = Arrow(r.x + r.w + 2f, r.cy - 2f, -200f, 0f)
        game.foes += arrow
        game.update(dt); game.update(dt)
        assertEquals(hearts, game.hearts, "blocked!")
        assertEquals(Shield.IRON.durability - 1, game.shieldHp)
    }

    @Test fun hopperJumpsAtYou() {
        startPlaying()
        game.foes.clear()
        game.rocco.x = 122 * TILE.toFloat(); game.rocco.y = 100f; run(0.6f) { game.hurtProof() }
        val r = game.rocco
        val h = Hopper(r.x + 110f, r.bottom).also { it.active = true }
        game.foes += h
        var leapt = false
        run(2.5f) { game.hurtProof(); if (!h.onGround && h.vx < -60f) leapt = true }
        assertTrue(leapt, "the hopper leapt toward Rocco")
    }

    @Test fun gloopSplitsInTwo() {
        startPlaying()
        game.foes.clear()
        game.rocco.x = 122 * TILE.toFloat(); game.rocco.y = 100f; run(0.6f) { game.hurtProof() }
        val r = game.rocco
        val gl = Gloop(r.x + 200f, r.bottom).also { it.active = true }
        game.foes += gl
        gl.hit(game, 99)
        game.update(dt)
        assertEquals(2, game.foes.count { it is Gloop && !it.big }, "two little gloops")
    }

    @Test fun snapperPopsUpAndSpits() {
        startPlaying()
        game.foes.clear()
        game.rocco.x = 122 * TILE.toFloat(); game.rocco.y = 100f; run(0.6f) { game.hurtProof() }
        val r = game.rocco
        val sn = Snapper(r.x + 80f, r.bottom).also { it.active = true }
        game.foes += sn
        var seeds = 0
        run(3.5f) { game.hurtProof(); seeds = maxOf(seeds, game.foes.count { it is Seed }) }
        assertTrue(seeds > 0, "the snapper spat a seed")
    }

    @Test fun bonusPortalGoesToTheSunsetAndBack() {
        startPlaying(1)
        game.foes.clear()
        val portal = game.thingsForTest().filterIsInstance<Portal>().single()
        val r = game.rocco
        r.x = portal.x - 30f; r.y = portal.bottom - r.h
        var t = 0f
        while (!game.inBonus && t < 4f) { game.hurtProof(); input.right = true; game.update(dt); t += dt }
        input.right = false
        assertTrue(game.inBonus, "walked into the portal")
        assertEquals("SUNSET BONUS", game.level.def.name)
        // Open the treasure chest.
        val chest = game.thingsForTest().filterIsInstance<Chest>().single()
        r.x = chest.x; r.y = chest.bottom - r.h
        game.update(dt)
        assertTrue(chest.open)
        assertTrue(game.inventory[Ore.OBSIDIAN.ordinal] >= 1, "new materials from the chest")
        // The sun goes down and the team is back where the portal was.
        run(Games.BONUS) { game.hurtProof() }
        assertTrue(!game.inBonus)
        assertEquals(Levels.all[1].name, game.level.def.name)
        assertTrue(kotlin.math.abs(game.rocco.cx - portal.cx) < 30f, "back at the portal")
        assertTrue(game.thingsForTest().none { it is Portal }, "each portal works once")
    }

    private object Games { const val BONUS = TeamGame.BONUS_TIME + 1f }

    @Test fun betterGearIsNeverReplacedByWorseGear() {
        startPlaying()
        val armorTab = TeamGame.TABS.indexOf("ARMOR")
        val shieldTab = TeamGame.TABS.indexOf("SHIELDS")
        Ore.entries.forEach { game.inventory[it.ordinal] = 50 }
        game.coins = 200
        game.craft(game.recipes(armorTab).indexOfFirst { it.armor == Armor.DIAMOND }, armorTab)
        assertEquals(Armor.DIAMOND, game.armorType)
        val iron = game.inventory[Ore.IRON.ordinal]
        game.craft(game.recipes(armorTab).indexOfFirst { it.armor == Armor.IRON }, armorTab)
        assertEquals(Armor.DIAMOND, game.armorType, "weaker armor can't replace better armor")
        assertEquals(iron, game.inventory[Ore.IRON.ordinal], "and nothing was used up")
        game.craft(game.recipes(shieldTab).indexOfFirst { it.shield == Shield.RUBY }, shieldTab)
        game.craft(game.recipes(shieldTab).indexOfFirst { it.shield == Shield.STONE }, shieldTab)
        assertEquals(Shield.RUBY, game.shieldType)
        assertTrue(Armor.entries.all { it.durability <= 8 } && Shield.entries.all { it.durability <= 6 }, "armor and shields don't last forever")
    }

    @Test fun swordsAndPickaxesWearOut() {
        startPlaying()
        val swordsTab = TeamGame.TABS.indexOf("WEAPONS")
        game.inventory[Ore.STONE.ordinal] = 99
        game.craft(game.recipes(swordsTab).indexOfFirst { it.sword == Sword.STONE }, swordsTab)
        assertEquals(Sword.STONE.durability, game.swordHp)
        input.swap = true; game.update(dt); input.swap = false; game.update(dt)
        val b = game.blocky
        var swings = 0
        while (game.sword != Sword.NONE && swings < 200) {
            game.foes.clear()
            game.foes += Shellback(b.x + 12f, b.bottom).also { it.active = true }
            b.facing = 1
            game.hurtProof()
            input.action = true; game.update(dt); input.action = false
            run(0.35f)
            swings++
        }
        assertEquals(Sword.NONE, game.sword, "the sword broke after $swings hits")
        assertEquals(Sword.STONE.durability, swings)
        // Pickaxes too: an iron pickaxe breaks back down to wood.
        game.foes.clear()
        game.inventory[Ore.IRON.ordinal] = 5
        game.craft(0, tab = 1)
        assertEquals(2, game.pickTier)
        val lv = game.level
        var mined = 0
        while (game.pickTier == 2 && mined < 300) {
            val tx = tileOf(b.x + b.w + 3); val ty = tileOf(b.bottom - 4)
            lv[tx, ty] = Tile.DIRT
            b.facing = 1
            input.action = true; game.update(dt); input.action = false
            run(0.3f)
            mined++
        }
        assertEquals(1, game.pickTier, "the iron pickaxe broke after $mined swings")
    }

    @Test fun earthquakesCrackTheGroundIntoPits() {
        val quakeLevel = Levels.all.indices.first { it > 0 && it % 4 == 1 && Levels.all[it].shoreX < 0 && Levels.all[it].place != Place.SKY }
        startPlaying(quakeLevel)
        assertTrue(game.quakeLevel)
        game.foes.clear()
        run(0.5f) { game.hurtProof() }
        // Walk a little way in, then shake (the cracks are random, so give it a few tries).
        repeat(60 * 3) { game.hurtProof(); botStep() }
        input.right = false; input.jump = false
        var tries = 0
        while (game.quakeWarn <= 0f && tries++ < 20) { game.quakeNowForTest(); game.update(dt); if (game.quakeWarn <= 0f) run(0.3f) { botStep() } }
        input.right = false; input.jump = false
        assertTrue(game.quakeWarn > 0f, "the ground cracks first")
        val cols = game.quakeCols.toList()
        assertTrue(cols.isNotEmpty())
        run(TeamGame.QUAKE_WARN + 0.2f) { game.hurtProof() }
        val lv = game.level
        for (c in cols) assertTrue((3 until ROWS).none { Tile.solid(lv[c, it]) }, "column $c caved in")
        val sx = tileOf(game.safeXForTest() + 6)
        assertTrue(Tile.solid(lv[sx, tileOf(game.safeYForTest() + 2)]), "the safe spot isn't over a pit")
        assertTrue(!game.isQuakeLevelForTest(0), "the first level is calm")
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

    /** World 2: the intro card and a peek at some of the new levels. */
    @Test fun world2Screenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        game.newGame(0, Mon.SPARKY)
        game.startLevel(WORLD2_START)
        run(0.5f)
        shot("w2-intro")
        for ((name, col) in listOf(
            "SUNSET MEADOW" to 60, "SANDSTORM DUNES" to 30, "BLIZZARD BRIDGE" to 34, "SUNKEN SHIP" to 60,
            "VOLCANO PEAK" to 30, "PYRAMID OF SPIKES" to 28, "BOMB-BOMB FACTORY" to 22, "THE FINAL BATTLE" to 150,
        )) {
            game.startLevel(Levels.all.indexOfFirst { it.name == name })
            run(3.2f)
            game.leader.x = col * TILE.toFloat(); game.leader.y = 60f
            run(1.2f) { game.hurtProof() }
            shot("w2-" + name.lowercase().replace(" ", "-").replace("'", ""))
        }
    }

    /** The new monsters, shields, bonus room and Poseidon's wave. */
    @Test fun newStuffScreenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        r.x = 122 * TILE.toFloat(); r.y = 100f
        run(2.5f) { game.hurtProof() }
        game.inventory[Ore.RUBY.ordinal] = 7; game.inventory[Ore.IRON.ordinal] = 6; game.coins = 40
        game.inventory[Ore.SAPPHIRE.ordinal] = 3; game.inventory[Ore.OBSIDIAN.ordinal] = 2
        game.craft(game.recipes(TeamGame.TABS.indexOf("SHIELDS")).indexOfFirst { it.shield == Shield.RUBY }, tab = TeamGame.TABS.indexOf("SHIELDS"))
        val b = r.bottom
        game.foes += listOf(
            SkellyArcher(r.x + 190f, b), Hopper(r.x + 70f, b), Gloop(r.x + 120f, b), Snapper(r.x + 150f, b),
        ).onEach { it.active = true }
        run(1.6f) { game.hurtProof(); r.vx = 0f }
        shot("new-monsters")
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        repeat(TeamGame.TABS.indexOf("SHIELDS")) { input.numberKey = 8; game.update(dt) }
        input.numberKey = 0; game.update(dt)
        shot("craft-shields")
        input.craft = true; game.update(dt); input.craft = false; game.update(dt)
        game.coins = 60
        input.poseidon = true; game.update(dt); input.poseidon = false
        run(0.7f) { game.hurtProof() }
        shot("poseidon-wave")
        run(2.5f) { game.hurtProof() }
        // The bonus room.
        startPlaying(1)
        game.foes.clear()
        val portal = game.thingsForTest().filterIsInstance<Portal>().single()
        r.x = portal.x - 50f; r.y = portal.bottom - r.h
        run(2f)
        shot("portal")
        var t = 0f
        while (!game.inBonus && t < 4f) { input.right = true; game.update(dt); t += dt }
        input.right = false
        run(3f)
        run(0.8f) { input.right = true }
        input.right = false
        shot("bonus-room")
    }

    /** Plays the very end of the game while drawing every frame, then goes back to the title and keeps playing. */
    @Test fun theEndingGoesBackToTheTitleWithoutCrashing() {
        val img = BufferedImage((game.viewW * 2).toInt(), 480, BufferedImage.TYPE_INT_ARGB)
        val gfx = AwtGfx(img, 2f)
        fun frame() { game.update(dt); game.render(gfx) }
        game.newGame(0, Mon.SPARKY)
        game.startLevel(Levels.all.lastIndex)
        repeat(200) { frame() }
        // Beat both bosses.
        var t = 0
        while (game.state != TeamGame.State.STICKER && t++ < 60 * 60) {
            game.hurtProof()
            for (f in game.foes.toList()) if (f.boss && f.hp > 0) f.hit(game, 50)
            game.foes.filterIsInstance<EndCrystal>().forEach { it.hit(game, 50) }
            if (game.boss == null) game.leader.x += 3f
            frame()
        }
        assertEquals(TeamGame.State.STICKER, game.state, "reached the last sticker")
        game.pickSticker(0)
        repeat(60 * 4) { frame() }
        assertEquals(TeamGame.State.VICTORY, game.state)
        repeat(60 * 4) { frame() }
        input.tapX = 50f; input.tapY = 100f; input.tap = true
        repeat(60) { frame() }
        assertTrue(game.state != TeamGame.State.VICTORY, "left the ending: ${game.state}")
        repeat(60 * 3) { frame(); if (it % 30 == 0) { input.tapX = 60f; input.tapY = 120f; input.tap = true } }
    }

    private fun goHome() {
        game.goToMap(-1)
        run(0.4f)
        input.jump = true; game.update(dt); input.jump = false; game.update(dt)
        assertEquals(TeamGame.State.HOME, game.state)
    }

    private fun walkHomeTo(x: Float) {
        var t = 0
        while (kotlin.math.abs(game.homeX - x) > 4f && t++ < 600) {
            input.right = game.homeX < x; input.left = game.homeX > x
            game.update(dt)
        }
        input.left = false; input.right = false
    }

    @Test fun walkAroundTheHouseShopAndSleepToSave() {
        game.newGame(1, Mon.BLAZE)
        run(3.2f)
        game.coins = 1500
        Ore.entries.forEach { game.inventory[it.ordinal] = 60 }
        goHome()
        // Walk to the shop counter and open the shop.
        walkHomeTo(Homes.SHOP_X + 30f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertTrue(game.homeShop, "the shop opened")
        assertEquals(listOf("FURNITURE", "BEDS", "PLUSHIES", "HOUSES", "COLORS", "STICKERS", "DECOR", "YARD", "STYLE"), game.SHOP_TABS)
        game.buyBed(BedStyle.BLUE)
        assertEquals(BedStyle.BLUE, game.bedStyle, "a blue bed!")
        Furniture.entries.forEach { game.buyFurniture(it) }
        game.buyHouse(HouseType.MUSHROOM)
        assertEquals(HouseType.MUSHROOM, game.houseType)
        val fox = Plushies.BABY_FOX
        game.buyPlushie(fox)
        assertTrue(game.hasPlushie(fox), "a baby fox plushie")
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        assertTrue(!game.homeShop)
        // Sleep in the bed: it saves.
        walkHomeTo(Homes.BED_X + 20f)
        game.coins = 123
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(1)
        assertEquals(123, g2.coins, "sleeping saved the game")
        assertEquals(TeamGame.State.MAP, g2.state, "continuing goes to the world map")
        assertEquals(HouseType.MUSHROOM, g2.houseType)
        assertEquals(BedStyle.BLUE, g2.bedStyle)
        assertTrue(g2.hasPlushie(fox) && g2.hasFurniture(Furniture.FISH_TANK))
        // Out the door, back to the map.
        walkHomeTo(Homes.DOOR_X + 10f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertEquals(TeamGame.State.MAP, game.state)
    }

    @Test fun lugiaLevelsAndPlayingAtHome() {
        startPlaying()
        // Lugia is locked until level 3 is beaten.
        assertTrue(!game.monUnlocked(Mon.LUGIA))
        game.switchMon(Mon.LUGIA)
        assertEquals(Mon.SPARKY, game.mon)
        game.clearForTest(2)
        game.switchMon(Mon.LUGIA)
        assertEquals(Mon.LUGIA, game.mon)
        // Levels go up to 10 and no further, and make the SUPER move a bit (not too much) better.
        assertEquals(1, game.monLevel())
        val slow = game.superTime()
        game.gainXp(100000)
        assertEquals(TeamGame.MON_MAX_LEVEL, game.monLevel())
        assertTrue(game.superTime() < slow && game.superTime() > slow * 0.7f, "a little faster")
        assertTrue(game.superDmg(10) in 11..14, "a little stronger: ${game.superDmg(10)}")
        assertEquals(1, game.monLevel(Mon.SPARKY), "each Pokemon has its own level")
        // Playing at home: PET, PLAY BALL and TRAIN teach Sparky, until it gets sleepy.
        game.switchMon(Mon.SPARKY)
        goHome()
        walkHomeTo(Homes.MON_X + 20f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertTrue(game.monMenu, "the Pokemon menu opened")
        val xp = game.monXp[Mon.SPARKY.ordinal]
        for (b in listOf(0, 1, 2, 2, 2, 2)) { game.monButtonForTest(b); run(2f) }
        assertEquals(xp + 1 + 3 + 4 * 3, game.monXp[Mon.SPARKY.ordinal], "5 plays, then it's sleepy")
        assertEquals(0, game.monEnergy)
        // Paint the house and buy a sticker pack; it's all saved.
        game.coins = 100
        game.paintHouse(5)
        val stickers = game.stickerCount
        game.buyStickerPack()
        assertEquals(stickers + 3, game.stickerCount)
        game.giveStickerForTest(299)
        game.sleepAtHome()
        game.buyBed(BedStyle.RED)
        game.sleepAtHome()
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertEquals(5, g2.houseColor)
        assertEquals(Mon.SPARKY, g2.mon)
        assertEquals(TeamGame.MON_MAX_LEVEL, g2.monLevel(Mon.LUGIA))
        assertTrue(g2.hasSticker(299) && g2.stickerCount == game.stickerCount)
    }

    @Test fun toyWorldAndGalaxyWorld() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f)); ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        for ((name, file) in listOf("TOY BOX TOWN" to "w8-toys", "GLOWSHROOM GROVE" to "w8-fungi", "MOON LANDING" to "w9-moon", "SPACE STATION" to "w9-station")) {
            startPlaying(Levels.all.indexOfFirst { it.name == name })
            run(2.5f) { game.hurtProof(); input.right = true }
            input.right = false
            shot(file)
        }
        // Jumping on the Moon goes way higher.
        startPlaying(Levels.all.indexOfFirst { it.name == "MOON LANDING" })
        game.foes.clear()
        val l = game.leader
        val ground = l.bottom
        var top = ground
        input.jump = true
        run(1.2f) { game.hurtProof(); top = minOf(top, l.bottom) }
        input.jump = false
        assertTrue(ground - top > 90f, "moon jump: ${ground - top}")
        // The two new bosses fight, then go down.
        for ((name, check) in listOf<Pair<String, (Foe) -> Boolean>>("MECHA KRAG" to { it is MechaKrag }, "GALAXY KRAG" to { it is GalaxyKrag })) {
            startPlaying(Levels.all.indexOfFirst { it.name == name })
            val b = game.foes.single { check(it) }
            game.foes.retainAll(setOf(b))
            l.x = b.x - 140f; l.y = 120f
            run(6f) { game.hurtProof() }
            assertTrue(game.boss === b, "$name noticed us")
            assertTrue(game.foes.any { it is Missile || it is StarShot || it is ChordWave }, "$name attacks")
            shot("boss-" + name.lowercase().replace(" ", "-"))
            var t = 0f
            while (game.state == TeamGame.State.PLAY && t < 60f) {
                game.hurtProof()
                if (!b.dying) b.hit(game, 5)
                run(0.4f) { game.hurtProof() }
                t += 0.4f
            }
            assertTrue(b.dying, "$name beaten")
        }
    }

    @Test fun blastCannonsAndTheTailSpin() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f)); ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        // 1-1 has a cannon near the start: hop in and BLAST OFF.
        startPlaying(0)
        game.foes.clear()
        val c = game.thingsForTest().filterIsInstance<BlastCannon>().first()
        val l = game.leader
        l.x = c.cx - 20f; l.y = c.bottom - l.h
        run(0.3f) { game.hurtProof() }
        input.action = true; game.update(dt); input.action = false
        assertNotNull(game.inCannon, "in the cannon")
        run(0.5f) { game.hurtProof() }
        var wait = 0
        while (c.aim > 0.5f && wait++ < 300) { game.hurtProof(); game.update(dt) }
        shot("cannon-aim")
        val x0 = l.x
        input.jump = true; game.update(dt); input.jump = false
        assertNull(game.inCannon)
        assertTrue(game.cannonFlyT > 0f, "flying!")
        run(0.25f) { game.hurtProof() }
        shot("cannon-fly")
        run(1.5f) { game.hurtProof() }
        assertTrue(l.x > x0 + 120f, "flew a long way (${l.x - x0})")
        assertEquals(0f, game.cannonFlyT, "landed")

        // KRAG'S TAIL SPIN: punches don't work, the spike bombs do.
        startPlaying(Levels.all.indexOfFirst { it.name == "KRAG'S TAIL SPIN" })
        val k = game.foes.filterIsInstance<TailKrag>().single()
        game.foes.retainAll(setOf(k))
        l.x = k.x - 120f; l.y = 120f
        run(4f) { game.hurtProof() }
        assertTrue(game.boss === k, "he noticed us")
        k.hit(game, 50)
        assertEquals(3, k.hp, "hits just tickle")
        var tries = 0
        while (!k.dying && tries++ < 60) {
            game.hurtProof()
            if (k.mode == 0 || k.mode == 3) {
                // Sneak behind him (any side works while he's dizzy).
                val side = if (k.mode == 3) 1 else if (k.vx >= 0f) -1 else 1
                l.x = k.cx + side * 22f - l.w / 2; l.y = k.bottom - l.h; l.vx = 0f
                input.action = true; game.update(dt); input.action = false
                game.update(dt)
            }
            if (k.mode == 1) {
                run(0.4f + (tries % 5) * 0.13f) { game.hurtProof(); input.right = true }
                if (tries == 1) shot("tail-spin")
                input.right = false
                input.action = true; game.update(dt); input.action = false
            }
            run(1.6f) { game.hurtProof() }
        }
        assertTrue(k.dying, "thrown into the spike bombs 3 times (${k.hp} left after $tries tries)")
        run(6f) { game.hurtProof() }
        assertTrue(game.state == TeamGame.State.CLEAR || game.state == TeamGame.State.STICKER, "level clear (${game.state})")
    }

    @Test fun erasedSavesCanBeRecovered() {
        // A game with some progress in file 1.
        game.newGame(0, Mon.BLAZE)
        repeat(5) { game.clearForTest(it) }
        game.startLevel(5)
        game.coins = 777 + 60 + BedStyle.RED.coins
        game.buyDecor(DecorItem.GIANT_TEDDY)
        game.buyBed(BedStyle.RED)
        game.sleepAtHome()
        assertEquals(777, game.coins)
        val menu = TeamGame(input, ears, storage)
        menu.update(0.5f); input.tapX = 10f; input.tapY = 10f; input.tap = true; menu.update(dt)
        assertEquals(TeamGame.State.SLOTS, menu.state)
        menu.update(0.5f)
        val x = menu.slotCardXForTest(0) + 20f
        fun tapAt(y: Float) { input.tapX = x; input.tapY = y; input.tap = true; menu.update(dt); menu.update(dt) }
        // Oops! ERASE (two taps)...
        tapAt(188f); tapAt(188f)
        assertEquals(-1, menu.slotLevel(0), "erased")
        assertTrue(menu.hasBackup(0))
        val img = BufferedImage((menu.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
        menu.render(AwtGfx(img, 3f)); File("build/screens").mkdirs(); ImageIO.write(img, "png", File("build/screens/slots-recover.png"))
        // ...RECOVER (two taps) brings it all back.
        tapAt(188f); tapAt(188f)
        assertEquals(5, menu.slotLevel(0))
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertEquals(777, g2.coins)
        assertEquals(Mon.BLAZE, g2.mon)
        assertTrue(g2.decor.any { it.item == DecorItem.GIANT_TEDDY })
        // Starting a new game over a file keeps the old one as a safety copy too.
        g2.newGame(0, Mon.SPARKY)
        assertEquals(0, g2.coins)
        assertTrue(g2.recoverSlot(0))
        g2.continueGame(0)
        assertEquals(777, g2.coins)
    }

    @Test fun moviesAndLongerScenes() {
        startPlaying()
        goHome()
        game.coins = 2000
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f)); ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        assertEquals(26, Scenes.titles.size)
        // Every long scene plays all the way through, then it's back home to the movies.
        for (k in LongScenes.FIRST until Scenes.titles.size) {
            game.playScene(k, -2)
            assertEquals(TeamGame.State.SCENE, game.state)
            var now = 0f
            for ((i, at) in listOf(3.5f, 7.7f, 10.8f).withIndex()) {
                run(at - now); now = at
                if (game.state == TeamGame.State.SCENE) shot("scene-${k - LongScenes.FIRST}-$i")
            }
            run(3f)
            assertEquals(TeamGame.State.HOME, game.state)
            assertTrue(game.theater)
        }
        shot("movies")
        // Locked scenes can't be watched; seen ones can.
        assertTrue(game.scenesSeen and 1 == 0)
        game.buyFurniture(Furniture.TV)
        game.playScene(0, -2); run(6f)
        assertTrue(game.scenesSeen and 1 != 0)
    }

    @Test fun outfitsAndPokemonCostumes() {
        startPlaying()
        goHome()
        game.coins = 5000
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f)); ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        game.buyStyle(RoccoOutfit.SUPERHERO)
        assertEquals(RoccoOutfit.SUPERHERO, Style.rocco)
        assertEquals(5000 - 400, game.coins)
        game.buyStyle(BlockySkin.DIAMOND)
        game.buyStyle(MonCostume.CROWN)
        assertEquals(MonCostume.CROWN, Style.monCostume)
        // Tapping a worn costume takes it off (no charge); tapping again puts it back on.
        val c = game.coins
        game.buyStyle(MonCostume.CROWN); assertNull(Style.monCostume)
        game.buyStyle(MonCostume.CROWN); assertEquals(MonCostume.CROWN, Style.monCostume)
        assertEquals(c, game.coins)
        run(0.5f)
        shot("style-home")
        tap(game.viewW - 197f, 10f)
        val tabW = (minOf(130f, (game.viewW - 40f) / 3f) * 3) / 9
        val left = game.viewW / 2 - (minOf(130f, (game.viewW - 40f) / 3f) * 3 + 8f) / 2
        tap(left + tabW * 8.5f, 38f); shot("shop-style")
        tap(game.viewW / 2 + 60f, 232f); tap(game.viewW / 2 + 60f, 232f); shot("shop-style-costumes")
        // In a level too, and saved.
        game.buyBed(BedStyle.RED); game.sleepAtHome()
        Style.rocco = RoccoOutfit.CLASSIC
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertEquals(RoccoOutfit.SUPERHERO, Style.rocco)
        assertEquals(MonCostume.CROWN, Style.monCostume)
        game.startLevel(0); run(3.5f)
        shot("style-level")
        // A new game starts in the classic clothes.
        game.newGame(1)
        assertEquals(RoccoOutfit.CLASSIC, Style.rocco)
        assertNull(Style.monCostume)
    }

    @Test fun pokemonMiniGames() {
        startPlaying()
        goHome()
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f)); ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        // MEMORY MATCH: flip every card (a cheat: try every pair).
        game.startMonGame(MonGame.MEMORY)
        assertEquals(TeamGame.State.GAMES, game.state)
        run(0.3f)
        shot("game-memory")
        val coins = game.coins
        // One wrong guess (they flip back), then every pair.
        val cards = game.monGames.cardsForTest()
        val wrong = (1 until 12).first { cards[it] != cards[0] }
        game.monGames.flipCard(0); game.monGames.flipCard(wrong)
        run(1f)
        for (i in 0 until 12) {
            val j = (i + 1 until 12).firstOrNull { cards[it] == cards[i] } ?: continue
            game.monGames.flipCard(i); game.monGames.flipCard(j)
            run(0.1f)
        }
        assertTrue(game.monGames.done, "found every pair")
        assertTrue(game.coins > coins, "won coins")
        run(1.2f); tap(game.viewW / 2, 120f)
        assertEquals(TeamGame.State.HOME, game.state)
        // BALLOON POP: pop balloons with A until time runs out.
        game.startMonGame(MonGame.BALLOON)
        run(5f) { game.monGames.update(0f, game.viewW, false, -1f, -1f, false, false, false, false, true, false) }
        shot("game-balloons")
        run(27f)
        assertTrue(game.monGames.done)
        assertTrue(game.monGames.score > 0, "popped some")
        run(1.2f); tap(game.viewW / 2, 120f)
        // SING-ALONG: tap right on every syllable.
        game.startMonGame(MonGame.SING)
        game.update(dt)
        assertEquals(com.roccoquest.game.Music.THEME, ears.music)
        val unit = 60f / com.roccoquest.game.Songs.THEME_BPM / 4f
        val times = com.roccoquest.game.Songs.themeLyrics.map { it.second * unit + 0.12f }
        var clock = dt
        var shotTaken = false
        while (!game.monGames.done && clock < 40f) {
            val hit = times.any { kotlin.math.abs(it - (clock + dt)) < dt / 2 }
            game.monGames.update(dt, game.viewW, false, -1f, -1f, false, false, false, false, hit, false)
            clock += dt
            if (!shotTaken && clock > 12f) { shot("game-sing"); shotTaken = true }
        }
        assertTrue(game.monGames.done)
        assertEquals(times.size, game.monGames.singHits(), "hit every note")
        run(1.2f); tap(game.viewW / 2, 120f)
        // DANCE PARTY
        game.startMonGame(MonGame.DANCE)
        run(1.5f)
        shot("game-dance")
        assertEquals(TeamGame.State.GAMES, game.state)
    }

    @Test fun constructionRoccoBuildsBlocks() {
        startPlaying()
        goHome()
        game.coins = 500
        game.inventory[Ore.STONE.ordinal] = 40
        game.inventory[Ore.GOLD.ordinal] = 5
        run(0.5f)
        walkHomeTo(380f)
        tap(game.viewW - 235f, 10f)
        assertTrue(game.building)
        // ACTION stacks blocks in front of Rocco.
        repeat(4) { input.action = true; game.update(dt); input.action = false; game.update(dt) }
        assertEquals(4, game.blocks[0].size)
        assertEquals(36, game.inventory[Ore.STONE.ordinal])
        // Tap the wall to build a little gold house; tap a block to take it back.
        game.buildBlock = BuildBlock.GOLD
        assertTrue(game.placeBlock(5 * Build.COLS + 30))
        game.buildBlock = BuildBlock.WOOD
        for (c in 20..28) game.placeBlock(c)
        for (r in 1..4) { game.placeBlock(r * Build.COLS + 20); game.placeBlock(r * Build.COLS + 28) }
        game.buildBlock = BuildBlock.GLASS
        game.placeBlock(2 * Build.COLS + 24)
        val n = game.blocks[0].size
        assertTrue(game.removeBlock(5 * Build.COLS + 30))
        assertEquals(n - 1, game.blocks[0].size)
        assertEquals(5, game.inventory[Ore.GOLD.ordinal], "got the gold back")
        val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img, 3f)); File("build/screens").mkdirs(); ImageIO.write(img, "png", File("build/screens/construction-rocco.png"))
        // Saved with the house.
        game.buyBed(BedStyle.RED)
        game.sleepAtHome()
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertEquals(game.blocks[0], g2.blocks[0])
    }

    @Test fun decorationsBackyardPoolsAndElevator() {
        startPlaying()
        goHome()
        game.coins = 20000
        Ore.entries.forEach { game.inventory[it.ordinal] = 99 }
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f)); ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        run(0.5f)
        // Inside: lots of new decorations, more than one of the same is fine.
        walkHomeTo(500f)
        for (d in listOf(DecorItem.RED_SOFA, DecorItem.RED_SOFA, DecorItem.AQUARIUM, DecorItem.ROCCO_POSTER, DecorItem.RAINBOW_RUG, DecorItem.GIANT_TEDDY, DecorItem.JUKEBOX, DecorItem.FLOOR_LAMP)) game.buyDecor(d)
        assertEquals(2, game.decor.count { it.item == DecorItem.RED_SOFA })
        assertTrue(game.decor.all { it.floor == 0 })
        shot("decor-room")
        // The shop has DECOR and YARD tabs with pages; the SHOP button works upstairs too.
        tap(game.viewW - 197f, 10f)
        assertTrue(game.homeShop)
        tap(10f, 120f)
        // Yard things go to the backyard (pools, trees...)
        for (d in listOf(DecorItem.SLIDE_POOL, DecorItem.PALM_TREE, DecorItem.CHERRY_TREE, DecorItem.SWING_SET, DecorItem.BOUNCY_HOUSE, DecorItem.LAZY_RIVER, DecorItem.HOT_SPRING)) game.buyDecor(d)
        assertTrue(game.decor.filter { it.item.yard }.all { it.floor == Decor.YARD && it.x >= Garden.YARD_X })
        game.enterGarden()
        walkHomeTo(Garden.YARD_X + 200f)
        shot("backyard")
        // Move a tree with DECORATE.
        val tree = game.decor.first { it.item == DecorItem.PALM_TREE }
        walkHomeTo(tree.x + tree.item.w / 2)
        game.toggleDecorate()
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertNotNull(game.carryingDecor)
        walkHomeTo(1900f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertNull(game.carryingDecor)
        assertTrue(tree.x > 1800f, "moved to ${tree.x}")
        game.toggleDecorate()
        // The elevator goes to every floor (and the backyard).
        game.leaveGarden()
        repeat(3) { game.buyFloor() }
        game.buyElevator()
        assertTrue(game.hasElevator)
        walkHomeTo(Homes.STAIRS_X + 10f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        shot("elevator")
        tap(game.viewW / 2 - 5 * 24f + 3 * 48f + 4f + 20f, 108f) // the 4F button (5 stops: 1F..4F, YARD)
        assertEquals(3, game.homeFloor)
        // Saved.
        game.buyBed(BedStyle.BLUE)
        game.sleepAtHome()
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertEquals(game.decor.map { it.item to it.floor }, g2.decor.map { it.item to it.floor })
        assertTrue(g2.hasElevator)
        assertTrue(g2.decor.any { it.item == DecorItem.PALM_TREE && it.x > 1800f })
        // Shop pages for the screenshots.
        game.enterHome(); run(0.4f)
        tap(game.viewW - 197f, 10f)
        val tabW = (minOf(130f, (game.viewW - 40f) / 3f) * 3) / 8
        val left = game.viewW / 2 - (minOf(130f, (game.viewW - 40f) / 3f) * 3 + 8f) / 2
        tap(left + tabW * 6.5f, 38f); shot("shop-decor")
        tap(left + tabW * 7.5f, 38f); shot("shop-yard")
    }

    @Test fun gardenBabyMachineAndTheSkyRoom() {
        startPlaying()
        goHome()
        game.coins = 2000
        Ore.entries.forEach { game.inventory[it.ordinal] = 80 }
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f)); ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        // The garden: plant, water, wait, pick, then a picnic for the Pokemon.
        run(0.5f)
        tap(game.viewW - 150f, 10f)
        assertTrue(game.inGarden)
        walkHomeTo(Garden.plotX(0) + 20f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertEquals(Crop.CARROT.ordinal, game.garden.kind[0], "planted")
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        walkHomeTo(Garden.plotX(1) + 20f)
        game.garden.seed = Crop.SUNFLOWER
        game.garden.plant(1)
        game.garden.seed = Crop.APPLE_TREE
        game.garden.plant(2)
        game.garden.seed = Crop.PUMPKIN
        game.garden.plant(3)
        run(16f)
        assertTrue(game.garden.growth[0] > game.garden.growth[1] * 1.5f, "watered plants grow faster")
        shot("garden-growing")
        run(120f)
        assertEquals(1f, game.garden.growth[0])
        shot("garden")
        game.garden.pick(0)
        assertEquals(-1, game.garden.kind[0])
        assertEquals(2, game.garden.basket[Crop.CARROT.ordinal])
        val xp = game.monXp[game.mon.root.ordinal]
        game.garden.picnic()
        assertTrue(game.monXp[game.mon.root.ordinal] > xp)
        assertEquals(0, game.garden.basket.sum())
        // Back inside, buy and use the Baby Machine.
        walkHomeTo(Garden.DOOR_X + 10f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertFalse(game.inGarden)
        game.buyFurniture(Furniture.BABY_MACHINE)
        assertTrue(game.hasFurniture(Furniture.BABY_MACHINE))
        walkHomeTo(Homes.spot(Furniture.BABY_MACHINE) + 16f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertTrue(game.babyMode)
        run(1.5f)
        shot("baby-home")
        // Up to the fourth floor: the sky room.
        repeat(3) { game.buyFloor() }
        assertEquals(4, game.houseFloors)
        for (f in Furniture.entries.filter { it.floor == 3 }) game.buyFurniture(f)
        assertTrue(game.hasFurniture(Furniture.MOVIE_SCREEN))
        repeat(3) { game.changeFloor(game.homeFloor + 1) }
        assertEquals(3, game.homeFloor)
        walkHomeTo(400f)
        shot("sky-room")
        game.buyHouse(HouseType.LIGHTHOUSE)
        assertEquals(HouseType.LIGHTHOUSE, game.houseType)
        // Saved: the garden, the babies and the floors.
        game.buyBed(BedStyle.RED)
        game.sleepAtHome()
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertTrue(g2.babyMode)
        assertEquals(4, g2.houseFloors)
        assertEquals(Crop.SUNFLOWER.ordinal, g2.garden.kind[1])
        // A baby level, and every new house drawn.
        game.goToMap(-1); run(0.4f)
        game.startLevel(0); run(3.5f)
        shot("baby-level")
        val img = BufferedImage(1500, 300, BufferedImage.TYPE_INT_ARGB)
        HouseType.entries.drop(10).forEachIndexed { i, t -> Homes.drawHouse(ScaledGfx(AwtGfx(img, 1f), 2.5f, 20f + i * 290f, 20f), t, 0f, 100f, 1f, 0, 2) }
        ImageIO.write(img, "png", File(dir, "newhouses.png"))
    }

    @Test fun moreFloorsUnlockFurnitureAndPokemonPark() {
        startPlaying()
        goHome()
        game.coins = 3000
        Ore.entries.forEach { game.inventory[it.ordinal] = 60 }
        // Upstairs furniture needs its floor, and the fancy pieces unlock as you beat levels.
        game.buyFurniture(Furniture.TOY_CHEST)
        assertTrue(!game.hasFurniture(Furniture.TOY_CHEST), "no floor 2 yet")
        game.buyFloor()
        assertEquals(2, game.houseFloors)
        game.buyFurniture(Furniture.TOY_CHEST)
        assertTrue(game.hasFurniture(Furniture.TOY_CHEST))
        game.buyFurniture(Furniture.ARCADE)
        assertTrue(!game.hasFurniture(Furniture.ARCADE), "locked until 10 levels are beaten")
        repeat(10) { game.clearForTest(it) }
        game.buyFurniture(Furniture.ARCADE)
        assertTrue(game.hasFurniture(Furniture.ARCADE))
        game.buyFloor(); game.buyFloor()
        assertEquals(4, game.houseFloors)
        // Walk up the stairs to the playroom and play the arcade: it's the Pokemon Park game.
        walkHomeTo(Homes.STAIRS_X + 8f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertEquals(1, game.homeFloor)
        walkHomeTo(Homes.spot(Furniture.ARCADE) + 10f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertEquals(TeamGame.State.PARK, game.state)
        // Stand still for the whole game: whatever lands on the Pokemon counts, then back home with prizes.
        val coins = game.coins
        run(45f)
        assertTrue(game.parkDone)
        assertTrue(game.coins >= coins)
        run(1.2f)
        tap(game.viewW / 2, 120f)
        assertEquals(TeamGame.State.HOME, game.state)
        // It's all saved.
        game.buyBed(BedStyle.RED)
        walkHomeTo(Homes.DOOR_X + 10f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        assertEquals(0, game.homeFloor, "back downstairs")
        game.sleepAtHome()
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertEquals(4, g2.houseFloors)
        assertTrue(g2.hasFurniture(Furniture.ARCADE))
    }

    @Test fun lotsOfDifferentMusic() {
        // Every new song gets played somewhere: levels, the map, home or the park.
        val used = Levels.all.indices.filter { !Levels.all[it].isBossLevel }.map { TeamGame.Tunes.levelMusic(it) }.toSet() +
            setOf(Music.ADVENTURE, Music.COZY, Music.PARK)
        for (m in listOf(Music.HEROIC, Music.FUNKY, Music.SPACE, Music.CRYSTAL, Music.TROPICAL)) assertTrue(m in used, "$m is used")
        assertTrue(used.size >= 20, "${used.size} different songs")
        startPlaying()
        game.goToMap(0)
        run(0.3f)
        assertEquals(Music.ADVENTURE, ears.music)
    }

    @Test fun aHundredPlushies() {
        assertEquals(350, Plushies.all.size)
        assertEquals(350, Plushies.all.map { it.name }.toSet().size, "all different")
        assertTrue(Plushies.all.any { it.name == "BABY FOX" })
        startPlaying()
        val before = game.plushieCount
        val p = game.givePlushie()
        assertNotNull(p)
        assertTrue(!p.boss, "boss plushies only come from bosses")
        assertEquals(before + 1, game.plushieCount)
        assertEquals(Plushies.KRAG_JR, game.givePlushie(Plushies.KRAG_JR))
        // Coins buy plushies in the shop.
        game.coins = 100
        val cheap = Plushies.all.first { !game.hasPlushie(it) && it.price == 15 }
        game.buyPlushie(cheap)
        assertTrue(game.hasPlushie(cheap))
        assertEquals(85, game.coins)
    }

    @Test fun worldMapPicksLevelsAndGoesHome() {
        startPlaying()
        // Beat level 1-1 (skip to the flag).
        game.foes.clear()
        val goal = game.thingsForTest().filterIsInstance<Goal>().single()
        game.leader.x = goal.x - 10f; game.leader.y = goal.bottom - game.leader.h - 2
        var t = 0
        while (game.state != TeamGame.State.STICKER && t++ < 600) { game.hurtProof(); game.update(dt) }
        assertEquals(TeamGame.State.STICKER, game.state)
        game.pickSticker(0)
        run(3f)
        assertEquals(TeamGame.State.MAP, game.state, "back to the world map")
        assertEquals(2, game.unlocked, "1-2 is open now")
        assertTrue(game.isCleared(0))
        assertEquals(1, game.mapSel, "standing on 1-2")
        assertEquals("1-2", game.levelLabel(1))
        // Tap 1-1 twice to play it again.
        val (x, y) = game.mapSpot(0)
        tap(x, y); tap(x, y)
        assertEquals(TeamGame.State.INTRO, game.state)
        assertEquals(0, game.levelIndex)
        run(3.2f)
        // Pause: SAVE & EXIT TO MAP.
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        tap(game.viewW / 2, 212f)
        assertEquals(TeamGame.State.MAP, game.state)
        // Locked levels can't be picked.
        val (lx, ly) = game.mapSpot(5)
        tap(lx, ly); tap(lx, ly)
        assertEquals(TeamGame.State.MAP, game.state)
    }

    @Test fun ridingDashTheDino() {
        val lv = Levels.all.indexOfFirst { d -> d.rows.any { 'y' in it } }
        assertTrue(lv >= 0, "Dash is in a level")
        startPlaying(lv)
        game.foes.clear()
        val d = game.thingsForTest().filterIsInstance<Dino>().single()
        val r = game.rocco
        r.x = d.cx - 6f; r.y = d.y - 40f; r.vy = 50f
        run(0.6f)
        assertTrue(game.dino === d, "landed on Dash's back")
        // The tongue gulps a bad guy.
        val g = Grumbler(r.x + 30f, r.bottom, false).also { it.active = true }
        game.foes += g
        r.facing = 1
        input.action = true; game.update(dt); input.action = false
        assertTrue(g.dying, "GULP!")
        // Jump, then jump again in the air to leap off.
        input.jump = true; game.update(dt); input.jump = false; run(0.15f)
        input.jump = true; game.update(dt); input.jump = false
        assertNull(game.dino, "jumped off")
        assertEquals(23f, r.h)
        // Getting hit while riding knocks you off but keeps your hearts.
        run(1.5f)
        val d2 = game.thingsForTest().filterIsInstance<Dino>().single()
        r.x = d2.cx - 6f; r.y = d2.y - 40f; r.vy = 50f
        run(0.6f)
        assertTrue(game.dino != null)
        val hearts = game.hearts
        run(2f)
        game.hurt(Grumbler(r.x + 10f, r.bottom, false))
        assertNull(game.dino)
        assertEquals(hearts, game.hearts)
    }

    @Test fun musicChangesFromLevelToLevel() {
        var same = 0
        for (i in 1 until Levels.all.size) {
            val a = if (Levels.all[i - 1].isBossLevel) null else TeamGame.Tunes.levelMusic(i - 1)
            val b = if (Levels.all[i].isBossLevel) null else TeamGame.Tunes.levelMusic(i)
            if (a != null && a == b) same++
        }
        assertEquals(0, same, "no two levels in a row share a tune")
        val bosses = Levels.all.indices.filter { Levels.all[it].isBossLevel }
        val tunes = bosses.map { TeamGame.Tunes.bossMusic(it, true) }
        assertTrue(tunes.zipWithNext().all { (x, y) -> x != y }, "boss fights take turns with scary songs")
    }

    @Test fun homeAndMapScreenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        game.newGame(2, Mon.SPARKY)
        run(3.2f)
        game.coins = 2000
        Ore.entries.forEach { game.inventory[it.ordinal] = 90 }
        // The map, with a few levels beaten.
        game.goToMap(0)
        run(0.5f)
        shot("map-world1")
        goHome()
        run(0.5f)
        shot("home-empty")
        Furniture.entries.forEach { game.buyFurniture(it) }
        game.buyBed(BedStyle.BLUE)
        repeat(40) { game.givePlushie() }
        game.buyPlushie(Plushies.BABY_FOX)
        walkHomeTo(400f)
        run(0.3f)
        shot("home-decorated")
        walkHomeTo(Homes.SHOP_X + 30f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        tap(game.viewW / 2, 38f)
        repeat(2) { tap(game.viewW / 2 + 60f, 38f) }
        shot("home-shop-plushies")
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        walkHomeTo(Homes.SHELF_X + 60f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        shot("plushie-book")
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        game.shopTabForTest(3)
        shot("home-shop-houses")
        game.shopTabForTest(4)
        shot("home-shop-colors")
        game.paintHouse(6)
        game.buyHouse(HouseType.CRYSTAL)
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        walkHomeTo(Homes.MON_X + 20f)
        input.action = true; game.update(dt); input.action = false; game.update(dt)
        game.monButtonForTest(1)
        run(0.6f)
        shot("home-pokemon")
        run(2f)
        game.clearForTest(2)
        game.monButtonForTest(4)
        shot("home-pokemon-switch")
        game.switchMon(Mon.LUGIA)
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        run(0.5f)
        shot("home-lugia")
        // Build two more floors and fill them with furniture.
        repeat(60) { game.clearForTest(it) }
        game.coins = 5000
        Ore.entries.forEach { game.inventory[it.ordinal] = 90 }
        game.buyFloor(); game.buyFloor()
        Furniture.entries.forEach { game.buyFurniture(it) }
        repeat(150) { game.givePlushie() }
        game.changeFloor(1)
        walkHomeTo(330f)
        run(0.3f)
        shot("home-floor2")
        game.changeFloor(2)
        walkHomeTo(400f)
        run(0.3f)
        shot("home-floor3")
        walkHomeTo(800f)
        run(0.3f)
        shot("home-floor3-more")
        game.changeFloor(1)
        walkHomeTo(800f)
        run(0.3f)
        shot("home-floor2-more")
        game.changeFloor(0)
        walkHomeTo(780f)
        run(0.3f)
        shot("home-kitchen")
        game.goToMap(-1)
        run(0.5f)
        shot("map-tall-house")
        goHome()
        game.startPark()
        run(6f)
        shot("pokemon-park")
        // Dash the dino.
        val lv = Levels.all.indexOfFirst { d -> d.rows.any { 'y' in it } }
        startPlaying(lv)
        game.foes.clear()
        val d = game.thingsForTest().filterIsInstance<Dino>().single()
        val r = game.rocco
        r.x = d.cx - 6f; r.y = d.y - 40f; r.vy = 50f
        run(0.6f)
        run(0.5f) { input.right = true }
        input.right = false
        run(2.5f)
        shot("dash-the-dino")
    }

    /** Taps the screen like a real finger: the touch also lands on the game's button zones. */
    private fun fingerTap(x: Float, y: Float) {
        val ids = intArrayOf(0); val xs = floatArrayOf(x); val ys = floatArrayOf(y)
        TeamControls.apply(input, ids, xs, ys, 1, game.viewW)
        input.tapX = x; input.tapY = y; input.tap = true
        repeat(3) { game.update(dt) }
        TeamControls.apply(input, ids, xs, ys, 0, game.viewW)
        repeat(3) { game.update(dt) }
    }

    @Test fun tappingTheMapNeverGetsStuckInTheHouse() {
        startPlaying()
        game.unlockForTest(Levels.all.size)
        // Standing on the house, tap levels all over the map (World 1, then World 2).
        for (world in 0..1) {
            game.goToMap(-1)
            run(0.5f)
            if (world == 1) { val b = game.viewW - 120f + 20f; fingerTap(b, 12f); assertEquals(1, game.mapWorld, "switched to World 2") }
            // (Switching to World 2 already picks 2-1, so a tap there would play it.)
            for (k in (if (world == 1) 1 else 0) until 21) {
                val i = world * WORLD2_START + k
                val (x, y) = game.mapSpot(i)
                fingerTap(x, y)
                assertEquals(TeamGame.State.MAP, game.state, "one tap on ${game.levelLabel(i)} only picks it")
                assertEquals(i, game.mapSel)
            }
            val last = world * WORLD2_START + 20
            val (x, y) = game.mapSpot(last)
            fingerTap(x, y)
            assertEquals(TeamGame.State.INTRO, game.state, "a second tap plays it")
            assertEquals(last, game.levelIndex)
            run(3.2f)
        }
        // Leaving the house puts you on a level, not back on the house.
        game.goToMap(-1)
        run(0.5f)
        val (hx, hy) = game.mapSpot(-1)
        fingerTap(hx, hy); fingerTap(hx, hy)
        assertEquals(TeamGame.State.HOME, game.state)
        input.pause = true; game.update(dt); input.pause = false; game.update(dt)
        assertEquals(TeamGame.State.MAP, game.state)
        assertEquals(Levels.all.size - 1, game.mapSel, "standing on the next level after leaving home")
        // The PLAY! button plays it.
        run(0.5f)
        fingerTap(game.viewW - 46f, 230f)
        assertEquals(TeamGame.State.INTRO, game.state)
    }


    /** Presses a controller button for a few frames (no finger on the screen). */
    private fun pad(set: (Boolean) -> Unit) {
        input.touching = false
        set(true); input.tapX = -1f; input.tapY = -1f; input.tap = true
        repeat(2) { game.update(dt) }
        set(false)
        repeat(2) { game.update(dt) }
    }

    @Test fun playTheWholeGameWithAController() {
        run(0.6f)
        pad { input.jump = it }                       // A on the title
        assertEquals(TeamGame.State.SLOTS, game.state)
        run(0.4f)
        pad { input.right = it }; pad { input.right = it } // over to FILE 3
        pad { input.jump = it }
        assertEquals(TeamGame.State.PICK, game.state)
        run(0.5f)
        pad { input.right = it }                      // Blaze
        pad { input.jump = it }
        assertEquals(Mon.BLAZE, game.mon)
        assertEquals(2, game.slot)
        run(3.2f)
        assertEquals(TeamGame.State.PLAY, game.state)
        // Y is SUPER, X switches, B throws, A jumps: the phone maps the buttons, the game just sees actions.
        val who = game.leaderWho
        pad { input.swap = it }
        assertTrue(game.leaderWho != who, "X switched character")
        // Down ducks (Rocco squats).
        pad { input.swap = it }; pad { input.swap = it }
        input.down = true; run(0.3f)
        assertTrue(game.rocco.crouching, "duck")
        input.down = false; run(0.2f)
        // Pause menu: down twice to SAVE & EXIT TO MAP, then A.
        pad { input.pause = it }
        assertTrue(game.paused)
        pad { input.down = it }; pad { input.down = it }
        pad { input.jump = it }
        assertEquals(TeamGame.State.MAP, game.state)
        // On the map, A plays the picked level.
        run(0.5f)
        pad { input.jump = it }
        assertEquals(TeamGame.State.INTRO, game.state)
    }

    @Test fun controllerShopsInTheHouseAndCrafts() {
        startPlaying()
        game.coins = 500
        Ore.entries.forEach { game.inventory[it.ordinal] = 30 }
        input.touching = false
        // Crafting: open with -, move down, A crafts.
        pad { input.craft = it }
        assertEquals(TeamGame.State.CRAFT, game.state)
        run(0.3f)
        pad { input.jump = it }
        assertEquals(Power.BOOM, game.power, "A crafted the first row")
        pad { input.action = it }                     // B closes
        assertEquals(TeamGame.State.PLAY, game.state)
        // House shop: L/R change tab, A buys.
        game.goToMap(-1); run(0.5f)
        pad { input.jump = it }
        assertEquals(TeamGame.State.HOME, game.state)
        var t = 0
        while (game.homeX > Homes.SHOP_X + 30f && t++ < 300) { input.left = true; game.update(dt) }
        input.left = false
        pad { input.jump = it }
        assertTrue(game.homeShop)
        pad { input.longJump = it }                   // R: next tab (BEDS)
        assertEquals(1, game.shopTab)
        pad { input.right = it }                      // the blue bed
        pad { input.jump = it }
        assertEquals(BedStyle.BLUE, game.bedStyle)
        pad { input.action = it }
        assertTrue(!game.homeShop)
    }

    @Test fun controllerSetupScreen() {
        run(0.6f)
        pad { input.craft = it }                      // - on the title opens it
        assertEquals(TeamGame.State.CONTROLS, game.state)
        run(0.4f)
        tap(game.viewW / 2 - 80f, 180f)               // SET MY BUTTONS
        assertEquals(0, game.padStep)
        game.padPressed("B"); game.padPressed("A"); game.padPressed("Y"); game.padPressed("X")
        assertEquals(-1, game.padStep)
        assertEquals(listOf("B", "A", "Y", "X"), game.padNames.toList())
    }

    @Test fun fourWorldsAndNewEnemies() {
        assertEquals(210, Levels.all.size)
        assertEquals(10, worldCount())
        val ultimate = Levels.all.first { it.name == "THE ULTIMATE BATTLE" }
        assertTrue(ultimate.rows.any { 'Z' in it } && ultimate.rows.any { 'Q' in it })
        for (c in "zqlj") assertTrue(Levels.all.count { d -> d.rows.any { c in it } } >= 3, "new monster $c is in levels")
        // Beating a World 3 level is saved.
        startPlaying()
        game.unlockForTest(50)
        game.startLevel(45)
        run(3.2f)
        game.foes.clear()
        val goal = game.thingsForTest().filterIsInstance<Goal>().single()
        game.leader.x = goal.x - 10f; game.leader.y = goal.bottom - game.leader.h - 2
        var t = 0
        while (game.state != TeamGame.State.STICKER && t++ < 600) { game.hurtProof(); game.update(dt) }
        game.pickSticker(0); run(3f)
        assertTrue(game.isCleared(45))
        assertEquals("3-4", game.levelLabel(45))
        val g2 = TeamGame(TeamInput(), ears, storage)
        g2.continueGame(0)
        assertTrue(g2.isCleared(45), "World 3 progress is saved")
    }

    @Test fun newEnemiesBehave() {
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        r.x = 122 * TILE.toFloat(); r.y = 100f
        run(1f)
        // The crusher slams down when you walk under it.
        val c = Crusher(r.cx - 8f, r.y - 30f).also { it.active = true }
        game.foes += c
        val y0 = c.y
        run(1.2f) { game.hurtProof() }
        assertTrue(c.y > y0 + 10f, "the crusher came down")
        game.foes.clear()
        // The ghost stops when you look at it.
        val gh = Peekaboo(r.x + 80f, r.bottom - 10f).also { it.active = true }
        game.foes += gh
        r.facing = 1
        val gx = gh.x
        run(0.8f) { game.hurtProof(); r.facing = 1 }
        assertEquals(gx, gh.x, 0.5f, "it hides while you look at it")
        r.facing = -1
        run(0.8f) { game.hurtProof(); r.facing = -1 }
        assertTrue(gh.x < gx - 5f, "it sneaks up when you look away")
        game.foes.clear()
        // The spikestack loses a ball each hit.
        val st = Spikestack(r.x + 100f, r.bottom).also { it.active = true }
        game.foes += st
        st.hit(game, 50)
        assertEquals(2, st.hp, "one ball per hit, however strong")
        game.foes.clear()
        // The puffcloud drops spiky balls.
        val pc = Puffcloud(r.x + 20f, 60f).also { it.active = true }
        game.foes += pc
        var balls = 0
        run(5f) { game.hurtProof(); balls = maxOf(balls, game.foes.count { it is SpikeBall }) }
        assertTrue(balls > 0, "spiky balls from the sky")
    }

    @Test fun moreWorldsScreenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        startPlaying()
        game.unlockForTest(Levels.all.size)
        game.padConnected = true
        game.goToMap(50); run(0.5f)
        shot("map-world3")
        game.goToMap(70); run(0.5f)
        shot("map-world4")
        for ((name, at) in listOf("CRUSHER CANYON" to 0.25f, "GHOST GARDEN" to 0.3f, "BONE DESERT" to 0.35f)) {
            startPlaying(Levels.all.indexOfFirst { it.name == name })
            game.leader.x = Levels.all[game.levelIndex].w * TILE * at; game.leader.y = 60f
            run(1.5f) { game.hurtProof() }
            shot("w34-" + name.lowercase().replace(" ", "-"))
        }
        game.openControllerSetup(); run(0.3f)
        shot("controller-setup")
    }

    @Test fun world5HasWaterShipsAndRainbowRoad() {
        val w5 = Levels.all.drop(4 * WORLD_SIZE).take(WORLD_SIZE)
        assertEquals(21, w5.size)
        assertTrue(w5.count { it.place == Place.WATER && it.shoreX > 0 } >= 9, "lots of water levels")
        assertTrue(w5.count { it.place == Place.SHIP } >= 8, "pirate ship levels")
        assertTrue(w5.any { it.name == "RAINBOW ROAD" && it.place == Place.RAINBOW })
        assertTrue(w5.any { d -> d.rows.any { 'N' in it } }, "cannons on the ships")
        assertTrue(w5.any { d -> d.rows.any { '$' in it } }, "metal caps")
    }

    @Test fun metalRoccoWalksUnderwaterAndCantBeHurt() {
        startPlaying(Levels.all.indexOfFirst { it.place == Place.WATER })
        game.foes.clear()
        game.startMetal()
        assertTrue(game.metal)
        val r = game.rocco
        run(1.5f) { input.right = true }
        input.right = false
        assertTrue(r.onGround, "Metal Rocco walks on the sea floor instead of swimming")
        run(5f)
        assertEquals(TeamGame.AIR_MAX, game.air, 0.01f, "no air needed")
        val hearts = game.hearts
        game.hurt(Grumbler(r.x + 4f, r.bottom, false))
        assertEquals(hearts, game.hearts, "nothing hurts Metal Rocco")
        run(20f)
        assertTrue(!game.metal, "it wears off")
    }

    @Test fun cannonsFireCannonballs() {
        startPlaying()
        game.foes.clear()
        val r = game.rocco
        r.x = 122 * TILE.toFloat(); r.y = 100f
        run(1f)
        game.foes += Cannon(r.x + 120f, r.bottom).also { it.active = true }
        var balls = 0
        run(4.5f) { game.hurtProof(); balls = maxOf(balls, game.foes.count { it is CannonBall }) }
        assertTrue(balls > 0, "BOOM")
    }

    @Test fun world5Screenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String) {
            val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            game.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        for ((name, at) in listOf("PIRATE SHIP" to 0.18f, "RAINBOW ROAD" to 0.3f, "SHIPWRECK BAY" to 0.05f)) {
            startPlaying(Levels.all.indexOfFirst { it.name == name })
            game.leader.x = Levels.all[game.levelIndex].w * TILE * at; game.leader.y = 60f
            if (name == "SHIPWRECK BAY") game.startMetal()
            run(1.5f)
            shot("w5-" + name.lowercase().replace(" ", "-"))
        }
    }
}
