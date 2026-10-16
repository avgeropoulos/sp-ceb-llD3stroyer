package com.bouzouki

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.bouzouki.core.BouzoukiSynth
import com.bouzouki.core.Cell
import com.bouzouki.core.Dromos
import com.bouzouki.core.Instrument
import com.bouzouki.core.NoteNames
import com.bouzouki.core.ScalePath
import java.lang.ref.SoftReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole Greek bouzouki seen from above, lying across the screen as the player looks down
 * at it: headstock and tuners on the left, the frets rising to the right, the body on the
 * right, the highest course at the top like a line of tab. Notes light up on it as they do on
 * [FretboardView]: the note now as a gold token with its name, the next one as a hollow ring,
 * a legato trail along the string, the dromos as small pearl dots, and the sounding string
 * hums and shimmers. Touch a course on the neck to pluck it there, or over the body to pick it
 * open (sweeping across the courses strums them).
 *
 * Proportions are a real instrument's (67.5 cm scale, a 30 by 39 cm pear-shaped body, 99 cm
 * long in all), except that the neck may be drawn up to 1.6 times wider so the courses stay
 * far enough apart to read. The leather it lies on fills the whole view; the instrument and
 * the free slots ([videoSlot], [statusSlot]) stay inside the view's padding, so padding the
 * view by the system bars and cut-outs keeps them clear while the view runs edge to edge.
 * Everything that stands still, the resting strings included, is painted into a bitmap on a
 * background thread; a frame copies it and draws the course that sounds, the dromos dots
 * (small ready-made bitmaps, so a change of dromos shows at once) and the song's marks.
 */
class BouzoukiView(context: Context, private val synth: BouzoukiSynth) : View(context) {
    var instrument = Instrument.TETRACHORDO
        set(v) { if (field != v) { field = v; held.clear(); bodyTouch.clear(); syncHeld(); relayout() } }
    var dromos = Dromos.HITZAZ
        set(v) { field = v; marksChanged() }
    var rootPc = 2
        set(v) { field = v; marksChanged() }
    var names = NoteNames.WESTERN
        set(v) { field = v; invalidate() }
    var showAllNotes = false
        set(v) { field = v; marksChanged() }
    /** Mark only the arpeggio (1st, 3rd, 5th, 7th) instead of the whole dromos. */
    var arpeggio = false
        set(v) { field = v; marksChanged() }
    /**
     * The note a song is playing right now: a big gold token, and its string hums from there to the
     * bridge. Set [legatoFrom] first: a hammered, pulled or slid note keeps the string ringing
     * from its new fret instead of picking it again.
     */
    var liveCell: Cell? = null
        set(v) {
            val now = SystemClock.uptimeMillis()
            // A new note (or the same one picked again) sets its string swinging; the same value set again at once doesn't.
            if (v != null && onNeck(v) && (v != field || now - liveAt > 60)) {
                if (legatoFrom?.course == v.course && v.course < MAX_COURSES) pluckFret[v.course] = v.fret else shake(v.course, v.fret, now)
                liveAt = now
            }
            field = v
            invalidate()
        }
    private var liveAt = 0L
    /** Where the song goes next, marked with a hollow ring so the hand can get ready. */
    var nextCell: Cell? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Where the live note was hammered, pulled or slid from (on its course): a gold trail along the string. */
    var legatoFrom: Cell? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /**
     * Tremolo: a finger holding a note picks it again and again (the classic bouzouki tremolo),
     * and with no finger down the live note's string keeps shimmering, for a tremolo note a song plays.
     */
    var tremolo = false
        set(v) { if (field != v) { field = v; if (v) scheduleTremolo() } }

    /**
     * A free area above the neck and headstock, 16:9 and as large as fits, where the song's video
     * can sit without hiding the instrument. Empty until the view has a size.
     */
    fun videoSlot(): RectF = RectF(shape?.video ?: EMPTY)

    /** A one-line strip for status text, below the neck on the left. Empty until the view has a size. */
    fun statusSlot(): RectF = RectF(shape?.status ?: EMPTY)

    /** The middle of [cell]'s place (an open string's is on the nut) in this view's pixels; null before layout or off the neck. */
    fun cellCenter(cell: Cell): PointF? {
        val sh = shape ?: return null
        if (!onNeck(cell)) return null
        val x = sh.cellX(cell.fret)
        return PointF(x, sh.courseY(cell.course, x))
    }

    /** Whether the painted instrument is shown; until it's ready a flat stand-in is. */
    val artReady: Boolean
        get() = art.let { it != null && it.shape === shape && !it.bitmap.isRecycled }

    /** Plucks [cell] with the bouzouki sound and sets its string swinging. */
    fun playCell(cell: Cell) {
        if (onNeck(cell)) play(cell.course, cell.fret, 0.8f)
    }

    /** Sets the string of [cell] swinging without a sound, say for each stroke of a tremolo the song plays. */
    fun showPluck(cell: Cell) {
        if (!onNeck(cell)) return
        shake(cell.course, cell.fret, SystemClock.uptimeMillis())
        invalidate()
    }

    private val dp = resources.displayMetrics.density
    private val pens = BouzoukiPens(dp)
    private var shape: BouzoukiShape? = null
    /** The last layout worked out (see [shapeFor]). */
    private var planned: BouzoukiShape? = null
    private var art: BouzoukiArt.Painting? = null
    private var painting = false
    private var paintGen = 0
    /** The layout the painter thread should paint; anything else it was asked for meanwhile it skips. */
    @Volatile private var wanted: BouzoukiShape? = null
    /** A layout whose painting failed (say, out of memory): the stand-in stays for it, without trying again and again. */
    private var failed: BouzoukiShape? = null
    private val artDst = Rect()
    // Flat stand-ins for the painting while it is being made.
    private var flatTop: Path? = null
    private var flatHead: Path? = null
    private var flatBoard: Path? = null
    // The dromos dots: x, y, radius and kind (0 pearl, 1 root, 2 another note) of each, made again when the scale or the layout changes.
    private var marks = FloatArray(0)
    private var markCount = 0
    private var marksDirty = true
    private var sprites: Array<Bitmap>? = null
    private val spriteDst = RectF()

    private val held = HashMap<Int, Pair<Int, Int>>() // pointer id -> (course, fret)
    /** Fingers that came down over the body: they pick open strings, and sweeping across the courses strums them. */
    private val bodyTouch = HashSet<Int>()
    private var tremoloUp = true
    // Per-cell and per-course state kept in flat arrays so drawing never boxes or allocates.
    private val pressed = BooleanArray(MAX_COURSES * CELLS)
    private val ringing = BooleanArray(MAX_COURSES)
    private val heldFret = IntArray(MAX_COURSES)
    private val flashAt = LongArray(MAX_COURSES * CELLS)
    private val pluckAt = LongArray(MAX_COURSES)
    private val pluckFret = IntArray(MAX_COURSES)
    private val pluckAmp = FloatArray(MAX_COURSES)
    private var lastPlay = 0L
    private val swingPath = Path()

