package com.bouzouki.core.song

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes PDF files object by object, for the PdfTab tests: a classic xref table, or (with
 * [objectStreams]) every non-stream object in one object stream and an xref stream with a PNG
 * predictor. Streams are deflated unless asked not to be. [encryption] encrypts the streams.
 */
internal class TestPdfWriter(private val objectStreams: Boolean = false, private val encryption: TestEncryption? = null) {
    private val bodies = ArrayList<ByteArray?>()
    private val streams = ArrayList<Boolean>()

    /** Reserves an object number, to be filled with [set] (for objects that point at each other). */
    fun reserve(): Int { bodies += null; streams += false; return bodies.size }

    fun set(num: Int, body: String) { bodies[num - 1] = body.toByteArray(Charsets.ISO_8859_1) }

    fun add(body: String): Int = reserve().also { set(it, body) }

    /** Adds a stream with dictionary entries [dict] (without << >>). */
    fun stream(dict: String, data: ByteArray, compress: Boolean = true): Int {
        val num = reserve()
        setStream(num, dict, data, compress)
        return num
    }

    fun setStream(num: Int, dict: String, data: ByteArray, compress: Boolean = true) {
        var d = if (compress) deflate(data) else data
        encryption?.let { d = it.encrypt(d, num) }
        val head = "<< $dict${if (compress) " /Filter /FlateDecode" else ""} /Length ${d.size} >>\nstream\n"
        bodies[num - 1] = head.toByteArray(Charsets.ISO_8859_1) + d + "\nendstream".toByteArray(Charsets.ISO_8859_1)
        streams[num - 1] = true
    }

