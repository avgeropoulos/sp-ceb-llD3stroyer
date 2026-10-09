package com.roccoquest.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.random.Random

open class Body(var x: Float, var y: Float, var w: Float, var h: Float) {
    var vx = 0f
    var vy = 0f
    var onGround = false

    val cx get() = x + w / 2
    val cy get() = y + h / 2
    val bottom get() = y + h

    fun overlaps(o: Body) = x < o.x + o.w && x + w > o.x && y < o.y + o.h && y + h > o.y
}

abstract class Entity(x: Float, y: Float, w: Float, h: Float) : Body(x, y, w, h) {
    var removed = false
    /** Entities wake up once they scroll near the camera. */
    var active = false

    abstract fun update(g: Game, dt: Float)
    abstract fun draw(g: Game, gfx: Gfx, ox: Float)
}

// ======================================================================== Hero

enum class Power { SMALL, FIRE }

class Hero(x: Float, y: Float) : Body(x, y, 12f, SMALL_H) {
    companion object {
        const val SMALL_H = 15f
        const val BIG_H = 23f
        const val MAX_SPEED = 115f
        const val JUMP_V = -310f
    }

    var power = Power.SMALL
        set(value) {
            val newH = if (value == Power.SMALL) SMALL_H else BIG_H
            y += h - newH
            h = newH
            field = value
        }
    var facing = 1
    var invuln = 0f
    var walkAnim = 0f
    var throwAnim = 0f
    var coyote = 0f
    var jumpBuffer = 0f
    var jumpHeld = false
    var visible = true
    var flash = 0f

    fun draw(gfx: Gfx, ox: Float, dead: Boolean) {
        if (!visible) return
        if (invuln > 0 && ((invuln * 20).toInt() % 2 == 0)) return
        val small = power == Power.SMALL
        val frame = when {
            dead -> Sprites.smallJump
            !onGround -> if (small) Sprites.smallJump else Sprites.fireJump
            abs(vx) > 5f && (walkAnim.toInt() % 2 == 1) -> if (small) Sprites.smallWalk else Sprites.fireWalk
            else -> if (small) Sprites.smallStand else Sprites.fireStand
        }
        val sx = x - 2 + ox
        val sy = y + h - frame.h
        gfx.sprite(frame, sx, sy, flipX = facing < 0)
        if (flash > 0) {
            gfx.oval(sx - 2, sy - 2, frame.w + 4f, frame.h + 4f, argb((flash * 300).toInt().coerceIn(0, 200), 0xFFF3A0))
        }
    }
}

// ======================================================================== Enemies

abstract class Enemy(x: Float, y: Float, w: Float, h: Float) : Entity(x, y, w, h) {
    /** Knocked out: tumbling off screen, no longer interacts. */
    var dying = false
    open val stompable = true

    open fun stomp(g: Game) { knockOut(g) }
    open fun touchHero(g: Game) { g.hurtHero() }
    /** Called when a hero fireball hits; return true if the fireball is used up. */
    open fun fireHit(g: Game): Boolean { knockOut(g); g.addScore(200, cx, y); return true }

    fun knockOut(g: Game) {
        if (dying) return
        dying = true
        vy = -220f
        vx = if (g.hero.cx < cx) 40f else -40f
        g.sfx(Sound.KICK)
    }

    /** Shared tumble-off-screen motion; returns true while dying. */
    protected fun updateDying(g: Game, dt: Float): Boolean {
        if (!dying) return false
        vy += 900f * dt
        x += vx * dt
        y += vy * dt
        if (y > g.level.h * TILE + 32) removed = true
        return true
    }

    protected fun walk(g: Game, dt: Float) {
        vy = min(vy + 900f * dt, 400f)
        if (g.level.moveX(this, vx * dt)) vx = -vx
        g.level.moveY(this, vy * dt)
        if (y > g.level.h * TILE + 32) removed = true
    }
}

class Grumbler(x: Float, y: Float) : Enemy(x + 1, y - 14, 14f, 14f) {
    private var squashed = -1f
    private var anim = 0f
    init { vx = -30f }

