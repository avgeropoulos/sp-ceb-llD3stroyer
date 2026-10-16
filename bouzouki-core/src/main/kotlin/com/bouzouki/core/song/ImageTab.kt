package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A grayscale picture: [gray] from 0 (black) to 255 (white) per pixel, row by row, [width] x [height]. */
class GrayImage(val gray: ByteArray, val width: Int, val height: Int) {
    init { require(width > 0 && height > 0 && gray.size >= width * height) { "bad image size" } }
}

/**
 * A digit as a font draws it, for [ImageTab] to compare a picture's numbers with: [ink] from 0 (paper) to 255
 * (ink) per pixel, row by row, [width] x [height] tightly around the digit; [font] names the font (any text).
 */
class DigitGlyph(val digit: Int, val width: Int, val height: Int, val ink: ByteArray, val font: String = "")

/**
 * A tab read from pictures: the [tab] (its warnings start with [ImageTab.PICTURE]), how many fret numbers were
 * read ([numbers]) and how many of them were hard to make out ([unsure]); [confidence] is the share read
 * confidently. [triplets] are the notes (indices into the tab's notes) under each triplet mark, [systems] the
 * lines of music read.
 */
class ImageTabResult(
    val tab: ParsedTab,
    val numbers: Int,
    val unsure: Int,
    val confidence: Double,
    val triplets: List<IntRange>,
    val systems: Int,
)

/**
 * Reads a bouzouki tab from pictures of it: screenshots, PDF pages saved as images, clean scans of printed tabs.
 * Photos of paper and handwritten tabs are beyond it.
 *
 * 1. The picture's paper is evened out (a scan darker at an edge), it's made black and white (Otsu's threshold;
 *    white on black is turned round), turned straight when it's a little turned (the angle that lines the long
 *    thin strokes up best), and its staff lines are made straight where they bend (a page scanned curved).
 * 2. Staffs are runs of 3 to 6 equally spaced long thin horizontal lines: 3 or 4 lines a tab staff (trichordo
 *    or tetrachordo); 5 a notation staff and 6 a guitar tab, which are skipped.
 * 3. On each tab staff the lines are taken out (where they're only line), and the marks left that sit centred on
 *    a line at the height most of them have are fret numbers: stems, beams, the TAB clef, time signatures,
 *    rests and slurs are other sizes or shapes. Marks close together on a line make a 2-digit number; one too
 *    wide for a digit is cut where it has least ink.
 * 4. Each digit is compared with digits drawn in fonts (the ones given, rendered at the picture's digit
 *    size, and a plain built-in set), scaled to one size, by correlation, with its holes (0 6 8 9) as a check.
 *    Then the digits read confidently become the picture's own font, which settles between the likeliest
 *    digits of the others, so a tab in an unusual font is read well too. Frets are at most the bouzouki's, and a
 *    2-digit fret starts with 1 or 2. A mark that matches no digit well is left out; one that matches two digits
 *    alike is unsure.
 * 5. Notes in one column are a chord (its top note, with the others in [TabNote.stack], as [TabParser] keeps
 *    them); columns come from x as [PdfTab] makes them; systems go top to bottom, pages in order. Barlines,
 *    repeat barlines (thick and thin, with two dots) and 1st/2nd-time brackets are drawn into a page for
 *    [PdfForm], which makes the bars and the sections in playing order. Triplet marks (a 3 under the staff,
 *    with a bracket or a beam) are found and reported. Words (D.C., D.S., Fine) can't be read from a picture.
 */
object ImageTab {
    /** The first warning of a tab read from a picture. */
    const val PICTURE = "Read from a picture of the tab"

    /** The warning for pictures with no tab staff found. */
    const val NO_STAFFS = "No tab lines were found in the picture: it needs a printed tab with 3 or 4 lines per staff, " +
        "clear and straight (a screenshot, or a PDF saved as pictures); photos of paper and handwriting can't be read"

    /** The warning for tab staffs with no numbers read. */
    const val NO_NUMBERS = "No fret numbers could be read on the tab lines in the picture"

    private const val UNSURE_PREFIX = "Numbers hard to make out on the picture: "

    /** Said of every tab read from a picture: its words aren't read. */
    const val WORDS = "Words on the picture (D.C., D.S., Fine, x2) aren't read: check the order of the parts by ear"

    /**
     * Where the reader shows what it found (for tests and tuning; null normally): a page, a kind ("line", "staff",
     * "number"; "glyph" with the page as -2 - page) and its numbers (boxes in pixels, values, scores).
     */
    internal var debug: ((page: Int, kind: String, data: DoubleArray) -> Unit)? = null

    /** The page as read (straightened), for tests and tuning; null normally. */
    internal var debugImage: ((page: Int, gray: ByteArray, width: Int, height: Int) -> Unit)? = null

    /** Whether [warning] is the one saying how many numbers were hard to make out (see [unsureIn]). */
    fun unsureWarning(warning: String) = warning.startsWith(UNSURE_PREFIX)

    /** How many numbers [tab] says were hard to make out, if it was read from a picture ([PICTURE]); null otherwise. */
    fun unsureIn(tab: ParsedTab): Int? {
        if (tab.warnings.firstOrNull() != PICTURE) return null
        return tab.warnings.firstOrNull { it.startsWith(UNSURE_PREFIX) }?.removePrefix(UNSURE_PREFIX)?.substringBefore(' ')?.toIntOrNull() ?: 0
    }

    /** Reads the tab in one picture; see [read]. */
    fun read(gray: ByteArray, width: Int, height: Int, instrument: Instrument, digitGlyphs: ((Int) -> List<DigitGlyph>)? = null): ImageTabResult =
        read(listOf(GrayImage(gray, width, height)), instrument, digitGlyphs)

    /**
     * Reads the tab in [pages] (pictures in order). [instrument] is the song's (the tab's own, by its line count, is
     * in the result). [digitGlyphs], if given, draws the digits 0-9 in a few fonts at a height in pixels (the app
     * uses the phone's fonts); a plain built-in set is used too.
     */
    fun read(pages: List<GrayImage>, instrument: Instrument, digitGlyphs: ((Int) -> List<DigitGlyph>)? = null): ImageTabResult =
        read(pages.size, { pages[it] }, instrument, digitGlyphs)

    /**
     * Reads the tab in [count] pictures that [page] gives one at a time, in order (so a long PDF's pages needn't all
     * be in memory at once); a page it can't give (null) is left out, with a warning. See the other [read].
     */
    fun read(count: Int, page: (Int) -> GrayImage?, instrument: Instrument, digitGlyphs: ((Int) -> List<DigitGlyph>)? = null): ImageTabResult {
        val warnings = LinkedHashSet<String>()
        warnings += PICTURE
        val read = ArrayList<PageRead>()
        for (i in 0 until count) {
            val p = page(i)
            if (p == null) { warnings += "A picture couldn't be opened"; continue }
            if (p.width.toLong() * p.height > MAX_PIXELS) { warnings += "A picture is too big to read (over ${MAX_PIXELS / 1_000_000} megapixels)"; continue }
            val img = straighten(prepare(p))
            debugImage?.invoke(i, img.gray, img.w, img.h)
            read += readPage(img, i)
        }
        return finish(read, instrument, digitGlyphs, warnings)
    }

    // ---- Tuning ----

    private const val MAX_PIXELS = 50_000_000L
    /** Tilts tried when straightening, in degrees either way. */
    private const val MAX_TILT = 3.0
    /** A staff line's long runs cover at least this share of the picture's width. */
    private const val LINE_COVER = 0.12
    /** Staff line thickness at most this share of the spacing (and a pixel and a half). */
    private const val MAX_THICKNESS = 0.25
    /** The lines of a staff are at least this share of the longest one's length. */
    private const val SAME_LENGTH = 0.6
    /** How far a digit's middle may be from its line, as a share of the digit height. */
    private const val ON_LINE = 0.22
    /** Digit heights accepted, as shares of the usual one. */
    private const val MIN_HEIGHT = 0.68
    private const val MAX_HEIGHT = 1.22
    /** The TAB clef at a staff's start, in line spacings: no frets there. */
    private const val CLEF = 2.3
    /** Gap between the digits of one number, at most, as a share of the digit height. */
    private const val DIGIT_GAP = 0.42
    /** A mark wider than this (share of the digit height) is two digits. */
    private const val TWO_WIDE = 0.92
    /** The normalised glyph field's size. */
    private const val N = 24
    /** Digits are thin (taken from the light gray up) when their dark ink is less than this many times their light gray. */
    private const val THIN_STROKES = 3.8
    /** A digit is read confidently with at least this similarity and this lead over the next best. */
    private const val SURE = 0.62
    private const val LEAD = 0.035
    /** A digit joins the picture's own font ([ownTemplates]) with at least this similarity (and a lead of 0.1). */
    private const val OWN_SURE = 0.75
    /** Below this similarity a mark isn't a digit at all. */
    private const val NOT_DIGIT = 0.42
    /** Columns per typical note spacing, and the gap between systems (as [PdfTab]). */
    private const val COLUMNS_PER_NOTE = 2.0
    private const val SYSTEM_GAP = 2
    /** The staff spacing a page is drawn at for [PdfForm], in points. */
    private const val FORM_SPACING = 6.0

    // ---- Pictures ----

    /** A page ready to read: gray levels (0 black) and ink (dark pixels), straightened. */
    private class Img(
        val w: Int, val h: Int, val gray: ByteArray, val ink: BooleanArray, val line: BooleanArray, val paper: Int, val black: Int,
        val inkLevel: Int, val lineLevel: Int,
    ) {
        /** Darkness from 0 (paper) to 1 (ink) at pixel [i]. */
        fun dark(i: Int): Float = ((paper - (gray[i].toInt() and 0xFF)).toFloat() / max(1, paper - black)).coerceIn(0f, 1f)
    }

    private fun prepare(p: GrayImage): Img {
        val w = p.width
        val h = p.height
        var gray = p.gray.copyOf(w * h)
        val hist = IntArray(256)
        for (b in gray) hist[b.toInt() and 0xFF]++
        // White on black (a dark-mode screenshot): turn it round.
        var sum = 0L
        for (v in 0..255) sum += v.toLong() * hist[v]
        if (sum / (w.toLong() * h) < 110) {
            for (i in gray.indices) gray[i] = (255 - (gray[i].toInt() and 0xFF)).toByte()
        }
        flatten(gray, w, h)
        hist.fill(0)
        for (b in gray) hist[b.toInt() and 0xFF]++
        val t = otsu(hist).coerceIn(60, 215)
        val paper = percentile(hist, 0.6).coerceAtLeast(t + 1)
        val black = percentile(hist, 0.01).coerceAtMost(t - 1)
        // Thin lines are often drawn light (anti-aliased): they count as line from a lighter gray than marks do.
        val tl = max(t, paper - (0.25 * (paper - black)).toInt())
        val tilt = tilt(BooleanArray(w * h) { (gray[it].toInt() and 0xFF) < tl }, w, h)
        if (abs(tilt) >= 0.04) {
            val k = tan(Math.toRadians(tilt))
            val g2 = ByteArray(w * h) { -1 }
            for (x in 0 until w) {
                val dy = ((x - w / 2) * k).roundToInt()
                for (y in max(0, -dy) until min(h, h - dy)) g2[y * w + x] = gray[(y + dy) * w + x]
            }
            gray = g2
        }
        val ink = BooleanArray(w * h) { (gray[it].toInt() and 0xFF) < t }
        val line = BooleanArray(w * h) { (gray[it].toInt() and 0xFF) < tl }
        return Img(w, h, gray, ink, line, paper, black, t, tl)
    }