    /** The finished file, with [root] as the catalog. */
    fun build(root: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        w("%PDF-1.7\n%âãÏÓ\n")
        val id = "<00112233445566778899AABBCCDDEEFF>"
        val encryptRef = encryption?.let { e -> " /Encrypt ${add(e.dictionary())} 0 R" } ?: ""
        if (!objectStreams) {
            val offsets = IntArray(bodies.size + 1)
            for (i in bodies.indices) {
                offsets[i + 1] = out.size()
                w("${i + 1} 0 obj\n"); out.write(bodies[i] ?: "null".toByteArray()); w("\nendobj\n")
            }
            val xref = out.size()
            w("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
            for (i in 1..bodies.size) w("%010d 00000 n \n".format(offsets[i]))
            w("trailer\n<< /Size ${bodies.size + 1} /Root $root 0 R /ID [$id $id]$encryptRef >>\nstartxref\n$xref\n%%EOF\n")
            return out.toByteArray()
        }
        // Non-stream objects (except the encryption dictionary) go into one object stream.
        val encNum = if (encryption != null) bodies.size else -1
        val packed = bodies.indices.filter { !streams[it] && it + 1 != encNum }
        val header = StringBuilder()
        val payload = ByteArrayOutputStream()
        for (i in packed) {
            header.append("${i + 1} ${payload.size()} ")
            payload.write(bodies[i] ?: "null".toByteArray()); payload.write('\n'.code)
        }
        val first = header.length
        val stm = stream("/Type /ObjStm /N ${packed.size} /First $first", header.toString().toByteArray() + payload.toByteArray())
        val xrefNum = reserve()
        val offsets = IntArray(bodies.size + 1)
        for (i in bodies.indices) {
            if (i in packed || i + 1 == xrefNum) continue
            offsets[i + 1] = out.size()
            w("${i + 1} 0 obj\n"); out.write(bodies[i]!!); w("\nendobj\n")
        }
        offsets[xrefNum] = out.size()
        // Rows of type (1 byte), offset or object stream (4), generation or index (2), PNG "Up" filtered.
        val rows = ByteArrayOutputStream()
        var prev = ByteArray(7)
        for (num in 0..bodies.size) {
            val row = ByteArray(7)
            val idx = packed.indexOf(num - 1)
            when {
                num == 0 -> row[0] = 0
                idx >= 0 -> { row[0] = 2; put(row, 1, stm, 4); put(row, 5, idx, 2) }
                else -> { row[0] = 1; put(row, 1, offsets[num], 4) }
            }
            rows.write(2)
            for (k in 0 until 7) rows.write(row[k] - prev[k])
            prev = row
        }
        val xrefData = deflate(rows.toByteArray())
        w("$xrefNum 0 obj\n<< /Type /XRef /Size ${bodies.size + 1} /W [1 4 2] /Root $root 0 R /ID [$id $id]$encryptRef " +
            "/Filter /FlateDecode /DecodeParms << /Columns 7 /Predictor 12 >> /Length ${xrefData.size} >>\nstream\n")
        out.write(xrefData)
        w("\nendstream\nendobj\nstartxref\n${offsets[xrefNum]}\n%%EOF\n")
        return out.toByteArray()
    }

    private fun put(row: ByteArray, at: Int, v: Int, n: Int) {
        for (k in 0 until n) row[at + k] = (v shr (8 * (n - 1 - k))).toByte()
    }

    companion object {
        fun deflate(d: ByteArray): ByteArray {
            val def = Deflater()
            def.setInput(d); def.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!def.finished()) out.write(buf, 0, def.deflate(buf))
            def.end()
            return out.toByteArray()
        }

        /**
         * Appends an incremental update to [base]: [objects] (number to body) replace or add objects,
         * with a new xref section and trailer pointing back with /Prev.
         */
        fun update(base: ByteArray, objects: Map<Int, String>, root: Int, size: Int): ByteArray {
            val text = String(base, Charsets.ISO_8859_1)
            val prev = text.substring(text.lastIndexOf("startxref") + 9).trim().lines().first().trim()
            val out = ByteArrayOutputStream()
            out.write(base)
            val offsets = HashMap<Int, Int>()
            for ((num, body) in objects) {
                offsets[num] = out.size()
                out.write("$num 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            }
            val xref = out.size()
            val sb = StringBuilder("xref\n")
            for ((num, off) in offsets.toSortedMap()) sb.append("$num 1\n%010d 00000 n \n".format(off))
            sb.append("trailer\n<< /Size $size /Root $root 0 R /Prev $prev >>\nstartxref\n$xref\n%%EOF\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            return out.toByteArray()
        }
    }
}

/**
 * The standard security handler with an empty user password, written from ISO 32000 for the
 * tests: RC4 40-bit (revision 2), AES-128 (revision 4) or AES-256 (revision 6). [userPassword]
 * other than empty makes a file that can't be opened without it. Without [topLength], an AES-128
 * dictionary leaves out its optional top-level /Length (the crypt filter's says 16 bytes).
 */
internal class TestEncryption(private val kind: Kind, private val userPassword: String = "", private val topLength: Boolean = true) {
    enum class Kind { RC4_40, AES_128, AES_256 }

    private val id0 = hex("00112233445566778899AABBCCDDEEFF")
    private val owner = ByteArray(32) { (it * 7 + 3).toByte() }
    private val p = -1036
    private val rnd = java.util.Random(7)
    private val fileKey: ByteArray
    private val u: ByteArray
    private var ue = ByteArray(0)

    init {
        val pw = (userPassword.toByteArray(Charsets.ISO_8859_1) + PAD).copyOf(32)
        when (kind) {
            Kind.AES_256 -> {
                fileKey = ByteArray(32) { (it * 13 + 5).toByte() }
                val validation = ByteArray(8) { (it + 1).toByte() }
                val keySalt = ByteArray(8) { (it + 21).toByte() }
                val pwb = userPassword.toByteArray(Charsets.UTF_8)
                u = hash6(pwb, validation) + validation + keySalt
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hash6(pwb, keySalt), "AES"), IvParameterSpec(ByteArray(16)))
                ue = c.doFinal(fileKey)
            }
            else -> {
                val n = if (kind == Kind.RC4_40) 5 else 16
                val md = MessageDigest.getInstance("MD5")
                md.update(pw); md.update(owner)
                md.update(byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte()))
                md.update(id0)
                var h = md.digest()
                if (kind != Kind.RC4_40) repeat(50) { h = MessageDigest.getInstance("MD5").digest(h.copyOf(n)) }
                fileKey = h.copyOf(n)
                u = if (kind == Kind.RC4_40) PdfCrypt.rc4(fileKey, PAD) else {
                    val m = MessageDigest.getInstance("MD5"); m.update(PAD); m.update(id0)
                    var x = PdfCrypt.rc4(fileKey, m.digest())
                    for (i in 1..19) x = PdfCrypt.rc4(ByteArray(n) { (fileKey[it].toInt() xor i).toByte() }, x)
                    x + ByteArray(16)
                }
            }
        }
    }

    fun dictionary(): String = when (kind) {
        Kind.RC4_40 -> "<< /Filter /Standard /V 1 /R 2 /O <${hex(owner)}> /U <${hex(u)}> /P $p >>"
        Kind.AES_128 -> "<< /Filter /Standard /V 4 /R 4${if (topLength) " /Length 128" else ""} /CF << /StdCF << /CFM /AESV2 /Length 16 /AuthEvent /DocOpen >> >> " +
            "/StmF /StdCF /StrF /StdCF /O <${hex(owner)}> /U <${hex(u)}> /P $p >>"
        Kind.AES_256 -> "<< /Filter /Standard /V 5 /R 6 /Length 256 /CF << /StdCF << /CFM /AESV3 /Length 32 /AuthEvent /DocOpen >> >> " +
            "/StmF /StdCF /StrF /StdCF /O <${hex(owner + ByteArray(16))}> /U <${hex(u)}> /UE <${hex(ue)}> /OE <${hex(ByteArray(32))}> " +
            "/Perms <${hex(ByteArray(16))}> /P $p >>"
    }

