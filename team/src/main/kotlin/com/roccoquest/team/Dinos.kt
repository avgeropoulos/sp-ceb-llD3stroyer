package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Voice
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * DINO WORLD's dinosaurs. Every one of them can be captured with Cappy, and each gives Rocco its own power
 * (see [CaptureKind]): RAPTOR runs super fast, TRICERATOPS horn-charges, PTERODACTYL flies, STEGOSAURUS has a
 * spiky back nothing can hurt, ANKYLOSAURUS smashes with its tail club and BABY REX does a big ROAR.
 * Level chars: '(' raptor, ')' triceratops, '[' pterodactyl, ']' stegosaurus, '_' ankylosaurus, '`' baby rex,
 * '"' a free Cappy hat, and '\'' REX KRAG (the Dino World boss).
 */
abstract class DinoFoe(x: Float, bottom: Float, w: Float, h: Float, hp: Int) : Foe(x, bottom - h, w, h, hp) {
    protected var anim = 0f
    val facing get() = if (vx < 0f) -1 else 1

    /** The shapes; drawn twice (dark outline first). [f] is the facing as a float. */
    protected abstract fun shapes(g: TeamGame, gfx: Gfx, px: Float, f: Float)

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        if (gfx !is SilhouetteGfx) shapes(g, SilhouetteGfx(if (gfx is TintGfx) gfx.inner else gfx, 0.8f), px, facing.toFloat())
        shapes(g, gfx, px, facing.toFloat())
        drawFlash(gfx, ox)
    }

    /** Legs that step while walking. */
    protected fun legs(gfx: Gfx, xs: FloatArray, legW: Float, legH: Float, c: Int) {
        val step = if (abs(vx) > 5f && onGround) sin(anim * 14f) * 1.5f else 0f
        for ((i, lx) in xs.withIndex()) gfx.rect(lx, bottom - legH + (if (i % 2 == 0) step else -step).coerceAtMost(0f), legW, legH, c)
    }

    /** A dino-style eye. */
    protected fun eye(gfx: Gfx, x: Float, y: Float) {
        gfx.oval(x - 2f, y - 2f, 4f, 4f, 0xFFFFFFFF.toInt())
        gfx.oval(x - 0.8f, y - 1.2f, 2f, 2.4f, 0xFF1A1420.toInt())
    }
}

/** RAPTOR: small and FAST. It chases you when you're close. */
class Raptor(x: Float, bottom: Float) : DinoFoe(x + 1, bottom, 16f, 16f, 4) {
    init { vx = -45f }
    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        val l = g.leader
        val dx = l.cx - cx
        if (abs(dx) < 150f && abs(l.cy - cy) < 60f) vx = if (dx > 0) 85f else -85f
        // Little hops over bumps
        if (onGround && g.level.solidAt(cx + facing * 12f, cy)) vy = -260f
        walk(g, dt)
    }
    override fun shapes(g: TeamGame, gfx: Gfx, px: Float, f: Float) {
        val c = 0xFF3AA8B8.toInt(); val dk = 0xFF2A7A8A.toInt()
        val mx = px + w / 2
        gfx.poly(floatArrayOf(mx - f * 4f, mx - f * 14f, mx - f * 4f), floatArrayOf(y + 6f, y + 4f, y + 10f), c) // tail
        legs(gfx, floatArrayOf(mx - 3f, mx + 1f), 2.5f, 6f, dk)
        gfx.oval(mx - 6f, y + 4f, 12f, 8f, c)
        for (k in 0 until 3) gfx.rect(mx - 4f + k * 3f, y + 5f, 1.5f, 5f, dk)
        gfx.oval(mx + f * 3f - 3f, y - 1f, 9f, 6f, c) // head
        gfx.rect(mx + f * 5f - (if (f < 0) 4f else 0f), y + 3f, 4f, 1.2f, 0xFFF4F4F4.toInt()) // teeth
        eye(gfx, mx + f * 5f, y + 1f)
        gfx.poly(floatArrayOf(mx + f * 2f, mx + f * 1f, mx + f * 4f), floatArrayOf(y + 9f, y + 13f, y + 11f), 0xFFF4F4F4.toInt()) // claw
    }
}

