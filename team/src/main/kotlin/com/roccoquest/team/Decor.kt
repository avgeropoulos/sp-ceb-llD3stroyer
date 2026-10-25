package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.argb
import com.roccoquest.game.mixColor
import com.roccoquest.game.shadowText
import kotlin.math.cos
import kotlin.math.sin

/**
 * Decorations you can buy as many of as you like and put anywhere: inside on any floor, or out in the
 * backyard ([yard] ones: pools, trees, swings...). Saved by index, so new ones always go at the END.
 * [layer]: 0 stands on the floor, 1 hangs on the wall, 2 lies flat on the ground (rugs, pools).
 */
enum class DecorItem(val label: String, val coins: Int, val w: Float, val yard: Boolean = false, val layer: Int = 0) {
    // ---- Inside
    RED_SOFA("RED SOFA", 80, 60f), BLUE_SOFA("BLUE SOFA", 80, 60f), GREEN_SOFA("GREEN SOFA", 80, 60f),
    ARMCHAIR("COMFY ARMCHAIR", 50, 30f), PINK_ARMCHAIR("PINK ARMCHAIR", 50, 30f),
    BLUE_RUG("ROUND BLUE RUG", 25, 80f, layer = 2), PINK_RUG("ROUND PINK RUG", 25, 80f, layer = 2), RAINBOW_RUG("RAINBOW RUG", 40, 90f, layer = 2),
    FLOOR_LAMP("FLOOR LAMP", 20, 14f), LAVA_LAMP("LAVA LAMP", 30, 12f), FERN("FERN", 15, 18f), CACTUS("CACTUS", 15, 14f),
    FLOWER_POT("FLOWER POT", 20, 16f), VASE("BIG VASE", 25, 16f), DESK("DESK", 45, 46f), DRESSER("DRESSER", 50, 40f),
    WARDROBE("WARDROBE", 70, 40f), GRANDFATHER_CLOCK("GRANDFATHER CLOCK", 90, 20f), MIRROR("MIRROR", 35, 26f, layer = 1),
    ROCCO_POSTER("ROCCO POSTER", 20, 26f, layer = 1), KRAG_POSTER("KRAG POSTER", 20, 26f, layer = 1),
    POKEMON_POSTER("POKEMON POSTER", 20, 26f, layer = 1), BLOCKY_POSTER("BLOCKY POSTER", 20, 26f, layer = 1),
    CURTAINS("CURTAINS", 30, 40f, layer = 1), AQUARIUM("BIG AQUARIUM", 160, 70f), KITCHEN("KITCHEN COUNTER", 70, 60f),
    BATHTUB("BUBBLE BATHTUB", 90, 60f), GIANT_TEDDY("GIANT TEDDY", 60, 34f), ROCCO_STATUE("GOLD ROCCO STATUE", 250, 30f),
    KRAG_STATUE("KRAG STATUE", 250, 34f), JUKEBOX("JUKEBOX", 140, 30f), SNACK_MACHINE("SNACK MACHINE", 120, 30f),
    PUNCHING_BAG("PUNCHING BAG", 45, 20f), PING_PONG("PING PONG TABLE", 110, 70f), HOOP("BASKETBALL HOOP", 40, 26f, layer = 1),
    DARTBOARD("DARTBOARD", 25, 22f, layer = 1), GLOBE("GLOBE", 30, 18f), ROBOT_TOY("ROBOT TOY", 50, 18f),
    DOLLHOUSE("DOLLHOUSE", 60, 40f), RACECAR_BED("RACE CAR BED", 150, 64f), PLAY_CASTLE("PLAY CASTLE", 70, 50f),
    SNOW_GLOBE("SNOW GLOBE", 35, 16f), SPACE_HELMET("SPACE HELMET", 40, 18f), GOLD_TROPHY("GOLD TROPHY", 100, 18f),
    // ---- The backyard: pools
    KIDDIE_POOL("KIDDIE POOL", 60, 60f, true, 2), ROUND_POOL("ROUND POOL", 200, 90f, true, 2),
    BIG_POOL("BIG POOL", 350, 140f, true, 2), SLIDE_POOL("POOL WITH SLIDE", 500, 160f, true, 2),
    HOT_SPRING("HOT SPRING", 300, 100f, true, 2), LAZY_RIVER("LAZY RIVER", 600, 200f, true, 2),
    // ---- Trees
    OAK_TREE("OAK TREE", 30, 50f, true), PINE_TREE("PINE TREE", 30, 40f, true), PALM_TREE("PALM TREE", 45, 46f, true),
    CHERRY_TREE("CHERRY BLOSSOM", 60, 54f, true), BIRCH_TREE("BIRCH TREE", 35, 40f, true),
    MUSHROOM_TREE("GIANT MUSHROOM", 80, 50f, true), CANDY_TREE("LOLLIPOP TREE", 90, 40f, true),
    // ---- Yard fun
    SWING_SET("SWING SET", 90, 70f, true), SANDBOX("SANDBOX", 40, 50f, true), SLIDE_TOWER("SLIDE TOWER", 120, 70f, true),
    BBQ("BBQ GRILL", 60, 26f, true), PICNIC_TABLE("PICNIC TABLE", 50, 56f, true), BIG_TRAMPOLINE("BIG TRAMPOLINE", 110, 70f, true),
    DOG_HOUSE("DOG HOUSE", 60, 40f, true), BIRD_BATH("BIRD BATH", 30, 20f, true), GNOME("GARDEN GNOME", 20, 12f, true),
    FLOWER_BED("FLOWER BED", 25, 50f, true, 2), FIRE_PIT("FIRE PIT", 45, 30f, true), UMBRELLA("BEACH UMBRELLA", 30, 30f, true),
    LAWN_CHAIRS("LAWN CHAIRS", 35, 50f, true), SOCCER_GOAL("SOCCER GOAL", 40, 50f, true), LAMP_POST("LAMP POST", 30, 14f, true),
    POND("FISH POND", 150, 90f, true, 2), BOUNCY_HOUSE("BOUNCY HOUSE", 200, 90f, true), SNOWMAN("SNOWMAN", 15, 24f, true),
    MAILBOX("MAILBOX", 15, 14f, true),
}

/** One decoration that's been put down: [floor] is a floor of the house, the [Decor.YARD] or the [Decor.ROOF]. */
class Placed(val item: DecorItem, var x: Float, var floor: Int)