    fun encrypt(d: ByteArray, num: Int): ByteArray {
        if (kind == Kind.AES_256) return aes(fileKey, d)
        val md = MessageDigest.getInstance("MD5")
        md.update(fileKey)
        md.update(byteArrayOf(num.toByte(), (num shr 8).toByte(), (num shr 16).toByte(), 0, 0))
        if (kind == Kind.AES_128) md.update("sAlT".toByteArray())
        val key = md.digest().copyOf(minOf(fileKey.size + 5, 16))
        return if (kind == Kind.AES_128) aes(key, d) else PdfCrypt.rc4(key, d)
    }

    private fun aes(key: ByteArray, d: ByteArray): ByteArray {
        val iv = ByteArray(16).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return iv + c.doFinal(d)
    }

    private fun hash6(pw: ByteArray, salt: ByteArray): ByteArray {
        var k = MessageDigest.getInstance("SHA-256").digest(pw + salt)
        var round = 0
        while (true) {
            val unit = pw + k
            val k1 = ByteArray(64 * unit.size).also { for (i in 0 until 64) System.arraycopy(unit, 0, it, i * unit.size, unit.size) }
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOf(16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
            val e = c.doFinal(k1)
            val mod = java.math.BigInteger(1, e.copyOf(16)).mod(java.math.BigInteger.valueOf(3)).toInt()
            k = MessageDigest.getInstance(arrayOf("SHA-256", "SHA-384", "SHA-512")[mod]).digest(e)
            round++
            if (round >= 64 && (e.last().toInt() and 0xFF) <= round - 32) break
        }
        return k.copyOf(32)
    }

    companion object {
        private val PAD = hex("28BF4E5E4E758A4164004E56FFFA01082E2E00B6D0683E802F0CA9FE6453697A")
        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    }
}

/** A font for the test pages: a simple WinAnsi TrueType font with /Widths, or a Type0 Identity-H font with a /ToUnicode CMap. */
internal class TestFont(val resName: String, private val type0: Boolean, private val baseFont: String = "ABCDEF+ArialMT") {
    /** Advance widths per 1000, roughly Arial's. */
    fun width(c: Char): Int = when (c) {
        in '0'..'9' -> 556
        ' ', '/', '\\', '.' -> 278
        '(', ')' -> 333
        'l', 'i' -> 222
        's' -> 500
        'H' -> 722
        'P' -> 667
        else -> 556
    }

    /** [s] as a string operand in a content stream. */
    fun encode(s: String): String =
        if (type0) "<" + s.map { "%04X".format(gid(it)) }.joinToString("") + ">"
        else "(" + s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"

    /** Writes the font's objects; returns the font dictionary's object number. */
    fun write(w: TestPdfWriter): Int {
        if (!type0) {
            val widths = (32..126).joinToString(" ") { width(it.toChar()).toString() }
            return w.add("<< /Type /Font /Subtype /TrueType /BaseFont /$baseFont /FirstChar 32 /LastChar 126 /Widths [$widths] /Encoding /WinAnsiEncoding >>")
        }
        // Glyph ids in a made-up order, as embedded subsets have them.
        val digits = (0..9).joinToString(" ") { "<%04X>".format(0x30 + it) }
        val letters = CHARS.substring(10).mapIndexed { i, c -> "<%04X> <%04X>".format(gid(c), c.code) }
        val cmap = """
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
            /CMapName /Adobe-Identity-UCS def
            /CMapType 2 def
            1 begincodespacerange
            <0000> <FFFF>
            endcodespacerange
            2 beginbfrange
            <0000> <0000> <0000>
            <%04X> <%04X> [$digits]
            endbfrange
            ${letters.size} beginbfchar
            ${letters.joinToString("\n")}
            endbfchar
            endcmap
            CMapName currentdict /CMap defineresource pop
            end
            end
        """.trimIndent().format(gid('0'), gid('9'))
        val toUnicode = w.stream("", cmap.toByteArray(Charsets.ISO_8859_1))
        val wArray = CHARS.map { width(it) }.joinToString(" ")
        val descendant = w.add("<< /Type /Font /Subtype /CIDFontType2 /BaseFont /$baseFont /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /DW 1000 /W [${gid(CHARS[0])} [$wArray]] /CIDToGIDMap /Identity >>")
        return w.add("<< /Type /Font /Subtype /Type0 /BaseFont /$baseFont /Encoding /Identity-H /DescendantFonts [$descendant 0 R] /ToUnicode $toUnicode 0 R >>")
    }

    private fun gid(c: Char): Int = 7 + CHARS.indexOf(c).also { require(it >= 0) { "no glyph for $c" } }

