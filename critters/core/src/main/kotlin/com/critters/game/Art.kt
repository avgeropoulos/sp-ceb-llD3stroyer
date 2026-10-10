package com.critters.game

/** Overworld tiles, people and little UI bits, all drawn in code. */
object Art {
    private fun c(v: Long) = v.toInt()

    private val GRASS_L = c(0xFF98D868)
    private val GRASS_M = c(0xFF68B048)
    private val GRASS_D = c(0xFF387830)

    private val grassPal = mapOf('a' to GRASS_L, 'b' to GRASS_M, 'c' to GRASS_D, 'k' to OUTLINE)

    val grass = art(
        grassPal,
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aabaabaaaaaaaaaa",
        "aaabbaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaababaaaa",
        "aaaaaaaaaabbaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
    )

    val tallGrass = tiled(
        art(
            grassPal,
            "bbbabbbb",
            "bbacabbb",
            "bacccabb",
            "accccca.",
            "bbbbbbba",
            "abbbbbac",
            "cabbbacc",
            "ccabaccc",
        ).let { s -> Sprite(s.w, s.h, IntArray(s.px.size) { if (s.px[it] == 0) GRASS_M else s.px[it] }) }
    )

    val tree = art(
        mapOf('a' to GRASS_L, 'k' to OUTLINE, 'd' to c(0xFF286830), 'm' to c(0xFF48A048), 'l' to c(0xFF88D060), 't' to c(0xFF905830)),
        "aaaakkkkkkkkaaaa",
        "aakkmmmmmmmmkkaa",
        "akmmllmmmmllmmka",
        "akmllllmmllllmka",
        "kmmmllmmmmllmmdk",
        "kmmmmmmmmmmmmmdk",
        "kmllmmmmllmmmmdk",
        "kllllmmllllmmddk",
        "kmllmmmmllmmmddk",
        "kmmmmmmmmmmmdddk",
        "akddmmmmmmmdddka",
        "aakkddddddddkkaa",
        "aaaakkkttkkkaaaa",
        "aaaaaakttkaaaaaa",
        "aaaaakttttkaaaaa",
        "aaaaaakkkkaaaaaa",
    )

    private val pathPal = mapOf('p' to c(0xFFE8D8A8), 'q' to c(0xFFD0B880))
    val path = tiled(art(pathPal, "pppppppp", "pppppqpp", "pppppppp", "pqpppppp", "pppppppp", "pppppppp", "ppppqppp", "pppppppp"))
    val sand = tiled(
        art(mapOf('p' to c(0xFFF8E8B8), 'q' to c(0xFFE0C888)), "pppppppp", "ppqppppp", "pppppppp", "pppppqpp", "pppppppp", "pqpppppp", "pppppppp", "ppppppqp")
    )

    private val waterPal = mapOf('w' to c(0xFF58A0F0), 'l' to c(0xFFC0E4FF), 'd' to c(0xFF4080E0))
    private val water8 = art(waterPal, "wwwwwwww", "wllwwwww", "lwwlwwww", "wwwwwwww", "wwwwwwww", "wwwwwllw", "wwwwlwwl", "dwwwwwww")
    val water = listOf(tiled(water8), tiled(Sprite(8, 8, IntArray(64) { water8.px[(it / 8) * 8 + (it % 8 + 6) % 8] })))

