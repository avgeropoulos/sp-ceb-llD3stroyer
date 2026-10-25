package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.argb
import com.roccoquest.game.rainbow
import com.roccoquest.game.shadowText
import kotlin.math.sin

/** The kinds of house the team can build. They all have the same size and the door on the left. */
enum class HouseType(val label: String, val costs: List<Pair<Ore, Int>>, val coins: Int, val note: String) {
    COTTAGE("COTTAGE", listOf(Ore.STONE to 10, Ore.IRON to 2), 0, "A cozy stone house"),
    CABIN("LOG CABIN", listOf(Ore.STONE to 6, Ore.COPPER to 5), 40, "Warm wooden logs"),
    IGLOO("IGLOO", listOf(Ore.STONE to 8, Ore.SAPPHIRE to 2), 50, "A round house made of ice"),
    MUSHROOM("MUSHROOM HOUSE", listOf(Ore.EMERALD to 4, Ore.GOLD to 3), 100, "Live inside a giant mushroom!"),
    CASTLE("MINI CASTLE", listOf(Ore.STONE to 20, Ore.GOLD to 4, Ore.OBSIDIAN to 3), 300, "Towers and flags. Fancy!"),
    TREEHOUSE("TREEHOUSE", listOf(Ore.STONE to 4, Ore.EMERALD to 3), 80, "Up in a big tree!"),
    CANDY("CANDY HOUSE", listOf(Ore.GOLD to 3, Ore.RUBY to 2), 120, "Walls of cookie, roof of icing"),
    BEACH("BEACH HUT", listOf(Ore.STONE to 6, Ore.SAPPHIRE to 1), 60, "Sand, palm leaves and sunshine"),
    SPACE("SPACE DOME", listOf(Ore.IRON to 8, Ore.DIAMOND to 2), 250, "A shiny dome with a rocket"),
    CRYSTAL("CRYSTAL PALACE", listOf(Ore.AMETHYST to 4, Ore.DIAMOND to 3), 400, "Sparkly purple crystal towers"),
    LIGHTHOUSE("LIGHTHOUSE", listOf(Ore.STONE to 12, Ore.GOLD to 2), 180, "A striped tower with a spinning light"),
    WINDMILL("WINDMILL", listOf(Ore.STONE to 8, Ore.COPPER to 4), 140, "Big sails that go round and round"),
    PIRATE("PIRATE SHIP", listOf(Ore.COPPER to 6, Ore.GOLD to 4), 350, "Live on a pirate ship! Arrr!"),
    BARN("RED BARN", listOf(Ore.STONE to 6, Ore.IRON to 3), 90, "A farm barn with a big X door"),
    PYRAMID("PYRAMID", listOf(Ore.GOLD to 6, Ore.RUBY to 2), 450, "A golden pyramid from the desert"),
    HAUNTED("HAUNTED HOUSE", listOf(Ore.STONE to 10, Ore.OBSIDIAN to 2), 220, "Creaky, spooky and full of bats"),
    CLOUD("CLOUD CASTLE", listOf(Ore.DIAMOND to 3, Ore.SAPPHIRE to 3), 500, "A castle floating on a cloud"),
    UNDERSEA("UNDERSEA DOME", listOf(Ore.SAPPHIRE to 5, Ore.IRON to 4), 380, "A glass bubble with fish all around"),
    ROBOT("ROBOT HOUSE", listOf(Ore.IRON to 10, Ore.COPPER to 4), 320, "Beep boop! A house that's a robot"),
    BOUNCY("BOUNCY CASTLE", listOf(Ore.RUBY to 2, Ore.EMERALD to 2), 160, "Boing boing! A blow-up castle"),
    VOLCANO("VOLCANO LAIR", listOf(Ore.OBSIDIAN to 5, Ore.RUBY to 3), 550, "A secret lair inside a volcano"),
    TOYBLOCK("TOY BLOCK HOUSE", listOf(Ore.STONE to 6, Ore.GOLD to 2), 110, "Built from giant toy blocks"),
    TEEPEE("TEEPEE", listOf(Ore.STONE to 3, Ore.COPPER to 2), 30, "A cozy tent with poles on top"),
    // The second batch (drawn in MoreHouses.kt)
    ICE_CREAM("ICE CREAM SHOP", listOf(Ore.STONE to 6, Ore.RUBY to 1), 130, "A giant cone on the roof. Yum!"),
    SHOE("GIANT SHOE", listOf(Ore.STONE to 5, Ore.RUBY to 2), 90, "Live in a big red sneaker"),
    ROCKET("ROCKET SHIP", listOf(Ore.IRON to 10, Ore.DIAMOND to 2), 420, "3, 2, 1... home sweet home!"),
    DINO_EGG("DINO EGG", listOf(Ore.STONE to 8, Ore.EMERALD to 3), 200, "A giant egg. Someone's peeking out!"),
    LILY_PAD("LILY PAD HUT", listOf(Ore.EMERALD to 2, Ore.SAPPHIRE to 1), 70, "A flower hut on a pond, with a frog"),
    PUMPKIN("PUMPKIN HOUSE", listOf(Ore.STONE to 4, Ore.COPPER to 3), 100, "A glowing jack-o-lantern home"),
    CUPCAKE("CUPCAKE TOWER", listOf(Ore.GOLD to 2, Ore.RUBY to 2), 150, "Pink frosting and a cherry on top"),
    PAGODA("PAGODA", listOf(Ore.STONE to 12, Ore.GOLD to 3), 280, "Three roofs and swinging lanterns"),
    ADOBE("DESERT ADOBE", listOf(Ore.STONE to 10, Ore.COPPER to 2), 60, "A warm clay house with a ladder"),
    STILT("STILT HOUSE", listOf(Ore.STONE to 6, Ore.COPPER to 3), 110, "Up on legs over the water"),
    HOUSEBOAT("HOUSEBOAT", listOf(Ore.IRON to 4, Ore.SAPPHIRE to 2), 240, "A floating home with a flag"),
    TRAIN("TRAIN CAR HOUSE", listOf(Ore.IRON to 8, Ore.COPPER to 3), 210, "Choo choo! A red caboose"),
    TEAPOT("GIANT TEAPOT", listOf(Ore.STONE to 6, Ore.SAPPHIRE to 2), 170, "A china teapot with steam"),
    CLOCK_TOWER("CLOCK TOWER", listOf(Ore.STONE to 14, Ore.GOLD to 3), 330, "A ticking clock and a little bell"),
    SNOW_GLOBE("SNOW GLOBE", listOf(Ore.DIAMOND to 2, Ore.SAPPHIRE to 2), 360, "It's always snowing inside!"),
    BEEHIVE("BEEHIVE", listOf(Ore.GOLD to 4), 140, "Buzz buzz! Sweet as honey"),
    BALLOON("HOT AIR BALLOON", listOf(Ore.COPPER to 4, Ore.RUBY to 2), 260, "A basket home under a big balloon"),
    FAIRY("FAIRY FLOWER", listOf(Ore.AMETHYST to 3, Ore.EMERALD to 2), 300, "A tulip house with a tiny fairy"),
    HILL("HILL HOUSE", listOf(Ore.STONE to 8, Ore.EMERALD to 2), 160, "A round door in a grassy hill"),
    GLASS("GLASS HOUSE", listOf(Ore.IRON to 6, Ore.DIAMOND to 3), 480, "Super modern with big windows"),
    SKYSCRAPER("SKYSCRAPER", listOf(Ore.IRON to 12, Ore.GOLD to 4, Ore.DIAMOND to 2), 600, "So tall! With a garden on top"),
    CACTUS("CACTUS HOUSE", listOf(Ore.EMERALD to 3, Ore.STONE to 4), 80, "Prickly outside, cozy inside"),
    STUMP("TREE STUMP", listOf(Ore.STONE to 3, Ore.EMERALD to 1), 45, "A cozy home in an old stump"),
}