    companion object {
        private const val CHARS = "0123456789()hpHPsl/\\.TABabcdefgijkmnoqrtuvwxyzE "
    }
}

/**
 * Writes sheet music (5-line staves, no tab) for the PdfNotation tests, laid out from a small text language the way
 * notation programs export it: [Style.OPUS] as Sibelius does (a Sonata-style font, "OpusStd", by MacRoman codes, flat
 * beams as filled rectangles, dots in "OpusSpecialStd"), [Style.SMUFL] as MuseScore does (a SMuFL font, "Leland", by
 * code point through a /ToUnicode map, sloping beams), [Style.SHAPES] with the noteheads drawn as filled ellipses and
 * rings. Every melody written with it is made up for the tests.
 *
 * A [Line] is one system; its music is tokens separated by spaces:
 * - a note `C4/4`: letter, accidental drawn (`#`, `b`, `n` or none), octave as written, `/` and the length (1, 2, 4,
 *   8, 16), `.` dotted, `~` tied to the next note (`~>` to the end of the system); `g` before it: a grace note;
 * - a chord `D4+F#4+A4/4` (one stem); a rest `r/4`, a whole-bar rest `R`;
 * - `3:` before the first of three notes: a triplet; `|` a barline, `|:` and `:|` repeat barlines.
 */
internal class TestScore(private val style: Style) {
    enum class Style { OPUS, SMUFL, SHAPES }

    /** One system: [music] (tokens), the clef (treble, treble8, bass), the key (sharps > 0, flats < 0), the time signature, a tie into it, a metronome mark. */
    class Line(
        val music: String, val clef: String = "treble", val key: Int = 0, val time: Pair<Int, Int>? = null,
        val tieIn: Boolean = false, val tempo: Int? = null,
    )

    private val sp = 5.0
    private val size = 4 * sp
    private val x0 = 50.0
    private val sb = StringBuilder()
    private var top = 0.0

    private fun f(v: Double) = "%.3f".format(java.util.Locale.ROOT, v)
    private fun py(y: Double) = f(H - y)
    private fun y(pos: Int) = top + 4 * sp - pos * sp / 2

    // ---- Fonts ----

    /** The music font's character (Opus) or code point (SMuFL) for each symbol. */
    private val opus = mapOf(
        "black" to 'œ', "half" to '˙', "whole" to 'w', "treble" to '&', "bass" to '?', "sharp" to '#', "flat" to 'b',
        "natural" to 'n', "r1" to '∑', "r2" to 'Ó', "r4" to 'Œ', "r8" to '‰', "r16" to '≈', "flag8u" to 'j', "flag8d" to 'J',
        "flag16u" to 'r', "flag16d" to 'R',
    ) + (0..9).associate { "t$it" to ('0' + it) }
    private val smufl = mapOf(
        "black" to 0xE0A4, "half" to 0xE0A3, "whole" to 0xE0A2, "treble" to 0xE050, "treble8" to 0xE052, "bass" to 0xE062,
        "sharp" to 0xE262, "flat" to 0xE260, "natural" to 0xE261, "r1" to 0xE4E3, "r2" to 0xE4E4, "r4" to 0xE4E5, "r8" to 0xE4E6,
        "r16" to 0xE4E7, "flag8u" to 0xE240, "flag8d" to 0xE241, "flag16u" to 0xE242, "flag16d" to 0xE243, "dot" to 0xE1E7,
        "quarterNote" to 0xECA5,
    ) + (0..9).associate { "t$it" to 0xE080 + it }
    private val smuflCodes = smufl.values.distinct().sorted()

    /** Advance of a symbol, in em (the font size is 4 line spacings). */
    private fun advance(name: String): Double = when (name) {
        "black", "half" -> if (style == Style.OPUS) 0.42 else 0.295
        "whole" -> if (style == Style.OPUS) 0.6 else 0.42
        "treble", "treble8", "bass" -> 0.7
        "sharp" -> 0.3
        "flat", "natural" -> 0.25
        "dot" -> 0.1
        else -> if (name.startsWith("t")) 0.45 else 0.3
    }

    /** Visual width of a notehead. */
    private val hw = 1.2 * sp

    private fun glyph(name: String, x: Double, baseline: Double, scale: Double = 1.0) {
        val s = f(size * scale)
        if (style == Style.OPUS) {
            val c = opus[name] ?: error("no Opus glyph $name")
            val code = PdfEncodings.MAC_ROMAN.indexOfFirst { it == c.toString() }.takeIf { it >= 32 } ?: c.code
            sb.append("BT /M1 $s Tf ${f(x)} ${py(baseline)} Td (\\%03o) Tj ET\n".format(code))
        } else {
            val cp = smufl[name] ?: error("no SMuFL glyph $name")
            sb.append("BT /M1 $s Tf ${f(x)} ${py(baseline)} Td <%04X> Tj ET\n".format(1 + smuflCodes.indexOf(cp)))
        }
    }