object Decor {
    /** Not house floors (those are 0-9). Old saves (before 10 floors) used 9 for the yard: see [unpack]. */
    const val YARD = 20
    const val ROOF = 21
    /** True for the places outside (backyard things go there). */
    fun outside(floor: Int) = floor == YARD || floor == ROOF
    /** Most decorations you can have out at once (saves stay small). */
    const val MAX = 120
    private const val INK = 0xFF1A1420.toInt()

    /** Saved as one number: item + x * 128 + floor * 128 * 8192 (plus 1, so 0 means "nothing"). */
    private const val FLOOR_STEP = 128 * 8192
    /** Since save version 3 there's room for 256 kinds: item + x * 256 + floor * 256 * 8192. */
    private const val STEP3 = 256 * 8192
    fun pack(p: Placed) = 1 + p.item.ordinal + p.x.toInt().coerceIn(0, 8191) * 256 + p.floor * STEP3

    /** [version] is the save's `decorV`: below 3 it's the old 128-kinds packing, below 2 the yard was floor 9. */
    fun unpack(v: Int, version: Int = 3): Placed? {
        if (v <= 0) return null
        val n = v - 1
        val kinds = if (version >= 3) 256 else 128
        val step = if (version >= 3) STEP3 else FLOOR_STEP
        val item = DecorItem.entries.getOrNull(n % kinds) ?: return null
        val floor = n / step
        return Placed(item, ((n % step) / kinds).toFloat(), if (version < 2 && floor == 9) YARD else floor)
    }

