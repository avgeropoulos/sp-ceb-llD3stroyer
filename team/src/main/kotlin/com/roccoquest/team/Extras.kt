package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.TILE
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import com.roccoquest.game.tileOf
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ============================================================================ Friends

/** A villager who talks (out loud!) and trades. [seed] picks which trades they offer. */
class Villager(x: Float, bottom: Float, val seed: Int) : Thing(x + 1, bottom - 24, 14f, 24f) {
    private var t = Random.nextFloat() * 5
    private var facing = -1
    private val homeX = x
    private val robe = listOf(0xFF7A4A20.toInt(), 0xFF2E7A3A.toInt(), 0xFF8A2A6A.toInt(), 0xFFE8E8E8.toInt())[seed % 4]
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        val l = g.leader
        if (abs(l.cx - cx) < 80f) {
            facing = if (l.cx < cx) -1 else 1
        } else {
            // Wander back and forth near home.
            x = homeX + sin(t * 0.4f) * 20f
            facing = if (cos(t * 0.4f) > 0) 1 else -1
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val f = facing.toFloat()
        val skin = 0xFFC89070.toInt()
        gfx.rect(px + 1, y + 10, 12f, 14f, robe)
        gfx.rect(px + 1, y + 13, 12f, 3f, argb(255, robe) and 0xFFB0B0B0.toInt())
        gfx.rect(px + 2, y + 22, 4f, 2f, 0xFF3A2A1A.toInt())
        gfx.rect(px + 8, y + 22, 4f, 2f, 0xFF3A2A1A.toInt())
        // Head with the big nose and one long eyebrow
        gfx.rect(px + 2, y, 10f, 10f, skin)
        gfx.rect(px + 2, y, 10f, 2f, 0xFF4A2E14.toInt())
        gfx.rect(px + 3, y + 3, 8f, 1f, 0xFF3A2410.toInt())
        gfx.rect(px + 4, y + 4, 2f, 2f, 0xFF2E7A3A.toInt())
        gfx.rect(px + 8, y + 4, 2f, 2f, 0xFF2E7A3A.toInt())
        gfx.rect(px + 6 + f * 1.5f, y + 5, 2.5f, 5f, 0xFFA87050.toInt())
        // Arms folded
        gfx.rect(px + 1, y + 15, 12f, 3f, argb(255, robe) and 0xFFD0D0D0.toInt())
        val near = abs(g.leader.cx - cx) < 22f && abs(g.leader.bottom - bottom) < 20f
        if (near && g.state == TeamGame.State.PLAY && (g.time * 2).toInt() % 2 == 0) {
            gfx.shadowText("TALK!", cx + ox, y - 6, 7f, 0xFF7FF4A0.toInt(), 1)
        }
    }
}

/** Dennis the dog runs up every now and then, barks, and points the way to go. */
class Dennis(x: Float, private val groundY: Float) : Thing(x, groundY - 14, 20f, 14f) {
    private var t = 0f
    private var mode = 0
    private var pointT = 0f
    private var targetX = 0f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        when (mode) {
            0 -> {
                if (targetX == 0f) targetX = g.leader.cx + 110f
                x += 210f * dt
                if (x >= targetX) {
                    mode = 1
                    g.sfx(Sound.BARK)
                    g.say(cx, y - 6, "WOOF WOOF! This way!", 2f)
                }
            }
            1 -> {
                pointT += dt
                if (pointT > 0.9f && pointT - dt <= 0.9f) g.sfx(Sound.BARK)
                if (pointT > 3f) mode = 2
            }
            else -> {
                x += 230f * dt
                if (x > g.camX + g.viewW + 40) removed = true
            }
        }
        y = groundY - 14
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val brown = 0xFFB07840.toInt()
        val dark = 0xFF6A4420.toInt()
        val run = mode != 1
        val leg = if (run && (t * 14).toInt() % 2 == 0) 2f else 0f
        // Wagging tail
        val wag = sin(t * (if (run) 20 else 14)) * 3f
        gfx.poly(floatArrayOf(px + 1, px - 4, px + 2), floatArrayOf(y + 5, y + 1 + wag, y + 7), brown)
        gfx.rect(px + 3, y + 10 - leg, 3f, 4f + leg, dark)
        gfx.rect(px + 13, y + 10 + leg - 2, 3f, 4f - leg + 2, dark)
        gfx.oval(px + 1, y + 3, 17f, 9f, brown)
        gfx.oval(px + 12, y - 3, 10f, 9f, brown)
        gfx.oval(px + 18, y + 1, 5f, 4f, 0xFFD8A870.toInt())
        gfx.oval(px + 21, y + 1, 2.5f, 2f, 0xFF101010.toInt())
        gfx.oval(px + 16, y - 1, 2.5f, 2.5f, 0xFF101010.toInt())
        gfx.oval(px + 11, y - 3, 4f, 8f, dark)
        gfx.rect(px + 12, y + 4, 7f, 1.5f, 0xFFE52521.toInt())
        if (mode == 1) {
            // Tongue out and a big arrow pointing the way
            gfx.rect(px + 20, y + 4, 2f, 3f, 0xFFFF7AA8.toInt())
            val bob = sin(t * 8) * 3f
            val ax = px + 34 + bob
            val ay = y - 4
            gfx.rect(ax, ay - 3, 16f, 6f, 0xFFFFD21F.toInt())
            gfx.poly(floatArrayOf(ax + 16, ax + 26, ax + 16), floatArrayOf(ay - 8, ay, ay + 8), 0xFFFFD21F.toInt())
        }
        gfx.shadowText("DENNIS", px + 10, y - 9, 6f, 0xFFFFE8A0.toInt(), 1)
    }
}

