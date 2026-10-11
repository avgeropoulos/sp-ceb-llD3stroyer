package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Sprites
import com.roccoquest.game.TILE
import com.roccoquest.game.argb
import com.roccoquest.game.rainbow
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Every bad guy has hit points and shows a health bar. */
abstract class Foe(x: Float, y: Float, w: Float, h: Float, val maxHp: Int) : Thing(x, y, w, h) {
    var hp = maxHp
        protected set
    var dying = false
    /** Flashes white for a moment after being hit. */
    var flash = 0f
    open val stompable = true
    open val boss = false
    /** Small things (fire, spike balls) don't get a health bar. */
    open val showBar get() = maxHp > 1

    /** Something hit this foe for [dmg] damage. Return false if it shrugged it off. */
    open fun hit(g: TeamGame, dmg: Int): Boolean {
        if (dying) return false
        hp -= dmg
        flash = 0.2f
        if (hp <= 0) {
            hp = 0
            knockOut(g)
            g.addScore(100 * maxHp + 100, cx, y)
        } else {
            g.sfx(Sound.BUMP)
            vy = min(vy, -80f)
        }
        return true
    }

    open fun stomp(g: TeamGame, stomper: Hero) { hp = 0; knockOut(g); g.addScore(100, cx, y) }
    open fun touch(g: TeamGame) { g.hurt() }

    fun knockOut(g: TeamGame) {
        if (dying) return
        dying = true
        if (!boss) g.onFoeDefeated(this)
        vy = -220f
        vx = if (g.leader.cx < cx) 40f else -40f
        g.sfx(Sound.KICK)
    }

    protected fun updateDying(g: TeamGame, dt: Float): Boolean {
        if (flash > 0) flash -= dt
        if (!dying) return false
        vy += GRAVITY * dt
        x += vx * dt
        y += vy * dt
        if (y > g.level.h * TILE + 40) removed = true
        return true
    }

    protected fun walk(g: TeamGame, dt: Float) {
        vy = min(vy + GRAVITY * dt, MAX_FALL)
        if (g.level.moveX(this, vx * dt)) vx = -vx
        g.level.moveY(this, vy * dt)
        if (y > g.level.h * TILE + 32 || g.level.inLava(this)) removed = true
    }

    /** White flash drawn over a foe that was just hit. */
    protected fun drawFlash(gfx: Gfx, ox: Float) {
        if (flash > 0) gfx.oval(x + ox - 2, y - 2, w + 4, h + 4, 0x99FFFFFF.toInt())
    }

    fun drawHealth(gfx: Gfx, ox: Float) {
        if (!showBar || dying || removed) return
        val bw = max(16f, w)
        val bx = cx + ox - bw / 2
        val by = y - 7
        val f = hp.toFloat() / maxHp
        val c = when {
            f > 0.6f -> 0xFF3CE070.toInt()
            f > 0.3f -> 0xFFFFD21F.toInt()
            else -> 0xFFE53A1E.toInt()
        }
        gfx.rect(bx - 1, by - 1, bw + 2, 4f, 0xCC000000.toInt())
        gfx.rect(bx, by, bw * f, 2f, c)
    }
}

