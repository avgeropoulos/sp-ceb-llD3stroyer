package com.bouzouki

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import com.bouzouki.core.NoteNames
import com.bouzouki.core.song.Beats
import com.bouzouki.core.song.SongAnalysis
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The whole song as printed bouzouki tablature, for a vertical [ScrollView]: rows of two bars
 * (8 beats, or 4 s when the beats aren't known), one line per course with the highest on top,
 * fret numbers where the notes start and a bar through the ones that are held (tremolo).
 * A gold cursor follows the song, the note playing is lit, and the sheet scrolls along unless
 * the reader has just scrolled it. Tap a note to play it, or anywhere else to go there.
 * Stretches without bouzouki fold into one short row.
 */
class TabSheetView(context: Context) : View(context) {
    var song: SongAnalysis? = null
        set(v) { field = v; relayout() }
    /** Beat times in seconds; empty for rows of [SECONDS_PER_ROW]. */
    var beats: List<Double> = emptyList()
        set(v) { field = v; relayout() }
    /** The song's time now, in seconds. */
    var time = 0.0
        set(v) { if (v != field) { field = v; invalidate(); follow(force = false) } }
    var names = NoteNames.WESTERN
        set(v) { field = v; relayout() }
    /** Called with a note's index when it's tapped. */
    var onNoteTap: ((Int) -> Unit)? = null
    /** Called with a time in seconds when the sheet is tapped away from the notes. */
    var onSeek: ((Double) -> Unit)? = null

    private val dp = resources.displayMetrics.density

    // Layout, rebuilt when the song or the beats change. grid[] holds the beat times (extended
    // past both ends of the song); a row of music covers grid[g]..grid[g + 8].
    private var grid = DoubleArray(0)
    private var rowCount = 0
    private var rowGrid = IntArray(0)        // first grid index, or -1 for a folded stretch
    private var rowT0 = DoubleArray(0)
    private var rowT1 = DoubleArray(0)
    private var rowTop = FloatArray(0)
    private var rowHeight = FloatArray(0)
    private var rowLabel = arrayOf<String>()
    private var courseNames = arrayOf<String>()
    private var courses = 0
    private var title = ""
    private var titleShown = ""
    private var subtitle = ""
    private var headerHeight = 0f
    private var totalHeight = 0f

    // Geometry (px).
    private val margin = 8 * dp
    private val bandHeight = 18 * dp          // above the staff: the row's time
    private val courseGap = 22 * dp
    private val rowPadBottom = 18 * dp
    private val foldHeight = 34 * dp
    private val boxHeight = 16 * dp
    private var labelWidth = 0f

    // Auto-scroll.
    private var lastUserScroll = 0L
    private var autoScrollUntil = 0L
    private var followedRow = -1
    private var lastTarget = -1

    // Tapping.
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tapping = false

    private val path = Path()

    private val paperPaint = Paint().apply { color = PAPER }
    private val staffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink(0x99); strokeWidth = 1f * dp }
    private val beatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink(0x30); strokeWidth = 0.8f * dp }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink(0xB0); strokeWidth = 1.4f * dp }
    private val coursePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEK_BLUE; textSize = 12 * dp; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEK_RED; textSize = 11 * dp; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK; textSize = 13 * dp; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PAPER }
    private val boxEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink(0x60); style = Paint.Style.STROKE; strokeWidth = 1f * dp }
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD }
    private val litEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD_DARK; style = Paint.Style.STROKE; strokeWidth = 1.5f * dp }
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (GREEK_BLUE and 0x00FFFFFF) or (0xA0 shl 24); strokeWidth = 3.2f * dp; strokeCap = Paint.Cap.ROUND
    }
    private val holdLitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD_DARK; strokeWidth = 3.2f * dp; strokeCap = Paint.Cap.ROUND }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD_DARK; strokeWidth = 2.5f * dp }
    private val cursorHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD_DARK }
    private val rowTint = Paint().apply { color = 0x1AE0A100 }
    private val playedTint = Paint().apply { color = 0x26E0A100 }
    private val foldLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink(0x60); strokeWidth = 1f * dp; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4 * dp, 4 * dp), 0f)
    }
    private val foldText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink(0x99); textSize = 12 * dp; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK; textSize = 17 * dp; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink(0xB0); textSize = 12 * dp; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEK_BLUE; strokeWidth = 1.2f * dp }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink(0x99); textSize = 14 * dp; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }

    init { setBackgroundColor(PAPER) }

    // --- layout ---

    private fun relayout() {
        val s = song
        followedRow = -1
        lastTarget = -1
        lastUserScroll = 0L
        autoScrollUntil = SystemClock.uptimeMillis() + 500 // the scroll view settling isn't the reader
        if (s == null) {
            rowCount = 0
            totalHeight = 0f
            requestLayout(); invalidate()
            return
        }
        courses = s.instrument.courses.size
        courseNames = Array(courses) { names.name(s.instrument.courses[it].openMidi) }
        labelWidth = courseNames.maxOf { coursePaint.measureText(it) }
        val notes = s.notes
        val first = notes.firstOrNull()?.start ?: 0.0
        val end = max(notes.maxOfOrNull { it.end } ?: 0.0, first + 1.0)
        grid = buildGrid(first, end)

        // Rows of 8 beats; empty stretches of two rows or more (and the intro) fold into one.
        val music = (grid.size - 1) / BEATS_PER_ROW
        val g = ArrayList<Int>()
        val t0 = ArrayList<Double>()
        val t1 = ArrayList<Double>()
        var r = 0
        while (r < music) {
            if (hasNotes(s, grid[r * BEATS_PER_ROW], grid[(r + 1) * BEATS_PER_ROW])) {
                g += r * BEATS_PER_ROW; t0 += grid[r * BEATS_PER_ROW]; t1 += grid[(r + 1) * BEATS_PER_ROW]
                r++
                continue
            }
            var e = r
            while (e + 1 < music && !hasNotes(s, grid[(e + 1) * BEATS_PER_ROW], grid[(e + 2) * BEATS_PER_ROW])) e++
            val intro = g.isEmpty()
            if (e == r && !intro && e + 1 < music) { // a single empty row keeps its place
                g += r * BEATS_PER_ROW; t0 += grid[r * BEATS_PER_ROW]; t1 += grid[(r + 1) * BEATS_PER_ROW]
            } else if (e + 1 < music || !intro) {
                g += -1; t0 += if (intro) 0.0 else grid[r * BEATS_PER_ROW]; t1 += grid[(e + 1) * BEATS_PER_ROW]
            }
            r = e + 1
        }
        if (g.isNotEmpty() && g[0] >= 0 && t0[0] > 1.5) { g.add(0, -1); t0.add(0, 0.0); t1.add(0, t0[1]) }

        rowCount = g.size
        rowGrid = g.toIntArray()
        rowT0 = t0.toDoubleArray()
        rowT1 = t1.toDoubleArray()
        rowTop = FloatArray(rowCount)
        rowHeight = FloatArray(rowCount)
        rowLabel = Array(rowCount) { i ->
            if (rowGrid[i] >= 0) clock(rowT0[i])
            else (if (i == 0) "Intro" else "No bouzouki") + " · " + clock(rowT0[i]) + " – " + clock(rowT1[i])
        }
        title = s.title.ifBlank { "Bouzouki line" }
        fitTitle()
        val bpm = Beats.period(beats)?.let { (60.0 / it).roundToInt() }
        subtitle = (if (bpm != null && beats.size >= 8) "♩ ≈ $bpm  ·  " else "") +
            courseNames.joinToString(" ") + "  ·  " + s.instrument.greekName
        headerHeight = 58 * dp
        var y = headerHeight
        val staff = (courses - 1) * courseGap
        for (i in 0 until rowCount) {
            rowTop[i] = y
            rowHeight[i] = if (rowGrid[i] >= 0) bandHeight + staff + rowPadBottom + 6 * dp else foldHeight
            y += rowHeight[i]
        }
        totalHeight = y + 24 * dp
        requestLayout()
        invalidate()
    }

    /** Beat times covering [from]..[to], from the beats if there are enough, else every 0.5 s. */
    private fun buildGrid(from: Double, to: Double): DoubleArray {
        val b = beats
        val p = Beats.period(b)
        val out = ArrayList<Double>()
        if (b.size >= BEATS_PER_ROW && p != null && p in 0.2..2.0) {
            // Whole rows before the first beat, so the beats keep their place in the rows.
            val before = ceil(max(0.0, b[0] - from) / p / BEATS_PER_ROW).toInt() * BEATS_PER_ROW
            for (k in before downTo 1) out += b[0] - k * p
            for (t in b) {
                if (out.isNotEmpty() && t <= out.last() + 0.05 * p) continue
                // Missing beats (a hole in the list) are filled in evenly, so no row stretches.
                val missing = if (out.isEmpty()) 0 else ((t - out.last()) / p).roundToInt() - 1
                if (missing > 0) { val last = out.last(); val step = (t - last) / (missing + 1); for (k in 1..missing) out += last + k * step }
                out += t
            }
        } else {
            out += 0.0
        }
        val step = if (b.size >= BEATS_PER_ROW && p != null && p in 0.2..2.0) p else SECONDS_PER_ROW / BEATS_PER_ROW
        while (out.last() < to || (out.size - 1) % BEATS_PER_ROW != 0) out += out.last() + step
        return out.toDoubleArray()
    }

    private fun hasNotes(s: SongAnalysis, t0: Double, t1: Double): Boolean {
        val i = s.nextAfter(t0 - 1e-9)
        if (i < s.notes.size && s.notes[i].start < t1) return true
        return i > 0 && s.notes[i - 1].end > t0 + 0.05
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (song == null) (200 * dp).toInt() else ceil(totalHeight).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec)) // a ScrollView's fillViewport asks for exactly its height
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitTitle()
        followedRow = -1
        lastTarget = -1
    }

    private val staffLeft get() = margin + labelWidth + 6 * dp
    private val left get() = staffLeft + 12 * dp
    private val right get() = width - margin - 6 * dp
    private fun courseY(row: Int, course: Int) = rowTop[row] + bandHeight + 6 * dp + (courses - 1 - course) * courseGap

    /** x of time [t] on a row of music: evenly spaced beats, time running evenly within each beat. */
    private fun xFor(row: Int, t: Double): Float {
        val g = rowGrid[row]
        val beatWidth = (right - left) / BEATS_PER_ROW
        if (t <= grid[g]) return left
        if (t >= grid[g + BEATS_PER_ROW]) return right
        var k = 0
        while (k < BEATS_PER_ROW - 1 && grid[g + k + 1] <= t) k++
        val frac = (t - grid[g + k]) / (grid[g + k + 1] - grid[g + k])
        return left + ((k + frac) * beatWidth).toFloat()
    }

    private fun timeAt(row: Int, x: Float): Double {
        val g = rowGrid[row]
        if (g < 0) return rowT0[row] + ((x - left) / (right - left)).coerceIn(0f, 1f) * (rowT1[row] - rowT0[row])
        val pos = ((x - left) / ((right - left) / BEATS_PER_ROW)).coerceIn(0f, BEATS_PER_ROW - 0.001f)
        val k = pos.toInt()
        return grid[g + k] + (pos - k) * (grid[g + k + 1] - grid[g + k])
    }

    /** The row playing at [t] (the nearest one outside the song), or -1 with no rows. */
    private fun rowAt(t: Double): Int {
        if (rowCount == 0) return -1
        var lo = 0
        var hi = rowCount - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (rowT0[mid] <= t) lo = mid else hi = mid - 1 }
        return lo
    }

    private fun rowAtY(y: Float): Int {
        if (rowCount == 0 || y < rowTop[0]) return -1
        var lo = 0
        var hi = rowCount - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (rowTop[mid] <= y) lo = mid else hi = mid - 1 }
        return if (y < rowTop[lo] + rowHeight[lo]) lo else -1
    }

    // --- drawing ---

    override fun onDraw(canvas: Canvas) {
        val s = song
        if (s == null || rowCount == 0) {
            val message = if (s == null) "The tablature appears here once the song is ready" else "No bouzouki notes in this song"
            canvas.drawText(message, width / 2f, 80 * dp, placeholderPaint)
            return
        }
        // Only the rows on screen, and a screen's worth either side for smooth scrolling.
        val scroller = parent as? View
        val viewTop = if (scroller != null) scroller.scrollY - top else 0
        val viewHeight = scroller?.height ?: height
        val from = viewTop - viewHeight
        val to = viewTop + 2 * viewHeight

        if (from < headerHeight) drawHeader(canvas)
        val now = rowAt(time)
        val lit = s.noteAt(time)
        var r = firstRowEndingBelow(from.toFloat())
        while (r < rowCount && rowTop[r] < to) {
            if (rowGrid[r] >= 0) drawRow(canvas, s, r, r == now, lit) else drawFold(canvas, r, r == now)
            r++
        }
    }

    /** The first row whose bottom is below [y]. */
    private fun firstRowEndingBelow(y: Float): Int {
        var lo = 0
        var hi = rowCount
        while (lo < hi) { val mid = (lo + hi) / 2; if (rowTop[mid] + rowHeight[mid] > y) hi = mid else lo = mid + 1 }
        return lo
    }

    private fun fitTitle() {
        titleShown = if (width > 0) TextUtils.ellipsize(title, titlePaint, width - 2 * margin, TextUtils.TruncateAt.END).toString() else title
    }

    private fun drawHeader(canvas: Canvas) {
        canvas.drawText(titleShown, margin, 24 * dp, titlePaint)
        canvas.drawText(subtitle, margin, 42 * dp, subtitlePaint)
        val y = headerHeight - 8 * dp
        canvas.drawLine(margin, y, width - margin, y, rulePaint)
        canvas.drawLine(margin, y + 3 * dp, width - margin, y + 3 * dp, rulePaint)
    }

    private fun drawFold(canvas: Canvas, r: Int, current: Boolean) {
        val top = rowTop[r]
        val mid = top + rowHeight[r] / 2
        if (current) canvas.drawRect(0f, top, width.toFloat(), top + rowHeight[r], rowTint)
        path.reset()
        path.moveTo(staffLeft, mid)
        path.lineTo(right, mid)
        canvas.drawPath(path, foldLine)
        val label = rowLabel[r]
        val w = foldText.measureText(label) / 2 + 8 * dp
        canvas.drawRect(width / 2f - w, mid - 9 * dp, width / 2f + w, mid + 9 * dp, paperPaint)
        canvas.drawText(label, width / 2f, mid + 4 * dp, foldText)
        if (current) drawCursor(canvas, left + ((time - rowT0[r]) / (rowT1[r] - rowT0[r])).coerceIn(0.0, 1.0).toFloat() * (right - left),
            mid - 10 * dp, mid + 10 * dp)
    }

    private fun drawRow(canvas: Canvas, s: SongAnalysis, r: Int, current: Boolean, lit: Int) {
        val g = rowGrid[r]
        val top = rowTop[r]
        val yTop = courseY(r, courses - 1)
        val yBottom = courseY(r, 0)
        val beatWidth = (right - left) / BEATS_PER_ROW
        val cursorX = if (current) xFor(r, time) else 0f
        if (current) {
            canvas.drawRect(0f, top, width.toFloat(), top + rowHeight[r], rowTint)
            canvas.drawRect(staffLeft, yTop - 8 * dp, cursorX, yBottom + 8 * dp, playedTint)
        }
        canvas.drawText(rowLabel[r], margin, top + 13 * dp, timePaint)

        // Staff: one line per course, the highest on top, its name in front.
        for (c in 0 until courses) {
            val y = courseY(r, c)
            canvas.drawLine(staffLeft, y, right, y, staffPaint)
            canvas.drawText(courseNames[c], margin, y + 4.2f * dp, coursePaint)
        }
        // Beats, and a bar line every four.
        for (k in 0..BEATS_PER_ROW) {
            val x = if (k == 0) staffLeft else left + k * beatWidth
            canvas.drawLine(x, yTop, x, yBottom, if (k % 4 == 0) barPaint else beatPaint)
        }
        // The cursor goes under the notes, so the one playing stays readable.
        if (current) drawCursor(canvas, cursorX, yTop - 10 * dp, yBottom + 10 * dp)

        // A note held over from the row before.
        val t0 = grid[g]
        val t1 = grid[g + BEATS_PER_ROW]
        val firstNote = s.nextAfter(t0 - 1e-9)
        val prev = firstNote - 1
        if (prev >= 0 && s.notes[prev].end > t0 + 0.05) {
            val n = s.notes[prev]
            val y = courseY(r, n.course)
            canvas.drawLine(staffLeft, y, xFor(r, n.end), y, if (prev == lit) holdLitPaint else holdPaint)
        }
        // Held notes' bars first, then the fret numbers over them.
        var i = firstNote
        while (i < s.notes.size && s.notes[i].start < t1) {
            val n = s.notes[i]
            if (isHeld(g, n.start, n.end)) {
                val x0 = xFor(r, n.start) + boxWidth(n.fret) / 2 + 1 * dp
                val x1 = xFor(r, n.end)
                val y = courseY(r, n.course)
                if (x1 > x0 + 4 * dp) canvas.drawLine(x0, y, x1, y, if (i == lit) holdLitPaint else holdPaint)
            }
            i++
        }
        i = firstNote
        while (i < s.notes.size && s.notes[i].start < t1) {
            val n = s.notes[i]
            val x = xFor(r, n.start)
            val y = courseY(r, n.course)
            val half = boxWidth(n.fret) / 2
            val on = i == lit
            canvas.drawRoundRect(x - half, y - boxHeight / 2, x + half, y + boxHeight / 2, 3 * dp, 3 * dp, if (on) litPaint else boxPaint)
            canvas.drawRoundRect(x - half, y - boxHeight / 2, x + half, y + boxHeight / 2, 3 * dp, 3 * dp, if (on) litEdge else boxEdge)
            canvas.drawText(fretText(n.fret), x, y + 4.6f * dp, numPaint)
            i++
        }
    }

    private fun drawCursor(canvas: Canvas, x: Float, y0: Float, y1: Float) {
        canvas.drawLine(x, y0, x, y1, cursorPaint)
        path.reset()
        path.moveTo(x - 5 * dp, y0 - 5 * dp)
        path.lineTo(x + 5 * dp, y0 - 5 * dp)
        path.lineTo(x, y0 + 1 * dp)
        path.close()
        canvas.drawPath(path, cursorHead)
    }

    /** Long enough to be held (or played tremolo): most of a beat or more. */
    private fun isHeld(g: Int, start: Double, end: Double): Boolean {
        var k = g
        while (k < g + BEATS_PER_ROW - 1 && grid[k + 1] <= start) k++
        return end - start >= 0.8 * (grid[k + 1] - grid[k])
    }

    private fun boxWidth(fret: Int) = max(numPaint.measureText(fretText(fret)) + 6 * dp, 14 * dp)

    // --- following the song ---

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.setOnScrollChangeListener { _, _, _, _, _ ->
            if (SystemClock.uptimeMillis() > autoScrollUntil) lastUserScroll = SystemClock.uptimeMillis()
            invalidate() // the rows drawn depend on the scroll position
        }
    }

    override fun onDetachedFromWindow() {
        (parent as? View)?.setOnScrollChangeListener(null)
        super.onDetachedFromWindow()
    }

    /** Scrolls to the row playing now, even if the reader has just scrolled. */
    fun showNow() {
        lastUserScroll = 0L
        followedRow = -1
        lastTarget = -1
        post { follow(force = true) }
    }

    private fun follow(force: Boolean) {
        val scroller = parent as? ScrollView ?: return
        if (!isShown || rowCount == 0 || scroller.height == 0) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastUserScroll < FOLLOW_PAUSE_MS) return
        val r = rowAt(time)
        val viewTop = scroller.scrollY - top
        val viewBottom = viewTop + scroller.height
        val rowBottom = rowTop[r] + rowHeight[r]
        val nextBottom = if (r + 1 < rowCount) rowBottom + rowHeight[r + 1] * 0.5f else rowBottom
        val visible = rowTop[r] >= viewTop && nextBottom <= viewBottom
        if (visible && r == followedRow) return
        followedRow = r
        if (visible) return
        // The row playing near the top, with a glimpse of the one before.
        val target = (top + rowTop[r] - min(rowHeight[r] * 0.35f, scroller.height * 0.15f)).toInt().coerceAtLeast(0)
        if (target == lastTarget) return
        lastTarget = target
        autoScrollUntil = now + 700
        scroller.smoothScrollTo(0, target)
    }

    // --- touch ---

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lastUserScroll = SystemClock.uptimeMillis() // the reader is handling the sheet
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; tapping = song != null }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop) tapping = false
            MotionEvent.ACTION_UP -> { if (tapping) tap(e.x, e.y); tapping = false }
            MotionEvent.ACTION_CANCEL -> tapping = false
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val s = song ?: return
        val r = rowAtY(y)
        if (r < 0) return
        if (rowGrid[r] >= 0) {
            // The nearest fret number within reach of the finger.
            var best = -1
            var bestD = 20 * dp
            val t0 = grid[rowGrid[r]]
            val t1 = grid[rowGrid[r] + BEATS_PER_ROW]
            var i = s.nextAfter(t0 - 1e-9)
            while (i < s.notes.size && s.notes[i].start < t1) {
                val n = s.notes[i]
                val dx = abs(xFor(r, n.start) - x)
                val dy = abs(courseY(r, n.course) - y)
                val d = max(dx, dy * 1.3f)
                if (d < bestD) { bestD = d; best = i }
                i++
            }
            if (best >= 0) { onNoteTap?.invoke(best); return }
        }
        onSeek?.invoke(timeAt(r, x).coerceAtLeast(0.0))
    }

    companion object {
        const val BEATS_PER_ROW = 8
        const val SECONDS_PER_ROW = 4.0
        private const val FOLLOW_PAUSE_MS = 4000L

        private const val PAPER = 0xFFF7F0E1.toInt()
        private const val INK = 0xFF2A2420.toInt()
        private const val GREEK_RED = 0xFFB3202E.toInt()
        private const val GOLD = 0xFFF5C542.toInt()
        private const val GOLD_DARK = 0xFFC98A00.toInt()

        private fun ink(alpha: Int) = (INK and 0x00FFFFFF) or (alpha shl 24)
        private val FRETS = Array(40) { it.toString() }
        private fun fretText(fret: Int) = FRETS.getOrElse(fret) { "?" }

        private fun clock(t: Double): String {
            val s = max(0, t.toInt())
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
