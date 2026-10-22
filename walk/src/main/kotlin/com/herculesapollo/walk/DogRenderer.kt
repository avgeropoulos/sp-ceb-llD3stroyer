package com.herculesapollo.walk

import com.roccoquest.game.Gfx
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.sin

/** Draws the dog's-eye adventure, reusing the walk's scenery, HUD and end screens. */
object DogRenderer {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val YELLOW = 0xFFFFE14D.toInt()

    fun render(g: DogGame, gfx: Gfx) {
        // The world is drawn zoomed in, with the ground kept at the bottom of the screen.
        val world = ScaledGfx(gfx, DogGame.ZOOM, VIEW_H - VIEW_H / DogGame.ZOOM)
        WalkRenderer.drawWorld(g.world, world)
        // Your person waits at the front door.
        if (g.state != DogGame.State.ALL_DONE) {
            WalkRenderer.sprite(world, WalkSprites.walkerStand, g.course.homeX + 46f - g.camX, GROUND, -1)
        }
        drawPlayer(g, world)
        WalkRenderer.drawPopups(g.world, world, g.popups)

        when (g.state) {
            DogGame.State.INTRO -> { WalkRenderer.drawHud(g, gfx); WalkRenderer.drawIntro(g, gfx) }
            DogGame.State.PLAYING -> {
                WalkRenderer.drawHud(g, gfx)
                g.tip?.let { WalkRenderer.banner(gfx, g.viewW, it, 66f) }
                val p = g.player
                WalkControls.drawDog(
                    gfx, g.input, g.viewW, p.actionLabel,
                    if (p.herc) WalkSprites.HERC_COLLAR else WalkSprites.APOLLO_COLLAR, p.cooldown <= 0f,
                )
            }
            DogGame.State.HOME -> {
                WalkRenderer.drawHud(g, gfx)
                if (g.stateTime > 0.6f) gfx.shadowText("HOME SWEET HOME!", g.viewW / 2, 80f, 14f, YELLOW, 1)
            }
            DogGame.State.RESULTS -> WalkRenderer.drawResults(g, gfx)
            DogGame.State.OUCH -> WalkRenderer.drawOuch(g, gfx)
            DogGame.State.ALL_DONE -> WalkRenderer.drawAllDone(g, gfx)
        }
    }

    private fun drawPlayer(g: DogGame, gfx: Gfx) {
        val p = g.player
        val sx = p.x - g.camX
        if (p.barkT > 0f) {
            // The bark's shock wave.
            val r = (0.4f - p.barkT) / 0.4f * 120f
            gfx.oval(sx - r, p.y - p.h / 2 - r * 0.6f, r * 2, r * 1.2f, 0x33FFFFFF)
            gfx.oval(sx - r * 0.7f, p.y - p.h / 2 - r * 0.42f, r * 1.4f, r * 0.84f, 0x22FFFFFF)
        }
        if (p.dashT > 0f) {
            for (i in 1..3) {
                gfx.rect(sx - p.facing * (p.w / 2 + i * 7f) - 3f, p.y - p.h + i * 4f, 8f, 1.5f, 0xAAFFFFFF.toInt())
            }
        }
        if (p.hurtT > 0f && (p.hurtT * 12f).toInt() % 2 == 0) return
        val walking = abs(p.vx) > 20f
        val frame = (p.anim / 12f).toInt() % 2 == 0
        val s = if (p.herc) {
            when { !p.onGround || p.dashT > 0f -> WalkSprites.hercLeap; walking && frame -> WalkSprites.hercWalk; else -> WalkSprites.hercStand }
        } else {
            when { !p.onGround || p.dashT > 0f -> WalkSprites.apolloLeap; walking && frame -> WalkSprites.apolloWalk; else -> WalkSprites.apolloStand }
        }
        if (g.state == DogGame.State.ALL_DONE) {
            WalkRenderer.sprite(gfx, s, sx, p.y, p.facing)
            val z = (g.time * 0.8f) % 1f
            gfx.shadowText("z", sx + 6f + z * 6f, p.y - p.h - 4f - z * 14f, 6f + z * 4f, WHITE, 1)
            return
        }
        WalkRenderer.sprite(gfx, s, sx, p.y, p.facing)
        if (p.stinkT > 0f) {
            for (i in 0 until 3) {
                val a = g.time * 2f + i * 2.1f
                val px = sx + sin(a) * 9f
                val py = p.y - p.h - 4f - ((g.time * 12f + i * 6f) % 16f)
                gfx.oval(px - 3f, py - 3f, 6f, 6f, 0x889BE15D.toInt())
            }
        }
        if (g.state == DogGame.State.INTRO) {
            val c = if (p.herc) WalkSprites.HERC_COLLAR else WalkSprites.APOLLO_COLLAR
            gfx.shadowText(p.name.uppercase(), sx, p.y - p.h - 6f, 6f, WalkRenderer.lerpColor(c, WHITE, 0.4f), 1)
        }
    }
}
