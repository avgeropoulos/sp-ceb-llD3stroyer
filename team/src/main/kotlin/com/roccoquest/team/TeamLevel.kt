package com.roccoquest.team

import com.roccoquest.game.Body
import com.roccoquest.game.Music
import com.roccoquest.game.TILE
import com.roccoquest.game.tileOf

const val ROWS = 15

object Tile {
    const val EMPTY = 0
    const val GROUND = 1
    const val DIRT = 2
    const val BRICK = 3
    const val QCOIN = 4
    const val QHEART = 5
    const val USED = 6
    const val HARD = 7
    const val STONE = 8
    const val IRON = 9
    const val GOLD = 10
    const val DIAMOND = 11
    const val EMERALD = 12
    const val LAVA = 13
    const val CLOUD = 14
    const val COPPER = 15
    const val AMETHYST = 16
    /** Hides a Super Star. */
    const val QSTAR = 17
    /** A rainbow mystery block: a random surprise. */
    const val QMYSTERY = 18
    /** Minecart track: you can walk through it. */
    const val RAIL = 19
    /** A spike trap on the floor. */
    const val SPIKES = 20
    /** Underwater bubble vent: a solid block that puffs out air bubbles. */
    const val VENT = 21
    /** New materials: red ruby, blue sapphire and super-tough black obsidian. */
    const val RUBY = 22
    const val SAPPHIRE = 23
    const val OBSIDIAN = 24
    /** Ship planks, ship masts (you walk past them) and Rainbow Road. */
    const val PLANK = 25
    const val MAST = 26
    const val RAINBOW = 27

    fun solid(t: Int) = t != EMPTY && t != LAVA && t != CLOUD && t != RAIL && t != SPIKES && t != MAST

    /** How many pickaxe points it takes to mine a tile; 0 means it can't be mined. */
    fun toughness(t: Int) = when (t) {
        GROUND, DIRT, BRICK, PLANK -> 2
        STONE, IRON, COPPER -> 4
        GOLD, EMERALD, AMETHYST, RUBY, SAPPHIRE -> 6
        DIAMOND -> 8
        OBSIDIAN -> 10
        else -> 0
    }

    fun ore(t: Int): Ore? = when (t) {
        STONE -> Ore.STONE
        IRON -> Ore.IRON
        GOLD -> Ore.GOLD
        DIAMOND -> Ore.DIAMOND
        EMERALD -> Ore.EMERALD
        COPPER -> Ore.COPPER
        AMETHYST -> Ore.AMETHYST
        RUBY -> Ore.RUBY
        SAPPHIRE -> Ore.SAPPHIRE
        OBSIDIAN -> Ore.OBSIDIAN
        else -> null
    }
}

/** Things Blocky can dig up. Iron is the weakest metal, then gold, then diamond. Copper and amethyst come later. */
enum class Ore(val label: String, val color: Int, val dark: Int) {
    STONE("STONE", 0xFFA8A8A8.toInt(), 0xFF6E6E6E.toInt()),
    IRON("IRON", 0xFFE8C0A0.toInt(), 0xFFB07850.toInt()),
    GOLD("GOLD", 0xFFFFD82A.toInt(), 0xFFC89A10.toInt()),
    DIAMOND("DIAMOND", 0xFF7FF4F0.toInt(), 0xFF20B8C0.toInt()),
    EMERALD("EMERALD", 0xFF3CE070.toInt(), 0xFF128A3A.toInt()),
    COPPER("COPPER", 0xFFF0904A.toInt(), 0xFFA0501E.toInt()),
    AMETHYST("AMETHYST", 0xFFC890FF.toInt(), 0xFF7A3AC0.toInt()),
    RUBY("RUBY", 0xFFFF3A5A.toInt(), 0xFFA0102A.toInt()),
    SAPPHIRE("SAPPHIRE", 0xFF4A7AFF.toInt(), 0xFF1A2A9A.toInt()),
    OBSIDIAN("OBSIDIAN", 0xFF5A3A8A.toInt(), 0xFF1A0E2A.toInt()),
}

enum class Place(val music: Music) {
    HILLS(Music.OVERWORLD),
    CAVE(Music.UNDERGROUND),
    SKY(Music.SKY),
    CASTLE(Music.CASTLE),
    DESERT(Music.OVERWORLD),
    SNOW(Music.SKY),
    JUNGLE(Music.OVERWORLD),
    VOLCANO(Music.CASTLE),
    WATER(Music.SKY),
    NIGHT(Music.UNDERGROUND),
    DEEP(Music.UNDERGROUND),
    END(Music.UNDERGROUND),
    /** The bonus rooms behind the little portals. */
    SUNSET(Music.STAR),
    /** Pirate ships on the open sea. */
    SHIP(Music.SHANTY),
    /** Rainbow Road, up among the stars. */
    RAINBOW(Music.STAR),
}

/**
 * A level as a character grid.
 *
 *  '#' ground   'd' dirt   'B' brick   '?' coin block   'h' heart block   'X' hard block
 *  's' stone   'i' iron ore   'o' gold ore   'D' diamond ore   'e' emerald ore
 *  'u' copper ore   'a' amethyst ore   '*' star block   'm' mystery block
 *  'L' lava   'O' cloud   '@' start   'c' coin   'g' grumbler   'w' winged grumbler
 *  'k' shellback   'b' bonehead   'v' flapper bat   'p' bomb-bomb
 *  '|' checkpoint   'F' goal flag   'r' Ring-Ding Frog drives by here
 *  'J' Krag Jr.   'K' King Krag   'W' Wonder Krag Jr.   'Q' Wonder King Krag
 *  'V' villager   'n' enderman   'f' fish   'G' the Warden   'Y' Dry Bones King Krag   'Z' Ender Dragon
 *  'M' minecart   '=' rail   '^' spike trap   '~' bubble vent   'C' end crystal
 *  'S' (any row) marks the shore: everything left of that column is underwater
 *  'R' ruby   'E' sapphire   'x' obsidian   'P' bonus portal   't' treasure chest (bonus rooms)
 *  'A' skelly archer   'H' hopper   'U' gloop slime   'T' snapper plant   'y' Dash the dino
 *  'z' crusher   'q' peekaboo ghost   'l' puffcloud   'j' spikestack
 *  'I' ship plank   ':' mast   '%' rainbow road   'N' cannon   '$' metal cap
 */
