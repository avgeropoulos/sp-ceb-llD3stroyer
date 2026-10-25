package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Sprite
import com.roccoquest.game.TILE
import com.roccoquest.game.Voice
import com.roccoquest.game.argb
import com.roccoquest.game.gradientStops
import com.roccoquest.game.mixColor
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ============================================================================ World 8 (Toy World) and World 9 (Galaxy World)

/** Ground tiles and skies for the newest places. */
object GalaxyArt {
    private const val OUT = 0xFF1A1420.toInt()

    /** A 16x16 tile: [fill] decides each pixel's color; the edges get a light top-left and a dark bottom-right. */
    private fun tile(fill: (Int, Int) -> Int): Sprite = Sprite(16, 16, IntArray(256) { i ->
        val x = i % 16; val y = i / 16
        val c = fill(x, y)
        when {
            x == 15 || y == 15 -> mixColor(c, OUT, 0.45f)
            x == 0 || y == 0 -> mixColor(c, 0xFFFFFFFF.toInt(), 0.25f)
            else -> c
        }
    })

    /** Toy blocks: bright plastic with round studs on top. */
    val toyTop: Sprite by lazy {
        tile { x, y ->
            val stud = y in 1..3 && (x in 2..5 || x in 10..13)
            when {
                stud && y == 1 -> 0xFFFF8A8A.toInt()
                stud -> 0xFFE84A4A.toInt()
                y <= 4 -> 0xFFE84A4A.toInt()
                else -> if ((x + y) % 7 == 0) 0xFFC83A3A.toInt() else 0xFFE84A4A.toInt()
            }
        }
    }
    val toyUnder: Sprite by lazy {
        tile { x, y -> if ((x / 8 + y / 8) % 2 == 0) 0xFF3A7AE8.toInt() else 0xFFF4C83A.toInt() }
    }

    /** Glowing mushroom forest floor: purple moss over dark soil. */
    val fungiTop: Sprite by lazy {
        tile { x, y ->
            when {
                y < 3 + (x % 3 == 0).compareTo(false) -> 0xFFB86AE8.toInt()
                y < 5 -> 0xFF7A3AA8.toInt()
                (x * 7 + y * 3) % 11 == 0 -> 0xFF5AF0C8.toInt()
                else -> 0xFF3A2440.toInt()
            }
        }
    }
    val fungiUnder: Sprite by lazy {
        tile { x, y -> if ((x * 7 + y * 3) % 13 == 0) 0xFF5AF0C8.toInt() else if ((x + y * 2) % 5 == 0) 0xFF2E1C34.toInt() else 0xFF3A2440.toInt() }
    }

    /** Moon rock: grey with little craters. */
    val moonTop: Sprite by lazy {
        tile { x, y ->
            val crater = (x - 5) * (x - 5) + (y - 8) * (y - 8) < 6 || (x - 12) * (x - 12) + (y - 4) * (y - 4) < 3
            when {
                y < 2 -> 0xFFE4E4EC.toInt()
                crater -> 0xFF8A8A98.toInt()
                else -> 0xFFBEBECA.toInt()
            }
        }
    }
    val moonUnder: Sprite by lazy {
        tile { x, y -> if ((x - 8) * (x - 8) + (y - 9) * (y - 9) < 8) 0xFF7A7A88.toInt() else 0xFFA8A8B4.toInt() }
    }

    /** Space station floor: metal plates with rivets and a glowing stripe. */
    val stationTop: Sprite by lazy {
        tile { x, y ->
            when {
                y in 2..3 -> 0xFF4AE8F0.toInt()
                (x == 2 || x == 13) && (y == 6 || y == 12) -> 0xFF5A6070.toInt()
                else -> 0xFF9AA4B8.toInt()
            }
        }
    }
    val stationUnder: Sprite by lazy {
        tile { x, y -> if ((x == 2 || x == 13) && (y == 2 || y == 13)) 0xFF5A6070.toInt() else 0xFF7A8498.toInt() }
    }

    fun ground(place: Place, under: Boolean): Sprite? = when (place) {
        Place.TOYS -> if (under) toyUnder else toyTop
        Place.FUNGI -> if (under) fungiUnder else fungiTop
        Place.MOON -> if (under) moonUnder else moonTop
        Place.STATION, Place.ORBIT -> if (under) stationUnder else stationTop
        else -> null
    }

    private inline fun layer(viewW: Float, cam: Float, factor: Float, spacing: Float, draw: (Int, Float) -> Unit) {
        val off = cam * factor
        val first = (off / spacing).toInt() - 1
        val last = ((off + viewW) / spacing).toInt() + 1
        for (i in first..last) draw(i and 0xFFFF, i * spacing - off)
    }

