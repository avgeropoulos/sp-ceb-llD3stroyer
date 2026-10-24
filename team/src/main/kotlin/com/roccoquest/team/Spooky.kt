package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.TILE
import com.roccoquest.game.Voice
import com.roccoquest.game.argb
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ============================================================================ World 6: Spooky World

/** A bubbling potion thrown by a witch. It flies in an arc and splashes when it lands. */
class Potion(x: Float, y: Float, tx: Float, private val color: Int = 0xFF8A3AE8.toInt()) : Foe(x, y, 10f, 10f, 1), Projectile {
    private var t = 0f
    override val stompable = false
    override val showBar get() = false
    init {
        active = true
        // Aim so it lands near the target in about a second.
        vx = ((tx - x) / 1.1f).coerceIn(-180f, 180f)
        vy = -260f
    }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (dying) { removed = true; return }
        vy = min(vy + GRAVITY * 0.5f * dt, 300f)
        x += vx * dt
        y += vy * dt
        if (g.level.solidAt(cx, y + h) || t > 4f) splash(g)
    }

    private fun splash(g: TeamGame) {
        removed = true
        g.sfx(Sound.SPLASH)
        repeat(8) { g.addParticle(Sparkle(cx, cy, (it - 3.5f) * 30f, -60f - (it % 3) * 30f, color)) }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { splash(g); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        gfx.oval(px, y + 2, 10f, 9f, color)
        gfx.rect(px + 3.5f, y - 1, 3f, 4f, 0xFFC8D8E8.toInt())
        gfx.rect(px + 3, y - 2, 4f, 1.5f, 0xFF8A5A2A.toInt())
        gfx.oval(px + 2, y + 4, 2.5f, 2.5f, 0xCCFFFFFF.toInt())
        if ((t * 8).toInt() % 2 == 0) gfx.oval(px + 5, y - 5, 2f, 2f, argb(180, color and 0xFFFFFF))
    }
}

/** A little witch with a pointy hat. She stands still and throws potions at you. */
class Witch(x: Float, bottom: Float) : Foe(x, bottom - 22, 14f, 22f, 5) {
    private var t = Random.nextFloat() * 2f
    private var throwT = 2f + Random.nextFloat() * 2f
    private var facing = -1

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        val l = g.leader
        facing = if (l.cx < cx) -1 else 1
        throwT -= dt
        if (throwT <= 0f) {
            throwT = 3.5f
            if (abs(l.cx - cx) < 220f) {
                g.spawn(Potion(cx - 5, y - 2, l.cx))
                if (Random.nextInt(3) == 0) g.say(cx, y - 6, "Hee hee!", 1f)
            }
        }
        vx = 0f
        walk(g, dt)
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) = drawWitch(gfx, x + ox, y, t, facing, 1f)

    companion object {
        /** A witch picture, [s] times the normal size, top-left at ([px], [y]). */
        fun drawWitch(gfx: Gfx, px: Float, y: Float, t: Float, facing: Int, s: Float) {
            val f = facing.toFloat()
            val robe = 0xFF5A2A8A.toInt()
            // Robe
            gfx.poly(floatArrayOf(px + 1 * s, px + 7 * s, px + 13 * s), floatArrayOf(y + 22 * s, y + 9 * s, y + 22 * s), robe)
            // Face (green, with a big nose and a grin)
            gfx.oval(px + 3 * s, y + 6 * s, 8 * s, 7 * s, 0xFF7AC86A.toInt())
            gfx.oval(px + (6 + f * 3) * s, y + 9 * s, 3 * s, 2 * s, 0xFF5AA84A.toInt())
            gfx.oval(px + (5 + f) * s, y + 8 * s, 1.5f * s, 1.5f * s, 0xFF1A1A1A.toInt())
            gfx.rect(px + (5 + f) * s, y + 11 * s, 4 * s, 0.8f * s, 0xFF1A1A1A.toInt())
            // Pointy hat
            gfx.rect(px, y + 6 * s, 14 * s, 1.5f * s, 0xFF2A1A3A.toInt())
            gfx.poly(floatArrayOf(px + 3 * s, px + 7 * s + sin(t * 2) * s, px + 11 * s), floatArrayOf(y + 6 * s, y - 6 * s, y + 6 * s), 0xFF2A1A3A.toInt())
            gfx.rect(px + 4 * s, y + 4 * s, 6 * s, 1.5f * s, 0xFFC890FF.toInt())
        }
    }
}

