package com.bouzouki

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The bouzouki neck drawn vertically, nut at the top, so it scrolls down towards the
 * high frets. Touch a course anywhere along the neck to pluck it, slide along it to
 * glide from fret to fret, and use several fingers at once. The fret-number gutters
 * on both sides are left to the surrounding ScrollView for scrolling.
 *
 * It is painted like a real Greek bouzouki: an ebony fingerboard with cream binding and
 * mother-of-pearl inlays, nickel-silver frets, a bone nut under the black headstock, steel
 * and wound strings that shake when plucked, and the spruce top past the body joint.
 * Everything as tall as the neck is drawn with rects and lines; paths are kept small,
 * because Android 7 and 8 drop paths larger than a GPU texture.
 */
class FretboardView(context: Context, private val synth: BouzoukiSynth) : View(context) {
    var instrument = Instrument.TETRACHORDO
        set(v) { field = v; held.clear(); syncHeld(); requestLayout(); invalidate() }
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
    /**
     * Where the live note was hammered, pulled or slid from (on its course), drawn as a gold trail
     * along the string into it; null when the live note is picked.
     */
    var legatoFrom: Cell? = null
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
    private var tremoloUp = true

    // Per-cell and per-course state kept in flat arrays so drawing never boxes or allocates.
    private val pressed = BooleanArray(MAX_COURSES * CELLS)  // a finger is on this cell
    private val ringing = BooleanArray(MAX_COURSES)          // a finger is on this course
    private val flashAt = LongArray(MAX_COURSES * CELLS)     // when the cell was last played
    private val pluckAt = LongArray(MAX_COURSES)             // when the course was last plucked
    private val pluckFret = IntArray(MAX_COURSES)            // and where it was stopped
    private var lastPlay = 0L

