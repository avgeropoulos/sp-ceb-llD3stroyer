package com.herculesapollo.walk3d

import com.herculesapollo.walk.Kind
import com.herculesapollo.walk.PauseMenu
import com.herculesapollo.walk.SaveSlots
import com.roccoquest.game.Gfx
import com.roccoquest.game.shadowText
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * On-screen controls: a floating thumbstick on the left half of the screen (it centers
 * wherever your thumb lands), and JUMP plus the dog's special move on the right.
 */
object Controls3D {
    class Button(val label: String, var cx: Float, var cy: Float, val r: Float)

    val jump = Button("JUMP", 0f, 196f, 28f)
    val action = Button("ACTION", 0f, 214f, 21f)
    val pause = Button("II", 0f, 14f, 10f)
    val music = Button("M", 0f, 14f, 10f)
    const val STICK_R = 30f

    /** Where the stick is drawn when nobody's touching it. */
    var restX = 64f
    var restY = 190f
    var stickX = restX
    var stickY = restY
    var knobX = restX
    var knobY = restY
    private var stickId = -1

    fun layout(viewW: Float) {
        jump.cx = viewW - 40f
        action.cx = viewW - 104f
        pause.cx = viewW - 16f
        music.cx = viewW - 42f
    }

    /** Maps the held touch points onto [input]. [ids] identify fingers across calls. */
    fun apply(input: Input3D, ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int, viewW: Float) {
        layout(viewW)
        var j = false
        var a = false
        var p = false
        var m = false
        var stickFound = false
        for (i in 0 until count) {
            val x = xs[i]
            val y = ys[i]
            if (ids[i] == stickId) {
                stickFound = true
                knobX = x; knobY = y
                continue
            }
            if (hypot(x - pause.cx, y - pause.cy) < pause.r * 1.3f) { p = true; continue }
            if (hypot(x - music.cx, y - music.cy) < music.r * 1.3f) { m = true; continue }
            if (x < viewW * 0.45f && y > 50f && stickId == -1) {
                // A new thumb on the left: the stick centers right there.
                stickId = ids[i]
                stickFound = true
                stickX = x; stickY = y; knobX = x; knobY = y
                continue
            }
            if (x >= viewW / 2) {
                val dj = hypot(x - jump.cx, y - jump.cy) / jump.r
                val da = hypot(x - action.cx, y - action.cy) / action.r
                if (dj <= da) j = true else a = true
            }
        }
        if (!stickFound) {
            stickId = -1
            stickX = restX; stickY = restY; knobX = restX; knobY = restY
        }
        var dx = (knobX - stickX) / STICK_R
        var dy = (knobY - stickY) / STICK_R
        val l = hypot(dx, dy)
        if (l > 1f) { dx /= l; dy /= l }
        if (l < 0.15f) { dx = 0f; dy = 0f }
        input.stickX = dx
        input.stickY = -dy
        input.jump = j
        input.action = a
        input.pause = p
        input.musicToggle = m
    }

    fun draw(gfx: Gfx, input: Input3D, viewW: Float, actionLabel: String, ring: Int, ready: Boolean) {
        layout(viewW)
        val c = 0xDDFFFFFF.toInt()
        gfx.oval(stickX - STICK_R - 2, stickY - STICK_R - 2, STICK_R * 2 + 4, STICK_R * 2 + 4, 0x55000000)
        gfx.oval(stickX - STICK_R, stickY - STICK_R, STICK_R * 2, STICK_R * 2, 0x33FFFFFF)
        var kx = knobX - stickX
        var ky = knobY - stickY
        val kl = hypot(kx, ky)
        if (kl > STICK_R) { kx *= STICK_R / kl; ky *= STICK_R / kl }
        gfx.oval(stickX + kx - 13, stickY + ky - 13, 26f, 26f, 0x99FFFFFF.toInt())
        button(gfx, jump, input.jump, 0)
        button(gfx, action, input.action, if (ready) ring else 0xFF666666.toInt())
        gfx.text("JUMP", jump.cx, jump.cy + 3, 8f, c, 1)
        gfx.text(actionLabel, action.cx, action.cy + 3, 7f, if (ready) c else 0x88FFFFFF.toInt(), 1)
    }

