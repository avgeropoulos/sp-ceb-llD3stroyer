package com.roccoquest.team

import kotlin.random.Random

/**
 * World 3 (Mystery Islands) and World 4 (the Doom Lands): 21 levels each, built from pieces
 * (flat ground, gaps, lava pits, raised ledges, stairs, ore rocks, prize blocks) with a fixed seed,
 * so every level is always the same. Every world ends with a boss, and there are bosses halfway.
 * The very last level is THE ULTIMATE BATTLE: Dry Bones King Krag, Wonder King Krag and the Ender Dragon.
 */
object MoreWorlds {
    private class Plan(val name: String, val place: Place, val tip: String, val boss: String? = null)

    private val world3 = listOf(
        Plan("PALM BEACH", Place.HILLS, "WORLD 3: the Mystery Islands! New bad guys live here."),
        Plan("GLOOP SWAMP", Place.JUNGLE, "Gloops everywhere. Squish the big ones and watch them split!"),
        Plan("CRUSHER CANYON", Place.DESERT, "Stone CRUSHERS slam down when you walk under them. Run!"),
        Plan("MISTY CAVERN", Place.CAVE, "Shy ghosts hide when you look at them..."),
        Plan("GHOST GARDEN", Place.NIGHT, "PEEKABOO ghosts! Turn around and face them to make them stop."),
        Plan("SNOWBALL SLOPES", Place.SNOW, "Puffclouds drop spiky balls from the sky!"),
        Plan("SKY PALACE", Place.SKY, "WONDER KRAG JR. is back in the clouds!", boss = "W"),
        Plan("CACTUS CROSSING", Place.DESERT, "SPIKESTACKS are too spiky to stomp. Hit them!"),
        Plan("BOUNCY HILLS", Place.HILLS, "Hoppers and Gloops love to bounce."),
        Plan("EMBER CAVES", Place.VOLCANO, "Hot lava pits. Jump carefully!"),
        Plan("MOONLIT MARSH", Place.NIGHT, "Endermen and ghosts at night."),
        Plan("FROZEN FALLS", Place.SNOW, "Icy ledges and Skelly Archers."),
        Plan("GEM GROTTO", Place.CAVE, "Lots of gems to mine down here."),
        Plan("WARDEN'S DEEP", Place.DEEP, "THE WARDEN is in the deepest cave yet. Shhh!", boss = "G"),
        Plan("JUNGLE JUMPS", Place.JUNGLE, "Big jumps over the jungle."),
        Plan("SANDY TEMPLE", Place.DESERT, "An old temple full of crushers."),
        Plan("STORM PEAKS", Place.SNOW, "Windy mountain tops."),
        Plan("HAUNTED HALLS", Place.CASTLE, "A spooky castle. Ghosts, bones and lava!"),
        Plan("LAVA RIVER", Place.VOLCANO, "The lava river. Don't fall in!"),
        Plan("SHADOW ROAD", Place.END, "The road to the twin bosses..."),
        Plan("TWIN TERROR", Place.CASTLE, "Two bosses! WONDER KING KRAG, then DRY BONES KING KRAG!", boss = "QY"),
    )

