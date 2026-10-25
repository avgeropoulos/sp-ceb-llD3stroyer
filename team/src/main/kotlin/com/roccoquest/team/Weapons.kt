package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.argb
import kotlin.math.min
import kotlin.math.sin

/**
 * Blocky's bows. Craft one and Blocky gets a SHOOT button. Each is better than the one before:
 * the crossbow shoots fast and straight, the enchanted bow shoots three magic arrows that go
 * through bad guys, and the flame bow's arrows are on fire.
 */
enum class Bow(
    val label: String, val costs: List<Pair<Ore, Int>>, val coins: Int, val damage: Int,
    val speed: Float, val cooldown: Float, val color: Int, val note: String,
) {
    NONE("NO BOW", emptyList(), 0, 0, 0f, 1f, 0, ""),
    BOW("BOW", listOf(Ore.STONE to 4, Ore.IRON to 1), 10, 2, 260f, 0.55f, 0xFF8A5A2A.toInt(), "Shoots arrows far away: 2 damage"),
    CROSSBOW("CROSSBOW", listOf(Ore.IRON to 4, Ore.COPPER to 2), 25, 4, 420f, 0.7f, 0xFF5A5A64.toInt(), "Fast, straight bolts: 4 damage"),
    ENCHANTED("ENCHANTED BOW", listOf(Ore.AMETHYST to 3, Ore.DIAMOND to 2), 40, 3, 340f, 0.6f, 0xFFC890FF.toInt(), "3 magic arrows that go through bad guys!"),
    FLAME("FLAME BOW", listOf(Ore.GOLD to 3, Ore.RUBY to 2), 50, 6, 320f, 0.55f, 0xFFFF6A2A.toInt(), "Arrows on fire: 6 damage!"),
    ;

    /** The name on Blocky's SHOOT button. */
    val short get() = when (this) { ENCHANTED -> "MAGIC"; FLAME -> "FLAME"; CROSSBOW -> "XBOW"; else -> "BOW" }
}

/** An arrow (or bolt) shot by Blocky. */
class HeroArrow(x: Float, y: Float, private val dir: Int, private val bow: Bow, spread: Float = 0f) : Thing(x, y, 12f, 4f) {
    private var t = 0f
    private val hitAlready = HashSet<Foe>()
    init {
        active = true
        vx = dir * bow.speed
        vy = spread
    }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        // Plain arrows drop a little; bolts and magic arrows fly straight.
        if (bow == Bow.BOW) vy = min(vy + GRAVITY * 0.25f * dt, 200f)
        if (g.level.moveX(this, vx * dt)) { removed = true; g.puff(cx, cy); return }
        y += vy * dt
        if (t > 1.6f) removed = true
        for (f in g.foes) {
            if (f.dying || f.removed || !f.overlaps(this) || f in hitAlready) continue
            if (f.hit(g, bow.damage)) {
                hitAlready += f
                if (bow == Bow.FLAME) repeat(4) { g.addParticle(Sparkle(cx, cy, (it - 1.5f) * 40f, -60f, 0xFFFF8A2A.toInt())) }
                // Magic arrows keep going.
                if (bow != Bow.ENCHANTED) { removed = true; return }
            }
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val tip = if (dir > 0) px + 12 else px
        val tail = if (dir > 0) px else px + 12
        if (bow == Bow.ENCHANTED) gfx.oval(px - 3, y - 4, 18f, 12f, argb(90, 0xC890FF))
        if (bow == Bow.FLAME) gfx.oval(tip - 5 + sin(t * 30) * 1.5f, y - 3, 9f, 9f, 0xCCFF8A2A.toInt())
        gfx.rect(px, y + 1.5f, 12f, 1.5f, if (bow == Bow.CROSSBOW) 0xFF4A4A54.toInt() else 0xFF8A5A2A.toInt())
        gfx.poly(floatArrayOf(tip, tip + dir * 3f, tip), floatArrayOf(y, y + 2.2f, y + 4.4f), if (bow == Bow.ENCHANTED) 0xFFE8C8FF.toInt() else 0xFFC8C8D0.toInt())
        gfx.rect(tail - dir * 1f, y, 3f, 1.5f, 0xFFF4F4F4.toInt())
        gfx.rect(tail - dir * 1f, y + 3, 3f, 1.5f, 0xFFF4F4F4.toInt())
    }
}

/**
 * A crazy mushroom for the boss fights. FIRE makes Rocco throw giant bouncing fireballs,
 * ICE makes him throw giant ice balls that freeze bad guys.
 */
