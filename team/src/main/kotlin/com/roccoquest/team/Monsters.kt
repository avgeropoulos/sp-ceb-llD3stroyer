package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Sprites
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ============================================================================ New monsters

/** An arrow from a Skelly Archer. It flies in a gentle arc and sticks in walls. */
class Arrow(x: Float, y: Float, vx0: Float, vy0: Float) : Foe(x, y, 12f, 4f, 1), Projectile {
    override val stompable = false
    override val showBar = false
    private var life = 4f
    init { vx = vx0; vy = vy0; active = true }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        life -= dt
        vy += 110f * dt
        x += vx * dt
        y += vy * dt
        if (life <= 0 || g.level.solidAt(cx + (if (vx > 0) 5f else -5f), cy)) { removed = true; g.puff(cx, cy) }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val a = atan2(vy, vx)
        val dx = cos(a)
        val dy = sin(a)
        val mx = cx + ox
        val my = cy
        // Shaft, tip and feathers along the direction it's flying.
        gfx.poly(
            floatArrayOf(mx - dx * 7 - dy * 0.8f, mx + dx * 5 - dy * 0.8f, mx + dx * 5 + dy * 0.8f, mx - dx * 7 + dy * 0.8f),
            floatArrayOf(my - dy * 7 + dx * 0.8f, my + dy * 5 + dx * 0.8f, my + dy * 5 - dx * 0.8f, my - dy * 7 - dx * 0.8f),
            0xFF8A5A2A.toInt(),
        )
        gfx.poly(
            floatArrayOf(mx + dx * 8, mx + dx * 4 - dy * 2.5f, mx + dx * 4 + dy * 2.5f),
            floatArrayOf(my + dy * 8, my + dy * 4 + dx * 2.5f, my + dy * 4 - dx * 2.5f),
            0xFFC8C8D0.toInt(),
        )
        gfx.poly(
            floatArrayOf(mx - dx * 7, mx - dx * 10 - dy * 3, mx - dx * 5),
            floatArrayOf(my - dy * 7, my - dy * 10 + dx * 3, my - dy * 5),
            0xFFFFFFFF.toInt(),
        )
        gfx.poly(
            floatArrayOf(mx - dx * 7, mx - dx * 10 + dy * 3, mx - dx * 5),
            floatArrayOf(my - dy * 7, my - dy * 10 - dx * 3, my - dy * 5),
            0xFFE0E0E0.toInt(),
        )
    }
}

