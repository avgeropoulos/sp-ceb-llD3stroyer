package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.TILE
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import com.roccoquest.game.tileOf
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** The top of the ground at [x] (scanning down from below the ceiling), or null over a pit. */
fun groundTop(g: TeamGame, x: Float, fromRow: Int = 2): Float? {
    val tx = tileOf(x)
    for (ty in fromRow until g.level.h) if (Tile.solid(g.level.get(tx, ty))) return ty * TILE.toFloat()
    return null
}

/**
 * A meteor from a METEOR SHOWER: a red X shows where it will land, then it falls in at a slant and goes BOOM.
 * Get out from under the X! (Like any bad guy, a shot or a bonk can break it on the way down.)
 */
class Meteor(private val tx: Float, private val ty: Float, private val fall: Float = 1.3f) : Foe(tx - 170f, ty - 260f, 14f, 14f, 1), Projectile {
    private var t = 0f
    private val sx = x
    private val sy = y
    override val stompable = false
    override val showBar get() = false
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        if (dying) { removed = true; return }
        t += dt
        val k = (t / fall).coerceAtMost(1f)
        x = sx + (tx - 7f - sx) * k
        y = sy + (ty - 14f - sy) * k * k
        if (k >= 1f) {
            removed = true
            g.shake = maxOf(g.shake, 0.25f)
            g.puff(tx, ty - 6f)
            g.sfx(Sound.STOMP)
            // The BOOM hurts anyone right next to it.
            val l = g.leader
            if (abs(l.cx - tx) < 20f && abs(l.bottom - ty) < 24f) g.hurt(this)
        }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); g.sfx(Sound.BREAK); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        // The warning X on the ground, blinking faster as it gets close.
        val k = t / fall
        if ((t * (4f + k * 10f)).toInt() % 2 == 0 || k > 0.8f) {
            val mx = tx + ox
            gfx.oval(mx - 12f, ty - 4f, 24f, 7f, argb(90 + (k * 120).toInt(), 0xFF3A2A))
            gfx.shadowText("X", mx, ty + 1f, 9f, 0xFFFF3A2A.toInt(), 1)
        }
        // The meteor with its fiery tail.
        val px = x + ox
        for (i in 1..4) gfx.oval(px - i * 6f + 2f, y - i * 8f + 2f, 12f - i * 2f, 12f - i * 2f, argb(200 - i * 40, if (i % 2 == 0) 0xFFD21F else 0xFF7A2A))
        gfx.oval(px - 1, y - 1, 16f, 16f, 0xFF3A2A2A.toInt())
        gfx.oval(px, y, 14f, 14f, 0xFF8A5A4A.toInt())
        gfx.oval(px + 3, y + 3, 4f, 4f, 0xFF5A3A2A.toInt())
        gfx.oval(px + 8, y + 7, 3f, 3f, 0xFF5A3A2A.toInt())
    }
}

/**
 * A wave of lava from a volcano ERUPTION: it rolls along the ground toward you. JUMP OVER IT!
 * It can't be stomped or broken.
 */
class LavaWave(x: Float, y: Float, private val speed: Float = 115f) : Foe(x, y, 26f, 13f, 99), Projectile {
    private var t = 0f
    override val stompable = false
    override val showBar get() = false
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        x -= speed * dt
        val top = groundTop(g, cx)
        y = if (top == null) g.level.h * TILE.toFloat() else top - h
        if (x < g.camX - 80f || t > 14f) removed = true
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean = false

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        gfx.oval(px - 4f, y - 6f, w + 8f, h + 10f, 0x55FF6A1A)
        gfx.poly(floatArrayOf(px, px + 6f, px + 14f, px + 22f, px + w, px + w), floatArrayOf(y + h, y + 2f + sin(t * 9) * 2f, y - 2f, y + 3f + sin(t * 7) * 2f, y + 5f, y + h), 0xFFE84A1A.toInt())
        gfx.rect(px + 2f, y + 6f, w - 2f, h - 6f, 0xFFFF8A2A.toInt())
        for (i in 0 until 3) gfx.oval(px + 4f + i * 7f, y + 4f + ((t * 3 + i) % 1f) * 4f, 4f, 3f, 0xFFFFE14D.toInt())
        // Sparks flying up
        for (i in 0 until 3) {
            val sy = ((t * 40f + i * 13f) % 26f)
            gfx.rect(px + 6f + i * 7f, y - sy, 2f, 2f, argb(255 - (sy * 8).toInt().coerceIn(0, 200), 0xFFD21F))
        }
    }
}