class CrazyMushroom(x: Float, y: Float, val fire: Boolean) : Pickup(x, y, false) {
    override fun collect(g: TeamGame) = g.startCrazy(fire)
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) = draw(gfx, x + ox, y, t, fire)

    companion object {
        fun draw(gfx: Gfx, px: Float, y: Float, t: Float, fire: Boolean) {
            val cap = if (fire) 0xFFFF6A00.toInt() else 0xFF3A8AE8.toInt()
            val glow = if (fire) 0xFFE14D else 0xBFF4FF
            gfx.oval(px - 3, y - 3, 18f, 18f, argb((90 + sin(t * 8) * 40).toInt(), glow))
            gfx.rect(px + 3, y + 6, 6f, 6f, 0xFFF4E8D0.toInt())
            gfx.oval(px + 4, y + 7, 1.5f, 2.5f, 0xFF1A1A1A.toInt())
            gfx.oval(px + 7, y + 7, 1.5f, 2.5f, 0xFF1A1A1A.toInt())
            gfx.oval(px - 1, y - 1, 14f, 9f, cap)
            if (fire) {
                gfx.poly(floatArrayOf(px + 2, px + 4, px + 6), floatArrayOf(y + 1, y - 4 + sin(t * 20) * 1.5f, y + 1), 0xFFFFE14D.toInt())
                gfx.poly(floatArrayOf(px + 6, px + 8, px + 10), floatArrayOf(y + 1, y - 5 + sin(t * 17) * 1.5f, y + 1), 0xFFFFE14D.toInt())
            } else {
                gfx.oval(px + 1, y + 1, 3f, 3f, 0xFFFFFFFF.toInt()); gfx.oval(px + 8, y, 3f, 3f, 0xFFFFFFFF.toInt())
                gfx.rect(px + 5.5f, y - 3, 1f, 5f, 0xFFDDF4FF.toInt()); gfx.rect(px + 3.5f, y - 1, 5f, 1f, 0xFFDDF4FF.toInt())
            }
        }
    }
}

/** A giant crazy fireball (bounces!) or ice ball (freezes bad guys) from Rocco. */
class CrazyBall(x: Float, y: Float, private val dir: Int, private val fire: Boolean, vy0: Float) : Thing(x, y, 16f, 16f) {
    private var t = 0f
    private val hitAlready = HashSet<Foe>()
    private var bounces = 0
    init { active = true; vx = dir * (if (fire) 230f else 260f); vy = vy0 }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        vy = min(vy + GRAVITY * (if (fire) 0.9f else 0.35f) * dt, 320f)
        if (g.level.moveX(this, vx * dt)) { if (++bounces > 2) { gone(g); return }; vx = -vx }
        if (g.level.moveY(this, vy * dt) == 1) { vy = if (fire) -240f else -120f; if (!fire && ++bounces > 2) { gone(g); return } }
        if (t > 3f) { removed = true; return }
        for (f in g.foes) {
            if (f.dying || f.removed || !f.overlaps(this) || f in hitAlready) continue
            if (f.hit(g, if (f.boss) 3 else if (fire) 6 else 5)) {
                hitAlready += f
                if (fire) repeat(6) { g.addParticle(Sparkle(cx, cy, (it - 2.5f) * 40f, -80f, 0xFFFF8A2A.toInt())) }
                else { f.frozenT = if (f.boss) 0.8f else 3f; g.iceBurst(f.cx, f.cy) }
            }
        }
    }

    private fun gone(g: TeamGame) { removed = true; g.puff(cx, cy) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        if (fire) {
            gfx.oval(px - 3, y - 3, 22f, 22f, argb(110, 0xFFE14D))
            gfx.oval(px, y, 16f, 16f, 0xFFFF6A00.toInt())
            gfx.oval(px + 3, y + 3, 10f, 10f, 0xFFFFE14D.toInt())
            gfx.oval(px + 5 + sin(t * 25) * 2, y + 4, 4f, 4f, 0xFFFFFFFF.toInt())
        } else {
            gfx.oval(px - 3, y - 3, 22f, 22f, argb(110, 0xBFF4FF))
            gfx.oval(px, y, 16f, 16f, 0xFF7FD8FF.toInt())
            gfx.oval(px + 3, y + 3, 10f, 10f, 0xFFDDF8FF.toInt())
            gfx.rect(px + 7.5f, y + 2, 1f, 12f, 0xFFFFFFFF.toInt()); gfx.rect(px + 2, y + 7.5f, 12f, 1f, 0xFFFFFFFF.toInt())
        }
    }
}

/** A small bow picture for the crafting table, top-left at ([x], [y]). */
fun drawBowIcon(gfx: Gfx, x: Float, y: Float, b: Bow) {
    val wood = if (b == Bow.CROSSBOW) 0xFF5A5A64.toInt() else b.color
    if (b == Bow.CROSSBOW) {
        gfx.rect(x + 2, y + 8, 16f, 3f, 0xFF7A4A20.toInt())
        gfx.poly(floatArrayOf(x + 14, x + 18, x + 14), floatArrayOf(y + 2, y + 9.5f, y + 17), wood)
        gfx.rect(x + 15, y + 2, 1f, 15f, 0xFFDDDDDD.toInt())
        return
    }
    gfx.poly(floatArrayOf(x + 4, x + 12, x + 15, x + 12, x + 4, x + 10, x + 12, x + 10), floatArrayOf(y, y + 4, y + 9.5f, y + 15, y + 19, y + 14, y + 9.5f, y + 5), wood)
    gfx.rect(x + 4, y, 1f, 19f, 0xFFF4F4F4.toInt())
    gfx.rect(x + 3, y + 9, 14f, 1.5f, 0xFF8A5A2A.toInt())
    gfx.poly(floatArrayOf(x + 17, x + 20, x + 17), floatArrayOf(y + 8, y + 9.75f, y + 11.5f), 0xFFC8C8D0.toInt())
    if (b == Bow.ENCHANTED) gfx.oval(x - 2, y - 2, 22f, 23f, argb(70, 0xC890FF))
}