    fun drawSky(gfx: Gfx, place: Place, viewW: Float, viewH: Float, cam: Float, t: Float) {
        when (place) {
            Place.TOYS -> {
                gfx.gradient(0f, 0f, viewW, viewH, 0xFF6AC8FF.toInt(), 0xFFFFF0C8.toInt())
                // Giant toys far away: stacked blocks, a teddy bear and a spinning top.
                layer(viewW, cam, 0.2f, 230f) { i, x ->
                    val c = listOf(0xFFE84A4A, 0xFF3A7AE8, 0xFFF4C83A, 0xFF4AB85A)
                    for (k in 0 until 3) {
                        val bx = x + 20 + (k % 2) * 14f
                        val by = 190f - (k + 1) * 30f
                        gfx.rect(bx - 1, by - 1, 34f, 32f, 0x55000000)
                        gfx.rect(bx, by, 32f, 30f, c[(i + k) % 4].toInt())
                        gfx.text("ABC"[k].toString(), bx + 16, by + 21, 16f, 0xCCFFFFFF.toInt(), 1)
                    }
                    if (i % 2 == 0) {
                        // Teddy bear
                        val tx = x + 140
                        gfx.oval(tx - 18, 130f, 44f, 54f, 0xFFB8783A.toInt())
                        gfx.oval(tx - 12, 100f, 32f, 32f, 0xFFB8783A.toInt())
                        gfx.oval(tx - 14, 96f, 12f, 12f, 0xFFB8783A.toInt()); gfx.oval(tx + 10, 96f, 12f, 12f, 0xFFB8783A.toInt())
                        gfx.oval(tx - 4, 114f, 16f, 11f, 0xFFF0D0A0.toInt())
                        gfx.oval(tx - 4, 109f, 4f, 4f, OUT); gfx.oval(tx + 8, 109f, 4f, 4f, OUT)
                        gfx.oval(tx + 2, 115f, 4f, 3f, OUT)
                    } else {
                        // Spinning top
                        val sx = x + 150
                        val w = 30f * abs(cos(t * 3f + i))
                        gfx.poly(floatArrayOf(sx - 22, sx + 22, sx), floatArrayOf(150f, 150f, 186f), 0xFFE84AA8.toInt())
                        gfx.rect(sx - w / 2, 146f, w, 6f, 0xFFF4F4F4.toInt())
                        gfx.rect(sx - 1.5f, 132f, 3f, 16f, 0xFF8A5A2A.toInt())
                    }
                }
                layer(viewW, cam, 0.1f, 180f) { i, x -> cloudPuff(gfx, x + 30, 26f + (i % 3) * 20f) }
            }
            Place.FUNGI -> {
                gfx.gradientStops(0f, 0f, viewW, viewH, 0xFF1E0E3A.toInt(), 0xFF4A1E6A.toInt(), 0xFF8A3A8A.toInt())
                for (i in 0 until 26) gfx.rect((i * 97f - cam * 0.04f).mod(viewW), (i * 53f) % 110f, 1.5f, 1.5f, 0xCCFFFFFF.toInt())
                // Giant glowing mushrooms
                layer(viewW, cam, 0.25f, 150f) { i, x ->
                    val h = 70f + (i % 3) * 22f
                    val cap = listOf(0xFF5AF0C8, 0xFFFF6AC8, 0xFF6AA8FF)[i % 3].toInt()
                    gfx.rect(x + 50, 220f - h, 14f, h, 0xFFE8D8F0.toInt())
                    gfx.oval(x + 16, 200f - h - 14f, 82f, 46f, argb(70, cap and 0xFFFFFF))
                    gfx.oval(x + 22, 206f - h - 10f, 70f, 34f, cap)
                    for (k in 0 until 3) gfx.oval(x + 32 + k * 18f, 212f - h - 4f, 8f, 6f, 0xCCFFFFFF.toInt())
                }
                // Floating spores
                for (i in 0 until 18) {
                    val fx = (i * 61f - cam * 0.3f + sin(t + i) * 10f).mod(viewW)
                    val fy = (200f - (t * 12f + i * 23f) % 200f)
                    gfx.oval(fx, fy, 3f, 3f, argb(140 + (sin(t * 3 + i) * 80).toInt(), 0x9AFFE0))
                }
            }
            Place.MOON -> {
                gfx.gradient(0f, 0f, viewW, viewH, 0xFF02020A.toInt(), 0xFF1A1A30.toInt())
                for (i in 0 until 50) {
                    val tw = if ((t * 2 + i) % 5 < 1) 0x88FFFFFF.toInt() else 0xFFFFFFFF.toInt()
                    gfx.rect((i * 89f - cam * 0.02f).mod(viewW), (i * 41f) % 180f, 1.5f, 1.5f, tw)
                }
                // The Earth hanging in the sky
                val ex = viewW * 0.78f - cam * 0.01f
                gfx.oval(ex - 30, 22f, 60f, 60f, 0x332A8AFF)
                gfx.oval(ex - 26, 26f, 52f, 52f, 0xFF2A6AD8.toInt())
                gfx.oval(ex - 18, 34f, 18f, 14f, 0xFF3AA84A.toInt())
                gfx.oval(ex + 2, 50f, 16f, 18f, 0xFF3AA84A.toInt())
                gfx.oval(ex - 10, 30f, 30f, 6f, 0xAAFFFFFF.toInt())
                // Grey hills with craters
                layer(viewW, cam, 0.25f, 210f) { i, x ->
                    gfx.oval(x - 20, 160f - (i % 3) * 14f, 250f, 120f, 0xFF6A6A7A.toInt())
                    gfx.oval(x + 50, 176f - (i % 3) * 14f, 40f, 10f, 0xFF4A4A5A.toInt())
                }
            }
            Place.ORBIT -> {
                // Space walk: black space, lots of stars, and the big blue Earth curving along the bottom.
                gfx.gradient(0f, 0f, viewW, viewH, 0xFF000006.toInt(), 0xFF0A0E2A.toInt())
                for (i in 0 until 40) {
                    val tw = if ((t * 2 + i) % 6 < 1) 0x88FFFFFF.toInt() else 0xFFFFFFFF.toInt()
                    gfx.rect((i * 73f - cam * 0.02f).mod(viewW), (i * 37f) % 150f, 1.5f, 1.5f, tw)
                }
                val ex = -cam * 0.04f
                gfx.oval(ex - 200f, 120f, viewW + 400f, 420f, 0x442A8AFF)
                gfx.oval(ex - 190f, 128f, viewW + 380f, 400f, 0xFF2A6AD8.toInt())
                layer(viewW, cam, 0.05f, 180f) { i, x ->
                    gfx.oval(x, 140f + (i % 3) * 8f, 70f + (i % 2) * 30f, 22f, 0xFF3AA84A.toInt())
                    gfx.oval(x + 60, 132f + (i % 2) * 10f, 80f, 10f, 0xCCFFFFFF.toInt())
                }
                // Space station modules and solar panels floating behind
                layer(viewW, cam, 0.2f, 260f) { i, x ->
                    if (i % 2 == 0) {
                        gfx.rect(x + 40, 60f, 90f, 22f, 0xFFB8C0CC.toInt())
                        gfx.rect(x + 10, 52f, 26f, 38f, 0xFF2A4A9A.toInt()); gfx.rect(x + 134, 52f, 26f, 38f, 0xFF2A4A9A.toInt())
                        for (k in 0 until 3) { gfx.rect(x + 10, 60f + k * 10f, 26f, 1f, 0xFF6A8AD8.toInt()); gfx.rect(x + 134, 60f + k * 10f, 26f, 1f, 0xFF6A8AD8.toInt()) }
                        gfx.oval(x + 76, 64f, 14f, 14f, 0xFF04060E.toInt())
                        if ((t * 2).toInt() % 2 == 0) gfx.oval(x + 126, 58f, 4f, 4f, 0xFFFF4A4A.toInt())
                    } else {
                        gfx.oval(x + 80, 40f, 16f, 10f, 0xFF8A8A9A.toInt())
                    }
                }
            }
            Place.STATION -> {
                gfx.gradient(0f, 0f, viewW, viewH, 0xFF0A0E1E.toInt(), 0xFF1E2A44.toInt())
                // Big round windows looking out at the stars and a ringed planet
                layer(viewW, cam, 0.3f, 200f) { i, x ->
                    gfx.rect(x, 0f, 200f, viewH, 0xFF2A3448.toInt())
                    gfx.rect(x + 196, 0f, 4f, viewH, 0xFF1A2232.toInt())
                    gfx.oval(x + 50, 50f, 100f, 100f, 0xFF5A6478.toInt())
                    gfx.oval(x + 56, 56f, 88f, 88f, 0xFF04060E.toInt())
                    for (k in 0 until 6) gfx.rect(x + 66 + (k * 23f) % 66f, 68f + (k * 31f) % 60f, 1.5f, 1.5f, 0xFFFFFFFF.toInt())
                    if (i % 2 == 0) {
                        gfx.oval(x + 82, 82f, 34f, 34f, 0xFFE8A84A.toInt())
                        gfx.oval(x + 70, 95f, 58f, 8f, 0x99F0D8A0.toInt())
                    }
                    // Blinking control lights
                    for (k in 0 until 4) gfx.oval(x + 20 + k * 12f, 180f, 6f, 6f, if (((t * 3).toInt() + k + i) % 3 == 0) 0xFF4AE8F0.toInt() else 0xFF2A5A60.toInt())
                }
            }
            else -> {}
        }
    }

