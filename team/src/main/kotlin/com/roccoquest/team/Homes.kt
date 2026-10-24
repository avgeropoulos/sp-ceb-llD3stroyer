package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.argb
import com.roccoquest.game.rainbow
import com.roccoquest.game.shadowText
import kotlin.math.sin

/** The kinds of house the team can build. They all have the same size and the door on the left. */
enum class HouseType(val label: String, val costs: List<Pair<Ore, Int>>, val coins: Int, val note: String) {
    COTTAGE("COTTAGE", listOf(Ore.STONE to 10, Ore.IRON to 2), 0, "A cozy stone house"),
    CABIN("LOG CABIN", listOf(Ore.STONE to 6, Ore.COPPER to 5), 15, "Warm wooden logs"),
    IGLOO("IGLOO", listOf(Ore.STONE to 8, Ore.SAPPHIRE to 2), 20, "A round house made of ice"),
    MUSHROOM("MUSHROOM HOUSE", listOf(Ore.EMERALD to 4, Ore.GOLD to 3), 30, "Live inside a giant mushroom!"),
    CASTLE("MINI CASTLE", listOf(Ore.STONE to 20, Ore.GOLD to 4, Ore.OBSIDIAN to 3), 60, "Towers and flags. Fancy!"),
    TREEHOUSE("TREEHOUSE", listOf(Ore.STONE to 4, Ore.EMERALD to 3), 25, "Up in a big tree!"),
    CANDY("CANDY HOUSE", listOf(Ore.GOLD to 3, Ore.RUBY to 2), 35, "Walls of cookie, roof of icing"),
    BEACH("BEACH HUT", listOf(Ore.STONE to 6, Ore.SAPPHIRE to 1), 20, "Sand, palm leaves and sunshine"),
    SPACE("SPACE DOME", listOf(Ore.IRON to 8, Ore.DIAMOND to 2), 50, "A shiny dome with a rocket"),
    CRYSTAL("CRYSTAL PALACE", listOf(Ore.AMETHYST to 4, Ore.DIAMOND to 3), 80, "Sparkly purple crystal towers"),
}

/**
 * Furniture to decorate the inside of the house. Each piece has its own spot on its [floor].
 * Fancier pieces unlock after beating [unlock] levels.
 */
enum class Furniture(val label: String, val costs: List<Pair<Ore, Int>>, val coins: Int, val floor: Int = 0, val unlock: Int = 0) {
    RUG("RUG", emptyList(), 10),
    LAMP("LAMP", listOf(Ore.GOLD to 1), 10),
    TABLE("TABLE", listOf(Ore.STONE to 4), 12),
    CHAIRS("CHAIRS", listOf(Ore.STONE to 4), 12),
    COUCH("COUCH", listOf(Ore.EMERALD to 1), 30),
    BOOKSHELF("BOOKSHELF", listOf(Ore.STONE to 6), 20),
    PLANT("PLANT", listOf(Ore.EMERALD to 1), 10),
    TV("TV", listOf(Ore.IRON to 3, Ore.COPPER to 2), 40),
    FIREPLACE("FIREPLACE", listOf(Ore.STONE to 10), 25),
    PAINTING("PAINTING", listOf(Ore.GOLD to 1), 25),
    FISH_TANK("FISH TANK", listOf(Ore.DIAMOND to 1), 30),
    CLAW_MACHINE("CLAW MACHINE", listOf(Ore.IRON to 4, Ore.GOLD to 2), 50),
    // Floor 2: the playroom
    TOY_CHEST("TOY CHEST", listOf(Ore.STONE to 4), 15, 1, 0),
    BUNK_BEDS("BUNK BEDS", listOf(Ore.IRON to 2), 30, 1, 5),
    ARCADE("ARCADE GAME", listOf(Ore.IRON to 3, Ore.COPPER to 2), 50, 1, 10),
    TRAMPOLINE("TRAMPOLINE", listOf(Ore.IRON to 2), 40, 1, 15),
    BALL_PIT("BALL PIT", emptyList(), 40, 1, 20),
    TOY_TRAIN("TOY TRAIN", listOf(Ore.IRON to 2), 35, 1, 25),
    // Floor 3: the top floor
    TELESCOPE("TELESCOPE", listOf(Ore.GOLD to 1, Ore.DIAMOND to 1), 40, 2, 30),
    PIANO("PIANO", listOf(Ore.STONE to 4), 50, 2, 35),
    TROPHY_CASE("TROPHY CASE", listOf(Ore.GOLD to 3), 60, 2, 40),
    HOT_TUB("HOT TUB", listOf(Ore.SAPPHIRE to 2), 70, 2, 45),
    DISCO_BALL("DISCO BALL", listOf(Ore.DIAMOND to 1), 50, 2, 50),
    POOL_TABLE("POOL TABLE", listOf(Ore.EMERALD to 2), 60, 2, 60),
}

/** Beds for the house. Sleep in one to save the game. */
enum class BedStyle(val label: String, val coins: Int, val blanket: Int, val pillow: Int) {
    RED("RED BED", 20, 0xFFE52521.toInt(), 0xFFFFFFFF.toInt()),
    BLUE("BLUE BED", 20, 0xFF2E6BE6.toInt(), 0xFFDDEEFF.toInt()),
}

object Homes {
    /** Paint colors for the house (0 = its normal look). */
    val PAINTS = listOf(
        0, 0xFFE84A4A.toInt(), 0xFFF08A3A.toInt(), 0xFFF4D84A.toInt(), 0xFF5AC86A.toInt(),
        0xFF5A9AF0.toInt(), 0xFFAA7AE8.toInt(), 0xFFFF9AC8.toInt(), 0xFFF4F4F4.toInt(), 0xFF3A3A44.toInt(),
    )
    val PAINT_NAMES = listOf("NORMAL", "RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE", "PINK", "WHITE", "BLACK")

    /** Where the Pokemon's play mat is in the room. */
    const val MON_X = 192f
    /** How tall each extra floor looks from outside. */
    const val STORY_H = 26f
    /** Houses can have up to 3 floors. */
    const val MAX_FLOORS = 3
    /** Where the stairs are (at the right end of the room). */
    const val STAIRS_X = 690f

    /** The room is wider than the screen: walk around and the view follows you. */
    const val ROOM_W = 720f
    const val FLOOR_Y = 176f

    // ------------------------------------------------------------------ Outside

