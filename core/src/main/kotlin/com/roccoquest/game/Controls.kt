package com.roccoquest.game

import kotlin.math.hypot

/** On-screen touch controls, laid out in virtual coordinates. */
object Controls {
    class Button(val label: String, var cx: Float, var cy: Float, val r: Float)

    val left = Button("<", 30f, 206f, 22f)
    val right = Button(">", 84f, 206f, 22f)
    val fire = Button("FIRE", 0f, 212f, 20f)
    val jump = Button("JUMP", 0f, 194f, 26f)
    val pause = Button("II", 0f, 14f, 10f)

    fun layout(viewW: Float) {
        fire.cx = viewW - 96f
        jump.cx = viewW - 36f
        pause.cx = viewW - 16f
    }

    /** Maps the currently held touch points onto [input]. */
    fun apply(input: Input, xs: FloatArray, ys: FloatArray, count: Int, viewW: Float) {
        layout(viewW)
        var l = false
        var r = false
        var j = false
        var f = false
        var p = false
        for (i in 0 until count) {
            val x = xs[i]
            val y = ys[i]
            if (hypot(x - pause.cx, y - pause.cy) < pause.r * 2f) { p = true; continue }
            if (y < 120f) continue
            if (x < viewW / 2) {
                // Whole bottom-left area acts as a d-pad so a sliding thumb works.
                if (x < (left.cx + right.cx) / 2) l = true else r = true
            } else {
                val dj = hypot(x - jump.cx, y - jump.cy) / jump.r
                val df = hypot(x - fire.cx, y - fire.cy) / fire.r
                if (dj <= df) j = true else f = true
            }
        }
        input.left = l
        input.right = r
        input.jump = j
        input.fire = f
        input.pause = p
    }

    fun draw(gfx: Gfx, input: Input, viewW: Float, showFire: Boolean) {
        layout(viewW)
        button(gfx, left, input.left)
        button(gfx, right, input.right)
        button(gfx, jump, input.jump)
        if (showFire) button(gfx, fire, input.fire)
        // Arrow glyphs for the d-pad
        val c = 0xDDFFFFFF.toInt()
        gfx.poly(floatArrayOf(left.cx - 9, left.cx + 6, left.cx + 6), floatArrayOf(left.cy, left.cy - 9, left.cy + 9), c)
        gfx.poly(floatArrayOf(right.cx + 9, right.cx - 6, right.cx - 6), floatArrayOf(right.cy, right.cy - 9, right.cy + 9), c)
        gfx.text("JUMP", jump.cx, jump.cy + 3, 8f, c, 1)
        if (showFire) gfx.text("FIRE", fire.cx, fire.cy + 3, 7f, c, 1)
    }

    fun drawPause(gfx: Gfx, viewW: Float) {
        layout(viewW)
        gfx.oval(pause.cx - pause.r, pause.cy - pause.r, pause.r * 2, pause.r * 2, 0x66000000)
        gfx.rect(pause.cx - 4, pause.cy - 5, 3f, 10f, 0xDDFFFFFF.toInt())
        gfx.rect(pause.cx + 1, pause.cy - 5, 3f, 10f, 0xDDFFFFFF.toInt())
    }

    private fun button(gfx: Gfx, b: Button, pressed: Boolean) {
        val fill = if (pressed) 0x88FFFFFF.toInt() else 0x44FFFFFF
        gfx.oval(b.cx - b.r - 1, b.cy - b.r - 1, b.r * 2 + 2, b.r * 2 + 2, 0x55000000)
        gfx.oval(b.cx - b.r, b.cy - b.r, b.r * 2, b.r * 2, fill)
    }
}
