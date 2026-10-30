package com.javelinstrike

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.javelinstrike.game.Updater
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Updates the app from inside it. GitHub Actions publishes every build to the
 * `javelin-strike` release together with `javelin-version.txt`, which holds its build number.
 * If that's newer than this app, the title screen offers the update; tapping it downloads the
 * APK and hands it to Android's installer, which asks the player to confirm.
 */
class AppUpdater(private val activity: Activity) : Updater {
    private enum class State { CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, FAILED }

    @Volatile private var state = State.CHECKING
    @Volatile private var remote = 0
    @Volatile private var progress = 0
    @Volatile private var error: String? = null

    @Suppress("DEPRECATION")
    private val current: Int = try {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode
    } catch (_: Exception) { 0 }

    override val label: String
        get() = when (state) {
            State.CHECKING -> "CHECKING FOR UPDATES..."
            State.UP_TO_DATE -> "UP TO DATE (BUILD $current)"
            State.AVAILABLE -> "UPDATE TO BUILD $remote"
            State.DOWNLOADING -> "DOWNLOADING $progress%"
            State.INSTALLING -> "INSTALLING..."
            State.FAILED -> if (remote > current) "UPDATE FAILED - RETRY" else "CHECK FOR UPDATES"
        }

    override val detail: String?
        get() = when (state) {
            State.FAILED -> error
            State.AVAILABLE -> "You have build $current. Tap to download and install."
            else -> null
        }

    override val available get() = state == State.AVAILABLE

    init {
        InstallReceiver.onFailure = { message ->
            error = message
            state = State.FAILED
        }
        check()
    }

    /** Called from the game thread when the button is tapped. */
    override fun tap() {
        when (state) {
            State.UP_TO_DATE -> check()
            State.AVAILABLE -> download()
            State.FAILED -> if (remote > current) download() else check()
            else -> {}
        }
    }

    private fun check() {
        state = State.CHECKING
        error = null
        thread(name = "update-check") {
            try {
                remote = open(VERSION_URL).inputStream.use { it.readBytes() }.decodeToString().trim().toInt()
                state = if (remote > current) State.AVAILABLE else State.UP_TO_DATE
            } catch (_: Exception) {
                error = "Couldn't reach GitHub. Are you online?"
                state = State.FAILED
            }
        }
    }

    private fun download() {
        // Android 8+ asks once for permission to install apps from this one.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            error = "Allow \"Install unknown apps\" for Javelin Strike, then come back and tap again."
            state = State.FAILED
            activity.runOnUiThread {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                )
            }
            return
        }
        state = State.DOWNLOADING
        progress = 0
        error = null
        thread(name = "update-download") {
            try {
                val file = File(activity.cacheDir, "update.apk")
                val conn = open(APK_URL)
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress = (done * 100 / total).toInt()
                        }
                    }
                }
                state = State.INSTALLING
                install(file)
            } catch (e: Exception) {
                error = "Download failed: ${e.message ?: e.javaClass.simpleName}"
                state = State.FAILED
            }
        }
    }

    /** Streams the APK into an install session; Android then shows its "Update this app?" screen. */
    private fun install(file: File) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(activity.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("javelin-strike.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(activity, id, Intent(activity, InstallReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Cache-Control", "no-cache")
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        return conn
    }

    private companion object {
        const val BASE = "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/javelin-strike/"
        const val VERSION_URL = BASE + "javelin-version.txt"
        const val APK_URL = BASE + "JavelinStrike.apk"
    }
}
