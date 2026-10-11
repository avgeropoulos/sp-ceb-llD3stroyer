package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import kotlin.math.sin

/** The kinds of house the team can build. They all have the same size and the door on the left. */
enum class HouseType(val label: String, val costs: List<Pair<Ore, Int>>, val coins: Int, val note: String) {
    COTTAGE("COTTAGE", listOf(Ore.STONE to 10, Ore.IRON to 2), 0, "A cozy stone house"),
    CABIN("LOG CABIN", listOf(Ore.STONE to 6, Ore.COPPER to 5), 15, "Warm wooden logs"),
    IGLOO("IGLOO", listOf(Ore.STONE to 8, Ore.SAPPHIRE to 2), 20, "A round house made of ice"),
    MUSHROOM("MUSHROOM HOUSE", listOf(Ore.EMERALD to 4, Ore.GOLD to 3), 30, "Live inside a giant mushroom!"),
    CASTLE("MINI CASTLE", listOf(Ore.STONE to 20, Ore.GOLD to 4, Ore.OBSIDIAN to 3), 60, "Towers and flags. Fancy!"),
}

/** Furniture to decorate the inside of the house. Each piece has its own spot in the room. */
enum class Furniture(val label: String, val costs: List<Pair<Ore, Int>>, val coins: Int) {
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
}

/** 20 plushies to collect and show off on the shelves at home. */
enum class Plushie(val label: String, val sticker: String?) {
    ROCCO("ROCCO", "ROCCO"),
    BLOCKY("BLOCKY", "BLOCKY"),
    SPARKY("SPARKY", "SPARKY"),
    BLAZE("BLAZE", "BLAZE"),
    HYDRO("HYDRO", "HYDRO"),
    BLOOM("BLOOM", "BLOOM"),
    EMBER("EMBER", "EMBER"),
    SPLASHY("SPLASHY", "SPLASHY"),
    MYSTIC("MYSTIC", "MYSTIC"),
    PUNCHO("PUNCHO", "PUNCHO"),
    SPOOKY("SPOOKY", "SPOOKY"),
    FROG("RING-DING FROG", "RING-DING FROG"),
    DENNIS("DENNIS", "DENNIS"),
    GRUMBLER("GRUMBLER", "GRUMBLER"),
    GLOOP("GLOOP", null),
    ENDERMAN("ENDERMAN", "ENDERMAN"),
    KRAG_JR("KRAG JR.", "KRAG JR."),
    KING_KRAG("KING KRAG", "KING KRAG"),
    WARDEN("THE WARDEN", "THE WARDEN"),
    DRAGON("ENDER DRAGON", "ENDER DRAGON"),
}

object Homes {
    /** The room is laid out for a 427-wide screen and centered on wider ones. */
    const val ROOM_W = 427f
    const val FLOOR_Y = 176f

    // ------------------------------------------------------------------ Outside

