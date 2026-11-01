package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.Sprites
import com.roccoquest.game.argb
import com.roccoquest.game.pixelArt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wonder power-ups, like in Super Mario Bros. Wonder: the ELEPHANT FRUIT, the DRILL HAT and the BUBBLE FLOWER.
 * They pop out of power blocks ([CHAR], sprinkled into the levels by `remix`) or are made on the crafting
 * table's WONDER tab, and they're saved like the flowers (`flower_<name>`, `power`). Only Rocco uses them
 * (Blocky's shirt just changes color, like with the flowers), and a hit knocks the power off (back to the
 * flower he had before) instead of costing a heart, like Mini Rocco.
 */
object PowerUps {
    /** The level character of a power block: a ? block with a Wonder power-up inside. */
    const val CHAR = '¿'
    val ALL = listOf(Power.ELEPHANT, Power.DRILL, Power.BUBBLE)

    fun isWonder(p: Power) = p == Power.ELEPHANT || p == Power.DRILL || p == Power.BUBBLE

    /** Which power-up the power block in column [tx] of level [index] holds: always the same one, and the three take turns. */
    fun at(tx: Int, index: Int): Power = ALL[Math.floorMod(index * 5 + tx, ALL.size)]

    /** Every other level gets a power block (not the very first level: that one teaches the basics). */
    fun hasBlock(index: Int) = index % 2 == 1

    /** What the ACTION button says. */
    fun button(p: Power) = when (p) {
        Power.ELEPHANT -> "TRUNK"
        Power.DRILL -> "DRILL"
        else -> "BUBBLE"
    }

    /** What the crafting table says about it (short: it's one line). */
    fun note(p: Power) = when (p) {
        Power.ELEPHANT -> "Ruby: trunk WHACKS bad guys + smashes blocks"
        Power.DRILL -> "Iron: drill up through blocks, stomp spikes"
        else -> "Sapphire: bubbles pop bad guys. Bounce on them!"
    }

    fun hint(p: Power) = when (p) {
        Power.ELEPHANT -> "ELEPHANT ROCCO! Tap TRUNK to whack bad guys and smash blocks!"
        Power.DRILL -> "DRILL ROCCO! Drill blocks from below, stomp spiky guys, tap DRILL to spin!"
        else -> "BUBBLE ROCCO! Tap BUBBLE to blow bubbles. Bounce on them!"
    }

    fun cheer(p: Power) = when (p) {
        Power.ELEPHANT -> "I'm an elephant! Toot toot!"
        Power.DRILL -> "Drill time! Let's-a dig!"
        else -> "Bubbles! Wahoo!"
    }

    /** How long Elephant Rocco's trunk swing lasts (the picture follows it). */
    const val TRUNK_TIME = 0.28f
    /** Bad guys up to this tough pop in one bubble (bosses only ever take damage). */
    const val POP_HP = 60
    /** Drill Rocco squats this long on a breakable block before he drills down through it. */
    const val DRILL_DOWN_TIME = 0.35f
    /** How long the drill spin lasts, and how far around Rocco it reaches. */
    const val SPIN_TIME = 0.4f
    const val SPIN_REACH = 30f
}

/** A Wonder power-up popping out of a power block. Rocco grabs it and turns into Elephant, Drill or Bubble Rocco. */
class PowerItem(x: Float, y: Float, val power: Power) : Pickup(x, y, false) {
    override fun collect(g: TeamGame) = g.givePowerUp(power)
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) = PowerArt.item(gfx, power, x + ox - 2f, y - 4f, t)
}

/**
 * A bubble that Bubble Rocco blows. It floats forward, slows down and drifts up, and pops any bad guy it
 * touches (they turn into coins; bosses just take a hit). Rocco can bounce off each one once ([TeamGame]).
 */
