package com.bouzouki

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
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
import android.widget.Toast
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
    private lateinit var customButton: Button
    private lateinit var allButton: Button
    private var rootNames: NoteNames? = null
    private val updater by lazy { Updater(this) }
    private lateinit var updateButton: Button
    private var latestBuild: Long? = null
    private var updating = false

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
                text = "Μπουζούκι Lab"
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
            customButton = ui.chip("") { askTuning() }.apply { textSize = 13f; minHeight = px(56) }
            addView(customButton, ui.weighted())
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

        // Play along with a song: an audio file of yours, or one heard on the phone.
        controls.addView(ui.chip("🎵  Songs: listen, transcribe, play along") {
            startActivity(Intent(this, SongActivity::class.java))
        }.apply {
            textSize = 16f
            minHeight = px(56)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(10)
        })

        // Update straight from GitHub, no browser needed.
        updateButton = ui.chip("Checking for updates…") { onUpdateClicked() }
        controls.addView(updateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(12)
        })

        // Calibrate: the player's bouzouki and room, for the songs they record.
        controls.addView(ui.chip("🎯  Calibrate my bouzouki") { startActivity(Intent(this, CalibrationActivity::class.java)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })

        // Measurements on the phone: note engines, separation, listening to other apps.
        controls.addView(ui.chip("🔧  Lab tools") { LabTools.show(this) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })

        // The Terms of Use, privacy and the third-party parts.
        controls.addView(ui.chip("ⓘ  About · Terms of Use · Privacy") { Legal.showAbout(this) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })

        setContentView(root)
        fitSystemBars(this, root)
        SongJobs.sweep(this) // temporary audio a crash may have left behind
        FingeringPrefs.apply(this)
        Legal.requireTerms(this) { checkForUpdate() }
        handleInstallStatus(intent)
        refresh()
    }

    private fun checkForUpdate() {
        updateButton.text = "Checking for updates…"
        ui.style(updateButton, false)
        updater.checkLatest { latest ->
            latestBuild = latest
            if (!updating) showUpdateState()
        }
    }

    private fun showUpdateState() {
        val latest = latestBuild
        when {
            latest == null -> { updateButton.text = "Couldn't check for updates · tap to retry"; ui.style(updateButton, false) }
            latest > updater.installedBuild -> { updateButton.text = "⬆  Update to v1.$latest"; ui.style(updateButton, true) }
            else -> { updateButton.text = "✓ Up to date (v1.${updater.installedBuild}) · tap to check again"; ui.style(updateButton, false) }
        }
    }

    private fun onUpdateClicked() {
        val latest = latestBuild
        if (updating) return
        if (latest == null || latest <= updater.installedBuild) { checkForUpdate(); return }
        if (!updater.ensureCanInstall()) {
            Toast.makeText(this, "Allow Bouzouki Lab to install updates, then come back and tap Update again", Toast.LENGTH_LONG).show()
            return
        }
        updating = true
        updater.install(
            progress = { pct -> updateButton.text = "Downloading v1.$latest…  $pct%" },
            failed = { msg ->
                updating = false
                showUpdateState()
                Toast.makeText(this, "Update failed: $msg", Toast.LENGTH_LONG).show()
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallStatus(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh() // the song screen can switch the bouzouki
    }

    /** Android reports on the update here: first it needs you to confirm, then it says how it went. */
    private fun handleInstallStatus(intent: Intent?) {
        if (intent?.action != Updater.ACTION_INSTALL_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> updating = false
            else -> {
                updating = false
                showUpdateState()
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"
                Toast.makeText(this, "Update not installed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refresh() {
        for ((inst, b) in instrumentButtons) ui.style(b, inst == settings.instrument)
        val custom = settings.customTuning
        customButton.text = if (custom != null) "Κούρδισμα\n${custom.tuning}" else "Κούρδισμα\nCustom tuning…"
        ui.style(customButton, !settings.instrument.standard)
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

    /**
     * The player's own tuning: courses low to high as note names, an octave pair as both its notes ("C3/C4 F3/F4 A3 D4"),
     * and the frets (up to ${Instrument.MAX_FRETS}). Tapped again while it's chosen, it can be changed.
     */
    private fun askTuning() {
        val current = settings.customTuning
        if (current != null && settings.instrument != current) { settings.instrument = current; settings.course = 0; refresh(); return }
        val tuning = android.widget.EditText(this).apply { setText(current?.tuning ?: "C3/C4 F3/F4 A3 D4"); textSize = 16f }
        val frets = android.widget.EditText(this).apply {
            setText((current?.frets ?: 26).toString()); textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(20), 0, ui.px(20), 0)
            addView(TextView(context).apply { text = "Courses, low to high. An octave pair as both its notes: C3/C4."; textSize = 13f })
            addView(tuning)
            addView(TextView(context).apply { text = "Frets"; textSize = 13f })
            addView(frets)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Κούρδισμα · Custom tuning")
            .setView(box)
            .setPositiveButton("Use") { _, _ ->
                val inst = Instrument.parseTuning(tuning.text.toString(), frets.text.toString().toIntOrNull()?.coerceIn(1, Instrument.MAX_FRETS) ?: 26)
                if (inst == null) { Toast.makeText(this, "Couldn't read that tuning. Write the courses like C3/C4 F3/F4 A3 D4.", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                if (!inst.standard) settings.customTuning = inst
                settings.instrument = inst
                settings.course = 0
                refresh()
            }
            .apply { if (current != null) setNeutralButton("Remove") { _, _ -> settings.customTuning = null; if (!settings.instrument.standard) settings.instrument = Instrument.TETRACHORDO; refresh() } }
            .setNegativeButton("Cancel", null)
            .show()
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
