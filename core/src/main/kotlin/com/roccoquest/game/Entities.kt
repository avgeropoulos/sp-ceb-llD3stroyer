package com.roccoquest.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
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

enum class Power {
    SMALL, BIG, FIRE, ICE, BOOM, MINI;

    /** Big Rocco: breaks bricks and survives one hit. */
    val isBig get() = this == BIG || this == FIRE || this == ICE || this == BOOM
    val canThrow get() = this == FIRE || this == ICE || this == BOOM
}

class Hero(x: Float, y: Float) : Body(x, y, 12f, SMALL_H) {
    companion object {
        const val SMALL_H = 15f
        const val BIG_H = 23f
        const val MINI_H = 8f
        const val MAX_SPEED = 115f
        const val RUN_SPEED = 175f
        const val JUMP_V = -310f
        const val STAR_TIME = 10f
    }

    var power = Power.SMALL
        set(value) { field = value; resize() }
    var crouching = false
        set(value) { field = value; resize() }

    /** Keeps the feet (and horizontal centre) in place when the hitbox changes size. */
    private fun resize() {
        val newW = if (power == Power.MINI) 7f else 12f
        val newH = when {
            power == Power.MINI -> MINI_H
            !power.isBig || crouching -> SMALL_H
            else -> BIG_H
        }
        x += (w - newW) / 2
        y += h - newH
        w = newW
        h = newH
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
    var running = false
    var slamming = false
    var slamPause = 0f
    var starTime = 0f

    private fun skin(p: Power): Triple<Sprite, Sprite, Sprite> = when (p) {
        Power.SMALL, Power.MINI -> Triple(Sprites.smallStand, Sprites.smallWalk, Sprites.smallJump)
        Power.BIG -> Triple(Sprites.bigStand, Sprites.bigWalk, Sprites.bigJump)
        Power.FIRE -> Triple(Sprites.fireStand, Sprites.fireWalk, Sprites.fireJump)
        Power.ICE -> Triple(Sprites.iceStand, Sprites.iceWalk, Sprites.iceJump)
        Power.BOOM -> Triple(Sprites.boomStand, Sprites.boomWalk, Sprites.boomJump)
    }

    fun draw(gfx: Gfx, ox: Float, dead: Boolean, time: Float) {
        if (!visible) return
        if (invuln > 0 && ((invuln * 20).toInt() % 2 == 0)) return
        // With a star, Rocco flashes through all his outfits.
        val look = if (starTime > 0 && power.isBig) {
            listOf(Power.BIG, Power.FIRE, Power.ICE, Power.BOOM)[(time * 14).toInt() % 4]
        } else power
        val (stand, walk, jump) = skin(look)
        val frame = when {
            dead -> Sprites.smallJump
            slamming -> stand
            !onGround -> jump
            abs(vx) > 5f && (walkAnim.toInt() % 2 == 1) && !crouching -> walk
            else -> stand
        }
        val mini = power == Power.MINI
        val dw = if (mini) 8f else frame.w.toFloat()
        var dh = if (mini) 8f else frame.h.toFloat()
        if (crouching && !mini) dh = if (power.isBig) 16f else 11f
        val sx = cx - dw / 2 + ox
        val sy = y + h - dh
        if (starTime > 0) {
            val glow = listOf(0xFFFFE14D, 0xFFFF6FB5, 0xFF6FD3FF, 0xFF7ED957)[(time * 10).toInt() % 4].toInt()
            gfx.oval(sx - 3, sy - 3, dw + 6, dh + 6, argb(110, glow))
        }
        val spin = slamming && slamPause > 0 && (time * 24).toInt() % 2 == 0
        gfx.sprite(frame, sx, sy, flipX = (facing < 0) != spin, w = dw, h = dh)
        if (flash > 0) {
            gfx.oval(sx - 2, sy - 2, dw + 4f, dh + 4f, argb((flash * 300).toInt().coerceIn(0, 200), 0xFFF3A0))
        }
    }
}

// ======================================================================== Enemies

abstract class Enemy(x: Float, y: Float, w: Float, h: Float) : Entity(x, y, w, h) {
    /** Knocked out: tumbling off screen, no longer interacts. */
    var dying = false
    open val stompable = true

    /** Enemies a star or slam can't hurt and that hurt Rocco when he lands on them. */
    open val spiky = false
    /** Ice turns these enemies into ice blocks. */
    open val freezable = true

