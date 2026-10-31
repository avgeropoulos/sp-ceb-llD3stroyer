package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Music
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Sprite
import com.roccoquest.game.Storage
import com.roccoquest.game.VIEW_H
import com.roccoquest.game.Voice
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The SillyScenes: the newest funny scenes (Wonder Oops!, Spooky S'mores, Bath Time Dennis...). */
class SillyScenesTest {
    private class Ears : SoundSink {
        val played = ArrayList<Sound>()
        val said = ArrayList<String>()
        val voices = ArrayList<Voice>()
        var music = Music.NONE
        override fun play(s: Sound) { played += s }
        override fun music(m: Music) { music = m }
        override fun say(text: String) { said += text }
        override fun sayAs(text: String, voice: Voice) { voices += voice; say(text) }
    }

    /** Draws nothing, but counts the shapes, keeps the words, and fails on broken numbers. */
    private class CheckGfx : Gfx {
        var shapes = 0
        val words = ArrayList<String>()
        private fun ok(vararg v: Float) = v.forEach { assertTrue(it.isFinite(), "a drawing number went bad") }
        override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) { ok(x, y, w, h); shapes++ }
        override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) { ok(x, y, w, h); shapes++ }
        override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {
            assertEquals(xs.size, ys.size)
            assertTrue(xs.size >= 3)
            ok(*xs); ok(*ys); shapes++
        }
        override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) { ok(x, y, w, h); shapes++ }
        override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) { ok(x, y, size); words += s }
    }

    private val dt = 1f / 60f
    private val input = TeamInput()
    private val ears = Ears()
    // (The one-time File 1 welcome-back gift is already given, so it doesn't change these tests.)
    private val storage = Storage.Memory().apply { save("gift1", 1); save("gift2", 1) }
    private val game = TeamGame(input, ears, storage)
    private val newKinds = SillyScenes.FIRST until Scenes.titles.size

    private fun run(seconds: Float) = repeat((seconds / dt).toInt()) { game.update(dt) }

    private fun tap(x: Float, y: Float) {
        input.tapX = x; input.tapY = y; input.tap = true
        game.update(dt)
    }

    private fun startPlaying() {
        game.newGame(0, Mon.SPARKY)
        run(3.2f)
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    @Test fun theNewScenesComeAfterTheOldOnes() {
        assertTrue(SillyScenes.titles.size >= 8, "at least 8 new scenes")
        // Old scenes keep their numbers (scenes you've seen are saved by number).
        assertEquals(LongScenes.FIRST + LongScenes.titles.size, SillyScenes.FIRST)
        assertEquals("DONUT TIME!", Scenes.titles[0])
        assertEquals("THE WRONG PIPE", Scenes.titles[SillyScenes.FIRST - 1])
        assertEquals(SillyScenes.FIRST + SillyScenes.titles.size, Scenes.titles.size)
        assertEquals(Scenes.titles.size, Scenes.titles.toSet().size, "every title is different")
        // The seen-scenes bits are saved in two 32-bit keys.
        assertTrue(Scenes.titles.size <= 64)
        assertTrue(Scenes.titles.size > 32, "some scenes need the second save key")
    }

    @Test fun everyNewSceneDrawsAtEveryMoment() {
        // Lots of moments, a few screen widths and all sorts of Pokemon (big, small, evolved, legendary).
        val mons = Mon.entries.filterIndexed { i, _ -> i % 8 == 0 } + Mon.LUGIA + Mon.entries.last()
        for (kind in newKinds) for (w in floatArrayOf(320f, 427f, 540f)) for (m in mons) {
            var t = 0f
            while (t < Scenes.length(kind) + 0.6f) {
                val gfx = CheckGfx()
                Scenes.draw(game, gfx, kind, t, w, VIEW_H, m)
                assertTrue(gfx.shapes > 40, "${Scenes.titles[kind]} at $t draws a real picture")
                assertTrue(gfx.words.any { it.endsWith(Scenes.titles[kind]) }, "the title is shown")
                t += 0.07f
            }
        }
    }

    @Test fun everyNewSceneHasTimedLinesVoicesAndSounds() {
        for (kind in newKinds) {
            val name = Scenes.titles[kind]
            val cues = Scenes.cues(kind, "SPARKY")
            val spoken = cues.filter { it.text.isNotEmpty() }
            assertTrue(Scenes.length(kind) in 10f..16f, "$name is a long-ish scene")
            assertTrue(spoken.size >= 6, "$name has plenty to say")
            assertTrue(cues.zipWithNext().all { (a, b) -> a.at <= b.at }, "$name: cues in order")
            assertTrue(cues.all { it.at >= 0f && it.at < Scenes.length(kind) - 0.5f }, "$name: cues inside the scene")
            assertTrue(cues.all { it.text.isNotEmpty() || it.sound != null }, "$name: no empty cues")
            assertTrue(cues.count { it.sound != null } >= 4, "$name: lots of sounds")
            assertTrue(spoken.map { it.voice }.toSet().size >= 2, "$name: more than one voice")
            // A new line cuts off the one before it, so they're spaced out.
            assertTrue(spoken.zipWithNext().all { (a, b) -> b.at - a.at >= 0.75f }, "$name: lines don't step on each other")
        }
        fun cuesOf(n: Int) = Scenes.cues(SillyScenes.FIRST + n, "SPARKY")
        // Each star talks in their own voice.
        assertTrue(cuesOf(0).any { it.voice == Voice.GOD }, "Wonder King Krag")
        assertTrue(cuesOf(0).any { it.voice == Voice.TOAD }, "Krag Jr.")
        assertTrue(cuesOf(1).any { it.voice == Voice.GOD } && cuesOf(1).any { it.sound == Sound.BARK }, "Dry Bones King Krag and Dennis")
        assertTrue(cuesOf(2).any { it.voice == Voice.TOAD }, "Spotty")
        assertTrue(cuesOf(3).count { it.voice == Voice.FROG } >= 3, "Ring-Ding Frog sings")
        assertTrue(cuesOf(5).count { it.sound == Sound.BARK } >= 2, "Dennis barks")
        assertTrue(cuesOf(6).any { it.text == "SPARKY!" }, "the Pokemon says its own name")
        // Three of them have their own song.
        assertEquals(Music.WONDER, Scenes.music(SillyScenes.FIRST))
        assertEquals(Music.FROG, Scenes.music(SillyScenes.FIRST + 3))
        assertEquals(Music.SILLY, Scenes.music(0))
    }

    @Test fun newScenesPlayAllTheWayThrough() {
        startPlaying()
        for (kind in newKinds) {
            ears.said.clear(); ears.played.clear()
            game.playScene(kind, 1)
            assertEquals(TeamGame.State.SCENE, game.state)
            run(1f)
            assertEquals(Scenes.music(kind), ears.music, "${Scenes.titles[kind]} plays its song")
            run(Scenes.length(kind))
            assertEquals(TeamGame.State.MAP, game.state, "${Scenes.titles[kind]} ends on the map")
            val cues = Scenes.cues(kind, game.mon.label)
            assertTrue(ears.said.containsAll(cues.filter { it.text.isNotEmpty() }.map { it.text }), "every line is said")
            assertFalse("" in ears.said, "sound-only cues don't talk")
            assertTrue(ears.played.containsAll(cues.mapNotNull { it.sound }), "every sound plays")
            assertTrue(game.sceneSeen(kind))
        }
    }

    @Test fun newScenesTakeTheirTurnAndOlderSavesGetThemFirst() {
        // A brand-new game: every scene takes its turn, in order, the new ones too.
        startPlaying()
        for (k in Scenes.titles.indices) {
            game.startScene(1)
            assertEquals(k, game.sceneKind)
            run(Scenes.length(k) + 0.2f)
        }
        game.startScene(1)
        assertEquals(0, game.sceneKind, "then round again")

        // A save from before this update: it saw all 26 old scenes and is half way round again.
        game.newGame(0, Mon.SPARKY)
        game.slotSave("scenesSeen", (1 shl SillyScenes.FIRST) - 1)
        game.slotSave("scenes", 57)
        val old = TeamGame(TeamInput(), ears, storage)
        old.continueGame(0)
        assertEquals((1L shl SillyScenes.FIRST) - 1, old.scenesSeen)
        // The new scenes come first (they're new to this player)...
        for (k in newKinds) {
            old.startScene(1)
            assertEquals(k, old.sceneKind)
            repeat(((Scenes.length(k) + 0.2f) / dt).toInt()) { old.update(dt) }
        }
        // ...then it's back to taking turns.
        old.startScene(1)
        assertEquals((57 + SillyScenes.titles.size) % Scenes.titles.size, old.sceneKind)
    }

    @Test fun theMoviesHaveTheNewScenesOnceSeen() {
        startPlaying()
        game.goToMap(-1)
        run(0.4f)
        input.jump = true; game.update(dt); input.jump = false; game.update(dt)
        assertEquals(TeamGame.State.HOME, game.state)
        // THE BLOCK TOWER is number 33: its bit is in the second save key.
        val kind = SillyScenes.FIRST + 7
        assertTrue(kind >= 32)
        game.playScene(0, -2); run(Scenes.length(0) + 0.3f)
        assertTrue(game.theater)
        // Not seen yet: it won't play.
        game.watch(kind)
        assertEquals(TeamGame.State.HOME, game.state)
        // Seen once...
        game.playScene(kind, -2); run(Scenes.length(kind) + 0.3f)
        assertEquals(TeamGame.State.HOME, game.state)
        assertTrue(game.theater)
        // ...it's on the 4th page of the MOVIES (10 to a page): NEXT, NEXT, NEXT.
        repeat(3) { tap(game.viewW * 0.75f, VIEW_H - 8f) }
        val gfx = CheckGfx()
        game.render(gfx)
        assertTrue("THE BLOCK TOWER" in gfx.words, "it's listed")
        assertTrue("???" in gfx.words, "the ones not seen yet are still a mystery")
        assertTrue("super silly!" in gfx.words)
        val dir = File("build/screens").apply { mkdirs() }
        val img = BufferedImage((game.viewW * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img, 3f)); ImageIO.write(img, "png", File(dir, "silly-movies.png"))
        // Tap it to watch it again.
        val i = kind % 10
        tap(game.viewW / 2 - 156f + (i % 2) * 160f + 40f, 46f + (i / 2) * 32f + 12f)
        assertEquals(TeamGame.State.SCENE, game.state)
        assertEquals(kind, game.sceneKind)
        // And a reload remembers it (in the new second key).
        val restored = TeamGame(TeamInput(), ears, storage)
        restored.continueGame(0)
        assertTrue(restored.sceneSeen(kind))
        assertTrue(restored.sceneSeen(0))
        assertFalse(restored.sceneSeen(kind + 1))
        // Erasing the file forgets it.
        game.eraseWithBackup(0)
        val erased = TeamGame(TeamInput(), ears, storage)
        erased.newGame(0)
        assertFalse(erased.sceneSeen(kind))
    }

    @Test fun screenshots() {
        // Four moments of each new scene on one sheet (2 x 2): build/screens/silly-<n>.png
        val moments = listOf(
            floatArrayOf(3.9f, 6.4f, 9.6f, 11.6f), floatArrayOf(4.8f, 8.4f, 10.6f, 13.0f), floatArrayOf(1.8f, 5.0f, 9.2f, 10.8f),
            floatArrayOf(2.8f, 5.5f, 8.0f, 10.5f), floatArrayOf(5.9f, 7.2f, 8.7f, 11.0f), floatArrayOf(3.5f, 6.5f, 9.6f, 11.5f),
            floatArrayOf(2.5f, 6.9f, 10.0f, 12.2f), floatArrayOf(4.0f, 5.8f, 8.7f, 10.6f), floatArrayOf(1.5f, 5.2f, 6.9f, 9.0f),
        )
        val dir = File("build/screens").apply { mkdirs() }
        val w = 427f
        for (kind in newKinds) {
            val sheet = BufferedImage((w * 4).toInt(), (VIEW_H * 4).toInt(), BufferedImage.TYPE_INT_ARGB)
            for ((i, t) in moments[kind - SillyScenes.FIRST].withIndex()) {
                val img = BufferedImage((w * 2).toInt(), (VIEW_H * 2).toInt(), BufferedImage.TYPE_INT_ARGB)
                Scenes.draw(game, AwtGfx(img, 2f), kind, t, w, VIEW_H, Mon.SPARKY)
                sheet.createGraphics().drawImage(img, (i % 2) * img.width, (i / 2) * img.height, null)
            }
            ImageIO.write(sheet, "png", File(dir, "silly-${kind - SillyScenes.FIRST}.png"))
        }
    }
}