    // ---- Paints and shaders, made once. Gradients are in unit space and placed with the canvas matrix. ----
    private val aa = Paint.ANTI_ALIAS_FLAG
    private val woodPaint = Paint(aa or Paint.FILTER_BITMAP_FLAG).apply { color = 0xFF22160F.toInt() }
    private val sprucePaint = Paint(aa or Paint.FILTER_BITMAP_FLAG).apply { color = 0xFFD5B27C.toInt() }
    private val leatherPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0xFF2A1C14.toInt() }
    private val pearlPaint = Paint(aa or Paint.FILTER_BITMAP_FLAG).apply { color = 0xFFEFE9DE.toInt() }
    private val abalonePaint = Paint(aa or Paint.FILTER_BITMAP_FLAG).apply { color = 0xFF3E7A78.toInt() }
    private val inlayEdge = Paint(aa).apply { style = Paint.Style.STROKE; color = 0xD0120A06.toInt() }
    private val veneerPaint = Paint(aa)
    private val edgeShadowLeft = Paint()
    private val edgeShadowRight = Paint()
    private val endShadow = Paint(aa).apply { style = Paint.Style.STROKE; color = 0x30000000 }
    private val bindingPaint = Paint(aa).apply { color = CREAM }
    private val bindingStroke = Paint(aa).apply { style = Paint.Style.STROKE; color = CREAM }
    private val purflingPaint = Paint(aa)
    private val purflingStroke = Paint(aa).apply { style = Paint.Style.STROKE }
    private val bodyEdgePaint = Paint(aa).apply { style = Paint.Style.STROKE }
    private val rosettePaint = Paint(aa).apply { style = Paint.Style.STROKE }
    private val holePaint = Paint(aa).apply { color = 0xFF0C0806.toInt() }
    private val wirePaint = Paint(aa).apply {
        shader = LinearGradient(0f, -0.5f, 0f, 0.5f,
            intArrayOf(0xFF5A5650.toInt(), 0xFFFFFFFF.toInt(), 0xFFE2DED6.toInt(), 0xFFA9A59D.toInt(), 0xFF3A3632.toInt()),
            floatArrayOf(0f, 0.3f, 0.48f, 0.8f, 1f), Shader.TileMode.CLAMP)
    }
    private val wireShadow = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, 1f, 0xB0000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
    }
    private val nutPaint = Paint(aa).apply {
        shader = LinearGradient(0f, 0f, 0f, 1f,
            intArrayOf(0xFFFBF5E6.toInt(), 0xFFEDE2C8.toInt(), 0xFFD6C6A2.toInt(), 0xFFA8977A.toInt()),
            floatArrayOf(0f, 0.35f, 0.8f, 1f), Shader.TileMode.CLAMP)
    }
    private val slotPaint = Paint(aa).apply { color = 0x8A2A1A0A.toInt() }
    private val stringShadow = Paint(aa).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x55000000 }
    private val stringPaint = Paint(aa).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val windPaint = Paint(aa).apply { style = Paint.Style.STROKE; shader = windingShader() }
    private val shinePaint = Paint(aa).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ghostString = Paint(aa).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val postPaint = Paint(aa).apply {
        shader = RadialGradient(-0.35f, -0.35f, 1.4f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFC9CCD0.toInt(), 0xFF6E7176.toInt(), 0xFF2E3034.toInt()),
            floatArrayOf(0f, 0.3f, 0.75f, 1f), Shader.TileMode.CLAMP)
    }
    private val bushingPaint = Paint(aa).apply {
        shader = RadialGradient(-0.3f, -0.35f, 1.4f,
            intArrayOf(0xFFB4B7BC.toInt(), 0xFF64676C.toInt(), 0xFF2E3033.toInt(), 0xFF141516.toInt()),
            floatArrayOf(0f, 0.35f, 0.8f, 1f), Shader.TileMode.CLAMP)
    }
    private val tokenShadow = Paint(aa).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0xA0000000.toInt(), 0x70000000, 0x00000000),
            floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
    }
    private val ivoryFace = face(0xFFFFFCF2, 0xFFF3E9D2, 0xFFDDCCA8, 0xFFB29C74)
    private val rootFace = face(0xFFE8705C, 0xFFB52A20, 0xFF8A1612, 0xFF5A0B0A)
    private val pressedFace = face(0xFFFFF4C8, 0xFFFFCF58, 0xFFE29C18, 0xFFA26608)
    private val liveFace = face(0xFFFFF8D8, 0xFFFFD866, 0xFFEAA824, 0xFFA87010)
    private val glowPaint = Paint(aa).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0xD8FFC860.toInt(), 0x66FFB030, 0x00FFA000),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }
    /** The legato trail: transparent where the finger was, gold where it is now (unit space, 0 to 1 down). */
    private val trailPaint = Paint(aa).apply {
        shader = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(0x00FFC860, 0x70FFC860, 0xE8FFD866.toInt()),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }
    private val ring = Paint(aa).apply { style = Paint.Style.STROKE }
    private val text = Paint(aa).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val ghostFill = Paint(aa).apply { color = 0xC8140D09.toInt() }
    private val faint = Paint(aa).apply {
        color = 0xE6E6D8BE.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val gutterText = Paint(aa).apply {
        textAlign = Paint.Align.CENTER; textSize = 13 * dp; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val focusFill = Paint(aa).apply { color = 0x2EFFDC8A }
    private val focusEdge = Paint(aa).apply { color = 0xB8F2C564.toInt(); style = Paint.Style.STROKE }

    // ---- Small shapes that follow the frets, rebuilt in place (no new objects) when the layout changes. ----
    private val clip = Rect()
    private val endPath = Path()       // the rounded point of the fingerboard over the soundhole
    private val endBindPath = Path()
    private val endPurflPath = Path()
    private val headPath = Path()
    private val headEdgePath = Path()
    private val shoulderPath = Path()
    private val bodyEdgePath = Path()
    private val inlays = Array(MAX_INLAYS) { Path() }
    private var inlayCount = 0
    private val shellDots = FloatArray(MAX_INLAYS * 3) // abalone centres: x, y, r
    private var shellDotCount = 0
    private val swing = FloatArray(SWING_STEPS * 4)     // a vibrating string as line segments
    private var geometryDirty = true
    private var artWidth = 0
    private var nutTop = 0f
    private var nutH = 6f
    private var boardEnd = 0f
    private var jointY = 0f
    private var bridgeY = 0f

    /** Fret spacing at zoom 1: shrinks like a real neck near the nut, levelling off at 46 dp so high frets stay playable. */
    private fun baseFretHeight(n: Int) = (46 + 58 * 0.9f.pow(n - 1)) * dp
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
        geometryDirty = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometryDirty = true
        // The fingerboard texture is half the board's width and needs at least two pixels.
        val woodW = (w - 2 * gutter).toInt() / 2
        if (w == artWidth || woodW < 2) return
        artWidth = w
        // Textures depend only on the width (they repeat down the neck), so zooming never rebuilds them.
        // The first time they take a moment to make: plain colours stand in until they are ready.
        val spruceW = w / 2
        val ready = NeckArt.cached(woodW, spruceW)
        if (ready != null) useTextures(ready) else {
            woodPaint.shader = null; sprucePaint.shader = null; leatherPaint.shader = null
            pearlPaint.shader = null; abalonePaint.shader = null
            Thread({
                // Should making them fail (say, out of memory) the plain colours simply stay.
                val made = try { NeckArt.textures(woodW, spruceW) } catch (e: Throwable) { null }
                if (made != null) mainHandler.post { if (artWidth == w) { useTextures(made); invalidate() } }
            }, "neck-textures").apply { isDaemon = true }.start()
        }
        veneerPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(0xFF050403.toInt(), 0xFF15100D.toInt(), 0xFF2A221C.toInt(), 0xFF120E0B.toInt(), 0xFF050403.toInt()),
            floatArrayOf(0.05f, 0.25f, 0.4f, 0.65f, 0.95f), Shader.TileMode.CLAMP)
        // The fingerboard stands a little above the leather and the top: a soft shadow beside it.
        val reach = 9 * dp
        edgeShadowLeft.shader = LinearGradient(gutter - reach, 0f, gutter, 0f, 0x00000000, 0x80000000.toInt(), Shader.TileMode.CLAMP)
        edgeShadowRight.shader = LinearGradient(w - gutter, 0f, w - gutter + reach, 0f, 0x80000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
    }

    /** Mirrored both ways: the wood and spruce bitmaps span the full width, and Skia draws mirror-mirror fastest. */
    private fun useTextures(t: NeckArt.Textures) {
        woodPaint.shader = BitmapShader(t.wood, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
            setLocalMatrix(Matrix().apply { setScale(2f, 2f); postTranslate(gutter, 0f) })
        }
        sprucePaint.shader = BitmapShader(t.spruce, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
            setLocalMatrix(Matrix().apply { setScale(2f, 2f) })
        }
        leatherPaint.shader = BitmapShader(t.leather, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(dp / 2f, dp / 2f) })
        }
        pearlPaint.shader = BitmapShader(t.pearl, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        abalonePaint.shader = BitmapShader(t.abalone, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
    }

    private val neckLeft get() = gutter
    private val neckRight get() = width - gutter
    private val courseWidth get() = (neckRight - neckLeft) / instrument.courses.size
    private fun courseX(c: Int) = neckLeft + courseWidth * (c + 0.5f)
    private fun cellTop(fret: Int) = if (fret == 0) 0f else fretTops[fret - 1]
    private fun cellBottom(fret: Int) = fretTops[fret]
    private fun cellCenterY(fret: Int) = if (fret == 0) openRow * 0.5f else (cellTop(fret) + cellBottom(fret)) / 2
    private fun cellIndex(course: Int, fret: Int) = course * CELLS + fret

    private val binding get() = 3.2f * dp
    private val purfling get() = 0.75f * dp
    private val endTip get() = 14 * dp
    /** Where the straight part of the fingerboard stops and its rounded point begins. */
    private val straightEnd get() = boardEnd - endTip

    /** Rebuilds the small shapes that follow the frets: the board's end, inlays, headstock and body shoulders. */
    private fun buildGeometry() {
        geometryDirty = false
        val w = width.toFloat()
        val cx = w / 2
        nutH = (6 * dp * min(1f, openRow / (64 * dp)).pow(0.5f)).coerceAtLeast(3.5f * dp)
        nutTop = openRow - nutH
        boardEnd = fretTops.last() + 12 * dp
        bridgeY = boardEnd + 0.3f * (boardEnd - openRow)
        jointY = fretTops[min(JOINT_FRET, instrument.frets)]

        boardTip(endPath, 0f, closed = true)
        boardTip(endBindPath, binding / 2, closed = false)
        boardTip(endPurflPath, binding + purfling * 1.5f, closed = false)

        // Headstock: black veneer flaring out above the nut.
        val flare = min(gutter * 0.62f, nutTop * 0.55f)
        val l0 = neckLeft
        val r0 = neckRight
        headPath.reset()
        headPath.moveTo(l0, nutTop + 1)
        headPath.cubicTo(l0, nutTop * 0.5f, l0 - flare * 0.7f, nutTop * 0.25f, l0 - flare, -1f)
        headPath.lineTo(r0 + flare, -1f)
        headPath.cubicTo(r0 + flare * 0.7f, nutTop * 0.25f, r0, nutTop * 0.5f, r0, nutTop + 1)
        headPath.close()
        val b = binding / 2
        headEdgePath.reset()
        headEdgePath.moveTo(l0 + b, nutTop)
        headEdgePath.cubicTo(l0 + b, nutTop * 0.5f, l0 - flare * 0.7f + b, nutTop * 0.25f, l0 - flare + b, 0f)
        headEdgePath.moveTo(r0 - b, nutTop)
        headEdgePath.cubicTo(r0 - b, nutTop * 0.5f, r0 + flare * 0.7f - b, nutTop * 0.25f, r0 + flare - b, 0f)

        // The spruce top past the body joint; its shoulders curve gently away from the neck.
        val drop = SHOULDER_DROP_DP * dp
        shoulderPath.reset()
        shoulderPath.moveTo(0f, jointY + drop + 1)
        shoulderPath.quadTo(l0 * 0.55f, jointY, l0, jointY)
        shoulderPath.lineTo(l0, jointY + drop + 1)
        shoulderPath.close()
        shoulderPath.moveTo(w, jointY + drop + 1)
        shoulderPath.quadTo(r0 + (w - r0) * 0.45f, jointY, r0, jointY)
        shoulderPath.lineTo(r0, jointY + drop + 1)
        shoulderPath.close()
        val e = 1.5f * dp
        bodyEdgePath.reset()
        bodyEdgePath.moveTo(-1f, jointY + drop + e)
        bodyEdgePath.quadTo(l0 * 0.55f, jointY + e, l0, jointY + e)
        bodyEdgePath.moveTo(r0, jointY + e)
        bodyEdgePath.quadTo(r0 + (w - r0) * 0.45f, jointY + e, w + 1, jointY + drop + e)

        // Mother-of-pearl inlays: diamonds, four-petal flowers, and doubles at 12 and 24. One small path each.
        inlayCount = 0
        shellDotCount = 0
        val bw = neckRight - neckLeft
        for (n in 1..instrument.frets) {
            val cy = cellCenterY(n)
            val s = min(fretHeight(n) * 0.22f, 13 * dp)
            when (n) {
                3, 7, 15, 19 -> diamond(nextInlay(), cx, cy, s * 1.5f, s)
                5, 10, 17, 22 -> { flower(nextInlay(), cx, cy, s * 1.25f); shellDot(cx, cy, s * 0.28f) }
                12, 24 -> {
                    val xl = neckLeft + bw * 0.25f
                    val xr = neckLeft + bw * 0.75f
                    diamond(nextInlay(), xl, cy, s * 1.35f, s * 0.95f)
                    diamond(nextInlay(), xr, cy, s * 1.35f, s * 0.95f)
                    flower(nextInlay(), cx, cy, s * 0.8f)
                    shellDot(xl, cy, s * 0.26f)
                    shellDot(xr, cy, s * 0.26f)
                }
            }
        }
        if (openRow >= 40 * dp) { // a pearl flower on the headstock face
            val s = min(nutTop * 0.24f, 13 * dp)
            flower(nextInlay(), cx, nutTop * 0.42f, s)
            shellDot(cx, nutTop * 0.42f, s * 0.3f)
        }
    }

    private fun nextInlay(): Path = inlays[min(inlayCount++, MAX_INLAYS - 1)].apply { reset() }

    private fun shellDot(x: Float, y: Float, r: Float) {
        if (shellDotCount >= MAX_INLAYS) return
        shellDots[shellDotCount * 3] = x
        shellDots[shellDotCount * 3 + 1] = y
        shellDots[shellDotCount * 3 + 2] = r
        shellDotCount++
    }

    /** The rounded point at the end of the fingerboard, [inset] inside its edge, from where its straight sides stop. */
    private fun boardTip(p: Path, inset: Float, closed: Boolean) {
        val tip = endTip
        val e = boardEnd - inset
        val cx = width / 2f
        val l = neckLeft + inset
        val r = neckRight - inset
        val top = straightEnd - if (closed) 1f else 0f
        p.reset()
        p.moveTo(l, top)
        p.lineTo(l, e - tip)
        p.cubicTo(l, e - tip * 0.35f, cx - (cx - l) * 0.45f, e, cx, e)
        p.cubicTo(cx + (r - cx) * 0.45f, e, r, e - tip * 0.35f, r, e - tip)
        p.lineTo(r, top)
        if (closed) p.close()
    }

    private fun diamond(p: Path, cx: Float, cy: Float, rx: Float, ry: Float) {
        p.moveTo(cx, cy - ry); p.lineTo(cx + rx, cy); p.lineTo(cx, cy + ry); p.lineTo(cx - rx, cy); p.close()
    }

    /** A four-petal flower: concave sides between four points, the old Greek inlay motif. */
    private fun flower(p: Path, cx: Float, cy: Float, r: Float) {
        val k = r * 0.22f
        p.moveTo(cx, cy - r)
        p.quadTo(cx + k, cy - k, cx + r, cy)
        p.quadTo(cx + k, cy + k, cx, cy + r)
        p.quadTo(cx - k, cy + k, cx - r, cy)
        p.quadTo(cx - k, cy - k, cx, cy - r)
        p.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (fretTops.isEmpty() || width <= 2 * gutter) return
        if (geometryDirty) buildGeometry()
        val now = SystemClock.uptimeMillis()
        canvas.getClipBounds(clip)
        val top = clip.top.toFloat()
        val bottom = clip.bottom.toFloat()

        drawSurroundings(canvas)
        drawBoard(canvas)
        drawFocus(canvas)
        drawInlays(canvas, top, bottom)
        drawFrets(canvas, top, bottom)
        drawNut(canvas)
        val vibrating = drawStrings(canvas, now)
        drawFretNumbers(canvas, top, bottom)
        drawNotes(canvas, now, top, bottom)
        drawSongMarks(canvas)

        if (vibrating || now - lastPlay < FLASH_MS) postInvalidateOnAnimation()
    }

    /** Leather around the neck, the spruce top with its soundhole rosette, and the headstock. */
    private fun drawSurroundings(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Only where they show: the fingerboard covers the middle.
        val body = jointY + SHOULDER_DROP_DP * dp
        canvas.drawRect(0f, 0f, neckLeft, body, leatherPaint)
        canvas.drawRect(neckRight, 0f, w, body, leatherPaint)
        canvas.drawRect(0f, body, neckLeft, h, sprucePaint)
        canvas.drawRect(neckRight, body, w, h, sprucePaint)
        canvas.drawRect(neckLeft, straightEnd, neckRight, h, sprucePaint)
        canvas.drawPath(shoulderPath, sprucePaint)
        // Body binding: cream edge with a black line inside.
        bodyEdgePaint.strokeWidth = 3 * dp
        bodyEdgePaint.color = 0xFF1A120C.toInt()
        canvas.drawPath(bodyEdgePath, bodyEdgePaint)
        bodyEdgePaint.strokeWidth = 1.6f * dp
        bodyEdgePaint.color = CREAM
        canvas.save()
        canvas.translate(0f, -0.9f * dp)
        canvas.drawPath(bodyEdgePath, bodyEdgePaint)
        canvas.restore()
        // The soundhole just past the end of the fingerboard, ringed by a wooden mosaic rosette.
        val cx = w / 2
        val hole = (neckRight - neckLeft) * 0.36f
        val cy = boardEnd + hole * 0.55f
        canvas.drawCircle(cx, cy, hole, holePaint)
        rosettePaint.strokeWidth = 1.5f * dp
        for (i in ROSETTE.indices) {
            rosettePaint.color = ROSETTE[i]
            canvas.drawCircle(cx, cy, hole + (2f + i * 1.6f) * dp, rosettePaint)
        }
        // Headstock veneer with a cream binding on its flaring sides.
        canvas.drawPath(headPath, veneerPaint)
        bindingStroke.strokeWidth = binding
        canvas.drawPath(headEdgePath, bindingStroke)
        // A soft shadow where the fingerboard sits above the leather and the top.
        val reach = 9 * dp
        canvas.drawRect(neckLeft - reach, nutTop, neckLeft, straightEnd, edgeShadowLeft)
        canvas.drawRect(neckRight, nutTop, neckRight + reach, straightEnd, edgeShadowRight)
        endShadow.strokeWidth = 10 * dp
        canvas.drawPath(endPath, endShadow)
        endShadow.strokeWidth = 5 * dp
        canvas.drawPath(endPath, endShadow)
    }

    /** Ebony (its rounded sheen is in the texture), cream binding and black-white-black purfling along both edges. */
    private fun drawBoard(canvas: Canvas) {
        val l = neckLeft
        val r = neckRight
        val end = straightEnd
        canvas.drawRect(l, nutTop, r, end, woodPaint)
        canvas.drawPath(endPath, woodPaint)
        canvas.drawRect(l, nutTop, l + binding, end, bindingPaint)
        canvas.drawRect(r - binding, nutTop, r, end, bindingPaint)
        bindingStroke.strokeWidth = binding
        canvas.drawPath(endBindPath, bindingStroke)
        val p = purfling
        purflingPaint.color = 0xFF0E0906.toInt()
        canvas.drawRect(l + binding, nutTop, l + binding + 3 * p, end, purflingPaint)
        canvas.drawRect(r - binding - 3 * p, nutTop, r - binding, end, purflingPaint)
        purflingStroke.strokeWidth = 3 * p
        purflingStroke.color = 0xFF0E0906.toInt()
        canvas.drawPath(endPurflPath, purflingStroke)
        purflingPaint.color = 0xFFF2E9D6.toInt()
        canvas.drawRect(l + binding + p, nutTop, l + binding + 2 * p, end, purflingPaint)
        canvas.drawRect(r - binding - 2 * p, nutTop, r - binding - p, end, purflingPaint)
        purflingStroke.strokeWidth = p
        purflingStroke.color = 0xFFF2E9D6.toInt()
        canvas.drawPath(endPurflPath, purflingStroke)
    }

    /** Where the scale player will play: one course, or one hand position. */
    private fun drawFocus(canvas: Canvas) {
        focusEdge.strokeWidth = 2 * dp
        val corner = 8 * dp
        val bottom = boardEnd - 6 * dp
        focusCourse?.takeIf { it >= 0 && it < instrument.courses.size }?.let { c ->
            val l = neckLeft + courseWidth * c + 4 * dp
            val r = l + courseWidth - 8 * dp
            canvas.drawRoundRect(l, 3 * dp, r, bottom, corner, corner, focusFill)
            canvas.drawRoundRect(l, 3 * dp, r, bottom, corner, corner, focusEdge)
        }
        focusFrets?.let { range ->
            val first = range.first.coerceIn(0, instrument.frets)
            val last = range.last.coerceIn(0, instrument.frets)
            val t = cellTop(first) + 3 * dp
            val b = cellBottom(last) - 3 * dp
            val l = neckLeft + binding + 3 * dp
            val r = neckRight - binding - 3 * dp
            canvas.drawRoundRect(l, t, r, b, corner, corner, focusFill)
            canvas.drawRoundRect(l, t, r, b, corner, corner, focusEdge)
        }
    }

    /** Pearl shapes outlined in black mastic, with abalone hearts. */
    private fun drawInlays(canvas: Canvas, top: Float, bottom: Float) {
        inlayEdge.strokeWidth = 0.9f * dp
        for (i in 0 until min(inlayCount, MAX_INLAYS)) {
            canvas.drawPath(inlays[i], pearlPaint)
            canvas.drawPath(inlays[i], inlayEdge)
        }
        for (i in 0 until shellDotCount) {
            val y = shellDots[i * 3 + 1]
            if (y < top - 30 * dp || y > bottom + 30 * dp) continue
            canvas.drawCircle(shellDots[i * 3], y, shellDots[i * 3 + 2], abalonePaint)
        }
    }

    /** Nickel-silver fret wires: a shadow on the wood below, then a rounded crown with a highlight. */
    private fun drawFrets(canvas: Canvas, top: Float, bottom: Float) {
        val wire = if (zoom < 0.5f) 2.6f * dp else 3.6f * dp
        val shadow = wire * 1.5f
        val l = neckLeft + binding * 0.6f
        val r = neckRight - binding * 0.6f
        for (n in 1..instrument.frets) {
            val y = fretTops[n]
            if (y + shadow < top || y - wire > bottom) continue
            canvas.save()
            canvas.translate(0f, y + wire * 0.3f)
            canvas.scale(1f, shadow)
            canvas.drawRect(l, 0f, r, 1f, wireShadow)
            canvas.restore()
            canvas.save()
            canvas.translate(0f, y)
            canvas.scale(1f, wire)
            canvas.drawRoundRect(l, -0.5f, r, 0.5f, wire * 0.5f, 0.5f, wirePaint)
            canvas.restore()
        }
    }

    /** The bone nut, with a slot for every string and a shadow on the first fret. */
    private fun drawNut(canvas: Canvas) {
        val l = neckLeft
        val r = neckRight
        canvas.save()
        canvas.translate(0f, openRow)
        canvas.scale(1f, 4 * dp)
        canvas.drawRect(l, 0f, r, 1f, wireShadow)
        canvas.restore()
        canvas.save()
        canvas.translate(0f, nutTop)
        canvas.scale(1f, nutH)
        canvas.drawRoundRect(l - dp, 0f, r + dp, 1f, 1.5f * dp, 0.25f, nutPaint)
        canvas.restore()
        for (c in instrument.courses.indices) for (s in 0..1) {
            val x = stringX(c, s)
            val half = stringWidth(c, s) * 0.5f + 0.5f * dp
            canvas.drawRect(x - half, nutTop, x + half, nutTop + nutH * 0.45f, slotPaint)
        }
    }

    private fun stringX(course: Int, s: Int) = courseX(course) + if (s == 0) -STRING_GAP_DP * dp else STRING_GAP_DP * dp

    /** Strings get thinner as they go up; the thick string of an octave course is wound. */
    private fun stringWidth(course: Int, s: Int): Float {
        val c = instrument.courses[course]
        return if (c.octavePair && s == 0) (2.2f + (60 - c.openMidi) * 0.07f) * dp
        else (1.2f + (66 - c.openMidi - if (c.octavePair) 12 else 0) * 0.03f).coerceIn(1.05f, 1.6f) * dp
    }

    /**
     * The string pairs. Octave courses have a thick wound string and a thin plain one. A plucked
     * course shakes from where it is stopped down to the bridge for a little over half a second.
     * Returns whether any string is still moving.
     */
    private fun drawStrings(canvas: Canvas, now: Long): Boolean {
        val h = height.toFloat()
        var moving = false
        for (c in instrument.courses.indices) {
            val course = instrument.courses[c]
            val age = now - pluckAt[c]
            val fret = pluckFret[c].coerceIn(0, instrument.frets)
            val shaking = pluckAt[c] > 0 && age < VIBRATE_MS
            if (shaking) moving = true
            for (s in 0..1) {
                val wound = course.octavePair && s == 0
                val x = stringX(c, s)
                val sw = stringWidth(c, s)
                val nutMid = nutTop + nutH * 0.5f
                val postY = postY(c, s)
                val color = when {
                    ringing[c] -> if (wound) 0xFFF0DCA0.toInt() else 0xFFFFF2C8.toInt()
                    wound -> WOUND
                    else -> STEEL
                }
                stringPaint.color = color
                val stop = if (shaking) (if (fret == 0) openRow else fretTops[fret]) else h
                drawStringLine(canvas, fanX(x, postY), postY, x, nutMid, sw, wound)
                drawStringLine(canvas, x, nutMid, x, stop, sw, wound)
                if (shaking) {
                    // Decaying sideways swing; the thin octave string swings twice as fast.
                    val t = age.toFloat()
                    val env = exp(-t / 190f)
                    val period = if (course.octavePair && s == 1) 42f else 84f
                    val amp = 5.5f * dp * env * cos(2f * PI.toFloat() * t / period + s * 0.9f)
                    // Faint ghosts at both ends of the swing, where a real string blurs.
                    ghostString.strokeWidth = sw
                    ghostString.color = (color and 0x00FFFFFF) or ((0x48 * env).toInt().coerceIn(0, 255) shl 24)
                    val ghostCount = buildSwing(x, stop, h, 4.5f * dp * env)
                    canvas.drawLines(swing, 0, ghostCount, ghostString)
                    buildSwing(x, stop, h, -4.5f * dp * env)
                    canvas.drawLines(swing, 0, ghostCount, ghostString)
                    val count = buildSwing(x, stop, h, amp)
                    drawStringSegments(canvas, count, sw, wound)
                }
            }
        }
        // The nearest tuning posts hold the outermost strings; the others run on up the headstock.
        if (showPosts) for (side in 0..1) {
            val c = if (side == 0) 0 else instrument.courses.size - 1
            val wound = instrument.courses[c].octavePair && side == 0
            val py = postY(c, side)
            val px = fanX(stringX(c, side), py)
            unitCircle(canvas, px + 1.2f * dp, py + 1.8f * dp, 7.5f * dp, tokenShadow)
            unitCircle(canvas, px, py, 6.2f * dp, bushingPaint)
            ring.strokeWidth = stringWidth(c, side) + 0.6f * dp
            ring.color = if (wound) WOUND else STEEL
            canvas.drawCircle(px, py, 3.5f * dp + ring.strokeWidth / 2, ring)
            unitCircle(canvas, px, py, 3.5f * dp, postPaint)
        }
        return moving
    }

    private val showPosts get() = openRow >= 40 * dp

    /** Where a string ends at the top: on a tuning post for the two outermost strings, else off the top edge. */
    private fun postY(c: Int, s: Int): Float {
        val outer = (c == 0 && s == 0) || (c == instrument.courses.size - 1 && s == 1)
        return if (outer && showPosts) nutTop * 0.22f else 0f
    }

    /** From the nut the strings fan out slightly towards their tuners. */
    private fun fanX(x: Float, y: Float) = x + (x - width / 2f) * FAN * (nutTop + nutH * 0.5f - y) / (58 * dp)

    private fun drawStringLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, sw: Float, wound: Boolean) {
        if (y2 <= y1) return
        val off = 2.4f * dp
        stringShadow.strokeWidth = sw + 1.2f * dp
        canvas.drawLine(x1 + off, y1 + off * 0.5f, x2 + off, y2 + off * 0.5f, stringShadow)
        stringPaint.strokeWidth = sw
        canvas.drawLine(x1, y1, x2, y2, stringPaint)
        if (wound) {
            windPaint.strokeWidth = sw
            canvas.drawLine(x1, y1, x2, y2, windPaint)
        }
        shinePaint.strokeWidth = sw * 0.34f
        shinePaint.color = if (wound) 0x88FFFFFF.toInt() else 0xD8FFFFFF.toInt()
        canvas.drawLine(x1 - sw * 0.2f, y1, x2 - sw * 0.2f, y2, shinePaint)
    }

    /** Draws the first [count] floats of [swing] as a string: shadow, metal, winding and highlight. */
    private fun drawStringSegments(canvas: Canvas, count: Int, sw: Float, wound: Boolean) {
        if (count == 0) return
        val off = 2.4f * dp
        stringShadow.strokeWidth = sw + 1.2f * dp
        canvas.save()
        canvas.translate(off, off * 0.5f)
        canvas.drawLines(swing, 0, count, stringShadow)
        canvas.restore()
        stringPaint.strokeWidth = sw
        canvas.drawLines(swing, 0, count, stringPaint)
        if (wound) {
            windPaint.strokeWidth = sw
            canvas.drawLines(swing, 0, count, windPaint)
        }
        shinePaint.strokeWidth = sw * 0.34f
        shinePaint.color = if (wound) 0x88FFFFFF.toInt() else 0xD8FFFFFF.toInt()
        canvas.save()
        canvas.translate(-sw * 0.2f, 0f)
        canvas.drawLines(swing, 0, count, shinePaint)
        canvas.restore()
    }

    /**
     * Fills [swing] with line segments for a string bent [amp] sideways between [y0] and the bridge,
     * drawn down to [yEnd]: half a sine over the whole vibrating length. Returns the number of floats.
     */
    private fun buildSwing(x: Float, y0: Float, yEnd: Float, amp: Float): Int {
        val len = bridgeY - y0
        if (len <= 0f || yEnd <= y0) return 0
        var px = x
        var py = y0
        for (i in 1..SWING_STEPS) {
            val y = y0 + (yEnd - y0) * i / SWING_STEPS
            val nx = x + amp * sin(PI.toFloat() * (y - y0) / len)
            val k = (i - 1) * 4
            swing[k] = px; swing[k + 1] = py; swing[k + 2] = nx; swing[k + 3] = y
            px = nx
            py = y
        }
        return SWING_STEPS * 4
    }

    /** Fret numbers stamped in gold on the leather, and inked on the spruce past the body joint. */
    private fun drawFretNumbers(canvas: Canvas, top: Float, bottom: Float) {
        val w = width.toFloat()
        for (n in 0..instrument.frets) {
            val h = if (n == 0) openRow else fretHeight(n)
            // Zoomed far out, only the marked frets keep their numbers.
            if (n > 0 && h < 16 * dp && n !in MARKED) continue
            val cy = cellCenterY(n)
            if (cy + h < top || cy - h > bottom) continue
            gutterText.textSize = if (n == 0) min(13 * dp, openRow * 0.5f) else min(13 * dp, h * 0.75f).coerceAtLeast(8 * dp)
            val ty = cy + gutterText.textSize * 0.36f
            val onSpruce = cy > jointY
            val octave = n > 0 && n % 12 == 0
            val label = FRET_LABELS[n]
            // Debossed: a thin dark or light edge under the letters, then the letters.
            gutterText.color = if (onSpruce) 0x70FFF4DC else 0xC0000000.toInt()
            val dy = if (onSpruce) 0.9f * dp else -0.9f * dp
            canvas.drawText(label, gutter / 2, ty + dy, gutterText)
            canvas.drawText(label, w - gutter / 2, ty + dy, gutterText)
            gutterText.color = when {
                onSpruce && octave -> 0xFF9A1E14.toInt()
                onSpruce -> 0xFF5E3C1E.toInt()
                octave -> 0xFFF6CF74.toInt()
                else -> 0xFFC2A066.toInt()
            }
            canvas.drawText(label, gutter / 2, ty, gutterText)
            canvas.drawText(label, w - gutter / 2, ty, gutterText)
        }
    }

    /** Notes of the dromos as ivory finger tokens, the root in red lacquer, plus glows where notes were played. */
    private fun drawNotes(canvas: Canvas, now: Long, top: Float, bottom: Float) {
        val mask = markMask()
        val labels = LABELS[names.ordinal]
        for (c in instrument.courses.indices) for (f in 0..instrument.frets) {
            val y = cellCenterY(f)
            val cellH = if (f == 0) openRow else fretHeight(f)
            if (y + cellH < top || y - cellH > bottom) continue
            val midi = instrument.midiAt(c, f)
            val degree = Math.floorMod(midi - rootPc, 12)
            val inScale = mask and (1 shl degree) != 0
            val x = courseX(c)
            val r = min(courseWidth * 0.34f, cellH * 0.36f)
            val i = cellIndex(c, f)
            val since = now - flashAt[i]
            val flash = if (flashAt[i] > 0 && since < FLASH_MS) 1f - since / FLASH_MS.toFloat() else 0f
            val down = pressed[i]
            if (down || flash > 0f) {
                glowPaint.alpha = ((if (down) 1f else flash) * 255).toInt()
                unitCircle(canvas, x, y, r * 1.9f, glowPaint)
                glowPaint.alpha = 255
            }
            val label = labels[Math.floorMod(midi, 12)]
            if (inScale) {
                val isRoot = degree == 0
                when {
                    down -> token(canvas, x, y, r, pressedFace, 0xFF9A6008.toInt(), 1.2f * dp)
                    isRoot -> token(canvas, x, y, r, rootFace, 0xFFE4BC58.toInt(), 2 * dp)
                    else -> token(canvas, x, y, r, ivoryFace, 0xFF8A744E.toInt(), 1 * dp)
                }
                if (r >= 6 * dp) { // too small to read when zoomed far out
                    text.textSize = r * if (names == NoteNames.GREEK) 0.64f else 0.84f
                    text.color = if (isRoot && !down) 0xFFFFF3DC.toInt() else 0xFF2A1A0C.toInt()
                    canvas.drawText(label, x, y + text.textSize * 0.36f, text)
                }
            } else if ((showAllNotes || down) && r >= 6 * dp) {
                // Notes outside the dromos: a small dark dot that hides the strings, with a pale name.
                canvas.drawCircle(x, y, r * 0.72f, ghostFill)
                ring.strokeWidth = 1 * dp
                ring.color = 0x60E8DCC4
                canvas.drawCircle(x, y, r * 0.72f, ring)
                faint.textSize = r * if (names == NoteNames.GREEK) 0.5f else 0.62f
                canvas.drawText(label, x, y + faint.textSize * 0.36f, faint)
            }
        }
    }

    /** The song's next note (hollow gold ring) and the note now (gold token in a warm glow). */
    private fun drawSongMarks(canvas: Canvas) {
        val labels = LABELS[names.ordinal]
        nextCell?.takeIf { it != liveCell && onNeck(it) }?.let { cell ->
            val x = courseX(cell.course)
            val y = cellCenterY(cell.fret)
            val cellH = if (cell.fret == 0) openRow else fretHeight(cell.fret)
            val r = max(min(courseWidth * 0.36f, cellH * 0.40f), 9 * dp)
            ring.strokeWidth = 5.5f * dp
            ring.color = 0x99000000.toInt()
            canvas.drawCircle(x, y, r, ring)
            ring.strokeWidth = 3 * dp
            ring.color = 0xF0FFD36A.toInt()
            canvas.drawCircle(x, y, r, ring)
        }
        liveCell?.takeIf { onNeck(it) }?.let { cell ->
            val x = courseX(cell.course)
            val y = cellCenterY(cell.fret)
            val cellH = if (cell.fret == 0) openRow else fretHeight(cell.fret)
            val r = max(min(courseWidth * 0.40f, cellH * 0.44f), 11 * dp) // stays readable zoomed out
            legatoFrom?.takeIf { it.course == cell.course && it.fret != cell.fret && onNeck(it) }?.let { from ->
                // Hammered, pulled or slid here: a gold trail along the string from where the finger was.
                val y0 = cellCenterY(from.fret)
                val half = min(max(2.5f * dp, courseWidth * 0.07f), 4 * dp)
                canvas.save()
                canvas.translate(x, y0)
                canvas.scale(1f, y - y0)
                canvas.drawRect(-half, 0f, half, 1f, trailPaint)
                canvas.restore()
                ring.strokeWidth = 1.5f * dp
                ring.color = 0x90FFD36A.toInt()
                canvas.drawCircle(x, y0, r * 0.42f, ring)
            }
            unitCircle(canvas, x, y, r * 2.3f, glowPaint)
            token(canvas, x, y, r, liveFace, 0xFFFFF6DA.toInt(), 3 * dp)
            ring.strokeWidth = 1.2f * dp
            ring.color = 0x90000000.toInt()
            canvas.drawCircle(x, y, r + 0.6f * dp, ring)
            text.textSize = r * if (names == NoteNames.GREEK) 0.64f else 0.86f
            text.color = 0xFF2A1A08.toInt()
            canvas.drawText(labels[Math.floorMod(instrument.midiAt(cell.course, cell.fret), 12)], x, y + text.textSize * 0.36f, text)
        }
    }

    private fun onNeck(cell: Cell) = cell.course >= 0 && cell.course < instrument.courses.size && cell.fret >= 0 && cell.fret <= instrument.frets

    /** A round token with a soft shadow on the wood, a shaded face and a rim. */
    private fun token(canvas: Canvas, x: Float, y: Float, r: Float, face: Paint, rim: Int, rimWidth: Float) {
        // The shadow's gradient is centred on its own circle, down and right of the face, so a crescent shows.
        unitCircle(canvas, x + 0.1f * r, y + 0.16f * r, 1.14f * r, tokenShadow)
        unitCircle(canvas, x, y, r, face)
        ring.strokeWidth = rimWidth
        ring.color = rim
        canvas.drawCircle(x, y, r - rimWidth / 2, ring)
    }

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

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (x < neckLeft || x > neckRight || fretTops.isEmpty()) return null
        val c = ((x - neckLeft) / courseWidth).toInt().coerceIn(0, instrument.courses.size - 1)
        if (y < openRow) return c to 0
        val f = fretTops.indexOfFirst { y < it }
        return if (f < 1) c to instrument.frets else c to f
    }

    private fun play(course: Int, fret: Int, velocity: Float) {
        synth.pluck(course, instrument.midiAt(course, fret), instrument.courses[course].octavePair, velocity)
        val now = SystemClock.uptimeMillis()
        if (course < MAX_COURSES && fret in 0 until CELLS) {
            flashAt[cellIndex(course, fret)] = now
            pluckAt[course] = now
            pluckFret[course] = fret
        }
        lastPlay = now
        invalidate()
    }

    /** Plays one note for the scale player and reports where it is so the screen can follow. */
    fun playCell(cell: Cell) {
        play(cell.course, cell.fret, 0.8f)
        if (laidOut) onPlayed?.invoke(cellTop(cell.fret).toInt(), cellBottom(cell.fret).toInt())
    }

    /** The y range of one fret, for scrolling it into view. */
    fun cellSpan(cell: Cell): Pair<Int, Int> = fretSpan(cell.fret, cell.fret)

    /** The y range of a block of frets, for scrolling it into view; 0 to 0 until the neck has been measured. */
    fun fretSpan(first: Int, last: Int): Pair<Int, Int> =
        if (!laidOut) 0 to 0
        else cellTop(first.coerceIn(0, instrument.frets)).toInt() to cellBottom(last.coerceIn(0, instrument.frets)).toInt()

    /** True once onMeasure has placed the frets of the current instrument (not while the view is hidden, GONE). */
    private val laidOut get() = fretTops.size > instrument.frets

    /** Copies the fingers in [held] into the flat arrays the drawing reads. */
    private fun syncHeld() {
        pressed.fill(false)
        ringing.fill(false)
        for ((c, f) in held.values) {
            if (c < MAX_COURSES && f in 0 until CELLS) { pressed[cellIndex(c, f)] = true; ringing[c] = true }
        }
    }

    private var pinching = false

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // Two fingers spreading or closing: zoom instead of playing.
            pinching = true
            held.clear()
            syncHeld()
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
        val handled = handleTouch(e)
        syncHeld()
        return handled
    }

    private fun handleTouch(e: MotionEvent): Boolean {
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
            val now = SystemClock.uptimeMillis()
            for ((c, f) in held.values.distinct()) {
                synth.pluck(c, instrument.midiAt(c, f), instrument.courses[c].octavePair, if (tremoloUp) 0.62f else 0.74f)
                if (c < MAX_COURSES) { pluckAt[c] = now; pluckFret[c] = f } // keep the string shaking
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
        const val MIN_ZOOM = 0.05f
        const val MAX_ZOOM = 2.5f
        private val MARKED = setOf(3, 5, 7, 10, 12, 15, 17, 19, 22, 24)
        private const val MAX_COURSES = 8
        private const val CELLS = 40             // frets 0..39 per course in the flat arrays
        private const val MAX_INLAYS = 24
        private const val SWING_STEPS = 36       // line segments in a vibrating string
        private const val FLASH_MS = 320L        // glow after a note is played
        private const val VIBRATE_MS = 650L      // a plucked string shakes this long
        private const val JOINT_FRET = 17        // where the neck meets the body
        private const val SHOULDER_DROP_DP = 9f  // how far the body's shoulders fall away towards the screen edge
        private const val STRING_GAP_DP = 4f     // half the distance between the two strings of a course
        private const val FAN = 0.12f            // how fast the strings spread above the nut (per unit of height)
        private const val CREAM = 0xFFEDE2C6.toInt()
        private const val STEEL = 0xFFC9CDD2.toInt()
        private const val WOUND = 0xFFA99C86.toInt()
        private val FRET_LABELS = Array(CELLS) { it.toString() }
        private val mainHandler = Handler(Looper.getMainLooper())
        private val LABELS = Array(NoteNames.entries.size) { n -> Array(12) { NoteNames.entries[n].name(it) } }
        private val ROSETTE = intArrayOf(
            0xFF1A100A.toInt(), 0xFFD9C8A2.toInt(), 0xFF5A3218.toInt(), 0xFF2F4436.toInt(), 0xFF5A3218.toInt(),
            0xFFD9C8A2.toInt(), 0xFF1A100A.toInt(),
        )

        /** A shaded round face in unit space, lit from the upper left. */
        private fun face(hi: Long, mid: Long, low: Long, edge: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(-0.35f, -0.42f, 1.5f,
                intArrayOf(hi.toInt(), mid.toInt(), low.toInt(), edge.toInt()), floatArrayOf(0f, 0.38f, 0.78f, 1f),
                Shader.TileMode.CLAMP)
        }

        /** Fine dark bands across a wound string, repeating down its length. */
        private fun windingShader(): Shader {
            val stripes = Bitmap.createBitmap(1, 4, Bitmap.Config.ARGB_8888)
            stripes.setPixels(intArrayOf(0x00000000, 0x30000000, 0x8C000000.toInt(), 0x30000000), 0, 1, 0, 0, 1, 4)
            return BitmapShader(stripes, Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
        }
    }
}