    private fun cloudPuff(gfx: Gfx, x: Float, y: Float) {
        gfx.oval(x, y, 50f, 20f, 0xFFFFFFFF.toInt())
        gfx.oval(x + 12, y - 10, 28f, 22f, 0xFFFFFFFF.toInt())
    }

    /** A dark outline behind an oval, so shapes stand out. */
    fun outlined(gfx: Gfx, x: Float, y: Float, w: Float, h: Float, color: Int) {
        gfx.oval(x - 1, y - 1, w + 2, h + 2, OUT)
        gfx.oval(x, y, w, h, color)
    }
}

/** A wind-up toy soldier marching back and forth (level char ';'). The key on his back keeps turning. */
class ToySoldier(x: Float, bottom: Float) : Foe(x, bottom - 22, 14f, 22f, 3) {
    private var t = Random.nextFloat()
    init { vx = -30f }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        walk(g, dt)
        // Turn around at the edge of a ledge too.
        val ahead = if (vx > 0) x + w + 2 else x - 2
        if (onGround && !g.level.solidAt(ahead, bottom + 4)) vx = -vx
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val step = (sin(t * 10) * 2f)
        val f = if (vx > 0) 1f else -1f
        val o = 0xFF1A1420.toInt()
        // Legs
        gfx.rect(px + 3 - 1, y + 16 - 1, 4f, 7f + step * 0.3f, o); gfx.rect(px + 8 - 1, y + 16 - 1, 4f, 7f - step * 0.3f, o)
        gfx.rect(px + 3, y + 16, 2.5f, 6f + step * 0.3f, 0xFF2A3A8A.toInt()); gfx.rect(px + 8, y + 16, 2.5f, 6f - step * 0.3f, 0xFF2A3A8A.toInt())
        // Red coat with a white cross belt
        gfx.rect(px + 1, y + 7, 12f, 10f, o)
        gfx.rect(px + 2, y + 8, 10f, 8f, 0xFFD83A3A.toInt())
        gfx.poly(floatArrayOf(px + 2, px + 4, px + 12, px + 10), floatArrayOf(y + 8, y + 8, y + 16, y + 16), 0xFFF4F4F4.toInt())
        gfx.rect(px + 2, y + 13, 10f, 1.5f, 0xFFF4C83A.toInt())
        // Face and the tall black hat
        GalaxyArt.outlined(gfx, px + 3, y + 3, 8f, 6f, 0xFFF4D0A8.toInt())
        gfx.oval(px + 7 + f * 1.5f - 1, y + 5, 1.6f, 1.6f, o)
        gfx.oval(px + 5 + f, y + 7.5f, 3f, 1.5f, 0xFFE87A7A.toInt())
        gfx.rect(px + 2, y - 5, 10f, 9f, o)
        gfx.rect(px + 3, y - 4, 8f, 7f, 0xFF20202A.toInt())
        gfx.rect(px + 2, y + 2, 10f, 1.5f, 0xFFF4C83A.toInt())
        // The wind-up key on his back
        val kx = px + 7 - f * 9
        val spin = abs(cos(t * 6f))
        gfx.rect(kx - 1, y + 10, 2f, 3f, 0xFFC8A030.toInt())
        gfx.oval(kx - 4 * spin - 1, y + 7, 8 * spin + 2, 5f, 0xFFE8C83A.toInt())
        drawFlash(gfx, ox)
    }
}

