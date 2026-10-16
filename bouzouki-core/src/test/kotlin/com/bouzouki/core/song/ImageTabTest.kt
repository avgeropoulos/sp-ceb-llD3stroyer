package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ImageTab on pictures of tabs drawn here with java.awt: made-up tabs (random frets, mostly on the top two
 * courses, with chords, barlines, repeat signs, triplet brackets, stems and beams), in several fonts and sizes,
 * blurred, JPEG-compressed, a little turned, or inside a screenshot with the phone's bars around it.
 */
class ImageTabTest {
    // ---- Made-up tabs ----

    /** A column of a system: one fret or a chord (course to fret), or a barline ([bar]: 1 thin, 2 |:, 3 :|). */
    private class Col(val frets: List<Pair<Int, Int>> = emptyList(), val bar: Int = 0)

    /** A system: its columns, and triplet groups (column index ranges, notes only). */
    private class System(val cols: List<Col>, val triplets: List<IntRange> = emptyList())

    /** Made-up systems for a bouzouki with [courses] courses: [bars] bars of 4 notes each, from [seed]. */
    private fun tab(seed: Int, systems: Int, bars: Int, courses: Int, repeat: Boolean = false, triplets: Boolean = true): List<System> {
        val rnd = Random(seed)
        val top = courses - 1
        return (0 until systems).map { k ->
            val cols = ArrayList<Col>()
            val trip = ArrayList<IntRange>()
            if (repeat && k == 1) cols += Col(bar = 2)
            for (b in 0 until bars) {
                if (b > 0) cols += Col(bar = 1)
                for (n in 0 until 4) {
                    val course = if (rnd.nextInt(5) == 0) top - 1 else top
                    val fret = if (rnd.nextInt(4) == 0) rnd.nextInt(10, 20) else rnd.nextInt(0, 10)
                    val chord = rnd.nextInt(12) == 0 && course == top
                    cols += Col(if (chord) listOf(top to fret, top - 1 to rnd.nextInt(0, 8)) else listOf(course to fret))
                }
                val notes = cols.count { it.bar == 0 }
                if (triplets && b == 1) trip += (notes - 3) until notes
            }
            if (repeat && k == 2) cols += Col(bar = 3)
            System(cols, trip)
        }
    }

    /** The notes [systems] hold, as the reader gives them: (system, course, fret, stack), in order. */
    private fun expected(systems: List<System>, inst: Instrument): List<String> = systems.flatMapIndexed { k, s ->
        s.cols.filter { it.bar == 0 }.map { c ->
            val sorted = c.frets.sortedByDescending { inst.midiAt(it.first, it.second) }
            "$k:${sorted[0].first}/${sorted[0].second}" + sorted.drop(1).joinToString("") { "+${it.first}/${it.second}" }
        }
    }

    private fun got(tab: ParsedTab): List<String> = tab.notes.map { n ->
        "${n.system}:${n.course}/${n.fret}" + n.stack.joinToString("") { "+${it.first}/${it.second}" }
    }

    /** Notes in common, in order (longest common subsequence), over the expected count. */
    private fun accuracy(want: List<String>, have: List<String>): Double {
        val d = Array(want.size + 1) { IntArray(have.size + 1) }
        for (i in 1..want.size) for (j in 1..have.size) {
            d[i][j] = if (want[i - 1] == have[j - 1]) d[i - 1][j - 1] + 1 else max(d[i - 1][j], d[i][j - 1])
        }
        return d[want.size][have.size].toDouble() / want.size
    }

    // ---- Drawing ----

    /**
     * How a page is drawn: the frets' [font] with digits [digit] pixels tall, staff spacing [spacing] (a share
     * of the digit height), a notation staff over each tab staff, stems and beams over the tab.
     */
    private class Style(
        val font: Font, val digit: Int, val spacing: Double = 1.3, val notation: Boolean = false, val stems: Boolean = false,
        val lineThickness: Float = 1f,
    )

