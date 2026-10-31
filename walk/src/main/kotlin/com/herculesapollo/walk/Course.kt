package com.herculesapollo.walk

const val VIEW_H = 240f

/** Feet line for everyone on the path. */
const val GROUND = 196f

enum class Theme(
    val skyTop: Int, val skyBottom: Int, val hills: Int, val grass: Int, val path: Int, val pathEdge: Int,
    val tune: WalkTune,
) {
    PARK(0xFF6FB6FF.toInt(), 0xFFC8E8FF.toInt(), 0xFF7CC46A.toInt(), 0xFF4CB531.toInt(), 0xFFD8C8A8.toInt(), 0xFFB09E7C.toInt(), WalkTune.DAY),
    STREET(0xFF8EC9FF.toInt(), 0xFFE4F4FF.toInt(), 0xFF9CCB86.toInt(), 0xFF5DBB4A.toInt(), 0xFFCACACA.toInt(), 0xFF9A9A9A.toInt(), WalkTune.DAY),
    MEADOW(0xFF9AD0FF.toInt(), 0xFFFFF1C9.toInt(), 0xFFB5D96A.toInt(), 0xFF8CC63F.toInt(), 0xFFC9A26B.toInt(), 0xFF9C7A48.toInt(), WalkTune.DAY),
    BEACH(0xFF5EC8FF.toInt(), 0xFFD2F4FF.toInt(), 0xFF2A8FD8.toInt(), 0xFFF2D49B.toInt(), 0xFFB98A5A.toInt(), 0xFF8A6038.toInt(), WalkTune.DAY),
    NIGHT(0xFF0D1B3D.toInt(), 0xFF2B3A6B.toInt(), 0xFF1E2E4F.toInt(), 0xFF1F4A2E.toInt(), 0xFF6E6A78.toInt(), 0xFF4A4654.toInt(), WalkTune.NIGHT),
    /** Hercules and Apollo's own backyard: stucco houses, a white vinyl fence, the pool and the lawn. */
    BACKYARD(0xFF7EC3F2.toInt(), 0xFFE6F3FA.toInt(), 0xFFE9E4D8.toInt(), 0xFF6DB33F.toInt(), 0xFFC9C6C0.toInt(), 0xFFA9A6A0.toInt(), WalkTune.DAY),
}

enum class Kind(val points: Int, val label: String) {
    SQUIRREL(100, "squirrel"),
    PIGEON(50, "pigeon"),
    GULL(50, "seagull"),
    RABBIT(150, "bunny"),
    CAT(200, "cat"),
    RACCOON(250, "raccoon"),
    CRAB(100, "crab"),
    /** Florida anoles: dart off and up the fence, where only a high jumper can reach. */
    LIZARD(150, "lizard"),
    /** Hops off in big arcs for the pond. */
    FROG(100, "frog"),
    /** Don't chase the skunk! */
    SKUNK(0, "skunk"),
}

enum class Mood {
    /** Minding its own business. */
    IDLE,
    /** Running for safety (or hissing, for a cat). */
    FLEE,
    /** Got away: up a tree, into a bush, a hole or a bin. */
    SAFE,
    /** A dog caught up with it; it's running off the screen. */
    CHASED,
    GONE,
}

/** A critter the dogs can chase. [x] is the center, [y] the feet. */
class Critter(val kind: Kind, var x: Float, var y: Float, val home: Float) {
    var mood = Mood.IDLE
    var t = 0f
    var vx = 0f
    var vy = 0f
    var facing = 1
    /** Squirrels: true while climbing the trunk. */
    var climbing = false
    /** Hidden in a bush, a hole or a bin. */
    var hidden = false
    /** Seconds left frozen in surprise after Hercules' big bark. */
    var frozenT = 0f
    val startX = x

    val w get() = when (kind) {
        Kind.SQUIRREL -> if (climbing) 8f else 12f
        Kind.PIGEON, Kind.GULL -> 10f
        Kind.RABBIT -> 12f
        Kind.CAT -> 12f
        Kind.RACCOON -> 14f
        Kind.CRAB -> 9f
        Kind.LIZARD -> if (climbing) 6f else 14f
        Kind.FROG -> 10f
        Kind.SKUNK -> 15f
    }
    val h get() = when (kind) {
        Kind.SQUIRREL -> if (climbing) 12f else 10f
        Kind.PIGEON, Kind.GULL -> 7f
        Kind.RABBIT -> 9f
        Kind.CAT -> 11f
        Kind.RACCOON -> 9f
        Kind.CRAB -> 6f
        Kind.LIZARD -> if (climbing) 13f else 6f
        Kind.FROG -> 7f
        Kind.SKUNK -> 9f
    }