/**
 * Furniture to decorate the inside of the house. Each piece has its own spot on its [floor].
 * Fancier pieces unlock after beating [unlock] levels.
 */
enum class Furniture(val label: String, val costs: List<Pair<Ore, Int>>, val coins: Int, val floor: Int = 0, val unlock: Int = 0) {
    RUG("RUG", emptyList(), 25),
    LAMP("LAMP", listOf(Ore.GOLD to 1), 15),
    TABLE("TABLE", listOf(Ore.STONE to 4), 30),
    CHAIRS("CHAIRS", listOf(Ore.STONE to 4), 30),
    COUCH("COUCH", listOf(Ore.EMERALD to 1), 70),
    BOOKSHELF("BOOKSHELF", listOf(Ore.STONE to 6), 45),
    PLANT("PLANT", listOf(Ore.EMERALD to 1), 15),
    TV("TV", listOf(Ore.IRON to 3, Ore.COPPER to 2), 90),
    FIREPLACE("FIREPLACE", listOf(Ore.STONE to 10), 80),
    PAINTING("PAINTING", listOf(Ore.GOLD to 1), 35),
    FISH_TANK("FISH TANK", listOf(Ore.DIAMOND to 1), 70),
    CLAW_MACHINE("CLAW MACHINE", listOf(Ore.IRON to 4, Ore.GOLD to 2), 150),
    // Floor 2: the playroom
    TOY_CHEST("TOY CHEST", listOf(Ore.STONE to 4), 30, 1, 0),
    BUNK_BEDS("BUNK BEDS", listOf(Ore.IRON to 2), 90, 1, 5),
    ARCADE("ARCADE GAME", listOf(Ore.IRON to 3, Ore.COPPER to 2), 150, 1, 10),
    TRAMPOLINE("TRAMPOLINE", listOf(Ore.IRON to 2), 80, 1, 15),
    BALL_PIT("BALL PIT", emptyList(), 100, 1, 20),
    TOY_TRAIN("TOY TRAIN", listOf(Ore.IRON to 2), 70, 1, 25),
    // Floor 3: the top floor
    TELESCOPE("TELESCOPE", listOf(Ore.GOLD to 1, Ore.DIAMOND to 1), 90, 2, 30),
    PIANO("PIANO", listOf(Ore.STONE to 4), 160, 2, 35),
    TROPHY_CASE("TROPHY CASE", listOf(Ore.GOLD to 3), 110, 2, 40),
    HOT_TUB("HOT TUB", listOf(Ore.SAPPHIRE to 2), 200, 2, 45),
    DISCO_BALL("DISCO BALL", listOf(Ore.DIAMOND to 1), 90, 2, 50),
    POOL_TABLE("POOL TABLE", listOf(Ore.EMERALD to 2), 170, 2, 60),
    // The second batch (the rooms got wider to fit them)
    FRIDGE("FRIDGE", listOf(Ore.IRON to 3), 60, 0, 0),
    STOVE("STOVE", listOf(Ore.IRON to 2, Ore.COPPER to 1), 55, 0, 8),
    WALL_CLOCK("WALL CLOCK", listOf(Ore.GOLD to 1), 20, 0, 12),
    DOG_BED("PUPPY BED", emptyList(), 35, 0, 18),
    SLIDE("SLIDE", listOf(Ore.IRON to 3), 90, 1, 22),
    ROCKING_HORSE("ROCKING HORSE", listOf(Ore.STONE to 3), 45, 1, 28),
    BEAN_BAG("BEAN BAG", emptyList(), 30, 1, 32),
    STAR_LIGHTS("STAR LIGHTS", listOf(Ore.COPPER to 2), 40, 1, 36),
    GAMING_PC("GAMING PC", listOf(Ore.IRON to 2, Ore.DIAMOND to 1), 180, 2, 55),
    FOUNTAIN("FOUNTAIN", listOf(Ore.STONE to 6, Ore.SAPPHIRE to 1), 150, 2, 65),
    ROCKET("ROCKET MODEL", listOf(Ore.IRON to 4), 120, 2, 75),
    CHANDELIER("CHANDELIER", listOf(Ore.GOLD to 3, Ore.DIAMOND to 1), 200, 2, 85),
    // Shelves for even more plushies (12 each), to put anywhere you like
    PLUSH_SHELF("PLUSHIE SHELF", listOf(Ore.STONE to 4), 50, 1, 0),
    PLUSH_SHELF2("BIG PLUSHIE SHELF", listOf(Ore.STONE to 6), 80, 2, 10),
    PLUSH_SHELF3("PLUSHIE TOWER", listOf(Ore.STONE to 6, Ore.GOLD to 1), 110, 0, 20),
    // Turns everybody into babies (and back again)
    BABY_MACHINE("BABY MACHINE", listOf(Ore.IRON to 3, Ore.DIAMOND to 1), 200, 0, 0),
    // Floor 4: the sky room
    MOVIE_SCREEN("MOVIE SCREEN", listOf(Ore.IRON to 2), 140, 3, 0),
    DRUM_SET("DRUM SET", listOf(Ore.IRON to 2), 110, 3, 0),
    CANDY_MACHINE("CANDY MACHINE", listOf(Ore.GOLD to 1), 45, 3, 0),
    HAMMOCK("HAMMOCK", emptyList(), 50, 3, 0),
    BUBBLE_MACHINE("BUBBLE MACHINE", listOf(Ore.SAPPHIRE to 1), 60, 3, 0),
    TREASURE_CHEST("TREASURE CHEST", listOf(Ore.GOLD to 2), 120, 3, 0),
    ;

    /** The plushie shelves, in order (the plushies on them come after the ones on the wall shelves). */
    val plushShelf get() = when (this) { PLUSH_SHELF -> 0; PLUSH_SHELF2 -> 1; PLUSH_SHELF3 -> 2; else -> -1 }
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
    /** Houses can have up to 10 floors (and a roof on top). */
    const val MAX_FLOORS = 10
    /** Where the stairs are (at the right end of the room). */
    const val STAIRS_X = 870f

    /** The room is wider than the screen: walk around and the view follows you. */
    const val ROOM_W = 900f
    const val FLOOR_Y = 176f

    // ------------------------------------------------------------------ Outside

    fun drawHouse(gfx: Gfx, type: HouseType, x: Float, bottom: Float, t: Float, paint: Int = 0, floors: Int = 1) {
        // Painted houses really are painted: walls and roof turn the new color, but keep their light and shadow.
        val pg = if (paint != 0) PaintGfx(gfx, paint) else gfx
        // Extra floors stack under the house, each with two windows (tall houses squeeze them a little).
        val storyH = storyHeight(floors)
        val lift = (floors - 1) * storyH
        val wh = minOf(12f, storyH - 4f)
        for (k in 0 until floors - 1) {
            val sy = bottom - (k + 1) * storyH
            pg.rect(x, sy, 72f, storyH, 0xFFD8C8A8.toInt())
            pg.rect(x, sy, 72f, 2f, 0xFFA89878.toInt())
            for (wx in listOf(x + 28, x + 50)) {
                gfx.rect(wx, sy + (storyH - wh) / 2 + 1f, 14f, wh, 0xFF5A3A1A.toInt())
                gfx.rect(wx + 1.5f, sy + (storyH - wh) / 2 + 2.5f, 11f, wh - 3f, 0xFFFFE8A0.toInt())
            }
        }
        drawHouseBody(pg, type, x, bottom - lift, t)
        // Every house has its door in the same spot, on the left.
        if (type != HouseType.IGLOO || floors > 1) {
            gfx.rect(x + 6, bottom - 26, 14f, 26f, if (type == HouseType.CASTLE) 0xFF5A3A1A.toInt() else 0xFF7A4A20.toInt())
            gfx.oval(x + 16, bottom - 14, 2.5f, 2.5f, 0xFFFFD21F.toInt())
        } else {
            gfx.oval(x + 6, bottom - 24, 14f, 26f, 0xFF4A6A8A.toInt())
        }
    }

