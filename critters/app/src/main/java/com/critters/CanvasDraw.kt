package com.critters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.critters.game.Draw
import com.critters.game.SH
import com.critters.game.SW
import kotlin.math.roundToInt

/** [Draw] on an Android [Canvas]: scales virtual units up to screen pixels. */
class CanvasDraw : Draw {
    private lateinit var canvas: Canvas
    private var s = 1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rectF = RectF()
    private val dst = Rect()
    private val frame: Bitmap = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888)

    fun begin(canvas: Canvas, scale: Float) {
        this.canvas = canvas
        s = scale
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color
        canvas.drawRect(x * s, y * s, (x + w) * s, (y + h) * s, paint)
    }

    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color
        rectF.set(x * s, y * s, (x + w) * s, (y + h) * s)
        canvas.drawOval(rectF, paint)
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {
        textPaint.color = color
        textPaint.textSize = size * this.s
        textPaint.textAlign = when (align) {
            1 -> Paint.Align.CENTER
            2 -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
        canvas.drawText(s, x * this.s, y * this.s, textPaint)
    }

    override fun screen(pixels: IntArray, x: Float, y: Float) {
        frame.setPixels(pixels, 0, SW, 0, 0, SW, SH)
        val l = (x * s).roundToInt()
        val t = (y * s).roundToInt()
        dst.set(l, t, l + (SW * s).roundToInt(), t + (SH * s).roundToInt())
        canvas.drawBitmap(frame, null, dst, pixelPaint)
    }
}
