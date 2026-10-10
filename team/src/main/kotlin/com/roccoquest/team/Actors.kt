package com.roccoquest.team

import com.roccoquest.game.Body
import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Sprites
import com.roccoquest.game.TILE
import com.roccoquest.game.argb
import com.roccoquest.game.rainbow
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

const val GRAVITY = 900f
const val MAX_FALL = 420f

/**
 * Flower powers the team can craft. The metal sets the strength: iron is the weakest,
 * then gold, then diamond. Emerald makes the Grass Flower, which gives green armor.
 */
enum class Power(val label: String, val ore: Ore?, val damage: Int) {
    NONE("", null, 0),
    BOOM("BOOMERANG FLOWER", Ore.IRON, 1),
    FIRE("FIRE FLOWER", Ore.GOLD, 2),
    ICE("ICE FLOWER", Ore.DIAMOND, 3),
    GRASS("GRASS FLOWER", Ore.EMERALD, 1),
}

enum class Who(val label: String) { ROCCO("ROCCO"), BLOCKY("BLOCKY") }

abstract class Thing(x: Float, y: Float, w: Float, h: Float) : Body(x, y, w, h) {
    var removed = false
    var active = false
    abstract fun update(g: TeamGame, dt: Float)
    abstract fun draw(g: TeamGame, gfx: Gfx, ox: Float)
}

internal fun approach(v: Float, target: Float, step: Float) =
    if (v < target) min(v + step, target) else max(v - step, target)

// ============================================================================ Heroes

class Hero(val who: Who, x: Float, y: Float) : Body(x, y, 12f, 23f) {
    var facing = 1
    var anim = 0f
    /** Counts down while the pickaxe or a throw animation is playing. */
    var swing = 0f
    var cooldown = 0f
    var jumpCooldown = 0f
    var stuck = 0f

    val speed get() = if (who == Who.ROCCO) 120f else 108f
    /** Rocco jumps higher; Blocky is the strong one. */
    val jumpV get() = if (who == Who.ROCCO) -335f else -305f

    fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val flicker = this === g.leader && g.invuln > 0 && (g.invuln * 20).toInt() % 2 == 0
        if (flicker) return
        val walking = onGround && abs(vx) > 10 && (anim * 10).toInt() % 2 == 0
        val px = x - 2 + ox
        val py = bottom - 24
        when (who) {
            Who.ROCCO -> {
                val frame = if (!onGround) 2 else if (walking) 1 else 0
                gfx.sprite(TeamArt.rocco(g.power, frame), px, py, flipX = facing < 0)
                if (swing > 0) {
                    // Throwing arm forward
                    val hx = cx + facing * 8 + ox
                    gfx.oval(hx - 2, y + 11, 4f, 4f, 0xFFFFC08A.toInt())
                }
            }
            Who.BLOCKY -> {
                gfx.sprite(TeamArt.blocky(g.power, walking || !onGround), px, py, flipX = facing < 0)
                // The pickaxe: resting on his shoulder, or swinging down onto a block.
                val p = if (swing > 0) 1f - swing / 0.22f else 0f
                val base = if (g.digDown && swing > 0) 0.6f else 0f
                val a = if (swing > 0) -1.9f + p * 2.6f + base else -2.3f
                val hx = cx + facing * 4 + ox
                val angle = if (facing > 0) a else (Math.PI.toFloat() - a)
                TeamArt.pickaxe(gfx, hx, y + 13, angle, g.pickTier)
            }
        }
        if (g.armor > 0 && (g.time * 4).toInt() % 2 == 0) {
            gfx.oval(px - 1, py - 1, 18f, 26f, argb(40, 0x2EB84A))
        }
    }
}

// ============================================================================ Foes

abstract class Foe(x: Float, y: Float, w: Float, h: Float) : Thing(x, y, w, h) {
    var dying = false
    open val stompable = true
    open val boss = false

