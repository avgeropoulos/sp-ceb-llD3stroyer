package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.argb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ============================================================================ "Juice": little touches that make it feel alive
// Dust puffs at the feet, squash and stretch, and the Mario-style circle wipe between screens.

/**
 * Draws everything stretched by [sx] x [sy] around one point (a hero's feet), for squash and
 * stretch. Each hero keeps one and re-aims it every frame ([aim]), so drawing makes nothing new.
 */
class StretchGfx : Gfx {
    private var inner: Gfx = NoGfx
    private var sx = 1f
    private var sy = 1f
    private var px = 0f
    private var py = 0f
    /** Reused corner lists for [poly], one per corner count (most shapes have only a few). */
    private val bufX = arrayOfNulls<FloatArray>(33)
    private val bufY = arrayOfNulls<FloatArray>(33)

    fun aim(inner: Gfx, sx: Float, sy: Float, px: Float, py: Float): StretchGfx {
        this.inner = inner
        this.sx = sx; this.sy = sy
        this.px = px; this.py = py
        return this
    }

    private fun x(v: Float) = px + (v - px) * sx
    private fun y(v: Float) = py + (v - py) * sy

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.rect(x(x), y(y), w * sx, h * sy, color)
    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.oval(x(x), y(y), w * sx, h * sy, color)
    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {
        val n = xs.size
        val ox = if (n < bufX.size) bufX[n] ?: FloatArray(n).also { bufX[n] = it } else FloatArray(n)
        val oy = if (n < bufY.size) bufY[n] ?: FloatArray(n).also { bufY[n] = it } else FloatArray(n)
        for (i in 0 until n) { ox[i] = x(xs[i]); oy[i] = y(ys[i]) }
        inner.poly(ox, oy, color)
    }
    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) =
        inner.sprite(s, x(x), y(y), flipX, flipY, w * sx, h * sy)
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) =
        inner.text(s, x(x), y(y), size * sy, color, align)
    override fun gradient(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) =
        inner.gradient(x(x), y(y), w * sx, h * sy, top, bottom)
}

/**
 * A [Gfx] that can squash and stretch what it draws by itself. Team Up! 3D's PaperGfx does: it scales its
 * 3D shapes, because handing it new (stretched) outlines would make it build new 3D shapes every frame.
 */
interface StretchingGfx : Gfx {
    /** Everything drawn until [unstretch] is scaled by [sx] x [sy] around ([px], [py]). */
    fun stretch(sx: Float, sy: Float, px: Float, py: Float)
    fun unstretch()
}

/** Draws nothing (a [StretchGfx] before it's aimed anywhere). */
private object NoGfx : Gfx {
    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {}
    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {}
    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {}
    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) {}
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {}
}

/** Squash and stretch numbers (see [Hero.stretch]). */
object Squash {
    /** The most a hero gets taller and thinner (jumping up fast). */
    const val MAX_STRETCH = 0.13f
    /** The most a hero gets shorter and wider (a big landing). */
    const val MAX_SQUASH = 0.2f
    /** How wide to draw for a stretch of [s]: thinner when taller, wider when squashed (about the same "amount" of hero). */
    fun widthFor(s: Float) = 1f - s * 0.8f
}

/**
 * Little puffs at the heroes' feet (dust), air bubbles underwater, and twinkles from coins.
 * It's a fixed pool that gets reused (a new puff takes the place of the oldest one), so a long
 * sprint never makes new objects or piles up particles: there are never more than [size].
 */
class Puffs(val size: Int = 28) {
    private val x = FloatArray(size)
    private val y = FloatArray(size)
    private val vx = FloatArray(size)
    private val vy = FloatArray(size)
    private val life = FloatArray(size)
    private val dur = FloatArray(size)
    private val r = FloatArray(size)
    private val color = IntArray(size)
    private val kind = IntArray(size)
    private var next = 0

    /** How many are showing right now (tests check it never grows past [size]). */
    val live: Int get() { var n = 0; for (i in 0 until size) if (life[i] > 0f) n++; return n }

    /** How many of them are dust. */
    val dustLive: Int get() { var n = 0; for (i in 0 until size) if (life[i] > 0f && kind[i] == DUST) n++; return n }

    /** How many dust puffs are within [r] of ([px], [py]) (tests check whose feet the dust came from). */
    fun dustNear(px: Float, py: Float, r: Float): Int {
        var n = 0
        for (i in 0 until size) if (life[i] > 0f && kind[i] == DUST && hypot(x[i] - px, y[i] - py) <= r) n++
        return n
    }

