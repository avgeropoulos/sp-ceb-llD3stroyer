package com.bouzouki.hero

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * If Bouzouki Hero crashes, keeps what went wrong in a file, and shows it the next time the
 * game's menu opens, with a button to copy it (to paste into a bug report).
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "hero-crash.txt")

    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val version = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (_: Exception) { "?" }
                file(app).writeText(
                    "Bouzouki Hero crash (app ${version}, Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}, thread ${thread.name})\n$sw"
                )
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Shows the last crash, if there was one, once. */
    fun offer(activity: Activity) {
        val f = file(activity)
        if (!f.exists()) return
        val text = try { f.readText() } catch (_: Exception) { "" }
        f.delete()
        if (text.isBlank()) return
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Bouzouki Hero crashed last time")
            .setMessage("Sorry! Tap Copy and paste this to Claude so it can be fixed:\n\n" + text.take(1500))
            .setPositiveButton("Copy") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Bouzouki Hero crash", text))
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