    /** Draws [systems] on a white page [width] pixels wide in [style]. */
    private fun draw(systems: List<System>, courses: Int, style: Style, width: Int = 1100): BufferedImage {
        val dh = style.digit
        val sp = (style.spacing * dh).roundToInt().toDouble()
        val noteStep = 2.7 * dh
        val systemHeight = (courses - 1) * sp + (if (style.notation) 9 * sp else 0.0) + 7 * sp
        val height = (60 + systems.size * systemHeight + 40).roundToInt()
        val img = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.color = Color.BLACK
        g.font = Font(Font.SERIF, Font.BOLD, 26)
        g.drawString("A made-up tune", width / 3f, 34f)
        val digitFont = fontFor(g, style.font, dh)
        var y = 60.0 + (if (style.notation) 7 * sp else 3 * sp)
        val left = 40.0
        val right = width - 40.0
        for (s in systems) {
            if (style.notation) {
                // A notation staff over the tab, with a few noteheads.
                val ny = y - 7 * sp
                val nsp = 0.75 * sp
                g.stroke = BasicStroke(style.lineThickness)
                for (i in 0 until 5) line(g, left, ny + i * nsp, right, ny + i * nsp)
                var x = left + 4 * sp
                while (x < right - sp) { g.fillOval((x - 0.6 * nsp).toInt(), (ny + 2 * nsp - 0.5 * nsp).toInt(), (1.2 * nsp).toInt(), nsp.toInt()); x += noteStep * 1.5 }
            }
            val ys = DoubleArray(courses) { y + it * sp }
            g.stroke = BasicStroke(style.lineThickness)
            for (ly in ys) line(g, left, ly, right, ly)
            line(g, left, ys.first(), left, ys.last())
            line(g, right, ys.first(), right, ys.last())
            // The TAB clef.
            g.font = Font(Font.SANS_SERIF, Font.BOLD, (sp * 0.95).roundToInt())
            for ((i, c) in "TAB".withIndex()) g.drawString("$c", (left + 0.6 * sp).toFloat(), (ys.first() + (i + 0.85) * (ys.last() - ys.first()) / 3).toFloat())
            g.font = digitFont
            var x = left + 4.2 * sp
            val noteXs = ArrayList<Double>()
            for (c in s.cols) {
                when (c.bar) {
                    1 -> { g.stroke = BasicStroke(style.lineThickness); line(g, x - 0.4 * dh, ys.first(), x - 0.4 * dh, ys.last()); x += 0.7 * dh; continue }
                    2, 3 -> {
                        val thick = max(3.0, 0.35 * sp)
                        val xs = if (c.bar == 2) doubleArrayOf(x, x + thick + 2) else doubleArrayOf(x + thick + 2, x)
                        g.fill(java.awt.geom.Rectangle2D.Double(xs[0] - thick / 2, ys.first(), thick, ys.last() - ys.first()))
                        g.stroke = BasicStroke(style.lineThickness)
                        line(g, xs[1], ys.first(), xs[1], ys.last())
                        val dotX = if (c.bar == 2) xs[1] + 0.45 * sp else xs[1] - 0.45 * sp
                        val r = 0.18 * sp
                        val mid = (ys.first() + ys.last()) / 2
                        // In the spaces each side of the middle one (4 lines) or of the middle line (3).
                        val off = if (courses == 4) sp else 0.5 * sp
                        for (dy in doubleArrayOf(-off, off)) g.fill(java.awt.geom.Ellipse2D.Double(dotX - r, mid + dy - r, 2 * r, 2 * r))
                        x += thick + 0.9 * sp + 0.8 * dh
                        continue
                    }
                }
                noteXs += x
                for ((course, fret) in c.frets) fret(g, "$fret", x, ys[courses - 1 - course], dh)
                if (style.stems) {
                    val topNote = c.frets.minOf { courses - 1 - it.first }
                    g.stroke = BasicStroke(max(1f, dh / 10f))
                    line(g, x, ys[topNote] - 0.62 * dh, x, ys.first() - 2.2 * sp)
                }
                x += noteStep
            }
            if (style.stems) {
                // Beams over groups of four notes.
                for (k in 0 until noteXs.size / 4) {
                    val a = noteXs[4 * k]
                    val b = noteXs[4 * k + 3]
                    g.fill(java.awt.geom.Rectangle2D.Double(a, ys.first() - 2.2 * sp - 0.35 * sp, b - a + 1, 0.35 * sp))
                }
            }
            for (t in s.triplets) {
                // A bracket under the staff with a 3 in a gap in it.
                val a = noteXs[t.first] - 0.5 * dh
                val b = noteXs[t.last] + 0.5 * dh
                val by = ys.last() + 1.3 * sp
                val mid = (a + b) / 2
                g.stroke = BasicStroke(max(1f, dh / 12f))
                line(g, a, by - 0.5 * sp, a, by); line(g, a, by, mid - 0.7 * dh, by)
                line(g, mid + 0.7 * dh, by, b, by); line(g, b, by, b, by - 0.5 * sp)
                g.font = Font(Font.SERIF, Font.BOLD or Font.ITALIC, (dh * 1.45).roundToInt())
                val w3 = g.fontMetrics.stringWidth("3")
                g.drawString("3", (mid - w3 / 2.0).toFloat(), (by + 0.5 * dh).toFloat())
                g.font = digitFont
            }
            y += systemHeight
        }
        g.dispose()
        return img
    }

