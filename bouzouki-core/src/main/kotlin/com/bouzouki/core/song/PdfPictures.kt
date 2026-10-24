package com.bouzouki.core.song

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.max

/**
 * The picture a PDF page is made of (a scan, or a tab saved as an image): the [page] it's on (from 0), how its
 * data is coded ([kind]), and its size in pixels. A [Kind.JPEG] picture's [bytes] are a JPEG file, for the
 * phone to decode; a [Kind.SAMPLES] picture's are its raw samples ([components] of [bits] each, rows padded to
 * whole bytes), which [gray] turns into a grayscale image. [quarterTurns] is how far to turn the decoded
 * picture clockwise to see it as the page shows it.
 */
class PdfPicture internal constructor(
    val page: Int,
    val kind: Kind,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val components: Int,
    val bits: Int,
    val quarterTurns: Int,
    private val inverted: Boolean,
    private val palette: ByteArray?,
    private val paletteComponents: Int,
    private val mask: Boolean,
) {
    enum class Kind { JPEG, SAMPLES, JPEG2000, FAX, JBIG2 }

    /** A [Kind.SAMPLES] picture as gray levels (0 black to 255 white, row by row), or null if it can't be read. */
    fun gray(): ByteArray? {
        if (kind != Kind.SAMPLES || width <= 0 || height <= 0) return null
        val comps = if (palette != null || mask) 1 else components
        if (comps !in 1..4 || bits !in intArrayOf(1, 2, 4, 8, 16)) return null
        val rowBytes = (width.toLong() * comps * bits + 7) / 8
        if (rowBytes * height > bytes.size + rowBytes) return null // a little short is fine (truncated data reads as white)
        val out = ByteArray(width * height)
        val max = (1 shl minOf(bits, 8)) - 1
        fun sample(row: Int, i: Int): Int {
            val at = row * rowBytes
            return when (bits) {
                8 -> byteAt(at + i)
                16 -> byteAt(at + 2L * i)
                else -> {
                    val bit = i.toLong() * bits
                    val b = byteAt(at + bit / 8)
                    (b shr (8 - bits - (bit % 8).toInt())) and max
                }
            }
        }
        for (y in 0 until height) for (x in 0 until width) {
            var v: Int
            if (palette != null) {
                val idx = sample(y, x)
                val pc = paletteComponents
                val base = idx * pc
                v = if (base + pc > palette.size) 255 else toGray(pc) { palette[base + it].toInt() and 0xFF }
            } else if (mask) {
                // An image mask paints where its sample is 0 (or 1, with Decode [1 0]).
                val s = sample(y, x)
                v = if ((s == 0) != inverted) 0 else 255
            } else {
                v = toGray(comps) { c -> sample(y, x * comps + c) * 255 / max }
                if (inverted) v = 255 - v
            }
            out[y * width + x] = v.toByte()
        }
        return out
    }

    private fun byteAt(i: Long): Int = if (i < bytes.size) bytes[i.toInt()].toInt() and 0xFF else 255

    private inline fun toGray(comps: Int, c: (Int) -> Int): Int = when (comps) {
        1 -> c(0)
        3 -> (299 * c(0) + 587 * c(1) + 114 * c(2)) / 1000
        4 -> 255 - minOf(255, (299 * c(0) + 587 * c(1) + 114 * c(2)) / 1000 + c(3))
        else -> c(0)
    }
}

/**
 * Finds the pictures PDF pages are made of ([pages]), for [ImageTab] to read a tab saved as a picture; and makes
 * a one-page PDF of a picture ([wrap]), so a tab opened as an image file goes the way a PDF of pictures does.
 */
object PdfPictures {
    /** Pictures smaller than this (pixels across and down, and along their longer side) aren't a page of tab. */
    private const val MIN_SIDE = 100
    private const val MIN_LONG_SIDE = 300
    /** A page's picture covers at least this share of the page. */
    private const val MIN_COVER = 0.25
    /** The most bytes one picture may decode to. */
    private const val MAX_PICTURE = 96 * 1024 * 1024
    private const val MAX_FORM_DEPTH = 6

    /**
     * The picture each page of [pdf] is made of: the biggest one drawn on it, if it covers a good part of the page;
     * pages without one are left out. Empty if the file can't be read.
     */
    fun pages(pdf: ByteArray): List<PdfPicture> = try {
        val doc = PdfDocument(pdf)
        doc.pages().withIndex().mapNotNull { (i, p) ->
            try { biggest(doc, p, i) } catch (e: RuntimeException) { null }
        }
    } catch (e: RuntimeException) {
        emptyList()
    } catch (e: StackOverflowError) {
        emptyList()
    }

