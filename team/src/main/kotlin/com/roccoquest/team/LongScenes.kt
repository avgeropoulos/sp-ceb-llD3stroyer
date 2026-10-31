package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Voice
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The longer funny scenes (10-13 seconds, with a little story). Scene numbers start at [FIRST]. */
object LongScenes {
    const val FIRST = 20
    val titles = listOf("THE BIRTHDAY CAKE", "KRAG'S BAD HAIR DAY", "DANCE-OFF!", "THE STINKY CHEESE", "ROCCO'S DREAM", "THE WRONG PIPE")

    // (Long enough for the last line to be said before the scene ends.)
    private val lengths = floatArrayOf(13.2f, 12.3f, 13.2f, 10.5f, 13.3f, 12.1f)

    fun length(kind: Int) = lengths.getOrElse(kind - FIRST) { 10.5f }

    private fun cue(at: Float, text: String, voice: Voice = Voice.HERO, sound: Sound? = null) = Scenes.Cue(at, text, voice, sound)
    /** Just a sound (a SPLASH, a POP...) with nobody talking, so it doesn't cut off whoever is speaking. */
    private fun sfx(at: Float, sound: Sound) = Scenes.Cue(at, "", Voice.HERO, sound)

    /*
     * The voices speak at a natural speed, and a new line cuts off the one before it, so each line is short
     * enough to be finished before the next one starts (and the last one before the scene ends): about 14
     * letters a second, a bit slower for King Krag's deep voice (SpeechText.seconds, checked by
     * LongScenesTimingTest). Things nobody should read out ("*yawn*", "Zzzz", Dennis's howl, the SPLOOSH)
     * are written on the screen, with a sound.
     */
    fun cues(kind: Int, monName: String): List<Scenes.Cue> = when (kind - FIRST) {
        0 -> listOf( // THE BIRTHDAY CAKE
            cue(0.4f, "Happy birthday, Blocky!"),
            cue(2.5f, "Wow! A giant cake!", Voice.VILLAGER),
            cue(4.25f, "I got it... whoa..."),
            cue(6.4f, "WHOOOOAAA!", sound = Sound.JUMP),
            cue(7.5f, "SPLAT!", sound = Sound.BOSS_HIT),
            cue(8.4f, "...Happy birthday?"),
            cue(10.3f, "$monName!", Voice.MON, Sound.COIN),
            cue(11.45f, "Mmm, frosting!", Voice.VILLAGER),
        )
        1 -> listOf( // KRAG'S BAD HAIR DAY
            cue(0.4f, "ROOOAAAR!", Voice.GOD, Sound.BOSS_FIRE),
            cue(1.75f, "Ah... AAAH...", Voice.GOD),
            cue(3.2f, "AH-CHOOOO!", Voice.GOD, Sound.BOSS_FIRE),
            cue(4.3f, "My HAIR! It's on FIRE!", Voice.GOD),
            cue(6.4f, "Here, King Krag!"),
            sfx(6.9f, Sound.SPLASH),
            cue(7.95f, "...Thank you, Rocco.", Voice.GOD),
            cue(9.9f, "Ha ha! Nice new hairdo!"),
        )
        2 -> listOf( // DANCE-OFF!
            cue(0.3f, "DANCE-OFF! Rocco versus Blocky!", Voice.VILLAGER),
            cue(3.0f, "Watch THIS! Wahoo!", sound = Sound.ONEUP),
            cue(5.6f, "My turn! The block dance!", Voice.VILLAGER, Sound.KICK),
            cue(8.8f, "And the winner is...", Voice.TOAD),
            cue(10.4f, "$monName!!", Voice.MON, Sound.POWERUP),
            cue(11.55f, "Hey! No fair!"),
        )
        3 -> listOf( // THE STINKY CHEESE
            cue(0.3f, "Mmm, a little snack..."),
            cue(2.3f, "PEE-YEW!", Voice.VILLAGER, Sound.BUMP),
            sfx(3.5f, Sound.BARK),
            cue(5.0f, "$monName!", Voice.MON),
            cue(6.15f, "It's not THAT bad..."),
            cue(8.0f, "BLEEEEH!", sound = Sound.HURT),
            cue(9.2f, "Ha ha ha!", Voice.TOAD),
        )
        4 -> listOf( // ROCCO'S DREAM
            cue(0.3f, "Good night..."),
            cue(2.0f, "Mmm... flying pizza..."),
            cue(4.5f, "Riding a dino... wheeee!", sound = Sound.JUMP),
            cue(6.85f, "King Krag! That's MY pizza!"),
            sfx(9.2f, Sound.BREAK),
            cue(9.35f, "Huh? A dream?"),
            sfx(10.4f, Sound.CHOMP),
            cue(10.7f, "$monName ate my pizza!!"),
        )
        else -> listOf( // THE WRONG PIPE
            cue(0.3f, "Let's-a go down the pipe!"),
            cue(2.5f, "Wahoooo!", sound = Sound.JUMP),
            sfx(4.3f, Sound.SPLASH),
            cue(5.0f, "Uh... this isn't the castle."),
            cue(7.45f, "HEY! I'm taking a BATH!", Voice.VILLAGER, Sound.BUMP),
            cue(9.55f, "Sorry, Blocky!"),
            cue(10.95f, "Wahoo!", sound = Sound.JUMP),
        )
    }

