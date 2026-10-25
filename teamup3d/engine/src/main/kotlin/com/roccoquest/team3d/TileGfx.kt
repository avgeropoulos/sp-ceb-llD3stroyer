package com.roccoquest.team3d

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.TILE
import com.roccoquest.team.Tile
import com.roccoquest.team.TeamGame
import java.util.IdentityHashMap
import kotlin.math.floor

/**
 * Receives the level's tiles exactly as Team Up! draws them and turns each one into 3D:
 * ground becomes a deep slab with a rounded grassy lip, blocks become bevelled cubes
 * wearing the same pixel art, clouds become fluffy puffs and lava glows.
 * Anything else drawn on a tile (cracks, rails, spikes) is passed on to [paper].
 */
class TileGfx(private val list: DrawList, private val paper: PaperGfx) : Gfx {
    private lateinit var game: TeamGame
    private var cam = 0f
    private val m = Mat4()
    private var cellX = Int.MIN_VALUE
    private var cellY = Int.MIN_VALUE

    private val cubes = IdentityHashMap<Region, Mesh>()
    private val slabs = IdentityHashMap<Region, Mesh>()
    private val tops = IdentityHashMap<Region, Mesh>()
    private var epoch = -1

    /**
     * Title screen: only the ground and blocks high in the sky are shown, so nothing in the
     * level ever walks through the team standing in front.
     */
    var titleMode = false

    fun begin(g: TeamGame) {
        game = g
        cam = g.worldCam
        cellX = Int.MIN_VALUE
        if (epoch != Atlas.epoch) {
            // The atlas was wiped, so meshes pointing into it are stale.
            for (map in listOf(cubes, slabs, tops)) { map.values.forEach { it.release() }; map.clear() }
            epoch = Atlas.epoch
        }
    }

