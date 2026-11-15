package com.sitarsitara

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.sitarsitara.core.Script
import com.sitarsitara.core.SitarNote
import com.sitarsitara.core.TaalGrid

/**
 * A song's sargam on the taal grid: each line headed by its time in the song, its vibhags (wrapping onto the next
 * row when the screen is narrow) marked X, 2, 0, 3 above, one box per matra with the notes that share it. Octaves
 * are dots above (taar) or below (mandra) a swara; in Devanagari komal is a line below and tivra Ma a stroke above.
 * ~ before a note: reached by meend. * after it: fast strokes. The matra playing now is lit; tapping one plays from it.
 */
class SargamView(context: Context, private val onTap: (Double) -> Unit) : View(context) {
    private val dp = resources.displayMetrics.density
    private var lines: List<TaalGrid.Line> = emptyList()
    private var notes: List<SitarNote> = emptyList()
    var script: Script = Script.LATIN
        set(v) { field = v; requestLayout(); invalidate() }

    /** The song time now, which lights its matra. */
    var now: Double = -1.0
        set(v) {
            val was = current
            field = v
            current = boxes.indexOfLast { it.time <= v && v < it.lineEnd }
            if (current != was) invalidate()
        }
    private var current = -1

    private class Box(val rect: RectF, val time: Double, val lineEnd: Double, val parts: List<Int?>)
    private class Label(val text: String, val x: Float, val y: Float, val kind: Int)
    private class Bar(val x: Float, val top: Float, val bottom: Float, val double: Boolean)

    private val boxes = ArrayList<Box>()
    private val labels = ArrayList<Label>()
    private val bars = ArrayList<Bar>()
    private val rules = ArrayList<Float>()
    private var laidOutFor = -1
    private var contentHeight = 0