    /** Something hit this foe for [dmg] damage. Return false if it shrugged it off. */
    open fun hit(g: TeamGame, dmg: Int): Boolean { knockOut(g); g.addScore(200, cx, y); return true }
    open fun stomp(g: TeamGame, stomper: Hero) { knockOut(g); g.addScore(100, cx, y) }
    open fun touch(g: TeamGame) { g.hurt() }

    fun knockOut(g: TeamGame) {
        if (dying) return
        dying = true
        vy = -220f
        vx = if (g.leader.cx < cx) 40f else -40f
        g.sfx(Sound.KICK)
    }

    protected fun updateDying(g: TeamGame, dt: Float): Boolean {
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
}

class Grumbler(x: Float, y: Float, private var winged: Boolean) : Foe(x + 1, y - 14, 14f, 14f) {
    private var squashed = -1f
    private var anim = 0f
    init { vx = -30f }

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

    override fun stomp(g: TeamGame, stomper: Hero) {
        g.sfx(Sound.STOMP)
        g.addScore(100, cx, y)
        if (winged) { winged = false; vy = 0f; return }
        squashed = 0f
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
    }
}

class Shellback(x: Float, y: Float) : Foe(x + 1, y - 22, 14f, 22f) {
    private var shell = false
    private var sliding = false
    private var anim = 0f
    private var kickGrace = 0f
    init { vx = -28f }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        kickGrace -= dt
        walk(g, dt)
        if (sliding) {
            // A kicked shell bowls over every other foe it touches.
            for (f in g.foes) if (f !== this && !f.dying && !f.boss && f.overlaps(this)) f.hit(g, 1)
        }
    }

    override val stompable = true

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
    }
}

/** Dropped by Krag Jr.: rolls along and can't be stomped. */
class SpikeBall(x: Float, y: Float) : Foe(x, y, 12f, 12f) {
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

/** King Krag's fire breath. */
class Flame(x: Float, y: Float, private val dir: Int) : Foe(x, y, 20f, 8f) {
    override val stompable = false
    private var t = 0f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        x += dir * 120f * dt
        if (t > 5f) removed = true
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val wob = sin(t * 30) * 1.5f
        gfx.oval(x + ox, y - 1 + wob, w, h + 2, 0xFFFF6A00.toInt())
        gfx.oval(x + ox + 3, y + 1, w - 6, h - 2, 0xFFFFD21F.toInt())
        gfx.oval(x + ox + if (dir > 0) w - 8 else 2f, y + 2, 6f, 4f, 0xFFFFFFFF.toInt())
    }
}

/**
 * Wonder Krag Jr.: flies his clown car, drops spike balls and swoops down after a
 * shaky "!" warning. When the fight starts the whole sky goes Wonder.
 */
class KragJr(x: Float, y: Float) : Foe(x, y - 34, 30f, 34f) {
    enum class Mode { WAIT, FLY, WINDUP, SWOOP, HURT, BEATEN }

