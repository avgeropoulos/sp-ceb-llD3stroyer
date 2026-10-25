package com.roccoquest.team

import com.roccoquest.game.Gfx
import kotlin.math.sin

/** Hats and head things for outfits and Pokemon costumes. */
enum class HatKind { NONE, TOPHAT, PIRATE, HARDHAT, SPACE, CHEF, COWBOY, HEADBAND, CAPE, HELMET, SANTA, PARTY, BOW, SUNGLASSES, WITCH, CROWN,
    VIKING, BEANIE, FLOWER, HEADPHONES, GRADCAP, PROPELLER, BUNNY, HALO, TIARA, STRAW, CATEARS, CAP, DINO, FIREHAT, WIZARD, MUSHROOM }

/** Rocco's outfits: [shirt] replaces his red cap and shirt, [overalls] his blue overalls. Saved by index (new ones at the END). */
enum class RoccoOutfit(val label: String, val coins: Int, val shirt: Int, val overalls: Int, val hat: HatKind = HatKind.NONE) {
    CLASSIC("CLASSIC ROCCO", 0, 0, 0),
    GREEN_BRO("GREEN BROTHER", 150, 0xFF2EA84A.toInt(), 0xFF2A3A8A.toInt()),
    YELLOW_RASCAL("YELLOW RASCAL", 200, 0xFFF4D84A.toInt(), 0xFF6A3AA8.toInt()),
    TUXEDO("FANCY TUXEDO", 300, 0xFF2A2A30.toInt(), 0xFF1A1A20.toInt(), HatKind.TOPHAT),
    PIRATE("PIRATE ROCCO", 250, 0xFF8A2A2A.toInt(), 0xFF3A2A1A.toInt(), HatKind.PIRATE),
    BUILDER("BUILDER ROCCO", 200, 0xFFF08A2A.toInt(), 0xFF3A5A9A.toInt(), HatKind.HARDHAT),
    ASTRONAUT("ASTRONAUT", 400, 0xFFF4F4F4.toInt(), 0xFFD8D8E0.toInt(), HatKind.SPACE),
    CHEF("CHEF ROCCO", 250, 0xFFF4F4F4.toInt(), 0xFF8A8A9A.toInt(), HatKind.CHEF),
    COWBOY("COWBOY ROCCO", 250, 0xFF8A5A2A.toInt(), 0xFF4A6A9A.toInt(), HatKind.COWBOY),
    NINJA("NINJA ROCCO", 300, 0xFF2A2A30.toInt(), 0xFF2A2A30.toInt(), HatKind.HEADBAND),
    SUPERHERO("SUPER ROCCO", 400, 0xFF3A6AD8.toInt(), 0xFFE84A3A.toInt(), HatKind.CAPE),
    FIREFIGHTER("FIREFIGHTER", 250, 0xFFE8C83A.toInt(), 0xFF3A3A44.toInt(), HatKind.FIREHAT),
    DOCTOR("DOCTOR ROCCO", 200, 0xFFF4F4F4.toInt(), 0xFF5AB0C8.toInt(), HatKind.HEADBAND),
    POLICE("POLICE ROCCO", 250, 0xFF3A5AA8.toInt(), 0xFF1A2A5A.toInt(), HatKind.CAP),
    FARMER("FARMER ROCCO", 150, 0xFFE84A4A.toInt(), 0xFF6A8AC8.toInt(), HatKind.STRAW),
    SURFER("SURFER ROCCO", 200, 0xFF3AD8C8.toInt(), 0xFFF08A3A.toInt(), HatKind.SUNGLASSES),
    KING("KING ROCCO", 500, 0xFF8A3AD8.toInt(), 0xFFE8C83A.toInt(), HatKind.CROWN),
    WIZARD("WIZARD ROCCO", 400, 0xFF3A3AA8.toInt(), 0xFF6A3AA8.toInt(), HatKind.WIZARD),
    VIKING("VIKING ROCCO", 350, 0xFF8A5A2A.toInt(), 0xFF6A6A7A.toInt(), HatKind.VIKING),
    RAINBOW("RAINBOW ROCCO", 300, 0xFFFF6A9A.toInt(), 0xFF6AC8FF.toInt(), HatKind.PROPELLER),
    DINO_TAMER("DINO TAMER", 350, 0xFF5AB84A.toInt(), 0xFF8A5A2A.toInt(), HatKind.DINO),
    MUSHROOM("MUSHROOM ROCCO", 250, 0xFFF4F4F4.toInt(), 0xFFE84A4A.toInt(), HatKind.MUSHROOM),
}

