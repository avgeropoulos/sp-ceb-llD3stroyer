package com.bouzouki

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Procedural textures for the bouzouki neck: the ebony fingerboard, the spruce top, the
 * leather the instrument lies on and mother-of-pearl for the inlays. Each is drawn pixel by
 * pixel once (a few hundred milliseconds, so off the UI thread), then cached for the process
 * and shared by every [FretboardView] of the same width.
 */
internal object NeckArt {
    /** Everything a neck needs, for a fingerboard [woodW] and a spruce top [spruceW] pixels wide. */
    class Textures(
        val woodW: Int, val spruceW: Int,
        val wood: Bitmap, val spruce: Bitmap, val leather: Bitmap, val pearl: Bitmap, val abalone: Bitmap,
    )

    private const val WOOD_H = 384   // texture heights; both are mirrored down the neck
    private const val SPRUCE_H = 256
    @Volatile private var made: Textures? = null
    private val making = Any()

    /** The textures for these widths if they are already made. Never blocks, so the UI thread may ask. */
    fun cached(woodW: Int, spruceW: Int): Textures? = made?.takeIf { it.woodW == woodW && it.spruceW == spruceW }

    /** Makes the textures (reusing what fits); slow the first time, so call it from a background thread. */
    fun textures(woodW: Int, spruceW: Int): Textures = synchronized(making) {
        cached(woodW, spruceW)?.let { return it }
        val old = made
        Textures(
            woodW, spruceW,
            old?.takeIf { it.woodW == woodW }?.wood ?: fingerboard(woodW),
            old?.takeIf { it.spruceW == spruceW }?.spruce ?: spruceTop(spruceW),
            old?.leather ?: leather(), old?.pearl ?: pearl(), old?.abalone ?: abalone(),
        ).also { made = it }
    }

