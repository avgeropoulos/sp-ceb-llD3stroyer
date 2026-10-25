package com.roccoquest.team3d

import com.roccoquest.game.Sprite
import kotlin.math.min

/**
 * Turns 2D pixel art into chunky, "puffed-up" 3D models: each pixel becomes a column
 * that is thickest in the middle of the shape and thin at its edges, like an inflated
 * vinyl toy. This gives every sprite in the game a 3D look for free.
 *
 * The mesh spans x 0..w and y 0..-h (pixel rows go down, world y goes up).
 */
object Voxels {
    /** Most recently used last; the oldest models are freed when there are too many. */
    private val cache = LinkedHashMap<Sprite, Mesh>(256, 0.75f, true)

    /** How many models have been built so far (tests use it to spot pictures made every frame). */
    var built = 0
        private set

    /** New models that may still be built this frame (building lots at once would stutter). */
    private var budget = Int.MAX_VALUE

    /** Call once per frame: at most [perFrame] new models get built until the next call. */
    fun newFrame(perFrame: Int) { budget = perFrame }

    /**
     * The model for [s], or null if it isn't built yet and this frame's budget is used up
     * (draw [card] instead; the model gets built on a following frame).
     */
    fun meshOrNull(s: Sprite): Mesh? {
        cache[s]?.let { return it }
        if (budget <= 0) return null
        budget--
        return mesh(s)
    }

    /** A flat picture of [s] (x 0..w, y 0..-h) using the tile atlas: a quick stand-in for its model. */
    fun card(s: Sprite): Mesh = cards.getOrPut(s) {
        val r = Atlas.region(s)
        MeshBuilder().apply {
            quad(0f, -s.h.toFloat(), 0f, s.w.toFloat(), -s.h.toFloat(), 0f, s.w.toFloat(), 0f, 0f, 0f, 0f, 0f, -1, r)
        }.build()
    }.also {
        if (cards.size > 300) {
            val it2 = cards.entries.iterator()
            it2.next().value.release()
            it2.remove()
        }
    }

    private val cards = LinkedHashMap<Sprite, Mesh>(64, 0.75f, true)

    fun mesh(s: Sprite): Mesh {
        cache[s]?.let { return it }
        val mesh = build(s)
        built++
        cache[s] = mesh
        if (cache.size > MAX_CACHED) {
            val it = cache.entries.iterator()
            it.next().value.release()
            it.remove()
        }
        return mesh
    }

    private fun build(s: Sprite): Mesh {
        val w = s.w
        val h = s.h
        fun opaque(x: Int, y: Int) = x in 0 until w && y in 0 until h && (s.pixels[y * w + x] ushr 24) > 0
        // Distance (in pixels) to the outline, via a two-pass chamfer transform.
        val big = 999
        val d = IntArray(w * h) { if (opaque(it % w, it / w)) big else 0 }
        fun at(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) d[y * w + x] else 0
        for (y in 0 until h) for (x in 0 until w) if (d[y * w + x] > 0) {
            d[y * w + x] = min(d[y * w + x], min(at(x - 1, y), at(x, y - 1)) + 1)
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) if (d[y * w + x] > 0) {
            d[y * w + x] = min(d[y * w + x], min(at(x + 1, y), at(x, y + 1)) + 1)
        }
        val thick = FloatArray(w * h) { if (d[it] == 0) 0f else min(0.9f + (d[it] - 1) * 0.85f, MAX_HALF) }
        fun t(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) thick[y * w + x] else 0f

        val b = MeshBuilder()
        val r = Atlas.white
        val u = r.u(0.5f); val v = r.v(0.5f)
        fun color(x: Int, y: Int) = s.pixels[y * w + x] or 0xFF000000.toInt()
        // Pillowy normals from the thickness gradient.
        fun gx(x: Int, y: Int) = (t(x - 1, y) - t(x + 1, y)) * 0.5f
        fun gy(x: Int, y: Int) = (t(x, y + 1) - t(x, y - 1)) * 0.5f
        for (y in 0 until h) {
            val y0 = -y.toFloat(); val y1 = -(y + 1f)
            // Front and back faces: neighbouring pixels that match exactly become one long piece.
            var x = 0
            while (x < w) {
                val z = t(x, y)
                if (z == 0f) { x++; continue }
                val c = color(x, y)
                val nx = gx(x, y); val ny = gy(x, y)
                val start = x
                x++
                while (x < w && t(x, y) == z && color(x, y) == c && gx(x, y) == nx && gy(x, y) == ny) x++
                val x0 = start.toFloat(); val x1 = x.toFloat()
                for (side in intArrayOf(1, -1)) {
                    val zz = z * side
                    val sx = nx * side; val sy = ny * side; val sz = 1f * side
                    b.vertex(x0, y1, zz, sx, sy, sz, c, u, v)
                    b.vertex(x1, y1, zz, sx, sy, sz, c, u, v)
                    b.vertex(x1, y0, zz, sx, sy, sz, c, u, v)
                    b.vertex(x0, y1, zz, sx, sy, sz, c, u, v)
                    b.vertex(x1, y0, zz, sx, sy, sz, c, u, v)
                    b.vertex(x0, y0, zz, sx, sy, sz, c, u, v)
                }
            }
            // Walls wherever the neighbour is thinner.
            for (px in 0 until w) {
                val z = t(px, y)
                if (z == 0f) continue
                val side = Atlas.shade(color(px, y), 0.85f)
                val x0 = px.toFloat(); val x1 = px + 1f
                wall(t(px - 1, y), z) { zA, zB -> b.quad(x0, y1, zA, x0, y1, zB, x0, y0, zB, x0, y0, zA, side) }
                wall(t(px + 1, y), z) { zA, zB -> b.quad(x1, y1, zB, x1, y1, zA, x1, y0, zA, x1, y0, zB, side) }
                wall(t(px, y - 1), z) { zA, zB -> b.quad(x0, y0, zB, x1, y0, zB, x1, y0, zA, x0, y0, zA, side) }
                wall(t(px, y + 1), z) { zA, zB -> b.quad(x0, y1, zA, x1, y1, zA, x1, y1, zB, x0, y1, zB, side) }
            }
        }
        return b.build()
    }

    /** Emits the wall between a column of half-thickness [own] and a thinner neighbour. */
    private inline fun wall(other: Float, own: Float, emit: (Float, Float) -> Unit) {
        if (other >= own) return
        if (other == 0f) {
            emit(-own, own)
        } else {
            emit(other, own)
            emit(-own, -other)
        }
    }

    const val MAX_HALF = 3.6f
    private const val MAX_CACHED = 900
}
