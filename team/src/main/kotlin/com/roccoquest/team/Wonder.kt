package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.Sprites
import com.roccoquest.game.TILE
import com.roccoquest.game.argb
import com.roccoquest.game.gradientStops
import com.roccoquest.game.mixColor
import com.roccoquest.game.rainbow
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================ World 13 (Wonder World)

/** World 13, WONDER WORLD: where it starts, and which levels are in it. */
object Wonder {
    /** The first level of World 13 (an index into [Levels.all]). */
    const val WORLD13 = WORLD_SIZE * 12

    fun isWonderLevel(index: Int) = index in WORLD13 until WORLD13 + WORLD_SIZE
}

/**
 * What touching a Wonder Flower does (on top of the floaty rainbow WONDER TIME). Every flower always does the
 * same surprise ([at] picks it from where the flower grows), so a level is the same each time you play it.
 */
enum class WonderEffect(val label: String, val how: String, val seconds: Float, val line: String) {
    FLOATY("SUPER FLOATY!", "Everybody jumps sky high!", 12f, "Wahoo! I'm so floaty!"),
    COIN_RAIN("COIN RAIN!", "Coins are falling from the sky. Grab them!", 10f, "Mamma mia! It's raining coins!"),
    DANCE("DANCE PARTY!", "The bad guys can't stop dancing. Run past them!", 10f, "Look! The bad guys are dancing!"),
    // (Mega Rocco says his own line.)
    MEGA("MEGA ROCCO!", "Giant Rocco squashes everything!", 12f, ""),
    STAR("STAR RUN!", "Nothing can hurt you. RUN!", 10f, "A Wonder star! Let's-a go!"),
    COIN_POP("POP POP POP!", "The bad guys turned into COINS!", 8f, "Pop pop pop! Coins!"),
    ;

    companion object {
        fun at(tx: Int, levelIndex: Int): WonderEffect = entries[(tx * 7 + levelIndex * 3) % entries.size]
    }
}

/** A Wonder Flower (level char '-'): touch it for WONDER TIME. Handled in [TeamGame.interact]. */
class WonderFlower(x: Float, bottom: Float, val effect: WonderEffect) : Thing(x + 1, bottom - 18f, 14f, 18f) {
    private var t = 0f
    override fun update(g: TeamGame, dt: Float) { t += dt }
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        WonderArt.wonderFlower(gfx, cx + ox, bottom, t, 1f)
        if ((t * 2).toInt() % 3 != 0) gfx.shadowText("WONDER!", cx + ox, y - 6f, 5.5f, argb(255, rainbow(t * 0.6f)), 1)
    }
}

/**
 * A Talking Flower (level char '\\'): says something funny when the team walks by (a speech bubble, and a
 * little voice). Each one talks twice at most, so they never get annoying.
 */
class TalkingFlower(x: Float, bottom: Float, private val seed: Int) : Thing(x + 2, bottom - 16f, 12f, 16f) {
    private var t = 0f
    /** The mouth moves while this counts down. */
    private var talkT = 0f
    private var quietT = 0f
    private var said = 0
    private val petals = PETALS[seed % PETALS.size]

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (talkT > 0f) talkT -= dt
        if (quietT > 0f) { quietT -= dt; return }
        val l = g.leader
        if (said < 2 && abs(l.cx - cx) < 56f && abs(l.cy - cy) < 60f) {
            g.flowerTalk(cx, y - 4f, line(g))
            said++
            talkT = 1.3f
            quietT = 6f
        }
    }

    /** What to say: a heads-up if something's coming, otherwise something silly. */
    private fun line(g: TeamGame): String {
        val ahead = cx + 220f
        return when {
            g.wonderT > 0f -> listOf("Whoa! Wonder!", "Everything is wiggly!", "So wonderful!", "Wheee! Rainbows!")[(seed + said) % 4]
            g.wonderFlowerBetween(cx, ahead) -> "A Wonder Flower! Touch it!"
            said == 0 -> if (g.heroOut == Who.BLOCKY) "Hi, Blocky!" else "Hi, Rocco!"
            g.foes.any { !it.dying && !it.boss && it.cx in cx..ahead } -> "Watch out! Bad guys!"
            else -> SILLY[(seed * 5 + said) % SILLY.size]
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val sway = sin(t * 2.2f + seed) * 1.5f
        WonderArt.talkingFlower(gfx, cx + ox, bottom, sway, petals, talkT > 0f && (talkT * 10).toInt() % 2 == 0, t + seed)
    }

    private companion object {
        val PETALS = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFE14D.toInt(), 0xFFFF9AD0.toInt(), 0xFF8AD8FF.toInt())
        val SILLY = listOf(
            "Don't step on me!", "I'm a flower!", "You can do it!", "Tickle tickle!", "Go go go!", "Team up!",
            "Is it lunch time yet?", "I need a bath...", "Nice jump!", "I like your mustache!", "Wheee!", "Watch your step!",
        )
    }
}

