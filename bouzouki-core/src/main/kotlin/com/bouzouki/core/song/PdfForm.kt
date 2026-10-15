package com.bouzouki.core.song

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the form of a score printed in a PDF, for [PdfTab]: repeat barlines (|: and :|, drawn as a thick and a thin
 * line with two dots, read on the tab staff or the notation staff over it), counts written at a repeat ("x3", "3x",
 * "3 times"), 1st/2nd-time endings (a bracket over the staff with "1." or "2."), and jumps: D.C. and D.S. (back to
 * the segno sign), al Fine and al Coda, To Coda, Fine, the segno and coda signs. They become the tab's sections in
 * playing order, as [TabParser] gives a text tab's |: :| and x2: a stretch played again straight away is one section
 * with [TabSection.times], and an ending or a jump starts a new section where the playing goes on (an earlier one
 * again, for a D.S.). A double barline also ends a section: a part of the song, where a recording may leave the tab
 * and come back. Signs that can't be made sense of are left out, with a warning: the tab is read in written order there.
 */
internal object PdfForm {
    /** Where the form reader says what it found (for tests and tuning); null normally. */
    internal var log: ((String) -> Unit)? = null

    /**
     * A tab system as the form reader needs it: its [page], the heights of its tab staff's lines ([ys]) and of the
     * notation staff's over it ([above], null without one), its extent, and its notes: the first one's index in the
     * tab ([first]) and their x positions, in order ([xs]).
     */
    class System(
        val page: Int, val ys: DoubleArray, val above: DoubleArray?, val x0: Double, val x1: Double, val first: Int, val xs: DoubleArray,
    ) {
        val spacing = (ys.last() - ys.first()) / (ys.size - 1)
        val top get() = above?.first() ?: ys.first()
        val bottom get() = ys.last()

        /** The note boundary at [x]: the index (in the tab) of the first note right of it. */
        fun position(x: Double): Int {
            var lo = 0
            var hi = xs.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (xs[m] < x) lo = m + 1 else hi = m }
            return first + lo
        }
    }

    /** A 1st/2nd-time ending: note boundaries [from] to [to], for the times through in [numbers]. */
    private class Ending(val from: Int, val to: Int, val numbers: Set<Int>, val page: Int)

    /** A D.C. (back to the start) or D.S. (back to the segno) at boundary [at], and whether it says al Fine or al Coda. */
    private class Jump(val at: Int, val segno: Boolean, val alFine: Boolean, val alCoda: Boolean, val page: Int)

    /** Everything read, as note boundaries (0 = before the first note, the tab's size = after the last), with pages. */
    private class Marks {
        val starts = HashMap<Int, Int>() // |:
        val ends = HashMap<Int, Int>() // :| and how many times the stretch is played
        val endings = ArrayList<Ending>()
        val cuts = HashSet<Int>() // double barlines
        val segnos = ArrayList<Pair<Int, Int>>()
        val codaSigns = ArrayList<Triple<Int, Int, Boolean>>() // boundary, page, by a "To Coda" (where to leave) or not
        val toCodas = ArrayList<Pair<Int, Int>>()
        val fines = ArrayList<Pair<Int, Int>>()
        val jumps = ArrayList<Jump>()
    }

    /** Glyphs of text and signs, joined into phrases: words with the spaces between them. */
    private class Phrase(val text: String, val x0: Double, val x1: Double, val baseline: Double, val size: Double)

    /** A barline (one or more lines close together) across the tab staff, from [x0] to [x1], and what kind it is. */
    private class Bar(val x0: Double, val x1: Double, val lines: Int, val thick: Boolean) {
        val x get() = (x0 + x1) / 2
        val strong get() = thick || lines > 1
    }

    /** A page's glyphs (by baseline), vertical lines (joined, by x), thin horizontal lines and curves (by height). */
    private class Page(val content: PdfPageContent) {
        private val glyphs = content.glyphs.filter { it.upright && it.size > 0 && it.text.isNotBlank() }.sortedBy { it.baseline }
        private val baselines = DoubleArray(glyphs.size) { glyphs[it].baseline }
        val vlines = joinVertical(content.vlines).sortedBy { it.x }
        private val vxs = DoubleArray(vlines.size) { vlines[it].x }
        private val lines = content.lines.filter { it.thickness <= 1.6 && it.x1 - it.x0 >= 2.0 }.sortedBy { it.y }
        private val ys = DoubleArray(lines.size) { lines[it].y }
        private val arcs = content.arcs.sortedBy { it.y0 }
        private val arcYs = DoubleArray(arcs.size) { arcs[it].y0 }

        fun glyphs(top: Double, bottom: Double): List<PdfGlyph> = glyphs.subList(first(baselines, top), first(baselines, Math.nextUp(bottom)))
        fun lines(top: Double, bottom: Double): List<PdfLine> = lines.subList(first(ys, top), first(ys, Math.nextUp(bottom)))
        fun vlines(left: Double, right: Double): List<PdfVLine> = vlines.subList(first(vxs, left), first(vxs, Math.nextUp(right)))
        /** The curves whose tops are from [top] to [bottom]. */
        fun arcs(top: Double, bottom: Double): List<PdfArc> = arcs.subList(first(arcYs, top), first(arcYs, Math.nextUp(bottom)))

        private fun first(a: DoubleArray, v: Double): Int {
            var lo = 0
            var hi = a.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
            return lo
        }
    }

    /** The form read: the [sections] to play, in order, and the [bar] each note is in (counted from 0 through the tab). */
    class Form(val sections: List<TabSection>, val bar: IntArray)

    /** The form of [systems] (in reading order, holding all [n] notes). */
    fun read(contents: List<PdfPageContent>, systems: List<System>, n: Int, warnings: MutableSet<String>): Form {
        val bar = IntArray(n)
        if (n == 0) return Form(emptyList(), bar)
        val pages = HashMap<Int, Page>()
        fun page(i: Int) = pages.getOrPut(i) { Page(contents[i]) }
        val marks = Marks()
        var firstBar = 0
        for ((k, s) in systems.withIndex()) {
            if (s.page !in contents.indices) continue
            val bars = bars(page(s.page), s)
            // Each note's bar: the barlines left of it (the system's own start line aside), after the systems before.
            val xs = bars.map { it.x }.filter { it > s.x0 + s.spacing }
            for ((j, x) in s.xs.withIndex()) if (s.first + j < n) bar[s.first + j] = firstBar + xs.count { it < x }
            firstBar += xs.size + 1
            log?.invoke("system $k page ${s.page + 1} from note ${s.first}: bars " + bars.joinToString(" ") { "%.1f%s".format(it.x, if (it.strong) "!" else "") } +
                " | notes " + s.xs.joinToString(" ") { "%.1f".format(it) })
            val next = systems.getOrNull(k + 1)?.takeIf { it.page in contents.indices }
            read(page(s.page), s, bars, band(systems, k, contents[s.page].height), next?.let { page(it.page) }, next, marks, warnings)
        }
        log?.invoke("|: ${marks.starts.keys.sorted()} :| ${marks.ends.toSortedMap()} endings ${marks.endings.map { "${it.from}-${it.to} ${it.numbers}" }} " +
            "cuts ${marks.cuts.sorted()} segno ${marks.segnos} coda ${marks.codaSigns} to coda ${marks.toCodas} fine ${marks.fines} " +
            "jumps ${marks.jumps.map { "${it.at}${if (it.segno) " D.S." else " D.C."}${if (it.alFine) " al Fine" else ""}${if (it.alCoda) " al Coda" else ""}" }}")
        return Form(unfold(n, marks, warnings), bar)
    }

    /** The heights of the page that belong to system [k]: halfway to the systems above and below it, within limits. */
    private fun band(systems: List<System>, k: Int, height: Double): ClosedFloatingPointRange<Double> {
        val s = systems[k]
        val prev = systems.getOrNull(k - 1)?.takeIf { it.page == s.page }
        val next = systems.getOrNull(k + 1)?.takeIf { it.page == s.page }
        val top = max(prev?.let { (it.bottom + s.top) / 2 } ?: 0.0, s.top - 16 * s.spacing)
        val bottom = min(next?.let { (s.bottom + it.top) / 2 } ?: height, s.bottom + 8 * s.spacing)
        return top..max(top, bottom)
    }

    // ---- Reading one system ----

    private fun read(
        page: Page, s: System, bars: List<Bar>, band: ClosedFloatingPointRange<Double>, nextPage: Page?, next: System?, marks: Marks,
        warnings: MutableSet<String>,
    ) {
        val sp = s.spacing
        val pageNo = s.page + 1
        // Repeat dots: on the tab staff, or on the notation staff by a thick or double barline.
        val tabDots = dots(page, s.ys, s.x0, s.x1)
        val noteDots = s.above?.let { dots(page, it, s.x0, s.x1) } ?: emptyList()
        val ends = ArrayList<Bar>()
        for (b in bars) {
            fun near(dots: List<Double>, right: Boolean) = dots.any { if (right) it > b.x1 && it - b.x1 <= 2.2 * sp else it < b.x0 && b.x0 - it <= 2.2 * sp }
            val start = near(tabDots, true) || (b.strong && near(noteDots, true))
            val end = near(tabDots, false) || (b.strong && near(noteDots, false))
            val at = s.position(b.x)
            if (end) { marks.ends[at] = 2; ends += b }
            if (start) marks.starts[at] = pageNo
            if (!start && !end && b.strong) marks.cuts += at
        }
        // Text above the system and below the tab staff (not between the staffs, where lyrics are).
        val glyphs = page.glyphs(band.start, band.endInclusive).filter { g ->
            (g.x0 + g.x1) / 2 in s.x0 - 2 * sp..s.x1 + 2 * sp && !(g.baseline > s.top - 0.3 * sp && g.baseline < s.bottom + 1.2 * sp)
        }
        val phrases = phrases(glyphs)
        val toCoda = phrases.filter { TO_CODA.containsMatchIn(it.text) }
        for (g in glyphs) {
            val kind = sign(g) ?: continue
            val cx = (g.x0 + g.x1) / 2
            if (kind == SEGNO) { marks.segnos += startOfBar(s, bars, cx) to pageNo; continue }
            val leave = toCoda.any { cx in it.x0 - 2 * g.size..it.x1 + 2 * g.size && abs(it.baseline - g.baseline) < g.size }
            marks.codaSigns += Triple(if (leave) endOfBar(s, bars, g.x0, g.x1) else startOfBar(s, bars, cx), pageNo, leave)
        }
        for (p in phrases) {
            val t = p.text.trim()
            when {
                DC.containsMatchIn(t) || DS.containsMatchIn(t) -> marks.jumps += Jump(
                    endOfBar(s, bars, p.x0, p.x1), DS.containsMatchIn(t), AL_FINE.containsMatchIn(t), AL_CODA.containsMatchIn(t), pageNo,
                )
                TO_CODA.containsMatchIn(t) -> marks.toCodas += endOfBar(s, bars, p.x0, p.x1) to pageNo
                FINE.matches(t) -> marks.fines += endOfBar(s, bars, p.x0, p.x1) to pageNo
                CODA_WORD.matches(t) -> marks.codaSigns += Triple(startOfBar(s, bars, p.x0), pageNo, false)
                COUNT.matches(t) -> {
                    val times = COUNT.matchEntire(t)!!.groupValues.drop(1).first { it.isNotEmpty() }.toInt()
                    // A count goes with the :| it's written by.
                    val bar = ends.minByOrNull { dist(it.x, p.x0, p.x1) }?.takeIf { dist(it.x, p.x0, p.x1) <= 4 * sp }
                    if (bar != null && times in 2..16) marks.ends[s.position(bar.x)] = times
                    else warnings += "Left out \"$t\" on page $pageNo: no repeat sign (:|) by it to say what it repeats"
                }
            }
        }
        endings(page, s, band.start, nextPage, next, phrases, marks, warnings)
    }

    /** How far [x] is from the stretch [x0] to [x1]. */
    private fun dist(x: Double, x0: Double, x1: Double) = if (x < x0) x0 - x else if (x > x1) x - x1 else 0.0

    /** The boundary a sign at the end of a bar marks (D.C., D.S., Fine, To Coda): the barline nearest its right end. */
    private fun endOfBar(s: System, bars: List<Bar>, x0: Double, x1: Double): Int {
        val b = bars.filter { it.x in x0 - s.spacing..x1 + 6 * s.spacing }.minByOrNull { abs(it.x - x1) }
        return s.position(b?.x ?: x1)
    }

    /** The boundary a sign at the start of a bar marks (segno, coda): the barline nearest it, else where it is. */
    private fun startOfBar(s: System, bars: List<Bar>, x: Double): Int {
        val b = bars.minByOrNull { abs(it.x - x) }?.takeIf { abs(it.x - x) <= 3 * s.spacing }
        return s.position(b?.x ?: x)
    }

    /** The barlines across the tab staff, with the staff's ends, in order; lines close together make one. */
    private fun bars(page: Page, s: System): List<Bar> {
        val sp = s.spacing
        val tol = max(1.5, 0.3 * sp)
        val across = page.vlines.filter { v -> v.y0 <= s.ys.first() + tol && v.y1 >= s.ys.last() - tol && v.x in s.x0 - tol..s.x1 + tol }
        val thin = across.map { it.thickness }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 4] }
        val out = ArrayList<Bar>()
        var i = 0
        while (i < across.size) {
            var j = i + 1
            while (j < across.size && across[j].x - across[j - 1].x <= 1.2 * sp) j++
            val group = across.subList(i, j)
            val thick = group.any { it.thickness >= max(1.0, 1.8 * thin) }
            out += Bar(group.first().x, group.last().x, group.distinctBy { Math.round(it.x * 2) }.size, thick)
            i = j
        }
        if (out.none { abs(it.x - s.x0) <= sp }) out.add(0, Bar(s.x0, s.x0, 1, false))
        if (out.none { abs(it.x - s.x1) <= sp }) out += Bar(s.x1, s.x1, 1, false)
        return out
    }

    /**
     * Joins vertical lines drawn in pieces (a system's barline is often drawn staff by staff) into single lines, in order
     * of x: pieces within half a point of each other across, that touch or overlap down the page.
     */
    internal fun joinVertical(lines: List<PdfVLine>): List<PdfVLine> {
        val byX = lines.sortedBy { it.x }
        val out = ArrayList<PdfVLine>()
        var i = 0
        while (i < byX.size) {
            var j = i + 1
            while (j < byX.size && byX[j].x - byX[i].x <= 0.5) j++
            val column = byX.subList(i, j).sortedBy { it.y0 }
            var cur = column[0]
            for (k in 1 until column.size) {
                val l = column[k]
                cur = if (l.y0 <= cur.y1 + 1.0) PdfVLine(cur.x, cur.y0, max(cur.y1, l.y1), max(cur.thickness, l.thickness)) else { out += cur; l }
            }
            out += cur
            i = j
        }
        return out
    }

    /**
     * The x of each pair of repeat dots on the staff with lines at [ys]: two small round marks one above the other, in
     * the spaces each side of the staff's middle (glyphs of a music font, or filled curves).
     */
    private fun dots(page: Page, ys: DoubleArray, x0: Double, x1: Double): List<Double> {
        val sp = (ys.last() - ys.first()) / (ys.size - 1)
        val middle = (ys.first() + ys.last()) / 2
        // Candidates: x, the y given (a glyph's baseline, or a curve's middle), and how far above it the dot may be.
        val cand = ArrayList<DoubleArray>()
        for (g in page.glyphs(ys.first() - sp, ys.last() + 4 * sp)) {
            if (g.text.length != 1) continue
            val c = g.text[0]
            if (c.isLetterOrDigit() || c in "()[]{}<>/\\|-_–—,;'\"`~^=+*#") continue
            val cx = (g.x0 + g.x1) / 2
            if (g.x1 - g.x0 > 1.2 * sp || cx !in x0..x1 || g.baseline > ys.last() + sp + 0.5 * g.size) continue
            cand += doubleArrayOf(cx, g.baseline, 0.45 * g.size)
        }
        for (a in page.arcs(ys.first() - sp, ys.last())) {
            val w = a.x1 - a.x0
            val h = a.y1 - a.y0
            if (w !in 0.15 * sp..sp || h !in 0.15 * sp..sp || w > 1.7 * h || h > 1.7 * w) continue
            val cx = (a.x0 + a.x1) / 2
            val cy = (a.y0 + a.y1) / 2
            if (cx in x0..x1 && cy in ys.first()..ys.last()) cand += doubleArrayOf(cx, cy, 0.0)
        }
        if (cand.size > MAX_DOTS) return emptyList()
        cand.sortBy { it[0] }
        val out = ArrayList<Double>()
        for (i in cand.indices) for (j in i + 1 until cand.size) {
            val a = cand[i]
            val b = cand[j]
            if (b[0] - a[0] > 0.3 * sp) break
            val gap = abs(a[1] - b[1])
            if (abs(gap - sp) > 0.25 * sp && abs(gap - 2 * sp) > 0.25 * sp) continue
            val mid = (a[1] + b[1]) / 2
            if (mid - middle < -0.6 * sp || mid - middle > 0.6 * sp + max(a[2], b[2])) continue
            out += (a[0] + b[0]) / 2
        }
        return out
    }

    private const val SEGNO = 1
    private const val CODA_SIGN = 2
    private const val MAX_DOTS = 2_000
    /** The most thin lines above a system read for endings: drawn ([MAX_LINES_DRAWN], copies too) and different ([MAX_LINES]). */
    private const val MAX_LINES_DRAWN = 20_000
    private const val MAX_LINES = 300
    /** The most 1st/2nd-time endings read (different ones; the same one read twice is one). */
    private const val MAX_ENDINGS = 200

    /** The sign [g] draws: a segno or a coda, as Unicode's musical symbols, SMuFL's, or a Sonata-style music font's. */
    private fun sign(g: PdfGlyph): Int? {
        when (g.text) {
            "\uD834\uDD0B", "\uE047", "\uE04A", "\uE04B" -> return SEGNO
            "\uD834\uDD0C", "\uE048", "\uE049" -> return CODA_SIGN
        }
        val f = g.font.lowercase()
        if (MUSIC_FONTS.none { it in f } || "chord" in f || "text" in f) return null
        return when (g.text) { "%" -> SEGNO; "Þ" -> CODA_SIGN; else -> null }
    }

    /** Music fonts laid out like Sonata (Opus, Maestro, Petrucci ...), where % is the segno and Þ the coda. */
    private val MUSIC_FONTS = listOf("opus", "maestro", "sonata", "petrucci", "jazz", "engraver", "broadway", "reprise", "inkpen")

    /** Rows of [glyphs] joined into phrases: glyphs close together, with a space where the gap is a word space. */
    private fun phrases(glyphs: List<PdfGlyph>): List<Phrase> {
        val rows = ArrayList<MutableList<PdfGlyph>>()
        for (g in glyphs.sortedBy { it.baseline }) {
            val row = rows.lastOrNull()
            if (row != null && abs(row[0].baseline - g.baseline) < 0.3 * min(row[0].size, g.size)) row += g else rows += mutableListOf(g)
        }
        val out = ArrayList<Phrase>()
        for (row in rows) {
            row.sortBy { it.x0 }
            var text = StringBuilder(row[0].text)
            var start = row[0]
            var last = row[0]
            for (k in 1 until row.size) {
                val g = row[k]
                val gap = g.x0 - last.x1
                val size = max(g.size, last.size)
                if (gap > 0.7 * size || g.size > 1.6 * last.size || last.size > 1.6 * g.size) {
                    out += Phrase(text.toString(), start.x0, last.x1, start.baseline, start.size)
                    text = StringBuilder(g.text)
                    start = g
                } else {
                    if (gap > 0.15 * size && !text.endsWith(" ")) text.append(' ')
                    text.append(g.text)
                }
                last = g
            }
            out += Phrase(text.toString(), start.x0, last.x1, start.baseline, start.size)
        }
        return out
    }

    // Instructions as written, in any case (but Fine and Coda, which a lyric could hold): with the dots, or without them
    // alone or before "al ..." (so a stray "DC" in other words isn't a jump).
    private val DC = Regex("""(?i)^\s*(?:(?:d\.\s?c\.?|da\s+capo)(?![\p{L}])|dc\.?\s*(?:$|al\s))""")
    private val DS = Regex("""(?i)^\s*(?:(?:d\.\s?s\.?|dal\s+segno)(?![\p{L}])|ds\.?\s*(?:$|al\s))""")
    private val AL_FINE = Regex("""(?i)al\s+fine""")
    private val AL_CODA = Regex("""(?i)al\s+coda""")
    private val TO_CODA = Regex("""(?i)^\s*to\s+(?:the\s+)?coda""")
    private val FINE = Regex("""^\s*(?:Fine|FINE)\s*\.?\s*$""")
    private val CODA_WORD = Regex("""^\s*(?:Coda|CODA)\s*$""")
    private val COUNT = Regex("""(?i)^\s*[(\[]?\s*(?:[x×]\s?(\d{1,2})|(\d{1,2})\s?[x×]|(\d{1,2})\s*(?:times|φορές|φορες))\s*[)\]]?\s*$""")
    /** An ending's numbers: 1. or 2nd, several with commas or a dash between (1.-3.). */
    private val ENDING = Regex("""^\s*\d{1,2}\s*(?:\.|st|nd|rd|th)?(?:\s*[,\-–+&]\s*\d{1,2}\s*(?:\.|st|nd|rd|th)?)*\s*$""")

    /**
     * 1st/2nd-time endings: a thin line above the system with a hook down at its left end and the ending's numbers
     * just under it, to its right. The ending runs to the line's right end, and on into the next system when it reaches
     * this one's end and the next one starts with a bracket line without a hook.
     */
    private fun endings(page: Page, s: System, top: Double, nextPage: Page?, next: System?, phrases: List<Phrase>, marks: Marks, warnings: MutableSet<String>) {
        val sp = s.spacing
        // Where an ending reaching this system's end goes on to in the next system (looked for once).
        val cont: Int? by lazy {
            if (next == null || nextPage == null) return@lazy null
            val nextTop = if (next.page == s.page) max(s.bottom, next.top - 16 * next.spacing) else next.top - 16 * next.spacing
            brackets(nextPage, next, nextTop).firstOrNull { it.x0 <= next.x0 + 12 * next.spacing && !hooked(nextPage, it, next) }?.let { next.position(it.x1) }
        }
        for (l in brackets(page, s, top)) {
            if (!hooked(page, l, s)) continue
            // The numbers hang under the line, which runs on over them (a tuplet's number sits in a gap in its bracket).
            val label = phrases.firstOrNull { p ->
                p.x0 in l.x0 - 0.5 * sp..l.x0 + 4 * sp && p.x1 <= l.x1 + 0.5 * sp && p.baseline - 0.36 * p.size >= l.y + 0.2 * p.size &&
                    p.baseline <= l.y + 4 * sp && ENDING.matches(p.text)
            }
            val numbers = label?.let { numbers(it.text) } ?: emptySet()
            if (numbers.isEmpty()) continue // a tuplet's bracket, a line of a text box ...
            val to = (if (l.x1 >= s.x1 - sp) cont else null) ?: s.position(l.x1)
            marks.endings += Ending(s.position(l.x0), to, numbers, s.page + 1)
        }
    }

    /** Whether bracket line [l] has a hook down at its left end. */
    private fun hooked(page: Page, l: PdfLine, s: System): Boolean {
        val sp = s.spacing
        return page.vlines(l.x0 - 0.6 * sp, l.x0 + 0.6 * sp).any { v -> abs(v.y0 - l.y) <= 0.6 * sp && v.y1 - v.y0 in 0.6 * sp..5 * sp }
    }

    /**
     * Thin horizontal lines above system [s] (below [top]), at least two spacings long, that aren't a staff's: a line
     * drawn several times over is one, and a page with more of them than a score would draw there is left unread.
     */
    private fun brackets(page: Page, s: System, top: Double): List<PdfLine> {
        val sp = s.spacing
        val all = page.lines(top - 25, s.top)
        if (all.size > MAX_LINES_DRAWN) return emptyList()
        val seen = HashSet<Triple<Long, Long, Long>>()
        val near = all.filter { seen.add(Triple(Math.round(2 * it.y), Math.round(2 * it.x0), Math.round(2 * it.x1))) }
        if (near.size > MAX_LINES) return emptyList()
        return near.filter { l ->
            l.x1 - l.x0 >= 2 * sp && l.y >= top && l.y <= s.top - 0.8 * sp && l.x1 > s.x0 && l.x0 < s.x1 + sp &&
                // not a staff line: no other line just like it a few points above or below
                near.none { o -> o !== l && abs(o.x0 - l.x0) < 1 && abs(o.x1 - l.x1) < 1 && abs(o.y - l.y) in 2.0..20.0 }
        }
    }

    /** The numbers an ending's label names: "1." -> 1, "1, 2." -> 1 and 2, "1.-3." -> 1 to 3. */
    private fun numbers(label: String): Set<Int> {
        val nums = Regex("""\d{1,2}""").findAll(label).map { it.value.toInt() }.toList()
        val range = Regex("""\d\s*\.?\s*[\-–]\s*\d""").containsMatchIn(label) && nums.size == 2 && nums[1] in nums[0]..nums[0] + 8
        return (if (range) (nums[0]..nums[1]).toList() else nums).filter { it in 1..16 }.toSet()
    }

    // ---- Playing order ----

    /**
     * Plays the tab through as its marks say, from the first note: repeats taken as many times as written (an ending
     * played the times through it names), a D.C. or D.S. taken once, going on to Fine, or from To Coda to the coda
     * (repeats aren't taken after it, and only the last ending is played). The stretches played, in order, become the
     * sections: a stretch played again straight away is one section played that many times, and stretches played once
     * are cut at double barlines.
     */
    private fun unfold(n: Int, m: Marks, warnings: MutableSet<String>): List<TabSection> {
        val starts = m.starts.keys.filter { it in 0 until n }.sorted()
        val ends = m.ends.filterKeys { it in 1..n }
        for ((at, page) in m.starts) if (ends.keys.none { it > at }) warnings += "The repeat sign |: on page $page has no :| after it: read as played once"
        val lastEnd = ends.keys.maxOrNull() ?: -1
        // The same ending read twice (drawn over itself) is one.
        val distinct = m.endings.distinctBy { Triple(it.from, it.to, it.numbers) }
        if (distinct.size > MAX_ENDINGS) warnings += "Read only the first $MAX_ENDINGS 1st/2nd-time endings; left out the others"
        val endings = distinct.take(MAX_ENDINGS).filter { e ->
            val ok = e.to > e.from && e.from in 0..n && lastEnd >= e.from
            if (!ok) warnings += "The 1st/2nd-time ending on page ${e.page} has no repeat sign (:|) to go with it: read as written"
            ok
        }
        // A :| ending an ending is taken as many times as the ending is played through.
        val times = HashMap<Int, Int>(ends)
        for (e in endings) if (e.to in ends) times[e.to] = max(times.getValue(e.to), e.numbers.size + 1)
        // Whether each boundary is in an ending.
        val inEnding = BooleanArray(n + 2)
        val edges = IntArray(n + 2)
        for (e in endings) { edges[e.from]++; edges[min(n, e.to) + 1]-- }
        var depth = 0
        for (q in 0..n) { depth += edges[q]; inEnding[q] = depth > 0 }
        /** Where a :| at [p] goes back to: the last |: before it, or the last :| outside an ending, or the start. */
        fun back(p: Int): Int = max(0, max(starts.lastOrNull { it < p } ?: -1, ends.keys.filter { it < p && !inEnding[it] }.maxOrNull() ?: -1))
        // The repeat each ending belongs to (where its :| goes back to), and the last ending of each.
        val owner = endings.associateWith { e -> back(ends.keys.filter { it >= e.from }.min()) }
        val lastNumber = HashMap<Int, Int>()
        for (e in endings) lastNumber[owner.getValue(e)] = max(lastNumber[owner.getValue(e)] ?: 0, e.numbers.max())

        val segno = m.segnos.minByOrNull { it.first }?.first
        val first = m.jumps.minByOrNull { it.at }
        if (m.jumps.size > 1) warnings += "Read only the first D.C. or D.S. (page ${first!!.page}); left out the others"
        val jump = first?.let { j ->
            when {
                j.segno && segno == null -> { warnings += "Left out the D.S. on page ${j.page}: no segno sign found to go back to"; null }
                j.segno && segno!! >= j.at -> { warnings += "Left out the D.S. on page ${j.page}: the segno sign isn't before it"; null }
                else -> j
            }
        }
        if (first == null && m.fines.isNotEmpty()) warnings += "Left out \"Fine\" on page ${m.fines.first().second}: no D.C. or D.S. leads back to it"
        // The coda: where to leave (To Coda, else the first of two coda signs) and where to go (the last coda sign).
        val codaSigns = m.codaSigns.filter { !it.third }.map { it.first }.sorted()
        var leaveAt = (m.codaSigns.filter { it.third }.map { it.first } + m.toCodas.map { it.first }).minOrNull()
        var codaAt = codaSigns.lastOrNull()
        if (leaveAt == null && codaSigns.size >= 2) leaveAt = codaSigns.first()
        if (leaveAt != null && codaAt != null && codaAt <= leaveAt) codaAt = null
        val alCoda = jump != null && !jump.alFine && leaveAt != null && codaAt != null
        if (jump != null && jump.alCoda && !alCoda) {
            warnings += "The ${if (jump.segno) "D.S." else "D.C."} al Coda on page ${jump.page} has no " +
                (if (leaveAt == null) "To Coda" else "coda") + " sign that could be read: played on to the end"
        }
        val back0 = if (jump?.segno == true) segno!! else 0
        val fine = if (jump != null && !alCoda) m.fines.map { it.first }.filter { it > back0 }.minOrNull() else null

        val byFrom = endings.sortedBy { it.from }
        val segs = ArrayList<IntArray>()
        val taken = HashMap<Int, Int>()
        val backs = HashMap<Int, Int>() // times the playing went back to each place
        var cur = 0
        var from = 0
        var jumped = false
        var coda = false
        while (cur < n && segs.size < MAX_SECTIONS) {
            // The next thing that changes where the playing goes, after cur. At one barline a :| comes before the ending
            // that starts there and before a D.C. or D.S. written by it (taken once the repeat is done); a :| or a jump
            // at the tab's very end still counts.
            var at = n
            var what = 0
            var skip: Ending? = null
            for (e in byFrom) {
                if (e.from < cur || e.from >= at) continue
                val play = if (jumped) e.numbers.max() == lastNumber[owner.getValue(e)] else 1 + (backs[owner.getValue(e)] ?: 0) in e.numbers
                if (!play) { at = e.from; what = SKIP; skip = e }
            }
            if (!jumped) for ((p, t) in times) if (p > cur && p <= at && (taken[p] ?: 0) < t - 1) { at = p; what = BACK }
            if (jumped && fine != null && fine > cur && fine < at) { at = fine; what = STOP }
            if (jumped && alCoda && !coda && leaveAt!! > cur && leaveAt < at) { at = leaveAt; what = CODA }
            if (!jumped && jump != null && jump.at > cur && (jump.at < at || jump.at == at && what != BACK)) { at = jump.at; what = JUMP }
            if (at > from) segs += intArrayOf(from, at)
            cur = when (what) {
                SKIP -> skip!!.to
                BACK -> { taken[at] = (taken[at] ?: 0) + 1; back(at).also { backs[it] = (backs[it] ?: 0) + 1 } }
                CODA -> { coda = true; codaAt!! }
                JUMP -> { jumped = true; back0 }
                else -> n
            }
            from = cur
        }
        if (segs.isEmpty()) return listOf(TabSection(0, n))
        // Cut at every mark, so a stretch played again straight away is one section played that many times; then
        // stretches played once in a row are one again, but cut at double barlines.
        val marks = (starts + ends.keys + endings.flatMap { listOf(it.from, it.to) } + listOfNotNull(segno, leaveAt, codaAt, fine, jump?.at))
            .filter { it in 1 until n }.toSortedSet()
        val pieces = segs.flatMap { sg ->
            val inside = listOf(sg[0]) + marks.subSet(sg[0] + 1, sg[1]) + sg[1]
            inside.zipWithNext { a, b -> TabSection(a, b) }
        }
        val runs = ArrayList<TabSection>()
        for (p in pieces) {
            val last = runs.lastOrNull()
            if (last != null && last.from == p.from && last.to == p.to) runs[runs.size - 1] = last.copy(times = last.times + 1) else runs += p
        }
        val out = ArrayList<TabSection>()
        for (r in runs) {
            val last = out.lastOrNull()
            if (last != null && last.times == 1 && r.times == 1 && last.to == r.from) out[out.size - 1] = last.copy(to = r.to) else out += r
        }
        val cuts = m.cuts.sorted()
        return out.flatMap { s ->
            if (s.times > 1) listOf(s) else {
                val inside = listOf(s.from) + cuts.filter { it > s.from && it < s.to } + s.to
                inside.zipWithNext { a, b -> TabSection(a, b) }
            }
        }
    }

    private const val SKIP = 1
    private const val BACK = 2
    private const val STOP = 3
    private const val CODA = 4
    private const val JUMP = 5
    /** The most sections a form may unfold to (a loop of marks that never ends is cut there). */
    private const val MAX_SECTIONS = 256
}
