package com.roccoquest.team

import com.roccoquest.game.Gfx
import kotlin.concurrent.Volatile
import kotlin.math.hypot

/** Held-button state written by the platform layer and read by [TeamGame]. */
class TeamInput {
    @Volatile var left = false
    @Volatile var right = false
    @Volatile var jump = false
    /** Mine (Blocky) or throw (Rocco). */
    @Volatile var action = false
    /** Swipe down: Blocky digs straight down. */
    @Volatile var down = false
    @Volatile var swap = false
    /** The Pokemon's SUPER move. */
    @Volatile var superMove = false
    /** Keyboard run button; on touch screens, double-tap a direction instead. */
    @Volatile var run = false
    /** Rocco's long jump. */
    @Volatile var longJump = false
    /** Throw a stick of TNT. */
    @Volatile var tnt = false
    /** Use a Black Flower. */
    @Volatile var blackFlower = false
    /** Call Poseidon (costs 50 coins). */
    @Volatile var poseidon = false
    @Volatile var craft = false
    @Volatile var pause = false
    @Volatile var musicToggle = false
    /** Set on any new touch or key press; menus use it. */
    @Volatile var tap = false
    @Volatile var tapX = -1f
    @Volatile var tapY = -1f
    /** A number key 1-9 pressed in a menu (10 for the 0 key), or 0 for none. */
    @Volatile var numberKey = 0
}

/** On-screen touch controls in virtual coordinates (the screen is 240 tall). */
object TeamControls {
    class Button(var cx: Float, var cy: Float, val r: Float)

    val left = Button(30f, 206f, 22f)
    val right = Button(84f, 206f, 22f)
    val jump = Button(0f, 194f, 26f)
    val action = Button(0f, 212f, 20f)
    val swap = Button(0f, 150f, 17f)
    val superB = Button(0f, 160f, 16f)
    val tntB = Button(0f, 210f, 14f)
    val longB = Button(0f, 166f, 15f)
    val poseidonB = Button(30f, 150f, 17f)
    val blackB = Button(76f, 150f, 16f)
    val pause = Button(0f, 14f, 10f)
    val music = Button(0f, 14f, 10f)
    /** The CRAFT button is a pill [CRAFT_W] wide. */
    val craft = Button(0f, 14f, 10f)
    const val CRAFT_W = 40f

    fun layout(viewW: Float) {
        jump.cx = viewW - 36f
        action.cx = viewW - 96f
        swap.cx = viewW - 70f
        superB.cx = viewW - 124f
        tntB.cx = viewW - 150f
        longB.cx = viewW - 176f
        pause.cx = viewW - 16f
        music.cx = viewW - 42f
        craft.cx = viewW - 82f
    }

    fun inCraft(x: Float, y: Float) = x > craft.cx - CRAFT_W / 2 - 3 && x < craft.cx + CRAFT_W / 2 + 3 && y < 30f

    private val startY = HashMap<Int, Float>()
    private const val SWIPE = 14f

    fun apply(input: TeamInput, ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int, viewW: Float) {
        layout(viewW)
        startY.keys.retainAll((0 until count).map { ids[it] }.toSet())
        var l = false
        var r = false
        var j = false
        var a = false
        var d = false
        var s = false
        var sup = false
        var tn = false
        var lj = false
        var po = false
        var bf = false
        var c = false
        var p = false
        var m = false
        for (i in 0 until count) {
            val x = xs[i]
            val y = ys[i]
            if (hypot(x - pause.cx, y - pause.cy) < pause.r * 1.3f) { p = true; continue }
            if (hypot(x - music.cx, y - music.cy) < music.r * 1.3f) { m = true; continue }
            if (inCraft(x, y)) { c = true; continue }
            if (hypot(x - poseidonB.cx, y - poseidonB.cy) < poseidonB.r * 1.2f) { po = true; continue }
            if (hypot(x - blackB.cx, y - blackB.cy) < blackB.r * 1.2f) { bf = true; continue }
            if (y < 110f) continue
            val swiped = y - startY.getOrPut(ids[i]) { y } > SWIPE
            if (swiped && x < viewW / 2) { d = true; continue }
            if (x < viewW / 2) {
                if (x < (left.cx + right.cx) / 2) l = true else r = true
            } else {
                val dj = hypot(x - jump.cx, y - jump.cy) / jump.r
                val da = hypot(x - action.cx, y - action.cy) / action.r
                val ds = hypot(x - swap.cx, y - swap.cy) / swap.r
                val dsu = hypot(x - superB.cx, y - superB.cy) / superB.r
                val dt = hypot(x - tntB.cx, y - tntB.cy) / tntB.r
                val dl = hypot(x - longB.cx, y - longB.cy) / longB.r
                when {
                    dl < 1.2f && dl <= dj && dl <= da && dl <= dt && dl <= dsu -> lj = true
                    dt < 1.2f && dt <= dj && dt <= da && dt <= dsu -> tn = true
                    dsu < 1.25f && dsu <= dj && dsu <= da && dsu <= ds -> sup = true
                    ds < 1.25f && ds <= dj && ds <= da -> s = true
                    dj <= da -> j = true
                    else -> {
                        a = true
                        // Swiping down on the action button digs down.
                        if (swiped) d = true
                    }
                }
            }
        }
        input.left = l
        input.right = r
        input.jump = j
        input.action = a
        input.down = d
        input.swap = s
        input.superMove = sup
        input.tnt = tn
        input.longJump = lj
        input.poseidon = po
        input.blackFlower = bf
        input.craft = c
        input.pause = p
        input.musicToggle = m
    }