class HeroBubble(x: Float, y: Float, private val dir: Int, private val damage: Int) : Thing(x, y, 14f, 14f) {
    private var t = 0f
    private val wobble = (x * 0.37f) % 6.28f
    init { active = true; vx = dir * 170f }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        vx = approach(vx, 0f, 150f * dt)
        if (t > 0.35f) vy = approach(vy, -24f, 60f * dt)
        x += vx * dt
        y += vy * dt + sin(t * 5f + wobble) * 0.2f
        // It pops on walls and ceilings, and when it has floated for a while.
        if (t > LIFE || y < 16f || g.level.solidAt(cx + signOf(vx) * 6f, cy)) { pop(g); return }
        for (f in g.foes) {
            if (f.dying || f.removed || !f.active || !f.overlaps(this)) continue
            g.bubbleHit(this, f, damage)
            pop(g)
            return
        }
    }

    fun pop(g: TeamGame) {
        if (removed) return
        removed = true
        for (i in 0 until 6) {
            val a = i * PI.toFloat() / 3f
            g.addParticle(Sparkle(cx, cy, cos(a) * 70f, sin(a) * 70f, if (i % 2 == 0) 0xFFBFF4FF.toInt() else 0xFFFFC8EC.toInt()))
        }
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        // It shivers a little just before it pops.
        val shiver = if (t > LIFE - 0.6f) sin(t * 40f) * 0.8f else 0f
        PowerArt.bubble(gfx, x + ox + shiver, y, w)
    }

    companion object {
        const val LIFE = 4f
    }
}

/** The pictures for the Wonder power-ups: Elephant, Drill and Bubble Rocco, and the items you grab. */
object PowerArt {
    const val BUBBLE_SHIRT = 0xFF5CD6E8.toInt()
    private const val BUBBLE_PANTS = 0xFFF59AD2.toInt()
    private const val RED = 0xFFE52521.toInt()
    private const val BLUE = 0xFF2B4FD8.toInt()
    const val GREY = 0xFF9EA8BE.toInt()
    private const val GREY_DARK = 0xFF6C7690.toInt()

    // ---------------------------------------------------------------- Elephant Rocco
    // A chubby grey elephant in Rocco's red cap and overalls (he keeps his mustache!). 20 wide: the
    // trunk hangs out in front.
    private val elephantPal = mapOf(
        'R' to RED, 'W' to 0xFFFFFFFF.toInt(), 'K' to 0xFF101010.toInt(), 'G' to GREY, 'g' to GREY_DARK,
        'L' to 0xFFC8D0E0.toInt(), 'P' to 0xFFF2A6BE.toInt(), 'B' to BLUE, 'b' to 0xFF1E3AA8.toInt(),
        'Y' to 0xFFFFD21F.toInt(), 'w' to 0xFFF4F0E8.toInt(), 'H' to 0xFF5A2E0C.toInt(),
    )
    private val elephantHead = arrayOf(
        "....................",
        ".......RRRRRR.......",
        ".....RRRRRWWRR......",
        "....RRRRRRWWRRR.....",
        "...gRRRRRRRRRRRRRR..",
        "..gPgGGGGGGGGGGG....",
        ".gPPPgGGGGGGGWWG....",
        ".gPPPgGGGGGGGWKGG...",
        ".gPPPgGLLGGGGGGGGG..",
        ".gPPPgGLLGGGGGGGGGG.",
        "..gPPgGGGGGGGHHHGGG.",
        "..ggggGGGGGGGGg.gGG.",
        "....gGGGGGGGGG..gGG.",
        "...GGBGGGGGGBGG.gGG.",
    )
    private val elephantStand = pixelArt(elephantPal, *elephantHead,
        "..GGGBBBBBBBBGGGgGG.",
        "..GG.BBYBBYBB...GGL.",
        "..GG.BBBBBBBB.......",
        "....BBBBBBBBBB......",
        "....BBBBBBBBBB......",
        "....BBBBbBBBBB......",
        ".....GGGG.GGGG......",
        ".....GGGG.GGGG......",
        "....GGGGG.GGGGG.....",
        "....wGwGw.wGwGw.....",
    )
    private val elephantWalk = pixelArt(elephantPal, *elephantHead,
        "...GGBBBBBBBBGG.gGG.",
        "...GGBBYBBYBB..LGG..",
        "....BBBBBBBBB.......",
        "....BBBBBBBBBB......",
        "....BBBBBBBBBB......",
        "....BBBBbBBBBB......",
        "...GGGG...GGGG......",
        "..GGGG.....GGGG.....",
        ".GGGGG.....GGGGG....",
        ".wGwGw.....wGwGw....",
    )
    /** Jumping: trunk up high (TOOT!) and the legs tucked. */
    private val elephantJump = pixelArt(elephantPal,
        "....................",
        ".......RRRRRR.......",
        ".....RRRRRWWRR....L.",
        "....RRRRRRWWRRR..GG.",
        "...gRRRRRRRRRRRRRGG.",
        "..gPgGGGGGGGGGGG.gG.",
        ".gPPPgGGGGGGGWWG.gG.",
        ".gPPPgGGGGGGGWKGGgG.",
        ".gPPPgGLLGGGGGGGGGG.",
        ".gPPPgGLLGGGGGGGGG..",
        "..gPPgGGGGGGGHHHG...",
        "..ggggGGGGGGGGg.....",
        "....gGGGGGGGGGGGG...",
        "...GGBGGGGGGBGGGG...",
        "....BBBBBBBBB.......",
        "....BBYBBYBBB.......",
        "....BBBBBBBBBB......",
        "....BBBBBBBBBB......",
        "....BBBBbBBBBBGGG...",
        "...GGGGG..GGGGGGG...",
        "..GGGGGG...GGGGw....",
        "..wGwGw....wGw......",
        "....................",
        "....................",
    )
    private val elephant = listOf(elephantStand, elephantWalk, elephantJump)