/**
 * WITCH WANDA, a boss. She flies around on her broom throwing potions, calls bats, and every so
 * often swoops down low: that's the moment to jump on her!
 */
class WitchBoss(x: Float, private val ground: Float) : Foe(x, ground - 30, 26f, 30f, 70) {
    var awake = false
        private set
    private var t = 0f
    private var potionT = 2f
    private var batT = 8f
    private var swoopT = 6f
    private var low = 0f
    private var hurtT = 0f
    private var facing = -1
    val homeX = x
    override val boss = true
    override val stompable = true

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        hurtT -= dt
        if (updateDying(g, dt)) return
        val l = g.leader
        if (!awake) {
            if (l.cx > homeX - g.viewW * 0.45f) {
                awake = true
                g.onBossEngaged(this)
                g.say(cx, y - 8, "Hee hee hee! I'm WITCH WANDA!", 2.5f)
                g.sayAs("Hee hee hee! I'm Witch Wanda!", Voice.TOAD)
            }
            return
        }
        val angry = hp < maxHp / 2
        // Fly back and forth on the broom; swoop down low now and then.
        swoopT -= dt
        if (swoopT <= 0f && low <= 0f) { low = 2.2f; swoopT = if (angry) 5f else 6.5f; g.say(cx, y - 8, "Wheee!", 1f) }
        if (low > 0f) low -= dt
        val height = if (low > 0f) 26f else 86f
        val wantY = ground - height - h + sin(t * 1.6f) * 8f
        y += (wantY - y) * min(1f, dt * 3f)
        val wantX = homeX + sin(t * 0.7f) * 110f
        vx = (wantX - x) * 2f
        x += vx * dt
        facing = if (l.cx < cx) -1 else 1
        if (low <= 0f) {
            potionT -= dt
            if (potionT <= 0f) {
                potionT = if (angry) 1.8f else 2.6f
                g.spawn(Potion(cx - 5, y + h - 6, l.cx + Random.nextFloat() * 40f - 20f))
                g.sfx(Sound.FIRE)
            }
            batT -= dt
            if (batT <= 0f) {
                batT = 10f
                if (g.foes.count { it is Flapper && !it.dying } < 2) {
                    g.spawn(Flapper(cx - 30f, y + 40f).also { it.active = true })
                    g.say(cx, y - 8, "Go, my bats!", 1.2f)
                }
            }
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
            g.addScore(15000, cx, y)
            g.say(cx, y - 8, "Nooo! My broom!", 2f)
            g.onBossBeaten(this)
        }
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 4) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        // The broom
        gfx.rect(px - 8, y + 24, 42f, 3f, 0xFF8A5A2A.toInt())
        val tail = if (facing > 0) px - 14 else px + 32
        gfx.poly(floatArrayOf(tail, tail + 10, tail + 10, tail), floatArrayOf(y + 20, y + 23, y + 28, y + 31), 0xFFE8C860.toInt())
        if (low > 0f || (t * 6).toInt() % 2 == 0) gfx.oval(tail - 2, y + 22, 6f, 6f, 0x66C890FF)
        Witch.drawWitch(gfx, px - 2, y - 4, t, facing, 2f)
        drawFlash(gfx, ox)
    }
}

/**
 * KING BOO-HOO, the king of the Peekaboo ghosts. Like them, he covers his face when you look at
 * him, and chases you when you look away. He calls Peekaboos to help. He's still a scaredy-ghost
 * though: hit him while he's hiding!
 */