    fun draw(
        gfx: Gfx, input: TeamInput, viewW: Float, actionLabel: String, swapLabel: String, swapColor: Int,
        superCharge: Float, superColor: Int, t: Float, tntCount: Int = 0, poseidonReady: Boolean = false,
        showLong: Boolean = false, sprinting: Boolean = false, blackFlowers: Int = 0,
    ) {
        layout(viewW)
        val c = 0xDDFFFFFF.toInt()
        button(gfx, left, input.left)
        button(gfx, right, input.right)
        button(gfx, jump, input.jump)
        button(gfx, action, input.action)
        gfx.poly(floatArrayOf(left.cx - 9, left.cx + 6, left.cx + 6), floatArrayOf(left.cy, left.cy - 9, left.cy + 9), c)
        gfx.poly(floatArrayOf(right.cx + 9, right.cx - 6, right.cx - 6), floatArrayOf(right.cy, right.cy - 9, right.cy + 9), c)
        gfx.text("JUMP", jump.cx, jump.cy + 3, 8f, c, 1)
        gfx.text(actionLabel, action.cx, action.cy + 3, 7f, c, 1)
        // SWAP shows who you'll switch to.
        val sf = if (input.swap) 0xCCFFFFFF.toInt() else (swapColor and 0x00FFFFFF) or 0x99000000.toInt()
        gfx.oval(swap.cx - swap.r - 1, swap.cy - swap.r - 1, swap.r * 2 + 2, swap.r * 2 + 2, 0x55000000)
        gfx.oval(swap.cx - swap.r, swap.cy - swap.r, swap.r * 2, swap.r * 2, sf)
        gfx.text("SWAP", swap.cx, swap.cy, 6f, c, 1)
        gfx.text(swapLabel, swap.cx, swap.cy + 8, 5f, c, 1)
        // SUPER fills up from the bottom while it recharges and pulses when it's ready.
        val ready = superCharge >= 1f
        val b = superB
        gfx.oval(b.cx - b.r - 1, b.cy - b.r - 1, b.r * 2 + 2, b.r * 2 + 2, 0x55000000)
        gfx.oval(b.cx - b.r, b.cy - b.r, b.r * 2, b.r * 2, 0x33FFFFFF)
        val fillH = b.r * 2 * superCharge.coerceIn(0f, 1f)
        gfx.rect(b.cx - b.r * 0.7f, b.cy + b.r - fillH, b.r * 1.4f, fillH, (superColor and 0x00FFFFFF) or 0x88000000.toInt())
        if (ready) {
            val pulse = 2f + kotlin.math.abs(kotlin.math.sin(t * 6)) * 3f
            gfx.oval(b.cx - b.r - pulse, b.cy - b.r - pulse, (b.r + pulse) * 2, (b.r + pulse) * 2, (superColor and 0x00FFFFFF) or 0x55000000)
            gfx.oval(b.cx - b.r, b.cy - b.r, b.r * 2, b.r * 2, if (input.superMove) 0xCCFFFFFF.toInt() else superColor)
        }
        gfx.text("SUPER", b.cx, b.cy + 2, 6f, c, 1)
        if (showLong) {
            val lb = longB
            gfx.oval(lb.cx - lb.r - 1, lb.cy - lb.r - 1, lb.r * 2 + 2, lb.r * 2 + 2, 0x55000000)
            gfx.oval(lb.cx - lb.r, lb.cy - lb.r, lb.r * 2, lb.r * 2, if (input.longJump) 0xCCFFFFFF.toInt() else 0x88E52521.toInt())
            gfx.text("LONG", lb.cx, lb.cy + 1, 6f, c, 1)
            gfx.text("JUMP", lb.cx, lb.cy + 8, 5f, c, 1)
        }
        if (sprinting) gfx.text("SPRINT!", (left.cx + right.cx) / 2, 178f, 8f, 0xFFFFD21F.toInt(), 1)
        if (tntCount > 0) {
            val tb = tntB
            gfx.oval(tb.cx - tb.r - 1, tb.cy - tb.r - 1, tb.r * 2 + 2, tb.r * 2 + 2, 0x55000000)
            gfx.oval(tb.cx - tb.r, tb.cy - tb.r, tb.r * 2, tb.r * 2, if (input.tnt) 0xCCFFFFFF.toInt() else 0x99E53A1E.toInt())
            gfx.text("TNT", tb.cx, tb.cy + 1, 6f, c, 1)
            gfx.text("x$tntCount", tb.cx, tb.cy + 8, 5f, c, 1)
        }
        if (blackFlowers > 0) {
            val bb = blackB
            gfx.oval(bb.cx - bb.r - 1, bb.cy - bb.r - 1, bb.r * 2 + 2, bb.r * 2 + 2, 0x55000000)
            gfx.oval(bb.cx - bb.r, bb.cy - bb.r, bb.r * 2, bb.r * 2, if (input.blackFlower) 0xCCFFFFFF.toInt() else 0xCC2A1A3A.toInt())
            TeamArt.blackFlower(gfx, bb.cx - 10, bb.cy - 11, 20f, t)
            gfx.text("x$blackFlowers", bb.cx + 10, bb.cy + 14, 5f, c, 1)
        }
        if (poseidonReady) {
            val pb = poseidonB
            val pulse = kotlin.math.abs(kotlin.math.sin(t * 4)) * 3f
            gfx.oval(pb.cx - pb.r - pulse, pb.cy - pb.r - pulse, (pb.r + pulse) * 2, (pb.r + pulse) * 2, 0x552E8AC8)
            gfx.oval(pb.cx - pb.r, pb.cy - pb.r, pb.r * 2, pb.r * 2, if (input.poseidon) 0xCCFFFFFF.toInt() else 0xCC2E8AC8.toInt())
            // Little trident
            val gold = 0xFFFFD21F.toInt()
            gfx.rect(pb.cx - 0.75f, pb.cy - 9, 1.5f, 13f, gold)
            gfx.rect(pb.cx - 5, pb.cy - 6, 10f, 1.5f, gold)
            gfx.rect(pb.cx - 5, pb.cy - 10, 1.5f, 4f, gold)
            gfx.rect(pb.cx + 3.5f, pb.cy - 10, 1.5f, 4f, gold)
            gfx.text("50", pb.cx, pb.cy + 11, 5f, c, 1)
        }
        gfx.text("2x tap = sprint  swipe \u2193 = squat/slam", 70f, 236f, 6f, if (input.down) c else 0x88FFFFFF.toInt(), 1)
    }

