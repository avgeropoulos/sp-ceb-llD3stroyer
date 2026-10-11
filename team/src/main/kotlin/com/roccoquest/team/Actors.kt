package com.roccoquest.team

import com.roccoquest.game.Body
import com.roccoquest.game.Gfx
import com.roccoquest.game.argb
import com.roccoquest.game.rainbow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

const val GRAVITY = 900f
const val MAX_FALL = 420f
const val MAX_FLOWER_LV = 5

/**
 * Flower powers the team can craft. The metal sets the strength: iron is the weakest,
 * then gold, then diamond. Emerald makes the Grass Flower, which gives green armor.
 * Copper and amethyst are found in the later levels.
 */
enum class Power(val label: String, val ore: Ore?, val damage: Int) {
    NONE("", null, 0),
    BOOM("BOOMERANG FLOWER", Ore.IRON, 1),
    FIRE("FIRE FLOWER", Ore.GOLD, 2),
    ICE("ICE FLOWER", Ore.DIAMOND, 3),
    GRASS("GRASS FLOWER", Ore.EMERALD, 1),
    THUNDER("THUNDER FLOWER", Ore.COPPER, 2),
    CRYSTAL("CRYSTAL FLOWER", Ore.AMETHYST, 2),
}

/**
 * The Pokemon partners. Each has a weak normal attack and one huge SUPER move that
 * takes a long time to charge, so it's a boost rather than something to spam.
 */
enum class Mon(val label: String, val color: Int, val superName: String, val blurb: String) {
    SPARKY("SPARKY", 0xFFFFD82A.toInt(), "THUNDERBOLT", "Electric mouse. Fast, high jumps. SUPER zaps every bad guy on screen!"),
    BLAZE("BLAZE", 0xFFFF8A3A.toInt(), "FLAMETHROWER", "Fire dragon. Glides when you hold JUMP. SUPER burns everything in front!"),
    HYDRO("HYDRO", 0xFF4A8AE8.toInt(), "HYDRO CANNON", "Water turtle with cannons. SUPER blasts both ways at once!"),
    BLOOM("BLOOM", 0xFF3CB878.toInt(), "SOLAR BEAM", "Flower dino. Strong vine whip. SUPER fires a giant beam across the screen!"),
    EMBER("EMBER", 0xFFFF9A4A.toInt(), "FIRE SPIN", "Little fire lizard. SUPER spins a ring of fire all around!"),
    SPLASHY("SPLASHY", 0xFF6AB8F0.toInt(), "BUBBLE BEAM", "Tiny turtle. Holds its breath longer underwater. SUPER blasts bubbles!"),
    MYSTIC("MYSTIC", 0xFFFF9AD0.toInt(), "PSYCHIC", "Pink mystery cat. Floats when you hold JUMP. SUPER hits everyone AND heals!"),
    PUNCHO("PUNCHO", 0xFF8A9AB8.toInt(), "DYNAMIC PUNCH", "Super strong. Punches hard up close. SUPER is the biggest hit in the game!"),
    SPOOKY("SPOOKY", 0xFF7A4AA8.toInt(), "NIGHT SHADE", "Floaty ghost. Glides when you hold JUMP. SUPER scares every bad guy on screen!"),
}

/** Pokemon that are happy in the water: the team's air lasts twice as long. */
val Mon.swimmer get() = this == Mon.HYDRO || this == Mon.SPLASHY

/** Pokemon that float down slowly while JUMP is held. */
val Mon.floaty get() = this == Mon.BLAZE || this == Mon.MYSTIC || this == Mon.SPOOKY

enum class Who { ROCCO, BLOCKY, MON }

abstract class Thing(x: Float, y: Float, w: Float, h: Float) : Body(x, y, w, h) {
    var removed = false
    var active = false
    abstract fun update(g: TeamGame, dt: Float)
    abstract fun draw(g: TeamGame, gfx: Gfx, ox: Float)
}

internal fun approach(v: Float, target: Float, step: Float) =
    if (v < target) min(v + step, target) else max(v - step, target)

internal fun signOf(v: Float) = sign(v).toInt()

/** [rainbow] gives a bare RGB color; this adds full opacity. */
fun solidRainbow(t: Float, offset: Float = 0f) = argb(255, rainbow(t, offset))

// ============================================================================ Heroes

