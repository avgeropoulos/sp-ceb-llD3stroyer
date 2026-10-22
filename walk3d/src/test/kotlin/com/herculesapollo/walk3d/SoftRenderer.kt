package com.herculesapollo.walk3d

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.sqrt

/**
 * A tiny software renderer for test screenshots: flat-shaded triangles sorted back to
 * front (painter's algorithm), with the same lighting and fog as the phone's shader.
 */
object SoftRenderer {
    private class Tri(val xs: FloatArray, val ys: FloatArray, val depth: Float, val color: Int)

    fun render(frame: Frame, img: BufferedImage) {
        val w = img.width
        val h = img.height
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        for (y in 0 until h step 4) {
            val t = y.toFloat() / h
            g.color = Color(mix(frame.skyTop, frame.fogColor, (t * 2f).coerceAtMost(1f)), true)
            g.fillRect(0, y, w, 4)
        }
        val tris = ArrayList<Tri>()
        val wp = Array(3) { FloatArray(4) }
        val cp = Array(3) { FloatArray(4) }
        for (item in frame.items) {
            val d = if (item.mesh === Meshes.plane && (item.model[0] > 8f || item.model[10] > 8f)) tiles else item.mesh.data
            val ground = (item.mesh === Meshes.plane || item.mesh === Meshes.disc) && item.model[13] < 0.1f
            val st = Mesh.STRIDE
            var i = 0
            while (i < d.size) {
                var tr = 0f; var tg = 0f; var tb = 0f
                for (k in 0 until 3) {
                    val o = i + k * st
                    tr += d[o + 6] / 3f; tg += d[o + 7] / 3f; tb += d[o + 8] / 3f
                    Mat4.transform(item.model, d[o], d[o + 1], d[o + 2], wp[k])
                    Mat4.transform(frame.viewProj, wp[k][0], wp[k][1], wp[k][2], cp[k])
                }
                i += 3 * st
                // Face normal from the world-space triangle.
                val ux = wp[1][0] - wp[0][0]; val uy = wp[1][1] - wp[0][1]; val uz = wp[1][2] - wp[0][2]
                val vx = wp[2][0] - wp[0][0]; val vy = wp[2][1] - wp[0][1]; val vz = wp[2][2] - wp[0][2]
                var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
                val nl = sqrt(nx * nx + ny * ny + nz * nz)
                if (nl < 1e-9f) continue
                nx /= nl; ny /= nl; nz /= nl
                val cx = (wp[0][0] + wp[1][0] + wp[2][0]) / 3f
                val cy = (wp[0][1] + wp[1][1] + wp[2][1]) / 3f
                val cz = (wp[0][2] + wp[1][2] + wp[2][2]) / 3f
                // Back-face cull: the front faces the camera.
                if (nx * (frame.eyeX - cx) + ny * (frame.eyeY - cy) + nz * (frame.eyeZ - cz) <= 0f) continue
                val poly = clipNear(cp) ?: continue
                val xs = FloatArray(poly.size) { (poly[it][0] / poly[it][3] * 0.5f + 0.5f) * w }
                val ys = FloatArray(poly.size) { (0.5f - poly[it][1] / poly[it][3] * 0.5f) * h }
                val dist = sqrt((cx - frame.eyeX) * (cx - frame.eyeX) + (cy - frame.eyeY) * (cy - frame.eyeY) + (cz - frame.eyeZ) * (cz - frame.eyeZ))
                val light = (frame.ambient + frame.sun * maxOf(0f, nx * frame.light[0] + ny * frame.light[1] + nz * frame.light[2])).coerceAtMost(1.15f)
                val c = item.color
                val tinted = (0xFF shl 24) or
                    (((c shr 16 and 0xFF) * tr).toInt().coerceIn(0, 255) shl 16) or
                    (((c shr 8 and 0xFF) * tg).toInt().coerceIn(0, 255) shl 8) or
                    ((c and 0xFF) * tb).toInt().coerceIn(0, 255)
                val lit = Models.shade(tinted, light)
                val fog = ((dist - frame.fogNear) / (frame.fogFar - frame.fogNear)).coerceIn(0f, 1f)
                // Ground first, then what lies on it, then everything else back to front.
                val layer = if (item.mesh.ground) 2e6f else if (item.mesh.decal || ground) 1e6f else 0f
                tris += Tri(xs, ys, layer + dist, mix(lit, frame.fogColor, fog))
            }
        }
        tris.sortByDescending { it.depth }
        for (t in tris) {
            val p = Path2D.Float()
            p.moveTo(t.xs[0], t.ys[0])
            for (k in 1 until t.xs.size) p.lineTo(t.xs[k], t.ys[k])
            p.closePath()
            g.color = Color(t.color, true)
            g.fill(p)
            g.draw(p)
        }
        g.dispose()
    }

    /**
     * The unit plane cut into a 12 x 12 grid. Big flat surfaces use it so the fog (worked
     * out once per triangle here) still fades smoothly with distance.
     */
    private val tiles: FloatArray = run {
        val n = 12
        val out = ArrayList<Float>()
        fun v(x: Float, z: Float) { out += x; out += 0f; out += z; out += 0f; out += 1f; out += 0f; out += 1f; out += 1f; out += 1f }
        for (i in 0 until n) for (j in 0 until n) {
            val x0 = -0.5f + i / n.toFloat(); val x1 = x0 + 1f / n
            val z0 = -0.5f + j / n.toFloat(); val z1 = z0 + 1f / n
            v(x0, z0); v(x1, z1); v(x1, z0)
            v(x0, z0); v(x0, z1); v(x1, z1)
        }
        out.toFloatArray()
    }

    /** Clips a clip-space triangle against the near plane (w > 0.2). */
    private fun clipNear(v: Array<FloatArray>): List<FloatArray>? {
        val near = 0.2f
        val out = ArrayList<FloatArray>()
        for (k in 0 until 3) {
            val a = v[k]; val b = v[(k + 1) % 3]
            val ain = a[3] > near; val bin = b[3] > near
            if (ain) out += a.copyOf()
            if (ain != bin) {
                val t = (near - a[3]) / (b[3] - a[3])
                out += FloatArray(4) { a[it] + (b[it] - a[it]) * t }
            }
        }
        return if (out.size >= 3) out else null
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
