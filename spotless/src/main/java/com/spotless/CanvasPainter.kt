package com.spotless

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.spotless.game.ALIGN_CENTER
import com.spotless.game.ALIGN_RIGHT
import com.spotless.game.Painter
import com.spotless.game.Shape

/** [Painter] backed by an Android [Canvas]. The caller sets up the virtual-to-screen transform. */
class CanvasPainter : Painter {
    private lateinit var canvas: Canvas

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rectF = RectF()
    private val path = Path()

    fun begin(canvas: Canvas) {
        this.canvas = canvas
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        fillPaint.color = color
        canvas.drawRect(x, y, x + w, y + h, fillPaint)
    }

    override fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        fillPaint.color = color
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, r, r, fillPaint)
    }

    override fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        fillPaint.color = color
        rectF.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(rectF, fillPaint)
    }

    private fun toPath(s: Shape): Path {
        path.reset()
        s.forEach(
            { x, y -> path.moveTo(x, y) },
            { x, y -> path.lineTo(x, y) },
            { a, b, x, y -> path.quadTo(a, b, x, y) },
            { a, b, c, d, x, y -> path.cubicTo(a, b, c, d, x, y) },
            { path.close() },
        )
        return path
    }

    override fun fill(p: Shape, color: Int) {
        fillPaint.color = color
        canvas.drawPath(toPath(p), fillPaint)
    }

    override fun stroke(p: Shape, color: Int, width: Float) {
        strokePaint.color = color
        strokePaint.strokeWidth = width
        canvas.drawPath(toPath(p), strokePaint)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        strokePaint.color = color
        strokePaint.strokeWidth = width
        canvas.drawLine(x1, y1, x2, y2, strokePaint)
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {
        textPaint.color = color
        textPaint.textSize = size
        textPaint.textAlign = when (align) {
            ALIGN_CENTER -> Paint.Align.CENTER
            ALIGN_RIGHT -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
        canvas.drawText(s, x, y, textPaint)
    }

    override fun textWidth(s: String, size: Float): Float {
        textPaint.textSize = size
        return textPaint.measureText(s)
    }

    override fun save() { canvas.save() }
    override fun restore() = canvas.restore()
    override fun translate(x: Float, y: Float) = canvas.translate(x, y)
    override fun scale(sx: Float, sy: Float) = canvas.scale(sx, sy)
    override fun rotate(degrees: Float) = canvas.rotate(degrees)
}
