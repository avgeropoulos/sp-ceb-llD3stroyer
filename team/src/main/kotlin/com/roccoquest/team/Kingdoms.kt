package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Sprite
import com.roccoquest.game.Voice
import com.roccoquest.game.argb
import com.roccoquest.game.gradientStops
import com.roccoquest.game.mixColor
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ============================================================================ World 14 (the Kingdom Tour)

/**
 * World 14, the KINGDOM TOUR: a trip through lots of different kingdoms. Two are brand new: THE BIG CITY
 * (skyscraper rooftops at night, neon signs, honking taxis) and THE FOOD KINGDOM (cheese and bread to walk on,
 * hot soup pools that burn like lava, giant veggies and forks). It ends with CHEF KRAG, the last boss of the game.
 * Level chars: '¥' taxi, 'ø' meatball, 'ç' chili pepper and '§' Chef Krag (every plain letter was taken).
 */
object Kingdoms {
    /** The first level of World 14 (an index into [Levels.all]). */
    const val WORLD14 = WORLD_SIZE * 13

    fun isTourLevel(index: Int) = index in WORLD14 until WORLD14 + WORLD_SIZE

    /** What the title card calls each stop on the tour. */
    fun kingdomName(p: Place) = when (p) {
        Place.CITY -> "THE BIG CITY"
        Place.FOOD -> "THE FOOD KINGDOM"
        Place.DESERT -> "THE SAND KINGDOM"
        Place.JUNGLE -> "THE JUNGLE KINGDOM"
        Place.PREHISTORIC -> "THE DINO KINGDOM"
        Place.SNOW -> "THE SNOW KINGDOM"
        Place.MOON -> "THE MOON KINGDOM"
        Place.SKY -> "THE CLOUD KINGDOM"
        else -> "A NEW KINGDOM"
    }
}

/** World 14's new bad guys get the same dark outline as the dinosaurs: shapes drawn twice. */
abstract class TourFoe(x: Float, y: Float, w: Float, h: Float, hp: Int) : Foe(x, y, w, h, hp) {
    protected var t = 0f

    protected abstract fun shapes(gfx: Gfx, px: Float)

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        if (gfx !is SilhouetteGfx) shapes(SilhouetteGfx(if (gfx is TintGfx) gfx.inner else gfx, 0.8f), px)
        shapes(gfx, px)
        drawFlash(gfx, ox)
    }

    protected fun eye(gfx: Gfx, x: Float, y: Float, look: Float) {
        gfx.oval(x - 2.2f, y - 2.5f, 4.4f, 5f, 0xFFFFFFFF.toInt())
        gfx.oval(x - 1f + look, y - 1.2f, 2f, 2.6f, 0xFF1A1420.toInt())
    }
}

/**
 * TAXI (level char '¥', the Big City): a little yellow cab with big eyes in its windshield. It drives along,
 * and when it sees you in front of it, it stops, goes HONK HONK!... and ZOOMS. Jump on its roof!
 */
class Taxi(x: Float, bottom: Float) : TourFoe(x - 3, bottom - 14, 22f, 14f, 4) {
    private var mode = 0 // 0 driving, 1 honking (revving up), 2 zooming
    private var modeT = 0f
    private var dir = -1
    init { vx = -40f }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        modeT += dt
        val l = g.leader
        when (mode) {
            0 -> {
                vx = dir * 40f
                if (modeT > 1.5f && abs(l.cy - cy) < 40f && (l.cx - cx) * dir in 0f..150f) {
                    mode = 1; modeT = 0f
                    g.say(cx, y - 6f, "HONK HONK!", 0.9f)
                    g.sfx(Sound.BUMP)
                }
            }
            1 -> { vx = dir * 0.01f; if (modeT > 0.6f) { mode = 2; modeT = 0f } }
            else -> { vx = dir * 150f; if (modeT > 1f) { mode = 0; modeT = 0f } }
        }
        // Springy wheels: it hops up little steps (looking further ahead the faster it goes).
        val ahead = cx + dir * (w / 2 + 4f + abs(vx) * 0.1f)
        if (onGround && g.level.solidAt(ahead, bottom - 4f) && !g.level.solidAt(ahead, bottom - 20f)) vy = -250f
        val before = vx
        walk(g, dt)
        // Bumped into a wall: turn around (and stop zooming).
        if (vx * before < 0f) { dir = -dir; if (mode == 2) { mode = 0; modeT = 0f } }
    }

    override fun shapes(gfx: Gfx, px: Float) {
        // (Captured by Cappy it isn't updated, so the facing comes from vx.)
        val f = if (vx < 0f) -1f else 1f
        val mx = px + w / 2
        val bounce = when {
            mode == 1 -> sin(t * 50f) * 0.8f
            abs(vx) > 5f && onGround -> abs(sin(t * 14f)) * 0.8f
            else -> 0f
        }
        val top = y + bounce
        val yellow = 0xFFFFC82A.toInt()
        val dark = 0xFFD89A10.toInt()
        // Zoom lines behind it
        if (mode == 2) for (k in 0 until 3) gfx.rect(mx - f * (16f + k * 3f) - 4f, top + 4f + k * 3f, 8f, 1.2f, 0x99FFFFFF.toInt())
        // Wheels
        val spin = t * vx * 0.25f
        for (k in -1..1 step 2) {
            val wx = mx + k * 6.5f
            gfx.oval(wx - 3.5f, bottom - 7f, 7f, 7f, 0xFF1A1A22.toInt())
            gfx.oval(wx - 1.5f + cos(spin) * 0.8f, bottom - 5f + sin(spin) * 0.8f, 3f, 3f, 0xFFB8B8C8.toInt())
        }
        // The cab: a rounded body and a little cabin with its sign on top
        gfx.oval(px - 1f, top + 4f, 8f, 7f, yellow)
        gfx.oval(px + w - 7f, top + 4f, 8f, 7f, yellow)
        gfx.rect(px + 2f, top + 4f, w - 4f, 7f, yellow)
        gfx.poly(floatArrayOf(mx - 8f, mx - 5f, mx + 5f, mx + 8f), floatArrayOf(top + 5f, top, top, top + 5f), yellow)
        gfx.rect(mx - 3f, top - 3f, 6f, 3f, if (mode == 1 && (t * 12).toInt() % 2 == 0) 0xFFFF5A3A.toInt() else 0xFFFFF4C8.toInt())
        // Checkered stripe
        for (k in 0 until 9) gfx.rect(px + 2f + k * 2f, top + 7.5f, 2f, 1.5f, if (k % 2 == 0) 0xFF1A1A22.toInt() else 0xFFF4F4F4.toInt())
        gfx.rect(px + 2f, top + 10f, w - 4f, 1f, dark)
        // Windshield in front, with big eyes in it; a headlight and a bumper
        val wx = mx + f * 3f
        gfx.poly(floatArrayOf(wx - 3.5f, wx - 2.5f, wx + 2.5f, wx + 3.5f), floatArrayOf(top + 5f, top + 1f, top + 1f, top + 5f), 0xFF9AD8FF.toInt())
        eye(gfx, wx - 1.6f, top + 3.2f, f * 0.6f)
        eye(gfx, wx + 1.8f, top + 3.2f, f * 0.6f)
        if (mode == 1) gfx.rect(wx - 3.5f, top + 0.6f, 7f, 1.2f, 0xFF1A1420.toInt()) // grumpy brow
        gfx.oval(mx + f * 10f - 1.5f, top + 5f, 3f, 3f, 0xFFFFF8C8.toInt())
        gfx.rect(mx + f * 9f - 2f, top + 9.5f, 4f, 1.5f, 0xFFB8B8C8.toInt())
    }
}

