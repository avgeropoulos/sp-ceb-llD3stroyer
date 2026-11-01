package com.roccoquest.team3d

import com.roccoquest.team.HomePlanet
import com.roccoquest.team.Place
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Real 3D scenery behind a level, like the 3D Mario games: rolling round hills, puffy trees,
 * faraway mountains and floating clouds, picked to suit the place (snowy pines and snowmen,
 * desert dunes and pyramids, candy lollipops, dark spires over glowing lava, palm islands...).
 *
 * It's built once when a level starts, from the level's number, so a level always looks the same.
 * Each [Band] (near, middle, far and sky) is a few ready-made strips that repeat along the level in a
 * shuffled order (each one mirrored half the time), so long levels cost no more than short ones and
 * nothing is built while playing. A frame only draws the strips it can see.
 *
 * Everything sits well behind the level (z below [FRONT_Z]) so it can never cover a block or a bad guy.
 * The camera's own perspective makes the far bands slide by slower (real parallax), and the fog tints
 * them toward the sky so the level in front stays easy to read.
 *
 * Budget: a whole level's scenery is at most [MAX_TRIANGLES] triangles, and a frame draws at most
 * [MAX_DRAWN] of them (tested for every level in SceneryTest).
 */
object Scenery {
    /** Nothing in the scenery comes closer to the camera than this (the level's ground ends at -56). */
    const val FRONT_Z = -80f
    /** All of one level's scenery (every strip of every band, and the floor). */
    const val MAX_TRIANGLES = 10_000
    /** What one frame may draw. */
    const val MAX_DRAWN = 9_000
    /** Different strips made per band. */
    const val STRIPS = 3
    /** Fog starts just past the farthest corner of the level, so the level itself keeps its exact colours. */
    const val FOG_NEAR = 520f
    /** How much glowing things (lava, crystals, lanterns) shine regardless of light and fog. */
    const val GLOW = 0.85f

    /** The depths scenery is built at. A strip of a band is [width] long; [haze] mixes its colours toward the sky. */
    enum class Band(val z: Float, val spread: Float, val width: Float, val foot: Float, val haze: Float) {
        NEAR(-190f, 26f, 640f, -430f, 0f),
        MID(-500f, 70f, 960f, -680f, 0.1f),
        FAR(-1300f, 150f, 1600f, -900f, 0.2f),
        SKY(-950f, 160f, 1280f, -900f, 0f),
    }

    /**
     * Builds the scenery for a level in [place] (null for a place without any); [seed] (the level's number)
     * picks where everything goes.
     */
    fun build(place: Place, seed: Int): SceneryLevel? {
        val look = look(place) ?: return null
        val bands = Band.entries.toTypedArray()
        val strips = Array(bands.size) { b ->
            Array(STRIPS) { v ->
                val kit = Kit(bands[b], Random(seed * 7919 + b * 131 + v * 17 + 1), look.haze)
                if (look.floor != 0) { kit.floorY = look.floorY; kit.floorColor = look.floor }
                look.fill(kit)
                kit.strip()
            }
        }
        val floor = if (look.floor != 0) floorMesh(look.floorY, look.floor) else null
        return SceneryLevel(place, seed, bands, strips, look.cloudGlow, floor, look.floorGlow, look.haze, look.fogFar)
    }

    /** A flat sea (or lava lake, or floor) reaching from just behind the level to the horizon. */
    private fun floorMesh(y: Float, color: Int): Mesh = MeshBuilder().apply {
        // Cut into tiles (smaller near the middle) so the fog, which some renderers work out per corner,
        // still fades smoothly into the distance.
        val zs = floatArrayOf(FRONT_Z, -160f, -280f, -450f, -700f, -1000f, -1400f, -1900f, -2500f)
        val xs = floatArrayOf(-3000f, -1800f, -1100f, -600f, -250f, 0f, 250f, 600f, 1100f, 1800f, 3000f)
        for (j in 0 until zs.size - 1) for (i in 0 until xs.size - 1) {
            quad(xs[i], y, zs[j], xs[i + 1], y, zs[j], xs[i + 1], y, zs[j + 1], xs[i], y, zs[j + 1], color)
        }
    }.build()

    // ---------------------------------------------------------------- the looks of each place

    /**
     * How a place looks: [haze] is the sky colour near the horizon (the fog colour), [floor] an optional flat
     * floor colour (0 = none), and [fill] builds one strip of one band.
     */
    private class Look(
        val haze: Int,
        val fogFar: Float = 2300f,
        val cloudGlow: Float = 0.3f,
        val floor: Int = 0,
        val floorY: Float = -218f,
        val floorGlow: Float = 0f,
        val fill: Kit.() -> Unit,
    )