    // ---------------------------------------------------------------- Drill Rocco
    // A shiny drill on top of Rocco's cap, with a gold ring. The spiral stripes shift each [phase], so
    // flipping through the pictures makes it spin (a few fixed pictures, so the 3D app models each just once).
    private const val DRILL_LIGHT = 0xFFF4F8FF.toInt()
    private const val DRILL_MID = 0xFFB8C4D4.toInt()
    private const val DRILL_DARK = 0xFF7A889E.toInt()
    private const val DRILL_GROOVE = 0xFF4A5468.toInt()
    private const val RING = 0xFFFFD21F.toInt()
    private const val RING_DARK = 0xFFC89A10.toInt()
    /** Each row of the drill cone: first column, width (centred on Rocco's cap). */
    private val CONE = intArrayOf(8, 2, 8, 2, 7, 4, 7, 4, 6, 6, 6, 6, 5, 8, 5, 8)
    /** How much taller the drill makes him: the cone plus its ring. */
    const val DRILL_ROWS = 9
    const val PHASES = 4

    /** Paints the drill cone (rows 0-7) and its ring (row 8) into [px], a picture [w] wide. */
    private fun paintDrill(px: IntArray, w: Int, phase: Int, ringX0: Int, ringX1: Int) {
        for (y in 0 until 8) {
            val x0 = CONE[y * 2]
            val n = CONE[y * 2 + 1]
            for (i in 0 until n) {
                val x = x0 + i
                val f = i / maxOf(1f, n - 1f)
                var c = if (f < 0.34f) DRILL_LIGHT else if (f < 0.75f) DRILL_MID else DRILL_DARK
                if (n >= 4 && (x + y + phase) % 4 == 0) c = if (c == DRILL_LIGHT) DRILL_MID else DRILL_GROOVE
                px[y * w + x] = c
            }
        }
        for (x in ringX0..ringX1) px[8 * w + x] = if ((x - ringX0) % 3 == 1) RING_DARK else RING
    }

    private fun withDrill(body: Sprite, phase: Int): Sprite {
        val w = body.w
        val px = IntArray(w * (body.h + DRILL_ROWS))
        paintDrill(px, w, phase, 5, 12)
        System.arraycopy(body.pixels, 0, px, DRILL_ROWS * w, body.pixels.size)
        return Sprite(w, body.h + DRILL_ROWS, px)
    }

    /** [frame] (stand, walk, jump) x [PHASES] spin pictures, made once. */
    private val drill = listOf(Sprites.bigStand, Sprites.bigWalk, Sprites.bigJump).map { b -> List(PHASES) { withDrill(b, it) } }

    /** Drill Rocco with his drill turned to [phase] (it spins as the phase goes round). */
    fun drillRocco(frame: Int, phase: Int): Sprite = drill[frame][Math.floorMod(phase, PHASES)]

    // ---------------------------------------------------------------- Bubble Rocco
    private val bubbleMap = mapOf(RED to BUBBLE_SHIRT, BLUE to BUBBLE_PANTS)
    private val bubbleSuit = listOf(Sprites.bigStand, Sprites.bigWalk, Sprites.bigJump).map { it.recolor(bubbleMap) }

    /** Rocco's picture with a Wonder power-up (no outline yet). The drill is shown standing still. */
    fun rocco(p: Power, frame: Int): Sprite = when (p) {
        Power.ELEPHANT -> elephant[frame]
        Power.DRILL -> drill[frame][0]
        else -> bubbleSuit[frame]
    }

