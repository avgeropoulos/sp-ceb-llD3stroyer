package com.bouzouki

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.bouzouki.core.NoteNames
import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.SongAnalysis

/**
 * A moving strip of tablature: one line per course (highest on top, as bouzouki tab is
 * written), the song's notes as fret numbers sliding left towards the "now" line.
 * A legato note's box joins the one before, with a small "h" (hammer-on), "p" (pull-off)
 * or slash (slide, up or down) before its number, in a box made wide enough for both; when
 * the next note on the line comes too soon for that, the mark goes just under the box's
 * start instead (over it on the lowest line). Tremolo notes have slashes after the number.
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
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = 0xFFFFFFFF.toInt(); textSize = 12 * dp
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC); textSize = 10 * dp
    }
    private val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.4f * dp; strokeCap = Paint.Cap.ROUND }
    // A mark outside its box, where the next note leaves no room inside: small, in the "now" gold.
    private val outMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC); textSize = 9 * dp
        color = OUT_MARK
    }
    private val outSlash = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.3f * dp; strokeCap = Paint.Cap.ROUND; color = OUT_MARK }
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
        val first = (s.nextAfter(from) - 1).coerceAtLeast(0)
        var i = first
        while (i < s.notes.size && s.notes[i].start <= to) {
            val n = s.notes[i]
            val x0 = xFor(n.start)
            val legato = s.legatoInto(i)
            val inline = legato != null && markFits(s, i)
            val x1 = boxEnd(s, i, inline)
            val y = yFor(n.course)
            val playing = n.start <= time && time < n.end
            val ink = if (playing) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
            box.color = if (playing) 0xFFFFC107.toInt() else 0xFF0D5EAF.toInt()
            if (legato != null) {
                // A link from the note before: one stroke of the pick for both.
                val px1 = boxEnd(s, i - 1, s.legatoInto(i - 1) != null && markFits(s, i - 1))
                if (px1 < x0 + 2 * dp) canvas.drawRect(px1 - 2 * dp, y - 3.5f * dp, x0 + 2 * dp, y + 3.5f * dp, box)
            }
            canvas.drawRoundRect(x0, y - 8 * dp, x1, y + 8 * dp, 5 * dp, 5 * dp, box)
            num.color = ink
            val textWidth = fretWidth(n.fret)
            var nx = x0 + 7 * dp
            if (inline) {
                mark.color = ink
                slash.color = ink
                when (legato) {
                    Articulation.HAMMER -> canvas.drawText(HAMMER_MARK, x0 + 3.8f * dp, y + 2.8f * dp, mark)
                    Articulation.PULL -> canvas.drawText(PULL_MARK, x0 + 3.8f * dp, y + 2.8f * dp, mark)
                    else -> { // a slide, up or down
                        val rise = if (n.midi >= s.notes[i - 1].midi) 3.5f * dp else -3.5f * dp
                        canvas.drawLine(x0 + 2 * dp, y + rise, x0 + 5.5f * dp, y - rise, slash)
                    }
                }
                nx = x0 + MARK_ROOM * dp + textWidth / 2
            }
            canvas.drawText(fretText(n.fret), nx, y + 4.2f * dp, num)
            if (n.articulation == Articulation.TREMOLO) {
                val sx = nx + textWidth / 2 + 5 * dp
                if (sx + 10 * dp < x1) {
                    slash.color = ink
                    for (k in 0..2) canvas.drawLine(sx + k * 3.5f * dp - 1.8f * dp, y + 4 * dp, sx + k * 3.5f * dp + 1.8f * dp, y - 4 * dp, slash)
                }
            }
            i++
        }
        // Then the marks with no room in their box, over everything: just under the box's start
        // (over it on the lowest line, which has nothing below).
        i = first
        while (i < s.notes.size && s.notes[i].start <= to) {
            val legato = s.legatoInto(i)
            if (legato != null && !markFits(s, i)) {
                val n = s.notes[i]
                val x = xFor(n.start) + 3.5f * dp
                val below = n.course > 0
                val y = yFor(n.course)
                when (legato) {
                    Articulation.HAMMER, Articulation.PULL -> {
                        val base = if (below) y + 15 * dp else y - 9.5f * dp
                        canvas.drawText(if (legato == Articulation.HAMMER) HAMMER_MARK else PULL_MARK, x, base, outMark)
                    }
                    else -> {
                        val mid = if (below) y + 12.5f * dp else y - 12.5f * dp
                        val rise = if (n.midi >= s.notes[i - 1].midi) 3 * dp else -3 * dp
                        canvas.drawLine(x - 2 * dp, mid + rise, x + 2 * dp, mid - rise, outSlash)
                    }
                }
            }
            i++
        }
        canvas.drawLine(width * nowAt, 0f, width * nowAt, height.toFloat(), nowLine)
    }

    /**
     * Whether note [i]'s legato mark fits before its number: a box wide enough for both ends before
     * the next note on its line starts.
     */
    private fun markFits(s: SongAnalysis, i: Int): Boolean {
        val n = s.notes[i]
        val x0 = xFor(n.start)
        val need = x0 + (MARK_ROOM + 3) * dp + fretWidth(n.fret)
        var j = i + 1
        while (j < s.notes.size) {
            val x = xFor(s.notes[j].start)
            if (x >= need) break
            if (s.notes[j].course == n.course) return false
            j++
        }
        return true
    }

    /** Right edge of note [i]'s box: as long as the note, at least 14 dp, and with room for its mark when that goes [inline]. */
    private fun boxEnd(s: SongAnalysis, i: Int, inline: Boolean): Float {
        val n = s.notes[i]
        val x0 = xFor(n.start)
        val x1 = maxOf(xFor(n.end), x0 + 14 * dp)
        return if (inline) maxOf(x1, x0 + (MARK_ROOM + 3) * dp + fretWidth(n.fret)) else x1
    }

    private val fretWidths = FloatArray(FRETS.size) // measured once each (0 = not yet)

    private fun fretWidth(fret: Int): Float {
        if (fret !in fretWidths.indices) return num.measureText(fretText(fret))
        if (fretWidths[fret] == 0f) fretWidths[fret] = num.measureText(fretText(fret))
        return fretWidths[fret]
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && song != null) {
            onSeek?.invoke((time + (e.x - width * nowAt) / width * secondsShown).coerceAtLeast(0.0))
        }
        return true
    }

    private companion object {
        const val HAMMER_MARK = "h"
        const val PULL_MARK = "p"
        /** dp from a box's start to where its number starts when a mark goes in front of it. */
        const val MARK_ROOM = 7.5f
        const val OUT_MARK = 0xFFFFD54F.toInt()
        val FRETS = Array(40) { it.toString() }
        fun fretText(fret: Int) = FRETS.getOrElse(fret) { fret.toString() }
    }
}
