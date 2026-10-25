package com.roccoquest.team

import com.roccoquest.game.Gfx
import kotlin.math.sin

/**
 * The planet your house is on (saved by index: new ones at the END). It changes the sky and ground in the garden,
 * the backyard, the window view, the roof and the 3D yard. [extra] picks something special in the sky:
 * 0 nothing, 1 the Earth, 2 a ringed planet, 3 two moons, 4 lava glow, 5 floating crystals, 6 a rainbow.
 */
enum class HomePlanet(
    val label: String, val coins: Int, val note: String,
    val skyTop: Int, val skyBottom: Int, val ground: Int, val ground2: Int, val hill1: Int, val hill2: Int,
    val sun: Int, val extra: Int,
) {
    EARTH("PLANET EARTH", 0, "Blue sky, green grass. Home sweet home!",
        0xFF4AA4F0.toInt(), 0xFFD8F0FF.toInt(), 0xFF5AB84A.toInt(), 0xFF4AA83A.toInt(), 0xFF7AC86A.toInt(), 0xFF6AB85A.toInt(), 0xFFFFE14D.toInt(), 0),
    MOON("THE MOON", 300, "Grey craters, black sky, and the Earth up above!",
        0xFF02020A.toInt(), 0xFF1A1A30.toInt(), 0xFF9A9AA8.toInt(), 0xFF7A7A88.toInt(), 0xFF6A6A7A.toInt(), 0xFF5A5A6A.toInt(), 0xFFFFF4D0.toInt(), 1),
    MARS("PLANET MARS", 400, "The red planet: red sand and pink sky.",
        0xFFD87A5A.toInt(), 0xFFF4C8A0.toInt(), 0xFFC8582A.toInt(), 0xFFA8481E.toInt(), 0xFFB85A3A.toInt(), 0xFF9A4A2A.toInt(), 0xFFFFE8C0.toInt(), 3),
    ICE("ICE PLANET", 400, "Snow and ice everywhere. Brrr!",
        0xFF8AC8F0.toInt(), 0xFFF0FAFF.toInt(), 0xFFF4F8FF.toInt(), 0xFFD0E4F4.toInt(), 0xFFDDEEFF.toInt(), 0xFFBFD8F0.toInt(), 0xFFFFFFFF.toInt(), 3),
    CANDY("CANDY PLANET", 500, "Pink sky, frosting grass and gumdrop hills.",
        0xFFFF9AC8.toInt(), 0xFFFFF0F8.toInt(), 0xFFB8F0C8.toInt(), 0xFFFFB8D8.toInt(), 0xFFFFE0A0.toInt(), 0xFFB8F0C8.toInt(), 0xFFFFF4A0.toInt(), 6),
    JUNGLE("JUNGLE PLANET", 400, "Purple jungle and a green sky. Very alien!",
        0xFF3AA87A.toInt(), 0xFFC8F0B8.toInt(), 0xFF8A4AD8.toInt(), 0xFF6A3AB8.toInt(), 0xFF5A2A9A.toInt(), 0xFF7A3AC8.toInt(), 0xFFFFE14D.toInt(), 2),
    RINGS("RINGED PLANET", 600, "A giant planet with rings fills the sky!",
        0xFF1A1A4A.toInt(), 0xFF8A6AC8.toInt(), 0xFFE8C88A.toInt(), 0xFFC8A86A.toInt(), 0xFFA88A5A.toInt(), 0xFF8A6A4A.toInt(), 0xFFFFE8A0.toInt(), 2),
    LAVA("LAVA PLANET", 600, "Hot hot hot! Glowing lava rivers in the distance.",
        0xFF3A1A1A.toInt(), 0xFFE86A2A.toInt(), 0xFF4A3A38.toInt(), 0xFF3A2A28.toInt(), 0xFF5A3A30.toInt(), 0xFF6A2A1A.toInt(), 0xFFFFB04A.toInt(), 4),
    CRYSTAL("CRYSTAL PLANET", 800, "Sparkly floating crystals and a purple sky.",
        0xFF4A2A8A.toInt(), 0xFFE0C8FF.toInt(), 0xFFB88AF0.toInt(), 0xFF9A6AD8.toInt(), 0xFFC8A8FF.toInt(), 0xFF8A5AD8.toInt(), 0xFFFFFFFF.toInt(), 5),
    ;

    /** Things in the sky (drawn after the sky gradient, before the hills). [w] is the screen width, [h] where the sky ends. */
    fun drawSky(gfx: Gfx, w: Float, h: Float, t: Float, sunX: Float = w - 60f) {
        if (skyTop.toLong() and 0xFFFFFF < 0x303030) for (i in 0 until 24) gfx.rect((i * 89f) % w, (i * 37f) % (h * 0.6f), 1.5f, 1.5f, 0xCCFFFFFF.toInt())
        when (extra) {
            1 -> {
                gfx.oval(sunX - 4, 12f, 34f, 34f, 0xFF2A6AD8.toInt())
                gfx.oval(sunX + 2, 18f, 12f, 10f, 0xFF3AA84A.toInt()); gfx.oval(sunX + 14, 28f, 10f, 12f, 0xFF3AA84A.toInt())
            }
            2 -> {
                gfx.oval(sunX - 30, 8f, 60f, 60f, 0xFFE8B86A.toInt())
                gfx.oval(sunX - 30, 24f, 60f, 10f, 0x66FFFFFF)
                gfx.poly(floatArrayOf(sunX - 56, sunX + 56, sunX + 50, sunX - 50), floatArrayOf(40f, 34f, 40f, 46f), 0xCCF4E0B0.toInt())
            }
            3 -> { gfx.oval(sunX, 14f, 26f, 26f, sun); gfx.oval(sunX - 70, 26f, 14f, 14f, 0xFFE8E0F0.toInt()) }
            4 -> { gfx.oval(sunX, 14f, 30f, 30f, sun); gfx.oval(sunX - 6, 8f, 42f, 42f, 0x44FF6A1A) }
            5 -> for (k in 0 until 6) {
                val cx = (k * 83f + 20f) % w; val cy = 20f + (k * 29f) % 50f + sin(t * 1.5f + k) * 4f
                gfx.poly(floatArrayOf(cx, cx + 6, cx, cx - 6), floatArrayOf(cy - 10, cy, cy + 10, cy), if (k % 2 == 0) 0xFFF0C8FF.toInt() else 0xFF8AE8F0.toInt())
            }
            6 -> {
                gfx.oval(sunX, 14f, 30f, 30f, sun)
                // A rainbow: five arc bands.
                val rb = intArrayOf(0x99FF6A6A.toInt(), 0x99FFB04A.toInt(), 0x99FFE14D.toInt(), 0x996AD86A.toInt(), 0x996AA8FF.toInt())
                val cx = w * 0.35f; val cy = h
                for (k in rb.indices) {
                    val r0 = 110f - k * 6f; val r1 = r0 - 6f
                    val n = 12
                    val xs = FloatArray(2 * (n + 1)); val ys = FloatArray(2 * (n + 1))
                    for (i in 0..n) {
                        val a = Math.PI.toFloat() * i / n
                        xs[i] = cx - kotlin.math.cos(a) * r0; ys[i] = cy - sin(a) * r0
                        xs[2 * n + 1 - i] = cx - kotlin.math.cos(a) * r1; ys[2 * n + 1 - i] = cy - sin(a) * r1
                    }
                    gfx.poly(xs, ys, rb[k])
                }
            }
            else -> gfx.oval(sunX, 16f, 34f, 34f, sun)
        }
    }
}

