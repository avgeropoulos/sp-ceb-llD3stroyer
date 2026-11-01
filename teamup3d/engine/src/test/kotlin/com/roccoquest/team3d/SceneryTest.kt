package com.roccoquest.team3d

import com.roccoquest.game.TILE
import com.roccoquest.team.Levels
import com.roccoquest.team.Place
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 3D scenery behind levels: always the same for a level, always behind it, and cheap enough for phones. */
class SceneryTest {
    private val dir = File("build/screens3d").apply { mkdirs() }

    @Test fun everyPlaceButTheSpaceStationHasScenery() {
        for (p in Place.entries) {
            if (p == Place.STATION) assertNull(Scenery.build(p, 1), "the station's painted windows are its view")
            else assertNotNull(Scenery.build(p, 1), "$p has 3D scenery").release()
        }
    }

    @Test fun aLevelLooksTheSameEveryTime() {
        for (i in listOf(0, 5, 43, 97, 127, 190, 252)) {
            val def = Levels.all[i]
            val a = Scenery.build(def.place, i)!!
            val b = Scenery.build(def.place, i)!!
            assertEquals(a.meshes.size, b.meshes.size)
            for (k in a.meshes.indices) assertContentEquals(a.meshes[k].data, b.meshes[k].data, "level $i mesh $k")
            // And the strips are laid out the same way along the level.
            val la = DrawList(); val lb = DrawList()
            for (cam in listOf(0f, 900f, 2500f)) {
                la.clear(); lb.clear()
                la.setCamera(0f, 427f, pitch = 0.22f); lb.setCamera(0f, 427f, pitch = 0.22f)
                a.draw(la, cam); b.draw(lb, cam)
                la.finish(); lb.finish()
                assertEquals(la.count, lb.count)
                for (k in 0 until la.count) {
                    assertTrue(la.cmd(k).mesh.data.contentEquals(lb.cmd(k).mesh.data))
                    assertContentEquals(la.cmd(k).model, lb.cmd(k).model)
                }
            }
            a.release(); b.release()
        }
        // Different levels in the same place get different scenery.
        val first = Scenery.build(Place.HILLS, 0)!!
        val other = Scenery.build(Place.HILLS, 11)!!
        assertTrue(!first.meshes[0].data.contentEquals(other.meshes[0].data))
    }

    /** Nothing may come in front of the level (its ground ends at [LevelDepth.BACK]), so no block or bad guy is ever hidden. */
    @Test fun sceneryStaysBehindTheLevel() {
        assertTrue(Scenery.FRONT_Z < LevelDepth.BACK - 20f)
        for (p in Place.entries) {
            val s = Scenery.build(p, 3) ?: continue
            for (m in s.meshes) {
                var i = 0
                while (i < m.data.size) {
                    assertTrue(m.data[i + 2] <= Scenery.FRONT_Z + 0.01f, "$p: a point at z = ${m.data[i + 2]}")
                    i += Mesh.STRIDE
                }
            }
            s.release()
        }
    }

    /**
     * The budget: a whole level's scenery stays under [Scenery.MAX_TRIANGLES] triangles, and on the widest
     * phone screen a frame never draws more than [Scenery.MAX_DRAWN] of them, wherever the level is scrolled.
     */
    @Test fun everyLevelStaysInsideTheBudget() {
        val report = StringBuilder()
        var worstAll = 0
        var worstDrawn = 0
        val tooBig = ArrayList<String>()
        val list = DrawList()
        for ((i, def) in Levels.all.withIndex()) {
            val s = Scenery.build(def.place, i) ?: continue
            if (s.triangles > Scenery.MAX_TRIANGLES) tooBig += "level $i (${def.place}) has ${s.triangles} triangles"
            var drawn = 0
            for (viewW in listOf(320f, 427f, 600f)) {
                var cam = 0f
                val end = def.w * TILE - viewW
                while (cam <= end) {
                    list.clear()
                    list.setCamera(0f, viewW, pitch = 0.22f)
                    s.draw(list, cam)
                    drawn = maxOf(drawn, s.drawn)
                    cam += 97f
                }
            }
            if (drawn > Scenery.MAX_DRAWN) tooBig += "level $i (${def.place}) draws $drawn triangles in one frame"
            worstAll = maxOf(worstAll, s.triangles)
            worstDrawn = maxOf(worstDrawn, drawn)
            report.append("%3d %-14s %6d triangles, at most %5d drawn\n".format(i, def.place, s.triangles, drawn))
            s.release()
        }
        report.append("worst: $worstAll triangles in a level, $worstDrawn in a frame\n")
        File(dir, "scenery-budget.txt").writeText(report.toString())
        assertTrue(tooBig.isEmpty(), tooBig.joinToString("\n"))
    }

    /** Building a level's scenery is quick (it happens on the first frame of a level). */
    @Test fun buildingIsQuick() {
        repeat(3) { Scenery.build(Place.HILLS, it)!!.release() }
        val t0 = System.nanoTime()
        for (p in Place.entries) Scenery.build(p, 7)?.release()
        val ms = (System.nanoTime() - t0) / 1e6 / Place.entries.size
        assertTrue(ms < 40.0, "one place's scenery took $ms ms to build")
    }
}
