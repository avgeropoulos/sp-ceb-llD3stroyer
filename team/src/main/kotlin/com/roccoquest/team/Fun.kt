package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Voice
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================ Spotty

/**
 * Spotty, the little mushroom guy with the red-spotted cap. He shows up in random levels, runs
 * up to Rocco, jumps up and down and yells something silly in his squeaky voice.
 */
class Spotty(x: Float, private val groundY: Float, private val line: String) : Thing(x, groundY - 20, 14f, 20f) {
    private var t = 0f
    /** 0 running in, 1 jumping and yelling, 2 running away. */
    private var mode = 0
    private var yellT = 0f
    private var hop = 0f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        t += dt
        when (mode) {
            0 -> {
                x -= 150f * dt
                if (x < g.leader.cx + 46f) {
                    mode = 1
                    g.sfx(Sound.JUMP)
                    g.say(cx, y - 10, line, 3f)
                    g.sayAs(line, Voice.TOAD)
                }
            }
            1 -> {
                yellT += dt
                hop = -abs(sin(yellT * 9f)) * 10f
                if (yellT > 3.2f) mode = 2
            }
            else -> {
                hop = 0f
                x += 200f * dt
                if (x > g.camX + g.viewW + 40) removed = true
            }
        }
        y = groundY - 20 + hop
    }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) = draw(gfx, x + ox, y, t, mode != 1, mode == 2)

    companion object {
        val LINES = listOf(
            "OH ROCCO, YOU'RE CRAZY!!!",
            "WAAAH! ROCCO, YOU'RE NUTS!",
            "ROCCO! YOU SMELL LIKE SPAGHETTI!",
            "OH ROCCO! NICE MUSTACHE!!!",
            "HEY ROCCO! YOU'RE SUPER SILLY!",
            "ROCCO! WHO ATE ALL THE MUSHROOMS?!",
        )

        /** Draws Spotty with his top-left at ([px], [y]). */
        fun draw(gfx: Gfx, px: Float, y: Float, t: Float, running: Boolean, facingRight: Boolean) {
            val leg = if (running && (t * 14).toInt() % 2 == 0) 2f else 0f
            val f = if (facingRight) 1f else -1f
            // Feet and body (a blue vest)
            gfx.rect(px + 2, y + 17, 4f, 3f - leg * 0.5f, 0xFF6A3A1A.toInt())
            gfx.rect(px + 8, y + 17 - leg, 4f, 3f, 0xFF6A3A1A.toInt())
            gfx.oval(px + 1, y + 10, 12f, 9f, 0xFFF4E8D0.toInt())
            gfx.rect(px + 2, y + 11, 10f, 5f, 0xFF2E6BE6.toInt())
            // Face
            gfx.oval(px + 2, y + 5, 10f, 8f, 0xFFF4E8D0.toInt())
            gfx.oval(px + 4.5f + f, y + 7, 1.6f, 2.4f, 0xFF1A1A1A.toInt())
            gfx.oval(px + 7.5f + f, y + 7, 1.6f, 2.4f, 0xFF1A1A1A.toInt())
            gfx.oval(px + 5f + f, y + 10.5f, 4f, 2f, 0xFF8A1A1A.toInt())
            // The big spotted mushroom cap
            gfx.oval(px - 3, y - 2, 20f, 11f, 0xFFFFFFFF.toInt())
            gfx.oval(px + 0, y - 1, 5f, 4f, 0xFFE52521.toInt())
            gfx.oval(px + 9, y - 1, 5f, 4f, 0xFFE52521.toInt())
            gfx.oval(px + 5, y + 3, 4f, 3f, 0xFFE52521.toInt())
            gfx.oval(px - 2, y + 3, 3f, 3f, 0xFFE52521.toInt())
            gfx.oval(px + 13, y + 3, 3f, 3f, 0xFFE52521.toInt())
        }
    }
}

// ============================================================================ Funny scenes

/**
 * Short funny scenes (about 5 seconds) that play every couple of levels: Rocco eating a donut,
 * slipping on a banana, sneezing his hat off... Each has a few cues: something to say and a sound.
 */
object Scenes {
    const val LENGTH = 5.5f

    class Cue(val at: Float, val text: String, val voice: Voice = Voice.HERO, val sound: Sound? = null)

