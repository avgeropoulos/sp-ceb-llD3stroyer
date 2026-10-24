package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Voice
import com.roccoquest.game.argb
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ============================================================================ World 7 bad guys

/** Something a bad guy throws in an arc: a snowball or a hammer. */
class Lob(x: Float, y: Float, tx: Float, private val hammer: Boolean) : Foe(x, y, 9f, 9f, 1), Projectile {
    private var t = 0f
    override val stompable = false
    override val showBar get() = false
    init {
        active = true
        vx = ((tx - x) / 1.0f).coerceIn(-170f, 170f)
        vy = if (hammer) -300f else -230f
    }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (dying) { removed = true; return }
        vy = min(vy + GRAVITY * 0.55f * dt, 320f)
        x += vx * dt
        y += vy * dt
        if ((!hammer && g.level.solidAt(cx, y + h)) || t > 3.5f) { removed = true; if (!hammer) g.puff(cx, cy) }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        if (!hammer) {
            gfx.oval(px, y, 9f, 9f, 0xFFF4F8FF.toInt())
            gfx.oval(px + 2, y + 2, 3f, 3f, 0xFFFFFFFF.toInt())
            return
        }
        // A spinning hammer
        val a = t * 14f
        val hx = px + 4.5f + cos(a) * 4f
        val hy = y + 4.5f + sin(a) * 4f
        gfx.poly(floatArrayOf(px + 4.5f - cos(a) * 4, hx + sin(a), hx - sin(a)), floatArrayOf(y + 4.5f - sin(a) * 4, hy - cos(a), hy + cos(a)), 0xFF8A5A2A.toInt())
        gfx.oval(hx - 3, hy - 3, 6f, 6f, 0xFF8A8A9A.toInt())
    }
}

/** A snowman that throws snowballs. */
class Snowman(x: Float, bottom: Float) : Foe(x, bottom - 22, 16f, 22f, 5) {
    private var t = Random.nextFloat() * 2f
    private var throwT = 2f + Random.nextFloat() * 2f

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        val l = g.leader
        throwT -= dt
        if (throwT <= 0f) {
            throwT = 3.2f
            if (abs(l.cx - cx) < 220f) g.spawn(Lob(cx - 4, y, l.cx, false))
        }
        vx = 0f
        walk(g, dt)
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val look = if (g.leader.cx < cx) -1.5f else 1.5f
        gfx.oval(px, y + 9, 16f, 13f, 0xFFF4F8FF.toInt())
        gfx.oval(px + 2, y + 1, 12f, 11f, 0xFFF4F8FF.toInt())
        gfx.oval(px + 5 + look, y + 4, 2f, 2f, 0xFF1A1A1A.toInt()); gfx.oval(px + 9 + look, y + 4, 2f, 2f, 0xFF1A1A1A.toInt())
        gfx.poly(floatArrayOf(px + 8 + look, px + 8 + look * 4, px + 8 + look), floatArrayOf(y + 6.5f, y + 7.5f, y + 8.5f), 0xFFFF8A2A.toInt())
        // Top hat and scarf
        gfx.rect(px + 3, y - 1, 10f, 2f, 0xFF1A1A1A.toInt()); gfx.rect(px + 5, y - 7, 6f, 6f, 0xFF1A1A1A.toInt())
        gfx.rect(px + 2, y + 10, 12f, 2.5f, 0xFFE84A4A.toInt())
        for (k in 0 until 2) gfx.oval(px + 7, y + 14 + k * 4f, 2f, 2f, 0xFF1A1A1A.toInt())
        drawFlash(gfx, ox)
    }
}

/** A penguin that slides along on its belly, fast! */
class Penguin(x: Float, bottom: Float) : Foe(x, bottom - 12, 18f, 12f, 3) {
    private var t = 0f
    init { vx = -110f }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        walk(g, dt)
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val f = if (vx < 0) -1f else 1f
        gfx.oval(px, y + 2, 18f, 10f, 0xFF1A1A2A.toInt())
        gfx.oval(px + 3, y + 5, 12f, 6f, 0xFFF4F4F4.toInt())
        val hx = if (f > 0) px + 13 else px - 1
        gfx.oval(hx, y, 7f, 7f, 0xFF1A1A2A.toInt())
        gfx.oval(hx + 3 + f, y + 2, 2f, 2f, 0xFFFFFFFF.toInt())
        gfx.poly(floatArrayOf(hx + 3.5f + f * 3, hx + 3.5f + f * 7, hx + 3.5f + f * 3), floatArrayOf(y + 3, y + 4.5f, y + 6), 0xFFFFA82A.toInt())
        gfx.rect(px + (if (f > 0) -4f else 18f), y + 9, 4f, 1.5f, 0x99FFFFFF.toInt())
        drawFlash(gfx, ox)
    }
}