/** A minecart on rails. Press the action button next to it to ride; JUMP hops out. */
class Minecart(x: Float, bottom: Float) : Thing(x, bottom - 12, 22f, 12f) {
    var rider: Hero? = null
    private var t = 0f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (rider == null) { vx = 0f; return }
        val ahead = if (vx >= 0) x + w + 2 else x - 2
        val onRail = g.level[tileOf(ahead), tileOf(bottom - 2)] == Tile.RAIL
        if (!onRail) {
            g.leaveCart(this, true)
            return
        }
        vx = approach(vx, 230f, 400f * dt)
        x += vx * dt
        // Smash any ordinary bad guy in the way.
        for (f in g.foes) if (!f.dying && !f.boss && f.active && f.overlaps(this)) f.hit(g, 99)
        if ((t * 8).toInt() % 3 == 0 && abs(vx) > 100) g.addParticle(Sparkle(x + 4, bottom, -vx * 0.2f, -20f, 0xFFFFD21F.toInt()))
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        gfx.rect(px, y, 22f, 9f, 0xFF6A6A78.toInt())
        gfx.rect(px + 1.5f, y + 1.5f, 19f, 3f, 0xFF3A3A48.toInt())
        gfx.rect(px, y, 22f, 1.5f, 0xFF9A9AA8.toInt())
        val spin = (t * 20).toInt() % 2 == 0
        for (wx in floatArrayOf(px + 3, px + 14)) {
            gfx.oval(wx, y + 7, 5f, 5f, 0xFF202020.toInt())
            gfx.rect(wx + (if (spin) 2f else 1f), y + 9, 1f, 1f, 0xFF9A9AA8.toInt())
        }
        if (rider == null && abs(g.leader.cx - cx) < 24f && g.state == TeamGame.State.PLAY && (g.time * 2).toInt() % 2 == 0) {
            gfx.shadowText("RIDE!", cx + ox, y - 26, 7f, 0xFFFFD21F.toInt(), 1)
        }
    }
}

/** A spike trap the team crafted and set down. Bad guys that walk into it get hurt. */
class SpikeTrap(x: Float, bottom: Float) : Thing(x, bottom - 6, 16f, 6f) {
    private var uses = 5
    private var cool = 0f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        cool -= dt
        if (cool > 0) return
        for (f in g.foes) {
            if (!f.dying && f.active && f.overlaps(this)) {
                f.hit(g, if (f.boss) 3 else 4)
                g.sfx(Sound.STOMP)
                cool = 0.5f
                if (--uses <= 0) { removed = true; g.puff(cx, y) }
                return
            }
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) = TeamArt.spikes(gfx, x + ox, bottom, 0xFFC8C8D8.toInt())
}

/** Thrown TNT: lands, shows the blast area as a flashing red grid, and goes off a moment later. */
class ThrownTnt(x: Float, y: Float, dir: Int) : Thing(x, y, 10f, 10f) {
    companion object {
        const val FUSE = 1.1f
        /** The blast covers RADIUS tiles in every direction (a 5 x 5 grid). */
        const val RADIUS = 2
    }

