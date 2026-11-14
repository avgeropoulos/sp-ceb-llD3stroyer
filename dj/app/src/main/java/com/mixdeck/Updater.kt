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
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Updates the app from inside the app. Every push to GitHub builds a new APK and publishes it with
 * a version.json next to it; the app compares that build number with its own and, if newer,
 * downloads the APK and hands it to Android's package installer.
 */
class Updater(
    private val activity: Activity,
    /** Music is playing or a mix is being recorded: no popups, and warn before installing. */
    private val busy: () -> Boolean,
    private val recording: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("mixdeck", Context.MODE_PRIVATE)
    data class Release(val versionCode: Int, val versionName: String, val notes: String)

    /** A newer version, once a check found one (the ☰ button shows a badge). */
    @Volatile var available: Release? = null; private set

    /** The ☰ menu's update item, or null when there's no update. */
    fun menuLabel(): String? = available?.let { "⬆ Update to ${it.versionName}" }
    fun menuPick() { available?.let(::offer) }

    /** After an update: say what's new, once. */
    fun welcomeBack() {
        val from = prefs.getInt("updatedFrom", 0)
        if (from == 0) return
        val notes = prefs.getString("updateNotes", "") ?: ""
        prefs.edit().remove("updatedFrom").remove("updateNotes").apply()
        if (BuildConfig.VERSION_CODE > from) AlertDialog.Builder(activity).setTitle("MixDeck updated")
            .setMessage("You're now on MixDeck ${BuildConfig.VERSION_NAME}.\n\n${notes.removePrefix("What's new: ").let { if (it.isBlank()) "" else "What's new: $it" }}".trim())
            .setPositiveButton("OK", null).show()
    }

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
                    rel.versionCode > BuildConfig.VERSION_CODE -> {
                        available = rel
                        // Never interrupt the music with a popup: the ☰ badge says it's there.
                        if (manual || !busy()) offer(rel)
                    }
                    manual -> toast("MixDeck is up to date (version ${BuildConfig.VERSION_NAME})")
                }
            }
        }
    }

    private fun offer(rel: Release) {
        AlertDialog.Builder(activity)
            .setTitle("Update available: ${rel.versionName}")
            .setMessage(("You have ${BuildConfig.VERSION_NAME}.\n\n${rel.notes}".trim()) +
                "\n\nMixDeck closes while Android installs it. Open it again when it's done." +
                if (busy()) "\n\nThis stops the music." else "")
            .setPositiveButton("Update now") { _, _ -> install(rel) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun install(rel: Release) {
        if (recording()) {
            AlertDialog.Builder(activity).setTitle("Save your recording first")
                .setMessage("You're recording a mix. Tap REC to stop and save it, then update from ☰.")
                .setPositiveButton("OK", null).show()
            return
        }
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
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity).setTitle("Downloading MixDeck ${rel.versionName}…").setMessage("0%")
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .show()
        thread(name = "update download") {
            val apk = File(activity.cacheDir, "update.apk")
            val result = runCatching {
                Net.download(BuildConfig.UPDATE_URL + "MixDeck.apk", apk) { p ->
                    if (cancelled.get()) throw CancellationException()
                    main.post { dialog.setMessage("${(p * 100).toInt()}%") }
                }
                // Remembered so the new version can say what's new when it opens.
                prefs.edit().putInt("updatedFrom", BuildConfig.VERSION_CODE).putString("updateNotes", rel.notes).commit()
                val installer = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(activity.packageName)
                // Android 12+: once MixDeck has installed itself, later updates need no extra "Update?" screen.
                if (android.os.Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
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
                runCatching { dialog.dismiss() }
                result.exceptionOrNull()?.let {
                    apk.delete()
                    if (it !is CancellationException && !activity.isFinishing) toast("Update failed: ${it.message}")
                }
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
