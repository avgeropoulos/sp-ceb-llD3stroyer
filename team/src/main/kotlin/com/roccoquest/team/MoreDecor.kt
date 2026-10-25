package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.argb
import com.roccoquest.game.mixColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pictures for the newer [DecorItem]s (BUNK_BEDS onwards). Same rules as [Decor.draw]: stand on the floor line
 * [fy] with the left edge at [x]; wall things (layer 1) hang around y = 40..90; flat things (layer 2) lie just
 * below [fy]. Each one stays small (about 25 shapes at most) so phones don't lag.
 */
object MoreDecor {
    private const val INK = 0xFF1A1420.toInt()
    private const val WHITE = 0xFFF4F4F4.toInt()
    private const val RED = 0xFFE84A4A.toInt()
    private const val BLUE = 0xFF3A6AD8.toInt()
    private const val SKY = 0xFF6AC8F0.toInt()
    private const val GREEN = 0xFF3AA84A.toInt()
    private const val LEAF = 0xFF4AB85A.toInt()
    private const val YELLOW = 0xFFF4D84A.toInt()
    private const val GOLD = 0xFFE8C83A.toInt()
    private const val ORANGE = 0xFFF08A3A.toInt()
    private const val PINK = 0xFFFF8AC0.toInt()
    private const val PURPLE = 0xFF8A5AD8.toInt()
    private const val WOOD = 0xFFA87A4A.toInt()
    private const val DWOOD = 0xFF7A4A20.toInt()
    private const val GREY = 0xFFB8C0CC.toInt()
    private const val DGREY = 0xFF3A3A44.toInt()
    private const val STONE = 0xFF9A9AA8.toInt()
    private const val SAND = 0xFFF0D090.toInt()
    private const val BONE = 0xFFF4ECD8.toInt()
    private val BALLS = intArrayOf(RED, YELLOW, BLUE, LEAF, PINK, ORANGE)

    /** A five-pointed star centred at ([cx], [cy]); [sy] squashes it (flat rugs). */
    private fun star(gfx: Gfx, cx: Float, cy: Float, r: Float, color: Int, sy: Float = 1f, spin: Float = 0f) {
        val xs = FloatArray(10); val ys = FloatArray(10)
        for (i in 0 until 10) {
            val a = -PI.toFloat() / 2 + spin + i * PI.toFloat() / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            xs[i] = cx + cos(a) * rr; ys[i] = cy + sin(a) * rr * sy
        }
        gfx.poly(xs, ys, color)
    }