    val maxHp = 10
    var hp = maxHp
        private set
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
                val sp = if (angry) 85f else 65f
                x += (tx - x).coerceIn(-sp * dt, sp * dt)
                y += (ty - y).coerceIn(-60f * dt, 60f * dt)
                throwTimer -= dt
                if (throwTimer <= 0) {
                    g.spawn(SpikeBall(cx - 6, bottom - 4))
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
                    swoopTimer = 4.5f + Random.nextFloat() * 2f
                    setMode(Mode.FLY)
                } else {
                    x += dx / d * 150f * dt
                    y += dy / d * 150f * dt
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
        if (mode == Mode.HURT) return true
        hp -= dmg
        g.sfx(Sound.BOSS_HIT)
        if (hp <= 0) {
            hp = 0
            setMode(Mode.BEATEN)
            g.say(cx, y - 10, "I'm telling Papa!!")
            g.addScore(5000, cx, y)
            g.onBossBeaten(this)
        } else setMode(Mode.HURT)
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 2) }
    override fun touch(g: TeamGame) { if (mode == Mode.FLY || mode == Mode.SWOOP || mode == Mode.WINDUP) g.hurt() }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox + if (mode == Mode.WINDUP) sin(t * 60) * 2f else 0f
        val flash = mode == Mode.HURT && (modeTime * 20).toInt() % 2 == 0
        if (mode == Mode.WINDUP) gfx.shadowText("!", px + 15, y - 18, 16f, 0xFFFF3A3A.toInt(), 1)
        val tilt = if (mode == Mode.BEATEN) sin(t * 20) * 3f else 0f
        // Wonder aura
        if (engaged) {
            for (i in 0 until 6) {
                val a = t * 3 + i * 1.047f
                gfx.oval(px + 13 + sin(a) * 22, y + 14 + kotlin.math.cos(a) * 20, 5f, 5f, argb(200, rainbow(t, i / 6f)))
            }
        }
        val skin = if (flash) 0xFFFFFFFF.toInt() else 0xFFF2B33D.toInt()
        gfx.oval(px + 7, y - 2 + tilt, 16f, 15f, skin)
        gfx.poly(floatArrayOf(px + 10, px + 15, px + 20), floatArrayOf(y + 1 + tilt, y - 8 + tilt, y + 1 + tilt), 0xFFE53A1E.toInt())
        gfx.poly(floatArrayOf(px + 8, px + 6, px + 11), floatArrayOf(y + 2 + tilt, y - 4 + tilt, y + tilt), 0xFFFFF2C8.toInt())
        gfx.poly(floatArrayOf(px + 22, px + 24, px + 19), floatArrayOf(y + 2 + tilt, y - 4 + tilt, y + tilt), 0xFFFFF2C8.toInt())
        gfx.oval(px + 10, y + 3 + tilt, 4f, 5f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 16, y + 3 + tilt, 4f, 5f, 0xFFFFFFFF.toInt())
        gfx.rect(px + 11, y + 5 + tilt, 2f, 3f, 0xFF101010.toInt())
        gfx.rect(px + 17, y + 5 + tilt, 2f, 3f, 0xFF101010.toInt())
        // Bib with a fang-y grin
        gfx.rect(px + 12, y + 9 + tilt, 6f, 2f, 0xFF8A1010.toInt())
        gfx.poly(floatArrayOf(px + 13, px + 14, px + 15), floatArrayOf(y + 9 + tilt, y + 12 + tilt, y + 9 + tilt), 0xFFFFFFFF.toInt())
        // Clown car, rainbow-striped while Wonder is on
        val car = when {
            flash -> 0xFFFFD0D0.toInt()
            engaged -> solidRainbow(t * 0.5f)
            else -> 0xFFF4F4F4.toInt()
        }
        val cy = y + 10
        gfx.oval(px - 1, cy - 2, 32f, 7f, 0xFFB8B8C8.toInt())
        gfx.oval(px, cy, 30f, 22f, car)
        gfx.rect(px, cy, 30f, 6f, car)
        gfx.oval(px + 6, cy + 7, 6f, 6f, 0xFF101010.toInt())
        gfx.oval(px + 18, cy + 7, 6f, 6f, 0xFF101010.toInt())
        gfx.oval(px + 7, cy + 8, 2f, 2f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 19, cy + 8, 2f, 2f, 0xFFFFFFFF.toInt())
        gfx.poly(floatArrayOf(px + 6, px + 15, px + 24, px + 15), floatArrayOf(cy + 15, cy + 20, cy + 15, cy + 17), 0xFFE53A1E.toInt())
        val prop = 4f + abs(sin(t * 30)) * 14f
        gfx.rect(px + 14, cy + 21, 2f, 4f, 0xFF606070.toInt())
        gfx.oval(px + 15 - prop / 2, cy + 24, prop, 3f, 0xFF606070.toInt())
    }
}

