package com.critters.game

import com.critters.game.Art.Look

class Wild(val species: Int, val min: Int, val max: Int, val weight: Int)

/** Stepping on (x, y) takes you to [map]; "BACK" returns to where you came in from. */
class Warp(val x: Int, val y: Int, val map: String, val tx: Int = 0, val ty: Int = 0, val face: Dir = Dir.UP)

/** Walking off the [dir] edge leads to [map], shifted by [offset] tiles along the edge. */
class Link(val map: String, val offset: Int = 0)

/** A tile that starts a script when the player steps on it (while [active]). */
class Trigger(val x: Int, val y: Int, val active: (Game) -> Boolean, val script: Script)

class NpcDef(
    val id: String,
    val x: Int,
    val y: Int,
    val look: Look,
    val face: Dir = Dir.DOWN,
    val text: String = "",
    val wander: Boolean = false,
    /** A trainer who battles you when they see you (within [sight] tiles in front). */
    val trainer: Trainer? = null,
    val sight: Int = 0,
    val intro: String = "",
    val after: String = "",
    val show: (Game) -> Boolean = { true },
    val talk: Script? = null,
    /** Runs instead of the usual trainer battle when this NPC spots you. */
    val spot: Script? = null,
)

class MapDef(
    val id: String,
    val name: String,
    val rows: List<String>,
    val tune: Tune,
    val border: Char = '#',
    val wild: List<Wild> = emptyList(),
    /** Caves: every floor tile can have wild critters, not just tall grass. */
    val cave: Boolean = false,
    val north: Link? = null,
    val south: Link? = null,
    val east: Link? = null,
    val west: Link? = null,
    val warps: List<Warp> = emptyList(),
    val npcs: List<NpcDef> = emptyList(),
    val triggers: List<Trigger> = emptyList(),
    val signs: Map<Pair<Int, Int>, String> = emptyMap(),
    val indoor: Boolean = false,
) {
    val w = rows[0].length
    val h = rows.size

    fun at(x: Int, y: Int): Char = if (x in 0 until w && y in 0 until h) rows[y][x] else border

    fun link(d: Dir) = when (d) { Dir.UP -> north; Dir.DOWN -> south; Dir.LEFT -> west; Dir.RIGHT -> east }
}

object World {
    const val RIVAL = "RYDER"

    fun rivalStarterFor(player: Int) = when (player) { 1 -> 3; 3 -> 5; else -> 1 }

    private fun trainerOf(npcId: String, t: Trainer) = t

    fun item(id: String, x: Int, y: Int, item: Item, count: Int = 1, map: String) = NpcDef(
        id, x, y, Look.BALL,
        show = { !it.flags.contains("item_${map}_$id") },
        talk = script {
            yield(Cmd.GiveItem(item, count))
            yield(Cmd.SetFlag("item_${map}_$id"))
            yield(Cmd.Refresh)
        },
    )

    private val house = listOf(
        "XXxXXxXX",
        "BBffffYP",
        "ffffffbf",
        "ffTTffbf",
        "ffTTffff",
        "ffffffff",
        "ffffffff",
        "fffEffff",
    )

    private fun backWarps(vararg xy: Pair<Int, Int>) = xy.map { Warp(it.first, it.second, "BACK") }

    // ------------------------------------------------------------------ Sprout Town

    private val sprout = MapDef(
        "sprout", "SPROUT TOWN",
        listOf(
            "########.==.########",
            "#......*.==.*......#",
            "#.RRRRR..==...RRRR.#",
            "#.RRRRR..==...RRRR.#",
            "#.WwDwW..==...WDwW.#",
            "#...=....==....=...#",
            "#...============..*#",
            "#.S......==.......*#",
            "#........==...***..#",
            "#..HHHHHH==........#",
            "#..HHHHHH==..FFFFF.#",
            "#..WwWDwW==..F~~~F.#",
            "#.....=...=..F~~~F.#",
            "#.....=====..FFFFF.#",
            "#*................*#",
            "#**..............**#",
            "####################",
        ),
        Tune.TOWN,
        north = Link("route1"),
        warps = listOf(Warp(4, 4, "home", 3, 7), Warp(15, 4, "house_sprout", 3, 7), Warp(6, 11, "lab", 4, 11)),
        signs = mapOf((2 to 7) to "SPROUT TOWN\nWHERE NEW JOURNEYS SPROUT!"),
        npcs = listOf(
            NpcDef("girl", 12, 8, Look.GIRL, wander = true,
                text = "TALL GRASS HIDES WILD CRITTERS. YOU NEED A CRITTER OF YOUR OWN TO GO THROUGH IT SAFELY!"),
            NpcDef("man", 6, 14, Look.OLDMAN, face = Dir.UP,
                text = "PROF. HAZEL'S LAB IS RIGHT HERE. SHE KNOWS EVERYTHING ABOUT CRITTERS!"),
        ),
        triggers = (8..11).map { x ->
            Trigger(x, 1, { !it.flags.contains("starter") }, script {
                yield(Cmd.Sound(Sfx.EXCLAIM))
                say("HEY! WAIT!", "IT'S NOT SAFE TO GO INTO THE TALL GRASS WITHOUT A CRITTER.", "GO SEE PROF. HAZEL IN HER LAB FIRST!")
                yield(Cmd.Walk("", "D"))
            })
        },
    )

    private val home = MapDef(
        "home", "HOME", house, Tune.TOWN, border = ' ', indoor = true,
        warps = backWarps(3 to 7),
        npcs = listOf(
            NpcDef("mom", 4, 4, Look.MOM, face = Dir.LEFT, talk = script { c ->
                if (!c.flag("starter")) {
                    say("MOM: OH, {P}! PROF. HAZEL WAS LOOKING FOR YOU.", "HER LAB IS JUST DOWN THE ROAD. TODAY'S THE BIG DAY!")
                } else {
                    say("MOM: {P}! YOU LOOK TIRED. YOU AND YOUR CRITTERS SHOULD TAKE A REST.")
                    yield(Cmd.Heal)
                    say("MOM: OH, GOOD! YOU'RE ALL READY TO GO. DON'T FORGET TO CALL!")
                }
            }),
        ),
    )

