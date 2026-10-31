package com.roccoquest.team

import com.roccoquest.game.Music
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.SpeechText
import com.roccoquest.game.Storage
import com.roccoquest.game.VIEW_H
import com.roccoquest.game.VoiceCast
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** The long scenes (THE BIRTHDAY CAKE ... THE WRONG PIPE): every line is said in full, at a natural speed. */
class LongScenesTimingTest {
    private class Ears : SoundSink {
        override fun play(s: Sound) {}
        override fun music(m: Music) {}
        override fun say(text: String) {}
    }

    private val game = TeamGame(TeamInput(), Ears(), Storage.Memory().apply { save("gift1", 1); save("gift2", 1) })
    private val kinds = LongScenes.FIRST until LongScenes.FIRST + LongScenes.titles.size

    /** About how long [c]'s line takes to say: the game's own guess, at that voice's natural speed. */
    private fun saying(c: Scenes.Cue) = SpeechText.seconds(SpeechText.tidy(c.text), VoiceCast.parts.getValue(c.voice).rate)

    @Test fun everyLineIsFinishedBeforeTheNextOne() {
        // A new scene line cuts off the one before it: so each line has to be finished before the next one
        // starts, and the last one before the scene ends. With the longest Pokemon name, so it fits everybody.
        val longName = Mon.entries.maxBy { it.label.length }.label
        for (kind in kinds) {
            val name = Scenes.titles[kind]
            val cues = Scenes.cues(kind, longName)
            val spoken = cues.filter { it.text.isNotEmpty() }
            assertTrue(cues.zipWithNext().all { (a, b) -> a.at <= b.at }, "$name: cues in order")
            assertTrue(cues.all { it.at >= 0f && it.at < Scenes.length(kind) - 0.5f }, "$name: cues inside the scene")
            assertTrue(cues.all { it.text.isNotEmpty() || it.sound != null }, "$name: no empty cues")
            assertTrue(spoken.size >= 4, "$name still has plenty to say")
            assertTrue(spoken.none { '*' in it.text }, "$name: no *stars* read out loud")
            for ((a, b) in spoken.zipWithNext()) {
                val said = "%.2f".format(saying(a))
                assertTrue(b.at - a.at >= saying(a), "$name: \"${a.text}\" ($said s from ${a.at} s) is said before \"${b.text}\" (${b.at} s)")
            }
            val last = spoken.last()
            assertTrue(last.at + saying(last) <= Scenes.length(kind) - 0.3f, "$name: \"${last.text}\" is said before the scene ends")
            // Still a long scene, but short enough that the old tests' 13.8 s run sees it finish.
            assertTrue(Scenes.length(kind) in 10f..13.4f, "$name: ${Scenes.length(kind)} s")
        }
    }

    @Test fun screenshots() {
        // Six moments of each long scene on one sheet (3 x 2): build/screens/long-<n>.png
        val moments = listOf(
            floatArrayOf(1.0f, 4.8f, 6.9f, 8.0f, 10.8f, 13.0f),
            floatArrayOf(2.2f, 3.5f, 6.0f, 6.95f, 8.5f, 12.1f),
            floatArrayOf(1.0f, 4.4f, 7.0f, 9.5f, 10.8f, 13.0f),
            floatArrayOf(1.0f, 2.6f, 4.0f, 7.4f, 8.5f, 10.3f),
            floatArrayOf(0.8f, 3.0f, 5.5f, 7.8f, 9.4f, 13.1f),
            floatArrayOf(1.0f, 3.0f, 4.6f, 7.8f, 11.2f, 11.9f),
        )
        val dir = File("build/screens").apply { mkdirs() }
        val w = 427f
        for (kind in kinds) {
            val sheet = BufferedImage((w * 6).toInt(), (VIEW_H * 4).toInt(), BufferedImage.TYPE_INT_ARGB)
            for ((i, t) in moments[kind - LongScenes.FIRST].withIndex()) {
                val img = BufferedImage((w * 2).toInt(), (VIEW_H * 2).toInt(), BufferedImage.TYPE_INT_ARGB)
                val gfx = AwtGfx(img, 2f)
                Scenes.draw(game, gfx, kind, t, w, VIEW_H, Mon.SPARKY)
                gfx.stampTime("%.1f s".format(t))
                sheet.createGraphics().drawImage(img, (i % 3) * img.width, (i / 3) * img.height, null)
            }
            ImageIO.write(sheet, "png", File(dir, "long-${kind - LongScenes.FIRST}.png"))
        }
    }

    private fun AwtGfx.stampTime(s: String) = text(s, 8f, VIEW_H - 8f, 8f, 0xFFFFFFFF.toInt(), 0)
}
