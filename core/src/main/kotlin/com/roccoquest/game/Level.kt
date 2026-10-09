package com.roccoquest.game

import kotlin.math.floor

const val TILE = 16
const val VIEW_H = 240f
const val LEVEL_ROWS = 15

object T {
    const val EMPTY = 0
    const val GROUND = 1
    const val BRICK = 2
    const val QCOIN = 3
    const val QPOWER = 4
    const val USED = 5
    const val HARD = 6
    const val PIPE_TL = 7
    const val PIPE_TR = 8
    const val PIPE_L = 9
    const val PIPE_R = 10
    const val LAVA = 11
    const val BRIDGE = 12

    fun solid(t: Int) = t != EMPTY && t != LAVA
}

enum class Theme { OVERWORLD, UNDERGROUND, CASTLE }

/** Immutable description of a level: a character grid plus metadata. */
class LevelDef(val name: String, val theme: Theme, val rows: List<String>)

/**
 * Character legend:
 *  '#' ground   'B' brick   '?' coin block   'P' power block   'X' hard block
 *  '[' ']' pipe top   '{' '}' pipe body   'L' lava   '=' bridge
 *  '@' hero start   'g' grumbler   'k' shellback   'c' coin   'p' lava bubble
 *  'K' King Krag   'R' Princess Rosalie   'A' axe   'F' flagpole (top)   'C' castle   'E' exit door
 */
class LevelBuilder(private val w: Int) {
    private val g = Array(LEVEL_ROWS) { CharArray(w) { '.' } }

    fun at(x: Int, y: Int, c: Char) = apply { if (x in 0 until w && y in 0 until LEVEL_ROWS) g[y][x] = c }
    fun row(x: Int, y: Int, s: String) = apply { s.forEachIndexed { i, c -> if (c != ' ') at(x + i, y, c) } }
    fun fill(x0: Int, x1: Int, y0: Int, y1: Int, c: Char) = apply {
        for (y in y0..y1) for (x in x0..x1) at(x, y, c)
    }
    fun ground(x0: Int, x1: Int, top: Int = 13) = fill(x0, x1, top, LEVEL_ROWS - 1, '#')
    fun pipe(x: Int, height: Int, base: Int = 13) = apply {
        val top = base - height
        at(x, top, '['); at(x + 1, top, ']')
        for (y in top + 1 until base) { at(x, y, '{'); at(x + 1, y, '}') }
    }
    fun stairsUp(x: Int, n: Int, base: Int = 13) = apply { for (i in 0 until n) fill(x + i, x + i, base - 1 - i, base - 1, 'X') }
    fun stairsDown(x: Int, n: Int, base: Int = 13) = apply { for (i in 0 until n) fill(x + i, x + i, base - n + i, base - 1, 'X') }
    fun build(name: String, theme: Theme) = LevelDef(name, theme, g.map { String(it) })
}

object Levels {
    val all: List<LevelDef> by lazy { listOf(meadow(), caverns(), castle()) }