class LevelDef(val name: String, val place: Place, val rows: List<String>, val tip: String) {
    val w get() = rows[0].length
    /** Column where the water ends, or -1 if this isn't an underwater level. */
    val shoreX: Int = rows.map { it.indexOf('S') }.filter { it >= 0 }.minOrNull() ?: -1
    /** Levels with a boss fight get scary music. */
    val isBossLevel: Boolean get() = rows.any { r -> r.any { it in "JWKQGYZ" } }
}

/** Characters that make solid ground a bad guy (or a portal) can stand on. */
const val GROUND_CHARS = "#sdXBiouaDeREx%I"

class Builder(private val w: Int) {
    private val g = Array(ROWS) { CharArray(w) { '.' } }

    fun at(x: Int, y: Int, c: Char) = apply { if (x in 0 until w && y in 0 until ROWS) g[y][x] = c }
    fun row(x: Int, y: Int, s: String) = apply { s.forEachIndexed { i, c -> if (c != ' ') at(x + i, y, c) } }
    fun fill(x0: Int, x1: Int, y0: Int, y1: Int, c: Char) = apply { for (y in y0..y1) for (x in x0..x1) at(x, y, c) }
    fun ground(x0: Int, x1: Int, top: Int = 13) = fill(x0, x1, top, ROWS - 1, '#')

    /** A little hill of stone with ore veins: each string is one row from the top. */
    fun rock(x: Int, bottom: Int, vararg rows: String) = apply {
        rows.forEachIndexed { i, s -> row(x, bottom - rows.size + 1 + i, s) }
    }

    /** Puts a foe [c] standing on the ground in each of the columns [xs] (skipping columns with no room). */
    fun onGround(c: Char, vararg xs: Int) = apply {
        for (x in xs) {
            val y = (ROWS - 2 downTo 1).firstOrNull { g[it][x] == '.' && g[it + 1][x] in GROUND_CHARS } ?: continue
            g[y][x] = c
        }
    }

    fun build(name: String, place: Place, tip: String) = LevelDef(name, place, g.map { String(it) }, tip)
}

object Levels {
    val all: List<LevelDef> by lazy {
        listOf(
            hills(), cave(), cloudSky(), castle(),
            copperCanyon(), frostyPeaks(), amethystMines(), wonderFortress(),
            mushroomJungle(), lavaLake(), wonderCastle(),
            villagerValley(), coralReef(), minecartMine(), enderPlains(), deepDark(),
            treasureLagoon(), spikeTemple(), fireFortress(), boneCastle(), theEnd(),
        ).plus(World2.all).plus(MoreWorlds.all).plus(World5.all).mapIndexed { i, d -> remix(d, i) }
    }

    /**
     * Mixes the newer stuff into every level: Hoppers and Skelly Archers in place of some
     * Grumblers and Shellbacks, Gloops and Snapper plants later on, new materials in the rock,
     * and a little BONUS PORTAL somewhere after the first checkpoint.
     */
    private fun remix(d: LevelDef, index: Int): LevelDef {
        val g = d.rows.map { it.toCharArray() }
        val counts = HashMap<Char, Int>()
        fun nth(c: Char, every: Int) = ((counts[c] ?: 0) + 1).also { counts[c] = it } % every == 0
        val newOres = if (index >= 15) "REx" else "RE"
        var oreTurn = 0
        for (x in 0 until d.w) for (y in 0 until ROWS) {
            val below = if (y + 1 < ROWS) g[y + 1][x] else '#'
            when (g[y][x]) {
                'g' -> if (nth('g', 3)) g[y][x] = 'H'
                'k' -> if (index >= 3 && nth('k', 2)) g[y][x] = 'A'
                // Fewer prize blocks: some become plain bricks.
                'm' -> if (nth('m', 2)) g[y][x] = '?'
                '?' -> if (nth('?', 3)) g[y][x] = 'B'
                'b' -> when {
                    index >= 5 && nth('b', 3) -> g[y][x] = 'U'
                    index in 21..41 && nth('B', 4) -> g[y][x] = 'j'
                }
                'w' -> when {
                    index >= 7 && below in GROUND_CHARS && nth('w', 3) -> g[y][x] = 'T'
                    // World 2 gets some of the newer monsters too.
                    index in 21..41 && nth('W', 3) && y >= 4 && g[3][x] == '.' -> { g[y][x] = '.'; g[3][x] = 'l' }
                }
                'v' -> if (index >= 14 && nth('v', 3)) g[y][x] = 'q'
                's' -> if (index >= 4 && y >= 2 && nth('s', 8)) g[y][x] = newOres[oreTurn++ % newOres.length]
            }
        }
        // A bonus portal a little way past the first checkpoint, standing on open ground.
        val cp = (0 until d.w).firstOrNull { x -> (0 until ROWS).any { d.rows[it][x] == '|' } }
        // Bonus portals only show up every third level now, so they feel special.
        if (cp != null && d.shoreX < 0 && index % 3 == 1) {
            val spot = ((cp + 5) until minOf(d.w - 4, cp + 40)).firstNotNullOfOrNull { x ->
                val y = (ROWS - 2 downTo 2).firstOrNull { y ->
                    g[y][x] == '.' && g[y - 1][x] == '.' && g[y + 1][x] in GROUND_CHARS &&
                        g[y][x - 1] == '.' && g[y][x + 1] == '.' && g[y + 1][x - 1] in GROUND_CHARS && g[y + 1][x + 1] in GROUND_CHARS
                }
                y?.let { x to it }
            }
            if (spot != null) g[spot.second][spot.first] = 'P'
        }
        // Dash the dino waits near the start of every fourth level.
        val boss = d.rows.any { r -> r.any { it in "JWKQGYZ" } }
        if (index % 4 == 2 && d.shoreX < 0 && d.place != Place.SKY && !boss) {
            val spot = (10 until minOf(d.w - 4, 45)).firstNotNullOfOrNull { x ->
                val y = (ROWS - 2 downTo 2).firstOrNull { y ->
                    g[y][x] == '.' && g[y - 1][x] == '.' && g[y][x + 1] == '.' && g[y + 1][x] in GROUND_CHARS && g[y + 1][x + 1] in GROUND_CHARS
                }
                y?.let { x to it }
            }
            if (spot != null) g[spot.second][spot.first] = 'y'
        }
        return LevelDef(d.name, d.place, g.map { String(it) }, d.tip)
    }

