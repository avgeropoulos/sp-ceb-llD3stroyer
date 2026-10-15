package com.bouzouki.core.song

import java.util.IdentityHashMap
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.min

/*
 * A small PDF reader: just enough of ISO 32000 to find the text and the straight lines on the pages
 * of printed music (see PdfTab). Objects are plain Kotlin values:
 * null, Boolean, Double (every number), PdfName, PdfString, List<Any?> (arrays),
 * Map<String, Any?> (dictionaries, keys without the slash), PdfRef, PdfStream, and PdfOp
 * (keywords; in content streams these are the operators).
 *
 * Nothing in here throws on bad input: damaged files are repaired or read in part, and the
 * problems are collected in PdfDocument.warnings. Work is bounded by the caps below.
 */

/** A PDF name such as `/Font`, without its slash. */
internal data class PdfName(val name: String)

/** A PDF string's raw bytes (in content streams these are character codes of the current font). */
internal class PdfString(val bytes: ByteArray)

/** An indirect reference, `num gen R`. */
internal data class PdfRef(val num: Int, val gen: Int)

/** A stream: its dictionary and its still encoded (and possibly encrypted) bytes; [num] and [gen] name its object. */
internal class PdfStream(val dict: Map<String, Any?>, val raw: ByteArray, val num: Int = -1, val gen: Int = 0)

/** A keyword that isn't true, false or null: a content stream operator, or `obj`, `stream`, `xref`... */
internal data class PdfOp(val name: String)

/** The name in [o] if it is one, else null. */
internal fun pdfName(o: Any?): String? = (o as? PdfName)?.name

/** [o] as a dictionary, or null. */
@Suppress("UNCHECKED_CAST")
internal fun pdfDict(o: Any?): Map<String, Any?>? = o as? Map<String, Any?>

/**
 * Splits PDF syntax into objects. Used for whole files (with [PdfRef]s) and for content streams
 * (where `n g R` can't occur). Malformed syntax is skipped, never thrown.
 */
internal class PdfLexer(private val buf: ByteArray, var pos: Int = 0, private val end: Int = buf.size) {
    /** Where the last token began, so a caller can step back over it. */
    private var tokenStart = 0
    /** Whether the last number read was a plain unsigned integer (needed to spot `n g R`). */
    private var plainInt = false
    private val text = ByteBuf()

    /** The next object, or [EOF] at the end. With [refs], `n g R` is read as a [PdfRef]. */
    fun next(refs: Boolean = true): Any? {
        while (true) {
            val t = token(refs, 0)
            if (t !== END_DICT && t !== END_ARRAY) return t
        }
    }

    fun skipSpace() {
        while (pos < end) {
            val c = buf[pos].toInt() and 0xFF
            if (isWhite(c)) pos++
            else if (c == '%'.code) { while (pos < end && buf[pos] != NL && buf[pos] != CR) pos++ }
            else return
        }
    }

    /**
     * Skips an inline image (`BI` dict `ID` data `EI`), called just after `BI`: the data is binary,
     * so it ends at the first `EI` that stands between white space.
     */
    fun skipInlineImage() {
        while (true) {
            val t = next(false)
            if (t === EOF) return
            if (t is PdfOp && t.name == "ID") break
        }
        pos++
        while (pos + 1 < end) {
            if (buf[pos] == 'E'.code.toByte() && buf[pos + 1] == 'I'.code.toByte() &&
                isWhite(buf[pos - 1].toInt() and 0xFF) &&
                (pos + 2 >= end || isWhite(buf[pos + 2].toInt() and 0xFF))
            ) { pos += 2; return }
            pos++
        }
        pos = end
    }

    private fun token(refs: Boolean, depth: Int): Any? {
        while (true) {
            skipSpace()
            if (pos >= end) return EOF
            tokenStart = pos
            val c = buf[pos].toInt() and 0xFF
            when (c) {
                '/'.code -> return name()
                '('.code -> return literal()
                '<'.code -> {
                    if (pos + 1 < end && buf[pos + 1] == '<'.code.toByte()) { pos += 2; return dict(refs, depth) }
                    return hex()
                }
                '>'.code -> {
                    if (pos + 1 < end && buf[pos + 1] == '>'.code.toByte()) { pos += 2; return END_DICT }
                    pos++
                }
                '['.code -> { pos++; return array(refs, depth) }
                ']'.code -> { pos++; return END_ARRAY }
                '{'.code, '}'.code, ')'.code -> pos++ // PostScript braces, stray parenthesis
                else -> return if (c in '0'.code..'9'.code || c == '+'.code || c == '-'.code || c == '.'.code) number(refs) else keyword()
            }
        }
    }

