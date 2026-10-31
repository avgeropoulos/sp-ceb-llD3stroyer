package com.bouzoukihero.core

import java.awt.image.BufferedImage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Plays the game the way a person might, frame by frame, to catch crashes. */
class CrashTest {
    private val frame = 1 / 60.0

    private fun session(tune: Tune, d: Difficulty, buttons: Buttons) =
        Session(tune.title, d, Charts.build(tune.sourceNotes(), d, buttons = buttons), tempo = tune.bpm.toDouble()).also { it.viewH = 740f }

    @Test fun opaPlatesDontCrash() {
        // Fill the OPA! meter, set it off, and keep playing until the plates have shattered.
        val tune = Tunes.all[3]
        val s = session(tune, Difficulty.EASY, Buttons.TAB)
        val g = AwtGfx(BufferedImage(360, 740, BufferedImage.TYPE_INT_ARGB), 1f)
        var t = 0.0
        var tapped = false
        while (t < tune.sourceNotes().last().time + 4) {
            s.update(t, frame.toFloat())
            for (gem in s.gems) if (gem.judged == null && kotlin.math.abs(gem.time - t) < frame / 2) {
                s.press(gem.lane, gem.time); s.release(gem.lane, gem.time + 0.05)
            }
            if (!tapped && s.opaMeter >= 0.5f) {
                s.render(g)
                tapped = s.tap(225f, 20f) == Session.Action.OPA
                assertTrue(s.opaActive)
            }
            s.render(g)
            t += frame
        }
        assertTrue(tapped, "the OPA! meter filled up and was set off")
    }

    @Test fun stressPlayEverySong() {
        val g = AwtGfx(BufferedImage(360, 740, BufferedImage.TYPE_INT_ARGB), 1f)
        val rnd = Random(42)
        for (tune in Tunes.all) for (d in Difficulty.entries) for (b in Buttons.entries) {
            val s = session(tune, d, b)
            if (b == Buttons.TAB) s.topInset = 202.5f
            var t = -1.0
            val end = tune.sourceNotes().last().time + 3
            while (t < end && s.state != Session.State.FINISHED) {
                s.update(t, frame.toFloat())
                // Random fingers, slides and chords.
                repeat(rnd.nextInt(3)) {
                    val lane = rnd.nextInt(-1, s.lanes + 1)
                    if (rnd.nextBoolean()) s.press(lane, t) else s.release(lane, t)
                }
                // Taps anywhere: OPA!, pause, resume, restart now and then.
                if (rnd.nextInt(30) == 0) {
                    when (s.tap(rnd.nextFloat() * 360, rnd.nextFloat() * 740)) {
                        Session.Action.PAUSE -> { s.releaseAll(t); s.state = Session.State.PAUSED }
                        Session.Action.RESUME -> s.state = Session.State.PLAYING
                        Session.Action.RESTART -> { s.restart(); t = -1.0 }
                        else -> {}
                    }
                }
                if (s.state == Session.State.PAUSED && rnd.nextInt(20) == 0) s.state = Session.State.PLAYING
                if (rnd.nextInt(500) == 0) s.viewH = 600f + rnd.nextInt(300)
                s.render(g)
                t += frame
            }
            s.finish()
            s.render(g)
        }
    }
}
