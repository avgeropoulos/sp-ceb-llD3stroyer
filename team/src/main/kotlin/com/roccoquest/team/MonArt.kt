package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.argb
import kotlin.math.abs
import kotlin.math.sin

/** Pictures of the 20 newer Pokemon (Eevo to Pengi). Each stands with its feet at ([cx], [bottom]). */
object MonArt {
    private const val BLACK = 0xFF101010.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    private fun eyes(gfx: Gfx, cx: Float, y: Float, f: Float, gap: Float = 5f, size: Float = 2.6f, color: Int = BLACK) {
        gfx.oval(cx - gap / 2 - size / 2 + f * 1.5f, y, size, size + 0.6f, color)
        gfx.oval(cx + gap / 2 - size / 2 + f * 1.5f, y, size, size + 0.6f, color)
        gfx.oval(cx - gap / 2 - size / 2 + f * 1.5f + 0.4f, y + 0.4f, size * 0.4f, size * 0.4f, WHITE)
        gfx.oval(cx + gap / 2 - size / 2 + f * 1.5f + 0.4f, y + 0.4f, size * 0.4f, size * 0.4f, WHITE)
    }

    private fun feet(gfx: Gfx, cx: Float, bottom: Float, color: Int, walking: Boolean, t: Float, gap: Float = 4f) {
        val step = if (walking) sin(t * 14) * 1.5f else 0f
        gfx.oval(cx - gap - 2.5f + step, bottom - 3, 5f, 3f, color)
        gfx.oval(cx + gap - 2.5f - step, bottom - 3, 5f, 3f, color)
    }

