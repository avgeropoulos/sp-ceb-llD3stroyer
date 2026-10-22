package com.herculesapollo.walk

import com.roccoquest.game.Gfx
import kotlin.concurrent.Volatile
import kotlin.math.hypot

/** Held-button state written by the platform layer, read by the game loop. */
class WalkInput {
    @Volatile var left = false
    @Volatile var right = false
    @Volatile var jump = false
    /** Sends Hercules after the nearest critter. */
    @Volatile var herc = false
    /** Sends Apollo after the nearest critter. */
    @Volatile var apollo = false
    /** The dog's special move in the adventure: Hercules barks, Apollo dashes. */
    @Volatile var action = false
    @Volatile var pause = false
    @Volatile var musicToggle = false
    /** Set on any new touch / key press; consumed by menus. */
    @Volatile var tap = false
    /** Position (virtual pixels) of the last tap, for on-screen menu buttons; -1 for a key press. */
    @Volatile var tapX = -1f
    @Volatile var tapY = -1f
    /** The phone's Back button: pauses (or resumes) the game, or leaves for the title screen. */
    @Volatile var back = false
}

/** On-screen touch controls, laid out in virtual coordinates. */
object WalkControls {
    class Button(val label: String, var cx: Float, var cy: Float, val r: Float)

    val left = Button("<", 30f, 210f, 22f)
    val right = Button(">", 84f, 210f, 22f)
    val jump = Button("JUMP", 0f, 216f, 17f)
    val herc = Button("HERC", 0f, 210f, 23f)
    val apollo = Button("APOLLO", 0f, 196f, 23f)
    /** The adventure's layout: a big JUMP button with the dog's special move beside it. */
    val bigJump = Button("JUMP", 0f, 200f, 27f)
    val action = Button("ACTION", 0f, 214f, 21f)
    val pause = Button("II", 0f, 14f, 10f)
    val music = Button("M", 0f, 14f, 10f)

    fun layout(viewW: Float) {
        apollo.cx = viewW - 34f
        herc.cx = viewW - 90f
        jump.cx = viewW - 140f
        bigJump.cx = viewW - 38f
        action.cx = viewW - 104f
        pause.cx = viewW - 16f
        music.cx = viewW - 42f
    }

    /** Maps the currently held touch points onto [input]. [dogMode] picks the adventure's layout. */
    fun apply(input: WalkInput, xs: FloatArray, ys: FloatArray, count: Int, viewW: Float, dogMode: Boolean = false) {
        layout(viewW)
        var l = false
        var r = false
        var j = false
        var h = false
        var a = false
        var p = false
        var m = false
        var act = false
        for (i in 0 until count) {
            val x = xs[i]
            val y = ys[i]
            if (hypot(x - pause.cx, y - pause.cy) < pause.r * 1.3f) { p = true; continue }
            if (hypot(x - music.cx, y - music.cy) < music.r * 1.3f) { m = true; continue }
            if (y < 120f) continue
            if (x < viewW / 2) {
                // Whole bottom-left area acts as a d-pad so a sliding thumb works.
                if (x < (left.cx + right.cx) / 2) l = true else r = true
            } else if (dogMode) {
                val dj = hypot(x - bigJump.cx, y - bigJump.cy) / bigJump.r
                val da = hypot(x - action.cx, y - action.cy) / action.r
                if (dj <= da) j = true else act = true
            } else {
                val best = listOf(jump, herc, apollo).minBy { hypot(x - it.cx, y - it.cy) / it.r }
                when (best) {
                    jump -> j = true
                    herc -> h = true
                    else -> a = true
                }
            }
        }
        input.left = l
        input.right = r
        input.jump = j
        input.herc = h
        input.apollo = a
        input.pause = p
        input.musicToggle = m
        input.action = act
    }

