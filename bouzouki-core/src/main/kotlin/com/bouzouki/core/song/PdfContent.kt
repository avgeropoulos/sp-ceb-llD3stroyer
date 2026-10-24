package com.bouzouki.core.song

import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A glyph of text on a page, in page space with y measured down from the top of the page (as
 * pdfplumber does). [x0]..[x1] is its advance, [size] its em height (the font size as drawn).
 * [upright] is false for rotated or mirrored text. [code] is its character code in the font, and
 * [name] the glyph name the font's encoding gives it (if it names it), for music fonts whose glyphs
 * have no proper text.
 */
internal class PdfGlyph(
    val text: String,
    val x0: Double,
    val x1: Double,
    val baseline: Double,
    val size: Double,
    val upright: Boolean,
    val font: String,
    val code: Int = -1,
    val name: String? = null,
)

/** A straight horizontal stroke, or a thin filled bar, from [x0] to [x1] at height [y] (page space, y down). */
internal class PdfLine(val x0: Double, val x1: Double, val y: Double, val thickness: Double)

/** A straight vertical stroke, or a thin filled bar, at [x] from height [y0] down to [y1] (page space, y down). */
internal class PdfVLine(val x: Double, val y0: Double, val y1: Double, val thickness: Double)

/**
 * The bounding box of a painted curve (a subpath with Bézier segments), such as a tie or a slur (page space, y down).
 * [filled] when it was filled (a notehead drawn as a shape, a tie as a crescent), not only stroked; [hollow] when the
 * same path has another curved subpath inside it (a ring, such as a half note's head), and [inner] for that one.
 */
internal class PdfArc(
    val x0: Double, val x1: Double, val y0: Double, val y1: Double,
    val filled: Boolean = false, val hollow: Boolean = false, val inner: Boolean = false,
)

/** A filled shape of 4 straight sides that isn't an upright rectangle, such as a sloping beam: its corners (page space, y down). */
internal class PdfPoly(val xs: DoubleArray, val ys: DoubleArray) {
    val x0 get() = xs.min()
    val x1 get() = xs.max()
    val y0 get() = ys.min()
    val y1 get() = ys.max()
}

/** The text, the horizontal and vertical lines, the curves and sloping filled shapes on one page, and how many pictures it draws. */
internal class PdfPageContent(
    val width: Double,
    val height: Double,
    val glyphs: List<PdfGlyph>,
    val lines: List<PdfLine>,
    val images: Int,
    val vlines: List<PdfVLine> = emptyList(),
    val arcs: List<PdfArc> = emptyList(),
    val polys: List<PdfPoly> = emptyList(),
)

/** An affine transform `[a b c d e f]`, as PDF writes them (row vectors: x' = a x + c y + e, y' = b x + d y + f). */
internal class PdfMatrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    /** This transform followed by [m]. */
    operator fun times(m: PdfMatrix) = PdfMatrix(
        a * m.a + b * m.c, a * m.b + b * m.d,
        c * m.a + d * m.c, c * m.b + d * m.d,
        e * m.a + f * m.c + m.e, e * m.b + f * m.d + m.f,
    )

    fun x(px: Double, py: Double) = a * px + c * py + e
    fun y(px: Double, py: Double) = b * px + d * py + f

    /** How much the transform scales lengths, on average. */
    val scale get() = sqrt(abs(a * d - b * c))

    companion object {
        val IDENTITY = PdfMatrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun translate(x: Double, y: Double) = PdfMatrix(1.0, 0.0, 0.0, 1.0, x, y)

        /** The matrix in a PDF array, or null. */
        fun of(o: Any?): PdfMatrix? {
            val l = o as? List<*> ?: return null
            if (l.size < 6) return null
            val v = DoubleArray(6) { (l[it] as? Double) ?: return null }
            return PdfMatrix(v[0], v[1], v[2], v[3], v[4], v[5])
        }
    }
}

/**
 * Runs a page's content streams, and the forms they draw, keeping track of the graphics and text
 * state (q/Q/cm, BT/ET, Tf Tm Td TD T* TL Tc Tw Tz Ts, Tj TJ ' ") to place every glyph, and of
 * paths (m l re h c v y, painted by S s f F f* B B* b b* or dropped by n) to find horizontal and
 * vertical lines and curves.
 */
internal class PdfContentReader(private val doc: PdfDocument) {
    /** Problems met while reading pages, worded for the player. */
    val warnings = LinkedHashSet<String>()
    private val fonts = IdentityHashMap<Map<String, Any?>, PdfFont>()

