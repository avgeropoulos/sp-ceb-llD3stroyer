package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads bouzouki tabs from PDF files, as Sibelius, MuseScore, Guitar Pro or Finale export them:
 * a tab staff of 4 lines (tetrachordo, C F A D) or 3 lines (trichordo, D A D), usually under a
 * notation staff, with the frets written as text on the lines.
 *
 * 1. The pages are read with a small PDF reader ([PdfDocument], [PdfContentReader]): text glyphs
 *    with their positions and sizes, and horizontal lines.
 * 2. Tab staffs are runs of 3 or 4 equally spaced long thin lines (notation staffs have 5 and are
 *    skipped; guitar tabs have 6 and are skipped with a warning).
 * 3. Frets are digits centred on a line, of the size most frets in the file have (which leaves out
 *    time signatures, bar numbers, tuplets, lyrics and page numbers); digits that touch make one
 *    fret. The line gives the course, top line = highest course. Hammer-ons, pull-offs and slides
 *    written as text (h, p, sl, / or \) mark the note after them.
 * 4. Notes are put in reading order (page, then top to bottom), and their x positions become
 *    columns of one timeline across the systems, about 2 columns per typical note spacing (like a
 *    text tab), so the aligner's tempo prior still works. Notes at one x (chords) share a column.
 *
 * A PDF without any staffs (a text tab printed to PDF) has its page text rebuilt and handed to
 * [TabParser]. A PDF with no readable digits, such as a scan, gives no notes and [NO_NUMBERS].
 */
object PdfTab {
    /** The warning for a PDF whose tab is a picture (or has no tab at all). */
    const val NO_NUMBERS = "This PDF has no readable tab numbers (it may be a scanned picture)"

    /** Reads the bouzouki tab in [pdf]. [instrument] is the song's; the tab's own (by its line count) is reported in the result. */
    fun read(pdf: ByteArray, instrument: Instrument): ParsedTab {
        val warnings = LinkedHashSet<String>()
        val pages = try {
            readPages(pdf, warnings)
        } catch (e: RuntimeException) {
            warnings += "This PDF couldn't be read"
            emptyList()
        } catch (e: StackOverflowError) {
            warnings += "This PDF couldn't be read"
            emptyList()
        }
        return try {
            fromPages(pages, instrument, warnings)
        } catch (e: RuntimeException) {
            ParsedTab(instrument, emptyList(), (warnings + NO_NUMBERS).toList())
        }
    }

    /** The content of every page of [pdf]; problems go to [warnings]. */
    internal fun readPages(pdf: ByteArray, warnings: MutableSet<String>): List<PdfPageContent> {
        val doc = PdfDocument(pdf)
        val reader = PdfContentReader(doc)
        val pages = doc.pages()
        val out = ArrayList<PdfPageContent>()
        for (p in pages) {
            try {
                out += reader.read(p)
            } catch (e: RuntimeException) {
                warnings += "A page of this PDF couldn't be read"
            }
        }
        warnings += doc.warnings
        warnings += reader.warnings
        if (pages.isEmpty() && !doc.locked) warnings += "This file doesn't look like a PDF, or it is too damaged to read"
        return out
    }

    // ---- Tuning ----

    /** Lines thicker than this, in points, are beams or bars, not staff lines. */
    private const val MAX_LINE_THICKNESS = 1.6
    /** Pieces of one staff line (same height) closer than this, in points, are joined. */
    private const val JOIN_GAP = 15.0
    /** Shortest staff, in points. */
    private const val MIN_STAFF_LENGTH = 40.0
    /** Range of staff line spacings, in points. */
    private const val MIN_SPACING = 2.0
    private const val MAX_SPACING = 20.0
    /** Height of a digit's middle above its baseline, as a fraction of the font size. */
    private const val DIGIT_MIDDLE = 0.36
    /** How far a fret's middle may be from its line, as a fraction of the line spacing. */
    private const val ON_LINE = 0.35
    /** Frets are this size range around the median digit size on the tab staffs. */
    private const val MIN_SIZE_RATIO = 0.55
    private const val MAX_SIZE_RATIO = 1.45
    /** Columns per typical note spacing, and the gap between systems. */
    private const val COLUMNS_PER_NOTE = 2.0
    private const val SYSTEM_GAP = 2
    /** Caps that keep a hostile file from making the geometry slow (real pages are far below them). */
    private const val MAX_LINES_PER_PAGE = 2_000
    private const val MAX_STAFFS_PER_PAGE = 200
    private const val MAX_MARKS_PER_STAFF = 1_000

