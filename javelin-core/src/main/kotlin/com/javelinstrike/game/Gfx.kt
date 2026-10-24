package com.javelinstrike.game

import kotlin.concurrent.Volatile

/** The view is always this many virtual pixels tall; the width follows the screen's shape. */
const val VIEW_H = 360f

/**
 * Platform-neutral drawing surface. All coordinates are in virtual pixels;
 * implementations scale them to the screen.
 */
interface Gfx {
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun oval(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun poly(xs: FloatArray, ys: FloatArray, color: Int, n: Int = xs.size)

    /** A thick line with round ends. */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int)

    /** [y] is the text baseline. [align]: 0 = left, 1 = center, 2 = right. */
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int = 0)

    /** Restricts drawing to a rectangle until [unclip]. */
    fun clip(x: Float, y: Float, w: Float, h: Float)
    fun unclip()
}

fun Gfx.shadowText(s: String, x: Float, y: Float, size: Float, color: Int, align: Int = 0) {
    text(s, x + size * 0.08f, y + size * 0.08f, size, 0xC0000000.toInt(), align)
    text(s, x, y, size, color, align)
}

fun argb(a: Int, rgb: Int): Int = (a shl 24) or (rgb and 0xFFFFFF)

fun mixColor(c1: Int, c2: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val a = (c1 ushr shift) and 0xFF
        val b = (c2 ushr shift) and 0xFF
        return (a + (b - a) * k).toInt() and 0xFF
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Formats with one decimal place without depending on the phone's locale. */
fun oneDecimal(v: Float): String {
    val tenths = kotlin.math.round(kotlin.math.abs(v) * 10f).toInt()
    return (if (v < 0f && tenths != 0) "-" else "") + "${tenths / 10}.${tenths % 10}"
}

enum class Sound { CLICK, GRIP, DRAWN, WHOOSH, THUD, HIT, BULLSEYE, CHEER, TICK, CLEAR, FAIL, GAME_OVER }

interface SoundSink {
    fun play(s: Sound)

    /** Buzzes the phone where supported. */
    fun vibrate(ms: Int) {}
}

/** Tiny key/value store so settings and high scores survive restarts. */
interface Storage {
    fun load(key: String): Int
    fun save(key: String, value: Int)

    /** In-memory storage (used by tests and as a fallback). */
    class Memory : Storage {
        private val mem = HashMap<String, Int>()
        override fun load(key: String) = mem[key] ?: 0
        override fun save(key: String, value: Int) { mem[key] = value }
    }
}

/**
 * Input written by the platform layer (UI thread) and read by the game loop.
 * Touches are queued so quick taps and flicks are never missed.
 */
class Input {
    class Touch(val type: Int, val x: Float, val y: Float, val time: Float)

    private val queue = ArrayList<Touch>()

    /** Steering-wheel tilt of the phone in radians; positive when the right side is lower. */
    @Volatile var tilt = 0f
    @Volatile var hasTilt = false

    /** Set by the Android back button. */
    @Volatile var back = false

    /** [time] is in seconds on any steady clock; only differences matter. */
    fun push(type: Int, x: Float, y: Float, time: Float) = synchronized(queue) { queue += Touch(type, x, y, time) }

    fun drain(out: MutableList<Touch>) = synchronized(queue) {
        out.addAll(queue)
        queue.clear()
    }

    companion object {
        /** First finger down: grips the javelin or presses a button. */
        const val DOWN = 0
        const val MOVE = 1
        /** Last finger lifted. */
        const val UP = 2
        /** An extra finger touched down; only used for buttons. */
        const val TAP = 3
    }
}