/** A jack-in-the-box (level char ','). Walk close and BOING! A clown pops out. Stomp the box when it's closed. */
class JackBox(x: Float, bottom: Float) : Foe(x, bottom - 16, 16f, 16f, 2) {
    private val base = bottom
    private var pop = 0f
    private var popT = 0f
    private var coolT = Random.nextFloat()
    private var t = 0f

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        if (coolT > 0f) coolT -= dt
        val l = g.leader
        if (popT <= 0f && coolT <= 0f && abs(l.cx - cx) < 60f && abs(l.bottom - base) < 50f) {
            popT = 1.4f
            g.sfx(Sound.JUMP)
            g.say(cx, base - 46, "BOING!", 0.8f)
        }
        if (popT > 0f) {
            popT -= dt
            pop = min(1f, pop + dt * 6f)
            if (popT <= 0f) coolT = 1.8f
        } else pop = maxOf(0f, pop - dt * 3f)
        h = 16f + pop * 24f
        y = base - h
    }

    override fun touch(g: TeamGame) { if (pop > 0.3f) g.hurt(this) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val o = 0xFF1A1420.toInt()
        // The spring and the clown
        if (pop > 0.05f) {
            val top = base - 16 - pop * 24f
            for (k in 0 until 4) gfx.rect(px + 5, base - 16 - (k + 1) * pop * 5f, 6f, 1.5f, 0xFFB8B8C8.toInt())
            val wob = sin(t * 18) * 2f * pop
            GalaxyArt.outlined(gfx, px + 1 + wob, top - 2, 14f, 13f, 0xFFF4F4F4.toInt())
            gfx.oval(px + 4 + wob, top + 2, 2.5f, 2.5f, o); gfx.oval(px + 10 + wob, top + 2, 2.5f, 2.5f, o)
            gfx.oval(px + 6.5f + wob, top + 5, 3.5f, 3.5f, 0xFFE83A3A.toInt())
            gfx.oval(px + 4 + wob, top + 8, 8f, 3f, 0xFFE83A3A.toInt())
            // Rainbow hair and a pointy hat
            for (k in 0 until 3) gfx.oval(px - 2 + k * 7f + wob, top - 1, 6f, 6f, listOf(0xFF4AB8F0, 0xFFF4C83A, 0xFF4AB85A)[k].toInt())
            gfx.poly(floatArrayOf(px + 3 + wob, px + 8 + wob, px + 13 + wob), floatArrayOf(top - 1, top - 12, top - 1), 0xFF8A3AD8.toInt())
        }
        // The box
        gfx.rect(px - 1, base - 17, 18f, 17f, o)
        gfx.rect(px, base - 16, 16f, 16f, 0xFF3A7AE8.toInt())
        gfx.rect(px, base - 16, 8f, 8f, 0xFFF4C83A.toInt()); gfx.rect(px + 8, base - 8, 8f, 8f, 0xFFF4C83A.toInt())
        gfx.text("?", px + 12, base - 9.5f, 7f, 0xFFFFFFFF.toInt(), 1)
        // Lid: shut, or flipped open
        if (pop > 0.05f) gfx.rect(px - 5, base - 20, 6f, 4f, 0xFFE84A4A.toInt()) else gfx.rect(px - 1, base - 18, 18f, 3f, 0xFFE84A4A.toInt())
        drawFlash(gfx, ox)
    }
}