class KingBooHoo(x: Float, bottom: Float) : Foe(x, bottom - 60, 44f, 40f, 80) {
    var awake = false
        private set
    private var t = 0f
    private var hiding = false
    private var callT = 5f
    private var hurtT = 0f
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
                g.onBossEngaged(this)
                g.say(cx, y - 8, "BOO-HOO-HOO! I'm KING BOO-HOO!", 2.5f)
                g.sayAs("Boo hoo hoo! I'm King Boo-Hoo!", Voice.GOD)
            }
            return
        }
        val angry = hp < maxHp / 2
        val dx = l.cx - cx
        val dy = l.cy - cy
        hiding = (dx > 0 && l.facing < 0) || (dx < 0 && l.facing > 0)
        if (!hiding) {
            val d = hypot(dx, dy).coerceAtLeast(1f)
            val sp = if (angry) 52f else 40f
            x += dx / d * sp * dt
            y += dy / d * sp * dt
        } else y += sin(t * 2) * 0.4f
        // Don't sink into the floor.
        y = y.coerceAtMost(g.level.h * TILE - 4 * TILE - h)
        callT -= dt
        if (callT <= 0f) {
            callT = if (angry) 5f else 7f
            if (g.foes.count { it is Peekaboo && !it.dying } < 3) {
                g.spawn(Peekaboo(cx - 30f, y + 20f).also { it.active = true })
                g.say(cx, y - 8, "Come out, my ghosts!", 1.2f)
            }
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
            g.addScore(15000, cx, y)
            g.say(cx, y - 8, "BOO-HOO! You win...", 2f)
            g.onBossBeaten(this)
        }
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 3) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val a = if (hiding) 0xBB else 0xEE
        val body = argb(a, 0xF4F4FF)
        gfx.oval(px - 6, y - 6, w + 12, h + 12, argb(50, 0xC8C8FF))
        gfx.oval(px, y, w, h - 4, body)
        for (i in 0 until 5) gfx.poly(floatArrayOf(px + i * 9f, px + i * 9f + 4.5f, px + i * 9f + 9), floatArrayOf(y + 28, y + 42, y + 28), body)
        // The crown, with a big red jewel
        gfx.poly(floatArrayOf(px + 10, px + 12, px + 17, px + 22, px + 27, px + 32, px + 34), floatArrayOf(y + 4, y - 8, y - 2, y - 10, y - 2, y - 8, y + 4), 0xFFFFD21F.toInt())
        gfx.oval(px + 19, y - 4, 6f, 6f, 0xFFE52521.toInt())
        if (hiding) {
            // Hands over his eyes, blushing
            gfx.oval(px + 6, y + 10, 13f, 10f, body); gfx.oval(px + 25, y + 10, 13f, 10f, body)
            gfx.oval(px + 5, y + 20, 7f, 4f, 0x88FF7AA8.toInt()); gfx.oval(px + 32, y + 20, 7f, 4f, 0x88FF7AA8.toInt())
        } else {
            val look = if (g.leader.cx > cx) 2f else -2f
            gfx.oval(px + 10 + look, y + 10, 7f, 9f, 0xFF101010.toInt()); gfx.oval(px + 26 + look, y + 10, 7f, 9f, 0xFF101010.toInt())
            gfx.oval(px + 13, y + 21, 18f, 12f, 0xFF8A1010.toInt())
            gfx.oval(px + 17, y + 26, 10f, 7f, 0xFFFF7AA8.toInt())
            for (i in 0 until 4) gfx.poly(floatArrayOf(px + 14 + i * 4f, px + 16 + i * 4f, px + 18 + i * 4f), floatArrayOf(y + 21, y + 25, y + 21), 0xFFFFFFFF.toInt())
        }
        drawFlash(gfx, ox)
    }
}