    /** The sunset bonus room behind every portal: coins, prize blocks, new materials and a treasure chest. */
    val bonusRoom: LevelDef by lazy {
        Builder(46)
            .ground(0, 45)
            .fill(0, 0, 2, 12, 'X').fill(45, 45, 2, 12, 'X')
            .at(3, 12, '@')
            .row(7, 11, "ccc").row(6, 8, "B?B")
            .rock(14, 12, "sRs", "Ess")
            .row(20, 9, "cccc").row(20, 10, "OOOO")
            .rock(27, 12, "sxs", "sRs")
            .row(32, 11, "cc").row(31, 7, "BmB")
            .at(36, 12, 't')
            .row(39, 8, "cc")
            .at(42, 12, 'P')
            .build("SUNSET BONUS", Place.SUNSET, "Grab everything before the sun goes down!")
    }

    private fun hills() = Builder(170)
        .ground(0, 52).ground(56, 96).ground(100, 169)
        .at(3, 12, '@')
        // An iron hill right at the start, so the team can craft a Boomerang Flower early.
        .rock(9, 12, "  sis ", " sisis", "ssiiss")
        .row(18, 9, "B?Bh").at(24, 12, 'g')
        .rock(28, 12, " ss", "sos", "sos")
        .at(34, 12, 'g').at(37, 12, 'g')
        .row(40, 9, "?B?").row(40, 5, "ccc")
        .rock(46, 12, "ses", "ssss")
        .at(50, 12, 'k')
        .at(58, 12, 'r')
        .at(62, 12, '|')
        .rock(66, 12, "  ii  ", " sois ", "ssooss")
        .at(74, 12, 'g').at(76, 12, 'w')
        .row(80, 8, "BBhBB").row(81, 4, "cc")
        .at(86, 12, 'k').at(90, 12, 'g')
        .rock(92, 12, " e ", "ses", "sss")
        .row(104, 9, "??").at(108, 12, 'g').at(110, 12, 'g')
        .rock(114, 12, "  D", " ss", "sss")
        .fill(118, 118, 11, 12, 'X').fill(119, 119, 10, 12, 'X').fill(120, 120, 9, 12, 'X')
        .row(124, 9, "cccc").at(126, 12, 'w').at(130, 12, 'k')
        .rock(134, 12, "ioi", "sss")
        .at(140, 12, 'g').at(142, 12, 'g').at(144, 12, 'g')
        .fill(150, 150, 12, 12, 'X').fill(151, 151, 11, 12, 'X').fill(152, 152, 10, 12, 'X').fill(153, 153, 9, 12, 'X')
        .at(160, 12, 'F')
        .build(
            "GRASSY HILLS", Place.HILLS,
            "Blocky MINES ore. Rocco THROWS flowers. Tap SWAP to switch!",
        )

    private fun cave() = Builder(180)
        .ground(0, 40).ground(44, 88).ground(92, 140).ground(144, 179)
        .fill(0, 179, 0, 1, 's')
        .fill(0, 0, 2, 12, 'X')
        .at(3, 12, '@')
        .rock(7, 12, "sDs", "sss")
        .row(12, 9, "?h?").at(16, 12, 'g').at(18, 12, 'g')
        .fill(22, 26, 2, 4, 's').row(22, 4, "sioDs")
        .rock(24, 12, "  ss", "soos", "ssss")
        .at(30, 12, 'k').at(34, 12, 'w')
        .fill(41, 43, 13, 14, 'L')
        .row(48, 8, "BBBBB").row(48, 7, "ccccc")
        .rock(54, 12, "ss", "ee", "ss")
        .at(57, 12, 'g').at(59, 12, 'g').at(60, 12, 'k')
        .at(62, 12, 'r')
        .at(64, 12, '|')
        .fill(68, 75, 2, 5, 's').row(69, 5, "DsDsoo")
        .rock(68, 12, "    s   ", "  ssDs  ", " sDDssss", "ssssssss")
        .at(78, 12, 'k').at(82, 12, 'g').at(84, 12, 'w')
        .fill(89, 91, 13, 14, 'L')
        .row(94, 9, "?B?Bh")
        .at(98, 12, 'g').at(100, 12, 'k')
        .rock(104, 12, "sees", "siis", "ssss")
        .at(110, 12, 'w').at(113, 12, 'g').at(115, 12, 'g')
        .rock(118, 12, "ooo", "sss")
        .at(124, 12, '|')
        .fill(126, 128, 11, 12, 'X').row(130, 8, "cccc").at(134, 12, 'k').at(137, 12, 'k')
        .fill(141, 143, 13, 14, 'L')
        .rock(148, 12, "DeD", "sss")
        .at(154, 12, 'g').at(156, 12, 'w').at(158, 12, 'g')
        .at(170, 12, 'F')
        .onGround('p', 66, 96, 120)
        .build(
            "DIAMOND CAVE", Place.CAVE,
            "Diamonds are tough! Craft an IRON PICKAXE first.",
        )

    private fun cloudSky() = Builder(150)
        .ground(0, 14)
        .at(3, 12, '@')
        .rock(8, 12, "oe", "ss")
        .row(17, 11, "OOOO").row(23, 9, "OOOO").at(25, 8, 'g')
        .row(29, 11, "OOOOO").row(30, 10, "cccc")
        .ground(35, 44, 12).rock(38, 11, "ioD", "sss").at(43, 11, 'k')
        .row(47, 10, "OOO").row(52, 8, "OOOO").at(54, 7, 'w')
        .row(57, 11, "OOOOO").at(60, 10, 'g')
        .ground(63, 74, 12).at(66, 11, 'r').at(68, 11, '|').row(65, 8, "?h?")
        .rock(72, 11, "eo", "ss")
        .row(77, 10, "OOOO").row(83, 9, "OOOO").at(85, 8, 'w')
        .row(89, 11, "OOO")
        .ground(93, 149, 12)
        .row(101, 7, "OOOO").row(112, 7, "OOOO").row(106, 4, "OOOO")
        .row(108, 3, "h")
        .at(110, 6, 'J')
        .fill(119, 149, 12, 14, '#')
        .at(130, 11, 'F')
        .build(
            "CLOUD SKIES", Place.SKY,
            "Krag Jr. is waiting above the clouds in his clown car!",
        )