    fun drawTop(gfx: Gfx, viewW: Float, musicOn: Boolean, craftReady: Boolean, t: Float) {
        layout(viewW)
        val c = 0xDDFFFFFF.toInt()
        gfx.oval(pause.cx - pause.r, pause.cy - pause.r, pause.r * 2, pause.r * 2, 0x66000000)
        gfx.rect(pause.cx - 4, pause.cy - 5, 3f, 10f, c)
        gfx.rect(pause.cx + 1, pause.cy - 5, 3f, 10f, c)
        val x = music.cx
        val y = music.cy
        gfx.oval(x - music.r, y - music.r, music.r * 2, music.r * 2, 0x66000000)
        gfx.text("♪", x, y + 4, 11f, c, 1)
        if (!musicOn) {
            gfx.poly(floatArrayOf(x - 7, x - 5.5f, x + 7, x + 5.5f), floatArrayOf(y - 6, y - 7.5f, y + 6, y + 7.5f), 0xFFE53A1E.toInt())
        }
        // CRAFT pill glows when there's something new you can make.
        val glow = craftReady && (t * 3).toInt() % 2 == 0
        val left = craft.cx - CRAFT_W / 2
        gfx.rect(left, craft.cy - 9, CRAFT_W, 18f, if (glow) 0xEE2EB84A.toInt() else 0xCC7A4A20.toInt())
        gfx.rect(left + 1.5f, craft.cy - 7.5f, CRAFT_W - 3, 15f, if (glow) 0xFF3CE070.toInt() else 0xFFA87040.toInt())
        gfx.text("CRAFT", craft.cx, craft.cy + 3, 7f, 0xFFFFFFFF.toInt(), 1)
    }

    private fun button(gfx: Gfx, b: Button, pressed: Boolean) {
        val fill = if (pressed) 0x88FFFFFF.toInt() else 0x44FFFFFF
        gfx.oval(b.cx - b.r - 1, b.cy - b.r - 1, b.r * 2 + 2, b.r * 2 + 2, 0x55000000)
        gfx.oval(b.cx - b.r, b.cy - b.r, b.r * 2, b.r * 2, fill)
    }
}