/** The Wonder Seed that pops out when Wonder Time is over: it floats to the team. */
class WonderSeed(x: Float, y: Float) : Thing(x - 6f, y, 12f, 14f) {
    private var t = 0f
    init { active = true }
    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (t < 0.6f) { y -= 30f * dt; return }
        // Then it flies to whoever is in front.
        val l = g.leader
        val dx = l.cx - cx
        val dy = l.cy - cy
        val d = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val sp = 140f + t * 60f
        x += dx / d * sp * dt
        y += dy / d * sp * dt
        if (t > 8f) removed = true
    }
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        WonderArt.seed(gfx, cx + ox, cy + sin(t * 4f) * 1.5f, t)
    }
}

/** A coin falling from the sky (COIN RAIN). It lands, waits a little, then fades away. */
class RainCoin(x: Float, y: Float) : Thing(x, y, 8f, 12f) {
    private var t = 0f
    private var landedT = -1f
    private val vy0 = 70f + (x * 13f % 50f)
    init { active = true }
    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (landedT >= 0f) { landedT += dt; if (landedT > 2.5f) removed = true; return }
        x += sin(t * 3f + y * 0.05f) * 12f * dt
        y += vy0 * dt
        if (g.level.solidAt(cx, bottom)) landedT = 0f
        if (y > g.level.h * TILE) removed = true
    }
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        if (landedT > 1.8f && (t * 10).toInt() % 2 == 0) return
        val squish = abs(sin(t * 5 + x * 0.05f))
        val w = 3f + 5f * squish
        gfx.sprite(Sprites.coin, cx - w / 2 + ox, y, w = w, h = 11f)
    }
}

/** Wonder World's look: the flower-kingdom sky and ground, and the flowers. */
object WonderArt {
    private const val OUT = 0xFF2A1A20.toInt()

    /** A 16x16 tile (cached as a sprite, which is much faster than drawing shapes for every tile). */
    private fun tile(fill: (Int, Int) -> Int): Sprite = Sprite(16, 16, IntArray(256) { i ->
        val x = i % 16; val y = i / 16
        val c = fill(x, y)
        when {
            x == 15 || y == 15 -> mixColor(c, OUT, 0.35f)
            x == 0 -> mixColor(c, 0xFFFFFFFF.toInt(), 0.18f)
            else -> c
        }
    })

    private const val SOIL = 0xFFE8984A.toInt()
    private const val SOIL_DOT = 0xFFF8C878.toInt()
    private const val SOIL_DARK = 0xFFC8743A.toInt()

    private fun soil(x: Int, y: Int) = when {
        (x - 4) * (x - 4) + (y - 9) * (y - 9) < 4 || (x - 11) * (x - 11) + (y - 13) * (y - 13) < 3 -> SOIL_DOT
        (x * 5 + y * 3) % 17 == 0 -> SOIL_DARK
        else -> SOIL
    }

    /** Bright grass with tiny flowers on top of orange polka-dot soil. */
    val groundTop: Sprite by lazy {
        tile { x, y ->
            val edge = 4 + (if (x % 4 == 1 || x % 4 == 2) 1 else 0)
            when {
                y == 0 -> 0xFF9AF08A.toInt()
                y == 1 && x % 5 == 2 -> 0xFFFF7AC8.toInt()
                y == 1 && x % 5 == 4 -> 0xFFFFF4A0.toInt()
                y < edge -> 0xFF4AC85A.toInt()
                y == edge -> 0xFF3A9A4A.toInt()
                else -> soil(x, y)
            }
        }
    }
    val groundUnder: Sprite by lazy { tile { x, y -> soil(x, (y + 5) % 16) } }

    fun ground(under: Boolean): Sprite = if (under) groundUnder else groundTop

    private inline fun layer(viewW: Float, cam: Float, factor: Float, spacing: Float, draw: (Int, Float) -> Unit) {
        val off = cam * factor
        val first = (off / spacing).toInt() - 1
        val last = ((off + viewW) / spacing).toInt() + 1
        for (i in first..last) draw(i and 0xFFFF, i * spacing - off)
    }

