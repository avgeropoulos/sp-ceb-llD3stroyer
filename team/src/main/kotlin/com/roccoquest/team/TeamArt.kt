package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.Sprites
import com.roccoquest.game.argb
import com.roccoquest.game.pixelArt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Pixel art and hand-drawn pieces that only Team Up! uses. Rocco's own art comes from [Sprites]. */
object TeamArt {
    private const val RED = 0xFFE52521.toInt()
    private const val BLUE = 0xFF2B4FD8.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    // ---------------------------------------------------------------- Blocky the miner
    private const val SHIRT = 0xFF2AA7A7.toInt()
    private const val SHIRT_DARK = 0xFF1E8484.toInt()
    private val blockyPal = mapOf(
        'H' to 0xFF4A2E14.toInt(), 'S' to 0xFFC89070.toInt(), 's' to 0xFFA87050.toInt(),
        'E' to WHITE, 'P' to 0xFF4A3AA8.toInt(), 'M' to 0xFF7A4A30.toInt(),
        'T' to SHIRT, 't' to SHIRT_DARK, 'L' to 0xFF3A3AA8.toInt(), 'F' to 0xFF5A5A5A.toInt(),
    )
    private val blockyTop = arrayOf(
        "...HHHHHHHHHH...",
        "...HHHHHHHHHH...",
        "...HSSSSSSSSH...",
        "...SSSSSSSSSS...",
        "...SEPSSSSPES...",
        "...SSSSssSSSS...",
        "...SSSMMMMSSS...",
        "...SSSSSSSSSS...",
        "...TTTTSSTTTT...",
        ".TTTTTTTTTTTTTT.",
        ".TTTTTTTTTTTTTT.",
        ".TTtTTTTTTTTtTT.",
        ".SStTTTTTTTTtSS.",
        ".SS.TTTTTTTT.SS.",
        ".SS.TTTTTTTT.SS.",
        "....LLLLLLLL....",
        "....LLLLLLLL....",
        "....LLLLLLLL....",
    )
    val blockyStand = pixelArt(blockyPal, *blockyTop,
        "....LLL..LLL....",
        "....LLL..LLL....",
        "....LLL..LLL....",
        "....LLL..LLL....",
        "....FFF..FFF....",
        "...FFFF..FFFF...",
    )
    val blockyWalk = pixelArt(blockyPal, *blockyTop,
        "...LLL....LLL...",
        "...LLL....LLL...",
        "..LLL......LLL..",
        "..LLL......LLL..",
        "..FFF......FFF..",
        ".FFFF......FFFF.",
    )

    /** Blocky's shirt changes color with the team's flower power, like Rocco's outfit. */
    fun blockyShirt(p: Power): Pair<Int, Int> = when (p) {
        Power.NONE -> SHIRT to SHIRT_DARK
        Power.BOOM -> 0xFFFF8A00.toInt() to 0xFFC86400.toInt()
        Power.FIRE -> 0xFFF4F4F4.toInt() to RED
        Power.ICE -> 0xFF9FE0FF.toInt() to 0xFF4A9AD8.toInt()
        Power.GRASS -> 0xFF2EB84A.toInt() to 0xFF1E7A2E.toInt()
        Power.THUNDER -> 0xFFFFE14D.toInt() to 0xFFC87A10.toInt()
        Power.CRYSTAL -> 0xFFC890FF.toInt() to 0xFF7A3AC0.toInt()
        Power.SHELL -> 0xFF2E6BE6.toInt() to 0xFF1A3A9A.toInt()
    }

    private val blockyCache = HashMap<Power, Pair<Sprite, Sprite>>()
    fun blocky(p: Power, walking: Boolean): Sprite {
        val pair = blockyCache.getOrPut(p) {
            val (a, b) = blockyShirt(p)
            val m = mapOf(SHIRT to a, SHIRT_DARK to b)
            blockyStand.recolor(m) to blockyWalk.recolor(m)
        }
        return if (walking) pair.second else pair.first
    }

    // ---------------------------------------------------------------- Rocco outfits
    private val grassMap = mapOf(RED to 0xFF2EB84A.toInt(), BLUE to 0xFF1E7A2E.toInt())
    private val grassStand = Sprites.bigStand.recolor(grassMap)
    private val grassWalk = Sprites.bigWalk.recolor(grassMap)
    private val grassJump = Sprites.bigJump.recolor(grassMap)
    private val thunderMap = mapOf(RED to 0xFFFFD21F.toInt(), BLUE to 0xFFC87A10.toInt())
    private val thunder = arrayOf(Sprites.bigStand, Sprites.bigWalk, Sprites.bigJump).map { it.recolor(thunderMap) }
    private val crystalMap = mapOf(RED to 0xFFC890FF.toInt(), BLUE to 0xFF5A2A9A.toInt())
    private val crystal = arrayOf(Sprites.bigStand, Sprites.bigWalk, Sprites.bigJump).map { it.recolor(crystalMap) }

    /** frame: 0 stand, 1 walk, 2 jump */
    fun rocco(p: Power, frame: Int): Sprite = if (p == Power.THUNDER) thunder[frame] else if (p == Power.CRYSTAL) crystal[frame] else when (p) {
        Power.NONE -> arrayOf(Sprites.bigStand, Sprites.bigWalk, Sprites.bigJump)
        Power.FIRE -> arrayOf(Sprites.fireStand, Sprites.fireWalk, Sprites.fireJump)
        Power.ICE -> arrayOf(Sprites.iceStand, Sprites.iceWalk, Sprites.iceJump)
        Power.BOOM -> arrayOf(Sprites.boomStand, Sprites.boomWalk, Sprites.boomJump)
        Power.SHELL -> arrayOf(Sprites.shellStand, Sprites.shellWalk, Sprites.shellJump)
        else -> arrayOf(grassStand, grassWalk, grassJump)
    }[frame]

    // ---------------------------------------------------------------- Blocks
    private val orePal = mapOf(
        'g' to 0xFF8A8A8A.toInt(), 'G' to 0xFF6E6E6E.toInt(), 'l' to 0xFFA8A8A8.toInt(),
        'x' to 0xFFFF00FF.toInt(), 'X' to 0xFF800080.toInt(),
    )
    private val oreBase = pixelArt(orePal,
        "gggGgggggglggggg",
        "gxxggggGgggggGgg",
        "gxXgglggggxxgggg",
        "ggggggggggxXgggl",
        "gGgggxxggggggggg",
        "ggggxXxggGgggggg",
        "gglggggggggggxxg",
        "gggggggGggggxXgg",
        "gGgxxgggggggggGg",
        "gggxXggglggggggg",
        "ggggggggggxxgggg",
        "gglggGgggxXxgggg",
        "ggggggggggggglgg",
        "gxxggggGgggggggg",
        "gxXgglgggggxxGgg",
        "ggggggggggggXggg",
    )
    private fun oreBlock(o: Ore) = oreBase.recolor(
        if (o == Ore.STONE) mapOf(0xFFFF00FF.toInt() to 0xFF7C7C7C.toInt(), 0xFF800080.toInt() to 0xFF6E6E6E.toInt())
        else mapOf(0xFFFF00FF.toInt() to o.color, 0xFF800080.toInt() to o.dark),
    )
    val ores: Map<Ore, Sprite> = Ore.entries.associateWith { oreBlock(it) }