/** King Krag: stomps around, breathes fire and jumps to shake the floor. */
class KingKrag(x: Float, y: Float) : Foe(x - 10, y - 40, 36f, 40f) {
    val maxHp = 16
    var hp = maxHp
        private set
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
        val h = g.leader
        if (!awake) {
            if (h.cx > homeX - g.viewW * 0.45f) {
                awake = true
                g.onBossEngaged(this)
            }
            walk(g, 0f)
            vx = 0f
            return
        }
        val angry = hp <= maxHp / 2
        // Slow to turn around, so you can jump over him.
        val want = if (h.cx < cx) -1 else 1
        if (want != facing) {
            turnDelay += dt
            if (turnDelay > 0.7f) { facing = want; turnDelay = 0f }
        } else turnDelay = 0f
        if (onGround) vx = facing * if (angry) 34f else 24f
        fireTimer -= dt
        if (fireTimer <= 0) {
            mouth = 0.5f
            val fy = if (Random.nextBoolean()) bottom - 10 else bottom - 26
            g.spawn(Flame(if (facing < 0) x - 18 else x + w - 2, fy, facing))
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
        }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean {
        if (!awake || dying) return false
        if (hurtFlash > 0) return true
        hp -= dmg
        hurtFlash = 0.35f
        g.sfx(Sound.BOSS_HIT)
        if (hp <= 0) {
            hp = 0
            knockOut(g)
            g.addScore(10000, cx, y)
            g.onBossBeaten(this)
        }
        return true
    }

    /** Stomping King Krag is allowed (it's that kind of game), but only does 1 damage. */
    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 1) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val flash = hurtFlash > 0 && (hurtFlash * 30).toInt() % 2 == 0
        val f = facing < 0
        // Mirror helper: local coordinates relative to the left edge, facing left.
        fun mx(lx: Float) = if (f) x + ox + lx else x + ox + w - lx
        fun r(lx: Float, ly: Float, lw: Float, lh: Float, c: Int) {
            val left = if (f) mx(lx) else mx(lx + lw)
            gfx.rect(left, y + ly, lw, lh, c)
        }
        fun o(lx: Float, ly: Float, lw: Float, lh: Float, c: Int) {
            val left = if (f) mx(lx) else mx(lx + lw)
            gfx.oval(left, y + ly, lw, lh, c)
        }
        fun p(xs: FloatArray, ys: FloatArray, c: Int) =
            gfx.poly(FloatArray(xs.size) { mx(xs[it]) }, FloatArray(ys.size) { y + ys[it] }, c)

        val skin = if (flash) 0xFFFFFFFF.toInt() else 0xFFF2B33D.toInt()
        val belly = if (flash) 0xFFFFFFFF.toInt() else 0xFFFFE8A0.toInt()
        val shell = if (flash) 0xFFFFFFFF.toInt() else 0xFF2E9E3A.toInt()
        val spike = 0xFFF4F4F4.toInt()
        val horn = 0xFFFFF2C8.toInt()
        val hair = 0xFFE53A1E.toInt()
        val step = if (onGround && (t * 4).toInt() % 2 == 0) 2f else 0f
        p(floatArrayOf(30f, 42f, 33f), floatArrayOf(28f, 34f, 36f), skin)
        o(8f, 31f - step, 11f, 10f, skin)
        o(21f, 29f + step, 11f, 10f, skin)
        for (i in 0 until 4) {
            val sy = 6f + i * 7f
            p(floatArrayOf(30f, 41f, 30f), floatArrayOf(sy, sy + 3f, sy + 6f), spike)
        }
        o(14f, 6f, 24f, 30f, 0xFF16602A.toInt())
        o(15f, 7f, 22f, 27f, shell)
        o(6f, 14f, 16f, 22f, belly)
        o(4f, 18f, 9f, 7f, skin)
        o(0f, -2f, 22f, 18f, skin)
        val jaw = if (mouth > 0) 5f else 0f
        o(-6f, 4f, 16f, 9f, skin)
        if (jaw > 0) {
            r(-5f, 10f, 12f, jaw, 0xFF8A1010.toInt())
            o(-6f, 9f + jaw, 15f, 6f, skin)
        }
        p(floatArrayOf(-3f, -1f, 1f), floatArrayOf(11f, 14f + jaw, 11f), spike)
        p(floatArrayOf(3f, 5f, 7f), floatArrayOf(11f, 14f + jaw, 11f), spike)
        o(6f, 0f, 7f, 7f, 0xFFFFFFFF.toInt())
        r(7f, 2f, 3f, 4f, 0xFF101010.toInt())
        p(floatArrayOf(4f, 14f, 14f), floatArrayOf(-2f, 0f, 2f), hair)
        p(floatArrayOf(8f, 11f, 6f), floatArrayOf(-1f, -9f, -2f), horn)
        p(floatArrayOf(15f, 20f, 13f), floatArrayOf(-1f, -10f, -2f), horn)
        p(floatArrayOf(14f, 26f, 22f, 28f, 20f), floatArrayOf(-3f, -6f, 2f, 6f, 6f), hair)
        r(10f, 14f, 12f, 3f, 0xFF303030.toInt())
    }
}

