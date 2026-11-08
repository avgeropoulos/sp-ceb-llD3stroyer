package com.goalrush

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.goalrush.game.Gfx

/** [Gfx] backed by an Android [Canvas]; scales virtual pixels up to the screen. */
class CanvasGfx : Gfx {
    private lateinit var canvas: Canvas
    private var s = 1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val gradPaint = Paint()
    private var gradKey = ""
    private val rectF = RectF()
    private val path = Path()

    fun begin(canvas: Canvas, scale: Float) {
        this.canvas = canvas
        s = scale
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color
        canvas.drawRect(x * s, y * s, (x + w) * s, (y + h) * s, paint)
    }

    override fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        paint.color = color
        rectF.set(x * s, y * s, (x + w) * s, (y + h) * s)
        canvas.drawRoundRect(rectF, r * s, r * s, paint)
    }

    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color
        rectF.set(x * s, y * s, (x + w) * s, (y + h) * s)
        canvas.drawOval(rectF, paint)
    }

    override fun poly(xs: FloatArray, ys: FloatArray, n: Int, color: Int) {
        if (n < 3) return
        path.reset()
        path.moveTo(xs[0] * s, ys[0] * s)
        for (i in 1 until n) path.lineTo(xs[i] * s, ys[i] * s)
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = width * s
        canvas.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, stroke)
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

    override fun gradient(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int) {
        val key = "$y,$h,$top,$bottom,$s"
        if (key != gradKey) {
            gradPaint.shader = LinearGradient(0f, y * s, 0f, (y + h) * s, top, bottom, Shader.TileMode.CLAMP)
            gradKey = key
        }
        canvas.drawRect(x * s, y * s, (x + w) * s, (y + h) * s, gradPaint)
    }
}
