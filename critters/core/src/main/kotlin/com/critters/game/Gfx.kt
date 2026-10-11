package com.critters.game

/** The handheld's screen: 160 x 144 pixels, just like the original. */
const val SW = 160
const val SH = 144

/**
 * Platform drawing surface, in virtual units. The game screen itself is rendered
 * in software into a [Screen] and handed over with [screen]; everything else
 * (the console shell and buttons) uses the vector calls.
 */
interface Draw {
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun oval(x: Float, y: Float, w: Float, h: Float, color: Int)

    /** [y] is the text baseline. [align]: 0 = left, 1 = center, 2 = right. */
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int = 0)

    /** Draws the [SW] x [SH] frame buffer with its top-left corner at (x, y), one unit per pixel. */
    fun screen(pixels: IntArray, x: Float, y: Float)
}

/** A small ARGB bitmap; fully transparent pixels are 0. */
class Sprite(val w: Int, val h: Int, val px: IntArray) {
    fun recolor(map: Map<Int, Int>) = Sprite(w, h, IntArray(px.size) { map[px[it]] ?: px[it] })

    fun flipped() = Sprite(w, h, IntArray(px.size) { px[(it / w) * w + (w - 1 - it % w)] })
}

/** Builds a sprite from rows of characters; '.' is transparent. */
fun art(palette: Map<Char, Int>, vararg rows: String): Sprite {
    val w = rows[0].length
    require(rows.all { it.length == w }) { "Ragged sprite rows: ${rows.map { it.length }}" }
    val px = IntArray(w * rows.size)
    rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, c ->
            px[y * w + x] = if (c == '.') 0 else palette[c] ?: error("No palette entry for '$c'")
        }
    }
    return Sprite(w, rows.size, px)
}

/** Tiles a small pattern to fill a [w] x [h] sprite. */
fun tiled(pattern: Sprite, w: Int = 16, h: Int = 16) =
    Sprite(w, h, IntArray(w * h) { pattern.px[(it / w % pattern.h) * pattern.w + it % w % pattern.w] })

/** Stacks [top] over [base] (both the same size). */
fun layer(base: Sprite, top: Sprite) =
    Sprite(base.w, base.h, IntArray(base.px.size) { if (top.px[it] != 0) top.px[it] else base.px[it] })

const val INK = 0xFF181820.toInt()
const val PAPER = 0xFFF8F8F0.toInt()
const val FRAME = 0xFF5878D8.toInt()

/** The software frame buffer the whole game is drawn into. */
class Screen {
    val px = IntArray(SW * SH)

    fun fill(c: Int) = px.fill(c)

    fun rect(x: Int, y: Int, w: Int, h: Int, c: Int) {
        val x0 = maxOf(0, x); val y0 = maxOf(0, y)
        val x1 = minOf(SW, x + w); val y1 = minOf(SH, y + h)
        for (yy in y0 until y1) {
            val row = yy * SW
            for (xx in x0 until x1) px[row + xx] = c
        }
    }

    fun blit(s: Sprite, x: Int, y: Int, flip: Boolean = false, tint: Int = 0) {
        for (sy in 0 until s.h) {
            val yy = y + sy
            if (yy < 0 || yy >= SH) continue
            for (sx in 0 until s.w) {
                val xx = x + sx
                if (xx < 0 || xx >= SW) continue
                val c = s.px[sy * s.w + if (flip) s.w - 1 - sx else sx]
                if (c != 0) px[yy * SW + xx] = if (tint != 0) tint else c
            }
        }
    }

    /** Nearest-neighbour scaled blit into the box (x, y, w, h). */
    fun blitScaled(s: Sprite, x: Int, y: Int, w: Int, h: Int, tint: Int = 0) {
        if (w <= 0 || h <= 0) return
        for (dy in 0 until h) {
            val yy = y + dy
            if (yy < 0 || yy >= SH) continue
            val sy = dy * s.h / h
            for (dx in 0 until w) {
                val xx = x + dx
                if (xx < 0 || xx >= SW) continue
                val c = s.px[sy * s.w + dx * s.w / w]
                if (c != 0) px[yy * SW + xx] = if (tint != 0) tint else c
            }
        }
    }