/** A green tractor beam the UFO shoots straight down. */
class Beam(x: Float, y: Float) : Foe(x, y, 6f, 14f, 1), Projectile {
    private var t = 0f
    override val stompable = false
    override val showBar get() = false
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        y += 170f * dt
        if (t > 3f || g.level.solidAt(cx, bottom)) { removed = true; g.puff(cx, bottom - 4) }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        gfx.rect(x + ox - 1, y - 2, 8f, 18f, 0x553CF070)
        gfx.rect(x + ox + 1, y, 4f, 14f, 0xFF7CFFA0.toInt())
    }
}

/** A little flying saucer (level char '/'). It drifts over you and zaps lasers down. Jump on it! */
class Ufo(x: Float, bottom: Float) : Foe(x, bottom - 12, 24f, 12f, 3) {
    private var t = Random.nextFloat() * 3f
    private var shootT = 2f + Random.nextFloat()
    private val homeY = bottom - 12

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        val l = g.leader
        if (abs(l.cx - cx) < 220f) vx = ((l.cx - cx) * 1.2f).coerceIn(-55f, 55f)
        x += vx * dt
        y = homeY + sin(t * 2f) * 6f
        shootT -= dt
        if (shootT <= 0f && abs(l.cx - cx) < 30f && l.y > y) {
            shootT = 2.6f
            g.spawn(Beam(cx - 3, bottom))
            g.sfx(Sound.ZAP)
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val o = 0xFF1A1420.toInt()
        // Glass dome with a little alien inside
        gfx.oval(px + 5, y - 7, 14f, 13f, o)
        gfx.oval(px + 6, y - 6, 12f, 11f, 0xAA9AE8FF.toInt())
        gfx.oval(px + 9, y - 3, 6f, 6f, 0xFF7CE07C.toInt())
        gfx.oval(px + 10, y - 2, 1.6f, 2f, o); gfx.oval(px + 13, y - 2, 1.6f, 2f, o)
        // The saucer
        gfx.oval(px - 1, y + 1, 26f, 10f, o)
        gfx.oval(px, y + 2, 24f, 8f, 0xFFB8C0CC.toInt())
        gfx.rect(px + 2, y + 5, 20f, 1.5f, 0xFF7A8494.toInt())
        for (k in 0 until 4) gfx.oval(px + 3 + k * 5f, y + 5.5f, 3f, 3f, if (((t * 6).toInt() + k) % 4 == 0) 0xFFFFE14D.toInt() else 0xFFFF6A6A.toInt())
        drawFlash(gfx, ox)
    }
}

