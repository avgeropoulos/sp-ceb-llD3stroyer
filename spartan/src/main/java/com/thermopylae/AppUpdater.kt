package com.thermopylae

import android.annotation.TargetApi
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * One-tap app updates. CI publishes `thermopylae-update.json` next to `Thermopylae.apk` in the
 * GitHub release this build came from. [check] compares its version code with ours; when the
 * player taps Update, [install] downloads the APK, checks its size and SHA-256, and hands it to
 * Android's package installer, where the player confirms. Every build is signed with the same
 * key, so the new version installs over this one and keeps the saved game.
 *
 * State changes are reported to the page through [report] as JSON (see [stateJson]).
 * Network work runs on its own thread; [install]'s installer launch runs on the UI thread.
 */
class AppUpdater(private val activity: Activity, private val report: (String) -> Unit) {
    private val manifestUrl: String? = run {
        val id = activity.resources.getIdentifier("update_manifest_url", "string", activity.packageName)
        val url = if (id != 0) activity.getString(id) else ""
        if (HostLogic.isTrustedDownload(url)) url else null
    }

    /** Version code of the running app. */
    val installedCode: Long = try {
        versionCodeOf(activity.packageManager.getPackageInfo(activity.packageName, 0))
    } catch (e: Throwable) {
        Log.w(TAG, e)
        0L
    }

    @Volatile private var state = STATE_IDLE
    @Volatile private var available = 0L
    @Volatile private var availableName = ""
    @Volatile private var notes = ""
    @Volatile private var apkUrl: String? = null
    @Volatile private var apkSha256 = ""
    @Volatile private var apkSize = -1L
    @Volatile private var progress = 0.0
    @Volatile private var error = ""
    private val busy = Any()
    private var working = false

    private val apkFile: File get() = apkFile(activity)

    /** `{"state":…,"installed":…,"available":…,"name":…,"notes":…,"progress":…,"error":…}`. */
    fun stateJson(): String {
        val o = JSONObject()
        o.put("state", state)
        o.put("installed", installedCode)
        o.put("available", available)
        o.put("name", availableName)
        o.put("notes", notes)
        o.put("progress", progress)
        o.put("error", error)
        o.put("enabled", manifestUrl != null)
        return o.toString()
    }

    /** Looks for a newer build in the background (quietly does nothing for local builds). */
    fun check() {
        val url = manifestUrl ?: return
        if (!begin()) return
        thread(name = "app-update-check", isDaemon = true) {
            try {
                setState(STATE_CHECKING)
                val m = JSONObject(fetchText(url, 64 * 1024))
                val code = m.optLong("versionCode", 0L)
                val link = m.optString("apkUrl", "")
                if (code > installedCode && HostLogic.isTrustedDownload(link)) {
                    available = code
                    availableName = m.optString("versionName", "")
                    notes = m.optString("notes", "").take(400)
                    apkUrl = link
                    apkSha256 = m.optString("sha256", "").lowercase()
                    apkSize = m.optLong("size", -1L)
                    // Already downloaded on an earlier run (Android can restart the app after the
                    // player allows it to install updates): offer to install it straight away.
                    setState(if (alreadyDownloaded()) STATE_READY else STATE_AVAILABLE)
                } else {
                    // Up to date: a downloaded APK, if any, has been installed (or overtaken).
                    apkFile.delete()
                    setState(STATE_CURRENT)
                }
            } catch (e: Throwable) {
                // No network or no release yet: try again next launch, without bothering the player.
                Log.w(TAG, "update check failed: $e")
                setState(STATE_IDLE)
            } finally {
                end()
            }
        }
    }

    /** Downloads the available update (if not already) and opens the installer. */
    fun install() {
        if (state == STATE_READY || state == STATE_PERMISSION) {
            activity.runOnUiThread { launchInstaller() }
            return
        }
        val link = apkUrl ?: return
        if (state != STATE_AVAILABLE && state != STATE_ERROR) return
        if (!begin()) return
        thread(name = "app-update-download", isDaemon = true) {
            try {
                progress = 0.0
                error = ""
                setState(STATE_DOWNLOADING)
                val digest = download(link, apkFile)
                if (apkSha256.isNotEmpty() && digest != apkSha256) throw IOException("the download was corrupted")
                setState(STATE_READY)
                activity.runOnUiThread { launchInstaller() }
            } catch (e: Throwable) {
                Log.w(TAG, "update download failed", e)
                apkFile.delete()
                error = "Download failed. Check your connection and try again."
                setState(STATE_ERROR)
            } finally {
                end()
            }
        }
    }

