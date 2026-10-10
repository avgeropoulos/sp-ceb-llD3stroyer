package com.critters.game

enum class Type(val color: Int) {
    NORMAL(0xFFA8A878.toInt()),
    FIRE(0xFFF08030.toInt()),
    WATER(0xFF6890F0.toInt()),
    GRASS(0xFF78C850.toInt()),
    ELECTRIC(0xFFE8C020.toInt()),
    ROCK(0xFFB8A038.toInt()),
    FLYING(0xFFA890F0.toInt()),
    BUG(0xFFA8B820.toInt()),
    GHOST(0xFF705898.toInt());

    companion object {
        private val strong = mapOf(
            FIRE to setOf(GRASS, BUG),
            WATER to setOf(FIRE, ROCK),
            GRASS to setOf(WATER, ROCK),
            ELECTRIC to setOf(WATER, FLYING),
            ROCK to setOf(FIRE, FLYING, BUG),
            FLYING to setOf(GRASS, BUG),
            BUG to setOf(GRASS, GHOST),
            GHOST to setOf(GHOST),
        )
        private val weak = mapOf(
            NORMAL to setOf(ROCK),
            FIRE to setOf(FIRE, WATER, ROCK),
            WATER to setOf(WATER, GRASS),
            GRASS to setOf(FIRE, GRASS, FLYING, BUG),
            ELECTRIC to setOf(ELECTRIC, GRASS, ROCK),
            ROCK to setOf(ROCK),
            FLYING to setOf(ELECTRIC, ROCK),
            BUG to setOf(FIRE, FLYING),
        )
        private val immune = mapOf(NORMAL to setOf(GHOST), GHOST to setOf(NORMAL))

        /** Damage multiplier for a move of type [atk] hitting a defender of type [def]. */
        fun mult(atk: Type, def: Type): Float = when {
            immune[atk]?.contains(def) == true -> 0f
            strong[atk]?.contains(def) == true -> 2f
            weak[atk]?.contains(def) == true -> 0.5f
            else -> 1f
        }
    }
}

enum class Status(val tag: String) { OK(""), BURN("BRN"), POISON("PSN"), PARALYZE("PAR"), SLEEP("SLP") }

enum class Effect { NONE, BURN, POISON, PARALYZE, SLEEP, ATK_DOWN, DEF_DOWN, SPD_DOWN, ATK_UP, DEF_UP, SPD_UP, DRAIN, HIGH_CRIT, PRIORITY, RECOIL }

/**
 * A battle move. Damaging moves have [power] > 0 and apply [effect] with
 * [chance] percent; status moves (power 0) always try their [effect].
 */
