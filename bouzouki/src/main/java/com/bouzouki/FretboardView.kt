package com.bouzouki

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.bouzouki.core.BouzoukiSynth
import com.bouzouki.core.Cell
import com.bouzouki.core.Dromos
import com.bouzouki.core.Instrument
import com.bouzouki.core.NoteNames
import com.bouzouki.core.ScalePath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The bouzouki neck drawn vertically, nut at the top, so it scrolls down towards the
 * high frets. Touch a course anywhere along the neck to pluck it, slide along it to
 * glide from fret to fret, and use several fingers at once. The fret-number gutters
 * on both sides are left to the surrounding ScrollView for scrolling.
 */
class FretboardView(context: Context, private val synth: BouzoukiSynth) : View(context) {
    var instrument = Instrument.TETRACHORDO
        set(v) { field = v; held.clear(); requestLayout(); invalidate() }
    var dromos = Dromos.HITZAZ
        set(v) { field = v; invalidate() }
    var rootPc = 2
        set(v) { field = v; invalidate() }
    var names = NoteNames.WESTERN
        set(v) { field = v; invalidate() }
    var showAllNotes = false
        set(v) { field = v; invalidate() }
    /** Mark only the arpeggio (1st, 3rd, 5th, 7th) instead of the whole dromos. */
    var arpeggio = false
        set(v) { field = v; invalidate() }
    /** The course the scale player runs down, shaded on the neck. */
    var focusCourse: Int? = null
        set(v) { field = v; invalidate() }
    /** The note a song is playing right now, marked with a big gold ring. */
    var liveCell: Cell? = null
        set(v) { field = v; invalidate() }
    /** Where the song goes next, marked with a hollow ring so the hand can get ready. */
    var nextCell: Cell? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The frets of the hand position the scale player runs across, shaded on the neck. */
    var focusFrets: IntRange? = null
        set(v) { field = v; invalidate() }
    var tremolo = false
        set(v) { field = v; if (v) scheduleTremolo() }

    /**
     * How tall the frets are: 1 is the normal size, smaller fits more frets on screen.
     * Pinch the neck to change it.
     */
    var zoom: Float
        get() = zoomValue
        set(v) { zoomValue = v.coerceIn(MIN_ZOOM, MAX_ZOOM); requestLayout(); invalidate() }
    private var zoomValue = 1f

    /** Shrink the neck so every fret fits on screen at once. Pinching turns this off. */
    var wholeNeck = false
        set(v) { field = v; requestLayout(); invalidate() }

    /** Called after a pinch, with the new zoom, so the screen can remember it. */
    var onZoomChanged: ((Float) -> Unit)? = null

