package com.mixdeck.ui

/*
 * A tiny drawing API, shaped like Android's Canvas/Paint/RectF/Path, so the DJ console can be
 * drawn by the same code on the phone (android.graphics.Canvas) and on the computer (Java2D).
 * Each platform implements [Canvas] and [TextMetrics].
 */

class RectF(@JvmField var left: Float = 0f, @JvmField var top: Float = 0f, @JvmField var right: Float = 0f, @JvmField var bottom: Float = 0f) {
    constructor(r: RectF) : this(r.left, r.top, r.right, r.bottom)

    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2
    fun centerY() = (top + bottom) / 2
    fun isEmpty() = left >= right || top >= bottom
    fun contains(x: Float, y: Float) = !isEmpty() && x >= left && x < right && y >= top && y < bottom
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun set(r: RectF) = set(r.left, r.top, r.right, r.bottom)
    fun setEmpty() = set(0f, 0f, 0f, 0f)
    fun inset(dx: Float, dy: Float) { left += dx; top += dy; right -= dx; bottom -= dy }
    fun union(r: RectF) {
        if (r.isEmpty()) return
        if (isEmpty()) set(r) else set(minOf(left, r.left), minOf(top, r.top), maxOf(right, r.right), maxOf(bottom, r.bottom))
    }

    companion object {
        fun intersects(a: RectF, b: RectF) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    }
}

object Color {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
}

enum class Typeface { DEFAULT_BOLD, MONO_BOLD }

/** Measures text for the platform's fonts. */
interface TextMetrics {
    fun width(s: CharSequence, start: Int, end: Int, size: Float, face: Typeface): Float
    /** Distance from the baseline to the top of the text (negative, like Android). */
    fun ascent(size: Float, face: Typeface): Float
    /** Distance from the baseline to the bottom of the text (positive). */
    fun descent(size: Float, face: Typeface): Float

    companion object {
        /** Set by the platform before anything is drawn. */
        lateinit var current: TextMetrics
    }
}

class Paint(flags: Int = 0) {
    enum class Style { FILL, STROKE }
    enum class Align { LEFT, CENTER, RIGHT }
    enum class Cap { BUTT, ROUND }

    var color = Color.BLACK
    var style = Style.FILL
    var strokeWidth = 1f
    var strokeCap = Cap.BUTT
    var textSize = 12f
    var textAlign = Align.LEFT
    var typeface = Typeface.DEFAULT_BOLD

    fun measureText(s: CharSequence) = TextMetrics.current.width(s, 0, s.length, textSize, typeface)
    fun measureText(s: CharSequence, start: Int, end: Int) = TextMetrics.current.width(s, start, end, textSize, typeface)
    fun ascent() = TextMetrics.current.ascent(textSize, typeface)
    fun descent() = TextMetrics.current.descent(textSize, typeface)

    companion object {
        const val ANTI_ALIAS_FLAG = 1
    }
}

/** A filled outline made of rectangles and rounded rectangles; overlapping parts cancel out (even-odd). */
class Path {
    sealed class Part {
        class Rect(val r: RectF) : Part()
        class Round(val r: RectF, val rx: Float, val ry: Float) : Part()
    }
    val parts = ArrayList<Part>()
    fun addRect(l: Float, t: Float, r: Float, b: Float) { parts += Part.Rect(RectF(l, t, r, b)) }
    fun addRoundRect(r: RectF, rx: Float, ry: Float) { parts += Part.Round(RectF(r), rx, ry) }
}

interface Canvas {
    fun drawColor(color: Int)
    fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint)
    fun drawRect(r: RectF, p: Paint) = drawRect(r.left, r.top, r.right, r.bottom, p)
    fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint)
    fun drawCircle(cx: Float, cy: Float, radius: Float, p: Paint)
    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint)
    /** Draws [count]/4 separate lines from x0,y0,x1,y1 groups in [pts] starting at [offset]. */
    fun drawLines(pts: FloatArray, offset: Int, count: Int, p: Paint)
    /** Angles in degrees, clockwise from 3 o'clock, like Android. */
    fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, p: Paint)
    fun drawText(s: String, x: Float, y: Float, p: Paint)
    fun drawPath(path: Path, p: Paint)
    fun save()
    fun restore()
    fun clipRect(r: RectF)
}