/**
 * MEATBALL (level char 'ø', the Food Kingdom): a big round meatball with a blob of sauce on top. It rolls
 * along, and when you're close it rolls faster and faster at you. Stomp it flat!
 */
class Meatball(x: Float, bottom: Float) : TourFoe(x + 1, bottom - 14, 14f, 14f, 4) {
    private var spin = 0f
    init { vx = -30f }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        val l = g.leader
        val dx = l.cx - cx
        val chasing = abs(dx) < 140f && abs(l.cy - cy) < 50f
        val want = if (chasing) (if (dx > 0) 95f else -95f) else (if (vx >= 0f) 30f else -30f)
        // It speeds up (and slows down to turn) like a real ball.
        vx += (want - vx) * min(1f, dt * 2.5f)
        if (onGround && abs(vx) > 80f && t % 1.2f < dt) vy = -150f
        spin += vx * dt / 7f
        walk(g, dt)
    }

    override fun shapes(gfx: Gfx, px: Float) {
        val f = if (vx < 0f) -1f else 1f
        val mx = px + w / 2
        val my = y + h / 2
        gfx.oval(px, y, w, h, 0xFF8A4A2A.toInt())
        gfx.oval(px + 2f, y + 1.5f, w - 6f, h - 7f, 0xFFA8643A.toInt())
        // Bumpy bits that roll round as it rolls
        for (k in 0 until 4) {
            val a = spin + k * 1.571f
            gfx.oval(mx + cos(a) * 4.5f - 1.5f, my + sin(a) * 4.5f - 1.5f, 3f, 3f, 0xFF6A361E.toInt())
        }
        // Tomato sauce on top, and a green herb
        val wob = sin(t * 6f) * 0.6f
        gfx.oval(px + 2f, y - 1.5f + wob, w - 4f, 5f, 0xFFD8302A.toInt())
        gfx.oval(px + 4f, y - 0.5f + wob, 3f, 2f, 0xFFFF7A6A.toInt())
        gfx.rect(mx + 1f, y - 1f + wob, 2f, 1.2f, 0xFF4AB84A.toInt())
        // Grumpy eyes looking where it rolls
        eye(gfx, mx + f * 1.5f - 2.4f, my + 1f, f * 0.6f)
        eye(gfx, mx + f * 1.5f + 2.4f, my + 1f, f * 0.6f)
        gfx.poly(floatArrayOf(mx + f * 1.5f - 4.5f, mx + f * 1.5f, mx + f * 1.5f + 4.5f), floatArrayOf(my - 2.5f, my - 1f, my - 2.5f), 0xFF3A1A0E.toInt())
    }
}

/**
 * CHILI (level char 'ç'): a red-hot pepper that hops about and spits little fireballs at you in an arc.
 * Stomp it or hit it (it's SO spicy, it shouts HOT HOT HOT!).
 */
class Chili(x: Float, bottom: Float) : TourFoe(x + 3, bottom - 16, 10f, 16f, 3) {
    private var hopT = 0.8f
    private var spitT = 1.5f
    private var face = -1
    private var hot = 0f

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        t += dt
        if (hot > 0f) hot -= dt
        val l = g.leader
        val dx = l.cx - cx
        face = if (dx < 0f) -1 else 1
        if (onGround) {
            vx = 0f
            hopT -= dt
            if (hopT <= 0f) { hopT = 0.9f + Random.nextFloat() * 0.5f; vy = -190f; vx = face * 18f }
        }
        spitT -= dt
        if (spitT <= 0f && abs(dx) < 170f && abs(l.cy - cy) < 70f) {
            spitT = 2.4f + Random.nextFloat()
            hot = 0.5f
            g.spawn(ChiliFlame(cx - 4f + face * 6f, y + 1f, l.cx))
            g.sfx(Sound.FIRE)
        }
        walk(g, dt)
    }

    override fun stomp(g: TeamGame, stomper: Hero) {
        super.stomp(g, stomper)
        g.say(cx, y - 6f, "HOT HOT HOT!", 0.9f)
    }

    override fun shapes(gfx: Gfx, px: Float) {
        val f = if (vx != 0f) (if (vx < 0f) -1f else 1f) else face.toFloat()
        val mx = px + w / 2
        val red = if (hot > 0f) 0xFFFF5A2A.toInt() else 0xFFE8281E.toInt()
        // A curvy pepper body, pointy at the bottom
        gfx.oval(px, y + 2f, w, 10f, red)
        gfx.poly(floatArrayOf(px + 0.5f, px + w - 0.5f, mx - f * 3f), floatArrayOf(y + 8f, y + 8f, bottom), red)
        gfx.oval(px + 2f, y + 3.5f, 2.5f, 6f, 0x88FFFFFF.toInt())
        // Green cap and a curly stem
        gfx.oval(px + 1f, y, w - 2f, 4f, 0xFF3AA83A.toInt())
        gfx.poly(floatArrayOf(mx - 1f, mx + 1f, mx + f * 3f, mx + f * 2f), floatArrayOf(y + 1f, y + 1f, y - 4f, y - 4.5f), 0xFF2A8A2A.toInt())
        eye(gfx, mx - 2f + f, y + 6f, f * 0.6f)
        eye(gfx, mx + 2f + f, y + 6f, f * 0.6f)
        // An open mouth when it spits, a cheeky grin otherwise
        if (hot > 0f) gfx.oval(mx + f * 1.5f - 1.5f, y + 9f, 3f, 2.5f, 0xFF5A0A0A.toInt())
        else gfx.rect(mx + f - 1.8f, y + 9.5f, 3.6f, 0.9f, 0xFF5A0A0A.toInt())
        if (hot > 0f) for (k in 0 until 3) gfx.oval(mx - 4f + k * 3f, y - 7f - k % 2 * 2f, 2f, 2f, 0xCCFFB04A.toInt())
    }
}

