package com.bouzouki

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Tab files the player opens (📝 Tab → Open a tab file) or another app hands over (Share or Open
 * with Μπουζούκι, from WhatsApp, Files, Gmail or Drive). They're read at once, off the main thread,
 * while the app may still read them (a shared file's permission lasts only as long as the screen it
 * came to), up to [MAX_BYTES]. A PDF is copied into the app's own files ([incomingFile]) and read
 * with [com.bouzouki.core.song.PdfTab] when it's lined up ([SongLines]); a text file is decoded
 * (UTF-8, or the Greek code page older files use) and goes through the paste flow.
 */
object TabFiles {
    /** A tab that came in, with the file's [name] (as the player knows it; empty for a tab's text shared). */
    sealed interface Incoming {
        val name: String

        class Text(override val name: String, val text: String) : Incoming

        /** A PDF, copied to [file] in the app's own files. */
        class Pdf(override val name: String, val file: File) : Incoming
    }

    /** What went wrong reading a tab file, in a sentence for the player. */
    class Problem(message: String) : Exception(message)

    /**
     * Reads [uri] (of MIME [type], if known) off the main thread; [done] gets the tab, or a [Problem], on the
     * main thread. Incoming PDFs other than [keep] (one still being tried) are deleted first.
     */
    fun read(context: Context, uri: Uri, type: String?, keep: File?, done: (Result<Incoming>) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = try {
                Result.success(read(app, uri, type, keep))
            } catch (e: Problem) {
                Result.failure(e)
            } catch (_: OutOfMemoryError) {
                Result.failure(Problem("That file is too big for the phone's memory."))
            } catch (e: Exception) {
                Result.failure(Problem("Couldn't read the file (${e.message ?: e.javaClass.simpleName})."))
            }
            main.post { done(result) }
        }, "tab-file").start()
    }

    private fun read(context: Context, uri: Uri, type: String?, keep: File?): Incoming {
        val resolver = context.contentResolver
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "tab"
        val bytes = try {
            (resolver.openInputStream(uri) ?: throw Problem("That file couldn't be opened.")).use { capped(it, MAX_BYTES) }
        } catch (_: SecurityException) {
            throw Problem("Μπουζούκι wasn't allowed to read that file. Share it again, or save it and use 📝 Tab → Open a tab file.")
        } catch (_: FileNotFoundException) {
            throw Problem("That file isn't there any more (moved or deleted?).")
        } catch (e: IOException) {
            throw Problem("Couldn't read the file (${e.message ?: "read error"}).")
        } ?: throw Problem("That file is too big for a tab (over ${MAX_BYTES / 1_000_000} MB).")
        if (bytes.isEmpty()) throw Problem("That file is empty.")
        val mime = type ?: try { resolver.getType(uri) } catch (_: Exception) { null }
        if (isPdf(bytes) || mime == PDF || name.endsWith(".pdf", ignoreCase = true)) {
            val file = incomingFile(context, keep)
            try {
                file.writeBytes(bytes)
            } catch (e: IOException) {
                file.delete()
                throw Problem("Couldn't keep the PDF on the phone (${e.message ?: "no room?"}).")
            }
            return Incoming.Pdf(name, file)
        }
        if (bytes.size > MAX_TEXT_BYTES) throw Problem("That text file is far too long for one song's tab.")
        val text = decode(bytes) ?: throw Problem("That file isn't a PDF or a text file, so no tab can be read from it.")
        return Incoming.Text(name, text)
    }

    /** All of [input], or null if it's longer than [cap] bytes. */
    internal fun capped(input: InputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > cap) return null
            out.write(buf, 0, n)
        }
    }

    /** Whether [bytes] start like a PDF (its header may come after up to 1 KB of junk). */
    fun isPdf(bytes: ByteArray): Boolean {
        val end = minOf(bytes.size, 1024) - MAGIC.size
        for (i in 0..end) if (MAGIC.indices.all { bytes[i + it] == MAGIC[it] }) return true
        return false
    }

    /**
     * [bytes] as text: UTF-8 (or UTF-16 with its byte-order mark), else the Greek Windows code page that
     * older Greek text files use; null if it isn't text at all.
     */
    fun decode(bytes: ByteArray): String? {
        fun has(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        val text = when {
            has(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            has(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            has(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, greek())
            }
        }
        // Text has no NULs, and hardly any other control characters.
        val control = text.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' && it != '\u000C' }
        return if ('\u0000' in text || control > text.length / 100) null else text
    }

    private fun greek(): Charset =
        listOf("windows-1253", "ISO-8859-7").firstNotNullOfOrNull { n -> try { Charset.forName(n) } catch (_: Exception) { null } }
            ?: Charsets.ISO_8859_1

    /**
     * Whether shared [text] is a tab rather than a link: at least [MIN_TAB_LINES] lines that look like tab
     * lines (mostly dashes, frets and bars, such as D|--5-7--|), with a fret among them.
     */
    fun looksLikeTab(text: String): Boolean {
        val lines = text.lineSequence().filter { tabLine(it) }.toList()
        return lines.size >= MIN_TAB_LINES && lines.any { l -> l.any { it.isDigit() } }
    }

    private fun tabLine(line: String): Boolean {
        val t = line.trim()
        if (t.length < 6) return false
        val dashes = t.count { it in DASHES }
        val marks = t.count { it in DASHES || it.isDigit() || it in MARKS }
        val ink = t.count { !it.isWhitespace() }
        return dashes >= 4 && marks >= 0.6 * ink
    }

    /**
     * A new file for a PDF that came in, in the app's own files next to the songs (so keeping it for a song
     * is a rename). The others there are deleted, but [keep]: one still being tried for a song.
     */
    fun incomingFile(context: Context, keep: File?): File {
        val dir = File(context.filesDir, "tab-incoming").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it != keep) it.delete() }
        var t = System.currentTimeMillis()
        while (File(dir, "$t.pdf").exists()) t++
        return File(dir, "$t.pdf")
    }

    /** Whether [file] is one that came in and wasn't kept for a song (deleted when it's not wanted). */
    fun isIncoming(context: Context, file: File) = file.parentFile == File(context.filesDir, "tab-incoming")

    /** The biggest tab file read (a long PDF score with pictures can be several MB). */
    const val MAX_BYTES = 20_000_000

    /** The biggest text file read: a tab is a few KB; the paste flow then takes up to its own limit of characters. */
    private const val MAX_TEXT_BYTES = 512 * 1024

    /** Tab-like lines a shared text needs to be taken as a tab (a trichordo tab has 3 a system). */
    private const val MIN_TAB_LINES = 3

    const val PDF = "application/pdf"
    private val MAGIC = "%PDF-".toByteArray(Charsets.ISO_8859_1)
    private const val DASHES = "-–—−‐_="
    private const val MARKS = "|:hpbrsx/\\~()[]<>^*.'"
}
