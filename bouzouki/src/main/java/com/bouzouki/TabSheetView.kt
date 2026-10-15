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
import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.Beats
import com.bouzouki.core.song.SongAnalysis
import com.bouzouki.core.song.SongNote
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The whole song as printed bouzouki tablature, for a vertical [ScrollView]: rows of two bars
 * (8 beats, or 4 s with a tick every second when the beats aren't known), one line per course
 * with the highest on top, fret numbers where the notes start and a bar along the ones that
 * are held. Where the notes are too close together for that, a row holds half as much, so
 * every number stays at its time and none run into each other.
 * Articulations are marked as in printed tab: a slur with "h" or "p" over a hammer-on or
 * pull-off, a slanted line ("sl.") into a slide, slashes across a tremolo note's bar, and a
 * note held over a bar line (or into the next row) shows its fret again in brackets, tied.
 * A gold cursor follows the song, the note playing is lit, and the sheet scrolls along unless
 * the reader has just scrolled it. Tap a note to play it, or anywhere else to go there.
 * Stretches without bouzouki fold into one short row. A pasted tab's notes that weren't heard
 * in the recording show faint, and those heard at another pitch marked ([unheard], [offPitch]).
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
    /**
     * Notes of the song (by index) drawn faint, in a dashed box: a pasted tab's notes that weren't heard in
     * the recording, placed where the notes around them put them. Set before [song].
     */
    var unheard: Set<Int> = emptySet()
        set(v) { if (v != field) { field = v; relayout() } }
    /** Notes of the song (by index) marked "?": a pasted tab's notes heard only at another pitch (a wrong fret?). Set before [song]. */
    var offPitch: Set<Int> = emptySet()
        set(v) { if (v != field) { field = v; relayout() } }
    /** Called with a note's index when it's tapped. */
    var onNoteTap: ((Int) -> Unit)? = null
    /** Called with a time in seconds when the sheet is tapped away from the notes. */
    var onSeek: ((Double) -> Unit)? = null

    private val dp = resources.displayMetrics.density

    // Layout, rebuilt when the song, the beats or the width change. grid[] holds the beat times
    // (extended past both ends of the song); a row of music covers grid[g]..grid[g + steps].
    private var grid = DoubleArray(0)
    private var hasBeats = false
    private var rowCount = 0
    private var rowGrid = IntArray(0)        // first grid index, or -1 for a folded stretch
    private var rowSteps = IntArray(0)       // grid steps in the row: BEATS_PER_ROW, or half where it's crowded
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
    private var legend = ""                  // the articulation marks the song uses, explained
    private var headerHeight = 0f
    private var totalHeight = 0f
    private var layoutWidth = -1
    private var boxX = FloatArray(0)         // where each note's fret number is drawn (x of its centre)
    private val boxWidths = FloatArray(40)   // cached box width per fret number (0 = not measured yet)
    private val tiedWidths = FloatArray(40)  // and of a tied fret number, "(5)"

    // Geometry (px).
    private val margin = 8 * dp
    private val bandHeight = 24 * dp          // above the staff: the row's time, and room for the top course's slurs
    private val courseGap = 22 * dp
    private val rowPadBottom = 18 * dp
    private val foldHeight = 34 * dp
    private val boxHeight = 16 * dp
    private val boxGap = 2 * dp               // at least this between two numbers on a course
    private val maxDrift = 4 * dp             // numbers moved further than this from their time: halve the row
    private var labelWidth = 0f
    private var staffLeft = 0f                // where the staff starts, set with the rows
    private var left = 0f                     // where its notes start and end
    private var right = 0f

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
    private var apexX = 0f                    // the middle of the last arc drawn, for its letter
    private var apexY = 0f
    private var rowIsCurrent = false          // the row being drawn is tinted (playing), and darker up to the cursor
    private var rowCursorX = 0f
    private var playedTop = 0f                // where that darker tint is, top to bottom
    private var playedBottom = 0f

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
    // Articulation marks.
    private val slurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink(0xD0); style = Paint.Style.STROKE; strokeWidth = 1.3f * dp; strokeCap = Paint.Cap.ROUND
    }
    private val slurLitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GOLD_DARK; style = Paint.Style.STROKE; strokeWidth = 1.8f * dp; strokeCap = Paint.Cap.ROUND
    }
    private val tiePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink(0x90); style = Paint.Style.STROKE; strokeWidth = 1.1f * dp; strokeCap = Paint.Cap.ROUND
    }
    private val slidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink(0xE0); strokeWidth = 1.6f * dp; strokeCap = Paint.Cap.ROUND }
    private val slideLitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD_DARK; strokeWidth = 2f * dp; strokeCap = Paint.Cap.ROUND }
    private val tremoloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; strokeWidth = 1.5f * dp; strokeCap = Paint.Cap.ROUND }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEK_RED; textSize = 10.5f * dp; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    }
    private val tiedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink(0xA8); textSize = 12 * dp; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink(0x80); textSize = 13 * dp; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val ghostEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink(0x55); style = Paint.Style.STROKE; strokeWidth = 1f * dp
        pathEffect = DashPathEffect(floatArrayOf(2.5f * dp, 2 * dp), 0f)
    }
    private val legendPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink(0x99); textSize = 10.5f * dp; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val hWidth = markPaint.measureText(HAMMER_MARK)
    private val pWidth = markPaint.measureText(PULL_MARK)
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
        lastUserScroll = 0L
        autoScrollUntil = SystemClock.uptimeMillis() + 500 // the scroll view settling isn't the reader
        if (s == null) {
            rowCount = 0
            totalHeight = 0f
            followedRow = -1
            lastTarget = -1
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
        title = s.title.ifBlank { "Bouzouki line" }
        legend = legendFor(s)
        fitTitle()
        val bpm = Beats.period(beats)?.let { (60.0 / it).roundToInt() }
        subtitle = (if (bpm != null && hasBeats) "♩ ≈ $bpm  ·  " else "") +
            courseNames.joinToString(" ") + "  ·  " + s.instrument.greekName
        buildRows(s, width)
        requestLayout()
        invalidate()
    }

    /** The rows for a view [w] px wide: which stretch each one shows, where, and the fret numbers in them. */
    private fun buildRows(s: SongAnalysis, w: Int) {
        layoutWidth = w
        followedRow = -1
        lastTarget = -1
        staffLeft = margin + labelWidth + 6 * dp
        left = staffLeft + 12 * dp
        right = w - margin - 6 * dp
        boxX = FloatArray(s.notes.size)

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

        // A row whose fret numbers would have to move off their time to fit holds one bar instead.
        val steps = ArrayList<Int>()
        val half = BEATS_PER_ROW / 2
        var i = 0
        while (i < g.size) {
            val gi = g[i]
            if (gi >= 0 && right - left > 0 && placeBoxes(s, gi, BEATS_PER_ROW) > maxDrift) {
                placeBoxes(s, gi, half)
                placeBoxes(s, gi + half, half)
                g.add(i + 1, gi + half); t0.add(i + 1, grid[gi + half]); t1.add(i + 1, t1[i]); t1[i] = grid[gi + half]
                steps += half; steps += half
                i += 2
                continue
            }
            steps += if (gi >= 0) BEATS_PER_ROW else 0
            i++
        }

        rowCount = g.size
        rowGrid = g.toIntArray()
        rowSteps = steps.toIntArray()
        rowT0 = t0.toDoubleArray()
        rowT1 = t1.toDoubleArray()
        rowTop = FloatArray(rowCount)
        rowHeight = FloatArray(rowCount)
        rowLabel = Array(rowCount) { r ->
            if (rowGrid[r] >= 0) clock(rowT0[r])
            else (if (r == 0) "Intro" else "No bouzouki") + " · " + clock(rowT0[r]) + " – " + clock(rowT1[r])
        }
        headerHeight = if (legend.isEmpty()) 58 * dp else 72 * dp
        var y = headerHeight
        val staff = (courses - 1) * courseGap
        for (r in 0 until rowCount) {
            rowTop[r] = y
            rowHeight[r] = if (rowGrid[r] >= 0) bandHeight + staff + rowPadBottom + 6 * dp else foldHeight
            y += rowHeight[r]
        }
        totalHeight = y + 24 * dp
    }

    /**
     * Places the fret numbers of the notes starting in grid[g]..grid[g + steps] into [boxX]: each
     * at its time, unless that would touch the one before on its course, then just after it (and
     * back from the row's end). Returns how far the furthest one had to move, in px.
     */
    private fun placeBoxes(s: SongAnalysis, g: Int, steps: Int): Float {
        val notes = s.notes
        val first = firstFrom(s, grid[g])
        val end = firstFrom(s, grid[g + steps])
        var drift = 0f
        for (c in 0 until courses) {
            var prevRight = Float.NEGATIVE_INFINITY
            for (i in first until end) {
                val n = notes[i]
                if (n.course != c) continue
                val half = boxWidth(n.fret) / 2
                boxX[i] = max(xAt(g, steps, n.start), prevRight + boxGap + half)
                prevRight = boxX[i] + half
            }
            var nextLeft = Float.NaN
            for (i in end - 1 downTo first) {
                val n = notes[i]
                if (n.course != c) continue
                val half = boxWidth(n.fret) / 2
                boxX[i] = min(boxX[i], if (nextLeft.isNaN()) right else nextLeft - boxGap - half)
                nextLeft = boxX[i] - half
                drift = max(drift, abs(boxX[i] - xAt(g, steps, n.start)))
            }
        }
        return drift
    }

    /** Beat times covering [from]..[to], from the beats if there are enough, else every 0.5 s. */
    private fun buildGrid(from: Double, to: Double): DoubleArray {
        val b = beats
        val p = Beats.period(b)
        val out = ArrayList<Double>()
        hasBeats = b.size >= BEATS_PER_ROW && p != null && p in 0.2..2.0
        if (hasBeats && p != null) {
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
        val step = if (hasBeats && p != null) p else SECONDS_PER_ROW / BEATS_PER_ROW
        while (out.last() < to || (out.size - 1) % BEATS_PER_ROW != 0) out += out.last() + step
        return out.toDoubleArray()
    }

    /** The articulation marks [s] uses, explained in one line (empty when every note is simply picked). */
    private fun legendFor(s: SongAnalysis): String {
        val used = BooleanArray(Articulation.entries.size)
        for (i in s.notes.indices) {
            if (s.notes[i].articulation == Articulation.TREMOLO) used[Articulation.TREMOLO.ordinal] = true
            s.legatoInto(i)?.let { used[it.ordinal] = true }
        }
        val parts = ArrayList<String>()
        if (used[Articulation.HAMMER.ordinal]) parts += "$HAMMER_MARK hammer-on"
        if (used[Articulation.PULL.ordinal]) parts += "$PULL_MARK pull-off"
        if (used[Articulation.SLIDE.ordinal]) parts += "$SLIDE_MARK slide"
        if (used[Articulation.TREMOLO.ordinal]) parts += "/// tremolo"
        if (unheard.isNotEmpty()) parts += "faint: in the tab, not heard"
        if (offPitch.isNotEmpty()) parts += "$OFF_PITCH_MARK heard at another pitch"
        return parts.joinToString("    ")
    }

    private fun hasNotes(s: SongAnalysis, t0: Double, t1: Double): Boolean {
        val i = firstFrom(s, t0)
        if (i < firstFrom(s, t1)) return true
        return i > 0 && s.notes[i - 1].end > t0 + 0.05
    }

    /**
     * The first note of a row starting at [t]: a row from t0 to t1 holds the notes firstFrom(t0) until
     * firstFrom(t1), so a note on the line between two rows (within rounding) is in the later one only.
     */
    private fun firstFrom(s: SongAnalysis, t: Double) = s.nextAfter(t - 1e-9)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val s = song
        if (s != null && w != layoutWidth) buildRows(s, w) // which rows are halved depends on the width
        val h = if (s == null) (200 * dp).toInt() else ceil(totalHeight).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec)) // a ScrollView's fillViewport asks for exactly its height
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitTitle()
        followedRow = -1
        lastTarget = -1
    }

    private fun courseY(row: Int, course: Int) = rowTop[row] + bandHeight + 6 * dp + (courses - 1 - course) * courseGap

    /** x of time [t] on a row of music. */
    private fun xFor(row: Int, t: Double): Float = xAt(rowGrid[row], rowSteps[row], t)

    /** x of time [t] on a row of [steps] beats from grid[g]: evenly spaced beats, time running evenly within each. */
    private fun xAt(g: Int, steps: Int, t: Double): Float {
        val beatWidth = (right - left) / steps
        if (t <= grid[g]) return left
        if (t >= grid[g + steps]) return right
        var k = 0
        while (k < steps - 1 && grid[g + k + 1] <= t) k++
        val frac = (t - grid[g + k]) / (grid[g + k + 1] - grid[g + k])
        return left + ((k + frac) * beatWidth).toFloat()
    }

    private fun timeAt(row: Int, x: Float): Double {
        val g = rowGrid[row]
        if (g < 0) return rowT0[row] + ((x - left) / (right - left)).coerceIn(0f, 1f) * (rowT1[row] - rowT0[row])
        val steps = rowSteps[row]
        val pos = ((x - left) / ((right - left) / steps)).coerceIn(0f, steps - 0.001f)
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
        // Only the rows on screen (plus a little slack): the scroll listener redraws as it scrolls.
        val scroller = parent as? View
        val viewTop = if (scroller != null) scroller.scrollY - top else 0
        val viewHeight = scroller?.height ?: height
        val slack = (120 * dp).toInt()
        val from = viewTop - slack
        val to = viewTop + viewHeight + slack

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

    private var legendShown = ""

    private fun fitTitle() {
        titleShown = if (width > 0) TextUtils.ellipsize(title, titlePaint, width - 2 * margin, TextUtils.TruncateAt.END).toString() else title
        legendShown = if (width > 0) TextUtils.ellipsize(legend, legendPaint, width - 2 * margin, TextUtils.TruncateAt.END).toString() else legend
    }

    private fun drawHeader(canvas: Canvas) {
        canvas.drawText(titleShown, margin, 24 * dp, titlePaint)
        canvas.drawText(subtitle, margin, 42 * dp, subtitlePaint)
        if (legendShown.isNotEmpty()) canvas.drawText(legendShown, margin, 57 * dp, legendPaint)
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
        val steps = rowSteps[r]
        val top = rowTop[r]
        val yTop = courseY(r, courses - 1)
        val yBottom = courseY(r, 0)
        val beatWidth = (right - left) / steps
        val cursorX = if (current) xFor(r, time) else 0f
        rowIsCurrent = current
        rowCursorX = cursorX
        playedTop = yTop - 8 * dp
        playedBottom = yBottom + 8 * dp
        if (current) {
            canvas.drawRect(0f, top, width.toFloat(), top + rowHeight[r], rowTint)
            canvas.drawRect(staffLeft, playedTop, cursorX, playedBottom, playedTint)
        }
        canvas.drawText(rowLabel[r], margin, top + 10.5f * dp, timePaint)

        // Staff: one line per course, the highest on top, its name in front.
        for (c in 0 until courses) {
            val y = courseY(r, c)
            canvas.drawLine(staffLeft, y, right, y, staffPaint)
            canvas.drawText(courseNames[c], margin, y + 4.2f * dp, coursePaint)
        }
        // Beats, and a bar line every four (rows start on a bar); without beats, a tick every
        // second, all alike, since bars would only be made up.
        for (k in 0..steps) {
            val x = if (k == 0) staffLeft else left + k * beatWidth
            val paint = when {
                hasBeats -> if (k % 4 == 0) barPaint else beatPaint
                k == 0 || k == steps -> staffPaint
                k % 2 == 0 -> beatPaint
                else -> null
            }
            if (paint != null) canvas.drawLine(x, yTop, x, yBottom, paint)
        }
        // The cursor goes under the notes, so the one playing stays readable.
        if (current) drawCursor(canvas, cursorX, yTop - 10 * dp, yBottom + 10 * dp)

        val t0 = grid[g]
        val t1 = grid[g + steps]
        val notes = s.notes
        val firstNote = firstFrom(s, t0)
        val end = firstFrom(s, t1) // notes firstNote until end start in this row

        // Notes held over from the row before (the line is nearly always one note at a time).
        var held = firstNote - 1
        while (held >= 0 && held >= firstNote - 4) {
            if (carriesOver(s, held, r - 1)) drawHeldOver(canvas, s, r, held, end, held == lit)
            held--
        }
        // Bars of held and tremolo notes first, then the fret numbers over them, then the marks.
        for (i in firstNote until end) {
            val n = notes[i]
            if (hasBar(g, steps, n)) {
                val x0 = boxX[i] + boxWidth(n.fret) / 2 + 1 * dp
                val x1 = xFor(r, n.end)
                val y = courseY(r, n.course)
                if (x1 > x0 + 4 * dp) canvas.drawLine(x0, y, x1, y, if (i == lit) holdLitPaint else holdPaint)
            }
        }
        for (i in firstNote until end) {
            val n = notes[i]
            val x = boxX[i]
            val y = courseY(r, n.course)
            val half = boxWidth(n.fret) / 2
            val on = i == lit
            val faint = !on && i in unheard // a tab note not heard: faint, in a dashed box
            canvas.drawRoundRect(x - half, y - boxHeight / 2, x + half, y + boxHeight / 2, 3 * dp, 3 * dp, if (on) litPaint else boxPaint)
            canvas.drawRoundRect(x - half, y - boxHeight / 2, x + half, y + boxHeight / 2, 3 * dp, 3 * dp,
                if (on) litEdge else if (faint) ghostEdge else boxEdge)
            canvas.drawText(fretText(n.fret), x, y + 4.6f * dp, if (faint) ghostPaint else numPaint)
            if (i in offPitch) canvas.drawText(OFF_PITCH_MARK, x + half + 1 * dp, y - boxHeight / 2 + 2 * dp, markPaint)
        }
        for (i in firstNote until end) {
            val n = notes[i]
            val slashed = hasBar(g, steps, n) && drawTies(canvas, s, r, i, boxX[i], boxWidth(n.fret) / 2, end)
            when (s.legatoInto(i)) {
                Articulation.HAMMER -> drawSlur(canvas, s, r, i, firstNote, hammer = true, lit = i == lit)
                Articulation.PULL -> drawSlur(canvas, s, r, i, firstNote, hammer = false, lit = i == lit)
                Articulation.SLIDE -> drawSlide(canvas, s, r, i, firstNote, i == lit)
                else -> {}
            }
            if (n.articulation == Articulation.TREMOLO && !slashed) smallTremolo(canvas, boxX[i], courseY(r, n.course))
        }
        // A hammer-on or pull-off at the start of the next row: its slur leaves from here (unless
        // the note is tied over to its fret in brackets there, which the slur then leaves from).
        val into = s.legatoInto(end)
        if (end > firstNote && into != null && into != Articulation.SLIDE && heldBracketX(s, r + 1, end - 1).isNaN()) {
            val n = notes[end - 1]
            val y = courseY(r, n.course) - boxHeight / 2 - 1.5f * dp
            arc(canvas, boxX[end - 1], y, right + 5 * dp, y, up = true, paint = if (end == lit) slurLitPaint else slurPaint)
        }
    }

    /** Whether note [n] (starting in the row from grid[g]) gets a bar for its length: held long enough, or tremolo. */
    private fun hasBar(g: Int, steps: Int, n: SongNote) = n.articulation == Articulation.TREMOLO || isHeld(g, steps, n.start, n.end)

    /**
     * Whether note [i] is shown going on from row [r] into the next: held (it has a bar) and lasting
     * past the row's end. Both rows go by this, so a tie leaving one row always arrives in the next.
     */
    private fun carriesOver(s: SongAnalysis, i: Int, r: Int): Boolean {
        if (r < 0 || r + 1 >= rowCount || rowGrid[r] < 0 || rowGrid[r + 1] < 0) return false
        val n = s.notes[i]
        if (n.end <= rowT1[r] + 0.05) return false
        val from = rowOfNote(n.start)
        return rowGrid[from] >= 0 && hasBar(rowGrid[from], rowSteps[from], n)
    }

    /** The row note starting at [start] is drawn in (as [firstFrom] shares them out). */
    private fun rowOfNote(start: Double): Int {
        var lo = 0
        var hi = rowCount - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (rowT0[mid] - 1e-9 < start) lo = mid else hi = mid - 1 }
        return lo
    }

    /**
     * Note [i], held over into row [r] from the row before: its bar, and its fret again in brackets,
     * tied over; or, when it ends too soon for the brackets (or the next note is in their way), a tie
     * onto its bar. [end] is the first note after the row.
     */
    private fun drawHeldOver(canvas: Canvas, s: SongAnalysis, r: Int, i: Int, end: Int, on: Boolean) {
        val n = s.notes[i]
        val y = courseY(r, n.course)
        val x1 = xFor(r, n.end)
        val tx = heldBracketX(s, r, i)
        val slashed = if (!tx.isNaN()) {
            val half = tiedWidth(n.fret) / 2
            canvas.drawLine(tx + half, y, x1, y, if (on) holdLitPaint else holdPaint)
            tie(canvas, staffLeft - 5 * dp, tx, y)
            drawTied(canvas, tx, y, n.fret)
            drawTies(canvas, s, r, i, tx, half, end)
        } else {
            canvas.drawLine(staffLeft, y, x1, y, if (on) holdLitPaint else holdPaint)
            tie(canvas, staffLeft - 5 * dp, max(x1, staffLeft + 10 * dp), y)
            drawTies(canvas, s, r, i, staffLeft + 6 * dp, 0f, end)
        }
        // A tremolo still going on, with no room for its slashes on the bar: small ones over its
        // start, unless a slur to the next note leaves from there.
        val next = s.legatoInto(i + 1)
        if (n.articulation == Articulation.TREMOLO && !slashed && next != Articulation.HAMMER && next != Articulation.PULL) {
            smallTremolo(canvas, if (tx.isNaN()) staffLeft + 8 * dp else tx, y)
        }
    }

    /**
     * Where note [i], held over from the row before into row [r], shows its fret again in brackets
     * (x of their centre); NaN when it isn't held over, ends too soon for them, or the next note on
     * its line is in their way.
     */
    private fun heldBracketX(s: SongAnalysis, r: Int, i: Int): Float {
        if (i < 0 || i >= s.notes.size || !carriesOver(s, i, r - 1)) return Float.NaN
        val n = s.notes[i]
        val half = tiedWidth(n.fret) / 2
        val tx = staffLeft + 3 * dp + half
        if (xFor(r, n.end) < tx + half + 4 * dp) return Float.NaN
        val end = firstFrom(s, rowT1[r])
        var next = firstFrom(s, rowT0[r])
        while (next < end && s.notes[next].course != n.course) next++
        return if (next >= end || boxX[next] - boxWidth(s.notes[next].fret) / 2 > tx + half + boxGap) tx else Float.NaN
    }

    /**
     * Ties for note [i], whose fret number (or bracketed fret, held over), [fromHalf] wide on each side,
     * is at [fromX] on row [r]: at each bar line it is held over, its fret again in brackets, tied to the
     * one before, and a tie running off the row if it goes on into the next. A tremolo note has its
     * slashes on each stretch of its bar long enough for them. [end] is the first note after the row.
     * Returns whether any tremolo slashes were drawn.
     */
    private fun drawTies(canvas: Canvas, s: SongAnalysis, r: Int, i: Int, fromX: Float, fromHalf: Float, end: Int): Boolean {
        val n = s.notes[i]
        val g = rowGrid[r]
        val steps = rowSteps[r]
        val y = courseY(r, n.course)
        val x1 = xFor(r, n.end)
        val tied = tiedWidth(n.fret)
        val tremolo = n.articulation == Articulation.TREMOLO
        val next = nextOnCourse(s, i, end)
        // The slashes stop short of the next note on the line, and of the slanted line into it if it's slid to.
        val stop = if (next < 0) x1 else min(x1, boxX[next] - boxWidth(s.notes[next].fret) / 2 -
            if (s.legatoInto(next) == Articulation.SLIDE) 15 * dp else 2 * dp)
        var slashed = false
        var half = fromHalf
        var x = fromX
        if (hasBeats) {
            var k = BARS_EVERY
            while (k < steps) {
                val bar = grid[g + k]
                val xb = left + k * ((right - left) / steps)
                k += BARS_EVERY
                if (x + half >= xb - 1 * dp || n.end < bar + 0.05) continue // not held over this bar line
                val tx = xb + 2 * dp + tied / 2
                if (tx + tied / 2 + 2 * dp > x1) continue // it ends too soon after the bar line for the brackets
                if (next >= 0 && boxX[next] - boxWidth(s.notes[next].fret) / 2 < tx + tied / 2 + boxGap) continue
                if (tremolo && tremoloSlashes(canvas, x + half + 1 * dp, tx - tied / 2 - 1 * dp, y)) slashed = true
                tie(canvas, x, tx, y)
                drawTied(canvas, tx, y, n.fret)
                x = tx
                half = tied / 2
            }
        }
        if (tremolo && tremoloSlashes(canvas, x + half + 1 * dp, stop, y)) slashed = true
        if (carriesOver(s, i, r)) tie(canvas, x, right + 5 * dp, y) // on into the next row
        return slashed
    }

    /** The next note after [i] on its course among the notes before [end], or -1. */
    private fun nextOnCourse(s: SongAnalysis, i: Int, end: Int): Int {
        var j = i + 1
        while (j < end) { if (s.notes[j].course == s.notes[i].course) return j; j++ }
        return -1
    }

    /** A tie between two fret numbers at [x0] and [x1] on a line at [y]: a flat arc under them. */
    private fun tie(canvas: Canvas, x0: Float, x1: Float, y: Float) {
        val yb = y + boxHeight / 2 + 1.5f * dp
        arc(canvas, x0 + 2 * dp, yb, x1 - 2 * dp, yb, up = false, paint = tiePaint)
    }

    /** A held-over fret in brackets, "(5)", at [x] on the line at [y], over the note's bar. */
    private fun drawTied(canvas: Canvas, x: Float, y: Float, fret: Int) {
        val half = tiedWidth(fret) / 2
        clear(canvas, x - half, y - boxHeight / 2 + 2 * dp, x + half, y + boxHeight / 2 - 2 * dp)
        canvas.drawText(tiedText(fret), x, y + 4.3f * dp, tiedPaint)
    }

    /** Paints over the lines in a small rectangle of a music row with the row's own background, for a mark on top. */
    private fun clear(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        canvas.drawRect(l, t, r, b, paperPaint)
        if (!rowIsCurrent) return
        canvas.drawRect(l, t, r, b, rowTint)
        val pl = max(l, staffLeft)
        val pr = min(r, rowCursorX)
        val pt = max(t, playedTop)
        val pb = min(b, playedBottom)
        if (pl < pr && pt < pb) canvas.drawRect(pl, pt, pr, pb, playedTint)
    }

    /**
     * The slur of a hammer-on ([hammer]) or pull-off into note [i], from the note before, with its
     * letter. When that note is in the row above, the slur leaves from its fret in brackets if it's
     * held over, else from the row's start, kept low so its letter stays clear of the row's time.
     */
    private fun drawSlur(canvas: Canvas, s: SongAnalysis, r: Int, i: Int, firstNote: Int, hammer: Boolean, lit: Boolean) {
        val n = s.notes[i]
        val y = courseY(r, n.course) - boxHeight / 2 - 1.5f * dp
        val bracket = if (i - 1 < firstNote) heldBracketX(s, r, i - 1) else Float.NaN
        val fromStart = i - 1 < firstNote && bracket.isNaN()
        val x0 = when {
            i - 1 >= firstNote -> boxX[i - 1]
            fromStart -> max(margin, staffLeft - 10 * dp)
            else -> bracket
        }
        arc(canvas, x0, y, boxX[i], y, up = true, paint = if (lit) slurLitPaint else slurPaint, maxHeight = if (fromStart) 2.5f * dp else 6 * dp)
        // The letter sits in a gap at the top of the slur.
        val w = (if (hammer) hWidth else pWidth) / 2 + 1.2f * dp
        clear(canvas, apexX - w, apexY - 4.6f * dp, apexX + w, apexY + 3.6f * dp)
        canvas.drawText(if (hammer) HAMMER_MARK else PULL_MARK, apexX, apexY + 3.2f * dp, markPaint)
    }

    /** The slanted line of a slide into note [i]: up or down as the slide goes, just before its fret number, marked "sl.". */
    private fun drawSlide(canvas: Canvas, s: SongAnalysis, r: Int, i: Int, firstNote: Int, lit: Boolean) {
        val n = s.notes[i]
        val p = s.notes[i - 1]
        val y = courseY(r, n.course)
        val to = boxX[i] - boxWidth(n.fret) / 2 - 1.5f * dp
        val bracket = if (i - 1 < firstNote) heldBracketX(s, r, i - 1) else Float.NaN
        val from = when {
            i - 1 >= firstNote -> boxX[i - 1] + boxWidth(p.fret) / 2 + 1.5f * dp
            !bracket.isNaN() -> bracket + tiedWidth(p.fret) / 2 + 1.5f * dp
            else -> staffLeft + 2 * dp
        }
        val gap = to - from
        val len = gap.coerceIn(7 * dp, 13 * dp)
        val x1 = if (gap < len) to + (len - gap) / 2 else to // too close: centred on the gap, over the boxes' edges
        val x0 = x1 - len
        val rise = if (n.midi >= p.midi) 4.5f * dp else -4.5f * dp
        canvas.drawLine(x0, y + rise, x1, y - rise, if (lit) slideLitPaint else slidePaint)
        canvas.drawText(SLIDE_MARK, (x0 + x1) / 2, y - boxHeight / 2 - 2.5f * dp, markPaint)
    }

    /** Tremolo slashes across a stretch of a note's bar from [x0] to [x1] on the line at [y], if it is long enough for them. */
    private fun tremoloSlashes(canvas: Canvas, x0: Float, x1: Float, y: Float): Boolean {
        if (x1 - x0 < 18 * dp) return false
        for (k in 0..2) {
            val x = x0 + 6 * dp + k * 4 * dp
            canvas.drawLine(x - 2.2f * dp, y + 4.5f * dp, x + 2.2f * dp, y - 4.5f * dp, tremoloPaint)
        }
        return true
    }

    /** Small tremolo slashes over a fret number at [cx] on the line at [y], when no stretch of its bar is long enough for them. */
    private fun smallTremolo(canvas: Canvas, cx: Float, y: Float) {
        val yt = y - boxHeight / 2 - 5 * dp
        for (k in -1..1) {
            val x = cx + k * 3.5f * dp
            canvas.drawLine(x - 1.6f * dp, yt + 3 * dp, x + 1.6f * dp, yt - 3 * dp, tremoloPaint)
        }
    }

    /**
     * A slur ([up]) or tie from ([x0], [y0]) to ([x1], [y1]): a quadratic arc, higher for longer
     * spans (within a few dp, at most [maxHeight] for a slur). Leaves its middle in [apexX], [apexY].
     */
    private fun arc(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, up: Boolean, paint: Paint, maxHeight: Float = 6 * dp) {
        val h = (abs(x1 - x0) * if (up) 0.2f else 0.12f).coerceIn(2.5f * dp, if (up) maxHeight else 5 * dp)
        val cx = (x0 + x1) / 2
        val cy = if (up) min(y0, y1) - 2 * h else max(y0, y1) + 2 * h
        path.reset()
        path.moveTo(x0, y0)
        path.quadTo(cx, cy, x1, y1)
        canvas.drawPath(path, paint)
        apexX = cx
        apexY = (y0 + 2 * cy + y1) / 4
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
    private fun isHeld(g: Int, steps: Int, start: Double, end: Double): Boolean {
        var k = g
        while (k < g + steps - 1 && grid[k + 1] <= start) k++
        return end - start >= 0.8 * (grid[k + 1] - grid[k])
    }

    /** Width of a fret number's box, measured once per fret (drawing asks for it at every frame). */
    private fun boxWidth(fret: Int): Float {
        if (fret !in boxWidths.indices) return max(numPaint.measureText(fretText(fret)) + 6 * dp, 14 * dp)
        if (boxWidths[fret] == 0f) boxWidths[fret] = max(numPaint.measureText(fretText(fret)) + 6 * dp, 14 * dp)
        return boxWidths[fret]
    }

    /** Width of a held-over fret in brackets, measured once per fret. */
    private fun tiedWidth(fret: Int): Float {
        if (fret !in tiedWidths.indices) return tiedPaint.measureText(tiedText(fret)) + 2 * dp
        if (tiedWidths[fret] == 0f) tiedWidths[fret] = tiedPaint.measureText(tiedText(fret)) + 2 * dp
        return tiedWidths[fret]
    }

    // --- following the song ---

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.setOnScrollChangeListener { _, _, _, _, _ ->
            if (SystemClock.uptimeMillis() > autoScrollUntil) userScrolled()
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
        userScrolled() // the reader is handling the sheet
        return super.dispatchTouchEvent(event)
    }

    /** The reader moved the sheet: following pauses, then brings the playing row back even if it's the same one. */
    private fun userScrolled() {
        lastUserScroll = SystemClock.uptimeMillis()
        followedRow = -1
        lastTarget = -1
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
            val t1 = grid[rowGrid[r] + rowSteps[r]]
            var i = firstFrom(s, t0)
            val end = firstFrom(s, t1)
            while (i < end) {
                val n = s.notes[i]
                val dx = abs(boxX[i] - x)
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
        /** Beats from one bar line to the next. */
        private const val BARS_EVERY = 4
        private const val HAMMER_MARK = "h"
        private const val PULL_MARK = "p"
        private const val SLIDE_MARK = "sl."
        /** By a tab note heard only at another pitch. */
        private const val OFF_PITCH_MARK = "?"

        private const val PAPER = 0xFFF7F0E1.toInt()
        private const val INK = 0xFF2A2420.toInt()
        private const val GREEK_RED = 0xFFB3202E.toInt()
        private const val GOLD = 0xFFF5C542.toInt()
        private const val GOLD_DARK = 0xFFC98A00.toInt()

        private fun ink(alpha: Int) = (INK and 0x00FFFFFF) or (alpha shl 24)
        private val FRETS = Array(40) { it.toString() }
        private val TIED = Array(40) { "($it)" }
        private fun fretText(fret: Int) = FRETS.getOrElse(fret) { "?" }
        private fun tiedText(fret: Int) = TIED.getOrElse(fret) { "(?)" }

        private fun clock(t: Double): String {
            val s = max(0, t.toInt())
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
