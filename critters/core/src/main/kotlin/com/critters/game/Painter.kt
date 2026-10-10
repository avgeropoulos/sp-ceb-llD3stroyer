package com.critters.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val OUTLINE = 0xFF282030.toInt()

fun shade(c: Int, k: Float): Int {
    val r = ((c shr 16 and 255) * k).toInt().coerceIn(0, 255)
    val g = ((c shr 8 and 255) * k).toInt().coerceIn(0, 255)
    val b = ((c and 255) * k).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

fun lighten(c: Int, k: Float): Int {
    fun ch(v: Int) = (v + (255 - v) * k).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(c shr 16 and 255) shl 16) or (ch(c shr 8 and 255) shl 8) or ch(c and 255)
}

/**
 * Paints creature sprites out of simple shapes, then gives them a dark outline,
 * which comes out looking like hand-made pixel art. Shapes are shaded with a light
 * top-left and a darker bottom-right.
 */
class Painter(val w: Int = 48, val h: Int = 48) {
    val px = IntArray(w * h)

    /** While set, painting only fills empty pixels, so shapes go behind what's already there. */
    var underMode = false

    fun set(x: Int, y: Int, c: Int) {
        if (x !in 0 until w || y !in 0 until h) return
        if (underMode && px[y * w + x] != 0) return
        px[y * w + x] = c
    }

    fun get(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) px[y * w + x] else 0

    private fun shaded(c: Int, nx: Float, ny: Float, flat: Boolean): Int {
        if (flat) return c
        val s = nx * 0.45f + ny * 0.8f
        return when {
            s > 0.55f -> shade(c, 0.74f)
            s < -0.62f -> lighten(c, 0.35f)
            else -> c
        }
    }

    /** Filled ellipse centered on (cx, cy). */
    fun ell(cx: Float, cy: Float, rx: Float, ry: Float, c: Int, flat: Boolean = false) {
        for (y in max(0, (cy - ry - 1).toInt())..min(h - 1, (cy + ry + 1).toInt())) {
            for (x in max(0, (cx - rx - 1).toInt())..min(w - 1, (cx + rx + 1).toInt())) {
                val nx = (x + 0.5f - cx) / rx
                val ny = (y + 0.5f - cy) / ry
                if (nx * nx + ny * ny <= 1f) set(x, y, shaded(c, nx, ny, flat))
            }
        }
    }

    /** Filled polygon from alternating x, y coordinates. */
    fun poly(vararg p: Float, c: Int, flat: Boolean = false) {
        val n = p.size / 2
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minX = min(minX, p[i * 2]); maxX = max(maxX, p[i * 2])
            minY = min(minY, p[i * 2 + 1]); maxY = max(maxY, p[i * 2 + 1])
        }
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        val rx = max(1f, (maxX - minX) / 2); val ry = max(1f, (maxY - minY) / 2)
        for (y in max(0, minY.toInt())..min(h - 1, maxY.toInt())) {
            for (x in max(0, minX.toInt())..min(w - 1, maxX.toInt())) {
                val fx = x + 0.5f; val fy = y + 0.5f
                var inside = false
                var j = n - 1
                for (i in 0 until n) {
                    val xi = p[i * 2]; val yi = p[i * 2 + 1]
                    val xj = p[j * 2]; val yj = p[j * 2 + 1]
                    if ((yi > fy) != (yj > fy) && fx < (xj - xi) * (fy - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
                if (inside) set(x, y, shaded(c, (fx - cx) / rx, (fy - cy) / ry, flat))
            }
        }
    }

    fun rect(x: Int, y: Int, rw: Int, rh: Int, c: Int) {
        for (yy in y until y + rh) for (xx in x until x + rw) set(xx, yy, c)
    }

    fun line(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        var x = x0; var y = y0
        val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            set(x, y, c)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** A shiny cartoon eye. */
    fun eye(x: Float, y: Float, r: Float = 2.5f, pupil: Int = INK) {
        ell(x, y, r, r + 1f, pupil, flat = true)
        set((x - r * 0.4f).toInt(), (y - r * 0.6f).toInt(), PAPER)
    }

    /** A plain round eye with a white of the eye. */
    fun bigEye(x: Float, y: Float, r: Float = 3.5f) {
        ell(x, y, r, r + 0.5f, PAPER, flat = true)
        ell(x + 0.5f, y + 0.5f, r * 0.55f, r * 0.65f, INK, flat = true)
        set((x - r * 0.1f).toInt(), (y - r * 0.2f).toInt(), PAPER)
    }

    /** Copies the left half onto the right half, for symmetric front views. */
    fun mirror() {
        for (y in 0 until h) for (x in 0 until w / 2) px[y * w + (w - 1 - x)] = px[y * w + x]
    }

    /** Outlines every shape and seals off the inside edges between colors. */
    fun outline(c: Int = OUTLINE) {
        val src = px.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            if (src[y * w + x] != 0) continue
            val near = (x > 0 && src[y * w + x - 1] != 0) || (x < w - 1 && src[y * w + x + 1] != 0) ||
                (y > 0 && src[(y - 1) * w + x] != 0) || (y < h - 1 && src[(y + 1) * w + x] != 0)
            if (near) px[y * w + x] = c
        }
    }

    fun sprite() = Sprite(w, h, px.copyOf())
}