    /** Draws [d] standing on the ground at [fy], its left edge at [x]. Wall things hang at the usual height. */
    fun draw(gfx: Gfx, d: DecorItem, x: Float, fy: Float, t: Float) {
        val w = d.w
        when (d) {
            DecorItem.RED_SOFA, DecorItem.BLUE_SOFA, DecorItem.GREEN_SOFA -> {
                val c = when (d) { DecorItem.RED_SOFA -> 0xFFD83A3A; DecorItem.BLUE_SOFA -> 0xFF3A6AD8; else -> 0xFF3AA84A }.toInt()
                val dk = mixColor(c, INK, 0.3f)
                gfx.rect(x, fy - 26, w, 14f, dk)
                gfx.rect(x - 2, fy - 18, 8f, 16f, dk); gfx.rect(x + w - 6, fy - 18, 8f, 16f, dk)
                gfx.rect(x + 4, fy - 14, w - 8, 9f, c)
                gfx.rect(x + 6, fy - 24, w / 2 - 8, 10f, mixColor(c, 0xFFFFFFFF.toInt(), 0.2f)); gfx.rect(x + w / 2 + 2, fy - 24, w / 2 - 8, 10f, mixColor(c, 0xFFFFFFFF.toInt(), 0.2f))
                gfx.rect(x + 2, fy - 4, 3f, 4f, INK); gfx.rect(x + w - 5, fy - 4, 3f, 4f, INK)
            }
            DecorItem.ARMCHAIR, DecorItem.PINK_ARMCHAIR -> {
                val c = if (d == DecorItem.ARMCHAIR) 0xFF8A5A2A.toInt() else 0xFFFF8AC0.toInt()
                gfx.rect(x + 2, fy - 30, w - 4, 18f, mixColor(c, INK, 0.25f))
                gfx.rect(x, fy - 18, 6f, 16f, mixColor(c, INK, 0.25f)); gfx.rect(x + w - 6, fy - 18, 6f, 16f, mixColor(c, INK, 0.25f))
                gfx.rect(x + 5, fy - 14, w - 10, 10f, c)
                gfx.rect(x + 3, fy - 4, 3f, 4f, INK); gfx.rect(x + w - 6, fy - 4, 3f, 4f, INK)
            }
            DecorItem.BLUE_RUG, DecorItem.PINK_RUG -> {
                val c = if (d == DecorItem.BLUE_RUG) 0xFF3A7AD8.toInt() else 0xFFFF8AC0.toInt()
                gfx.oval(x, fy + 4, w, 16f, c); gfx.oval(x + 10, fy + 7, w - 20, 10f, mixColor(c, 0xFFFFFFFF.toInt(), 0.35f))
            }
            DecorItem.RAINBOW_RUG -> for (k in 0 until 6) gfx.oval(x + k * 6f, fy + 4 + k * 1.5f, w - k * 12f, 16f - k * 3f, solidRainbow(k * 0.16f, 0f))
            DecorItem.FLOOR_LAMP -> {
                gfx.rect(x + 6, fy - 40, 2f, 40f, 0xFF3A3A3A.toInt()); gfx.rect(x + 1, fy - 3, 12f, 3f, 0xFF3A3A3A.toInt())
                gfx.poly(floatArrayOf(x, x + 4, x + 10, x + 14), floatArrayOf(fy - 36, fy - 48, fy - 48, fy - 36), 0xFFF4E0A0.toInt())
                gfx.oval(x - 8, fy - 46, 30f, 24f, 0x22FFF2A0)
            }
            DecorItem.LAVA_LAMP -> {
                gfx.poly(floatArrayOf(x + 2, x + 4, x + 8, x + 10), floatArrayOf(fy - 4, fy - 26, fy - 26, fy - 4), 0xAA8A3AD8.toInt())
                for (k in 0 until 3) gfx.oval(x + 4, fy - 8 - ((t * 6 + k * 7) % 18), 4f, 5f, 0xFFFF6A3A.toInt())
                gfx.rect(x + 1, fy - 4, 10f, 4f, 0xFFB8C0CC.toInt()); gfx.rect(x + 3, fy - 28, 6f, 3f, 0xFFB8C0CC.toInt())
            }
            DecorItem.FERN, DecorItem.CACTUS, DecorItem.FLOWER_POT -> {
                gfx.poly(floatArrayOf(x + 2, x + w - 2, x + w - 4, x + 4), floatArrayOf(fy - 10, fy - 10, fy, fy), 0xFFC86A3A.toInt())
                when (d) {
                    DecorItem.FERN -> for (k in 0 until 5) { val a = -2.6f + k * 0.55f; gfx.poly(floatArrayOf(x + w / 2, x + w / 2 + cos(a) * 14 - 2, x + w / 2 + cos(a) * 14 + 2), floatArrayOf(fy - 10, fy - 10 + sin(a) * 16, fy - 10 + sin(a) * 16), 0xFF3AA84A.toInt()) }
                    DecorItem.CACTUS -> { gfx.oval(x + 4, fy - 30, 6f, 22f, 0xFF4AA85A.toInt()); gfx.oval(x, fy - 24, 5f, 9f, 0xFF4AA85A.toInt()); gfx.oval(x + 9, fy - 27, 5f, 9f, 0xFF4AA85A.toInt()); gfx.oval(x + 5, fy - 32, 4f, 4f, 0xFFFF6A9A.toInt()) }
                    else -> for (k in 0 until 3) { gfx.rect(x + 4 + k * 4f, fy - 22 + k % 2 * 3, 1f, 12f, 0xFF3AA84A.toInt()); gfx.oval(x + 2 + k * 4f, fy - 26 + k % 2 * 3, 5f, 5f, listOf(0xFFFF6A9A, 0xFFFFD21F, 0xFF8A6AE8)[k].toInt()) }
                }
            }
            DecorItem.VASE -> {
                gfx.oval(x, fy - 22, w, 22f, 0xFF3A8AC8.toInt())
                gfx.rect(x + 4, fy - 28, w - 8, 8f, 0xFF3A8AC8.toInt())
                gfx.rect(x + 1, fy - 13, w - 2, 2f, 0xFFF4D84A.toInt())
            }
            DecorItem.DESK -> {
                gfx.rect(x, fy - 24, w, 4f, 0xFF8A5A2A.toInt()); gfx.rect(x + 2, fy - 20, 3f, 20f, 0xFF6A3A1A.toInt())
                gfx.rect(x + w - 18, fy - 20, 16f, 20f, 0xFF7A4A20.toInt()); gfx.rect(x + w - 12, fy - 14, 4f, 1.5f, 0xFFF4D84A.toInt())
                gfx.rect(x + 8, fy - 36, 16f, 12f, 0xFF2A2A34.toInt()); gfx.rect(x + 9, fy - 35, 14f, 9f, 0xFF4AB8F0.toInt())
            }
            DecorItem.DRESSER, DecorItem.WARDROBE -> {
                val h = if (d == DecorItem.DRESSER) 30f else 62f
                gfx.rect(x, fy - h, w, h, 0xFF8A5A2A.toInt()); gfx.rect(x + 2, fy - h + 2, w - 4, h - 4, 0xFFA87A4A.toInt())
                if (d == DecorItem.DRESSER) for (k in 0 until 3) { gfx.rect(x + 3, fy - 27 + k * 9f, w - 6, 1f, 0xFF6A3A1A.toInt()); gfx.oval(x + w / 2 - 2, fy - 24 + k * 9f, 4f, 3f, 0xFFF4D84A.toInt()) }
                else { gfx.rect(x + w / 2 - 0.5f, fy - h + 2, 1f, h - 4, 0xFF6A3A1A.toInt()); gfx.oval(x + w / 2 - 4, fy - 34, 3f, 3f, 0xFFF4D84A.toInt()); gfx.oval(x + w / 2 + 1, fy - 34, 3f, 3f, 0xFFF4D84A.toInt()) }
            }
            DecorItem.GRANDFATHER_CLOCK -> {
                gfx.rect(x, fy - 64, w, 64f, 0xFF6A3A1A.toInt())
                gfx.oval(x + 2, fy - 62, 16f, 16f, 0xFFF4ECD8.toInt())
                val a = t * 0.8f
                gfx.rect(x + 9.5f, fy - 54 - 5, 1f, 5f, INK); gfx.rect(x + 10, fy - 54, cos(a) * 5f, 1f, INK)
                gfx.rect(x + 9, fy - 42, 2f, 14f + sin(t * 3) * 0f, 0xFFC8A030.toInt())
                gfx.oval(x + 6 + sin(t * 3) * 3, fy - 30, 8f, 8f, 0xFFE8C83A.toInt())
            }
            DecorItem.MIRROR -> { gfx.oval(x, 50f, w, 38f, 0xFFC8A030.toInt()); gfx.oval(x + 3, 53f, w - 6, 32f, 0xFFD8F0FF.toInt()); gfx.rect(x + 8, 58f, 3f, 14f, 0x99FFFFFF.toInt()) }
            DecorItem.ROCCO_POSTER, DecorItem.KRAG_POSTER, DecorItem.POKEMON_POSTER, DecorItem.BLOCKY_POSTER -> {
                gfx.rect(x, 46f, w, 34f, 0xFFF4F4F4.toInt()); gfx.rect(x + 2, 48f, w - 4, 30f, 0xFF8AD0FF.toInt())
                when (d) {
                    DecorItem.ROCCO_POSTER -> gfx.sprite(TeamArt.rocco(Power.NONE, 2), x + 7, 52f, w = 12f, h = 18f)
                    DecorItem.BLOCKY_POSTER -> gfx.sprite(TeamArt.blocky(Power.NONE, true), x + 7, 52f, w = 12f, h = 18f)
                    DecorItem.POKEMON_POSTER -> TeamArt.mon(gfx, Mon.SPARKY, x + w / 2, 74f, t, 1, false)
                    else -> { gfx.oval(x + 6, 54f, 14f, 16f, 0xFF3AA84A.toInt()); gfx.oval(x + 8, 52f, 10f, 9f, 0xFFF4C88A.toInt()); gfx.rect(x + 9, 50f, 8f, 3f, 0xFFE84A3A.toInt()) }
                }
                gfx.text(when (d) { DecorItem.ROCCO_POSTER -> "WAHOO"; DecorItem.KRAG_POSTER -> "ROAR"; DecorItem.BLOCKY_POSTER -> "MINE"; else -> "ZAP" }, x + w / 2, 77f, 4.5f, INK, 1)
            }
            DecorItem.CURTAINS -> {
                gfx.rect(x - 2, 40f, w + 4, 3f, 0xFF8A5A2A.toInt())
                gfx.rect(x + 6, 44f, w - 12, 46f, 0xFF8AD0FF.toInt())
                gfx.poly(floatArrayOf(x, x + 12, x + 8, x), floatArrayOf(43f, 43f, 92f, 92f), 0xFFD83A6A.toInt())
                gfx.poly(floatArrayOf(x + w, x + w - 12, x + w - 8, x + w), floatArrayOf(43f, 43f, 92f, 92f), 0xFFD83A6A.toInt())
            }
            DecorItem.AQUARIUM -> {
                gfx.rect(x, fy - 12, w, 12f, 0xFF3A3A44.toInt())
                gfx.rect(x, fy - 44, w, 32f, 0xFF6AC8F0.toInt()); gfx.rect(x, fy - 44, w, 3f, 0xFF3A3A44.toInt())
                for (k in 0 until 4) { val fx = x + 6 + ((t * (15 + k * 6) + k * 20) % (w - 16)); gfx.oval(fx, fy - 38 + k * 6f, 9f, 5f, listOf(0xFFFF8A3A, 0xFFF4D84A, 0xFFE84A6A, 0xFF8A6AE8)[k].toInt()) }
                for (k in 0 until 3) gfx.oval(x + 10 + k * 22f, fy - 16, 6f, 8f, 0xFF3AA84A.toInt())
            }
            DecorItem.KITCHEN -> {
                gfx.rect(x, fy - 28, w, 28f, 0xFFF4F4F4.toInt()); gfx.rect(x - 1, fy - 30, w + 2, 3f, 0xFF8A94A4.toInt())
                for (k in 0 until 3) gfx.rect(x + 3 + k * 19f, fy - 24, 16f, 20f, 0xFFE8E8F0.toInt())
                gfx.rect(x + 22, fy - 36, 2f, 6f, 0xFFB8C0CC.toInt()); gfx.rect(x + 22, fy - 36, 6f, 2f, 0xFFB8C0CC.toInt())
            }
            DecorItem.BATHTUB -> {
                gfx.rect(x, fy - 20, w, 18f, 0xFFF4F4F4.toInt()); gfx.oval(x - 2, fy - 24, w + 4, 8f, 0xFFF4F4F4.toInt())
                for (k in 0 until 6) gfx.oval(x + 4 + k * 9f, fy - 30 - (k % 2) * 3f + sin(t * 2 + k), 10f, 9f, 0xEEFFFFFF.toInt())
                gfx.rect(x + 4, fy - 2, 3f, 2f, 0xFFC8A030.toInt()); gfx.rect(x + w - 7, fy - 2, 3f, 2f, 0xFFC8A030.toInt())
            }
            DecorItem.GIANT_TEDDY -> {
                gfx.oval(x + 4, fy - 26, 26f, 26f, 0xFFB8783A.toInt()); gfx.oval(x + 7, fy - 44, 20f, 20f, 0xFFB8783A.toInt())
                gfx.oval(x + 6, fy - 46, 7f, 7f, 0xFFB8783A.toInt()); gfx.oval(x + 21, fy - 46, 7f, 7f, 0xFFB8783A.toInt())
                gfx.oval(x + 12, fy - 36, 10f, 7f, 0xFFF0D0A0.toInt()); gfx.oval(x + 12, fy - 40, 2.5f, 2.5f, INK); gfx.oval(x + 20, fy - 40, 2.5f, 2.5f, INK)
                gfx.oval(x + 15.5f, fy - 35, 3f, 2f, INK); gfx.oval(x + 12, fy - 20, 10f, 10f, 0xFFF0D0A0.toInt())
                gfx.poly(floatArrayOf(x + 12, x + 17, x + 22), floatArrayOf(fy - 26, fy - 23, fy - 26), 0xFFE84A6A.toInt())
            }
            DecorItem.ROCCO_STATUE -> {
                gfx.rect(x, fy - 8, w, 8f, 0xFFB8C0CC.toInt())
                gfx.sprite(Metal.of(TeamArt.rocco(Power.NONE, 0)), x + 3, fy - 44, w = 24f, h = 36f)
                gfx.rect(x + 3, fy - 44, 24f, 36f, 0x55FFD21F)
            }
            DecorItem.KRAG_STATUE -> {
                gfx.rect(x, fy - 8, w, 8f, 0xFFB8C0CC.toInt())
                gfx.oval(x + 4, fy - 34, 26f, 26f, 0xFF8A94A4.toInt()); gfx.oval(x + 8, fy - 46, 18f, 16f, 0xFF9AA4B8.toInt())
                for (k in 0 until 3) gfx.poly(floatArrayOf(x + 6 + k * 8f, x + 9 + k * 8f, x + 12 + k * 8f), floatArrayOf(fy - 30, fy - 38, fy - 30), 0xFFE8E8F0.toInt())
                gfx.poly(floatArrayOf(x + 10, x + 8, x + 14), floatArrayOf(fy - 44, fy - 52, fy - 46), 0xFFE8E8F0.toInt())
            }
            DecorItem.JUKEBOX -> {
                gfx.rect(x, fy - 36, w, 36f, 0xFF8A3A2A.toInt()); gfx.oval(x, fy - 48, w, 26f, 0xFF8A3A2A.toInt())
                gfx.oval(x + 4, fy - 44, w - 8, 18f, solidRainbow(t * 0.4f, 0f))
                gfx.rect(x + 5, fy - 24, w - 10, 12f, 0xFF2A2A34.toInt())
                if ((t * 2).toInt() % 2 == 0) gfx.shadowText("♪", x + w + 4, fy - 44 - (t % 1f) * 6, 7f, 0xFFFFD21F.toInt(), 1)
            }
            DecorItem.SNACK_MACHINE -> {
                gfx.rect(x, fy - 54, w, 54f, 0xFF3A6AD8.toInt()); gfx.rect(x + 3, fy - 50, w - 12, 36f, 0xFFDDEEFF.toInt())
                for (k in 0 until 9) gfx.rect(x + 5 + (k % 3) * 6f, fy - 47 + (k / 3) * 11f, 4f, 7f, listOf(0xFFE84A4A, 0xFFF4D84A, 0xFF4AB85A)[k % 3].toInt())
                gfx.rect(x + w - 7, fy - 44, 4f, 8f, 0xFF2A2A34.toInt()); gfx.rect(x + 3, fy - 10, w - 12, 6f, 0xFF2A2A34.toInt())
            }
            DecorItem.PUNCHING_BAG -> {
                gfx.rect(x + 9, 24f, 2f, fy - 72, 0xFF5A5A64.toInt())
                gfx.oval(x + 2, fy - 50 + sin(t * 2), 16f, 34f, 0xFFD83A3A.toInt()); gfx.rect(x + 3, fy - 40, 14f, 2f, 0xFF2A2A34.toInt())
            }
            DecorItem.PING_PONG -> {
                gfx.rect(x, fy - 22, w, 5f, 0xFF2E8A4A.toInt()); gfx.rect(x + w / 2 - 0.5f, fy - 30, 1f, 8f, 0xFFF4F4F4.toInt())
                gfx.rect(x + 4, fy - 17, 3f, 17f, 0xFF3A3A44.toInt()); gfx.rect(x + w - 7, fy - 17, 3f, 17f, 0xFF3A3A44.toInt())
                val bx = x + w / 2 + sin(t * 4) * (w / 2 - 6)
                gfx.oval(bx - 2, fy - 34 - kotlin.math.abs(cos(t * 4)) * 8, 4f, 4f, 0xFFF4F4F4.toInt())
            }
            DecorItem.HOOP -> { gfx.rect(x, 40f, w, 22f, 0xFFF4F4F4.toInt()); gfx.rect(x + 8, 50f, 10f, 8f, 0xFFE84A3A.toInt()); gfx.rect(x + 5, 60f, 16f, 2f, 0xFFF08A3A.toInt()); for (k in 0 until 4) gfx.rect(x + 6 + k * 4f, 62f, 1f, 8f, 0xFFF4F4F4.toInt()) }
            DecorItem.DARTBOARD -> for (k in 0 until 4) gfx.oval(x + k * 3f, 50f + k * 3f, w - k * 6f, w - k * 6f, if (k % 2 == 0) 0xFFE84A4A.toInt() else 0xFFF4ECD8.toInt())
            DecorItem.GLOBE -> { gfx.rect(x + 8, fy - 6, 2f, 6f, 0xFF8A5A2A.toInt()); gfx.rect(x + 3, fy - 2, 12f, 2f, 0xFF8A5A2A.toInt()); gfx.oval(x + 1, fy - 22, 16f, 16f, 0xFF3A7AD8.toInt()); gfx.oval(x + 4 + sin(t) * 3, fy - 19, 6f, 6f, 0xFF4AB85A.toInt()) }
            DecorItem.ROBOT_TOY -> {
                gfx.rect(x + 3, fy - 16, 12f, 12f, 0xFFB8C0CC.toInt()); gfx.rect(x + 5, fy - 26, 8f, 9f, 0xFF9AA4B8.toInt())
                gfx.oval(x + 6, fy - 24, 2.5f, 2.5f, 0xFFE84A4A.toInt()); gfx.oval(x + 10, fy - 24, 2.5f, 2.5f, 0xFFE84A4A.toInt())
                gfx.rect(x + 8.5f, fy - 31, 1f, 5f, INK); gfx.rect(x + 4, fy - 4, 4f, 4f, INK); gfx.rect(x + 10, fy - 4, 4f, 4f, INK)
            }
            DecorItem.DOLLHOUSE -> {
                gfx.rect(x, fy - 30, w, 30f, 0xFFFFB8D8.toInt())
                gfx.poly(floatArrayOf(x - 3, x + w / 2, x + w + 3), floatArrayOf(fy - 30, fy - 46, fy - 30), 0xFFD83A6A.toInt())
                for (k in 0 until 2) gfx.rect(x + 6 + k * 18f, fy - 24, 10f, 9f, 0xFFFFF2A0.toInt())
                gfx.rect(x + 16, fy - 12, 8f, 12f, 0xFF8A5A2A.toInt())
            }
            DecorItem.RACECAR_BED -> {
                gfx.rect(x, fy - 20, w, 14f, 0xFFE84A3A.toInt()); gfx.poly(floatArrayOf(x + w - 14, x + w + 4, x + w), floatArrayOf(fy - 20, fy - 12, fy - 6), 0xFFE84A3A.toInt())
                gfx.rect(x + 6, fy - 24, 40f, 6f, 0xFFF4F4F4.toInt()); gfx.oval(x + 4, fy - 28, 12f, 8f, 0xFFF4F4F4.toInt())
                for (wx in floatArrayOf(x + 6, x + w - 18)) { gfx.oval(wx, fy - 10, 12f, 12f, INK); gfx.oval(wx + 3, fy - 7, 6f, 6f, 0xFFB8C0CC.toInt()) }
                gfx.text("1", x + w / 2 + 10, fy - 9, 7f, 0xFFF4F4F4.toInt(), 1)
            }
            DecorItem.PLAY_CASTLE -> {
                gfx.rect(x, fy - 30, w, 30f, 0xFFF4C8E8.toInt())
                for (tx in floatArrayOf(x - 2, x + w - 12)) { gfx.rect(tx, fy - 42, 14f, 42f, 0xFFE8A0D0.toInt()); gfx.poly(floatArrayOf(tx - 2, tx + 7, tx + 16), floatArrayOf(fy - 42, fy - 54, fy - 42), 0xFF8A3AD8.toInt()) }
                gfx.oval(x + w / 2 - 7, fy - 18, 14f, 22f, 0xFF8A3AD8.toInt())
            }
            DecorItem.SNOW_GLOBE -> { gfx.rect(x + 1, fy - 5, 14f, 5f, 0xFF8A5A2A.toInt()); gfx.oval(x, fy - 21, 16f, 17f, 0xAADDEEFF.toInt()); gfx.oval(x + 5, fy - 12, 6f, 6f, 0xFFF4F4F4.toInt()); for (k in 0 until 4) gfx.oval(x + 3 + (k * 5 + t * 3) % 10, fy - 19 + (t * 6 + k * 4) % 13, 1.5f, 1.5f, 0xFFFFFFFF.toInt()) }
            DecorItem.SPACE_HELMET -> { gfx.oval(x, fy - 20, 18f, 18f, 0xFFF4F4F4.toInt()); gfx.oval(x + 4, fy - 15, 11f, 9f, 0xFF2A3A6A.toInt()); gfx.oval(x + 6, fy - 14, 3f, 2f, 0x99FFFFFF.toInt()); gfx.rect(x + 1, fy - 4, 16f, 4f, 0xFFB8C0CC.toInt()) }
            DecorItem.GOLD_TROPHY -> { gfx.rect(x + 3, fy - 5, 12f, 5f, 0xFF6A3A1A.toInt()); gfx.rect(x + 8, fy - 12, 2f, 7f, 0xFFE8C83A.toInt()); gfx.poly(floatArrayOf(x + 2, x + 16, x + 13, x + 5), floatArrayOf(fy - 28, fy - 28, fy - 13, fy - 13), 0xFFE8C83A.toInt()); gfx.oval(x - 2, fy - 26, 5f, 7f, 0xFFE8C83A.toInt()); gfx.oval(x + 15, fy - 26, 5f, 7f, 0xFFE8C83A.toInt()) }
            // ---- Pools: blue water set into the grass
            DecorItem.KIDDIE_POOL -> { gfx.oval(x, fy - 4, w, 18f, 0xFFFF8AC0.toInt()); gfx.oval(x + 4, fy - 2, w - 8, 12f, 0xFF6AC8F0.toInt()); duck(gfx, x + w / 2 + sin(t) * 8, fy - 2) }
            DecorItem.ROUND_POOL, DecorItem.BIG_POOL, DecorItem.SLIDE_POOL -> {
                val round = d == DecorItem.ROUND_POOL
                if (round) gfx.oval(x, fy - 4, w, 22f, 0xFFE8E8F0.toInt()) else gfx.rect(x, fy - 4, w, 22f, 0xFFE8E8F0.toInt())
                if (round) gfx.oval(x + 4, fy - 1, w - 8, 16f, 0xFF3AA8E8.toInt()) else gfx.rect(x + 4, fy - 1, w - 8, 16f, 0xFF3AA8E8.toInt())
                for (k in 0 until (w / 20).toInt()) gfx.rect(x + 8 + k * 20f + sin(t * 2 + k) * 3, fy + 4 + (k % 2) * 4f, 8f, 1.5f, 0x88FFFFFF.toInt())
                if (!round) { gfx.rect(x + 8, fy - 22, 1.5f, 20f, 0xFFB8C0CC.toInt()); gfx.rect(x + 14, fy - 22, 1.5f, 20f, 0xFFB8C0CC.toInt()); for (k in 0 until 3) gfx.rect(x + 8, fy - 18 + k * 6f, 7.5f, 1f, 0xFFB8C0CC.toInt()) }
                if (d == DecorItem.SLIDE_POOL) {
                    gfx.rect(x + w - 30, fy - 50, 4f, 48f, 0xFFF4C83A.toInt()); gfx.rect(x + w - 14, fy - 50, 4f, 48f, 0xFFF4C83A.toInt())
                    gfx.poly(floatArrayOf(x + w - 30, x + w - 24, x + w - 74, x + w - 80), floatArrayOf(fy - 52, fy - 48, fy + 2, fy - 2), 0xFF3AB8E8.toInt())
                    gfx.rect(x + w - 32, fy - 54, 24f, 4f, 0xFFE84A4A.toInt())
                }
                if (d == DecorItem.BIG_POOL) { gfx.oval(x + w - 40 + sin(t) * 10, fy + 2, 22f, 9f, 0xFFFF6A9A.toInt()); gfx.oval(x + w - 34 + sin(t) * 10, fy + 4, 10f, 5f, 0xFF3AA8E8.toInt()) }
            }
            DecorItem.HOT_SPRING -> {
                for (k in 0 until 7) gfx.oval(x + k * 14f, fy - 8 + (k % 2) * 3f, 18f, 14f, 0xFF8A8A9A.toInt())
                gfx.oval(x + 6, fy - 2, w - 12, 16f, 0xFF6AD8D0.toInt())
                for (k in 0 until 3) { val p = (t * 0.5f + k / 3f) % 1f; gfx.oval(x + 20 + k * 25f, fy - 6 - p * 26, 10f + p * 8, 8f + p * 6, argb(((1 - p) * 120).toInt(), 0xFFFFFF)) }
            }
            DecorItem.LAZY_RIVER -> {
                gfx.oval(x, fy - 6, w, 26f, 0xFFE8E8F0.toInt()); gfx.oval(x + 6, fy - 3, w - 12, 20f, 0xFF3AA8E8.toInt())
                gfx.oval(x + 40, fy + 2, w - 80, 10f, 0xFF7AC86A.toInt())
                for (k in 0 until 3) { val a = t * 0.5f + k * 2.09f; val rx = x + w / 2 + cos(a) * (w / 2 - 22); val ry = fy + 7 + sin(a) * 6; gfx.oval(rx - 8, ry - 3, 16f, 7f, listOf(0xFFFF6A9A, 0xFFF4D84A, 0xFF4AB85A)[k].toInt()); gfx.oval(rx - 4, ry - 1.5f, 8f, 3.5f, 0xFF3AA8E8.toInt()) }
            }
            // ---- Trees
            DecorItem.OAK_TREE, DecorItem.CHERRY_TREE, DecorItem.BIRCH_TREE -> {
                val trunk = if (d == DecorItem.BIRCH_TREE) 0xFFF4F4F4.toInt() else 0xFF7A4A20.toInt()
                gfx.rect(x + w / 2 - 4, fy - 50, 8f, 50f, trunk)
                if (d == DecorItem.BIRCH_TREE) for (k in 0 until 4) gfx.rect(x + w / 2 - 4, fy - 44 + k * 11f, 4f, 2f, INK)
                val leaf = when (d) { DecorItem.CHERRY_TREE -> 0xFFFFB8D8.toInt(); DecorItem.BIRCH_TREE -> 0xFF8AD86A.toInt(); else -> 0xFF3A9A3A.toInt() }
                val sway = sin(t * 1.2f) * 1.5f
                gfx.oval(x + sway, fy - 84, w, 44f, leaf); gfx.oval(x + 8 + sway, fy - 96, w - 16, 30f, mixColor(leaf, 0xFFFFFFFF.toInt(), 0.15f))
                if (d == DecorItem.CHERRY_TREE) for (k in 0 until 4) { val p = (t * 0.3f + k / 4f) % 1f; gfx.oval(x + 10 + k * 10f + sin(p * 6) * 6, fy - 60 + p * 60, 3f, 2.5f, 0xFFFF8AC0.toInt()) }
            }
            DecorItem.PINE_TREE -> {
                gfx.rect(x + w / 2 - 3, fy - 14, 6f, 14f, 0xFF7A4A20.toInt())
                for (k in 0 until 3) gfx.poly(floatArrayOf(x + k * 4f, x + w / 2, x + w - k * 4f), floatArrayOf(fy - 12 - k * 22f, fy - 44 - k * 22f, fy - 12 - k * 22f), 0xFF2E7A3A.toInt())
            }
            DecorItem.PALM_TREE -> {
                for (k in 0 until 6) gfx.oval(x + w / 2 - 4 + k * 1.5f, fy - 10 - k * 11f, 9f, 12f, 0xFFB8905A.toInt())
                val top = fy - 74
                for (k in 0 until 5) { val a = -2.8f + k * 0.65f + sin(t) * 0.05f; gfx.poly(floatArrayOf(x + w / 2 + 5, x + w / 2 + 5 + cos(a) * 26, x + w / 2 + 5 + cos(a) * 22), floatArrayOf(top, top + sin(a) * 14 + 8, top + sin(a) * 14 + 14), 0xFF3AA84A.toInt()) }
                gfx.oval(x + w / 2, top, 6f, 6f, 0xFF7A4A20.toInt()); gfx.oval(x + w / 2 + 5, top + 2, 6f, 6f, 0xFF7A4A20.toInt())
            }
            DecorItem.MUSHROOM_TREE -> {
                gfx.rect(x + w / 2 - 6, fy - 50, 12f, 50f, 0xFFF4ECD8.toInt())
                gfx.oval(x - 6, fy - 76, w + 12, 36f, 0xFFE84A4A.toInt())
                for (k in 0 until 4) gfx.oval(x + 2 + k * 13f, fy - 70 + (k % 2) * 8f, 9f, 7f, 0xFFFFFFFF.toInt())
            }
            DecorItem.CANDY_TREE -> {
                gfx.rect(x + w / 2 - 2, fy - 50, 4f, 50f, 0xFFF4F4F4.toInt())
                for (k in 0 until 4) gfx.oval(x + 4 + k * 2f, fy - 82 + k * 2f, w - 8 - k * 4f, w - 8 - k * 4f, if (k % 2 == 0) 0xFFFF6A9A.toInt() else 0xFFFFFFFF.toInt())
            }
            // ---- Yard fun
            DecorItem.SWING_SET -> {
                gfx.poly(floatArrayOf(x, x + 6, x + 10), floatArrayOf(fy, fy - 50, fy), 0xFF3A6AD8.toInt())
                gfx.poly(floatArrayOf(x + w - 10, x + w - 6, x + w), floatArrayOf(fy, fy - 50, fy), 0xFF3A6AD8.toInt())
                gfx.rect(x + 4, fy - 52, w - 8, 4f, 0xFFE84A4A.toInt())
                for (k in 0 until 2) { val sw = sin(t * 2 + k * 2) * 6; val sx = x + 20 + k * 26f; gfx.rect(sx, fy - 48, 1f, 34f, INK); gfx.rect(sx + 8, fy - 48, 1f, 34f, INK); gfx.rect(sx - 1 + sw, fy - 14, 11f, 3f, 0xFFF4D84A.toInt()) }
            }
            DecorItem.SANDBOX -> { gfx.rect(x, fy - 8, w, 10f, 0xFF8A5A2A.toInt()); gfx.rect(x + 3, fy - 7, w - 6, 6f, 0xFFF4DCA0.toInt()); gfx.poly(floatArrayOf(x + 14, x + 22, x + 30), floatArrayOf(fy - 6, fy - 14, fy - 6), 0xFFF0CC88.toInt()); gfx.rect(x + 36, fy - 14, 6f, 8f, 0xFFE84A4A.toInt()) }
            DecorItem.SLIDE_TOWER -> {
                gfx.rect(x, fy - 46, 4f, 46f, 0xFFF4C83A.toInt()); gfx.rect(x + 20, fy - 46, 4f, 46f, 0xFFF4C83A.toInt())
                gfx.rect(x - 2, fy - 48, 28f, 5f, 0xFF3A6AD8.toInt()); gfx.poly(floatArrayOf(x - 2, x + 12, x + 26), floatArrayOf(fy - 48, fy - 62, fy - 48), 0xFFE84A4A.toInt())
                gfx.poly(floatArrayOf(x + 22, x + 28, x + w, x + w - 6), floatArrayOf(fy - 46, fy - 42, fy - 2, fy - 2), 0xFF4AB85A.toInt())
                for (k in 0 until 4) gfx.rect(x, fy - 40 + k * 10f, 24f, 2f, 0xFFF4C83A.toInt())
            }
            DecorItem.BBQ -> {
                gfx.oval(x, fy - 26, w, 14f, INK); gfx.rect(x + 2, fy - 20, w - 4, 2f, 0xFFB8C0CC.toInt())
                gfx.rect(x + 4, fy - 14, 2f, 14f, 0xFF3A3A44.toInt()); gfx.rect(x + w - 6, fy - 14, 2f, 14f, 0xFF3A3A44.toInt())
                for (k in 0 until 2) { val p = (t * 0.7f + k * 0.5f) % 1f; gfx.oval(x + 8 + sin(p * 6) * 3, fy - 30 - p * 20, 6f + p * 6, 6f + p * 6, argb(((1 - p) * 140).toInt(), 0xDDDDDD)) }
                gfx.oval(x + 6, fy - 24, 6f, 3f, 0xFF8A3A2A.toInt()); gfx.oval(x + 14, fy - 24, 6f, 3f, 0xFF8A3A2A.toInt())
            }
            DecorItem.PICNIC_TABLE -> { gfx.rect(x, fy - 20, w, 4f, 0xFFA87A4A.toInt()); gfx.rect(x - 4, fy - 10, w + 8, 3f, 0xFFA87A4A.toInt()); gfx.rect(x + 8, fy - 16, 3f, 16f, 0xFF7A4A20.toInt()); gfx.rect(x + w - 11, fy - 16, 3f, 16f, 0xFF7A4A20.toInt()); for (k in 0 until 4) gfx.rect(x + 4 + k * 13f, fy - 20, 6f, 4f, 0xFFE84A4A.toInt()) }
            DecorItem.BIG_TRAMPOLINE -> { gfx.oval(x, fy - 18, w, 10f, 0xFF2A2A34.toInt()); gfx.oval(x + 4, fy - 17, w - 8, 7f, 0xFF3A6AD8.toInt()); for (k in 0 until 4) gfx.rect(x + 6 + k * (w - 14) / 3, fy - 12, 2f, 12f, 0xFF5A6070.toInt()) }
            DecorItem.DOG_HOUSE -> {
                gfx.rect(x + 2, fy - 26, w - 4, 26f, 0xFFD83A3A.toInt())
                gfx.poly(floatArrayOf(x - 2, x + w / 2, x + w + 2), floatArrayOf(fy - 26, fy - 42, fy - 26), 0xFF6A3A1A.toInt())
                gfx.oval(x + w / 2 - 8, fy - 20, 16f, 22f, INK)
                gfx.text("DENNIS", x + w / 2, fy - 28, 4.5f, 0xFFF4F4F4.toInt(), 1)
            }
            DecorItem.BIRD_BATH -> { gfx.rect(x + 8, fy - 20, 4f, 20f, 0xFFB8C0CC.toInt()); gfx.oval(x, fy - 26, w, 8f, 0xFFB8C0CC.toInt()); gfx.oval(x + 3, fy - 25, w - 6, 4f, 0xFF6AC8F0.toInt()); gfx.oval(x + 12 + sin(t * 3) * 2, fy - 32, 6f, 5f, 0xFF3A6AD8.toInt()) }
            DecorItem.GNOME -> { gfx.oval(x, fy - 12, 12f, 12f, 0xFF3A6AD8.toInt()); gfx.oval(x + 2, fy - 17, 8f, 7f, 0xFFF4C88A.toInt()); gfx.poly(floatArrayOf(x + 1, x + 6, x + 11), floatArrayOf(fy - 15, fy - 28, fy - 15), 0xFFE84A4A.toInt()); gfx.oval(x + 2, fy - 13, 8f, 6f, 0xFFF4F4F4.toInt()) }
            DecorItem.FLOWER_BED -> { gfx.oval(x, fy - 2, w, 10f, 0xFF6A4A2A.toInt()); for (k in 0 until 6) { gfx.rect(x + 5 + k * 7.5f, fy - 8, 1f, 8f, 0xFF3AA84A.toInt()); gfx.oval(x + 3 + k * 7.5f, fy - 12, 5f, 5f, listOf(0xFFFF6A9A, 0xFFFFD21F, 0xFF8A6AE8, 0xFFFF8A3A)[k % 4].toInt()) } }
            DecorItem.FIRE_PIT -> { for (k in 0 until 5) gfx.oval(x + k * 6f, fy - 6, 8f, 7f, 0xFF8A8A9A.toInt()); val fl = sin(t * 10) * 2; gfx.oval(x + 9, fy - 20 + fl, 12f, 16f, 0xFFFF7A2A.toInt()); gfx.oval(x + 12, fy - 14 + fl, 6f, 9f, 0xFFFFE14D.toInt()) }
            DecorItem.UMBRELLA -> { gfx.rect(x + w / 2 - 1, fy - 40, 2f, 40f, 0xFFF4F4F4.toInt()); for (k in 0 until 4) gfx.poly(floatArrayOf(x - 4 + k * 9.5f, x + w / 2, x + 5.5f + k * 9.5f), floatArrayOf(fy - 36, fy - 48, fy - 36), if (k % 2 == 0) 0xFFE84A4A.toInt() else 0xFFF4F4F4.toInt()) }
            DecorItem.LAWN_CHAIRS -> for (k in 0 until 2) { val cx = x + k * 28f; gfx.poly(floatArrayOf(cx, cx + 6, cx + 22, cx + 18), floatArrayOf(fy - 24, fy - 26, fy - 6, fy - 4), 0xFF4AB8F0.toInt()); gfx.rect(cx + 4, fy - 6, 18f, 3f, 0xFF4AB8F0.toInt()); gfx.rect(cx + 6, fy - 3, 1.5f, 3f, 0xFFB8C0CC.toInt()); gfx.rect(cx + 20, fy - 3, 1.5f, 3f, 0xFFB8C0CC.toInt()) }
            DecorItem.SOCCER_GOAL -> { gfx.rect(x, fy - 30, 3f, 30f, 0xFFF4F4F4.toInt()); gfx.rect(x + w - 3, fy - 30, 3f, 30f, 0xFFF4F4F4.toInt()); gfx.rect(x, fy - 31, w, 3f, 0xFFF4F4F4.toInt()); for (k in 0 until 5) gfx.rect(x + 3 + k * 10f, fy - 28, 0.8f, 28f, 0x88FFFFFF.toInt()); gfx.oval(x + w + 4, fy - 8, 8f, 8f, 0xFFF4F4F4.toInt()) }
            DecorItem.LAMP_POST -> { gfx.rect(x + 6, fy - 50, 2f, 50f, INK); gfx.rect(x + 2, fy - 58, 10f, 10f, 0xFFFFF2A0.toInt()); gfx.rect(x + 1, fy - 60, 12f, 3f, INK); gfx.oval(x - 10, fy - 66, 34f, 26f, 0x22FFF2A0) }
            DecorItem.POND -> { gfx.oval(x, fy - 4, w, 20f, 0xFF8A8A9A.toInt()); gfx.oval(x + 5, fy - 1, w - 10, 14f, 0xFF3A8AC8.toInt()); for (k in 0 until 2) { val fx = x + 15 + ((t * 12 + k * 30) % (w - 34)); gfx.oval(fx, fy + 4 + k * 3f, 8f, 4f, 0xFFFF8A3A.toInt()) }; gfx.oval(x + w - 26, fy, 12f, 5f, 0xFF3AA84A.toInt()) }
            DecorItem.BOUNCY_HOUSE -> {
                val sq = sin(t * 5) * 1.5f
                gfx.rect(x, fy - 30 + sq, w, 30f - sq, 0xFFF4D84A.toInt())
                for (k in 0 until 4) gfx.oval(x - 4 + k * 28f, fy - 46 + sq, 18f, 22f, listOf(0xFFE84A4A, 0xFF3A6AD8, 0xFF4AB85A, 0xFFE84A4A)[k].toInt())
                gfx.oval(x + w / 2 - 14, fy - 24 + sq, 28f, 24f, 0xFF3A6AD8.toInt())
            }
            DecorItem.SNOWMAN -> { gfx.oval(x, fy - 16, w, 16f, 0xFFF4F8FF.toInt()); gfx.oval(x + 3, fy - 28, w - 6, 14f, 0xFFF4F8FF.toInt()); gfx.oval(x + 6, fy - 37, w - 12, 11f, 0xFFF4F8FF.toInt()); gfx.poly(floatArrayOf(x + 12, x + 18, x + 12), floatArrayOf(fy - 33, fy - 32, fy - 31), 0xFFF08A3A.toInt()); gfx.rect(x + 6, fy - 42, 12f, 6f, INK) }
            DecorItem.MAILBOX -> { gfx.rect(x + 6, fy - 20, 2f, 20f, 0xFF7A4A20.toInt()); gfx.rect(x, fy - 28, 14f, 9f, 0xFF3A6AD8.toInt()); gfx.oval(x, fy - 31, 14f, 7f, 0xFF3A6AD8.toInt()); gfx.rect(x + 12, fy - 34, 1.5f, 8f, 0xFFE84A4A.toInt()) }
        }
    }

    private fun duck(gfx: Gfx, x: Float, y: Float) {
        gfx.oval(x - 4, y - 3, 8f, 5f, 0xFFF4D84A.toInt()); gfx.oval(x + 1, y - 6, 4f, 4f, 0xFFF4D84A.toInt())
        gfx.poly(floatArrayOf(x + 4.5f, x + 6.5f, x + 4.5f), floatArrayOf(y - 5, y - 4, y - 3), 0xFFF08A3A.toInt())
    }
}
