package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import com.bouzouki.core.song.Articulation.HAMMER
import com.bouzouki.core.song.Articulation.PICK
import com.bouzouki.core.song.Articulation.PULL
import com.bouzouki.core.song.Articulation.SLIDE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PdfTab on PDFs written here in the style of notation programs' exports (a notation staff over
 * a tab staff, frets as text on the lines), with everything a real page has around the frets.
 * Every phrase is made up for these tests.
 */
class PdfTabTest {
    // ---- Drawing ----

    /**
     * Draws a page in points from the top left. [flip] writes it the way Sibelius does (a
     * quarter-point grid with y flipped, text flipped back by its text matrix); otherwise plain
     * PDF coordinates, shifted by ([ox], [oy]) for drawing inside a form with that /Matrix.
     */
    private class Ink(private val flip: Boolean, private val ox: Double = 0.0, private val oy: Double = 0.0) {
        private val sb = StringBuilder(if (flip) "q 0.25 0 0 -0.25 0 $H cm\n" else "")
        private fun f(v: Double) = "%.3f".format(java.util.Locale.ROOT, v)
        private fun px(x: Double) = if (flip) f(4 * x) else f(x - ox)
        private fun py(y: Double) = if (flip) f(4 * y) else f(H - y - oy)
        private val k = if (flip) 4.0 else 1.0

        fun line(x0: Double, x1: Double, y: Double, width: Double = 0.45) {
            sb.append("${f(width * k)} w ${px(x0)} ${py(y)} m ${px(x1)} ${py(y)} l S\n")
        }

        fun vline(x: Double, y0: Double, y1: Double, width: Double = 0.6) {
            sb.append("${f(width * k)} w ${px(x)} ${py(y0)} m ${px(x)} ${py(y1)} l S\n")
        }

        /** A filled bar centred on [y]. */
        fun bar(x0: Double, x1: Double, y: Double, thick: Double) {
            val top = if (flip) py(y - thick / 2) else py(y + thick / 2)
            sb.append("${px(x0)} $top ${f((x1 - x0) * k)} ${f(thick * k)} re f\n")
        }

        fun text(font: TestFont, size: Double, x: Double, baseline: Double, s: String) {
            if (flip) sb.append("BT /${font.resName} ${f(4 * size)} Tf 1 0 0 -1 ${px(x)} ${py(baseline)} Tm ${font.encode(s)} Tj ET\n")
            else sb.append("BT /${font.resName} ${f(size)} Tf ${px(x)} ${py(baseline)} Td ${font.encode(s)} Tj ET\n")
        }

        /** Two strings in one TJ, the second moved left by [kern] thousandths of an em. */
        fun kerned(font: TestFont, size: Double, x: Double, baseline: Double, a: String, kern: Int, b: String) {
            sb.append("BT /${font.resName} ${f(size * k)} Tf ${if (flip) "1 0 0 -1" else "1 0 0 1"} ${px(x)} ${py(baseline)} Tm [${font.encode(a)} $kern ${font.encode(b)}] TJ ET\n")
        }

        /** A tie: a filled crescent from [x0] to [x1], its ends at height [y], bulging up. */
        fun tie(x0: Double, x1: Double, y: Double) {
            val d = (x1 - x0) / 3
            val up = if (flip) -1.0 else 1.0
            fun pt(x: Double, dy: Double) = "${px(x)} ${if (flip) f(4 * (y - dy)) else f(H - y + dy * up - oy)}"
            sb.append("${pt(x0, 0.0)} m ${pt(x0 + d, 3.0)} ${pt(x1 - d, 3.0)} ${pt(x1, 0.0)} c ${pt(x1 - d, 2.2)} ${pt(x0 + d, 2.2)} ${pt(x0, 0.0)} c f\n")
        }

        /** A filled round dot of radius [r] centred on ([cx], [cy]), drawn with curves. */
        fun dot(cx: Double, cy: Double, r: Double) {
            val k = 0.55 * r
            fun pt(x: Double, y: Double) = "${px(x)} ${py(y)}"
            sb.append("${pt(cx + r, cy)} m ${pt(cx + r, cy + k)} ${pt(cx + k, cy + r)} ${pt(cx, cy + r)} c ")
            sb.append("${pt(cx - k, cy + r)} ${pt(cx - r, cy + k)} ${pt(cx - r, cy)} c ${pt(cx - r, cy - k)} ${pt(cx - k, cy - r)} ${pt(cx, cy - r)} c ")
            sb.append("${pt(cx + k, cy - r)} ${pt(cx + r, cy - k)} ${pt(cx + r, cy)} c f\n")
        }

        /** A glyph of the music font (F4), as bytes of its WinAnsi encoding, at ([x], [baseline]). */
        fun music(size: Double, x: Double, baseline: Double, code: Int) {
            val str = "(\\%03o)".format(code)
            if (flip) sb.append("BT /F4 ${f(4 * size)} Tf 1 0 0 -1 ${px(x)} ${py(baseline)} Tm $str Tj ET\n")
            else sb.append("BT /F4 ${f(size)} Tf ${px(x)} ${py(baseline)} Td $str Tj ET\n")
        }

        fun raw(s: String) { sb.append(s).append('\n') }
        fun done() = sb.toString() + (if (flip) "Q\n" else "")
    }

    /** A mark written as text: on tab line [line], or [ABOVE] or [BELOW] the staff, at slot position [at]. */
    private class Mark(val text: String, val at: Double, val line: Int)

    /** A tie on tab line [line] from slot position [from] to [to]; [START] and [END] are the ends of the system. */
    private class Tie(val from: Double, val to: Double, val line: Int)

    /** A sign of the score's form at slot position [at] (between two slots, where a barline goes). */
    private sealed class Form { abstract val at: Double }

    /**
     * A repeat barline: |: ([start]), :| ([end]) or both, a thick and a thin line with two dots each side that repeats;
     * the dots drawn as text (a music font's dot) or as filled curves ([curves]), on both staffs or only the notation
     * staff's ([tabDots] false).
     */
    private class Repeat(override val at: Double, val start: Boolean, val end: Boolean, val curves: Boolean = false, val tabDots: Boolean = true) : Form()

    /** Two thin barlines: the end of a part. */
    private class DoubleBar(override val at: Double) : Form()

    /** A 1st/2nd-time ending: a bracket over the notation staff from [at] to [to], with a hook down at its start and [label]. */
    private class Ending(override val at: Double, val to: Double, val label: String, val closed: Boolean = true) : Form()

    /** Words over the notation staff (D.S. al Coda, Fine, x3 ...), their right end at [at]. */
    private class Words(override val at: Double, val text: String) : Form()

    /** A glyph of a Sonata-style music font over the notation staff (% a segno, Þ a coda), centred on [at]. */
    private class Sign(override val at: Double, val code: Int) : Form()

    /** Anything else drawn with the system (a hostile page's clutter). */
    private class Raw(override val at: Double, val draw: (Ink, Sys) -> Unit) : Form()

