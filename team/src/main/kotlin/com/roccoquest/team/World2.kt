package com.roccoquest.team

/** The first level of World 2 (as an index into [Levels.all]). */
const val WORLD2_START = 21

/** Every world has 21 levels: 1-1 to 1-21, 2-1 to 2-21, and so on. */
const val WORLD_SIZE = 21

/** How many worlds there are. */
fun worldCount() = (Levels.all.size + WORLD_SIZE - 1) / WORLD_SIZE

/** Where each world's beaten levels are saved (the first two names come from older saves). */
fun clearedKey(w: Int) = when (w) { 0 -> "clearedLo"; 1 -> "clearedHi"; else -> "cleared$w" }

/**
 * World 2: 21 more levels after the Ender Dragon, using everything from World 1 but harder,
 * with more bad guys mixed together. It ends with THE FINAL BATTLE: Wonder King Krag and then
 * the Ender Dragon, back to back. Same character grid as [Levels] (see [LevelDef]).
 */
object World2 {
    val all: List<LevelDef> by lazy {
        listOf(
            sunsetMeadow(), crystalCaverns(), stormySkies(), sandstormDunes(),
            blizzardBridge(), sunkenShip(), kragsReturn(),
            jungleRuins(), minecartMadness(), volcanoPeak(), rainbowFortress(),
            moonlightVillage(), deepSeaTrench(), endermanWoods(), wardensLair(),
            pyramidOfSpikes(), magmaMines(), skullKeep(),
            bombFactory(), theLastRoad(), theFinalBattle(),
        )
    }

    private fun stairs(b: Builder, x: Int, steps: Int, top: Int = 13) = b.apply {
        for (i in 0 until steps) fill(x + i, x + i, top - 1 - i, top - 1, 'X')
    }

    private fun sunsetMeadow() = Builder(200)
        .ground(0, 48).ground(52, 100).ground(104, 150).ground(154, 199)
        .at(3, 12, '@').at(9, 12, 'V')
        .rock(13, 12, " i ", "sis", "sss")
        .row(20, 9, "?m?").at(25, 12, 'g').at(28, 12, 'w').at(31, 12, 'k')
        .rock(36, 12, "oe", "ss").at(42, 12, 'b').at(45, 12, 'g')
        .row(52, 8, "cccc")
        .at(56, 12, 'r').at(60, 12, '|')
        .rock(64, 12, "  D ", " ioi", "ssss")
        .at(71, 12, 'k').at(74, 12, 'w').at(77, 12, 'b').row(80, 9, "B*B")
        .at(86, 12, 'g').at(88, 12, 'g').at(90, 12, 'g')
        .let { stairs(it, 94, 3) }
        .at(108, 12, '|').rock(112, 12, "eDe", "sss")
        .at(118, 12, 'n').at(124, 12, 'b').at(128, 12, 'w').row(132, 9, "?h?")
        .at(138, 12, 'k').at(142, 12, 'b').rock(145, 12, "uau", "sss")
        .at(158, 12, 'g').at(161, 12, 'w').at(164, 12, 'b').rock(168, 12, "DoD", "sss")
        .let { stairs(it, 176, 3) }
        .at(190, 12, 'F')
        .onGround('p', 34, 84, 172)
        .build(
            "SUNSET MEADOW", Place.HILLS,
            "WORLD 2! More bad guys, and they come in gangs. Stick together!",
        )