/** A little fireball from a CHILI: it flies in an arc and fizzles when it lands. */
class ChiliFlame(x: Float, y: Float, tx: Float) : Foe(x, y, 8f, 8f, 1), Projectile {
    private var t = 0f
    override val stompable = false
    override val showBar get() = false
    init {
        active = true
        vx = ((tx - x) / 1.1f).coerceIn(-140f, 140f)
        vy = -220f
    }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (dying) { removed = true; return }
        vy = min(vy + GRAVITY * 0.5f * dt, 300f)
        x += vx * dt
        y += vy * dt
        if (g.level.solidAt(cx, bottom) || t > 3f) { removed = true; g.puff(cx, cy) }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val flick = sin(t * 40f) * 1f
        gfx.oval(px - 2f, y - 2f + flick, 12f, 12f, 0x66FF8A2A)
        gfx.oval(px, y, 8f, 8f, 0xFFFF5A1A.toInt())
        gfx.oval(px + 2f, y + 2f, 4f, 4f, 0xFFFFE14D.toInt())
    }
}

/** A PIE from Chef Krag: thrown in an arc, it goes SPLAT where it lands. (A soup splash if [soup].) */
class Pie(x: Float, y: Float, tx: Float, private val soup: Boolean = false) : Foe(x, y, 12f, 8f, 1), Projectile {
    private var t = 0f
    override val stompable = false
    override val showBar get() = false
    init {
        active = true
        vx = ((tx - x) / 1.2f).coerceIn(-180f, 180f)
        vy = if (soup) -300f else -240f
    }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (dying) { removed = true; return }
        vy = min(vy + GRAVITY * 0.55f * dt, 330f)
        x += vx * dt
        y += vy * dt
        if (vy > 0f && g.level.solidAt(cx, bottom) || t > 4f) {
            removed = true
            g.puff(cx, cy)
            g.sfx(Sound.SPLASH)
        }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        if (soup) {
            gfx.oval(px + 1f, y, 10f, 9f, 0xFFE85A2A.toInt())
            gfx.oval(px + 3f, y + 1.5f, 3f, 3f, 0xFFFFA86A.toInt())
            gfx.rect(px + 6f, y + 4f, 2f, 2f, 0xFFFF9A3A.toInt())
            return
        }
        val tilt = sin(t * 8f) * 1.5f
        gfx.oval(px - 1f, y + 2f + tilt * 0.3f, 14f, 7f, 0xFFC8843A.toInt())
        gfx.oval(px, y + 1f, 12f, 5f, 0xFFE84A6A.toInt())
        gfx.oval(px + 3f, y - 2f, 6f, 4f, 0xFFFFFFFF.toInt())
        gfx.oval(px + 5f, y - 3.5f, 2.5f, 2.5f, 0xFFE8283A.toInt())
    }
}

/** A giant meatball Chef Krag BOWLS along the floor. It can't be stomped: JUMP OVER IT (or blast it). */
class BigMeatball(x: Float, bottom: Float, private val dir: Int) : Foe(x, bottom - 16, 16f, 16f, 1), Projectile {
    private var t = 0f
    override val stompable = false
    override val showBar get() = false
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (dying) { removed = true; return }
        x += dir * 125f * dt
        // It rolls until it bumps into the kitchen wall.
        if (g.level.solidAt(cx + dir * 9f, cy) || t > 5f) { removed = true; g.puff(cx, cy); g.sfx(Sound.STOMP) }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val a = t * dir * 9f
        gfx.oval(px - 1f, y - 1f, 18f, 18f, 0xFF3A1A0E.toInt())
        gfx.oval(px, y, 16f, 16f, 0xFF8A4A2A.toInt())
        for (k in 0 until 5) gfx.oval(px + 8f + cos(a + k * 1.257f) * 5f - 1.5f, y + 8f + sin(a + k * 1.257f) * 5f - 1.5f, 3f, 3f, 0xFF6A361E.toInt())
        gfx.oval(px + 3f, y + 2f, 5f, 3f, 0x55FFFFFF)
    }
}

/** Chef Krag's giant soup pot (part of his kitchen arena). It bubbles, and boils over when he stirs it. */
class SoupPot(x: Float, bottom: Float) : Thing(x - 18f, bottom - 26f, 36f, 26f) {
    private var t = 0f
    /** Boiling over while this counts down. */
    var boil = 0f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (boil > 0f) boil -= dt
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        if (gfx !is SilhouetteGfx) shapes(SilhouetteGfx(if (gfx is TintGfx) gfx.inner else gfx, 0.8f), ox)
        shapes(gfx, ox)
    }

    private fun shapes(gfx: Gfx, ox: Float) {
        val px = x + ox
        val hot = boil > 0f
        // Fire under the pot
        for (k in 0 until 4) {
            val fh = 5f + abs(sin(t * 9f + k)) * 4f
            gfx.poly(floatArrayOf(px + 6f + k * 8f, px + 9f + k * 8f, px + 12f + k * 8f), floatArrayOf(bottom, bottom - fh, bottom), 0xFFFF8A1A.toInt())
        }
        gfx.rect(px + 2f, bottom - 22f, w - 4f, 18f, 0xFF5A6A80.toInt())
        gfx.rect(px + 4f, bottom - 20f, 5f, 14f, 0xFF8A9AB0.toInt())
        gfx.rect(px, bottom - 24f, w, 4f, 0xFF7A8AA0.toInt())
        gfx.rect(px - 4f, bottom - 19f, 4f, 3f, 0xFF4A5A70.toInt())
        gfx.rect(px + w, bottom - 19f, 4f, 3f, 0xFF4A5A70.toInt())
        // Soup at the top, bubbling (and spilling over when he stirs it)
        gfx.oval(px + 2f, bottom - 27f, w - 4f, 6f, 0xFFE85A2A.toInt())
        for (k in 0 until 3) {
            val b = (t * 1.5f + k * 0.37f) % 1f
            gfx.oval(px + 8f + k * 9f, bottom - 27f - b * 4f, 3f + b * 2f, 3f + b * 2f, 0xCCFFA86A.toInt())
        }
        if (hot) for (k in 0 until 2) gfx.oval(px + 3f + k * (w - 10f), bottom - 24f + (t * 10f % 6f), 4f, 6f, 0xFFE85A2A.toInt())
        // Steam
        for (k in 0 until 3) {
            val s = (t * 0.5f + k * 0.33f) % 1f
            gfx.oval(px + 8f + k * 8f + sin(t + k) * 3f, bottom - 32f - s * 30f, 8f + s * 6f, 6f + s * 4f, argb((150 * (1f - s)).toInt(), 0xFFFFFF))
        }
    }
}