    override fun update(g: Game, dt: Float) {
        if (updateDying(g, dt)) return
        if (squashed >= 0) {
            squashed += dt
            if (squashed > 0.5f) removed = true
            return
        }
        anim += dt
        walk(g, dt)
    }

    override fun stomp(g: Game) {
        squashed = 0f
        dying = false
        vx = 0f
        g.sfx(Sound.STOMP)
        g.addScore(100, cx, y)
    }

    val isSquashed get() = squashed >= 0

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val s = if (g.level.def.theme == Theme.UNDERGROUND) Sprites.grumblerBlue else Sprites.grumbler
        if (squashed >= 0) {
            gfx.sprite(s, x - 1 + ox, bottom - 7, h = 7f)
        } else {
            gfx.sprite(s, x - 1 + ox, bottom - 16, flipX = (anim * 6).toInt() % 2 == 0, flipY = dying)
        }
    }
}

class Shellback(x: Float, y: Float) : Enemy(x + 1, y - 22, 14f, 22f) {
    enum class Mode { WALK, SHELL, SLIDE }

    var mode = Mode.WALK
        private set
    private var shellTimer = 0f
    private var kickGrace = 0f
    private var anim = 0f
    init { vx = -30f }

    private fun toShell() {
        if (mode == Mode.WALK) { y += 10f; h = 12f }
        mode = Mode.SHELL
        vx = 0f
        shellTimer = 7f
    }

    private fun kick(g: Game) {
        mode = Mode.SLIDE
        vx = if (g.hero.cx < cx) 210f else -210f
        kickGrace = 0.25f
        g.sfx(Sound.KICK)
        g.addScore(100, cx, y)
    }

    override fun update(g: Game, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        kickGrace -= dt
        when (mode) {
            Mode.WALK -> walk(g, dt)
            Mode.SHELL -> {
                walk(g, dt)
                shellTimer -= dt
                if (shellTimer <= 0f) {
                    mode = Mode.WALK
                    y -= 10f; h = 22f
                    vx = if (g.hero.cx < cx) -30f else 30f
                }
            }
            Mode.SLIDE -> {
                val before = vx
                walk(g, dt)
                if (before != vx && g.isOnScreen(this)) g.sfx(Sound.BUMP)
                // Sliding shells plough through other enemies.
                for (e in g.entities) {
                    if (e !== this && e is Enemy && !e.dying && e !is Krag && e.overlaps(this)) {
                        if (e is Grumbler && e.isSquashed) continue
                        e.knockOut(g)
                        g.addScore(200, e.cx, e.y)
                    }
                }
            }
        }
    }

    override fun stomp(g: Game) {
        when (mode) {
            Mode.WALK -> { toShell(); g.sfx(Sound.STOMP); g.addScore(100, cx, y) }
            Mode.SHELL -> kick(g)
            Mode.SLIDE -> { mode = Mode.SHELL; vx = 0f; shellTimer = 7f; g.sfx(Sound.STOMP) }
        }
    }

    override fun touchHero(g: Game) {
        when (mode) {
            Mode.SHELL -> kick(g)
            Mode.SLIDE -> if (kickGrace <= 0f) g.hurtHero()
            Mode.WALK -> g.hurtHero()
        }
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        if (mode == Mode.WALK && !dying) {
            val s = if ((anim * 5).toInt() % 2 == 0) Sprites.shellback1 else Sprites.shellback2
            gfx.sprite(s, x - 1 + ox, bottom - 24, flipX = vx < 0)
        } else {
            val shaking = mode == Mode.SHELL && shellTimer < 1.5f && (anim * 20).toInt() % 2 == 0
            gfx.sprite(Sprites.shell, x - 1 + ox + if (shaking) 1 else 0, bottom - 16, flipY = dying)
        }
    }
}

/** Lava bubble that leaps out of the lava. Immune to fireballs and stomps. */
class Podoboo(x: Float, y: Float) : Enemy(x + 2, y, 12f, 13f) {
    private val baseY = y + 8
    private var wait = Random.nextFloat() * 2f
    override val stompable = false

    init { this.y = baseY }

