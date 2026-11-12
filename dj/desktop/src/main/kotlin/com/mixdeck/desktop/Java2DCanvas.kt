package com.mixdeck.desktop

import com.mixdeck.ui.Canvas
import com.mixdeck.ui.Paint
import com.mixdeck.ui.Path
import com.mixdeck.ui.RectF
import com.mixdeck.ui.TextMetrics
import com.mixdeck.ui.Typeface
import java.awt.BasicStroke
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.geom.Arc2D
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/** Draws the shared console with Java2D. */
class Java2DCanvas : Canvas {
    lateinit var g: Graphics2D
    private val saved = ArrayDeque<Shape?>()
    private val line = Line2D.Float()
    private val rect = Rectangle2D.Float()
    private val round = RoundRectangle2D.Float()
    private val oval = Ellipse2D.Float()
    private val arc = Arc2D.Float()

    fun begin(g: Graphics2D) {
        this.g = g
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    private fun apply(p: Paint) {
        g.color = java.awt.Color(p.color, true)
        if (p.style == Paint.Style.STROKE) {
            g.stroke = BasicStroke(p.strokeWidth, if (p.strokeCap == Paint.Cap.ROUND) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        }
    }

    private fun paintShape(s: Shape, p: Paint) {
        apply(p)
        if (p.style == Paint.Style.STROKE) g.draw(s) else g.fill(s)
    }

    override fun drawColor(color: Int) {
        g.color = java.awt.Color(color, true)
        val b = g.clipBounds ?: java.awt.Rectangle(0, 0, 100000, 100000)
        g.fill(b)
    }

    override fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) {
        rect.setRect(l, t, r - l, b - t); paintShape(rect, p)
    }

    override fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) {
        round.setRoundRect(r.left, r.top, r.width(), r.height(), rx * 2, ry * 2); paintShape(round, p)
    }

    override fun drawCircle(cx: Float, cy: Float, radius: Float, p: Paint) {
        oval.setFrame(cx - radius, cy - radius, radius * 2, radius * 2); paintShape(oval, p)
    }

    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) {
        apply(p)
        if (p.style != Paint.Style.STROKE) g.stroke = BasicStroke(p.strokeWidth, if (p.strokeCap == Paint.Cap.ROUND) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        line.setLine(x0, y0, x1, y1); g.draw(line)
    }

    override fun drawLines(pts: FloatArray, offset: Int, count: Int, p: Paint) {
        g.color = java.awt.Color(p.color, true)
        g.stroke = BasicStroke(p.strokeWidth)
        val path = Path2D.Float()
        var k = offset
        while (k + 3 < offset + count) {
            path.moveTo(pts[k], pts[k + 1]); path.lineTo(pts[k + 2], pts[k + 3]); k += 4
        }
        g.draw(path)
    }

    override fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, p: Paint) {
        // Android: degrees clockwise from 3 o'clock. Java2D: counter-clockwise.
        arc.setArc(oval.left.toDouble(), oval.top.toDouble(), oval.width().toDouble(), oval.height().toDouble(), -startAngle.toDouble(), -sweepAngle.toDouble(), if (useCenter) Arc2D.PIE else Arc2D.OPEN)
        paintShape(arc, p)
    }

    override fun drawText(s: String, x: Float, y: Float, p: Paint) {
        val f = Fonts.font(p.textSize, p.typeface)
        g.font = f
        g.color = java.awt.Color(p.color, true)
        val w = when (p.textAlign) {
            Paint.Align.LEFT -> 0f
            Paint.Align.CENTER -> Fonts.width(s, 0, s.length, p.textSize, p.typeface) / 2
            Paint.Align.RIGHT -> Fonts.width(s, 0, s.length, p.textSize, p.typeface)
        }
        g.drawString(s, x - w, y)
    }

    override fun drawPath(path: Path, p: Paint) {
        val shape = Path2D.Float(Path2D.WIND_EVEN_ODD)
        for (part in path.parts) when (part) {
            is Path.Part.Rect -> shape.append(Rectangle2D.Float(part.r.left, part.r.top, part.r.width(), part.r.height()), false)
            is Path.Part.Round -> shape.append(RoundRectangle2D.Float(part.r.left, part.r.top, part.r.width(), part.r.height(), part.rx * 2, part.ry * 2), false)
            is Path.Part.Poly -> {
                shape.moveTo(part.pts[0], part.pts[1])
                for (k in 2 until part.pts.size step 2) shape.lineTo(part.pts[k], part.pts[k + 1])
                shape.closePath()
            }
        }
        paintShape(Area(shape), p)
    }

    override fun save() { saved.addLast(g.clip) }
    override fun restore() { g.clip = saved.removeLastOrNull() }
    override fun clipRect(r: RectF) { g.clip(Rectangle2D.Float(r.left, r.top, r.width(), r.height())) }
}

/** Fonts and text measuring for the console on the desktop. */
object Fonts : TextMetrics {
    private val frc = FontRenderContext(null, true, true)
    private val cache = HashMap<Long, Font>()
    // Logical fonts map to each OS's own UI fonts with good symbol coverage.
    private val bold = Font(Font.SANS_SERIF, Font.BOLD, 1)
    private val mono = Font(Font.MONOSPACED, Font.BOLD, 1)

    fun font(size: Float, face: Typeface): Font {
        val key = (Math.round(size * 4).toLong() shl 1) or (if (face == Typeface.MONO_BOLD) 1L else 0L)
        return cache.getOrPut(key) { (if (face == Typeface.MONO_BOLD) mono else bold).deriveFont(Math.round(size * 4) / 4f) }
    }

    override fun width(s: CharSequence, start: Int, end: Int, size: Float, face: Typeface): Float {
        if (end <= start) return 0f
        return font(size, face).getStringBounds(s.subSequence(start, end).toString(), frc).width.toFloat()
    }

    override fun ascent(size: Float, face: Typeface) = -font(size, face).getLineMetrics("Hg", frc).ascent
    override fun descent(size: Float, face: Typeface) = font(size, face).getLineMetrics("Hg", frc).descent
}