    fun draw(gfx: Gfx, input: WalkInput, viewW: Float, hercReady: Boolean, apolloReady: Boolean) {
        layout(viewW)
        val c = 0xDDFFFFFF.toInt()
        button(gfx, left, input.left, 0)
        button(gfx, right, input.right, 0)
        button(gfx, jump, input.jump, 0)
        button(gfx, herc, input.herc, if (hercReady) WalkSprites.HERC_COLLAR else 0xFF666666.toInt())
        button(gfx, apollo, input.apollo, if (apolloReady) WalkSprites.APOLLO_COLLAR else 0xFF666666.toInt())
        gfx.poly(floatArrayOf(left.cx - 9, left.cx + 6, left.cx + 6), floatArrayOf(left.cy, left.cy - 9, left.cy + 9), c)
        gfx.poly(floatArrayOf(right.cx + 9, right.cx - 6, right.cx - 6), floatArrayOf(right.cy, right.cy - 9, right.cy + 9), c)
        gfx.text("JUMP", jump.cx, jump.cy + 3, 7f, c, 1)
        gfx.sprite(WalkSprites.hercStand, herc.cx - 10.5f, herc.cy - 14f, w = 21f, h = 15f)
        gfx.text("HERC", herc.cx, herc.cy + 12, 7f, c, 1)
        gfx.sprite(WalkSprites.apolloStand, apollo.cx - 10.5f, apollo.cy - 14f, w = 21f, h = 15f)
        gfx.text("APOLLO", apollo.cx, apollo.cy + 12, 6.5f, c, 1)
    }

    /** The adventure's controls. [ready] greys out the action button while it recharges. */
    fun drawDog(gfx: Gfx, input: WalkInput, viewW: Float, actionLabel: String, ring: Int, ready: Boolean) {
        layout(viewW)
        val c = 0xDDFFFFFF.toInt()
        button(gfx, left, input.left, 0)
        button(gfx, right, input.right, 0)
        button(gfx, bigJump, input.jump, 0)
        button(gfx, action, input.action, if (ready) ring else 0xFF666666.toInt())
        gfx.poly(floatArrayOf(left.cx - 9, left.cx + 6, left.cx + 6), floatArrayOf(left.cy, left.cy - 9, left.cy + 9), c)
        gfx.poly(floatArrayOf(right.cx + 9, right.cx - 6, right.cx - 6), floatArrayOf(right.cy, right.cy - 9, right.cy + 9), c)
        gfx.text("JUMP", bigJump.cx, bigJump.cy + 3, 8f, c, 1)
        gfx.text(actionLabel, action.cx, action.cy + 3, 7f, if (ready) c else 0x88FFFFFF.toInt(), 1)
    }

    // ---------------------------------------------------------------- Menus
    /** The title screen's row of save-slot tabs, and the mode buttons below them. */
    const val SLOT_TOP = 113f
    const val SLOT_H = 16f
    const val CARD_TOP = 134f
    const val SLOT_W = 64f

    /** Left edge of slot tab [i] (0-based). */
    fun slotX(i: Int, viewW: Float) = viewW / 2 - SaveSlots.COUNT * SLOT_W / 2 + i * SLOT_W

    /** The slot (1-3) whose tab is at [x], if any. */
    fun slotAt(x: Float, viewW: Float): Int? {
        val i = kotlin.math.floor((x - slotX(0, viewW)) / SLOT_W).toInt()
        return if (i in 0 until SaveSlots.COUNT) i + 1 else null
    }

    /** Pause button plus the music on/off toggle beside it. */
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
            gfx.poly(
                floatArrayOf(x - 7, x - 5.5f, x + 7, x + 5.5f), floatArrayOf(y - 6, y - 7.5f, y + 6, y + 7.5f),
                0xFFE53A1E.toInt(),
            )
        }
    }

    /** [ring] tints the button's rim (0 for none). */
    private fun button(gfx: Gfx, b: Button, pressed: Boolean, ring: Int) {
        val fill = if (pressed) 0x88FFFFFF.toInt() else 0x44FFFFFF
        gfx.oval(b.cx - b.r - 2, b.cy - b.r - 2, b.r * 2 + 4, b.r * 2 + 4, if (ring != 0) (0xAA000000.toInt() or (ring and 0xFFFFFF)) else 0x55000000)
        gfx.oval(b.cx - b.r, b.cy - b.r, b.r * 2, b.r * 2, fill)
    }
}
