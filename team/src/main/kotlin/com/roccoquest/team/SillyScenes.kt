package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Music
import com.roccoquest.game.Sound
import com.roccoquest.game.Sprite
import com.roccoquest.game.Voice
import com.roccoquest.game.argb
import com.roccoquest.game.mixColor
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws everything turned by [ang] (radians, clockwise) around ([px], [py]), so scene props can tip over,
 * spin and flop. Ovals become many-sided shapes; sprites and text can't turn, so they only move along.
 */
class TurnGfx(private val inner: Gfx, private val px: Float, private val py: Float, ang: Float) : Gfx {
    private val cosA = cos(ang)
    private val sinA = sin(ang)
    // Not turned at all: draw straight through (real ovals look rounder than the many-sided ones).
    private val still = abs(ang) < 0.001f
    private fun tx(x: Float, y: Float) = px + (x - px) * cosA - (y - py) * sinA
    private fun ty(x: Float, y: Float) = py + (x - px) * sinA + (y - py) * cosA

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        if (still) { inner.rect(x, y, w, h, color); return }
        poly(floatArrayOf(x, x + w, x + w, x), floatArrayOf(y, y, y + h, y + h), color)
    }

    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        if (still) { inner.oval(x, y, w, h, color); return }
        val ox = x + w / 2
        val oy = y + h / 2
        val xs = FloatArray(SIDES)
        val ys = FloatArray(SIDES)
        for (i in 0 until SIDES) {
            val a = i * 6.2832f / SIDES
            val vx = ox + cos(a) * w / 2
            val vy = oy + sin(a) * h / 2
            xs[i] = tx(vx, vy)
            ys[i] = ty(vx, vy)
        }
        inner.poly(xs, ys, color)
    }

    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) =
        inner.poly(FloatArray(xs.size) { tx(xs[it], ys[it]) }, FloatArray(ys.size) { ty(xs[it], ys[it]) }, color)

    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) {
        val mx = x + w / 2
        val my = y + h / 2
        inner.sprite(s, tx(mx, my) - w / 2, ty(mx, my) - h / 2, flipX, flipY, w, h)
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) = inner.text(s, tx(x, y), ty(x, y), size, color, align)

    private companion object {
        const val SIDES = 18
    }
}

/**
 * Even more funny scenes (12-15 seconds each) starring the whole gang and the newest bad guys: Wonder King Krag's
 * magic bouncing back on himself, Dry Bones King Krag toasting marshmallows, Dennis's bath, Ring-Ding Frog's
 * concert... Scene numbers start at [FIRST], right after [LongScenes], so the old ones keep their numbers
 * (scenes you've seen are saved by number).
 */
object SillyScenes {
    const val FIRST = 26
    val titles = listOf(
        "WONDER OOPS!", "SPOOKY S'MORES", "THE WRONG MUSHROOM", "RING-DING CONCERT", "SAY CHEESE!",
        "BATH TIME, DENNIS!", "WHO ATE MY SPAGHETTI?", "THE BLOCK TOWER", "DASH AND THE BUTTERFLY",
    )
    // (Long enough for the last line to be said before the scene ends.)
    private val lengths = floatArrayOf(13.6f, 15.3f, 13.3f, 13.5f, 12.8f, 13.6f, 14.2f, 13.3f, 12.4f)

    fun length(kind: Int) = lengths.getOrElse(kind - FIRST) { 12f }

    /** Most scenes get the silly song; a few have their own (Wonder magic, a spooky campfire, the frog's big show). */
    fun music(kind: Int) = when (kind - FIRST) {
        0 -> Music.WONDER
        1 -> Music.SPOOKY
        3 -> Music.FROG
        else -> Music.SILLY
    }

    private fun cue(at: Float, text: String, voice: Voice = Voice.HERO, sound: Sound? = null) = Scenes.Cue(at, text, voice, sound)
    /** Just a sound (a ZAP, a SPLASH...) with nobody talking, so it doesn't cut off whoever is speaking. */
    private fun sfx(at: Float, sound: Sound) = Scenes.Cue(at, "", Voice.HERO, sound)

    /*
     * The voices speak at a natural speed, and a new line cuts off the one before it, so each line is short
     * enough to be finished before the next one starts (and the last one before the scene ends): about 14
     * letters a second, a bit slower for King Krag's deep voice (SpeechText.seconds, checked by the tests).
     * Little shouts that wouldn't fit ("BING-BONG!", "WOOF!", "AH... AAAH...") are written on the screen
     * with a sound instead.
     */
    fun cues(kind: Int, monName: String): List<Scenes.Cue> = when (kind - FIRST) {
        0 -> listOf( // WONDER OOPS!
            cue(0.4f, "Wonder magic! Be a fish!", Voice.GOD, Sound.PORTAL),
            cue(2.7f, "Not today! Magic mirror!", sound = Sound.COIN),
            sfx(3.4f, Sound.ZAP), sfx(4.3f, Sound.KICK),
            cue(5.0f, "Huh? Blub?!", Voice.GOD, Sound.PORTAL),
            cue(6.3f, "NOT wonderful!", Voice.GOD, Sound.SPLASH),
            cue(7.9f, "Papa! I'll zap him!", Voice.TOAD, Sound.KICK),
            sfx(9.4f, Sound.ZAP),
            cue(10.1f, "Blub?!", Voice.TOAD, Sound.PORTAL),
            cue(10.9f, "Ha ha! A whole fish family!", sound = Sound.ONEUP),
        )
        1 -> listOf( // SPOOKY S'MORES
            cue(0.4f, "Uh oh... no fire!"),
            cue(2.0f, "And no matches!", Voice.VILLAGER),
            cue(3.6f, "Rattle! Need a light?", Voice.GOD, Sound.BREAK),
            cue(5.8f, "EEEEK! A skeleton!", sound = Sound.JUMP),
            sfx(6.9f, Sound.BOSS_FIRE),
            cue(7.9f, "Ooh! Blue marshmallows!", sound = Sound.COIN),
            cue(10.4f, "AH-CHOOOO!", Voice.GOD, Sound.BOSS_FIRE),
            sfx(10.8f, Sound.BREAK),
            cue(11.5f, "...Extra crispy."),
            sfx(12.3f, Sound.BARK),
            cue(13.1f, "Hey! That's my leg!", Voice.GOD),
        )
        2 -> listOf( // THE WRONG MUSHROOM
            cue(0.4f, "Rocco! Super mushroom!", Voice.TOAD),
            cue(2.1f, "Grazie, Spotty! Nom nom!", sound = Sound.CHOMP),
            sfx(3.3f, Sound.POWERUP),
            cue(4.6f, "HUGE! MAMMA MIA!", Voice.GOD),
            cue(6.3f, "Oops! Wrong one! Try this one!", Voice.TOAD),
            sfx(7.6f, Sound.CHOMP), sfx(7.9f, Sound.HURT),
            cue(8.7f, "I'm teeny tiny!", Voice.TOAD),
            cue(10.0f, "$monName?", Voice.MON),
            cue(11.1f, "Hee hee! Mushroom mix-up!", Voice.TOAD, Sound.JUMP),
        )
        3 -> listOf( // RING-DING CONCERT
            cue(0.4f, "Ring-ding! Hello everybody!", Voice.FROG, Sound.ONEUP),
            cue(2.5f, "Woo-hoo! Go, Frog, go!"),
            sfx(3.7f, Sound.COIN),
            cue(4.9f, "STOP that noise!", Voice.TOAD, Sound.BUMP),
            cue(6.2f, "My HIGHEST note!", Voice.FROG),
            cue(7.6f, "DIIIIIIIING!", Voice.FROG, Sound.ZAP),
            cue(8.8f, "WAAAAAAH!", Voice.TOAD, Sound.JUMP),
            cue(10.0f, "Encore! Encore!", sound = Sound.COIN),
            cue(11.5f, "Thank you! Ring-ding!", Voice.FROG, Sound.ONEUP),
        )
        4 -> listOf( // SAY CHEESE!
            cue(0.4f, "Team photo time!"),
            cue(2.2f, "Wait for me!", Voice.VILLAGER, Sound.JUMP),
            cue(3.5f, "Say CHEESE!"),
            cue(4.7f, "Cheeeeese!", Voice.MON),
            cue(5.7f, "AH-CHOO!", Voice.MON, Sound.ZAP),
            cue(6.9f, "Hot hot HOT!", Voice.VILLAGER, Sound.FIRE),
            cue(8.2f, "Hydro!", Voice.MON, Sound.SPLASH),
            sfx(8.8f, Sound.KICK),
            sfx(9.4f, Sound.COIN),
            cue(10.2f, "...Perfect! Ha ha!"),
            sfx(11.2f, Sound.ONEUP),
        )
        5 -> listOf( // BATH TIME, DENNIS!
            cue(0.4f, "Bath time, Dennis!"),
            cue(2.1f, "Woof...", Voice.MON, Sound.BARK),
            cue(3.2f, "A bubble beard!"),
            cue(4.7f, "Uh oh... no shaking!"),
            sfx(5.9f, Sound.SPLASH),
            cue(6.5f, "I'm SOAKED!", sound = Sound.SPLASH),
            cue(7.7f, "Woof woof!", Voice.MON, Sound.BARK),
            sfx(8.6f, Sound.SPLASH),
            cue(9.2f, "DENNIS! Not the MUD!"),
            sfx(10.4f, Sound.BARK),
            cue(11.0f, "...Bath time for everybody."),
        )
        6 -> listOf( // WHO ATE MY SPAGHETTI?
            cue(0.4f, "My spaghetti!"),
            cue(1.9f, "Detective Rocco!", sound = Sound.COIN),
            cue(3.8f, "I only eat cookies!", Voice.VILLAGER),
            cue(5.6f, "I'm a mushroom!", Voice.TOAD, Sound.JUMP),
            cue(6.9f, "$monName!", Voice.MON),
            cue(8.0f, "That tree looks funny..."),
            cue(10.1f, "I am... a tree.", Voice.GOD, Sound.BREAK),
            cue(11.7f, "Slurrrrp!", Voice.GOD, Sound.CHOMP),
            cue(12.7f, "KING KRAG!!", sound = Sound.BUMP),
        )
        7 -> listOf( // THE BLOCK TOWER
            cue(0.4f, "Watch me build the tallest tower!", Voice.VILLAGER),
            *BLOCK_TIMES.map { sfx(it, Sound.KICK) }.toTypedArray(),
            cue(3.8f, "Wow! So tall!"),
            cue(5.2f, "$monName!", Voice.MON, Sound.JUMP),
            sfx(5.8f, Sound.KICK),
            cue(6.25f, "Not on the top!", Voice.VILLAGER),
            cue(7.8f, "Uh oh..."),
            sfx(8.4f, Sound.BREAK), sfx(9.0f, Sound.BOSS_HIT),
            cue(9.8f, "...I'm okay!"),
            cue(11.0f, "Let's build it again!", Voice.VILLAGER, Sound.ONEUP),
        )
        else -> listOf( // DASH AND THE BUTTERFLY
            cue(0.4f, "Nice and slow, Dash..."),
            cue(2.6f, "Rawr?!", Voice.MON),
            cue(3.4f, "Dash! SLOW DOWN!", sound = Sound.JUMP),
            cue(5.0f, "WAAAAAAAH!"),
            sfx(6.3f, Sound.BUMP),
            cue(6.6f, "Wahoooooo!", sound = Sound.JUMP),
            sfx(7.6f, Sound.SPLASH),
            cue(8.4f, "Rawr!", Voice.MON, Sound.COIN),
            cue(9.8f, "...Next time, I'm walking."),
        )
    }

    fun draw(g: TeamGame, gfx: Gfx, kind: Int, t: Float, viewW: Float, viewH: Float, mon: Mon) {
        when (kind - FIRST) {
            0 -> wonderOops(g, gfx, t, viewW, viewH)
            1 -> spookySmores(g, gfx, t, viewW, viewH)
            2 -> wrongMushroom(gfx, t, viewW, viewH, mon)
            3 -> concert(g, gfx, t, viewW, viewH, mon)
            4 -> sayCheese(gfx, t, viewW, viewH)
            5 -> bathTime(gfx, t, viewW, viewH)
            6 -> spaghetti(g, gfx, t, viewW, viewH, mon)
            7 -> blockTower(gfx, t, viewW, viewH, mon)
            else -> dashAndButterfly(gfx, t, viewW, viewH)
        }
    }

