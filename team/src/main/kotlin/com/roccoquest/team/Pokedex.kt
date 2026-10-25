package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.mixColor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 50 newest Pokemon. Instead of a hand drawing each, every one is built from parts: a body shape,
 * ears, a tail and a few extras (cheeks, a shell, a collar...), in its own colors.
 */
object Pokedex {
    enum class Body { BIPED, QUAD, BIRD, BLOB, DINO, SERPENT }
    enum class Ears { NONE, POINTY, BLACKTIP, CAT, ROUND, BIG, LONG, FLOP, FIN, FINWHITE, LEAF, SPROUT, CREST, DIAMOND, GILLS }
    enum class Tail { NONE, THIN, FLAME, LEAF, BOLT, FLUFFY, FIN, CURL, BALL, STAR, SPLIT, LONG }
    enum class X {
        BACKFIRE, BANDAGE, BEAKRED, BEAKYELLOW, BELLY, BONE, BOWTIE, CHEEKS, CLOWNNOSE, COLLAR, DIAMONDS, EARFUR,
        FLAMEHAT, FLOWERMASK, GEM, MASK, MOON, ROCKCOLLAR, SHELL, SKULL, SNOUT, SPIKES, STICK, STRIPE, STRIPES,
        TEETH, TRIANGLES, TUFT, TUSKS, WHISKERS, WINGFINS, WOOL,
    }

    class Spec(val main: Int, val belly: Int, val accent: Int, val body: Body, val ears: Ears, val tail: Tail, val extras: Set<X>, val base: Mon)