    private fun castle() = Builder(150)
        .ground(0, 149)
        .fill(0, 149, 0, 1, 'X')
        .at(3, 12, '@')
        .rock(7, 12, "DoeD", "ssss")
        .row(14, 9, "?h?")
        .fill(19, 22, 13, 14, 'L').at(24, 12, 'g').at(27, 12, 'k')
        .fill(30, 31, 10, 12, 'X').fill(35, 38, 13, 14, 'L')
        .row(35, 9, "cccc").at(41, 12, 'w').at(44, 12, 'g')
        .rock(47, 12, "iie", "ooD", "sss")
        .at(53, 12, 'r').at(56, 12, '|')
        .fill(60, 63, 13, 14, 'L').at(66, 12, 'k').at(69, 12, 'k')
        .row(72, 9, "BhB").at(76, 12, 'w').at(79, 12, 'g').at(81, 12, 'g')
        .rock(84, 12, "eDo", "sss")
        .at(90, 12, '|')
        .row(104, 8, "h").row(122, 8, "h")
        .fill(131, 131, 2, 12, 'X')
        .at(122, 12, 'K')
        .build(
            "KRAG'S CASTLE", Place.CASTLE,
            "Craft your strongest flower and stop King Krag!",
        )

    private fun copperCanyon() = Builder(180)
        .ground(0, 40).ground(44, 90).ground(94, 140).ground(143, 179)
        .at(3, 12, '@')
        .rock(7, 12, "uuu", "sus").at(11, 12, 'V')
        .row(13, 9, "?m?").at(18, 12, 'b')
        .rock(22, 12, " su ", "suus", "ssss")
        .at(28, 12, 'k').at(31, 12, 'g').at(35, 12, 'b')
        .row(37, 8, "cccc")
        .rock(47, 12, "oiu", "sss")
        .at(53, 12, 'b').at(56, 12, 'w')
        .at(58, 12, 'r').at(61, 12, '|')
        .fill(64, 65, 10, 12, 'X')
        .rock(68, 12, "  u", "uuo", "sss")
        .at(74, 12, 'k').at(77, 12, 'b').at(80, 12, 'g')
        .row(83, 9, "B*B")
        .rock(97, 12, "ue", "ss")
        .at(102, 12, 'b').at(105, 12, 'b').at(108, 12, 'w')
        .at(112, 12, '|')
        .rock(116, 12, " i ", "uiu", "sss")
        .at(122, 12, 'k').at(126, 12, 'g').at(128, 12, 'g')
        .row(131, 9, "?m?")
        .rock(146, 12, "Duu", "sss")
        .at(152, 12, 'b').at(155, 12, 'w')
        .fill(158, 158, 12, 12, 'X').fill(159, 159, 11, 12, 'X').fill(160, 160, 10, 12, 'X')
        .at(170, 12, 'F')
        .onGround('p', 26, 88, 134)
        .build(
            "COPPER CANYON", Place.DESERT,
            "Copper makes the THUNDER FLOWER! Boneheads get back up...",
        )

    private fun frostyPeaks() = Builder(180)
        .ground(0, 30).ground(33, 60, 11).ground(64, 90).ground(93, 120, 11).ground(124, 179)
        .at(3, 12, '@')
        .rock(8, 12, "aa", "ss")
        .row(12, 9, "?h?").at(17, 12, 'g').at(20, 12, 'w').at(24, 12, 'k')
        .rock(26, 12, "sa", "ss")
        .at(36, 10, 'w').at(40, 10, 'g').rock(44, 10, "ai", "ss")
        .row(48, 6, "cccc").at(50, 10, 'k')
        .at(57, 10, 'r')
        .at(66, 12, '|').rock(70, 12, " D ", "aoa", "sss")
        .at(76, 12, 'w').at(79, 12, 'w').at(83, 12, 'b').row(85, 9, "m?m")
        .at(96, 10, 'g').at(99, 10, 'k').rock(103, 10, "eae", "sss")
        .at(110, 10, 'w').at(114, 10, '|')
        .at(128, 12, 'b').at(131, 12, 'g').at(134, 12, 'g').rock(138, 12, "aD", "ss")
        .at(144, 12, 'k').at(148, 12, 'w').row(150, 9, "*")
        .at(165, 12, 'F')
        .build(
            "FROSTY PEAKS", Place.SNOW,
            "Purple AMETHYST makes the CRYSTAL FLOWER!",
        )

    private fun amethystMines() = Builder(190)
        .ground(0, 50).ground(54, 100).ground(104, 150).ground(154, 189)
        .fill(0, 189, 0, 1, 's')
        .fill(0, 0, 2, 12, 'X')
        .at(3, 12, '@')
        .rock(7, 12, "aDa", "sss")
        .at(14, 8, 'v').at(18, 12, 'b').row(20, 9, "?m?")
        .fill(26, 32, 2, 4, 's').row(27, 4, "aauaa")
        .rock(28, 12, "  a  ", " saas", "ssssss")
        .at(36, 12, 'k').at(40, 8, 'v').at(44, 12, 'b')
        .fill(51, 53, 13, 14, 'L')
        .at(57, 12, 'r').at(60, 12, '|')
        .rock(64, 12, "DaD", "sss").at(70, 8, 'v').at(72, 12, 'b').at(75, 12, 'w')
        .row(78, 8, "BBBBB").row(78, 7, "ccccc")
        .rock(86, 12, "ueu", "sss").at(92, 12, 'k').at(95, 8, 'v')
        .fill(101, 103, 13, 14, 'L')
        .at(107, 12, '|').rock(111, 12, "oao", "sss")
        .at(117, 12, 'b').at(120, 12, 'b').at(123, 8, 'v').at(126, 8, 'v')
        .row(130, 9, "?*?")
        .rock(136, 12, "aDe", "sss").at(142, 12, 'k').at(146, 12, 'w')
        .fill(151, 153, 13, 14, 'L')
        .at(158, 12, 'b').at(161, 8, 'v').rock(164, 12, "aa", "ss")
        .at(180, 12, 'F')
        .build(
            "AMETHYST MINES", Place.CAVE,
            "Watch out for Flapper bats in the dark!",
        )