/** A bouncy moon alien (level char '<') that hops toward you in big floaty arcs. */
class MoonHopper(x: Float, bottom: Float) : Foe(x, bottom - 14, 14f, 14f, 2) {
    private var hopT = 1f + Random.nextFloat()
    private var t = 0f

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        if (onGround) {
            vx = 0f
            hopT -= dt
            if (hopT <= 0f) {
                hopT = 1.3f
                vy = -260f
                onGround = false
                vx = if (g.leader.cx < cx) -55f else 55f
            }
        }
        vy = min(vy + GRAVITY * 0.5f * dt, MAX_FALL)
        if (g.level.moveX(this, vx * dt)) vx = -vx
        g.level.moveY(this, vy * dt)
        if (y > g.level.h * TILE + 32) removed = true
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val o = 0xFF1A1420.toInt()
        val sq = if (onGround) 2f else -1f
        // Antennae
        gfx.rect(px + 3, y - 5, 1.5f, 6f, o); gfx.rect(px + 10, y - 5, 1.5f, 6f, o)
        gfx.oval(px + 2, y - 7, 4f, 4f, 0xFFFFE14D.toInt()); gfx.oval(px + 9, y - 7, 4f, 4f, 0xFFFFE14D.toInt())
        GalaxyArt.outlined(gfx, px, y + sq, 14f, 14f - sq, 0xFF7CE07C.toInt())
        gfx.oval(px + 2, y + 2 + sq, 10f, 4f, 0x55FFFFFF)
        // One big eye
        gfx.oval(px + 3.5f, y + 3 + sq, 7f, 7f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 5.5f, y + 5 + sq, 3f, 3.5f, o)
        gfx.rect(px + 4, y + 11, 6f, 1.2f, o)
        drawFlash(gfx, ox)
    }
}

/** An arcing missile from Mecha Krag. */
class Missile(x: Float, y: Float, tx: Float) : Foe(x, y, 10f, 6f, 1), Projectile {
    private var t = 0f
    override val stompable = false
    override val showBar get() = false
    init {
        active = true
        vx = ((tx - x) / 1.2f).coerceIn(-200f, 200f)
        vy = -260f
    }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        vy = min(vy + GRAVITY * 0.5f * dt, 320f)
        x += vx * dt; y += vy * dt
        if (g.level.solidAt(cx, bottom) || t > 4f) { removed = true; g.puff(cx, cy); g.sfx(Sound.BREAK) }
        if ((t * 20).toInt() % 2 == 0) g.addParticle(Sparkle(cx - vx * 0.03f, cy, -vx * 0.1f, 10f, 0xFFFFA83A.toInt()))
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val a = atan2(vy, vx)
        val cxp = cx + ox
        val dx = cos(a); val dy = sin(a)
        gfx.poly(
            floatArrayOf(cxp + dx * 7, cxp - dx * 5 - dy * 3, cxp - dx * 5 + dy * 3),
            floatArrayOf(cy + dy * 7, cy - dy * 5 + dx * 3, cy - dy * 5 - dx * 3), 0xFF5A6070.toInt(),
        )
        gfx.oval(cxp + dx * 4 - 2, cy + dy * 4 - 2, 4f, 4f, 0xFFE84A4A.toInt())
    }
}

/** A spinning star that Galaxy Krag shoots. */
class StarShot(x: Float, y: Float, private val dvx: Float, private val dvy: Float) : Foe(x, y, 10f, 10f, 1), Projectile {
    private var t = 0f
    override val stompable = false
    override val showBar get() = false
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        x += dvx * dt; y += dvy * dt
        if (t > 4f || y > g.level.h * TILE) removed = true
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val c = solidRainbow(t * 0.7f, 0f)
        val px = cx + ox
        val xs = FloatArray(10); val ys = FloatArray(10)
        for (k in 0 until 10) {
            val r = if (k % 2 == 0) 6f else 2.6f
            val a = t * 8f + k * 0.628f
            xs[k] = px + cos(a) * r; ys[k] = cy + sin(a) * r
        }
        gfx.oval(px - 7, cy - 7, 14f, 14f, argb(60, c and 0xFFFFFF))
        gfx.poly(xs, ys, c)
    }
}

/**
 * MECHA KRAG (level char '{'), the end of Toy World: a giant wind-up robot King Krag. He fires missiles
 * that arc through the air, and STOMPS to send shockwaves along the ground.
 */
