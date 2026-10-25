package com.roccoquest.team3d

import com.roccoquest.team.Place
import com.roccoquest.team.TeamGame

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

    fun build(g: TeamGame): DrawList {
        list.clear()
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
        val DARK = setOf(
            Place.CAVE, Place.CASTLE, Place.VOLCANO, Place.NIGHT, Place.DEEP, Place.END,
            Place.AURORA, Place.FUNGI, Place.ORBIT,
        )
    }
}