    /** A tab staff: its page, its lines' heights from the top, and its horizontal extent. */
    private class Staff(val page: Int, val ys: DoubleArray, val x0: Double, val x1: Double) {
        val spacing = (ys.last() - ys.first()) / (ys.size - 1)
        val lines get() = ys.size
        val top get() = ys.first()
        val bottom get() = ys.last()

        /** The line nearest to height [y], and whether [y] is on it. */
        fun lineAt(y: Double): Int = ys.indices.minByOrNull { abs(ys[it] - y) }!!
        fun isOn(line: Int, y: Double) = abs(ys[line] - y) <= ON_LINE * spacing
    }

    /** A fret read from the staff: [line] counted from the top. */
    private class Fret(
        val staff: Staff, val line: Int, val fret: Int, val x0: Double, val x1: Double,
        val baseline: Double, val size: Double, val bracketed: Boolean,
    ) {
        val x get() = (x0 + x1) / 2
        var articulation = Articulation.PICK
    }

    private class Counts {
        var guitar = 0
        var notation = 0
        var stacks = 0
        var bracketed = 0
        var tooBig = 0
    }

    /** A page's upright glyphs sorted by baseline, to pick out the ones near a staff quickly. */
    private class GlyphIndex(page: PdfPageContent) {
        private val sorted = page.glyphs.filter { it.upright && it.size > 0 }.sortedBy { it.baseline }
        private val baselines = DoubleArray(sorted.size) { sorted[it].baseline }

        /** The glyphs whose baseline is from [top] to [bottom]. */
        fun band(top: Double, bottom: Double): List<PdfGlyph> = sorted.subList(firstAtOrBelow(top), firstAtOrBelow(Math.nextUp(bottom)))