    fun drawHouse(gfx: Gfx, type: HouseType, x: Float, bottom: Float, t: Float) {
        val top = bottom - 40
        when (type) {
            HouseType.COTTAGE -> { TeamArt.house(gfx, x, bottom, t); return }
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
        // Every house has its door in the same spot, on the left.
        if (type != HouseType.IGLOO) {
            gfx.rect(x + 6, bottom - 26, 14f, 26f, if (type == HouseType.CASTLE) 0xFF5A3A1A.toInt() else 0xFF7A4A20.toInt())
            gfx.oval(x + 16, bottom - 14, 2.5f, 2.5f, 0xFFFFD21F.toInt())
        } else {
            gfx.oval(x + 6, bottom - 24, 14f, 26f, 0xFF4A6A8A.toInt())
        }
    }

    private fun smoke(gfx: Gfx, x: Float, y: Float, t: Float) {
        for (i in 0 until 3) {
            val p = (t * 0.6f + i / 3f) % 1f
            gfx.oval(x + sin(p * 6) * 3, y - p * 22, 6f + p * 6, 6f + p * 6, argb(((1 - p) * 150).toInt(), 0xDDDDDD))
        }
    }

    // ------------------------------------------------------------------ Inside

    /** Wallpaper and floor for each kind of house. [ox] is where the 427-wide room starts. */
    fun drawRoom(gfx: Gfx, type: HouseType, viewW: Float, viewH: Float) {
        val (wall, stripe) = when (type) {
            HouseType.COTTAGE -> 0xFFF2E2C0.toInt() to 0xFFF0C8C8.toInt()
            HouseType.CABIN -> 0xFF9A6A3A.toInt() to 0xFF7A4A20.toInt()
            HouseType.IGLOO -> 0xFFDDEEFF.toInt() to 0xFFB8D4F0.toInt()
            HouseType.MUSHROOM -> 0xFFFFC8D8.toInt() to 0xFFFFFFFF.toInt()
            HouseType.CASTLE -> 0xFF8A8A9A.toInt() to 0xFF6A6A7A.toInt()
        }
        gfx.rect(0f, 0f, viewW, viewH, wall)
        when (type) {
            HouseType.COTTAGE -> { var x = 0f; while (x < viewW) { gfx.rect(x, 24f, 10f, FLOOR_Y - 24, stripe); x += 28f } }
            HouseType.CABIN -> for (i in 0 until 10) gfx.rect(0f, 24f + i * 15f, viewW, 2f, stripe)
            HouseType.IGLOO -> for (i in 0 until 10) {
                gfx.rect(0f, 24f + i * 16f, viewW, 1f, stripe)
                var x = if (i % 2 == 0) 0f else 20f
                while (x < viewW) { gfx.rect(x, 24f + i * 16f, 1f, 16f, stripe); x += 40f }
            }
            HouseType.MUSHROOM -> for (i in 0 until 60) gfx.oval((i * 53f) % viewW, 30f + (i * 37f) % 140f, 8f, 8f, stripe)
            HouseType.CASTLE -> for (i in 0 until 10) {
                gfx.rect(0f, 24f + i * 16f, viewW, 1f, stripe)
                var x = if (i % 2 == 0) 0f else 16f
                while (x < viewW) { gfx.rect(x, 24f + i * 16f, 1f, 16f, stripe); x += 32f }
            }
        }
        if (type == HouseType.CASTLE) {
            // Red banners
            for (bx in listOf(viewW * 0.2f, viewW * 0.8f)) {
                gfx.rect(bx, 28f, 16f, 34f, 0xFFB01A1A.toInt())
                gfx.poly(floatArrayOf(bx, bx + 8, bx + 16), floatArrayOf(62f, 56f, 62f), stripe)
                gfx.oval(bx + 4, 36f, 8f, 8f, 0xFFFFD21F.toInt())
            }
        }
        val floor = when (type) {
            HouseType.COTTAGE -> 0xFFB98A52.toInt()
            HouseType.CABIN -> 0xFF6A3A1A.toInt()
            HouseType.IGLOO -> 0xFFF4F8FF.toInt()
            HouseType.MUSHROOM -> 0xFF5AB85A.toInt()
            HouseType.CASTLE -> 0xFF5A5A6A.toInt()
        }
        gfx.rect(0f, FLOOR_Y, viewW, viewH - FLOOR_Y, floor)
        gfx.rect(0f, FLOOR_Y, viewW, 3f, 0x55000000)
        var x = 0f
        while (x < viewW) { gfx.rect(x, FLOOR_Y + 3, 1f, viewH, 0x22000000); x += 34f }
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

    fun drawDoor(gfx: Gfx, x: Float) {
        gfx.rect(x, FLOOR_Y - 56, 30f, 56f, 0xFF5A3A1A.toInt())
        gfx.rect(x + 3, FLOOR_Y - 53, 24f, 53f, 0xFF8A5A2A.toInt())
        gfx.oval(x + 20, FLOOR_Y - 28, 4f, 4f, 0xFFFFD21F.toInt())
        gfx.shadowText("EXIT", x + 15, FLOOR_Y - 60, 7f, 0xFFFFF2C8.toInt(), 1)
    }

    /** Where each piece of furniture goes, as an x position in the 427-wide room. */
    fun spot(f: Furniture): Float = when (f) {
        Furniture.BOOKSHELF -> 42f
        Furniture.FIREPLACE -> 78f
        Furniture.PAINTING -> 82f
        Furniture.COUCH -> 170f
        Furniture.LAMP -> 228f
        Furniture.RUG -> 150f
        Furniture.TABLE -> 252f
        Furniture.CHAIRS -> 242f
        Furniture.TV -> 300f
        Furniture.FISH_TANK -> 340f
        Furniture.PLANT -> 376f
        Furniture.CLAW_MACHINE -> 392f
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
        }
    }

    /** Draws plushie [p] sitting with its bottom at ([cx], [bottom]), about [size] tall. */
    fun drawPlushie(g: TeamGame, gfx: Gfx, p: Plushie, cx: Float, bottom: Float, size: Float) {
        gfx.oval(cx - size * 0.45f, bottom - 2, size * 0.9f, 4f, 0x44000000)
        val name = p.sticker
        if (name == null) {
            val s = size / 18f
            Gloop(0f, 16f).draw(g, ScaledGfx(gfx, s, cx - 9 * s, bottom - 17 * s), 0f)
        } else {
            val i = Stickers.all.indexOfFirst { it.name == name }
            if (i >= 0) Stickers.drawPicture(g, gfx, i, cx, bottom - size / 2, size)
        }
        // A little heart tag: it's a plushie!
        gfx.oval(cx + size * 0.25f, bottom - 6, 5f, 5f, 0xFFFF6A9A.toInt())
    }

    /** The plushie shelves: 2 rows of 10, x positions in the 427-wide room. */
    fun shelfSpot(i: Int): Pair<Float, Float> {
        val row = i / 10
        val col = i % 10
        return (258f + col * 17f) to (if (row == 0) 72f else 112f)
    }

    fun drawShelves(gfx: Gfx, ox: Float) {
        for (y in listOf(72f, 112f)) {
            gfx.rect(ox + 248f, y, 174f, 4f, 0xFF7A4A20.toInt())
            gfx.rect(ox + 252f, y + 4, 3f, 6f, 0xFF5A3A1A.toInt())
            gfx.rect(ox + 414f, y + 4, 3f, 6f, 0xFF5A3A1A.toInt())
        }
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