    private fun crystalCaverns() = Builder(200)
        .ground(0, 46).ground(50, 96).ground(100, 148).ground(152, 199)
        .fill(0, 199, 0, 1, 's').fill(0, 0, 2, 12, 'X')
        .fill(47, 49, 13, 14, 'L').fill(97, 99, 13, 14, 'L').fill(149, 151, 13, 14, 'L')
        .at(3, 12, '@').rock(7, 12, "DaD", "sss")
        .at(13, 8, 'v').at(17, 12, 'b').row(20, 9, "?m?")
        .fill(24, 30, 2, 4, 's').row(25, 4, "aDaDa")
        .rock(26, 12, "  a  ", " saas ", "ssssss")
        .at(35, 12, 'k').at(38, 8, 'v').at(42, 12, 'n')
        .at(54, 12, 'r').at(57, 12, '|')
        .rock(61, 12, "eae", "sss").at(67, 8, 'v').at(70, 12, 'b').at(73, 12, 'b')
        .row(77, 8, "BBBBB").row(77, 7, "ccccc")
        .rock(85, 12, "oDo", "sss").at(91, 12, 'k').at(94, 8, 'v')
        .at(104, 12, '|').rock(108, 12, "aia", "sss")
        .at(114, 12, 'n').at(119, 8, 'v').at(122, 8, 'v').at(125, 12, 'b')
        .row(129, 9, "m*m").rock(135, 12, "DeD", "sss").at(141, 12, 'k').at(145, 12, 'b')
        .at(156, 12, 'b').at(160, 8, 'v').rock(163, 12, "aa", "ss").at(168, 12, 'n')
        .let { stairs(it, 174, 2) }
        .at(188, 12, 'F')
        .onGround('p', 32, 83, 132)
        .build(
            "CRYSTAL CAVERNS", Place.CAVE,
            "Sparkly crystals everywhere... and LOTS of Flapper bats!",
        )

    private fun stormySkies() = Builder(160)
        .ground(0, 16)
        .at(3, 12, '@').rock(8, 12, "Do", "ie", "ss")
        .row(19, 11, "OOOO").row(25, 9, "OOOO").at(27, 8, 'w')
        .row(31, 11, "OOOOO").row(32, 10, "cccc")
        .ground(37, 48, 12).rock(40, 11, "oiD", "sss").at(46, 11, 'k')
        .row(51, 10, "OOO").at(53, 5, 'v').row(56, 8, "OOOO").at(58, 7, 'w')
        .row(61, 11, "OOOOO").at(63, 10, 'g')
        .ground(67, 80, 12).at(68, 11, '|').at(70, 11, 'r').row(72, 8, "?m?")
        .rock(77, 11, "eD", "ss")
        .row(83, 10, "OOOO").row(89, 9, "OOOO").at(91, 8, 'w')
        .row(95, 11, "OOO")
        .ground(99, 159, 12)
        .row(107, 7, "OOOO").row(118, 7, "OOOO").row(112, 4, "OOOO")
        .row(114, 3, "h")
        .at(116, 6, 'J')
        .fill(125, 159, 12, 14, '#')
        .at(140, 11, 'F')
        .build(
            "STORMY SKIES", Place.SKY,
            "KRAG JR. IS BACK! And he brought the thunderclouds!",
        )

    private fun sandstormDunes() = Builder(210)
        .ground(0, 30).ground(31, 60, 11).ground(64, 92).ground(93, 124, 11).ground(128, 160).ground(161, 209, 11)
        .at(3, 12, '@').rock(7, 12, "uuo", "sus")
        .at(12, 12, 'V').row(15, 9, "?m?").at(20, 12, 'b').at(24, 12, 'k').at(28, 12, 'g')
        .at(35, 10, 'b').at(39, 10, 'w').rock(43, 10, "oiu", "sss")
        .at(54, 10, 'k').at(58, 10, 'r')
        .at(67, 12, '|').at(70, 12, '^').at(71, 12, '^').rock(75, 12, " D ", "uiu", "sss")
        .at(82, 12, 'b').at(85, 12, 'b').row(87, 9, "B*B")
        .at(97, 10, 'g').at(100, 10, 'w').at(104, 10, 'n').rock(109, 10, "eue", "sss")
        .at(116, 10, 'k').at(120, 10, '|')
        .at(132, 12, 'b').at(135, 12, '^').at(136, 12, '^').at(140, 12, 'k').row(144, 9, "?h?")
        .rock(149, 12, "DuD", "sss").at(156, 12, 'n')
        .at(165, 10, 'b').at(169, 10, 'g').at(172, 10, 'g').rock(176, 10, "oeo", "sss")
        .let { stairs(it, 184, 3, top = 11) }
        .at(198, 10, 'F')
        .onGround('p', 48, 112, 162)
        .build(
            "SANDSTORM DUNES", Place.DESERT,
            "Up and down the sand dunes. Boneheads AND Endermen!",
        )

