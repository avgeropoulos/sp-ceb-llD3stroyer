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

    /** frame: 0 stand, 1 walk, 2 jump */
    fun rocco(p: Power, frame: Int): Sprite = when (p) {
        Power.NONE -> arrayOf(Sprites.bigStand, Sprites.bigWalk, Sprites.bigJump)
        Power.FIRE -> arrayOf(Sprites.fireStand, Sprites.fireWalk, Sprites.fireJump)
        Power.ICE -> arrayOf(Sprites.iceStand, Sprites.iceWalk, Sprites.iceJump)
        Power.BOOM -> arrayOf(Sprites.boomStand, Sprites.boomWalk, Sprites.boomJump)
        Power.GRASS -> arrayOf(grassStand, grassWalk, grassJump)
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

    fun flowerSprite(p: Power): Sprite = when (p) {
        Power.FIRE -> Sprites.blossom1
        Power.ICE -> Sprites.iceFlower
        Power.BOOM -> Sprites.boomFlower
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
            Ore.IRON, Ore.GOLD -> {
                // An ingot
                gfx.poly(floatArrayOf(x + 2, x + s - 2, x + s, x), floatArrayOf(y + 2, y + 2, y + s - 1, y + s - 1), o.dark)
                gfx.poly(floatArrayOf(x + 3, x + s - 3, x + s - 1.5f, x + 1.5f), floatArrayOf(y + 3, y + 3, y + s - 3, y + s - 3), o.color)
                gfx.rect(x + 4, y + 4, s / 3, 1.5f, 0xAAFFFFFF.toInt())
            }
            Ore.DIAMOND, Ore.EMERALD -> {
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
        else -> 0xFF7FF4F0.toInt()
    }

    fun pickName(tier: Int) = when (tier) {
        1 -> "WOOD"
        2 -> "IRON"
        3 -> "GOLD"
        else -> "DIAMOND"
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

    // ---------------------------------------------------------------- Ring-Ding Frog on his scooter
    fun frog(gfx: Gfx, px: Float, y: Float, t: Float) {
        gfx.rect(px - 2, y + 20, 26f, 3f, 0xFF808090.toInt())
        gfx.rect(px + 20, y + 4, 2f, 17f, 0xFF808090.toInt())
        gfx.rect(px + 17, y + 3, 8f, 2f, 0xFF404050.toInt())
        gfx.oval(px - 2, y + 20, 6f, 6f, 0xFF202020.toInt())
        gfx.oval(px + 18, y + 20, 6f, 6f, 0xFF202020.toInt())
        gfx.oval(px + 2, y + 6, 16f, 15f, 0xFF4CC83A.toInt())
        gfx.oval(px + 5, y + 11, 10f, 8f, 0xFFB8F0A0.toInt())
        gfx.oval(px + 2, y - 1, 8f, 9f, 0xFF4CC83A.toInt())
        gfx.oval(px + 10, y - 1, 8f, 9f, 0xFF4CC83A.toInt())
        gfx.oval(px + 3, y, 6f, 7f, WHITE)
        gfx.oval(px + 11, y, 6f, 7f, WHITE)
        val look = if ((t * 4).toInt() % 2 == 0) 1f else 0f
        gfx.oval(px + 5 + look, y + 2, 3f, 3f, 0xFF101010.toInt())
        gfx.oval(px + 13 + look, y + 2, 3f, 3f, 0xFF101010.toInt())
        // Mouth wide open: he's singing!
        val open = abs(sin(t * 12)) * 3f
        gfx.oval(px + 6, y + 8, 8f, 2f + open, 0xFF8A1010.toInt())
        gfx.rect(px + 14, y + 8, 6f, 2f, 0xFF4CC83A.toInt())
        for (i in 0 until 3) {
            val nx = px + 6 + i * 8f + sin(t * 3 + i) * 3f
            val ny = y - 12 - ((t * 20 + i * 9) % 22)
            gfx.text("♪", nx, ny, 9f, argb(220, com.roccoquest.game.rainbow(t, i / 3f)), 1)
        }
    }
}
