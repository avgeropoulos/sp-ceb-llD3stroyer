package com.herculesapollo.walk3d

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Little touches scattered over each level: grass tufts, flowers and pebbles where you
 * play, and a ring of trees on the hills outside so the world doesn't just stop.
 * Always the same for a given level.
 */
object Decor {
    enum class Kind { TUFT, FLOWER, PEBBLE, TREE, PINE }

    fun interface Emit {
        fun emit(kind: Kind, x: Float, y: Float, z: Float, size: Float, yaw: Float, color: Int)
    }

    private val flowerColors = intArrayOf(
        0xFFFF6B9A.toInt(), 0xFFFFFFFF.toInt(), 0xFFB98CFF.toInt(), 0xFFFF9F43.toInt(), 0xFF6FC3FF.toInt(),
    )

    /** Is ([x], [z]) somewhere decorations shouldn't go: on a path, inside something, by the door? */
    private fun blocked(level: Level, x: Float, z: Float): Boolean {
        for (p in level.props) {
            if (p.type == PropType.PATH && abs(x - p.x) < p.w / 2 + 0.4f && abs(z - p.z) < p.d / 2 + 0.4f) return true
            if (p.type == PropType.WATER && z > p.z - p.d / 2 - 1.5f) return true
        }
        for (s in level.solids) if (x > s.x0 - 0.3f && x < s.x1 + 0.3f && z > s.z0 - 0.3f && z < s.z1 + 0.3f) return true
        return abs(x - level.homeX) < 2f && abs(z - level.homeZ) < 2f
    }

    fun scatter(level: Level, out: Emit) {
        val look = level.def.look
        val rnd = Random(level.def.title.hashCode())
        val h = level.half - 0.8f
        val beach = look == Look.BEACH

        // Grass tufts (dune grass at the beach, only up the back).
        repeat(if (beach) 160 else 520) {
            val x = rnd.nextFloat() * 2 * h - h
            val z = if (beach) -h + rnd.nextFloat() * 12f else rnd.nextFloat() * 2 * h - h
            if (blocked(level, x, z)) return@repeat
            val c = Models.shade(if (beach) 0xFF8FB04E.toInt() else look.ground, 0.95f + rnd.nextFloat() * 0.4f)
            out.emit(Kind.TUFT, x, 0f, z, 0.8f + rnd.nextFloat() * 0.8f, rnd.nextFloat() * 6.3f, c)
        }
        // Little flower patches.
        if (!beach) repeat(if (look.night) 30 else 70) {
            val cx = rnd.nextFloat() * 2 * h - h
            val cz = rnd.nextFloat() * 2 * h - h
            val color = flowerColors[rnd.nextInt(flowerColors.size)]
            repeat(3 + rnd.nextInt(4)) {
                val x = cx + rnd.nextFloat() * 1.2f - 0.6f
                val z = cz + rnd.nextFloat() * 1.2f - 0.6f
                if (!blocked(level, x, z)) out.emit(Kind.FLOWER, x, 0f, z, 0.8f + rnd.nextFloat() * 0.5f, rnd.nextFloat() * 6.3f, color)
            }
        }
        // Pebbles (and shells, at the beach).
        repeat(if (beach) 90 else 40) {
            val x = rnd.nextFloat() * 2 * h - h
            val z = rnd.nextFloat() * 2 * h - h
            if (blocked(level, x, z)) return@repeat
            val c = if (beach && rnd.nextInt(3) == 0) 0xFFFFE6EE.toInt() else Models.shade(0xFF9A948C.toInt(), 0.8f + rnd.nextFloat() * 0.4f)
            out.emit(Kind.PEBBLE, x, 0f, z, 0.6f + rnd.nextFloat() * 0.9f, rnd.nextFloat() * 6.3f, c)
        }
        // Trees on the hills beyond the edge.
        val treeGreen = if (look.night) 0xFF234A2E.toInt() else 0xFF3E8F3A.toInt()
        val pineGreen = if (look.night) 0xFF1C3D28.toInt() else 0xFF2F6E38.toInt()
        repeat(110) {
            val a = rnd.nextFloat() * 6.283f
            val r = level.half + 4f + rnd.nextFloat() * 20f
            // Push the ring out to a square so it hugs the hedges.
            val sq = max(abs(cos(a)), abs(sin(a)))
            val x = cos(a) * r / sq
            val z = sin(a) * r / sq
            if (abs(x) > level.half + Terrain.SKIRT - 4f || abs(z) > level.half + Terrain.SKIRT - 4f) return@repeat
            if (beach && z > level.half - 2f) return@repeat
            // Not inside the neighbours' houses.
            if (level.solids.any { x > it.x0 - 2.5f && x < it.x1 + 2.5f && z > it.z0 - 2.5f && z < it.z1 + 2.5f }) return@repeat
            val y = Terrain.height(level, x, z)
            val pine = rnd.nextInt(3) == 0
            val c = Models.shade(if (pine) pineGreen else treeGreen, 0.8f + rnd.nextFloat() * 0.35f)
            out.emit(if (pine) Kind.PINE else Kind.TREE, x, y, z, 0.8f + rnd.nextFloat() * 0.7f, 0f, c)
        }
    }
}