    private fun meadow(): LevelDef = LevelBuilder(212)
        .ground(0, 68).ground(71, 85).ground(89, 150).ground(153, 211)
        .at(3, 12, '@')
        .row(16, 9, "?")
        .row(20, 9, "BPB?B").row(22, 5, "?")
        .at(24, 12, 'g')
        .pipe(28, 2).pipe(38, 3).pipe(46, 4).pipe(57, 4)
        .at(42, 12, 'g').at(51, 12, 'g').at(53, 12, 'g')
        .row(62, 9, "cccc").at(65, 12, 'k')
        .row(77, 9, "B?B").row(80, 5, "BBBBBBBB").at(82, 4, 'g').at(85, 4, 'g')
        .row(91, 5, "BBB?").row(94, 9, "B").row(91, 4, "ccc")
        .at(97, 12, 'g').at(99, 12, 'g')
        .row(100, 9, "BP").row(106, 9, "?").row(109, 9, "?").row(109, 5, "?").row(112, 9, "?")
        .at(107, 12, 'k').at(114, 12, 'g').at(116, 12, 'g')
        .row(118, 9, "B").row(121, 5, "BBB").row(128, 5, "B??B").row(129, 9, "BB")
        .at(124, 12, 'g').at(126, 12, 'g').at(131, 12, 'g').at(133, 12, 'g')
        .stairsUp(135, 4).stairsDown(140, 4)
        .stairsUp(145, 5).fill(150, 150, 8, 12, 'X')
        .stairsDown(153, 4)
        .pipe(160, 2).row(163, 8, "cccc").at(166, 12, 'g').at(168, 12, 'g')
        .row(170, 9, "BB?B").at(175, 12, 'k')
        .pipe(178, 2)
        .stairsUp(182, 8).fill(190, 190, 5, 12, 'X')
        .at(199, 12, 'X').at(199, 3, 'F')
        .at(203, 12, 'C')
        .build("MEADOW HILLS", Theme.OVERWORLD)

    private fun caverns(): LevelDef = LevelBuilder(176)
        .ground(0, 79).ground(83, 119).ground(123, 175)
        .fill(0, 0, 2, 12, 'B').fill(6, 160, 2, 2, 'B')
        .at(3, 12, '@')
        .row(10, 9, "?????").at(16, 12, 'g').at(18, 12, 'g')
        .stairsUp(22, 2).stairsUp(26, 3).stairsUp(30, 4).fill(34, 35, 9, 12, 'X')
        .row(38, 8, "cccccc").at(40, 12, 'k')
        .fill(44, 45, 5, 9, 'B').fill(46, 51, 9, 9, 'B').row(46, 8, "cccccc")
        .row(52, 5, "BBBPBB").at(50, 12, 'g').at(54, 12, 'g')
        .pipe(60, 3).pipe(66, 2).at(64, 12, 'g')
        .row(71, 9, "BBBB").row(71, 5, "c?cc").at(74, 12, 'k')
        .row(80, 9, "BB").row(80, 8, "cc")
        .row(86, 9, "B?B?B").at(88, 12, 'g').at(91, 12, 'g')
        .fill(95, 97, 6, 6, 'B').fill(100, 103, 9, 9, 'B').row(100, 8, "cccc")
        .at(106, 12, 'g').at(108, 12, 'g').at(110, 12, 'g')
        .pipe(113, 4)
        .row(117, 9, "BB").row(124, 9, "BBB").row(124, 5, "BPB")
        .at(128, 12, 'k').at(131, 12, 'g')
        .stairsUp(134, 4).stairsDown(140, 4).row(135, 6, "ccccccc")
        .at(147, 12, 'g').at(149, 12, 'g')
        .pipe(152, 3)
        .stairsUp(156, 4).stairsDown(160, 3)
        .fill(164, 175, 2, 9, 'X').fill(170, 170, 10, 12, '.').at(170, 12, 'E')
        .fill(171, 175, 10, 12, 'X')
        .build("CRYSTAL CAVERNS", Theme.UNDERGROUND)

    private fun castle(): LevelDef = LevelBuilder(132)
        // Ceiling and floor
        .fill(0, 131, 2, 2, '#').fill(0, 15, 3, 5, '#')
        .ground(0, 15, 10)
        .at(3, 9, '@')
        .row(9, 6, "P")
        .fill(16, 19, 13, 14, 'L').at(17, 13, 'p')
        .ground(20, 27, 10)
        .fill(28, 31, 13, 14, 'L').at(29, 13, 'p')
        .ground(32, 37, 8).at(35, 7, 'k')
        .fill(38, 41, 13, 14, 'L').at(39, 13, 'p')
        .ground(42, 55, 10).fill(48, 51, 3, 5, '#').at(48, 9, 'g').at(50, 9, 'g').at(53, 9, 'g')
        .fill(56, 63, 13, 14, 'L').at(62, 13, 'p').row(59, 8, "XX").row(59, 6, "cc")
        .ground(64, 75, 10).row(68, 6, "BPB").at(72, 9, 'k')
        .fill(76, 79, 13, 14, 'L').at(77, 13, 'p')
        .ground(80, 86, 10)
        // Boss bridge over lava
        .fill(87, 104, 13, 14, 'L')
        .fill(87, 104, 10, 10, '=')
        .at(99, 9, 'K')
        .ground(105, 131, 10).at(105, 9, 'A')
        .fill(105, 107, 3, 7, '#')
        .at(122, 9, 'R')
        .fill(131, 131, 3, 9, '#')
        .build("KRAG'S CASTLE", Theme.CASTLE)
}