    private fun houseMap(id: String, npc: NpcDef, extra: List<NpcDef> = emptyList()) =
        MapDef(id, "HOUSE", house, Tune.TOWN, border = ' ', indoor = true, warps = backWarps(3 to 7), npcs = listOf(npc) + extra)

    private val houseSprout = houseMap(
        "house_sprout",
        NpcDef("kid", 5, 5, Look.BOY, wander = true,
            text = "MY BIG SISTER IS A SWIMMER IN TIDEPORT. SHE SAYS WATER CRITTERS ARE THE BEST!"),
        listOf(item("potion", 1, 5, Item.POTION, 1, "house_sprout")),
    )

    // ------------------------------------------------------------------ The lab and the starters

    private val labRows = listOf(
        "BBXXxxXXBB",
        "ffffffffff",
        "fffTTTTfff",
        "ffffffffff",
        "ffffffffff",
        "PffffffffP",
        "ffffffffff",
        "BBffffffBB",
        "ffffffffff",
        "ffffffffff",
        "YffffffffY",
        "ffffEfffff",
    )

    private fun starterBall(species: Int, x: Int) = NpcDef(
        "ball$species", x, 2, Look.BALL,
        show = { !it.flags.contains("took_$species") },
        talk = script { c ->
            if (c.flag("starter")) {
                say("THAT'S PROF. HAZEL'S LAST CRITTER. IT'S SAFE WITH HER.")
                return@script
            }
            val sp = Dex[species]
            yield(Cmd.Ask("SO, YOU WANT ${sp.name}, THE ${sp.types[0].name} CRITTER?"))
            if (!c.yes) return@script
            yield(Cmd.SetFlag("took_$species"))
            yield(Cmd.SetFlag("starter"))
            yield(Cmd.Refresh)
            yield(Cmd.GiveCritter(species, 5))
            val rs = rivalStarterFor(species)
            c.game.rivalStarter = rs
            val bx = listOf(1, 3, 5).indexOf(rs) + 4
            say("$RIVAL: THEN I'LL TAKE THIS ONE!")
            yield(Cmd.Walk("rival", "R".repeat(bx - 2) + "U"))
            yield(Cmd.Face("rival", Dir.UP))
            yield(Cmd.SetFlag("took_$rs"))
            yield(Cmd.Refresh)
            say("$RIVAL RECEIVED ${Dex[rs].name}!")
            yield(Cmd.FacePlayer("rival"))
            say("$RIVAL: WAIT, {P}! LET'S CHECK OUT OUR CRITTERS. COME ON, I'LL TAKE YOU ON!")
            yield(Cmd.Fight(rivalTeam(0, rs), canLose = true, tune = Tune.BOSS))
            if (c.won) say("$RIVAL: WHAT? NO WAY! I PICKED THE WRONG CRITTER!") else say("$RIVAL: YEAH! AM I GREAT OR WHAT?")
            say("PROF. HAZEL: WHAT A BATTLE! LET ME HEAL YOUR CRITTERS.")
            yield(Cmd.Heal)
            say("$RIVAL: OKAY! I'LL MAKE MY CRITTER BATTLE TO TOUGHEN IT UP. SMELL YA LATER, {P}!")
            yield(Cmd.Walk("rival", "DDDDDDD" + (if (bx > 4) "L".repeat(bx - 4) else "R".repeat(4 - bx)) + "D"))
            yield(Cmd.SetFlag("rival_left_lab"))
            yield(Cmd.Refresh)
            yield(Cmd.Music(Tune.TOWN))
        },
    )

    private val lab = MapDef(
        "lab", "LAB", labRows, Tune.TOWN, border = ' ', indoor = true,
        warps = backWarps(4 to 11),
        npcs = listOf(
            starterBall(1, 4), starterBall(3, 5), starterBall(5, 6),
            NpcDef("prof", 7, 2, Look.PROF, face = Dir.DOWN, talk = script { c ->
                when {
                    !c.flag("starter") -> say(
                        "PROF. HAZEL: HELLO, {P}! WELCOME TO MY LAB.",
                        "I STUDY CRITTERS, THE AMAZING CREATURES THAT LIVE ALL AROUND US.",
                        "THERE ARE THREE CRITTERS IN THE CAPSULES ON THE TABLE.",
                        "EMBIT IS FIRE, DRIZZLET IS WATER AND SPROUTLE IS GRASS.",
                        "GO ON, PICK ONE! IT'S YOURS TO KEEP.",
                    )
                    !c.flag("dex") -> {
                        say("PROF. HAZEL: {P}, I HAVE A FAVOR TO ASK.", "PLEASE FILL THIS CRITTERDEX! IT RECORDS EVERY CRITTER YOU MEET.")
                        yield(Cmd.Sound(Sfx.ITEM))
                        say("{P} RECEIVED THE CRITTERDEX!")
                        yield(Cmd.SetFlag("dex"))
                        say("PROF. HAZEL: AND TAKE THESE. WEAKEN A WILD CRITTER, THEN THROW A CAPSULE TO CATCH IT!")
                        yield(Cmd.GiveItem(Item.CAPSULE, 5))
                        say(
                            "THERE ARE THREE GYMS ON THE WAY: MAPLE CITY, TIDEPORT AND SPARKTON.",
                            "EARN ALL THREE BADGES, AND THEY'LL LET YOU INTO SUMMIT CAVE.",
                            "AT THE TOP, THEY SAY, LIVES A LEGENDARY CRITTER... GOOD LUCK, {P}!",
                        )
                    }
                    else -> say("PROF. HAZEL: HOW IS YOUR CRITTERDEX COMING ALONG? YOU'VE CAUGHT ${c.game.dexCaught()} KINDS SO FAR!")
                }
            }),
            NpcDef("rival", 2, 4, Look.RIVAL, face = Dir.UP, show = { !it.flags.contains("rival_left_lab") }, talk = script { c ->
                if (!c.flag("starter")) say("$RIVAL: YO, {P}! GRAMPS... I MEAN PROF. HAZEL ISN'T GIVING ME A CRITTER UNTIL YOU PICK ONE.", "HURRY UP!")
            }),
            NpcDef("aide", 7, 8, Look.CLERK, wander = true, text = "I'M THE PROF'S AIDE. CRITTERS GET STRONGER BY BATTLING, AND SOME EVEN EVOLVE!"),
        ),
    )