    open fun stomp(g: Game) { knockOut(g) }
    open fun touchHero(g: Game) { g.hurtHero() }
    /** Called when a hero fireball hits; return true if the fireball is used up. */
    open fun fireHit(g: Game): Boolean { knockOut(g); g.addScore(200, cx, y); return true }

    /** Called when an iceball hits; by default the enemy is frozen solid. */
    open fun iceHit(g: Game): Boolean {
        if (!freezable) return fireHit(g)
        removed = true
        g.spawn(FrozenBlock(x - 1, bottom, w + 2, max(h, 14f)))
        g.addScore(200, cx, y)
        g.sfx(Sound.BUMP)
        return true
    }

    /** Called when the boomerang hits; return true if it actually hurt the enemy. */
    open fun boomHit(g: Game): Boolean { fireHit(g); return true }

    /** A star-powered Rocco bowls enemies over just by touching them. */
    open fun starHit(g: Game) { knockOut(g); g.addScore(200, cx, y) }

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

class Grumbler(x: Float, y: Float, winged: Boolean = false) : Enemy(x + 1, y - 14, 14f, 14f) {
    /** Winged grumblers hop along; stomping one clips its wings. */
    var winged = winged
        private set
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
        if (winged && onGround) vy = -290f
    }

    override fun stomp(g: Game) {
        if (winged) {
            winged = false
            vy = 0f
            g.sfx(Sound.STOMP)
            g.addScore(100, cx, y)
            return
        }
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
            val flap = (anim * 10).toInt() % 2 == 0
            if (winged && !dying) wing(gfx, x + ox - 5, flap, true)
            gfx.sprite(s, x - 1 + ox, bottom - 16, flipX = (anim * 6).toInt() % 2 == 0, flipY = dying)
            if (winged && !dying) wing(gfx, x + ox + 13, flap, false)
        }
    }

    private fun wing(gfx: Gfx, wx: Float, up: Boolean, left: Boolean) {
        val wy = bottom - 16 + if (up) -2f else 3f
        val tip = if (left) wx - 2 else wx + 8
        gfx.poly(
            floatArrayOf(wx + 3, tip, wx + 3), floatArrayOf(wy + 2, wy + if (up) -6f else 9f, wy + 9),
            0xFF5A5A5A.toInt(),
        )
        gfx.oval(wx, wy, 7f, 9f, 0xFFFFFFFF.toInt())
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

    override val spiky = true
    override val freezable = false
    override fun fireHit(g: Game) = true
    override fun iceHit(g: Game) = true
    override fun boomHit(g: Game) = false
    override fun starHit(g: Game) {}

    override fun touchHero(g: Game) { if (wait <= 0) g.hurtHero() }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        if (wait > 0) return
        gfx.sprite(Sprites.podoboo, x - 1 + ox, y, flipY = vy > 0)
    }
}

// ======================================================================== King Krag (boss)

class Krag(x: Float, y: Float, val maxHp: Int = 10) : Enemy(x - 10, y - 40, 36f, 40f) {
    var hp = maxHp
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

    override val spiky = true
    override val freezable = false
    override fun iceHit(g: Game) = damage(g, 1)
    override fun boomHit(g: Game) = damage(g, 1)
    override fun starHit(g: Game) {}
    override fun fireHit(g: Game) = damage(g, 1)

