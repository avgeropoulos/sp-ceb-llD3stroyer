package com.roccoquest.team3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Triangle list with an interleaved vertex layout of [STRIDE] floats:
 * position (3), normal (3), colour RGBA (4), texture uv (2).
 */
class Mesh(val data: FloatArray, val vertexCount: Int) {
    /** Backend handle (an OpenGL buffer id); 0 until the backend uploads it. */
    @Volatile var handle = 0
    @Volatile var released = false
        private set

    /** Lets the backend free its copy. The mesh must not be drawn again. */
    fun release() {
        if (released) return
        released = true
        synchronized(releasedQueue) { releasedQueue += this }
    }

    companion object {
        const val STRIDE = 12
        /** Meshes released since the backend last looked; the backend drains this. */
        val releasedQueue = ArrayList<Mesh>()
    }
}

/** A rectangle of the texture atlas, in 0..1 texture coordinates. */
class Region(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    fun u(f: Float) = u0 + (u1 - u0) * f
    fun v(f: Float) = v0 + (v1 - v0) * f
}

/** Builds meshes out of primitives, placed with an optional transform. */
class MeshBuilder {
    private var buf = FloatArray(4096)
    private var size = 0
    val xf = Mat4()
    private val nm = FloatArray(9)
    private var nmDirty = true
    private val p = FloatArray(3)

    val isEmpty get() = size == 0

    /** Sets the transform applied to everything added after this call. */
    fun place(block: Mat4.() -> Unit): MeshBuilder {
        xf.identity().block()
        nmDirty = true
        return this
    }

    fun resetPlace(): MeshBuilder = place {}