    private val aa = Paint.ANTI_ALIAS_FLAG
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val flat = Paint(aa)
    private val hum = Paint(aa).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ring = Paint(aa).apply { style = Paint.Style.STROKE }
    private val text = Paint(aa).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    private val tokenShadow = Paint(aa).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0xA0000000.toInt(), 0x70000000, 0x00000000),
            floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
    }
    private val liveFace = face(0xFFFFF8D8, 0xFFFFD866, 0xFFEAA824, 0xFFA87010)
    private val glowPaint = Paint(aa).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0xD8FFC860.toInt(), 0x66FFB030, 0x00FFA000),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }
    /** The legato trail along x in unit space: clear where the finger was, gold where it is now. */
    private val trailPaint = Paint(aa).apply {
        shader = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0x00FFC860, 0x70FFC860, 0xE8FFD866.toInt()),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    /** The padding is the room kept clear of system bars and cut-outs: the instrument is laid out inside it. */
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        val same = left == paddingLeft && top == paddingTop && right == paddingRight && bottom == paddingBottom
        super.setPadding(left, top, right, bottom)
        if (!same) relayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestPaint()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tremoloTick)
        paintGen++
        painting = false
        wanted = null
        // The painting is a screenful of pixels: the view lets it go now. It stays in [lastPainting] only while memory
        // allows, so coming back to the same screen shows it at once; otherwise it's made again.
        art = null
    }

    /**
     * The layout for a [w] by [h] view with the current padding and instrument, kept while those stay the
     * same (a parent can ask for it while measuring, before the view has its size). Null for an empty view.
     */
    internal fun shapeFor(w: Int, h: Int): BouzoukiShape? {
        if (w <= 0 || h <= 0) return null
        val pad = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
        planned?.takeIf { it.matches(w, h, dp, instrument, pad) }?.let { return it }
        // A painting kept from before (the screen turned sideways again, say) brings its own layout, the very same.
        val kept = lastPainting?.get()?.takeIf { it.shape.matches(w, h, dp, instrument, pad) && !it.bitmap.isRecycled }
        return (kept?.shape ?: BouzoukiShape(w, h, dp, instrument, pad)).also { planned = it }
    }

    /** Lays the instrument out again for a new size, padding or instrument; the old painting no longer fits. */
    private fun relayout() {
        val sh = shapeFor(width, height)
        if (sh != null && sh === shape) return
        val old = art
        art = null
        old?.let { retire(it) }
        shape = sh
        marksDirty = true
        if (sh == null) { flatTop = null; flatHead = null; flatBoard = null; return }
        flatTop = sh.outline(0f)
        flatHead = sh.headPath()
        flatBoard = sh.boardPath(0f)
        requestPaint()
        invalidate()
    }

    private fun marksChanged() {
        marksDirty = true
        invalidate()
    }

    /** Paints the still bouzouki on the painter thread, unless it's painted (or being painted) already. */
    private fun requestPaint() {
        val sh = shape ?: return
        if (!isAttachedToWindow || sh === failed) return
        art?.let { if (it.shape === sh && !it.bitmap.isRecycled) return }
        lastPainting?.get()?.takeIf { it.shape === sh && !it.bitmap.isRecycled }?.let { art = it; invalidate(); return }
        if (painting) return // it looks again when that one is done
        painting = true
        wanted = sh
        val gen = paintGen
        painter.execute {
            var broke = false
            val made = if (wanted !== sh) null else try { BouzoukiArt.paint(sh) } catch (e: Throwable) { broke = true; null }
            mainHandler.post {
                // Detached meanwhile: kept for when the view comes back (only while memory allows).
                if (gen != paintGen) { made?.let { lastPainting = SoftReference(it) }; return@post }
                painting = false
                if (broke) failed = sh
                if (made != null && made.shape === shape) {
                    val old = art
                    art = made
                    lastPainting = SoftReference(made)
                    if (old != null && old !== made) retire(old)
                    invalidate()
                } else made?.let { release(it.bitmap) }
                requestPaint() // the size may have changed meanwhile
            }
        }
    }

    /** Lets an old painting's pixels go, unless it's the one kept for later. */
    private fun retire(p: BouzoukiArt.Painting) {
        if (p !== art && p !== lastPainting?.get()) release(p.bitmap)
    }

    override fun onDraw(canvas: Canvas) {
        val sh = shape ?: return
        if (art?.bitmap?.isRecycled == true) { art = null; requestPaint() }
        val now = SystemClock.uptimeMillis()
        val a = art?.takeIf { it.shape === sh }
        if (a != null) {
            artDst.set(0, 0, sh.width, sh.height)
            canvas.drawBitmap(a.bitmap, null, artDst, artPaint)
        } else drawStandIn(canvas, sh)
        val moving = drawSounding(canvas, sh, now)
        drawMarks(canvas, sh)
        drawTouches(canvas, sh, now)
        drawSongMarks(canvas, sh)
        // Only the band the strings and notes move in changes. A software canvas then copies just that much; with
        // hardware drawing (the usual case) the whole view is drawn again, which is a cheap copy of the painting.
        if (moving || now - lastPlay < FLASH_MS) sh.band.let { postInvalidateOnAnimation(it.left, it.top, it.right, it.bottom) }
    }

    /** Flat shapes and the strings while the painting is being made. */
    private fun drawStandIn(canvas: Canvas, sh: BouzoukiShape) {
        canvas.drawColor(0xFF22170F.toInt())
        flat.color = 0xFFCFAE7A.toInt()
        flatTop?.let { canvas.drawPath(it, flat) }
        flat.color = 0xFF0E0A08.toInt()
        flatHead?.let { canvas.drawPath(it, flat) }
        flat.color = 0xFF1E140E.toInt()
        flatBoard?.let { canvas.drawPath(it, flat) }
        for (st in sh.strings) pens.stillString(canvas, sh, st)
    }

    /**
     * The courses that sound: the live note's course hums (a warm glow and bright metal from the
     * stopping fret to the bridge), and a plucked course swings there for a moment, blurring
     * between its ghosts. Returns whether any string moves.
     */
    private fun drawSounding(canvas: Canvas, sh: BouzoukiShape, now: Long): Boolean {
        var moving = false
        val live = liveCell?.takeIf { onNeck(it) }
        for (c in 0 until sh.courses) {
            val age = now - pluckAt[c]
            val shaking = pluckAt[c] > 0 && age < VIBRATE_MS
            val isLive = live?.course == c
            if (!shaking && !isLive && !ringing[c]) continue
            if (shaking) moving = true
            val fret = when {
                shaking -> pluckFret[c]
                isLive -> live!!.fret
                else -> heldFret[c]
            }.coerceIn(0, sh.frets)
            val x0 = sh.stopX(fret)
            val bright = isLive || ringing[c]
            for (st in sh.strings) {
                if (st.course != c) continue
                if (isLive) {
                    val lx = sh.stopX(live!!.fret)
                    hum.strokeWidth = st.width + 3.4f * dp
                    hum.color = 0x44FFC860
                    canvas.drawLine(lx, sh.yOn(st, lx), sh.bridgeX, st.bridgeY, hum)
                }
                val color = pens.colorOf(st, bright)
                if (!shaking) {
                    pens.piece(canvas, x0, sh.yOn(st, x0), sh.bridgeX, st.bridgeY, st, color)
                    continue
                }
                // Decaying swing; the thin string of an octave pair swings twice as fast.
                val t = age.toFloat()
                val env = exp(-t / 190f)
                val period = if (sh.instrument.courses[c].octavePair && !st.wound) 42f else 84f
                val reach = sh.spacing(sh.bridgeX) * 0.3f * pluckAmp[c]
                val y0 = sh.yOn(st, x0)
                swing(sh, st, x0, y0, reach * 0.85f * env)
                pens.ghost(canvas, swingPath, st, color, env)
                swing(sh, st, x0, y0, -reach * 0.85f * env)
                pens.ghost(canvas, swingPath, st, color, env)
                swing(sh, st, x0, y0, reach * env * cos(2f * PI.toFloat() * t / period + st.index * 0.9f))
                pens.curve(canvas, swingPath, st, color)
            }
        }
        return moving
    }

    /** Makes [swingPath] a string bent [amp] sideways halfway between ([x0], [y0]) and the bridge (a quadratic is close to half a sine). */
    private fun swing(sh: BouzoukiShape, st: BouzoukiShape.Str, x0: Float, y0: Float, amp: Float) {
        swingPath.rewind()
        swingPath.moveTo(x0, y0)
        swingPath.quadTo((x0 + sh.bridgeX) / 2, (y0 + st.bridgeY) / 2 + 2 * amp, sh.bridgeX, st.bridgeY)
    }

    /** The dromos: pearl dots, the root in red lacquer, the other notes as small dark dots when asked; each a small ready-made bitmap. */
    private fun drawMarks(canvas: Canvas, sh: BouzoukiShape) {
        if (marksDirty) buildMarks(sh)
        if (markCount == 0) return
        val sp = sprites ?: makeSprites().also { sprites = it }
        val perR = sp[0].width / 2f / pens.maxMarkR // a sprite's half size, per pixel of the dot's radius
        for (m in 0 until markCount) {
            val k = m * 4
            val half = marks[k + 2] * perR
            spriteDst.set(marks[k] - half, marks[k + 1] - half, marks[k] + half, marks[k + 1] + half)
            canvas.drawBitmap(sp[marks[k + 3].toInt()], null, spriteDst, artPaint)
        }
    }

    /** Works out where the dromos dots go (only when the scale or the layout changes). */
    private fun buildMarks(sh: BouzoukiShape) {
        marksDirty = false
        val mask = markMask()
        val root = Math.floorMod(rootPc, 12)
        val need = sh.courses * (sh.frets + 1) * 4
        if (marks.size < need) marks = FloatArray(need)
        markCount = 0
        for (c in 0 until sh.courses) for (f in 0..sh.frets) {
            val degree = Math.floorMod(sh.instrument.midiAt(c, f) - root, 12)
            val kind = when {
                mask and (1 shl degree) == 0 -> if (showAllNotes) 2 else continue
                degree == 0 -> 1
                else -> 0
            }
            val x = sh.cellX(f)
            val k = markCount++ * 4
            marks[k] = x
            marks[k + 1] = sh.courseY(c, x)
            marks[k + 2] = pens.markR(sh, f)
            marks[k + 3] = kind.toFloat()
        }
    }

    /** The three kinds of dot painted once at their largest, shadows included; each frame scales them down to size. */
    private fun makeSprites(): Array<Bitmap> {
        val r = pens.maxMarkR
        val size = ceil(2 * SPRITE_REACH * r).toInt() + 2
        return Array(3) { kind ->
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { pens.mark(Canvas(it), size / 2f, size / 2f, r, kind) }
        }
    }

    /** Glows where fingers press or just played, with the pressed notes in gold. */
    private fun drawTouches(canvas: Canvas, sh: BouzoukiShape, now: Long) {
        if (now - lastPlay >= FLASH_MS && held.isEmpty()) return
        for (c in 0 until sh.courses) for (f in 0..sh.frets) {
            val i = c * CELLS + f
            val since = now - flashAt[i]
            val flash = if (flashAt[i] > 0 && since < FLASH_MS) 1f - since / FLASH_MS.toFloat() else 0f
            val down = pressed[i]
            if (!down && flash <= 0f) continue
            val x = sh.cellX(f)
            val y = sh.courseY(c, x)
            val r = pens.markR(sh, f)
            glowPaint.alpha = ((if (down) 1f else flash) * 255).toInt()
            unitCircle(canvas, x, y, max(r * 3.4f, 10 * dp), glowPaint)
            glowPaint.alpha = 255
            if (down) pens.pressedDot(canvas, x, y, r * 1.3f)
        }
    }

    /** The song's next note (hollow gold ring), the legato trail into the note now, and the note now (gold token). */
    private fun drawSongMarks(canvas: Canvas, sh: BouzoukiShape) {
        val labels = LABELS[names.ordinal]
        val r = (sh.spacing(sh.cellX(7)) * 0.85f).coerceIn(10.5f * dp, 14 * dp)
        nextCell?.takeIf { it != liveCell && onNeck(it) }?.let { cell ->
            val x = sh.cellX(cell.fret)
            val y = sh.courseY(cell.course, x)
            // Dark inside, so a dot or an inlay under it doesn't make it read as one more marked note.
            flat.color = 0xC0140D09.toInt()
            canvas.drawCircle(x, y, r * 0.86f - 1.3f * dp, flat)
            ring.strokeWidth = 5f * dp
            ring.color = 0x99000000.toInt()
            canvas.drawCircle(x, y, r * 0.86f, ring)
            ring.strokeWidth = 2.6f * dp
            ring.color = 0xF0FFD36A.toInt()
            canvas.drawCircle(x, y, r * 0.86f, ring)
        }
        val cell = liveCell?.takeIf { onNeck(it) } ?: return
        val x = sh.cellX(cell.fret)
        val y = sh.courseY(cell.course, x)
        legatoFrom?.takeIf { it.course == cell.course && it.fret != cell.fret && onNeck(it) }?.let { from ->
            // Hammered, pulled or slid here: a gold trail along the string from where the finger was.
            val x0 = sh.cellX(from.fret)
            val y0 = sh.courseY(cell.course, x0)
            val half = (sh.spacing(x) * 0.22f).coerceIn(2.2f * dp, 4 * dp)
            canvas.save()
            canvas.translate(x0, y0)
            canvas.rotate(atan2(y - y0, x - x0) * 180f / PI.toFloat())
            canvas.scale(hypot(x - x0, y - y0), 1f)
            canvas.drawRect(0f, -half, 1f, half, trailPaint)
            canvas.restore()
            ring.strokeWidth = 1.5f * dp
            ring.color = 0x90FFD36A.toInt()
            canvas.drawCircle(x0, y0, r * 0.42f, ring)
        }
        unitCircle(canvas, x, y, r * 2.3f, glowPaint)
        unitCircle(canvas, x + 0.1f * r, y + 0.16f * r, 1.14f * r, tokenShadow)
        unitCircle(canvas, x, y, r, liveFace)
        ring.strokeWidth = 2.4f * dp
        ring.color = 0xFFFFF6DA.toInt()
        canvas.drawCircle(x, y, r - ring.strokeWidth / 2, ring)
        ring.strokeWidth = 1.2f * dp
        ring.color = 0x90000000.toInt()
        canvas.drawCircle(x, y, r + 0.6f * dp, ring)
        text.textSize = r * if (names == NoteNames.GREEK) 0.66f else 0.9f
        text.color = 0xFF2A1A08.toInt()
        canvas.drawText(labels[Math.floorMod(sh.instrument.midiAt(cell.course, cell.fret), 12)], x, y + text.textSize * 0.36f, text)
    }

    private fun onNeck(cell: Cell) =
        cell.course >= 0 && cell.course < instrument.courses.size && cell.fret >= 0 && cell.fret <= instrument.frets

    /** Draws a unit-space radial [paint] as a circle of radius [r] at ([x], [y]). */
    private fun unitCircle(canvas: Canvas, x: Float, y: Float, r: Float, paint: Paint) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(r, r)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restore()
    }

    private var maskDromos: Dromos? = null
    private var maskArpeggio = false
    private var maskBits = 0

    /** Bit d is set when the degree d semitones above the root is marked. Recomputed only when the scale changes. */
    private fun markMask(): Int {
        if (maskDromos != dromos || maskArpeggio != arpeggio) {
            maskDromos = dromos
            maskArpeggio = arpeggio
            val marked = if (arpeggio) ScalePath.arpeggio(dromos) else dromos.semitones
            maskBits = 0
            for (s in marked) maskBits = maskBits or (1 shl Math.floorMod(s, 12))
        }
        return maskBits
    }

    /** Sets course [course] swinging from [fret], [amp] as wide as a full pick. */
    private fun shake(course: Int, fret: Int, now: Long, amp: Float = 1f) {
        if (course !in 0 until MAX_COURSES) return
        pluckAt[course] = now
        pluckFret[course] = fret
        pluckAmp[course] = amp
    }

    private fun play(course: Int, fret: Int, velocity: Float) {
        synth.pluck(course, instrument.midiAt(course, fret), instrument.courses[course].octavePair, velocity)
        val now = SystemClock.uptimeMillis()
        if (course < MAX_COURSES && fret in 0 until CELLS) flashAt[course * CELLS + fret] = now
        shake(course, fret, now)
        lastPlay = now
        invalidate()
    }

    /** Copies the fingers in [held] into the flat arrays the drawing reads. */
    private fun syncHeld() {
        pressed.fill(false)
        ringing.fill(false)
        for ((c, f) in held.values) {
            if (c < MAX_COURSES && f in 0 until CELLS) { pressed[c * CELLS + f] = true; ringing[c] = true; heldFret[c] = f }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val handled = handleTouch(e)
        syncHeld()
        return handled
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        val sh = shape ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val cell = sh.cellAt(x, e.getY(i)) ?: return e.actionMasked != MotionEvent.ACTION_DOWN
                parent?.requestDisallowInterceptTouchEvent(true)
                val id = e.getPointerId(i)
                if (sh.overBody(x)) bodyTouch.add(id) else bodyTouch.remove(id)
                // One finger per course: a new finger on a ringing course replaces the old one.
                held.entries.removeAll { it.value.first == cell.first }
                held[id] = cell
                play(cell.first, cell.second, 0.9f)
                if (tremolo) scheduleTremolo()
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                val old = held[id] ?: continue
                val x = e.getX(i)
                // A fretting finger stays on the neck and a picking one over the body.
                val cell = sh.cellAt(x, e.getY(i))?.takeIf { sh.overBody(x) == (id in bodyTouch) } ?: continue
                if (cell != old) {
                    held.entries.removeAll { it.key != id && it.value.first == cell.first }
                    held[id] = cell
                    // Gliding along a course re-sounds it softly, like a slide; sweeping over the body strums.
                    play(cell.first, cell.second, if (cell.first == old.first) 0.55f else 0.85f)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                held.remove(id)
                bodyTouch.remove(id)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { held.clear(); bodyTouch.clear(); invalidate() }
        }
        return true
    }

    private val tremoloTick = object : Runnable {
        override fun run() {
            if (!tremolo) return
            val live = liveCell?.takeIf { onNeck(it) }
            if (held.isEmpty() && live == null) return
            tremoloUp = !tremoloUp
            val now = SystemClock.uptimeMillis()
            if (held.isNotEmpty()) for ((c, f) in held.values.distinct()) {
                synth.pluck(c, instrument.midiAt(c, f), instrument.courses[c].octavePair, if (tremoloUp) 0.62f else 0.74f)
                shake(c, f, now, 0.8f)
            } else if (live != null) {
                // The song's tremolo: the string keeps being picked (the song makes the sound itself).
                shake(live.course, live.fret, now, 0.7f)
            }
            invalidate()
            postDelayed(this, 68)
        }
    }

    private fun scheduleTremolo() {
        removeCallbacks(tremoloTick)
        postDelayed(tremoloTick, 68)
    }

    companion object {
        private const val MAX_COURSES = 8
        private const val CELLS = 40            // frets 0..39 per course in the flat arrays
        private const val FLASH_MS = 320L       // glow after a note is touched
        private const val VIBRATE_MS = 650L     // a plucked string swings this long
        private const val SPRITE_REACH = 1.5f   // a dot's shadow reaches this many radii from its middle
        private val EMPTY = RectF()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val LABELS = Array(NoteNames.entries.size) { n -> Array(12) { NoteNames.entries[n].name(it) } }
        /** One thread paints for every view, one painting at a time (each is a screenful of pixels). */
        private val painter: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "bouzouki-art").apply { isDaemon = true } }
        }
        /** The last painting made, kept only while memory allows, so turning back to the same screen shows it at once. */
        private var lastPainting: SoftReference<BouzoukiArt.Painting>? = null

        /** Frees a painting's pixels at once where that is safe (Android 8 on); before that the collector does it. */
        private fun release(b: Bitmap) {
            if (Build.VERSION.SDK_INT >= 26) b.recycle()
        }

        /** A shaded round face in unit space, lit from the upper left. */
        internal fun face(hi: Long, mid: Long, low: Long, edge: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(-0.35f, -0.42f, 1.5f,
                intArrayOf(hi.toInt(), mid.toInt(), low.toInt(), edge.toInt()), floatArrayOf(0f, 0.38f, 0.78f, 1f),
                Shader.TileMode.CLAMP)
        }
    }
}