    private class State(
        var ctm: PdfMatrix, var lineWidth: Double, var font: PdfFont?, var size: Double,
        var charSpace: Double, var wordSpace: Double, var hScale: Double, var leading: Double, var rise: Double,
    ) {
        fun copy() = State(ctm, lineWidth, font, size, charSpace, wordSpace, hScale, leading, rise)
    }

    // Per-page state.
    private var gs = State(PdfMatrix.IDENTITY, 1.0, null, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    private val stack = ArrayList<State>()
    private var tm = PdfMatrix.IDENTITY
    private var tlm = PdfMatrix.IDENTITY
    // The current path, already in page space, in flat arrays: point i is (px[i], py[i]), reached by
    // pk[i] (0 move, 1 line, 2 curve); subpath k starts at point subStart[k] and is closed if subClosed[k].
    private var px = DoubleArray(64)
    private var py = DoubleArray(64)
    private var pk = ByteArray(64)
    private var points = 0
    private var subStart = IntArray(16)
    private var subClosed = BooleanArray(16)
    private var subs = 0
    private var glyphs = ArrayList<PdfGlyph>()
    private var lines = ArrayList<PdfLine>()
    private var vlines = ArrayList<PdfVLine>()
    private var arcs = ArrayList<PdfArc>()
    private var polys = ArrayList<PdfPoly>()
    private var images = 0
    private var ops = 0
    /** What's left of the budgets shared by all the pages of the document. */
    private var opsLeft = MAX_DOC_OPS
    private var glyphsLeft = MAX_DOC_GLYPHS
    private val activeForms = IdentityHashMap<PdfStream, Boolean>()

    /** Everything [page] shows that matters here. */
    fun read(page: PdfPage): PdfPageContent {
        val box = page.box
        // Page space as the page is shown: origin at the top left, y going down, turned by /Rotate.
        val base = when (page.rotate) {
            90 -> PdfMatrix(0.0, 1.0, 1.0, 0.0, -box[1], -box[0])
            180 -> PdfMatrix(-1.0, 0.0, 0.0, 1.0, box[2], -box[1])
            270 -> PdfMatrix(0.0, -1.0, -1.0, 0.0, box[3], box[2])
            else -> PdfMatrix(1.0, 0.0, 0.0, -1.0, -box[0], box[3])
        }
        gs = State(base, 1.0, null, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        stack.clear(); activeForms.clear()
        endPath(); images = 0; ops = 0
        tm = PdfMatrix.IDENTITY; tlm = PdfMatrix.IDENTITY
        glyphs = ArrayList(); lines = ArrayList(); vlines = ArrayList(); arcs = ArrayList(); polys = ArrayList()
        run(doc.contents(page), page.resources, 0)
        val turned = page.rotate == 90 || page.rotate == 270
        val w = box[2] - box[0]
        val h = box[3] - box[1]
        return PdfPageContent(if (turned) h else w, if (turned) w else h, glyphs, lines, images, vlines, arcs, polys)
    }

    private fun run(data: ByteArray, res: Map<String, Any?>, depth: Int) {
        val lx = PdfLexer(data)
        val operands = ArrayList<Any?>(16)
        while (true) {
            val t = lx.next(false)
            if (t === PdfLexer.EOF) return
            if (t !is PdfOp) { if (operands.size < 64) operands.add(t); continue }
            if (++ops > MAX_OPS || --opsLeft < 0) { warnings += "This PDF is too complex to read completely"; return }
            if (t.name == "BI") { lx.skipInlineImage(); images++ }
            else try { op(t.name, operands, res, depth) } catch (e: RuntimeException) { /* bad operands: skip the operator */ }
            operands.clear()
        }
    }

    private fun num(o: List<Any?>, n: Int, i: Int): Double = (o[o.size - n + i] as? Double) ?: 0.0

    private fun op(name: String, o: List<Any?>, res: Map<String, Any?>, depth: Int) {
        fun has(n: Int) = o.size >= n
        when (name) {
            "q" -> if (stack.size < MAX_STACK) stack.add(gs.copy())
            "Q" -> if (stack.isNotEmpty()) gs = stack.removeAt(stack.size - 1)
            "cm" -> if (has(6)) gs.ctm = matrix(o) * gs.ctm
            "w" -> if (has(1)) gs.lineWidth = num(o, 1, 0)
            "BT" -> { tm = PdfMatrix.IDENTITY; tlm = PdfMatrix.IDENTITY }
            "Tf" -> if (has(2)) { gs.font = font(res, pdfName(o[o.size - 2])); gs.size = num(o, 2, 1) }
            "Tc" -> if (has(1)) gs.charSpace = num(o, 1, 0)
            "Tw" -> if (has(1)) gs.wordSpace = num(o, 1, 0)
            "Tz" -> if (has(1)) gs.hScale = num(o, 1, 0) / 100
            "TL" -> if (has(1)) gs.leading = num(o, 1, 0)
            "Ts" -> if (has(1)) gs.rise = num(o, 1, 0)
            "Td" -> if (has(2)) moveText(num(o, 2, 0), num(o, 2, 1))
            "TD" -> if (has(2)) { gs.leading = -num(o, 2, 1); moveText(num(o, 2, 0), num(o, 2, 1)) }
            "Tm" -> if (has(6)) { tlm = matrix(o); tm = tlm }
            "T*" -> moveText(0.0, -gs.leading)
            "Tj" -> (o.lastOrNull() as? PdfString)?.let { show(it.bytes) }
            "'" -> { moveText(0.0, -gs.leading); (o.lastOrNull() as? PdfString)?.let { show(it.bytes) } }
            "\"" -> if (has(3)) {
                gs.wordSpace = num(o, 3, 0); gs.charSpace = num(o, 3, 1)
                moveText(0.0, -gs.leading)
                (o.last() as? PdfString)?.let { show(it.bytes) }
            }
            "TJ" -> (o.lastOrNull() as? List<*>)?.forEach { e ->
                when (e) {
                    is PdfString -> show(e.bytes)
                    is Double -> tm = PdfMatrix.translate(-e / 1000 * gs.size * gs.hScale, 0.0) * tm
                }
            }
            "m" -> if (has(2)) moveTo(num(o, 2, 0), num(o, 2, 1))
            "l" -> if (has(2)) lineTo(num(o, 2, 0), num(o, 2, 1), 1)
            "c" -> if (has(6)) lineTo(num(o, 6, 4), num(o, 6, 5), 2)
            "v", "y" -> if (has(4)) lineTo(num(o, 4, 2), num(o, 4, 3), 2)
            "re" -> if (has(4)) rect(num(o, 4, 0), num(o, 4, 1), num(o, 4, 2), num(o, 4, 3))
            "h" -> closePath()
            "S" -> { stroke(); curves(false); endPath() }
            "s" -> { closePath(); stroke(); curves(false); endPath() }
            "f", "F", "f*" -> { fill(); curves(true); endPath() }
            "B", "B*" -> { fill(); stroke(); curves(true); endPath() }
            "b", "b*" -> { closePath(); fill(); stroke(); curves(true); endPath() }
            "n" -> endPath()
            "Do" -> if (has(1)) xObject(pdfName(o.last()), res, depth)
        }
    }

    private fun matrix(o: List<Any?>) =
        PdfMatrix(num(o, 6, 0), num(o, 6, 1), num(o, 6, 2), num(o, 6, 3), num(o, 6, 4), num(o, 6, 5))

    private fun font(res: Map<String, Any?>, name: String?): PdfFont? {
        val d = pdfDict(doc.resolve(pdfDict(doc.resolve(res["Font"]))?.get(name ?: return null))) ?: return null
        return fonts.getOrPut(d) { PdfFont.load(doc, d) }
    }

    // ---- Text ----

    private fun moveText(tx: Double, ty: Double) {
        tlm = PdfMatrix.translate(tx, ty) * tlm
        tm = tlm
    }

    private fun show(bytes: ByteArray) {
        val f = gs.font ?: return
        val size = gs.size
        val h = gs.hScale
        f.forEachCode(bytes) { code, len ->
            val w = f.width(code)
            val trm = PdfMatrix(size * h, 0.0, 0.0, size, 0.0, gs.rise) * tm * gs.ctm
            val x0 = trm.e
            val x1 = trm.a * w + trm.e
            val em = hypot(trm.c, trm.d)
            if ((x0 + x1 + trm.f + em).isFinite() && glyphs.size < MAX_GLYPHS && glyphsLeft-- > 0) {
                // Upright: the glyph's up points up the page (y down) and its advance goes right.
                val upright = trm.d < 0 && abs(trm.c) < 0.1 * em && trm.a > 0 && abs(trm.b) < 0.1 * abs(trm.a)
                glyphs.add(PdfGlyph(f.text(code), min(x0, x1), max(x0, x1), trm.f, em, upright, f.name, code, f.glyphName(code)))
            }
            val tx = (w * size + gs.charSpace + if (len == 1 && code == 32) gs.wordSpace else 0.0) * h
            tm = PdfMatrix.translate(tx, 0.0) * tm
        }
    }

    // ---- Paths ----

    /** Adds a point to the current subpath, unless the path is full. */
    private fun addPoint(x: Double, y: Double, kind: Int) {
        if (points >= MAX_PATH_POINTS) return
        if (points == px.size) { px = px.copyOf(points * 2); py = py.copyOf(points * 2); pk = pk.copyOf(points * 2) }
        px[points] = x; py[points] = y; pk[points] = kind.toByte(); points++
    }

    /** Starts a subpath at page-space point ([x], [y]). */
    private fun startSubpath(x: Double, y: Double) {
        if (points >= MAX_PATH_POINTS) return
        if (subs == subStart.size) { subStart = subStart.copyOf(subs * 2); subClosed = subClosed.copyOf(subs * 2) }
        subStart[subs] = points; subClosed[subs] = false; subs++
        addPoint(x, y, 0)
    }

    private fun moveTo(x: Double, y: Double) = startSubpath(gs.ctm.x(x, y), gs.ctm.y(x, y))

    private fun lineTo(x: Double, y: Double, kind: Int) {
        if (subs == 0) { moveTo(x, y); return }
        if (subClosed[subs - 1]) {
            // After h, drawing goes on from the closed subpath's start, in a new subpath.
            val from = subStart[subs - 1]
            startSubpath(px[from], py[from])
            if (subClosed[subs - 1]) return // the path is full
        }
        addPoint(gs.ctm.x(x, y), gs.ctm.y(x, y), kind)
    }

    private fun rect(x: Double, y: Double, w: Double, h: Double) {
        moveTo(x, y)
        lineTo(x + w, y, 1)
        lineTo(x + w, y + h, 1)
        lineTo(x, y + h, 1)
        closePath()
    }

    private fun closePath() {
        if (subs > 0) subClosed[subs - 1] = true
    }

    private fun endPath() {
        points = 0
        subs = 0
    }

    /** Calls [f] with the first point and the end (exclusive) of every subpath, and whether it is closed. */
    private inline fun forEachSubpath(f: (from: Int, to: Int, closed: Boolean) -> Unit) {
        for (k in 0 until subs) f(subStart[k], if (k + 1 < subs) subStart[k + 1] else points, subClosed[k])
    }

    private fun stroke() {
        val width = max(gs.lineWidth, 0.0) * gs.ctm.scale
        forEachSubpath { from, to, closed ->
            for (i in from + 1 until to) if (pk[i].toInt() == 1) segment(px[i - 1], py[i - 1], px[i], py[i], width)
            if (closed && to - from > 1) segment(px[to - 1], py[to - 1], px[from], py[from], width)
        }
    }

    private fun segment(x0: Double, y0: Double, x1: Double, y1: Double, width: Double) {
        val dx = abs(x1 - x0)
        val dy = abs(y1 - y0)
        if (!(dx + dy + x0 + y0 + width).isFinite()) return
        if (dx >= 0.5 && dy <= max(0.25, 0.005 * dx)) {
            if (lines.size < MAX_LINES) lines.add(PdfLine(min(x0, x1), max(x0, x1), (y0 + y1) / 2, width))
        } else if (dy >= 0.5 && dx <= max(0.25, 0.005 * dy)) {
            if (vlines.size < MAX_LINES) vlines.add(PdfVLine((x0 + x1) / 2, min(y0, y1), max(y0, y1), width))
        }
    }

    /** Filled axis-aligned rectangles that are thin and long count as lines (some programs draw staff lines and barlines that way). */
    private fun fill() {
        forEachSubpath { from, to, _ ->
            var n = to - from
            if (n == 5 && abs(px[from + 4] - px[from]) < 0.01 && abs(py[from + 4] - py[from]) < 0.01) n = 4
            if (n != 4) return@forEachSubpath
            for (i in 1 until to - from) if (pk[from + i].toInt() != 1) return@forEachSubpath
            var axis = true
            for (i in 0 until 4) {
                val a = from + i
                val b = from + (i + 1) % 4
                if (abs(px[a] - px[b]) > 0.01 && abs(py[a] - py[b]) > 0.01) axis = false
            }
            if (!axis) {
                // A sloping filled quadrilateral: a beam, as most notation programs draw them.
                if (polys.size < MAX_POLYS) {
                    val xs = DoubleArray(4) { px[from + it] }
                    val ys = DoubleArray(4) { py[from + it] }
                    if ((xs.sum() + ys.sum()).isFinite()) polys.add(PdfPoly(xs, ys))
                }
                return@forEachSubpath
            }
            val x0 = min(min(px[from], px[from + 1]), min(px[from + 2], px[from + 3]))
            val x1 = max(max(px[from], px[from + 1]), max(px[from + 2], px[from + 3]))
            val y0 = min(min(py[from], py[from + 1]), min(py[from + 2], py[from + 3]))
            val y1 = max(max(py[from], py[from + 1]), max(py[from + 2], py[from + 3]))
            if (!(x0 + x1 + y0 + y1).isFinite()) return@forEachSubpath
            if (y1 - y0 <= MAX_BAR && x1 - x0 > 2 * (y1 - y0)) {
                if (lines.size < MAX_LINES) lines.add(PdfLine(x0, x1, (y0 + y1) / 2, y1 - y0))
            } else if (x1 - x0 <= MAX_BAR && y1 - y0 > 2 * (x1 - x0)) {
                if (vlines.size < MAX_LINES) vlines.add(PdfVLine((x0 + x1) / 2, y0, y1, x1 - x0))
            }
        }
    }

    /**
     * Keeps the bounding box of every painted subpath that has curves in it ([filled] if the path was filled), marking
     * the ones with another inside them (rings, such as hollow noteheads) and those inside.
     */
    private fun curves(filled: Boolean) {
        val boxes = ArrayList<DoubleArray>()
        forEachSubpath { from, to, _ ->
            var curved = false
            for (i in from + 1 until to) if (pk[i].toInt() == 2) { curved = true; break }
            if (!curved || arcs.size + boxes.size >= MAX_ARCS) return@forEachSubpath
            var x0 = px[from]; var x1 = x0; var y0 = py[from]; var y1 = y0
            for (i in from + 1 until to) { x0 = min(x0, px[i]); x1 = max(x1, px[i]); y0 = min(y0, py[i]); y1 = max(y1, py[i]) }
            if ((x0 + x1 + y0 + y1).isFinite()) boxes.add(doubleArrayOf(x0, x1, y0, y1))
        }
        val n = boxes.size
        // Rings: only looked for in small paths (a glyph drawn as a shape has a few subpaths).
        val hollow = BooleanArray(n)
        val inner = BooleanArray(n)
        if (n in 2..MAX_RING_SUBPATHS) {
            for (i in 0 until n) for (j in 0 until n) {
                if (i == j) continue
                val a = boxes[i]
                val b = boxes[j]
                if (b[0] > a[0] && b[1] < a[1] && b[2] > a[2] && b[3] < a[3]) { hollow[i] = true; inner[j] = true }
            }
        }
        for (i in 0 until n) {
            val b = boxes[i]
            arcs.add(PdfArc(b[0], b[1], b[2], b[3], filled, hollow[i] && !inner[i], inner[i]))
        }
    }

    // ---- Forms and pictures ----

    private fun xObject(name: String?, res: Map<String, Any?>, depth: Int) {
        val x = doc.resolve(pdfDict(doc.resolve(res["XObject"]))?.get(name ?: return)) as? PdfStream ?: return
        when (pdfName(x.dict["Subtype"])) {
            "Image" -> images++
            "Form" -> {
                if (depth >= MAX_FORM_DEPTH || activeForms.put(x, true) != null) return
                try {
                    val data = doc.decode(x) ?: return
                    val saved = gs.copy()
                    val savedStack = stack.size
                    val savedTm = tm
                    val savedTlm = tlm
                    PdfMatrix.of(doc.resolve(x.dict["Matrix"]))?.let { gs.ctm = it * gs.ctm }
                    run(data, pdfDict(doc.resolve(x.dict["Resources"])) ?: res, depth + 1)
                    while (stack.size > savedStack) stack.removeAt(stack.size - 1)
                    gs = saved
                    tm = savedTm
                    tlm = savedTlm
                } finally {
                    activeForms.remove(x)
                }
            }
        }
    }

    companion object {
        private const val MAX_OPS = 3_000_000
        private const val MAX_DOC_OPS = 20_000_000
        private const val MAX_DOC_GLYPHS = 1_000_000
        private const val MAX_STACK = 256
        private const val MAX_GLYPHS = 200_000
        private const val MAX_LINES = 200_000
        private const val MAX_ARCS = 50_000
        private const val MAX_POLYS = 50_000
        /** The most subpaths of one path looked at for rings (hollow noteheads drawn as shapes). */
        private const val MAX_RING_SUBPATHS = 8
        private const val MAX_PATH_POINTS = 200_000
        private const val MAX_FORM_DEPTH = 12
        /** The thickest filled bar still taken as a line, in points. */
        private const val MAX_BAR = 4.0
    }
}
