package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.shadowText
import kotlin.math.hypot
import kotlin.math.sin

/** A carved pumpkin standing in the backyard: [rows] are the carved holes (one bit per cell, [Pumpkins.GW] per row). */
class Lantern(val rows: IntArray, var x: Float) {
    /** The picture, made once (pumpkins don't change after carving). */
    val sprite: Sprite by lazy { Pumpkins.sprite(rows) }
}

/**
 * PUMPKIN CARVING: at the carving table in the backyard, carve a pumpkin with your finger (or a controller cursor)
 * and it becomes a glowing jack-o'-lantern in the yard. Walk up to one to REMOVE it when Halloween is over.
 */
object Pumpkins {
    const val GW = 32
    const val GH = 24
    /** Most jack-o'-lanterns out at once. */
    const val MAX = 12
    /** A pumpkin from the store costs this (or carve one you grew in the garden for free). */
    const val COINS = 15
    /** The carving table, at the start of the backyard. */
    const val TABLE_X = Garden.YARD_X + 8f
    const val TABLE_W = 64f
    /** How wide a lantern is in the yard. */
    const val LANTERN_W = 42f
    private const val STEM = 4

    /** True if cell ([cx], [cy]) is pumpkin (an oval). */
    fun inside(cx: Int, cy: Int): Boolean {
        val dx = (cx + 0.5f - GW / 2f) / (GW / 2f - 0.5f)
        val dy = (cy + 0.5f - GH / 2f) / (GH / 2f - 0.5f)
        return dx * dx + dy * dy <= 1f
    }

    fun carved(rows: IntArray, cx: Int, cy: Int) = cy in 0 until GH && cx in 0 until GW && (rows[cy] ushr cx) and 1 == 1

    fun sprite(rows: IntArray): Sprite {
        val h = GH + STEM
        val px = IntArray(GW * h)
        for (y in 0 until STEM) for (x in GW / 2 - 2 until GW / 2 + 1) px[y * GW + x + (if (y < 2) 1 else 0)] = if (x == GW / 2 - 2) 0xFF3A6A1A.toInt() else 0xFF5A8A2A.toInt()
        for (cy in 0 until GH) for (cx in 0 until GW) {
            if (!inside(cx, cy)) continue
            val c = if (carved(rows, cx, cy)) {
                // Glowing inside; a darker rim where the cut meets the pumpkin.
                val rim = !carved(rows, cx, cy - 1) && inside(cx, cy - 1)
                if (rim) 0xFFE89A2A.toInt() else 0xFFFFE070.toInt()
            } else {
                val edge = !inside(cx - 1, cy) || !inside(cx + 1, cy) || !inside(cx, cy - 1) || !inside(cx, cy + 1)
                val ridge = cx == 8 || cx == 15 || cx == 16 || cx == 23
                when { edge -> 0xFFC8601A.toInt(); ridge -> 0xFFE0701A.toInt(); cy < 6 -> 0xFFFFA040.toInt(); else -> 0xFFF4881F.toInt() }
            }
            px[(cy + STEM) * GW + cx] = c
        }
        return Sprite(GW, h, px)
    }

    /** A lantern with its feet at ([x] left, [bottom]); it flickers if anything is carved. */
    fun draw(gfx: Gfx, l: Lantern, x: Float, bottom: Float, t: Float) {
        val s = LANTERN_W / GW
        val h = (GH + STEM) * s
        if (l.rows.any { it != 0 }) {
            val f = 0.75f + 0.25f * sin(t * 9f + x)
            gfx.oval(x - 8f, bottom - h - 6f, LANTERN_W + 16f, h + 12f, ((f * 70).toInt() shl 24) or 0xFFC040)
        }
        gfx.sprite(l.sprite, x, bottom - h, w = LANTERN_W, h = h)
    }

    /** The carving table, with a pumpkin waiting on it. */
    fun drawTable(gfx: Gfx, x: Float, fy: Float) {
        gfx.rect(x, fy - 26f, TABLE_W, 6f, 0xFF8A5A2A.toInt())
        gfx.rect(x + 4f, fy - 20f, 4f, 20f, 0xFF6A4A20.toInt()); gfx.rect(x + TABLE_W - 8f, fy - 20f, 4f, 20f, 0xFF6A4A20.toInt())
        gfx.oval(x + 18f, fy - 44f, 28f, 20f, 0xFFF4881F.toInt())
        gfx.rect(x + 31f, fy - 48f, 3f, 6f, 0xFF5A8A2A.toInt())
        gfx.rect(x + TABLE_W - 14f, fy - 32f, 2f, 7f, 0xFFB8C0CC.toInt()); gfx.rect(x + TABLE_W - 15f, fy - 26f, 4f, 3f, 0xFF3A2A1A.toInt())
        gfx.shadowText("CARVING", x + TABLE_W / 2, fy - 52f, 6f, 0xFFFFF2C8.toInt(), 1)
    }

    /** The classic jack-o'-lantern face (the FACE button). */
    fun face(): IntArray {
        val rows = IntArray(GH)
        fun cut(x: Int, y: Int) { if (inside(x, y)) rows[y] = rows[y] or (1 shl x) }
        // Triangle eyes
        for (y in 0 until 5) for (x in 0..(4 - y) * 2) { cut(7 + y + x, 6 + (4 - y)); cut(18 + y + x, 6 + (4 - y)) }
        // Nose
        for (y in 0 until 3) for (x in 0..y * 2) cut(15 - y + x, 11 + y)
        // A big toothy smile
        for (x in 6..25) { val d = (x - 15.5f) / 10f; val top = (16 - (1 - d * d) * 1.5f).toInt(); for (y in top..(top + 3 - (if (x % 4 == 0) 2 else 0))) cut(x, y + 1) }
        return rows
    }
}