/**
 * Runs the big events in a level: METEOR SHOWERS (now and then, in levels out under the sky) and volcano
 * ERUPTIONS (World 10's volcano levels): a warning first, then the danger.
 */
class Hazards {
    var meteors = false
        private set
    var eruptions = false
        private set
    private var meteorT = 0f
    private var showerLeft = 0
    private var showerGap = 0f
    private var eruptT = 0f
    /** The warning on the screen, and how long it stays. */
    var warning = ""
        private set
    var warnT = 0f
        private set

    companion object {
        /** Places out under the open sky, where meteors can fall. */
        private val OPEN = setOf(Place.HILLS, Place.DESERT, Place.SNOW, Place.JUNGLE, Place.VOLCANO, Place.NIGHT, Place.MOON,
            Place.ORBIT, Place.BLIZZARD, Place.AURORA, Place.CANDY, Place.FUNGI, Place.TOYS, Place.RAINBOW, Place.SKY, Place.END)
        const val WORLD10 = 189

        /** Meteor showers: always in World 10's space and volcano levels, and now and then (1 in 3) in other open levels after World 1. */
        fun meteorLevel(index: Int, d: LevelDef, roll: Int): Boolean =
            !d.isBossLevel && d.shoreX < 0 && d.place in OPEN &&
                ((index >= WORLD10 && d.place in setOf(Place.VOLCANO, Place.ORBIT, Place.MOON)) || (index >= WORLD_SIZE && roll % 3 == 0))

        fun eruptionLevel(index: Int, d: LevelDef) = index >= WORLD10 && d.place == Place.VOLCANO && !d.isBossLevel
    }

    fun start(index: Int, d: LevelDef) {
        meteors = meteorLevel(index, d, Random.nextInt(3))
        eruptions = eruptionLevel(index, d)
        meteorT = 14f + Random.nextFloat() * 10f
        eruptT = 9f
        showerLeft = 0
        warnT = 0f
    }

    fun update(g: TeamGame, dt: Float) {
        if (warnT > 0f) warnT -= dt
        if (meteors) updateMeteors(g, dt)
        if (eruptions) updateEruptions(g, dt)
    }

    private fun warn(s: String) { warning = s; warnT = 2.2f }

    private fun updateMeteors(g: TeamGame, dt: Float) {
        if (showerLeft > 0) {
            showerGap -= dt
            if (showerGap <= 0f) {
                showerGap = 0.5f + Random.nextFloat() * 0.3f
                showerLeft--
                val l = g.leader
                // Mostly ahead of you, some right where you are: keep moving!
                val tx = (l.cx + Random.nextFloat() * 260f - 80f).coerceIn(40f, g.level.w * TILE - 40f)
                val top = groundTop(g, tx) ?: return
                g.spawn(Meteor(tx, top))
            }
            return
        }
        meteorT -= dt
        if (meteorT <= 0f) {
            meteorT = 22f + Random.nextFloat() * 14f
            showerLeft = 8 + Random.nextInt(4)
            showerGap = 1.6f
            warn("METEOR SHOWER! Stay away from the red X!")
            g.sfx(Sound.ZAP)
            g.sayWarning("Meteor shower!")
        }
    }

    private fun updateEruptions(g: TeamGame, dt: Float) {
        eruptT -= dt
        if (eruptT in 0f..1.8f) g.shake = maxOf(g.shake, 0.15f)
        if (eruptT <= 1.8f && eruptT + dt > 1.8f) {
            warn("ERUPTION! JUMP over the lava!")
            g.sfx(Sound.STOMP)
            g.sayWarning("Eruption! Jump!")
        }
        if (eruptT <= 0f) {
            eruptT = 9f + Random.nextFloat() * 4f
            val x = g.camX + g.viewW + 30f
            val top = groundTop(g, x) ?: return
            g.spawn(LavaWave(x, top - 13f))
        }
    }

    fun draw(g: TeamGame, gfx: Gfx, viewW: Float) {
        if (warnT <= 0f) return
        val c = if (warning.startsWith("ERUPTION")) 0xFFFF6A1A.toInt() else 0xFFFFB04A.toInt()
        if ((warnT * 6).toInt() % 2 == 0 || warnT < 1.2f) gfx.shadowText(warning, viewW / 2, 64f, 10f, c, 1)
        if (eruptions && warning.startsWith("ERUPTION")) {
            // The volcano in the background blows its top.
            val k = 1f - (warnT / 2.2f)
            for (i in 0 until 8) gfx.oval(viewW * 0.8f + sin(i * 2.1f) * 30f * k, 40f - k * 30f + i * 3f, 8f, 8f, argb(200, if (i % 2 == 0) 0xFF6A1A else 0xFFD21F))
        }
    }
}