    // ---------------------------------------------------------------- Items
    private val flowerPal = mapOf(
        'P' to 0xFFFF8A00.toInt(), 'R' to RED, 'Y' to 0xFFFFE14D.toInt(), 'G' to 0xFF1E9E3A.toInt(),
        'L' to 0xFF8EE07A.toInt(), 'K' to 0xFF101010.toInt(),
    )
    /** The Grass Flower: a leafy green flower that gives the team green armor. */
    val grassFlower = pixelArt(flowerPal,
        "......GGGG......",
        "....GGLLLLGG....",
        "...GLLGGGGLLG...",
        "..GLLGYYYYGLLG..",
        "..GLGYYKKYYGLG..",
        "..GLGYYKKYYGLG..",
        "..GLLGYYYYGLLG..",
        "...GLLGGGGLLG...",
        "....GGLLLLGG....",
        "......GGGG......",
        ".......GG.......",
        "..GGG..GG..GGG..",
        "..GLLG.GG.GLLG..",
        "...GLLGGGGLLG...",
        "....GGGGGGGG....",
        ".......GG.......",
    )

    private val thunderFlower = Sprites.blossom1.recolor(mapOf(0xFFFF8A00.toInt() to 0xFFFFE14D.toInt(), RED to 0xFFE8803A.toInt()))
    private val crystalFlower = Sprites.blossom1.recolor(mapOf(0xFFFF8A00.toInt() to 0xFFC890FF.toInt(), RED to 0xFF7A3AC0.toInt()))

    fun flowerSprite(p: Power): Sprite = when (p) {
        Power.FIRE -> Sprites.blossom1
        Power.ICE -> Sprites.iceFlower
        Power.BOOM -> Sprites.boomFlower
        Power.THUNDER -> thunderFlower
        Power.CRYSTAL -> crystalFlower
        Power.SHELL -> Sprites.blueShell
        Power.GRASS, Power.NONE -> grassFlower
    }

    /** A heart, top-left at (x, y), about [s] wide. */
    fun heart(gfx: Gfx, x: Float, y: Float, s: Float, color: Int) {
        val r = s / 2
        gfx.oval(x, y, r + 1, r + 1, color)
        gfx.oval(x + r - 1, y, r + 1, r + 1, color)
        gfx.poly(floatArrayOf(x + 0.3f, x + s - 0.3f, x + s / 2), floatArrayOf(y + r * 0.7f, y + r * 0.7f, y + s * 0.95f), color)
    }

    /** A little green shield: one point of green armor. */
    /** A shield you can craft: a rim, the shield's color and a little gem in the middle. [s] is its height. */
    fun shieldIcon(gfx: Gfx, x: Float, y: Float, s: Float, sh: Shield) {
        if (sh == Shield.NONE) return
        val w = s * 0.8f
        gfx.poly(floatArrayOf(x, x + w, x + w, x + w / 2, x), floatArrayOf(y, y, y + s * 0.6f, y + s, y + s * 0.6f), sh.rim)
        gfx.poly(
            floatArrayOf(x + 1.5f, x + w - 1.5f, x + w - 1.5f, x + w / 2, x + 1.5f),
            floatArrayOf(y + 1.5f, y + 1.5f, y + s * 0.58f, y + s - 2.2f, y + s * 0.58f), sh.color,
        )
        gfx.rect(x + w / 2 - 0.75f, y + 2f, 1.5f, s - 5f, sh.rim)
        gfx.oval(x + w / 2 - s * 0.14f, y + s * 0.3f, s * 0.28f, s * 0.28f, sh.rim)
        gfx.rect(x + 2.5f, y + 2.5f, w * 0.25f, s * 0.2f, 0x66FFFFFF)
    }

    fun shield(gfx: Gfx, x: Float, y: Float, s: Float, color: Int = 0xFF2EB84A.toInt()) {
        gfx.poly(
            floatArrayOf(x, x + s, x + s, x + s / 2, x),
            floatArrayOf(y, y, y + s * 0.55f, y + s, y + s * 0.55f),
            0xFF0E4A1A.toInt(),
        )
        gfx.poly(
            floatArrayOf(x + 1.5f, x + s - 1.5f, x + s - 1.5f, x + s / 2, x + 1.5f),
            floatArrayOf(y + 1.5f, y + 1.5f, y + s * 0.53f, y + s - 2f, y + s * 0.53f),
            color,
        )
        gfx.rect(x + s / 2 - 0.75f, y + 2f, 1.5f, s - 5f, 0x66FFFFFF)
    }

    /** A small gem/ingot icon for the inventory. */
    fun oreIcon(gfx: Gfx, o: Ore, x: Float, y: Float, s: Float) {
        when (o) {
            Ore.STONE -> {
                gfx.rect(x, y, s, s, 0xFF6E6E6E.toInt())
                gfx.rect(x + 1, y + 1, s - 2, s - 2, 0xFF9A9A9A.toInt())
                gfx.rect(x + 2, y + 2, 2f, 2f, 0xFF6E6E6E.toInt())
                gfx.rect(x + s - 4, y + s - 4, 2f, 2f, 0xFF6E6E6E.toInt())
            }
            Ore.IRON, Ore.GOLD, Ore.COPPER -> {
                // An ingot
                gfx.poly(floatArrayOf(x + 2, x + s - 2, x + s, x), floatArrayOf(y + 2, y + 2, y + s - 1, y + s - 1), o.dark)
                gfx.poly(floatArrayOf(x + 3, x + s - 3, x + s - 1.5f, x + 1.5f), floatArrayOf(y + 3, y + 3, y + s - 3, y + s - 3), o.color)
                gfx.rect(x + 4, y + 4, s / 3, 1.5f, 0xAAFFFFFF.toInt())
            }
            Ore.OBSIDIAN -> {
                // A rough black-purple chunk with a shine.
                gfx.poly(floatArrayOf(x + 1, x + s * 0.6f, x + s, x + s - 1, x + s * 0.3f), floatArrayOf(y + s * 0.4f, y, y + s * 0.3f, y + s, y + s - 0.5f), o.dark)
                gfx.poly(floatArrayOf(x + 2.5f, x + s * 0.6f, x + s - 1.5f, x + s * 0.55f), floatArrayOf(y + s * 0.42f, y + 1.5f, y + s * 0.35f, y + s * 0.6f), o.color)
                gfx.rect(x + s * 0.5f, y + 2.5f, 1.5f, 1.5f, 0xCCE0C8FF.toInt())
            }
            Ore.DIAMOND, Ore.EMERALD, Ore.AMETHYST, Ore.RUBY, Ore.SAPPHIRE -> {
                gfx.poly(
                    floatArrayOf(x + s / 2, x + s, x + s / 2, x), floatArrayOf(y, y + s / 2, y + s, y + s / 2), o.dark,
                )
                gfx.poly(
                    floatArrayOf(x + s / 2, x + s - 2, x + s / 2, x + 2), floatArrayOf(y + 2, y + s / 2, y + s - 2, y + s / 2), o.color,
                )
                gfx.rect(x + s / 2 - 1, y + 3, 2f, 3f, 0xCCFFFFFF.toInt())
            }
        }
    }