    fun build(): Mesh = Mesh(buf.copyOf(size), size / Mesh.STRIDE)

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, color: Int, u: Float, v: Float) {
        if (size + Mesh.STRIDE > buf.size) buf = buf.copyOf(buf.size * 2)
        if (nmDirty) { xf.normalMatrix(nm); nmDirty = false }
        xf.transform(x, y, z, p)
        var tx = nm[0] * nx + nm[3] * ny + nm[6] * nz
        var ty = nm[1] * nx + nm[4] * ny + nm[7] * nz
        var tz = nm[2] * nx + nm[5] * ny + nm[8] * nz
        val l = sqrt(tx * tx + ty * ty + tz * tz)
        if (l > 1e-6f) { tx /= l; ty /= l; tz /= l }
        val b = buf
        var i = size
        b[i++] = p[0]; b[i++] = p[1]; b[i++] = p[2]
        b[i++] = tx; b[i++] = ty; b[i++] = tz
        b[i++] = (color shr 16 and 255) / 255f
        b[i++] = (color shr 8 and 255) / 255f
        b[i++] = (color and 255) / 255f
        b[i++] = (color ushr 24) / 255f
        b[i++] = u; b[i++] = v
        size = i
    }

    /** Flat-shaded quad a-b-c-d (counter-clockwise when seen from the front). */
    fun quad(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
        color: Int, r: Region = Atlas.white,
    ) {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = dx - ax; val vy = dy - ay; val vz = dz - az
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        vertex(ax, ay, az, nx, ny, nz, color, r.u0, r.v1)
        vertex(bx, by, bz, nx, ny, nz, color, r.u1, r.v1)
        vertex(cx, cy, cz, nx, ny, nz, color, r.u1, r.v0)
        vertex(ax, ay, az, nx, ny, nz, color, r.u0, r.v1)
        vertex(cx, cy, cz, nx, ny, nz, color, r.u1, r.v0)
        vertex(dx, dy, dz, nx, ny, nz, color, r.u0, r.v0)
    }

    /** Axis-aligned box with flat faces. [r] textures every face. */
    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, color: Int, r: Region = Atlas.white) {
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, color, r) // front
        quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, color, r) // back
        quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, color, r) // top
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, color, r) // bottom
        quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, color, r) // right
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, color, r) // left
    }

    /**
     * Ellipsoid centred on (cx, cy, cz). [latMin]/[latMax] (radians, -PI/2..PI/2) cut it,
     * so `latMin = 0` gives a dome.
     */
    fun ellipsoid(
        cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, color: Int,
        slices: Int = 14, stacks: Int = 9, latMin: Float = -PI.toFloat() / 2, latMax: Float = PI.toFloat() / 2,
    ) {
        val r = Atlas.white
        fun pt(i: Int, j: Int, out: FloatArray) {
            val lat = latMin + (latMax - latMin) * j / stacks
            val lon = 2 * PI.toFloat() * i / slices
            out[0] = cos(lat) * cos(lon); out[1] = sin(lat); out[2] = -cos(lat) * sin(lon)
        }
        val a = FloatArray(3); val b = FloatArray(3); val c = FloatArray(3); val d = FloatArray(3)
        for (j in 0 until stacks) for (i in 0 until slices) {
            pt(i, j, a); pt(i + 1, j, b); pt(i + 1, j + 1, c); pt(i, j + 1, d)
            for (q in arrayOf(a, b, c, a, c, d)) {
                // Normal of an ellipsoid: position divided by the squared radii.
                vertex(cx + q[0] * rx, cy + q[1] * ry, cz + q[2] * rz, q[0] / rx, q[1] / ry, q[2] / rz, color, r.u(0.5f), r.v(0.5f))
            }
        }
        if (latMin > -PI.toFloat() / 2 + 1e-3f) {
            // Close the cut with a flat cap.
            val y = cy + sin(latMin) * ry
            val k = cos(latMin)
            for (i in 0 until slices) {
                val l0 = 2 * PI.toFloat() * i / slices
                val l1 = 2 * PI.toFloat() * (i + 1) / slices
                vertex(cx, y, cz, 0f, -1f, 0f, color, r.u0, r.v0)
                vertex(cx + cos(l1) * rx * k, y, cz - sin(l1) * rz * k, 0f, -1f, 0f, color, r.u0, r.v0)
                vertex(cx + cos(l0) * rx * k, y, cz - sin(l0) * rz * k, 0f, -1f, 0f, color, r.u0, r.v0)
            }
        }
    }

    fun sphere(cx: Float, cy: Float, cz: Float, radius: Float, color: Int, slices: Int = 14, stacks: Int = 9) =
        ellipsoid(cx, cy, cz, radius, radius, radius, color, slices, stacks)

    /** Upright cylinder (or cone when [rTop] differs) from [y0] to [y1]. */
    fun cylinder(
        cx: Float, y0: Float, cz: Float, y1: Float, rBottom: Float, color: Int,
        rTop: Float = rBottom, slices: Int = 16, caps: Boolean = true, zScale: Float = 1f,
    ) {
        val r = Atlas.white
        val u = r.u(0.5f); val v = r.v(0.5f)
        val slope = (rBottom - rTop) / (y1 - y0)
        for (i in 0 until slices) {
            val a0 = 2 * PI.toFloat() * i / slices
            val a1 = 2 * PI.toFloat() * (i + 1) / slices
            val c0 = cos(a0); val s0 = -sin(a0) * zScale
            val c1 = cos(a1); val s1 = -sin(a1) * zScale
            vertex(cx + c0 * rBottom, y0, cz + s0 * rBottom, c0, slope, s0, color, u, v)
            vertex(cx + c1 * rBottom, y0, cz + s1 * rBottom, c1, slope, s1, color, u, v)
            vertex(cx + c1 * rTop, y1, cz + s1 * rTop, c1, slope, s1, color, u, v)
            vertex(cx + c0 * rBottom, y0, cz + s0 * rBottom, c0, slope, s0, color, u, v)
            vertex(cx + c1 * rTop, y1, cz + s1 * rTop, c1, slope, s1, color, u, v)
            vertex(cx + c0 * rTop, y1, cz + s0 * rTop, c0, slope, s0, color, u, v)
            if (caps) {
                if (rTop > 0f) {
                    vertex(cx, y1, cz, 0f, 1f, 0f, color, u, v)
                    vertex(cx + c0 * rTop, y1, cz + s0 * rTop, 0f, 1f, 0f, color, u, v)
                    vertex(cx + c1 * rTop, y1, cz + s1 * rTop, 0f, 1f, 0f, color, u, v)
                }
                vertex(cx, y0, cz, 0f, -1f, 0f, color, u, v)
                vertex(cx + c1 * rBottom, y0, cz + s1 * rBottom, 0f, -1f, 0f, color, u, v)
                vertex(cx + c0 * rBottom, y0, cz + s0 * rBottom, 0f, -1f, 0f, color, u, v)
            }
        }
    }

    /**
     * A box with rounded edges and corners. Each face gets its own atlas region
     * ([faces]: +x, -x, +y, -y, +z, -z), stretched over the whole face so textures
     * wrap over the bevels like a real toy block.
     */
    fun roundBox(
        cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, bevel: Float,
        color: Int, faces: Array<Region>,
    ) {
        val b = min(bevel, min(hx, min(hy, hz)))
        // One bevel step per edge: smooth enough at game size, and far fewer points to draw.
        fun steps(h: Float) = floatArrayOf(-h, -h + b, h - b, h)
        val sx = steps(hx); val sy = steps(hy); val sz = steps(hz)
        val pos = FloatArray(3); val nrm = FloatArray(3)
        fun round(x: Float, y: Float, z: Float) {
            val ix = max(-(hx - b), min(hx - b, x))
            val iy = max(-(hy - b), min(hy - b, y))
            val iz = max(-(hz - b), min(hz - b, z))
            var dx = x - ix; var dy = y - iy; var dz = z - iz
            val l = sqrt(dx * dx + dy * dy + dz * dz)
            if (l > 1e-5f) { dx /= l; dy /= l; dz /= l }
            pos[0] = ix + dx * b; pos[1] = iy + dy * b; pos[2] = iz + dz * b
            nrm[0] = dx; nrm[1] = dy; nrm[2] = dz
        }
        // face axis, sign, (u axis, v axis) chosen so quads wind outward.
        for (f in 0 until 6) {
            val axis = f / 2
            val sign = if (f % 2 == 0) 1f else -1f
            val reg = faces[f]
            val ua: Int; val va: Int
            when (axis) {
                0 -> { ua = 2; va = 1 }
                1 -> { ua = 0; va = 2 }
                else -> { ua = 0; va = 1 }
            }
            val us = when (ua) { 0 -> sx; 1 -> sy; else -> sz }
            val vs = when (va) { 0 -> sx; 1 -> sy; else -> sz }
            val hu = us.last(); val hv = vs.last()
            // Flip u on faces where the default orientation would mirror the texture.
            val flipU = (axis == 0 && sign > 0) || (axis == 2 && sign < 0)
            val flipV = axis == 1 && sign < 0
            val c = FloatArray(3)
            for (j in 0 until vs.size - 1) for (i in 0 until us.size - 1) {
                val corners = arrayOf(intArrayOf(i, j), intArrayOf(i + 1, j), intArrayOf(i + 1, j + 1), intArrayOf(i, j + 1))
                val order = if ((axis == 1) xor (sign < 0)) intArrayOf(0, 3, 2, 0, 2, 1) else intArrayOf(0, 1, 2, 0, 2, 3)
                for (k in order) {
                    val (ci, cj) = corners[k].let { it[0] to it[1] }
                    c[axis] = sign * when (axis) { 0 -> hx; 1 -> hy; else -> hz }
                    c[ua] = us[ci]; c[va] = vs[cj]
                    round(c[0], c[1], c[2])
                    var fu = (us[ci] + hu) / (2 * hu)
                    var fv = 1f - (vs[cj] + hv) / (2 * hv)
                    if (axis == 1) fv = 1f - fv
                    if (flipU) fu = 1f - fu
                    if (flipV) fv = 1f - fv
                    vertex(cx + pos[0], cy + pos[1], cz + pos[2], nrm[0], nrm[1], nrm[2], color, reg.u(fu), reg.v(fv))
                }
            }
        }
    }

    /** Extrudes a polygon (x, y; triangulated as a fan) between z = [z0] and [z1]. */
    fun prism(xs: FloatArray, ys: FloatArray, z0: Float, z1: Float, color: Int) {
        val n = xs.size
        if (n < 3) return
        // Work out the winding so the front faces point toward +z.
        var area = 0f
        for (i in 0 until n) { val j = (i + 1) % n; area += xs[i] * ys[j] - xs[j] * ys[i] }
        val ccw = area > 0
        val r = Atlas.white
        val u = r.u(0.5f); val v = r.v(0.5f)
        for (i in 1 until n - 1) {
            val a = 0; val b = if (ccw) i else i + 1; val c = if (ccw) i + 1 else i
            vertex(xs[a], ys[a], z1, 0f, 0f, 1f, color, u, v)
            vertex(xs[b], ys[b], z1, 0f, 0f, 1f, color, u, v)
            vertex(xs[c], ys[c], z1, 0f, 0f, 1f, color, u, v)
            vertex(xs[a], ys[a], z0, 0f, 0f, -1f, color, u, v)
            vertex(xs[c], ys[c], z0, 0f, 0f, -1f, color, u, v)
            vertex(xs[b], ys[b], z0, 0f, 0f, -1f, color, u, v)
        }
        for (i in 0 until n) {
            val j = (i + 1) % n
            val (a, b) = if (ccw) i to j else j to i
            quad(xs[a], ys[a], z1, xs[a], ys[a], z0, xs[b], ys[b], z0, xs[b], ys[b], z1, color)
        }
    }

    /** Flat disc facing +y. */
    fun disc(cx: Float, y: Float, cz: Float, rx: Float, rz: Float, color: Int, slices: Int = 20) {
        val r = Atlas.white
        for (i in 0 until slices) {
            val a0 = 2 * PI.toFloat() * i / slices
            val a1 = 2 * PI.toFloat() * (i + 1) / slices
            vertex(cx, y, cz, 0f, 1f, 0f, color, r.u(0.5f), r.v(0.5f))
            vertex(cx + cos(a0) * rx, y, cz - sin(a0) * rz, 0f, 1f, 0f, color, r.u(0.5f), r.v(0.5f))
            vertex(cx + cos(a1) * rx, y, cz - sin(a1) * rz, 0f, 1f, 0f, color, r.u(0.5f), r.v(0.5f))
        }
    }
}