    private fun dotGlyph(x: Double, yy: Double) {
        if (style == Style.OPUS) sb.append("BT /M2 ${f(size)} Tf ${f(x)} ${py(yy)} Td (\\252) Tj ET\n") else glyph("dot", x, yy)
    }

    private fun text(s: String, x: Double, baseline: Double, sz: Double) {
        sb.append("BT /T1 ${f(sz)} Tf ${f(x)} ${py(baseline)} Td ${TEXT.encode(s)} Tj ET\n")
    }

    private fun hline(xa: Double, xb: Double, yy: Double, w: Double = 0.5) { sb.append("${f(w)} w ${f(xa)} ${py(yy)} m ${f(xb)} ${py(yy)} l S\n") }
    private fun vline(x: Double, ya: Double, yb: Double, w: Double = 0.45) { sb.append("${f(w)} w ${f(x)} ${py(ya)} m ${f(x)} ${py(yb)} l S\n") }

    /** A beam from ([xa], [ya]) to ([xb], [yb]), its middle at those heights: a flat filled rectangle, or a sloping quadrilateral. */
    private fun beam(xa: Double, ya: Double, xb: Double, yb: Double) {
        val t = 0.25 * sp
        if (style == Style.OPUS) sb.append("${f(xa)} ${py(ya + t)} ${f(xb - xa)} ${f(2 * t)} re f\n")
        else sb.append("${f(xa)} ${py(ya - t)} m ${f(xb)} ${py(yb - t)} l ${f(xb)} ${py(yb + t)} l ${f(xa)} ${py(ya + t)} l h f\n")
    }

    /** A tie: a filled crescent from [xa] to [xb] at height [yy], bulging down ([down]) or up. */
    private fun tie(xa: Double, xb: Double, yy: Double, down: Boolean) {
        val d = (xb - xa) / 3
        val s = if (down) 1.0 else -1.0
        fun pt(x: Double, dy: Double) = "${f(x)} ${py(yy + s * dy)}"
        sb.append("${pt(xa, 0.0)} m ${pt(xa + d, 3.0)} ${pt(xb - d, 3.0)} ${pt(xb, 0.0)} c ${pt(xb - d, 2.2)} ${pt(xa + d, 2.2)} ${pt(xa, 0.0)} c f\n")
    }

    /** A filled ellipse ([ring]: with a hole, filled even-odd) centred on ([cx], [cy]). */
    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, ring: Boolean) {
        fun one(rx: Double, ry: Double) {
            val kx = 0.55 * rx
            val ky = 0.55 * ry
            fun pt(x: Double, yy: Double) = "${f(x)} ${py(yy)}"
            sb.append("${pt(cx + rx, cy)} m ${pt(cx + rx, cy + ky)} ${pt(cx + kx, cy + ry)} ${pt(cx, cy + ry)} c ")
            sb.append("${pt(cx - kx, cy + ry)} ${pt(cx - rx, cy + ky)} ${pt(cx - rx, cy)} c ${pt(cx - rx, cy - ky)} ${pt(cx - kx, cy - ry)} ${pt(cx, cy - ry)} c ")
            sb.append("${pt(cx + kx, cy - ry)} ${pt(cx + rx, cy - ky)} ${pt(cx + rx, cy)} c h\n")
        }
        one(rx, ry)
        if (ring) one(0.6 * rx, 0.5 * ry)
        sb.append(if (ring) "f*\n" else "f\n")
    }

    // ---- Notes ----

    private class Note(
        val letters: List<Pair<Int, Int>>, val accidentals: List<Char?>, val len: Int, val dotted: Boolean, val grace: Boolean,
        val tie: Int, val triplet: Boolean, val rest: Boolean = false, val barRest: Boolean = false, val bar: String? = null,
    ) {
        var x = 0.0
        var positions = IntArray(0)
        var up = true
        var tip = 0.0
        var stemX = 0.0
    }

    private val noteRe = Regex("""^(g?)([A-G])([#bn]?)(\d)$""")

    private fun parse(token: String, triplet: Boolean): Note {
        if (token == "R") return Note(emptyList(), emptyList(), 1, false, false, 0, false, rest = true, barRest = true)
        val (body, lenPart) = token.split('/', limit = 2).let { it[0] to it[1] }
        var lp = lenPart
        var tie = 0
        if (lp.endsWith("~>")) { tie = 2; lp = lp.dropLast(2) } else if (lp.endsWith("~")) { tie = 1; lp = lp.dropLast(1) }
        val dotted = lp.endsWith(".")
        val len = lp.trimEnd('.').toInt()
        if (body == "r") return Note(emptyList(), emptyList(), len, dotted, false, 0, triplet, rest = true)
        var grace = false
        val letters = ArrayList<Pair<Int, Int>>()
        val accs = ArrayList<Char?>()
        for (part in body.split('+')) {
            val m = noteRe.matchEntire(part) ?: error("bad note $part")
            if (m.groupValues[1] == "g") grace = true
            letters += "CDEFGAB".indexOf(m.groupValues[2][0]) to m.groupValues[4].toInt()
            accs += m.groupValues[3].firstOrNull()
        }
        return Note(letters, accs, len, dotted, grace, tie, triplet)
    }