    fun rivalTeam(stage: Int, starter: Int): Trainer = when (stage) {
        0 -> Trainer("RIVAL", RIVAL, listOf(starter to 5), 30, "$RIVAL: AWW, MAN!")
        1 -> Trainer("RIVAL", RIVAL, listOf(7 to 9, starter to 12), 40, "$RIVAL: WHAT?! YOU GOT LUCKY!")
        else -> Trainer(
            "RIVAL", RIVAL, listOf(8 to 30, 10 to 29, 18 to 30, starter + 1 to 33), 60,
            "$RIVAL: ...I LOST. YOU REALLY ARE THE BEST, {P}.", potions = 2, boss = true,
        )
    }

    // ------------------------------------------------------------------ Route 1

    private val route1 = MapDef(
        "route1", "ROUTE 1",
        listOf(
            "########.==.########",
            "#.......====.......#",
            "#..,,,,..==..,,,,..#",
            "#..,,,,..==..,,,,..#",
            "#..,,,,..==..,,,,..#",
            "#.......====.......#",
            "#LLLLLL.====.LLLLLL#",
            "#........==........#",
            "#.S......==...###..#",
            "###.....====..###..#",
            "#,,,,....==.....,,,#",
            "#,,,,....==.....,,,#",
            "#,,,,....==.....,,,#",
            "#.......===........#",
            "#..####.===.####...#",
            "#..####.===.####...#",
            "#........===.......#",
            "#LLLLLLLL===LLLLLLL#",
            "#.,,,,,,..==.,,,,,,#",
            "#.,,,,,,..==.,,,,,,#",
            "#.,,,,,,..==.,,,,,,#",
            "#.........==.......#",
            "#*.*......==.....*.#",
            "########.==.########",
        ),
        Tune.ROUTE,
        wild = listOf(Wild(9, 2, 4, 40), Wild(7, 2, 4, 40), Wild(21, 3, 4, 10), Wild(15, 3, 4, 10)),
        north = Link("maple"), south = Link("sprout"),
        signs = mapOf((2 to 8) to "ROUTE 1\nSPROUT TOWN - MAPLE CITY"),
        npcs = listOf(
            NpcDef("tim", 4, 13, Look.BOY, face = Dir.RIGHT, sight = 4,
                trainer = Trainer("YOUNGSTER", "TIM", listOf(9 to 4), 15, "TIM: AWW, I LOST!"),
                intro = "HEY! YOU HAVE A CRITTER! LET'S BATTLE!", after = "I NEED TO CATCH MORE CRITTERS. MAYBE A PIPWING..."),
            NpcDef("amy", 16, 7, Look.GIRL, face = Dir.LEFT, sight = 4,
                trainer = Trainer("LASS", "AMY", listOf(7 to 4, 9 to 3), 15, "AMY: YOU'RE GOOD!"),
                intro = "MY EYES MET YOURS! THAT MEANS WE BATTLE!", after = "CRITTERS LEVEL UP WHEN THEY WIN. KEEP AT IT!"),
            item("potion", 18, 4, Item.POTION, 1, "route1"),
            item("capsule", 1, 21, Item.CAPSULE, 2, "route1"),
        ),
    )

    // ------------------------------------------------------------------ Maple City

    private val maple = MapDef(
        "maple", "MAPLE CITY",
        listOf(
            "####################",
            "#.GGGGGG....RRRR..*#",
            "#.GGGGGG....RRRR..*#",
            "#.WWDyWW....WwDW...#",
            "#...=.........=....#",
            "#...===========.....",
            "#.S.......=.........",
            "#.CCCC....=...MMMM.#",
            "#.CCCC....=...MMMM.#",
            "#.WnDW....=...WDvW.#",
            "#...=.....=....=...#",
            "#...============...#",
            "#.........=........#",
            "#**.....F.=.F....**#",
            "#**.....F.=.F....**#",
            "########.==.########",
        ),
        Tune.CITY,
        south = Link("route1"), east = Link("route2"),
        warps = listOf(Warp(4, 3, "gym1", 4, 11), Warp(14, 3, "house_maple", 3, 7), Warp(4, 9, "center", 4, 7), Warp(15, 9, "mart", 3, 7)),
        signs = mapOf((2 to 6) to "MAPLE CITY\nTHE CITY OF AUTUMN LEAVES"),
        npcs = listOf(
            NpcDef("boy", 12, 12, Look.BOY, wander = true,
                text = "THE GYM LEADER, GRANITA, USES ROCK CRITTERS. WATER AND GRASS MOVES CRUMBLE THEM!"),
            NpcDef("lady", 7, 5, Look.GIRL, face = Dir.DOWN,
                text = "THE CRITTER CENTER HAS THE RED ROOF. THE MART HAS THE BLUE ONE. EASY!"),
        ),
    )

    private val houseMaple = houseMap(
        "house_maple",
        NpcDef("old", 5, 4, Look.OLDMAN, face = Dir.LEFT,
            text = "WHEN A CRITTER IS ASLEEP OR PARALYZED, IT'S MUCH EASIER TO CATCH. HEH HEH."),
    )

    // ------------------------------------------------------------------ Route 2 and Mosswood

