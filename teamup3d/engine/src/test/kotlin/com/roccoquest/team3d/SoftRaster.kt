package com.roccoquest.team3d

import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A small software rasteriser that draws a [DrawList] the same way the phone's
 * OpenGL shader does (per-vertex here, per-pixel there), so tests can save screenshots.
 */
class SoftRaster(private val w: Int, private val h: Int) {
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    private val px = IntArray(w * h)
    private val zb = FloatArray(w * h)

    private val mvp = Mat4()
    private val model = Mat4()

    /** Draws [list] over [backdrop] (the painted sky), or over a plain sky gradient. */
    fun render(list: DrawList, backdrop: BufferedImage? = null): BufferedImage {
        if (!list.showWorld) {
            px.fill(0xFF000000.toInt())
        } else {
            if (backdrop != null) {
                backdrop.getRGB(0, 0, w, h, px, 0, w)
            } else for (y in 0 until h) {
                val c = Atlas.mix(list.env.skyTop, list.env.skyBottom, y / (h - 1f))
                for (x in 0 until w) px[y * w + x] = c
            }
            zb.fill(Float.MAX_VALUE)
            for (i in 0 until list.count) draw(list, list.cmd(i))
        }
        image.setRGB(0, 0, w, h, px, 0, w)
        return image
    }

    /** Draws [list] on top of what's already there (for the HUD and menus over a 3D level). */
    fun overlay(list: DrawList): BufferedImage {
        zb.fill(Float.MAX_VALUE)
        for (i in 0 until list.count) draw(list, list.cmd(i))
        image.setRGB(0, 0, w, h, px, 0, w)
        return image
    }

    private var cutout = false
    private var glyphs = false

    private class V { var sx = 0f; var sy = 0f; var z = 0f; var w = 0f; var r = 0f; var g = 0f; var b = 0f; var a = 0f; var u = 0f; var v = 0f }

    private val vs = Array(3) { V() }
    private val tmp = FloatArray(3)

