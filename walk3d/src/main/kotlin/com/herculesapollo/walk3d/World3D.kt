package com.herculesapollo.walk3d

import com.herculesapollo.walk.Kind
import com.herculesapollo.walk.WalkTune

/** Colors and lighting for a level. */
enum class Look(
    val skyTop: Int, val fog: Int, val ground: Int, val path: Int,
    val ambient: Float, val sun: Float, val night: Boolean, val tune: WalkTune,
) {
    PARK(0xFF5FA8F5.toInt(), 0xFFCDE9FF.toInt(), 0xFF5DBB4A.toInt(), 0xFFD8C8A8.toInt(), 0.62f, 0.5f, false, WalkTune.DAY),
    STREET(0xFF7FC0FF.toInt(), 0xFFE4F4FF.toInt(), 0xFF66C152.toInt(), 0xFF8A8A92.toInt(), 0.62f, 0.5f, false, WalkTune.DAY),
    BEACH(0xFF4FC0FF.toInt(), 0xFFD2F4FF.toInt(), 0xFFF2D49B.toInt(), 0xFFB98A5A.toInt(), 0.66f, 0.45f, false, WalkTune.DAY),
    NIGHT(0xFF0A1530.toInt(), 0xFF1B2850.toInt(), 0xFF2A5A38.toInt(), 0xFF5A5664.toInt(), 0.5f, 0.3f, true, WalkTune.NIGHT),
}

enum class PropType { TREE, BUSH, BENCH, FENCE, BIN, HOUSE, HOME, LAMP, UMBRELLA, SANDCASTLE, HYDRANT, FLOWERS, ROCK, HOLE, HEDGE, WATER, PATH }

/**
 * A piece of scenery at ([x], [z]). [w] and [d] are its size along x and z where that
 * matters (fences, hedges, paths, houses); [color] varies houses.
 */
class Prop(val type: PropType, val x: Float, val z: Float, val w: Float = 0f, val d: Float = 0f, val color: Int = 0)

/** A solid, axis-aligned box from the ground up to [top]: it blocks you and you can stand on it. */
class Solid(val x0: Float, val x1: Float, val z0: Float, val z1: Float, val top: Float, val bouncy: Boolean = false)

/** Where a critter starts, and its hiding place or perch. */
class Spawn(val kind: Kind, val x: Float, val z: Float, val hx: Float = x, val hz: Float = z, val perch: Float = 0f)

/** One 3D level, built fresh each time it starts. [half] is how far the play area reaches from the middle. */
class Level(val def: LevelDef) {
    val props = ArrayList<Prop>()
    val solids = ArrayList<Solid>()
    val spawns = ArrayList<Spawn>()
    val treats = ArrayList<FloatArray>()
    /** The loop the bikes ride around. */
    val bikeLoop = ArrayList<FloatArray>()
    var bikes = 0
    var homeX = 0f
    var homeZ = 0f
    /** Where you start, and which way you face. */
    var startX = 0f
    var startZ = 0f
    var startYaw = 0f
    val half get() = def.half

    fun tree(x: Float, z: Float, squirrel: Boolean = true) {
        props += Prop(PropType.TREE, x, z)
        solids += Solid(x - 0.32f, x + 0.32f, z - 0.32f, z + 0.32f, 40f)
        if (squirrel) spawns += Spawn(Kind.SQUIRREL, x + 1.6f, z + 0.8f, x, z)
    }

    fun bush(x: Float, z: Float) { props += Prop(PropType.BUSH, x, z) }

    fun bench(x: Float, z: Float) {
        props += Prop(PropType.BENCH, x, z)
        solids += Solid(x - 0.9f, x + 0.9f, z - 0.3f, z + 0.3f, 0.5f)
    }

    /** A fence [len] long, running along x (or along z if [alongZ]), with a cat on top if [cat]. */
    fun fence(x: Float, z: Float, len: Float, alongZ: Boolean = false, cat: Boolean = false) {
        val w = if (alongZ) 0.2f else len
        val d = if (alongZ) len else 0.2f
        props += Prop(PropType.FENCE, x, z, w, d)
        solids += Solid(x - w / 2, x + w / 2, z - d / 2, z + d / 2, FENCE_TOP)
        if (cat) spawns += Spawn(Kind.CAT, x, z, perch = FENCE_TOP)
    }

    fun bin(x: Float, z: Float, raccoon: Boolean = true) {
        props += Prop(PropType.BIN, x, z)
        solids += Solid(x - 0.45f, x + 0.45f, z - 0.45f, z + 0.45f, BIN_TOP)
        if (raccoon) spawns += Spawn(Kind.RACCOON, x, z, perch = BIN_TOP)
    }

    fun house(x: Float, z: Float, w: Float, d: Float, color: Int) {
        props += Prop(PropType.HOUSE, x, z, w, d, color)
        solids += Solid(x - w / 2, x + w / 2, z - d / 2, z + d / 2, 40f)
    }

