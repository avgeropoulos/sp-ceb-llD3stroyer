package com.roccoquest.teamup

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.roccoquest.team.TeamGame
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The title screen's UPDATE button: asks GitHub whether a newer build of this game was made
 * (CI uploads `version.txt` next to the APKs), downloads it inside the game and lets Android
 * install it over this one. Saves are kept, because it's the same app with the same key.
 */
class Updater(
    private val context: Context,
    private val game: TeamGame,
    /** The APK's file name in the GitHub release, e.g. "TeamUp.apk". */
    private val apkName: String,
    /** The branch this build came from ("" for a build made on a computer). */
    private val branch: String,
    /** This build's CI run number. */
    private val myRun: Int,
) {
    @Volatile private var busy = false
    private val action = context.packageName + ".UPDATE_STATUS"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android asks "Do you want to update this app?".
                    val confirm = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { c.startActivity(confirm) } catch (_: Exception) { status("COULDN'T OPEN THE INSTALLER") }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> status("UPDATED!")
                PackageInstaller.STATUS_FAILURE_ABORTED -> status("UPDATE CANCELLED. TAP UPDATE TO TRY AGAIN")
                else -> status("UPDATE DIDN'T INSTALL. TAP UPDATE TO TRY AGAIN")
            }
        }
    }

    init {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun release() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    /** Called when UPDATE is tapped. */
    fun start() {
        if (busy) return
        busy = true
        thread(name = "updater") {
            try {
                update()
            } catch (_: Exception) {
                status("NO INTERNET? TAP UPDATE TO TRY AGAIN")
            } finally {
                busy = false
            }
        }
    }

    private fun status(s: String) { game.updateStatus = s }

    private fun update() {
        if (branch.isEmpty()) {
            status("THIS COPY WASN'T MADE BY GITHUB, SO IT CAN'T UPDATE ITSELF")
            return
        }
        status("CHECKING FOR AN UPDATE...")
        val base = "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/latest-$branch/"
        val newest = String(get(base + "version.txt") { }).trim().toIntOrNull()
        if (newest == null) {
            status("COULDN'T CHECK FOR AN UPDATE. TRY AGAIN LATER")
            return
        }
        if (newest <= myRun) {
            status("YOU HAVE THE NEWEST VERSION!")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            // Android needs a one-time OK before a game may install its own updates.
            status("TURN ON \"ALLOW\", COME BACK, THEN TAP UPDATE AGAIN")
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(settings) } catch (_: Exception) {}
            return
        }
        status("DOWNLOADING THE UPDATE... 0%")
        val apk = File(context.cacheDir, "update.apk")
        apk.writeBytes(get(base + apkName) { pct -> status("DOWNLOADING THE UPDATE... $pct%") })
        status("INSTALLING...")
        install(apk)
    }

    /** Downloads [url] (following GitHub's redirects), telling [progress] the percent done. */
    private fun get(url: String, progress: (Int) -> Unit): ByteArray {
        var link = URL("$url?t=${System.currentTimeMillis()}")
        repeat(5) {
            val c = link.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "TeamUp-Updater")
            val code = c.responseCode
            if (code in 300..399) {
                link = URL(link, c.getHeaderField("Location"))
                c.disconnect()
                return@repeat
            }
            if (code != 200) error("HTTP $code")
            val total = c.contentLengthLong
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var shown = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != shown) { shown = pct; progress(pct) }
                    }
                }
            }
            c.disconnect()
            return out.toByteArray()
        }
        error("Too many redirects")
    }

    /** Hands the downloaded APK to Android's installer, which asks before updating. */
    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(action).setPackage(context.packageName), flags)
            session.commit(pending.intentSender)
        }
        apk.delete()
    }
}