// ============================================================================ Shots

/** A thrown flower power. Boomerangs fly out and come back, passing through foes. */
class Shot(x: Float, y: Float, private val dir: Int, val kind: Power, val damage: Int, private val thrower: Hero) :
    Thing(x, y, 9f, 9f) {
    private var t = 0f
    private val hitAlready = HashSet<Foe>()
    private var returning = false

    init {
        active = true
        vx = dir * when (kind) {
            Power.FIRE -> 200f
            Power.ICE -> 230f
            Power.GRASS -> 210f
            else -> 230f
        }
        vy = if (kind == Power.FIRE) 60f else 0f
    }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        when (kind) {
            Power.FIRE -> {
                // Bounces along the ground
                vy = min(vy + GRAVITY * dt, 300f)
                if (g.level.moveX(this, vx * dt)) { gone(g); return }
                if (g.level.moveY(this, vy * dt) == 1) vy = -170f
            }
            Power.BOOM -> {
                // Slows down, turns around, and flies back to the thrower.
                if (t < 0.45f) x += vx * dt
                else {
                    // On the way back it can hit the same foes again.
                    if (!returning) { returning = true; hitAlready.clear() }
                    val dx = thrower.cx - cx
                    val dy = thrower.cy - cy
                    val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    x += dx / d * 260f * dt
                    y += dy / d * 260f * dt
                    if (d < 10f || t > 3f) removed = true
                }
            }
            Power.GRASS -> {
                if (g.level.moveX(this, vx * dt)) { gone(g); return }
                y += sin(t * 18) * 1.2f
            }
            else -> if (g.level.moveX(this, vx * dt)) { gone(g); return }
        }
        if (t > 2.5f && kind != Power.BOOM) removed = true
        for (f in g.foes) {
            if (f.dying || f.removed || !f.overlaps(this) || f in hitAlready) continue
            if (f.hit(g, damage)) {
                if (kind == Power.ICE && !f.boss) g.iceBurst(f.cx, f.cy)
                if (kind == Power.BOOM) hitAlready += f else { gone(g); return }
            }
        }
    }

    private fun gone(g: TeamGame) {
        removed = true
        g.puff(cx, cy)
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        when (kind) {
            Power.FIRE -> gfx.sprite(Sprites.fireball, x + ox, y, flipX = (t * 12).toInt() % 2 == 0, w = 9f, h = 9f)
            Power.ICE -> gfx.sprite(Sprites.iceball, x + ox, y, flipY = (t * 12).toInt() % 2 == 0, w = 9f, h = 9f)
            Power.BOOM -> gfx.sprite(Sprites.boomerang, x + ox - 1, y, flipY = (t * 10).toInt() % 2 == 0, w = 12f, h = 9f)
            else -> {
                val spin = sin(t * 25) * 3f
                gfx.poly(
                    floatArrayOf(x + ox, x + ox + 4.5f + spin, x + ox + 9, x + ox + 4.5f - spin),
                    floatArrayOf(y + 4.5f, y, y + 4.5f, y + 9),
                    0xFF2EB84A.toInt(),
                )
                gfx.rect(x + ox + 2, y + 4, 5f, 1f, 0xFF0E4A1A.toInt())
            }
        }
    }
}

// ============================================================================ Pickups

