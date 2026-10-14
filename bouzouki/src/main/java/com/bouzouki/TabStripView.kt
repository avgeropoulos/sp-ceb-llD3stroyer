package com.bouzouki

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.bouzouki.core.NoteNames
import com.bouzouki.core.song.SongAnalysis

/**
 * A moving strip of tablature: one line per course (highest on top, as bouzouki tab is
 * written), the song's notes as fret numbers sliding left towards the "now" line.
 * Tap it to jump the song to that moment.
 */
class TabStripView(context: Context) : View(context) {
    var song: SongAnalysis? = null
        set(v) { field = v; invalidate() }
    var time = 0.0
        set(v) { field = v; invalidate() }
    var names = NoteNames.WESTERN
    /** Called with a time in seconds when the strip is tapped. */
    var onSeek: ((Double) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val secondsShown = 5.0
    private val nowAt = 0.18f // the "now" line, as a fraction of the width

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5D7A99.toInt(); strokeWidth = 1.2f * dp }
    private val nowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt(); strokeWidth = 2.5f * dp }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = 0xFFFFFFFF.toInt()
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9FB4CC.toInt(); textSize = 10 * dp }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (70 * dp).toInt())
    }

    private fun xFor(t: Double) = (width * nowAt + (t - time) / secondsShown * width).toFloat()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0F2238.toInt())
        val s = song ?: run {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText("The bouzouki tab appears here once the song is ready", width / 2f, height / 2f, label)
            return
        }
        val courses = s.instrument.courses.size
        val top = 10 * dp
        val gap = (height - 2 * top) / (courses - 1).coerceAtLeast(1)
        fun yFor(course: Int) = top + (courses - 1 - course) * gap // highest course on top
        label.textAlign = Paint.Align.LEFT
        for (c in 0 until courses) {
            canvas.drawLine(0f, yFor(c), width.toFloat(), yFor(c), line)
            canvas.drawText(names.name(s.instrument.courses[c].openMidi), 2 * dp, yFor(c) - 2 * dp, label)
        }
        val from = time - secondsShown * nowAt
        val to = time + secondsShown * (1 - nowAt)
        var i = (s.nextAfter(from) - 1).coerceAtLeast(0)
        num.textSize = 12 * dp
        while (i < s.notes.size && s.notes[i].start <= to) {
            val n = s.notes[i]
            val x0 = xFor(n.start)
            val x1 = maxOf(xFor(n.end), x0 + 14 * dp)
            val y = yFor(n.course)
            val playing = n.start <= time && time < n.end
            box.color = if (playing) 0xFFFFC107.toInt() else 0xFF0D5EAF.toInt()
            canvas.drawRoundRect(x0, y - 8 * dp, x1, y + 8 * dp, 5 * dp, 5 * dp, box)
            num.color = if (playing) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
            canvas.drawText(n.fret.toString(), x0 + 7 * dp, y + 4.2f * dp, num)
            i++
        }
        canvas.drawLine(width * nowAt, 0f, width * nowAt, height.toFloat(), nowLine)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && song != null) {
            onSeek?.invoke((time + (e.x - width * nowAt) / width * secondsShown).coerceAtLeast(0.0))
        }
        return true
    }
}
