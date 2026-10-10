package com.critters.game

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameTest {
    private fun press(g: Game, b: Btn, frames: Int = 2) {
        g.pad.key(b, true)
        repeat(frames) { g.update() }
        g.pad.key(b, false)
        repeat(2) { g.update() }
    }

    private fun run(g: Game, frames: Int) = repeat(frames) { g.update(); if (it % 7 == 0) g.render() }

    /** Mashes A until the overlays and scripts settle (or [limit] frames pass). */
    private fun mash(g: Game, limit: Int = 4000, until: () -> Boolean = { g.ui.isEmpty() && !g.scriptRunning }) {
        var n = 0
        while (n < limit && !until()) {
            press(g, Btn.A, 2)
            run(g, 6)
            n += 10
        }
    }

    @Test fun mapsAreConsistent() {
        for (m in World.maps.values) {
            assertTrue(m.rows.all { it.length == m.w }, "${m.id} rows are ragged: ${m.rows.map { it.length }}")
            for (row in m.rows) for (ch in row) Art.tile(ch, 0)
            for (w in m.warps) {
                assertTrue(w.x in 0 until m.w && w.y in 0 until m.h, "${m.id} warp out of bounds")
                assertTrue(m.at(w.x, w.y) in Art.WARPS, "${m.id} warp at ${w.x},${w.y} is on '${m.at(w.x, w.y)}'")
                if (w.map != "BACK") {
                    val t = World[w.map]
                    assertTrue(t.at(w.tx, w.ty) !in Art.SOLID, "${m.id} -> ${w.map} lands on solid '${t.at(w.tx, w.ty)}'")
                }
            }
            for (n in m.npcs) {
                assertTrue(n.x in 0 until m.w && n.y in 0 until m.h, "${m.id}/${n.id} out of bounds")
                val ch = m.at(n.x, n.y)
                assertTrue(ch !in Art.SOLID || n.look == Art.Look.BALL, "${m.id}/${n.id} stands on '$ch'")
            }
            // Edge links must line up with open tiles on both sides.
            for (d in Dir.entries) {
                val link = m.link(d) ?: continue
                val t = World[link.map]
                val back = t.link(d.opposite)
                assertNotNull(back, "${t.id} should link back to ${m.id}")
                assertEquals(m.id, back.map)
                assertEquals(-link.offset, back.offset, "${m.id} <-> ${t.id} offsets")
                val edge = when (d) {
                    Dir.UP -> (0 until m.w).map { it to 0 }
                    Dir.DOWN -> (0 until m.w).map { it to m.h - 1 }
                    Dir.LEFT -> (0 until m.h).map { 0 to it }
                    Dir.RIGHT -> (0 until m.h).map { m.w - 1 to it }
                }
                val open = edge.filter { (x, y) -> m.at(x, y) !in Art.SOLID }
                assertTrue(open.isNotEmpty(), "${m.id} has no opening to the ${d.name}")
                for ((x, y) in open) {
                    val (tx, ty) = when (d) {
                        Dir.UP -> x + link.offset to t.h - 1
                        Dir.DOWN -> x + link.offset to 0
                        Dir.LEFT -> t.w - 1 to y + link.offset
                        Dir.RIGHT -> 0 to y + link.offset
                    }
                    assertTrue(t.at(tx, ty) !in Art.SOLID, "${m.id} exit at $x,$y lands on '${t.at(tx, ty)}' in ${t.id}")
                }
            }
        }
    }

    @Test fun songsRender() {
        for (t in Tune.entries) {
            if (t == Tune.NONE) continue
            val pcm = Tunes.render(t)
            assertNotNull(pcm)
            assertTrue(pcm.size > Synth.RATE * 4, "$t is too short")
        }
        for (s in Sfx.entries) assertTrue(Synth.sfx(s).isNotEmpty())
        for (sp in Dex.all) assertTrue(Synth.cry(sp.id).isNotEmpty())
    }

    @Test fun speciesAreSane() {
        assertEquals(22, Dex.all.size)
        Dex.all.forEachIndexed { i, sp ->
            assertEquals(i + 1, sp.id)
            assertTrue(sp.movesAt(sp.learn.minOf { it.first }).isNotEmpty(), "${sp.name} starts with moves")
            if (sp.evolveTo != 0) assertTrue(sp.evolveLevel > 1)
        }
        assertEquals(2f, Type.mult(Type.WATER, Type.FIRE))
        assertEquals(0.5f, Type.mult(Type.FIRE, Type.WATER))
        assertEquals(0f, Type.mult(Type.NORMAL, Type.GHOST))
    }

    @Test fun battleRulesWork() {
        val rng = Random(42)
        val mine = Critter(Dex[3], 12, rng)
        val party = mutableListOf(mine)
        val foe = Critter(Dex[1], 8, rng)
        val b = Battle(party, listOf(foe), null, Bag(), rng)
        b.start()
        var turns = 0
        while (b.result == null && turns < 30) {
            b.turn(Action.Fight(mine.moves.indexOfFirst { it.move == Move.WATER_GUN }.coerceAtLeast(0)))
            turns++
        }
        assertEquals(Battle.Result.WIN, b.result)
        assertTrue(mine.xp > Critter.xpFor(12), "gained XP")
    }

    @Test fun catchingAndLevelling() {
        val rng = Random(7)
        val party = mutableListOf(Critter(Dex[5], 10, rng))
        val bag = Bag().apply { add(Item.SUPER_CAPSULE, 50) }
        var caught = 0
        repeat(10) {
            val b = Battle(party, listOf(Critter(Dex[7], 3, rng)), null, bag, rng)
            b.start()
            while (b.result == null) b.turn(Action.Throw(Item.SUPER_CAPSULE))
            if (b.result == Battle.Result.CAUGHT) caught++
            party[0].heal()
        }
        assertTrue(caught >= 7, "PIPWING is easy to catch ($caught/10)")

        val c = Critter(Dex[1], 15, rng)
        c.xp = Critter.xpFor(16)
        val learned = c.levelUp()
        assertEquals(16, c.level)
        assertTrue(c.canEvolve())
        c.evolve()
        assertEquals("BLAZOR", c.name)
        assertTrue(learned.isEmpty() || learned.all { it in Move.entries })
    }

    @Test fun saveAndLoad() {
        val store = SaveStore.Memory()
        val g = Game(store = store, rng = Random(1))
        g.debugStart("route1", 9, 20)
        g.flags += "badge1"
        g.money = 1234
        g.bag.add(Item.SUPER_POTION, 3)
        g.save()
        val data = store.data!!
        val g2 = Game(store = store, rng = Random(2))
        run(g2, 5)
        press(g2, Btn.START)
        run(g2, 5)
        press(g2, Btn.A) // CONTINUE
        run(g2, 60)
        assertEquals(Game.Scene.WORLD, g2.scene)
        assertEquals("route1", g2.map.id)
        assertEquals(1234, g2.money)
        assertEquals(3, g2.bag.count(Item.SUPER_POTION))
        assertEquals(1, g2.badges)
        assertEquals(g.party[0].encode(), g2.party[0].encode())
        assertTrue(data.contains("party="))
    }

    @Test fun newGameToStarterAndRival() {
        val g = Game(rng = Random(3))
        run(g, 10)
        press(g, Btn.START)
        // Intro dialog, then the name menu.
        mash(g, 3000) { g.ui.any { it is Menu } }
        press(g, Btn.A)
        mash(g, 3000) { g.scene == Game.Scene.WORLD && g.ui.isEmpty() }
        run(g, 40)
        assertEquals("home", g.map.id)
        // Walk down onto the mat and out the door.
        walk(g, Btn.LEFT, 1)
        walk(g, Btn.DOWN, 2)
        run(g, 60)
        assertEquals("sprout", g.map.id, "left the house at ${g.px},${g.py}")
        Shots.screen("sprout", g.screen.also { g.render() })
        // Go to the lab door at (6, 11).
        walk(g, Btn.DOWN, 1)
        walk(g, Btn.RIGHT, 5)
        walk(g, Btn.DOWN, 6)
        walk(g, Btn.LEFT, 3)
        walk(g, Btn.UP, 1)
        run(g, 60)
        assertEquals("lab", g.map.id, "at ${g.map.id} ${g.px},${g.py}")
        // Walk up to the table and pick the first capsule (EMBIT at x = 4).
        walk(g, Btn.UP, 8)
        assertEquals(4, g.px)
        assertEquals(3, g.py)
        press(g, Btn.A)
        run(g, 20)
        g.render(); Shots.screen("lab_ask", g.screen)
        mash(g, 20000) { g.scene == Game.Scene.BATTLE }
        assertEquals(Game.Scene.BATTLE, g.scene)
        assertEquals(1, g.party.size)
        assertEquals("EMBIT", g.party[0].name)
        run(g, 120)
        g.render(); Shots.screen("battle", g.screen)
        // Fight with the first move until the battle ends.
        var guard = 0
        while (g.scene == Game.Scene.BATTLE && guard < 400) {
            press(g, Btn.A, 2)
            run(g, 8)
            guard++
        }
        mash(g, 20000)
        assertTrue("rival_left_lab" in g.flags, "rival left the lab")
        assertTrue(g.party[0].hp == g.party[0].maxHp, "healed after the rival battle")
    }

    private fun walk(g: Game, b: Btn, tiles: Int) {
        repeat(tiles) {
            g.pad.key(b, true)
            var n = 0
            val sx = g.px; val sy = g.py
            while ((g.px == sx && g.py == sy) && n < 40) { g.update(); n++ }
            g.pad.key(b, false)
            while (g.moving && n < 80) { g.update(); n++ }
            run(g, 2)
        }
    }

    @Test fun wildBattleInTallGrass() {
        val g = Game(rng = Random(11))
        g.debugStart("route1", 4, 19, starter = 3, level = 12)
        var steps = 0
        while (g.scene != Game.Scene.BATTLE && steps < 200) {
            walk(g, if (steps % 2 == 0) Btn.RIGHT else Btn.LEFT, 1)
            run(g, 60)
            steps++
        }
        assertEquals(Game.Scene.BATTLE, g.scene)
        run(g, 150)
        g.render(); Shots.screen("wild", g.screen)
        // Open the move list for a screenshot, then fight.
        press(g, Btn.A); run(g, 4)
        g.render(); Shots.screen("moves", g.screen)
        var guard = 0
        while (g.scene == Game.Scene.BATTLE && guard < 600) {
            press(g, Btn.A, 2)
            run(g, 8)
            guard++
        }
        mash(g, 6000)
        assertEquals(Game.Scene.WORLD, g.scene)
    }

    @Test fun screenshots() {
        val g = Game(rng = Random(5))
        run(g, 200)
        g.render(); Shots.screen("title", g.screen)
        g.debugStart("maple", 10, 12, starter = 5, level = 14)
        g.party += Critter(Dex[11], 12)
        g.party += Critter(Dex[17], 11)
        run(g, 30)
        g.render(); Shots.screen("maple", g.screen)
        press(g, Btn.START); run(g, 5)
        g.render(); Shots.screen("startmenu", g.screen)
        press(g, Btn.DOWN); press(g, Btn.A); run(g, 5)
        g.render(); Shots.screen("party", g.screen)
        press(g, Btn.B); press(g, Btn.B); run(g, 5)
        for ((id, x, y) in listOf(Triple("route2", 20, 6), Triple("moss", 5, 5), Triple("tide", 10, 8), Triple("cave", 9, 18), Triple("gym1", 4, 10), Triple("center", 4, 4), Triple("lab", 4, 5), Triple("peak", 5, 9))) {
            g.debugStart(id, x, y)
            g.flags += "badge3"
            g.refreshNpcs()
            run(g, 10)
            g.render(); Shots.screen("map_$id", g.screen)
        }
        // The whole console, drawn the way the phone draws it: the title, a town and a battle.
        fun console(name: String) {
            val img = java.awt.image.BufferedImage(176 * 2, 340 * 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            g.draw(AwtDraw(img, 2f), 176f, 340f)
            javax.imageio.ImageIO.write(img, "png", java.io.File(Shots.dir, "$name.png"))
        }
        console("console_peak")
        g.debugStart("sprout", 9, 7, starter = 1, level = 9)
        run(g, 140)
        console("console_town")
        g.debugBattle(11, 7)
        run(g, 200)
        console("console_battle")
        // Stitch the three together for the README.
        val parts = listOf("console_town", "console_battle").map { javax.imageio.ImageIO.read(java.io.File(Shots.dir, "$it.png")) }
        val title = Game(rng = Random(5)).also { t -> run(t, 200) }
        val ti = java.awt.image.BufferedImage(176 * 2, 340 * 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        title.draw(AwtDraw(ti, 2f), 176f, 340f)
        val all = listOf(ti) + parts
        val out = java.awt.image.BufferedImage(all.sumOf { it.width } + 16 * (all.size - 1), all[0].height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val og = out.createGraphics()
        var x = 0
        for (im in all) { og.drawImage(im, x, 0, null); x += im.width + 16 }
        javax.imageio.ImageIO.write(out, "png", java.io.File(Shots.dir, "readme.png"))
    }
}