    // ======================================================================== The scenes

    /** Wonder King Krag zaps Rocco... who holds up a mirror. Then Krag Jr. tries it too. */
    private fun wonderOops(g: TeamGame, gfx: Gfx, t: Float, viewW: Float, viewH: Float) {
        wonderStage(gfx, viewW, viewH, t)
        val cx = viewW / 2
        val kx = cx + 100f
        val ks = 1.75f
        val rx = cx - 125f
        val laughing = t > 10.9f
        rocco(gfx, rx, G, if (laughing) 2 else 0, lift = if (laughing) abs(sin(t * 7)) * 14f else 0f)
        // Rocco's magic mirror comes out whenever a zap is coming.
        val mx = rx + 38f
        val my = G - 62f
        if (t in 2.7f..5.4f || t in 9.0f..10.6f) mirror(gfx, rx, mx, my, t)
        if (t < 5f) {
            val wobble = if (t in 4.6f..5f) sin(t * 80) * 3f else 0f
            krag(g, gfx, kx + wobble, G, ks, wonder = true, mouth = t in 3.2f..4.3f)
            for (i in 0 until 8) {
                val a = t * 2 + i * 0.785f
                gfx.oval(kx + sin(a) * 66f - 4, G - 64f + cos(a) * 60f - 4, 8f, 8f, argb(200, solidRainbow(t + i / 8f)))
            }
            if (t in 0.4f..2.7f) gfx.shadowText("WONDER MAGIC!", kx - 10, 52f, 15f, solidRainbow(t), 1)
        }
        // The rainbow zap flies to the mirror... and bounces right back at him!
        if (t in 3.4f..4.3f) beam(gfx, kragMouthX(kx, ks, -1, true), kragMouthY(G, ks, true), mx, my, (t - 3.4f) / 0.9f, t)
        if (t in 4.3f..5.0f) {
            beam(gfx, mx, my, kx, G - 70f, (t - 4.3f) / 0.7f, t)
            gfx.shadowText("BOING!", mx, my - 34f, 14f, WHITE, 1)
        }
        if (t >= 5f) {
            // King Krag the floppy fish
            val p = (t - 5f) * 5.5f
            val fx = kx - 10f + sin((t - 5f) * 1.2f) * 26f
            kragFish(gfx, fx, G - 38f - abs(sin(p)) * 34f, 2.1f, cos(p) * 0.5f, if ((p / PI_F).toInt() % 2 == 0) -1 else 1, wonder = true, junior = false, t = t)
            if (t < 7.6f) gfx.shadowText("FLOP! FLOP!", fx, 66f, 14f, YELLOW, 1)
        }
        if (t in 5f..6f) puff(gfx, kx, G - 64f, t - 5f, 1.4f)
        // Krag Jr. flies in to help his papa (with his own Wonder magic)...
        val jx = cx + 20f
        if (t in 7.3f..10.1f) {
            val k = smooth(((t - 7.3f) / 1.0f).coerceAtMost(1f))
            val x = viewW + 50f + (jx - viewW - 50f) * k
            val y = 22f + 22f * k + sin(t * 3) * 4f
            kragJr(g, gfx, x, y, 1.8f, wonder = true)
            if (t in 7.9f..9.3f) gfx.shadowText("PAPA!", x, y - 4f, 12f, 0xFFFFD0F0.toInt(), 1)
        }
        // ...and gets bounced too.
        val jy = 44f + 22f * 1.8f
        if (t in 9.4f..9.8f) beam(gfx, jx, jy, mx, my, (t - 9.4f) / 0.4f, t)
        if (t in 9.8f..10.1f) beam(gfx, mx, my, jx, jy, (t - 9.8f) / 0.3f, t)
        if (t in 10.1f..11.1f) puff(gfx, jx, jy, t - 10.1f, 1f)
        if (t >= 10.1f) {
            val fall = ((t - 10.1f) / 0.5f).coerceAtMost(1f)
            val p = (t - 10.1f) * 7f
            val y = jy + (G - 27f - jy) * fall * fall - (if (fall >= 1f) abs(sin(p)) * 20f else 0f)
            kragFish(gfx, jx - 14f, y, 1.35f, if (fall >= 1f) cos(p) * 0.6f else 0.4f, if ((p / PI_F).toInt() % 2 == 0) 1 else -1, wonder = true, junior = true, t = t)
        }
        if (laughing) gfx.shadowText("HA HA HA!", rx, 70f + sin(t * 9) * 3f, 14f, YELLOW, 1)
    }

    /** Dry Bones King Krag lights the campfire with his blue fire... and then sneezes. */
    private fun spookySmores(g: TeamGame, gfx: Gfx, t: Float, viewW: Float, viewH: Float) {
        night(gfx, viewW, viewH, t)
        val cx = viewW / 2
        val fireX = cx - 6f
        val rx = cx - 150f
        val bx = cx - 85f
        val lit = t > 7.3f
        if (lit) gfx.oval(fireX - 80, G - 70, 160f, 100f, 0x302E7BFF)
        campfire(gfx, fireX, t, lit)
        // Rocco and Blocky jump when the skeleton shows up, and shiver before that.
        val scared = t in 5.8f..6.7f
        val hop = if (scared) sin((t - 5.8f) / 0.9f * PI_F) * 34f else 0f
        val shiver = if (t in 3.6f..5.8f) sin(t * 50) * 1.2f else 0f
        val sooty = t > 10.6f
        rocco(gfx, rx + shiver, G, if (scared) 2 else 0, lift = hop)
        blocky(gfx, bx - shiver, G, lift = hop)
        if (scared) gfx.shadowText("EEEK!", cx - 118, 62f, 20f, YELLOW, 1)
        // Marshmallows on long sticks: white, then golden... then burnt black.
        val toast = ((t - 7.6f) / 1.8f).coerceIn(0f, 1f)
        val mallow = if (sooty) 0xFF2A2420.toInt() else mixColor(0xFFFFFFFF.toInt(), 0xFFE0A050.toInt(), toast)
        val tipY = G - 36f - hop
        stick(gfx, rx + 16, G - 33 - hop, fireX - 14, tipY, 2.5f, BROWN)
        stick(gfx, bx + 14, G - 26 - hop, fireX + 6, tipY - 14, 2.5f, BROWN)
        gfx.oval(fireX - 20, tipY - 5, 13f, 10f, mallow)
        gfx.oval(fireX, tipY - 19, 13f, 10f, mallow)
        if (sooty) {
            // Sooty faces and smoking hair after the big sneeze
            sootFace(gfx, rx + 3f, G - 58f, 30f, t)
            sootFace(gfx, bx, G - 54f, 32f, t)
            for (k in 0 until 6) {
                val sy = ((t * 26 + k * 13) % 50)
                gfx.oval(rx - 8 + (k % 3) * 8f + sin(t * 3 + k) * 3, G - 78 - sy, 8f, 8f, argb(120 - sy.toInt() * 2, 0x8A8A8A))
                gfx.oval(fireX - 18 + (k % 2) * 20f, tipY - 14 - sy, 6f, 6f, argb(120 - sy.toInt() * 2, 0x6A6A6A))
            }
        }
        // Dry Bones King Krag rattles in from the right
        val ks = 1.55f
        val kx = if (t < 4.6f) viewW + 70f + (cx + 110f - viewW - 70f) * ((t - 3.0f) / 1.6f).coerceIn(0f, 1f) else cx + 110f
        val kmx = kragMouthX(kx, ks, -1, true)
        val kmy = kragMouthY(G, ks, true)
        val inPieces = t > 10.8f
        if (t > 3.0f && !inPieces) {
            val bob = if (t < 4.6f) abs(sin(t * 8)) * 4f else 0f
            val lean = when {
                t in 9.4f..10.4f -> (t - 9.4f) * 0.18f
                t in 10.4f..10.8f -> 0.18f - (t - 10.4f) * 1.1f
                else -> 0f
            }
            krag(g, TurnGfx(gfx, kx, G, lean), kx, G - bob, ks, dry = true, mouth = t in 6.9f..7.7f || t in 10.4f..10.9f)
            if (t in 3.6f..5.6f) gfx.shadowText("RATTLE RATTLE", kx, 76f, 11f, 0xFFF0F0E0.toInt(), 1)
            if (t in 9.4f..10.4f) gfx.shadowText("AH... AAAH...", kx - 10f, 56f, 13f, 0xFF9FE0FF.toInt(), 1)
        }
        // Blue fire into the logs: WHOOSH, the campfire is lit.
        if (t in 6.9f..7.7f) for (i in 0 until 6) {
            val f = ((t - 6.9f) * 2.2f + i * 0.17f) % 1f
            val x = kmx + (fireX - kmx) * f
            val y = kmy + (G - 24f - kmy) * f
            blueFlame(gfx, x, y, 1f)
        }
        // AH-CHOOOO: a giant blue sneeze toasts everybody's marshmallows (and faces).
        if (t in 10.4f..11.0f) {
            val k = (t - 10.4f) / 0.6f
            val reach = kmx - (kmx - 10f) * min(1f, k * 2.5f)
            gfx.poly(floatArrayOf(kmx, reach, reach), floatArrayOf(kmy, kmy - 80f, kmy + 46f), argb((190 * (1 - k)).toInt(), 0x2E7BFF))
            for (i in 0 until 9) blueFlame(gfx, kmx + (reach - kmx) * (i / 8f), kmy - 20f + sin(i * 2.1f + t * 20) * 26f, 1.4f)
            gfx.shadowText("AH-CHOOOO!", cx, 52f, 22f, 0xFF9FE0FF.toInt(), 1)
        }
        // He sneezed himself to pieces! And here comes Dennis...
        val dennisX = if (t < 12.7f) -70f + (kx - 40f + 70f) * ((t - 11.9f) / 0.8f) else kx - 40f + (t - 12.7f) * 220f
        val hasBone = t >= 12.7f
        if (inPieces) {
            val chase = t > 13.1f
            val skullX = if (chase) kx + 14f + (t - 13.1f) * 70f else kx + 14f
            val skullUp = if (chase) abs(sin((t - 13.1f) * 9f)) * 16f else 0f
            bonePile(gfx, kx, G, skullX, skullUp, missing = hasBone, t = t)
            if (t in 10.8f..11.5f) puff(gfx, kx, G - 30f, (t - 10.8f) / 0.7f, 1.2f)
            if (chase) gfx.shadowText("HEY!", skullX, G - 50f - skullUp, 16f, 0xFFFF3A3A.toInt(), 1)
        }
        if (t in 11.9f..14.6f) {
            // He runs the whole time: in, grab the leg bone, and away (with the bone in his mouth).
            dennis(gfx, dennisX, G, t, running = true)
            if (hasBone) bone(gfx, dennisX + HerculesArt.MOUTH_X * DS, G - (HerculesArt.H - HerculesArt.MOUTH_Y) * DS, 26f)
            if (t in 12.3f..13.0f) gfx.shadowText("WOOF WOOF!", dennisX + 30f, G - 82f, 12f, WHITE, 1)
        }
    }

