package com.roccoquest.team3d

import com.roccoquest.game.Gfx
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Sprite
import com.roccoquest.game.Storage
import com.roccoquest.game.TILE
import com.roccoquest.game.VIEW_H
import com.roccoquest.team.Dennis
import com.roccoquest.team.HerculesArt
import com.roccoquest.team.Levels
import com.roccoquest.team.LugoArt
import com.roccoquest.team.ChefKrag
import com.roccoquest.team.Kingdoms
import com.roccoquest.team.Tile
import com.roccoquest.team.Armor
import com.roccoquest.team.Shield
import com.roccoquest.team.Sword
import com.roccoquest.team.WORLD_SIZE
import com.roccoquest.team.Mon
import com.roccoquest.team.Place
import com.roccoquest.team.Portal
import com.roccoquest.team.Power
import com.roccoquest.team.PowerUps
import com.roccoquest.team.TeamArt
import com.roccoquest.team.TeamGame
import com.roccoquest.team.TeamInput
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TeamSceneTest {
    private val dt = 1f / 60f
    private val input = TeamInput()
    private val storage = Storage.Memory().apply { save("gift1", 1); save("gift2", 1) }
    private val game = TeamGame(input, SoundSink {}, storage)
    private val dir = File("build/screens3d").apply { mkdirs() }

    private fun run(seconds: Float) = repeat((seconds / dt).toInt()) { game.update(dt) }

    private fun startPlaying(level: Int) {
        game.newGame(0, Mon.SPARKY)
        if (level > 0) game.startLevel(level)
        repeat((3.2f / dt).toInt()) { game.update(dt) }
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    /** The painted sky and the 3D level, as the phone draws them under the HUD. */
    private fun frame(scene: TeamScene, scale: Float): Pair<DrawList, BufferedImage> {
        val w = (game.viewW * scale).toInt()
        val h = (VIEW_H * scale).toInt()
        val list = scene.build(game)
        val sky = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        // Like the phone: with 3D scenery the painted backdrop is only the sky.
        game.renderLevelBackdrop(AwtGfx(sky, scale), scenery = !scene.drawsScenery)
        return list to SoftRaster(w, h).render(list, sky)
    }

    /** Draws a frame the way the phone does: painted sky, 3D level, then the HUD and buttons. */
    private fun shot(scene: TeamScene, name: String): BufferedImage {
        val scale = 2f
        val (list, img) = frame(scene, scale)
        val overlay = AwtGfx(img, scale)
        for (i in 0 until list.labelCount) list.label(i).let { overlay.text(it.text, it.x, it.y, it.size, it.color, it.align) }
        game.render(overlay, drawWorld = false)
        ImageIO.write(img, "png", File(dir, "$name.png"))
        return img
    }

    @Test fun file1GetsTheHeadStartOnce() {
        val fresh = TeamGame(TeamInput(), SoundSink {}, Storage.Memory())
        fresh.headStart = TeamGame.HeadStart(
            WORLD_SIZE * 8, 5000, 99, 4, 30, "HEAD START",
            Sword.DIAMOND, Armor.DIAMOND, Shield.SAPPHIRE, key = "start3d_world9",
        )
        fresh.newGame(0, Mon.SPARKY)
        assertEquals(WORLD_SIZE * 8, fresh.levelIndex, "starts at World 9-1")
        assertEquals("9-1", Levels.all[fresh.levelIndex].let { "${fresh.levelIndex / WORLD_SIZE + 1}-${fresh.levelIndex % WORLD_SIZE + 1}" })
        assertEquals(5000, fresh.coins)
        assertEquals(4, fresh.pickTier)
        assertEquals(30, fresh.tnt)
        assertEquals(Sword.DIAMOND, fresh.sword)
        assertEquals(Armor.DIAMOND, fresh.armorType)
        assertEquals(Shield.SAPPHIRE, fresh.shieldType)
        assertTrue(fresh.unlocked >= WORLD_SIZE * 8 + 1)
        // Not given again, and the 2D game's World 7 present never comes on top.
        fresh.continueGame(0)
        assertEquals(5000, fresh.coins)
        assertEquals(WORLD_SIZE * 8, fresh.levelIndex)
    }

    @Test fun updateButtonOnTheTitle() {
        var asked = 0
        game.onUpdate = { asked++; game.updateStatus = "CHECKING FOR AN UPDATE..." }
        repeat(60) { game.update(dt) }
        input.tapX = 30f; input.tapY = 14f; input.tap = true
        game.update(dt)
        assertEquals(1, asked, "UPDATE was pressed")
        assertEquals(TeamGame.State.TITLE, game.state, "pressing UPDATE doesn't start the game")
        input.tapX = 200f; input.tapY = 150f; input.tap = true
        game.update(dt)
        assertEquals(1, asked)
        assertEquals(TeamGame.State.PLAYERS, game.state, "tapping anywhere else still starts (1 PLAYER or 2 PLAYERS first)")
        game.onUpdate = null
    }

    @Test fun titleScreen() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        repeat(90) { game.update(dt) }
        assertEquals(TeamGame.State.TITLE, game.state)
        shot(scene, "title")
        val list = scene.build(game)
        var points = 0
        for (k in 0 until list.count) points += list.cmd(k).mesh.vertexCount
        assertTrue(points < 150_000, "the title screen draws $points points")
        repeat(70) { game.update(dt) }
        shot(scene, "title-later")
    }

    /**
     * Plays a few seconds of every level (walking, jumping, attacking) and checks that building
     * the 3D frame stays fast, doesn't use too many draws and doesn't keep making new models.
     */
    @Test fun noLagInAnyLevel() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        val report = StringBuilder()
        var worstMs = 0.0
        var worstDraws = 0
        var worstModels = 0
        for (i in Levels.all.indices) {
            startPlaying(i)
            input.right = true
            repeat(60) { game.update(dt); scene.build(game) }
            val models = Voxels.built
            var maxMs = 0.0
            var maxDraws = 0
            var maxVerts = 0
            for (f in 0 until 240) {
                input.jump = f % 50 < 12
                input.action = f % 30 < 3
                game.update(dt)
                val t0 = System.nanoTime()
                val list = scene.build(game)
                maxMs = maxOf(maxMs, (System.nanoTime() - t0) / 1e6)
                maxDraws = maxOf(maxDraws, list.count)
                var v = 0
                for (k in 0 until list.count) v += list.cmd(k).mesh.vertexCount
                maxVerts = maxOf(maxVerts, v)
            }
            input.right = false; input.jump = false; input.action = false
            val made = Voxels.built - models
            report.append("%3d %-28s %6.2f ms %5d draws %7d points %4d new models\n".format(i, Levels.all[i].name, maxMs, maxDraws, maxVerts, made))
            assertTrue(maxVerts < 200_000, "level $i draws $maxVerts points: that would lag on phones")
            if (i > 0) { worstMs = maxOf(worstMs, maxMs); worstDraws = maxOf(worstDraws, maxDraws); worstModels = maxOf(worstModels, made) }
        }
        File(dir, "performance.txt").writeText(report.toString())
        assertTrue(worstDraws < 1500, "too many draws: $worstDraws")
        assertTrue(worstModels < 400, "too many new models in one level: $worstModels")
    }

    @Test fun screenshots() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        val picks = listOf(0, 3, 21, 43, 70, 85, 106, 127, 150, 170, 190, 211, 232, 252, 263, 274, 285)
        for (i in picks) {
            if (i >= Levels.all.size) continue
            startPlaying(i)
            game.leader.x += 60f
            run(1.2f)
            val name = Levels.all[i].name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
            shot(scene, "level-%03d-%s".format(i, name))
        }
    }

    /** World 14 in 3D: the Big City's rooftops, the Food Kingdom's hot soup, and Chef Krag in his kitchen. */
    @Test fun kingdomTourIn3D() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        val soupy = Levels.all.indexOfFirst { it.name == "SOUP SPRINGS" }
        startPlaying(soupy)
        val pool = (0 until game.level.w).first { game.level[it, 13] == Tile.LAVA }
        game.leader.x = (pool - 8) * 16f
        run(1.5f)
        shot(scene, "kingdom-food-soup")
        startPlaying(Kingdoms.WORLD14 + 1)
        game.leader.x += 40 * 16f; game.leader.y = 40f
        run(1.5f)
        shot(scene, "kingdom-big-city")
        startPlaying(Levels.all.lastIndex)
        val chef = game.foes.filterIsInstance<ChefKrag>().single()
        game.leader.x = chef.homeX - 110f
        run(3.2f)
        assertTrue(game.boss === chef, "Chef Krag woke up")
        val list = scene.build(game)
        assertTrue(list.showWorld && list.count > 10)
        shot(scene, "kingdom-chef-krag")
    }

    /** With 3D scenery the painted backdrop is only the sky (no flat hills or trees), while the 2D game still paints them. */
    @Test fun backdropIsJustTheSkyBehind3DScenery() {
        startPlaying(0)
        assertEquals(0, rowsWithShapes(scenery = false), "only the sky")
        assertTrue(rowsWithShapes(scenery = true) > 100, "the 2D game still paints its hills, trees and clouds")
        // World 14 too: below the stars, moon and sun, the Big City and the Food Kingdom paint only their sky.
        for (p in listOf(Place.CITY, Place.FOOD)) {
            startPlaying(Levels.all.indexOfFirst { it.place == p })
            assertEquals(0, rowsWithShapes(scenery = false, from = 112), "$p: only the sky")
            assertTrue(rowsWithShapes(scenery = true, from = 112) > 80, "$p: the 2D game still paints its skyline or food")
        }
    }

    /** How many rows of the painted backdrop (from row [from] down) have something on the sky (a plain sky gradient has one colour across each row). */
    private fun rowsWithShapes(scenery: Boolean, from: Int = 0): Int {
        val img = BufferedImage(game.viewW.toInt(), VIEW_H.toInt(), BufferedImage.TYPE_INT_ARGB)
        game.renderLevelBackdrop(AwtGfx(img, 1f), scenery)
        return (from until img.height).count { y -> (0 until img.width).any { x -> img.getRGB(x, y) != img.getRGB(0, y) } }
    }

    /** The 3D scenery of every place (hills and trees, snowy pines, dunes, lava spires, space rocks...): screens3d/scenery-*.png. */
    @Test fun sceneryScreenshots() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        for (p in Place.entries) {
            val i = Levels.all.indexOfFirst { it.place == p }
            if (i < 0) continue
            startPlaying(i)
            game.leader.x += 60f
            run(1.2f)
            shot(scene, "scenery-%02d-%s".format(p.ordinal, p.name.lowercase()))
            assertEquals(p != Place.STATION, scene.drawsScenery, "$p")
        }
        // The bonus room behind a portal (its sunset place isn't used by any level).
        startPlaying(0)
        val l = game.leader
        game.spawn(Portal(l.x + 2f, l.bottom))
        run(1.5f)
        assertEquals(Levels.bonusRoom, game.level.def)
        shot(scene, "scenery-%02d-bonus-room".format(Place.SUNSET.ordinal))
        assertTrue(scene.drawsScenery)
    }

    /**
     * A level's scenery is built while its INTRO card shows (the app draws the card in 2D and calls [TeamScene.prepare]
     * meanwhile), so the level's first 3D frame has nothing to build. It used to build it right then, and send all
     * of it to the GPU at once: a stutter at the start of every level.
     */
    @Test fun sceneryIsReadyBeforeTheLevelShows() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        game.newGame(0, Mon.SPARKY)
        for (i in listOf(5, Kingdoms.WORLD14 + 1)) {
            game.startLevel(i)
            assertEquals(TeamGame.State.INTRO, game.state)
            assertTrue(game.showsIntroCard && !game.showsLevel, "the card isn't a 3D frame")
            scene.prepare(game)
            val ready = assertNotNull(scene.scenery, "level $i: built during its INTRO card")
            assertEquals(Levels.all[i].place, ready.place)
            val builds = scene.sceneryBuilt
            var n = 0
            while (game.state == TeamGame.State.INTRO && n++ < 400) { game.update(dt); scene.prepare(game) }
            assertEquals(TeamGame.State.PLAY, game.state)
            // The first 3D frame of the level.
            scene.build(game)
            assertTrue(scene.drawsScenery)
            assertSame(ready, scene.scenery, "level $i: the first frame draws the scenery made during the card")
            assertEquals(builds, scene.sceneryBuilt, "level $i: and builds nothing new")
        }
    }

    /** Into a bonus room and back keeps the level's scenery (nothing built or sent to the GPU again); only what falls out is freed. */
    @Test fun aBonusRoomRoundTripKeepsTheLevelsScenery() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        startPlaying(0)
        scene.build(game)
        val level = assertNotNull(scene.scenery)
        val builds = scene.sceneryBuilt
        fun portal(exit: Boolean) {
            val l = game.leader
            game.spawn(Portal(l.x + 2f, l.bottom, exit))
            repeat(90) { game.update(dt); scene.build(game) }
        }
        portal(exit = false)
        assertEquals(Levels.bonusRoom, game.level.def)
        val bonus = assertNotNull(scene.scenery)
        assertEquals(builds + 1, scene.sceneryBuilt, "the bonus room's scenery is built once")
        portal(exit = true)
        assertSame(Levels.all[0], game.level.def, "back in the level")
        assertSame(level, scene.scenery, "the level's scenery was kept")
        assertTrue(level.meshes.none { it.released })
        // Round and round: nothing new.
        portal(exit = false)
        assertSame(bonus, scene.scenery)
        portal(exit = true)
        assertSame(level, scene.scenery)
        assertEquals(builds + 1, scene.sceneryBuilt)
        // The next levels push the oldest scenery out (and free it), one at a time.
        startPlaying(1)
        scene.build(game)
        assertTrue(bonus.meshes.all { it.released }, "the bonus room's scenery is freed")
        assertTrue(level.meshes.none { it.released }, "level 1-1's is still kept")
        startPlaying(2)
        scene.build(game)
        assertTrue(level.meshes.all { it.released }, "then level 1-1's too")
        assertTrue(scene.scenery!!.meshes.none { it.released })
    }

    /**
     * Cloud Skies: the level's white cloud platforms must stand out from the 3D cloud sea behind them (white
     * on white hid where the platforms were, and made the pits look like more cloud to stand on). Each puff
     * on screen is compared with the backdrop two tiles above and below it, in every Cloud Skies level.
     */
    @Test fun cloudPlatformsStandOutFromTheCloudSea() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        val scale = 2f
        val at = FloatArray(2)
        fun diff(a: Int, b: Int) = (0..16 step 8).sumOf { abs((a shr it and 255) - (b shr it and 255)) }
        val report = StringBuilder()
        var samples = 0
        var worst = Int.MAX_VALUE
        for ((i, def) in Levels.all.withIndex()) {
            if (def.place != Place.SKY) continue
            startPlaying(i)
            game.leader.x += 60f
            run(1.2f)
            val (list, img) = frame(scene, scale)
            val lv = game.level
            val cam = game.worldCam
            // The colour on screen at a point of the 3D world (the camera looks down a little, so it isn't the 2D spot).
            fun rgb(x: Float, y: Float, z: Float): Int {
                list.project(x, y, z, at)
                return img.getRGB((at[0] * scale).toInt(), (at[1] * scale).toInt()) and 0xFFFFFF
            }
            // Nobody (hero or bad guy) in the empty tile the backdrop is seen through.
            fun clear(tx: Int, ty: Int) = (game.foes + game.team).none {
                it.x + it.w > tx * TILE - 4f && it.x < (tx + 1) * TILE + 4f && it.y + it.h > ty * TILE - 4f && it.y < (ty + 1) * TILE + 4f
            }
            for (tx in (cam / TILE).toInt() + 1 until ((cam + game.viewW) / TILE).toInt()) for (ty in 2 until lv.h - 2) {
                if (lv[tx, ty] != Tile.CLOUD) continue
                // The middle of the tile's puffs, at their front (TileGfx.cloud).
                val x = tx * TILE + TILE / 2f - cam
                val puff = rgb(x, -(ty * TILE + TILE / 2f) + 1f, 8f)
                for (dir in intArrayOf(-1, 1)) {
                    // Two empty tiles, so only the sky and the scenery show there.
                    val by = ty + 2 * dir
                    if (by < 3 || lv[tx, ty + dir] != Tile.EMPTY || lv[tx, by] != Tile.EMPTY || !clear(tx, by)) continue
                    val back = rgb(x, -(by * TILE + TILE / 2f), 0f)
                    val d = diff(puff, back)
                    report.append("level $i: puff at ($tx, $ty) %06X vs %06X %s: $d\n".format(puff, back, if (dir < 0) "above" else "below"))
                    samples++
                    worst = minOf(worst, d)
                }
            }
        }
        File(dir, "sky-platforms.txt").writeText(report.toString())
        assertTrue(samples >= 10, "only $samples cloud puffs were on screen\n$report")
        assertTrue(worst >= MIN_CLOUD_CONTRAST, "a cloud platform blends into the sky behind it\n$report")
    }

    /** Dennis (Hercules from the Hercules and Apollo game) turns into a puffy 3D dog like everyone else. */
    @Test fun dennisIn3D() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        val frames = listOf(HerculesArt.stand, HerculesArt.walk, HerculesArt.leap, HerculesArt.wag)
        for (s in frames) assertTrue(Voxels.mesh(s).vertexCount > 0)
        startPlaying(0)
        game.foes.clear()
        // He runs up about 10 seconds into a level: watch what the level draws until he's there.
        val noGfx = object : Gfx {
            override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {}
            override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {}
            override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {}
            override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) {}
            override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {}
        }
        var dennis: Dennis? = null
        var n = 0
        while (dennis == null && n++ < 1200) {
            game.update(dt)
            game.renderLevelActors(noGfx) { if (it is Dennis) dennis = it }
        }
        assertTrue(dennis != null, "Dennis came running")
        // Then he stops to bark and point the way.
        run(1.6f)
        val list = scene.build(game)
        val models = frames.map { Voxels.mesh(it) }
        val dog = (0 until list.count).count { k -> models.any { it === list.cmd(k).mesh } }
        assertEquals(1, dog, "drawn as one puffy model")
        shot(scene, "dennis-3d")
    }

    /**
     * The Wonder power-ups in 3D: Elephant, Drill and Bubble Rocco are puffy models like everyone else, the
     * spinning drill flips through a few fixed pictures (each modelled once), and bubbles are shiny see-through balls.
     */
    @Test fun powerUpsIn3D() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        for (p in PowerUps.ALL) {
            startPlaying(0)
            game.foes.clear()
            game.givePowerUp(p)
            val r = game.rocco
            r.x = 16 * 16f
            r.y = 13 * 16f - r.h
            run(1.2f)
            r.facing = 1
            if (p == Power.ELEPHANT) {
                // He's one puffy model (his picture is the body; the trunk swing is drawn on top of it).
                val frames = (0 until 3).map { Voxels.mesh(TeamArt.rocco(p, it)) }
                val list = scene.build(game)
                assertEquals(1, (0 until list.count).count { k -> frames.any { it === list.cmd(k).mesh } }, "Elephant Rocco is one model")
            }
            input.action = true; game.update(dt); input.action = false
            run(if (p == Power.BUBBLE) 0.45f else 0.1f)
            if (p == Power.BUBBLE) { input.action = true; game.update(dt); input.action = false; run(0.1f) }
            shot(scene, "power-${p.name.lowercase()}-3d")
        }
        // Walking and spinning the drill for a while doesn't keep making new models.
        input.right = true
        repeat(120) { f -> input.action = f % 30 < 2; game.update(dt); scene.build(game) }
        game.givePowerUp(Power.DRILL)
        repeat(120) { f -> input.action = f % 30 < 2; game.update(dt); scene.build(game) }
        val before = Voxels.built
        repeat(240) { f -> input.action = f % 30 < 2; game.update(dt); scene.build(game) }
        input.right = false; input.action = false
        assertTrue(Voxels.built - before < 30, "made ${Voxels.built - before} new models while drilling along")

        // MEGA Rocco's trunk, drill whirl and bubbles are giant too (and swinging away makes no new shapes).
        for (p in PowerUps.ALL) {
            startPlaying(0)
            game.foes.clear()
            game.givePowerUp(p)
            game.startMega()
            val r = game.rocco
            r.x = 16 * 16f
            r.y = 13 * 16f - r.h
            run(1.2f)
            r.facing = 1
            repeat(80) { f -> input.action = f % 40 < 2; game.update(dt); scene.build(game) }
            val shapes = scene.paper.polysBuilt
            repeat(160) { f -> input.action = f % 40 < 2; game.update(dt); scene.build(game) }
            assertTrue(scene.paper.polysBuilt - shapes < 10, "made ${scene.paper.polysBuilt - shapes} new shapes as MEGA ${p.label}")
            input.action = true; game.update(dt); input.action = false
            run(0.1f)
            shot(scene, "power-mega-${p.name.lowercase()}-3d")
        }
    }

    /**
     * Two players in 3D: LUGO (player 2) is a puffy model like his brother, made once per picture (running about
     * doesn't keep making new ones).
     */
    @Test fun twoPlayersIn3D() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        startPlaying(0)
        game.foes.clear()
        game.setPlayers(true, byPad = true)
        game.joinPlayer2()
        run(1.2f)
        val lugo = game.lugo
        lugo.x = game.leader.x + 26f
        lugo.facing = -1
        run(0.3f)
        val list = scene.build(game)
        val frames = (0 until 3).map { Voxels.mesh(LugoArt.lugo(Power.NONE, it)) }
        assertEquals(1, (0 until list.count).count { k -> frames.any { it === list.cmd(k).mesh } }, "LUGO is one model")
        shot(scene, "two-player-3d")
        closeUp(scene, "two-player-3d-closeup", game.leader.cx + 13f - game.worldCam, game.leader.cy, 90f, 60f)
        // Running and jumping about for a while makes no new models.
        val p2 = game.input2
        repeat(120) { f -> p2.right = f % 60 < 40; p2.jump = f % 40 < 8; game.update(dt); scene.build(game) }
        val before = Voxels.built
        repeat(240) { f -> p2.right = f % 60 < 40; p2.left = f % 60 >= 40; p2.jump = f % 40 < 8; game.update(dt); scene.build(game) }
        p2.right = false; p2.left = false; p2.jump = false
        assertTrue(Voxels.built - before < 10, "made ${Voxels.built - before} new models while LUGO ran about")
    }

    /** A big close-up around ([cx], [cy]) (game pixels) of the 3D picture, to see small things. */
    private fun closeUp(scene: TeamScene, name: String, cx: Float, cy: Float, w: Float = 110f, h: Float = 70f) {
        val scale = 6f
        val iw = (game.viewW * scale).toInt()
        val ih = (VIEW_H * scale).toInt()
        val sky = BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB)
        game.renderLevelBackdrop(AwtGfx(sky, scale))
        val img = SoftRaster(iw, ih).render(scene.build(game), sky)
        val x0 = ((cx - w / 2) * scale).toInt().coerceIn(0, iw - (w * scale).toInt())
        val y0 = ((cy - h / 2) * scale).toInt().coerceIn(0, ih - (h * scale).toInt())
        ImageIO.write(img.getSubimage(x0, y0, (w * scale).toInt(), (h * scale).toInt()), "png", File(dir, "$name.png"))
    }

    /** The circle wipe is drawn with the HUD over the 3D level, with its middle where the hero shows in 3D. */
    @Test fun circleWipeOpensOnTheHeroIn3D() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        game.newGame(0, Mon.SPARKY)
        var n = 0
        while (game.state != TeamGame.State.PLAY && n++ < 400) game.update(dt)
        run(0.2f)
        scene.build(game)
        val l = game.leader
        val p = floatArrayOf(l.cx - game.worldCam, l.cy)
        game.levelToScreen!!(p)
        assertTrue(abs(p[0] - (l.cx - game.worldCam)) < 30f && abs(p[1] - l.cy) < 30f, "the 3D hero is near his 2D spot: ${p.toList()}")
        val img = shot(scene, "wipe-3d")
        val black = 0xFF000000.toInt()
        assertEquals(black, img.getRGB(img.width - 3, 3), "black far from the hero")
        val seen = (-4..4).any { dy -> (-4..4).any { dx -> img.getRGB((p[0] * 2).toInt() + dx, (p[1] * 2).toInt() + dy) != black } }
        assertTrue(seen, "the hero shows through the hole")
        run(0.5f)
        val open = shot(scene, "wipe-3d-open")
        assertTrue(open.getRGB(open.width - 3, 3) != black, "then the whole level shows")
    }

    /** Landing squashes the heroes in 3D too, without making new 3D models for every size. */
    @Test fun squashAndDustIn3D() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        startPlaying(0)
        game.foes.clear()
        run(1f)
        val r = game.rocco
        r.y -= 100f; r.vy = 0f; r.onGround = false
        game.partner.y -= 70f; game.partner.vy = 0f; game.partner.onGround = false
        var n = 0
        while (!r.onGround && n++ < 120) { game.update(dt); scene.build(game) }
        game.update(dt); game.update(dt)
        assertTrue(r.stretch < -0.05f, "squashed: ${r.stretch}")
        shot(scene, "squash-3d")
        closeUp(scene, "squash-3d-zoom", r.cx - game.worldCam, r.bottom - 16f)
        // Lots of jumping and landing: squashing scales the 3D shapes, it doesn't make new ones.
        fun jumpAround() {
            for (f in 0 until 360) {
                input.jump = f % 40 < 10
                input.right = f % 120 < 60
                input.left = !input.right
                game.foes.clear()
                game.update(dt)
                scene.build(game)
            }
            input.jump = false; input.right = false; input.left = false
        }
        jumpAround()
        val models = Voxels.built
        val shapes = scene.paper.polysBuilt
        jumpAround()
        assertTrue(Voxels.built - models < 6, "made ${Voxels.built - models} new models while jumping")
        assertTrue(scene.paper.polysBuilt - shapes < 10, "made ${scene.paper.polysBuilt - shapes} new shapes while jumping")
    }

    @Test fun everyLevelBuilds() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        for (i in Levels.all.indices) {
            startPlaying(i)
            val list = scene.build(game)
            assertTrue(list.showWorld && list.count > 10, "level $i draws ${list.count}")
        }
    }

    @Test fun framesAreQuickAndDontKeepMakingModels() {
        val scene = TeamScene().apply { voxelsPerFrame = Int.MAX_VALUE }
        startPlaying(0)
        game.leader.x += 40f
        input.right = true
        repeat(120) { game.update(dt); scene.build(game) }
        val builtBefore = Voxels.built
        val t0 = System.nanoTime()
        repeat(300) { game.update(dt); scene.build(game) }
        val ms = (System.nanoTime() - t0) / 1e6 / 300
        input.right = false
        assertTrue(ms < 6.0, "a frame took $ms ms")
        assertTrue(Voxels.built - builtBefore < 60, "made ${Voxels.built - builtBefore} new models while just walking")
    }

    companion object {
        /** How different (red + green + blue steps) a cloud platform must look from the sky right behind it. */
        const val MIN_CLOUD_CONTRAST = 60
    }
}