    private fun dict(refs: Boolean, depth: Int): Any? {
        if (depth >= MAX_DEPTH) return null
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            val k = token(refs, depth + 1)
            when {
                k === END_DICT || k === EOF -> return map
                k is PdfOp -> { pos = tokenStart; return map } // a missing ">>"
                k is PdfName -> {
                    val v = token(refs, depth + 1)
                    if (v === END_DICT || v === EOF) return map
                    if (v is PdfOp) { pos = tokenStart; return map }
                    if (v !== END_ARRAY) map[k.name] = v
                }
            }
        }
    }

    private fun array(refs: Boolean, depth: Int): Any? {
        if (depth >= MAX_DEPTH) return null
        val list = ArrayList<Any?>()
        while (true) {
            val v = token(refs, depth + 1)
            when {
                v === END_ARRAY || v === EOF -> return list
                v === END_DICT -> {}
                v is PdfOp -> { pos = tokenStart; return list } // a missing "]"
                else -> list.add(v)
            }
        }
    }

    private fun number(refs: Boolean): Any? {
        val v = parseNumber()
        if (refs && plainInt) {
            val save = pos
            skipSpace()
            if (pos < end && isDigit(buf[pos])) {
                val g = parseNumber()
                if (plainInt) {
                    skipSpace()
                    if (pos < end && buf[pos] == 'R'.code.toByte() && (pos + 1 >= end || !isRegular(buf[pos + 1].toInt() and 0xFF))) {
                        pos++
                        if (v <= Int.MAX_VALUE && g <= 65535) return PdfRef(v.toInt(), g.toInt())
                        return null
                    }
                }
            }
            pos = save
            plainInt = true
        }
        return v
    }

    /** Reads a number leniently ("--5" is 5, "4.-2" stops at the second sign); sets [plainInt]. */
    private fun parseNumber(): Double {
        var neg = false
        var signed = false
        while (pos < end && (buf[pos] == '+'.code.toByte() || buf[pos] == '-'.code.toByte())) {
            if (buf[pos] == '-'.code.toByte()) neg = !neg
            signed = true
            pos++
        }
        var v = 0.0
        var scale = 0.0
        var digits = 0
        while (pos < end) {
            val c = buf[pos].toInt()
            if (c in '0'.code..'9'.code) {
                if (scale == 0.0) v = v * 10 + (c - '0'.code) else { v += (c - '0'.code) * scale; scale /= 10 }
                digits++
            } else if (c == '.'.code && scale == 0.0) scale = 0.1
            else break
            pos++
        }
        if (digits == 0 && !signed && scale == 0.0) pos++ // never stall
        plainInt = !signed && scale == 0.0 && digits > 0
        return if (neg) -v else v
    }

    private fun name(): PdfName {
        pos++
        val sb = StringBuilder()
        while (pos < end) {
            val c = buf[pos].toInt() and 0xFF
            if (isWhite(c) || isDelim(c)) break
            if (c == '#'.code && pos + 2 < end && hexValue(buf[pos + 1]) >= 0 && hexValue(buf[pos + 2]) >= 0) {
                sb.append(((hexValue(buf[pos + 1]) shl 4) or hexValue(buf[pos + 2])).toChar())
                pos += 3
            } else { sb.append(c.toChar()); pos++ }
        }
        return PdfName(sb.toString())
    }

    private fun literal(): PdfString {
        pos++
        text.clear()
        var depth = 1
        while (pos < end) {
            val c = buf[pos++].toInt() and 0xFF
            when (c) {
                '\\'.code -> {
                    if (pos >= end) break
                    val e = buf[pos++].toInt() and 0xFF
                    when (e) {
                        'n'.code -> text.add(10)
                        'r'.code -> text.add(13)
                        't'.code -> text.add(9)
                        'b'.code -> text.add(8)
                        'f'.code -> text.add(12)
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            var k = 1
                            while (k < 3 && pos < end && buf[pos] >= '0'.code.toByte() && buf[pos] <= '7'.code.toByte()) {
                                v = v * 8 + (buf[pos] - '0'.code.toByte()); pos++; k++
                            }
                            text.add(v and 0xFF)
                        }
                        CR.toInt() -> if (pos < end && buf[pos] == NL) pos++ // a line continuation
                        NL.toInt() -> {}
                        else -> text.add(e)
                    }
                }
                '('.code -> { depth++; text.add(c) }
                ')'.code -> { depth--; if (depth == 0) return PdfString(text.toArray()); text.add(c) }
                else -> text.add(c)
            }
        }
        return PdfString(text.toArray())
    }

    private fun hex(): PdfString {
        pos++
        text.clear()
        var hi = -1
        while (pos < end) {
            val b = buf[pos]
            if (b == '>'.code.toByte()) { pos++; break }
            val h = hexValue(b)
            if (h < 0 && !isWhite(b.toInt() and 0xFF)) break // malformed: stop here
            pos++
            if (h < 0) continue
            if (hi < 0) hi = h else { text.add((hi shl 4) or h); hi = -1 }
        }
        if (hi >= 0) text.add(hi shl 4)
        return PdfString(text.toArray())
    }

    private fun keyword(): Any? {
        val start = pos
        while (pos < end && isRegular(buf[pos].toInt() and 0xFF)) pos++
        if (pos == start) { pos++; return null }
        return when (val s = String(buf, start, pos - start, Charsets.ISO_8859_1)) {
            "true" -> true
            "false" -> false
            "null" -> null
            else -> PdfOp(s)
        }
    }

    companion object {
        /** Returned by [next] at the end of the input. */
        val EOF = Any()
        private val END_DICT = Any()
        private val END_ARRAY = Any()
        private const val MAX_DEPTH = 64
        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()

        fun isWhite(c: Int) = c == 32 || c == 10 || c == 13 || c == 9 || c == 12 || c == 0
        fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code ||
            c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
        fun isRegular(c: Int) = !isWhite(c) && !isDelim(c)
        fun isDigit(b: Byte) = b >= '0'.code.toByte() && b <= '9'.code.toByte()
        fun hexValue(b: Byte): Int = when (b.toInt().toChar()) {
            in '0'..'9' -> b - '0'.code.toByte()
            in 'a'..'f' -> b - 'a'.code.toByte() + 10
            in 'A'..'F' -> b - 'A'.code.toByte() + 10
            else -> -1
        }
    }
}