    private fun draw(list: DrawList, c: DrawCmd) {
        c.model.copyInto(model.m)
        mvp.set(list.viewProj).mul(model)
        val d = c.mesh.data
        val env = list.env
        val tr = (c.tint shr 16 and 255) / 255f; val tg = (c.tint shr 8 and 255) / 255f
        val tb = (c.tint and 255) / 255f; val ta = (c.tint ushr 24) / 255f
        val translucent = c.translucent
        cutout = c.cutout
        glyphs = c.texture == DrawList.TEX_GLYPHS
        var i = 0
        while (i < c.mesh.vertexCount) {
            var ok = true
            for (k in 0 until 3) {
                val o = (i + k) * Mesh.STRIDE
                val v = vs[k]
                val cw = mvp.transform(d[o], d[o + 1], d[o + 2], tmp)
                if (cw < 1f) { ok = false; break }
                v.w = cw
                v.sx = (tmp[0] / cw * 0.5f + 0.5f) * w
                v.sy = (0.5f - tmp[1] / cw * 0.5f) * h
                v.z = tmp[2] / cw
                // Lighting, as in the GLSL shader.
                val nm = c.normal
                var nx = nm[0] * d[o + 3] + nm[3] * d[o + 4] + nm[6] * d[o + 5]
                var ny = nm[1] * d[o + 3] + nm[4] * d[o + 4] + nm[7] * d[o + 5]
                var nz = nm[2] * d[o + 3] + nm[5] * d[o + 4] + nm[8] * d[o + 5]
                val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                nx /= nl; ny /= nl; nz /= nl
                model.transform(d[o], d[o + 1], d[o + 2], tmp)
                var vx = list.camPos[0] - tmp[0]; var vy = list.camPos[1] - tmp[1]; var vz = list.camPos[2] - tmp[2]
                val dist = sqrt(vx * vx + vy * vy + vz * vz)
                vx /= dist; vy /= dist; vz /= dist
                v.u = d[o + 10]; v.v = d[o + 11]
                val br = d[o + 6] * tr
                val bg = d[o + 7] * tg
                val bb = d[o + 8] * tb
                val l = env.light
                val diff = max(0f, nx * l[0] + ny * l[1] + nz * l[2])
                val hemi = ny * 0.5f + 0.5f
                fun ch(c: Int, s: Int) = (c shr s and 255) / 255f
                val ar = ch(env.ambientGround, 16) + (ch(env.ambientSky, 16) - ch(env.ambientGround, 16)) * hemi
                val ag = ch(env.ambientGround, 8) + (ch(env.ambientSky, 8) - ch(env.ambientGround, 8)) * hemi
                val ab = ch(env.ambientGround, 0) + (ch(env.ambientSky, 0) - ch(env.ambientGround, 0)) * hemi
                // Specular (Blinn) and a soft rim light.
                var hx = l[0] + vx; var hy = l[1] + vy; var hz = l[2] + vz
                val hl = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-6f)
                hx /= hl; hy /= hl; hz /= hl
                val spec = max(0f, nx * hx + ny * hy + nz * hz).pow(28f) * c.shine
                val rim = (1f - max(0f, nx * vx + ny * vy + nz * vz)).pow(3f) * 0.25f
                var r = br * (ar * 0.75f + diff * ch(env.sun, 16)) + spec + rim * ch(env.ambientSky, 16)
                var g = bg * (ag * 0.75f + diff * ch(env.sun, 8)) + spec + rim * ch(env.ambientSky, 8)
                var b = bb * (ab * 0.75f + diff * ch(env.sun, 0)) + spec + rim * ch(env.ambientSky, 0)
                r += (br - r) * c.emissive; g += (bg - g) * c.emissive; b += (bb - b) * c.emissive
                // Fully glowing things (sun, moon, stars) shine through the haze.
                val f = if (c.emissive >= 0.99f) 0f else ((dist - env.fogNear) / (env.fogFar - env.fogNear)).coerceIn(0f, 1f) * 0.85f
                v.r = r + (ch(env.fog, 16) - r) * f
                v.g = g + (ch(env.fog, 8) - g) * f
                v.b = b + (ch(env.fog, 0) - b) * f
                v.a = d[o + 9] * ta
            }
            if (ok) tri(vs[0], vs[1], vs[2], translucent)
            i += 3
        }
    }

    private fun texel(u: Float, v: Float): Int {
        if (glyphs) {
            val x = (u * Glyphs.SIZE).toInt().coerceIn(0, Glyphs.SIZE - 1)
            val y = (v * Glyphs.SIZE).toInt().coerceIn(0, Glyphs.SIZE - 1)
            return Glyphs.pixels[y * Glyphs.SIZE + x]
        }
        val x = (u * Atlas.SIZE).toInt().coerceIn(0, Atlas.SIZE - 1)
        val y = (v * Atlas.SIZE).toInt().coerceIn(0, Atlas.SIZE - 1)
        return Atlas.pixels[y * Atlas.SIZE + x]
    }

    private fun tri(a: V, b: V, c: V, blend: Boolean) {
        val minX = max(0, min(a.sx, min(b.sx, c.sx)).toInt())
        val maxX = min(w - 1, max(a.sx, max(b.sx, c.sx)).toInt() + 1)
        val minY = max(0, min(a.sy, min(b.sy, c.sy)).toInt())
        val maxY = min(h - 1, max(a.sy, max(b.sy, c.sy)).toInt() + 1)
        val area = (b.sx - a.sx) * (c.sy - a.sy) - (b.sy - a.sy) * (c.sx - a.sx)
        if (area == 0f || minX > maxX || minY > maxY) return
        for (y in minY..maxY) for (x in minX..maxX) {
            val pxf = x + 0.5f; val pyf = y + 0.5f
            val w0 = ((b.sx - pxf) * (c.sy - pyf) - (b.sy - pyf) * (c.sx - pxf)) / area
            val w1 = ((c.sx - pxf) * (a.sy - pyf) - (c.sy - pyf) * (a.sx - pxf)) / area
            val w2 = 1f - w0 - w1
            if (w0 < 0 || w1 < 0 || w2 < 0) continue
            val z = a.z * w0 + b.z * w1 + c.z * w2
            val idx = y * w + x
            if (z >= zb[idx]) continue
            // Texture is applied per pixel (an approximation: it also scales the highlights).
            val tex = texel(a.u * w0 + b.u * w1 + c.u * w2, a.v * w0 + b.v * w1 + c.v * w2)
            if (cutout && (tex ushr 24) < 128) continue
            val r = ((a.r * w0 + b.r * w1 + c.r * w2) * (tex shr 16 and 255) / 255f).coerceIn(0f, 1f)
            val g = ((a.g * w0 + b.g * w1 + c.g * w2) * (tex shr 8 and 255) / 255f).coerceIn(0f, 1f)
            val bl = ((a.b * w0 + b.b * w1 + c.b * w2) * (tex and 255) / 255f).coerceIn(0f, 1f)
            if (blend) {
                val al = ((a.a * w0 + b.a * w1 + c.a * w2) * (tex ushr 24) / 255f).coerceIn(0f, 1f)
                if (al <= 0.01f) continue
                val dst = px[idx]
                fun mixc(s: Float, sh: Int) = ((dst shr sh and 255) * (1 - al) + s * 255 * al).toInt().coerceIn(0, 255)
                px[idx] = (0xFF shl 24) or (mixc(r, 16) shl 16) or (mixc(g, 8) shl 8) or mixc(bl, 0)
            } else {
                zb[idx] = z
                px[idx] = (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (bl * 255).toInt()
            }
        }
    }
}