/** TRICERATOPS: walks slowly, then scrapes its foot... and CHARGES! */
class Triceratops(x: Float, bottom: Float) : DinoFoe(x - 6, bottom, 26f, 18f, 8) {
    private var mode = 0 // 0 walk, 1 getting ready, 2 charging
    private var t = 0f
    private var dir = -1
    init { vx = -20f }
    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt; t += dt
        val l = g.leader
        when (mode) {
            0 -> { vx = dir * 20f; if (t > 1.5f && abs(l.cx - cx) < 170f && abs(l.cy - cy) < 40f) { mode = 1; t = 0f; dir = if (l.cx > cx) 1 else -1 } }
            1 -> { vx = dir * 0.01f; if (t > 0.7f) { mode = 2; t = 0f; g.sfx(Sound.STOMP) } }
            2 -> { vx = dir * 160f; if (t > 1.1f) { mode = 0; t = 0f } }
        }
        val before = vx
        walk(g, dt)
        if (vx != before && mode == 2) { mode = 0; t = 0f; g.shake = maxOf(g.shake, 0.15f) }
        if (vx.toInt() != 0 && mode == 0) dir = if (vx > 0) 1 else -1
    }
    override fun shapes(g: TeamGame, gfx: Gfx, px: Float, f: Float) {
        val c = 0xFFE8904A.toInt(); val dk = 0xFFB8682A.toInt()
        val mx = px + w / 2
        val shake = if (mode == 1) sin(t * 60f) * 1f else 0f
        gfx.poly(floatArrayOf(mx - f * 9f, mx - f * 16f, mx - f * 9f), floatArrayOf(y + 7f, y + 9f, y + 11f), c)
        legs(gfx, floatArrayOf(mx - 9f, mx - 4f, mx + 2f, mx + 7f), 3f, 6f, dk)
        gfx.oval(mx - 11f + shake, y + 3f, 22f, 12f, c)
        // The frill and the horns
        gfx.oval(mx + f * 5f - 6f + shake, y - 2f, 12f, 12f, 0xFFF4C86A.toInt())
        gfx.oval(mx + f * 9f - 4f + shake, y + 3f, 9f, 8f, c)
        gfx.poly(floatArrayOf(mx + f * 9f, mx + f * 18f, mx + f * 10f), floatArrayOf(y + 3f, y + 0f, y + 5f), 0xFFF4F4E0.toInt())
        gfx.poly(floatArrayOf(mx + f * 12f, mx + f * 19f, mx + f * 13f), floatArrayOf(y + 6f, y + 5f, y + 8f), 0xFFF4F4E0.toInt())
        eye(gfx, mx + f * 9f + shake, y + 5f)
        if (mode == 1) gfx.shadowText("!", mx, y - 4f, 8f, 0xFFFF4A4A.toInt(), 1)
    }
}

/** PTERODACTYL: flaps high up, then SWOOPS down at you. */
class Pterodactyl(x: Float, bottom: Float) : DinoFoe(x - 3, bottom - 40f, 22f, 10f, 3) {
    private val homeY = y
    private var swoop = -1f
    private var dir = -1
    init { vx = -30f; active = true }
    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        val l = g.leader
        if (swoop < 0f) {
            x += vx * dt
            y = homeY + sin(anim * 2f) * 6f
            if (abs(anim % 6f - 3f) < dt) vx = -vx
            if (abs(l.cx - cx) < 70f && l.y > y + 20f && anim > 1.5f) { swoop = 0f; dir = if (l.cx > cx) 1 else -1; g.sfx(Sound.JUMP) }
        } else {
            swoop += dt
            // Down in a curve, then back up.
            x += dir * 110f * dt
            y = homeY + sin((swoop / 1.6f).coerceAtMost(1f) * Math.PI.toFloat()) * 60f
            vx = dir * 1f
            if (swoop > 1.6f) { swoop = -1f; anim = 0f; vx = dir * 30f }
        }
    }
    override fun shapes(g: TeamGame, gfx: Gfx, px: Float, f: Float) {
        val c = 0xFF8A5AB8.toInt(); val wing = 0xFFB88AD8.toInt()
        val mx = px + w / 2
        val flap = sin(anim * 9f) * 6f
        gfx.poly(floatArrayOf(mx - 3f, mx - 14f, mx - 2f), floatArrayOf(y + 4f, y + 2f - flap, y + 7f), wing)
        gfx.poly(floatArrayOf(mx + 3f, mx + 14f, mx + 2f), floatArrayOf(y + 4f, y + 2f - flap, y + 7f), wing)
        gfx.oval(mx - 4f, y + 2f, 8f, 7f, c)
        gfx.oval(mx + f * 3f - 3f, y, 7f, 5f, c)
        gfx.poly(floatArrayOf(mx + f * 5f, mx + f * 12f, mx + f * 5f), floatArrayOf(y + 1f, y + 3f, y + 4f), 0xFFF4C84A.toInt())
        gfx.poly(floatArrayOf(mx - f * 1f, mx - f * 7f, mx + f * 1f), floatArrayOf(y + 1f, y - 3f, y), c)
        eye(gfx, mx + f * 3f, y + 2f)
    }
}

