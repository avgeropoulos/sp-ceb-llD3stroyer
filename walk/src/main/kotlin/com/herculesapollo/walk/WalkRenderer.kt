package com.herculesapollo.walk

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Draws the walk: sky, scenery, critters, the dogs on their leashes, HUD and menus. */
object WalkRenderer {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val YELLOW = 0xFFFFE14D.toInt()
    private const val SHADE = 0x99000000.toInt()

    fun render(g: WalkGame, gfx: Gfx) {
        drawWorld(g, gfx)
        if (g.state != WalkGame.State.ALL_DONE) {
            for (d in g.dogs) drawLeash(g, gfx, d)
            drawWalker(g, gfx)
        }
        for (d in g.dogs) drawDog(g, gfx, d)
        drawPopups(g, gfx, g.popups)

        when (g.state) {
            WalkGame.State.TITLE -> drawTitle(g, gfx)
            WalkGame.State.INTRO -> { drawHud(g, gfx); drawIntro(g, gfx) }
            WalkGame.State.PLAYING -> {
                drawHud(g, gfx)
                g.tip?.let { banner(gfx, g.viewW, it, 66f) }
                WalkControls.draw(gfx, g.input, g.viewW, g.herc.ready, g.apollo.ready)
                if (g.paused) drawPaused(gfx, g.viewW, canSwitch = false)
            }
            WalkGame.State.HOME -> {
                drawHud(g, gfx)
                if (g.stateTime > 0.8f) gfx.shadowText("HOME SWEET HOME!", g.viewW / 2, 80f, 14f, YELLOW, 1)
            }
            WalkGame.State.RESULTS -> drawResults(g, gfx)
            WalkGame.State.OUCH -> drawOuch(g, gfx)
            WalkGame.State.ALL_DONE -> drawAllDone(g, gfx)
        }
        WalkControls.drawPause(gfx, g.viewW, g.musicOn)
    }

    /** Sky, scenery, treats, critters and bikes: everything but the player's characters. */
    internal fun drawWorld(g: WalkScene, gfx: Gfx) {
        val theme = g.course.walk.theme
        drawSky(g, gfx, theme)
        drawBackdrop(g, gfx, theme)
        drawGround(g, gfx, theme)
        for (p in g.course.props) drawProp(g, gfx, p, theme)
        for (t in g.course.treats) if (!t.taken) {
            val bob = sin(g.time * 4f + t.x) * 1.5f
            gfx.sprite(WalkSprites.bone, t.x - g.camX, t.y + bob)
        }
        for (c in g.course.critters) drawCritter(g, gfx, c)
        for (b in g.course.bikes) if (b.active) drawBike(gfx, b.x - g.camX, b.t)
    }

    internal fun drawPopups(g: WalkScene, gfx: Gfx, popups: List<Popup>) {
        for (p in popups) gfx.shadowText(p.text, p.x - g.camX, p.y - p.t * 18f, p.size, p.color, 1)
    }

    /** [canSwitch]: playing as one of the dogs, so offer SWITCH DOG too. */
    internal fun drawPaused(gfx: Gfx, viewW: Float, canSwitch: Boolean) =
        PauseMenu.draw(gfx, viewW, VIEW_H, canSwitch, "Your game is saved at the start of every walk.")

    // ------------------------------------------------------------------ Helpers

