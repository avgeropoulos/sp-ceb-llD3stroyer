package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.argb
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The second batch of house styles (ICE CREAM SHOP ... TREE STUMP). Like the first ones they are about 72 wide
 * (x..x+72), stand on [bottom], and leave the door spot (x+6..x+20, the bottom 26 px) for [Homes.drawHouse].
 * Walls and roofs are opaque so painting works; windows are bright yellow or sky blue so they stay lit.
 */
object MoreHouses {
    private const val WIN = 0xFFFFE8A0.toInt()
    private const val GLASS = 0xFF8AD0F8.toInt()
    private const val DARK = 0xFF2A1A1A.toInt()
    private const val WHITE = 0xFFF4F4F4.toInt()

    fun drawBody(gfx: Gfx, type: HouseType, x: Float, b: Float, t: Float) {
        val top = b - 40
        when (type) {
            HouseType.ICE_CREAM -> {
                gfx.rect(x, top, 72f, 40f, c(0xFFF8C0D4))
                gfx.rect(x, b - 5, 72f, 5f, c(0xFFB86A8A))
                gfx.rect(x + 28, top + 12, 38f, 16f, c(0xFF7A4A3A))
                gfx.rect(x + 30, top + 14, 34f, 12f, WIN)
                for (k in 0 until 3) gfx.oval(x + 33 + k * 10f, top + 18, 8f, 7f, c(listOf(0xFFFF9AC8, 0xFF9AE8C8, 0xFF8A5A3A)[k]))
                gfx.rect(x + 28, top + 25, 38f, 3f, WHITE)
                // A striped awning
                for (i in 0 until 6) {
                    val col = if (i % 2 == 0) c(0xFFE84A6A) else WHITE
                    gfx.rect(x - 4 + i * 80f / 6, top - 4, 80f / 6 + 0.5f, 8f, col)
                    gfx.oval(x - 4 + i * 80f / 6, top + 1, 80f / 6, 7f, col)
                }
                // A giant cone on the roof
                gfx.poly(floatArrayOf(x + 25, x + 47, x + 36), floatArrayOf(top - 30, top - 30, top - 4), c(0xFFE0A050))
                gfx.rect(x + 29, top - 24, 14f, 1.5f, c(0xFFB07830)); gfx.rect(x + 31, top - 17, 10f, 1.5f, c(0xFFB07830))
                gfx.oval(x + 23, top - 42, 26f, 18f, c(0xFF8A5A3A))
                gfx.oval(x + 25, top - 56, 22f, 19f, c(0xFFFF9AC8))
                gfx.oval(x + 32, top - 63, 8f, 8f, c(0xFFE52521))
                for (k in 0 until 4) gfx.rect(x + 28 + k * 4.5f, top - 50 + (k % 2) * 5f, 3f, 1.5f, sprinkle(k, t))
            }
            HouseType.SHOE -> {
                gfx.rect(x + 30, top - 52, 16f, 16f, c(0xFFC83A3A)) // the tongue
                gfx.rect(x - 2, top - 40, 48f, 74f, c(0xFFE84A4A))
                gfx.oval(x + 18, b - 40, 66f, 36f, c(0xFFE84A4A))
                gfx.oval(x + 60, b - 26, 24f, 20f, WHITE)
                gfx.rect(x - 6, b - 7, 92f, 7f, WHITE)
                gfx.rect(x - 6, b - 3, 92f, 3f, c(0xFF5A5A66))
                gfx.oval(x - 6, top - 46, 56f, 12f, WHITE)
                gfx.oval(x, top - 44, 44f, 7f, DARK)
                for (k in 0 until 4) gfx.rect(x + 30, top - 30 + k * 10f, 18f, 3f, WHITE)
                gfx.oval(x + 4, top - 26, 18f, 18f, c(0xFF8A2A2A))
                gfx.oval(x + 6, top - 24, 14f, 14f, WIN)
                gfx.oval(x + 47, b - 32, 12f, 11f, WIN)
                puffs(gfx, x + 18, top - 44, t)
            }
            HouseType.ROCKET -> {
                gfx.rect(x - 6, b - 4, 84f, 4f, c(0xFF8A94A4))
                gfx.poly(floatArrayOf(x + 4, x - 10, x - 10, x + 4), floatArrayOf(b - 42, b - 12, b - 4, b - 18), c(0xFFE84A4A))
                gfx.poly(floatArrayOf(x + 56, x + 70, x + 70, x + 56), floatArrayOf(b - 42, b - 12, b - 4, b - 18), c(0xFFE84A4A))
                gfx.rect(x + 4, b - 86, 52f, 82f, c(0xFFDCE0E8))
                gfx.rect(x + 44, b - 86, 12f, 82f, c(0xFFB8C0CC))
                gfx.poly(floatArrayOf(x + 4, x + 12, x + 30, x + 48, x + 56), floatArrayOf(b - 86, b - 104, b - 116, b - 104, b - 86), c(0xFFE84A4A))
                gfx.rect(x + 4, b - 34, 52f, 5f, c(0xFFE84A4A))
                for (k in 0 until 2) {
                    gfx.oval(x + 22, b - 80 + k * 22f, 18f, 18f, c(0xFF5A6070))
                    gfx.oval(x + 25, b - 77 + k * 22f, 12f, 12f, GLASS)
                }
                gfx.oval(x + 28, b - 120, 4f, 4f, if ((t * 2).toInt() % 2 == 0) 0xFFFF4A4A.toInt() else 0xFF6A1A1A.toInt())
                // Steam at the bottom, like it's about to blast off
                for (k in 0 until 3) {
                    val p = (t * 0.8f + k / 3f) % 1f
                    gfx.oval(x + 22 + (k - 1) * p * 30, b - 8 - p * 6, 10f + p * 8, 8f + p * 4, argb(((1 - p) * 150).toInt(), 0xE8E8E8))
                }
            }
            HouseType.DINO_EGG -> {
                // A baby dino peeks out of the top
                val bob = sin(t * 2) * 2 + 6
                gfx.oval(x + 22, b - 122 + bob, 28f, 24f, c(0xFF4AB84A))
                val eye = if ((t * 0.7f) % 1f < 0.1f) 1f else 5f
                for (ex in listOf(x + 28, x + 38)) {
                    gfx.oval(ex, b - 117 + bob, 6f, 6f, WHITE)
                    gfx.oval(ex + 1.5f, b - 116 + bob + (5 - eye) / 2, 3f, eye * 0.6f, DARK)
                }
                gfx.oval(x + 18, b - 130 + bob, 36f, 12f, c(0xFFF0E4C0))
                gfx.oval(x + 4, b - 104, 64f, 80f, c(0xFFF0E4C0))
                gfx.oval(x - 4, b - 70, 80f, 68f, c(0xFFF0E4C0))
                for ((dx, dy, r) in listOf(Triple(4f, -60f, 14f), Triple(48f, -92f, 11f), Triple(56f, -40f, 13f), Triple(20f, -88f, 8f), Triple(30f, -46f, 8f)))
                    gfx.oval(x + dx, b + dy, r, r * 0.8f, c(0xFF7AC85A))
                // The crack
                val cx = floatArrayOf(x + 6, x + 16, x + 26, x + 36, x + 46, x + 56, x + 66)
                for (i in 0 until 6) line(gfx, cx[i], b - 74 + (i % 2) * 6f, cx[i + 1], b - 74 + ((i + 1) % 2) * 6f, 2f, c(0xFF5A3A1A))
                gfx.oval(x + 42, b - 60, 18f, 18f, c(0xFF8A6A3A))
                gfx.oval(x + 44, b - 58, 14f, 14f, WIN)
                for (k in 0 until 5) gfx.oval(x - 10 + k * 19f, b - 10, 26f, 12f, c(if (k % 2 == 0) 0xFFB08850 else 0xFFC8A060))
            }
            HouseType.LILY_PAD -> {
                gfx.oval(x - 16, b - 9, 104f, 13f, 0x885AB8E8.toInt())
                gfx.oval(x - 10, b - 10, 92f, 12f, c(0xFF4AA84A))
                gfx.poly(floatArrayOf(x + 50, x + 82, x + 80), floatArrayOf(b - 4, b - 8, b - 1), c(0xFF3A8A3A))
                gfx.rect(x + 2, top + 2, 66f, 32f, c(0xFF9AC86A))
                for (i in 0 until 8) gfx.rect(x + 4 + i * 8.5f, top + 2, 1.5f, 32f, c(0xFF7AA84A))
                gfx.oval(x + 40, top + 10, 16f, 14f, WIN)
                // A lotus flower roof
                val tips = floatArrayOf(x - 8, x + 12, x + 35, x + 58, x + 78)
                val tipY = floatArrayOf(top - 12, top - 28, top - 38, top - 28, top - 12)
                for (k in listOf(0, 4, 1, 3, 2)) {
                    gfx.poly(floatArrayOf(tips[k] - 14 + (k - 2) * -2f, tips[k], tips[k] + 14 + (k - 2) * -2f), floatArrayOf(top + 5, tipY[k], top + 5),
                        c(if (k % 2 == 0) 0xFFFF8AC0 else 0xFFFFB8DC))
                }
                gfx.oval(x + 28, top - 8, 14f, 10f, c(0xFFFFD04A))
                // A frog hopping on the pad
                val hop = maxOf(0f, sin(t * 3)) * 8
                gfx.oval(x + 60, b - 18 - hop, 15f, 11f, c(0xFF2E9A3A))
                for (ex in listOf(x + 61, x + 68)) { gfx.oval(ex, b - 22 - hop, 5f, 5f, WHITE); gfx.oval(ex + 1.5f, b - 21 - hop, 2.5f, 2.5f, DARK) }
            }
            HouseType.PUMPKIN -> {
                gfx.rect(x + 32, b - 88, 8f, 16f, c(0xFF5A8A2A))
                gfx.poly(floatArrayOf(x + 40, x + 56, x + 64, x + 46), floatArrayOf(b - 82, b - 94, b - 80, b - 76), c(0xFF4AA83A))
                gfx.oval(x - 8, b - 72, 42f, 72f, c(0xFFD87018))
                gfx.oval(x + 38, b - 72, 42f, 72f, c(0xFFD87018))
                gfx.oval(x + 6, b - 76, 60f, 76f, c(0xFFF08A2A))
                gfx.oval(x + 24, b - 76, 24f, 76f, c(0xFFF8A040))
                // A friendly face for windows, flickering like a candle
                val glow = if (sin(t * 9) + sin(t * 5.3f) > 0.4f) 0xFFFFE87A.toInt() else 0xFFFFD860.toInt()
                gfx.poly(floatArrayOf(x + 22, x + 34, x + 28), floatArrayOf(b - 44, b - 44, b - 58), glow)
                gfx.poly(floatArrayOf(x + 42, x + 54, x + 48), floatArrayOf(b - 44, b - 44, b - 58), glow)
                gfx.poly(floatArrayOf(x + 24, x + 32, x + 35, x + 41, x + 44, x + 52, x + 60, x + 54, x + 42, x + 30),
                    floatArrayOf(b - 34, b - 34, b - 30, b - 30, b - 34, b - 34, b - 34, b - 24, b - 20, b - 24), glow)
                gfx.oval(x + 70, b - 8, 12f, 7f, c(0xFF4AA83A)); gfx.oval(x + 78, b - 12, 9f, 6f, c(0xFF4AA83A))
            }
            HouseType.CUPCAKE -> {
                gfx.poly(floatArrayOf(x - 2, x + 74, x + 68, x + 4), floatArrayOf(top, top, b, b), c(0xFF6AD8B0))
                for (i in 0 until 6) gfx.rect(x + 7 + i * 11f, top, 4f, 40f, c(0xFF48B890))
                gfx.rect(x + 40, top + 12, 20f, 16f, c(0xFF2A7A60))
                gfx.rect(x + 42, top + 14, 16f, 12f, WIN)
                gfx.oval(x - 8, top - 22, 88f, 30f, c(0xFFFFB0D4))
                for (k in 0 until 4) gfx.oval(x + 2 + k * 20f, top - 2, 10f, 12f, c(0xFFFFB0D4))
                gfx.oval(x + 2, top - 38, 68f, 26f, c(0xFFFFB0D4))
                gfx.oval(x + 14, top - 52, 44f, 22f, c(0xFFFFB0D4))
                gfx.poly(floatArrayOf(x + 27, x + 36, x + 45), floatArrayOf(top - 44, top - 62, top - 44), c(0xFFFFB0D4))
                gfx.rect(x + 37, top - 76, 1.5f, 8f, c(0xFF4A8A2A))
                gfx.oval(x + 32, top - 70, 10f, 10f, c(0xFFE52521))
                val sp = listOf(8f to -12f, 20f to -28f, 34f to -18f, 50f to -30f, 62f to -10f, 26f to -44f, 44f to -46f, 12f to -24f)
                for ((k, p) in sp.withIndex()) gfx.rect(x + p.first, top + p.second, 4f, 1.6f, sprinkle(k, t))
            }
            HouseType.PAGODA -> {
                gfx.rect(x - 4, b - 4, 80f, 4f, c(0xFF9A9AA0))
                gfx.rect(x + 2, top + 2, 68f, 36f, c(0xFFC83A2A))
                gfx.rect(x + 2, top + 2, 4f, 36f, c(0xFF8A2018)); gfx.rect(x + 66, top + 2, 4f, 36f, c(0xFF8A2018))
                gfx.rect(x + 34, top + 12, 22f, 14f, WIN)
                gfx.rect(x + 44, top + 12, 2f, 14f, c(0xFF8A2018))
                val roof = c(0xFF2A8A7A)
                gfx.poly(floatArrayOf(x - 14, x + 2, x + 70, x + 86, x + 74, x - 2), floatArrayOf(top - 4, top + 4, top + 4, top - 4, top - 14, top - 14), roof)
                gfx.rect(x + 12, top - 34, 48f, 22f, c(0xFFC83A2A))
                gfx.rect(x + 28, top - 30, 16f, 12f, WIN)
                gfx.poly(floatArrayOf(x - 2, x + 12, x + 60, x + 74, x + 62, x + 10), floatArrayOf(top - 38, top - 30, top - 30, top - 38, top - 46, top - 46), roof)
                gfx.rect(x + 22, top - 60, 28f, 16f, c(0xFFC83A2A))
                gfx.poly(floatArrayOf(x + 8, x + 20, x + 52, x + 64, x + 46, x + 26), floatArrayOf(top - 64, top - 58, top - 58, top - 64, top - 74, top - 74), roof)
                gfx.rect(x + 35, top - 88, 2f, 16f, c(0xFFE8B83A))
                gfx.oval(x + 32.5f, top - 80, 7f, 7f, c(0xFFE8B83A)); gfx.oval(x + 33.5f, top - 90, 5f, 5f, c(0xFFE8B83A))
                for ((k, lx) in listOf(x - 8, x + 80).withIndex()) {
                    val sx = lx + sin(t * 2 + k * 2) * 1.5f
                    gfx.rect(sx, top - 4, 1f, 8f, DARK)
                    gfx.oval(sx - 4, top + 3, 9f, 11f, c(0xFFE8442A))
                }
            }
            HouseType.ADOBE -> {
                val wall = c(0xFFD8925A); val shade = c(0xFFB87448); val wood = c(0xFF6A4020)
                gfx.rect(x - 2, top, 76f, 40f, wall)
                gfx.rect(x + 30, top - 28, 42f, 30f, wall)
                gfx.rect(x + 66, top - 28, 6f, 68f, shade)
                gfx.rect(x - 2, top - 2, 32f, 3f, shade); gfx.rect(x + 30, top - 30, 42f, 3f, shade)
                for (k in 0 until 4) gfx.rect(x + 33 + k * 9f, top - 22, 5f, 4f, wood)
                for (k in 0 until 3) gfx.rect(x + 3 + k * 9f, top + 4, 5f, 4f, wood)
                gfx.rect(x + 46, top - 16, 14f, 12f, c(0xFF3A8AC8)); gfx.rect(x + 48, top - 14, 10f, 8f, WIN)
                gfx.rect(x + 38, top + 12, 16f, 14f, c(0xFF3A8AC8)); gfx.rect(x + 40, top + 14, 12f, 10f, WIN)
                // A ladder up to the roof
                gfx.rect(x + 18, top - 26, 2f, 26f, wood); gfx.rect(x + 26, top - 26, 2f, 26f, wood)
                for (k in 0 until 3) gfx.rect(x + 18, top - 20 + k * 7f, 10f, 1.5f, wood)
                for (k in 0 until 4) gfx.oval(x + 58, top + 6 + k * 6f, 4f, 7f, c(0xFFE52521))
                gfx.rect(x + 80, b - 22, 6f, 22f, c(0xFF4AA84A)); gfx.rect(x + 84, b - 16, 6f, 3f, c(0xFF4AA84A)); gfx.rect(x + 88, b - 22, 3f, 9f, c(0xFF4AA84A))
            }
            HouseType.STILT -> {
                gfx.rect(x + 22, b - 8, 66f, 8f, 0xAA4A9AD8.toInt())
                for (k in 0 until 3) gfx.oval(x + 26 + ((t * 10 + k * 22) % 56), b - 7, 10f, 3f, 0x99DDF4FF.toInt())
                for (sx in listOf(x + 28, x + 48, x + 66)) gfx.rect(sx, b - 48, 4f, 48f, c(0xFF6A4A2A))
                gfx.rect(x + 2, b - 30, 24f, 30f, c(0xFF9A7A4A))
                gfx.rect(x - 4, b - 48, 82f, 5f, c(0xFF7A5430))
                gfx.rect(x + 2, b - 82, 68f, 34f, c(0xFFD8B070))
                for (k in 0 until 3) gfx.rect(x + 2, b - 74 + k * 9f, 68f, 1f, c(0xFFB0905A))
                gfx.rect(x + 42, b - 76, 16f, 14f, c(0xFF6A4A2A)); gfx.rect(x + 44, b - 74, 12f, 10f, WIN)
                gfx.rect(x + 12, b - 76, 14f, 14f, c(0xFF6A4A2A)); gfx.rect(x + 14, b - 74, 10f, 10f, WIN)
                gfx.poly(floatArrayOf(x - 10, x + 36, x + 82), floatArrayOf(b - 78, b - 110, b - 78), c(0xFFC8A040))
                gfx.rect(x - 8, b - 82, 88f, 4f, c(0xFFA8802A))
                gfx.rect(x - 4, b - 56, 1.5f, 8f, c(0xFF7A5430)); gfx.rect(x + 76, b - 56, 1.5f, 8f, c(0xFF7A5430))
                // A fish jumps now and then
                val p = (t * 0.5f) % 1f
                if (p < 0.4f) gfx.oval(x + 74 + p * 20, b - 6 - sin(p / 0.4f * 3.14f) * 14, 7f, 4f, c(0xFFFF8A3A))
            }
            HouseType.HOUSEBOAT -> {
                gfx.rect(x - 16, b - 6, 104f, 6f, 0xAA4A9AD8.toInt())
                gfx.poly(floatArrayOf(x - 12, x + 86, x + 76, x - 2), floatArrayOf(b - 18, b - 18, b - 2, b - 2), c(0xFF2A5AA8))
                gfx.rect(x - 10, b - 15, 94f, 3f, WHITE)
                for (k in 0 until 4) gfx.oval(x - 14 + ((t * 12 + k * 27) % 100), b - 4, 12f, 3f, 0x99DDF4FF.toInt())
                gfx.rect(x + 2, b - 50, 62f, 32f, c(0xFFE07A5A))
                gfx.rect(x - 2, b - 56, 70f, 6f, c(0xFF2A6A6A))
                for (px in listOf(x, x + 32, x + 64)) gfx.rect(px, b - 66, 1.5f, 10f, WHITE)
                gfx.rect(x, b - 66, 66f, 1.5f, WHITE)
                for (wx in listOf(x + 28, x + 46)) { gfx.oval(wx - 1.5f, b - 44, 15f, 15f, WHITE); gfx.oval(wx, b - 42.5f, 12f, 12f, WIN) }
                gfx.rect(x + 50, b - 74, 8f, 18f, c(0xFFE84A4A))
                puffs(gfx, x + 51, b - 76, t)
                gfx.rect(x + 78, b - 52, 1.5f, 34f, c(0xFF5A3A1A))
                gfx.poly(floatArrayOf(x + 79.5f, x + 92 + sin(t * 4) * 1.5f, x + 79.5f), floatArrayOf(b - 52, b - 47, b - 42), c(0xFFF4C83A))
                gfx.oval(x + 8, b - 64, 10f, 8f, c(0xFF4AA84A))
            }
            HouseType.TRAIN -> {
                gfx.rect(x - 12, b - 3, 96f, 3f, c(0xFF6A6A6A))
                gfx.rect(x - 8, b - 16, 88f, 5f, c(0xFF3A3A3A))
                for (wx in listOf(x - 6, x + 26, x + 46, x + 64)) {
                    gfx.oval(wx, b - 14, 12f, 12f, DARK)
                    gfx.oval(wx + 4, b - 10, 4f, 4f, c(0xFFA8A8A8))
                }
                gfx.rect(x - 6, b - 56, 84f, 41f, c(0xFFC83A2A))
                gfx.oval(x - 8, b - 64, 88f, 14f, c(0xFF5A2A20))
                gfx.rect(x + 24, b - 76, 24f, 16f, c(0xFFC83A2A))
                gfx.rect(x + 22, b - 79, 28f, 4f, c(0xFF5A2A20))
                gfx.rect(x + 27, b - 72, 7f, 7f, WIN); gfx.rect(x + 38, b - 72, 7f, 7f, WIN)
                for (k in 0 until 3) gfx.rect(x + 26 + k * 16f, b - 48, 10f, 12f, WIN)
                gfx.rect(x - 6, b - 26, 84f, 3f, c(0xFFF4C83A))
                gfx.rect(x + 60, b - 74, 5f, 12f, c(0xFF3A3A3A))
                puffs(gfx, x + 60, b - 76, t)
                gfx.oval(x + 74, b - 44, 6f, 6f, if ((t * 2).toInt() % 2 == 0) 0xFFFF4A4A.toInt() else 0xFFFFE87A.toInt())
            }
            HouseType.TEAPOT -> {
                val body = c(0xFFD8E0F0)
                gfx.poly(floatArrayOf(x + 60, x + 86, x + 93, x + 70), floatArrayOf(b - 22, b - 62, b - 58, b - 44), body)
                line(gfx, x + 2, b - 62, x - 12, b - 56, 6f, body)
                line(gfx, x - 12, b - 56, x - 12, b - 32, 6f, body)
                line(gfx, x - 12, b - 32, x + 2, b - 24, 6f, body)
                gfx.oval(x - 6, b - 76, 84f, 74f, body)
                gfx.rect(x + 8, b - 6, 56f, 6f, c(0xFFA8B0C4))
                gfx.oval(x + 12, b - 86, 48f, 16f, c(0xFFB8C4DC))
                gfx.oval(x + 30, b - 96, 12f, 12f, c(0xFFE8B83A))
                for ((fx, fy) in listOf(28f to -66f, 60f to -60f, 30f to -38f, 66f to -30f, 4f to -50f)) {
                    gfx.oval(x + fx, b + fy, 9f, 9f, c(0xFF3A6AC8))
                    gfx.oval(x + fx + 3, b + fy + 3, 3f, 3f, 0xFFFFE87A.toInt())
                }
                gfx.oval(x + 38, b - 56, 20f, 20f, c(0xFF7A8AAA))
                gfx.oval(x + 40, b - 54, 16f, 16f, WIN)
                puffs(gfx, x + 88, b - 62, t)
            }
            HouseType.CLOCK_TOWER -> {
                val stone = c(0xFFD8C8A0)
                gfx.rect(x, top, 72f, 40f, stone)
                for (k in 0 until 3) gfx.rect(x, top + 10 + k * 10f, 72f, 1f, c(0xFFB8A880))
                gfx.rect(x + 54, top + 12, 12f, 16f, WIN)
                gfx.rect(x + 30, top + 12, 12f, 16f, WIN)
                gfx.poly(floatArrayOf(x - 4, x + 76, x + 66, x + 6), floatArrayOf(top + 2, top + 2, top - 8, top - 8), c(0xFF5A6A8A))
                gfx.rect(x + 22, top - 62, 28f, 56f, c(0xFFC8B890))
                gfx.rect(x + 30, top - 28, 12f, 16f, c(0xFF3A2A1A))
                gfx.oval(x + 32 + sin(t * 3) * 1.5f, top - 24, 8f, 9f, c(0xFFE8B83A))
                gfx.poly(floatArrayOf(x + 18, x + 36, x + 54), floatArrayOf(top - 60, top - 92, top - 60), c(0xFF5A6A8A))
                gfx.rect(x + 35.5f, top - 100, 1f, 9f, DARK)
                gfx.poly(floatArrayOf(x + 36.5f, x + 44, x + 36.5f), floatArrayOf(top - 100, top - 97, top - 94), c(0xFFE84A4A))
                val cx = x + 36; val cy = top - 46
                gfx.oval(cx - 12, cy - 12, 24f, 24f, c(0xFF8A6A3A))
                gfx.oval(cx - 10, cy - 10, 20f, 20f, 0xFFFAF6EA.toInt())
                for (k in 0 until 4) { val a = k * 1.5708f; gfx.oval(cx + cos(a) * 7.5f - 1, cy + sin(a) * 7.5f - 1, 2f, 2f, DARK) }
                line(gfx, cx, cy, cx + cos(t * 0.3f - 1.57f) * 5, cy + sin(t * 0.3f - 1.57f) * 5, 1.6f, DARK)
                line(gfx, cx, cy, cx + cos(t * 3f - 1.57f) * 8, cy + sin(t * 3f - 1.57f) * 8, 1.1f, DARK)
                gfx.oval(cx - 1.5f, cy - 1.5f, 3f, 3f, c(0xFFE84A4A))
            }
            HouseType.SNOW_GLOBE -> {
                val cx = x + 36; val cy = b - 52; val r = 40f
                gfx.oval(cx - r, cy - r, r * 2, r * 2, 0x557FD8FF)
                gfx.oval(x + 2, b - 26, 68f, 18f, 0xFFF4F8FF.toInt())
                gfx.rect(x + 2, b - 40, 26f, 26f, c(0xFFC84A3A))
                gfx.poly(floatArrayOf(x - 2, x + 15, x + 32), floatArrayOf(b - 39, b - 54, b - 39), 0xFFF4F8FF.toInt())
                gfx.rect(x + 46, b - 26, 3f, 6f, c(0xFF6A4A2A))
                gfx.poly(floatArrayOf(x + 38, x + 47.5f, x + 57), floatArrayOf(b - 24, b - 48, b - 24), c(0xFF2E8A4A))
                gfx.poly(floatArrayOf(x + 41, x + 47.5f, x + 54), floatArrayOf(b - 36, b - 56, b - 36), c(0xFF3AA858))
                gfx.oval(x + 59, b - 34, 10f, 10f, 0xFFF4F8FF.toInt()); gfx.oval(x + 61, b - 42, 7f, 7f, 0xFFF4F8FF.toInt())
                for (k in 0 until 8) {
                    val fx = x + 8 + k * 8f + sin(t * 2 + k) * 2
                    val fy = b - 88 + ((t * 12 + k * 13) % 64)
                    val dx = fx - cx; val dy = fy - cy
                    if (dx * dx + dy * dy < (r - 4) * (r - 4)) gfx.oval(fx, fy, 2.5f, 2.5f, 0xFFFFFFFF.toInt())
                }
                gfx.oval(x + 8, b - 82, 12f, 22f, 0x66FFFFFF)
                gfx.oval(cx - r, cy - r, r * 2, r * 2, 0x2A7FD8FF)
                gfx.poly(floatArrayOf(x - 6, x + 78, x + 72, x), floatArrayOf(b - 16, b - 16, b, b), c(0xFF7A4A20))
                gfx.rect(x - 4, b - 11, 80f, 2f, c(0xFFE8B83A))
            }
            HouseType.BEEHIVE -> {
                val ws = floatArrayOf(84f, 80f, 72f, 62f, 48f, 30f)
                for (i in 5 downTo 0) gfx.oval(x + 36 - ws[i] / 2, b - 20 - i * 14f, ws[i], 20f, c(if (i % 2 == 0) 0xFFE8B030 else 0xFFD89A20))
                gfx.oval(x + 30, b - 100, 12f, 10f, c(0xFFC88A18))
                gfx.oval(x + 38, b - 62, 20f, 18f, c(0xFF8A5A1A))
                gfx.oval(x + 40, b - 60, 16f, 14f, WIN)
                gfx.oval(x + 50, b - 22, 12f, 8f, DARK)
                gfx.oval(x + 26, b - 40, 6f, 10f, c(0xFFF4A020)); gfx.oval(x + 62, b - 52, 5f, 9f, c(0xFFF4A020))
                for (k in 0 until 3) {
                    val a = t * 2.4f + k * 2.1f
                    val bx = x + 36 + cos(a) * 48; val by = b - 64 + sin(a * 1.3f) * 22
                    gfx.oval(bx, by, 8f, 6f, 0xFFFFD21F.toInt())
                    gfx.rect(bx + 3.5f, by, 1.5f, 6f, DARK)
                    gfx.oval(bx + 1, by - 3.5f, 5f, 4f, 0xAAFFFFFF.toInt())
                }
                for ((k, fx) in listOf(x - 10, x + 78).withIndex()) {
                    gfx.rect(fx + 3, b - 8, 1.5f, 8f, c(0xFF4AA83A))
                    gfx.oval(fx, b - 13, 8f, 7f, c(if (k == 0) 0xFFFF6AA8 else 0xFFAA7AE8))
                }
            }
            HouseType.BALLOON -> {
                val bob = sin(t * 1.5f) * 2
                val top0 = b - 122 + bob
                gfx.oval(x - 6, top0, 84f, 76f, c(0xFFE84A4A))
                gfx.oval(x + 8, top0, 56f, 76f, c(0xFFF4C83A))
                gfx.oval(x + 22, top0, 28f, 76f, c(0xFFE84A4A))
                gfx.oval(x + 33, top0, 6f, 76f, c(0xFFF4C83A))
                gfx.poly(floatArrayOf(x + 18, x + 54, x + 46, x + 26), floatArrayOf(top0 + 70, top0 + 70, top0 + 80, top0 + 80), c(0xFFB83A3A))
                for ((bx, tx) in listOf(x + 2 to x + 26, x + 22 to x + 30, x + 50 to x + 42, x + 70 to x + 46)) line(gfx, bx, b - 34, tx, top0 + 80, 1f, c(0xFF5A3A1A))
                val f = (sin(t * 12) + 1) * 2
                gfx.oval(x + 31, top0 + 80, 10f, 6f + f, 0xB0FF8A2A.toInt())
                gfx.rect(x, b - 32, 72f, 32f, c(0xFFB0804A))
                for (k in 0 until 3) gfx.rect(x, b - 24 + k * 8f, 72f, 1.5f, c(0xFF8A5A2A))
                for (k in 0 until 5) gfx.rect(x + 26 + k * 10f, b - 32, 1.5f, 32f, c(0xFF8A5A2A))
                gfx.rect(x - 2, b - 35, 76f, 4f, c(0xFF6A4020))
                gfx.rect(x + 40, b - 26, 22f, 12f, GLASS)
                gfx.oval(x + 72, b - 26, 7f, 10f, c(0xFFC8A870))
            }
            HouseType.FAIRY -> {
                gfx.poly(floatArrayOf(x - 16, x + 4, x + 8), floatArrayOf(b, b - 34, b), c(0xFF4AB84A))
                gfx.poly(floatArrayOf(x + 64, x + 92, x + 76), floatArrayOf(b, b - 36, b), c(0xFF4AB84A))
                val petal = c(0xFFC86AE8); val petal2 = c(0xFFB050D8)
                gfx.poly(floatArrayOf(x - 2, x + 6, x + 26), floatArrayOf(top - 6, top - 50, top - 18), petal2)
                gfx.poly(floatArrayOf(x + 46, x + 66, x + 74), floatArrayOf(top - 18, top - 50, top - 6), petal2)
                gfx.poly(floatArrayOf(x + 16, x + 36, x + 56), floatArrayOf(top - 16, top - 58, top - 16), petal)
                gfx.oval(x - 2, top - 34, 76f, 74f, petal)
                gfx.rect(x + 8, b - 16, 56f, 16f, petal)
                gfx.oval(x + 26, top - 18, 16f, 16f, c(0xFF8A3AA8)); gfx.oval(x + 28, top - 16, 12f, 12f, WIN)
                gfx.oval(x + 46, top + 4, 14f, 14f, c(0xFF8A3AA8)); gfx.oval(x + 48, top + 6, 10f, 10f, WIN)
                gfx.oval(x + 1, b - 33, 24f, 16f, c(0xFF3A9A3A))
                gfx.rect(x + 75, b - 8, 4f, 8f, c(0xFFF4E8D0)); gfx.oval(x + 71, b - 13, 12f, 7f, c(0xFFE52521))
                // Sparkles and a tiny fairy
                for (k in 0 until 5) {
                    val s = 1.5f + (sin(t * 4 + k * 1.3f) + 1) * 1.2f
                    gfx.oval(x - 4 + k * 19f - s / 2, top - 52 + (k * 37 % 30) - s / 2, s, s, 0xFFFFF4B0.toInt())
                }
                val fx = x + 36 + cos(t * 1.2f) * 44; val fy = top - 60 + sin(t * 2.4f) * 8
                gfx.oval(fx - 4, fy - 4, 5f, 6f, 0xAADDF4FF.toInt()); gfx.oval(fx + 1, fy - 4, 5f, 6f, 0xAADDF4FF.toInt())
                gfx.oval(fx - 1.5f, fy - 1, 4f, 5f, 0xFFFFE87A.toInt())
            }
            HouseType.HILL -> {
                gfx.rect(x + 50, b - 70, 9f, 16f, c(0xFF8A8A92))
                puffs(gfx, x + 51, b - 72, t)
                dome(gfx, x + 36, b, 50f, 60f, c(0xFF5AB84A))
                dome(gfx, x + 36, b - 44, 34f, 16f, c(0xFF6ACC5A))
                for ((k, p) in listOf(-6f to -30f, 76f to -24f, 18f to -54f, 44f to -58f, 64f to -44f, 0f to -14f).withIndex())
                    gfx.oval(x + p.first, b + p.second, 4f, 4f, sprinkle(k, 0f))
                gfx.oval(x + 30, b - 42, 18f, 18f, c(0xFF8A5A2A)); gfx.oval(x + 33, b - 39, 12f, 12f, WIN)
                gfx.oval(x + 54, b - 34, 15f, 15f, c(0xFF8A5A2A)); gfx.oval(x + 56.5f, b - 31.5f, 10f, 10f, WIN)
                // A round door frame
                gfx.oval(x + 1, b - 36, 24f, 24f, c(0xFF8A5A2A)); gfx.rect(x + 1, b - 24, 24f, 24f, c(0xFF8A5A2A))
                gfx.oval(x + 6, b - 33, 14f, 14f, 0xFF7A4A20.toInt())
                for (k in 0 until 3) gfx.rect(x + 74 + k * 6f, b - 12, 2f, 12f, c(0xFFC8A060))
                gfx.rect(x + 72, b - 9, 16f, 1.5f, c(0xFFC8A060))
            }
            HouseType.GLASS -> {
                gfx.rect(x, top + 2, 72f, 38f, c(0xFFD8DCE0))
                gfx.rect(x + 28, top + 8, 40f, 28f, GLASS)
                gfx.rect(x + 47, top + 8, 2f, 28f, c(0xFF3A3E48))
                gfx.rect(x + 28, top + 34, 40f, 2f, c(0xFF3A3E48))
                gfx.rect(x, top + 2, 26f, 9f, c(0xFFB07A4A))
                gfx.rect(x + 14, top - 28, 66f, 30f, c(0xFF4A4E58))
                gfx.rect(x + 20, top - 22, 54f, 16f, GLASS)
                for (k in 1 until 3) gfx.rect(x + 20 + k * 18f, top - 22, 2f, 16f, c(0xFF2A2E38))
                gfx.rect(x + 12, top - 31, 70f, 3f, WHITE)
                gfx.rect(x - 2, top, 18f, 3f, WHITE)
                gfx.poly(floatArrayOf(x + 22, x + 40, x + 44, x + 26), floatArrayOf(top - 31, top - 31, top - 39, top - 39), c(0xFF2A3A6A))
                gfx.poly(floatArrayOf(x + 46, x + 64, x + 68, x + 50), floatArrayOf(top - 31, top - 31, top - 39, top - 39), c(0xFF2A3A6A))
                gfx.poly(floatArrayOf(x + 30, x + 36, x + 44, x + 38), floatArrayOf(top + 34, top + 34, top + 8, top + 8), 0x55FFFFFF)
                gfx.poly(floatArrayOf(x + 54, x + 60, x + 72, x + 66), floatArrayOf(top - 7, top - 7, top - 21, top - 21), 0x55FFFFFF)
                gfx.rect(x + 74, b - 4, 18f, 4f, GLASS)
                gfx.rect(x + 78, b - 18, 2f, 14f, c(0xFF6A4A2A)); gfx.oval(x + 72, b - 30, 14f, 14f, c(0xFF4AA84A))
            }
            HouseType.SKYSCRAPER -> {
                val body = c(0xFF5A6A8A)
                gfx.rect(x + 6, b - 100, 60f, 100f, body)
                gfx.rect(x + 54, b - 100, 12f, 100f, c(0xFF48587A))
                val step = (t * 0.4f).toInt()
                for (r in 0 until 7) gfx.rect(x + 12, b - 93 + r * 10f, 48f, 6f, if ((r * 5 + step) % 3 == 0) WIN else GLASS)
                for (k in 1 until 4) gfx.rect(x + 12 + k * 12f - 1, b - 93, 2f, 68f, body)
                gfx.rect(x + 6, b - 26, 60f, 26f, c(0xFF3A3E48))
                gfx.rect(x + 28, b - 22, 32f, 22f, GLASS)
                gfx.rect(x + 43, b - 22, 2f, 22f, c(0xFF3A3E48))
                gfx.rect(x + 2, b - 30, 68f, 4f, c(0xFFE8B83A))
                gfx.rect(x + 4, b - 104, 64f, 4f, c(0xFF3A3E48))
                for (k in 0 until 3) gfx.oval(x + 8 + k * 14f, b - 112, 12f, 10f, c(0xFF4AA84A))
                gfx.rect(x + 52, b - 120, 2f, 18f, c(0xFF8A94A4))
                gfx.oval(x + 50.5f, b - 124, 5f, 5f, if ((t * 1.5f).toInt() % 2 == 0) 0xFFFF4A4A.toInt() else 0xFF6A1A1A.toInt())
            }
            HouseType.CACTUS -> {
                val green = c(0xFF4AA84A); val dark = c(0xFF2E8A3A)
                gfx.oval(x - 14, b - 10, 100f, 14f, c(0xFFE8C888))
                gfx.rect(x - 12, top - 30, 10f, 26f, green); gfx.oval(x - 12, top - 36, 10f, 12f, green)
                gfx.rect(x - 12, top - 8, 16f, 10f, green)
                gfx.rect(x + 56, top - 20, 18f, 10f, green)
                gfx.rect(x + 64, top - 50, 10f, 40f, green); gfx.oval(x + 64, top - 56, 10f, 12f, green)
                gfx.rect(x + 2, top - 50, 54f, 90f, green)
                gfx.oval(x + 2, top - 74, 54f, 50f, green)
                for (k in 0 until 4) gfx.rect(x + 9 + k * 13f, top - 60, 2f, 98f, dark)
                for ((k, p) in listOf(14f to -50f, 40f to -40f, 26f to -24f, 48f to -10f, 6f to -14f, 34f to 4f, 50f to -62f).withIndex())
                    gfx.rect(x + p.first, top + p.second, 2.5f, 1.2f, if (k % 2 == 0) WHITE else 0xFFE8E8C8.toInt())
                gfx.oval(x + 24, top - 34, 18f, 18f, dark); gfx.oval(x + 27, top - 31, 12f, 12f, WIN)
                gfx.oval(x + 36, top + 6, 16f, 16f, dark); gfx.oval(x + 38.5f, top + 8.5f, 11f, 11f, WIN)
                val bloom = 10f + sin(t * 2) * 1f
                gfx.oval(x + 29 - bloom / 2, top - 82 - bloom / 2, bloom, bloom, c(0xFFFF6AA8))
                gfx.oval(x + 27, top - 84, 4f, 4f, 0xFFFFE87A.toInt())
            }
            HouseType.STUMP -> {
                val bark = c(0xFF7A4A24); val barkD = c(0xFF5A341A)
                gfx.poly(floatArrayOf(x - 14, x - 2, x + 6), floatArrayOf(b, b - 24, b), bark)
                gfx.poly(floatArrayOf(x + 66, x + 74, x + 88), floatArrayOf(b, b - 24, b), bark)
                gfx.rect(x - 2, top - 26, 76f, 66f, bark)
                for (k in 0 until 5) gfx.rect(x + 2 + k * 15f, top - 22, 2f, 62f, barkD)
                gfx.oval(x - 2, top - 34, 76f, 16f, c(0xFFE8C890))
                gfx.oval(x + 12, top - 31, 48f, 10f, c(0xFFC8A060))
                gfx.oval(x + 26, top - 28, 20f, 5f, c(0xFFE8C890))
                gfx.rect(x + 50, top - 50, 8f, 20f, c(0xFF8A8A92))
                puffs(gfx, x + 51, top - 52, t)
                gfx.rect(x + 20, top - 40, 2f, 10f, c(0xFF4AA83A))
                gfx.oval(x + 13, top - 44, 9f, 5f, c(0xFF4AB84A)); gfx.oval(x + 21, top - 46, 9f, 5f, c(0xFF4AB84A))
                gfx.oval(x + 30, top - 8, 22f, 22f, barkD); gfx.oval(x + 33, top - 5, 16f, 16f, WIN)
                gfx.rect(x + 40.5f, top - 5, 1f, 16f, barkD)
                gfx.rect(x + 54, top + 14, 14f, 12f, barkD); gfx.rect(x + 56, top + 16, 10f, 8f, WIN)
                // Mushrooms on the side
                gfx.oval(x + 70, top + 2, 12f, 7f, c(0xFFE52521)); gfx.rect(x + 72, top + 6, 4f, 3f, c(0xFFF4E8D0))
                gfx.oval(x + 72, top + 16, 9f, 5f, c(0xFFE52521))
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ Inside

    /** Wall, wallpaper stripe and floor colors for the new houses (match the outside). */
    fun room(type: HouseType): Triple<Int, Int, Int> = when (type) {
        HouseType.ICE_CREAM -> Triple(0xFFFFE0EA, 0xFF9AE8D0, 0xFFE8A0B8)
        HouseType.SHOE -> Triple(0xFFF0D8C8, 0xFFE84A4A, 0xFF8A5A3A)
        HouseType.ROCKET -> Triple(0xFFD8DCE4, 0xFF9AA4B8, 0xFF5A6070)
        HouseType.DINO_EGG -> Triple(0xFFF4ECCC, 0xFF8AC86A, 0xFF8A6A3A)
        HouseType.LILY_PAD -> Triple(0xFFC8F0C8, 0xFF7AC87A, 0xFF5AA85A)
        HouseType.PUMPKIN -> Triple(0xFFF8B060, 0xFFE07A2A, 0xFF6A4A2A)
        HouseType.CUPCAKE -> Triple(0xFFFFE8F0, 0xFFFF8AB8, 0xFF6AD8B0)
        HouseType.PAGODA -> Triple(0xFFF4E4C8, 0xFFC83A2A, 0xFF8A4A2A)
        HouseType.ADOBE -> Triple(0xFFF0C8A0, 0xFFC8784A, 0xFFB86A40)
        HouseType.STILT -> Triple(0xFFD8B888, 0xFFB0905A, 0xFF9A7040)
        HouseType.HOUSEBOAT -> Triple(0xFFDDEEF8, 0xFF3A7AC8, 0xFF9A6A3A)
        HouseType.TRAIN -> Triple(0xFFE8D0A8, 0xFFB03A2A, 0xFF6A4A30)
        HouseType.TEAPOT -> Triple(0xFFF4F8FF, 0xFF6A9AE0, 0xFFD8C8A8)
        HouseType.CLOCK_TOWER -> Triple(0xFFE8DCC0, 0xFFB8A070, 0xFF7A5A3A)
        HouseType.SNOW_GLOBE -> Triple(0xFFDDEEFF, 0xFFFFFFFF, 0xFFF4F8FF)
        HouseType.BEEHIVE -> Triple(0xFFF8D870, 0xFFE0A830, 0xFFC88A30)
        HouseType.BALLOON -> Triple(0xFFFFF0D0, 0xFFE84A4A, 0xFFB8905A)
        HouseType.FAIRY -> Triple(0xFFF0E0FF, 0xFFFFA8D8, 0xFF7AC86A)
        HouseType.HILL -> Triple(0xFFF0E0B8, 0xFF8AB85A, 0xFF8A6A3A)
        HouseType.GLASS -> Triple(0xFFF0F0F0, 0xFFD0D4D8, 0xFFB08A60)
        HouseType.SKYSCRAPER -> Triple(0xFFD8E0EC, 0xFF8AA0C0, 0xFF5A5A66)
        HouseType.CACTUS -> Triple(0xFFC8E8A8, 0xFF6AB85A, 0xFFE8C888)
        HouseType.STUMP -> Triple(0xFFD8B080, 0xFFA87A48, 0xFF6A4A2A)
        else -> Triple(0xFFF2E2C0, 0xFFF0D0C8, 0xFFB98A52)
    }.let { (a, b, f) -> Triple(a.toInt(), b.toInt(), f.toInt()) }

    /** The wallpaper pattern for the new houses, from [x0] to [x1] (the room's left and right walls). */
    fun wallpaper(gfx: Gfx, type: HouseType, x0: Float, x1: Float, stripe: Int) {
        val w = x1 - x0
        val floorY = Homes.FLOOR_Y
        when (type) {
            // Wide stripes
            HouseType.ICE_CREAM, HouseType.BALLOON -> { var x = x0; var k = 0; while (x < x1) { if (k++ % 2 == 0) gfx.rect(x, 24f, 24f, floorY - 24, stripe); x += 24f } }
            // Thin upright lines (planks, ribs)
            HouseType.PUMPKIN, HouseType.STILT, HouseType.LILY_PAD, HouseType.CACTUS, HouseType.STUMP, HouseType.GLASS ->
                { var x = x0; while (x < x1) { gfx.rect(x, 24f, 2f, floorY - 24, stripe); x += 28f } }
            // Bands across
            HouseType.PAGODA, HouseType.ADOBE, HouseType.HOUSEBOAT, HouseType.TRAIN, HouseType.HILL ->
                for (i in 0 until 4) gfx.rect(x0, 44f + i * 34f, w, 5f, stripe)
            // Dots and spots
            HouseType.DINO_EGG, HouseType.TEAPOT, HouseType.SNOW_GLOBE, HouseType.FAIRY ->
                for (i in 0 until 40) gfx.oval(x0 + (i * 59f) % w, 30f + (i * 37f) % 136f, 8f, 8f, stripe)
            // Sprinkles
            HouseType.CUPCAKE -> for (i in 0 until 44) gfx.rect(x0 + (i * 47f) % w, 32f + (i * 31f) % 134f, 8f, 3f, if (i % 3 == 0) 0xFF6AD8FF.toInt() else stripe)
            // Rivets and grids
            HouseType.ROCKET, HouseType.SKYSCRAPER -> {
                for (i in 0 until 5) gfx.rect(x0, 24f + i * 32f, w, 1.5f, stripe)
                var x = x0; while (x < x1) { gfx.rect(x, 24f, 1.5f, floorY - 24, stripe); x += 48f }
            }
            // Stitches (dashes)
            HouseType.SHOE -> for (i in 0 until 3) { var x = x0 + 4; while (x < x1) { gfx.rect(x, 50f + i * 46f, 10f, 2f, stripe); x += 18f } }
            // Bricks
            HouseType.CLOCK_TOWER -> for (i in 0 until 10) {
                gfx.rect(x0, 24f + i * 15f, w, 1f, stripe)
                var x = x0 + if (i % 2 == 0) 0f else 20f
                while (x < x1) { gfx.rect(x, 24f + i * 15f, 1f, 15f, stripe); x += 40f }
            }
            // Honeycomb
            HouseType.BEEHIVE -> for (i in 0 until 36) {
                val cx = x0 + (i % 12) * (w / 12) + (if ((i / 12) % 2 == 0) 0f else w / 24)
                val cy = 50f + (i / 12) * 44f
                gfx.poly(floatArrayOf(cx - 10, cx - 5, cx + 5, cx + 10, cx + 5, cx - 5), floatArrayOf(cy, cy - 9, cy - 9, cy, cy + 9, cy + 9), stripe)
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ Helpers

    private fun c(v: Long) = v.toInt()

    private fun sprinkle(k: Int, t: Float): Int {
        val cols = intArrayOf(0xFFFF4A6A.toInt(), 0xFF4AC8FF.toInt(), 0xFFFFD84A.toInt(), 0xFF6AE86A.toInt(), 0xFFAA7AE8.toInt(), 0xFFFFFFFF.toInt())
        return cols[(k + (t * 2).toInt()) % cols.size]
    }

    /** A thick straight line from (x1, y1) to (x2, y2). */
    private fun line(gfx: Gfx, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, color: Int) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
        val nx = -dy / len * w / 2; val ny = dx / len * w / 2
        gfx.poly(floatArrayOf(x1 + nx, x2 + nx, x2 - nx, x1 - nx), floatArrayOf(y1 + ny, y2 + ny, y2 - ny, y1 - ny), color)
    }

    /** A half oval sitting on [by], [rx] wide each way and [ry] tall. */
    private fun dome(gfx: Gfx, cx: Float, by: Float, rx: Float, ry: Float, color: Int) {
        val n = 14
        val xs = FloatArray(n + 1) { i -> cx + cos(Math.PI * i / n).toFloat() * rx }
        val ys = FloatArray(n + 1) { i -> by - sin(Math.PI * i / n).toFloat() * ry }
        gfx.poly(xs, ys, color)
    }

    /** Little puffs of smoke or steam rising from (x, y). */
    private fun puffs(gfx: Gfx, x: Float, y: Float, t: Float) {
        for (i in 0 until 3) {
            val p = (t * 0.6f + i / 3f) % 1f
            gfx.oval(x + sin(p * 6) * 3, y - p * 22, 6f + p * 6, 6f + p * 6, argb(((1 - p) * 150).toInt(), 0xDDDDDD))
        }
    }
}
