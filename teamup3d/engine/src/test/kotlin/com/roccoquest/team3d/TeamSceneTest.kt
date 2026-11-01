package com.roccoquest.team3d

import com.roccoquest.game.Gfx
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Sprite
import com.roccoquest.game.Storage
import com.roccoquest.game.VIEW_H
import com.roccoquest.team.Dennis
import com.roccoquest.team.HerculesArt
import com.roccoquest.team.Levels
import com.roccoquest.team.ChefKrag
import com.roccoquest.team.Kingdoms
import com.roccoquest.team.Tile
import com.roccoquest.team.Armor
import com.roccoquest.team.Shield
import com.roccoquest.team.Sword
import com.roccoquest.team.WORLD_SIZE
import com.roccoquest.team.Mon
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

    /** Draws a frame the way the phone does: painted sky, 3D level, then the HUD and buttons. */
    private fun shot(scene: TeamScene, name: String): BufferedImage {
        val scale = 2f
        val w = (game.viewW * scale).toInt()
        val h = (VIEW_H * scale).toInt()
        val sky = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        game.renderLevelBackdrop(AwtGfx(sky, scale))
        val list = scene.build(game)
        val img = SoftRaster(w, h).render(list, sky)
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
        assertEquals(TeamGame.State.SLOTS, game.state, "tapping anywhere else still starts")
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
}