    fun damage(g: Game, n: Int): Boolean {
        if (dying) return true
        hp -= n
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

    override val spiky = true
    override val freezable = false

    /** Hero fireballs fizzle out against the boss's flames. */
    override fun fireHit(g: Game): Boolean = true
    override fun iceHit(g: Game): Boolean { removed = true; g.puff(cx, cy); return true }
    override fun boomHit(g: Game) = false
    override fun starHit(g: Game) {}

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

/** A bouncing shot: a fireball, or an iceball (with [ice]) that freezes enemies. */
class Fireball(x: Float, y: Float, dir: Int, val ice: Boolean = false) : Entity(x, y, 8f, 8f) {
    private var anim = 0f
    init { vx = dir * (if (ice) 190f else 230f); vy = 60f; active = true }

    override fun update(g: Game, dt: Float) {
        anim += dt
        vy = min(vy + 1000f * dt, 300f)
        if (g.level.moveX(this, vx * dt)) { removed = true; g.puff(cx, cy); return }
        g.level.moveY(this, vy * dt)
        if (onGround) vy = if (ice) -150f else -190f
        if (x + w < g.camX - 8 || x > g.camX + g.viewW + 8 || y > g.level.h * TILE) removed = true
        if (g.level.isLavaAt(cx, cy)) { removed = true; g.puff(cx, cy) }
        if (removed) return
        for (e in g.entities) {
            if (e is Enemy && !e.dying && e.active && e.overlaps(this)) {
                if (e is Grumbler && e.isSquashed) continue
                if (if (ice) e.iceHit(g) else e.fireHit(g)) { removed = true; g.puff(cx, cy); return }
            }
        }
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val f = (anim * 16).toInt() % 4
        gfx.sprite(if (ice) Sprites.iceball else Sprites.fireball, x + ox, y, flipX = f % 2 == 1, flipY = f >= 2)
    }
}

enum class Item { MUSHROOM, FIRE, ICE, BOOM, STAR, MINI }

/** A power-up that rises out of a ? block. Mushrooms slide along and stars bounce. */
class PowerItem(x: Float, y: Float, val kind: Item) : Entity(x + 1, y, 14f, 16f) {
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
            if (ready) vx = when (kind) {
                Item.MUSHROOM, Item.MINI -> if (g.hero.cx < cx) -55f else 55f
                Item.STAR -> if (g.hero.cx < cx) -75f else 75f
                else -> 0f
            }
            return
        }
        if (vx == 0f) return
        vy = min(vy + 900f * dt, 400f)
        if (g.level.moveX(this, vx * dt)) vx = -vx
        g.level.moveY(this, vy * dt)
        if (kind == Item.STAR && onGround) vy = -300f
        if (y > g.level.h * TILE + 16) removed = true
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        // While emerging it is drawn behind the tiles, so the block hides its lower part.
        val alt = (anim * 8).toInt() % 2 == 0
        val s = when (kind) {
            Item.MUSHROOM -> Sprites.mushroom
            Item.MINI -> Sprites.miniMushroom
            Item.FIRE -> if (alt) Sprites.blossom1 else Sprites.blossom2
            Item.ICE -> Sprites.iceFlower
            Item.BOOM -> Sprites.boomFlower
            Item.STAR -> if (alt) Sprites.star1 else Sprites.star2
        }
        if (kind == Item.MINI) gfx.sprite(s, x - 1 + ox + 4, y + 8, w = 8f, h = 8f)
        else gfx.sprite(s, x - 1 + ox, y)
    }
}

/** Rocco's boomerang: flies out, curves back to him, and grabs coins on the way. */
class Boomerang(x: Float, y: Float, private val dir: Int) : Entity(x, y, 12f, 9f) {
    private var t = 0f
    private val hit = HashSet<Entity>()
    init { vx = dir * 280f; active = true }

    override fun update(g: Game, dt: Float) {
        t += dt
        val h = g.hero
        if (t < 0.45f) {
            vx -= dir * 520f * dt
            vy = (h.cy - 6 - y) * 2f
        } else {
            // Home back in on Rocco.
            val dx = h.cx - cx
            val dy = h.cy - cy
            val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val speed = 260f
            vx = dx / d * speed
            vy = dy / d * speed
            if (d < 10f || t > 3f) { removed = true; return }
        }
        x += vx * dt
        y += vy * dt
        for (e in g.entities) {
            if (e === this || !e.active || e.removed || !e.overlaps(this) || e in hit) continue
            if (e is Coin) { e.removed = true; g.collectCoin(e.cx, e.y) }
            if (e is Enemy && !e.dying && !(e is Grumbler && e.isSquashed)) {
                if (e.boomHit(g)) hit += e
            }
        }
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val f = (t * 20).toInt() % 4
        gfx.sprite(Sprites.boomerang, x + ox, y, flipX = f == 1 || f == 2, flipY = f >= 2)
    }
}

/** An enemy frozen by an iceball. Rocco can stand on it; it shatters after a while. */
class FrozenBlock(x: Float, bottom: Float, w: Float, h: Float) : Entity(x, bottom - h, w, h) {
    private var life = 0f
    init { active = true }

    override fun update(g: Game, dt: Float) {
        life += dt
        if (life > 7f) shatter(g)
    }