    // ---------------------------------------------------------------- Pickaxe
    fun pickColor(tier: Int) = when (tier) {
        1 -> 0xFF9A6A3A.toInt()
        2 -> 0xFFD8D8D8.toInt()
        3 -> 0xFFFFD82A.toInt()
        4 -> 0xFF7FF4F0.toInt()
        else -> 0xFFC890FF.toInt()
    }

    fun pickName(tier: Int) = when (tier) {
        1 -> "WOOD"
        2 -> "IRON"
        3 -> "GOLD"
        4 -> "DIAMOND"
        else -> "AMETHYST"
    }

    /** Draws a pickaxe held at (hx, hy), pointing at [angle] radians (0 = right, positive = down). */
    fun pickaxe(gfx: Gfx, hx: Float, hy: Float, angle: Float, tier: Int) {
        val len = 13f
        val dx = cos(angle)
        val dy = sin(angle)
        val nx = -dy
        val ny = dx
        val tx = hx + dx * len
        val ty = hy + dy * len
        val handle = 0xFF7A4A20.toInt()
        gfx.poly(
            floatArrayOf(hx + nx, tx + nx, tx - nx, hx - nx),
            floatArrayOf(hy + ny, ty + ny, ty - ny, hy - ny), handle,
        )
        // The curved head, across the handle tip
        val c = pickColor(tier)
        val hw = 7f
        gfx.poly(
            floatArrayOf(tx + nx * hw - dx * 3, tx + dx * 2, tx - nx * hw - dx * 3, tx - nx * hw * 0.6f, tx - dx * 0.5f, tx + nx * hw * 0.6f),
            floatArrayOf(ty + ny * hw - dy * 3, ty + dy * 2, ty - ny * hw - dy * 3, ty - ny * hw * 0.6f, ty - dy * 0.5f, ty + ny * hw * 0.6f),
            c,
        )
    }

    /** Tanooki ears on Rocco's cap and a striped raccoon tail (spinning during a tail attack). */
    fun tanooki(gfx: Gfx, cx: Float, top: Float, bottom: Float, facing: Int, t: Float, spinning: Boolean, low: Boolean) {
        val brown = 0xFFB0702A.toInt()
        val dark = 0xFF5A3410.toInt()
        val headY = if (low) bottom - 16 else top
        gfx.oval(cx - 7, headY - 4, 5f, 6f, brown)
        gfx.oval(cx + 2, headY - 4, 5f, 6f, brown)
        gfx.oval(cx - 6, headY - 3, 3f, 3f, 0xFFF0D0A0.toInt())
        gfx.oval(cx + 3, headY - 3, 3f, 3f, 0xFFF0D0A0.toInt())
        // Tail: wags behind him, or whips all the way around during a tail spin.
        val side = if (spinning) (if ((t * 20).toInt() % 2 == 0) 1 else -1) else -facing
        val wag = if (spinning) 0f else sin(t * 8) * 2f
        val tx = cx + side * 8
        val ty = bottom - 11 + wag
        gfx.oval(tx - 3 + side * 3, ty - 3, 10f, 7f, brown)
        gfx.rect(tx + side * 5 - 1, ty - 2.5f, 2f, 5f, dark)
        gfx.rect(tx + side * 8 - 1, ty - 2f, 2f, 4f, dark)
    }

    /** The Wing Cap: little white wings on the sides of Rocco's cap that flap when he flies. */
    fun wingCap(gfx: Gfx, cx: Float, top: Float, t: Float, flying: Boolean) {
        val flap = if (flying) sin(t * 18) * 3f else 0f
        for (side in intArrayOf(-1, 1)) {
            val bx = cx + side * 6
            gfx.poly(
                floatArrayOf(bx, bx + side * 9, bx + side * 7, bx + side * 2),
                floatArrayOf(top + 2, top - 5 - flap, top + 1, top + 4),
                0xFFFFFFFF.toInt(),
            )
            gfx.rect(bx + side * 3 - 0.5f, top - 1 - flap / 2, 1f, 3f, 0xFFC8C8D8.toInt())
        }
    }

    /** The Super Leaf that turns Rocco into Tanooki Rocco. */
    fun leaf(gfx: Gfx, x: Float, y: Float, t: Float) {
        val sway = sin(t * 3) * 2f
        gfx.poly(floatArrayOf(x + 6 + sway, x + 13, x + 6 + sway, x - 1), floatArrayOf(y, y + 6, y + 13, y + 6), 0xFFE86A1E.toInt())
        gfx.poly(floatArrayOf(x + 6 + sway, x + 10, x + 6 + sway, x + 2), floatArrayOf(y + 2, y + 6, y + 11, y + 6), 0xFF2EB84A.toInt())
        gfx.rect(x + 6 + sway, y + 3, 1f, 9f, 0xFF7A4A20.toInt())
    }

    /** The Wing Cap item: Rocco's red cap with white wings. */
    fun wingCapItem(gfx: Gfx, x: Float, y: Float, t: Float) {
        gfx.oval(x + 2, y + 3, 10f, 8f, 0xFFE52521.toInt())
        gfx.rect(x + 2, y + 8, 12f, 3f, 0xFFE52521.toInt())
        gfx.oval(x + 5, y + 5, 4f, 3f, 0xFFFFFFFF.toInt())
        wingCap(gfx, x + 7, y + 6, t, true)
    }

    /** The rare Black Flower: dark petals with a glowing purple middle. */
    fun blackFlower(gfx: Gfx, x: Float, y: Float, s: Float, t: Float) {
        val c = x + s / 2
        val m = y + s * 0.38f
        gfx.rect(c - s * 0.05f, m, s * 0.1f, s * 0.6f, 0xFF1E5A2E.toInt())
        gfx.oval(c - s * 0.3f, y + s * 0.62f, s * 0.28f, s * 0.14f, 0xFF1E5A2E.toInt())
        for (i in 0 until 6) {
            val a = i * 1.047f + t
            gfx.oval(c + cos(a) * s * 0.22f - s * 0.16f, m + sin(a) * s * 0.22f - s * 0.16f, s * 0.32f, s * 0.32f, 0xFF1A1020.toInt())
        }
        gfx.oval(c - s * 0.14f, m - s * 0.14f, s * 0.28f, s * 0.28f, 0xFFC060FF.toInt())
        gfx.oval(c - s * 0.06f, m - s * 0.06f, s * 0.12f, s * 0.12f, 0xFFFFFFFF.toInt())
    }