/**
 * CHEF KRAG (level char '§'), the last boss of the whole game (14-21): King Krag in a chef's hat and apron,
 * in his giant kitchen. He throws PIES at you, BOWLS giant meatballs along the floor (jump over them!), and
 * when he gets angry he stirs his soup pot so hot soup splashes out. Stomp his head or hit him!
 */
class ChefKrag(x: Float, private val ground: Float) : Foe(x - 10, ground - 40, 36f, 40f, 130), NamedBoss {
    override var awake = false
        private set
    override val homeX = x
    override val boss = true
    override val stompable = true
    override val bossName = "CHEF KRAG"
    override val bossSub = "Dodge his PIES and JUMP over the rolling meatballs! Stomp his head!"
    private var t = 0f
    private var pieT = 2.2f
    private var bowlT = 6f
    private var stirT = 9f
    private var stirring = 0f
    /** Walking over to his soup pot to stir it (counts down: if he can't get there in time, he doesn't stir). */
    private var toPotT = 0f
    /** Off to stir his soup pot (no pies or meatballs on the way). */
    val goingToPot get() = toPotT > 0f
    private var throwT = 0f
    private var hurtT = 0f
    private var facing = -1
    private var pot: SoupPot? = null
    /** He looks just like King Krag (plus the chef things drawn on top). */
    private val look = KingKrag(x, ground)

    private companion object {
        /** He stirs standing this far from the middle of his pot: his big spoon reaches into the soup from there. */
        const val STIR_REACH = 30f
    }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        hurtT -= dt
        if (throwT > 0f) throwT -= dt
        if (pot == null) pot = SoupPot(homeX + 70f, ground).also { g.spawn(it) }
        if (updateDying(g, dt)) return
        val l = g.leader
        if (!awake) {
            if (l.cx > homeX - g.viewW * 0.45f) {
                awake = true
                g.onBossEngaged(this)
                g.say(cx, y - 8, "DINNER IS SERVED! IT'S YOU!", 2.5f)
                g.sayAs("Dinner is served!", Voice.GOD)
            }
            return
        }
        val angry = hp < maxHp / 2
        facing = if (l.cx < cx) -1 else 1
        val p = pot
        if (stirring > 0f) {
            // Stirring the pot: soup splashes out all over the kitchen.
            stirring -= dt
            vx = 0f
            if (p != null) facing = if (p.cx < cx) -1 else 1
            if ((stirring * 4f).toInt() != ((stirring + dt) * 4f).toInt() && stirring > 0.2f) {
                val sx = p?.cx ?: cx
                g.spawn(Pie(sx - 5f, (p?.y ?: y) - 6f, l.cx + (Random.nextFloat() - 0.5f) * 120f, soup = true))
            }
        } else if (toPotT > 0f && p != null) {
            // Off to his pot first, so his spoon really goes in the soup (not in the air across the kitchen).
            toPotT -= dt
            val dx = (if (cx < p.cx) p.cx - STIR_REACH else p.cx + STIR_REACH) - cx
            if (abs(dx) < 4f) {
                toPotT = 0f
                vx = 0f
                stirring = 1.5f
                p.boil = 1.6f
                g.say(cx, y - 8, "SOUP'S ON!", 1.4f)
                g.sfx(Sound.SPLASH)
            } else {
                // (If something keeps him from getting there, he gives up when the time runs out: no stirring.)
                facing = if (dx < 0f) -1 else 1
                if (onGround) vx = facing * 60f
            }
        } else if (onGround) {
            // Waddle about the kitchen, staying near his pot.
            vx = if (abs(cx - homeX) > 70f) (if (cx > homeX) -30f else 30f) else facing * (if (angry) 32f else 22f)
        }
        // (Decided first, so he doesn't throw a pie on the very moment he sets off for his pot.)
        stirT -= dt
        if (stirT <= 0f && angry && onGround && stirring <= 0f && toPotT <= 0f) {
            stirT = 9f
            toPotT = 3f
        }
        val busy = stirring > 0f || toPotT > 0f
        // (The timers wait while he walks to his pot and stirs, so pies and meatballs don't all come at once after.)
        if (!busy) pieT -= dt
        if (pieT <= 0f && !busy) {
            pieT = if (angry) 1.6f else 2.3f
            throwT = 0.35f
            val hx = if (facing > 0) x + w else x - 12f
            g.spawn(Pie(hx, y + 4f, l.cx))
            if (angry) g.spawn(Pie(hx, y + 4f, l.cx + facing * 46f))
            g.sfx(Sound.KICK)
        }
        if (!busy) bowlT -= dt
        if (bowlT <= 0f && onGround && !busy) {
            bowlT = if (angry) 5f else 6.5f
            g.say(cx, y - 8, "MEATBALL BOWLING!", 1.2f)
            g.sfx(Sound.STOMP)
            g.spawn(BigMeatball(if (facing > 0) x + w else x - 16f, bottom, facing))
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
            g.addScore(25000, cx, y)
            g.say(cx, y - 8, "MY SOUP... IS RUINED!", 2f)
            g.onBossBeaten(this)
        }
        return true
    }

    override fun stomp(g: TeamGame, stomper: Hero) { hit(g, 3) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        look.x = x; look.y = y; look.vx = vx; look.facing = facing
        look.draw(g, gfx, ox)
        if (gfx !is SilhouetteGfx) chef(SilhouetteGfx(if (gfx is TintGfx) gfx.inner else gfx, 0.8f), ox)
        chef(gfx, ox)
        drawFlash(gfx, ox)
    }

    /** The chef's hat, apron, mustache and wooden spoon (or a pie, while he throws one). */
    private fun chef(gfx: Gfx, ox: Float) {
        val px = x + ox
        val f = facing.toFloat()
        val white = 0xFFFAFAF4.toInt()
        val shade = 0xFFDCDCE4.toInt()
        // A tall puffy chef's hat on his head
        val hx = px + 18f + f * 7f
        gfx.oval(hx - 13f, y - 19f, 14f, 14f, white)
        gfx.oval(hx - 6f, y - 24f, 14f, 15f, white)
        gfx.oval(hx + 1f, y - 19f, 13f, 14f, white)
        // (A filled middle, so no outline peeks through where the puffs meet.)
        gfx.rect(hx - 8f, y - 17f, 16f, 10f, white)
        gfx.rect(hx - 10f, y - 8f, 20f, 7f, white)
        gfx.rect(hx - 10f, y - 3f, 20f, 2f, shade)
        // His apron, with a red heart on it
        val bx = px + 18f + f * 4f
        gfx.rect(bx - 7f, y + 16f, 14f, 18f, white)
        gfx.rect(bx - 7f, y + 31f, 14f, 3f, shade)
        gfx.rect(bx - 10f, y + 19f, 20f, 1.5f, white)
        gfx.oval(bx - 3f, y + 21f, 3.5f, 3.5f, 0xFFE8283A.toInt())
        gfx.oval(bx - 0.5f, y + 21f, 3.5f, 3.5f, 0xFFE8283A.toInt())
        gfx.poly(floatArrayOf(bx - 3f, bx + 3f, bx), floatArrayOf(y + 23f, y + 23f, y + 27f), 0xFFE8283A.toInt())
        // A curly chef's mustache
        val mx = px + 18f + f * 13f
        gfx.oval(mx - 6f, y + 10f, 6f, 3f, 0xFF3A1A0E.toInt())
        gfx.oval(mx, y + 10f, 6f, 3f, 0xFF3A1A0E.toInt())
        // In his hand: a pie to throw, or his big wooden spoon (stirring away while he stirs)
        val hand = px + 18f + f * 12f
        if (throwT > 0f) {
            gfx.oval(hand - 7f, y + 13f, 14f, 6f, 0xFFC8843A.toInt())
            gfx.oval(hand - 6f, y + 12f, 12f, 4f, 0xFFE84A6A.toInt())
        } else {
            // Held out in front, tipped forward (under his chin, so it never hides his face).
            val sw = if (stirring > 0f) sin(t * 18f) * 4f else 0f
            val tipX = px + 18f + f * 24f + sw
            val tipY = y + 15f
            gfx.poly(floatArrayOf(hand - 1.2f, hand + 1.2f, tipX + 1.2f, tipX - 1.2f), floatArrayOf(y + 25f, y + 25f, tipY, tipY), 0xFFB8783A.toInt())
            gfx.oval(tipX - 3.5f, tipY - 5f, 7f, 8f, 0xFFC8884A.toInt())
        }
    }
}