    private fun length(n: Note) = 4.0 / n.len * (if (n.dotted) 1.5 else 1.0) * (if (n.triplet) 2.0 / 3 else 1.0)

    /** Draws one system with its staff's top line at [top]. */
    private fun system(line: Line, top: Double) {
        this.top = top
        val clefRef = if (line.clef == "bass") 18 else 30 // the diatonic step of the bottom line, as written
        // Clef, key, time, tempo.
        var x = x0 + 6
        when (line.clef) {
            "bass" -> glyph("bass", x, y(6))
            "treble8" -> if (style == Style.OPUS) { glyph("treble", x, y(2)); text("8", x + 5, y(0) + 3.2 * sp, 8.0) } else glyph("treble8", x, y(2))
            else -> glyph("treble", x, y(2))
        }
        x += 0.7 * size + 4
        val sharps = intArrayOf(8, 5, 9, 6, 4, 7, 3)
        val flats = intArrayOf(4, 7, 3, 6, 2, 5, 1)
        val shift = if (line.clef == "bass") -2 else 0
        for (i in 0 until kotlin.math.abs(line.key)) {
            glyph(if (line.key > 0) "sharp" else "flat", x, y((if (line.key > 0) sharps[i] else flats[i]) + shift))
            x += 1.3 * sp
        }
        x += 4
        line.time?.let { (n, d) ->
            glyph("t$n", x, y(6)); glyph("t$d", x, y(2))
            x += 0.45 * size + 6
        }
        line.tempo?.let { bpm ->
            if (style == Style.OPUS) sb.append("BT /Q1 12 Tf ${f(x)} ${py(top - 3 * sp)} Td (q) Tj ET\n") else glyph("quarterNote", x, top - 3 * sp, 0.6)
            text("= $bpm", x + 8, top - 3 * sp, 10.0)
        }
        x += 8
        val tieInFrom = x - 10
        // Notes, laid out left to right.
        val notes = ArrayList<Note>()
        var triplets = 0
        for (t in line.music.trim().split(Regex("\\s+"))) {
            if (t == "|" || t == "|:" || t == ":|") {
                if (t == "|:") x += 4
                notes += Note(emptyList(), emptyList(), 0, false, false, 0, false, bar = t).also { it.x = x }
                x += if (t == "|:") 14 else 10
                continue
            }
            if (t.startsWith("3:")) triplets = 3
            val n = parse(t.removePrefix("3:"), triplets > 0)
            if (triplets > 0) triplets--
            if (n.accidentals.any { it != null }) x += 7
            n.x = x
            n.positions = IntArray(n.letters.size) { n.letters[it].first + 7 * n.letters[it].second - clefRef }
            notes += n
            x += when {
                n.grace -> 10.0
                n.barRest -> 40.0
                else -> when (n.len) { 1 -> 40.0; 2 -> 30.0; 4 -> 22.0; 8 -> 16.0; 16 -> 12.0; else -> 10.0 } + (if (n.dotted) 5.0 else 0.0)
            }
        }
        val last = notes.lastOrNull()
        val x1 = if (last?.bar != null) last.x else x + 6
        // Staff and barlines.
        for (i in 0 until 5) hline(x0, x1, top + i * sp)
        vline(x0, top, top + 4 * sp, 0.7)
        for (b in notes) {
            when (b.bar) {
                "|" -> vline(b.x, top, top + 4 * sp, 0.7)
                "|:" -> { vline(b.x - 3, top, top + 4 * sp, 2.5); vline(b.x, top, top + 4 * sp, 0.7); repeatDots(b.x + 3) }
                ":|" -> { repeatDots(b.x - 6); vline(b.x - 3, top, top + 4 * sp, 0.7); vline(b.x, top, top + 4 * sp, 2.5) }
            }
        }
        if (last?.bar == null) vline(x1, top, top + 4 * sp, 0.7)
        // Rhythm: beam groups (quavers and shorter within one beat of a bar); the others get flags.
        val real = notes.filter { it.bar == null }
        val groups = ArrayList<List<Note>>()
        var group = ArrayList<Note>()
        var beat = 0.0
        fun close() { if (group.isNotEmpty()) groups += group; group = ArrayList() }
        for (n in notes) {
            if (n.bar != null) { close(); beat = 0.0; continue }
            if (n.grace || n.barRest) continue
            if (n.rest || n.len < 8) { close(); if (!n.rest) groups += listOf(n) } else group.add(n)
            beat += length(n)
            if (kotlin.math.abs(beat - kotlin.math.round(beat)) < 1e-6) close()
        }
        close()
        for (n in real) drawNote(n)
        for (g in groups) drawRhythm(g)
        for (n in real.filter { it.grace }) graceStem(n)
        // Triplet numbers over each triplet's middle note.
        val trip = real.filter { it.triplet && !it.rest }
        for (k in 0 until trip.size / 3) {
            val three = trip.subList(3 * k, 3 * k + 3)
            text("3", three[1].x + 1, minOf(top - 1.5 * sp, three.minOf { minOf(it.tip, y(it.positions.max()) - sp) } - 4), 9.0)
        }
        // Ties.
        val sounding = real.filter { !it.rest && !it.grace }
        for ((i, n) in sounding.withIndex()) {
            val yy = y(n.positions.max()) + (if (n.up) 0.7 else -0.7) * sp
            if (n.tie == 1) tie(n.x + hw + 1, sounding[i + 1].x - 1, yy, n.up)
            else if (n.tie == 2) tie(n.x + hw + 1, x1 - 2, yy, n.up)
        }
        if (line.tieIn) {
            val first = sounding.first()
            tie(tieInFrom, first.x - 1, y(first.positions.max()) + (if (first.up) 0.7 else -0.7) * sp, first.up)
        }
    }