/** World 12's planets: each level Place has a planet look, a gravity, and a name for the rocket trip. */
object PlanetArt {
    fun planetOf(p: Place): HomePlanet? = when (p) {
        Place.MARS -> HomePlanet.MARS
        Place.ICE_PLANET -> HomePlanet.ICE
        Place.CANDY_PLANET -> HomePlanet.CANDY
        Place.JUNGLE_PLANET -> HomePlanet.JUNGLE
        Place.RING_PLANET -> HomePlanet.RINGS
        Place.LAVA_PLANET -> HomePlanet.LAVA
        Place.CRYSTAL_PLANET -> HomePlanet.CRYSTAL
        else -> null
    }

    /** How heavy everyone is on this planet (1 = Earth). */
    fun gravity(p: Place): Float = when (p) {
        Place.MARS -> 0.7f
        Place.RING_PLANET -> 0.55f
        Place.CRYSTAL_PLANET, Place.JUNGLE_PLANET -> 0.85f
        else -> 1f
    }

    private val tiles = HashMap<Pair<HomePlanet, Boolean>, com.roccoquest.game.Sprite>()

    /** A ground tile in the planet's colors (cached: drawn once per planet). */
    fun ground(p: Place, under: Boolean): com.roccoquest.game.Sprite? {
        val pl = planetOf(p) ?: return null
        return tiles.getOrPut(pl to under) {
            com.roccoquest.game.Sprite(16, 16, IntArray(256) { i ->
                val x = i % 16; val y = i / 16
                val speck = (x * 7 + y * 13) % 11 == 0
                when {
                    !under && y < 3 -> if ((x + y) % 5 == 0) com.roccoquest.game.mixColor(pl.ground, 0xFFFFFFFF.toInt(), 0.25f) else pl.ground
                    speck -> com.roccoquest.game.mixColor(pl.ground2, 0xFF000000.toInt(), 0.25f)
                    else -> pl.ground2
                }
            })
        }
    }

