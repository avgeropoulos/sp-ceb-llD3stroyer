package com.bouzouki

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.bouzouki.core.Instrument
import com.bouzouki.core.song.BasicPitch
import com.bouzouki.core.song.Calibration
import com.bouzouki.core.song.PostConfig
import com.bouzouki.core.song.resample
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 🎯 Calibrate: a moment of silence, then each open course plucked three times, to measure the player's bouzouki and
 * room ([Calibration]): how it's tuned (cents from A = 440 Hz, and the courses' notes), how loud its octave strings are
 * against their courses, and the noise floor. Kept on the phone ([CalibrationStore]) and used when songs are prepared.
 * The recordings stay in memory while they're measured, and are gone after.
 */
class CalibrationActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private val settings by lazy { Settings(this) }
    private lateinit var step: TextView
    private lateinit var meter: ProgressBar
    private lateinit var result: TextView
    private lateinit var start: Button
    @Volatile private var cancelled = false
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = GREEK_BLUE
        window.navigationBarColor = NAVY
        val px = ui::px
        val inst = settings.instrument
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(NAVY) }
        root.addView(TextView(this).apply {
            text = "🎯 Calibrate your bouzouki"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WHITE)
            setBackgroundColor(GREEK_BLUE)
            setPadding(px(16), px(14), px(16), px(12))
        })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(12), px(16), px(16)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(TextView(this).apply {
            text = "${inst.greekName} · ${inst.tuning}\n\nIn a quiet room, hold the phone near the bouzouki. First stay silent for two seconds; " +
                "then pluck each open course three times when asked, from the lowest course to the highest, letting each ring.\n\n" +
                "This measures how your bouzouki is tuned, how loud its octave strings are, and the room's noise, so songs you " +
                "record are transcribed for your instrument. The sound stays on the phone and is deleted once it's measured."
            textSize = 15f
            setTextColor(0xFFE8EEF6.toInt())
            setLineSpacing(0f, 1.2f)
        })
        step = TextView(this).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFD54F.toInt()); setPadding(0, px(16), 0, px(8)) }
        body.addView(step)
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        body.addView(meter)
        start = ui.chip("Start") { begin() }.apply { textSize = 18f; minHeight = px(60) }
        ui.style(start, true)
        body.addView(start, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) })
        result = TextView(this).apply { textSize = 14f; setTextColor(0xFFE8EEF6.toInt()); typeface = Typeface.MONOSPACE; setPadding(0, px(14), 0, 0) }
        body.addView(result)
        CalibrationStore.load(this)?.let { result.text = "Calibrated before:\n" + describe(it) }
        setContentView(root)
        fitSystemBars(this, root)
    }

    private fun begin() {
        if (running) { cancelled = true; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val inst = settings.instrument
        running = true
        cancelled = false
        start.text = "Stop"
        result.text = ""
        val app = applicationContext
        thread(name = "calibrate") {
            val outcome = try {
                show("Silence, please…")
                val silence = MicRecorder.record(app, 2.0, ::level) { cancelled }
                val plucks = ArrayList<FloatArray>()
                for ((c, course) in inst.courses.withIndex()) {
                    if (cancelled) break
                    val name = Instrument.pitchName(course.openMidi) + if (course.octavePair) "/" + Instrument.pitchName(course.openMidi + 12) else ""
                    show("Course ${c + 1} of ${inst.courses.size} ($name): pluck it open three times")
                    plucks += MicRecorder.record(app, 4.5, ::level) { cancelled }
                }
                if (cancelled) null else {
                    show("Measuring…")
                    PitchEngines.open(app).use { m ->
                        fun down(x: FloatArray) = resample(x, MicRecorder.RATE, BasicPitch.SAMPLE_RATE)
                        Result.success(Calibration.measure(down(silence), plucks.map { down(it) }, inst, m.pitch))
                    }
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
            runOnUiThread { finished(outcome) }
        }
    }

    private fun show(text: String) = runOnUiThread { step.text = text }
    private fun level(rms: Float) = runOnUiThread { meter.progress = (rms * 400).roundToInt().coerceIn(0, 100) }

    private fun finished(outcome: Result<Calibration>?) {
        running = false
        start.text = "Start again"
        meter.progress = 0
        if (outcome == null) { step.text = "Stopped."; return }
        val cal = outcome.getOrElse { step.text = "Couldn't calibrate: ${it.message}"; return }
        step.text = "Done"
        result.text = describe(cal)
        val inst = settings.instrument
        val heard = cal.tuning
        val sameTuning = heard.courses.map { it.openMidi } == inst.courses.map { it.openMidi }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Keep this calibration?")
            .setMessage(describe(cal))
            .setPositiveButton("Keep") { _, _ -> CalibrationStore.save(this, cal); step.text = "Kept: songs you prepare use it." }
            .setNegativeButton("Discard", null)
        if (!sameTuning) dialog.setNeutralButton("Keep, and use the tuning heard") { _, _ ->
            CalibrationStore.save(this, cal)
            if (!heard.standard) settings.customTuning = heard
            settings.instrument = heard
            step.text = "Kept, and the bouzouki is now ${heard.tuning}."
        }
        dialog.show()
    }

    private fun describe(cal: Calibration): String = buildString {
        val off = cal.cents.roundToInt()
        append(String.format(Locale.US, "Tuning: %+d cents from A = 440 Hz%s\n", off, if (abs(off) < 5) " (in tune)" else if (off < 0) " (flat)" else " (sharp)"))
        append("Courses heard: ${cal.tuning.tuning}\n")
        for ((c, r) in cal.courses.withIndex()) {
            append(String.format(Locale.US, "  %d. %-4s %+4d¢%s\n", c + 1, Instrument.pitchName(r.midi), r.cents.roundToInt(),
                r.octaveRatio?.let { String.format(Locale.US, "  octave string %.2f× as loud", it) } ?: ""))
        }
        cal.octaveRatio?.let { append(String.format(Locale.US, "Octave strings: %.2f× their courses (octave notes merged up to %.1f×)\n", it, cal.post(PostConfig()).octaveMaxRatio)) }
        append(String.format(Locale.US, "Room noise: %.0f dBFS (note activation %.2f)", cal.noiseDb, cal.noiseFloor))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin()
        else if (requestCode == REQ_MIC) step.text = "Calibration needs the microphone."
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private companion object { const val REQ_MIC = 21 }
}

/** The calibration kept on the phone (one, the last one kept). */
object CalibrationStore {
    private fun prefs(context: Context) = context.getSharedPreferences("bouzouki", Context.MODE_PRIVATE)
    fun load(context: Context): Calibration? = prefs(context).getString("calibration", null)?.let { Calibration.fromJson(it) }
    fun save(context: Context, cal: Calibration) = prefs(context).edit().putString("calibration", cal.toJson()).apply()

    /**
     * The post-processing for a recording ([solo]: the bouzouki alone), with the calibration if there is one: in a solo
     * take everything it measured, in a band's song only the octave strings' loudness (the room and microphone differ).
     */
    fun post(context: Context, solo: Boolean): PostConfig {
        val base = PostConfig(solo = solo)
        val cal = load(context) ?: return base
        return if (solo) cal.post(base) else base.calibrated(cal.octaveRatio, null)
    }
}
