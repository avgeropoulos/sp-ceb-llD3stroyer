package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.argb
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What Rocco can do after Cappy captures a bad guy. He looks like that bad guy (with his cap on) and
 * gets its power until the time runs out, he gets hit, or he taps SWAP.
 */
enum class CaptureKind(val label: String, val speed: Float, val jump: Float, val how: String) {
    GRUMBLER("GRUMBLER", 1.5f, 1f, "Super fast running!"),
    SHELLBACK("SHELLBACK", 1.1f, 1f, "THROW: shell dash through bad guys!"),
    FLAPPER("FLAPPER", 1.1f, 1f, "Hold JUMP to fly!"),
    BOMB("BOMB-BOMB", 1f, 1f, "THROW: KA-BOOM!"),
    HOPPER("HOPPER", 1f, 1.6f, "Mega jumps!"),
    BONEHEAD("BONEHEAD", 1f, 1f, "Bones can't be hurt!"),
    GHOST("PEEKABOO", 1.1f, 1f, "Hold JUMP to float!"),
    ENDERMAN("ENDERMAN", 1.1f, 1.1f, "THROW: teleport ahead!"),
    WITCH("WITCH", 1f, 1f, "THROW: potions!"),
    FISH("FISH", 1.3f, 1f, "Swim super fast!"),
    OTHER("BAD GUY", 1.2f, 1.1f, "A bit faster!"),
    // Dino World: every dinosaur can be captured!
    RAPTOR("RAPTOR", 1.9f, 1.25f, "Super fast raptor run!"),
    TRICERA("TRICERATOPS", 1.2f, 1f, "THROW: horn CHARGE!"),
    PTERO("PTERODACTYL", 1.2f, 1.1f, "Hold JUMP to fly!"),
    STEGO("STEGOSAURUS", 1f, 1f, "Spiky back: bad guys can't hurt you!"),
    ANKYLO("ANKYLOSAURUS", 1f, 1f, "THROW: tail club SMASH!"),
    REX("BABY REX", 1.15f, 1.1f, "THROW: a big ROAR!"),
    /** A fish out of water (Wonder King Krag's magic): slow, and it can't stop flopping! */
    FLOPPY("FLOPPY FISH", 0.6f, 0.75f, "Flop flop flop! You can still stomp him!"),
    ;

    companion object {
        /** Which power a bad guy gives (bosses and flying bits can't be captured). */
        fun of(f: Foe): CaptureKind? = when {
            f.boss || f is Projectile -> null
            f is Grumbler -> GRUMBLER
            f is Shellback -> SHELLBACK
            f is Flapper -> FLAPPER
            f is BombBomb -> BOMB
            f is Hopper -> HOPPER
            f is Bonehead -> BONEHEAD
            f is Peekaboo -> GHOST
            f is Enderman -> ENDERMAN
            f is Witch -> WITCH
            f is Fish -> FISH
            f is Raptor -> RAPTOR
            f is Triceratops -> TRICERA
            f is Pterodactyl -> PTERO
            f is Stegosaurus -> STEGO
            f is Ankylosaurus -> ANKYLO
            f is BabyRex -> REX
            f.maxHp > 20 -> null
            else -> OTHER
        }
    }
}

/** Cappy, Rocco's hat: thrown like a boomerang. It captures the first bad guy it touches. */
class CappyHat(x: Float, y: Float, private val dir: Int, private val thrower: Hero) : Thing(x, y, 12f, 8f) {
    private var t = 0f
    private var back = false
    private val bonked = HashSet<Foe>()
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        if (!back) {
            x += dir * 300f * dt * (1f - t / 0.45f).coerceAtLeast(0.15f)
            if (t > 0.45f) back = true
        } else {
            val dx = thrower.cx - cx
            val dy = thrower.cy - cy
            val d = hypot(dx, dy).coerceAtLeast(1f)
            x += dx / d * 320f * dt
            y += dy / d * 320f * dt
            if (d < 10f || t > 3f) { removed = true; return }
        }
        for (f in g.foes) {
            if (f.dying || f.removed || !f.active || !f.overlaps(this)) continue
            if (CaptureKind.of(f) != null) { g.capture(f); removed = true; return }
            // Bosses (and things you can't capture) just get bonked, once per throw.
            if (f in bonked) continue
            bonked += f
            f.hit(g, 2)
            back = true
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) = drawCap(gfx, x + ox, y, t, true)

    companion object {
        /** Rocco's red cap, with Cappy's eyes when [eyes]. */
        fun drawCap(gfx: Gfx, px: Float, y: Float, t: Float, eyes: Boolean) {
            val spin = sin(t * 30) * 2f
            gfx.oval(px - 1, y + 4, 14f, 4f, 0xFFB01A1A.toInt())
            gfx.oval(px + 1 + spin * 0.3f, y, 10f, 7f, 0xFFE52521.toInt())
            gfx.oval(px + 4, y + 1, 4f, 3f, 0xFFFFFFFF.toInt())
            if (eyes) {
                gfx.oval(px + 2, y + 2, 3f, 3.5f, 0xFFFFFFFF.toInt()); gfx.oval(px + 7, y + 2, 3f, 3.5f, 0xFFFFFFFF.toInt())
                gfx.oval(px + 3, y + 3, 1.5f, 2f, 0xFF1A1A1A.toInt()); gfx.oval(px + 8, y + 3, 1.5f, 2f, 0xFF1A1A1A.toInt())
            }
        }

        /** A little picture of Cappy for the crafting table. */
        val icon: Sprite = run {
            val r = 0xFFE52521.toInt(); val d = 0xFFB01A1A.toInt(); val w = 0xFFFFFFFF.toInt(); val k = 0xFF1A1A1A.toInt()
            val rows = listOf(
                "................", "................", ".....rrrrrr.....", "...rrrrrrrrrr...", "..rrwwrrrrwwrr..",
                "..rrwkrrrrwkrr..", ".rrrwkrrrrwkrrr.", ".rrrrrrrrrrrrrr.", "dddddddddddddddd", ".dddddddddddddd.",
                "................", "................", "................", "................", "................", "................",
            )
            Sprite(16, 16, IntArray(256) { i -> when (rows[i / 16][i % 16]) { 'r' -> r; 'd' -> d; 'w' -> w; 'k' -> k; else -> 0 } })
        }
    }
}