    /** Draws a sword held at (hx, hy) pointing at [angle] radians. */
    fun sword(gfx: Gfx, hx: Float, hy: Float, angle: Float, sw: Sword) {
        if (sw == Sword.NONE) return
        val dx = cos(angle)
        val dy = sin(angle)
        val nx = -dy
        val ny = dx
        // Handle
        val gx = hx + dx * 3
        val gy = hy + dy * 3
        gfx.poly(
            floatArrayOf(hx + nx, gx + nx, gx - nx, hx - nx), floatArrayOf(hy + ny, gy + ny, gy - ny, hy - ny),
            0xFF6A3A1A.toInt(),
        )
        // Cross guard
        gfx.poly(
            floatArrayOf(gx + nx * 4 - dx, gx + nx * 4 + dx, gx - nx * 4 + dx, gx - nx * 4 - dx),
            floatArrayOf(gy + ny * 4 - dy, gy + ny * 4 + dy, gy - ny * 4 + dy, gy - ny * 4 - dy),
            0xFF5A3A1A.toInt(),
        )
        // Blade with a pointy tip
        val len = 13f
        val tx = gx + dx * len
        val ty = gy + dy * len
        gfx.poly(
            floatArrayOf(gx + nx * 1.6f, tx + nx * 1.6f, tx + dx * 3, tx - nx * 1.6f, gx - nx * 1.6f),
            floatArrayOf(gy + ny * 1.6f, ty + ny * 1.6f, ty + dy * 3, ty - ny * 1.6f, gy - ny * 1.6f),
            sw.blade,
        )
        gfx.poly(
            floatArrayOf(gx + nx * 0.4f, tx + nx * 0.4f, tx, gx),
            floatArrayOf(gy + ny * 0.4f, ty + ny * 0.4f, ty, gy),
            0xAAFFFFFF.toInt(),
        )
    }

    // ---------------------------------------------------------------- Sparky the electric mouse
    fun sparky(gfx: Gfx, x: Float, y: Float, t: Float, facing: Int, zapping: Boolean) {
        val yellow = 0xFFFFD82A.toInt()
        val dark = 0xFFC89A10.toInt()
        val black = 0xFF101010.toInt()
        val f = facing.toFloat()
        val cx = x + 8
        val bob = abs(sin(t * 6)) * 2f
        val by = y - bob
        // Lightning-bolt tail behind him
        val tx = cx - f * 8
        gfx.poly(
            floatArrayOf(tx, tx - f * 6, tx - f * 3, tx - f * 9, tx - f * 4, tx - f * 1),
            floatArrayOf(by + 10, by + 4, by + 3, by - 4, by + 1, by + 2), dark,
        )
        gfx.poly(
            floatArrayOf(tx + f * 1, tx - f * 5, tx - f * 2, tx - f * 8, tx - f * 3, tx),
            floatArrayOf(by + 11, by + 5, by + 4, by - 3, by + 2, by + 3), yellow,
        )
        // Body and feet
        gfx.oval(cx - 6, by + 5, 12f, 11f, yellow)
        gfx.oval(cx - 6, by + 14, 5f, 3f, dark)
        gfx.oval(cx + 1, by + 14, 5f, 3f, dark)
        // Ears with black tips
        gfx.poly(floatArrayOf(cx - 5, cx - 9, cx - 2), floatArrayOf(by - 1, by - 11, by - 3), yellow)
        gfx.poly(floatArrayOf(cx - 8, cx - 9, cx - 6.5f), floatArrayOf(by - 8, by - 11, by - 8.5f), black)
        gfx.poly(floatArrayOf(cx + 5, cx + 9, cx + 2), floatArrayOf(by - 1, by - 11, by - 3), yellow)
        gfx.poly(floatArrayOf(cx + 8, cx + 9, cx + 6.5f), floatArrayOf(by - 8, by - 11, by - 8.5f), black)
        // Head
        gfx.oval(cx - 7, by - 4, 14f, 12f, yellow)
        gfx.oval(cx - 4 + f, by - 1, 3f, 3f, black)
        gfx.oval(cx + 1 + f, by - 1, 3f, 3f, black)
        gfx.oval(cx - 3.5f + f, by - 0.5f, 1f, 1f, WHITE)
        gfx.oval(cx + 1.5f + f, by - 0.5f, 1f, 1f, WHITE)
        gfx.oval(cx - 7 + f, by + 3, 4f, 3f, 0xFFF04040.toInt())
        gfx.oval(cx + 3 + f, by + 3, 4f, 3f, 0xFFF04040.toInt())
        gfx.rect(cx - 1 + f, by + 4, 2f, 1f, 0xFF7A2A10.toInt())
        if (zapping) {
            for (i in 0 until 4) {
                val a = t * 20 + i * 1.57f
                gfx.rect(cx + cos(a) * 11 - 1, by + 4 + sin(a) * 9 - 1, 3f, 3f, argb(220, 0xFFFF80))
            }
        }
    }

    // ---------------------------------------------------------------- Pokemon partners

    /** Draws a Pokemon standing with its feet at ([cx], [bottom]). */
    fun mon(gfx: Gfx, m: Mon, cx: Float, bottom: Float, t: Float, facing: Int, glow: Boolean, walking: Boolean = false) {
        if (glow) gfx.oval(cx - 14, bottom - 26, 28f, 28f, argb(70, m.color))
        when (m) {
            Mon.SPARKY -> sparky(gfx, cx - 8, bottom - 17, t, facing, glow)
            Mon.BLAZE -> blaze(gfx, cx, bottom, t, facing, walking)
            Mon.HYDRO -> hydro(gfx, cx, bottom, t, facing, walking)
            Mon.BLOOM -> bloom(gfx, cx, bottom, t, facing, walking)
            Mon.EMBER -> ember(gfx, cx, bottom, t, facing, walking)
            Mon.SPLASHY -> splashy(gfx, cx, bottom, t, facing, walking)
            Mon.MYSTIC -> mystic(gfx, cx, bottom, t, facing)
            Mon.PUNCHO -> puncho(gfx, cx, bottom, t, facing, walking)
            Mon.SPOOKY -> spooky(gfx, cx, bottom, t, facing)
            Mon.LUGIA -> lugia(gfx, cx, bottom, t, facing)
        }
    }

