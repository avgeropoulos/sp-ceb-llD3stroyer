package com.herculesapollo.walk

import com.roccoquest.game.Storage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DogGameTest {
    private val dt = 1f / 60f

    private fun run(g: WalkGame, seconds: Float) = repeat((seconds * 60).toInt()) { g.update(dt) }

    /** Picks "BE HERCULES" or "BE APOLLO" on the title screen and skips the intro. */
    private fun adventure(herc: Boolean, walk: Int = 0, storage: Storage = Storage.Memory()): Pair<WalkGame, DogGame> {
        val g = WalkGame(WalkInput(), storage = storage)
        g.viewW = 427f
        run(g, 0.5f)
        g.input.tapX = if (herc) 213f else 400f
        g.input.tapY = WalkControls.CARD_TOP + 20f
        g.input.tap = true
        g.update(dt)
        val dg = assertNotNull(g.dogGame)
        assertEquals(herc, dg.player.herc)
        assertTrue(g.dogMode)
        if (walk != 0) dg.startWalk(walk)
        run(g, 2.2f)
        assertEquals(DogGame.State.PLAYING, dg.state)
        return g to dg
    }

    private fun place(g: WalkGame, dg: DogGame, x: Float) {
        dg.player.x = x
        dg.player.y = GROUND
        dg.player.vx = 0f
        dg.player.vy = 0f
        dg.camX = x - dg.worldW * 0.4f
        g.update(dt)
    }

    private fun clearCritters(dg: DogGame) = dg.course.critters.forEach { it.mood = Mood.GONE }

    /** Jumps holding the button for [hold] seconds; returns the highest point reached. */
    private fun jumpHeight(g: WalkGame, dg: DogGame, hold: Float): Float {
        g.input.jump = true
        var t = 0f
        var peak = 0f
        do {
            if (t >= hold) g.input.jump = false
            g.update(dt)
            t += dt
            peak = maxOf(peak, GROUND - dg.player.y)
        } while (!dg.player.onGround || t < 0.1f)
        g.input.jump = false
        g.update(dt)
        return peak
    }

    @Test fun controlsAreTight() {
        val (g, dg) = adventure(herc = false)
        clearCritters(dg)
        val p = dg.player
        g.input.right = true
        run(g, 0.1f)
        assertEquals(p.maxSpeed, p.vx, "full speed in a tenth of a second")
        g.input.right = false
        run(g, 0.09f)
        assertEquals(0f, p.vx, "stops almost at once")
        g.input.right = true
        run(g, 0.2f)
        g.input.right = false
        g.input.left = true
        g.update(dt)
        assertTrue(p.vx < 0f, "turns around on the spot")
        assertEquals(-1, p.facing)
        g.input.left = false
        run(g, 0.2f)
    }

    @Test fun holdJumpToJumpHigher() {
        val (g, dg) = adventure(herc = false)
        clearCritters(dg)
        val high = jumpHeight(g, dg, 1f)
        assertTrue(abs(high - 96f) < 5f, "Apollo jumps $high")
        val low = jumpHeight(g, dg, 0.05f)
        assertTrue(low < high * 0.5f, "a tap is a hop: $low")

        val (g2, dg2) = adventure(herc = true)
        clearCritters(dg2)
        val herc = jumpHeight(g2, dg2, 1f)
        assertTrue(abs(herc - 56f) < 5f, "Hercules jumps $herc")
    }

    @Test fun herculesIsFaster() {
        assertTrue(Player(herc = true).maxSpeed > Player(herc = false).maxSpeed)
        assertTrue(Player(herc = false).jumpV > Player(herc = true).jumpV)
    }

    @Test fun jumpBufferAndCoyoteTime() {
        val (g, dg) = adventure(herc = false)
        clearCritters(dg)
        val p = dg.player
        // Press JUMP a moment before landing: the jump still happens.
        g.input.jump = true
        g.update(dt)
        g.input.jump = false
        while (p.vy < 0f || GROUND - p.y > 8f) g.update(dt)
        g.input.jump = true
        g.update(dt)
        g.input.jump = false
        repeat(12) { g.update(dt) }
        assertTrue(GROUND - p.y > 10f, "buffered jump")
        run(g, 1f)

        // Run off the end of a bench and jump just after: coyote time saves it.
        val bench = dg.ledges.first { it.top == GROUND - 22f }
        p.x = bench.x1 - 2f; p.y = bench.top; p.vy = 0f
        g.update(dt)
        assertTrue(p.onGround)
        g.input.right = true
        while (p.onGround) g.update(dt)
        repeat(3) { g.update(dt) }
        g.input.jump = true
        g.update(dt)
        g.input.right = false
        assertTrue(p.vy < -200f, "coyote jump")
        g.input.jump = false
        run(g, 1f)
    }

    @Test fun apolloJumpsOntoTheBranchForTheSquirrel() {
        val (g, dg) = adventure(herc = false)
        val squirrel = dg.course.critters.first { it.kind == Kind.SQUIRREL }
        // Let it scamper up the tree first.
        place(g, dg, squirrel.x - 60f)
        run(g, 3f)
        assertEquals(Mood.SAFE, squirrel.mood)
        val chasedBefore = dg.chased
        place(g, dg, squirrel.home - 10f)
        g.input.jump = true
        g.input.right = true
        run(g, 0.7f)
        g.input.jump = false
        g.input.right = false
        run(g, 0.5f)
        assertEquals(chasedBefore + 1, dg.chased, "caught it on the branch")
    }

    @Test fun herculesCantReachTheBranch() {
        val (g, dg) = adventure(herc = true)
        val tree = dg.ledges.first { it.top == GROUND - 84f }
        place(g, dg, tree.x0 + 6f)
        assertTrue(jumpHeight(g, dg, 1f) < 84f)
    }

    @Test fun herculesJumpsOnTheFenceForTheCat() {
        val (g, dg) = adventure(herc = true, walk = 1)
        val cat = dg.course.critters.first { it.kind == Kind.CAT }
        place(g, dg, cat.x - 70f)
        g.input.right = true
        g.input.jump = true
        run(g, 0.6f)
        g.input.jump = false
        run(g, 0.6f)
        g.input.right = false
        assertTrue(cat.mood == Mood.CHASED || cat.mood == Mood.GONE)
    }

    @Test fun apolloBarkFreezesTheBunny() {
        val (g, dg) = adventure(herc = false, walk = 2)
        val bunny = dg.course.critters.first { it.kind == Kind.RABBIT }
        place(g, dg, bunny.x - 90f)
        g.input.action = true
        g.update(dt)
        g.input.action = false
        assertTrue(bunny.frozenT > 0f)
        g.input.right = true
        run(g, 1.2f)
        g.input.right = false
        assertEquals(1, dg.chased)
    }

    @Test fun herculesDashesDownTheBunny() {
        val (g, dg) = adventure(herc = true, walk = 2)
        val bunny = dg.course.critters.first { it.kind == Kind.RABBIT }
        place(g, dg, bunny.x - 110f)
        g.input.right = true
        run(g, 0.35f)
        g.input.action = true
        g.update(dt)
        g.input.action = false
        run(g, 1.2f)
        g.input.right = false
        assertEquals(1, dg.chased)
    }

    @Test fun skunkStinks() {
        val (g, dg) = adventure(herc = true, walk = 2)
        val skunk = dg.course.critters.first { it.kind == Kind.SKUNK }
        dg.score = 300
        place(g, dg, skunk.x - 60f)
        g.input.right = true
        run(g, 0.8f)
        g.input.right = false
        assertTrue(dg.player.stinkT > 0f)
        assertEquals(200, dg.score)
    }

    @Test fun umbrellasAreBouncy() {
        val (g, dg) = adventure(herc = false, walk = 3)
        clearCritters(dg)
        val u = dg.ledges.first { it.bouncy }
        dg.player.x = (u.x0 + u.x1) / 2f
        dg.player.y = u.top - 20f
        dg.player.vy = 100f
        dg.player.onGround = false
        run(g, 0.2f)
        assertTrue(dg.player.vy < 0f || dg.player.y < u.top - 20f, "bounced")
    }

    /** Runs each walk start to finish as each dog: hold right, jump bikes and skunks. */
    @Test fun everyWalkCanBeFinishedAsEitherDog() {
        for (herc in listOf(true, false)) for (i in Walks.all.indices) {
            val (g, dg) = adventure(herc, walk = i)
            val p = dg.player
            g.input.right = true
            var t = 0f
            while (dg.state == DogGame.State.PLAYING && t < 60f) {
                val danger = dg.course.bikes.any { it.active && it.x - p.x in -10f..60f } ||
                    dg.course.critters.any { it.kind == Kind.SKUNK && it.catchable && it.x - p.x in 0f..36f }
                g.input.jump = danger || (t % 1.2f < 0.4f)
                g.input.action = t % 0.9f < 0.05f
                g.update(dt)
                t += dt
            }
            assertEquals(DogGame.State.HOME, dg.state, "${p.name} on ${dg.course.walk.title}")
            assertTrue(dg.hearts > 0)
            println("${p.name} on ${dg.course.walk.title}: chased ${dg.chased}/${dg.course.total} in ${"%.1f".format(t)}s, hearts ${dg.hearts}")
        }
    }

    @Test fun finishingAWalkSavesProgressAndAllDoneReturnsToTitle() {
        val store = Storage.Memory()
        val (g, dg) = adventure(herc = true, walk = 0, storage = store)
        place(g, dg, dg.course.homeX + 20f)
        g.input.right = true
        run(g, 0.3f)
        g.input.right = false
        assertEquals(DogGame.State.HOME, dg.state)
        run(g, 3f)
        assertEquals(DogGame.State.RESULTS, dg.state)
        val slots = SaveSlots(store)
        assertEquals(1, slots.walk(WalkGame.HERC))
        assertEquals(dg.score, slots.score(WalkGame.HERC))
        assertTrue(!slots.started(WalkGame.APOLLO))
        assertEquals(1, g.adventureWalk(herc = true))

        dg.startWalk(Walks.all.lastIndex)
        run(g, 2.2f)
        place(g, dg, dg.course.homeX + 20f)
        g.input.right = true
        run(g, 0.3f)
        g.input.right = false
        run(g, 3f)
        run(g, 2f)
        g.input.tap = true
        g.update(dt)
        assertEquals(DogGame.State.ALL_DONE, dg.state)
        run(g, 3.5f)
        g.input.tap = true
        g.update(dt)
        assertNull(g.dogGame)
        assertEquals(WalkGame.State.TITLE, g.state)
        assertTrue(!SaveSlots(store).started(WalkGame.HERC), "back to a new game after the last walk")
    }

    @Test fun saveAndQuitFromThePauseScreen() {
        val store = Storage.Memory()
        val (g, dg) = adventure(herc = false, walk = 2, storage = store)
        dg.score = 450
        dg.startWalk(2)
        run(g, 2.2f)
        g.input.pause = true
        g.update(dt)
        g.input.pause = false
        g.update(dt)
        assertTrue(g.paused)
        g.input.tapX = PauseMenu.x(PauseMenu.Action.QUIT, g.viewW, canSwitch = true) + 10f
        g.input.tapY = PauseMenu.Y + 10f
        g.input.tap = true
        g.update(dt)
        assertNull(g.dogGame)
        assertEquals(WalkGame.State.TITLE, g.state)
        val slots = SaveSlots(store)
        assertEquals(2, slots.walk(WalkGame.APOLLO))
        assertEquals(450, slots.score(WalkGame.APOLLO))

        // Picking Apollo again carries on from there, with the score.
        val again = WalkGame(WalkInput(), storage = store).apply { viewW = 427f }
        run(again, 0.5f)
        again.input.tapX = 400f
        again.input.tapY = WalkControls.CARD_TOP + 20f
        again.input.tap = true
        again.update(dt)
        val dg2 = assertNotNull(again.dogGame)
        assertEquals(2, dg2.walkIndex)
        assertEquals(450, dg2.score)
    }

    /** Lizards run up the backyard fence: too high for Hercules, not for Apollo. */
    @Test fun onlyApolloReachesLizardsOnTheFence() {
        val backyard = Walks.all.indexOfFirst { it.theme == Theme.BACKYARD }
        for (herc in listOf(true, false)) {
            val (g, dg) = adventure(herc, walk = backyard)
            val liz = dg.course.critters.first { it.kind == Kind.LIZARD }
            place(g, dg, liz.x - 60f)
            run(g, 2f)
            assertEquals(Mood.SAFE, liz.mood, "up the fence")
            val before = dg.chased
            // Jump straight up from right underneath it.
            place(g, dg, liz.x)
            g.input.jump = true
            run(g, 0.75f)
            g.input.jump = false
            run(g, 0.6f)
            if (herc) assertEquals(before, dg.chased, "too high for Hercules") else assertEquals(before + 1, dg.chased, "Apollo got it")
        }
    }

    @Test fun switchDogFromThePauseScreen() {
        val store = Storage.Memory()
        val (g, dg) = adventure(herc = true, walk = 3, storage = store)
        dg.score = 600
        dg.startWalk(3)
        run(g, 2.2f)
        g.input.pause = true
        g.update(dt)
        g.input.pause = false
        g.update(dt)
        g.input.tapX = PauseMenu.x(PauseMenu.Action.SWITCH, g.viewW, canSwitch = true) + 10f
        g.input.tapY = PauseMenu.Y + 10f
        g.input.tap = true
        g.update(dt)
        val apollo = assertNotNull(g.dogGame)
        assertTrue(!apollo.player.herc, "now playing as Apollo")
        assertEquals(0, apollo.walkIndex, "Apollo's own (new) game")
        val slots = SaveSlots(store)
        assertEquals(3, slots.walk(WalkGame.HERC), "Hercules' walk was saved")
        assertEquals(600, slots.score(WalkGame.HERC))
    }

    @Test fun backButtonPausesThenLeaves() {
        val store = Storage.Memory()
        val (g, dg) = adventure(herc = false, walk = 1, storage = store)
        assertTrue(!g.atTitle)
        g.input.back = true
        g.update(dt)
        assertTrue(g.paused, "Back pauses")
        g.input.back = true
        g.update(dt)
        assertTrue(!g.paused, "Back again carries on")
        // From the results screen, Back goes to the title without losing the finished walk.
        place(g, dg, dg.course.homeX + 20f)
        g.input.right = true
        run(g, 0.3f)
        g.input.right = false
        run(g, 3f)
        assertEquals(DogGame.State.RESULTS, dg.state)
        g.input.back = true
        g.update(dt)
        assertTrue(g.atTitle)
        assertEquals(2, SaveSlots(store).walk(WalkGame.APOLLO))
    }

    @Test fun screenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(g: WalkGame, name: String) {
            val scale = 3f
            val img = BufferedImage((g.viewW * scale).toInt(), (VIEW_H * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
            g.render(AwtGfx(img, scale))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        val (g, dg) = adventure(herc = false)
        val squirrel = dg.course.critters.first { it.kind == Kind.SQUIRREL }
        place(g, dg, squirrel.x - 60f)
        run(g, 3f)
        place(g, dg, squirrel.home - 10f)
        g.input.jump = true
        g.input.right = true
        run(g, 0.3f)
        g.input.right = false
        shot(g, "7-apollo-branch")
        g.input.jump = false
        run(g, 1f)
        g.input.action = true
        g.update(dt)
        g.input.action = false
        run(g, 0.15f)
        shot(g, "7-apollo-bark")

        val (g2, dg2) = adventure(herc = true, walk = 1)
        val cat = dg2.course.critters.first { it.kind == Kind.CAT }
        place(g2, dg2, cat.x - 70f)
        g2.input.right = true
        g2.input.jump = true
        run(g2, 0.3f)
        shot(g2, "8-herc-fence")
        g2.input.jump = false
        g2.input.right = false
        run(g2, 1f)
        g2.input.right = true
        run(g2, 0.3f)
        g2.input.action = true
        g2.update(dt)
        g2.input.action = false
        run(g2, 0.1f)
        shot(g2, "8-herc-dash")

        val backyard = Walks.all.indexOfFirst { it.theme == Theme.BACKYARD }
        val (g3, dg3) = adventure(herc = false, walk = backyard)
        val pool = dg3.course.props.first { it.kind == PropKind.POOL }
        place(g3, dg3, pool.x + 40f)
        run(g3, 0.3f)
        shot(g3, "9-backyard-pool")
        val cot = dg3.course.props.first { it.kind == PropKind.COT }
        place(g3, dg3, cot.x - 40f)
        run(g3, 0.6f)
        shot(g3, "9-backyard-cot")
    }
}
