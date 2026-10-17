package com.bouzouki

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 🔧 Lab tools: measurements to run on the phone itself (which note engine is faster, what instrument separation
 * costs, whether listening to another app works), each reported in a dialog that can be copied to report it.
 * Nothing is sent anywhere.
 */
object LabTools {
    /** A tool: its name in the menu, and the work (off the main thread) that returns its report. */
    class Tool(val name: String, val run: (Activity) -> String)

    /** Tools other parts of the app add (separation cost, capture test). */
    val extra = ArrayList<Tool>()

    fun show(activity: Activity) {
        val tools = listOf(Tool("⏱ Note engines: ONNX Runtime vs LiteRT") { PitchEngines.benchmark(it) }) + extra
        val fingering = "🎚 Fingering: engine and weights"
        val engine = PitchEngines.chosen(activity).label
        AlertDialog.Builder(activity)
            .setTitle("🔧 Lab tools · note engine: $engine")
            .setItems((tools.map { it.name } + fingering).toTypedArray()) { _, i ->
                if (i < tools.size) run(activity, tools[i]) else FingeringPrefs.edit(activity, Settings(activity).instrument)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    fun run(activity: Activity, tool: Tool) {
        @Suppress("DEPRECATION")
        val wait = ProgressDialog.show(activity, tool.name, "Measuring on this phone…", true, false)
        thread(name = "lab-tool") {
            val report = try { tool.run(activity) } catch (t: Throwable) { "Failed: ${t.javaClass.simpleName}: ${t.message}" }
            activity.runOnUiThread {
                wait.dismiss()
                if (!activity.isFinishing) report(activity, tool.name, report)
            }
        }
    }

    fun report(activity: Activity, title: String, text: String) {
        val ui = Ui(activity)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply {
                addView(TextView(activity).apply {
                    this.text = text
                    textSize = 13f
                    setTextIsSelectable(true)
                    setPadding(ui.px(24), ui.px(8), ui.px(24), ui.px(8))
                })
            })
            .setPositiveButton("Copy") { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, "$title\n$text"))
                Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