    /**
     * Dark ebony with long wandering streaks and pores along the neck; mirror it vertically.
     * The board is slightly rounded, so it is darker at the edges with a soft sheen left of centre.
     */
    private fun fingerboard(w: Int): Bitmap {
        val h = WOOD_H
        val px = IntArray(w * h)
        val light = FloatArray(w) { x -> sheen(x / (w - 1f)) }
        for (y in 0 until h) for (x in 0 until w) {
            val warp = 22f * fbm(x * 0.006f, y * 0.0022f, 11, 2)
            val gx = x + warp
            val streak = fbm(gx * 0.05f, y * 0.0028f, 3, 4)
            val fibre = noise(gx * 0.55f, y * 0.018f, 5)
            val pore = noise(gx * 0.9f, y * 0.07f, 9)
            var t = (streak - 0.5f) * 2.1f + (fibre - 0.5f) * 0.45f
            t = (0.5f + t * 0.5f).coerceIn(0f, 1f)
            // Ebony is near black with chocolate and faintly red streaks.
            var r = lerp(14f, 66f, t * t)
            var g = lerp(9f, 40f, t * t)
            var b = lerp(6f, 25f, t * t)
            if (pore > 0.83f) { val k = 1f - (pore - 0.83f) * 2.6f; r *= k; g *= k; b *= k }
            val k = light[x]
            px[y * w + x] = rgb(r * k + (k - 1f).coerceAtLeast(0f) * 60f, g * k + (k - 1f).coerceAtLeast(0f) * 52f, b * k + (k - 1f).coerceAtLeast(0f) * 40f)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Honey spruce with fine straight grain lines and faint silk; mirror it vertically. */
    private fun spruceTop(w: Int): Bitmap {
        val h = SPRUCE_H
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val wander = 18f * fbm(x * 0.004f, y * 0.003f, 21, 2)
            val phase = (x + wander) * 0.19f + 2.5f * noise(x * 0.02f, 0.5f, 23)
            val line = (0.5f + 0.5f * cos(2f * PI.toFloat() * phase)).pow(10)
            val tone = fbm(x * 0.02f, y * 0.006f, 29, 3)
            val silk = noise(x * 0.08f, y * 0.9f, 31)
            var r = 224f - 26f * line - 22f * tone
            var g = 189f - 30f * line - 22f * tone
            var b = 134f - 34f * line - 18f * tone
            if (silk > 0.85f) { r += 8f; g += 7f; b += 5f }
            px[y * w + x] = rgb(r, g, b)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Dark brown pebbled leather, a seamless tile. */
    private fun leather(): Bitmap {
        val n = 128
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val pebble = fbm(x * 0.125f, y * 0.125f, 41, 3, 16)
            val crease = abs(noise(x * 0.0625f, y * 0.0625f, 43, 8) - 0.5f)
            val grain = hash(x, y, 47)
            val k = 0.8f + 0.34f * pebble - 0.16f * (0.12f - crease).coerceAtLeast(0f) * 8f + 0.06f * grain
            px[y * n + x] = rgb(44f * k, 30f * k, 21f * k)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** White mother-of-pearl with a pastel rainbow shimmer; mirror it both ways. */
    private fun pearl() = shell(saturation = 0.13f, value = 0.88f, hueFrom = 0f, hueSpan = 360f, seed = 51)

    /** Green, blue and violet abalone; mirror it both ways. */
    private fun abalone() = shell(saturation = 0.62f, value = 0.55f, hueFrom = 140f, hueSpan = 170f, seed = 61)

    private fun shell(saturation: Float, value: Float, hueFrom: Float, hueSpan: Float, seed: Int): Bitmap {
        val n = 160
        val px = IntArray(n * n)
        val hsv = FloatArray(3)
        for (y in 0 until n) for (x in 0 until n) {
            val f = fbm(x * 0.025f, y * 0.03f, seed, 3)
            val layers = 0.5f + 0.5f * sin(f * 34f + x * 0.05f)
            val fleck = noise(x * 0.3f, y * 0.3f, seed + 3)
            hsv[0] = (hueFrom + hueSpan * ((f * 2.3f + y * 0.002f) % 1f)) % 360f
            hsv[1] = (saturation * (0.45f + 0.75f * layers)).coerceIn(0f, 1f)
            hsv[2] = (value + (1f - value) * (0.35f + 0.65f * layers) + 0.06f * (fleck - 0.5f)).coerceIn(0f, 1f)
            px[y * n + x] = Color.HSVToColor(hsv)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Light across a gently rounded fingerboard, 0..1 from edge to edge: dark rims, a sheen at a third. */
    private fun sheen(u: Float): Float {
        val rim = (1f - 4f * u * (1f - u)).pow(3)            // 1 at the edges, 0 in the middle
        val glint = kotlin.math.exp(-((u - 0.34f) / 0.12f).pow(2))
        return 1f - 0.55f * rim + 0.22f * glint - 0.12f * u // light from the left
    }

    private fun rgb(r: Float, g: Float, b: Float) =
        (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun lattice(i: Int, j: Int, seed: Int, period: Int) =
        if (period > 0) hash(Math.floorMod(i, period), Math.floorMod(j, period), seed) else hash(i, j, seed)

    /** Smooth value noise in 0..1; with [period] > 0 it tiles every [period] lattice cells. */
    private fun noise(x: Float, y: Float, seed: Int, period: Int = 0): Float {
        val xf = floor(x)
        val yf = floor(y)
        val xi = xf.toInt()
        val yi = yf.toInt()
        val tx = x - xf
        val ty = y - yf
        val sx = tx * tx * (3 - 2 * tx)
        val sy = ty * ty * (3 - 2 * ty)
        val a = lattice(xi, yi, seed, period)
        val b = lattice(xi + 1, yi, seed, period)
        val c = lattice(xi, yi + 1, seed, period)
        val d = lattice(xi + 1, yi + 1, seed, period)
        return lerp(lerp(a, b, sx), lerp(c, d, sx), sy)
    }

    /** A few octaves of [noise], each twice as fine and half as strong. */
    private fun fbm(x: Float, y: Float, seed: Int, octaves: Int, period: Int = 0): Float {
        var sum = 0f
        var amp = 0.5f
        var norm = 0f
        var f = 1f
        var p = period
        for (o in 0 until octaves) {
            sum += amp * noise(x * f, y * f, seed + o * 17, p)
            norm += amp
            amp *= 0.5f
            f *= 2f
            p *= 2
        }
        return sum / norm
    }
}
