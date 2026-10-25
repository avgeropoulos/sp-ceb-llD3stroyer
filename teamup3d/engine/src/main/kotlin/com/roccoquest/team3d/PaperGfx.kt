package com.roccoquest.team3d

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.mixColor
import kotlin.math.min

/**
 * A [Gfx] that builds 3D shapes instead of painting pixels, so everything Team Up! knows
 * how to draw in 2D also shows up in 3D. The first solid picture of each object (its body)
 * becomes a chunky, puffed-up 3D model (see [Voxels]); the details drawn after it (faces,
 * hats, health bars, effects) are stacked in front of it like thick stickers.
 *
 * Coordinates are the 2D game's screen pixels (y down); they are flipped to world y.
 */
class PaperGfx(private val list: DrawList) : Gfx {
    private val m = Mat4()
    private val pt = FloatArray(2)
    private var base = 0f
    private var front = 0f
    private var hasBody = false
    /** How much shapes glow regardless of lighting (0..1). */
    var emissive = 0f

    /** Starts a new object at depth [z]. */
    fun begin(z: Float = 0f) {
        base = z
        front = z
        hasBody = false
    }

    /** Where the next sticker-like shape goes, and moves the stack forward. */
    private fun nextDecal(): Float {
        front += STEP
        return front
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        if (w <= 0f || h <= 0f) return
        val big = isBody(color) && w * h > 40f
        val (z, d) = if (big) bodyDepth(min(w, h) * 0.5f) else nextDecal() to DECAL * 2
        m.identity().translate(x + w / 2, -(y + h / 2), z).scale(w, h, d)
        list.add(Prims.cube, m, color, glow(color))
    }

    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        if (w <= 0f || h <= 0f) return
        val big = isBody(color) && w * h > 40f
        val (z, d) = if (big) bodyDepth(min(w, h) * 0.5f) else nextDecal() to DECAL * 2
        m.identity().translate(x + w / 2, -(y + h / 2), z).scale(w / 2, h / 2, d / 2)
        list.add(Prims.ballFor(maxOf(w, h)), m, color, glow(color))
    }

    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {
        if (xs.size < 3) return
        val ox = xs[0]; val oy = ys[0]
        val mesh = polyMesh(xs, ys, ox, oy)
        m.identity().translate(ox, -oy, nextDecal())
        list.add(mesh, m, color, glow(color))
    }

    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val sx = w / s.w
        val sy = h / s.h
        val puffy = !hasBody
        val zScale: Float
        val z: Float
        if (puffy) {
            // The body: full thickness, scaled with the picture but never thinner than the art.
            zScale = min(1.4f, maxOf(0.6f, (sx + sy) / 2))
            z = base
            hasBody = true
            front = maxOf(front, base + Voxels.MAX_HALF * zScale)
        } else {
            zScale = DECAL / Voxels.MAX_HALF
            z = nextDecal()
        }
        m.identity()
            .translate(if (flipX) x + w else x, if (flipY) -(y + h) else -y, z)
            .scale(if (flipX) -sx else sx, if (flipY) -sy else sy, zScale)
        val mesh = Voxels.meshOrNull(s)
        if (mesh != null) {
            list.add(mesh, m, -1, emissive)
        } else {
            m.scale(1f, 1f, 0f)
            val c = list.add(Voxels.card(s), m, -1, emissive)
            c.cutout = true
        }
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {
        if (list.project(x, -y, front + 2f, pt)) {
            // Keep the size the 2D game asked for, scaled by how close the text is.
            list.addLabel(s, pt[0], pt[1], size, color, align)
        }
    }

    override fun gradient(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) {
        // One slab in the middle colour is plenty for the few in-level gradients.
        rect(x, y, w, h, mixColor(top, bottom, 0.5f))
    }

    private fun isBody(color: Int) = !hasBody && (color ushr 24) == 255

    private fun bodyDepth(half: Float): Pair<Float, Float> {
        hasBody = true
        val d = half.coerceIn(1.5f, 8f)
        front = maxOf(front, base + d)
        return base to d * 2
    }

    /** See-through shapes in the 2D game are glows, shadows and flashes; let the glows shine. */
    private fun glow(color: Int): Float {
        val a = color ushr 24
        val dark = (color shr 16 and 255) + (color shr 8 and 255) + (color and 255) < 120
        return if (a < 255 && !dark) 0.6f else emissive
    }

    // Polygons change shape as things animate, so cache them by their (rounded) outline.
    private val polys = LinkedHashMap<Long, Mesh>(64, 0.75f, true)

    private fun polyMesh(xs: FloatArray, ys: FloatArray, ox: Float, oy: Float): Mesh {
        // A hash of the rounded outline (no strings, so nothing is thrown away every frame).
        var k = 1469598103934665603L
        for (i in xs.indices) {
            k = (k xor ((xs[i] - ox) * 2).toInt().toLong()) * 1099511628211L
            k = (k xor ((ys[i] - oy) * 2).toInt().toLong()) * 1099511628211L
        }
        polys[k]?.let { return it }
        val px = FloatArray(xs.size) { xs[it] - ox }
        val py = FloatArray(ys.size) { -(ys[it] - oy) }
        val mesh = MeshBuilder().apply { prism(px, py, -DECAL, DECAL, -1) }.build()
        polys[k] = mesh
        if (polys.size > 500) {
            val it = polys.entries.iterator()
            it.next().value.release()
            it.remove()
        }
        return mesh
    }

    private companion object {
        /** Half-thickness of sticker-like detail shapes. */
        const val DECAL = 0.6f
        /** How far each new detail sits in front of the one before. */
        const val STEP = 0.22f
    }
}
