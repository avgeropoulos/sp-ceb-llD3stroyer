package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.SoundSink
import com.roccoquest.game.SpeechText
import com.roccoquest.game.Sprite
import com.roccoquest.game.Storage
import com.roccoquest.game.VIEW_H
import com.roccoquest.game.VoiceCast
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every funny scene's lines fit natural-speed voices. A scene line is said right when its cue comes
 * ([SoundSink.sayNow]) and cuts off whatever is still being said, so each line has to be finished (by the
 * game's own guess, [SpeechText.seconds] at the voice's [VoiceCast] speed) before the next spoken cue, and the
 * last one before the scene ends, with a little gap so the map doesn't come back mid-word. The lines are
 * checked with the longest Pokemon name, so they fit whoever your partner is. (These are the checks from
 * [SillyScenesTest], for every scene: the 20 short ones in Fun.kt, [LongScenes] and [SillyScenes].)
 */
class SceneTimingTest {
    private val longName = Mon.entries.maxBy { it.label.length }.label
    private val shortKinds = 0 until LongScenes.FIRST
    private val longKinds = LongScenes.FIRST until SillyScenes.FIRST
    private val sillyKinds = SillyScenes.FIRST until Scenes.titles.size

    /** About how long [c]'s line takes to say: the game's own guess, at that voice's natural speed. */
    private fun saying(c: Scenes.Cue) = SpeechText.seconds(SpeechText.tidy(c.text), VoiceCast.parts.getValue(c.voice).rate)

    private fun secs(v: Number) = String.format(Locale.ROOT, "%.2f s", v.toDouble())

    /** Everything wrong with scene [kind]'s timing, one line each, naming the scene and the line (empty if it's fine). */
    private fun problems(kind: Int): List<String> {
        val name = "Scene $kind ${Scenes.titles[kind]}"
        val length = Scenes.length(kind)
        val cues = Scenes.cues(kind, longName)
        val out = ArrayList<String>()
        for ((a, b) in cues.zipWithNext()) if (b.at < a.at) out += "$name: the cue at ${a.at} s comes before the one at ${b.at} s (out of order)"
        for (c in cues) {
            if (c.at < 0f || c.at >= length) out += "$name: the cue at ${c.at} s is outside the scene (it lasts ${secs(length)})"
            if (c.text.isEmpty() && c.sound == null) out += "$name: the cue at ${c.at} s says nothing and plays nothing"
        }
        val spoken = cues.filter { it.text.isNotEmpty() }
        if (spoken.isEmpty()) out += "$name: nobody says anything"
        for ((a, b) in spoken.zipWithNext()) {
            val s = saying(a)
            if (b.at - a.at < s) out += "$name: \"${a.text}\" (${a.voice}, said at ${a.at} s, takes ${secs(s)}) is cut off by " +
                "\"${b.text}\" at ${b.at} s: it needs until ${secs(a.at + s)}"
        }
        spoken.lastOrNull()?.let { last ->
            val ends = last.at + saying(last)
            if (ends > length - 0.3f) out += "$name: the last line \"${last.text}\" (${last.voice}, said at ${last.at} s) ends at " +
                "${secs(ends)}, but the scene ends at ${secs(length)} (it must be done by ${secs(length - 0.3f)})"
        }
        return out
    }

    private fun assertTimed(kinds: IntRange) {
        val all = kinds.flatMap { problems(it) }
        assertTrue(all.isEmpty(), "${all.size} timing problem(s):\n" + all.joinToString("\n"))
    }

    @Test fun theseTestsCoverEveryScene() {
        assertEquals(20, shortKinds.count(), "the 20 short scenes in Fun.kt")
        assertEquals(Scenes.titles.indices.toList(), (shortKinds + longKinds + sillyKinds).toList())
    }

    @Test fun everyShortSceneLineIsFinishedBeforeTheNextOne() = assertTimed(shortKinds)

    /** The longer story scenes (LongScenes.kt), re-timed for the natural voices like the short ones. */
    @Test fun everyLongSceneLineIsFinishedBeforeTheNextOne() = assertTimed(longKinds)

    @Test fun everySillySceneLineIsFinishedBeforeTheNextOne() = assertTimed(sillyKinds)

    @Test fun theShortScenesLastLongEnoughToEnjoy() {
        for (kind in shortKinds) {
            val length = Scenes.length(kind)
            assertTrue(length in Scenes.LENGTH..8f, "${Scenes.titles[kind]} is still a short scene (${secs(length)})")
            assertTrue(Scenes.cues(kind, "SPARKY").count { it.text.isNotEmpty() } >= 2, "${Scenes.titles[kind]} still has its lines")
        }
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

    // (The one-time File 1 welcome-back gift is already given, so it doesn't get in the way.)
    private val game = TeamGame(TeamInput(), SoundSink { }, Storage.Memory().apply { save("gift1", 1); save("gift2", 1) })

    @Test fun theShortScenesDrawAllTheWayToTheEnd() {
        // A scene that got longer still has a picture in its last moments (and a little after, on a slow phone).
        val mons = listOf(Mon.SPARKY, Mon.LUGIA, Mon.entries.last())
        for (kind in shortKinds) for (w in floatArrayOf(320f, 427f, 540f)) for (m in mons) {
            var t = 0f
            while (t < Scenes.length(kind) + 0.4f) {
                val gfx = CheckGfx()
                Scenes.draw(game, gfx, kind, t, w, VIEW_H, m)
                assertTrue(gfx.shapes > 10, "${Scenes.titles[kind]} at $t s draws a picture")
                assertTrue(gfx.words.any { it.endsWith(Scenes.titles[kind]) }, "the title is shown")
                t += 0.05f
            }
        }
    }

    @Test fun screenshots() {
        // build/screens/fun-scene-<kind>.png: each short scene just after each of its cues (a line or a sound), plus
        // its very last moment, so you can check the picture goes with what's being said.
        val dir = File("build/screens").apply { mkdirs() }
        val w = 427f
        for (kind in shortKinds) {
            val moments = Scenes.cues(kind, "SPARKY").map { it.at + 0.3f } + (Scenes.length(kind) - 0.05f)
            val cols = 3
            val rows = (moments.size + cols - 1) / cols
            val sheet = BufferedImage((w * 2 * cols).toInt(), (VIEW_H * 2 * rows).toInt(), BufferedImage.TYPE_INT_ARGB)
            val pen = sheet.createGraphics()
            for ((i, t) in moments.withIndex()) {
                val img = BufferedImage((w * 2).toInt(), (VIEW_H * 2).toInt(), BufferedImage.TYPE_INT_ARGB)
                Scenes.draw(game, AwtGfx(img, 2f), kind, t, w, VIEW_H, Mon.SPARKY)
                pen.drawImage(img, (i % cols) * img.width, (i / cols) * img.height, null)
            }
            ImageIO.write(sheet, "png", File(dir, "fun-scene-$kind.png"))
        }
    }
}