    private val world4 = listOf(
        Plan("DOOM MEADOW", Place.HILLS, "WORLD 4: the Doom Lands. The toughest bad guys of all!"),
        Plan("BONE DESERT", Place.DESERT, "Boneheads and Skelly Archers everywhere."),
        Plan("CRYSTAL DEPTHS", Place.CAVE, "Deep crystal caves with crushers."),
        Plan("FROSTBITE RIDGE", Place.SNOW, "Freezing ledges and spiky balls."),
        Plan("THUNDER JUNGLE", Place.JUNGLE, "A stormy jungle full of snappers."),
        Plan("NIGHTMARE WOODS", Place.NIGHT, "Ghosts and Endermen in the dark woods."),
        Plan("KRAG'S KEEP", Place.CASTLE, "KING KRAG's toughest castle!", boss = "K"),
        Plan("ASH VALLEY", Place.VOLCANO, "Ash and fire. Keep moving!"),
        Plan("PHANTOM PATH", Place.NIGHT, "So many ghosts..."),
        Plan("MOLTEN MINES", Place.VOLCANO, "Mine obsidian between the lava pits."),
        Plan("GLACIER GAP", Place.SNOW, "Jump the gaps in the glacier."),
        Plan("SPIKE RUINS", Place.DESERT, "Spikestacks and crushers in the ruins."),
        Plan("OBSIDIAN CAVES", Place.CAVE, "Dark caves with lots of obsidian."),
        Plan("DRAGON'S NEST", Place.END, "THE ENDER DRAGON is back! Break its crystals!", boss = "Z"),
        Plan("STORMY HEIGHTS", Place.HILLS, "High ledges, strong winds."),
        Plan("GRAVEYARD GROVE", Place.NIGHT, "Boneheads keep getting back up..."),
        Plan("CRUSHER CASTLE", Place.CASTLE, "A castle made of crushers!"),
        Plan("INFERNO", Place.VOLCANO, "The hottest place in the game."),
        Plan("VOID WALK", Place.END, "Walking on the edge of nothing."),
        Plan("THE LAST STAND", Place.END, "Every bad guy in the game. You can do it!"),
        Plan("THE ULTIMATE BATTLE", Place.END, "THREE BOSSES: Dry Bones King Krag, Wonder King Krag, then THE ENDER DRAGON!", boss = "YQZ"),
    )

    val all: List<LevelDef> by lazy {
        world3.mapIndexed { i, p -> make(p, world = 3, seed = 3000 + i * 17) } +
            world4.mapIndexed { i, p -> make(p, world = 4, seed = 4000 + i * 23) }
    }

    private fun make(p: Plan, world: Int, seed: Int): LevelDef = when (p.boss) {
        null -> level(p, world, seed)
        "W", "J" -> skyBoss(p, p.boss[0])
        "G" -> deepBoss(p)
        "Z" -> dragonBoss(p)
        "K", "Q", "Y" -> castleBoss(p, p.boss[0])
        else -> rush(p, p.boss)
    }

    /** The bad guys each world uses. Air ones go up high; the rest stand on the ground. */
    private fun groundFoes(world: Int, place: Place) = buildString {
        append("gkbAHUjj")
        if (world >= 4) append("bAnpj")
        if (place == Place.NIGHT || place == Place.END) append("nn")
        if (place == Place.JUNGLE) append("TTU")
        if (place == Place.DESERT) append("jjb")
    }

    private fun airFoes(place: Place) = when (place) {
        Place.CAVE, Place.DEEP -> "vvq"
        Place.NIGHT, Place.CASTLE, Place.END -> "qqv"
        else -> "llw"
    }

    private val rocks = listOf(
        arrayOf(" i ", "sis", "sss"), arrayOf("oDo", "sss"), arrayOf("eae", "sss"), arrayOf("uRu", "sss"),
        arrayOf(" x ", "sDs", "sss"), arrayOf("ioE", "sss"), arrayOf("DeD", "sss"), arrayOf("  a", " sx", "sss"),
    )