    val titles = listOf(
        "DONUT TIME!", "BANANA PEEL!", "THE BIG SNEEZE", "DANCE PARTY!", "PIE IN THE FACE!",
        "THE GIANT BURP", "NAP TIME", "WHERE'S MY MUSTACHE?!", "TICKLE ATTACK!", "BUBBLE GUM TROUBLE",
        "SPAGHETTI SLURP", "THE HICCUPS", "BIRD ON MY HAT", "SKATEBOARD CRASH", "MAGIC TRICK",
        "ROCCO SINGS", "BALLOON RIDE", "SPOTTY SAYS BOO!", "WHOOPEE CUSHION", "PIZZA PARTY",
    )

    fun cues(kind: Int, monName: String): List<Cue> = when (kind) {
        0 -> listOf(Cue(0.8f, "A donut!", sound = Sound.COIN), Cue(2.0f, "Nom nom nom...", sound = Sound.KICK), Cue(3.6f, "Mmmm... OH YA!", sound = Sound.ONEUP))
        1 -> listOf(Cue(0.6f, "La la la, what a nice day..."), Cue(2.0f, "WHOOOAAA!", sound = Sound.JUMP), Cue(3.8f, "Oof! My bottom!", sound = Sound.BUMP))
        2 -> listOf(Cue(0.6f, "Ah... ah..."), Cue(1.8f, "AAAH..."), Cue(2.8f, "AH-CHOOOOO!", sound = Sound.BREAK), Cue(4.2f, "Hey! Come back, hat!"))
        3 -> listOf(Cue(0.5f, "Let's-a dance!", sound = Sound.ONEUP), Cue(2.5f, "Wiggle wiggle wiggle!"), Cue(4.2f, "Oh yeah! Boogie time!", sound = Sound.COIN))
        4 -> listOf(Cue(0.6f, "Is that... a pie?"), Cue(2.2f, "SPLAT!", sound = Sound.BUMP), Cue(3.6f, "Mamma mia... it's banana cream! Yum!"))
        5 -> listOf(Cue(0.6f, "Glug glug glug...", sound = Sound.SPLASH), Cue(2.4f, "BUUUUURRRP!", sound = Sound.BOSS_HIT), Cue(4.0f, "Ha ha! Excuse me!"))
        6 -> listOf(Cue(0.6f, "So... sleepy..."), Cue(2.2f, "Zzzz... spaghetti... meatballs..."), Cue(4.2f, "POP! Huh? Who's there?!", sound = Sound.KICK))
        7 -> listOf(Cue(0.6f, "La la la..."), Cue(1.8f, "Woof! Woof!", Voice.MON, Sound.BARK), Cue(3.0f, "Hey! My MUSTACHE! Come back, Dennis!"))
        8 -> listOf(Cue(0.6f, "$monName! What are you doing?"), Cue(1.8f, "$monName!!", Voice.MON, Sound.JUMP), Cue(3.0f, "Ha ha ha ha! It tickles!"))
        10 -> listOf(Cue(0.6f, "Spaghetti!", sound = Sound.COIN), Cue(1.8f, "Slurrrrrrp!"), Cue(3.6f, "Mamma mia, delizioso!", sound = Sound.ONEUP))
        11 -> listOf(Cue(0.8f, "Hic!", sound = Sound.JUMP), Cue(1.8f, "Hic!", sound = Sound.JUMP), Cue(2.8f, "Hic!", sound = Sound.JUMP), Cue(3.9f, "Oh no, I can't stop! HIC!", sound = Sound.JUMP))
        12 -> listOf(Cue(0.6f, "Tweet tweet!", Voice.TOAD), Cue(2.2f, "Uh... hello, birdie?"), Cue(3.8f, "An egg? On my hat?!", sound = Sound.POWERUP))
        13 -> listOf(Cue(0.5f, "Wheeee! Look at me!"), Cue(2.4f, "Uh oh... BUSH!", sound = Sound.BUMP), Cue(3.6f, "I'm okay!"))
        14 -> listOf(Cue(0.6f, "Ladies and gentlemen...", Voice.VILLAGER), Cue(2.0f, "Abracadabra!", Voice.VILLAGER, Sound.PORTAL), Cue(3.2f, "$monName!!", Voice.MON, Sound.ONEUP), Cue(4.2f, "Ta-da!", Voice.VILLAGER))
        15 -> listOf(Cue(0.6f, "La la LAAAAA!"), Cue(2.2f, "Aroooooo!", Voice.MON, Sound.BARK), Cue(3.8f, "Everybody's a critic!"))
        16 -> listOf(Cue(0.6f, "So many balloons!"), Cue(2.2f, "Whoa... I'm going up!", sound = Sound.JUMP), Cue(4.0f, "Bye bye! Wahooo!"))
        17 -> listOf(Cue(1.0f, "What's in this box?"), Cue(2.0f, "BOO!", Voice.TOAD, Sound.BOSS_HIT), Cue(2.4f, "WAAAAAH!", sound = Sound.JUMP), Cue(4.0f, "Hee hee! Got you, Rocco!", Voice.TOAD))
        18 -> listOf(Cue(0.6f, "Ah, time to sit down..."), Cue(2.0f, "PFFFFFFT!", Voice.FROG, Sound.BREAK), Cue(3.2f, "It wasn't me!"), Cue(4.3f, "Ha ha ha ha!", Voice.MON))
        19 -> listOf(Cue(0.6f, "What's that in the sky?"), Cue(1.8f, "SPLAT!", sound = Sound.BOSS_HIT), Cue(3.0f, "Om nom nom nom..."), Cue(4.4f, "Best day EVER!", sound = Sound.ONEUP))
        else -> listOf(Cue(0.6f, "Chew chew chew..."), Cue(2.0f, "Look at my big bubble!"), Cue(3.6f, "Uh oh... I'm flying! WAHOOO!", sound = Sound.JUMP), Cue(4.8f, "POP!", sound = Sound.BREAK))
    }

