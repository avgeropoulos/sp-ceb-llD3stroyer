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

    /** 2 PLAYERS on, and player 2 presses a button. */
    private fun join() {
        game.setPlayers(true, byPad = true)
        press { p2.action = it }
        assertTrue(game.p2Joined)
    }

    private fun shot(name: String, scale: Float = 3f) {
        val img = BufferedImage((game.viewW * scale).toInt(), (240 * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img, scale))
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    @Test fun onePlayerEveryControllerIsPlayerOne() {
        val slots = PadSlots()
        // A PlayStation controller on a tablet: the gamepad, its touchpad and motion sensor, the volume keys,
        // the on-screen keyboard, and a second controller lying on the sofa. In 1 PLAYER they're all player 1.
        for (id in listOf(7, 8, 9, 0, -1, 12)) assertEquals(0, slots.playerFor(id, "ds4", gamepad = id == 7))
        assertEquals(0, slots.playerFor(12, "other"))
        assertEquals(PadSlots.NONE, slots.deviceOf(1))
        assertEquals(-1, slots.removed(12), "nobody's place to free")
        assertFalse(slots.takeLost())
    }

    @Test fun twoPlayersFirstControllerIsPlayerOneAndASecondOneIsPlayerTwo() {
        val slots = PadSlots()
        val gen = slots.generation
        // Player 1 picks 2 PLAYERS with controller 7 (it was the last one to press a button).
        slots.playerFor(7, "pad-one")
        slots.setTwoPlayers(true, byPad = true)
        assertTrue(slots.generation != gen, "the Android side lets go of held directions")
        assertEquals(7, slots.deviceOf(0))
        assertEquals(0, slots.playerFor(7, "pad-one"))
        // Its other parts (a touchpad or motion sensor with the same descriptor, even a "gamepad" one) stay player 1.
        assertEquals(0, slots.playerFor(8, "pad-one", gamepad = true, button = true))
        assertEquals(0, slots.playerFor(9, "pad-one", gamepad = false))
        // Volume keys, keyboards, touchpads of other things: never player 2.
        assertEquals(0, slots.playerFor(0, "gpio-keys", gamepad = false, button = true))
        assertEquals(0, slots.playerFor(-1, "", gamepad = false, button = true))
        assertEquals(PadSlots.NONE, slots.deviceOf(1))
        // A second controller's stick resting a bit off centre moves nobody, and doesn't make it player 2...
        assertEquals(PadSlots.IGNORE, slots.playerFor(12, "pad-two", button = false))
        assertEquals(PadSlots.NONE, slots.deviceOf(1))
        // ...a button does.
        assertEquals(1, slots.playerFor(12, "pad-two", button = true))
        assertEquals(1, slots.playerFor(12, "pad-two", button = false))
        assertEquals(1, slots.playerFor(13, "pad-two", gamepad = false), "player 2's touchpad is player 2's")
        assertEquals(0, slots.playerFor(30, "pad-three"), "a third one helps player 1 and takes nobody's place")
        assertEquals(12, slots.deviceOf(1))
        // Player 2's controller goes away: the place is free, and the game hears about it once.
        assertEquals(1, slots.removed(12))
        assertTrue(slots.takeLost())
        assertFalse(slots.takeLost())
        assertEquals(-1, slots.removed(99))
        assertEquals(1, slots.playerFor(40, "pad-four"), "a new controller takes the free place")
        // Player 1's goes away: the next new one is player 1, player 2 stays player 2.
        assertEquals(0, slots.removed(7))
        assertFalse(slots.takeLost())
        assertEquals(0, slots.playerFor(41, "pad-five", button = false))
        assertEquals(1, slots.playerFor(40, "pad-four"))
        // Back to 1 PLAYER: everybody is player 1 again.
        slots.setTwoPlayers(false, byPad = true)
        assertEquals(0, slots.playerFor(40, "pad-four"))
        assertEquals(PadSlots.NONE, slots.deviceOf(0))
    }

    @Test fun twoPlayersPickedByTouchMakesTheFirstControllerPlayerTwo() {
        val slots = PadSlots()
        slots.setTwoPlayers(true, byPad = false)
        assertEquals(PadSlots.TOUCH, slots.deviceOf(0), "player 1 plays on the screen")
        assertEquals(PadSlots.IGNORE, slots.playerFor(5, "pad", button = false))
        assertEquals(1, slots.playerFor(5, "pad"))
        assertEquals(0, slots.playerFor(6, "pad-b"), "another controller helps player 1")
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

    @Test fun twoPlayersWaitsForAButtonAndSelectOrThePauseMenuSwitchItOff() {
        startPlaying()
        assertEquals(2, game.team.size, "just player 1's team")
        // 1 PLAYER: player 2's buttons do nothing at all.
        press { p2.action = it }; press { p2.pause = it }
        assertFalse(game.p2Joined)
        // The pause screen turns 2 PLAYERS on (its top button)...
        press { input.pause = it }
        assertTrue(game.paused)
        press { input.up = it }
        press { input.jump = it }
        assertTrue(game.twoPlayers)
        assertFalse(game.p2Joined, "LUGO waits for player 2's controller")
        assertEquals(2, game.team.size)
        press { input.pause = it }
        assertFalse(game.paused)
        assertTrue(game.p2Waiting)
        // A stick nudge alone doesn't join (a controller lying on the sofa).
        p2.right = true; run(0.2f); p2.right = false; run(0.1f)
        assertFalse(game.p2Joined)
        press { p2.action = it }
        assertTrue(game.p2Joined)
        assertEquals("PLAYER 2: LUGO JOINED!", game.p2NoteForTest())
        assertTrue(game.lugo in game.team)
        assertFalse(game.lugo in game.followers, "player 2 controls him: he's no follower")
        assertTrue(abs(game.lugo.cx - game.leader.cx) < 20f, "he pops in next to player 1")
        // The button that joined doesn't also do something (like a jump).
        run(0.3f)
        assertTrue(game.lugo.onGround)
        // Holding SELECT sends him home: 1 PLAYER again.
        p2.craft = true; run(TeamGame.LEAVE_HOLD + 0.1f); p2.craft = false
        assertFalse(game.p2Joined)
        assertFalse(game.twoPlayers)
        assertEquals(2, game.team.size)
        press { p2.jump = it }; press { p2.pause = it }
        assertFalse(game.p2Joined, "no coming back by himself")
        // The pause menu's 2 PLAYERS: OFF works with either controller (up from the first button goes to it).
        join()
        press { input.pause = it }
        assertTrue(game.paused)
        press { p2.up = it }
        press { p2.jump = it }
        assertFalse(game.p2Joined)
        assertFalse(game.twoPlayers)
        press { input.pause = it }
        // Player 2's controller unplugged: LUGO goes home, 2 PLAYERS stays on, and a button brings him back.
        game.padSlots.playerFor(5, "one")
        game.setPlayers(true, byPad = true)
        assertEquals(1, game.padSlots.playerFor(6, "two"))
        press { p2.jump = it }
        assertTrue(game.p2Joined && !game.paused)
        game.padSlots.removed(6)
        game.update(dt)
        assertFalse(game.p2Joined)
        assertTrue(game.twoPlayers)
        assertEquals("PLAYER 2'S CONTROLLER IS GONE", game.p2NoteForTest())
        press { p2.jump = it }
        assertTrue(game.p2Joined)
    }

    @Test fun pickOneOrTwoPlayersAfterTheTitle() {
        run(0.6f)
        input.tapX = 200f; input.tapY = 150f; input.tap = true
        game.update(dt)
        assertEquals(TeamGame.State.PLAYERS, game.state)
        assertEquals(0, game.playersCursorForTest(), "1 PLAYER first")
        run(0.4f)
        // Tap 2 PLAYERS.
        input.tapX = game.playersCardXForTest(1) + 20f; input.tapY = 120f; input.tap = true
        game.update(dt)
        assertEquals(TeamGame.State.SLOTS, game.state)
        assertTrue(game.twoPlayers)
        assertEquals(PadSlots.TOUCH, game.padSlots.deviceOf(0), "picked with a finger: player 1 plays by touch")
        assertEquals(2, storage.load(TeamGame.PLAYERS_KEY))
        // B goes back to the choice, which remembers 2 PLAYERS; the controller picks 1 PLAYER.
        run(0.4f)
        game.padConnected = true
        pad { input.action = it }
        assertEquals(TeamGame.State.PLAYERS, game.state)
        assertEquals(1, game.playersCursorForTest())
        run(0.4f)
        pad { input.left = it }
        assertEquals(0, game.playersCursorForTest())
        pad { input.jump = it }
        assertEquals(TeamGame.State.SLOTS, game.state)
        assertFalse(game.twoPlayers)
        assertEquals(1, storage.load(TeamGame.PLAYERS_KEY))
    }

    /** A controller press (no finger on the screen): a tap at no particular spot, like the Android app sends. */
    private fun pad(set: (Boolean) -> Unit) {
        input.touching = false
        set(true); input.tapX = -1f; input.tapY = -1f; input.tap = true
        repeat(2) { game.update(dt) }
        set(false)
        repeat(2) { game.update(dt) }
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
        // A tablet with a PlayStation controller (gamepad, touchpad, motion sensor), its volume keys and a
        // second controller: buttons from all of them. Everything is player 1, LUGO never comes.
        for (id in listOf(3, 4, 5, 0, -1, 9, 10)) {
            val who = game.padSlots.playerFor(id, if (id in 3..5) "ds4" else "dev$id", gamepad = id == 3 || id >= 9, button = true)
            assertEquals(0, who, "device $id is player 1")
        }
        // (Even if something did reach player 2's buttons, 1 PLAYER never lets LUGO in.)
        press { p2.action = it }; press { p2.pause = it }; press { p2.jump = it }
        run(0.5f)
        assertFalse(game.p2Joined)
        assertFalse(game.lugoIn)
        assertEquals(2, game.team.size)
        assertFalse(game.paused, "player 2's START did nothing")
        // No crafting table at home either (that's for two players).
        game.enterHome()
        run(0.3f)
        game.setHomeXForTest(Homes.CRAFT_X + 12f)
        assertTrue(game.homeTargetForTest() != "CRAFT")
    }

    @Test fun choiceScreenAndWaitingScreenshots() {
        // The 1 PLAYER / 2 PLAYERS screen on narrow, usual and wide screens.
        for (w in listOf(320f, 427f, 540f)) {
            val img = BufferedImage((w * 3).toInt(), 720, BufferedImage.TYPE_INT_ARGB)
            // (Tap the title, then look at the choice.)
            val inp = TeamInput()
            val h = TeamGame(inp, SoundSink {}, Storage.Memory().apply { save(TeamGame.PLAYERS_KEY, if (w > 500f) 2 else 1) })
            h.viewW = w
            h.update(0.6f)
            inp.tapX = 100f; inp.tapY = 150f; inp.tap = true
            h.update(dt)
            assertEquals(TeamGame.State.PLAYERS, h.state)
            repeat(20) { h.update(dt) }
            h.render(AwtGfx(img, 3f))
            ImageIO.write(img, "png", File(dir, "players-menu-${w.toInt()}.png"))
            for (i in 0..1) {
                assertTrue(h.playersCardXForTest(i) >= 4f && h.playersCardXForTest(i) + minOf(150f, (w - 36f) / 2f) <= w - 4f, "card $i fits at $w")
            }
        }
        // 2 PLAYERS on, player 2 hasn't pressed a button yet.
        startPlaying(1)
        game.hurtProof()
        game.setPlayers(true, byPad = true)
        run(3f)
        game.noInvulnForTest()
        assertFalse(game.lugoIn)
        shot("two-player-waiting-level")
        game.padConnected = true
        press { input.pause = it }
        shot("two-player-pause")
        press { input.pause = it }
        game.goToMap(0)
        run(1f)
        shot("two-player-waiting-map")
        // A bookshelf and a fireplace at their usual spots: the crafting table moves out of their way.
        game.coins = 100000
        game.inventory.fill(200)
        game.buyFurniture(Furniture.BOOKSHELF)
        game.buyFurniture(Furniture.FIREPLACE)
        game.enterHome()
        run(0.3f)
        join()
        game.setLugoHomeXForTest(game.craftX() + 12f)
        run(0.3f)
        val cx = game.craftX()
        assertTrue(cx > Homes.CRAFT_X, "the table moved over")
        assertTrue(Furniture.entries.none { f ->
            game.hasFurniture(f) && game.furnFloor(f) == 0 && Homes.layer(f) == 0 &&
                cx + Homes.CRAFT_W > game.furnX(f) && cx < game.furnX(f) + Homes.width(f)
        }, "nothing stands on it")
        shot("two-player-house-furniture")
    }

    /**
     * Two players run through a sample of levels from every world (water, space, carts, cannons, bosses):
     * player 1 runs and jumps, player 2 mashes buttons. Nothing crashes, LUGO never gets lost outside the
     * level or off the screen, and the hearts are never below zero.
     */
    @Test fun twoPlayersSoakThroughEveryWorld() {
        val rnd = kotlin.random.Random(7)
        for (level in Levels.all.indices step 4) {
            startPlaying(level)
            join()
            var held = 0
            var offFrames = 0
            repeat((14f / dt).toInt()) { f ->
                game.hurtProof()
                input.right = true
                input.jump = f % 50 < 16
                if (f % 20 == 0) {
                    held = rnd.nextInt(6)
                    p2.right = held != 1
                    p2.left = held == 1
                }
                p2.jump = rnd.nextInt(5) == 0
                p2.action = rnd.nextInt(9) == 0
                p2.down = rnd.nextInt(40) == 0
                p2.longJump = rnd.nextInt(60) == 0
                game.update(dt)
                val g = game.lugo
                assertFalse(g.x.isNaN() || g.y.isNaN(), "level ${level + 1}: LUGO is somewhere")
                assertTrue(game.hearts >= 0)
                if (game.lugoIn && game.state == TeamGame.State.PLAY && !game.paused) {
                    assertTrue(g.x >= -1f && g.x + g.w <= game.level.w * TILE + 1f, "level ${level + 1}: LUGO in the level (x ${g.x})")
                    // (A pit respawn moves the team after the camera's turn, so it catches up a frame later, as
                    // in the one-player game.)
                    val l = game.leader
                    fun seen(h: Hero) = h.x + h.w >= game.camX - 2f && h.x <= game.camX + game.viewW + 2f
                    val near = abs(g.cx - l.cx) <= game.viewW - TeamGame.SPREAD_MARGIN + 2f
                    offFrames = if (seen(g) && seen(l) && near) 0 else offFrames + 1
                    assertTrue(offFrames < 2, "level ${level + 1} frame $f: both on screen (LUGO ${g.x}, player 1 ${l.x}, camera ${game.camX})")
                }
            }
            listOf<(Boolean) -> Unit>({ p2.right = it }, { p2.left = it }, { p2.jump = it }, { p2.action = it },
                { p2.down = it }, { p2.longJump = it }, { input.right = it }, { input.jump = it }).forEach { it(false) }
            game.setPlayers(false, byPad = true)
        }
    }

    /** Every boss with two players: LUGO stays inside the locked arena, and nothing crashes. */
    @Test fun twoPlayersFightEveryBoss() {
        val rnd = kotlin.random.Random(11)
        val bossLevels = Levels.all.indices.filter { i -> Levels.all[i].rows.any { r -> r.any { it in BOSS_CHARS } } }
        for (level in bossLevels) {
            startPlaying(level)
            join()
            val boss = game.foes.filter { it.boss }.minByOrNull { it.x } ?: continue
            val l = game.leader
            l.x = boss.x - 150f; l.y = 60f; l.vy = 0f
            game.lugo.x = l.x - 20f; game.lugo.y = 60f
            game.foes.removeAll { !it.boss }
            var wasLocked = false
            repeat((12f / dt).toInt()) { f ->
                game.hurtProof()
                game.fullHeartsForTest()
                input.right = f % 120 < 60
                input.left = !input.right
                input.action = f % 10 < 5
                input.jump = f % 40 < 12
                p2.right = rnd.nextBoolean(); p2.left = !p2.right && rnd.nextInt(3) == 0
                p2.jump = rnd.nextInt(4) == 0
                p2.action = rnd.nextInt(3) == 0
                game.update(dt)
                val g = game.lugo
                assertFalse(g.x.isNaN() || g.y.isNaN())
                val lock = game.camLockValueForTest()
                if (lock != null && game.lugoIn && game.state == TeamGame.State.PLAY) {
                    wasLocked = true
                    assertTrue(g.x >= lock - 2f && g.x + g.w <= lock + game.viewW + 2f, "level ${level + 1}: LUGO inside the arena (x ${g.x}, arena $lock)")
                }
            }
            listOf<(Boolean) -> Unit>({ p2.right = it }, { p2.left = it }, { p2.jump = it }, { p2.action = it },
                { input.right = it }, { input.left = it }, { input.jump = it }, { input.action = it }).forEach { it(false) }
            game.setPlayers(false, byPad = true)
        }
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