    internal fun lerpColor(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Darkens a color (for night-time scenery). */
    private fun dim(c: Int, night: Boolean) = if (night) lerpColor(c, 0xFF101830.toInt(), 0.55f) else c

    /** Small deterministic hash so scenery looks random but stays put. */
    private fun hash(i: Int): Int {
        var h = i * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return (h xor (h ushr 16)) and 0x7FFFFFFF
    }

    /** A straight line of [w] thickness, drawn as a thin quad. */
    internal fun line(gfx: Gfx, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, color: Int) {
        val len = hypot(x2 - x1, y2 - y1)
        if (len < 0.01f) return
        val nx = -(y2 - y1) / len * w / 2f
        val ny = (x2 - x1) / len * w / 2f
        gfx.poly(floatArrayOf(x1 + nx, x2 + nx, x2 - nx, x1 - nx), floatArrayOf(y1 + ny, y2 + ny, y2 - ny, y1 - ny), color)
    }

    internal fun sprite(gfx: Gfx, s: Sprite, cx: Float, feet: Float, facing: Int) =
        gfx.sprite(s, cx - s.w / 2f, feet - s.h, flipX = facing < 0)

    internal fun banner(gfx: Gfx, viewW: Float, text: String, y: Float) {
        val w = text.length * 5.2f + 16f
        gfx.rect(viewW / 2 - w / 2, y - 11f, w, 16f, 0xAA000000.toInt())
        gfx.text(text, viewW / 2, y, 8f, WHITE, 1)
    }

    // ------------------------------------------------------------------ Scenery

    private fun drawSky(g: WalkScene, gfx: Gfx, theme: Theme) {
        val bands = 12
        val bottom = GROUND - 10f
        for (i in 0 until bands) {
            val y = bottom * i / bands
            gfx.rect(0f, y, g.viewW, bottom / bands + 1f, lerpColor(theme.skyTop, theme.skyBottom, i / (bands - 1f)))
        }
        if (theme == Theme.NIGHT) {
            for (i in 0 until 40) {
                val h = hash(i)
                val x = (h % 1000) / 1000f * g.viewW
                val y = ((h / 1000) % 1000) / 1000f * 110f + 6f
                val tw = if (sin(g.time * 3f + i) > 0.6f) 0xFFFFFFFF.toInt() else 0xAAFFFFFF.toInt()
                gfx.rect(x, y, 1.5f, 1.5f, tw)
            }
            gfx.oval(g.viewW - 110f, 26f, 30f, 30f, 0xFFFFF4C8.toInt())
            gfx.oval(g.viewW - 102f, 22f, 28f, 28f, theme.skyTop)
        } else {
            gfx.oval(g.viewW - 112f, 22f, 28f, 28f, 0x55FFF6A0)
            gfx.oval(g.viewW - 108f, 26f, 20f, 20f, 0xFFFFE680.toInt())
            // A couple of slow clouds.
            for (i in 0 until 4) {
                val span = g.viewW + 160f
                val x = ((i * 157f - g.camX * 0.1f - g.time * 4f) % span + span) % span - 80f
                val y = 30f + (hash(i) % 40)
                gfx.oval(x, y, 44f, 14f, 0xDDFFFFFF.toInt())
                gfx.oval(x + 10f, y - 7f, 26f, 16f, 0xDDFFFFFF.toInt())
            }
        }
    }

    private fun drawBackdrop(g: WalkScene, gfx: Gfx, theme: Theme) {
        val night = theme == Theme.NIGHT
        // Far hills, slow parallax.
        val far = g.camX * 0.25f
        val hillW = 220f
        val first = floor(far / hillW).toInt() - 1
        for (i in first..first + (g.viewW / hillW).toInt() + 2) {
            val x = i * hillW - far
            val rise = (hash(i) % 30).toFloat()
            if (theme == Theme.BEACH || theme == Theme.BACKYARD) continue
            gfx.oval(x - 50f, GROUND - 70f - rise, 320f, 140f, theme.hills)
        }
        val mid = g.camX * 0.5f
        when (theme) {
            Theme.PARK, Theme.MEADOW -> {
                val step = 70f
                val start = floor(mid / step).toInt() - 1
                for (i in start..start + (g.viewW / step).toInt() + 2) {
                    val h = hash(i + 99)
                    if (h % 3 == 0) continue
                    val x = i * step - mid + (h % 30)
                    val s = 0.7f + (h % 5) * 0.1f
                    val dark = if (theme == Theme.PARK) 0xFF3F8F3A.toInt() else 0xFF7AA83A.toInt()
                    gfx.rect(x - 2f, GROUND - 30f * s - 14f, 4f, 30f * s, 0xFF6B4A2A.toInt())
                    gfx.oval(x - 14f * s, GROUND - 62f * s - 14f, 28f * s, 36f * s, dark)
                }
            }
            Theme.STREET, Theme.NIGHT -> {
                val step = 96f
                val start = floor(mid / step).toInt() - 1
                val colors = intArrayOf(0xFFF4A261.toInt(), 0xFF8ECAE6.toInt(), 0xFFE9C46A.toInt(), 0xFFE5989B.toInt(), 0xFFB5E48C.toInt())
                for (i in start..start + (g.viewW / step).toInt() + 2) {
                    val h = hash(i + 7)
                    val x = i * step - mid + 8f
                    val hw = 64f + (h % 3) * 8f
                    val hh = 44f + (h % 4) * 6f
                    val top = GROUND - 16f - hh
                    gfx.rect(x, top, hw, hh, dim(colors[h % colors.size], night))
                    gfx.poly(floatArrayOf(x - 6f, x + hw / 2, x + hw + 6f), floatArrayOf(top, top - 22f, top), dim(0xFFB5432F.toInt(), night))
                    val win = if (night) (if (h % 4 != 0) 0xFFFFE08A.toInt() else 0xFF2A3350.toInt()) else 0xFFDFF3FF.toInt()
                    gfx.rect(x + 10f, top + 10f, 14f, 12f, win)
                    gfx.rect(x + hw - 24f, top + 10f, 14f, 12f, win)
                    gfx.rect(x + hw / 2 - 6f, top + hh - 20f, 12f, 20f, dim(0xFF7A4A24.toInt(), night))
                }
            }
            Theme.BACKYARD -> {
                // The neighbours' two-storey stucco houses, then our white vinyl privacy fence.
                val step = 150f
                val start = floor(mid / step).toInt() - 1
                for (i in start..start + (g.viewW / step).toInt() + 2) {
                    val h = hash(i + 31)
                    val x = i * step - mid
                    val hw = 120f
                    val top = GROUND - 150f - (h % 3) * 8f
                    val wall = if (h % 2 == 0) 0xFFEFEAE0.toInt() else 0xFFDCD8D0.toInt()
                    gfx.rect(x, top, hw, GROUND - 16f - top, wall)
                    gfx.rect(x - 6f, top - 6f, hw + 12f, 8f, 0xFF8A8F96.toInt())
                    for (r in 0..1) for (c in 0..2) {
                        gfx.rect(x + 12f + c * 38f, top + 16f + r * 46f, 22f, 26f, 0xFF4F5A66.toInt())
                    }
                }
                val fence = g.camX * 0.85f
                gfx.rect(0f, GROUND - 92f, g.viewW, 78f, 0xFFF7F7F2.toInt())
                gfx.rect(0f, GROUND - 92f, g.viewW, 3f, 0xFFDDDDD6.toInt())
                val post = 52f
                val s0 = floor(fence / post).toInt() - 1
                for (i in s0..s0 + (g.viewW / post).toInt() + 2) {
                    val x = i * post - fence
                    gfx.rect(x, GROUND - 96f, 6f, 82f, 0xFFFFFFFF.toInt())
                    gfx.rect(x - 1f, GROUND - 98f, 8f, 4f, 0xFFE8E8E2.toInt())
                    gfx.rect(x + 6f, GROUND - 92f, 1f, 78f, 0xFFD6D6CE.toInt())
                    for (k in 1..3) gfx.rect(x + 6f + k * 11.5f, GROUND - 88f, 1f, 72f, 0xFFE6E6E0.toInt())
                }
            }
            Theme.BEACH -> {
                gfx.rect(0f, GROUND - 58f, g.viewW, 44f, theme.hills)
                gfx.rect(0f, GROUND - 58f, g.viewW, 3f, 0xFF7FD0FF.toInt())
                val step = 34f
                val off = (g.camX * 0.5f + g.time * 10f) % step
                for (i in -1..(g.viewW / step).toInt() + 1) {
                    val x = i * step - off
                    gfx.rect(x, GROUND - 46f + (i % 3) * 9f, 12f, 2f, 0x88FFFFFF.toInt())
                }
                val boat = ((300f - g.camX * 0.2f) % (g.viewW + 100f) + g.viewW + 100f) % (g.viewW + 100f) - 50f
                gfx.rect(boat, GROUND - 62f, 26f, 5f, 0xFFFFFFFF.toInt())
                gfx.poly(floatArrayOf(boat + 12f, boat + 12f, boat + 26f), floatArrayOf(GROUND - 64f, GROUND - 92f, GROUND - 64f), 0xFFFF6B6B.toInt())
            }
        }
    }

    private fun drawGround(g: WalkScene, gfx: Gfx, theme: Theme) {
        gfx.rect(0f, GROUND - 16f, g.viewW, VIEW_H - GROUND + 16f, theme.grass)
        // Tufts / speckles that scroll with the ground.
        val step = 23f
        val start = floor(g.camX / step).toInt()
        val speck = lerpColor(theme.grass, 0xFF000000.toInt(), 0.15f)
        for (i in start..start + (g.viewW / step).toInt() + 1) {
            val h = hash(i)
            val x = i * step - g.camX
            gfx.rect(x, GROUND - 13f + (h % 4), 2f, 2f, speck)
            gfx.rect(x + 9f, GROUND + 16f + (h % 20), 2f, 2f, speck)
        }
        // The path everyone walks along.
        gfx.rect(0f, GROUND - 7f, g.viewW, 15f, theme.path)
        gfx.rect(0f, GROUND - 7f, g.viewW, 2f, theme.pathEdge)
        gfx.rect(0f, GROUND + 7f, g.viewW, 2f, theme.pathEdge)
        val seam = when (theme) { Theme.STREET, Theme.NIGHT -> 34f; Theme.BEACH -> 9f; Theme.BACKYARD -> 24f; else -> 0f }
        if (seam > 0f) {
            val s0 = floor(g.camX / seam).toInt()
            for (i in s0..s0 + (g.viewW / seam).toInt() + 1) {
                gfx.rect(i * seam - g.camX, GROUND - 5f, 1f, 12f, theme.pathEdge)
            }
        }
    }

    private fun drawProp(g: WalkScene, gfx: Gfx, p: Prop, theme: Theme) {
        val sx = p.x - g.camX
        if (sx < -120f - p.w || sx > g.viewW + 120f) return
        val night = theme == Theme.NIGHT
        when (p.kind) {
            PropKind.TREE -> {
                gfx.rect(sx - 5f, GROUND - 92f, 10f, 92f, dim(0xFF7A5230.toInt(), night))
                gfx.rect(sx - 2f, GROUND - 80f, 2f, 60f, dim(0xFF5E3E22.toInt(), night))
                gfx.rect(sx + 3f, GROUND - 84f, 22f, 4f, dim(0xFF7A5230.toInt(), night))
                gfx.oval(sx - 40f, GROUND - 124f, 34f, 30f, dim(0xFF3E9B3A.toInt(), night))
                gfx.oval(sx - 34f, GROUND - 142f, 68f, 54f, dim(0xFF3E9B3A.toInt(), night))
                gfx.oval(sx - 22f, GROUND - 152f, 44f, 38f, dim(0xFF56B84A.toInt(), night))
                gfx.oval(sx + 6f, GROUND - 126f, 32f, 28f, dim(0xFF4AAA42.toInt(), night))
            }
            PropKind.FENCE -> {
                val top = g.course.fenceTop
                val c = dim(0xFFF2EEE6.toInt(), night)
                val sh = dim(0xFFC9C2B4.toInt(), night)
                var x = sx
                while (x <= sx + p.w) {
                    gfx.rect(x, top - 3f, 5f, GROUND - top - 2f, c)
                    gfx.rect(x + 4f, top - 3f, 1f, GROUND - top - 2f, sh)
                    x += 16f
                }
                gfx.rect(sx - 2f, top, p.w + 9f, 3f, c)
                gfx.rect(sx - 2f, top + 12f, p.w + 9f, 4f, sh)
                gfx.rect(sx - 2f, top + 28f, p.w + 9f, 4f, sh)
            }
            PropKind.BIN -> {
                val top = g.course.binTop
                gfx.rect(sx - 14f, top + 4f, 28f, GROUND - top - 4f, dim(0xFF3C7A4A.toInt(), night))
                gfx.rect(sx - 10f, top + 8f, 2f, GROUND - top - 12f, dim(0xFF2C5A36.toInt(), night))
                gfx.rect(sx + 8f, top + 8f, 2f, GROUND - top - 12f, dim(0xFF2C5A36.toInt(), night))
                val raccoon = g.course.critters.firstOrNull { it.kind == Kind.RACCOON && it.home == p.x }
                if (raccoon != null && raccoon.hidden) {
                    // Lid ajar, two eyes peeking out.
                    gfx.poly(floatArrayOf(sx - 16f, sx + 16f, sx + 16f, sx - 16f), floatArrayOf(top - 2f, top - 6f, top - 2f, top + 2f), dim(0xFF2C5A36.toInt(), night))
                    gfx.rect(sx - 8f, top + 1f, 16f, 3f, 0xFF101010.toInt())
                    if (sin(g.time * 2f) > -0.7f) {
                        gfx.rect(sx - 5f, top + 1.5f, 2f, 2f, YELLOW)
                        gfx.rect(sx + 3f, top + 1.5f, 2f, 2f, YELLOW)
                    }
                } else {
                    gfx.rect(sx - 16f, top, 32f, 4f, dim(0xFF2C5A36.toInt(), night))
                }
            }
            PropKind.BUSH -> {
                gfx.oval(sx - 22f, GROUND - 26f, 44f, 28f, dim(0xFF2F8A3A.toInt(), night))
                gfx.oval(sx - 14f, GROUND - 32f, 28f, 22f, dim(0xFF43A84A.toInt(), night))
            }
            PropKind.BENCH -> {
                val c = dim(0xFF9A6234.toInt(), night)
                gfx.rect(sx - 20f, GROUND - 22f, 40f, 4f, c)
                gfx.rect(sx - 20f, GROUND - 32f, 40f, 3f, c)
                gfx.rect(sx - 20f, GROUND - 38f, 40f, 3f, c)
                gfx.rect(sx - 18f, GROUND - 18f, 3f, 12f, 0xFF333333.toInt())
                gfx.rect(sx + 15f, GROUND - 18f, 3f, 12f, 0xFF333333.toInt())
            }
            PropKind.HYDRANT -> {
                gfx.rect(sx - 4f, GROUND - 18f, 8f, 14f, 0xFFD62828.toInt())
                gfx.rect(sx - 6f, GROUND - 14f, 12f, 3f, 0xFFD62828.toInt())
                gfx.oval(sx - 4f, GROUND - 22f, 8f, 7f, 0xFFB01E1E.toInt())
            }
            PropKind.LAMP -> {
                gfx.rect(sx - 1.5f, GROUND - 82f, 3f, 76f, 0xFF2E2E3A.toInt())
                gfx.rect(sx - 5f, GROUND - 88f, 10f, 7f, 0xFF2E2E3A.toInt())
                gfx.rect(sx - 3f, GROUND - 82f, 6f, 3f, if (night) 0xFFFFF0A0.toInt() else 0xFFE8E8E8.toInt())
                if (night) {
                    gfx.oval(sx - 26f, GROUND - 92f, 52f, 40f, 0x22FFF0A0)
                    gfx.poly(floatArrayOf(sx - 3f, sx + 3f, sx + 22f, sx - 22f), floatArrayOf(GROUND - 79f, GROUND - 79f, GROUND, GROUND), 0x18FFF0A0)
                }
            }
            PropKind.FLOWERS -> {
                val colors = intArrayOf(0xFFFF6B9A.toInt(), 0xFFFFE14D.toInt(), 0xFFB98CFF.toInt(), 0xFFFFFFFF.toInt())
                for (i in 0 until 6) {
                    val fx = sx + i * 7f - 18f
                    val fy = GROUND - 14f - (hash(i + p.x.toInt()) % 5)
                    gfx.rect(fx + 1f, fy + 2f, 1f, 5f, 0xFF2F7A2A.toInt())
                    gfx.rect(fx, fy, 3f, 3f, colors[(i + p.x.toInt() / 7) % colors.size])
                }
            }
            PropKind.HOLE -> {
                gfx.oval(sx - 8f, GROUND - 3f, 16f, 6f, 0xFF6B4E2A.toInt())
                gfx.oval(sx - 6f, GROUND - 2f, 12f, 4f, 0xFF2A1E10.toInt())
                val crab = g.course.critters.firstOrNull { it.kind == Kind.CRAB && it.home == p.x }
                if (crab != null && crab.hidden && sin(g.time * 2.5f) > -0.5f) {
                    gfx.rect(sx - 3f, GROUND - 5f, 1.5f, 3f, 0xFFE04A2A.toInt())
                    gfx.rect(sx + 2f, GROUND - 5f, 1.5f, 3f, 0xFFE04A2A.toInt())
                    gfx.rect(sx - 3f, GROUND - 6f, 1.5f, 1.5f, 0xFF101010.toInt())
                    gfx.rect(sx + 2f, GROUND - 6f, 1.5f, 1.5f, 0xFF101010.toInt())
                }
            }
            PropKind.UMBRELLA -> {
                gfx.rect(sx - 1f, GROUND - 62f, 2f, 56f, 0xFFEEEEEE.toInt())
                val n = 6
                for (i in 0 until n) {
                    val x0 = sx - 30f + i * 10f
                    gfx.poly(
                        floatArrayOf(sx, x0, x0 + 10f), floatArrayOf(GROUND - 74f, GROUND - 56f, GROUND - 56f),
                        if (i % 2 == 0) 0xFFFF5D5D.toInt() else 0xFFFFFFFF.toInt(),
                    )
                }
                gfx.oval(sx - 26f, GROUND - 10f, 34f, 8f, 0xFF4DA3FF.toInt())
            }
            PropKind.SANDCASTLE -> {
                val c = 0xFFE0B870.toInt()
                gfx.rect(sx - 16f, GROUND - 20f, 32f, 14f, c)
                gfx.rect(sx - 14f, GROUND - 30f, 8f, 10f, c)
                gfx.rect(sx + 6f, GROUND - 30f, 8f, 10f, c)
                gfx.rect(sx - 4f, GROUND - 36f, 8f, 16f, c)
                gfx.rect(sx - 2f, GROUND - 14f, 4f, 8f, 0xFF9C7A48.toInt())
                gfx.rect(sx, GROUND - 46f, 1f, 10f, 0xFF555555.toInt())
                gfx.poly(floatArrayOf(sx + 1f, sx + 1f, sx + 8f), floatArrayOf(GROUND - 46f, GROUND - 40f, GROUND - 43f), 0xFFFF5D5D.toInt())
            }
            PropKind.MAILBOX -> {
                gfx.rect(sx - 1.5f, GROUND - 24f, 3f, 18f, 0xFF6B4A2A.toInt())
                gfx.rect(sx - 7f, GROUND - 34f, 14f, 10f, 0xFF2B4FD8.toInt())
                gfx.rect(sx + 6f, GROUND - 38f, 2f, 7f, 0xFFE52521.toInt())
            }
            PropKind.SIGN -> {
                gfx.rect(sx - 1.5f, GROUND - 30f, 3f, 24f, 0xFF6B4A2A.toInt())
                gfx.rect(sx - 18f, GROUND - 42f, 36f, 14f, 0xFF9A6234.toInt())
                gfx.text("TRAIL", sx, GROUND - 32f, 7f, 0xFFFFF1C9.toInt(), 1)
            }
            PropKind.HOME -> drawHome(gfx, sx + 30f, night)
            PropKind.POND -> {
                gfx.oval(sx - 18f, GROUND + 10f, 36f, 10f, 0xFF2E7D9A.toInt())
                gfx.oval(sx - 14f, GROUND + 11f, 28f, 7f, 0xFF4FA8C8.toInt())
                gfx.oval(sx + 2f, GROUND + 12f, 8f, 4f, 0xFF4CAF50.toInt())
                val frog = g.course.critters.firstOrNull { it.kind == Kind.FROG && it.home == p.x }
                if (frog != null && frog.hidden) {
                    // Ripples where it splashed in, and two eyes peeking out.
                    val r = (g.time * 1.5f) % 1f
                    gfx.oval(sx - 6f - r * 8f, GROUND + 14f - r * 2f, 12f + r * 16f, 4f + r * 4f, 0x55FFFFFF)
                    gfx.rect(sx - 4f, GROUND + 13f, 2f, 2f, 0xFF4CAF50.toInt())
                    gfx.rect(sx + 1f, GROUND + 13f, 2f, 2f, 0xFF4CAF50.toInt())
                }
            }
            PropKind.COT -> {
                // The raised dog cot from the patio: black tube legs and grey mesh.
                gfx.rect(sx - 32f, GROUND - 18f, 64f, 4f, 0xFFB8BCC2.toInt())
                gfx.rect(sx - 32f, GROUND - 19f, 64f, 1.5f, 0xFF2A2A2A.toInt())
                for (lx in floatArrayOf(-30f, 27f)) gfx.rect(sx + lx, GROUND - 16f, 3f, 12f, 0xFF2A2A2A.toInt())
            }
            PropKind.COUCH -> {
                // The wicker loveseat with its white cushions.
                val wicker = 0xFF3A3A3E.toInt()
                gfx.rect(sx - 34f, GROUND - 40f, 68f, 22f, wicker)
                gfx.rect(sx - 38f, GROUND - 30f, 8f, 26f, wicker)
                gfx.rect(sx + 30f, GROUND - 30f, 8f, 26f, wicker)
                gfx.rect(sx - 30f, GROUND - 22f, 60f, 16f, wicker)
                gfx.rect(sx - 29f, GROUND - 38f, 58f, 16f, 0xFFF2F0EA.toInt())
                gfx.rect(sx - 30f, GROUND - 24f, 60f, 6f, 0xFFF7F5EF.toInt())
                for (k in 0 until 7) gfx.rect(sx - 33f + k * 10f, GROUND - 16f, 1f, 10f, 0xFF55555A.toInt())
            }
            PropKind.TOYCAR -> {
                // A little purple ride-on car with a yellow roof.
                gfx.rect(sx - 18f, GROUND - 22f, 36f, 14f, 0xFFB57EDC.toInt())
                gfx.rect(sx - 12f, GROUND - 34f, 2f, 12f, 0xFFFFFFFF.toInt())
                gfx.rect(sx + 10f, GROUND - 34f, 2f, 12f, 0xFFFFFFFF.toInt())
                gfx.rect(sx - 16f, GROUND - 37f, 32f, 4f, 0xFFFFD21F.toInt())
                gfx.oval(sx - 16f, GROUND - 12f, 9f, 9f, 0xFF2A2A2A.toInt())
                gfx.oval(sx + 7f, GROUND - 12f, 9f, 9f, 0xFF2A2A2A.toInt())
                gfx.oval(sx + 10f, GROUND - 19f, 5f, 5f, 0xFFFFF3B0.toInt())
            }
            PropKind.PALM -> {
                for (k in 0 until 9) gfx.rect(sx - 3f + k * 0.8f, GROUND - 14f - k * 11f, 7f, 12f, if (k % 2 == 0) 0xFF8A6A48.toInt() else 0xFF7A5A3A.toInt())
                val tx = sx + 4f
                val ty = GROUND - 112f
                for (a in 0 until 7) {
                    val ang = -2.6f + a * 0.85f
                    val ex = tx + kotlin.math.cos(ang) * 38f
                    val ey = ty + kotlin.math.sin(ang) * 18f + 14f
                    gfx.poly(floatArrayOf(tx, ex, tx + (ex - tx) * 0.5f), floatArrayOf(ty, ey, ty + (ey - ty) * 0.5f - 5f), if (a % 2 == 0) 0xFF3E9B3A.toInt() else 0xFF56B84A.toInt())
                }
            }
            PropKind.POOL -> {
                // The pool, behind its black mesh safety fence.
                gfx.rect(sx - 6f, GROUND - 24f, p.w + 12f, 10f, 0xFFD9D6CE.toInt())
                gfx.rect(sx, GROUND - 22f, p.w, 7f, 0xFF3FB6E0.toInt())
                gfx.rect(sx, GROUND - 22f, p.w, 2f, 0xFFB8ECFF.toInt())
                gfx.rect(sx - 6f, GROUND - 58f, p.w + 12f, 40f, 0x55303030)
                var x = sx - 6f
                while (x <= sx + p.w + 6f) {
                    gfx.rect(x, GROUND - 60f, 2f, 44f, 0xFF1E1E1E.toInt())
                    x += 30f
                }
                gfx.rect(sx - 6f, GROUND - 60f, p.w + 12f, 2f, 0xFF1E1E1E.toInt())
            }
        }
    }

    private fun drawHome(gfx: Gfx, cx: Float, night: Boolean) {
        val top = GROUND - 74f
        gfx.rect(cx - 44f, top, 88f, 68f, 0xFFF1D7A0.toInt())
        gfx.poly(floatArrayOf(cx - 54f, cx, cx + 54f), floatArrayOf(top + 2f, top - 36f, top + 2f), 0xFFB5432F.toInt())
        gfx.rect(cx + 22f, top - 30f, 9f, 18f, 0xFF8A3324.toInt())
        val win = if (night) 0xFFFFE08A.toInt() else 0xFFBFE6FF.toInt()
        gfx.rect(cx - 36f, top + 12f, 18f, 16f, win)
        gfx.rect(cx + 18f, top + 12f, 18f, 16f, win)
        gfx.rect(cx - 10f, GROUND - 38f, 20f, 32f, 0xFF7A4A24.toInt())
        gfx.oval(cx + 5f, GROUND - 23f, 3f, 3f, YELLOW)
        gfx.rect(cx - 16f, top + 2f, 32f, 9f, 0xFF7A4A24.toInt())
        gfx.text("HOME", cx, top + 9.5f, 7f, 0xFFFFF1C9.toInt(), 1)
        // Two water bowls by the door, in the dogs' collar colors.
        gfx.oval(cx - 30f, GROUND - 9f, 12f, 6f, WalkSprites.HERC_COLLAR)
        gfx.oval(cx + 20f, GROUND - 9f, 12f, 6f, WalkSprites.APOLLO_COLLAR)
    }

    private fun drawBike(gfx: Gfx, sx: Float, t: Float) {
        val wy = GROUND - 7f
        for (wx in floatArrayOf(sx - 11f, sx + 11f)) {
            gfx.oval(wx - 7f, wy - 7f, 14f, 14f, 0xFF222222.toInt())
            gfx.oval(wx - 5f, wy - 5f, 10f, 10f, 0xFFB8C4CC.toInt())
            gfx.oval(wx - 1.5f, wy - 1.5f, 3f, 3f, 0xFF222222.toInt())
        }
        val frame = 0xFF2E86DE.toInt()
        val seat = sx + 5f to GROUND - 19f
        val bar = sx - 8f to GROUND - 22f
        val crank = sx to wy
        line(gfx, sx + 11f, wy, seat.first, seat.second, 2f, frame)
        line(gfx, seat.first, seat.second, crank.first, crank.second, 2f, frame)
        line(gfx, crank.first, crank.second, sx - 11f, wy, 2f, frame)
        line(gfx, seat.first - 1f, seat.second + 2f, bar.first, bar.second + 2f, 2f, frame)
        line(gfx, bar.first, bar.second, sx - 11f, wy, 2f, frame)
        // The rider, pedalling.
        val pedal = sin(t * 12f) * 4f
        line(gfx, seat.first, seat.second - 2f, crank.first + pedal, crank.second - 2f, 3f, 0xFF2D3142.toInt())
        line(gfx, seat.first, seat.second - 2f, crank.first - pedal, crank.second + 1f, 3f, 0xFF4F5D75.toInt())
        line(gfx, seat.first, seat.second - 2f, sx - 3f, GROUND - 36f, 6f, 0xFF06D6A0.toInt())
        line(gfx, sx - 3f, GROUND - 34f, bar.first, bar.second, 2.5f, 0xFFE0A878.toInt())
        gfx.oval(sx - 9f, GROUND - 46f, 10f, 10f, 0xFFE0A878.toInt())
        gfx.poly(floatArrayOf(sx - 11f, sx - 4f, sx + 3f), floatArrayOf(GROUND - 41f, GROUND - 49f, GROUND - 41f), 0xFFFFD166.toInt())
        gfx.rect(sx - 8f, GROUND - 42f, 2f, 2f, 0xFF101010.toInt())
    }

    // ------------------------------------------------------------------ Characters

    private fun drawCritter(g: WalkScene, gfx: Gfx, c: Critter) {
        if (c.mood == Mood.GONE) return
        val sx = c.x - g.camX
        if (sx < -40f || sx > g.viewW + 40f) return
        val flap = (c.t * 14f).toInt() % 2 == 0
        val moving = c.mood == Mood.FLEE || c.mood == Mood.CHASED
        if (c.hidden) {
            if (c.kind == Kind.RABBIT) {
                // Ears poking out of the bush.
                val wig = sin(g.time * 3f) * 1f
                gfx.rect(sx - 3f, GROUND - 38f + wig, 2f, 7f, 0xFFB08A64.toInt())
                gfx.rect(sx + 1f, GROUND - 37f - wig, 2f, 7f, 0xFFB08A64.toInt())
            }
            return
        }
        when (c.kind) {
            Kind.SQUIRREL -> when {
                c.climbing -> gfx.sprite(WalkSprites.squirrelClimb, sx - 4f, c.y - 12f)
                c.mood == Mood.SAFE -> {
                    sprite(gfx, WalkSprites.squirrel, sx, c.y, c.facing)
                    if (sin(g.time * 5f + c.home) > 0.6f) gfx.shadowText("chk!", sx, c.y - 14f, 6f, WHITE, 1)
                }
                moving || sin(c.t * 6f) > 0.3f -> sprite(gfx, WalkSprites.squirrelHop, sx, c.y - (if (moving) 0f else 2f), c.facing)
                else -> sprite(gfx, WalkSprites.squirrel, sx, c.y, c.facing)
            }
            Kind.PIGEON, Kind.GULL -> {
                val air = c.y < GROUND - 1f
                val gull = c.kind == Kind.GULL
                val s = when {
                    air && flap -> if (gull) WalkSprites.gullFly else WalkSprites.pigeonFly
                    else -> if (gull) WalkSprites.gull else WalkSprites.pigeon
                }
                val peck = if (!air && sin(c.t * 4f) > 0.8f) 1f else 0f
                sprite(gfx, s, sx, c.y + peck, c.facing)
            }
            Kind.RABBIT -> {
                val hop = if (moving) abs(sin(c.t * 11f)) * 9f else 0f
                sprite(gfx, if (hop > 2f) WalkSprites.rabbitHop else WalkSprites.rabbit, sx, c.y - hop, c.facing)
            }
            Kind.CAT -> {
                sprite(gfx, if (c.mood == Mood.IDLE) WalkSprites.cat else WalkSprites.catHiss, sx, c.y, c.facing)
                if (c.mood == Mood.FLEE && sin(g.time * 8f) > 0f) gfx.shadowText("HSSS!", sx, c.y - 14f, 6f, WHITE, 1)
            }
            Kind.RACCOON -> {
                sprite(gfx, WalkSprites.raccoon, sx, c.y, c.facing)
                if (c.mood == Mood.FLEE) gfx.shadowText("?!", sx, c.y - 12f, 6f, WHITE, 1)
            }
            Kind.CRAB -> {
                val step = moving || sin(c.t * 4f) > 0f
                sprite(gfx, if (step && flap) WalkSprites.crabStep else WalkSprites.crab, sx, c.y, 1)
            }
            Kind.LIZARD -> when {
                c.climbing -> gfx.sprite(WalkSprites.lizardClimb, sx - 3.5f, c.y - 13f)
                moving -> sprite(gfx, if (flap) WalkSprites.lizardRun else WalkSprites.lizard, sx, c.y, c.facing)
                // Basking: a little push-up now and then.
                else -> sprite(gfx, WalkSprites.lizard, sx, c.y - (if (sin(c.t * 3f) > 0.85f) 1f else 0f), c.facing)
            }
            Kind.FROG -> sprite(gfx, if (c.y < GROUND - 1f) WalkSprites.frogHop else WalkSprites.frog, sx, c.y, c.facing)
            Kind.SKUNK -> sprite(gfx, WalkSprites.skunk, sx, c.y, c.facing)
        }
        if (c.frozenT > 0f && c.catchable) gfx.shadowText("!?", sx, c.y - c.h - 5f, 7f, YELLOW, 1)
        if (c.mood == Mood.CHASED && c.t < 0.5f && c.kind != Kind.SKUNK) {
            gfx.shadowText("!", sx, c.y - c.h - 6f, 9f, YELLOW, 1)
        }
    }

    private fun hand(g: WalkGame) = (g.walker.x + 5f * g.walker.facing) to (g.walker.y - 11.5f)

    private fun collar(d: Dog) =
        (d.x + 4f * d.facing) to (d.y - 8.5f)

    private fun drawLeash(g: WalkGame, gfx: Gfx, d: Dog) {
        val (ax, ay) = hand(g)
        val (bx, by) = collar(d)
        val dist = hypot(bx - ax, by - ay)
        val slack = ((LEASH - dist) / LEASH).coerceIn(0f, 1f)
        val sag = min(slack * 26f, GROUND - max(ay, by) - 1f).coerceAtLeast(0f)
        val mx = (ax + bx) / 2f
        val my = (ay + by) / 2f + sag * 2f
        val color = if (d.herc) WalkSprites.HERC_COLLAR else WalkSprites.APOLLO_COLLAR
        var px = ax
        var py = ay
        val n = 10
        for (i in 1..n) {
            val t = i / n.toFloat()
            val u = 1f - t
            val x = u * u * ax + 2f * u * t * mx + t * t * bx
            val y = u * u * ay + 2f * u * t * my + t * t * by
            line(gfx, px - g.camX, py, x - g.camX, y, 1.3f, color)
            px = x; py = y
        }
    }

    private fun drawWalker(g: WalkGame, gfx: Gfx) {
        val w = g.walker
        if (w.hurtT > 0f && (w.hurtT * 12f).toInt() % 2 == 0) return
        val s = when {
            !w.onGround -> WalkSprites.walkerStepA
            w.moving || w.dragT > 0f -> if ((w.step * 7f).toInt() % 2 == 0) WalkSprites.walkerStepA else WalkSprites.walkerStepB
            else -> WalkSprites.walkerStand
        }
        sprite(gfx, s, w.x - g.camX, w.y, w.facing)
    }

    private fun drawDog(g: WalkGame, gfx: Gfx, d: Dog) {
        val sx = d.x - g.camX
        val walking = abs(d.vx) > 20f
        val frame = (d.anim / 12f).toInt() % 2 == 0
        val s = if (d.herc) {
            when { !d.onGround -> WalkSprites.hercLeap; walking && frame -> WalkSprites.hercWalk; else -> WalkSprites.hercStand }
        } else {
            when { !d.onGround -> WalkSprites.apolloLeap; walking && frame -> WalkSprites.apolloWalk; else -> WalkSprites.apolloStand }
        }
        if (g.state == WalkGame.State.ALL_DONE) {
            // Curled up asleep at home.
            sprite(gfx, if (d.herc) WalkSprites.hercStand else WalkSprites.apolloStand, sx, d.y, d.facing)
            val z = (g.time * 0.8f + (if (d.herc) 0f else 0.5f)) % 1f
            gfx.shadowText("z", sx + 6f + z * 6f, d.y - d.h - 4f - z * 14f, 6f + z * 4f, WHITE, 1)
            return
        }
        sprite(gfx, s, sx, d.y, d.facing)
        if (d.stinkT > 0f) {
            for (i in 0 until 3) {
                val a = g.time * 2f + i * 2.1f
                val px = sx + sin(a) * 9f
                val py = d.y - d.h - 4f - ((g.time * 12f + i * 6f) % 16f)
                gfx.oval(px - 3f, py - 3f, 6f, 6f, 0x889BE15D.toInt())
            }
        }
        val showName = g.state == WalkGame.State.INTRO ||
            (g.state == WalkGame.State.PLAYING && g.walkIndex == 0 && g.time < 5f)
        if (showName) {
            val c = if (d.herc) WalkSprites.HERC_COLLAR else WalkSprites.APOLLO_COLLAR
            gfx.shadowText(d.name.uppercase(), sx, d.y - d.h - (if (d.herc) 14f else 4f), 6f, lerpColor(c, WHITE, 0.4f), 1)
        }
    }

    // ------------------------------------------------------------------ HUD and menus

    internal fun drawHud(g: WalkSession, gfx: Gfx) {
        gfx.shadowText("WALK ${g.walkIndex + 1}: ${g.course.walk.title}", 8f, 14f, 8f, WHITE)
        gfx.shadowText("SCORE ${g.score.toString().padStart(6, '0')}", 8f, 26f, 8f, YELLOW)
        for (i in 0 until 3) {
            gfx.sprite(if (i < g.hearts) WalkSprites.heart else WalkSprites.heartEmpty, 8f + i * 10f, 31f)
        }
        gfx.shadowText("CHASED ${g.chased}/${g.course.total}", 44f, 37f, 7f, WHITE)
        gfx.shadowText("BEST ${g.best}", g.viewW - 60f, 14f, 6f, WHITE, 2)
    }

    internal fun drawIntro(g: WalkSession, gfx: Gfx) {
        gfx.rect(0f, 70f, g.viewW, 56f, SHADE)
        gfx.shadowText("WALK ${g.walkIndex + 1}", g.viewW / 2, 90f, 10f, YELLOW, 1)
        gfx.shadowText(g.course.walk.title, g.viewW / 2, 110f, 16f, WHITE, 1)
        gfx.shadowText(g.introLine, g.viewW / 2, 122f, 7f, WHITE, 1)
    }

    private fun drawTitle(g: WalkGame, gfx: Gfx) {
        gfx.rect(0f, 36f, g.viewW, 74f, 0x77000000)
        gfx.shadowText("HERCULES & APOLLO", g.viewW / 2, 68f, 22f, WHITE, 1)
        gfx.shadowText("THE BIG WALK", g.viewW / 2, 90f, 14f, YELLOW, 1)
        gfx.shadowText("Chase squirrels, bunnies, cats and more!", g.viewW / 2, 104f, 7f, WHITE, 1)
        // Save slot tabs.
        val ty = WalkControls.SLOT_TOP
        for (i in 0 until SaveSlots.COUNT) {
            val x = WalkControls.slotX(i, g.viewW)
            val on = g.slots.current == i + 1
            gfx.rect(x + 2f, ty, WalkControls.SLOT_W - 4f, WalkControls.SLOT_H, if (on) 0xFFFFD21F.toInt() else 0xAA000000.toInt())
            gfx.text("SLOT ${i + 1}", x + WalkControls.SLOT_W / 2, ty + 11f, 7.5f, if (on) 0xFF1D1D1D.toInt() else WHITE, 1)
        }
        // Three buttons side by side: walk both dogs, or be one of them.
        val third = g.viewW / 3f
        val top = WalkControls.CARD_TOP
        val cards = listOf(
            Triple("WALK THE DOGS", WalkGame.WALK, 0xFF2A9D8F.toInt()),
            Triple("BE HERCULES", WalkGame.HERC, WalkSprites.HERC_COLLAR),
            Triple("BE APOLLO", WalkGame.APOLLO, WalkSprites.APOLLO_COLLAR),
        )
        val pulse = (g.stateTime * 2f).toInt() % 2 == 0
        cards.forEachIndexed { i, (label, mode, color) ->
            val cx = third * i + third / 2
            val w = third - 12f
            gfx.rect(cx - w / 2 - 1.5f, top - 1.5f, w + 3f, 51f, if (pulse) WHITE else 0xAAFFFFFF.toInt())
            gfx.rect(cx - w / 2, top, w, 48f, (0xDD000000.toInt()) or (color and 0xFFFFFF))
            gfx.shadowText(label, cx, top + 11f, 8f, WHITE, 1)
            when (i) {
                0 -> {
                    gfx.sprite(WalkSprites.walkerStand, cx - 36f, top + 15f, w = 9f, h = 17f)
                    gfx.sprite(WalkSprites.hercStand, cx - 25f, top + 14f)
                    gfx.sprite(WalkSprites.apolloStand, cx + 2f, top + 14f)
                }
                1 -> gfx.sprite(WalkSprites.hercStand, cx - 12.5f, top + 14f)
                else -> gfx.sprite(WalkSprites.apolloStand, cx - 12.5f, top + 14f)
            }
            val progress = if (g.slots.started(mode)) {
                "WALK ${g.slots.walk(mode).coerceAtMost(Walks.all.lastIndex) + 1}  SCORE ${g.slots.score(mode)}"
            } else {
                "NEW GAME"
            }
            gfx.text(progress, cx, top + 44f, 5.5f, 0xDDFFFFFF.toInt(), 1)
        }
        if (g.best > 0) gfx.shadowText("BEST ${g.best}", g.viewW / 2, 232f, 7f, WHITE, 1)
    }

    internal fun panel(g: WalkSession, gfx: Gfx, top: Float, height: Float) {
        val w = min(300f, g.viewW - 32f)
        gfx.rect(g.viewW / 2 - w / 2 - 2f, top - 2f, w + 4f, height + 4f, 0xFF1D3557.toInt())
        gfx.rect(g.viewW / 2 - w / 2, top, w, height, 0xEE2A4A7A.toInt())
    }

    internal fun drawResults(g: WalkSession, gfx: Gfx) {
        drawHud(g, gfx)
        val cx = g.viewW / 2
        panel(g, gfx, 50f, 150f)
        gfx.shadowText("WALK ${g.walkIndex + 1} DONE!", cx, 70f, 12f, YELLOW, 1)
        gfx.shadowText(g.course.walk.title, cx, 84f, 8f, WHITE, 1)
        val shown = min(3, (g.stateTime / 0.35f).toInt())
        for (i in 0 until 3) {
            val x = cx - 30f + i * 30f
            star(gfx, x, 104f, 10f, if (i < g.stars && i < shown) YELLOW else 0x55FFFFFF)
        }
        gfx.shadowText("Critters chased: ${g.chased} / ${g.course.total}", cx, 130f, 8f, WHITE, 1)
        gfx.shadowText("Treats: ${g.treatsGot}    Bonus: +${g.bonus}", cx, 144f, 8f, WHITE, 1)
        if (g.chased == g.course.total) gfx.shadowText("PERFECT CHASE! +1000", cx, 158f, 8f, 0xFF7DF9FF.toInt(), 1)
        gfx.shadowText("SCORE ${g.score}", cx, 174f, 10f, YELLOW, 1)
        if (g.stateTime > 1.5f) {
            val next = if (g.walkIndex == Walks.all.lastIndex) "tap to finish" else "tap for the next walk"
            gfx.shadowText(next, cx, 192f, 7f, WHITE, 1)
        }
    }

    internal fun drawOuch(g: WalkSession, gfx: Gfx) {
        drawHud(g, gfx)
        val cx = g.viewW / 2
        panel(g, gfx, 70f, 90f)
        gfx.shadowText("OOF! TOO MANY BUMPS", cx, 96f, 12f, 0xFFFF6B6B.toInt(), 1)
        gfx.shadowText("Watch out for the bikes - JUMP over them!", cx, 116f, 7f, WHITE, 1)
        if (g.stateTime > 1.5f) gfx.shadowText("tap to try this walk again", cx, 142f, 8f, YELLOW, 1)
    }

    internal fun drawAllDone(g: WalkSession, gfx: Gfx) {
        val cx = g.viewW / 2
        panel(g, gfx, 40f, 112f)
        gfx.shadowText("ALL WALKS DONE!", cx, 64f, 14f, YELLOW, 1)
        gfx.shadowText(g.doneLine, cx, 82f, 8f, WHITE, 1)
        gfx.shadowText("GOOD DOGS!", cx, 102f, 12f, WHITE, 1)
        gfx.shadowText("FINAL SCORE ${g.score}", cx, 122f, 10f, YELLOW, 1)
        gfx.shadowText("BEST ${g.best}", cx, 138f, 7f, WHITE, 1)
        if (g.stateTime > 3f) gfx.shadowText("tap to go back to the start", cx, 232f, 7f, WHITE, 1)
    }

    internal fun star(gfx: Gfx, cx: Float, cy: Float, r: Float, color: Int) {
        val xs = FloatArray(10)
        val ys = FloatArray(10)
        for (i in 0 until 10) {
            val a = -Math.PI / 2 + i * Math.PI / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            xs[i] = cx + (kotlin.math.cos(a) * rr).toFloat()
            ys[i] = cy + (kotlin.math.sin(a) * rr).toFloat()
        }
        gfx.poly(xs, ys, color)
    }
}