    override fun update(g: Game, dt: Float) {
        if (wait > 0) {
            wait -= dt
            if (wait <= 0) vy = -400f
            return
        }
        vy += 700f * dt
        y += vy * dt
        if (y >= baseY && vy > 0) {
            y = baseY
            wait = 1.4f + Random.nextFloat() * 1.4f
        }
    }

    override fun fireHit(g: Game) = true

    override fun touchHero(g: Game) { if (wait <= 0) g.hurtHero() }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        if (wait > 0) return
        gfx.sprite(Sprites.podoboo, x - 1 + ox, y, flipY = vy > 0)
    }
}

// ======================================================================== King Krag (boss)

class Krag(x: Float, y: Float) : Enemy(x - 10, y - 40, 36f, 40f) {
    companion object { const val MAX_HP = 10 }

    var hp = MAX_HP
    private val homeX = x - 10
    private var facing = -1
    private var walkDir = -1
    private var jumpTimer = 2.5f
    private var fireTimer = 1.8f
    private var mouth = 0f
    private var hurtFlash = 0f
    private var anim = 0f
    override val stompable = false

    override fun update(g: Game, dt: Float) {
        anim += dt
        hurtFlash -= dt
        if (dying) {
            vy += 700f * dt
            y += vy * dt
            if (y > g.level.h * TILE + 64) removed = true
            return
        }
        facing = if (g.hero.cx < cx) -1 else 1

        // Pace back and forth on the bridge.
        vx = walkDir * 22f
        if (x < homeX - 48) walkDir = 1
        if (x > homeX + 20) walkDir = -1

        jumpTimer -= dt
        if (jumpTimer <= 0 && onGround) {
            vy = -270f
            jumpTimer = 2f + Random.nextFloat() * 2.5f
        }

        fireTimer -= dt
        if (fireTimer < 0.45f) mouth = 1f
        if (fireTimer <= 0) {
            val mx = if (facing < 0) x - 6 else x + w - 10
            val targetY = (g.hero.cy - 6).coerceIn(y - 10, bottom - 14)
            g.spawn(KragFlame(mx, y + 8, facing, targetY))
            g.sfx(Sound.BOSS_FIRE)
            fireTimer = 1.6f + Random.nextFloat() * 1.4f
            mouth = 0f
        }

        vy = min(vy + 900f * dt, 450f)
        if (g.level.moveX(this, vx * dt)) walkDir = -walkDir
        g.level.moveY(this, vy * dt)
        if (y > g.level.h * TILE) {
            // Fell into the lava once the bridge gave way.
            dying = true
            removed = true
            g.onBossDefeated()
        }
    }