class Grumbler(x: Float, y: Float, private var winged: Boolean) : Foe(x + 1, y - 14, 14f, 14f, 3) {
    private var squashed = -1f
    private var anim = 0f
    init { vx = -38f }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        if (squashed >= 0) {
            squashed += dt
            if (squashed > 0.5f) removed = true
            return
        }
        anim += dt
        walk(g, dt)
        if (winged && onGround) vy = -270f
    }

    override val stompable get() = squashed < 0
    override val showBar get() = squashed < 0

    override fun stomp(g: TeamGame, stomper: Hero) {
        g.sfx(Sound.STOMP)
        g.addScore(100, cx, y)
        if (winged) { winged = false; vy = 0f; return }
        squashed = 0f
        hp = 0
        g.onFoeDefeated(this)
        vx = 0f
    }

    override fun touch(g: TeamGame) { if (squashed < 0) g.hurt() }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val s = if (g.level.def.place == Place.CAVE) Sprites.grumblerBlue else Sprites.grumbler
        if (squashed >= 0) {
            gfx.sprite(s, x - 1 + ox, bottom - 7, h = 7f)
            return
        }
        if (winged && !dying) {
            val up = (anim * 10).toInt() % 2 == 0
            gfx.oval(x + ox - 6, y - 2 + if (up) -3f else 2f, 7f, 9f, 0xFFFFFFFF.toInt())
            gfx.oval(x + ox + 13, y - 2 + if (up) -3f else 2f, 7f, 9f, 0xFFFFFFFF.toInt())
        }
        gfx.sprite(s, x - 1 + ox, bottom - 16, flipX = (anim * 6).toInt() % 2 == 0, flipY = dying)
        drawFlash(gfx, ox)
    }
}

class Shellback(x: Float, y: Float) : Foe(x + 1, y - 22, 14f, 22f, 4) {
    private var shell = false
    private var sliding = false
    private var anim = 0f
    private var kickGrace = 0f
    init { vx = -34f }

    override val showBar get() = !shell

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        kickGrace -= dt
        walk(g, dt)
        if (sliding) {
            // A kicked shell bowls over every other foe it touches.
            for (f in g.foes) if (f !== this && !f.dying && !f.boss && f.overlaps(this)) f.hit(g, 3)
        }
    }

    override fun stomp(g: TeamGame, stomper: Hero) {
        g.sfx(Sound.STOMP)
        g.addScore(100, cx, y)
        if (!shell) {
            shell = true
            y += 8; h = 14f
            vx = 0f
        } else if (sliding) {
            sliding = false
            vx = 0f
        } else kick(g, stomper)
    }

    private fun kick(g: TeamGame, h: Hero) {
        sliding = true
        vx = if (h.cx < cx) 220f else -220f
        kickGrace = 0.3f
        g.sfx(Sound.KICK)
    }

    override fun touch(g: TeamGame) {
        when {
            !shell -> g.hurt()
            !sliding -> kick(g, g.leader)
            kickGrace <= 0 -> g.hurt()
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        if (shell) {
            gfx.sprite(Sprites.shell, x - 1 + ox, bottom - 16, flipY = dying)
        } else {
            val s = if ((anim * 5).toInt() % 2 == 0) Sprites.shellback1 else Sprites.shellback2
            gfx.sprite(s, x - 1 + ox, bottom - 24, flipX = vx > 0, flipY = dying)
        }
        drawFlash(gfx, ox)
    }
}

/** A skeleton turtle. Stomp him and he falls into a pile of bones... then gets back up! */
class Bonehead(x: Float, y: Float) : Foe(x + 1, y - 20, 14f, 20f, 6) {
    private var pile = -1f
    private var anim = 0f
    init { vx = -32f }

