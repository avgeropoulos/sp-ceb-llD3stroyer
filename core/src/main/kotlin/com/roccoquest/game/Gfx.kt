package com.roccoquest.game

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

enum class Music { NONE, OVERWORLD, UNDERGROUND, SKY, CASTLE, BOSS }

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
    @Volatile var pause = false
    @Volatile var musicToggle = false
    /** Set on any new touch / key press; consumed by menus. */
    @Volatile var tap = false
}
