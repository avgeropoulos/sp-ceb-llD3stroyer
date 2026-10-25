package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.TILE
import com.roccoquest.game.Voice
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A cannon you can climb into (level char '>'). Press ACTION next to it to hop in, then JUMP (or ACTION)
 * to BLAST off in the direction the barrel is pointing. The barrel swings up and down by itself.
 */
class BlastCannon(x: Float, bottom: Float) : Thing(x - 6, bottom - 22, 28f, 22f) {
    private var t = 0f
    /** How long someone has been inside. */
    var loadT = 0f
    var loaded = false
    private var smokeT = 0f
    init { active = true }

    /** The barrel's angle above the ground, swinging between about 20 and 60 degrees. */
    val aim get() = 0.7f + sin(t * 1.6f) * 0.35f

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (loaded) loadT += dt else loadT = 0f
        if (smokeT > 0f) smokeT -= dt
    }

    fun fired() { loaded = false; smokeT = 0.6f }

    /** Where the end of the barrel is. */
    fun muzzleX() = cx + cos(aim) * 22f
    fun muzzleY() = y + 6f - sin(aim) * 22f

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = cx + ox
        val a = aim
        // The barrel: a fat dark tube pointing up and to the right.
        val bx = px; val by = y + 6f
        val dx = cos(a); val dy = -sin(a)
        val nx = -dy * 7f; val ny = dx * 7f
        gfx.poly(
            floatArrayOf(bx + nx, bx + dx * 24 + nx, bx + dx * 24 - nx, bx - nx),
            floatArrayOf(by + ny, by + dy * 24 + ny, by + dy * 24 - ny, by - ny), 0xFF2A2A34.toInt(),
        )
        gfx.oval(bx + dx * 24 - 6f, by + dy * 24 - 6f, 12f, 12f, 0xFF3A3A48.toInt())
        gfx.oval(bx + dx * 24 - 4f, by + dy * 24 - 4f, 8f, 8f, 0xFF101014.toInt())
        // The base, with a gold star
        gfx.oval(px - 14f, y + 4f, 28f, 20f, 0xFF5A5A6A.toInt())
        gfx.rect(px - 14f, y + 14f, 28f, 8f, 0xFF4A4A5A.toInt())
        gfx.shadowText("★", px, y + 18f, 8f, 0xFFFFD21F.toInt(), 1)
        if (loaded) {
            // Dotted aim line showing where you'll fly
            for (k in 1..6) {
                val s = k * 0.07f
                val fx = bx + dx * TeamGame.CANNON_SPEED * s
                val fy = by + dy * TeamGame.CANNON_SPEED * s + 0.5f * GRAVITY * 0.8f * s * s
                gfx.oval(fx - 1.5f, fy - 1.5f, 3f, 3f, if ((g.time * 6).toInt() % 2 == 0) 0xFFFFE14D.toInt() else 0xFFFFFFFF.toInt())
            }
            gfx.shadowText("JUMP = BLAST OFF!", px, y - 18f, 6f, 0xFFFFE14D.toInt(), 1)
        } else if (abs(g.leader.cx - cx) < 26f && g.state == TeamGame.State.PLAY && (g.time * 2).toInt() % 2 == 0) {
            gfx.shadowText("GET IN!", px, y - 14f, 7f, 0xFFFFD21F.toInt(), 1)
        }
        if (smokeT > 0f) {
            val k = 1f - smokeT / 0.6f
            gfx.oval(bx + dx * 30 - 8f - k * 6f, by + dy * 30 - 8f - k * 6f, 16f + k * 12f, 16f + k * 12f, argb((smokeT / 0.6f * 200).toInt(), 0xDDDDDD))
        }
    }
}