    fun draw(gfx: Gfx, m: Mon, cx: Float, bottom: Float, t: Float, facing: Int, walking: Boolean) {
        val f = facing.toFloat()
        val bob = if (walking) abs(sin(t * 10)) * 1.5f else sin(t * 3) * 0.5f
        val b = bottom - bob
        when (m) {
            Mon.EEVO -> {
                val fur = 0xFFB8885A.toInt(); val cream = 0xFFF4E4C0.toInt()
                gfx.oval(cx - f * 9 - 4, b - 16, 9f, 9f, cream) // fluffy tail
                gfx.oval(cx - 6, b - 13, 12f, 10f, fur)
                gfx.oval(cx - 4, b - 10, 8f, 5f, cream)
                gfx.oval(cx - 6 + f * 2, b - 22, 12f, 10f, fur)
                gfx.poly(floatArrayOf(cx - 5 + f * 2, cx - 8 + f * 2, cx - 1 + f * 2), floatArrayOf(b - 19, b - 31, b - 21), fur)
                gfx.poly(floatArrayOf(cx + 5 + f * 2, cx + 8 + f * 2, cx + 1 + f * 2), floatArrayOf(b - 19, b - 31, b - 21), fur)
                eyes(gfx, cx + f * 2, b - 19, f)
                feet(gfx, cx, b + bob, fur, walking, t)
            }
            Mon.RIBBON -> {
                val pink = 0xFFFFB8D8.toInt(); val white = 0xFFFFF4FA.toInt()
                for (k in 0 until 2) {
                    val a = t * 2 + k * 3.1f
                    gfx.poly(floatArrayOf(cx + 4, cx + 14 + sin(a) * 4, cx + 10 + sin(a) * 4), floatArrayOf(b - 22, b - 18 + k * 6f, b - 12 + k * 6f), 0xFF8ACCFF.toInt())
                }
                gfx.oval(cx - 6, b - 13, 12f, 10f, white)
                gfx.oval(cx - 6 + f * 2, b - 23, 12f, 11f, white)
                gfx.poly(floatArrayOf(cx - 5 + f * 2, cx - 9 + f * 2, cx - 1 + f * 2), floatArrayOf(b - 20, b - 31, b - 22), pink)
                gfx.poly(floatArrayOf(cx + 5 + f * 2, cx + 9 + f * 2, cx + 1 + f * 2), floatArrayOf(b - 20, b - 31, b - 22), pink)
                gfx.oval(cx - 3 + f * 6, b - 24, 6f, 5f, pink); gfx.oval(cx - 1 + f * 6, b - 23, 2f, 2f, 0xFF8ACCFF.toInt())
                eyes(gfx, cx + f * 2, b - 19, f, color = 0xFF3A6AC8.toInt())
                feet(gfx, cx, b + bob, pink, walking, t)
            }
            Mon.NIGHTFUR -> {
                val black = 0xFF2A2A3A.toInt(); val ring = argb((180 + sin(t * 3) * 70).toInt(), 0xFFE14D)
                gfx.oval(cx - f * 10 - 4, b - 15, 8f, 6f, black)
                gfx.oval(cx - 6, b - 13, 12f, 10f, black)
                gfx.oval(cx - 6 + f * 2, b - 23, 12f, 10f, black)
                gfx.poly(floatArrayOf(cx - 5 + f * 2, cx - 8 + f * 2, cx - 1 + f * 2), floatArrayOf(b - 20, b - 31, b - 22), black)
                gfx.poly(floatArrayOf(cx + 5 + f * 2, cx + 8 + f * 2, cx + 1 + f * 2), floatArrayOf(b - 20, b - 31, b - 22), black)
                gfx.oval(cx - 2 + f * 2, b - 25, 4f, 3f, ring); gfx.oval(cx - 4, b - 10, 3f, 3f, ring); gfx.oval(cx + 2, b - 10, 3f, 3f, ring)
                eyes(gfx, cx + f * 2, b - 19, f, color = 0xFFE84A4A.toInt())
                feet(gfx, cx, b + bob, black, walking, t)
            }
            Mon.SNOOZER -> {
                val teal = 0xFF3A6A7A.toInt(); val cream = 0xFFF0E0C0.toInt()
                gfx.oval(cx - 12, b - 22, 24f, 22f, teal)
                gfx.oval(cx - 8, b - 16, 16f, 15f, cream)
                gfx.oval(cx - 7 + f, b - 30, 14f, 12f, teal)
                gfx.poly(floatArrayOf(cx - 6 + f, cx - 5 + f, cx - 2 + f), floatArrayOf(b - 26, b - 32, b - 29), teal)
                gfx.poly(floatArrayOf(cx + 6 + f, cx + 5 + f, cx + 2 + f), floatArrayOf(b - 26, b - 32, b - 29), teal)
                gfx.oval(cx - 5 + f, b - 24, 10f, 6f, cream)
                gfx.rect(cx - 4 + f, b - 26, 3f, 0.8f, BLACK); gfx.rect(cx + 1 + f, b - 26, 3f, 0.8f, BLACK) // sleepy eyes
                gfx.oval(cx - 11, b - 4, 7f, 4f, cream); gfx.oval(cx + 4, b - 4, 7f, 4f, cream)
                if ((t * 0.6f) % 2f < 1f) gfx.text("z", cx + 10, b - 32 - (t * 6) % 6, 6f, WHITE, 1)
            }
            Mon.PUFFLE -> {
                val pink = 0xFFFFA8C8.toInt()
                gfx.oval(cx - 9, b - 18, 18f, 17f, pink)
                gfx.poly(floatArrayOf(cx - 8, cx - 6, cx - 2), floatArrayOf(b - 13, b - 22, b - 16), pink)
                gfx.poly(floatArrayOf(cx + 8, cx + 6, cx + 2), floatArrayOf(b - 13, b - 22, b - 16), pink)
                gfx.oval(cx - 2 + f * 2, b - 20, 4f, 5f, pink) // the curl
                gfx.oval(cx - 6 + f * 2, b - 13, 5f, 5f, WHITE); gfx.oval(cx + 1 + f * 2, b - 13, 5f, 5f, WHITE)
                gfx.oval(cx - 4.5f + f * 3, b - 12, 2.5f, 3f, 0xFF3A8AC8.toInt()); gfx.oval(cx + 2.5f + f * 3, b - 12, 2.5f, 3f, 0xFF3A8AC8.toInt())
                gfx.oval(cx - 1 + f * 2, b - 6, 3f, 2f, 0xFFC84A6A.toInt())
                feet(gfx, cx, b + bob, pink, walking, t, 3f)
            }
            Mon.PSYQUACK -> {
                val yellow = 0xFFF4D84A.toInt()
                gfx.oval(cx - 7, b - 14, 14f, 13f, yellow)
                gfx.oval(cx - 7 + f, b - 26, 14f, 13f, yellow)
                gfx.oval(cx - 5 + f * 6, b - 20, 10f, 5f, 0xFFF4E8B0.toInt()) // beak
                gfx.oval(cx - 5 + f, b - 23, 4f, 4f, WHITE); gfx.oval(cx + 1 + f, b - 23, 4f, 4f, WHITE)
                gfx.oval(cx - 4 + f, b - 22, 1.5f, 1.5f, BLACK); gfx.oval(cx + 2 + f, b - 22, 1.5f, 1.5f, BLACK)
                for (k in 0 until 3) gfx.rect(cx - 2 + k * 2f + f, b - 29, 0.8f, 4f, BLACK)
                gfx.oval(cx - 11, b - 22, 5f, 4f, yellow); gfx.oval(cx + 6, b - 22, 5f, 4f, yellow) // hands on head
                feet(gfx, cx, b + bob, 0xFFF4E8B0.toInt(), walking, t)
            }
            Mon.LUKO -> {
                val blue = 0xFF3A6AC8.toInt(); val black = 0xFF1A1A2A.toInt(); val cream = 0xFFF0E4B0.toInt()
                gfx.rect(cx - 4, b - 10, 3f, 8f, black); gfx.rect(cx + 1, b - 10, 3f, 8f, black)
                gfx.oval(cx - 6, b - 20, 12f, 12f, blue)
                gfx.oval(cx - 4, b - 17, 8f, 7f, cream)
                gfx.oval(cx - 6 + f, b - 30, 12f, 11f, blue)
                gfx.rect(cx - 5 + f, b - 26, 10f, 2.5f, black) // mask
                gfx.poly(floatArrayOf(cx - 4 + f, cx - 6 + f, cx - 1 + f), floatArrayOf(b - 29, b - 37, b - 30), blue)
                gfx.poly(floatArrayOf(cx + 4 + f, cx + 6 + f, cx + 1 + f), floatArrayOf(b - 29, b - 37, b - 30), blue)
                gfx.oval(cx - 3 + f * 3, b - 26, 2f, 2f, 0xFFE84A4A.toInt())
                gfx.oval(cx + f * 9 - 3, b - 18, 6f, 6f, argb((150 + sin(t * 6) * 60).toInt(), 0x6AB8FF))
            }
            Mon.SHURIKEN -> {
                val navy = 0xFF2A4A8A.toInt(); val pink = 0xFFE87AA8.toInt()
                gfx.rect(cx - 4, b - 10, 3f, 8f, navy); gfx.rect(cx + 1, b - 10, 3f, 8f, navy)
                gfx.oval(cx - 6, b - 21, 12f, 13f, navy)
                gfx.oval(cx - 3, b - 17, 6f, 8f, 0xFFF4F0E0.toInt())
                gfx.oval(cx - 6 + f, b - 31, 12f, 11f, navy)
                // the long tongue scarf
                gfx.poly(floatArrayOf(cx - 5, cx + 5, cx - f * 14, cx - f * 12), floatArrayOf(b - 22, b - 22, b - 16 + sin(t * 5) * 2, b - 13), pink)
                gfx.oval(cx - 4 + f * 2, b - 28, 3f, 3f, 0xFFFFE14D.toInt()); gfx.oval(cx + 1 + f * 2, b - 28, 3f, 3f, 0xFFFFE14D.toInt())
                feet(gfx, cx, b + bob, navy, walking, t)
            }
            Mon.MIMIC -> {
                val cloth = 0xFFE8D088.toInt()
                gfx.poly(floatArrayOf(cx - 10, cx - 7, cx - 3, cx, cx + 3, cx + 7, cx + 10, cx + 7, cx - 7),
                    floatArrayOf(b, b - 3, b, b - 3, b, b - 3, b, b - 16, b - 16), cloth)
                gfx.oval(cx - 7, b - 24, 14f, 14f, cloth)
                // A droopy Pikachu-costume ear
                gfx.poly(floatArrayOf(cx - 5, cx - 9, cx - 2), floatArrayOf(b - 22, b - 30, b - 24), cloth)
                gfx.poly(floatArrayOf(cx + 4, cx + 10, cx + 12), floatArrayOf(b - 22, b - 28, b - 24), cloth)
                gfx.rect(cx - 4 + f * 2, b - 19, 2f, 2f, BLACK); gfx.rect(cx + 1 + f * 2, b - 19, 2f, 2f, BLACK)
                gfx.poly(floatArrayOf(cx - 3 + f * 2, cx + f * 2, cx + 3 + f * 2), floatArrayOf(b - 15, b - 13, b - 15), BLACK)
                gfx.oval(cx - 2, b - 4, 4f, 4f, BLACK); gfx.oval(cx - 1.5f + f, b - 3.5f, 1.2f, 1.2f, 0xFFFFE14D.toInt())
            }
            Mon.RAYZOR -> {
                val green = 0xFF2E9A4A.toInt(); val gold = 0xFFFFD21F.toInt()
                // A long flying sky dragon, wiggling like a ribbon
                for (k in 7 downTo 0) {
                    val sx = cx - f * k * 4f
                    val sy = b - 16 + sin(t * 4 - k * 0.7f) * 4f
                    gfx.oval(sx - 4, sy - 4, 8f, 8f, green)
                    if (k % 2 == 0) gfx.oval(sx - 1.5f, sy - 1, 3f, 3f, gold)
                }
                gfx.oval(cx + f * 3 - 5, b - 24 + sin(t * 4) * 4, 11f, 9f, green)
                gfx.poly(floatArrayOf(cx + f * 3, cx - f * 4, cx + f * 1), floatArrayOf(b - 22 + sin(t * 4) * 4, b - 30 + sin(t * 4) * 4, b - 20 + sin(t * 4) * 4), gold)
                gfx.oval(cx + f * 6 - 1, b - 21 + sin(t * 4) * 4, 2.5f, 2.5f, 0xFFFFE14D.toInt())
            }
            Mon.DRAGOBUD -> {
                val orange = 0xFFF0A040.toInt(); val belly = 0xFFF8E0A0.toInt()
                val flap = sin(t * 8) * 3f
                gfx.poly(floatArrayOf(cx - 4, cx - 14, cx - 8), floatArrayOf(b - 18, b - 24 + flap, b - 12), 0xFF3AB8A0.toInt())
                gfx.poly(floatArrayOf(cx + 4, cx + 14, cx + 8), floatArrayOf(b - 18, b - 24 + flap, b - 12), 0xFF3AB8A0.toInt())
                gfx.oval(cx - 8, b - 20, 16f, 18f, orange)
                gfx.oval(cx - 5, b - 15, 10f, 12f, belly)
                gfx.oval(cx - 6 + f * 2, b - 30, 12f, 11f, orange)
                gfx.rect(cx - 3 + f * 2, b - 35, 1f, 5f, orange); gfx.rect(cx + 2 + f * 2, b - 35, 1f, 5f, orange)
                eyes(gfx, cx + f * 3, b - 26, f, 4f, 2.2f)
                feet(gfx, cx, b + bob, orange, walking, t)
            }
            Mon.FLOPFISH -> {
                val red = 0xFFE8603A.toInt()
                val flop = abs(sin(t * 7)) * 5f
                gfx.oval(cx - 9, b - 12 - flop, 18f, 11f, red)
                gfx.poly(floatArrayOf(cx - f * 8, cx - f * 15, cx - f * 15), floatArrayOf(b - 7 - flop, b - 13 - flop, b - 1 - flop), red)
                gfx.poly(floatArrayOf(cx - 4, cx, cx + 4), floatArrayOf(b - 12 - flop, b - 18 - flop, b - 12 - flop), 0xFFFFD21F.toInt())
                gfx.oval(cx + f * 3 - 2.5f, b - 10 - flop, 5f, 5f, WHITE); gfx.oval(cx + f * 3 - 0.75f, b - 8.5f - flop, 1.5f, 1.5f, BLACK)
                gfx.rect(cx + f * 7, b - 5 - flop, 1f, 6f, 0xFFF4F0D0.toInt()) // whisker
            }
            Mon.RAGEFISH -> {
                val blue = 0xFF3A7AD8.toInt(); val cream = 0xFFF4F0C0.toInt()
                for (k in 6 downTo 0) {
                    val sx = cx - f * k * 4f
                    val sy = b - 10 - k * 1.5f + sin(t * 3 - k * 0.6f) * 3f
                    gfx.oval(sx - 5, sy - 5, 10f, 10f, blue)
                    gfx.oval(sx - 3, sy, 6f, 4f, cream)
                }
                gfx.oval(cx + f * 2 - 7, b - 30, 14f, 12f, blue)
                gfx.oval(cx + f * 6 - 4, b - 24, 8f, 6f, 0xFFF0F0F0.toInt())
                gfx.poly(floatArrayOf(cx + f * 2 - 2, cx - f * 4, cx + f * 2), floatArrayOf(b - 29, b - 36, b - 27), cream)
                gfx.oval(cx + f * 5 - 1.5f, b - 27, 3f, 3f, 0xFFE84A4A.toInt())
            }
            Mon.EGGBABY -> {
                val shell = 0xFFF4F0E0.toInt()
                gfx.oval(cx - 7, b - 13, 14f, 13f, shell)
                for (k in 0 until 3) gfx.poly(floatArrayOf(cx - 6 + k * 4f, cx - 4 + k * 4f, cx - 2 + k * 4f), floatArrayOf(b - 8, b - 11, b - 8), listOf(0xFFE84A4A, 0xFF4A8AE8, 0xFF3CB84A)[k].toInt())
                gfx.oval(cx - 6, b - 22, 12f, 11f, 0xFFF8F0D0.toInt())
                for (k in 0 until 3) gfx.poly(floatArrayOf(cx - 3 + k * 3f, cx - 2 + k * 3f, cx - 1 + k * 3f), floatArrayOf(b - 21, b - 25, b - 21), 0xFFF8F0D0.toInt())
                eyes(gfx, cx + f, b - 18, f, 4.5f, 2.2f)
                gfx.oval(cx - 4 + f, b - 15, 2f, 1.2f, 0x66FF6A9A); gfx.oval(cx + 2 + f, b - 15, 2f, 1.2f, 0x66FF6A9A)
            }
            Mon.MEOWZER -> {
                val cream = 0xFFF0E0B0.toInt(); val brown = 0xFF8A6A3A.toInt()
                gfx.rect(cx - f * 7 - 0.5f, b - 18, 1.5f, 12f, brown)
                gfx.oval(cx - 5, b - 13, 10f, 11f, cream)
                gfx.oval(cx - 7 + f, b - 25, 14f, 13f, cream)
                gfx.poly(floatArrayOf(cx - 6 + f, cx - 7 + f, cx - 2 + f), floatArrayOf(b - 21, b - 29, b - 24), BLACK)
                gfx.poly(floatArrayOf(cx + 6 + f, cx + 7 + f, cx + 2 + f), floatArrayOf(b - 21, b - 29, b - 24), BLACK)
                gfx.oval(cx - 2 + f, b - 26, 4f, 4f, 0xFFFFD21F.toInt()) // the gold coin charm
                eyes(gfx, cx + f * 2, b - 20, f, 5f, 2.2f)
                gfx.rect(cx - 9 + f, b - 16, 4f, 0.6f, BLACK); gfx.rect(cx + 5 + f, b - 16, 4f, 0.6f, BLACK)
                feet(gfx, cx, b + bob, cream, walking, t, 3f)
            }
            Mon.FOXFIRE -> {
                val red = 0xFFE8603A.toInt(); val curl = 0xFFF0904A.toInt()
                for (k in 0 until 6) gfx.oval(cx - f * (9 + (k % 3) * 2f) - 3, b - 16 - (k / 3) * 4f + sin(t * 3 + k) * 1.5f, 6f, 6f, curl)
                gfx.oval(cx - 6, b - 13, 12f, 10f, red)
                gfx.oval(cx - 6 + f * 2, b - 22, 12f, 10f, red)
                gfx.oval(cx - 3 + f * 2, b - 25, 6f, 5f, curl)
                gfx.poly(floatArrayOf(cx - 5 + f * 2, cx - 7 + f * 2, cx - 1 + f * 2), floatArrayOf(b - 19, b - 28, b - 21), red)
                gfx.poly(floatArrayOf(cx + 5 + f * 2, cx + 7 + f * 2, cx + 1 + f * 2), floatArrayOf(b - 19, b - 28, b - 21), red)
                eyes(gfx, cx + f * 2, b - 18, f)
                feet(gfx, cx, b + bob, red, walking, t)
            }
            Mon.FIREPUP -> {
                val orange = 0xFFF08A3A.toInt(); val cream = 0xFFF8E8C0.toInt()
                gfx.oval(cx - f * 9 - 4, b - 17, 8f, 8f, cream)
                gfx.oval(cx - 8, b - 14, 16f, 10f, orange)
                for (k in 0 until 3) gfx.rect(cx - 5 + k * 4f, b - 13, 1.5f, 6f, BLACK)
                gfx.oval(cx - 6 + f * 4, b - 23, 12f, 11f, orange)
                gfx.oval(cx - 4 + f * 4, b - 26, 8f, 5f, cream)
                gfx.oval(cx - 2 + f * 9, b - 18, 6f, 5f, cream)
                eyes(gfx, cx + f * 5, b - 20, f, 4.5f, 2.2f)
                feet(gfx, cx, b + bob, orange, walking, t, 5f)
            }
            Mon.BLOBBY -> {
                val purple = 0xFFC8A0E8.toInt()
                val wob = sin(t * 5) * 1.5f
                gfx.oval(cx - 10 - wob, b - 12 + wob, 20f + wob * 2, 12f - wob, purple)
                gfx.oval(cx - 6, b - 18 + wob, 12f, 10f, purple)
                gfx.rect(cx - 3 + f, b - 14 + wob, 1.2f, 1.2f, BLACK); gfx.rect(cx + 2 + f, b - 14 + wob, 1.2f, 1.2f, BLACK)
                gfx.rect(cx - 2 + f, b - 11 + wob, 4f, 0.8f, BLACK)
            }
            Mon.SEASONG -> {
                val blue = 0xFF5AA0E8.toInt(); val shell = 0xFF8A8A9A.toInt(); val cream = 0xFFF4F0D0.toInt()
                gfx.oval(cx - 11, b - 14, 22f, 13f, cream)
                gfx.oval(cx - 10, b - 18, 20f, 12f, shell)
                for (k in 0 until 3) gfx.poly(floatArrayOf(cx - 7 + k * 6f, cx - 5 + k * 6f, cx - 3 + k * 6f), floatArrayOf(b - 16, b - 21, b - 16), 0xFF6A6A7A.toInt())
                gfx.rect(cx + f * 6 - 2, b - 30, 5f, 16f, blue)
                gfx.oval(cx + f * 7 - 5, b - 34, 11f, 9f, blue)
                gfx.poly(floatArrayOf(cx + f * 5, cx + f * 3, cx + f * 7), floatArrayOf(b - 32, b - 38, b - 33), shell)
                gfx.oval(cx + f * 10 - 1, b - 31, 2.5f, 2.5f, BLACK)
                gfx.oval(cx - 12, b - 7, 7f, 4f, blue); gfx.oval(cx + 5, b - 7, 7f, 4f, blue)
            }
            Mon.PENGI -> {
                val blue = 0xFF4A8AD8.toInt(); val navy = 0xFF2A4A8A.toInt()
                gfx.oval(cx - 6, b - 14, 12f, 13f, 0xFFF4F4F4.toInt())
                gfx.oval(cx - 7, b - 26, 14f, 13f, navy)
                gfx.oval(cx - 5, b - 22, 10f, 8f, 0xFFF4F4F4.toInt())
                gfx.oval(cx - 9, b - 13, 5f, 8f, navy); gfx.oval(cx + 4, b - 13, 5f, 8f, navy)
                gfx.poly(floatArrayOf(cx - 2 + f * 3, cx + 2 + f * 3, cx + f * 6), floatArrayOf(b - 19, b - 19, b - 17), 0xFFFFC82A.toInt())
                gfx.rect(cx - 5, b - 15, 10f, 1.5f, blue)
                eyes(gfx, cx + f * 2, b - 22, f, 5f, 2.4f)
                feet(gfx, cx, b + bob, 0xFFFFC82A.toInt(), walking, t, 3f)
            }
            else -> {}
        }
    }
}