/** The carving screen: a big pumpkin to cut with your finger, and DONE / FACE / CLEAR / CANCEL. */
class Carver {
    val rows = IntArray(Pumpkins.GH)
    /** The picture is made again only after a cut. */
    private var pic: Sprite? = null
    /** The controller cursor, in cells. */
    var curX = Pumpkins.GW / 2f
    var curY = Pumpkins.GH / 2f
    private var lastX = -1f
    private var lastY = -1f
    var cuts = 0
        private set

    companion object {
        const val CELL = 6f
        val BUTTONS = listOf("DONE", "FACE", "CLEAR", "CANCEL")
    }

    fun boardX(viewW: Float) = viewW / 2 - Pumpkins.GW * CELL / 2 - 40f
    fun boardY() = 58f
    fun buttonX(viewW: Float) = boardX(viewW) + Pumpkins.GW * CELL + 18f
    fun buttonY(i: Int) = 52f + i * 36f

    private fun cutAt(cx: Float, cy: Float) {
        for (y in (cy - 1.2f).toInt()..(cy + 1.2f).toInt()) for (x in (cx - 1.2f).toInt()..(cx + 1.2f).toInt()) {
            if (y !in 0 until Pumpkins.GH || x !in 0 until Pumpkins.GW || !Pumpkins.inside(x, y)) continue
            if (hypot(x + 0.5f - cx, y + 0.5f - cy) > 1.25f) continue
            if ((rows[y] ushr x) and 1 == 0) { rows[y] = rows[y] or (1 shl x); cuts++; pic = null }
        }
    }

    /** Cuts along the finger from where it was to where it is (cells), so fast swipes leave no gaps. */
    fun drag(cx: Float, cy: Float) {
        if (lastX < 0f) cutAt(cx, cy)
        else {
            val steps = (hypot(cx - lastX, cy - lastY) * 3).toInt().coerceIn(1, 60)
            for (k in 1..steps) cutAt(lastX + (cx - lastX) * k / steps, lastY + (cy - lastY) * k / steps)
        }
        lastX = cx; lastY = cy
    }

    fun lift() { lastX = -1f; lastY = -1f }

    /** A finger at screen ([x], [y]): carve if it's on the pumpkin. */
    fun touch(x: Float, y: Float, viewW: Float) {
        val cx = (x - boardX(viewW)) / CELL
        val cy = (y - boardY()) / CELL
        if (cx < -1f || cy < -1f || cx > Pumpkins.GW + 1 || cy > Pumpkins.GH + 1) { lift(); return }
        drag(cx, cy)
    }

    /** Which button is at ([x], [y]), or null. */
    fun buttonAt(x: Float, y: Float, viewW: Float): String? {
        val bx = buttonX(viewW)
        return BUTTONS.indices.firstOrNull { x in bx..(bx + 70f) && y in buttonY(it)..(buttonY(it) + 30f) }?.let { BUTTONS[it] }
    }

    fun clear() { rows.fill(0); cuts = 0; lift(); pic = null }
    fun face() { val f = Pumpkins.face(); for (i in rows.indices) rows[i] = rows[i] or f.getOrElse(i) { 0 }; cuts += 50; pic = null }

    fun draw(gfx: Gfx, viewW: Float, viewH: Float, t: Float, pad: Boolean) {
        gfx.rect(0f, 0f, viewW, viewH, 0xEE1A1020.toInt())
        gfx.shadowText("CARVE YOUR PUMPKIN!", viewW / 2, 14f, 10f, 0xFFFFA040.toInt(), 1)
        gfx.text(if (pad) "Hold A and move to carve. RB done, LB face, B cancel." else "Draw on the pumpkin with your finger to carve it.", viewW / 2, 25f, 6f, 0xFFFFF2C8.toInt(), 1)
        val x0 = boardX(viewW); val y0 = boardY()
        // Glow behind the holes, then the pumpkin cell by cell (only ~600 cells: still cheap).
        gfx.oval(x0 - 6f, y0 - 6f, Pumpkins.GW * CELL + 12f, Pumpkins.GH * CELL + 12f, 0x33FFB040)
        val p = pic ?: Pumpkins.sprite(rows).also { pic = it }
        gfx.sprite(p, x0, y0 - 4 * CELL, w = Pumpkins.GW * CELL, h = (Pumpkins.GH + 4) * CELL)
        if (pad) {
            val cx = x0 + curX * CELL; val cy = y0 + curY * CELL
            gfx.oval(cx - 5f, cy - 5f, 10f, 10f, 0x88FFFFFF.toInt())
            gfx.rect(cx - 1f, cy - 7f, 2f, 14f, 0xFFFFFFFF.toInt()); gfx.rect(cx - 7f, cy - 1f, 14f, 2f, 0xFFFFFFFF.toInt())
        }
        val bx = buttonX(viewW)
        for ((i, b) in BUTTONS.withIndex()) {
            val c = when (b) { "DONE" -> 0xFF2EB84A; "FACE" -> 0xFFF08A1A; "CLEAR" -> 0xFF6A6A7A; else -> 0xFFC83A3A }.toInt()
            gfx.rect(bx, buttonY(i), 70f, 30f, 0xFF0A0A10.toInt())
            gfx.rect(bx + 2f, buttonY(i) + 2f, 66f, 26f, c)
            gfx.text(b, bx + 35f, buttonY(i) + 19f, 8f, 0xFFFFFFFF.toInt(), 1)
        }
        gfx.text("FACE = a quick spooky face", bx + 35f, buttonY(4) + 6f, 4.5f, 0xFFB8B0C8.toInt(), 1)
    }
}