    /** Called with the y range of a cell that was played, so the scroller can follow it. */
    var onPlayed: ((top: Int, bottom: Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val gutter = 40 * dp
    private var openRow = 64 * dp

    private val fretTops = ArrayList<Float>() // fretTops[n] = y of the wire at the bottom of fret n (n=0 is the nut)

    private val held = HashMap<Int, Pair<Int, Int>>() // pointer id -> (course, fret)
    private val flashes = HashMap<Long, Long>()      // cell key -> time played
    private var tremoloUp = true

    private val wood = Paint()
    private val headPaint = Paint().apply { color = 0xFF1C0F07.toInt() }
    private val fretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9D9D9.toInt() }
    private val fretShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val nutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3EBD8.toInt() }
    private val inlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99E8DCC8.toInt(); textAlign = Paint.Align.CENTER
    }
    private val gutterText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9FB4CC.toInt(); textAlign = Paint.Align.CENTER; textSize = 13 * dp
    }
    private val gutterBg = Paint().apply { color = 0xFF0B1A2C.toInt() }
    private val focusFill = Paint().apply { color = 0x30FFD54F }
    private val focusEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB0FFD54F.toInt(); style = Paint.Style.STROKE
    }

    private fun baseFretHeight(n: Int) = max(56 * dp, 104 * dp * 0.965f.pow(n - 1))
    private fun fretHeight(n: Int) = baseFretHeight(n) * zoom
    private fun openRowFor(z: Float) = max(26 * dp, 64 * dp * z)
    private val bottomMargin get() = 24 * dp

    /** Height of the scroll area the neck sits in. */
    private val viewport get() = (parent as? View)?.height ?: 0

    /** The zoom at which the whole neck fits in [height] pixels. */
    private fun fitZoom(height: Int): Float {
        val frets = (1..instrument.frets).sumOf { baseFretHeight(it).toDouble() }.toFloat()
        var z = (height - bottomMargin) / (64 * dp + frets)
        if (64 * dp * z < 26 * dp) z = (height - bottomMargin - 26 * dp) / frets
        return z.coerceIn(0.05f, MAX_ZOOM)
    }

    /** Smallest useful zoom: the whole neck on screen (never smaller). */
    private fun minZoom() = if (viewport > 0) min(fitZoom(viewport), 1f) else MIN_ZOOM

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The whole-neck size depends on the scroll area's height, known only after it's laid out.
        (parent as? View)?.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (wholeNeck && bottom - top != oldBottom - oldTop) post { requestLayout() }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (wholeNeck && viewport > 0) zoomValue = fitZoom(viewport) // no requestLayout() while measuring
        openRow = openRowFor(zoom)
        var y = openRow
        fretTops.clear()
        fretTops.add(y)
        for (n in 1..instrument.frets) { y += fretHeight(n); fretTops.add(y) }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (y + bottomMargin).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        wood.shader = LinearGradient(
            gutter, 0f, w - gutter, 0f,
            intArrayOf(0xFF2A160C.toInt(), 0xFF4A2A17.toInt(), 0xFF3A2012.toInt(), 0xFF4A2A17.toInt(), 0xFF2A160C.toInt()),
            null, Shader.TileMode.CLAMP,
        )
    }

    private val neckLeft get() = gutter
    private val neckRight get() = width - gutter
    private val courseWidth get() = (neckRight - neckLeft) / instrument.courses.size
    private fun courseX(c: Int) = neckLeft + courseWidth * (c + 0.5f)
    private fun cellTop(fret: Int) = if (fret == 0) 0f else fretTops[fret - 1]
    private fun cellBottom(fret: Int) = fretTops[fret]
    private fun cellCenterY(fret: Int) = if (fret == 0) openRow * 0.5f else (cellTop(fret) + cellBottom(fret)) / 2
    private fun key(course: Int, fret: Int) = course * 100L + fret

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val bottom = fretTops.last()
        canvas.drawColor(0xFF0B1A2C.toInt())
        canvas.drawRect(0f, 0f, gutter, height.toFloat(), gutterBg)
        canvas.drawRect(neckLeft, openRow, neckRight, bottom + 12 * dp, wood)

        // Headstock area above the nut.
        canvas.drawRect(neckLeft, 0f, neckRight, openRow, headPaint)

        // Where the scale player will play: one course, or one hand position.
        focusEdge.strokeWidth = 2 * dp
        focusCourse?.takeIf { it in instrument.courses.indices }?.let { c ->
            val l = neckLeft + courseWidth * c + 2 * dp
            val r = l + courseWidth - 4 * dp
            canvas.drawRect(l, 2 * dp, r, bottom + 10 * dp, focusFill)
            canvas.drawRect(l, 2 * dp, r, bottom + 10 * dp, focusEdge)
        }
        focusFrets?.let { range ->
            val first = range.first.coerceIn(0, instrument.frets)
            val last = range.last.coerceIn(0, instrument.frets)
            val t = cellTop(first) + 2 * dp
            val b = cellBottom(last) - 2 * dp
            canvas.drawRect(neckLeft + 2 * dp, t, neckRight - 2 * dp, b, focusFill)
            canvas.drawRect(neckLeft + 2 * dp, t, neckRight - 2 * dp, b, focusEdge)
        }

        // Mother-of-pearl inlays.
        val now = SystemClock.uptimeMillis()
        for (n in 1..instrument.frets) {
            val marks = when (n) { 12, 24 -> 2; 3, 5, 7, 10, 15, 17, 19, 22 -> 1; else -> 0 }
            if (marks == 0) continue
            val cy = cellCenterY(n)
            val r = min(fretHeight(n) * 0.16f, 11 * dp)
            val xs = if (marks == 1) listOf((neckLeft + neckRight) / 2) else
                listOf(neckLeft + (neckRight - neckLeft) * 0.25f, neckLeft + (neckRight - neckLeft) * 0.75f)
            for (x in xs) {
                inlay.shader = RadialGradient(x - r / 3, cy - r / 3, r * 1.6f,
                    intArrayOf(0xFFFFFFFF.toInt(), 0xFFCFE3E8.toInt(), 0xFFE8D9F0.toInt()), null, Shader.TileMode.CLAMP)
                canvas.drawPath(diamond(x, cy, r * 1.25f, r), inlay)
            }
        }

        // Frets and the nut, with fret numbers in both gutters.
        canvas.drawRect(neckLeft, openRow - 6 * dp, neckRight, openRow, nutPaint)
        for (n in 1..instrument.frets) {
            val y = fretTops[n]
            canvas.drawRect(neckLeft, y + 1.5f * dp, neckRight, y + 3.5f * dp, fretShadow)
            canvas.drawRect(neckLeft, y - 1.5f * dp, neckRight, y + 1.5f * dp, fretPaint)
            // Zoomed far out, only the marked frets keep their numbers.
            val h = fretHeight(n)
            if (h < 16 * dp && n !in MARKED) continue
            gutterText.textSize = min(13 * dp, h * 0.75f).coerceAtLeast(8 * dp)
            val ty = cellCenterY(n) + gutterText.textSize * 0.38f
            gutterText.color = if (n % 12 == 0) 0xFFFFD54F.toInt() else 0xFF9FB4CC.toInt()
            canvas.drawText(n.toString(), gutter / 2, ty, gutterText)
            canvas.drawText(n.toString(), w - gutter / 2, ty, gutterText)
        }
        gutterText.color = 0xFF9FB4CC.toInt()
        gutterText.textSize = min(13 * dp, openRow * 0.5f)
        canvas.drawText("0", gutter / 2, openRow / 2 + 5 * dp, gutterText)
        canvas.drawText("0", w - gutter / 2, openRow / 2 + 5 * dp, gutterText)

        // The string pairs. Octave courses have a thick and a thin string.
        for ((c, course) in instrument.courses.withIndex()) {
            val x = courseX(c)
            val gap = 4 * dp
            val ringing = held.values.any { it.first == c }
            stringPaint.color = if (ringing) 0xFFFFF3C4.toInt() else 0xFFC9CED6.toInt()
            stringPaint.strokeWidth = (if (course.octavePair) 2.6f else 1.6f) * dp
            canvas.drawLine(x - gap, 8 * dp, x - gap, bottom + 12 * dp, stringPaint)
            stringPaint.strokeWidth = 1.4f * dp
            canvas.drawLine(x + gap, 8 * dp, x + gap, bottom + 12 * dp, stringPaint)
        }

        // Notes of the dromos.
        val heldKeys = held.values.map { key(it.first, it.second) }.toSet()
        val marked = if (arpeggio) ScalePath.arpeggio(dromos) else dromos.semitones
        for (c in instrument.courses.indices) for (f in 0..instrument.frets) {
            val midi = instrument.midiAt(c, f)
            val inScale = Math.floorMod(midi - rootPc, 12) in marked
            val x = courseX(c)
            val y = cellCenterY(f)
            val cellH = if (f == 0) openRow else fretHeight(f)
            val r = min(courseWidth * 0.34f, cellH * 0.36f)
            val k = key(c, f)
            val flash = flashes[k]?.let { 1f - (now - it) / 320f }?.takeIf { it > 0f } ?: 0f
            val pressed = k in heldKeys
            if (pressed || flash > 0f) {
                val a = if (pressed) 1f else flash
                glow.shader = RadialGradient(x, y, r * 1.9f,
                    intArrayOf(((0xC0 * a).toInt() shl 24) or 0xFFE082, 0x00FFE082), null, Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, r * 1.9f, glow)
            }
            val label = names.name(midi)
            if (inScale) {
                val isRoot = Math.floorMod(midi - rootPc, 12) == 0
                dotPaint.color = when {
                    pressed -> 0xFFFFC107.toInt()
                    isRoot -> 0xFFC62828.toInt()
                    else -> 0xFF0D5EAF.toInt()
                }
                canvas.drawCircle(x, y, r, dotPaint)
                ring.strokeWidth = 2 * dp
                ring.color = if (isRoot) 0xFFFFD54F.toInt() else 0xCCFFFFFF.toInt()
                canvas.drawCircle(x, y, r, ring)
                if (r >= 6 * dp) { // too small to read when zoomed far out
                    text.textSize = r * if (names == NoteNames.GREEK) 0.62f else 0.78f
                    text.color = if (pressed) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()
                    canvas.drawText(label, x, y + text.textSize * 0.36f, text)
                }
            } else if ((showAllNotes || pressed) && r >= 6 * dp) {
                faint.textSize = r * 0.62f
                canvas.drawText(label, x, y + faint.textSize * 0.36f, faint)
            }
        }
        nextCell?.takeIf { it != liveCell && it.course in instrument.courses.indices && it.fret in 0..instrument.frets }?.let { cell ->
            val x = courseX(cell.course)
            val y = cellCenterY(cell.fret)
            val cellH = if (cell.fret == 0) openRow else fretHeight(cell.fret)
            val r = max(min(courseWidth * 0.36f, cellH * 0.40f), 9 * dp)
            ring.strokeWidth = 3 * dp
            ring.color = 0xCCFFD54F.toInt()
            canvas.drawCircle(x, y, r, ring)
        }
        liveCell?.takeIf { it.course in instrument.courses.indices && it.fret in 0..instrument.frets }?.let { cell ->
            val x = courseX(cell.course)
            val y = cellCenterY(cell.fret)
            val cellH = if (cell.fret == 0) openRow else fretHeight(cell.fret)
            val r = max(min(courseWidth * 0.40f, cellH * 0.44f), 11 * dp) // stays readable zoomed out
            glow.shader = RadialGradient(x, y, r * 2.2f, intArrayOf(0xE0FFC107.toInt(), 0x00FFC107), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r * 2.2f, glow)
            dotPaint.color = 0xFFFFC107.toInt()
            canvas.drawCircle(x, y, r, dotPaint)
            ring.strokeWidth = 3 * dp
            ring.color = WHITE
            canvas.drawCircle(x, y, r, ring)
            text.textSize = r * if (names == NoteNames.GREEK) 0.62f else 0.8f
            text.color = 0xFF1A1A1A.toInt()
            canvas.drawText(names.name(instrument.midiAt(cell.course, cell.fret)), x, y + text.textSize * 0.36f, text)
        }
        if (flashes.isNotEmpty()) {
            flashes.entries.removeAll { now - it.value > 320 }
            postInvalidateOnAnimation()
        }
    }

    private fun diamond(cx: Float, cy: Float, rx: Float, ry: Float) = Path().apply {
        moveTo(cx, cy - ry); lineTo(cx + rx, cy); lineTo(cx, cy + ry); lineTo(cx - rx, cy); close()
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (x < neckLeft || x > neckRight || fretTops.isEmpty()) return null
        val c = ((x - neckLeft) / courseWidth).toInt().coerceIn(0, instrument.courses.size - 1)
        if (y < openRow) return c to 0
        val f = fretTops.indexOfFirst { y < it }
        return if (f < 1) c to instrument.frets else c to f
    }

    private fun play(course: Int, fret: Int, velocity: Float) {
        synth.pluck(course, instrument.midiAt(course, fret), instrument.courses[course].octavePair, velocity)
        flashes[key(course, fret)] = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Plays one note for the scale player and reports where it is so the screen can follow. */
    fun playCell(cell: Cell) {
        play(cell.course, cell.fret, 0.8f)
        onPlayed?.invoke(cellTop(cell.fret).toInt(), cellBottom(cell.fret).toInt())
    }

    /** The y range of one fret, for scrolling it into view. */
    fun cellSpan(cell: Cell): Pair<Int, Int> = fretSpan(cell.fret, cell.fret)

    /** The y range of a block of frets, for scrolling it into view. */
    fun fretSpan(first: Int, last: Int): Pair<Int, Int> =
        cellTop(first.coerceIn(0, instrument.frets)).toInt() to cellBottom(last.coerceIn(0, instrument.frets)).toInt()

    private var pinching = false

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // Two fingers spreading or closing: zoom instead of playing.
            pinching = true
            held.clear()
            synth.muteAll()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = zoom
            val new = (old * detector.scaleFactor).coerceIn(minZoom(), MAX_ZOOM)
            if (new == old) return true
            wholeNeck = false
            zoom = new
            // Keep the frets under the fingers in place while the neck grows or shrinks.
            val scroller = parent as? View ?: return true
            val onScreen = detector.focusY - scroller.scrollY
            val target = (detector.focusY * new / old - onScreen).toInt().coerceAtLeast(0)
            post { scroller.scrollTo(0, target) }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            onZoomChanged?.invoke(zoom)
        }
    }).apply { isQuickScaleEnabled = false }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
        pinch.onTouchEvent(e)
        if (pinching) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                pinching = false
                held.clear()
                invalidate()
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val cell = cellAt(e.getX(i), e.getY(i))
                if (cell == null) return e.actionMasked != MotionEvent.ACTION_DOWN // gutter: let the ScrollView scroll
                parent?.requestDisallowInterceptTouchEvent(true)
                // One finger per course: a new finger on a ringing course replaces the old one.
                held.entries.removeAll { it.value.first == cell.first }
                held[e.getPointerId(i)] = cell
                play(cell.first, cell.second, 0.9f)
                if (tremolo) scheduleTremolo()
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                val old = held[id] ?: continue
                val cell = cellAt(e.getX(i), e.getY(i)) ?: continue
                if (cell != old) {
                    held.entries.removeAll { it.key != id && it.value.first == cell.first }
                    held[id] = cell
                    // Gliding along a course re-sounds it softly, like a slide on the fretboard.
                    play(cell.first, cell.second, if (cell.first == old.first) 0.55f else 0.85f)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> { held.remove(e.getPointerId(e.actionIndex)); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { held.clear(); invalidate() }
        }
        return true
    }

    private val tremoloTick = object : Runnable {
        override fun run() {
            if (!tremolo || held.isEmpty()) return
            // Fast alternate picking, the classic bouzouki tremolo.
            tremoloUp = !tremoloUp
            for ((c, f) in held.values.distinct()) {
                synth.pluck(c, instrument.midiAt(c, f), instrument.courses[c].octavePair, if (tremoloUp) 0.62f else 0.74f)
            }
            postDelayed(this, 68)
        }
    }

    private fun scheduleTremolo() {
        removeCallbacks(tremoloTick)
        postDelayed(tremoloTick, 68)
    }

    companion object {
        const val MIN_ZOOM = 0.05f
        const val MAX_ZOOM = 2.5f
        private val MARKED = setOf(3, 5, 7, 10, 12, 15, 17, 19, 22, 24)
    }
}