class Coin(x: Float, y: Float, private val popping: Boolean = false) : Thing(x + 4, y + 2, 8f, 12f) {
    private var t = 0f
    init { if (popping) vy = -250f }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (popping) {
            vy += GRAVITY * dt
            y += vy * dt
            if (t > 0.45f) { removed = true; g.coins += 1 }
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val squish = abs(sin(t * 4 + x * 0.05f))
        val w = 4f + 8f * squish
        gfx.sprite(Sprites.coin, cx - w / 2 + ox, y, w = w, h = 12f)
    }
}

class HeartItem(x: Float, y: Float) : Thing(x + 2, y, 12f, 12f) {
    private var t = 0f
    init { vx = 50f; vy = -150f; active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        vy = min(vy + GRAVITY * dt, MAX_FALL)
        if (g.level.moveX(this, vx * dt)) vx = -vx
        g.level.moveY(this, vy * dt)
        if (y > g.level.h * TILE) removed = true
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        TeamArt.heart(gfx, x + ox, y + sin(t * 6), 12f, 0xFFE52521.toInt())
        gfx.oval(x + ox + 2, y + 2, 3f, 3f, 0xAAFFFFFF.toInt())
    }
}

/** Mid-level flag: if the team gets knocked out, they start again from here. */
class Checkpoint(x: Float, y: Float) : Thing(x, y - 32, 16f, 32f) {
    var reached = false

    override fun update(g: TeamGame, dt: Float) {}

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        gfx.rect(x + ox + 7, y, 2f, 32f, 0xFFD8D8D8.toInt())
        val c = if (reached) 0xFF2EB84A.toInt() else 0xFFE53A1E.toInt()
        gfx.poly(floatArrayOf(x + ox + 9, x + ox + 21, x + ox + 9), floatArrayOf(y + 1, y + 6, y + 11), c)
        gfx.oval(x + ox + 5, y - 2, 6f, 6f, 0xFFFFD21F.toInt())
    }
}

/** The goal flag at the end of a level. */
class Goal(x: Float, y: Float) : Thing(x + 6, y - 9 * TILE + TILE, 4f, 9f * TILE) {
    var flagY = 8f
    override fun update(g: TeamGame, dt: Float) {}

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        gfx.rect(x + ox, y, 4f, h, 0xFF8EE07A.toInt())
        gfx.oval(x + ox - 2, y - 6, 8f, 8f, 0xFF1E9E3A.toInt())
        gfx.poly(
            floatArrayOf(x + ox, x + ox - 16, x + ox), floatArrayOf(y + flagY, y + flagY + 7, y + flagY + 14),
            0xFFFFFFFF.toInt(),
        )
        gfx.oval(x + ox - 9, y + flagY + 4, 6f, 6f, 0xFF2AA7A7.toInt())
    }
}

/** Ring-Ding Frog zooms past on his scooter, singing, and bowls over any foe he hits. */
class Frog(x: Float, private val baseY: Float) : Thing(x, baseY, 24f, 24f) {
    private var t = 0f
    private var nextLine = 0.2f
    private var line = 0
    private val lines = listOf("Ring-ding-ding-ding!", "Bing-bong-a-dong!", "Ribbit-a-dee-doo!", "Brrrm brrrm! Ding!")
    init { vx = 80f; active = true }
    val singing get() = t < 7.5f

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        x += vx * dt
        y = baseY - abs(sin(t * 9f)) * 6f
        nextLine -= dt
        if (nextLine <= 0) {
            g.say(cx, y - 8, lines[line % lines.size], 1.4f)
            line++
            nextLine = 1.6f
        }
        for (f in g.foes) if (!f.dying && !f.boss && f.overlaps(this)) f.hit(g, 1)
        if (t > 7.5f) removed = true
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) = TeamArt.frog(gfx, x + ox, y, t)
}

// ============================================================================ Particles

abstract class Particle(var x: Float, var y: Float) {
    var life = 1f
    abstract fun update(dt: Float)
    abstract fun draw(gfx: Gfx, ox: Float)
}

class Bit(x: Float, y: Float, private var vx: Float, private var vy: Float, private val color: Int) : Particle(x, y) {
    override fun update(dt: Float) {
        vy += GRAVITY * dt
        x += vx * dt
        y += vy * dt
        life -= dt
    }