    fun clear() = life.fill(0f)

    private fun add(px: Float, py: Float, pvx: Float, pvy: Float, radius: Float, c: Int, time: Float, k: Int) {
        // Use a free slot if there is one, otherwise the oldest puff makes room.
        var i = next
        for (n in 0 until size) {
            val j = (next + n) % size
            if (life[j] <= 0f) { i = j; break }
        }
        next = (i + 1) % size
        x[i] = px; y[i] = py; vx[i] = pvx; vy[i] = pvy
        r[i] = radius; color[i] = c and 0xFFFFFF; life[i] = time; dur[i] = time; kind[i] = k
    }

    /** A soft dust puff of [radius] at ([px], [py]) drifting at ([pvx], [pvy]). */
    fun dust(px: Float, py: Float, pvx: Float, pvy: Float, radius: Float, c: Int) = add(px, py, pvx, pvy, radius, c, 0.4f, DUST)

    /** An air bubble wobbling up to the surface. */
    fun bubble(px: Float, py: Float, radius: Float) = add(px, py, 0f, -38f, radius, 0xBFE8FF, 1.1f, BUBBLE)

    /** A little twinkle flying out (collecting a coin). */
    fun glint(px: Float, py: Float, pvx: Float, pvy: Float) = add(px, py, pvx, pvy, 3f, 0xFFF4A0, 0.35f, GLINT)

    fun update(dt: Float, waterTop: Float) {
        for (i in 0 until size) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            when (kind[i]) {
                // Dust slows down and floats up a little.
                DUST -> { vx[i] *= 1f - min(1f, 5f * dt); vy[i] *= 1f - min(1f, 3f * dt) }
                BUBBLE -> { x[i] += sin(life[i] * 9f + i) * 12f * dt; if (y[i] < waterTop) life[i] = 0f }
                else -> { vx[i] *= 1f - min(1f, 4f * dt); vy[i] *= 1f - min(1f, 4f * dt) }
            }
        }
    }

    /** Dust and bubbles: drawn behind the heroes, so a puff at their feet never hides their legs. */
    fun drawBehind(gfx: Gfx, ox: Float, mark: (Any) -> Unit) = draw(gfx, ox, mark, glints = false)

    /** Coin twinkles: drawn on top of everything. */
    fun drawFront(gfx: Gfx, ox: Float, mark: (Any) -> Unit) = draw(gfx, ox, mark, glints = true)

    private fun draw(gfx: Gfx, ox: Float, mark: (Any) -> Unit, glints: Boolean) {
        for (i in 0 until size) {
            if (life[i] <= 0f || (kind[i] == GLINT) != glints) continue
            mark(this)
            val k = 1f - life[i] / dur[i]
            val px = x[i] + ox
            val py = y[i]
            when (kind[i]) {
                DUST -> {
                    // Grows as it fades: a soft ball, a little flatter than round, with a lighter top.
                    val rr = r[i] * (0.65f + k * 0.8f)
                    val a = ((1f - k * k) * 210f).toInt().coerceIn(0, 255)
                    gfx.oval(px - rr, py - rr * 1.7f, rr * 2f, rr * 1.7f, argb(a, color[i]))
                    gfx.oval(px - rr * 0.6f, py - rr * 1.55f, rr * 1.2f, rr * 0.85f, argb(a * 3 / 4, 0xFFFFFF))
                }
                BUBBLE -> {
                    val rr = r[i]
                    val a = if (life[i] < 0.2f) (life[i] / 0.2f * 255f).toInt() else 255
                    gfx.oval(px - rr, py - rr, rr * 2f, rr * 2f, argb(a * 140 / 255, color[i]))
                    gfx.oval(px - rr * 0.5f, py - rr * 0.6f, rr * 0.7f, rr * 0.7f, argb(a * 220 / 255, 0xFFFFFF))
                }
                else -> {
                    // A tiny four-point star (a plus with a bright middle), shrinking away.
                    val s = r[i] * (1f - k)
                    val a = ((1f - k) * 255f).toInt().coerceIn(0, 255)
                    gfx.rect(px - s, py - 0.5f, s * 2f, 1f, argb(a, color[i]))
                    gfx.rect(px - 0.5f, py - s, 1f, s * 2f, argb(a, color[i]))
                    gfx.rect(px - 0.75f, py - 0.75f, 1.5f, 1.5f, argb(a, 0xFFFFFF))
                }
            }
        }
    }

    private companion object {
        const val DUST = 0
        const val BUBBLE = 1
        const val GLINT = 2
    }
}

