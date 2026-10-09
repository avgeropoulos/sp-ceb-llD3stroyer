package com.roccoquest.game

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** A speech bubble that floats above a spot for a moment. */
class Speech(x: Float, y: Float, private val s: String, private val dur: Float = 1.8f) : Particle(x, y) {
    override fun update(dt: Float) {
        life += dt
        y -= 4f * dt
        if (life > dur) removed = true
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val w = s.length * 4.4f + 10f
        val bx = x + ox - w / 2
        gfx.rect(bx - 1, y - 15, w + 2, 14f, 0xFF101010.toInt())
        gfx.rect(bx, y - 14, w, 12f, 0xFFFFFFFF.toInt())
        gfx.poly(floatArrayOf(x + ox - 3, x + ox + 3, x + ox), floatArrayOf(y - 2, y - 2, y + 3), 0xFFFFFFFF.toInt())
        gfx.text(s, x + ox, y - 5, 7f, 0xFF101010.toInt(), 1)
    }
}

/** A sparkle where King Krag disappears into the sky. */
class Twinkle(x: Float, y: Float) : Particle(x, y) {
    override fun update(dt: Float) {
        life += dt
        if (life > 1.2f) removed = true
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val r = 10f * (1f - life / 1.2f) + 2f
        val c = 0xFFFFF6A0.toInt()
        gfx.poly(floatArrayOf(x + ox - r, x + ox, x + ox + r, x + ox), floatArrayOf(y, y - r * 0.3f, y, y + r * 0.3f), c)
        gfx.poly(floatArrayOf(x + ox, x + ox + r * 0.3f, x + ox, x + ox - r * 0.3f), floatArrayOf(y - r, y, y + r, y), c)
    }
}

// ======================================================================== Boomadaboom

/** Krag's big cousin. He leaps at Rocco and lands with a ground-shaking smash. */
class Boomadaboom(x: Float, y: Float) : Enemy(x - 4, y - 28, 24f, 28f) {
    var hp = 3
        private set
    private var hurt = 0f
    private var anim = 0f
    private var jumpTimer = 2.5f
    private var airborne = false
    override val freezable = false

    init { vx = -25f }

    override fun update(g: Game, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        hurt -= dt
        val h = g.hero
        if (onGround && airborne) {
            airborne = false
            smash(g)
        }
        if (onGround) {
            vx = if (h.cx < cx) -25f else 25f
            jumpTimer -= dt
            if (jumpTimer <= 0 && abs(h.cx - cx) < 220f) {
                vy = -380f
                vx = (h.cx - cx).coerceIn(-110f, 110f)
                airborne = true
                jumpTimer = 3f + Random.nextFloat() * 1.5f
            }
        }
        vy = min(vy + 900f * dt, 450f)
        if (g.level.moveX(this, vx * dt)) vx = -vx
        g.level.moveY(this, vy * dt)
        if (y > g.level.h * TILE + 32) removed = true
    }

    private fun smash(g: Game) {
        g.sfx(Sound.BREAK)
        g.puff(x, bottom - 2)
        g.puff(x + w, bottom - 2)
        g.say(cx, y - 4, "BOOMADABOOM!")
        val row = tileOf(bottom + 1)
        for (tx in tileOf(x)..tileOf(x + w - 0.01f)) if (g.level[tx, row] == T.BRICK) g.smashTile(tx, row)
        val h = g.hero
        if (h.onGround && abs(h.cx - cx) < 70f && abs(h.bottom - bottom) < 20f) g.hurtHero()
    }

    fun damage(g: Game, n: Int): Boolean {
        if (hurt > 0) return true
        hp -= n
        hurt = 0.6f
        g.sfx(Sound.BOSS_HIT)
        if (hp <= 0) {
            knockOut(g)
            g.addScore(2000, cx, y)
        }
        return true
    }