    val specs: Map<Mon, Spec> by lazy { mapOf(
        Mon.LEAFLING to Spec(0xFF8CD86A.toInt(), 0xFFF4F0C8.toInt(), 0xFF3AA84A.toInt(), Body.QUAD, Ears.LEAF, Tail.NONE, setOf(X.COLLAR), Mon.BLOOM),
        Mon.FLAMEBACK to Spec(0xFF2A3A6A.toInt(), 0xFFF4E0A0.toInt(), 0xFFFF7A2A.toInt(), Body.QUAD, Ears.NONE, Tail.NONE, setOf(X.BACKFIRE, X.BELLY), Mon.EMBER),
        Mon.CHOMPODILE to Spec(0xFF4A8AE8.toInt(), 0xFFF4E0A0.toInt(), 0xFFE84A4A.toInt(), Body.DINO, Ears.NONE, Tail.FIN, setOf(X.BELLY, X.TEETH), Mon.SPLASHY),
        Mon.MUDKIPPY to Spec(0xFF5AA0E8.toInt(), 0xFFF4F4F4.toInt(), 0xFFF08A3A.toInt(), Body.QUAD, Ears.FIN, Tail.FIN, setOf(X.CHEEKS), Mon.SPLASHY),
        Mon.GECKLE to Spec(0xFF5AB85A.toInt(), 0xFFF0C8A0.toInt(), 0xFF2E8A3A.toInt(), Body.BIPED, Ears.NONE, Tail.LEAF, setOf(X.BELLY), Mon.BLOOM),
        Mon.CHIRPY to Spec(0xFFFF9A3A.toInt(), 0xFFFFE07A.toInt(), 0xFFE85A2A.toInt(), Body.BIRD, Ears.CREST, Tail.NONE, setOf(), Mon.EMBER),
        Mon.TURTLEAF to Spec(0xFF7AC85A.toInt(), 0xFFF4E09A.toInt(), 0xFF8A5A2A.toInt(), Body.QUAD, Ears.SPROUT, Tail.NONE, setOf(X.SHELL), Mon.BLOOM),
        Mon.MONKEYFIRE to Spec(0xFFF08A3A.toInt(), 0xFFF4E0B0.toInt(), 0xFFFF5A2A.toInt(), Body.BIPED, Ears.ROUND, Tail.FLAME, setOf(X.BELLY), Mon.EMBER),
        Mon.SNIVVY to Spec(0xFF5AB85A.toInt(), 0xFFF4E0A0.toInt(), 0xFFF4C83A.toInt(), Body.BIPED, Ears.NONE, Tail.LEAF, setOf(X.COLLAR), Mon.BLOOM),
        Mon.TEPPY to Spec(0xFFF08A3A.toInt(), 0xFFF4E0A0.toInt(), 0xFF2A2A2A.toInt(), Body.QUAD, Ears.ROUND, Tail.CURL, setOf(X.SNOUT), Mon.EMBER),
        Mon.OTTERSHELL to Spec(0xFFF4F4F4.toInt(), 0xFF8AC8F0.toInt(), 0xFF3A6AC8.toInt(), Body.BIPED, Ears.ROUND, Tail.NONE, setOf(X.SHELL, X.BELLY), Mon.SPLASHY),
        Mon.FENNEX to Spec(0xFFF4D86A.toInt(), 0xFFF4F0D8.toInt(), 0xFFFF6A2A.toInt(), Body.QUAD, Ears.BIG, Tail.FLUFFY, setOf(X.EARFUR), Mon.EMBER),
        Mon.HOOTLET to Spec(0xFFC8A06A.toInt(), 0xFFF4ECD8.toInt(), 0xFF3AA84A.toInt(), Body.BIRD, Ears.NONE, Tail.NONE, setOf(X.BOWTIE), Mon.BLOOM),
        Mon.LITTY to Spec(0xFF2A2A30.toInt(), 0xFFF4D8A0.toInt(), 0xFFE84A3A.toInt(), Body.QUAD, Ears.CAT, Tail.THIN, setOf(X.STRIPES), Mon.EMBER),
        Mon.SEALY to Spec(0xFF5AB0F0.toInt(), 0xFFF4F4F4.toInt(), 0xFFFF8AC0.toInt(), Body.QUAD, Ears.NONE, Tail.FIN, setOf(X.CLOWNNOSE, X.COLLAR), Mon.SPLASHY),
        Mon.GROOKY to Spec(0xFF6AC85A.toInt(), 0xFFF4E0A0.toInt(), 0xFF8A5A2A.toInt(), Body.BIPED, Ears.ROUND, Tail.THIN, setOf(X.STICK, X.BELLY), Mon.BLOOM),
        Mon.BUNBUN to Spec(0xFFF4F4F4.toInt(), 0xFFF4F4F4.toInt(), 0xFFFF8A3A.toInt(), Body.BIPED, Ears.LONG, Tail.FLUFFY, setOf(X.BANDAGE), Mon.EMBER),
        Mon.SOBBY to Spec(0xFF6AA8E8.toInt(), 0xFFF4F0C8.toInt(), 0xFFF4D84A.toInt(), Body.BIPED, Ears.FIN, Tail.THIN, setOf(), Mon.SPLASHY),
        Mon.KITTLEAF to Spec(0xFF7AC85A.toInt(), 0xFFF4F4E8.toInt(), 0xFFFF6A9A.toInt(), Body.QUAD, Ears.CAT, Tail.THIN, setOf(X.FLOWERMASK), Mon.BLOOM),
        Mon.CROCO to Spec(0xFFF05A3A.toInt(), 0xFFF4E0A0.toInt(), 0xFFFFD24A.toInt(), Body.DINO, Ears.NONE, Tail.THIN, setOf(X.FLAMEHAT, X.TEETH), Mon.EMBER),
        Mon.DUCKLY to Spec(0xFFF4F4F4.toInt(), 0xFFF4D86A.toInt(), 0xFF3A8AE8.toInt(), Body.BIRD, Ears.CREST, Tail.NONE, setOf(X.BEAKYELLOW), Mon.SPLASHY),
        Mon.PICHI to Spec(0xFFF4E04A.toInt(), 0xFFF4E04A.toInt(), 0xFF2A2A2A.toInt(), Body.BIPED, Ears.BLACKTIP, Tail.BOLT, setOf(X.CHEEKS), Mon.SPARKY),
        Mon.PACHI to Spec(0xFFF4F4F4.toInt(), 0xFFF4F4F4.toInt(), 0xFF4A8AE8.toInt(), Body.QUAD, Ears.ROUND, Tail.FLUFFY, setOf(X.CHEEKS, X.STRIPE), Mon.SPARKY),
        Mon.SHINXY to Spec(0xFF4A8AE8.toInt(), 0xFF2A2A3A.toInt(), 0xFFF4C83A.toInt(), Body.QUAD, Ears.CAT, Tail.STAR, setOf(), Mon.SPARKY),
        Mon.SHEEPZAP to Spec(0xFFF4F0D8.toInt(), 0xFFF4D84A.toInt(), 0xFF3A5AC8.toInt(), Body.QUAD, Ears.NONE, Tail.BALL, setOf(X.WOOL), Mon.SPARKY),
        Mon.MORPO to Spec(0xFFF4D84A.toInt(), 0xFF2A2A30.toInt(), 0xFFE8A0C0.toInt(), Body.BIPED, Ears.ROUND, Tail.THIN, setOf(X.CHEEKS), Mon.SPARKY),
        Mon.YAMPY to Spec(0xFFF4D86A.toInt(), 0xFFF4F4F4.toInt(), 0xFFF08A3A.toInt(), Body.QUAD, Ears.FLOP, Tail.BOLT, setOf(), Mon.SPARKY),
        Mon.JOLTEO to Spec(0xFFF4D84A.toInt(), 0xFFF4F4F4.toInt(), 0xFFF4D84A.toInt(), Body.QUAD, Ears.BIG, Tail.NONE, setOf(X.SPIKES, X.COLLAR), Mon.SPARKY),
        Mon.FLAREO to Spec(0xFFF08A3A.toInt(), 0xFFF4E0A0.toInt(), 0xFFF4E0A0.toInt(), Body.QUAD, Ears.BIG, Tail.FLUFFY, setOf(X.COLLAR), Mon.EMBER),
        Mon.VAPEO to Spec(0xFF5AB0E8.toInt(), 0xFF5AB0E8.toInt(), 0xFF2A6AA8.toInt(), Body.QUAD, Ears.FIN, Tail.FIN, setOf(X.COLLAR), Mon.HYDRO),
        Mon.LEAFEO to Spec(0xFFF4E0A0.toInt(), 0xFFF4E0A0.toInt(), 0xFF5AB85A.toInt(), Body.QUAD, Ears.LEAF, Tail.LEAF, setOf(), Mon.BLOOM),
        Mon.GLACEO to Spec(0xFF8AD8F0.toInt(), 0xFF8AD8F0.toInt(), 0xFF3A8AC8.toInt(), Body.QUAD, Ears.DIAMOND, Tail.THIN, setOf(X.DIAMONDS), Mon.HYDRO),
        Mon.ESPEO to Spec(0xFFE8B8F0.toInt(), 0xFFE8B8F0.toInt(), 0xFFE84A6A.toInt(), Body.QUAD, Ears.BIG, Tail.SPLIT, setOf(X.GEM), Mon.MYSTIC),
        Mon.MARRY to Spec(0xFF4A8AE8.toInt(), 0xFFF4F4F4.toInt(), 0xFF4A8AE8.toInt(), Body.BLOB, Ears.ROUND, Tail.BALL, setOf(), Mon.SPLASHY),
        Mon.WOOPY to Spec(0xFF6AB8F0.toInt(), 0xFF6AB8F0.toInt(), 0xFFE86AA8.toInt(), Body.BLOB, Ears.GILLS, Tail.THIN, setOf(), Mon.SPLASHY),
        Mon.TEDDO to Spec(0xFFC8884A.toInt(), 0xFFC8884A.toInt(), 0xFFF4D84A.toInt(), Body.BIPED, Ears.ROUND, Tail.NONE, setOf(X.MOON), Mon.PUNCHO),
        Mon.MUNCHY to Spec(0xFF2A5A6A.toInt(), 0xFFF4E8C8.toInt(), 0xFF2A5A6A.toInt(), Body.BLOB, Ears.CAT, Tail.NONE, setOf(X.BELLY), Mon.PUNCHO),
        Mon.RIOLITTLE to Spec(0xFF4A8AE8.toInt(), 0xFF2A2A3A.toInt(), 0xFFF4D84A.toInt(), Body.BIPED, Ears.FLOP, Tail.THIN, setOf(X.MASK), Mon.PUNCHO),
        Mon.GIBBLE to Spec(0xFF3A6AA8.toInt(), 0xFFE87A5A.toInt(), 0xFFE87A5A.toInt(), Body.DINO, Ears.FIN, Tail.NONE, setOf(X.TEETH), Mon.BLAZE),
        Mon.SHARKCHOMP to Spec(0xFF2A4A8A.toInt(), 0xFFE87A5A.toInt(), 0xFFE85A3A.toInt(), Body.DINO, Ears.FIN, Tail.FIN, setOf(X.WINGFINS, X.TEETH), Mon.BLAZE),
        Mon.ZORRY to Spec(0xFF4A4A5A.toInt(), 0xFF4A4A5A.toInt(), 0xFFC8303A.toInt(), Body.QUAD, Ears.BIG, Tail.FLUFFY, setOf(X.TUFT), Mon.SPOOKY),
        Mon.AXEY to Spec(0xFF6AA84A.toInt(), 0xFF8AC86A.toInt(), 0xFFE84A3A.toInt(), Body.DINO, Ears.NONE, Tail.THIN, setOf(X.TUSKS), Mon.BLAZE),
        Mon.DEDDY to Spec(0xFFF0A04A.toInt(), 0xFFF4E0A0.toInt(), 0xFF2A2A2A.toInt(), Body.BIPED, Ears.BIG, Tail.THIN, setOf(X.WHISKERS, X.CHEEKS), Mon.SPARKY),
        Mon.WOOLY to Spec(0xFFF4F4F4.toInt(), 0xFF2A2A30.toInt(), 0xFFF4F4F4.toInt(), Body.BLOB, Ears.NONE, Tail.NONE, setOf(X.WOOL), Mon.PUNCHO),
        Mon.PECKY to Spec(0xFF2A2A30.toInt(), 0xFFF4F4F4.toInt(), 0xFFE84A3A.toInt(), Body.BIRD, Ears.CREST, Tail.NONE, setOf(X.BEAKRED), Mon.BLAZE),
        Mon.PUPROCK to Spec(0xFFC8A06A.toInt(), 0xFFF4E8C8.toInt(), 0xFF8A8A9A.toInt(), Body.QUAD, Ears.FLOP, Tail.THIN, setOf(X.ROCKCOLLAR), Mon.PUNCHO),
        Mon.BONEKID to Spec(0xFFC8A06A.toInt(), 0xFFF4E0A0.toInt(), 0xFFF4F4F4.toInt(), Body.BIPED, Ears.NONE, Tail.THIN, setOf(X.SKULL, X.BONE), Mon.PUNCHO),
        Mon.MEWLY to Spec(0xFFFFB8D8.toInt(), 0xFFFFB8D8.toInt(), 0xFFFF8AC0.toInt(), Body.BLOB, Ears.CAT, Tail.LONG, setOf(), Mon.MYSTIC),
        Mon.TOGEKISSY to Spec(0xFFF4F4F4.toInt(), 0xFFF4F4F4.toInt(), 0xFFE84A4A.toInt(), Body.BIRD, Ears.CREST, Tail.NONE, setOf(X.TRIANGLES), Mon.MYSTIC),
        Mon.DRATINY to Spec(0xFF8AB8F0.toInt(), 0xFFF4F4F4.toInt(), 0xFFF4F4F4.toInt(), Body.SERPENT, Ears.FINWHITE, Tail.NONE, setOf(), Mon.HYDRO),
    ) }

