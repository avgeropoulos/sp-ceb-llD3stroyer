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
 *    with their positions and sizes, horizontal and vertical lines, and curves.
 * 2. Tab staffs are runs of 3 or 4 equally spaced long thin lines (notation staffs have 5 and are
 *    skipped; guitar tabs have 6 and are skipped with a warning). Tab staffs joined by a barline or
 *    bracket belong to one system; when a system has several (two bouzouki parts, or a bass tab),
 *    only the top one is read, with a warning.
 * 3. Frets are digits centred on a line, of the size most frets in the file have (which leaves out
 *    time signatures, bar numbers, tuplets, lyrics and page numbers); digits that touch make one
 *    fret, and a run too big for one fret is split at its widest gaps. The line gives the course,
 *    top line = highest course. Hammer-ons, pull-offs and slides written as text (h, p, sl, / or \
 *    between two frets, H, P or sl above or below the staff) mark the note after them. A tied note
 *    (the same fret again on the line, joined to it by a tie or written in brackets) is left out,
 *    as it isn't picked again.
 * 4. Notes are put in reading order (page, then top to bottom), and their x positions become
 *    columns of one timeline across the systems, about 2 columns per typical note spacing (like a
 *    text tab), so the aligner's tempo prior still works. Frets at one x are a chord, kept as [TabParser]
 *    keeps them: its top (melody) note, with the others in [TabNote.stack]. Each note has its system
 *    (the tab staff it's printed on, counted from 0), as a text tab's notes have theirs.
 * 5. The score's form ([PdfForm]): repeat barlines, 1st/2nd-time endings, D.C., D.S., Coda and Fine
 *    become the tab's sections in playing order ([ParsedTab.sections]), as a text tab's |: :| and x2.
 *
 * A PDF without any staffs (a text tab printed to PDF) has its page text rebuilt and handed to
 * [TabParser] ([NO_TEXT_TAB] if that finds nothing). A PDF with no readable digits, such as a scan,
 * gives no notes and [NO_NUMBERS].
 */
object PdfTab {
    /** The warning for a PDF whose tab is a picture (or has no tab at all). */
    const val NO_NUMBERS = "This PDF has no readable tab numbers (it may be a scanned picture)"

    /** The warning for a PDF of plain text (a typed tab) in which [TabParser] finds no tab. */
    const val NO_TEXT_TAB = "This PDF's text has no tab lines that could be read"

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
    private const val PIECE_GAP = 15.0
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
    /**
     * Digits on a line make one number when the gap after the first one's advance is at most this,
     * as a fraction of the size: the digits of a number touch (-0.05 to 0.01 in exports), while
     * separate frets in dense passages are 0.1 or more apart.
     */
    private const val DIGIT_GAP = 0.08
    /** A number too big for a fret is split at its widest gap if that is at least this wide (fraction of the size). */
    private const val SPLIT_GAP = 0.03
    /** How far a barline's ends may be from a staff's top and bottom lines, in points. */
    private const val BARLINE_END = 1.5
    /** Columns per typical note spacing, and the gap between systems. */
    private const val COLUMNS_PER_NOTE = 2.0
    private const val SYSTEM_GAP = 2
    /** Caps that keep a hostile file from making the geometry slow (real pages are far below them). */
    private const val MAX_LINES_PER_PAGE = 2_000
    private const val MAX_STAFFS_PER_PAGE = 200
    private const val MAX_MARKS_PER_STAFF = 1_000
    /** Text rows further apart than this many usual row steps get an empty line between them (between systems). */
    private const val PARAGRAPH_GAP = 1.5
    /** Caps on the rebuilt text of a page: columns per row, and characters in all. */
    private const val MAX_TEXT_COLUMNS = 1_000
    private const val MAX_TEXT = 1_000_000

    /** A staff (a tab staff when it has 3 or 4 lines): its page, its lines' heights from the top, and its horizontal extent. */
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
        /** Joined by a tie to the fret before it on the line (in the same system). */
        var tied = false
        /** A tie runs into it from the start of the system (from the previous system's last note). */
        var tiedFromLeft = false
    }

    private class Counts {
        var stacks = 0
        var bracketed = 0
        var ties = 0
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
        val all = pages.flatMapIndexed { i, p -> staffs(p, i) }
        val notation = all.count { it.lines == 5 }
        val guitar = all.count { it.lines == 6 }
        if (guitar > 0) warnings += "Skipped $guitar guitar tab staff${if (guitar > 1) "s" else ""} (6 lines); bouzouki tabs have 3 or 4"
        val staffs = all.filter { it.lines <= 4 }

        if (staffs.isEmpty()) {
            // No staffs at all: maybe a text tab printed to PDF. Rebuild the text and let the text tab reader try.
            if (notation == 0 && guitar == 0) {
                val text = text(pages)
                if (text.any { it.isDigit() }) {
                    val t = TabParser.parse(text, instrument)
                    val w = warnings + t.warnings
                    return ParsedTab(t.instrument, t.notes, (if (t.notes.isEmpty()) w + NO_TEXT_TAB else w).toList(), t.sections)
                }
            }
            return ParsedTab(instrument, emptyList(), (warnings + NO_NUMBERS).toList())
        }

        // Digits on the staffs, then the usual fret size among them.
        val index = pages.map { GlyphIndex(it) }
        val digits = staffs.associateWith { s -> digitsOn(index[s.page], s) }
        val sizes = digits.values.flatMap { g -> g.map { it.size } }.sorted()
        if (sizes.isEmpty()) return ParsedTab(instrumentFor(staffs.map { it.lines }), emptyList(), (warnings + NO_NUMBERS).toList())
        val usual = sizes[sizes.size / 2]

        // The top tab staff of each system is the tab; the others with frets are other parts.
        val tabs = ArrayList<Staff>()
        var parts = 1
        for (system in systems(pages, all, staffs)) {
            tabs += system[0]
            parts = max(parts, 1 + system.drop(1).count { digits.getValue(it).isNotEmpty() })
        }
        if (parts > 1) warnings += "This PDF has $parts tab parts; only the top one was read"

        // The tab's instrument: the line count of most staffs with frets.
        val lineCounts = tabs.filter { digits.getValue(it).isNotEmpty() }.map { it.lines }
        val tabLines = if (lineCounts.count { it == 4 } >= lineCounts.count { it == 3 }) 4 else 3
        val tabInstrument = instrumentFor(listOf(tabLines))
        val skipped = tabs.count { it.lines != tabLines && digits.getValue(it).isNotEmpty() }
        if (skipped > 0) warnings += "This PDF mixes 3- and 4-line tab staffs; only the $tabLines-line ones were read"
        if (tabInstrument != instrument) {
            warnings += "This tab is for the ${tabInstrument.name.lowercase()} ($tabLines courses), not the ${instrument.name.lowercase()}"
        }

        val read = tabs.filter { it.lines == tabLines }
        val systems = read.map { s ->
            val g = digits.getValue(s).filter { it.size in usual * MIN_SIZE_RATIO..usual * MAX_SIZE_RATIO }
            val frets = frets(index[s.page], s, g, tabInstrument.frets, counts)
            articulations(index[s.page], s, frets, usual)
            ties(pages[s.page], s, frets)
            frets
        }
        val placed = timeline(systems, tabInstrument, counts)
        val notes0 = placed.flatten().map { it.note }
        // The form: each system with the notation staff over it (the nearest 5-line staff above, with no tab staff between).
        var first = 0
        val form = read.mapIndexed { k, s ->
            val above = all.filter { o ->
                o.page == s.page && o.lines == 5 && o.bottom < s.top && s.top - o.bottom < 20 * s.spacing && o.x0 < s.x1 && o.x1 > s.x0 &&
                    staffs.none { t -> t.page == s.page && t.top > o.bottom && t.bottom < s.top }
            }.maxByOrNull { it.bottom }
            PdfForm.System(s.page, s.ys, above?.ys, s.x0, s.x1, first, DoubleArray(placed[k].size) { placed[k][it].x }).also { first += placed[k].size }
        }
        val score = try { PdfForm.read(pages, form, notes0.size, warnings) } catch (e: RuntimeException) {
            warnings += "Couldn't make sense of the score's repeat signs: the tab is read in written order"
            PdfForm.Form(listOf(TabSection(0, notes0.size)), IntArray(notes0.size))
        }
        val notes = notes0.mapIndexed { i, nt -> nt.copy(bar = score.bar[i]) }
        val sections = score.sections
        if (counts.stacks > 0) warnings += "Kept ${counts.stacks} chord${if (counts.stacks > 1) "s" else ""} (notes played together)"
        if (counts.ties > 0) warnings += "Left out ${counts.ties} tied note${if (counts.ties > 1) "s" else ""} (held on, not picked again)"
        if (counts.bracketed > 0) warnings += "Kept ${counts.bracketed} note${if (counts.bracketed > 1) "s" else ""} in brackets (tied or ghost notes)"
        if (counts.tooBig > 0) warnings += "Left out ${counts.tooBig} number${if (counts.tooBig > 1) "s" else ""} on the tab lines too big to be frets"
        if (notes.isEmpty()) warnings += NO_NUMBERS
        return ParsedTab(tabInstrument, notes, warnings.toList(), if (notes.isEmpty()) emptyList() else sections)
    }

    private fun instrumentFor(lines: List<Int>) = if (lines.isNotEmpty() && lines.all { it == 3 }) Instrument.TRICHORDO else Instrument.TETRACHORDO

    // ---- Staffs ----

    /** The staffs (runs of 3 to 6 equally spaced lines) on [page] in reading order, top to bottom. */
    private fun staffs(page: PdfPageContent, pageIndex: Int): List<Staff> {
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
                if (n <= 6) out += Staff(pageIndex, DoubleArray(n) { run[it].y }, x0, x1)
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

    /**
     * The tab staffs ([tabs], from [staffs] of all kinds) grouped into systems, in reading order, each
     * top to bottom: tab staffs on a page are in one system when a barline or bracket runs across
     * them all, from a staff's top line to a staff's bottom line (so a page frame doesn't count).
     */
    private fun systems(pages: List<PdfPageContent>, staffs: List<Staff>, tabs: List<Staff>): List<List<Staff>> {
        val out = ArrayList<List<Staff>>()
        for ((pi, page) in pages.withIndex()) {
            val mine = tabs.filter { it.page == pi }
            if (mine.isEmpty()) continue
            // Union-find over the page's tab staffs: a barline joins all the staffs it runs across.
            val parent = IntArray(mine.size) { it }
            fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
            if (mine.size > 1) {
                val onPage = staffs.filter { it.page == pi }
                val tops = onPage.map { it.top }.sorted().toDoubleArray()
                val bottoms = onPage.map { it.bottom }.sorted().toDoubleArray()
                fun near(ys: DoubleArray, y: Double): Boolean {
                    val k = firstAtLeast(ys, y - BARLINE_END)
                    return k < ys.size && ys[k] <= y + BARLINE_END
                }
                for (b in joinVertical(page.vlines)) {
                    if (!near(tops, b.y0) || !near(bottoms, b.y1)) continue
                    var first = -1
                    for ((i, st) in mine.withIndex()) {
                        val margin = 3 * st.spacing
                        if (b.x !in st.x0 - margin..st.x1 + margin || b.y0 > st.top + BARLINE_END || b.y1 < st.bottom - BARLINE_END) continue
                        if (first < 0) first = root(i) else parent[root(i)] = first
                    }
                }
            }
            val bySystem = LinkedHashMap<Int, MutableList<Staff>>()
            for ((i, st) in mine.withIndex()) bySystem.getOrPut(root(i)) { ArrayList() } += st
            out += bySystem.values
        }
        return out
    }

    /** Joins vertical lines drawn in pieces (a system's barline is often drawn staff by staff) into single lines. */
    private fun joinVertical(lines: List<PdfVLine>): List<PdfVLine> = PdfForm.joinVertical(lines)

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
                if (l.x0 - x1 <= PIECE_GAP) {
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
     * line that touch (left to right) make one number. A number above [maxFret] is split at its
     * widest gaps into frets; one that can't be split is left out.
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
        val numbers = ArrayList<List<PdfGlyph>?>()
        for ((line, glyphs) in byLine.withIndex()) {
            glyphs.sortBy { it.x0 }
            var k = 0
            while (k < glyphs.size) {
                val first = glyphs[k]
                var j = k + 1
                while (j < glyphs.size) {
                    val g = glyphs[j]
                    val gap = g.x0 - glyphs[j - 1].x1
                    val touches = gap >= -0.35 * first.size && gap <= DIGIT_GAP * first.size &&
                        abs(g.baseline - first.baseline) < 0.25 * first.size && g.size in first.size * 0.8..first.size * 1.25
                    if (!touches) break
                    j++
                }
                numbers.clear()
                split(glyphs.subList(k, j), maxFret, numbers)
                k = j
                for (n in numbers) {
                    if (n == null) { counts.tooBig++; continue }
                    val value = n.joinToString("") { it.text }.toInt()
                    val x0 = n.first().x0
                    val x1 = n.last().x1
                    val size = n.first().size
                    val baseline = n.first().baseline
                    // A bracket just before or just after: a tied or ghost note.
                    var bracketed = false
                    var b = firstAtLeast(bracketX, x0 - 2 * size)
                    while (b < brackets.size && brackets[b].x0 <= x1 + 0.6 * size && !bracketed) {
                        val br = brackets[b++]
                        bracketed = abs(br.baseline - baseline) < 0.5 * size &&
                            ((br.text == "(" && br.x1 in x0 - 0.6 * size..x0 + 0.2 * size) ||
                                (br.text == ")" && br.x0 in x1 - 0.2 * size..x1 + 0.6 * size))
                    }
                    out += Fret(s, line, value, x0, x1, baseline, size, bracketed)
                }
            }
        }
        return out.sortedWith(compareBy({ it.x }, { it.line }))
    }

    /**
     * Touching digits [run] as fret numbers, added to [out]: as one number if it is a fret (one digit,
     * or two without a leading zero, at most [maxFret]), else split at its widest gap (frets set
     * closely), part by part. A part that is too big but has no gap to split at goes in as null.
     */
    private fun split(run: List<PdfGlyph>, maxFret: Int, out: MutableList<List<PdfGlyph>?>) {
        val text = run.joinToString("") { it.text }
        val value = text.toIntOrNull()
        if (value != null && value <= maxFret && (text.length == 1 || (text.length == 2 && text[0] != '0'))) { out += run; return }
        var widest = -1
        for (i in 1 until run.size) {
            val gap = run[i].x0 - run[i - 1].x1
            if (gap >= SPLIT_GAP * run[0].size && (widest < 0 || gap > run[widest].x0 - run[widest - 1].x1)) widest = i
        }
        if (widest < 0) { out += null; return }
        split(run.subList(0, widest), maxFret, out)
        split(run.subList(widest, run.size), maxFret, out)
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
            val m = w.baseline - DIGIT_MIDDLE * w.size
            val line = s.lineAt(m)
            val onLine = s.isOn(line, m)
            val t = w.text.trimEnd('.')
            // Between two frets on a line any case; above or below the staff only H, P and sl, so
            // dynamics there (p, mp, f) aren't taken for pull-offs.
            val kind = if (onLine) {
                when (t.lowercase()) {
                    "h" -> Articulation.HAMMER
                    "p" -> Articulation.PULL
                    "s", "sl", "/", "\\" -> Articulation.SLIDE
                    else -> null
                }
            } else {
                when (t) {
                    "H" -> Articulation.HAMMER
                    "P" -> Articulation.PULL
                    "sl", "Sl", "SL" -> Articulation.SLIDE
                    else -> null
                }
            }
            if (kind != null && w.size in usual * 0.4..usual * 2.5) Mark(w, kind, line, onLine, m) else null
        }.take(MAX_MARKS_PER_STAFF)
        for ((w, kind, line, onLine, m) in marks) {
            val mid = (w.x0 + w.x1) / 2
            val target = if (onLine) {
                // On a line: between a fret and the next one on the same line.
                val sameLine = lines[line]
                val n = firstAtLeast(lineX0[line], w.x1 - 0.3 * w.size)
                val next = sameLine.getOrNull(n)
                val prev = sameLine.getOrNull(n - 1)?.takeIf { it.x1 <= w.x0 + 0.3 * it.size }
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

    /** A mark: its word, what it marks, the line nearest to its middle [m], and whether it is on that line. */
    private data class Mark(val word: Word, val kind: Articulation, val line: Int, val onLine: Boolean, val m: Double)

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

    // ---- Ties ----

    /**
     * Marks the [frets] of staff [s] that a tie joins to the fret before them: a curve near their
     * line from inside the previous fret on the line (the same number) to inside this one, or from
     * the start of the system (a tie from the previous system, checked in [timeline]).
     */
    private fun ties(page: PdfPageContent, s: Staff, frets: List<Fret>) {
        if (frets.isEmpty() || page.arcs.isEmpty()) return
        val sp = s.spacing
        val arcs = page.arcs.filter { a ->
            val y = (a.y0 + a.y1) / 2
            a.y1 - a.y0 <= sp && y in s.top - 1.5 * sp..s.bottom + 1.5 * sp && a.x1 > s.x0 && a.x0 < s.x1
        }.take(MAX_MARKS_PER_STAFF).sortedBy { it.x1 }
        if (arcs.isEmpty()) return
        val ends = DoubleArray(arcs.size) { arcs[it].x1 }
        val firstX = frets.minOf { it.x0 }
        for (line in 0 until s.lines) {
            val onLine = frets.filter { it.line == line }.sortedBy { it.x0 }
            for ((i, b) in onLine.withIndex()) {
                val a = onLine.getOrNull(i - 1)
                if (a != null && a.fret != b.fret) continue
                val pad = 0.3 * b.size
                // The curves that end inside this fret, near its line.
                var k = firstAtLeast(ends, b.x0 - pad)
                while (k < arcs.size && ends[k] <= b.x1 + pad) {
                    val arc = arcs[k++]
                    if (abs((arc.y0 + arc.y1) / 2 - s.ys[line]) > sp) continue
                    if (a != null && arc.x0 in a.x0 - pad..a.x1 + pad) b.tied = true
                    if (a == null && arc.x0 < min(firstX, b.x0 - pad)) b.tiedFromLeft = true
                }
            }
        }
    }

    // ---- Timeline ----

    /** A note as read, with the x of its column on the page. */
    private class Placed(val note: TabNote, val x: Double)

    /**
     * The notes of all [systems] in reading order, system by system, with columns on one timeline: within a
     * system the x positions scaled so that the typical note spacing is [COLUMNS_PER_NOTE] columns, each
     * system starting [SYSTEM_GAP] columns after the previous one ends. Frets at one x are a chord: its
     * highest note on [inst], with the others in its stack (highest first), as [TabParser] reads chords.
     */
    private fun timeline(all: List<List<Fret>>, inst: Instrument, counts: Counts): List<List<Placed>> {
        // Leave out tied notes: the same fret again on a course, joined to the one before by a tie,
        // or in brackets. The fret before may end the previous system.
        val last = HashMap<Int, Int>()
        val systems = all.map { frets ->
            frets.filter { f ->
                val course = f.staff.lines - 1 - f.line
                val tie = f.tied || ((f.bracketed || f.tiedFromLeft) && last[course] == f.fret)
                last[course] = f.fret
                if (tie) counts.ties++ else if (f.bracketed) counts.bracketed++
                !tie
            }
        }
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
                // One fret per course; the highest note is the melody, the others its stack.
                val chord = g.distinctBy { it.line }.sortedWith(
                    compareByDescending<Fret> { inst.midiAt(it.staff.lines - 1 - it.line, it.fret) }.thenBy { it.line },
                )
                if (chord.size > 1) counts.stacks++
                val top = chord[0]
                notes += Placed(
                    TabNote(
                        top.staff.lines - 1 - top.line, top.fret, col, top.articulation,
                        chord.drop(1).map { (it.staff.lines - 1 - it.line) to it.fret }, k,
                    ),
                    g.sumOf { it.x } / g.size,
                )
                prev = col
            }
            next = prev + 1 + SYSTEM_GAP
        }
        return out
    }

    // ---- Text tabs printed to PDF ----

    /** The text of all [pages], as handed to [TabParser]: each page's [pageText], with an empty line between pages. */
    internal fun text(pages: List<PdfPageContent>): String {
        val sb = StringBuilder()
        for (p in pages) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(pageText(p))
            if (sb.length > MAX_TEXT) break
        }
        return sb.toString()
    }

    /**
     * The text of [page], one row per baseline, with characters placed by their x position in
     * steps of the usual glyph width, so a monospaced text tab keeps its columns. A gap between
     * rows much bigger than the usual one (between two systems) becomes an empty line.
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
        val steps = rows.zipWithNext { a, b -> b[0].baseline - a[0].baseline }.sorted()
        val step = if (steps.isEmpty()) 0.0 else steps[steps.size / 2]
        val sb = StringBuilder()
        for ((i, row) in rows.withIndex()) {
            if (i > 0 && row[0].baseline - rows[i - 1][0].baseline > PARAGRAPH_GAP * step) sb.append('\n')
            val line = StringBuilder()
            for (g in row.sortedBy { it.x0 }) {
                val col = ((g.x0 - left) / cw).roundToInt()
                if (col > MAX_TEXT_COLUMNS) break
                while (line.length < col) line.append(' ')
                line.append(g.text)
            }
            sb.append(line.toString().trimEnd()).append('\n')
            if (sb.length > MAX_TEXT) break
        }
        return sb.toString()
    }
}
