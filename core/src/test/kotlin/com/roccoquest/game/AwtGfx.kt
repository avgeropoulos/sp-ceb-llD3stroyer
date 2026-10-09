package com.roccoquest.game

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/** Java2D implementation of [Gfx], used to render screenshots in tests. */
class AwtGfx(val image: BufferedImage, private val scale: Float) : Gfx {
    private val g: Graphics2D = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        stroke = BasicStroke(0f)
    }

    private fun color(c: Int) { g.color = Color(c, true) }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        color(color); g.fill(Rectangle2D.Float(x * scale, y * scale, w * scale, h * scale))
    }

    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        color(color); g.fill(Ellipse2D.Float(x * scale, y * scale, w * scale, h * scale))
    }

    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {
        val p = Path2D.Float()
        p.moveTo(xs[0] * scale, ys[0] * scale)
        for (i in 1 until xs.size) p.lineTo(xs[i] * scale, ys[i] * scale)
        p.closePath()
        color(color); g.fill(p)
    }

    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) {
        val img = s.cache as? BufferedImage ?: BufferedImage(s.w, s.h, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, s.w, s.h, s.pixels, 0, s.w)
            s.cache = it
        }
        val l = Math.floor((x * scale).toDouble()).toInt()
        val t = Math.floor((y * scale).toDouble()).toInt()
        val r = Math.floor(((x + w) * scale).toDouble()).toInt()
        val b = Math.floor(((y + h) * scale).toDouble()).toInt()
        val (dx1, dx2) = if (flipX) r to l else l to r
        val (dy1, dy2) = if (flipY) b to t else t to b
        g.drawImage(img, dx1, dy1, dx2, dy2, 0, 0, s.w, s.h, null)
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {
        g.font = Font(Font.MONOSPACED, Font.BOLD, (size * scale).toInt().coerceAtLeast(1))
        val w = g.fontMetrics.stringWidth(s)
        val px = x * scale - when (align) { 1 -> w / 2f; 2 -> w.toFloat(); else -> 0f }
        color(color); g.drawString(s, px, y * scale)
    }
}
