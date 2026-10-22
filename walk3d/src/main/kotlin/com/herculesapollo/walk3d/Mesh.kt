package com.herculesapollo.walk3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A triangle mesh: [STRIDE] floats per vertex (x, y, z, nx, ny, nz, r, g, b), 3 vertices
 * per triangle, counter-clockwise seen from the front. The vertex color multiplies the
 * draw color (the unit shapes are white). [ground] marks the terrain, which is drawn
 * pushed slightly back so [decal] meshes (paths, water) lying on it never flicker.
 * [handle] lets a platform keep its GPU copy.
 */
class Mesh(val data: FloatArray, val ground: Boolean = false, val decal: Boolean = false) {
    val vertexCount get() = data.size / STRIDE
    var handle: Any? = null

    companion object {
        const val STRIDE = 9
    }
}

/**
 * Bakes many shapes into one mesh, each moved by its model matrix and tinted, so a
 * whole level's scenery can be drawn in one go.
 */
class MeshBatch {
    private var data = FloatArray(1 shl 16)
    private var size = 0
    private val nrm = FloatArray(9)
    private val p = FloatArray(4)

    private fun put(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, color: Int) {
        put(x); put(y); put(z); put(nx); put(ny); put(nz)
        put((color shr 16 and 0xFF) / 255f); put((color shr 8 and 0xFF) / 255f); put((color and 0xFF) / 255f)
    }

    /** Adds [mesh] transformed by [model] and tinted [color]. */
    fun add(mesh: Mesh, model: FloatArray, color: Int) {
        Mat4.normalMatrix(model, nrm)
        val d = mesh.data
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        var i = 0
        while (i < d.size) {
            Mat4.transform(model, d[i], d[i + 1], d[i + 2], p)
            val nx = nrm[0] * d[i + 3] + nrm[3] * d[i + 4] + nrm[6] * d[i + 5]
            val ny = nrm[1] * d[i + 3] + nrm[4] * d[i + 4] + nrm[7] * d[i + 5]
            val nz = nrm[2] * d[i + 3] + nrm[5] * d[i + 4] + nrm[8] * d[i + 5]
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            put(p[0]); put(p[1]); put(p[2]); put(nx / l); put(ny / l); put(nz / l)
            put(d[i + 6] * r); put(d[i + 7] * g); put(d[i + 8] * b)
            i += Mesh.STRIDE
        }
    }

    fun build(ground: Boolean = false, decal: Boolean = false) = Mesh(data.copyOf(size), ground, decal)
}

/** Low-poly unit shapes that everything in the 3D world is built from. */
object Meshes {
    private class Builder(val cx: Float, val cy: Float, val cz: Float) {
        val out = ArrayList<Float>()

