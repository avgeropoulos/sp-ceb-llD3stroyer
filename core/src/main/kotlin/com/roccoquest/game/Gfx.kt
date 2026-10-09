package com.roccoquest.game

import kotlin.concurrent.Volatile

/**
 * Platform-neutral drawing surface. All coordinates are in virtual game pixels
 * (the view is always [VIEW_H] pixels tall); implementations scale to the screen.
 */
interface Gfx {
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun oval(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun poly(xs: FloatArray, ys: FloatArray, color: Int)
    fun sprite(
        s: Sprite, x: Float, y: Float,
        flipX: Boolean = false, flipY: Boolean = false,
        w: Float = s.w.toFloat(), h: Float = s.h.toFloat(),
    )

    /** [y] is the text baseline. [align]: 0 = left, 1 = center, 2 = right. */
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int = 0)
}

/** A small ARGB bitmap. [cache] lets a platform keep its own converted copy. */
class Sprite(val w: Int, val h: Int, val pixels: IntArray) {
    var cache: Any? = null

    fun recolor(map: Map<Int, Int>): Sprite =
        Sprite(w, h, IntArray(pixels.size) { map[pixels[it]] ?: pixels[it] })
}

/** Builds a sprite from rows of characters; '.' is transparent. */
fun pixelArt(palette: Map<Char, Int>, vararg rows: String): Sprite {
    val w = rows[0].length
    require(rows.all { it.length == w }) { "Ragged sprite rows: ${rows.map { it.length }}" }
    val px = IntArray(w * rows.size)
    rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, c ->
            px[y * w + x] = if (c == '.') 0 else palette[c] ?: error("No palette entry for '$c'")
        }
    }
    return Sprite(w, rows.size, px)
}

fun Gfx.shadowText(s: String, x: Float, y: Float, size: Float, color: Int, align: Int = 0) {
    text(s, x + 1, y + 1, size, 0xFF000000.toInt(), align)
    text(s, x, y, size, color, align)
}

fun argb(a: Int, rgb: Int): Int = (a shl 24) or (rgb and 0xFFFFFF)

enum class Sound { JUMP, COIN, FIRE, STOMP, KICK, POWERUP, HURT, BUMP, BREAK, BOSS_HIT, BOSS_FIRE, DIE, CLEAR, VICTORY, ONEUP }

enum class Music { NONE, OVERWORLD, UNDERGROUND, SKY, CASTLE, BOSS, STAR, WONDER }

fun interface SoundSink {
    fun play(s: Sound)

    /** Switches the looping background track; [Music.NONE] silences it. */
    fun music(m: Music) {}
}

/** Held-button state written by the platform layer, read by the game loop. */
class Input {
    @Volatile var left = false
    @Volatile var right = false
    @Volatile var jump = false
    @Volatile var fire = false
    /** Crouch on the ground, butt slam in the air (swipe down / down arrow). */
    @Volatile var down = false
    /** Keyboard run button; on touch screens, double-tap a direction instead. */
    @Volatile var run = false
    @Volatile var pause = false
    @Volatile var musicToggle = false
    /** Set on any new touch / key press; consumed by menus. */
    @Volatile var tap = false
    /** Horizontal position (virtual pixels) of the last tap, for on-screen menu buttons. */
    @Volatile var tapX = -1f
}

/** Tiny key/value store so progress (best score, Wonder World unlock) survives restarts. */
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
