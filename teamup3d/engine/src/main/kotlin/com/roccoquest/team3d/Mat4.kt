package com.roccoquest.team3d

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A 4x4 column-major matrix (the layout OpenGL expects). The chaining helpers
 * post-multiply in place, so `m.identity().translate(..).rotateY(..)` applies the
 * rotation first and the translation last, like a scene-graph transform. Nothing
 * here allocates, so it is safe to use every frame.
 */
class Mat4 {
    val m = FloatArray(16)
    private val tmp = FloatArray(16)

    init { identity() }

    fun identity(): Mat4 {
        m.fill(0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return this
    }

    fun set(o: Mat4): Mat4 { o.m.copyInto(m); return this }

    /** this = this * o */
    fun mul(o: FloatArray): Mat4 {
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += m[k * 4 + r] * o[c * 4 + k]
            tmp[c * 4 + r] = s
        }
        tmp.copyInto(m)
        return this
    }

    fun mul(o: Mat4) = mul(o.m)

    fun translate(x: Float, y: Float, z: Float): Mat4 {
        for (r in 0 until 4) m[12 + r] += m[r] * x + m[4 + r] * y + m[8 + r] * z
        return this
    }

    fun scale(x: Float, y: Float, z: Float): Mat4 {
        for (r in 0 until 4) { m[r] *= x; m[4 + r] *= y; m[8 + r] *= z }
        return this
    }

    fun scale(s: Float) = scale(s, s, s)

    fun rotateX(a: Float): Mat4 = rot(1, 2, a)
    fun rotateY(a: Float): Mat4 = rot(2, 0, a)
    fun rotateZ(a: Float): Mat4 = rot(0, 1, a)

    /** Rotation in the plane of axes [i] -> [j] by [a] radians. */
    private fun rot(i: Int, j: Int, a: Float): Mat4 {
        val c = cos(a)
        val s = sin(a)
        for (r in 0 until 4) {
            val ci = m[i * 4 + r]
            val cj = m[j * 4 + r]
            m[i * 4 + r] = ci * c + cj * s
            m[j * 4 + r] = cj * c - ci * s
        }
        return this
    }

    fun perspective(fovY: Float, aspect: Float, near: Float, far: Float): Mat4 {
        val f = 1f / tan(fovY / 2f)
        m.fill(0f)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return this
    }

    fun lookAt(ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float): Mat4 {
        var fx = tx - ex; var fy = ty - ey; var fz = tz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= fl; fy /= fl; fz /= fl
        // side = f x up(0,1,0)
        var sx = -fz; val sy = 0f; var sz = fx
        val sl = sqrt(sx * sx + sz * sz)
        sx /= sl; sz /= sl
        // up = s x f
        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx
        m[0] = sx; m[4] = sy; m[8] = sz
        m[1] = ux; m[5] = uy; m[9] = uz
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[3] = 0f; m[7] = 0f; m[11] = 0f
        m[12] = -(sx * ex + sy * ey + sz * ez)
        m[13] = -(ux * ex + uy * ey + uz * ez)
        m[14] = fx * ex + fy * ey + fz * ez
        m[15] = 1f
        return this
    }

    /**
     * Writes the inverse-transpose of the upper 3x3 into [out] (column-major), which is
     * what normals need when a model is scaled unevenly (squashed spheres and so on).
     */
    fun normalMatrix(out: FloatArray) {
        val a = m[0]; val b = m[4]; val c = m[8]
        val d = m[1]; val e = m[5]; val f = m[9]
        val g = m[2]; val h = m[6]; val i = m[10]
        val A = e * i - f * h
        val B = -(d * i - f * g)
        val C = d * h - e * g
        val det = a * A + b * B + c * C
        val inv = if (det == 0f) 1f else 1f / det
        // The cofactor matrix divided by det is the inverse-transpose; stored by column.
        out[0] = A * inv; out[1] = -(b * i - c * h) * inv; out[2] = (b * f - c * e) * inv
        out[3] = B * inv; out[4] = (a * i - c * g) * inv; out[5] = -(a * f - c * d) * inv
        out[6] = C * inv; out[7] = -(a * h - b * g) * inv; out[8] = (a * e - b * d) * inv
    }

    /** Transforms a point; returns clip-space w and writes x, y, z into [out]. */
    fun transform(x: Float, y: Float, z: Float, out: FloatArray): Float {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        return m[3] * x + m[7] * y + m[11] * z + m[15]
    }
}