    /** How tall each extra floor looks from outside: up to 4 floors full size, then squeezed so 10 still fit. */
    fun storyHeight(floors: Int) = if (floors <= 4) STORY_H else 3 * STORY_H / (floors - 1) + 4f

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
            HouseType.LIGHTHOUSE -> {
                // A red and white striped tower with a light that sweeps around.
                gfx.rect(x, top + 10, 72f, 30f, 0xFFF4F4F4.toInt())
                gfx.rect(x + 40, top - 46, 26f, 86f, 0xFFF4F4F4.toInt())
                for (i in 0 until 4) gfx.rect(x + 40, top - 40 + i * 22f, 26f, 10f, 0xFFE84A4A.toInt())
                gfx.rect(x + 37, top - 50, 32f, 4f, 0xFF3A3A44.toInt())
                gfx.rect(x + 42, top - 62, 22f, 12f, 0xFFFFE89A.toInt())
                gfx.poly(floatArrayOf(x + 38, x + 53, x + 68), floatArrayOf(top - 62, top - 74, top - 62), 0xFFE84A4A.toInt())
                val beam = sin(t * 2f)
                gfx.poly(floatArrayOf(x + 53, x + 53 + beam * 60, x + 53 + beam * 60), floatArrayOf(top - 56, top - 66, top - 46), 0x55FFF2A0)
                gfx.poly(floatArrayOf(x - 4, x + 20, x + 44), floatArrayOf(top + 12, top - 6, top + 12), 0xFF3A6AC8.toInt())
            }
            HouseType.WINDMILL -> {
                gfx.poly(floatArrayOf(x + 4, x + 18, x + 54, x + 68), floatArrayOf(top + 40, top - 20, top - 20, top + 40), 0xFFF4E8C8.toInt())
                gfx.poly(floatArrayOf(x + 14, x + 36, x + 58), floatArrayOf(top - 18, top - 38, top - 18), 0xFF8A3A2A.toInt())
                gfx.rect(x + 30, top, 12f, 12f, 0xFF7FC8FF.toInt())
                // The sails, turning
                val cx = x + 36; val cy = top - 18
                for (k in 0 until 4) {
                    val a = t * 1.5f + k * 1.5708f
                    val ex = cx + kotlin.math.cos(a) * 40f; val ey = cy + sin(a) * 40f
                    val nx = -sin(a) * 6f; val ny = kotlin.math.cos(a) * 6f
                    gfx.poly(floatArrayOf(cx, ex, ex + nx, cx + nx * 0.3f), floatArrayOf(cy, ey, ey + ny, cy + ny * 0.3f), 0xFFF4F4F4.toInt())
                    gfx.poly(floatArrayOf(cx, ex), floatArrayOf(cy, ey), 0xFF7A4A20.toInt())
                }
                gfx.oval(cx - 4, cy - 4, 8f, 8f, 0xFF5A3A1A.toInt())
            }
            HouseType.PIRATE -> {
                // The hull, masts and a skull flag
                gfx.poly(floatArrayOf(x - 10, x + 82, x + 72, x), floatArrayOf(top + 8, top + 8, bottom, bottom), 0xFF7A4A20.toInt())
                gfx.rect(x - 10, top + 8, 92f, 4f, 0xFFE8C83A.toInt())
                for (i in 0 until 4) gfx.oval(x + 30 + i * 11f, top + 18, 6f, 6f, 0xFF2A1808.toInt())
                gfx.rect(x + 36, top - 50, 3f, 58f, 0xFF5A3A1A.toInt())
                gfx.poly(floatArrayOf(x + 14, x + 62, x + 58, x + 18), floatArrayOf(top - 40, top - 40, top - 4, top - 4), 0xFFF4ECD8.toInt())
                gfx.rect(x + 39, top - 50, 16f + sin(t * 4) * 1.5f, 10f, 0xFF1A1A1A.toInt())
                gfx.oval(x + 44, top - 48, 5f, 5f, 0xFFF4F4F4.toInt())
            }
            HouseType.BARN -> {
                gfx.rect(x, top, 72f, 40f, 0xFFC83A2A.toInt())
                gfx.poly(floatArrayOf(x - 6, x + 10, x + 36, x + 62, x + 78), floatArrayOf(top + 2, top - 16, top - 26, top - 16, top + 2), 0xFF5A3A2A.toInt())
                gfx.poly(floatArrayOf(x, x + 12, x + 36, x + 60, x + 72), floatArrayOf(top + 2, top - 12, top - 21, top - 12, top + 2), 0xFFC83A2A.toInt())
                gfx.rect(x + 30, top - 14, 12f, 10f, 0xFFF4F4F4.toInt())
                gfx.rect(x + 34, top + 10, 30f, 30f, 0xFFF4F4F4.toInt())
                gfx.rect(x + 36, top + 12, 26f, 26f, 0xFFC83A2A.toInt())
                gfx.poly(floatArrayOf(x + 36, x + 38, x + 62, x + 60), floatArrayOf(top + 12, top + 12, top + 38, top + 38), 0xFFF4F4F4.toInt())
                gfx.poly(floatArrayOf(x + 60, x + 62, x + 38, x + 36), floatArrayOf(top + 12, top + 12, top + 38, top + 38), 0xFFF4F4F4.toInt())
            }
            HouseType.PYRAMID -> {
                gfx.poly(floatArrayOf(x - 14, x + 36, x + 86), floatArrayOf(bottom, top - 34, bottom), 0xFFE8C860.toInt())
                gfx.poly(floatArrayOf(x + 36, x + 86, x + 50), floatArrayOf(top - 34, bottom, bottom), 0xFFC8A040.toInt())
                for (i in 1 until 7) {
                    val y = top - 34 + i * 11f
                    val half = 50f * i * 11f / 74f
                    gfx.rect(x + 36 - half, y, half * 2, 1f, 0xFFB8903A.toInt())
                }
                gfx.oval(x + 31, top - 20, 10f, 6f, 0xFF3AA8E8.toInt())
                gfx.oval(x + 34, top - 19, 4f, 4f, 0xFF1A1A1A.toInt())
                if ((t * 2).toInt() % 2 == 0) gfx.oval(x + 34, top - 38, 4f, 4f, 0xFFFFF2A0.toInt())
            }
            HouseType.HAUNTED -> {
                gfx.rect(x, top, 72f, 40f, 0xFF4A3A54.toInt())
                gfx.poly(floatArrayOf(x - 6, x + 30, x + 78), floatArrayOf(top + 2, top - 26, top + 2), 0xFF2A1E30.toInt())
                gfx.rect(x + 50, top - 22, 10f, 24f, 0xFF3A2A44.toInt())
                for (wx in listOf(x + 28, x + 48)) { gfx.rect(wx, top + 8, 12f, 12f, if ((t * 2).toInt() % 3 == 0) 0xFFFFE87A.toInt() else 0xFF1A1420.toInt()) }
                for (k in 0 until 2) { val bx = x + 20 + k * 30f + sin(t * 3 + k) * 6; gfx.poly(floatArrayOf(bx - 6, bx, bx + 6, bx), floatArrayOf(top - 30, top - 27, top - 30, top - 25), 0xFF1A1420.toInt()) }
            }
            HouseType.CLOUD -> {
                for (k in 0 until 5) gfx.oval(x - 12 + k * 20f, bottom - 14, 30f, 18f, 0xFFF4F8FF.toInt())
                gfx.rect(x + 6, top + 4, 60f, 26f, 0xFFDDEEFF.toInt())
                for (tx in listOf(x + 2, x + 54)) { gfx.rect(tx, top - 14, 16f, 44f, 0xFFE8F0FF.toInt()); gfx.poly(floatArrayOf(tx - 2, tx + 8, tx + 18), floatArrayOf(top - 14, top - 30, top - 14), 0xFF8AC8FF.toInt()) }
                gfx.oval(x + 30, top + 8, 12f, 12f, 0xFFFFE87A.toInt())
            }
            HouseType.UNDERSEA -> {
                gfx.oval(x - 6, top - 20, 84f, 70f, 0x887FD8FF.toInt())
                gfx.oval(x - 6, top - 20, 84f, 70f, 0x22FFFFFF)
                gfx.rect(x + 4, top + 10, 64f, 30f, 0xFFE8D8A0.toInt())
                for (k in 0 until 3) { val fx = x + 10 + ((t * 20 + k * 25) % 60); gfx.oval(fx, top - 6 + k * 9f, 8f, 5f, listOf(0xFFFF8A3A, 0xFFF4D84A, 0xFFE84A6A)[k].toInt()) }
                gfx.rect(x - 6, bottom - 4, 84f, 4f, 0xFFB8A070.toInt())
            }
            HouseType.ROBOT -> {
                gfx.rect(x + 4, top - 4, 64f, 44f, 0xFF9AA4B8.toInt())
                gfx.rect(x + 16, top - 28, 40f, 24f, 0xFFB8C0CC.toInt())
                gfx.oval(x + 22, top - 22, 10f, 10f, if ((t * 3).toInt() % 2 == 0) 0xFF4AE8F0.toInt() else 0xFF2A8A90.toInt())
                gfx.oval(x + 40, top - 22, 10f, 10f, if ((t * 3).toInt() % 2 == 0) 0xFF4AE8F0.toInt() else 0xFF2A8A90.toInt())
                gfx.rect(x + 35, top - 40, 2f, 12f, 0xFF5A6070.toInt()); gfx.oval(x + 32, top - 44, 8f, 8f, 0xFFE84A4A.toInt())
                gfx.rect(x - 4, top + 6, 8f, 20f, 0xFF7A8494.toInt()); gfx.rect(x + 68, top + 6, 8f, 20f, 0xFF7A8494.toInt())
            }
            HouseType.BOUNCY -> {
                val sq = sin(t * 6) * 1.5f
                gfx.rect(x, top + sq, 72f, 40f - sq, 0xFF5AB0F0.toInt())
                for (tx in listOf(x - 4, x + 26, x + 56)) { gfx.oval(tx, top - 16 + sq, 20f, 26f, listOf(0xFFE84A4A, 0xFFF4D84A, 0xFF4AB85A)[((tx - x) / 30).toInt().coerceIn(0, 2)].toInt()) }
                for (k in 0 until 6) gfx.oval(x + 2 + k * 12f, top + 30 + sq, 10f, 8f, 0xFFE84A4A.toInt())
            }
            HouseType.VOLCANO -> {
                gfx.poly(floatArrayOf(x - 14, x + 20, x + 52, x + 86), floatArrayOf(bottom, top - 30, top - 30, bottom), 0xFF4A2A20.toInt())
                gfx.poly(floatArrayOf(x + 20, x + 28, x + 44, x + 52), floatArrayOf(top - 30, top - 20, top - 20, top - 30), 0xFFFF6A2A.toInt())
                for (k in 0 until 3) { val p = (t * 0.5f + k / 3f) % 1f; gfx.oval(x + 30 + sin(p * 6) * 4, top - 34 - p * 30, 8f + p * 8, 8f + p * 8, argb(((1 - p) * 160).toInt(), 0x8A8A8A)) }
                gfx.rect(x + 40, top + 10, 14f, 12f, 0xFFFF8A3A.toInt())
            }
            HouseType.TOYBLOCK -> {
                val c = listOf(0xFFE84A4A, 0xFF3A7AE8, 0xFFF4C83A, 0xFF4AB85A)
                for (k in 0 until 8) gfx.rect(x + (k % 4) * 18f, top + (k / 4) * 20f, 18f, 20f, c[(k + k / 4) % 4].toInt())
                for (k in 0 until 4) gfx.rect(x + k * 18f, top + (k % 2) * 20f, 18f, 1f, 0x55000000)
                gfx.poly(floatArrayOf(x - 4, x + 36, x + 76), floatArrayOf(top, top - 24, top), 0xFFE84A4A.toInt())
                gfx.text("ABC", x + 50, top + 16, 8f, 0xFFFFFFFF.toInt(), 1)
            }
            HouseType.TEEPEE -> {
                gfx.poly(floatArrayOf(x - 4, x + 36, x + 76), floatArrayOf(bottom, top - 30, bottom), 0xFFF0DCB0.toInt())
                for (k in 0 until 3) gfx.rect(x + 8 + k * 2f, top + 6 + k * 10f, 56f - k * 4f, 3f, listOf(0xFFC86A3A, 0xFF3A8AC8, 0xFFC86A3A)[k].toInt())
                for (k in 0 until 3) gfx.poly(floatArrayOf(x + 32 + k * 4f, x + 33 + k * 4f, x + 28 + k * 6f, x + 27 + k * 6f), floatArrayOf(top - 26, top - 26, top - 40, top - 40), 0xFF7A4A20.toInt())
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
            else -> MoreHouses.drawBody(gfx, type, x, bottom, t)
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
    private fun floorColor(type: HouseType): Int = when (type) {
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
        HouseType.LIGHTHOUSE -> 0xFF8A94A4.toInt()
        HouseType.WINDMILL -> 0xFFA87A4A.toInt()
        HouseType.PIRATE -> 0xFF7A4A20.toInt()
        HouseType.BARN -> 0xFFC8A04A.toInt()
        HouseType.PYRAMID -> 0xFFD8B860.toInt()
        HouseType.HAUNTED -> 0xFF4A3A44.toInt()
        HouseType.CLOUD -> 0xFFF4F8FF.toInt()
        HouseType.UNDERSEA -> 0xFFE8D8A0.toInt()
        HouseType.ROBOT -> 0xFF7A8494.toInt()
        HouseType.BOUNCY -> 0xFF5AB0F0.toInt()
        HouseType.VOLCANO -> 0xFF3A2A28.toInt()
        HouseType.TOYBLOCK -> 0xFF3A7AE8.toInt()
        HouseType.TEEPEE -> 0xFFB8905A.toInt()
        else -> MoreHouses.room(type).third
    }

    /** The wall, wallpaper stripe and floor colors inside a house (a painted house gets painted walls). */
    fun roomColors(type: HouseType, paint: Int = 0): Triple<Int, Int, Int> {
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
            HouseType.LIGHTHOUSE -> 0xFFF4F4F4.toInt() to 0xFFE84A4A.toInt()
            HouseType.WINDMILL -> 0xFFF4E8C8.toInt() to 0xFFD8B88A.toInt()
            HouseType.PIRATE -> 0xFFA87A4A.toInt() to 0xFF8A5A2A.toInt()
            HouseType.BARN -> 0xFFC84A3A.toInt() to 0xFFA83A2A.toInt()
            HouseType.PYRAMID -> 0xFFF0D890.toInt() to 0xFFC8A860.toInt()
            HouseType.HAUNTED -> 0xFF5A4A6A.toInt() to 0xFF3A2A4A.toInt()
            HouseType.CLOUD -> 0xFFDDEEFF.toInt() to 0xFFFFFFFF.toInt()
            HouseType.UNDERSEA -> 0xFF4A9AD8.toInt() to 0xFF8AD0F8.toInt()
            HouseType.ROBOT -> 0xFFB8C0CC.toInt() to 0xFF8A94A4.toInt()
            HouseType.BOUNCY -> 0xFFFFE07A.toInt() to 0xFFFF6A6A.toInt()
            HouseType.VOLCANO -> 0xFF5A2A20.toInt() to 0xFFFF6A2A.toInt()
            HouseType.TOYBLOCK -> 0xFFFFF0C8.toInt() to 0xFFE84A4A.toInt()
            HouseType.TEEPEE -> 0xFFF0DCB0.toInt() to 0xFFC86A3A.toInt()
            else -> MoreHouses.room(type).let { it.first to it.second }
        }
        val wall = if (paint != 0) mix(paint, 0xFFFFFFFF.toInt(), 0.35f) else normalWall
        val stripe = if (paint != 0) mix(paint, 0xFF000000.toInt(), 0.15f) else normalStripe
        return Triple(wall, stripe, floorColor(type))
    }

