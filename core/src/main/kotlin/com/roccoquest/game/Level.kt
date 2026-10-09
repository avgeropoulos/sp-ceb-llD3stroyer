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
    /** Cloud platform: you can jump up through it and land on top. */
    const val CLOUD = 13

    fun solid(t: Int) = t != EMPTY && t != LAVA && t != CLOUD
}

enum class Theme(val music: Music, val slippery: Boolean = false) {
    OVERWORLD(Music.OVERWORLD),
    SNOW(Music.SKY, slippery = true),
    DESERT(Music.OVERWORLD),
    UNDERGROUND(Music.UNDERGROUND),
    SKY(Music.SKY),
    NIGHT(Music.SKY),
    CASTLE(Music.CASTLE),
}

/** Immutable description of a level: a character grid plus metadata. */
class LevelDef(
    val name: String,
    val theme: Theme,
    val rows: List<String>,
    val world: Int,
    val num: Int,
    /** King Krag's hit points, for castle levels. */
    val bossHp: Int = 10,
)

/**
 * Character legend:
 *  '#' ground   'B' brick   '?' coin block   'X' hard block
 *  Item blocks: 'P' Blaze Blossom   '1' mushroom   '3' ice flower   '4' boomerang flower
 *               '5' super star   '6' mini mushroom
 *  '[' ']' pipe top   '{' '}' pipe body   'L' lava   '=' bridge
 *  '@' hero start   'g' grumbler   'k' shellback   'c' coin   'p' lava bubble
 *  'O' cloud platform   'w' winged grumbler   'J' Krag Jr. in his clown car
 *  'K' King Krag   'R' Princess Rosalie   'M' Pip the messenger   'A' axe
 *  'F' flagpole (top)   'C' castle   'E' exit door
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
    fun clouds(x0: Int, x1: Int, y: Int) = fill(x0, x1, y, y, 'O')
    fun lava(x0: Int, x1: Int) = fill(x0, x1, 13, LEVEL_ROWS - 1, 'L')
    fun flagAndCastle(x: Int) = at(x, 12, 'X').at(x, 3, 'F').at(x + 4, 12, 'C')
    fun build(name: String, theme: Theme, world: Int, num: Int, bossHp: Int = 10) =
        LevelDef(name, theme, g.map { String(it) }, world, num, bossHp)
}

object Levels {
    val all: List<LevelDef> by lazy {
        listOf(
            meadow(), caverns(), cloudtop(), fortress(),
            dunes(), deepCaverns(), skyway(), volcano(),
            frostyPeaks(), pipeGorge(), skyArmada(), lastStand(),
        )
    }

    private fun meadow(): LevelDef = LevelBuilder(212)
        .ground(0, 68).ground(71, 85).ground(89, 150).ground(153, 211)
        .at(3, 12, '@')
        .row(16, 9, "?")
        .row(20, 9, "B1B?B").row(22, 5, "?")
        .at(24, 12, 'g')
        .pipe(28, 2).pipe(38, 3).pipe(46, 4).pipe(57, 4)
        .at(42, 12, 'g').at(51, 12, 'g').at(53, 12, 'g')
        .row(62, 9, "cccc").at(65, 12, 'k')
        .row(77, 9, "B?B").row(80, 5, "BBBBBBBB").at(82, 4, 'g').at(85, 4, 'g')
        .row(91, 5, "BBB?").row(94, 9, "B").row(91, 4, "ccc")
        .at(97, 12, 'g').at(99, 12, 'g')
        .row(100, 9, "BP").row(106, 9, "?").row(109, 9, "?").row(109, 5, "5").row(112, 9, "?")
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
        .build("MEADOW HILLS", Theme.OVERWORLD, 1, 1)

    private fun caverns(): LevelDef = LevelBuilder(176)
        .ground(0, 79).ground(83, 119).ground(123, 175)
        .fill(0, 0, 2, 12, 'B').fill(6, 160, 2, 2, 'B')
        .at(3, 12, '@')
        .row(10, 9, "??6??").at(16, 12, 'g').at(18, 12, 'g')
        .stairsUp(22, 2).stairsUp(26, 3).stairsUp(30, 4).fill(34, 35, 9, 12, 'X')
        .row(38, 8, "cccccc").at(40, 12, 'k')
        .fill(44, 45, 5, 9, 'B').fill(46, 51, 9, 9, 'B').row(46, 8, "cccccc")
        .row(52, 5, "BBB3BB").at(50, 12, 'g').at(54, 12, 'g')
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
        .build("CRYSTAL CAVERNS", Theme.UNDERGROUND, 1, 2)

    private fun fortress(): LevelDef = LevelBuilder(132)
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
        .ground(64, 75, 10).row(68, 6, "B3B").at(72, 9, 'k')
        .fill(76, 79, 13, 14, 'L').at(77, 13, 'p')
        .ground(80, 86, 10)
        // Boss bridge over lava
        .fill(87, 104, 13, 14, 'L')
        .fill(87, 104, 10, 10, '=')
        .at(99, 9, 'K')
        .ground(105, 131, 10).at(105, 9, 'A')
        .fill(105, 107, 3, 7, '#')
        .at(122, 9, 'M')
        .fill(131, 131, 3, 9, '#')
        .build("KRAG'S FORTRESS", Theme.CASTLE, 1, 4, bossHp = 6)

    private fun cloudtop(): LevelDef = LevelBuilder(190)
        .ground(0, 12).at(3, 12, '@')
        .clouds(16, 20, 11).row(16, 9, "c c c")
        .ground(23, 28, 10).at(26, 9, 'g')
        .clouds(31, 34, 8).row(31, 6, "cccc")
        .clouds(37, 41, 10).at(39, 9, 'w')
        .ground(44, 52, 12).row(46, 8, "?1?").at(50, 11, 'k')
        .clouds(55, 58, 10).clouds(61, 64, 8).clouds(67, 70, 6).row(67, 4, "cccc")
        .ground(73, 80, 9).at(76, 8, 'g').at(78, 8, 'g')
        .clouds(82, 83, 11)
        .ground(86, 95, 10).pipe(90, 2, base = 10).at(93, 9, 'w')
        .clouds(98, 103, 9).row(99, 5, "B4B")
        .clouds(106, 108, 11).clouds(111, 113, 9).clouds(116, 118, 7).row(111, 7, "ccc")
        .ground(121, 130, 9).at(125, 8, 'k').at(128, 8, 'g')
        .ground(134, 136, 11).ground(140, 142, 9).row(140, 7, "ccc")
        .clouds(146, 150, 10).at(148, 9, 'w')
        .ground(153, 189).row(156, 9, "B?B")
        .stairsUp(160, 5).fill(165, 165, 8, 12, 'X')
        .flagAndCastle(175)
        .build("CLOUDTOP HEIGHTS", Theme.SKY, 1, 3)

    private fun dunes(): LevelDef = LevelBuilder(200)
        .ground(0, 40).ground(44, 70).ground(73, 110).ground(115, 150).ground(154, 199)
        .at(3, 12, '@')
        .row(14, 9, "B?P?B").row(16, 5, "?")
        .at(20, 12, 'g').at(24, 12, 'k').pipe(28, 3).at(33, 12, 'g')
        .stairsUp(36, 3).fill(39, 40, 10, 12, 'X')
        .row(48, 9, "BBBBB").row(48, 8, "ccccc").at(52, 12, 'k').at(55, 12, 'g').at(57, 12, 'g')
        .pipe(60, 4).pipe(66, 3).at(64, 12, 'w')
        .row(76, 9, "?").row(80, 9, "B?B").row(80, 5, "BBBBBB").at(84, 4, 'w').at(88, 12, 'g').at(90, 12, 'g')
        .pipe(94, 2).at(98, 12, 'k').row(100, 9, "B4B")
        .stairsUp(105, 4).fill(109, 110, 9, 12, 'X').row(112, 9, "XX")
        .at(120, 12, 'w').at(124, 12, 'w').row(122, 9, "B?5?B").at(128, 12, 'g')
        .pipe(132, 3).at(137, 12, 'k')
        .stairsUp(142, 5).fill(147, 150, 8, 12, 'X')
        .stairsDown(154, 4)
        .at(162, 12, 'g').at(164, 12, 'g').row(166, 9, "?c?").pipe(170, 2)
        .stairsUp(176, 8).fill(184, 184, 5, 12, 'X')
        .flagAndCastle(190)
        .build("SUNSET DUNES", Theme.DESERT, 2, 1)

    private fun deepCaverns(): LevelDef = LevelBuilder(170)
        .ground(0, 30).ground(34, 60).ground(65, 95).ground(99, 125).ground(129, 169)
        .fill(0, 0, 2, 12, 'B').fill(6, 150, 2, 2, 'B')
        .at(3, 12, '@')
        .row(8, 9, "?1?").at(14, 12, 'g').at(16, 12, 'g').at(22, 12, 'k').pipe(26, 2)
        .fill(36, 39, 9, 9, 'B').row(36, 8, "cccc").at(38, 8, 'w').row(41, 9, "B")
        .row(44, 5, "BBBBBBB").row(44, 4, "ccccccc")
        .at(48, 12, 'g').at(50, 12, 'g').at(54, 12, 'k').pipe(57, 3)
        .row(62, 9, "BB")
        .stairsUp(66, 3).stairsDown(70, 3)
        .at(76, 12, 'w').at(80, 12, 'w').row(78, 9, "B?B?B").row(79, 5, "c3c")
        .pipe(86, 4).at(90, 12, 'g').at(92, 12, 'g')
        .fill(101, 104, 10, 12, 'X').fill(105, 108, 8, 12, 'X').at(103, 9, 'g').at(107, 7, 'w')
        .row(112, 8, "cccccc").at(115, 12, 'k').at(118, 12, 'g').pipe(121, 2)
        .stairsUp(131, 4).row(137, 6, "BBBB").row(137, 5, "cccc")
        .at(140, 12, 'g').at(142, 12, 'g').at(146, 12, 'w')
        .stairsUp(150, 4).stairsDown(154, 3)
        .fill(158, 169, 2, 9, 'X').at(164, 12, 'E').fill(165, 169, 10, 12, 'X')
        .build("DEEP CAVERNS", Theme.UNDERGROUND, 2, 2)

    private fun skyway(): LevelDef = LevelBuilder(200)
        .ground(0, 10).at(3, 12, '@')
        .clouds(13, 15, 11).clouds(18, 20, 9)
        .ground(23, 30, 9).row(25, 5, "?P?").at(28, 8, 'w')
        .clouds(33, 36, 11).clouds(39, 42, 9).clouds(45, 48, 7).row(45, 5, "cccc")
        .clouds(51, 54, 9).at(52, 8, 'w').clouds(57, 60, 11)
        .ground(63, 70, 10).at(66, 9, 'k').at(68, 9, 'g')
        .ground(74, 80, 10).at(77, 9, 'w')
        .clouds(83, 85, 8).clouds(88, 90, 6).clouds(93, 95, 8).at(94, 7, 'w').clouds(98, 100, 10)
        .ground(103, 112, 11).at(106, 10, 'g').at(108, 10, 'g').pipe(110, 2, base = 11)
        .clouds(115, 117, 9).clouds(120, 122, 7).row(120, 5, "ccc")
        .clouds(125, 128, 9).at(127, 8, 'w').clouds(131, 133, 11)
        .ground(136, 142, 10).at(139, 9, 'k')
        .ground(147, 150, 10).row(147, 6, "B5B")
        .clouds(153, 156, 8).clouds(159, 162, 10).at(161, 9, 'w')
        .ground(165, 199).at(168, 12, 'g')
        .at(178, 6, 'J').row(171, 9, "XX")
        .stairsUp(181, 3).flagAndCastle(190)
        .build("STARLIGHT SKYWAY", Theme.NIGHT, 2, 3)

    private fun volcano(): LevelDef = LevelBuilder(170)
        .fill(0, 169, 2, 2, '#').fill(0, 12, 3, 5, '#')
        .ground(0, 12, 10).at(2, 9, '@').row(8, 6, "P")
        .lava(13, 16).at(15, 13, 'p')
        .ground(17, 22, 10).at(20, 9, 'g')
        .lava(23, 26).at(24, 13, 'p')
        .ground(27, 29, 8)
        .lava(30, 33).at(31, 13, 'p')
        .ground(34, 36, 7)
        .lava(37, 40).at(38, 13, 'p').at(40, 13, 'p')
        .ground(41, 52, 10).fill(45, 48, 3, 6, '#').at(44, 9, 'k').at(47, 9, 'g').at(49, 9, 'g')
        .lava(53, 60).row(55, 8, "X").row(58, 8, "X").at(56, 13, 'p').at(60, 13, 'p')
        .ground(61, 72, 10).row(64, 6, "B3B").at(67, 9, 'w').at(70, 9, 'k')
        .lava(73, 76).at(74, 13, 'p')
        .ground(77, 79, 8)
        .lava(80, 83).at(82, 13, 'p')
        .ground(84, 90, 10).at(87, 9, 'g').row(86, 6, "4")
        // Final battle on the bridge
        .lava(91, 114).fill(91, 114, 10, 10, '=')
        .at(108, 9, 'K')
        .ground(115, 169, 10).at(115, 9, 'A').fill(115, 117, 3, 7, '#')
        .at(150, 9, 'M').fill(169, 169, 3, 9, '#')
        .build("KRAG'S VOLCANO", Theme.CASTLE, 2, 4, bossHp = 10)

    private fun frostyPeaks(): LevelDef = LevelBuilder(200)
        .ground(0, 30).ground(34, 60).ground(64, 100).ground(105, 140).ground(144, 199)
        .at(3, 12, '@')
        .row(8, 9, "?3?").at(14, 12, 'g').at(18, 12, 'k').at(22, 12, 'w')
        .stairsUp(26, 4).fill(30, 30, 9, 12, 'X')
        .row(38, 9, "BBBB").row(38, 5, "?6?").row(36, 3, "cccccc")
        .at(44, 12, 'g').at(46, 12, 'g').at(50, 12, 'k').pipe(54, 3).at(58, 12, 'w')
        .row(68, 9, "B?B?B").row(70, 5, "BBB").at(74, 12, 'g').at(76, 12, 'g').at(80, 12, 'w')
        .pipe(84, 2).pipe(90, 3).at(95, 12, 'k')
        .stairsUp(96, 4).fill(100, 100, 9, 12, 'X').clouds(102, 103, 11)
        .row(108, 9, "?5?").at(112, 12, 'g').at(114, 12, 'g').at(116, 12, 'g').at(118, 12, 'g')
        .at(122, 12, 'k').row(126, 9, "BBBBBB").row(126, 8, "cccccc").at(130, 12, 'w').pipe(134, 4)
        .stairsUp(146, 3).at(152, 12, 'g').at(156, 12, 'k').at(160, 12, 'g')
        .row(164, 9, "B?B").stairsUp(176, 8).fill(184, 184, 5, 12, 'X')
        .flagAndCastle(190)
        .build("FROSTY PEAKS", Theme.SNOW, 3, 1)

    private fun pipeGorge(): LevelDef = LevelBuilder(200)
        .ground(0, 50).ground(54, 90).ground(94, 199)
        .at(3, 12, '@')
        .row(10, 9, "?P?").pipe(16, 2).pipe(22, 3).pipe(28, 4).at(19, 12, 'g').at(25, 12, 'g')
        .at(32, 12, 'w').at(36, 12, 'w').row(40, 9, "B4B").at(44, 12, 'k')
        .stairsUp(46, 3).fill(49, 50, 10, 12, 'X')
        .pipe(58, 3).pipe(64, 4).pipe(70, 2).at(62, 12, 'g').at(68, 12, 'g')
        .row(74, 9, "BBBBBB").row(74, 5, "B?B5BB").at(80, 12, 'k').at(84, 12, 'g').at(86, 12, 'g')
        .stairsUp(87, 4)
        .row(98, 9, "?1?").at(102, 12, 'w').at(106, 12, 'w').pipe(110, 3).at(116, 12, 'k').row(118, 9, "BBB")
        // Krag Jr. arena
        .row(134, 9, "XX").row(142, 8, "XX").at(150, 6, 'J')
        .stairsUp(176, 8).fill(184, 184, 5, 12, 'X')
        .flagAndCastle(190)
        .build("PIPE GORGE", Theme.OVERWORLD, 3, 2)

    private fun skyArmada(): LevelDef = LevelBuilder(200)
        .ground(0, 10).at(3, 12, '@')
        .clouds(13, 16, 10).clouds(19, 22, 8)
        .ground(25, 32, 10).row(27, 6, "?4?").at(30, 9, 'w')
        .clouds(35, 38, 11).at(37, 10, 'w').clouds(41, 44, 9).clouds(47, 50, 7).row(47, 5, "cccc")
        .clouds(53, 56, 9).at(55, 8, 'w')
        .ground(59, 68, 10).at(62, 9, 'k').at(64, 9, 'g').pipe(66, 2, base = 10)
        .clouds(71, 73, 8).clouds(76, 78, 10).clouds(81, 83, 8).at(82, 7, 'w').clouds(86, 88, 10)
        .ground(91, 100, 11).at(94, 10, 'g').at(96, 10, 'g').row(97, 7, "?1?")
        .clouds(103, 105, 9).clouds(108, 110, 7).row(108, 5, "ccc").clouds(113, 115, 9).at(114, 8, 'w')
        .ground(118, 124, 10).at(121, 9, 'k')
        .clouds(127, 129, 9).clouds(132, 134, 11)
        // Krag Jr. arena on the last island
        .ground(137, 199, 12).clouds(143, 146, 8).clouds(154, 157, 8).at(162, 5, 'J')
        .stairsUp(176, 6, base = 12).fill(182, 182, 5, 11, 'X')
        .at(190, 11, 'X').at(190, 3, 'F').at(194, 11, 'C')
        .build("SKY ARMADA", Theme.SKY, 3, 3)

    private fun lastStand(): LevelDef = LevelBuilder(200)
        .fill(0, 199, 2, 2, '#').fill(0, 12, 3, 5, '#')
        .ground(0, 12, 10).at(2, 9, '@').row(8, 6, "P")
        .lava(13, 16).at(14, 13, 'p')
        .ground(17, 24, 10).at(20, 9, 'g').at(23, 9, 'k')
        .lava(25, 28).at(26, 13, 'p').at(28, 13, 'p')
        .ground(29, 31, 8)
        .lava(32, 35).at(33, 13, 'p')
        .ground(36, 38, 7)
        .lava(39, 42).at(40, 13, 'p')
        .ground(43, 56, 10).fill(47, 50, 3, 6, '#').at(45, 9, 'g').at(49, 9, 'w').at(53, 9, 'g').row(54, 6, "?3?")
        // Krag Jr. ambush in the great hall
        .ground(57, 85, 10).row(64, 7, "XX").row(74, 7, "XX").at(80, 5, 'J')
        .lava(86, 89).at(87, 13, 'p')
        .ground(90, 96, 10).row(92, 6, "5")
        // Final battle
        .lava(97, 122).fill(97, 122, 10, 10, '=')
        .at(116, 9, 'K')
        .ground(123, 199, 10).at(123, 9, 'A').fill(123, 125, 3, 7, '#')
        .at(180, 9, 'R').fill(199, 199, 3, 9, '#')
        .build("KRAG'S LAST STAND", Theme.CASTLE, 3, 4, bossHp = 14)
}

/** Mutable runtime tile map. */
class Level(val def: LevelDef) {
    val w = def.rows[0].length
    val h = LEVEL_ROWS
    val tiles = IntArray(w * h)
    val pixelW get() = w * TILE
    /** What each item block (T.QPOWER) contains, keyed by tile index. */
    private val items = HashMap<Int, Item>()