    /**
     * [img] with its staff lines made straight where they bend (a page scanned or photocopied curved near its
     * spine): the lines are followed strip by strip across the page, and each column is stretched up or down so
     * every long line lies at its average height. Returns [img] itself when the lines are straight already.
     */
    private fun straighten(img: Img): Img {
        val w = img.w
        val h = img.h
        val runs = longRuns(img.line, w, h, minRun(w))
        val stripW = max(120, w / 12)
        val strips = (w + stripW - 1) / stripW
        if (strips < 3) return img
        // Line pieces in each strip: peaks of long strokes.
        val pieces = Array(strips) { s ->
            val a = s * stripW
            val b = min(w, a + stripW)
            val counts = IntArray(h) { y -> var c = 0; val r = y * w; for (x in a until b) if (runs[r + x]) c++; c }
            peaks(counts, max(10, (0.4 * (b - a)).toInt())).map { band ->
                var sw = 0.0
                var sy = 0.0
                for (r in band) { sw += counts[r]; sy += counts[r] * r.toDouble() }
                sy / sw
            }
        }
        // Pieces joined into lines from left to right (a strip may be missed, where digits cover the line).
        class Chain(val ys: DoubleArray) { var last = -1 }
        val chains = ArrayList<Chain>()
        val tol = max(2.5, 0.015 * stripW)
        for (s in 0 until strips) {
            val taken = HashSet<Chain>()
            for (y in pieces[s]) {
                val c = chains.filter { it.last >= s - 2 && it.last < s && it !in taken && abs(it.ys[it.last] - y) <= tol * (s - it.last) }
                    .minByOrNull { abs(it.ys[it.last] - y) }
                    ?: Chain(DoubleArray(strips) { Double.NaN }).also { chains += it }
                c.ys[s] = y
                c.last = s
                taken += c
            }
        }
        val long = chains.filter { c -> c.ys.count { !it.isNaN() } >= max(3, strips / 2) }
        if (long.isEmpty()) return img
        // Each line's height at each column, and the height it goes to (its average).
        val centre = DoubleArray(strips) { (it * stripW + min(w, it * stripW + stripW)) / 2.0 }
        fun at(c: Chain, x: Int): Double {
            var left = -1
            var right = -1
            for (s in 0 until strips) if (!c.ys[s].isNaN()) { if (centre[s] <= x) left = s; if (centre[s] >= x && right < 0) right = s }
            return when {
                left < 0 -> c.ys[right]
                right < 0 || right == left -> c.ys[left]
                else -> c.ys[left] + (c.ys[right] - c.ys[left]) * (x - centre[left]) / (centre[right] - centre[left])
            }
        }
        val targets = long.map { c -> c.ys.filter { !it.isNaN() }.average() }
        var bend = 0.0
        for ((k, c) in long.withIndex()) for (y in c.ys) if (!y.isNaN()) bend = max(bend, abs(y - targets[k]))
        if (bend < 1.0) return img
        val order = targets.indices.sortedBy { targets[it] }
        val gray = ByteArray(w * h)
        val orig = DoubleArray(order.size)
        val goal = DoubleArray(order.size)
        for (x in 0 until w) {
            // Knots for this column: (goal, original height), kept in order both ways.
            var n = 0
            for (k in order) {
                val o = at(long[k], x)
                if (n > 0 && (o <= orig[n - 1] + 1 || targets[k] <= goal[n - 1] + 1)) continue
                orig[n] = o
                goal[n] = targets[k]
                n++
            }
            var j = 0
            for (y in 0 until h) {
                while (j < n && goal[j] < y) j++
                val src = when {
                    n == 0 -> y.toDouble()
                    j == 0 -> y + orig[0] - goal[0]
                    j == n -> y + orig[n - 1] - goal[n - 1]
                    else -> orig[j - 1] + (orig[j] - orig[j - 1]) * (y - goal[j - 1]) / (goal[j] - goal[j - 1])
                }
                val y0 = kotlin.math.floor(src).toInt()
                val f = src - y0
                val a = if (y0 in 0 until h) img.gray[y0 * w + x].toInt() and 0xFF else 255
                val b = if (y0 + 1 in 0 until h) img.gray[(y0 + 1) * w + x].toInt() and 0xFF else 255
                gray[y * w + x] = (a + (b - a) * f).roundToInt().toByte()
            }
        }
        val ink = BooleanArray(w * h) { (gray[it].toInt() and 0xFF) < img.inkLevel }
        val line = BooleanArray(w * h) { (gray[it].toInt() and 0xFF) < img.lineLevel }
        return Img(w, h, gray, ink, line, img.paper, img.black, img.inkLevel, img.lineLevel)
    }

    /**
     * Rows of [counts] that are the peaks of lines: stretches of at least [need], split at their peaks (each with
     * the rows round it at least half as high), leaving out peaks much lower than the stretch's highest.
     */
    private fun peaks(counts: IntArray, need: Int): List<IntRange> {
        val bands = ArrayList<IntRange>()
        var y = 0
        while (y < counts.size) {
            if (counts[y] < need) { y++; continue }
            var e = y
            while (e < counts.size && counts[e] >= need) e++
            var top = 0
            for (r in y until e) top = max(top, counts[r])
            var r = y
            while (r < e) {
                var p = r
                while (p + 1 < e && counts[p + 1] >= counts[p]) p++
                var q = p
                while (q + 1 < e && counts[q + 1] >= counts[p] / 2 && counts[q + 1] <= counts[q]) q++
                var a = p
                while (a - 1 >= r && counts[a - 1] >= counts[p] / 2) a--
                if (counts[p] >= 0.6 * top) bands += a..q
                r = q + 1
            }
            y = e
        }
        return bands
    }