// ---------------------------------------------------------------------------- The Big City's look

/** The Big City: rooftops with windows below, and a night skyline with neon signs, cranes and taxis. */
object CityArt {
    private const val OUT = 0xFF14101E.toInt()
    private const val WALL = 0xFF3A3458.toInt()
    private const val MORTAR = 0xFF2C2646.toInt()
    private const val LIT = 0xFFFFD86A.toInt()
    private const val LIT2 = 0xFFFFF0B0.toInt()
    private const val DARK = 0xFF22304E.toInt()

    private fun tile(fill: (Int, Int) -> Int): Sprite = Sprite(16, 16, IntArray(256) { i ->
        val x = i % 16; val y = i / 16
        val c = fill(x, y)
        if (x == 15 || y == 15) mixColor(c, OUT, 0.35f) else c
    })

    /** A window pane at (x, y) inside the window, lit or not. */
    private fun pane(x: Int, y: Int, lit: Boolean) = when {
        !lit -> if (x == 0 && y == 0) 0xFF3A4A6E.toInt() else DARK
        x == 0 || y == 0 -> LIT2
        else -> LIT
    }

    /** A brick wall with two windows ([lit] says which are lit: bit 1 left, bit 2 right) from row [top]. */
    private fun wall(x: Int, y: Int, top: Int, lit: Int): Int {
        val wy = y - top
        val left = x in 2..6
        val right = x in 9..13
        return when {
            wy in 0..6 && (left || right) -> {
                val on = if (left) lit and 1 != 0 else lit and 2 != 0
                if (wy == 0 || wy == 6 || x == 2 || x == 6 || x == 9 || x == 13) 0xFF241E3A.toInt()
                else pane(if (left) x - 3 else x - 10, wy - 1, on)
            }
            // Bricks: a mortar line every 4 rows, and the joints between bricks staggered row by row.
            y % 4 == 3 || (x + (if ((y / 4) % 2 == 0) 0 else 4)) % 8 == 0 -> MORTAR
            else -> WALL
        }
    }

    private val tops: List<Sprite> by lazy {
        listOf(3, 1, 2, 0).map { lit ->
            tile { x, y ->
                when {
                    y == 0 -> 0xFFD8DCE8.toInt()
                    y < 3 -> if (x % 8 == 7) 0xFF7A8094.toInt() else 0xFF9AA0B4.toInt()
                    y == 3 -> 0xFF22203A.toInt()
                    else -> wall(x, y, 6, lit)
                }
            }
        }
    }
    private val unders: List<Sprite> by lazy { listOf(1, 3, 0, 2, 3).map { lit -> tile { x, y -> wall(x, y, 4, lit) } } }

    /** A rooftop, or the building's wall under it (the windows change from tile to tile). */
    fun ground(under: Boolean, tx: Int, ty: Int): Sprite {
        val k = (tx * 7 + ty * 13 + (tx shr 2) * 5) and 0xFFFF
        return if (under) unders[k % unders.size] else tops[k % tops.size]
    }

