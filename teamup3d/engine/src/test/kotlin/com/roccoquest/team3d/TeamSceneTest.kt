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
