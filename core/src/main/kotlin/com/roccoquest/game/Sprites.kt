package com.roccoquest.game

/** All pixel art in the game, authored as character grids. */
object Sprites {
    private const val K = 0xFF101010.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val RED = 0xFFE52521.toInt()
    private const val BLUE = 0xFF2B4FD8.toInt()

    // ---------------------------------------------------------------- Rocco (hero)
    private val heroPal = mapOf(
        'R' to RED, 'W' to WHITE, 'H' to 0xFF5A2E0C.toInt(), 'S' to 0xFFFFC08A.toInt(),
        'K' to K, 'B' to BLUE, 'Y' to 0xFFFFD21F.toInt(), 'N' to 0xFF6B3A12.toInt(),
    )
    private val head = arrayOf(
        "......RRRRR.....",
        ".....RRRRWRR....",
        ".....RRRRRRRRR..",
        ".....HHSSSKS....",
        "....HSSSSSKSS...",
        "....HSSSSSSSSS..",
        ".....SSHHHHH....",
        "......SSSSS.....",
    )

    val smallStand = pixelArt(heroPal, *head,
        ".....RRBRRBR....",
        "....RRRBBBBRR...",
        "...SRRBYBBYBRS..",
        "...SS.BBBBBB.SS.",
        "......BBBBBB....",
        ".....BBB..BBB...",
        "....NNN....NNN..",
        "...NNNN....NNNN.",
    )
    val smallWalk = pixelArt(heroPal, *head,
        ".....RRBRRBR....",
        "....RRRBBBBRR...",
        "....SRBYBBYBS...",
        "....SSBBBBBBSS..",
        "......BBBBBB....",
        ".......BBBB.....",
        "......NNNNN.....",
        ".....NNNNNNN....",
    )
    val smallJump = pixelArt(heroPal, *head,
        ".....RRBRRBR.SS.",
        "....RRRBBBBRRSS.",
        "...SRRBYBBYBR...",
        "...SS.BBBBBB....",
        "......BBBBBBB...",
        ".....BBB...BBN..",
        "....NNN.....NN..",
        "....NN..........",
    )

    private val bigTorso = arrayOf(
        ".....RRBRRBRR...",
        "....RRRBRRBRRR..",
        "...RRRRBBBBRRRR.",
        "...RRRBYBBYBRRR.",
    )
    val bigStand = pixelArt(heroPal, *head, *bigTorso,
        "...SSRBBBBBBRSS.",
        "...SSSBBBBBBSSS.",
        "...SS.BBBBBB.SS.",
        "......BBBBBB....",
        ".....BBBBBBBB...",
        ".....BBBBBBBB...",
        ".....BBB..BBB...",
        ".....BBB..BBB...",
        ".....BBB..BBB...",
        "....NNNN..NNNN..",
        "...NNNNN..NNNNN.",
        "...NNNNN..NNNNN.",
    )
    val bigWalk = pixelArt(heroPal, *head, *bigTorso,
        "....SRBBBBBBRS..",
        "....SSBBBBBBSS..",
        "......BBBBBB....",
        "......BBBBBB....",
        ".....BBBBBBBB...",
        "......BBBBBB....",
        "......BBBBB.....",
        ".......BBBB.....",
        ".......BBB......",
        "......NNNNN.....",
        ".....NNNNNN.....",
        ".....NNNNNN.....",
    )
    val bigJump = pixelArt(heroPal, *head,
        ".....RRBRRBRR.SS",
        "....RRRBRRBRRRSS",
        "...RRRRBBBBRRR..",
        "..SSRRBYBBYBR...",
        "..SS.RBBBBBB....",
        ".....BBBBBBB....",
        ".....BBBBBBBB...",
        ".....BBBBBBBBB..",
        ".....BBB..BBBB..",
        "....BBB....BBB..",
        "....BBB.....BB..",
        "...BBB......NNN.",
        "...BBB......NNNN",
        "..NNNN..........",
        "..NNNN..........",
        "................",
    )

    /** Fire form: white cap and shirt, red overalls. */
    private val fireMap = mapOf(RED to 0xFFF4F4F4.toInt(), WHITE to RED, BLUE to RED)
    val fireStand = bigStand.recolor(fireMap)
    val fireWalk = bigWalk.recolor(fireMap)
    val fireJump = bigJump.recolor(fireMap)