    // ---------------------------------------------------------------- Menus
    /** The title screen's save-slot tabs, and the dog buttons below them. */
    const val SLOT_TOP = 160f
    const val SLOT_H = 16f
    const val SLOT_W = 64f
    const val CARD_TOP = 196f

    fun slotX(i: Int, viewW: Float) = viewW / 2 - SaveSlots.COUNT * SLOT_W / 2 + i * SLOT_W

    fun slotAt(x: Float, viewW: Float): Int? {
        val i = kotlin.math.floor((x - slotX(0, viewW)) / SLOT_W).toInt()
        return if (i in 0 until SaveSlots.COUNT) i + 1 else null
    }

    fun drawPause(gfx: Gfx, viewW: Float, musicOn: Boolean) {
        layout(viewW)
        val c = 0xDDFFFFFF.toInt()
        gfx.oval(pause.cx - pause.r, pause.cy - pause.r, pause.r * 2, pause.r * 2, 0x66000000)
        gfx.rect(pause.cx - 4, pause.cy - 5, 3f, 10f, c)
        gfx.rect(pause.cx + 1, pause.cy - 5, 3f, 10f, c)
        val x = music.cx
        val y = music.cy
        gfx.oval(x - music.r, y - music.r, music.r * 2, music.r * 2, 0x66000000)
        gfx.oval(x - 5, y + 1, 5f, 4f, c)
        gfx.oval(x + 1, y, 5f, 4f, c)
        gfx.rect(x - 1, y - 6, 1.5f, 9f, c)
        gfx.rect(x + 5, y - 7, 1.5f, 9f, c)
        gfx.poly(floatArrayOf(x - 1, x + 6.5f, x + 6.5f, x - 1), floatArrayOf(y - 6, y - 7, y - 5, y - 4), c)
        if (!musicOn) {
            gfx.poly(floatArrayOf(x - 7, x - 5.5f, x + 7, x + 5.5f), floatArrayOf(y - 6, y - 7.5f, y + 6, y + 7.5f), 0xFFE53A1E.toInt())
        }
    }

    private fun button(gfx: Gfx, b: Button, pressed: Boolean, ring: Int) {
        val fill = if (pressed) 0x88FFFFFF.toInt() else 0x44FFFFFF
        gfx.oval(b.cx - b.r - 2, b.cy - b.r - 2, b.r * 2 + 4, b.r * 2 + 4, if (ring != 0) (0xAA000000.toInt() or (ring and 0xFFFFFF)) else 0x55000000)
        gfx.oval(b.cx - b.r, b.cy - b.r, b.r * 2, b.r * 2, fill)
    }
}

/** The 2D overlay on top of the 3D view: score, compass, labels, menus. */
object Hud3D {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val YELLOW = 0xFFFFE14D.toInt()
    private val pt = FloatArray(2)

