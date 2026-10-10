package com.spotless.game

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/** Java2D implementation of [Painter], used to render screenshots in tests. */
class AwtPainter(val image: BufferedImage) : Painter {
    private val g: Graphics2D = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        scale(image.width / VW.toDouble(), image.height / VH.toDouble())
    }
    private val stack = ArrayList<AffineTransform>()

    private fun color(c: Int) { g.color = Color(c, true) }
    private fun font(size: Float) = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(size)

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        color(color); g.fill(Rectangle2D.Float(x, y, w, h))
    }

    override fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        color(color); g.fill(RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2))
    }

    override fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        color(color); g.fill(Ellipse2D.Float(cx - rx, cy - ry, rx * 2, ry * 2))
    }

    private fun path(s: Shape): Path2D.Float {
        val p = Path2D.Float()
        s.forEach(
            { x, y -> p.moveTo(x, y) },
            { x, y -> p.lineTo(x, y) },
            { a, b, x, y -> p.quadTo(a, b, x, y) },
            { a, b, c, d, x, y -> p.curveTo(a, b, c, d, x, y) },
            { p.closePath() },
        )
        return p
    }

    override fun fill(p: Shape, color: Int) { color(color); g.fill(path(p)) }

    override fun stroke(p: Shape, color: Int, width: Float) {
        color(color)
        g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(path(p))
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        color(color)
        g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Float(x1, y1, x2, y2))
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {
        g.font = font(size)
        val w = g.fontMetrics.stringWidth(s)
        val px = x - when (align) { ALIGN_CENTER -> w / 2f; ALIGN_RIGHT -> w.toFloat(); else -> 0f }
        color(color); g.drawString(s, px, y)
    }

    override fun textWidth(s: String, size: Float): Float {
        g.font = font(size)
        return g.fontMetrics.stringWidth(s).toFloat()
    }

    override fun save() { stack.add(g.transform) }
    override fun restore() { g.transform = stack.removeAt(stack.size - 1) }
    override fun translate(x: Float, y: Float) = g.translate(x.toDouble(), y.toDouble())
    override fun scale(sx: Float, sy: Float) = g.scale(sx.toDouble(), sy.toDouble())
    override fun rotate(degrees: Float) = g.rotate(Math.toRadians(degrees.toDouble()))
}