/** STEGOSAURUS: big, slow and SPIKY on top. Don't jump on it: throw things at it! */
class Stegosaurus(x: Float, bottom: Float) : DinoFoe(x - 6, bottom, 26f, 18f, 10) {
    override val stompable = false
    init { vx = -16f }
    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        walk(g, dt)
    }
    override fun shapes(g: TeamGame, gfx: Gfx, px: Float, f: Float) {
        val c = 0xFF6AB85A.toInt(); val dk = 0xFF4A8A3A.toInt(); val plate = 0xFFE8783A.toInt()
        val mx = px + w / 2
        gfx.poly(floatArrayOf(mx - f * 9f, mx - f * 17f, mx - f * 9f), floatArrayOf(y + 8f, y + 10f, y + 12f), c)
        gfx.poly(floatArrayOf(mx - f * 15f, mx - f * 18f, mx - f * 16f), floatArrayOf(y + 10f, y + 5f, y + 10f), 0xFFF4F4E0.toInt()) // tail spikes
        legs(gfx, floatArrayOf(mx - 9f, mx - 4f, mx + 2f, mx + 7f), 3f, 6f, dk)
        for (k in 0 until 4) {
            val bx = mx - 8f + k * 5f
            gfx.poly(floatArrayOf(bx - 3f, bx, bx + 3f), floatArrayOf(y + 4f, y - 4f - (k % 2) * 2f, y + 4f), plate)
        }
        gfx.oval(mx - 11f, y + 2f, 22f, 12f, c)
        gfx.oval(mx + f * 10f - 3f, y + 6f, 7f, 5f, c)
        eye(gfx, mx + f * 11f, y + 7.5f)
    }
}

/** ANKYLOSAURUS: armored like a tank, with a club on its tail that swings when you're behind it. */
class Ankylosaurus(x: Float, bottom: Float) : DinoFoe(x - 4, bottom, 24f, 14f, 12) {
    private var swing = 0f
    init { vx = -18f }
    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        if (swing > 0f) swing -= dt
        val l = g.leader
        val behind = (l.cx - cx) * facing < 0 && abs(l.cx - cx) < 34f && abs(l.cy - cy) < 24f
        if (behind && swing <= 0f) { swing = 0.6f; g.sfx(Sound.STOMP) }
        if (swing in 0.25f..0.45f && behind) g.hurt(this)
        walk(g, dt)
    }
    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 3); stomper.vy = -260f }
    override fun shapes(g: TeamGame, gfx: Gfx, px: Float, f: Float) {
        val c = 0xFF9A8A5A.toInt(); val dk = 0xFF6A5A3A.toInt()
        val mx = px + w / 2
        val sw = if (swing > 0f) sin(swing * 16f) * 5f else 0f
        gfx.rect(mx - f * 18f - (if (f > 0) 0f else -10f) - 5f, y + 6f + sw, 10f, 2f, c)
        gfx.oval(mx - f * 18f - 4f, y + 3f + sw, 8f, 8f, dk) // the club
        legs(gfx, floatArrayOf(mx - 8f, mx - 3f, mx + 2f, mx + 6f), 3f, 4f, dk)
        gfx.oval(mx - 11f, y + 1f, 22f, 11f, c)
        for (k in 0 until 5) gfx.oval(mx - 9f + k * 4f, y + 2f + (k % 2) * 2f, 3f, 3f, 0xFFD8C88A.toInt())
        gfx.oval(mx + f * 10f - 3f, y + 5f, 7f, 6f, c)
        eye(gfx, mx + f * 11f, y + 7f)
    }
}

