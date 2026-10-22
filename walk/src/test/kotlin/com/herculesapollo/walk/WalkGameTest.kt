package com.herculesapollo.walk

import com.roccoquest.game.Chiptune
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WalkGameTest {
    private val dt = 1f / 60f

    private fun run(g: WalkGame, seconds: Float) = repeat((seconds * 60).toInt()) { g.update(dt) }

    private fun newGame(walk: Int = 0): WalkGame {
        val g = WalkGame(WalkInput())
        g.viewW = 427f
        run(g, 0.5f)
        g.input.tap = true
        g.update(dt)
        assertEquals(WalkGame.State.INTRO, g.state)
        if (walk != 0) g.startWalk(walk)
        run(g, 2.5f)
        assertEquals(WalkGame.State.PLAYING, g.state)
        return g
    }

    /** Teleports the walker (and the dogs beside them) to [x]. */
    private fun place(g: WalkGame, x: Float) {
        g.walker.x = x
        for (d in g.dogs) { d.x = x + d.offset; d.mode = DogMode.FOLLOW; d.target = null }
        g.camX = x - g.viewW * 0.3f
        run(g, 0.05f)
    }

    private fun press(g: WalkGame, herc: Boolean) {
        if (herc) g.input.herc = true else g.input.apollo = true
        g.update(dt)
        g.input.herc = false
        g.input.apollo = false
        g.update(dt)
    }

    @Test fun walksLoad() {
        assertEquals(5, Walks.all.size)
        for (w in Walks.all) {
            val c = Course(w)
            assertTrue(c.total >= 6, "${w.title} has ${c.total} critters")
            assertTrue(c.props.any { it.kind == PropKind.HOME }, "${w.title} ends at home")
            assertTrue(c.critters.all { it.x in 100f..c.homeX - 40f }, "${w.title} critters are on the walk")
        }
        assertEquals(WalkSprites.hercStand.w, WalkSprites.apolloStand.w, "the two doodles are the same size")
    }

    @Test fun walkerWalksAndDogsFollow() {
        val g = newGame()
        val start = g.walker.x
        g.input.right = true
        run(g, 1f)
        g.input.right = false
        assertTrue(g.walker.x > start + 60f)
        run(g, 1f)
        assertTrue(abs(g.herc.x - (g.walker.x + g.herc.offset)) < 3f)
        assertTrue(abs(g.apollo.x - (g.walker.x + g.apollo.offset)) < 3f)
        assertTrue(g.apollo.x > g.herc.x)
    }

    private fun abs(f: Float) = kotlin.math.abs(f)

    @Test fun dogsChaseSquirrels() {
        val g = newGame()
        val squirrel = g.course.critters.first { it.kind == Kind.SQUIRREL }
        place(g, squirrel.x - 150f)
        press(g, herc = false)
        run(g, 1.5f)
        assertEquals(1, g.chased)
        assertEquals(Kind.SQUIRREL.points, g.score - g.treatsGot * 25)
        assertTrue(squirrel.mood == Mood.CHASED || squirrel.mood == Mood.GONE)
    }

    @Test fun squirrelEscapesUpTheTree() {
        val g = newGame()
        val squirrel = g.course.critters.first { it.kind == Kind.SQUIRREL }
        place(g, squirrel.x - 70f)
        run(g, 3f)
        assertEquals(Mood.SAFE, squirrel.mood)
        press(g, herc = false)
        run(g, 1f)
        assertEquals(0, g.chased)
    }

    @Test fun onlyApolloReachesTheCat() {
        val g = newGame(1)
        val cat = g.course.critters.first { it.kind == Kind.CAT }
        place(g, cat.x - 90f)
        press(g, herc = true)
        run(g, 2.6f)
        assertEquals(0, g.chased, "Hercules can't jump that high")
        assertTrue(cat.catchable)
        place(g, cat.x - 90f)
        press(g, herc = false)
        run(g, 1.5f)
        assertEquals(1, g.chased, "Apollo can")
    }

    @Test fun onlyHerculesCatchesBunnies() {
        val g = newGame(2)
        val bunny = g.course.critters.first { it.kind == Kind.RABBIT }
        place(g, bunny.x - 130f)
        press(g, herc = false)
        run(g, 2.5f)
        assertEquals(Mood.SAFE, bunny.mood, "the bunny outruns Apollo")
        assertTrue(g.popups.any { it.text == "TOO FAST!" } || g.chased == 0)

        val g2 = newGame(2)
        val bunny2 = g2.course.critters.first { it.kind == Kind.RABBIT }
        place(g2, bunny2.x - 130f)
        press(g2, herc = true)
        run(g2, 2f)
        assertEquals(1, g2.chased, "Hercules catches it")
    }

    @Test fun dontChaseTheSkunk() {
        val g = newGame(2)
        g.score = 500
        val skunk = g.course.critters.first { it.kind == Kind.SKUNK }
        place(g, skunk.x - 100f)
        press(g, herc = true)
        run(g, 1.5f)
        assertTrue(g.herc.stinkT > 0f)
        assertEquals(400, g.score)
        assertEquals(0, g.chased)
        // A stinky dog won't chase anything for a while.
        press(g, herc = true)
        assertTrue(g.herc.mode != DogMode.DASH)
    }

    @Test fun bikesBumpYouUnlessYouJump() {
        val g = newGame()
        val bike = g.course.bikes.first()
        place(g, bike.x - 160f)
        run(g, 1.6f)
        assertEquals(2, g.hearts)

        val g2 = newGame()
        val bike2 = g2.course.bikes.first()
        place(g2, bike2.x - 160f)
        var t = 0f
        while (t < 2f) {
            if (bike2.active && bike2.x - g2.walker.x in 0f..40f && g2.walker.onGround) {
                g2.input.jump = true
            }
            g2.update(dt)
            g2.input.jump = false
            t += dt
        }
        assertEquals(3, g2.hearts)
    }

    @Test fun threeBumpsAndYouTryAgain() {
        val g = newGame()
        g.hearts = 1
        val bike = g.course.bikes.first()
        place(g, bike.x - 160f)
        run(g, 1.6f)
        assertEquals(WalkGame.State.OUCH, g.state)
        run(g, 2f)
        g.input.tap = true
        g.update(dt)
        assertEquals(WalkGame.State.INTRO, g.state)
        assertEquals(3, g.hearts)
    }

    @Test fun comingHomeFinishesTheWalk() {
        val g = newGame()
        place(g, g.course.homeX - 30f)
        g.input.right = true
        run(g, 1f)
        g.input.right = false
        assertEquals(WalkGame.State.HOME, g.state)
        run(g, 3.5f)
        assertEquals(WalkGame.State.RESULTS, g.state)
        assertEquals(300, g.bonus)
        run(g, 2f)
        g.input.tap = true
        g.update(dt)
        assertEquals(WalkGame.State.INTRO, g.state)
        assertEquals(1, g.walkIndex)
    }

    /** Plays each walk start to finish: hold right, send the dogs, jump the bikes. */
    @Test fun everyWalkCanBeFinished() {
        for (i in Walks.all.indices) {
            val g = newGame(i)
            g.input.right = true
            var t = 0f
            var send = 0f
            while (g.state == WalkGame.State.PLAYING && t < 90f) {
                val bike = g.course.bikes.firstOrNull { it.active && it.x - g.walker.x in 0f..40f }
                g.input.jump = bike != null && g.walker.onGround
                send += dt
                if (send > 0.4f) {
                    send = 0f
                    val dog = if ((t * 2.5f).toInt() % 2 == 0) g.herc else g.apollo
                    val target = g.pickTarget(dog)
                    if (target != null && target.kind != Kind.SKUNK) {
                        if (dog.herc) g.input.herc = true else g.input.apollo = true
                    }
                }
                g.update(dt)
                g.input.herc = false
                g.input.apollo = false
                t += dt
            }
            assertEquals(WalkGame.State.HOME, g.state, "${g.course.walk.title}: reached home")
            assertTrue(g.hearts > 0)
            assertTrue(g.chased >= g.course.total / 3, "${g.course.walk.title}: chased ${g.chased}/${g.course.total}")
            println("${g.course.walk.title}: chased ${g.chased}/${g.course.total}, treats ${g.treatsGot}, score ${g.score}")
        }
    }

    @Test fun audioRenders() {
        for (t in WalkTune.entries) {
            val song = WalkSongs.of(t) ?: continue
            assertTrue(Chiptune.render(song).isNotEmpty())
        }
        for (s in WalkSound.entries) assertTrue(WalkSynth.render(s).isNotEmpty(), "$s")
    }

    /** Renders a few frames to build/screens for eyeballing. */
    @Test fun screenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(g: WalkGame, name: String) {
            val scale = 3f
            val img = BufferedImage((g.viewW * scale).toInt(), (VIEW_H * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
            g.render(AwtGfx(img, scale))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        val title = WalkGame(WalkInput()).apply { viewW = 427f }
        title.slots.save(WalkGame.HERC, 2, 1350)
        run(title, 1f)
        shot(title, "0-title")

        val g = WalkGame(WalkInput()).apply { viewW = 427f }
        run(g, 0.5f)
        g.input.tap = true
        g.update(dt)
        run(g, 1f)
        shot(g, "1-intro")
        run(g, 1.5f)
        shot(g, "1-start")
        val squirrel = g.course.critters.first { it.kind == Kind.SQUIRREL }
        place(g, squirrel.x - 150f)
        press(g, herc = false)
        run(g, 0.25f)
        shot(g, "1-chase")
        val birds = g.course.critters.first { it.kind == Kind.PIGEON }
        place(g, birds.x - 120f)
        press(g, herc = true)
        run(g, 0.4f)
        shot(g, "1-pigeons")
        val bike = g.course.bikes.first()
        place(g, bike.x - 200f)
        run(g, 0.5f)
        shot(g, "1-bike")
        g.input.pause = true
        g.update(dt)
        g.input.pause = false
        g.update(dt)
        shot(g, "1-paused")
        g.input.tapX = 10f
        g.input.tapY = 10f
        g.input.tap = true
        g.update(dt)

        for ((walk, kind) in listOf(1 to Kind.CAT, 2 to Kind.RABBIT, 3 to Kind.CRAB, 4 to Kind.RACCOON)) {
            val w = newGame(walk)
            val c = w.course.critters.first { it.kind == kind }
            place(w, c.x - 110f)
            press(w, herc = kind != Kind.RABBIT)
            run(w, 0.35f)
            shot(w, "${walk + 1}-${kind.label}")
        }
        val sk = newGame(4)
        val skunk = sk.course.critters.first { it.kind == Kind.SKUNK }
        place(sk, skunk.x - 100f)
        press(sk, herc = true)
        run(sk, 1.2f)
        shot(sk, "5-skunk")

        val home = newGame()
        place(home, home.course.homeX - 30f)
        home.input.right = true
        run(home, 2f)
        shot(home, "6-home")
        run(home, 3f)
        shot(home, "6-results")
    }

    @Test fun progressIsSaved() {
        val store = com.roccoquest.game.Storage.Memory()
        val g = WalkGame(WalkInput(), storage = store).apply { viewW = 427f }
        run(g, 0.5f)
        g.input.tap = true
        g.update(dt)
        run(g, 2.5f)
        place(g, g.course.homeX - 10f)
        g.input.right = true
        run(g, 4.5f)
        assertEquals(WalkGame.State.RESULTS, g.state)
        assertEquals(1, SaveSlots(store).walk(WalkGame.WALK))
        assertEquals(g.score, SaveSlots(store).score(WalkGame.WALK))
        assertTrue(store.load("best") > 0)

        val again = WalkGame(WalkInput(), storage = store).apply { viewW = 427f }
        assertEquals(1, again.savedWalk)
        run(again, 0.5f)
        again.input.tapX = 50f
        again.input.tapY = WalkControls.CARD_TOP + 20f
        again.input.tap = true
        again.update(dt)
        assertEquals(1, again.walkIndex)
        assertEquals(g.score, again.score, "the score comes back too")
    }

    @Test fun threeSaveSlots() {
        val store = com.roccoquest.game.Storage.Memory()
        val slots = SaveSlots(store)
        slots.save(WalkGame.WALK, 3, 900)
        val g = WalkGame(WalkInput(), storage = store).apply { viewW = 427f }
        run(g, 0.5f)
        // Pick slot 2 on the title screen: it's empty, so the walk starts from the beginning.
        g.input.tapX = WalkControls.slotX(1, g.viewW) + 10f
        g.input.tapY = WalkControls.SLOT_TOP + 5f
        g.input.tap = true
        g.update(dt)
        assertEquals(WalkGame.State.TITLE, g.state)
        assertEquals(2, g.slots.current)
        assertEquals(0, g.savedWalk)
        g.input.tapX = 50f
        g.input.tapY = WalkControls.CARD_TOP + 20f
        g.input.tap = true
        g.update(dt)
        assertEquals(0, g.walkIndex)
        assertEquals(0, g.score)
        // Slot 1 still has its walk.
        assertEquals(3, slots.walk(WalkGame.WALK, slot = 1))
        assertEquals(900, slots.score(WalkGame.WALK, slot = 1))
    }

    @Test fun oldProgressMovesIntoSlotOne() {
        val store = com.roccoquest.game.Storage.Memory()
        store.save("walk", 2)
        store.save("adventureApollo", 4)
        val g = WalkGame(WalkInput(), storage = store)
        assertEquals(2, g.slots.walk(WalkGame.WALK, slot = 1))
        assertEquals(4, g.adventureWalk(herc = false))
        assertEquals(0, store.load("walk"))
    }
}