    private const val INK = 0xFF1A1420.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun draw(gfx: Gfx, m: Mon, cx: Float, bottom: Float, t: Float, facing: Int, walking: Boolean) {
        val s = specs[m] ?: return
        val f = facing.toFloat()
        val floaty = s.body == Body.BLOB || m == Mon.MEWLY || m == Mon.ESPEO
        val bob = if (floaty) sin(t * 3) * 1.5f - (if (m == Mon.MEWLY) 4f else 0f) else if (walking) abs(sin(t * 10)) * 1.2f else 0f
        val b = bottom - bob
        val step = if (walking) sin(t * 14) * 1.5f else 0f
        val dark = mixColor(s.main, INK, 0.35f)
        fun has(x: X) = x in s.extras

        // Where the head is (filled in by the body shape), and where the tail starts.
        var hx: Float; var hy: Float; var hw: Float; var hh: Float
        val tailX: Float; val tailY: Float
        when (s.body) {
            Body.BIPED -> { hw = 14f; hh = 13f; hx = cx - 7 + f; hy = b - 23; tailX = cx - f * 6; tailY = b - 7 }
            Body.QUAD -> { hw = 11f; hh = 10f; hx = cx + f * 6 - 5.5f; hy = b - 19; tailX = cx - f * 9; tailY = b - 10 }
            Body.BIRD -> { hw = 13f; hh = 11f; hx = cx - 6.5f + f * 1.5f; hy = b - 16; tailX = cx - f * 7; tailY = b - 8 }
            Body.BLOB -> { hw = 16f; hh = 15f; hx = cx - 8; hy = b - 16; tailX = cx - f * 8; tailY = b - 6 }
            Body.DINO -> { hw = 14f; hh = 11f; hx = cx + f * 3 - 7; hy = b - 26; tailX = cx - f * 5; tailY = b - 8 }
            Body.SERPENT -> { hw = 10f; hh = 9f; hx = cx + f * 8 - 5; hy = b - 17; tailX = cx - f * 12; tailY = b - 6 }
        }
        val hcx = hx + hw / 2

        // ---- Tail (behind everything)
        drawTail(gfx, s, tailX, tailY, f, t)
        // ---- Things on the back
        if (has(X.SHELL) && s.body != Body.BIPED) {
            gfx.oval(cx - f * 3 - 7, b - 16, 14f, 9f, s.accent)
            gfx.rect(cx - f * 3 - 5, b - 12, 10f, 1f, mixColor(s.accent, INK, 0.3f))
        }
        if (has(X.BACKFIRE)) for (k in 0 until 3) {
            val fl = sin(t * 12 + k) * 1.5f
            gfx.oval(cx - f * 2 - 6 + k * 4f, b - 18 - k % 2 * 2f + fl, 5f, 7f, 0xFFFF7A2A.toInt())
            gfx.oval(cx - f * 2 - 5 + k * 4f, b - 16 - k % 2 * 2f + fl, 3f, 4f, 0xFFFFE14D.toInt())
        }
        if (has(X.DIAMONDS)) for (k in 0 until 3) {
            val dx = cx - f * 2 - 5 + k * 4f
            gfx.poly(floatArrayOf(dx, dx + 1.5f, dx + 3f, dx + 1.5f), floatArrayOf(b - 13, b - 17, b - 13, b - 11), s.accent)
        }
        // ---- Body
        when (s.body) {
            Body.BIPED -> {
                gfx.oval(cx - 6 + step, b - 3, 5f, 3f, dark); gfx.oval(cx + 1 - step, b - 3, 5f, 3f, dark)
                gfx.oval(cx - 6, b - 12, 12f, 10f, s.main)
                if (has(X.BELLY) || s.belly != s.main) gfx.oval(cx - 4 + f, b - 10, 8f, 7f, s.belly)
                if (has(X.SHELL)) gfx.oval(cx - 3 + f * 1.5f, b - 9, 6f, 5f, 0xFFF4E0A0.toInt())
                gfx.oval(cx - 8, b - 10 + step * 0.3f, 4f, 4f, s.main); gfx.oval(cx + 4, b - 10 - step * 0.3f, 4f, 4f, s.main)
                if (has(X.WINGFINS)) for (sd in intArrayOf(-1, 1)) gfx.poly(floatArrayOf(cx + sd * 7, cx + sd * 12, cx + sd * 7), floatArrayOf(b - 12, b - 9, b - 6), s.accent)
            }
            Body.QUAD -> {
                for ((k, lx) in floatArrayOf(-7f, -3.5f, 2f, 5.5f).withIndex()) {
                    val sw = if (k % 2 == 0) step else -step
                    gfx.rect(cx + lx * f - 1.2f + sw * 0.5f, b - 5, 2.6f, 5f, if (k == 1 || k == 3) dark else s.main)
                }
                gfx.oval(cx - 9, b - 13, 18f, 9f, s.main)
                if (s.belly != s.main) gfx.oval(cx - 6, b - 8.5f, 12f, 4f, s.belly)
            }
            Body.BIRD -> {
                gfx.rect(cx - 3, b - 3, 1.5f, 3f, 0xFFF0A040.toInt()); gfx.rect(cx + 1.5f, b - 3, 1.5f, 3f, 0xFFF0A040.toInt())
                gfx.oval(cx - 7, b - 16, 14f, 14f, s.main)
                gfx.oval(cx - 4 + f * 1.5f, b - 11, 8f, 8f, s.belly)
                val flap = sin(t * (if (walking) 18 else 4)) * 2f
                gfx.oval(cx - f * 3 - 4, b - 11 + flap, 8f, 6f, mixColor(s.main, s.accent, 0.4f))
                if (has(X.TRIANGLES)) {
                    gfx.poly(floatArrayOf(cx - 3, cx - 1, cx - 2), floatArrayOf(b - 7, b - 7, b - 9), 0xFFE84A4A.toInt())
                    gfx.poly(floatArrayOf(cx + 1, cx + 3, cx + 2), floatArrayOf(b - 7, b - 7, b - 9), 0xFF4A8AE8.toInt())
                }
            }
            Body.BLOB -> {
                gfx.oval(cx - 6, b - 3, 4f, 3f, dark); gfx.oval(cx + 2, b - 3, 4f, 3f, dark)
                if (has(X.WOOL)) for (k in 0 until 8) {
                    val a = k * 0.785f
                    gfx.oval(cx - 4 + cos(a) * 7f, b - 12 + sin(a) * 6f, 8f, 8f, s.main)
                }
                gfx.oval(cx - 8, b - 16, 16f, 15f, s.main)
                if (has(X.BELLY)) gfx.oval(cx - 5, b - 9, 10f, 7f, s.belly)
            }
            Body.DINO -> {
                gfx.oval(cx - 6 + step, b - 5, 5f, 5f, dark); gfx.oval(cx + 1 - step, b - 5, 5f, 5f, dark)
                gfx.poly(floatArrayOf(cx - f * 3, cx - f * 14, cx - f * 3), floatArrayOf(b - 12, b - 4, b - 5), s.main)
                gfx.oval(cx - 7, b - 17, 14f, 13f, s.main)
                gfx.oval(cx - 4 + f * 2, b - 14, 8f, 9f, s.belly)
                gfx.oval(cx + f * 5 - 2, b - 13, 4f, 4f, s.main)
                if (has(X.WINGFINS)) for (sd in intArrayOf(-1, 1)) gfx.poly(floatArrayOf(cx + sd * 6, cx + sd * 13, cx + sd * 6), floatArrayOf(b - 16, b - 12, b - 8), s.main)
            }
            Body.SERPENT -> {
                for (i in 0 until 6) {
                    val sx = cx - f * (12 - i * 3.5f)
                    val sy = b - 6 - sin(t * 4 + i) * 2f
                    val r = 5f + i * 0.4f
                    gfx.oval(sx - r, sy - r, r * 2, r * 2, s.main)
                    gfx.oval(sx - r * 0.6f, sy, r * 1.2f, r * 0.8f, s.belly)
                }
            }
        }
        // ---- Things on the body
        if (has(X.STRIPES)) for (k in 0 until 3) gfx.rect(cx - 5 + k * 4f, b - 13, 1.5f, 4f, s.accent)
        if (has(X.STRIPE)) gfx.rect(cx - 7, b - 13, 14f, 1.5f, s.accent)
        if (has(X.COLLAR)) for (k in 0 until 4) gfx.oval(hcx - 6 + k * 3.2f, hy + hh - 2, 4f, 4f, if (s.accent == s.main) s.belly else s.accent)
        if (has(X.ROCKCOLLAR)) for (k in 0 until 3) gfx.poly(floatArrayOf(hcx - 5 + k * 3.5f, hcx - 3.5f + k * 3.5f, hcx - 2 + k * 3.5f), floatArrayOf(hy + hh, hy + hh - 4, hy + hh), 0xFF8A8A9A.toInt())
        if (has(X.SPIKES)) for (k in 0 until 4) gfx.poly(floatArrayOf(hcx - 7 + k * 3.5f, hcx - 5 + k * 3.5f, hcx - 3 + k * 3.5f), floatArrayOf(hy + hh, hy + hh + 4, hy + hh), WHITE)
        if (has(X.BOWTIE)) {
            gfx.poly(floatArrayOf(cx, cx - 4, cx - 4), floatArrayOf(b - 12, b - 14, b - 10), s.accent)
            gfx.poly(floatArrayOf(cx, cx + 4, cx + 4), floatArrayOf(b - 12, b - 14, b - 10), s.accent)
        }
        if (has(X.BONE)) { gfx.rect(cx + f * 6 - 1, b - 16, 2f, 10f, 0xFFF4F0E0.toInt()); gfx.oval(cx + f * 6 - 2, b - 17, 4f, 3f, 0xFFF4F0E0.toInt()) }
        // ---- Head
        if (s.body != Body.BIRD && s.body != Body.BLOB) gfx.oval(hx, hy, hw, hh, s.main)
        drawEars(gfx, s, hx, hy, hw, hh, f, t)
        if (s.body == Body.DINO || has(X.SNOUT)) gfx.oval(hcx + f * 5 - 3.5f, hy + hh * 0.45f, 7f, 5f, if (has(X.SNOUT)) 0xFFF0A0A0.toInt() else s.main)
        if (has(X.SKULL)) {
            gfx.oval(hx - 1, hy - 2, hw + 2, hh * 0.7f, 0xFFF4F0E0.toInt())
            gfx.rect(hx + 1, hy + hh * 0.35f, hw - 2, 2f, 0xFFF4F0E0.toInt())
        }
        if (has(X.MASK)) gfx.rect(hx + 1, hy + hh * 0.3f, hw - 2, 3.5f, s.belly)
        if (has(X.FLOWERMASK)) for (k in 0 until 5) {
            val a = k * 1.256f
            gfx.oval(hcx + f * 2 + cos(a) * 3.5f - 2, hy + hh * 0.55f + sin(a) * 3f - 2, 4f, 4f, s.accent)
        }
        // ---- Face
        val ey = hy + hh * (if (s.body == Body.BIRD || s.body == Body.BLOB) 0.32f else 0.38f)
        val ex = hcx + f * (if (s.body == Body.QUAD || s.body == Body.SERPENT) 2f else 1f)
        for (dx in floatArrayOf(-3.2f, 1f)) {
            gfx.oval(ex + dx, ey, 2.4f, 2.9f, INK)
            gfx.oval(ex + dx + 0.4f, ey + 0.4f, 1f, 1f, WHITE)
        }
        if (has(X.CHEEKS)) for (dx in floatArrayOf(-6f, 3.5f)) gfx.oval(ex + dx, ey + 3f, 3f, 2.5f, if (s.accent == 0xFF2A2A2A.toInt() || s.body == Body.QUAD) 0xFFE84A4A.toInt() else 0xFFFF6A6A.toInt())
        if (has(X.WHISKERS)) for (sd in intArrayOf(-1, 1)) gfx.rect(if (sd < 0) hx - 3 else hx + hw - 1, ey + 3, 4f, 0.8f, INK)
        when {
            s.body == Body.BIRD -> {
                val beak = when { has(X.BEAKRED) -> 0xFFE84A3A.toInt(); has(X.BEAKYELLOW) -> 0xFFF4D84A.toInt(); else -> 0xFFF0A040.toInt() }
                gfx.poly(floatArrayOf(cx + f * 5, cx + f * 10, cx + f * 5), floatArrayOf(b - 11, b - 9.5f, b - 8), beak)
            }
            has(X.CLOWNNOSE) -> gfx.oval(hcx + f * 4 - 1.5f, ey + 3, 3.5f, 3f, s.accent)
            else -> gfx.rect(ex - 1.5f, ey + 5, 3f, 0.9f, INK)
        }
        if (has(X.TEETH)) for (k in 0 until 3) gfx.poly(floatArrayOf(ex - 3 + k * 2f, ex - 2 + k * 2f, ex - 1 + k * 2f), floatArrayOf(ey + 5.5f, ey + 7.5f, ey + 5.5f), WHITE)
        if (has(X.TUSKS)) for (dx in floatArrayOf(-3f, 2f)) gfx.poly(floatArrayOf(ex + dx, ex + dx + 1.5f, ex + dx + 0.5f), floatArrayOf(ey + 5, ey + 5, ey + 9), WHITE)
        if (has(X.BANDAGE)) gfx.rect(ex - 1, ey + 2.5f, 3f, 1.5f, s.accent)
        // ---- On top of the head
        if (has(X.GEM)) gfx.oval(hcx - 1.2f + f, hy + 1, 2.6f, 2.6f, s.accent)
        if (has(X.MOON)) { gfx.oval(hcx - 3, hy - 1, 6f, 5f, s.accent); gfx.oval(hcx - 2, hy - 2, 6f, 5f, s.main) }
        if (has(X.TUFT)) gfx.poly(floatArrayOf(hcx - 3, hcx, hcx + 3, hcx + 1), floatArrayOf(hy + 1, hy - 5, hy + 1, hy - 1), s.accent)
        if (has(X.STICK)) gfx.rect(hcx - 4, hy - 2, 9f, 1.5f, 0xFF8A5A2A.toInt())
        if (has(X.FLAMEHAT)) {
            val fl = sin(t * 10) * 1.5f
            gfx.oval(hcx - 3, hy - 6 + fl, 6f, 8f, 0xFFFF7A2A.toInt()); gfx.oval(hcx - 1.5f, hy - 3 + fl, 3f, 4f, 0xFFFFE14D.toInt())
        }
    }

