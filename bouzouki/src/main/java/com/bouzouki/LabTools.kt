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
        val tools = listOf(
            Tool("⏱ Note engines: ONNX Runtime vs LiteRT") { PitchEngines.benchmark(it) },
            Tool("⏱ Separation: time and memory of each kind") { separationCost(it) },
        ) + extra
        val more = listOf(
            "🎧 Capture test (play a song in YouTube first)" to {
                activity.startActivity(android.content.Intent(activity, SongActivity::class.java).setAction(SongActivity.ACTION_CAPTURE_TEST))
            },
            "🧩 Separation: which kind, and get its model" to { chooseSeparation(activity) },
            "⚖ Compare two songs (solo take vs heard in a mix)" to { compareSongs(activity) },
            "🎚 Fingering: engine and weights" to { FingeringPrefs.edit(activity, Settings(activity).instrument) },
        )
        val engine = PitchEngines.chosen(activity).label
        AlertDialog.Builder(activity)
            .setTitle("🔧 Lab tools · note engine: $engine")
            .setItems((tools.map { it.name } + more.map { it.first }).toTypedArray()) { _, i ->
                if (i < tools.size) run(activity, tools[i]) else more[i - tools.size].second()
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

    /**
     * Each separation kind on 20 s of a synthetic band (the app's bouzouki with a bass, drums and a voice-like lead):
     * seconds per second of audio and the process's peak memory over it. Demucs only if its model is on the phone.
     */
    private fun separationCost(activity: Activity): String {
        val (l, r) = testMix(20.0)
        val lines = ArrayList<String>()
        for (kind in listOf(Separation.Kind.LIGHT, Separation.Kind.DEMUCS_TINY, Separation.Kind.DEMUCS_LITE, Separation.Kind.DEMUCS)) {
            val file = Separation.modelFile(activity, kind)
            if (file != null && !file.isFile) { lines += "${kind.name}: model not on the phone (🧩 Separation gets it)"; continue }
            System.gc()
            val base = rssMb()
            val peakMb = java.util.concurrent.atomic.AtomicLong(base)
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val sampler = thread { while (running.get()) { peakMb.accumulateAndGet(rssMb()) { a, b -> maxOf(a, b) }; Thread.sleep(50) } }
            val t0 = System.nanoTime()
            val label = try {
                Separation.open(activity, kind, { _, _ -> }, { false }).use { sep ->
                    sep.separator.instrumentalMono(l, r, {}, { false })
                    sep.label
                }
            } catch (t: Throwable) { "failed: ${t.javaClass.simpleName} ${t.message}" }
            val secs = (System.nanoTime() - t0) / 1e9
            running.set(false)
            sampler.join()
            lines += String.format(java.util.Locale.US, "%s: %.1f s for 20 s (%.2f s per second), peak memory +%d MB · %s", kind.name, secs, secs / 20, peakMb.get() - base, label)
        }
        val mem = android.app.ActivityManager.MemoryInfo().also { (activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
        lines += "Phone memory: ${mem.totalMem / 1_000_000} MB; chosen now: ${Separation.chosen(activity).name}"
        lines += "${Runtime.getRuntime().availableProcessors()} cores, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}"
        return lines.joinToString("\n")
    }

    /** This process's resident memory (MB), from /proc/self/status. */
    private fun rssMb(): Long = try {
        java.io.File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.div(1024) ?: 0
    } catch (_: Exception) { 0 }

    /** [seconds] of a synthetic band at 44.1 kHz, stereo: the app's bouzouki, a bass, noise drums and a buzzy lead. */
    private fun testMix(seconds: Double): Pair<FloatArray, FloatArray> {
        val fs = 44100
        val n = (seconds * fs).toInt()
        val synth = com.bouzouki.core.BouzoukiSynth(fs)
        val bz = FloatArray(n)
        val melody = intArrayOf(62, 63, 66, 67, 69, 70, 72, 74, 72, 70, 69, 67, 66, 63)
        var pos = 0
        var k = 0
        while (true) {
            val at = (k * 0.25 * fs).toInt()
            if (at >= n) break
            synth.render(bz, pos, at - pos); pos = at
            synth.pluck(3, melody[k % melody.size], false)
            k++
        }
        synth.render(bz, pos, n - pos)
        val rnd = java.util.Random(3)
        val l = FloatArray(n); val r = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / fs
            val beat = t % 0.5
            val bass = 0.2 * kotlin.math.sin(2 * Math.PI * 73.4 * t) * kotlin.math.exp(-beat * 4)
            val drum = 0.15 * rnd.nextGaussian() * kotlin.math.exp(-beat * 40)
            phase += 146.8 / fs
            val lead = 0.05 * (2 * (phase - kotlin.math.floor(phase)) - 1)
            val c = (bass + drum + lead).toFloat()
            l[i] = 0.7f * bz[i] + c; r[i] = 0.9f * bz[i] + c
        }
        return l to r
    }

    /** 🧩: which separation songs with a band get, and getting its model (once, from the app's model release). */
    private fun chooseSeparation(activity: Activity) {
        val kinds = Separation.Kind.entries.filter { it != Separation.Kind.NONE }
        val names = listOf("Automatic (by the phone's memory: now ${Separation.chosen(activity).name})") + kinds.map { k ->
            k.label + (Separation.modelFile(activity, k)?.let { if (it.isFile) " · model on the phone" else " · model to download" } ?: "")
        }
        AlertDialog.Builder(activity)
            .setTitle("🧩 Separation of a band's songs")
            .setItems(names.toTypedArray()) { _, i ->
                val kind = if (i == 0) null else kinds[i - 1]
                Separation.choose(activity, kind)
                val k = kind ?: Separation.chosen(activity)
                if (Separation.modelFile(activity, k)?.isFile == false) {
                    run(activity, Tool("Getting the ${k.name} model") { a ->
                        Separation.ensureModel(a, k, { _, _ -> }, { false }).let { "Downloaded and checked: ${it.name} (${it.length() / 1_000_000} MB)." }
                    })
                } else Toast.makeText(activity, "Songs with a band: ${k.label}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** ⚖: two saved songs, the first as the reference (the player alone), the second lined up and scored against it. */
    private fun compareSongs(activity: Activity) {
        val songs = SongJobs.recent(activity)
        if (songs.size < 2) { Toast.makeText(activity, "Prepare the song twice first: alone (🎤 or 📁) and heard in its mix (🎧).", Toast.LENGTH_LONG).show(); return }
        val titles = songs.map { it.title }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("The reference (the bouzouki alone)").setItems(titles) { _, a ->
            AlertDialog.Builder(activity).setTitle("Compared with (heard in the mix)").setItems(titles) { _, b ->
                val ref = SongJobs.cached(activity, songs[a].id)
                val other = SongJobs.cached(activity, songs[b].id)
                if (ref == null || other == null) { Toast.makeText(activity, "Prepare both songs again first.", Toast.LENGTH_LONG).show(); return@setItems }
                val c = com.bouzouki.core.song.SongCompare.of(ref.notes, other.notes)
                report(activity, "⚖ ${songs[b].title} against ${songs[a].title}",
                    "$c\n\nNotes: the reference's ${ref.notes.size}, the other's ${other.notes.size}. The same pitch with onsets within " +
                        "50 ms counts (after lining the two up). With the solo take as the truth, this is how well the tab heard in the mix matches it.")
            }.show()
        }.show()
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