    /**
     * One system: a notation staff at [top] over a tab staff of [lines] lines; frets by slot (line
     * from the top, text). [barTo] draws the system's barline on down to that height (over a second
     * part's staffs). [bars] are the slot positions of its plain barlines, [form] the signs of the score's form.
     */
    private class Sys(
        val x0: Double, val x1: Double, val top: Double, val lines: Int,
        val slots: Map<Int, List<Pair<Int, String>>>, val marks: List<Mark> = emptyList(), val tabLines: Int = lines,
        val ties: List<Tie> = emptyList(), val barTo: Double? = null,
        val bars: List<Double> = listOf(8.5), val form: List<Form> = emptyList(),
    ) {
        val tabTop get() = top + 55
        fun slotX(at: Double) = x0 + 50 + STEP * at
        fun lineY(i: Int) = tabTop + SPACING * i
    }

    /** How a test file is written. */
    private class Style(
        val flip: Boolean = true,
        val type0: Boolean = false,
        val objectStreams: Boolean = false,
        val form: Boolean = false,
        val bars: Boolean = false,
        val kern: Boolean = false,
        val encryption: TestEncryption? = null,
        val rotated: Boolean = false,
    )

    private class Built(val bytes: ByteArray, val pages: List<Int>, val pagesNode: Int, val catalog: Int, val size: Int, val fonts: String)

    private fun widthOf(font: TestFont, s: String, size: Double) = s.sumOf { font.width(it) } * size / 1000

    private fun drawSystem(ink: Ink, s: Sys, font: TestFont, music: TestFont, style: Style) {
        // Notation staff, barlines, bar number, time signatures, clef letters.
        for (i in 0 until 5) ink.line(s.x0, s.x1, s.top + 5.0 * i)
        val tabBottom = s.lineY(s.tabLines - 1)
        for (x in listOf(s.x0, s.x1) + s.bars.map { s.slotX(it) }) { ink.vline(x, s.top, s.top + 20); ink.vline(x, s.tabTop, tabBottom) }
        drawForm(ink, s, font)
        ink.text(font, 7.0, s.x0, s.top - 8, "3")
        for (y in listOf(s.top + 10, s.top + 20)) ink.text(music, 20.0, s.x0 + 20, y, "\u0005")
        ink.text(music, 24.0, s.x0 + 22, s.tabTop + 9, "\u0005")
        ink.text(music, 24.0, s.x0 + 22, s.tabTop + 18, "\u0005")
        "TAB".forEachIndexed { i, c -> ink.text(font, 8.0, s.x0 + 6, s.tabTop + 5 + 6 * i, c.toString()) }
        // Tab staff lines: strokes, or thin filled bars.
        for (i in 0 until s.tabLines) {
            if (style.bars) ink.bar(s.x0, s.x1, s.lineY(i), 0.5) else ink.line(s.x0, s.x1, s.lineY(i))
        }
        // Rhythm above the tab: stems, a thick beam, a tuplet number the size of the frets.
        for (k in s.slots.keys) ink.vline(s.slotX(k.toDouble()), s.tabTop - 4, s.tabTop - 18, 0.5)
        ink.bar(s.slotX(0.0), s.slotX(3.0), s.tabTop - 18, 2.0)
        ink.text(font, FRET_SIZE, s.slotX(2.0) - 2, s.tabTop - 9.6, "3")
        // Lyrics between the staffs, with one-letter syllables that aren't marks.
        ink.text(font, 8.0, s.slotX(1.0), s.tabTop - 5 * SPACING, "la")
        ink.text(font, 8.0, s.slotX(3.0), s.tabTop - 5 * SPACING, "h")
        ink.text(font, 8.0, s.slotX(5.0), s.tabTop - 5 * SPACING, "p")
        // Frets, centred on their slot and their line.
        for ((k, frets) in s.slots) for ((line, t) in frets) {
            val cx = s.slotX(k.toDouble())
            val baseline = s.lineY(line) + 0.35 * FRET_SIZE
            if (style.kern && t.length == 2) {
                ink.kerned(font, FRET_SIZE, cx - widthOf(font, t, FRET_SIZE) / 2 + 0.1, baseline, t.substring(0, 1), 30, t.substring(1))
            } else ink.text(font, FRET_SIZE, cx - widthOf(font, t, FRET_SIZE) / 2, baseline, t)
        }
        for (m in s.marks) {
            val baseline = when (m.line) {
                ABOVE -> s.tabTop - SPACING
                BELOW -> tabBottom + 1.8 * SPACING
                else -> s.lineY(m.line) + 0.35 * FRET_SIZE
            }
            ink.text(font, FRET_SIZE, s.slotX(m.at) - widthOf(font, m.text, FRET_SIZE) / 2, baseline, m.text)
        }
        // Ties just above the line, from inside one fret to inside the next.
        for (t in s.ties) {
            val a = if (t.from == START) s.x0 + 30 else s.slotX(t.from) + 1
            val b = if (t.to == END) s.x1 else s.slotX(t.to) - 1
            ink.tie(a, b, s.lineY(t.line) - 0.6 * SPACING)
        }
        s.barTo?.let { ink.vline(s.x0, s.top, it) }
    }

    /** Draws the signs of the score's form of system [s]. */
    private fun drawForm(ink: Ink, s: Sys, font: TestFont) {
        val tabBottom = s.lineY(s.tabLines - 1)
        fun across(x: Double, width: Double) { ink.vline(x, s.top, s.top + 20, width); ink.vline(x, s.tabTop, tabBottom, width) }
        fun dots(x: Double, curves: Boolean, tab: Boolean) {
            // In the spaces each side of the middle line: the notation staff's 2nd and 3rd, the tab staff's outer ones.
            val ys = listOf(s.top + 7.5, s.top + 12.5) + if (!tab) emptyList() else if (s.tabLines == 4) listOf(s.tabTop + 3, s.tabTop + 15) else listOf(s.tabTop + 3, s.tabTop + 9)
            for (y in ys) if (curves) ink.dot(x, y, 1.1) else ink.text(font, 9.0, x - widthOf(font, ".", 9.0) / 2, y, ".")
        }
        for (f in s.form) {
            val x = s.slotX(f.at)
            when (f) {
                is Repeat -> {
                    if (f.end) { dots(x - 5.5, f.curves, f.tabDots); across(x - 2, 0.6); across(x + 1.5, 2.5) }
                    if (f.start) { val y = if (f.end) x + 5 else x - 1.5; across(y, 2.5); across(y + 3.5, 0.6); dots(y + 7.5, f.curves, f.tabDots) }
                }
                is DoubleBar -> { across(x - 1.5, 0.6); across(x + 1.5, 0.6) }
                is Ending -> {
                    val y = s.top - 14
                    val end = s.slotX(f.to)
                    ink.line(x + 1, end - 1, y, 0.6)
                    ink.vline(x + 1, y, y + 8, 0.6)
                    if (f.closed) ink.vline(end - 1, y, y + 8, 0.6)
                    ink.text(font, 8.0, x + 3, y + 9, f.label)
                }
                is Words -> ink.text(font, 9.0, x - widthOf(font, f.text, 9.0), s.top - 4, f.text)
                is Sign -> ink.music(14.0, x - 5, s.top - 4, f.code)
                is Raw -> f.draw(ink, s)
            }
        }
    }