    /** Spotty brings Rocco a mushroom: a GIANT one. Oops. Then a teeny tiny one. */
    private fun wrongMushroom(gfx: Gfx, t: Float, viewW: Float, viewH: Float, mon: Mon) {
        sunny(gfx, viewW, viewH, t)
        val cx = viewW / 2
        val rx = cx - 60f
        // How big Rocco is: normal, then GIANT (growing in Mario-style steps), then teeny tiny.
        val s = when {
            t < 3.3f -> 3f
            t < 4.5f -> GROW[((t - 3.3f) / 0.2f).toInt().coerceIn(0, GROW.size - 1)]
            t < 7.9f -> 7.6f
            t < 8.7f -> SHRINK[((t - 7.9f) / 0.14f).toInt().coerceIn(0, SHRINK.size - 1)]
            else -> 1f
        }
        // Giant Rocco makes the ground shake: Spotty bounces.
        val quake = if (t in 3.3f..6.0f) abs(sin(t * 22)) * 5f else 0f
        val tinyHop = if (t in 10.4f..11.4f) abs(sin((t - 10.4f) * 9f)) * 10f else 0f
        rocco(gfx, rx, G, if (t in 3.3f..4.5f) 2 else 0, lift = tinyHop, s = s)
        // Spotty runs in with the mushroom
        val sx = if (t < 1.2f) viewW + 30f + (cx + 70f - viewW - 30f) * (t / 1.2f) else cx + 70f
        val spottyHop = quake + (if (t > 11f) abs(sin(t * 9)) * 14f else 0f)
        spotty(gfx, sx, G, t, running = t < 1.2f, lift = spottyHop)
        if (t < 2.1f) mushroom(gfx, sx, G - 62f - spottyHop, 18f, RED)
        if (t in 2.1f..2.6f) {
            val k = (t - 2.1f) / 0.5f
            mushroom(gfx, sx + (rx + 6f - sx) * k, G - 62f + (G - 52f - G + 62f) * k - sin(k * PI_F) * 30f, 18f * (1 - k * 0.5f), RED)
        }
        if (t in 2.6f..3.2f) gfx.shadowText("NOM!", rx + 20f, G - 84f, 14f, WHITE, 1)
        if (t in 3.3f..4.6f) gfx.shadowText("!", sx, G - 70f - spottyHop, 18f, 0xFFFF3A3A.toInt(), 1)
        if (t in 4.6f..6.2f) gfx.shadowText("I'M HUGE!", cx + 90f, 74f + sin(t * 30) * 2f, 22f, 0xFFFF8A2A.toInt(), 1)
        // The little blue mini mushroom, tossed right into Rocco's giant mouth
        if (t in 6.3f..6.8f) mushroom(gfx, sx, G - 62f, 14f, 0xFF3A8AE8.toInt())
        if (t in 6.8f..7.6f) {
            val k = (t - 6.8f) / 0.8f
            val tx = rx + 2f * 7.6f
            val ty = G - 17.5f * 7.6f
            mushroom(gfx, sx + (tx - sx) * k, G - 62f + (ty - G + 62f) * k - sin(k * PI_F) * 50f, 14f, 0xFF3A8AE8.toInt())
        }
        if (t in 7.6f..8.0f) gfx.shadowText("GULP!", rx + 40f, 60f, 16f, WHITE, 1)
        if (t in 8.8f..10f) gfx.shadowText("teeny tiny!", rx, G - 34f, 8f, WHITE, 1)
        // The Pokemon comes to sniff tiny Rocco
        if (t > 9.6f) {
            val k = ((t - 9.6f) / 0.7f).coerceAtMost(1f)
            val px = -40f + (rx - 40f + 40f) * k
            val sniff = if (t > 10.3f) abs(sin(t * 10)) * 3f else 0f
            mon(gfx, mon, px, G + sniff, t, 1, t > 10.3f)
            if (t in 10.0f..11.0f) gfx.shadowText("?", px, G - 64f, 16f, YELLOW, 1)
        }
        if (t > 11f) gfx.shadowText("HEE HEE!", sx, G - 76f - spottyHop, 13f, 0xFFFF6A9A.toInt(), 1)
    }

    /** Ring-Ding Frog's concert. Krag Jr. wants it to stop... until the HIGHEST NOTE. */
    private fun concert(g: TeamGame, gfx: Gfx, t: Float, viewW: Float, viewH: Float, mon: Mon) {
        concertStage(gfx, viewW, viewH, t)
        val cx = viewW / 2
        val stageX = cx - 6f
        val top = G - 26f
        val fx = cx + 92f
        // The stage, with spotlights on the star
        gfx.rect(stageX, top, viewW - stageX, 26f, 0xFF8A5A2A.toInt())
        gfx.rect(stageX, top, viewW - stageX, 4f, 0xFFC8904A.toInt())
        for (k in 0 until 6) gfx.rect(stageX + k * 30f, top + 8, 1.5f, 18f, 0xFF6A4220.toInt())
        gfx.poly(floatArrayOf(fx - 70, fx - 62, fx + 50, fx - 10), floatArrayOf(22f, 22f, top, top), 0x30FFF4C0)
        gfx.poly(floatArrayOf(fx + 62, fx + 70, fx + 10, fx - 50), floatArrayOf(22f, 22f, top, top), 0x30FFF4C0)
        gfx.oval(fx - 60, top - 6, 120f, 12f, 0x40FFF4C0)
        // The big note: he takes a deep breath (and gets bigger), then DIIING!
        val breath = if (t in 6.2f..7.6f) (t - 6.2f) / 1.4f * 0.5f else 0f
        val fs = 3f + breath
        val bow = if (t > 11.5f) 6f else 0f
        TeamArt.frog(ScaledGfx(gfx, fs, fx - 11f * fs, top - 26f * fs + bow), 0f, 0f, t, TeamArt.frogColors[0])
        val ding = t in 7.6f..9.2f
        if (ding) {
            val k = t - 7.6f
            for (ring in 0 until 3) {
                val r = k * 260f - ring * 46f
                if (r <= 0f) continue
                for (i in 0 until 26) {
                    val a = i * 0.2417f
                    gfx.oval(fx + cos(a) * r - 4, top - 50f + sin(a) * r * 0.8f - 4, 8f, 8f, solidRainbow(i / 26f + ring * 0.3f))
                }
            }
            if (k < 1f) gfx.shadowText("DIIIIIIING!", fx, 50f + sin(t * 50) * 2f, 20f, solidRainbow(t), 1)
        }
        // A silly little song before the big note (written up, so it doesn't talk over the cheering).
        if (t in 3.7f..4.9f) gfx.shadowText("BING-BONG-A-DONG!", fx - 10f, 58f - abs(sin(t * 9)) * 6f, 13f, solidRainbow(t * 2), 1)
        // The crowd: dancing, then blown back by the big note, then cheering for more.
        val dancing = t < 4.9f || t > 10f
        val blown = if (t in 7.6f..8.6f) sin((t - 7.6f) * PI_F) * 16f else 0f
        fun dance(i: Int) = if (dancing) abs(sin(t * 7 + i * 0.9f)) * (if (t > 10f) 18f else 9f) else 0f
        spotty(gfx, cx - 176f - blown, G, t, lift = dance(0))
        rocco(gfx, cx - 132f - blown, G, if (dancing) 1 + (t * 6).toInt() % 2 else 0, flip = dancing && (t * 2).toInt() % 2 == 0, lift = dance(1))
        blocky(gfx, cx - 84f - blown, G, walking = dancing && (t * 6).toInt() % 2 == 0, upside = t in 7.9f..8.7f, lift = dance(2) + (if (t in 7.9f..8.7f) 10f else 0f))
        mon(gfx, mon, cx - 38f - blown, G - dance(3), t, 1, dancing)
        if (t in 7.7f..9.0f) {
            // Rocco's cap flies off!
            val k = t - 7.7f
            cap(gfx, cx - 138f - k * 70f, G - 76f - k * 90f + k * k * 60f)
        }
        if (t > 10f) for (i in 0 until 18) gfx.rect((i * 53f + t * 40f) % viewW, 30f + (i * 37f + t * 80f) % (G - 40f), 4f, 6f, solidRainbow(i * 0.1f))
        if (t in 10f..11f) gfx.shadowText("ENCORE!", cx - 110f, 70f, 16f, YELLOW, 1)
        // Krag Jr. swoops in to complain, and the big note sends him spinning away.
        if (t in 4.5f..9.4f) {
            val inK = smooth(((t - 4.5f) / 0.7f).coerceAtMost(1f))
            val away = if (t > 7.8f) t - 7.8f else 0f
            val x = viewW + 40f + (cx - 6f - viewW - 40f) * inK - away * 230f
            val y = 50f + sin(t * 3) * 4f - away * 80f
            val angry = if (t in 4.9f..6.2f) sin(t * 60) * 2f else 0f
            kragJr(g, TurnGfx(gfx, x, y + 40f, away * 14f), x + angry, y, 1.8f)
            if (t in 4.9f..6.2f) gfx.shadowText("STOP!", x, y - 4f, 14f, 0xFFFF3A3A.toInt(), 1)
            if (away > 0.6f) gfx.shadowText("WAAAH!", x, y + 14f, 13f, WHITE, 1)
        }
    }

    private val CHEESE_MONS = listOf(Mon.SPARKY, Mon.HYDRO, Mon.BLOOM, Mon.BLAZE)

    /** The team photo. Sparky sneezes, Blaze gets a fright, Hydro puts out the fire... CLICK! */
    private fun sayCheese(gfx: Gfx, t: Float, viewW: Float, viewH: Float) {
        sunny(gfx, viewW, viewH, t)
        val cx = viewW / 2
        val rx = cx - 160f
        camera(gfx, rx + 46f, G)
        // Rocco sets the self-timer, then runs and jumps into the picture.
        val run = ((t - 8.8f) / 0.6f).coerceIn(0f, 1f)
        val rPos = rx + (cx - 52f - rx) * run
        val rLift = if (t in 9.1f..9.7f) sin((t - 9.1f) / 0.6f * PI_F) * 24f else 0f
        rocco(gfx, rPos, G, if (t in 8.8f..9.7f) (if (rLift > 0f) 2 else 1 + (t * 8).toInt() % 2) else 0, lift = rLift)
        if (t > 5.8f) afro(gfx, rPos - 1f, G - 70f - rLift, 40f, t)
        lineup(gfx, cx, t, photo = false)
        if (t in 8.8f..9.4f) gfx.shadowText("BEEP BEEP!", rx + 46f, G - 74f, 10f, 0xFFFF3A3A.toInt(), 1)
        // FLASH! Then the photo slides up.
        if (t in 9.4f..9.9f) gfx.rect(0f, 0f, viewW, viewH, argb((255 * (1 - (t - 9.4f) / 0.5f)).toInt(), 0xFFFFFF))
        if (t > 9.6f) {
            val k = smooth(((t - 9.6f) / 0.5f).coerceAtMost(1f))
            gfx.rect(0f, 22f, viewW, viewH - 22f, argb((120 * k).toInt(), 0))
            val pw = 214f
            val ph = 150f
            val px = cx - pw / 2
            val py = 36f + (1 - k) * 220f
            gfx.rect(px - 2, py - 2, pw + 4, ph + 4, 0xFF2A2A2A.toInt())
            gfx.rect(px, py, pw, ph, 0xFFF8F8F0.toInt())
            // The picture: the spot where everybody stood, shrunk down, at the moment of the flash.
            val left = cx - 78f
            val regionTop = G - 130f
            val sc = (pw - 16f) / 252f
            val pg = ScaledGfx(gfx, sc, px + 8f - left * sc, py + 8f - regionTop * sc)
            pg.rect(left, regionTop, 252f, 130f, 0xFF8ACBF5.toInt())
            pg.rect(left, G, 252f, 14f, 0xFF4AA83A.toInt())
            lineup(pg, cx, t, photo = true)
            gfx.text("TEAM PHOTO!", cx, py + ph - 12f, 10f, 0xFF3A3A8A.toInt(), 1)
            if (t > 10.2f) gfx.shadowText("PERFECT!", cx, py + ph + 20f, 18f, YELLOW, 1)
        }
    }

