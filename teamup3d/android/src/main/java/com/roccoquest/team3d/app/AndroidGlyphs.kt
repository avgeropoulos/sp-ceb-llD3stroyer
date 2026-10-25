package com.roccoquest.team3d.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.roccoquest.team3d.Glyphs
import kotlin.math.ceil

/** Draws text characters for the 3D screens in the same bold monospace font as Team Up!. */
class AndroidGlyphs : Glyphs.Rasterizer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = Glyphs.RASTER
        color = 0xFFFFFFFF.toInt()
    }

    override fun draw(c: Char): Glyphs.Bitmap {
        val fm = paint.fontMetrics
        val s = c.toString()
        val advance = paint.measureText(s)
        val ascent = ceil(-fm.ascent).toInt()
        val descent = ceil(fm.descent).toInt()
        val pad = 2
        val w = ceil(advance).toInt() + pad * 2
        val h = ascent + descent + pad * 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(s, pad.toFloat(), (pad + ascent).toFloat(), paint)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        // Keep only the coverage as alpha on pure white; the text colour is applied when drawn.
        for (i in px.indices) px[i] = (px[i] and 0xFF000000.toInt()) or 0xFFFFFF
        return Glyphs.Bitmap(w, h, px, (pad + ascent).toFloat(), pad.toFloat(), advance)
    }
}
