package com.bouzouki

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.bouzouki.core.song.ParsedTab
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
 *
 * A PDF that came in stays in the app's files only while it's offered or tried for a song ([hold]); once
 * it's kept for a song it's moved there, and when it isn't wanted it's deleted ([release]). Copies left
 * behind (the app was closed meanwhile) are cleared after a while ([sweep]).
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
     * main thread. A PDF's copy is held ([hold]) till it's [release]d.
     */
    fun read(context: Context, uri: Uri, type: String?, done: (Result<Incoming>) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = try {
                Result.success(read(app, uri, type))
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

    private fun read(context: Context, uri: Uri, type: String?): Incoming {
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
            val file = incomingFile(context)
            try {
                file.writeBytes(bytes)
            } catch (e: Throwable) {
                release(context, file)
                throw if (e is IOException) Problem("Couldn't keep the PDF on the phone (${e.message ?: "no room?"}).") else e
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
     * A new file for a PDF that came in, in the app's own files next to the songs (so keeping it for a song
     * is a rename), held till it's [release]d. Copies left behind are cleared first ([sweep]).
     */
    fun incomingFile(context: Context): File {
        sweep(context)
        val dir = incomingDir(context).apply { mkdirs() }
        synchronized(held) {
            var t = System.currentTimeMillis()
            while (File(dir, "$t.pdf").exists() || File(dir, "$t.pdf").path in held) t++
            return File(dir, "$t.pdf").also { held += it.path }
        }
    }

    /** Whether [file] is one that came in and wasn't kept for a song (deleted when it's not wanted). */
    fun isIncoming(context: Context, file: File) = file.parentFile == incomingDir(context)

    /** Keeps [file], a PDF that came in, while a song screen offers or tries it (one coming back after it was closed, say). */
    fun hold(file: File) = synchronized(held) { held += file.path }

    /**
     * [tab] (or the PDF [file]) that came in is settled: kept for a song (moved there), or not wanted. Its copy is
     * deleted if it's still among those that came in.
     */
    fun release(context: Context, tab: Incoming) {
        if (tab is Incoming.Pdf) release(context, tab.file)
    }

    fun release(context: Context, file: File) {
        synchronized(held) { held -= file.path }
        if (isIncoming(context, file)) file.delete()
    }

    /**
     * Deletes the copies of PDFs that came in and aren't held by a song screen: left behind when the app was closed
     * while one was offered. Only those a while old ([STALE_MS]), so one a screen coming back still wants survives.
     */
    fun sweep(context: Context) {
        val old = System.currentTimeMillis() - STALE_MS
        val files = incomingDir(context).listFiles() ?: return
        synchronized(held) {
            for (f in files) if (f.path !in held && f.lastModified() < old) f.delete()
        }
    }

    private fun incomingDir(context: Context) = File(context.filesDir, "tab-incoming")

    /** The PDFs that came in still offered or tried on a song screen (their paths), across the app's screens. */
    private val held = HashSet<String>()

    /** Why a PDF gave nothing at all, as [com.bouzouki.core.song.PdfTab]'s warnings tell it. */
    enum class PdfTrouble {
        /** Locked with a password (or a lock the reader can't open). */
        LOCKED,

        /** Damaged, or not really a PDF. */
        DAMAGED,
    }

    /** Why nothing could be read from the PDF [tab] came from: locked or damaged; null if neither (a scan, say) or it has notes. */
    fun pdfTrouble(tab: ParsedTab): PdfTrouble? = when {
        tab.notes.isNotEmpty() -> null
        tab.warnings.any { lockWarning(it) } -> PdfTrouble.LOCKED
        tab.warnings.any { damageWarning(it) } -> PdfTrouble.DAMAGED
        else -> null
    }

    /** Whether [warning] is one of PdfTab's for a PDF it couldn't open or read (explained in plain words instead of listed). */
    fun troubleWarning(warning: String) = lockWarning(warning) || damageWarning(warning)

    // PdfTab has no constants for these yet; it words them "This PDF is protected with a password or an encryption …",
    // "This PDF couldn't be read", "A page of this PDF couldn't be read", "This file doesn't look like a PDF, or it is
    // too damaged to read" and "Parts of this PDF are damaged; it was read as well as possible".
    private fun lockWarning(w: String) = "password" in w || "encryption" in w
    private fun damageWarning(w: String) = "couldn't be read" in w || "doesn't look like a PDF" in w || "damaged" in w

    /** The biggest tab file read (a long PDF score with pictures can be several MB). */
    const val MAX_BYTES = 20_000_000

    /** The biggest text file read: a tab is a few KB; the paste flow then takes up to its own limit of characters. */
    private const val MAX_TEXT_BYTES = 512 * 1024

    /** How old a copy of a PDF that came in, held by no screen, must be to be cleared ([sweep]). */
    private const val STALE_MS = 60 * 60 * 1000L

    const val PDF = "application/pdf"
    private val MAGIC = "%PDF-".toByteArray(Charsets.ISO_8859_1)
}