    /** Blocky and the Pokemon lined up for the photo. With [photo], everything is frozen at the moment of the flash. */
    private fun lineup(gfx: Gfx, cx: Float, now: Float, photo: Boolean) {
        val t = if (photo) 7.1f else now
        // Blocky stands at the end, next to Blaze (that's going to be a problem).
        val bx = if (t < 2.2f) -999f else cx + 152f + (1 - ((t - 2.2f) / 0.8f).coerceAtMost(1f)) * 200f
        val hot = t in 6.9f..7.9f
        val bLift = if (hot) sin((t - 6.9f) * PI_F) * 54f else 0f
        if (bx > -999f) {
            blocky(gfx, bx, G, walking = t < 3.0f && (t * 8).toInt() % 2 == 0, lift = bLift)
            if (t > 5.8f) afro(gfx, bx, G - 64f - bLift, 44f, now)
            if (hot) {
                for (k in 0 until 3) gfx.oval(bx - 12 + k * 8f, G - 4f - bLift - abs(sin(now * 20 + k)) * 6f, 9f, 14f, 0xFFFF8A2A.toInt())
                gfx.shadowText("HOT!", bx - 34f, G - 70f - bLift, 12f, 0xFFFF6A00.toInt(), 1)
            }
        }
        val wet = photo || t > 8.6f
        if (photo) {
            // Rocco jumped in just in time (with his zapped hair)!
            rocco(gfx, cx - 52f, G, 2, lift = 18f)
            afro(gfx, cx - 53f, G - 88f, 40f, now)
        }
        for ((i, m) in CHEESE_MONS.withIndex()) {
            val mx = cx - 28f + i * 47f
            val hopIn = if (t < 1.6f) abs(sin(t * 6 + i)) * 10f else 0f
            val sneezing = m == Mon.SPARKY && t in 5.0f..5.7f
            val jitter = if (sneezing) sin(t * 40) * 1.5f else 0f
            mon(gfx, m, mx + jitter, G - hopIn, now, -1, t in 4.7f..5.6f)
            if (wet) for (k in 0 until 3) gfx.oval(mx - 10 + k * 9f, G - 46f + ((now * 30 + k * 11 + i * 5) % 30), 3f, 5f, 0xCC6AC8FF.toInt())
        }
        if (t in 4.7f..5.6f) gfx.shadowText("CHEEESE!", cx + 50f, 56f, 16f, YELLOW, 1)
        if (t in 5.0f..5.7f) gfx.shadowText("ah... ah...", cx - 28f, G - 50f, 9f, WHITE, 1)
        // Sparky's sneeze: ZAP in every direction!
        if (t in 5.7f..6.4f) {
            val sx = cx - 28f
            val sy = G - 22f
            for (k in 0 until 7) {
                val a = -PI_F + k * PI_F / 6f
                bolt(gfx, sx, sy, sx + cos(a) * 130f, sy + sin(a) * 90f, now + k)
            }
            gfx.shadowText("AH-CHOO!", sx, 50f, 18f, YELLOW, 1)
        }
        // Blaze gets a fright: a puff of fire, right at Blocky's bottom.
        if (!photo && t in 6.6f..7.4f) for (k in 0 until 5) {
            val f = ((t - 6.6f) * 2.5f + k * 0.2f) % 1f
            gfx.oval(cx + 116f + f * 36f - 6, G - 22f - f * 4f - 6, 12f, 12f, if (k % 2 == 0) 0xFFFF8A2A.toInt() else 0xFFFFD21F.toInt())
        }
        // Hydro to the rescue: water everywhere.
        if (!photo && t in 8.2f..9.6f) for (k in 0 until 16) {
            val f = ((t - 8.2f) * 1.6f + k / 16f) % 1f
            val hx = cx + 19f
            gfx.oval(hx + (k - 7.5f) * 14f * f - 3, G - 40f - 90f * f + 110f * f * f - 3, 6f, 7f, 0xCC6AC8FF.toInt())
        }
    }

    /** Dennis's bath: bubbles, a big shake, and then... the mud. */
    private fun bathTime(gfx: Gfx, t: Float, viewW: Float, viewH: Float) {
        sunny(gfx, viewW, viewH, t)
        fence(gfx, viewW)
        val cx = viewW / 2
        val rx = cx - 135f
        val tubX = cx - 80f
        val mudX = cx + 128f
        // The mud puddle (uh oh)
        gfx.oval(mudX - 52, G - 3, 104f, 18f, 0xFF6A4422.toInt())
        gfx.oval(mudX - 40, G, 80f, 10f, 0xFF7A5230.toInt())
        // Rocco: scrubbing, then soaked, then muddy
        val soaked = t > 6.2f
        val muddy = t > 10.8f
        rocco(gfx, rx, G, 0)
        if (soaked) {
            gfx.oval(rx - 30, G - 3, 60f, 8f, 0x886AC8FF.toInt())
            for (k in 0 until 4) gfx.oval(rx - 16 + k * 10f, G - 70f + ((t * 50 + k * 17) % 66), 3f, 5f, 0xDD6AC8FF.toInt())
        }
        if (muddy) for (k in 0 until 7) gfx.oval(rx - 18 + (k * 13f) % 36f, G - 64f + (k * 17f) % 56f, 9f, 7f, 0xFF6A4422.toInt())
        if (t in 10.4f..10.8f) for (k in 0 until 6) {
            val f = (t - 10.4f) / 0.4f
            gfx.oval(mudX + (rx - mudX) * f - 4 + k * 4f, G - 20f - sin(f * PI_F) * 70f + k * 5f, 9f, 7f, 0xFF6A4422.toInt())
        }
        // Where Dennis is: his paws are on [dg], and he leaps [lift] above it.
        val inTub = t < 5.4f
        val dx: Float
        val dg: Float
        var lift = 0f
        var running = false
        when {
            inTub -> { dx = tubX + 26f; dg = G - 26f }
            t < 5.9f -> { val k = (t - 5.4f) / 0.5f; dx = tubX + 26f + (cx + 8f - tubX - 26f) * k; dg = G - 26f + 26f * k; lift = sin(k * PI_F) * 50f }
            t < 7.6f -> { dx = cx + 8f + (if (t < 7.3f) sin(t * 80) * 3f else 0f); dg = G }
            t < 8.4f -> { dx = cx + 8f + (mudX - 34f - cx - 8f) * ((t - 7.6f) / 0.8f); dg = G; running = true }
            t < 8.8f -> { val k = (t - 8.4f) / 0.4f; dx = mudX - 34f + 6f * k; dg = G; lift = sin(k * PI_F) * 30f }
            // SQUISH SQUISH: he shimmies from side to side, stomping and hopping in the mud.
            t < 10.4f -> { dx = mudX - 28f + sin(t * 12) * 5f; dg = G + 2f; lift = abs(sin(t * 12)) * 6f; running = true }
            else -> { dx = mudX - 28f; dg = G + 2f }
        }
        if (inTub) {
            // The tub: back rim, Dennis, then the front (so only his top shows), then bubbles.
            gfx.oval(tubX - 4, G - 46, 108f, 14f, 0xFF8A94A4.toInt())
            dennis(gfx, dx, dg, t)
            gfx.rect(tubX, G - 40, 100f, 36f, 0xFFB8C0CC.toInt())
            gfx.rect(tubX + 6, G - 34, 88f, 3f, 0xFFD8E0E8.toInt())
            gfx.rect(tubX + 6, G - 4, 8f, 4f, 0xFF6A7484.toInt()); gfx.rect(tubX + 86, G - 4, 8f, 4f, 0xFF6A7484.toInt())
            for (k in 0 until 9) gfx.oval(tubX + 2 + k * 11f, G - 48f - (k % 3) * 4f + sin(t * 2 + k) * 2, 16f, 13f, 0xEEFFFFFF.toInt())
            for (k in 0 until 5) {
                val by = (t * 30 + k * 23) % 80
                gfx.oval(tubX + 14 + k * 17f + sin(t * 2 + k) * 6, G - 52f - by, 7f, 7f, argb(200 - by.toInt() * 2, 0xFFFFFF))
            }
            if (t > 3.2f) {
                // A bubble beard (and a bubble hat)
                for (k in 0 until 5) gfx.oval(dx + 34f + k * 5f, dg - 30f + (k % 2) * 4f, 10f, 10f, 0xF0FFFFFF.toInt())
                for (k in 0 until 3) gfx.oval(dx + 30f + k * 7f, dg - 52f - (k % 2) * 5f, 11f, 10f, 0xF0FFFFFF.toInt())
            }
            // Rocco's scrub brush
            val bxs = dx + 22f + sin(t * 12) * 10f
            stick(gfx, rx + 16, G - 33, bxs, G - 52f, 3f, BROWN)
            gfx.oval(bxs - 9, G - 60f, 18f, 9f, 0xFFE8B060.toInt())
            if (t in 0.4f..2f) gfx.shadowText("SCRUB SCRUB!", rx + 40f, 60f, 13f, WHITE, 1)
        } else {
            // Out of the tub (and into the mud)
            gfx.oval(tubX - 4, G - 46, 108f, 14f, 0xFF8A94A4.toInt())
            gfx.rect(tubX, G - 40, 100f, 36f, 0xFFB8C0CC.toInt())
            gfx.oval(tubX + 4, G - 46, 92f, 10f, 0xFF8AD0FF.toInt())
            val inMud = t > 8.8f
            // (No shadow while he jumps out of the tub: it would hang in the air over the tub's side.)
            dennis(gfx, dx, dg, t, running, lift = lift, shadow = t >= 5.9f)
            // Mud splotches on his back, legs, tail and head: they move with him as he shimmies and hops.
            if (inMud) for ((ax, ay) in MUD_SPOTS) gfx.oval(dx + ax * DS - 6f, dg - lift - (HerculesArt.H - ay) * DS - 4.5f, 12f, 9f, 0xFF6A4422.toInt())
            if (t in 6.2f..7.2f) gfx.shadowText("WAAAH!", rx, G - 84f, 13f, 0xFF8AD8FF.toInt(), 1)
            if (t in 5.9f..7.3f) {
                for (k in 0 until 16) {
                    val a = k * 0.39f + t
                    val r = ((t - 5.9f) * 150 + k * 23) % 130
                    gfx.oval(dx + 26f + cos(a) * r - 3, dg - 18f + sin(a) * r * 0.7f - 3, 6f, 7f, 0xDD8AD8FF.toInt())
                }
                gfx.shadowText("SHAKE SHAKE SHAKE!", cx, 54f, 16f, 0xFF8AD8FF.toInt(), 1)
            }
            if (t in 8.6f..9.4f) for (k in 0 until 10) {
                val f = (t - 8.6f) / 0.8f
                gfx.oval(mudX + (k - 4.5f) * 12f * f - 4, G - 10f - sin(f * PI_F) * (40f + (k % 3) * 14f) - 4, 8f, 8f, 0xFF6A4422.toInt())
            }
            if (inMud && t < 10.4f) gfx.shadowText("SQUISH SQUISH!", mudX - 10f, 60f, 13f, 0xFFD8A060.toInt(), 1)
        }
    }