    fun drawHouse(gfx: Gfx, type: HouseType, x: Float, bottom: Float, t: Float, paint: Int = 0, floors: Int = 1) {
        // Extra floors stack under the house, each with two windows.
        val lift = (floors - 1) * STORY_H
        for (k in 0 until floors - 1) {
            val sy = bottom - (k + 1) * STORY_H
            gfx.rect(x, sy, 72f, STORY_H, 0xFFD8C8A8.toInt())
            gfx.rect(x, sy, 72f, 2f, 0xFFA89878.toInt())
            for (wx in listOf(x + 28, x + 50)) {
                gfx.rect(wx, sy + 6, 14f, 12f, 0xFF5A3A1A.toInt())
                gfx.rect(wx + 1.5f, sy + 7.5f, 11f, 9f, 0xFFFFE8A0.toInt())
            }
            if (paint != 0) gfx.rect(x, sy, 72f, STORY_H, argb(120, paint and 0xFFFFFF))
        }
        val top = bottom - lift - 40
        drawHouseBody(gfx, type, x, bottom - lift, t)
        // Painted: a coat of the chosen color over the walls.
        if (paint != 0) {
            if (type == HouseType.IGLOO || type == HouseType.SPACE) gfx.oval(x - 4, bottom - lift - 56, 80f, 112f, argb(110, paint and 0xFFFFFF))
            else gfx.rect(x, top + (if (type == HouseType.TREEHOUSE) -14f else 0f), 72f, if (type == HouseType.TREEHOUSE) 30f else 40f, argb(120, paint and 0xFFFFFF))
        }
        // Every house has its door in the same spot, on the left.
        if (type != HouseType.IGLOO || floors > 1) {
            gfx.rect(x + 6, bottom - 26, 14f, 26f, if (type == HouseType.CASTLE) 0xFF5A3A1A.toInt() else 0xFF7A4A20.toInt())
            gfx.oval(x + 16, bottom - 14, 2.5f, 2.5f, 0xFFFFD21F.toInt())
        } else {
            gfx.oval(x + 6, bottom - 24, 14f, 26f, 0xFF4A6A8A.toInt())
        }
    }

