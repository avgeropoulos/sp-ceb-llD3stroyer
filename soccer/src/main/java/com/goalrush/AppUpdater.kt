package com.goalrush

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.goalrush.game.Updater
import com.goalrush.game.Updater.Status
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * In-game updates. Every push to GitHub builds a new APK and publishes it, together with a
 * small version file, as the latest release. This class reads the version file, and if the
 * build is newer than this one it downloads the APK and hands it to Android's installer.
 */
class AppUpdater(context: Context) : Updater {
    private val app = context.applicationContext
    private val installedCode: Long
    override val currentVersion: String

    @Volatile override var status = Status.IDLE
        private set
    @Volatile override var latestVersion = ""
        private set
    @Volatile override var message = ""
        private set
    @Volatile override var progress = 0f
        private set

    @Volatile private var busy = false
    @Volatile private var apkUrl = APK_URL
    @Volatile private var apkSize = 0L

    init {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION")
        installedCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        currentVersion = info.versionName ?: installedCode.toString()
        InstallReceiver.listener = { ok, msg ->
            status = if (ok) Status.INSTALLING else Status.ERROR
            message = msg
        }
    }

    override fun check() {
        if (busy) return
        busy = true
        status = Status.CHECKING
        message = ""
        thread(name = "update-check") {
            try {
                val json = JSONObject(httpGet(VERSION_URL))
                val code = json.getLong("versionCode")
                latestVersion = json.optString("versionName", code.toString())
                json.optString("apk").takeIf { it.startsWith("https://") }?.let { apkUrl = it }
                apkSize = json.optLong("size", 0L)
                if (code > installedCode) {
                    status = Status.AVAILABLE
                    message = "Version $latestVersion is out. Your saves are kept."
                } else {
                    status = Status.UP_TO_DATE
                    message = ""
                }
            } catch (e: Exception) {
                status = Status.ERROR
                message = "Could not reach GitHub. Check your internet connection."
            } finally {
                busy = false
            }
        }
    }

    override fun install() {
        if (busy) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            message = "Allow Goal Rush to install apps, then come back and tap INSTALL UPDATE again."
            try {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
            return
        }
        busy = true
        status = Status.DOWNLOADING
        progress = 0f
        message = ""
        thread(name = "update-download") {
            try {
                val file = File(app.cacheDir, "goalrush-update.apk")
                download(apkUrl, file)
                status = Status.INSTALLING
                message = "Tap Update when Android asks. The game will restart."
                installApk(file)
            } catch (e: Exception) {
                status = Status.ERROR
                message = "The download failed. Try again in a moment."
            } finally {
                busy = false
            }
        }
    }

    fun release() {
        InstallReceiver.listener = null
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "GoalRush/$currentVersion")
            setRequestProperty("Cache-Control", "no-cache")
        }

    private fun httpGet(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, file: File) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: apkSize
            var done = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
            progress = 1f
        } finally {
            c.disconnect()
        }
    }

    private fun installApk(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("goalrush.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(app, InstallReceiver::class.java).setPackage(app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(app, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    companion object {
        private const val RELEASE = "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/latest/download"
        const val VERSION_URL = "$RELEASE/GoalRush-version.json"
        const val APK_URL = "$RELEASE/GoalRush.apk"
    }
}