    /** Somebody ate Rocco's spaghetti. Detective Rocco asks everybody... and finds a very funny-looking tree. */
    private fun spaghetti(g: TeamGame, gfx: Gfx, t: Float, viewW: Float, viewH: Float, mon: Mon) {
        sunny(gfx, viewW, viewH, t)
        val cx = viewW / 2
        // The picnic table with the empty plate
        val tx = cx - 212f
        gfx.rect(tx + 6, G - 34, 4f, 34f, 0xFF7A4A24.toInt()); gfx.rect(tx + 52, G - 34, 4f, 34f, 0xFF7A4A24.toInt())
        // A red-and-white checked tablecloth
        gfx.rect(tx, G - 38, 62f, 12f, 0xFFF4F4F4.toInt())
        for (k in 0 until 8) for (row in 0 until 3) if ((k + row) % 2 == 0) gfx.rect(tx + k * 7.75f, G - 38 + row * 4f, 7.75f, 4f, 0xFFE84A4A.toInt())
        gfx.oval(tx + 14, G - 44, 34f, 8f, 0xFFFFFFFF.toInt())
        gfx.oval(tx + 22, G - 43, 14f, 4f, 0xFFD8402A.toInt())
        // The suspects
        val bx = cx - 66f
        val sx = cx - 6f
        val mx = cx + 52f
        val kx = cx + 152f
        val ks = 1.6f
        val caught = t > 12.7f
        krag(g, gfx, kx, G, ks, -1)
        // Spaghetti hanging out of King Krag's mouth (and sauce all over his face)
        // (His lips: a bit behind where his fire comes out.)
        val lipX = kx - 22f * ks
        val lipY = G - 28f * ks
        gfx.oval(lipX - 8, lipY - 10, 10f, 7f, 0xFFD8402A.toInt())
        gfx.oval(lipX + 4, lipY + 3, 8f, 5f, 0xFFD8402A.toInt())
        val noodle = 1f - ((t - 11.7f) / 0.8f).coerceIn(0f, 1f)
        if (noodle > 0f) {
            for (k in 0 until 4) {
                val len = (22f + k * 7f) * noodle
                val sway = sin(t * 4 + k) * 4f
                stick(gfx, lipX - 4 + k * 3f, lipY, lipX - 6 + k * 3f + sway, lipY + len * 0.5f, 2.5f, 0xFFF0D080.toInt())
                stick(gfx, lipX - 6 + k * 3f + sway, lipY + len * 0.5f, lipX - 3 + k * 3f - sway, lipY + len, 2.5f, 0xFFF0D080.toInt())
            }
            gfx.oval(lipX - 2, lipY + 34f * noodle - 4, 9f, 9f, 0xFF8A3A1A.toInt())
        }
        if (t in 11.7f..12.5f) gfx.shadowText("SLURRRP!", kx, 60f, 16f, 0xFFF0D080.toInt(), 1)
        if (caught) {
            // Blushing!
            gfx.oval(kx - 14f * ks - 6, G - 33f * ks - 3, 12f, 6f, 0x99FF6A9A.toInt())
            gfx.shadowText("BUSTED!", kx, 54f + sin(t * 12) * 2f, 20f, 0xFFFF3A3A.toInt(), 1)
        }
        // The teeny tree he's "hiding" behind
        val rustle = if (t in 10.1f..11.7f) sin(t * 34) * 2.5f else 0f
        gfx.rect(kx + 2, G - 44, 4f, 44f, 0xFF7A4A24.toInt())
        gfx.oval(kx - 12 + rustle, G - 62, 32f, 24f, 0xFF2E9A3A.toInt())
        gfx.oval(kx - 6 + rustle, G - 70, 22f, 16f, 0xFF3CB84A.toInt())
        if (t in 10.1f..11.7f) {
            gfx.shadowText("rustle rustle", kx + 4f, G - 104f, 8f, WHITE, 1)
            for (k in 0 until 3) gfx.oval(kx - 10 + k * 12f + sin(t * 5 + k) * 6, G - 56f + ((t - 10.1f) * 40 + k * 14) % 50, 6f, 4f, 0xFF3CB84A.toInt())
        }
        blocky(gfx, bx, G)
        if (t in 3.8f..5.4f) gfx.shadowText("NOT ME!", bx, G - 74f, 11f, WHITE, 1)
        val sHop = if (t in 5.6f..6.6f) abs(sin(t * 9)) * 10f else 0f
        spotty(gfx, sx, G, t, lift = sHop)
        if (t in 5.6f..6.7f) gfx.shadowText("NOT ME!", sx, G - 64f - sHop, 11f, WHITE, 1)
        val shaking = t in 6.9f..7.7f
        mon(gfx, mon, mx + (if (shaking) sin(t * 30) * 3f else 0f), G, t, if (shaking && (t * 8).toInt() % 2 == 0) 1 else -1, false)
        // Detective Rocco walks along the line of suspects with his magnifying glass.
        val stops = floatArrayOf(cx - 130f, bx - 42f, sx - 38f, mx - 42f, kx - 80f)
        val legs = floatArrayOf(3.0f, 4.9f, 6.2f, 7.8f)
        var rx = stops[0]
        var walking = false
        for (i in legs.indices) {
            val k = (t - legs[i]) / 0.5f
            if (k >= 1f) rx = stops[i + 1] else if (k > 0f) { rx = stops[i] + (stops[i + 1] - stops[i]) * k; walking = true }
        }
        val jump = if (caught) abs(sin((t - 12.7f) * 8f)) * 16f else 0f
        rocco(gfx, rx, G, if (walking) 1 + (t * 8).toInt() % 2 else if (caught) 2 else 0, flip = t < 1.9f, lift = jump)
        if (t >= 1.9f) {
            detectiveHat(gfx, rx, G - 72f - jump)
            stick(gfx, rx + 16, G - 33 - jump, rx + 30, G - 46 - jump, 3f, 0xFF3A3A44.toInt())
            gfx.oval(rx + 26, G - 64f - jump, 22f, 22f, 0xFF3A3A44.toInt())
            gfx.oval(rx + 29, G - 61f - jump, 16f, 16f, 0x88CFF4FF.toInt())
            gfx.oval(rx + 31, G - 59f - jump, 5f, 5f, 0xCCFFFFFF.toInt())
        }
        if (t < 1.9f) gfx.shadowText("GONE?!", rx - 20f, 60f, 18f, 0xFFFF3A3A.toInt(), 1)
        if (t in 8.3f..10.1f) gfx.shadowText("HMMM...", rx, G - 92f, 12f, WHITE, 1)
        if (caught) gfx.shadowText("KING KRAG!!", rx, 84f, 14f, YELLOW, 1)
    }

    /** Blocky builds a super tall tower. The Pokemon adds one tiny block on top... */
    private fun blockTower(gfx: Gfx, t: Float, viewW: Float, viewH: Float, mon: Mon) {
        sunny(gfx, viewW, viewH, t)
        val cx = viewW / 2
        val bs = 19f
        val baseX = cx - 40f
        val rx = cx - 142f
        val blockyX = cx + 30f
        val monX = cx + 92f
        val placed = BLOCK_TIMES.count { t >= it }
        val fallK = ((t - 8.4f) / 0.6f).coerceIn(0f, 1f)
        val fallen = t >= 9.0f
        // Rocco watches (and then gets buried; only his head pops back out)
        if (!fallen) {
            val wow = t in 3.8f..4.6f
            rocco(gfx, rx, G, if (wow) 2 else 0, lift = if (wow) sin((t - 3.8f) / 0.8f * PI_F) * 20f else 0f)
        } else {
            val up = ((t - 9.6f) / 0.4f).coerceIn(0f, 1f)
            rocco(gfx, rx, G + 44f - up * 26f)
            ground(gfx, viewW, viewH, 0xFF4AA83A.toInt(), 0xFF6ACB5A.toInt(), rx - 40f, 80f)
            for ((i, b) in PILE.withIndex()) {
                val (dx, dy, ang) = b
                block(TurnGfx(gfx, rx + dx, G + dy - bs / 2, ang), rx + dx - bs / 2, G + dy - bs, bs, i)
            }
            block(TurnGfx(gfx, rx + 30f, G - 48f, 0.5f), rx + 25f, G - 53f, 10f, 7)
            if (t < 9.8f) puff(gfx, rx, G - 30f, (t - 9.0f) / 0.8f, 1.3f)
            if (up > 0.5f) for (k in 0 until 3) {
                val a = t * 5 + k * 2.1f
                star(gfx, rx + cos(a) * 18f, G - 64f + sin(a) * 5f, 5f, YELLOW)
            }
        }
        // The tower: it wobbles... and wobbles... and falls right on Rocco.
        if (!fallen) {
            val wob = if (t in 6.2f..8.4f) sin(t * 9f) * min(0.09f, (t - 6.2f) * 0.05f) else 0f
            val tg = TurnGfx(gfx, baseX, G, wob - fallK * fallK * 1.6f)
            for (i in 0 until placed) {
                var y = G - (i + 1) * bs
                var x = baseX
                // The newest block flies in from Blocky's hands.
                val fly = ((t - BLOCK_TIMES[i]) / 0.25f).coerceIn(0f, 1f)
                if (fly < 1f) { x = blockyX - 10f + (baseX - blockyX + 10f) * fly; y = G - 40f + (y - G + 40f) * fly - sin(fly * PI_F) * 30f }
                block(tg, x, y, bs, i)
            }
            if (t >= 5.8f) block(tg, baseX + 6f, G - placed * bs - 10f, 10f, 7)
        }
        // Blocky builds (and panics, and cheers)
        val panic = t in 6.2f..9.0f
        val cheer = t > 11f
        val bLift = when {
            BLOCK_TIMES.any { t in it..(it + 0.2f) } -> 5f
            cheer -> abs(sin(t * 8)) * 14f
            else -> 0f
        }
        blocky(gfx, blockyX + (if (panic) sin(t * 40) * 2f else 0f), G, lift = bLift)
        if (panic && t < 7.8f) gfx.shadowText("NO NO NO!", blockyX, G - 80f, 12f, 0xFFFF3A3A.toInt(), 1)
        if (t in 3.8f..4.8f) gfx.shadowText("WOW!", rx, 70f, 16f, YELLOW, 1)
        // The Pokemon hops up with one teeny tiny block
        val mk = ((t - 5.2f) / 0.6f).coerceIn(0f, 1f)
        val back = ((t - 5.9f) / 0.5f).coerceIn(0f, 1f)
        val topY = G - placed * bs
        val mPos: Float
        val mY: Float
        if (t < 5.9f) { mPos = monX + (baseX + bs / 2 - monX) * mk; mY = G + (topY - G) * mk - sin(mk * PI_F) * 40f }
        else { mPos = baseX + bs / 2 + (monX - baseX - bs / 2) * back; mY = topY + (G - topY) * back - sin(back * PI_F) * 30f }
        mon(gfx, mon, mPos, mY, t, -1, cheer)
        if (t in 5.2f..5.8f) block(gfx, mPos + 6f, mY - 24f, 10f, 7)
        if (t in 8.4f..9.2f) gfx.shadowText("CRASH!", cx - 80f, 60f, 22f, 0xFFFF6A00.toInt(), 1)
        if (cheer) gfx.shadowText("AGAIN!", blockyX, G - 84f - bLift, 13f, YELLOW, 1)
    }

