package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.mixColor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 90 newest Pokemon. Instead of a hand drawing each, every one is built from parts: a body shape,
 * ears, a tail and a few extras (cheeks, a shell, a collar...), in its own colors.
 */
object Pokedex {
    enum class Body { BIPED, QUAD, BIRD, BLOB, DINO, SERPENT, BUG, GOLEM, SLUG }
    enum class Ears { NONE, POINTY, BLACKTIP, CAT, ROUND, BIG, LONG, FLOP, FIN, FINWHITE, LEAF, SPROUT, CREST, DIAMOND, GILLS }
    enum class Tail { NONE, THIN, FLAME, LEAF, BOLT, FLUFFY, FIN, CURL, BALL, STAR, SPLIT, LONG, SPADE, STINGER, TADPOLE }
    enum class X {
        BACKFIRE, BANDAGE, BEAKRED, BEAKYELLOW, BELLY, BONE, BOWTIE, CHEEKS, CLOWNNOSE, COLLAR, DIAMONDS, EARFUR,
        FLAMEHAT, FLOWERMASK, GEM, MASK, MOON, ROCKCOLLAR, SHELL, SKULL, SNOUT, SPIKES, STICK, STRIPE, STRIPES,
        TEETH, TRIANGLES, TUFT, TUSKS, WHISKERS, WINGFINS, WOOL,
        // Parts for the 40 newest
        ACORN, ANTENNAE, ANTLERS, BATWINGS, BEESTRIPES, BIGHORN, BIRDWINGS, BOLTS, BROW, BUGWINGS, BUTTERFLY, CLAWS,
        CORAL, CROWN, CRYSTALS, FIREMANE, FRILL, FROGEYES, GEAR, GHOST, HORNS, ICICLES, LANTERN, LEAFBACK, MANE,
        MUSHCAP, MUZZLE, NOSEHORN, ROCKS, SAIL, SCARF, SNOWFLAKE, SPIRAL, SPOTS, STAR, SWIRL, VISOR, WISPFLAME,
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
    ) + MORE_SPECS }

    private const val INK = 0xFF1A1420.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BONE = 0xFFF4ECD0.toInt()

    fun draw(gfx: Gfx, m: Mon, cx: Float, bottom: Float, t: Float, facing: Int, walking: Boolean) {
        val s = specs[m] ?: return
        val f = facing.toFloat()
        fun has(x: X) = x in s.extras
        val floaty = (s.body == Body.BLOB && !has(X.ROCKS)) || m == Mon.MEWLY || m == Mon.ESPEO || has(X.BUGWINGS) || has(X.BUTTERFLY)
        val bob = if (floaty) sin(t * 3) * 1.5f - (if (m == Mon.MEWLY || has(X.GHOST) || has(X.BUTTERFLY)) 4f else 0f) else if (walking) abs(sin(t * 10)) * 1.2f else 0f
        val b = bottom - bob
        val step = if (walking) sin(t * 14) * 1.5f else 0f
        val dark = mixColor(s.main, INK, 0.35f)

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
            Body.BUG -> { hw = 10f; hh = 9f; hx = cx + f * 8 - 5; hy = b - 13; tailX = cx - f * 9; tailY = b - 9 }
            Body.GOLEM -> { hw = 13f; hh = 11f; hx = cx - 6.5f + f * 2; hy = b - 30; tailX = cx - f * 9; tailY = b - 10 }
            Body.SLUG -> { hw = 10f; hh = 9f; hx = cx + f * 7 - 5; hy = b - 19; tailX = cx - f * 11; tailY = b - 3 }
        }
        // The top of the back (wings, sails and flames go there).
        val backY = b - when (s.body) {
            Body.BIPED -> 12f; Body.QUAD -> 13f; Body.BIRD, Body.BLOB -> 16f; Body.DINO -> 17f; Body.SERPENT -> 10f
            Body.BUG -> 14f; Body.GOLEM -> 23f; Body.SLUG -> 6f
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
            gfx.oval(cx - f * 2 - 6 + k * 4f, backY - 5 - k % 2 * 2f + fl, 5f, 7f, 0xFFFF7A2A.toInt())
            gfx.oval(cx - f * 2 - 5 + k * 4f, backY - 3 - k % 2 * 2f + fl, 3f, 4f, 0xFFFFE14D.toInt())
        }
        drawWings(gfx, s, cx, b, backY, f, t)
        if (has(X.GEAR)) {
            val big = s.body == Body.GOLEM
            val gx = cx - f * (if (big) 7f else 5f); val gy = if (big) b - 24 else b - 13
            val r = if (big) 9f else 7f
            for (k in 0 until 8) {
                val a = k * 0.785f + t * 1.5f
                gfx.rect(gx + cos(a) * r - 1.6f, gy + sin(a) * r - 1.6f, 3.2f, 3.2f, s.accent)
            }
            gfx.oval(gx - r, gy - r, r * 2, r * 2, s.accent)
            gfx.oval(gx - r * 0.35f, gy - r * 0.35f, r * 0.7f, r * 0.7f, mixColor(s.accent, INK, 0.45f))
        }
        if (has(X.SAIL)) spikes(gfx, cx, backY, f, 3, 5f, s.accent)
        if (has(X.ICICLES)) spikes(gfx, cx, backY, f, 3, 6f, 0xFFE0F4FF.toInt())
        if (has(X.LEAFBACK)) for (k in 0 until 3) {
            val px = cx - f * (1 + k * 3.5f); val py = backY + 1 + k * 0.5f
            gfx.poly(floatArrayOf(px, px - f * 5, px - f * 7, px - f * 3), floatArrayOf(py, py - 5 + k, py - 8 + k, py - 1), s.accent)
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
                if (!has(X.GHOST)) { gfx.oval(cx - 6, b - 3, 4f, 3f, dark); gfx.oval(cx + 2, b - 3, 4f, 3f, dark) }
                if (has(X.GHOST)) gfx.poly(floatArrayOf(cx - 6, cx + 6, cx - f * 12, cx - f * 9), floatArrayOf(b - 9, b - 3, b + 1, b - 4), s.main)
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
            Body.BUG -> {
                for (k in 0 until 3) {
                    val sw = if (k % 2 == 0) step else -step
                    gfx.rect(cx - 6.5f + k * 4.5f + sw * 0.4f, b - 5, 1.4f, 5f, dark)
                }
                gfx.oval(cx - 9, b - 14, 18f, 11f, s.main)
                if (s.belly != s.main && !has(X.BIGHORN)) gfx.oval(cx - 7, b - 7, 14f, 3.5f, s.belly)
                gfx.rect(cx - f * 1.5f - 0.5f, b - 14, 1f, 8f, mixColor(s.main, INK, 0.4f))
            }
            Body.GOLEM -> {
                gfx.oval(cx - 8 + step * 0.5f, b - 6, 7f, 6f, dark); gfx.oval(cx + 1 - step * 0.5f, b - 6, 7f, 6f, dark)
                gfx.oval(cx - 10, b - 23, 20f, 19f, s.main)
                if (s.belly != s.main) gfx.oval(cx - 6 + f, b - 18, 12f, 12f, s.belly)
                gfx.oval(cx - 15, b - 20 + step * 0.3f, 7f, 13f, s.main); gfx.oval(cx + 8, b - 20 - step * 0.3f, 7f, 13f, s.main)
            }
            Body.SLUG -> {
                gfx.oval(cx - 11, b - 6, 22f, 6f, s.main)
                gfx.oval(cx - 10, b - 2.5f, 20f, 2.5f, s.belly)
                gfx.oval(cx + f * 6 - 3, b - 13, 6f, 10f, s.main)
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
        if (has(X.SPOTS)) {
            val sy = b - when (s.body) {
                Body.BIPED -> 7f; Body.QUAD -> 10f; Body.BUG -> 10f; Body.GOLEM -> 15f; Body.SERPENT -> 8f; Body.DINO -> 11f
                Body.SLUG -> 4f; else -> 9f
            }
            for ((dx, dy) in listOf(-5f to -1.5f, 0f to -3f, 4f to -1f, -1.5f to 1.2f)) gfx.oval(cx - f * dx - 1.2f, sy + dy - 1.2f, 2.4f, 2.4f, s.accent)
        }
        if (has(X.BEESTRIPES)) { gfx.rect(cx - 7.5f, b - 8, 15f, 2f, s.accent); gfx.rect(cx - 6, b - 4.5f, 12f, 1.8f, s.accent) }
        if (has(X.SWIRL)) {
            gfx.oval(cx - 3.5f, b - 6.8f, 7f, 5.6f, s.belly)
            gfx.oval(cx - 2.2f, b - 5.9f, 4.4f, 3.8f, INK)
            gfx.oval(cx - 1.3f, b - 5.2f, 2.6f, 2.4f, s.belly)
            gfx.oval(cx - 0.5f, b - 4.5f, 1f, 1f, INK)
        }
        if (has(X.LANTERN)) {
            val metal = s.accent
            gfx.oval(cx - 5.5f, b - 14.5f, 11f, 10f, s.belly)
            val fl = sin(t * 9) * 0.8f
            gfx.oval(cx - 1.5f, b - 9 + fl, 3f, 4f, WHITE)
            gfx.rect(cx - 6.5f, b - 18, 13f, 3f, metal)
            gfx.rect(cx - 0.7f, b - 23, 1.4f, 5f, metal); gfx.rect(cx - 3, b - 23.5f, 6f, 1.5f, metal)
            gfx.rect(cx - 5, b - 4, 10f, 2.5f, metal)
        }
        if (has(X.ROCKS)) {
            val spots = when (s.body) {
                Body.GOLEM -> listOf(-11f to -19f, 11f to -20f, -4f to -22f)
                Body.BLOB -> listOf(-5f to -13f, 4f to -14.5f, -7f to -5f)
                else -> listOf(-4f to -12f, 3f to -13f)
            }
            for ((dx, dy) in spots) {
                val x = cx + dx; val y = b + dy
                gfx.poly(floatArrayOf(x - 3, x - 1, x + 2.5f, x + 3), floatArrayOf(y + 1.5f, y - 2.5f, y - 2, y + 2), s.accent)
            }
        }
        if (has(X.CRYSTALS)) for ((dx, h) in listOf(-11f to 8f, -8f to 5f, 10f to 7f)) {
            val x = cx + dx
            gfx.poly(floatArrayOf(x - 1.6f, x, x + 1.6f), floatArrayOf(b - 19, b - 19 - h, b - 19), s.accent)
        }
        if (has(X.BOLTS)) for (dx in floatArrayOf(-5f, 5f)) {
            val y = if (s.body == Body.GOLEM) b - 13 else b - 5
            gfx.oval(cx + dx - 1.3f, y - 1.3f, 2.6f, 2.6f, mixColor(s.main, INK, 0.45f))
            gfx.rect(cx + dx - 1f, y - 0.3f, 2f, 0.6f, s.main)
        }
        if (has(X.CLAWS)) {
            val (clx, cly) = when (s.body) {
                Body.BIPED -> (cx + f * 6) to (b - 6.5f); Body.QUAD -> (cx + f * 6.5f) to (b - 0.5f); Body.GOLEM -> (cx + f * 11.5f) to (b - 7.5f)
                Body.DINO -> (cx + f * 5) to (b - 9.5f); else -> (cx + f * 6) to (b - 1f)
            }
            for (k in -1..1) { val x = clx + f * k * 1.3f; gfx.poly(floatArrayOf(x - 0.6f, x + 0.6f, x + f * 0.9f), floatArrayOf(cly, cly, cly + 2f), WHITE) }
        }
        if (has(X.SPIRAL)) {
            val sx = cx - f * 3; val sy = b - 11
            val d = mixColor(s.accent, INK, 0.35f)
            gfx.oval(sx - 7, sy - 7, 14f, 14f, s.accent); gfx.oval(sx - 4.8f, sy - 4.8f, 9.6f, 9.6f, d)
            gfx.oval(sx - 3.4f, sy - 3.4f, 6.8f, 6.8f, s.accent); gfx.oval(sx - 1.4f, sy - 1.4f, 2.8f, 2.8f, d)
        }
        if (has(X.BONE)) { gfx.rect(cx + f * 6 - 1, b - 16, 2f, 10f, 0xFFF4F0E0.toInt()); gfx.oval(cx + f * 6 - 2, b - 17, 4f, 3f, 0xFFF4F0E0.toInt()) }
        // ---- Around the head
        if (has(X.FRILL)) {
            gfx.oval(hcx - f * 3 - hw / 2 - 4, hy - 5, hw + 8, hh + 7, s.accent)
            for (k in 0 until 5) {
                val a = 3.6f + k * 0.55f
                gfx.oval(hcx - f * 3 + cos(a) * (hw / 2 + 3) - 1.3f, hy + hh / 2 - 1.5f + sin(a) * (hh / 2 + 4) - 1.3f, 2.6f, 2.6f, mixColor(s.accent, INK, 0.3f))
            }
        }
        if (has(X.MANE) || has(X.FIREMANE)) for (k in 0 until 10) {
            val a = k * 0.628f
            val fire = has(X.FIREMANE)
            val fl = if (fire) sin(t * 12 + k * 2) * 0.8f else 0f
            val ox = hcx + cos(a) * (hw * 0.55f + 1); val oy = hy + hh / 2 + sin(a) * (hh * 0.55f + 1) + fl
            gfx.oval(ox - 3.5f, oy - 3.5f, 7f, 7f, if (fire) 0xFFFF7A2A.toInt() else s.belly)
            if (fire) gfx.oval(ox - 1.5f, oy - 1f, 3f, 3f, 0xFFFFE14D.toInt())
        }
        // ---- Head
        if (s.body != Body.BIRD && s.body != Body.BLOB) gfx.oval(hx, hy, hw, hh, s.main)
        drawEars(gfx, s, hx, hy, hw, hh, f, t)
        if (s.body == Body.DINO || has(X.SNOUT)) gfx.oval(hcx + f * 5 - 3.5f, hy + hh * 0.45f, 7f, 5f, if (has(X.SNOUT)) 0xFFF0A0A0.toInt() else s.main)
        if (has(X.SKULL)) {
            gfx.oval(hx - 1, hy - 2, hw + 2, hh * 0.7f, 0xFFF4F0E0.toInt())
            gfx.rect(hx + 1, hy + hh * 0.35f, hw - 2, 2f, 0xFFF4F0E0.toInt())
        }
        if (has(X.MASK)) gfx.rect(hx + 1, hy + hh * 0.3f, hw - 2, 3.5f, s.belly)
        drawHeadTop(gfx, s, hx, hy, hw, hh, f, t)
        if (has(X.FLOWERMASK)) for (k in 0 until 5) {
            val a = k * 1.256f
            gfx.oval(hcx + f * 2 + cos(a) * 3.5f - 2, hy + hh * 0.55f + sin(a) * 3f - 2, 4f, 4f, s.accent)
        }
        // ---- Face
        val ey = if (has(X.FROGEYES)) hy - 0.5f else hy + hh * (if (s.body == Body.BIRD || s.body == Body.BLOB) 0.32f else 0.38f)
        val ex = hcx + f * (if (s.body == Body.QUAD || s.body == Body.SERPENT || s.body == Body.BUG || s.body == Body.SLUG) 2f else 1f)
        if (has(X.FROGEYES)) for (dx in floatArrayOf(-3.2f, 1f)) gfx.oval(ex + dx - 1.4f, ey - 1.6f, 5.2f, 5.6f, s.main)
        if (has(X.VISOR)) gfx.rect(hx + 1.5f, ey - 1, hw - 3, 4.6f, 0xFF3AD8F8.toInt())
        if (has(X.MUZZLE)) {
            gfx.oval(hcx + f * hw * 0.22f - 3.5f, hy + hh * 0.5f, 7f, 4.6f, s.belly)
            gfx.oval(hcx + f * (hw * 0.22f + 1.5f) - 1.2f, hy + hh * 0.5f, 2.4f, 1.8f, INK)
        }
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
            has(X.SWIRL) || has(X.LANTERN) -> {}
            has(X.FROGEYES) -> gfx.rect(ex - 3, ey + 5.5f, 7f, 1f, INK)
            else -> gfx.rect(ex - 1.5f, ey + 5, 3f, 0.9f, INK)
        }
        if (has(X.TEETH)) for (k in 0 until 3) gfx.poly(floatArrayOf(ex - 3 + k * 2f, ex - 2 + k * 2f, ex - 1 + k * 2f), floatArrayOf(ey + 5.5f, ey + 7.5f, ey + 5.5f), WHITE)
        if (has(X.TUSKS)) for (dx in floatArrayOf(-3f, 2f)) gfx.poly(floatArrayOf(ex + dx, ex + dx + 1.5f, ex + dx + 0.5f), floatArrayOf(ey + 5, ey + 5, ey + 9), WHITE)
        if (has(X.BANDAGE)) gfx.rect(ex - 1, ey + 2.5f, 3f, 1.5f, s.accent)
        if (has(X.BROW)) {
            gfx.poly(floatArrayOf(ex - 3.9f, ex - 0.5f, ex - 0.5f, ex - 3.9f), floatArrayOf(ey - 2.3f, ey - 0.9f, ey + 0.1f, ey - 1.3f), INK)
            gfx.poly(floatArrayOf(ex + 0.3f, ex + 3.7f, ex + 3.7f, ex + 0.3f), floatArrayOf(ey - 0.9f, ey - 2.3f, ey - 1.3f, ey + 0.1f), INK)
        }
        if (has(X.SCARF)) {
            gfx.rect(hx + 0.5f, hy + hh - 2.5f, hw - 1, 3f, s.accent)
            gfx.rect(hcx - f * 3 - 1.2f, hy + hh, 2.5f, 5f, s.accent)
        }
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

    /** A row of spikes (or plates) along the back. */
    private fun spikes(gfx: Gfx, cx: Float, backY: Float, f: Float, n: Int, h: Float, c: Int) {
        for (k in 0 until n) {
            val px = cx - f * (1 + k * 3.5f); val py = backY + 1.5f + k * 1.2f
            gfx.poly(floatArrayOf(px - 2, px - f * 1.5f, px + 2), floatArrayOf(py, py - h + k * 0.8f, py), c)
        }
    }

    /** Wings behind the body: bat/dragon, bird, bug and butterfly ones. */
    private fun drawWings(gfx: Gfx, s: Spec, cx: Float, b: Float, backY: Float, f: Float, t: Float) {
        val x = s.extras
        if (X.BATWINGS in x) {
            val flap = sin(t * 7) * 2f
            val wy = backY + 3
            val c = mixColor(s.main, INK, 0.3f)
            for (sd in intArrayOf(-1, 1)) gfx.poly(
                floatArrayOf(cx + sd * 3, cx + sd * 14, cx + sd * 12, cx + sd * 15, cx + sd * 9, cx + sd * 5),
                floatArrayOf(wy, wy - 9 + flap, wy - 3, wy + 1 + flap * 0.5f, wy + 2, wy + 5), c)
        }
        if (X.BIRDWINGS in x) {
            val flap = sin(t * 5) * 2f
            val c = mixColor(s.main, s.accent, 0.25f)
            for (sd in intArrayOf(-1, 1)) {
                gfx.poly(floatArrayOf(cx + sd * 3, cx + sd * 10, cx + sd * 18, cx + sd * 15, cx + sd * 17, cx + sd * 12, cx + sd * 13, cx + sd * 6),
                    floatArrayOf(b - 12, b - 22 + flap, b - 26 + flap, b - 19, b - 15, b - 12, b - 9, b - 7), c)
                gfx.poly(floatArrayOf(cx + sd * 10, cx + sd * 18, cx + sd * 15), floatArrayOf(b - 22 + flap, b - 26 + flap, b - 19), s.accent)
            }
        }
        if (X.BUGWINGS in x) {
            val flap = sin(t * 22) * 1.5f
            val c = 0xC8E8F8FF.toInt()
            gfx.oval(cx - f * 3 - 8, backY - 9 + flap, 8f, 11f, c)
            gfx.oval(cx - f * 3, backY - 8 - flap, 8f, 10f, c)
        }
        if (X.BUTTERFLY in x) {
            val k = 0.7f + 0.3f * abs(sin(t * 6))
            for (sd in intArrayOf(-1, 1)) {
                val w = 13f * k
                val ux = if (sd < 0) cx - w - 1 else cx + 1
                gfx.oval(ux, b - 31, w, 15f, s.accent)
                gfx.oval(ux + w * 0.3f, b - 27, w * 0.4f, 5f, WHITE)
                val lw = w * 0.8f
                gfx.oval(if (sd < 0) cx - lw else cx, b - 19, lw, 10f, s.belly)
            }
        }
    }

    /** Hats, horns, antennae and other things on top of the head (drawn before the face, so eyes stay on top). */
    private fun drawHeadTop(gfx: Gfx, s: Spec, hx: Float, hy: Float, hw: Float, hh: Float, f: Float, t: Float) {
        val x = s.extras
        val hcx = hx + hw / 2
        if (X.HORNS in x) for (bx in floatArrayOf(hcx - 5, hcx + 2)) gfx.poly(floatArrayOf(bx, bx + 3, bx + 1.5f - f * 2), floatArrayOf(hy + 2, hy + 2, hy - 5), BONE)
        if (X.NOSEHORN in x) gfx.poly(floatArrayOf(hcx + f * hw * 0.15f, hcx + f * hw * 0.45f, hcx + f * (hw * 0.5f + 4)),
            floatArrayOf(hy + hh * 0.3f, hy + hh * 0.55f, hy - 2), if (X.BIGHORN in x) s.accent else BONE)
        if (X.BIGHORN in x) {
            gfx.poly(floatArrayOf(hcx - f * 1, hcx + f * 3, hcx + f * 9, hcx + f * 5, hcx + f * 3),
                floatArrayOf(hy + 2, hy - 8, hy - 12, hy - 6, hy + 3), s.accent)
            gfx.poly(floatArrayOf(hcx + f * 3.5f, hcx + f * 6.5f, hcx + f * 5.5f), floatArrayOf(hy - 7, hy - 7, hy - 10), s.accent)
        }
        if (X.ANTENNAE in x) for (dx in floatArrayOf(-3f, 2f)) {
            val bx = hcx + dx
            gfx.poly(floatArrayOf(bx - 0.45f, bx + 0.45f, bx + f * 1.5f + 0.45f, bx + f * 1.5f - 0.45f), floatArrayOf(hy + 1, hy + 1, hy - 6, hy - 6), INK)
            gfx.oval(bx + f * 1.5f - 1.4f, hy - 7.6f, 2.8f, 2.8f, s.accent)
        }
        if (X.ANTLERS in x) for (sd in intArrayOf(-1, 1)) {
            val bx = hcx + sd * 2.5f
            val brown = 0xFF6A4A2A.toInt()
            gfx.rect(bx - 0.65f, hy - 8, 1.3f, 9f, brown)
            gfx.rect(if (sd > 0) bx else bx - 4, hy - 5, 4f, 1.2f, brown)
            gfx.rect(if (sd > 0) bx else bx - 3, hy - 8, 3f, 1.2f, brown)
            gfx.oval(bx + sd * 4 - 1.8f, hy - 7.5f, 3.6f, 3f, s.accent)
            gfx.oval(bx + sd * 3 - 1.6f, hy - 10.5f, 3.2f, 3.2f, s.accent)
            gfx.oval(bx - 1.5f, hy - 11, 3f, 3.5f, s.accent)
        }
        if (X.ACORN in x) {
            gfx.rect(hcx - 0.7f, hy - 6, 1.4f, 4f, 0xFF5A3A1A.toInt())
            gfx.oval(hx - 1, hy - 3, hw + 2, hh * 0.55f + 1, 0xFF8A5A2A.toInt())
            gfx.rect(hx + 1, hy - 0.5f, hw - 2, 0.8f, 0xFF6A4220.toInt())
        }
        if (X.MUSHCAP in x) {
            gfx.oval(hx - 4, hy - 6, hw + 8, hh * 0.5f + 3, s.accent)
            gfx.oval(hcx - 6, hy - 4, 3f, 2.5f, WHITE); gfx.oval(hcx + 0.5f, hy - 5, 3.5f, 3f, WHITE); gfx.oval(hcx + 5, hy - 2.5f, 2.5f, 2f, WHITE)
        }
        if (X.CORAL in x) for ((dx, h) in listOf(-4.5f to 7f, 0f to 10f, 4.5f to 6f)) {
            gfx.rect(hcx + dx - 0.8f, hy - h + 2, 1.6f, h, s.accent)
            gfx.oval(hcx + dx - 1.8f, hy - h, 3.6f, 3.6f, s.accent)
            gfx.rect(hcx + dx, hy - h + 4.5f, 2.5f, 1.2f, s.accent)
        }
        if (X.CROWN in x) gfx.poly(floatArrayOf(hcx - 4, hcx - 4, hcx - 2, hcx, hcx + 2, hcx + 4, hcx + 4),
            floatArrayOf(hy + 1, hy - 4, hy - 1.5f, hy - 5.5f, hy - 1.5f, hy - 4, hy + 1), 0xFFFFD24A.toInt())
        if (X.STAR in x) {
            val xs = FloatArray(10); val ys = FloatArray(10)
            for (k in 0 until 10) {
                val a = -1.5708f + k * 0.6283f + sin(t * 2) * 0.1f
                val r = if (k % 2 == 0) 5.5f else 2.3f
                xs[k] = hcx + cos(a) * r; ys[k] = hy - 1 + sin(a) * r
            }
            gfx.poly(xs, ys, s.accent)
        }
        if (X.SNOWFLAKE in x) {
            val sx = hcx + f; val sy = hy + 1.8f
            gfx.rect(sx - 2.4f, sy - 0.4f, 4.8f, 0.8f, s.accent); gfx.rect(sx - 0.4f, sy - 2.4f, 0.8f, 4.8f, s.accent)
            gfx.oval(sx - 0.9f, sy - 0.9f, 1.8f, 1.8f, WHITE)
        }
        if (X.WISPFLAME in x) {
            val fl = sin(t * 9) * 1.2f
            gfx.oval(hcx - 3.5f, hy - 8 + fl, 7f, 11f, s.accent)
            gfx.oval(hcx - 1.8f, hy - 5 + fl, 3.6f, 6f, 0xFFD8F8FF.toInt())
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
            Tail.SPADE -> { val (px, py) = thin(5, 1.0f); gfx.poly(floatArrayOf(px + f, px, px - f * 4, px), floatArrayOf(py, py - 3, py, py + 3), s.accent) }
            Tail.STINGER -> gfx.poly(floatArrayOf(x + f, x - f * 4, x + f), floatArrayOf(y - 1.8f, y, y + 1.8f), INK)
            Tail.TADPOLE -> gfx.poly(floatArrayOf(x, x - f * 7, x - f * 11, x - f * 7, x), floatArrayOf(y - 2.5f, y - 5 + wag, y + wag, y + 4 + wag, y + 2.5f), s.accent)
            Tail.SPLIT -> { thin(4, 1.6f); var px = x; var py = y; for (k in 0 until 4) { px -= f * 2.5f; py -= 0.4f; gfx.oval(px - 1.2f, py - 1.2f, 2.4f, 2.4f, s.main) } }
        }
    }
}