    /** A skyscraper (cached as a sprite): [shape] picks the roof, [near] the brighter, closer colors. */
    private fun tower(shape: Int, near: Boolean): Sprite {
        val w = intArrayOf(16, 20, 14, 18)[shape]
        val h = intArrayOf(56, 72, 80, 64)[shape]
        val body = if (near) 0xFF1E1840.toInt() else 0xFF2E2658.toInt()
        val edge = if (near) 0xFF2E2860.toInt() else 0xFF3A3268.toInt()
        val on = if (near) LIT else 0xFFB8A86A.toInt()
        val off = if (near) 0xFF2A3256.toInt() else 0xFF343060.toInt()
        val roof = intArrayOf(6, 8, 18, 10)[shape]
        return Sprite(w, h, IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val inBody = when (shape) {
                0 -> y >= roof || (x == w / 2 && y >= 0)                              // flat top and an antenna
                1 -> y >= roof || (y >= 3 && x in w / 2 - 3..w / 2 + 2)               // stepped top
                2 -> y >= roof || abs(x - w / 2 + 0.5f) < (y + 1) * 0.4f              // a pointy spire
                else -> y >= roof || (y in 2 until roof && x in 3..8) || (y == roof - 1 && x in 2..9) // a water tower
            }
            when {
                !inBody -> 0
                y < roof -> edge
                x == 0 || x == w - 1 -> edge
                x % 3 != 0 && (y - roof) % 4 in 1..2 -> if (((x / 3) * 7 + ((y - roof) / 4) * 3 + shape * 5) % 5 < 2) on else off
                else -> body
            }
        })
    }

    private val towers: List<Sprite> by lazy { (0 until 8).map { tower(it % 4, it >= 4) } }
    private val SIGNS = listOf("PIZZA", "HOTEL", "TAXI", "OPEN", "DINER", "ARCADE", "BURGERS", "SHOW")
    private val NEON = intArrayOf(0xFFFF4AA8.toInt(), 0xFF4AE8FF.toInt(), 0xFFFFE14D.toInt(), 0xFF7AFF6A.toInt(), 0xFFFF8A3A.toInt())

    private inline fun layer(viewW: Float, cam: Float, factor: Float, spacing: Float, draw: (Int, Float) -> Unit) {
        val off = cam * factor
        val first = (off / spacing).toInt() - 1
        val last = ((off + viewW) / spacing).toInt() + 1
        for (i in first..last) draw(i and 0xFFFF, i * spacing - off)
    }

    /** The night sky; with [scenery] false only the sky, stars and moon (Team Up! 3D builds the buildings in 3D). */
    fun drawSky(gfx: Gfx, viewW: Float, viewH: Float, cam: Float, t: Float, scenery: Boolean = true) {
        gfx.gradientStops(0f, 0f, viewW, viewH, 0xFF080A24.toInt(), 0xFF241A54.toInt(), 0xFF6A2A72.toInt(), 0xFFC8507A.toInt())
        for (i in 0 until 28) gfx.rect((i * 97f - cam * 0.03f).mod(viewW), (i * 53f) % 110f, 1.5f, 1.5f, if (i % 5 == 0) 0xFFFFE8B0.toInt() else 0xCCFFFFFF.toInt())
        gfx.oval(viewW * 0.78f - cam * 0.01f, 18f, 26f, 26f, 0xFFF4ECD0.toInt())
        gfx.oval(viewW * 0.78f - cam * 0.01f + 5f, 24f, 6f, 5f, 0xFFDCD4B8.toInt())
        if (!scenery) return
        // A construction crane far away, with a steel beam swinging on its hook
        layer(viewW, cam, 0.1f, 560f) { i, x ->
            val cx = x + 120f + (i % 3) * 60f
            val c = 0xFF3A2E66.toInt()
            gfx.rect(cx, 40f, 5f, 160f, c)
            for (k in 0 until 8) gfx.rect(cx - 1f, 50f + k * 19f, 7f, 1.5f, c)
            gfx.rect(cx - 30f, 38f, 120f, 4f, c)
            gfx.rect(cx - 30f, 42f, 10f, 8f, c)
            gfx.poly(floatArrayOf(cx - 2f, cx + 2.5f, cx + 7f), floatArrayOf(38f, 26f, 38f), c)
            val sw = sin(t * 0.8f + i) * 4f
            val hookX = cx + 70f
            gfx.rect(hookX, 42f, 1f, 50f, c)
            gfx.rect(hookX - 16f + sw, 92f, 32f, 4f, 0xFF4A3A7A.toInt())
            if ((t * 1.5f + i).toInt() % 2 == 0) gfx.oval(cx, 23f, 5f, 5f, 0xFFFF3A3A.toInt())
        }
        // Far skyscrapers, then nearer ones with neon signs
        layer(viewW, cam, 0.15f, 44f) { i, x ->
            val s = towers[(i * 5 + i / 3) % 4]
            val sc = 1.5f + (i % 3) * 0.2f
            gfx.sprite(s, x, 200f - s.h * sc, w = s.w * sc, h = s.h * sc)
        }
        layer(viewW, cam, 0.3f, 70f) { i, x ->
            val s = towers[4 + (i * 3 + i / 2) % 4]
            val sc = 1.8f + (i % 2) * 0.3f
            val top = 210f - s.h * sc
            gfx.sprite(s, x, top, w = s.w * sc, h = s.h * sc)
            if (i % 2 == 0) {
                // A glowing neon sign on the side of the building
                val sign = SIGNS[(i / 2) % SIGNS.size]
                val c = NEON[(i / 2) % NEON.size]
                val sw = sign.length * 4.2f + 6f
                val sx = x + s.w * sc / 2 - sw / 2
                val sy = top + 26f + (i % 3) * 10f
                val on = (t * 2f + i).toInt() % 7 != 0
                gfx.rect(sx - 2f, sy - 2f, sw + 4f, 13f, if (on) argb(70, c) else 0x22000000)
                gfx.rect(sx, sy, sw, 9f, 0xFF140E28.toInt())
                gfx.text(sign, sx + sw / 2, sy + 7.5f, 6.5f, if (on) c else mixColor(c, 0xFF000000.toInt(), 0.6f), 1)
            }
        }
        // An elevated road in front, with taxis zooming along it
        val roadY = 186f
        gfx.rect(0f, roadY, viewW, 6f, 0xFF2A2440.toInt())
        gfx.rect(0f, roadY - 1f, viewW, 1.5f, 0xFF5A5280.toInt())
        layer(viewW, cam, 0.45f, 60f) { _, x -> gfx.rect(x, roadY + 6f, 4f, 24f, 0xFF2A2440.toInt()) }
        for (k in 0 until 4) {
            val dir = if (k % 2 == 0) 1f else -1f
            val tx = (k * 157f + dir * t * (50f + k * 12f) - cam * 0.45f).mod(viewW + 40f) - 20f
            gfx.rect(tx, roadY - 5f, 12f, 4f, 0xFFFFC82A.toInt())
            gfx.rect(tx + 3f, roadY - 7.5f, 6f, 3f, 0xFFFFC82A.toInt())
            gfx.rect(tx + (if (dir > 0) 11f else -1f), roadY - 4.5f, 2f, 1.5f, 0xFFFFF8C8.toInt())
            gfx.oval(tx + 1.5f, roadY - 2f, 3f, 3f, 0xFF1A1A22.toInt())
            gfx.oval(tx + 7.5f, roadY - 2f, 3f, 3f, 0xFF1A1A22.toInt())
        }
    }

    /** A little skyline for the World 14 map. */
    fun miniSkyline(gfx: Gfx, x: Float, base: Float, t: Float) {
        for (k in 0 until 3) {
            val s = towers[4 + (k + 1) % 4]
            gfx.sprite(s, x + k * 9f, base - s.h * 0.35f, w = s.w * 0.35f, h = s.h * 0.35f)
        }
        // A tiny neon sign that blinks
        val on = (t * 2f).toInt() % 5 != 0
        gfx.rect(x + 5f, base - 14f, 16f, 5f, 0xFF140E28.toInt())
        gfx.rect(x + 6f, base - 13f, 14f, 3f, if (on) 0xFFFF4AA8.toInt() else 0xFF6A2A4A.toInt())
    }
}