    /** A normal level: pieces one after another, then a staircase and the flag. */
    private fun level(p: Plan, world: Int, seed: Int): LevelDef {
        val r = Random(seed)
        val w = 200 + r.nextInt(30)
        val b = Builder(w)
        val place = p.place
        if (place == Place.CAVE || place == Place.CASTLE) b.fill(0, w - 1, 0, 1, if (place == Place.CAVE) 's' else 'X')
        val lava = place == Place.VOLCANO || place == Place.CASTLE
        val ground = groundFoes(world, place)
        val air = airFoes(place)
        b.ground(0, 16).at(3, 12, '@')
        // The first rock always has iron, gold, diamond or emerald in it.
        b.rock(8, 12, *rocks[listOf(0, 1, 2, 6)[r.nextInt(4)]])
        var x = 17
        var lastGap = true
        var checkpoints = 0
        var frog = false
        val busy = 1 + world - 3 // World 4 has more bad guys
        while (x < w - 28) {
            val kind = r.nextInt(7)
            when {
                kind == 0 && !lastGap && x > 24 -> {
                    // A gap (or a lava pit) to jump over.
                    val gw = 2 + r.nextInt(2)
                    if (lava) b.fill(x, x + gw - 1, 13, 14, 'L')
                    x += gw
                    lastGap = true
                    continue
                }
                kind == 1 -> {
                    // A raised ledge with a bad guy on it.
                    val len = 7 + r.nextInt(6)
                    b.ground(x, x + len - 1, 11)
                    b.onGround(ground[r.nextInt(ground.length)], x + len / 2)
                    if (r.nextBoolean()) b.row(x + 2, 7, "c".repeat(minOf(4, len - 3)))
                    x += len
                }
                kind == 2 -> {
                    // A solid hill: stairs up, a short top, and back down (no holes in it).
                    b.ground(x, x + 9)
                    val heights = intArrayOf(1, 2, 3, 3, 2, 1)
                    for (i in heights.indices) b.fill(x + 1 + i, x + 1 + i, 13 - heights[i], 12, 'X')
                    x += 10
                }
                else -> {
                    // Flat ground with ore, prize blocks, bad guys, maybe a crusher overhead.
                    val len = 11 + r.nextInt(8)
                    b.ground(x, x + len - 1)
                    if (r.nextInt(3) == 0) b.rock(x + 1, 12, *rocks[r.nextInt(rocks.size)])
                    val blocks = r.nextInt(3) == 0
                    if (blocks) b.row(x + len / 2 - 1, 9, listOf("?B?", "B?B", "?mB", "BhB", "B*B")[r.nextInt(5)])
                    repeat(busy + r.nextInt(2)) { k -> b.onGround(ground[r.nextInt(ground.length)], x + 5 + k * 3) }
                    if (r.nextInt(3) == 0) b.at(x + 6, if (air[0] == 'l') 3 else 8, air[r.nextInt(air.length)])
                    if (!blocks && len >= 13 && r.nextInt(3) == 0) b.at(x + len - 4, 8, 'z')
                    if (!frog && x > 40) { b.at(x + 2, 12, 'r'); frog = true }
                    if (checkpoints < 2 && x > w * (checkpoints + 1) / 3) { b.at(x + 3, 12, '|'); checkpoints++ }
                    x += len
                }
            }
            lastGap = false
        }
        // The end: a staircase and the flag.
        b.ground(x, w - 1)
        for (i in 0 until 4) b.fill(x + 4 + i, x + 4 + i, 12 - i, 12, 'X')
        b.onGround('p', x + 1)
        if (!frog) b.at(x + 2, 12, 'r')
        b.at(w - 8, 12, 'F')
        return b.build(p.name, place, p.tip)
    }

    /** Krag Jr. (or Wonder Krag Jr.) up in the clouds. */
    private fun skyBoss(p: Plan, boss: Char) = Builder(160)
        .ground(0, 16)
        .at(3, 12, '@').rock(8, 12, "Dx", "ie", "ss")
        .row(19, 11, "OOOO").row(25, 9, "OOOO").at(27, 3, 'l')
        .row(31, 11, "OOOOO").row(32, 10, "cccc")
        .ground(37, 48, 12).rock(40, 11, "oRD", "sss").at(46, 11, 'j')
        .row(51, 10, "OOO").row(56, 8, "OOOO").at(58, 7, 'w')
        .row(61, 11, "OOOOO").at(63, 10, 'H')
        .ground(67, 80, 12).at(68, 11, '|').at(70, 11, 'r').row(72, 8, "?m?")
        .rock(77, 11, "eD", "ss")
        .row(83, 10, "OOOO").row(89, 9, "OOOO")
        .row(95, 11, "OOO")
        .ground(99, 159, 12)
        .row(107, 7, "OOOO").row(118, 7, "OOOO").row(112, 4, "OOOO")
        .row(114, 3, "h")
        .at(116, 6, boss)
        .fill(125, 159, 12, 14, '#')
        .at(140, 11, 'F')
        .build(p.name, p.place, p.tip)

