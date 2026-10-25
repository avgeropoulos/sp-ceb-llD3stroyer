package com.roccoquest.team3d

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.Sprites
import com.roccoquest.game.TILE
import com.roccoquest.game.rainbow
import com.roccoquest.team.Tile
import com.roccoquest.team.Place
import com.roccoquest.team.TeamGame
import kotlin.math.sin

/**
 * Builds a 3D frame of a Team Up! level from the live game. The game itself (heroes,
 * monsters, menus, saves) is exactly the normal Team Up!; only the picture changes:
 * the painted sky stays flat in the back (drawn by the app), and the level is 3D in front.
 *
 * The 3D world uses the 2D game's screen coordinates, so the camera never moves: the level
 * slides past it, which keeps everything lined up with the touch buttons and HUD.
 */
class TeamScene {
    val list = DrawList()
    private val paper = PaperGfx(list)
    private val tiles = TileGfx(list, paper)
    private val mark: (Any) -> Unit = { paper.begin(0f) }
    private val castMark: (Any) -> Unit = { paper.begin(CAST_Z) }
    /** The 2D title draws the team a bit above the ground; in 3D they stand right on it. */
    private val castGfx = ShiftGfx(paper, TITLE_DROP)

    fun build(g: TeamGame): DrawList {
        list.clear()
        if (g.state == TeamGame.State.TITLE) {
            title(g)
            list.finish()
            return list
        }
        list.showWorld = g.showsLevel
        if (!list.showWorld) {
            list.finish()
            return list
        }
        list.setCamera(0f, g.viewW)
        lighting(g.level.def.place)
        tiles.begin(g)
        g.renderLevelTiles(tiles)
        paper.begin(0f)
        g.renderLevelActors(paper, mark)
        list.finish()
        return list
    }

    /**
     * The title screen in 3D: level 1 drifting by under a gently swaying camera, the team in
     * front, and the game's name in big bobbing block letters.
     */
    private fun title(g: TeamGame) {
        list.showWorld = true
        g.prepareTitle()
        val t = g.time
        list.setCamera(0f, g.viewW, yaw = sin(t * 0.4f) * 0.07f, pitch = 0.2f + sin(t * 0.3f) * 0.03f)
        lighting(Place.HILLS)
        tiles.begin(g)
        tiles.titleMode = true
        try {
            g.renderLevelTiles(tiles)
        } finally {
            tiles.titleMode = false
        }
        // Fill in the pits, so the team always has ground to stand on.
        val cam = g.worldCam
        val lv = g.level
        for (tx in (cam / TILE).toInt() - 2..((cam + g.viewW) / TILE).toInt() + 2) {
            if (lv[tx, GROUND_ROW] == Tile.GROUND) continue
            tiles.fillGround(tx * TILE - cam, GROUND_ROW, Sprites.grassTop, top = true)
            tiles.fillGround(tx * TILE - cam, GROUND_ROW + 1, Sprites.dirt, top = false)
        }
        // The team stands on the ground in front of the level, so the blocks never hide anyone.
        paper.begin(CAST_Z)
        g.renderTitleCast(castGfx, castMark)
        word("ROCCO & BLOCKY", g.viewW / 2, -48f, 2.8f, t, 0) { 0xFFFFC81A.toInt() }
        word("TEAM UP!", g.viewW / 2, -74f, 2.5f, t, 14) { i -> rainbow(t * 0.3f, i / 8f) or 0xFF000000.toInt() }
    }

    private val m = Mat4()

    /** Draws [text] in 3D block letters centred on ([cx], [cy]); each letter bobs on its own. */
    private inline fun word(text: String, cx: Float, cy: Float, size: Float, t: Float, seed: Int, color: (Int) -> Int) {
        val width = text.length * TitleLetters.ADVANCE * size
        for ((i, c) in text.withIndex()) {
            val mesh = TitleLetters.mesh(c) ?: continue
            val k = i + seed
            val x = cx - width / 2 + (i + 0.5f) * TitleLetters.ADVANCE * size
            val bob = sin(t * 3f + k * 0.55f) * 2.2f
            m.identity().translate(x, cy + bob, 10f).rotateY(sin(t * 1.6f + k * 0.4f) * 0.25f).scale(size)
            list.add(mesh, m, color(i), emissive = 0.15f, shine = 0.7f)
            // A dark outline just behind, so the letters stand out from the sky.
            m.scale(1.12f, 1.1f, 0.9f).translate(0f, 0f, -0.6f)
            list.add(mesh, m, 0xFF3A1A08.toInt())
        }
    }

    private fun lighting(place: Place) {
        val e = list.env
        e.setLight(-0.45f, 0.85f, 0.6f)
        // No fog: the painted sky behind already gives the distance.
        e.fogNear = 1e6f
        e.fogFar = 2e6f
        if (place in DARK) {
            e.sun = 0xFFC8D0F0.toInt()
            e.ambientSky = 0xFF8C9CC8.toInt()
            e.ambientGround = 0xFF544C64.toInt()
        } else {
            e.sun = 0xFFFFF2DC.toInt()
            e.ambientSky = 0xFFB0C0E0.toInt()
            e.ambientGround = 0xFF786652.toInt()
        }
    }

    private companion object {
        /** How far in front of the level the title screen's team stands. */
        const val CAST_Z = 20f
        const val GROUND_ROW = 13
        const val TITLE_DROP = GROUND_ROW * 16f - 190f

        val DARK = setOf(
            Place.CAVE, Place.CASTLE, Place.VOLCANO, Place.NIGHT, Place.DEEP, Place.END,
            Place.AURORA, Place.FUNGI, Place.ORBIT,
        )
    }
}

/** Draws everything [dy] pixels lower. */
class ShiftGfx(private val inner: Gfx, private val dy: Float) : Gfx {
    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.rect(x, y + dy, w, h, color)
    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.oval(x, y + dy, w, h, color)
    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) = inner.poly(xs, FloatArray(ys.size) { ys[it] + dy }, color)
    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) =
        inner.sprite(s, x, y + dy, flipX, flipY, w, h)
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) = inner.text(s, x, y + dy, size, color, align)
    override fun gradient(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) = inner.gradient(x, y + dy, w, h, top, bottom)
}
