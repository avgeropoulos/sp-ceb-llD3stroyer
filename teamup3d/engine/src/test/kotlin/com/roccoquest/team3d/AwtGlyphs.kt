package com.roccoquest.team3d

import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil

/** Draws characters with Java2D for the tests (the phone uses Android's own fonts). */
object AwtGlyphs : Glyphs.Rasterizer {
    private val font = Font(Font.MONOSPACED, Font.BOLD, Glyphs.RASTER.toInt())

    override fun draw(c: Char): Glyphs.Bitmap {
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        probe.font = font
        val fm = probe.fontMetrics
        val adv = fm.charWidth(c).toFloat()
        val w = ceil(adv).toInt() + 4
        val h = fm.ascent + fm.descent + 4
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = font
        g.color = java.awt.Color.WHITE
        g.drawString(c.toString(), 2, 2 + fm.ascent)
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        return Glyphs.Bitmap(w, h, px, fm.ascent + 2f, 2f, adv)
    }
}