    private fun repeatDots(x: Double) {
        for (pos in listOf(3, 5)) {
            if (style == Style.OPUS) dotGlyph(x, y(pos)) else ellipse(x + 0.25 * sp, y(pos), 0.25 * sp, 0.25 * sp, false)
        }
    }

    private fun drawNote(n: Note) {
        if (n.rest) {
            glyph(if (n.barRest) "r1" else "r${n.len}", n.x, if (n.barRest || n.len == 1) y(6) else y(4))
            return
        }
        val scale = if (n.grace) 0.6 else 1.0
        val head = when (n.len) { 1 -> "whole"; 2 -> "half"; else -> "black" }
        n.up = n.positions.average() < 4 || n.grace
        for ((k, p) in n.positions.withIndex()) {
            // Ledger lines.
            var lp = -2
            while (lp >= p) { hline(n.x - 0.4 * sp, n.x + hw * scale + 0.4 * sp, y(lp), 0.6); lp -= 2 }
            lp = 10
            while (lp <= p) { hline(n.x - 0.4 * sp, n.x + hw * scale + 0.4 * sp, y(lp), 0.6); lp += 2 }
            if (style == Style.SHAPES) ellipse(n.x + hw * scale / 2, y(p), hw * scale / 2, 0.45 * sp * scale, head != "black")
            else glyph(head, n.x, y(p), scale)
            n.accidentals[k]?.let { a -> glyph(when (a) { '#' -> "sharp"; 'b' -> "flat"; else -> "natural" }, n.x - 7, y(p), scale) }
            if (n.dotted) dotGlyph(n.x + hw + 0.5 * sp, if (p % 2 != 0) y(p) else y(p + 1))
        }
        if (head == "whole") return
        n.stemX = stemX(n, n.up, scale)
        n.tip = if (n.up) y(n.positions.max()) - 3.5 * sp * scale else y(n.positions.min()) + 3.5 * sp * scale
    }

    private fun stemX(n: Note, up: Boolean, scale: Double = 1.0) =
        if (up) n.x + (if (style == Style.OPUS) 6.66 else hw - 0.25) * scale else n.x + 0.25

    private fun graceStem(n: Note) {
        vline(n.stemX, y(n.positions.max()) - 0.2, n.tip)
        glyph("flag8u", n.stemX - 0.1, n.tip + 0.6 * sp, 0.6)
    }

    /** Stems, then a beam (and second beams for semiquavers) for a group of two or more, or a flag for one alone. */
    private fun drawRhythm(g: List<Note>) {
        if (g.size == 1) {
            val n = g[0]
            stem(n)
            if (n.len >= 8) glyph((if (n.len >= 16) "flag16" else "flag8") + (if (n.up) "u" else "d"), n.stemX - 0.1, if (n.up) n.tip + sp else n.tip - sp)
            return
        }
        val up = g.map { it.positions.average() }.average() < 4
        for (n in g) { n.up = up; n.stemX = stemX(n, up) }
        val a = g.first()
        val b = g.last()
        val heads = g.map { y(if (up) it.positions.max() else it.positions.min()) }
        val slope = if (style == Style.OPUS) 0.0 else ((heads.last() - heads.first()) * 0.25).coerceIn(-sp, sp)
        // The beam's height at the first stem, with every stem at least 3 spaces long.
        var base = if (up) heads.min() - 3.5 * sp else heads.max() + 3.5 * sp
        fun at(x: Double) = base + slope * (x - a.stemX) / (b.stemX - a.stemX)
        for ((k, n) in g.withIndex()) {
            val t = at(n.stemX)
            if (up && heads[k] - t < 3 * sp) base -= 3 * sp - (heads[k] - t)
            if (!up && t - heads[k] < 3 * sp) base += 3 * sp - (t - heads[k])
        }
        for (n in g) { n.tip = at(n.stemX); stem(n) }
        val inward = if (up) 1.0 else -1.0
        beam(a.stemX - 0.2, a.tip + inward * 0.25 * sp, b.stemX + 0.2, b.tip + inward * 0.25 * sp)
        // Second beams between neighbouring semiquavers; a stub for one alone.
        for ((k, n) in g.withIndex()) {
            if (n.len < 16) continue
            val off = inward * sp
            val next = g.getOrNull(k + 1)
            if (next != null && next.len >= 16) beam(n.stemX - 0.2, n.tip + off, next.stemX + 0.2, next.tip + off)
            else if ((g.getOrNull(k - 1)?.len ?: 0) < 16) {
                val xa = if (k > 0) n.stemX - 1.2 * sp else n.stemX - 0.2
                val xb = if (k > 0) n.stemX + 0.2 else n.stemX + 1.2 * sp
                beam(xa, at(xa) + off, xb, at(xb) + off)
            }
        }
    }