    /** Rocco rides Dash nice and slow... until Dash sees a butterfly. */
    private fun dashAndButterfly(gfx: Gfx, t: Float, viewW: Float, viewH: Float) {
        sunny(gfx, viewW, viewH, t)
        val cx = viewW / 2
        val pondX = cx + 150f
        gfx.oval(pondX - 62, G - 6, 124f, 24f, 0xFF3A8AD8.toInt())
        gfx.oval(pondX - 52, G - 4, 104f, 14f, 0xFF5AAAF0.toInt())
        val ds = 3f
        val dx = when {
            t < 2.4f -> cx - 190f + t * 40f
            t < 3.5f -> cx - 94f
            t < 4.2f -> cx - 94f + (t - 3.5f) * (t - 3.5f) * 1400f
            t < 4.8f -> -999f
            t < 5.8f -> -120f + (t - 4.8f) * (viewW + 240f)
            t < 6.2f -> -999f
            t < 6.6f -> -60f + (cx + 40f) * (1 - (1 - (t - 6.2f) / 0.4f).let { it * it })
            else -> cx - 20f
        }
        val zooming = t in 3.5f..6.2f
        val onScreen = dx > -900f
        val rs = 2.2f
        if (t < 3.5f) rocco(gfx, dx - 9f, G - 44f, 0, s = rs)
        if (onScreen) {
            // Speed lines (and dust when he skids to a stop)
            if (zooming) for (k in 0 until 5) gfx.rect(dx - 90f - k * 14f, G - 70f + k * 12f, 50f, 2f, 0xCCFFFFFF.toInt())
            if (t in 6.2f..6.9f) {
                for (k in 0 until 5) gfx.oval(dx - 30f - k * 12f, G - 12f - (k % 2) * 8f, 18f, 14f, 0xCCD8C8A8.toInt())
                gfx.shadowText("SCREEECH!", dx, 62f, 16f, WHITE, 1)
            }
            val happy = t > 8.4f
            Dino.drawBody(ScaledGfx(gfx, ds, dx, G - (if (happy) abs(sin(t * 6)) * 6f else 0f)), 0f, 0f, 1, if (zooming) t * 3f else t * 0.6f, true)
            if (t in 2.6f..3.5f) gfx.shadowText("!", dx + 14f, G - 110f, 22f, 0xFFFF3A3A.toInt(), 1)
            if (happy) for (k in 0 until 3) TeamArt.heart(gfx, dx + 20f + k * 14f, G - 110f - ((t * 30 + k * 12) % 40), 9f, 0xFFFF6A9A.toInt())
        }
        // Rocco: riding, then flapping behind like a flag, then flying into the pond.
        val sprite = TeamArt.rocco(Power.NONE, 2)
        when {
            t < 3.5f -> {}
            t < 4.2f -> gfx.sprite(sprite, dx - 70f, G - 106f, w = 16 * rs * 1.5f, h = 24 * rs * 0.75f)
            t in 4.8f..5.8f -> gfx.sprite(sprite, dx - 110f, G - 102f + sin(t * 30) * 5f, w = 16 * rs * 2.1f, h = 24 * rs * 0.55f)
            t in 6.2f..6.5f -> rocco(gfx, dx + 4f, G - 50f, 2, s = rs)
            t in 6.5f..7.6f -> {
                val k = (t - 6.5f) / 1.1f
                val x = cx - 16f + (pondX - cx + 16f) * k
                val y = G - 50f + (G + 18f - G + 50f) * k - sin(k * PI_F) * 100f
                rocco(gfx, x, y, 2, upside = (t * 8).toInt() % 2 == 0, s = rs)
                gfx.shadowText("WAHOOO!", x, maxOf(40f, y - 64f), 12f, WHITE, 1)
            }
            t >= 7.6f -> {
                rocco(gfx, pondX, G + 18f, 0, s = rs)
                if (t > 8.0f) {
                    // A lily pad hat
                    gfx.oval(pondX - 13, G - 38f, 26f, 8f, 0xFF3CB84A.toInt())
                    gfx.poly(floatArrayOf(pondX, pondX + 10, pondX + 4), floatArrayOf(G - 34f, G - 38f, G - 38f), 0xFF5AAAF0.toInt())
                }
            }
        }
        // The pond's front edge goes over Rocco's legs
        gfx.oval(pondX - 62, G - 4, 124f, 30f, 0xFF3A8AD8.toInt())
        gfx.oval(pondX - 50, G - 2, 100f, 14f, 0xFF5AAAF0.toInt())
        if (t in 7.6f..8.4f) {
            val f = (t - 7.6f) / 0.8f
            for (k in 0 until 10) gfx.oval(pondX + (k - 4.5f) * 10f * (0.4f + f) - 3, G - 8f - sin(f * PI_F) * (50f + (k % 3) * 16f), 6f, 8f, 0xDD8AD8FF.toInt())
            gfx.shadowText("SPLOOSH!", pondX, 60f, 18f, 0xFF8AD8FF.toInt(), 1)
        }
        if (t > 9.8f) for (k in 0 until 3) gfx.oval(pondX - 12 + k * 10f, G - 30f + ((t * 30 + k * 9) % 22), 3f, 5f, 0xDD8AD8FF.toInt())
        // The butterfly: flutters in, zooms ahead of Dash, then lands on his nose.
        val bfx: Float
        val bfy: Float
        when {
            t < 2.2f -> { bfx = -999f; bfy = 0f }
            t < 3.5f -> { val k = (t - 2.2f) / 1.3f; bfx = viewW + 20f + (cx - 30f - viewW - 20f) * k; bfy = 50f + k * 40f + sin(t * 6) * 10f }
            t < 4.2f -> { bfx = cx - 30f + (t - 3.5f) * 500f; bfy = 90f + sin(t * 6) * 10f }
            t in 4.8f..5.8f -> { bfx = dx + 110f; bfy = G - 100f + sin(t * 8) * 10f }
            t in 6.2f..7.4f -> { val k = (t - 6.2f) / 1.2f; bfx = viewW - 20f + (cx + 18f - viewW + 20f) * k; bfy = 60f + (G - 96f - 60f) * k + sin(t * 6) * 8f * (1 - k) }
            t >= 7.4f -> { bfx = cx + 18f; bfy = G - 96f - (if (t > 8.4f) abs(sin(t * 6)) * 6f else 0f) }
            else -> { bfx = -999f; bfy = 0f }
        }
        if (bfx > -900f) butterfly(gfx, bfx, bfy, t)
    }

    // ======================================================================== Stages

    private fun ground(gfx: Gfx, viewW: Float, viewH: Float, base: Int, top: Int, x: Float = 0f, w: Float = viewW) {
        gfx.rect(x, G, w, viewH - G, base)
        gfx.rect(x, G, w, 4f, top)
    }

    private fun cloud(gfx: Gfx, x: Float, y: Float) {
        gfx.oval(x, y, 54f, 16f, 0xFFF8FCFF.toInt())
        gfx.oval(x + 12, y - 9, 30f, 20f, 0xFFF8FCFF.toInt())
    }

    private fun sunny(gfx: Gfx, viewW: Float, viewH: Float, t: Float) {
        gfx.gradient(0f, 0f, viewW, G, 0xFF4AA4F0.toInt(), 0xFFCDEFFF.toInt())
        gfx.oval(viewW - 78, 32f, 36f, 36f, 0xFFFFE87A.toInt())
        // Rolling hills far away, and slow clouds
        gfx.oval(-80f, G - 54, 300f, 130f, 0xFF8AD276.toInt())
        gfx.oval(viewW * 0.45f, G - 42, 340f, 120f, 0xFF7CCB6A.toInt())
        for (i in 0 until 3) cloud(gfx, ((t * 9 + i * 170f) % (viewW + 120f)) - 60f, 40f + i * 18f)
        ground(gfx, viewW, viewH, 0xFF4AA83A.toInt(), 0xFF6ACB5A.toInt())
        for (i in 0 until 9) {
            val fx = (i * 53f + 17f) % viewW
            val fy = G + 12f + (i * 13 % 3) * 9f
            gfx.oval(fx - 3, fy - 3, 6f, 6f, FLOWERS[i % FLOWERS.size])
            gfx.oval(fx - 1, fy - 1, 2f, 2f, YELLOW)
        }
    }

    private fun night(gfx: Gfx, viewW: Float, viewH: Float, t: Float) {
        gfx.gradient(0f, 0f, viewW, G, 0xFF0A0E2A.toInt(), 0xFF2E2A5E.toInt())
        for (k in 0 until 26) {
            val a = if ((t * 2 + k).toInt() % 5 == 0) 110 else 255
            gfx.rect((k * 61f + 13f) % viewW, 28f + (k * 37f) % 120f, 1.6f, 1.6f, argb(a, 0xFFFFFF))
        }
        // A crescent moon
        gfx.oval(viewW - 84, 34f, 30f, 30f, 0xFFF4F0D0.toInt())
        gfx.oval(viewW - 76, 30f, 30f, 30f, mixColor(0xFF0A0E2A.toInt(), 0xFF2E2A5E.toInt(), 0.2f))
        // Pine trees
        for (k in 0 until 6) {
            val x = k * 90f + 20f
            val h = 70f + (k % 3) * 18f
            gfx.poly(floatArrayOf(x - 24, x, x + 24), floatArrayOf(G, G - h, G), 0xFF12223A.toInt())
        }
        ground(gfx, viewW, viewH, 0xFF1E3A22.toInt(), 0xFF2E5A32.toInt())
    }

    private fun wonderStage(gfx: Gfx, viewW: Float, viewH: Float, t: Float) {
        gfx.gradient(0f, 0f, viewW, G, 0xFF6A3AC8.toInt(), 0xFFFFA8E0.toInt())
        // Floating Wonder bubbles (see-through white with a shine: colored ones look muddy on purple)
        for (i in 0 until 10) {
            val bx = (i * 47f + sin(t + i) * 10f) % viewW
            val by = G - ((t * 18f + i * 37f) % (G - 20f))
            val r = 6f + (i % 3) * 4f
            gfx.oval(bx - r, by - r, r * 2, r * 2, 0x40FFFFFF)
            gfx.oval(bx - r * 0.55f, by - r * 0.6f, r * 0.5f, r * 0.4f, 0xB0FFFFFF.toInt())
        }
        ground(gfx, viewW, viewH, 0xFF3AB86A.toInt(), 0xFF7AE89A.toInt())
        // Polka-dot Wonder flowers
        for (i in 0 until 7) {
            val fx = (i * 67f + 30f) % viewW
            val sway = sin(t * 3 + i) * 2f
            gfx.rect(fx - 1, G - 14, 2f, 14f, 0xFF2E8A3A.toInt())
            gfx.oval(fx - 7 + sway, G - 24, 14f, 12f, FLOWERS[i % FLOWERS.size])
            gfx.oval(fx - 2 + sway, G - 20, 4f, 4f, WHITE)
        }
    }

    private fun concertStage(gfx: Gfx, viewW: Float, viewH: Float, t: Float) {
        gfx.gradient(0f, 0f, viewW, G, 0xFF120822.toInt(), 0xFF3A1A5A.toInt())
        // Disco lights on the back wall
        for (i in 0 until 10) gfx.oval(viewW * ((i * 0.11f + t * 0.12f) % 1f) - 10, 36f + (i * 29f) % 90f, 20f, 20f, argb(90, solidRainbow(t * 0.5f + i * 0.1f)))
        // A row of light bulbs, and red curtains on both sides
        for (i in 0 until (viewW / 24f).toInt() + 1) gfx.oval(i * 24f + 6, 24f, 7f, 7f, if ((i + (t * 4).toInt()) % 2 == 0) YELLOW else 0xFFFF8A2A.toInt())
        for (side in 0 until 2) {
            val x = if (side == 0) 0f else viewW - 28f
            gfx.rect(x, 22f, 28f, G - 22f, 0xFFB8202A.toInt())
            for (k in 0 until 3) gfx.rect(x + 4 + k * 8f, 22f, 2f, G - 22f, 0xFF8A1420.toInt())
        }
        ground(gfx, viewW, viewH, 0xFF3A2418.toInt(), 0xFF5A3A24.toInt())
    }

    private fun fence(gfx: Gfx, viewW: Float) {
        gfx.rect(0f, G - 44, viewW, 5f, 0xFFB89060.toInt())
        gfx.rect(0f, G - 18, viewW, 5f, 0xFFB89060.toInt())
        var x = 0f
        while (x < viewW) {
            gfx.rect(x + 2, G - 54, 16f, 54f, 0xFFE0BC88.toInt())
            gfx.poly(floatArrayOf(x + 2, x + 10, x + 18), floatArrayOf(G - 54, G - 62, G - 54), 0xFFE0BC88.toInt())
            x += 22f
        }
    }

    // ======================================================================== Characters and props

    private const val G = 196f
    private const val PI_F = 3.1416f
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val YELLOW = 0xFFFFE14D.toInt()
    private const val RED = 0xFFE52521.toInt()
    private const val BROWN = 0xFF8A5A2A.toInt()
    /** How much bigger Dennis is in the scenes. */
    private const val DS = 2.4f
    /** Where the mud sticks to Dennis in BATH TIME (pixels of his art, facing right): back, legs, tail and head. */
    private val MUD_SPOTS = listOf(7f to 10f, 11f to 13f, 15f to 11f, 6f to 14f, 17f to 14f, 3f to 6f, 14f to 3f)
    private val FLOWERS = intArrayOf(0xFFFF6A9A.toInt(), 0xFFFFFFFF.toInt(), 0xFFAA6AE8.toInt(), 0xFFFF8A2A.toInt())
    /** Mario-style growing (big, small, bigger...) and shrinking. */
    private val GROW = floatArrayOf(4.5f, 3f, 6f, 4.5f, 7.6f, 6f, 7.6f)
    private val SHRINK = floatArrayOf(4f, 7.6f, 2f, 4f, 1f, 2f, 1f)
    /** When each block of THE BLOCK TOWER goes on (each one makes a sound). */
    private val BLOCK_TIMES = floatArrayOf(0.9f, 1.3f, 1.7f, 2.1f, 2.5f, 2.9f, 3.3f)
    /** Where the tower's blocks land around Rocco: (x, y, turn). */
    private val PILE = listOf(
        Triple(-34f, 0f, 0.12f), Triple(-12f, 0f, -0.2f), Triple(10f, 0f, 0.3f), Triple(32f, 0f, 0.05f),
        Triple(-26f, -20f, 0.5f), Triple(22f, -19f, -0.35f), Triple(54f, 0f, 0.6f),
    )