    override fun fireHit(g: Game): Boolean {
        if (dying) return true
        hp--
        hurtFlash = 0.2f
        g.sfx(Sound.BOSS_HIT)
        if (hp <= 0) {
            dying = true
            vy = -250f
            g.addScore(5000, cx, y)
            g.onBossDefeated()
        }
        return true
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val m = Mirror(gfx, x + ox, y, w, flip = facing > 0, flipY = dying && vy > 0)
        val flash = hurtFlash > 0 && (hurtFlash * 30).toInt() % 2 == 0
        val skin = if (flash) 0xFFFFFFFF.toInt() else 0xFFF2B33D.toInt()
        val belly = if (flash) 0xFFFFFFFF.toInt() else 0xFFFFE8A0.toInt()
        val shell = if (flash) 0xFFFFFFFF.toInt() else 0xFF2E9E3A.toInt()
        val shellDark = 0xFF16602A.toInt()
        val spike = 0xFFF4F4F4.toInt()
        val horn = 0xFFFFF2C8.toInt()
        val hair = 0xFFE53A1E.toInt()
        val step = if (onGround && (anim * 4).toInt() % 2 == 0) 2f else 0f

        // Tail
        m.poly(floatArrayOf(30f, 42f, 33f), floatArrayOf(28f, 34f, 36f), skin)
        // Legs
        m.oval(8f, 31f - step, 11f, 10f, skin)
        m.oval(21f, 31f + step - 2, 11f, 10f, skin)
        m.rect(6f, 38f - step, 13f, 3f, horn)
        m.rect(20f, 36f + step, 13f, 3f, horn)
        // Shell with spikes (on his back, which faces away from the hero)
        for (i in 0 until 4) {
            val sy = 6f + i * 7f
            m.poly(floatArrayOf(30f, 41f, 30f), floatArrayOf(sy, sy + 3f, sy + 6f), spike)
        }
        m.oval(14f, 6f, 24f, 30f, shellDark)
        m.oval(15f, 7f, 22f, 27f, shell)
        m.rect(18f, 12f, 16f, 2f, shellDark)
        m.rect(18f, 20f, 16f, 2f, shellDark)
        m.rect(18f, 28f, 14f, 2f, shellDark)
        // Belly
        m.oval(6f, 14f, 16f, 22f, belly)
        m.rect(9f, 20f, 10f, 1f, 0xFFD8B860.toInt())
        m.rect(9f, 25f, 10f, 1f, 0xFFD8B860.toInt())
        m.rect(9f, 30f, 10f, 1f, 0xFFD8B860.toInt())
        // Arm with claws
        m.oval(4f, 18f, 9f, 7f, skin)
        m.poly(floatArrayOf(2f, 5f, 4f), floatArrayOf(19f, 18f, 22f), horn)
        // Head
        m.oval(0f, -2f, 22f, 18f, skin)
        // Snout / jaw
        val jaw = if (mouth > 0) 5f else 0f
        m.oval(-6f, 4f, 16f, 9f, skin)
        if (jaw > 0) {
            m.rect(-5f, 10f, 12f, jaw, 0xFF8A1010.toInt())
            m.oval(-6f, 9f + jaw, 15f, 6f, skin)
        }
        m.poly(floatArrayOf(-3f, -1f, 1f), floatArrayOf(11f, 14f + jaw, 11f), spike)
        m.poly(floatArrayOf(3f, 5f, 7f), floatArrayOf(11f, 14f + jaw, 11f), spike)
        m.rect(-4f, 6f, 2f, 2f, 0xFF5A2E0C.toInt())
        // Eye and angry brow
        m.oval(6f, 0f, 7f, 7f, 0xFFFFFFFF.toInt())
        m.rect(7f, 2f, 3f, 4f, 0xFF101010.toInt())
        m.poly(floatArrayOf(4f, 14f, 14f), floatArrayOf(-2f, 0f, 2f), hair)
        // Horns and flaming hair
        m.poly(floatArrayOf(8f, 11f, 6f), floatArrayOf(-1f, -9f, -2f), horn)
        m.poly(floatArrayOf(15f, 20f, 13f), floatArrayOf(-1f, -10f, -2f), horn)
        m.poly(floatArrayOf(14f, 26f, 22f, 28f, 20f), floatArrayOf(-3f, -6f, 2f, 6f, 6f), hair)
        // Spiked collar
        m.rect(10f, 14f, 12f, 3f, 0xFF303030.toInt())
        m.poly(floatArrayOf(12f, 13.5f, 15f), floatArrayOf(14f, 10f, 14f), spike)
        m.poly(floatArrayOf(17f, 18.5f, 20f), floatArrayOf(14f, 10f, 14f), spike)
    }
}

/** Fire breathed by King Krag. Drifts toward the hero's height when breathed. */
class KragFlame(x: Float, y: Float, dir: Int, private val targetY: Float) : Enemy(x, y, 22f, 8f) {
    private var anim = 0f
    override val stompable = false

    init { vx = dir * 115f }

    override fun update(g: Game, dt: Float) {
        anim += dt
        x += vx * dt
        y += (targetY - y).coerceIn(-40f * dt, 40f * dt)
        if (x + w < g.camX - 32 || x > g.camX + g.viewW + 32) removed = true
    }