/**
 * The Mario-style circle wipe: black everywhere except a round hole on the hero, which shrinks to
 * nothing (closing) or grows until the whole picture is back (opening). It's drawn as plain bands
 * around the hole's square plus four corner pieces with a curved side, so it's only 8 shapes at
 * any screen width, and it also works over Team Up! 3D's level (drawn with the HUD on top).
 */
class Iris {
    private val xs = FloatArray(ARC + 2)
    private val ys = FloatArray(ARC + 2)

    /**
     * Black over a [w] x [h] screen except a hole of radius [r] at ([cx], [cy]). Nothing is drawn
     * once the hole uncovers the whole screen, and one rectangle when it's shut.
     */
    fun draw(gfx: Gfx, w: Float, h: Float, cx: Float, cy: Float, r: Float, color: Int = BLACK) {
        if (r >= fullRadius(w, h, cx, cy)) return
        if (r < 0.5f) { gfx.rect(-2f, -2f, w + 4f, h + 4f, color); return }
        // Neighbouring pieces overlap a hair, so no thin line of the picture shows between them.
        val e = SEAM
        val top = cy - r
        val bot = cy + r
        val left = cx - r
        val right = cx + r
        // Full-width bands above and below the hole...
        if (top > 0f) gfx.rect(-2f, -2f, w + 4f, top + 2f + e, color)
        if (bot < h) gfx.rect(-2f, bot - e, w + 4f, h + 2f - bot + e, color)
        // ...and to its left and right, between them.
        val y0 = max(top, -2f)
        val y1 = min(bot, h + 2f)
        if (y1 > y0) {
            if (left > 0f) gfx.rect(-2f, y0, left + 2f + e, y1 - y0, color)
            if (right < w) gfx.rect(right - e, y0, w + 2f - right + e, y1 - y0, color)
        }
        // The four corners of the hole's square, outside the circle.
        for (q in 0 until 4) {
            val sx = if (q == 0 || q == 3) 1f else -1f
            val sy = if (q < 2) 1f else -1f
            val cornerX = cx + sx * r
            val cornerY = cy + sy * r
            if (max(cx, cornerX) < 0f || min(cx, cornerX) > w || max(cy, cornerY) < 0f || min(cy, cornerY) > h) continue
            for (i in 0..ARC) {
                xs[i] = cx + sx * r * COS[i]
                ys[i] = cy + sy * r * SIN[i]
            }
            xs[ARC + 1] = cornerX
            ys[ARC + 1] = cornerY
            gfx.poly(xs, ys, color)
        }
    }

    companion object {
        /** Seconds for the circle to close, and to open. */
        const val CLOSE = 0.45f
        const val OPEN = 0.45f
        /** Walking into a portal closes quicker (you can still move meanwhile). */
        const val PORTAL_CLOSE = 0.3f
        /** How big the hole is while it waits on the hero for a moment. */
        const val HOLD = 30f
        private const val SEAM = 0.75f
        private const val BLACK = 0xFF000000.toInt()
        /** Corners per quarter of the circle (smooth enough even on big screens). */
        private const val ARC = 12
        private val COS = FloatArray(ARC + 1) { cos(it * PI / 2 / ARC).toFloat() }
        private val SIN = FloatArray(ARC + 1) { sin(it * PI / 2 / ARC).toFloat() }

        /** The hole size that just uncovers a whole [w] x [h] screen around ([cx], [cy]). */
        fun fullRadius(w: Float, h: Float, cx: Float, cy: Float) = hypot(max(cx, w - cx), max(cy, h - cy)) + 2f

        /**
         * The hole's radius [p] (0..1) of the way through a wipe, [full] being wide open. Closing, it
         * shrinks quickly onto the hero, waits there a moment so you see him, then shuts. Opening,
         * it pops open to hero size and then grows faster and faster until the picture is all back.
         */
        fun radius(p: Float, closing: Boolean, full: Float): Float {
            val k = p.coerceIn(0f, 1f)
            val hold = min(HOLD, full)
            if (closing) return when {
                k < 0.6f -> { val t = 1f - k / 0.6f; hold + (full - hold) * t * t }
                k < 0.8f -> hold
                else -> hold * (1f - (k - 0.8f) / 0.2f)
            }
            if (k < 0.2f) return hold * sin(k / 0.2f * (PI / 2).toFloat())
            val t = (k - 0.2f) / 0.8f
            return hold + (full - hold) * t * t
        }
    }
}