    private const val S = 3f
    private fun rocco(gfx: Gfx, x: Float, ground: Float, frame: Int = 0, flip: Boolean = false, upside: Boolean = false, lift: Float = 0f) =
        gfx.sprite(TeamArt.rocco(Power.NONE, frame), x - 8 * S, ground - 24 * S - lift, flipX = flip, flipY = upside, w = 16 * S, h = 24 * S)
    private fun blocky(gfx: Gfx, x: Float, ground: Float, walking: Boolean = false, flip: Boolean = false, upside: Boolean = false, lift: Float = 0f) =
        gfx.sprite(TeamArt.blocky(Power.NONE, walking), x - 8 * 2.6f, ground - 24 * 2.6f - lift, flipX = flip, flipY = upside, w = 16 * 2.6f, h = 24 * 2.6f)
    private fun mon(gfx: Gfx, m: Mon, x: Float, ground: Float, t: Float, facing: Int, happy: Boolean = false) =
        TeamArt.mon(ScaledGfx(gfx, 2.4f, x, ground), m, 0f, 0f, t, facing, happy, happy)

    fun draw(g: TeamGame, gfx: Gfx, kind: Int, t: Float, viewW: Float, viewH: Float, mon: Mon) {
        val ground = 196f
        val cx = viewW / 2
        val n = kind - FIRST
        // The stage: a sunny day (a dark bedroom for the dream, a bathroom wall for the pipe).
        when (n) {
            4 -> gfx.gradient(0f, 0f, viewW, viewH, 0xFF0A0E2A.toInt(), 0xFF3A2A6A.toInt())
            else -> gfx.gradient(0f, 0f, viewW, viewH, 0xFF4AA4F0.toInt(), 0xFFD0F0FF.toInt())
        }
        if (n == 5) { gfx.rect(cx, 0f, viewW - cx, ground, 0xFFDDEEFF.toInt()); for (k in 0 until 12) gfx.rect(cx + k * 20f, 0f, 1f, ground, 0xFFB8D0E8.toInt()) }
        gfx.rect(0f, ground, viewW, viewH - ground, if (n == 4) 0xFF3A2A4A.toInt() else 0xFF4AA83A.toInt())
        gfx.rect(0f, ground, viewW, 4f, if (n == 4) 0xFF5A4A6A.toInt() else 0xFF6ACB5A.toInt())
        when (n) {
            0 -> { // THE BIRTHDAY CAKE
                val bx = cx + 110f
                val covered = t > 7.5f
                blocky(gfx, bx, ground, flip = true)
                gfx.shadowText("HAPPY BIRTHDAY!", cx, 40f, 12f, solidRainbow(t * 0.3f, 0f), 1)
                // Rocco walks in carrying the cake, wobbling, then slips.
                val rx = when { t < 4f -> -40f + t * 30f; t < 6.4f -> 80f + (t - 4f) * 40f; else -> 176f }
                val slip = t in 6.4f..7.4f
                rocco(gfx, rx, ground, when { slip -> 2; t > 7.4f -> 0; else -> (t * 6).toInt() % 2 }, upside = slip && t > 6.8f, lift = if (slip) sin((t - 6.4f) * 3.1416f) * 30f else 0f)
                if (t in 5.6f..6.6f) gfx.poly(floatArrayOf(rx + 18, rx + 26, rx + 36), floatArrayOf(ground, ground - 6, ground), 0xFFFFE14D.toInt())
                val cakeX: Float; val cakeY: Float
                if (t < 6.4f) { cakeX = rx; cakeY = ground - 24 * S - 18f + sin(t * 9) * (if (t > 4f) 3f else 1f) }
                else { val k = minOf(1f, (t - 6.4f) / 1.1f); cakeX = rx + (bx - rx) * k; cakeY = ground - 90f - sin(k * 3.1416f) * 70f + k * 10f }
                if (!covered) cake(gfx, cakeX, cakeY, t)
                if (covered) {
                    // Blocky wears the cake!
                    gfx.oval(bx - 26, ground - 74, 52f, 40f, 0xFFFFF4F8.toInt())
                    for (k in 0 until 5) gfx.oval(bx - 20 + k * 9f, ground - 40 + ((t - 7.5f) * 20 + k * 7) % 30, 5f, 8f, 0xFFFFF4F8.toInt())
                    gfx.oval(bx - 5, ground - 82, 4f, 10f, 0xFFF4F4F4.toInt()); gfx.oval(bx - 4, ground - 88 + sin(t * 12), 3f, 5f, 0xFFFF8A2A.toInt())
                    if (t < 8.5f) gfx.shadowText("SPLAT!", cx, 70f, 24f, 0xFFFFFFFF.toInt(), 1)
                }
                if (t > 11.45f) gfx.shadowText("yum!", bx, ground - 100, 9f, 0xFFFFFFFF.toInt(), 1)
                if (t > 10.2f) mon(gfx, mon, bx - 40f + sin(t * 3) * 4, ground - abs(sin(t * 8)) * 16f, t, 1, true)
            }
            1 -> { // KRAG'S BAD HAIR DAY
                val kx = cx + 70f
                val krag = KingKrag(0f, 40f)
                krag.facing = -1
                krag.draw(g, ScaledGfx(gfx, 2.4f, kx - 18f * 2.4f, ground - 40f * 2.4f), 0f)
                val headX = kx - 10f
                val headY = ground - 40f * 2.4f
                if (t in 3.2f..4.4f) for (k in 0 until 8) gfx.oval(kx - 60f - k * 18f - (t - 3.2f) * 60f, headY + 30f + sin(k + t * 20) * 6, 22f, 16f, if (k % 2 == 0) 0xFFFF7A2A.toInt() else 0xFFFFE14D.toInt())
                // His hair burns from the sneeze until Rocco's water puts it out (the SPLASH at 6.9 s).
                val wet = 6.9f
                if (t in 3.2f..wet) for (k in 0 until 5) {
                    val fl = sin(t * 14 + k) * 4
                    gfx.oval(headX - 12 + k * 8f, headY - 20 + fl, 12f, 18f, 0xFFFF7A2A.toInt())
                    gfx.oval(headX - 9 + k * 8f, headY - 12 + fl, 6f, 9f, 0xFFFFE14D.toInt())
                }
                if (t > wet) {
                    // All wet: droopy hair, drips and a silly flower.
                    for (k in 0 until 4) gfx.oval(headX - 10 + k * 9f, headY - 6 + ((t * 30 + k * 7) % 30), 4f, 6f, 0x998AD8FF.toInt())
                    gfx.oval(headX + 10, headY - 18, 12f, 12f, 0xFFFF6A9A.toInt()); gfx.oval(headX + 13, headY - 15, 6f, 6f, 0xFFFFE14D.toInt())
                }
                // Rocco walks in, grabs a bucket, and throws ("Here, King Krag!" at 6.4 s).
                val throwT = 6.4f
                val rx = if (t < throwT) -40f + minOf(t, 4f) * 30f else 80f + minOf(1f, (t - throwT)) * 40f
                rocco(gfx, rx, ground, when { t < 4f -> (t * 6).toInt() % 2; t in throwT..throwT + 1.2f -> 2; else -> 0 })
                if (t in 5.6f..throwT + 1f) {
                    // A big bucket of water
                    gfx.poly(floatArrayOf(rx + 14, rx + 34, rx + 30, rx + 18), floatArrayOf(ground - 60, ground - 60, ground - 40, ground - 40), 0xFF8A94A4.toInt())
                    if (t > wet - 0.3f) for (k in 0 until 10) gfx.oval(rx + 30 + k * 10f, ground - 70 - sin(k * 0.4f) * 30 + (t - wet + 0.3f) * 40, 8f, 8f, 0xCC6AC8FF.toInt())
                }
                if (t in 3.2f..4.2f) gfx.shadowText("AH-CHOOO!", cx, 46f, 22f, 0xFFFF6A00.toInt(), 1)
                if (t in wet..wet + 1f) gfx.shadowText("SPLASH!", kx - 10f, 46f, 20f, 0xFF6AC8FF.toInt(), 1)
            }
            2 -> { // DANCE-OFF!
                gfx.rect(0f, 0f, viewW, ground, 0x99201040.toInt())
                for (i in 0 until 8) gfx.oval(viewW * ((i * 0.13f + t * 0.3f) % 1f), 30f + (i * 37f) % 110f, 26f, 26f, argb(120, solidRainbow(t + i * 0.2f, 0f)))
                // "DANCE-OFF!", Rocco's turn ("Watch THIS!" at 3 s), Blocky's turn (5.6 s), then the judge (10.4 s).
                val roccoTurn = t in 3f..5.6f
                val blockyTurn = t in 5.6f..8.6f
                val judged = 10.4f
                if (t in 0.3f..2.9f) gfx.shadowText("DANCE-OFF!", cx, 56f, 22f, solidRainbow(t, 0f), 1)
                rocco(gfx, cx - 90 + (if (roccoTurn) sin(t * 6) * 16 else 0f), ground, if (roccoTurn) 1 + (t * 6).toInt() % 2 else 0,
                    flip = roccoTurn && (t * 2).toInt() % 2 == 0, upside = roccoTurn && t in 4.3f..4.6f, lift = if (roccoTurn) abs(sin(t * 8)) * 16 else 0f)
                blocky(gfx, cx + 90, ground, walking = blockyTurn && (t * 8).toInt() % 2 == 0, flip = true, lift = if (blockyTurn) ((t * 4).toInt() % 2) * 10f else 0f)
                if (blockyTurn) gfx.shadowText("BEEP BOOP", cx + 90, 70f, 10f, 0xFF6AE8F0.toInt(), 1)
                // The judge
                val jy = if (t > judged) ground - abs(sin((t - judged) * 5)) * 50f else ground
                mon(gfx, mon, cx, jy, t, 1, t > judged)
                if (t in 4.7f..5.9f) gfx.shadowText("9!", cx, ground - 70, 18f, 0xFFFFE14D.toInt(), 1)
                if (t in 7.7f..8.9f) gfx.shadowText("9!", cx, ground - 70, 18f, 0xFFFFE14D.toInt(), 1)
                if (t in 9.0f..judged) gfx.shadowText("?", cx, ground - 70 - abs(sin(t * 4)) * 6, 18f, 0xFFFFFFFF.toInt(), 1)
                if (t > judged) {
                    gfx.shadowText("10! I WIN!", cx, 56f, 22f, solidRainbow(t, 0f), 1)
                    for (k in 0 until 20) gfx.rect((k * 53f + t * 40f) % viewW, (k * 37f + t * 90f) % ground, 4f, 6f, solidRainbow(k * 0.1f, 0f))
                }
            }
            3 -> { // THE STINKY CHEESE
                val rx = cx - 60f
                val green = t > 8f
                rocco(gfx, rx, ground, 0)
                if (green) gfx.oval(rx - 22, ground - 72, 44f, 40f, 0x664AC84A)
                // The cheese
                if (t < 9.5f) {
                    gfx.poly(floatArrayOf(rx + 24, rx + 64, rx + 64), floatArrayOf(ground - 40, ground - 52, ground - 32), 0xFFF4D84A.toInt())
                    for (k in 0 until 3) gfx.oval(rx + 40 + k * 7f, ground - 44 + (k % 2) * 5f, 4f, 4f, 0xFFE8B82A.toInt())
                    val waves = minOf(1f, t / 4f)
                    for (k in 0 until (3 + t * 1.5f).toInt().coerceAtMost(14)) {
                        val wx = rx + 50 + k * 18f * waves + sin(t * 3 + k) * 6
                        val wy = ground - 60 - (k % 4) * 18f - sin(t * 2 + k) * 6
                        gfx.shadowText("~", wx, wy, 14f, 0xFF6AC84A.toInt(), 1)
                    }
                }
                // Blocky says "PEE-YEW!" (2.3 s) and faints.
                blocky(gfx, cx + 60, ground, flip = true, upside = t > 2.6f, lift = if (t > 2.6f) -8f else 0f)
                val dx = if (t < 3.5f) cx + 130 else cx + 130 + (t - 3.5f) * 120f
                // Dennis watches the others, then howls and runs away from the smell.
                val run = t >= 3.5f
                Dennis.draw(ScaledGfx(gfx, 2.4f, dx, ground), 0f, 0f, t, running = run, facing = if (run) 1 else -1)
                if (t in 3.5f..4.6f) gfx.shadowText("AROOO!", cx + 130, ground - 84, 12f, 0xFFFFFFFF.toInt(), 1)
                mon(gfx, mon, cx + 10, ground, t, -1, t > 5f)
                if (t > 5f) gfx.shadowText("*holds nose*", cx + 10, ground - 60, 7f, 0xFFFFFFFF.toInt(), 1)
                if (t in 7.0f..8.0f) gfx.shadowText("*sniff sniff*", rx + 34, ground - 84, 8f, 0xFFFFFFFF.toInt(), 1)
                if (t in 2.3f..3.3f) gfx.shadowText("PEE-YEW!", cx, 50f, 22f, 0xFF6AC84A.toInt(), 1)
                if (t > 8f) gfx.shadowText("BLEEEH!", cx, 50f, 22f, 0xFF4AC84A.toInt(), 1)
            }
            4 -> { // ROCCO'S DREAM
                for (k in 0 until 20) gfx.rect((k * 61f) % viewW, (k * 29f) % 100f, 1.5f, 1.5f, 0xFFFFFFFF.toInt())
                val rx = cx - 110f
                // The bed
                gfx.rect(rx - 40, ground - 20, 100f, 20f, 0xFF8A5A2A.toInt()); gfx.rect(rx - 40, ground - 26, 100f, 8f, 0xFF3A6AD8.toInt())
                rocco(gfx, rx, ground - 16, 0)
                gfx.poly(floatArrayOf(rx - 14, rx + 16, rx + 10), floatArrayOf(ground - 80, ground - 86, ground - 100), 0xFF3A6AD8.toInt())
                gfx.oval(rx + 6, ground - 104, 8f, 8f, 0xFFFFFFFF.toInt())
                // "Good night...", then he dreams (pizza 2 s, dino 4.5 s, King Krag 6.85 s) until the bubble POPs at 9.2 s.
                val pop = 9.2f
                if (t in 0.2f..1.7f) gfx.shadowText("*yawn*", rx, ground - 112, 9f, 0xFFFFFFFF.toInt(), 1)
                if (t in 1.5f..pop) for (k in 0 until 3) {
                    // Z's float up out of his nightcap.
                    val f = ((t - 1.5f) * 0.5f + k / 3f) % 1f
                    gfx.shadowText(if (k == 1) "Z" else "z", rx - 12 - f * 26, ground - 104 - f * 44, 8f + f * 8, argb((255 * (1f - f * 0.7f)).toInt(), 0xFFFFFF), 1)
                }
                if (t > 1.8f && t < pop) {
                    for (k in 0 until 3) gfx.oval(rx + 30 + k * 16f, ground - 90 - k * 16f, 6f + k * 4, 6f + k * 4, 0xEEFFFFFF.toInt())
                    // The dream bubble
                    val bx = cx + 10; val by = 20f
                    gfx.oval(bx - 10, by, viewW - bx - 10, 150f, 0xEEFFFFFF.toInt())
                    val mx = bx + (viewW - bx - 30) / 2
                    when {
                        t < 4.4f -> { for (k in 0 until 2) { val px = mx - 30 + sin(t * 2 + k * 3) * 50; drawSlice(gfx, px, by + 40 + k * 40 + cos(t * 3 + k) * 10) } }
                        t < 6.8f -> {
                            val dx = mx + sin(t * 2) * 40
                            Dino.drawBody(ScaledGfx(gfx, 2f, dx, by + 130f), 0f, 0f, 1, t, true)
                            gfx.sprite(TeamArt.rocco(Power.NONE, 2), dx - 14, by + 80f, w = 28f, h = 42f)
                        }
                        else -> {
                            drawSlice(gfx, mx + 20, by + 80)
                            val krag = KingKrag(0f, 40f)
                            krag.facing = 1
                            krag.draw(g, ScaledGfx(gfx, 1.6f, mx - 60, by + 50f), 0f)
                        }
                    }
                }
                if (t in pop..pop + 0.6f) gfx.shadowText("POP!", cx + 60, 80f, 20f, 0xFFFFFFFF.toInt(), 1)
                if (t in pop + 0.1f..10.6f) gfx.shadowText("?!", rx, ground - 112, 14f, 0xFFFFE14D.toInt(), 1)
                if (t > 10.4f) {
                    mon(gfx, mon, cx + 80, ground, t, -1, true)
                    for (k in 0 until 4) gfx.oval(cx + 64 + k * 8f, ground - 6, 4f, 3f, 0xFFD8A050.toInt())
                    gfx.shadowText("burp!", cx + 80, ground - 64, 9f, 0xFFFFFFFF.toInt(), 1)
                }
            }
            else -> { // THE WRONG PIPE
                val pipeX = cx - 140f
                // The bathtub with Blocky in it
                val tubX = cx + 50f
                // Rocco jumps in the pipe (2.5 s), lands in the bath (4.3 s), Blocky yells "HEY!" (7.45 s), and Rocco
                // hops out with a "Wahoo!" (10.95 s) and runs back.
                val splash = 4.3f
                val hey = 7.45f
                val out = 10.95f
                blocky(gfx, tubX + 70, ground - 10, flip = true, lift = if (t > hey && t < hey + 0.8f) 14f else 0f)
                gfx.rect(tubX, ground - 34, 140f, 34f, 0xFFF4F4F4.toInt()); gfx.oval(tubX - 4, ground - 40, 148f, 12f, 0xFFF4F4F4.toInt())
                for (k in 0 until 10) gfx.oval(tubX + 4 + k * 13f, ground - 52 - (k % 3) * 6f + sin(t * 2 + k) * 2, 18f, 16f, 0xEEFFFFFF.toInt())
                // The pipes
                pipe(gfx, pipeX, ground)
                pipe(gfx, tubX + 20, ground - 120f, upsideDown = true)
                // Rocco
                when {
                    t < 2.5f -> rocco(gfx, pipeX - 40 + t * 16, ground - 30, (t * 6).toInt() % 2)
                    t < 4.0f -> { val k = (t - 2.5f) / 1.5f; if (k < 0.6f) rocco(gfx, pipeX, ground - 30 - sin(k / 0.6f * 3.1416f) * 40 + (if (k > 0.3f) (k - 0.3f) * 160 else 0f), 2) }
                    t < out -> {
                        val k = minOf(1f, (t - splash) * 3)
                        val ry = ground - 120f + 30f + k * 60f
                        if (t >= splash) rocco(gfx, tubX + 30, ry + 24 * S - 60f, 2)
                        if (t >= splash) for (k2 in 0 until 4) gfx.oval(tubX + 14 + k2 * 10f, ry - 30f + k2 % 2 * 6f, 14f, 12f, 0xEEFFFFFF.toInt())
                        if (t in splash..splash + 1f) gfx.shadowText("SPLOOSH!", cx, 50f, 22f, 0xFF6AC8FF.toInt(), 1)
                    }
                    else -> {
                        // A big hop out of the tub (dripping bubbles), then off he runs, back the way he came.
                        val u = t - out
                        val k = minOf(1f, u / 0.6f)
                        val x = tubX + 30 - k * 80f - maxOf(0f, u - 0.6f) * 220f
                        val feet = ground - 18f + k * 18f - sin(k * 3.1416f) * 60f
                        rocco(gfx, x, feet, if (k < 1f) 2 else (t * 8).toInt() % 2, flip = true)
                        if (u < 1f) for (d in 0 until 3) gfx.oval(x + 12 + d * 7f, feet - 50f + ((t * 60 + d * 13) % 30), 5f, 5f, 0xDDFFFFFF.toInt())
                    }
                }
                if (t in hey..hey + 1.2f) gfx.shadowText("HEY!!", tubX + 70, 50f, 22f, 0xFFE84A4A.toInt(), 1)
            }
        }
    }