    private fun isGround(t: Int) = t == Tile.GROUND || t == Tile.DIRT

    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) {
        val tx = floor((x + cam + 0.5f) / TILE).toInt()
        val ty = floor((y + TILE / 2f) / TILE).toInt()
        val level = game.level
        val t = level[tx, ty]
        if (titleMode && !isGround(t) && ty >= TITLE_CLEAR_ROW) return
        enterCell(tx, ty, if (isGround(t)) LevelDepth.FRONT + 0.3f else HALF + 0.3f)
        val cx = x + w / 2
        val cy = -(y + h / 2)
        when {
            isGround(t) -> {
                val r = Atlas.region(s)
                m.identity().translate(cx, cy, 0f)
                list.add(slabs.getOrPut(r) { slab(r) }, m).cutout = true
                if (!isGround(level[tx, ty - 1])) list.add(tops.getOrPut(r) { top(r) }, m).cutout = true
            }
            t == Tile.CLOUD -> cloud(cx, cy)
            t == Tile.LAVA -> {
                val r = Atlas.region(s)
                m.identity().translate(cx, cy, 0f)
                list.add(slabs.getOrPut(r) { slab(r) }, m, emissive = 0.85f).cutout = true
                if (level[tx, ty - 1] != Tile.LAVA) list.add(tops.getOrPut(r) { top(r) }, m, emissive = 0.85f).cutout = true
            }
            else -> {
                val r = Atlas.region(s)
                m.identity().translate(cx, cy, 0f)
                val shiny = t == Tile.QCOIN || t == Tile.QHEART || t == Tile.QSTAR || t == Tile.QMYSTERY
                list.add(cubes.getOrPut(r) { cube(r) }, m, emissive = if (shiny) 0.15f else 0f, shine = if (shiny) 0.5f else 0.12f).cutout = true
            }
        }
    }

    /** Starts a new tile; shapes drawn on it stack in front of its face at depth [z]. */
    private fun enterCell(tx: Int, ty: Int, z: Float) {
        if (tx == cellX && ty == cellY) return
        cellX = tx; cellY = ty
        paper.begin(z)
    }

    /** A ground tile at screen [x] in row [ty], used to fill in pits under the title screen's team. */
    fun fillGround(x: Float, ty: Int, s: Sprite, top: Boolean) {
        val r = Atlas.region(s)
        m.identity().translate(x + HALF, -(ty * TILE + HALF), 0f)
        list.add(slabs.getOrPut(r) { slab(r) }, m).cutout = true
        if (top) list.add(tops.getOrPut(r) { top(r) }, m).cutout = true
    }

    private fun shapeCell(x: Float, y: Float) {
        val tx = floor((x + cam) / TILE).toInt()
        val ty = floor(y / TILE).toInt()
        val t = game.level[tx, ty]
        enterCell(tx, ty, when {
            isGround(t) -> LevelDepth.FRONT + 0.3f
            Tile.solid(t) -> HALF + 0.3f
            else -> 0f
        })
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) { if (titleMode) return; shapeCell(x, y); paper.rect(x, y, w, h, color) }
    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) { if (titleMode) return; shapeCell(x, y); paper.oval(x, y, w, h, color) }
    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) { if (titleMode) return; shapeCell(xs[0], ys[0]); paper.poly(xs, ys, color) }
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) = paper.text(s, x, y, size, color, align)

    private fun cloud(cx: Float, cy: Float) {
        val white = 0xFFFFFFFF.toInt()
        m.identity().translate(cx - 3f, cy + 1f, 2f).scale(7f, 6f, 8f)
        list.add(Prims.ball, m, white, emissive = 0.25f)
        m.identity().translate(cx + 4f, cy + 2f, -1f).scale(8f, 6.5f, 8.5f)
        list.add(Prims.ball, m, white, emissive = 0.25f)
        m.identity().translate(cx, cy - 2f, 4f).scale(6f, 4f, 6f)
        list.add(Prims.ball, m, 0xFFE4F0FF.toInt(), emissive = 0.25f)
    }

    // ---------------------------------------------------------------- meshes (centred on the tile)

    private fun cube(r: Region) =
        MeshBuilder().apply { roundBox(0f, 0f, 0f, HALF, HALF, HALF, 1.8f, -1, Array(6) { r }) }.build()

    /** The front and sides of a ground tile, reaching from the camera side far back. */
    private fun slab(r: Region) = MeshBuilder().apply {
        val f = LevelDepth.FRONT; val b = LevelDepth.BACK
        quad(-HALF, -HALF, f, HALF, -HALF, f, HALF, HALF, f, -HALF, HALF, f, -1, r)
        var z = f
        while (z > b) {
            val z2 = maxOf(b, z - TILE)
            quad(-HALF, -HALF, z2, -HALF, -HALF, z, -HALF, HALF, z, -HALF, HALF, z2, -1, r)
            quad(HALF, -HALF, z, HALF, -HALF, z2, HALF, HALF, z2, HALF, HALF, z, -1, r)
            z = z2
        }
    }.build()

    /**
     * The walkable top of a ground tile, plus a rounded lip along its front edge. Both wear
     * only the top rows of the picture (the grass or snow), not the earth under it.
     */
    private fun top(full: Region) = MeshBuilder().apply {
        val r = Region(full.u0, full.v0, full.u1, full.v0 + (full.v1 - full.v0) * 0.2f)
        val f = LevelDepth.FRONT; val b = LevelDepth.BACK
        var z = f
        while (z > b) {
            val z2 = maxOf(b, z - TILE)
            quad(-HALF, HALF, z, HALF, HALF, z, HALF, HALF, z2, -HALF, HALF, z2, -1, r)
            z = z2
        }
        roundBox(0f, HALF - 1.4f, f, HALF + 0.4f, 2.8f, 2.8f, 2.6f, -1, Array(6) { r })
    }.build()

    private companion object {
        const val HALF = TILE / 2f
        const val TITLE_CLEAR_ROW = 8
    }
}

/** How deep the ground runs, in front of and behind the line the heroes walk on. */
object LevelDepth {
    const val FRONT = 14f
    const val BACK = -56f
}