// ---------------------------------------------------------------------------- The Food Kingdom's look

/** The Food Kingdom: cheese on bread to walk on, toast and cheese blocks, hot tomato soup, giant veggies and forks. */
object FoodArt {
    private const val OUT = 0xFF3A1A0E.toInt()
    private const val BREAD = 0xFFF6E0B0.toInt()
    private const val CHEESE = 0xFFFFC21A.toInt()

    private fun tile(edge: Boolean = true, fill: (Int, Int) -> Int): Sprite = Sprite(16, 16, IntArray(256) { i ->
        val x = i % 16; val y = i / 16
        val c = fill(x, y)
        if (edge && (x == 15 || y == 15)) mixColor(c, OUT, 0.3f) else c
    })

    /** Soft white bread with little air holes. */
    private fun crumb(x: Int, y: Int) = when {
        (x - 4) * (x - 4) + (y - 6) * (y - 6) < 3 || (x - 11) * (x - 11) + (y - 11) * (y - 11) < 4 || (x - 12) * (x - 12) + (y - 3) * (y - 3) < 2 -> 0xFFE0BC80.toInt()
        (x - 4) * (x - 4) + (y - 5) * (y - 5) < 3 || (x - 11) * (x - 11) + (y - 10) * (y - 10) < 3 -> 0xFFD0A060.toInt()
        else -> BREAD
    }

    /** Melty orange cheese (with holes) dripping over soft bread. */
    val groundTop: Sprite by lazy {
        tile { x, y ->
            val drip = 5 + (if (x % 7 == 2) 4 else if (x % 7 == 1 || x % 7 == 3) 2 else 0)
            when {
                y == 0 -> 0xFFFFE070.toInt()
                y < drip && ((x - 10) * (x - 10) + (y - 2) * (y - 2) < 3 || (x - 4) * (x - 4) + (y - 3) * (y - 3) < 2) -> 0xFFE89A10.toInt()
                y < drip -> CHEESE
                y == drip -> 0xFFD8880E.toInt()
                else -> crumb(x, y)
            }
        }
    }
    val groundUnder: Sprite by lazy { tile { x, y -> crumb(x, (y + 6) % 16) } }

    /** A slice of toast: a golden crust around the soft middle. */
    val toast: Sprite by lazy {
        tile { x, y ->
            when {
                x < 2 || y < 2 || x > 13 || y > 13 -> 0xFFB8702A.toInt()
                (x * 7 + y * 3) % 17 == 0 -> 0xFFD8984A.toInt()
                abs(x - 7.5f) + abs(y - 7.5f) < 5f -> 0xFFF8DC9A.toInt()
                else -> 0xFFF0C878.toInt()
            }
        }
    }

    /** A block of holey cheese. */
    val cheese: Sprite by lazy {
        tile { x, y ->
            val hole = (x - 4) * (x - 4) + (y - 4) * (y - 4) < 5 || (x - 11) * (x - 11) + (y - 9) * (y - 9) < 7 || (x - 4) * (x - 4) + (y - 12) * (y - 12) < 3
            when {
                hole -> 0xFFE0A42A.toInt()
                y == 0 || x == 0 -> 0xFFFFEA8A.toInt()
                else -> CHEESE
            }
        }
    }

    private const val SOUP = 0xFFE24E24.toInt()

    /** Tomato soup with a few chunky carrot cubes and peas floating in it. */
    private fun soup(x: Int, y: Int, deep: Boolean) = when {
        x in 3..4 && y in 6..7 || x in 11..12 && y in 11..12 -> 0xFFFF9A2A.toInt()
        (x - 8) * (x - 8) + (y - 9) * (y - 9) < 2 || (x - 14) * (x - 14) + (y - 5) * (y - 5) < 2 -> 0xFF7AD84A.toInt()
        deep -> mixColor(SOUP, 0xFF8A1A0A.toInt(), 0.15f + y / 60f)
        else -> SOUP
    }

    /** Hot tomato soup (it works just like lava: don't fall in!). The top has a wavy, bubbly surface. */
    val soupTop: Sprite by lazy {
        tile(edge = false) { x, y ->
            val wave = if (x % 8 in 2..5) 0 else 1
            when {
                y < wave -> 0
                y <= wave + 1 -> 0xFFFF9A6A.toInt()
                (x - 6) * (x - 6) + (y - 3) * (y - 3) < 2 -> 0xFFFFC09A.toInt()
                else -> soup(x, y, false)
            }
        }
    }
    val soupDeep: Sprite by lazy { tile(edge = false) { x, y -> soup((x + 5) % 16, (y + 4) % 16, true) } }

    private inline fun layer(viewW: Float, cam: Float, factor: Float, spacing: Float, draw: (Int, Float) -> Unit) {
        val off = cam * factor
        val first = (off / spacing).toInt() - 1
        val last = ((off + viewW) / spacing).toInt() + 1
        for (i in first..last) draw(i and 0xFFFF, i * spacing - off)
    }

    /** A giant fork stuck in the ground, prongs up. */
    fun fork(gfx: Gfx, cx: Float, top: Float, bottom: Float, s: Float) {
        val c = 0xFFD8DCE8.toInt(); val dk = 0xFFA0A8BC.toInt()
        gfx.rect(cx - 2.5f * s, top + 30f * s, 5f * s, bottom - top - 30f * s, c)
        gfx.rect(cx - 1f * s, top + 30f * s, 1.5f * s, bottom - top - 30f * s, 0xFFF4F6FA.toInt())
        gfx.oval(cx - 9f * s, top + 18f * s, 18f * s, 16f * s, c)
        for (k in 0 until 4) gfx.rect(cx - 8f * s + k * 4.6f * s, top, 2.6f * s, 26f * s, if (k % 2 == 0) c else dk)
    }