    /** Your house: the door faces +z. Go here once enough critters are chased. */
    fun home(x: Float, z: Float) {
        props += Prop(PropType.HOME, x, z, 6f, 5f)
        solids += Solid(x - 3f, x + 3f, z - 2.5f, z + 2.5f, 40f)
        homeX = x
        homeZ = z + 2.5f + 0.8f
    }

    fun lamp(x: Float, z: Float) {
        props += Prop(PropType.LAMP, x, z)
        solids += Solid(x - 0.12f, x + 0.12f, z - 0.12f, z + 0.12f, 40f)
    }

    fun umbrella(x: Float, z: Float, color: Int) {
        props += Prop(PropType.UMBRELLA, x, z, color = color)
        solids += Solid(x - 1.1f, x + 1.1f, z - 1.1f, z + 1.1f, UMBRELLA_TOP, bouncy = true)
    }

    fun sandcastle(x: Float, z: Float) {
        props += Prop(PropType.SANDCASTLE, x, z)
        solids += Solid(x - 0.8f, x + 0.8f, z - 0.8f, z + 0.8f, 0.7f)
    }

    fun hydrant(x: Float, z: Float) {
        props += Prop(PropType.HYDRANT, x, z)
        solids += Solid(x - 0.2f, x + 0.2f, z - 0.2f, z + 0.2f, 0.7f)
    }

    fun rock(x: Float, z: Float) {
        props += Prop(PropType.ROCK, x, z)
        solids += Solid(x - 0.7f, x + 0.7f, z - 0.7f, z + 0.7f, 0.6f)
    }

    fun flowers(x: Float, z: Float) { props += Prop(PropType.FLOWERS, x, z) }

    fun path(x: Float, z: Float, w: Float, d: Float) { props += Prop(PropType.PATH, x, z, w, d) }

    fun birds(x: Float, z: Float, n: Int, kind: Kind = Kind.PIGEON) {
        for (i in 0 until n) spawns += Spawn(kind, x + (i % 2) * 0.9f - 0.4f, z + (i / 2) * 0.8f)
    }

    /** A bunny, and the burrow it runs for. */
    fun rabbit(x: Float, z: Float) {
        spawns += Spawn(Kind.RABBIT, x, z, x + 6f, z - 4f)
        bush(x + 6f, z - 4f)
    }

    fun crab(x: Float, z: Float) {
        props += Prop(PropType.HOLE, x + 1.5f, z)
        spawns += Spawn(Kind.CRAB, x, z, x + 1.5f, z)
    }

    fun skunk(x: Float, z: Float) { spawns += Spawn(Kind.SKUNK, x, z) }

    fun treats(x0: Float, z0: Float, x1: Float, z1: Float, n: Int) {
        for (i in 0 until n) {
            val t = if (n == 1) 0.5f else i / (n - 1f)
            treats += floatArrayOf(lerp(x0, x1, t), 0.6f, lerp(z0, z1, t))
        }
    }

    fun bikeLoop(vararg pts: Float, count: Int) {
        for (i in pts.indices step 2) bikeLoop += floatArrayOf(pts[i], pts[i + 1])
        bikes = count
    }

    /** A hedge (or a wall of houses, at the beach the sea) all round the edge of the play area. */
    private fun border() {
        val h = half
        if (def.look == Look.BEACH) {
            props += Prop(PropType.WATER, 0f, h + 30f, 200f, 60f)
        }
        for ((x, z, w, d) in listOf(
            listOf(0f, -h - 0.5f, 2 * h + 2f, 1f), listOf(0f, h + 0.5f, 2 * h + 2f, 1f),
            listOf(-h - 0.5f, 0f, 1f, 2 * h + 2f), listOf(h + 0.5f, 0f, 1f, 2 * h + 2f),
        )) {
            if (def.look == Look.BEACH && z > 0f) continue
            props += Prop(PropType.HEDGE, x, z, w, d)
        }
    }

    init {
        def.build(this)
        border()
    }

    val total get() = spawns.count { it.kind != Kind.SKUNK }
    /** Critters to chase before heading home: all but a couple. */
    val target get() = (total - 2).coerceAtLeast(1)

    companion object {
        const val FENCE_TOP = 1.2f
        const val BIN_TOP = 1.05f
        const val UMBRELLA_TOP = 2.1f
    }
}

class LevelDef(val title: String, val look: Look, val half: Float, val par: Float, val build: Level.() -> Unit)