    override fun stomp(g: Game) { damage(g, 1) }
    override fun fireHit(g: Game) = damage(g, 1)
    override fun iceHit(g: Game) = damage(g, 1)
    override fun boomHit(g: Game) = damage(g, 1)

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val face = if (g.hero.cx < cx) -1 else 1
        val m = Mirror(gfx, x + ox, y, w, flip = face > 0, flipY = dying, h = 28f)
        val flash = hurt > 0 && (hurt * 20).toInt() % 2 == 0
        val body = if (flash) 0xFFFFFFFF.toInt() else 0xFFB0303A.toInt()
        val dark = 0xFF6A1020.toInt()
        val step = if (onGround && (anim * 5).toInt() % 2 == 0) 1f else 0f
        // Legs and boots
        m.rect(4f, 22f + step, 7f, 6f - step, dark)
        m.rect(14f, 22f - step + 1, 7f, 5f + step, dark)
        // Big body
        m.oval(0f, 6f, 24f, 20f, body)
        m.oval(5f, 12f, 14f, 11f, 0xFFF2C18A.toInt())
        // Fists
        m.oval(-4f, 13f, 8f, 8f, 0xFFF2C18A.toInt())
        m.oval(20f, 13f, 8f, 8f, 0xFFF2C18A.toInt())
        // Head with a horned helmet and angry eyes
        m.oval(3f, -2f, 18f, 14f, 0xFFF2C18A.toInt())
        m.rect(2f, -3f, 20f, 5f, 0xFF505060.toInt())
        m.poly(floatArrayOf(2f, -2f, 6f), floatArrayOf(-2f, -9f, -2f), 0xFFE8E8F0.toInt())
        m.poly(floatArrayOf(18f, 26f, 22f), floatArrayOf(-2f, -9f, -2f), 0xFFE8E8F0.toInt())
        m.rect(5f, 4f, 4f, 3f, 0xFF101010.toInt())
        m.rect(13f, 4f, 4f, 3f, 0xFF101010.toInt())
        m.poly(floatArrayOf(4f, 10f, 10f), floatArrayOf(2f, 3f, 4f), 0xFF101010.toInt())
        m.poly(floatArrayOf(12f, 18f, 12f), floatArrayOf(3f, 2f, 4f), 0xFF101010.toInt())
        m.rect(7f, 9f, 9f, 2f, 0xFF8A1010.toInt())
        if (hp in 1 until 3) {
            gfx.rect(x + ox, y - 14, w, 3f, 0xFF000000.toInt())
            gfx.rect(x + ox, y - 14, w * hp / 3f, 3f, 0xFFE53A1E.toInt())
        }
    }
}

// ======================================================================== Friendly things

/** A colourful twisting puzzle cube that bounces around. Bounce on it for a super jump and a coin. */
class PuzzleCube(x: Float, y: Float) : Entity(x + 1, y - 14, 14f, 14f) {
    private var t = 0f
    private var cooldown = 0f
    private val colors = intArrayOf(
        0xFFE53A1E.toInt(), 0xFF2E6BE6.toInt(), 0xFFFFD21F.toInt(),
        0xFF2EB84A.toInt(), 0xFFFF8A00.toInt(), 0xFFFFFFFF.toInt(),
    )

    init { vx = 45f; vy = -60f }

    override fun update(g: Game, dt: Float) {
        t += dt
        cooldown -= dt
        if (g.level.moveX(this, vx * dt)) vx = -vx
        val goingDown = vy > 0
        val ceiling = g.level.moveY(this, vy * dt)
        if (onGround && goingDown) vy = -abs(vy)
        if (ceiling != null || y < 28f) vy = abs(vy)
        if (y > g.level.h * TILE) vy = -abs(vy)
    }

    /** Rocco bounced on it. */
    fun bounced(g: Game) {
        if (cooldown > 0) return
        cooldown = 0.4f
        g.collectCoin(cx, y)
        g.sfx(Sound.JUMP)
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val px = x + ox
        gfx.rect(px - 1, y - 1, w + 2, h + 2, 0xFF101010.toInt())
        val shift = (t * 3).toInt()
        for (r in 0 until 3) for (c in 0 until 3) {
            val col = colors[(r * 3 + c + shift + (if (r == 1) shift else 0)) % colors.size]
            gfx.rect(px + c * 4.7f + 0.5f, y + r * 4.7f + 0.5f, 3.9f, 3.9f, col)
        }
    }
}

/** Skip-a-Doo Toilet: crouch on one to flush yourself ahead to the next one. */
class Toilet(x: Float, bottom: Float, val entry: Boolean) : Entity(x, bottom - 18, 16f, 18f) {
    var flush = 0f

