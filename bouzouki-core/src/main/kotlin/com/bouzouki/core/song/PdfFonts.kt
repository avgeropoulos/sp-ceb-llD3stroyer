package com.bouzouki.core.song

/**
 * What text extraction needs from a PDF font: how a string's bytes split into character codes,
 * each code's advance width (in text space units for a font size of 1) and the text it stands for.
 * Simple fonts (Type1, TrueType, Type3) use one byte per code; Type0 (composite) fonts use their
 * CMap's code lengths, two bytes for Identity-H/V.
 */
internal class PdfFont private constructor(
    /** The font's /BaseFont, without a subset prefix such as `ABCDEF+`. */
    val name: String,
    private val composite: Boolean,
    private val encodingCMap: PdfCMap?,
    private val widthOf: (Int) -> Double,
    private val toUnicode: PdfCMap?,
    private val encoding: Array<String?>?,
    private val unicodeCodes: Boolean,
    private val scale: Double,
) {
    private val texts = HashMap<Int, String>()

    /** Calls [f] with each character code in [bytes] and the number of bytes it took. */
    fun forEachCode(bytes: ByteArray, f: (code: Int, length: Int) -> Unit) {
        if (!composite) { for (b in bytes) f(b.toInt() and 0xFF, 1); return }
        val cmap = encodingCMap?.takeIf { it.hasCodespace }
        if (cmap == null) {
            var i = 0
            while (i + 1 < bytes.size) { f(((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF), 2); i += 2 }
            return
        }
        var i = 0
        while (i < bytes.size) {
            val len = cmap.codeLength(bytes, i)
            var code = 0
            for (k in 0 until len) code = (code shl 8) or (bytes[i + k].toInt() and 0xFF)
            f(code, len)
            i += len
        }
    }

    /** The advance of [code] in text space units at font size 1. */
    fun width(code: Int): Double {
        val cid = if (composite) encodingCMap?.cid(code) ?: code else code
        return widthOf(cid) * scale
    }

    /** The text [code] stands for: from /ToUnicode, else the encoding, else the standard encoding. */
    fun text(code: Int): String = texts.getOrPut(code) {
        toUnicode?.unicode(code)?.takeIf { it.isNotEmpty() && it != "\u0000" }
            ?: (if (unicodeCodes) String(Character.toChars(code)) else null)
            ?: (if (!composite) encoding?.getOrNull(code) else null)
            ?: PdfEncodings.STANDARD.getOrNull(code)
            ?: ""
    }

    companion object {
        /** Reads font dictionary [d]. Never fails: what can't be read falls back to plain defaults. */
        fun load(doc: PdfDocument, d: Map<String, Any?>): PdfFont {
            val subtype = pdfName(d["Subtype"])
            val base = (pdfName(d["BaseFont"]) ?: "").substringAfter('+')
            val toUnicode = (doc.resolve(d["ToUnicode"]) as? PdfStream)?.let { doc.decode(it) }?.let { PdfCMap.parse(it) }
            return if (subtype == "Type0") composite(doc, d, base, toUnicode) else simple(doc, d, subtype, base, toUnicode)
        }

        private fun simple(doc: PdfDocument, d: Map<String, Any?>, subtype: String?, base: String, toUnicode: PdfCMap?): PdfFont {
            val first = (doc.resolve(d["FirstChar"]) as? Double)?.toInt() ?: 0
            val widths = doc.resolve(d["Widths"]) as? List<*>
            val descriptor = pdfDict(doc.resolve(d["FontDescriptor"]))
            val missing = doc.resolve(descriptor?.get("MissingWidth")) as? Double
            // Type3 glyphs are measured in their own glyph space, which /FontMatrix maps to text space.
            val scale = if (subtype == "Type3") {
                ((doc.resolve(d["FontMatrix"]) as? List<*>)?.getOrNull(0) as? Double)?.takeIf { it != 0.0 } ?: 0.001
            } else 0.001
            val table = DoubleArray(256) { code ->
                (doc.resolve(widths?.getOrNull(code - first)) as? Double)
                    ?: missing?.takeIf { widths != null && it > 0 }
                    ?: (guessWidth(base, code) * 0.001 / scale)
            }
            return PdfFont(base, false, null, { table.getOrElse(it) { 0.0 } }, toUnicode, encoding(doc, d["Encoding"]), false, scale)
        }

        private fun composite(doc: PdfDocument, d: Map<String, Any?>, base: String, toUnicode: PdfCMap?): PdfFont {
            val enc = doc.resolve(d["Encoding"])
            val cmap = (enc as? PdfStream)?.let { doc.decode(it) }?.let { PdfCMap.parse(it) }
            val encName = pdfName(enc) ?: ""
            val unicodeCodes = toUnicode == null && encName.startsWith("Uni") && (encName.contains("UCS2") || encName.contains("UTF16"))
            val descendant = pdfDict(doc.resolve((doc.resolve(d["DescendantFonts"]) as? List<*>)?.firstOrNull()))
            val dw = (doc.resolve(descendant?.get("DW")) as? Double) ?: 1000.0
            val single = HashMap<Int, Double>()
            val ranges = ArrayList<IntRange>()
            val rangeWidths = ArrayList<Double>()
            val w = doc.resolve(descendant?.get("W")) as? List<*>
            if (w != null) {
                var i = 0
                while (i < w.size && single.size < 65_536 && ranges.size < 65_536) {
                    val c = (doc.resolve(w[i]) as? Double) ?: break
                    val next = doc.resolve(w.getOrNull(i + 1))
                    if (next is List<*>) {
                        for ((k, v) in next.withIndex()) (doc.resolve(v) as? Double)?.let { single[c.toInt() + k] = it }
                        i += 2
                    } else {
                        val last = next as? Double ?: break
                        val width = (doc.resolve(w.getOrNull(i + 2)) as? Double) ?: break
                        ranges += c.toInt()..last.toInt()
                        rangeWidths += width
                        i += 3
                    }
                }
            }
            val lookup = RangeLookup(ranges)
            val widthOf = { cid: Int -> single[cid] ?: lookup.find(cid).let { if (it >= 0) rangeWidths[it] else dw } }
            return PdfFont(base, true, cmap, widthOf, toUnicode, null, unicodeCodes, 0.001)
        }

        /** A simple font's code → text table from its /Encoding (a name, or a dictionary with /Differences). */
        private fun encoding(doc: PdfDocument, e: Any?): Array<String?> {
            val enc = doc.resolve(e)
            val baseName = pdfName(enc) ?: pdfName(pdfDict(enc)?.get("BaseEncoding"))
            val table = when (baseName) {
                "WinAnsiEncoding" -> PdfEncodings.WIN_ANSI
                "MacRomanEncoding" -> PdfEncodings.MAC_ROMAN
                else -> PdfEncodings.STANDARD
            }.copyOf()
            val diffs = doc.resolve(pdfDict(enc)?.get("Differences")) as? List<*>
            if (diffs != null) {
                var code = 0
                for (item in diffs) {
                    when (val v = doc.resolve(item)) {
                        is Double -> code = v.toInt()
                        is PdfName -> { if (code in 0..255) table[code] = PdfEncodings.glyphText(v.name); code++ }
                    }
                }
            }
            return table
        }

        /** Advance widths (per 1000) for fonts without /Widths, close enough for the standard fonts. */
        private fun guessWidth(base: String, code: Int): Double {
            val lower = base.lowercase()
            if ("courier" in lower || "mono" in lower) return 600.0
            val times = "times" in lower || "serif" in lower && "sans" !in lower
            return when (code) {
                32 -> if (times) 250.0 else 278.0
                in '0'.code..'9'.code -> if (times) 500.0 else 556.0
                '('.code, ')'.code -> 333.0
                else -> if (times) 500.0 else 556.0
            }
        }
    }
}

/**
 * A CMap: a font's code lengths (codespace ranges) and code → CID mapping, or a /ToUnicode map
 * from codes to text (bfchar, and bfrange in both its forms).
 */
internal class PdfCMap private constructor() {
    private val spaces = ArrayList<IntArray>() // byte count, low, high
    private val chars = HashMap<Int, String>()
    private val ranges = ArrayList<Range>()
    private val cidChars = HashMap<Int, Int>()
    private val cidRanges = ArrayList<IntArray>() // low, high, first CID
    private var rangeLookup = RangeLookup(emptyList())
    private var cidLookup = RangeLookup(emptyList())

    private class Range(val lo: Int, val hi: Int, val base: ByteArray?, val list: List<String>?)

    val hasCodespace get() = spaces.isNotEmpty()

    /** How many bytes the code starting at [at] takes, by the codespace ranges (the shortest one if none matches). */
    fun codeLength(bytes: ByteArray, at: Int): Int {
        var shortest = 4
        for (len in 1..4) {
            if (at + len > bytes.size) break
            var code = 0
            for (k in 0 until len) code = (code shl 8) or (bytes[at + k].toInt() and 0xFF)
            for (s in spaces) {
                if (s[0] != len) continue
                if (code in s[1]..s[2]) return len
            }
        }
        for (s in spaces) if (s[0] < shortest) shortest = s[0]
        return shortest.coerceIn(1, bytes.size - at)
    }

    fun cid(code: Int): Int? {
        cidChars[code]?.let { return it }
        val k = cidLookup.find(code)
        return if (k >= 0) cidRanges[k][2] + (code - cidRanges[k][0]) else null
    }

    fun unicode(code: Int): String? {
        chars[code]?.let { return it }
        val k = rangeLookup.find(code)
        if (k >= 0) {
            val r = ranges[k]
            val off = code - r.lo
            if (r.list != null) return r.list.getOrNull(off)
            val b = r.base!!.copyOf()
            if (b.size >= 2) {
                val unit = (((b[b.size - 2].toInt() and 0xFF) shl 8) or (b[b.size - 1].toInt() and 0xFF)) + off
                b[b.size - 2] = (unit shr 8).toByte()
                b[b.size - 1] = unit.toByte()
            } else if (b.size == 1) b[0] = (b[0] + off).toByte()
            return utf16(b)
        }
        return null
    }

    companion object {
        private const val MAX_ENTRIES = 100_000

        fun parse(data: ByteArray): PdfCMap {
            val m = PdfCMap()
            val lx = PdfLexer(data)
            val ops = ArrayList<Any?>()
            var entries = 0
            while (entries < MAX_ENTRIES) {
                val t = lx.next(false)
                if (t === PdfLexer.EOF) break
                if (t !is PdfOp) { if (ops.size < 3 * 10_000) ops.add(t); continue }
                when (t.name) {
                    "endcodespacerange" -> for (i in 0 until ops.size / 2) {
                        val lo = ops[2 * i] as? PdfString ?: continue
                        val hi = ops[2 * i + 1] as? PdfString ?: continue
                        if (lo.bytes.size in 1..4 && m.spaces.size < 256) m.spaces += intArrayOf(lo.bytes.size, code(lo.bytes), code(hi.bytes))
                    }
                    "endbfchar" -> for (i in 0 until ops.size / 2) {
                        val src = ops[2 * i] as? PdfString ?: continue
                        val text = when (val dst = ops[2 * i + 1]) {
                            is PdfString -> utf16(dst.bytes)
                            is PdfName -> PdfEncodings.glyphText(dst.name)
                            else -> null
                        } ?: continue
                        m.chars[code(src.bytes)] = text
                        entries++
                    }
                    "endbfrange" -> for (i in 0 until ops.size / 3) {
                        val lo = ops[3 * i] as? PdfString ?: continue
                        val hi = ops[3 * i + 1] as? PdfString ?: continue
                        val l = code(lo.bytes)
                        val h = code(hi.bytes).coerceAtMost(l + 65_535)
                        if (h < l) continue
                        when (val dst = ops[3 * i + 2]) {
                            is PdfString -> m.ranges += Range(l, h, dst.bytes, null)
                            is List<*> -> m.ranges += Range(l, h, null, dst.map { (it as? PdfString)?.let { s -> utf16(s.bytes) } ?: "" })
                        }
                        entries++
                    }
                    "endcidchar" -> for (i in 0 until ops.size / 2) {
                        val src = ops[2 * i] as? PdfString ?: continue
                        val cid = ops[2 * i + 1] as? Double ?: continue
                        m.cidChars[code(src.bytes)] = cid.toInt()
                        entries++
                    }
                    "endcidrange" -> for (i in 0 until ops.size / 3) {
                        val lo = ops[3 * i] as? PdfString ?: continue
                        val hi = ops[3 * i + 1] as? PdfString ?: continue
                        val cid = ops[3 * i + 2] as? Double ?: continue
                        m.cidRanges += intArrayOf(code(lo.bytes), code(hi.bytes), cid.toInt())
                        entries++
                    }
                }
                ops.clear()
            }
            m.rangeLookup = RangeLookup(m.ranges.map { it.lo..it.hi })
            m.cidLookup = RangeLookup(m.cidRanges.map { it[0]..it[1] })
            return m
        }

        private fun code(b: ByteArray): Int {
            var c = 0
            for (i in 0 until minOf(b.size, 4)) c = (c shl 8) or (b[i].toInt() and 0xFF)
            return c
        }

        private fun utf16(b: ByteArray): String =
            if (b.size == 1) (b[0].toInt() and 0xFF).toChar().toString() else String(b, Charsets.UTF_16BE)
    }
}

/** The standard single-byte encodings and glyph names, for simple fonts. */
internal object PdfEncodings {
    /** Adobe StandardEncoding (ASCII with curly quotes, and its upper half). */
    val STANDARD: Array<String?> = Array(256) { c -> if (c in 32..126) c.toChar().toString() else null }.also { t ->
        t[0x27] = "’"
        t[0x60] = "‘"
        val upper = "A1¡A2¢A3£A4⁄A5¥A6ƒA7§A8¤A9'AA“AB«AC‹AD›AEﬁAFﬂB1–B2†B3‡B4·B6¶B7•B8‚B9„BA”BB»BC…BD‰BF¿" +
            "C1`C2´C3ˆC4˜C5¯C6˘C7˙C8¨CA˚CB¸CD˝CE˛CFˇD0—E1ÆE3ªE8ŁE9ØEAŒEBºF1æF5ıF8łF9øFAœFBß"
        var i = 0
        while (i + 2 < upper.length) { t[upper.substring(i, i + 2).toInt(16)] = upper[i + 2].toString(); i += 3 }
    }

    /** WinAnsiEncoding (Windows code page 1252). */
    val WIN_ANSI: Array<String?> = Array(256) { c ->
        when (c) {
            in 32..126 -> c.toChar().toString()
            in 0x80..0x9F -> "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ"[c - 0x80].toString()
            in 0xA0..0xFF -> c.toChar().toString()
            else -> null
        }
    }

    /** MacRomanEncoding. */
    val MAC_ROMAN: Array<String?> = run {
        val upper = "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûü" +
            "†°¢£§•¶ß®©™´¨≠ÆØ∞±≤≥¥µ∂∑∏π∫ªºΩæø" +
            "¿¡¬√ƒ≈∆«»… ÀÃÕŒœ–—“”‘’÷◊ÿŸ⁄€‹›ﬁﬂ" +
            "‡·‚„‰ÂÊÁËÈÍÎÏÌÓÔÒÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ"
        Array(256) { c -> if (c in 32..126) c.toChar().toString() else if (c >= 128 && c - 128 < upper.length) upper[c - 128].toString() else null }
    }

    private val NAMES: Map<String, Char> = run {
        val pairs = "space 20 exclam 21 quotedbl 22 numbersign 23 dollar 24 percent 25 ampersand 26 quotesingle 27 " +
            "parenleft 28 parenright 29 asterisk 2A plus 2B comma 2C hyphen 2D period 2E slash 2F zero 30 one 31 " +
            "two 32 three 33 four 34 five 35 six 36 seven 37 eight 38 nine 39 colon 3A semicolon 3B less 3C " +
            "equal 3D greater 3E question 3F at 40 bracketleft 5B backslash 5C bracketright 5D asciicircum 5E " +
            "underscore 5F grave 60 braceleft 7B bar 7C braceright 7D asciitilde 7E quoteleft 2018 quoteright 2019 " +
            "quotedblleft 201C quotedblright 201D quotesinglbase 201A quotedblbase 201E endash 2013 emdash 2014 " +
            "bullet 2022 ellipsis 2026 minus 2212 periodcentered B7 nbspace A0 sfthyphen AD dagger 2020 " +
            "daggerdbl 2021 degree B0 multiply D7 divide F7 fraction 2044 perthousand 2030 trademark 2122 " +
            "copyright A9 registered AE section A7 paragraph B6 fi FB01 fl FB02 florin 192 sharp 266F flat 266D natural 266E"
        val t = pairs.split(' ')
        (0 until t.size / 2).associate { t[2 * it] to t[2 * it + 1].toInt(16).toChar() }
    }

    /** The text of a glyph name: standard names, single letters, `uniXXXX` and `uXXXX[XX]`; suffixes like `.alt` are dropped. */
    fun glyphText(name: String): String? {
        val n = name.substringBefore('.').ifEmpty { return null }
        if (n.length == 1 && n[0].isLetter()) return n
        NAMES[n]?.let { return it.toString() }
        if (n.startsWith("uni") && n.length >= 7) n.substring(3, 7).toIntOrNull(16)?.let { return it.toChar().toString() }
        if (n.startsWith("u") && n.length in 5..7) n.substring(1).toIntOrNull(16)?.takeIf { it <= 0x10FFFF }?.let { return String(Character.toChars(it)) }
        return null
    }
}

/** Finds which of a list of code ranges holds a code, by binary search (the first listed wins where ranges overlap). */
internal class RangeLookup(ranges: List<IntRange>) {
    private val order = ranges.indices.sortedWith(compareBy({ ranges[it].first }, { it })).toIntArray()
    private val firsts = IntArray(order.size) { ranges[order[it]].first }
    private val lasts = IntArray(order.size) { ranges[order[it]].last }
    /** For each position in [order], the furthest end of the ranges up to it, to know when to stop looking back. */
    private val reach = IntArray(order.size).also { r -> var m = Int.MIN_VALUE; for (i in r.indices) { m = maxOf(m, lasts[i]); r[i] = m } }

    /** The index (in the original list) of the first range holding [code], or -1. */
    fun find(code: Int): Int {
        var lo = 0
        var hi = order.size - 1
        var at = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (firsts[mid] <= code) { at = mid; lo = mid + 1 } else hi = mid - 1
        }
        var best = -1
        var i = at
        var steps = 0
        while (i >= 0 && reach[i] >= code && steps++ < 64) { // only nested ranges make this look far back
            if (lasts[i] >= code && (best < 0 || order[i] < best)) best = order[i]
            i--
        }
        return best
    }
}
