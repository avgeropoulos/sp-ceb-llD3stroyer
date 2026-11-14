package com.mixdeck

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.Recordings
import com.mixdeck.engine.WavWriter
import com.mixdeck.engine.XfadeCurve
import com.mixdeck.ui.Console
import com.mixdeck.ui.MenuItem
import com.mixdeck.ui.Menus
import com.mixdeck.ui.PickCheck
import com.mixdeck.ui.Tutorial
import com.mixdeck.ui.Tutorials
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicIntegerArray

/** The app's audio engine (and REC) lives here so it survives the activity being recreated. */
object Engine {
    var mixer: Mixer? = null
    var audio: AudioOut? = null
    var recorder: Recorder? = null
    /** REC's text while a mix is being saved ("SAVING", "42%"), else null. */
    @Volatile var saving: String? = null
    /** Recordings an earlier run left unsaved were looked for (once per run, before anything records). */
    var leftoversChecked = false
    /**
     * When a mix couldn't be saved this run (elapsedRealtime), else 0: unsaved recordings are looked
     * for again when MixDeck is back in front, a minute or more later.
     */
    @Volatile var saveFailedAt = 0L
    /** A recording or save problem that happened while MixDeck wasn't in front: shown when it is again. */
    @Volatile var missed: String? = null
    val deckTrackIds = arrayOfNulls<String>(2)

    fun get(ctx: Context): Mixer = mixer ?: Mixer(AudioOut.deviceSampleRate(ctx)).also { m ->
        mixer = m
        audio = AudioOut(m).apply { start() }
        recorder = Recorder(ctx.applicationContext, m)
    }
}

