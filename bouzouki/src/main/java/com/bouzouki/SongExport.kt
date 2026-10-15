package com.bouzouki

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Send song data": packs a prepared song into one zip to share (to the developer, to improve how
 * notes are found). It holds the song as saved (<id>.json), what its line comes from (<id>.data:
 * the note detector's activations and pick attacks), the tab kept for it if any, and info.txt (title,
 * link, app version, bouzouki, detail level, sync, the playback time and the player's note). Never
 * any audio: the app doesn't keep it.
 */
object SongExport {
    /** What goes in info.txt besides the files. */
    class Info(
        val id: String,
        val title: String,
        val link: String?,
        val instrument: String,
        val detail: String,
        val lineFrom: String,
        val sync: Double,
        val time: Double,
        val note: String,
    )

    /**
     * Writes the zip off the main thread, then opens the share sheet; [done] gets null when the
     * sheet opened, or a message saying what went wrong.
     */
    fun share(activity: Activity, info: Info, done: (String?) -> Unit) {
        val app = activity.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                Result.success(write(app, info))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                val zip = result.getOrNull()
                if (zip == null) { done("Couldn't pack the song's data: ${result.exceptionOrNull()?.message ?: "unknown error"}"); return@post }
                val uri = ExportProvider.uri(app, zip)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Bouzouki song data: ${info.title}")
                    putExtra(Intent.EXTRA_TEXT, "Song data for \"${info.title}\" from the Bouzouki app (notes and what was heard, no audio).")
                    clipData = ClipData.newRawUri(zip.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    activity.startActivity(Intent.createChooser(send, "Send song data"))
                    done(null)
                } catch (e: Exception) {
                    done("No app found to send it with.")
                }
            }
        }.start()
    }

    /** The zip for [info]'s song, in the cache folder the provider serves (older ones are removed). */
    private fun write(context: Context, info: Info): File {
        val songs = File(context.filesDir, "songs")
        val dir = ExportProvider.dir(context)
        dir.listFiles()?.forEach { it.delete() }
        val zip = File(dir, "bouzouki-" + safeName(info.title).ifEmpty { "song" } + ".zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("info.txt"))
            out.write(infoText(context, info).toByteArray(Charsets.UTF_8))
            out.closeEntry()
            for (name in listOf("${info.id}.json", "${info.id}.data", "${info.id}.tab.txt")) {
                val f = File(songs, name).takeIf { it.isFile } ?: continue
                out.putNextEntry(ZipEntry(name.replaceFirst(info.id, "song")))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    private fun infoText(context: Context, info: Info): String {
        val version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { null } ?: "?"
        return buildString {
            appendLine("title: ${info.title}")
            appendLine("id: ${info.id}")
            info.link?.let { appendLine("link: $it") }
            appendLine("app: v$version")
            appendLine("phone: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            appendLine("bouzouki: ${info.instrument}")
            appendLine("notes: ${info.detail}")
            appendLine("line from: ${info.lineFrom}")
            appendLine("sync: %.3f s".format(java.util.Locale.ROOT, info.sync))
            appendLine("playback time: ${clock(info.time)} (%.2f s)".format(java.util.Locale.ROOT, info.time))
            if (info.note.isNotBlank()) { appendLine(); appendLine("note:"); appendLine(info.note.trim()) }
        }
    }

    /** m:ss for [seconds]. */
    fun clock(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** A file name from a song title: letters (any script), digits, - and _ only, at most 40. */
    private fun safeName(title: String) =
        title.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').take(40)
}

/**
 * Serves the zips [SongExport] writes, read only, to the app picked in the share sheet (which gets
 * a one-off grant). Only plain file names in its own cache folder are served.
 */
class ExportProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/zip"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private fun file(uri: Uri): File {
        val context = context ?: throw FileNotFoundException("no context")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no name")
        if (uri.pathSegments.size != 1 || name.contains('/') || name.startsWith('.')) throw FileNotFoundException(name)
        return File(dir(context), name).takeIf { it.isFile } ?: throw FileNotFoundException(name)
    }

    companion object {
        fun dir(context: Context) = File(context.cacheDir, "export").apply { mkdirs() }
        fun uri(context: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority("${context.packageName}.export").appendPath(file.name).build()
    }
}