/** The Mega Mushroom: Rocco grows GIANT for a while and stomps through everything. */
class MegaItem(x: Float, y: Float) : Pickup(x, y, false) {
    override fun collect(g: TeamGame) = g.startMega()
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val grow = 1f + sin(t * 6) * 0.08f
        gfx.oval(px - 2, y - 2, 16f * grow, 16f * grow, argb(80, 0xFFFFFF))
        gfx.rect(px + 3, y + 6, 6f, 6f, 0xFFF4E8D0.toInt())
        gfx.oval(px - 2, y - 2, 16f, 11f, 0xFFF08A2A.toInt())
        gfx.oval(px + 1, y, 4f, 4f, 0xFFFFFFFF.toInt()); gfx.oval(px + 8, y - 1, 4f, 4f, 0xFFFFFFFF.toInt())
        gfx.text("M", px + 6, y + 6, 5f, 0xFFFFFFFF.toInt(), 1)
    }
}

/** The Propeller Mushroom: press JUMP in the air to spin way up, hold JUMP to float down. */
class PropellerItem(x: Float, y: Float) : Pickup(x, y, true) {
    override fun collect(g: TeamGame) = g.startPropeller()
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        gfx.rect(px + 3, y + 6, 6f, 6f, 0xFFF4E8D0.toInt())
        gfx.oval(px - 1, y, 14f, 9f, 0xFF3A6AE8.toInt())
        drawPropeller(gfx, px + 6, y, t)
    }

    companion object {
        fun drawPropeller(gfx: Gfx, cx: Float, top: Float, t: Float) {
            gfx.rect(cx - 0.75f, top - 4, 1.5f, 4f, 0xFF5A5A64.toInt())
            val w = 12f * kotlin.math.abs(sin(t * 25))
            gfx.oval(cx - w / 2 - 1, top - 6, w + 2f, 3f, 0xFFFFD21F.toInt())
        }
    }
}

/** A potion thrown by Rocco when he's a captured witch: it splashes bad guys. */
class HeroPotion(x: Float, y: Float, tx: Float) : Thing(x, y, 10f, 10f) {
    private var t = 0f
    init { active = true; vx = ((tx - x) / 0.8f).coerceIn(-200f, 200f); vy = -220f }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        vy = kotlin.math.min(vy + GRAVITY * 0.5f * dt, 300f)
        x += vx * dt
        y += vy * dt
        val hitFoe = g.foes.firstOrNull { !it.dying && it.active && it.overlaps(this) }
        if (hitFoe != null || g.level.solidAt(cx, y + h) || t > 3f) {
            removed = true
            g.sfx(com.roccoquest.game.Sound.SPLASH)
            repeat(8) { g.addParticle(Sparkle(cx, cy, (it - 3.5f) * 30f, -60f, 0xFF8A3AE8.toInt())) }
            for (f in g.foes) if (!f.dying && f.active && hypot(f.cx - cx, f.cy - cy) < 40f) f.hit(g, if (f.boss) 3 else 6)
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        gfx.oval(x + ox, y + 2, 10f, 9f, 0xFF8A3AE8.toInt())
        gfx.rect(x + ox + 3.5f, y - 1, 3f, 4f, 0xFFC8D8E8.toInt())
    }
}

/** The BABY MACHINE's look: everyone smaller, with a bonnet and a pacifier. */
object Baby {
    const val SCALE = 0.7f

    /** Draws through this to shrink something standing at ([cx], [bottom]) down to baby size. */
    fun shrink(gfx: Gfx, cx: Float, bottom: Float): Gfx = ScaledGfx(gfx, SCALE, cx * (1 - SCALE), bottom * (1 - SCALE))

    fun dress(gfx: Gfx, cx: Float, bottom: Float, facing: Int, mon: Boolean) {
        val f = facing.toFloat()
        if (!mon) {
            // A frilly bonnet
            gfx.oval(cx - 7f, bottom - 20f, 14f, 8f, 0xFFBFE4FF.toInt())
            gfx.rect(cx - 7f, bottom - 16f, 14f, 2f, 0xFFFFFFFF.toInt())
        }
        val py = if (mon) bottom - 9f else bottom - 10.5f
        val px = cx + f * (if (mon) 6f else 4f)
        gfx.oval(px - 2.5f, py - 2.5f, 5f, 5f, 0xFFFF8AC0.toInt())
        gfx.oval(px - 1.2f, py - 1.2f, 2.4f, 2.4f, 0xFFFFF0F6.toInt())
        gfx.oval(px + f * 2f - 1f, py - 1f, 2f, 2f, 0xFFFF5A9A.toInt())
    }
}
