package com.roccoquest.team3d

import com.roccoquest.game.SoundSink
import com.roccoquest.game.Storage
import com.roccoquest.game.VIEW_H
import com.roccoquest.team.Levels
import com.roccoquest.team.Mon
import com.roccoquest.team.TeamGame
import com.roccoquest.team.TeamInput
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamSceneTest {
    private val dt = 1f / 60f
    private val input = TeamInput()
    private val storage = Storage.Memory().apply { save("gift1", 1); save("gift2", 1) }
    private val game = TeamGame(input, SoundSink {}, storage)
    private val dir = File("build/screens3d").apply { mkdirs() }

    init { Glyphs.rasterizer = AwtGlyphs }

    /** Draws a frame exactly like the phone: sky, 3D level (if any), then the screen in 3D on top. */
    private fun shot3d(scene: TeamScene, flat: FlatScreen, name: String) {
        val scale = 2f
        val w = (game.viewW * scale).toInt()
        val h = (VIEW_H * scale).toInt()
        val raster = SoftRaster(w, h)
        val world = scene.build(game)
        if (world.showWorld) {
            val sky = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            game.renderLevelBackdrop(AwtGfx(sky, scale))
            raster.render(world, sky)
        } else {
            raster.render(DrawList().apply { showWorld = false; finish() })
        }
        val img = raster.overlay(flat.build(game, world))
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    @Test fun everyScreenIn3d() {
        val scene = TeamScene()
        val flat = FlatScreen()
        repeat(90) { game.update(dt) }
        shot3d(scene, flat, "screen-title")
        game.newGame(0, Mon.SPARKY)
        game.update(dt)
        shot3d(scene, flat, "screen-intro")
        repeat((3.2f / dt).toInt()) { game.update(dt) }
        shot3d(scene, flat, "screen-level-hud")
        game.goToMap()
        repeat(30) { game.update(dt) }
        shot3d(scene, flat, "screen-map")
        game.enterHome()
        repeat(30) { game.update(dt) }
        shot3d(scene, flat, "screen-home")
        game.enterGarden()
        repeat(30) { game.update(dt) }
        shot3d(scene, flat, "screen-garden")
        game.openControllerSetup()
        repeat(10) { game.update(dt) }
        shot3d(scene, flat, "screen-controls")
        game.startPark()
        repeat(60) { game.update(dt) }
        shot3d(scene, flat, "screen-park")
        game.playScene(0, 0)
        repeat(60) { game.update(dt) }
        shot3d(scene, flat, "screen-scene")
        // Busy screens must still be quick to build.
        game.enterHome()
        repeat(30) { game.update(dt) }
        repeat(20) { flat.build(game, null) }
        val t0 = System.nanoTime()
        repeat(100) { flat.build(game, null) }
        val ms = (System.nanoTime() - t0) / 1e6 / 100
        File(dir, "flat-performance.txt").writeText("home: %.2f ms, %d draws\n".format(ms, flat.list.count))
    }

    private fun run(seconds: Float) = repeat((seconds / dt).toInt()) { game.update(dt) }

    private fun startPlaying(level: Int) {
        game.newGame(0, Mon.SPARKY)
        if (level > 0) game.startLevel(level)
        repeat((3.2f / dt).toInt()) { game.update(dt) }
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    /** Draws a frame the way the phone does: painted sky, 3D level, then the HUD and buttons. */
    private fun shot(scene: TeamScene, name: String) {
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
    }

    @Test fun titleScreen() {
        val scene = TeamScene()
        repeat(90) { game.update(dt) }
        assertEquals(TeamGame.State.TITLE, game.state)
        shot(scene, "title")
        repeat(70) { game.update(dt) }
        shot(scene, "title-later")
    }

    /**
     * Plays a few seconds of every level (walking, jumping, attacking) and checks that building
     * the 3D frame stays fast, doesn't use too many draws and doesn't keep making new models.
     */
    @Test fun noLagInAnyLevel() {
        val scene = TeamScene()
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
            for (f in 0 until 240) {
                input.jump = f % 50 < 12
                input.action = f % 30 < 3
                game.update(dt)
                val t0 = System.nanoTime()
                val list = scene.build(game)
                maxMs = maxOf(maxMs, (System.nanoTime() - t0) / 1e6)
                maxDraws = maxOf(maxDraws, list.count)
            }
            input.right = false; input.jump = false; input.action = false
            val made = Voxels.built - models
            report.append("%3d %-28s %6.2f ms %5d draws %4d new models\n".format(i, Levels.all[i].name, maxMs, maxDraws, made))
            if (i > 0) { worstMs = maxOf(worstMs, maxMs); worstDraws = maxOf(worstDraws, maxDraws); worstModels = maxOf(worstModels, made) }
        }
        File(dir, "performance.txt").writeText(report.toString())
        assertTrue(worstDraws < 1500, "too many draws: $worstDraws")
        assertTrue(worstModels < 400, "too many new models in one level: $worstModels")
    }

    @Test fun screenshots() {
        val scene = TeamScene()
        val picks = listOf(0, 3, 21, 43, 70, 85, 106, 127, 150, 170, 190, 211, 232)
        for (i in picks) {
            if (i >= Levels.all.size) continue
            startPlaying(i)
            game.leader.x += 60f
            run(1.2f)
            val name = Levels.all[i].name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
            shot(scene, "level-%03d-%s".format(i, name))
        }
    }

    @Test fun everyLevelBuilds() {
        val scene = TeamScene()
        for (i in Levels.all.indices) {
            startPlaying(i)
            val list = scene.build(game)
            assertTrue(list.showWorld && list.count > 10, "level $i draws ${list.count}")
        }
    }

    @Test fun framesAreQuickAndDontKeepMakingModels() {
        val scene = TeamScene()
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
