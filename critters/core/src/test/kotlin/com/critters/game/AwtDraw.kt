package com.critters.game

import java.awt.Color
import java.awt.Font as AwtFont
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/** Java2D version of [Draw], for rendering the console in tests. */
class AwtDraw(private val img: BufferedImage, private val scale: Float) : Draw {
    private val g = img.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        g.color = Color(color, true); g.fill(Rectangle2D.Float(x * scale, y * scale, w * scale, h * scale))
    }

    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        g.color = Color(color, true); g.fill(Ellipse2D.Float(x * scale, y * scale, w * scale, h * scale))
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {
        g.font = AwtFont(AwtFont.MONOSPACED, AwtFont.BOLD, (size * scale).toInt().coerceAtLeast(1))
        val w = g.fontMetrics.stringWidth(s)
        g.color = Color(color, true)
        g.drawString(s, x * scale - when (align) { 1 -> w / 2f; 2 -> w.toFloat(); else -> 0f }, y * scale)
    }

    override fun screen(pixels: IntArray, x: Float, y: Float) {
        val b = BufferedImage(SW, SH, BufferedImage.TYPE_INT_ARGB)
        b.setRGB(0, 0, SW, SH, pixels, 0, SW)
        g.drawImage(b, (x * scale).toInt(), (y * scale).toInt(), (SW * scale).toInt(), (SH * scale).toInt(), null)
    }
}
