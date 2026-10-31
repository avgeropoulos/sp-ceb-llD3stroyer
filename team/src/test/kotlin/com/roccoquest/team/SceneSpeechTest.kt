package com.roccoquest.team

import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Storage
import com.roccoquest.game.Voice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The funny scenes' lines go with the pictures, so they're said right when their moment comes
 * ([SoundSink.sayNow]), not queued up behind the line before like everyday chatter ([SoundSink.sayAs]),
 * where they came late or got dropped. Skipping a scene stops the talking ([SoundSink.hush]).
 */
class SceneSpeechTest {
    /** Keeps what the game says and how: right now (with the scene clock), politely queued, or hushed. */
    private class Ears : SoundSink {
        class Line(val text: String, val voice: Voice, val at: Float)
        var clock = 0f
        val now = ArrayList<Line>()
        val queued = ArrayList<String>()
        var hushes = 0
        override fun play(s: Sound) {}
        override fun sayAs(text: String, voice: Voice) { queued += text }
        override fun sayNow(text: String, voice: Voice) { now += Line(text, voice, clock) }
        override fun hush() { hushes++ }
    }

    private val dt = 1f / 60f
    private val input = TeamInput()
    private val ears = Ears()
    // (The one-time File 1 welcome-back gift is already given, so it doesn't change these tests.)
    private val storage = Storage.Memory().apply { save("gift1", 1); save("gift2", 1) }
    private val game = TeamGame(input, ears, storage)

    /** One frame; the ears' clock keeps time with the scene's. */
    private fun step() { ears.clock += dt; game.update(dt) }

    private fun run(seconds: Float) = repeat((seconds / dt).toInt()) { step() }

    private fun startPlaying() {
        game.newGame(0, Mon.SPARKY)
        run(3.2f)
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    private fun play(kind: Int) {
        ears.now.clear(); ears.queued.clear(); ears.hushes = 0; ears.clock = 0f
        game.playScene(kind, 1)
        assertEquals(TeamGame.State.SCENE, game.state)
    }

    @Test fun everySceneLineIsSaidRightOnTime() {
        startPlaying()
        for (kind in Scenes.titles.indices) {
            val name = Scenes.titles[kind]
            play(kind)
            var frames = 0
            while (game.state == TeamGame.State.SCENE && frames < ((Scenes.length(kind) + 1f) / dt).toInt()) { step(); frames++ }
            assertEquals(TeamGame.State.MAP, game.state, "$name ends on the map")
            val spoken = Scenes.cues(kind, game.mon.label).filter { it.text.isNotEmpty() }
            assertEquals(spoken.map { it.text }, ears.now.map { it.text }, "$name: every line is said right away, in order")
            assertEquals(spoken.map { it.voice }, ears.now.map { it.voice }, "$name: each in its own voice")
            for ((cue, said) in spoken.zip(ears.now)) {
                assertTrue(said.at >= cue.at - 0.001f && said.at <= cue.at + 2 * dt, "$name: \"${cue.text}\" is said at ${cue.at} s (not ${said.at} s)")
            }
            assertTrue(ears.queued.none { line -> spoken.any { it.text == line } }, "$name: no line waits its turn behind another")
            // At the natural end the last line may finish (a few punchlines run a moment past the end).
            assertEquals(0, ears.hushes, "$name: the last line isn't cut off")
        }
    }

    @Test fun skippingASceneStopsTheTalking() {
        startPlaying()
        val kind = SillyScenes.FIRST + 1
        // A tap right at the start doesn't skip yet (it might be the tap that closed the sticker screen).
        play(kind)
        run(0.3f)
        input.tapX = 100f; input.tapY = 100f; input.tap = true; step()
        assertEquals(TeamGame.State.SCENE, game.state)
        assertEquals(0, ears.hushes)
        // Later on, a tap skips it, and the voices stop with it: nothing goes on talking over the map.
        run(2.5f)
        assertTrue(ears.now.isNotEmpty(), "somebody was talking")
        input.tapX = 100f; input.tapY = 100f; input.tap = true; step()
        assertEquals(TeamGame.State.MAP, game.state)
        assertEquals(1, ears.hushes)
        // The same with the controller's pause button.
        play(SillyScenes.FIRST + 3)
        run(4f)
        input.pause = true; step(); input.pause = false; step()
        assertEquals(TeamGame.State.MAP, game.state)
        assertEquals(1, ears.hushes)
        val said = ears.now.size
        run(2f)
        assertEquals(said, ears.now.size, "no more lines once it's over")
    }

    @Test fun hazardWarningsAreSaidRightAway() {
        startPlaying()
        ears.now.clear(); ears.queued.clear()
        game.sayWarning("Eruption! Jump!")
        assertEquals(listOf("Eruption! Jump!"), ears.now.map { it.text })
        assertEquals(Voice.FROG, ears.now.single().voice)
        assertTrue(ears.queued.isEmpty(), "a warning doesn't wait for other talk to finish")
    }
}