    fun render(g: Game3D, gfx: Gfx) {
        val w = g.viewW
        val f = g.frame
        if (g.state != Game3D.State.TITLE) {
            // Labels floating over the world.
            if (f.project(g.level.homeX, 3.1f, g.level.homeZ - 0.6f, w, VIEW_H, pt) && (g.goalReached || g.state != Game3D.State.PLAYING)) {
                gfx.shadowText("HOME", pt[0], pt[1], 9f, YELLOW, 1)
            }
            for (c in g.critters) {
                if (c.frozenT > 0f && c.catchable && f.project(c.x, c.y + 0.7f, c.z, w, VIEW_H, pt)) {
                    gfx.shadowText("!?", pt[0], pt[1], 8f, YELLOW, 1)
                }
                if (c.kind == Kind.CAT && c.state == State3.FLEE && sin(g.clock * 8f) > 0f && f.project(c.x, c.y + 0.7f, c.z, w, VIEW_H, pt)) {
                    gfx.shadowText("HSSS!", pt[0], pt[1], 6f, WHITE, 1)
                }
                if (c.state == State3.CHASED && c.t < 0.5f && f.project(c.x, c.y + 0.8f, c.z, w, VIEW_H, pt)) {
                    gfx.shadowText("!", pt[0], pt[1], 10f, YELLOW, 1)
                }
            }
            for (p in g.popups) {
                if (f.project(p.x, p.y + p.t * 0.8f, p.z, w, VIEW_H, pt)) gfx.shadowText(p.text, pt[0], pt[1], p.size, p.color, 1)
            }
        }
        when (g.state) {
            Game3D.State.TITLE -> title(g, gfx)
            Game3D.State.INTRO -> {
                hud(g, gfx)
                gfx.rect(0f, 70f, w, 56f, 0x99000000.toInt())
                gfx.shadowText("LEVEL ${g.levelIndex + 1}", w / 2, 90f, 10f, YELLOW, 1)
                gfx.shadowText(g.level.def.title, w / 2, 110f, 16f, WHITE, 1)
                gfx.shadowText("Chase ${g.level.target} critters, then head home!", w / 2, 122f, 7f, WHITE, 1)
            }
            Game3D.State.PLAYING -> {
                hud(g, gfx)
                compass(g, gfx)
                g.tip?.let { banner(gfx, w, it, 72f) }
                val ring = if (g.dog.herc) Models.HERC_COLLAR else Models.APOLLO_COLLAR
                Controls3D.draw(gfx, g.input, w, g.dog.actionLabel, ring, g.dog.cooldown <= 0f)
                if (g.paused) paused(gfx, w)
            }
            Game3D.State.HOME -> {
                hud(g, gfx)
                if (g.stateTime > 0.5f) gfx.shadowText("HOME SWEET HOME!", w / 2, 80f, 14f, YELLOW, 1)
            }
            Game3D.State.RESULTS -> results(g, gfx)
            Game3D.State.OUCH -> {
                hud(g, gfx)
                panel(gfx, w, 70f, 90f)
                gfx.shadowText("OOF! TOO MANY BUMPS", w / 2, 96f, 12f, 0xFFFF6B6B.toInt(), 1)
                gfx.shadowText("Jump out of the way of the bikes!", w / 2, 116f, 7f, WHITE, 1)
                if (g.stateTime > 1.5f) gfx.shadowText("tap to try this level again", w / 2, 142f, 8f, YELLOW, 1)
            }
            Game3D.State.ALL_DONE -> {
                panel(gfx, w, 40f, 112f)
                gfx.shadowText("ALL LEVELS DONE!", w / 2, 64f, 14f, YELLOW, 1)
                gfx.shadowText("${g.dog.name} is tired and happy.", w / 2, 82f, 8f, WHITE, 1)
                gfx.shadowText("GOOD DOG!", w / 2, 102f, 12f, WHITE, 1)
                gfx.shadowText("FINAL SCORE ${g.score}", w / 2, 122f, 10f, YELLOW, 1)
                gfx.shadowText("BEST ${g.best}", w / 2, 138f, 7f, WHITE, 1)
                if (g.stateTime > 3f) gfx.shadowText("tap to go back to the start", w / 2, 232f, 7f, WHITE, 1)
            }
        }
        Controls3D.drawPause(gfx, w, g.musicOn)
    }