    private fun stem(n: Note) {
        val headY = if (n.up) y(n.positions.min()) else y(n.positions.max())
        vline(n.stemX, headY + (if (n.up) -0.2 else 0.2), n.tip)
    }

    /** The music of [pages] (systems one under another) as a PDF; [extra] adds to a page's content (page index, the height below its systems). */
    fun pdf(pages: List<List<Line>>, extra: (Int, Double) -> String = { _, _ -> "" }): ByteArray {
        val w = TestPdfWriter()
        val music = if (style == Style.OPUS) {
            val widths = (32..255).joinToString(" ") { code ->
                val c = PdfEncodings.MAC_ROMAN[code]?.singleOrNull()
                val name = opus.entries.firstOrNull { it.value == c }?.key
                ((if (name != null) advance(name) else 0.3) * 1000).toInt().toString()
            }
            w.add("<< /Type /Font /Subtype /TrueType /BaseFont /ABCDEF+OpusStd /FirstChar 32 /LastChar 255 /Widths [$widths] /Encoding /MacRomanEncoding >>")
        } else {
            val name = if (style == Style.SHAPES) "ABCDEF+Bravura" else "ABCDEF+Leland"
            val bf = smuflCodes.mapIndexed { i, cp -> "<%04X> <%04X>".format(i + 1, cp) }
            val cmap = "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n" +
                "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n${bf.size} beginbfchar\n${bf.joinToString("\n")}\nendbfchar\nendcmap\n" +
                "CMapName currentdict /CMap defineresource pop\nend\nend\n"
            val toUnicode = w.stream("", cmap.toByteArray(Charsets.ISO_8859_1))
            val wArray = smuflCodes.joinToString(" ") { cp -> (advance(smufl.entries.first { it.value == cp }.key) * 1000).toInt().toString() }
            val desc = w.add("<< /Type /Font /Subtype /CIDFontType2 /BaseFont /$name /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /DW 300 /W [1 [$wArray]] /CIDToGIDMap /Identity >>")
            w.add("<< /Type /Font /Subtype /Type0 /BaseFont /$name /Encoding /Identity-H /DescendantFonts [$desc 0 R] /ToUnicode $toUnicode 0 R >>")
        }
        val special = w.add("<< /Type /Font /Subtype /TrueType /BaseFont /ABCDEF+OpusSpecialStd /FirstChar 32 /LastChar 255 /Widths [${(32..255).joinToString(" ") { "132" }}] /Encoding /MacRomanEncoding >>")
        val opusText = w.add("<< /Type /Font /Subtype /TrueType /BaseFont /ABCDEF+OpusTextStd /FirstChar 32 /LastChar 126 /Widths [${(32..126).joinToString(" ") { "500" }}] /Encoding /WinAnsiEncoding >>")
        val textFont = TEXT.write(w)
        val pagesNode = w.reserve()
        val kids = ArrayList<Int>()
        for ((pi, systems) in pages.withIndex()) {
            sb.setLength(0)
            var t = 120.0
            for (s in systems) { system(s, t); t += 110.0 }
            sb.append(extra(pi, t))
            val content = w.stream("", sb.toString().toByteArray(Charsets.ISO_8859_1))
            kids += w.add("<< /Type /Page /Parent $pagesNode 0 R /MediaBox [0 0 595 842] /Contents $content 0 R " +
                "/Resources << /Font << /M1 $music 0 R /M2 $special 0 R /Q1 $opusText 0 R /T1 $textFont 0 R >> >> >>")
        }
        w.set(pagesNode, "<< /Type /Pages /Kids [${kids.joinToString(" ") { "$it 0 R" }}] /Count ${kids.size} >>")
        return w.build(w.add("<< /Type /Catalog /Pages $pagesNode 0 R >>"))
    }

    companion object {
        const val H = 842.0
        val TEXT = TestFont("T1", false)
    }
}