    private fun line(g: Graphics2D, x0: Double, y0: Double, x1: Double, y1: Double) = g.draw(java.awt.geom.Line2D.Double(x0, y0, x1, y1))

    /** [font] sized so its digits are [dh] pixels tall. */
    private fun fontFor(g: Graphics2D, font: Font, dh: Int): Font {
        var f = font.deriveFont(dh * 1.4f)
        val h = f.createGlyphVector(g.fontRenderContext, "0123456789").visualBounds.height
        f = f.deriveFont((dh * 1.4f * dh / h).toFloat())
        return f
    }

    /** Draws fret [text] centred on the line at [y], clearing the line behind it as notation programs do. */
    private fun fret(g: Graphics2D, text: String, x: Double, y: Double, dh: Int) {
        val gv = g.font.createGlyphVector(g.fontRenderContext, text)
        val b = gv.visualBounds
        val left = x - b.width / 2 - b.x
        val base = y + b.height / 2 - (b.y + b.height)
        val c = g.color
        g.color = Color.WHITE
        g.fill(java.awt.geom.Rectangle2D.Double(x - b.width / 2 - 0.15 * dh, y - b.height / 2 - 1, b.width + 0.3 * dh, b.height + 2))
        g.color = c
        g.drawGlyphVector(gv, left.toFloat(), base.toFloat())
    }