    // ---------------------------------------------------------------- Enemies
    private val grumPal = mapOf(
        'X' to 0xFFA4501C.toInt(), 'T' to 0xFFF2CF9B.toInt(), 'K' to K, 'W' to WHITE,
    )
    val grumbler = pixelArt(grumPal,
        "......XXXX......",
        "....XXXXXXXX....",
        "...XXXXXXXXXX...",
        "..XXKKXXXXKKXX..",
        ".XXXXWKXXKWXXXX.",
        ".XXXXWKXXKWXXXX.",
        "XXXXXWWXXWWXXXXX",
        "XXXXXXXXXXXXXXXX",
        "XXXXXKKKKKKXXXXX",
        ".XXXXXXXXXXXXXX.",
        "....TTTTTTTT....",
        "...TTTTTTTTTT...",
        "...TTTTTTTTTT...",
        "..KKTTTTTTTT....",
        ".KKKKK..TTKKK...",
        ".KKKKK...KKKKK..",
    )
    val grumblerBlue = grumbler.recolor(mapOf(0xFFA4501C.toInt() to 0xFF3A6EC8.toInt()))

    private val shellPal = mapOf(
        'G' to 0xFF1E9E3A.toInt(), 'L' to 0xFF8EE07A.toInt(), 'Y' to 0xFFF8D860.toInt(),
        'W' to WHITE, 'K' to K, 'O' to 0xFFE07A1E.toInt(),
    )
    private val shellbackTop = arrayOf(
        "..........YYY...",
        ".........YYYYY..",
        ".........YYKWY..",
        ".........YYKWYY.",
        ".........YYYYYY.",
        "..........YYYYY.",
        "..........YYY...",
        "....GGGG..YY....",
        "...GGLLGG.YY....",
        "..GGLGGLGGYY....",
        "..GLGGGGLGYY....",
        ".GGLGGGGLGGY....",
        ".GLLLLLLLLGY....",
        ".GGLGGGGLGGY....",
        ".GLGGGGGGLGY....",
        ".GGLGGGGLGG.....",
        "..GGLLLLGGY.....",
        "..WWWWWWWWYY....",
        "...WWWWWWYY.....",
    )
    val shellback1 = pixelArt(shellPal, *shellbackTop,
        "....YYY.YYY.....",
        "....YYY..YY.....",
        "...OOOO..OOOO...",
        "...OOOOO.OOOOO..",
        "................",
    )
    val shellback2 = pixelArt(shellPal, *shellbackTop,
        ".....YYYYY......",
        "......YYY.......",
        ".....OOOOO......",
        ".....OOOOOO.....",
        "................",
    )
    val shell = pixelArt(shellPal,
        "................",
        "................",
        "................",
        "................",
        ".....GGGGGG.....",
        "...GGLGGGGLGG...",
        "..GGLLGGGGLLGG..",
        ".GGGGLGGGGLGGGG.",
        ".GLLLLGGGGLLLLG.",
        ".GGGGLGGGGLGGGG.",
        ".GGGLLGGGGLLGGG.",
        ".GLLGLLLLLLGLLG.",
        ".GGGGGGGGGGGGGG.",
        ".WWWWWWWWWWWWWW.",
        "..WWWWWWWWWWWW..",
        "...WWWWWWWWWW...",
    )

    private val podoPal = mapOf('O' to 0xFFFF8A00.toInt(), 'Y' to 0xFFFFE14D.toInt(), 'R' to RED, 'K' to K, 'W' to WHITE)
    val podoboo = pixelArt(podoPal,
        "......YY......",
        "....YYOOYY....",
        "...YOOOOOOY...",
        "..YOOOOOOOOY..",
        ".YOOWKOOWKOOY.",
        ".YOOWKOOWKOOY.",
        "YOOOOOOOOOOOOY",
        "YOROOOOOOOOROY",
        "YORROOOOOORROY",
        ".YORRRRRRRROY.",
        "..YORRRRRROY..",
        "...YYOOOOYY...",
        ".....YYYY.....",
    )