    private val route2 = MapDef(
        "route2", "ROUTE 2",
        listOf(
            "########################################",
            "#,,,,,,,..####....,,,,,,,,...####..,,,,#",
            "#,,,,,,,..####....,,,,,,,,...####..,,,,#",
            "#,,,,,,,...........,,,,,,,..........,,,#",
            "#.....S.....................S..........#",
            "========================================",
            "........................................",
            "#...,,,,,,,,......~~~~~~.....,,,,,,,,..#",
            "#...,,,,,,,,......~~~~~~.....,,,,,,,,..#",
            "#...,,,,,,,,..**..~~~~~~..**.,,,,,,,,..#",
            "#.............**.............,,,,,,,,..#",
            "########################################",
        ),
        Tune.ROUTE,
        wild = listOf(Wild(7, 6, 9, 30), Wild(9, 6, 9, 30), Wild(15, 6, 8, 20), Wild(11, 7, 9, 10), Wild(21, 7, 9, 10)),
        west = Link("maple"), east = Link("moss"),
        signs = mapOf(
            (6 to 4) to "ROUTE 2\nMAPLE CITY - MOSSWOOD",
            (28 to 4) to "TRAINER TIPS: WEAKEN A WILD CRITTER BEFORE YOU THROW A CAPSULE!",
        ),
        npcs = listOf(
            NpcDef("leo", 12, 4, Look.BUGKID, face = Dir.DOWN, sight = 2,
                trainer = Trainer("BUG KID", "LEO", listOf(15 to 8, 15 to 9), 15, "LEO: MY BUGS!"),
                intro = "I CAUGHT A BUNCH OF BUGS! WANNA SEE?", after = "GRUBBLE EVOLVES REALLY FAST. IT'S AWESOME."),
            NpcDef("hank", 26, 7, Look.HIKER, face = Dir.UP, sight = 1,
                trainer = Trainer("HIKER", "HANK", listOf(13 to 10), 25, "HANK: TOUGH AS ROCKS, AREN'T YA?"),
                intro = "HOHOH! I'M OFF TO CLIMB A MOUNTAIN!", after = "SUMMIT CAVE IS PAST SPARKTON. YOU NEED THREE BADGES TO GO IN."),
            NpcDef("jen", 34, 4, Look.GIRL, face = Dir.LEFT, sight = 4,
                trainer = Trainer("LASS", "JEN", listOf(9 to 10, 11 to 10), 20, "JEN: OH NO!"),
                intro = "LET'S SEE IF YOUR CRITTERS ARE AS CUTE AS MINE!", after = "ZAPPUP IS SO RARE. I GOT LUCKY!"),
            NpcDef("rival", 21, 4, Look.RIVAL, face = Dir.DOWN, sight = 2, show = { !it.flags.contains("rival2") },
                spot = script { c ->
                    yield(Cmd.Music(Tune.BOSS))
                    say("$RIVAL: HEY, {P}! I'VE BEEN TRAINING. LET'S SEE WHO'S STRONGER NOW!")
                    yield(Cmd.Fight(rivalTeam(1, c.rivalStarter), canLose = false, tune = Tune.BOSS))
                    say("$RIVAL: FINE. YOU WIN THIS TIME.", "I'M GOING THROUGH MOSSWOOD TO TIDEPORT. TRY TO KEEP UP!")
                    yield(Cmd.SetFlag("rival2"))
                    yield(Cmd.Refresh)
                    yield(Cmd.Music(Tune.ROUTE))
                }),
            item("capsule", 33, 2, Item.CAPSULE, 3, "route2"),
            item("superpotion", 37, 10, Item.SUPER_POTION, 1, "route2"),
        ),
    )

    private val moss = MapDef(
        "moss", "MOSSWOOD",
        listOf(
            "########################",
            "########################",
            "##,,,,,#####,,,,,,,#####",
            "##,,,,,#####,,,,,,,#####",
            "##..........,,,,,,,..###",
            "............#####.....##",
            "............#####.....##",
            "##,,,##....,,,,,,.....##",
            "##,,,##....,,,,,,..,,,##",
            "##,,,##.....#####..,,,##",
            "##.........######..,,,##",
            "####...,,,,,,..........#",
            "####...,,,,,,..........#",
            "####...,,,,,,###.......#",
            "##.........,,,###.......",
            "##.........,,,,,,.......",
            "##,,,,###..,,,,,,..**..#",
            "##,,,,###..........**..#",
            "########################",
            "########################",
        ),
        Tune.FOREST,
        wild = listOf(Wild(15, 9, 12, 30), Wild(17, 10, 12, 20), Wild(21, 10, 13, 25), Wild(7, 10, 12, 15), Wild(16, 12, 13, 10)),
        west = Link("route2"), east = Link("tide", -5),
        npcs = listOf(
            NpcDef("max", 9, 10, Look.BUGKID, face = Dir.UP, sight = 3,
                trainer = Trainer("BUG KID", "MAX", listOf(15 to 10, 16 to 12), 18, "MAX: MY LUMOTH!"),
                intro = "THE FOREST IS FULL OF BUGS! AND I'VE GOT THE BEST ONES!", after = "LUMOTH'S SLEEP POWDER IS SUPER USEFUL."),
            NpcDef("ivy", 20, 12, Look.GIRL, face = Dir.LEFT, sight = 4,
                trainer = Trainer("PICNICKER", "IVY", listOf(21 to 12, 7 to 12), 20, "IVY: AW, MY PICNIC IS RUINED."),
                intro = "I'M HAVING A PICNIC! WANT TO BATTLE FIRST?", after = "SHROOMP SMELLS LIKE MUSHROOM SOUP."),
            NpcDef("mort", 8, 14, Look.OLDMAN, face = Dir.RIGHT, sight = 3,
                trainer = Trainer("MEDIUM", "MORT", listOf(17 to 13, 17 to 13), 25, "MORT: THE SPIRITS... ARE SATISFIED."),
                intro = "THE SPIRITS OF THE FOREST... THEY WANT TO PLAY...", after = "SPOOKIT EVOLVES INTO GLOOMBRA. A SPOOKY SIGHT."),
            item("fullheal", 2, 4, Item.FULL_HEAL, 1, "moss"),
            item("revive", 22, 16, Item.REVIVE, 1, "moss"),
            item("capsule", 13, 17, Item.SUPER_CAPSULE, 1, "moss"),
        ),
    )

    // ------------------------------------------------------------------ Tideport