    /** Digits drawn in java.awt's fonts (sans-serif and plain serif), as the app draws them in the phone's. */
    private fun awtDigits(height: Int): List<DigitGlyph> {
        val out = ArrayList<DigitGlyph>()
        for (font in listOf(Font(Font.SANS_SERIF, Font.PLAIN, 10), Font(Font.SANS_SERIF, Font.BOLD, 10), Font(Font.SERIF, Font.PLAIN, 10))) {
            val style = "${font.family} ${font.style}"
            val probe = BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY).createGraphics()
            val f = fontFor(probe, font, height)
            probe.dispose()
            for (d in 0..9) {
                val w = height * 2
                val h = height * 2
                val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.color = Color.WHITE
                g.font = f
                g.drawString("$d", height / 3f, height * 1.5f)
                g.dispose()
                out += DigitGlyph(d, w, h, ByteArray(w * h) { img.raster.getSample(it % w, it / w, 0).toByte() }, style)
            }
        }
        return out
    }

    // ---- Degrading ----

    /** [img]'s gray levels as drawn (a gray image's samples as they are: its RGB view would lighten them). */
    private fun gray(img: BufferedImage): GrayImage {
        val w = img.width
        val h = img.height
        if (img.type == BufferedImage.TYPE_BYTE_GRAY) return GrayImage(ByteArray(w * h) { img.raster.getSample(it % w, it / w, 0).toByte() }, w, h)
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        return GrayImage(ByteArray(w * h) { val c = px[it]; ((299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000).toByte() }, w, h)
    }

    private fun blur(img: BufferedImage): BufferedImage {
        val w = img.width
        val h = img.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { s += img.raster.getSample(xx, yy, 0) * (if (dx == 0 && dy == 0) 4 else 1); n += if (dx == 0 && dy == 0) 4 else 1 }
            }
            out.raster.setSample(x, y, 0, s / n)
        }
        return out
    }

    private fun jpeg(img: BufferedImage, quality: Float): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        writer.output = ImageIO.createImageOutputStream(out)
        val p = writer.defaultWriteParam
        p.compressionMode = ImageWriteParam.MODE_EXPLICIT
        p.compressionQuality = quality
        writer.write(null, IIOImage(img, null, null), p)
        writer.dispose()
        return out.toByteArray()
    }

    private fun turned(img: BufferedImage, degrees: Double): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_BYTE_GRAY)
        val g = out.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, out.width, out.height)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, AffineTransform.getRotateInstance(Math.toRadians(degrees), img.width / 2.0, img.height / 2.0), null)
        g.dispose()
        return out
    }

    /** [img] as a phone screenshot: a dark status bar over it, a toolbar with buttons under it, the page a little narrower. */
    private fun screenshot(img: BufferedImage): BufferedImage {
        val w = img.width
        val out = BufferedImage(w, img.height + 180, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(250, 250, 250)
        g.fillRect(0, 0, w, out.height)
        g.color = Color(33, 33, 33)
        g.fillRect(0, 0, w, 70)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 28)
        g.drawString("12:45", 30f, 46f)
        g.drawString("87%", w - 110f, 46f)
        g.drawImage(img, 0, 90, null)
        g.color = Color(60, 90, 160)
        g.fillRect(0, out.height - 80, w, 80)
        g.color = Color.WHITE
        for (k in 0 until 4) g.fillOval(80 + k * (w - 160) / 3 - 18, out.height - 58, 36, 36)
        g.color = Color(170, 170, 170)
        g.fillRoundRect(w - 14, 300, 8, 200, 8, 8)
        g.dispose()
        return out
    }

    // ---- Tests ----

    @Test
    fun readsACleanTetrachordoTab() {
        val systems = tab(seed = 1, systems = 3, bars = 4, courses = 4, repeat = true)
        val img = draw(systems, 4, Style(Font(Font.SANS_SERIF, Font.BOLD, 10), digit = 14, stems = true))
        val r = ImageTab.read(listOf(gray(img)), Instrument.TETRACHORDO, ::awtDigits)
        val want = expected(systems, Instrument.TETRACHORDO)
        // Every note (a pixel of font difference between machines may cost one at most).
        assertTrue(accuracy(want, got(r.tab)) >= 0.97, "${got(r.tab)} for $want")
        assertEquals(want.size, r.tab.notes.size)
        assertEquals(Instrument.TETRACHORDO, r.tab.instrument)
        assertEquals(3, r.systems)
        assertEquals(ImageTab.PICTURE, r.tab.warnings.first())
        assertEquals(ImageTab.unsureIn(r.tab), r.unsure)
        assertTrue(r.confidence >= 0.9, "confidence ${r.confidence}")
        // One triplet a system, over the bar's last three notes.
        assertEquals(3, r.triplets.size)
        for (t in r.triplets) assertEquals(3, t.last - t.first + 1)
        // |: at the start of the second system, :| at the end of the third: that stretch twice.
        val firstOfSecond = r.tab.notes.indexOfFirst { it.system == 1 }
        assertEquals(listOf(TabSection(0, firstOfSecond), TabSection(firstOfSecond, r.tab.notes.size, 2)), r.tab.sections)
        // Barlines start bars: 4 bars a system.
        assertEquals(12, r.tab.notes.map { it.bar }.distinct().size)
    }

    @Test
    fun readsATrichordoScreenshotWithNotationAndPhoneBars() {
        val systems = tab(seed = 2, systems = 2, bars = 4, courses = 3)
        val page = draw(systems, 3, Style(Font(Font.DIALOG, Font.PLAIN, 10), digit = 11, notation = true, stems = true))
        val r = ImageTab.read(listOf(gray(screenshot(page))), Instrument.TETRACHORDO, ::awtDigits)
        assertEquals(Instrument.TRICHORDO, r.tab.instrument)
        assertTrue(r.tab.warnings.any { "trichordo" in it })
        val acc = accuracy(expected(systems, Instrument.TRICHORDO), got(r.tab))
        assertTrue(acc >= 0.97, "accuracy $acc: ${got(r.tab)} for ${expected(systems, Instrument.TRICHORDO)}")
        assertEquals(2, r.systems)
    }

    @Test
    fun readsASerifTabBlurredCompressedAndTurned() {
        // Drawn in a serif font, read with sans-serif digits to compare with: the picture's own font settles it.
        val systems = tab(seed = 3, systems = 3, bars = 4, courses = 4)
        var img = draw(systems, 4, Style(Font(Font.SERIF, Font.BOLD, 10), digit = 20, lineThickness = 2f), width = 1500)
        img = blur(turned(img, 0.8))
        val decoded = ImageIO.read(ByteArrayInputStream(jpeg(img, 0.55f)))
        val r = ImageTab.read(listOf(gray(decoded)), Instrument.TETRACHORDO, ::awtDigits)
        val acc = accuracy(expected(systems, Instrument.TETRACHORDO), got(r.tab))
        assertTrue(acc >= 0.95, "accuracy $acc: ${got(r.tab)} for ${expected(systems, Instrument.TETRACHORDO)}")
    }

    @Test
    fun theBuiltInDigitsReadAPlainSansSerifTab() {
        val systems = tab(seed = 4, systems = 2, bars = 4, courses = 4)
        val img = draw(systems, 4, Style(Font(Font.SANS_SERIF, Font.BOLD, 10), digit = 16))
        val r = ImageTab.read(listOf(gray(img)), Instrument.TETRACHORDO)
        val acc = accuracy(expected(systems, Instrument.TETRACHORDO), got(r.tab))
        assertTrue(acc >= 0.9, "accuracy $acc: ${got(r.tab)} for ${expected(systems, Instrument.TETRACHORDO)}")
    }

    @Test
    fun readsSmallDigitsInSeveralFonts() {
        val fonts = listOf(
            Font(Font.SANS_SERIF, Font.PLAIN, 10) to 10, Font(Font.SANS_SERIF, Font.BOLD, 10) to 10, Font(Font.SERIF, Font.PLAIN, 10) to 10,
            Font(Font.DIALOG, Font.BOLD, 10) to 13, Font(Font.SERIF, Font.BOLD, 10) to 12,
        )
        for ((i, f) in fonts.withIndex()) {
            val (font, size) = f
            val systems = tab(seed = 10 + i, systems = 2, bars = 4, courses = 4, triplets = false)
            val img = draw(systems, 4, Style(font, digit = size))
            val r = ImageTab.read(listOf(gray(img)), Instrument.TETRACHORDO, ::awtDigits)
            val acc = accuracy(expected(systems, Instrument.TETRACHORDO), got(r.tab))
            assertTrue(acc >= 0.95, "${font.family}: accuracy $acc: ${got(r.tab)} for ${expected(systems, Instrument.TETRACHORDO)}")
        }
    }

    @Test
    fun pagesGoInOrder() {
        val a = tab(seed = 20, systems = 1, bars = 3, courses = 4, triplets = false)
        val b = tab(seed = 21, systems = 2, bars = 3, courses = 4, triplets = false)
        val style = Style(Font(Font.SANS_SERIF, Font.BOLD, 10), digit = 13)
        val r = ImageTab.read(listOf(gray(draw(a, 4, style)), gray(draw(b, 4, style))), Instrument.TETRACHORDO, ::awtDigits)
        assertTrue(accuracy(expected(a + b, Instrument.TETRACHORDO), got(r.tab)) >= 0.97, "${got(r.tab)}")
        assertEquals(listOf(0, 1, 2), r.tab.notes.map { it.system }.distinct())
        // Columns go on across the systems and pages.
        assertTrue(r.tab.notes.zipWithNext().all { (p, q) -> q.column > p.column })
    }

    @Test
    fun aPictureWithoutTabSaysSo() {
        val img = BufferedImage(800, 600, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 800, 600)
        g.color = Color.BLACK
        g.font = Font(Font.SERIF, Font.PLAIN, 22)
        for (k in 0 until 12) g.drawString("Some words of a made-up song, line ${k + 1} 2 3", 40f, 60f + 40 * k)
        g.dispose()
        val r = ImageTab.read(listOf(gray(img)), Instrument.TETRACHORDO, ::awtDigits)
        assertTrue(r.tab.notes.isEmpty())
        assertTrue(ImageTab.NO_STAFFS in r.tab.warnings)
    }

    @Test
    fun aNotationStaffAloneIsNotATab() {
        val img = BufferedImage(900, 300, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 900, 300)
        g.color = Color.BLACK
        for (i in 0 until 5) g.drawLine(40, 100 + 10 * i, 860, 100 + 10 * i)
        for (k in 0 until 20) g.fillOval(80 + 38 * k, 96 + (k % 5) * 5, 12, 9)
        g.dispose()
        val r = ImageTab.read(listOf(gray(img)), Instrument.TETRACHORDO)
        assertTrue(r.tab.notes.isEmpty())
    }

    // ---- Pictures in PDFs ----

    @Test
    fun aPictureWrappedInAPdfComesBack() {
        val systems = tab(seed = 30, systems = 2, bars = 3, courses = 4)
        val img = gray(draw(systems, 4, Style(Font(Font.SANS_SERIF, Font.BOLD, 10), digit = 13)))
        val pdf = PdfPictures.wrap(img.gray, img.width, img.height)
        // No text in it: PdfTab finds no numbers, and says so; the page's picture is there to read.
        val text = PdfTab.read(pdf, Instrument.TETRACHORDO)
        assertTrue(text.notes.isEmpty())
        assertTrue(PdfTab.NO_NUMBERS in text.warnings)
        assertTrue(PdfTab.PICTURES in text.warnings)
        val pics = PdfTab.pageImages(pdf)
        assertEquals(1, pics.size)
        val p = pics[0]
        assertEquals(PdfPicture.Kind.SAMPLES, p.kind)
        assertEquals(img.width, p.width)
        assertEquals(img.height, p.height)
        assertEquals(0, p.quarterTurns)
        val back = assertNotNull(p.gray())
        assertTrue(back.contentEquals(img.gray.copyOf(img.width * img.height)))
        val r = ImageTab.read(back, p.width, p.height, Instrument.TETRACHORDO, ::awtDigits)
        assertTrue(accuracy(expected(systems, Instrument.TETRACHORDO), got(r.tab)) >= 0.97, "${got(r.tab)}")
    }

    @Test
    fun jpegPagesAreHandedOverAsJpegFiles() {
        val pages = (0 until 2).map { k ->
            val img = draw(tab(seed = 40 + k, systems = 1, bars = 3, courses = 4), 4, Style(Font(Font.SANS_SERIF, Font.BOLD, 10), digit = 13))
            Triple(img.width, img.height, jpeg(img, 0.8f))
        }
        val w = TestPdfWriter()
        val pagesObj = w.reserve()
        val kids = pages.mapIndexed { k, (pw, ph, data) ->
            val image = w.stream("/Type /XObject /Subtype /Image /Width $pw /Height $ph /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /DCTDecode", data, compress = false)
            // The second page draws its picture turned a quarter clockwise (and its page box sideways).
            val content = if (k == 0) "q $pw 0 0 $ph 0 0 cm /Im0 Do Q" else "q 0 -$pw $ph 0 0 $pw cm /Im0 Do Q"
            val c = w.stream("", content.toByteArray(Charsets.ISO_8859_1))
            val box = if (k == 0) "0 0 $pw $ph" else "0 0 $ph $pw"
            w.add("<< /Type /Page /Parent $pagesObj 0 R /MediaBox [$box] /Resources << /XObject << /Im0 $image 0 R >> >> /Contents $c 0 R >>")
        }
        w.set(pagesObj, "<< /Type /Pages /Kids [${kids.joinToString(" ") { "$it 0 R" }}] /Count ${kids.size} >>")
        val pdf = w.build(w.add("<< /Type /Catalog /Pages $pagesObj 0 R >>"))
        val pics = PdfTab.pageImages(pdf)
        assertEquals(2, pics.size)
        for ((k, p) in pics.withIndex()) {
            assertEquals(PdfPicture.Kind.JPEG, p.kind)
            assertTrue(p.bytes.contentEquals(pages[k].third))
            assertNull(p.gray())
        }
        assertEquals(0, pics[0].quarterTurns)
        assertEquals(1, pics[1].quarterTurns)
    }

    @Test
    fun pdfPicturesReadTheirColourSpaces() {
        // A 2 x 2 RGB picture and a 1-bit mask, as raw samples.
        val w = TestPdfWriter()
        val pagesObj = w.reserve()
        val big = 400
        val rgb = ByteArray(big * big * 3) { if (it % 3 == 0) 255.toByte() else 0 } // pure red
        val image = w.stream("/Type /XObject /Subtype /Image /Width $big /Height $big /ColorSpace /DeviceRGB /BitsPerComponent 8", rgb)
        val c = w.stream("", "q 300 0 0 300 0 0 cm /Im0 Do Q".toByteArray(Charsets.ISO_8859_1))
        val page = w.add("<< /Type /Page /Parent $pagesObj 0 R /MediaBox [0 0 300 300] /Resources << /XObject << /Im0 $image 0 R >> >> /Contents $c 0 R >>")
        w.set(pagesObj, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
        val pdf = w.build(w.add("<< /Type /Catalog /Pages $pagesObj 0 R >>"))
        val p = PdfTab.pageImages(pdf).single()
        val g = assertNotNull(p.gray())
        assertEquals((299 * 255) / 1000, g[0].toInt() and 0xFF)
        // A picture too small to be a page of tab is left out.
        val w2 = TestPdfWriter()
        val p2 = w2.reserve()
        val small = w2.stream("/Type /XObject /Subtype /Image /Width 20 /Height 20 /ColorSpace /DeviceGray /BitsPerComponent 8", ByteArray(400))
        val c2 = w2.stream("", "q 300 0 0 300 0 0 cm /Im0 Do Q".toByteArray(Charsets.ISO_8859_1))
        val page2 = w2.add("<< /Type /Page /Parent $p2 0 R /MediaBox [0 0 300 300] /Resources << /XObject << /Im0 $small 0 R >> >> /Contents $c2 0 R >>")
        w2.set(p2, "<< /Type /Pages /Kids [$page2 0 R] /Count 1 >>")
        assertTrue(PdfTab.pageImages(w2.build(w2.add("<< /Type /Catalog /Pages $p2 0 R >>"))).isEmpty())
        // Not a PDF at all.
        assertTrue(PdfTab.pageImages("hello".toByteArray()).isEmpty())
    }
}
