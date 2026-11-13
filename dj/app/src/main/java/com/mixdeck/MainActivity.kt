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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.mixdeck.engine.Analyzer
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.XfadeCurve
import com.mixdeck.ui.Console
import com.mixdeck.ui.Tutorials
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

class MainActivity : Activity(), Console.Host {
    private lateinit var mixer: Mixer
    private lateinit var store: TrackStore
    lateinit var suggester: Suggester
    private lateinit var recorder: Recorder
    private lateinit var updater: Updater
    private lateinit var dj: DjView
    @Volatile private var saving: String? = null
    private val loading = arrayOfNulls<String>(2)
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("mixdeck", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mixer = Engine.get(this)
        store = TrackStore(filesDir)
        suggester = Suggester(store) { f, p -> Decoder.decode(f, p) }
        recorder = Recorder(this, mixer)
        updater = Updater(this)
        restoreSettings()
        dj = DjView(this, mixer, this)
        setContentView(dj)
        hideSystemBars()
        // First launch: show the key so every control is explained.
        if (!prefs.getBoolean("seenKey", false)) {
            dj.helpMode = true
            prefs.edit().putBoolean("seenKey", true).apply()
        }
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
            recorder.stop()?.let { wav -> runCatching { MixStore(this).save(wav, "Unsaved mix ${stamp()}") {} } }
            Engine.audio?.stop(); Engine.audio = null; Engine.mixer = null
            Engine.deckTrackIds.fill(null)
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

    override fun suggestNext(next: Console.NextPlan) {
        val after = next.after
        if (after == null) {
            AlertDialog.Builder(this).setTitle("What to play next")
                .setMessage("Load a song on a deck first. Then NEXT suggests songs that fit after it: a key that blends, " +
                    "a tempo close enough to sync, and the energy you want.")
                .setPositiveButton("Pick a song") { _, _ -> openLibrary(0) }
                .setNegativeButton("OK", null).show()
            return
        }
        // No "deck is playing" question here: browsing is harmless; LibraryActivity asks when you pick.
        startActivityForResult(Intent(this, LibraryActivity::class.java)
            .putExtra(LibraryActivity.EXTRA_DECK, next.target).putExtra(LibraryActivity.EXTRA_AFTER, after)
            .putExtra(LibraryActivity.EXTRA_WAITING, next.waiting).putExtra(LibraryActivity.EXTRA_SUGGEST, true), REQ_LIBRARY)
    }

    override fun deckStarted(deck: Int) {
        val id = Engine.deckTrackIds[deck] ?: return
        loader.execute { store.markPlayed(id) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LIBRARY && resultCode == RESULT_OK && data != null) handleLoadIntent(data)
        if (requestCode == REQ_HELP && resultCode == HelpActivity.RESULT_SHOW_KEY) dj.helpMode = true
        if (requestCode == REQ_HELP && resultCode == HelpActivity.RESULT_TUTORIAL) startTutorial()
    }

    private fun handleLoadIntent(i: Intent?) {
        val id = i?.getStringExtra(LibraryActivity.EXTRA_TRACK) ?: return
        val deck = i.getIntExtra(LibraryActivity.EXTRA_DECK, 0)
        i.removeExtra(LibraryActivity.EXTRA_TRACK)
        loadTrack(deck, id, sync = i.getBooleanExtra(LibraryActivity.EXTRA_SYNC, false))
    }

    /** Loads song [id] onto [deck]; with [sync], matches its tempo to the other deck once loaded. */
    private fun loadTrack(deck: Int, id: String, sync: Boolean = false) {
        val t = store.get(id) ?: return toast("That song isn't on the phone any more")
        loading[deck] = "Loading ${t.title}…"
        loader.execute {
            try {
                val pcm = Decoder.decode(store.audioFile(id)) { p -> loading[deck] = "Decoding ${(p * 100).toInt()}%" }
                // Beat grid, waveform, key and energy (only what this song is still missing).
                val (track, analysis) = suggester.analyze(t, pcm) { p -> loading[deck] = "Listening ${(p * 100).toInt()}%" }
                var synced: Double? = null
                synchronized(mixer.lock) {
                    val d = mixer.decks[deck]
                    val replacedBpm = if (d.loaded && d.bpm > 0) d.bpm * (1 + d.pitch) else 0.0
                    d.load(pcm, analysis, track.title, track.artist, track.loudnessDb)
                    if (sync) synced = syncLoaded(deck, replacedBpm)
                }
                synced?.let { p ->
                    val pct = "%+.1f".format(p * 100)
                    main.post { toast("\"${track.title}\" is on deck ${deckName(deck)}, synced ($pct%). Press ▶ when the moment's right.") }
                }
                Engine.deckTrackIds[deck] = id
            } catch (e: Throwable) {
                main.post { toast("Couldn't load ${t.title}: ${e.message}") }
            } finally {
                loading[deck] = null
            }
        }
    }

    /**
     * "SYNC it for me" after a suggestion is loaded (call inside the mixer lock): match the other
     * deck's song, or the song this one replaced when the other deck is empty (the suggestions
     * followed that one). With no beat to match, play at normal speed, as the advice assumed.
     * Returns the new tempo change, or null if it couldn't sync.
     */
    private fun syncLoaded(deck: Int, replacedBpm: Double): Double? {
        val d = mixer.decks[deck]; val other = mixer.decks[1 - deck]
        val target = if (other.loaded) (if (other.bpm > 0) other.bpm * (1 + other.pitch) else 0.0) else replacedBpm
        if (d.bpm <= 0 || target <= 0) { d.pitch = 0.0; return null }
        if (other.loaded) mixer.sync(deck) else d.matchTempo(target)
        return d.pitch
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
            "▶ Tutorial: mix Nelly – Air Force Ones → Grillz",
            "My mixes",
            "How it works (guide)",
            "Show button labels",
            "Master volume…",
            "Sampler volume…",
            "Crossfader curve: ${if (mixer.curve == XfadeCurve.SMOOTH) "smooth (mixing)" else "sharp (scratching)"}",
            "Quantize (snap cues & loops to the beat): ${onOff(mixer.quantize)}",
            "Headphone pre-listen (split cue): ${onOff(mixer.splitCue)}",
            "Check for updates",
        )
        AlertDialog.Builder(this).setTitle("MixDeck ${BuildConfig.VERSION_NAME}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startTutorial()
                    1 -> startActivity(Intent(this, MixesActivity::class.java))
                    2 -> startActivityForResult(Intent(this, HelpActivity::class.java), REQ_HELP)
                    3 -> dj.helpMode = true
                    4 -> slider("Master volume", mixer.master) { v -> mixer.master = v }
                    5 -> slider("Sampler volume", mixer.sampler.volume) { v -> mixer.sampler.volume = v }
                    6 -> synchronized(mixer.lock) { mixer.curve = if (mixer.curve == XfadeCurve.SMOOTH) XfadeCurve.SCRATCH else XfadeCurve.SMOOTH }
                    7 -> synchronized(mixer.lock) { mixer.quantize = !mixer.quantize }
                    8 -> {
                        synchronized(mixer.lock) { mixer.splitCue = !mixer.splitCue }
                        if (mixer.splitCue) AlertDialog.Builder(this).setTitle("Split cue is on")
                            .setMessage("Plug in headphones. Your LEFT ear hears the mix and your RIGHT ear hears the decks " +
                                "with 🎧 lit, so you can line up the next song before the crowd hears it.\n\n" +
                                "Don't use this through a speaker: the speaker would play the cue too.")
                            .setPositiveButton("OK", null).show()
                    }
                    9 -> updater.check(manual = true)
                }
                saveSettings()
            }.show()
    }

    // ---------------------------------------------------------------- tutorial

    private fun startTutorial() {
        if (mixer.decks.any { it.playing }) {
            AlertDialog.Builder(this).setTitle("Start the tutorial?")
                .setMessage("This stops both decks and loads Nelly's \"Air Force Ones\" and \"Grillz\".")
                .setPositiveButton("Start") { _, _ -> beginTutorial() }
                .setNegativeButton("Cancel", null).show()
        } else beginTutorial()
    }

    private fun beginTutorial() {
        // A clean console: stopped decks, flat EQs, crossfader centred, effects off, vinyl on.
        synchronized(mixer.lock) {
            for (d in mixer.decks) {
                d.pause(); d.eqLow = 0f; d.eqMid = 0f; d.eqHigh = 0f; d.filter = 0f; d.trimDb = 0.0
                d.volume = 1f; d.pitch = 0.0; d.fx.on = false; d.fx.type = com.mixdeck.engine.FxType.ECHO
                d.fx.amount = 0.5f; d.vinylMode = true; d.headphone = false
                if (d.loopOn) d.loopToggle()
                for (h in 0..3) d.clearHotCue(h)
            }
            mixer.crossfader = 0f
        }
        val t = Tutorials.nelly()
        dj.tutorial = t
        Thread({
            val songs = listOf(0 to Tutorials.NELLY_A, 1 to Tutorials.NELLY_B)
            try {
                for ((deck, song) in songs) {
                    val id = SongFetcher.findOrDownload(store, song) { msg -> t.status = msg }
                    t.status = "Loading onto deck ${deckName(deck)}…"
                    main.post { loadTrack(deck, id) }
                }
                t.status = null
            } catch (e: Throwable) {
                t.status = "Couldn't get the songs (${e.message}). Check your internet and try again, " +
                    "or load them yourself with LOAD (search \"Nelly Air Force Ones\" and \"Nelly Grillz\")."
            }
        }, "tutorial songs").start()
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
        if (saving != null) return
        if (!recorder.recording) {
            runCatching { recorder.start() }.onFailure { toast("Couldn't start recording: ${it.message}") }
            return
        }
        val wav = recorder.stop() ?: return
        val secs = (wav.length() - 44) / 4.0 / mixer.sampleRate
        val input = EditText(this).apply {
            setText("Mix ${stamp()}"); setSelectAllOnFocus(true); setSingleLine()
        }
        val box = LinearLayout(this).apply {
            setPadding(60, 20, 60, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Save your mix (${Console.fmtTime(secs)})")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ -> saveMix(wav, input.text.toString()) }
            .setNegativeButton("Discard") { _, _ ->
                AlertDialog.Builder(this).setMessage("Throw this recording away?")
                    .setPositiveButton("Discard") { _, _ -> wav.delete() }
                    .setNegativeButton("Keep it") { _, _ -> saveMix(wav, input.text.toString()) }
                    .setCancelable(false).show()
            }.show()
    }

    private fun saveMix(wav: java.io.File, name: String) {
        saving = "SAVING"
        Thread({
            val r = runCatching { MixStore(this).save(wav, name) { p -> saving = "${(p * 100).toInt()}%" } }
            saving = null
            main.post {
                r.onSuccess { m ->
                    AlertDialog.Builder(this).setTitle("Mix saved")
                        .setMessage("\"${m.name}\" is in My mixes and in Music/MixDeck on your phone.")
                        .setPositiveButton("Open My mixes") { _, _ -> startActivity(Intent(this, MixesActivity::class.java)) }
                        .setNegativeButton("OK", null).show()
                }.onFailure { toast("Couldn't save the mix: ${it.message}") }
            }
        }, "save mix").start()
    }

    private fun stamp() = java.text.SimpleDateFormat("d MMM yyyy HH.mm", java.util.Locale.getDefault()).format(java.util.Date())

    override fun savingText() = saving
    override fun recording() = recorder.recording
    override fun recordSeconds() = recorder.seconds
    override fun loadingText(deck: Int) = loading[deck]

    private fun onOff(b: Boolean) = if (b) "on" else "off"
    private fun deckName(i: Int) = if (i == 0) "A" else "B"
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val REQ_LIBRARY = 1
        const val REQ_HELP = 3
    }
}