    private fun smooth(k: Float) = k * k * (3 - 2 * k)

    private fun rocco(gfx: Gfx, x: Float, ground: Float, frame: Int = 0, flip: Boolean = false, upside: Boolean = false, lift: Float = 0f, s: Float = 3f) =
        gfx.sprite(TeamArt.rocco(Power.NONE, frame), x - 8 * s, ground - 24 * s - lift, flipX = flip, flipY = upside, w = 16 * s, h = 24 * s)

    private fun blocky(gfx: Gfx, x: Float, ground: Float, walking: Boolean = false, upside: Boolean = false, lift: Float = 0f, s: Float = 2.5f) =
        gfx.sprite(TeamArt.blocky(Power.NONE, walking), x - 8 * s, ground - 26 * s - lift, flipY = upside, w = 16 * s, h = 26 * s)

    /** A Pokemon with its feet at ([x], [ground]); [happy] ones bounce their feet (no glow: it looks muddy this big). */
    private fun mon(gfx: Gfx, m: Mon, x: Float, ground: Float, t: Float, facing: Int, happy: Boolean) =
        TeamArt.mon(ScaledGfx(gfx, 2.3f, x, ground), m, 0f, 0f, t, facing, false, happy)

    private fun spotty(gfx: Gfx, x: Float, ground: Float, t: Float, running: Boolean = false, lift: Float = 0f, s: Float = 2.4f) =
        Spotty.draw(ScaledGfx(gfx, s, x - 7f * s, ground - 20f * s - lift), 0f, 0f, t, running, false)

    /**
     * Dennis, [DS] times his size in the levels, with his left side at [x] and his paws on [ground] (the same
     * drawing as everywhere else): [running] swaps his legs as [t] ticks on, standing still he wags his tail,
     * and [lift] (in screen pixels) makes him leap.
     */
    private fun dennis(gfx: Gfx, x: Float, ground: Float, t: Float, running: Boolean = false, lift: Float = 0f, shadow: Boolean = true) =
        Dennis.draw(ScaledGfx(gfx, DS, x, ground), 0f, 0f, t, running = running, lift = lift / DS, shadow = shadow)

    /** King Krag standing with his feet at ([cx], [ground]), [s] times his size in the levels. */
    private fun krag(g: TeamGame, gfx: Gfx, cx: Float, ground: Float, s: Float, facing: Int = -1, wonder: Boolean = false, dry: Boolean = false, mouth: Boolean = false) {
        val k = KingKrag(0f, 40f, wonder, dry)
        k.facing = facing
        if (mouth) k.mouth = 1f
        k.draw(g, ScaledGfx(gfx, s, cx - (k.x + k.w / 2) * s, ground - 40f * s), 0f)
    }

    // Where King Krag's open mouth is (his fire comes out of there), for the way [krag] draws him.
    private fun kragMouthX(cx: Float, s: Float, facing: Int, big: Boolean) = cx + facing * 30f * s * KingKrag.scaleOf(big)
    private fun kragMouthY(ground: Float, s: Float, big: Boolean) = ground - 27.5f * s * KingKrag.scaleOf(big)

    /** Krag Jr. in his clown car, centered on [cx] with the top of his hair at [top]. */
    private fun kragJr(g: TeamGame, gfx: Gfx, cx: Float, top: Float, s: Float, wonder: Boolean = false) =
        KragJr(0f, 34f, wonder).draw(g, ScaledGfx(gfx, s, cx - 15f * s, top + 8f * s), 0f)

    /**
     * King Krag (or Krag Jr.) turned into a big floppy fish by his own Wonder magic: his hair, horns and angry
     * eyebrows are still on. Centered at ([x], [y]), turned by [ang], looking left if [face] is -1.
     */
    private fun kragFish(gfx: Gfx, x: Float, y: Float, s: Float, ang: Float, face: Int, wonder: Boolean, junior: Boolean, t: Float) {
        val tg = TurnGfx(gfx, x, y, ang)
        fishShapes(SilhouetteGfx(tg, 1f), x, y, s, face, wonder, junior, t)
        fishShapes(tg, x, y, s, face, wonder, junior, t)
    }

    private fun fishShapes(gfx: Gfx, x: Float, y: Float, s: Float, face: Int, wonder: Boolean, junior: Boolean, t: Float) {
        val f = face.toFloat()
        fun o(lx: Float, ly: Float, w: Float, h: Float, c: Int) = gfx.oval(if (f < 0) x + lx * s else x - (lx + w) * s, y + ly * s, w * s, h * s, c)
        fun p(xs: FloatArray, ys: FloatArray, c: Int) = gfx.poly(FloatArray(xs.size) { x - f * xs[it] * s }, FloatArray(ys.size) { y + ys[it] * s }, c)
        val skin = if (wonder) 0xFF5AD05A.toInt() else 0xFFF2B33D.toInt()
        val belly = if (wonder) 0xFFD8F5B0.toInt() else 0xFFFFE8A0.toInt()
        val shell = if (wonder) 0xFF8A3AD8.toInt() else 0xFF2E9E3A.toInt()
        val hair = if (wonder) 0xFFC060FF.toInt() else 0xFFE53A1E.toInt()
        val horn = 0xFFFFF2C8.toInt()
        val wag = sin(t * 22) * 2f
        // Tail, body, shell patch with spikes, belly and fin
        p(floatArrayOf(18f, 32f, 27f, 32f), floatArrayOf(0f, -13f + wag, 0f, 13f + wag), shell)
        o(-22f, -13f, 44f, 26f, skin)
        o(-6f, -13f, 22f, 12f, shell)
        for (i in 0 until 3) p(floatArrayOf(-2f + i * 7, 1f + i * 7, 4f + i * 7), floatArrayOf(-11f, -17f, -11f), WHITE)
        o(-18f, 1f, 34f, 11f, belly)
        p(floatArrayOf(-2f, 9f, 3f), floatArrayOf(4f, 6f + wag, 13f), shell)
        // A big googly eye and a grumpy eyebrow
        o(-16f, -9f, 10f, 10f, WHITE)
        o(-14f, -6f, 4f, 5f, if (wonder) 0xFF8A3AD8.toInt() else 0xFF101010.toInt())
        p(floatArrayOf(-18f, -5f, -5f), floatArrayOf(-10f, -12f, -8f), hair)
        // Fishy lips going "blub"
        val blub = 4f + abs(sin(t * 8)) * 2f
        o(-26f, -3f, 8f, 8f, 0xFFFF7AA8.toInt())
        o(-25f, 1f - blub / 2, 4f, blub, 0xFF8A1010.toInt())
        // King Krag's hair and horns are still on!
        if (junior) {
            p(floatArrayOf(-8f, -3f, 2f), floatArrayOf(-11f, -20f, -11f), hair)
            p(floatArrayOf(-13f, -15f, -10f), floatArrayOf(-11f, -16f, -12f), horn)
            // His bib, with the scary mouth drawn on it
            p(floatArrayOf(-20f, -10f, -15f), floatArrayOf(5f, 5f, 12f), WHITE)
        } else {
            p(floatArrayOf(-10f, -5f, -2f, 2f, 5f, 10f, 4f), floatArrayOf(-11f, -21f, -13f, -22f, -12f, -18f, -10f), hair)
            p(floatArrayOf(-15f, -19f, -11f), floatArrayOf(-10f, -18f, -12f), horn)
            p(floatArrayOf(-3f, -1f, 2f), floatArrayOf(-13f, -21f, -13f), horn)
        }
    }

    /** Rocco's hand mirror (a magic one: Wonder zaps bounce right off it), held out from his hand. */
    private fun mirror(gfx: Gfx, rx: Float, x: Float, y: Float, t: Float) {
        stick(gfx, rx + 16f, G - 33f, x - 2f, y + 14f, 4f, BROWN)
        gfx.oval(x - 12, y - 18, 24f, 36f, 0xFFE8C040.toInt())
        gfx.oval(x - 9, y - 15, 18f, 30f, 0xFFCFF4FF.toInt())
        gfx.poly(floatArrayOf(x - 4, x, x + 4, x), floatArrayOf(y - 10, y - 12, y + 2, y + 4), WHITE)
        if ((t * 6).toInt() % 3 == 0) star(gfx, x + 10, y - 16, 5f, WHITE)
    }

    /** A rainbow zap from (x0, y0) toward (x1, y1); [k] is how far it has got (0 to 1). */
    private fun beam(gfx: Gfx, x0: Float, y0: Float, x1: Float, y1: Float, k: Float, t: Float) {
        val n = 16
        val far = k.coerceIn(0f, 1f)
        for (i in 0..(far * n).toInt()) {
            val f = i / n.toFloat()
            val r = 7f + (i % 2) * 2f
            gfx.oval(x0 + (x1 - x0) * f - r, y0 + (y1 - y0) * f + sin(t * 30 + i) * 2f - r, r * 2, r * 2, argb(220, solidRainbow(t * 2 + i * 0.07f)))
        }
        star(gfx, x0 + (x1 - x0) * far, y0 + (y1 - y0) * far, 10f, WHITE)
    }

    private fun star(gfx: Gfx, x: Float, y: Float, r: Float, color: Int) {
        val xs = FloatArray(8)
        val ys = FloatArray(8)
        for (i in 0 until 8) {
            val a = i * 0.7854f - 1.5708f
            val rr = if (i % 2 == 0) r else r * 0.38f
            xs[i] = x + cos(a) * rr
            ys[i] = y + sin(a) * rr
        }
        gfx.poly(xs, ys, color)
    }

    /** A cartoon POOF cloud: [k] goes from 0 (just popped) to 1 (gone). */
    private fun puff(gfx: Gfx, x: Float, y: Float, k: Float, size: Float) {
        val a = (230 * (1f - k)).toInt().coerceIn(0, 255)
        for (i in 0 until 8) {
            val ang = i * 0.785f
            val r = (12f + k * 34f) * size
            val d = (22f - k * 8f) * size
            gfx.oval(x + cos(ang) * r - d / 2, y + sin(ang) * r * 0.75f - d / 2, d, d, argb(a, 0xFFFFFF))
        }
        if (k < 0.5f) gfx.shadowText("POOF!", x, y - 26f * size, 16f, WHITE, 1)
    }

    /** A thick line (sticks, handles, noodles...). */
    private fun stick(gfx: Gfx, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
        val nx = -dy / len * w / 2
        val ny = dx / len * w / 2
        gfx.poly(floatArrayOf(x0 + nx, x1 + nx, x1 - nx, x0 - nx), floatArrayOf(y0 + ny, y1 + ny, y1 - ny, y0 - ny), color)
    }

    private fun campfire(gfx: Gfx, x: Float, t: Float, lit: Boolean) {
        for (i in 0 until 5) gfx.oval(x - 32 + i * 13f, G - 8, 14f, 10f, 0xFF6A6A78.toInt())
        stick(gfx, x - 24, G - 4, x + 20, G - 16, 8f, 0xFF6A3A1A.toInt())
        stick(gfx, x - 20, G - 16, x + 24, G - 4, 8f, 0xFF7A4A24.toInt())
        if (!lit) return
        // Spooky blue flames (Dry Bones fire)
        for (i in 0 until 4) {
            val h = 34f + (i % 2) * 10f + sin(t * 13 + i * 1.7f) * 4f
            gfx.oval(x - 20 + i * 9f, G - 10 - h, 14f, h, 0xFF2E7BFF.toInt())
        }
        for (i in 0 until 3) {
            val h = 20f + sin(t * 17 + i) * 3f
            gfx.oval(x - 14 + i * 9f, G - 12 - h, 9f, h, 0xFF9FE0FF.toInt())
        }
    }