    /** A rainbow arc (bands of polygons, like the Candy Planet's) with its middle at ([cx], [cy]). */
    fun rainbowArc(gfx: Gfx, cx: Float, cy: Float, r: Float, band: Float, alpha: Int) {
        val colors = intArrayOf(0xFF5A5A, 0xFFA84A, 0xFFE14D, 0x6AD86A, 0x6AA8FF, 0xA87AF0)
        val n = 14
        for (k in colors.indices) {
            val r0 = r - k * band
            val r1 = r0 - band
            val xs = FloatArray(2 * (n + 1)); val ys = FloatArray(2 * (n + 1))
            for (i in 0..n) {
                val a = Math.PI.toFloat() * i / n
                xs[i] = cx - cos(a) * r0; ys[i] = cy - sin(a) * r0
                xs[2 * n + 1 - i] = cx - cos(a) * r1; ys[2 * n + 1 - i] = cy - sin(a) * r1
            }
            gfx.poly(xs, ys, argb(alpha, colors[k]))
        }
    }

    /** Cute round hills with eyes (they blink!), giant flowers and smiling clouds. */
    fun drawSky(gfx: Gfx, viewW: Float, viewH: Float, cam: Float, t: Float) {
        gfx.gradientStops(0f, 0f, viewW, viewH, 0xFF3AA8FF.toInt(), 0xFF8ADCFF.toInt(), 0xFFFFE4F4.toInt())
        rainbowArc(gfx, viewW * 0.62f - cam * 0.02f, 190f, 150f, 7f, 120)
        // Smiling clouds
        layer(viewW, cam, 0.08f, 210f) { i, x ->
            val cy = 26f + (i % 3) * 18f
            gfx.oval(x + 20, cy, 56f, 22f, 0xFFFFFFFF.toInt())
            gfx.oval(x + 32, cy - 10, 30f, 22f, 0xFFFFFFFF.toInt())
            gfx.oval(x + 40, cy + 6, 3f, 4f, OUT); gfx.oval(x + 50, cy + 6, 3f, 4f, OUT)
            gfx.oval(x + 36, cy + 10, 4f, 3f, 0x66FF7AA8); gfx.oval(x + 53, cy + 10, 4f, 3f, 0x66FF7AA8)
        }
        // Round hills with big eyes, far away
        val hills = intArrayOf(0xFF8AD86A.toInt(), 0xFFFFA8D8.toInt(), 0xFF6ACFD8.toInt(), 0xFFC8A8FF.toInt())
        layer(viewW, cam, 0.18f, 240f) { i, x ->
            val top = 112f + (i % 3) * 16f
            val c = hills[i % hills.size]
            gfx.oval(x - 10, top, 200f, 180f, mixColor(c, 0xFFFFFFFF.toInt(), 0.25f))
            gfx.oval(x + 6, top + 10, 168f, 160f, c)
            val blink = (t * 0.7f + i * 0.37f) % 4f < 0.12f
            for (k in 0..1) {
                val ex = x + 70 + k * 26f
                if (blink) gfx.rect(ex, top + 34f, 12f, 2.5f, OUT)
                else {
                    gfx.oval(ex, top + 24f, 12f, 18f, 0xFFFFFFFF.toInt())
                    gfx.oval(ex + 3f, top + 29f, 7f, 10f, OUT)
                }
            }
        }
        // Giant flowers up close
        layer(viewW, cam, 0.4f, 170f) { i, x ->
            val h = 56f + (i % 3) * 18f
            val fx = x + 60f
            val fy = 216f - h
            gfx.rect(fx - 2.5f, fy, 5f, h, 0xFF3A9A4A.toInt())
            gfx.oval(fx + 1f, fy + h * 0.5f, 16f, 7f, 0xFF4AC85A.toInt())
            val pc = listOf(0xFFFF7AC8, 0xFFFFE14D, 0xFFFFFFFF, 0xFF8AD8FF)[i % 4].toInt()
            for (k in 0 until 6) {
                val a = k * 1.047f + t * 0.3f
                gfx.oval(fx + cos(a) * 11f - 7f, fy + sin(a) * 11f - 7f, 14f, 14f, pc)
            }
            gfx.oval(fx - 8f, fy - 8f, 16f, 16f, 0xFFFFC83A.toInt())
            gfx.oval(fx - 4f, fy - 3f, 2.5f, 4f, OUT); gfx.oval(fx + 2f, fy - 3f, 2.5f, 4f, OUT)
        }
        // Rainbow petals drifting by
        for (i in 0 until 14) {
            val px = (i * 71f - cam * 0.5f + sin(t + i) * 12f).mod(viewW)
            val py = (i * 37f + t * 18f) % 200f
            gfx.oval(px, py, 3.5f, 2.5f, argb(200, rainbow(i / 14f)))
        }
    }