/** BABY REX: a little T-rex. It stops to ROAR (that's your moment!). */
class BabyRex(x: Float, bottom: Float) : DinoFoe(x - 1, bottom, 18f, 18f, 6) {
    private var roarT = 3f
    private var roaring = 0f
    init { vx = -28f }
    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        anim += dt
        if (roaring > 0f) { roaring -= dt; vx = facing * 0.01f; if (roaring <= 0f) vx = facing * 28f; walk(g, dt); return }
        roarT -= dt
        if (roarT <= 0f && abs(g.leader.cx - cx) < 160f) { roarT = 3.5f + Random.nextFloat() * 2f; roaring = 1f; g.say(cx, y - 6, "RAWR!", 0.9f) }
        walk(g, dt)
    }
    override fun shapes(g: TeamGame, gfx: Gfx, px: Float, f: Float) {
        val c = 0xFF5AA84A.toInt(); val dk = 0xFF3A7A2A.toInt()
        val mx = px + w / 2
        gfx.poly(floatArrayOf(mx - f * 4f, mx - f * 14f, mx - f * 4f), floatArrayOf(y + 9f, y + 13f, y + 14f), c)
        legs(gfx, floatArrayOf(mx - 4f, mx + 1f), 3.5f, 6f, dk)
        gfx.oval(mx - 6f, y + 5f, 12f, 10f, c)
        gfx.oval(mx - 3f, y + 8f, 6f, 6f, 0xFFE8E0A0.toInt())
        val open = if (roaring > 0f) 3f else 0f
        gfx.oval(mx + f * 4f - 5f, y - 1f, 11f, 8f, c)
        if (open > 0f) gfx.rect(mx + f * 6f - (if (f < 0) 5f else 0f), y + 4f, 5f, open, 0xFF8A1A1A.toInt())
        gfx.rect(mx + f * 6f - (if (f < 0) 5f else 0f), y + 4f, 5f, 1f, 0xFFF4F4F4.toInt())
        eye(gfx, mx + f * 5f, y + 1.5f)
        gfx.rect(mx + f * 4f - 1f, y + 9f, 2f, 3f, dk) // tiny arm
    }
}

/** A free Cappy hat floating in Dino World: grab it and throw it at the dinosaurs! */
class CappyPickup(x: Float, bottom: Float) : Thing(x, bottom - 30f, 14f, 10f) {
    private var t = 0f
    init { active = true }
    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (g.team.any { it.overlaps(this) }) { removed = true; g.giveCappy() }
    }
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val by = y + sin(t * 3f) * 2f
        gfx.oval(x + ox - 4f, by - 4f, w + 8f, h + 8f, argb(80, 0xFFE14D))
        CappyHat.drawCap(gfx, x + ox + 1f, by, t, false)
        if (g.power != Power.CAPPY) gfx.shadowText("CAPPY!", x + ox + 7f, by - 6f, 5.5f, 0xFFFFF2C8.toInt(), 1)
    }
}

/** Bosses that look after their own name and banner (newer ones). */
interface NamedBoss {
    val homeX: Float
    val awake: Boolean
    val bossName: String
    val bossSub: String
}

/**
 * REX KRAG (level char '\''): King Krag turned into a giant T-REX. He ROARS out shockwaves along the ground
 * (jump over them), and stomps so hard that rocks rain down (watch for the red X). Stomp his head or hit him!
 */