/** Mutable runtime tile map. */
class Level(val def: LevelDef) {
    val w = def.rows[0].length
    val h = LEVEL_ROWS
    val tiles = IntArray(w * h)
    val pixelW get() = w * TILE

    init {
        for (y in 0 until h) for (x in 0 until w) {
            tiles[y * w + x] = when (def.rows[y][x]) {
                '#' -> T.GROUND
                'B' -> T.BRICK
                '?' -> T.QCOIN
                'P' -> T.QPOWER
                'X' -> T.HARD
                '[' -> T.PIPE_TL
                ']' -> T.PIPE_TR
                '{' -> T.PIPE_L
                '}' -> T.PIPE_R
                'L', 'p' -> T.LAVA
                '=' -> T.BRIDGE
                else -> T.EMPTY
            }
        }
    }

    operator fun get(x: Int, y: Int): Int = when {
        x < 0 || x >= w -> T.HARD // invisible side walls
        y < 0 || y >= h -> T.EMPTY
        else -> tiles[y * w + x]
    }

    operator fun set(x: Int, y: Int, t: Int) {
        if (x in 0 until w && y in 0 until h) tiles[y * w + x] = t
    }

    fun solid(x: Int, y: Int) = T.solid(this[x, y])

    fun isLavaAt(px: Float, py: Float) = this[tileOf(px), tileOf(py)] == T.LAVA

    /** Moves [b] horizontally by [dx]; returns true if it hit a wall. */
    fun moveX(b: Body, dx: Float): Boolean {
        b.x += dx
        val top = tileOf(b.y)
        val bot = tileOf(b.y + b.h - 0.01f)
        if (dx > 0) {
            val tx = tileOf(b.x + b.w - 0.01f)
            for (ty in top..bot) if (solid(tx, ty)) { b.x = tx * TILE - b.w; return true }
        } else if (dx < 0) {
            val tx = tileOf(b.x)
            for (ty in top..bot) if (solid(tx, ty)) { b.x = (tx + 1f) * TILE; return true }
        }
        return false
    }

    /**
     * Moves [b] vertically by [dy]. Sets [Body.onGround]. Returns the tile x of the
     * ceiling block that was hit (the one nearest the body's center), or null.
     */
    fun moveY(b: Body, dy: Float): Int? {
        b.y += dy
        b.onGround = false
        val left = tileOf(b.x)
        val right = tileOf(b.x + b.w - 0.01f)
        if (dy > 0) {
            val ty = tileOf(b.y + b.h - 0.01f)
            for (tx in left..right) if (solid(tx, ty)) {
                b.y = ty * TILE - b.h; b.onGround = true; return null
            }
        } else if (dy < 0) {
            val ty = tileOf(b.y)
            if (ty < 0) return null
            val cx = tileOf(b.x + b.w / 2)
            val hit = when {
                solid(cx, ty) -> cx
                solid(left, ty) -> left
                solid(right, ty) -> right
                else -> null
            }
            if (hit != null) { b.y = (ty + 1f) * TILE; return hit }
        }
        return null
    }
}

fun tileOf(p: Float): Int = floor(p / TILE).toInt()
