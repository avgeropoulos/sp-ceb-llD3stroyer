package com.javelinstrike.game

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/** Java2D implementation of [Gfx], used to render screenshots in tests. */
class AwtGfx(val image: BufferedImage, private val scale: Float) : Gfx {
    private val g: Graphics2D = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    private fun color(c: Int) { g.color = Color(c, true) }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        color(color); g.fill(Rectangle2D.Float(x * scale, y * scale, w * scale, h * scale))
    }

    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        color(color); g.fill(Ellipse2D.Float(x * scale, y * scale, w * scale, h * scale))
    }

    override fun poly(xs: FloatArray, ys: FloatArray, color: Int, n: Int) {
        val p = Path2D.Float()
        p.moveTo(xs[0] * scale, ys[0] * scale)
        for (i in 1 until n) p.lineTo(xs[i] * scale, ys[i] * scale)
        p.closePath()
        color(color); g.fill(p)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        color(color)
        g.stroke = BasicStroke(width * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Float(x1 * scale, y1 * scale, x2 * scale, y2 * scale))
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int, serif: Boolean) {
        g.font = Font(if (serif) Font.SERIF else Font.MONOSPACED, Font.BOLD, (size * scale).toInt().coerceAtLeast(1))
        val w = g.fontMetrics.stringWidth(s)
        val px = x * scale - when (align) { 1 -> w / 2f; 2 -> w.toFloat(); else -> 0f }
        color(color); g.drawString(s, px, y * scale)
    }

    override fun clip(x: Float, y: Float, w: Float, h: Float) {
        g.clip = Rectangle2D.Float(x * scale, y * scale, w * scale, h * scale)
    }

    override fun unclip() { g.clip = null }
}