class RexKrag(x: Float, private val ground: Float) : Foe(x - 14, ground - 44, 44f, 44f, 110), NamedBoss {
    override var awake = false
        private set
    override val homeX = x
    override val boss = true
    override val stompable = true
    override val bossName = "REX KRAG"
    override val bossSub = "JUMP over his roar waves and dodge the falling rocks! Stomp his head!"
    private var t = 0f
    private var roarT = 3f
    private var rockT = 6f
    private var roaring = 0f
    private var hurtT = 0f
    private var facing = -1

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        hurtT -= dt
        if (updateDying(g, dt)) return
        val l = g.leader
        if (!awake) {
            if (l.cx > homeX - g.viewW * 0.45f) {
                awake = true
                g.onBossEngaged(this)
                g.say(cx, y - 8, "RAAAWR! I'M REX KRAG!", 2.5f)
                g.sayAs("Rawr! I am Rex Krag!", Voice.GOD)
            }
            return
        }
        val angry = hp < maxHp / 2
        facing = if (l.cx < cx) -1 else 1
        if (roaring > 0f) {
            roaring -= dt
            vx = 0f
        } else if (onGround) vx = if (abs(cx - homeX) > 80f) (if (cx > homeX) -30f else 30f) else facing * (if (angry) 34f else 24f)
        roarT -= dt
        if (roarT <= 0f && onGround) {
            roarT = if (angry) 3.2f else 4.2f
            roaring = 0.8f
            g.shake = 0.4f
            g.sfx(Sound.BOSS_FIRE)
            g.say(cx, y - 8, "ROOOAR!", 1f)
            g.spawn(ChordWave(x - 16, bottom, -1))
            g.spawn(ChordWave(x + w, bottom, 1))
        }
        rockT -= dt
        if (rockT <= 0f) {
            rockT = if (angry) 4.5f else 6.5f
            g.shake = 0.5f
            g.sfx(Sound.STOMP)
            repeat(if (angry) 4 else 3) { k ->
                val tx = (l.cx + (k - 1) * 46f + Random.nextFloat() * 20f).coerceIn(homeX - 220f, homeX + 220f)
                groundTop(g, tx)?.let { top -> g.spawn(Meteor(tx, top, 1.5f)) }
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
            g.addScore(20000, cx, y)
            g.say(cx, y - 8, "RAWR... I'M EXTINCT!", 2f)
            g.onBossBeaten(this)
        }
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 3) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        if (gfx !is SilhouetteGfx) shapes(SilhouetteGfx(if (gfx is TintGfx) gfx.inner else gfx, 1f), px)
        shapes(gfx, px)
        drawFlash(gfx, ox)
    }

    private fun shapes(gfx: Gfx, px: Float) {
        val f = facing.toFloat()
        val mx = px + w / 2
        val c = 0xFF3A9A4A.toInt(); val dk = 0xFF2A6A3A.toInt(); val belly = 0xFFF4D88A.toInt()
        val step = if (abs(vx) > 5f) sin(t * 10f) * 2f else 0f
        // Tail, legs, body
        gfx.poly(floatArrayOf(mx - f * 10f, mx - f * 34f, mx - f * 8f), floatArrayOf(y + 22f, y + 30f, y + 32f), c)
        gfx.rect(mx - 10f, bottom - 12f + step.coerceAtMost(0f), 8f, 12f, dk)
        gfx.rect(mx + 3f, bottom - 12f - step.coerceAtLeast(0f), 8f, 12f, dk)
        gfx.oval(mx - 16f, y + 12f, 32f, 24f, c)
        gfx.oval(mx - 9f + f * 3f, y + 18f, 16f, 16f, belly)
        // Krag's spiky shell on his back and his red hair
        gfx.oval(mx - f * 8f - 10f, y + 10f, 20f, 16f, 0xFF2A8A3A.toInt())
        for (k in 0 until 4) gfx.poly(floatArrayOf(mx - f * 14f + k * 5f - 2f, mx - f * 14f + k * 5f + 1f, mx - f * 14f + k * 5f + 4f), floatArrayOf(y + 12f, y + 6f, y + 12f), 0xFFF4F4E0.toInt())
        // Big head with a jaw that opens when he roars
        val jaw = if (roaring > 0f) 7f else 2f
        gfx.oval(mx + f * 8f - 12f, y, 26f, 16f, c)
        gfx.rect(mx + f * 12f - (if (f < 0) 16f else 0f), y + 10f, 16f, 2f + jaw, 0xFF8A1A1A.toInt())
        for (k in 0 until 4) gfx.poly(floatArrayOf(mx + f * (13f + k * 4f), mx + f * (14f + k * 4f), mx + f * (15f + k * 4f)), floatArrayOf(y + 10f, y + 13f, y + 10f), 0xFFF4F4F4.toInt())
        gfx.oval(mx + f * 12f - (if (f < 0) 16f else 0f), y + 11f + jaw, 16f, 6f, c)
        for (k in 0 until 4) gfx.poly(floatArrayOf(mx - f * 2f + k * 3f - 5f, mx - f * 2f + k * 3f - 3f, mx - f * 2f + k * 3f - 1f), floatArrayOf(y + 2f, y - 6f - (k % 2) * 2f, y + 2f), 0xFFE84A3A.toInt())
        // Angry eyebrow and eye, tiny arms
        gfx.oval(mx + f * 9f - 3f, y + 3f, 6f, 6f, 0xFFFFFFFF.toInt())
        gfx.oval(mx + f * 10f - 1.5f, y + 4.5f, 3f, 3f, 0xFFE84A3A.toInt())
        gfx.poly(floatArrayOf(mx + f * 5f, mx + f * 13f, mx + f * 13f), floatArrayOf(y + 2f, y + 1f, y + 3.5f), 0xFF1A1420.toInt())
        gfx.rect(mx + f * 12f - 2f, y + 22f, 6f, 3f, dk)
        // A golden crown, of course
        gfx.poly(floatArrayOf(mx + f * 2f - 6f, mx + f * 2f - 6f, mx + f * 2f - 3f, mx + f * 2f, mx + f * 2f + 3f, mx + f * 2f + 6f, mx + f * 2f + 6f),
            floatArrayOf(y + 1f, y - 6f, y - 2f, y - 7f, y - 2f, y - 6f, y + 1f), 0xFFF4D84A.toInt())
    }
}