    override fun draw(gfx: Gfx, ox: Float) = gfx.rect(x + ox, y, 4f, 4f, color)
}

class Puff(x: Float, y: Float) : Particle(x, y) {
    override fun update(dt: Float) { life -= dt * 3f }
    override fun draw(gfx: Gfx, ox: Float) {
        val r = 4f + (1f - life) * 8f
        gfx.oval(x + ox - r, y - r, r * 2, r * 2, argb((life * 200).toInt().coerceIn(0, 255), 0xFFFFFF))
    }
}

class FloatText(x: Float, y: Float, private val s: String, private val color: Int = 0xFFFFFFFF.toInt()) : Particle(x, y) {
    override fun update(dt: Float) { y -= 30f * dt; life -= dt }
    override fun draw(gfx: Gfx, ox: Float) = gfx.shadowText(s, x + ox, y, 7f, color, 1)
}

class Speech(x: Float, y: Float, private val s: String, dur: Float) : Particle(x, y) {
    init { life = dur }
    override fun update(dt: Float) { life -= dt; y -= 4f * dt }
    override fun draw(gfx: Gfx, ox: Float) {
        val w = s.length * 4.4f + 10f
        val a = if (life < 0.3f) (life / 0.3f * 255).toInt() else 255
        val bx = x + ox - w / 2
        gfx.rect(bx, y - 12, w, 14f, argb(a * 230 / 255, 0xFFFFFF))
        gfx.poly(floatArrayOf(x + ox - 3, x + ox + 3, x + ox), floatArrayOf(y + 2, y + 2, y + 6), argb(a * 230 / 255, 0xFFFFFF))
        gfx.text(s, x + ox, y - 2, 7f, argb(a, 0x202020), 1)
    }
}

/** Sparky's lightning bolt. */
class Bolt(private val x0: Float, private val y0: Float, x1: Float, y1: Float) : Particle(x1, y1) {
    private val seed = Random.nextInt()
    init { life = 0.3f }
    override fun update(dt: Float) { life -= dt }
    override fun draw(gfx: Gfx, ox: Float) {
        val r = Random(seed + (life * 30).toInt())
        var px = x0
        var py = y0
        for (i in 1..6) {
            val nx = x0 + (x - x0) * i / 6 + if (i < 6) r.nextFloat() * 10 - 5 else 0f
            val ny = y0 + (y - y0) * i / 6 + if (i < 6) r.nextFloat() * 10 - 5 else 0f
            val dx = nx - px
            val dy = ny - py
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.1f)
            val nX = -dy / len * 1.5f
            val nY = dx / len * 1.5f
            gfx.poly(
                floatArrayOf(px + ox + nX, nx + ox + nX, nx + ox - nX, px + ox - nX),
                floatArrayOf(py + nY, ny + nY, ny - nY, py - nY),
                0xFFFFF27A.toInt(),
            )
            px = nx
            py = ny
        }
    }
}

/** Little sparkle used for Wonder rain and ice bursts. */
class Sparkle(x: Float, y: Float, private val vx: Float, private val vy: Float, private val color: Int) : Particle(x, y) {
    override fun update(dt: Float) { x += vx * dt; y += vy * dt; life -= dt * 1.5f }
    override fun draw(gfx: Gfx, ox: Float) {
        val s = 2f + life * 3f
        gfx.poly(
            floatArrayOf(x + ox, x + ox + s * 0.3f, x + ox + s, x + ox + s * 0.3f, x + ox, x + ox - s * 0.3f, x + ox - s, x + ox - s * 0.3f),
            floatArrayOf(y - s, y - s * 0.3f, y, y + s * 0.3f, y + s, y + s * 0.3f, y, y - s * 0.3f),
            color,
        )
    }
}

internal fun signOf(v: Float) = sign(v).toInt()

/** [rainbow] gives a bare RGB color; this adds full opacity. */
fun solidRainbow(t: Float, offset: Float = 0f) = argb(255, rainbow(t, offset))