    private fun look(place: Place): Look? = when (place) {
        Place.HILLS -> meadow(0xFFB4DCF8, ints(0xFF7FCB6A, 0xFF6CBF5C, 0xFF8AD474), ints(0xFF5FAE6A, 0xFF74BE78),
            ints(0xFF3E9A3A, 0xFF4CB848, 0xFF2E8A3E), 0xFF6B4423.toInt(), 0xFF7E9CC4.toInt())
        Place.JUNGLE -> Look(c(0xFFB4E6C0)) {
            when (band) {
                Scenery.Band.NEAR -> { hills(4, -240f, -195f, ints(0xFF3E8A4A, 0xFF4E9A4E)); trees(2, 60f, 80f, ints(0xFF2E7A3A, 0xFF3A8A36), 0xFF5A3A1E.toInt(), tall = true); mushrooms(2, 40f, 64f, ints(0xFFE53A3A, 0xFF8A3AD8), glow = false) }
                Scenery.Band.MID -> { hills(4, -300f, -220f, ints(0xFF3A7A46, 0xFF488A4A)); mushrooms(3, 70f, 110f, ints(0xFFE53A3A, 0xFF8A3AD8, 0xFFE8A030), glow = false); trees(2, 80f, 110f, ints(0xFF2E7A3A), 0xFF5A3A1E.toInt(), tall = true) }
                Scenery.Band.FAR -> mountains(5, -360f, -220f, ints(0xFF5A8A7A, 0xFF4E7E6E), snow = 0)
                Scenery.Band.SKY -> clouds(3)
            }
        }
        Place.SUNSET -> Look(c(0xFFD07070), cloudGlow = 0.15f) {
            when (band) {
                Scenery.Band.NEAR -> { hills(4, -236f, -196f, ints(0xFF4A1E5A, 0xFF3A1448)); trees(3, 46f, 66f, ints(0xFF2A1040, 0xFF341448), 0xFF1E0A28.toInt()) }
                Scenery.Band.MID -> { hills(4, -290f, -215f, ints(0xFF5A2466, 0xFF4A1E5A)); trees(3, 70f, 90f, ints(0xFF341448), 0xFF1E0A28.toInt()) }
                Scenery.Band.FAR -> mountains(4, -330f, -200f, ints(0xFF6A2A6A, 0xFF7A3070), snow = 0)
                Scenery.Band.SKY -> clouds(2, ints(0xFFFFC8A0, 0xFFFFB0B8))
            }
        }
        Place.PREHISTORIC -> Look(c(0xFFF8D8A0)) {
            when (band) {
                Scenery.Band.NEAR -> { hills(4, -240f, -196f, ints(0xFF8AA858, 0xFF7A9A4A)); ferns(3, 34f, 48f, ints(0xFF4A8A3A, 0xFF3A7A3A)); palms(1, 60f, 80f) }
                Scenery.Band.MID -> { hills(4, -290f, -220f, ints(0xFF7A9A50, 0xFF6A8A44)); dino(0xFF8A9A6A.toInt()); ferns(2, 60f, 80f, ints(0xFF4A8A3A)) }
                Scenery.Band.FAR -> volcanoes(3, -330f, -230f, 0xFFA8705A.toInt())
                Scenery.Band.SKY -> clouds(2)
            }
        }
        Place.WONDER -> Look(c(0xFFF4E4F6)) {
            val pastel = ints(0xFF8AD86A, 0xFFFFA8D8, 0xFF6ACFD8, 0xFFC8A8FF)
            when (band) {
                Scenery.Band.NEAR -> { hills(4, -236f, -194f, pastel, eyes = true); flowers(2, 54f, 70f) }
                Scenery.Band.MID -> { hills(4, -296f, -220f, pastel, eyes = true); flowers(2, 80f, 100f) }
                Scenery.Band.FAR -> hills(5, -360f, -260f, ints(0xFFB8E8A8, 0xFFFFD0E8, 0xFFB8E8F0))
                Scenery.Band.SKY -> clouds(3)
            }
        }
        Place.JUNGLE_PLANET -> {
            val pl = HomePlanet.JUNGLE
            Look(Atlas.mix(pl.skyTop, pl.skyBottom, 0.75f)) {
                when (band) {
                    Scenery.Band.NEAR -> { hills(4, -236f, -196f, ints(pl.hill2, pl.hill1)); trees(3, 54f, 74f, ints(0xFF3AA87A, 0xFF2E9A8A), 0xFF4A2A6A.toInt()) }
                    Scenery.Band.MID -> { hills(4, -290f, -215f, ints(pl.hill1, pl.hill2)); trees(4, 80f, 104f, ints(0xFF3AA87A, 0xFF4AB86A), 0xFF4A2A6A.toInt(), tall = true) }
                    Scenery.Band.FAR -> mountains(5, -350f, -220f, ints(0xFF4A2A7A, 0xFF5A3A8A), snow = 0)
                    Scenery.Band.SKY -> floaters(2, planets = true)
                }
            }
        }
        Place.SNOW -> snowy(0xFFDCEAFA, ints(0xFFE8F0FA, 0xFFDAE6F4), 0xFF2E6A4A.toInt(), 0xFF8AA8C8.toInt())
        Place.BLIZZARD -> snowy(0xFFB4C2D6, ints(0xFFE6ECF4, 0xFFD4DCE8), 0xFF3A5A50.toInt(), 0xFF6A7C94.toInt(), fogFar = 1700f)
        Place.AURORA -> snowy(0xFF1E3458, ints(0xFFDDEEFF, 0xFFC8DCF4), 0xFF1E4A3A.toInt(), 0xFF5A6E94.toInt(), cloudGlow = 0f, withClouds = false)
        Place.ICE_PLANET -> {
            val pl = HomePlanet.ICE
            Look(Atlas.mix(pl.skyTop, pl.skyBottom, 0.75f)) {
                when (band) {
                    Scenery.Band.NEAR -> { hills(4, -236f, -196f, ints(pl.hill1, pl.hill2)); crystals(3, 30f, 54f, ints(0xFFBFE8FF, 0xFFE8F8FF), glow = false) }
                    Scenery.Band.MID -> { hills(4, -290f, -215f, ints(pl.hill1, pl.ground)); crystals(4, 60f, 100f, ints(0xFFBFE8FF, 0xFFA8D8F4), glow = false) }
                    Scenery.Band.FAR -> mountains(5, -360f, -220f, ints(0xFFA8C4E0, 0xFFBCD4EC), snow = 0xFFFFFFFF.toInt())
                    Scenery.Band.SKY -> clouds(2)
                }
            }
        }
        Place.DESERT -> Look(c(0xFFFFD49A)) {
            when (band) {
                Scenery.Band.NEAR -> { hills(3, -238f, -204f, ints(0xFFE8B070, 0xFFDCA060), wide = 1.5f); cacti(3, 36f, 56f) }
                Scenery.Band.MID -> { hills(3, -280f, -230f, ints(0xFFD89A5A, 0xFFE0A866), wide = 1.6f); pyramids(1, 120f, 160f, 0xFFE0B068.toInt()); cacti(2, 60f, 80f) }
                Scenery.Band.FAR -> { mesas(4, -320f, -230f, ints(0xFFC07048, 0xFFB86440)); pyramids(1, 300f, 380f, 0xFFD8A060.toInt()) }
                Scenery.Band.SKY -> clouds(1)
            }
        }
        Place.MARS -> {
            val pl = HomePlanet.MARS
            Look(Atlas.mix(pl.skyTop, pl.skyBottom, 0.75f), cloudGlow = 0f) {
                when (band) {
                    Scenery.Band.NEAR -> { hills(3, -238f, -204f, ints(pl.hill1, pl.ground), wide = 1.5f); rocks(4, 10f, 20f, ints(0xFF8A3A1A, 0xFF9A4A2A)) }
                    Scenery.Band.MID -> { hills(3, -280f, -228f, ints(pl.hill2, pl.hill1), wide = 1.6f); rocks(4, 18f, 34f, ints(0xFF8A3A1A)) }
                    Scenery.Band.FAR -> mesas(4, -330f, -230f, ints(0xFFA84A2A, 0xFF9A4024))
                    Scenery.Band.SKY -> floaters(1, planets = true)
                }
            }
        }
        Place.CANDY -> candy(c(0xFFFFD8EC), ints(0xFFB8F0C8, 0xFFFFE0A0, 0xFFFFB8D8, 0xFFB8D8FF))
        Place.CANDY_PLANET -> HomePlanet.CANDY.let { pl ->
            candy(Atlas.mix(pl.skyTop, pl.skyBottom, 0.75f), ints(pl.hill1, pl.hill2, pl.ground2, 0xFFB8D8FF.toInt()))
        }
        Place.CASTLE -> lava(c(0xFF3A141A), ints(0xFF2A1A1E, 0xFF3A2428), withVolcanoes = false)
        Place.VOLCANO -> lava(c(0xFF5A2010), ints(0xFF3A2420, 0xFF4A2A22), withVolcanoes = true)
        Place.LAVA_PLANET -> HomePlanet.LAVA.let { pl ->
            lava(Atlas.mix(pl.skyTop, pl.skyBottom, 0.7f), ints(pl.hill1, pl.ground), withVolcanoes = true)
        }
        Place.SHIP -> Look(c(0xFFBCE2FA), floor = c(0xFF3A8AD8), floorY = -218f) {
            when (band) {
                Scenery.Band.NEAR -> islands(1, 26f, 36f)
                Scenery.Band.MID -> islands(2, 50f, 70f)
                Scenery.Band.FAR -> islands(2, 120f, 170f)
                Scenery.Band.SKY -> clouds(3)
            }
        }
        Place.WATER -> Look(c(0xFF1E5A9A), fogFar = 1500f) {
            when (band) {
                Scenery.Band.NEAR -> { hills(4, -246f, -206f, ints(0xFFE8D8A0, 0xFFD8C890), wide = 1.3f); corals(3, 24f, 40f); seaweed(3, 50f, 80f) }
                Scenery.Band.MID -> { hills(4, -300f, -220f, ints(0xFF8A9AA8, 0xFF7A8A9A)); corals(3, 50f, 70f); seaweed(3, 90f, 130f) }
                Scenery.Band.FAR -> hills(5, -360f, -240f, ints(0xFF4A6A8A, 0xFF3A5A7A))
                Scenery.Band.SKY -> {}
            }
        }
        Place.CAVE -> cave(c(0xFF1E1A2E), ints(0xFF3A3450, 0xFF2E2840), ints(0xFFB86AF0, 0xFF5AE8F0, 0xFF5AE87A, 0xFFFFC83A))
        Place.DEEP -> cave(c(0xFF0A1E26), ints(0xFF0E2A34, 0xFF14323C), ints(0xFF2AE8E0, 0xFF1AB8C8))
        Place.CRYSTAL_PLANET -> HomePlanet.CRYSTAL.let { pl ->
            Look(Atlas.mix(pl.skyTop, pl.skyBottom, 0.75f)) {
                when (band) {
                    Scenery.Band.NEAR -> { hills(4, -236f, -196f, ints(pl.hill1, pl.ground)); crystals(3, 34f, 60f, ints(0xFFF0C8FF, 0xFF8AE8F0), glow = true) }
                    Scenery.Band.MID -> { hills(4, -290f, -215f, ints(pl.hill2, pl.hill1)); crystals(4, 70f, 120f, ints(0xFFF0C8FF, 0xFF8AE8F0, 0xFFFFA8E8), glow = true) }
                    Scenery.Band.FAR -> mountains(5, -360f, -220f, ints(0xFF8A5AD8, 0xFF9A6AE8), snow = 0xFFE0C8FF.toInt())
                    Scenery.Band.SKY -> floaters(2, crystals = true)
                }
            }
        }
        Place.NIGHT -> Look(c(0xFF2A2858), cloudGlow = 0f) {
            when (band) {
                Scenery.Band.NEAR -> { hills(4, -236f, -198f, ints(0xFF1E2A4A, 0xFF26345A)); spookyTrees(2, 56f, 76f); graves(2); pumpkins(2) }
                Scenery.Band.MID -> { hills(4, -290f, -218f, ints(0xFF222E50, 0xFF1A2444)); spookyTrees(3, 80f, 110f); pumpkins(1) }
                Scenery.Band.FAR -> { mountains(3, -320f, -230f, ints(0xFF1A2040, 0xFF20284A), snow = 0); haunted() }
                Scenery.Band.SKY -> {}
            }
        }
        Place.END -> Look(c(0xFF1E1030), cloudGlow = 0f) {
            when (band) {
                Scenery.Band.NEAR -> hills(4, -238f, -200f, ints(0xFFE0E0A0, 0xFFD0D090))
                Scenery.Band.MID -> { hills(3, -290f, -230f, ints(0xFFC8C890, 0xFFB8B884)); pillars(2, 80f, 130f) }
                Scenery.Band.FAR -> pillars(4, 260f, 420f)
                Scenery.Band.SKY -> floaters(2, endStone = true)
            }
        }
        // The level's cloud platforms are white puffs, so the cloud sea under them is sky blue and sits low
        // (white on white hid where the platforms were, and made the pits look like more cloud to stand on).
        // Its flat floor reaches down past the bottom of the screen, so the puffs never look cut off, and the haze
        // is the painted sky's blue at the horizon, so the far edge of the floor fades into it.
        Place.SKY -> Look(c(0xFF7CBEFF), cloudGlow = 0.35f, floor = c(0xFF94BCEA), floorY = -322f) {
            when (band) {
                Scenery.Band.NEAR -> cloudSea(-262f, -240f)
                Scenery.Band.MID -> { cloudSea(-296f, -256f); floaters(1, grass = true) }
                Scenery.Band.FAR -> cloudSea(-300f, -230f)
                Scenery.Band.SKY -> clouds(3)
            }
        }
        Place.TOYS -> Look(c(0xFFF0EAD8), floor = c(0xFFE0B878), floorY = -222f) {
            when (band) {
                Scenery.Band.NEAR -> toys(3, 1f)
                Scenery.Band.MID -> { toys(3, 1.8f); teddy(1.8f) }
                Scenery.Band.FAR -> toys(4, 3.4f)
                Scenery.Band.SKY -> clouds(2)
            }
        }
        Place.FUNGI -> Look(c(0xFF5A2470), cloudGlow = 0f) {
            when (band) {
                Scenery.Band.NEAR -> { hills(4, -236f, -198f, ints(0xFF2A1A44, 0xFF3A2252)); mushrooms(3, 40f, 64f, ints(0xFF5AF0C8, 0xFFFF6AC8, 0xFF6AA8FF), glow = true) }
                Scenery.Band.MID -> { hills(4, -290f, -218f, ints(0xFF2E1E4A, 0xFF241640)); mushrooms(3, 90f, 140f, ints(0xFF5AF0C8, 0xFFFF6AC8, 0xFF6AA8FF), glow = true) }
                Scenery.Band.FAR -> mountains(4, -330f, -230f, ints(0xFF2A1440, 0xFF341A4A), snow = 0)
                Scenery.Band.SKY -> {}
            }
        }
        Place.MOON -> Look(c(0xFF1A1A30), cloudGlow = 0f) {
            when (band) {
                Scenery.Band.NEAR -> { hills(4, -236f, -198f, ints(0xFF7A7A8A, 0xFF6A6A7A), craters = true); rocks(3, 8f, 16f, ints(0xFF5A5A6A)) }
                Scenery.Band.MID -> { hills(4, -290f, -218f, ints(0xFF6A6A7A, 0xFF5E5E6E), craters = true); rocks(3, 16f, 30f, ints(0xFF5A5A6A)) }
                Scenery.Band.FAR -> hills(5, -340f, -250f, ints(0xFF4A4A5A, 0xFF545466))
                Scenery.Band.SKY -> floaters(2, planets = true)
            }
        }
        Place.ORBIT, Place.RAINBOW -> Look(c(0xFF0A0E2A), cloudGlow = 0f) {
            when (band) {
                Scenery.Band.NEAR -> {}
                Scenery.Band.MID -> if (place == Place.ORBIT) satellites(1) else floaters(1)
                Scenery.Band.FAR -> floaters(2, planets = true)
                Scenery.Band.SKY -> floaters(3)
            }
        }
        Place.RING_PLANET -> HomePlanet.RINGS.let { pl ->
            Look(Atlas.mix(pl.skyTop, pl.skyBottom, 0.7f), cloudGlow = 0f) {
                when (band) {
                    Scenery.Band.NEAR -> { hills(4, -236f, -198f, ints(pl.hill1, pl.ground2)); rocks(3, 10f, 20f, ints(pl.hill2)) }
                    Scenery.Band.MID -> { hills(4, -290f, -218f, ints(pl.hill2, pl.hill1)); rocks(3, 20f, 34f, ints(pl.hill2)) }
                    Scenery.Band.FAR -> mesas(4, -330f, -240f, ints(0xFF7A5A3A, 0xFF8A6A4A))
                    Scenery.Band.SKY -> floaters(2, planets = true)
                }
            }
        }
        // Inside the space station: its big painted windows are the view.
        Place.STATION -> null
        // The Big City at night: low buildings with water tanks just behind the level, tall skyscrapers with neon
        // signs, antennas and cranes with blinking lights behind them, and a moonlit skyline in the haze far away.
        // All darker than the level's own buildings, so its rooftops stand out in front.
        Place.CITY -> Look(c(0xFF3A2C6A), cloudGlow = 0f) {
            when (band) {
                Scenery.Band.NEAR -> skyscrapers(6, -262f, -190f, ints(0xFF1C1A3C, 0xFF201C46, 0xFF1A2244), 0.22f, tanks = true)
                Scenery.Band.MID -> { skyscrapers(6, -330f, -110f, ints(0xFF282452, 0xFF2C2858, 0xFF222A54), 0.2f, signs = true); if (rnd.nextInt(3) != 0) crane() }
                Scenery.Band.FAR -> skyscrapers(8, -470f, -170f, ints(0xFF3A3268, 0xFF34306A, 0xFF40366E), 0.24f)
                Scenery.Band.SKY -> {}
            }
        }
        // The Food Kingdom: toast and lettuce hills (melted cheese on the middle ones) with giant food on them, broccoli
        // trees, carrots, tomatoes, cheese wedges, forks stuck in the ground and steaming bowls of soup, then tomatoes as
        // big as hills and cheese mountains far away. No red near the level: its hot soup has to stand out.
        Place.FOOD -> Look(c(0xFFD2EAEE)) {
            when (band) {
                Scenery.Band.NEAR -> { hills(4, -240f, -198f, ints(0xFFE0A860, 0xFF8AD86A, 0xFFD89A52)); broccoli(1, 44f, 62f); carrots(3, 30f, 42f) }
                Scenery.Band.MID -> {
                    hills(4, -296f, -218f, ints(0xFFF0C888, 0xFF7ACC62, 0xFFE8B070), cheesy = true)
                    broccoli(1, 74f, 98f); forks(1, 130f, 170f); soupBowls(1); carrots(1, 30f, 40f); cheeseWedges(1, 26f, 34f); tomatoes(1, 14f, 20f)
                }
                Scenery.Band.FAR -> { hills(2, -390f, -300f, ints(0xFF8AD86A, 0xFF6AC85A)); tomatoHills(2, -330f, -250f); cheeseMountains(2, -330f, -230f) }
                Scenery.Band.SKY -> clouds(2)
            }
        }
    }

    private fun meadow(haze: Long, near: IntArray, mid: IntArray, leaves: IntArray, trunk: Int, far: Int) = Look(c(haze)) {
        when (band) {
            Scenery.Band.NEAR -> { hills(4, -238f, -196f, near); trees(3, 50f, 70f, leaves, trunk); bushes(3, near) }
            Scenery.Band.MID -> { hills(4, -296f, -216f, mid); trees(5, 76f, 104f, leaves, trunk) }
            Scenery.Band.FAR -> mountains(5, -370f, -210f, ints(far, Atlas.shade(far, 0.92f)), snow = 0xFFFFFFFF.toInt())
            Scenery.Band.SKY -> clouds(3)
        }
    }

    private fun snowy(haze: Long, snow: IntArray, pine: Int, far: Int, fogFar: Float = 2300f, cloudGlow: Float = 0.3f, withClouds: Boolean = true) =
        Look(c(haze), fogFar = fogFar, cloudGlow = cloudGlow) {
            when (band) {
                Scenery.Band.NEAR -> { hills(4, -238f, -198f, snow); pines(3, 50f, 74f, pine); snowman() }
                Scenery.Band.MID -> { hills(4, -296f, -218f, snow); pines(5, 80f, 120f, pine) }
                Scenery.Band.FAR -> mountains(5, -380f, -220f, ints(far, Atlas.shade(far, 0.9f)), snow = 0xFFFFFFFF.toInt())
                Scenery.Band.SKY -> if (withClouds) clouds(2)
            }
        }

    private fun candy(haze: Int, gumdrops: IntArray) = Look(haze) {
        when (band) {
            Scenery.Band.NEAR -> { hills(4, -236f, -196f, gumdrops, gumdrops = true); lollipops(3, 56f, 76f) }
            Scenery.Band.MID -> { hills(4, -292f, -218f, gumdrops, gumdrops = true); lollipops(3, 90f, 120f) }
            Scenery.Band.FAR -> mountains(5, -360f, -220f, ints(0xFFFFF4F8, 0xFFFFE8F0), snow = 0xFFFF8AC0.toInt())
            Scenery.Band.SKY -> clouds(3, ints(0xFFFFFFFF, 0xFFFFE0F0))
        }
    }

    private fun lava(haze: Int, rock: IntArray, withVolcanoes: Boolean) =
        Look(haze, cloudGlow = 0f, floor = 0xFFE8561A.toInt(), floorY = Kit.FLOOR_LAVA, floorGlow = 0.75f) {
            // Volcanoes stand in the lava lake, so their feet are at its shore (not far down under it).
            val shore = Kit.FLOOR_LAVA - 30f
            when (band) {
                Scenery.Band.NEAR -> { spires(4, 70f, 120f, rock); lavaPools(2) }
                Scenery.Band.MID -> { spires(5, 130f, 220f, rock); if (withVolcanoes) volcanoes(1, -190f, -160f, 0xFF6A3A2A.toInt(), shore, 1.1f) }
                Scenery.Band.FAR -> if (withVolcanoes) volcanoes(3, -150f, -70f, 0xFF7A4432.toInt(), shore, 0.95f) else spires(6, 220f, 330f, rock)
                Scenery.Band.SKY -> {}
            }
        }

