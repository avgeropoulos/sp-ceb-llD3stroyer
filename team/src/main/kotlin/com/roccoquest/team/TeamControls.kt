package com.roccoquest.team

import com.roccoquest.game.Gfx
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Held-button state written by the platform layer and read by [TeamGame].
 *
 * Every button also remembers that it was pressed ([wasPressed]) until the game reads it, so a quick tap
 * that goes down and up between two game frames is never lost.
 */
class TeamInput {
    @Volatile private var pressed = 0

    private fun hold(bit: Int, old: Boolean, now: Boolean): Boolean {
        if (now && !old) synchronized(this) { pressed = pressed or (1 shl bit) }
        return now
    }

    /** True if the button went down since the last call (and forgets it). */
    fun wasPressed(bit: Int): Boolean = synchronized(this) {
        val hit = pressed and (1 shl bit) != 0
        pressed = pressed and (1 shl bit).inv()
        hit
    }

    @Volatile var left = false; set(v) { field = hold(B_LEFT, field, v) }
    @Volatile var right = false; set(v) { field = hold(B_RIGHT, field, v) }
    @Volatile var jump = false; set(v) { field = hold(B_JUMP, field, v) }
    /** Mine (Blocky) or throw (Rocco). */
    @Volatile var action = false; set(v) { field = hold(B_ACTION, field, v) }
    /** Swipe down: Blocky digs straight down. */
    @Volatile var down = false; set(v) { field = hold(B_DOWN, field, v) }
    /** Up on a controller or keyboard: only used to move around menus. */
    @Volatile var up = false; set(v) { field = hold(B_UP, field, v) }
    @Volatile var swap = false; set(v) { field = hold(B_SWAP, field, v) }
    /** The Pokemon's SUPER move. */
    @Volatile var superMove = false; set(v) { field = hold(B_SUPER, field, v) }
    /** Keyboard run button; on touch screens, double-tap a direction instead. */
    @Volatile var run = false
    /** Rocco's long jump. */
    @Volatile var longJump = false; set(v) { field = hold(B_LONG, field, v) }
    /** Throw a stick of TNT. */
    @Volatile var tnt = false; set(v) { field = hold(B_TNT, field, v) }
    /** Use a Black Flower. */
    @Volatile var blackFlower = false; set(v) { field = hold(B_BLACK, field, v) }
    /** Call Poseidon (costs 50 coins). */
    @Volatile var poseidon = false; set(v) { field = hold(B_POSEIDON, field, v) }
    @Volatile var craft = false; set(v) { field = hold(B_CRAFT, field, v) }
    @Volatile var pause = false; set(v) { field = hold(B_PAUSE, field, v) }
    @Volatile var musicToggle = false; set(v) { field = hold(B_MUSIC, field, v) }
    /** Looking up and down in the 3D house. */
    @Volatile var lookUp = false
    @Volatile var lookDown = false
    /** Set on any new touch or key press; menus use it. */
    @Volatile var tap = false
    @Volatile var tapX = -1f
    @Volatile var tapY = -1f
    /** True while a finger is on the screen (so menus can tell taps from keyboard keys). */
    @Volatile var touching = false
    /** Where the first finger is right now (-1 when none): for drawing, like carving pumpkins. */
    @Volatile var fingerX = -1f
    @Volatile var fingerY = -1f
    /** A number key 1-9 pressed in a menu (10 for the 0 key), or 0 for none. */
    @Volatile var numberKey = 0