    /** Hero fireballs fizzle out against the boss's flames. */
    override fun fireHit(g: Game): Boolean = true

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val m = Mirror(gfx, x + ox, y, w, flip = vx > 0)
        val f = if ((anim * 12).toInt() % 2 == 0) 1f else 0f
        m.poly(floatArrayOf(0f, 8f, 22f, 8f), floatArrayOf(4f, -1f - f, 4f, 9f + f), 0xFFE53A1E.toInt())
        m.poly(floatArrayOf(2f, 9f, 18f, 9f), floatArrayOf(4f, 1f, 4f, 7f), 0xFFFF9A1E.toInt())
        m.oval(1f, 2f, 9f, 5f, 0xFFFFF0A0.toInt())
    }
}

/** Draws in local coordinates, optionally mirrored horizontally / vertically. */
class Mirror(
    private val gfx: Gfx, private val ox: Float, private val oy: Float, private val w: Float,
    private val flip: Boolean, private val flipY: Boolean = false, private val h: Float = 40f,
) {
    private fun mx(x: Float, ww: Float) = if (flip) ox + w - x - ww else ox + x
    private fun my(y: Float, hh: Float) = if (flipY) oy + h - y - hh else oy + y
    fun rect(x: Float, y: Float, ww: Float, hh: Float, c: Int) = gfx.rect(mx(x, ww), my(y, hh), ww, hh, c)
    fun oval(x: Float, y: Float, ww: Float, hh: Float, c: Int) = gfx.oval(mx(x, ww), my(y, hh), ww, hh, c)
    fun poly(xs: FloatArray, ys: FloatArray, c: Int) = gfx.poly(
        FloatArray(xs.size) { mx(xs[it], 0f) },
        FloatArray(ys.size) { my(ys[it], 0f) },
        c,
    )
}

// ======================================================================== Hero projectiles & items

class Fireball(x: Float, y: Float, dir: Int) : Entity(x, y, 8f, 8f) {
    private var anim = 0f
    init { vx = dir * 230f; vy = 60f; active = true }

    override fun update(g: Game, dt: Float) {
        anim += dt
        vy = min(vy + 1000f * dt, 300f)
        if (g.level.moveX(this, vx * dt)) { removed = true; g.puff(cx, cy); return }
        g.level.moveY(this, vy * dt)
        if (onGround) vy = -190f
        if (x + w < g.camX - 8 || x > g.camX + g.viewW + 8 || y > g.level.h * TILE) removed = true
        if (g.level.isLavaAt(cx, cy)) { removed = true; g.puff(cx, cy) }
        if (removed) return
        for (e in g.entities) {
            if (e is Enemy && !e.dying && e.active && e.overlaps(this)) {
                if (e is Grumbler && e.isSquashed) continue
                if (e.fireHit(g)) { removed = true; g.puff(cx, cy); return }
            }
        }
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val f = (anim * 16).toInt() % 4
        gfx.sprite(Sprites.fireball, x + ox, y, flipX = f % 2 == 1, flipY = f >= 2)
    }
}

/** The Blaze Blossom power-up: rises out of a block, then waits to be collected. */
class Blossom(x: Float, y: Float) : Entity(x, y, 16f, 16f) {
    private val startY = y
    private var rise = 0f
    private var anim = 0f
    init { active = true }

    val ready get() = rise >= 1f

    override fun update(g: Game, dt: Float) {
        anim += dt
        if (rise < 1f) {
            rise = min(1f, rise + dt * 1.6f)
            y = startY - 16f * rise
        }
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        // While emerging it is drawn behind the tiles, so the block hides its lower part.
        val s = if ((anim * 8).toInt() % 2 == 0) Sprites.blossom1 else Sprites.blossom2
        gfx.sprite(s, x + ox, y)
    }
}

class Coin(x: Float, y: Float) : Entity(x + 4, y + 2, 8f, 12f) {
    override fun update(g: Game, dt: Float) {}
    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val phase = (g.time * 4f) % 4f
        val sw = when {
            phase < 1f -> 16f
            phase < 2f -> 10f
            phase < 3f -> 4f
            else -> 10f
        }
        gfx.sprite(Sprites.coin, x - 4 + ox + (16 - sw) / 2, y - 2, w = sw)
    }
}

class Axe(x: Float, y: Float) : Entity(x, y - 16, 16f, 16f) {
    override fun update(g: Game, dt: Float) {}
    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val bob = if ((g.time * 3).toInt() % 2 == 0) 0f else 1f
        gfx.sprite(Sprites.axe, x + ox, y - bob)
    }
}