    /** A carrot stuck in the ground, leaves up. */
    fun carrot(gfx: Gfx, cx: Float, top: Float, bottom: Float, s: Float) {
        gfx.poly(floatArrayOf(cx - 10f * s, cx + 10f * s, cx + 2f * s, cx - 2f * s), floatArrayOf(top + 14f * s, top + 14f * s, bottom, bottom), 0xFFFF8A2A.toInt())
        for (k in 0 until 4) gfx.rect(cx - 6f * s + k * 2f * s, top + 22f * s + k * 9f * s, 8f * s, 1.5f * s, 0xFFD8681A.toInt())
        for (k in -1..1) gfx.poly(floatArrayOf(cx - 2f * s, cx + 2f * s, cx + k * 9f * s), floatArrayOf(top + 15f * s, top + 15f * s, top), 0xFF4AB84A.toInt())
    }

    /** A broccoli tree. */
    fun broccoli(gfx: Gfx, cx: Float, top: Float, bottom: Float, s: Float) {
        gfx.rect(cx - 5f * s, top + 20f * s, 10f * s, bottom - top - 20f * s, 0xFF9AD86A.toInt())
        val g = 0xFF3A9A3A.toInt()
        gfx.oval(cx - 22f * s, top + 8f * s, 22f * s, 20f * s, g)
        gfx.oval(cx, top + 8f * s, 22f * s, 20f * s, g)
        gfx.oval(cx - 14f * s, top - 2f * s, 28f * s, 24f * s, 0xFF4AAA4A.toInt())
        gfx.oval(cx - 8f * s, top + 2f * s, 7f * s, 6f * s, 0xFF6ACA5A.toInt())
    }

    /** The green star of leaves (and a little stem) on top of a tomato, [r] wide, centered at ([cx], [cy]). */
    private fun tomatoTop(gfx: Gfx, cx: Float, cy: Float, r: Float) {
        val g = 0xFF3A9A3A.toInt()
        for (k in 0 until 5) {
            val a = -1.571f + k * 1.257f
            gfx.poly(
                floatArrayOf(cx + cos(a - 0.9f) * r * 0.22f, cx + cos(a) * r, cx + cos(a + 0.9f) * r * 0.22f),
                floatArrayOf(cy + sin(a - 0.9f) * r * 0.12f, cy + sin(a) * r * 0.35f, cy + sin(a + 0.9f) * r * 0.12f), g,
            )
        }
        gfx.rect(cx - r * 0.05f, cy - r * 0.3f, r * 0.1f, r * 0.3f, 0xFF2A7A2A.toInt())
    }

    /** A tomato (for the map). */
    fun tomato(gfx: Gfx, cx: Float, cy: Float, r: Float) {
        gfx.oval(cx - r, cy - r * 0.9f, r * 2, r * 1.8f, 0xFFE8382A.toInt())
        gfx.oval(cx - r * 0.6f, cy - r * 0.6f, r * 0.5f, r * 0.4f, 0x88FFFFFF.toInt())
        tomatoTop(gfx, cx, cy - r * 0.85f, r * 0.9f)
    }

    /** The sunny sky; with [scenery] false only the sky and sun (Team Up! 3D builds the clouds and the food in 3D). */
    fun drawSky(gfx: Gfx, viewW: Float, viewH: Float, cam: Float, t: Float, scenery: Boolean = true) {
        gfx.gradientStops(0f, 0f, viewW, viewH, 0xFF4AB0F0.toInt(), 0xFF9ADCF8.toInt(), 0xFFFFF0C8.toInt())
        val sunX = viewW * 0.72f - cam * 0.01f
        gfx.oval(sunX - 6f, 8f, 48f, 48f, 0x55FFF4A0)
        gfx.oval(sunX, 14f, 36f, 36f, 0xFFFFE85A.toInt())
        if (!scenery) return
        layer(viewW, cam, 0.06f, 220f) { i, x ->
            val cy = 24f + (i % 3) * 20f
            gfx.oval(x + 20f, cy, 54f, 18f, 0xFFFFFFFF.toInt())
            gfx.oval(x + 32f, cy - 9f, 28f, 20f, 0xFFFFFFFF.toInt())
        }
        // Giant tomato and lettuce hills far away
        layer(viewW, cam, 0.15f, 250f) { i, x ->
            val top = 128f + (i % 3) * 14f
            if (i % 2 == 0) {
                gfx.oval(x, top, 210f, 160f, 0xFFE8483A.toInt())
                gfx.oval(x + 40f, top + 14f, 50f, 26f, 0x55FFFFFF)
                tomatoTop(gfx, x + 105f, top + 4f, 30f)
            } else {
                for (k in 0 until 4) gfx.oval(x + k * 46f, top + 10f - (k % 2) * 10f, 70f, 120f, if (k % 2 == 0) 0xFF8AD86A.toInt() else 0xFF6AC85A.toInt())
            }
        }
        // Forks, carrots and broccoli trees stuck in the ground
        layer(viewW, cam, 0.3f, 150f) { i, x ->
            val cx = x + 70f
            when (i % 3) {
                0 -> fork(gfx, cx, 96f + (i % 2) * 14f, 214f, 1.2f)
                1 -> carrot(gfx, cx, 120f, 214f, 1.1f)
                else -> broccoli(gfx, cx, 130f, 214f, 1.1f)
            }
        }
        // Big bowls of soup, steaming away
        layer(viewW, cam, 0.42f, 260f) { i, x ->
            val bx = x + 90f
            for (k in 0 until 3) {
                val s = (t * 0.4f + k * 0.33f + i * 0.17f) % 1f
                gfx.oval(bx + 10f + k * 14f + sin(t + k) * 4f, 176f - s * 50f, 12f + s * 8f, 9f + s * 6f, argb((140 * (1f - s)).toInt(), 0xFFFFFF))
            }
            gfx.oval(bx, 180f, 64f, 14f, 0xFFE85A2A.toInt())
            gfx.poly(floatArrayOf(bx, bx + 64f, bx + 52f, bx + 12f), floatArrayOf(187f, 187f, 212f, 212f), 0xFFF4F4FA.toInt())
            gfx.rect(bx + 2f, 190f, 60f, 3f, 0xFF4A8AE8.toInt())
        }
    }
}

/** World 14's map: a little skyline and food (a fork, tomatoes and carrots) between the rows of levels. */
object KingdomMap {
    fun draw(gfx: Gfx, viewW: Float, t: Float) {
        val step = (viewW - 82f - 36f) / 6f
        for (c in 0 until 6) {
            val x = 82f + (c + 0.5f) * step
            val y = if (c % 2 == 0) 112f else 170f
            when (c % 3) {
                0 -> CityArt.miniSkyline(gfx, x - 14f, y, t)
                1 -> FoodArt.tomato(gfx, x, y - 6f, 6f + sin(t * 2f + c) * 0.4f)
                else -> FoodArt.fork(gfx, x, y - 26f, y, 0.42f)
            }
        }
    }
}