    private fun flowerTile(diag: Boolean): Sprite {
        val px = grass.px.copyOf()
        fun flower(cx: Int, cy: Int, petal: Int, mid: Int) {
            val pts = if (diag) listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1) else listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
            for ((dx, dy) in pts) px[(cy + dy) * 16 + cx + dx] = petal
            px[cy * 16 + cx] = mid
            px[(cy + 2) * 16 + cx] = GRASS_D
        }
        flower(4, 3, c(0xFFF85858), PAPER)
        flower(11, 10, c(0xFFF8D040), c(0xFFF89830))
        return Sprite(16, 16, px)
    }
    val flowers = listOf(flowerTile(false), flowerTile(true))

    val fence = art(
        grassPal + mapOf('w' to PAPER, 'g' to c(0xFFB0B0B8)),
        "aaaaaaaaaaaaaaaa",
        "aaaaaakkkkaaaaaa",
        "aaaaaakwwkaaaaaa",
        "kkkkkkkwwkkkkkkk",
        "wwwwwwkwwkwwwwww",
        "ggggggkwwkgggggg",
        "kkkkkkkwwkkkkkkk",
        "aaaaaakwwkaaaaaa",
        "aaaaaakwwkaaaaaa",
        "kkkkkkkwwkkkkkkk",
        "wwwwwwkwwkwwwwww",
        "ggggggkwgkgggggg",
        "kkkkkkkwgkkkkkkk",
        "aaaaaakggkaaaaaa",
        "aaaaaakkkkaaaaaa",
        "aaaaaaabbaaaaaaa",
    )

    val sign = art(
        grassPal + mapOf('t' to c(0xFFC08848), 'l' to c(0xFFE8B870), 'd' to c(0xFF805028)),
        "aaaaaaaaaaaaaaaa",
        "akkkkkkkkkkkkkka",
        "klllllllllllllldk"
            .take(16),
        "kltddddtdddddtdk",
        "kltttttttttttttk",
        "kltddtdddtdddttk",
        "kltttttttttttttk",
        "kldddddddddddddk",
        "akkkkkkkkkkkkkka",
        "aaaaaakddkaaaaaa",
        "aaaaaakddkaaaaaa",
        "aaaaaakddkaaaaaa",
        "aaaaaakddkaaaaaa",
        "aaaaabkkkkbaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
    )

    val ledge = art(
        grassPal,
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaabaaaaaaaabaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "bbbbbbbbbbbbbbbb",
        "kkkkkkkkkkkkkkkk",
        "cbcccbcccbcccbcc",
        "cccccccccccccccc",
        "kkkkkkkkkkkkkkkk",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaa",
    )

    private fun roof(color: Int): Sprite {
        val d = shade(color, 0.72f)
        val l = lighten(color, 0.3f)
        return tiled(
            art(
                mapOf('r' to color, 'd' to d, 'l' to l),
                "llldllll", "rrrdrrrr", "rrrdrrrr", "dddddddd", "lllllllld".take(8), "rrrrrrrd", "rrrrrrrd", "dddddddd",
            )
        )
    }

    val roofHouse = roof(c(0xFFD86838))
    val roofCenter = roof(c(0xFFE84850))
    val roofMart = roof(c(0xFF4878E0))
    val roofGym = roof(c(0xFF8868B8))
    val roofLab = roof(c(0xFF58A868))

    private val wallPal = mapOf('w' to c(0xFFF0E0C0), 'v' to c(0xFFD8C098), 'k' to OUTLINE, 'g' to c(0xFF88C8F8), 'G' to c(0xFFD8F0FF),
        'f' to c(0xFF905838), 'd' to c(0xFF603820), 'y' to c(0xFFF8D040), 'r' to c(0xFFE84850), 'b' to c(0xFF4878E0), 'p' to PAPER)

    val wall = art(
        wallPal,
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "wwwwvwwwwwwwvwww",
        "vvvvvvvvvvvvvvvv",
        "kkkkkkkkkkkkkkkk",
        "kkkkkkkkkkkkkkkk",
    )

    val window = art(
        wallPal,
        "wwwwwwwwwwwwwwww",
        "wwwwwwwwwwwwwwww",
        "wwkkkkkkkkkkkkww",
        "wwkGGgggkgGggkww",
        "wwkGgggkgGgggkww",
        "wwkgggkgggggGkww",
        "wwkkkkkkkkkkkkww",
        "wwkggggkGgggggkw"
            .take(16),
        "wwkgggkgGggggkww",
        "wwkggggggggggkww",
        "wwkkkkkkkkkkkkww",
        "wwvvvvvvvvvvvvww",
        "wwwwwwwwwwwwwwww",
        "vvvvvvvvvvvvvvvv",
        "kkkkkkkkkkkkkkkk",
        "kkkkkkkkkkkkkkkk",
    )

    val door = art(
        wallPal,
        "wwkkkkkkkkkkkkww",
        "wwkffffffffffkww",
        "wwkfddddddddfkww",
        "wwkfdffffffdfkww",
        "wwkfdffffffdfkww",
        "wwkfddddddddfkww",
        "wwkffffffffffkww",
        "wwkffffffffffkww",
        "wwkffffffffyfkww",
        "wwkffffffffffkww",
        "wwkfddddddddfkww",
        "wwkfdffffffdfkww",
        "wwkfddddddddfkww",
        "wwkffffffffffkww",
        "kkkkkkkkkkkkkkkk",
        "kkkkkkkkkkkkkkkk",
    )

    private fun wallSign(rows: Array<String>): Sprite {
        val px = wall.px.copyOf()
        val pal = mapOf('k' to OUTLINE, 'p' to PAPER, 'r' to c(0xFFE84850), 'b' to c(0xFF4878E0), 'y' to c(0xFFF8D040))
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch != '.') px[(y + 2) * 16 + x + 2] = pal.getValue(ch) } }
        return Sprite(16, 16, px)
    }

    val centerSign = wallSign(
        arrayOf(
            "...kkkkkk...",
            "..kpppppppk.".take(12),
            ".kpppprrpppk",
            ".kpppprrpppk",
            ".kprrrrrrrpk",
            ".kprrrrrrrpk",
            ".kpppprrpppk",
            ".kpppprrpppk",
            "..kppppppk..",
            "...kkkkkk...",
        )
    )
    val martSign = wallSign(
        arrayOf(
            "kkkkkkkkkkkk",
            "kbbbbbbbbbbk",
            "kbpbbbbbbpbk",
            "kbppbbbbppbk",
            "kbpbpbbpbpbk",
            "kbpbbppbbpbk",
            "kbpbbbbbbpbk",
            "kbpbbbbbbpbk",
            "kbbbbbbbbbbk",
            "kkkkkkkkkkkk",
        )
    )
    val gymSign = wallSign(
        arrayOf(
            ".....kk.....",
            "....kyyk....",
            "....kyyk....",
            "kkkkkyykkkkk",
            "kyyyyyyyyyyk",
            ".kyyyyyyyyk.",
            "..kyyyyyyk..",
            "..kyykkyyk..",
            ".kyyk..kyyk.",
            ".kkk....kkk.",
        )
    )

    private val floorPal = mapOf('f' to c(0xFFE8C890), 'l' to c(0xFFC8A060), 'k' to OUTLINE)
    val floor = tiled(art(floorPal, "ffffffff", "ffffffff", "ffffffff", "llllllll", "ffffflff", "ffffflff", "ffffflff", "llllllll"))

    private val backPal = mapOf('w' to c(0xFFF0E8D8), 'p' to c(0xFFD8C8E8), 'b' to c(0xFF987858), 'k' to OUTLINE, 'g' to c(0xFF88C8F8), 'G' to c(0xFFD8F0FF))
    val backWall = art(
        backPal,
        "wwwwwwwwwwwwwwww",
        "wwpwwwwwwwpwwwww",
        "wpppwwwwwpppwwww",
        "wwpwwwwwwwpwwwww",
        "wwwwwwwwwwwwwwww",
        "wwwwwwpwwwwwwwpw",
        "wwwwwpppwwwwwppp",
        "wwwwwwpwwwwwwwpw",
        "wwwwwwwwwwwwwwww",
        "wwpwwwwwwwpwwwww",
        "wpppwwwwwpppwwww",
        "wwpwwwwwwwpwwwww",
        "wwwwwwwwwwwwwwww",
        "bbbbbbbbbbbbbbbb",
        "bbbbbbbbbbbbbbbb",
        "kkkkkkkkkkkkkkkk",
    )
    val backWindow = art(
        backPal,
        "wwwwwwwwwwwwwwww",
        "wkkkkkkkkkkkkkkw",
        "wkGGgggggkgggGkw",
        "wkGgggggkgggGgkw",
        "wkgggggkggggggkw",
        "wkkkkkkkkkkkkkkw",
        "wkggggkGggggggkw",
        "wkgggkGgggggggkw",
        "wkggggggggggggkw",
        "wkkkkkkkkkkkkkkw",
        "wwwwwwwwwwwwwwww",
        "wwwwwwwwwwwwwwww",
        "wwwwwwwwwwwwwwww",
        "bbbbbbbbbbbbbbbb",
        "bbbbbbbbbbbbbbbb",
        "kkkkkkkkkkkkkkkk",
    )

    private val furnPal = mapOf('k' to OUTLINE, 't' to c(0xFFC89058), 'T' to c(0xFFE8B878), 'd' to c(0xFF8C5830), 'f' to c(0xFFE8C890), 'l' to c(0xFFC8A060),
        'r' to c(0xFFE84850), 'b' to c(0xFF4878E0), 'g' to c(0xFF48A848), 'y' to c(0xFFF8D040), 'p' to PAPER, 's' to c(0xFF707888), 'S' to c(0xFFA8B0C0), 'c' to c(0xFF58D8F8))

    val counter = art(
        furnPal,
        "kkkkkkkkkkkkkkkk",
        "TTTTTTTTTTTTTTTT",
        "TTTTTTTTTTTTTTTT",
        "TTTTTTTTTTTTTTTT",
        "TTTTTTTTTTTTTTTT",
        "tttttttttttttttt",
        "kkkkkkkkkkkkkkkk",
        "tdttttttdttttttt",
        "tdttttttdttttttt",
        "tdttttttdttttttt",
        "tdttttttdttttttt",
        "tdttttttdttttttt",
        "tdttttttdttttttt",
        "tdttttttdttttttt",
        "dddddddddddddddd",
        "kkkkkkkkkkkkkkkk",
    )
    val shelf = art(
        furnPal,
        "kkkkkkkkkkkkkkkk",
        "kddddddddddddddk",
        "kdrrbbgyyrbbggdk",
        "kdrrbbgyyrbbggdk",
        "kdrrbbgyyrbbggdk",
        "kddddddddddddddk",
        "kdbbyyrrggbyyrdk",
        "kdbbyyrrggbyyrdk",
        "kdbbyyrrggbyyrdk",
        "kddddddddddddddk",
        "kdggrrbbyyggrrdk",
        "kdggrrbbyyggrrdk",
        "kdggrrbbyyggrrdk",
        "kddddddddddddddk",
        "kkkkkkkkkkkkkkkk",
        "kkkkkkkkkkkkkkkk",
    )
    val pc = art(
        furnPal,
        "ffffffffffffffff",
        "fkkkkkkkkkkkkkkf",
        "fkSSSSSSSSSSSSkf",
        "fkSkkkkkkkkkkSkf",
        "fkSkccccccccksSf".take(16),
        "fkSkcpccccccckSk".take(16),
        "fkSkccccccccckSk".take(16),
        "fkSkkkkkkkkkkSkf",
        "fkSSSSSSSSSSSSkf",
        "fkkkkkkkkkkkkkkf",
        "fffkssssssssskff",
        "ffkSSSSSSSSSSSkf",
        "ffkSsSsSsSsSsSkf",
        "ffkSSSSSSSSSSSkf",
        "fffkkkkkkkkkkkff",
        "llllllllllllllll",
    )
    val bed = art(
        furnPal,
        "kkkkkkkkkkkkkkkk",
        "kppppppppppppppk",
        "kppppppppppppppk",
        "kppppppppppppppk",
        "kkkkkkkkkkkkkkkk",
        "kbbbbbbbbbbbbbbk",
        "kbbbbbbbbbbbbbbk",
        "kbpbbbbpbbbbpbbk",
        "kbbbbbbbbbbbbbbk",
        "kbbbbpbbbbbpbbbk",
        "kbbbbbbbbbbbbbbk",
        "kbpbbbbpbbbbpbbk",
        "kbbbbbbbbbbbbbbk",
        "kkkkkkkkkkkkkkkk",
        "kdffffffffffffdk",
        "llllllllllllllll",
    )
    val plant = art(
        furnPal,
        "ffffffkkkkffffff",
        "ffffkkggggkkffff",
        "fffkggkgggggkfff",
        "ffkgggggkggggkff",
        "ffkggkggggkggkff",
        "fkgggggkgggggggk".take(16),
        "fkggkgggggggkgkf",
        "ffkggggkggggggkf",
        "fffkkggggggkkkff",
        "ffffkkkkkkkkffff",
        "ffffkttttttkffff",
        "fffkTTTTTTTTkfff",
        "fffkttttttttkfff",
        "fffkttttttttkfff",
        "ffffkkkkkkkkffff",
        "llllllllllllllll",
    )
    val mat = layer(floor, art(
        furnPal,
        "................",
        "................",
        "................",
        "................",
        ".kkkkkkkkkkkkkk.",
        ".krrrrrrrrrrrrk.",
        ".krpprrrrrrpprk.",
        ".krrrrrrrrrrrrk.",
        ".krrrrrrrrrrrrk.",
        ".krpprrrrrrpprk.",
        ".krrrrrrrrrrrrk.",
        ".kkkkkkkkkkkkkk.",
        "................",
        "................",
        "................",
        "................",
    ))
    val carpet = tiled(art(mapOf('r' to c(0xFFE87878), 's' to c(0xFFD06060)), "rrrrrrrr", "rsrrrrsr", "rrrrrrrr", "rrrsrrrr", "rrrrrrrr", "rsrrrrsr", "rrrrrrrr", "rrrrsrrr"))

    val gymFloor = tiled(art(mapOf('a' to c(0xFFD8D8E8), 'b' to c(0xFFB8B8D0)), "aaaaaaab", "aaaaaaab", "aaaaaaab", "aaaaaaab", "aaaaaaab", "aaaaaaab", "aaaaaaab", "bbbbbbbb"))
    val statue = layer(
        gymFloor,
        art(
            furnPal,
            "......kkkk......",
            ".....kSSSSk.....",
            "....kSSkSSSk....",
            "....kSSSSSSk....",
            "...kkSSSSSSkk...",
            "..kSSSSSSSSSSk..",
            "..kSSSSSSSSSSk..",
            "...kSSSSSSSSk...",
            "...kSSSkkSSSk...",
            "..kkkkkkkkkkkk..",
            "..kSSSSSSSSSSk..",
            "..ksssssssssssk.".take(16),
            "..kssssssssssk..",
            "..kssssssssssk..",
            "..kkkkkkkkkkkk..",
            "................",
        ),
    )

    private val cavePal = mapOf('a' to c(0xFFD8B080), 'b' to c(0xFFB88C58), 'c' to c(0xFF8C6438), 'd' to c(0xFF604020), 'k' to OUTLINE, 'h' to c(0xFFF0D0A0))
    val caveFloor = tiled(art(cavePal, "aaaaaaaa", "abaaaaaa", "aaaaaaba", "aaaaaaaa", "aaaabaaa", "aaaaaaaa", "abaaaaaa", "aaaaaaab"))
    val caveWall = art(
        cavePal,
        "cccbbccccbbbcccc",
        "cbbhbbccbbhbbccc",
        "cbhhbbcbbhhbbcdc",
        "cbbbbcdcbbbbbcdc",
        "ccbbcddccbbbcddc",
        "dcccddddcccccddd",
        "ccdddcccdddccccc",
        "cbbcccbbbcccbbbc",
        "bhhbcbhhbbcbhhbc",
        "bhbbcbbbbbcbbbbd",
        "bbbcddbbbcddbbcd",
        "cccdddccccdddccd",
        "ddddcccddddcccdd",
        "cbbbcccbbbbcccbb",
        "cbbcddcbbbcddcbb",
        "dddddddddddddddd",
    )
    val boulder = layer(
        caveFloor,
        art(
            cavePal,
            "................",
            "....kkkkkkk.....",
            "...kbbbhhbbk....",
            "..kbbhhhbbbbk...",
            ".kbbhhbbbbbbck..",
            ".kbbbbbbbbbbck..",
            "kbbbbbbbbbbbbck.",
            "kbbbbbbbbbbbcck.",
            "kbbbbbbbbbbcccck",
            "kcbbbbbbbbbccdck",
            "kccbbbbbbbcccdck",
            ".kcccbbbbcccddk.",
            ".kdcccccccccddk.",
            "..kkddddddddkk..",
            "....kkkkkkkk....",
            "................",
        ),
    )
    private val cliffPal = mapOf('a' to c(0xFFB8A888), 'b' to c(0xFF988868), 'c' to c(0xFF706048), 'h' to c(0xFFD8C8A8), 'k' to OUTLINE)
    val cliff = art(
        cliffPal,
        "abbbaabbbbaabbba",
        "hhbbahhbbbahhbba",
        "hbbbahbbbbahbbbc",
        "abbcabbbbcabbbcc",
        "cccccccccccccccc",
        "bbbaabbbbaabbbab",
        "hhbbahhbbahhbbah",
        "hbbcahbbbcahbbch",
        "bbccabbbccabbccb",
        "cccccccccccccccc",
        "abbbaabbbbaabbba",
        "hhbbahhbbbahhbba",
        "hbbbahbbbbahbbbc",
        "abbcabbbbcabbbcc",
        "cccccccccccccccc",
        "kkkkkkkkkkkkkkkk",
    )
    val caveDoor = layer(
        cliff,
        art(
            mapOf('k' to OUTLINE, 'x' to c(0xFF181010)),
            "................",
            "................",
            "................",
            "....kkkkkkkk....",
            "...kxxxxxxxxk...",
            "..kxxxxxxxxxxk..",
            "..kxxxxxxxxxxk..",
            ".kxxxxxxxxxxxxk.",
            ".kxxxxxxxxxxxxk.",
            ".kxxxxxxxxxxxxk.",
            ".kxxxxxxxxxxxxk.",
            ".kxxxxxxxxxxxxk.",
            ".kxxxxxxxxxxxxk.",
            ".kxxxxxxxxxxxxk.",
            ".kxxxxxxxxxxxxk.",
            ".kxxxxxxxxxxxxk.",
        ),
    )
    val ladder = layer(
        caveFloor,
        art(
            mapOf('k' to OUTLINE, 'x' to c(0xFF181010), 'l' to c(0xFFA87038)),
            "..kkkkkkkkkkkk..",
            ".kxxxxxxxxxxxxk.",
            ".kxlxxxxxxxxlxk.",
            ".kxllllllllllxk.",
            ".kxlxxxxxxxxlxk.",
            ".kxlxxxxxxxxlxk.",
            ".kxllllllllllxk.",
            ".kxlxxxxxxxxlxk.",
            ".kxlxxxxxxxxlxk.",
            ".kxllllllllllxk.",
            ".kxlxxxxxxxxlxk.",
            ".kxlxxxxxxxxlxk.",
            ".kxllllllllllxk.",
            ".kxxxxxxxxxxxxk.",
            "..kkkkkkkkkkkk..",
            "................",
        ),
    )
    val snow = tiled(art(mapOf('a' to c(0xFFF0F8FF), 'b' to c(0xFFD0E0F0)), "aaaaaaaa", "aaabaaaa", "aaaaaaaa", "aaaaaaba", "abaaaaaa", "aaaaaaaa", "aaaaabaa", "aaaaaaaa"))
    val black = Sprite(16, 16, IntArray(256) { c(0xFF000000) })

    // ------------------------------------------------------------------ people

    private val personPal = mapOf('k' to OUTLINE, 'r' to c(0xFFE83838), 'w' to PAPER, 's' to c(0xFFF8C898), 'h' to c(0xFF583018), 'b' to c(0xFF3868D8), 'p' to c(0xFF384058))

    private val headDown = arrayOf(
        "....kkkkkkkk....",
        "...krrrrrrrrk...",
        "..krrrrwwrrrrk..",
        "..krrrrrrrrrrk..",
        ".kkkkkkkkkkkkkk.",
        ".khsssssssssshk.",
        ".khsksssssskshk.",
        "..kssssssssssk..",
        "...kkssssssk....",
        "..kkbbbbbbbbkk..",
        ".ksbbbbwwbbbbsk.",
        ".ksbbbbwwbbbbsk.",
        "..kkbbbbbbbbkk..",
    )
    private val headUp = arrayOf(
        "....kkkkkkkk....",
        "...krrrrrrrrk...",
        "..krrrrrrrrrrk..",
        "..krrrrrrrrrrk..",
        "..kkrrrrrrrrkk..",
        ".khhkkkkkkkkhhk.",
        ".khhhhhhhhhhhhk.",
        "..khhhhhhhhhhk..",
        "...kkhhhhhhkk...",
        "..kkbbbbbbbbkk..",
        ".ksbbbbbbbbbbsk.",
        ".ksbbbbbbbbbbsk.",
        "..kkbbbbbbbbkk..",
    )
    private val legsStand = arrayOf("...kppppppppk...", "...kppk..kppk...", "...kkkk..kkkk...")
    private val legsWalk = arrayOf("...kppppppppk...", "...kppk..kkkk...", "...kkkk.........")
    private val headLeft = arrayOf(
        ".....kkkkkkk....",
        "....krrrrrrrk...",
        "...krrrrrwrrrk..",
        "...krrrrrrrrrk..",
        ".kkkkkkkrrrrrk..",
        "..ksssssshhhhk..",
        "..kskssssshhhk..",
        "..ksssssshhhk...",
        "...kkssssshk....",
        "....kbbbbbbk....",
        "...kbbbbbbbbk...",
        "...kbbsbbbbbk...",
        "....kbbbbbbk....",
    )
    private val legsSideStand = arrayOf("....kppppppk....", "....kppkppk.....", "....kkkkkkk.....")
    private val legsSideWalk = arrayOf("...kpppppppk....", "..kppk..kppk....", "..kkkk..kkkk....")

    /** How a person looks: cap / hair, shirt, hair and pants colors. */
    enum class Look(val cap: Long, val shirt: Long, val hair: Long = 0xFF583018, val pants: Long = 0xFF384058) {
        PLAYER(0xFFE83838, 0xFF3868D8),
        RIVAL(0xFF6848A8, 0xFFF08030, 0xFF282028),
        PROF(0xFFC8C8C8, 0xFFF0F0F0, 0xFF989898, 0xFF805838),
        MOM(0xFF905030, 0xFFF888A8, 0xFF905030, 0xFF5868B0),
        NURSE(0xFFF890B0, 0xFFF8F8F8, 0xFFF890B0, 0xFFF8F8F8),
        CLERK(0xFF4878E0, 0xFF48A848),
        BOY(0xFFF8C830, 0xFF48A848),
        GIRL(0xFFE88848, 0xFFE84850, 0xFFE88848, 0xFF6858B0),
        HIKER(0xFF8C5830, 0xFFC88850, 0xFF282018, 0xFF605040),
        BUGKID(0xFF58B848, 0xFFF8D040),
        SWIMMER(0xFF4898F0, 0xFFF8C898, 0xFF282018, 0xFF4898F0),
        OLDMAN(0xFFE8E8E8, 0xFF888890, 0xFFE8E8E8, 0xFF585860),
        GUARD(0xFF384888, 0xFF384888),
        GRANITA(0xFF805030, 0xFFC8A060, 0xFF805030, 0xFF706050),
        MARINA(0xFF40C8D0, 0xFF2858A8, 0xFF40C8D0, 0xFF2858A8),
        VOLTA(0xFFF8D020, 0xFF303038, 0xFFF8D020, 0xFF303038),
        BALL(0, 0),
        SOLARIS(0, 0),
    }

    class Frames(val down: Sprite, val downWalk: Sprite, val up: Sprite, val upWalk: Sprite, val left: Sprite, val leftWalk: Sprite)

    private val frameCache = HashMap<Look, Frames>()

    fun person(look: Look): Frames = frameCache.getOrPut(look) {
        val pal = personPal + mapOf('r' to c(look.cap), 'b' to c(look.shirt), 'h' to c(look.hair), 'p' to c(look.pants))
        fun f(top: Array<String>, legs: Array<String>) = art(pal, *(top + legs))
        Frames(
            f(headDown, legsStand), f(headDown, legsWalk),
            f(headUp, legsStand), f(headUp, legsWalk),
            f(headLeft, legsSideStand), f(headLeft, legsSideWalk),
        )
    }

    val ball = art(
        mapOf('k' to OUTLINE, 'r' to c(0xFFE83838), 'R' to c(0xFFF89090), 'w' to PAPER, 'g' to c(0xFFC8C8D0)),
        "................",
        "................",
        "................",
        ".....kkkkkk.....",
        "....kRrrrrrk....",
        "...kRrrrrrrrk...",
        "...krrrrrrrrk...",
        "...kkkkwwkkkk...",
        "...kwwkwwkwwk...",
        "...kwwwkkwwgk...",
        "....kwwwwwgk....",
        ".....kkkkkk.....",
        "................",
        "................",
        "................",
        "................",
    )

    /** The small capsule thrown in battle. */
    val capsule = art(
        mapOf('k' to OUTLINE, 'r' to c(0xFFE83838), 'w' to PAPER),
        "..kkkk..",
        ".krrrrk.",
        "krrrrrrk",
        "kkkwwkkk",
        "kwwkkwwk",
        "kwwwwwwk",
        ".kwwwwk.",
        "..kkkk..",
    )

    val bubble = art(
        mapOf('k' to OUTLINE, 'w' to PAPER, 'r' to c(0xFFE83838)),
        "..kkkkkkkkkk....",
        ".kwwwwwwwwwwk...",
        "kwwwwwrrwwwwwk..",
        "kwwwwwrrwwwwwk..",
        "kwwwwwrrwwwwwk..",
        "kwwwwwrrwwwwwk..",
        "kwwwwwwwwwwwwk..",
        "kwwwwwrrwwwwwk..",
        ".kwwwwwwwwwwk...",
        "..kkkkkwwkkk....",
        ".......kwk......",
        "........k.......",
        "................",
        "................",
        "................",
        "................",
    )

    // ------------------------------------------------------------------ tiles by map character

    /** Map legend. Returns the tile picture for [ch] at animation frame [frame]. */
    fun tile(ch: Char, frame: Int): Sprite = when (ch) {
        '.' -> grass
        ',' -> tallGrass
        '#' -> tree
        '=' -> path
        's' -> sand
        '~' -> water[frame % 2]
        '*' -> flowers[frame % 2]
        'F' -> fence
        'S' -> sign
        'L' -> ledge
        'R' -> roofHouse
        'C' -> roofCenter
        'M' -> roofMart
        'G' -> roofGym
        'H' -> roofLab
        'W' -> wall
        'w' -> window
        'D' -> door
        'n' -> centerSign
        'v' -> martSign
        'y' -> gymSign
        'f' -> floor
        'X' -> backWall
        'x' -> backWindow
        'T' -> counter
        'B' -> shelf
        'P' -> pc
        'b' -> bed
        'Y' -> plant
        'E' -> mat
        'Z' -> carpet
        'g' -> gymFloor
        'Q' -> statue
        'k' -> caveFloor
        'K' -> caveWall
        'r' -> boulder
        'A' -> cliff
        'O' -> caveDoor
        'l' -> ladder
        'i' -> snow
        else -> black
    }

    const val SOLID = "#~FSRCMGHWwnvyXxTBPbYQKrA "
    const val WARPS = "DEOl"
}