/** Blocky's skins. Saved by index (new ones at the END). */
enum class BlockySkin(val label: String, val coins: Int, val shirt: Int, val shirtDark: Int, val legs: Int, val skin: Int = 0, val hat: HatKind = HatKind.NONE) {
    CLASSIC("CLASSIC BLOCKY", 0, 0, 0, 0),
    DIAMOND("DIAMOND ARMOR", 400, 0xFF6AE8F0.toInt(), 0xFF3AB8C8.toInt(), 0xFF3AB8C8.toInt(), hat = HatKind.HELMET),
    GOLD("GOLD ARMOR", 350, 0xFFF4D84A.toInt(), 0xFFC8A030.toInt(), 0xFFC8A030.toInt(), hat = HatKind.HELMET),
    ZOMBIE("ZOMBIE BLOCKY", 200, 0xFF2AA7A7.toInt(), 0xFF1E8484.toInt(), 0xFF3A3AA8.toInt(), skin = 0xFF5AA85A.toInt()),
    KNIGHT("KNIGHT BLOCKY", 300, 0xFFB8C0CC.toInt(), 0xFF8A94A4.toInt(), 0xFF8A94A4.toInt(), hat = HatKind.HELMET),
    NINJA("NINJA BLOCKY", 250, 0xFF2A2A30.toInt(), 0xFF1A1A20.toInt(), 0xFF2A2A30.toInt(), hat = HatKind.HEADBAND),
    ASTRONAUT("SPACE BLOCKY", 400, 0xFFF4F4F4.toInt(), 0xFFD8D8E0.toInt(), 0xFFD8D8E0.toInt(), hat = HatKind.SPACE),
    PIRATE("PIRATE BLOCKY", 250, 0xFFE84A4A.toInt(), 0xFF2A2A30.toInt(), 0xFF3A2A1A.toInt(), hat = HatKind.PIRATE),
    SANTA("SANTA BLOCKY", 200, 0xFFE84A4A.toInt(), 0xFFF4F4F4.toInt(), 0xFFE84A4A.toInt(), hat = HatKind.SANTA),
    ICE("ICE BLOCKY", 250, 0xFFBFE8FF.toInt(), 0xFF8AC8F0.toInt(), 0xFF6AA8E0.toInt(), skin = 0xFFDDF4FF.toInt()),
    LAVA("LAVA BLOCKY", 300, 0xFFF08A1A.toInt(), 0xFFC83A1A.toInt(), 0xFF3A2A28.toInt()),
    EMERALD("EMERALD ARMOR", 400, 0xFF3AD86A.toInt(), 0xFF2AA84A.toInt(), 0xFF2AA84A.toInt(), hat = HatKind.HELMET),
    REDSTONE("REDSTONE BLOCKY", 250, 0xFFC82A2A.toInt(), 0xFF8A1A1A.toInt(), 0xFF5A5A6A.toInt()),
    ENDER("ENDER BLOCKY", 300, 0xFF2A2A30.toInt(), 0xFF1A1A20.toInt(), 0xFF1A1A20.toInt(), skin = 0xFF3A2A4A.toInt()),
    BEE("BEE BLOCKY", 200, 0xFFF4D84A.toInt(), 0xFF2A2A30.toInt(), 0xFF2A2A30.toInt(), hat = HatKind.BEANIE),
    WIZARD("WIZARD BLOCKY", 350, 0xFF6A3AA8.toInt(), 0xFF4A2A8A.toInt(), 0xFF3A2A6A.toInt(), hat = HatKind.WIZARD),
    PRINCE("PRINCE BLOCKY", 450, 0xFF3A6AD8.toInt(), 0xFFE8C83A.toInt(), 0xFF2A3A8A.toInt(), hat = HatKind.CROWN),
    VIKING("VIKING BLOCKY", 300, 0xFF8A5A2A.toInt(), 0xFF6A4A20.toInt(), 0xFF6A6A7A.toInt(), hat = HatKind.VIKING),
}