    /** Draws only the top [rows] rows of [s] with its bottom edge at [bottom] (for slide-down faints). */
    fun blitSink(s: Sprite, x: Int, bottom: Int, sink: Int) {
        val visible = s.h - sink
        if (visible <= 0) return
        for (sy in 0 until visible) {
            val yy = bottom - s.h + sink + sy
            if (yy < 0 || yy >= SH || yy >= bottom) continue
            for (sx in 0 until s.w) {
                val xx = x + sx
                if (xx < 0 || xx >= SW) continue
                val c = s.px[sy * s.w + sx]
                if (c != 0) px[yy * SW + xx] = c
            }
        }
    }

    fun text(s: String, x: Int, y: Int, c: Int = INK) {
        var cx = x
        for (ch in s) {
            Font.draw(this, ch, cx, y, c)
            cx += Font.ADV
        }
    }

    fun textRight(s: String, right: Int, y: Int, c: Int = INK) = text(s, right - s.length * Font.ADV, y, c)

    fun textCenter(s: String, cx: Int, y: Int, c: Int = INK) = text(s, cx - s.length * Font.ADV / 2, y, c)

    /** A white text box with the colored double frame. */
    fun box(x: Int, y: Int, w: Int, h: Int, frame: Int = FRAME) {
        rect(x, y, w, h, INK)
        rect(x + 1, y + 1, w - 2, h - 2, frame)
        rect(x + 3, y + 3, w - 6, h - 6, INK)
        rect(x + 4, y + 4, w - 8, h - 8, PAPER)
        // Rounded corners
        for ((cx, cy) in listOf(x to y, x + w - 1 to y, x to y + h - 1, x + w - 1 to y + h - 1)) {
            if (cx in 0 until SW && cy in 0 until SH) px[cy * SW + cx] = 0xFF000000.toInt()
        }
    }

    /** Fades the whole frame toward black (or white) by [t] in 0..1. */
    fun fade(t: Float, white: Boolean = false) {
        if (t <= 0f) return
        val k = t.coerceIn(0f, 1f)
        val to = if (white) 255 else 0
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16 and 255) + ((to - (c shr 16 and 255)) * k)).toInt()
            val g = ((c shr 8 and 255) + ((to - (c shr 8 and 255)) * k)).toInt()
            val b = ((c and 255) + ((to - (c and 255)) * k)).toInt()
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** HP bar in the classic green / yellow / red. */
    fun hpBar(x: Int, y: Int, w: Int, frac: Float) {
        rect(x, y, w + 2, 4, INK)
        rect(x + 1, y + 1, w, 2, 0xFF505058.toInt())
        val f = frac.coerceIn(0f, 1f)
        val fill = if (f > 0f) maxOf(1, (w * f).toInt()) else 0
        val col = when {
            f > 0.5f -> 0xFF48D048.toInt()
            f > 0.2f -> 0xFFF8C020.toInt()
            else -> 0xFFF04030.toInt()
        }
        rect(x + 1, y + 1, fill, 2, col)
    }
}

/** A 5x7 pixel font on a 6 pixel advance; lower case is drawn as upper case. */
object Font {
    const val ADV = 6

    private val glyphs = HashMap<Char, IntArray>()

    private fun g(c: Char, rows: String) {
        val r = rows.split('|')
        require(r.size == 7 && r.all { it.length == 5 }) { "Bad glyph '$c'" }
        glyphs[c] = IntArray(7) { y -> r[y].foldIndexed(0) { x, acc, ch -> if (ch == '#') acc or (1 shl x) else acc } }
    }