    private fun blizzardBridge() = Builder(200)
        .ground(0, 30)
        .at(3, 12, '@').rock(8, 12, "aa", "ia", "ss")
        .row(13, 9, "?h?").at(18, 12, 'w').at(22, 12, 'g').at(26, 12, 'k')
        .row(33, 11, "OOOOO").at(35, 10, 'g')
        .row(40, 9, "OOOO").at(41, 8, 'w')
        .row(46, 11, "OOOOO")
        .ground(53, 80).at(55, 12, 'r').at(58, 12, '|').rock(62, 12, " D ", "aoa", "sss")
        .at(68, 12, 'b').at(71, 12, 'w').at(74, 12, 'k').row(76, 9, "m?m")
        .row(83, 11, "OOOOO").row(90, 9, "OOOO").at(92, 8, 'g').row(96, 11, "OOOOO")
        .ground(103, 140, 12).at(107, 11, '|').rock(111, 11, "eae", "sss")
        .at(118, 11, 'n').at(123, 11, 'w').at(127, 11, 'b').row(130, 8, "*")
        .ground(144, 199).at(148, 12, 'g').at(151, 12, 'k').at(154, 12, 'b')
        .rock(158, 12, "aDi", "sss").at(165, 12, 'w').at(168, 12, 'w')
        .let { stairs(it, 174, 3) }
        .at(188, 12, 'F')
        .build(
            "BLIZZARD BRIDGE", Place.SNOW,
            "Hop across the icy cloud bridges. Don't fall!",
        )

    private fun sunkenShip() = Builder(180)
        .ground(0, 179)
        .at(3, 12, '@').at(7, 12, '~').rock(11, 12, "Di", "ss")
        .at(16, 7, 'f').at(20, 10, 'f').at(24, 5, 'f').at(28, 12, '~')
        .fill(32, 34, 8, 12, 's').row(32, 8, "oDo")
        .at(38, 9, 'f').at(42, 6, 'f').at(46, 12, '~').row(48, 4, "ccccc")
        .at(52, 8, 'f').at(56, 11, 'f').at(60, 5, 'f').at(64, 12, '~').at(66, 12, '|')
        .fill(70, 72, 0, 5, 'X')
        .at(76, 10, 'f').at(80, 6, 'f').rock(84, 12, "eae", "sss").at(90, 12, '~')
        .at(94, 7, 'f').at(98, 10, 'f').at(102, 5, 'f').at(106, 12, '~')
        .fill(110, 113, 8, 12, 's').row(110, 8, "DuoD").at(117, 6, 'f').at(121, 10, 'f').at(124, 12, '~')
        .fill(128, 129, 0, 6, 'X').at(132, 8, 'f').at(136, 11, 'f').at(140, 12, '~')
        .at(146, 6, 'f').at(150, 9, 'f').at(154, 12, '~')
        .at(160, 0, 'S').fill(160, 179, 11, 12, '#').at(163, 10, 'r').at(172, 10, 'F')
        .build(
            "SUNKEN SHIP", Place.WATER,
            "Swim through the old shipwreck. Grab the bubbles!",
        )

    private fun kragsReturn() = Builder(150)
        .ground(0, 149).fill(0, 149, 0, 1, 'X')
        .at(3, 12, '@').rock(7, 12, "DoiD", "ssss").row(14, 9, "?h?")
        .fill(19, 22, 13, 14, 'L').at(24, 12, 'b').at(27, 12, 'k').at(30, 8, 'v')
        .fill(31, 32, 10, 12, 'X').fill(37, 40, 13, 14, 'L').row(37, 9, "cccc").at(43, 12, 'n').at(46, 12, 'b')
        .rock(49, 12, "aie", "ooD", "sss")
        .at(55, 12, 'r').at(58, 12, '|')
        .fill(61, 64, 13, 14, 'L').at(67, 12, 'k').at(70, 12, 'b').row(73, 9, "BhB").at(77, 12, 'w').at(80, 12, 'b')
        .rock(84, 12, "eDo", "sss").at(90, 12, '|')
        .row(104, 8, "h")
        .fill(131, 131, 2, 12, 'X')
        .at(122, 12, '&')
        .at(112, 12, '+').at(117, 12, '+').at(126, 12, '+').at(130, 12, '+')
        .build(
            "KRAG'S TAIL SPIN", Place.CASTLE,
            "Sneak behind KING KRAG, grab his TAIL, spin him round and throw him into a SPIKE BOMB!",
        )

