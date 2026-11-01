package com.roccoquest.team3d

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.Sprites
import com.roccoquest.game.TILE
import com.roccoquest.game.rainbow
import com.roccoquest.team.LevelDef
import com.roccoquest.team.Levels
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
 *
 * Behind the level stands real 3D [Scenery] (hills, trees, mountains, clouds...), built once per
 * level (while its INTRO card shows: [prepare]); when there is some, the painted backdrop leaves out its
 * own flat hills ([drawsScenery]).
 */
class TeamScene {
    val list = DrawList()
    internal val paper = PaperGfx(list)
    private val tiles = TileGfx(list, paper)
    private val mark: (Any) -> Unit = { paper.begin(0f) }
    /** New 3D models built per frame at most (each new picture costs a moment to build). */
    var voxelsPerFrame = 3
    private val castMark: (Any) -> Unit = { paper.begin(CAST_Z) }
    /** The 2D title draws the team a bit above the ground; in 3D they stand right on it. */
    private val castGfx = ShiftGfx(paper, TITLE_DROP)

    /** Where a point of the 2D level picture shows in this 3D one (so the circle wipe closes right on the hero). */
    private val toScreen: (FloatArray) -> Unit = { p -> list.project(p[0], -p[1], 0f, p) }

    /**
     * The scenery built so far, most recently used last: the level and the room before it (a level and its bonus
     * room), so going into a bonus room and back builds (and uploads) nothing again. What falls out is freed.
     */
    private val built = ArrayList<Built>(KEEP_SCENERY)
    private class Built(val def: LevelDef, val scenery: SceneryLevel?)
    /** How many times scenery has been built (each one is a moment's work, then a dozen meshes to upload). */
    var sceneryBuilt = 0
        private set
    /** The current level's 3D scenery (null where the place has none). */
    var scenery: SceneryLevel? = null
        private set
    /**
     * True when the last frame built has 3D scenery, so the painted backdrop behind should only be the
     * sky (`renderLevelBackdrop(gfx, scenery = false)`), not its own flat hills.
     */
    var drawsScenery = false
        private set

    fun build(g: TeamGame): DrawList {
        g.levelToScreen = toScreen
        drawsScenery = false
        list.clear()
        Voxels.newFrame(voxelsPerFrame)
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
        list.setCamera(0f, g.viewW, pitch = LEVEL_PITCH)
        lighting(g.level.def.place)
        tiles.begin(g)
        g.renderLevelTiles(tiles)
        paper.begin(0f)
        g.renderLevelActors(paper, mark)
        // After the level, so the parts of the scenery hidden behind it are skipped quickly.
        scenery(g)
        list.finish()
        return list
    }

    /**
     * Gets the scenery of a level that's starting ready ahead of time. The app calls this on the frames it draws in
     * 2D: while a level's INTRO card shows (a still picture, so a moment's work there can't be seen) it builds the
     * level's scenery, so the level's first 3D frame has nothing new to make (and the app has time to send it to
     * the GPU bit by bit). Costs nothing once it's ready.
     */
    fun prepare(g: TeamGame) {
        if (g.showsIntroCard || g.showsLevel) scenery = sceneryOf(g.level.def)
    }

    /** The scenery of [def]: kept from before, or built now (the same for a level every time). */
    private fun sceneryOf(def: LevelDef): SceneryLevel? {
        val last = built.lastIndex
        for (i in last downTo 0) {
            val b = built[i]
            if (b.def !== def) continue
            if (i != last) { built.removeAt(i); built.add(b) }
            return b.scenery
        }
        if (built.size >= KEEP_SCENERY) built.removeAt(0).scenery?.release()
        val index = Levels.all.indexOf(def)
        val s = Scenery.build(def.place, if (index >= 0) index else BONUS_SEED + def.place.ordinal)
        built += Built(def, s)
        sceneryBuilt++
        return s
    }

    /**
     * Draws the level's scenery (building it first if [prepare] didn't get the chance).
     * [sway] is how far the camera turns sideways (the title screen's camera sways).
     */
    private fun scenery(g: TeamGame, sway: Float = 0f) {
        scenery = sceneryOf(g.level.def)
        val s = scenery ?: return
        drawsScenery = true
        val e = list.env
        // The fog fades the far scenery into the sky (a rainbow sky during WONDER TIME and the victory dance).
        e.fog = if (g.wonder || g.state == TeamGame.State.VICTORY) RAINBOW_HAZE else s.haze
        e.fogNear = Scenery.FOG_NEAR
        e.fogFar = s.fogFar
        s.draw(list, g.worldCam, sway, g.time)
    }

    /**
     * The title screen in 3D: level 1 drifting by under a gently swaying camera, the team in
     * front, and the game's name in big bobbing block letters.
     */
    private fun title(g: TeamGame) {
        list.showWorld = true
        g.prepareTitle()
        val t = g.time
        list.setCamera(0f, g.viewW, yaw = sin(t * 0.4f) * TITLE_SWAY, pitch = 0.2f + sin(t * 0.3f) * 0.03f)
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
        // Level 1's hills and trees behind it all (with room for the swaying camera).
        scenery(g, sway = TITLE_SWAY + 0.03f)
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
        // No fog unless there's scenery (it turns the fog on for itself, starting past the level).
        e.fogNear = 1e6f
        e.fogFar = 2e6f
        // A touch cooler and dimmer in dark places, plain white light everywhere else.
        e.sun = if (place in DARK) 0xFFE2E6F4.toInt() else 0xFFFFFFFF.toInt()
    }

    private companion object {
        /** How far in front of the level the title screen's team stands. */
        const val CAST_Z = 20f
        /** How far the camera looks down on a level: shows the tops of the ground and blocks. */
        const val LEVEL_PITCH = 0.22f
        const val GROUND_ROW = 13
        const val TITLE_DROP = GROUND_ROW * 16f - 190f
        /** How far the title screen's camera turns from side to side (radians). */
        const val TITLE_SWAY = 0.07f
        /** Scenery seeds for rooms that aren't one of the levels (the bonus rooms). */
        const val BONUS_SEED = 1000
        /** How many rooms' scenery is kept: a level and its bonus room. */
        const val KEEP_SCENERY = 2
        val RAINBOW_HAZE = 0xFFFFE8F4.toInt()

        val DARK = setOf(
            Place.CAVE, Place.CASTLE, Place.VOLCANO, Place.NIGHT, Place.DEEP, Place.END,
            Place.AURORA, Place.FUNGI, Place.ORBIT, Place.CITY,
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