/** Costumes for your Pokemon partner. They're expensive! Saved by index (new ones at the END). */
enum class MonCostume(val label: String, val coins: Int, val hat: HatKind) {
    PARTY_HAT("PARTY HAT", 500, HatKind.PARTY),
    BOW("BIG BOW", 500, HatKind.BOW),
    SUNGLASSES("COOL SHADES", 700, HatKind.SUNGLASSES),
    COWBOY("COWBOY HAT", 800, HatKind.COWBOY),
    PIRATE("PIRATE HAT", 900, HatKind.PIRATE),
    WITCH("WITCH HAT", 900, HatKind.WITCH),
    CHEF("CHEF HAT", 900, HatKind.CHEF),
    SANTA("SANTA HAT", 1000, HatKind.SANTA),
    TOPHAT("TOP HAT", 1200, HatKind.TOPHAT),
    CROWN("ROYAL CROWN", 1500, HatKind.CROWN),
    VIKING("VIKING HELMET", 900, HatKind.VIKING),
    BEANIE("COZY BEANIE", 500, HatKind.BEANIE),
    FLOWER("FLOWER CROWN", 600, HatKind.FLOWER),
    HEADPHONES("HEADPHONES", 800, HatKind.HEADPHONES),
    GRADCAP("GRAD CAP", 700, HatKind.GRADCAP),
    PROPELLER("PROPELLER CAP", 700, HatKind.PROPELLER),
    BUNNY("BUNNY EARS", 600, HatKind.BUNNY),
    HALO("ANGEL HALO", 1100, HatKind.HALO),
    TIARA("SPARKLY TIARA", 1300, HatKind.TIARA),
    CATEARS("CAT EARS", 600, HatKind.CATEARS),
    DINO("DINO HOOD", 1000, HatKind.DINO),
}

/** What everyone is wearing right now (the art reads these). */
object Style {
    var rocco = RoccoOutfit.CLASSIC
    var blocky = BlockySkin.CLASSIC
    var monCostume: MonCostume? = null
    /** On a SPACE WALK everyone has to wear a spacesuit (set by the game while that level is on). */
    @Volatile var spaceSuit = false
    val roccoNow get() = if (spaceSuit) RoccoOutfit.ASTRONAUT else rocco
    val blockyNow get() = if (spaceSuit) BlockySkin.ASTRONAUT else blocky

