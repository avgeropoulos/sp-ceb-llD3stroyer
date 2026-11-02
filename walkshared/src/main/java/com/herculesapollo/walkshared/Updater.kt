package com.herculesapollo.walkshared

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Updates the game from inside the app. When the game opens it asks GitHub for the number of
 * the newest build (the release's version.txt); if that's newer than this one, it offers to
 * download the new APK and hands it to Android's installer. Your saved games are kept, since
 * every build is signed with the same key.
 *
 * Builds made on a computer rather than by GitHub have no release to follow, so they never ask.
 */
class Updater(private val activity: Activity, private val apkName: String) {
    /** The GitHub release this build came from, like "latest-main"; blank for a local build. */
    private val tag: String = runCatching {
        @Suppress("DEPRECATION")
        activity.packageManager.getApplicationInfo(activity.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("updateTag")
    }.getOrNull().orEmpty()
    private var checked = false

    private fun url(file: String) = "$RELEASES/$tag/$file"

    private fun currentVersion(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    /** Looks for a newer build once per launch, quietly giving up if there's no connection. */
    fun check() {
        if (checked || tag.isBlank()) return
        checked = true
        thread(isDaemon = true, name = "update-check") {
            val latest = runCatching { String(open(url("version.txt")).use { it.readBytes() }).trim().toLong() }.getOrNull()
                ?: return@thread
            if (latest > currentVersion()) activity.runOnUiThread { offer() }
        }
    }

    private fun offer() {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("Update ready!")
            .setMessage("A new version of the game is ready. Update now? Your saved games are kept.")
            .setPositiveButton("UPDATE") { _, _ -> download() }
            .setNegativeButton("LATER", null)
            .show()
    }

    private fun download() {
        val progress = AlertDialog.Builder(activity)
            .setTitle("Updating")
            .setMessage("Downloading… 0%")
            .setCancelable(false)
            .show()
        thread(isDaemon = true, name = "update-download") {
            val dir = File(activity.cacheDir, UpdateFileProvider.DIR).apply { mkdirs() }
            val apk = File(dir, apkName)
            val ok = runCatching {
                val conn = connect(url(apkName))
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct >= 0 && pct != shown) {
                                shown = pct
                                activity.runOnUiThread { progress.setMessage("Downloading… $pct%") }
                            }
                        }
                    }
                }
                total <= 0 || apk.length() == total
            }.getOrDefault(false)
            activity.runOnUiThread {
                progress.dismiss()
                if (ok) install(apk) else failed()
            }
        }
    }

    private fun install(apk: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(UpdateFileProvider.uriFor(activity, apk), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // The first time, Android asks you to allow this game to install updates.
        runCatching { activity.startActivity(intent) }.onFailure { failed() }
    }

    private fun failed() {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("Couldn't update")
            .setMessage("The update didn't download. Check your connection and try again next time you open the game.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun connect(address: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            useCaches = false
            instanceFollowRedirects = true
        }

    private fun open(address: String) = connect(address).inputStream

    companion object {
        const val RELEASES = "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download"
    }
}
