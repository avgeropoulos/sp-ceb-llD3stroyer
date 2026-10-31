package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Sprite
import com.roccoquest.game.Storage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Dennis looks like Hercules from the Hercules and Apollo game, in levels, scenes and on his sticker. */
class DennisLookTest {
    private val dt = 1f / 60f
    private val barks = ArrayList<Sound>()
    private val storage = Storage.Memory().apply { save("gift1", 1); save("gift2", 1) }
    private val game = TeamGame(TeamInput(), SoundSink { barks += it }, storage)
    private val dir = File("build/screens").apply { mkdirs() }

    private fun run(seconds: Float, each: () -> Unit = {}) = repeat((seconds / dt).toInt()) { each(); game.update(dt) }

    private fun shot(name: String) {
        val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img, 3f))
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    /** Remembers where the pictures go, to check his paws are on the ground. */
    private class Spy : Gfx {
        val sprites = ArrayList<FloatArray>()
        val pictures = ArrayList<Sprite>()
        override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {}
        override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {}
        override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {}
        override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) {
            pictures += s; sprites += floatArrayOf(x, y, w, h)
        }
        override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {}
    }

    @Test fun herculesArt() {
        // The Hercules and Apollo art is 25 x 18, plus the outline pixels on the top and sides.
        for (s in listOf(HerculesArt.stand, HerculesArt.walk, HerculesArt.leap, HerculesArt.wag)) {
            assertEquals(HerculesArt.W + 2, s.w)
            assertEquals(HerculesArt.H + 1, s.h)
            // Paws on the bottom row, so he stands right on the ground.
            assertTrue((0 until s.w).any { (s.pixels[(s.h - 1) * s.w + it] ushr 24) > 0 })
        }
        // Black curls, a white beard, a red collar and a gold tag.
        val colors = HerculesArt.stand.pixels.toSet()
        for (c in listOf(HerculesArt.BODY, HerculesArt.COLLAR, 0xFFE8E4DC.toInt(), 0xFFFFD21F.toInt())) assertTrue(c in colors)

        // A close-up of every frame, both ways round, on a light and a dark background.
        val img = BufferedImage(8 * 130, 8 * 60, BufferedImage.TYPE_INT_ARGB)
        val gfx = AwtGfx(img, 8f)
        gfx.rect(0f, 0f, 130f, 30f, 0xFF9AD4F4.toInt())
        gfx.rect(0f, 30f, 130f, 30f, 0xFF1A1A3A.toInt())
        for ((i, s) in listOf(HerculesArt.stand, HerculesArt.walk, HerculesArt.leap, HerculesArt.wag).withIndex()) {
            HerculesArt.draw(gfx, s, 2f + i * 32f, 6f, 1)
            HerculesArt.draw(gfx, s, 2f + i * 32f, 36f, -1)
        }
        ImageIO.write(img, "png", File(dir, "dennis-frames.png"))
    }

    @Test fun dennisInALevel() {
        game.newGame(0, Mon.SPARKY)
        run(3.2f)
        assertEquals(TeamGame.State.PLAY, game.state)
        game.foes.clear()
        game.dogForTest()
        run(0.1f)
        val d = game.thingsForTest().filterIsInstance<Dennis>().single()
        val ground = game.leader.bottom
        assertEquals(ground, d.bottom, "Dennis runs along the ground")
        // He runs in on alternating legs...
        val seen = HashSet<Sprite>()
        var frames = 0
        while (Sound.BARK !in barks && frames++ < 600) {
            val spy = Spy()
            d.draw(game, spy, 0f)
            seen += spy.pictures
            val (_, y, _, h) = spy.sprites.single().toList()
            assertEquals(ground, y + h, "paws on the ground")
            game.update(dt)
        }
        assertTrue(HerculesArt.walk in seen && HerculesArt.stand in seen, "running swaps his legs")
        assertTrue(barks.contains(Sound.BARK), "woof!")
        // He stops out in the open (level 1-1 has a pile of stone blocks just there), not inside a wall.
        assertTrue(d.x >= game.leader.cx + 100f)
        for (dx in listOf(2f, d.w / 2, d.w - 2)) assertTrue(!game.level.solidAt(d.x + dx, d.bottom - 2), "not in a wall")
        // ...barks with a happy leap...
        run(0.15f)
        val leap = Spy().also { d.draw(game, it, 0f) }
        assertEquals(HerculesArt.leap, leap.pictures.single())
        shot("dennis-leap")
        // ...and points the way with his big arrow.
        run(0.6f)
        shot("dennis-level")
        assertEquals(ground, d.bottom)
    }

    @Test fun dennisInTheScenes() {
        game.newGame(0, Mon.SPARKY)
        run(3.2f)
        // Dennis steals Rocco's mustache, then runs off with it (both ways).
        game.playScene(7, -2)
        var now = 0f
        for ((i, at) in listOf(1.0f, 2.6f, 3.4f).withIndex()) {
            run(at - now); now = at
            assertEquals(TeamGame.State.SCENE, game.state)
            shot("dennis-scene-mustache-$i")
        }
        // He howls along with Rocco's singing.
        game.playScene(15, -2)
        run(3.0f)
        shot("dennis-scene-singing")
        // The stinky cheese: he watches, then runs away.
        game.playScene(LongScenes.FIRST + 3, -2)
        run(2.5f)
        shot("dennis-scene-cheese-0")
        run(2.0f)
        shot("dennis-scene-cheese-1")
    }

    @Test fun dennisSticker() {
        val i = Stickers.all.indexOfFirst { it.name == "DENNIS" }
        val st = Stickers.all[i]
        assertEquals(26f, st.w); assertEquals(30f, st.h)
        // He fits in his sticker's box.
        val spy = Spy()
        st.draw(game, ScaledGfx(spy, 1f, 0f, 0f))
        val (x, y, w, h) = spy.sprites.single().toList()
        assertTrue(x >= 0f && y >= 0f && x + w <= st.w + 0.01f && y + h <= st.h, "inside the box: $x $y $w $h")

        // Big cards in every style, and the sticker book page he's on.
        val img = BufferedImage(5 * 3 * 90, 3 * 110, BufferedImage.TYPE_INT_ARGB)
        val gfx = AwtGfx(img, 3f)
        gfx.rect(0f, 0f, 450f, 110f, 0xFF2A1A10.toInt())
        for (k in 0 until 5) Stickers.drawCard(game, gfx, k * Stickers.BASE + i, 5f + k * 90f, 5f, 80f, 100f)
        ImageIO.write(img, "png", File(dir, "dennis-sticker.png"))
        game.newGame(0, Mon.SPARKY)
        run(3.2f)
        for (k in 0 until 24) game.giveStickerForTest(k)
        game.openBookForTest()
        run(0.2f)
        shot("dennis-sticker-book")
    }
}