/**
 * Paints for the strings and the dots, drawn both by the painter (once, on its thread) and by
 * [BouzoukiView] (each frame, and its dot sprites): each of them has its own set.
 */
internal class BouzoukiPens(private val dp: Float) {
    private val aa = Paint.ANTI_ALIAS_FLAG
    private val metal = Paint(aa).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val shadow = Paint(aa).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x5A000000 }
    private val wind = Paint(aa).apply { style = Paint.Style.STROKE; shader = windingShader() }
    private val shine = Paint(aa).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ghost = Paint(aa).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ring = Paint(aa).apply { style = Paint.Style.STROKE }
    private val flat = Paint(aa)
    private val dotShadow = Paint(aa).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0x90000000.toInt(), 0x60000000, 0x00000000), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
    }
    private val pearlFace = BouzoukiView.face(0xFFFFFFFA, 0xFFF4ECDC, 0xFFD8C8A8, 0xFF9C8662)
    private val rootFace = BouzoukiView.face(0xFFF08A70, 0xFFC0362A, 0xFF8E1A14, 0xFF5A0B0A)
    private val pressedFace = BouzoukiView.face(0xFFFFF4C8, 0xFFFFCF58, 0xFFE29C18, 0xFFA26608)

    fun colorOf(st: BouzoukiShape.Str, bright: Boolean) = when {
        bright -> if (st.wound) 0xFFF0DCA0.toInt() else 0xFFFFF2C8.toInt()
        st.wound -> WOUND
        else -> STEEL
    }

    /** A resting string from its post to its hook, with a shadow that falls further off towards the bridge, where it stands higher. */
    fun stillString(canvas: Canvas, sh: BouzoukiShape, st: BouzoukiShape.Str) {
        val o1 = 0.6f * dp
        val o2 = 2.4f * dp
        shadow.strokeWidth = st.width + 0.8f * dp
        canvas.drawLine(st.postX + o1, st.postY + o1 * 1.6f, sh.nutX + o1, st.nutY + o1 * 1.6f, shadow)
        canvas.drawLine(sh.nutX + o1, st.nutY + o1 * 1.6f, sh.bridgeX + o2, st.bridgeY + o2 * 1.6f, shadow)
        canvas.drawLine(sh.bridgeX + o2, st.bridgeY + o2 * 1.6f, st.hookX + o1, st.hookY + o1 * 1.6f, shadow)
        val color = colorOf(st, false)
        piece(canvas, st.postX, st.postY, sh.nutX, st.nutY, st, color)
        piece(canvas, sh.nutX, st.nutY, sh.bridgeX, st.bridgeY, st, color)
        piece(canvas, sh.bridgeX, st.bridgeY, st.hookX, st.hookY, st, color)
    }

    /** One straight piece of string: metal, the winding of a wound string, and a highlight along its top. */
    fun piece(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, st: BouzoukiShape.Str, color: Int) {
        metal.color = color
        metal.strokeWidth = st.width
        canvas.drawLine(x1, y1, x2, y2, metal)
        if (st.wound) {
            wind.strokeWidth = st.width
            canvas.drawLine(x1, y1, x2, y2, wind)
        }
        val off = st.width * 0.22f
        shine.strokeWidth = st.width * 0.36f
        shine.color = if (st.wound) 0x80FFFFFF.toInt() else 0xD0FFFFFF.toInt()
        canvas.drawLine(x1, y1 - off, x2, y2 - off, shine)
    }

    /** A swinging string along [p] (moving too fast to see its winding). */
    fun curve(canvas: Canvas, p: Path, st: BouzoukiShape.Str, color: Int) {
        metal.color = color
        metal.strokeWidth = st.width
        canvas.drawPath(p, metal)
    }

    /** The faint blur at one end of a swing, fading with [env]. */
    fun ghost(canvas: Canvas, p: Path, st: BouzoukiShape.Str, color: Int, env: Float) {
        ghost.strokeWidth = st.width
        ghost.color = (color and 0x00FFFFFF) or ((0x58 * env).toInt().coerceIn(0, 255) shl 24)
        canvas.drawPath(p, ghost)
    }

    /** The largest radius a dromos dot gets (still well under half the live note's token). */
    val maxMarkR = 4.4f * dp

    fun markR(sh: BouzoukiShape, fret: Int): Float {
        val x = sh.cellX(fret)
        return min(sh.spacing(x) * 0.26f, sh.cellW(fret) * 0.30f).coerceAtMost(maxMarkR)
    }

    /** One dromos dot of radius [r] at ([x], [y]): [kind] 0 a pearl dot, 1 the root in red lacquer, 2 another note (a small dark dot). */
    fun mark(canvas: Canvas, x: Float, y: Float, r: Float, kind: Int) {
        when (kind) {
            0 -> dot(canvas, x, y, r, pearlFace, 0xFF5E4A2E.toInt(), true)
            1 -> dot(canvas, x, y, r, rootFace, 0xFFE4BC58.toInt(), true)
            else -> {
                flat.color = 0xB0140D09.toInt()
                canvas.drawCircle(x, y, r * 0.62f, flat)
                ring.strokeWidth = r * 0.18f
                ring.color = 0x70E8DCC4
                canvas.drawCircle(x, y, r * 0.62f, ring)
            }
        }
    }

    fun pressedDot(canvas: Canvas, x: Float, y: Float, r: Float) = dot(canvas, x, y, r, pressedFace, 0xFF9A6008.toInt(), true)

    private fun dot(canvas: Canvas, x: Float, y: Float, r: Float, face: Paint, rim: Int, shadow: Boolean) {
        if (shadow) unit(canvas, x + 0.12f * r, y + 0.22f * r, 1.25f * r, dotShadow)
        unit(canvas, x, y, r, face)
        ring.strokeWidth = max(0.6f * dp, r * 0.16f)
        ring.color = rim
        canvas.drawCircle(x, y, r - ring.strokeWidth / 2, ring)
    }

    private fun unit(canvas: Canvas, x: Float, y: Float, r: Float, p: Paint) {
        canvas.save(); canvas.translate(x, y); canvas.scale(r, r); canvas.drawCircle(0f, 0f, 1f, p); canvas.restore()
    }

    private companion object {
        const val STEEL = 0xFFD2D5DA.toInt()
        const val WOUND = 0xFFB4A68C.toInt()

        /** Fine dark bands across a wound string, repeating along it (strings lie along x). */
        fun windingShader(): Shader {
            val stripes = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888)
            stripes.setPixels(intArrayOf(0x00000000, 0x30000000, 0x8C000000.toInt(), 0x30000000), 0, 4, 0, 0, 4, 1)
            return BitmapShader(stripes, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        }
    }
}