    /** A thick straight line from (x1, y1) to (x2, y2). */
    private fun line(gfx: Gfx, x1: Float, y1: Float, x2: Float, y2: Float, th: Float, color: Int) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
        val nx = -dy / len * th / 2; val ny = dx / len * th / 2
        gfx.poly(floatArrayOf(x1 + nx, x2 + nx, x2 - nx, x1 - nx), floatArrayOf(y1 + ny, y2 + ny, y2 - ny, y1 - ny), color)
    }

    /** A gold picture frame on the wall with a [bg] canvas; returns nothing, draw the picture inside after. */
    private fun frame(gfx: Gfx, x: Float, w: Float, h: Float, bg: Int) {
        gfx.rect(x, 44f, w, h, 0xFFC8A030.toInt()); gfx.rect(x + 2.5f, 46.5f, w - 5, h - 5, bg)
    }

    fun draw(gfx: Gfx, d: DecorItem, x: Float, fy: Float, t: Float) {
        val w = d.w
        when (d) {
            // ================= Inside =================
            DecorItem.BUNK_BEDS -> {
                gfx.rect(x, fy - 62, 4f, 62f, DWOOD); gfx.rect(x + w - 4, fy - 62, 4f, 62f, DWOOD)
                gfx.rect(x, fy - 10, w, 4f, DWOOD); gfx.rect(x, fy - 40, w, 4f, DWOOD)
                gfx.rect(x + 4, fy - 16, w - 8, 6f, BLUE); gfx.oval(x + 5, fy - 20, 12f, 6f, WHITE)
                gfx.rect(x + 4, fy - 46, w - 8, 6f, RED); gfx.oval(x + 5, fy - 50, 12f, 6f, WHITE)
                gfx.rect(x + 4, fy - 54, w - 8, 3f, DWOOD)
                gfx.rect(x + w - 16, fy - 40, 2f, 40f, WOOD); gfx.rect(x + w - 8, fy - 40, 2f, 40f, WOOD)
                for (k in 0 until 4) gfx.rect(x + w - 16, fy - 34 + k * 8f, 10f, 2f, WOOD)
                gfx.oval(x + 22, fy - 50, 8f, 6f, YELLOW) // a teddy-coloured blob up top
            }
            DecorItem.GAMING_CHAIR -> {
                gfx.rect(x + 2, fy - 3, 20f, 2f, DGREY); gfx.oval(x, fy - 3, 4f, 3f, INK); gfx.oval(x + 20, fy - 3, 4f, 3f, INK)
                gfx.rect(x + 11, fy - 14, 2f, 11f, DGREY)
                gfx.rect(x + 3, fy - 48, 18f, 32f, 0xFF2A2A34.toInt()); gfx.rect(x + 5, fy - 46, 3f, 28f, RED); gfx.rect(x + 16, fy - 46, 3f, 28f, RED)
                gfx.oval(x + 7, fy - 46, 10f, 6f, RED)
                gfx.rect(x + 1, fy - 18, 22f, 5f, 0xFF2A2A34.toInt()); gfx.rect(x, fy - 26, 5f, 2f, INK); gfx.rect(x + 19, fy - 26, 5f, 2f, INK)
                gfx.rect(x + 1, fy - 26, 2f, 9f, INK); gfx.rect(x + 21, fy - 26, 2f, 9f, INK)
            }
            DecorItem.PINBALL -> {
                gfx.rect(x + 3, fy - 22, 3f, 22f, INK); gfx.rect(x + w - 6, fy - 22, 3f, 22f, INK)
                gfx.rect(x + 6, fy - 66, w - 12, 30f, PURPLE); gfx.rect(x + 9, fy - 62, w - 18, 14f, 0xFF2A1A4A.toInt())
                gfx.text("PINBALL", x + w / 2, fy - 52, 5f, solidRainbow(t * 0.5f, 0f), 1)
                for (k in 0 until 4) if (((t * 4).toInt() + k) % 2 == 0) gfx.oval(x + 10 + k * 6f, fy - 45, 3f, 3f, YELLOW)
                gfx.poly(floatArrayOf(x, x + w, x + w - 6, x + 6), floatArrayOf(fy - 20, fy - 20, fy - 36, fy - 36), 0xFF2A4AA8.toInt())
                gfx.oval(x + 10, fy - 33, 6f, 4f, RED); gfx.oval(x + 22, fy - 33, 6f, 4f, YELLOW); gfx.oval(x + 16, fy - 29, 6f, 4f, LEAF)
                gfx.poly(floatArrayOf(x + 9, x + 17, x + 9), floatArrayOf(fy - 24, fy - 22, fy - 21), WHITE)
                gfx.poly(floatArrayOf(x + w - 9, x + w - 17, x + w - 9), floatArrayOf(fy - 24, fy - 22, fy - 21), WHITE)
                gfx.oval(x + w / 2 - 1.5f + sin(t * 3) * 9, fy - 31 + cos(t * 2) * 4, 3f, 3f, GREY)
            }
            DecorItem.ROBOT_BUTLER -> {
                val b = sin(t * 2) * 1.5f
                gfx.oval(x + 3, fy - 6, 18f, 6f, DGREY)
                gfx.rect(x + 5, fy - 30 + b, 14f, 24f - b, GREY); gfx.rect(x + 8, fy - 22 + b, 8f, 6f, 0xFF4AB8F0.toInt())
                gfx.poly(floatArrayOf(x + 8, x + 12, x + 8), floatArrayOf(fy - 30 + b, fy - 28 + b, fy - 26 + b), RED)
                gfx.poly(floatArrayOf(x + 16, x + 12, x + 16), floatArrayOf(fy - 30 + b, fy - 28 + b, fy - 26 + b), RED)
                gfx.rect(x + 6, fy - 44 + b, 12f, 12f, 0xFF9AA4B8.toInt())
                gfx.oval(x + 8, fy - 40 + b, 3f, 3f, 0xFF3AE8F0.toInt()); gfx.oval(x + 13, fy - 40 + b, 3f, 3f, 0xFF3AE8F0.toInt())
                gfx.rect(x + 9, fy - 35 + b, 6f, 1f, INK)
                gfx.rect(x + 11.5f, fy - 49 + b, 1f, 5f, INK); gfx.oval(x + 10.5f, fy - 51 + b, 3f, 3f, RED)
                gfx.rect(x + 19, fy - 26 + b, 3f, 8f, GREY); gfx.rect(x + 2, fy - 26 + b, 3f, 10f, GREY)
                gfx.rect(x + 16, fy - 27 + b, 10f, 1.5f, WHITE)
                gfx.rect(x + 19, fy - 31 + b, 4f, 4f, WHITE); gfx.rect(x + 19.5f, fy - 30 + b, 3f, 1.5f, 0xFF8A4A2A.toInt())
            }
            DecorItem.FISH_WALL -> {
                gfx.rect(x, 40f, w, 42f, DGREY); gfx.rect(x + 2, 42f, w - 4, 38f, SKY)
                gfx.rect(x + 2, 74f, w - 4, 6f, SAND)
                for (k in 0 until 3) gfx.oval(x + 6 + k * 20f + sin(t + k) * 1.5f, 62f, 4f, 14f, GREEN)
                for (k in 0 until 3) {
                    val dir = if (k % 2 == 0) 1f else -1f
                    val p = ((t * (8f + k * 3) + k * 17) % (w - 16))
                    val fx = if (dir > 0) x + 4 + p else x + w - 14 - p
                    gfx.oval(fx, 48f + k * 8f, 9f, 5f, intArrayOf(ORANGE, YELLOW, PINK)[k])
                    gfx.poly(floatArrayOf(fx + if (dir > 0) 0f else 9f, fx + if (dir > 0) -3f else 12f, fx + if (dir > 0) -3f else 12f), floatArrayOf(50.5f + k * 8f, 48f + k * 8f, 53f + k * 8f), intArrayOf(ORANGE, YELLOW, PINK)[k])
                }
                for (k in 0 until 2) gfx.oval(x + 40 + k * 6f, 74f - ((t * 12 + k * 15) % 30), 3f, 3f, 0xAAFFFFFF.toInt())
            }
            DecorItem.RED_BEANBAG, DecorItem.BLUE_BEANBAG, DecorItem.PURPLE_BEANBAG -> {
                val c = when (d) { DecorItem.RED_BEANBAG -> RED; DecorItem.BLUE_BEANBAG -> BLUE; else -> PURPLE }
                gfx.oval(x, fy - 20, w, 21f, mixColor(c, INK, 0.25f))
                gfx.oval(x + 2, fy - 24, w - 6, 18f, c)
                gfx.oval(x + 6, fy - 20, w - 14, 8f, mixColor(c, INK, 0.2f))
                gfx.oval(x + 4, fy - 22, 6f, 3f, mixColor(c, WHITE, 0.45f))
            }
            DecorItem.FISH_BOWL -> {
                gfx.rect(x + 2, fy - 4, w - 4, 4f, DWOOD)
                gfx.oval(x, fy - 20, w, 17f, 0xAA9AD8F8.toInt())
                gfx.rect(x + 3, fy - 8, w - 6, 3f, 0xFFE8A0C0.toInt())
                gfx.oval(x + 3, fy - 21, w - 6, 4f, 0xCCDDF4FF.toInt())
                val fx = x + 5 + sin(t * 1.5f) * 3
                gfx.oval(fx, fy - 14, 7f, 4f, ORANGE)
                gfx.poly(floatArrayOf(fx + if (cos(t * 1.5f) > 0) 0f else 7f, fx + if (cos(t * 1.5f) > 0) -3f else 10f, fx + if (cos(t * 1.5f) > 0) -3f else 10f), floatArrayOf(fy - 12, fy - 14, fy - 10), ORANGE)
                gfx.oval(x + 3, fy - 17, 2f, 4f, 0x99FFFFFF.toInt())
            }
            DecorItem.PIANO -> {
                gfx.rect(x, fy - 46, w, 46f, 0xFF201A24.toInt()); gfx.rect(x - 1, fy - 48, w + 2, 3f, 0xFF3A3040.toInt())
                gfx.rect(x - 3, fy - 26, w + 6, 6f, WHITE)
                for (k in 0 until 7) gfx.rect(x + 2 + k * 7f, fy - 26, 3f, 3.5f, INK)
                gfx.rect(x + 2, fy - 20, 3f, 20f, 0xFF3A3040.toInt()); gfx.rect(x + w - 5, fy - 20, 3f, 20f, 0xFF3A3040.toInt())
                gfx.rect(x + w / 2 - 8, fy - 42, 16f, 12f, BONE)
                for (k in 0 until 3) gfx.rect(x + w / 2 - 6, fy - 39 + k * 3f, 12f, 0.7f, INK)
                gfx.rect(x + 8, fy - 14, 4f, 2f, GOLD); gfx.rect(x + w - 12, fy - 14, 4f, 2f, GOLD)
                val p = t % 1.5f / 1.5f
                gfx.oval(x + w - 10 + p * 6, fy - 56 - p * 10, 4f, 3f, argb(((1 - p) * 255).toInt(), 0xF4D84A))
                gfx.rect(x + w - 7 + p * 6, fy - 64 - p * 10, 1f, 8f, argb(((1 - p) * 255).toInt(), 0xF4D84A))
            }
            DecorItem.HARP -> {
                gfx.rect(x - 1, fy - 4, w + 2, 4f, 0xFFC8A030.toInt())
                gfx.rect(x + 1, fy - 60, 4f, 58f, GOLD); gfx.oval(x - 1, fy - 64, 8f, 8f, GOLD)
                gfx.poly(floatArrayOf(x + 1, x + w / 2, x + w, x + w, x + w / 2, x + 3), floatArrayOf(fy - 62, fy - 54, fy - 62, fy - 55, fy - 48, fy - 56), GOLD)
                for (k in 0 until 6) {
                    val sx = x + 7 + k * 3.4f
                    val top = fy - 56 + abs(sx - x - w / 2) * 0.2f
                    val bot = fy - 6 - (sx - x - 6) / (w - 8) * 46
                    gfx.rect(sx + if (k == ((t * 3).toInt() % 6)) sin(t * 40) * 0.5f else 0f, top, 0.8f, bot - top, 0xFFFFF4C8.toInt())
                }
                line(gfx, x + 5, fy - 4, x + w - 1, fy - 54, 5f, 0xFFC8A030.toInt())
            }
            DecorItem.DRUM_SET -> {
                val hit = abs(sin(t * 5))
                gfx.rect(x + 3, fy - 34, 1.5f, 34f, GREY); gfx.oval(x - 2, fy - 36 - hit, 12f, 3f, GOLD)
                gfx.rect(x + w - 5, fy - 40, 1.5f, 40f, GREY)
                gfx.poly(floatArrayOf(x + w - 14, x + w + 4, x + w + 4, x + w - 14), floatArrayOf(fy - 40 + sin(t * 6) * 2, fy - 44 - sin(t * 6) * 2, fy - 42 - sin(t * 6) * 2, fy - 38 + sin(t * 6) * 2), GOLD)
                gfx.rect(x + 6, fy - 18, 11f, 9f, RED); gfx.oval(x + 6, fy - 20, 11f, 4f, WHITE); gfx.rect(x + 10, fy - 9, 1.5f, 9f, GREY)
                gfx.oval(x + 14, fy - 24, 22f, 24f, mixColor(RED, INK, 0.2f)); gfx.oval(x + 17, fy - 21, 16f, 18f, WHITE)
                gfx.oval(x + 21, fy - 16, 8f, 8f, YELLOW)
                gfx.rect(x + 15, fy - 32, 9f, 7f, RED); gfx.oval(x + 15, fy - 34, 9f, 3f, WHITE)
                gfx.rect(x + 27, fy - 32, 9f, 7f, RED); gfx.oval(x + 27, fy - 34, 9f, 3f, WHITE)
                gfx.rect(x + w - 12, fy - 16, 10f, 12f, RED); gfx.oval(x + w - 12, fy - 18, 10f, 4f, WHITE)
                line(gfx, x + 18, fy - 44 + hit * 6, x + 22, fy - 34, 1.2f, WOOD)
            }
            DecorItem.DINO_SKELETON -> {
                gfx.rect(x, fy - 5, w, 5f, DWOOD)
                gfx.rect(x + 20, fy - 30, 4f, 25f, BONE); gfx.rect(x + 28, fy - 30, 4f, 25f, BONE)
                gfx.rect(x + 40, fy - 30, 4f, 25f, BONE); gfx.rect(x + 48, fy - 30, 4f, 25f, BONE)
                for (k in 0 until 7) gfx.oval(x + 12 + k * 7f, fy - 44 - sin(k / 6f * PI.toFloat()) * 6, 7f, 6f, BONE)
                for (k in 0 until 4) gfx.rect(x + 24 + k * 6f, fy - 40 - sin((k + 2) / 6f * PI.toFloat()) * 4, 1.5f, 14f, BONE)
                gfx.oval(x + 6, fy - 40, 7f, 5f, BONE); gfx.oval(x + 1, fy - 35, 6f, 4f, BONE); gfx.oval(x - 3, fy - 30, 5f, 3f, BONE)
                gfx.oval(x + 56, fy - 52, 6f, 8f, BONE)
                gfx.oval(x + 58, fy - 62, 14f, 10f, BONE); gfx.rect(x + 60, fy - 54, 12f, 3f, BONE)
                gfx.oval(x + 62, fy - 60, 3f, 3f, INK)
                for (k in 0 until 3) gfx.poly(floatArrayOf(x + 63 + k * 3f, x + 64.5f + k * 3f, x + 66 + k * 3f), floatArrayOf(fy - 54, fy - 56, fy - 54), WHITE)
            }
            DecorItem.ROCKET_MODEL -> {
                gfx.rect(x + 2, fy - 3, 14f, 3f, DWOOD); gfx.rect(x + 8, fy - 8, 2f, 6f, GREY)
                gfx.poly(floatArrayOf(x + 5, x + 1, x + 5), floatArrayOf(fy - 22, fy - 8, fy - 12), RED)
                gfx.poly(floatArrayOf(x + 13, x + 17, x + 13), floatArrayOf(fy - 22, fy - 8, fy - 12), RED)
                gfx.rect(x + 5, fy - 38, 8f, 28f, WHITE)
                gfx.poly(floatArrayOf(x + 5, x + 9, x + 13), floatArrayOf(fy - 38, fy - 50, fy - 38), RED)
                gfx.oval(x + 6.5f, fy - 33, 5f, 5f, 0xFF4AB8F0.toInt())
                gfx.rect(x + 5, fy - 20, 8f, 2f, BLUE)
            }
            DecorItem.TELESCOPE -> {
                line(gfx, x + 12, fy - 22, x + 3, fy, 2f, DWOOD); line(gfx, x + 12, fy - 22, x + 21, fy, 2f, DWOOD); gfx.rect(x + 11, fy - 22, 2f, 22f, DWOOD)
                line(gfx, x + 2, fy - 18, x + 26, fy - 40, 7f, BLUE)
                line(gfx, x + 20, fy - 34.5f, x + 28, fy - 42, 9f, mixColor(BLUE, INK, 0.3f))
                line(gfx, x + 1, fy - 17, x + 3, fy - 19, 4f, GOLD)
                gfx.oval(x + 10, fy - 26, 5f, 5f, GOLD)
                if ((t * 2).toInt() % 2 == 0) star(gfx, x + w + 2, fy - 54, 4f, YELLOW)
            }
            DecorItem.CUCKOO_CLOCK -> {
                val sw = sin(t * 3) * 4
                gfx.rect(x + 10.5f, 72f, 1f, 18f, INK); gfx.oval(x + 8 + sw, 88f, 6f, 6f, GOLD)
                gfx.rect(x + 6, 72f, 1f, 12f + sin(t) * 2, INK); gfx.oval(x + 4.5f, 83f + sin(t) * 2, 4f, 7f, DWOOD)
                gfx.rect(x + 15, 72f, 1f, 16f - sin(t) * 2, INK); gfx.oval(x + 13.5f, 87f - sin(t) * 2, 4f, 7f, DWOOD)
                gfx.rect(x + 2, 52f, w - 4, 22f, WOOD)
                gfx.poly(floatArrayOf(x - 1, x + w / 2, x + w + 1), floatArrayOf(53f, 40f, 53f), DWOOD)
                gfx.oval(x + 5, 58f, 12f, 12f, BONE)
                gfx.rect(x + 10.5f, 60f, 1f, 5f, INK); gfx.rect(x + 11f, 64f, 4f, 1f, INK)
                gfx.rect(x + 8, 45f, 6f, 6f, INK)
                if (t % 4f < 1f) { gfx.oval(x + 8, 44f, 7f, 6f, YELLOW); gfx.poly(floatArrayOf(x + 6, x + 8.5f, x + 8.5f), floatArrayOf(46.5f, 45.5f, 47.5f), ORANGE); gfx.oval(x + 9, 45.5f, 1.5f, 1.5f, INK) }
            }
            DecorItem.NEON_SIGN -> {
                gfx.rect(x, 44f, w, 32f, 0xFF201830.toInt())
                val on = sin(t * 7) > -0.85f
                if (on) gfx.oval(x - 4, 40f, w + 8, 40f, 0x33FF4AD8)
                star(gfx, x + w / 2, 60f, 13f, if (on) 0xFFFF6AE0.toInt() else 0xFF6A3A60.toInt())
                star(gfx, x + w / 2, 60f, 7f, if (on) 0xFFFFD8F8.toInt() else 0xFF6A3A60.toInt())
                gfx.text("WOW", x + w / 2, 75f, 5f, if (on) 0xFF6AF0FF.toInt() else 0xFF2A4A60.toInt(), 1)
            }
            DecorItem.VOLCANO_PICTURE -> {
                frame(gfx, x, w, 34f, 0xFFFFB86A.toInt())
                gfx.poly(floatArrayOf(x + 3, x + 12, x + 18, x + w - 3), floatArrayOf(76f, 56f, 56f, 76f), 0xFF6A3A2A.toInt())
                gfx.poly(floatArrayOf(x + 12, x + 18, x + 17, x + 15, x + 13), floatArrayOf(56f, 56f, 64f, 60f, 63f), RED)
                val p = t * 0.5f % 1f
                gfx.oval(x + 12 - p * 2, 50f - p * 4, 6f + p * 3, 5f, 0xCC8A8A9A.toInt())
                gfx.oval(x + 14 + p * 2, 47f - p * 3, 5f, 4f, 0xAA8A8A9A.toInt())
                gfx.rect(x + 2.5f, 74f, w - 5, 2.5f, 0xFF3AA84A.toInt())
            }
            DecorItem.PLANET_PICTURE -> {
                frame(gfx, x, w, 34f, 0xFF1A1A4A.toInt())
                for (k in 0 until 4) gfx.oval(x + 5 + k * 6f, 49f + (k * 7 % 20), 1.5f, 1.5f, WHITE)
                gfx.oval(x + 4, 60f, 22f, 5f, 0xFFE8C88A.toInt())
                gfx.oval(x + 8, 52f, 14f, 14f, 0xFFE87A3A.toInt())
                gfx.rect(x + 9, 57f, 12f, 2f, 0xFFC85A2A.toInt())
                gfx.oval(x + 5, 62f, 20f, 3f, 0xFFE8C88A.toInt())
            }
            DecorItem.DASH_PICTURE -> {
                frame(gfx, x, w, 34f, 0xFFBDE8FF.toInt())
                gfx.rect(x + 2.5f, 70f, w - 5, 5.5f, 0xFF7AC86A.toInt())
                Dino.drawBody(ScaledGfx(gfx, 0.7f, x + w / 2, 72f), 0f, 0f, 1, t, false)
                gfx.oval(x + 4, 49f, 6f, 6f, YELLOW)
            }
            DecorItem.SUNSET_PICTURE -> {
                frame(gfx, x, w, 38f, 0xFFFF9A5A.toInt())
                gfx.rect(x + 2.5f, 46.5f, w - 5, 10f, 0xFF8A5AD8.toInt()); gfx.rect(x + 2.5f, 56.5f, w - 5, 6f, 0xFFFF6A9A.toInt())
                gfx.oval(x + 8, 60f, 14f, 14f, 0xFFFFE14D.toInt())
                gfx.rect(x + 2.5f, 67f, w - 5, 12.5f, 0xFF2A6AC8.toInt())
                for (k in 0 until 3) gfx.rect(x + 9 + k * 2f + sin(t + k) * 1, 69f + k * 3f, 12f - k * 3f, 1f, 0xFFFFE14D.toInt())
                gfx.poly(floatArrayOf(x + 4, x + 7, x + 10), floatArrayOf(52f, 50f, 52f), INK)
            }
            DecorItem.STAR_RUG -> {
                star(gfx, x + w / 2, fy + 12, w / 2, 0xFFE8B83A.toInt(), 0.28f)
                star(gfx, x + w / 2, fy + 11, w / 2 - 4, YELLOW, 0.28f)
                star(gfx, x + w / 2, fy + 11, w / 4, 0xFFFFF2A0.toInt(), 0.28f)
            }
            DecorItem.GREEN_RUG, DecorItem.YELLOW_RUG, DecorItem.PURPLE_RUG -> {
                val c = when (d) { DecorItem.GREEN_RUG -> LEAF; DecorItem.YELLOW_RUG -> YELLOW; else -> PURPLE }
                gfx.oval(x, fy + 4, w, 16f, c)
                gfx.oval(x + 10, fy + 7, w - 20, 10f, mixColor(c, WHITE, 0.35f))
                gfx.oval(x + 26, fy + 9.5f, w - 52, 5f, c)
            }
            DecorItem.CHECKER_RUG -> {
                gfx.rect(x - 2, fy + 3, w + 4, 16f, WHITE)
                gfx.rect(x, fy + 4, w, 14f, RED)
                for (k in 0 until 8) gfx.rect(x + k * 10f, fy + 4 + (k % 2) * 7f, 10f, 7f, 0xFF2A2A34.toInt())
            }
            DecorItem.BEAR_RUG -> {
                gfx.oval(x + 8, fy + 1, 14f, 9f, 0xFF8A5A2A.toInt()); gfx.oval(x + w - 22, fy + 1, 14f, 9f, 0xFF8A5A2A.toInt())
                gfx.oval(x + 4, fy + 3, w - 8, 18f, 0xFFB8783A.toInt())
                gfx.oval(x + w / 2 - 10, fy + 11, 20f, 8f, 0xFFF0D0A0.toInt())
                gfx.oval(x + w / 2 - 14, fy + 7, 5f, 3f, INK); gfx.oval(x + w / 2 + 9, fy + 7, 5f, 3f, INK)
                gfx.oval(x + w / 2 - 3, fy + 12, 6f, 3f, INK)
            }
            DecorItem.BOOKSHELF -> {
                gfx.rect(x, fy - 62, w, 62f, DWOOD); gfx.rect(x + 2, fy - 60, w - 4, 58f, 0xFF5A3418.toInt())
                for (row in 0 until 3) {
                    val by = fy - 60 + row * 20f
                    gfx.rect(x + 2, by + 18, w - 4, 2f, DWOOD)
                    for (k in 0 until 5) {
                        val h = 12f + ((row * 5 + k) * 7 % 5)
                        gfx.rect(x + 4 + k * 6f, by + 18 - h, 5f, h, BALLS[(row * 2 + k) % BALLS.size])
                    }
                }
            }
            DecorItem.TOY_BOX -> {
                gfx.poly(floatArrayOf(x + 2, x + w - 2, x + w + 2, x + 6), floatArrayOf(fy - 26, fy - 26, fy - 40, fy - 40), mixColor(RED, INK, 0.3f))
                gfx.oval(x + 4, fy - 32, 10f, 10f, BLUE); gfx.oval(x + 6, fy - 30, 4f, 3f, WHITE)
                gfx.oval(x + 16, fy - 34, 12f, 12f, 0xFFB8783A.toInt()); gfx.oval(x + 15, fy - 36, 5f, 5f, 0xFFB8783A.toInt()); gfx.oval(x + 24, fy - 36, 5f, 5f, 0xFFB8783A.toInt())
                gfx.oval(x + 19, fy - 31, 2f, 2f, INK); gfx.oval(x + 23, fy - 31, 2f, 2f, INK)
                gfx.rect(x + 28, fy - 40, 2f, 14f, GREY)
                gfx.rect(x, fy - 26, w, 26f, RED); gfx.rect(x, fy - 26, w, 3f, YELLOW); gfx.rect(x, fy - 3, w, 3f, YELLOW)
                gfx.text("TOYS", x + w / 2, fy - 9, 7f, YELLOW, 1)
            }
            DecorItem.INDOOR_SLIDE -> {
                gfx.rect(x, fy - 46, 3f, 46f, YELLOW); gfx.rect(x + 14, fy - 46, 3f, 46f, YELLOW)
                for (k in 0 until 4) gfx.rect(x, fy - 38 + k * 10f, 17f, 2f, YELLOW)
                gfx.rect(x - 2, fy - 48, 22f, 4f, PURPLE)
                gfx.rect(x - 2, fy - 58, 2f, 10f, PURPLE); gfx.rect(x + 18, fy - 58, 2f, 10f, PURPLE); gfx.rect(x - 2, fy - 59, 22f, 2f, PURPLE)
                gfx.poly(floatArrayOf(x + 18, x + 24, x + w, x + w - 4), floatArrayOf(fy - 48, fy - 44, fy - 4, fy - 1), 0xFF3AB8E8.toInt())
                gfx.poly(floatArrayOf(x + 18, x + 22, x + w - 3, x + w - 4), floatArrayOf(fy - 48, fy - 50, fy - 7, fy - 4), mixColor(0xFF3AB8E8.toInt(), WHITE, 0.4f))
                gfx.rect(x + w - 6, fy - 4, 8f, 4f, PURPLE)
            }
            DecorItem.BALL_PIT -> {
                for (k in 0 until 12) gfx.oval(x + 2 + (k % 6) * 10f, fy - 30 + (k / 6) * 5f + if (k % 2 == 0) 0f else 2f, 9f, 9f, BALLS[k % BALLS.size])
                val jump = abs(sin(t * 3)) * 8
                gfx.oval(x + w / 2 - 4, fy - 34 - jump, 8f, 8f, BALLS[(t.toInt()) % BALLS.size])
                gfx.rect(x, fy - 20, w, 20f, BLUE); gfx.rect(x, fy - 22, w, 3f, YELLOW)
                for (k in 0 until 4) gfx.rect(x + 6 + k * 16f, fy - 18, 6f, 14f, mixColor(BLUE, WHITE, 0.3f))
                gfx.rect(x - 2, fy - 34, 4f, 34f, RED); gfx.rect(x + w - 2, fy - 34, 4f, 34f, RED)
            }
            DecorItem.POPCORN -> {
                gfx.oval(x + 1, fy - 5, 5f, 5f, INK); gfx.oval(x + w - 6, fy - 5, 5f, 5f, INK)
                gfx.rect(x, fy - 22, w, 18f, RED); gfx.text("POP", x + w / 2, fy - 9, 6f, YELLOW, 1)
                gfx.rect(x + 1, fy - 44, w - 2, 22f, 0x99DDF4FF.toInt())
                gfx.rect(x + 1, fy - 30, w - 2, 8f, 0xFFFFF2C8.toInt())
                for (k in 0 until 6) {
                    val p = (t * 0.9f + k / 6f) % 1f
                    gfx.oval(x + 3 + k * 3.2f, fy - 32 - sin(p * PI.toFloat()) * 9, 3.5f, 3.5f, 0xFFFFF8DC.toInt())
                }
                gfx.rect(x + 1, fy - 45, 1.5f, 23f, RED); gfx.rect(x + w - 2.5f, fy - 45, 1.5f, 23f, RED)
                gfx.poly(floatArrayOf(x - 2, x + w / 2, x + w + 2), floatArrayOf(fy - 44, fy - 52, fy - 44), RED)
            }
            DecorItem.COTTON_CANDY -> {
                gfx.oval(x + 3, fy - 7, 7f, 7f, INK); gfx.oval(x + w - 10, fy - 7, 7f, 7f, INK)
                gfx.rect(x, fy - 24, w, 18f, PINK); gfx.rect(x, fy - 24, w, 3f, WHITE)
                gfx.rect(x + 2, fy - 50, 2f, 26f, WHITE); gfx.rect(x + w - 4, fy - 50, 2f, 26f, WHITE)
                for (k in 0 until 4) gfx.poly(floatArrayOf(x - 2 + k * 9f, x + 7 + k * 9f, x + 2.5f + k * 9f), floatArrayOf(fy - 50, fy - 50, fy - 44), if (k % 2 == 0) PINK else WHITE)
                gfx.rect(x - 2, fy - 54, w + 4, 4f, PINK)
                gfx.rect(x + 8, fy - 34, 1.5f, 10f, BONE); gfx.oval(x + 3, fy - 42, 11f, 10f, 0xFFFFB8E0.toInt())
                gfx.rect(x + 20, fy - 34, 1.5f, 10f, BONE); gfx.oval(x + 15, fy - 41, 11f, 9f, 0xFFA8D8FF.toInt())
                gfx.text("YUM", x + w / 2, fy - 11, 6f, WHITE, 1)
            }
            DecorItem.ARCADE_RACER -> {
                gfx.rect(x, fy - 56, 22f, 56f, 0xFF2A2A6A.toInt()); gfx.rect(x - 1, fy - 60, 24f, 6f, RED)
                gfx.rect(x + 3, fy - 50, 16f, 14f, 0xFF4AB8F0.toInt())
                gfx.poly(floatArrayOf(x + 3, x + 19, x + 13, x + 9), floatArrayOf(fy - 36, fy - 36, fy - 44, fy - 44), 0xFF5A5A64.toInt())
                gfx.rect(x + 10.5f, fy - 42 + (t * 10 % 3), 1f, 2f, WHITE)
                gfx.rect(x + 9, fy - 39, 4f, 2.5f, RED)
                gfx.rect(x + 22, fy - 30, 6f, 4f, 0xFF2A2A6A.toInt()); gfx.oval(x + 22 + sin(t * 2), fy - 38, 4f, 10f, INK)
                gfx.rect(x + 30, fy - 18, 10f, 12f, DGREY); gfx.rect(x + 32, fy - 22, 9f, 5f, RED); gfx.rect(x + 37, fy - 38, 4f, 18f, RED)
                gfx.rect(x + 22, fy - 4, 8f, 4f, DGREY)
                gfx.text("RACE", x + 11, fy - 26, 5f, YELLOW, 1)
            }
            DecorItem.DJ_BOOTH -> {
                for (sx in floatArrayOf(x, x + w - 12)) { gfx.rect(sx, fy - 34, 12f, 34f, INK); gfx.oval(sx + 2, fy - 30, 8f, 8f, DGREY); gfx.oval(sx + 1 + abs(sin(t * 8)), fy - 18 + abs(sin(t * 8)), 10f - abs(sin(t * 8)) * 2, 10f - abs(sin(t * 8)) * 2, DGREY) }
                gfx.rect(x + 13, fy - 26, w - 26, 26f, PURPLE); gfx.rect(x + 12, fy - 28, w - 24, 3f, GREY)
                for (k in 0 until 2) { val cx = x + 15 + k * 12f; gfx.oval(cx, fy - 31, 10f, 4f, INK); gfx.oval(cx + 4 + cos(t * 6 + k) * 3, fy - 30 + sin(t * 6 + k) * 1, 2f, 2f, WHITE) }
                for (k in 0 until 3) gfx.oval(x + 16 + k * 7f, fy - 16, 4f, 4f, solidRainbow(t * 0.7f + k * 0.3f, 0f))
                gfx.text("DJ", x + w / 2, fy - 4, 6f, YELLOW, 1)
            }
            DecorItem.HAMMOCK -> {
                val s = sin(t * 1.5f) * 2
                gfx.rect(x, fy - 44, 4f, 44f, DWOOD); gfx.rect(x + w - 4, fy - 44, 4f, 44f, DWOOD)
                gfx.rect(x - 2, fy - 2, 8f, 2f, DWOOD); gfx.rect(x + w - 6, fy - 2, 8f, 2f, DWOOD)
                val xs = FloatArray(10); val ys = FloatArray(10)
                for (i in 0 until 5) {
                    val u = i / 4f; val px = x + 4 + u * (w - 8); val sag = sin(u * PI.toFloat()) * 16
                    xs[i] = px + s * sin(u * PI.toFloat()); ys[i] = fy - 38 + sag
                    xs[9 - i] = px + s * sin(u * PI.toFloat()); ys[9 - i] = fy - 38 + sag + if (i == 0 || i == 4) 1f else 6f
                }
                gfx.poly(xs, ys, 0xFF3AB8C8.toInt())
                for (i in 1 until 4) gfx.rect(xs[i] - 1.5f, ys[i], 3f, ys[9 - i] - ys[i], YELLOW)
                gfx.oval(x + 10 + s, fy - 34, 10f, 6f, WHITE)
            }
            DecorItem.BLANKET_FORT -> {
                gfx.rect(x + 2, fy - 26, 3f, 26f, DWOOD); gfx.rect(x + w - 5, fy - 26, 3f, 26f, DWOOD)
                gfx.poly(floatArrayOf(x - 2, x + w / 2, x + w + 2, x + w - 2, x + 2), floatArrayOf(fy - 22, fy - 46, fy - 22, fy - 2, fy - 2), 0xFF8A6AE8.toInt())
                gfx.rect(x + 8, fy - 26, 10f, 8f, YELLOW); gfx.rect(x + w - 18, fy - 32, 9f, 9f, PINK)
                gfx.poly(floatArrayOf(x + w / 2 - 10, x + w / 2, x + w / 2 + 10), floatArrayOf(fy, fy - 26, fy), 0xFF2A1A4A.toInt())
                gfx.oval(x + w / 2 - 5, fy - 9, 10f, 6f, WHITE)
                gfx.rect(x + w / 2 - 0.5f, fy - 56, 1f, 10f, INK); gfx.poly(floatArrayOf(x + w / 2, x + w / 2 + 8 + sin(t * 4), x + w / 2), floatArrayOf(fy - 56, fy - 53, fy - 50), RED)
                for (k in 0 until 5) gfx.oval(x + 4 + k * 11f, fy - 26 - (if (k < 3) k else 4 - k) * 8f, 3f, 3f, if (((t * 3).toInt() + k) % 2 == 0) YELLOW else 0xFF8AF0FF.toInt())
            }
            DecorItem.BONSAI -> {
                gfx.rect(x + 3, fy - 7, w - 6, 7f, 0xFF3A6AA8.toInt()); gfx.rect(x + 1, fy - 8, w - 2, 2f, 0xFF2A4A88.toInt())
                line(gfx, x + 12, fy - 8, x + 8, fy - 20, 3.5f, DWOOD); line(gfx, x + 8, fy - 20, x + 14, fy - 28, 3f, DWOOD)
                line(gfx, x + 9, fy - 18, x + 18, fy - 22, 2f, DWOOD)
                gfx.oval(x, fy - 26, 12f, 7f, GREEN); gfx.oval(x + 13, fy - 28, 11f, 7f, GREEN); gfx.oval(x + 6, fy - 36, 14f, 9f, LEAF)
            }
            DecorItem.SUNFLOWER_POT -> {
                gfx.poly(floatArrayOf(x + 2, x + w - 2, x + w - 4, x + 4), floatArrayOf(fy - 10, fy - 10, fy, fy), 0xFFC86A3A.toInt())
                gfx.rect(x + w / 2 - 1, fy - 36, 2f, 26f, GREEN)
                gfx.oval(x + w / 2 - 8, fy - 22, 8f, 4f, LEAF); gfx.oval(x + w / 2, fy - 18, 8f, 4f, LEAF)
                val nod = sin(t * 1.3f) * 1.5f
                for (k in 0 until 8) { val a = k * PI.toFloat() / 4; gfx.oval(x + w / 2 - 3 + cos(a) * 6 + nod, fy - 43 + sin(a) * 6, 6f, 6f, 0xFFFFD21F.toInt()) }
                gfx.oval(x + w / 2 - 4.5f + nod, fy - 44.5f, 9f, 9f, 0xFF6A3A1A.toInt())
            }
            DecorItem.FIREPLACE -> {
                gfx.rect(x + 14, fy - 76, w - 28, 26f, 0xFFA85A4A.toInt())
                gfx.rect(x, fy - 50, w, 50f, 0xFFA85A4A.toInt())
                for (k in 0 until 4) gfx.rect(x + (k % 2) * 8f, fy - 44 + k * 11f, w - 8, 1f, 0xFF8A3A2A.toInt())
                gfx.rect(x - 3, fy - 52, w + 6, 4f, DWOOD)
                gfx.rect(x + 9, fy - 36, w - 18, 36f, 0xFF201418.toInt()); gfx.oval(x + 9, fy - 42, w - 18, 14f, 0xFF201418.toInt())
                gfx.rect(x + 12, fy - 6, w - 24, 4f, DWOOD)
                for (k in 0 until 3) { val f = sin(t * 9 + k * 2) * 2; gfx.oval(x + 13 + k * 8f, fy - 22 + f, 9f, 16f - f, 0xFFFF7A2A.toInt()); gfx.oval(x + 15.5f + k * 8f, fy - 14 + f, 4f, 8f, 0xFFFFE14D.toInt()) }
                gfx.rect(x + 4, fy - 48, 6f, 9f, RED); gfx.rect(x + 4, fy - 48, 6f, 2f, WHITE)
                gfx.rect(x + w - 10, fy - 48, 6f, 9f, LEAF); gfx.rect(x + w - 10, fy - 48, 6f, 2f, WHITE)
            }
            DecorItem.TROPHY_CASE -> {
                gfx.rect(x, fy - 64, w, 64f, DWOOD); gfx.rect(x + 3, fy - 61, w - 6, 58f, 0xFF4A3A5A.toInt())
                gfx.rect(x + 3, fy - 41, w - 6, 2f, DWOOD); gfx.rect(x + 3, fy - 21, w - 6, 2f, DWOOD)
                for (k in 0 until 2) { val tx = x + 6 + k * 14f; gfx.poly(floatArrayOf(tx, tx + 10, tx + 8, tx + 2), floatArrayOf(fy - 58, fy - 58, fy - 49, fy - 49), GOLD); gfx.rect(tx + 4, fy - 49, 2f, 5f, GOLD); gfx.rect(tx + 2, fy - 44, 6f, 3f, GOLD) }
                gfx.oval(x + 8, fy - 36, 8f, 8f, GREY); gfx.rect(x + 11, fy - 38, 2f, 3f, RED)
                gfx.oval(x + 20, fy - 36, 8f, 8f, 0xFFC87A3A.toInt()); gfx.rect(x + 23, fy - 38, 2f, 3f, BLUE)
                star(gfx, x + 12, fy - 10, 6f, GOLD); star(gfx, x + 25, fy - 10, 6f, GREY)
                gfx.poly(floatArrayOf(x + 4, x + 10, x + 6, x + 4), floatArrayOf(fy - 60, fy - 60, fy - 4, fy - 4), 0x33FFFFFF)
            }
            DecorItem.DISCO_BALL -> {
                gfx.rect(x + w / 2 - 0.5f, 26f, 1f, 18f, GREY)
                for (k in 0 until 3) { val a = t * 1.5f + k * 2.1f; gfx.poly(floatArrayOf(x + w / 2, x + w / 2 + cos(a) * 30 - 6, x + w / 2 + cos(a) * 30 + 6), floatArrayOf(55f, 95f, 95f), argb(50, intArrayOf(0xFF4AD8, 0x4AD8FF, 0xFFE14D)[k])) }
                gfx.oval(x + 2, 44f, w - 4, w - 4, 0xFFC8D0DC.toInt())
                for (k in 0 until 9) {
                    val gx = x + 5 + (k % 3) * 5f; val gy = 47f + (k / 3) * 5f
                    gfx.rect(gx, gy, 4f, 4f, if (((t * 6).toInt() + k) % 4 == 0) WHITE else if (k % 2 == 0) 0xFF9AA4B8.toInt() else 0xFFDDE4F0.toInt())
                }
            }
            DecorItem.KNIGHT_ARMOR -> {
                gfx.rect(x + 1, fy - 4, w - 2, 4f, DWOOD)
                gfx.rect(x + 7, fy - 22, 4f, 18f, GREY); gfx.rect(x + 13, fy - 22, 4f, 18f, GREY)
                gfx.rect(x + 6, fy - 42, 12f, 22f, 0xFFD8DEE8.toInt()); gfx.rect(x + 6, fy - 24, 12f, 3f, DGREY)
                gfx.rect(x + 3, fy - 42, 3f, 16f, GREY); gfx.rect(x + 18, fy - 42, 3f, 16f, GREY)
                gfx.oval(x + 6, fy - 54, 12f, 14f, 0xFFD8DEE8.toInt()); gfx.rect(x + 8, fy - 49, 8f, 1.5f, INK)
                gfx.poly(floatArrayOf(x + 11, x + 13, x + 18, x + 16), floatArrayOf(fy - 54, fy - 54, fy - 62, fy - 62), RED)
                gfx.rect(x + w - 2, fy - 66, 1.5f, 62f, DWOOD); gfx.poly(floatArrayOf(x + w - 4, x + w - 1.25f, x + w + 1.5f), floatArrayOf(fy - 66, fy - 74, fy - 66), GREY)
                gfx.oval(x - 2, fy - 38, 8f, 12f, BLUE); gfx.rect(x + 1.5f, fy - 36, 1f, 8f, YELLOW)
            }
            DecorItem.GUMBALL -> {
                gfx.rect(x + 2, fy - 4, 14f, 4f, RED); gfx.rect(x + 7, fy - 12, 4f, 8f, RED)
                gfx.rect(x + 3, fy - 20, 12f, 9f, RED); gfx.rect(x + 7, fy - 17, 4f, 4f, GREY)
                gfx.oval(x, fy - 38, w, 20f, 0xAADDF4FF.toInt())
                for (k in 0 until 7) gfx.oval(x + 2 + (k % 4) * 3.5f, fy - 27 - (k / 4) * 4f - (k % 2), 4f, 4f, BALLS[k % BALLS.size])
                gfx.oval(x + 3, fy - 35, 3f, 5f, 0x99FFFFFF.toInt())
                gfx.rect(x + 5, fy - 40, 8f, 3f, RED)
            }
            DecorItem.BIG_TV -> {
                gfx.rect(x + 4, fy - 14, w - 8, 14f, WOOD); gfx.rect(x + 8, fy - 10, w / 2 - 12, 7f, DWOOD); gfx.rect(x + w / 2 + 4, fy - 10, w / 2 - 12, 7f, DWOOD)
                gfx.rect(x + w / 2 - 3, fy - 18, 6f, 4f, INK)
                gfx.rect(x, fy - 50, w, 32f, INK); gfx.rect(x + 2, fy - 48, w - 4, 28f, 0xFF8AD0FF.toInt())
                gfx.rect(x + 2, fy - 26, w - 4, 6f, 0xFF4AB85A.toInt())
                val jx = x + 6 + (t * 10 % (w - 16)); val jy = abs(sin(t * 4)) * 8
                gfx.rect(jx, fy - 33 - jy, 5f, 7f, RED); gfx.rect(jx, fy - 34 - jy, 5f, 2f, RED)
                gfx.oval(x + w - 16, fy - 46, 8f, 8f, YELLOW)
                gfx.rect(x + 30, fy - 37, 6f, 6f, 0xFFC86A3A.toInt())
            }
            DecorItem.TREASURE_MAP -> {
                gfx.rect(x, 44f, w, 30f, 0xFFF0DCA8.toInt()); gfx.oval(x - 3, 42f, 6f, 34f, 0xFFD8B878.toInt()); gfx.oval(x + w - 3, 42f, 6f, 34f, 0xFFD8B878.toInt())
                gfx.rect(x + 4, 47f, w - 8, 24f, 0xFF8AC8E8.toInt())
                gfx.oval(x + 6, 51f, 16f, 12f, 0xFF8AC86A.toInt()); gfx.oval(x + 22, 57f, 12f, 10f, 0xFFE8D08A.toInt())
                for (k in 0 until 4) gfx.rect(x + 10 + k * 4f, 60f + (k % 2) * 2f, 2f, 1f, RED)
                gfx.text("X", x + 29, 65f, 7f, RED, 1)
                gfx.poly(floatArrayOf(x + 13, x + 15, x + 17), floatArrayOf(57f, 52f, 57f), 0xFF6A4A2A.toInt())
            }
            DecorItem.RAINBOW_WINDOW -> {
                gfx.oval(x, 42f, w, w, WHITE); gfx.rect(x, 57f, w, 30f, WHITE)
                for (k in 0 until 6) gfx.rect(x + 3 + k * 4f, 60f, 4f, 24f, solidRainbow(k / 6f, 0f))
                for (k in 0 until 3) gfx.oval(x + 3 + k * 4f, 45f + k * 4f, w - 6 - k * 8f, w - 6 - k * 8f, solidRainbow((k + 3) / 6f + t * 0.05f, 0f))
                gfx.rect(x + w / 2 - 1, 44f, 2f, 42f, WHITE); gfx.rect(x + 2, 70f, w - 4, 2f, WHITE)
                gfx.rect(x - 2, 86f, w + 4, 3f, WOOD)
            }
            DecorItem.TOY_TRAIN -> {
                gfx.rect(x - 2, fy - 3, w + 4, 2f, GREY)
                for (k in 0 until 6) gfx.rect(x + k * 10f, fy - 2, 5f, 2f, DWOOD)
                gfx.rect(x, fy - 16, 20f, 10f, BLUE); gfx.rect(x + 2, fy - 21, 16f, 5f, mixColor(BLUE, WHITE, 0.4f))
                gfx.rect(x + 24, fy - 16, 16f, 10f, RED); gfx.rect(x + 36, fy - 26, 12f, 20f, RED); gfx.rect(x + 34, fy - 28, 16f, 3f, INK)
                gfx.rect(x + 38, fy - 23, 7f, 6f, 0xFFFFF2A0.toInt()); gfx.rect(x + 27, fy - 24, 4f, 8f, INK)
                gfx.rect(x + 20, fy - 11, 4f, 2f, INK)
                for (wx in floatArrayOf(x + 2, x + 12, x + 26, x + 38)) gfx.oval(wx, fy - 9, 7f, 7f, INK)
                for (k in 0 until 2) { val p = (t * 0.8f + k * 0.5f) % 1f; gfx.oval(x + 27 - p * 6, fy - 30 - p * 16, 5f + p * 6, 5f + p * 6, argb(((1 - p) * 180).toInt(), 0xEEEEEE)) }
            }

            // ================= The backyard =================
            DecorItem.TREEHOUSE -> {
                gfx.rect(x + w / 2 - 6, fy - 70, 12f, 70f, DWOOD)
                gfx.oval(x - 4, fy - 112, w + 8, 50f, 0xFF3A9A3A.toInt()); gfx.oval(x + 6, fy - 122, w - 12, 30f, LEAF)
                gfx.rect(x - 2, fy - 52, w + 4, 5f, WOOD)
                gfx.rect(x + 8, fy - 76, w - 16, 24f, 0xFFD8A060.toInt())
                gfx.poly(floatArrayOf(x + 4, x + w / 2, x + w - 4), floatArrayOf(fy - 76, fy - 92, fy - 76), RED)
                gfx.rect(x + 14, fy - 70, 10f, 9f, SKY); gfx.rect(x + 18.5f, fy - 70, 1f, 9f, DWOOD)
                gfx.rect(x + w - 26, fy - 70, 10f, 18f, DWOOD)
                gfx.rect(x + 10, fy - 47, 2f, 47f, WOOD); gfx.rect(x + 20, fy - 47, 2f, 47f, WOOD)
                for (k in 0 until 4) gfx.rect(x + 10, fy - 40 + k * 10f, 12f, 2f, WOOD)
                gfx.poly(floatArrayOf(x + w - 2, x + w + 10 + sin(t * 3) * 2, x + w - 2), floatArrayOf(fy - 92, fy - 88, fy - 84), YELLOW); gfx.rect(x + w - 3, fy - 92, 1.5f, 40f, INK)
            }
            DecorItem.ZIP_LINE -> {
                gfx.rect(x + 4, fy - 84, 5f, 84f, DWOOD); gfx.rect(x - 2, fy - 60, 18f, 4f, WOOD)
                gfx.rect(x + w - 8, fy - 34, 5f, 34f, DWOOD)
                line(gfx, x + 6, fy - 80, x + w - 6, fy - 30, 1.2f, INK)
                val p = (t * 0.25f) % 1f
                val rx = x + 8 + p * (w - 16); val ry = fy - 79 + p * 49
                gfx.oval(rx - 3, ry - 2, 6f, 4f, RED)
                gfx.rect(rx - 0.5f, ry, 1f, 12f, INK); gfx.rect(rx - 6, ry + 11, 12f, 2f, YELLOW)
                gfx.oval(rx - 3, ry + 13, 6f, 6f, 0xFFF4C88A.toInt())
                gfx.rect(x, fy - 64, 1.5f, 4f, WOOD); gfx.rect(x + 14, fy - 64, 1.5f, 4f, WOOD); gfx.rect(x - 2, fy - 64, 18f, 1.5f, WOOD)
                for (k in 0 until 5) gfx.rect(x + 4, fy - 52 + k * 10f, 5f, 1.5f, WOOD)
            }
            DecorItem.SANDCASTLE -> {
                gfx.oval(x - 4, fy - 6, w + 8, 10f, SAND)
                gfx.rect(x + 4, fy - 20, w - 8, 16f, 0xFFE8C078.toInt())
                for (tx in floatArrayOf(x + 1, x + w - 13)) {
                    gfx.rect(tx, fy - 32, 12f, 28f, 0xFFE0B868.toInt())
                    for (k in 0 until 3) gfx.rect(tx + k * 4.5f, fy - 36, 3f, 4f, 0xFFE0B868.toInt())
                }
                gfx.rect(x + w / 2 - 6, fy - 30, 12f, 12f, 0xFFE8C078.toInt())
                gfx.oval(x + w / 2 - 4, fy - 14, 8f, 12f, 0xFF8A6A3A.toInt())
                gfx.rect(x + w / 2 - 0.5f, fy - 42, 1f, 12f, INK); gfx.poly(floatArrayOf(x + w / 2, x + w / 2 + 7 + sin(t * 4), x + w / 2), floatArrayOf(fy - 42, fy - 39, fy - 36), RED)
                gfx.oval(x + 6, fy - 4, 4f, 3f, PINK); gfx.rect(x + w + 2, fy - 10, 6f, 8f, BLUE)
            }
            DecorItem.MERRY_GO_ROUND -> {
                val cx = x + w / 2; val cy = fy - 12f
                gfx.rect(cx - 2, fy - 8, 4f, 8f, GREY)
                gfx.oval(x, cy - 4, w, 14f, mixColor(RED, INK, 0.35f))
                for (k in 0 until 8) {
                    val a1 = t * 1.2f + k * PI.toFloat() / 4; val a2 = a1 + PI.toFloat() / 4
                    gfx.poly(floatArrayOf(cx, cx + cos(a1) * w / 2, cx + cos(a2) * w / 2), floatArrayOf(cy, cy + sin(a1) * 6, cy + sin(a2) * 6), BALLS[k % 4])
                }
                gfx.rect(cx - 1.5f, cy - 18, 3f, 18f, GREY); gfx.oval(cx - 4, cy - 21, 8f, 5f, YELLOW)
                for (k in 0 until 4) {
                    val a = t * 1.2f + k * PI.toFloat() / 2 + PI.toFloat() / 8
                    val bx = cx + cos(a) * (w / 2 - 6)
                    gfx.rect(bx - 1, cy + sin(a) * 4 - 14, 2f, 14f, if (sin(a) > 0) WHITE else GREY)
                }
            }
            DecorItem.SEESAW -> {
                val a = sin(t * 1.6f) * 0.3f
                val cx = x + w / 2; val cy = fy - 14f
                gfx.poly(floatArrayOf(cx - 8, cx, cx + 8), floatArrayOf(fy, cy, fy), RED)
                val ex = cos(a) * (w / 2); val ey = sin(a) * (w / 2)
                line(gfx, cx - ex, cy - ey, cx + ex, cy + ey, 4f, YELLOW)
                for (s in intArrayOf(-1, 1)) {
                    val hx = cx + s * ex * 0.8f; val hy = cy + s * ey * 0.8f
                    gfx.rect(hx - 1, hy - 8, 2f, 8f, BLUE); gfx.rect(hx - 4, hy - 9, 8f, 2f, BLUE)
                }
                gfx.oval(cx - 2, cy - 2, 4f, 4f, INK)
            }
            DecorItem.SPLASH_PAD -> {
                gfx.oval(x, fy - 2, w, 22f, 0xFFD8DCE8.toInt())
                gfx.oval(x + 6, fy + 1, w - 12, 16f, 0xFF8AD8F8.toInt())
                for (k in 0 until 5) {
                    val nx = x + 12 + k * (w - 24) / 4; val ny = fy + 9 - (k % 2) * 3
                    val h = 14f + sin(t * 4 + k * 1.3f) * 8 + (k % 2) * 6
                    gfx.rect(nx - 1.5f, ny - h, 3f, h, 0xAA6AC8F0.toInt())
                    gfx.oval(nx - 4, ny - h - 4, 8f, 6f, 0xCCAADDFF.toInt())
                    gfx.oval(nx - 2.5f, ny - 1, 5f, 2.5f, YELLOW)
                }
            }
            DecorItem.WATER_SLIDE -> {
                gfx.oval(x + w - 46, fy - 6, 48f, 14f, WHITE); gfx.oval(x + w - 43, fy - 4, 42f, 10f, 0xFF3AA8E8.toInt())
                gfx.rect(x + 2, fy - 84, 4f, 84f, GREY); gfx.rect(x + 22, fy - 84, 4f, 84f, GREY)
                for (k in 0 until 7) gfx.rect(x + 2, fy - 76 + k * 11f, 24f, 2f, GREY)
                gfx.rect(x, fy - 86, 28f, 4f, BLUE); gfx.poly(floatArrayOf(x - 2, x + 14, x + 30), floatArrayOf(fy - 96, fy - 108, fy - 96), RED)
                gfx.rect(x + 2, fy - 96, 2f, 10f, GREY); gfx.rect(x + 24, fy - 96, 2f, 10f, GREY)
                val n = 8; val xs = FloatArray(n * 2); val ys = FloatArray(n * 2)
                for (i in 0 until n) {
                    val u = i / (n - 1f); val px = x + 26 + u * (w - 50); val py = fy - 84 + u * 80 - sin(u * PI.toFloat() * 2) * 10
                    xs[i] = px; ys[i] = py; xs[2 * n - 1 - i] = px; ys[2 * n - 1 - i] = py + 7
                }
                gfx.poly(xs, ys, 0xFFFFD21F.toInt())
                for (i in 0 until n) ys[2 * n - 1 - i] = ys[i] + 3
                gfx.poly(xs, ys, 0xFF6AC8F0.toInt())
                val p = (t * 0.35f) % 1f; val ri = (p * (n - 1)).toInt().coerceAtMost(n - 2); val f = p * (n - 1) - ri
                gfx.oval(xs[ri] + (xs[ri + 1] - xs[ri]) * f - 5, ys[ri] + (ys[ri + 1] - ys[ri]) * f - 4, 10f, 6f, PINK)
            }
            DecorItem.HOT_TUB -> {
                gfx.rect(x, fy - 22, w, 22f, WOOD)
                for (k in 0 until 5) gfx.rect(x + 6 + k * 10f, fy - 22, 1.5f, 22f, DWOOD)
                gfx.rect(x - 2, fy - 25, w + 4, 4f, DWOOD)
                gfx.oval(x + 2, fy - 27, w - 4, 6f, 0xFF4AD8D8.toInt())
                for (k in 0 until 4) { val p = (t * 2 + k * 0.7f) % 1f; gfx.oval(x + 8 + k * 12f + sin(k + t) * 2, fy - 27 - p * 4, 4f, 4f, 0xCCFFFFFF.toInt()) }
                for (k in 0 until 2) { val p = (t * 0.4f + k * 0.5f) % 1f; gfx.oval(x + 14 + k * 20f, fy - 34 - p * 22, 10f + p * 8, 7f + p * 5, argb(((1 - p) * 110).toInt(), 0xFFFFFF)) }
                gfx.rect(x + w - 10, fy - 34, 2f, 10f, GREY); gfx.rect(x + w - 14, fy - 34, 6f, 2f, GREY)
            }
            DecorItem.FLOWER_ARCH -> {
                gfx.rect(x, fy - 40, 5f, 40f, WHITE); gfx.rect(x + w - 5, fy - 40, 5f, 40f, WHITE)
                val n = 9; val xs = FloatArray(n * 2); val ys = FloatArray(n * 2)
                for (i in 0 until n) {
                    val a = PI.toFloat() + i * PI.toFloat() / (n - 1)
                    xs[i] = x + w / 2 + cos(a) * w / 2; ys[i] = fy - 40 + sin(a) * 24
                    xs[2 * n - 1 - i] = x + w / 2 + cos(a) * (w / 2 - 5); ys[2 * n - 1 - i] = fy - 40 + sin(a) * 19
                }
                gfx.poly(xs, ys, WHITE)
                gfx.oval(x - 2, fy - 30, 9f, 20f, GREEN); gfx.oval(x + w - 7, fy - 34, 9f, 22f, GREEN)
                for (k in 0 until 7) {
                    val a = PI.toFloat() + (k + 0.5f) * PI.toFloat() / 7
                    gfx.oval(x + w / 2 + cos(a) * (w / 2 - 2.5f) - 3.5f, fy - 40 + sin(a) * 21.5f - 3.5f, 7f, 7f, intArrayOf(PINK, RED, YELLOW, 0xFFFF6A9A.toInt(), PURPLE, PINK, RED)[k])
                }
            }
            DecorItem.SCARECROW -> {
                gfx.rect(x + w / 2 - 1.5f, fy - 42, 3f, 42f, DWOOD); gfx.rect(x + 1, fy - 38, w - 2, 3f, DWOOD)
                gfx.poly(floatArrayOf(x + 3, x + w - 3, x + w - 7, x + 7), floatArrayOf(fy - 40, fy - 40, fy - 22, fy - 22), RED)
                gfx.rect(x + 7, fy - 34, w - 14, 1.5f, 0xFF8A2A2A.toInt()); gfx.rect(x + w / 2 - 4, fy - 40, 1.5f, 18f, 0xFF8A2A2A.toInt())
                gfx.rect(x + 9, fy - 22, w - 18, 10f, BLUE)
                gfx.poly(floatArrayOf(x - 1, x + 3, x + 3), floatArrayOf(fy - 37, fy - 41, fy - 33), YELLOW); gfx.poly(floatArrayOf(x + w + 1, x + w - 3, x + w - 3), floatArrayOf(fy - 37, fy - 41, fy - 33), YELLOW)
                gfx.oval(x + w / 2 - 6, fy - 52, 12f, 12f, 0xFFE0C08A.toInt())
                gfx.oval(x + w / 2 - 3.5f, fy - 48, 2f, 2f, INK); gfx.oval(x + w / 2 + 1.5f, fy - 48, 2f, 2f, INK); gfx.rect(x + w / 2 - 3, fy - 44, 6f, 1f, INK)
                gfx.rect(x + w / 2 - 10, fy - 53, 20f, 2.5f, 0xFFE8C860.toInt()); gfx.rect(x + w / 2 - 5, fy - 60, 10f, 7f, 0xFFE8C860.toInt())
                val hop = abs(sin(t * 2)) * 2
                gfx.oval(x + w - 9, fy - 44 - hop, 7f, 5f, INK); gfx.poly(floatArrayOf(x + w - 3, x + w + 1, x + w - 3), floatArrayOf(fy - 43 - hop, fy - 42 - hop, fy - 41 - hop), ORANGE)
            }
            DecorItem.WINDMILL -> {
                gfx.poly(floatArrayOf(x + 6, x + w - 6, x + w - 12, x + 12), floatArrayOf(fy, fy, fy - 56, fy - 56), 0xFFF0E0C0.toInt())
                gfx.poly(floatArrayOf(x + 8, x + w / 2, x + w - 8), floatArrayOf(fy - 54, fy - 68, fy - 54), RED)
                gfx.oval(x + w / 2 - 6, fy - 14, 12f, 18f, DWOOD); gfx.rect(x + w / 2 - 6, fy - 5, 12f, 5f, DWOOD)
                gfx.rect(x + w / 2 - 3, fy - 40, 6f, 6f, SKY)
                val hx = x + w / 2; val hy = fy - 56f
                for (k in 0 until 4) {
                    val a = t * 1.5f + k * PI.toFloat() / 2
                    val ex = hx + cos(a) * 28; val ey = hy + sin(a) * 28
                    val px = -sin(a) * 4; val py = cos(a) * 4
                    gfx.poly(floatArrayOf(hx + cos(a) * 4, ex, ex + px * 1.5f, hx + cos(a) * 6 + px), floatArrayOf(hy + sin(a) * 4, ey, ey + py * 1.5f, hy + sin(a) * 6 + py), WHITE)
                    line(gfx, hx, hy, ex, ey, 1.2f, DWOOD)
                }
                gfx.oval(hx - 3, hy - 3, 6f, 6f, INK)
            }
            DecorItem.FLAMINGO -> {
                gfx.rect(x + 7, fy - 20, 1f, 20f, 0xFFFF6A9A.toInt())
                line(gfx, x + 8, fy - 20, x + 3, fy - 14, 1f, 0xFFFF6A9A.toInt()); line(gfx, x + 3, fy - 14, x + 8, fy - 12, 1f, 0xFFFF6A9A.toInt())
                gfx.oval(x + 1, fy - 28, 14f, 9f, 0xFFFF8AB8.toInt()); gfx.poly(floatArrayOf(x + 1, x - 2, x + 4), floatArrayOf(fy - 25, fy - 28, fy - 23), 0xFFFF8AB8.toInt())
                gfx.oval(x + 4, fy - 27, 7f, 4f, 0xFFFF6A9A.toInt())
                val bob = sin(t * 1.5f) * 1
                line(gfx, x + 12, fy - 26, x + 13, fy - 36 + bob, 2.5f, 0xFFFF8AB8.toInt()); line(gfx, x + 13, fy - 36 + bob, x + 11, fy - 42 + bob, 2.5f, 0xFFFF8AB8.toInt())
                gfx.oval(x + 9, fy - 46 + bob, 6f, 5f, 0xFFFF8AB8.toInt()); gfx.oval(x + 11.5f, fy - 45 + bob, 1.5f, 1.5f, INK)
                gfx.poly(floatArrayOf(x + 14, x + 18, x + 15), floatArrayOf(fy - 45 + bob, fy - 42 + bob, fy - 41 + bob), INK)
            }
            DecorItem.DASH_STATUE -> {
                gfx.rect(x, fy - 10, w, 10f, STONE); gfx.rect(x + 2, fy - 12, w - 4, 3f, 0xFFB8B8C4.toInt())
                gfx.text("DASH", x + w / 2, fy - 2.5f, 5.5f, 0xFF5A5A6A.toInt(), 1)
                Dino.drawBody(ScaledGfx(TintGfx(gfx, 0xFFA8B0A8.toInt(), 0.8f), 1.5f, x + w / 2, fy - 12), 0f, 0f, 1, 0f, false)
            }
            DecorItem.FOUNTAIN -> {
                gfx.oval(x, fy - 14, w, 14f, STONE); gfx.rect(x, fy - 8, w, 8f, STONE)
                gfx.oval(x + 4, fy - 13, w - 8, 8f, 0xFF4AA8E8.toInt())
                gfx.rect(x + w / 2 - 3, fy - 34, 6f, 24f, STONE)
                gfx.oval(x + w / 2 - 14, fy - 38, 28f, 7f, STONE); gfx.oval(x + w / 2 - 11, fy - 38, 22f, 4f, 0xFF4AA8E8.toInt())
                gfx.rect(x + w / 2 - 1, fy - 48, 2f, 10f, 0xCC8AD8FF.toInt()); gfx.oval(x + w / 2 - 4, fy - 52, 8f, 6f, 0xCCBDE8FF.toInt())
                for (k in 0 until 6) {
                    val p = (t * 0.8f + k / 6f) % 1f; val s = if (k % 2 == 0) 1f else -1f
                    gfx.oval(x + w / 2 + s * (4 + p * 18) - 1.5f, fy - 50 + p * 38 - sin(p * PI.toFloat()) * 8, 3f, 3f, 0xDD8AD8FF.toInt())
                }
                for (k in 0 until 2) gfx.oval(x + w / 2 - 12 + k * 20f + sin(t + k) * 2, fy - 11, 6f, 2f, 0x99FFFFFF.toInt())
            }
            DecorItem.CAMPFIRE -> {
                for (lx in floatArrayOf(x, x + w - 18)) { gfx.rect(lx + 2, fy - 8, 14f, 8f, DWOOD); gfx.oval(lx + 12, fy - 8, 6f, 8f, 0xFFD8A060.toInt()); gfx.oval(lx, fy - 8, 5f, 8f, DWOOD) }
                for (k in 0 until 5) gfx.oval(x + 18 + k * 6f, fy - 6, 7f, 6f, STONE)
                line(gfx, x + 22, fy - 3, x + 42, fy - 9, 3.5f, DWOOD); line(gfx, x + 22, fy - 9, x + 42, fy - 3, 3.5f, 0xFF8A5A2A.toInt())
                val fl = sin(t * 10) * 2
                gfx.oval(x + w / 2 - 7, fy - 26 + fl, 14f, 20f - fl, 0xFFFF6A2A.toInt()); gfx.oval(x + w / 2 - 4, fy - 18 + fl, 8f, 11f, 0xFFFFE14D.toInt())
                for (k in 0 until 2) { val p = (t * 0.9f + k * 0.5f) % 1f; gfx.oval(x + w / 2 + sin(p * 9 + k) * 5, fy - 28 - p * 22, 2f, 2f, argb(((1 - p) * 255).toInt(), 0xFFB83A)) }
                line(gfx, x + 10, fy - 12, x + w / 2 - 4, fy - 22, 1f, WOOD); gfx.oval(x + w / 2 - 7, fy - 25, 5f, 5f, WHITE)
            }
            DecorItem.CAMP_TENT -> {
                gfx.poly(floatArrayOf(x, x + w / 2, x + w), floatArrayOf(fy, fy - 40, fy), ORANGE)
                gfx.poly(floatArrayOf(x + w / 2, x + w / 2 + 6, x + w), floatArrayOf(fy - 40, fy - 40, fy), mixColor(ORANGE, INK, 0.25f))
                gfx.poly(floatArrayOf(x + w / 2 - 10, x + w / 2, x + w / 2 + 10), floatArrayOf(fy, fy - 28, fy), 0xFF3A2A1A.toInt())
                gfx.oval(x + w / 2 - 5, fy - 14, 10f, 10f, 0x55FFE14D)
                gfx.poly(floatArrayOf(x + w / 2, x + w / 2 - 10, x + w / 2 - 14), floatArrayOf(fy - 28, fy, fy), 0xFFF8B060.toInt())
                gfx.rect(x + w / 2 - 0.5f, fy - 46, 1f, 7f, INK)
                line(gfx, x + 2, fy - 4, x - 6, fy, 0.8f, INK); line(gfx, x + w - 2, fy - 4, x + w + 6, fy, 0.8f, INK)
                gfx.oval(x + w / 2 - 2.5f, fy - 22, 5f, 6f, YELLOW)
            }
            DecorItem.LAUNCH_PAD -> {
                gfx.rect(x - 2, fy - 6, w + 4, 6f, STONE); gfx.rect(x + 4, fy - 8, w - 8, 3f, YELLOW)
                gfx.rect(x, fy - 80, 6f, 74f, RED)
                for (k in 0 until 5) gfx.rect(x, fy - 72 + k * 14f, 14f, 2f, RED)
                val cx = x + w / 2 + 6
                gfx.poly(floatArrayOf(cx - 9, cx - 15, cx - 9), floatArrayOf(fy - 30, fy - 8, fy - 14), RED)
                gfx.poly(floatArrayOf(cx + 9, cx + 15, cx + 9), floatArrayOf(fy - 30, fy - 8, fy - 14), RED)
                gfx.rect(cx - 9, fy - 70, 18f, 60f, WHITE)
                gfx.poly(floatArrayOf(cx - 9, cx, cx + 9), floatArrayOf(fy - 70, fy - 92, fy - 70), RED)
                gfx.oval(cx - 5, fy - 60, 10f, 10f, 0xFF4AB8F0.toInt()); gfx.rect(cx - 9, fy - 40, 18f, 3f, BLUE)
                for (k in 0 until 3) { val p = (t * 0.7f + k / 3f) % 1f; gfx.oval(cx - 8 + (k - 1) * p * 16, fy - 14 - p * 4, 16f, 10f, argb(((1 - p) * 200).toInt(), 0xDDDDDD)) }
                gfx.text("${3 - (t.toInt() % 4).coerceAtMost(3)}".let { if (it == "0") "GO!" else it }, x + 3, fy - 84, 7f, YELLOW, 1)
            }
            DecorItem.MINI_GOLF -> {
                gfx.oval(x, fy, w, 20f, 0xFF2E8A3A.toInt()); gfx.oval(x + 3, fy + 2, w - 6, 16f, 0xFF5AC85A.toInt())
                gfx.oval(x + w - 22, fy + 8, 8f, 4f, INK)
                gfx.rect(x + w - 18.5f, fy - 30, 1.5f, 40f, WHITE)
                gfx.poly(floatArrayOf(x + w - 17, x + w - 5 + sin(t * 4) * 1.5f, x + w - 17), floatArrayOf(fy - 30, fy - 26, fy - 22), RED)
                val p = (t * 0.3f) % 1f
                gfx.oval(x + 8 + p * (w - 32), fy + 8 + sin(p * PI.toFloat()) * 2, 4f, 4f, WHITE)
                gfx.rect(x + 2, fy - 16, 1.5f, 22f, GREY); gfx.rect(x + 1, fy + 5, 6f, 2f, GREY)
            }
            DecorItem.BIG_HOOP -> {
                gfx.rect(x + 4, fy - 66, 4f, 66f, DGREY); gfx.rect(x + 4, fy - 66, 12f, 3f, DGREY)
                gfx.rect(x + 14, fy - 76, 4f, 26f, WHITE); gfx.rect(x + 15, fy - 68, 2f, 10f, RED)
                gfx.rect(x + 18, fy - 59, 14f, 2f, ORANGE)
                for (k in 0 until 4) gfx.rect(x + 19 + k * 3.5f, fy - 57, 1f, 8f, WHITE)
                gfx.rect(x + 19, fy - 50, 12f, 1f, WHITE)
                val b = abs(sin(t * 3)) * 26
                gfx.oval(x + 20, fy - 10 - b, 10f, 10f, ORANGE); gfx.rect(x + 20, fy - 5.5f - b, 10f, 1f, INK)
            }
            DecorItem.TIRE_SWING -> {
                gfx.rect(x + 4, fy - 76, 10f, 76f, DWOOD)
                line(gfx, x + 10, fy - 62, x + w, fy - 70, 5f, DWOOD)
                gfx.oval(x - 8, fy - 102, 40f, 34f, 0xFF3A9A3A.toInt()); gfx.oval(x + 20, fy - 92, 40f, 30f, LEAF)
                val a = sin(t * 1.8f) * 0.25f
                val px = x + w - 14; val py = fy - 68
                val tx = px + sin(a) * 40; val ty = py + cos(a) * 40
                line(gfx, px, py, tx, ty, 1.2f, 0xFFD8B878.toInt())
                gfx.oval(tx - 10, ty - 2, 20f, 16f, 0xFF2A2A30.toInt()); gfx.oval(tx - 5, ty + 3, 10f, 6f, 0xFF5A5A64.toInt())
            }
            DecorItem.LEMONADE -> {
                gfx.rect(x + 2, fy - 24, w - 4, 24f, WOOD); gfx.rect(x, fy - 26, w, 3f, DWOOD)
                gfx.rect(x + 4, fy - 20, w - 8, 10f, YELLOW); gfx.text("LEMONADE", x + w / 2, fy - 12, 5f, 0xFF8A5A2A.toInt(), 1)
                gfx.rect(x + 2, fy - 54, 3f, 28f, DWOOD); gfx.rect(x + w - 5, fy - 54, 3f, 28f, DWOOD)
                for (k in 0 until 5) gfx.poly(floatArrayOf(x - 2 + k * 11f, x + 9 + k * 11f, x + 9 + k * 11f, x - 2 + k * 11f), floatArrayOf(fy - 58, fy - 58, fy - 50, fy - 50), if (k % 2 == 0) YELLOW else WHITE)
                gfx.rect(x + 10, fy - 38, 10f, 12f, 0xAAFFF07A.toInt()); gfx.rect(x + 10, fy - 39, 10f, 2f, 0xCCDDF4FF.toInt())
                gfx.oval(x + 12, fy - 34, 5f, 5f, 0xFFF4E04A.toInt())
                for (k in 0 until 2) gfx.rect(x + 28 + k * 9f, fy - 33, 6f, 7f, 0xCCFFF07A.toInt())
            }
            DecorItem.CHICKEN_COOP -> {
                gfx.rect(x + 8, fy - 14, 3f, 14f, DWOOD); gfx.rect(x + 32, fy - 14, 3f, 14f, DWOOD)
                gfx.rect(x + 4, fy - 38, 36f, 24f, RED)
                gfx.poly(floatArrayOf(x, x + 22, x + 44), floatArrayOf(fy - 38, fy - 52, fy - 38), WHITE)
                gfx.oval(x + 16, fy - 32, 10f, 12f, INK)
                gfx.poly(floatArrayOf(x + 26, x + 30, x + 50, x + 46), floatArrayOf(fy - 22, fy - 20, fy, fy), WOOD)
                for (k in 0 until 2) {
                    val cx = x + 44 + k * 10f; val peck = if (k == 0 && sin(t * 4) > 0.3f) 3f else 0f
                    gfx.oval(cx, fy - 9, 9f, 8f, WHITE)
                    gfx.oval(cx + 5, fy - 14 + peck, 5f, 5f, WHITE); gfx.rect(cx + 6, fy - 16 + peck, 3f, 2f, RED)
                    gfx.poly(floatArrayOf(cx + 10, cx + 12, cx + 10), floatArrayOf(fy - 12.5f + peck, fy - 11.5f + peck, fy - 10.5f + peck), ORANGE)
                    gfx.oval(cx + 7.5f, fy - 13 + peck, 1.2f, 1.2f, INK)
                }
            }
            DecorItem.GREENHOUSE -> {
                gfx.rect(x, fy - 36, w, 36f, 0x88BDF0E0.toInt())
                gfx.poly(floatArrayOf(x - 3, x + w / 2, x + w + 3), floatArrayOf(fy - 36, fy - 60, fy - 36), 0x88D8F8F0.toInt())
                for (k in 0 until 4) gfx.oval(x + 5 + k * 16f, fy - 18, 12f, 10f, GREEN)
                for (k in 0 until 4) gfx.oval(x + 6 + k * 15f, fy - 14, 4f, 4f, RED)
                for (k in 0 until 5) gfx.rect(x + k * (w - 3) / 4, fy - 36, 3f, 36f, WHITE)
                gfx.rect(x, fy - 37, w, 3f, WHITE)
                line(gfx, x - 3, fy - 36, x + w / 2, fy - 60, 2.5f, WHITE); line(gfx, x + w / 2, fy - 60, x + w + 3, fy - 36, 2.5f, WHITE)
                gfx.rect(x + w / 2 - 6, fy - 26, 12f, 26f, 0x66FFFFFF)
                gfx.rect(x - 2, fy - 3, w + 4, 3f, 0xFF8A5A2A.toInt())
            }
            DecorItem.BEEHIVE -> {
                gfx.rect(x + 9, fy - 12, 3f, 12f, DWOOD); gfx.rect(x + 3, fy - 14, 16f, 3f, DWOOD)
                for (k in 0 until 4) gfx.oval(x + 1 + k * 2f, fy - 22 - k * 7f, w - 2 - k * 4f, 10f, if (k % 2 == 0) 0xFFF4C83A.toInt() else 0xFFE0A830.toInt())
                gfx.oval(x + 8, fy - 20, 6f, 5f, 0xFF3A2A1A.toInt())
                for (k in 0 until 3) {
                    val a = t * (2f + k * 0.5f) + k * 2.1f
                    val bx = x + w / 2 + cos(a) * (14 + k * 2); val by = fy - 32 + sin(a * 1.3f) * 10
                    gfx.oval(bx - 2.5f, by - 2, 5f, 4f, YELLOW); gfx.rect(bx - 0.5f, by - 2, 1.2f, 4f, INK)
                    gfx.oval(bx - 1.5f, by - 4.5f, 3f, 2.5f, 0xCCFFFFFF.toInt())
                }
            }
            DecorItem.PUMPKIN_PATCH -> {
                gfx.oval(x - 2, fy - 4, w + 4, 10f, 0xFF6A4A2A.toInt())
                gfx.rect(x + 2, fy - 3, w - 4, 1.5f, GREEN)
                for (k in 0 until 3) gfx.oval(x + 6 + k * 18f, fy - 10, 10f, 6f, LEAF)
                val sizes = floatArrayOf(16f, 22f, 13f); val px = floatArrayOf(x + 2, x + 18, x + 42)
                for (k in 0 until 3) {
                    val s = sizes[k]
                    gfx.oval(px[k], fy - s * 0.8f, s, s * 0.8f, 0xFFF0802A.toInt())
                    gfx.oval(px[k] + s * 0.3f, fy - s * 0.8f, s * 0.4f, s * 0.8f, 0xFFE0701A.toInt())
                    gfx.rect(px[k] + s / 2 - 1, fy - s * 0.8f - 4, 2.5f, 5f, GREEN)
                }
            }
            DecorItem.PICNIC_BLANKET -> {
                gfx.poly(floatArrayOf(x + 4, x + w, x + w - 4, x), floatArrayOf(fy + 1, fy + 1, fy + 17, fy + 17), WHITE)
                for (k in 0 until 8) { val col = k % 4; val row = k / 4; if ((col + row) % 2 == 0) gfx.poly(floatArrayOf(x + 4 - row * 2 + col * 15f, x + 4 - row * 2 + (col + 1) * 15f, x + 4 - (row + 1) * 2 + (col + 1) * 15f, x + 4 - (row + 1) * 2 + col * 15f), floatArrayOf(fy + 1 + row * 8f, fy + 1 + row * 8f, fy + 9 + row * 8f, fy + 9 + row * 8f), RED) else Unit }
                for (k in 0 until 8) { val col = k % 4; val row = k / 4; if ((col + row) % 2 == 1) gfx.poly(floatArrayOf(x + 4 - row * 2 + col * 15f, x + 4 - row * 2 + (col + 1) * 15f, x + 4 - (row + 1) * 2 + (col + 1) * 15f, x + 4 - (row + 1) * 2 + col * 15f), floatArrayOf(fy + 1 + row * 8f, fy + 1 + row * 8f, fy + 9 + row * 8f, fy + 9 + row * 8f), 0x33E84A4A) else Unit }
                gfx.rect(x + 8, fy - 8, 16f, 12f, 0xFFC8904A.toInt()); gfx.rect(x + 8, fy - 8, 16f, 2f, DWOOD)
                gfx.poly(floatArrayOf(x + 10, x + 16, x + 22, x + 20, x + 16, x + 12), floatArrayOf(fy - 8, fy - 16, fy - 8, fy - 8, fy - 13, fy - 8), DWOOD)
                gfx.oval(x + 36, fy + 2, 7f, 7f, RED); gfx.rect(x + 39, fy, 1f, 3f, DWOOD)
                gfx.poly(floatArrayOf(x + 46, x + 58, x + 46), floatArrayOf(fy + 4, fy + 8, fy + 12), 0xFFF4D8A0.toInt())
            }
            DecorItem.KITE -> {
                gfx.rect(x + 2, fy - 6, 2f, 6f, DWOOD)
                val kx = x + 18 + sin(t * 1.3f) * 5; val ky = fy - 92 + cos(t * 1.7f) * 4
                line(gfx, x + 3, fy - 5, kx, ky + 12, 0.7f, INK)
                gfx.poly(floatArrayOf(kx, kx - 9, kx), floatArrayOf(ky - 12, ky, ky + 12), RED)
                gfx.poly(floatArrayOf(kx, kx + 9, kx), floatArrayOf(ky - 12, ky, ky + 12), YELLOW)
                gfx.poly(floatArrayOf(kx - 9, kx + 9, kx), floatArrayOf(ky, ky, ky + 12), BLUE)
                for (k in 0 until 3) { val bx = kx + sin(t * 3 + k) * 3 - k; val by = ky + 16 + k * 7f; gfx.poly(floatArrayOf(bx - 3, bx + 3, bx - 3), floatArrayOf(by - 2, by, by + 2), BALLS[k]); gfx.poly(floatArrayOf(bx + 3, bx - 3, bx + 3), floatArrayOf(by - 2, by, by + 2), BALLS[k]) }
            }
            DecorItem.BLOCKY_STATUE -> {
                gfx.rect(x, fy - 8, w, 8f, GREY)
                gfx.sprite(Metal.of(TeamArt.blocky(Power.NONE, false)), x + 3, fy - 44, w = 24f, h = 36f)
            }
            else -> {}
        }
    }
}
