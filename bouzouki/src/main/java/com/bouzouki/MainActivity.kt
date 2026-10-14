package com.bouzouki

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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

class MainActivity : Activity() {
    private val audio = AudioEngine()
    private lateinit var board: FretboardView
    private lateinit var scroller: ScrollView
    private lateinit var info: TextView
    private lateinit var rootSpinner: Spinner
    private lateinit var playButton: Button
    private val instrumentButtons = HashMap<Instrument, Button>()
    private lateinit var namesButton: Button
    private lateinit var allButton: Button
    private lateinit var tremoloButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private var rootNames: NoteNames? = null
    private val prefs by lazy { getSharedPreferences("bouzouki", Context.MODE_PRIVATE) }

    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = GREEK_BLUE
        window.navigationBarColor = NAVY

        board = FretboardView(this, audio.synth).apply {
            instrument = Instrument.entries.getOrElse(prefs.getInt("instrument", 1)) { Instrument.TETRACHORDO }
            dromos = Dromos.entries.getOrElse(prefs.getInt("dromos", Dromos.HITZAZ.ordinal)) { Dromos.HITZAZ }
            rootPc = prefs.getInt("root", 2)
            names = NoteNames.entries.getOrElse(prefs.getInt("names", 0)) { NoteNames.WESTERN }
            showAllNotes = prefs.getBoolean("all", false)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAVY)
        }

        // Title.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(GREEK_BLUE)
            setPadding(px(16), px(10), px(16), px(8))
            addView(TextView(context).apply {
                text = "Μπουζούκι"
                textSize = 24f
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
            setPadding(px(12), px(8), px(12), px(4))
        }
        root.addView(controls)

        // Trichordo / Tetrachordo.
        controls.addView(row().apply {
            for (inst in Instrument.entries) {
                val b = chip("${inst.greekName}\n${inst.englishName.substringBefore(" ·")} · ${inst.englishName.substringAfterLast("· ")}") {
                    board.instrument = inst
                    prefs.edit().putInt("instrument", inst.ordinal).apply()
                    stopScale()
                    refresh()
                }
                b.textSize = 13f
                instrumentButtons[inst] = b
                addView(b, weighted())
            }
        })

        // Dromos.
        val dromosSpinner = Spinner(this).apply {
            adapter = darkAdapter(Dromos.entries.map { it.title })
            setSelection(board.dromos.ordinal)
            background = rounded(0xFF15304F.toInt())
            onItemSelectedListener = selected { pos ->
                board.dromos = Dromos.entries[pos]
                prefs.edit().putInt("dromos", pos).apply()
                refresh()
            }
        }
        controls.addView(label("Δρόμος · Scale"))
        controls.addView(dromosSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)))

        // Root, play and mute.
        rootSpinner = Spinner(this).apply {
            background = rounded(0xFF15304F.toInt())
            onItemSelectedListener = selected { pos ->
                board.rootPc = pos
                prefs.edit().putInt("root", pos).apply()
                refresh()
            }
        }
        playButton = chip("▶ Scale") { if (playing) stopScale() else playScale() }
        controls.addView(label("Τονική · Root note"))
        controls.addView(row().apply {
            addView(rootSpinner, weighted(1.2f).apply { height = px(48) })
            addView(playButton, weighted())
            addView(chip("✋ Mute") { stopScale(); audio.synth.muteAll() }, weighted())
        })

        // Display and playing options.
        namesButton = chip("") {
            board.names = if (board.names == NoteNames.WESTERN) NoteNames.GREEK else NoteNames.WESTERN
            prefs.edit().putInt("names", board.names.ordinal).apply()
            refresh()
        }
        allButton = chip("All notes") {
            board.showAllNotes = !board.showAllNotes
            prefs.edit().putBoolean("all", board.showAllNotes).apply()
            refresh()
        }
        tremoloButton = chip("Tremolo") { board.tremolo = !board.tremolo; refresh() }
        controls.addView(row().apply {
            addView(namesButton, weighted())
            addView(allButton, weighted())
            addView(tremoloButton, weighted())
        })

        info = TextView(this).apply {
            setTextColor(0xFFE8EEF6.toInt())
            textSize = 14f
            setPadding(px(4), px(6), px(4), px(2))
        }
        controls.addView(info)

        scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = true
            addView(board)
        }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        board.onPlayed = { top, bottom ->
            // Keep the scale player's notes on screen as it climbs the neck.
            val visibleTop = scroller.scrollY
            val visibleBottom = visibleTop + scroller.height
            if (top < visibleTop || bottom > visibleBottom) scroller.smoothScrollTo(0, (top - scroller.height / 3).coerceAtLeast(0))
        }

        setContentView(root)
        refresh()
    }

    private fun refresh() {
        for ((inst, b) in instrumentButtons) style(b, inst == board.instrument)
        val names = board.names
        if (rootNames != names) { // re-setting the adapter would fire the listener again
            rootNames = names
            rootSpinner.adapter = darkAdapter((0 until 12).map { names.name(it) })
            rootSpinner.setSelection(board.rootPc)
        }
        namesButton.text = if (names == NoteNames.GREEK) "Ντο Ρε Μι" else "C D E"
        style(namesButton, names == NoteNames.GREEK)
        style(allButton, board.showAllNotes)
        style(tremoloButton, board.tremolo)
        style(playButton, playing)
        playButton.text = if (playing) "■ Stop" else "▶ Scale"
        val d = board.dromos
        info.text = "${d.greekName}  ·  ${d.spell(board.rootPc, names).joinToString("  ")}\n" +
            d.degreeLabels().joinToString("  ")
    }

    /** Plays the dromos up an octave and back down, in one position on the neck. */
    private fun playScale() {
        val inst = board.instrument
        val low = inst.courses[0].openMidi
        val start = low + Math.floorMod(board.rootPc - low, 12)
        val up = board.dromos.semitones.map { start + it } + (start + 12)
        val notes = up + up.reversed().drop(1)
        val handFret = inst.position(start)?.second ?: 0
        playing = true
        refresh()
        notes.forEachIndexed { i, midi ->
            handler.postDelayed({ if (playing) board.playMidi(midi, handFret) }, i * 330L)
        }
        handler.postDelayed({ playing = false; refresh() }, notes.size * 330L + 200)
    }

    private fun stopScale() {
        handler.removeCallbacksAndMessages(null)
        if (playing) { playing = false; refresh() }
    }

    override fun onResume() {
        super.onResume()
        audio.start()
    }

    override fun onPause() {
        stopScale()
        audio.stop()
        super.onPause()
    }

    // --- small view helpers ---

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(3), 0, px(3))
    }

    private fun weighted(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply {
        marginStart = px(3); marginEnd = px(3)
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        textSize = 12f
        setTextColor(0xFF9FB4CC.toInt())
        setPadding(px(4), px(6), 0, px(2))
    }

    private fun chip(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 14f
        minHeight = px(46)
        stateListAnimator = null
        setOnClickListener { onClick() }
        style(this, false)
    }

    private fun style(b: Button, on: Boolean) {
        b.background = rounded(if (on) GREEK_BLUE else 0xFF15304F.toInt(), if (on) WHITE else 0xFF2C4A6E.toInt())
        b.setTextColor(WHITE)
    }

    private fun rounded(fill: Int, stroke: Int = 0xFF2C4A6E.toInt()) = GradientDrawable().apply {
        cornerRadius = 10 * dp
        setColor(fill)
        setStroke(px(1), stroke)
    }

    private fun darkAdapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(WHITE); textSize = 16f; setPadding(px(12), 0, px(12), 0)
            }
    }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun selected(onSelect: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    /** A strip of Greek key (meander) pattern under the title. */
    private class MeanderView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE; style = Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density * 1.6f
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(GREEK_BLUE)
            val h = height.toFloat()
            val u = h / 5 // one unit of the key
            val path = Path()
            var x = 0f
            path.moveTo(0f, h - u)
            while (x < width) {
                path.lineTo(x + 4 * u, h - u)
                path.lineTo(x + 4 * u, u)
                path.lineTo(x + u, u)
                path.lineTo(x + u, 3 * u)
                path.lineTo(x + 3 * u, 3 * u)
                path.lineTo(x + 3 * u, 2 * u)
                path.moveTo(x + 4 * u, h - u)
                path.lineTo(x + 5 * u, h - u)
                x += 5 * u
            }
            canvas.drawPath(path, paint)
        }
    }

    private companion object {
        const val GREEK_BLUE = 0xFF0D5EAF.toInt()
        const val NAVY = 0xFF0B1A2C.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
