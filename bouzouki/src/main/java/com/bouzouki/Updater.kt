package com.bouzouki

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Updates the app from its own GitHub release ([CHANNEL]) without going through the browser:
 * it reads the build number CI publishes next to the APK, and if it's newer, downloads the
 * APK (resuming if the connection drops) and hands it to Android's package installer, which
 * asks you to confirm.
 */
class Updater(private val activity: Activity) {
    val installedBuild: Long = try {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: Exception) { 0 }

    /** Calls [done] on the UI thread with the latest build number, or null if it couldn't be read. */
    fun checkLatest(done: (Long?) -> Unit) {
        thread(name = "update-check") {
            val latest = try { open(VERSION_URL).inputStream.bufferedReader().use { it.readText().trim().toLong() } } catch (_: Exception) { null }
            activity.runOnUiThread { done(latest) }
        }
    }

    /** True if Android lets this app install updates; otherwise opens the setting to allow it and returns false. */
    fun ensureCanInstall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()) return true
        activity.startActivity(
            Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        )
        return false
    }

    /** Downloads the latest APK into the package installer. [progress] gets 0..100, [failed] an error message. */
    fun install(progress: (Int) -> Unit, failed: (String) -> Unit) {
        thread(name = "update-download") {
            val installer = activity.packageManager.packageInstaller
            var sessionId = -1
            try {
                val apk = download(progress)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(activity.packageName)
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("Bouzouki.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                        session.fsync(out)
                    }
                    apk.delete()
                    // Android reports back to MainActivity, which shows the "Update?" confirmation.
                    val status = Intent(activity, MainActivity::class.java).setAction(ACTION_INSTALL_STATUS)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getActivity(activity, 0, status, flags).intentSender)
                }
            } catch (e: Exception) {
                if (sessionId >= 0) try { installer.abandonSession(sessionId) } catch (_: Exception) {}
                activity.runOnUiThread { failed(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /**
     * Downloads the APK to a file, picking up where it stopped if the connection drops (phones
     * on mobile data often lose a long download), and returns it once it's complete.
     */
    private fun download(progress: (Int) -> Unit): File {
        val file = File(activity.cacheDir, "update.apk")
        file.delete()
        var total = -1L
        var lastPct = -1
        var failures = 0
        while (true) {
            val have = file.length()
            if (total > 0 && have >= total) break
            try {
                val conn = URL(APK_URL).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("Cache-Control", "no-cache")
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                val resumed = code == 206
                if (code !in 200..299) throw IllegalStateException("GitHub answered $code")
                if (!resumed && have > 0) file.delete() // the server sent it all again
                val len = conn.contentLengthLong
                if (total < 0 || !resumed) total = if (len >= 0) file.length() + len else -1
                FileOutputStream(file, resumed).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            val pct = if (total > 0) (file.length() * 100 / total).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; activity.runOnUiThread { progress(pct) } }
                        }
                    }
                }
                conn.disconnect()
                if (total <= 0) break // size unknown: take what came
            } catch (e: Exception) {
                if (++failures > 8) throw IllegalStateException("the download kept failing (${e.message ?: e.javaClass.simpleName})")
                Thread.sleep(1000L * failures)
            }
        }
        if (total > 0 && file.length() != total) throw IllegalStateException("the download is incomplete")
        return file
    }

    private fun open(url: String): HttpURLConnection {
        // GitHub redirects release downloads to its file servers (https to https, which this follows).
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Cache-Control", "no-cache")
        if (conn.responseCode !in 200..299) throw IllegalStateException("GitHub answered ${conn.responseCode}")
        return conn
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.bouzouki.INSTALL_STATUS"
        /**
         * This app's own release, published by CI from the Bouzouki Hero branch. Not GitHub's "latest"
         * release, which other branches' builds also take turns at.
         */
        const val CHANNEL = "bouzouki-hero"
        private const val RELEASE = "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/$CHANNEL"
        private const val APK_URL = "$RELEASE/Bouzouki.apk"
        private const val VERSION_URL = "$RELEASE/bouzouki-version.txt"
    }
}