    private var fuse = -1f
    private var t = 0f
    init { vx = dir * 170f; vy = -230f; active = true }

    val landed get() = fuse >= 0

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (fuse < 0) {
            vy = min(vy + GRAVITY * dt, MAX_FALL)
            if (g.level.moveX(this, vx * dt)) vx = -vx * 0.3f
            if (g.level.moveY(this, vy * dt) == 1 || t > 1.4f) {
                vx = 0f
                fuse = FUSE
                g.sfx(Sound.KICK)
            }
            return
        }
        fuse -= dt
        if (fuse <= 0) {
            removed = true
            g.tntBlast(tileOf(cx), tileOf(cy))
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        if (fuse >= 0) {
            // Show exactly which tiles will blow up.
            val blink = (fuse * 12).toInt() % 2 == 0
            val tx = tileOf(cx)
            val ty = tileOf(cy)
            for (dy in -RADIUS..RADIUS) for (dx in -RADIUS..RADIUS) {
                val gx = (tx + dx) * TILE + ox
                val gy = (ty + dy) * TILE.toFloat()
                gfx.rect(gx, gy, 16f, 16f, if (blink) 0x55FF2020 else 0x33FF8080)
                gfx.rect(gx, gy, 16f, 1f, 0xAAFF2020.toInt())
                gfx.rect(gx, gy, 1f, 16f, 0xAAFF2020.toInt())
            }
            gfx.shadowText(String.format("%.1f", fuse.coerceAtLeast(0f)), cx + ox, y - 6, 8f, 0xFFFF4040.toInt(), 1)
        }
        val px = x + ox
        gfx.rect(px, y, 10f, 10f, 0xFFE53A1E.toInt())
        gfx.rect(px, y + 3.5f, 10f, 3f, 0xFFFFFFFF.toInt())
        gfx.rect(px + 4.5f, y - 3, 1f, 3f, 0xFF404040.toInt())
        if ((t * 16).toInt() % 2 == 0) gfx.oval(px + 3.5f, y - 5, 3f, 3f, 0xFFFFD21F.toInt())
    }
}

/** An air bubble from a vent. Swim into it to refill your air. */
class Bubble(x: Float, y: Float) : Thing(x, y, 10f, 10f) {
    private var t = Random.nextFloat() * 3
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        y -= 30f * dt
        x += sin(t * 3) * 0.4f
        if (y < 30f) removed = true
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        gfx.oval(x + ox, y, 10f, 10f, 0x88BFE8FF.toInt())
        gfx.oval(x + ox + 1, y + 1, 8f, 8f, 0x33FFFFFF)
        gfx.oval(x + ox + 2, y + 2, 3f, 3f, 0xDDFFFFFF.toInt())
    }
}

// ============================================================================ New bad guys

/** A fish that swims back and forth underwater. */
class Fish(x: Float, y: Float) : Foe(x + 1, y - 12, 14f, 10f, 2) {
    private var t = Random.nextFloat() * 6
    private val homeX = x
    private val homeY = y - 12
    private val color = listOf(0xFFFF7A3A.toInt(), 0xFFFFD21F.toInt(), 0xFFE84A8A.toInt())[Random.nextInt(3)]
    init { vx = -38f }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        x += vx * dt
        if (x < homeX - 50 || x > homeX + 50) vx = -vx
        y = homeY + sin(t * 2) * 8f
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val f = vx > 0
        val tailX = if (f) px - 3 else px + 13
        gfx.poly(floatArrayOf(tailX, tailX + (if (f) 4f else -4f), tailX), floatArrayOf(y, y + 5, y + 10), color)
        gfx.oval(px, y, 14f, 10f, color)
        gfx.oval(if (f) px + 9 else px + 2, y + 2, 3f, 3f, 0xFFFFFFFF.toInt())
        gfx.oval(if (f) px + 10 else px + 2.5f, y + 2.5f, 1.5f, 1.5f, 0xFF101010.toInt())
        drawFlash(gfx, ox)
    }
}

/**
 * Endermen: tall, tough, and they teleport. Sometimes they dodge a shot by teleporting
 * away, but SUPER moves and TNT always hit.
 */
class Enderman(x: Float, bottom: Float) : Foe(x + 2, bottom - 34, 12f, 34f, 18) {
    private var t = 0f
    private var tele = 3f + Random.nextFloat()
    private var angry = false
    init { vx = -18f }