    /** A picture placed on a page: its stream and where the page's matrix puts it. */
    private class Placed(val stream: PdfStream, val m: PdfMatrix)

    private fun biggest(doc: PdfDocument, page: PdfPage, index: Int): PdfPicture? {
        val placed = ArrayList<Placed>()
        walk(doc, doc.contents(page), page.resources, PdfMatrix.IDENTITY, 0, placed)
        val box = page.box
        val pageArea = abs((box[2] - box[0]) * (box[3] - box[1])).coerceAtLeast(1.0)
        val best = placed.filter { p ->
            val w = (doc.resolve(p.stream.dict["Width"]) as? Double ?: 0.0)
            val h = (doc.resolve(p.stream.dict["Height"]) as? Double ?: 0.0)
            w >= MIN_SIDE && h >= MIN_SIDE && max(w, h) >= MIN_LONG_SIDE && area(p.m) >= MIN_COVER * pageArea
        }.maxByOrNull { area(it.m) } ?: return null
        return picture(doc, best, index, page.rotate)
    }

    /** The page area the unit square takes under [m]. */
    private fun area(m: PdfMatrix) = abs(m.a * m.d - m.b * m.c)

    /** Runs a content stream for its q, Q, cm and Do, collecting the pictures drawn (also inside forms). */
    private fun walk(doc: PdfDocument, data: ByteArray, res: Map<String, Any?>, start: PdfMatrix, depth: Int, out: MutableList<Placed>) {
        val lx = PdfLexer(data)
        val operands = ArrayList<Any?>(8)
        var ctm = start
        val stack = ArrayList<PdfMatrix>()
        var ops = 0
        while (true) {
            val t = lx.next(false)
            if (t === PdfLexer.EOF) return
            if (t !is PdfOp) { if (operands.size < 16) operands += t; continue }
            if (++ops > 2_000_000) return
            when (t.name) {
                "BI" -> lx.skipInlineImage()
                "q" -> if (stack.size < 64) stack += ctm
                "Q" -> if (stack.isNotEmpty()) ctm = stack.removeAt(stack.size - 1)
                "cm" -> if (operands.size >= 6) {
                    val v = DoubleArray(6) { (operands[operands.size - 6 + it] as? Double) ?: 0.0 }
                    ctm = PdfMatrix(v[0], v[1], v[2], v[3], v[4], v[5]) * ctm
                }
                "Do" -> {
                    val name = pdfName(operands.lastOrNull())
                    val x = name?.let { doc.resolve(pdfDict(doc.resolve(res["XObject"]))?.get(it)) } as? PdfStream
                    when (pdfName(x?.dict?.get("Subtype"))) {
                        "Image" -> if (out.size < 1000) out += Placed(x!!, ctm)
                        "Form" -> if (depth < MAX_FORM_DEPTH) {
                            val m = PdfMatrix.of(doc.resolve(x!!.dict["Matrix"]))?.let { it * ctm } ?: ctm
                            val inner = pdfDict(doc.resolve(x.dict["Resources"])) ?: res
                            doc.decode(x)?.let { walk(doc, it, inner, m, depth + 1, out) }
                        }
                    }
                }
            }
            operands.clear()
        }
    }