    // ---------------------------------------------------------------- Items & effects
    private val flowerPal = mapOf(
        'O' to 0xFFFF8A00.toInt(), 'R' to RED, 'Y' to 0xFFFFE14D.toInt(), 'W' to WHITE,
        'G' to 0xFF1E9E3A.toInt(), 'L' to 0xFF7ED957.toInt(), 'K' to K,
    )
    val blossom1 = pixelArt(flowerPal,
        "......OOOO......",
        "....OORRRROO....",
        "...ORRYYYYRRO...",
        "..ORYYWWWWYYRO..",
        "..ORYWKWWKWYRO..",
        "..ORYYWWWWYYRO..",
        "...ORRYYYYRRO...",
        "....OORRRROO....",
        "......OOOO......",
        ".......GG.......",
        "..GGG..GG..GGG..",
        ".GLLGG.GG.GGLLG.",
        "..GLLGGGGGGLLG..",
        "...GGGGGGGGGG...",
        ".......GG.......",
        ".......GG.......",
    )
    val blossom2 = blossom1.recolor(mapOf(0xFFFF8A00.toInt() to RED, RED to 0xFFFF8A00.toInt()))

    private val coinPal = mapOf('Y' to 0xFFFFD21F.toInt(), 'W' to 0xFFFFF6B0.toInt(), 'D' to 0xFFC88A00.toInt())
    val coin = pixelArt(coinPal,
        "................",
        "......YYYY......",
        ".....YYYYYY.....",
        "....YYWWYYYY....",
        "....YWYYYYDY....",
        "....YWYYYYDY....",
        "....YWYYYYDY....",
        "....YWYYYYDY....",
        "....YWYYYYDY....",
        "....YWYYYYDY....",
        "....YWYYYYDY....",
        "....YYYYYYYY....",
        ".....YYYYYY.....",
        "......YYYY......",
        "................",
        "................",
    )

    val fireball = pixelArt(flowerPal,
        "..OOOO..",
        ".OYYYYO.",
        "OYYWWYYO",
        "OYWWWWYO",
        "OYWWWWYO",
        "OYYWWYYO",
        ".OYYYYO.",
        "..OOOO..",
    )

    // ---------------------------------------------------------------- Tiles
    private val dirtPal = mapOf(
        'l' to 0xFF9BE15D.toInt(), 'g' to 0xFF4CB531.toInt(), 'G' to 0xFF2E7D1F.toInt(),
        'M' to 0xFFC2762E.toInt(), 'D' to 0xFF8A4E1B.toInt(), 'L' to 0xFFE0A060.toInt(),
    )
    val grassTop = pixelArt(dirtPal,
        "llllllllllllllll",
        "gggggggggggggggg",
        "gggglgggggggglgg",
        "gggggggGgggggggg",
        "GgGggGGMGgggGgGG",
        "MGMGGMMMMGGMGMMM",
        "MMMMMMMMMMMMMMMM",
        "MMDMMMMMMMMMLMMM",
        "MMMMMMLMMMMMMMMM",
        "MLMMMMMMMMDMMMMM",
        "MMMMMDMMMMMMMMDM",
        "MMMMMMMMMLMMMMMM",
        "MMDMMMMMMMMMMMMM",
        "MMMMMMMMMMMMDMMM",
        "MMMMLMMMMMMMMMMM",
        "MMMMMMMMDMMMMMML",
    )
    val dirt = pixelArt(dirtPal,
        "MMMMMMMMMMMMMMMM",
        "MMDMMMMMMMMMLMMM",
        "MMMMMMLMMMMMMMMM",
        "MLMMMMMMMMDMMMMM",
        "MMMMMDMMMMMMMMDM",
        "MMMMMMMMMLMMMMMM",
        "MMDMMMMMMMMMMMMM",
        "MMMMMMMMMMMMDMMM",
        "MMMMLMMMMMMMMMMM",
        "MMMMMMMMDMMMMMML",
        "MDMMMMMMMMMMMMMM",
        "MMMMMMMMMMMLMMMM",
        "MMMMMDMMMMMMMMMM",
        "MMMMMMMMMMMMMDMM",
        "MMLMMMMMMMMMMMMM",
        "MMMMMMMMMDMMMMMM",
    )