        /** Adds a triangle facing away from the builder's center. Null normals mean flat shading. */
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray, na: FloatArray? = null, nb: FloatArray? = null, nc: FloatArray? = null) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val mx = (a[0] + b[0] + c[0]) / 3f - cx
            val my = (a[1] + b[1] + c[1]) / 3f - cy
            val mz = (a[2] + b[2] + c[2]) / 3f - cz
            val flip = nx * mx + ny * my + nz * mz < 0f
            if (flip) { nx = -nx; ny = -ny; nz = -nz }
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            val flat = floatArrayOf(nx / l, ny / l, nz / l)
            val verts = if (flip) listOf(a to (na ?: flat), c to (nc ?: flat), b to (nb ?: flat))
            else listOf(a to (na ?: flat), b to (nb ?: flat), c to (nc ?: flat))
            for ((p, n) in verts) {
                out += p[0]; out += p[1]; out += p[2]; out += n[0]; out += n[1]; out += n[2]
                out += 1f; out += 1f; out += 1f
            }
        }

        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) { tri(a, b, c); tri(a, c, d) }

        fun mesh() = Mesh(out.toFloatArray())
    }

    private fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    private fun p(x: Double, y: Double, z: Double) = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())

    /** x and z from -0.5 to 0.5, y from 0 to 1, so it sits on the ground. */
    val box: Mesh = Builder(0f, 0.5f, 0f).apply {
        val h = 0.5f
        val c = arrayOf(
            p(-h, 0f, -h), p(h, 0f, -h), p(h, 0f, h), p(-h, 0f, h),
            p(-h, 1f, -h), p(h, 1f, -h), p(h, 1f, h), p(-h, 1f, h),
        )
        quad(c[0], c[1], c[2], c[3]); quad(c[4], c[5], c[6], c[7])
        quad(c[0], c[1], c[5], c[4]); quad(c[1], c[2], c[6], c[5])
        quad(c[2], c[3], c[7], c[6]); quad(c[3], c[0], c[4], c[7])
    }.mesh()

    /** Radius 0.5, y from 0 to 1. */
    val cylinder: Mesh = Builder(0f, 0.5f, 0f).apply {
        val n = 10
        for (i in 0 until n) {
            val a0 = 2 * PI * i / n; val a1 = 2 * PI * (i + 1) / n
            val x0 = 0.5 * cos(a0); val z0 = 0.5 * sin(a0)
            val x1 = 0.5 * cos(a1); val z1 = 0.5 * sin(a1)
            val n0 = p(cos(a0), 0.0, sin(a0)); val n1 = p(cos(a1), 0.0, sin(a1))
            tri(p(x0, 0.0, z0), p(x1, 0.0, z1), p(x1, 1.0, z1), n0, n1, n1)
            tri(p(x0, 0.0, z0), p(x1, 1.0, z1), p(x0, 1.0, z0), n0, n1, n0)
            tri(p(0f, 1f, 0f), p(x0, 1.0, z0), p(x1, 1.0, z1))
            tri(p(0f, 0f, 0f), p(x0, 0.0, z0), p(x1, 0.0, z1))
        }
    }.mesh()

    /** Radius 0.5 around the origin, a chunky 8 x 6 ball. */
    val sphere: Mesh = Builder(0f, 0f, 0f).apply {
        val lon = 8
        val lat = 6
        fun v(i: Int, j: Int): FloatArray {
            val th = PI * j / lat
            val ph = 2 * PI * i / lon
            return p(0.5 * sin(th) * cos(ph), 0.5 * cos(th), 0.5 * sin(th) * sin(ph))
        }
        fun nrm(q: FloatArray) = floatArrayOf(q[0] * 2f, q[1] * 2f, q[2] * 2f)
        for (j in 0 until lat) for (i in 0 until lon) {
            val a = v(i, j); val b = v(i + 1, j); val c = v(i + 1, j + 1); val d = v(i, j + 1)
            if (j != 0) tri(a, b, c, nrm(a), nrm(b), nrm(c))
            if (j != lat - 1) tri(a, c, d, nrm(a), nrm(c), nrm(d))
        }
    }.mesh()

    /** Base radius 0.5 at y = 0, point at y = 1. */
    val cone: Mesh = Builder(0f, 0.3f, 0f).apply {
        val n = 10
        for (i in 0 until n) {
            val a0 = 2 * PI * i / n; val a1 = 2 * PI * (i + 1) / n
            val b0 = p(0.5 * cos(a0), 0.0, 0.5 * sin(a0)); val b1 = p(0.5 * cos(a1), 0.0, 0.5 * sin(a1))
            tri(b0, b1, p(0f, 1f, 0f))
            tri(b0, b1, p(0f, 0f, 0f))
        }
    }.mesh()

    /** A flat disc, radius 0.5, facing up. For shadows, holes and rugs. */
    val disc: Mesh = Builder(0f, -1f, 0f).apply {
        val n = 12
        for (i in 0 until n) {
            val a0 = 2 * PI * i / n; val a1 = 2 * PI * (i + 1) / n
            tri(p(0f, 0f, 0f), p(0.5 * cos(a0), 0.0, 0.5 * sin(a0)), p(0.5 * cos(a1), 0.0, 0.5 * sin(a1)))
        }
    }.mesh()

    /** A flat square, -0.5 to 0.5 in x and z, facing up. */
    val plane: Mesh = Builder(0f, -1f, 0f).apply {
        quad(p(-0.5f, 0f, -0.5f), p(0.5f, 0f, -0.5f), p(0.5f, 0f, 0.5f), p(-0.5f, 0f, 0.5f))
    }.mesh()

    /** A roof: a triangular prism with its ridge along z at y = 1. */
    val roof: Mesh = Builder(0f, 0.3f, 0f).apply {
        val h = 0.5f
        val l0 = p(-h, 0f, -h); val r0 = p(h, 0f, -h); val t0 = p(0f, 1f, -h)
        val l1 = p(-h, 0f, h); val r1 = p(h, 0f, h); val t1 = p(0f, 1f, h)
        tri(l0, r0, t0); tri(l1, r1, t1)
        quad(l0, t0, t1, l1); quad(r0, t0, t1, r1); quad(l0, r0, r1, l1)
    }.mesh()

    val all get() = listOf(box, cylinder, sphere, cone, disc, plane, roof)
}