    /** The player came back (e.g. from the "install unknown apps" settings): carry on if allowed now. */
    fun onResume() {
        if (state == STATE_PERMISSION && canInstall()) launchInstaller()
    }

    // ---- Internals ----

    private fun launchInstaller() {
        if (!apkFile.isFile) {
            setState(if (apkUrl != null) STATE_AVAILABLE else STATE_IDLE)
            return
        }
        if (!canInstall()) {
            // Android 8+: the player has to allow this app to install updates, once.
            setState(STATE_PERMISSION)
            try {
                activity.startActivity(Api26.unknownSourcesSettings(activity.packageName))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, e)
            }
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(UpdateFileProvider.APK_URI, UpdateFileProvider.APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            setState(STATE_READY)
            activity.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, e)
            error = "Android couldn't open the installer."
            setState(STATE_ERROR)
        }
    }

    private fun alreadyDownloaded(): Boolean {
        val f = apkFile
        if (!f.isFile || apkSha256.isEmpty() || (apkSize > 0 && f.length() != apkSize)) return false
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            f.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            HostLogic.hex(md.digest()) == apkSha256
        } catch (e: IOException) {
            false
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || Api26.canRequestPackageInstalls(activity)

    private fun begin(): Boolean = synchronized(busy) {
        if (working) false else {
            working = true
            true
        }
    }

    private fun end() = synchronized(busy) { working = false }

    private fun setState(s: String) {
        state = s
        report(stateJson())
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.useCaches = false
        c.setRequestProperty("User-Agent", "Thermopylae/$installedCode")
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw IOException("HTTP $code")
        }
        return c
    }

    private fun fetchText(url: String, limit: Int): String {
        val c = open(url)
        try {
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size > limit) throw IOException("response too large")
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    /** Downloads [url] to [dest], reporting progress, and returns the file's SHA-256 in hex. */
    private fun download(url: String, dest: File): String {
        dest.parentFile?.mkdirs()
        val c = open(url)
        try {
            val total = if (apkSize > 0) apkSize else c.contentLength.toLong()
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var done = 0L
            var lastReport = 0.0
            c.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > HostLogic.MAX_UPDATE_BYTES) throw IOException("update too large")
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                        if (total > 0) {
                            progress = (done.toDouble() / total).coerceIn(0.0, 1.0)
                            if (progress - lastReport >= 0.04) {
                                lastReport = progress
                                report(stateJson())
                            }
                        }
                    }
                }
            }
            if (apkSize > 0 && done != apkSize) throw IOException("incomplete download")
            progress = 1.0
            return HostLogic.hex(md.digest())
        } finally {
            c.disconnect()
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private object Api26 {
        fun canRequestPackageInstalls(activity: Activity): Boolean = activity.packageManager.canRequestPackageInstalls()

        fun unknownSourcesSettings(pkg: String): Intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$pkg"))
    }

    @TargetApi(Build.VERSION_CODES.P)
    private object Api28 {
        fun versionCode(info: PackageInfo): Long = info.longVersionCode
    }

    companion object {
        private const val TAG = "Spartan"
        const val STATE_IDLE = "idle"
        const val STATE_CHECKING = "checking"
        const val STATE_CURRENT = "current"
        const val STATE_AVAILABLE = "available"
        const val STATE_DOWNLOADING = "downloading"
        const val STATE_READY = "ready"
        const val STATE_PERMISSION = "permission"
        const val STATE_ERROR = "error"

        /** Where the downloaded APK lives; [UpdateFileProvider] serves exactly this file. */
        fun apkFile(context: android.content.Context): File = File(File(context.cacheDir, "updates"), "Thermopylae.apk")

        @Suppress("DEPRECATION")
        private fun versionCodeOf(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Api28.versionCode(info) else info.versionCode.toLong()
    }
}
