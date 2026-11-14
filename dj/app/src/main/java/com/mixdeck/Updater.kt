package com.mixdeck

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.mixdeck.UpdatePolicy.Stage
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Updates the app from inside the app, like the computer version. Every push to GitHub builds a new
 * APK and publishes it with a version.json next to it. When that build number is newer, the ☰ badge
 * lights; the APK downloads in the background while you keep mixing, and you choose when Android
 * installs it. With notifications on, a notification ([UpdatedReceiver]) brings you back to the new version.
 *
 * Stages: available → downloading (percent) → downloaded (the APK in filesDir, verified).
 */
class Updater(
    private val activity: Activity,
    /** Music is playing or a mix is being recorded: no popups, and warn before installing. */
    private val busy: () -> Boolean,
    private val recording: () -> Boolean,
    /** The tutorial or the newcomer Start card is showing: no automatic popups, the ☰ badge only. */
    private val quiet: () -> Boolean,
    /** A short notice for the user (the "downloaded" message), shown without stopping anything. */
    private val notify: (String) -> Unit,
    /** When a held-back install can happen: "when the music stops", … ([UpdatePolicy.readyWhen]). */
    private val readyWhen: () -> String = { "when the music stops" },
) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("mixdeck", Context.MODE_PRIVATE)
    data class Release(val versionCode: Int, val versionName: String, val notes: String)

    sealed class State(val rel: Release) {
        class Available(rel: Release) : State(rel)
        class Downloading(rel: Release, val percent: Int, val stop: AtomicBoolean) : State(rel)
        class Downloaded(rel: Release, val apk: File) : State(rel)
    }

    /** Where the update is, or null when there's none. Changed on the main thread only. */
    @Volatile var state: State? = null; private set

    /** True when there's an update for the ☰ badge, in any stage. */
    val hasNews get() = state != null

    @Volatile private var checking = false
    private var checkedThisRun = false
    private var lastTry = 0L
    private var progressDialog: AlertDialog? = null
    /** The install that waits for the notification permission answer, or for "Allow from this source". */
    private var waitingInstall: State.Downloaded? = null
    private var afterSettings: State.Downloaded? = null
    private var installing = false

    /** The ☰ menu's update item, or null when there's no update. */
    fun menuLabel(): String? {
        dropMissing()
        return when (val s = state) {
            null -> null
            is State.Available -> UpdatePolicy.phoneMenuLabel(Stage.AVAILABLE, s.rel.versionName)
            is State.Downloading -> UpdatePolicy.phoneMenuLabel(Stage.DOWNLOADING, s.rel.versionName, s.percent)
            is State.Downloaded -> UpdatePolicy.phoneMenuLabel(Stage.DOWNLOADED, s.rel.versionName)
        }
    }

    /** The ☰ menu's update item. */
    fun menuPick() {
        dropMissing()
        when (val s = state) {
            null -> {}
            is State.Available -> offer(s.rel)
            is State.Downloading -> showProgress(s)
            is State.Downloaded -> askInstall(s)
        }
    }

    // ---------------------------------------------------------------- launch and resume

    /**
     * At launch: an update downloaded earlier (and not installed yet) goes straight back to
     * "downloaded"; anything else that's left over (older versions, a half-finished download) goes.
     */
    fun resumeDownloaded() {
        File(activity.cacheDir, "update.apk").delete() // where older versions downloaded to
        val files = activity.filesDir.listFiles { f -> f.isFile && f.name.startsWith("update-") } ?: return
        val apks = ArrayList<Pair<File, PackageInfo>>()
        for (f in files) {
            if (f.name.endsWith(".part")) {
                // A download that was cut off. (A fresh one may still be running in this process.)
                if (System.currentTimeMillis() - f.lastModified() > 10 * 60_000L) f.delete()
                continue
            }
            val info = if (f.name.endsWith(".apk")) archiveInfo(f) else null
            if (info != null && info.code > BuildConfig.VERSION_CODE) apks += f to info else f.delete()
        }
        val newest = apks.maxByOrNull { it.second.code }
        apks.filter { it !== newest }.forEach { it.first.delete() }
        if (newest == null) {
            prefs.edit().remove("pendingCode").remove("pendingName").remove("pendingNotes").apply()
            return
        }
        val (apk, info) = newest
        val code = info.code.toInt()
        val mine = prefs.getInt("pendingCode", 0) == code
        val name = prefs.getString("pendingName", null)?.takeIf { mine } ?: info.versionName ?: "1.$code"
        val notes = prefs.getString("pendingNotes", null)?.takeIf { mine } ?: ""
        state = State.Downloaded(Release(code, name, notes), apk)
    }

    /**
     * Call from onResume: carries on an install that waited for "Allow from this source", and checks
     * for updates at launch and again whenever 6 hours have passed while MixDeck stays open.
     */
    fun onResume() {
        dropMissing()
        afterSettings?.let { s ->
            afterSettings = null
            if (activity.packageManager.canRequestPackageInstalls() && state === s) install(s)
            return
        }
        val now = System.currentTimeMillis()
        if (!checkedThisRun || UpdatePolicy.checkDue(now, prefs.getLong("lastUpdateCheck", 0L), lastTry)) check(manual = false)
    }

    /** Call from onRequestPermissionsResult: the install goes on whatever the answer was. */
    fun onPermissionResult(requestCode: Int) {
        if (requestCode != REQ_NOTIFY) return
        val s = waitingInstall ?: return
        waitingInstall = null
        if (state === s) commit(s)
    }

    /** After an update: say what's new, once. */
    fun welcomeBack() {
        val from = prefs.getInt("updatedFrom", 0)
        if (from == 0) return
        val notes = prefs.getString("updateNotes", "") ?: ""
        prefs.edit().remove("updatedFrom").remove("updateNotes").apply()
        runCatching { activity.getSystemService(NotificationManager::class.java)?.cancel(UpdatedReceiver.NOTIFICATION) }
        if (BuildConfig.VERSION_CODE > from) AlertDialog.Builder(activity).setTitle("MixDeck updated")
            .setMessage("You're now on MixDeck ${BuildConfig.VERSION_NAME}.\n\n${UpdatePolicy.whatsNew(notes)}".trim())
            .setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- checking

    /** Checks for an update. [manual] = the user asked, so always answer, even when there's none. */
    fun check(manual: Boolean) {
        if (checking && !manual) return
        checking = true
        checkedThisRun = true
        lastTry = System.currentTimeMillis()
        thread(name = "update check") {
            val r = runCatching { fetchRelease() }
            main.post {
                checking = false
                if (gone()) return@post
                val rel = r.getOrNull()
                if (rel != null) prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
                when {
                    rel == null -> if (manual) toast("Couldn't check for updates: ${explain(r.exceptionOrNull()!!)}")
                    rel.versionCode > BuildConfig.VERSION_CODE -> found(rel, manual)
                    manual -> toast("MixDeck is up to date (version ${BuildConfig.VERSION_NAME})")
                }
            }
        }
    }

    private fun fetchRelease(): Release {
        val o = JSONObject(Net.getText(BuildConfig.UPDATE_URL + "version.json?t=" + System.currentTimeMillis()))
        return Release(o.getInt("versionCode"), o.optString("versionName"), o.optString("notes"))
    }

    private fun found(rel: Release, manual: Boolean) {
        val s = state
        if (s is State.Downloading || (s != null && s.rel.versionCode >= rel.versionCode)) {
            // Already found, on its way or downloaded: the user's own check shows where it is;
            // an automatic one only reminds when nothing's going on and they didn't say "Later".
            if (manual || (s !is State.Downloading && offerNow(s.rel))) menuPick()
            return
        }
        if (s is State.Downloaded) s.apk.delete() // an older download: this newer one replaces it
        state = State.Available(rel)
        if (UpdatePolicy.shouldOffer(manual, rel.versionCode, prefs.getInt("laterVersion", 0), busy(), quiet())) offer(rel)
    }

    private fun offerNow(rel: Release) =
        UpdatePolicy.shouldOffer(false, rel.versionCode, prefs.getInt("laterVersion", 0), busy(), quiet())

    /** "Later": automatic checks don't pop up for this version again (the ☰ badge and row stay). */
    private fun later(rel: Release) = prefs.edit().putInt("laterVersion", rel.versionCode).apply()

    private fun offer(rel: Release) {
        // This copy can't install it (found out at an earlier try): say so instead of downloading again.
        if (prefs.getBoolean("otherBuild", false)) return otherBuild(rel)
        val news = UpdatePolicy.whatsNew(rel.notes)
        show(AlertDialog.Builder(activity)
            .setTitle("Update available: ${rel.versionName}")
            .setMessage("MixDeck ${rel.versionName} is out (you have ${BuildConfig.VERSION_NAME}).\n\n" +
                (if (news.isEmpty()) "" else "$news\n\n") +
                "It downloads in the background while you keep mixing. You choose when to install.")
            .setPositiveButton("Download") { _, _ -> download(rel) }
            .setNegativeButton("Later") { _, _ -> later(rel) })
    }

    // ---------------------------------------------------------------- downloading

    private fun download(rel: Release) {
        if (state is State.Downloading) return
        val stop = AtomicBoolean(false)
        state = State.Downloading(rel, 0, stop)
        // Choosing Download overrides an earlier "Later": the "ready to install" question may come back.
        prefs.edit().remove("laterVersion").apply()
        val apk = File(activity.filesDir, "update-${rel.versionCode}.apk")
        thread(name = "update download") {
            val r = runCatching {
                var shown = -1
                Net.download(BuildConfig.UPDATE_URL + "MixDeck.apk", apk) { p ->
                    if (stop.get()) throw CancellationException()
                    val pct = (p * 100).toInt().coerceIn(0, 100)
                    if (pct != shown) { shown = pct; main.post { progressed(stop, pct) } }
                }
                verify(rel, apk)
            }
            main.post { downloaded(rel, apk, r.getOrNull(), r.exceptionOrNull()) }
        }
    }

    /**
     * Checks the downloaded file is a MixDeck newer than this one. Normally it's exactly [rel]; if a
     * newer build was published since the check, that one is kept (with its own notes).
     */
    private fun verify(rel: Release, apk: File): Pair<Release, File> {
        val info = archiveInfo(apk) ?: run { apk.delete(); throw IOException("the file isn't a MixDeck app") }
        if (!sameSigner(apk)) { apk.delete(); throw OtherBuild() }
        val code = info.code.toInt()
        if (code == rel.versionCode) return rel to apk
        if (code <= BuildConfig.VERSION_CODE) { apk.delete(); throw IOException("the file is version ${info.versionName}, not ${rel.versionName}") }
        val newer = runCatching { fetchRelease() }.getOrNull()?.takeIf { it.versionCode == code }
            ?: Release(code, info.versionName ?: "1.$code", "")
        val moved = File(apk.parentFile, "update-$code.apk")
        if (!apk.renameTo(moved)) { apk.delete(); throw IOException("Could not save the download") }
        return newer to moved
    }

    private fun progressed(stop: AtomicBoolean, pct: Int) {
        val s = state as? State.Downloading ?: return
        if (s.stop !== stop) return
        state = State.Downloading(s.rel, pct, stop)
        progressDialog?.setMessage(progressText(s.rel, pct))
    }

    private fun progressText(rel: Release, pct: Int) = "Downloading MixDeck ${rel.versionName} ($pct%)"

    private fun showProgress(s: State.Downloading) {
        progressDialog?.dismiss()
        val d = show(AlertDialog.Builder(activity)
            .setMessage(progressText(s.rel, s.percent))
            .setPositiveButton("Hide", null)
            .setNegativeButton("Stop download") { _, _ -> s.stop.set(true) })
        d?.setOnDismissListener { if (progressDialog === d) progressDialog = null }
        progressDialog = d
    }

    /** The download of [rel] to [apk] ended: [got] is the verified release and file, or [error] says why not. */
    private fun downloaded(rel: Release, apk: File, got: Pair<Release, File>?, error: Throwable?) {
        runCatching { progressDialog?.dismiss() }
        progressDialog = null
        if (got != null) {
            val (r, file) = got
            // Remembered so the downloaded file can be offered again after MixDeck is closed.
            prefs.edit().putInt("pendingCode", r.versionCode).putString("pendingName", r.versionName)
                .putString("pendingNotes", r.notes).apply()
            val s = State.Downloaded(r, file)
            state = s
            if (gone()) return
            if (!busy() && !quiet()) askInstall(s)
            else notify("MixDeck ${r.versionName} is downloaded · install it from ☰ ${readyWhen()}")
        } else {
            apk.delete()
            state = State.Available(rel)
            if (error is OtherBuild) {
                // No popup mid-set: ☰ explains it (offer and the menu row ask otherBuild first).
                if (gone() || !busy() && !quiet()) return otherBuild(rel)
                prefs.edit().putBoolean("otherBuild", true).apply()
                later(rel)
                return notify("MixDeck ${rel.versionName} can't install over this copy · see ☰")
            }
            if (error !is CancellationException && !gone())
                toast("The update didn't download: ${explain(error ?: IOException("unknown error")).trimEnd('.')}. Try again from ☰.")
        }
    }

    // ---------------------------------------------------------------- installing

    private fun askInstall(s: State.Downloaded) {
        // Only promise the way back that will come: with notifications off, the user opens it again.
        val back = if (notificationsOn()) "MixDeck closes for a few seconds while Android installs it. A notification brings you back."
            else "MixDeck closes while Android installs it. Open it again when it's done."
        show(AlertDialog.Builder(activity)
            .setTitle("MixDeck ${s.rel.versionName} is ready")
            .setMessage("Install it now? $back" + if (busy()) "\n\nThis stops the music." else "")
            .setPositiveButton("Install now") { _, _ -> install(s) }
            .setNegativeButton("Later") { _, _ -> later(s.rel) })
    }

    private fun install(s: State.Downloaded) {
        if (recording()) {
            show(AlertDialog.Builder(activity).setTitle("Save your mix first")
                .setMessage("Your mix isn't saved yet. If you're recording, hold REC to stop. " +
                    "Once REC no longer shows SAVING, the mix is in My mixes: then update from ☰.")
                .setPositiveButton("OK", null))
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            show(AlertDialog.Builder(activity)
                .setTitle("Allow updates")
                .setMessage("Android needs your OK once for MixDeck to install its own updates. " +
                    "Turn on \"Allow from this source\", then come back to MixDeck and the update carries on.")
                .setPositiveButton("Open settings") { _, _ ->
                    afterSettings = s
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton("Cancel", null))
            return
        }
        // Android 13+: ask once to post the "MixDeck updated" notification; the install goes on either way.
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotifications", false) &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            waitingInstall = s
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        commit(s)
    }

    /** Hands the downloaded APK to Android's package installer. */
    private fun commit(s: State.Downloaded) {
        if (installing) return
        if (!s.apk.isFile) {
            state = State.Available(s.rel)
            toast("The update didn't download: the file is gone. Try again from ☰.")
            return
        }
        installing = true
        // Remembered so the new version can say what's new (and post its notification) when it opens.
        prefs.edit().putInt("updatedFrom", BuildConfig.VERSION_CODE).putString("updateNotes", s.rel.notes).commit()
        toast("Installing MixDeck ${s.rel.versionName}…")
        thread(name = "update install") {
            val r = runCatching {
                val installer = activity.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(activity.packageName)
                // Android 12+: once MixDeck has installed itself, later updates need no extra "Update?" screen.
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("MixDeck.apk", 0, s.apk.length()).use { out ->
                        s.apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val intent = Intent(activity, UpdateReceiver::class.java)
                    val pi = PendingIntent.getBroadcast(activity, id, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pi.intentSender)
                }
            }
            main.post {
                installing = false
                r.exceptionOrNull()?.let {
                    prefs.edit().remove("updatedFrom").remove("updateNotes").apply()
                    if (!gone()) toast("Update not installed: ${it.message ?: it.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * The installer's answer deleted the downloaded file ([UpdateReceiver]): the update is just
     * available again, not ready to install.
     */
    private fun dropMissing() {
        val s = state
        if (s is State.Downloaded && !s.apk.isFile) state = State.Available(s.rel)
    }

    /** The update is signed by another build than this copy, so Android won't install it: say what to do (once per version). */
    private fun otherBuild(rel: Release) {
        prefs.edit().putBoolean("otherBuild", true).apply()
        later(rel)
        show(AlertDialog.Builder(activity).setTitle("MixDeck ${rel.versionName} is out")
            .setMessage(otherBuildText()).setPositiveButton("OK", null))
    }

    /**
     * Whether [apk] is signed like this copy of MixDeck (if not, Android won't install it over this
     * one). True when that can't be told: the installer decides then.
     */
    @Suppress("DEPRECATION")
    private fun sameSigner(apk: File): Boolean = runCatching {
        val pm = activity.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        fun certs(i: PackageInfo?): Set<String> = (if (Build.VERSION.SDK_INT >= 28) i?.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else i?.signatures).orEmpty().map { it.toCharsString() }.toSet()
        val mine = certs(pm.getPackageInfo(activity.packageName, flag))
        val theirs = certs(pm.getPackageArchiveInfo(apk.path, flag))
        mine.isEmpty() || theirs.isEmpty() || mine.any { it in theirs }
    }.getOrDefault(true)

    /** The download is signed by another build than this copy of MixDeck. */
    private class OtherBuild : IOException("signed by another build")

    // ---------------------------------------------------------------- helpers

    /** Notifications are on, so one can bring you back after the install. */
    private fun notificationsOn(): Boolean =
        runCatching { activity.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() }.getOrNull() == true

    @Suppress("DEPRECATION")
    private fun archiveInfo(f: File): PackageInfo? =
        runCatching { activity.packageManager.getPackageArchiveInfo(f.path, 0) }.getOrNull()
            ?.takeIf { it.packageName == activity.packageName }

    private val PackageInfo.code: Long
        @Suppress("DEPRECATION") get() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    /** In plain words, as the console's notices ("No internet connection. Check Wi-Fi and try again."), about the update server. */
    private fun explain(e: Throwable): String = Problems.explainUpdate(e)

    private fun gone() = activity.isFinishing || activity.isDestroyed

    private fun show(b: AlertDialog.Builder): AlertDialog? = if (gone()) null else b.show()

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()

    companion object {
        /** MainActivity forwards onRequestPermissionsResult with this code. */
        const val REQ_NOTIFY = 7

        /** This copy was installed from another build (signed with another key): what to do to get updates. */
        fun otherBuildText(): String = "This copy of MixDeck came from a different build, so it can't install updates itself. " +
            "To get them, uninstall MixDeck and install it again from GitHub. " +
            if (Build.VERSION.SDK_INT < 29) "Uninstalling also deletes the songs and mixes saved in MixDeck."
            else "Uninstalling also deletes the songs saved in MixDeck (your mixes in Music/MixDeck stay)."

        /**
         * The installer refused the downloaded update for good: drop the file and the pending
         * install, and don't pop it up again. [otherBuild]: because this copy is signed differently.
         */
        fun dropDownload(context: Context, otherBuild: Boolean) {
            val prefs = context.getSharedPreferences("mixdeck", Context.MODE_PRIVATE)
            context.filesDir.listFiles { f -> f.name.startsWith("update-") && f.name.endsWith(".apk") }?.forEach { it.delete() }
            val e = prefs.edit().remove("updatedFrom").remove("updateNotes")
                .putInt("laterVersion", prefs.getInt("pendingCode", 0))
                .remove("pendingCode").remove("pendingName").remove("pendingNotes")
            if (otherBuild) e.putBoolean("otherBuild", true)
            e.apply()
        }
    }
}

/** Gets the package installer's answer: shows Android's "Update this app?" screen, or reports errors. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
        when {
            status == PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            // The new version takes over from here (UpdatedReceiver).
            status == PackageInstaller.STATUS_SUCCESS -> {}
            // Signed with another key (a test build installed by hand; Updater.verify usually finds this
            // first): this copy can never take the update. Drop it so it isn't offered at every launch.
            status == PackageInstaller.STATUS_FAILURE_CONFLICT && msg.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") -> {
                Updater.dropDownload(context, otherBuild = true)
                Toast.makeText(context, Updater.otherBuildText(), Toast.LENGTH_LONG).show()
            }
            // This phone can't run this version (it needs a newer Android, say): reinstalling wouldn't help.
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Updater.dropDownload(context, otherBuild = false)
                Toast.makeText(context, "This phone can't install this MixDeck version. MixDeck stays as it is.", Toast.LENGTH_LONG).show()
            }
            else -> {
                // Not installed after all: the new version won't open, so don't let this one think it did.
                context.getSharedPreferences("mixdeck", Context.MODE_PRIVATE).edit()
                    .remove("updatedFrom").remove("updateNotes").apply()
                Toast.makeText(context, "Update not installed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * Runs in the new version right after an update replaced MixDeck (Android closed the old one to
 * install it). Posts a notification that brings you back; opening MixDeck then says what's new.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("mixdeck", Context.MODE_PRIVATE)
        if (prefs.getInt("updatedFrom", 0) == 0) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("MixDeck updated to ${BuildConfig.VERSION_NAME}")
                .setContentText("Tap to open MixDeck")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION, n)
        }
    }

    companion object {
        const val CHANNEL = "updates"
        const val NOTIFICATION = 1
    }
}
