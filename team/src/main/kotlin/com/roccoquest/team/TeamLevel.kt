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

    fun solid(t: Int) = t != EMPTY && t != LAVA && t != CLOUD

    /** How many pickaxe points it takes to mine a tile; 0 means it can't be mined. */
    fun toughness(t: Int) = when (t) {
        GROUND, DIRT, BRICK -> 2
        STONE, IRON -> 4
        GOLD, EMERALD -> 6
        DIAMOND -> 8
        else -> 0
    }

    fun ore(t: Int): Ore? = when (t) {
        STONE -> Ore.STONE
        IRON -> Ore.IRON
        GOLD -> Ore.GOLD
        DIAMOND -> Ore.DIAMOND
        EMERALD -> Ore.EMERALD
        else -> null
    }
}

/** Things Blocky can dig up. Iron is the weakest metal, then gold, then diamond. */
enum class Ore(val label: String, val color: Int, val dark: Int) {
    STONE("STONE", 0xFFA8A8A8.toInt(), 0xFF6E6E6E.toInt()),
    IRON("IRON", 0xFFE8C0A0.toInt(), 0xFFB07850.toInt()),
    GOLD("GOLD", 0xFFFFD82A.toInt(), 0xFFC89A10.toInt()),
    DIAMOND("DIAMOND", 0xFF7FF4F0.toInt(), 0xFF20B8C0.toInt()),
    EMERALD("EMERALD", 0xFF3CE070.toInt(), 0xFF128A3A.toInt()),
}

enum class Place(val music: Music) {
    HILLS(Music.OVERWORLD),
    CAVE(Music.UNDERGROUND),
    SKY(Music.SKY),
    CASTLE(Music.CASTLE),
}

/**
 * A level as a character grid.
 *
 *  '#' ground   'd' dirt   'B' brick   '?' coin block   'h' heart block   'X' hard block
 *  's' stone   'i' iron ore   'o' gold ore   'D' diamond ore   'e' emerald ore
 *  'L' lava   'O' cloud   '@' start   'c' coin   'g' grumbler   'w' winged grumbler
 *  'k' shellback   '|' checkpoint   'F' goal flag   'r' Ring-Ding Frog drives by here
 *  'J' Wonder Krag Jr.   'K' King Krag
 */
class LevelDef(val name: String, val place: Place, val rows: List<String>, val tip: String) {
    val w get() = rows[0].length
}

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

    fun build(name: String, place: Place, tip: String) = LevelDef(name, place, g.map { String(it) }, tip)
}

object Levels {
    val all: List<LevelDef> by lazy { listOf(hills(), cave(), wonderSky(), castle()) }

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
        .build(
            "DIAMOND CAVE", Place.CAVE,
            "Diamonds are tough! Craft an IRON PICKAXE first.",
        )

    private fun wonderSky() = Builder(150)
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
            "WONDER SKIES", Place.SKY,
            "Wonder Krag Jr. is waiting above the clouds!",
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
                else -> Tile.EMPTY
            }
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