    private val brickPal = mapOf('B' to 0xFFC84C0C.toInt(), 'L' to 0xFFFF9D5C.toInt(), 'K' to 0xFF3A1A08.toInt())
    val brick = pixelArt(brickPal,
        "LLLLLLLLLLLLLLLL",
        "BBBBBBBKBBBBBBBK",
        "BBBBBBBKBBBBBBBK",
        "BBBBBBBKBBBBBBBK",
        "KKKKKKKKKKKKKKKK",
        "BBBKBBBBBBBKBBBB",
        "BBBKBBBBBBBKBBBB",
        "BBBKBBBBBBBKBBBB",
        "KKKKKKKKKKKKKKKK",
        "BBBBBBBKBBBBBBBK",
        "BBBBBBBKBBBBBBBK",
        "BBBBBBBKBBBBBBBK",
        "KKKKKKKKKKKKKKKK",
        "BBBKBBBBBBBKBBBB",
        "BBBKBBBBBBBKBBBB",
        "KKKKKKKKKKKKKKKK",
    )
    private val blueMap = mapOf(
        0xFFC84C0C.toInt() to 0xFF2C64B8.toInt(), 0xFFFF9D5C.toInt() to 0xFF8CC8FF.toInt(),
        0xFF3A1A08.toInt() to 0xFF0A1636.toInt(),
    )
    private val greyMap = mapOf(
        0xFFC84C0C.toInt() to 0xFF7C7C8C.toInt(), 0xFFFF9D5C.toInt() to 0xFFB8B8C8.toInt(),
        0xFF3A1A08.toInt() to 0xFF2A2A36.toInt(),
    )
    val brickBlue = brick.recolor(blueMap)
    val stone = brick.recolor(greyMap)

    private val qPal = mapOf(
        'Q' to 0xFFF7B32B.toInt(), 'D' to 0xFFA0500C.toInt(), 'K' to 0xFF3A1A08.toInt(), 'W' to 0xFFFFE6A0.toInt(),
    )
    val question1 = pixelArt(qPal,
        "DWWWWWWWWWWWWWWD",
        "WQQQQQQQQQQQQQQK",
        "WQKQQQQQQQQQQKQK",
        "WQQQQDDDDDQQQQQK",
        "WQQQDDKKKDDQQQQK",
        "WQQQDDKQQDDKQQQK",
        "WQQQQKKQQDDKQQQK",
        "WQQQQQQQDDKKQQQK",
        "WQQQQQQDDKKQQQQK",
        "WQQQQQQDDKQQQQQK",
        "WQQQQQQQKKQQQQQK",
        "WQQQQQQDDQQQQQQK",
        "WQQQQQQDDKQQQQQK",
        "WQKQQQQQKKQQQKQK",
        "WQQQQQQQQQQQQQQK",
        "KKKKKKKKKKKKKKKK",
    )
    val question2 = question1.recolor(mapOf(0xFFF7B32B.toInt() to 0xFFFFD25A.toInt()))

    private val usedPal = mapOf('U' to 0xFF9A5A2A.toInt(), 'K' to 0xFF3A1A08.toInt())
    val used = pixelArt(usedPal,
        "KKKKKKKKKKKKKKKK",
        "KUUUUUUUUUUUUUUK",
        "KUKUUUUUUUUUUKUK",
        "KUUUUUUUUUUUUUUK",
        "KUUUUUUUUUUUUUUK",
        "KUUUUUUUUUUUUUUK",
        "KUUUUUUUUUUUUUUK",
        "KUUUUUUUUUUUUUUK",
        "KUUUUUUUUUUUUUUK",
        "KUUUUUUUUUUUUUUK",
        "KUUUUUUUUUUUUUUK",
        "KUUUUUUUUUUUUUUK",
        "KUUUUUUUUUUUUUUK",
        "KUKUUUUUUUUUUKUK",
        "KUUUUUUUUUUUUUUK",
        "KKKKKKKKKKKKKKKK",
    )