/** A skeleton with a bow. He stops, pulls back his bow (watch for the shake!) and shoots arrows at you. */
class SkellyArcher(x: Float, bottom: Float) : Foe(x + 1, bottom - 24, 14f, 24f, 7) {
    private var anim = 0f
    private var reload = 1.2f + Random.nextFloat()
    private var aim = -1f
    private var facing = -1
    private val homeX = x
    init { vx = -18f }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        val l = g.leader
        val dx = l.cx - cx
        val sees = abs(dx) < 250f && abs(l.cy - cy) < 90f
        if (aim >= 0f) {
            // Pulling back the bow...
            aim += dt
            vx = 0f
            facing = if (dx > 0) 1 else -1
            if (aim > 0.65f) {
                aim = -1f
                reload = 2.2f + Random.nextFloat() * 0.8f
                // Aim a little high so the arrow arcs down onto the target.
                val speed = 210f
                val t = (abs(dx) / speed).coerceIn(0.25f, 1.4f)
                val vy0 = ((l.cy - (y + 9)) / t - 55f * t).coerceIn(-170f, 120f)
                g.spawn(Arrow(if (facing > 0) x + w else x - 12, y + 8, facing * speed, vy0))
                g.sfx(Sound.ARROW)
            }
        } else {
            reload -= dt
            if (sees && reload <= 0f && onGround) aim = 0f
            // Shuffle back and forth near home, keeping some distance from the team.
            if (abs(x - homeX) > 40f) vx = if (x > homeX) -18f else 18f
            if (sees && abs(dx) < 70f) vx = if (dx > 0) -26f else 26f
            if (vx != 0f) facing = if (sees) (if (dx > 0) 1 else -1) else (if (vx > 0) 1 else -1)
        }
        walk(g, dt)
    }

    override fun stomp(g: TeamGame, stomper: Hero) {
        g.sfx(Sound.BREAK)
        hit(g, 4)
        aim = -1f
        reload = 1.5f
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val bone = 0xFFF0F0E0.toInt()
        val dark = 0xFF8A8A7A.toInt()
        val f = facing > 0
        fun mx(lx: Float, lw: Float = 0f) = if (f) px + lx else px + w - lx - lw
        val shake = if (aim > 0.35f) sin(aim * 70) * 0.8f else 0f
        val step = if (vx != 0f && (anim * 6).toInt() % 2 == 0) 1f else 0f
        // Legs, ribs and head
        gfx.rect(mx(3f, 2f), y + 17 + step, 2f, 7f - step, bone)
        gfx.rect(mx(9f, 2f), y + 17 - step, 2f, 7f + step - 0f, bone)
        gfx.rect(mx(4f, 6f), y + 9, 6f, 9f, dark)
        for (i in 0 until 3) gfx.rect(mx(3f, 8f), y + 10 + i * 2.5f, 8f, 1.2f, bone)
        gfx.oval(mx(2f, 10f), y, 10f, 10f, bone)
        gfx.rect(mx(7f, 2f), y + 3, 2f, 2.5f, 0xFF101010.toInt())
        gfx.rect(mx(4f, 2f), y + 3, 2f, 2.5f, 0xFF101010.toInt())
        gfx.rect(mx(4f, 5f), y + 7, 5f, 1f, 0xFF404040.toInt())
        // The bow, and the string pulled back while aiming.
        val bx = mx(13f) + shake
        val by = y + 12
        val pull = if (aim >= 0f) min(1f, aim / 0.5f) * 5f else 0f
        val dir = if (f) 1f else -1f
        gfx.poly(
            floatArrayOf(bx, bx + dir * 4, bx + dir * 5, bx + dir * 4, bx, bx + dir * 2.5f, bx + dir * 3, bx + dir * 2.5f),
            floatArrayOf(by - 9, by - 5, by, by + 5, by + 9, by + 5, by, by - 5),
            0xFF8A5A2A.toInt(),
        )
        gfx.poly(floatArrayOf(bx, bx - dir * pull, bx - dir * pull + dir * 0.6f, bx + dir * 0.6f), floatArrayOf(by - 9, by, by, by - 9), 0xFFE0E0E0.toInt())
        gfx.poly(floatArrayOf(bx, bx - dir * pull, bx - dir * pull + dir * 0.6f, bx + dir * 0.6f), floatArrayOf(by + 9, by, by, by + 9), 0xFFE0E0E0.toInt())
        if (aim >= 0f) gfx.rect(if (f) bx - pull else bx + pull - 10, by - 0.6f, 10f, 1.2f, 0xFF8A5A2A.toInt())
        // Arm holding the bow
        gfx.rect(if (f) mx(8f) else mx(8f, 6f), y + 11, 6f, 1.5f, bone)
        if (aim > 0.35f) gfx.shadowText("!", cx + ox, y - 9, 9f, 0xFFFF4040.toInt(), 1)
        drawFlash(gfx, ox)
    }
}

/** A Grumbler with a red headband. When you get close he crouches... then JUMPS right at you! */
class Hopper(x: Float, bottom: Float) : Foe(x + 1, bottom - 14, 14f, 14f, 4) {
    private var crouch = -1f
    private var cool = 0.8f
    private var anim = 0f
    private var squashed = -1f
    init { vx = -30f }

    override val stompable get() = squashed < 0
    override val showBar get() = squashed < 0

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        if (squashed >= 0) { squashed += dt; if (squashed > 0.5f) removed = true; return }
        anim += dt
        val l = g.leader
        val dx = l.cx - cx
        cool -= dt
        if (crouch >= 0f) {
            crouch += dt
            vx = 0f
            if (crouch > 0.35f) {
                crouch = -1f
                cool = 1.5f + Random.nextFloat() * 0.6f
                vx = (if (dx > 0) 1 else -1) * (abs(dx) * 1.6f).coerceIn(90f, 170f)
                vy = -330f
                g.sfx(Sound.JUMP)
            }
        } else if (onGround) {
            if (cool <= 0f && abs(dx) < 150f && abs(l.cy - cy) < 70f) {
                crouch = 0f
            } else {
                vx = if (abs(dx) < 200f) (if (dx > 0) 34f else -34f) else (if (vx >= 0) 30f else -30f)
            }
        }
        walk(g, dt)
    }

    override fun stomp(g: TeamGame, stomper: Hero) {
        g.sfx(Sound.STOMP)
        g.addScore(150, cx, y)
        squashed = 0f
        hp = 0
        g.onFoeDefeated(this)
        vx = 0f
    }

    override fun touch(g: TeamGame) { if (squashed < 0) g.hurt(this) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val s = Sprites.grumbler
        if (squashed >= 0) { gfx.sprite(s, x - 1 + ox, bottom - 7, h = 7f); return }
        // Squash down before a jump, stretch tall in the air.
        val sh = when {
            crouch >= 0f -> 16f - crouch * 14f
            !onGround -> 18f
            else -> 16f
        }
        val sw = if (crouch >= 0f) 16f + crouch * 8f else if (!onGround) 14f else 16f
        val px = cx + ox - sw / 2
        val py = bottom - sh
        gfx.sprite(s, px, py, w = sw, h = sh, flipX = vx > 0 || (vx == 0f && g.leader.cx > cx), flipY = dying)
        if (!dying) {
            // Red headband with flapping ties.
            gfx.rect(px + 1, py + sh * 0.18f, sw - 2, 2.2f, 0xFFE52521.toInt())
            val tie = if (vx > 0) px - 3 else px + sw - 1
            gfx.rect(tie, py + sh * 0.18f + sin(anim * 14) * 1f, 4f, 1.6f, 0xFFE52521.toInt())
        }
        if (crouch >= 0f) gfx.shadowText("!", cx + ox, py - 4, 9f, 0xFFFFD21F.toInt(), 1)
        drawFlash(gfx, ox)
    }
}

