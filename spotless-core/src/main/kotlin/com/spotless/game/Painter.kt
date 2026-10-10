package com.spotless.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Virtual screen size. The whole game is laid out in these units (portrait). */
const val VW = 720f
const val VH = 1280f

const val ALIGN_LEFT = 0
const val ALIGN_CENTER = 1
const val ALIGN_RIGHT = 2

/**
 * Everything the game draws goes through this interface, so the same code renders to an
 * Android Canvas on the phone and to a Java2D image in the tests.
 */
interface Painter {
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int)
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Int)
    fun fill(p: Shape, color: Int)
    fun stroke(p: Shape, color: Int, width: Float)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float)
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int = ALIGN_LEFT)
    fun textWidth(s: String, size: Float): Float
    fun save()
    fun restore()
    fun translate(x: Float, y: Float)
    fun scale(sx: Float, sy: Float)
    fun rotate(degrees: Float)
}

/** A vector path: move/line/quad/cubic/close commands stored in a flat float list. */
class Shape {
    var data = FloatArray(64)
        private set
    var size = 0
        private set

    private fun put(vararg v: Float) {
        if (size + v.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + v.size))
        for (f in v) data[size++] = f
    }

    fun moveTo(x: Float, y: Float) = apply { put(OP_MOVE, x, y) }
    fun lineTo(x: Float, y: Float) = apply { put(OP_LINE, x, y) }
    fun quadTo(cx: Float, cy: Float, x: Float, y: Float) = apply { put(OP_QUAD, cx, cy, x, y) }
    fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) =
        apply { put(OP_CUBIC, c1x, c1y, c2x, c2y, x, y) }
    fun close() = apply { put(OP_CLOSE) }

    /** Walks the commands; used by the Painter implementations. */
    inline fun forEach(
        move: (Float, Float) -> Unit,
        line: (Float, Float) -> Unit,
        quad: (Float, Float, Float, Float) -> Unit,
        cubic: (Float, Float, Float, Float, Float, Float) -> Unit,
        close: () -> Unit,
    ) {
        var i = 0
        val d = data
        while (i < size) {
            when (d[i]) {
                OP_MOVE -> { move(d[i + 1], d[i + 2]); i += 3 }
                OP_LINE -> { line(d[i + 1], d[i + 2]); i += 3 }
                OP_QUAD -> { quad(d[i + 1], d[i + 2], d[i + 3], d[i + 4]); i += 5 }
                OP_CUBIC -> { cubic(d[i + 1], d[i + 2], d[i + 3], d[i + 4], d[i + 5], d[i + 6]); i += 7 }
                else -> { close(); i += 1 }
            }
        }
    }

    companion object {
        const val OP_MOVE = 0f
        const val OP_LINE = 1f
        const val OP_QUAD = 2f
        const val OP_CUBIC = 3f
        const val OP_CLOSE = 4f
    }
}

inline fun shape(block: Shape.() -> Unit): Shape = Shape().apply(block)

/** A polygon through the given x,y pairs. */
fun poly(vararg xy: Float): Shape = shape {
    moveTo(xy[0], xy[1])
    var i = 2
    while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }
    close()
}

fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

fun alpha(c: Int, a: Float): Int {
    val ca = (c ushr 24) and 0xFF
    val na = (ca * a.coerceIn(0f, 1f)).toInt()
    return (na shl 24) or (c and 0xFFFFFF)
}

fun mix(c1: Int, c2: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(s: Int) = (((c1 ushr s) and 0xFF) * (1 - k) + ((c2 ushr s) and 0xFF) * k).toInt()
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

fun darker(c: Int, t: Float = 0.2f) = mix(c, c and 0xFF000000.toInt(), t)
fun lighter(c: Int, t: Float = 0.2f) = mix(c, c or 0xFFFFFF, t)

/** A heart centred on (cx, cy) with total width 2r. */
fun heart(cx: Float, cy: Float, r: Float): Shape = shape {
    moveTo(cx, cy + r * 0.95f)
    cubicTo(cx - r * 0.2f, cy + r * 0.7f, cx - r * 1.05f, cy + r * 0.2f, cx - r, cy - r * 0.3f)
    cubicTo(cx - r * 0.95f, cy - r * 0.95f, cx - r * 0.15f, cy - r * 1.05f, cx, cy - r * 0.45f)
    cubicTo(cx + r * 0.15f, cy - r * 1.05f, cx + r * 0.95f, cy - r * 0.95f, cx + r, cy - r * 0.3f)
    cubicTo(cx + r * 1.05f, cy + r * 0.2f, cx + r * 0.2f, cy + r * 0.7f, cx, cy + r * 0.95f)
    close()
}

/** A five-pointed star. */
fun star(cx: Float, cy: Float, r: Float, inner: Float = 0.45f): Shape = shape {
    for (i in 0 until 10) {
        val a = -PI / 2 + i * PI / 5
        val rr = if (i % 2 == 0) r else r * inner
        val x = cx + (cos(a) * rr).toFloat()
        val y = cy + (sin(a) * rr).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** A four-pointed sparkle. */
fun sparkle(cx: Float, cy: Float, r: Float): Shape = shape {
    val k = r * 0.18f
    moveTo(cx, cy - r)
    quadTo(cx + k, cy - k, cx + r, cy)
    quadTo(cx + k, cy + k, cx, cy + r)
    quadTo(cx - k, cy + k, cx - r, cy)
    quadTo(cx - k, cy - k, cx, cy - r)
    close()
}

fun Painter.heartIcon(cx: Float, cy: Float, r: Float, color: Int = PINK) {
    fill(heart(cx, cy, r), color)
    oval(cx - r * 0.45f, cy - r * 0.35f, r * 0.2f, r * 0.14f, alpha(WHITE, 0.6f))
}

fun Painter.coinIcon(cx: Float, cy: Float, r: Float) {
    oval(cx, cy, r, r, rgb(0xD9, 0x9A, 0x1E))
    oval(cx, cy, r * 0.8f, r * 0.8f, rgb(0xFF, 0xC8, 0x3A))
    text("$", cx, cy + r * 0.42f, r * 1.15f, rgb(0xC0, 0x80, 0x10), ALIGN_CENTER)
}

/** Text with a darker outline, used for titles. */
fun Painter.outlinedText(s: String, x: Float, y: Float, size: Float, color: Int, outline: Int, align: Int = ALIGN_CENTER) {
    val o = size * 0.06f
    for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) text(s, x + dx * o, y + dy * o, size, outline, align)
    text(s, x, y, size, color, align)
}

/** Word-wraps [s] into lines no wider than [width]. */
fun Painter.wrap(s: String, size: Float, width: Float): List<String> {
    val out = ArrayList<String>()
    for (para in s.split('\n')) {
        var line = ""
        for (word in para.split(' ')) {
            val t = if (line.isEmpty()) word else "$line $word"
            if (textWidth(t, size) > width && line.isNotEmpty()) { out.add(line); line = word } else line = t
        }
        out.add(line)
    }
    return out
}

val WHITE = rgb(255, 255, 255)
val BLACK = rgb(0, 0, 0)
val PINK = rgb(0xFF, 0x4F, 0x8B)
val SOFT_PINK = rgb(0xFF, 0xD6, 0xE5)
val INK = rgb(0x3A, 0x24, 0x40)
val GOLD = rgb(0xFF, 0xC8, 0x3A)
