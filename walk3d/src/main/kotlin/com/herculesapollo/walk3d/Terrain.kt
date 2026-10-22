package com.herculesapollo.walk3d

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The ground: flat where you play, rising into hills beyond the hedges, with the grass
 * (or sand) colored in soft patches. Paths are laid on top with darker edges.
 */
object Terrain {
    /** How far the ground reaches past the edge of the play area. */
    const val SKIRT = 34f
    private const val CELL = 2f

    private fun hash(x: Int, z: Int): Float {
        var h = x * 374761393 + z * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777215f
    }

    /** Smooth value noise, 0..1. */
    fun noise(x: Float, z: Float): Float {
        val x0 = floor(x).toInt(); val z0 = floor(z).toInt()
        val fx = x - x0; val fz = z - z0
        val sx = fx * fx * (3 - 2 * fx); val sz = fz * fz * (3 - 2 * fz)
        val a = lerp(hash(x0, z0), hash(x0 + 1, z0), sx)
        val b = lerp(hash(x0, z0 + 1), hash(x0 + 1, z0 + 1), sx)
        return lerp(a, b, sz)
    }

    private fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

    /** Ground height at (x, z): 0 inside the play area, hills (or the sea floor at the beach) outside. */
    fun height(level: Level, x: Float, z: Float): Float {
        val edge = level.half + 1.5f
        if (level.def.look == Look.BEACH && z > edge) return -0.4f * smooth((z - edge) / 3f)
        val d = max(abs(x), abs(z)) - edge
        if (d <= 0f) return 0f
        val rise = smooth(d / 14f)
        return rise * (2.5f + d * 0.35f + noise(x * 0.09f + 5f, z * 0.09f) * 6f)
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(s: Int) = ((a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * k).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The ground color at (x, z): soft patches of lighter and darker grass, or sand. */
    fun color(level: Level, x: Float, z: Float, h: Float): Int {
        val look = level.def.look
        val big = noise(x * 0.11f, z * 0.11f)
        val fine = noise(x * 0.45f + 31f, z * 0.45f - 17f)
        var c = Models.shade(look.ground, 0.86f + big * 0.18f + fine * 0.1f)
        when (look) {
            Look.BEACH -> {
                // Damp, darker sand by the water; grassy dunes up the back.
                if (z > level.half - 4f) c = mix(c, 0xFFD6B47A.toInt(), smooth((z - level.half + 4f) / 4f))
                if (h > 0.5f) c = mix(c, 0xFF9DBB5C.toInt(), smooth((h - 0.5f) / 2f) * 0.8f)
            }
            Look.NIGHT -> {
                c = mix(c, 0xFF35683F.toInt(), smooth((big - 0.6f) * 4f) * 0.6f)
                c = mix(c, 0xFF1C3A26.toInt(), smooth((0.35f - fine) * 4f) * 0.5f)
            }
            else -> {
                c = mix(c, 0xFF9CCF52.toInt(), smooth((big - 0.6f) * 4f) * 0.55f)
                c = mix(c, 0xFF3F8C38.toInt(), smooth((0.35f - fine) * 4f) * 0.45f)
                if (h > 3f) c = mix(c, 0xFF6FA84A.toInt(), smooth((h - 3f) / 5f) * 0.5f)
            }
        }
        return c
    }

    /** The ground itself. Paths and water go in [buildDecals]. */
    fun build(level: Level): Mesh {
        val b = MeshBatch()
        val extent = level.half + SKIRT
        val n = (2 * extent / CELL).toInt()
        val x0 = -extent
        fun hx(x: Float, z: Float) = height(level, x, z)
        fun vert(x: Float, z: Float) {
            val h = hx(x, z)
            val e = 0.5f
            val nx = hx(x - e, z) - hx(x + e, z)
            val nz = hx(x, z - e) - hx(x, z + e)
            val ny = 2f * e
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            b.vertex(x, h, z, nx / l, ny / l, nz / l, color(level, x, z, h))
        }
        for (i in 0 until n) for (j in 0 until n) {
            val ax = x0 + i * CELL; val az = x0 + j * CELL
            val bx = ax + CELL; val bz = az + CELL
            // Counter-clockwise seen from above.
            vert(ax, az); vert(bx, bz); vert(bx, az)
            vert(ax, az); vert(ax, bz); vert(bx, bz)
        }
        return b.build(ground = true)
    }

    /** Paths and water, lying flat on the ground. */
    fun buildDecals(level: Level): Mesh {
        val b = MeshBatch()
        for (p in level.props) if (p.type == PropType.PATH) path(b, level, p)
        for (p in level.props) if (p.type == PropType.WATER) water(b, p)
        return b.build(decal = true)
    }

    private fun quad(b: MeshBatch, x0: Float, z0: Float, x1: Float, z1: Float, y: Float, c: Int) {
        b.vertex(x0, y, z0, 0f, 1f, 0f, c); b.vertex(x1, y, z1, 0f, 1f, 0f, c); b.vertex(x1, y, z0, 0f, 1f, 0f, c)
        b.vertex(x0, y, z0, 0f, 1f, 0f, c); b.vertex(x0, y, z1, 0f, 1f, 0f, c); b.vertex(x1, y, z1, 0f, 1f, 0f, c)
    }

    /** A path in 1 m tiles of slightly varying color, with a darker border. */
    private fun path(b: MeshBatch, level: Level, p: Prop) {
        val base = level.def.look.path
        val x0 = p.x - p.w / 2; val x1 = p.x + p.w / 2
        val z0 = p.z - p.d / 2; val z1 = p.z + p.d / 2
        val edge = 0.18f
        quad(b, x0 - edge, z0 - edge, x1 + edge, z1 + edge, 0.02f, Models.shade(base, 0.72f))
        val nx = max(1, (p.w / 1f).toInt()); val nz = max(1, (p.d / 1f).toInt())
        for (i in 0 until nx) for (j in 0 until nz) {
            val ax = x0 + (x1 - x0) * i / nx; val bx = x0 + (x1 - x0) * (i + 1) / nx
            val az = z0 + (z1 - z0) * j / nz; val bz = z0 + (z1 - z0) * (j + 1) / nz
            val k = 0.92f + noise(ax * 0.7f + 3f, az * 0.7f) * 0.14f
            quad(b, ax, az, bx, bz, 0.03f, Models.shade(base, k))
        }
        // The road on Maple Street gets a dashed center line.
        if (level.def.look == Look.STREET && p.d >= 4f) {
            var x = x0 + 0.5f
            while (x < x1 - 1f) {
                quad(b, x, p.z - 0.06f, x + 1.2f, p.z + 0.06f, 0.04f, 0xFFF2D35B.toInt())
                x += 2.4f
            }
        }
    }

    private fun water(b: MeshBatch, p: Prop) {
        quad(b, p.x - p.w / 2, p.z - p.d / 2, p.x + p.w / 2, p.z + p.d / 2, 0.04f, 0xFF2A8FD8.toInt())
        quad(b, p.x - p.w / 2, p.z - p.d / 2 + 6f, p.x + p.w / 2, p.z + p.d / 2, 0.06f, 0xFF1F78C0.toInt())
    }
}
