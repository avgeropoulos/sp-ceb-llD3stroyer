package com.roccoquest.team3d

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import kotlin.math.min

/**
 * Turns a whole 2D screen (menus, the map, the house, the HUD and buttons) into 3D, keeping
 * every shape exactly where the 2D game put it so taps still land on the right button:
 *
 * * boxes become rounded plastic slabs with shiny bevelled edges,
 * * circles become domes,
 * * pictures become puffy 3D models,
 * * text stays crisp and flat on top.
 *
 * Each new shape sits a little in front of everything drawn before it, so later shapes cover
 * earlier ones just like in 2D. Use with [DrawList.setFlatCamera] and [DrawList.keepOrder].
 */
class FlatGfx(private val list: DrawList) : Gfx {
    private val m = Mat4()
    /** Depth of the next shape; grows by [STEP] per shape. */
    var z = 0f
        private set

    /** Soft drop shadows under raised shapes. */
    var shadows = true

    fun begin() { z = 0f }

    private fun next(): Float {
        z += STEP
        return z
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        if (w <= 0f || h <= 0f || (color ushr 24) == 0) return
        val zz = next()
        val solid = (color ushr 24) == 255
        if (!solid || w < 3f || h < 3f || w * h > BIG) {
            // Thin lines, see-through tints and whole-screen backgrounds stay flat.
            m.identity().translate(x + w / 2, -(y + h / 2), zz - 0.5f).scale(w, h, 1f)
            list.add(Prims.cube, m, color)
            return
        }
        shadow(zz) { translate(x + w / 2 + SHADOW_X, -(y + h / 2) - SHADOW_Y, 0f).scale(w, h, 0.2f); Prims.cube }
        m.identity().translate(x + w / 2, -(y + h / 2), zz - THICK / 2)
        list.add(slab(w, h), m, color, shine = 0.35f)
    }