    private val tide = MapDef(
        "tide", "TIDEPORT",
        listOf(
            "########.==.########",
            "#........==.GGGGGG.#",
            "#.RRRR....=.GGGGGG.#",
            "#.RRRR....=.WWDyWW.#",
            "#.WDwW....=...=....#",
            "#..=......=====....#",
            "#..========........#",
            "#.CCCC....=..MMMM..#",
            "#.CCCC....=..MMMM..#",
            "..WnDW....=..WDvW..#",
            "....=======...=....#",
            "#.........=====..S.#",
            "#ssssssssssssssssss#",
            "#ssssssssssssssssss#",
            "#~~~~~~~~~~~~~~~~~~#",
            "#~~~~~~~~~~~~~~~~~~#",
            "####################",
        ),
        Tune.CITY,
        west = Link("moss", 5), north = Link("route3"),
        warps = listOf(Warp(14, 3, "gym2", 4, 11), Warp(3, 4, "house_tide", 3, 7), Warp(4, 9, "center", 4, 7), Warp(14, 9, "mart", 3, 7)),
        signs = mapOf((17 to 11) to "TIDEPORT\nTHE CITY BY THE SEA"),
        npcs = listOf(
            NpcDef("swim", 8, 12, Look.SWIMMER, wander = true, text = "MARINA, THE GYM LEADER, IS THE BEST SWIMMER IN TOWN. HER CRITTERS ARE FAST!"),
            NpcDef("sailor", 16, 13, Look.OLDMAN, face = Dir.LEFT, text = "ELECTRIC AND GRASS CRITTERS MAKE SHORT WORK OF WATER CRITTERS, MATEY."),
        ),
    )

    private val houseTide = houseMap(
        "house_tide",
        NpcDef("sis", 5, 4, Look.SWIMMER, face = Dir.LEFT, talk = script { c ->
            if (!c.flag("gift_tide")) {
                say("MY LITTLE BROTHER IN SPROUT TOWN TALKS ABOUT YOU! HERE, TAKE THIS.")
                yield(Cmd.GiveItem(Item.SUPER_CAPSULE, 3))
                yield(Cmd.SetFlag("gift_tide"))
            } else {
                say("SUPER CAPSULES CATCH CRITTERS BETTER THAN REGULAR ONES.")
            }
        }),
    )

    // ------------------------------------------------------------------ Route 3 and Sparkton

    private val route3 = MapDef(
        "route3", "ROUTE 3",
        listOf(
            "########.==.########",
            "#AAAA....==....AAAA#",
            "#A.......==.......A#",
            "#A.,,,,,.==.,,,,,.A#",
            "#A.,,,,,.==.,,,,,.A#",
            "#..,,,,,.==.,,,,,..#",
            "#........==........#",
            "#~~~ss...==...AAA..#",
            "#~~~ss...==...AAA..#",
            "#~~~ss.S.==........#",
            "#~~~ss...==..,,,,,.#",
            "#~~~ss...==..,,,,,.#",
            "#~~~ss...==..,,,,,.#",
            "#~~~ss...==........#",
            "#~~~ss...==...AA...#",
            "#~~~ss.,,==,,.AA...#",
            "#~~~ss.,,==,,......#",
            "#~~~ss.,,==,,..,,,.#",
            "#~~~ss.....=...,,,.#",
            "#~~~sssss..=.......#",
            "#AAAAAAA.==.AAAAAAA#",
            "########.==.########",
        ),
        Tune.ROUTE,
        wild = listOf(Wild(11, 15, 18, 25), Wild(13, 15, 18, 25), Wild(7, 15, 18, 20), Wild(9, 16, 18, 20), Wild(19, 15, 17, 10)),
        north = Link("spark"), south = Link("tide"),
        signs = mapOf((7 to 9) to "ROUTE 3\nTIDEPORT - SPARKTON"),
        npcs = listOf(
            NpcDef("kai", 6, 13, Look.SWIMMER, face = Dir.RIGHT, sight = 3,
                trainer = Trainer("SWIMMER", "KAI", listOf(19 to 16, 19 to 17), 20, "KAI: GLUB GLUB..."),
                intro = "THE SEA BREEZE IS GREAT! SO ARE MY FINNOWS!", after = "FINNOW EVOLVES INTO SHARKLE. IT'S SO COOL!"),
            NpcDef("rocky", 17, 8, Look.HIKER, face = Dir.LEFT, sight = 3,
                trainer = Trainer("HIKER", "ROCKY", listOf(13 to 17, 13 to 18), 25, "ROCKY: CRUMBLED LIKE GRAVEL!"),
                intro = "MY PEBBLITS ARE ROCK HARD!", after = "PEBBLIT EVOLVES AT LEVEL 25. THEN IT'S A REAL BOULDER!"),
            NpcDef("zoe", 12, 6, Look.GIRL, face = Dir.LEFT, sight = 2,
                trainer = Trainer("LASS", "ZOE", listOf(11 to 18, 16 to 18), 20, "ZOE: ZAP! I MEAN... WAAAH!"),
                intro = "BZZT! READY FOR A SHOCK?", after = "SPARKTON'S GYM LEADER IS SUPER FAST. LIKE LIGHTNING!"),
            item("superpotion", 18, 2, Item.SUPER_POTION, 1, "route3"),
            item("revive", 6, 19, Item.REVIVE, 1, "route3"),
        ),
    )