    /** Still out in the open, so a dog can catch it. */
    val catchable get() = (mood == Mood.IDLE || mood == Mood.FLEE) && !hidden
    val counts get() = kind != Kind.SKUNK
}

enum class PropKind { POND, COT, COUCH, TOYCAR, PALM, POOL, TREE, FENCE, BIN, BUSH, BENCH, HYDRANT, LAMP, FLOWERS, HOLE, UMBRELLA, SANDCASTLE, MAILBOX, SIGN, HOME }

class Prop(val kind: PropKind, val x: Float, val w: Float = 0f)

/** A bike that rings its bell and rides along the path toward the walker. Jump over it! */
class Bike(var x: Float) {
    var active = false
    var hit = false
    var t = 0f
}

class Treat(val x: Float, val y: Float) {
    var taken = false
}

/** One walk's layout: scenery, critters, bikes and treats, built fresh each time it starts. */
class Course(val walk: WalkDef) {
    val props = ArrayList<Prop>()
    val critters = ArrayList<Critter>()
    val bikes = ArrayList<Bike>()
    val treats = ArrayList<Treat>()
    val homeX = walk.length - 90f

    /** Height of the top of a fence, which cats sit on. */
    val fenceTop = GROUND - 44f
    /** Height of the lid of a bin, which raccoons sit on. */
    val binTop = GROUND - 38f

    fun prop(kind: PropKind, x: Float, w: Float = 0f) { props += Prop(kind, x, w) }

    /** A tree with a squirrel foraging beside it. */
    fun squirrel(x: Float) {
        prop(PropKind.TREE, x)
        critters += Critter(Kind.SQUIRREL, x - 34f, GROUND, x)
    }

    /** A flock of pigeons (or seagulls at the beach). */
    fun birds(x: Float, n: Int, kind: Kind = Kind.PIGEON) {
        for (i in 0 until n) critters += Critter(kind, x + i * 14f + (i % 2) * 5f, GROUND, x).also { it.t = i * 0.37f }
    }

    /** A bunny that bolts for the bush up ahead. */
    fun rabbit(x: Float) {
        critters += Critter(Kind.RABBIT, x, GROUND, x + 230f)
        prop(PropKind.BUSH, x + 230f)
    }

    /** A cat sitting on a fence, too high for Apollo. */
    fun cat(x: Float) {
        prop(PropKind.FENCE, x - 34f, 68f)
        critters += Critter(Kind.CAT, x, fenceTop, x)
    }

    /** A raccoon on a bin. It ducks inside if you're slow. */
    fun raccoon(x: Float) {
        prop(PropKind.BIN, x)
        critters += Critter(Kind.RACCOON, x, binTop, x)
    }

    /** A crab that scuttles back to its hole. */
    fun crab(x: Float) {
        prop(PropKind.HOLE, x + 46f)
        critters += Critter(Kind.CRAB, x, GROUND, x + 46f)
    }

    fun skunk(x: Float) { critters += Critter(Kind.SKUNK, x, GROUND, x) }

    /** A lizard basking on the path; it darts to the fence behind and climbs up out of reach. */
    fun lizard(x: Float) { critters += Critter(Kind.LIZARD, x, GROUND, x + 36f) }

    /** A frog that hops off for the little pond up ahead. */
    fun frog(x: Float) {
        prop(PropKind.POND, x + 80f)
        critters += Critter(Kind.FROG, x, GROUND, x + 80f)
    }

    fun bike(x: Float) { bikes += Bike(x) }

    /** A row of [n] floating treats, [up] pixels above the path. */
    fun treats(x: Float, n: Int, up: Float = 12f) {
        for (i in 0 until n) treats += Treat(x + i * 16f, GROUND - up - (if (up > 20f) (i % 2) * 6f else 0f))
    }

    fun repeatEvery(from: Float, step: Float, count: Int, f: (Float) -> Unit) {
        for (i in 0 until count) f(from + i * step)
    }

    init {
        walk.build(this)
        prop(PropKind.HOME, homeX)
        props.sortBy { if (it.kind == PropKind.HOLE) -1f else it.x }
    }

    val total get() = critters.count { it.counts }
}

