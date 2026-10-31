package com.herculesapollo.walk

import com.roccoquest.game.Sprite
import com.roccoquest.game.pixelArt

/** Builds a sprite like [pixelArt], padding short rows on the right with transparency. */
private fun art(palette: Map<Char, Int>, vararg rows: String): Sprite {
    val w = rows.maxOf { it.length }
    return pixelArt(palette, *Array(rows.size) { rows[it].padEnd(w, '.') })
}

/** All pixel art for the walk, authored as character grids. Everything faces right. */
object WalkSprites {
    private const val K = 0xFF101010.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val PINK = 0xFFFF7A9A.toInt()
    private const val GOLD = 0xFFFFD21F.toInt()

    // ---------------------------------------------------------------- Hercules and Apollo
    /*
     * Both dogs are curly-coated doodles drawn from one template, colored by name:
     * B body, b body shadow, c curl highlight, H head, h head highlight, E floppy ear,
     * M muzzle, W beard, N nose, I eye, g eye glint, C collar, Y tag, T tail, t tail curl.
     */
    private val dogTop = arrayOf(
        "...............hHHHh",
        ".............HHHHHHHHH",
        "............HHHHhHHHHHH",
        "..TT.......HHHHHHHHHHHHH",
        ".TTtT.....EEEHHHHHHgIHHH",
        "TTTTtT...EEEEEHHHHHIIHMMM",
        "TtTTTT...EEEEEEHHHHHMMMNN",
        ".TTtTT...EEEEEEHHHHMMMMNN",
        "..TTTT..BEEEEEEhHHWWWWW",
        "...TTTBBBEEEEEECCCWWWW",
        "....BBBBcBEEEEEBCCYWW",
        "...BBcBBBBcBEEBBBB",
        "...BBBBBcBBBBBBcBBB",
        "...BcBBBBBBcBBBBBBB",
        "....BBbbbbbbbbbBBB",
    )
    private val standLegs = arrayOf(
        "....BBB.BB...BBB.BB",
        "....BBB.BB...BBB.BB",
        "....bbb.bbb..bbb.bbb",
    )
    private val walkLegs = arrayOf(
        "....BBB..BB..BB..BBB",
        "...BBB....BB.BB...BBB",
        "..bbb.....bbbbb....bbb",
    )
    private val leapLegs = arrayOf(
        "..BBB...........BBB",
        ".BB...............BBB",
        "bb...................bb",
    )

    /** Hercules: black curls, a white beard and a red collar. */
    const val HERC_BODY = 0xFF1E1A1C.toInt()
    const val HERC_COLLAR = 0xFFE52521.toInt()
    private val hercPal = mapOf(
        'B' to HERC_BODY, 'b' to 0xFF0E0C0D.toInt(), 'c' to 0xFF3B3538.toInt(), 'H' to 0xFF221E20.toInt(),
        'h' to 0xFF403A3D.toInt(), 'E' to 0xFF141113.toInt(), 'M' to 0xFF2A2527.toInt(), 'W' to 0xFFE8E4DC.toInt(),
        'N' to 0xFF050505.toInt(), 'I' to 0xFF050505.toInt(), 'g' to WHITE, 'C' to HERC_COLLAR, 'Y' to GOLD,
        'T' to 0xFF1A1618.toInt(), 't' to 0xFF3B3538.toInt(),
    )

    /** Apollo: tan curls, a silvery-grey head, reddish-brown ears and a dark tail. */
    const val APOLLO_BODY = 0xFFC9A27A.toInt()
    const val APOLLO_COLLAR = 0xFF2E7CF6.toInt()
    private val apolloPal = mapOf(
        'B' to APOLLO_BODY, 'b' to 0xFFA07E58.toInt(), 'c' to 0xFFE0C29A.toInt(), 'H' to 0xFF9C958E.toInt(),
        'h' to 0xFFC2BCB4.toInt(), 'E' to 0xFFA0552E.toInt(), 'M' to 0xFFB9905F.toInt(), 'W' to 0xFFD8B88E.toInt(),
        'N' to 0xFF101010.toInt(), 'I' to 0xFF101010.toInt(), 'g' to WHITE, 'C' to APOLLO_COLLAR, 'Y' to GOLD,
        'T' to 0xFF2A2018.toInt(), 't' to 0xFF4A3A2C.toInt(),
    )

    val hercStand = art(hercPal, *dogTop, *standLegs)
    val hercWalk = art(hercPal, *dogTop, *walkLegs)
    val hercLeap = art(hercPal, *dogTop, *leapLegs)
    val apolloStand = art(apolloPal, *dogTop, *standLegs)
    val apolloWalk = art(apolloPal, *dogTop, *walkLegs)
    val apolloLeap = art(apolloPal, *dogTop, *leapLegs)