    override val stompable = true

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        val l = g.leader
        if (!angry && abs(l.cx - cx) < 130f) angry = true
        if (angry) vx = if (l.cx < cx) -32f else 32f
        walk(g, dt)
        tele -= dt
        if (angry && tele <= 0) {
            teleportNear(g, l.cx + (if (Random.nextBoolean()) 70f else -70f))
            tele = 3.2f + Random.nextFloat() * 1.5f
        }
    }

    private fun teleportNear(g: TeamGame, tx: Float) {
        repeat(8) { g.addParticle(Sparkle(cx, cy, Random.nextFloat() * 80 - 40, Random.nextFloat() * 80 - 40, 0xFFC060FF.toInt())) }
        // Land on the first solid ground below that spot.
        val col = tileOf(tx)
        val ty = (2 until ROWS).firstOrNull { Tile.solid(g.level[col, it]) && !Tile.solid(g.level[col, it - 1]) && !Tile.solid(g.level[col, it - 2]) }
            ?: return
        x = col * TILE + 2f
        y = ty * TILE - h
        vy = 0f
        g.sfx(Sound.ZAP)
        repeat(8) { g.addParticle(Sparkle(cx, cy, Random.nextFloat() * 80 - 40, Random.nextFloat() * 80 - 40, 0xFFC060FF.toInt())) }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean {
        if (dying) return false
        if (dmg < 5 && Random.nextFloat() < 0.3f) {
            g.addParticle(FloatText(cx, y, "DODGE!", 0xFFC060FF.toInt()))
            teleportNear(g, cx + (if (Random.nextBoolean()) 90f else -90f))
            return false
        }
        angry = true
        return super.hit(g, dmg)
    }

    /** Endermen are too tall to squash: a stomp only does 2 damage. */
    override fun stomp(g: TeamGame, stomper: Hero) { super.hit(g, 2) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val black = 0xFF141018.toInt()
        val step = if ((t * 5).toInt() % 2 == 0) 1.5f else -1.5f
        gfx.rect(px + 2, y + 18, 2.5f, 16f + step, black)
        gfx.rect(px + 7.5f, y + 18, 2.5f, 16f - step, black)
        gfx.rect(px + 1, y + 8, 10f, 11f, black)
        gfx.rect(px - 1, y + 9, 2f, 18f, black)
        gfx.rect(px + 11, y + 9, 2f, 18f, black)
        gfx.rect(px, y, 12f, 9f, black)
        val eye = if (angry) 0xFFFF4AFF.toInt() else 0xFFC060FF.toInt()
        gfx.rect(px + 1, y + 4, 4f, 1.5f, eye)
        gfx.rect(px + 7, y + 4, 4f, 1.5f, eye)
        if (angry) gfx.rect(px + 3, y + 6.5f, 6f, 2f, 0xFFFFFFFF.toInt())
        if ((t * 6).toInt() % 3 == 0) gfx.rect(px + Random.nextFloat() * 12, y + Random.nextFloat() * 30, 2f, 2f, 0xAAC060FF.toInt())
        drawFlash(gfx, ox)
        gfx.shadowText("ENDERMAN", cx + ox, y - 11, 5f, 0xFFE0A0FF.toInt(), 1)
    }
}

/** The Warden's sonic boom: a ring of sound that goes through walls. */
class SonicBoom(x: Float, y: Float, private val dir: Int) : Foe(x, y, 18f, 18f, 1) {
    private var t = 0f
    override val stompable = false
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        x += dir * 170f * dt
        if (t > 3f) removed = true
    }

    override fun hit(g: TeamGame, dmg: Int) = false

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        for (i in 0 until 3) {
            val r = 6f + i * 4f + sin(t * 20 + i) * 1.5f
            gfx.oval(cx + ox - r, cy - r, r * 2, r * 2, argb(140 - i * 35, 0x3AE8F0))
        }
        gfx.oval(cx + ox - 4, cy - 4, 8f, 8f, 0xFFE8FFFF.toInt())
    }
}

/**
 * THE WARDEN: a huge, blind cave monster. He hears you when you run or jump and
 * charges up sonic booms that fly through walls.
 */
