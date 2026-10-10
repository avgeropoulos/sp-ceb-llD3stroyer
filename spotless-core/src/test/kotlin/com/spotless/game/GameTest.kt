package com.spotless.game

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameTest {
    private class MemStore : Storage {
        val map = HashMap<String, Int>()
        override fun load(key: String) = map[key] ?: 0
        override fun save(key: String, value: Int) { map[key] = value }
    }

    private class Sounds : SoundSink {
        val played = ArrayList<Sound>()
        override fun play(s: Sound) { played.add(s) }
    }

    private fun newGame(store: MemStore = MemStore()) = Game(Sounds(), store)

    private fun tapButton(g: Game, x: Float, y: Float) {
        g.render(AwtPainter(BufferedImage(72, 128, BufferedImage.TYPE_INT_ARGB)))
        g.touch(TOUCH_DOWN, x, y)
        g.touch(TOUCH_UP, x, y)
    }

    private fun toolIndex(t: Tool) = Tool.entries.indexOf(t)

    /** Cleans the whole room the way a player would: right tool, then scrub or tap. */
    private fun cleanEverything(g: Game) {
        repeat(120) { g.update(1f / 60f) } // "Ready? Go!"
        for (m in g.messes) {
            if (m.cleaned) continue
            tapButton(g, 16f + toolIndex(m.type.tool) * 174f + 80f, SCENE_BOTTOM + 100f)
            assertEquals(m.type.tool, g.tool)
            if (m.type.tool == Tool.HAND) {
                g.touch(TOUCH_DOWN, m.x, m.y)
                g.touch(TOUCH_UP, m.x, m.y)
            } else {
                g.touch(TOUCH_DOWN, m.x - 20f, m.y)
                var k = 0
                while (!m.cleaned && k < 200) {
                    g.touch(TOUCH_MOVE, m.x + (if (k % 2 == 0) 20f else -20f), m.y + (k % 3) * 4f)
                    k++
                }
                g.touch(TOUCH_UP, m.x, m.y)
            }
            assertTrue(m.cleaned, "${m.type} should be cleaned")
            g.update(1f / 60f)
        }
    }

    @Test fun messesStayInsideTheRoom() {
        for (day in 0 until 30) for (kind in RoomKind.entries) {
            val ms = Rooms.makeMess(kind, day, Random(day.toLong()))
            assertTrue(ms.size >= 6, "enough mess on day $day")
            assertTrue(ms.map { it.type.tool }.toSet().size >= 2, "at least two tools needed")
            for (m in ms) {
                assertTrue(m.x in 0f..VW && m.y in SCENE_TOP..SCENE_BOTTOM, "$m inside scene")
            }
            for (i in ms.indices) for (j in i + 1 until ms.size) {
                assertTrue(hypot(ms[i].x - ms[j].x, ms[i].y - ms[j].y) > 10f)
            }
        }
    }

    @Test fun cleaningARoomEarnsHeartsAndCoins() {
        val store = MemStore()
        val g = newGame(store)
        g.touch(TOUCH_DOWN, 360f, 600f); g.touch(TOUCH_UP, 360f, 600f) // title -> home
        assertEquals(Screen.HOME, g.screen)
        g.startRoom()
        cleanEverything(g)
        assertTrue(g.finished)
        assertEquals(3, g.stars)
        repeat(180) { g.update(1f / 60f) }
        assertEquals(Screen.RESULT, g.screen)
        tapButton(g, 360f, 260f + 660f - 70f) // "See Bella"
        assertEquals(Screen.REACT, g.screen)
        assertEquals(3, g.hearts)
        assertTrue(g.coins > 0)
        assertEquals(1, g.day)
        assertEquals(1, g.level, "3 hearts makes her friendly")
        // Saved.
        assertEquals(3, store.load("hearts"))
        // Levelling up shows her changing into a new outfit.
        repeat(60) { g.update(1f / 60f) }
        tapButton(g, 360f, 1210f)
        assertEquals(Screen.CHANGE, g.screen)
        repeat(260) { g.update(1f / 60f) }
        assertEquals(Screen.REVEAL, g.screen)
        tapButton(g, 360f, 1210f)
        assertEquals(Screen.HOME, g.screen)
        assertEquals(1, store.load("seen"))
        // Reload from the save.
        val again = newGame(store)
        assertEquals(3, again.hearts)
        assertEquals(1, again.day)
    }

    @Test fun runningOutOfTimeGivesNoHearts() {
        val g = newGame()
        g.startRoom()
        repeat(60 * 200) { g.update(1f / 60f) }
        assertTrue(g.finished)
        assertEquals(0, g.stars)
    }

    @Test fun wrongToolDoesNotClean() {
        val g = newGame()
        g.startRoom()
        repeat(120) { g.update(1f / 60f) }
        val m = g.messes.first { it.type.tool != Tool.HAND }
        val wrong = Tool.entries.first { it != m.type.tool && it != Tool.HAND }
        tapButton(g, 16f + toolIndex(wrong) * 174f + 80f, SCENE_BOTTOM + 100f)
        g.touch(TOUCH_DOWN, m.x, m.y)
        repeat(100) { g.touch(TOUCH_MOVE, m.x + (if (it % 2 == 0) 25f else -25f), m.y) }
        g.touch(TOUCH_UP, m.x, m.y)
        assertEquals(1f, m.dirt)
    }

    @Test fun levelsAreOrdered() {
        val hs = Story.levels.map { it.hearts }
        assertEquals(hs.sorted(), hs)
        assertEquals(0, Story.levelFor(0))
        assertEquals(Story.maxLevel, Story.levelFor(999))
    }

    @Test fun soundsRender() {
        for (s in Sound.entries) assertTrue(Synth.render(s).isNotEmpty())
    }

    /** Renders each screen to build/screens for eyeballing. */
    @Test fun screenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String, g: Game) {
            val img = BufferedImage(540, 960, BufferedImage.TYPE_INT_ARGB)
            g.render(AwtPainter(img))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        val g = newGame()
        repeat(30) { g.update(1f / 60f) }
        shot("1_title", g)
        g.debugScreen(Screen.HOME) { debugSetHearts(9, 340); debugSay(Story.levels[2].chat[0]) }
        shot("2_home", g)
        for ((i, kind) in RoomKind.entries.withIndex()) {
            val r = newGame(MemStore().apply { map["day"] = i + 5 })
            r.startRoom()
            repeat(100) { r.update(1f / 60f) }
            if (i == 1) {
                // Show a half-scrubbed spill and the tool cursor.
                val m = r.messes.first { it.type.tool != Tool.HAND }
                m.dirt = 0.4f
                r.touch(TOUCH_DOWN, 360f, 800f)
            }
            shot("3_room_${kind.name.lowercase()}", r)
        }
        val res = newGame()
        res.startRoom()
        repeat(100) { res.update(1f / 60f) }
        res.debugFinish(dirtLeft = false)
        repeat(240) { res.update(1f / 60f) }
        shot("4_result", res)
        g.debugScreen(Screen.REACT) { debugSetHearts(20); debugSay("Come here, you! *hugs*") }
        shot("5_react", g)
        g.debugScreen(Screen.CHANGE)
        shot("6_change", g)
        g.debugScreen(Screen.REVEAL) { debugSetHearts(33) }
        shot("7_reveal", g)
        g.debugScreen(Screen.SHOP) { debugSetHearts(14, 260) }
        shot("8_shop", g)
        g.debugScreen(Screen.WARDROBE)
        shot("9_wardrobe", g)
    }
}