    companion object {
        const val B_JUMP = 0
        const val B_SWAP = 1
        const val B_CRAFT = 2
        const val B_PAUSE = 3
        const val B_MUSIC = 4
        const val B_SUPER = 5
        const val B_ACTION = 6
        const val B_TNT = 7
        const val B_POSEIDON = 8
        const val B_DOWN = 9
        const val B_LONG = 10
        const val B_BLACK = 11
        const val B_LEFT = 12
        const val B_RIGHT = 13
        const val B_UP = 14
    }
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
    /** Poseidon and the Black Flower sit up in the top-right corner, out of the way of your thumbs. */
    val poseidonB = Button(0f, 52f, 14f)
    val blackB = Button(0f, 52f, 13f)
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
        poseidonB.cx = viewW - 20f
        blackB.cx = viewW - 54f
    }

    fun inCraft(x: Float, y: Float) = x > craft.cx - CRAFT_W / 2 - 3 && x < craft.cx + CRAFT_W / 2 + 3 && y < 30f

    private val startY = HashMap<Int, Float>()
    private const val SWIPE = 14f

    /** True while the house is shown in 3D: the touch buttons work differently there. */
    @Volatile var mode3d = false

    /** Where the 3D house's buttons are. */
    private const val PAD_X = 64f
    private const val PAD_Y = 186f
    private fun look3d(viewW: Float) = Button(viewW - 150f, 196f, 16f)
    /** What the two big 3D buttons say: PLACE / BREAK in the 3D house, PUNCH / SPIN in the 3D worlds. */
    var labels3d = "PLACE" to "BREAK"

    /** The 3D JUMP button, above PLACE and BREAK. */
    private fun hop3d(viewW: Float) = Button(viewW - 66f, 140f, 19f)

    /** The 3D house: a 4-way pad on the left (walk and turn), PLACE, BREAK and LOOK buttons on the right. */
    private fun apply3d(input: TeamInput, xs: FloatArray, ys: FloatArray, count: Int, viewW: Float) {
        var u = false; var d = false; var l = false; var r = false; var a = false; var j = false; var lu = false; var ld = false; var p = false
        var hop = false
        val look = look3d(viewW)
        val jb = hop3d(viewW)
        for (i in 0 until count) {
            val x = xs[i]
            val y = ys[i]
            if (hypot(x - pause.cx, y - pause.cy) < pause.r * 1.3f && y < 30f) { p = true; continue }
            if (y < 110f) continue
            if (x < viewW / 2 - 40f) {
                val dx = x - PAD_X
                val dy = y - PAD_Y
                if (hypot(dx, dy) < 10f) continue
                if (abs(dy) > abs(dx)) { if (dy < 0) u = true else d = true } else { if (dx < 0) l = true else r = true }
            } else if (hypot(x - jb.cx, y - jb.cy) < jb.r * 1.25f) hop = true
            else if (hypot(x - look.cx, y - (look.cy - 22f)) < look.r * 1.3f) lu = true
            else if (hypot(x - look.cx, y - (look.cy + 14f)) < look.r * 1.3f) ld = true
            else if (hypot(x - jump.cx, y - jump.cy) < hypot(x - action.cx, y - action.cy)) j = true
            else a = true
        }
        input.touching = count > 0
        input.up = u; input.down = d; input.left = l; input.right = r
        input.action = a; input.jump = j
        input.lookUp = lu; input.lookDown = ld
        input.longJump = hop
        input.pause = p
    }

    fun draw3d(gfx: Gfx, input: TeamInput, viewW: Float, viewH: Float) {
        layout(viewW)
        val c = 0xCCFFFFFF.toInt()
        val bg = 0x55FFFFFF
        for ((dx, dy, on) in listOf(Triple(0f, -1f, input.up), Triple(0f, 1f, input.down), Triple(-1f, 0f, input.left), Triple(1f, 0f, input.right))) {
            val bx = PAD_X + dx * 30f
            val by = PAD_Y + dy * 26f
            gfx.oval(bx - 15, by - 15, 30f, 30f, if (on) 0x99FFFFFF.toInt() else bg)
            gfx.poly(floatArrayOf(bx + dx * 8 + dy * 0, bx - dx * 5 - dy * 7, bx - dx * 5 + dy * 7), floatArrayOf(by + dy * 8, by - dy * 5 + dx * 7, by - dy * 5 - dx * 7), c)
        }
        gfx.text("WALK / TURN", PAD_X, PAD_Y + 50f, 6f, c, 1)
        gfx.oval(jump.cx - jump.r, jump.cy - jump.r, jump.r * 2, jump.r * 2, if (input.jump) 0xCCFFFFFF.toInt() else 0x99E84A4A.toInt())
        gfx.text(labels3d.second, jump.cx, jump.cy + 3, 8f, c, 1)
        gfx.oval(action.cx - action.r, action.cy - action.r, action.r * 2, action.r * 2, if (input.action) 0xCCFFFFFF.toInt() else 0x992EB84A.toInt())
        gfx.text(labels3d.first, action.cx, action.cy + 3, 7f, c, 1)
        val jb = hop3d(viewW)
        gfx.oval(jb.cx - jb.r, jb.cy - jb.r, jb.r * 2, jb.r * 2, if (input.longJump) 0xCCFFFFFF.toInt() else 0x993A6AD8.toInt())
        gfx.text("JUMP", jb.cx, jb.cy + 3, 7f, c, 1)
        val look = look3d(viewW)
        for ((dy, up, on) in listOf(Triple(-22f, true, input.lookUp), Triple(14f, false, input.lookDown))) {
            val by = look.cy + dy
            gfx.oval(look.cx - look.r, by - look.r, look.r * 2, look.r * 2, if (on) 0x99FFFFFF.toInt() else bg)
            gfx.poly(floatArrayOf(look.cx - 6, look.cx + 6, look.cx), if (up) floatArrayOf(by + 4, by + 4, by - 5) else floatArrayOf(by - 4, by - 4, by + 5), c)
        }
        gfx.text("LOOK", look.cx - 26f, look.cy - 2f, 6f, c, 1)
    }

    fun apply(input: TeamInput, ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int, viewW: Float) {
        layout(viewW)
        input.fingerX = if (count > 0) xs[0] else -1f
        input.fingerY = if (count > 0) ys[0] else -1f
        if (mode3d) { apply3d(input, xs, ys, count, viewW); return }
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
        input.touching = count > 0
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
        longLabel: Pair<String, String> = "LONG" to "JUMP",
    ) {
        layout(viewW)
        val c = 0xBBFFFFFF.toInt()
        button(gfx, left, input.left)
        button(gfx, right, input.right)
        button(gfx, jump, input.jump)
        button(gfx, action, input.action)
        gfx.poly(floatArrayOf(left.cx - 9, left.cx + 6, left.cx + 6), floatArrayOf(left.cy, left.cy - 9, left.cy + 9), c)
        gfx.poly(floatArrayOf(right.cx + 9, right.cx - 6, right.cx - 6), floatArrayOf(right.cy, right.cy - 9, right.cy + 9), c)
        gfx.text("JUMP", jump.cx, jump.cy + 3, 8f, c, 1)
        gfx.text(actionLabel, action.cx, action.cy + 3, 7f, c, 1)
        // SWAP shows who you'll switch to.
        val sf = if (input.swap) 0xCCFFFFFF.toInt() else (swapColor and 0x00FFFFFF) or 0x66000000
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
            gfx.oval(lb.cx - lb.r, lb.cy - lb.r, lb.r * 2, lb.r * 2, if (input.longJump) 0xCCFFFFFF.toInt() else 0x55E52521)
            gfx.text(longLabel.first, lb.cx, lb.cy + 1, 6f, c, 1)
            gfx.text(longLabel.second, lb.cx, lb.cy + 8, 4.5f, c, 1)
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
            TeamArt.blackFlower(gfx, bb.cx - 8, bb.cy - 9, 16f, t)
            gfx.text("x$blackFlowers", bb.cx + 9, bb.cy + 13, 5f, c, 1)
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
        // Soft, see-through buttons so they don't crowd the screen.
        val fill = if (pressed) 0x77FFFFFF else 0x26FFFFFF
        gfx.oval(b.cx - b.r - 1, b.cy - b.r - 1, b.r * 2 + 2, b.r * 2 + 2, 0x30000000)
        gfx.oval(b.cx - b.r, b.cy - b.r, b.r * 2, b.r * 2, fill)
    }

    /** Inside the house: just walk left/right and one action button (SHOP, SLEEP, LOOK...). */
    fun drawHome(gfx: Gfx, input: TeamInput, label: String, t: Float) {
        for ((b, held, arrow) in listOf(Triple(left, input.left, -1f), Triple(right, input.right, 1f))) {
            gfx.oval(b.cx - b.r, b.cy - b.r, b.r * 2, b.r * 2, if (held) 0x99FFFFFF.toInt() else 0x55FFFFFF)
            gfx.poly(
                floatArrayOf(b.cx + arrow * 8, b.cx - arrow * 6, b.cx - arrow * 6),
                floatArrayOf(b.cy, b.cy - 9, b.cy + 9), 0xDDFFFFFF.toInt(),
            )
        }
        if (label.isNotEmpty()) {
            val b = action
            val glow = 0x66 + ((kotlin.math.sin(t * 5) + 1) * 40).toInt()
            gfx.oval(b.cx - b.r - 4, b.cy - b.r - 4, b.r * 2 + 8, b.r * 2 + 8, (glow shl 24) or 0x2EB84A)
            gfx.text(label, b.cx, b.cy + 3, 8f, 0xFFFFFFFF.toInt(), 1)
        }
    }
}