    private val spark = MapDef(
        "spark", "SPARKTON",
        listOf(
            "AAAAAAAAAOAAAAAAAAAA",
            "#........=.........#",
            "#.GGGGGG.=..RRRR...#",
            "#.GGGGGG.=..RRRR...#",
            "#.WWDyWW.=..WDwW...#",
            "#...=....=...=.....#",
            "#...===========....#",
            "#.S......=.........#",
            "#.CCCC...=...MMMM..#",
            "#.CCCC...=...MMMM..#",
            "#.WnDW...=...WDvW..#",
            "#...=....=....=....#",
            "#...===========....#",
            "#*.......=.......*.#",
            "#**......==......**#",
            "########.==.########",
        ),
        Tune.CITY,
        south = Link("route3"),
        warps = listOf(
            Warp(9, 0, "cave", 9, 19), Warp(4, 4, "gym3", 4, 11), Warp(13, 4, "house_spark", 3, 7),
            Warp(4, 10, "center", 4, 7), Warp(14, 10, "mart", 3, 7),
        ),
        signs = mapOf((2 to 7) to "SPARKTON\nTHE CITY THAT NEVER SLEEPS"),
        npcs = listOf(
            NpcDef("guard", 9, 1, Look.GUARD, face = Dir.DOWN, show = { it.badges < 3 },
                text = "SUMMIT CAVE IS DANGEROUS. ONLY TRAINERS WITH THREE BADGES MAY ENTER."),
            NpcDef("guard2", 10, 1, Look.GUARD, face = Dir.LEFT, show = { it.badges >= 3 },
                text = "THREE BADGES! YOU MAY PASS. THE PEAK IS AT THE TOP OF THE CAVE."),
            NpcDef("girl", 16, 13, Look.GIRL, wander = true, text = "VOLTA'S VOLTHOUND IS SO FAST! SLOW IT DOWN WITH A STRING SHOT OR BUBBLE BEAM."),
        ),
    )

    private val houseSpark = houseMap(
        "house_spark",
        NpcDef("nerd", 5, 4, Look.CLERK, face = Dir.LEFT, talk = script { c ->
            if (!c.flag("gift_spark")) {
                say("YOU'RE A TRAINER? TAKE THESE. I KEEP BUYING TOO MANY.")
                yield(Cmd.GiveItem(Item.SUPER_POTION, 2))
                yield(Cmd.SetFlag("gift_spark"))
            } else {
                say("STATUS PROBLEMS LIKE POISON KEEP HURTING AFTER BATTLE. USE A FULL HEAL!")
            }
        }),
    )

    // ------------------------------------------------------------------ Gyms

    private fun gym(
        id: String, rows: List<String>, leaderLook: Look, leader: Trainer, badge: String, badgeName: String,
        intro: List<String>, afterText: String, guide: String, trainers: List<NpcDef>,
    ) = MapDef(
        id, "GYM", rows, Tune.GYM, border = ' ', indoor = true,
        warps = backWarps(4 to 11),
        npcs = trainers + listOf(
            NpcDef("guide", 6, 10, Look.OLDMAN, face = Dir.DOWN, text = guide),
            NpcDef("leader", 4, 1, leaderLook, face = Dir.DOWN, talk = script { c ->
                if (c.flag(badge)) {
                    say(afterText)
                    return@script
                }
                for (l in intro) say(l)
                yield(Cmd.Fight(leader, tune = Tune.BOSS))
                yield(Cmd.Sound(Sfx.BADGE))
                say("{P} RECEIVED THE $badgeName!")
                yield(Cmd.SetFlag(badge))
                say(afterText)
                yield(Cmd.Music(Tune.GYM))
            }),
        ),
    )

    private val gym1 = gym(
        "gym1",
        listOf(
            "XXXXXXXXXX",
            "kkkkkkkkkk",
            "krrrkkkrrk",
            "kkkkkkkkkk",
            "kkkkrrkkkk",
            "kkkkkkkkkk",
            "krrkkkkrrk",
            "kkkkkkkkkk",
            "kkkkkkkkkk",
            "kQkkkkkkQk",
            "kkkkkkkkkk",
            "kkkkEkkkkk",
        ),
        Look.GRANITA,
        Trainer("LEADER", "GRANITA", listOf(13 to 10, 13 to 12), 80, "GRANITA: I UNDERESTIMATED YOU. YOU'VE EARNED THIS.", potions = 1, boss = true),
        "badge1", "STONE BADGE",
        listOf("GRANITA: SO, A NEW CHALLENGER! I'M GRANITA, THE MAPLE CITY GYM LEADER.", "MY ROCK-HARD WILL IS AS SOLID AS MY CRITTERS. SHOW ME WHAT YOU'VE GOT!"),
        "GRANITA: THE NEXT GYM IS IN TIDEPORT, PAST MOSSWOOD. MARINA IS A TOUGH ONE!",
        "YO! FUTURE CHAMP! GRANITA USES ROCK CRITTERS. WATER AND GRASS MOVES WORK GREAT. FIRE AND FLYING... NOT SO MUCH!",
        listOf(
            NpcDef("hiker", 2, 8, Look.HIKER, face = Dir.RIGHT, sight = 5,
                trainer = Trainer("HIKER", "DAVE", listOf(13 to 8, 13 to 9), 20, "DAVE: ROCK BOTTOM!"),
                intro = "YOU WANT TO FACE GRANITA? GO THROUGH ME FIRST!", after = "GRANITA'S PEBBLITS HARDEN THEIR SHELLS. HIT THEM HARD!"),
        ),
    )

    private val gym2 = gym(
        "gym2",
        listOf(
            "XXXXXXXXXX",
            "gggggggggg",
            "g~~gggg~~g",
            "g~~gggg~~g",
            "gggggggggg",
            "ggg~~~~ggg",
            "gggggggggg",
            "g~~gggg~~g",
            "gggggggggg",
            "gQggggggQg",
            "gggggggggg",
            "ggggEggggg",
        ),
        Look.MARINA,
        Trainer("LEADER", "MARINA", listOf(19 to 17, 20 to 19), 90, "MARINA: WOW... YOU SWEPT ME AWAY!", potions = 1, boss = true),
        "badge2", "TIDE BADGE",
        listOf("MARINA: HI! I'M MARINA. I LOVE THE SEA AND EVERY CRITTER IN IT.", "MY WATER CRITTERS WILL WASH YOU OUT TO SEA. READY?"),
        "MARINA: THE LAST GYM IS IN SPARKTON, NORTH ALONG ROUTE 3. VOLTA IS SHOCKINGLY STRONG!",
        "HEY CHAMP! MARINA'S CRITTERS ARE WATER TYPE. USE ELECTRIC OR GRASS MOVES. KEEP FIRE AND ROCK AWAY!",
        listOf(
            NpcDef("swimmer", 8, 6, Look.SWIMMER, face = Dir.LEFT, sight = 6,
                trainer = Trainer("SWIMMER", "NIA", listOf(19 to 16, 3 to 17), 25, "NIA: SPLASHED!"),
                intro = "WELCOME TO THE TIDE GYM! TIME TO MAKE A SPLASH!", after = "MARINA'S SHARKLE HAS A NASTY BITE."),
        ),
    )