/** A bouncy green slime. Beat a big Gloop and it splits into two little ones! */
class Gloop(x: Float, bottom: Float, val big: Boolean = true) :
    Foe(x + (if (big) 0f else 3f), bottom - (if (big) 16f else 9f), if (big) 18f else 10f, if (big) 16f else 9f, if (big) 8 else 3) {
    private var hopT = 0.5f + Random.nextFloat()
    private var split = false
    private var anim = 0f

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        val l = g.leader
        if (onGround) {
            vx = 0f
            hopT -= dt
            if (hopT <= 0f) {
                hopT = (if (big) 1.1f else 0.7f) + Random.nextFloat() * 0.5f
                val dir = if (abs(l.cx - cx) < 260f) (if (l.cx > cx) 1 else -1) else (if (Random.nextBoolean()) 1 else -1)
                vx = dir * (if (big) 70f else 90f)
                vy = if (big) -230f else -200f
            }
        }
        walk(g, dt)
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean {
        val r = super.hit(g, dmg)
        if (big && dying && !split) {
            split = true
            g.sfx(Sound.SPLASH)
            for (d in listOf(-1, 1)) {
                g.spawn(Gloop(cx - 5 + d * 6, bottom, big = false).also { it.vx = d * 70f; it.vy = -220f; it.active = true })
            }
        }
        return r
    }

    override fun stomp(g: TeamGame, stomper: Hero) {
        g.sfx(Sound.STOMP)
        hit(g, if (big) 4 else 99)
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val stretch = if (!onGround) (-vy / 600f).coerceIn(-0.2f, 0.3f) else -0.12f + abs(sin(anim * 5)) * 0.06f
        val dw = w * (1f - stretch)
        val dh = h * (1f + stretch)
        val px = cx + ox - dw / 2
        val py = if (dying) y else bottom - dh
        gfx.oval(px - 1, py - 1, dw + 2, dh + 2, 0xFF1A7A2A.toInt())
        gfx.oval(px, py, dw, dh, 0xCC5AE06A.toInt())
        gfx.rect(px + dw * 0.3f, py + dh * 0.4f, dw * 0.4f, dh * 0.35f, 0xAA2EA84A.toInt())
        gfx.oval(px + dw * 0.15f, py + dh * 0.1f, dw * 0.25f, dh * 0.2f, 0x88FFFFFF.toInt())
        val eye = if (big) 2.5f else 1.6f
        val look = if (g.leader.cx > cx) 1f else -1f
        gfx.rect(px + dw * 0.3f + look, py + dh * 0.3f, eye, eye * 1.4f, 0xFF101010.toInt())
        gfx.rect(px + dw * 0.62f + look, py + dh * 0.3f, eye, eye * 1.4f, 0xFF101010.toInt())
        drawFlash(gfx, ox)
    }
}

/** A seed spat by a Snapper plant. */
class Seed(x: Float, y: Float, vx0: Float, vy0: Float) : Foe(x, y, 6f, 6f, 1), Projectile {
    override val stompable = false
    override val showBar = false
    private var life = 3f
    init { vx = vx0; vy = vy0; active = true }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        life -= dt
        vy += 300f * dt
        x += vx * dt
        y += vy * dt
        if (life <= 0 || g.level.solidAt(cx, cy)) { removed = true; g.puff(cx, cy) }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        gfx.oval(x + ox, y, w, h, 0xFF5A3A1A.toInt())
        gfx.oval(x + ox + 1, y + 1, 2f, 2f, 0xFFC89A5A.toInt())
    }
}

