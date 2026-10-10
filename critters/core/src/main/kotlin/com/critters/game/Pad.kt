package com.critters.game

import kotlin.math.abs
import kotlin.math.hypot

enum class Btn(val bit: Int) { UP(1), DOWN(2), LEFT(4), RIGHT(8), A(16), B(32), START(64), SELECT(128) }

/** Buttons currently held, written by the platform (touch or keys) and read by the game. */
class Pad {
    @Volatile var bits = 0

    /** Bits held by the touch screen and by keys, combined so one never cancels the other. */
    @Volatile var touchBits = 0
        set(v) { field = v; bits = v or keyBits }
    @Volatile var keyBits = 0
        set(v) { field = v; bits = v or touchBits }

    fun key(b: Btn, down: Boolean) {
        keyBits = if (down) keyBits or b.bit else keyBits and b.bit.inv()
    }
}

/**
 * The handheld console drawn around the game screen: the bezel, the D-pad and
 * the A / B / START / SELECT buttons, plus mapping touches onto them.
 * Coordinates are virtual units; one unit is one game pixel.
 */
object Shell {
    /** The smallest area the whole console needs, so the platform can pick a scale. */
    const val MIN_W = 176f
    const val MIN_H = 304f

    var screenX = 8f; private set
    var screenY = 16f; private set
    var dpadX = 0f; private set
    var dpadY = 0f; private set
    var aX = 0f; private set
    var aY = 0f; private set
    var bX = 0f; private set
    var bY = 0f; private set
    var startX = 0f; private set
    var selectX = 0f; private set
    var pillY = 0f; private set

    private const val DPAD_R = 30f
    private const val BTN_R = 13f

    fun layout(viewW: Float, viewH: Float) {
        val cx = viewW / 2
        screenX = (cx - SW / 2f).toInt().toFloat()
        val spare = (viewH - MIN_H).coerceAtLeast(0f)
        screenY = (16f + spare * 0.12f).toInt().toFloat()
        val bezelBottom = screenY + SH + 24
        val ctrl = bezelBottom + 42 + spare * 0.3f
        dpadX = cx - 50
        dpadY = ctrl
        aX = cx + 58; aY = ctrl - 10
        bX = cx + 28; bY = ctrl + 8
        pillY = ctrl + 52
        selectX = cx - 18
        startX = cx + 18
    }

    /** Turns the fingers currently on the screen into held buttons. */
    fun touch(xs: FloatArray, ys: FloatArray, count: Int, viewW: Float, viewH: Float): Int {
        layout(viewW, viewH)
        var bits = 0
        for (i in 0 until count) {
            val x = xs[i]
            val y = ys[i]
            if (y < screenY + SH + 8) continue
            val dx = x - dpadX
            val dy = y - dpadY
            if (hypot(dx, dy) < DPAD_R + 18 && x < viewW / 2) {
                if (hypot(dx, dy) > 4) {
                    bits = bits or if (abs(dx) > abs(dy)) (if (dx < 0) Btn.LEFT.bit else Btn.RIGHT.bit)
                    else (if (dy < 0) Btn.UP.bit else Btn.DOWN.bit)
                }
                continue
            }
            if (abs(y - pillY) < 12 && abs(x - selectX) < 16) { bits = bits or Btn.SELECT.bit; continue }
            if (abs(y - pillY) < 12 && abs(x - startX) < 16) { bits = bits or Btn.START.bit; continue }
            if (x > viewW / 2 - 6 && y < pillY - 10) {
                val da = hypot(x - aX, y - aY)
                val db = hypot(x - bX, y - bY)
                if (minOf(da, db) < BTN_R + 16) bits = bits or if (da <= db) Btn.A.bit else Btn.B.bit
            }
        }
        return bits
    }

    private const val BODY = 0xFF6A48B0.toInt()
    private const val BODY_DARK = 0xFF4C3088.toInt()
    private const val BEZEL = 0xFF3A3A48.toInt()

    private fun roundRect(d: Draw, x: Float, y: Float, w: Float, h: Float, r: Float, c: Int) {
        d.rect(x + r, y, w - 2 * r, h, c)
        d.rect(x, y + r, w, h - 2 * r, c)
        d.oval(x, y, 2 * r, 2 * r, c)
        d.oval(x + w - 2 * r, y, 2 * r, 2 * r, c)
        d.oval(x, y + h - 2 * r, 2 * r, 2 * r, c)
        d.oval(x + w - 2 * r, y + h - 2 * r, 2 * r, 2 * r, c)
    }