    private fun hud(g: Game3D, gfx: Gfx) {
        gfx.shadowText("${g.levelIndex + 1}: ${g.level.def.title}", 8f, 14f, 8f, WHITE)
        gfx.shadowText("SCORE ${g.score.toString().padStart(6, '0')}", 8f, 26f, 8f, YELLOW)
        for (i in 0 until 3) heart(gfx, 12f + i * 11f, 34f, i < g.hearts)
        gfx.shadowText("CHASED ${g.chased}/${g.level.target}", 46f, 38f, 7f, if (g.goalReached) 0xFF7DF9FF.toInt() else WHITE)
        val secs = g.levelTime.toInt()
        gfx.shadowText("%d:%02d".format(secs / 60, secs % 60), g.viewW - 60f, 14f, 8f, WHITE, 2)
    }

    private fun heart(gfx: Gfx, cx: Float, cy: Float, full: Boolean) {
        val c = if (full) 0xFFE52521.toInt() else 0x66000000
        gfx.oval(cx - 4f, cy - 3f, 4.5f, 4.5f, c)
        gfx.oval(cx - 0.5f, cy - 3f, 4.5f, 4.5f, c)
        gfx.poly(floatArrayOf(cx - 4f, cx + 4f, cx), floatArrayOf(cy - 0.5f, cy - 0.5f, cy + 4.5f), c)
    }

    /** An arrow at the top of the screen pointing at the nearest critter (or home). */
    private fun compass(g: Game3D, gfx: Gfx) {
        val t = g.compassTarget() ?: return
        val dx = t[0] - g.dog.x
        val dz = t[1] - g.dog.z
        if (hypot(dx, dz) < 2f) return
        val fwd = dx * sin(g.camYaw) + dz * cos(g.camYaw)
        val right = -dx * cos(g.camYaw) + dz * sin(g.camYaw)
        val a = atan2(right, fwd)
        val cx = g.viewW / 2
        val cy = 22f
        gfx.oval(cx - 13f, cy - 13f, 26f, 26f, 0x66000000)
        val tipX = cx + sin(a) * 10f; val tipY = cy - cos(a) * 10f
        val lx = cx + sin(a + 2.5f) * 8f; val ly = cy - cos(a + 2.5f) * 8f
        val rx = cx + sin(a - 2.5f) * 8f; val ry = cy - cos(a - 2.5f) * 8f
        gfx.poly(floatArrayOf(tipX, lx, cx, rx), floatArrayOf(tipY, ly, cy, ry), if (g.goalReached) YELLOW else WHITE)
        gfx.text(if (g.goalReached) "HOME" else "CRITTER", cx, cy + 20f, 5.5f, 0xCCFFFFFF.toInt(), 1)
    }

    private fun title(g: Game3D, gfx: Gfx) {
        val w = g.viewW
        gfx.rect(0f, 30f, w, 72f, 0x77000000)
        gfx.shadowText("HERCULES & APOLLO", w / 2, 60f, 22f, WHITE, 1)
        gfx.shadowText("3D", w / 2, 86f, 18f, YELLOW, 1)
        // Save slot tabs.
        for (i in 0 until SaveSlots.COUNT) {
            val x = Controls3D.slotX(i, w)
            val on = g.slots.current == i + 1
            gfx.rect(x + 2f, Controls3D.SLOT_TOP, Controls3D.SLOT_W - 4f, Controls3D.SLOT_H, if (on) 0xFFFFD21F.toInt() else 0xAA000000.toInt())
            gfx.text("SLOT ${i + 1}", x + Controls3D.SLOT_W / 2, Controls3D.SLOT_TOP + 11f, 7.5f, if (on) 0xFF1D1D1D.toInt() else WHITE, 1)
        }
        val pulse = (g.stateTime * 2f).toInt() % 2 == 0
        val top = Controls3D.CARD_TOP
        for ((i, herc) in listOf(true, false).withIndex()) {
            val cx = w / 4 + i * w / 2
            val bw = min(150f, w / 2 - 24f)
            val color = if (herc) Models.HERC_COLLAR else Models.APOLLO_COLLAR
            gfx.rect(cx - bw / 2 - 1.5f, top - 1.5f, bw + 3f, 37f, if (pulse) WHITE else 0xAAFFFFFF.toInt())
            gfx.rect(cx - bw / 2, top, bw, 34f, 0xDD000000.toInt() or (color and 0xFFFFFF))
            gfx.shadowText(if (herc) "BE HERCULES" else "BE APOLLO", cx, top + 12f, 9f, WHITE, 1)
            gfx.text(if (herc) "Super fast + DASH" else "Big jumps + BARK", cx, top + 22f, 6f, 0xDDFFFFFF.toInt(), 1)
            val mode = if (herc) "herc" else "apollo"
            val progress = if (g.slots.started(mode)) "LEVEL ${g.savedLevel(herc) + 1}  SCORE ${g.slots.score(mode)}" else "NEW GAME"
            gfx.text(progress, cx, top + 31f, 5.5f, 0xDDFFFFFF.toInt(), 1)
        }
        if (g.best > 0) gfx.shadowText("BEST ${g.best}", w / 2, 112f, 7f, WHITE, 1)
    }