    init {
        g('A', ".###.|#...#|#...#|#####|#...#|#...#|#...#")
        g('B', "####.|#...#|#...#|####.|#...#|#...#|####.")
        g('C', ".###.|#...#|#....|#....|#....|#...#|.###.")
        g('D', "####.|#...#|#...#|#...#|#...#|#...#|####.")
        g('E', "#####|#....|#....|####.|#....|#....|#####")
        g('F', "#####|#....|#....|####.|#....|#....|#....")
        g('G', ".###.|#...#|#....|#.###|#...#|#...#|.####")
        g('H', "#...#|#...#|#...#|#####|#...#|#...#|#...#")
        g('I', ".###.|..#..|..#..|..#..|..#..|..#..|.###.")
        g('J', "..###|...#.|...#.|...#.|...#.|#..#.|.##..")
        g('K', "#...#|#..#.|#.#..|##...|#.#..|#..#.|#...#")
        g('L', "#....|#....|#....|#....|#....|#....|#####")
        g('M', "#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#")
        g('N', "#...#|#...#|##..#|#.#.#|#..##|#...#|#...#")
        g('O', ".###.|#...#|#...#|#...#|#...#|#...#|.###.")
        g('P', "####.|#...#|#...#|####.|#....|#....|#....")
        g('Q', ".###.|#...#|#...#|#...#|#.#.#|#..#.|.##.#")
        g('R', "####.|#...#|#...#|####.|#.#..|#..#.|#...#")
        g('S', ".####|#....|#....|.###.|....#|....#|####.")
        g('T', "#####|..#..|..#..|..#..|..#..|..#..|..#..")
        g('U', "#...#|#...#|#...#|#...#|#...#|#...#|.###.")
        g('V', "#...#|#...#|#...#|#...#|#...#|.#.#.|..#..")
        g('W', "#...#|#...#|#...#|#.#.#|#.#.#|#.#.#|.#.#.")
        g('X', "#...#|#...#|.#.#.|..#..|.#.#.|#...#|#...#")
        g('Y', "#...#|#...#|.#.#.|..#..|..#..|..#..|..#..")
        g('Z', "#####|....#|...#.|..#..|.#...|#....|#####")
        g('0', ".###.|#...#|#..##|#.#.#|##..#|#...#|.###.")
        g('1', "..#..|.##..|..#..|..#..|..#..|..#..|.###.")
        g('2', ".###.|#...#|....#|...#.|..#..|.#...|#####")
        g('3', "#####|...#.|..#..|...#.|....#|#...#|.###.")
        g('4', "...#.|..##.|.#.#.|#..#.|#####|...#.|...#.")
        g('5', "#####|#....|####.|....#|....#|#...#|.###.")
        g('6', "..##.|.#...|#....|####.|#...#|#...#|.###.")
        g('7', "#####|....#|...#.|..#..|.#...|.#...|.#...")
        g('8', ".###.|#...#|#...#|.###.|#...#|#...#|.###.")
        g('9', ".###.|#...#|#...#|.####|....#|...#.|.##..")
        g('!', "..#..|..#..|..#..|..#..|..#..|.....|..#..")
        g('?', ".###.|#...#|....#|...#.|..#..|.....|..#..")
        g('.', ".....|.....|.....|.....|.....|.##..|.##..")
        g(',', ".....|.....|.....|.....|.##..|..#..|.#...")
        g('\'', "..#..|..#..|.#...|.....|.....|.....|.....")
        g('"', ".#.#.|.#.#.|.....|.....|.....|.....|.....")
        g('-', ".....|.....|.....|.###.|.....|.....|.....")
        g(':', ".....|.##..|.##..|.....|.##..|.##..|.....")
        g('/', "....#|....#|...#.|..#..|.#...|#....|#....")
        g('(', "...#.|..#..|.#...|.#...|.#...|..#..|...#.")
        g(')', ".#...|..#..|...#.|...#.|...#.|..#..|.#...")
        g('$', "..#..|.####|#.#..|.###.|..#.#|####.|..#..")
        g('&', ".##..|#..#.|#.#..|.#...|#.#.#|#..#.|.##.#")
        g('+', ".....|..#..|..#..|#####|..#..|..#..|.....")
        g('=', ".....|.....|#####|.....|#####|.....|.....")
        g('*', ".....|..#..|#.#.#|.###.|#.#.#|..#..|.....")
        g('#', ".#.#.|#####|.#.#.|.#.#.|.#.#.|#####|.#.#.")
        // '>' is the menu cursor (a filled arrow), '^' the "more text" arrow, '~' a heart, '%' a badge star.
        g('>', "#....|##...|###..|####.|###..|##...|#....")
        g('^', ".....|#####|.###.|..#..|.....|.....|.....")
        g('~', ".....|.#.#.|#####|#####|.###.|..#..|.....")
        g('%', "..#..|..#..|#####|.###.|.###.|##.##|#...#")
    }

    fun draw(s: Screen, ch: Char, x: Int, y: Int, c: Int) {
        val bits = glyphs[ch.uppercaseChar()] ?: return
        for (row in 0 until 7) {
            val b = bits[row]
            if (b == 0) continue
            val yy = y + row
            if (yy < 0 || yy >= SH) continue
            for (col in 0 until 5) {
                if (b and (1 shl col) == 0) continue
                val xx = x + col
                if (xx in 0 until SW) s.px[yy * SW + xx] = c
            }
        }
    }

    fun has(ch: Char) = ch == ' ' || glyphs.containsKey(ch.uppercaseChar())
}