/** HAMMER HANK: hops around and throws hammers. */
class HammerHank(x: Float, bottom: Float) : Foe(x, bottom - 22, 14f, 22f, 6) {
    private var t = Random.nextFloat() * 3f
    private var throwT = 1.5f
    private var hopT = 2f
    private var dir = -1f
    private var arm = 0f

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        if (arm > 0) arm -= dt
        val l = g.leader
        if ((t % 1.6f) < dt) dir = -dir
        vx = dir * 22f
        hopT -= dt
        if (hopT <= 0f && onGround) { vy = -230f; hopT = 2.5f + Random.nextFloat() * 2f }
        throwT -= dt
        if (throwT <= 0f) {
            throwT = 2.2f
            if (abs(l.cx - cx) < 240f) { g.spawn(Lob(cx - 4, y - 4, l.cx, true)); arm = 0.25f }
        }
        walk(g, dt)
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val f = if (g.leader.cx < cx) -1f else 1f
        // A turtle in a helmet, holding a hammer up high
        gfx.oval(px + 1, y + 9, 12f, 11f, 0xFF3CB84A.toInt())
        gfx.oval(px + 3, y + 11, 8f, 7f, 0xFFF4E8A0.toInt())
        gfx.rect(px + 2, y + 19, 4f, 3f, 0xFFF4E8A0.toInt()); gfx.rect(px + 8, y + 19, 4f, 3f, 0xFFF4E8A0.toInt())
        gfx.oval(px + 2, y + 2, 10f, 9f, 0xFFF4E8A0.toInt())
        gfx.oval(px + 1, y, 12f, 6f, 0xFF3A6AC8.toInt())
        gfx.oval(px + 6 + f * 2, y + 4, 2f, 2.5f, 0xFF1A1A1A.toInt())
        if (arm <= 0f) {
            val hx = px + 7 - f * 6
            gfx.rect(hx, y - 6, 1.5f, 8f, 0xFF8A5A2A.toInt())
            gfx.rect(hx - 2.5f, y - 8, 6.5f, 4f, 0xFF8A8A9A.toInt())
        }
        drawFlash(gfx, ox)
    }
}

/** CHOMPY: a big chained ball with teeth. It lunges when you get close. */
class Chompy(x: Float, bottom: Float) : Foe(x, bottom - 16, 16f, 16f, 10) {
    private val postX = x + 8f
    private val postY = bottom
    private var t = Random.nextFloat()
    private var lunge = 0f
    private var rest = 1f
    private var lx = 0f
    private var ly = 0f
    override val stompable = false

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        val l = g.leader
        rest -= dt
        if (lunge > 0f) {
            lunge -= dt
            x += lx * dt; y += ly * dt
            val dx = cx - postX; val dy = cy - (postY - 8f)
            val d = hypot(dx, dy)
            if (d > 80f) { x -= dx / d * (d - 80f); y -= dy / d * (d - 80f) }
            if (lunge <= 0f) rest = 1.6f
        } else {
            // Back to the post, bouncing in place
            x += (postX - 8f - x) * min(1f, dt * 4f)
            y += (postY - 16f - abs(sin(t * 6)) * 6f - y) * min(1f, dt * 6f)
            if (rest <= 0f && hypot(l.cx - cx, l.cy - cy) < 110f) {
                val d = hypot(l.cx - cx, l.cy - cy).coerceAtLeast(1f)
                lx = (l.cx - cx) / d * 260f; ly = (l.cy - cy) / d * 260f
                lunge = 0.35f
                g.sfx(Sound.BUMP)
            }
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        // The chain
        for (k in 1..4) {
            val kx = postX + (cx - postX) * k / 5f
            val ky = postY - 3f + (cy - postY + 3f) * k / 5f
            gfx.oval(kx + ox - 2, ky - 2, 4f, 4f, 0xFF6A6A7A.toInt())
        }
        gfx.rect(postX + ox - 2, postY - 6, 4f, 6f, 0xFF8A5A2A.toInt())
        val px = x + ox
        val f = if (g.leader.cx < cx) -1f else 1f
        gfx.oval(px, y, 16f, 16f, 0xFF1A1A22.toInt())
        gfx.oval(px + 7 + f * 2, y + 3, 5f, 5f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 8.5f + f * 3, y + 4.5f, 2f, 2f, 0xFF1A1A1A.toInt())
        val open = if (lunge > 0f) 5f else 2.5f
        gfx.oval(px + 8 + f * 3 - 4, y + 9, 8f, open, 0xFFB01A1A.toInt())
        for (k in 0 until 3) gfx.poly(floatArrayOf(px + 5 + f * 3 + k * 2.5f, px + 6 + f * 3 + k * 2.5f, px + 7 + f * 3 + k * 2.5f), floatArrayOf(y + 9, y + 11, y + 9), 0xFFFFFFFF.toInt())
        drawFlash(gfx, ox)
    }
}