    private val gym3 = gym(
        "gym3",
        listOf(
            "XXXXXXXXXX",
            "gggggggggg",
            "gPPggggPPg",
            "gggggggggg",
            "ggPPggPPgg",
            "gggggggggg",
            "gPPggggPPg",
            "gggggggggg",
            "gggggggggg",
            "gQggggggQg",
            "gggggggggg",
            "ggggEggggg",
        ),
        Look.VOLTA,
        Trainer("LEADER", "VOLTA", listOf(11 to 24, 8 to 24, 12 to 27), 100, "VOLTA: SHORT-CIRCUITED! YOU'RE ELECTRIFYING!", potions = 2, boss = true),
        "badge3", "VOLT BADGE",
        listOf("VOLTA: HEY THERE! I'M VOLTA, AND SPARKTON NEVER SLEEPS!", "MY CRITTERS ARE FASTER THAN LIGHTNING. TRY TO KEEP UP!"),
        "VOLTA: WITH THREE BADGES, THE GUARD WILL LET YOU INTO SUMMIT CAVE. THE PEAK AWAITS!",
        "YO CHAMP! VOLTA'S CRITTERS ARE ELECTRIC AND FLYING. ROCK MOVES ARE YOUR FRIEND HERE!",
        listOf(
            NpcDef("eng", 1, 5, Look.CLERK, face = Dir.RIGHT, sight = 6,
                trainer = Trainer("ENGINEER", "BOLT", listOf(11 to 22, 11 to 23), 25, "BOLT: OVERLOAD!"),
                intro = "THESE MACHINES POWER THE WHOLE CITY! AND MY CRITTERS!", after = "VOLTA'S VOLTHOUND IS AMAZINGLY FAST."),
            NpcDef("kid", 8, 3, Look.BOY, face = Dir.LEFT, sight = 4,
                trainer = Trainer("SCHOOL KID", "OTTO", listOf(7 to 23, 12 to 23), 20, "OTTO: I NEED TO STUDY MORE!"),
                intro = "ELECTRIC BEATS FLYING AND WATER! I LEARNED THAT IN SCHOOL!", after = "ROCK RESISTS ELECTRIC. THAT'S ON THE TEST!"),
        ),
    )

    // ------------------------------------------------------------------ Center and Mart

    private val center = MapDef(
        "center", "CRITTER CENTER",
        listOf(
            "XXXXxxXXXX",
            "YffffffffP",
            "ffTTTTTTff",
            "ffffffffff",
            "ffffffffff",
            "ZZffffffZZ",
            "ZZffffffZZ",
            "ffffEfffff",
        ),
        Tune.CENTER, border = ' ', indoor = true,
        warps = backWarps(4 to 7),
        npcs = listOf(
            NpcDef("nurse", 4, 1, Look.NURSE, face = Dir.DOWN, talk = script { c ->
                say("WELCOME TO THE CRITTER CENTER!")
                yield(Cmd.Ask("SHALL I HEAL YOUR CRITTERS?"))
                if (c.yes) {
                    say("OKAY, I'LL TAKE YOUR CRITTERS FOR A FEW SECONDS.")
                    yield(Cmd.Heal)
                    c.game.setRespawnHere()
                    say("THANK YOU FOR WAITING. YOUR CRITTERS ARE FIGHTING FIT!", "WE HOPE TO SEE YOU AGAIN!")
                } else {
                    say("WE HOPE TO SEE YOU AGAIN!")
                }
            }),
            NpcDef("gent", 7, 5, Look.OLDMAN, wander = true,
                text = "CRITTER CENTERS HEAL YOUR CRITTERS FOR FREE. AND THE PC IN THE CORNER STORES ANY EXTRA CRITTERS YOU CATCH."),
        ),
    )

    private val mart = MapDef(
        "mart", "MART",
        listOf(
            "XXXXXXXX",
            "ffffffff",
            "TTTffBBf",
            "ffffffff",
            "ffffBBBf",
            "ffffffff",
            "fBBfffff",
            "fffEffff",
        ),
        Tune.CENTER, border = ' ', indoor = true,
        warps = backWarps(3 to 7),
        npcs = listOf(
            NpcDef("clerk", 1, 1, Look.CLERK, face = Dir.DOWN, talk = script { c ->
                say("HI THERE! MAY I HELP YOU?")
                val stock = mutableListOf(Item.CAPSULE, Item.POTION, Item.FULL_HEAL)
                if (c.badges >= 1) stock += Item.SUPER_POTION
                if (c.badges >= 2) { stock += Item.SUPER_CAPSULE; stock += Item.REVIVE }
                yield(Cmd.Shop(stock))
                say("PLEASE COME AGAIN!")
            }),
            NpcDef("shopper", 6, 5, Look.GIRL, wander = true, text = "SUPER CAPSULES GO ON SALE ONCE YOU HAVE TWO BADGES."),
        ),
    )

    // ------------------------------------------------------------------ Summit Cave and the Peak