class Warden(x: Float, bottom: Float) : Foe(x - 8, bottom - 46, 32f, 46f, 90) {
    var awake = false
        private set
    private var t = 0f
    private var boomT = 2.5f
    private var charge = 0f
    private var heardX = 0f
    private var hurtT = 0f
    private var facing = -1
    val homeX = x
    override val boss = true

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        hurtT -= dt
        if (updateDying(g, dt)) return
        val l = g.leader
        if (!awake) {
            if (l.cx > homeX - g.viewW * 0.45f) {
                awake = true
                heardX = l.cx
                g.onBossEngaged(this)
            }
            return
        }
        // He listens: running and jumping make noise.
        val noisy = abs(l.vx) > 80f || !l.onGround
        if (noisy) heardX = l.cx
        val angry = hp < maxHp / 2
        if (charge > 0) {
            charge -= dt
            vx = 0f
            if (charge <= 0) {
                g.spawn(SonicBoom(if (facing > 0) x + w else x - 18, l.cy - 9, facing))
                g.sfx(Sound.BOSS_FIRE)
                g.shake = 0.3f
            }
        } else {
            facing = if (heardX < cx) -1 else 1
            val sp = if (noisy) (if (angry) 60f else 46f) else 18f
            vx = if (abs(heardX - cx) > 8) facing * sp else 0f
            boomT -= dt
            if (boomT <= 0) {
                charge = 1.1f
                facing = if (l.cx < cx) -1 else 1
                boomT = if (angry) 4f else 5.5f
                g.say(cx, y - 6, "RRRROAR!", 1f)
            }
        }
        walk(g, dt)
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean {
        if (!awake || dying) return false
        if (hurtT > 0 && dmg < 5) return true
        hp -= dmg
        hurtT = 0.3f
        flash = 0.2f
        g.sfx(Sound.BOSS_HIT)
        if (hp <= 0) {
            hp = 0
            knockOut(g)
            g.addScore(15000, cx, y)
            g.onBossBeaten(this)
        }
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 1) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val body = 0xFF0E3A44.toInt()
        val dark = 0xFF072028.toInt()
        val step = if (abs(vx) > 1 && (t * 4).toInt() % 2 == 0) 2f else 0f
        gfx.rect(px + 5, y + 32 - step, 8f, 14f + step, dark)
        gfx.rect(px + 19, y + 32 + step - 2, 8f, 14f - step + 2, dark)
        gfx.rect(px + 2, y + 12, 28f, 22f, body)
        gfx.rect(px - 3, y + 14, 6f, 22f, body)
        gfx.rect(px + 29, y + 14, 6f, 22f, body)
        // Glowing soul in his chest, brighter while charging a sonic boom
        val glow = if (charge > 0) 255 else (150 + sin(t * 3) * 60).toInt()
        gfx.oval(px + 9, y + 15, 14f, 12f, argb(glow, 0x3AE8F0))
        gfx.oval(px + 13, y + 18, 6f, 6f, argb(glow, 0xE8FFFF))
        // Head with antler-like "ears", no eyes
        gfx.rect(px + 5, y, 22f, 13f, body)
        gfx.poly(floatArrayOf(px + 5, px - 4, px + 5), floatArrayOf(y + 2, y - 6, y + 8), 0xFF1E6A78.toInt())
        gfx.poly(floatArrayOf(px + 27, px + 36, px + 27), floatArrayOf(y + 2, y - 6, y + 8), 0xFF1E6A78.toInt())
        gfx.rect(px + 9, y + 8, 14f, 2f, dark)
        if (charge > 0) {
            for (i in 0 until 3) {
                val r = 10f + ((t * 30 + i * 10) % 30)
                gfx.oval(px + 16 - r, y + 21 - r, r * 2, r * 2, argb(60, 0x3AE8F0))
            }
        }
        drawFlash(gfx, ox)
    }
}

/** A purple fireball breathed by the Ender Dragon. */
class DragonBall(x: Float, y: Float, tx: Float, ty: Float) : Foe(x, y, 12f, 12f, 1) {
    private var t = 0f
    override val stompable = false
    init {
        active = true
        val d = hypot(tx - x, ty - y).coerceAtLeast(1f)
        vx = (tx - x) / d * 110f
        vy = (ty - y) / d * 110f
    }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        x += vx * dt
        y += vy * dt
        if (t > 5f || g.level.solidAt(cx, cy)) { removed = true; g.puff(cx, cy) }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        gfx.oval(x + ox - 2, y - 2, 16f, 16f, 0x66C060FF)
        gfx.oval(x + ox, y, 12f, 12f, 0xFF8A3AD8.toInt())
        gfx.oval(x + ox + 3, y + 3, 6f, 6f, 0xFFE0A0FF.toInt())
    }
}