    private fun cave(haze: Int, rock: IntArray, gems: IntArray) = Look(haze, cloudGlow = 0f, fogFar = 1800f) {
        when (band) {
            Scenery.Band.NEAR -> { hills(4, -246f, -214f, rock); stalagmites(4, 50f, 90f, rock); crystals(3, 24f, 40f, gems, glow = true) }
            Scenery.Band.MID -> { hills(4, -300f, -236f, rock); stalagmites(5, 100f, 170f, rock); crystals(3, 50f, 80f, gems, glow = true) }
            Scenery.Band.FAR -> columns(4, rock)
            Scenery.Band.SKY -> stalactites(6, rock, gems)
        }
    }

    private fun ints(vararg c: Long) = IntArray(c.size) { c[it].toInt() }
    private fun ints(vararg c: Int) = c
    private fun c(v: Long) = v.toInt()
}

/**
 * One ready-made strip of scenery: the [mesh], the parts that glow ([glow], or null) and little lights that
 * blink on and off ([blink], or null: the tips of the city's antennas and cranes).
 */
class SceneryStrip(val mesh: Mesh, val glow: Mesh?, val blink: Mesh? = null) {
    val triangles get() = (mesh.vertexCount + (glow?.vertexCount ?: 0) + (blink?.vertexCount ?: 0)) / 3
}

/** A level's scenery, ready to draw: built once by [Scenery.build], then [draw]n every frame. */
class SceneryLevel internal constructor(
    val place: Place,
    private val seed: Int,
    val bands: Array<Scenery.Band>,
    val strips: Array<Array<SceneryStrip>>,
    private val cloudGlow: Float,
    val floor: Mesh?,
    private val floorGlow: Float,
    /** The fog colour: the sky near the horizon. */
    val haze: Int,
    /** Where the fog is thickest. */
    val fogFar: Float,
) {
    /** Every mesh this level's scenery uses. */
    val meshes: List<Mesh> = strips.flatMap { s -> s.flatMap { listOfNotNull(it.mesh, it.glow, it.blink) } } + listOfNotNull(floor)
    /** All of its triangles. */
    val triangles = meshes.sumOf { it.vertexCount } / 3
    /** Triangles sent by the last [draw]. */
    var drawn = 0
        private set

    // How far each band's strips reach past their ends, and how far back they go (for working out what's on screen).
    private val pad = FloatArray(bands.size)
    private val farZ = FloatArray(bands.size)

    init {
        for (b in bands.indices) {
            var minX = 0f; var maxX = bands[b].width; var minZ = 0f
            for (s in strips[b]) for (mesh in listOfNotNull(s.mesh, s.glow, s.blink)) {
                val d = mesh.data
                var i = 0
                while (i < d.size) {
                    minX = min(minX, d[i]); maxX = max(maxX, d[i]); minZ = min(minZ, d[i + 2])
                    i += Mesh.STRIDE
                }
            }
            pad[b] = max(-minX, maxX - bands[b].width)
            farZ[b] = minZ
        }
    }

    private val m = Mat4()

    /**
     * Adds the strips that can be seen with the level scrolled to [cam]. [sway] is how far (radians) the
     * camera may turn sideways, like on the title screen. [time] makes the blinking lights blink.
     */
    fun draw(list: DrawList, cam: Float, sway: Float = 0f, time: Float = 0f) {
        drawn = 0
        val centre = cam + list.viewW / 2
        for (b in bands.indices) {
            val w = bands[b].width
            val reach = list.halfWidthAt(farZ[b]) + (list.camPos[2] - farZ[b]) * sway + pad[b]
            val k0 = floor((centre - reach) / w).toInt()
            val k1 = floor((centre + reach) / w).toInt()
            for (k in k0..k1) {
                var h = mix(b, k)
                val prev = mix(b, k - 1)
                // Never the same strip the same way round twice in a row.
                if (pick(h) == pick(prev)) h = h xor 1
                val strip = strips[b][(h ushr 1) % strips[b].size]
                val flip = h and 1 == 1
                m.identity().translate(k * w - cam + if (flip) w else 0f, 0f, 0f)
                if (flip) m.scale(-1f, 1f, 1f)
                list.add(strip.mesh, m, -1, if (bands[b] == Scenery.Band.SKY) cloudGlow else 0f)
                strip.glow?.let { list.add(it, m, -1, Scenery.GLOW) }
                // Each copy of a strip blinks in its own rhythm; while off, its lights are dim (not gone).
                strip.blink?.let { list.add(it, m, if ((time * 1.4f + (k and 7) * 0.37f) % 2f < 1.2f) -1 else BLINK_OFF, 1f) }
                drawn += strip.triangles
            }
        }
        floor?.let {
            // The floor is one plain colour, so it can simply stay put under the camera.
            m.identity().translate(list.viewW / 2, 0f, 0f)
            list.add(it, m, -1, floorGlow)
            drawn += it.vertexCount / 3
        }
    }

    private fun pick(h: Int) = ((h ushr 1) % strips[0].size) * 2 + (h and 1)

    /** Which strip goes at place [k] of band [b] (and which way round): the same every time for this level. */
    private fun mix(b: Int, k: Int): Int {
        var h = seed * -0x61c88647 + b * 0x27d4eb2d + k * 0x165667b1
        h = h xor (h ushr 15)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        return h and 0x7FFFFFFF
    }

    /** Frees the meshes (the level is over). */
    fun release() = meshes.forEach { it.release() }

    private companion object {
        /** A blinking light while it's off. */
        val BLINK_OFF = 0xFF3A3040.toInt()
    }
}

/**
 * Builds one strip of one band. All the shapes are low-poly on purpose (a strip has to be cheap),
 * and every colour is mixed toward the sky by the band's [Scenery.Band.haze].
 */
private class Kit(val band: Scenery.Band, val rnd: Random, private val haze: Int) {
    val b = MeshBuilder()
    val glow = MeshBuilder()
    /** Little lights that blink (full bright, even through the fog). */
    val blink = MeshBuilder()
    val w get() = band.width
    /** The hills made so far: x, foot y, z, rx, ry, rz (for standing things on them). */
    private val hillList = ArrayList<FloatArray>()
    /** The flat floor's height and colour, when there is one (things stand on it where there's no hill). */
    var floorY = Float.NEGATIVE_INFINITY
    var floorColor = 0

    fun strip() = SceneryStrip(b.build(), if (glow.isEmpty) null else glow.build(), if (blink.isEmpty) null else blink.build())

    fun col(color: Int) = if (band.haze > 0f) Atlas.mix(color, haze, band.haze) else color or 0xFF000000.toInt()
    fun r(lo: Float, hi: Float) = lo + rnd.nextFloat() * (hi - lo)
    fun pick(a: IntArray) = a[rnd.nextInt(a.size)]
    /** A depth in this band, kept at least [depth] behind [Scenery.FRONT_Z]. */
    fun z(depth: Float, front: Float = 1f) = min(band.z + r(-band.spread, band.spread * front), Scenery.FRONT_Z - depth)
    /** How much bigger things are in this band than in the near one. */
    val size get() = when (band) { Scenery.Band.NEAR -> 1f; Scenery.Band.MID -> 1.6f; else -> 3.2f }

    /** [n] spots spread along the strip with a little wobble, so neighbouring strips join up evenly. */
    inline fun along(n: Int, wobble: Float = 0.35f, f: (Float) -> Unit) {
        for (i in 0 until n) f((i + 0.5f + r(-wobble, wobble)) * w / n)
    }

    /** The height of the ground at ([x], [z]): the highest hill there, or the floor. */
    fun groundAt(x: Float, z: Float): Float {
        var y = floorY
        for (h in hillList) {
            val dx = (x - h[0]) / h[3]; val dz = (z - h[2]) / h[5]
            val q = 1f - dx * dx - dz * dz
            if (q > 0f) y = max(y, h[1] + h[4] * sqrt(q))
        }
        return y
    }

    /** Picks a spot on the ground for something [depth] deep and builds it there with [f] (if there's ground there). */
    inline fun onGround(depth: Float, f: (x: Float, y: Float, z: Float) -> Unit) {
        val x = r(0f, w)
        val z = z(depth, 0.6f)
        val y = groundAt(x, z)
        if (y > band.foot + 40f) f(x, y, z)
    }

    // ---------------------------------------------------------------- shapes

    /**
     * Part of an ellipsoid between the latitudes [lats] (radians, -PI/2..PI/2), shaded from [low] at the
     * bottom to [top] at the top. Open where it's cut: the cut parts are always hidden.
     */
    fun ball(
        cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, top: Int, low: Int = top,
        slices: Int = 8, lats: FloatArray = PUFF, into: MeshBuilder = b,
    ) {
        val t0 = lats.first(); val t1 = lats.last()
        fun v(lat: Float, lon: Float) {
            val cl = cos(lat); val x = cl * cos(lon); val y = sin(lat); val z = -cl * sin(lon)
            val k = ((lat - t0) / (t1 - t0)).coerceIn(0f, 1f)
            into.vertex(cx + x * rx, cy + y * ry, cz + z * rz, x / rx, y / ry, z / rz, Atlas.mix(low, top, k), U, V)
        }
        for (j in 0 until lats.size - 1) {
            val a0 = lats[j]; val a1 = lats[j + 1]
            for (i in 0 until slices) {
                val l0 = TAU * i / slices; val l1 = TAU * (i + 1) / slices
                when {
                    a1 >= HALF_PI - 1e-4f -> { v(a0, l0); v(a0, l1); v(a1, (l0 + l1) / 2) }
                    a0 <= -HALF_PI + 1e-4f -> { v(a0, (l0 + l1) / 2); v(a1, l1); v(a1, l0) }
                    else -> { v(a0, l0); v(a0, l1); v(a1, l1); v(a0, l0); v(a1, l1); v(a1, l0) }
                }
            }
        }
    }

    /** A cone from [y0] to its tip at [y1] (pointing down when [y1] is lower). [flat] gives crisp facets. */
    fun cone(x: Float, y0: Float, z: Float, y1: Float, r: Float, color: Int, slices: Int = 6, flat: Boolean = false, into: MeshBuilder = b, rz: Float = r, tip: Int = color) {
        val h = abs(y1 - y0); val up = if (y1 >= y0) 1f else -1f
        for (i in 0 until slices) {
            val a0 = TAU * i / slices; val a1 = TAU * (i + 1) / slices; val am = (a0 + a1) / 2
            fun v(a: Float, y: Float, rr: Float, normalAt: Float, c: Int) =
                into.vertex(x + cos(a) * rr, y, z - sin(a) * rr * rz / r, cos(normalAt) * h, up * r, -sin(normalAt) * h, c, U, V)
            v(a0, y0, r, if (flat) am else a0, color)
            v(a1, y0, r, if (flat) am else a1, color)
            v(am, y1, 0f, am, tip)
        }
    }

    /**
     * A round shape facing the camera (eyes, petals, candy): a flat disc of radius [rx] x [ry] that bulges
     * [depth] toward the camera, so it shades like a button. Only the front is built: the camera never sees
     * behind it, and a round outline costs just [slices] triangles this way.
     */
    fun lens(x: Float, y: Float, z: Float, rx: Float, ry: Float, depth: Float, color: Int, slices: Int = 10, into: MeshBuilder = b) {
        for (i in 0 until slices) {
            val a0 = TAU * i / slices; val a1 = TAU * (i + 1) / slices
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            into.vertex(x, y, z + depth, 0f, 0f, 1f, color, U, V)
            into.vertex(x + c0 * rx, y + s0 * ry, z, c0, s0, 1.2f, color, U, V)
            into.vertex(x + c1 * rx, y + s1 * ry, z, c1, s1, 1.2f, color, U, V)
        }
    }

    /** A cone with its top cut off at radius [rTop] (tree trunks, volcanoes, mesas); [cap] closes the top. */
    fun frustum(x: Float, y0: Float, z: Float, y1: Float, r0: Float, rTop: Float, color: Int, slices: Int = 6, cap: Boolean = false, into: MeshBuilder = b, topColor: Int = color) {
        into.cylinder(x, y0, z, y1, r0, color, rTop = rTop, slices = slices, caps = false)
        if (cap) into.disc(x, y1, z, rTop, rTop, topColor, slices)
    }

    // ---------------------------------------------------------------- props