    /** The Wonder Flower: rainbow petals around a sparkly smiling middle, glowing. */
    fun wonderFlower(gfx: Gfx, cx: Float, bottom: Float, t: Float, s: Float) {
        val cy = bottom - 12f * s + sin(t * 3f) * 1f
        gfx.oval(cx - 12f * s, cy - 12f * s, 24f * s, 24f * s, argb(70, rainbow(t * 0.5f)))
        gfx.rect(cx - 1f * s, cy, 2f * s, bottom - cy, 0xFF3A9A4A.toInt())
        gfx.oval(cx + 1f * s, bottom - 6f * s, 6f * s, 3f * s, 0xFF4AC85A.toInt())
        gfx.oval(cx - 7f * s, bottom - 5f * s, 6f * s, 3f * s, 0xFF4AC85A.toInt())
        for (k in 0 until 6) {
            val a = k * 1.047f + t * 1.5f
            gfx.oval(cx + cos(a) * 5f * s - 3.5f * s, cy + sin(a) * 5f * s - 3.5f * s, 7f * s, 7f * s, argb(255, rainbow(k / 6f)))
        }
        gfx.oval(cx - 4f * s, cy - 4f * s, 8f * s, 8f * s, 0xFFFFF8D0.toInt())
        gfx.oval(cx - 2.5f * s, cy - 2f * s, 1.5f * s, 2f * s, OUT); gfx.oval(cx + 1f * s, cy - 2f * s, 1.5f * s, 2f * s, OUT)
        gfx.rect(cx - 1.5f * s, cy + 1.2f * s, 3f * s, 1f * s, 0xFFE85A7A.toInt())
        // A twinkle
        val tw = (t * 1.3f) % 1f
        if (tw < 0.4f) {
            val sx = cx + 7f * s; val sy = cy - 8f * s; val r = 3f * s * (1f - abs(tw - 0.2f) * 5f)
            gfx.poly(floatArrayOf(sx, sx + r * 0.3f, sx + r, sx + r * 0.3f, sx, sx - r * 0.3f, sx - r, sx - r * 0.3f),
                floatArrayOf(sy - r, sy - r * 0.3f, sy, sy + r * 0.3f, sy + r, sy + r * 0.3f, sy, sy - r * 0.3f), 0xFFFFFFFF.toInt())
        }
    }

    /** A Talking Flower: a tall stem, petals, and a face with a mouth that moves while it talks. */
    fun talkingFlower(gfx: Gfx, cx: Float, bottom: Float, sway: Float, petals: Int, open: Boolean, t: Float) {
        val hx = cx + sway
        val hy = bottom - 11f
        gfx.poly(floatArrayOf(cx - 1f, cx + 1f, hx + 1f, hx - 1f), floatArrayOf(bottom, bottom, hy, hy), 0xFF3A9A4A.toInt())
        gfx.oval(cx - 6f, bottom - 5f, 6f, 3f, 0xFF4AC85A.toInt()); gfx.oval(cx, bottom - 7f, 6f, 3f, 0xFF4AC85A.toInt())
        for (k in 0 until 5) {
            val a = k * 1.2566f - 1.5708f
            gfx.oval(hx + cos(a) * 5f - 3.5f, hy + sin(a) * 5f - 3.5f, 7f, 7f, petals)
        }
        gfx.oval(hx - 4.5f, hy - 4.5f, 9f, 9f, 0xFFFFD84A.toInt())
        val blink = t % 3.5f < 0.12f
        if (blink) { gfx.rect(hx - 3f, hy - 1.5f, 2f, 0.8f, OUT); gfx.rect(hx + 1f, hy - 1.5f, 2f, 0.8f, OUT) }
        else { gfx.oval(hx - 3f, hy - 3f, 1.8f, 2.6f, OUT); gfx.oval(hx + 1.2f, hy - 3f, 1.8f, 2.6f, OUT) }
        if (open) gfx.oval(hx - 1.6f, hy + 0.5f, 3.2f, 3f, 0xFF8A1A2A.toInt())
        else gfx.rect(hx - 1.5f, hy + 1.2f, 3f, 0.9f, OUT)
    }

    /** A Wonder Seed: a shiny rainbow seed with sparkles. */
    fun seed(gfx: Gfx, cx: Float, cy: Float, t: Float) {
        gfx.oval(cx - 9f, cy - 9f, 18f, 18f, argb(80, rainbow(t)))
        gfx.oval(cx - 5f, cy - 6f, 10f, 12f, argb(255, rainbow(t * 0.7f)))
        gfx.oval(cx - 3f, cy - 4f, 3f, 4f, 0xCCFFFFFF.toInt())
        for (k in 0 until 3) {
            val a = t * 3f + k * 2.09f
            gfx.oval(cx + cos(a) * 10f - 1f, cy + sin(a) * 10f - 1f, 2f, 2f, 0xFFFFFFFF.toInt())
        }
    }
}