/** A growable byte array (faster than ByteArrayOutputStream, which locks on every write). */
internal class ByteBuf(capacity: Int = 64) {
    private var a = ByteArray(capacity)
    var size = 0
        private set

    fun add(b: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = b.toByte()
    }

    fun add(src: ByteArray, from: Int, len: Int) {
        if (size + len > a.size) a = a.copyOf(maxOf(a.size * 2, size + len))
        System.arraycopy(src, from, a, size, len)
        size += len
    }

    fun clear() { size = 0 }
    fun toArray(): ByteArray = a.copyOf(size)
}

/**
 * A page: its dictionary, its (inherited) resources, its media box (left, bottom, right, top) and
 * how many degrees it is turned clockwise for display (0, 90, 180 or 270).
 */
internal class PdfPage(val dict: Map<String, Any?>, val resources: Map<String, Any?>, val box: DoubleArray, val rotate: Int = 0)

/**
 * A PDF file opened for reading: the cross-reference table (classic tables and xref streams,
 * incremental updates, hybrid files), objects loaded on demand (also from object streams), stream
 * decoding, and the page tree. A missing or damaged table is rebuilt by scanning for `n g obj`.
 */
internal class PdfDocument(private val data: ByteArray) {
    /** Problems met while reading, worded for the player. */
    val warnings = LinkedHashSet<String>()

    /** The (merged) trailer dictionary. */
    var trailer: Map<String, Any?> = emptyMap()
        private set

    /** Whether the file is encrypted in a way that can't be read (it needs a password, or uses an unknown scheme). */
    var locked = false
        private set

    /** Where an object lives: at a file offset ([inStream] false, [a] = offset), or in object stream [a] at index [b]. */
    private class Entry(val inStream: Boolean, val a: Int, val b: Int)

    private val table = HashMap<Int, Entry?>()
    private val cache = HashMap<Int, Any?>()
    private val loading = HashSet<Int>()
    private val objStms = HashMap<Int, ObjStm?>()
    private var repaired = false
    private var decodeBudget = MAX_TOTAL_DECODED
    private var crypt: PdfCrypt? = null
    private var endstreams: IntArray? = null

    private class ObjStm(val bytes: ByteArray, val nums: IntArray, val offsets: IntArray)

    init {
        val ok = try { readXref() } catch (e: RuntimeException) { false }
        if (!ok || trailer["Root"] == null) repair()
        val enc = trailer["Encrypt"]
        if (enc != null) {
            crypt = PdfCrypt.open(pdfDict(resolve(enc)), trailer["ID"])
            if (crypt == null) {
                locked = true
                warnings += "This PDF is protected with a password or an encryption PdfTab can't read"
            }
        }
    }

    /** Follows references (a few hops at most) to the object itself. */
    fun resolve(o: Any?): Any? {
        var x = o
        var hops = 0
        while (x is PdfRef) {
            if (++hops > 16) return null
            x = get(x.num)
        }
        return x
    }

    /** Object number [num], or null if it doesn't exist. */
    fun get(num: Int): Any? {
        if (cache.containsKey(num)) return cache[num]
        if (cache.size > MAX_OBJECTS || !loading.add(num)) return null
        try {
            val v = try { load(num) } catch (e: RuntimeException) { null }
            cache[num] = v
            return v
        } finally {
            loading.remove(num)
        }
    }

    private fun load(num: Int): Any? {
        val e = table[num] ?: return null
        if (e.inStream) return fromObjStm(e.a, e.b, num)
        val v = parseObjectAt(e.a, num)
        if (v !== MISMATCH) return v
        if (repaired) return null
        repair()
        val again = table[num] ?: return null
        if (again.inStream) return fromObjStm(again.a, again.b, num)
        return parseObjectAt(again.a, num).takeIf { it !== MISMATCH }
    }

    // ---- Cross-reference table ----

    private fun readXref(): Boolean {
        var offset = findStartXref() ?: return false
        val seen = HashSet<Int>()
        val merged = LinkedHashMap<String, Any?>()
        while (offset >= 0) {
            if (offset >= data.size || !seen.add(offset) || seen.size > 64) return false
            val tr = readSection(offset) ?: return false
            for ((k, v) in tr) if (k !in merged) merged[k] = v
            offset = (tr["Prev"] as? Double)?.toInt() ?: -1
        }
        merged.remove("Prev")
        trailer = merged
        return true
    }

