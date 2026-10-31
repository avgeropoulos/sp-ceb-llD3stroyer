package com.roccoquest.team

import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Storage
import com.roccoquest.game.TILE
import com.roccoquest.game.tileOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Little things in the levels: Wonder Flowers one after another, the Enderman teleport in a boss arena, and where Dennis stops. */
class LevelPlayTest {
    private val dt = 1f / 60f
    private val input = TeamInput()
    private val sounds = ArrayList<Sound>()
    // (The one-time File 1 welcome-back gift is already given, so it doesn't change the tests.)
    private val storage = Storage.Memory().apply { save("gift1", 1); save("gift2", 1) }
    private val game = TeamGame(input, SoundSink { sounds += it }, storage)

    private fun run(seconds: Float, each: () -> Unit = {}) = repeat((seconds / dt).toInt()) { each(); game.update(dt) }

    private fun startPlaying(level: Int) {
        game.newGame(0, Mon.SPARKY)
        if (level > 0) game.startLevel(level)
        run(3.2f)
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    @Test fun aSecondWonderFlowerFinishesTheFirstOne() {
        startPlaying(Wonder.WORLD13)
        game.foes.clear()
        val h = game.leader
        val foe = Grumbler(h.x + 70f, h.bottom, false).also { it.active = true }
        game.foes += foe
        game.spawnForTest(WonderFlower(h.x, h.bottom, WonderEffect.DANCE))
        run(0.2f) { game.hurtProof() }
        assertEquals(WonderEffect.DANCE, game.wonderEffect)
        assertTrue(foe.danceT > 0f, "the bad guy dances")
        run(2f) { game.hurtProof() }
        assertTrue(game.wonderT > 0f, "the dance party is still on")

        // The team touches a second flower while the first one's Wonder Time is still running.
        val coins0 = game.coins
        game.spawnForTest(WonderFlower(h.x, h.bottom, WonderEffect.FLOATY))
        run(0.2f) { game.hurtProof() }
        assertEquals(WonderEffect.FLOATY, game.wonderEffect, "the new surprise starts")
        assertTrue(game.wonder, "the sky stays Wonder")
        assertTrue(game.wonderT > WonderEffect.FLOATY.seconds - 0.5f, "with its own full time: ${game.wonderT}")
        // The first one finished properly: its bad guys stopped dancing and its Wonder Seed popped out.
        assertEquals(0f, foe.danceT, "the dancing stopped")
        assertTrue(game.thingsForTest().any { it is WonderSeed } || game.coins >= coins0 + 20, "the first flower's Wonder Seed")
        run(4f) { game.hurtProof() }
        assertTrue(game.wonderT > 0f, "the second Wonder Time is still going")
        assertTrue(game.coins >= coins0 + 20, "the first seed was collected: ${game.coins - coins0}")

        // Then the second one ends with a seed of its own: two flowers, two seeds.
        run(WonderEffect.FLOATY.seconds) { game.hurtProof() }
        assertEquals(0f, game.wonderT)
        assertEquals(null, game.wonderEffect)
        run(4f) { game.hurtProof() }
        assertTrue(game.thingsForTest().none { it is WonderSeed })
        assertTrue(game.coins >= coins0 + 40, "both seeds: ${game.coins - coins0}")
    }

    @Test fun endermanTeleportStaysInsideTheBossArena() {
        startPlaying(2)
        val jr = game.foes.filterIsInstance<KragJr>().single()
        val r = game.leader
        r.x = jr.homeX - 150f; r.y = 100f
        var t = 0f
        while (!jr.engaged && t < 5f) { game.hurtProof(); game.update(dt); t += dt }
        assertTrue(jr.engaged, "Krag Jr. noticed the team")
        jr.frozenT = 999f
        run(2f) { game.hurtProof() }
        val lock = (jr.homeX + 16f - game.viewW / 2).coerceIn(0f, game.level.w * TILE - game.viewW)
        assertEquals(lock, game.camX, 0.5f, "the camera holds the arena")

        // Rocco captures an Enderman...
        val e = Enderman(r.x, r.bottom).also { it.active = true }
        game.foes += e
        game.capture(e)
        assertEquals(CaptureKind.ENDERMAN, game.captureKind)
        val h = game.leader
        // ...and teleports ahead, right next to the arena's walls (both ways).
        for (dir in listOf(1, -1)) {
            val wall = if (dir > 0) lock + game.viewW - h.w else lock
            h.x = wall - dir * 50f
            h.facing = dir
            run(0.5f) { game.hurtProof(); h.facing = dir }
            sounds.clear()
            var furthest = 0f
            input.action = true
            game.update(dt)
            input.action = false
            // (80 px ahead would be 30 px outside the arena: he lands right by its wall instead.)
            assertEquals(wall, h.x, 0.5f, "teleported up to the wall")
            assertTrue(Sound.PORTAL in sounds)
            repeat(90) {
                game.hurtProof()
                assertTrue(h.x >= lock - 0.01f && h.x + h.w <= lock + game.viewW + 0.01f, "still in the arena: ${h.x}")
                furthest = maxOf(furthest, abs(game.camX - lock))
                game.update(dt)
            }
            assertEquals(CaptureKind.ENDERMAN, game.captureKind)
            assertTrue(furthest < 0.5f, "the camera never leaves the arena: $furthest px")
        }
    }

    @Test fun dennisStopsWhereYouCanSeeHim() {
        // A wide phone and a tablet (on the tablet the leader is closer to the right edge).
        for (view in listOf(427f, 320f)) {
            sounds.clear()
            game.viewW = view
            startPlaying(0)
            game.foes.clear()
            val l = game.leader
            // A flat stretch in the middle of the level, so nothing else gets in the way.
            val gy = tileOf(l.bottom + 2f)
            val x0 = game.level.w * TILE / 2f
            for (tx in tileOf(x0 - 700f)..tileOf(x0 + 400f)) {
                game.level[tx, gy] = Tile.GROUND
                for (ty in gy - 6 until gy) game.level[tx, ty] = Tile.EMPTY
            }
            game.thingsForTest().forEach { if (abs(it.cx - x0) < 800f) it.removed = true }
            l.x = x0; l.y = gy * TILE - l.h - 1f
            run(1f) { game.hurtProof() }
            // Walk left a while, then stop: the camera looks left, so the leader stands right of the middle.
            run(2.5f) { game.hurtProof(); input.left = true }
            input.left = false
            run(1.5f) { game.hurtProof() }
            assertTrue(l.onGround)
            assertTrue(l.cx - game.camX > view * 0.6f, "the leader is right of the middle: ${l.cx - game.camX} of $view")
            // A wall where he'd like to stop (110 px ahead). The old search went on past it, off the right edge.
            val from = l.cx + 110f
            for (tx in tileOf(from)..tileOf(from + 55f)) { game.level[tx, gy - 1] = Tile.STONE; game.level[tx, gy - 2] = Tile.STONE }
            game.dogForTest()
            var frames = 0
            while (Sound.BARK !in sounds && frames++ < 600) { game.hurtProof(); game.update(dt) }
            assertTrue(Sound.BARK in sounds, "woof!")
            val d = game.thingsForTest().filterIsInstance<Dennis>().single()
            // He's on the screen, with room for his arrow, out in the open with ground under his paws.
            assertTrue(d.x >= game.camX, "not off the left edge: ${d.x - game.camX}")
            assertTrue(d.x + d.w + 40f <= game.camX + view + 0.01f, "he and his arrow fit on the screen: ${d.x + d.w - game.camX} of $view")
            for (dx in listOf(2f, d.w / 2, d.w - 2)) assertTrue(!game.level.solidAt(d.x + dx, d.bottom - 2), "not in a wall")
            assertTrue(game.level.solidAt(d.cx, d.bottom + 2), "ground under him")
            assertTrue(d.x > l.cx, "ahead of the leader, pointing the way")
        }
    }
}
