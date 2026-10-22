package com.herculesapollo.walk3d

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 4x4 matrices stored column-major in a FloatArray(16), the way OpenGL wants them.
 * The in-place operations post-multiply (m = m * op), so a chain like
 * translate, rotateY, scale builds a model matrix the usual way round.
 */
object Mat4 {
    fun identity(m: FloatArray = FloatArray(16)): FloatArray {
        m.fill(0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return m
    }

    fun copy(src: FloatArray, dst: FloatArray): FloatArray {
        src.copyInto(dst)
        return dst
    }

    /** out = a * b. [out] may not be [a] or [b]. */
    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray): FloatArray {
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = s
        }
        return out
    }

    fun translate(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        for (r in 0 until 4) m[12 + r] += m[r] * x + m[4 + r] * y + m[8 + r] * z
        return m
    }

    fun scale(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        for (r in 0 until 4) { m[r] *= x; m[4 + r] *= y; m[8 + r] *= z }
        return m
    }

    /** Rotates about the y (up) axis; positive [a] turns +z toward +x. */
    fun rotateY(m: FloatArray, a: Float): FloatArray {
        val c = cos(a); val s = sin(a)
        for (r in 0 until 4) {
            val x = m[r]; val z = m[8 + r]
            m[r] = x * c - z * s
            m[8 + r] = x * s + z * c
        }
        return m
    }

    fun rotateX(m: FloatArray, a: Float): FloatArray {
        val c = cos(a); val s = sin(a)
        for (r in 0 until 4) {
            val y = m[4 + r]; val z = m[8 + r]
            m[4 + r] = y * c + z * s
            m[8 + r] = -y * s + z * c
        }
        return m
    }

    fun rotateZ(m: FloatArray, a: Float): FloatArray {
        val c = cos(a); val s = sin(a)
        for (r in 0 until 4) {
            val x = m[r]; val y = m[4 + r]
            m[r] = x * c + y * s
            m[4 + r] = -x * s + y * c
        }
        return m
    }

    fun perspective(m: FloatArray, fovY: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(fovY / 2f)
        m.fill(0f)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return m
    }

    fun lookAt(m: FloatArray, ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float): FloatArray {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= fl; fy /= fl; fz /= fl
        // side = f x up (up = +y)
        var sx = -fz; var sz = fx
        val sl = sqrt(sx * sx + sz * sz)
        sx /= sl; sz /= sl
        // u = s x f
        val ux = -sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy
        m[0] = sx; m[4] = 0f; m[8] = sz
        m[1] = ux; m[5] = uy; m[9] = uz
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[3] = 0f; m[7] = 0f; m[11] = 0f
        m[12] = -(sx * ex + sz * ez)
        m[13] = -(ux * ex + uy * ey + uz * ez)
        m[14] = fx * ex + fy * ey + fz * ez
        m[15] = 1f
        return m
    }

    /** The inverse-transpose of [m]'s upper 3x3 (for transforming normals), into a FloatArray(9). */
    fun normalMatrix(m: FloatArray, out: FloatArray): FloatArray {
        val a = m[0]; val b = m[4]; val c = m[8]
        val d = m[1]; val e = m[5]; val f = m[9]
        val g = m[2]; val h = m[6]; val i = m[10]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val D = -(b * i - c * h); val E = a * i - c * g; val F = -(a * h - b * g)
        val G = b * f - c * e; val H = -(a * f - c * d); val I = a * e - b * d
        val det = a * A + b * B + c * C
        val inv = if (det == 0f) 0f else 1f / det
        // The inverse is the transposed cofactor matrix over det, so the inverse-transpose
        // is the cofactor matrix itself over det. Stored column-major.
        out[0] = A * inv; out[1] = D * inv; out[2] = G * inv
        out[3] = B * inv; out[4] = E * inv; out[5] = H * inv
        out[6] = C * inv; out[7] = F * inv; out[8] = I * inv
        return out
    }

    /** Transforms the point (x, y, z, 1) by [m] into [out] (x, y, z, w). */
    fun transform(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        for (r in 0 until 4) out[r] = m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r]
    }
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Wraps an angle into -PI..PI. */
fun wrapAngle(a: Float): Float {
    var x = a
    val tau = (Math.PI * 2).toFloat()
    while (x > Math.PI) x -= tau
    while (x < -Math.PI) x += tau
    return x
}

/** Turns angle [a] toward [b] by at most [step] radians, the short way round. */
fun turnToward(a: Float, b: Float, step: Float): Float {
    val d = wrapAngle(b - a)
    return if (kotlin.math.abs(d) <= step) b else a + step * kotlin.math.sign(d)
}