    private fun jungleRuins() = Builder(210)
        .ground(0, 44).ground(47, 82).ground(86, 132, 12).ground(135, 170).ground(174, 209)
        .at(3, 12, '@').rock(7, 12, "ei", "ss").at(10, 12, 'V')
        .row(13, 9, "?m?").at(17, 12, 'g').at(19, 12, 'g').at(21, 12, 'b')
        .rock(25, 12, " e ", "eoe", "sss").at(31, 12, 'k').at(34, 12, 'w').at(37, 12, 'n').row(39, 8, "BhB")
        .at(50, 12, 'b').at(53, 12, 'g').at(56, 12, 'g').at(59, 12, 'r').at(62, 12, '|')
        .rock(65, 12, "  D ", " eae", "ssss").at(72, 12, 'k').at(75, 12, 'w').at(78, 12, 'b')
        .at(90, 11, 'g').at(92, 11, 'g').at(94, 11, 'n').row(96, 8, "?*?")
        .rock(101, 11, "oDo", "sss").at(106, 11, 'w').at(109, 11, 'k').at(112, 11, 'v').at(116, 11, '|')
        .rock(120, 11, "ua", "ss").at(125, 11, 'b').at(128, 11, 'g')
        .at(139, 12, 'w').at(142, 12, 'b').rock(146, 12, "DeD", "sss").at(152, 12, 'n').row(156, 9, "m?m").at(162, 12, 'k')
        .at(178, 12, 'b').at(181, 12, 'g').at(184, 12, 'g')
        .let { stairs(it, 188, 3) }
        .at(200, 12, 'F')
        .onGround('p', 58, 136, 168)
        .build(
            "JUNGLE RUINS", Place.JUNGLE,
            "Old ruins in the jungle. Watch out, the Endermen moved in!",
        )

    private fun minecartMadness() = Builder(230)
        .ground(0, 229).fill(0, 229, 0, 1, 's').fill(0, 0, 2, 12, 'X')
        .at(3, 12, '@').at(8, 12, 'V').rock(12, 12, "iDi", "sss")
        .fill(18, 100, 12, 12, '=').at(18, 12, 'M')
        .at(28, 12, 'g').at(36, 12, 'b').at(44, 12, 'k').at(52, 12, 'n').at(60, 12, 'b')
        .at(68, 12, 'k').at(76, 12, 'b').at(86, 12, 'g').at(94, 12, 'b')
        .at(32, 8, 'v').at(56, 8, 'v').at(80, 8, 'v')
        .at(104, 12, '|').rock(108, 12, "DoD", "sss").at(114, 12, 'r').at(117, 12, '^').at(118, 12, '^')
        .fill(122, 190, 12, 12, '=').at(122, 12, 'M')
        .at(132, 12, 'b').at(142, 12, 'n').at(152, 12, 'b').at(162, 12, 'k').at(172, 12, 'b').at(182, 12, 'n')
        .at(146, 8, 'v').at(170, 8, 'v')
        .at(194, 12, '|').rock(198, 12, "aea", "sss").at(205, 12, 'b').at(209, 8, 'v')
        .let { stairs(it, 214, 2) }
        .at(222, 12, 'F')
        .onGround('p', 102, 120, 203)
        .build(
            "MINECART MADNESS", Place.CAVE,
            "Two BIG minecart rides! Smash everything on the tracks!",
        )