    private fun drawHouseBody(gfx: Gfx, type: HouseType, x: Float, bottom: Float, t: Float) {
        val top = bottom - 40
        when (type) {
            HouseType.COTTAGE -> TeamArt.house(gfx, x, bottom, t)
            HouseType.TREEHOUSE -> {
                gfx.rect(x + 28, top - 20, 16f, 60f, 0xFF7A4A20.toInt())
                gfx.oval(x - 14, top - 62, 100f, 52f, 0xFF2E9A3A.toInt())
                gfx.oval(x + 4, top - 72, 64f, 36f, 0xFF3CB84A.toInt())
                gfx.rect(x, top - 14, 72f, 30f, 0xFFB07A3A.toInt())
                for (i in 0 until 4) gfx.rect(x, top - 14 + i * 7.5f, 72f, 1f, 0xFF8A5A2A.toInt())
                gfx.rect(x + 44, top - 6, 12f, 10f, 0xFFFFE8A0.toInt())
                // A rope ladder down to the door
                gfx.rect(x + 12, top + 16, 1f, 24f, 0xFFC8A060.toInt()); gfx.rect(x + 22, top + 16, 1f, 24f, 0xFFC8A060.toInt())
                for (i in 0 until 4) gfx.rect(x + 12, top + 20 + i * 5f, 11f, 1f, 0xFFC8A060.toInt())
            }
            HouseType.CANDY -> {
                gfx.rect(x, top, 72f, 40f, 0xFFC8884A.toInt())
                for (i in 0 until 6) gfx.oval(x + 4 + i * 11f, top + 26, 7f, 7f, if (i % 2 == 0) 0xFFFF6AA8.toInt() else 0xFF6AD8FF.toInt())
                gfx.poly(floatArrayOf(x - 8, x + 36, x + 80), floatArrayOf(top + 2, top - 24, top + 2), 0xFFFFF4F8.toInt())
                for (i in 0 until 8) gfx.oval(x - 6 + i * 11f, top - 2, 8f, 8f, 0xFFFFF4F8.toInt())
                for (i in 0 until 5) gfx.rect(x + 8 + i * 13f, top - 10 + (i % 2) * 6f, 3f, 2f, solidRainbow(i * 0.2f, 0f))
                gfx.rect(x + 50, top + 6, 3f, 34f, 0xFFFFFFFF.toInt()); gfx.oval(x + 45, top - 2, 13f, 13f, 0xFFE52521.toInt())
            }
            HouseType.BEACH -> {
                gfx.rect(x, top + 4, 72f, 36f, 0xFFF4DCA0.toInt())
                for (i in 0 until 9) gfx.rect(x + i * 8f, top + 4, 1f, 36f, 0xFFD8B878.toInt())
                gfx.poly(floatArrayOf(x - 10, x + 36, x + 82), floatArrayOf(top + 8, top - 18, top + 8), 0xFF7AB83A.toInt())
                for (i in 0 until 9) gfx.poly(floatArrayOf(x - 8 + i * 10f, x - 3 + i * 10f, x + 2 + i * 10f), floatArrayOf(top + 6, top + 13, top + 6), 0xFF5A9A2A.toInt())
                gfx.oval(x + 74, bottom - 8, 16f, 8f, 0xFFF4E0A8.toInt())
                gfx.rect(x + 44, top + 14, 14f, 10f, 0xFF7FC8FF.toInt())
            }
            HouseType.SPACE -> {
                val n = 16
                val xs = FloatArray(n + 1) { i -> x + 36 + kotlin.math.cos(Math.PI * i / n).toFloat() * 38f }
                val ys = FloatArray(n + 1) { i -> bottom - kotlin.math.sin(Math.PI * i / n).toFloat() * 46f }
                gfx.poly(xs, ys, 0xFFC8D4E4.toInt())
                gfx.oval(x + 30, bottom - 40, 20f, 14f, 0xAA7FC8FF.toInt())
                gfx.rect(x - 2, bottom - 4, 76f, 4f, 0xFF8A94A4.toInt())
                // A little rocket next to it, ready to blast off
                gfx.rect(x + 72, bottom - 30, 8f, 26f, 0xFFF4F4F4.toInt())
                gfx.poly(floatArrayOf(x + 72, x + 76, x + 80), floatArrayOf(bottom - 30, bottom - 40, bottom - 30), 0xFFE52521.toInt())
                gfx.oval(x + 73, bottom - 4 + sin(t * 20), 6f, 6f, 0xFFFF8A2A.toInt())
                if ((t * 2).toInt() % 2 == 0) gfx.oval(x + 35, bottom - 52, 3f, 3f, 0xFFFF4A4A.toInt())
            }
            HouseType.CRYSTAL -> {
                gfx.rect(x, top, 72f, 40f, 0xFFB88AF0.toInt())
                for (tx in listOf(x - 4, x + 26, x + 58)) {
                    val h = if (tx == x + 26) 40f else 28f
                    gfx.poly(floatArrayOf(tx, tx + 9, tx + 18), floatArrayOf(top + 4, top - h, top + 4), 0xFFC890FF.toInt())
                    gfx.poly(floatArrayOf(tx + 9, tx + 13, tx + 9), floatArrayOf(top - h, top + 2, top + 4), 0x66FFFFFF)
                }
                for (i in 0 until 3) gfx.oval(x + 10 + i * 22f + sin(t * 3 + i) * 2, top + 10, 3f, 3f, 0xCCFFFFFF.toInt())
            }
            HouseType.CABIN -> {
                for (i in 0 until 5) {
                    gfx.rect(x, top + i * 8f, 72f, 8f, if (i % 2 == 0) 0xFF8A5A2A.toInt() else 0xFF7A4A20.toInt())
                    gfx.oval(x - 3, top + i * 8f, 7f, 8f, 0xFFC89A5A.toInt())
                    gfx.oval(x + 68, top + i * 8f, 7f, 8f, 0xFFC89A5A.toInt())
                }
                gfx.poly(floatArrayOf(x - 8, x + 36, x + 80), floatArrayOf(top + 2, top - 20, top + 2), 0xFF4A2A10.toInt())
                gfx.poly(floatArrayOf(x - 2, x + 36, x + 74), floatArrayOf(top, top - 15, top), 0xFF6A3A1A.toInt())
                smoke(gfx, x + 56, top - 20, t)
                gfx.rect(x + 54, top - 18, 7f, 12f, 0xFF5A5A5A.toInt())
            }
            HouseType.IGLOO -> {
                // A half dome of ice.
                val n = 16
                val xs = FloatArray(n + 1) { i -> x + 36 + kotlin.math.cos(Math.PI * i / n).toFloat() * 40f }
                val ys = FloatArray(n + 1) { i -> bottom - kotlin.math.sin(Math.PI * i / n).toFloat() * 58f }
                gfx.poly(xs, ys, 0xFFDDEEFF.toInt())
                for (row in 1 until 5) {
                    val h = row * 11f
                    val half = 40f * kotlin.math.sqrt(1f - (h / 58f) * (h / 58f))
                    gfx.rect(x + 36 - half, bottom - h, half * 2, 1f, 0xFFA8C8E8.toInt())
                }
                // The tunnel door
                gfx.oval(x + 2, bottom - 30, 22f, 34f, 0xFFC8DDF2.toInt())
            }
            HouseType.MUSHROOM -> {
                gfx.rect(x + 2, top + 4, 60f, 36f, 0xFFF4E8D0.toInt())
                gfx.oval(x - 8, top - 30, 88f, 46f, 0xFFE52521.toInt())
                for ((dx, dy, r) in listOf(Triple(6f, -22f, 10f), Triple(30f, -28f, 12f), Triple(56f, -20f, 9f), Triple(18f, -12f, 7f), Triple(46f, -10f, 7f))) {
                    gfx.oval(x + dx, top + dy, r, r, 0xFFFFFFFF.toInt())
                }
                gfx.oval(x + 40, top + 12, 12f, 12f, 0xFF7A4A20.toInt())
                gfx.oval(x + 41.5f, top + 13.5f, 9f, 9f, 0xFFFFE8A0.toInt())
            }
            HouseType.CASTLE -> {
                gfx.rect(x, top - 4, 72f, 44f, 0xFF8A8A9A.toInt())
                for (i in 0 until 4) gfx.rect(x + 16 + i * 12f, top - 10, 7f, 7f, 0xFF8A8A9A.toInt())
                for (tx in listOf(x - 6, x + 58)) {
                    gfx.rect(tx, top - 26, 20f, 66f, 0xFF7A7A8A.toInt())
                    gfx.poly(floatArrayOf(tx - 3, tx + 10, tx + 23), floatArrayOf(top - 26, top - 44, top - 26), 0xFF3A5AC8.toInt())
                    gfx.rect(tx + 9, top - 58, 1.5f, 15f, 0xFF3A3A3A.toInt())
                    gfx.poly(floatArrayOf(tx + 10.5f, tx + 20 + sin(t * 4) * 2, tx + 10.5f), floatArrayOf(top - 58, top - 54, top - 50), 0xFFE52521.toInt())
                    gfx.rect(tx + 7, top - 14, 6f, 9f, 0xFF2A2A3A.toInt())
                }
                for (row in 0 until 5) gfx.rect(x, top - 4 + row * 9f, 72f, 1f, 0xFF6A6A7A.toInt())
                gfx.rect(x + 34, top + 4, 10f, 12f, 0xFFFFE8A0.toInt())
            }
        }
    }

    private fun smoke(gfx: Gfx, x: Float, y: Float, t: Float) {
        for (i in 0 until 3) {
            val p = (t * 0.6f + i / 3f) % 1f
            gfx.oval(x + sin(p * 6) * 3, y - p * 22, 6f + p * 6, 6f + p * 6, argb(((1 - p) * 150).toInt(), 0xDDDDDD))
        }
    }

    // ------------------------------------------------------------------ Inside

