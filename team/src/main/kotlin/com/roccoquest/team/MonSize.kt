package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps the Pokemon in proportion: each one is measured once at its natural drawing size, then scaled so
 * first forms are small, evolutions bigger and legends the biggest (some drawings were way bigger than others).
 */
object MonSize {
    private val cache = HashMap<Mon, Float>()

    /** How tall (in game pixels) each kind of Pokemon should look. */
    fun target(m: Mon): Float = when {
        m.isMega -> 30f
        m == Mon.LUGIA || m == Mon.RAYZOR -> 27f
        m.stage >= 3 -> 25f
        m.stage == 2 -> 21.5f
        else -> 18f
    }

    fun scale(m: Mon): Float = synchronized(cache) {
        cache.getOrPut(m) {
            val b = Bounds()
            TeamArt.monNatural(b, m)
            if (b.maxY < b.minY) return@getOrPut 1f
            // Long, flat Pokemon (sea serpents, dragons) count by their length too.
            val size = max(b.maxY - b.minY, (b.maxX - b.minX) * 0.75f)
            if (size <= 1f) 1f else (target(m) / size).coerceIn(0.4f, 1.35f)
        }
    }

    private val heads = HashMap<Mon, Triple<Float, Float, Float>>()

    /**
     * Where the head is, at the drawn size (facing right, feet at 0): how high its top is above the feet,
     * how far its middle is from the Pokemon's middle, and how wide it is. Hats go there.
     */
    fun head(m: Mon): Triple<Float, Float, Float> = synchronized(cache) {
        heads.getOrPut(m) {
            val b = Bounds()
            TeamArt.monNatural(b, m)
            val k = scale(m)
            if (b.maxY < b.minY) return@getOrPut Triple(18f, 0f, 10f)
            val h = b.maxY - b.minY
            val topOnes = b.shapes.filter { it[1] < b.minY + h * 0.3f && it[2] > 2f }
            val l = topOnes.minOfOrNull { it[0] } ?: b.minX
            val r = topOnes.maxOfOrNull { it[0] + it[2] } ?: b.maxX
            Triple(-b.minY * k, (l + r) / 2 * k, ((r - l) * k).coerceIn(8f, 18f))
        }
    }

    /** A pretend drawing surface that only remembers how far the drawing reaches. */
    private class Bounds : Gfx {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val shapes = ArrayList<FloatArray>()
        private fun add(x: Float, y: Float, w: Float, h: Float, color: Int) {
            if ((color ushr 24) < 0xA0) return // see-through glows and shadows don't count
            minX = min(minX, x); maxX = max(maxX, x + w); minY = min(minY, y); maxY = max(maxY, y + h)
            shapes += floatArrayOf(x, y, w, h)
        }
        override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) = add(x, y, w, h, color)
        override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) = add(x, y, w, h, color)
        override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {
            for (i in xs.indices) add(xs[i], ys[i], 0f, 0f, color)
        }
        override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) = add(x, y, w, h, -1)
        override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {}
        override fun gradient(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) {}
    }
}
