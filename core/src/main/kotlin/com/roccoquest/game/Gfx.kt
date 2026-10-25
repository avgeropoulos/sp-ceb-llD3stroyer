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

    /**
     * A rectangle fading smoothly from [top] to [bottom]. Platforms draw a real gradient; this fallback uses
     * many thin bands (fine for tests and wrappers).
     */
    fun gradient(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) {
        val n = 48
        val bh = h / n
        for (i in 0 until n) rect(x, y + i * bh, w, bh + 0.5f, mixColor(top, bottom, i / (n - 1f)))
    }
}

/** A smooth gradient through several [colors], spread evenly from top to bottom. */
fun Gfx.gradientStops(x: Float, y: Float, w: Float, h: Float, vararg colors: Int) {
    val n = colors.size - 1
    if (n <= 0) { rect(x, y, w, h, colors.firstOrNull() ?: 0); return }
    val sh = h / n
    // Each piece overlaps the next by a hair so no seam shows between them.
    for (i in 0 until n) gradient(x, y + i * sh, w, sh + if (i < n - 1) 0.5f else 0f, colors[i], colors[i + 1])
}

/** Blends two ARGB colors: [k] = 0 gives [a], 1 gives [b]. */
fun mixColor(a: Int, b: Int, k: Float): Int {
    fun ch(sh: Int) = ((((a ushr sh) and 255) * (1 - k) + ((b ushr sh) and 255) * k).toInt() and 255) shl sh
    return ch(24) or ch(16) or ch(8) or ch(0)
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

enum class Sound { JUMP, COIN, FIRE, STOMP, KICK, POWERUP, HURT, BUMP, BREAK, BOSS_HIT, BOSS_FIRE, DIE, CLEAR, VICTORY, ONEUP, FLUSH, ZAP, CHOMP, BARK, SPLASH, ARROW, WAVE, PORTAL }

enum class Music { NONE, OVERWORLD, UNDERGROUND, SKY, CASTLE, BOSS, STAR, WONDER, FROG, KRAG, WARDEN, MEADOW, JUNGLE, SNOW, DESERT, NIGHT, BEACH, DOOM, SHANTY,
    PARK, COZY, ADVENTURE, HEROIC, FUNKY, SPACE, CRYSTAL, TROPICAL,
    FANFARE, DREAMY, SPOOKY, WITCHY, SILLY, RACE,
    TOYBOX, MOON, ROBOT, GALAXY, FUNGI, PARADE }

/** Speaking voices: each one gets its own pitch and speed. */
enum class Voice { HERO, FROG, VILLAGER, GOD, MON, TOAD }

fun interface SoundSink {
    fun play(s: Sound)

    /** Switches the looping background track; [Music.NONE] silences it. */
    fun music(m: Music) {}

    /** Speaks a line out loud (Rocco's voice) where the platform supports it. */
    fun say(text: String) {}

    /** Speaks a line in a particular [voice]; platforms without voices fall back to [say]. */
    fun sayAs(text: String, voice: Voice) = say(text)
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

    /** Every key saved so far (used to make safety copies of a whole save file). */
    fun keys(): Set<String> = emptySet()

    /** In-memory storage (used by tests and as a fallback). */
    class Memory : Storage {
        private val mem = HashMap<String, Int>()
        override fun load(key: String) = mem[key] ?: 0
        override fun save(key: String, value: Int) { mem[key] = value }
        override fun keys(): Set<String> = mem.keys.toSet()
    }
}
