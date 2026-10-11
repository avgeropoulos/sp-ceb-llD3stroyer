package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Sprites
import com.roccoquest.game.TILE
import com.roccoquest.game.argb
import com.roccoquest.game.rainbow
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ============================================================================ Shots

/**
 * A thrown flower power. Boomerangs fly out and come back. From Lv4 shots go through
 * foes ([pierce] = how many extra foes they can hit).
 */
class Shot(
    x: Float, y: Float, private val dir: Int, val kind: Power, val damage: Int,
    private val thrower: Hero, private var pierce: Int = 0, spread: Float = 0f,
) : Thing(x, y, 9f, 9f) {
    private var t = 0f
    private val hitAlready = HashSet<Foe>()
    private var returning = false

    init {
        active = true
        vx = dir * when (kind) {
            Power.FIRE -> 200f
            Power.ICE -> 230f
            Power.GRASS -> 210f
            Power.THUNDER -> 300f
            Power.CRYSTAL -> 240f
            else -> 230f
        }
        vy = if (kind == Power.FIRE) 60f else spread
    }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        when (kind) {
            Power.FIRE -> {
                vy = min(vy + GRAVITY * dt, 300f)
                if (g.level.moveX(this, vx * dt)) { gone(g); return }
                if (g.level.moveY(this, vy * dt) == 1) vy = -170f
            }
            Power.BOOM -> {
                if (t < 0.45f) x += vx * dt
                else {
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
            else -> {
                if (g.level.moveX(this, vx * dt)) { gone(g); return }
                y += vy * dt
            }
        }
        if (t > 2.5f && kind != Power.BOOM) removed = true
        for (f in g.foes) {
            if (f.dying || f.removed || !f.overlaps(this) || f in hitAlready) continue
            if (f.hit(g, damage)) {
                hitAlready += f
                when (kind) {
                    Power.ICE -> if (!f.boss) g.iceBurst(f.cx, f.cy)
                    // Thunder jumps to the next closest bad guy.
                    Power.THUNDER -> g.foes.filter { it !== f && !it.dying && it.active && hypot(it.cx - f.cx, it.cy - f.cy) < 120f }
                        .minByOrNull { hypot(it.cx - f.cx, it.cy - f.cy) }?.let {
                            g.addParticle(Bolt(f.cx, f.cy, it.cx, it.cy, 0xFFFFF27A.toInt()))
                            it.hit(g, (damage + 1) / 2)
                        }
                    else -> {}
                }
                if (kind != Power.BOOM) {
                    if (pierce <= 0) { gone(g); return }
                    pierce--
                }
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
            Power.THUNDER -> {
                val px = x + ox
                gfx.poly(
                    floatArrayOf(px, px + 6, px + 4, px + 10, px + 3, px + 5),
                    floatArrayOf(y + 4, y, y + 3, y + 2, y + 9, y + 5), 0xFFFFF27A.toInt(),
                )
                gfx.oval(px - 2, y, 13f, 9f, 0x55FFFF80)
            }
            Power.CRYSTAL -> {
                val px = x + ox
                gfx.poly(floatArrayOf(px, px + 4.5f, px + 9, px + 4.5f), floatArrayOf(y + 4.5f, y, y + 4.5f, y + 9), 0xFFC890FF.toInt())
                gfx.rect(px + 3.5f, y + 2, 2f, 3f, 0xCCFFFFFF.toInt())
            }
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

/** A Pokemon's small normal attack: a spark, an ember or a water drop. */
class MonShot(x: Float, y: Float, private val dir: Int, private val mon: Mon) : Thing(x, y, 8f, 8f) {
    private var t = 0f
    init { active = true; vx = dir * 240f }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (g.level.moveX(this, vx * dt) || t > 0.9f) { removed = true; g.puff(cx, cy); return }
        for (f in g.foes) if (!f.dying && f.active && f.overlaps(this) && f.hit(g, 1)) { removed = true; return }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val c = mon.color
        gfx.oval(x + ox - 1, y - 1, 10f, 10f, argb(90, c))
        gfx.oval(x + ox + 1, y + 1, 6f, 6f, c)
        gfx.oval(x + ox + 2.5f, y + 2.5f, 3f, 3f, 0xFFFFFFFF.toInt())
    }
}

/** A winged blue shell that flies to the nearest bad guy and explodes. */
class HomingShell(x: Float, y: Float, private val dir: Int, private val damage: Int) : Thing(x, y, 14f, 12f) {
    private var t = 0f
    init { active = true; vx = dir * 150f; vy = -60f }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        val target = g.foes.filter { it.active && !it.dying && it.x + it.w > g.camX && it.x < g.camX + g.viewW && it !is EndCrystal }
            .minByOrNull { hypot(it.cx - cx, it.cy - cy) }
        if (target != null && t > 0.15f) {
            val dx = target.cx - cx
            val dy = target.cy - cy
            val d = hypot(dx, dy).coerceAtLeast(1f)
            vx = approach(vx, dx / d * 230f, 600f * dt)
            vy = approach(vy, dy / d * 230f, 600f * dt)
        }
        x += vx * dt
        y += vy * dt
        val hitFoe = g.foes.any { it.active && !it.dying && it.overlaps(this) }
        if (hitFoe || t > 2.5f || g.level.solidAt(cx, cy)) explode(g)
    }

    private fun explode(g: TeamGame) {
        removed = true
        g.addParticle(Boom(cx, cy, 30f))
        g.sfx(Sound.BOSS_HIT)
        g.shake = 0.2f
        for (f in g.foes) if (f.active && !f.dying && hypot(f.cx - cx, f.cy - cy) < 44f) f.hit(g, damage)
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val flap = (t * 16).toInt() % 2 == 0
        gfx.oval(x + ox - 4, y - (if (flap) 4f else 0f), 7f, 8f, 0xFFFFFFFF.toInt())
        gfx.oval(x + ox + 11, y - (if (flap) 4f else 0f), 7f, 8f, 0xFFFFFFFF.toInt())
        gfx.sprite(Sprites.blueShell, x + ox - 1, y - 3, w = 16f, h = 16f, flipX = vx < 0)
    }
}

/** The amethyst sword's magic slash: a purple crescent that flies forward. */
class SwordBeam(x: Float, y: Float, private val dir: Int) : Thing(x, y, 12f, 16f) {
    private var t = 0f
    private val hitAlready = HashSet<Foe>()
    init { active = true; vx = dir * 260f }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        x += vx * dt
        if (t > 0.7f) removed = true
        for (f in g.foes) if (!f.dying && f.active && f !in hitAlready && f.overlaps(this)) {
            hitAlready += f
            f.hit(g, 4)
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val a = ((1f - t / 0.7f) * 255).toInt().coerceIn(0, 255)
        val px = x + ox
        val f = dir.toFloat()
        gfx.poly(
            floatArrayOf(px + 6 - f * 4, px + 6 + f * 6, px + 6 - f * 4, px + 6 + f * 1),
            floatArrayOf(y, y + 8, y + 16, y + 8), argb(a, 0xC890FF),
        )
        gfx.poly(
            floatArrayOf(px + 6 - f * 2, px + 6 + f * 4, px + 6 - f * 2, px + 6 + f * 1),
            floatArrayOf(y + 3, y + 8, y + 13, y + 8), argb(a, 0xFFFFFF),
        )
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

/** Things that pop out of blocks and slide or bounce along. */
abstract class Pickup(x: Float, y: Float, private val bouncy: Boolean) : Thing(x + 2, y, 12f, 12f) {
    protected var t = 0f
    init { vx = 50f; vy = -150f; active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        vy = min(vy + GRAVITY * dt, MAX_FALL)
        if (g.level.moveX(this, vx * dt)) vx = -vx
        if (g.level.moveY(this, vy * dt) == 1 && bouncy) vy = -260f
        if (y > g.level.h * TILE) removed = true
    }

    abstract fun collect(g: TeamGame)
}

class HeartItem(x: Float, y: Float) : Pickup(x, y, false) {
    override fun collect(g: TeamGame) = g.healHeart()
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        TeamArt.heart(gfx, x + ox, y + sin(t * 6), 12f, 0xFFE52521.toInt())
        gfx.oval(x + ox + 2, y + 2, 3f, 3f, 0xAAFFFFFF.toInt())
    }
}

/** A bouncing Super Star: 10 seconds of invincibility. */
class StarItem(x: Float, y: Float) : Pickup(x, y, true) {
    override fun collect(g: TeamGame) = g.startStar()
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        gfx.sprite(if ((t * 8).toInt() % 2 == 0) Sprites.star1 else Sprites.star2, x + ox - 2, y - 2, w = 16f, h = 16f)
    }
}

/** A Mini Mushroom: Rocco shrinks to tiny size and floats high. */
class MiniItem(x: Float, y: Float) : Pickup(x, y, false) {
    override fun collect(g: TeamGame) = g.startMini()
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        gfx.sprite(Sprites.miniMushroom, x + ox + 2, y + 4, w = 8f, h = 8f)
    }
}

/** A gift box from Ring-Ding Frog or a mystery block: random ore inside! */
class GiftBox(x: Float, y: Float) : Pickup(x, y, false) {
    init { vx = 30f }
    override fun collect(g: TeamGame) = g.openGift(cx, y)
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        gfx.rect(px, y + 3, 12f, 9f, solidRainbow(t * 0.5f))
        gfx.rect(px - 1, y, 14f, 4f, solidRainbow(t * 0.5f, 0.3f))
        gfx.rect(px + 5, y, 2f, 12f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 2, y - 4, 5f, 5f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 5, y - 4, 5f, 5f, 0xFFFFFFFF.toInt())
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

/** The house the team can build out of stone and iron. */
class House(x: Float, bottom: Float) : Thing(x, bottom - 56, 72f, 56f) {
    override fun update(g: TeamGame, dt: Float) {}
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) = TeamArt.house(gfx, x + ox, bottom, g.time)
}

/** A bed inside the house. Sleep in it to save the game. */
class Bed(x: Float, bottom: Float) : Thing(x, bottom - 12, 26f, 12f) {
    override fun update(g: TeamGame, dt: Float) {}
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        TeamArt.bed(gfx, x + ox, bottom)
        if (overlaps(g.leader) && g.state == TeamGame.State.PLAY && (g.time * 2).toInt() % 2 == 0) {
            gfx.shadowText("SLEEP?", cx + ox, y - 18, 7f, 0xFFFFF2C8.toInt(), 1)
        }
    }
}

/** Ring-Ding Frog zooms past on his scooter, singing, bowling over foes and dropping a present. */
class Frog(x: Float, private val baseY: Float) : Thing(x, baseY, 24f, 24f) {
    private var t = 0f
    private var nextLine = 0.2f
    private var line = 0
    private var gave = false
    private val lines = listOf("Ring-ding-ding-ding!", "Bing-bong-a-dong!", "Ribbit-a-dee-doo!", "A present for you!", "Brrrm brrrm! Ding!")
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
        if (!gave && x > g.leader.x + 20) {
            gave = true
            g.spawn(GiftBox(cx, y - 10))
        }
        for (f in g.foes) if (!f.dying && !f.boss && f.overlaps(this)) f.hit(g, 99)
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

/** A big TNT explosion. */
class Boom(x: Float, y: Float, private val size: Float = 30f) : Particle(x, y) {
    override fun update(dt: Float) { life -= dt * 2f }
    override fun draw(gfx: Gfx, ox: Float) {
        val r = size * (1.2f - life)
        val a = (life * 255).toInt().coerceIn(0, 255)
        gfx.oval(x + ox - r, y - r, r * 2, r * 2, argb(a, 0xFF8A00))
        gfx.oval(x + ox - r * 0.6f, y - r * 0.6f, r * 1.2f, r * 1.2f, argb(a, 0xFFE14D))
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

/** A lightning bolt (or other zap) between two points. */
class Bolt(private val x0: Float, private val y0: Float, x1: Float, y1: Float, private val color: Int = 0xFFFFF27A.toInt(), thick: Float = 1.5f) :
    Particle(x1, y1) {
    private val seed = Random.nextInt()
    private val th = thick
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
            val nX = -dy / len * th
            val nY = dx / len * th
            gfx.poly(
                floatArrayOf(px + ox + nX, nx + ox + nX, nx + ox - nX, px + ox - nX),
                floatArrayOf(py + nY, ny + nY, ny - nY, py - nY),
                color,
            )
            px = nx
            py = ny
        }
    }
}

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

/** Bloom's vine whip: a green line that lashes out and back. */
class Vine(x: Float, y: Float, private val dir: Int) : Particle(x, y) {
    init { life = 0.25f }
    override fun update(dt: Float) { life -= dt }
    override fun draw(gfx: Gfx, ox: Float) {
        val len = 34f * sin(life / 0.25f * Math.PI.toFloat())
        val x1 = x + dir * len
        val left = minOf(x, x1)
        gfx.rect(left + ox, y - 1, abs(x1 - x), 3f, 0xFF2E9E3A.toInt())
        gfx.oval(x1 + ox - 3, y - 3, 6f, 6f, 0xFF3CE070.toInt())
    }
}

/** The Black Flower blooming: a dark swirl that pulls everything in. */
class BlackBloom(x: Float, y: Float) : Particle(x, y) {
    private var t = 0f
    init { life = 1.2f }
    override fun update(dt: Float) { life -= dt; t += dt }
    override fun draw(gfx: Gfx, ox: Float) {
        val a = (min(1f, life) * 220).toInt()
        val r = TeamGame.BLACK_RANGE * (1f - min(1f, t * 1.2f))
        gfx.oval(x + ox - r, y - r, r * 2, r * 2, argb(a / 3, 0x2A0A3A))
        for (i in 0 until 10) {
            val ang = i * 0.628f + t * 8
            val rr = r * 0.9f
            gfx.oval(x + ox + kotlin.math.cos(ang) * rr - 4, y + sin(ang) * rr - 4, 8f, 8f, argb(a, 0xC060FF))
        }
        TeamArt.blackFlower(gfx, x + ox - 14, y - 16, 28f, t * 4)
    }
}

/** The big picture for a Pokemon SUPER move. [w] is how far it reaches. */
class SuperFx(x: Float, y: Float, private val mon: Mon, private val dir: Int, private val w: Float) : Particle(x, y) {
    private var t = 0f
    init { life = 1.1f }
    override fun update(dt: Float) { life -= dt; t += dt }
    override fun draw(gfx: Gfx, ox: Float) {
        val a = (min(1f, life * 2) * 255).toInt()
        when (mon) {
            Mon.SPARKY -> gfx.oval(x + ox - 30, y - 30, 60f, 60f, argb(a / 3, 0xFFF27A))
            Mon.BLAZE -> {
                // A cone of flames
                for (i in 0 until 14) {
                    val d = (i * 13f + t * 260f) % w
                    val r = 5f + d * 0.07f
                    val c = if (i % 2 == 0) 0xFF8A00 else 0xFFE14D
                    gfx.oval(x + ox + dir * d - r, y - r + sin(t * 20 + i) * 3, r * 2, r * 2, argb(a, c))
                }
            }
            Mon.HYDRO -> for (side in intArrayOf(-1, 1)) {
                val len = min(w, t * 900f)
                val left = if (side > 0) x else x - len
                gfx.rect(left + ox, y - 8, len, 16f, argb(a, 0x4A8AE8))
                gfx.rect(left + ox, y - 4, len, 8f, argb(a, 0xBFE8FF))
                gfx.oval(x + ox + side * len - 12, y - 12, 24f, 24f, argb(a, 0xBFE8FF))
            }
            Mon.EMBER -> for (i in 0 until 16) {
                val ang = i * 0.3927f + t * 6
                val r = 20f + t * 80f
                gfx.oval(x + ox + kotlin.math.cos(ang) * r - 7, y + sin(ang) * r * 0.6f - 7, 14f, 14f, argb(a, if (i % 2 == 0) 0xFF6A00 else 0xFFE14D))
            }
            Mon.SPLASHY -> for (i in 0 until 12) {
                val d = (i * 19f + t * 300f) % w
                val r = 4f + (i % 3) * 3f
                gfx.oval(x + ox + dir * d - r, y - r + sin(t * 10 + i) * 8, r * 2, r * 2, argb(a, 0xBFE8FF))
            }
            Mon.MYSTIC -> {
                val r = t * 500f
                gfx.oval(x + ox - r, y - r * 0.6f, r * 2, r * 1.2f, argb(a / 3, 0xFF9AD0))
                gfx.oval(x + ox - r * 0.7f, y - r * 0.4f, r * 1.4f, r * 0.8f, argb(a / 3, 0xFFFFFF))
            }
            Mon.PUNCHO -> {
                val reach = min(w, t * 600f)
                val fx = x + ox + dir * reach
                gfx.oval(fx - 16, y - 16, 32f, 32f, argb(a, 0x8A9AB8))
                gfx.oval(fx - 10, y - 10, 20f, 20f, argb(a, 0xFFE14D))
                gfx.shadowText("POW!", fx, y - 20, 14f, argb(a, 0xFF3A3A), 1)
            }
            Mon.SPOOKY -> {
                gfx.rect(x + ox - 600, 0f, 1200f, 240f, argb(a / 3, 0x2A0A4A))
                for (i in 0 until 6) {
                    val gx = x + ox + kotlin.math.cos(i * 1.05f + t * 3) * (40f + t * 120)
                    gfx.oval(gx - 8, y - 30 + sin(i + t * 4) * 20, 16f, 18f, argb(a, 0xE8E0FF))
                }
            }
            Mon.BLOOM -> {
                val len = min(w, t * 1200f)
                val left = if (dir > 0) x else x - len
                gfx.rect(left + ox, y - 14, len, 28f, argb(a / 2, 0xFFF27A))
                gfx.rect(left + ox, y - 8, len, 16f, argb(a, 0xFFFFC8))
                gfx.rect(left + ox, y - 3, len, 6f, argb(a, 0xFFFFFF))
            }
        }
        if (t < 0.3f && mon == Mon.SPARKY) {
            gfx.oval(x + ox - 6, y - 6, 12f, 12f, argb(220, 0xFFFFFF))
        }
    }
}