    override val stompable get() = pile < 0

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        if (pile >= 0) {
            pile += dt
            vy = min(vy + GRAVITY * dt, MAX_FALL)
            g.level.moveY(this, vy * dt)
            if (pile > 3f) {
                pile = -1f
                vx = if (g.leader.cx < cx) -26f else 26f
                g.say(cx, y - 4, "Rattle!", 0.8f)
            }
            return
        }
        walk(g, dt)
    }

    override fun stomp(g: TeamGame, stomper: Hero) {
        pile = 0f
        vx = 0f
        g.sfx(Sound.BREAK)
        g.addScore(100, cx, y)
    }

    /** While he's a pile of bones, powers do double damage. */
    override fun hit(g: TeamGame, dmg: Int) = super.hit(g, if (pile >= 0) dmg * 2 else dmg)

    override fun touch(g: TeamGame) { if (pile < 0) g.hurt() }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val bone = 0xFFF0F0E0.toInt()
        val dark = 0xFF8A8A7A.toInt()
        if (pile >= 0) {
            val shake = if (pile > 2.4f) sin(pile * 60) * 1.5f else 0f
            gfx.oval(px - 1 + shake, bottom - 7, 16f, 7f, dark)
            gfx.rect(px + 1 + shake, bottom - 6, 12f, 2f, bone)
            gfx.oval(px + 8 + shake, bottom - 12, 7f, 6f, bone)
            gfx.rect(px + 10 + shake, bottom - 10, 2f, 2f, 0xFF101010.toInt())
            return
        }
        val step = if ((anim * 6).toInt() % 2 == 0) 1f else -1f
        val f = vx > 0
        fun mx(lx: Float, lw: Float = 0f) = if (f) px + w - lx - lw else px + lx
        // Shell
        gfx.oval(mx(2f, 12f), y + 6, 12f, 10f, dark)
        gfx.rect(mx(4f, 8f), y + 9, 8f, 1.5f, bone)
        // Head and legs
        gfx.oval(mx(-1f, 9f), y, 9f, 9f, bone)
        gfx.rect(mx(0f, 3f), y + 3, 3f, 3f, 0xFF101010.toInt())
        gfx.rect(mx(3f, 3f), y + 15 + step, 3f, 5f - step, bone)
        gfx.rect(mx(9f, 3f), y + 15 - step, 3f, 5f + step, bone)
        drawFlash(gfx, ox)
    }
}

/** A cave bat: hangs from the ceiling until the team comes close, then flaps after them. */
class Flapper(x: Float, y: Float) : Foe(x + 2, y - 12, 12f, 10f, 3) {
    private var awake = false
    /** For the sticker picture: show it flapping. */
    fun wakeForSticker() { awake = true }
    private var t = 0f
    private val baseY = y - 12

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        val h = g.leader
        if (!awake) {
            if (abs(h.cx - cx) < 120f) awake = true
            return
        }
        val dx = h.cx - cx
        vx = approach(vx, if (dx > 0) 66f else -66f, 140f * dt)
        x += vx * dt
        val ty = (h.y - 4f).coerceIn(baseY - 30f, baseY + 70f)
        y += ((ty - y).coerceIn(-40f * dt, 40f * dt)) + sin(t * 6) * 0.8f
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val body = 0xFF4A2A6A.toInt()
        val wing = 0xFF6A3A9A.toInt()
        if (!awake && !dying) {
            gfx.oval(px + 2, y, 8f, 10f, body)
            gfx.rect(px + 4, y + 6, 1.5f, 1.5f, 0xFFFFE14D.toInt())
            gfx.rect(px + 7, y + 6, 1.5f, 1.5f, 0xFFFFE14D.toInt())
            return
        }
        val up = (t * 12).toInt() % 2 == 0
        val wy = if (up) y - 4 else y + 6
        gfx.poly(floatArrayOf(px + 3, px - 6, px + 1), floatArrayOf(y + 4, wy, y + 8), wing)
        gfx.poly(floatArrayOf(px + 9, px + 18, px + 11), floatArrayOf(y + 4, wy, y + 8), wing)
        gfx.oval(px + 1, y + 1, 10f, 9f, body)
        gfx.poly(floatArrayOf(px + 2, px + 3, px + 4.5f), floatArrayOf(y + 2, y - 2, y + 2), body)
        gfx.poly(floatArrayOf(px + 7.5f, px + 9, px + 10), floatArrayOf(y + 2, y - 2, y + 2), body)
        gfx.rect(px + 3.5f, y + 4, 2f, 2f, 0xFFFFE14D.toInt())
        gfx.rect(px + 6.5f, y + 4, 2f, 2f, 0xFFFFE14D.toInt())
        drawFlash(gfx, ox)
    }
}