    /** A tab PDF of [pages] (each a list of systems), in [style]. */
    private fun tabPdf(pages: List<List<Sys>>, style: Style = Style()): Built {
        val w = TestPdfWriter(style.objectStreams, style.encryption)
        val font = TestFont("F1", style.type0)
        val music = TestFont("F3", false)
        val fontNum = font.write(w)
        val musicNum = w.add(
            "<< /Type /Font /Subtype /Type1 /BaseFont /MusicStd /FirstChar 1 /LastChar 10 /Widths [500 500 500 500 500 500 500 500 500 500] " +
                "/Encoding << /Type /Encoding /Differences [1 /zero /one /two /three /four /five /six /seven /eight /nine] >> >>",
        )
        // A music font laid out like Sonata (Opus Special), for the form's signs.
        val signs = w.add("<< /Type /Font /Subtype /Type1 /BaseFont /ABCDEF+OpusSpecialStd /FirstChar 32 /LastChar 255 /Widths [${List(224) { 700 }.joinToString(" ")}] /Encoding /WinAnsiEncoding >>")
        val fonts = "<< /F1 $fontNum 0 R /F3 $musicNum 0 R /F4 $signs 0 R >>"
        val pagesNode = w.reserve()
        val middle = if (style.type0) w.reserve() else pagesNode
        val pageNums = ArrayList<Int>()
        for ((i, systems) in pages.withIndex()) {
            val ink = Ink(style.flip)
            if (i == 0) ink.text(font, 14.0, 200.0, 50.0, "made up etude")
            ink.text(font, 10.0, 290.0, if (i == 0) 30.0 else 820.0, "${i + 1}")
            var xobjects = ""
            if (style.form) {
                // The systems go into a form drawn with a translation, like a placed graphic.
                val formInk = Ink(false, 10.0, -20.0)
                for (s in systems) drawSystem(formInk, s, font, music, style)
                val form = w.stream("/Type /XObject /Subtype /Form /BBox [-50 -50 650 900] /Matrix [1 0 0 1 10 -20] /Resources << /Font $fonts >>", formInk.done().toByteArray())
                ink.raw("q /Fm1 Do Q")
                xobjects = " /XObject << /Fm1 $form 0 R >>"
                // Text operators the systems don't use: leading, next line, ' and ".
                ink.raw("BT /F1 8 Tf 14 TL 60 60 Td ${font.encode("made up")} Tj T* ${font.encode("etude")} ' 0 0 ${font.encode("a")} \" ET")
            } else for (s in systems) drawSystem(ink, s, font, music, style)
            // A rotated page: landscape media turned for display, the music drawn turned the other way.
            val text = if (style.rotated) "q 0 1 -1 0 $H 0 cm\n${ink.done()}Q\n" else ink.done()
            val cut = text.indexOf('\n', text.length / 2) + 1
            val c1 = w.stream("", text.substring(0, cut).toByteArray(Charsets.ISO_8859_1))
            val c2 = w.stream("", text.substring(cut).toByteArray(Charsets.ISO_8859_1))
            val resources = if (style.type0 || style.form) " /Resources << /Font $fonts$xobjects >>" else ""
            val turn = if (style.rotated) " /MediaBox [0 0 $H 595] /Rotate 90" else ""
            pageNums += w.add("<< /Type /Page /Parent $middle 0 R /Contents [$c1 0 R $c2 0 R]$resources$turn >>")
        }
        val kids = pageNums.joinToString(" ") { "$it 0 R" }
        if (middle != pagesNode) {
            w.set(middle, "<< /Type /Pages /Parent $pagesNode 0 R /Kids [$kids] /Count ${pageNums.size} >>")
            w.set(pagesNode, "<< /Type /Pages /Kids [$middle 0 R] /Count ${pageNums.size} /MediaBox [0 0 595 $H] >>")
        } else {
            w.set(pagesNode, "<< /Type /Pages /Kids [$kids] /Count ${pageNums.size} /MediaBox [0 0 595 $H] /Resources << /Font $fonts >> >>")
        }
        val catalog = w.add("<< /Type /Catalog /Pages $pagesNode 0 R >>")
        val bytes = w.build(catalog)
        return Built(bytes, pageNums, pagesNode, catalog, pageNums.size + 100, fonts)
    }

    // ---- The made-up tab ----

    private val system1 = Sys(
        80.0, 540.0, 90.0, 4,
        mapOf(
            0 to listOf(0 to "0"), 1 to listOf(0 to "2"), 2 to listOf(0 to "3"), 3 to listOf(0 to "5"),
            4 to listOf(0 to "7", 1 to "9"), 5 to listOf(0 to "8"), 6 to listOf(0 to "10"), 7 to listOf(0 to "8"),
            8 to listOf(0 to "(7)"), 9 to listOf(1 to "12"), 11 to listOf(1 to "10"), 12 to listOf(2 to "3"), 13 to listOf(3 to "2"),
        ),
        listOf(Mark("h", 5.5, 0), Mark("P", 6.5, ABOVE), Mark("/", 10.6, 1)),
    )
    private val system2 = Sys(
        60.0, 540.0, 260.0, 4,
        mapOf(
            0 to listOf(1 to "5"), 1 to listOf(1 to "7"), 2 to listOf(0 to "3"), 3 to listOf(0 to "5", 1 to "5", 2 to "7"),
            4 to listOf(0 to "15"), 5 to listOf(0 to "17"), 6 to listOf(0 to "0"),
        ),
        listOf(Mark("sl.", 3.5, BELOW)),
    )
    private val system3 = Sys(
        60.0, 540.0, 90.0, 4,
        mapOf(0 to listOf(3 to "0"), 1 to listOf(2 to "2"), 2 to listOf(1 to "4"), 3 to listOf(0 to "26"), 4 to listOf(0 to "29"), 5 to listOf(0 to "1")),
    )
    private val pages = listOf(listOf(system1, system2), listOf(system3))

    /**
     * What PdfTab should read: course 3 is the top line (D); 2 columns per note step; 3 columns between systems; a
     * chord is its top note with the others in its stack. Each system has a barline after slot 8 and one at its end;
     * bars are counted on through the systems, each starting a new one (the first system's bars are 0 and 1, the
     * second system's start at 3).
     */
    private val expected = listOf(
        TabNote(3, 0, 0), TabNote(3, 2, 2), TabNote(3, 3, 4), TabNote(3, 5, 6),
        TabNote(3, 7, 8, stack = listOf(2 to 9)),
        TabNote(3, 8, 10), TabNote(3, 10, 12, HAMMER), TabNote(3, 8, 14, PULL), TabNote(3, 7, 16),
        TabNote(2, 12, 18, bar = 1), TabNote(2, 10, 22, SLIDE, bar = 1), TabNote(1, 3, 24, bar = 1), TabNote(0, 2, 26, bar = 1),
        TabNote(2, 5, 29, system = 1, bar = 3), TabNote(2, 7, 31, system = 1, bar = 3), TabNote(3, 3, 33, system = 1, bar = 3),
        TabNote(3, 5, 35, stack = listOf(2 to 5, 1 to 7), system = 1, bar = 3),
        TabNote(3, 15, 37, SLIDE, system = 1, bar = 3), TabNote(3, 17, 39, system = 1, bar = 3), TabNote(3, 0, 41, system = 1, bar = 3),
        // Page 2; its "29" is too big for a fret and is left out.
        TabNote(0, 0, 44, system = 2, bar = 6), TabNote(1, 2, 46, system = 2, bar = 6), TabNote(2, 4, 48, system = 2, bar = 6),
        TabNote(3, 26, 50, system = 2, bar = 6), TabNote(3, 1, 54, system = 2, bar = 6),
    )
    private val expectedWarnings = listOf(
        "Kept 2 chords (notes played together)",
        "Kept 1 note in brackets (tied or ghost notes)",
        "Left out 1 number on the tab lines too big to be frets",
    )