    fun itemAt(x: Int, y: Int): Item = items[y * w + x] ?: Item.FIRE

    init {
        for (y in 0 until h) for (x in 0 until w) {
            tiles[y * w + x] = when (def.rows[y][x]) {
                '#' -> T.GROUND
                'B' -> T.BRICK
                '?' -> T.QCOIN
                'P', '1', '3', '4', '5', '6' -> T.QPOWER
                'X' -> T.HARD
                '[' -> T.PIPE_TL
                ']' -> T.PIPE_TR
                '{' -> T.PIPE_L
                '}' -> T.PIPE_R
                'L', 'p' -> T.LAVA
                'O' -> T.CLOUD
                '=' -> T.BRIDGE
                else -> T.EMPTY
            }
            itemFor(def.rows[y][x])?.let { items[y * w + x] = it }
        }
    }

    private fun itemFor(c: Char) = when (c) {
        'P' -> Item.FIRE
        '1' -> Item.MUSHROOM
        '3' -> Item.ICE
        '4' -> Item.BOOM
        '5' -> Item.STAR
        '6' -> Item.MINI
        else -> null
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
            val wasAbove = b.y + b.h - dy <= ty * TILE + 0.01f
            for (tx in left..right) if (solid(tx, ty) || (wasAbove && this[tx, ty] == T.CLOUD)) {
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
