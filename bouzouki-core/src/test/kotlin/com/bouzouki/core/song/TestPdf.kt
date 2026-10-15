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
 * other than empty makes a file that can't be opened without it.
 */
internal class TestEncryption(private val kind: Kind, private val userPassword: String = "") {
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
        Kind.AES_128 -> "<< /Filter /Standard /V 4 /R 4 /Length 128 /CF << /StdCF << /CFM /AESV2 /Length 16 /AuthEvent /DocOpen >> >> " +
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