    private fun drawEars(gfx: Gfx, s: Spec, hx: Float, hy: Float, hw: Float, hh: Float, f: Float, t: Float) {
        val hcx = hx + hw / 2
        val inner = if (s.belly != s.main) s.belly else mixColor(s.main, 0xFFFFFFFF.toInt(), 0.4f)
        fun tri(x: Float, w: Float, h: Float, c: Int) = gfx.poly(floatArrayOf(x, x + w / 2, x + w), floatArrayOf(hy + 2, hy + 2 - h, hy + 2), c)
        when (s.ears) {
            Ears.NONE -> {}
            Ears.POINTY -> { tri(hcx - 6, 4f, 6f, s.main); tri(hcx + 2, 4f, 6f, s.main) }
            Ears.BLACKTIP -> for (x in floatArrayOf(hcx - 7, hcx + 2)) { tri(x, 5f, 9f, s.main); gfx.poly(floatArrayOf(x + 1.5f, x + 2.5f, x + 3.5f), floatArrayOf(hy - 4, hy - 7, hy - 4), s.accent) }
            Ears.CAT -> { tri(hcx - 5.5f, 4f, 4.5f, s.main); tri(hcx + 1.5f, 4f, 4.5f, s.main) }
            Ears.BIG -> for (x in floatArrayOf(hcx - 8, hcx + 2)) { tri(x, 6f, 10f, s.main); tri(x + 1.5f, 3f, 6f, inner) }
            Ears.LONG -> { gfx.oval(hcx - 5, hy - 10, 4f, 12f, s.main); gfx.oval(hcx + 1, hy - 11, 4f, 12f, s.main); gfx.oval(hcx - 4, hy - 8, 2f, 7f, s.accent) }
            Ears.ROUND -> { gfx.oval(hcx - 7, hy - 2, 5f, 5f, s.main); gfx.oval(hcx + 2, hy - 2, 5f, 5f, s.main) }
            Ears.FLOP -> { gfx.oval(hx - 1.5f, hy + 1, 4f, 7f, s.belly.takeIf { it != s.main } ?: mixColor(s.main, 0xFF000000.toInt(), 0.3f)); gfx.oval(hx + hw - 2.5f, hy + 1, 4f, 7f, mixColor(s.main, 0xFF000000.toInt(), 0.3f)) }
            Ears.FIN -> gfx.poly(floatArrayOf(hcx - 3, hcx - f * 2, hcx + 3), floatArrayOf(hy + 2, hy - 6, hy + 2), s.accent.takeIf { it != s.main } ?: mixColor(s.main, 0xFF000000.toInt(), 0.25f))
            Ears.FINWHITE -> for (sd in intArrayOf(-1, 1)) gfx.poly(floatArrayOf(hcx + sd * 4, hcx + sd * 9, hcx + sd * 5), floatArrayOf(hy + 3, hy, hy + 6), s.accent)
            Ears.LEAF -> {
                gfx.rect(hcx - 0.5f, hy - 3, 1f, 4f, 0xFF2E8A3A.toInt())
                gfx.poly(floatArrayOf(hcx, hcx - f * 4, hcx - f * 10, hcx - f * 5), floatArrayOf(hy - 3, hy - 8, hy - 9, hy - 3), s.accent)
            }
            Ears.SPROUT -> {
                gfx.rect(hcx - 0.5f, hy - 4, 1f, 5f, 0xFF7A5A2A.toInt())
                gfx.oval(hcx - 4, hy - 6, 4f, 3f, 0xFF3AA84A.toInt()); gfx.oval(hcx, hy - 6, 4f, 3f, 0xFF3AA84A.toInt())
            }
            Ears.CREST -> for (k in 0 until 3) gfx.oval(hcx - 3 + k * 2f - f, hy - 4 - (k % 2) * 1.5f, 2.5f, 5f, s.accent)
            Ears.DIAMOND -> for (x in floatArrayOf(hcx - 6, hcx + 2)) gfx.poly(floatArrayOf(x, x + 2, x + 4, x + 2), floatArrayOf(hy, hy - 4, hy, hy + 3), s.accent)
            Ears.GILLS -> for (sd in intArrayOf(-1, 1)) for (k in 0 until 3) gfx.oval(hcx + sd * (hw / 2 + 1) - 1.5f, hy + 2 + k * 3f, 3f + (1 - k % 2) * 2f, 2f, s.accent)
        }
    }