    private fun findStartXref(): Int? {
        val key = "startxref".toByteArray(Charsets.ISO_8859_1)
        val from = maxOf(0, data.size - 64 * 1024)
        val at = lastIndexOf(key, from) ?: return null
        return (PdfLexer(data, at + key.size).next(false) as? Double)?.toInt()
    }

    /** Reads one xref section (classic or stream) at [offset] into [table]; returns its trailer. */
    private fun readSection(offset: Int): Map<String, Any?>? {
        val lx = PdfLexer(data, offset)
        val first = lx.next(false)
        if (first is PdfOp && first.name == "xref") {
            while (true) {
                val a = lx.next(false)
                if (a is PdfOp && a.name == "trailer") break
                val start = (a as? Double)?.toInt() ?: return null
                val count = (lx.next(false) as? Double)?.toInt() ?: return null
                if (start < 0 || count < 0 || count > MAX_OBJECTS) return null
                for (i in 0 until count) {
                    val off = lx.next(false) as? Double ?: return null
                    val gen = lx.next(false) as? Double ?: return null
                    val kind = (lx.next(false) as? PdfOp)?.name ?: return null
                    val num = start + i
                    if (!table.containsKey(num)) table[num] = if (kind == "n" && off > 0) Entry(false, off.toInt(), gen.toInt()) else null
                }
            }
            val tr = pdfDict(lx.next(true)) ?: return null
            (tr["XRefStm"] as? Double)?.let { at ->
                (parseObjectAt(at.toInt(), -1) as? PdfStream)?.let { readXrefStream(it) }
            }
            return tr
        }
        val s = parseObjectAt(offset, -1) as? PdfStream ?: return null
        if (pdfName(s.dict["Type"]) != "XRef") return null
        readXrefStream(s)
        return s.dict
    }

    private fun readXrefStream(s: PdfStream) {
        val d = decode(s) ?: return
        val w = (s.dict["W"] as? List<*>)?.map { (it as? Double)?.toInt() ?: -1 } ?: return
        if (w.size < 3 || w.any { it !in 0..8 }) return
        val row = w[0] + w[1] + w[2]
        if (row == 0) return
        val size = (s.dict["Size"] as? Double)?.toInt() ?: 0
        val index = (s.dict["Index"] as? List<*>)?.map { (it as? Double)?.toInt() ?: 0 } ?: listOf(0, size)
        var p = 0
        for (k in 0 until index.size / 2) {
            val start = index[2 * k]
            val count = index[2 * k + 1]
            for (i in 0 until count) {
                if (p + row > d.size) return
                val type = if (w[0] == 0) 1 else field(d, p, w[0])
                val f1 = field(d, p + w[0], w[1])
                val f2 = field(d, p + w[0] + w[1], w[2])
                p += row
                val num = start + i
                if (num < 0 || table.containsKey(num)) continue
                when (type) {
                    0 -> table[num] = null
                    1 -> table[num] = if (f1 > 0) Entry(false, f1, f2) else null
                    2 -> table[num] = Entry(true, f1, f2)
                }
            }
        }
    }

    private fun field(d: ByteArray, at: Int, width: Int): Int {
        var v = 0L
        for (i in 0 until width) v = (v shl 8) or (d[at + i].toLong() and 0xFF)
        return if (v > Int.MAX_VALUE) -1 else v.toInt()
    }

