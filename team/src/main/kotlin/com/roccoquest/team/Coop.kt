package com.roccoquest.team

import com.roccoquest.game.Sprite
import kotlin.concurrent.Volatile

/**
 * Two players on one tablet: player 2 plays LUGO (Rocco's taller brother) with a second controller.
 *
 * The Android side only says which controller a button came from; everything else (which controller is
 * which player, joining, the directions) is worked out here, so it can be tested without a phone.
 */

/**
 * Which game controller is which player. The first controller that's used is player 1 (everything works
 * as it always did), a second, different one is player 2. Unplugging one frees its place.
 */
class PadSlots {
    private val ids = IntArray(2) { NONE }

    /** Set when player 2's controller goes away (the game then sends LUGO home). */
    @Volatile var lost = false

    /** The player (0 or 1) that controller [deviceId] belongs to; a new one takes the first free place. */
    @Synchronized fun playerFor(deviceId: Int): Int {
        for (p in ids.indices) if (ids[p] == deviceId) return p
        for (p in ids.indices) if (ids[p] == NONE) { ids[p] = deviceId; return p }
        // A third controller helps player 1 (it never takes anybody's place).
        return 0
    }

    /** Controller [deviceId] was unplugged: its place is free again. Returns the player it was (-1 if none). */
    @Synchronized fun removed(deviceId: Int): Int {
        for (p in ids.indices) if (ids[p] == deviceId) {
            ids[p] = NONE
            if (p == 1) lost = true
            return p
        }
        return -1
    }

    /** True once after player 2's controller went away. */
    fun takeLost(): Boolean {
        if (!lost) return false
        lost = false
        return true
    }

    /** The controller playing as [player], or [NONE]. */
    @Synchronized fun deviceOf(player: Int): Int = ids[player]

    companion object {
        const val NONE = Int.MIN_VALUE
    }
}

/**
 * One controller's directions: its D-pad buttons, the D-pad "hat" and the analog stick (and, for player 1,
 * the keyboard's arrows too). Any of them held means that direction is held.
 */
class PadSticks(private val input: TeamInput) {
    var keyL = false; var keyR = false; var keyD = false; var keyU = false
    var padL = false; var padR = false; var padD = false; var padU = false
    private var hatL = false; private var hatR = false; private var hatD = false; private var hatU = false
    private var stickL = false; private var stickR = false; private var stickD = false; private var stickU = false

    fun refresh() {
        input.left = keyL || padL || hatL || stickL
        input.right = keyR || padR || hatR || stickR
        input.down = keyD || padD || hatD || stickD
        input.up = keyU || padU || hatU || stickU
    }

    /**
     * The analog stick ([x], [y]) and the hat ([hx], [hy]). A direction turns on past [STICK_ON] and only turns
     * off again below [STICK_OFF], so a stick resting near the edge doesn't flicker on and off.
     */
    fun stick(x: Float, y: Float, hx: Float, hy: Float) {
        stickL = if (stickL) x < -STICK_OFF else x < -STICK_ON
        stickR = if (stickR) x > STICK_OFF else x > STICK_ON
        // Up and down need a firmer push, so running doesn't make Rocco duck by accident.
        stickU = if (stickU) y < -STICK_OFF - 0.2f else y < -STICK_ON - 0.2f
        stickD = if (stickD) y > STICK_OFF + 0.2f else y > STICK_ON + 0.2f
        hatL = hx < -0.5f; hatR = hx > 0.5f
        hatU = hy < -0.5f; hatD = hy > 0.5f
        refresh()
    }

    /** The controller was unplugged: nothing is held any more. */
    fun clear() {
        keyL = false; keyR = false; keyD = false; keyU = false
        padL = false; padR = false; padD = false; padU = false
        hatL = false; hatR = false; hatD = false; hatU = false
        stickL = false; stickR = false; stickD = false; stickU = false
        refresh()
    }

    companion object {
        const val STICK_ON = 0.35f
        const val STICK_OFF = 0.25f
    }
}

/**
 * Player 2's buttons, read once a frame, and LUGO's own jump buffer, coyote time and so on (player 1's are
 * in [TeamGame] itself, exactly as before).
 */
class PlayerPad(private val input: TeamInput) {
    private val prev = BooleanArray(16)
    var jumpE = false; private set
    var actionE = false; private set
    var downE = false; private set
    var longE = false; private set
    var pauseE = false; private set
    var leftE = false; private set
    var rightE = false; private set
    var upE = false; private set
    var tntE = false; private set

