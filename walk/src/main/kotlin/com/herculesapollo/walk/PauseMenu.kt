package com.herculesapollo.walk

import com.roccoquest.game.Gfx
import com.roccoquest.game.shadowText

/**
 * The pause screen, shared by both Hercules & Apollo apps: KEEP GOING, SWITCH DOG (when
 * you're playing as one of the dogs) and SAVE & QUIT, in a row.
 */
object PauseMenu {
    enum class Action(val label: String, val color: Int) {
        RESUME("KEEP GOING", 0xFF2A9D8F.toInt()),
        SWITCH("SWITCH DOG", 0xFF5B4BD6.toInt()),
        QUIT("SAVE & QUIT", 0xFFE76F51.toInt()),
    }

    const val Y = 142f
    const val H = 24f
    const val W = 100f
    private const val GAP = 8f

    fun buttons(canSwitch: Boolean) =
        if (canSwitch) Action.entries.toList() else listOf(Action.RESUME, Action.QUIT)

    /** Left edge of [action]'s button. */
    fun x(action: Action, viewW: Float, canSwitch: Boolean): Float {
        val list = buttons(canSwitch)
        val total = list.size * W + (list.size - 1) * GAP
        return viewW / 2 - total / 2 + list.indexOf(action) * (W + GAP)
    }

    /** The button at ([tx], [ty]), if any. */
    fun hit(tx: Float, ty: Float, viewW: Float, canSwitch: Boolean): Action? {
        if (ty < Y - 4f || ty > Y + H + 4f) return null
        return buttons(canSwitch).firstOrNull { val bx = x(it, viewW, canSwitch); tx >= bx - 2f && tx <= bx + W + 2f }
    }

    fun draw(gfx: Gfx, viewW: Float, viewH: Float, canSwitch: Boolean, note: String) {
        val white = 0xFFFFFFFF.toInt()
        gfx.rect(0f, 0f, viewW, viewH, 0x88000000.toInt())
        gfx.shadowText("PAUSED", viewW / 2, 110f, 18f, white, 1)
        gfx.shadowText(note, viewW / 2, 128f, 7f, white, 1)
        for (a in buttons(canSwitch)) {
            val bx = x(a, viewW, canSwitch)
            gfx.rect(bx - 1.5f, Y - 1.5f, W + 3f, H + 3f, white)
            gfx.rect(bx, Y, W, H, a.color)
            gfx.shadowText(a.label, bx + W / 2, Y + H / 2 + 3f, 8.5f, white, 1)
        }
        gfx.shadowText("Back button: keep going", viewW / 2, Y + H + 16f, 6f, 0xCCFFFFFF.toInt(), 1)
    }
}