enum class Move(
    val label: String, val type: Type, val power: Int, val acc: Int, val pp: Int,
    val effect: Effect = Effect.NONE, val chance: Int = 100,
) {
    TACKLE("TACKLE", Type.NORMAL, 40, 100, 35),
    SCRATCH("SCRATCH", Type.NORMAL, 40, 100, 35),
    GROWL("GROWL", Type.NORMAL, 0, 100, 40, Effect.ATK_DOWN),
    LEER("LEER", Type.NORMAL, 0, 100, 30, Effect.DEF_DOWN),
    QUICK_STRIKE("QUICK STRIKE", Type.NORMAL, 40, 100, 30, Effect.PRIORITY),
    HEADBUTT("HEADBUTT", Type.NORMAL, 70, 100, 15),
    HYPER_FANG("HYPER FANG", Type.NORMAL, 80, 90, 15),
    BODY_SLAM("BODY SLAM", Type.NORMAL, 85, 100, 15, Effect.PARALYZE, 30),
    HARDEN("HARDEN", Type.NORMAL, 0, 100, 30, Effect.DEF_UP),
    AGILITY("AGILITY", Type.NORMAL, 0, 100, 30, Effect.SPD_UP),
    EMBER("EMBER", Type.FIRE, 40, 100, 25, Effect.BURN, 10),
    FLAME_WHEEL("FLAME WHEEL", Type.FIRE, 60, 100, 25, Effect.BURN, 10),
    FLAMETHROWER("FLAMETHROWER", Type.FIRE, 90, 100, 15, Effect.BURN, 10),
    SOLAR_FLARE("SOLAR FLARE", Type.FIRE, 110, 90, 5, Effect.BURN, 30),
    WATER_GUN("WATER GUN", Type.WATER, 40, 100, 25),
    BUBBLE_BEAM("BUBBLE BEAM", Type.WATER, 65, 100, 20, Effect.SPD_DOWN, 10),
    WAVE_CRASH("WAVE CRASH", Type.WATER, 90, 95, 10),
    VINE_WHIP("VINE WHIP", Type.GRASS, 45, 100, 25),
    RAZOR_LEAF("RAZOR LEAF", Type.GRASS, 55, 95, 25, Effect.HIGH_CRIT),
    MEGA_DRAIN("MEGA DRAIN", Type.GRASS, 40, 100, 15, Effect.DRAIN),
    PETAL_BLADE("PETAL BLADE", Type.GRASS, 90, 100, 10),
    SLEEP_POWDER("SLEEP POWDER", Type.GRASS, 0, 75, 15, Effect.SLEEP),
    GROWTH("GROWTH", Type.GRASS, 0, 100, 40, Effect.ATK_UP),
    THUNDER_SHOCK("THUNDERSHOCK", Type.ELECTRIC, 40, 100, 30, Effect.PARALYZE, 10),
    SPARK("SPARK", Type.ELECTRIC, 65, 100, 20, Effect.PARALYZE, 30),
    THUNDERBOLT("THUNDERBOLT", Type.ELECTRIC, 90, 100, 15, Effect.PARALYZE, 10),
    THUNDER_WAVE("THUNDER WAVE", Type.ELECTRIC, 0, 90, 20, Effect.PARALYZE),
    ROCK_THROW("ROCK THROW", Type.ROCK, 50, 90, 15),
    ROCK_SLIDE("ROCK SLIDE", Type.ROCK, 75, 90, 10),
    STONE_EDGE("STONE EDGE", Type.ROCK, 100, 80, 5, Effect.HIGH_CRIT),
    GUST("GUST", Type.FLYING, 40, 100, 35),
    WING_ATTACK("WING ATTACK", Type.FLYING, 60, 100, 35),
    SKY_DIVE("SKY DIVE", Type.FLYING, 90, 95, 15),
    STRING_SHOT("STRING SHOT", Type.BUG, 0, 95, 40, Effect.SPD_DOWN),
    POISON_STING("POISON STING", Type.BUG, 15, 100, 35, Effect.POISON, 30),
    BUG_BITE("BUG BITE", Type.BUG, 50, 100, 20),
    SIGNAL_BEAM("SIGNAL BEAM", Type.BUG, 75, 100, 15),
    LICK("LICK", Type.GHOST, 30, 100, 30, Effect.PARALYZE, 30),
    SHADOW_SNEAK("SHADOW SNEAK", Type.GHOST, 40, 100, 30, Effect.PRIORITY),
    HYPNOSIS("HYPNOSIS", Type.GHOST, 0, 60, 20, Effect.SLEEP),
    SHADOW_BALL("SHADOW BALL", Type.GHOST, 80, 100, 15),
    STRUGGLE("STRUGGLE", Type.NORMAL, 50, 100, 1, Effect.RECOIL),
}

/**
 * A kind of critter. Base stats are HP / ATK / DEF / SPD. [learn] lists
 * (level, move) pairs; an evolved form lists its whole learnset.
 */
class Species(
    val id: Int,
    val name: String,
    val types: List<Type>,
    val hp: Int, val atk: Int, val def: Int, val spd: Int,
    val catchRate: Int,
    val baseExp: Int,
    val learn: List<Pair<Int, Move>>,
    val evolveLevel: Int = 0,
    val evolveTo: Int = 0,
    val kind: String,
    val dex: String,
) {
    fun movesAt(level: Int): List<Move> = learn.filter { it.first <= level }.map { it.second }.distinct().takeLast(4)
}