class MechaKrag(x: Float, private val ground: Float) : Foe(x - 10, ground - 40, 36f, 40f, 90) {
    var awake = false
        private set
    val homeX = x
    override val boss = true
    override val stompable = true
    private var t = 0f
    private var shootT = 2.5f
    private var stompT = 6f
    private var hurtT = 0f
    private var facing = -1
    private var stomping = false
    private val look = KingKrag(x, ground)

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        hurtT -= dt
        if (updateDying(g, dt)) return
        val l = g.leader
        if (!awake) {
            if (l.cx > homeX - g.viewW * 0.45f) {
                awake = true
                g.onBossEngaged(this)
                g.say(cx, y - 8, "BEEP BOOP! MECHA KRAG ONLINE!", 2.5f)
                g.sayAs("Beep boop! Mecha Krag online!", Voice.GOD)
            }
            return
        }
        val angry = hp < maxHp / 2
        facing = if (l.cx < cx) -1 else 1
        if (onGround && !stomping) vx = if (abs(cx - homeX) > 70f) (if (cx > homeX) -28f else 28f) else facing * 22f
        shootT -= dt
        if (shootT <= 0f) {
            shootT = if (angry) 1.7f else 2.4f
            g.spawn(Missile(if (facing > 0) x + w else x - 10, y + 6, l.cx))
            if (angry) g.spawn(Missile(if (facing > 0) x + w else x - 10, y + 6, l.cx + facing * 50f))
            g.sfx(Sound.BOSS_FIRE)
        }
        stompT -= dt
        if (stompT <= 0f && onGround) {
            stompT = if (angry) 5f else 6.5f
            vy = -340f; vx = 0f
            onGround = false
            stomping = true
            g.say(cx, y - 8, "STOMP MODE!", 1.2f)
        }
        walk(g, dt)
        if (stomping && onGround && vy >= 0f) {
            stomping = false
            g.shake = 0.5f
            g.sfx(Sound.BOSS_HIT)
            g.spawn(ChordWave(x - 16, bottom, -1))
            g.spawn(ChordWave(x + w, bottom, 1))
        }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean {
        if (!awake || dying) return false
        if (hurtT > 0 && dmg < 5) return true
        hp -= dmg
        hurtT = 0.3f
        flash = 0.2f
        g.sfx(Sound.BOSS_HIT)
        if (hp <= 0) {
            hp = 0
            knockOut(g)
            g.addScore(20000, cx, y)
            g.say(cx, y - 8, "SYSTEM... ERROR... BZZT!", 2f)
            g.onBossBeaten(this)
        }
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 3) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        look.x = x; look.y = y; look.vx = vx; look.facing = facing
        // Metal all over: King Krag tinted steel blue.
        look.draw(g, TintGfx(gfx, 0xFF9AA8C0.toInt(), 0.62f), ox)
        val px = x + ox
        val f = facing.toFloat()
        // Rivets, a glowing red eye visor and an antenna
        for (k in 0 until 4) gfx.oval(px + 8 + k * 6f, y + 24f, 2.5f, 2.5f, 0xFF5A6070.toInt())
        gfx.rect(px + 18 + f * 6 - 8, y + 8, 16f, 4f, 0xFF1A1420.toInt())
        gfx.rect(px + 18 + f * 6 - 7, y + 9, 14f, 2f, if ((t * 4).toInt() % 2 == 0) 0xFFFF3A3A.toInt() else 0xFFFF8A8A.toInt())
        gfx.rect(px + 17, y - 10, 2f, 10f, 0xFF5A6070.toInt())
        gfx.oval(px + 15, y - 14, 6f, 6f, if ((t * 3).toInt() % 2 == 0) 0xFFFFE14D.toInt() else 0xFFE84A4A.toInt())
        // A big wind-up key on his back
        val kx = px + 18 - f * 22
        val spin = abs(cos(t * 3f))
        gfx.rect(kx - 2, y + 18, 4f, 6f, 0xFFC8A030.toInt())
        gfx.oval(kx - 10 * spin - 2, y + 10, 20 * spin + 4, 10f, 0xFFE8C83A.toInt())
        drawFlash(gfx, ox)
    }
}

/**
 * GALAXY KRAG (level char '}'), the very last boss: King Krag with the power of the stars. He floats over
 * the moon shooting rings of stars, and every so often SWOOPS down low: that's your chance to jump on him!
 */
