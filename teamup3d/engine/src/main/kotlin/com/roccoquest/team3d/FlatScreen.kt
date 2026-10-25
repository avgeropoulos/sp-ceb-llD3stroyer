package com.roccoquest.team3d

import com.roccoquest.team.TeamGame

/**
 * Every Team Up! screen that isn't the 3D level itself (title words, save files, the world
 * map, the house, shops, sticker book, mini games, the HUD and touch buttons) drawn by the
 * game's own 2D code through [FlatGfx], so it all comes out as lit, chunky 3D.
 */
class FlatScreen {
    val list = DrawList().apply { keepOrder = true }
    private val gfx = FlatGfx(list)

    /**
     * Builds the screen. When [world] is the level's 3D frame, only what goes on top of the
     * level (its floating words, the HUD, buttons and menus) is drawn here.
     */
    fun build(g: TeamGame, world: DrawList?): DrawList {
        list.clear()
        list.showWorld = true
        gfx.begin()
        val overLevel = world != null && world.showWorld
        if (overLevel) {
            for (i in 0 until world!!.labelCount) {
                val l = world.label(i)
                gfx.text(l.text, l.x, l.y, l.size, l.color, l.align)
            }
        }
        g.render(gfx, drawWorld = !overLevel)
        list.setFlatCamera(g.viewW, gfx.z + FlatGfx.STEP)
        val e = list.env
        e.setLight(-0.35f, 0.55f, 0.75f)
        e.sun = 0xFFE0E0E0.toInt()
        e.ambientSky = 0xFF7A7A80.toInt()
        e.ambientGround = 0xFF404048.toInt()
        e.fogNear = 1e9f
        e.fogFar = 2e9f
        list.finish()
        return list
    }
}