    private fun paused(gfx: Gfx, w: Float) =
        PauseMenu.draw(gfx, w, VIEW_H, canSwitch = true, note = "Your game is saved at the start of every level.")

    private fun banner(gfx: Gfx, w: Float, text: String, y: Float) {
        val bw = text.length * 5.2f + 16f
        gfx.rect(w / 2 - bw / 2, y - 11f, bw, 16f, 0xAA000000.toInt())
        gfx.text(text, w / 2, y, 8f, WHITE, 1)
    }

    private fun panel(gfx: Gfx, viewW: Float, top: Float, height: Float) {
        val w = min(300f, viewW - 32f)
        gfx.rect(viewW / 2 - w / 2 - 2f, top - 2f, w + 4f, height + 4f, 0xFF1D3557.toInt())
        gfx.rect(viewW / 2 - w / 2, top, w, height, 0xEE2A4A7A.toInt())
    }

    private fun results(g: Game3D, gfx: Gfx) {
        hud(g, gfx)
        val w = g.viewW
        val cx = w / 2
        panel(gfx, w, 46f, 156f)
        gfx.shadowText("LEVEL ${g.levelIndex + 1} DONE!", cx, 66f, 12f, YELLOW, 1)
        gfx.shadowText(g.level.def.title, cx, 80f, 8f, WHITE, 1)
        val shown = min(3, (g.stateTime / 0.35f).toInt())
        for (i in 0 until 3) star(gfx, cx - 30f + i * 30f, 100f, 10f, if (i < g.stars && i < shown) YELLOW else 0x55FFFFFF)
        val secs = g.levelTime.toInt()
        gfx.shadowText("Time %d:%02d   (par %d:%02d)".format(secs / 60, secs % 60, g.level.def.par.toInt() / 60, g.level.def.par.toInt() % 60), cx, 124f, 8f, WHITE, 1)
        gfx.shadowText("Critters chased: ${g.chased} / ${g.level.total}", cx, 138f, 8f, WHITE, 1)
        gfx.shadowText("Treats: ${g.treatsGot}    Bonus: +${g.bonus}", cx, 152f, 8f, WHITE, 1)
        gfx.shadowText("SCORE ${g.score}", cx, 172f, 10f, YELLOW, 1)
        if (g.stateTime > 1.5f) {
            gfx.shadowText(if (g.levelIndex == Levels.all.lastIndex) "tap to finish" else "tap for the next level", cx, 192f, 7f, WHITE, 1)
        }
    }

    private fun star(gfx: Gfx, cx: Float, cy: Float, r: Float, color: Int) {
        val xs = FloatArray(10)
        val ys = FloatArray(10)
        for (i in 0 until 10) {
            val a = -Math.PI / 2 + i * Math.PI / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            xs[i] = cx + (cos(a) * rr).toFloat()
            ys[i] = cy + (sin(a) * rr).toFloat()
        }
        gfx.poly(xs, ys, color)
    }
}
