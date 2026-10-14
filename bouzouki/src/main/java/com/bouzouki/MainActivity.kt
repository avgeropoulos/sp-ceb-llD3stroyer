package com.bouzouki

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.bouzouki.core.Dromos
import com.bouzouki.core.Instrument
import com.bouzouki.core.NoteNames

/** The setup screen: pick the bouzouki, the dromos and the root, then open the neck on its own screen. */
class MainActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private val settings by lazy { Settings(this) }

    private lateinit var info: TextView
    private lateinit var rootSpinner: Spinner
    private val instrumentButtons = HashMap<Instrument, Button>()
    private lateinit var namesButton: Button
    private lateinit var allButton: Button
    private var rootNames: NoteNames? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GREEK_BLUE
        window.navigationBarColor = NAVY
        val px = ui::px

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAVY)
        }

        // Title.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(GREEK_BLUE)
            setPadding(px(16), px(14), px(16), px(10))
            addView(TextView(context).apply {
                text = "Μπουζούκι"
                textSize = 30f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                setTextColor(WHITE)
            })
            addView(TextView(context).apply {
                val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { null }
                text = "Greek Bouzouki · Δρόμοι (Greek scales)" + (version?.let { "  ·  v$it" } ?: "")
                textSize = 13f
                setTextColor(0xFFCFE0F5.toInt())
            })
        })
        root.addView(MeanderView(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(14)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(10), px(14), px(14))
        }
        root.addView(ScrollView(this).apply { addView(controls) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Trichordo / Tetrachordo.
        controls.addView(ui.label("Όργανο · Instrument"))
        controls.addView(ui.row().apply {
            for (inst in Instrument.entries) {
                val b = ui.chip("${inst.greekName}\n${inst.englishName.substringBefore(" ·")} · ${inst.englishName.substringAfterLast("· ")}") {
                    settings.instrument = inst
                    settings.course = 0
                    refresh()
                }
                b.textSize = 13f
                b.minHeight = px(56)
                instrumentButtons[inst] = b
                addView(b, ui.weighted())
            }
        })

        // Dromos.
        controls.addView(ui.label("Δρόμος · Scale"))
        controls.addView(Spinner(this).apply {
            adapter = darkAdapter(Dromos.entries.map { it.title })
            setSelection(settings.dromos.ordinal)
            background = ui.rounded()
            onItemSelectedListener = selected { pos -> settings.dromos = Dromos.entries[pos]; refresh() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50)))

        // Root.
        controls.addView(ui.label("Τονική · Root note"))
        rootSpinner = Spinner(this).apply {
            background = ui.rounded()
            onItemSelectedListener = selected { pos -> settings.rootPc = pos; refresh() }
        }
        controls.addView(rootSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50)))

        // Note names.
        controls.addView(ui.label("Νότες · Note names"))
        namesButton = ui.chip("") {
            settings.names = if (settings.names == NoteNames.WESTERN) NoteNames.GREEK else NoteNames.WESTERN
            refresh()
        }
        allButton = ui.chip("All notes") { settings.showAllNotes = !settings.showAllNotes; refresh() }
        controls.addView(ui.row().apply {
            addView(namesButton, ui.weighted())
            addView(allButton, ui.weighted())
        })

        info = TextView(this).apply {
            setTextColor(0xFFE8EEF6.toInt())
            textSize = 15f
            setLineSpacing(0f, 1.25f)
            setPadding(px(4), px(14), px(4), px(14))
        }
        controls.addView(info)

        // Open the neck on its own screen.
        controls.addView(ui.chip("Παίξε  ▶  Play the bouzouki") {
            startActivity(Intent(this, PlayActivity::class.java))
        }.apply {
            textSize = 18f
            minHeight = px(64)
            ui.style(this, true)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        refresh()
    }

    private fun refresh() {
        for ((inst, b) in instrumentButtons) ui.style(b, inst == settings.instrument)
        val names = settings.names
        if (rootNames != names) { // re-setting the adapter would fire the listener again
            rootNames = names
            rootSpinner.adapter = darkAdapter((0 until 12).map { names.name(it) })
            rootSpinner.setSelection(settings.rootPc)
        }
        namesButton.text = if (names == NoteNames.GREEK) "Ντο Ρε Μι" else "C D E"
        ui.style(namesButton, names == NoteNames.GREEK)
        ui.style(allButton, settings.showAllNotes)
        val d = settings.dromos
        val spelled = d.spell(settings.rootPc, names)
        val degrees = d.degreeLabels()
        val arp = listOf(0, 2, 4, 6) // 1st, 3rd, 5th and 7th degrees
        info.text = "${d.greekName} · ${d.title}\n\n" +
            "Scale:  ${spelled.joinToString("  ")}\n(${degrees.joinToString("  ")})\n\n" +
            "Arpeggio:  ${arp.joinToString("  ") { spelled[it] }}\n(${arp.joinToString("  ") { degrees[it] }})"
    }

    private fun darkAdapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(WHITE); textSize = 16f; setPadding(ui.px(12), 0, ui.px(12), 0)
            }
    }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun selected(onSelect: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}
