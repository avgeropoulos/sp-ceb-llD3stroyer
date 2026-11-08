package com.mixdeck

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Updates the app from inside the app. Every push to GitHub builds a new APK and publishes it with
 * a version.json next to it; the app compares that build number with its own and, if newer,
 * downloads the APK and hands it to Android's package installer.
 */
class Updater(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    data class Release(val versionCode: Int, val versionName: String, val notes: String)

    /** Checks for an update. [manual] = the user asked, so also say when there isn't one. */
    fun check(manual: Boolean) {
        thread(name = "update check") {
            val r = runCatching {
                val o = JSONObject(Net.getText(BuildConfig.UPDATE_URL + "version.json?t=" + System.currentTimeMillis()))
                Release(o.getInt("versionCode"), o.optString("versionName"), o.optString("notes"))
            }
            main.post {
                if (activity.isFinishing) return@post
                val rel = r.getOrNull()
                when {
                    rel == null -> if (manual) toast("Couldn't check for updates: ${r.exceptionOrNull()?.message}")
                    rel.versionCode > BuildConfig.VERSION_CODE -> offer(rel)
                    manual -> toast("MixDeck is up to date (version ${BuildConfig.VERSION_NAME})")
                }
            }
        }
    }

    private fun offer(rel: Release) {
        AlertDialog.Builder(activity)
            .setTitle("Update available: ${rel.versionName}")
            .setMessage("You have ${BuildConfig.VERSION_NAME}.\n\n${rel.notes}".trim())
            .setPositiveButton("Update now") { _, _ -> install() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun install() {
        val pm = activity.packageManager
        if (!pm.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Allow updates")
                .setMessage("Android needs your OK once for MixDeck to install its own updates. " +
                    "Turn on \"Allow from this source\", then come back and tap Update again.")
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Downloading update…").setMessage("0%")
            .setCancelable(false).show()
        thread(name = "update download") {
            val apk = File(activity.cacheDir, "update.apk")
            val result = runCatching {
                Net.download(BuildConfig.UPDATE_URL + "MixDeck.apk", apk) { p ->
                    main.post { dialog.setMessage("${(p * 100).toInt()}%") }
                }
                val installer = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(activity.packageName)
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("MixDeck.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                    val intent = Intent(activity, UpdateReceiver::class.java)
                    val pi = PendingIntent.getBroadcast(activity, id, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    s.commit(pi.intentSender)
                }
            }
            main.post {
                dialog.dismiss()
                result.exceptionOrNull()?.let { toast("Update failed: ${it.message}") }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()
}

/** Gets the package installer's answer: shows Android's "Update this app?" screen, or reports errors. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Update not installed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