        private fun firstAtOrBelow(y: Double): Int {
            var lo = 0
            var hi = baselines.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (baselines[mid] < y) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    private fun fromPages(pages: List<PdfPageContent>, instrument: Instrument, warnings: MutableSet<String>): ParsedTab {
        val counts = Counts()
        val staffs = pages.flatMapIndexed { i, p -> staffs(p, i, counts) }
        if (counts.guitar > 0) warnings += "Skipped ${counts.guitar} guitar tab staff${if (counts.guitar > 1) "s" else ""} (6 lines); bouzouki tabs have 3 or 4"

        if (staffs.isEmpty()) {
            // No staffs at all: maybe a text tab printed to PDF. Rebuild the text and let the text tab reader try.
            val text = if (counts.notation == 0 && counts.guitar == 0) pages.joinToString("\n\n") { pageText(it) } else ""
            if (text.any { it.isDigit() }) {
                val t = TabParser.parse(text, instrument)
                if (t.notes.isNotEmpty()) return ParsedTab(t.instrument, t.notes, (warnings + t.warnings).toList())
            }
            return ParsedTab(instrument, emptyList(), (warnings + NO_NUMBERS).toList())
        }

        // Digits on the staffs, then the usual fret size among them.
        val index = pages.map { GlyphIndex(it) }
        val candidates = staffs.map { s -> s to digitsOn(index[s.page], s) }
        val sizes = candidates.flatMap { (_, g) -> g.map { it.size } }.sorted()
        if (sizes.isEmpty()) return ParsedTab(instrumentFor(staffs.map { it.lines }), emptyList(), (warnings + NO_NUMBERS).toList())
        val usual = sizes[sizes.size / 2]

        // The tab's instrument: the line count of most staffs with frets.
        val lineCounts = candidates.filter { (_, g) -> g.isNotEmpty() }.map { it.first.lines }
        val tabLines = if (lineCounts.count { it == 4 } >= lineCounts.count { it == 3 }) 4 else 3
        val tabInstrument = instrumentFor(listOf(tabLines))
        val skipped = candidates.count { (s, g) -> s.lines != tabLines && g.isNotEmpty() }
        if (skipped > 0) warnings += "This PDF mixes 3- and 4-line tab staffs; only the $tabLines-line ones were read"
        if (tabInstrument != instrument) {
            warnings += "This tab is for the ${tabInstrument.name.lowercase()} ($tabLines courses), not the ${instrument.name.lowercase()}"
        }

        val systems = candidates.filter { it.first.lines == tabLines }.map { (s, digits) ->
            val g = digits.filter { it.size in usual * MIN_SIZE_RATIO..usual * MAX_SIZE_RATIO }
            val frets = frets(index[s.page], s, g, tabInstrument.frets, counts)
            articulations(index[s.page], s, frets, usual)
            frets
        }
        val notes = timeline(systems, counts)
        if (counts.stacks > 0) warnings += "Kept ${counts.stacks} chord${if (counts.stacks > 1) "s" else ""} (notes played together)"
        if (counts.bracketed > 0) warnings += "Kept ${counts.bracketed} note${if (counts.bracketed > 1) "s" else ""} in brackets (tied or ghost notes)"
        if (counts.tooBig > 0) warnings += "Left out ${counts.tooBig} number${if (counts.tooBig > 1) "s" else ""} on the tab lines too big to be frets"
        if (notes.isEmpty()) warnings += NO_NUMBERS
        return ParsedTab(tabInstrument, notes, warnings.toList())
    }

    private fun instrumentFor(lines: List<Int>) = if (lines.isNotEmpty() && lines.all { it == 3 }) Instrument.TRICHORDO else Instrument.TETRACHORDO

    // ---- Staffs ----

    /** The tab staffs on [page] in reading order (top to bottom), counting notation and guitar staffs in [counts]. */
    private fun staffs(page: PdfPageContent, pageIndex: Int, counts: Counts): List<Staff> {
        val lines = joinPieces(page.lines.filter { it.thickness <= MAX_LINE_THICKNESS })
            .filter { it.x1 - it.x0 >= MIN_STAFF_LENGTH }
            .sortedBy { it.y }
            .take(MAX_LINES_PER_PAGE)
        // Group lines with the same extent (a staff's lines start and end together), then split
        // each group, top to bottom, into runs of equally spaced lines.
        val groups = ArrayList<MutableList<PdfLine>>()
        for (l in lines) {
            val tol = max(4.0, 0.03 * (l.x1 - l.x0))
            val g = groups.firstOrNull { abs(it[0].x0 - l.x0) <= tol && abs(it[0].x1 - l.x1) <= tol }
            if (g != null) g += l else groups += mutableListOf(l)
        }
        val out = ArrayList<Staff>()
        for (g in groups) {
            var run = mutableListOf(g[0])
            var spacing = 0.0
            fun close() {
                val n = run.size
                if (n < 3 || !run.all { it.thickness <= 0.35 * spacing }) return
                val x0 = run.maxOf { it.x0 }
                val x1 = run.minOf { it.x1 }
                if (x1 - x0 < max(MIN_STAFF_LENGTH, 8 * spacing)) return
                when (n) {
                    3, 4 -> out += Staff(pageIndex, DoubleArray(n) { run[it].y }, x0, x1)
                    5 -> counts.notation++
                    6 -> counts.guitar++
                }
            }
            for (k in 1 until g.size) {
                val l = g[k]
                val gap = l.y - run.last().y
                if (run.size >= 2 && abs(gap - spacing) <= 0.15 * spacing) { run += l; continue }
                // The run ends here; a new one may start from its last line.
                if (run.size >= 2) close()
                val prev = run.last()
                if (gap in MIN_SPACING..MAX_SPACING) { run = mutableListOf(prev, l); spacing = gap } else { run = mutableListOf(l); spacing = 0.0 }
            }
            if (run.size >= 2) close()
        }
        return out.sortedWith(compareBy({ it.top }, { it.x0 })).take(MAX_STAFFS_PER_PAGE)
    }

    /** Joins lines drawn in pieces (or drawn twice) at the same height into single lines. */
    private fun joinPieces(lines: List<PdfLine>): List<PdfLine> {
        val sorted = lines.sortedWith(compareBy({ it.y }, { it.x0 }))
        val out = ArrayList<PdfLine>()
        var i = 0
        while (i < sorted.size) {
            // Lines at the same height (within a quarter point).
            var j = i + 1
            while (j < sorted.size && sorted[j].y - sorted[j - 1].y <= 0.25) j++
            val row = sorted.subList(i, j).sortedBy { it.x0 }
            var x0 = row[0].x0
            var x1 = row[0].x1
            var ySum = row[0].y
            var count = 1
            var thick = row[0].thickness
            for (k in 1 until row.size) {
                val l = row[k]
                if (l.x0 - x1 <= JOIN_GAP) {
                    x1 = max(x1, l.x1); ySum += l.y; count++; thick = max(thick, l.thickness)
                } else {
                    out += PdfLine(x0, x1, ySum / count, thick)
                    x0 = l.x0; x1 = l.x1; ySum = l.y; count = 1; thick = l.thickness
                }
            }
            out += PdfLine(x0, x1, ySum / count, thick)
            i = j
        }
        return out
    }

    // ---- Frets ----

    private fun middle(g: PdfGlyph) = g.baseline - DIGIT_MIDDLE * g.size

    private fun isDigit(g: PdfGlyph) = g.text.length == 1 && g.text[0] in '0'..'9'

    /** The glyphs that could belong to staff [s]: baselines from a little above it to a little below. */
    private fun near(index: GlyphIndex, s: Staff, above: Double, below: Double) =
        index.band(s.top - above * s.spacing, s.bottom + below * s.spacing).filter { (it.x0 + it.x1) / 2 in s.x0 - s.spacing..s.x1 + s.spacing }

    /** Digits drawn over [s] (on or near its lines), of a plausible size for its spacing, without duplicates. */
    private fun digitsOn(index: GlyphIndex, s: Staff): List<PdfGlyph> {
        val sp = s.spacing
        val found = near(index, s, 1.0, 2.0).filter { g ->
            isDigit(g) && g.size in 0.4 * sp..2.5 * sp && middle(g) in s.top - ON_LINE * sp..s.bottom + ON_LINE * sp
        }.sortedWith(compareBy({ it.x0 }, { it.baseline }))
        // The same digit drawn twice in one place (fake bold, shadows) counts once.
        val out = ArrayList<PdfGlyph>()
        for (g in found) {
            val dup = out.asReversed().asSequence().take(32).takeWhile { g.x0 - it.x0 < 0.2 * g.size }.any {
                it.text == g.text && abs(it.baseline - g.baseline) < 0.2 * g.size
            }
            if (!dup) out += g
        }
        return out
    }

    /**
     * Frets on staff [s] from its [digits]: each digit goes to its nearest line, and digits on one
     * line that touch (left to right) make one number. Numbers above [maxFret] are left out.
     */
    private fun frets(index: GlyphIndex, s: Staff, digits: List<PdfGlyph>, maxFret: Int, counts: Counts): List<Fret> {
        val byLine = Array(s.lines) { ArrayList<PdfGlyph>() }
        for (g in digits) {
            val line = s.lineAt(middle(g))
            if (s.isOn(line, middle(g))) byLine[line] += g
        }
        val brackets = near(index, s, 1.0, 2.0).filter { it.text == "(" || it.text == ")" }.sortedBy { it.x0 }
        val bracketX = DoubleArray(brackets.size) { brackets[it].x0 }
        val out = ArrayList<Fret>()
        for ((line, glyphs) in byLine.withIndex()) {
            glyphs.sortBy { it.x0 }
            var k = 0
            while (k < glyphs.size) {
                val first = glyphs[k]
                val text = StringBuilder(first.text)
                var x1 = first.x1
                var j = k + 1
                while (j < glyphs.size) {
                    val g = glyphs[j]
                    val gap = g.x0 - x1
                    val touches = gap >= -0.35 * first.size && gap <= 0.3 * first.size &&
                        abs(g.baseline - first.baseline) < 0.25 * first.size && g.size in first.size * 0.8..first.size * 1.25
                    if (!touches) break
                    text.append(g.text)
                    x1 = g.x1
                    j++
                }
                k = j
                val value = text.toString().toIntOrNull()
                if (value == null || text.length > 2 || value > maxFret) { counts.tooBig++; continue }
                val size = first.size
                // A bracket just before or just after: a tied or ghost note.
                var bracketed = false
                var b = firstAtLeast(bracketX, first.x0 - 2 * size)
                while (b < brackets.size && brackets[b].x0 <= x1 + 0.6 * size && !bracketed) {
                    val br = brackets[b++]
                    bracketed = abs(br.baseline - first.baseline) < 0.5 * size &&
                        ((br.text == "(" && br.x1 in first.x0 - 0.6 * size..first.x0 + 0.2 * size) ||
                            (br.text == ")" && br.x0 in x1 - 0.2 * size..x1 + 0.6 * size))
                }
                if (bracketed) counts.bracketed++
                out += Fret(s, line, value, first.x0, x1, first.baseline, size, bracketed)
            }
        }
        return out.sortedWith(compareBy({ it.x }, { it.line }))
    }

    /** The first index in sorted [xs] whose value is at least [v]. */
    private fun firstAtLeast(xs: DoubleArray, v: Double): Int {
        var lo = 0
        var hi = xs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Hammer-ons, pull-offs and slides written as text: `h`/`p`/`/`/`\`/`sl` between two frets on a
     * line (as in "7h9"), or `H`/`P`/`sl` just above or below the staff between two notes (as Guitar
     * Pro writes them). The mark goes to the second note, which sounds without a new pick.
     */
    private fun articulations(index: GlyphIndex, s: Staff, frets: List<Fret>, usual: Double) {
        if (frets.size < 2) return
        val sp = s.spacing
        val xs = DoubleArray(frets.size) { frets[it].x }
        val lines = Array(s.lines) { line -> frets.filter { it.line == line }.sortedBy { it.x0 } }
        val lineX0 = Array(s.lines) { line -> DoubleArray(lines[line].size) { lines[line][it].x0 } }
        val marks = words(index, s).mapNotNull { w ->
            val kind = when (w.text.lowercase().trimEnd('.')) {
                "h" -> Articulation.HAMMER
                "p" -> Articulation.PULL
                "s", "sl", "/", "\\" -> Articulation.SLIDE
                else -> null
            }
            if (kind != null && w.size in usual * 0.4..usual * 2.5) w to kind else null
        }.take(MAX_MARKS_PER_STAFF)
        for ((w, kind) in marks) {
            val mid = (w.x0 + w.x1) / 2
            val m = w.baseline - DIGIT_MIDDLE * w.size
            val line = s.lineAt(m)
            val target = if (s.isOn(line, m)) {
                // On a line: between a fret and the next one on the same line.
                val onLine = lines[line]
                val n = firstAtLeast(lineX0[line], w.x1 - 0.3 * w.size)
                val next = onLine.getOrNull(n)
                val prev = onLine.getOrNull(n - 1)?.takeIf { it.x1 <= w.x0 + 0.3 * it.size }
                if (next != null && prev != null && next.x0 - w.x1 <= 1.5 * next.size) next else null
            } else if (m in s.top - 3 * sp..s.top - 0.4 * sp || m in s.bottom + 0.4 * sp..s.bottom + 3 * sp) {
                // Above or below the staff: the note after the mark, if there's one before it.
                val n = firstAtLeast(xs, Math.nextUp(mid))
                val next = frets.getOrNull(n)
                val prev = frets.getOrNull(n - 1)
                if (next != null && prev != null) {
                    // In a chord, the note on the course the previous note was on, else the top one.
                    val chord = frets.subList(n, frets.size).takeWhile { abs(it.x - next.x) < 0.5 * next.size }
                    chord.firstOrNull { it.line == prev.line } ?: chord.minByOrNull { it.line }
                } else null
            } else null
            target?.articulation = kind
        }
    }

    /** A run of touching glyphs on one baseline. */
    private class Word(val text: String, val x0: Double, val x1: Double, val baseline: Double, val size: Double)

    /** Words of non-digit glyphs on or near staff [s] (within 4 line spacings). */
    private fun words(index: GlyphIndex, s: Staff): List<Word> {
        val near = near(index, s, 4.0, 4.0).filter { !isDigit(it) && it.text.isNotBlank() }
        val rows = ArrayList<MutableList<PdfGlyph>>()
        for (g in near) { // sorted by baseline already
            val row = rows.lastOrNull()
            if (row != null && abs(row[0].baseline - g.baseline) < 0.3 * g.size) row += g else rows += mutableListOf(g)
        }
        val out = ArrayList<Word>()
        for (row in rows) {
            row.sortBy { it.x0 }
            var cur: PdfGlyph = row[0]
            val text = StringBuilder(cur.text)
            var x0 = cur.x0
            for (k in 1 until row.size) {
                val g = row[k]
                if (g.x0 - cur.x1 in -0.35 * g.size..0.3 * g.size) text.append(g.text)
                else {
                    out += Word(text.toString(), x0, cur.x1, row[0].baseline, row[0].size)
                    text.setLength(0); text.append(g.text); x0 = g.x0
                }
                cur = g
            }
            out += Word(text.toString(), x0, cur.x1, row[0].baseline, row[0].size)
        }
        return out
    }

    // ---- Timeline ----

    /**
     * The notes of all [systems] in reading order, with columns on one timeline: within a system
     * the x positions scaled so that the typical note spacing is [COLUMNS_PER_NOTE] columns, each
     * system starting [SYSTEM_GAP] columns after the previous one ends. Frets at one x (chords)
     * share a column, top course first.
     */
    private fun timeline(systems: List<List<Fret>>, counts: Counts): List<TabNote> {
        // Group each system's frets into onsets (one x).
        val onsets = systems.map { frets ->
            val groups = ArrayList<MutableList<Fret>>()
            for (f in frets) {
                val g = groups.lastOrNull()
                if (g != null && abs(f.x - g[0].x) < 0.5 * f.size) g += f else groups += mutableListOf(f)
            }
            groups
        }
        val gaps = onsets.flatMap { g -> g.zipWithNext { a, b -> b[0].x - a[0].x } }.filter { it > 0 }.sorted()
        val typical = if (gaps.isNotEmpty()) gaps[gaps.size / 2] else 4 * (systems.firstOrNull()?.firstOrNull()?.staff?.spacing ?: 5.0)
        val unit = typical / COLUMNS_PER_NOTE
        val notes = ArrayList<TabNote>()
        var next = 0
        for (system in onsets) {
            if (system.isEmpty()) continue
            val start = next
            val x0 = system[0][0].x
            var last = start - 1
            for (g in system) {
                val col = max(last + 1, start + ((g[0].x - x0) / unit).roundToInt())
                if (g.size > 1) counts.stacks++
                for (f in g.sortedBy { it.line }) {
                    notes += TabNote(f.staff.lines - 1 - f.line, f.fret, col, f.articulation)
                }
                last = col
            }
            next = last + 1 + SYSTEM_GAP
        }
        return notes
    }

    // ---- Text tabs printed to PDF ----

    /**
     * The text of [page], one row per baseline, with characters placed by their x position in
     * steps of the usual glyph width, so a monospaced text tab keeps its columns.
     */
    internal fun pageText(page: PdfPageContent): String {
        val glyphs = page.glyphs.filter { it.upright && it.text.isNotEmpty() && it.size > 0 }
        if (glyphs.isEmpty()) return ""
        val widths = glyphs.map { it.x1 - it.x0 }.filter { it > 0.01 }.sorted()
        val cw = if (widths.isEmpty()) glyphs[0].size * 0.6 else widths[widths.size / 2]
        val left = glyphs.minOf { it.x0 }
        val rows = ArrayList<MutableList<PdfGlyph>>()
        for (g in glyphs.sortedWith(compareBy({ it.baseline }, { it.x0 }))) {
            val row = rows.lastOrNull()
            if (row != null && abs(g.baseline - row[0].baseline) < 0.4 * min(g.size, row[0].size)) row += g else rows += mutableListOf(g)
        }
        val sb = StringBuilder()
        for (row in rows) {
            val line = StringBuilder()
            for (g in row.sortedBy { it.x0 }) {
                val col = ((g.x0 - left) / cw).roundToInt().coerceAtMost(line.length + 500)
                while (line.length < col) line.append(' ')
                line.append(g.text)
            }
            sb.append(line.toString().trimEnd()).append('\n')
        }
        return sb.toString()
    }
}