    private fun wonderFortress() = Builder(160)
        .ground(0, 16)
        .at(3, 12, '@')
        .rock(8, 12, "aD", "uo", "ss")
        .row(19, 11, "OOOO").row(25, 9, "OOOO").at(27, 8, 'w')
        .row(31, 11, "OOOOO").row(32, 10, "cccc")
        .ground(37, 48, 12).rock(40, 11, "aue", "sss").at(46, 11, 'k')
        .row(51, 10, "OOO").row(56, 8, "OOOO").at(58, 7, 'w')
        .row(61, 11, "OOOOO").at(63, 10, 'g')
        .ground(67, 80, 12).at(70, 11, 'r').at(68, 11, '|').row(71, 8, "m*m")
        .rock(77, 11, "DD", "ss")
        .row(83, 10, "OOOO").row(89, 9, "OOOO").at(91, 8, 'w')
        .row(95, 11, "OOO")
        .ground(99, 159, 12)
        .row(107, 7, "OOOO").row(118, 7, "OOOO").row(112, 4, "OOOO")
        .row(114, 3, "h")
        .at(116, 6, 'W')
        .fill(125, 159, 12, 14, '#')
        .at(140, 11, 'F')
        .build(
            "WONDER FORTRESS", Place.SKY,
            "WONDER KRAG JR. has gone purple and green!",
        )

    private fun mushroomJungle() = Builder(190)
        .ground(0, 44).ground(47, 80).ground(84, 130, 12).ground(133, 189)
        .at(3, 12, '@')
        .rock(7, 12, "ee", "ss").at(10, 12, 'V')
        .row(12, 9, "?m?").at(16, 12, 'g').at(18, 12, 'g').at(20, 12, 'g')
        .rock(24, 12, " e ", "eie", "sss")
        .at(30, 12, 'k').at(33, 12, 'w').at(36, 12, 'w').row(38, 8, "BhB")
        .rock(41, 12, "uo", "ss")
        .at(50, 12, 'b').at(53, 12, 'g').at(55, 12, 'g')
        .at(58, 12, 'r').at(61, 12, '|')
        .rock(64, 12, "  a ", " eDe", "ssss")
        .at(70, 12, 'k').at(73, 12, 'k').at(76, 12, 'w')
        .at(88, 11, 'g').at(90, 11, 'g').at(92, 11, 'b').row(94, 8, "?*?")
        .rock(99, 11, "eoe", "sss")
        .at(104, 11, 'w').at(107, 11, 'k').at(110, 11, 'v')
        .at(114, 11, '|')
        .rock(118, 11, "ua", "ss")
        .at(124, 11, 'g').at(126, 11, 'g').at(128, 11, 'b')
        .rock(137, 12, "DeD", "sss")
        .at(143, 12, 'w').at(146, 12, 'w').at(149, 12, 'k').row(151, 9, "m?m")
        .at(156, 12, 'b').at(159, 12, 'g')
        .fill(164, 164, 12, 12, 'X').fill(165, 165, 11, 12, 'X').fill(166, 166, 10, 12, 'X')
        .at(178, 12, 'F')
        .onGround('p', 46, 86, 140)
        .build(
            "MUSHROOM JUNGLE", Place.JUNGLE,
            "Lots of bad guys here. Good time for a Pokemon SUPER move!",
        )

    private fun lavaLake() = Builder(190)
        .ground(0, 34).ground(38, 70).ground(74, 110).ground(115, 150).ground(154, 189)
        .fill(35, 37, 13, 14, 'L').fill(71, 73, 13, 14, 'L').fill(111, 114, 13, 14, 'L').fill(151, 153, 13, 14, 'L')
        .at(3, 12, '@')
        .rock(7, 12, "oDo", "sss")
        .row(13, 9, "?h?").at(18, 12, 'b').at(22, 12, 'g').at(25, 8, 'v')
        .rock(28, 12, "uau", "sss")
        .at(42, 12, 'b').at(45, 12, 'k').at(48, 8, 'v')
        .fill(52, 53, 10, 12, 'X').row(56, 8, "cccc")
        .at(60, 12, 'r').at(63, 12, '|')
        .rock(66, 12, "Da", "ss")
        .at(78, 12, 'b').at(81, 12, 'b').at(84, 8, 'v').row(86, 9, "m*m")
        .rock(92, 12, " a ", "eDe", "sss")
        .at(99, 12, 'k').at(103, 12, 'w').at(106, 12, 'b')
        .at(118, 12, '|')
        .rock(122, 12, "ooo", "sss").at(128, 8, 'v').at(131, 12, 'b').at(134, 12, 'k')
        .row(138, 9, "?m?").at(143, 12, 'w').at(146, 12, 'b')
        .rock(158, 12, "aDa", "sss")
        .at(164, 12, 'b').at(167, 8, 'v')
        .at(180, 12, 'F')
        .onGround('p', 52, 96, 136)
        .build(
            "LAVA LAKE", Place.VOLCANO,
            "Hot hot hot! Jump over the lava pits.",
        )

    private fun wonderCastle() = Builder(160)
        .ground(0, 159)
        .fill(0, 159, 0, 1, 'X')
        .at(3, 12, '@')
        .rock(7, 12, "aDuo", "eDaD", "ssss")
        .row(14, 9, "?h?")
        .fill(19, 22, 13, 14, 'L').at(25, 12, 'b').at(28, 12, 'k').at(31, 8, 'v')
        .fill(34, 37, 13, 14, 'L')
        .row(34, 9, "cccc").at(41, 12, 'w').at(44, 12, 'b')
        .rock(47, 12, "aaD", "ooe", "sss")
        .at(53, 12, 'r').at(56, 12, '|')
        .row(59, 9, "m*m")
        .fill(63, 66, 13, 14, 'L').at(69, 12, 'k').at(72, 12, 'b').at(75, 8, 'v')
        .rock(80, 12, "DuD", "sss")
        .at(86, 12, '|')
        .row(98, 8, "h").row(118, 8, "h").row(108, 9, "m")
        .fill(133, 133, 2, 12, 'X')
        .at(120, 12, 'Q')
        .build(
            "WONDER CASTLE", Place.CASTLE,
            "WONDER KING KRAG! Use EVERYTHING: flowers, TNT, stars and SUPER moves!",
        )

