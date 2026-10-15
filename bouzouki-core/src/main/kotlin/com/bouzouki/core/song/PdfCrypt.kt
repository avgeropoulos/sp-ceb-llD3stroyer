package com.bouzouki.core.song

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decryption for PDFs protected by the standard security handler with an empty user password:
 * the "can open, can't copy or print" protection music sites often add. RC4 (40 to 128 bit), AES-128
 * and AES-256 (revisions 2 to 6). Only streams are decrypted: strings inside content streams come
 * out with them, and nothing PdfTab reads lives in an encrypted string outside a stream.
 */
internal class PdfCrypt private constructor(private val key: ByteArray, private val method: Method, private val encryptMetadata: Boolean) {
    enum class Method { NONE, RC4, AES128, AES256 }

    /** [d], the bytes of stream [num] [gen], decrypted; null if they can't be. */
    fun decrypt(d: ByteArray, num: Int, gen: Int, dict: Map<String, Any?>): ByteArray? {
        if (method == Method.NONE) return d
        if (!encryptMetadata && pdfName(dict["Type"]) == "Metadata") return d
        // A stream can opt out with its own Identity crypt filter.
        val filters = dict["Filter"].let { if (it is List<*>) it.map { f -> pdfName(f) } else listOf(pdfName(it)) }
        if ("Crypt" in filters) return d
        return try {
            when (method) {
                Method.AES256 -> aes(key, d)
                Method.AES128 -> aes(objectKey(num, gen, true), d)
                else -> rc4(objectKey(num, gen, false), d)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun objectKey(num: Int, gen: Int, aes: Boolean): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(key)
        md.update(byteArrayOf(num.toByte(), (num shr 8).toByte(), (num shr 16).toByte(), gen.toByte(), (gen shr 8).toByte()))
        if (aes) md.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54)) // "sAlT"
        return md.digest().copyOf(minOf(key.size + 5, 16))
    }

    companion object {
        private val PAD = intArrayOf(
            0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
        ).map { it.toByte() }.toByteArray()

        /**
         * The decryptor for encryption dictionary [enc] and file identifier [id], or null if the
         * file needs a password or uses another security handler.
         */
        fun open(enc: Map<String, Any?>?, id: Any?): PdfCrypt? = try { create(enc, id) } catch (e: Exception) { null }

        private fun create(enc: Map<String, Any?>?, id: Any?): PdfCrypt? {
            if (enc == null || pdfName(enc["Filter"]) != "Standard") return null
            val v = (enc["V"] as? Double)?.toInt() ?: 0
            val r = (enc["R"] as? Double)?.toInt() ?: 2
            val o = (enc["O"] as? PdfString)?.bytes ?: return null
            val u = (enc["U"] as? PdfString)?.bytes ?: return null
            val p = (enc["P"] as? Double)?.toLong()?.toInt() ?: 0
            val encryptMetadata = enc["EncryptMetadata"] != false
            val id0 = ((id as? List<*>)?.firstOrNull() as? PdfString)?.bytes ?: ByteArray(0)
            val stmF = pdfName(enc["StmF"]) ?: "Identity"
            val filter = pdfDict(pdfDict(enc["CF"])?.get(stmF))
            val method = when (v) {
                1, 2 -> Method.RC4
                4 -> {
                    if (stmF == "Identity") Method.NONE
                    else when (pdfName(filter?.get("CFM"))) {
                        "AESV2" -> Method.AES128
                        "V2" -> Method.RC4
                        "None" -> Method.NONE
                        else -> return null
                    }
                }
                5 -> Method.AES256
                else -> return null
            }
            if (r >= 5) {
                val ue = (enc["UE"] as? PdfString)?.bytes ?: return null
                if (u.size < 48 || ue.size < 32) return null
                val check = hashR6(r, u.copyOfRange(32, 40))
                if (!check.contentEquals(u.copyOf(32))) return null
                val intermediate = hashR6(r, u.copyOfRange(40, 48))
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(intermediate, "AES"), IvParameterSpec(ByteArray(16)))
                return PdfCrypt(c.doFinal(ue, 0, 32), method, encryptMetadata)
            }
            // The key length in bytes. For V 4 the top-level /Length is optional: then it's the crypt filter's
            // (in bytes, though some writers give bits), else 128 bits, as pdf.js reads it. AES-128 needs 16.
            val bits = (enc["Length"] as? Double)?.toInt()
                ?: if (v == 4) (filter?.get("Length") as? Double)?.toInt()?.let { if (it < 40) it * 8 else it } ?: 128 else 40
            val length = when {
                method == Method.AES128 -> 16
                v == 1 || r == 2 -> 5
                else -> (bits / 8).coerceIn(5, 16)
            }
            val md = MessageDigest.getInstance("MD5")
            md.update(PAD)
            md.update(o, 0, minOf(o.size, 32))
            md.update(byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte()))
            md.update(id0)
            if (r >= 4 && !encryptMetadata) md.update(byteArrayOf(-1, -1, -1, -1))
            var h = md.digest()
            if (r >= 3) repeat(50) { h = MessageDigest.getInstance("MD5").digest(h.copyOf(length)) }
            val key = h.copyOf(length)
            // Check the empty password against /U.
            val ok = if (r == 2) {
                rc4(key, PAD).contentEquals(u.copyOf(32))
            } else {
                val m = MessageDigest.getInstance("MD5")
                m.update(PAD)
                m.update(id0)
                var x = rc4(key, m.digest())
                for (i in 1..19) x = rc4(ByteArray(key.size) { (key[it].toInt() xor i).toByte() }, x)
                u.size >= 16 && x.contentEquals(u.copyOf(16))
            }
            return if (ok) PdfCrypt(key, method, encryptMetadata) else null
        }

        /** The revision 5/6 hash of the empty password with [salt] (ISO 32000-2, algorithm 2.B). */
        private fun hashR6(r: Int, salt: ByteArray): ByteArray {
            var k = MessageDigest.getInstance("SHA-256").digest(salt)
            if (r == 5) return k
            var round = 0
            while (true) {
                val k1 = ByteArray(64 * k.size)
                for (i in 0 until 64) System.arraycopy(k, 0, k1, i * k.size, k.size)
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOf(16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
                val e = c.doFinal(k1)
                var sum = 0
                for (i in 0 until 16) sum += e[i].toInt() and 0xFF
                k = MessageDigest.getInstance(arrayOf("SHA-256", "SHA-384", "SHA-512")[sum % 3]).digest(e)
                round++
                if (round >= 64 && (e[e.size - 1].toInt() and 0xFF) <= round - 32) break
            }
            return k.copyOf(32)
        }

        fun rc4(key: ByteArray, d: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 255
                val t = s[i]; s[i] = s[j]; s[j] = t
            }
            var i = 0
            j = 0
            val out = ByteArray(d.size)
            for (k in d.indices) {
                i = (i + 1) and 255
                j = (j + s[i]) and 255
                val t = s[i]; s[i] = s[j]; s[j] = t
                out[k] = (d[k].toInt() xor s[(s[i] + s[j]) and 255]).toByte()
            }
            return out
        }

        /** AES-CBC with the IV in the first 16 bytes and PKCS#7 padding (tolerating a missing one). */
        private fun aes(key: ByteArray, d: ByteArray): ByteArray {
            if (d.size < 32) return ByteArray(0)
            val n = (d.size - 16) / 16 * 16
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(d, 0, 16))
            val out = c.doFinal(d, 16, n)
            val pad = out.last().toInt() and 0xFF
            return if (pad in 1..16 && pad <= out.size) out.copyOf(out.size - pad) else out
        }
    }
}