    fun drawRoom(gfx: Gfx, type: HouseType, ox: Float, viewW: Float, viewH: Float, paint: Int = 0) {
        val (wall, stripe, _) = roomColors(type, paint)
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
            HouseType.LIGHTHOUSE -> for (i in 0 until 3) gfx.rect(x0, 40f + i * 50f, ROOM_W, 14f, stripe)
            HouseType.WINDMILL, HouseType.BARN -> { var x = x0; while (x < x1) { gfx.rect(x, 24f, 2f, FLOOR_Y - 24, stripe); x += 22f } }
            HouseType.PIRATE -> for (i in 0 until 12) gfx.rect(x0, 24f + i * 13f, ROOM_W, 1.5f, stripe)
            HouseType.PYRAMID -> for (i in 0 until 10) {
                gfx.rect(x0, 24f + i * 16f, ROOM_W, 1f, stripe)
                var x = x0 + if (i % 2 == 0) 0f else 24f
                while (x < x1) { gfx.rect(x, 24f + i * 16f, 1f, 16f, stripe); x += 48f }
            }
            HouseType.HAUNTED, HouseType.ROBOT -> { var x = x0; while (x < x1) { gfx.rect(x, 24f, 2f, FLOOR_Y - 24, stripe); x += 40f } }
            HouseType.CLOUD -> for (i in 0 until 14) gfx.oval(x0 + (i * 67f) % ROOM_W, 30f + (i * 41f) % 120f, 40f, 16f, stripe)
            HouseType.UNDERSEA -> for (i in 0 until 30) gfx.oval(x0 + (i * 61f) % ROOM_W, 30f + (i * 37f) % 140f, 5f, 5f, stripe)
            HouseType.BOUNCY, HouseType.TEEPEE -> { var x = x0; var k = 0; while (x < x1) { if (k++ % 2 == 0) gfx.rect(x, 24f, 30f, FLOOR_Y - 24, stripe); x += 30f } }
            HouseType.VOLCANO -> for (i in 0 until 12) gfx.rect(x0 + (i * 79f) % ROOM_W, 24f, 3f, 40f + (i * 23f) % 90f, stripe)
            HouseType.TOYBLOCK -> for (i in 0 until 18) gfx.rect(x0 + (i * 53f) % ROOM_W, 40f + (i * 29f) % 110f, 18f, 18f, listOf(0xFFE84A4A, 0xFF3A7AE8, 0xFFF4C83A, 0xFF4AB85A)[i % 4].toInt())
            HouseType.CRYSTAL -> for (i in 0 until 30) {
                val cx = x0 + (i * 59f) % ROOM_W
                val cy = 36f + (i * 43f) % 120f
                gfx.poly(floatArrayOf(cx, cx + 4, cx + 8), floatArrayOf(cy + 10, cy, cy + 10), stripe)
            }
            else -> MoreHouses.wallpaper(gfx, type, x0, x1, stripe)
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
        val floor = floorColor(type)
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
        Furniture.DISCO_BALL -> 660f
        Furniture.POOL_TABLE -> 560f
        Furniture.FRIDGE -> 704f
        Furniture.STOVE -> 740f
        Furniture.WALL_CLOCK -> 744f
        Furniture.DOG_BED -> 790f
        Furniture.SLIDE -> 700f
        Furniture.ROCKING_HORSE -> 785f
        Furniture.BEAN_BAG -> 830f
        Furniture.STAR_LIGHTS -> 0f
        Furniture.GAMING_PC -> 700f
        Furniture.FOUNTAIN -> 770f
        Furniture.ROCKET -> 832f
        Furniture.CHANDELIER -> 190f
        Furniture.PLUSH_SHELF -> 278f
        Furniture.PLUSH_SHELF2 -> 470f
        Furniture.PLUSH_SHELF3 -> 606f
        Furniture.BABY_MACHINE -> 610f
        Furniture.MOVIE_SCREEN -> 120f
        Furniture.DRUM_SET -> 250f
        Furniture.CANDY_MACHINE -> 340f
        Furniture.HAMMOCK -> 420f
        Furniture.BUBBLE_MACHINE -> 540f
        Furniture.TREASURE_CHEST -> 640f
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
            Furniture.FRIDGE, Furniture.STOVE, Furniture.WALL_CLOCK, Furniture.DOG_BED, Furniture.SLIDE, Furniture.ROCKING_HORSE,
            Furniture.BEAN_BAG, Furniture.STAR_LIGHTS, Furniture.GAMING_PC, Furniture.FOUNTAIN, Furniture.ROCKET, Furniture.CHANDELIER,
            Furniture.PLUSH_SHELF, Furniture.PLUSH_SHELF2, Furniture.PLUSH_SHELF3, Furniture.BABY_MACHINE, Furniture.MOVIE_SCREEN,
            Furniture.DRUM_SET, Furniture.CANDY_MACHINE, Furniture.HAMMOCK, Furniture.BUBBLE_MACHINE, Furniture.TREASURE_CHEST ->
                drawMore(gfx, f, x, fy, t)
            Furniture.POOL_TABLE -> {
                gfx.rect(x, fy - 26, 90f, 8f, 0xFF6A3A1A.toInt())
                gfx.rect(x + 3, fy - 28, 84f, 4f, 0xFF2E9A4A.toInt())
                gfx.rect(x + 6, fy - 18, 6f, 18f, 0xFF6A3A1A.toInt()); gfx.rect(x + 78, fy - 18, 6f, 18f, 0xFF6A3A1A.toInt())
                for (i in 0 until 4) gfx.oval(x + 30 + i * 6f, fy - 32, 5f, 5f, listOf(0xFFFFD82A, 0xFFE84A4A, 0xFF3A6AC8, 0xFFFFFFFF)[i].toInt())
            }
        }
    }

    private fun drawMore(gfx: Gfx, f: Furniture, x: Float, fy: Float, t: Float) {
        when (f) {
            Furniture.FRIDGE -> {
                gfx.rect(x, fy - 72, 30f, 72f, 0xFFE8EEF4.toInt())
                gfx.rect(x, fy - 48, 30f, 2f, 0xFFA8B0BC.toInt())
                gfx.rect(x + 24, fy - 66, 2f, 12f, 0xFF8A94A4.toInt()); gfx.rect(x + 24, fy - 40, 2f, 16f, 0xFF8A94A4.toInt())
                gfx.oval(x + 6, fy - 64, 6f, 6f, 0xFFE84A4A.toInt()); gfx.rect(x + 5, fy - 36, 8f, 6f, 0xFFFFD21F.toInt())
            }
            Furniture.STOVE -> {
                gfx.rect(x, fy - 32, 34f, 32f, 0xFF3A3A44.toInt())
                gfx.rect(x + 4, fy - 24, 26f, 16f, 0xFF1A1A20.toInt())
                gfx.rect(x - 1, fy - 34, 36f, 3f, 0xFF5A5A64.toInt())
                gfx.oval(x + 4, fy - 42, 14f, 9f, 0xFF8A94A4.toInt())
                for (i in 0 until 2) {
                    val p = (t * 0.7f + i * 0.5f) % 1f
                    gfx.oval(x + 8 + sin(p * 6) * 2, fy - 46 - p * 16, 5f, 5f, argb(((1 - p) * 150).toInt(), 0xFFFFFF))
                }
            }
            Furniture.WALL_CLOCK -> {
                gfx.oval(x, 40f, 26f, 26f, 0xFF7A4A20.toInt())
                gfx.oval(x + 2, 42f, 22f, 22f, 0xFFFFF8E8.toInt())
                val a = t * 0.5f
                gfx.poly(floatArrayOf(x + 12, x + 14, x + 13 + kotlin.math.cos(a) * 9), floatArrayOf(53f, 53f, 53f + sin(a) * 9), 0xFF1A1A1A.toInt())
                gfx.rect(x + 12.5f, 46f, 1f, 7f, 0xFF1A1A1A.toInt())
            }
            Furniture.DOG_BED -> {
                gfx.oval(x, fy - 14, 50f, 16f, 0xFF3A6AC8.toInt())
                gfx.oval(x + 4, fy - 12, 42f, 10f, 0xFF8AB8F0.toInt())
                // A sleepy puppy curled up
                gfx.oval(x + 12, fy - 20, 24f, 12f, 0xFFC8904A.toInt())
                gfx.oval(x + 30, fy - 22, 10f, 9f, 0xFFC8904A.toInt())
                gfx.oval(x + 34, fy - 23, 4f, 6f, 0xFF7A4A20.toInt())
                gfx.rect(x + 32, fy - 18, 4f, 1f, 0xFF1A1A1A.toInt())
                val z = (t * 0.8f) % 1f
                gfx.shadowText("z", x + 40 + z * 6, fy - 26 - z * 14, 6f + z * 3, argb(((1 - z) * 255).toInt(), 0xFFFFFF))
            }
            Furniture.SLIDE -> {
                gfx.rect(x, fy - 60, 4f, 60f, 0xFFE84A4A.toInt()); gfx.rect(x + 14, fy - 60, 4f, 60f, 0xFFE84A4A.toInt())
                for (i in 0 until 5) gfx.rect(x, fy - 52 + i * 11f, 18f, 2f, 0xFFE84A4A.toInt())
                gfx.poly(floatArrayOf(x + 16, x + 22, x + 72, x + 66), floatArrayOf(fy - 62, fy - 58, fy - 2, fy - 2), 0xFFFFD21F.toInt())
                gfx.rect(x - 2, fy - 64, 22f, 4f, 0xFF3A6AC8.toInt())
            }
            Furniture.ROCKING_HORSE -> {
                val rock = sin(t * 2) * 2f
                gfx.oval(x - 2, fy - 8 + rock * 0.3f, 44f, 10f, 0xFF7A4A20.toInt())
                gfx.rect(x + 8, fy - 22 + rock, 3f, 16f, 0xFF8A5A2A.toInt()); gfx.rect(x + 28, fy - 22 - rock, 3f, 16f, 0xFF8A5A2A.toInt())
                gfx.oval(x + 4, fy - 30, 32f, 12f, 0xFFF4F4F4.toInt())
                gfx.oval(x + 28, fy - 40 - rock, 12f, 14f, 0xFFF4F4F4.toInt())
                gfx.rect(x + 26, fy - 42 - rock, 4f, 10f, 0xFFE84A4A.toInt())
                gfx.oval(x + 35, fy - 36 - rock, 2f, 2f, 0xFF1A1A1A.toInt())
                gfx.rect(x + 2, fy - 28, 6f, 10f, 0xFFE84A4A.toInt())
            }
            Furniture.BEAN_BAG -> {
                gfx.oval(x, fy - 22, 36f, 24f, 0xFF8A3AC8.toInt())
                gfx.oval(x + 6, fy - 18, 16f, 10f, 0xFFAA6AE8.toInt())
            }
            Furniture.STAR_LIGHTS -> {
                var lx = x + 20f
                var i = 0
                while (lx < x + ROOM_W - 20f) {
                    val sag = sin((lx - x) / 60f * 3.1416f) * 6f
                    gfx.rect(lx, 30f + kotlin.math.abs(sag), 30f, 1f, 0xFF3A5A3A.toInt())
                    val on = ((t * 3).toInt() + i) % 3 != 0
                    gfx.oval(lx + 13, 31f + kotlin.math.abs(sag), 5f, 5f, if (on) solidRainbow(i * 0.13f, 0f) else 0xFF5A5A5A.toInt())
                    lx += 30f
                    i++
                }
            }
            Furniture.GAMING_PC -> {
                gfx.rect(x, fy - 26, 54f, 4f, 0xFF3A3A44.toInt())
                gfx.rect(x + 4, fy - 22, 3f, 22f, 0xFF3A3A44.toInt()); gfx.rect(x + 47, fy - 22, 3f, 22f, 0xFF3A3A44.toInt())
                gfx.rect(x + 10, fy - 54, 34f, 24f, 0xFF1A1A20.toInt())
                gfx.rect(x + 12, fy - 52, 30f, 20f, solidRainbow(t * 0.3f, 0f))
                gfx.sprite(TeamArt.rocco(Power.NONE, (t * 4).toInt() % 3), x + 21, fy - 50, w = 9f, h = 15f)
                gfx.rect(x + 25, fy - 30, 4f, 4f, 0xFF3A3A44.toInt())
                gfx.rect(x + 12, fy - 29, 22f, 3f, 0xFF5A5A64.toInt())
            }
            Furniture.FOUNTAIN -> {
                gfx.oval(x, fy - 14, 44f, 14f, 0xFFB8C0CC.toInt())
                gfx.oval(x + 4, fy - 12, 36f, 8f, 0xFF4AB8F0.toInt())
                gfx.rect(x + 19, fy - 36, 6f, 26f, 0xFFB8C0CC.toInt())
                gfx.oval(x + 12, fy - 40, 20f, 6f, 0xFFB8C0CC.toInt())
                for (i in 0 until 6) {
                    val p = (t * 1.2f + i / 6f) % 1f
                    val side = if (i % 2 == 0) -1 else 1
                    gfx.oval(x + 21 + side * p * 14, fy - 42 - sin(p * 3.1416f) * 10 + p * 28, 3f, 3f, 0xCC8AD8FF.toInt())
                }
            }
            Furniture.ROCKET -> {
                gfx.rect(x + 6, fy - 58, 12f, 46f, 0xFFF4F4F4.toInt())
                gfx.poly(floatArrayOf(x + 6, x + 12, x + 18), floatArrayOf(fy - 58, fy - 72, fy - 58), 0xFFE84A4A.toInt())
                gfx.oval(x + 8, fy - 48, 8f, 8f, 0xFF4AB8F0.toInt())
                gfx.poly(floatArrayOf(x + 6, x, x + 6), floatArrayOf(fy - 26, fy - 10, fy - 14), 0xFFE84A4A.toInt())
                gfx.poly(floatArrayOf(x + 18, x + 24, x + 18), floatArrayOf(fy - 26, fy - 10, fy - 14), 0xFFE84A4A.toInt())
                gfx.rect(x + 4, fy - 12, 16f, 12f, 0xFF5A5A64.toInt())
            }
            Furniture.CHANDELIER -> {
                gfx.rect(x + 29, 24f, 2f, 14f, 0xFFC8A030.toInt())
                gfx.oval(x + 8, 36f, 44f, 10f, 0xFFFFD21F.toInt())
                for (i in 0 until 5) {
                    gfx.rect(x + 10 + i * 9f, 30f, 2f, 7f, 0xFFF4F4F4.toInt())
                    gfx.oval(x + 9.5f + i * 9f, 26f + sin(t * 8 + i), 3f, 4f, 0xFFFFC83A.toInt())
                    gfx.poly(floatArrayOf(x + 9 + i * 9f, x + 11 + i * 9f, x + 13 + i * 9f), floatArrayOf(46f, 52f, 46f), 0xCCDDEEFF.toInt())
                }
                gfx.oval(x - 10, 26f, 80f, 40f, 0x22FFF2A0)
            }
            Furniture.PLUSH_SHELF, Furniture.PLUSH_SHELF2, Furniture.PLUSH_SHELF3 -> {
                val wood = when (f) { Furniture.PLUSH_SHELF -> 0xFF8A5A2A.toInt(); Furniture.PLUSH_SHELF2 -> 0xFFE8A0C8.toInt(); else -> 0xFFC89A2A.toInt() }
                gfx.rect(x, fy - 86, 3f, 86f, wood); gfx.rect(x + 37, fy - 86, 3f, 86f, wood)
                for (k in 0 until 4) gfx.rect(x, fy - 2 - k * 28f, 40f, 3f, wood)
                gfx.rect(x + 3, fy - 84, 34f, 82f, 0x22000000)
            }
            Furniture.BABY_MACHINE -> {
                // A big pink machine with a dish on top that zaps out baby rays.
                gfx.rect(x, fy - 44, 34f, 44f, 0xFFFF9AC8.toInt())
                gfx.rect(x + 2, fy - 42, 30f, 3f, 0xFFFFC8E0.toInt())
                gfx.rect(x + 5, fy - 34, 24f, 14f, 0xFF2A2A44.toInt())
                gfx.text("BABY", x + 17, fy - 24, 6f, solidRainbow(t * 0.5f, 0f), 1)
                for (k in 0 until 3) gfx.oval(x + 7 + k * 8f, fy - 14, 5f, 5f, if ((t * 3).toInt() % 3 == k) 0xFFFFE14D.toInt() else 0xFF8A5AA0.toInt())
                gfx.rect(x + 15, fy - 54, 4f, 10f, 0xFF8A8A9A.toInt())
                gfx.oval(x + 5, fy - 62, 24f, 10f, 0xFFB8C0CC.toInt())
                gfx.oval(x + 14, fy - 60, 6f, 6f, if ((t * 4).toInt() % 2 == 0) 0xFFFF5A9A.toInt() else 0xFFFFE14D.toInt())
                // A little pacifier painted on the side
                gfx.oval(x + 13, fy - 8, 8f, 6f, 0xFFFFFFFF.toInt())
            }
            Furniture.MOVIE_SCREEN -> {
                gfx.rect(x, 30f, 90f, 4f, 0xFF3A3A44.toInt())
                gfx.rect(x + 4, 34f, 82f, 52f, 0xFFF4F4F4.toInt())
                // A cartoon is playing: Rocco runs across, chased by a Grumbler.
                val p = (t * 0.3f) % 1f
                gfx.rect(x + 4, 74f, 82f, 12f, 0xFF7AC86A.toInt())
                gfx.sprite(TeamArt.rocco(Power.NONE, 1 + (t * 6).toInt() % 2), x + 6 + p * 66, 60f, w = 9f, h = 14f)
                gfx.oval(x + p * 66 - 6, 66f, 10f, 8f, 0xFF9A5A2A.toInt())
            }
            Furniture.DRUM_SET -> {
                gfx.oval(x + 12, fy - 26, 26f, 26f, 0xFFE84A4A.toInt())
                gfx.oval(x + 16, fy - 22, 18f, 18f, 0xFFF4F4F4.toInt())
                gfx.rect(x, fy - 22, 12f, 14f, 0xFF3A6AC8.toInt()); gfx.rect(x + 38, fy - 22, 12f, 14f, 0xFF3A6AC8.toInt())
                gfx.rect(x - 2, fy - 24, 16f, 3f, 0xFFF4F4F4.toInt()); gfx.rect(x + 36, fy - 24, 16f, 3f, 0xFFF4F4F4.toInt())
                gfx.rect(x + 46, fy - 44, 1.5f, 44f, 0xFF8A8A9A.toInt())
                gfx.oval(x + 38, fy - 46 + sin(t * 8) * 1f, 18f, 4f, 0xFFE8C83A.toInt())
            }
            Furniture.CANDY_MACHINE -> {
                gfx.rect(x + 4, fy - 20, 18f, 20f, 0xFFE84A4A.toInt())
                gfx.oval(x, fy - 46, 26f, 28f, 0xCCDDEEFF.toInt())
                for (k in 0 until 9) gfx.oval(x + 4 + (k % 3) * 6f, fy - 40 + (k / 3) * 6f, 5f, 5f, solidRainbow(k * 0.11f, 0f))
                gfx.rect(x + 10, fy - 12, 6f, 4f, 0xFFC8C8D0.toInt())
            }
            Furniture.HAMMOCK -> {
                val swing = sin(t * 1.5f) * 3f
                gfx.rect(x, fy - 50, 4f, 50f, 0xFF8A5A2A.toInt()); gfx.rect(x + 66, fy - 50, 4f, 50f, 0xFF8A5A2A.toInt())
                gfx.poly(floatArrayOf(x + 4, x + 35 + swing, x + 66), floatArrayOf(fy - 44, fy - 22, fy - 44), 0xFF4AB8A0.toInt())
                gfx.poly(floatArrayOf(x + 10, x + 35 + swing, x + 60), floatArrayOf(fy - 42, fy - 26, fy - 42), 0xFFF4D84A.toInt())
            }
            Furniture.BUBBLE_MACHINE -> {
                gfx.rect(x + 2, fy - 22, 26f, 22f, 0xFF3A8AD8.toInt())
                gfx.oval(x + 8, fy - 30, 14f, 10f, 0xFF5AA8F0.toInt())
                for (k in 0 until 6) {
                    val p = (t * 0.4f + k / 6f) % 1f
                    gfx.oval(x + 12 + sin(p * 9 + k) * 14, fy - 34 - p * 110, 6f + k % 3 * 2f, 6f + k % 3 * 2f, argb(((1 - p) * 140).toInt(), 0xBFE8FF))
                }
            }
            Furniture.TREASURE_CHEST -> {
                gfx.rect(x, fy - 22, 44f, 22f, 0xFF8A5A2A.toInt())
                gfx.oval(x, fy - 32, 44f, 20f, 0xFF7A4A20.toInt())
                gfx.rect(x, fy - 22, 44f, 3f, 0xFFE8C83A.toInt())
                gfx.rect(x + 19, fy - 24, 6f, 8f, 0xFFE8C83A.toInt())
                if ((t * 2).toInt() % 3 == 0) gfx.shadowText("✦", x + 34, fy - 30, 7f, 0xFFFFE14D.toInt(), 1)
            }
            else -> {}
        }
    }

    /** Where the plushies sit on a plushie shelf at [x]: [k] from 0 to 11 (4 to a shelf, 3 shelves). */
    fun plushShelfSpot(x: Float, k: Int): Pair<Float, Float> = (x + 7f + (k % 4) * 9f) to (FLOOR_Y - 4f - (k / 4) * 28f)

    /** How wide each piece of furniture is (in the room), for finding space to put it. */
    fun width(f: Furniture): Float = when (f) {
        Furniture.RUG -> 150f; Furniture.LAMP -> 24f; Furniture.TABLE -> 36f; Furniture.CHAIRS -> 68f
        Furniture.COUCH -> 66f; Furniture.BOOKSHELF -> 30f; Furniture.FIREPLACE -> 56f; Furniture.PAINTING -> 42f
        Furniture.TV -> 38f; Furniture.FISH_TANK -> 32f; Furniture.PLANT -> 24f; Furniture.CLAW_MACHINE -> 32f
        Furniture.TOY_CHEST -> 44f; Furniture.BUNK_BEDS -> 64f; Furniture.ARCADE -> 34f; Furniture.TRAMPOLINE -> 56f
        Furniture.BALL_PIT -> 70f; Furniture.TOY_TRAIN -> 110f; Furniture.TELESCOPE -> 34f; Furniture.PIANO -> 70f
        Furniture.TROPHY_CASE -> 50f; Furniture.HOT_TUB -> 80f; Furniture.DISCO_BALL -> 40f; Furniture.POOL_TABLE -> 90f
        Furniture.FRIDGE -> 30f; Furniture.STOVE -> 36f; Furniture.WALL_CLOCK -> 26f; Furniture.DOG_BED -> 50f
        Furniture.SLIDE -> 74f; Furniture.ROCKING_HORSE -> 44f; Furniture.BEAN_BAG -> 36f; Furniture.STAR_LIGHTS -> ROOM_W
        Furniture.GAMING_PC -> 54f; Furniture.FOUNTAIN -> 44f; Furniture.ROCKET -> 24f; Furniture.CHANDELIER -> 60f
        Furniture.PLUSH_SHELF, Furniture.PLUSH_SHELF2, Furniture.PLUSH_SHELF3 -> 40f
        Furniture.BABY_MACHINE -> 34f; Furniture.MOVIE_SCREEN -> 90f; Furniture.DRUM_SET -> 50f; Furniture.CANDY_MACHINE -> 26f
        Furniture.HAMMOCK -> 70f; Furniture.BUBBLE_MACHINE -> 30f; Furniture.TREASURE_CHEST -> 44f
    }

    /** 0: stands on the floor, 1: hangs on the wall or ceiling, 2: lies flat on the floor (rugs). */
    fun layer(f: Furniture): Int = when (f) {
        Furniture.PAINTING, Furniture.WALL_CLOCK, Furniture.DISCO_BALL, Furniture.CHANDELIER, Furniture.STAR_LIGHTS, Furniture.MOVIE_SCREEN -> 1
        Furniture.RUG, Furniture.TOY_TRAIN -> 2
        else -> 0
    }

    /** Everything can be moved around except the star lights (they go all the way across the room). */
    fun movable(f: Furniture) = f != Furniture.STAR_LIGHTS

    /** Places on each floor where furniture can't go (doors, stairs, the shop counter, the bed and the window). */
    fun blocked(floor: Int, layer: Int, hasBed: Boolean, hasStairsUp: Boolean): List<ClosedFloatingPointRange<Float>> = buildList {
        add(0f..(DOOR_X + 36f))
        if (hasStairsUp) add((STAIRS_X - 8f)..ROOM_W)
        if (floor == 0 && layer == 0) { add((SHOP_X - 4f)..(SHOP_X + 72f)); add((MON_X - 4f)..(MON_X + 48f)) }
        if (floor == 0 && layer == 0 && hasBed) add((BED_X - 6f)..(BED_X + 62f))
        if (layer == 1) { add((BED_X - 6f)..(BED_X + 62f)); add(SHELF_X..(SHELF_X + 210f)) }
        add((ROOM_W - 12f)..(ROOM_W + 100f))
    }

    /** The elevator at the end of the room: shiny doors and an arrow light. */
    fun drawElevator(gfx: Gfx, x: Float, t: Float, floorY: Float = FLOOR_Y) {
        gfx.rect(x - 2, floorY - 74, 38f, 74f, 0xFF5A6070.toInt())
        gfx.rect(x, floorY - 70, 34f, 70f, 0xFFB8C0CC.toInt())
        val open = ((sin(t * 0.8f) + 1f) * 0.5f).let { if (it > 0.85f) (it - 0.85f) * 6f else 0f }
        gfx.rect(x + 1, floorY - 64, 16f - open * 10f, 64f, 0xFF9AA4B8.toInt())
        gfx.rect(x + 17 + open * 10f, floorY - 64, 16f - open * 10f, 64f, 0xFF9AA4B8.toInt())
        gfx.rect(x + 16.5f, floorY - 64, 1f, 64f, 0xFF7A8494.toInt())
        gfx.oval(x + 13, floorY - 72, 8f, 6f, if ((t * 2).toInt() % 2 == 0) 0xFFFFD21F.toInt() else 0xFFFF8A3A.toInt())
        gfx.oval(x + 36, floorY - 40, 5f, 5f, 0xFFE8E8F0.toInt())
        gfx.oval(x + 36, floorY - 32, 5f, 5f, 0xFFE8E8F0.toInt())
    }

    fun drawBuildSign(gfx: Gfx, x: Float, line2: String = "UPSTAIRS") {
        gfx.rect(x + 3f, FLOOR_Y - 40f, 4f, 40f, 0xFF8A5A2A.toInt())
        gfx.rect(x - 28f, FLOOR_Y - 52f, 56f, 24f, 0xFFFFD21F.toInt())
        gfx.text("BUILD", x, FLOOR_Y - 42f, 7f, 0xFF2A1808.toInt(), 1)
        gfx.text(line2, x, FLOOR_Y - 32f, 6f, 0xFF2A1808.toInt(), 1)
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

/**
 * Draws through to [inner] in house paint: every wall-ish color becomes [paint], just as light or dark as it
 * was (so stripes, logs and bricks still show). Windows, glass, very dark outlines and see-through bits stay.
 */
class PaintGfx(private val inner: Gfx, private val paint: Int) : Gfx {
    private fun lum(c: Int) = (((c shr 16) and 255) * 0.3f + ((c shr 8) and 255) * 0.59f + (c and 255) * 0.11f) / 255f
    private val paintLum = lum(paint).coerceAtLeast(0.15f)
    private val cache = HashMap<Int, Int>()

    private fun recolor(c: Int): Int = cache.getOrPut(c) {
        val a = c ushr 24
        val l = lum(c)
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        // Leave alone: see-through things, near-black lines, bright windows and lights, and sky-blue glass.
        if (a < 0xC0 || l < 0.13f || l > 0.9f || (r > 230 && g > 200 && b < 170) || (b > r + 50 && b > g + 10 && l > 0.55f && r < 170)) return@getOrPut c
        val k = (l / paintLum) * 0.95f
        fun ch(sh: Int) = (((paint shr sh) and 255) * k).toInt().coerceIn(0, 255)
        val target = (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        com.roccoquest.game.mixColor(c, (a shl 24) or target, 0.82f)
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.rect(x, y, w, h, recolor(color))
    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.oval(x, y, w, h, recolor(color))
    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) = inner.poly(xs, ys, recolor(color))
    override fun sprite(s: com.roccoquest.game.Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) = inner.sprite(s, x, y, flipX, flipY, w, h)
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) = inner.text(s, x, y, size, color, align)
    override fun gradient(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) = inner.gradient(x, y, w, h, recolor(top), recolor(bottom))
}