    private val hardPal = mapOf('L' to 0xFFE8B888.toInt(), 'M' to 0xFFC07840.toInt(), 'D' to 0xFF6A3A12.toInt())
    private val hardMid = "LLMMMMMMMMMMMMDD"
    val hard = pixelArt(hardPal,
        "MLLLLLLLLLLLLLLD",
        "LMLLLLLLLLLLLLDD",
        hardMid, hardMid, hardMid, hardMid, hardMid, hardMid, hardMid,
        hardMid, hardMid, hardMid, hardMid, hardMid,
        "LDDDDDDDDDDDDDMD",
        "DDDDDDDDDDDDDDDM",
    )
    val hardBlue = hard.recolor(mapOf(
        0xFFE8B888.toInt() to 0xFF8CC8FF.toInt(), 0xFFC07840.toInt() to 0xFF3C74C8.toInt(),
        0xFF6A3A12.toInt() to 0xFF102650.toInt(),
    ))
    val hardGrey = hard.recolor(mapOf(
        0xFFE8B888.toInt() to 0xFFC8C8D8.toInt(), 0xFFC07840.toInt() to 0xFF8C8C9C.toInt(),
        0xFF6A3A12.toInt() to 0xFF3A3A48.toInt(),
    ))

    private val pipePal = mapOf(
        'G' to 0xFF2EA043.toInt(), 'L' to 0xFF8BE07A.toInt(), 'D' to 0xFF145A22.toInt(), 'K' to 0xFF0A2E10.toInt(),
    )
    private val lipL = "KLLGGGGGGGGGGGGG"
    private val lipR = "GGGGGGGGGDGDDDDK"
    private val kRow = "KKKKKKKKKKKKKKKK"
    val pipeTL = pixelArt(pipePal, kRow, *Array(14) { lipL }, kRow)
    val pipeTR = pixelArt(pipePal, kRow, *Array(14) { lipR }, kRow)
    val pipeL = pixelArt(pipePal, *Array(16) { "..KLLGGGGGGGGGGG" })
    val pipeR = pixelArt(pipePal, *Array(16) { "GGGGGGGDGDDDK..." })

    private val lavaPal = mapOf('O' to 0xFFFF7A00.toInt(), 'R' to 0xFFD82800.toInt(), 'Y' to 0xFFFFD21F.toInt())
    val lavaTop = pixelArt(lavaPal,
        "..YY......YY....",
        ".YOOY....YOOY...",
        "YOOOOYYYYOOOOYYY",
        "OOOOOOOOOOOOOOOO",
        "OOROOOOOOOROOOOO",
        "OOOOOOROOOOOOOOR",
        "OOOROOOOOOOOROOO",
        "ROOOOOOOROOOOOOO",
        "OOOOOROOOOOOOROO",
        "OROOOOOOOOROOOOO",
        "OOOOOOOROOOOOOOO",
        "OOOROOOOOOOOOROO",
        "OOOOOOOOOROOOOOO",
        "OROOOOROOOOOOOOR",
        "OOOOOOOOOOOROOOO",
        "OOOROOOOOOOOOOOO",
    )
    val lava = pixelArt(lavaPal,
        "OOOOOOOOOOOOOOOO",
        "OOROOOOOOOROOOOO",
        "OOOOOOROOOOOOOOR",
        "OOOROOOOOOOOROOO",
        "ROOOOOOOROOOOOOO",
        "OOOOOROOOOOOOROO",
        "OROOOOOOOOROOOOO",
        "OOOOOOOROOOOOOOO",
        "OOOROOOOOOOOOROO",
        "OOOOOOOOOROOOOOO",
        "OROOOOROOOOOOOOR",
        "OOOOOOOOOOOROOOO",
        "OOOROOOOOOOOOOOO",
        "OOOOOOROOOOOOROO",
        "ROOOOOOOOOOOOOOO",
        "OOOOOOOOOROOOOOO",
    )

    private val bridgePal = mapOf('B' to 0xFF8A4E1B.toInt(), 'L' to 0xFFC2762E.toInt(), 'K' to 0xFF2A1408.toInt())
    private val empty16 = "................"
    val bridge = pixelArt(bridgePal,
        "KKKKKKKKKKKKKKKK",
        "BBBLBBBBBBBLBBBB",
        "BBBLBBBBBBBLBBBB",
        "BBBLBBBBBBBLBBBB",
        "BBBLBBBBBBBLBBBB",
        "KKKKKKKKKKKKKKKK",
        ".K.....K.....K..",
        "..K...K.K...K...",
        "...K.K...K.K....",
        "....K.....K.....",
        empty16, empty16, empty16, empty16, empty16, empty16,
    )