/** Dino World's own backdrop: a warm prehistoric sky, smoking volcanoes, giant ferns and long-necked dinos far away. */
object DinoArt {
    fun drawSky(gfx: Gfx, viewW: Float, viewH: Float, cam: Float, t: Float) {
        gfx.gradient(0f, 0f, viewW, viewH, 0xFFF0A860.toInt(), 0xFFFCE8B8.toInt())
        gfx.oval(viewW * 0.7f - cam * 0.01f, 26f, 40f, 40f, 0xFFFFF0A0.toInt())
        // Volcanoes far away
        layer(viewW, cam, 0.12f, 300f) { i, x ->
            gfx.poly(floatArrayOf(x, x + 90f, x + 120f, x + 210f), floatArrayOf(190f, 80f + (i % 2) * 14f, 80f + (i % 2) * 14f, 190f), 0xFFA8705A.toInt())
            val puff = (t * 0.4f + i) % 1f
            gfx.oval(x + 96f - puff * 10f, 64f - puff * 30f, 28f + puff * 14f, 18f + puff * 8f, argb((140 * (1 - puff)).toInt(), 0x8A7A7A))
        }
        // Long-necked dinosaurs walking in the distance
        layer(viewW, cam, 0.25f, 420f) { i, x ->
            val c = 0xFF8A9A6A.toInt()
            gfx.oval(x + 40f, 150f, 60f, 26f, c)
            gfx.poly(floatArrayOf(x + 88f, x + 120f, x + 126f, x + 94f), floatArrayOf(158f, 108f, 112f, 164f), c)
            gfx.oval(x + 116f, 102f, 18f, 10f, c)
            gfx.poly(floatArrayOf(x + 44f, x + 10f, x + 46f), floatArrayOf(160f, 172f, 168f), c)
            for (k in 0 until 4) gfx.rect(x + 48f + k * 12f, 168f, 6f, 16f, c)
        }
        // Giant ferns up close
        layer(viewW, cam, 0.5f, 170f) { i, x ->
            val c = if (i % 2 == 0) 0xFF4A8A3A.toInt() else 0xFF3A7A3A.toInt()
            for (k in 0 until 5) {
                val a = -1.2f + k * 0.6f
                gfx.poly(floatArrayOf(x + 60f, x + 60f + kotlin.math.sin(a) * 60f, x + 66f + kotlin.math.sin(a) * 60f), floatArrayOf(200f, 200f - kotlin.math.cos(a) * 60f, 204f - kotlin.math.cos(a) * 60f), c)
            }
        }
    }

    private inline fun layer(viewW: Float, cam: Float, factor: Float, spacing: Float, draw: (Int, Float) -> Unit) {
        val off = cam * factor
        val first = (off / spacing).toInt() - 1
        val last = ((off + viewW) / spacing).toInt() + 1
        for (i in first..last) draw(i and 0xFFFF, i * spacing - off)
    }
}