/**
 * Where every part of a whole bouzouki lies in a [width] by [height] view, in pixels. It is plain
 * arithmetic, made on the UI thread and read by the painting thread too, so it never changes
 * once made (the paths it hands out are new each time).
 *
 * The instrument lies along x, centred in the view less its padding [pad] (the room kept clear of
 * system bars and cut-outs): the headstock's tip at [left], the nut, the frets, the body and the
 * tailpiece. Lengths are real ones (centimetres) times [s] pixels per centimetre; only the neck
 * and the headstock are widened, [k] times, so the courses stay readable.
 */
internal class BouzoukiShape(val width: Int, val height: Int, val dp: Float, val instrument: Instrument, pad: Rect = Rect()) {
    val pad = Rect(pad)
    val courses = instrument.courses.size
    val frets = instrument.frets
    /** Real fingerboard width at the nut (six strings sit a little closer than eight). */
    private val nutCm = if (courses >= 4) 3.3f else 3.0f
    /** Distance from the nut to the last fret. */
    private val lastFretCm = SCALE_CM * (1f - 2f.pow(-frets / 12f))
    val s: Float
    val k: Float
    val left: Float
    val cy: Float

    init {
        val margin = MARGIN_DP * dp
        val w = width - pad.left - pad.right
        val h = height - pad.top - pad.bottom
        s = min((w - 2 * margin) / TOTAL_CM, (h - 2 * margin) / TALL_CM).coerceAtLeast(0.05f)
        k = (MIN_COURSE_DP * dp * courses / (nutCm * s)).coerceIn(1f, MAX_WIDEN)
        left = pad.left + (w - TOTAL_CM * s) / 2
        cy = pad.top + h / 2f - BOWL_DROP_CM * s / 2
    }

    /** Whether this is the layout for a [w] by [h] view of [inst] with padding [p] at density [d]. */
    fun matches(w: Int, h: Int, d: Float, inst: Instrument, p: Rect) = w == width && h == height && d == dp && inst == instrument && p == pad

    fun x(cm: Float) = left + cm * s

    val nutX = x(HEAD_CM)
    /** fretX[n] is the wire of fret n (fretX[0] is the nut's playing edge), at 12-TET distances from a 67.5 cm scale. */
    val fretX = FloatArray(frets + 1) { x(HEAD_CM + SCALE_CM * (1f - 2f.pow(-it / 12f))) }
    val bridgeX = x(HEAD_CM + SCALE_CM)
    val nutLen = NUT_CM * s
    /** Where the straight edges of the fingerboard stop, and the tip of its rounded point. */
    val boardEndX = x(HEAD_CM + lastFretCm + 0.35f)
    val boardTipX = boardEndX + 1.5f * s
    val holeX = x(HEAD_CM + HOLE_FROM_NUT_CM)
    val holeR = HOLE_R_CM * s
    val bodyX = x(HEAD_CM + SCALE_CM * (1f - 2f.pow(-JOINT_FRET / 12f)) - 0.6f)
    val bodyLen = BODY_LEN_CM * s
    val headHalf = s * max(3.6f, k * nutCm / 2 + 1.95f)
    /** Tuner posts along each side of the headstock, nearest the nut first. */
    val postX = FloatArray(courses) { x(HEAD_CM - 2.9f - it * if (courses >= 4) 3.25f else 4.1f) }
    val postInset = 1.1f * s
    val postR = 0.25f * s
    val buttonReach = 2.35f * s

    fun boardHalf(px: Float) = k * s * (nutCm + TAPER_CM * (px - nutX) / s / lastFretCm) / 2
    fun spacing(px: Float) = 2 * boardHalf(px) / courses
    fun courseY(c: Int, px: Float) = cy - (c - (courses - 1) / 2f) * spacing(px)
    /** String [str] of course [c]: 0 is the thick (or first) string, on the bass side below; 1 is above it. */
    fun stringY(c: Int, str: Int, px: Float) = courseY(c, px) + (if (str == 0) PAIR else -PAIR) * spacing(px)

    /** Where a finger stops the string for [fret]: the fret wire, or the nut for an open string. */
    fun stopX(fret: Int) = fretX[fret.coerceIn(0, frets)]
    /** The middle of a fret's cell; an open string's note sits on the nut. */
    fun cellX(fret: Int) = if (fret <= 0) nutX - nutLen / 2 else (fretX[fret - 1] + fretX[fret.coerceAtMost(frets)]) / 2
    fun cellW(fret: Int) = if (fret <= 0) 2f * s else fretX[fret.coerceAtMost(frets)] - fretX[fret - 1]

    /** Whether column [px] lies past the fingerboard, over the body, where the strings are picked. */
    fun overBody(px: Float) = px > boardEndX

    /**
     * The (course, fret) under a finger on the fingerboard; over the body, between the fingerboard and
     * the bridge (where a player picks), the open course under it; null anywhere else.
     */
    fun cellAt(px: Float, py: Float): Pair<Int, Int>? {
        if (px < nutX - nutLen - 1.2f * s || px > bridgeX - 0.6f * s) return null
        if (overBody(px)) {
            val sp = spacing(px)
            val c = ((cy - py) / sp + (courses - 1) / 2f).roundToInt()
            return if (c in 0 until courses && abs(py - courseY(c, px)) <= sp * 0.8f) c to 0 else null
        }
        if (abs(py - cy) > boardHalf(px) + 6 * dp) return null
        val c = ((cy - py) / spacing(px) + (courses - 1) / 2f).roundToInt().coerceIn(0, courses - 1)
        if (px < nutX) return c to 0
        for (n in 1..frets) if (px < fretX[n]) return c to n
        return c to frets
    }

    /** One string's way: from its tuning post over the nut to the bridge saddle and a hook of the tailpiece. */
    class Str(
        val course: Int, val index: Int, val wound: Boolean, val width: Float,
        val postX: Float, val postY: Float, val nutY: Float, val bridgeY: Float, val hookX: Float, val hookY: Float,
    )

    fun yOn(st: Str, px: Float) = st.nutY + (st.bridgeY - st.nutY) * (px - nutX) / (bridgeX - nutX)

    val strings: List<Str>

    init {
        val all = ArrayList<Pair<Int, Int>>()
        for (c in 0 until courses) for (i in 0..1) all.add(c to i)
        all.sortBy { (c, i) -> stringY(c, i, nutX) }
        // The upper half of the strings go to the posts along the top of the headstock, the lower half to the bottom;
        // the outermost string takes the post nearest the nut so none of them cross.
        val half = all.size / 2
        val hookX = x(TAIL_HOOK_CM)
        strings = all.mapIndexed { order, (c, i) ->
            val top = order < half
            val post = if (top) order else all.size - 1 - order
            val course = instrument.courses[c]
            val wound = course.octavePair && i == 0
            val w = if (wound) (1.45f + (60 - course.openMidi) * 0.045f) * dp
            else (0.95f + (66 - course.openMidi - if (course.octavePair) 12 else 0) * 0.02f).coerceIn(0.85f, 1.2f) * dp
            val py = if (top) cy - headHalf + postInset + postR else cy + headHalf - postInset - postR
            val by = stringY(c, i, bridgeX)
            Str(c, i, wound, w, postX[post], py, stringY(c, i, nutX), by, hookX, cy + (by - cy) * TAIL_SPREAD)
        }
    }

    // ---- The body: a pear (a teardrop), its shoulders swelling out of the neck, widest 60% of the way down, round at the tail. ----
    private val frontHalfCm = boardHalf(bodyX) / s + 0.2f

    /**
     * Half the width of the top, in cm, at [u] = 0 (the neck joint) to 1 (the tail). Below 0 the curve
     * goes on, narrowing under the fingerboard, so the top comes out from under it without a corner.
     */
    private fun bodyHalfCm(u: Float): Float = if (u < WIDEST) {
        val t = (WIDEST - u) / WIDEST
        BODY_HALF_CM - (BODY_HALF_CM - frontHalfCm) * t.pow(FULLNESS)
    } else {
        val v = ((u - WIDEST) / (1f - WIDEST)).coerceIn(0f, 1f)
        BODY_HALF_CM * sqrt(1f - v * v)
    }

    /** Where the outline starts: out of sight, a little inside the fingerboard's edge. */
    private val startU: Float = run {
        var u = 0f
        while (u > -0.1f && bodyHalfCm(u) * s > boardHalf(bodyX + u * bodyLen) - 0.4f * s) u -= 0.001f
        u
    }

    /** The top's outline, clockwise from the neck along the treble side and back: x, y and the outward normal. */
    private val rim = FloatArray((2 * OUTLINE + 2) * 4)
    private val rimCount: Int

    init {
        val pts = ArrayList<FloatArray>()
        val front = OUTLINE / 2
        for (i in 0..front) {
            // Closer together near the neck, where the curve turns fastest.
            val u = startU + (WIDEST - startU) * (i.toFloat() / front).pow(1.3f)
            pts.add(floatArrayOf(bodyX + u * bodyLen, bodyHalfCm(u) * s))
        }
        val tail = OUTLINE - front
        for (i in 1..tail) {
            val a = (PI / 2 * i / tail).toFloat()
            val u = WIDEST + (1f - WIDEST) * sin(a)
            pts.add(floatArrayOf(bodyX + u * bodyLen, BODY_HALF_CM * cos(a) * s))
        }
        val loop = ArrayList<FloatArray>()
        for (p in pts) loop.add(floatArrayOf(p[0], cy - p[1]))
        for (j in pts.size - 2 downTo 0) loop.add(floatArrayOf(pts[j][0], cy + pts[j][1]))
        rimCount = loop.size
        for (j in loop.indices) {
            val a = loop[(j - 1 + loop.size) % loop.size]
            val b = loop[(j + 1) % loop.size]
            var tx = b[0] - a[0]
            var ty = b[1] - a[1]
            val len = hypot(tx, ty).coerceAtLeast(1e-3f)
            tx /= len; ty /= len
            rim[j * 4] = loop[j][0]; rim[j * 4 + 1] = loop[j][1]
            rim[j * 4 + 2] = ty; rim[j * 4 + 3] = -tx
        }
    }

