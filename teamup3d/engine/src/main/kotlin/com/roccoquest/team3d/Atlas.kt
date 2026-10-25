package com.roccoquest.team3d

import com.roccoquest.game.Sprite
import java.util.IdentityHashMap

/**
 * One texture that collects the game's tile pictures as they are first drawn, so 3D
 * blocks can wear the same pixel art as the 2D game. Each picture gets a 1-pixel border
 * copied from its edge, so filtering never bleeds in a neighbour.
 */
object Atlas {
    const val SIZE = 1024

    /** ARGB pixels, row-major, top row first. */
    val pixels = IntArray(SIZE * SIZE)
    /** Goes up whenever [pixels] change, so the GPU copy can be refreshed. */
    @Volatile var version = 0
        private set
    /** Goes up when the atlas is wiped (it was full); regions from before are no longer valid. */
    var epoch = 0
        private set

    private val regions = IdentityHashMap<Sprite, Region>()
    private var penX = 0
    private var penY = 0
    private var rowH = 0

    /** A plain white patch, for untextured shapes. */
    val white: Region = Region(1f / SIZE, 1f / SIZE, 3f / SIZE, 3f / SIZE)

    init { reset() }

    private fun reset() {
        pixels.fill(0)
        regions.clear()
        for (y in 0 until 4) for (x in 0 until 4) pixels[y * SIZE + x] = -1
        penX = 4; penY = 0; rowH = 4
        version++
    }

    fun region(s: Sprite): Region = regions[s] ?: add(s)

    private fun add(s: Sprite): Region {
        val w = s.w + 2
        val h = s.h + 2
        if (penX + w > SIZE) { penX = 0; penY += rowH; rowH = 0 }
        if (penY + h > SIZE) { reset(); epoch++ }
        val ox = penX; val oy = penY
        for (y in 0 until h) for (x in 0 until w) {
            val sx = (x - 1).coerceIn(0, s.w - 1)
            val sy = (y - 1).coerceIn(0, s.h - 1)
            pixels[(oy + y) * SIZE + ox + x] = s.pixels[sy * s.w + sx]
        }
        penX += w
        rowH = maxOf(rowH, h)
        version++
        val r = Region((ox + 1f) / SIZE, (oy + 1f) / SIZE, (ox + 1f + s.w) / SIZE, (oy + 1f + s.h) / SIZE)
        regions[s] = r
        return r
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * k).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun shade(c: Int, f: Float): Int {
        fun ch(s: Int) = ((c shr s and 255) * f).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