    private fun picture(doc: PdfDocument, p: Placed, page: Int, rotate: Int): PdfPicture? {
        val d = p.stream.dict
        fun num(key: String, alt: String) = ((doc.resolve(d[key] ?: d[alt]) as? Double) ?: 0.0).toInt()
        val width = num("Width", "W")
        val height = num("Height", "H")
        val mask = doc.resolve(d["ImageMask"] ?: d["IM"]) == true
        var bits = if (mask) 1 else num("BitsPerComponent", "BPC").takeIf { it > 0 } ?: 8
        val (codec, bytes) = doc.imageData(p.stream, MAX_PICTURE) ?: return null
        val kind = when (codec) {
            null -> PdfPicture.Kind.SAMPLES
            "DCTDecode", "DCT" -> PdfPicture.Kind.JPEG
            "JPXDecode" -> PdfPicture.Kind.JPEG2000
            "CCITTFaxDecode", "CCF" -> PdfPicture.Kind.FAX
            else -> PdfPicture.Kind.JBIG2
        }
        // Colour space: its components, or a palette.
        var comps = 1
        var palette: ByteArray? = null
        var paletteComps = 1
        if (!mask) {
            val cs = doc.resolve(d["ColorSpace"] ?: d["CS"])
            fun count(space: Any?): Int {
                val s = doc.resolve(space)
                return when (val n = pdfName(s) ?: pdfName((s as? List<*>)?.firstOrNull()?.let { doc.resolve(it) })) {
                    "DeviceRGB", "RGB", "CalRGB", "Lab" -> 3
                    "DeviceCMYK", "CMYK" -> 4
                    "ICCBased" -> ((doc.resolve((s as List<*>).getOrNull(1)) as? PdfStream)?.dict?.get("N") as? Double)?.toInt() ?: 3
                    else -> 1
                }
            }
            val csName = pdfName(cs) ?: pdfName((cs as? List<*>)?.firstOrNull()?.let { doc.resolve(it) })
            if (csName == "Indexed" || csName == "I") {
                val l = cs as List<*>
                paletteComps = count(l.getOrNull(1))
                palette = when (val lookup = doc.resolve(l.getOrNull(3))) {
                    is PdfString -> lookup.bytes
                    is PdfStream -> doc.decode(lookup)
                    else -> null
                }
            } else comps = count(cs)
        }
        val decode = doc.resolve(d["Decode"] ?: d["D"]) as? List<*>
        val inverted = decode != null && decode.size >= 2 && (decode[0] as? Double ?: 0.0) > (decode[1] as? Double ?: 1.0)
        if (kind == PdfPicture.Kind.JPEG) bits = 8
        return PdfPicture(page, kind, bytes, width, height, comps, bits, turns(p.m, rotate), inverted, palette, paletteComps, mask)
    }

    /**
     * Clockwise quarter turns that show a picture drawn with [m] (PDF space, y up) as the page shows it (with its
     * /Rotate of [rotate] degrees): where the picture's rows run (its x axis) on the page.
     */
    private fun turns(m: PdfMatrix, rotate: Int): Int {
        // The picture's x axis on the page, y down: (a, -b).
        val ax = m.a
        val ay = -m.b
        val q = if (abs(ax) >= abs(ay)) (if (ax >= 0) 0 else 2) else (if (ay > 0) 1 else 3)
        return (q + rotate / 90).mod(4)
    }

    /**
     * A one-page PDF showing the grayscale picture [gray] ([width] x [height], 0 black to 255 white, row by row),
     * losslessly (Flate), as [pages] finds it again.
     */
    fun wrap(gray: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0 && gray.size >= width * height)
        val packed = ByteArrayOutputStream(gray.size / 4 + 64)
        val def = Deflater(6)
        def.setInput(gray, 0, width * height)
        def.finish()
        val buf = ByteArray(64 * 1024)
        while (!def.finished()) { val n = def.deflate(buf); packed.write(buf, 0, n) }
        def.end()
        val data = packed.toByteArray()
        // The page at 144 pixels per inch.
        val pw = width * 0.5
        val ph = height * 0.5
        val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q".toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream(data.size + 1024)
        val offsets = IntArray(6)
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        w("%PDF-1.4\n%âãÏÓ\n")
        offsets[1] = out.size(); w("1 0 obj\n<</Type/Catalog/Pages 2 0 R>>\nendobj\n")
        offsets[2] = out.size(); w("2 0 obj\n<</Type/Pages/Kids[3 0 R]/Count 1>>\nendobj\n")
        offsets[3] = out.size(); w("3 0 obj\n<</Type/Page/Parent 2 0 R/MediaBox[0 0 $pw $ph]/Resources<</XObject<</Im0 4 0 R>>>>/Contents 5 0 R>>\nendobj\n")
        offsets[4] = out.size()
        w("4 0 obj\n<</Type/XObject/Subtype/Image/Width $width/Height $height/ColorSpace/DeviceGray/BitsPerComponent 8/Filter/FlateDecode/Length ${data.size}>>\nstream\n")
        out.write(data); w("\nendstream\nendobj\n")
        offsets[5] = out.size(); w("5 0 obj\n<</Length ${content.size}>>\nstream\n"); out.write(content); w("\nendstream\nendobj\n")
        val xref = out.size()
        w("xref\n0 6\n0000000000 65535 f \n")
        for (i in 1..5) w("%010d 00000 n \n".format(offsets[i]))
        w("trailer\n<</Size 6/Root 1 0 R>>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}

/**
 * The pictures the pages of [pdf] are made of (see [PdfPictures.pages]): for a PDF whose tab is a picture, which
 * [read] finds no numbers in ([PdfTab.NO_NUMBERS]); the app decodes them and reads them with [ImageTab].
 */
fun PdfTab.pageImages(pdf: ByteArray): List<PdfPicture> = PdfPictures.pages(pdf)