/** A spiky black bomb at the edge of the tail-spin arena (level char '+'). Don't touch it! Throw King Krag into it. */
class SpikeBomb(x: Float, bottom: Float) : Thing(x, bottom - 16, 16f, 16f) {
    private var t = 0f
    val homeX = x
    val homeY = bottom - 16
    /** Blown up (it comes back after a while). */
    var goneT = 0f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (goneT > 0f) { goneT -= dt; return }
        if (overlaps(g.leader) && g.grabbing == null) g.hurt(this)
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        if (goneT > 0f) return
        val px = x + ox
        for (k in 0 until 8) {
            val a = k * 0.785f + t * 0.5f
            val sx = px + 8 + cos(a) * 9f
            val sy = y + 8 + sin(a) * 9f
            gfx.poly(floatArrayOf(px + 8 + cos(a + 0.3f) * 5f, sx, px + 8 + cos(a - 0.3f) * 5f), floatArrayOf(y + 8 + sin(a + 0.3f) * 5f, sy, y + 8 + sin(a - 0.3f) * 5f), 0xFFC8C8D0.toInt())
        }
        gfx.oval(px + 1, y + 1, 14f, 14f, 0xFF1A1A22.toInt())
        gfx.oval(px + 4, y + 4, 4f, 4f, 0x66FFFFFF)
        if ((t * 3).toInt() % 2 == 0) gfx.oval(px + 6, y + 6, 4f, 4f, 0xFFE84A4A.toInt())
    }
}

/**
 * King Krag in the TAIL SPIN arena (level char '&'), like the very first big bad-guy fight in the old 3D
 * games: hits don't hurt him. Sneak BEHIND him, grab his TAIL with ACTION, hold LEFT or RIGHT to spin
 * faster, then ACTION again to let go and throw him into a SPIKE BOMB. Three bombs and he's beaten!
 */