    /** Ember: a small orange lizard with a flame on the end of its tail. */
    private fun ember(gfx: Gfx, cx: Float, bottom: Float, t: Float, facing: Int, walking: Boolean) {
        val f = facing.toFloat()
        val orange = 0xFFFF9A4A.toInt()
        val cream = 0xFFFFE8A0.toInt()
        val step = if (walking) 1.5f else 0f
        val tx = cx - f * 10
        gfx.poly(floatArrayOf(cx - f * 2, tx, tx + f * 2), floatArrayOf(bottom - 5, bottom - 12, bottom - 3), orange)
        gfx.oval(tx - 3, bottom - 19 + sin(t * 20), 6f, 8f, 0xFFFF6A00.toInt())
        gfx.oval(tx - 1.5f, bottom - 16 + sin(t * 20), 3f, 4f, 0xFFFFE14D.toInt())
        gfx.oval(cx - 5, bottom - 4 + step, 4f, 4f, orange)
        gfx.oval(cx + 1, bottom - 4 - step, 4f, 4f, orange)
        gfx.oval(cx - 5, bottom - 13, 10f, 11f, orange)
        gfx.oval(cx - 2 + f, bottom - 10, 5f, 7f, cream)
        gfx.oval(cx - 5 + f * 3, bottom - 21, 11f, 10f, orange)
        gfx.oval(cx + f * 5 - 1, bottom - 18, 3f, 3.5f, 0xFF101010.toInt())
        gfx.oval(cx + f * 5 - 0.5f, bottom - 18, 1f, 1f, WHITE)
        gfx.rect(cx + f * 6 - 1, bottom - 14, 3f, 1f, 0xFF7A2A10.toInt())
    }

    /** Splashy: a little blue turtle with a curly tail. */
    private fun splashy(gfx: Gfx, cx: Float, bottom: Float, t: Float, facing: Int, walking: Boolean) {
        val f = facing.toFloat()
        val blue = 0xFF6AB8F0.toInt()
        val step = if (walking) 1.5f else 0f
        gfx.oval(cx - f * 11 - 3, bottom - 12, 7f, 7f, blue)
        gfx.oval(cx - f * 11 - 1, bottom - 10, 3f, 3f, 0xFF4A8AC8.toInt())
        gfx.oval(cx - 6, bottom - 4 + step, 5f, 4f, blue)
        gfx.oval(cx + 1, bottom - 4 - step, 5f, 4f, blue)
        gfx.oval(cx - 8 - f, bottom - 15, 15f, 13f, 0xFFA0602A.toInt())
        gfx.oval(cx - 4 + f * 2, bottom - 13, 9f, 10f, 0xFFF8E0A0.toInt())
        gfx.oval(cx - 4 + f * 5, bottom - 22, 10f, 10f, blue)
        gfx.oval(cx + f * 7 - 1.5f, bottom - 19, 3.5f, 4f, 0xFF101010.toInt())
        gfx.oval(cx + f * 7 - 0.5f, bottom - 19, 1.2f, 1.2f, WHITE)
        gfx.rect(cx + f * 7 - 1, bottom - 15, 3f, 1f, 0xFF2A4A7A.toInt())
        if (sin(t * 2) > 0.9f) gfx.oval(cx + f * 10, bottom - 26 - (t * 20 % 6), 3f, 3f, 0x99BFE8FF.toInt())
    }

    /** Mystic: a small pink cat with a long thin tail that floats in the air. */
    private fun mystic(gfx: Gfx, cx: Float, bottom: Float, t: Float, facing: Int) {
        val f = facing.toFloat()
        val pink = 0xFFFF9AD0.toInt()
        val hover = sin(t * 3) * 2f - 3f
        val by = bottom + hover
        gfx.oval(cx - 8, bottom - 2, 16f, 3f, 0x33000000)
        // Long curly tail
        for (i in 0 until 6) {
            val a = i * 0.5f + t * 2
            gfx.oval(cx - f * (5 + i * 2.5f) - 1.5f, by - 8 - i * 2 + sin(a) * 2, 3f, 3f, pink)
        }
        gfx.oval(cx - f * 21 - 2.5f, by - 22 + sin(t * 2) * 2, 5f, 5f, pink)
        gfx.oval(cx - 5, by - 13, 10f, 10f, pink)
        gfx.oval(cx - 4, by - 5, 3f, 4f, pink)
        gfx.oval(cx + 1, by - 5, 3f, 4f, pink)
        gfx.oval(cx - 6 + f * 2, by - 23, 12f, 11f, pink)
        gfx.poly(floatArrayOf(cx - 5 + f * 2, cx - 6 + f * 2, cx - 2 + f * 2), floatArrayOf(by - 20, by - 26, by - 22), pink)
        gfx.poly(floatArrayOf(cx + 5 + f * 2, cx + 6 + f * 2, cx + 2 + f * 2), floatArrayOf(by - 20, by - 26, by - 22), pink)
        gfx.oval(cx + f * 5 - 1.5f, by - 20, 4f, 5f, 0xFF3A6AD8.toInt())
        gfx.oval(cx + f * 5 - 0.5f, by - 19.5f, 1.5f, 1.5f, WHITE)
    }

    /** Puncho: a strong grey-blue fighter with three ridges on its head. */
    private fun puncho(gfx: Gfx, cx: Float, bottom: Float, t: Float, facing: Int, walking: Boolean) {
        val f = facing.toFloat()
        val skin = 0xFF8A9AB8.toInt()
        val dark = 0xFF5A6A88.toInt()
        val step = if (walking) 1.5f else 0f
        gfx.rect(cx - 5, bottom - 7 + step, 4f, 7f - step, skin)
        gfx.rect(cx + 1, bottom - 7 - step, 4f, 7f + step, skin)
        gfx.rect(cx - 5, bottom - 2, 4f, 2f, dark)
        gfx.rect(cx + 1, bottom - 2, 4f, 2f, dark)
        gfx.oval(cx - 7, bottom - 18, 14f, 13f, skin)
        gfx.rect(cx - 4, bottom - 9, 8f, 2f, 0xFFD8D8E8.toInt())
        // Big arms (one punching forward)
        gfx.oval(cx - f * 9 - 3, bottom - 16, 6f, 9f, skin)
        gfx.oval(cx + f * 8 - 3, bottom - 16, 7f, 6f, skin)
        gfx.oval(cx + f * 11 - 3, bottom - 17, 6f, 6f, dark)
        gfx.oval(cx - 5 + f * 1.5f, bottom - 26, 10f, 10f, skin)
        for (i in -1..1) gfx.poly(floatArrayOf(cx + i * 3 - 1.5f, cx + i * 3, cx + i * 3 + 1.5f), floatArrayOf(bottom - 25, bottom - 30, bottom - 25), dark)
        gfx.oval(cx + f * 4 - 1.5f, bottom - 23, 3f, 3f, 0xFFE52521.toInt())
        gfx.rect(cx + f * 3 - 1.5f, bottom - 19, 3f, 1f, 0xFF2A3A58.toInt())
    }