/** Dropped by Krag Jr.: rolls along and can't be stomped. */
class SpikeBall(x: Float, y: Float) : Foe(x, y, 12f, 12f, 1) {
    override val stompable = false
    private var life = 9f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        life -= dt
        if (life <= 0) { removed = true; g.puff(cx, cy) }
        val wasGround = onGround
        walk(g, dt)
        if (onGround && !wasGround && vx == 0f) vx = if (g.leader.cx < cx) -70f else 70f
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        gfx.sprite(Sprites.spikeBall, x + ox, y, w = 12f, h = 12f, flipY = dying)
    }
}

/** King Krag's fire breath. Wonder King Krag breathes rainbow fire. */
class Flame(x: Float, y: Float, private val dir: Int, private val rainbowFire: Boolean = false) : Foe(x, y, 20f, 8f, 1) {
    override val stompable = false
    private var t = 0f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        x += dir * (if (rainbowFire) 140f else 120f) * dt
        if (t > 5f) removed = true
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val wob = sin(t * 30) * 1.5f
        val outer = if (rainbowFire) solidRainbow(t * 2) else 0xFFFF6A00.toInt()
        gfx.oval(x + ox, y - 1 + wob, w, h + 2, outer)
        gfx.oval(x + ox + 3, y + 1, w - 6, h - 2, 0xFFFFD21F.toInt())
        gfx.oval(x + ox + if (dir > 0) w - 8 else 2f, y + 2, 6f, 4f, 0xFFFFFFFF.toInt())
    }
}

// ============================================================================ Bosses

/**
 * Krag Jr. flies his clown car, drops spike balls and swoops down after a shaky "!"
 * warning. The Wonder version is purple and green, tougher, and turns the sky Wonder.
 */