    private fun volcanoPeak() = Builder(210)
        .ground(0, 31).ground(34, 62, 11).ground(66, 100).ground(103, 136, 11).ground(141, 170).ground(174, 209)
        .fill(32, 33, 13, 14, 'L').fill(63, 65, 13, 14, 'L').fill(101, 102, 13, 14, 'L')
        .fill(137, 140, 13, 14, 'L').fill(171, 173, 13, 14, 'L')
        .at(3, 12, '@').rock(7, 12, "oDa", "sss").row(13, 9, "?h?").at(18, 12, 'b').at(22, 8, 'v').at(26, 12, 'n')
        .at(38, 10, 'b').at(41, 10, 'k').rock(45, 10, "aua", "sss").at(52, 6, 'v').at(56, 10, 'r').at(59, 10, '|')
        .at(70, 12, 'b').at(73, 12, '^').at(74, 12, '^').rock(78, 12, " D ", "eae", "sss")
        .at(85, 12, 'n').row(88, 9, "m*m").at(95, 12, 'b')
        .at(107, 10, '|').at(111, 10, 'k').rock(115, 10, "DoD", "sss").at(121, 6, 'v').at(125, 10, 'b').at(129, 10, 'n')
        .at(145, 12, 'b').at(149, 12, 'b').row(152, 9, "?m?").rock(157, 12, "aDi", "sss").at(164, 12, 'n')
        .at(178, 12, 'b').at(182, 8, 'v')
        .let { stairs(it, 188, 2) }
        .at(200, 12, 'F')
        .onGround('p', 50, 92, 168)
        .build(
            "VOLCANO PEAK", Place.VOLCANO,
            "Climb the volcano! Lava pits everywhere, jump carefully.",
        )

    private fun rainbowFortress() = Builder(160)
        .ground(0, 16)
        .at(3, 12, '@').rock(8, 12, "Da", "eo", "ss")
        .row(19, 11, "OOOO").row(25, 9, "OOOO").at(27, 8, 'w')
        .row(31, 11, "OOOOO").row(32, 10, "cccc")
        .ground(37, 48, 12).rock(40, 11, "Due", "sss").at(44, 11, 'n')
        .row(51, 10, "OOO").row(56, 8, "OOOO").at(58, 7, 'w')
        .row(61, 11, "OOOOO").at(63, 10, 'b')
        .ground(67, 80, 12).at(68, 11, '|').at(70, 11, 'r').row(72, 8, "m*m")
        .rock(77, 11, "oD", "ss")
        .row(83, 10, "OOOO").row(89, 9, "OOOO").at(91, 8, 'w')
        .row(95, 11, "OOO")
        .ground(99, 159, 12)
        .row(107, 7, "OOOO").row(118, 7, "OOOO").row(112, 4, "OOOO")
        .row(114, 3, "h")
        .at(116, 6, 'W')
        .fill(125, 159, 12, 14, '#')
        .at(140, 11, 'F')
        .build(
            "RAINBOW FORTRESS", Place.SKY,
            "WONDER KRAG JR. wants a rematch! The sky goes all wobbly!",
        )

    private fun moonlightVillage() = Builder(210)
        .ground(0, 209)
        .at(3, 12, '@').at(9, 12, 'V').rock(13, 12, "io", "ss").row(18, 9, "?m?").at(23, 12, 'g').at(26, 12, 'n')
        .at(31, 12, '^').at(32, 12, '^').rock(36, 12, "eDe", "sss").at(43, 12, 'V').at(47, 12, 'k')
        .at(52, 12, 'r').at(55, 12, '|')
        .fill(58, 104, 12, 12, '=').at(58, 12, 'M')
        .at(66, 12, 'n').at(72, 12, 'b').at(78, 12, 'g').at(84, 12, 'n').at(92, 12, 'b').at(98, 12, 'k')
        .at(108, 12, 'V').rock(112, 12, "oDo", "sss").at(119, 12, '^').at(120, 12, '^').at(121, 12, '^')
        .at(126, 12, 'n').at(130, 12, 'w').at(134, 12, '|').row(138, 9, "*").at(144, 12, 'b').at(148, 12, 'n')
        .rock(153, 12, "aea", "sss").at(160, 12, 'g').at(163, 12, 'k')
        .let { stairs(it, 168, 3) }
        .at(184, 12, 'V').at(196, 12, 'F')
        .onGround('p', 40, 106, 157)
        .build(
            "MOONLIGHT VILLAGE", Place.NIGHT,
            "Night time in the village. Trade with VILLAGERS before the Endermen come!",
        )