    /** Spooky: a purple ball of ghost gas with a big grin. */
    private fun spooky(gfx: Gfx, cx: Float, bottom: Float, t: Float, facing: Int) {
        val f = facing.toFloat()
        val by = bottom - 13 + sin(t * 2.5f) * 2f
        for (i in 0 until 7) {
            val a = i * 0.9f + t * 2
            gfx.oval(cx + cos(a) * 9 - 4, by + sin(a) * 8 - 4, 8f, 8f, argb(110, 0x8A4AC8))
        }
        gfx.oval(cx - 8, by - 8, 16f, 16f, 0xFF2A1A3A.toInt())
        gfx.oval(cx - 3 + f * 2, by - 5, 5f, 5f, WHITE)
        gfx.oval(cx + 2 + f * 2, by - 5, 5f, 5f, WHITE)
        gfx.oval(cx - 1.5f + f * 3, by - 3.5f, 2f, 2f, 0xFF101010.toInt())
        gfx.oval(cx + 3.5f + f * 3, by - 3.5f, 2f, 2f, 0xFF101010.toInt())
        gfx.rect(cx - 3 + f * 2, by + 2, 8f, 2.5f, 0xFFE07AB0.toInt())
        gfx.poly(floatArrayOf(cx - 2 + f * 2, cx - 1 + f * 2, cx + f * 2), floatArrayOf(by + 2, by + 4.5f, by + 2), WHITE)
        gfx.poly(floatArrayOf(cx + 3 + f * 2, cx + 4 + f * 2, cx + 5 + f * 2), floatArrayOf(by + 2, by + 4.5f, by + 2), WHITE)
    }

    /** Lugia: a big white sea bird with blue belly plates and long wings. */
    private fun lugia(gfx: Gfx, cx: Float, bottom: Float, t: Float, facing: Int) {
        val f = facing.toFloat()
        val white = 0xFFF2F6FC.toInt()
        val blue = 0xFF3A5A9A.toInt()
        val by = bottom - 14 + sin(t * 2.5f) * 1.5f
        val flap = sin(t * 6) * 4f
        // Wings (with dark blue tips like fingers)
        for (s in intArrayOf(-1, 1)) {
            gfx.poly(floatArrayOf(cx + s * 4, cx + s * 17, cx + s * 15, cx + s * 4), floatArrayOf(by - 4, by - 10 + flap, by + 2 + flap, by + 4), white)
            gfx.poly(floatArrayOf(cx + s * 15, cx + s * 19, cx + s * 17), floatArrayOf(by - 10 + flap, by - 8 + flap, by + 1 + flap), blue)
        }
        // Tail
        gfx.poly(floatArrayOf(cx - f * 4, cx - f * 13, cx - f * 11), floatArrayOf(by + 4, by + 6, by + 10), white)
        // Body with blue belly plates
        gfx.oval(cx - 7, by - 6, 14f, 18f, white)
        gfx.oval(cx - 3, by - 1, 6f, 4f, blue)
        gfx.oval(cx - 3, by + 4, 6f, 4f, blue)
        // Feet
        gfx.rect(cx - 5, bottom - 3, 3f, 3f, 0xFF8A9AB8.toInt())
        gfx.rect(cx + 2, bottom - 3, 3f, 3f, 0xFF8A9AB8.toInt())
        // Long neck and head with a blue eye mask and back spikes
        gfx.oval(cx - 3 + f * 3, by - 12, 7f, 9f, white)
        gfx.oval(cx - 5 + f * 6, by - 18, 11f, 9f, white)
        gfx.rect(cx - 3 + f * 6, by - 15, 7f, 2.5f, blue)
        gfx.oval(cx - 0.5f + f * 8, by - 15, 2f, 2f, WHITE)
        gfx.poly(floatArrayOf(cx - f * 1, cx - f * 5, cx - f * 1), floatArrayOf(by - 18, by - 20, by - 14), blue)
    }

    /** Blaze: an orange fire dragon with teal wings and a flame on its tail. */
    private fun blaze(gfx: Gfx, cx: Float, bottom: Float, t: Float, facing: Int, walking: Boolean) {
        val f = facing.toFloat()
        val orange = 0xFFFF8A3A.toInt()
        val dark = 0xFFC85A1A.toInt()
        val cream = 0xFFFFE8A0.toInt()
        val wing = 0xFF2AA7A7.toInt()
        val flap = sin(t * 8) * 3f
        val step = if (walking) 1.5f else 0f
        // Tail with flame
        val tx = cx - f * 10
        gfx.poly(floatArrayOf(cx - f * 3, tx - f * 4, tx, cx - f * 2), floatArrayOf(bottom - 6, bottom - 10, bottom - 5, bottom - 2), orange)
        gfx.oval(tx - f * 4 - 3, bottom - 17 + sin(t * 20), 6f, 8f, 0xFFFF6A00.toInt())
        gfx.oval(tx - f * 4 - 1.5f, bottom - 14 + sin(t * 20), 3f, 4f, 0xFFFFE14D.toInt())
        // Wings
        gfx.poly(floatArrayOf(cx - f * 2, cx - f * 14, cx - f * 10, cx - f * 4), floatArrayOf(bottom - 16, bottom - 26 - flap, bottom - 14, bottom - 10), dark)
        gfx.poly(floatArrayOf(cx - f * 3, cx - f * 12, cx - f * 9), floatArrayOf(bottom - 15, bottom - 23 - flap, bottom - 13), wing)
        // Legs and body
        gfx.oval(cx - 6, bottom - 5 + step, 5f, 5f, dark)
        gfx.oval(cx + 1, bottom - 5 - step, 5f, 5f, dark)
        gfx.oval(cx - 6, bottom - 17, 12f, 14f, orange)
        gfx.oval(cx - 3 + f, bottom - 14, 7f, 10f, cream)
        // Long neck and head
        gfx.oval(cx - 3 + f * 3, bottom - 23, 7f, 9f, orange)
        gfx.oval(cx - 4 + f * 6, bottom - 28, 10f, 8f, orange)
        gfx.poly(floatArrayOf(cx + f * 2, cx - f * 1, cx + f * 4), floatArrayOf(bottom - 27, bottom - 32, bottom - 28), dark)
        gfx.oval(cx + f * 6 - 0.5f, bottom - 27, 2.5f, 2.5f, 0xFF101010.toInt())
        gfx.rect(cx + f * 10 - 1, bottom - 23, 2f, 1f, 0xFF7A2A10.toInt())
    }

    /** Hydro: a big blue turtle with a brown shell and two water cannons. */
    private fun hydro(gfx: Gfx, cx: Float, bottom: Float, t: Float, facing: Int, walking: Boolean) {
        val f = facing.toFloat()
        val blue = 0xFF4A8AE8.toInt()
        val shell = 0xFF8A5A2A.toInt()
        val rim = 0xFFF0E0B0.toInt()
        val step = if (walking) 1.5f else 0f
        gfx.oval(cx - 8, bottom - 5 + step, 6f, 5f, blue)
        gfx.oval(cx + 2, bottom - 5 - step, 6f, 5f, blue)
        // Shell
        gfx.oval(cx - 10 - f * 2, bottom - 20, 18f, 17f, shell)
        gfx.oval(cx - 7 + f * 2, bottom - 17, 12f, 13f, rim)
        gfx.oval(cx - 5 + f * 3, bottom - 15, 8f, 10f, 0xFFFFF4D0.toInt())
        // Cannons
        val cxn = cx - f * 2
        gfx.rect(if (f > 0) cxn - 2 else cxn - 8, bottom - 24, 10f, 4f, 0xFF9A9AA8.toInt())
        gfx.rect(if (f > 0) cxn + 6 else cxn - 9, bottom - 25, 3f, 6f, 0xFF6A6A78.toInt())
        // Head
        gfx.oval(cx - 4 + f * 6, bottom - 24, 10f, 9f, blue)
        gfx.oval(cx + f * 8 - 1, bottom - 22, 3f, 3f, 0xFFFFFFFF.toInt())
        gfx.oval(cx + f * 8 - 0.5f, bottom - 21.5f, 1.5f, 1.5f, 0xFF101010.toInt())
        gfx.poly(floatArrayOf(cx + f * 1, cx - f * 2, cx + f * 3), floatArrayOf(bottom - 23, bottom - 27, bottom - 24), blue)
        if (sin(t * 3) > 0.95f) gfx.oval(cxn + f * 10 - 2, bottom - 26, 4f, 4f, 0xAABFE8FF.toInt())
    }