    /** The planet's sky and hills behind a level. */
    fun drawSky(gfx: Gfx, p: Place, viewW: Float, viewH: Float, cam: Float, t: Float) {
        val pl = planetOf(p) ?: return
        gfx.gradient(0f, 0f, viewW, viewH, pl.skyTop, pl.skyBottom)
        pl.drawSky(gfx, viewW, 150f, t, viewW - 80f - cam * 0.01f)
        val off1 = cam * 0.15f
        var i = (off1 / 260f).toInt() - 1
        while (i * 260f - off1 < viewW) { gfx.oval(i * 260f - off1 - 40f, 150f + (i and 1) * 10f, 340f, 120f, pl.hill1); i++ }
        val off2 = cam * 0.3f
        i = (off2 / 200f).toInt() - 1
        while (i * 200f - off2 < viewW) {
            val x = i * 200f - off2
            gfx.oval(x, 172f + (i and 1) * 8f, 240f, 100f, pl.hill2)
            // Something special on each planet
            when (pl) {
                HomePlanet.CRYSTAL -> gfx.poly(floatArrayOf(x + 90f, x + 102f, x + 114f), floatArrayOf(178f, 136f, 178f), 0xFFE0C8FF.toInt())
                HomePlanet.CANDY -> { gfx.rect(x + 100f, 140f, 4f, 40f, 0xFFF4F4F4.toInt()); gfx.oval(x + 88f, 124f, 28f, 28f, 0xFFFF6A9A.toInt()) }
                HomePlanet.JUNGLE -> { gfx.rect(x + 100f, 130f, 6f, 50f, 0xFF4A2A6A.toInt()); gfx.oval(x + 80f, 116f, 46f, 26f, 0xFF3AA87A.toInt()) }
                HomePlanet.ICE -> gfx.poly(floatArrayOf(x + 80f, x + 100f, x + 120f), floatArrayOf(180f, 130f, 180f), 0xFFF4F8FF.toInt())
                HomePlanet.LAVA -> gfx.rect(x + 60f, 176f, 80f, 4f, 0xFFFF8A2A.toInt())
                HomePlanet.MARS -> gfx.oval(x + 90f, 168f, 30f, 8f, 0xFF8A3A1A.toInt())
                else -> {}
            }
            i++
        }
    }

    /** The rocket trip on the level's title card: the rocket flies to the planet. */
    fun drawTrip(gfx: Gfx, p: Place, viewW: Float, t: Float) {
        val pl = planetOf(p) ?: return
        val px = viewW - 70f
        gfx.oval(px - 26f, 104f, 52f, 52f, pl.ground)
        gfx.oval(px - 18f, 112f, 18f, 12f, pl.hill1)
        gfx.oval(px - 2f, 132f, 14f, 10f, pl.hill2)
        if (pl.extra == 2) gfx.poly(floatArrayOf(px - 40f, px + 40f, px + 36f, px - 36f), floatArrayOf(132f, 126f, 131f, 137f), 0xCCF4E0B0.toInt())
        val k = (t / 2.5f).coerceAtMost(1f)
        val rx = 60f + (px - 100f) * k
        val ry = 150f - sin(k * 3.14f) * 30f
        gfx.oval(rx - 22f, ry + 2f, 12f, 6f, 0xFFFF8A2A.toInt())
        gfx.rect(rx - 12f, ry, 22f, 10f, 0xFFF4F4F4.toInt())
        gfx.poly(floatArrayOf(rx + 10f, rx + 18f, rx + 10f), floatArrayOf(ry, ry + 5f, ry + 10f), 0xFFE84A4A.toInt())
        gfx.oval(rx - 2f, ry + 2f, 6f, 6f, 0xFF6AC8FF.toInt())
        gfx.poly(floatArrayOf(rx - 12f, rx - 16f, rx - 6f), floatArrayOf(ry, ry - 5f, ry), 0xFFE84A4A.toInt())
        gfx.poly(floatArrayOf(rx - 12f, rx - 16f, rx - 6f), floatArrayOf(ry + 10f, ry + 15f, ry + 10f), 0xFFE84A4A.toInt())
    }
}