class Hero(val who: Who, x: Float, y: Float) :
    Body(x, y, if (who == Who.MON) 14f else 12f, if (who == Who.MON) 18f else 23f) {
    var facing = 1
    var anim = 0f
    /** Counts down while the pickaxe or an attack animation is playing. */
    var swing = 0f
    var cooldown = 0f
    var jumpCooldown = 0f
    var stuck = 0f
    var crouching = false
    /** Butt slam: a short pause in the air, then a fast drop. */
    var slamming = false
    var slamPause = 0f
    var longJumping = false
    var sprinting = false
    /** Which Pokemon this is, when [who] is [Who.MON]. */
    var mon = Mon.SPARKY

    val label get() = when (who) {
        Who.ROCCO -> "ROCCO"
        Who.BLOCKY -> "BLOCKY"
        Who.MON -> mon.label
    }

    val color get() = when (who) {
        Who.ROCCO -> 0xFFE52521.toInt()
        Who.BLOCKY -> 0xFF2AA7A7.toInt()
        Who.MON -> mon.color
    }

    val speed get() = when (who) {
        Who.ROCCO -> 120f
        Who.BLOCKY -> 108f
        Who.MON -> when (mon) { Mon.SPARKY, Mon.EMBER -> 128f; Mon.BLAZE, Mon.MYSTIC, Mon.SPOOKY -> 115f; else -> 100f }
    }

    /** Rocco jumps higher; Blocky is the strong one. */
    val jumpV get() = when (who) {
        Who.ROCCO -> -335f
        Who.BLOCKY -> -305f
        Who.MON -> when (mon) { Mon.SPARKY, Mon.EMBER, Mon.MYSTIC -> -340f; Mon.BLAZE, Mon.PUNCHO -> -310f; else -> -295f }
    }

    fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val flicker = this === g.leader && g.invuln > 0 && g.starT <= 0 && (g.invuln * 20).toInt() % 2 == 0
        if (flicker) return
        val walking = onGround && abs(vx) > 10 && (anim * 10).toInt() % 2 == 0
        val px = x - 2 + ox
        val py = bottom - 24
        if (g.starT > 0) {
            gfx.oval(px - 3, py - 3, w + 10, h + 8, argb(110, rainbow(g.time * 2)))
        }
        when (who) {
            Who.ROCCO -> {
                val frame = if (!onGround) 2 else if (walking) 1 else 0
                when {
                    // Squatting: squash the sprite down.
                    crouching -> gfx.sprite(TeamArt.rocco(g.power, 0), px, bottom - 15, flipX = facing < 0, h = 15f)
                    // Butt slam: tucked in, spinning, with speed lines.
                    slamming -> {
                        gfx.sprite(TeamArt.rocco(g.power, 2), px, py + 4, flipX = (anim * 20).toInt() % 2 == 0, h = 20f)
                        if (slamPause <= 0) for (i in 0 until 3) gfx.rect(px + 2 + i * 5, py - 10, 1.5f, 10f, 0xAAFFFFFF.toInt())
                    }
                    // Long jump: stretched out flat like a superhero.
                    longJumping && !onGround -> {
                        gfx.sprite(TeamArt.rocco(g.power, 2), px - 3, py + 6, flipX = facing < 0, w = 22f, h = 18f)
                        gfx.rect(px - facing * 10 + 4, py + 14, 8f, 1.5f, 0x99FFFFFF.toInt())
                    }
                    else -> gfx.sprite(TeamArt.rocco(g.power, frame), px, py, flipX = facing < 0)
                }
                if (swing > 0) {
                    val hx = cx + facing * 8 + ox
                    gfx.oval(hx - 2, y + 11, 4f, 4f, 0xFFFFC08A.toInt())
                }
            }
            Who.BLOCKY -> {
                gfx.sprite(TeamArt.blocky(g.power, walking || !onGround), px, py, flipX = facing < 0)
                val p = if (swing > 0) 1f - swing / 0.22f else 0f
                val base = if (g.digDown && swing > 0) 0.6f else 0f
                val a = if (swing > 0) -1.9f + p * 2.6f + base else -2.3f
                val hx = cx + facing * 4 + ox
                val angle = if (facing > 0) a else (Math.PI.toFloat() - a)
                TeamArt.pickaxe(gfx, hx, y + 13, angle, g.pickTier)
            }
            Who.MON -> TeamArt.mon(gfx, mon, cx + ox, bottom, g.time + x * 0.01f, facing, swing > 0 || g.superFlash > 0, walking)
        }
        if (g.armor > 0 && who != Who.MON && (g.time * 4).toInt() % 2 == 0) {
            gfx.oval(px - 1, py - 1, 18f, 26f, argb(40, 0x2EB84A))
        }
    }
}