    private fun villagerValley() = Builder(200)
        .ground(0, 199)
        .at(3, 12, '@')
        .at(10, 12, 'V').rock(15, 12, "si", "ss")
        .row(20, 9, "?m?").at(24, 12, 'g').at(27, 12, 'g')
        .at(32, 12, '^').at(33, 12, '^')
        .rock(37, 12, "eio", "sss")
        .at(44, 12, 'k').at(47, 12, 'V')
        .at(52, 12, 'r').at(55, 12, '|')
        .fill(58, 100, 12, 12, '=').at(58, 12, 'M')
        .at(66, 12, 'g').at(70, 12, 'g').at(74, 12, 'k').at(78, 12, 'g').at(84, 12, 'b').at(90, 12, 'g')
        .at(104, 12, 'V').rock(108, 12, "oDo", "sss")
        .at(115, 12, '^').at(116, 12, '^').at(117, 12, '^')
        .at(122, 12, 'w').at(126, 12, 'b')
        .at(130, 12, '|').row(134, 9, "*").at(140, 12, 'k')
        .rock(145, 12, "uea", "sss")
        .at(152, 12, 'g').at(155, 12, 'g')
        .fill(160, 160, 12, 12, 'X').fill(161, 161, 11, 12, 'X').fill(162, 162, 10, 12, 'X')
        .at(175, 12, 'V').at(185, 12, 'F')
        .onGround('p', 40, 96, 142)
        .build(
            "VILLAGER VALLEY", Place.HILLS,
            "Talk to VILLAGERS to trade! Hop in a MINECART and ride!",
        )

    private fun coralReef() = Builder(170)
        .ground(0, 169)
        .at(3, 12, '@')
        .at(8, 12, '~').rock(12, 12, "sD", "ss")
        .at(18, 8, 'f').at(24, 11, 'f').fill(28, 30, 9, 12, 's').row(28, 9, "eoe")
        .at(34, 12, '~').at(38, 6, 'f').row(40, 6, "cccc").at(44, 10, 'f')
        .fill(48, 49, 7, 12, 'X')
        .at(52, 12, '~').at(56, 9, 'f').at(60, 5, 'f').rock(64, 12, "aDa", "sss")
        .at(70, 12, '|').at(74, 12, '~').at(78, 7, 'f').at(82, 11, 'f').fill(86, 88, 4, 7, 's').row(86, 6, "ouo")
        .at(92, 12, '~').at(96, 8, 'f').at(100, 10, 'f').at(104, 6, 'f')
        .at(110, 12, '~').rock(114, 12, "DeD", "sss").at(120, 9, 'f').at(124, 5, 'f')
        .at(128, 12, '~').at(132, 10, 'f').at(136, 7, 'f')
        .at(142, 12, '~')
        .at(150, 0, 'S')
        .fill(150, 169, 11, 12, '#')
        .at(153, 10, 'r').at(160, 10, 'F')
        .build(
            "CORAL REEF", Place.WATER,
            "Underwater! Tap JUMP to swim. Grab BUBBLES so you don't run out of air!",
        )

    private fun minecartMine() = Builder(220)
        .ground(0, 219)
        .fill(0, 219, 0, 1, 's')
        .fill(0, 0, 2, 12, 'X')
        .at(3, 12, '@').at(8, 12, 'V')
        .rock(12, 12, "uau", "sss")
        .fill(18, 90, 12, 12, '=').at(18, 12, 'M')
        .at(30, 12, 'g').at(38, 12, 'b').at(46, 12, 'k').at(54, 12, 'b').at(62, 12, 'g').at(70, 12, 'k').at(80, 12, 'b')
        .at(30, 8, 'v').at(60, 8, 'v')
        .at(94, 12, '|').rock(98, 12, "DoD", "sss")
        .at(104, 12, 'r').at(108, 12, '^').at(109, 12, '^')
        .fill(114, 170, 12, 12, '=').at(114, 12, 'M')
        .at(126, 12, 'b').at(136, 12, 'n').at(146, 12, 'b').at(156, 12, 'k').at(140, 8, 'v')
        .at(174, 12, '|').rock(178, 12, "aea", "sss").at(186, 12, 'b').at(190, 8, 'v')
        .fill(196, 196, 12, 12, 'X').fill(197, 197, 11, 12, 'X')
        .at(210, 12, 'F')
        .onGround('p', 102, 180)
        .build(
            "MINECART MINE", Place.CAVE,
            "Ride the minecarts! They smash right through bad guys.",
        )

    private fun enderPlains() = Builder(200)
        .ground(0, 60).ground(64, 130).ground(134, 199)
        .at(3, 12, '@').at(8, 12, 'V').rock(13, 12, "aDa", "sss")
        .at(20, 12, 'g').at(24, 12, 'w').row(28, 9, "?m?")
        .at(36, 12, 'n').rock(42, 12, "eue", "sss").at(50, 12, 'b').at(55, 12, 'k')
        .at(68, 12, 'r').at(72, 12, '|').at(80, 12, 'n').row(86, 9, "*")
        .rock(90, 12, "DaD", "sss").at(98, 12, 'b').at(104, 12, 'w').at(110, 12, 'n')
        .at(118, 12, '^').at(119, 12, '^').at(124, 12, 'k')
        .at(138, 12, '|').at(146, 12, 'n').rock(152, 12, "aoa", "sss").at(160, 12, 'b').at(166, 12, 'n')
        .fill(172, 172, 12, 12, 'X').fill(173, 173, 11, 12, 'X').at(185, 12, 'V').at(190, 12, 'F')
        .onGround('p', 58, 114, 156)
        .build(
            "ENDER PLAINS", Place.NIGHT,
            "ENDERMEN! They teleport and they're tough. Team up on them!",
        )