    /**
     * Evens out the paper of [gray] in place (a scan darker towards an edge, a shadow): each pixel is scaled by the
     * paper's brightness around it (the bright end of its block's levels, the brightest of the blocks round it).
     */
    private fun flatten(gray: ByteArray, w: Int, h: Int) {
        val b = max(32, min(w, h) / 40)
        val bw = (w + b - 1) / b
        val bh = (h + b - 1) / b
        val level = IntArray(bw * bh)
        val hist = IntArray(256)
        for (by in 0 until bh) for (bx in 0 until bw) {
            hist.fill(0)
            var n = 0
            for (y in by * b until min(h, by * b + b)) for (x in bx * b until min(w, bx * b + b)) { hist[gray[y * w + x].toInt() and 0xFF]++; n++ }
            level[by * bw + bx] = percentile(hist, 0.9)
        }
        // A block full of print (beams, a title) takes its neighbours' paper.
        val paper = IntArray(bw * bh) { i ->
            val bx = i % bw
            val by = i / bw
            var m = 0
            for (dy in -1..1) for (dx in -1..1) {
                val x = bx + dx
                val y = by + dy
                if (x in 0 until bw && y in 0 until bh) m = max(m, level[y * bw + x])
            }
            m
        }
        if (paper.min() >= 235) return // even already
        for (y in 0 until h) {
            val fy = ((y + 0.5) / b - 0.5).coerceIn(0.0, bh - 1.0)
            val y0 = min(fy.toInt(), bh - 1)
            val y1 = min(y0 + 1, bh - 1)
            val ay = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5) / b - 0.5).coerceIn(0.0, bw - 1.0)
                val x0 = min(fx.toInt(), bw - 1)
                val x1 = min(x0 + 1, bw - 1)
                val ax = fx - x0
                val p = (1 - ay) * ((1 - ax) * paper[y0 * bw + x0] + ax * paper[y0 * bw + x1]) +
                    ay * ((1 - ax) * paper[y1 * bw + x0] + ax * paper[y1 * bw + x1])
                if (p < 40) continue
                val v = (gray[y * w + x].toInt() and 0xFF) * 255.0 / p
                gray[y * w + x] = min(255, v.roundToInt()).toByte()
            }
        }
    }

    private fun otsu(hist: IntArray): Int {
        val total = hist.sum().toDouble()
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * hist[i].toDouble()
        var wB = 0.0
        var sumB = 0.0
        var best = 0.0
        var t = 128
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = i }
        }
        return t + 1
    }

    private fun percentile(hist: IntArray, q: Double): Int {
        val total = hist.sum()
        var acc = 0
        for (i in 0..255) { acc += hist[i]; if (acc >= q * total) return i }
        return 255
    }

    /** Runs of ink at least [minRun] long in each row: a pixel mask of long horizontal strokes. */
    private fun longRuns(ink: BooleanArray, w: Int, h: Int, minRun: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            var x = 0
            val row = y * w
            while (x < w) {
                if (!ink[row + x]) { x++; continue }
                var e = x
                while (e < w && ink[row + e]) e++
                if (e - x >= minRun) for (k in x until e) out[row + k] = true
                x = e
            }
        }
        return out
    }

    private fun minRun(w: Int) = max(6, w / 200)

    /** The tilt (degrees, positive: the right side lower) that lines the long strokes up best. */
    private fun tilt(ink: BooleanArray, w: Int, h: Int): Double {
        val runs = longRuns(ink, w, h, minRun(w))
        // Sample the stroke pixels (every other column) once.
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w step 2) if (runs[y * w + x]) { xs += x - w / 2; ys += y }
        if (xs.size < 100) return 0.0
        val step = max(1, xs.size / 400_000)
        val px = IntArray((xs.size + step - 1) / step) { xs[it * step] }
        val py = IntArray(px.size) { ys[it * step] }
        val hist = IntArray(h + 2 * (w / 2 * tan(Math.toRadians(MAX_TILT))).toInt() + 4)
        val off = (hist.size - h) / 2
        fun score(deg: Double): Double {
            val k = tan(Math.toRadians(deg))
            hist.fill(0)
            for (i in px.indices) {
                val y = (py[i] - px[i] * k).roundToInt() + off
                if (y in hist.indices) hist[y]++
            }
            var s = 0.0
            for (v in hist) s += v.toDouble() * v
            return s
        }
        var best = 0.0
        var bestScore = score(0.0)
        var d = -MAX_TILT
        while (d <= MAX_TILT + 1e-9) { val s = score(d); if (s > bestScore * 1.0001) { best = d; bestScore = s }; d += 0.1 }
        val coarse = best
        d = coarse - 0.1
        while (d <= coarse + 0.1 + 1e-9) { val s = score(d); if (s > bestScore) { best = d; bestScore = s }; d += 0.02 }
        return best
    }

    // ---- Staffs ----

    /** A staff line: its middle [y], the rows [y0]..[y1] it takes, and its extent. */
    private class Line(val y: Double, val y0: Int, val y1: Int, val x0: Int, val x1: Int) {
        val thickness get() = y1 - y0 + 1
    }

    private class Staff(val page: Int, val lines: List<Line>) {
        val ys = DoubleArray(lines.size) { lines[it].y }
        val spacing = (ys.last() - ys.first()) / (ys.size - 1)
        // Its extent: as far as two of its lines go (one may run into text, or fade where the page is light).
        val x0 = lines.map { it.x0 }.sorted()[1]
        val x1 = lines.map { it.x1 }.sortedDescending()[1]
        val top get() = ys.first()
        val bottom get() = ys.last()
        val n get() = lines.size
    }

    /** The staff lines on [img], top to bottom. */
    private fun lines(img: Img, runs: BooleanArray): List<Line> {
        val w = img.w
        val counts = IntArray(img.h) { y -> var c = 0; val r = y * w; for (x in 0 until w) if (runs[r + x]) c++; c }
        val need = max(30, (LINE_COVER * w).toInt())
        val out = ArrayList<Line>()
        // Lines close together (a notation staff crowded with notes) can make one stretch of rows with long strokes:
        // it's split at its peaks; a much weaker peak (a row of noteheads between two lines) isn't a line.
        for (band in peaks(counts, need)) {
            // A band: its middle (weighted), and its extent: the longest chain of stroke columns with gaps of at
            // most a few percent of the width.
            val y = band.first
            val e = band.last + 1
            var sw = 0.0
            var sy = 0.0
            for (r in y until e) { sw += counts[r]; sy += counts[r] * r.toDouble() }
            val cover = BooleanArray(w)
            for (r in y until e) { val row = r * w; for (x in 0 until w) if (runs[row + x]) cover[x] = true }
            val gap = max(12, w / 12)
            var best0 = 0
            var best1 = -1
            var start = -1
            var last = -1
            var covered = 0
            var bestCovered = 0
            for (x in 0 until w) {
                if (!cover[x]) continue
                if (start < 0 || x - last > gap) { start = x; covered = 0 }
                covered++
                last = x
                if (covered > bestCovered) { bestCovered = covered; best0 = start; best1 = x }
            }
            if (best1 < 0) continue
            // Its thickness: the usual height of the line where nothing crosses it (digits written on it, the
            // rows next to it in a crowded band, don't count), and its middle there.
            var peak = y
            for (r in y until e) if (counts[r] > counts[peak]) peak = r
            val lengths = ArrayList<Int>()
            val middles = ArrayList<Double>()
            for (x in best0..best1) {
                if (!runs[peak * w + x]) continue
                var t = peak
                while (t > 0 && peak - t < 64 && img.line[(t - 1) * w + x]) t--
                var b = peak
                while (b < img.h - 1 && b - peak < 64 && img.line[(b + 1) * w + x]) b++
                lengths += b - t + 1
                middles += (t + b) / 2.0
            }
            if (lengths.isEmpty()) continue
            val thick = lengths.sorted()[lengths.size / 2]
            val mid = middles.filterIndexed { k, _ -> lengths[k] <= thick }.average().takeIf { !it.isNaN() } ?: (sy / sw)
            val y0 = (mid - (thick - 1) / 2.0).roundToInt()
            out += Line(mid, y0, y0 + thick - 1, best0, best1)
        }
        return out
    }

    /** Staffs made of [lines]: runs of 3 to 6 lines equally spaced, overlapping across. */
    private fun staffs(lines: List<Line>, page: Int, img: Img): List<Staff> {
        // Candidate staffs: from each line, with one of the next few lines like it (as long, side by side) as the
        // second, the lines going on at that spacing. Lines between them that don't fit (the tops and bottoms of a
        // row of digits, beams) are passed over, but no more than two: a run over another staff isn't one.
        val candidates = ArrayList<List<Int>>()
        for (i in lines.indices) {
            var best: List<Int>? = null
            val seconds = (i + 1 until lines.size).filter { overlap(lines[i], lines[it]) && lines[it].y - lines[i].y >= 4 }.take(4)
            for (j in seconds) {
                val sp = lines[j].y - lines[i].y
                if (sp > img.h / 6.0) break
                val thin = MAX_THICKNESS * sp + 1.5
                if (lines[i].thickness > thin || lines[j].thickness > thin) continue
                val run = arrayListOf(i, j)
                var skipped = 0
                var k = j + 1
                while (k < lines.size && run.size < 7) {
                    val gap = lines[k].y - lines[run.last()].y
                    if (gap > 1.15 * sp) break
                    val like = overlap(lines[i], lines[k])
                    if (like && gap >= 0.85 * sp && lines[k].thickness <= thin) { run += k; skipped = 0 } else if (like && ++skipped > 2) break
                    k++
                }
                if (run.size >= 3 && (best == null || run.size > best.size)) best = run
            }
            if (best != null) candidates += best
        }
        // The longest and most even runs first; each line in one staff.
        fun unevenness(run: List<Int>): Double {
            val gaps = run.zipWithNext { a, b -> lines[b].y - lines[a].y }
            val mean = gaps.average()
            return gaps.maxOf { abs(it - mean) } / mean
        }
        val out = ArrayList<Staff>()
        val used = BooleanArray(lines.size)
        for (run in candidates.sortedWith(compareByDescending<List<Int>> { it.size }.thenBy { unevenness(it) })) {
            if (run.any { used[it] }) continue
            val s = Staff(page, run.map { lines[it] })
            if (s.n in 3..6 && s.x1 - s.x0 >= 8 * s.spacing) {
                out += s
                for (k in run) used[k] = true
            }
        }
        return out.sortedBy { it.top }
    }

    /** Whether lines [a] and [b] could be lines of one staff: about as long, side by side. */
    private fun overlap(a: Line, b: Line): Boolean {
        val o = min(a.x1, b.x1) - max(a.x0, b.x0)
        val la = a.x1 - a.x0
        val lb = b.x1 - b.x0
        return o >= 0.7 * min(la, lb) && min(la, lb) >= SAME_LENGTH * max(la, lb)
    }

    // ---- Marks ----

    /** A connected mark: its box and pixels (indices into the page). */
    private class Blob(var x0: Int, var y0: Int, var x1: Int, var y1: Int, var pixels: IntArray) {
        val w get() = x1 - x0 + 1
        val h get() = y1 - y0 + 1
        val cx get() = (x0 + x1) / 2.0
        val cy get() = (y0 + y1) / 2.0
    }

    /** The marks (8-connected ink, leaving out [skip]) in the box [x0]..[x1], [y0]..[y1]. */
    private fun blobs(
        img: Img, x0: Int, y0: Int, x1: Int, y1: Int, skip: BooleanArray?, maxPixels: Int = Int.MAX_VALUE, ink: BooleanArray = img.ink,
    ): List<Blob> {
        val ax0 = x0.coerceIn(0, img.w - 1)
        val ax1 = x1.coerceIn(0, img.w - 1)
        val ay0 = y0.coerceIn(0, img.h - 1)
        val ay1 = y1.coerceIn(0, img.h - 1)
        if (ax1 < ax0 || ay1 < ay0) return emptyList()
        val bw = ax1 - ax0 + 1
        val seen = BooleanArray(bw * (ay1 - ay0 + 1))
        val out = ArrayList<Blob>()
        val queue = IntArray(seen.size)
        for (y in ay0..ay1) for (x in ax0..ax1) {
            val li = (y - ay0) * bw + (x - ax0)
            val gi = y * img.w + x
            if (seen[li] || !ink[gi] || (skip != null && skip[gi])) continue
            var head = 0
            var tail = 0
            queue[tail++] = li
            seen[li] = true
            var bx0 = x; var bx1 = x; var by0 = y; var by1 = y
            while (head < tail) {
                val c = queue[head++]
                val cx = c % bw + ax0
                val cy = c / bw + ay0
                if (cx < bx0) bx0 = cx; if (cx > bx1) bx1 = cx
                if (cy < by0) by0 = cy; if (cy > by1) by1 = cy
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < ax0 || nx > ax1 || ny < ay0 || ny > ay1) continue
                    val nl = (ny - ay0) * bw + (nx - ax0)
                    if (seen[nl]) continue
                    val ng = ny * img.w + nx
                    if (!ink[ng] || (skip != null && skip[ng])) continue
                    seen[nl] = true
                    queue[tail++] = nl
                }
            }
            if (tail <= maxPixels) {
                val px = IntArray(tail) { val c = queue[it]; (c / bw + ay0) * img.w + (c % bw + ax0) }
                out += Blob(bx0, by0, bx1, by1, px)
            }
        }
        return out
    }

    /**
     * Marks [mask] with the pixels of [s]'s lines that are only line: in a long horizontal stroke on the line's rows,
     * with no ink just above and below (a digit's stroke crossing the line is kept).
     */
    private fun maskLines(img: Img, s: Staff, runs: BooleanArray, mask: BooleanArray) {
        val w = img.w
        val x0 = max(0, (s.x0 - 2 * s.spacing).toInt())
        val x1 = min(w - 1, (s.x1 + 2 * s.spacing).toInt())
        for (l in s.lines) {
            val a = max(0, l.y0 - 1)
            val b = min(img.h - 1, l.y1 + 1)
            for (x in x0..x1) {
                var y = a
                while (y <= b) {
                    if (!runs[y * w + x]) { y++; continue }
                    // The vertical run of ink through the line here: only line if it stays on the line's rows.
                    var t = y
                    while (t > 0 && img.line[(t - 1) * w + x]) t--
                    var e = y
                    while (e < img.h - 1 && img.line[(e + 1) * w + x]) e++
                    if (t >= a && e <= b) for (r in t..e) mask[r * w + x] = true
                    y = e + 1
                }
            }
        }
    }

    // ---- Reading a page ----

    /** A number on a line: its digits' glyphs, left to right. */
    private class Num(val staff: Staff, val line: Int, val glyphs: List<Glyph>) {
        val x0 get() = glyphs.first().x0
        val x1 get() = glyphs.last().x1
        val x get() = (x0 + x1) / 2.0
        var value = -1
        var sure = true
        var digits = ""
    }

    /** One digit's picture: its box on the page, darkness field and holes. */
    private class Glyph(
        val x0: Int, val y0: Int, val x1: Int, val y1: Int, val field: FloatArray, val aspect: Double, val holes: Int, val height: Int,
    ) {
        var scores = DoubleArray(10)
    }

    /** What one page gives: its staffs, their numbers, triplet marks and the drawing for [PdfForm]. */
    private class PageRead(
        val index: Int, val tabs: List<Staff>, val notation: List<Staff>, val guitar: Int,
        val numbers: Map<Staff, List<Num>>, val digitHeight: Double, val triplets: Map<Staff, List<Triplet>>,
        val form: FormDraft, val scale: Double,
    )

    private fun readPage(img: Img, index: Int): PageRead {
        val runs = longRuns(img.line, img.w, img.h, minRun(img.w))
        val lines = lines(img, runs)
        val all = staffs(lines, index, img)
        // A 3- or 4-line run with a line drawn as fully one spacing above or below it (maybe run into a beam) is
        // part of a bigger staff.
        fun cover(y: Double, half: Double, s: Staff): Int {
            var n = 0
            val a = max(0, (y - half).toInt())
            val b = min(img.h - 1, (y + half).roundToInt())
            if (a > b) return 0
            for (x in max(0, s.x0)..min(img.w - 1, s.x1)) for (r in a..b) if (runs[r * img.w + x]) { n++; break }
            return n
        }
        val tabs = all.filter { it.n in 3..4 }.filter { s ->
            val weakest = s.lines.minOf { cover(it.y, it.thickness / 2.0 + 1, s) }
            val half = 0.15 * s.spacing
            cover(s.top - s.spacing, half, s) < 0.9 * weakest && cover(s.bottom + s.spacing, half, s) < 0.9 * weakest
        }
        val notation = all.filter { it.n == 5 }
        val guitar = all.count { it.n == 6 }
        debug?.let { d ->
            for (l in lines) d(index, "line", doubleArrayOf(l.x0.toDouble(), l.y, l.x1.toDouble(), l.thickness.toDouble()))
            for (s in all) d(index, "staff", doubleArrayOf(s.x0.toDouble(), s.top, s.x1.toDouble(), s.bottom, s.n.toDouble(), if (s in tabs) 1.0 else 0.0))
        }
        val mask = BooleanArray(img.w * img.h)
        for (s in tabs) maskLines(img, s, runs, mask)
        // The usual digit height: of marks across the tab lines, measured on the side of the line away from a stem
        // that may touch them (a digit is centred on its line).
        val heights = ArrayList<Int>()
        val perStaff = HashMap<Staff, List<Int>>()
        for (s in tabs) {
            val sp = s.spacing
            val hs = blobs(img, (s.x0 - sp).toInt(), (s.top - 0.75 * sp).toInt(), (s.x1 + sp).toInt(), (s.bottom + 0.75 * sp).toInt(), mask, 20_000)
                .mapNotNull { b ->
                    val ly = s.ys.minByOrNull { abs(b.cy - it) }!!
                    // Measured each side of the line; a digit a pixel or two off centre keeps its own height.
                    val half = (2 * min(b.y1 - ly, ly - b.y0) + 1).roundToInt()
                    val h = if (b.h <= 1.3 * half) b.h else half + 1
                    h.takeIf { b.y0 < ly && b.y1 > ly && h >= 0.35 * sp && h <= 1.25 * sp && b.w >= 0.12 * sp && b.w <= 2.2 * sp }
                }
            perStaff[s] = hs
            heights += hs
        }
        val pageHeight = median(heights) ?: 0.0
        // Thin digits drawn anti-aliased have much of their strokes in light gray: they're taken from the light
        // gray up (else they fall apart); bold ones from the dark ink (light gray would join them to their stems).
        var darkPx = 0
        var lightPx = 0
        for (s in tabs) for (ly in s.ys) {
            for (y in max(0, (ly - 0.3 * s.spacing).toInt())..min(img.h - 1, (ly + 0.3 * s.spacing).toInt())) {
                for (x in max(0, s.x0)..min(img.w - 1, s.x1)) {
                    val i = y * img.w + x
                    if (mask[i] || !img.line[i]) continue
                    if (img.ink[i]) darkPx++ else lightPx++
                }
            }
        }
        val thin = darkPx < THIN_STROKES * lightPx
        debug?.invoke(-1, "strokes", doubleArrayOf(darkPx.toDouble(), lightPx.toDouble()))
        val numbers = LinkedHashMap<Staff, List<Num>>()
        val triplets = LinkedHashMap<Staff, List<Triplet>>()
        val cut = BooleanArray(img.w * img.h)
        // Barlines are taken out with the lines (a fret written against one would be joined to it).
        val barMask = BooleanArray(img.w * img.h)
        val bars = tabs.associateWith { s -> barlines(img, s, all) }
        for ((s, list) in bars) for (b in list) {
            val bx0 = max(0, (b.x - b.width / 2 - 1).toInt())
            val bx1 = min(img.w - 1, (b.x + b.width / 2 + 1).roundToInt())
            for (y in max(0, b.y0.toInt() - 1)..min(img.h - 1, b.y1.roundToInt())) for (x in bx0..bx1) {
                barMask[y * img.w + x] = true
                mask[y * img.w + x] = true
            }
        }
        for (s in tabs) {
            val own = perStaff.getValue(s)
            val dh = if (own.size >= 5) median(own)!! else pageHeight
            if (dh < 4) { numbers[s] = emptyList(); continue }
            numbers[s] = numbers(img, s, mask, barMask, cut, dh, thin)
            triplets[s] = triplets(img, s, dh, mask)
        }
        val tabSpacing = median(tabs.map { it.spacing }) ?: 10.0
        val scale = FORM_SPACING / tabSpacing
        val form = formPage(img, tabs, notation, scale, mask, pageHeight)
        return PageRead(index, tabs, notation, guitar, numbers, pageHeight, triplets, form, scale)
    }

    private fun median(v: List<Number>): Double? {
        if (v.isEmpty()) return null
        val s = v.map { it.toDouble() }.sorted()
        return s[s.size / 2]
    }

    /** The numbers on [s]'s lines, as glyphs not yet read. */
    private fun numbers(img: Img, s: Staff, mask: BooleanArray, barMask: BooleanArray, cut: BooleanArray, dh: Double, thin: Boolean): List<Num> {
        val ink = if (thin) img.line else img.ink
        val sp = s.spacing
        val out = ArrayList<Num>()
        for ((li, ly) in s.ys.withIndex()) {
            val band0 = (ly - 0.78 * dh).toInt()
            val band1 = (ly + 0.78 * dh).roundToInt()
            val top = max(0, band0)
            val bottom = min(img.h - 1, band1)
            // Marks as drawn; one joined to the line (a stretch of line touching it, or the line drawn through the
            // digits) is taken apart where the line is all there is ([apart]). Thin digits are parted where only
            // light gray joins them (two digits close together).
            val x0 = (s.x0 + CLEF * sp).toInt()
            val marks = blobs(img, x0, band0, (s.x1 + sp).toInt(), band1, barMask, ink = ink)
                .flatMap { b -> if (b.w > 1.6 * dh) apart(img, b, s.lines[li], mask, cut, dh, band0, band1, ink) else listOf(b) }
                .flatMap { b -> if (thin) partLight(img, b, dh) else listOf(b) }
                .let { stacked(img, it, dh) }
                // A mark running through the whole band (a barline, a stem, a rest) isn't a digit.
                .filter { b -> b.y0 > top || b.y1 < bottom }
                .mapNotNull { b -> trim(img, b, dh, ly) }
                .filterNot { b -> slanted(img, b, dh) }
                .filter { b ->
                    abs(b.cy - ly) <= ON_LINE * dh + 1 && b.h >= MIN_HEIGHT * dh && b.h <= MAX_HEIGHT * dh &&
                        b.w >= 0.12 * dh && b.w <= 3.6 * dh && b.pixels.size >= 0.06 * dh * dh
                }
                .sortedBy { it.x0 }
            // Marks close together, with no line between them, make one number.
            var k = 0
            while (k < marks.size) {
                val group = arrayListOf(marks[k])
                var j = k + 1
                while (j < marks.size) {
                    val prev = group.last()
                    val gap = marks[j].x0 - prev.x1 - 1
                    if (gap > DIGIT_GAP * dh) break
                    if (gap > 0.25 * dh && lineBetween(img, mask, s.lines[li], prev.x1 + 1, marks[j].x0 - 1)) break
                    group += marks[j]
                    j++
                }
                k = j
                val glyphs = group.flatMap { split(img, it, dh, mask) }
                if (glyphs.isEmpty()) continue
                // More than 2 digits: frets set close together, cut at the widest gaps.
                for (part in cutRun(glyphs)) out += Num(s, li, part)
            }
        }
        return out.sortedWith(compareBy({ it.x }, { it.line }))
    }

    /**
     * Mark [b], joined to line [l], taken apart where the line is all there is: its line pixels ([mask]) in columns
     * with nothing of it above or below the line within a digit's height go, so frets drawn on the line come apart
     * while their own strokes along the line stay. [cut] is a page-sized scratch mask, left clear.
     */
    private fun apart(img: Img, b: Blob, l: Line, mask: BooleanArray, cut: BooleanArray, dh: Double, band0: Int, band1: Int, ink: BooleanArray): List<Blob> {
        val a = l.y0 - 1
        val z = l.y1 + 1
        val above = BooleanArray(b.w)
        for (p in b.pixels) {
            val y = p / img.w
            if (y < a || y > z) above[p % img.w - b.x0] = true
        }
        val marked = ArrayList<Int>()
        for (p in b.pixels) {
            val y = p / img.w
            if (y in a..z && mask[p] && !above[p % img.w - b.x0]) { cut[p] = true; marked += p }
        }
        val mine = b.pixels.toHashSet()
        val out = blobs(img, b.x0, band0, b.x1, band1, cut, 20_000, ink).filter { o -> o.pixels[0] in mine }
        for (p in marked) cut[p] = false
        return out
    }

    /** Whether a stretch of line ([mask]ed line pixels) lies between [x0] and [x1] on [l]: two separate frets. */
    private fun lineBetween(img: Img, mask: BooleanArray, l: Line, x0: Int, x1: Int): Boolean {
        if (x1 < x0) return false
        var hit = 0
        for (x in x0..x1) {
            for (y in max(0, l.y0 - 1)..min(img.h - 1, l.y1 + 1)) if (mask[y * img.w + x]) { hit++; break }
        }
        return hit >= max(2, (x1 - x0 + 1) * 3 / 5)
    }

    /**
     * [b] without thin rows at its top and bottom (the end of a stem or beam touching a digit) while it's taller than
     * a digit; null if nothing is left.
     */
    private fun trim(img: Img, b: Blob, dh: Double, ly: Double): Blob? {
        if (b.h <= 1.08 * dh) return b
        val rows = IntArray(b.h)
        for (p in b.pixels) rows[p / img.w - b.y0]++
        val thin = max(2.0, 0.2 * dh)
        // A digit is centred on its line: what's beyond a digit's height from it must be a thin stroke, else the
        // mark is something else (a time signature's digit, a letter).
        var top = max(0, (ly - 0.56 * dh - 1 - b.y0).toInt())
        var bottom = min(b.h - 1, (ly + 0.56 * dh + 1 - b.y0).roundToInt())
        if (top > bottom) return null
        for (r in 0 until top) if (rows[r] > thin) return null
        for (r in bottom + 1 until b.h) if (rows[r] > thin) return null
        // The rest of the stroke where it meets the digit.
        val stemTop = top > 0
        val stemBottom = bottom < b.h - 1
        while (bottom - top + 1 > 1.02 * dh) {
            if (stemTop && rows[top] <= thin) top++ else if (stemBottom && rows[bottom] <= thin) bottom-- else break
        }
        val y0 = b.y0 + top
        val y1 = b.y0 + bottom
        val px = b.pixels.filter { it / img.w in y0..y1 }.toIntArray()
        if (px.isEmpty()) return null
        var x0 = Int.MAX_VALUE
        var x1 = -1
        for (p in px) { val x = p % img.w; if (x < x0) x0 = x; if (x > x1) x1 = x }
        return Blob(x0, y0, x1, y1, px)
    }

    /**
     * [b] parted at columns with no dark ink, only light gray (anti-aliasing, a smudge), where both sides are marks
     * of their own (a digit's height, a few pixels wide); otherwise [b] as it is.
     */
    private fun partLight(img: Img, b: Blob, dh: Double): List<Blob> {
        if (b.w < 0.5 * dh) return listOf(b)
        val dark = IntArray(b.w)
        val light = IntArray(b.w)
        for (p in b.pixels) if (img.ink[p]) dark[p % img.w - b.x0]++ else light[p % img.w - b.x0]++
        var from = 0
        val out = ArrayList<Blob>()
        var x = 0
        while (x < b.w) {
            // A cut where a pixel or so of light gray touches; not through a loop's light top and bottom (a thin 0).
            if (dark[x] > 0 || light[x] > 1) { x++; continue }
            var e = x
            while (e + 1 < b.w && dark[e + 1] == 0 && light[e + 1] <= 1) e++
            // Columns x..e have no dark ink: a cut if both sides are substantial.
            val left = b.pixels.filter { val c = it % img.w - b.x0; c in from until x }
            val right = b.pixels.filter { val c = it % img.w - b.x0; c > e }
            if (left.isNotEmpty() && right.isNotEmpty()) {
                val lb = box(img, left.toIntArray())
                val rb = box(img, right.toIntArray())
                if (lb.h >= 0.5 * dh && rb.h >= 0.5 * dh && lb.w >= 2 && rb.w >= 2) { out += lb; from = e + 1 }
            }
            x = e + 1
        }
        if (out.isEmpty()) return listOf(b)
        val rest = b.pixels.filter { it % img.w - b.x0 >= from }
        if (rest.isNotEmpty()) out += box(img, rest.toIntArray())
        return out
    }

    /**
     * [marks] with pieces one above the other joined: a digit whose hairline is too light to hold it together (a
     * serif 5's join). Digits on one line are side by side, never stacked.
     */
    private fun stacked(img: Img, marks: List<Blob>, dh: Double): List<Blob> {
        val out = marks.toMutableList()
        var joined = true
        while (joined && out.size < 2_000) {
            joined = false
            loop@ for (i in out.indices) for (j in i + 1 until out.size) {
                val a = out[i]
                val b = out[j]
                val across = min(a.x1, b.x1) - max(a.x0, b.x0) + 1
                val gap = max(a.y0, b.y0) - min(a.y1, b.y1) - 1
                if (across >= 0.5 * min(a.w, b.w) && gap <= max(2.0, 0.15 * dh) && max(a.y1, b.y1) - min(a.y0, b.y0) + 1 <= 1.25 * dh) {
                    out[i] = box(img, a.pixels + b.pixels)
                    out.removeAt(j)
                    joined = true
                    break@loop
                }
            }
        }
        return out
    }

    /** Whether [b] is a narrow slanting stroke (a rest's, a slash), not a 1: its top and bottom are far apart across. */
    private fun slanted(img: Img, b: Blob, dh: Double): Boolean {
        if (b.w > 0.45 * dh || b.h < 4) return false
        val q = max(1, b.h / 4)
        var top = 0.0
        var nt = 0
        var bottom = 0.0
        var nb = 0
        for (p in b.pixels) {
            val y = p / img.w - b.y0
            val x = (p % img.w).toDouble()
            if (y < q) { top += x; nt++ } else if (y >= b.h - q) { bottom += x; nb++ }
        }
        return nt > 0 && nb > 0 && abs(top / nt - bottom / nb) > 0.2 * dh
    }

    /** [b] as digit glyphs: cut where it has least ink if it's too wide for one digit. */
    private fun split(img: Img, b: Blob, dh: Double, mask: BooleanArray): List<Glyph> {
        if (b.w <= TWO_WIDE * dh) return listOf(glyph(img, b, mask))
        val parts = (b.w / (0.62 * dh)).roundToInt().coerceIn(2, 3)
        val cols = IntArray(b.w)
        for (p in b.pixels) cols[p % img.w - b.x0]++
        val cuts = ArrayList<Int>()
        var from = 0
        for (k in 1 until parts) {
            val centre = b.w * k / parts
            val lo = max(from + 2, centre - b.w / (2 * parts))
            val hi = min(b.w - 3, centre + b.w / (2 * parts))
            if (lo > hi) return listOf(glyph(img, b, mask))
            var best = lo
            for (c in lo..hi) if (cols[c] < cols[best] || (cols[c] == cols[best] && abs(c - centre) < abs(best - centre))) best = c
            cuts += best
            from = best
        }
        val out = ArrayList<Glyph>()
        val bounds = listOf(-1) + cuts + listOf(b.w)
        for (k in 0 until bounds.size - 1) {
            val px = b.pixels.filter { val x = it % img.w - b.x0; x > bounds[k] && x < bounds[k + 1] || (x == bounds[k + 1] && k + 1 == bounds.size - 1) }.toIntArray()
            if (px.size < 3) continue
            out += glyph(img, box(img, px), mask)
        }
        return out
    }

    private fun box(img: Img, px: IntArray): Blob {
        var x0 = Int.MAX_VALUE; var x1 = -1; var y0 = Int.MAX_VALUE; var y1 = -1
        for (p in px) {
            val x = p % img.w
            val y = p / img.w
            if (x < x0) x0 = x; if (x > x1) x1 = x
            if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        return Blob(x0, y0, x1, y1, px)
    }

    /** Glyphs of one run of touching digits as frets: more than two are cut at the widest gaps. */
    private fun cutRun(glyphs: List<Glyph>): List<List<Glyph>> {
        if (glyphs.size <= 2) return listOf(glyphs)
        var widest = 1
        for (i in 1 until glyphs.size) if (glyphs[i].x0 - glyphs[i - 1].x1 > glyphs[widest].x0 - glyphs[widest - 1].x1) widest = i
        return cutRun(glyphs.subList(0, widest)) + cutRun(glyphs.subList(widest, glyphs.size))
    }

    /** The glyph of mark [b]: its darkness (the mark and the pixels round it that aren't line), normalised. */
    private fun glyph(img: Img, b0: Blob, mask: BooleanArray): Glyph {
        // The mark with the light gray of its strokes next to it (a thin digit's anti-aliased edges), within its rows.
        val grown = b0.pixels.toHashSet()
        var front = b0.pixels.toList()
        repeat(2) {
            val next = ArrayList<Int>()
            for (p in front) {
                val px = p % img.w
                val py = p / img.w
                for (dy in -1..1) for (dx in -1..1) {
                    val x = px + dx
                    val y = py + dy
                    if (x < 0 || x >= img.w || y < b0.y0 || y > b0.y1) continue
                    val q = y * img.w + x
                    if (img.line[q] && !mask[q] && grown.add(q)) next += q
                }
            }
            front = next
        }
        val b = box(img, grown.toIntArray())
        val bw = b.w + 2
        val bh = b.h + 2
        val mine = BooleanArray(bw * bh)
        for (p in b.pixels) mine[(p / img.w - b.y0 + 1) * bw + (p % img.w - b.x0 + 1)] = true
        val dark = FloatArray(b.w * b.h)
        for (y in 0 until b.h) for (x in 0 until b.w) {
            val lx = x + 1
            val ly = y + 1
            val gx = b.x0 + x
            val gy = b.y0 + y
            val gi = gy * img.w + gx
            if (mine[ly * bw + lx]) { dark[y * b.w + x] = img.dark(gi); continue }
            if (mask[gi] || img.line[gi]) continue // a line, or another mark
            // A light edge pixel next to the mark (anti-aliasing).
            var near = false
            for (dy in -1..1) for (dx in -1..1) if (mine[(ly + dy) * bw + lx + dx]) near = true
            if (near) dark[y * b.w + x] = img.dark(gi) * 0.8f
        }
        val bin = BooleanArray(b.w * b.h) { dark[it] >= 0.4f }
        // A hole filled in gray (ink spread into a bold 8's small loop) still shows as lighter than the strokes.
        val strict = BooleanArray(b.w * b.h) { dark[it] >= 0.72f }
        val holes = max(holes(bin, b.w, b.h), holes(strict, b.w, b.h))
        return Glyph(b.x0, b.y0, b.x1, b.y1, field(dark, b.w, b.h), b.w.toDouble() / b.h, holes, b.h)
    }

    // ---- Recognising digits ----

    /**
     * [dark] ([w] x [h], 0 paper to 1 ink) scaled to [N] rows, keeping its proportions (centred across, squeezed only
     * if wider than tall), smoothed, with its mean taken out and scaled to length 1, for correlation.
     */
    private fun field(dark: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(N * N)
        val tw = (N * w.toDouble() / h).coerceIn(1.0, N.toDouble())
        val left = (N - tw) / 2
        val sub = 3
        for (ty in 0 until N) for (tx in 0 until N) {
            if (tx + 1 <= left || tx >= left + tw) continue
            var s = 0f
            var c = 0
            for (sy in 0 until sub) for (sx in 0 until sub) {
                val u = tx + (sx + 0.5) / sub
                val v = ty + (sy + 0.5) / sub
                if (u < left || u >= left + tw) continue
                val fx = ((u - left) / tw * w - 0.5).coerceIn(0.0, w - 1.0)
                val fy = (v / N * h - 0.5).coerceIn(0.0, h - 1.0)
                val ix = fx.toInt().coerceAtMost(w - 2).coerceAtLeast(0)
                val iy = fy.toInt().coerceAtMost(h - 2).coerceAtLeast(0)
                val ax = (fx - ix).toFloat()
                val ay = (fy - iy).toFloat()
                fun at(x: Int, y: Int) = dark[min(y, h - 1) * w + min(x, w - 1)]
                s += (1 - ax) * (1 - ay) * at(ix, iy) + ax * (1 - ay) * at(ix + 1, iy) + (1 - ax) * ay * at(ix, iy + 1) + ax * ay * at(ix + 1, iy + 1)
                c++
            }
            out[ty * N + tx] = if (c > 0) s / (sub * sub) else 0f
        }
        return normalise(blur(out))
    }

    private fun blur(f: FloatArray): FloatArray {
        val a = FloatArray(N * N)
        for (y in 0 until N) for (x in 0 until N) {
            val l = f[y * N + max(0, x - 1)]
            val r = f[y * N + min(N - 1, x + 1)]
            a[y * N + x] = 0.25f * l + 0.5f * f[y * N + x] + 0.25f * r
        }
        val b = FloatArray(N * N)
        for (y in 0 until N) for (x in 0 until N) {
            val u = a[max(0, y - 1) * N + x]
            val d = a[min(N - 1, y + 1) * N + x]
            b[y * N + x] = 0.25f * u + 0.5f * a[y * N + x] + 0.25f * d
        }
        return b
    }

    private fun normalise(f: FloatArray): FloatArray {
        val mean = f.average().toFloat()
        var ss = 0.0
        for (i in f.indices) { f[i] -= mean; ss += f[i].toDouble() * f[i] }
        val n = sqrt(ss).toFloat()
        if (n > 1e-6f) for (i in f.indices) f[i] /= n
        return f
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Holes in a binary glyph: enclosed paper areas of a few pixels at least. */
    private fun holes(bin: BooleanArray, w: Int, h: Int): Int {
        val pw = w + 2
        val ph = h + 2
        val paper = BooleanArray(pw * ph) { i -> val x = i % pw - 1; val y = i / pw - 1; x < 0 || y < 0 || x >= w || y >= h || !bin[y * w + x] }
        val seen = BooleanArray(pw * ph)
        val queue = IntArray(pw * ph)
        val minArea = max(1, (w * h) / 80)
        var holes = 0
        var first = true
        for (start in 0 until pw * ph) {
            if (!paper[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            while (head < tail) {
                val c = queue[head++]
                val cx = c % pw
                val cy = c / pw
                for ((dx, dy) in FOUR) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= pw || ny >= ph) continue
                    val n = ny * pw + nx
                    if (paper[n] && !seen[n]) { seen[n] = true; queue[tail++] = n }
                }
            }
            if (first) first = false // the outside (start 0 is the padded corner)
            else if (tail >= minArea) holes++
        }
        return holes
    }

    private val FOUR = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** A digit to compare with: its field, proportions and holes. */
    private class Template(val digit: Int, val field: FloatArray, val aspect: Double, val holes: Int)

    private fun template(g: DigitGlyph): Template? {
        if (g.digit !in 0..9 || g.width <= 0 || g.height <= 0 || g.ink.size < g.width * g.height) return null
        // Tight box around the ink.
        var x0 = g.width; var x1 = -1; var y0 = g.height; var y1 = -1
        for (y in 0 until g.height) for (x in 0 until g.width) if ((g.ink[y * g.width + x].toInt() and 0xFF) >= 64) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val dark = FloatArray(w * h) { (g.ink[(y0 + it / w) * g.width + x0 + it % w].toInt() and 0xFF) / 255f }
        val bin = BooleanArray(w * h) { dark[it] >= 0.5f }
        return Template(g.digit, field(dark, w, h), w.toDouble() / h, holes(bin, w, h))
    }

    /** How alike [g] and [t] are: correlation, less for other proportions and other holes. */
    private fun similarity(g: Glyph, t: Template): Double {
        var s = dot(g.field, t.field)
        s -= 0.35 * min(1.0, abs(ln(g.aspect / t.aspect)))
        if (g.holes != t.holes && !(t.digit == 4 && g.holes <= 1)) s -= if (g.height >= 14) 0.12 else 0.05
        return s
    }

    private fun score(glyphs: List<Glyph>, templates: List<Template>) {
        for (g in glyphs) {
            val sc = DoubleArray(10) { -1.0 }
            for (t in templates) { val v = similarity(g, t); if (v > sc[t.digit]) sc[t.digit] = v }
            g.scores = sc
        }
    }

    /** Reads [n]'s value from its glyphs' scores: a fret of [maxFret] at most; 2 digits start with 1 or 2. */
    private fun decide(n: Num, maxFret: Int) {
        val g = n.glyphs
        fun lead(sc: DoubleArray, pick: Int): Double = sc[pick] - sc.indices.filter { it != pick }.maxOf { sc[it] }
        when (g.size) {
            1 -> {
                val sc = g[0].scores
                val d = sc.indices.maxByOrNull { sc[it] }!!
                n.value = d
                n.digits = "$d"
                n.sure = sc[d] >= SURE && lead(sc, d) >= LEAD
                if (sc[d] < NOT_DIGIT) n.value = -1
            }
            else -> {
                val a = g[0].scores
                val b = g[1].scores
                var best = -1
                var bestScore = Double.NEGATIVE_INFINITY
                for (t in 1..2) for (u in 0..9) {
                    val v = 10 * t + u
                    if (v > maxFret) continue
                    val s = a[t] + b[u]
                    if (s > bestScore) { bestScore = s; best = v }
                }
                if (best < 0) { n.value = -1; return }
                n.value = best
                n.digits = "$best"
                val t = best / 10
                val u = best % 10
                n.sure = a[t] >= SURE && b[u] >= SURE && lead(b, u) >= LEAD && a[t] - a[3 - t] >= LEAD
                if (min(a[t], b[u]) < NOT_DIGIT) n.value = -1
            }
        }
    }

    // ---- Built-in digits ----

    /**
     * Plain digits drawn from strokes (a simple sans-serif of this file's own), at a regular and a bold weight: a
     * fallback when no fonts are given, and a second opinion when they are.
     */
    private val builtIn: List<Template> by lazy {
        val out = ArrayList<Template>()
        for (weight in doubleArrayOf(0.12, 0.19)) for (d in 0..9) {
            template(strokeDigit(d, 48, weight))?.let { out += it }
        }
        out
    }

    /** Digit [d]'s strokes: polylines in a box 0.6 wide and 1 tall (y down). */
    private fun strokes(d: Int): List<List<Pair<Double, Double>>> {
        fun arc(cx: Double, cy: Double, rx: Double, ry: Double, from: Double, to: Double): List<Pair<Double, Double>> {
            val steps = max(4, (abs(to - from) / 10).toInt())
            return (0..steps).map { val t = Math.toRadians(from + (to - from) * it / steps); cx + rx * cos(t) to cy + ry * sin(t) }
        }
        return when (d) {
            0 -> listOf(arc(0.3, 0.5, 0.25, 0.47, 0.0, 360.0))
            1 -> listOf(listOf(0.13 to 0.2, 0.34 to 0.03, 0.34 to 0.97))
            2 -> listOf(arc(0.3, 0.29, 0.24, 0.26, 200.0, 380.0) + listOf(0.06 to 0.97, 0.56 to 0.97))
            3 -> listOf(arc(0.3, 0.27, 0.22, 0.23, 200.0, 450.0), arc(0.3, 0.72, 0.25, 0.25, 270.0, 520.0))
            4 -> listOf(listOf(0.43 to 0.97, 0.43 to 0.03, 0.04 to 0.68, 0.58 to 0.68))
            5 -> listOf(listOf(0.52 to 0.03, 0.13 to 0.03, 0.09 to 0.47) + arc(0.3, 0.67, 0.25, 0.29, 215.0, 520.0))
            6 -> listOf(arc(0.31, 0.68, 0.24, 0.28, 0.0, 360.0), arc(0.33, 0.55, 0.27, 0.5, 300.0, 170.0))
            7 -> listOf(listOf(0.04 to 0.03, 0.56 to 0.03, 0.22 to 0.97))
            8 -> listOf(arc(0.3, 0.26, 0.2, 0.22, 0.0, 360.0), arc(0.3, 0.72, 0.25, 0.25, 0.0, 360.0))
            else -> strokes(6).map { line -> line.map { (x, y) -> 0.6 - x to 1 - y } }
        }
    }

    /** Draws digit [d] from its [strokes] at [height] pixels, strokes [weight] of the height thick. */
    private fun strokeDigit(d: Int, height: Int, weight: Double): DigitGlyph {
        val pad = weight / 2 + 0.02
        val w = ((0.6 + 2 * pad) * height).roundToInt()
        val h = ((1 + 2 * pad) * height).roundToInt()
        val ink = ByteArray(w * h)
        val lines = strokes(d)
        val half = weight * height / 2
        for (y in 0 until h) for (x in 0 until w) {
            val px = (x + 0.5) / height - pad
            val py = (y + 0.5) / height - pad
            var best = Double.MAX_VALUE
            for (l in lines) for (k in 0 until l.size - 1) best = min(best, segDist(px, py, l[k], l[k + 1]))
            val v = (half - best * height + 0.5).coerceIn(0.0, 1.0)
            ink[y * w + x] = (v * 255).roundToInt().toByte()
        }
        return DigitGlyph(d, w, h, ink, "built-in")
    }

    private fun segDist(px: Double, py: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else (((px - a.first) * dx + (py - a.second) * dy) / len).coerceIn(0.0, 1.0)
        val ex = a.first + t * dx - px
        val ey = a.second + t * dy - py
        return sqrt(ex * ex + ey * ey)
    }

    // ---- Triplets ----

    /**
     * The triplet marks under staff [s]: a 3 the size of the frets (or a little bigger), with a bracket or beam
     * running from it, or on its own; each as the stretch across the staff it covers (from x to x).
     */
    private fun triplets(img: Img, s: Staff, dh: Double, mask: BooleanArray): List<Triplet> {
        val sp = s.spacing
        val y0 = (s.bottom + 0.35 * sp).toInt()
        val y1 = (s.bottom + 4.6 * sp).toInt()
        val marks = blobs(img, (s.x0 - sp).toInt(), y0, (s.x1 + sp).toInt(), y1, null, 50_000)
        val threes = marks.filter { b -> b.h >= 0.6 * dh && b.h <= 1.6 * dh && b.w >= 0.3 * dh && b.w <= 1.3 * dh && b.y0 > y0 && b.y1 < y1 }
        val strokes = marks.filter { b -> b.w >= 1.2 * dh && b.h <= 1.3 * dh }
        val out = ArrayList<Triplet>()
        for (b in threes) {
            // A bracket or beam beside it (or over it): the stretch it covers.
            var x0 = b.x0.toDouble()
            var x1 = b.x1.toDouble()
            for (st in strokes) {
                val beside = st.y1 >= b.y0 - 1.6 * dh && st.y0 <= b.y1 + 0.3 * dh &&
                    (st.x1 >= b.x0 - 1.2 * dh && st.x0 <= b.x1 + 1.2 * dh)
                if (beside) { x0 = min(x0, st.x0.toDouble()); x1 = max(x1, st.x1.toDouble()) }
            }
            out += Triplet(x0, x1, glyph(img, b, mask))
        }
        return out
    }

    /** A mark under a staff that may be a triplet's 3: the stretch it covers (with its bracket) and its glyph. */
    private class Triplet(val x0: Double, val x1: Double, val glyph: Glyph)

    // ---- Putting it together ----

    private fun finish(pages: List<PageRead>, instrument: Instrument, digitGlyphs: ((Int) -> List<DigitGlyph>)?, warnings: MutableSet<String>): ImageTabResult {
        val tabsAll = pages.flatMap { it.tabs }
        val guitar = pages.sumOf { it.guitar }
        if (guitar > 0) warnings += "Skipped $guitar guitar tab staff${if (guitar > 1) "s" else ""} (6 lines); bouzouki tabs have 3 or 4"
        if (tabsAll.isEmpty()) {
            warnings += NO_STAFFS
            return ImageTabResult(ParsedTab(instrument, emptyList(), warnings.toList(), emptyList()), 0, 0, 0.0, emptyList(), 0)
        }
        val allNums = pages.flatMap { p -> p.numbers.values.flatten() }
        val glyphs = allNums.flatMap { it.glyphs }
        // Fonts given, drawn at the usual digit height.
        val dh = median(pages.map { it.digitHeight }.filter { it > 0 }) ?: 20.0
        // Small digits look different a pixel bigger or smaller (strokes snap to whole pixels): two sizes are drawn.
        val sizes = listOf(dh.roundToInt(), (dh * 1.3).roundToInt()).map { it.coerceIn(8, 200) }.distinct()
        val given = try {
            sizes.flatMap { digitGlyphs?.invoke(it).orEmpty() }.mapNotNull { template(it) }
        } catch (e: RuntimeException) {
            emptyList()
        }
        val base = given + builtIn
        score(glyphs, base)
        // The picture's own font: the digits read confidently, averaged; it settles between the likeliest digits.
        val own = ownTemplates(allNums)
        if (own.isNotEmpty()) refine(glyphs, own)
        // The tab's instrument: the line count of most staffs with numbers.
        val lineCounts = pages.flatMap { p -> p.tabs.filter { p.numbers[it].orEmpty().isNotEmpty() }.map { it.n } }
        val tabLines = if (lineCounts.count { it == 4 } >= lineCounts.count { it == 3 }) 4 else 3
        val tabInstrument = if (tabLines == 3) Instrument.TRICHORDO else Instrument.TETRACHORDO
        for (n in allNums) decide(n, tabInstrument.frets)
        debug?.let { d ->
            for (n in allNums) for (g in n.glyphs) d(-2 - n.staff.page, "glyph", doubleArrayOf(g.x0.toDouble(), g.y0.toDouble(), g.x1.toDouble(), g.y1.toDouble(), g.holes.toDouble(), g.aspect) + g.scores)
            for (n in allNums) d(n.staff.page, "number", doubleArrayOf(n.x0.toDouble(), n.glyphs.minOf { it.y0 }.toDouble(), n.x1.toDouble(),
                n.glyphs.maxOf { it.y1 }.toDouble(), n.value.toDouble(), if (n.sure) 1.0 else 0.0))
        }
        // A staff whose marks are hardly ever digits read confidently isn't a tab (a notation staff with a line
        // missed, say): it's left out.
        val notTab = pages.flatMap { p ->
            p.tabs.filter { s ->
                val ns = p.numbers[s].orEmpty()
                ns.size >= 6 && ns.count { it.value >= 0 && it.sure } < 0.3 * ns.size
            }
        }.toSet()
        if (notTab.isNotEmpty()) warnings += "Left out ${notTab.size} staff${if (notTab.size > 1) "s" else ""} with lines like a tab's but no clear numbers"
        if (lineCounts.any { it != tabLines }) warnings += "This picture mixes 3- and 4-line tab staffs; only the $tabLines-line ones were read"
        if (tabInstrument != instrument) {
            warnings += "This tab is for the ${tabInstrument.name.lowercase()} ($tabLines courses), not the ${instrument.name.lowercase()}"
        }
        // Triplet 3s: alike the 3 templates, better than any other digit.
        val threes = (base + own).filter { it.digit == 3 }
        // Systems in order: pages, then top to bottom.
        val systems = ArrayList<Pair<PageRead, Staff>>()
        for (p in pages) for (s in p.tabs) if (s.n == tabLines && s !in notTab) systems += p to s
        val placed = timeline(systems.map { (p, s) -> p.numbers[s].orEmpty().filter { it.value >= 0 } }, tabInstrument)
        val notes0 = placed.flatten().map { it.note }
        val unsure = placed.flatten().count { !it.sure }
        val total = placed.flatten().size
        // Triplets: the notes under each mark.
        val triplets = ArrayList<IntRange>()
        var first = 0
        for ((k, ps) in systems.withIndex()) {
            val (p, s) = ps
            val notes = placed[k]
            for (tr in p.triplets[s].orEmpty()) {
                val tg = tr.glyph
                val a = tr.x0
                val b = tr.x1
                val sc = DoubleArray(10) { -1.0 }
                for (t in base + own) { val v = similarity(tg, t); if (v > sc[t.digit]) sc[t.digit] = v }
                val three = threes.maxOfOrNull { similarity(tg, it) } ?: -1.0
                if (three < 0.5 || sc.indices.maxByOrNull { sc[it] } != 3) continue
                val dhs = (s.spacing * 0.8)
                var idx = notes.indices.filter { notes[it].x in a - 0.4 * dhs..b + 0.4 * dhs }
                if (b - a < 2 * dhs) {
                    // A 3 on its own: the three notes nearest to it.
                    val mid = (a + b) / 2
                    idx = notes.indices.sortedBy { abs(notes[it].x - mid) }.take(3).sorted()
                }
                if (idx.size >= 2) triplets += (first + idx.first())..(first + idx.last())
            }
            first += notes.size
        }
        // The form: bars and sections, from barlines, repeat signs and endings drawn for PdfForm.
        var start = 0
        val formSystems = systems.mapIndexed { k, (p, s) ->
            val k2 = p.scale
            // The staff over it for PdfForm: the system's top one, under which its endings are drawn.
            val above = systemTop(s, p.tabs, p.notation)
            PdfForm.System(
                pages.indexOf(p), DoubleArray(s.n) { s.ys[it] * k2 }, above?.let { a -> DoubleArray(a.n) { a.ys[it] * k2 } },
                s.x0 * k2, s.x1 * k2, start, DoubleArray(placed[k].size) { placed[k][it].x * k2 },
            ).also { start += placed[k].size }
        }
        val formWarnings = LinkedHashSet<String>()
        // The pages as PdfForm reads them, with the numbers of the 1st/2nd-time brackets read now.
        val formPages = pages.map { p ->
            val f = p.form
            val glyphs = f.labels.mapNotNull { l ->
                val sc = DoubleArray(10) { -1.0 }
                for (t in base + own) { val v = similarity(l.glyph, t); if (v > sc[t.digit]) sc[t.digit] = v }
                val d = sc.indices.maxByOrNull { sc[it] }!!
                if (sc[d] < NOT_DIGIT || d !in 1..4) null
                else PdfGlyph(if (l.dot) "$d." else "$d", l.x0, l.x1, l.baseline, l.size, true, "")
            }
            PdfPageContent(f.width, f.height, glyphs, f.lines, 1, f.vlines, f.arcs)
        }
        val form = try { PdfForm.read(formPages, formSystems, notes0.size, formWarnings) } catch (e: RuntimeException) {
            warnings += "Couldn't make sense of the repeat signs: the tab is read in written order"
            PdfForm.Form(listOf(TabSection(0, notes0.size)), IntArray(notes0.size))
        }
        warnings += formWarnings.filterNot { it.startsWith("Left out \"") }
        val notes = notes0.mapIndexed { i, nt -> nt.copy(bar = form.bar[i]) }
        if (notes.isEmpty()) warnings += NO_NUMBERS
        if (unsure > 0) warnings += "$UNSURE_PREFIX$unsure of $total (check them by ear)"
        val stacks = notes.count { it.stack.isNotEmpty() }
        if (stacks > 0) warnings += "Kept $stacks chord${if (stacks > 1) "s" else ""} (notes played together)"
        if (triplets.isNotEmpty()) warnings += "Found ${triplets.size} triplet${if (triplets.size > 1) "s" else ""} (3 notes in the time of 2)"
        if (notes.isNotEmpty()) warnings += WORDS
        val confidence = if (total == 0) 0.0 else (total - unsure).toDouble() / total
        return ImageTabResult(
            ParsedTab(tabInstrument, notes, warnings.toList(), if (notes.isEmpty()) emptyList() else form.sections),
            total, unsure, confidence, triplets, systems.count { (p, s) -> p.numbers[s].orEmpty().isNotEmpty() },
        )
    }

    /** The picture's own digits: the fields of digits read confidently (and fitting a fret), averaged per digit. */
    private fun ownTemplates(nums: List<Num>): List<Template> {
        val sums = Array(10) { FloatArray(N * N) }
        val aspects = DoubleArray(10)
        val holes = Array(10) { IntArray(4) }
        val counts = IntArray(10)
        for (n in nums) {
            if (n.glyphs.size > 2) continue
            decide(n, max(Instrument.TRICHORDO.frets, Instrument.TETRACHORDO.frets))
            if (!n.sure || n.value < 0) continue
            val ds = n.digits.map { it - '0' }
            if (ds.size != n.glyphs.size) continue
            for ((g, d) in n.glyphs.zip(ds)) {
                val next = g.scores.indices.filter { it != d }.maxOf { g.scores[it] }
                if (g.scores[d] < OWN_SURE || g.scores[d] - next < 0.1) continue
                for (i in 0 until N * N) sums[d][i] += g.field[i]
                aspects[d] += g.aspect
                holes[d][min(3, g.holes)]++
                counts[d]++
            }
        }
        val out = ArrayList<Template>()
        for (d in 0..9) {
            if (counts[d] < 3) continue
            val f = FloatArray(N * N) { sums[d][it] / counts[d] }
            val h = holes[d].indices.maxByOrNull { holes[d][it] }!!
            out += Template(d, normalise(f), aspects[d] / counts[d], h)
        }
        return out
    }

    /**
     * Each glyph's likeliest digits (by the fonts) compared again with the picture's [own] digits, when it has
     * them all: the closest of them wins, by its likeness to the picture's own (a fair contest: a digit the picture
     * has many confident copies of would beat one it has none of if own and font likenesses were mixed).
     */
    private fun refine(glyphs: List<Glyph>, own: List<Template>) {
        val byDigit = own.associateBy { it.digit }
        for (g in glyphs) {
            val sc = g.scores
            val top = sc.indices.sortedByDescending { sc[it] }.take(3).filter { sc[it] >= sc.max() - 0.15 }
            if (top.size < 2 || top.any { it !in byDigit }) continue
            val mine = top.associateWith { similarity(g, byDigit.getValue(it)) }
            // The contenders keep their places among the other digits, in the order the picture's own font gives.
            val slots = top.map { sc[it] }.sortedDescending()
            val order = top.sortedByDescending { mine.getValue(it) }
            val lead = mine.getValue(order[0]) - mine.getValue(order[1])
            val out = sc.copyOf()
            for ((k, d) in order.withIndex()) out[d] = slots[k]
            // The lead between the first two is the picture's own font's.
            out[order[1]] = min(out[order[1]], out[order[0]] - lead)
            g.scores = out
        }
    }

    /** A note as read, with its column's x on the page and whether its numbers were read confidently. */
    private class Placed(val note: TabNote, val x: Double, val sure: Boolean)

    /**
     * The notes of all [systems] (numbers, by x) in reading order, with columns on one timeline as [PdfTab] makes
     * them: x scaled so the typical note spacing is [COLUMNS_PER_NOTE] columns, each system [SYSTEM_GAP] after
     * the one before. Numbers at one x are a chord: its highest note, with the others in its stack.
     */
    private fun timeline(systems: List<List<Num>>, inst: Instrument): List<List<Placed>> {
        val onsets = systems.map { nums ->
            val groups = ArrayList<MutableList<Num>>()
            for (n in nums.sortedBy { it.x }) {
                val g = groups.lastOrNull()
                val size = (n.glyphs.first().height).toDouble()
                if (g != null && abs(n.x - g[0].x) < 0.5 * size) g += n else groups += mutableListOf(n)
            }
            groups
        }
        val gaps = onsets.flatMap { g -> g.zipWithNext { a, b -> b[0].x - a[0].x } }.filter { it > 0 }.sorted()
        val typical = if (gaps.isNotEmpty()) gaps[gaps.size / 2] else 20.0
        val unit = typical / COLUMNS_PER_NOTE
        val out = ArrayList<List<Placed>>()
        var next = 0
        for ((k, system) in onsets.withIndex()) {
            val notes = ArrayList<Placed>()
            out += notes
            if (system.isEmpty()) continue
            val start = next
            val x0 = system[0][0].x
            var prev = start - 1
            for (g in system) {
                val col = max(prev + 1, start + ((g[0].x - x0) / unit).roundToInt())
                val chord = g.distinctBy { it.line }.sortedWith(
                    compareByDescending<Num> { inst.midiAt(it.staff.n - 1 - it.line, it.value) }.thenBy { it.line },
                )
                val top = chord[0]
                notes += Placed(
                    TabNote(top.staff.n - 1 - top.line, top.value, col, Articulation.PICK, chord.drop(1).map { (it.staff.n - 1 - it.line) to it.value }, k),
                    g.sumOf { it.x } / g.size,
                    chord.all { it.sure },
                )
                prev = col
            }
            next = prev + 1 + SYSTEM_GAP
        }
        return out
    }

    // ---- The form, drawn for PdfForm ----

    /**
     * The page as [PdfForm] reads it, in points ([scale] per pixel): barlines across the staffs (with their
     * thickness), repeat dots on them (as small round curves), and thin lines above the systems with their hooks
     * (1st/2nd-time brackets), with digits and full stops under them as text.
     */
    private fun formPage(img: Img, tabs: List<Staff>, notation: List<Staff>, scale: Double, mask: BooleanArray, dh: Double): FormDraft {
        val vlines = ArrayList<PdfVLine>()
        val arcs = ArrayList<PdfArc>()
        val lines = ArrayList<PdfLine>()
        val labels = ArrayList<Label>()
        for (s in tabs + notation) {
            vlines += barlines(img, s, tabs + notation).map { PdfVLine(it.x * scale, it.y0 * scale, it.y1 * scale, it.width * scale) }
            arcs += dots(img, s).map { b -> PdfArc(b.x0 * scale, (b.x1 + 1) * scale, b.y0 * scale, (b.y1 + 1) * scale) }
        }
        if (dh >= 4) for (s in tabs) {
            // 1st/2nd-time brackets: thin lines above the system (over its notation staff, if it has one), with a hook
            // down at the left, and the ending's number under it.
            val head = systemTop(s, tabs, notation) ?: s
            val sp = head.spacing
            val y0 = max(0, (head.top - 11 * sp).toInt())
            val y1 = (head.top - 0.8 * sp).toInt()
            if (y1 <= y0) continue
            for (l in bracketLines(img, y0, y1, max(4 * s.spacing, 3.0 * dh), 0.35 * sp + 1, s)) {
                lines += PdfLine(l.x0 * scale, l.x1 * scale, l.y * scale, l.thickness * scale)
                // The hook: ink straight down from the line's left end.
                var len = 0
                val hx = (l.x0..min(img.w - 1, l.x0 + 2)).maxByOrNull { x -> var n = 0; var y = l.y1 + 1; while (y < img.h && img.line[y * img.w + x]) { n++; y++ }; n } ?: l.x0
                var y = l.y1 + 1
                while (y < img.h && img.line[y * img.w + hx]) { len++; y++ }
                if (len >= 0.6 * s.spacing) vlines +=PdfVLine(hx * scale, l.y0 * scale, (l.y1 + 1 + len) * scale, 1.0 * scale)
                // The number under its left end.
                val under = blobs(img, l.x0 + 1, l.y1 + 2, min(img.w - 1, (l.x0 + 4 * s.spacing).toInt()), min(img.h - 1, (l.y1 + 3 * sp).toInt()), null, 5_000)
                val digits = under.filter { b -> b.h >= 0.5 * dh && b.h <= 1.7 * dh && b.w >= 0.15 * dh && b.w <= 1.3 * dh }.sortedBy { it.x0 }.take(1)
                for (d in digits) {
                    val dot = under.any { b -> b.w <= 0.4 * dh && b.h <= 0.4 * dh && b.x0 > d.x1 && b.x0 - d.x1 <= 0.5 * dh && abs(b.y1 - d.y1) <= 0.25 * dh }
                    labels += Label(glyph(img, d, mask), d.x0 * scale, (d.x1 + 1) * scale, (d.y1 + 1) * scale, d.h / 0.72 * scale, dot)
                }
            }
        }
        return FormDraft(img.w * scale, img.h * scale, vlines, arcs, lines, labels)
    }

    /** The page as drawn for [PdfForm], before the numbers of its 1st/2nd-time brackets are read ([labels]). */
    private class FormDraft(
        val width: Double, val height: Double, val vlines: List<PdfVLine>, val arcs: List<PdfArc>, val lines: List<PdfLine>,
        val labels: List<Label>,
    )

    /** A digit under a bracket line (an ending's number), followed by a full stop or not; in points. */
    private class Label(val glyph: Glyph, val x0: Double, val x1: Double, val baseline: Double, val size: Double, val dot: Boolean)

    /** The notation staff over tab staff [s] (the nearest above it, with no other tab staff between), if any. */
    private fun above(s: Staff, tabs: List<Staff>, notation: List<Staff>): Staff? =
        notation.filter { o -> o.bottom < s.top && s.top - o.bottom < 12 * s.spacing && o.x0 < s.x1 && o.x1 > s.x0 }
            .filter { o -> tabs.none { t -> t.top > o.bottom && t.bottom < s.top } }.maxByOrNull { it.bottom }

    /**
     * The top staff of tab staff [s]'s system: the notation staffs stacked over it (a voice over the bouzouki's
     * notes, with lyrics and chord diagrams between), up to the staff above that has a tab of its own; null if none.
     */
    private fun systemTop(s: Staff, tabs: List<Staff>, notation: List<Staff>): Staff? {
        var head = above(s, tabs, notation) ?: return null
        while (true) {
            val h0 = head
            head = notation.filter { o -> o.bottom < h0.top && h0.top - o.bottom < 20 * h0.spacing && tabs.none { t -> t.top > o.bottom && t.bottom < h0.top } }
                .maxByOrNull { it.bottom } ?: return head
        }
    }

    /** Thin horizontal lines at least [minLength] long, [maxThick] thick at most, between rows [y0] and [y1], over [s]. */
    private fun bracketLines(img: Img, y0: Int, y1: Int, minLength: Double, maxThick: Double, s: Staff): List<Line> {
        val w = img.w
        val segs = ArrayList<IntArray>() // y, x0, x1
        for (y in y0..y1) {
            var x = max(0, s.x0 - 2)
            val end = min(w - 1, s.x1 + (2 * s.spacing).toInt())
            while (x <= end) {
                if (!img.line[y * w + x]) { x++; continue }
                var e = x
                while (e + 1 <= end && img.line[y * w + e + 1]) e++
                if (e - x + 1 >= minLength) segs += intArrayOf(y, x, e)
                x = e + 1
            }
        }
        // Rows of one line (its thickness) joined.
        val out = ArrayList<Line>()
        val used = BooleanArray(segs.size)
        for (i in segs.indices) {
            if (used[i]) continue
            var top = segs[i][0]
            var bottom = top
            var x0 = segs[i][1]
            var x1 = segs[i][2]
            for (j in i + 1 until segs.size) {
                if (used[j] || segs[j][0] > bottom + 1) continue
                if (min(x1, segs[j][2]) - max(x0, segs[j][1]) < 0.5 * (segs[j][2] - segs[j][1])) continue
                used[j] = true
                bottom = segs[j][0]
                x0 = min(x0, segs[j][1])
                x1 = max(x1, segs[j][2])
            }
            if (bottom - top + 1 <= maxThick && out.size < 50) out += Line((top + bottom) / 2.0, top, bottom, x0, x1)
        }
        return out
    }

    /** A barline: its middle [x], from [y0] down to [y1], [width] thick (pixels). */
    private class BarLine(val x: Double, val y0: Double, val y1: Double, val width: Double)

    /**
     * Barlines on staff [s]: columns of ink from its top line to its bottom line without a break, that don't run up
     * past the top line (a stem does) unless they reach the staff above (a barline through the system). Each as
     * x, top, bottom and thickness.
     */
    private fun barlines(img: Img, s: Staff, all: List<Staff>): List<BarLine> {
        val w = img.w
        val top = s.lines.first().y0
        val bottom = s.lines.last().y1
        val height = bottom - top + 1
        val allowedGap = max(1, height / 40)
        val above = all.filter { it.page == s.page && it.bottom < s.top && it.x0 < s.x1 && it.x1 > s.x0 }.maxByOrNull { it.bottom }
        val cols = BooleanArray(w)
        val x0 = max(0, (s.x0 - s.spacing).toInt())
        val x1 = min(w - 1, (s.x1 + s.spacing).toInt())
        for (x in x0..x1) {
            var gaps = 0
            for (y in top..bottom) if (!img.line[y * w + x]) { gaps++; if (gaps > allowedGap) break }
            if (gaps > allowedGap) continue
            var up = top
            while (up > 0 && img.line[(up - 1) * w + x]) up--
            val rise = top - up
            val reaches = above != null && up <= above.bottom + 0.5 * above.spacing
            if (rise > 0.45 * s.spacing && !reaches) continue
            cols[x] = true
        }
        val out = ArrayList<BarLine>()
        var x = x0
        while (x <= x1) {
            if (!cols[x]) { x++; continue }
            var e = x
            while (e + 1 <= x1 && cols[e + 1]) e++
            val width = e - x + 1
            if (width <= 0.6 * s.spacing) {
                val mid = (x + e) / 2
                var y0 = top
                while (y0 > 0 && img.line[(y0 - 1) * w + mid]) y0--
                var y1 = bottom
                while (y1 < img.h - 1 && img.line[(y1 + 1) * w + mid]) y1++
                out += BarLine((x + e) / 2.0 + 0.5, y0.toDouble(), y1 + 1.0, width.toDouble())
            }
            x = e + 1
        }
        return out
    }

    /** Repeat dots on staff [s]: small round solid marks in its spaces. */
    private fun dots(img: Img, s: Staff): List<Blob> {
        val sp = s.spacing
        return blobs(img, s.x0.toInt(), s.top.toInt(), s.x1.toInt(), s.bottom.toInt(), null, 5_000).filter { b ->
            b.w >= 0.2 * sp && b.w <= 0.75 * sp && b.h >= 0.2 * sp && b.h <= 0.75 * sp && abs(b.w - b.h) <= 0.35 * max(b.w, b.h) &&
                b.pixels.size >= 0.55 * b.w * b.h && s.ys.none { abs(b.cy - it) < 0.25 * sp }
        }
    }
}
