package com.thermopylae

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Lends the downloaded update APK to Android's package installer. Since Android 7 an app may not
 * hand another app a file:// path, so the installer reads it through this read-only provider
 * instead (androidx's FileProvider isn't available: the project doesn't use AndroidX). It is not
 * exported; the installer gets a one-off read grant with the intent.
 */
class UpdateFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = APK_MIME

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val ctx = context ?: throw FileNotFoundException()
        val file = AppUpdater.apkFile(ctx)
        if (uri.path != APK_URI.path || !file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val file = AppUpdater.apkFile(ctx)
        if (uri.path != APK_URI.path || !file.isFile) return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(cols.size)
        for (i in cols.indices) {
            row[i] = when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        const val AUTHORITY = "com.thermopylae.updates"
        const val APK_MIME = "application/vnd.android.package-archive"
        val APK_URI: Uri = Uri.parse("content://$AUTHORITY/Thermopylae.apk")
    }
}
