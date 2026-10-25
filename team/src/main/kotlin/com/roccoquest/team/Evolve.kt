package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.argb
import kotlin.math.sin

/**
 * Draws through to [inner] with every color moved part of the way ([k]) toward [target]. Very dark and
 * very light colors (eyes, outlines, shines) stay as they are, so faces still look right.
 */
class TintGfx(internal val inner: Gfx, private val target: Int, private val k: Float) : Gfx {
    private fun tint(c: Int): Int {
        val a = (c ushr 24) and 255
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        val lum = (r * 3 + g * 6 + b) / 10
        if (lum < 40 || lum > 225) return c
        fun mix(v: Int, t: Int) = (v + (t - v) * k).toInt().coerceIn(0, 255)
        return argb(a, (mix(r, (target shr 16) and 255) shl 16) or (mix(g, (target shr 8) and 255) shl 8) or mix(b, target and 255))
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.rect(x, y, w, h, tint(color))
    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.oval(x, y, w, h, tint(color))
    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) = inner.poly(xs, ys, tint(color))
    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) = inner.sprite(s, x, y, flipX, flipY, w, h)
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) = inner.text(s, x, y, size, color, align)
}

/** The evolved Pokemon: their first form drawn bigger, in new colors, with some extra parts. */
object Evolve {
    fun draw(gfx: Gfx, m: Mon, cx: Float, bottom: Float, t: Float, facing: Int, glow: Boolean, walking: Boolean) {
        val f = facing.toFloat()
        fun baseAs(base: Mon, color: Int, k: Float, scale: Float) =
            TeamArt.mon(ScaledGfx(TintGfx(gfx, color, k), scale, cx, bottom), base, 0f, 0f, t, facing, false, walking, outline = false)
        when (m) {
            Mon.SCORCH -> {
                baseAs(Mon.EMBER, 0xFFD8402A.toInt(), 0.45f, 1.25f)
                // A horn on the back of its head
                gfx.poly(floatArrayOf(cx - f * 4, cx - f * 12, cx - f * 3), floatArrayOf(bottom - 24, bottom - 30, bottom - 20), 0xFFB8301A.toInt())
            }
            Mon.TIDAL -> {
                // Fluffy white ears and tail
                gfx.oval(cx - f * 10 - 5, bottom - 26 + sin(t * 4), 10f, 7f, 0xFFF4F8FF.toInt())
                gfx.oval(cx - f * 13 - 5, bottom - 10, 12f, 8f, 0xFFF4F8FF.toInt())
                baseAs(Mon.SPLASHY, 0xFF3A5AD8.toInt(), 0.35f, 1.2f)
            }
            Mon.SEEDY -> {
                baseAs(Mon.BLOOM, 0xFF5AC8A8.toInt(), 0.35f, 0.7f)
                gfx.oval(cx - f * 3 - 5, bottom - 17, 10f, 8f, 0xFF3CA84A.toInt())
            }
            Mon.SPROUT -> {
                baseAs(Mon.BLOOM, 0xFF4AB890.toInt(), 0.2f, 0.85f)
                // A pink bud about to open
                gfx.oval(cx - f * 3 - 4, bottom - 25, 8f, 9f, 0xFFFF7AA8.toInt())
                gfx.poly(floatArrayOf(cx - f * 3 - 6, cx - f * 3, cx - f * 3 + 6), floatArrayOf(bottom - 18, bottom - 24, bottom - 18), 0xFF2E9A3A.toInt())
            }
            Mon.ZAPPER -> {
                baseAs(Mon.SPARKY, 0xFFF08A2A.toInt(), 0.55f, 1.2f)
                // A long thin tail with a bolt on the end
                val tx = cx - f * 12
                gfx.rect(if (f > 0) tx - 10 else tx, bottom - 6, 10f, 1.5f, 0xFF3A2A1A.toInt())
                gfx.poly(floatArrayOf(tx - f * 10, tx - f * 16, tx - f * 12, tx - f * 18), floatArrayOf(bottom - 7, bottom - 12, bottom - 12, bottom - 18), 0xFFFFD21F.toInt())
            }
            Mon.BRAWLER -> {
                baseAs(Mon.PUNCHO, 0xFF7A8AA8.toInt(), 0.25f, 1.2f)
                gfx.rect(cx - 7, bottom - 12, 14f, 2.5f, 0xFFFFD21F.toInt())
            }
            Mon.MEGAPUNCH -> {
                // Two extra arms!
                gfx.oval(cx - 16, bottom - 22 + sin(t * 8) * 2, 9f, 7f, 0xFF6A7A98.toInt())
                gfx.oval(cx + 7, bottom - 22 - sin(t * 8) * 2, 9f, 7f, 0xFF6A7A98.toInt())
                baseAs(Mon.PUNCHO, 0xFF6A7A98.toInt(), 0.3f, 1.4f)
                gfx.rect(cx - 9, bottom - 15, 18f, 3f, 0xFFFFD21F.toInt())
            }
            Mon.SHADE -> {
                baseAs(Mon.SPOOKY, 0xFF6A3AA8.toInt(), 0.35f, 1.15f)
                // Floating hands
                for (s in intArrayOf(-1, 1)) gfx.oval(cx + s * 13 - 3, bottom - 16 + sin(t * 5 + s) * 3, 6f, 5f, 0xFF4A2A7A.toInt())
            }
            Mon.PHANTOM -> {
                baseAs(Mon.SPOOKY, 0xFF5A2A88.toInt(), 0.6f, 1.35f)
                // Spikes on top and a big grin
                for (k in 0 until 3) gfx.poly(floatArrayOf(cx - 8 + k * 6f, cx - 5 + k * 6f, cx - 2 + k * 6f), floatArrayOf(bottom - 26, bottom - 33, bottom - 26), 0xFF4A1A78.toInt())
                gfx.rect(cx - 5 + f * 2, bottom - 14, 10f, 2.5f, 0xFFF4F4F4.toInt())
            }
            Mon.PSYKO -> {
                baseAs(Mon.MYSTIC, 0xFFB8A0D8.toInt(), 0.5f, 1.35f)
                gfx.oval(cx - 3, bottom - 30, 6f, 6f, argb(if (glow) 220 else 120, 0xC890FF))
            }
            else -> TeamArt.mon(gfx, m, cx, bottom, t, facing, glow, walking, outline = false)
        }
    }
}