    private fun assertTab(tab: ParsedTab, warnings: List<String> = expectedWarnings) {
        assertEquals(Instrument.TETRACHORDO, tab.instrument)
        assertEquals(expected, tab.notes)
        assertEquals(warnings, tab.warnings)
    }

    // ---- Tests ----

    @Test
    fun readsASibeliusStylePage() {
        assertTab(PdfTab.read(tabPdf(pages).bytes, Instrument.TETRACHORDO))
    }

    @Test
    fun readsObjectStreamsType0FontsAndForms() {
        val style = Style(flip = false, type0 = true, objectStreams = true, form = true, bars = true, kern = true)
        val pdf = tabPdf(pages, style).bytes
        assertTrue(String(pdf, Charsets.ISO_8859_1).contains("/ObjStm"))
        assertTab(PdfTab.read(pdf, Instrument.TETRACHORDO))
    }

    @Test
    fun readsRotatedPages() {
        assertTab(PdfTab.read(tabPdf(pages, Style(rotated = true)).bytes, Instrument.TETRACHORDO))
    }

    @Test
    fun readsATrichordoTab() {
        val sys = Sys(
            60.0, 540.0, 100.0, 3,
            mapOf(0 to listOf(0 to "0"), 1 to listOf(0 to "2"), 2 to listOf(0 to "3", 1 to "3"), 3 to listOf(2 to "5"), 4 to listOf(0 to "12")),
            listOf(Mark("h", 1.5, 0)),
        )
        val tab = PdfTab.read(tabPdf(listOf(listOf(sys))).bytes, Instrument.TETRACHORDO)
        assertEquals(Instrument.TRICHORDO, tab.instrument)
        assertEquals(
            listOf(TabNote(2, 0, 0), TabNote(2, 2, 2), TabNote(2, 3, 4, HAMMER, listOf(1 to 3)), TabNote(0, 5, 6), TabNote(2, 12, 8)),
            tab.notes,
        )
        assertEquals(listOf("This tab is for the trichordo (3 courses), not the tetrachordo", "Kept 1 chord (notes played together)"), tab.warnings)
        assertEquals(listOf("Kept 1 chord (notes played together)"), PdfTab.read(tabPdf(listOf(listOf(sys))).bytes, Instrument.TRICHORDO).warnings)
    }

    @Test
    fun leavesProtectedFilesAlone() {
        // Protection is never removed, even an empty-password "no copying" lock: nothing is read, and it says why.
        for (kind in TestEncryption.Kind.values()) {
            val tab = PdfTab.read(tabPdf(pages, Style(encryption = TestEncryption(kind))).bytes, Instrument.TETRACHORDO)
            assertEquals(emptyList(), tab.notes)
            assertTrue(tab.warnings.first().contains("encryption"), tab.warnings.toString())
        }
    }

    @Test
    fun saysSoWhenAPasswordIsNeeded() {
        val pdf = tabPdf(pages, Style(encryption = TestEncryption(TestEncryption.Kind.AES_128, userPassword = "secret"))).bytes
        val tab = PdfTab.read(pdf, Instrument.TETRACHORDO)
        assertEquals(emptyList(), tab.notes)
        assertTrue(tab.warnings.first().contains("password"), tab.warnings.toString())
    }

    @Test
    fun followsIncrementalUpdates() {
        val built = tabPdf(pages)
        // The update replaces page 2 with one whose tab is different.
        val changed = Sys(60.0, 540.0, 90.0, 4, mapOf(0 to listOf(1 to "2"), 1 to listOf(1 to "4"), 2 to listOf(0 to "5")))
        val ink = Ink(true)
        drawSystem(ink, changed, TestFont("F1", false), TestFont("F3", false), Style())
        val content = ink.done()
        val n = built.size + 10
        val pdf = TestPdfWriter.update(
            built.bytes,
            mapOf(
                n to "<< /Length ${content.length} >>\nstream\n$content\nendstream",
                built.pages[1] to "<< /Type /Page /Parent ${built.pagesNode} 0 R /Contents $n 0 R >>",
            ),
            built.catalog, n + 1,
        )
        val tab = PdfTab.read(pdf, Instrument.TETRACHORDO)
        assertEquals(expected.take(20) + listOf(TabNote(2, 2, 44, system = 2, bar = 6), TabNote(2, 4, 46, system = 2, bar = 6), TabNote(3, 5, 48, system = 2, bar = 6)), tab.notes)
    }

    @Test
    fun repairsABrokenCrossReferenceTable() {
        for (style in listOf(Style(), Style(flip = false, type0 = true, objectStreams = true, form = true))) {
            val text = String(tabPdf(pages, style).bytes, Charsets.ISO_8859_1)
            val at = text.lastIndexOf("startxref") + "startxref\n".length
            val broken = (text.substring(0, at) + "999" + text.substring(at)).toByteArray(Charsets.ISO_8859_1)
            assertTab(PdfTab.read(broken, Instrument.TETRACHORDO), listOf(DAMAGED) + expectedWarnings)
        }
        // Wrong stream lengths everywhere (and so wrong offsets too).
        val text = String(tabPdf(pages).bytes, Charsets.ISO_8859_1)
        val wrong = Regex("/Length \\d+").replace(text, "/Length 3").toByteArray(Charsets.ISO_8859_1)
        assertTab(PdfTab.read(wrong, Instrument.TETRACHORDO), listOf(DAMAGED) + expectedWarnings)
    }