    private fun deepDark() = Builder(170)
        .ground(0, 169)
        .fill(0, 169, 0, 1, 's')
        .fill(0, 0, 2, 12, 'X')
        .at(3, 12, '@').rock(8, 12, "DaD", "sss").row(14, 9, "?h?")
        .at(20, 12, 'b').at(24, 8, 'v').at(28, 12, 'n').rock(34, 12, "aea", "sss")
        .at(40, 12, 'r').at(44, 12, '|').at(48, 12, '^').at(49, 12, '^').at(54, 12, 'b').at(58, 8, 'v')
        .rock(62, 12, "oDo", "sss").at(68, 12, 'n').at(74, 12, '|').row(78, 9, "m*m")
        .row(96, 8, "h").row(116, 8, "h")
        .fill(131, 131, 2, 12, 'X')
        .at(115, 12, 'G')
        .build(
            "THE DEEP DARK", Place.DEEP,
            "Shhh... THE WARDEN hears you when you run and jump!",
        )

    private fun treasureLagoon() = Builder(180)
        .ground(0, 179)
        .at(3, 12, '@').at(7, 12, '~').rock(11, 12, "oo", "ss")
        .at(16, 9, 'f').at(20, 6, 'f').at(24, 11, 'f').at(28, 12, '~')
        .fill(32, 34, 7, 12, 's').row(32, 7, "DuD")
        .at(38, 8, 'f').at(42, 4, 'f').at(46, 12, '~').row(48, 5, "ccccc")
        .at(52, 10, 'f').at(56, 7, 'f').at(60, 11, 'f').at(64, 12, '~').at(66, 12, '|')
        .fill(70, 71, 3, 8, 'X')
        .at(76, 9, 'f').at(80, 5, 'f').rock(84, 12, "aea", "sss").at(90, 12, '~')
        .at(94, 8, 'f').at(98, 11, 'f').at(102, 6, 'f').at(106, 12, '~')
        .fill(110, 112, 9, 12, 's').row(110, 9, "DoD").at(116, 7, 'f').at(120, 10, 'f').at(124, 12, '~')
        .at(128, 6, 'f').at(132, 9, 'f').at(136, 11, 'f').at(140, 12, '~')
        .at(146, 5, 'f').at(150, 8, 'f').at(154, 12, '~')
        .at(160, 0, 'S').fill(160, 179, 11, 12, '#').at(163, 10, 'r').at(172, 10, 'F')
        .build(
            "TREASURE LAGOON", Place.WATER,
            "More swimming! Lots of treasure down here.",
        )

    private fun spikeTemple() = Builder(200)
        .ground(0, 199)
        .at(3, 12, '@').at(8, 12, 'V')
        .rock(12, 12, "uuo", "sss")
        .at(18, 12, '^').at(19, 12, '^').at(20, 12, '^').row(18, 8, "ccc")
        .at(26, 12, 'b').at(30, 12, 'k').fill(34, 35, 10, 12, 'X')
        .at(40, 12, '^').at(41, 12, '^').at(44, 12, 'n')
        .at(50, 12, 'r').at(53, 12, '|')
        .fill(56, 110, 12, 12, '=').at(56, 12, 'M').at(66, 12, 'b').at(76, 12, 'b').at(86, 12, 'k').at(96, 12, 'b')
        .rock(114, 12, "DaD", "sss").at(120, 12, '^').at(121, 12, '^').at(122, 12, '^').at(126, 12, 'n')
        .at(132, 12, '|').at(136, 12, 'V').row(140, 9, "m*m")
        .at(146, 12, 'b').at(150, 12, 'b').at(154, 12, '^').at(155, 12, '^').at(160, 12, 'n')
        .rock(166, 12, "eDe", "sss")
        .fill(172, 172, 12, 12, 'X').fill(173, 173, 11, 12, 'X').fill(174, 174, 10, 12, 'X')
        .at(190, 12, 'F')
        .onGround('p', 46, 128, 170)
        .build(
            "SPIKE TEMPLE", Place.DESERT,
            "Watch out for SPIKE TRAPS! Jump over them.",
        )

    private fun fireFortress() = Builder(200)
        .ground(0, 40).ground(44, 90).ground(94, 140).ground(145, 199)
        .fill(41, 43, 13, 14, 'L').fill(91, 93, 13, 14, 'L').fill(141, 144, 13, 14, 'L')
        .at(3, 12, '@').rock(7, 12, "oDa", "sss").row(14, 9, "?h?")
        .at(20, 12, 'b').at(24, 8, 'v').at(28, 12, 'n').at(34, 12, '^').at(35, 12, '^')
        .at(48, 12, 'b').at(52, 12, 'k').rock(56, 12, "aua", "sss").at(62, 12, 'n')
        .at(68, 12, 'r').at(72, 12, '|').at(76, 8, 'v').at(80, 12, 'b').row(84, 9, "m*m")
        .at(98, 12, 'n').at(104, 12, 'b').rock(108, 12, "DeD", "sss").at(114, 8, 'v').at(118, 12, 'k')
        .at(124, 12, '|').at(128, 12, 'n').at(134, 12, '^').at(135, 12, '^')
        .at(150, 12, 'b').at(156, 12, 'n').rock(162, 12, "aoa", "sss").at(170, 12, 'b')
        .fill(176, 176, 12, 12, 'X').fill(177, 177, 11, 12, 'X').at(190, 12, 'F')
        .onGround('p', 46, 100, 166)
        .build(
            "FIRE FORTRESS", Place.VOLCANO,
            "Endermen, Boneheads AND lava. Throw TNT to clear the way!",
        )

    private fun boneCastle() = Builder(160)
        .ground(0, 159)
        .fill(0, 159, 0, 1, 'X')
        .at(3, 12, '@').rock(7, 12, "DaDo", "ssss").row(14, 9, "?h?")
        .fill(19, 22, 13, 14, 'L').at(25, 12, 'b').at(28, 12, 'b').at(31, 8, 'v')
        .at(36, 12, 'n').fill(40, 43, 13, 14, 'L').row(40, 9, "cccc")
        .at(48, 12, 'b').rock(52, 12, "aDe", "sss").at(58, 12, 'r').at(61, 12, '|')
        .row(64, 9, "m*m").at(70, 12, 'b').at(74, 12, 'b').at(78, 8, 'v')
        .at(84, 12, '|')
        .row(98, 8, "h").row(118, 8, "h").row(108, 9, "m")
        .fill(133, 133, 2, 12, 'X')
        .at(120, 12, 'Y')
        .build(
            "BONE CASTLE", Place.CASTLE,
            "DRY BONES KING KRAG! Knock him down... and then do it again!",
        )