    /** A row of [n] rolling hills with their tops between [lo] and [hi] (world y, up is +). */
    fun hills(n: Int, lo: Float, hi: Float, colors: IntArray, wide: Float = 1f, eyes: Boolean = false, gumdrops: Boolean = false, craters: Boolean = false, cheesy: Boolean = false) {
        val spacing = w / n
        val depth = when (band) { Scenery.Band.NEAR -> 64f; Scenery.Band.MID -> 150f; else -> 320f }
        val slices = if (band == Scenery.Band.NEAR) 11 else 10
        along(n) { x ->
            val top = r(lo, hi)
            val rx = spacing * r(0.72f, 0.95f) * wide
            val rz = depth * r(0.8f, 1.1f)
            val cz = z(rz)
            val color = pick(colors)
            val foot = band.foot
            ball(x, foot, cz, rx, top - foot, rz, col(color), col(Atlas.shade(color, 0.72f)), slices, DOME)
            hillList += floatArrayOf(x, foot, cz, rx, top - foot, rz)
            if (gumdrops) {
                // Sugar sparkles on the gumdrops.
                repeat(2) {
                    val sx = x + r(-0.4f, 0.4f) * rx
                    val sz = cz + rz * r(0.4f, 0.6f)
                    val sy = groundAt(sx, sz)
                    ball(sx, sy, sz, 3f * size, 2.4f * size, 3f * size, col(0xFFFFFFFF.toInt()), slices = 5, lats = SMALL)
                }
            }
            if (craters && rnd.nextInt(2) == 0) {
                // Near the top, where the camera looking down can see it.
                val sx = x + r(-0.3f, 0.3f) * rx
                val sz = cz + rz * 0.35f
                val sy = groundAt(sx, sz)
                val cr = 12f * size
                ball(sx, sy - cr * 0.15f, sz, cr, cr * 0.25f, cr * 0.6f, col(Atlas.shade(color, 0.75f)), slices = 8, lats = DOME)
            }
            if (cheesy && rnd.nextInt(3) != 0) {
                // Melted cheese over the top (a little bigger than the hill, so it sits on it), dripping down the front.
                val k = 1.025f
                val ry = (top - foot) * k
                val edge = col(Atlas.shade(MELTED, 0.9f))
                ball(x, foot, cz, rx * k, ry, rz * k, col(MELTED), edge, slices, CHEESE_CAP)
                for (d in -1..1 step 2) {
                    // Each drip hangs from the edge of the cheese, on the side facing the camera.
                    val lon = -HALF_PI + d * r(0.2f, 0.5f)
                    val dh = r(10f, 16f) * size
                    val lat = kotlin.math.asin(((sin(CHEESE_CAP[0]) * ry - dh * 0.55f) / ry).coerceIn(-1f, 1f))
                    val dx = cos(lat) * cos(lon); val dz = -cos(lat) * sin(lon)
                    ball(x + dx * rx * k, foot + sin(lat) * ry, cz + dz * rz * k + 2f * size, 5f * size, dh, 4f * size, edge, slices = 5, lats = BALL6)
                }
            }
            if (eyes && top > lo + (hi - lo) * 0.3f && rnd.nextInt(4) != 0) {
                // The taller Wonder hills look at you, like in Wonder World. The eyes sit just under the
                // top on the side facing the camera (lower down they'd be hidden behind the level).
                val ry = top - foot
                val up = (ry - 17f * size) / ry
                val side = sqrt(1f - up * up)
                val ez = cz + rz * side
                val ey = foot + ry * up
                // Tilted back to lie flat on the slope.
                val tilt = kotlin.math.atan2(up / ry, side / rz)
                val gap = min(rx * 0.16f, 11f * size)
                for (s in -1..1 step 2) {
                    b.place { translate(x + s * gap, ey, ez); rotateX(-tilt) }
                    // A dark rim, the white, and a big pupil, like the 2D hills' eyes.
                    lens(0f, 0f, 1f, 8.6f * size, 11.6f * size, 2f * size, col(0xFF2A1A10.toInt()), 10)
                    lens(0f, 0f, 1.6f + 0.6f * size, 7f * size, 10f * size, 3f * size, col(0xFFFFFFFF.toInt()), 10)
                    lens(s * 0.8f * size, -1.5f * size, 2f + 3.4f * size, 3.8f * size, 6f * size, 1.2f * size, col(0xFF2A1A10.toInt()), 8)
                    b.resetPlace()
                }
            }
        }
    }