    /**
     * Rebuilds the table by scanning the whole file for `n g obj` (later definitions win, as in
     * incremental updates) and for trailers, then registers the objects of every object stream.
     */
    private fun repair() {
        repaired = true
        warnings += "Parts of this PDF are damaged; it was read as well as possible"
        val found = HashMap<Int, Entry>()
        val starts = ArrayList<Int>()
        val key = "obj".toByteArray(Charsets.ISO_8859_1)
        var i = indexOf(key, 0)
        while (i >= 0 && found.size < MAX_OBJECTS) {
            val after = i + 3
            if (after >= data.size || !PdfLexer.isRegular(data[after].toInt() and 0xFF)) {
                var p = i - 1
                while (p >= 0 && PdfLexer.isWhite(data[p].toInt() and 0xFF)) p--
                val genEnd = p
                while (p >= 0 && PdfLexer.isDigit(data[p])) p--
                val genStart = p + 1
                if (genEnd >= genStart && genEnd - genStart < 6 && p >= 0 && PdfLexer.isWhite(data[p].toInt() and 0xFF)) {
                    while (p >= 0 && PdfLexer.isWhite(data[p].toInt() and 0xFF)) p--
                    val numEnd = p
                    while (p >= 0 && PdfLexer.isDigit(data[p])) p--
                    val numStart = p + 1
                    if (numEnd >= numStart && numEnd - numStart < 9 && (p < 0 || !PdfLexer.isRegular(data[p].toInt() and 0xFF))) {
                        val num = String(data, numStart, numEnd - numStart + 1, Charsets.ISO_8859_1).toInt()
                        val gen = String(data, genStart, genEnd - genStart + 1, Charsets.ISO_8859_1).toInt()
                        found[num] = Entry(false, numStart, gen)
                        starts += numStart
                    }
                }
            }
            i = indexOf(key, i + 3)
        }
        // Keep what the old table said about objects in object streams; direct objects come from the scan.
        val packed = table.filterValues { it != null && it.inStream }
        table.clear()
        cache.clear()
        objStms.clear()
        table.putAll(found)
        for ((k, v) in packed) if (k !in table) table[k] = v
        starts.sort()
        val sorted = starts.toIntArray()

        // Objects inside object streams that the old table didn't know about.
        forEachOwner("/ObjStm", sorted) { num ->
            val s = get(num) as? PdfStream
            if (s != null && pdfName(s.dict["Type"]) == "ObjStm") {
                val stm = objStm(num)
                if (stm != null) for (k in stm.nums.indices) if (stm.nums[k] !in table) table[stm.nums[k]] = Entry(true, num, k)
            }
        }

        // The trailer: classic trailers and xref stream dictionaries, later ones winning.
        val merged = LinkedHashMap<String, Any?>()
        val trailerKey = "trailer".toByteArray(Charsets.ISO_8859_1)
        var t = indexOf(trailerKey, 0)
        while (t >= 0) {
            pdfDict(PdfLexer(data, t + trailerKey.size).next(true))?.let { merged.putAll(it) }
            t = indexOf(trailerKey, t + trailerKey.size)
        }
        forEachOwner("/XRef", sorted) { num ->
            val s = get(num) as? PdfStream
            if (s != null && pdfName(s.dict["Type"]) == "XRef") merged.putAll(s.dict)
        }
        if (resolve(merged["Root"]) == null) {
            merged.remove("Root")
            forEachOwner("/Catalog", sorted) { num ->
                if (pdfName(pdfDict(get(num))?.get("Type")) == "Catalog") merged["Root"] = PdfRef(num, 0)
            }
        }
        merged.remove("Prev")
        trailer = merged
    }

    /** For each place [marker] occurs, the number of the object (from [starts]) that contains it. */
    private inline fun forEachOwner(marker: String, starts: IntArray, f: (Int) -> Unit) {
        val key = marker.toByteArray(Charsets.ISO_8859_1)
        var i = indexOf(key, 0)
        var last = -1
        var n = 0
        while (i >= 0 && n++ < 10_000) {
            var k = java.util.Arrays.binarySearch(starts, i)
            if (k < 0) k = -k - 2
            if (k >= 0) {
                val num = PdfLexer(data, starts[k]).next(false) as? Double
                if (num != null && num.toInt() != last) { last = num.toInt(); f(last) }
            }
            i = indexOf(key, i + key.size)
        }
    }

    // ---- Objects ----

    /** The object at [offset], checked to be number [expect] (unless that's -1); [MISMATCH] if it isn't there. */
    private fun parseObjectAt(offset: Int, expect: Int): Any? {
        if (offset < 0 || offset >= data.size) return MISMATCH
        val lx = PdfLexer(data, offset)
        val n = lx.next(false) as? Double
        val g = lx.next(false) as? Double
        val kw = lx.next(false) as? PdfOp
        if (n == null || g == null || kw?.name != "obj" || (expect >= 0 && n.toInt() != expect)) return MISMATCH
        val obj = lx.next(true)
        if (obj === PdfLexer.EOF) return null
        val dict = pdfDict(obj) ?: return obj
        val save = lx.pos
        val k = lx.next(false)
        if (k !is PdfOp || k.name != "stream") { lx.pos = save; return dict }
        return PdfStream(dict, streamBytes(lx.pos, dict), n.toInt(), g.toInt())
    }

    /** A stream's bytes, starting just after the `stream` keyword; [dict]'s /Length is checked against `endstream`. */
    private fun streamBytes(afterKeyword: Int, dict: Map<String, Any?>): ByteArray {
        var p = afterKeyword
        if (p < data.size && data[p] == '\r'.code.toByte()) p++
        if (p < data.size && data[p] == '\n'.code.toByte()) p++
        val lenObj = dict["Length"]
        val len = (if (lenObj is PdfRef) resolve(lenObj) else lenObj) as? Double
        if (len != null && len >= 0 && p + len <= data.size && endstreamAt(p + len.toInt())) {
            return data.copyOfRange(p, p + len.toInt())
        }
        var e = nextEndstream(p)
        if (e < 0) e = data.size
        var q = e
        if (q > p && data[q - 1] == '\n'.code.toByte()) q--
        if (q > p && data[q - 1] == '\r'.code.toByte()) q--
        return data.copyOfRange(p, min(q, p + MAX_STREAM))
    }

    private fun endstreamAt(at: Int): Boolean {
        var p = at
        while (p < data.size && PdfLexer.isWhite(data[p].toInt() and 0xFF)) p++
        return startsWith(p, ENDSTREAM)
    }