    fun shatter(g: Game) {
        if (removed) return
        removed = true
        g.debris(x, y, 0xFF9FE6FF.toInt())
        g.sfx(Sound.BREAK)
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val shake = if (life > 6f && (life * 30).toInt() % 2 == 0) 1f else 0f
        gfx.rect(x + ox + shake, y, w, h, 0xFF1E8BE6.toInt())
        gfx.rect(x + ox + 1 + shake, y + 1, w - 2, h - 2, 0xCC9FE6FF.toInt())
        gfx.rect(x + ox + 3 + shake, y + 3, 3f, h - 8, 0xEEFFFFFF.toInt())
        gfx.rect(x + ox + 3 + shake, y + 3, w - 8, 2f, 0xEEFFFFFF.toInt())
    }
}

/** Spiked balls dropped by Krag Jr. They bounce once, then roll toward Rocco. */
class SpikeBall(x: Float, y: Float) : Enemy(x, y, 12f, 12f) {
    private var life = 0f
    private var bounced = false
    override val stompable = false
    override val spiky = true

    override fun update(g: Game, dt: Float) {
        if (updateDying(g, dt)) return
        life += dt
        if (life > 8f) { removed = true; g.puff(cx, cy); return }
        walk(g, dt)
        if (onGround && !bounced) {
            bounced = true
            vy = -170f
            vx = if (g.hero.cx < cx) -70f else 70f
        }
    }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val f = (life * 10).toInt() % 2 == 0
        gfx.sprite(Sprites.spikeBall, x + ox, y, flipX = f, flipY = dying)
    }
}

/** Krag's son, who harasses Rocco from his flying clown car in the later levels. */
class KragJr(x: Float, y: Float, val maxHp: Int = 6) : Enemy(x, y - 34, 30f, 34f) {
    enum class Mode { WAIT, FLY, SWOOP, HURT, BEATEN }

    var hp = maxHp
        private set
    var mode = Mode.WAIT
        private set
    private var t = 0f
    private var modeTime = 0f
    private var throwTimer = 2f
    private var swoopTimer = 5f
    private var swoopX = 0f
    private var swoopY = 0f
    private val minY = 50f

    val engaged get() = mode != Mode.WAIT && mode != Mode.BEATEN

    private fun setMode(m: Mode) { mode = m; modeTime = 0f }