class Princess(x: Float, y: Float) : Entity(x, y - 24, 16f, 24f) {
    override fun update(g: Game, dt: Float) {}
    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        gfx.sprite(Sprites.princess, x + ox, y, flipX = g.hero.cx < cx)
        if (!g.bossDefeated && (g.time % 3f) < 1.6f) {
            bubble(gfx, x + ox + 8, y - 6, "HELP!")
        } else if (g.bossDefeated) {
            val t = g.time * 2f
            gfx.text("♥", x + ox + 8 + kotlin.math.sin(t) * 4, y - 6 - (t * 6) % 12, 10f, 0xFFFF6FB5.toInt(), 1)
        }
    }

    private fun bubble(gfx: Gfx, cx: Float, by: Float, s: String) {
        gfx.rect(cx - 16, by - 14, 32f, 12f, 0xFFFFFFFF.toInt())
        gfx.poly(floatArrayOf(cx - 3, cx + 3, cx), floatArrayOf(by - 2, by - 2, by + 2), 0xFFFFFFFF.toInt())
        gfx.text(s, cx, by - 5, 8f, 0xFFD02060.toInt(), 1)
    }
}

/** Exit door at the end of an underground level. */
class Door(x: Float, y: Float) : Entity(x, y - 32, 16f, 32f) {
    override fun update(g: Game, dt: Float) {}
    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        gfx.rect(x + ox - 2, y - 2, 20f, 34f, 0xFF3A1A08.toInt())
        gfx.rect(x + ox, y, 16f, 32f, 0xFF9A5A2A.toInt())
        gfx.rect(x + ox + 7, y, 2f, 32f, 0xFF6A3A12.toInt())
        gfx.oval(x + ox + 11, y + 16, 3f, 3f, 0xFFFFD21F.toInt())
        gfx.text("EXIT", x + ox + 8, y - 5, 7f, 0xFFFFFFFF.toInt(), 1)
    }
}

// ======================================================================== Particles

abstract class Particle(var x: Float, var y: Float) {
    var life = 0f
    var removed = false
    abstract fun update(dt: Float)
    abstract fun draw(g: Game, gfx: Gfx, ox: Float)
}

class Debris(x: Float, y: Float, private var vx: Float, private var vy: Float, private val color: Int) : Particle(x, y) {
    override fun update(dt: Float) {
        life += dt
        vy += 900f * dt
        x += vx * dt
        y += vy * dt
        if (life > 2f) removed = true
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        gfx.rect(x + ox, y, 6f, 6f, color)
        gfx.rect(x + ox + 1, y + 1, 3f, 3f, 0xFF3A1A08.toInt())
    }
}

class CoinPop(x: Float, y: Float) : Particle(x, y) {
    private var vy = -280f
    override fun update(dt: Float) {
        life += dt
        vy += 900f * dt
        y += vy * dt
        if (life > 0.55f) removed = true
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val sw = if ((life * 16).toInt() % 2 == 0) 16f else 6f
        gfx.sprite(Sprites.coin, x + ox + (16 - sw) / 2, y, w = sw)
    }
}

class FloatText(x: Float, y: Float, private val s: String) : Particle(x, y) {
    override fun update(dt: Float) {
        life += dt
        y -= 30f * dt
        if (life > 0.8f) removed = true
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) = gfx.shadowText(s, x + ox, y, 7f, 0xFFFFFFFF.toInt(), 1)
}

class Puff(x: Float, y: Float) : Particle(x, y) {
    override fun update(dt: Float) {
        life += dt
        if (life > 0.2f) removed = true
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val r = 4f + life * 40f
        gfx.oval(x + ox - r / 2, y - r / 2, r, r, argb((200 * (1 - life / 0.2f)).toInt().coerceIn(0, 255), 0xFFE070))
    }
}

internal fun approach(v: Float, target: Float, step: Float): Float =
    if (v < target) min(v + step, target) else max(v - step, target)

internal fun signOf(v: Float) = sign(v).toInt()
