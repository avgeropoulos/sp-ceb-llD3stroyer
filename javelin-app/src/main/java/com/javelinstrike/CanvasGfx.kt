package com.javelinstrike

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.javelinstrike.game.Gfx

/** [Gfx] backed by an Android [Canvas]; scales virtual pixels up to the screen. */
class CanvasGfx : Gfx {
    private lateinit var canvas: Canvas
    private var s = 1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()
    private val path = Path()
    private var clipped = false

    fun begin(canvas: Canvas, scale: Float) {
        this.canvas = canvas
        s = scale
        clipped = false
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

    override fun poly(xs: FloatArray, ys: FloatArray, color: Int, n: Int) {
        path.reset()
        path.moveTo(xs[0] * s, ys[0] * s)
        for (i in 1 until n) path.lineTo(xs[i] * s, ys[i] * s)
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        linePaint.color = color
        linePaint.strokeWidth = width * s
        canvas.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, linePaint)
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int, serif: Boolean) {
        textPaint.typeface = if (serif) this.serif else mono
        textPaint.color = color
        textPaint.textSize = size * this.s
        textPaint.textAlign = when (align) {
            1 -> Paint.Align.CENTER
            2 -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
        canvas.drawText(s, x * this.s, y * this.s, textPaint)
    }

    override fun clip(x: Float, y: Float, w: Float, h: Float) {
        if (clipped) canvas.restore()
        canvas.save()
        canvas.clipRect(x * s, y * s, (x + w) * s, (y + h) * s)
        clipped = true
    }

    override fun unclip() {
        if (!clipped) return
        canvas.restore()
        clipped = false
    }
}