    private val axePal = mapOf('S' to 0xFFD8D8E8.toInt(), 'D' to 0xFF606070.toInt(), 'N' to 0xFF8A4E1B.toInt())
    private val handle = "........NN......"
    val axe = pixelArt(axePal,
        "..DDDDD.........",
        ".DSSSSSDNN......",
        "DSSSSSSSNN......",
        "DSSSSSSSNN......",
        "DSSSSSSSNN......",
        ".DSSSSSDNN......",
        "..DDDDD.NN......",
        handle, handle, handle, handle, handle, handle, handle, handle, handle,
    )

    private val cloudPal = mapOf('W' to 0xFFFFFFFF.toInt(), 'L' to 0xFFB8D8F0.toInt())
    val cloud = pixelArt(cloudPal,
        "..WWWW....WWWW..",
        ".WWWWWW..WWWWWW.",
        "WWWWWWWWWWWWWWWW",
        "WWWWWWWWWWWWWWWW",
        "WWWWWWWWWWWWWWWW",
        "LWWWWWWWWWWWWWWL",
        ".LLWWWWWWWWWWLL.",
        "...LLLLLLLLLL...",
        empty16, empty16, empty16, empty16, empty16, empty16, empty16, empty16,
    )

    private val sandMap = mapOf(
        0xFF9BE15D.toInt() to 0xFFFFEDB0.toInt(), 0xFF4CB531.toInt() to 0xFFF5D27A.toInt(),
        0xFF2E7D1F.toInt() to 0xFFD9A441.toInt(), 0xFFC2762E.toInt() to 0xFFE3A95C.toInt(),
        0xFF8A4E1B.toInt() to 0xFFB97A3A.toInt(), 0xFFE0A060.toInt() to 0xFFF2C888.toInt(),
    )
    val sandTop = grassTop.recolor(sandMap)
    val sandstone = dirt.recolor(sandMap)

    // ---------------------------------------------------------------- Pip the messenger
    private val pipPal = mapOf(
        'Y' to 0xFFFFD93D.toInt(), 'K' to K, 'O' to 0xFFFF8A1E.toInt(), 'B' to 0xFF3A8EE8.toInt(),
        'W' to WHITE,
    )
    val pip = pixelArt(pipPal,
        "................",
        ".....YYYYY......",
        "....YYYYYYY.....",
        "...YYYKYYKYY....",
        "...YYYKYYKYY....",
        "...YYYYOOYYY....",
        "...YYYYOOYYY....",
        "...BBBBBBBBB....",
        "..YYBBBBBBBYY...",
        "..YYYYYYYYYYY...",
        "...YYYYYYYYY....",
        "...YYYWWWYYY....",
        "....YYYYYYY.....",
        ".....YYYYY......",
        ".....OO.OO......",
        "....OOO.OOO.....",
    )

    // ---------------------------------------------------------------- Princess Rosalie
    private val princessPal = mapOf(
        'O' to 0xFFFFB000.toInt(), 'C' to 0xFF3CE0FF.toInt(), 'H' to 0xFFFFE070.toInt(),
        'S' to 0xFFFFD0A8.toInt(), 'K' to K, 'R' to 0xFFE0507A.toInt(), 'P' to 0xFFFF6FB5.toInt(),
        'Q' to 0xFFFFB8DC.toInt(), 'N' to 0xFFC03080.toInt(),
    )
    val princess = pixelArt(princessPal,
        "......O.O.O.....",
        "......OOCOO.....",
        ".....HHHHHHH....",
        "....HHSSSSSHH...",
        "....HSKSSSKSH...",
        "....HSSSSSSSH...",
        "....HSSSRSSSH...",
        "....HHSSSSSHH...",
        "...HHHPPPPPHHH..",
        "...HH.QPPPQ.HH..",
        "...H.SPPPPPS.H..",
        "....SSPPPPPSS...",
        ".....PPQQQPP....",
        ".....PPPPPPP....",
        "....PPPPQPPPP...",
        "....PPPPPPPPP...",
        "...PPPPQPPPPPP..",
        "...PPPPPPPPPPP..",
        "..PPPPPPQPPPPPP.",
        "..PPPPPPPPPPPPP.",
        "..QPPPPPPPPPPPQ.",
        "..QQQQQQQQQQQQQ.",
        "....NN.....NN...",
        "................",
    )
}