class MainActivity : Activity(), Console.Host {
    private lateinit var mixer: Mixer
    private lateinit var store: TrackStore
    lateinit var suggester: Suggester
    private lateinit var recorder: Recorder
    private lateinit var updater: Updater
    private lateinit var dj: DjView
    /** A mix being saved (in [Engine], so a recreated console still shows SAVING). */
    private var saving: String?
        get() = Engine.saving
        set(v) { Engine.saving = v }
    private val loading = arrayOfNulls<String>(2)
    /** The tutorial's songs on their way: "Downloading Air Force Ones… 37%", "Up next: Grillz". */
    private val tutLoading = arrayOfNulls<String>(2)
    private val loader = Executors.newSingleThreadExecutor()
    /** Songs picked in the library download here, two at a time, while you carry on mixing. */
    private val downloads = Executors.newFixedThreadPool(2)
    /** Per deck: bumped by every pick or load, so an older download for that deck gives up. */
    private val gen = AtomicIntegerArray(2)
    /** A song downloading for a deck: its progress shows on the deck's strip, or as "Up next" while the deck plays. */
    private class Fetch(val title: String) { @Volatile var strip: String? = null; @Volatile var short: String? = null }
    private val fetching = arrayOfNulls<Fetch>(2)
    /** Songs waiting to load on a deck when its song ends (so a pick never stops the music). */
    private lateinit var queued: UpNextQueue
    @Volatile private var destroyed = false
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("mixdeck", MODE_PRIVATE) }
    private lateinit var midi: com.mixdeck.midi.MidiRouter
    private lateinit var midiIn: PhoneMidi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mixer = Engine.get(this)
        queued = UpNextQueue(mixer)
        store = TrackStore(filesDir)
        suggester = Suggester(store) { f, p -> Decoder.decode(f, p) }
        recorder = Engine.recorder ?: Recorder(applicationContext, mixer).also { Engine.recorder = it }
        recorder.onFailed = { main.post { recordingFailed() } }
        // A mix still saving counts as recording: installing would close MixDeck in the middle of the save.
        updater = Updater(this, busy = { mixer.decks.any { it.playing } || recorder.recording || saving != null },
            recording = { recorder.recording || saving != null },
            // No automatic update popups over the tutorial or a newcomer's Start card: the ☰ badge only.
            quiet = { ::dj.isInitialized && (dj.tutorial != null || (dj.console.newcomer && dj.console.showStart())) },
            // "Downloaded · install it from ☰" goes on the console's notice line, over the music.
            notify = { if (::dj.isInitialized) dj.console.say(it, ms = 6000) else toast(it) },
            readyWhen = { UpdatePolicy.readyWhen(mixer.decks.any { it.playing }, recorder.recording, saving != null) })
        updater.resumeDownloaded()
        restoreSettings()
        dj = DjView(this, mixer, this)
        setContentView(dj)
        hideSystemBars()
        // Never loaded a song: the Start card welcomes a newcomer (songs saved by an older version count).
        dj.console.newcomer = !prefs.getBoolean("everLoaded", false)
        if (dj.console.newcomer) loader.execute { if (store.all().isNotEmpty()) main.post { markLoaded() } }
        if (!Engine.leftoversChecked) { Engine.leftoversChecked = true; recoverMixes() }
        handleLoadIntent(intent)
        updater.welcomeBack()
        main.post(keepAlive)
        // A plugged-in DJ controller drives the console (☰ → MIDI controller… links its controls).
        midi = com.mixdeck.midi.MidiRouter(mixer, dj.console,
            openLibrary = { d -> main.post { if (!destroyed && loadingText(d) == null) openLibrary(d) } },
            next = { main.post { if (!destroyed) suggestNext(dj.console.nextPlan()) } },
            record = { main.post { if (!destroyed) toggleRecord() } },
        ).also { it.map = com.mixdeck.midi.MidiMap.decode(prefs.getString("midiMap", "")) }
        midiIn = PhoneMidi(this, midi) { name -> dj.console.say(com.mixdeck.midi.MidiRouter.plugged(name, !midi.map.isEmpty), ms = 6000) }
        midiIn.start()
    }

    /** Music, REC, a save or a download is going on: MixDeck keeps running while you're in another app. */
    private fun busy() = mixer.decks.any { it.playing } || recorder.recording || saving != null || queued.waiting ||
        fetching.any { it != null } || loading.any { it != null } || tutLoading.any { it != null }

    /** Every 2 seconds: keeps [KeepPlayingService] on while [busy]. */
    private val keepAlive: Runnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            KeepPlayingService.keep(this@MainActivity, busy())
            main.postDelayed(this, 2000)
        }
    }

    /** In front (between onResume and onPause): notices can be seen. */
    private var resumed = false

    override fun onPause() {
        super.onPause()
        resumed = false
        // Still in front: the last moment it may start, before you switch to another app.
        KeepPlayingService.keep(this, busy())
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Engine.missed?.let { dj.console.say(it, error = true, ms = 15000); Engine.missed = null }
        // A save failed earlier (storage full?): with nothing recording or saving, every recording left is unsaved.
        val failed = Engine.saveFailedAt
        if (failed != 0L && SystemClock.elapsedRealtime() - failed > 60_000 && !recorder.recording && saving == null) {
            Engine.saveFailedAt = 0L
            recoverMixes()
        }
        // Checks for updates at launch, then again when 6 hours have passed while MixDeck stays open.
        updater.onResume()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updater.onPermissionResult(requestCode)
    }

    /** The ☰ badge: an update was found, is downloading, or is downloaded and ready to install. */
    override fun updateReady() = updater.hasNews

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
        destroyed = true
        if (::midiIn.isInitialized) midiIn.stop()
        main.removeCallbacks(pollQueue)
        main.removeCallbacks(keepAlive)
        downloads.shutdownNow()
        if (isFinishing) {
            // If this save fails or is cut off, the recording stays and is saved next time MixDeck opens.
            recorder.stop()?.let { wav -> runCatching { MixStore(this).save(wav, "Unsaved mix ${stamp()}") {} } }
            Engine.recorder = null
            Engine.audio?.stop(); Engine.audio = null
            // Under the mixer lock, so a song still loading can't put its id back afterwards.
            synchronized(mixer.lock) { Engine.mixer = null; Engine.deckTrackIds.fill(null) }
            KeepPlayingService.keep(this, false)
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

    /** LOAD: straight to the library. Picking asks first if the deck is playing (PickCheck). */
    override fun openLibrary(deck: Int) {
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

    /** A pick from the library (or the share menu): the song, its deck, SYNC, and when to load it. */
    private fun handleLoadIntent(i: Intent?) {
        if (i == null) return
        val pick = SongPick.fromExtras { i.getStringExtra(it) } ?: return
        SongPick.KEYS.forEach(i::removeExtra)
        val deck = i.getIntExtra(LibraryActivity.EXTRA_DECK, 0).coerceIn(0, 1)
        val whenToLoad = runCatching { Placement.When.valueOf(i.getStringExtra(LibraryActivity.EXTRA_WHEN) ?: "") }.getOrDefault(Placement.When.NOW)
        fetchAndLoad(pick, Placement(deck, i.getBooleanExtra(LibraryActivity.EXTRA_SYNC, false), whenToLoad))
    }

    private fun live(deck: Int) = PickCheck.live(mixer, deck)

    /**
     * Gets [pick] onto the phone, downloading it on the deck while the music carries on (progress
     * on the deck's strip, or after "Up next" while that deck plays), then [place]s it.
     */
    private fun fetchAndLoad(pick: SongPick, placement: Placement) {
        val deck = placement.deck
        val g = gen.incrementAndGet(deck)
        fetching[deck] = null
        queued.clear(deck) // a newer pick replaces the queued one
        if (pick is SongPick.Saved) return place(pick.id, placement)
        val f = Fetch(pick.title)
        fun show(s: SongFetcher.Step) {
            if (gen.get(deck) != g || destroyed) throw CancellationException("Stopped")
            if (live(deck)) { f.strip = null; f.short = s.short } else { f.short = null; f.strip = s.text }
        }
        show(SongFetcher.Step(pick.title, if (pick is SongPick.Spotify) null else 0f))
        fetching[deck] = f
        downloads.execute {
            try {
                val id = SongFetcher.fetchSteps(store, pick, ::show)
                main.post {
                    if (gen.get(deck) != g || destroyed || Engine.mixer !== mixer) return@post
                    fetching[deck] = null
                    place(id, placement.afterDownload(otherLive = live(1 - deck)), fromDownload = true)
                }
            } catch (e: Throwable) {
                main.post {
                    if (gen.get(deck) != g || destroyed) return@post
                    fetching[deck] = null
                    if (e !is CancellationException) dj.console.say(Problems.downloadFailed(pick.title, e), deck, error = true)
                }
            }
        }
    }

    /** Stops the download for [deck], if any (deckMenu "Stop downloading"). Returns the deck's new generation. */
    private fun cancelFetch(deck: Int): Int {
        fetching[deck] = null
        return gen.incrementAndGet(deck)
    }

    /**
     * Puts a saved song where [p] says. It never stops a playing deck unless the user chose
     * "Stop A and load": otherwise it waits as "Up next" and loads when that deck's song ends.
     */
    private fun place(id: String, p: Placement, fromDownload: Boolean = false) {
        val deck = p.deck
        if (!queued.mustWait(p)) return loadTrack(deck, id, p.sync, whenToLoad = p.whenToLoad, fromDownload = fromDownload)
        val t = store.get(id) ?: return dj.console.say("That song isn't on the phone any more", deck, error = true)
        queue(deck, UpNextQueue.Entry(id, t.title, p.sync))
    }

    /** [e] waits as "Up next" and loads when [deck]'s song ends. */
    private fun queue(deck: Int, e: UpNextQueue.Entry) {
        dj.console.say(queued.add(deck, e), deck)
        main.removeCallbacks(pollQueue)
        main.postDelayed(pollQueue, 250)
    }

    /** Loads each queued song once its deck has stopped. Runs every 250 ms while anything is queued. */
    private val pollQueue: Runnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            for ((k, q) in queued.due()) loadTrack(k, q.id, q.sync, readyNotice = UpNextQueue.readyNotice(k, q), whenToLoad = Placement.When.ENDS)
            if (queued.waiting) main.postDelayed(this, 250)
        }
    }

    override fun upNext(deck: Int): String? = queued[deck]?.title ?: fetching[deck]?.short

    /**
     * Loads song [id] onto [deck] now; with [sync], matches its tempo to the other deck once
     * loaded. It replaces any queued or downloading song for that deck. [readyNotice] replaces the
     * usual notice once it's loaded. [whenToLoad] is checked again once the song is decoded: if the
     * deck went live meanwhile (▶ again after a quick stop, MIX starting it), it waits as "Up next"
     * instead, unless the user chose to stop the deck (REPLACE). [fromDownload]: REPLACE only if the
     * other deck still plays then ([Placement.afterDownload]), so the music never stops.
     */
    private fun loadTrack(deck: Int, id: String, sync: Boolean = false, readyNotice: String? = null,
                          whenToLoad: Placement.When = Placement.When.NOW, fromDownload: Boolean = false) {
        val g = cancelFetch(deck)
        queued.clear(deck)
        val t = store.get(id) ?: return dj.console.say("That song isn't on the phone any more", deck, error = true)
        loading[deck] = "Loading ${t.title}…"
        loader.execute {
            try {
                val pcm = Decoder.decode(store.audioFile(id)) { p -> loading[deck] = "Decoding ${(p * 100).toInt()}%" }
                // Beat grid, waveform, key and energy (only what this song is still missing).
                val (track, analysis) = suggester.analyze(t, pcm) { p -> loading[deck] = "Listening ${(p * 100).toInt()}%" }
                val notice = synchronized(mixer.lock) {
                    // The app was closed while this loaded, or a newer pick for this deck came since (it wins).
                    if (Engine.mixer !== mixer || gen.get(deck) != g) return@execute
                    val p = Placement(deck, sync, whenToLoad).let { if (fromDownload) it.afterDownload(live(1 - deck)) else it }
                    if (queued.mustWait(p)) {
                        main.post { if (gen.get(deck) == g && !destroyed) queue(deck, UpNextQueue.Entry(id, t.title, sync)) }
                        return@execute
                    }
                    val d = mixer.decks[deck]
                    val replacedBpm = if (d.loaded && d.bpm > 0) d.bpm * (1 + d.pitch) else 0.0
                    d.load(pcm, analysis, track.title, track.artist, track.loudnessDb)
                    val synced = if (sync) syncLoaded(deck, replacedBpm) else null
                    Engine.deckTrackIds[deck] = id
                    Console.loadedNotice(mixer, deck, synced)
                }
                main.post { dj.console.say(readyNotice ?: notice, deck); markLoaded() }
            } catch (e: Throwable) {
                main.post {
                    dj.console.say(Problems.loadFailed(t.title, e), deck, error = true)
                    // The tutorial hides notices: its first step offers Try again instead.
                    dj.tutorial?.takeIf { it.index == 0 }?.let { it.failed = true; it.status = Menus.tutorialSongsFailed(Problems.explain(e)) }
                }
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

    /** A song loaded: from now on the Start card greets a returning user. */
    private fun markLoaded() {
        dj.console.newcomer = false
        if (!prefs.getBoolean("everLoaded", false)) prefs.edit().putBoolean("everLoaded", true).apply()
    }

    // ---------------------------------------------------------------- menus

    override fun deckMenu(deck: Int) {
        val d = mixer.decks[deck]
        // During the tutorial an empty deck's strip does nothing (its song is on the way).
        if (dj.tutorial != null && !d.loaded) return
        val items = ArrayList<Pair<String, () -> Unit>>()
        // The song waiting for this deck, or downloading for it, comes first.
        queued[deck]?.let { q -> items += "Cancel up next: ${q.title}" to { queued.clear(deck) } }
        fetching[deck]?.let { f ->
            items += "Stop downloading \"${f.title}\"" to { cancelFetch(deck) }
            items += "Pick another song" to { openLibrary(deck) }
        }
        if (!d.loaded) {
            if (items.isEmpty()) openLibrary(deck)
            else AlertDialog.Builder(this).setTitle("Deck ${deckName(deck)}")
                .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }.show()
            return
        }
        val fx = d.fx
        fun grid(change: (com.mixdeck.engine.TrackAnalysis) -> Unit) {
            synchronized(mixer.lock) { d.analysis?.let(change) }
            val id = Engine.deckTrackIds[deck]; val a = d.analysis
            if (id != null && a != null) store.updateGrid(id, a.bpm, a.firstBeatSec)
        }
        items += "Double the BPM (${"%.1f".format(d.bpm * 2)})" to { grid { a -> if (a.bpm > 0) d.setAnalysis(a.withBpm(a.bpm * 2)) } }
        items += "Halve the BPM (${"%.1f".format(d.bpm / 2)})" to { grid { a -> if (a.bpm > 0) d.setAnalysis(a.withBpm(a.bpm / 2)) } }
        items += "Put the first beat here" to { grid { a -> d.setAnalysis(a.withBpm(if (a.bpm > 0) a.bpm else 120.0, d.positionSec)) } }
        items += "Echo/flanger timing: ${beatsLabel(fx.beats)} beat" to {
            synchronized(mixer.lock) { fx.beats = when (fx.beats) { 0.5 -> 0.75; 0.75 -> 1.0; 1.0 -> 0.25; else -> 0.5 } }
        }
        items += "Reset pitch to 0%" to { synchronized(mixer.lock) { d.pitch = 0.0 } }
        items += "Eject" to { synchronized(mixer.lock) { d.eject(); Engine.deckTrackIds[deck] = null } }
        AlertDialog.Builder(this).setTitle("Deck ${deckName(deck)}: ${d.title}")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }.show()
    }

    private fun beatsLabel(b: Double) = when (b) { 0.25 -> "¼"; 0.5 -> "½"; 0.75 -> "¾"; else -> "1" }

    /** ☰: the same short menu as the computer's (Menus.mainMenu); it fits in landscape. */
    override fun mainMenu() {
        val items = Menus.mainMenu(updater.menuLabel(), BuildConfig.VERSION_NAME, computer = false)
        AlertDialog.Builder(this).setTitle(Menus.title(BuildConfig.VERSION_NAME))
            .setItems(items.map { it.second }.toTypedArray()) { _, which ->
                when (items[which].first) {
                    MenuItem.UPDATE -> updater.menuPick()
                    MenuItem.LEARN -> startTutorial()
                    MenuItem.HELP -> openGuide()
                    MenuItem.MIXES -> startActivity(Intent(this, MixesActivity::class.java))
                    MenuItem.SETTINGS -> SettingsPanel.show(this, mixer, saved = ::saveSettings, checkUpdates = { updater.check(manual = true) })
                    MenuItem.MIDI -> MidiSetup.show(this, midi, midiIn) { prefs.edit().putString("midiMap", midi.map.encode()).apply() }
                    MenuItem.CHECK -> updater.check(manual = true)
                }
            }.show()
    }

    /** Help & guide (☰, or the labels banner). */
    override fun openGuide() = startActivityForResult(Intent(this, HelpActivity::class.java), REQ_HELP)

    /** Don't ask about split cue again until MixDeck is opened again. */
    private var splitCueAsked = false

    /** 🎧 went on while split cue is off: pre-listening needs it, so offer to turn it on. */
    override fun splitCueOff() {
        if (splitCueAsked || isFinishing) return
        splitCueAsked = true
        AlertDialog.Builder(this).setTitle(Menus.SplitCue.TITLE).setMessage(Menus.SplitCue.MESSAGE)
            .setPositiveButton(Menus.SplitCue.TURN_ON) { _, _ -> synchronized(mixer.lock) { mixer.splitCue = true }; saveSettings() }
            .setNegativeButton(Menus.SplitCue.NOT_NOW, null).show()
    }

    // ---------------------------------------------------------------- tutorial

    override fun startTutorial() {
        val ask = Menus.tutorialStart(mixer.decks.any { it.playing }, recorder.recording,
            picksWaiting = queued.waiting || fetching.any { it != null }) ?: return beginTutorial()
        AlertDialog.Builder(this).setTitle("Start the tutorial?").setMessage(ask)
            // A running recording is saved first, so the tutorial isn't recorded into the mix.
            .setPositiveButton("Start") { _, _ -> if (recorder.recording) stopRecord(); beginTutorial() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun beginTutorial() {
        // No song picked earlier may land in the middle of the tutorial.
        for (k in 0..1) { cancelFetch(k); queued.clear(k) }
        // A clean console: stopped decks, flat EQs, crossfader centred, effects off, vinyl on.
        synchronized(mixer.lock) {
            for (d in mixer.decks) {
                d.pause(); d.eqLow = 0f; d.eqMid = 0f; d.eqHigh = 0f; d.filter = 0f; d.trimDb = 0.0
                d.volume = 1f; d.pitch = 0.0; d.fx.on = false; d.fx.type = com.mixdeck.engine.FxType.ECHO
                d.fx.amount = 0.5f; d.vinylMode = true; d.headphone = false
                if (d.loopOn) d.loopToggle()
                for (h in 0..3) d.clearHotCue(h)
                // Empty decks: step 1 waits for Nelly's songs, not whatever was on the decks.
                d.eject()
            }
            Engine.deckTrackIds.fill(null)
            mixer.crossfader = 0f
        }
        val t = Tutorials.nelly()
        dj.tutorial = t
        fetchTutorialSongs(t, onlyMissing = false)
    }

    /** Step 1 couldn't get the songs and "Try again" was tapped: fetch the ones still missing. */
    override fun retryTutorialSongs() {
        val t = dj.tutorial ?: return
        t.failed = false
        t.status = null
        fetchTutorialSongs(t, onlyMissing = true)
    }

    /**
     * Gets the tutorial's songs (with [onlyMissing], for "Try again", only those whose deck is still
     * empty), downloading them the first time, and loads them. The progress shows on the decks
     * ("Downloading Air Force Ones… 37%", "Up next: Grillz") and on the card ("Song 1 of 2 · …").
     * Stops when the tutorial is closed.
     */
    private fun fetchTutorialSongs(t: Tutorial, onlyMissing: Boolean) {
        val todo = listOf(0 to Tutorials.NELLY_A, 1 to Tutorials.NELLY_B)
            .filter { (deck, _) -> !onlyMissing || (!mixer.decks[deck].loaded && loading[deck] == null) }
        if (todo.isEmpty()) return
        todo.forEachIndexed { n, (deck, song) -> tutLoading[deck] = if (n == 0) "Getting ${song.title}…" else "Up next: ${song.title}" }
        Thread({
            var current = -1
            try {
                for ((deck, song) in todo) {
                    current = deck
                    fun show(msg: String) {
                        if (dj.tutorial !== t || destroyed) throw CancellationException("The tutorial was closed")
                        tutLoading[deck] = msg
                        t.status = "Song ${deck + 1} of 2 · $msg"
                    }
                    show("Getting ${song.title}…")
                    val id = SongFetcher.findOrDownload(store, song, ::show)
                    show("Loading onto deck ${deckName(deck)}…")
                    main.post {
                        if (dj.tutorial === t && !destroyed) loadTrack(deck, id, whenToLoad = Placement.When.REPLACE)
                        tutLoading[deck] = null
                    }
                }
                t.status = null
            } catch (e: Throwable) {
                for ((deck, _) in todo) if (deck >= current) tutLoading[deck] = null
                if (e is CancellationException || dj.tutorial !== t) return@Thread
                t.status = Menus.tutorialSongsFailed(Problems.explain(e))
                t.failed = true
            }
        }, "tutorial songs").start()
    }

    private fun saveSettings() {
        prefs.edit().putFloat("master", mixer.master).putFloat("sampler", mixer.sampler.volume)
            .putBoolean("scratchCurve", mixer.curve == XfadeCurve.SCRATCH).putBoolean("quantize", mixer.quantize)
            .putBoolean("splitCue", mixer.splitCue).putInt("mixBars", mixer.autoMix.bars).apply()
    }

    override fun settingsChanged() = saveSettings()

    private fun restoreSettings() = synchronized(mixer.lock) {
        mixer.master = prefs.getFloat("master", 0.85f)
        mixer.sampler.volume = prefs.getFloat("sampler", 0.7f)
        mixer.curve = if (prefs.getBoolean("scratchCurve", false)) XfadeCurve.SCRATCH else XfadeCurve.SMOOTH
        mixer.quantize = prefs.getBoolean("quantize", true)
        mixer.splitCue = prefs.getBoolean("splitCue", false)
        mixer.autoMix.bars = prefs.getInt("mixBars", 8)
    }

    // ---------------------------------------------------------------- recording

    /** REC: starts recording, or stops and saves it. */
    override fun toggleRecord() {
        saving?.let { dj.console.say(Menus.recWaitsForSave(it)); return }
        if (recorder.recording) return stopRecord()
        runCatching { recorder.start() }.onFailure { dj.console.say("Couldn't start recording: ${it.message}", error = true) }
    }

    /**
     * REC held while recording: stops and saves the mix straight away as "Mix 14 Nov 2026 21.30"
     * (rename it in My mixes). No dialogs, so the music carries on. Too short to be a mix: thrown away.
     */
    override fun stopRecord() {
        if (saving != null) return
        val wav = recorder.stop() ?: return
        val secs = (wav.length() - 44) / 4.0 / mixer.sampleRate
        if (secs < Menus.MIN_RECORDING_SECONDS) { wav.delete(); dj.console.say(Menus.tooShort(secs)); return }
        saveMixes(listOf(wav to "Mix ${stamp()}"))
    }

    /** The recording couldn't be written any more (storage full): it stopped, so save what was recorded. */
    private fun recordingFailed() {
        if (destroyed || !recorder.recording) return
        tellProblem(Menus.recordingStopped(recorder.seconds))
        stopRecord()
    }

    /**
     * Recordings an earlier run never saved (MixDeck was swiped away, killed or crashed while
     * recording, or the save failed): saved now as "Recovered mix <when it started>". Older
     * versions recorded into the cache. Runs at the start of a run, before anything records, and
     * after a failed save when MixDeck is back in front with nothing recording or saving.
     */
    private fun recoverMixes() {
        val files = Recordings.leftovers(listOf(Recorder.dir(this), cacheDir), idleMs = 0)
        if (files.isNotEmpty()) saveMixes(files.map { it to "Recovered mix ${stamp(Recordings.startedAt(it) ?: it.lastModified())}" }, recovered = true)
    }

    /**
     * Saves recorded WAVs as mixes, one after the other, in the background (REC shows SAVING), and
     * says how each went. A WAV that can't be saved stays, and is tried again when MixDeck is back in front.
     */
    private fun saveMixes(mixes: List<Pair<File, String>>, recovered: Boolean = false) {
        saving = "SAVING"
        Thread({
            for ((wav, name) in mixes) {
                // A recording cut off before it was closed gets a proper header first.
                val secs = runCatching { WavWriter.repair(wav) }.getOrDefault(0.0)
                if (secs < Menus.MIN_RECORDING_SECONDS) { wav.delete(); continue }
                val r = runCatching { MixStore(this).save(wav, name) { p -> saving = "${(p * 100).toInt()}%" } }
                if (r.isFailure) Engine.saveFailedAt = SystemClock.elapsedRealtime()
                main.post {
                    r.onSuccess { m -> if (!destroyed) dj.console.say(if (recovered) Menus.recoveredMix(m.name, secs) else Menus.savedMix(m.name, secs), ms = 6000) }
                        .onFailure { tellProblem(Menus.saveFailed(it.message)) }
                }
            }
            saving = null
        }, "save mix").start()
    }

    /** A recording or save problem: on the notice line, and again when MixDeck is back in front if it isn't now. */
    private fun tellProblem(text: String) {
        if (!resumed || destroyed) Engine.missed = text
        if (!destroyed) dj.console.say(text, error = true)
    }

    private fun stamp(ms: Long = System.currentTimeMillis()) =
        java.text.SimpleDateFormat("d MMM yyyy HH.mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    override fun savingText() = saving
    override fun recording() = recorder.recording
    override fun recordSeconds() = recorder.seconds
    override fun loadingText(deck: Int) = loading[deck] ?: fetching[deck]?.strip ?: tutLoading[deck]

    private fun deckName(i: Int) = if (i == 0) "A" else "B"
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val REQ_LIBRARY = 1
        const val REQ_HELP = 3
    }
}
