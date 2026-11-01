package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Sprite
import com.roccoquest.game.Storage
import com.roccoquest.game.TILE
import com.roccoquest.game.VIEW_H
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Dust puffs, squash and stretch, the circle wipe and the other little touches (Juice.kt). */
class JuiceTest {
    private val dt = 1f / 60f
    private val input = TeamInput()
    // (The one-time File 1 welcome-back gift is already given, so it doesn't change the tests.)
    private val storage = Storage.Memory().apply { save("gift1", 1); save("gift2", 1) }
    private val game = TeamGame(input, SoundSink {}, storage)
    private val dir = File("build/screens").apply { mkdirs() }

    private fun run(seconds: Float, each: () -> Unit = {}) = repeat((seconds / dt).toInt()) { each(); game.update(dt) }

    private fun startPlaying(level: Int) {
        game.newGame(0, Mon.SPARKY)
        if (level > 0) game.startLevel(level)
        run(3.2f)
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    private fun picture(scale: Float = 2f): BufferedImage {
        val img = BufferedImage((game.viewW * scale).toInt(), (VIEW_H * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img, scale))
        return img
    }

    private fun shot(name: String, scale: Float = 3f): BufferedImage = picture(scale).also { ImageIO.write(it, "png", File(dir, "$name.png")) }

    /** A close-up around ([cx], [cy]) (game pixels), so small things like dust can be seen. */
    private fun closeUp(name: String, cx: Float, cy: Float, w: Float = 120f, h: Float = 80f) {
        val scale = 6f
        val img = picture(scale)
        val x0 = ((cx - w / 2) * scale).toInt().coerceIn(0, img.width - (w * scale).toInt())
        val y0 = ((cy - h / 2) * scale).toInt().coerceIn(0, img.height - (h * scale).toInt())
        ImageIO.write(img.getSubimage(x0, y0, (w * scale).toInt(), (h * scale).toInt()), "png", File(dir, "$name.png"))
    }

    private fun isBlack(argb: Int) = argb == 0xFF000000.toInt()

    /** How much of the picture is the wipe's solid black. */
    private fun blackShare(img: BufferedImage): Float {
        var n = 0; var black = 0
        for (y in 0 until img.height step 3) for (x in 0 until img.width step 3) { n++; if (isBlack(img.getRGB(x, y))) black++ }
        return black.toFloat() / n
    }

    /** True when the picture around ([x], [y]) (game pixels) isn't all black: the hero shows through the hole. */
    private fun shows(img: BufferedImage, x: Float, y: Float, scale: Float = 2f): Boolean {
        for (dy in -3..3) for (dx in -3..3) {
            val px = (x * scale).toInt() + dx; val py = (y * scale).toInt() + dy
            if (px in 0 until img.width && py in 0 until img.height && !isBlack(img.getRGB(px, py))) return true
        }
        return false
    }

    // ---------------------------------------------------------------- the circle wipe

    /** Remembers what the wipe draws (corner lists are copied: the wipe reuses its own). */
    private class Shapes : Gfx {
        val rects = ArrayList<FloatArray>()
        val polys = ArrayList<Pair<FloatArray, FloatArray>>()
        val colors = ArrayList<Int>()
        override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) { rects += floatArrayOf(x, y, w, h); colors += color }
        override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) = error("the wipe draws no ovals")
        override fun poly(xs: FloatArray, ys: FloatArray, color: Int) { polys += xs.copyOf() to ys.copyOf(); colors += color }
        override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) = error("no sprites")
        override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) = error("no text")

        fun covers(px: Float, py: Float): Boolean {
            for (r in rects) if (px >= r[0] && px <= r[0] + r[2] && py >= r[1] && py <= r[1] + r[3]) return true
            for ((xs, ys) in polys) if (inside(xs, ys, px, py)) return true
            return false
        }

        private fun inside(xs: FloatArray, ys: FloatArray, px: Float, py: Float): Boolean {
            var c = false
            var j = xs.size - 1
            for (i in xs.indices) {
                if ((ys[i] > py) != (ys[j] > py) && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) c = !c
                j = i
            }
            return c
        }
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    /** Do segments a-b and c-d cross properly (not just touch at an end)? */
    private fun crosses(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay); val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy); val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 1e-3f && d2 < -1e-3f) || (d1 < -1e-3f && d2 > 1e-3f)) && ((d3 > 1e-3f && d4 < -1e-3f) || (d3 < -1e-3f && d4 > 1e-3f))
    }

    private fun checkPolygon(xs: FloatArray, ys: FloatArray, what: String) {
        assertEquals(xs.size, ys.size, what)
        assertTrue(xs.size >= 3, "$what: at least 3 corners")
        assertTrue(xs.all { it.isFinite() } && ys.all { it.isFinite() }, "$what: real numbers")
        var area = 0f
        for (i in xs.indices) { val j = (i + 1) % xs.size; area += xs[i] * ys[j] - xs[j] * ys[i] }
        assertTrue(abs(area) > 0.01f, "$what: not flat")
        // A simple outline: no two edges that aren't neighbours cross each other.
        val n = xs.size
        for (i in 0 until n) for (j in i + 2 until n) {
            if (i == 0 && j == n - 1) continue
            val i2 = (i + 1) % n; val j2 = (j + 1) % n
            assertTrue(!crosses(xs[i], ys[i], xs[i2], ys[i2], xs[j], ys[j], xs[j2], ys[j2]), "$what: edges $i and $j cross")
        }
    }

    @Test fun theWipeIsBlackOutsideTheCircleAndClearInsideAtEveryWidth() {
        val iris = Iris()
        val h = VIEW_H
        for (w in (320..540 step 20).map { it.toFloat() } + 427f) {
            val spots = listOf(w / 2 to h / 2, 16f to 16f, w - 16f to h - 16f, w * 0.3f to 200f, w - 16f to 16f, 40f to h / 2)
            for ((cx, cy) in spots) {
                val full = Iris.fullRadius(w, h, cx, cy)
                for (closing in listOf(true, false)) {
                    var last = if (closing) Float.MAX_VALUE else -1f
                    for (step in 0..20) {
                        val p = step / 20f
                        val r = Iris.radius(p, closing, full)
                        val what = "width $w, middle ($cx, $cy), ${if (closing) "closing" else "opening"} at $p (radius $r)"
                        assertTrue(r.isFinite() && r >= 0f, what)
                        // The circle only ever shrinks while closing and grows while opening.
                        if (closing) assertTrue(r <= last + 1e-3f, "$what: shrinks") else assertTrue(r >= last - 1e-3f, "$what: grows")
                        last = r
                        val g = Shapes()
                        iris.draw(g, w, h, cx, cy, r)
                        assertTrue(g.rects.size + g.polys.size <= 8, "$what: only a few shapes")
                        assertTrue(g.colors.all { it == 0xFF000000.toInt() }, "$what: solid black")
                        for (rc in g.rects) assertTrue(rc.all { it.isFinite() } && rc[2] > 0f && rc[3] > 0f, "$what: rectangle ${rc.toList()}")
                        for ((i, poly) in g.polys.withIndex()) checkPolygon(poly.first, poly.second, "$what, piece $i")
                        // Black outside the hole, nothing inside it (a hair's leeway on its edge).
                        var y = 0f
                        while (y <= h) {
                            var x = 0f
                            while (x <= w) {
                                val d = hypot(x - cx, y - cy)
                                if (d > r + 0.6f) assertTrue(g.covers(x, y), "$what: ($x, $y) should be black")
                                if (d < r - 1.6f) assertTrue(!g.covers(x, y), "$what: ($x, $y) should show the game")
                                x += 7f
                            }
                            y += 7f
                        }
                        // And right around the edge of the hole too.
                        for (k in 0 until 48) {
                            val a = k * Math.PI.toFloat() / 24f
                            val ox = kotlin.math.cos(a); val oy = kotlin.math.sin(a)
                            val px = cx + ox * (r + 1f); val py = cy + oy * (r + 1f)
                            if (r > 0.5f && px in 0f..w && py in 0f..h) assertTrue(g.covers(px, py), "$what: just outside the edge at $a")
                            val qx = cx + ox * (r - 2f); val qy = cy + oy * (r - 2f)
                            if (r > 3f && qx in 0f..w && qy in 0f..h) assertTrue(!g.covers(qx, qy), "$what: just inside the edge at $a")
                        }
                        if (closing && p == 1f) assertTrue(g.rects.size == 1 && g.polys.isEmpty(), "$what: shut is one black screen")
                        if (!closing && p == 1f || closing && p == 0f) assertTrue(g.rects.isEmpty() && g.polys.isEmpty(), "$what: wide open draws nothing")
                    }
                }
            }
        }
    }

    @Test fun aLevelOpensOnTheHeroAndClosesWhenItsBeatenAtEveryWidth() {
        for (w in listOf(320f, 427f, 540f)) {
            game.viewW = w
            game.newGame(0, Mon.SPARKY)
            run(2.9f)
            var n = 0
            while (game.state != TeamGame.State.PLAY && n++ < 30) game.update(dt)
            run(0.25f)
            // Halfway open: black all around, the hero in the middle of the hole.
            val l = game.leader
            var img = picture()
            assertTrue(blackShare(img) > 0.3f, "width $w: mostly black halfway through the opening (${blackShare(img)})")
            assertTrue(shows(img, l.cx - game.worldCam, l.cy), "width $w: the hero shows through the hole")
            if (w == 427f) ImageIO.write(picture(3f), "png", File(dir, "juice-wipe-opening.png"))
            if (w != 427f) ImageIO.write(picture(3f), "png", File(dir, "juice-wipe-opening-${w.toInt()}.png"))
            run(0.4f)
            img = picture()
            assertTrue(blackShare(img) < 0.05f, "width $w: wide open after half a second (${blackShare(img)})")

            // Beat the level: as LEVEL CLEAR! ends the circle closes on the hero.
            game.foes.clear()
            val goal = game.thingsForTest().filterIsInstance<Goal>().single()
            l.x = goal.x - 20f; l.y = goal.bottom - l.h - 2f; l.vy = 0f
            n = 0
            while (game.state != TeamGame.State.CLEAR && n++ < 300) { game.hurtProof(); input.right = true; game.update(dt) }
            input.right = false
            assertEquals(TeamGame.State.CLEAR, game.state)
            run(TeamGame.CLEAR_TIME - Iris.CLOSE * 0.3f)
            img = picture()
            assertEquals(TeamGame.State.CLEAR, game.state)
            assertTrue(blackShare(img) > 0.6f, "width $w: closing on the hero (${blackShare(img)})")
            assertTrue(shows(img, l.cx - game.worldCam, l.cy), "width $w: the hero is in the little circle")
            if (w == 427f) ImageIO.write(picture(3f), "png", File(dir, "juice-wipe-clear.png"))
            // Then the sticker screen opens from the middle.
            n = 0
            while (game.state != TeamGame.State.STICKER && n++ < 60) game.update(dt)
            assertTrue(blackShare(picture()) > 0.8f, "width $w: the sticker screen starts dark")
            run(0.25f)
            if (w == 427f) ImageIO.write(picture(3f), "png", File(dir, "juice-wipe-stickers.png"))
            run(0.4f)
            assertTrue(blackShare(picture()) < 0.05f, "width $w: then the stickers show")
        }
    }

    @Test fun pickingALevelClosesTheMapOnTheTeam() {
        game.newGame(0, Mon.SPARKY)
        game.goToMap(0)
        run(0.3f)
        assertTrue(blackShare(picture()) > 0.2f, "the map opens in a circle too")
        run(0.4f)
        assertTrue(blackShare(picture()) < 0.05f)
        game.playMapLevelForTest(0)
        assertEquals(TeamGame.State.INTRO, game.state)
        run(0.25f)
        val (mx, my) = game.mapSpot(0)
        val img = picture()
        assertTrue(blackShare(img) > 0.5f, "closing on the map")
        assertTrue(shows(img, mx, my - 24f), "on the team standing on the level")
        ImageIO.write(picture(3f), "png", File(dir, "juice-wipe-map.png"))
        // Then the title card, as long as it always was: the level starts at the same moment as before.
        run(0.35f)
        assertTrue(blackShare(picture()) < 0.05f, "then the title card (its own dark blue, not the wipe's black)")
        run(2.5f)
        assertEquals(TeamGame.State.PLAY, game.state)
    }

    @Test fun portalsCloseOnTheTeamAndOpenInTheBonusRoom() {
        startPlaying(1)
        game.foes.clear()
        val portal = game.thingsForTest().filterIsInstance<Portal>().single()
        val r = game.rocco
        r.x = portal.x - 30f; r.y = portal.bottom - r.h
        var t = 0f
        var closed = 0f
        while (!game.inBonus && t < 4f) {
            game.hurtProof(); input.right = true; game.update(dt); t += dt
            if (!game.inBonus) closed = max(closed, blackShare(picture(1f)))
        }
        input.right = false
        assertTrue(game.inBonus, "walked into the portal")
        assertTrue(closed > 0.8f, "the circle closed first ($closed)")
        game.update(dt)
        assertTrue(blackShare(picture()) > 0.6f, "and opens in the bonus room")
        run(0.6f)
        assertTrue(blackShare(picture()) < 0.05f)
    }

    // ---------------------------------------------------------------- squash and stretch

    @Test fun landingSquashesAndSpringsBackToNormal() {
        startPlaying(0)
        game.foes.clear()
        run(0.6f) { game.hurtProof() }
        for (h in game.team) {
            h.y -= 90f; h.vy = 0f; h.onGround = false
            val w = h.w; val hh = h.h
            var n = 0
            while (!h.onGround && n++ < 120) { game.hurtProof(); game.update(dt); assertEquals(w, h.w); assertEquals(hh, h.h) }
            assertTrue(h.onGround, "${h.label} landed")
            assertTrue(h.stretch < -0.1f, "${h.label} squashes on landing: ${h.stretch}")
            assertTrue(Squash.widthFor(h.stretch) > 1.08f, "and gets wider")
            assertTrue(game.puffs.live >= 2, "dust puffs out")
            var lowest = h.stretch
            run(0.7f) { game.hurtProof(); lowest = min(lowest, h.stretch); assertEquals(w, h.w); assertEquals(hh, h.h) }
            assertTrue(abs(h.stretch) < 0.01f, "${h.label} is back to normal: ${h.stretch}")
            assertEquals(0f, h.drawStretch(game), "drawn at normal size again")
            assertTrue(lowest >= -Squash.MAX_SQUASH, "never squashed too much")
        }
        // Jumping up stretches Rocco a little (never more than the most).
        val r = game.rocco
        var most = 0f
        input.jump = true
        run(0.25f) { game.hurtProof(); most = max(most, r.stretch) }
        input.jump = false
        assertTrue(most > 0.06f && most <= Squash.MAX_STRETCH, "stretched going up: $most")
        run(1.5f) { game.hurtProof() }
        assertTrue(abs(r.stretch) < 0.01f)
    }

    @Test fun stretchingKeepsTheFeetOnTheGround() {
        val g = Shapes()
        val st = StretchGfx().aim(g, 1.2f, 0.8f, 100f, 200f)
        st.rect(94f, 176f, 12f, 24f, -1)
        val r = g.rects.single()
        assertEquals(200f, r[1] + r[3], 0.01f, "the feet stay put")
        assertEquals(100f, r[0] + r[2] / 2, 0.01f, "and in the middle")
        assertEquals(14.4f, r[2], 0.01f)
        assertEquals(19.2f, r[3], 0.01f)
        st.poly(floatArrayOf(100f, 110f, 90f), floatArrayOf(180f, 200f, 200f), -1)
        val (xs, ys) = g.polys.single()
        assertEquals(184f, ys[0], 0.01f)
        assertEquals(112f, xs[1], 0.01f)

    }

    // ---------------------------------------------------------------- dust

    @Test fun dustNeverPilesUpOnALongSprint() {
        startPlaying(0)
        game.foes.clear()
        var most = 0
        var mostParticles = 0
        var frame = 0
        input.run = true
        run(30f) {
            game.hurtProof()
            // Sprint along, jumping now and then, and turn around every few seconds (skids and wall bumps).
            val back = (frame / 150) % 3 == 2
            input.right = !back
            input.left = back
            input.jump = frame % 45 < 8
            frame++
            most = max(most, game.puffs.live)
            mostParticles = max(mostParticles, game.particleCountForTest)
        }
        input.run = false; input.right = false; input.left = false; input.jump = false
        assertTrue(most >= 4, "dust was kicked up: $most")
        assertTrue(most <= game.puffs.size, "never more puffs than the pool: $most")
        assertTrue(mostParticles <= TeamGame.MAX_PARTICLES, "particles stay under the cap: $mostParticles")
    }

    @Test fun noDustInSpaceAndBubblesUnderwater() {
        val orbit = Levels.all.indexOfFirst { it.place == Place.ORBIT }
        assertTrue(orbit >= 0)
        startPlaying(orbit)
        game.foes.clear()
        input.run = true
        var most = 0
        var frame = 0
        run(4f) { game.hurtProof(); input.right = true; input.jump = frame++ % 50 < 8; most = max(most, game.puffs.dustLive) }
        input.run = false; input.right = false; input.jump = false
        assertEquals(0, most, "nothing to kick up on a space walk")

        val sea = Levels.all.indexOfFirst { it.shoreX > 5 }
        startPlaying(sea)
        game.foes.clear()
        val h = game.leader
        assertTrue(game.inWater(h), "starts in the water")
        input.jump = true; game.update(dt); input.jump = false
        assertTrue(game.puffs.live >= 1, "a swim stroke lets out bubbles")
    }

    @Test fun questionBlocksHopWhenHit() {
        startPlaying(0)
        game.foes.clear()
        val lv = game.level
        var spot: Pair<Int, Int>? = null
        for (tx in 0 until lv.w) for (ty in 2 until 12) {
            if (spot == null && lv[tx, ty] == Tile.QCOIN && !Tile.solid(lv[tx, ty + 1]) && !Tile.solid(lv[tx, ty + 2])) spot = tx to ty
        }
        val (tx, ty) = spot ?: error("no ? block in level 1")
        val r = game.rocco
        r.x = tx * TILE + 2f; r.y = (ty + 1) * TILE + 1f; r.vy = -300f; r.onGround = false
        var lowest = 0f
        run(0.15f) { game.hurtProof(); lowest = min(lowest, game.hopForTest(tx, ty)) }
        assertEquals(Tile.USED, lv[tx, ty], "the block was hit")
        assertTrue(lowest < -3f && lowest >= -TeamGame.HOP_HEIGHT, "it hopped up: $lowest")
        run(0.3f) { game.hurtProof() }
        assertEquals(0f, game.hopForTest(tx, ty), "and came back down")
    }

    // ---------------------------------------------------------------- pictures to look at

    @Test fun juiceScreenshots() {
        startPlaying(0)
        game.foes.clear()
        run(1f) { game.hurtProof() }
        // A big landing: squashed, with dust puffing out.
        val r = game.rocco
        r.y -= 100f; r.vy = 0f; r.onGround = false
        game.partner.y -= 60f; game.partner.vy = 0f; game.partner.onGround = false
        var n = 0
        while (!r.onGround && n++ < 120) { game.hurtProof(); game.update(dt) }
        game.update(dt); game.update(dt)
        shot("juice-landing")
        closeUp("juice-landing-zoom", r.cx - game.worldCam, r.bottom - 20f)
        // Jumping up: stretched.
        run(0.6f) { game.hurtProof() }
        input.jump = true
        run(0.12f) { game.hurtProof() }
        closeUp("juice-jump-zoom", r.cx - game.worldCam, r.cy)
        run(1f) { game.hurtProof() }
        input.jump = false
        run(1f) { game.hurtProof() }
        // Sprinting (on a long flat stretch, cleared for the picture), then skidding into a turn.
        val lv = game.level
        val gy = (r.bottom / TILE).toInt()
        for (tx in (r.x / TILE).toInt() - 2..(r.x / TILE).toInt() + 30) {
            for (ty in 0 until gy) lv[tx, ty] = Tile.EMPTY
            lv[tx, gy] = Tile.GROUND
        }
        input.run = true; input.right = true
        run(1.2f) { game.hurtProof() }
        closeUp("juice-sprint-zoom", r.cx - game.worldCam, r.bottom - 20f, 160f, 80f)
        input.right = false; input.left = true
        run(0.08f) { game.hurtProof() }
        closeUp("juice-skid-zoom", r.cx - game.worldCam, r.bottom - 20f, 160f, 80f)
        input.left = false; input.run = false
        run(1f) { game.hurtProof() }
        // Blocky and the Pokemon squash too.
        input.swap = true; game.update(dt); input.swap = false
        run(0.5f) { game.hurtProof() }
        val b = game.blocky
        b.y -= 100f; b.vy = 0f; b.onGround = false
        game.partner.y -= 100f; game.partner.vy = 0f; game.partner.onGround = false
        n = 0
        while (!b.onGround && n++ < 120) { game.hurtProof(); game.update(dt) }
        game.update(dt)
        closeUp("juice-blocky-landing-zoom", b.cx - game.worldCam, b.bottom - 20f, 160f, 80f)
    }
}