    private fun cake(gfx: Gfx, x: Float, y: Float, t: Float) {
        gfx.rect(x - 24, y - 4, 48f, 18f, 0xFFFF9AC8.toInt())
        gfx.rect(x - 18, y - 18, 36f, 15f, 0xFFFFF4F8.toInt())
        for (k in 0 until 6) gfx.oval(x - 24 + k * 8f, y - 6, 8f, 6f, 0xFFFFF4F8.toInt())
        for (k in 0 until 3) { gfx.rect(x - 10 + k * 9f, y - 28, 3f, 10f, 0xFFF4F4F4.toInt()); gfx.oval(x - 10.5f + k * 9f, y - 34 + sin(t * 10 + k), 4f, 6f, 0xFFFF8A2A.toInt()) }
    }

    private fun drawSlice(gfx: Gfx, x: Float, y: Float) {
        gfx.poly(floatArrayOf(x - 14, x + 14, x), floatArrayOf(y - 10, y - 10, y + 16), 0xFFF0C060.toInt())
        gfx.rect(x - 14, y - 12, 28f, 4f, 0xFFD8A050.toInt())
        for (k in 0 until 3) gfx.oval(x - 8 + k * 5f, y - 6 + k * 4f, 5f, 5f, 0xFFD8402A.toInt())
    }

    private fun pipe(gfx: Gfx, x: Float, y: Float, upsideDown: Boolean = false) {
        val top = if (upsideDown) y else y - 40f
        gfx.rect(x - 22, top, 44f, 40f, 0xFF2E9A3A.toInt())
        gfx.rect(x - 26, if (upsideDown) top + 30f else top, 52f, 12f, 0xFF3CB84A.toInt())
        gfx.rect(x - 18, top, 6f, 40f, 0x55FFFFFF)
    }
}