/** End crystals on the pillars heal the Ender Dragon until you break them. */
class EndCrystal(x: Float, bottom: Float) : Foe(x + 1, bottom - 14, 14f, 14f, 4) {
    private var t = Random.nextFloat() * 3
    override val stompable = true
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (updateDying(g, dt)) return
    }

    override fun touch(g: TeamGame) {}

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 2) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        if (dying) return
        val bob = sin(t * 3) * 2f
        val px = x + ox
        gfx.oval(px - 2, y - 2 + bob, 18f, 18f, 0x55C060FF)
        gfx.poly(floatArrayOf(px + 7, px + 14, px + 7, px), floatArrayOf(y + bob, y + 7 + bob, y + 14 + bob, y + 7 + bob), 0xFFE0A0FF.toInt())
        gfx.poly(floatArrayOf(px + 7, px + 11, px + 7, px + 3), floatArrayOf(y + 3 + bob, y + 7 + bob, y + 11 + bob, y + 7 + bob), 0xFFFFFFFF.toInt())
        drawFlash(gfx, ox)
    }
}

/**
 * THE ENDER DRAGON, the final boss. Flies around the arena breathing purple fireballs,
 * dives at the team, and lands now and then (that's the time to hit it hard!). Any end
 * crystals still standing slowly heal it.
 */
class EnderDragon(x: Float, y: Float) : Foe(x, y, 64f, 30f, 90) {
    enum class Mode { WAIT, FLY, DIVE, PERCH, BEATEN }

    var mode = Mode.WAIT
        private set
    private var t = 0f
    private var modeT = 0f
    private var fireT = 2.5f
    private var healT = 0f
    private var hurtT = 0f
    private var facing = -1
    private var diveX = 0f
    private var diveY = 0f
    val homeX = x
    override val boss = true
    override val stompable = true
    val engaged get() = mode != Mode.WAIT && mode != Mode.BEATEN

