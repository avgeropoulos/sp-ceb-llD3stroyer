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
 * [upright] is false for rotated or mirrored text.
 */
internal class PdfGlyph(
    val text: String,
    val x0: Double,
    val x1: Double,
    val baseline: Double,
    val size: Double,
    val upright: Boolean,
    val font: String,
)

/** A straight horizontal stroke, or a thin filled bar, from [x0] to [x1] at height [y] (page space, y down). */
internal class PdfLine(val x0: Double, val x1: Double, val y: Double, val thickness: Double)

/** The text and the horizontal lines on one page, and how many pictures it draws. */
internal class PdfPageContent(
    val width: Double,
    val height: Double,
    val glyphs: List<PdfGlyph>,
    val lines: List<PdfLine>,
    val images: Int,
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
 * paths (m l re h c v y, painted by S s f F f* B B* b b* or dropped by n) to find horizontal lines.
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

    /** One subpath, already in page space; kinds[i] says how point i was reached: 0 move, 1 line, 2 curve. */
    private class SubPath {
        var n = 0
        var xs = DoubleArray(8)
        var ys = DoubleArray(8)
        var kinds = ByteArray(8)
        var closed = false

        fun add(x: Double, y: Double, kind: Int) {
            if (n == xs.size) { xs = xs.copyOf(n * 2); ys = ys.copyOf(n * 2); kinds = kinds.copyOf(n * 2) }
            xs[n] = x; ys[n] = y; kinds[n] = kind.toByte(); n++
        }
    }

    // Per-page state.
    private var gs = State(PdfMatrix.IDENTITY, 1.0, null, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    private val stack = ArrayList<State>()
    private var tm = PdfMatrix.IDENTITY
    private var tlm = PdfMatrix.IDENTITY
    private val path = ArrayList<SubPath>()
    private var pathPoints = 0
    private var glyphs = ArrayList<PdfGlyph>()
    private var lines = ArrayList<PdfLine>()
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
        stack.clear(); path.clear(); activeForms.clear()
        pathPoints = 0; images = 0; ops = 0
        tm = PdfMatrix.IDENTITY; tlm = PdfMatrix.IDENTITY
        glyphs = ArrayList(); lines = ArrayList()
        run(doc.contents(page), page.resources, 0)
        val turned = page.rotate == 90 || page.rotate == 270
        val w = box[2] - box[0]
        val h = box[3] - box[1]
        return PdfPageContent(if (turned) h else w, if (turned) w else h, glyphs, lines, images)
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
            "S" -> { stroke(); endPath() }
            "s" -> { closePath(); stroke(); endPath() }
            "f", "F", "f*" -> { fill(); endPath() }
            "B", "B*" -> { fill(); stroke(); endPath() }
            "b", "b*" -> { closePath(); fill(); stroke(); endPath() }
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
                glyphs.add(PdfGlyph(f.text(code), min(x0, x1), max(x0, x1), trm.f, em, upright, f.name))
            }
            val tx = (w * size + gs.charSpace + if (len == 1 && code == 32) gs.wordSpace else 0.0) * h
            tm = PdfMatrix.translate(tx, 0.0) * tm
        }
    }

    // ---- Paths ----

    private fun moveTo(x: Double, y: Double) {
        if (pathPoints >= MAX_PATH_POINTS) return
        val s = SubPath()
        s.add(gs.ctm.x(x, y), gs.ctm.y(x, y), 0)
        path.add(s)
        pathPoints++
    }

    private fun lineTo(x: Double, y: Double, kind: Int) {
        val s = path.lastOrNull()
        if (s == null || s.closed) { moveTo(x, y); return }
        if (pathPoints >= MAX_PATH_POINTS) return
        s.add(gs.ctm.x(x, y), gs.ctm.y(x, y), kind)
        pathPoints++
    }

    private fun rect(x: Double, y: Double, w: Double, h: Double) {
        moveTo(x, y)
        lineTo(x + w, y, 1)
        lineTo(x + w, y + h, 1)
        lineTo(x, y + h, 1)
        closePath()
    }

    private fun closePath() {
        val s = path.lastOrNull() ?: return
        if (s.closed) return
        s.closed = true
        // Drawing continues from the subpath's start.
        if (pathPoints < MAX_PATH_POINTS) { val n = SubPath(); n.add(s.xs[0], s.ys[0], 0); path.add(n) }
    }

    private fun endPath() {
        path.clear()
        pathPoints = 0
    }

    private fun stroke() {
        val width = max(gs.lineWidth, 0.0) * gs.ctm.scale
        for (s in path) {
            for (i in 1 until s.n) if (s.kinds[i].toInt() == 1) segment(s.xs[i - 1], s.ys[i - 1], s.xs[i], s.ys[i], width)
            if (s.closed && s.n > 1) segment(s.xs[s.n - 1], s.ys[s.n - 1], s.xs[0], s.ys[0], width)
        }
    }

    private fun segment(x0: Double, y0: Double, x1: Double, y1: Double, width: Double) {
        val dx = abs(x1 - x0)
        if (!(dx + y0 + y1 + width).isFinite() || dx < 0.5 || abs(y1 - y0) > max(0.25, 0.005 * dx) || lines.size >= MAX_LINES) return
        lines.add(PdfLine(min(x0, x1), max(x0, x1), (y0 + y1) / 2, width))
    }

    /** Filled axis-aligned rectangles that are thin and long count as lines (some programs draw staff lines that way). */
    private fun fill() {
        for (s in path) {
            var n = s.n
            if (n < 4) continue
            if (n == 5 && abs(s.xs[4] - s.xs[0]) < 0.01 && abs(s.ys[4] - s.ys[0]) < 0.01) n = 4
            if (n != 4) continue
            var axis = true
            for (i in 1 until 4) if (s.kinds[i].toInt() != 1) axis = false
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                if (abs(s.xs[i] - s.xs[j]) > 0.01 && abs(s.ys[i] - s.ys[j]) > 0.01) axis = false
            }
            if (!axis) continue
            val x0 = min(min(s.xs[0], s.xs[1]), min(s.xs[2], s.xs[3]))
            val x1 = max(max(s.xs[0], s.xs[1]), max(s.xs[2], s.xs[3]))
            val y0 = min(min(s.ys[0], s.ys[1]), min(s.ys[2], s.ys[3]))
            val y1 = max(max(s.ys[0], s.ys[1]), max(s.ys[2], s.ys[3]))
            if (!(x0 + x1 + y0 + y1).isFinite()) continue
            if (y1 - y0 <= MAX_BAR && x1 - x0 > 2 * (y1 - y0) && lines.size < MAX_LINES) lines.add(PdfLine(x0, x1, (y0 + y1) / 2, y1 - y0))
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
        private const val MAX_PATH_POINTS = 1_000_000
        private const val MAX_FORM_DEPTH = 12
        /** The thickest filled bar still taken as a line, in points. */
        private const val MAX_BAR = 4.0
    }
}