    /** Puffy round trees ([tall] ones have a longer trunk and a stack of puffs). */
    fun trees(n: Int, lo: Float, hi: Float, leaves: IntArray, trunk: Int, tall: Boolean = false) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.3f) { x, y, z ->
            val leaf = pick(leaves)
            val tr = h * 0.06f
            frustum(x, y - 6f, z, y + h * (if (tall) 0.7f else 0.5f), tr * 1.4f, tr, col(trunk), slices = 5)
            val r = h * (if (tall) 0.24f else 0.3f)
            val cy = y + h * (if (tall) 0.78f else 0.66f)
            ball(x, cy, z, r, r * 1.1f, r, col(leaf), col(Atlas.shade(leaf, 0.8f)), slices = 7)
            ball(x - r * 0.7f, cy - r * 0.45f, z + r * 0.25f, r * 0.68f, r * 0.68f, r * 0.68f, col(Atlas.shade(leaf, 1.08f)), col(Atlas.shade(leaf, 0.85f)), slices = 6)
            if (tall) ball(x + r * 0.6f, cy + r * 0.5f, z - r * 0.2f, r * 0.62f, r * 0.62f, r * 0.62f, col(leaf), slices = 6)
        }
    }

    fun bushes(n: Int, colors: IntArray) = repeat(n) {
        onGround(14f) { x, y, z ->
            val c = Atlas.shade(pick(colors), 0.82f)
            val s = r(9f, 13f) * size
            ball(x, y + s * 0.2f, z, s, s * 0.85f, s * 0.8f, col(c), slices = 6)
            ball(x - s * 0.9f, y, z + 2f, s * 0.7f, s * 0.6f, s * 0.6f, col(Atlas.shade(c, 1.06f)), slices = 5)
            ball(x + s * 0.9f, y - 1f, z + 1f, s * 0.65f, s * 0.55f, s * 0.6f, col(c), slices = 5)
        }
    }

    /** Faraway mountains, with snowy tops when [snow] isn't 0. */
    fun mountains(n: Int, lo: Float, hi: Float, colors: IntArray, snow: Int) = along(n, 0.3f) { x ->
        val peak = r(lo, hi)
        val foot = band.foot
        val r = (peak - foot) * r(0.75f, 1.0f)
        val z = z(r * 0.6f)
        val c = pick(colors)
        cone(x, foot, z, peak, r, col(c), slices = 7, flat = true, rz = r * 0.6f, tip = col(Atlas.shade(c, 1.12f)))
        if (snow != 0) {
            val capY = peak - (peak - foot) * 0.24f
            cone(x, capY, z, peak + 1f, r * 0.255f, col(snow), slices = 7, flat = true, rz = r * 0.255f * 0.6f)
        }
    }

    /** Flat-topped desert rocks. */
    fun mesas(n: Int, lo: Float, hi: Float, colors: IntArray) = along(n, 0.3f) { x ->
        val top = r(lo, hi)
        val foot = band.foot
        val r = (top - foot) * r(0.36f, 0.5f)
        val z = z(r * 0.7f)
        val c = pick(colors)
        frustum(x, foot, z, top, r, r * 0.62f, col(c), slices = 7, cap = true, topColor = col(Atlas.shade(c, 1.1f)))
    }

    fun pines(n: Int, lo: Float, hi: Float, color: Int) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.3f) { x, y, z ->
            frustum(x, y - 6f, z, y + h * 0.3f, h * 0.06f, h * 0.05f, col(0xFF5A3A22.toInt()), slices = 5)
            for (t in 0 until 3) {
                val y0 = y + h * (0.18f + t * 0.24f)
                val rr = h * (0.3f - t * 0.065f)
                cone(x, y0, z, y0 + h * 0.4f, rr, col(Atlas.shade(color, 0.9f + t * 0.08f)), slices = 7, tip = col(0xFFFFFFFF.toInt()))
            }
        }
    }

    fun snowman() = onGround(18f * size) { x, y, z ->
        val s = 22f * size
        val white = col(0xFFF8FBFF.toInt())
        ball(x, y + s * 0.38f, z, s * 0.48f, s * 0.44f, s * 0.48f, white, slices = 8)
        ball(x, y + s * 0.98f, z, s * 0.34f, s * 0.32f, s * 0.34f, white, slices = 8)
        ball(x, y + s * 1.42f, z, s * 0.25f, s * 0.24f, s * 0.25f, white, slices = 8)
        // A carrot nose, coal eyes and a little hat.
        b.place { translate(x, y + s * 1.4f, z + s * 0.22f); rotateX(HALF_PI) }
        cone(0f, 0f, 0f, s * 0.24f, s * 0.05f, col(0xFFFF8A2A.toInt()), slices = 4)
        b.resetPlace()
        for (e in -1..1 step 2) ball(x + e * s * 0.09f, y + s * 1.5f, z + s * 0.21f, s * 0.035f, s * 0.035f, s * 0.035f, col(0xFF1A1A1A.toInt()), slices = 4, lats = SMALL)
        frustum(x, y + s * 1.6f, z, y + s * 1.85f, s * 0.15f, s * 0.15f, col(0xFF2A2A34.toInt()), slices = 7, cap = true)
        b.disc(x, y + s * 1.61f, z, s * 0.24f, s * 0.24f, col(0xFF2A2A34.toInt()), 8)
    }

    fun cacti(n: Int, lo: Float, hi: Float) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.15f) { x, y, z ->
            val g = col(0xFF3E9A3A.toInt())
            val r = h * 0.11f
            frustum(x, y - 6f, z, y + h * 0.85f, r, r, g, slices = 6)
            ball(x, y + h * 0.85f, z, r, r, r, g, slices = 6, lats = DOME)
            for (s in -1..1 step 2) {
                if (rnd.nextInt(3) == 0) continue
                val ay = y + h * r(0.35f, 0.5f)
                val ax = x + s * h * 0.26f
                b.place { translate(x + s * h * 0.12f, ay, z); rotateZ(-s * HALF_PI) }
                b.cylinder(0f, 0f, 0f, h * 0.15f, r * 0.7f, g, slices = 5, caps = false)
                b.resetPlace()
                frustum(ax, ay - r * 0.7f, z, ay + h * 0.28f, r * 0.7f, r * 0.7f, g, slices = 5)
                ball(ax, ay + h * 0.28f, z, r * 0.7f, r * 0.7f, r * 0.7f, g, slices = 5, lats = DOME)
            }
        }
    }

    fun pyramids(n: Int, lo: Float, hi: Float, color: Int) = repeat(n) {
        val s = r(lo, hi)
        onGround(s * 0.75f) { x, y, z ->
            // A face turned toward the camera, so it shows the 2D sand colour.
            b.place { translate(x, y - s * 0.12f, z); rotateY(PI.toFloat() / 4) }
            cone(0f, 0f, 0f, s * 0.75f, s * 0.75f, col(color), slices = 4, flat = true, tip = col(Atlas.shade(color, 1.1f)))
            b.resetPlace()
        }
    }

    fun rocks(n: Int, lo: Float, hi: Float, colors: IntArray) = repeat(n) {
        val s = r(lo, hi) * size
        onGround(s) { x, y, z ->
            val c = pick(colors)
            ball(x, y - s * 0.3f, z, s * r(1f, 1.5f), s * r(0.6f, 0.9f), s, col(c), col(Atlas.shade(c, 0.8f)), slices = 6, lats = DOME)
        }
    }

    /** Lollipop trees: a white stick and a swirly round candy facing the camera. */
    fun lollipops(n: Int, lo: Float, hi: Float) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.12f) { x, y, z ->
            val c = pick(CANDY)
            frustum(x, y - 6f, z, y + h * 0.7f, h * 0.035f, h * 0.035f, col(0xFFF8F8F8.toInt()), slices = 5)
            val r = h * 0.28f
            val cy = y + h * 0.72f
            // A thick round candy with a white swirl ring and a coloured middle.
            b.place { translate(x, cy, z); rotateX(HALF_PI) }
            b.cylinder(0f, -r * 0.25f, 0f, 0f, r, col(Atlas.shade(c, 0.9f)), slices = 12, caps = false)
            b.resetPlace()
            lens(x, cy, z, r, r, r * 0.12f, col(c), 12)
            lens(x, cy, z + r * 0.06f + 0.8f, r * 0.68f, r * 0.68f, r * 0.08f, col(0xFFFFFFFF.toInt()), 12)
            lens(x, cy, z + r * 0.1f + 1.6f, r * 0.38f, r * 0.38f, r * 0.06f, col(c), 10)
        }
    }

    /** Dark rocky spires sticking up out of the lava. */
    fun spires(n: Int, lo: Float, hi: Float, colors: IntArray) = along(n, 0.45f) { x ->
        val h = r(lo, hi)
        val r = h * r(0.16f, 0.24f)
        val z = z(r)
        val base = FLOOR_LAVA - 20f
        val c = pick(colors)
        b.place { translate(x, base, z); rotateZ(r(-0.1f, 0.1f)) }
        cone(0f, 0f, 0f, h + 20f, r, col(c), slices = 5, flat = true, tip = col(Atlas.shade(c, 1.4f)))
        b.resetPlace()
        // A smaller spike beside it, and a glowing crack of lava at its foot.
        cone(x + r * 0.9f, base, z + r * 0.3f, base + h * 0.45f, r * 0.55f, col(Atlas.shade(c, 0.9f)), slices = 5, flat = true)
        glow.disc(x, FLOOR_LAVA + 1.5f + (-z) * 0.003f, z + r * 0.4f, r * 1.5f, r * 0.7f, col(0xFFFFA83A.toInt()), 9)
    }

    fun lavaPools(n: Int) = repeat(n) {
        val x = r(0f, w); val z = z(30f)
        glow.disc(x, FLOOR_LAVA + 1.5f, z, r(30f, 50f), r(12f, 20f), col(0xFFFFD04A.toInt()), 10)
    }

    /** Volcanoes with a glowing crater and a puff of smoke. */
    fun volcanoes(n: Int, lo: Float, hi: Float, color: Int, foot: Float = band.foot, width: Float = 0.85f) = along(n, 0.3f) { x ->
        val peak = r(lo, hi)
        val r = (peak - foot) * width
        val z = z(r * 0.6f)
        val c = col(color)
        b.cylinder(x, foot, z, peak, r, c, rTop = r * 0.16f, slices = 8, caps = false, zScale = 0.6f)
        glow.disc(x, peak + 0.5f, z, r * 0.16f, r * 0.1f, col(0xFFFF7A1A.toInt()), 8)
        // Two streams of lava down the front.
        for (s in -1..1 step 2) {
            val a = HALF_PI + s * 0.35f
            val lx = x + cos(a) * r * 0.16f; val lz = z - sin(a) * r * 0.1f
            glow.place { translate(lx, peak, lz + 2f); rotateX(-0.25f); rotateZ(-s * 0.25f) }
            glow.box(-r * 0.03f, -(peak - foot) * 0.45f, -1f, r * 0.03f, 0f, 1f, col(0xFFFF6A1A.toInt()))
            glow.resetPlace()
        }
        // A little puff of smoke drifting up.
        val smoke = col(0xFF7A6A68.toInt())
        ball(x - r * 0.02f, peak + r * 0.07f, z, r * 0.06f, r * 0.045f, r * 0.05f, smoke, slices = 6)
        ball(x + r * 0.05f, peak + r * 0.14f, z, r * 0.075f, r * 0.055f, r * 0.06f, smoke, slices = 6)
    }

    /** Little islands with sandy beaches and palm trees, out on the sea. */
    fun islands(n: Int, lo: Float, hi: Float) = along(n, 0.45f) { x ->
        val s = r(lo, hi)
        val z = z(s * 1.3f)
        val sea = -218f
        // White foam around a sandy beach, with a grassy middle.
        b.disc(x, sea + 1f + (-z) * 0.003f, z, s * 1.55f, s * 0.95f, col(0xFFE8F6FF.toInt()), 12)
        ball(x, sea - s * 0.7f, z, s * 1.4f, s * 0.95f, s * 0.85f, col(0xFFF0DCA0.toInt()), col(0xFFD8C080.toInt()), 10, DOME)
        ball(x - s * 0.15f, sea - s * 0.5f, z - s * 0.1f, s * 0.95f, s * 0.95f, s * 0.6f, col(0xFF5AB85A.toInt()), col(0xFF3E9A3A.toInt()), 9, DOME)
        hillList += floatArrayOf(x - s * 0.15f, sea - s * 0.5f, z - s * 0.1f, s * 0.95f, s * 0.95f, s * 0.6f)
        palm(x + s * 0.1f, groundAt(x + s * 0.1f, z + s * 0.1f), z + s * 0.1f, s * 1.3f)
        if (s > 40f) palm(x - s * 0.5f, groundAt(x - s * 0.5f, z), z, s * 1.0f)
    }

    fun palms(n: Int, lo: Float, hi: Float) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.3f) { x, y, z -> palm(x, y, z, h) }
    }

    /** A palm tree: a curvy trunk and drooping leaves. */
    fun palm(x: Float, y: Float, z: Float, h: Float) {
        val lean = if (rnd.nextBoolean()) 1f else -1f
        var px = x; var py = y - 4f
        val seg = h * 0.25f
        for (i in 0 until 4) {
            val a = lean * (0.08f + i * 0.07f)
            b.place { translate(px, py, z); rotateZ(-a) }
            b.cylinder(0f, 0f, 0f, seg + 1f, h * (0.05f - i * 0.006f), col(if (i % 2 == 0) 0xFF9A6A3A.toInt() else 0xFF8A5A2E.toInt()), slices = 5, caps = false)
            b.resetPlace()
            px += sin(a) * seg; py += cos(a) * seg
        }
        val leaf = col(0xFF3EA83A.toInt())
        for (i in 0 until 5) {
            val turn = i * TAU / 5 + 0.3f
            b.place { translate(px, py, z); rotateY(turn); rotateZ(-0.45f); translate(h * 0.24f, 0f, 0f) }
            ball(0f, 0f, 0f, h * 0.28f, h * 0.04f, h * 0.09f, leaf, slices = 6, lats = SMALL)
            b.resetPlace()
        }
        ball(px, py - h * 0.04f, z + h * 0.04f, h * 0.05f, h * 0.05f, h * 0.05f, col(0xFF6A4A22.toInt()), slices = 5, lats = SMALL)
    }

    fun corals(n: Int, lo: Float, hi: Float) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.4f) { x, y, z ->
            val c = col(pick(CORAL))
            for (i in 0 until 3) {
                val a = (i - 1) * 0.5f + r(-0.15f, 0.15f)
                val len = h * r(0.55f, 0.9f)
                b.place { translate(x, y - 4f, z); rotateZ(-a) }
                b.cylinder(0f, 0f, 0f, len, h * 0.07f, c, rTop = h * 0.05f, slices = 5, caps = false)
                b.resetPlace()
                ball(x + sin(a) * len, y - 4f + cos(a) * len, z, h * 0.09f, h * 0.09f, h * 0.09f, c, slices = 5, lats = SMALL)
            }
        }
    }

    fun seaweed(n: Int, lo: Float, hi: Float) = repeat(n) {
        val h = r(lo, hi)
        onGround(6f) { x, y, z ->
            val g = col(if (rnd.nextBoolean()) 0xFF2E9E5A.toInt() else 0xFF3AAE6A.toInt())
            for (k in 0 until 5) {
                val sx = x + (if (k % 2 == 0) -1f else 1f) * h * 0.04f
                lens(sx, y + h * (0.1f + k * 0.18f), z, h * 0.06f, h * 0.12f, h * 0.03f, g, 6)
            }
        }
    }

    fun stalagmites(n: Int, lo: Float, hi: Float, colors: IntArray) = along(n, 0.45f) { x ->
        val h = r(lo, hi)
        val r = h * r(0.18f, 0.26f)
        val z = z(r)
        val y = groundAt(x, z)
        // Only where there's cave floor to stand on.
        if (y > band.foot) {
            val c = pick(colors)
            cone(x, y - 10f, z, y + h, r, col(c), slices = 6, flat = true, tip = col(Atlas.shade(c, 1.3f)))
        }
    }

    /** Pointy rocks hanging from the cave roof, some with a glowing gem. */
    fun stalactites(n: Int, colors: IntArray, gems: IntArray) = along(n, 0.45f) { x ->
        val h = r(80f, 170f)
        val r = h * r(0.2f, 0.3f)
        val z = z(r)
        val roof = 150f
        val c = pick(colors)
        cone(x, roof, z, roof - h, r, col(c), slices = 6, flat = true, tip = col(Atlas.shade(c, 1.3f)))
        if (rnd.nextInt(2) == 0) {
            val gx = x + r * 0.7f
            glow.place { translate(gx, roof - h * 0.35f, z + r * 0.4f); rotateZ(PI.toFloat() + r(-0.3f, 0.3f)) }
            cone(0f, 0f, 0f, h * 0.35f, r * 0.3f, col(pick(gems)), slices = 4, flat = true, into = glow)
            glow.resetPlace()
        }
    }

    /** Huge rock columns from the floor to the roof, far away in the dark. */
    fun columns(n: Int, colors: IntArray) = along(n, 0.35f) { x ->
        val r = r(90f, 150f)
        val z = z(r)
        val c = col(pick(colors))
        b.cylinder(x, band.foot, z, 400f, r, c, rTop = r * 0.75f, slices = 7, caps = false)
        // Flared at the bottom and the top, like a real cave column.
        cone(x, -460f, z, -200f, r * 1.8f, c, slices = 7, flat = true, rz = r * 1.2f)
        cone(x, 300f, z, 40f, r * 1.6f, c, slices = 7, flat = true, rz = r * 1.1f)
    }

    /** Glowing crystals poking out of the ground. */
    fun crystals(n: Int, lo: Float, hi: Float, colors: IntArray, glow: Boolean) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.3f) { x, y, z ->
            val into = if (glow) this.glow else b
            for (k in 0 until 3) {
                val hh = h * (if (k == 0) 1f else r(0.45f, 0.7f))
                val tilt = (k - 1) * 0.35f + r(-0.1f, 0.1f)
                val c = pick(colors)
                into.place { translate(x + (k - 1) * h * 0.12f, y - 6f, z); rotateZ(-tilt) }
                cone(0f, 0f, 0f, hh, h * 0.13f, col(c), slices = 5, flat = true, into = into, tip = col(Atlas.shade(c, 1.15f)))
                into.resetPlace()
            }
        }
    }

    fun mushrooms(n: Int, lo: Float, hi: Float, caps: IntArray, glow: Boolean) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.4f) { x, y, z ->
            val stem = col(if (glow) 0xFFE8D8F0.toInt() else 0xFFF0E0C0.toInt())
            frustum(x, y - 6f, z, y + h * 0.7f, h * 0.1f, h * 0.08f, stem, slices = 6)
            val cap = pick(caps)
            val r = h * 0.4f
            ball(x, y + h * 0.66f, z, r, r * 0.65f, r * 0.8f, col(cap), col(Atlas.shade(cap, 0.75f)), slices = 10, lats = DOME, into = if (glow) this.glow else b)
            // White spots on the cap.
            for (s in -1..1 step 2) {
                ball(x + s * r * 0.42f, y + h * 0.66f + r * 0.36f, z + r * 0.55f, r * 0.14f, r * 0.11f, r * 0.08f, col(0xFFFFFFFF.toInt()), slices = 5, lats = SMALL)
            }
        }
    }

    fun ferns(n: Int, lo: Float, hi: Float, colors: IntArray) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.4f) { x, y, z ->
            val c = col(pick(colors))
            for (i in 0 until 6) {
                val a = (i - 2.5f) * 0.42f
                b.place { translate(x, y, z); rotateY(if (i % 2 == 0) 0.5f else -0.5f); rotateZ(-a); translate(0f, h * 0.45f, 0f) }
                ball(0f, 0f, 0f, h * 0.07f, h * 0.48f, h * 0.025f, c, slices = 4, lats = SMALL)
                b.resetPlace()
            }
        }
    }

    /** A long-necked dinosaur strolling along far away. */
    fun dino(color: Int) = onGround(40f) { x, y, z ->
        val s = 34f
        val c = col(color)
        val dir = if (rnd.nextBoolean()) 1f else -1f
        ball(x, y + s * 1.2f, z, s * 1.1f, s * 0.6f, s * 0.55f, c, col(Atlas.shade(color, 0.8f)), slices = 9, lats = FULL)
        for (lx in floatArrayOf(-0.6f, -0.2f, 0.25f, 0.65f)) frustum(x + lx * s, y - 4f, z + (if (lx < 0f) 4f else -4f), y + s * 1.1f, s * 0.15f, s * 0.15f, c, slices = 5)
        b.place { translate(x + dir * s * 0.8f, y + s * 1.3f, z); rotateZ(-dir * 0.5f) }
        b.cylinder(0f, 0f, 0f, s * 1.5f, s * 0.18f, c, rTop = s * 0.12f, slices = 6, caps = false)
        b.resetPlace()
        val hx = x + dir * (s * 0.8f + sin(0.5f) * s * 1.5f)
        val hy = y + s * 1.3f + cos(0.5f) * s * 1.5f
        ball(hx + dir * s * 0.12f, hy, z, s * 0.28f, s * 0.18f, s * 0.2f, c, slices = 7, lats = FULL)
        b.place { translate(x - dir * s * 0.9f, y + s * 1.2f, z); rotateZ(dir * 1.9f) }
        cone(0f, 0f, 0f, s * 1.4f, s * 0.3f, c, slices = 6)
        b.resetPlace()
    }

    /** Giant smiling flowers (Wonder World). */
    fun flowers(n: Int, lo: Float, hi: Float) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.25f) { x, y, z ->
            frustum(x, y - 6f, z, y + h * 0.8f, h * 0.04f, h * 0.035f, col(0xFF3A9A4A.toInt()), slices = 5)
            lens(x + h * 0.1f, y + h * 0.35f, z, h * 0.12f, h * 0.04f, h * 0.03f, col(0xFF4AC85A.toInt()), 6)
            val fy = y + h * 0.84f
            val pc = pick(PETALS)
            for (k in 0 until 6) {
                val a = k * TAU / 6
                lens(x + cos(a) * h * 0.16f, fy + sin(a) * h * 0.16f, z, h * 0.11f, h * 0.11f, h * 0.04f, col(pc), 8)
            }
            // A smiling middle.
            lens(x, fy, z + h * 0.02f, h * 0.12f, h * 0.12f, h * 0.05f, col(0xFFFFC83A.toInt()), 10)
            for (e in -1..1 step 2) lens(x + e * h * 0.04f, fy + h * 0.015f, z + h * 0.07f, h * 0.014f, h * 0.025f, h * 0.01f, col(0xFF2A1A10.toInt()), 5)
        }
    }

    fun spookyTrees(n: Int, lo: Float, hi: Float) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.25f) { x, y, z ->
            val c = col(0xFF141A2E.toInt())
            cone(x, y - 6f, z, y + h, h * 0.08f, c, slices = 5, flat = true)
            for (k in 0 until 4) {
                val side = if (k % 2 == 0) 1f else -1f
                b.place { translate(x, y + h * (0.4f + k * 0.13f), z); rotateZ(-side * r(0.7f, 1.1f)) }
                cone(0f, 0f, 0f, h * (0.38f - k * 0.05f), h * 0.03f, c, slices = 4, flat = true)
                b.resetPlace()
            }
        }
    }

    fun graves(n: Int) = repeat(n) {
        onGround(8f) { x, y, z ->
            val s = 10f * size
            val c = col(0xFF5A6070.toInt())
            b.box(x - s * 0.5f, y - 4f, z - s * 0.2f, x + s * 0.5f, y + s, z + s * 0.2f, c)
            b.place { translate(x, y + s, z); rotateX(HALF_PI) }
            b.cylinder(0f, -s * 0.2f, 0f, s * 0.2f, s * 0.5f, c, slices = 8, caps = true)
            b.resetPlace()
        }
    }

    /** Glowing jack-o'-lanterns. */
    fun pumpkins(n: Int) = repeat(n) {
        onGround(10f) { x, y, z ->
            val s = 8f * size
            ball(x, y + s * 0.55f, z, s, s * 0.75f, s, col(0xFFFF8A1A.toInt()), col(0xFFC85A10.toInt()), slices = 9, lats = FULL, into = glow)
            frustum(x, y + s * 1.2f, z, y + s * 1.55f, s * 0.12f, s * 0.1f, col(0xFF3A6A2A.toInt()), slices = 4)
            for (e in -1..1 step 2) b.box(x + e * s * 0.35f - s * 0.12f, y + s * 0.65f, z + s * 0.86f, x + e * s * 0.35f + s * 0.12f, y + s * 0.85f, z + s * 0.98f, col(0xFF3A1A06.toInt()))
        }
    }

    /** A spooky castle on a faraway hill, with lit windows. */
    fun haunted() {
        if (rnd.nextInt(2) == 0) return
        val x = r(w * 0.3f, w * 0.7f)
        val z = z(160f)
        val base = -440f
        val c = col(0xFF141830.toInt())
        for (t in -1..1) {
            val tx = x + t * 70f
            val top = base + if (t == 0) 230f else 170f
            frustum(tx, band.foot, z, top, 32f, 32f, c, slices = 6)
            cone(tx, top, z, top + 90f, 44f, c, slices = 6, flat = true)
            glow.box(tx - 6f, top - 60f, z + 31f, tx + 6f, top - 40f, z + 33f, col(0xFFFFD84A.toInt()))
        }
        b.box(x - 70f, band.foot, z - 30f, x + 70f, base + 120f, z + 28f, c)
    }

    /** Obsidian pillars with a glowing crystal on top (the End). */
    fun pillars(n: Int, lo: Float, hi: Float) = along(n, 0.4f) { x ->
        val h = r(lo, hi)
        val s = h * 0.12f
        val z = z(s)
        val y = max(groundAt(x, z), band.foot) - 10f
        b.box(x - s, y, z - s, x + s, y + h, z + s, col(0xFF1A1028.toInt()))
        glow.place { translate(x, y + h + s * 1.1f, z) }
        ball(0f, 0f, 0f, s * 0.8f, s * 0.8f, s * 0.8f, col(0xFFE070FF.toInt()), slices = 6, lats = SMALL, into = glow)
        glow.resetPlace()
    }

    /**
     * A sea of clouds (the Cloud Skies levels have no ground). Sky blue, not white: the level's own cloud
     * platforms are white, and they have to stand out from it.
     */
    fun cloudSea(lo: Float, hi: Float) = along(if (band == Scenery.Band.NEAR) 5 else 6) { x ->
        val top = r(lo, hi)
        val r = w / 6f * r(0.8f, 1.1f)
        val z = z(r * 0.5f)
        // Each puff rises out of the floor (no gap under it).
        val foot = min(top - r * 0.7f, floorY)
        ball(x, foot, z, r, top - foot, r * 0.5f, col(0xFFB0D2F2.toInt()), col(0xFF84AEE0.toInt()), slices = 10, lats = DOME)
        hillList += floatArrayOf(x, foot, z, r, top - foot, r * 0.5f)
    }

    /** Big soft clouds floating high up. */
    fun clouds(n: Int, colors: IntArray = WHITES) = along(n, 0.4f) { x ->
        val s = r(40f, 66f)
        // Below the HUD bar along the top, and above most of the mountain tops.
        val y = r(-125f, -82f)
        val z = band.z + r(-band.spread, band.spread)
        val c = col(pick(colors))
        val under = col(Atlas.mix(c, 0xFFB8C8E0.toInt(), 0.35f))
        ball(x, y, z, s, s * 0.62f, s * 0.6f, c, under, slices = 8, lats = FULL)
        ball(x - s * 0.95f, y - s * 0.2f, z + 4f, s * 0.68f, s * 0.46f, s * 0.5f, c, under, slices = 7, lats = FULL)
        ball(x + s * 0.95f, y - s * 0.24f, z - 2f, s * 0.6f, s * 0.42f, s * 0.48f, c, under, slices = 7, lats = FULL)
    }

    /** The heights (world y) floating things may use in this band: on screen below the HUD bar and above the level's ground. */
    private val skyLo get() = when (band) { Scenery.Band.MID -> -200f; Scenery.Band.FAR -> -270f; else -> -150f }
    private val skyHi get() = when (band) { Scenery.Band.MID -> -60f; Scenery.Band.FAR -> -45f; else -> -28f }
    /** A height for something reaching [below] under and [above] over its middle. */
    private fun skyY(below: Float, above: Float) = r(skyLo + below, max(skyLo + below, skyHi - above))

    /**
     * Things floating in the sky: rocks, little planets with rings, end-stone or grassy islands, crystals.
     * Kept high up, above where the level is played and below the HUD.
     */
    fun floaters(n: Int, planets: Boolean = false, endStone: Boolean = false, grass: Boolean = false, crystals: Boolean = false) = along(n, 0.4f) { x ->
        val z = band.z + r(-band.spread, band.spread) * (if (band == Scenery.Band.MID) 0.3f else 1f)
        val big = if (band == Scenery.Band.FAR) 1.8f else 1f
        when {
            grass -> {
                val s = r(24f, 34f)
                val y = skyY(s * 1.6f, s * 1.7f)
                ball(x, y, z, s * 1.3f, s * 0.35f, s * 0.8f, col(0xFF6CC85A.toInt()), col(0xFF4AA84A.toInt()), slices = 9, lats = DOME)
                cone(x, y, z, y - s * 1.6f, s * 1.25f, col(0xFF8A6A4A.toInt()), slices = 7, flat = true, rz = s * 0.75f)
                trees2(x, y + s * 0.3f, z, s * 1.4f)
            }
            endStone -> {
                val s = r(36f, 60f) * big
                val y = skyY(s * 1.1f, s * 0.25f)
                ball(x, y, z, s, s * 0.22f, s * 0.6f, col(0xFFE0E0A0.toInt()), slices = 9, lats = DOME)
                cone(x, y, z, y - s * 1.1f, s * 0.95f, col(0xFF9A9A6A.toInt()), slices = 7, flat = true, rz = s * 0.57f)
            }
            crystals -> {
                val s = r(24f, 44f) * big
                val y = skyY(s * 0.5f, s)
                val c = col(pick(GEMS))
                glow.place { translate(x, y, z); rotateZ(r(-0.4f, 0.4f)) }
                cone(0f, 0f, 0f, s, s * 0.3f, c, slices = 5, flat = true, into = glow)
                cone(0f, 0f, 0f, -s * 0.5f, s * 0.3f, c, slices = 5, flat = true, into = glow)
                glow.resetPlace()
            }
            planets && rnd.nextInt(2) == 0 -> {
                val s = r(22f, 38f) * big
                val y = skyY(s, s)
                val c = pick(PLANETS)
                ball(x, y, z, s, s, s, col(c), col(Atlas.shade(c, 0.7f)), slices = 12, lats = FULL)
                if (rnd.nextBoolean()) ring(x, y, z, s * 1.35f, s * 1.9f, col(0xFFF4E0B0.toInt()))
            }
            else -> {
                val s = r(9f, 20f) * big
                val y = skyY(s, s)
                val c = pick(ROCKS)
                b.place { translate(x, y, z); rotateZ(r(0f, 3f)); rotateX(r(0f, 3f)) }
                ball(0f, 0f, 0f, s * 1.2f, s * 0.8f, s, col(c), col(Atlas.shade(c, 0.7f)), slices = 6, lats = BALL6)
                b.resetPlace()
            }
        }
    }

    private fun trees2(x: Float, y: Float, z: Float, h: Float) {
        frustum(x, y - 4f, z, y + h * 0.5f, h * 0.07f, h * 0.05f, col(0xFF6B4423.toInt()), slices = 5)
        ball(x, y + h * 0.66f, z, h * 0.3f, h * 0.33f, h * 0.3f, col(0xFF3E9A3A.toInt()), col(0xFF2E7A2E.toInt()))
    }

    /** A flat ring around a little planet. */
    private fun ring(x: Float, y: Float, z: Float, r0: Float, r1: Float, color: Int) {
        b.place { translate(x, y, z); rotateX(0.35f); rotateZ(0.3f) }
        val n = 16
        for (i in 0 until n) {
            val a0 = TAU * i / n; val a1 = TAU * (i + 1) / n
            b.quad(cos(a0) * r0, 0f, -sin(a0) * r0, cos(a0) * r1, 0f, -sin(a0) * r1, cos(a1) * r1, 0f, -sin(a1) * r1, cos(a1) * r0, 0f, -sin(a1) * r0, color)
        }
        b.resetPlace()
    }

    /** Satellites with solar panels and a dish (the space walk). */
    fun satellites(n: Int) = along(n, 0.4f) { x ->
        val z = band.z + r(-band.spread, band.spread) * 0.3f
        val s = r(16f, 22f)
        val y = skyY(s, s * 1.2f)
        b.place { translate(x, y, z); rotateZ(r(-0.25f, 0.25f)) }
        // A gold body, a rod each side holding a blue solar panel, and a white dish on top.
        b.box(-s * 0.6f, -s * 0.45f, -s * 0.45f, s * 0.6f, s * 0.45f, s * 0.45f, col(0xFFE8C060.toInt()))
        b.box(-s * 1.1f, -s * 0.06f, -s * 0.06f, s * 1.1f, s * 0.06f, s * 0.06f, col(0xFF9AA0AA.toInt()))
        for (side in -1..1 step 2) {
            val px = side * s * 1.9f
            b.box(px - s * 0.8f, -s * 0.42f, -s * 0.05f, px + s * 0.8f, s * 0.42f, s * 0.05f, col(0xFF3A62C0.toInt()))
            b.box(px - s * 0.03f, -s * 0.42f, -s * 0.03f, px + s * 0.03f, s * 0.42f, s * 0.07f, col(0xFF8AA8E8.toInt()))
        }
        cone(0f, s * 0.95f, 0f, s * 0.5f, s * 0.4f, col(0xFFF4F4F4.toInt()), slices = 8)
        b.resetPlace()
        glow.place { translate(x, y + s * 1.05f, z) }
        ball(0f, 0f, 0f, 2.2f, 2.2f, 2.2f, col(0xFFFF4A4A.toInt()), slices = 4, lats = SMALL, into = glow)
        glow.resetPlace()
    }

    /** A soft dark patch on the floor under something, so it looks like it's standing there (not floating). */
    fun contactShadow(x: Float, z: Float, rx: Float, rz: Float) {
        if (floorColor == 0) return
        // Lifted a little (more further away) so the floor never shows through it.
        b.disc(x, floorY + 1.5f + (-z) * 0.003f, z, rx, rz, col(Atlas.shade(floorColor, 0.78f)), 12)
    }

    /** Toys as big as hills: stacked blocks, balls and spinning tops on a wooden floor. */
    fun toys(n: Int, s: Float) {
        along(n, 0.4f) { x ->
            val z = z(20f * s)
            contactShadow(x, z, 19f * s, 15f * s)
            when (rnd.nextInt(3)) {
                0 -> {
                    var y = floorY
                    val stack = 1 + rnd.nextInt(3)
                    for (k in 0 until stack) {
                        val bs = 15f * s * (1f - k * 0.1f)
                        val ox = (if (k % 2 == 0) 0f else bs * 0.35f)
                        b.box(x + ox - bs, y, z - bs, x + ox + bs, y + bs * 2f, z + bs, col(TOY[(k + rnd.nextInt(4)) % TOY.size]))
                        y += bs * 2f
                    }
                }
                1 -> {
                    val r = 14f * s
                    val c1 = pick(TOY); val c2 = TOY[(TOY.indexOf(c1) + 1) % TOY.size]
                    ball(x, floorY + r, z, r, r, r, col(c1), slices = 10, lats = floatArrayOf(-HALF_PI, -0.8f, -0.25f, 0f))
                    ball(x, floorY + r, z, r, r, r, col(c2), slices = 10, lats = floatArrayOf(0f, 0.25f, 0.8f, HALF_PI))
                }
                else -> {
                    val r = 13f * s
                    val c = pick(TOY)
                    cone(x, floorY + r * 0.9f, z, floorY - 2f, r, col(c), slices = 8)
                    cone(x, floorY + r * 0.9f, z, floorY + r * 1.4f, r, col(Atlas.shade(c, 1.15f)), slices = 8)
                    frustum(x, floorY + r * 1.3f, z, floorY + r * 1.9f, r * 0.08f, r * 0.08f, col(0xFF8A5A2A.toInt()), slices = 5)
                }
            }
        }
    }

    /** A giant teddy bear sitting among the toys. */
    fun teddy(s: Float) {
        if (rnd.nextInt(2) == 0) return
        val x = r(w * 0.2f, w * 0.8f)
        val z = z(24f * s)
        val y = floorY
        val c = col(0xFFB8783A.toInt()); val light = col(0xFFF0D0A0.toInt())
        contactShadow(x, z, 30f * s, 20f * s)
        ball(x, y + 18f * s, z, 20f * s, 22f * s, 16f * s, c, slices = 9, lats = FULL)
        ball(x, y + 46f * s, z + 2f * s, 15f * s, 14f * s, 13f * s, c, slices = 9, lats = FULL)
        for (e in -1..1 step 2) {
            ball(x + e * 11f * s, y + 58f * s, z, 5.5f * s, 5.5f * s, 4f * s, c, slices = 7, lats = FULL)
            ball(x + e * 18f * s, y + 6f * s, z + 8f * s, 7f * s, 6f * s, 7f * s, c, slices = 7, lats = FULL)
            ball(x + e * 5f * s, y + 50f * s, z + 13.5f * s, 1.6f * s, 1.6f * s, 1f * s, col(0xFF1A1008.toInt()), slices = 4, lats = SMALL)
        }
        ball(x, y + 42f * s, z + 13f * s, 6f * s, 4.5f * s, 3f * s, light, slices = 7, lats = FULL)
        ball(x, y + 44f * s, z + 15.5f * s, 2f * s, 1.5f * s, 1f * s, col(0xFF1A1008.toInt()), slices = 4, lats = SMALL)
    }

    // ---------------------------------------------------------------- the Big City (World 14)

    /**
     * How far in front of a wall its windows and signs sit. More far away, where the phone's depth buffer is coarse
     * (a window right on its wall would flicker).
     */
    private val lift get() = 0.6f * size * size

    /**
     * The lowest point of this band the level doesn't hide (behind the back edge of its ground, with some room):
     * windows further down would never be seen.
     */
    private val seenY get() = when (band) { Scenery.Band.NEAR -> -300f; Scenery.Band.MID -> -440f; else -> -790f }

    /** The highest point of this band that shows under the HUD bar (antennas and spires stop there). */
    private val ceilingY get() = when (band) { Scenery.Band.NEAR -> -45f; Scenery.Band.MID -> -40f; else -> -20f }

    /** A box seen from the front: only its front, top and sides (the camera never sees the back or the bottom). */
    fun block(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, color: Int, into: MeshBuilder = b) {
        into.quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, color)
        into.quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, color)
        into.quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, color)
        into.quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, color)
    }

    /** A flat rectangle facing the camera at depth [z]. */
    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float, z: Float, color: Int, into: MeshBuilder = b) =
        into.quad(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, color)

    /** A wall facing the camera, [low] at the bottom fading up to [high] at the top. */
    private fun upright(x0: Float, y0: Float, x1: Float, y1: Float, z: Float, low: Int, high: Int) {
        b.vertex(x0, y0, z, 0f, 0f, 1f, low, U, V); b.vertex(x1, y0, z, 0f, 0f, 1f, low, U, V); b.vertex(x1, y1, z, 0f, 0f, 1f, high, U, V)
        b.vertex(x0, y0, z, 0f, 0f, 1f, low, U, V); b.vertex(x1, y1, z, 0f, 0f, 1f, high, U, V); b.vertex(x0, y1, z, 0f, 0f, 1f, high, U, V)
    }

    /**
     * A row of [n] skyscrapers with their roofs between [lo] and [hi] (world y), about [lit] of their windows lit.
     * Neighbours stand in two rows, one further back, so they can overlap without their walls meeting; and none
     * reaches past the strip's ends, so strips side by side never overlap either. On the roofs: water tanks
     * ([tanks]), or stepped tops, antennas with blinking lights and spires; now and then a neon sign ([signs]).
     */
    fun skyscrapers(n: Int, lo: Float, hi: Float, bodies: IntArray, lit: Float, tanks: Boolean = false, signs: Boolean = false) {
        val spacing = w / n
        var i = 0
        along(n, 0.2f) { x ->
            val row = i++ % 2
            val hw = min(spacing * r(0.34f, 0.56f), min(x, w - x) - 2f)
            if (hw < 5f * size) return@along
            val hd = hw * r(0.6f, 0.9f)
            val zf = min(band.z + band.spread * (0.6f - row * 0.9f + r(-0.1f, 0.1f)), Scenery.FRONT_Z - 3f * lift)
            val top = r(lo, hi)
            val body = pick(bodies)
            val glass = if (rnd.nextInt(4) == 0) COOL else WARM
            tower(x, zf, hw, hd, top, body, lit * r(0.6f, 1.4f), glass)
            if (signs && rnd.nextInt(3) == 0) {
                val sw = hw * r(0.5f, 0.72f)
                val sh = sw * r(0.24f, 0.32f)
                neonSign(x + (hw - sw) * r(-0.6f, 0.6f), top - sh - r(8f, 30f) * size, zf, sw, sh)
            }
            var roof = top; var rhw = hw; var rhd = hd; var rzf = zf
            if (!tanks && rnd.nextInt(3) == 0) {
                // A narrower top, stepped back.
                val sh = r(12f, 26f) * size
                rhw = hw * r(0.5f, 0.72f); rhd = hd * 0.6f; rzf = zf - hd * 0.3f
                tower(x, rzf, rhw, rhd, top + sh, body, lit, glass, foot = top - 1f, shadeLow = 1.05f)
                roof = top + sh
            }
            val room = ceilingY - roof - 3f * size
            when {
                tanks -> if (rnd.nextInt(3) != 0) waterTank(x + hw * r(-0.45f, 0.45f), roof, zf - hd, r(9f, 13f))
                rnd.nextInt(5) < 2 -> antenna(x + rhw * r(-0.4f, 0.4f), roof, rzf - rhd, min(r(16f, 30f) * size, room))
                rnd.nextInt(3) == 0 -> spire(x, roof, rzf - rhd, min(rhw, rhd) * 1.35f, room, body)
            }
        }
    }

    /**
     * A skyscraper with its front at depth [zf], from [foot] up to [top]: a box [hw] half wide and [hd] half deep,
     * moonlit at the top and darker down in the street, with rows of windows of which about [lit] glow in [glass]
     * colours. Only the lit windows are built (two triangles each), and only where they can be seen.
     */
    fun tower(
        x: Float, zf: Float, hw: Float, hd: Float, top: Float, body: Int, lit: Float, glass: IntArray,
        foot: Float = band.foot, shadeLow: Float = 0.55f,
    ) {
        val back = zf - 2f * hd
        val c = col(body)
        val seen = max(seenY, foot)
        val low = col(Atlas.shade(body, shadeLow))
        if (seen > foot) rect(x - hw, foot, x + hw, seen, zf, low)
        upright(x - hw, seen, x + hw, top, zf, low, col(Atlas.shade(body, 1.3f)))
        b.quad(x + hw, foot, zf, x + hw, foot, back, x + hw, top, back, x + hw, top, zf, c)
        b.quad(x - hw, foot, back, x - hw, foot, zf, x - hw, top, zf, x - hw, top, back, c)
        b.quad(x - hw, top, zf, x + hw, top, zf, x + hw, top, back, x - hw, top, back, col(ROOF))
        // The windows: rows of them across the front, some floors busier than others.
        val ww = 4f * size; val wh = 5f * size
        val px = 7f * size; val py = 9f * size
        val cols = ((2f * hw - px * 0.5f) / px).toInt()
        if (cols <= 0) return
        val left = x - cols * px / 2f + (px - ww) / 2f
        val z = zf + lift
        var y = top - py * 0.6f
        while (y - wh > seen) {
            val floorLit = lit * r(0.2f, 1.8f)
            for (k in 0 until cols) if (rnd.nextFloat() < floorLit) {
                val wx = left + k * px
                rect(wx, y - wh, wx + ww, y, z, col(pick(glass)), glow)
            }
            y -= py
        }
    }

    /** A glowing neon sign on a wall (at depth [z]): a dark board with a bright frame and a row of glowing letters. */
    fun neonSign(cx: Float, cy: Float, z: Float, sw: Float, sh: Float) {
        val neon = col(pick(NEON))
        val z1 = z + lift
        val z2 = z + 2f * lift
        rect(cx - sw, cy - sh, cx + sw, cy + sh, z1, col(0xFF140E28.toInt()))
        val t = sh * 0.14f
        rect(cx - sw, cy + sh - t, cx + sw, cy + sh, z2, neon, glow)
        rect(cx - sw, cy - sh, cx + sw, cy - sh + t, z2, neon, glow)
        rect(cx - sw, cy - sh + t, cx - sw + t, cy + sh - t, z2, neon, glow)
        rect(cx + sw - t, cy - sh + t, cx + sw, cy + sh - t, z2, neon, glow)
        // Letters too small to read, but shaped like letters: some like an H, some like an E.
        val n = (sw / sh * 1.4f).toInt().coerceIn(3, 6)
        val lw = (2f * sw - 5f * t) / n
        val ly = sh * 0.5f
        for (k in 0 until n) {
            val l0 = cx - sw + 2.5f * t + k * lw + lw * 0.15f
            val l1 = l0 + lw * 0.7f
            val s = lw * 0.2f
            rect(l0, cy - ly, l0 + s, cy + ly, z2, neon, glow)
            if (rnd.nextBoolean()) {
                rect(l1 - s, cy - ly, l1, cy + ly, z2, neon, glow)
                rect(l0 + s, cy - s * 0.5f, l1 - s, cy + s * 0.5f, z2, neon, glow)
            } else {
                rect(l0 + s, cy + ly - s, l1, cy + ly, z2, neon, glow)
                rect(l0 + s, cy - ly, l1, cy - ly + s, z2, neon, glow)
            }
        }
    }

    /** A little light that blinks (red, like on real antennas and cranes; now and then a white one). */
    fun light(x: Float, y: Float, z: Float) {
        val r = 3f * size
        ball(x, y, z, r, r, r, col(pick(BLINKS)), slices = 5, lats = SMALL, into = blink)
    }

    /** A thin antenna on a roof, with a little crossbar and a blinking light on its tip. */
    fun antenna(x: Float, roof: Float, z: Float, h: Float) {
        if (h < 6f * size) return
        val t = 0.8f * size
        val c = col(0xFF4A4468.toInt())
        block(x - t, roof - 1f, z - t, x + t, roof + h, z + t, c)
        block(x - t * 4f, roof + h * 0.5f, z - t * 0.6f, x + t * 4f, roof + h * 0.5f + t * 1.2f, z + t * 0.6f, c)
        light(x, roof + h + t, z)
    }

    /** A pointy four-sided spire on a roof (a face turned to the camera), at most [room] tall, with a blinking light on top. */
    fun spire(x: Float, roof: Float, z: Float, r: Float, room: Float, body: Int) {
        val h = min(r * r(1.4f, 2.4f), room)
        if (h < r * 0.8f) return
        b.place { translate(x, roof - 1f, z); rotateY(PI.toFloat() / 4) }
        cone(0f, 0f, 0f, h, r, col(body), slices = 4, flat = true, tip = col(Atlas.shade(body, 1.6f)))
        b.resetPlace()
        light(x, roof + h, z)
    }

    /** A wooden water tank on legs, on top of a low building. */
    fun waterTank(x: Float, roof: Float, z: Float, s: Float) {
        val wood = col(0xFF5A4038.toInt())
        val dark = col(0xFF2E2430.toInt())
        for (e in -1..1 step 2) {
            val lx = x + e * s * 0.55f
            block(lx - s * 0.07f, roof - 1f, z - s * 0.07f, lx + s * 0.07f, roof + s * 0.6f, z + s * 0.07f, dark)
        }
        frustum(x, roof + s * 0.55f, z, roof + s * 1.6f, s * 0.7f, s * 0.7f, wood, slices = 7)
        cone(x, roof + s * 1.6f, z, roof + s * 2.15f, s * 0.82f, dark, slices = 7, flat = true)
    }

    /** A construction crane among the skyscrapers: a steel beam hangs from its hook, and its top light blinks. */
    fun crane() {
        val s = size
        val x = r(w * 0.3f, w * 0.6f)
        val z = band.z - band.spread * 0.95f
        val top = r(-110f, -70f)
        val c = col(0xFF4A3E7A.toInt())
        val t = 3f * s
        // The mast, with crossbars so it looks like a lattice tower.
        block(x - t, band.foot, z - t, x + t, top, z + t, c)
        var y = top - 14f * s
        while (y > seenY) { rect(x - t * 1.5f, y, x + t * 1.5f, y + 1.6f * s, z + t + lift, c); y -= 18f * s }
        // The long arm, its counterweight, the driver's cab (lit) and the peak.
        block(x - 26f * s, top, z - 2f * s, x + 78f * s, top + 4f * s, z + 2f * s, c)
        block(x - 26f * s, top - 7f * s, z - 3f * s, x - 15f * s, top, z + 3f * s, col(0xFF3A3060.toInt()))
        block(x - 4f * s, top - 9f * s, z - 3f * s, x + 5f * s, top, z + 3f * s, col(0xFF5A4A88.toInt()))
        rect(x - 2.5f * s, top - 6.5f * s, x + 3.5f * s, top - 2f * s, z + 3f * s + lift, col(0xFFFFD86A.toInt()), glow)
        b.place { translate(x, top + 4f * s, z); rotateY(PI.toFloat() / 4) }
        cone(0f, 0f, 0f, 14f * s, 4.2f * s, c, slices = 4, flat = true)
        b.resetPlace()
        light(x, top + 19f * s, z)
        light(x + 77f * s, top + 6f * s, z)
        // The hook's cable and the beam it's lifting.
        val hook = x + r(40f, 66f) * s
        val beamY = top - r(36f, 64f) * s
        block(hook - 0.5f * s, beamY, z - 0.5f * s, hook + 0.5f * s, top, z + 0.5f * s, c)
        block(hook - 15f * s, beamY - 3f * s, z - 2f * s, hook + 15f * s, beamY, z + 2f * s, col(0xFF6A5A9A.toInt()))
    }

    // ---------------------------------------------------------------- the Food Kingdom (World 14)

    /** A tomato sitting at ([x], [y], [z]), [r] round: shiny, with a star of leaves on top. */
    fun tomato(x: Float, y: Float, z: Float, r: Float, slices: Int = 8) {
        val red = 0xFFE8382A.toInt()
        val cy = y + r * 0.7f
        ball(x, cy, z, r, r * 0.86f, r, col(red), col(Atlas.shade(red, 0.68f)), slices, FULL)
        lens(x - r * 0.36f, cy + r * 0.34f, z + r * 0.9f, r * 0.2f, r * 0.13f, r * 0.04f, col(0xFFFF9A8A.toInt()), 6)
        leafStar(x, cy + r * 0.84f, z, r * 0.7f)
    }

    /** A star of five green leaves and a little stem, lying on top of a tomato. */
    fun leafStar(x: Float, y: Float, z: Float, r: Float) {
        val g = col(0xFF3A9A3A.toInt())
        for (i in 0 until 5) {
            b.place { translate(x, y, z); rotateY(i * TAU / 5 - 0.3f); rotateZ(-0.18f); translate(r * 0.4f, 0f, 0f) }
            ball(0f, 0f, 0f, r * 0.46f, r * 0.08f, r * 0.17f, g, slices = 4, lats = SMALL)
            b.resetPlace()
        }
        frustum(x, y - r * 0.05f, z, y + r * 0.2f, r * 0.05f, r * 0.04f, col(0xFF2A7A2A.toInt()), slices = 4)
    }

    fun tomatoes(n: Int, lo: Float, hi: Float) = repeat(n) {
        val r = r(lo, hi) * size
        onGround(r) { x, y, z -> tomato(x, y - r * 0.2f, z, r) }
    }

    /** Tomatoes as big as hills far away (mostly sunk in the ground, like a hill). */
    fun tomatoHills(n: Int, lo: Float, hi: Float) = along(n, 0.4f) { x ->
        val r = r(260f, 320f)
        tomato(x, r(lo, hi) - r * 1.56f, z(r), r, slices = 10)
    }

    /** Giant carrots stuck in the ground, leaves up (only their tops show, thinner toward the ground). */
    fun carrots(n: Int, lo: Float, hi: Float) = repeat(n) {
        val h = r(lo, hi) * size
        onGround(h * 0.25f) { x, y, z ->
            val r = h * 0.2f
            val top = y + h * 0.6f
            val orange = 0xFFFF8A2A.toInt()
            cone(x, top, z, y - h * 0.45f, r, col(orange), slices = 7, tip = col(0xFFD8681A.toInt()))
            b.disc(x, top, z, r, r, col(Atlas.shade(orange, 1.08f)), 7)
            val g = col(0xFF4AB84A.toInt())
            for (k in -1..1) {
                b.place { translate(x, top, z); rotateY(k * 0.9f); rotateZ(-k * 0.45f); translate(0f, h * 0.2f, 0f) }
                ball(0f, 0f, 0f, h * 0.05f, h * 0.22f, h * 0.025f, g, slices = 4, lats = SMALL)
                b.resetPlace()
            }
        }
    }

    /** Broccoli trees: a thick pale stalk under a bumpy green top. */
    fun broccoli(n: Int, lo: Float, hi: Float) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.3f) { x, y, z ->
            frustum(x, y - 6f, z, y + h * 0.58f, h * 0.13f, h * 0.08f, col(0xFF9AD86A.toInt()), slices = 6)
            val r = h * 0.2f
            val cy = y + h * 0.66f
            val dark = 0xFF3A9A3A.toInt()
            val shade = col(Atlas.shade(dark, 0.78f))
            for (s in -1..1 step 2) ball(x + s * r * 0.95f, cy, z + r * 0.15f, r * 0.85f, r * 0.8f, r * 0.85f, col(dark), shade, slices = 6, lats = FLORET)
            ball(x, cy + r * 0.45f, z, r * 1.15f, r, r * 1.05f, col(0xFF4AAA4A.toInt()), col(dark), slices = 7, lats = FLORET)
        }
    }

    /** A cheese wedge: a triangle [s] half wide and [h] tall facing the camera, [d] half deep, with holes in it. */
    fun wedge(x: Float, y: Float, z: Float, s: Float, h: Float, d: Float) {
        val dir = if (rnd.nextBoolean()) 1f else -1f
        b.prism(floatArrayOf(x - s * dir, x + s * dir, x - s * dir), floatArrayOf(y, y, y + h), z - d, z + d, col(CHEESE))
        val hole = col(0xFFE0A020.toInt())
        // Spots on the triangle (u across from the tall side, v up; inside while u + v < 1) and how big.
        for (k in HOLES.indices step 3) {
            val hx = x - s * dir + dir * HOLES[k] * 2f * s
            val hy = y + HOLES[k + 1] * h
            lens(hx, hy, z + d + lift, HOLES[k + 2] * s, HOLES[k + 2] * s * 0.85f, lift, hole, 7)
        }
    }

    fun cheeseWedges(n: Int, lo: Float, hi: Float) = repeat(n) {
        val s = r(lo, hi) * size
        onGround(s * 0.8f) { x, y, z -> wedge(x, y - 4f, z, s, s * 1.1f, s * 0.7f) }
    }

    /** Cheese wedges as big as mountains, far away. */
    fun cheeseMountains(n: Int, lo: Float, hi: Float) = along(n, 0.35f) { x ->
        val h = r(lo, hi) - band.foot
        val s = h * r(0.5f, 0.62f)
        val d = s * 0.4f
        wedge(x, band.foot, z(d + 2f * lift), s, h, d)
    }

    /** Giant forks stuck in the ground, prongs up. */
    fun forks(n: Int, lo: Float, hi: Float) = repeat(n) {
        val h = r(lo, hi)
        onGround(h * 0.06f) { x, y, z ->
            val s = h / 100f
            val c = col(0xFFD8DCE8.toInt())
            val dk = col(0xFFA8B0C4.toInt())
            val t = 2f * s
            block(x - 3f * s, y - 6f, z - t, x + 3f * s, y + h * 0.58f, z + t, c)
            // The neck, widening into the head, and four prongs.
            ball(x, y + h * 0.63f, z, 10f * s, 8f * s, t * 1.1f, c, slices = 8, lats = BALL6)
            for (k in 0 until 4) {
                val px = x - 9f * s + k * 5.2f * s
                block(px, y + h * 0.63f, z - t * 0.8f, px + 2.6f * s, y + h, z + t * 0.8f, if (k % 2 == 0) c else dk)
            }
        }
    }

    /** Big bowls of hot tomato soup, steaming away. */
    fun soupBowls(n: Int) = repeat(n) {
        val s = r(16f, 22f) * size
        onGround(s) { x, y, z ->
            val y0 = y - 4f
            val y1 = y + s * 0.75f
            fun rad(at: Float) = s * (0.6f + 0.4f * (at - y0) / (y1 - y0))
            frustum(x, y0, z, y1, s * 0.6f, s, col(0xFFF4F4FA.toInt()), slices = 10)
            // A blue stripe around it, and the soup a little under the rim.
            val b0 = y + s * 0.42f
            val b1 = y + s * 0.54f
            frustum(x, b0, z, b1, rad(b0) * 1.02f, rad(b1) * 1.02f, col(0xFF4A8AE8.toInt()), slices = 10)
            b.disc(x, y1 - s * 0.06f, z, s * 0.95f, s * 0.95f, col(SOUP), 10)
            val steam = col(0xFFFFFFFF.toInt())
            for (k in 0 until 3) {
                val ps = s * (0.2f + k * 0.05f)
                ball(x + (k - 1) * s * 0.32f, y1 + s * (0.28f + k * 0.3f), z, ps, ps * 0.8f, ps * 0.8f, steam, slices = 6, lats = PUFF_SMALL)
            }
        }
    }

    companion object {
        const val TAU = (2 * PI).toFloat()
        const val HALF_PI = (PI / 2).toFloat()
        /** Where the lava lake is in the castle and volcano levels. */
        const val FLOOR_LAVA = -236f
        val U = Atlas.white.u(0.5f)
        val V = Atlas.white.v(0.5f)
        /** Latitudes for a hill (the top half of a ball, more rings near the top). */
        val DOME = floatArrayOf(0f, 0.5f, 0.9f, 1.22f, HALF_PI)
        /** A ball with a flat bottom (the bottom is never seen from the camera above). */
        val PUFF = floatArrayOf(-0.6f, 0f, 0.6f, 1.1f, HALF_PI)
        val FULL = floatArrayOf(-HALF_PI, -0.75f, 0f, 0.75f, HALF_PI)
        val SMALL = floatArrayOf(-HALF_PI, 0f, HALF_PI)
        val BALL6 = floatArrayOf(-HALF_PI, -0.5f, 0.5f, HALF_PI)
        val WHITES = intArrayOf(0xFFFFFFFF.toInt())
        val CANDY = intArrayOf(0xFFFF6A9A.toInt(), 0xFF6AC8FF.toInt(), 0xFFFFC82A.toInt(), 0xFFB86AF0.toInt())
        val CORAL = intArrayOf(0xFFE86A8A.toInt(), 0xFFFF9A4A.toInt(), 0xFFC86AE8.toInt(), 0xFFFFD04A.toInt())
        val PETALS = intArrayOf(0xFFFF7AC8.toInt(), 0xFFFFE14D.toInt(), 0xFFFFFFFF.toInt(), 0xFF8AD8FF.toInt())
        val GEMS = intArrayOf(0xFFF0C8FF.toInt(), 0xFF8AE8F0.toInt(), 0xFFFFA8E8.toInt())
        val PLANETS = intArrayOf(0xFFE8A84A.toInt(), 0xFF6AA8E8.toInt(), 0xFFE86A6A.toInt(), 0xFF8AD86A.toInt(), 0xFFC88AE8.toInt())
        val ROCKS = intArrayOf(0xFF6A6470.toInt(), 0xFF7A7068.toInt(), 0xFF5A5A66.toInt())
        val TOY = intArrayOf(0xFFE84A4A.toInt(), 0xFF3A7AE8.toInt(), 0xFFF4C83A.toInt(), 0xFF4AB85A.toInt())
        /** The Big City's roofs, lit windows (warm lamps, or the cool light of offices and TVs), neon and blinking lights. */
        const val ROOF = 0xFF4A4672.toInt()
        val WARM = intArrayOf(0xFFFFD86A.toInt(), 0xFFFFF0B0.toInt(), 0xFFFFD86A.toInt(), 0xFFFFB04A.toInt())
        val COOL = intArrayOf(0xFFBFE4FF.toInt(), 0xFFFFF0B0.toInt(), 0xFF8AD0FF.toInt())
        val NEON = intArrayOf(0xFFFF4AA8.toInt(), 0xFF4AE8FF.toInt(), 0xFFFFE14D.toInt(), 0xFF7AFF6A.toInt(), 0xFFFF8A3A.toInt())
        val BLINKS = intArrayOf(0xFFFF3A3A.toInt(), 0xFFFF3A3A.toInt(), 0xFFFFF4E0.toInt())
        /** The Food Kingdom's cheese and tomato soup (the same as the level's), and the creamier cheese melted on hills. */
        const val CHEESE = 0xFFFFC21A.toInt()
        const val SOUP = 0xFFE24E24.toInt()
        const val MELTED = 0xFFFFD866.toInt()
        /** Latitudes for the melted cheese on top of a hill. */
        val CHEESE_CAP = floatArrayOf(0.95f, 1.25f, HALF_PI)
        /** A light round puff (broccoli florets, steam): a flat bottom and fewer rings. */
        val FLORET = floatArrayOf(-0.6f, 0.3f, 1.0f, HALF_PI)
        val PUFF_SMALL = floatArrayOf(-0.6f, 0.5f, HALF_PI)
        /** The holes in a cheese wedge: across, up and size, for each hole. */
        val HOLES = floatArrayOf(0.2f, 0.2f, 0.13f, 0.44f, 0.13f, 0.08f, 0.14f, 0.52f, 0.1f, 0.3f, 0.36f, 0.06f, 0.1f, 0.76f, 0.06f)
    }
}