    private fun setMode(m: Mode) { mode = m; modeT = 0f }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        modeT += dt
        hurtT -= dt
        if (flash > 0) flash -= dt
        val l = g.leader
        val arenaL = g.camX + 20f
        val arenaR = g.camX + g.viewW - w - 20f
        when (mode) {
            Mode.WAIT -> {
                y += sin(t * 2) * 0.3f
                if (l.cx > homeX - g.viewW * 0.45f) {
                    setMode(Mode.FLY)
                    g.onBossEngaged(this)
                }
            }
            Mode.FLY -> {
                // A big figure-eight across the sky.
                val mid = (arenaL + arenaR) / 2
                val tx = mid + sin(t * 0.7f) * (arenaR - arenaL) / 2
                val ty = 60f + sin(t * 1.4f) * 25f
                facing = if (tx < x) -1 else 1
                x += (tx - x).coerceIn(-120f * dt, 120f * dt)
                y += (ty - y).coerceIn(-80f * dt, 80f * dt)
                fireT -= dt
                if (fireT <= 0) {
                    g.spawn(DragonBall(cx, cy, l.cx, l.cy))
                    if (hp < maxHp / 2) g.spawn(DragonBall(cx, cy, l.cx + 50, l.cy - 30))
                    g.sfx(Sound.BOSS_FIRE)
                    fireT = if (hp < maxHp / 2) 2f else 2.8f
                }
                if (modeT > 7f) {
                    diveX = l.cx - w / 2
                    diveY = l.bottom - h
                    setMode(Mode.DIVE)
                    g.say(cx, y - 4, "SCREEEE!", 1f)
                }
            }
            Mode.DIVE -> {
                val dx = diveX - x
                val dy = diveY - y
                val d = hypot(dx, dy)
                facing = if (dx < 0) -1 else 1
                if (d < 6f || modeT > 2f) {
                    y = min(y, diveY)
                    setMode(Mode.PERCH)
                    g.shake = 0.4f
                    g.sfx(Sound.BUMP)
                } else {
                    x += dx / d * 190f * dt
                    y += dy / d * 190f * dt
                }
            }
            Mode.PERCH -> {
                // Resting on the ground: hit it now!
                if (modeT > 3.2f) setMode(Mode.FLY)
            }
            Mode.BEATEN -> {
                y -= 30f * dt
                if (Random.nextFloat() < dt * 20) g.addParticle(Sparkle(cx + Random.nextFloat() * 60 - 30, cy, 0f, -60f, 0xFFE0A0FF.toInt()))
                if (modeT > 3f) removed = true
            }
        }
        x = x.coerceIn(g.camX, g.camX + g.viewW - w)
        // Crystals heal the dragon.
        if (engaged) {
            healT += dt
            if (healT > 1.5f) {
                healT = 0f
                val crystals = g.foes.count { it is EndCrystal && !it.dying }
                if (crystals > 0 && hp < maxHp) {
                    hp = min(maxHp, hp + crystals)
                    for (c in g.foes) if (c is EndCrystal && !c.dying) g.addParticle(Bolt(c.cx, c.cy, cx, cy, 0xFFE0A0FF.toInt()))
                }
            }
        }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean {
        if (mode == Mode.WAIT || mode == Mode.BEATEN) return false
        if (hurtT > 0 && dmg < 5) return true
        // It's tougher in the air: hits do double damage while it's resting on the ground.
        val real = if (mode == Mode.PERCH) dmg * 2 else dmg
        hp -= real
        hurtT = 0.25f
        flash = 0.2f
        g.sfx(Sound.BOSS_HIT)
        if (hp <= 0) {
            hp = 0
            setMode(Mode.BEATEN)
            dying = true
            g.addScore(50000, cx, y)
            g.onBossBeaten(this)
        }
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 2) }
    override fun touch(g: TeamGame) { if (mode != Mode.BEATEN) g.hurt() }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val black = if (flash > 0) 0xFFFFFFFF.toInt() else 0xFF1A1420.toInt()
        val dark = 0xFF2E2438.toInt()
        val f = facing.toFloat()
        val cx0 = px + w / 2
        val flap = if (mode == Mode.PERCH) 4f else sin(t * 6) * 14f
        // Wings
        gfx.poly(floatArrayOf(cx0 - 8, cx0 - 34, cx0 - 4), floatArrayOf(y + 10, y - 10 - flap, y + 6), dark)
        gfx.poly(floatArrayOf(cx0 + 8, cx0 + 34, cx0 + 4), floatArrayOf(y + 10, y - 10 - flap, y + 6), dark)
        gfx.poly(floatArrayOf(cx0 - 6, cx0 - 28, cx0 - 4), floatArrayOf(y + 10, y - 6 - flap, y + 7), 0xFF4A3A5A.toInt())
        gfx.poly(floatArrayOf(cx0 + 6, cx0 + 28, cx0 + 4), floatArrayOf(y + 10, y - 6 - flap, y + 7), 0xFF4A3A5A.toInt())
        // Tail
        gfx.poly(floatArrayOf(cx0 - f * 10, cx0 - f * 34, cx0 - f * 10), floatArrayOf(y + 10, y + 16 + sin(t * 4) * 4, y + 18), black)
        // Body and spikes
        gfx.oval(cx0 - 16, y + 6, 32f, 16f, black)
        for (i in -2..2) gfx.poly(floatArrayOf(cx0 + i * 6 - 2, cx0 + i * 6, cx0 + i * 6 + 2), floatArrayOf(y + 8, y + 2, y + 8), dark)
        gfx.rect(cx0 - 10, y + 20, 4f, 8f, black)
        gfx.rect(cx0 + 6, y + 20, 4f, 8f, black)
        // Neck and head
        val hx = cx0 + f * 22
        gfx.oval(cx0 + f * 12 - 5, y + 6, 10f, 8f, black)
        gfx.oval(hx - 8, y + 2, 16f, 10f, black)
        gfx.rect(hx + f * 6 - 3, y + 6, 6f, 4f, black)
        gfx.rect(hx + f * 2 - 2, y + 4, 4f, 2f, 0xFFE040FF.toInt())
        gfx.poly(floatArrayOf(hx - f * 4, hx - f * 7, hx - f * 2), floatArrayOf(y + 3, y - 3, y + 3), dark)
        if (mode == Mode.FLY && fireT < 0.4f) gfx.oval(hx + f * 9 - 4, y + 4, 8f, 6f, 0xFFC060FF.toInt())
        if (mode == Mode.PERCH) gfx.shadowText("HIT IT NOW!", cx0, y - 14, 8f, 0xFFFFD21F.toInt(), 1)
    }
}