    override fun update(g: Game, dt: Float) { flush -= dt }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val px = x + ox
        val white = 0xFFF4F4F4.toInt()
        val shade = 0xFFB8C0D0.toInt()
        gfx.rect(px + 1, y - 10, 6f, 16f, shade)
        gfx.rect(px + 2, y - 9, 4f, 14f, white)
        gfx.rect(px + 1, y + 2, 15f, 4f, 0xFF8AA0C0.toInt())
        gfx.oval(px + 3, y + 3, 13f, 9f, white)
        gfx.rect(px + 6, y + 10, 6f, 8f, white)
        gfx.rect(px + 4, y + 16, 10f, 2f, shade)
        gfx.rect(px + 3, y - 7, 2f, 2f, 0xFFC0C0C0.toInt())
        if (flush > 0) {
            val a = (flush * 200).toInt().coerceIn(0, 200)
            gfx.oval(px + 4, y - 4 - (1 - flush) * 20, 10f, 8f, argb(a, 0x6FD3FF))
            gfx.oval(px + 2, y - 10 - (1 - flush) * 30, 7f, 6f, argb(a, 0x9FE6FF))
        }
        if (entry) gfx.shadowText("SKIP-A-DOO", px + 8, y - 14, 6f, 0xFF6FD3FF.toInt(), 1)
    }
}

// ======================================================================== Guest stars

/** Zoom the Hedgehog spin-dashes in and smashes the wall in front of the princess. */
class Zoomer(x: Float, floor: Float, private val stopX: Float) : Entity(x, floor - 20, 18f, 20f) {
    private var t = 0f
    private var said = false
    init { vx = 420f; active = true }

    override fun update(g: Game, dt: Float) {
        t += dt
        x += vx * dt
        for (ty in tileOf(y)..tileOf(bottom - 0.01f)) for (tx in tileOf(x)..tileOf(x + w - 0.01f)) {
            val tile = g.level[tx, ty]
            if (tx in 0 until g.level.w && (tile == T.HARD || tile == T.BRICK)) g.smashTile(tx, ty)
        }
        if (!said && x > stopX) {
            said = true
            g.say(cx, y - 6, "Too slow, Krag!", 2.5f)
        }
        if (said && x > g.camX + g.viewW + 200) removed = true
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val px = x + ox
        // Speed streaks
        for (i in 1..3) gfx.rect(px - i * 9f, y + 4 + i * 3f, 8f, 2f, argb(150 - i * 40, 0x6FB6FF))
        // Spinning blue ball with quills
        gfx.oval(px, y + 1, 18f, 18f, 0xFF1E4FD8.toInt())
        val a = t * 30f
        for (i in 0 until 4) {
            val ang = a + i * 1.57f
            val qx = px + 9 + kotlin.math.cos(ang) * 9f
            val qy = y + 10 + sin(ang) * 9f
            gfx.poly(floatArrayOf(px + 9, qx + 3, qx - 3), floatArrayOf(y + 10, qy - 2, qy + 2), 0xFF143C9C.toInt())
        }
        gfx.oval(px + 5, y + 6, 8f, 8f, 0xFFF2C18A.toInt())
        gfx.oval(px + 9, y + 7, 3f, 4f, 0xFFFFFFFF.toInt())
        gfx.rect(px + 2, y + 15, 6f, 3f, 0xFFE52521.toInt())
    }
}

/** Captain Zap, space ranger: jets in now and then and zaps one bad guy. */
class CaptainZap(private val target: Enemy, startX: Float) : Entity(startX, 24f, 18f, 24f) {
    private var phase = 0
    private var t = 0f
    private var beamT = 0f
    private var bx = 0f
    private var by = 0f
    init { active = true }