/** A flying bullet with angry eyes, fired by a ZOOMER launcher. Stomp it! */
class Zoomer(x: Float, y: Float, private val dir: Int) : Foe(x, y, 16f, 12f, 1) {
    private var t = 0f
    init { active = true; vx = dir * 130f }
    override val showBar get() = false

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        x += vx * dt
        if (t > 6f) removed = true
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val f = dir.toFloat()
        gfx.oval(px, y, 16f, 12f, 0xFF1A1A22.toInt())
        gfx.rect(if (f > 0) px else px + 12, y, 4f, 12f, 0xFF3A3A44.toInt())
        gfx.oval(px + 8 + f * 2, y + 2, 4f, 4f, 0xFFFFFFFF.toInt())
        gfx.rect(px + 7 + f * 2, y + 2, 6f, 1.2f, 0xFF1A1A1A.toInt())
        gfx.rect(if (f > 0) px - 6 else px + 16, y + 5, 6f, 1.5f, 0x66FFFFFF)
    }
}

/** The ZOOMER launcher: a black cannon block that fires Zoomers at you. */
class ZoomerLauncher(x: Float, bottom: Float) : Foe(x, bottom - 20, 16f, 20f, 12) {
    private var fireT = 2f
    override val stompable = false
    override fun touch(g: TeamGame) {}

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        val l = g.leader
        fireT -= dt
        val d = abs(l.cx - cx)
        if (fireT <= 0f && d in 50f..320f) {
            fireT = 3.6f
            val dir = if (l.cx < cx) -1 else 1
            g.spawn(Zoomer(if (dir < 0) x - 16 else x + w, y + 1, dir))
            g.sfx(Sound.BOSS_FIRE)
            g.puff(if (dir < 0) x else x + w, y + 6)
        }
        vx = 0f
        walk(g, dt)
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        gfx.rect(px, y + 4, 16f, 16f, 0xFF2A2A34.toInt())
        gfx.rect(px - 1, y, 18f, 8f, 0xFF1A1A22.toInt())
        gfx.oval(px + 5, y + 10, 6f, 6f, 0xFFE8E8F0.toInt())
        gfx.rect(px + 7.5f, y + 9, 1f, 8f, 0xFF1A1A22.toInt())
        drawFlash(gfx, ox)
    }
}

/** A wobbly candy jelly that hops toward you. */
class Gummy(x: Float, bottom: Float) : Foe(x, bottom - 12, 12f, 12f, 4) {
    private var t = Random.nextFloat() * 2f
    private var hopT = 1f + Random.nextFloat()
    private val color = listOf(0xFFFF4A6A, 0xFF4AD86A, 0xFFFFC82A, 0xFF8A6AFF, 0xFFFF8A2A)[Random.nextInt(5)].toInt()

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        hopT -= dt
        if (hopT <= 0f && onGround) {
            hopT = 1.3f
            vy = -200f
            vx = if (g.leader.cx < cx) -60f else 60f
        }
        if (onGround) vx *= 0.8f
        walk(g, dt)
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val squish = if (onGround) sin(t * 10) * 1.5f else -1.5f
        gfx.oval(px - squish / 2, y + squish, 12f + squish, 12f - squish, argb(220, color and 0xFFFFFF))
        gfx.oval(px + 2, y + 2 + squish, 3f, 3f, 0x99FFFFFF.toInt())
        gfx.oval(px + 3, y + 5, 2f, 2f, 0xFF1A1A1A.toInt()); gfx.oval(px + 7, y + 5, 2f, 2f, 0xFF1A1A1A.toInt())
        drawFlash(gfx, ox)
    }
}

// ============================================================================ ROCK STAR KRAG

/** A musical note that ROCK STAR KRAG blasts out of his guitar. It wiggles toward you. */
class NoteShot(x: Float, y: Float, private val dir: Int, private val wiggle: Float) : Foe(x, y, 10f, 12f, 1), Projectile {
    private var t = 0f
    private val baseY = y
    override val stompable = false
    override val showBar get() = false
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        x += dir * 120f * dt
        y = baseY + sin(t * 6f + wiggle) * 14f
        if (t > 4f) removed = true
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val c = solidRainbow(t * 0.5f + wiggle, 0f)
        gfx.oval(px, y + 7, 6f, 5f, c)
        gfx.rect(px + 4.5f, y, 1.5f, 9f, c)
        gfx.poly(floatArrayOf(px + 6, px + 10, px + 6), floatArrayOf(y, y + 2, y + 4), c)
    }
}