    // ---------------------------------------------------------------- The walker
    private val walkerPal = mapOf(
        'C' to 0xFF2A9D8F.toInt(), 'S' to 0xFFE0A878.toInt(), 'H' to 0xFF3B2414.toInt(), 'K' to K,
        'J' to 0xFFE76F51.toInt(), 'N' to 0xFF264B8C.toInt(), 'O' to 0xFF333333.toInt(), 'W' to WHITE,
    )
    private val walkerTop = arrayOf(
        "...CCCCC",
        "..CCCCCCCC",
        "..HHSSSS",
        "..HSSSKS",
        "..HSSSSSS",
        "...SSSS",
        "....SS",
        "..JJJJJJ",
        ".JJJJJJJJ",
        ".JJJJJJJJJ",
        ".JJ.JJJJ.JJ",
        ".JJ.JJJJ..SS",
        ".SS.JJJJ",
        "....JJJJ",
        "....NNNN",
        "....NNNN",
    )
    val walkerStand = art(walkerPal, *walkerTop,
        "....NN.NN",
        "....NN.NN",
        "....NN.NN",
        "....NN.NN",
        "....NN.NN",
        "....NN.NN",
        "...OOO.OOO",
    )
    val walkerStepA = art(walkerPal, *walkerTop,
        "....NNNN",
        "...NN..NN",
        "...NN...NN",
        "..NN....NN",
        "..NN.....NN",
        "..NN.....NN",
        ".OOO.....OOO",
    )
    val walkerStepB = art(walkerPal, *walkerTop,
        "....NNNN",
        "....NNN",
        ".....NN",
        ".....NNN",
        "....NN.N",
        "....NN.NN",
        "...OOO.OOO",
    )

    // ---------------------------------------------------------------- Critters
    private val squirrelPal = mapOf(
        'G' to 0xFF8E6E53.toInt(), 'L' to 0xFFC9AE8E.toInt(), 'D' to 0xFF5E4630.toInt(), 'K' to K,
    )
    val squirrel = art(squirrelPal,
        ".DDD",
        "DDDDD",
        "DDGDD...GG",
        "DD.DD..GGKG",
        "DD..D.GGGGGG",
        ".DD..GGGLG",
        "..DDGGGLL",
        "...GGGGLL",
        "...GGGGGG",
        "...G.G.GG",
    )
    val squirrelHop = art(squirrelPal,
        "DDD",
        "DDDD....GG",
        "DGDD...GGKG",
        "DD.DD.GGGGGG",
        ".D..DGGGLG",
        "..DDGGGLL",
        "...GGGGLL",
        "..GGGGGGG",
        ".GG....GG",
        "G.......G",
    )
    /** Climbing a tree trunk, head up. */
    val squirrelClimb = art(squirrelPal,
        "..GG",
        ".GKKG",
        ".GGGG",
        "GGLLGG",
        ".GLLG",
        "GGLLGG",
        ".GGGG",
        "..GG.D",
        "...DDDD",
        "..DDDDD",
        "..DDDD",
        "...DD",
    )

    private val pigeonPal = mapOf(
        'G' to 0xFF8A8FA0.toInt(), 'D' to 0xFF5A6070.toInt(), 'P' to 0xFF6A9A8A.toInt(),
        'O' to 0xFFFF9A30.toInt(), 'K' to K,
    )
    val pigeon = art(pigeonPal,
        "......GG",
        ".....GKGO",
        ".....PP",
        "..GGGGPG",
        "DDGGGGGG",
        ".DDDGGG",
        "....OO",
    )
    val pigeonFly = art(pigeonPal,
        "..DD",
        "...DGG.GG",
        "....GGGKGO",
        "..GGGGGP",
        "DDGGGGGG",
        ".DDDGG",
        "..........",
    )
    private val gullColors = mapOf(
        0xFF8A8FA0.toInt() to WHITE, 0xFF5A6070.toInt() to 0xFF9AA4B0.toInt(),
        0xFF6A9A8A.toInt() to WHITE, 0xFFFF9A30.toInt() to 0xFFFFC21F.toInt(),
    )
    val gull = pigeon.recolor(gullColors)
    val gullFly = pigeonFly.recolor(gullColors)

    private val rabbitPal = mapOf(
        'B' to 0xFFB08A64.toInt(), 'L' to 0xFFF4EEE4.toInt(), 'K' to K, 'P' to PINK,
    )
    val rabbit = art(rabbitPal,
        ".......BB",
        ".......BPB",
        "........BB",
        "......BBBBB",
        "..L..BBBBKBB",
        ".LLBBBBBBBBP",
        "..BBBBBBBB",
        "..BBBBBBB",
        "..BB...BB",
    )
    val rabbitHop = art(rabbitPal,
        "........BB",
        "........BPB",
        ".........BB",
        ".......BBBBB",
        "..L..BBBBBKB",
        ".LLBBBBBBBBP",
        ".BBBBBBBBB",
        "BB.......BB",
        "B..........B",
    )