    /** The first `endstream` at or after [from], found by binary search in a list made once. */
    private fun nextEndstream(from: Int): Int {
        val all = endstreams ?: run {
            val list = ArrayList<Int>()
            var i = indexOf(ENDSTREAM, 0)
            while (i >= 0) { list += i; i = indexOf(ENDSTREAM, i + ENDSTREAM.size) }
            list.toIntArray().also { endstreams = it }
        }
        var k = java.util.Arrays.binarySearch(all, from)
        if (k < 0) k = -k - 1
        return if (k < all.size) all[k] else -1
    }

    private fun fromObjStm(stmNum: Int, index: Int, num: Int): Any? {
        val stm = objStm(stmNum) ?: return null
        val k = if (index in stm.nums.indices && stm.nums[index] == num) index else stm.nums.indexOf(num)
        if (k < 0) return null
        val off = stm.offsets[k]
        if (off !in stm.bytes.indices) return null
        val v = PdfLexer(stm.bytes, off).next(true)
        return if (v === PdfLexer.EOF) null else v
    }

    private fun objStm(num: Int): ObjStm? {
        if (objStms.containsKey(num)) return objStms[num]
        objStms[num] = null
        val s = get(num) as? PdfStream ?: return null
        val bytes = decode(s) ?: return null
        val n = ((s.dict["N"] as? Double)?.toInt() ?: 0).coerceIn(0, 100_000)
        val first = (s.dict["First"] as? Double)?.toInt() ?: return null
        val lx = PdfLexer(bytes, 0, first.coerceIn(0, bytes.size))
        val nums = IntArray(n)
        val offsets = IntArray(n)
        var count = 0
        while (count < n) {
            val a = lx.next(false) as? Double ?: break
            val b = lx.next(false) as? Double ?: break
            nums[count] = a.toInt()
            offsets[count] = first + b.toInt()
            count++
        }
        return ObjStm(bytes, nums.copyOf(count), offsets.copyOf(count)).also { objStms[num] = it }
    }

    // ---- Streams ----

    /**
     * A stream's decoded bytes: decrypted, then through its filters (Flate, LZW, ASCIIHex,
     * ASCII85, RunLength, with PNG/TIFF predictors). Null for image codecs and unknown filters.
     * Truncated compressed data gives what could be decoded.
     */
    fun decode(s: PdfStream): ByteArray? {
        var d = s.raw
        val c = crypt
        if (c != null && pdfName(s.dict["Type"]) != "XRef") d = c.decrypt(d, s.num, s.gen, s.dict) ?: return null
        val filters = resolve(s.dict["Filter"]).let { f -> if (f is List<*>) f.map { pdfName(resolve(it)) } else listOf(pdfName(f)) }
        val parms = resolve(s.dict["DecodeParms"] ?: s.dict["DP"]).let { p -> if (p is List<*>) p.map { pdfDict(resolve(it)) } else listOf(pdfDict(p)) }
        for ((i, f) in filters.withIndex()) {
            if (f == null) continue
            val parm = parms.getOrNull(i)
            d = when (f) {
                "FlateDecode", "Fl" -> PdfFilters.predict(inflate(d), parm)
                "LZWDecode", "LZW" -> PdfFilters.predict(PdfFilters.lzw(d, (parm?.get("EarlyChange") as? Double)?.toInt() ?: 1, budget()), parm)
                "ASCIIHexDecode", "AHx" -> PdfFilters.asciiHex(d)
                "ASCII85Decode", "A85" -> PdfFilters.ascii85(d)
                "RunLengthDecode", "RL" -> PdfFilters.runLength(d, budget())
                "Crypt" -> d
                "DCTDecode", "DCT", "JPXDecode", "CCITTFaxDecode", "CCF", "JBIG2Decode" -> return null // pictures
                else -> { warnings += "Skipped a part of this PDF compressed with $f, which isn't supported"; return null }
            }
            decodeBudget -= d.size
        }
        return d
    }

    private fun budget() = min(MAX_STREAM, maxOf(0, decodeBudget))

