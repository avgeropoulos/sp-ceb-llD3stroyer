package com.roccoquest.team

import com.roccoquest.game.SoundSink
import com.roccoquest.game.Storage
import com.roccoquest.game.TILE
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Two players: player 2 plays LUGO with a second controller (see Coop.kt). */
class TwoPlayerTest {
    private val dt = 1f / 60f
    private val input = TeamInput()
    private val storage = Storage.Memory().apply { save("gift1", 1); save("gift2", 1) }
    private val game = TeamGame(input, SoundSink {}, storage)
    private val p2 = game.input2
    private val dir = File("build/screens").apply { mkdirs() }

    private fun run(seconds: Float, each: () -> Unit = {}) = repeat((seconds / dt).toInt()) { each(); game.update(dt) }

    private fun startPlaying(level: Int = 0) {
        game.newGame(0, Mon.SPARKY)
        if (level > 0) game.startLevel(level)
        run(3.2f)
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    /** Player 2 taps a button (down for a frame, then up). */
    private fun press(set: (Boolean) -> Unit) {
        set(true); game.update(dt); set(false); game.update(dt)
    }

    private fun join() {
        press { p2.action = it }
        assertTrue(game.p2Joined)
    }

    private fun shot(name: String, scale: Float = 3f) {
        val img = BufferedImage((game.viewW * scale).toInt(), (240 * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img, scale))
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    @Test fun firstControllerIsPlayerOneAndASecondOneIsPlayerTwo() {
        val slots = PadSlots()
        assertEquals(0, slots.playerFor(7))
        assertEquals(0, slots.playerFor(7), "the same controller stays player 1")
        assertEquals(1, slots.playerFor(12))
        assertEquals(1, slots.playerFor(12))
        assertEquals(0, slots.playerFor(30), "a third one helps player 1 and takes nobody's place")
        assertEquals(12, slots.deviceOf(1))
        // Player 2's controller goes away: the place is free, and the game hears about it once.
        assertEquals(1, slots.removed(12))
        assertTrue(slots.takeLost())
        assertFalse(slots.takeLost())
        assertEquals(-1, slots.removed(99))
        assertEquals(1, slots.playerFor(40), "a new controller takes the free place")
        // Player 1's goes away: the next new one is player 1, player 2 stays player 2.
        assertEquals(0, slots.removed(7))
        assertFalse(slots.takeLost())
        assertEquals(0, slots.playerFor(41))
        assertEquals(1, slots.playerFor(40))
    }

    @Test fun eachControllerHasItsOwnDirections() {
        val one = TeamInput()
        val two = TeamInput()
        val s1 = PadSticks(one)
        val s2 = PadSticks(two)
        s2.stick(0.8f, 0f, 0f, 0f)
        assertTrue(two.right)
        assertFalse(one.right)
        // A stick resting near the edge doesn't flicker (on past 0.35, off only below 0.25).
        s2.stick(0.3f, 0f, 0f, 0f)
        assertTrue(two.right)
        s2.stick(0.2f, 0f, 0f, 0f)
        assertFalse(two.right)
        s1.keyL = true; s1.refresh()
        s1.stick(0f, 0f, 1f, 0f)
        assertTrue(one.left && one.right, "keyboard and hat both count")
        s1.clear()
        assertFalse(one.left || one.right)
    }

    @Test fun anyButtonJoinsLugoAndSelectOrThePauseMenuSendsHimHome() {
        startPlaying()
        assertEquals(2, game.team.size, "just player 1's team")
        // A stick nudge alone doesn't join (a controller lying on the sofa).
        p2.right = true; run(0.2f); p2.right = false; run(0.1f)
        assertFalse(game.p2Joined)
        join()
        assertEquals("PLAYER 2: LUGO JOINED!", game.p2NoteForTest())
        assertTrue(game.lugo in game.team)
        assertFalse(game.lugo in game.followers, "player 2 controls him: he's no follower")
        assertTrue(abs(game.lugo.cx - game.leader.cx) < 20f, "he pops in next to player 1")
        // The button that joined doesn't also do something (like a jump).
        run(0.3f)
        assertTrue(game.lugo.onGround)
        // Holding SELECT sends him home.
        p2.craft = true; run(TeamGame.LEAVE_HOLD + 0.1f); p2.craft = false
        assertFalse(game.p2Joined)
        assertEquals(2, game.team.size)
        // Put down on purpose: any button doesn't bring him back, START does.
        press { p2.jump = it }
        assertFalse(game.p2Joined)
        press { p2.pause = it }
        assertTrue(game.p2Joined)
        assertFalse(game.paused, "the START that joined doesn't pause")
        // The pause menu's PLAYER 2: LEAVE (with either controller: up from the first button goes to it).
        press { input.pause = it }
        assertTrue(game.paused)
        press { p2.up = it }
        press { p2.jump = it }
        assertFalse(game.p2Joined)
        // Player 2's controller unplugged: LUGO goes home, and any button brings him back.
        press { p2.pause = it }
        press { input.pause = it }
        assertTrue(game.p2Joined && !game.paused)
        game.padSlots.playerFor(5); game.padSlots.playerFor(6)
        game.padSlots.removed(6)
        game.update(dt)
        assertFalse(game.p2Joined)
        join()
    }

    @Test fun bothHeroesMoveOnTheirOwn() {
        startPlaying()
        game.hurtProof()
        game.foes.clear()
        join()
        run(1.2f)
        val l = game.leader
        val g = game.lugo
        val lx = l.x
        val gx = g.x
        // Player 2 runs right while player 1 stands still...
        run(0.5f) { p2.right = true }
        p2.right = false
        assertTrue(g.x > gx + 30f, "LUGO ran (${g.x - gx})")
        assertTrue(abs(l.x - lx) < 2f, "Rocco stayed put")
        // ...and jumps on his own.
        run(0.4f)
        p2.jump = true; run(0.15f)
        assertFalse(g.onGround)
        assertTrue(l.onGround)
        p2.jump = false
        run(1f)
        assertTrue(g.onGround)
        // Player 2's THROW: a punch with no flower, a fireball with one.
        game.powerForTest(Power.FIRE)
        press { p2.action = it }
        val shots = game.thingsForTest().filterIsInstance<Shot>()
        assertEquals(1, shots.size)
        assertTrue(shots[0].thrower === g)
    }

    @Test fun lugoJumpsHigherAndFloatsMoreThanRocco() {
        startPlaying()
        game.hurtProof()
        game.foes.clear()
        join()
        run(1.2f)
        val l = game.leader
        val g = game.lugo
        // Side by side on flat ground, both holding JUMP.
        val startL = l.bottom
        val startG = g.bottom
        var topL = startL
        var topG = startG
        var airL = 0
        var airG = 0
        input.jump = true; p2.jump = true
        run(1.6f) {
            topL = minOf(topL, l.bottom); topG = minOf(topG, g.bottom)
            if (!l.onGround) airL++
            if (!g.onGround) airG++
        }
        input.jump = false; p2.jump = false
        assertTrue(startG - topG > startL - topL, "LUGO jumps higher (${startG - topG} vs ${startL - topL})")
        assertTrue(airG > airL, "and stays up longer ($airG vs $airL frames)")
    }

    @Test fun theCameraKeepsBothPlayersOnTheScreen() {
        startPlaying()
        game.hurtProof()
        join()
        val l = game.leader
        val g = game.lugo
        var pulled = false
        // Player 2 runs off to the right (jumping now and then) while player 1 stays behind.
        repeat((8f / dt).toInt()) { f ->
            game.fullHeartsForTest()
            game.starForTest(1f)
            p2.right = true
            p2.jump = f % 100 < 15
            val before = l.x
            game.update(dt)
            if (l.x - before > 40f) pulled = true
            if (game.state != TeamGame.State.PLAY) return@repeat
            for (h in listOf(l, g)) {
                assertTrue(h.x >= game.camX - 1f && h.x + h.w <= game.camX + game.viewW + 1f,
                    "${h.label} on screen at frame $f (x ${h.x}, camera ${game.camX})")
            }
            assertTrue(abs(g.cx - l.cx) <= game.viewW - TeamGame.SPREAD_MARGIN + 1f, "never more than a screen apart")
        }
        p2.right = false; p2.jump = false
        assertTrue(pulled, "player 1 got pulled along to the one in front")
    }

    @Test fun lugoLeftBehindPopsBackNextToPlayerOne() {
        startPlaying()
        game.hurtProof()
        join()
        val l = game.leader
        val g = game.lugo
        val hearts = game.hearts
        var popped = false
        var lastX = g.x
        repeat((7f / dt).toInt()) { f ->
            game.starForTest(1f)
            input.right = true
            input.jump = f % 45 < 18
            game.update(dt)
            if (g.x - lastX > 60f) popped = true
            lastX = g.x
            assertTrue(abs(g.cx - l.cx) <= game.viewW - TeamGame.SPREAD_MARGIN + 1f)
        }
        input.right = false; input.jump = false
        assertTrue(popped, "LUGO popped back to player 1")
        assertEquals(hearts, game.hearts, "being left behind costs no heart")
        run(1f)
        assertTrue(g.onGround, "he landed on solid ground")
    }

    @Test fun aPitCostsOneHeartAndLugoComesBack() {
        startPlaying()
        game.hurtProof()
        game.foes.clear()
        join()
        run(1.2f)
        val before = game.hearts
        val g = game.lugo
        g.y = game.level.h * TILE + 20f
        g.vy = 200f
        game.update(dt)
        assertEquals(before - 1, game.hearts)
        assertEquals(TeamGame.State.PLAY, game.state)
        assertTrue(g.y < game.level.h * TILE, "back in the level")
        assertTrue(abs(g.cx - game.leader.cx) < 30f, "next to player 1")
        assertTrue(game.lugoInvuln > 0f)
        run(0.5f)
        assertTrue(g.onGround)
    }

    @Test fun badGuysHurtLugoAndHeReachesTheFlagToo() {
        startPlaying()
        game.hurtProof()
        game.foes.clear()
        join()
        run(1.5f)
        val g = game.lugo
        val before = game.hearts
        game.foes += Grumbler(g.x, g.bottom, false).also { it.active = true }
        game.update(dt)
        assertEquals(before - 1, game.hearts, "a bad guy touching LUGO costs a shared heart")
        assertTrue(game.lugoInvuln > 0f)
        game.foes.clear()
        // LUGO gets to the flag first: LEVEL CLEAR!
        val flag = game.thingsForTest().filterIsInstance<Goal>().first()
        g.x = flag.x + 4f
        g.y = flag.bottom - g.h - 40f
        game.update(dt)
        assertEquals(TeamGame.State.CLEAR, game.state)
    }

    @Test fun playerTwoCraftsAtHome() {
        game.newGame(0, Mon.SPARKY)
        game.enterHome()
        run(0.5f)
        join()
        game.inventory.fill(50)
        game.coins = 500
        val roccoX = game.homeXForTest()
        // LUGO walks to the crafting table and opens it with his button.
        game.setLugoHomeXForTest(Homes.CRAFT_X + 12f)
        run(2.7f)
        press { p2.action = it }
        assertTrue(game.homeCraft, "LUGO opened the crafting table")
        assertEquals(roccoX, game.homeXForTest(), "player 1 stayed where he was")
        // Player 2 moves around it: right changes the tab, down picks a row, A makes it.
        press { p2.right = it }
        assertEquals(1, game.craftTabForTest())
        val tntRow = game.recipes(1).indexOfFirst { it.kind == TeamGame.Kind.TNT }
        assertTrue(tntRow >= 0)
        repeat(tntRow) { press { p2.down = it } }
        assertEquals(tntRow, game.craftCursorForTest())
        shot("two-player-craft")
        val tnt = game.tnt
        press { p2.jump = it }
        assertEquals(tnt + 2, game.tnt, "player 2 made TNT")
        // Things that are used right away in a level can't be made at home.
        val star = game.recipes(1).indexOfFirst { it.kind == TeamGame.Kind.STAR }
        if (star >= 0) {
            game.craft(star, 1)
            assertEquals(TeamGame.State.HOME, game.state)
        }
        // B closes it.
        press { p2.action = it }
        assertFalse(game.homeCraft)
        // Player 1 can use the same table (A or B where Rocco stands).
        game.setHomeXForTest(Homes.CRAFT_X + 12f)
        game.setLugoHomeXForTest(Homes.CRAFT_X + 60f)
        press { input.action = it }
        assertTrue(game.homeCraft)
        press { input.action = it }
        assertFalse(game.homeCraft)
        // LUGO walks with his own controller.
        val lx = game.lugoHomeXForTest()
        run(0.4f) { p2.left = true }
        p2.left = false
        assertTrue(game.lugoHomeXForTest() < lx - 20f)
        // And opens the SHOP at the counter.
        game.setLugoHomeXForTest(Homes.SHOP_X + 30f)
        press { p2.jump = it }
        assertTrue(game.homeShop)
        press { p2.right = it }
        press { p2.action = it }
        assertFalse(game.homeShop)
    }

    @Test fun singlePlayerHasNoLugo() {
        startPlaying()
        run(1f) { input.right = true }
        input.right = false
        assertFalse(game.p2Joined)
        assertEquals(2, game.team.size)
        assertFalse(game.lugoIn)
        // The touch screen and the first controller never join anybody.
        input.action = true; input.pause = true; run(0.1f); input.action = false; input.pause = false
        run(0.1f)
        assertFalse(game.p2Joined)
    }

    @Test fun screenshots() {
        // In a level: LUGO a little ahead of Rocco.
        startPlaying(1)
        game.hurtProof()
        join()
        run(1f)
        run(0.35f) { p2.right = true }
        p2.right = false
        run(0.6f)
        game.noInvulnForTest()
        shot("two-player-level")
        // A jump, side by side.
        p2.jump = true; input.jump = true
        run(0.25f)
        game.noInvulnForTest()
        shot("two-player-jump")
        p2.jump = false; input.jump = false
        run(1f)
        // A fire flower: Fire LUGO is white and green.
        game.powerForTest(Power.FIRE)
        run(0.1f)
        game.noInvulnForTest()
        shot("two-player-fire")
        game.powerForTest(Power.NONE)
        // At home: LUGO next to the crafting table.
        game.enterHome()
        run(0.5f)
        game.setLugoHomeXForTest(Homes.CRAFT_X + 12f)
        run(0.1f)
        shot("two-player-house")
        // On the map: side by side.
        game.goToMap(0)
        run(1f)
        shot("two-player-map")
        // A big close-up of the two brothers.
        startPlaying(0)
        game.hurtProof()
        game.foes.clear()
        run(1.2f)
        game.lugo.x = game.leader.x + 20f
        game.lugo.facing = -1
        run(0.3f)
        game.noInvulnForTest()
        val img = BufferedImage((game.viewW * 8).toInt(), (240 * 8), BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img, 8f))
        val cx = ((game.leader.cx + 10f - game.camX) * 8).toInt()
        val cy = (game.leader.cy * 8).toInt()
        ImageIO.write(img.getSubimage((cx - 240).coerceIn(0, img.width - 480), (cy - 200).coerceIn(0, img.height - 320), 480, 320), "png", File(dir, "two-player-closeup.png"))
    }
}