    private val catPal = mapOf(
        'O' to 0xFFE8892E.toInt(), 'D' to 0xFFB55E14.toInt(), 'L' to 0xFFF6C78A.toInt(), 'K' to K,
        'G' to 0xFF6CD04A.toInt(), 'P' to PINK, 'W' to WHITE,
    )
    val cat = art(catPal,
        "......D...D",
        "......OD.DO",
        "......OOOOO",
        "......OGOGO",
        ".......OPO",
        ".D....OOOOO",
        "D.....ODODO",
        "D....OOOOOOO",
        ".D...ODODOLO",
        "..DOOOOOOOLO",
        "...OOOO.OOOO",
    )
    val catHiss = art(catPal,
        "......D...D",
        "..D.D.OD.DO",
        ".DOOOOOOOOOO",
        "DOODODOGOGOO",
        "D.OOOOOOOKWK",
        "..OOOOOOOOPO",
        "..O.O..O.O",
        "..O.O..O.O",
        "..O.O..O.O",
    )

    private val raccoonPal = mapOf(
        'G' to 0xFF8A8A8A.toInt(), 'D' to 0xFF4A4A4A.toInt(), 'W' to 0xFFE8E8E8.toInt(), 'K' to K,
    )
    val raccoon = art(raccoonPal,
        ".........G.G",
        "........GGGGG",
        "........WWWWWW",
        "........DKDKDW",
        "..D.D...GGGGGK",
        ".GDGDGGGGGGGG",
        "GDGDGGGGGGGG",
        "...GGGGGGGG",
        "...GG.GG.GG",
    )

    private val crabPal = mapOf('R' to 0xFFE04A2A.toInt(), 'D' to 0xFFA82A14.toInt(), 'K' to K, 'W' to WHITE)
    val crab = art(crabPal,
        ".W.....W",
        ".K.....K",
        "RR.RRR.RR",
        ".RRRRRRR",
        "DRRRRRRRD",
        "D.D.D.D.D",
    )
    val crabStep = art(crabPal,
        ".W.....W",
        ".K.....K",
        "RR.RRR.RR",
        ".RRRRRRR",
        "DRRRRRRRD",
        ".D.D.D.D.",
    )

    private val skunkPal = mapOf('K' to 0xFF1A1A1A.toInt(), 'W' to WHITE, 'P' to PINK)
    val skunk = art(skunkPal,
        "..KKK",
        ".KWWKK",
        "KKWWWKK",
        "KK.WWKK..KK",
        "K...KWK.KWKK",
        "....KWWWWWWKK",
        "....KKKKKKKKWK",
        "....KKKKKKKKKKP",
        "....KK.KK.KK",
    )

    /** A green anole with its pink throat fan. */
    private val lizardPal = mapOf(
        'G' to 0xFF5BBF3A.toInt(), 'D' to 0xFF2F7A22.toInt(), 'P' to 0xFFFF6FA0.toInt(), 'K' to K,
    )
    val lizard = art(lizardPal,
        "..........DGG",
        "....DGGGGGGGKG",
        "DDGGGGGGGGGGGG",
        "....GGGGGG.PP",
        "....G..G..G",
        "...G..G..G",
    )
    val lizardRun = art(lizardPal,
        "..........DGG",
        "....DGGGGGGGKG",
        "DDGGGGGGGGGGGG",
        "....GGGGGG.PP",
        "...G..G...G",
        "....G..G...G",
    )
    /** Up the fence, head first. */
    val lizardClimb = art(lizardPal,
        "..GG",
        ".GKGG",
        "..GG",
        "G.GGP.G",
        ".GGGG",
        "..GG",
        ".GGGG",
        "G.GG.G",
        "..GG",
        "..GD",
        "..D",
        "..D",
        "...D",
    )

    private val frogPal = mapOf(
        'G' to 0xFF4CAF50.toInt(), 'L' to 0xFFC5E1A5.toInt(), 'K' to K, 'W' to WHITE, 'D' to 0xFF2E7D32.toInt(),
    )
    val frog = art(frogPal,
        ".WK....KW",
        ".GGG..GGG",
        "GGGGGGGGGG",
        "GGLLLLLLGG",
        ".GGLLLLGG",
        "DG.G..G.GD",
        "D........D",
    )
    val frogHop = art(frogPal,
        ".WK....KW",
        ".GGG..GGG",
        "GGGGGGGGGG",
        ".GLLLLLLG",
        "..GLLLLG",
        ".D......D",
        "D........D",
    )

    // ---------------------------------------------------------------- Bits and bobs
    val bone = art(mapOf('W' to 0xFFFFF6E0.toInt(), 'G' to 0xFFC9B48A.toInt()),
        "GW.....WG",
        "WWWWWWWWW",
        "WWWWWWWWW",
        "GW.....WG",
    )

    val heart = art(mapOf('R' to 0xFFE52521.toInt(), 'W' to WHITE),
        ".RR.RR",
        "RWRRRRR",
        "RRRRRRR",
        ".RRRRR",
        "..RRR",
        "...R",
    )
    val heartEmpty = heart.recolor(mapOf(0xFFE52521.toInt() to 0x66000000, WHITE to 0x66000000))
}