object Dex {
    val all: List<Species> = listOf(
        Species(1, "EMBIT", listOf(Type.FIRE), 39, 52, 43, 65, 45, 64,
            listOf(1 to Move.SCRATCH, 1 to Move.GROWL, 6 to Move.EMBER, 10 to Move.QUICK_STRIKE, 14 to Move.FLAME_WHEEL),
            16, 2, "EMBER PUP", "THE FLAME ON ITS TAIL FLARES UP WHEN IT IS HAPPY. IT LOVES WARM ROCKS."),
        Species(2, "BLAZOR", listOf(Type.FIRE), 78, 88, 72, 92, 45, 160,
            listOf(1 to Move.SCRATCH, 1 to Move.EMBER, 10 to Move.QUICK_STRIKE, 14 to Move.FLAME_WHEEL,
                20 to Move.HEADBUTT, 28 to Move.FLAMETHROWER, 36 to Move.BODY_SLAM),
            kind = "BLAZE", dex = "ITS FIERY MANE BURNS HOT ENOUGH TO MELT IRON. IT FIGHTS WITH HONOR."),
        Species(3, "DRIZZLET", listOf(Type.WATER), 44, 48, 62, 45, 45, 64,
            listOf(1 to Move.TACKLE, 1 to Move.LEER, 6 to Move.WATER_GUN, 10 to Move.QUICK_STRIKE, 14 to Move.BUBBLE_BEAM),
            16, 4, "OTTER", "IT SPLASHES IN PUDDLES ALL DAY. ITS FUR SHEDS WATER LIKE A RAINCOAT."),
        Species(4, "TIDALON", listOf(Type.WATER), 82, 84, 92, 72, 45, 160,
            listOf(1 to Move.TACKLE, 1 to Move.WATER_GUN, 10 to Move.QUICK_STRIKE, 14 to Move.BUBBLE_BEAM,
                20 to Move.HEADBUTT, 28 to Move.WAVE_CRASH, 36 to Move.BODY_SLAM),
            kind = "TIDE", dex = "IT RIDES OCEAN WAVES FOR FUN. ITS TAIL CAN SPLIT A BOULDER IN TWO."),
        Species(5, "SPROUTLE", listOf(Type.GRASS), 46, 49, 52, 45, 45, 64,
            listOf(1 to Move.TACKLE, 1 to Move.GROWL, 6 to Move.VINE_WHIP, 9 to Move.SLEEP_POWDER, 13 to Move.MEGA_DRAIN),
            16, 6, "SEEDLING", "THE LEAVES ON ITS HEAD SOAK UP SUNLIGHT. IT NAPS IN SUNNY FIELDS."),
        Species(6, "THORNOX", listOf(Type.GRASS), 84, 86, 86, 72, 45, 160,
            listOf(1 to Move.TACKLE, 1 to Move.VINE_WHIP, 9 to Move.SLEEP_POWDER, 13 to Move.MEGA_DRAIN,
                18 to Move.RAZOR_LEAF, 24 to Move.GROWTH, 30 to Move.PETAL_BLADE, 36 to Move.BODY_SLAM),
            kind = "BRAMBLE", dex = "A FLOWER BLOOMS ON ITS BACK. ITS SWEET SCENT CALMS ANGRY FOES."),
        Species(7, "PIPWING", listOf(Type.NORMAL, Type.FLYING), 40, 45, 40, 58, 255, 50,
            listOf(1 to Move.TACKLE, 1 to Move.GUST, 5 to Move.QUICK_STRIKE, 12 to Move.WING_ATTACK, 16 to Move.AGILITY),
            18, 8, "TINY BIRD", "IT CHIRPS AT DAWN TO WAKE THE TOWN. IT IS BRAVE FOR ITS SIZE."),
        Species(8, "GALEHAWK", listOf(Type.NORMAL, Type.FLYING), 76, 80, 70, 98, 90, 150,
            listOf(1 to Move.GUST, 5 to Move.QUICK_STRIKE, 12 to Move.WING_ATTACK, 16 to Move.AGILITY,
                24 to Move.HEADBUTT, 30 to Move.SKY_DIVE),
            kind = "STORM", dex = "IT SOARS ON STORM WINDS AND DIVES FROM THE CLOUDS AT GREAT SPEED."),
        Species(9, "NIBBIT", listOf(Type.NORMAL), 32, 56, 35, 72, 255, 50,
            listOf(1 to Move.TACKLE, 1 to Move.LEER, 5 to Move.QUICK_STRIKE, 14 to Move.HYPER_FANG),
            20, 10, "MOUSE", "IT GNAWS ON ANYTHING. ITS FRONT TEETH NEVER STOP GROWING."),
        Species(10, "GNAWLER", listOf(Type.NORMAL), 58, 82, 60, 97, 90, 140,
            listOf(1 to Move.TACKLE, 5 to Move.QUICK_STRIKE, 14 to Move.HYPER_FANG, 24 to Move.HEADBUTT, 30 to Move.BODY_SLAM),
            kind = "MOUSE", dex = "ITS TEETH CAN CHEW THROUGH BRICK WALLS. IT HOARDS SHINY THINGS."),
        Species(11, "ZAPPUP", listOf(Type.ELECTRIC), 38, 55, 34, 90, 120, 70,
            listOf(1 to Move.THUNDER_SHOCK, 1 to Move.GROWL, 8 to Move.QUICK_STRIKE, 12 to Move.THUNDER_WAVE, 16 to Move.SPARK),
            22, 12, "SPARK PUP", "ITS BOLT-SHAPED EARS CRACKLE WHEN IT IS EXCITED. DON'T PET IT!"),
        Species(12, "VOLTHOUND", listOf(Type.ELECTRIC), 64, 88, 58, 112, 60, 165,
            listOf(1 to Move.THUNDER_SHOCK, 8 to Move.QUICK_STRIKE, 12 to Move.THUNDER_WAVE, 16 to Move.SPARK,
                26 to Move.AGILITY, 30 to Move.THUNDERBOLT, 36 to Move.HYPER_FANG),
            kind = "THUNDER", dex = "IT RUNS AS FAST AS LIGHTNING. ITS HOWL CAN BE HEARD MILES AWAY."),
        Species(13, "PEBBLIT", listOf(Type.ROCK), 42, 78, 98, 22, 200, 60,
            listOf(1 to Move.TACKLE, 1 to Move.HARDEN, 7 to Move.ROCK_THROW, 14 to Move.HEADBUTT, 20 to Move.ROCK_SLIDE),
            25, 14, "PEBBLE", "IT LOOKS LIKE AN ORDINARY ROCK. HIKERS OFTEN TRIP OVER IT."),
        Species(14, "BOULDRON", listOf(Type.ROCK), 82, 108, 128, 45, 60, 170,
            listOf(1 to Move.TACKLE, 1 to Move.HARDEN, 7 to Move.ROCK_THROW, 14 to Move.HEADBUTT,
                20 to Move.ROCK_SLIDE, 30 to Move.BODY_SLAM, 36 to Move.STONE_EDGE),
            kind = "BOULDER", dex = "IT CAN LIFT A TRUCK WITH ONE ARM. IT SLEEPS FOR WEEKS AT A TIME."),
        Species(15, "GRUBBLE", listOf(Type.BUG), 45, 35, 38, 45, 255, 40,
            listOf(1 to Move.TACKLE, 1 to Move.STRING_SHOT, 5 to Move.POISON_STING, 8 to Move.BUG_BITE),
            10, 16, "WORM", "IT MUNCHES LEAVES FROM DAWN TILL DUSK, THEN SPINS A COZY COCOON."),
        Species(16, "LUMOTH", listOf(Type.BUG, Type.FLYING), 62, 55, 52, 82, 120, 130,
            listOf(1 to Move.TACKLE, 1 to Move.POISON_STING, 8 to Move.BUG_BITE, 10 to Move.GUST,
                13 to Move.SLEEP_POWDER, 18 to Move.SIGNAL_BEAM, 26 to Move.WING_ATTACK),
            kind = "MOTH", dex = "ITS WINGS GLOW SOFTLY AT NIGHT. THE DUST THEY SHED MAKES FOES SLEEPY."),
        Species(17, "SPOOKIT", listOf(Type.GHOST), 40, 50, 45, 80, 150, 70,
            listOf(1 to Move.LICK, 1 to Move.LEER, 10 to Move.SHADOW_SNEAK, 15 to Move.HYPNOSIS, 22 to Move.SHADOW_BALL),
            26, 18, "SHEET", "IT HIDES IN OLD FORESTS AND GIGGLES AT LOST TRAVELERS."),
        Species(18, "GLOOMBRA", listOf(Type.GHOST), 66, 82, 66, 104, 60, 160,
            listOf(1 to Move.LICK, 10 to Move.SHADOW_SNEAK, 15 to Move.HYPNOSIS, 22 to Move.SHADOW_BALL,
                30 to Move.BODY_SLAM),
            kind = "SHADOW", dex = "IT SLIPS THROUGH WALLS AT NIGHT. ITS CROWN GLOWS WITH COLD FIRE."),
        Species(19, "FINNOW", listOf(Type.WATER), 42, 58, 42, 70, 200, 60,
            listOf(1 to Move.WATER_GUN, 1 to Move.TACKLE, 9 to Move.QUICK_STRIKE, 15 to Move.BUBBLE_BEAM),
            20, 20, "MINNOW", "IT SWIMS IN BIG SCHOOLS. ITS SHINY SCALES DAZZLE HUNGRY BIRDS."),
        Species(20, "SHARKLE", listOf(Type.WATER), 74, 98, 68, 88, 60, 160,
            listOf(1 to Move.WATER_GUN, 9 to Move.QUICK_STRIKE, 15 to Move.BUBBLE_BEAM, 20 to Move.HYPER_FANG,
                28 to Move.WAVE_CRASH),
            kind = "SHARK", dex = "IT SMELLS PREY FROM FAR AWAY. ITS TEETH GROW BACK IN A DAY."),
        Species(21, "SHROOMP", listOf(Type.GRASS), 54, 60, 58, 32, 190, 70,
            listOf(1 to Move.TACKLE, 1 to Move.GROWTH, 8 to Move.MEGA_DRAIN, 12 to Move.SLEEP_POWDER, 18 to Move.RAZOR_LEAF,
                26 to Move.PETAL_BLADE),
            kind = "MUSHROOM", dex = "IT HOPS ON ITS STUBBY FEET. RAIN MAKES ITS SPOTTED CAP GROW."),
        Species(22, "SOLARIS", listOf(Type.FIRE, Type.FLYING), 100, 105, 90, 105, 10, 255,
            listOf(1 to Move.FLAME_WHEEL, 1 to Move.WING_ATTACK, 30 to Move.AGILITY, 34 to Move.FLAMETHROWER,
                38 to Move.SKY_DIVE, 42 to Move.SOLAR_FLARE),
            kind = "SUN", dex = "A LEGEND SAYS IT CARRIES THE SUN ACROSS THE SKY EACH MORNING."),
    )

    operator fun get(id: Int): Species = all[id - 1]

    fun byName(name: String): Species = all.first { it.name == name }
}

enum class Item(val label: String, val price: Int, val desc: String, val ball: Float = 0f) {
    POTION("POTION", 300, "HEALS 20 HP."),
    SUPER_POTION("SUPER POTION", 700, "HEALS 60 HP."),
    FULL_HEAL("FULL HEAL", 500, "CURES ANY STATUS."),
    REVIVE("REVIVE", 1500, "REVIVES A FAINTED CRITTER."),
    CAPSULE("CAPSULE", 200, "CATCHES WILD CRITTERS.", 1f),
    SUPER_CAPSULE("SUPER CAPSULE", 600, "A BETTER CAPSULE.", 1.5f),
    ;

    val isBall get() = ball > 0f
}
