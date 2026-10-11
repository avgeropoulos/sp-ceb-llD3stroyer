package com.critters.game

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The helper for young players: spoken text and arrows that lead the way. */
class HelperTest {
    private class Recorder : Audio {
        val said = ArrayList<String>()
        override fun say(text: String, flush: Boolean) { said += text }
    }

    private fun tap(g: Game, b: Btn) {
        g.pad.key(b, true); repeat(2) { g.update() }
        g.pad.key(b, false); repeat(2) { g.update() }
    }

    private fun btn(d: Dir) = when (d) { Dir.UP -> Btn.UP; Dir.DOWN -> Btn.DOWN; Dir.LEFT -> Btn.LEFT; Dir.RIGHT -> Btn.RIGHT }

    /** Walks wherever the arrow points, clearing any text that pops up, until A is wanted. */
    private fun followArrows(g: Game, maxSteps: Int = 400): Int {
        var steps = 0
        while (steps < maxSteps) {
            repeat(8) { g.update() }
            if (g.ui.isNotEmpty() || g.scriptRunning || g.scene != Game.Scene.WORLD) {
                // Trainers, wild critters and talk: mash A through them.
                tap(g, Btn.A); repeat(6) { g.update() }
                continue
            }
            val p = g.plan ?: run { repeat(20) { g.update() }; g.plan } ?: return steps
            if (p.pressA) return steps
            val d = p.step ?: return steps
            g.pad.key(btn(d), true)
            var n = 0
            val sx = g.px; val sy = g.py; val sm = g.map.id
            while (g.px == sx && g.py == sy && g.map.id == sm && n < 60) { g.update(); n++ }
            g.pad.key(btn(d), false)
            while (g.moving && n < 120) { g.update(); n++ }
            steps++
        }
        return steps
    }

    @Test fun speechCleanup() {
        assertEquals("you got 300 dollars for winning!", Speech.clean("YOU GOT $300 FOR WINNING!"))
        assertEquals("embit level 5", Speech.clean("EMBIT :L5"))
        assertEquals("professor hazel: hi!", Speech.clean("PROF. HAZEL: HI!"))
        assertEquals("h.p. is low", Speech.clean("HP IS LOW"))
    }

    @Test fun dialogsAreReadAloud() {
        val rec = Recorder()
        val g = Game(audio = rec, rng = Random(4))
        g.debugStart("sprout", 2, 8)
        // Face the sign and read it.
        tap(g, Btn.UP); repeat(10) { g.update() }
        tap(g, Btn.A); repeat(10) { g.update() }
        assertTrue(rec.said.any { "sprout town" in it }, "sign read aloud: ${rec.said}")
        rec.said.clear()
        g.voiceOn = false
        tap(g, Btn.A); repeat(10) { g.update() }
        tap(g, Btn.A); repeat(10) { g.update() }
        assertTrue(rec.said.isEmpty(), "voice off stays quiet")
    }

    @Test fun arrowsLeadFromHomeToTheStarters() {
        val rec = Recorder()
        val g = Game(audio = rec, rng = Random(8))
        g.debugStart("home", 4, 6)
        g.flags.clear()
        g.party.clear()
        g.refreshNpcs()
        repeat(80) { g.update() }
        assertNotNull(g.plan, "a plan at home")
        assertTrue(rec.said.any { "lab" in it }, "the hint was spoken: ${rec.said}")
        followArrows(g)
        assertEquals("lab", g.map.id)
        assertTrue(g.plan!!.pressA, "standing at the starter table")
        g.render(); Shots.screen("helper_pressA", g.screen)
    }

    @Test fun arrowsLeadToTheFirstGym() {
        val g = Game(rng = Random(12))
        g.debugStart("sprout", 9, 7, starter = 3, level = 30)
        repeat(20) { g.update() }
        g.render(); Shots.screen("helper_arrow", g.screen)
        followArrows(g, 600)
        assertEquals("gym1", g.map.id, "ended at ${g.map.id} ${g.px},${g.py}")
        assertTrue(g.plan!!.pressA, "at ${g.px},${g.py} step=${g.plan?.step} scene=${g.scene} ui=${g.ui} script=${g.scriptRunning}")
    }
}