    private fun deepSeaTrench() = Builder(190)
        .ground(0, 189)
        .at(3, 12, '@').at(7, 12, '~').rock(11, 12, "oDo", "sss")
        .at(17, 9, 'f').at(21, 5, 'f').at(25, 11, 'f').at(29, 12, '~')
        .fill(33, 35, 6, 12, 's').row(33, 6, "eDe")
        .at(39, 9, 'f').at(43, 4, 'f').at(47, 12, '~').row(50, 6, "ccccc")
        .at(54, 10, 'f').at(58, 6, 'f').at(62, 11, 'f').at(66, 12, '~').at(68, 12, '|')
        .fill(72, 73, 0, 6, 'X')
        .at(77, 8, 'f').at(81, 4, 'f').rock(85, 12, "aia", "sss").at(91, 12, '~')
        .at(95, 7, 'f').at(99, 11, 'f').at(103, 5, 'f').at(107, 12, '~')
        .fill(111, 113, 8, 12, 's').row(111, 8, "DoD").at(117, 6, 'f').at(121, 10, 'f').at(125, 12, '~').at(127, 12, '|')
        .fill(131, 132, 0, 5, 'X').at(136, 8, 'f').at(140, 11, 'f').at(144, 12, '~')
        .at(148, 5, 'f').at(152, 9, 'f').at(156, 12, '~').at(160, 7, 'f').at(164, 10, 'f')
        .at(170, 0, 'S').fill(170, 189, 11, 12, '#').at(173, 10, 'r').at(182, 10, 'F')
        .build(
            "DEEP SEA TRENCH", Place.WATER,
            "The longest swim yet! Stay near the bubble vents.",
        )

    private fun endermanWoods() = Builder(210)
        .ground(0, 56).ground(60, 121).ground(124, 170, 12).ground(174, 209)
        .at(3, 12, '@').at(8, 12, 'V').rock(12, 12, "DiD", "sss")
        .at(19, 12, 'n').at(24, 12, 'w').row(28, 9, "?m?").at(34, 12, 'b').rock(38, 12, "eae", "sss")
        .at(45, 12, 'n').at(50, 12, 'k')
        .at(63, 12, 'r').at(67, 12, '|').at(73, 12, 'n').row(78, 9, "*").rock(82, 12, "oDo", "sss")
        .at(89, 12, 'b').at(94, 12, 'n')
        .at(100, 12, '^').at(101, 12, '^').at(106, 12, 'w').at(111, 12, 'n').at(116, 12, 'k')
        .at(128, 11, '|').at(133, 11, 'n').rock(137, 11, "aua", "sss").at(144, 11, 'b').at(149, 11, 'n')
        .row(153, 8, "m?m").at(160, 11, 'n')
        .at(178, 12, 'b').at(182, 12, 'n')
        .let { stairs(it, 188, 2) }
        .at(194, 12, 'V').at(200, 12, 'F')
        .onGround('p', 54, 118, 165)
        .build(
            "ENDERMAN WOODS", Place.NIGHT,
            "A whole forest of ENDERMEN! Use your SUPER move!",
        )

    private fun wardensLair() = Builder(180)
        .ground(0, 179).fill(0, 179, 0, 1, 's').fill(0, 0, 2, 12, 'X')
        .at(3, 12, '@').rock(8, 12, "DiD", "sss").row(14, 9, "?h?")
        .at(20, 12, 'b').at(24, 8, 'v').at(28, 12, 'n').rock(33, 12, "aea", "sss").at(39, 12, 'b')
        .at(44, 12, 'r').at(48, 12, '|').at(52, 12, '^').at(53, 12, '^').at(58, 12, 'n').at(62, 8, 'v')
        .rock(66, 12, "oDo", "sss").at(72, 12, 'b').at(78, 12, 'n').at(84, 12, '|').row(88, 9, "m*m")
        .row(106, 8, "h").row(126, 8, "h")
        .fill(141, 141, 2, 12, 'X')
        .at(125, 12, 'G')
        .build(
            "WARDEN'S LAIR", Place.DEEP,
            "Shhh... THE WARDEN woke up again. Tiptoe!",
        )