    private val swaraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; textSize = 21 * dp }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SAFFRON; textSize = 11 * dp; typeface = Typeface.DEFAULT_BOLD }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PEACOCK; textSize = 12 * dp; typeface = Typeface.MONOSPACE }
    private val ornPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PEACOCK; textSize = 17 * dp; typeface = Typeface.DEFAULT_BOLD }
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 19 * dp }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    private val linePaint = Paint().apply { color = 0xFF6B4A52.toInt(); strokeWidth = 1 * dp }
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6E4A0E.toInt() }
    private val hover = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }

    private val labelW = 46 * dp
    private val markH = 16 * dp
    private val noteH = 44 * dp
    private val rowGap = 10 * dp
    private val pad = 7 * dp
    private val gap = 3 * dp
    private val minBox = 38 * dp

    fun show(lines: List<TaalGrid.Line>, notes: List<SitarNote>) {
        this.lines = lines
        this.notes = notes
        laidOutFor = -1
        requestLayout()
        invalidate()
    }

    /** The top of the row that holds song time [t], for scrolling it into view; -1 if none. */
    fun rowTopAt(t: Double): Int {
        val b = boxes.lastOrNull { it.time <= t && t < it.lineEnd } ?: return -1
        return (b.rect.top - markH - rowGap).toInt().coerceAtLeast(0)
    }

    private fun partWidth(p: Int?): Float {
        if (p == null) return holdPaint.measureText("–")
        val n = notes[p]
        return swaraPaint.measureText(n.swara.letter(script)) +
            (if (n.note.meend) ornPaint.measureText("~") else 0f) + (if (n.note.fast) ornPaint.measureText("*") else 0f)
    }

    private fun layout(width: Int) {
        boxes.clear(); labels.clear(); bars.clear(); rules.clear()
        if (width <= 0) return
        val right = width - pad
        var y = rowGap
        for ((li, line) in lines.withIndex()) {
            if (li > 0) { rules += y - rowGap / 2 }
            labels += Label(TaalGrid.clock(line.start), pad, y + markH + noteH * 0.62f, 1)
            var x = labelW
            var rowTop = y
            for ((vi, v) in line.vibhags.withIndex()) {
                val widths = v.matras.map { m -> maxOf(minBox, m.parts.sumOf { partWidth(it).toDouble() }.toFloat() + gap * (m.parts.size - 1) + 2 * pad) }
                val w = widths.sum() + 2 * gap * widths.size + 2 * pad
                if (x > labelW && x + w > right) { // wrap the vibhag onto the next row
                    y += markH + noteH + rowGap
                    rowTop = y
                    x = labelW
                }
                if (vi > 0 || x > labelW) bars += Bar(x, rowTop + markH * 0.4f, rowTop + markH + noteH, v.sam && vi > 0)
                labels += Label(v.mark, x + pad, rowTop + markH * 0.8f, 0)
                var bx = x + pad
                for ((k, m) in v.matras.withIndex()) {
                    boxes += Box(RectF(bx, rowTop + markH + 2 * dp, bx + widths[k], rowTop + markH + noteH - 2 * dp), m.time, line.end, m.parts)
                    bx += widths[k] + 2 * gap
                }
                x += w
            }
            y += markH + noteH + rowGap * 1.6f
        }
        contentHeight = (y + rowGap).toInt()
        laidOutFor = width
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w != laidOutFor) layout(w)
        setMeasuredDimension(w, maxOf(contentHeight, (60 * dp).toInt()))
    }

    override fun onDraw(canvas: Canvas) {
        if (laidOutFor != width) { layout(width); requestLayout() }
        for (y in rules) canvas.drawLine(pad, y, width - pad, y, linePaint)
        for (l in labels) canvas.drawText(l.text, l.x, l.y, if (l.kind == 0) markPaint else timePaint)
        for (b in bars) {
            canvas.drawLine(b.x, b.top, b.x, b.bottom, linePaint)
            if (b.double) canvas.drawLine(b.x - 3 * dp, b.top, b.x - 3 * dp, b.bottom, linePaint)
        }
        for ((i, b) in boxes.withIndex()) {
            if (i == current) canvas.drawRoundRect(b.rect, 8 * dp, 8 * dp, lit)
            drawMatra(canvas, b)
        }
        if (lines.isEmpty()) canvas.drawText("No sitar notes were found in this song.", pad, 30 * dp, timePaint)
    }

    private fun drawMatra(canvas: Canvas, b: Box) {
        val total = b.parts.sumOf { partWidth(it).toDouble() }.toFloat() + gap * (b.parts.size - 1)
        var x = b.rect.centerX() - total / 2
        val base = b.rect.top + (b.rect.height() + swaraPaint.textSize * 0.7f) / 2
        for (p in b.parts) {
            if (p == null) {
                canvas.drawText("–", x, base, holdPaint)
                x += holdPaint.measureText("–") + gap
                continue
            }
            val n = notes[p]
            if (n.note.meend) { canvas.drawText("~", x, base, ornPaint); x += ornPaint.measureText("~") }
            val letter = n.swara.letter(script)
            val w = swaraPaint.measureText(letter)
            canvas.drawText(letter, x, base, swaraPaint)
            val cx = x + w / 2
            val r = 2.2f * dp
            // Octave dots: above for taar, below for mandra, one per octave.
            val octaves = kotlin.math.abs(n.swara.octave)
            for (k in 0 until octaves) {
                val dx = (k - (octaves - 1) / 2f) * 6 * dp
                val dy = if (n.swara.octave > 0) base - swaraPaint.textSize * 0.95f else base + 7 * dp
                canvas.drawCircle(cx + dx, dy, r, dotPaint)
            }
            if (script == Script.DEVANAGARI && n.swara.komal) {
                val uy = base + (if (n.swara.octave < 0) 12 else 5) * dp
                canvas.drawLine(x + 1 * dp, uy, x + w - 1 * dp, uy, swaraPaint.also { it.strokeWidth = 1.5f * dp })
            }
            if (script == Script.DEVANAGARI && n.swara.tivra) {
                val top = base - swaraPaint.textSize * (if (n.swara.octave > 0) 1.25f else 0.95f)
                canvas.drawLine(cx, top, cx, top + 6 * dp, swaraPaint.also { it.strokeWidth = 1.5f * dp })
            }
            x += w
            if (n.note.fast) { canvas.drawText("*", x, base, ornPaint); x += ornPaint.measureText("*") }
            x += gap
        }
    }

    private var down: Box? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = boxes.firstOrNull { it.rect.contains(e.x, e.y) }; return down != null }
            MotionEvent.ACTION_UP -> {
                val b = down
                down = null
                if (b != null && b.rect.contains(e.x, e.y)) { performClick(); onTap(b.time) }
            }
            MotionEvent.ACTION_CANCEL -> down = null
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