/**
 * A Snapper plant hides in the ground. When you come close it pops up, snaps its jaws and
 * spits seeds at you. Its leaves are spiky, so you can't stomp it: hit it with something!
 */
class Snapper(x: Float, bottom: Float) : Foe(x + 1, bottom - 22, 14f, 22f, 6) {
    override val stompable = false
    private var rise = 0f
    private var up = false
    private var spit = 1f
    private var t = 0f
    private val ground = bottom

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        val l = g.leader
        val near = abs(l.cx - cx) < 110f
        up = near || (up && abs(l.cx - cx) < 160f)
        rise = if (up) min(1f, rise + dt * 3f) else (rise - dt * 1.5f).coerceAtLeast(0f)
        y = ground - 22f * rise.coerceAtLeast(0.15f)
        h = ground - y
        if (rise >= 1f) {
            spit -= dt
            if (spit <= 0f) {
                spit = 1.8f + Random.nextFloat() * 0.6f
                val ft = 0.8f
                val sx = cx - 3
                val sy = y + 2
                val vx0 = ((l.cx - sx) / ft).coerceIn(-170f, 170f)
                val vy0 = ((l.cy - sy) / ft - 0.5f * 300f * ft).coerceIn(-320f, 40f)
                g.spawn(Seed(sx, sy, vx0, vy0))
                g.sfx(Sound.CHOMP)
            }
        }
    }

    override fun touch(g: TeamGame) { if (rise > 0.4f) g.hurt(this) }

    override fun hit(g: TeamGame, dmg: Int): Boolean = if (rise < 0.3f) false else super.hit(g, dmg)

    override val showBar get() = rise > 0.5f && maxHp > 1

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = cx + ox
        val stem = 0xFF2E8A3A.toInt()
        val leaf = 0xFF4AC85A.toInt()
        // Spiky leaves at the bottom.
        gfx.poly(floatArrayOf(px - 9, px - 2, px - 1), floatArrayOf(ground - 1, ground - 6, ground), leaf)
        gfx.poly(floatArrayOf(px + 9, px + 2, px + 1), floatArrayOf(ground - 1, ground - 6, ground), leaf)
        if (rise <= 0.16f) {
            gfx.oval(px - 4, ground - 4, 8f, 5f, 0xFFB0303A.toInt())
            return
        }
        gfx.rect(px - 1.5f, y + 8, 3f, ground - y - 8, stem)
        // The head: a red bulb with white spots and a big snapping mouth.
        val open = if (rise >= 1f) (abs(sin(t * 5)) * 5f) else 1f
        val hy = y
        gfx.oval(px - 8, hy, 16f, 6f, 0xFFD8303A.toInt())
        gfx.oval(px - 8, hy + 5 + open, 16f, 6f, 0xFFD8303A.toInt())
        gfx.rect(px - 7, hy + 4, 14f, 1.5f + open, 0xFFFFF2E0.toInt())
        for (i in 0 until 4) gfx.poly(floatArrayOf(px - 6 + i * 4, px - 4 + i * 4, px - 5 + i * 4), floatArrayOf(hy + 4, hy + 4, hy + 6.5f), 0xFFFFFFFF.toInt())
        gfx.oval(px - 5, hy + 1, 3f, 2f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 2, hy + 1, 3f, 2f, 0xFFFFFFFF.toInt())
        drawFlash(gfx, ox)
    }
}

// ============================================================================ Bonus portals

/**
 * A little swirly portal. Step in and you're whisked off to a sunset bonus room full of
 * coins, new materials and a treasure chest. The one in the bonus room takes you back.
 */
class Portal(x: Float, bottom: Float, val exit: Boolean = false) : Thing(x - 2, bottom - 26, 20f, 26f) {
    var used = false
    private var t = Random.nextFloat() * 5f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (!used && Random.nextFloat() < dt * 8) {
            g.addParticle(Sparkle(cx + (Random.nextFloat() - 0.5f) * 16f, cy + (Random.nextFloat() - 0.5f) * 20f, 0f, -20f, sunset(Random.nextFloat())))
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        if (used) {
            gfx.oval(px + 6, bottom - 3, 8f, 3f, 0x66000000)
            return
        }
        val pulse = sin(t * 4) * 1.2f
        gfx.oval(px - 1 - pulse, y - 1 - pulse, w + 2 + pulse * 2, h + 2 + pulse * 2, 0xFF2A0E3A.toInt())
        for (i in 0 until 5) {
            val k = i / 5f
            gfx.oval(px + k * 7f, y + k * 9f, w - k * 14f, h - k * 18f, sunset(k + t * 0.4f))
        }
        // A spinning swirl.
        for (i in 0 until 6) {
            val a = t * 5 + i * 1.047f
            gfx.oval(cx + ox + cos(a) * 6f - 1.5f, cy + sin(a) * 9f - 1.5f, 3f, 3f, 0xCCFFFFFF.toInt())
        }
        val label = if (exit) "EXIT" else "BONUS!"
        gfx.shadowText(label, cx + ox, y - 4 + sin(t * 3) * 1.5f, 7f, 0xFFFFD21F.toInt(), 1)
    }

