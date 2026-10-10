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
            Ore.IRON, Ore.GOLD, Ore.COPPER -> {
                // An ingot
                gfx.poly(floatArrayOf(x + 2, x + s - 2, x + s, x), floatArrayOf(y + 2, y + 2, y + s - 1, y + s - 1), o.dark)
                gfx.poly(floatArrayOf(x + 3, x + s - 3, x + s - 1.5f, x + 1.5f), floatArrayOf(y + 3, y + 3, y + s - 3, y + s - 3), o.color)
                gfx.rect(x + 4, y + 4, s / 3, 1.5f, 0xAAFFFFFF.toInt())
            }
            Ore.DIAMOND, Ore.EMERALD, Ore.AMETHYST -> {
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
        }
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