    override fun update(g: Game, dt: Float) {
        t += dt
        modeTime += dt
        val h = g.hero
        when (mode) {
            Mode.WAIT -> {
                y += sin(t * 3) * 0.3f
                if (h.cx > x - g.viewW * 0.55f) {
                    setMode(Mode.FLY)
                    g.onJrEngaged()
                }
            }
            Mode.FLY -> {
                val tx = h.cx + sin(t * 0.9f) * 90f - w / 2
                val ty = (h.y - 80f).coerceIn(minY, 140f)
                x += (tx - x).coerceIn(-85f * dt, 85f * dt)
                y += (ty - y).coerceIn(-70f * dt, 70f * dt)
                throwTimer -= dt
                if (throwTimer <= 0) {
                    g.spawn(SpikeBall(cx - 6, bottom - 6).also { it.active = true })
                    g.sfx(Sound.KICK)
                    throwTimer = 2.2f + Random.nextFloat()
                }
                swoopTimer -= dt
                if (swoopTimer <= 0) {
                    swoopX = h.cx - w / 2
                    swoopY = h.y - 6
                    setMode(Mode.SWOOP)
                }
            }
            Mode.SWOOP -> {
                val dx = swoopX - x
                val dy = swoopY - y
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d < 6f || modeTime > 1.3f) {
                    swoopTimer = 4f + Random.nextFloat() * 2f
                    setMode(Mode.FLY)
                } else {
                    x += dx / d * 200f * dt
                    y += dy / d * 200f * dt
                }
            }
            Mode.HURT -> {
                y = max(minY, y - 90f * dt)
                if (modeTime > 1.3f) setMode(Mode.FLY)
            }
            Mode.BEATEN -> {
                x += 110f * dt
                y -= 100f * dt
                if (y < -80f) removed = true
            }
        }
    }

    /** Stomps do 2 damage, a butt slam 3, shots and boomerangs 1. */
    fun damage(g: Game, n: Int): Boolean {
        if (mode == Mode.HURT || mode == Mode.BEATEN || mode == Mode.WAIT) return mode != Mode.WAIT
        hp -= n
        g.sfx(Sound.BOSS_HIT)
        if (hp <= 0) {
            setMode(Mode.BEATEN)
            dying = true
            g.addScore(3000, cx, y)
            g.onJrDefeated()
        } else {
            setMode(Mode.HURT)
        }
        return true
    }

    override val freezable = false
    override fun stomp(g: Game) { damage(g, 2) }
    override fun fireHit(g: Game) = damage(g, 1)
    override fun iceHit(g: Game) = damage(g, 1)
    override fun boomHit(g: Game) = damage(g, 1)
    override fun starHit(g: Game) { damage(g, 2) }
    override fun touchHero(g: Game) { if (mode == Mode.FLY || mode == Mode.SWOOP) g.hurtHero() }

    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val px = x + ox
        val flash = mode == Mode.HURT && (modeTime * 20).toInt() % 2 == 0
        val spin = mode == Mode.BEATEN
        val tilt = if (spin) sin(t * 20) * 3f else 0f
        // Krag Jr. pokes out of the top of the car.
        val skin = if (flash) 0xFFFFFFFF.toInt() else 0xFFF2B33D.toInt()
        gfx.oval(px + 7, y - 2 + tilt, 16f, 15f, skin)
        gfx.poly(floatArrayOf(px + 10, px + 15, px + 20), floatArrayOf(y + 1 + tilt, y - 8 + tilt, y + 1 + tilt), 0xFFE53A1E.toInt())
        gfx.poly(floatArrayOf(px + 8, px + 6, px + 11), floatArrayOf(y + 2 + tilt, y - 4 + tilt, y + tilt), 0xFFFFF2C8.toInt())
        gfx.poly(floatArrayOf(px + 22, px + 24, px + 19), floatArrayOf(y + 2 + tilt, y - 4 + tilt, y + tilt), 0xFFFFF2C8.toInt())
        gfx.oval(px + 10, y + 3 + tilt, 4f, 5f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 16, y + 3 + tilt, 4f, 5f, 0xFFFFFFFF.toInt())
        gfx.rect(px + 11, y + 5 + tilt, 2f, 3f, 0xFF101010.toInt())
        gfx.rect(px + 17, y + 5 + tilt, 2f, 3f, 0xFF101010.toInt())
        gfx.rect(px + 12, y + 9 + tilt, 6f, 2f, 0xFF8A1010.toInt())
        gfx.poly(floatArrayOf(px + 13, px + 14, px + 15), floatArrayOf(y + 9 + tilt, y + 12 + tilt, y + 9 + tilt), 0xFFFFFFFF.toInt())
        // The clown car: a white bowl with a painted face and a propeller underneath.
        val car = if (flash) 0xFFFFD0D0.toInt() else 0xFFF4F4F4.toInt()
        val cy = y + 10
        gfx.oval(px - 1, cy - 2, 32f, 7f, 0xFFB8B8C8.toInt())
        gfx.oval(px, cy, 30f, 22f, car)
        gfx.rect(px, cy, 30f, 6f, car)
        gfx.oval(px + 6, cy + 7, 6f, 6f, 0xFF101010.toInt())
        gfx.oval(px + 18, cy + 7, 6f, 6f, 0xFF101010.toInt())
        gfx.oval(px + 7, cy + 8, 2f, 2f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 19, cy + 8, 2f, 2f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 12, cy + 11, 6f, 5f, 0xFFE53A1E.toInt())
        gfx.poly(floatArrayOf(px + 6, px + 15, px + 24, px + 15), floatArrayOf(cy + 15, cy + 20, cy + 15, cy + 17), 0xFFE53A1E.toInt())
        gfx.oval(px + 2, cy + 2, 5f, 4f, 0x88FFFFFF.toInt())
        val prop = 4f + kotlin.math.abs(sin(t * 30)) * 14f
        gfx.rect(px + 14, cy + 21, 2f, 4f, 0xFF606070.toInt())
        gfx.oval(px + 15 - prop / 2, cy + 24, prop, 3f, 0xFF606070.toInt())
        if (hp in 1 until maxHp && mode != Mode.BEATEN) {
            gfx.rect(px, y - 14, 30f, 3f, 0xFF000000.toInt())
            gfx.rect(px, y - 14, 30f * hp / maxHp, 3f, 0xFFE53A1E.toInt())
        }
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

/** Pip, who waits in the first castle with news of where Rosalie was taken. */
class Pip(x: Float, y: Float) : Entity(x, y - 16, 16f, 16f) {
    override fun update(g: Game, dt: Float) {}
    override fun draw(g: Game, gfx: Gfx, ox: Float) {
        val hop = if (g.bossDefeated && (g.time * 4).toInt() % 2 == 0) -2f else 0f
        gfx.sprite(Sprites.pip, x + ox, y + hop, flipX = g.hero.cx < cx)
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