    /** Inflates zlib data, keeping whatever comes out of a truncated or corrupt stream. */
    private fun inflate(src: ByteArray): ByteArray {
        val cap = budget()
        val out = ByteBuf(maxOf(64, min(cap, src.size * 4)))
        for (raw in booleanArrayOf(false, true)) {
            val inf = Inflater(raw)
            val chunk = ByteArray(16 * 1024)
            try {
                inf.setInput(src)
                var stalls = 0
                while (!inf.finished() && out.size < cap) {
                    val n = inf.inflate(chunk, 0, min(chunk.size, cap - out.size))
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary() || ++stalls > 2) break
                    } else { stalls = 0; out.add(chunk, 0, n) }
                }
            } catch (e: DataFormatException) {
                // Keep what was decoded so far.
            } finally {
                inf.end()
            }
            if (out.size > 0 || raw) break
        }
        if (out.size >= cap) warnings += "This PDF is too large to read completely"
        return out.toArray()
    }

    // ---- Pages ----

    /** What a page inherits from the page tree nodes above it. */
    private class Inherited(val resources: Map<String, Any?>, val box: DoubleArray, val rotate: Int)

    /** The pages in order, with their inherited resources, media boxes and rotation. */
    fun pages(): List<PdfPage> {
        if (locked) return emptyList()
        val out = ArrayList<PdfPage>()
        val root = pdfDict(resolve(trailer["Root"]))
        val seen = IdentityHashMap<Any, Boolean>()
        val top = Inherited(emptyMap(), DEFAULT_BOX, 0)
        walk(resolve(root?.get("Pages")), top, 0, seen, out)
        if (out.isEmpty()) {
            // No usable page tree: take every page object in object-number order.
            for (num in table.keys.sorted().take(MAX_OBJECTS)) {
                val d = pdfDict(get(num)) ?: continue
                if (pdfName(d["Type"]) == "Page") out += page(d, top)
                if (out.size >= MAX_PAGES) break
            }
        }
        return out
    }

    private fun walk(node: Any?, up: Inherited, depth: Int, seen: IdentityHashMap<Any, Boolean>, out: MutableList<PdfPage>) {
        val d = pdfDict(node) ?: return
        if (depth > 64 || out.size >= MAX_PAGES || seen.put(d, true) != null) return
        val kids = resolve(d["Kids"]) as? List<*>
        val type = pdfName(d["Type"])
        if (type == "Page" || (kids == null && type != "Pages")) { out += page(d, up); return }
        val mine = inherit(d, up)
        for (k in kids.orEmpty()) walk(resolve(k), mine, depth + 1, seen, out)
    }

    private fun inherit(d: Map<String, Any?>, up: Inherited) = Inherited(
        pdfDict(resolve(d["Resources"])) ?: up.resources,
        box(d["MediaBox"]) ?: up.box,
        (resolve(d["Rotate"]) as? Double)?.toInt()?.let { ((it % 360) + 360) % 360 / 90 * 90 } ?: up.rotate,
    )

    private fun page(d: Map<String, Any?>, up: Inherited): PdfPage {
        val i = inherit(d, up)
        return PdfPage(d, i.resources, i.box, i.rotate)
    }

    private fun box(o: Any?): DoubleArray? {
        val a = resolve(o) as? List<*> ?: return null
        if (a.size < 4) return null
        val v = DoubleArray(4) { (resolve(a[it]) as? Double) ?: return null }
        val b = doubleArrayOf(min(v[0], v[2]), min(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
        return if (b[2] - b[0] < 1 || b[3] - b[1] < 1) null else b
    }

    /** A page's content streams, decoded and joined. */
    fun contents(page: PdfPage): ByteArray {
        val c = resolve(page.dict["Contents"])
        val parts = if (c is List<*>) c.map { resolve(it) } else listOf(c)
        val out = ByteBuf(4096)
        for (p in parts) {
            val s = p as? PdfStream ?: continue
            val d = decode(s) ?: continue
            out.add(d, 0, d.size)
            out.add('\n'.code)
        }
        return out.toArray()
    }

    // ---- Byte search ----

    private fun startsWith(at: Int, key: ByteArray): Boolean {
        if (at < 0 || at + key.size > data.size) return false
        for (i in key.indices) if (data[at + i] != key[i]) return false
        return true
    }

    private fun indexOf(key: ByteArray, from: Int): Int {
        val first = key[0]
        var i = maxOf(0, from)
        val last = data.size - key.size
        while (i <= last) {
            if (data[i] == first && startsWith(i, key)) return i
            i++
        }
        return -1
    }

    private fun lastIndexOf(key: ByteArray, from: Int): Int? {
        var i = data.size - key.size
        while (i >= from) {
            if (startsWith(i, key)) return i
            i--
        }
        return null
    }

    companion object {
        /** Caps that keep a hostile or broken file from taking too long or too much memory. */
        const val MAX_OBJECTS = 500_000
        const val MAX_PAGES = 2_000
        const val MAX_STREAM = 16 * 1024 * 1024
        const val MAX_TOTAL_DECODED = 64 * 1024 * 1024
        private val MISMATCH = Any()
        private val ENDSTREAM = "endstream".toByteArray(Charsets.ISO_8859_1)
        private val DEFAULT_BOX = doubleArrayOf(0.0, 0.0, 612.0, 792.0)
    }
}

/** The stream filters other than Flate (which uses java.util.zip), and the predictors. */
internal object PdfFilters {
    /** Undoes a PNG (10-15) or TIFF (2) predictor, as given by a stream's /DecodeParms. */
    fun predict(d: ByteArray, parms: Map<String, Any?>?): ByteArray {
        val predictor = (parms?.get("Predictor") as? Double)?.toInt() ?: 1
        if (predictor < 2) return d
        val colors = ((parms?.get("Colors") as? Double)?.toInt() ?: 1).coerceIn(1, 32)
        val bpc = ((parms?.get("BitsPerComponent") as? Double)?.toInt() ?: 8).coerceIn(1, 16)
        val columns = ((parms?.get("Columns") as? Double)?.toInt() ?: 1).coerceIn(1, 1 shl 20)
        val bpp = maxOf(1, (colors * bpc + 7) / 8)
        val rowLen = (columns * colors * bpc + 7) / 8
        if (predictor == 2) {
            if (bpc != 8) return d
            val out = d.copyOf()
            var r = 0
            while (r < out.size) {
                for (i in r + bpp until min(r + rowLen, out.size)) out[i] = (out[i] + out[i - bpp]).toByte()
                r += rowLen
            }
            return out
        }
        // Each row is one filter byte and rowLen bytes, so the output is never longer than the input.
        val rows = (d.size + rowLen) / (rowLen + 1)
        val out = ByteArray(min(rows.toLong() * rowLen, d.size.toLong()).toInt())
        var p = 0
        var o = 0
        while (p < d.size && o < out.size) {
            val type = d[p++].toInt()
            val n = min(min(rowLen, d.size - p), out.size - o)
            for (i in 0 until n) {
                val x = d[p + i].toInt() and 0xFF
                val a = if (i >= bpp) out[o + i - bpp].toInt() and 0xFF else 0
                val b = if (o >= rowLen) out[o - rowLen + i].toInt() and 0xFF else 0
                val c = if (i >= bpp && o >= rowLen) out[o - rowLen + i - bpp].toInt() and 0xFF else 0
                out[o + i] = when (type) {
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    4 -> {
                        val pa = abs(b - c)
                        val pb = abs(a - c)
                        val pc = abs(a + b - 2 * c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> x
                }.toByte()
            }
            p += n
            o += n
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    fun lzw(src: ByteArray, earlyChange: Int, cap: Int): ByteArray {
        val out = ByteBuf(maxOf(64, src.size * 2))
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val length = IntArray(4096)
        for (i in 0 until 256) { suffix[i] = i.toByte(); length[i] = 1; prefix[i] = -1 }
        var next = 258
        var width = 9
        var prev = -1
        var bitBuf = 0L
        var bits = 0
        var p = 0
        val tmp = ByteArray(4096)
        while (out.size < cap) {
            while (bits < width && p < src.size) { bitBuf = (bitBuf shl 8) or (src[p++].toLong() and 0xFF); bits += 8 }
            if (bits < width) break
            val code = ((bitBuf shr (bits - width)) and ((1L shl width) - 1)).toInt()
            bits -= width
            if (code == 256) { next = 258; width = 9; prev = -1; continue }
            if (code == 257) break
            val known = code < next
            if (!known && (prev < 0 || code != next)) break
            val entry = if (known) code else prev
            // Write the entry (backwards through the prefix chain).
            val len = length[entry]
            var e = entry
            for (k in len - 1 downTo 0) { tmp[k] = suffix[e]; e = prefix[e] }
            val firstByte = tmp[0]
            out.add(tmp, 0, len)
            if (!known) out.add(firstByte.toInt())
            if (prev >= 0 && next < 4096) {
                prefix[next] = prev
                suffix[next] = firstByte
                length[next] = length[prev] + 1
                next++
            }
            prev = code
            if (next + earlyChange >= (1 shl width) && width < 12) width++
        }
        return out.toArray()
    }

    fun asciiHex(src: ByteArray): ByteArray {
        val out = ByteBuf(src.size / 2 + 1)
        var hi = -1
        for (b in src) {
            if (b == '>'.code.toByte()) break
            val h = PdfLexer.hexValue(b)
            if (h < 0) continue
            if (hi < 0) hi = h else { out.add((hi shl 4) or h); hi = -1 }
        }
        if (hi >= 0) out.add(hi shl 4)
        return out.toArray()
    }

    fun ascii85(src: ByteArray): ByteArray {
        val out = ByteBuf(src.size)
        var group = 0L
        var n = 0
        var i = 0
        if (src.size >= 2 && src[0] == '<'.code.toByte() && src[1] == '~'.code.toByte()) i = 2
        while (i < src.size) {
            val c = src[i++].toInt() and 0xFF
            if (c == '~'.code) break
            if (PdfLexer.isWhite(c)) continue
            if (c == 'z'.code && n == 0) { repeat(4) { out.add(0) }; continue }
            if (c < '!'.code || c > 'u'.code) continue
            group = group * 85 + (c - '!'.code)
            if (++n == 5) {
                for (k in 3 downTo 0) out.add(((group shr (8 * k)) and 0xFF).toInt())
                group = 0; n = 0
            }
        }
        if (n > 1) {
            for (k in n until 5) group = group * 85 + 84
            for (k in 3 downTo 5 - n) out.add(((group shr (8 * k)) and 0xFF).toInt())
        }
        return out.toArray()
    }

    fun runLength(src: ByteArray, cap: Int): ByteArray {
        val out = ByteBuf(src.size * 2 + 1)
        var i = 0
        while (i < src.size && out.size < cap) {
            val len = src[i++].toInt() and 0xFF
            if (len == 128) break
            if (len < 128) {
                val n = min(len + 1, src.size - i)
                out.add(src, i, n)
                i += n
            } else if (i < src.size) {
                val b = src[i++].toInt()
                repeat(257 - len) { out.add(b) }
            }
        }
        return out.toArray()
    }
}