class KragJr(x: Float, y: Float, val wonderStyle: Boolean = false) :
    Foe(x, y - 34, 30f, 34f, if (wonderStyle) 24 else 13) {
    enum class Mode { WAIT, FLY, WINDUP, SWOOP, HURT, BEATEN }

    var mode = Mode.WAIT
        private set
    private var t = 0f
    private var modeTime = 0f
    private var throwTimer = 3f
    private var swoopTimer = 5f
    private var swoopX = 0f
    private var swoopY = 0f
    val homeX = x

    override val boss = true
    val engaged get() = mode != Mode.WAIT && mode != Mode.BEATEN

    private fun setMode(m: Mode) { mode = m; modeTime = 0f }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        modeTime += dt
        if (flash > 0) flash -= dt
        val h = g.leader
        when (mode) {
            Mode.WAIT -> {
                y += sin(t * 3) * 0.3f
                if (h.cx > homeX - g.viewW * 0.42f) {
                    setMode(Mode.FLY)
                    g.onBossEngaged(this)
                }
            }
            Mode.FLY -> {
                val angry = hp <= maxHp / 2
                val tx = h.cx + sin(t * 0.9f) * 110f - w / 2
                val ty = 46f + sin(t * 1.7f) * 14f
                val sp = (if (angry) 85f else 65f) * (if (wonderStyle) 1.2f else 1f)
                x += (tx - x).coerceIn(-sp * dt, sp * dt)
                y += (ty - y).coerceIn(-60f * dt, 60f * dt)
                throwTimer -= dt
                if (throwTimer <= 0) {
                    g.spawn(SpikeBall(cx - 6, bottom - 4))
                    if (wonderStyle) g.spawn(SpikeBall(cx - 6, bottom - 4).also { it.vx = if (h.cx < cx) 70f else -70f })
                    g.sfx(Sound.KICK)
                    throwTimer = (if (angry) 2.2f else 3.0f) + Random.nextFloat()
                }
                swoopTimer -= dt
                if (swoopTimer <= 0) setMode(Mode.WINDUP)
            }
            Mode.WINDUP -> if (modeTime > 0.9f) {
                swoopX = h.cx - w / 2
                swoopY = h.bottom - h.h - 6
                setMode(Mode.SWOOP)
            }
            Mode.SWOOP -> {
                val dx = swoopX - x
                val dy = swoopY - y
                val d = sqrt(dx * dx + dy * dy)
                if (d < 6f || modeTime > 1.6f) {
                    swoopTimer = (if (wonderStyle) 3.5f else 4.5f) + Random.nextFloat() * 2f
                    setMode(Mode.FLY)
                } else {
                    val sp = if (wonderStyle) 175f else 150f
                    x += dx / d * sp * dt
                    y += dy / d * sp * dt
                }
            }
            Mode.HURT -> {
                y = max(40f, y - 90f * dt)
                if (modeTime > 1.1f) setMode(Mode.FLY)
            }
            Mode.BEATEN -> {
                x += 110f * dt
                y -= 100f * dt
                if (y < -100f) removed = true
            }
        }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean {
        if (mode == Mode.WAIT || mode == Mode.BEATEN) return false
        // Little hits bounce off while he's reeling; big SUPER hits always land.
        if (mode == Mode.HURT && dmg < 5) return true
        hp -= dmg
        flash = 0.2f
        g.sfx(Sound.BOSS_HIT)
        if (hp <= 0) {
            hp = 0
            setMode(Mode.BEATEN)
            dying = true
            g.say(cx, y - 10, "I'm telling Papa!!")
            g.addScore(if (wonderStyle) 8000 else 5000, cx, y)
            g.onBossBeaten(this)
        } else setMode(Mode.HURT)
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 3) }
    override fun touch(g: TeamGame) { if (mode == Mode.FLY || mode == Mode.SWOOP || mode == Mode.WINDUP) g.hurt() }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox + if (mode == Mode.WINDUP) sin(t * 60) * 2f else 0f
        val hurt = mode == Mode.HURT && (modeTime * 20).toInt() % 2 == 0
        if (mode == Mode.WINDUP) gfx.shadowText("!", px + 15, y - 18, 16f, 0xFFFF3A3A.toInt(), 1)
        val tilt = if (mode == Mode.BEATEN) sin(t * 20) * 3f else 0f
        if (wonderStyle && engaged) {
            for (i in 0 until 6) {
                val a = t * 3 + i * 1.047f
                gfx.oval(px + 13 + sin(a) * 22, y + 14 + cos(a) * 20, 5f, 5f, argb(200, rainbow(t, i / 6f)))
            }
        }
        // Normal: yellow skin, red hair, white car. Wonder: green skin, purple hair, purple car.
        val skin = when {
            hurt -> 0xFFFFFFFF.toInt()
            wonderStyle -> 0xFF5AD05A.toInt()
            else -> 0xFFF2B33D.toInt()
        }
        val hair = if (wonderStyle) 0xFFB060FF.toInt() else 0xFFE53A1E.toInt()
        gfx.oval(px + 7, y - 2 + tilt, 16f, 15f, skin)
        gfx.poly(floatArrayOf(px + 10, px + 15, px + 20), floatArrayOf(y + 1 + tilt, y - 8 + tilt, y + 1 + tilt), hair)
        gfx.poly(floatArrayOf(px + 8, px + 6, px + 11), floatArrayOf(y + 2 + tilt, y - 4 + tilt, y + tilt), 0xFFFFF2C8.toInt())
        gfx.poly(floatArrayOf(px + 22, px + 24, px + 19), floatArrayOf(y + 2 + tilt, y - 4 + tilt, y + tilt), 0xFFFFF2C8.toInt())
        gfx.oval(px + 10, y + 3 + tilt, 4f, 5f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 16, y + 3 + tilt, 4f, 5f, 0xFFFFFFFF.toInt())
        gfx.rect(px + 11, y + 5 + tilt, 2f, 3f, 0xFF101010.toInt())
        gfx.rect(px + 17, y + 5 + tilt, 2f, 3f, 0xFF101010.toInt())
        gfx.rect(px + 12, y + 9 + tilt, 6f, 2f, 0xFF8A1010.toInt())
        gfx.poly(floatArrayOf(px + 13, px + 14, px + 15), floatArrayOf(y + 9 + tilt, y + 12 + tilt, y + 9 + tilt), 0xFFFFFFFF.toInt())
        val car = when {
            hurt -> 0xFFFFD0D0.toInt()
            wonderStyle -> 0xFF8A3AD8.toInt()
            else -> 0xFFF4F4F4.toInt()
        }
        val cy = y + 10
        gfx.oval(px - 1, cy - 2, 32f, 7f, if (wonderStyle) 0xFF2EB84A.toInt() else 0xFFB8B8C8.toInt())
        gfx.oval(px, cy, 30f, 22f, car)
        gfx.rect(px, cy, 30f, 6f, car)
        if (wonderStyle) gfx.rect(px + 1, cy + 3, 28f, 3f, 0xFF3CE070.toInt())
        gfx.oval(px + 6, cy + 7, 6f, 6f, 0xFF101010.toInt())
        gfx.oval(px + 18, cy + 7, 6f, 6f, 0xFF101010.toInt())
        gfx.oval(px + 7, cy + 8, 2f, 2f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 19, cy + 8, 2f, 2f, 0xFFFFFFFF.toInt())
        gfx.poly(
            floatArrayOf(px + 6, px + 15, px + 24, px + 15), floatArrayOf(cy + 15, cy + 20, cy + 15, cy + 17),
            if (wonderStyle) 0xFF3CE070.toInt() else 0xFFE53A1E.toInt(),
        )
        val prop = 4f + abs(sin(t * 30)) * 14f
        gfx.rect(px + 14, cy + 21, 2f, 4f, 0xFF606070.toInt())
        gfx.oval(px + 15 - prop / 2, cy + 24, prop, 3f, 0xFF606070.toInt())
    }
}