    private fun pyramidOfSpikes() = Builder(210)
        .ground(0, 209)
        .at(3, 12, '@').at(8, 12, 'V').rock(12, 12, "uoi", "sss")
        .at(18, 12, '^').at(19, 12, '^').at(20, 12, '^').row(18, 8, "ccc")
        .at(25, 12, 'b').at(29, 12, 'k')
        .fill(33, 40, 12, 12, 'X').fill(34, 39, 11, 11, 'X').fill(35, 38, 10, 10, 'X').fill(36, 37, 9, 9, 'X')
        .at(44, 12, '^').at(45, 12, '^').at(48, 12, 'n')
        .at(53, 12, 'r').at(56, 12, '|')
        .fill(59, 112, 12, 12, '=').at(59, 12, 'M')
        .at(68, 12, 'b').at(78, 12, 'n').at(88, 12, 'k').at(98, 12, 'b').at(106, 12, 'b')
        .rock(116, 12, "DaD", "sss").at(122, 12, '^').at(123, 12, '^').at(124, 12, '^').at(128, 12, 'n')
        .at(134, 12, '|').at(138, 12, 'V').row(142, 9, "m*m")
        .fill(147, 154, 12, 12, 'X').fill(148, 153, 11, 11, 'X').fill(149, 152, 10, 10, 'X').fill(150, 151, 9, 9, 'X')
        .at(158, 12, 'b').at(161, 12, '^').at(162, 12, '^').at(166, 12, 'n')
        .rock(171, 12, "eDe", "sss")
        .let { stairs(it, 178, 3) }
        .at(196, 12, 'F')
        .onGround('p', 50, 131, 175)
        .build(
            "PYRAMID OF SPIKES", Place.DESERT,
            "Climb the pyramids and jump the SPIKE TRAPS!",
        )

    private fun magmaMines() = Builder(210)
        .ground(0, 40).ground(44, 92).ground(96, 146).ground(150, 209)
        .fill(0, 209, 0, 1, 's').fill(0, 0, 2, 12, 'X')
        .fill(41, 43, 13, 14, 'L').fill(93, 95, 13, 14, 'L').fill(147, 149, 13, 14, 'L')
        .at(3, 12, '@').rock(7, 12, "DoD", "sss").row(13, 9, "?h?")
        .fill(18, 24, 2, 4, 's').row(19, 4, "oDoDo")
        .at(20, 12, 'b').at(24, 8, 'v').at(28, 12, 'n').at(33, 12, '^').at(34, 12, '^').at(37, 12, 'b')
        .at(48, 12, 'k').rock(52, 12, "aua", "sss").at(58, 8, 'v').at(62, 12, 'n')
        .at(66, 12, 'r').at(70, 12, '|').at(74, 12, 'b').row(78, 9, "m*m").rock(84, 12, "eDe", "sss")
        .at(100, 12, 'n').at(104, 8, 'v').at(108, 12, 'b').rock(112, 12, "  a ", " DoD", "ssss").at(120, 12, 'k')
        .at(126, 12, '|').at(130, 12, 'n').at(134, 12, '^').at(135, 12, '^').at(139, 8, 'v').at(142, 12, 'b')
        .at(154, 12, 'b').at(158, 12, 'n').rock(163, 12, "oao", "sss").at(170, 8, 'v').at(174, 12, 'b')
        .let { stairs(it, 180, 2) }
        .at(198, 12, 'F')
        .onGround('p', 46, 98, 152)
        .build(
            "MAGMA MINES", Place.VOLCANO,
            "Super-hot mines full of diamonds. Mine fast and keep moving!",
        )

    private fun skullKeep() = Builder(170)
        .ground(0, 169).fill(0, 169, 0, 1, 'X')
        .at(3, 12, '@').rock(7, 12, "DoDa", "ssss").row(14, 9, "?h?")
        .fill(19, 22, 13, 14, 'L').at(25, 12, 'b').at(28, 12, 'n').at(31, 8, 'v')
        .at(36, 12, 'b').fill(40, 43, 13, 14, 'L').row(40, 9, "cccc")
        .at(47, 12, 'k').rock(51, 12, "eDa", "sss").at(57, 12, 'b').at(61, 12, 'r').at(64, 12, '|')
        .row(68, 9, "m*m").at(74, 12, 'n').at(78, 12, 'b').at(82, 8, 'v').fill(86, 89, 13, 14, 'L').at(93, 12, '|')
        .row(108, 8, "h").row(128, 8, "h").row(118, 9, "m")
        .fill(143, 143, 2, 12, 'X')
        .at(130, 12, 'Y')
        .build(
            "SKULL KEEP", Place.CASTLE,
            "DRY BONES KING KRAG is back! Knock him down twice!",
        )

