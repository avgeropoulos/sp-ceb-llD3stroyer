package com.bouzouki

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Updates the app from the latest GitHub release without going through the browser:
 * it reads the build number CI publishes next to the APK, and if it's newer, downloads
 * the APK straight into Android's package installer, which asks you to confirm.
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
                val conn = open(APK_URL)
                val total = conn.contentLengthLong
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(activity.packageName)
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("Bouzouki.apk", 0, total).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != lastPct) { lastPct = pct; activity.runOnUiThread { progress(pct) } }
                            }
                        }
                        session.fsync(out)
                    }
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
        private const val RELEASE = "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/latest/download"
        private const val APK_URL = "$RELEASE/Bouzouki.apk"
        private const val VERSION_URL = "$RELEASE/bouzouki-version.txt"
    }
}