    /** JUMP pressed a moment before landing still jumps, and so does JUMP just after running off a ledge. */
    var jumpBufT = 0f
    var coyoteT = 0f
    /** THROW pressed while still cooling down happens as soon as it's ready. */
    var actionBufT = 0f
    /** Every jump goes at least a little way up. */
    var jumpHoldT = 0f
    /** Holding DOWN after a butt slam keeps pounding. */
    var poundAgain = false
    /** Double-tapping a direction sprints (like player 1). */
    var prevDir = 0
    var lastTapDir = 0
    var lastTapTime = -1f
    /** How long SELECT has been held (holding it sends LUGO home). */
    var selectT = 0f

    private fun edge(i: Int, now: Boolean): Boolean {
        val e = input.wasPressed(i) || (now && !prev[i])
        prev[i] = now
        return e
    }

    fun read() {
        jumpE = edge(TeamInput.B_JUMP, input.jump)
        actionE = edge(TeamInput.B_ACTION, input.action)
        downE = edge(TeamInput.B_DOWN, input.down)
        longE = edge(TeamInput.B_LONG, input.longJump)
        pauseE = edge(TeamInput.B_PAUSE, input.pause)
        leftE = edge(TeamInput.B_LEFT, input.left)
        rightE = edge(TeamInput.B_RIGHT, input.right)
        upE = edge(TeamInput.B_UP, input.up)
        tntE = edge(TeamInput.B_TNT, input.tnt)
    }

    /** Just joined: buttons already held (like the one that joined) don't count as new presses. */
    fun sync() {
        input.forgetPresses()
        prev[TeamInput.B_JUMP] = input.jump
        prev[TeamInput.B_ACTION] = input.action
        prev[TeamInput.B_DOWN] = input.down
        prev[TeamInput.B_LONG] = input.longJump
        prev[TeamInput.B_PAUSE] = input.pause
        prev[TeamInput.B_LEFT] = input.left
        prev[TeamInput.B_RIGHT] = input.right
        prev[TeamInput.B_UP] = input.up
        prev[TeamInput.B_TNT] = input.tnt
        jumpE = false; actionE = false; downE = false; longE = false; pauseE = false
        leftE = false; rightE = false; upE = false; tntE = false
        reset()
        selectT = 0f
    }

    /** A new level (or a pop back to player 1): no old presses carried over. */
    fun reset() {
        jumpBufT = 0f; coyoteT = 0f; actionBufT = 0f; jumpHoldT = 0f
        poundAgain = false
    }
}

/**
 * LUGO, Rocco's taller brother: Rocco's pictures with a green cap and shirt, a little taller and thinner.
 * Every picture is made once and kept (Team Up! 3D builds one 3D model per picture, so the same picture
 * must come back every frame).
 */
object LugoArt {
    const val GREEN = 0xFF2DB43C.toInt()
    private const val RED = 0xFFE52521.toInt()
    private val cache = java.util.IdentityHashMap<Sprite, Sprite>()

    /** frame: 0 stand, 1 walk, 2 jump. The Wonder power-ups' pictures are Rocco's own: LUGO keeps his. */
    fun lugo(p: Power, frame: Int): Sprite {
        val src = TeamArt.roccoPlain(if (PowerUps.isWonder(p)) Power.NONE else p, frame)
        return Outline.of(synchronized(cache) { cache.getOrPut(src) { lanky(src.recolor(mapOf(RED to GREEN))) } })
    }

    /**
     * Taller and thinner: one column out of the middle of him (so his body and legs are slimmer) and three rows
     * doubled (his chest, his belly and his legs), so he stands 27 pixels tall instead of 24.
     */
    fun lanky(s: Sprite): Sprite {
        if (s.w != 16 || s.h != 24) return s
        val rows = ArrayList<Int>(27)
        for (y in 0 until 24) {
            rows += y
            if (y == 10 || y == 13 || y == 17) rows += y
        }
        val w = 15
        val px = IntArray(w * rows.size)
        for ((ny, y) in rows.withIndex()) for (nx in 0 until w) {
            val x = if (nx < CUT) nx else nx + 1
            px[ny * w + nx] = s.pixels[y * s.w + x]
        }
        return Sprite(w, rows.size, px)
    }

    /** The column taken out of the middle. */
    private const val CUT = 8
}