class WalkDef(val title: String, val theme: Theme, val length: Float, val build: Course.() -> Unit)

object Walks {
    val all = listOf(
        WalkDef("Morning in the Park", Theme.PARK, 2500f) {
            repeatEvery(320f, 460f, 5) { squirrel(it) }
            birds(520f, 3); birds(990f, 4); birds(1460f, 3); birds(1930f, 5)
            prop(PropKind.BENCH, 640f); prop(PropKind.BENCH, 1600f)
            repeatEvery(200f, 400f, 6) { prop(PropKind.LAMP, it) }
            prop(PropKind.FLOWERS, 860f); prop(PropKind.FLOWERS, 1320f); prop(PropKind.FLOWERS, 2100f)
            bike(1300f); bike(2050f)
            treats(160f, 4); treats(760f, 3, 34f); treats(1180f, 4); treats(1720f, 3, 34f); treats(2250f, 5)
        },
        WalkDef("Maple Street", Theme.STREET, 2700f) {
            cat(420f); cat(1130f); cat(1840f); cat(2380f)
            squirrel(720f); squirrel(1530f); squirrel(2160f)
            birds(1320f, 4)
            repeatEvery(260f, 520f, 5) { prop(PropKind.HYDRANT, it) }
            prop(PropKind.MAILBOX, 600f); prop(PropKind.MAILBOX, 1700f)
            bike(950f); bike(1620f); bike(2000f)
            treats(200f, 3); treats(880f, 4, 34f); treats(1400f, 3); treats(2050f, 4); treats(2500f, 3, 34f)
        },
        WalkDef("Meadow Trail", Theme.MEADOW, 2800f) {
            rabbit(330f); rabbit(930f); rabbit(1520f); rabbit(2120f)
            squirrel(700f); squirrel(1300f); squirrel(1880f)
            skunk(1150f); skunk(2450f)
            repeatEvery(160f, 300f, 9) { prop(PropKind.FLOWERS, it) }
            prop(PropKind.SIGN, 120f)
            bike(820f); bike(1720f); bike(2300f)
            treats(250f, 3); treats(1050f, 3, 34f); treats(1640f, 4); treats(2350f, 3, 34f)
        },
        WalkDef("Sunny Beach", Theme.BEACH, 2800f) {
            birds(300f, 4, Kind.GULL); birds(1050f, 5, Kind.GULL); birds(1760f, 4, Kind.GULL); birds(2400f, 3, Kind.GULL)
            crab(560f); crab(1250f); crab(1520f); crab(2080f)
            repeatEvery(200f, 480f, 6) { prop(PropKind.UMBRELLA, it) }
            prop(PropKind.SANDCASTLE, 760f); prop(PropKind.SANDCASTLE, 1900f)
            bike(880f); bike(1950f)
            treats(180f, 3); treats(700f, 4, 34f); treats(1380f, 3); treats(2200f, 5)
        },
        WalkDef("Moonlight Walk", Theme.NIGHT, 2900f) {
            raccoon(420f); raccoon(1180f); raccoon(1950f); raccoon(2560f)
            cat(780f); cat(1580f)
            skunk(1000f); skunk(1780f); skunk(2280f)
            squirrel(2420f)
            repeatEvery(150f, 300f, 9) { prop(PropKind.LAMP, it) }
            bike(1380f); bike(2150f)
            treats(260f, 4); treats(900f, 3, 34f); treats(1450f, 4); treats(2050f, 3, 34f); treats(2650f, 3)
        },
        WalkDef("Our Backyard", Theme.BACKYARD, 2700f) {
            // The patio: the wicker loveseat and the dog cot.
            prop(PropKind.COUCH, 220f)
            prop(PropKind.COT, 560f)
            prop(PropKind.PALM, 420f); prop(PropKind.PALM, 1300f); prop(PropKind.PALM, 2150f)
            prop(PropKind.TOYCAR, 1180f)
            prop(PropKind.POOL, 1560f, 380f)
            lizard(340f); lizard(760f); lizard(1060f); lizard(1450f); lizard(2050f); lizard(2400f)
            frog(1580f); frog(1760f)
            squirrel(980f); squirrel(2280f)
            birds(700f, 3)
            cat(2520f)
            repeatEvery(150f, 450f, 6) { prop(PropKind.FLOWERS, it) }
            treats(300f, 3); treats(820f, 3, 34f); treats(1400f, 4); treats(1980f, 3, 34f); treats(2480f, 3)
        },
    )
}