    private fun bombFactory() = Builder(210)
        .ground(0, 209).fill(0, 209, 0, 1, 'X')
        .at(3, 12, '@').rock(7, 12, "iDi", "sss").row(13, 9, "?h?")
        .fill(18, 21, 13, 14, 'L').at(25, 12, 'b').at(29, 12, 'k')
        .fill(33, 34, 10, 12, 'X').row(38, 9, "m?m").at(44, 12, 'n')
        .fill(48, 51, 13, 14, 'L').rock(54, 12, "oeo", "sss").at(60, 12, 'r').at(63, 12, '|')
        .fill(66, 110, 12, 12, '=').at(66, 12, 'M')
        .at(76, 12, 'b').at(86, 12, 'k').at(96, 12, 'b')
        .rock(114, 12, "DaD", "sss").at(121, 12, '^').at(122, 12, '^').at(126, 12, 'n')
        .at(132, 12, '|').row(136, 9, "*").fill(140, 143, 13, 14, 'L').at(147, 12, 'b').at(151, 12, 'n')
        .rock(156, 12, "eDo", "sss")
        .let { stairs(it, 164, 3) }
        .at(198, 12, 'F')
        .onGround('p', 23, 36, 42, 52, 118, 129, 145, 150, 160, 172, 180, 188)
        .build(
            "BOMB-BOMB FACTORY", Place.CASTLE,
            "BOMB-BOMBS everywhere! Stomp one, then KICK it into the others!",
        )

    private fun theLastRoad() = Builder(220)
        .ground(0, 50).ground(54, 111).ground(114, 160, 11).ground(164, 219)
        .at(3, 12, '@').at(8, 12, 'V').rock(12, 12, "DeD", "sss")
        .row(18, 9, "?m?").at(23, 12, 'g').at(26, 12, 'w').at(30, 12, 'k').at(34, 12, 'b').at(38, 12, 'n').at(43, 8, 'v')
        .rock(46, 12, "oi", "ss")
        .at(58, 12, 'r').at(62, 12, '|').rock(66, 12, "aDa", "sss").at(72, 12, 'n').at(77, 12, '^').at(78, 12, '^')
        .fill(82, 104, 12, 12, '=').at(82, 12, 'M').at(90, 12, 'b').at(98, 12, 'n')
        .row(106, 9, "*")
        .at(118, 10, '|').at(122, 10, 'n').at(126, 10, 'b').rock(130, 10, "eoe", "sss").at(137, 6, 'v')
        .at(141, 10, 'n').at(146, 10, 'k').row(150, 7, "m?m").at(156, 10, 'n')
        .at(168, 12, 'b').at(172, 12, 'n').rock(176, 12, "DuD", "sss").at(184, 12, 'w').at(188, 12, 'n')
        .let { stairs(it, 194, 3) }
        .at(210, 12, 'F')
        .onGround('p', 49, 109, 166)
        .build(
            "THE LAST ROAD", Place.END,
            "Every kind of bad guy, all at once! The final battle is close...",
        )

    private fun theFinalBattle() = Builder(220)
        .ground(0, 219)
        .at(3, 12, '@').rock(7, 12, "DaD", "sss").row(13, 9, "?h?")
        .at(20, 12, 'n').at(27, 12, 'b').at(32, 12, 'r').at(36, 12, '|')
        .rock(40, 12, "oeo", "sss")
        // Round 1: Wonder King Krag.
        .row(58, 8, "h").row(80, 8, "h").row(69, 9, "m")
        .at(72, 12, 'Q')
        // Round 2: the Ender Dragon and its healing crystals.
        .at(104, 12, '|').rock(108, 12, "aaD", "sss").row(114, 9, "m*m")
        .fill(156, 157, 10, 12, 'X').at(156, 9, 'C')
        .fill(184, 185, 10, 12, 'X').at(184, 9, 'C')
        .row(170, 6, "h")
        .fill(211, 211, 2, 12, 'X')
        .at(170, 4, 'Z')
        .build(
            "THE FINAL BATTLE", Place.END,
            "WONDER KING KRAG, then THE ENDER DRAGON. Two bosses! Good luck, team!",
        )
}
