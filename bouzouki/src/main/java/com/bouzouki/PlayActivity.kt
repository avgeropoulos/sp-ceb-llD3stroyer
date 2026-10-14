package com.bouzouki

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bouzouki.core.ScalePath

/**
 * The bouzouki on its own screen: the whole neck to play by hand, and a scale player that
 * runs the dromos (or its arpeggio) down the neck on one course or across the neck in one position.
 */
class PlayActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private val settings by lazy { Settings(this) }
    private val audio = AudioEngine()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var board: FretboardView
    private lateinit var scroller: ScrollView
    private lateinit var downButton: Button
    private lateinit var acrossButton: Button
    private lateinit var scaleButton: Button
    private lateinit var arpeggioButton: Button
    private lateinit var playButton: Button
    private lateinit var tremoloButton: Button
    private lateinit var selector: LinearLayout

    private val instrument by lazy { settings.instrument }
    private val rootPc by lazy { settings.rootPc }
    private var handFret = 0
    private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = GREEK_BLUE
        window.navigationBarColor = NAVY
        val px = ui::px
        val dromos = settings.dromos
        val names = settings.names
        handFret = ScalePath.defaultHandFret(instrument, rootPc)
        if (settings.course !in instrument.courses.indices) settings.course = 0

        board = FretboardView(this, audio.synth).apply {
            instrument = this@PlayActivity.instrument
            this.dromos = dromos
            rootPc = this@PlayActivity.rootPc
            this.names = names
            showAllNotes = settings.showAllNotes
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAVY)
        }

        // Title bar with a way back to the setup screen.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(GREEK_BLUE)
            setPadding(px(4), px(6), px(12), px(6))
            addView(Button(context).apply {
                text = "←"
                textSize = 22f
                setTextColor(WHITE)
                background = null
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(px(52), px(48)))
            addView(TextView(context).apply {
                text = "${names.name(rootPc)} ${dromos.title}\n${dromos.greekName} · ${instrument.greekName}"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(WHITE)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(wireZoom(ui, settings, board, "play", defaultWhole = false),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)))
        })
        root.addView(MeanderView(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(10)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), px(4), px(8), px(4))
        }
        root.addView(controls)

        downButton = ui.chip("⇣ Down the neck") { stop(); settings.acrossTheNeck = false; refresh() }
        acrossButton = ui.chip("⇄ Across the neck") { stop(); settings.acrossTheNeck = true; refresh() }
        controls.addView(ui.row().apply {
            addView(downButton, ui.weighted())
            addView(acrossButton, ui.weighted())
        })

        scaleButton = ui.chip("Scale") { stop(); settings.arpeggio = false; refresh() }
        arpeggioButton = ui.chip("Arpeggio 1 3 5 7") { stop(); settings.arpeggio = true; refresh() }
        controls.addView(ui.row().apply {
            addView(scaleButton, ui.weighted())
            addView(arpeggioButton, ui.weighted())
        })

        selector = ui.row()
        controls.addView(selector)

        playButton = ui.chip("▶ Play") { if (playing) stop() else play() }
        tremoloButton = ui.chip("Tremolo") { board.tremolo = !board.tremolo; refresh() }
        controls.addView(ui.row().apply {
            addView(playButton, ui.weighted(1.3f))
            addView(tremoloButton, ui.weighted())
            addView(ui.chip("✋ Mute") { stop(); audio.synth.muteAll() }, ui.weighted())
        })

        scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(board)
        }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        board.onPlayed = { top, bottom -> keepVisible(top, bottom) }

        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val across = settings.acrossTheNeck
        ui.style(downButton, !across)
        ui.style(acrossButton, across)
        ui.style(scaleButton, !settings.arpeggio)
        ui.style(arpeggioButton, settings.arpeggio)
        ui.style(tremoloButton, board.tremolo)
        ui.style(playButton, playing)
        playButton.text = when {
            playing -> "■ Stop"
            settings.arpeggio -> "▶ Play arpeggio"
            else -> "▶ Play scale"
        }
        board.arpeggio = settings.arpeggio
        board.focusCourse = if (across) null else settings.course
        board.focusFrets = if (across) ScalePath.window(instrument, handFret) else null

        // Down the neck: pick the course. Across the neck: move the hand position.
        selector.removeAllViews()
        if (across) {
            val frets = ScalePath.window(instrument, handFret)
            selector.addView(ui.chip("◀") { moveHand(-1) }, ui.weighted(0.6f))
            selector.addView(TextView(this).apply {
                text = "Position: fret ${frets.first}–${frets.last}"
                setTextColor(WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
            }, ui.weighted(2f))
            selector.addView(ui.chip("▶") { moveHand(1) }, ui.weighted(0.6f))
        } else {
            selector.addView(TextView(this).apply {
                text = "String"
                setTextColor(0xFF9FB4CC.toInt())
                textSize = 13f
            }, ui.weighted(0.8f))
            for ((c, course) in instrument.courses.withIndex()) {
                val note = settings.names.name(course.openMidi) + (course.openMidi / 12 - 1)
                selector.addView(ui.chip(note) { stop(); settings.course = c; refresh() }.also {
                    ui.style(it, c == settings.course)
                }, ui.weighted())
            }
        }
    }

    private fun moveHand(by: Int) {
        stop()
        handFret = (handFret + by).coerceIn(0, ScalePath.maxHandFret(instrument))
        refresh()
        val frets = ScalePath.window(instrument, handFret)
        board.post { board.fretSpan(frets.first, frets.last).let { (t, b) -> keepVisible(t, b) } }
    }

    private fun play() {
        val intervals = if (settings.arpeggio) ScalePath.arpeggio(settings.dromos) else settings.dromos.semitones
        val up = if (settings.acrossTheNeck) ScalePath.acrossTheNeck(instrument, rootPc, intervals, handFret)
        else ScalePath.downTheNeck(instrument, settings.course, rootPc, intervals)
        if (up.isEmpty()) return
        val notes = ScalePath.upAndBack(up)
        val step = if (settings.arpeggio) 340L else 290L
        playing = true
        refresh()
        notes.forEachIndexed { i, cell -> handler.postDelayed({ if (playing) board.playCell(cell) }, i * step) }
        handler.postDelayed({ playing = false; refresh() }, notes.size * step + 250)
    }

    private fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (playing) { playing = false; refresh() }
    }

    /** Scrolls so the cells from [top] to [bottom] are on screen, following the scale up and down the neck. */
    private fun keepVisible(top: Int, bottom: Int) {
        val visibleTop = scroller.scrollY
        val visibleBottom = visibleTop + scroller.height
        if (top < visibleTop || bottom > visibleBottom) {
            scroller.smoothScrollTo(0, (top - scroller.height / 3).coerceAtLeast(0))
        }
    }

    override fun onResume() {
        super.onResume()
        audio.start()
    }

    override fun onPause() {
        stop()
        audio.stop()
        super.onPause()
    }
}