    /**
     * The top's outline pushed [delta] pixels outwards (inwards when negative) and [drop] pixels down. Both
     * fade out towards the neck, where the top meets the fingerboard and no bowl shows.
     */
    fun outline(delta: Float, drop: Float = 0f): Path {
        val p = Path()
        val x0 = rim[0]
        val ramp = OFFSET_RAMP_CM * s
        for (j in 0 until rimCount) {
            val f = ((rim[j * 4] - x0) / ramp).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            val x = rim[j * 4] + rim[j * 4 + 2] * delta * f
            val y = rim[j * 4 + 1] + (rim[j * 4 + 3] * delta + drop) * f
            if (j == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    /** Half the top's width at [px], or 0 off the body. */
    fun bodyHalf(px: Float): Float {
        val u = (px - bodyX) / bodyLen
        return if (u < startU || u > 1f) 0f else max(0f, bodyHalfCm(u) * s)
    }

    /** The fingerboard, [inset] pixels inside its edges: straight tapering sides and a rounded point over the top. */
    fun boardPath(inset: Float): Path {
        val p = Path()
        val l = nutX - nutLen
        val e = boardEndX
        val tip = boardTipX - inset
        val h0 = boardHalf(l) - inset
        val h1 = boardHalf(e) - inset
        p.moveTo(l, cy - h0)
        p.lineTo(e, cy - h1)
        p.cubicTo(e + (tip - e) * 0.55f, cy - h1, tip, cy - h1 * 0.5f, tip, cy)
        p.cubicTo(tip, cy + h1 * 0.5f, e + (tip - e) * 0.55f, cy + h1, e, cy + h1)
        p.lineTo(l, cy + h0)
        p.close()
        return p
    }

    /** The headstock: flaring from the nut, straight past the tuners, ending in a two-lobed crest with a point. */
    fun headPath(): Path = Path().apply {
        val hn = boardHalf(nutX)
        val hh = headHalf
        fun pt(cm: Float) = x(cm)
        val nut = nutX - nutLen * 0.5f
        moveTo(nut, cy - hn)
        cubicTo(nut - 1.2f * s, cy - hn, pt(HEAD_CM - 1.9f), cy - hh, pt(HEAD_CM - 3.1f), cy - hh)
        lineTo(pt(4.4f), cy - hh)
        cubicTo(pt(3.3f), cy - hh, pt(2.6f), cy - hh - 0.55f * s, pt(1.55f), cy - hh - 0.4f * s)
        cubicTo(pt(0.6f), cy - hh - 0.25f * s, pt(0.15f), cy - hh * 0.62f, pt(0.75f), cy - hh * 0.36f)
        cubicTo(pt(1.05f), cy - hh * 0.22f, pt(0.35f), cy - hh * 0.1f, pt(0f), cy)
        cubicTo(pt(0.35f), cy + hh * 0.1f, pt(1.05f), cy + hh * 0.22f, pt(0.75f), cy + hh * 0.36f)
        cubicTo(pt(0.15f), cy + hh * 0.62f, pt(0.6f), cy + hh + 0.25f * s, pt(1.55f), cy + hh + 0.4f * s)
        cubicTo(pt(2.6f), cy + hh + 0.55f * s, pt(3.3f), cy + hh, pt(4.4f), cy + hh)
        lineTo(pt(HEAD_CM - 3.1f), cy + hh)
        cubicTo(pt(HEAD_CM - 1.9f), cy + hh, nut - 1.2f * s, cy + hn, nut, cy + hn)
        close()
    }

    // ---- Free room for the video and the status line. ----

    /** How far the instrument reaches above (or below) the centre line at column [px]. */
    private fun reach(px: Float, below: Boolean): Float {
        if (px < x(-0.2f) || px > x(TOTAL_CM)) return 0f
        var r = 0f
        if (px <= nutX) {
            r = headHalf + 0.45f * s
            if (px >= postX.last() - 0.9f * s && px <= postX[0] + 0.9f * s) r = headHalf + buttonReach
        } else if (px <= boardTipX) r = boardHalf(px) + 0.2f * s
        val b = bodyHalf(px)
        if (b > 0f) r = max(r, b + (RIM_CM + if (below) BOWL_DROP_CM else -BOWL_DROP_CM) * s)
        if (px > x(TOTAL_CM - 1.6f)) r = max(r, 2.8f * s)
        return r
    }

    /** The rows the strings, the sounding courses and the notes' glows can reach. */
    val band = Rect(0, (cy - max(headHalf, boardHalf(bridgeX) + 38 * dp)).toInt() - 1, width,
        (cy + max(headHalf, boardHalf(bridgeX) + 38 * dp)).toInt() + 1)

    val video: RectF
    val status: RectF

    init {
        val m = MARGIN_DP * dp
        val gap = 6 * dp
        val step = max(1f, 2 * dp)
        val x0 = pad.left + m
        val y0 = pad.top + m
        val yEnd = height - pad.bottom - m
        val cols = ((width - pad.right - m - x0) / step).toInt().coerceAtLeast(0) + 1
        val top = FloatArray(cols) { cy - reach(x0 + it * step, false) }
        val bottom = FloatArray(cols) { cy + reach(x0 + it * step, true) }
        // The tallest 16:9 box that fits above the instrument, centred in the run of free columns it uses.
        var v = RectF()
        var h = cy - y0 - gap
        while (h >= 48 * dp && v.isEmpty) {
            val need = h * 16f / 9f
            var best = 0f
            var bestAt = 0f
            var start = -1
            for (i in 0 until cols) {
                if (top[i] >= y0 + h + gap) {
                    if (start < 0) start = i
                    val run = (i - start) * step
                    if (run > best) { best = run; bestAt = x0 + start * step }
                } else start = -1
            }
            if (best >= need) {
                val l = bestAt + (best - need) / 2
                v = RectF(l, y0, l + need, y0 + h)
            }
            h -= step
        }
        video = v
        // One line at the bottom left, under the neck: from the margin to the video's right edge or to just
        // before the body, whichever comes first (or as far as the room below the neck goes).
        val lineH = min(28 * dp, (yEnd - (cy + headHalf + buttonReach)).coerceAtLeast(0f))
        val y = yEnd - lineH
        val stop = min(if (v.isEmpty) Float.MAX_VALUE else v.right, bodyX - 8 * dp)
        var end = 0
        while (end < cols && bottom[end] + gap <= y && x0 + end * step <= stop) end++
        status = if (lineH < 14 * dp || end < 2) RectF() else RectF(x0, y, x0 + (end - 1) * step, y + lineH)
    }

    companion object {
        const val HEAD_CM = 18f           // headstock tip to nut
        const val SCALE_CM = 67.5f        // nut to saddle
        const val NUT_CM = 0.55f          // the nut's length along the strings
        const val TAPER_CM = 1.3f         // the fingerboard widens this much from the nut to the last fret
        const val JOINT_FRET = 17         // where the neck meets the body
        const val BODY_LEN_CM = 39f
        const val BODY_HALF_CM = 15f
        const val WIDEST = 0.6f           // where the body is widest, from the neck
        const val FULLNESS = 2.2f         // how quickly the body widens from the neck: higher is rounder
        const val OFFSET_RAMP_CM = 4f     // the bowl showing round the top fades out over this much towards the neck
        const val HOLE_FROM_NUT_CM = 58.4f
        const val HOLE_R_CM = 2.8f
        const val TAIL_HOOK_CM = 95.6f
        const val TOTAL_CM = 99.4f        // tip of the headstock to the end of the tailpiece
        const val RIM_CM = 0.7f           // the staved back showing round the top
        const val BOWL_DROP_CM = 0.35f    // ... a little more on the bass side, as if seen from slightly below
        const val TALL_CM = 2 * BODY_HALF_CM + 2 * RIM_CM
        const val MARGIN_DP = 8f
        const val MIN_COURSE_DP = 12f     // courses at least this far apart at the nut, if widening allows
        const val MAX_WIDEN = 1.6f
        const val PAIR = 0.17f            // half the gap between a course's two strings, in course spacings
        const val TAIL_SPREAD = 0.62f     // the tailpiece hooks are this much closer together than the saddle
        const val OUTLINE = 140           // points along each side of the body's outline
    }
}

/**
 * Paints everything of a [BouzoukiShape] that stands still into a bitmap: the leather it lies
 * on, its shadow, the staved back, the spruce top with binding, abalone purfling, rosette and
 * pickguard, the bridge, the tailpiece, the fingerboard with frets and pearl inlays, the nut, the
 * headstock with its tuners, and the resting strings. [BouzoukiView] draws what moves over it
 * each frame, the dromos dots included.
 */
internal object BouzoukiArt {
    /** A finished painting and the layout it was painted for. */
    class Painting(val bitmap: Bitmap, val shape: BouzoukiShape)

    /** Small tiles shared by every painting; size-independent, so made once per process. */
    private class Tiles(val leather: Bitmap, val pearl: Bitmap, val abalone: Bitmap, val tortoise: Bitmap)

    /** The spruce and ebony textures for the last size painted, kept so painting the same size again is quicker. */
    private class Woods(val spruceW: Int, val ebonyW: Int, val spruce: Bitmap, val ebony: Bitmap)

    @Volatile private var tiles: Tiles? = null
    @Volatile private var woods: Woods? = null
    private val making = Any()
    private const val MAX_SIDE = 4096f // larger bitmaps can't become one GPU texture
    private const val CREAM = 0xFFEDE2C6.toInt()

    private fun tiles(): Tiles = synchronized(making) {
        tiles ?: Tiles(NeckArt.leather(), NeckArt.pearl(), NeckArt.abalone(), tortoise()).also { tiles = it }
    }

    private fun woods(spruceW: Int, ebonyW: Int): Woods = synchronized(making) {
        woods?.takeIf { it.spruceW == spruceW && it.ebonyW == ebonyW }
            ?: Woods(spruceW, ebonyW, NeckArt.spruceTop(spruceW), NeckArt.fingerboard(ebonyW)).also { woods = it }
    }

    /** The still bouzouki for [sh]. Takes a few hundred milliseconds, so call it off the UI thread. */
    fun paint(sh: BouzoukiShape): Painting {
        val scale = min(1f, MAX_SIDE / max(sh.width, sh.height))
        val bmp = Bitmap.createBitmap(max(1, (sh.width * scale).toInt()), max(1, (sh.height * scale).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        Painter(sh, tiles(), c).paint()
        return Painting(bmp, sh)
    }

    /** Amber and dark brown flecks of tortoiseshell celluloid, a seamless tile. */
    private fun tortoise(): Bitmap {
        val n = 128
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val f = NeckArt.fbm(x * 0.0625f, y * 0.0625f, 71, 4, 8)
            val fine = NeckArt.fbm(x * 0.25f, y * 0.25f, 73, 2, 32)
            val t = ((f - 0.44f) / 0.2f + (fine - 0.5f) * 0.6f).coerceIn(0f, 1f)
            val tt = t * t * (3 - 2 * t)
            val r = 214f + (52f - 214f) * tt
            val g = 128f + (20f - 128f) * tt
            val b = 40f + (8f - 40f) * tt
            px[y * n + x] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    private class Painter(val sh: BouzoukiShape, val t: Tiles, val c: Canvas) {
        val s = sh.s
        val dp = sh.dp
        val cy = sh.cy
        val w = sh.width.toFloat()
        val h = sh.height.toFloat()
        val aa = Paint.ANTI_ALIAS_FLAG
        val top = sh.outline(0f)
        val board = sh.boardPath(0f)
        val head = sh.headPath()
        val pearl = tile(t.pearl, Shader.TileMode.MIRROR, max(0.5f, s / 40f))
        val shell = tile(t.abalone, Shader.TileMode.MIRROR, max(0.5f, s / 30f))
        /** The abalone toned down for the rings on the top, where it sits beside the honey spruce. */
        val softShell = Paint(shell).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0.7f)
                postConcat(ColorMatrix().apply { setScale(0.8f, 0.8f, 0.8f, 1f) })
            })
        }
        val inlayEdge = stroke(0xD0120A06.toInt(), max(0.6f, 0.05f * s))
        val spruceSpan = 2 * BouzoukiShape.BODY_HALF_CM * s
        val boardWide = 2 * sh.boardHalf(sh.boardEndX)
        val wood = woods(max(2, (spruceSpan / 2).toInt()), max(2, (boardWide / 2).toInt()))

        fun paint() {
            background()
            shadow()
            bowl()
            spruce()
            rosette()
            pickguard()
            bridge()
            tailpiece()
            neck()
            head()
            numbers()
            val pens = BouzoukiPens(dp)
            for (st in sh.strings) pens.stillString(c, sh, st)
        }

        private fun fill(color: Int) = Paint(aa).apply { this.color = color }
        private fun stroke(color: Int, width: Float) = Paint(aa).apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = width }
        private fun soft(color: Int, blur: Float) = Paint(aa).apply { this.color = color; maskFilter = BlurMaskFilter(max(0.5f, blur), BlurMaskFilter.Blur.NORMAL) }
        private fun tile(b: Bitmap, mode: Shader.TileMode, scale: Float) = Paint(aa or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(b, mode, mode).apply { setLocalMatrix(Matrix().apply { setScale(scale, scale) }) }
        }
        private fun linear(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray? = null) =
            Paint(aa).apply { shader = LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP) }

        /** Maps a texture whose grain runs down its y axis so the grain runs along x, its width spanning [y0] to [y0] + [span]. */
        private fun alongX(b: Bitmap, x0: Float, y0: Float, span: Float): Paint {
            val sc = span / b.width
            val m = Matrix()
            m.setValues(floatArrayOf(0f, sc, x0, sc, 0f, y0, 0f, 0f, 1f))
            return Paint(aa or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(b, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply { setLocalMatrix(m) }
            }
        }

        private fun shadowOf(p: Path, dx: Float, dy: Float, color: Int, blur: Float) {
            c.save(); c.translate(dx, dy); c.drawPath(p, soft(color, blur)); c.restore()
        }

        /** Dark leather under a soft studio light that falls off towards the corners. */
        private fun background() {
            c.drawRect(0f, 0f, w, h, tile(t.leather, Shader.TileMode.REPEAT, dp / 2))
            c.drawRect(0f, 0f, w, h, Paint().apply {
                shader = RadialGradient(w * 0.45f, h * 0.4f, max(w, h) * 0.7f,
                    intArrayOf(0x1CFFE8C8, 0x00000000, 0x8C000000.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            })
        }

        /** The instrument's shadow on the leather: a wide soft one and a close dark one. */
        private fun shadow() {
            val body = sh.outline(BouzoukiShape.RIM_CM * s, BouzoukiShape.BOWL_DROP_CM * s)
            for ((blur, off, col) in listOf(Triple(1.3f, 0.9f, 0x99000000.toInt()), Triple(0.3f, 0.25f, 0xB0000000.toInt()))) {
                for (p in listOf(body, board, head)) shadowOf(p, off * s * 0.55f, off * s, col, blur * s)
            }
        }

        /**
         * The staved bowl where it curves away under the top: narrow staves of maple and rosewood
         * with pale fillets between them, more of it showing on the bass side, darkening as it turns
         * away from the light.
         */
        private fun bowl() {
            val rim = BouzoukiShape.RIM_CM
            val drop = BouzoukiShape.BOWL_DROP_CM
            val bands = listOf(
                1.00f to 0xFF0E0805.toInt(), 0.95f to 0xFF3C200F.toInt(), 0.82f to 0xFFD9C08E.toInt(), 0.79f to 0xFFA8783F.toInt(),
                0.64f to 0xFF23120A.toInt(), 0.61f to 0xFF5C3119.toInt(), 0.45f to 0xFFE0CA9C.toInt(), 0.42f to 0xFFB4854B.toInt(),
                0.26f to 0xFF1A0E07.toInt(), 0.23f to 0xFF3A1E0E.toInt(),
            )
            for ((f, col) in bands) c.drawPath(sh.outline(f * rim * s, f * drop * s), fill(col))
            val outer = sh.outline(rim * s, drop * s)
            // Turning away from the light: brighter on the treble side, dark on the bass side and at the tail.
            c.drawPath(outer, linear(sh.bodyX, cy - 15f * s, sh.bodyX + sh.bodyLen * 0.4f, cy + 15f * s,
                intArrayOf(0x38FFF0D0, 0x00000000, 0x80000000.toInt()), floatArrayOf(0f, 0.45f, 1f)))
            c.save()
            c.clipPath(outer)
            c.drawPath(outer, stroke(0x90000000.toInt(), 0.5f * s).apply { maskFilter = BlurMaskFilter(0.25f * s, BlurMaskFilter.Blur.NORMAL) })
            c.restore()
        }

        /**
         * Honey spruce with its grain along the instrument, a pool of light, the bend behind the bridge, and
         * round its edge cream binding, black-white-black purfling and the band of abalone a Greek bouzouki has.
         */
        private fun spruce() {
            c.drawPath(top, alongX(wood.spruce, sh.bodyX, cy - spruceSpan / 2, spruceSpan))
            c.save()
            c.clipPath(top)
            // Aged a little towards amber, light falling on the lower bout.
            c.drawPath(top, fill(0x1CC07818))
            c.drawPath(top, Paint(aa).apply {
                shader = RadialGradient(sh.bodyX + sh.bodyLen * 0.55f, cy - 4f * s, 20f * s,
                    intArrayOf(0x30FFF6E0, 0x00FFF4DC, 0x34201000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            })
            // The top bends down a little behind the bridge, so that part catches less light.
            val bend = sh.x(BouzoukiShape.HEAD_CM + BouzoukiShape.SCALE_CM + 1.4f)
            c.drawRect(bend, 0f, w, h, Paint().apply {
                shader = LinearGradient(bend, 0f, bend + 2.6f * s, 0f, 0x00000000, 0x24000000, Shader.TileMode.CLAMP)
            })
            c.drawRect(bend - 0.15f * s, 0f, bend + 0.15f * s, h, fill(0x10FFF0D0))
            // A faint shade under the raised binding.
            c.drawPath(top, stroke(0x30301400, 1.6f * s).apply { maskFilter = BlurMaskFilter(0.4f * s, BlurMaskFilter.Blur.NORMAL) })
            // From the edge in: binding, black, white, black, abalone, black, white.
            val black = 0xFF140C08.toInt()
            val white = 0xFFF4ECDA.toInt()
            bands(top, floatArrayOf(0.30f, 0.045f, 0.04f, 0.045f, 0.24f, 0.04f, 0.035f),
                arrayOf(fill(CREAM), fill(black), fill(white), fill(black), Paint(softShell), fill(black), fill(white)))
            // The rounded binding catches the light along the top edge and falls into shade along the bottom.
            c.drawPath(top, Paint(aa).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.22f * s
                shader = LinearGradient(0f, cy - BouzoukiShape.BODY_HALF_CM * s, 0f, cy + BouzoukiShape.BODY_HALF_CM * s,
                    intArrayOf(0x50FFFFFF, 0x00FFFFFF, 0x40000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            })
            c.restore()
            c.drawPath(top, stroke(0xFF1A100A.toInt(), 0.06f * s))
        }

        /**
         * Concentric bands just inside [path] (the canvas clipped to it), [widths] in cm from the edge in: each
         * is a stroke along the edge twice as wide as how deep it reaches, the deepest drawn first.
         */
        private fun bands(path: Path, widths: FloatArray, paints: Array<Paint>) {
            var depth = widths.sum()
            for (i in widths.indices.reversed()) {
                paints[i].apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2 * depth * s
                    strokeJoin = Paint.Join.ROUND
                }
                c.drawPath(path, paints[i])
                depth -= widths[i]
            }
        }

        /**
         * The round soundhole with a rosette: bone and ebony lines, a ring of abalone, a wooden mosaic and a rope of
         * light and dark wood; through the hole, the inside of the staved bowl.
         */
        private fun rosette() {
            val x = sh.holeX
            val r = sh.holeR
            val black = 0xFF120B07.toInt()
            val cream = 0xFFEADFC4.toInt()
            shadowOf(Path().apply { addCircle(x, cy, r + 1.62f * s, Path.Direction.CW) }, 0.04f * s, 0.08f * s, 0x50000000, 0.1f * s)
            c.drawCircle(x, cy, r + 1.62f * s, fill(black))
            c.drawCircle(x, cy, r + 1.56f * s, fill(cream))
            c.drawCircle(x, cy, r + 1.46f * s, fill(black))
            val ropeR = r + 1.24f * s
            val ropeW = 0.38f * s
            c.drawCircle(x, cy, ropeR, stroke(0xFF5A3018.toInt(), ropeW))
            val seg = (2 * PI * ropeR / 72).toFloat()
            c.drawCircle(x, cy, ropeR, stroke(0xFFE2CB9C.toInt(), ropeW).apply { pathEffect = DashPathEffect(floatArrayOf(seg * 0.5f, seg * 0.5f), 0f) })
            c.drawCircle(x, cy, ropeR, stroke(0xFF2C5A44.toInt(), ropeW * 0.34f).apply { pathEffect = DashPathEffect(floatArrayOf(seg * 0.25f, seg * 0.75f), seg * 0.12f) })
            c.drawCircle(x, cy, r + 1.04f * s, fill(black))
            c.drawCircle(x, cy, r + 0.98f * s, fill(cream))
            c.drawCircle(x, cy, r + 0.92f * s, fill(black))
            mosaic(x, r + 0.62f * s, r + 0.88f * s)
            c.drawCircle(x, cy, r + 0.62f * s, fill(black))
            c.drawCircle(x, cy, r + 0.58f * s, softShell)
            c.drawCircle(x, cy, r + 0.58f * s, Paint(aa).apply {
                shader = RadialGradient(x - 0.3f * r, cy - 0.4f * r, r * 1.6f, intArrayOf(0x30FFFFFF, 0x00000000, 0x40000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            })
            c.drawCircle(x, cy, r + 0.28f * s, fill(black))
            c.drawCircle(x, cy, r + 0.22f * s, fill(cream))
            c.drawCircle(x, cy, r + 0.1f * s, fill(black))
            // The hole: the dark inside of the bowl, its far wall faintly lit.
            c.drawCircle(x, cy, r, Paint(aa).apply {
                shader = RadialGradient(x + 0.35f * r, cy + 0.45f * r, r * 1.3f, intArrayOf(0xFF3A2414.toInt(), 0xFF140C07.toInt(), 0xFF050302.toInt()),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            })
            c.save()
            c.clipPath(Path().apply { addCircle(x, cy, r, Path.Direction.CW) })
            // Inside: the seams of the bowl's staves running along it, and the shaded edge of the brace beyond the hole.
            val seam = stroke(0x2CE0B080, 0.06f * s)
            for (j in -4..4) {
                val y = cy + j * 0.8f * s
                c.drawLine(x - r, y + j * 0.15f * s, x + r, y - j * 0.05f * s, seam)
            }
            c.drawRect(x + r * 0.55f, cy - r, x + r * 0.82f, cy + r, fill(0x40000000))
            c.restore()
            c.drawCircle(x, cy, r - 0.08f * s, stroke(0x90000000.toInt(), 0.18f * s))
        }

        /** A ring of small tiles of bone, rosewood and green-stained wood between radii [r0] and [r1], in two rows half a tile apart. */
        private fun mosaic(x: Float, r0: Float, r1: Float) {
            val row = (r1 - r0) / 2
            val tiles = ((2 * PI * r1) / (0.14f * s)).toInt().coerceIn(24, 720) / 4 * 4
            val sweep = 360f / tiles
            val bone = 0xFFE6D3A6.toInt()
            val dark = 0xFF3A2414.toInt()
            val rows = arrayOf(intArrayOf(bone, dark, 0xFF8E3C1E.toInt(), dark), intArrayOf(dark, bone, dark, 0xFF2F5C44.toInt()))
            val p = Paint(aa).apply { style = Paint.Style.STROKE; strokeWidth = row + 0.5f }
            val oval = RectF()
            for (k in 0..1) {
                val rr = r0 + row * (k + 0.5f)
                oval.set(x - rr, cy - rr, x + rr, cy + rr)
                for (t in 0 until tiles) {
                    p.color = rows[k][t % 4]
                    c.drawArc(oval, t * sweep + k * sweep / 2, sweep + 0.3f, false, p)
                }
            }
        }

        /** The kapaki: a tortoiseshell crescent hugging the rosette towards the bridge, edged in cream, with a pearl flower. */
        private fun pickguard() {
            val x = sh.holeX
            val ri = sh.holeR + 1.62f * s + 0.2f * s
            val hornDeg = 80f
            val horn = hornDeg * PI.toFloat() / 180f
            val hx = x + ri * cos(horn)
            val hy = ri * sin(horn)
            val far = sh.bridgeX - 0.78f * s
            // The outer edge is a circle through both horns and the point nearest the bridge.
            val cx2 = (far * far - hx * hx - hy * hy) / (2 * (far - hx))
            val r2 = far - cx2
            val inner = RectF(x - ri, cy - ri, x + ri, cy + ri)
            val outer = RectF(cx2 - r2, cy - r2, cx2 + r2, cy + r2)
            val a2 = atan2(hy, hx - cx2) * 180f / PI.toFloat()
            val p = Path().apply {
                arcTo(outer, -a2, 2 * a2, true)
                arcTo(inner, hornDeg, -2 * hornDeg, false)
                close()
            }
            shadowOf(p, 0.08f * s, 0.14f * s, 0x70000000, 0.12f * s)
            c.drawPath(p, tile(t.tortoise, Shader.TileMode.REPEAT, max(0.5f, s / 46f)))
            c.drawPath(p, linear(x, cy - r2, far, cy + r2, intArrayOf(0x40FFF0D8, 0x00000000, 0x38000000)))
            c.drawPath(p, stroke(CREAM, 0.16f * s))
            c.drawPath(p, stroke(0xFF1A0E06.toInt(), 0.05f * s))
            // Pearl: a flower in the middle and small dots along the crescent.
            val mid = (x + ri + far) / 2
            val fl = flower(mid, cy, 0.85f * s)
            c.drawPath(fl, pearl); c.drawPath(fl, inlayEdge)
            c.drawCircle(mid, cy, 0.2f * s, shell)
            val dotR = (ri + (mid - x)) / 2
            for (deg in floatArrayOf(-58f, -38f, 38f, 58f)) {
                val a = deg * PI.toFloat() / 180f
                c.drawCircle(x + dotR * cos(a), cy + dotR * sin(a), 0.17f * s, pearl)
                c.drawCircle(x + dotR * cos(a), cy + dotR * sin(a), 0.17f * s, inlayEdge)
            }
        }

        /**
         * The floating ebony bridge: a straight bar about a centimetre wide, its rounded ends reaching past the
         * outer strings with a pearl dot in each, and the bone saddle the strings cross, notched for each.
         */
        private fun bridge() {
            val bx = sh.bridgeX
            val spread = sh.strings.maxOf { it.bridgeY } - sh.strings.minOf { it.bridgeY }
            val half = spread / 2 + 1.2f * s
            val f0 = bx - 0.45f * s
            val b0 = bx + 0.55f * s
            val end = (b0 - f0) / 2
            val p = Path().apply { addRoundRect(RectF(f0, cy - half, b0, cy + half), end, end, Path.Direction.CW) }
            shadowOf(p, 0.25f * s, 0.45f * s, 0x90000000.toInt(), 0.25f * s)
            // Lit on the neck side, the far side in shade; a soft sheen along the top.
            c.drawPath(p, linear(f0, 0f, b0, 0f, intArrayOf(0xFF5A4434.toInt(), 0xFF2E2118.toInt(), 0xFF140D09.toInt(), 0xFF1E140E.toInt()),
                floatArrayOf(0f, 0.4f, 0.75f, 1f)))
            c.drawPath(p, linear(0f, cy - half, 0f, cy + half, intArrayOf(0x30FFFFFF, 0x00FFFFFF, 0x30000000)))
            c.drawPath(p, stroke(0x50FFFFFF, 0.04f * s))
            c.drawPath(p, stroke(0x70000000, 0.03f * s))
            for (side in floatArrayOf(-1f, 1f)) {
                val ey = cy + side * (half - end)
                c.drawCircle(bx + 0.05f * s, ey, 0.16f * s, pearl)
                c.drawCircle(bx + 0.05f * s, ey, 0.16f * s, inlayEdge)
            }
            // Bone saddle: a cream strip with a lit edge, a little longer than the strings' spread, a notch under each string.
            val sHalf = spread / 2 + 0.4f * s
            val sw = 0.26f * s
            c.drawRoundRect(bx - sw * 0.5f, cy - sHalf, bx + sw * 0.5f, cy + sHalf, sw / 2, sw / 2,
                linear(bx - sw * 0.5f, 0f, bx + sw * 0.5f, 0f, intArrayOf(0xFFFFFAEC.toInt(), 0xFFE6D8B8.toInt(), 0xFF9C8B6E.toInt())))
            val notch = fill(0x70301E0A)
            for (st in sh.strings) c.drawRect(bx - sw * 0.5f, st.bridgeY - 0.045f * s, bx + sw * 0.5f, st.bridgeY + 0.045f * s, notch)
        }

        /** A shield of nickel: an arched front with a small point towards the bridge, sides widening to a round end over the rim. */
        private fun tailpieceShape(): Path {
            val x0 = sh.x(BouzoukiShape.TAIL_HOOK_CM - 0.75f)
            val xe = sh.x(BouzoukiShape.TOTAL_CM)
            val hw = 2.9f * s
            return Path().apply {
                moveTo(x0, cy)
                cubicTo(x0 + 0.35f * s, cy - 0.35f * s, x0 + 0.3f * s, cy - 1.7f * s, x0 + 0.55f * s, cy - 2.35f * s)
                cubicTo(x0 + 1.2f * s, cy - 2.55f * s, x0 + 2f * s, cy - hw, xe - 1.15f * s, cy - hw)
                cubicTo(xe - 0.4f * s, cy - hw, xe, cy - 1.6f * s, xe, cy)
                cubicTo(xe, cy + 1.6f * s, xe - 0.4f * s, cy + hw, xe - 1.15f * s, cy + hw)
                cubicTo(x0 + 2f * s, cy + hw, x0 + 1.2f * s, cy + 2.55f * s, x0 + 0.55f * s, cy + 2.35f * s)
                cubicTo(x0 + 0.3f * s, cy + 1.7f * s, x0 + 0.35f * s, cy + 0.35f * s, x0, cy)
                close()
            }
        }

        /** The nickel tailpiece over the end of the body: polished metal, an engraved border and shell, a hook for every string. */
        private fun tailpiece() {
            val p = tailpieceShape()
            shadowOf(p, 0.2f * s, 0.35f * s, 0xA0000000.toInt(), 0.25f * s)
            c.drawPath(p, linear(0f, cy - 2.9f * s, 0f, cy + 2.9f * s,
                intArrayOf(0xFF9A9EA4.toInt(), 0xFFF4F6F8.toInt(), 0xFFC4C8CE.toInt(), 0xFF84888E.toInt(), 0xFFD4D8DC.toInt(), 0xFF5E6268.toInt()),
                floatArrayOf(0f, 0.2f, 0.42f, 0.6f, 0.8f, 1f)))
            c.drawPath(p, linear(sh.x(BouzoukiShape.TAIL_HOOK_CM - 0.8f), 0f, sh.x(BouzoukiShape.TOTAL_CM), 0f,
                intArrayOf(0x00000000, 0x00000000, 0x50000000), floatArrayOf(0f, 0.6f, 1f)))
            c.drawPath(p, stroke(0xFF34363A.toInt(), 0.06f * s))
            val inner = Path(p).apply { transform(Matrix().apply { setScale(0.82f, 0.8f, sh.x(BouzoukiShape.TOTAL_CM - 1.4f), cy) }) }
            fun engrave(path: Path, width: Float) {
                c.drawPath(path, stroke(0x90303236.toInt(), width))
                c.save(); c.translate(0.03f * s, 0.05f * s); c.drawPath(path, stroke(0x80FFFFFF.toInt(), width * 0.7f)); c.restore()
            }
            engrave(inner, 0.06f * s)
            // A scallop shell engraved between the hooks and the end: ribs fanning from its hinge.
            val hx = sh.x(BouzoukiShape.TOTAL_CM - 0.85f)
            val shellR = 2.1f * s
            val ribs = Path()
            for (i in -3..3) {
                val a = PI.toFloat() + i * 0.22f
                ribs.moveTo(hx + 0.25f * s * cos(a), cy + 0.25f * s * sin(a))
                ribs.lineTo(hx + shellR * cos(a), cy + shellR * sin(a))
            }
            ribs.addArc(RectF(hx - shellR, cy - shellR, hx + shellR, cy + shellR), 180f - 42f, 84f)
            engrave(ribs, 0.05f * s)
            for (st in sh.strings) {
                c.drawCircle(st.hookX, st.hookY, 0.2f * s, fill(0xFF2A2C30.toInt()))
                c.drawCircle(st.hookX - 0.05f * s, st.hookY - 0.06f * s, 0.11f * s, fill(0xFFE8EAEE.toInt()))
                c.drawCircle(st.hookX, st.hookY, 0.24f * s, stroke(if (st.wound) 0xFF9C8E74.toInt() else 0xFFBFC3C8.toInt(), st.width))
            }
        }

        /** Ebony fingerboard on its shadow, cream binding and purfling, pearl inlays, nickel frets, and the bone nut. */
        private fun neck() {
            shadowOf(board, 0.12f * s, 0.26f * s, 0x90000000.toInt(), 0.18f * s)
            c.drawPath(board, alongX(wood.ebony, sh.nutX, cy - boardWide / 2, boardWide))
            val bind = 0.26f * s
            c.save()
            c.clipPath(board)
            c.drawPath(board, stroke(CREAM, 2 * bind))
            val p = max(0.6f, 0.055f * s)
            val purfl = sh.boardPath(bind + 1.5f * p)
            c.drawPath(purfl, stroke(0xFF0E0906.toInt(), 3 * p))
            c.drawPath(purfl, stroke(0xFFF2E9D6.toInt(), p))
            c.restore()
            inlays()
            frets()
            nut()
        }

        private fun inlays() {
            fun put(p: Path) { c.drawPath(p, pearl); c.drawPath(p, inlayEdge) }
            for (n in 1..sh.frets) {
                val x = sh.cellX(n)
                val half = sh.boardHalf(x)
                val z = min(sh.cellW(n) * 0.2f, half * 0.34f)
                when (n) {
                    3, 7, 15, 19 -> put(diamond(x, cy, z, z * 1.5f))
                    5, 10, 17, 22 -> { put(flower(x, cy, z * 1.3f)); c.drawCircle(x, cy, z * 0.34f, shell) }
                    12, 24 -> for (side in floatArrayOf(-1f, 1f)) {
                        val y = cy + side * half * 0.5f
                        put(diamond(x, y, z * 0.95f, z * 1.25f))
                        c.drawCircle(x, y, z * 0.28f, shell)
                    }
                }
            }
        }

        private fun frets() {
            val wire = max(1.3f * dp, 0.2f * s)
            val shade = Paint()
            for (n in 1..sh.frets) {
                val x = sh.fretX[n]
                val half = sh.boardHalf(x) - 0.14f * s
                // A soft shadow on the wood beside the wire, away from the light.
                shade.shader = LinearGradient(x, 0f, x + wire * 1.6f, 0f, 0x90000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
                c.drawRect(x, cy - half, x + wire * 1.6f, cy + half + 0.08f * s, shade)
                c.drawRoundRect(x - wire / 2, cy - half, x + wire / 2, cy + half, wire / 2, wire / 2,
                    linear(x - wire / 2, 0f, x + wire / 2, 0f,
                        intArrayOf(0xFF5A5650.toInt(), 0xFFFFFFFF.toInt(), 0xFFE2DED6.toInt(), 0xFFA9A59D.toInt(), 0xFF3A3632.toInt()),
                        floatArrayOf(0f, 0.3f, 0.48f, 0.8f, 1f)))
            }
        }

        private fun nut() {
            val r = sh.nutX
            val l = r - sh.nutLen
            val half = sh.boardHalf(r) + 0.06f * s
            c.drawRect(r, cy - half, r + 0.35f * s, cy + half, Paint().apply {
                shader = LinearGradient(r, 0f, r + 0.35f * s, 0f, 0x80000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            })
            c.drawRoundRect(l, cy - half, r, cy + half, 0.12f * s, 0.12f * s, linear(l, 0f, r, 0f,
                intArrayOf(0xFFA8977A.toInt(), 0xFFEDE2C8.toInt(), 0xFFFBF5E6.toInt(), 0xFFD6C6A2.toInt()), floatArrayOf(0f, 0.3f, 0.6f, 1f)))
            val slot = fill(0x8A2A1A0A.toInt())
            for (st in sh.strings) {
                val hw = st.width * 0.5f + 0.3f * dp
                c.drawRect(r - sh.nutLen * 0.5f, st.nutY - hw, r, st.nutY + hw, slot)
            }
        }

        /** Black headstock with cream binding and a pearl flower, machine tuners with pearloid buttons along both sides. */
        private fun head() {
            val hh = sh.headHalf
            // Tuner shafts and buttons stick out from under the headstock's edges.
            for (side in floatArrayOf(-1f, 1f)) for (px in sh.postX) {
                val y0 = cy + side * (hh - 0.4f * s)
                val y1 = cy + side * (hh + 0.85f * s)
                val by = cy + side * (hh + 1.6f * s)
                val btn = RectF(px - 0.52f * s, by - 0.78f * s, px + 0.52f * s, by + 0.78f * s)
                val shaft = RectF(px - 0.17f * s, min(y0, y1), px + 0.17f * s, max(y0, y1))
                c.save(); c.translate(0.18f * s, 0.32f * s)
                val sp = soft(0x90000000.toInt(), 0.18f * s)
                c.drawRect(shaft, sp)
                c.drawOval(btn, sp)
                c.restore()
                c.drawRect(shaft, linear(shaft.left, 0f, shaft.right, 0f,
                    intArrayOf(0xFF6C7076.toInt(), 0xFFF4F6F8.toInt(), 0xFF9A9EA4.toInt(), 0xFF4A4E54.toInt()), floatArrayOf(0f, 0.35f, 0.7f, 1f)))
                c.drawOval(btn, pearl)
                c.drawOval(btn, Paint(aa).apply {
                    shader = RadialGradient(px - 0.2f * s, by - side * 0.3f * s, 0.95f * s, intArrayOf(0x40FFFFFF, 0x10FFF4E0, 0x60402810), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                })
                c.drawOval(btn, stroke(0xA0302418.toInt(), 0.05f * s))
                val yc = cy + side * (hh + 0.85f * s)
                c.drawRoundRect(px - 0.3f * s, yc - 0.13f * s, px + 0.3f * s, yc + 0.13f * s, 0.08f * s, 0.08f * s,
                    linear(px - 0.3f * s, 0f, px + 0.3f * s, 0f, intArrayOf(0xFF7A7E84.toInt(), 0xFFF0F2F4.toInt(), 0xFF5A5E64.toInt())))
            }
            c.drawPath(head, linear(0f, cy - hh, 0f, cy + hh,
                intArrayOf(0xFF080605.toInt(), 0xFF2C241E.toInt(), 0xFF14100D.toInt(), 0xFF060504.toInt()), floatArrayOf(0.02f, 0.3f, 0.6f, 1f)))
            c.save()
            c.clipPath(head)
            c.drawPath(head, stroke(CREAM, 0.44f * s))
            c.restore()
            c.drawPath(head, stroke(0xFF140C08.toInt(), 0.05f * s))
            // A pearl flower in the crest between two leaves.
            val fx = sh.x(3.1f)
            val fl = flower(fx, cy, 0.95f * s)
            c.drawPath(fl, pearl); c.drawPath(fl, inlayEdge)
            c.drawCircle(fx, cy, 0.22f * s, shell)
            for (side in floatArrayOf(-1f, 1f)) {
                val lf = leaf(fx + 1.45f * s, cy + side * 0.5f * s, 1.4f * s, 0.4f * s, side * 18f)
                c.drawPath(lf, pearl); c.drawPath(lf, inlayEdge)
            }
            // Post bushings and posts, each with its string wound round it.
            val bush = Paint(aa).apply {
                shader = RadialGradient(0f, 0f, 1f, intArrayOf(0xFFE6E8EC.toInt(), 0xFF8E9298.toInt(), 0xFF3A3D42.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            }
            val post = Paint(aa).apply {
                shader = RadialGradient(-0.35f, -0.35f, 1.4f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFC9CCD0.toInt(), 0xFF6E7176.toInt(), 0xFF2E3034.toInt()),
                    floatArrayOf(0f, 0.3f, 0.75f, 1f), Shader.TileMode.CLAMP)
            }
            for (st in sh.strings) {
                val py = st.postY
                val px = st.postX
                c.drawCircle(px + 0.1f * s, py + 0.16f * s, 0.5f * s, soft(0x90000000.toInt(), 0.1f * s))
                unit(px, py, 0.46f * s, bush)
                c.drawCircle(px, py, sh.postR + st.width * 0.7f, stroke(if (st.wound) 0xFFA99C86.toInt() else 0xFFC9CDD2.toInt(), st.width * 1.6f))
                unit(px, py, sh.postR, post)
            }
        }

        private fun unit(x: Float, y: Float, r: Float, p: Paint) {
            c.save(); c.translate(x, y); c.scale(r, r); c.drawCircle(0f, 0f, 1f, p); c.restore()
        }

        /**
         * Fret numbers below the neck at the marked frets: gold on the leather, inked on the spruce, and on a dark
         * pill where one falls across the top's edge (17, where the neck meets the body, is a landmark players look for).
         */
        private fun numbers() {
            val tp = Paint(aa).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
            tp.textSize = (1.15f * s).coerceIn(9f * dp, 12.5f * dp)
            for (n in intArrayOf(3, 5, 7, 10, 12, 15, 17, 19, 22, 24)) {
                if (n > sh.frets) continue
                val label = n.toString()
                val x = sh.cellX(n)
                val y = cy + sh.boardHalf(x) + 0.45f * s + tp.textSize * 0.95f
                val half = tp.measureText(label) / 2 + 0.15f * s
                val ends = floatArrayOf(x - half, x + half)
                val inside = ends.all { sh.bodyHalf(it) > 0f && y + 0.3f * s < cy + sh.bodyHalf(it) - 0.6f * s }
                val outside = ends.all { sh.bodyHalf(it) == 0f || y - tp.textSize > cy + sh.bodyHalf(it) + 1.2f * s }
                val octave = n % 12 == 0
                if (inside) {
                    tp.color = 0xA0FFF4DC.toInt(); c.drawText(label, x, y + 0.9f * dp, tp)
                    tp.color = if (octave) 0xFF8E1A12.toInt() else 0xFF3E2610.toInt(); c.drawText(label, x, y, tp)
                    continue
                }
                if (!outside) {
                    val pill = RectF(x - half - 0.1f * s, y - tp.textSize * 0.86f, x + half + 0.1f * s, y + tp.textSize * 0.26f)
                    c.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, fill(0x90000000.toInt()))
                }
                tp.color = 0xC0000000.toInt(); c.drawText(label, x, y - 0.9f * dp, tp)
                tp.color = if (octave) 0xFFF6CF74.toInt() else 0xFFC2A066.toInt(); c.drawText(label, x, y, tp)
            }
        }

        private fun diamond(cx: Float, cy: Float, rx: Float, ry: Float) = Path().apply {
            moveTo(cx, cy - ry); lineTo(cx + rx, cy); lineTo(cx, cy + ry); lineTo(cx - rx, cy); close()
        }

        /** A four-petal flower: concave sides between four points, the old Greek inlay motif. */
        private fun flower(cx: Float, cy: Float, r: Float) = Path().apply {
            val k = r * 0.22f
            moveTo(cx, cy - r)
            quadTo(cx + k, cy - k, cx + r, cy)
            quadTo(cx + k, cy + k, cx, cy + r)
            quadTo(cx - k, cy + k, cx - r, cy)
            quadTo(cx - k, cy - k, cx, cy - r)
            close()
        }

        /** A pointed leaf [len] long and [wid] wide, centred at ([cx], [cy]) and turned [deg] degrees. */
        private fun leaf(cx: Float, cy: Float, len: Float, wid: Float, deg: Float) = Path().apply {
            moveTo(-len / 2, 0f)
            quadTo(0f, -wid, len / 2, 0f)
            quadTo(0f, wid, -len / 2, 0f)
            close()
            transform(Matrix().apply { setRotate(deg); postTranslate(cx, cy) })
        }
    }
}