    /**
     * Draws one of Rocco's pictures so that its usual 16x24 body fills (x, y, w, h). The wider elephant
     * spills out evenly on both sides, and the drill sticks up over his head (or down, upside down).
     */
    fun box(gfx: Gfx, s: Sprite, x: Float, y: Float, w: Float, h: Float, flipX: Boolean = false, flipY: Boolean = false) {
        val ew = s.w * w / 16f
        val eh = s.h * h / 24f
        gfx.sprite(s, x - (ew - w) / 2f, if (flipY) y else y - (eh - h), flipX, flipY, ew, eh)
    }

    // ---------------------------------------------------------------- The items
    private val itemPal = mapOf(
        'O' to 0xFFFF8A2A.toInt(), 'o' to 0xFFD8601A.toInt(), 'Y' to 0xFFFFD86A.toInt(), 'G' to 0xFF2EB84A.toInt(),
        'L' to 0xFF8EE07A.toInt(), 'N' to 0xFF7A4A20.toInt(), 'W' to 0xFFFFFFFF.toInt(), 'K' to 0xFF101010.toInt(),
        'R' to RED, 'r' to 0xFFB01A1A.toInt(), 'a' to 0xFF6FD8F4.toInt(), 'A' to 0xFF2EA8D8.toInt(),
        'P' to 0xFFFF9AD0.toInt(), 'w' to 0xFFFFF0F8.toInt(),
    )
    /** The Elephant Fruit: a round orange fruit whose two leaves flop like elephant ears. */
    val fruit = pixelArt(itemPal,
        "................",
        "...GGG....GGG...",
        "..GLLGG..GGLLG..",
        "..GLLLGNNGLLLG..",
        "...GGGGNNGGGG...",
        ".......NN.......",
        ".....OOOOOO.....",
        "...OOOOOOOOOO...",
        "..OOYYOOOOOOOO..",
        "..OYYOOOOOOOOo..",
        ".OOYOOOOOOOOOoo.",
        ".OOOOOOOOOOOOoo.",
        ".OOOOOOOOOOOooo.",
        "..OOOOOOOOOooo..",
        "...oOOOOOOooo...",
        ".....oooooo.....",
    )
    /** The Bubble Flower: bubble-blue petals with a pink face. */
    val bubbleFlower = pixelArt(itemPal,
        "......aaaa......",
        "....aaWWaaaa....",
        "...aWWaPPPaaa...",
        "..aWaPPwwPPaAa..",
        "..aaPwwKwKwPAa..",
        "..aaPwwwwwwPAa..",
        "...aaPPwwPPAA...",
        "....aaaPPaAA....",
        "......AAAA......",
        ".......GG.......",
        "..GGG..GG..GGG..",
        ".GLLGG.GG.GGLLG.",
        "..GLLGGGGGGLLG..",
        "...GGGGGGGGGG...",
        ".......GG.......",
        ".......GG.......",
    )
    private val hatCap = pixelArt(itemPal,
        "...RRRRRRRRRR...",
        "..RRRRRRWWRRRR..",
        "..RRRRRRWWRRRRR.",
        ".rrrrrrrrrrrrrrr",
    )
    /** The Drill Hat: Rocco's red cap with the drill on top (it spins). 16x16, the hat sits on the bottom. */
    private val drillHat = List(PHASES) { phase ->
        val w = 16
        val px = IntArray(w * 16)
        val top = IntArray(w * DRILL_ROWS)
        paintDrill(top, w, phase, 3, 12)
        // Three empty rows on top, then the drill, its ring and the cap.
        System.arraycopy(top, 0, px, 3 * w, top.size)
        System.arraycopy(hatCap.pixels, 0, px, (3 + DRILL_ROWS) * w, hatCap.pixels.size)
        Sprite(w, 16, px)
    }

    /** The crafting table's (and the HUD's) picture of a power-up. */
    fun icon(p: Power): Sprite = when (p) {
        Power.ELEPHANT -> fruit
        Power.DRILL -> drillHat[0]
        else -> bubbleFlower
    }