/**
 * King Krag: stomps around, breathes fire and jumps to shake the floor.
 * Wonder King Krag is bigger, purple and green, breathes rainbow fire and makes
 * spike balls rain from the sky when he lands.
 */
class KingKrag(x: Float, y: Float, val wonderStyle: Boolean = false, val dry: Boolean = false) :
    Foe(
        x - 10, y - 40 * scaleOf(wonderStyle || dry), 36f * scaleOf(wonderStyle || dry), 40f * scaleOf(wonderStyle || dry),
        if (wonderStyle) 40 else if (dry) 44 else 22,
    ) {
    companion object {
        fun scaleOf(wonder: Boolean) = if (wonder) 1.35f else 1f
    }

    private val s = scaleOf(wonderStyle || dry)
    /** Dry Bones King Krag falls into a pile of bones once, then gets back up. */
    private var revived = false
    private var bonesT = -1f
    val inBones get() = bonesT >= 0
    var awake = false
        private set
    private var t = 0f
    private var fireTimer = 2.5f
    private var jumpTimer = 5f
    private var mouth = 0f
    private var hurtFlash = 0f
    private var facing = -1
    private var turnDelay = 0f
    val homeX = x

    override val boss = true
    override val stompable = true

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        hurtFlash -= dt
        mouth -= dt
        if (updateDying(g, dt)) return
        if (bonesT >= 0) {
            bonesT += dt
            vx = 0f
            walk(g, dt)
            if (bonesT > 3.5f) {
                bonesT = -1f
                hp = maxHp / 2
                g.say(cx, y - 10, "DRY BONES NEVER GIVE UP!", 2f)
                g.sfx(Sound.BOSS_FIRE)
                g.shake = 0.5f
            }
            return
        }
        val h = g.leader
        if (!awake) {
            if (h.cx > homeX - g.viewW * 0.45f) {
                awake = true
                g.onBossEngaged(this)
            }
            vx = 0f
            return
        }
        val angry = hp <= maxHp / 2
        val want = if (h.cx < cx) -1 else 1
        if (want != facing) {
            turnDelay += dt
            if (turnDelay > (if (wonderStyle) 0.55f else 0.7f)) { facing = want; turnDelay = 0f }
        } else turnDelay = 0f
        if (onGround) vx = facing * (if (angry) 34f else 24f) * (if (wonderStyle) 1.2f else 1f)
        fireTimer -= dt
        if (fireTimer <= 0) {
            mouth = 0.5f
            val fx = if (facing < 0) x - 18 else x + w - 2
            if ((wonderStyle || dry) && angry) {
                // Two flames at once, low and high: stand in the gap or jump!
                g.spawn(Flame(fx, bottom - 10, facing, true))
                g.spawn(Flame(fx, bottom - 44, facing, true))
            } else {
                val fy = if (Random.nextBoolean()) bottom - 10 else bottom - 26 * s
                g.spawn(Flame(fx, fy, facing, wonderStyle || dry))
            }
            g.sfx(Sound.BOSS_FIRE)
            fireTimer = if (angry) 1.8f else 2.6f
        }
        jumpTimer -= dt
        if (jumpTimer <= 0 && onGround) {
            vy = -300f
            jumpTimer = 4f + Random.nextFloat() * 2f
        }
        val wasGround = onGround
        walk(g, dt)
        if (onGround && !wasGround) {
            g.shake = 0.3f
            g.sfx(Sound.BUMP)
            if (wonderStyle || dry) repeat(2) {
                g.spawn(SpikeBall(g.camX + 30f + Random.nextFloat() * (g.viewW - 60f), 20f))
            }
        }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean {
        if (!awake || dying || bonesT >= 0) return false
        if (hurtFlash > 0 && dmg < 5) return true
        hp -= dmg
        hurtFlash = 0.35f
        g.sfx(Sound.BOSS_HIT)
        if (hp <= 0 && dry && !revived) {
            hp = 0
            revived = true
            bonesT = 0f
            g.sfx(Sound.BREAK)
            g.say(cx, y - 10, "Rattle rattle...", 1.5f)
            return true
        }
        if (hp <= 0) {
            hp = 0
            knockOut(g)
            g.addScore(if (wonderStyle || dry) 20000 else 10000, cx, y)
            g.onBossBeaten(this)
        }
        return true
    }

    /** Stomping King Krag is allowed (it's that kind of game), but only does 1 damage. */
    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 1) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val flash = hurtFlash > 0 && (hurtFlash * 30).toInt() % 2 == 0
        val f = facing < 0
        if (bonesT >= 0) {
            // A pile of bones that shakes before he gets back up.
            val shakeX = if (bonesT > 2.5f) sin(bonesT * 60) * 2f else 0f
            gfx.oval(x + ox + shakeX, bottom - 14, w, 14f, 0xFF4A4A44.toInt())
            for (i in 0 until 5) gfx.rect(x + ox + 4 + i * w / 6 + shakeX, bottom - 10 - (i % 2) * 4, w / 5, 3f, 0xFFF0F0E0.toInt())
            gfx.oval(x + ox + w * 0.6f + shakeX, bottom - 22, 16f, 12f, 0xFFF0F0E0.toInt())
            gfx.rect(x + ox + w * 0.6f + 4 + shakeX, bottom - 18, 3f, 3f, 0xFF101010.toInt())
            return
        }
        // Local coordinates are for a 36x40 Krag facing left; scale and mirror them.
        fun mx(lx: Float) = if (f) x + ox + lx * s else x + ox + w - lx * s
        fun r(lx: Float, ly: Float, lw: Float, lh: Float, c: Int) {
            val left = if (f) mx(lx) else mx(lx + lw)
            gfx.rect(left, y + ly * s, lw * s, lh * s, c)
        }
        fun o(lx: Float, ly: Float, lw: Float, lh: Float, c: Int) {
            val left = if (f) mx(lx) else mx(lx + lw)
            gfx.oval(left, y + ly * s, lw * s, lh * s, c)
        }
        fun p(xs: FloatArray, ys: FloatArray, c: Int) =
            gfx.poly(FloatArray(xs.size) { mx(xs[it]) }, FloatArray(ys.size) { y + ys[it] * s }, c)

        if (wonderStyle && awake && !dying) {
            for (i in 0 until 8) {
                val a = t * 2 + i * 0.785f
                gfx.oval(x + ox + w / 2 + sin(a) * w * 0.8f - 3, y + h / 2 + cos(a) * h * 0.7f - 3, 6f, 6f, argb(190, rainbow(t, i / 8f)))
            }
        }
        val white = 0xFFFFFFFF.toInt()
        val skin = if (flash) white else if (dry) 0xFFF0F0E0.toInt() else if (wonderStyle) 0xFF5AD05A.toInt() else 0xFFF2B33D.toInt()
        val belly = if (flash) white else if (dry) 0xFFC8C8B8.toInt() else if (wonderStyle) 0xFFD8F5B0.toInt() else 0xFFFFE8A0.toInt()
        val shell = if (flash) white else if (dry) 0xFF7A7A70.toInt() else if (wonderStyle) 0xFF8A3AD8.toInt() else 0xFF2E9E3A.toInt()
        val shellDark = if (dry) 0xFF4A4A44.toInt() else if (wonderStyle) 0xFF4A1A80.toInt() else 0xFF16602A.toInt()
        val spike = if (dry) 0xFFF0F0E0.toInt() else if (wonderStyle) 0xFF3CE070.toInt() else 0xFFF4F4F4.toInt()
        val horn = 0xFFFFF2C8.toInt()
        val hair = if (wonderStyle) 0xFFC060FF.toInt() else 0xFFE53A1E.toInt()
        val step = if (onGround && (t * 4).toInt() % 2 == 0) 2f else 0f
        p(floatArrayOf(30f, 42f, 33f), floatArrayOf(28f, 34f, 36f), skin)
        o(8f, 31f - step, 11f, 10f, skin)
        o(21f, 29f + step, 11f, 10f, skin)
        for (i in 0 until 4) {
            val sy = 6f + i * 7f
            p(floatArrayOf(30f, 41f, 30f), floatArrayOf(sy, sy + 3f, sy + 6f), spike)
        }
        o(14f, 6f, 24f, 30f, shellDark)
        o(15f, 7f, 22f, 27f, shell)
        if (wonderStyle) r(18f, 18f, 16f, 2f, 0xFF3CE070.toInt())
        o(6f, 14f, 16f, 22f, belly)
        o(4f, 18f, 9f, 7f, skin)
        o(0f, -2f, 22f, 18f, skin)
        val jaw = if (mouth > 0) 5f else 0f
        o(-6f, 4f, 16f, 9f, skin)
        if (jaw > 0) {
            r(-5f, 10f, 12f, jaw, 0xFF8A1010.toInt())
            o(-6f, 9f + jaw, 15f, 6f, skin)
        }
        p(floatArrayOf(-3f, -1f, 1f), floatArrayOf(11f, 14f + jaw, 11f), horn)
        p(floatArrayOf(3f, 5f, 7f), floatArrayOf(11f, 14f + jaw, 11f), horn)
        o(6f, 0f, 7f, 7f, white)
        r(7f, 2f, 3f, 4f, if (dry) 0xFFFF3A3A.toInt() else if (wonderStyle) 0xFF8A3AD8.toInt() else 0xFF101010.toInt())
        p(floatArrayOf(4f, 14f, 14f), floatArrayOf(-2f, 0f, 2f), hair)
        p(floatArrayOf(8f, 11f, 6f), floatArrayOf(-1f, -9f, -2f), horn)
        p(floatArrayOf(15f, 20f, 13f), floatArrayOf(-1f, -10f, -2f), horn)
        p(floatArrayOf(14f, 26f, 22f, 28f, 20f), floatArrayOf(-3f, -6f, 2f, 6f, 6f), hair)
        r(10f, 14f, 12f, 3f, 0xFF303030.toInt())
    }
}