    private fun theEnd() = Builder(140)
        .ground(0, 139)
        .at(3, 12, '@').rock(7, 12, "DaD", "sss").row(13, 9, "?h?")
        .at(20, 12, 'n').at(28, 12, 'n').at(34, 12, 'r').at(38, 12, '|')
        .rock(42, 12, "aaD", "sss").row(48, 9, "m*m")
        .fill(76, 77, 10, 12, 'X').at(76, 9, 'C')
        .fill(104, 105, 10, 12, 'X').at(104, 9, 'C')
        .row(90, 6, "h")
        .fill(131, 131, 2, 12, 'X')
        .at(90, 4, 'Z')
        .build(
            "THE END", Place.END,
            "THE ENDER DRAGON! Break the purple crystals first, they heal it!",
        )
}

/** A playable copy of a [LevelDef] whose tiles can be mined away. */
class Level(val def: LevelDef) {
    val w = def.w
    val h = ROWS
    private val tiles = IntArray(w * h)
    /** Pickaxe damage taken so far, keyed by tile index. */
    val cracks = HashMap<Int, Int>()

    init {
        for (y in 0 until h) for (x in 0 until w) {
            tiles[y * w + x] = when (def.rows[y][x]) {
                '#' -> Tile.GROUND
                'd' -> Tile.DIRT
                'B' -> Tile.BRICK
                '?' -> Tile.QCOIN
                'h' -> Tile.QHEART
                'X' -> Tile.HARD
                's' -> Tile.STONE
                'i' -> Tile.IRON
                'o' -> Tile.GOLD
                'D' -> Tile.DIAMOND
                'e' -> Tile.EMERALD
                'L' -> Tile.LAVA
                'O' -> Tile.CLOUD
                'u' -> Tile.COPPER
                'a' -> Tile.AMETHYST
                '*' -> Tile.QSTAR
                'm' -> Tile.QMYSTERY
                '=', 'M' -> Tile.RAIL
                '^' -> Tile.SPIKES
                '~' -> Tile.VENT
                'R' -> Tile.RUBY
                'E' -> Tile.SAPPHIRE
                'x' -> Tile.OBSIDIAN
                'I' -> Tile.PLANK
                ':' -> Tile.MAST
                '%' -> Tile.RAINBOW
                else -> Tile.EMPTY
            }
        }
    }

    init {
        // A bad guy standing on the rails still has rails under him.
        for (y in 0 until h) for (x in 1 until w - 1) {
            val c = def.rows[y][x]
            if (c != '.' && tiles[y * w + x] == Tile.EMPTY &&
                tiles[y * w + x - 1] == Tile.RAIL && def.rows[y][x + 1] in "=M"
            ) tiles[y * w + x] = Tile.RAIL
        }
    }

    operator fun get(tx: Int, ty: Int): Int = when {
        tx < 0 || tx >= w -> Tile.HARD
        ty < 0 || ty >= h -> Tile.EMPTY
        else -> tiles[ty * w + tx]
    }

    operator fun set(tx: Int, ty: Int, t: Int) {
        if (tx in 0 until w && ty in 0 until h) {
            tiles[ty * w + tx] = t
            cracks.remove(ty * w + tx)
        }
    }

    fun solidAt(px: Float, py: Float) = Tile.solid(get(tileOf(px), tileOf(py)))

    /** The bottom row is bedrock, so nobody can dig a hole out of the world. */
    fun toughness(tx: Int, ty: Int) = if (ty >= h - 1) 0 else Tile.toughness(get(tx, ty))

    /** Set when an upward move hits a ceiling: the tile that was bumped. */
    var bumpX = -1
    var bumpY = -1

    /** Moves [b] sideways; returns true if it ran into a wall. */
    fun moveX(b: Body, dx: Float): Boolean {
        b.x += dx
        val top = tileOf(b.y + 1)
        val bot = tileOf(b.bottom - 1)
        if (dx > 0) {
            val tx = tileOf(b.x + b.w)
            for (ty in top..bot) if (Tile.solid(get(tx, ty))) { b.x = tx * TILE - b.w - 0.01f; return true }
        } else if (dx < 0) {
            val tx = tileOf(b.x)
            for (ty in top..bot) if (Tile.solid(get(tx, ty))) { b.x = (tx + 1) * TILE + 0.01f; return true }
        }
        return false
    }

    /** Moves [b] up or down. Returns 1 when it lands, -1 when it bumps its head, otherwise 0. */
    fun moveY(b: Body, dy: Float): Int {
        val oldBottom = b.bottom
        b.y += dy
        b.onGround = false
        val l = tileOf(b.x + 1)
        val r = tileOf(b.x + b.w - 1)
        if (dy > 0) {
            val ty = tileOf(b.bottom)
            for (tx in l..r) {
                val t = get(tx, ty)
                if (Tile.solid(t) || (t == Tile.CLOUD && oldBottom <= ty * TILE + 0.5f)) {
                    b.y = ty * TILE - b.h
                    b.vy = 0f
                    b.onGround = true
                    return 1
                }
            }
        } else if (dy < 0) {
            val ty = tileOf(b.y)
            val mid = tileOf(b.cx)
            val hit = if (Tile.solid(get(mid, ty))) mid else (l..r).firstOrNull { Tile.solid(get(it, ty)) }
            if (hit != null) {
                b.y = (ty + 1) * TILE + 0.01f
                b.vy = 0f
                bumpX = hit
                bumpY = ty
                return -1
            }
        }
        return 0
    }

    fun inLava(b: Body): Boolean {
        val ty = tileOf(b.bottom - 2)
        return get(tileOf(b.x + 2), ty) == Tile.LAVA || get(tileOf(b.x + b.w - 2), ty) == Tile.LAVA
    }
}