class GalaxyKrag(x: Float, private val ground: Float) : Foe(x - 10, ground - 40, 36f, 40f, 120) {
    var awake = false
        private set
    val homeX = x
    override val boss = true
    override val stompable = true
    private var t = 0f
    private var shootT = 2f
    private var swoopT = 8f
    private var swooping = 0f
    private var hurtT = 0f
    private var facing = -1
    private val look = KingKrag(x, ground)

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        hurtT -= dt
        if (updateDying(g, dt)) return
        val l = g.leader
        if (!awake) {
            if (l.cx > homeX - g.viewW * 0.45f) {
                awake = true
                g.onBossEngaged(this)
                g.say(cx, y - 8, "The whole GALAXY is mine!", 2.5f)
                g.sayAs("The whole galaxy is mine!", Voice.GOD)
            }
            return
        }
        val angry = hp < maxHp / 2
        facing = if (l.cx < cx) -1 else 1
        val floatY = ground - h - 80f + sin(t * 1.3f) * 14f
        val lowY = ground - h
        swoopT -= dt
        if (swoopT <= 0f && swooping <= 0f) { swooping = 3f; g.say(cx, y - 8, "SWOOOOP!", 1.2f) }
        val wantY = if (swooping > 0f) lowY else floatY
        if (swooping > 0f) { swooping -= dt; if (swooping <= 0f) swoopT = if (angry) 6f else 8f }
        y += (wantY - y) * min(1f, dt * 3f)
        val wantX = homeX - 10 + sin(t * 0.6f) * 110f
        x += (wantX - x) * min(1f, dt * 1.5f)
        shootT -= dt
        if (shootT <= 0f && swooping <= 0f) {
            shootT = if (angry) 1.8f else 2.6f
            // A fan of stars aimed at Rocco.
            val a0 = atan2(l.cy - cy, l.cx - cx)
            val n = if (angry) 7 else 5
            for (k in 0 until n) {
                val a = a0 + (k - (n - 1) / 2f) * 0.22f
                g.spawn(StarShot(cx - 5, cy - 5, cos(a) * 120f, sin(a) * 120f))
            }
            g.sfx(Sound.ZAP)
        }
        if ((t * 10).toInt() % 3 == 0) g.addParticle(Sparkle(cx + (Random.nextFloat() - 0.5f) * 40f, bottom, 0f, 30f, solidRainbow(t, Random.nextFloat())))
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean {
        if (!awake || dying) return false
        if (hurtT > 0 && dmg < 5) return true
        hp -= dmg
        hurtT = 0.3f
        flash = 0.2f
        g.sfx(Sound.BOSS_HIT)
        if (hp <= 0) {
            hp = 0
            knockOut(g)
            g.addScore(50000, cx, y)
            g.say(cx, y - 8, "Nooo! My galaxy!", 2f)
            g.onBossBeaten(this)
        }
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 4) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        // A starry cape glowing behind him
        gfx.oval(px - 10, y - 6, w + 20, h + 16, argb(70, solidRainbow(t * 0.3f, 0f) and 0xFFFFFF))
        gfx.poly(floatArrayOf(px + 4, px + w - 4, px + w + 8, px - 8), floatArrayOf(y + 10, y + 10, y + h + 4, y + h + 4), 0xFF2A1458.toInt())
        for (k in 0 until 6) gfx.rect(px - 4 + (k * 9f) % (w + 8), y + 16 + (k * 7f) % 26f, 1.5f, 1.5f, 0xFFFFFFFF.toInt())
        look.x = x; look.y = y; look.vx = vx; look.facing = facing
        look.draw(g, TintGfx(gfx, 0xFF8A4AE8.toInt(), 0.45f), ox)
        // A crown of stars
        for (k in 0 until 3) {
            val sx = px + 10 + k * 8f
            val sy = y - 6 - (if (k == 1) 4f else 0f)
            gfx.poly(floatArrayOf(sx, sx + 3, sx + 6, sx + 3), floatArrayOf(sy + 3, sy - 3, sy + 3, sy + 6), 0xFFFFE14D.toInt())
        }
        if (swooping > 0f && (t * 4).toInt() % 2 == 0) gfx.shadowText("JUMP ON HIM!", px + 18, y - 16, 7f, 0xFFFFE14D.toInt(), 1)
        drawFlash(gfx, ox)
    }
}

/**
 * Gives a sprite a crisp dark outline (inside its own box, so its size never changes), like the outlined
 * characters in newer games. Made once per sprite and kept.
 */
object Outline {
    private val cache = java.util.IdentityHashMap<Sprite, Sprite>()
    private const val INK = 0xFF1E1624.toInt()

    fun of(s: Sprite): Sprite = synchronized(cache) {
        cache.getOrPut(s) {
            val p = s.pixels
            fun solid(x: Int, y: Int) = x in 0 until s.w && y in 0 until s.h && (p[y * s.w + x] ushr 24) > 0x80
            Sprite(s.w, s.h, IntArray(p.size) { i ->
                val x = i % s.w; val y = i / s.w
                if ((p[i] ushr 24) > 0x80) p[i]
                else if (solid(x - 1, y) || solid(x + 1, y) || solid(x, y - 1) || solid(x, y + 1)) INK
                else p[i]
            })
        }
    }
}

/**
 * Draws everything as a dark shape 1 pixel bigger all round: draw something through this first, then
 * normally on top, and it gets a crisp outline. See-through bits (glows, shadows) and text are skipped.
 */
class SilhouetteGfx(private val inner: Gfx, private val g: Float = 1f) : Gfx {
    private val ink = 0xFF1E1624.toInt()
    private fun solid(c: Int) = (c ushr 24) >= 0xC0

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        if (solid(color)) inner.rect(x - g, y - g, w + 2 * g, h + 2 * g, ink)
    }
    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        if (solid(color)) inner.oval(x - g, y - g, w + 2 * g, h + 2 * g, ink)
    }
    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {
        if (!solid(color)) return
        // Shift a copy each way: together they make the shape a pixel fatter.
        for ((dx, dy) in OFFSETS) inner.poly(FloatArray(xs.size) { xs[it] + dx * g }, FloatArray(ys.size) { ys[it] + dy * g }, ink)
    }
    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) {}
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {}
    override fun gradient(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) {}

    private companion object {
        val OFFSETS = listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)
    }
}