    /** The Warden's cave. */
    private fun deepBoss(p: Plan) = Builder(190)
        .ground(0, 189).fill(0, 189, 0, 1, 's').fill(0, 0, 2, 12, 'X')
        .at(3, 12, '@').rock(8, 12, "DxD", "sss").row(14, 9, "?h?")
        .at(20, 12, 'b').at(24, 8, 'q').at(28, 12, 'n').rock(33, 12, "aea", "sss").at(39, 12, 'j')
        .at(44, 12, 'r').at(48, 12, '|').at(52, 12, '^').at(53, 12, '^').at(58, 12, 'n').at(62, 8, 'v')
        .rock(66, 12, "oDo", "sss").at(72, 12, 'b').at(76, 8, 'z').at(80, 12, 'n').at(88, 12, '|').row(92, 9, "m*m")
        .row(110, 8, "h").row(130, 8, "h")
        .fill(147, 147, 2, 12, 'X')
        .at(130, 12, 'G')
        .build(p.name, p.place, p.tip)

    /** The Ender Dragon and its healing crystals. */
    private fun dragonBoss(p: Plan) = Builder(150)
        .ground(0, 149)
        .at(3, 12, '@').rock(7, 12, "DxD", "sss").row(13, 9, "?h?")
        .at(20, 12, 'n').at(25, 8, 'q').at(28, 12, 'n').at(34, 12, 'r').at(38, 12, '|')
        .rock(42, 12, "aaD", "sss").row(48, 9, "m*m")
        .fill(80, 81, 10, 12, 'X').at(80, 9, 'C')
        .fill(110, 111, 10, 12, 'X').at(110, 9, 'C')
        .row(95, 6, "h")
        .fill(141, 141, 2, 12, 'X')
        .at(95, 4, 'Z')
        .build(p.name, p.place, p.tip)

    /** A castle with King Krag (normal, Wonder or Dry Bones) at the end. */
    private fun castleBoss(p: Plan, boss: Char) = Builder(160)
        .ground(0, 159).fill(0, 159, 0, 1, 'X')
        .at(3, 12, '@').rock(7, 12, "DoxD", "ssss").row(14, 9, "?h?")
        .fill(19, 22, 13, 14, 'L').at(25, 12, 'b').at(28, 12, 'j').at(31, 8, 'q')
        .fill(37, 40, 13, 14, 'L').row(37, 9, "cccc").at(44, 12, 'A').at(47, 12, 'n')
        .rock(50, 12, "aRe", "ooD", "sss")
        .at(56, 12, 'r').at(59, 12, '|')
        .fill(62, 65, 13, 14, 'L').at(68, 12, 'U').at(71, 8, 'z').row(74, 9, "BhB").at(80, 12, 'b')
        .rock(84, 12, "eDo", "sss").at(90, 12, '|')
        .row(104, 8, "h").row(122, 8, "h")
        .fill(133, 133, 2, 12, 'X')
        .at(122, 12, boss)
        .build(p.name, p.place, p.tip)

    /** Several bosses one after another (your hearts fill up between them). */
    private fun rush(p: Plan, bosses: String): LevelDef {
        val gap = 100
        val w = 70 + bosses.length * gap + 20
        val b = Builder(w)
        b.ground(0, w - 1)
        b.at(3, 12, '@').rock(7, 12, "DxD", "sss").row(13, 9, "?h?")
        b.at(20, 12, 'n').at(27, 12, 'j').at(32, 12, 'r').at(36, 12, '|').rock(40, 12, "oeo", "sss")
        bosses.forEachIndexed { k, c ->
            val bx = 72 + k * gap
            b.row(bx - 14, 8, "h").row(bx + 8, 8, "h")
            if (c == 'Z') {
                b.fill(bx - 14, bx - 13, 10, 12, 'X').at(bx - 14, 9, 'C')
                b.fill(bx + 14, bx + 15, 10, 12, 'X').at(bx + 14, 9, 'C')
                b.at(bx, 4, 'Z')
            } else b.at(bx, 12, c)
            if (k < bosses.length - 1) {
                // A checkpoint (and some ore) between bosses.
                b.at(bx + 32, 12, '|').rock(bx + 36, 12, "aaD", "sss").row(bx + 42, 9, "m*m")
            }
        }
        b.fill(w - 9, w - 9, 2, 12, 'X')
        return b.build(p.name, p.place, p.tip)
    }
}