    private fun drawTail(gfx: Gfx, s: Spec, x: Float, y: Float, f: Float, t: Float) {
        val wag = sin(t * 6) * 1.5f
        fun thin(n: Int, up: Float): Pair<Float, Float> {
            var px = x; var py = y
            for (k in 0 until n) {
                px -= f * 2.2f; py -= up + (if (k == n - 1) wag * 0.4f else 0f)
                gfx.oval(px - 1.4f, py - 1.4f, 2.8f, 2.8f, s.main)
            }
            return px to py
        }
        when (s.tail) {
            Tail.NONE -> {}
            Tail.THIN -> thin(4, 1.2f)
            Tail.LONG -> { var px = x; var py = y; for (k in 0 until 8) { px -= f * 2f; py -= 1.2f + sin(t * 3 + k) * 0.8f; gfx.oval(px - 1.2f, py - 1.2f, 2.4f, 2.4f, s.main) }; gfx.oval(px - 2.5f, py - 2.5f, 5f, 5f, s.main) }
            Tail.FLAME -> { val (px, py) = thin(3, 1.5f); gfx.oval(px - 3, py - 6 + wag, 6f, 8f, 0xFFFF7A2A.toInt()); gfx.oval(px - 1.5f, py - 3 + wag, 3f, 4f, 0xFFFFE14D.toInt()) }
            Tail.LEAF -> gfx.poly(floatArrayOf(x, x - f * 6, x - f * 12, x - f * 7), floatArrayOf(y, y - 10 + wag, y - 14 + wag, y - 2), s.accent.takeIf { it != s.main } ?: 0xFF3AA84A.toInt())
            Tail.BOLT -> gfx.poly(floatArrayOf(x, x - f * 5, x - f * 3, x - f * 9, x - f * 6, x - f * 2), floatArrayOf(y, y - 4, y - 5, y - 11 + wag, y - 4, y - 2), s.main)
            Tail.FLUFFY -> {
                gfx.oval(x - f * 9 - 4, y - 9 + wag, 10f, 9f, s.main)
                gfx.oval(x - f * 12 - 2.5f, y - 11 + wag, 5f, 5f, if (s.accent != s.main) s.accent else s.belly)
            }
            Tail.FIN -> gfx.poly(floatArrayOf(x, x - f * 8, x - f * 7), floatArrayOf(y, y - 6 + wag, y + 3), s.accent.takeIf { it != s.main } ?: mixColor(s.main, 0xFF000000.toInt(), 0.25f))
            Tail.CURL -> { gfx.oval(x - f * 4 - 2.5f, y - 3, 5f, 5f, s.main); gfx.oval(x - f * 4 - 1.2f, y - 1.7f, 2.4f, 2.4f, s.belly) }
            Tail.BALL -> { val (px, py) = thin(3, 1.4f); gfx.oval(px - 3, py - 3, 6f, 6f, s.accent) }
            Tail.STAR -> { val (px, py) = thin(3, 1.3f); gfx.poly(floatArrayOf(px - 3, px, px + 3, px), floatArrayOf(py, py - 3.5f, py, py + 3.5f), s.accent) }
            Tail.SPLIT -> { thin(4, 1.6f); var px = x; var py = y; for (k in 0 until 4) { px -= f * 2.5f; py -= 0.4f; gfx.oval(px - 1.2f, py - 1.2f, 2.4f, 2.4f, s.main) } }
        }
    }
}