    /** A giant pizza, [left] from 1 (whole) down to 0 (all eaten), centered at [cx], top at [y]. */
    private fun drawPizza(gfx: Gfx, cx: Float, y: Float, left: Float) {
        if (left <= 0f) return
        val w = 120f * left
        gfx.oval(cx - w / 2, y, w, 34f, 0xFFD8A050.toInt())
        gfx.oval(cx - w / 2 + 6, y + 4, w - 12, 26f, 0xFFF0C060.toInt())
        for (k in 0 until (8 * left).toInt()) gfx.oval(cx - w / 2 + 12 + (k * 23f) % (w - 24).coerceAtLeast(1f), y + 8 + (k % 3) * 6f, 9f, 7f, 0xFFC8302A.toInt())
    }

    /** Draws scene [kind] at time [t]. */
    fun draw(g: TeamGame, gfx: Gfx, kind: Int, t: Float, viewW: Float, viewH: Float, mon: Mon) {
        // A sunny meadow stage
        gfx.rect(0f, 0f, viewW, viewH, 0xFF8AD0FF.toInt())
        gfx.oval(viewW - 70, 24f, 34f, 34f, 0xFFFFE87A.toInt())
        for (i in 0 until 3) gfx.oval(((t * 10 + i * 170f) % (viewW + 80f)) - 40f, 30f + i * 16f, 50f, 14f, 0xEEFFFFFF.toInt())
        val ground = 196f
        gfx.rect(0f, ground, viewW, viewH - ground, 0xFF4AA83A.toInt())
        gfx.rect(0f, ground, viewW, 4f, 0xFF6ACB5A.toInt())
        if (kind == 3) {
            // Disco lights for the dance party
            gfx.rect(0f, 0f, viewW, ground, 0x99201040.toInt())
            for (i in 0 until 8) gfx.oval(viewW * ((i * 0.13f + t * 0.3f) % 1f), 40f + (i * 37f) % 120f, 26f, 26f, argb(120, com.roccoquest.game.rainbow(t + i * 0.2f)))
        }
        val cx = viewW / 2
        val s = 3f
        // Rocco, three times bigger than in the levels.
        var rx = cx - 8 * s
        var ry = ground - 24 * s
        var flip = false
        var upside = false
        var frame = 0
        var spin = 0f
        when (kind) {
            1 -> { // banana slip
                rx = cx - 80 + minOf(t, 2f) * 50f - 8 * s
                if (t in 2f..3.6f) { val k = (t - 2f) / 1.6f; ry -= sin(k * 3.1416f) * 60f; upside = k in 0.25f..0.75f; spin = k }
                if (t > 3.6f) ry += 8f
                frame = if (t < 2f) ((t * 8).toInt() % 2) + 0 else 2
            }
            3 -> { // dance
                rx += sin(t * 6) * 18f
                ry -= abs(sin(t * 8)) * 10f
                flip = (t * 2).toInt() % 2 == 0
                frame = 1 + ((t * 6).toInt() % 2)
            }
            9 -> if (t > 3.4f) { ry -= minOf(90f, (t - 3.4f) * 90f); if (t > 4.8f) ry = ground - 24 * s - (5.5f - t) * 40f }
            11 -> { val hic = listOf(0.8f, 1.8f, 2.8f, 3.9f).firstOrNull { t in it..(it + 0.35f) }; if (hic != null) { ry -= sin((t - hic) / 0.35f * 3.1416f) * 30f; frame = 2 } }
            13 -> if (t < 2.4f) { rx = -60f + t * 110f; frame = 2 } else { rx = 204f; upside = t < 3.2f; ry += 6f }
            16 -> if (t > 2.2f) ry -= minOf(200f, (t - 2.2f) * 70f)
            17 -> if (t in 2.0f..3.4f) { ry -= sin((t - 2f) / 1.4f * 3.1416f) * 90f; frame = 2 }
            2 -> if (t in 2.8f..3.4f) rx -= (t - 2.8f) * 30f
        }
        if (kind == 13) {
            // The bush he's about to crash into
            gfx.oval(cx + 20, ground - 40, 70f, 44f, 0xFF2E9A3A.toInt())
            gfx.oval(cx + 34, ground - 50, 44f, 30f, 0xFF3CB84A.toInt())
        }
        // A giant pizza falling from the sky
        if (kind == 19 && t < 1.8f) drawPizza(gfx, cx, -80f + t / 1.8f * (ground - 30f + 80f), 1f)
        if (kind == 6 && t > 1.2f) {
            // Napping on a giant mushroom
            gfx.rect(cx + 36, ground - 30, 20f, 30f, 0xFFF4E8D0.toInt())
            gfx.oval(cx + 16, ground - 52, 60f, 28f, 0xFFE52521.toInt())
            gfx.oval(cx + 26, ground - 48, 10f, 8f, 0xFFFFFFFF.toInt()); gfx.oval(cx + 52, ground - 46, 10f, 8f, 0xFFFFFFFF.toInt())
        }
        val sprite = TeamArt.rocco(Power.NONE, frame)
        if (kind == 6 && t > 1.2f) {
            // Lying down: the sprite drawn sideways isn't possible, so he sits, eyes closed (a sleepy cap over his eyes).
            gfx.sprite(sprite, rx, ry + 10, w = 16 * s, h = 24 * s)
            gfx.rect(rx + 12, ry + 18, 26f, 6f, 0xFFE52521.toInt())
        } else {
            gfx.sprite(sprite, rx, ry, flipX = flip, flipY = upside, w = 16 * s, h = 24 * s)
        }
        val face = ry + 22f
        val mouthX = rx + 30f
        when (kind) {
            0 -> { // donut
                val bites = when { t < 2f -> 0; t < 2.6f -> 1; t < 3.2f -> 2; else -> 3 }
                if (bites < 3) {
                    val dx = rx + 40f
                    val dy = face + 6f
                    gfx.oval(dx, dy, 26f, 22f, 0xFFD8A060.toInt())
                    gfx.oval(dx + 2, dy + 1, 22f, 15f, 0xFFFF8AC8.toInt())
                    for (k in 0 until 6) gfx.rect(dx + 4 + (k * 7f) % 18, dy + 3 + (k * 5f) % 9, 3f, 1.5f, listOf(0xFFFFE14D, 0xFF4AD8FF, 0xFF3CB84A)[k % 3].toInt())
                    gfx.oval(dx + 9, dy + 7, 8f, 6f, 0xFF8AD0FF.toInt())
                    for (b in 0 until bites) gfx.oval(dx - 6 + b * 6f, dy - 4, 12f, 12f, 0xFF8AD0FF.toInt())
                }
                if (t in 2f..3.4f) for (k in 0 until 5) gfx.oval(mouthX + 10 + k * 5f, face + 20 + ((t * 40 + k * 9) % 30), 3f, 3f, 0xFFD8A060.toInt())
                if (t > 3.6f) gfx.shadowText("OH YA!", cx, 60f, 20f, 0xFFFFD21F.toInt(), 1)
            }
            1 -> { // the banana peel
                val bx = cx - 4f
                if (t < 2.2f) {
                    gfx.poly(floatArrayOf(bx, bx + 8, bx + 18, bx + 10), floatArrayOf(ground, ground - 6, ground, ground - 2), 0xFFFFE14D.toInt())
                    gfx.poly(floatArrayOf(bx + 4, bx + 10, bx + 2), floatArrayOf(ground - 1, ground - 8, ground - 4), 0xFFFFD21F.toInt())
                } else gfx.poly(floatArrayOf(bx + 30, bx + 38, bx + 48), floatArrayOf(ground - 20 * (1 - minOf(1f, t - 2.2f)), ground - 26 * (1 - minOf(1f, t - 2.2f)), ground - 20 * (1 - minOf(1f, t - 2.2f))), 0xFFFFE14D.toInt())
                if (t > 3.6f) for (k in 0 until 3) {
                    val a = t * 4 + k * 2.1f
                    gfx.shadowText("*", rx + 24 + cos(a) * 20, ry + 8 + sin(a) * 6, 10f, 0xFFFFE14D.toInt(), 1)
                }
                if (spin > 0f) gfx.shadowText("WHOA!", cx, 50f, 16f, 0xFFFF6A00.toInt(), 1)
            }
            2 -> { // sneeze
                if (t < 2.8f) {
                    val grow = minOf(1f, t / 2.6f)
                    gfx.oval(rx + 26 - grow * 3, face + 4 - grow * 3, 8f + grow * 6, 6f + grow * 6, 0xFFFFB0A0.toInt())
                }
                if (t >= 2.8f) {
                    val k = t - 2.8f
                    gfx.shadowText("AH-CHOOOO!", cx, 54f, 22f, 0xFF3A6AC8.toInt(), 1)
                    for (i in 0 until 10) gfx.oval(mouthX + 10 + k * 160 + i * 9f, face + 8 + sin(i * 1.7f) * 10, 6f, 6f, 0xAADDEEFF.toInt())
                    // The hat flies away!
                    gfx.oval(rx + 10 + k * 110f, ry - 10 - k * 60f + k * k * 30f, 26f, 10f, 0xFFE52521.toInt())
                    gfx.shadowText("R", rx + 23 + k * 110f, ry - 2 - k * 60f + k * k * 30f, 6f, 0xFFFFFFFF.toInt(), 1)
                    // Blocky gets blown over
                    val bx = cx + 90 + k * 60f
                    gfx.sprite(TeamArt.blocky(Power.NONE, false), bx, ground - 26 * 2.5f, flipY = k > 0.6f, w = 16 * 2.5f, h = 26 * 2.5f)
                } else gfx.sprite(TeamArt.blocky(Power.NONE, false), cx + 90, ground - 26 * 2.5f, w = 16 * 2.5f, h = 26 * 2.5f)
            }
            3 -> { // dance partner
                TeamArt.mon(com.roccoquest.team.ScaledGfx(gfx, 2.4f, cx + 90 - sin(t * 6) * 14f, ground - abs(cos(t * 8)) * 12f), mon, 0f, 0f, t, if ((t * 2).toInt() % 2 == 0) -1 else 1, true, true)
                for (i in 0 until 3) gfx.shadowText("~", cx - 90 + i * 30f, 80f + sin(t * 4 + i) * 10, 14f, 0xFFFFE14D.toInt(), 1)
            }
            4 -> { // pie
                val px = if (t < 2.2f) viewW + 40 - (t / 2.2f) * (viewW + 40 - (mouthX + 4)) else mouthX + 4
                if (t < 2.2f) {
                    gfx.oval(px - 18, face + 2, 36f, 14f, 0xFFD8A060.toInt())
                    gfx.oval(px - 16, face - 2, 32f, 10f, 0xFFFFF4E0.toInt())
                } else {
                    // Cream all over his face!
                    gfx.oval(rx + 8, ry + 8, 40f, 36f, 0xFFFFF4E0.toInt())
                    gfx.oval(rx + 16, ry + 16, 8f, 6f, 0xFFFFE14D.toInt())
                    gfx.oval(rx + 30, ry + 24, 7f, 5f, 0xFFFFE14D.toInt())
                    for (i in 0 until 6) gfx.oval(rx + 4 + i * 8f, ry + 44 + ((t - 2.2f) * 30 + i * 5) % 20, 4f, 6f, 0xFFFFF4E0.toInt())
                    if (t < 3.2f) gfx.shadowText("SPLAT!", cx, 56f, 22f, 0xFFFFFFFF.toInt(), 1)
                }
            }
            5 -> { // burp
                if (t < 2.4f) {
                    gfx.rect(mouthX + 6, face - 6, 14f, 30f, 0xFFE84A4A.toInt())
                    gfx.rect(mouthX + 6, face + 2, 14f, 6f, 0xFFFFFFFF.toInt())
                    gfx.rect(mouthX + 12, face - 16, 2f, 12f, 0xFFFFFFFF.toInt())
                } else {
                    val k = minOf(1f, (t - 2.4f) * 2)
                    gfx.shadowText("BUUURRRP!", cx + sin(t * 40) * 3, 60f, 18f + k * 10, 0xFF6ACB5A.toInt(), 1)
                    for (i in 0 until 4) {
                        val bx = 40f + i * 90f + (t - 2.4f) * 80f
                        val by = 100f - (t - 2.4f) * 50f - i * 6
                        gfx.poly(floatArrayOf(bx, bx + 6, bx + 12), floatArrayOf(by, by - 4 + sin(t * 20 + i) * 3, by), 0xFF3A3A44.toInt())
                    }
                }
            }
            6 -> { // snore bubble
                if (t > 1.2f && t < 4.2f) {
                    val r = 6f + ((t - 1.2f) % 1.5f) * 14f
                    gfx.oval(mouthX + 4, face - r, r * 1.3f, r, 0x998AD0FF.toInt())
                    gfx.shadowText("Z", cx + 70 + ((t * 20) % 30), 80f - ((t * 30) % 40), 14f, 0xFFFFFFFF.toInt(), 1)
                    gfx.shadowText("z", cx + 90 + ((t * 25) % 30), 70f - ((t * 25) % 30), 10f, 0xFFFFFFFF.toInt(), 1)
                }
                if (t >= 4.2f) gfx.shadowText("POP!", mouthX + 10, face - 10, 16f, 0xFFFFFFFF.toInt(), 1)
            }
            7 -> { // Dennis steals the mustache
                // Dennis runs up, grabs the mustache, and runs around with it.
                val dx = when {
                    t < 1.8f -> -60f + t * (rx - 20f + 60f) / 1.8f
                    else -> rx - 20f + sin((t - 1.8f) * 2.2f) * 140f
                }
                val stolen = t >= 2.0f
                // No mustache any more: just a bare lip.
                if (stolen) gfx.rect(rx + 24, ry + 18, 21f, 4f, 0xFFFCB88A.toInt())
                Dennis(0f, 14f).draw(g, com.roccoquest.team.ScaledGfx(gfx, 2.4f, dx, ground - 14 * 2.4f), 0f)
                if (stolen) gfx.rect(dx + 40, ground - 26, 18f, 5f, 0xFF3A2410.toInt())
                if (t > 2.2f) gfx.shadowText("HEY!", cx, 56f, 20f, 0xFFE52521.toInt(), 1)
            }
            8 -> { // tickle attack
                val k = minOf(1f, t / 1.8f)
                val mx = viewW + 30 - k * (viewW + 30 - (rx + 50))
                TeamArt.mon(com.roccoquest.team.ScaledGfx(gfx, 2.4f, mx, ground - (if (t > 1.8f) abs(sin(t * 10)) * 10 else 0f)), mon, 0f, 0f, t, -1, t > 1.8f, true)
                if (t > 1.8f) {
                    gfx.shadowText("HA HA HA!", cx, 54f + sin(t * 20) * 3, 18f, 0xFFFF6AA8.toInt(), 1)
                    for (i in 0 until 3) TeamArt.heart(gfx, rx + 10 + i * 18f, ry - 10 - ((t * 30 + i * 12) % 40), 10f, 0xFFFF6A9A.toInt())
                }
            }
            10 -> { // spaghetti
                gfx.oval(rx + 40, face + 34, 50f, 12f, 0xFFF4F4F4.toInt())
                gfx.oval(rx + 44, face + 28, 42f, 14f, 0xFFF0D080.toInt())
                gfx.oval(rx + 56, face + 26, 12f, 8f, 0xFFD8402A.toInt())
                val len = if (t in 1.8f..3.4f) 1f - (t - 1.8f) / 1.6f else if (t < 1.8f) 1f else 0f
                if (len > 0f) gfx.poly(floatArrayOf(mouthX + 4, rx + 62, rx + 64, mouthX + 6), floatArrayOf(face + 16, face + 28 - (1 - len) * 10, face + 28 - (1 - len) * 10, face + 17), 0xFFF0D080.toInt())
                if (t > 3.0f) { gfx.oval(rx + 20, face + 14, 10f, 5f, 0xFFD8402A.toInt()); gfx.oval(rx + 36, face + 15, 8f, 4f, 0xFFD8402A.toInt()) }
            }
            11 -> if (listOf(0.8f, 1.8f, 2.8f, 3.9f).any { t in it..(it + 0.35f) }) gfx.shadowText("HIC!", rx + 60, ry, 14f, 0xFFFFFFFF.toInt(), 1)
            12 -> { // a bird lands on his hat
                val bx = if (t < 1.6f) viewW - t / 1.6f * (viewW - rx - 24) else rx + 24f
                val by = if (t < 1.6f) 30f + t / 1.6f * (ry - 40f) else ry - 10f
                if (t > 2.6f) { gfx.oval(rx + 14, ry - 4, 24f, 8f, 0xFF8A5A2A.toInt()) }
                if (t > 3.8f) gfx.oval(rx + 20, ry - 10, 8f, 9f, 0xFFF4F0E0.toInt())
                gfx.oval(bx - 6, by - 6, 14f, 10f, 0xFF4A8AE8.toInt())
                gfx.oval(bx + 3, by - 9, 7f, 7f, 0xFF4A8AE8.toInt())
                gfx.poly(floatArrayOf(bx + 9, bx + 13, bx + 9), floatArrayOf(by - 7, by - 5.5f, by - 4), 0xFFFFA82A.toInt())
                gfx.poly(floatArrayOf(bx - 4, bx - 12 , bx - 2), floatArrayOf(by - 4, by - 10 + sin(t * 20) * 4, by), 0xFF3A6AC8.toInt())
            }
            13 -> { // skateboard
                val sbx = if (t < 2.4f) rx + 4 else cx + 80f
                gfx.rect(sbx, (if (t < 2.4f) ry + 72f else ground - 6f), 42f, 4f, 0xFFE84A4A.toInt())
                if (t >= 2.4f) for (k in 0 until 4) gfx.oval(cx + 40 + k * 12f, ground - 60 - k * 6f, 6f, 4f, 0xFF3CB84A.toInt())
            }
            14 -> { // magic trick: Blocky pulls the Pokemon out of a hat
                gfx.sprite(TeamArt.blocky(Power.NONE, false), cx + 70, ground - 26 * 2.5f, w = 16 * 2.5f, h = 26 * 2.5f)
                gfx.rect(cx + 120, ground - 30, 30f, 30f, 0xFF1A1A1A.toInt()); gfx.rect(cx + 114, ground - 32, 42f, 5f, 0xFF1A1A1A.toInt())
                if (t > 2.0f && t < 2.8f) for (k in 0 until 8) gfx.oval(cx + 135 + kotlin.math.cos(k * 0.8f) * (t - 2f) * 60, ground - 40 + sin(k * 0.8f) * (t - 2f) * 40, 4f, 4f, solidRainbow(k * 0.1f, 0f))
                if (t > 3.0f) TeamArt.mon(ScaledGfx(gfx, 2.2f, cx + 135, ground - 34 - minOf(1f, t - 3f) * 30f), mon, 0f, 0f, t, -1, true, false)
                if (t > 4.2f) gfx.shadowText("TA-DA!", cx + 135, 60f, 18f, 0xFFFFD21F.toInt(), 1)
            }
            15 -> { // singing
                for (k in 0 until 3) gfx.shadowText("~", mouthX + 20 + k * 14f + sin(t * 5 + k) * 4, face - 10 - ((t * 30 + k * 12) % 40), 16f, solidRainbow(k * 0.3f, 0f), 1)
                if (t > 2.2f) {
                    Dennis(0f, 14f).draw(g, ScaledGfx(gfx, 2.4f, cx + 80, ground - 14 * 2.4f), 0f)
                    gfx.shadowText("AROOO!", cx + 110, ground - 50, 12f, 0xFFFFFFFF.toInt(), 1)
                }
            }
            16 -> { // balloons
                for (k in 0 until 6) {
                    val bx = rx + 6 + k * 8f - 16
                    val by = ry - 50 - (k % 3) * 14f + sin(t * 3 + k) * 2
                    gfx.rect(bx + 6, by + 14, 0.8f, ry + 20 - by - 14, 0xFFDDDDDD.toInt())
                    gfx.oval(bx, by, 13f, 15f, listOf(0xFFE84A4A, 0xFF4A8AE8, 0xFFFFD21F, 0xFF3CB84A, 0xFFFF6AA8, 0xFFAA6AE8)[k].toInt())
                }
            }
            17 -> { // Spotty in a box
                val bx = cx + 60f
                if (t < 2.0f) {
                    gfx.rect(bx, ground - 30, 34f, 30f, 0xFFC8904A.toInt()); gfx.rect(bx - 2, ground - 34, 38f, 6f, 0xFFA87030.toInt())
                    gfx.text("?", bx + 17, ground - 10, 14f, 0xFF7A4A20.toInt(), 1)
                } else {
                    gfx.rect(bx, ground - 30, 34f, 30f, 0xFFC8904A.toInt())
                    Spotty.draw(ScaledGfx(gfx, 2.4f, bx + 1, ground - 70f - minOf(1f, (t - 2f) * 4f) * 10f), 0f, 0f, t, false, false)
                    if (t < 3.0f) gfx.shadowText("BOO!", bx + 17, 50f, 22f, 0xFFE84A4A.toInt(), 1)
                }
            }
            18 -> { // whoopee cushion
                if (t < 2.0f) gfx.oval(rx + 6, ground - 6, 36f, 10f, 0xFFFF6AA8.toInt())
                else {
                    gfx.oval(rx + 10, ground - 3, 28f, 4f, 0xFFFF6AA8.toInt())
                    if (t < 3.2f) {
                        gfx.shadowText("PFFFFFT!", cx, 56f, 22f, 0xFF8AD86A.toInt(), 1)
                        for (k in 0 until 4) gfx.oval(rx - 10 - (t - 2f) * 40 - k * 6, ground - 20 - k * 5f, 10f, 8f, 0x668AD86A)
                    }
                }
            }
            19 -> { // pizza
                if (t in 1.8f..3.0f) drawPizza(gfx, cx, ground - 30f, 1f)
                if (t in 3.0f..4.4f) drawPizza(gfx, cx, ground - 30f, 1f - (t - 3f) / 1.4f)
                if (t > 3.0f) for (k in 0 until 3) gfx.oval(mouthX + k * 8f, face + 20 + ((t * 40 + k * 9) % 20), 3f, 3f, 0xFFF0C060.toInt())
            }
            9 -> { // bubble gum
                val r = when { t < 2f -> 3f; t < 3.4f -> 3f + (t - 2f) * 24f; t < 4.8f -> 36f; else -> 0f }
                if (r > 0f) gfx.oval(mouthX + 8 - r * 0.3f, face + 12 - r, r * 1.6f, r * 1.6f, 0xEEFF9AC8.toInt())
                if (t >= 4.8f) gfx.shadowText("POP!", cx, 60f, 24f, 0xFFFF6AA8.toInt(), 1)
            }
        }
        gfx.rect(0f, 0f, viewW, 22f, 0xAA000000.toInt())
        gfx.shadowText("FUNNY TIME: " + titles[kind], 8f, 15f, 9f, 0xFFFFD21F.toInt())
        gfx.shadowText("tap to skip", viewW - 8f, 15f, 7f, 0xFFDDDDDD.toInt(), 2)
    }
}