    /** A power-up item, 16x16 with its top-left at (x, y). */
    fun item(gfx: Gfx, p: Power, x: Float, y: Float, t: Float) {
        // A soft glow behind, so it stands out like a prize.
        gfx.oval(x - 2f, y - 2f, 20f, 20f, argb(70 + (40 * sin(t * 5f)).toInt(), 0xFFF6B0))
        when (p) {
            Power.DRILL -> gfx.sprite(drillHat[(t * 12f).toInt() % PHASES], x, y)
            Power.BUBBLE -> {
                gfx.sprite(bubbleFlower, x, y)
                // Little bubbles float up out of it (growing a pixel at a time).
                for (i in 0 until 2) {
                    val k = (t * 0.7f + i * 0.5f) % 1f
                    val s = 3f + (4f * k).toInt()
                    bubble(gfx, x + 3f + i * 8f + sin(t * 3f + i) * 2f, y - 2f - k * 10f, s)
                }
            }
            else -> gfx.sprite(fruit, x, y + abs(sin(t * 4f)) * -1.5f)
        }
    }

    // ---------------------------------------------------------------- Effects

    /**
     * A shiny soap bubble [s] across, top-left at (x, y): see-through in the middle, with a pink and blue rim and
     * a bright shine. (The rim keeps its shape, so Team Up! 3D makes each size of it just once.)
     */
    fun bubble(gfx: Gfx, x: Float, y: Float, s: Float) {
        val r = s / 2f
        val cx = x + r
        val cy = y + r
        gfx.oval(x, y, s, s, 0x40DFF8FF)
        rim(gfx, cx, cy, r, s * 0.14f, 0.2f, 2.7f, argb(180, 0xFF9AE0))
        rim(gfx, cx, cy, r, s * 0.1f, 3.4f, 5.7f, argb(180, 0x8AE8FF))
        gfx.oval(x + s * 0.2f, y + s * 0.16f, s * 0.3f, s * 0.22f, 0xEEFFFFFF.toInt())
        gfx.oval(x + s * 0.64f, y + s * 0.62f, s * 0.13f, s * 0.13f, 0xAAFFFFFF.toInt())
    }

    /** A curved band along the inside of a circle, from angle [a0] to [a1] (radians, clockwise from the right). */
    private fun rim(gfx: Gfx, cx: Float, cy: Float, r: Float, thick: Float, a0: Float, a1: Float, color: Int) {
        val n = 6
        val xs = FloatArray(n * 2 + 2)
        val ys = FloatArray(n * 2 + 2)
        for (i in 0..n) {
            val a = a0 + (a1 - a0) * i / n
            // The band is thickest in its middle and thin at both ends, like light on a bubble.
            val k = thick * sin(PI.toFloat() * i / n)
            xs[i] = cx + cos(a) * r; ys[i] = cy + sin(a) * r
            xs[n * 2 + 1 - i] = cx + cos(a) * (r - k); ys[n * 2 + 1 - i] = cy + sin(a) * (r - k)
        }
        gfx.poly(xs, ys, color)
    }

    /**
     * Elephant Rocco's trunk swinging out in front of him: [k] goes from 0 to 1 through the swing.
     * ([y] is the top of his picture, [cx] his middle.)
     */
    fun trunk(gfx: Gfx, cx: Float, y: Float, facing: Int, k: Float) {
        val f = facing.toFloat()
        val reach = 4f + 18f * sin(k * PI.toFloat())
        val drop = -5f + 12f * k
        for (i in 0..4) {
            val a = i / 4f
            val r = 3.4f - 1.2f * a
            gfx.oval(cx + f * (6f + reach * a) - r, y + 10f + drop * a * a - r, r * 2f, r * 2f, if (i == 4) 0xFFC8D0E0.toInt() else GREY)
        }
        // A whoosh behind the swing.
        val wx = cx + f * (6f + reach * 0.9f)
        gfx.oval(wx - 4f, y + 2f, 8f, 18f, 0x66FFFFFF)
    }

    /** Drill Rocco's drill spin: a whirl around his middle with sparks flying round. */
    fun spin(gfx: Gfx, cx: Float, cy: Float, t: Float) {
        gfx.oval(cx - 17f, cy - 7f, 34f, 14f, 0x55E8F0FF)
        for (i in 0 until 4) {
            val a = t * 28f + i * PI.toFloat() / 2f
            val sx = cx + cos(a) * 16f
            val sy = cy + sin(a) * 6f
            gfx.rect(sx - 1.5f, sy - 1.5f, 3f, 3f, if (i % 2 == 0) 0xFFFFFFFF.toInt() else RING)
        }
    }
}
