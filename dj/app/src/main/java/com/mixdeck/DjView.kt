package com.mixdeck

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint as APaint
import android.graphics.Path as APath
import android.graphics.RectF as ARectF
import android.graphics.Typeface as AFace
import android.view.MotionEvent
import android.view.View
import com.mixdeck.engine.Mixer
import com.mixdeck.ui.Canvas
import com.mixdeck.ui.Console
import com.mixdeck.ui.Paint
import com.mixdeck.ui.Path
import com.mixdeck.ui.RectF
import com.mixdeck.ui.TextMetrics
import com.mixdeck.ui.Tutorial
import com.mixdeck.ui.Typeface

/** The phone's DJ screen: hosts the shared [Console] in an Android View, with multi-touch. */
@SuppressLint("ViewConstructor")
class DjView(context: Context, mixer: Mixer, host: Console.Host) : View(context) {
    val console = Console(mixer, host, computer = false)
    private val canvas = AndroidCanvas()

    var helpMode by console::helpMode
    var tutorial: Tutorial?
        get() = console.tutorial
        set(v) { console.tutorial = v }

    init {
        TextMetrics.current = AndroidTextMetrics
        isFocusable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = console.layout(w, h)

    override fun onDraw(c: android.graphics.Canvas) {
        canvas.c = c
        console.draw(canvas)
        postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                console.pointerDown(e.getPointerId(i), e.getX(i), e.getY(i), first = e.actionMasked == MotionEvent.ACTION_DOWN)
            }
            MotionEvent.ACTION_MOVE -> for (k in 0 until e.pointerCount) console.pointerMove(e.getPointerId(k), e.getX(k), e.getY(k))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> console.pointerUp(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> console.cancelPointers()
        }
        return true
    }
}

/** Draws the shared console's drawing commands with android.graphics. */
class AndroidCanvas : Canvas {
    lateinit var c: android.graphics.Canvas
    private val p = APaint(APaint.ANTI_ALIAS_FLAG)
    private val r = ARectF()
    private val path = APath().apply { fillType = APath.FillType.EVEN_ODD }

    private fun paint(src: Paint): APaint {
        p.color = src.color
        p.style = if (src.style == Paint.Style.STROKE) APaint.Style.STROKE else APaint.Style.FILL
        p.strokeWidth = src.strokeWidth
        p.strokeCap = if (src.strokeCap == Paint.Cap.ROUND) APaint.Cap.ROUND else APaint.Cap.BUTT
        p.textSize = src.textSize
        p.textAlign = when (src.textAlign) { Paint.Align.LEFT -> APaint.Align.LEFT; Paint.Align.CENTER -> APaint.Align.CENTER; Paint.Align.RIGHT -> APaint.Align.RIGHT }
        p.typeface = face(src.typeface)
        return p
    }

    private fun rect(s: RectF) = r.apply { set(s.left, s.top, s.right, s.bottom) }

    override fun drawColor(color: Int) = c.drawColor(color)
    override fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) = c.drawRect(l, t, r, b, paint(p))
    override fun drawRoundRect(r: RectF, rx: Float, ry: Float, p: Paint) = c.drawRoundRect(rect(r), rx, ry, paint(p))
    override fun drawCircle(cx: Float, cy: Float, radius: Float, p: Paint) = c.drawCircle(cx, cy, radius, paint(p))
    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) = c.drawLine(x0, y0, x1, y1, paint(p))
    override fun drawLines(pts: FloatArray, offset: Int, count: Int, p: Paint) = c.drawLines(pts, offset, count, paint(p))
    override fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, p: Paint) =
        c.drawArc(rect(oval), startAngle, sweepAngle, useCenter, paint(p))
    override fun drawText(s: String, x: Float, y: Float, p: Paint) = c.drawText(s, x, y, paint(p))
    override fun drawPath(path: Path, p: Paint) {
        this.path.reset()
        for (part in path.parts) when (part) {
            is Path.Part.Rect -> this.path.addRect(rect(part.r), APath.Direction.CW)
            is Path.Part.Round -> this.path.addRoundRect(rect(part.r), part.rx, part.ry, APath.Direction.CW)
            is Path.Part.Poly -> {
                this.path.moveTo(part.pts[0], part.pts[1])
                for (k in 2 until part.pts.size step 2) this.path.lineTo(part.pts[k], part.pts[k + 1])
                this.path.close()
            }
        }
        c.drawPath(this.path, paint(p))
    }
    override fun save() { c.save() }
    override fun restore() = c.restore()
    override fun clipRect(r: RectF) { c.clipRect(rect(r)) }

    companion object {
        private val bold = AFace.DEFAULT_BOLD
        private val mono = AFace.create(AFace.MONOSPACE, AFace.BOLD)
        fun face(t: Typeface): AFace = if (t == Typeface.MONO_BOLD) mono else bold

    }
}

/** Text measuring with Android's fonts. */
object AndroidTextMetrics : TextMetrics {
    private val m = APaint(APaint.ANTI_ALIAS_FLAG)
    private fun at(size: Float, face: Typeface) = m.apply { textSize = size; typeface = AndroidCanvas.face(face) }
    override fun width(s: CharSequence, start: Int, end: Int, size: Float, face: Typeface) = at(size, face).measureText(s, start, end)
    override fun ascent(size: Float, face: Typeface) = at(size, face).ascent()
    override fun descent(size: Float, face: Typeface) = at(size, face).descent()
}
