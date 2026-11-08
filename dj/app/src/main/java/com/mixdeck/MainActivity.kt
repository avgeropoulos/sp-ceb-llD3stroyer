package com.mixdeck

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.mixdeck.engine.Analyzer
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.XfadeCurve
import java.util.concurrent.Executors

/** The app's audio engine lives here so it survives the activity being recreated. */
object Engine {
    var mixer: Mixer? = null
    var audio: AudioOut? = null
    val deckTrackIds = arrayOfNulls<String>(2)

    fun get(ctx: Context): Mixer = mixer ?: Mixer(AudioOut.deviceSampleRate(ctx)).also { m ->
        mixer = m
        audio = AudioOut(m).apply { start() }
    }
}

class MainActivity : Activity(), DjView.Host {
    private lateinit var mixer: Mixer
    private lateinit var store: TrackStore
    private lateinit var recorder: Recorder
    private lateinit var updater: Updater
    private val loading = arrayOfNulls<String>(2)
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("mixdeck", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mixer = Engine.get(this)
        store = TrackStore(this)
        recorder = Recorder(this, mixer)
        updater = Updater(this)
        restoreSettings()
        setContentView(DjView(this, mixer, this))
        hideSystemBars()
        handleLoadIntent(intent)
        updater.check(manual = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLoadIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            recorder.stop()
            Engine.audio?.stop(); Engine.audio = null; Engine.mixer = null
        }
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ---------------------------------------------------------------- loading tracks

    override fun openLibrary(deck: Int) {
        val d = mixer.decks[deck]
        if (d.playing) {
            AlertDialog.Builder(this)
                .setMessage("Deck ${deckName(deck)} is playing. Load a new song onto it anyway?")
                .setPositiveButton("Load") { _, _ -> startLibrary(deck) }
                .setNegativeButton("Cancel", null).show()
        } else startLibrary(deck)
    }

    private fun startLibrary(deck: Int) {
        startActivityForResult(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_DECK, deck), REQ_LIBRARY)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LIBRARY && resultCode == RESULT_OK && data != null) handleLoadIntent(data)
    }

    private fun handleLoadIntent(i: Intent?) {
        val id = i?.getStringExtra(LibraryActivity.EXTRA_TRACK) ?: return
        val deck = i.getIntExtra(LibraryActivity.EXTRA_DECK, 0)
        i.removeExtra(LibraryActivity.EXTRA_TRACK)
        loadTrack(deck, id)
    }

    private fun loadTrack(deck: Int, id: String) {
        val t = store.get(id) ?: return toast("That song isn't on the phone any more")
        loading[deck] = "Loading ${t.title}…"
        loader.execute {
            try {
                val pcm = Decoder.decode(store.audioFile(id)) { p -> loading[deck] = "Decoding ${(p * 100).toInt()}%" }
                var track = t
                var analysis = store.loadAnalysis(t)
                if (analysis == null) {
                    analysis = Analyzer.analyze(pcm) { p -> loading[deck] = "Finding the beat ${(p * 100).toInt()}%" }
                    track = store.saveAnalysis(t, analysis, Analyzer.loudnessDb(pcm), pcm.frames / pcm.sampleRate.toDouble())
                }
                synchronized(mixer.lock) { mixer.decks[deck].load(pcm, analysis, track.title, track.artist, track.loudnessDb) }
                Engine.deckTrackIds[deck] = id
            } catch (e: Throwable) {
                main.post { toast("Couldn't load ${t.title}: ${e.message}") }
            } finally {
                loading[deck] = null
            }
        }
    }

    // ---------------------------------------------------------------- menus

    override fun deckMenu(deck: Int) {
        val d = mixer.decks[deck]
        if (!d.loaded) { openLibrary(deck); return }
        val fx = d.fx
        val items = arrayOf(
            "Double the BPM (${"%.1f".format(d.bpm * 2)})",
            "Halve the BPM (${"%.1f".format(d.bpm / 2)})",
            "Put the first beat here",
            "Echo/flanger timing: ${beatsLabel(fx.beats)} beat",
            "Reset pitch to 0%",
            "Eject",
        )
        AlertDialog.Builder(this).setTitle("Deck ${deckName(deck)}: ${d.title}")
            .setItems(items) { _, which ->
                synchronized(mixer.lock) {
                    val a = d.analysis
                    when (which) {
                        0 -> if (a != null && a.bpm > 0) d.setAnalysis(a.withBpm(a.bpm * 2))
                        1 -> if (a != null && a.bpm > 0) d.setAnalysis(a.withBpm(a.bpm / 2))
                        2 -> if (a != null) d.setAnalysis(a.withBpm(if (a.bpm > 0) a.bpm else 120.0, d.positionSec))
                        3 -> fx.beats = when (fx.beats) { 0.5 -> 0.75; 0.75 -> 1.0; 1.0 -> 0.25; else -> 0.5 }
                        4 -> d.pitch = 0.0
                        5 -> { d.eject(); Engine.deckTrackIds[deck] = null }
                    }
                }
                val id = Engine.deckTrackIds[deck]
                val a = d.analysis
                if (which <= 2 && id != null && a != null) store.updateGrid(id, a.bpm, a.firstBeatSec)
            }.show()
    }

    private fun beatsLabel(b: Double) = when (b) { 0.25 -> "¼"; 0.5 -> "½"; 0.75 -> "¾"; else -> "1" }

    override fun mainMenu() {
        val items = arrayOf(
            "Master volume…",
            "Sampler volume…",
            "Crossfader curve: ${if (mixer.curve == XfadeCurve.SMOOTH) "smooth (mixing)" else "sharp (scratching)"}",
            "Quantize (snap cues & loops to the beat): ${onOff(mixer.quantize)}",
            "Headphone pre-listen (split cue): ${onOff(mixer.splitCue)}",
            "Check for updates",
            "About MixDeck",
        )
        AlertDialog.Builder(this).setTitle("MixDeck ${BuildConfig.VERSION_NAME}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> slider("Master volume", mixer.master) { v -> mixer.master = v }
                    1 -> slider("Sampler volume", mixer.sampler.volume) { v -> mixer.sampler.volume = v }
                    2 -> synchronized(mixer.lock) { mixer.curve = if (mixer.curve == XfadeCurve.SMOOTH) XfadeCurve.SCRATCH else XfadeCurve.SMOOTH }
                    3 -> synchronized(mixer.lock) { mixer.quantize = !mixer.quantize }
                    4 -> {
                        synchronized(mixer.lock) { mixer.splitCue = !mixer.splitCue }
                        if (mixer.splitCue) AlertDialog.Builder(this).setTitle("Split cue is on")
                            .setMessage("Plug in headphones. Your LEFT ear hears the mix and your RIGHT ear hears the decks " +
                                "with 🎧 lit, so you can line up the next song before the crowd hears it.\n\n" +
                                "Don't use this through a speaker: the speaker would play the cue too.")
                            .setPositiveButton("OK", null).show()
                    }
                    5 -> updater.check(manual = true)
                    6 -> about()
                }
                saveSettings()
            }.show()
    }

    private fun about() {
        AlertDialog.Builder(this).setTitle("MixDeck ${BuildConfig.VERSION_NAME}")
            .setMessage("""
                Load: tap LOAD and search YouTube, paste a Spotify link, or pick a file on your phone.
                Jog wheel: touch the middle to scratch (VINYL on), turn the edge to nudge the tempo.
                Drag the waveform to scratch or move through the track. Tap the overview to jump.
                CUE: set the cue point when stopped, hold to preview, tap while playing to go back.
                Hot cues 1-4: tap to set, tap again to jump, hold to delete.
                Loops 1/2/4/8 beats, ½ and ×2 to resize, IN/OUT for your own loop.
                SYNC matches the tempo and beat of the other deck.
                Knobs: drag up/down, double-tap to reset. Faders: double-tap to reset.
                Tap a deck's title for BPM fixes and more.
                REC records your mix to Music/MixDeck.

                Updates install from inside the app (☰ → Check for updates).
                YouTube downloads are for personal use only.
            """.trimIndent())
            .setPositiveButton("OK", null).show()
    }

    private fun slider(title: String, value: Float, set: (Float) -> Unit) {
        val bar = SeekBar(this).apply { max = 100; progress = (value * 100).toInt() }
        val label = TextView(this).apply { text = "${(value * 100).toInt()}%"; textSize = 18f; setPadding(0, 0, 0, 20) }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                synchronized(mixer.lock) { set(p / 100f) }
                label.text = "$p%"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { saveSettings() }
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20); addView(label); addView(bar) }
        AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Done", null).show()
    }

    private fun saveSettings() {
        prefs.edit().putFloat("master", mixer.master).putFloat("sampler", mixer.sampler.volume)
            .putBoolean("scratchCurve", mixer.curve == XfadeCurve.SCRATCH).putBoolean("quantize", mixer.quantize)
            .putBoolean("splitCue", mixer.splitCue).apply()
    }

    private fun restoreSettings() = synchronized(mixer.lock) {
        mixer.master = prefs.getFloat("master", 0.85f)
        mixer.sampler.volume = prefs.getFloat("sampler", 0.7f)
        mixer.curve = if (prefs.getBoolean("scratchCurve", false)) XfadeCurve.SCRATCH else XfadeCurve.SMOOTH
        mixer.quantize = prefs.getBoolean("quantize", true)
        mixer.splitCue = prefs.getBoolean("splitCue", false)
    }

    // ---------------------------------------------------------------- recording

    override fun toggleRecord() {
        if (recorder.recording) {
            loader.execute {
                val where = runCatching { recorder.stop() }
                main.post {
                    where.onSuccess { toast("Mix saved to $it") }.onFailure { toast("Couldn't save the mix: ${it.message}") }
                }
            }
        } else {
            runCatching { recorder.start() }.onFailure { toast("Couldn't start recording: ${it.message}") }
        }
    }

    override fun recording() = recorder.recording
    override fun recordSeconds() = recorder.seconds
    override fun loadingText(deck: Int) = loading[deck]

    private fun onOff(b: Boolean) = if (b) "on" else "off"
    private fun deckName(i: Int) = if (i == 0) "A" else "B"
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val REQ_LIBRARY = 1
    }
}