    /** Bloom: a teal-green dinosaur with a big pink flower on its back. */
    private fun bloom(gfx: Gfx, cx: Float, bottom: Float, t: Float, facing: Int, walking: Boolean) {
        val f = facing.toFloat()
        val body = 0xFF3CB8A0.toInt()
        val spot = 0xFF2A8A78.toInt()
        val pink = 0xFFFF7AA8.toInt()
        val step = if (walking) 1.5f else 0f
        gfx.oval(cx - 9, bottom - 5 + step, 6f, 5f, spot)
        gfx.oval(cx + 3, bottom - 5 - step, 6f, 5f, spot)
        gfx.oval(cx - 10, bottom - 14, 20f, 11f, body)
        gfx.oval(cx - 6, bottom - 12, 4f, 3f, spot)
        gfx.oval(cx + 1, bottom - 9, 3f, 3f, spot)
        // Leaves and flower on the back
        gfx.poly(floatArrayOf(cx - f * 2, cx - f * 16, cx - f * 6), floatArrayOf(bottom - 14, bottom - 12, bottom - 9), 0xFF1E7A2E.toInt())
        gfx.poly(floatArrayOf(cx - f * 2, cx + f * 10, cx + f * 2), floatArrayOf(bottom - 14, bottom - 13, bottom - 9), 0xFF1E7A2E.toInt())
        val pulse = 1f + sin(t * 2) * 0.5f
        for (i in 0 until 5) {
            val a = i * 1.2566f + t * 0.3f
            gfx.oval(cx - f * 2 + cos(a) * 5 - 4 - pulse / 2, bottom - 22 + sin(a) * 3 - 3, 8f + pulse, 6f, pink)
        }
        gfx.oval(cx - f * 2 - 3, bottom - 24, 6f, 5f, 0xFFFFE14D.toInt())
        // Head
        gfx.oval(cx + f * 6 - 5, bottom - 17, 10f, 9f, body)
        gfx.oval(cx + f * 9 - 1.5f, bottom - 15, 3f, 3f, 0xFFFFFFFF.toInt())
        gfx.oval(cx + f * 9 - 1, bottom - 14.5f, 1.5f, 1.5f, 0xFFE52521.toInt())
        gfx.rect(cx + f * 8 - 2, bottom - 11, 4f, 1f, 0xFF1A5A4A.toInt())
    }

    // ---------------------------------------------------------------- New blocks

    /** Pale yellow end stone for THE END. */
    val endStone: Sprite = Sprites.sandstone.recolor(mapOf()).let { base ->
        Sprite(base.w, base.h, IntArray(base.pixels.size) { i ->
            val p = base.pixels[i]
            if (p ushr 24 == 0) p else {
                val l = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                argb(255, ((l * 1.05f).toInt().coerceAtMost(255) shl 16) or ((l * 1.02f).toInt().coerceAtMost(255) shl 8) or (l * 0.75f).toInt())
            }
        })
    }

    /** Recolors a sprite by brightness into shades of one color. */
    private fun tint(base: Sprite, r: Float, g: Float, b: Float): Sprite = Sprite(base.w, base.h, IntArray(base.pixels.size) { i ->
        val p = base.pixels[i]
        if (p ushr 24 == 0) p else {
            val l = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3f
            argb(255, ((l * r).toInt().coerceIn(0, 255) shl 16) or ((l * g).toInt().coerceIn(0, 255) shl 8) or (l * b).toInt().coerceIn(0, 255))
        }
    })

    /** Dark purple-black obsidian. */
    val obsidian: Sprite = tint(Sprites.hard, 0.35f, 0.22f, 0.5f)

    /** Dark teal stone for the Deep Dark. */
    val deepStone: Sprite = tint(Sprites.stone, 0.18f, 0.32f, 0.38f)

    /** A bubble vent: blue stone with a hole that puffs air. */
    val vent: Sprite = tint(Sprites.stone, 0.4f, 0.6f, 0.9f)

    // ---------------------------------------------------------------- Traps, rails and Poseidon

    /** A row of metal spikes sitting on the floor at [bottom]. */
    fun spikes(gfx: Gfx, x: Float, bottom: Float, color: Int = 0xFFB8B8C8.toInt()) {
        gfx.rect(x, bottom - 2, 16f, 2f, 0xFF5A5A68.toInt())
        for (i in 0 until 4) {
            val sx = x + i * 4f
            gfx.poly(floatArrayOf(sx, sx + 2, sx + 4), floatArrayOf(bottom - 2, bottom - 9, bottom - 2), color)
            gfx.poly(floatArrayOf(sx + 1.5f, sx + 2, sx + 2.5f), floatArrayOf(bottom - 4, bottom - 9, bottom - 4), WHITE)
        }
    }

    /** Minecart rails across one tile whose bottom is [bottom]. */
    fun rail(gfx: Gfx, x: Float, bottom: Float) {
        for (i in 0 until 3) gfx.rect(x + 1 + i * 5.5f, bottom - 4, 3f, 4f, 0xFF7A4A20.toInt())
        gfx.rect(x, bottom - 4, 16f, 1.5f, 0xFF9A9AA8.toInt())
    }