    /** Wallpaper and floor for each kind of house, scrolled by [ox]. */
    fun drawRoom(gfx: Gfx, type: HouseType, ox: Float, viewW: Float, viewH: Float, paint: Int = 0) {
        val (normalWall, normalStripe) = when (type) {
            HouseType.COTTAGE -> 0xFFF2E2C0.toInt() to 0xFFF0D0C8.toInt()
            HouseType.CABIN -> 0xFF9A6A3A.toInt() to 0xFF7A4A20.toInt()
            HouseType.IGLOO -> 0xFFDDEEFF.toInt() to 0xFFB8D4F0.toInt()
            HouseType.MUSHROOM -> 0xFFFFC8D8.toInt() to 0xFFFFFFFF.toInt()
            HouseType.CASTLE -> 0xFF8A8A9A.toInt() to 0xFF6A6A7A.toInt()
            HouseType.TREEHOUSE -> 0xFFC8985A.toInt() to 0xFFA87A3A.toInt()
            HouseType.CANDY -> 0xFFFFE0EC.toInt() to 0xFFFF9AC8.toInt()
            HouseType.BEACH -> 0xFFF8E8C0.toInt() to 0xFF8AD8F0.toInt()
            HouseType.SPACE -> 0xFF2A3050.toInt() to 0xFFFFFFFF.toInt()
            HouseType.CRYSTAL -> 0xFFE0C8FF.toInt() to 0xFFB88AF0.toInt()
        }
        // A painted house gets painted walls inside too.
        val wall = if (paint != 0) mix(paint, 0xFFFFFFFF.toInt(), 0.35f) else normalWall
        val stripe = if (paint != 0) mix(paint, 0xFF000000.toInt(), 0.15f) else normalStripe
        gfx.rect(0f, 0f, viewW, viewH, wall)
        val x0 = ox
        val x1 = ox + ROOM_W
        when (type) {
            HouseType.COTTAGE -> { var x = x0; while (x < x1) { gfx.rect(x, 24f, 10f, FLOOR_Y - 24, stripe); x += 34f } }
            HouseType.CABIN -> for (i in 0 until 10) gfx.rect(x0, 24f + i * 15f, ROOM_W, 2f, stripe)
            HouseType.IGLOO, HouseType.CASTLE -> for (i in 0 until 10) {
                gfx.rect(x0, 24f + i * 16f, ROOM_W, 1f, stripe)
                var x = x0 + if (i % 2 == 0) 0f else 18f
                while (x < x1) { gfx.rect(x, 24f + i * 16f, 1f, 16f, stripe); x += 36f }
            }
            HouseType.MUSHROOM -> for (i in 0 until 50) gfx.oval(x0 + (i * 53f) % ROOM_W, 30f + (i * 37f) % 140f, 7f, 7f, stripe)
            HouseType.TREEHOUSE -> for (i in 0 until 24) gfx.rect(x0 + i * 30f, 24f, 2f, FLOOR_Y - 24, stripe)
            HouseType.CANDY -> for (i in 0 until 40) gfx.rect(x0 + (i * 47f) % ROOM_W, 34f + (i * 29f) % 130f, 8f, 3f, if (i % 3 == 0) 0xFF6AD8FF.toInt() else stripe)
            HouseType.BEACH -> for (i in 0 until 5) gfx.rect(x0, 40f + i * 28f, ROOM_W, 3f, stripe)
            HouseType.SPACE -> for (i in 0 until 60) gfx.oval(x0 + (i * 67f) % ROOM_W, 28f + (i * 41f) % 140f, 2f, 2f, stripe)
            HouseType.CRYSTAL -> for (i in 0 until 30) {
                val cx = x0 + (i * 59f) % ROOM_W
                val cy = 36f + (i * 43f) % 120f
                gfx.poly(floatArrayOf(cx, cx + 4, cx + 8), floatArrayOf(cy + 10, cy, cy + 10), stripe)
            }
        }
        if (type == HouseType.CASTLE) {
            for (bx in listOf(x0 + 150f, x0 + 600f)) {
                gfx.rect(bx, 28f, 16f, 34f, 0xFFB01A1A.toInt())
                gfx.poly(floatArrayOf(bx, bx + 8, bx + 16), floatArrayOf(62f, 56f, 62f), wall)
                gfx.oval(bx + 4, 36f, 8f, 8f, 0xFFFFD21F.toInt())
            }
        }
        // Darker outside the room's walls.
        if (x0 > 0) gfx.rect(0f, 0f, x0, viewH, 0xFF2A1A10.toInt())
        if (x1 < viewW) gfx.rect(x1, 0f, viewW - x1, viewH, 0xFF2A1A10.toInt())
        val floor = when (type) {
            HouseType.COTTAGE -> 0xFFB98A52.toInt()
            HouseType.CABIN -> 0xFF6A3A1A.toInt()
            HouseType.IGLOO -> 0xFFF4F8FF.toInt()
            HouseType.MUSHROOM -> 0xFF5AB85A.toInt()
            HouseType.CASTLE -> 0xFF5A5A6A.toInt()
            HouseType.TREEHOUSE -> 0xFF8A5A2A.toInt()
            HouseType.CANDY -> 0xFFC8884A.toInt()
            HouseType.BEACH -> 0xFFF4DCA0.toInt()
            HouseType.SPACE -> 0xFF8A94A4.toInt()
            HouseType.CRYSTAL -> 0xFF9A6AD0.toInt()
        }
        gfx.rect(maxOf(0f, x0), FLOOR_Y, minOf(viewW, x1) - maxOf(0f, x0), viewH - FLOOR_Y, floor)
        gfx.rect(maxOf(0f, x0), FLOOR_Y, minOf(viewW, x1) - maxOf(0f, x0), 3f, 0x55000000)
        var x = x0
        while (x < x1) { if (x > 0 && x < viewW) gfx.rect(x, FLOOR_Y + 3, 1f, viewH, 0x22000000); x += 34f }
    }