    private val cave = MapDef(
        "cave", "SUMMIT CAVE",
        listOf(
            "KKKKKKKKKKKKKKKKKKKK",
            "KKKKKKKKKlKKKKKKKKKK",
            "KKKKKkkkkkkkkKKKKKKK",
            "KKkkkkkrkkkkkkkkKKKK",
            "KKkkKKKKKKKKkkkkkKKK",
            "KKkkKKKKKKKKKKkkkKKK",
            "KKkkkkkkkkkKKKkkkkKK",
            "KKKKKKKKkkkkKKKKkkKK",
            "KKkkkkrkkkkkkkkkkkKK",
            "KKkkKKKKKKKKKKKKKKKK",
            "KKkkkkkkkkkkkkkkkKKK",
            "KKKKKKKKKKKKKKkkkKKK",
            "KKkkkkkkkkkkkkkkkKKK",
            "KKkkrkkKKKKKKKKKKKKK",
            "KKkkkkkkkkkkkkkkkkKK",
            "KKKKKKKKKKKKKKKkkkKK",
            "KKkkkkkkkkkkkkkkkkKK",
            "KKkkKKKKKKKKKKKKKKKK",
            "KKkkkkkkkkkkkkkkKKKK",
            "KKKKKKKKKkkkkkkkKKKK",
            "KKKKKKKKKlKKKKKKKKKK",
            "KKKKKKKKKKKKKKKKKKKK",
        ),
        Tune.CAVE, border = 'K', cave = true,
        wild = listOf(Wild(13, 24, 27, 30), Wild(11, 24, 26, 20), Wild(17, 24, 27, 20), Wild(18, 26, 28, 10), Wild(14, 27, 28, 10), Wild(10, 25, 27, 10)),
        warps = listOf(Warp(9, 20, "spark", 9, 1, Dir.DOWN), Warp(9, 1, "peak", 5, 10, Dir.UP)),
        npcs = listOf(
            NpcDef("hiker", 10, 14, Look.HIKER, face = Dir.LEFT, sight = 5,
                trainer = Trainer("HIKER", "BRUNO", listOf(14 to 26, 13 to 27), 30, "BRUNO: YOU MOVED A MOUNTAIN!"),
                intro = "ONLY THE STRONGEST REACH THE PEAK!", after = "THE LEGEND SAYS SOLARIS RESTS AT THE PEAK BEFORE DAWN."),
            NpcDef("ace", 5, 10, Look.BOY, face = Dir.RIGHT, sight = 6,
                trainer = Trainer("ACE", "VICTOR", listOf(8 to 27, 18 to 27), 40, "VICTOR: INCREDIBLE!"),
                intro = "I'VE BEATEN ALL THREE GYMS TOO. LET'S SEE WHO'S BETTER!", after = "KEEP CLIMBING. YOU'RE ALMOST THERE."),
            NpcDef("ace2", 16, 4, Look.GIRL, face = Dir.DOWN, sight = 3,
                trainer = Trainer("ACE", "STELLA", listOf(16 to 27, 12 to 28), 40, "STELLA: YOU'RE A NATURAL!"),
                intro = "THE AIR IS THIN UP HERE. CAN YOU HANDLE IT?", after = "THE LADDER UP AHEAD LEADS TO THE PEAK."),
            item("revive", 2, 17, Item.REVIVE, 1, "cave"),
            item("superpotion", 17, 15, Item.SUPER_POTION, 2, "cave"),
            item("capsule", 2, 4, Item.SUPER_CAPSULE, 2, "cave"),
        ),
    )

    private val peak = MapDef(
        "peak", "SUMMIT PEAK",
        listOf(
            "AAAAAAAAAAAA",
            "AAAAiiiiAAAA",
            "AAAiiiiiiAAA",
            "AAiiiiiiiiAA",
            "AiiiiiiiiiiA",
            "AiiiiiiiiiiA",
            "AiiiiiiiiiiA",
            "AiiiiiiiiiiA",
            "AAiiiiiiiiAA",
            "AAAiiiiiiAAA",
            "AAAAiiiiAAAA",
            "AAAAAOAAAAAA",
        ),
        Tune.FOREST, border = 'A',
        warps = listOf(Warp(5, 11, "cave", 9, 2, Dir.DOWN)),
        npcs = listOf(
            NpcDef("rival", 5, 6, Look.RIVAL, face = Dir.DOWN, sight = 3, show = { it.flags.contains("badge3") },
                spot = script { c ->
                    if (c.flag("rival3")) return@script
                    yield(Cmd.Music(Tune.BOSS))
                    say(
                        "$RIVAL: SO YOU MADE IT, {P}. I KNEW YOU WOULD.",
                        "I'VE BEATEN EVERY GYM AND EVERY TRAINER IN THIS CAVE.",
                        "THERE'S JUST ONE THING LEFT TO DO. BEAT YOU! LET'S GO!",
                    )
                    yield(Cmd.Fight(rivalTeam(2, c.rivalStarter), tune = Tune.BOSS))
                    say(
                        "$RIVAL: I GAVE IT EVERYTHING I HAD. YOU'RE THE CHAMPION NOW, {P}.",
                        "...HEY. LOOK UP THERE. DO YOU SEE THAT LIGHT?",
                        "IT'S SOLARIS! THE LEGENDARY CRITTER! GO ON. THIS ONE'S YOURS.",
                    )
                    yield(Cmd.SetFlag("rival3"))
                    yield(Cmd.Refresh)
                    yield(Cmd.Music(Tune.FOREST))
                },
                talk = script { c ->
                    if (c.flag("rival3")) say("$RIVAL: GO ON! SOLARIS IS WAITING FOR YOU.")
                }),
            NpcDef("solaris", 5, 2, Look.SOLARIS, face = Dir.DOWN, show = { it.flags.contains("rival3") && !it.flags.contains("solaris") },
                talk = script { c ->
                    say("A WARM LIGHT FILLS THE AIR...", "KYAAAAH!")
                    yield(Cmd.Wild(22, 40))
                    if (c.choice == Battle.Result.RUN.ordinal) {
                        say("SOLARIS IS STILL WATCHING YOU...")
                        return@script
                    }
                    if (c.choice == Battle.Result.CAUGHT.ordinal) {
                        say("SOLARIS JOINED YOUR TEAM! ITS LIGHT SHINES ON THE WHOLE WORLD.")
                    } else {
                        say("SOLARIS FLEW UP INTO THE RISING SUN...", "MAYBE IT WILL RETURN SOMEDAY.")
                    }
                    yield(Cmd.SetFlag("solaris"))
                    yield(Cmd.Refresh)
                    yield(Cmd.Credits)
                }),
        ),
    )

    val maps: Map<String, MapDef> = listOf(
        sprout, home, houseSprout, lab, route1, maple, houseMaple, route2, moss, tide, houseTide,
        route3, spark, houseSpark, gym1, gym2, gym3, center, mart, cave, peak,
    ).associateBy { it.id }

    operator fun get(id: String): MapDef = maps[id] ?: error("No map '$id'")
}
