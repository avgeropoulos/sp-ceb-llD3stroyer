package com.bouzouki

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.bouzouki.core.Instrument
import com.bouzouki.core.song.Fingering
import com.bouzouki.core.song.TuttutFingering

/**
 * The player's fingering settings: which engine places the notes ([Fingering.Engine]) and every one of its weights,
 * for each number of courses, kept as "name = value" text ([Fingering.Weights.text]). [apply] hands them to the
 * fingering, which every song (saved ones too) is placed with when it is shown.
 */
object FingeringPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("bouzouki", Context.MODE_PRIVATE)

    fun apply(context: Context) {
        val p = prefs(context)
        Fingering.engine = Fingering.Engine.entries.firstOrNull { it.name == p.getString(PREF_ENGINE, null) } ?: Fingering.Engine.HMM
        val hmm = HashMap<Int, Fingering.Weights>()
        val tut = HashMap<Int, TuttutFingering.Weights>()
        for (n in 1..8) {
            p.getString("${PREF_HMM}$n", null)?.let { hmm[n] = defaults(n).with(Fingering.Weights.parse(it)) }
            p.getString("${PREF_TUTTUT}$n", null)?.let { tut[n] = tuttutDefaults(n).with(Fingering.Weights.parse(it), n) }
        }
        Fingering.custom = hmm
        Fingering.tuttut = tut
    }

    /** The built-in weights for [n] courses (what [Fingering.weights] gives without the player's). */
    private fun defaults(n: Int) = when (n) { 4 -> Fingering.TETRACHORDO; 3 -> Fingering.TRICHORDO; else -> Fingering.forCourses(n) }

    private fun tuttutDefaults(n: Int) = TuttutFingering.Weights(course = defaults(n).course.copyOf())

    /** 🎚 Fingering: the engine, and its weights for [inst]'s number of courses, to change or set back to the defaults. */
    fun edit(activity: Activity, inst: Instrument) {
        apply(activity)
        val n = inst.courses.size
        val ui = Ui(activity)
        val p = prefs(activity)
        fun textFor(e: Fingering.Engine) = when (e) {
            Fingering.Engine.HMM -> (Fingering.custom[n] ?: defaults(n)).text()
            Fingering.Engine.TUTTUT -> (Fingering.tuttut[n] ?: tuttutDefaults(n)).toMap().entries.joinToString("\n") { (k, v) -> "$k = $v" }
        }
        var engine = Fingering.engine
        val box = EditText(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8
            setText(textFor(engine))
        }
        val group = RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            for (e in Fingering.Engine.entries) addView(RadioButton(activity).apply {
                id = e.ordinal + 1
                text = if (e == Fingering.Engine.HMM) "Bouzouki HMM" else "tuttut HMM"
                isChecked = e == engine
            })
            setOnCheckedChangeListener { _, id -> engine = Fingering.Engine.entries[id - 1]; box.setText(textFor(engine)) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(20), ui.px(4), ui.px(20), 0)
            addView(TextView(activity).apply {
                text = "${inst.greekName} · ${inst.tuning} ($n courses). Every song is fingered again with these when it's shown. " +
                    "Costs: higher means less likely (a course's cost, crossing between courses, moving the hand, …)."
                textSize = 13f
            })
            addView(group)
            addView(box)
        }
        AlertDialog.Builder(activity)
            .setTitle("🎚 Fingering")
            .setView(android.widget.ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Save") { _, _ ->
                val key = if (engine == Fingering.Engine.HMM) PREF_HMM else PREF_TUTTUT
                p.edit().putString(PREF_ENGINE, engine.name).putString("$key$n", box.text.toString()).apply()
                apply(activity)
            }
            .setNeutralButton("Defaults") { _, _ ->
                val key = if (engine == Fingering.Engine.HMM) PREF_HMM else PREF_TUTTUT
                p.edit().putString(PREF_ENGINE, engine.name).remove("$key$n").apply()
                apply(activity)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private const val PREF_ENGINE = "fingering_engine"
    private const val PREF_HMM = "fingering_hmm_"
    private const val PREF_TUTTUT = "fingering_tuttut_"
}