    @Test
    fun aScannedPictureHasNoNumbers() {
        val w = TestPdfWriter()
        val image = w.stream("/Type /XObject /Subtype /Image /Width 8 /Height 8 /ColorSpace /DeviceGray /BitsPerComponent 8", ByteArray(64) { (it * 4).toByte() }, compress = false)
        val content = w.stream("", "q 400 0 0 500 100 200 cm /Im1 Do Q".toByteArray())
        val pagesNode = w.reserve()
        val page = w.add("<< /Type /Page /Parent $pagesNode 0 R /MediaBox [0 0 595 842] /Contents $content 0 R /Resources << /XObject << /Im1 $image 0 R >> >> >>")
        w.set(pagesNode, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        val tab = PdfTab.read(w.build(w.add("<< /Type /Catalog /Pages $pagesNode 0 R >>")), Instrument.TRICHORDO)
        assertEquals(Instrument.TRICHORDO, tab.instrument)
        assertEquals(emptyList(), tab.notes)
        assertEquals(listOf(PdfTab.NO_NUMBERS), tab.warnings)
    }

    @Test
    fun staffsWithoutTextNumbersHaveNoNumbers() {
        // The frets drawn as outlines (paths), not text.
        val ink = Ink(true)
        for (i in 0 until 4) ink.line(60.0, 540.0, 150.0 + 6 * i)
        for (k in 0 until 8) ink.raw("${100 + 80 * k} ${600} m ${104 + 80 * k} ${612} l ${108 + 80 * k} ${600} l h f")
        val w = TestPdfWriter()
        val content = w.stream("", ink.done().toByteArray())
        val pagesNode = w.reserve()
        val page = w.add("<< /Type /Page /Parent $pagesNode 0 R /MediaBox [0 0 595 842] /Contents $content 0 R >>")
        w.set(pagesNode, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        val tab = PdfTab.read(w.build(w.add("<< /Type /Catalog /Pages $pagesNode 0 R >>")), Instrument.TETRACHORDO)
        assertEquals(emptyList(), tab.notes)
        assertEquals(listOf(PdfTab.NO_NUMBERS), tab.warnings)
    }

    @Test
    fun skipsGuitarTabs() {
        val guitar = Sys(60.0, 540.0, 100.0, 6, mapOf(0 to listOf(0 to "3"), 1 to listOf(2 to "5"), 2 to listOf(5 to "0")))
        val tab = PdfTab.read(tabPdf(listOf(listOf(guitar))).bytes, Instrument.TETRACHORDO)
        assertEquals(emptyList(), tab.notes)
        assertEquals(listOf("Skipped 1 guitar tab staff (6 lines); bouzouki tabs have 3 or 4", PdfTab.NO_NUMBERS), tab.warnings)
    }

    @Test
    fun rebuildsTheTextOfATextTabPrintedToPdf() {
        val typed = listOf(
            "Made-up exercise",
            "",
            "D|--0--2--3--5h7--|",
            "A|--------------5-|",
            "F|----------------|",
            "C|----------------|",
            "",
            "D|--7--5--3-------|",
            "A|-----------5--3-|",
            "F|----------------|",
            "C|----------------|",
        )
        val w = TestPdfWriter()
        // Typed in Courier, 12 pt apart, with an empty line between title and systems.
        val body = StringBuilder("BT /F1 10 Tf 12 TL 50 780 Td\n")
        for (line in typed) body.append(if (line.isEmpty()) "T*\n" else "(${line}) Tj T*\n")
        body.append("ET")
        val content = w.stream("", body.toString().toByteArray())
        val font = w.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>")
        val pagesNode = w.reserve()
        val page = w.add("<< /Type /Page /Parent $pagesNode 0 R /MediaBox [0 0 595 842] /Contents $content 0 R /Resources << /Font << /F1 $font 0 R >> >> >>")
        w.set(pagesNode, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        val pdf = w.build(w.add("<< /Type /Catalog /Pages $pagesNode 0 R >>"))
        // The text handed to the text tab reader keeps the columns and the empty lines between systems.
        val text = PdfTab.text(PdfTab.readPages(pdf, LinkedHashSet()))
        assertEquals(typed.joinToString("\n", postfix = "\n"), text)
        // What comes back is the text tab reader's reading of that text, with its warnings.
        val parsed = TabParser.parse(text, Instrument.TETRACHORDO)
        val tab = PdfTab.read(pdf, Instrument.TETRACHORDO)
        assertEquals(parsed.notes, tab.notes)
        assertTrue(tab.warnings.containsAll(parsed.warnings), tab.warnings.toString())
        assertEquals(parsed.notes.isEmpty(), PdfTab.NO_TEXT_TAB in tab.warnings)
        assertTrue(PdfTab.NO_NUMBERS !in tab.warnings)
    }

    @Test
    fun readsGlyphPositionsLikeAPdfViewer() {
        // Tc, Tw, Tz, Ts and TJ moves: "a b" with 1 pt character spacing, 3 pt word spacing, 200 % width.
        val w = TestPdfWriter()
        val content = w.stream("", "BT /F1 10 Tf 1 Tc 3 Tw 200 Tz 2 Ts 100 700 Td (a b) Tj [(c) -500 (d)] TJ ET".toByteArray())
        val font = w.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FirstChar 97 /LastChar 100 /Widths [500 500 500 500] >>")
        val pagesNode = w.reserve()
        val page = w.add("<< /Type /Page /Parent $pagesNode 0 R /MediaBox [0 0 595 842] /Contents $content 0 R /Resources << /Font << /F1 $font 0 R >> >> >>")
        w.set(pagesNode, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        val glyphs = PdfTab.readPages(w.build(w.add("<< /Type /Catalog /Pages $pagesNode 0 R >>")), LinkedHashSet()).single().glyphs
        assertEquals(listOf("a", " ", "b", "c", "d"), glyphs.map { it.text })
        // a: 100..110; advance (5 + 1) * 2 = 12. Space: Helvetica guess 278 → (2.78 + 1 + 3) * 2. b, c: 12 each, then 5 pt (half an em at 200 %).
        val x0 = listOf(100.0, 112.0, 112.0 + 13.56, 112.0 + 13.56 + 12, 112.0 + 13.56 + 24 + 10)
        for ((g, x) in glyphs.zip(x0)) assertEquals(x, g.x0, 1e-6)
        assertEquals(842.0 - 702.0, glyphs[0].baseline, 1e-6)
        assertEquals(10.0, glyphs[0].size, 1e-6)
        assertEquals(10.0, glyphs[0].x1 - glyphs[0].x0, 1e-6)
    }

    /** A one-page PDF drawn by [draw] in plain PDF coordinates (from the top left), with the test font as F1. */
    private fun onePage(draw: (Ink, TestFont) -> Unit): ByteArray {
        val w = TestPdfWriter()
        val font = TestFont("F1", false)
        val fontNum = font.write(w)
        val ink = Ink(false)
        draw(ink, font)
        val content = w.stream("", ink.done().toByteArray(Charsets.ISO_8859_1))
        val pagesNode = w.reserve()
        val page = w.add("<< /Type /Page /Parent $pagesNode 0 R /MediaBox [0 0 595 $H] /Contents $content 0 R /Resources << /Font << /F1 $fontNum 0 R >> >> >>")
        w.set(pagesNode, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        return w.build(w.add("<< /Type /Catalog /Pages $pagesNode 0 R >>"))
    }

    @Test
    fun readsDenseRunsOfTwoDigitFrets() {
        // A made-up fast run on the top line, set as densely as notation programs set 16ths:
        // 0.1 to 0.2 em between numbers, while the digits of a number touch.
        val run = listOf("17", "12", "10", "7", "12", "14", "15", "17", "19", "17", "15", "14", "12")
        val gaps = listOf(0.10, 0.14, 0.20, 0.12, 0.10, 0.16, 0.11, 0.10, 0.13, 0.18, 0.10, 0.15)
        // On the next line, numbers so close that their digits touch, but which only make sense as two
        // frets each (17 12 and 10 7, 0.05 em apart), and a real 29, too big for a fret.
        val close = listOf(listOf("17", "12"), listOf("10", "7"), listOf("29"))
        val pdf = onePage { ink, font ->
            for (i in 0 until 4) ink.line(60.0, 540.0, 300.0 + SPACING * i)
            var x = 80.0
            for ((i, t) in run.withIndex()) {
                ink.text(font, FRET_SIZE, x, 300.0 + 0.35 * FRET_SIZE, t)
                x += widthOf(font, t, FRET_SIZE) + (gaps.getOrNull(i) ?: 0.0) * FRET_SIZE
            }
            x = 320.0
            for (group in close) {
                var gx = x
                for (t in group) {
                    ink.text(font, FRET_SIZE, gx, 300.0 + SPACING + 0.35 * FRET_SIZE, t)
                    gx += widthOf(font, t, FRET_SIZE) + 0.05 * FRET_SIZE
                }
                x += 40
            }
        }
        val tab = PdfTab.read(pdf, Instrument.TETRACHORDO)
        assertEquals(run.map { 3 to it.toInt() } + listOf(2 to 17, 2 to 12, 2 to 10, 2 to 7), tab.notes.map { it.course to it.fret })
        assertTrue(tab.notes.zipWithNext().all { (a, b) -> b.column > a.column })
        assertEquals(listOf("Left out 1 number on the tab lines too big to be frets"), tab.warnings)
    }

    @Test
    fun readsOnlyTheTopPartOfATwoPartScore() {
        // Two made-up parts, each a notation staff over a 4-line tab, in two systems; each system's
        // barline runs down over both parts.
        fun parts(joined: Boolean): List<Sys> {
            val b1 = Sys(60.0, 540.0, 190.0, 4, mapOf(0 to listOf(2 to "0"), 1 to listOf(2 to "2"), 2 to listOf(3 to "3")))
            val b2 = Sys(60.0, 540.0, 430.0, 4, mapOf(0 to listOf(2 to "4"), 1 to listOf(3 to "6")))
            val a1 = Sys(60.0, 540.0, 90.0, 4, mapOf(0 to listOf(0 to "5"), 1 to listOf(0 to "7"), 2 to listOf(1 to "8")), barTo = if (joined) b1.lineY(3) else null)
            val a2 = Sys(60.0, 540.0, 330.0, 4, mapOf(0 to listOf(0 to "10"), 1 to listOf(1 to "12")), barTo = if (joined) b2.lineY(3) else null)
            return listOf(a1, b1, a2, b2)
        }
        val tab = PdfTab.read(tabPdf(listOf(parts(true))).bytes, Instrument.TETRACHORDO)
        assertEquals(listOf(3 to 5, 3 to 7, 2 to 8, 3 to 10, 2 to 12), tab.notes.map { it.course to it.fret })
        assertEquals(listOf("This PDF has 2 tab parts; only the top one was read"), tab.warnings)
        // Without barlines joining them they are four systems of one part, read in order.
        val apart = PdfTab.read(tabPdf(listOf(parts(false))).bytes, Instrument.TETRACHORDO)
        assertEquals(listOf(5, 7, 8, 0, 2, 3, 10, 12, 4, 6), apart.notes.map { it.fret })
        assertEquals(emptyList(), apart.warnings)
    }

    @Test
    fun leavesOutTiedNotes() {
        // A made-up phrase with ties: in brackets (5 then (5)), as a tie curve (7 to 7), and from the
        // end of one system to the start of the next (3 to 3). A bracketed 9 after an 8 is a ghost note.
        val s1 = Sys(
            60.0, 540.0, 90.0, 4,
            mapOf(0 to listOf(1 to "5"), 1 to listOf(1 to "(5)"), 2 to listOf(0 to "7"), 3 to listOf(0 to "7"), 4 to listOf(0 to "8"), 5 to listOf(0 to "(9)"), 6 to listOf(2 to "3")),
            ties = listOf(Tie(2.0, 3.0, 0), Tie(6.0, END, 2)),
        )
        val s2 = Sys(60.0, 540.0, 260.0, 4, mapOf(0 to listOf(2 to "3"), 1 to listOf(2 to "5")), ties = listOf(Tie(START, 0.0, 2)))
        val tab = PdfTab.read(tabPdf(listOf(listOf(s1, s2))).bytes, Instrument.TETRACHORDO)
        assertEquals(listOf(2 to 5, 3 to 7, 3 to 8, 3 to 9, 1 to 3, 1 to 5), tab.notes.map { it.course to it.fret })
        assertEquals(listOf("Left out 3 tied notes (held on, not picked again)", "Kept 1 note in brackets (tied or ghost notes)"), tab.warnings)
    }

    @Test
    fun dynamicsAroundTheStaffAreNotMarks() {
        // A p and an f below the staff (dynamics), and a Guitar Pro style H above it.
        val sys = Sys(
            60.0, 540.0, 90.0, 4,
            mapOf(0 to listOf(0 to "2"), 1 to listOf(0 to "3"), 2 to listOf(0 to "5"), 3 to listOf(0 to "3"), 4 to listOf(0 to "5")),
            listOf(Mark("p", 1.5, BELOW), Mark("f", 2.5, BELOW), Mark("p", 0.5, ABOVE), Mark("H", 3.5, ABOVE)),
        )
        val tab = PdfTab.read(tabPdf(listOf(listOf(sys))).bytes, Instrument.TETRACHORDO)
        assertEquals(listOf(PICK, PICK, PICK, PICK, HAMMER), tab.notes.map { it.articulation })
    }

    @Test
    fun neverThrowsOnDamagedFiles() {
        val bases = listOf(tabPdf(pages).bytes, tabPdf(pages, Style(flip = false, type0 = true, objectStreams = true, form = true, kern = true)).bytes)
        val rnd = java.util.Random(42)
        val start = System.nanoTime()
        var slowest = 0L
        for (i in 0 until 400) {
            val base = bases[i % 2]
            val d = when (i % 5) {
                0 -> base.copyOf(rnd.nextInt(base.size))
                1 -> base.copyOf().also { b -> repeat(1 + rnd.nextInt(40)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() } }
                2 -> { val a = rnd.nextInt(base.size); val n = rnd.nextInt(minOf(2000, base.size - a)); base.copyOf(a) + base.copyOfRange(a + n, base.size) }
                3 -> { val a = rnd.nextInt(base.size); val n = rnd.nextInt(minOf(2000, base.size - a)); base.copyOf(a + n) + base.copyOfRange(a, base.size) }
                else -> ByteArray(rnd.nextInt(5000)).also { rnd.nextBytes(it) }
            }
            val t0 = System.nanoTime()
            PdfTab.read(d, Instrument.TETRACHORDO)
            slowest = maxOf(slowest, System.nanoTime() - t0)
        }
        assertTrue(slowest < 2_000_000_000L, "slowest read ${slowest / 1e6} ms")
        assertTrue(System.nanoTime() - start < 60_000_000_000L)
    }

    @Test
    fun survivesHostileFiles() {
        fun pdf(vararg objects: String, trailer: String = "/Root 1 0 R"): ByteArray {
            val sb = StringBuilder("%PDF-1.7\n")
            val offsets = ArrayList<Int>()
            for ((i, o) in objects.withIndex()) { offsets += sb.length; sb.append("${i + 1} 0 obj\n$o\nendobj\n") }
            val xref = sb.length
            sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
            for (o in offsets) sb.append("%010d 00000 n \n".format(o))
            sb.append("trailer\n<< /Size ${objects.size + 1} $trailer >>\nstartxref\n$xref\n%%EOF\n")
            return sb.toString().toByteArray(Charsets.ISO_8859_1)
        }
        fun onePageWith(content: ByteArray, resources: String = ""): String = String(
            pdf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R$resources >>",
                "<< /Filter /FlateDecode /Length ${content.size} >>\nstream\n" + String(TestPdfWriter.deflate(content), Charsets.ISO_8859_1) + "\nendstream"),
            Charsets.ISO_8859_1,
        )
        val bomb = TestPdfWriter.deflate(ByteArray(40 * 1024 * 1024) { ' '.code.toByte() })
        val huge = "1" + "0".repeat(300)
        val overflow = "$huge 0 0 $huge 0 0 cm $huge 0 0 $huge 0 0 cm 0.5 w 0 0 m 500 0 l S BT /F1 7 Tf (12) Tj ET"
        // Many streams without endstream (or endobj) and with a wrong /Length.
        val unterminated = StringBuilder("%PDF-1.4\n").also { b -> for (i in 1..8_000) b.append("$i 0 obj <</Length 999999>> stream\n").append("x".repeat(40)).append('\n') }
        // An xref stream of 16 M one-byte rows, from a 16 KB file.
        val rows = TestPdfWriter.deflate(ByteArray(16 * 1024 * 1024))
        val xrefRows = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n" +
            "3 0 obj\n<< /Type /XRef /Size 16777216 /W [0 1 0] /Root 1 0 R /Filter /FlateDecode /Length ${rows.size} >>\nstream\n" +
            String(rows, Charsets.ISO_8859_1) + "\nendstream\nendobj\nstartxref\n85\n%%EOF\n"
        val cases = listOf(
            ByteArray(0),
            "%PDF-1.4".toByteArray(),
            "%PDF-1.4\n1 0 obj\n" + "[".repeat(200_000) + "\nendobj\n",
            "%PDF-1.4\n1 0 obj\n" + "<<".repeat(200_000) + "\nendobj\n",
            // Page tree loops, a form that draws itself, references to themselves, a missing font.
            String(pdf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [2 0 R 3 0 R] >>",
                "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /XObject << /X 5 0 R >> >> >>",
                "<< /Length 4 0 R >>\nstream\n/X Do /F9 12 Tf (1) Tj 0 0 m 500 0 l S\nendstream",
                "<< /Subtype /Form /Resources << /XObject << /X 5 0 R >> >> /Length 5 >>\nstream\n/X Do\nendstream"), Charsets.ISO_8859_1),
            // An xref chain that loops, and an object stream that contains itself.
            String(pdf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /ObjStm /N 1 /First 4 /Length 14 >>\nstream\n2 0 2 0 R     \nendstream",
                trailer = "/Root 1 0 R /Prev 9"), Charsets.ISO_8859_1),
            // A compressed stream that inflates to 40 MB.
            String(pdf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] >>",
                "<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>",
                "<< /Filter /FlateDecode /Length ${bomb.size} >>\nstream\n" + String(bomb, Charsets.ISO_8859_1) + "\nendstream"), Charsets.ISO_8859_1),
            // Numbers so big that positions overflow to infinity.
            String(pdf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length ${overflow.length} >>\nstream\n$overflow\nendstream", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"), Charsets.ISO_8859_1),
            // Unsupported filters and encryption handlers, nonsense numbers.
            String(pdf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count -5 >>",
                "<< /Type /Page /MediaBox [0 0 1e999 -4] /Contents 4 0 R >>",
                "<< /Filter /JBIG2Decode /Length 3 >>\nstream\nabc\nendstream", trailer = "/Root 1 0 R /Encrypt << /Filter /Custom >>"), Charsets.ISO_8859_1),
            // A path closed 3 million times, and a million subpaths.
            onePageWith(("0 0 m\n" + "h\n".repeat(3_000_000)).toByteArray()),
            onePageWith("0 0 m\n".repeat(1_000_000).toByteArray()),
            unterminated.toString(),
            xrefRows,
            // Thousands of trailers, each opening a string that runs to the end of the file.
            "%PDF-1.4\n" + "trailer<</A(".repeat(20_000),
            // Digits spread far apart along one row (a text page).
            onePageWith(("BT /F1 1 Tf 0 0 Td [" + "(1)-300000".repeat(150_000) + "] TJ ET").toByteArray(), " /Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Courier >> >> >>"),
        ).map { if (it is String) it.toByteArray(Charsets.ISO_8859_1) else it as ByteArray }
        for (c in cases) {
            val t0 = System.nanoTime()
            val tab = PdfTab.read(c, Instrument.TETRACHORDO)
            assertEquals(emptyList(), tab.notes)
            assertTrue(tab.warnings.isNotEmpty())
            assertTrue(System.nanoTime() - t0 < 3_000_000_000L, "took ${(System.nanoTime() - t0) / 1e6} ms")
        }
        // The spread-out row is cut, not padded with millions of spaces.
        assertTrue(PdfTab.text(PdfTab.readPages(cases.last(), LinkedHashSet())).length < 2_000)
    }

    // ---- The score's form ----

    /** A system at [top] of [n] notes on the top line, the frets counting up from [first]; with [bars] and [form]. */
    private fun formSys(top: Double, n: Int, bars: List<Double>, form: List<Form>, first: Int = 0) =
        Sys(60.0, 540.0, top, 4, (0 until n).associateWith { listOf(0 to "${(first + it) % 10}") }, bars = bars, form = form)

    private fun read(pages: List<List<Sys>>, style: Style = Style()) = PdfTab.read(tabPdf(pages, style).bytes, Instrument.TETRACHORDO)

    @Test
    fun readsRepeatBarlinesOverTwoSystems() {
        // |: after the 2nd note, :| after the 4th note of the next system, then a double barline: the end of a part.
        for ((curves, tabDots, style) in listOf(Triple(false, true, Style()), Triple(true, true, Style(flip = false)), Triple(false, false, Style()))) {
            val a = formSys(90.0, 8, listOf(3.5), listOf(Repeat(1.5, start = true, end = false, curves = curves, tabDots = tabDots)))
            val b = formSys(260.0, 6, emptyList(), listOf(Repeat(3.5, start = false, end = true, curves = curves, tabDots = tabDots), DoubleBar(4.5)), 8)
            val tab = read(listOf(listOf(a, b)), style)
            val what = "curves $curves, dots on the tab staff $tabDots"
            assertEquals((0 until 14).map { it % 10 }, tab.notes.map { it.fret }, what)
            assertEquals(List(8) { 0 } + List(6) { 1 }, tab.notes.map { it.system }, what)
            // Bars counted on through the systems: the first system's are 0 to 2 (the repeat sign and the barline are
            // barlines too) and its end barline is the last; the next system's start at 4.
            assertEquals(listOf(0, 0, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 5, 6), tab.notes.map { it.bar }, what)
            assertEquals(listOf(TabSection(0, 2), TabSection(2, 12, 2), TabSection(12, 13), TabSection(13, 14)), tab.sections, what)
            assertEquals(emptyList(), tab.warnings, what)
        }
    }

    @Test
    fun readsFirstAndSecondTimeEndings() {
        // |: 0 1 | 2 3 4 5 | [1. 6 7 :| [2. 8 9 ] : played 0-7, 2-5, 8-9.
        val sys = formSys(
            90.0, 10, listOf(5.5, 9.5),
            listOf(Repeat(1.5, start = true, end = false), Ending(5.5, 7.5, "1."), Repeat(7.5, start = false, end = true), Ending(7.5, 9.5, "2.")),
        )
        val tab = read(listOf(listOf(sys)))
        assertEquals(listOf(TabSection(0, 8), TabSection(2, 6), TabSection(8, 10)), tab.sections)
        assertEquals(emptyList(), tab.warnings)
        // A 1st ending played twice ("1., 2."), the 3rd ending after it: the :| is taken twice.
        val three = formSys(
            90.0, 10, listOf(5.5, 9.5),
            listOf(Repeat(1.5, start = true, end = false), Ending(5.5, 7.5, "1., 2."), Repeat(7.5, start = false, end = true), Ending(7.5, 9.5, "3.")),
        )
        assertEquals(listOf(TabSection(0, 8), TabSection(2, 8), TabSection(2, 6), TabSection(8, 10)), read(listOf(listOf(three))).sections)
    }

    @Test
    fun readsDalSegnoAlCoda() {
        // 0 1 2 | segno 3 4 5 To Coda | 6 7 8 9 D.S. al Coda || ; next system: coda 10 ... 15. Played 0-9, 3-5, 10-15.
        // (Written with or without the dots.)
        for (ds in listOf("D.S. al Coda", "DS al Coda")) {
            val a = formSys(
                90.0, 10, listOf(2.5, 5.5),
                listOf(Sign(2.8, 0x25), Words(5.4, "To Coda"), Words(9.3, ds), DoubleBar(9.5)),
            )
            val b = formSys(260.0, 6, emptyList(), listOf(Sign(-2.0, 0xDE)), 10)
            val tab = read(listOf(listOf(a, b)))
            assertEquals(listOf(TabSection(0, 10), TabSection(3, 6), TabSection(10, 16)), tab.sections, ds)
            assertEquals(emptyList(), tab.warnings, ds)
        }
    }

    @Test
    fun readsDaCapoAlFineAndACount() {
        // 0 1 2 3 :| x3 | 4 5 6 7 Fine | 8 9 0 1 D.C. al Fine (at the very end): played 0-3 three times, 4-11, 0-7.
        for (dc in listOf("D.C. al Fine", "DC al Fine")) {
            val sys = formSys(
                90.0, 12, listOf(7.5),
                listOf(Repeat(3.5, start = false, end = true), Words(3.4, "x3"), Words(7.4, "Fine"), Words(11.3, dc), DoubleBar(11.5)),
            )
            val tab = read(listOf(listOf(sys)))
            assertEquals(listOf(TabSection(0, 4, 3), TabSection(4, 12), TabSection(0, 8)), tab.sections, dc)
            assertEquals(emptyList(), tab.warnings, dc)
        }
        // A :| at the very end of the tab is taken too.
        val last = formSys(90.0, 6, listOf(1.5), listOf(Repeat(1.5, start = true, end = false), Repeat(5.5, start = false, end = true)))
        assertEquals(listOf(TabSection(0, 2), TabSection(2, 6, 2)), read(listOf(listOf(last))).sections)
    }

    @Test
    fun signsThatCantBeFollowedAreLeftOutWithAWarning() {
        // |: with no :| after it, a D.S. with no segno to go back to, a count with no repeat sign by it.
        val sys = formSys(90.0, 10, listOf(4.5), listOf(Repeat(1.5, start = true, end = false), Words(4.4, "2x"), Words(9.3, "D.S."), DoubleBar(9.5)))
        val tab = read(listOf(listOf(sys)))
        assertEquals((0 until 10).toList(), tab.notes.map { it.fret })
        assertEquals(listOf(TabSection(0, 10)), tab.sections)
        assertEquals(
            setOf(
                "Left out \"2x\" on page 1: no repeat sign (:|) by it to say what it repeats",
                "The repeat sign |: on page 1 has no :| after it: read as played once",
                "Left out the D.S. on page 1: no segno sign found to go back to",
            ),
            tab.warnings.toSet(),
        )
        // A bracket over the staff without numbers (a tuplet's, a text box's) isn't an ending.
        val bracket = formSys(90.0, 6, emptyList(), listOf(Ending(0.5, 3.5, "")))
        val plain = read(listOf(listOf(bracket)))
        assertEquals(listOf(TabSection(0, 6)), plain.sections)
        assertEquals(emptyList(), plain.warnings)
    }

    @Test
    fun readsPagesClutteredWithBracketLinesQuickly() {
        // Over each system, an ending's bracket line (with its hook and "1.") drawn 1990 times, reaching the system's end:
        // read as one ending, without comparing every copy with every other, or looking at the next system each time.
        val copies = Raw(0.0) { ink, s ->
            val y = s.top - 14
            repeat(1_990) { ink.line(s.x0 + 1, s.x1 - 1, y, 0.6) }
            ink.vline(s.x0 + 1, y, y + 8, 0.6)
        }
        val page = listOf(90.0, 260.0, 430.0, 600.0).map { formSys(it, 10, listOf(4.5), listOf(copies, Words(-1.5, "1."))) }
        val bytes = tabPdf(listOf(page, page)).bytes
        val t0 = System.nanoTime()
        val tab = PdfTab.read(bytes, Instrument.TETRACHORDO)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(80, tab.notes.size)
        assertEquals(listOf(TabSection(0, 80)), tab.sections)
        assertTrue(tab.warnings.any { "1st/2nd-time ending" in it }, "${tab.warnings}")
        assertTrue(ms < 3000, "two cluttered pages took $ms ms")
    }

    @Test
    fun readsTwentyPagesQuickly() {
        val many = tabPdf(List(20) { pages[it % 2] }).bytes
        PdfTab.read(many, Instrument.TETRACHORDO) // warm up
        val t0 = System.nanoTime()
        val tab = PdfTab.read(many, Instrument.TETRACHORDO)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(10 * expected.size, tab.notes.size)
        assertTrue(ms < 1000, "20 pages took $ms ms")
    }

    companion object {
        private const val H = 842.0
        private const val STEP = 20.0
        private const val SPACING = 6.0
        private const val FRET_SIZE = 7.0
        private const val ABOVE = -1
        private const val BELOW = -2
        private const val START = -1.0
        private const val END = -2.0
        private const val DAMAGED = "Parts of this PDF are damaged; it was read as well as possible"
    }
}