object Levels {
    val all = listOf(
        LevelDef("The Park", Look.PARK, 22f, 75f) {
            startX = 0f; startZ = 14f; startYaw = Math.PI.toFloat()
            home(0f, -19f)
            path(0f, 0f, 3f, 40f); path(0f, 0f, 40f, 3f)
            tree(-8f, -8f); tree(9f, -10f); tree(-12f, 7f); tree(11f, 9f); tree(-4f, -15f); tree(15f, -2f)
            tree(-17f, -3f, squirrel = false); tree(6f, 16f, squirrel = false); tree(-15f, 15f, squirrel = false)
            birds(-5f, 4f, 4); birds(6f, -3f, 4)
            rabbit(-14f, -12f); rabbit(13f, 14f)
            skunk(4f, 6f)
            bench(-3f, 9f); bench(4f, -8f); bench(-9f, 1.8f)
            lamp(2f, 5f); lamp(-2f, -5f); lamp(2f, -14f)
            flowers(-6f, 12f); flowers(8f, 3f); flowers(-11f, -6f); flowers(14f, -14f)
            rock(16f, 6f); rock(-18f, -16f)
            treats(0f, 12f, 0f, 3f, 5); treats(-14f, 0f, -4f, 0f, 5); treats(4f, -1f, 16f, -1f, 5)
            bikeLoop(-18f, -14f, 18f, -14f, 18f, 18f, -18f, 18f, count = 2)
        },
        LevelDef("Maple Street", Look.STREET, 22f, 60f) {
            startX = -16f; startZ = 0f; startYaw = (Math.PI / 2).toFloat()
            home(14f, -15f)
            path(0f, 0f, 44f, 5f)
            path(0f, -3.4f, 44f, 1.6f); path(0f, 3.4f, 44f, 1.6f)
            val colors = intArrayOf(0xFF8ECAE6.toInt(), 0xFFF4A261.toInt(), 0xFFE5989B.toInt(), 0xFFB5E48C.toInt(), 0xFFE9C46A.toInt())
            for (i in 0 until 3) house(-15f + i * 9f, -17f, 6f, 5f, colors[i % colors.size])
            for (i in 0 until 5) house(-17f + i * 8.5f, 17f, 6f, 5f, colors[(i + 2) % colors.size])
            fence(-12f, -9f, 6f, cat = true); fence(3f, -9f, 6f, cat = true)
            fence(-6f, 10f, 6f, cat = true); fence(10f, 10f, 6f, cat = true)
            fence(-19f, 10f, 5f, alongZ = true)
            bin(-3f, -6.5f); bin(8f, 6.5f); bin(18f, 6.5f)
            tree(-8f, 7.5f); tree(14f, -7.5f); tree(-17f, -7.5f)
            hydrant(-1f, 5f); hydrant(12f, -5f)
            skunk(5f, -12f); skunk(-12f, 13f)
            birds(0f, 0.5f, 3)
            treats(-14f, 0f, 14f, 0f, 9)
            bikeLoop(-20f, -1.2f, 20f, -1.2f, 20f, 1.2f, -20f, 1.2f, count = 3)
        },
        LevelDef("Sunny Beach", Look.BEACH, 22f, 80f) {
            startX = 0f; startZ = -16f; startYaw = 0f
            home(-14f, -19f)
            path(0f, -8f, 44f, 2.5f)
            birds(-8f, 4f, 4, Kind.GULL); birds(9f, 8f, 4, Kind.GULL); birds(2f, 14f, 3, Kind.GULL)
            crab(-14f, 10f); crab(-3f, 16f); crab(6f, 2f); crab(15f, 14f); crab(13f, -3f)
            rabbit(-16f, -2f); rabbit(10f, -14f)
            umbrella(-6f, 9f, 0xFFFF5D5D.toInt()); umbrella(4f, 6f, 0xFF4DA3FF.toInt()); umbrella(14f, 4f, 0xFFFFD166.toInt())
            umbrella(-12f, 16f, 0xFF06D6A0.toInt())
            sandcastle(-2f, 3f); sandcastle(10f, 15f)
            rock(-18f, 6f); rock(18f, -10f)
            treats(-16f, -8f, 16f, -8f, 9); treats(-6f, 11f, 4f, 8f, 4)
            bikeLoop(-20f, -8.6f, 20f, -8.6f, 20f, -7.4f, -20f, -7.4f, count = 2)
        },
        LevelDef("Moonlight Walk", Look.NIGHT, 22f, 60f) {
            startX = 0f; startZ = 16f; startYaw = Math.PI.toFloat()
            home(0f, -19f)
            path(0f, 0f, 3f, 40f); path(0f, 4f, 40f, 3f)
            val colors = intArrayOf(0xFF6D8BA8.toInt(), 0xFFA88B6D.toInt(), 0xFF8BA86D.toInt())
            house(-15f, -16f, 6f, 5f, colors[0]); house(14f, -16f, 6f, 5f, colors[1]); house(-15f, 17f, 6f, 5f, colors[2])
            bin(-6f, -6f); bin(7f, -4f); bin(-10f, 10f); bin(12f, 12f)
            fence(-12f, 0f, 6f, cat = true); fence(11f, 1f, 6f, cat = true); fence(6f, 14f, 6f, alongZ = true, cat = true)
            skunk(-5f, 4f); skunk(6f, 7f); skunk(-3f, -12f)
            tree(-16f, 4f); tree(16f, -6f)
            for ((x, z) in listOf(3f to 10f, -3f to 1f, 3f to -8f, -3f to -15f, 12f to 5f, -12f to 6f)) lamp(x, z)
            treats(0f, 14f, 0f, -12f, 9); treats(-16f, 4f, 16f, 4f, 7)
            bikeLoop(-19f, 4f, 19f, 4f, count = 1)
        },
    )
}