    companion object {
        /** Sunset colors: gold, orange, pink and purple. */
        fun sunset(k: Float): Int {
            val c = listOf(0xFFFFD84A, 0xFFFF8A3A, 0xFFFF4A7A, 0xFFB03AC8, 0xFF6A2AA8)
            val i = ((k % 1f + 1f) % 1f * c.size).toInt().coerceIn(0, c.lastIndex)
            return c[i].toInt()
        }
    }
}

/** The treasure chest in the bonus room. Touch it to open it and get a special prize! */
class Chest(x: Float, bottom: Float) : Thing(x, bottom - 13, 16f, 13f) {
    var open = false
    private var t = 0f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) { t += dt }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val wood = 0xFF8A4A1A.toInt()
        val gold = 0xFFFFD21F.toInt()
        gfx.rect(px, y + 5, w, 8f, wood)
        gfx.rect(px, y + 5, w, 1.5f, gold)
        if (open) {
            gfx.rect(px, y - 2, w, 4f, wood)
            gfx.rect(px + 2, y + 3, w - 4, 3f, 0xFF2A1808.toInt())
        } else {
            gfx.rect(px, y, w, 6f, wood)
            gfx.rect(px + 6.5f, y + 3, 3f, 4f, gold)
            if ((t * 3).toInt() % 2 == 0) gfx.oval(px + w - 3, y - 3, 3f, 3f, 0xFFFFFFFF.toInt())
        }
        gfx.rect(px + 2, y + 5, 1.5f, 8f, gold)
        gfx.rect(px + w - 3.5f, y + 5, 1.5f, 8f, gold)
    }
}

/** Bonus room sky: a big orange sun sinking behind purple hills. */
object Sunset {
    fun drawSky(gfx: Gfx, viewW: Float, viewH: Float, cam: Float, time: Float) {
        val bands = listOf(0xFF2A1450, 0xFF4A1E6A, 0xFF7A2A7A, 0xFFB8406A, 0xFFE8605A, 0xFFFF8A4A, 0xFFFFB04A)
        val bh = viewH * 0.75f / bands.size
        bands.forEachIndexed { i, c -> gfx.rect(0f, i * bh, viewW, bh + 1, c.toInt()) }
        gfx.rect(0f, bands.size * bh, viewW, viewH, 0xFFFFC85A.toInt())
        // The sun, half behind the hills, with a shimmer.
        val sx = viewW * 0.62f - cam * 0.05f
        gfx.oval(sx - 54, viewH * 0.52f, 108f, 108f, 0x55FFE08A)
        gfx.oval(sx - 40, viewH * 0.56f, 80f, 80f, 0xFFFFE87A.toInt())
        for (i in 0 until 4) gfx.rect(sx - 40 + i * 4, viewH * 0.66f + i * 7f, 80f - i * 8, 2f, 0x66FF8A3A)
        // Little stars coming out up high.
        for (i in 0 until 14) {
            val tw = if (((time * 2 + i) % 3) < 1.5f) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt()
            gfx.rect(((i * 97 + 30) % viewW.toInt()).toFloat(), (i * 23 % 60).toFloat() + 6, 1.5f, 1.5f, tw)
        }
        // Purple hills in front of the sun.
        var x = -((cam * 0.3f) % 160f) - 160f
        while (x < viewW + 160f) {
            gfx.oval(x, viewH * 0.7f, 200f, 120f, 0xFF4A1E5A.toInt())
            gfx.oval(x + 90, viewH * 0.75f, 160f, 100f, 0xFF3A1448.toInt())
            x += 160f
        }
        gfx.rect(0f, viewH * 0.82f, viewW, viewH, argb(255, 0x3A1448))
    }
}

/** Where a new monster's TILE-sized spawn point puts its feet. */
internal fun spawnMonster(c: Char, px: Float, bottom: Float): Foe? = when (c) {
    'A' -> SkellyArcher(px, bottom)
    'H' -> Hopper(px, bottom)
    'U' -> Gloop(px, bottom)
    'T' -> Snapper(px, bottom)
    else -> null
}