/** Unit primitives shared by everything that just needs "a box" or "a ball". */
object Prims {
    /** Box from -0.5 to 0.5 on every axis. */
    val cube: Mesh by lazy { MeshBuilder().apply { box(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, WHITE) }.build() }
    /** Sphere of radius 1. */
    val ball: Mesh by lazy { MeshBuilder().apply { sphere(0f, 0f, 0f, 1f, WHITE, 12, 8) }.build() }
    /** A lighter sphere for small round shapes, where the extra detail can't be seen anyway. */
    val smallBall: Mesh by lazy { MeshBuilder().apply { sphere(0f, 0f, 0f, 1f, WHITE, 8, 5) }.build() }

    /** The right sphere for something [size] pixels across. */
    fun ballFor(size: Float) = if (size < 14f) smallBall else ball

    /** Cylinder of radius 1 from y = 0 to y = 1. */
    val tube: Mesh by lazy { MeshBuilder().apply { cylinder(0f, 0f, 0f, 1f, 1f, WHITE, slices = 18) }.build() }
    /** Flat disc of radius 1 facing up (used for blob shadows). */
    val disc: Mesh by lazy { MeshBuilder().apply { disc(0f, 0f, 0f, 1f, 1f, WHITE, 24) }.build() }

    /** Flat disc of radius 1 facing the camera (+z), for cheap round shadows. */
    val faceDisc: Mesh by lazy {
        MeshBuilder().apply { place { rotateX(kotlin.math.PI.toFloat() / 2) }; disc(0f, 0f, 0f, 1f, 1f, WHITE, 20) }.build()
    }

    const val WHITE = -1
}