    /** Poseidon rising from the waves with his golden trident. [cx] is his middle, [baseY] the water line. */
    fun poseidon(gfx: Gfx, cx: Float, baseY: Float, t: Float) {
        val skin = 0xFFE8C8A0.toInt()
        val beard = 0xFFF0F0F8.toInt()
        val gold = 0xFFFFD21F.toInt()
        val sea = 0xFF2E8AC8.toInt()
        // Body rising out of the water
        gfx.oval(cx - 26, baseY - 70, 52f, 80f, sea)
        gfx.oval(cx - 18, baseY - 64, 36f, 44f, skin)
        // Beard and hair
        gfx.oval(cx - 22, baseY - 112, 44f, 40f, beard)
        gfx.oval(cx - 16, baseY - 108, 32f, 30f, skin)
        gfx.oval(cx - 20, baseY - 96, 40f, 34f, beard)
        gfx.rect(cx - 9, baseY - 98, 6f, 4f, 0xFF2A5A8A.toInt())
        gfx.rect(cx + 3, baseY - 98, 6f, 4f, 0xFF2A5A8A.toInt())
        gfx.rect(cx - 6, baseY - 86, 12f, 2f, 0xFFA05040.toInt())
        // Crown
        for (i in 0 until 5) gfx.poly(floatArrayOf(cx - 18 + i * 8, cx - 14 + i * 8, cx - 10 + i * 8), floatArrayOf(baseY - 110, baseY - 124, baseY - 110), gold)
        gfx.rect(cx - 18, baseY - 112, 36f, 5f, gold)
        // Arm holding the trident up high
        val tx = cx + 34
        gfx.oval(cx + 12, baseY - 70, 12f, 22f, skin)
        gfx.rect(tx - 1.5f, baseY - 150, 3f, 110f, gold)
        for (i in -1..1) gfx.poly(floatArrayOf(tx + i * 9 - 2, tx + i * 9, tx + i * 9 + 2), floatArrayOf(baseY - 145, baseY - 162, baseY - 145), gold)
        gfx.rect(tx - 11, baseY - 147, 22f, 3f, gold)
        if ((t * 6).toInt() % 2 == 0) gfx.oval(tx - 6, baseY - 168, 12f, 12f, 0x88FFFFFF.toInt())
    }

    // ---------------------------------------------------------------- House and bed

    /** A cozy stone house, [x] is the left edge and [bottom] is the floor. */
    fun house(gfx: Gfx, x: Float, bottom: Float, t: Float) {
        val top = bottom - 40
        gfx.rect(x, top, 72f, 40f, 0xFF7C7C7C.toInt())
        for (row in 0 until 5) {
            val off = if (row % 2 == 0) 0f else 9f
            gfx.rect(x, top + row * 8f, 72f, 1f, 0xFF5A5A5A.toInt())
            var bx = x + off
            while (bx < x + 72) { gfx.rect(bx, top + row * 8f, 1f, 8f, 0xFF5A5A5A.toInt()); bx += 18f }
        }
        // Roof and chimney with smoke
        gfx.rect(x + 54, top - 22, 8f, 14f, 0xFF8A3A2A.toInt())
        gfx.poly(floatArrayOf(x - 6, x + 36, x + 78), floatArrayOf(top, top - 18, top), 0xFFB04A2A.toInt())
        gfx.poly(floatArrayOf(x, x + 36, x + 72), floatArrayOf(top, top - 14, top), 0xFFD8603A.toInt())
        for (i in 0 until 3) {
            val p = (t * 0.6f + i / 3f) % 1f
            gfx.oval(x + 56 + sin(p * 6) * 3, top - 26 - p * 22, 6f + p * 6, 6f + p * 6, argb(((1 - p) * 150).toInt(), 0xDDDDDD))
        }
        // Door and windows
        gfx.rect(x + 6, bottom - 26, 14f, 26f, 0xFF7A4A20.toInt())
        gfx.oval(x + 16, bottom - 14, 2.5f, 2.5f, 0xFFFFD21F.toInt())
        gfx.rect(x + 28, top + 8, 14f, 12f, 0xFF5A3A1A.toInt())
        gfx.rect(x + 29.5f, top + 9.5f, 11f, 9f, 0xFFFFE8A0.toInt())
        gfx.rect(x + 34.5f, top + 9.5f, 1f, 9f, 0xFF5A3A1A.toInt())
    }

    /** A red bed with a white pillow. */
    fun bed(gfx: Gfx, x: Float, bottom: Float) {
        gfx.rect(x, bottom - 10, 3f, 10f, 0xFF7A4A20.toInt())
        gfx.rect(x + 23, bottom - 7, 3f, 7f, 0xFF7A4A20.toInt())
        gfx.rect(x, bottom - 6, 26f, 4f, 0xFF7A4A20.toInt())
        gfx.rect(x + 3, bottom - 9, 20f, 4f, 0xFFE52521.toInt())
        gfx.rect(x + 3, bottom - 9, 6f, 3f, 0xFFF4F4F4.toInt())
    }

    // ---------------------------------------------------------------- Ring-Ding Frog on his scooter
    /** Ring-Ding Frog's skin colors: (body, belly). He shows up in a different one each time! */
    val frogColors = listOf(
        0xFF4CC83A.toInt() to 0xFFB8F0A0.toInt(),
        0xFF3A8AE8.toInt() to 0xFFB8DCFF.toInt(),
        0xFFE84A9A.toInt() to 0xFFFFC8E4.toInt(),
        0xFFFF8A2A.toInt() to 0xFFFFE0B0.toInt(),
        0xFF9A4AE8.toInt() to 0xFFE0C8FF.toInt(),
        0xFFE8D02A.toInt() to 0xFFFFF4B0.toInt(),
        0xFFE83A3A.toInt() to 0xFFFFC0C0.toInt(),
        0xFF2AC8C8.toInt() to 0xFFB8F4F4.toInt(),
    )

    fun frog(gfx: Gfx, px: Float, y: Float, t: Float, colors: Pair<Int, Int> = frogColors[0], rainbowSkin: Boolean = false) {
        val skin = if (rainbowSkin) argb(255, com.roccoquest.game.rainbow(t * 0.5f)) else colors.first
        val belly = colors.second
        gfx.rect(px - 2, y + 20, 26f, 3f, 0xFF808090.toInt())
        gfx.rect(px + 20, y + 4, 2f, 17f, 0xFF808090.toInt())
        gfx.rect(px + 17, y + 3, 8f, 2f, 0xFF404050.toInt())
        gfx.oval(px - 2, y + 20, 6f, 6f, 0xFF202020.toInt())
        gfx.oval(px + 18, y + 20, 6f, 6f, 0xFF202020.toInt())
        gfx.oval(px + 2, y + 6, 16f, 15f, skin)
        gfx.oval(px + 5, y + 11, 10f, 8f, belly)
        gfx.oval(px + 2, y - 1, 8f, 9f, skin)
        gfx.oval(px + 10, y - 1, 8f, 9f, skin)
        gfx.oval(px + 3, y, 6f, 7f, WHITE)
        gfx.oval(px + 11, y, 6f, 7f, WHITE)
        val look = if ((t * 4).toInt() % 2 == 0) 1f else 0f
        gfx.oval(px + 5 + look, y + 2, 3f, 3f, 0xFF101010.toInt())
        gfx.oval(px + 13 + look, y + 2, 3f, 3f, 0xFF101010.toInt())
        // Mouth wide open: he's singing!
        val open = abs(sin(t * 12)) * 3f
        gfx.oval(px + 6, y + 8, 8f, 2f + open, 0xFF8A1010.toInt())
        gfx.rect(px + 14, y + 8, 6f, 2f, skin)
        for (i in 0 until 3) {
            val nx = px + 6 + i * 8f + sin(t * 3 + i) * 3f
            val ny = y - 12 - ((t * 20 + i * 9) % 22)
            gfx.text("♪", nx, ny, 9f, argb(220, com.roccoquest.game.rainbow(t, i / 3f)), 1)
        }
    }
}