    override fun update(g: Game, dt: Float) {
        t += dt
        when (phase) {
            0 -> {
                if (target.removed || target.dying) { phase = 2; return }
                val tx = target.cx - w / 2 - 30f
                val ty = (target.y - 70f).coerceAtLeast(30f)
                val dx = tx - x
                val dy = ty - y
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d < 8f || t > 4f) {
                    phase = 1
                    beamT = 0.4f
                    bx = target.cx
                    by = target.cy
                    target.knockOut(g)
                    g.addScore(200, target.cx, target.y)
                    g.sfx(Sound.ZAP)
                    g.say(cx, y - 4, "Zap-tastic!")
                } else {
                    x += dx / d * 230f * dt
                    y += dy / d * 230f * dt
                }
            }
            1 -> {
                beamT -= dt
                if (beamT <= 0) phase = 2
            }
            else -> {
                x += 220f * dt
                y -= 120f * dt
                if (y < -40f || x > g.camX + g.viewW + 40) removed = true
            }
        }
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val px = x + ox
        if (phase == 1) {
            val a = (beamT / 0.4f * 255).toInt().coerceIn(0, 255)
            gfx.poly(floatArrayOf(px + 16, bx + ox - 2, bx + ox + 2, px + 16), floatArrayOf(y + 10, by, by, y + 13), argb(a, 0xFF3A6A))
            gfx.oval(bx + ox - 8, by - 8, 16f, 16f, argb(a / 2, 0xFF9FB0))
        }
        // Jetpack flame
        if ((t * 20).toInt() % 2 == 0) gfx.oval(px + 1, y + 20, 6f, 8f, 0xFFFFB347.toInt())
        // Wings
        gfx.poly(floatArrayOf(px + 2, px - 8, px + 2), floatArrayOf(y + 8, y + 12, y + 16), 0xFFE8E8F0.toInt())
        gfx.poly(floatArrayOf(px + 16, px + 26, px + 16), floatArrayOf(y + 8, y + 12, y + 16), 0xFFE8E8F0.toInt())
        // Space suit
        gfx.oval(px + 1, y + 8, 16f, 16f, 0xFFF4F4F4.toInt())
        gfx.rect(px + 5, y + 12, 8f, 6f, 0xFF4CB531.toInt())
        gfx.rect(px + 7, y + 13, 4f, 2f, 0xFFE53A1E.toInt())
        // Head in a glass helmet, with a purple hood
        gfx.oval(px + 2, y - 2, 14f, 13f, 0xFF7B3FBF.toInt())
        gfx.oval(px + 4, y + 1, 10f, 9f, 0xFFFFD0A8.toInt())
        gfx.rect(px + 7, y + 4, 2f, 2f, 0xFF101010.toInt())
        gfx.rect(px + 11, y + 4, 2f, 2f, 0xFF101010.toInt())
        gfx.oval(px, y - 4, 18f, 16f, 0x55BFE8FF)
        // Arm pointing the laser
        gfx.rect(px + 14, y + 11, 5f, 3f, 0xFFF4F4F4.toInt())
    }
}

/** Ring-Ding Frog scoots across the screen for a few seconds, singing his crazy song. */
class RingDingFrog(x: Float, private val baseY: Float) : Entity(x, baseY, 24f, 24f) {
    private var t = 0f
    private var nextLine = 0.2f
    private var line = 0
    private val lines = listOf("Ring-a-ding-ding!", "Bing-bong-a-dong!", "Ribbit-a-dee-doo!", "Brrrm brrrm!")
    init { vx = 75f; active = true }

    override fun update(g: Game, dt: Float) {
        t += dt
        x += vx * dt
        y = baseY - abs(sin(t * 9f)) * 6f
        nextLine -= dt
        if (nextLine <= 0) {
            g.say(cx, y - 8, lines[line % lines.size], 1.4f)
            line++
            nextLine = 1.6f
        }
        if (t > 7.5f) removed = true
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val px = x + ox
        // Scooter
        gfx.rect(px - 2, y + 20, 26f, 3f, 0xFF808090.toInt())
        gfx.rect(px + 20, y + 4, 2f, 17f, 0xFF808090.toInt())
        gfx.rect(px + 17, y + 3, 8f, 2f, 0xFF404050.toInt())
        gfx.oval(px - 2, y + 20, 6f, 6f, 0xFF202020.toInt())
        gfx.oval(px + 18, y + 20, 6f, 6f, 0xFF202020.toInt())
        // Frog
        gfx.oval(px + 2, y + 6, 16f, 15f, 0xFF4CC83A.toInt())
        gfx.oval(px + 5, y + 11, 10f, 8f, 0xFFB8F0A0.toInt())
        gfx.oval(px + 2, y - 1, 8f, 9f, 0xFF4CC83A.toInt())
        gfx.oval(px + 10, y - 1, 8f, 9f, 0xFF4CC83A.toInt())
        gfx.oval(px + 3, y, 6f, 7f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 11, y, 6f, 7f, 0xFFFFFFFF.toInt())
        val look = if ((t * 4).toInt() % 2 == 0) 1f else 0f
        gfx.oval(px + 5 + look, y + 2, 3f, 3f, 0xFF101010.toInt())
        gfx.oval(px + 13 + look, y + 2, 3f, 3f, 0xFF101010.toInt())
        gfx.rect(px + 5, y + 9, 10f, 2f, 0xFFE52521.toInt())
        gfx.rect(px + 14, y + 8, 6f, 2f, 0xFF4CC83A.toInt())
        // Floating music notes
        for (i in 0 until 3) {
            val nx = px + 6 + i * 8f + sin(t * 3 + i) * 3f
            val ny = y - 12 - ((t * 20 + i * 9) % 22)
            gfx.text("♪", nx, ny, 9f, argb(220, rainbow(t, i / 3f)), 1)
        }
    }
}