    private fun blueFlame(gfx: Gfx, x: Float, y: Float, s: Float) {
        gfx.oval(x - 9 * s, y - 6 * s, 18f * s, 12f * s, 0xFF2E7BFF.toInt())
        gfx.oval(x - 5 * s, y - 3 * s, 10f * s, 6f * s, 0xFF9FE0FF.toInt())
    }

    /** A burnt face: black soot with two blinking white eyes. */
    private fun sootFace(gfx: Gfx, x: Float, y: Float, w: Float, t: Float) {
        gfx.oval(x - w / 2, y - w * 0.35f, w, w * 0.7f, 0xCC1A1A1A.toInt())
        val blink = (t * 2).toInt() % 3 == 0 && (t * 2) % 1f < 0.2f
        for (side in 0 until 2) {
            val ex = x - w * 0.22f + side * w * 0.3f
            if (blink) gfx.rect(ex, y - 1, 6f, 2f, WHITE)
            else { gfx.oval(ex, y - 4, 6f, 7f, WHITE); gfx.oval(ex + 2, y - 2, 2.5f, 3f, 0xFF101010.toInt()) }
        }
    }

    private fun bone(gfx: Gfx, x: Float, y: Float, len: Float) {
        gfx.rect(x - len / 2 + 3, y - 2, len - 6, 4f, 0xFFF0F0E0.toInt())
        for (end in 0 until 2) {
            val ex = if (end == 0) x - len / 2 else x + len / 2 - 6
            gfx.oval(ex, y - 5, 6f, 5f, 0xFFF0F0E0.toInt())
            gfx.oval(ex, y, 6f, 5f, 0xFFF0F0E0.toInt())
        }
    }

    /** Dry Bones King Krag in pieces: a pile of bones, his shell, and his skull (which can hop around). */
    private fun bonePile(gfx: Gfx, x: Float, ground: Float, skullX: Float, skullUp: Float, missing: Boolean, t: Float) {
        gfx.oval(x - 46, ground - 22, 92f, 26f, 0xFF4A4A44.toInt())
        gfx.oval(x - 40, ground - 26, 60f, 22f, 0xFF7A7A70.toInt())
        for (i in 0 until 5) {
            if (missing && i == 2) continue
            bone(TurnGfx(gfx, x - 30 + i * 15f, ground - 8f, -0.5f + i * 0.3f), x - 30 + i * 15f, ground - 8f - (i % 2) * 6f, 28f)
        }
        val sy = ground - 26f - skullUp
        gfx.oval(skullX - 16, sy - 12, 32f, 26f, 0xFFF0F0E0.toInt())
        gfx.rect(skullX - 10, sy + 10, 20f, 6f, 0xFFF0F0E0.toInt())
        for (side in 0 until 2) {
            val ex = skullX - 10 + side * 12f
            gfx.oval(ex, sy - 4, 8f, 9f, 0xFF1A1A1A.toInt())
            gfx.oval(ex + 2.5f, sy - 1, 3f, 3f, 0xFFFF3A3A.toInt())
        }
        for (k in 0 until 4) gfx.rect(skullX - 8 + k * 5f, sy + 10, 1.5f, 6f, 0xFF4A4A44.toInt())
        // Little horns
        gfx.poly(floatArrayOf(skullX - 12, skullX - 16, skullX - 6), floatArrayOf(sy - 8, sy - 20, sy - 11), 0xFFFFF2C8.toInt())
        gfx.poly(floatArrayOf(skullX + 6, skullX + 14, skullX + 10), floatArrayOf(sy - 11, sy - 20, sy - 8), 0xFFFFF2C8.toInt())
        if (sin(t * 3) > 0.8f) gfx.shadowText("rattle", skullX, sy - 24f, 8f, WHITE, 1)
    }

    private fun mushroom(gfx: Gfx, x: Float, y: Float, size: Float, cap: Int) {
        gfx.rect(x - size * 0.3f, y, size * 0.6f, size * 0.5f, 0xFFF4E8D0.toInt())
        gfx.rect(x - size * 0.14f, y + size * 0.1f, size * 0.07f, size * 0.2f, 0xFF101010.toInt())
        gfx.rect(x + size * 0.07f, y + size * 0.1f, size * 0.07f, size * 0.2f, 0xFF101010.toInt())
        gfx.oval(x - size / 2, y - size * 0.45f, size, size * 0.7f, cap)
        gfx.oval(x - size * 0.3f, y - size * 0.35f, size * 0.24f, size * 0.22f, WHITE)
        gfx.oval(x + size * 0.08f, y - size * 0.4f, size * 0.24f, size * 0.22f, WHITE)
    }

    /** Rocco's red cap (flying off his head). */
    private fun cap(gfx: Gfx, x: Float, y: Float) {
        gfx.oval(x - 15, y - 6, 30f, 14f, RED)
        gfx.rect(x - 2, y + 4, 22f, 4f, RED)
        gfx.oval(x - 4, y - 3, 8f, 7f, WHITE)
        gfx.text("R", x, y + 3, 6f, RED, 1)
    }

    /** Frizzy zapped hair: a big black puff, smoking a little. Centered on [x], sitting on [top]. */
    private fun afro(gfx: Gfx, x: Float, top: Float, w: Float, t: Float) {
        val d = w * 0.42f
        for ((ox, oy) in AFRO) gfx.oval(x + ox * w - d / 2, top + oy * w - d / 2, d, d, 0xFF2A1E18.toInt())
        gfx.oval(x - w * 0.2f, top - w * 0.36f, w * 0.18f, w * 0.12f, 0xFF4A3A30.toInt())
        for (k in 0 until 2) {
            val sy = (t * 20 + k * 15) % 30
            gfx.oval(x - 6 + k * 10f, top - w * 0.5f - sy, 7f, 7f, argb(140 - sy.toInt() * 4, 0x9A9A9A))
        }
    }
    private val AFRO = listOf(-0.42f to -0.02f, -0.26f to -0.26f, 0f to -0.34f, 0.26f to -0.26f, 0.42f to -0.02f, -0.18f to -0.06f, 0.18f to -0.06f)

    /** A zig-zag lightning bolt. */
    private fun bolt(gfx: Gfx, x0: Float, y0: Float, x1: Float, y1: Float, t: Float) {
        var px = x0
        var py = y0
        for (i in 1..5) {
            val f = i / 5f
            val jig = if (i < 5) sin(t * 37 + i * 2.3f) * 9f else 0f
            val nx = x0 + (x1 - x0) * f + jig
            val ny = y0 + (y1 - y0) * f - jig * 0.5f
            stick(gfx, px, py, nx, ny, 4f, YELLOW)
            stick(gfx, px, py, nx, ny, 1.5f, WHITE)
            px = nx
            py = ny
        }
    }

    private fun camera(gfx: Gfx, x: Float, ground: Float) {
        stick(gfx, x, ground - 44, x - 14, ground, 2.5f, 0xFF3A3A44.toInt())
        stick(gfx, x, ground - 44, x + 14, ground, 2.5f, 0xFF3A3A44.toInt())
        stick(gfx, x, ground - 44, x, ground, 2.5f, 0xFF3A3A44.toInt())
        gfx.rect(x - 14, ground - 62, 28f, 20f, 0xFF2A2A34.toInt())
        gfx.rect(x - 8, ground - 66, 10f, 5f, 0xFF2A2A34.toInt())
        gfx.oval(x + 6, ground - 60, 16f, 16f, 0xFF5A5A6A.toInt())
        gfx.oval(x + 9, ground - 57, 10f, 10f, 0xFF8AD0FF.toInt())
        gfx.rect(x - 11, ground - 59, 6f, 4f, 0xFFE84A4A.toInt())
    }

    /** A checked brown detective hat, over Rocco's cap (whose top is at [top]). */
    private fun detectiveHat(gfx: Gfx, x: Float, top: Float) {
        gfx.oval(x - 16, top - 6, 36f, 16f, 0xFF8A6A3A.toInt())
        gfx.poly(floatArrayOf(x + 16, x + 28, x + 16), floatArrayOf(top + 2, top + 6, top + 8), 0xFF8A6A3A.toInt())
        gfx.poly(floatArrayOf(x - 14, x - 24, x - 14), floatArrayOf(top + 2, top + 6, top + 8), 0xFF8A6A3A.toInt())
        for (k in 0 until 4) gfx.rect(x - 10 + k * 7f, top - 4, 2f, 12f, 0xFF6A4A24.toInt())
        gfx.rect(x - 14, top + 1, 30f, 2f, 0xFF6A4A24.toInt())
    }

    /** One building block, top-left at ([x], [y]). Kinds: grass, dirt, stone, planks, bricks, gold, diamond, a tiny pink one. */
    private fun block(gfx: Gfx, x: Float, y: Float, s: Float, kind: Int) {
        val k = kind % BLOCKS.size
        val (main, dark) = BLOCKS[k]
        gfx.rect(x, y, s, s, 0xFF2A1E14.toInt())
        gfx.rect(x + 1.5f, y + 1.5f, s - 3f, s - 3f, main)
        when (k) {
            0 -> { gfx.rect(x + 1.5f, y + 1.5f, s - 3f, s * 0.3f, 0xFF4AB83A.toInt()); for (i in 0 until 3) gfx.rect(x + 3 + i * s * 0.28f, y + s * 0.3f, 2f, 3f, 0xFF4AB83A.toInt()) }
            3 -> for (i in 1 until 3) gfx.rect(x + 1.5f, y + i * s / 3f, s - 3f, 1.2f, dark)
            4 -> { for (i in 1 until 3) gfx.rect(x + 1.5f, y + i * s / 3f, s - 3f, 1.2f, dark); gfx.rect(x + s / 2, y + 1.5f, 1.2f, s / 3f, dark); gfx.rect(x + s / 4, y + s / 3f, 1.2f, s / 3f, dark) }
            5, 6 -> gfx.poly(floatArrayOf(x + 3, x + s * 0.5f, x + 3), floatArrayOf(y + 3, y + 3, y + s * 0.5f), 0xAAFFFFFF.toInt())
            else -> for (i in 0 until 3) gfx.rect(x + 3 + (i * 7f) % (s - 7f), y + 4 + (i * 5f) % (s - 8f), 2.5f, 2.5f, dark)
        }
    }
    private val BLOCKS = listOf(
        0xFF8A5A2A.toInt() to 0xFF6A4220.toInt(), 0xFF8A5A2A.toInt() to 0xFF6A4220.toInt(), 0xFF8A8A8A.toInt() to 0xFF6A6A6A.toInt(),
        0xFFC8904A.toInt() to 0xFF8A5A2A.toInt(), 0xFFB84A3A.toInt() to 0xFFE8D8C8.toInt(), 0xFFFFD21F.toInt() to 0xFFC89A10.toInt(),
        0xFF6AE8E8.toInt() to 0xFF3AB8C8.toInt(), 0xFFFF8AC8.toInt() to 0xFFE85AA8.toInt(),
    )

    private fun butterfly(gfx: Gfx, x: Float, y: Float, t: Float) {
        val flap = 0.3f + abs(sin(t * 18)) * 0.7f
        gfx.oval(x - 12 * flap, y - 10, 12f * flap, 11f, 0xFFFF6AC8.toInt())
        gfx.oval(x, y - 10, 12f * flap, 11f, 0xFFFF6AC8.toInt())
        gfx.oval(x - 9 * flap, y, 9f * flap, 8f, 0xFFAA6AE8.toInt())
        gfx.oval(x, y, 9f * flap, 8f, 0xFFAA6AE8.toInt())
        gfx.rect(x - 1, y - 9, 2f, 16f, 0xFF2A1A34.toInt())
        stick(gfx, x, y - 9, x - 4, y - 15, 1f, 0xFF2A1A34.toInt())
        stick(gfx, x, y - 9, x + 4, y - 15, 1f, 0xFF2A1A34.toInt())
    }
}