    /**
     * Draws [hat] on a head whose top middle is at ([cx], [top]); [size] is how wide the head is.
     * The cape goes behind, so draw it before the body ([behind] = true).
     */
    fun hat(gfx: Gfx, hat: HatKind, cx: Float, top: Float, size: Float, facing: Int, t: Float, behind: Boolean = false) {
        val k = size / 12f
        val f = facing.toFloat()
        fun r(x: Float, y: Float, w: Float, h: Float, c: Int) = gfx.rect(cx + x * k, top + y * k, w * k, h * k, c)
        fun o(x: Float, y: Float, w: Float, h: Float, c: Int) = gfx.oval(cx + x * k, top + y * k, w * k, h * k, c)
        fun p(xs: FloatArray, ys: FloatArray, c: Int) = gfx.poly(FloatArray(xs.size) { cx + xs[it] * k }, FloatArray(ys.size) { top + ys[it] * k }, c)
        if (behind != (hat == HatKind.CAPE)) return
        when (hat) {
            HatKind.NONE -> {}
            HatKind.TOPHAT -> { r(-7f, 0f, 14f, 2f, 0xFF1A1A20.toInt()); r(-4.5f, -9f, 9f, 9f, 0xFF1A1A20.toInt()); r(-4.5f, -3f, 9f, 1.5f, 0xFFE84A4A.toInt()) }
            HatKind.PIRATE -> { p(floatArrayOf(-9f, 0f, 9f), floatArrayOf(1f, -8f, 1f), 0xFF1A1A20.toInt()); o(-2f, -5f, 4f, 3.5f, 0xFFF4F4F4.toInt()); r(-9f, 0f, 18f, 1.5f, 0xFFE8C83A.toInt()) }
            HatKind.HARDHAT -> { o(-7f, -4f, 14f, 9f, 0xFFF4C83A.toInt()); r(-8.5f, 2f, 17f, 2f, 0xFFE8A82A.toInt()) }
            HatKind.SPACE -> { o(-9f, -3f, 18f, 18f, 0x55BFE8FF); o(-9f, -3f, 18f, 3f, 0x88FFFFFF.toInt()); r(-7f, 13f, 14f, 2f, 0xFFB8C0CC.toInt()) }
            HatKind.CHEF -> { r(-5f, -4f, 10f, 5f, 0xFFF4F4F4.toInt()); o(-7f, -11f, 7f, 8f, 0xFFF4F4F4.toInt()); o(0f, -11f, 7f, 8f, 0xFFF4F4F4.toInt()); o(-3.5f, -13f, 7f, 7f, 0xFFF4F4F4.toInt()) }
            HatKind.COWBOY -> { o(-10f, -1f, 20f, 4f, 0xFF8A5A2A.toInt()); o(-5f, -7f, 10f, 8f, 0xFF8A5A2A.toInt()); r(-5f, -2f, 10f, 1.5f, 0xFF4A2A10.toInt()) }
            HatKind.HEADBAND -> { r(-7f, 2f, 14f, 2f, 0xFFE84A3A.toInt()); p(floatArrayOf(-f * 6f, -f * 12f, -f * 11f), floatArrayOf(2.5f, 1f + sin(t * 8), 5f + sin(t * 8)), 0xFFE84A3A.toInt()) }
            HatKind.CAPE -> p(floatArrayOf(-f * 2f, -f * 6f, -f * 15f + sin(t * 6) * 1.5f, -f * 9f), floatArrayOf(10f, 10f, 26f, 27f), 0xFFE84A3A.toInt())
            HatKind.HELMET -> { r(-7f, -1f, 14f, 4f, 0xFF8A94A4.toInt()); r(-7f, -1f, 2.5f, 8f, 0xFF8A94A4.toInt()); r(4.5f, -1f, 2.5f, 8f, 0xFF8A94A4.toInt()) }
            HatKind.SANTA -> { p(floatArrayOf(-6f, f * 8f, 6f), floatArrayOf(1f, -9f, 1f), 0xFFE84A4A.toInt()); r(-7f, 0f, 14f, 3f, 0xFFF4F4F4.toInt()); o(f * 8f - 2f, -11f, 4f, 4f, 0xFFF4F4F4.toInt()) }
            HatKind.PARTY -> { p(floatArrayOf(-4f, 0f, 4f), floatArrayOf(1f, -11f, 1f), 0xFF8A3AD8.toInt()); for (i in 0 until 3) o(-2.5f + i * 0.8f, -9f + i * 3f, 2f, 2f, 0xFFFFD21F.toInt()); o(-1.5f, -13f, 3f, 3f, 0xFFFF6A9A.toInt()) }
            HatKind.BOW -> { p(floatArrayOf(0f, -6f, -6f), floatArrayOf(-1f, -5f, 3f), 0xFFFF6A9A.toInt()); p(floatArrayOf(0f, 6f, 6f), floatArrayOf(-1f, -5f, 3f), 0xFFFF6A9A.toInt()); o(-1.5f, -2.5f, 3f, 3f, 0xFFE84A6A.toInt()) }
            HatKind.SUNGLASSES -> { r(-6f, 4f, 12f, 1f, 0xFF1A1A20.toInt()); o(-6f + f, 4f, 5f, 3.5f, 0xFF1A1A20.toInt()); o(1f + f, 4f, 5f, 3.5f, 0xFF1A1A20.toInt()); r(-4f + f, 4.5f, 1.5f, 1f, 0x88FFFFFF.toInt()) }
            HatKind.WITCH -> { o(-9f, -1f, 18f, 4f, 0xFF3A2A4A.toInt()); p(floatArrayOf(-5f, -f * 6f, 5f), floatArrayOf(1f, -14f, 1f), 0xFF3A2A4A.toInt()); r(-5f, -2f, 10f, 1.5f, 0xFF8A3AD8.toInt()) }
            HatKind.CROWN -> { p(floatArrayOf(-6f, -6f, -3f, 0f, 3f, 6f, 6f), floatArrayOf(1f, -6f, -2f, -7f, -2f, -6f, 1f), 0xFFF4D84A.toInt()); o(-1f, -4f, 2f, 2f, 0xFFE84A4A.toInt()) }
            HatKind.VIKING -> {
                o(-7f, -4f, 14f, 9f, 0xFF9AA4B4.toInt()); r(-7.5f, 2f, 15f, 2f, 0xFF8A5A2A.toInt())
                p(floatArrayOf(-6f, -11f, -9f), floatArrayOf(-1f, -8f, -1f), 0xFFF4ECD0.toInt()); p(floatArrayOf(6f, 11f, 9f), floatArrayOf(-1f, -8f, -1f), 0xFFF4ECD0.toInt())
            }
            HatKind.BEANIE -> { o(-7f, -5f, 14f, 10f, 0xFFE84A6A.toInt()); r(-7.5f, 2f, 15f, 3f, 0xFFF4F4F4.toInt()); o(-2f, -8f, 4f, 4f, 0xFFF4F4F4.toInt()) }
            HatKind.FLOWER -> for (i in 0 until 5) { o(-7f + i * 3f, -1f - (if (i % 2 == 0) 1f else 0f), 4f, 4f, intArrayOf(0xFFFF6A9A.toInt(), 0xFFFFE14D.toInt(), 0xFF8AD8FF.toInt())[i % 3]); o(-6f + i * 3f, 0f - (if (i % 2 == 0) 1f else 0f), 2f, 2f, 0xFFFFFFFF.toInt()) }
            HatKind.HEADPHONES -> { r(-7f, -3f, 14f, 2f, 0xFF2A2A30.toInt()); r(-8f, -2f, 2f, 6f, 0xFF2A2A30.toInt()); r(6f, -2f, 2f, 6f, 0xFF2A2A30.toInt()); o(-10f, 3f, 5f, 6f, 0xFFE84A4A.toInt()); o(5f, 3f, 5f, 6f, 0xFFE84A4A.toInt()) }
            HatKind.GRADCAP -> { r(-6f, -2f, 12f, 4f, 0xFF1A1A20.toInt()); p(floatArrayOf(-10f, 0f, 10f, 0f), floatArrayOf(-3f, -6f, -3f, 0f), 0xFF2A2A30.toInt()); r(f * 6f, -3f, 1f, 7f, 0xFFF4D84A.toInt()) }
            HatKind.PROPELLER -> {
                o(-6f, -4f, 12f, 8f, 0xFF3A6AD8.toInt()); r(-6f, -1f, 12f, 1.5f, 0xFFE84A4A.toInt()); r(-0.5f, -7f, 1f, 4f, 0xFF2A2A30.toInt())
                val w = 7f * kotlin.math.abs(kotlin.math.cos(t * 14f)) + 1f
                r(-w, -8f, w * 2, 1.5f, 0xFFF4D84A.toInt())
            }
            HatKind.BUNNY -> { o(-6f, -12f, 4f, 13f, 0xFFF4F4F4.toInt()); o(2f, -12f, 4f, 13f, 0xFFF4F4F4.toInt()); o(-5f, -10f, 2f, 9f, 0xFFFFB8D0.toInt()); o(3f, -10f, 2f, 9f, 0xFFFFB8D0.toInt()); r(-7f, 0f, 14f, 2f, 0xFFFF6A9A.toInt()) }
            HatKind.HALO -> { o(-6f, -6f + sin(t * 3f), 12f, 4f, 0xFFFFE14D.toInt()); o(-4f, -5f + sin(t * 3f), 8f, 2f, 0x00000000) }
            HatKind.TIARA -> { p(floatArrayOf(-5f, -3f, 0f, 3f, 5f), floatArrayOf(1f, -3f, -5f, -3f, 1f), 0xFFE0E8F0.toInt()); o(-1.2f, -3.5f, 2.4f, 2.4f, 0xFFFF6A9A.toInt()); o(-4f, -1.5f, 1.5f, 1.5f, 0xFF6AE8F0.toInt()); o(2.5f, -1.5f, 1.5f, 1.5f, 0xFF6AE8F0.toInt()) }
            HatKind.STRAW -> { o(-11f, -1f, 22f, 5f, 0xFFE8C87A.toInt()); o(-5f, -6f, 10f, 8f, 0xFFE8C87A.toInt()); r(-5f, -1f, 10f, 1.5f, 0xFFE84A4A.toInt()) }
            HatKind.CATEARS -> { r(-7f, 0f, 14f, 2f, 0xFF2A2A30.toInt()); p(floatArrayOf(-7f, -6f, -2f), floatArrayOf(1f, -6f, 0f), 0xFF2A2A30.toInt()); p(floatArrayOf(7f, 6f, 2f), floatArrayOf(1f, -6f, 0f), 0xFF2A2A30.toInt()); p(floatArrayOf(-6f, -5.5f, -3f), floatArrayOf(0f, -4f, 0f), 0xFFFFB8D0.toInt()); p(floatArrayOf(6f, 5.5f, 3f), floatArrayOf(0f, -4f, 0f), 0xFFFFB8D0.toInt()) }
            HatKind.CAP -> { o(-7f, -4f, 14f, 9f, 0xFF2A3A8A.toInt()); r(f * 2f - (if (facing < 0) 9f else 0f), 2f, 9f, 2f, 0xFF1A2A5A.toInt()); o(-1.5f, -2f, 3f, 3f, 0xFFF4D84A.toInt()) }
            HatKind.DINO -> {
                o(-8f, -4f, 16f, 12f, 0xFF5AB84A.toInt())
                for (i in 0 until 4) p(floatArrayOf(-6f + i * 4f, -4f + i * 4f, -2f + i * 4f), floatArrayOf(-2f, -7f, -2f), 0xFFF4D84A.toInt())
                o(f * 3f - 1f, -1f, 2.5f, 2.5f, 0xFFFFFFFF.toInt())
            }
            HatKind.FIREHAT -> { o(-8f, -1f, 16f, 5f, 0xFFC82A2A.toInt()); o(-6f, -6f, 12f, 9f, 0xFFE84A3A.toInt()); r(-1.5f, -5f, 3f, 4f, 0xFFF4D84A.toInt()) }
            HatKind.WIZARD -> { o(-9f, -1f, 18f, 4f, 0xFF2A3A9A.toInt()); p(floatArrayOf(-5f, -f * 4f, 5f), floatArrayOf(1f, -15f, 1f), 0xFF3A4AC8.toInt()); o(-2f, -8f, 2f, 2f, 0xFFFFE14D.toInt()); o(1f, -4f, 1.5f, 1.5f, 0xFFFFE14D.toInt()) }
            HatKind.MUSHROOM -> { o(-9f, -7f, 18f, 11f, 0xFFF4F4F4.toInt()); o(-6f, -6f, 5f, 4f, 0xFFE84A4A.toInt()); o(2f, -6f, 5f, 4f, 0xFFE84A4A.toInt()); o(-1.5f, -8f, 3f, 3f, 0xFFE84A4A.toInt()) }
        }
    }

    /** The costume on the Pokemon partner (drawn over the Pokemon at [cx], feet at [bottom]). */
    fun drawCostume(gfx: Gfx, m: Mon, cx: Float, bottom: Float, facing: Int, t: Float, costume: MonCostume? = monCostume) {
        val c = costume ?: return
        val (top, hx, w) = MonSize.head(m)
        val x = cx + hx * facing
        val y = bottom - top
        if (c.hat == HatKind.SUNGLASSES) hat(gfx, c.hat, x + facing * 1f, y + w * 0.22f, w * 0.8f, facing, t)
        else hat(gfx, c.hat, x, y + 1f, w * 0.75f, facing, t)
    }
}