/** A shockwave rolling along the ground after a POWER CHORD. Jump over it! */
class ChordWave(x: Float, bottom: Float, private val dir: Int) : Foe(x, bottom - 12, 16f, 12f, 1), Projectile {
    private var t = 0f
    override val stompable = false
    override val showBar get() = false
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        x += dir * 150f * dt
        if (t > 2.5f) removed = true
    }

    override fun hit(g: TeamGame, dmg: Int) = false

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        for (k in 0 until 3) {
            val hh = 12f - k * 3f + sin(t * 20 + k) * 2f
            gfx.oval(x + ox + k * 3f, y + 12 - hh, 10f, hh, argb(180 - k * 40, 0xFF6AE8))
        }
    }
}

/**
 * ROCK STAR KRAG: King Krag with sunglasses and an electric guitar. He blasts wiggly music notes at
 * Rocco and does POWER CHORD jumps that send shockwaves along the ground.
 */
class RockStarKrag(x: Float, private val ground: Float) : Foe(x - 10, ground - 40, 36f, 40f, 100) {
    var awake = false
        private set
    private var t = 0f
    private var strumT = 2f
    private var chordT = 7f
    private var hurtT = 0f
    private var facing = -1
    private var chording = false
    val homeX = x
    override val boss = true
    override val stompable = true
    /** He looks just like King Krag (plus the rock star stuff drawn on top). */
    private val look = KingKrag(x, ground)

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        hurtT -= dt
        if (updateDying(g, dt)) return
        val l = g.leader
        if (!awake) {
            if (l.cx > homeX - g.viewW * 0.45f) {
                awake = true
                g.onBossEngaged(this)
                g.say(cx, y - 8, "ARE YOU READY TO ROCK?!", 2.5f)
                g.sayAs("Are you ready to rock?!", Voice.GOD)
            }
            return
        }
        val angry = hp < maxHp / 2
        facing = if (l.cx < cx) -1 else 1
        // Strut back and forth on the stage.
        if (onGround) vx = if (abs(cx - homeX) > 60f) (if (cx > homeX) -30f else 30f) else (if ((t % 3f) < 1.5f) 30f else -30f)
        strumT -= dt
        if (strumT <= 0f) {
            strumT = if (angry) 1.6f else 2.2f
            for (k in 0 until 3) g.spawn(NoteShot(if (facing > 0) x + w else x - 10, y + 6f + k * 10f, facing, k * 2.1f))
            g.sfx(Sound.ZAP)
        }
        chordT -= dt
        if (chordT <= 0f && onGround) {
            chordT = if (angry) 5.5f else 7f
            vy = -330f
            onGround = false
            chording = true
            g.say(cx, y - 8, "POWER CHORD!", 1.4f)
        }
        walk(g, dt)
        // Landing from a power chord: shockwaves both ways.
        if (chording && onGround && vy >= 0f) {
            chording = false
            g.shake = 0.4f
            g.sfx(Sound.BOSS_HIT)
            g.spawn(ChordWave(x - 16, bottom, -1))
            g.spawn(ChordWave(x + w, bottom, 1))
        }
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
            g.addScore(20000, cx, y)
            g.say(cx, y - 8, "That's... the end of the show!", 2f)
            g.onBossBeaten(this)
        }
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 3) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        look.x = x; look.y = y; look.vx = vx
        look.draw(g, gfx, ox)
        val px = x + ox
        val f = facing.toFloat()
        // Sunglasses
        gfx.rect(px + 18 + f * 6 - 9, y + 9, 18f, 4f, 0xFF101010.toInt())
        gfx.rect(px + 18 + f * 6 - 7, y + 10, 5f, 1f, 0x88FFFFFF.toInt())
        // An electric guitar, with a lightning bolt on it
        val gx = px + 18 + f * 4
        val gy = y + 26
        gfx.poly(floatArrayOf(gx - 9, gx + 5, gx + 9, gx - 3), floatArrayOf(gy + 6, gy - 4, gy + 2, gy + 10), 0xFFE52521.toInt())
        gfx.rect(gx + f * 4, gy - 16, 2f, 16f, 0xFF3A2A1A.toInt())
        gfx.rect(gx + f * 4 - 2, gy - 19, 6f, 4f, 0xFF1A1A1A.toInt())
        gfx.poly(floatArrayOf(gx - 3, gx + 1, gx - 1, gx + 3), floatArrayOf(gy, gy + 1, gy + 4, gy + 5), 0xFFFFD21F.toInt())
        if (strumT < 0.3f) for (k in 0 until 3) gfx.oval(gx - 8 + k * 6f, gy - 10 - k * 4f, 3f, 3f, solidRainbow(t + k * 0.3f, 0f))
        drawFlash(gfx, ox)
    }
}