    /**
     * A soft shadow just behind the shape about to be drawn at depth [zz], so it looks
     * raised off whatever is under it. [place] positions the shadow and picks its mesh.
     */
    private inline fun shadow(zz: Float, place: Mat4.() -> Mesh) {
        if (!shadows) return
        m.identity().translate(0f, 0f, zz - STEP + 0.6f)
        val mesh = m.place()
        list.add(mesh, m, SHADOW)
    }

    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        if (w <= 0f || h <= 0f || (color ushr 24) == 0) return
        val zz = next()
        val dome = min(THICK, min(w, h) * 0.35f).coerceAtLeast(0.4f)
        if ((color ushr 24) == 255 && w * h < BIG) {
            shadow(zz) { translate(x + w / 2 + SHADOW_X, -(y + h / 2) - SHADOW_Y, 0f).scale(w / 2, h / 2, 0.1f); Prims.ball }
        }
        m.identity().translate(x + w / 2, -(y + h / 2), zz - dome).scale(w / 2, h / 2, dome)
        list.add(Prims.ball, m, color, shine = 0.35f)
    }

    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {
        if (xs.size < 3 || (color ushr 24) == 0) return
        val zz = next()
        val ox = xs[0]; val oy = ys[0]
        m.identity().translate(ox, -oy, zz - 1f)
        list.add(polyMesh(xs, ys, ox, oy), m, color, shine = 0.2f)
    }

    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val zz = next()
        val sx = w / s.w
        val sy = h / s.h
        // Puffy, but never thicker than the gap to the next shape.
        val zScale = THICK / Voxels.MAX_HALF / 2
        val mesh = Voxels.mesh(s)
        shadow(zz) {
            translate(if (flipX) x + w + SHADOW_X else x + SHADOW_X, if (flipY) -(y + h) - SHADOW_Y else -y - SHADOW_Y, 0f)
                .scale(if (flipX) -sx else sx, if (flipY) -sy else sy, 0.05f)
            mesh
        }
        m.identity()
            .translate(if (flipX) x + w else x, if (flipY) -(y + h) else -y, zz - THICK / 2)
            .scale(if (flipX) -sx else sx, if (flipY) -sy else sy, zScale)
        list.add(mesh, m, -1, shine = 0.3f)
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {
        if (s.isEmpty() || (color ushr 24) == 0) return
        val word = Glyphs.word(s)
        val mesh = word.mesh ?: return
        val k = size / Glyphs.RASTER
        val width = word.width * k
        val left = when (align) {
            1 -> x - width / 2
            2 -> x - width
            else -> x
        }
        m.identity().translate(left, -y, next()).scale(k, k, 1f)
        // Text glows a touch so it reads well on any colour, and needs blending for soft edges.
        val c = list.add(mesh, m, (color and 0xFFFFFF) or (min(254, color ushr 24) shl 24), emissive = 0.85f)
        c.texture = DrawList.TEX_GLYPHS
    }

    override fun gradient(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) {
        if (w <= 0f || h <= 0f) return
        val zz = next()
        m.identity().translate(x, -y, zz - 0.5f).scale(w, h, 1f)
        list.add(gradientMesh(top, bottom), m, -1, emissive = 0.55f)
    }

    // ---------------------------------------------------------------- meshes

    /** Rounded slabs, one per (rounded) size so the bevel stays the same width on any box. */
    private val slabs = LinkedHashMap<Long, Mesh>(256, 0.75f, true)

    private fun slab(w: Float, h: Float): Mesh {
        val qw = (w * 2).toInt()
        val qh = (h * 2).toInt()
        val key = (qw.toLong() shl 32) or qh.toLong()
        slabs[key]?.let { return it }
        val hw = qw / 4f
        val hh = qh / 4f
        val bevel = (min(hw, hh) * 0.45f).coerceIn(0.4f, 2.2f)
        val mesh = MeshBuilder().apply { roundBox(0f, 0f, 0f, hw, hh, THICK / 2, bevel, -1, Array(6) { Atlas.white }) }.build()
        slabs[key] = mesh
        if (slabs.size > 1500) {
            val it = slabs.entries.iterator()
            it.next().value.release()
            it.remove()
        }
        return mesh
    }

    /** A unit square (x 0..1, y 0..-1) fading from [top] to [bottom]. */
    private val gradients = LinkedHashMap<Long, Mesh>(64, 0.75f, true)

    private fun gradientMesh(top: Int, bottom: Int): Mesh {
        val key = (top.toLong() shl 32) or (bottom.toLong() and 0xFFFFFFFFL)
        gradients[key]?.let { return it }
        val r = Atlas.white
        val mesh = MeshBuilder().apply {
            vertex(0f, -1f, 0f, 0f, 0f, 1f, bottom, r.u0, r.v0)
            vertex(1f, -1f, 0f, 0f, 0f, 1f, bottom, r.u0, r.v0)
            vertex(1f, 0f, 0f, 0f, 0f, 1f, top, r.u0, r.v0)
            vertex(0f, -1f, 0f, 0f, 0f, 1f, bottom, r.u0, r.v0)
            vertex(1f, 0f, 0f, 0f, 0f, 1f, top, r.u0, r.v0)
            vertex(0f, 0f, 0f, 0f, 0f, 1f, top, r.u0, r.v0)
        }.build()
        gradients[key] = mesh
        if (gradients.size > 300) {
            val it = gradients.entries.iterator()
            it.next().value.release()
            it.remove()
        }
        return mesh
    }

    private val polys = LinkedHashMap<String, Mesh>(64, 0.75f, true)
    private val key = StringBuilder()

    private fun polyMesh(xs: FloatArray, ys: FloatArray, ox: Float, oy: Float): Mesh {
        key.setLength(0)
        for (i in xs.indices) {
            key.append(((xs[i] - ox) * 2).toInt()).append(',').append(((ys[i] - oy) * 2).toInt()).append(';')
        }
        val k = key.toString()
        polys[k]?.let { return it }
        val px = FloatArray(xs.size) { xs[it] - ox }
        val py = FloatArray(ys.size) { -(ys[it] - oy) }
        val mesh = MeshBuilder().apply { prism(px, py, -1f, 1f, -1) }.build()
        polys[k] = mesh
        if (polys.size > 800) {
            val it = polys.entries.iterator()
            it.next().value.release()
            it.remove()
        }
        return mesh
    }

    companion object {
        /** Thickness of slabs and pictures. */
        const val THICK = 3f
        /** Gap in depth between one shape and the next. */
        const val STEP = 4f
        const val SHADOW_X = 1.2f
        const val SHADOW_Y = 1.6f
        /** Shadow colour: see-through black. */
        const val SHADOW = 0x50000000
        /** Boxes bigger than this (in square pixels) are backgrounds, so they stay flat. */
        private const val BIG = 120f * 120f

    }
}