    fun draw(d: Draw, pixels: IntArray, bits: Int, viewW: Float, viewH: Float, musicOn: Boolean, powerOn: Boolean = true) {
        layout(viewW, viewH)
        d.rect(0f, 0f, viewW, viewH, BODY)
        // A soft highlight down the left edge, like molded plastic.
        d.rect(0f, 0f, 3f, viewH, 0xFF8466C8.toInt())

        val bx = screenX - 8
        val by = screenY - 12
        roundRect(d, bx, by, SW + 16f, SH + 34f, 6f, BEZEL)
        d.oval(bx + 8, by + 4, 4f, 4f, if (powerOn) 0xFFF04040.toInt() else 0xFF502020.toInt())
        d.text("POWER", bx + 15, by + 8, 4f, 0xFF9090A0.toInt())
        d.screen(pixels, screenX, screenY)

        val ly = screenY + SH + 17
        d.text("POCKET CRITTERS", viewW / 2 - 12, ly, 7f, 0xFFE8E8F0.toInt(), 1)
        val colors = intArrayOf(0xFFF85858.toInt(), 0xFFF8C030.toInt(), 0xFF58D058.toInt(), 0xFF58A8F8.toInt(), 0xFFC878F0.toInt())
        "COLOR".forEachIndexed { i, ch -> d.text(ch.toString(), viewW / 2 + 23 + i * 4.4f, ly, 7f, colors[i], 0) }

        // D-pad
        val pr = DPAD_R
        d.oval(dpadX - pr - 4, dpadY - pr - 4, (pr + 4) * 2, (pr + 4) * 2, BODY_DARK)
        val arm = 11f
        val dark = 0xFF26262E.toInt()
        val pressed = 0xFF505060.toInt()
        d.rect(dpadX - arm, dpadY - pr, arm * 2, pr * 2, dark)
        d.rect(dpadX - pr, dpadY - arm, pr * 2, arm * 2, dark)
        if (bits and Btn.UP.bit != 0) d.rect(dpadX - arm, dpadY - pr, arm * 2, pr - arm, pressed)
        if (bits and Btn.DOWN.bit != 0) d.rect(dpadX - arm, dpadY + arm, arm * 2, pr - arm, pressed)
        if (bits and Btn.LEFT.bit != 0) d.rect(dpadX - pr, dpadY - arm, pr - arm, arm * 2, pressed)
        if (bits and Btn.RIGHT.bit != 0) d.rect(dpadX + arm, dpadY - arm, pr - arm, arm * 2, pressed)
        d.oval(dpadX - 5, dpadY - 5, 10f, 10f, 0xFF1C1C22.toInt())
        val tri = 0xFF6A6A78.toInt()
        d.text("^", dpadX, dpadY - pr + 9, 7f, tri, 1)
        d.text("v", dpadX, dpadY + pr - 3, 7f, tri, 1)
        d.text("<", dpadX - pr + 5, dpadY + 3, 7f, tri, 1)
        d.text(">", dpadX + pr - 5, dpadY + 3, 7f, tri, 1)

        // A and B
        d.oval(bX - 22, bY - 22, 80f, 44f, BODY_DARK)
        for ((x, y, label, b) in listOf(Quad(aX, aY, "A", Btn.A), Quad(bX, bY, "B", Btn.B))) {
            val on = bits and b.bit != 0
            d.oval(x - BTN_R, y - BTN_R + 1.5f, BTN_R * 2, BTN_R * 2, 0xFF701838.toInt())
            d.oval(x - BTN_R, y - BTN_R + if (on) 1.5f else 0f, BTN_R * 2, BTN_R * 2, if (on) 0xFFB02050.toInt() else 0xFFD83068.toInt())
            d.text(label, x + 9, y + BTN_R + 9, 7f, 0xFFD8D0F0.toInt(), 1)
        }

        // START and SELECT
        for ((x, label, b) in listOf(Triple(selectX, "SELECT", Btn.SELECT), Triple(startX, "START", Btn.START))) {
            val on = bits and b.bit != 0
            roundRect(d, x - 10, pillY - 3, 20f, 6f, 3f, if (on) 0xFF505060.toInt() else 0xFF2E2E38.toInt())
            d.text(label, x, pillY + 11, 4.5f, 0xFFD8D0F0.toInt(), 1)
        }

        // Speaker grille
        for (i in 0 until 6) {
            val gx = viewW / 2 + 52 + i * 5f
            d.rect(gx, pillY - 2 - i * 2, 2f, 14f, BODY_DARK)
        }
        d.text(if (musicOn) "SELECT: MUSIC ON" else "SELECT: MUSIC OFF", viewW / 2, pillY + 22, 4f, 0xFFB8A8E0.toInt(), 1)
    }

    private data class Quad(val x: Float, val y: Float, val label: String, val b: Btn)
}