    private fun mix(a: Int, b: Int, k: Float): Int {
        fun ch(sh: Int) = ((((a shr sh) and 255) * (1 - k)) + (((b shr sh) and 255) * k)).toInt()
        return argb(255, (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
    }

    /** The Pokemon's play mat: a round cushion with a ball on it. */
    fun drawMonMat(gfx: Gfx, ox: Float, t: Float) {
        val x = MON_X + ox
        gfx.oval(x - 4, FLOOR_Y + 10, 52f, 14f, 0xFFE84A4A.toInt())
        gfx.oval(x, FLOOR_Y + 12, 44f, 9f, 0xFFFFFFFF.toInt())
        gfx.shadowText("POKEMON", x + 22, FLOOR_Y - 4, 6f, 0xFFFFF2C8.toInt(), 1)
    }

    /** A window showing the sky outside. */
    fun drawWindow(gfx: Gfx, x: Float, y: Float, t: Float) {
        gfx.rect(x - 3, y - 3, 62f, 46f, 0xFF5A3A1A.toInt())
        gfx.rect(x, y, 56f, 40f, 0xFF7FC8FF.toInt())
        gfx.oval(x + 38, y + 6, 12f, 12f, 0xFFFFE87A.toInt())
        val cx = x + ((t * 6) % 70f) - 14f
        if (cx > x - 6 && cx < x + 50) gfx.oval(cx.coerceIn(x, x + 40), y + 18, 16f, 8f, 0xFFFFFFFF.toInt())
        gfx.rect(x + 27, y, 2f, 40f, 0xFF5A3A1A.toInt())
        gfx.rect(x, y + 19, 56f, 2f, 0xFF5A3A1A.toInt())
        gfx.rect(x - 5, y + 40, 66f, 4f, 0xFF7A4A20.toInt())
    }

    const val DOOR_X = 10f
    const val SHOP_X = 52f
    const val BED_X = 250f
    const val SHELF_X = 400f

    fun drawDoor(gfx: Gfx, x: Float) {
        gfx.rect(x, FLOOR_Y - 56, 30f, 56f, 0xFF5A3A1A.toInt())
        gfx.rect(x + 3, FLOOR_Y - 53, 24f, 53f, 0xFF8A5A2A.toInt())
        gfx.oval(x + 20, FLOOR_Y - 28, 4f, 4f, 0xFFFFD21F.toInt())
        gfx.shadowText("MAP", x + 15, FLOOR_Y - 60, 7f, 0xFFFFF2C8.toInt(), 1)
    }

    /** The shop counter: buy furniture, beds, plushies and new houses here. */
    fun drawShop(g: TeamGame, gfx: Gfx, x: Float, t: Float) {
        // Awning
        for (i in 0 until 6) gfx.rect(x + i * 11f, FLOOR_Y - 92, 11f, 12f, if (i % 2 == 0) 0xFFE52521.toInt() else 0xFFFFFFFF.toInt())
        gfx.rect(x, FLOOR_Y - 80, 66f, 3f, 0xFF7A4A20.toInt())
        gfx.shadowText("SHOP", x + 33, FLOOR_Y - 97, 9f, 0xFFFFD21F.toInt(), 1)
        // Shopkeeper (a friendly villager)
        Villager(x + 26f, FLOOR_Y - 18f, 3).draw(g, gfx, 0f)
        // Counter with a cash register
        gfx.rect(x, FLOOR_Y - 26, 66f, 26f, 0xFF8A5A2A.toInt())
        gfx.rect(x, FLOOR_Y - 28, 66f, 4f, 0xFFB98A52.toInt())
        gfx.rect(x + 44, FLOOR_Y - 38, 16f, 10f, 0xFF3A3A44.toInt())
        gfx.rect(x + 46, FLOOR_Y - 36, 12f, 3f, 0xFF7FE07F.toInt())
        if ((t * 2).toInt() % 2 == 0) gfx.oval(x + 10, FLOOR_Y - 34, 5f, 5f, 0xFFFFD21F.toInt())
    }

    fun drawBed(gfx: Gfx, style: BedStyle, x: Float) {
        val fy = FLOOR_Y
        gfx.rect(x, fy - 24, 5f, 24f, 0xFF7A4A20.toInt())
        gfx.rect(x + 51, fy - 16, 5f, 16f, 0xFF7A4A20.toInt())
        gfx.rect(x, fy - 10, 56f, 6f, 0xFF7A4A20.toInt())
        gfx.rect(x + 5, fy - 17, 46f, 8f, 0xFFF4F4F4.toInt())
        gfx.rect(x + 18, fy - 18, 33f, 9f, style.blanket)
        gfx.rect(x + 18, fy - 18, 33f, 2f, 0x33FFFFFF)
        gfx.oval(x + 6, fy - 21, 12f, 7f, style.pillow)
        gfx.shadowText("SLEEP", x + 28, fy - 26, 6f, 0xFFFFF2C8.toInt(), 1)
    }

    /** Where each piece of furniture goes, as an x position in the room. */
    fun spot(f: Furniture): Float = when (f) {
        Furniture.BOOKSHELF -> 132f
        Furniture.FIREPLACE -> 172f
        Furniture.PAINTING -> 176f
        Furniture.COUCH -> 330f
        Furniture.LAMP -> 316f
        Furniture.RUG -> 300f
        Furniture.TABLE -> 420f
        Furniture.CHAIRS -> 410f
        Furniture.TV -> 486f
        Furniture.FISH_TANK -> 536f
        Furniture.PLANT -> 580f
        Furniture.CLAW_MACHINE -> 650f
        Furniture.TOY_CHEST -> 70f
        Furniture.BUNK_BEDS -> 140f
        Furniture.ARCADE -> 240f
        Furniture.TRAMPOLINE -> 320f
        Furniture.BALL_PIT -> 430f
        Furniture.TOY_TRAIN -> 540f
        Furniture.TELESCOPE -> 80f
        Furniture.PIANO -> 170f
        Furniture.TROPHY_CASE -> 280f
        Furniture.HOT_TUB -> 370f
        Furniture.DISCO_BALL -> 500f
        Furniture.POOL_TABLE -> 560f
    }

    fun drawFurniture(gfx: Gfx, f: Furniture, ox: Float, t: Float) {
        val x = spot(f) + ox
        val fy = FLOOR_Y
        when (f) {
            Furniture.RUG -> {
                gfx.oval(x, fy + 14, 150f, 30f, 0xFFB03A5A.toInt())
                gfx.oval(x + 10, fy + 18, 130f, 22f, 0xFFE8C04A.toInt())
                gfx.oval(x + 22, fy + 22, 106f, 14f, 0xFFB03A5A.toInt())
            }
            Furniture.LAMP -> {
                gfx.rect(x + 4, fy - 46, 2f, 46f, 0xFF3A3A3A.toInt())
                gfx.rect(x - 1, fy - 3, 12f, 3f, 0xFF3A3A3A.toInt())
                gfx.poly(floatArrayOf(x - 4, x + 14, x + 10, x), floatArrayOf(fy - 46, fy - 46, fy - 58, fy - 58), 0xFFFFE08A.toInt())
                gfx.oval(x - 14, fy - 52, 38f, 26f, 0x33FFF2A0)
            }
            Furniture.TABLE -> {
                gfx.rect(x, fy - 22, 36f, 4f, 0xFF8A5A2A.toInt())
                gfx.rect(x + 3, fy - 18, 3f, 18f, 0xFF6A3A1A.toInt())
                gfx.rect(x + 30, fy - 18, 3f, 18f, 0xFF6A3A1A.toInt())
                gfx.oval(x + 12, fy - 30, 12f, 8f, 0xFFFFFFFF.toInt())
                gfx.oval(x + 14, fy - 32, 8f, 5f, 0xFFE52521.toInt())
            }
            Furniture.CHAIRS -> for (cx in listOf(x - 4, x + 52)) {
                gfx.rect(cx, fy - 14, 12f, 3f, 0xFFB98A52.toInt())
                gfx.rect(cx + (if (cx < x) 0f else 9f), fy - 30, 3f, 30f, 0xFF8A5A2A.toInt())
                gfx.rect(cx + (if (cx < x) 9f else 0f), fy - 11, 3f, 11f, 0xFF8A5A2A.toInt())
            }
            Furniture.COUCH -> {
                gfx.rect(x, fy - 30, 56f, 16f, 0xFF3A6AC8.toInt())
                gfx.rect(x - 4, fy - 20, 64f, 14f, 0xFF4A7AD8.toInt())
                gfx.rect(x - 6, fy - 24, 8f, 18f, 0xFF2A5AB0.toInt())
                gfx.rect(x + 54, fy - 24, 8f, 18f, 0xFF2A5AB0.toInt())
                gfx.rect(x, fy - 6, 4f, 6f, 0xFF3A2A1A.toInt())
                gfx.rect(x + 52, fy - 6, 4f, 6f, 0xFF3A2A1A.toInt())
                gfx.oval(x + 6, fy - 28, 14f, 10f, 0xFFFFD21F.toInt())
            }
            Furniture.BOOKSHELF -> {
                gfx.rect(x, fy - 60, 30f, 60f, 0xFF6A3A1A.toInt())
                for (s in 0 until 3) {
                    val sy = fy - 56 + s * 19f
                    gfx.rect(x + 2, sy, 26f, 16f, 0xFF3A2010.toInt())
                    for (b in 0 until 6) {
                        val c = listOf(0xFFE52521, 0xFF2E6BE6, 0xFF2EB84A, 0xFFFFD21F, 0xFFC890FF, 0xFFFF8A3A)[(b + s) % 6].toInt()
                        gfx.rect(x + 3 + b * 4f, sy + 2 + (b % 2), 3.5f, 14f - (b % 2), c)
                    }
                }
            }
            Furniture.FIREPLACE -> {
                gfx.rect(x, fy - 42, 48f, 42f, 0xFF8A8A8A.toInt())
                gfx.rect(x - 4, fy - 46, 56f, 6f, 0xFF6A4A2A.toInt())
                gfx.rect(x + 10, fy - 28, 28f, 28f, 0xFF1A1010.toInt())
                val flick = sin(t * 12) * 2
                gfx.poly(floatArrayOf(x + 14, x + 24, x + 34), floatArrayOf(fy - 2, fy - 22 + flick, fy - 2), 0xFFFF6A00.toInt())
                gfx.poly(floatArrayOf(x + 18, x + 24, x + 30), floatArrayOf(fy - 2, fy - 14 - flick, fy - 2), 0xFFFFD21F.toInt())
                for (i in 0 until 3) gfx.rect(x, fy - 34 + i * 10f, 48f, 1f, 0xFF6A6A6A.toInt())
            }
            Furniture.PAINTING -> {
                gfx.rect(x, 66f, 42f, 30f, 0xFFC89A2A.toInt())
                gfx.rect(x + 3, 69f, 36f, 24f, 0xFF7FC8FF.toInt())
                gfx.poly(floatArrayOf(x + 3, x + 16, x + 30, x + 39, x + 39, x + 3), floatArrayOf(93f, 76f, 88f, 80f, 93f, 93f), 0xFF3CB84A.toInt())
                gfx.oval(x + 28, 72f, 7f, 7f, 0xFFFFE87A.toInt())
            }
            Furniture.TV -> {
                gfx.rect(x + 2, fy - 14, 34f, 14f, 0xFF5A3A1A.toInt())
                gfx.rect(x, fy - 40, 38f, 26f, 0xFF1A1A1A.toInt())
                gfx.rect(x + 2, fy - 38, 34f, 21f, if ((t * 2).toInt() % 2 == 0) 0xFF3A8AE8.toInt() else 0xFF4AC86A.toInt())
                // Rocco on TV!
                gfx.sprite(TeamArt.rocco(Power.NONE, (t * 4).toInt() % 3), x + 13, fy - 37, w = 11f, h = 17f)
            }
            Furniture.FISH_TANK -> {
                gfx.rect(x, fy - 16, 32f, 16f, 0xFF3A3A3A.toInt())
                gfx.rect(x, fy - 40, 32f, 24f, 0x996AB8F0.toInt())
                gfx.rect(x, fy - 40, 32f, 2f, 0xFFDDEEFF.toInt())
                for (i in 0 until 2) {
                    val fx = x + 4 + ((t * (10 + i * 6) + i * 11) % 22f)
                    gfx.oval(fx, fy - 34 + i * 9f, 6f, 4f, if (i == 0) 0xFFFF8A3A.toInt() else 0xFFFFD21F.toInt())
                }
                gfx.rect(x + 2, fy - 20, 28f, 3f, 0xFFE8D8A0.toInt())
                gfx.oval(x + 22 + sin(t * 3), fy - 30 - (t * 8 % 8), 3f, 3f, 0xAAFFFFFF.toInt())
            }
            Furniture.PLANT -> {
                gfx.rect(x + 2, fy - 12, 12f, 12f, 0xFFC86A3A.toInt())
                gfx.oval(x - 4, fy - 30, 14f, 20f, 0xFF2EB84A.toInt())
                gfx.oval(x + 6, fy - 34, 14f, 24f, 0xFF3CC85A.toInt())
                gfx.oval(x + 1, fy - 40, 12f, 18f, 0xFF2EA84A.toInt())
            }
            Furniture.CLAW_MACHINE -> {
                gfx.rect(x, fy - 56, 32f, 56f, 0xFFE52521.toInt())
                gfx.rect(x + 3, fy - 52, 26f, 26f, 0xAADDEEFF.toInt())
                for (i in 0 until 4) gfx.oval(x + 4 + i * 6f, fy - 33, 6f, 6f, listOf(0xFFFFD82A, 0xFF4A8AE8, 0xFFFF9AD0, 0xFF3CB878)[i].toInt())
                val cx = x + 8 + (sin(t * 1.5f) + 1) * 8
                gfx.rect(cx + 2, fy - 52, 1f, 8f, 0xFF3A3A3A.toInt())
                gfx.poly(floatArrayOf(cx - 1, cx + 2.5f, cx + 6), floatArrayOf(fy - 40, fy - 44, fy - 40), 0xFFC8C8D0.toInt())
                gfx.rect(x + 3, fy - 22, 26f, 10f, 0xFFB01A1A.toInt())
                gfx.text("PLAY", x + 16, fy - 15, 6f, 0xFFFFFFFF.toInt(), 1)
            }
            Furniture.TOY_CHEST -> {
                gfx.rect(x, fy - 20, 40f, 20f, 0xFFB0702A.toInt())
                gfx.rect(x - 2, fy - 26, 44f, 7f, 0xFF8A5020.toInt())
                gfx.rect(x + 17, fy - 22, 6f, 6f, 0xFFFFD21F.toInt())
                gfx.oval(x + 4, fy - 34, 10f, 10f, 0xFFE52521.toInt())
                gfx.rect(x + 26, fy - 36, 4f, 12f, 0xFF3A6AC8.toInt())
            }
            Furniture.BUNK_BEDS -> {
                gfx.rect(x, fy - 64, 4f, 64f, 0xFF7A4A20.toInt()); gfx.rect(x + 60, fy - 64, 4f, 64f, 0xFF7A4A20.toInt())
                for ((y, c) in listOf(fy - 14 to 0xFF3CB84A.toInt(), fy - 46 to 0xFFFF8A3A.toInt())) {
                    gfx.rect(x, y, 64f, 5f, 0xFF7A4A20.toInt())
                    gfx.rect(x + 4, y - 6, 56f, 6f, 0xFFF4F4F4.toInt())
                    gfx.rect(x + 18, y - 7, 42f, 7f, c)
                }
                for (i in 0 until 4) gfx.rect(x + 54, fy - 42 + i * 8f, 10f, 1.5f, 0xFF7A4A20.toInt())
            }
            Furniture.ARCADE -> {
                gfx.rect(x, fy - 62, 34f, 62f, 0xFF3A2A8A.toInt())
                gfx.rect(x + 4, fy - 56, 26f, 20f, 0xFF101018.toInt())
                gfx.oval(x + 8 + ((t * 20) % 16), fy - 50, 6f, 6f, 0xFFE84A4A.toInt())
                gfx.rect(x + 6, fy - 40, 22f, 2f, 0xFF4AC86A.toInt())
                gfx.rect(x + 2, fy - 32, 30f, 8f, 0xFF2A1A6A.toInt())
                gfx.oval(x + 8, fy - 31, 5f, 5f, 0xFFFF4A4A.toInt()); gfx.oval(x + 20, fy - 31, 5f, 5f, 0xFF4AD8FF.toInt())
                gfx.text("GAME", x + 17, fy - 64, 6f, if ((t * 3).toInt() % 2 == 0) 0xFFFFD21F.toInt() else 0xFFFF6AA8.toInt(), 1)
            }
            Furniture.TRAMPOLINE -> {
                gfx.rect(x + 4, fy - 12, 3f, 12f, 0xFF3A3A3A.toInt()); gfx.rect(x + 49, fy - 12, 3f, 12f, 0xFF3A3A3A.toInt())
                gfx.oval(x, fy - 16, 56f, 10f, 0xFF2E6BE6.toInt())
                gfx.oval(x + 4, fy - 15, 48f, 7f, 0xFF1A1A2A.toInt())
                gfx.oval(x + 22, fy - 40 - kotlin.math.abs(sin(t * 4)) * 20, 10f, 10f, 0xFFFFD21F.toInt())
            }
            Furniture.BALL_PIT -> {
                gfx.rect(x, fy - 22, 70f, 22f, 0xFFFF9AC8.toInt())
                for (i in 0 until 18) gfx.oval(x + 2 + (i * 11f) % 64, fy - 26 + (i / 6) * 6f, 7f, 7f, listOf(0xFFE84A4A, 0xFF4A8AE8, 0xFFFFD82A, 0xFF3CB878)[i % 4].toInt())
                gfx.rect(x, fy - 22, 70f, 2f, 0xFFFF6AA8.toInt())
            }
            Furniture.TOY_TRAIN -> {
                gfx.oval(x - 10, fy + 8, 120f, 34f, 0xFF6A5A4A.toInt())
                gfx.oval(x - 6, fy + 11, 112f, 28f, 0xFFB98A52.toInt())
                val a = t * 1.2f
                for (k in 0 until 3) {
                    val tx = x + 50 + kotlin.math.cos(a - k * 0.35f) * 50 - 6
                    val ty = fy + 22 + sin(a - k * 0.35f) * 13 - 6
                    gfx.rect(tx, ty, 12f, 9f, if (k == 0) 0xFFE52521.toInt() else 0xFF3A6AC8.toInt())
                    if (k == 0) gfx.rect(tx + 7, ty - 4, 3f, 4f, 0xFF3A3A3A.toInt())
                }
            }
            Furniture.TELESCOPE -> {
                gfx.rect(x + 12, fy - 26, 2f, 26f, 0xFF5A5A5A.toInt())
                gfx.poly(floatArrayOf(x + 4, x + 22, x + 13), floatArrayOf(fy, fy, fy - 10), 0xFF5A5A5A.toInt())
                gfx.poly(floatArrayOf(x, x + 30, x + 32, x + 2), floatArrayOf(fy - 30, fy - 46, fy - 42, fy - 25), 0xFFC89A2A.toInt())
                gfx.oval(x + 28, fy - 47, 6f, 6f, 0xFF7FC8FF.toInt())
            }
            Furniture.PIANO -> {
                gfx.rect(x, fy - 44, 70f, 30f, 0xFF1A1A1A.toInt())
                gfx.rect(x, fy - 18, 70f, 8f, 0xFFF4F4F4.toInt())
                for (i in 0 until 10) gfx.rect(x + 3 + i * 7f, fy - 18, 3f, 5f, 0xFF1A1A1A.toInt())
                gfx.rect(x + 2, fy - 10, 4f, 10f, 0xFF1A1A1A.toInt()); gfx.rect(x + 64, fy - 10, 4f, 10f, 0xFF1A1A1A.toInt())
                // A music note floating up
                val nx = x + 50 + sin(t * 2) * 4
                val ny = fy - 50 - (t * 10 % 10)
                gfx.oval(nx, ny + 6, 5f, 4f, 0xFF3A3A8A.toInt())
                gfx.rect(nx + 4, ny, 1.5f, 8f, 0xFF3A3A8A.toInt())
                gfx.rect(nx + 4, ny, 4f, 1.5f, 0xFF3A3A8A.toInt())
            }
            Furniture.TROPHY_CASE -> {
                gfx.rect(x, fy - 64, 50f, 64f, 0xFF7A4A20.toInt())
                gfx.rect(x + 3, fy - 61, 44f, 58f, 0xAADDEEFF.toInt())
                for (row in 0 until 2) for (k in 0 until 3) {
                    val tx = x + 8 + k * 14f
                    val ty = fy - 34 + row * 28f
                    gfx.rect(x + 3, ty + 2, 44f, 2f, 0xFF7A4A20.toInt())
                    gfx.poly(floatArrayOf(tx, tx + 8, tx + 6, tx + 2), floatArrayOf(ty - 14, ty - 14, ty - 6, ty - 6), 0xFFFFD21F.toInt())
                    gfx.rect(tx + 3, ty - 6, 2f, 6f, 0xFFE8B82A.toInt())
                }
            }
            Furniture.HOT_TUB -> {
                gfx.rect(x, fy - 22, 80f, 22f, 0xFF8A5A2A.toInt())
                gfx.rect(x + 4, fy - 20, 72f, 8f, 0xFF4AD8FF.toInt())
                for (i in 0 until 5) {
                    val p = (t * 0.8f + i * 0.2f) % 1f
                    gfx.oval(x + 8 + i * 14f, fy - 22 - p * 18, 5f, 5f, argb(((1 - p) * 200).toInt(), 0xFFFFFF))
                }
            }
            Furniture.DISCO_BALL -> {
                gfx.rect(x + 19, 24f, 1f, 24f, 0xFF3A3A3A.toInt())
                gfx.oval(x + 8, 46f, 24f, 24f, 0xFFC8D0DC.toInt())
                for (i in 0 until 6) gfx.rect(x + 11 + (i % 3) * 6f, 50f + (i / 3) * 8f, 4f, 4f, solidRainbow(t + i * 0.15f, 0f))
                for (i in 0 until 5) {
                    val a = t * 2 + i * 1.26f
                    gfx.oval(x + 20 + kotlin.math.cos(a) * 90, 110f + sin(a) * 40, 6f, 6f, argb(110, rainbow(t + i * 0.2f)))
                }
            }
            Furniture.POOL_TABLE -> {
                gfx.rect(x, fy - 26, 90f, 8f, 0xFF6A3A1A.toInt())
                gfx.rect(x + 3, fy - 28, 84f, 4f, 0xFF2E9A4A.toInt())
                gfx.rect(x + 6, fy - 18, 6f, 18f, 0xFF6A3A1A.toInt()); gfx.rect(x + 78, fy - 18, 6f, 18f, 0xFF6A3A1A.toInt())
                for (i in 0 until 4) gfx.oval(x + 30 + i * 6f, fy - 32, 5f, 5f, listOf(0xFFFFD82A, 0xFFE84A4A, 0xFF3A6AC8, 0xFFFFFFFF)[i].toInt())
            }
        }
    }

    /** Stairs at the end of the room, to go up (or down) a floor. */
    fun drawStairs(gfx: Gfx, x: Float, up: Boolean) {
        for (i in 0 until 6) {
            val sx = if (up) x + i * 4f else x + 24f - i * 4f
            gfx.rect(sx, FLOOR_Y - (i + 1) * 12f, 8f, (i + 1) * 12f, 0xFF8A5A2A.toInt())
            gfx.rect(sx, FLOOR_Y - (i + 1) * 12f, 8f, 2f, 0xFFB98A52.toInt())
        }
        gfx.shadowText(if (up) "UP" else "DOWN", x + 16, FLOOR_Y - 78, 7f, 0xFFFFF2C8.toInt(), 1)
    }

    /** The plushie shelves on the wall: 3 rows of 12 show off your newest plushies. */
    fun shelfSpot(i: Int): Pair<Float, Float> = (SHELF_X + 8f + (i % 12) * 17f) to (48f + (i / 12) * 30f)
    const val SHELF_SLOTS = 36

    fun drawShelves(gfx: Gfx, ox: Float) {
        for (row in 0 until 3) {
            val y = 48f + row * 30f
            gfx.rect(ox + SHELF_X, y, 208f, 4f, 0xFF7A4A20.toInt())
            gfx.rect(ox + SHELF_X + 4, y + 4, 3f, 5f, 0xFF5A3A1A.toInt())
            gfx.rect(ox + SHELF_X + 200, y + 4, 3f, 5f, 0xFF5A3A1A.toInt())
        }
        gfx.shadowText("PLUSHIES", ox + SHELF_X + 104, 132f, 7f, 0xFFFF9AD0.toInt(), 1)
    }
}

/** A plushie popping out of a mystery block. Grab it and it goes to the shelves at home! */
class PlushieItem(x: Float, y: Float) : Pickup(x, y, false) {
    init { vx = 35f }
    override fun collect(g: TeamGame) { g.givePlushie() }
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val c = 0xFFC8905A.toInt()
        gfx.oval(px + 1, y + 4, 10f, 9f, c)
        gfx.oval(px + 2, y - 3, 8f, 8f, c)
        gfx.oval(px + 1, y - 4, 3.5f, 3.5f, c)
        gfx.oval(px + 7.5f, y - 4, 3.5f, 3.5f, c)
        gfx.rect(px + 4, y, 1.2f, 1.2f, 0xFF101010.toInt())
        gfx.rect(px + 7, y, 1.2f, 1.2f, 0xFF101010.toInt())
        gfx.oval(px + 8, y + 6, 4f, 4f, 0xFFFF6A9A.toInt())
        if ((t * 4).toInt() % 2 == 0) gfx.oval(px + 10, y - 6, 2.5f, 2.5f, 0xFFFFFFFF.toInt())
    }
}