class TailKrag(x: Float, private val ground: Float) : Foe(x - 10, ground - 40, 36f, 40f, 3) {
    var awake = false
        private set
    val homeX = x
    override val boss = true
    override val showBar = true
    private var t = 0f
    private var facing = -1
    private var fireTimer = 3f
    /** 0 walking, 1 grabbed and spinning, 2 thrown through the air, 3 dizzy. */
    var mode = 0
        private set
    private var angle = 0f
    var spin = 0f
        private set
    private var grabT = 0f
    private var dizzyT = 0f
    private var tipT = 0f
    private val look = KingKrag(x, ground)

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (tipT > 0f) tipT -= dt
        if (updateDying(g, dt)) return
        val l = g.leader
        if (!awake) {
            if (l.cx > homeX - g.viewW * 0.45f) {
                awake = true
                g.onBossEngaged(this)
                g.say(cx, y - 8, "Nobody grabs MY tail!", 2.5f)
            }
            return
        }
        when (mode) {
            0, 3 -> {
                if (mode == 3) { dizzyT -= dt; vx = 0f; if (dizzyT <= 0f) mode = 0 }
                else {
                    val want = if (l.cx < cx) -1 else 1
                    if (want != facing && (t % 1.2f) < dt * 2) facing = want
                    if (onGround) vx = facing * 26f
                    fireTimer -= dt
                    if (fireTimer <= 0f) {
                        fireTimer = 3.2f
                        g.spawn(Flame(if (facing < 0) x - 18 else x + w - 2, bottom - 12, facing, false))
                        g.sfx(Sound.BOSS_FIRE)
                    }
                }
                walk(g, dt)
                // Grab the tail: from behind (or any side while he's dizzy).
                val behind = (l.cx - cx) * facing < 0 || mode == 3
                if (g.actionTap && behind && abs(l.cx - cx) < 34f && abs(l.bottom - bottom) < 22f && g.grabbing == null && g.cannonFlyT <= 0f) {
                    mode = 1
                    grabT = 0f
                    spin = 3f
                    angle = if (cx > l.cx) 0f else Math.PI.toFloat()
                    g.grabbing = this
                    g.sfx(Sound.KICK)
                    g.say(l.cx, l.y - 12, "GOT YOUR TAIL!", 1.4f)
                    g.toastFromGame("Hold LEFT or RIGHT to spin faster, then ACTION to throw him into a spike bomb!")
                }
            }
            1 -> {
                grabT += dt
                // Swing him around and around. Holding a direction spins faster.
                spin = if (g.heldDir() != 0) min(spin + 9f * dt, 13f) else maxOf(spin - 2f * dt, 4f)
                angle += spin * dt * (if (g.heldDir() < 0) -1 else 1)
                val r = 40f
                x = l.cx + cos(angle) * r - w / 2
                y = l.bottom - h - 2 + sin(angle) * 6f
                vx = 0f; vy = 0f
                facing = if (cos(angle) > 0) 1 else -1
                l.facing = facing
                if ((t * 6).toInt() != ((t - dt) * 6).toInt()) g.sfx(Sound.WAVE)
                if (g.actionTap && grabT > 0.35f) {
                    // Let go: he flies off the way he's facing, faster the faster you spun.
                    mode = 2
                    g.grabbing = null
                    vx = facing * (180f + spin * 30f)
                    vy = -200f
                    onGround = false
                    g.sfx(Sound.BOSS_FIRE)
                    g.say(l.cx, l.y - 12, if (spin > 9f) "SO LONG, KING KRAG!" else "WHEEE!", 1.4f)
                }
            }
            2 -> {
                vy = min(vy + GRAVITY * dt, MAX_FALL)
                if (g.level.moveX(this, vx * dt)) vx = -vx * 0.4f
                g.level.moveY(this, vy * dt)
                val bomb = g.thingsForBosses().firstOrNull { it is SpikeBomb && it.goneT <= 0f && it.overlaps(this) } as SpikeBomb?
                if (bomb != null) {
                    bomb.goneT = 8f
                    g.puff(bomb.cx, bomb.cy); g.puff(cx, cy)
                    g.shake = 0.5f
                    g.sfx(Sound.BOSS_HIT)
                    hp -= 1
                    flash = 0.4f
                    for (k in 0 until 10) g.addParticle(Sparkle(bomb.cx, bomb.cy, cos(k * 0.63f) * 120f, sin(k * 0.63f) * 120f, 0xFFFFA83A.toInt()))
                    if (hp <= 0) {
                        hp = 0
                        knockOut(g)
                        g.addScore(15000, cx, y)
                        g.say(cx, y - 8, "NOOOO! My TAIL!", 2f)
                        g.sayAs("Noooo! Not the spike bombs!", Voice.GOD)
                        g.onBossBeaten(this)
                        return
                    }
                    g.say(cx, y - 8, if (hp == 2) "OWWW! That's spiky!" else "OUCH OUCH OUCH!", 1.8f)
                    vx = -vx * 0.3f
                    vy = -260f
                }
                if (onGround && vy >= 0f) {
                    mode = 3
                    dizzyT = if (bomb != null) 1.4f else 1f
                    vx = 0f
                    if (bomb == null && hp > 0) g.say(cx, y - 8, "Ha! You missed!", 1.3f)
                }
                if (y > g.level.h * TILE) { x = homeX - 10; y = ground - h - 60; vy = 0f; mode = 0 }
            }
        }
    }

    /** Punches, fireballs and super moves bounce off him: the only way is the spike bombs. */
    override fun hit(g: TeamGame, dmg: Int): Boolean {
        if (dying || !awake) return false
        flash = 0.1f
        if (tipT <= 0f) {
            tipT = 4f
            g.say(cx, y - 8, "HA! That tickles! (Grab my TAIL!)", 1.8f)
        }
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) {
        if (tipT <= 0f) { tipT = 4f; g.say(cx, y - 8, "Ow, my head! Try my TAIL!", 1.6f) }
    }

    override fun touch(g: TeamGame) { if (mode == 0) g.hurt(this) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        look.x = x; look.y = y; look.vx = vx; look.facing = facing
        // His tail sticks out the back, with a little spike on the end.
        val px = x + ox
        val tx = if (facing > 0) px - 10f else px + w + 2f
        gfx.oval(tx, y + 24f, 12f, 8f, 0xFF4A9A3A.toInt())
        gfx.poly(floatArrayOf(tx + (if (facing > 0) 0f else 12f), tx + (if (facing > 0) -5f else 17f), tx + (if (facing > 0) 2f else 10f)), floatArrayOf(y + 25f, y + 28f, y + 31f), 0xFFF4F4F4.toInt())
        look.draw(g, gfx, ox)
        if (mode == 3) for (k in 0 until 3) {
            val a = t * 5f + k * 2.1f
            gfx.shadowText("★", px + 18f + cos(a) * 12f, y - 4f + sin(a) * 4f, 7f, 0xFFFFE14D.toInt(), 1)
        }
        if (mode == 1 && spin > 9f) for (k in 0 until 3) gfx.oval(px + 18f - facing * (12f + k * 8f), y + 18f + k * 4f, 6f, 2f, 0xAAFFFFFF.toInt())
        if (mode == 0 && awake && (g.time * 2).toInt() % 3 == 0 && abs(g.leader.cx - cx) < 90f) {
            gfx.shadowText("GRAB HIS TAIL!", tx + 6f, y + 18f, 5f, 0xFFFFE14D.toInt(), 1)
        }
        drawFlash(gfx, ox)
    }
}
