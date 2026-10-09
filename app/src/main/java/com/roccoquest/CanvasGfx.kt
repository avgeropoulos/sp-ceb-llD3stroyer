package com.roccoquest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import kotlin.math.floor

/** [Gfx] backed by an Android [Canvas]; scales virtual pixels up to the screen. */
class CanvasGfx : Gfx {
    private lateinit var canvas: Canvas
    private var s = 1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rectF = RectF()
    private val dst = Rect()
    private val path = Path()

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

    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {
        path.reset()
        path.moveTo(xs[0] * s, ys[0] * s)
        for (i in 1 until xs.size) path.lineTo(xs[i] * s, ys[i] * s)
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }

    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) {
        val bmp = s.cache as? Bitmap
            ?: Bitmap.createBitmap(s.pixels, s.w, s.h, Bitmap.Config.ARGB_8888).also { s.cache = it }
        // Snap edges to whole screen pixels so neighbouring tiles never leave seams.
        dst.set(
            floor(x * this.s).toInt(), floor(y * this.s).toInt(),
            floor((x + w) * this.s).toInt(), floor((y + h) * this.s).toInt(),
        )
        if (!flipX && !flipY) {
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            return
        }
        canvas.save()
        canvas.scale(
            if (flipX) -1f else 1f, if (flipY) -1f else 1f,
            (dst.left + dst.right) / 2f, (dst.top + dst.bottom) / 2f,
        )
        canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        canvas.restore()
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
}
