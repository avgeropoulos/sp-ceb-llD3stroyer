package com.mixdeck.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.mixdeck.Placement
import com.mixdeck.Problems
import com.mixdeck.SongFetcher
import com.mixdeck.SongPick
import com.mixdeck.TrackStore
import com.mixdeck.UpNextQueue
import com.mixdeck.YouTube
import com.mixdeck.engine.Analyzer
import com.mixdeck.engine.FxType
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.Recordings
import com.mixdeck.engine.WavWriter
import com.mixdeck.engine.XfadeCurve
import com.mixdeck.ui.Console
import com.mixdeck.ui.MenuItem
import com.mixdeck.ui.Menus
import com.mixdeck.ui.PickCheck
import com.mixdeck.ui.TextMetrics
import com.mixdeck.ui.Tutorial
import com.mixdeck.ui.Tutorials
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.prefs.Preferences
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--make-icons") {
        AppIcon.writeAll(File(args.getOrElse(1) { "icons" }))
        exitProcess(0)
    }
    // In-app update self-test (CI runs it on every OS): see UpdateSelfTest.
    when (args.firstOrNull()) {
        "--selftest-update", "--selftest-update-apply", "--selftest-updated" -> {
            System.setProperty("java.awt.headless", "true")
            val code = runCatching {
                when (args[0]) {
                    "--selftest-update" -> UpdateSelfTest.phase1(File(args[1]), File(args[2]))
                    "--selftest-update-apply" -> UpdateSelfTest.phase2(File(args[1]), File(args[2]))
                    else -> UpdateSelfTest.phase3(File(args[1]))
                }
            }.onFailure { it.printStackTrace() }.getOrDefault(1)
            exitProcess(code)
        }
    }
    if (args.firstOrNull() == "--selftest") {
        System.setProperty("java.awt.headless", "true")
        val ok = runCatching { SelfTest.run(File(args.getOrNull(1) ?: "selftest")) }.onFailure { it.printStackTrace() }.getOrDefault(false)
        exitProcess(if (ok) 0 else 1)
    }
    System.setProperty("apple.awt.application.name", "MixDeck")
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater { App().show() }
}

/** Where MixDeck keeps its files on this computer. */
object Places {
    private val home = File(System.getProperty("user.home"))
    private val os = System.getProperty("os.name").lowercase()

    /** Downloaded songs and their analysis. */
    val data: File = when {
        os.contains("win") -> File(System.getenv("LOCALAPPDATA") ?: home.path, "MixDeck")
        os.contains("mac") -> File(home, "Library/Application Support/MixDeck")
        else -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "mixdeck")
    }.apply { mkdirs() }

    /** Saved mixes, where music players and file browsers can find them. */
    val mixes: File = File(home, "Music/MixDeck").apply { mkdirs() }

    val temp: File = File(data, "tmp").apply { mkdirs() }
}

object Version {
    private val props = Properties().apply {
        Version::class.java.getResourceAsStream("/mixdeck-version.properties")?.use { load(it) }
    }
    val code = props.getProperty("versionCode", "1").toInt()
    val name: String = props.getProperty("versionName", "1.1")
    val platform: String = props.getProperty("platform", "")
}

class App : Console.Host {
    val mixer = Mixer(DesktopAudioOut.pickSampleRate())
    private val audio = DesktopAudioOut(mixer)
    val store = TrackStore(Places.data)
    val suggester = com.mixdeck.Suggester(store) { f, p -> Ffmpeg.decode(f, p) }
    private val recorder = DesktopRecorder(mixer, Places.temp)
    private val prefs = Preferences.userRoot().node("mixdeck")
    private val loading = arrayOfNulls<String>(2)
    /** The tutorial's songs on their way: "Downloading Air Force Ones… 37%", "Up next: Grillz". */
    private val tutLoading = arrayOfNulls<String>(2)
    private val deckTrackIds = arrayOfNulls<String>(2)
    /** The id of the song on [deck], or null. */
    fun trackOn(deck: Int): String? = deckTrackIds[deck]
    @Volatile private var saving: String? = null
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "MixDeck loader").apply { isDaemon = true } }
    /** Songs picked in the library download here, two at a time, while you carry on mixing. */
    private val downloads = Executors.newFixedThreadPool(2) { r -> Thread(r, "MixDeck download").apply { isDaemon = true } }
    /** Per deck: bumped by every pick or load, so an older download for that deck gives up. */
    private val gen = AtomicIntegerArray(2)
    /** A song downloading for a deck: its progress shows on the deck's strip, or as "Up next" while the deck plays. */
    private class Fetch(val title: String) { @Volatile var strip: String? = null; @Volatile var short: String? = null }
    private val fetching = arrayOfNulls<Fetch>(2)
    /** Songs waiting to load on a deck when its song ends (so a pick never stops the music). */
    private val queued = UpNextQueue(mixer)
    /** Loads each queued song once its deck has stopped (every 250 ms while anything is queued). */
    private val queueTimer = Timer(250) { pollQueue() }

    /** No automatic update popups over the tutorial or a newcomer's Start card: the ☰ badge only. */
    val updater = DesktopUpdater(this, quiet = { console.tutorial != null || (console.newcomer && console.showStart()) })
    val frame = JFrame("MixDeck")
    private val panel = ConsolePanel()
    val console get() = panel.console

    fun show() {
        audio.start()
        restoreSettings()
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = quit()
            // Coming back to MixDeck: check for updates if 6 hours have passed since the last check.
            override fun windowActivated(e: WindowEvent) = updater.checkIfDue()
        })
        // ⌘Q and Quit in the Dock or the app menu on a Mac: the same as closing the window (save a
        // recording, put a waiting update in), instead of Java just ending. macOS only hears back
        // when MixDeck stays open: when it closes, a shut down or log out goes on.
        runCatching {
            val d = java.awt.Desktop.getDesktop()
            if (d.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) {
                d.setQuitHandler { _, r ->
                    SwingUtilities.invokeLater {
                        // quit() doesn't return when MixDeck closes. Still here: Cancel was chosen, or
                        // MixDeck closes once the mix is saved (macOS hears then, see saveMixes).
                        try { quit() } finally { if (quitWhenSaved != null) systemQuit = r else r.cancelQuit() }
                    }
                }
            }
        }
        frame.contentPane.add(panel, BorderLayout.CENTER)
        frame.iconImages = listOf(16, 32, 64, 128, 256).map { AppIcon.render(it) }
        runCatching { java.awt.Taskbar.getTaskbar().iconImage = AppIcon.render(512) }
        frame.minimumSize = Dimension(1100, 560)
        frame.size = Dimension(1600, 820)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        midiIn.start()
        installKeys()
        // Never loaded a song: the Start card welcomes a newcomer (songs saved by an older version count).
        console.newcomer = !prefs.getBoolean("everLoaded", false)
        if (console.newcomer) loader.execute { if (store.all().isNotEmpty()) SwingUtilities.invokeLater { markLoaded() } }
        audio.error?.let { error(it) }
        recoverMixes()
        // A recording from a run that died just before this one still looks live: look again once it can't,
        // when nothing records or saves (so REC is never held up by it).
        Timer(61_000) { e -> if (saving == null && !recorder.recording) { recoverMixes(); (e.source as Timer).stop() } }.start()
        recorder.onFailed = { SwingUtilities.invokeLater { recordingFailed() } }
        runCatching { Install.cleanUp() }
        SwingUtilities.invokeLater { updater.welcomeBack(); updater.checkIfDue() }
        // MixDeck may stay open (and in front) for a whole set: look again once 6 hours have passed.
        Timer(30 * 60 * 1000) { updater.checkIfDue() }.start()
    }

    /** True while music is playing or a mix is being recorded: what "This stops the music" is about. */
    fun audible() = mixer.decks.any { it.playing } || recorder.recording

    /** True while music is playing or a mix is being recorded or saved (no popups then). */
    fun musicPlaying() = audible() || saving != null

    /** Quit was asked for while a mix was saving: MixDeck closes (restarting if true) once it's saved. */
    private var quitWhenSaved: Boolean? = null
    /** macOS's quit request (⌘Q, a shut down or log out) waiting for that save: answered if MixDeck stays open. */
    private var systemQuit: java.awt.desktop.QuitResponse? = null

    fun repaint() = panel.repaint()

    override fun updateReady() = updater.hasNews

    /**
     * Quits MixDeck (asking about an unsaved recording; waiting for a mix that's still saving).
     * [restart]: open again, e.g. to finish an update.
     */
    fun quit(restart: Boolean = false) {
        if (recorder.recording) {
            val r = JOptionPane.showConfirmDialog(frame, "You're recording. Save the mix before quitting?", "MixDeck",
                JOptionPane.YES_NO_CANCEL_OPTION)
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return
            // Saved in the background like REC held (the window keeps drawing, SAVING shows its progress).
            if (r == JOptionPane.YES_OPTION) stopRecord() else recorder.stop()?.delete()
        }
        if (saving != null) {
            quitWhenSaved = restart
            console.say("Saving your mix… MixDeck closes when it's saved")
            return
        }
        saveSettings()
        audio.stop()
        updater.applyOnQuit(relaunch = restart)
        exitProcess(0)
    }

    // ------------------------------------------------------------------ the console view

    inner class ConsolePanel : JPanel() {
        val console = Console(mixer, this@App, computer = true)
        private val canvas = Java2DCanvas()
        var lastMouse = Point(0, 0)

        init {
            TextMetrics.current = Fonts
            isFocusable = true
            isDoubleBuffered = true
            // Redraw at display rate; the console animates (waveforms, meters, jog wheels).
            Timer(15) { repaint() }.start()
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    lastMouse = e.point
                    console.pointerDown(0, e.x.toFloat(), e.y.toFloat(), first = true)
                }
                override fun mouseDragged(e: MouseEvent) { lastMouse = e.point; console.pointerMove(0, e.x.toFloat(), e.y.toFloat()) }
                override fun mouseReleased(e: MouseEvent) = console.pointerUp(0)
                override fun mouseMoved(e: MouseEvent) { lastMouse = e.point }
                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    console.wheel(e.x.toFloat(), e.y.toFloat(), (-e.preciseWheelRotation).toFloat())
                }
            }
            addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            console.layout(width, height)
            canvas.begin(g2)
            console.draw(canvas)
        }
    }

    // ------------------------------------------------------------------ keyboard

    private val held = HashSet<Int>()

    private fun installKeys() {
        // A dialog opened from a key (N) takes the key-up: forget held keys when the window loses focus.
        frame.addWindowFocusListener(object : WindowAdapter() {
            // The key-up goes elsewhere: let go of held keys now (a CUE preview stops).
            override fun windowLostFocus(e: WindowEvent) { held.forEach { keyUp(it) }; held.clear() }
        })
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (!frame.isFocused) return@addKeyEventDispatcher false
            when (e.id) {
                KeyEvent.KEY_PRESSED -> if (held.add(e.keyCode)) keyDown(e) else false
                KeyEvent.KEY_RELEASED -> { held.remove(e.keyCode); keyUp(e.keyCode) }
                else -> false
            }
        }
    }

    private inline fun act(crossinline f: Mixer.() -> Unit) = synchronized(mixer.lock) { mixer.f() }

    /** Keyboard shortcuts (the labels show them, see [shortcut]). Returns true if the key did something. */
    private fun keyDown(e: KeyEvent): Boolean {
        val k = e.keyCode
        val a = 0; val b = 1
        // Ctrl+R (⌘R on a Mac) starts or stops recording: no hold needed with the modifier.
        if (k == KeyEvent.VK_R && (e.isControlDown || e.isMetaDown)) { if (console.allows("rec")) SwingUtilities.invokeLater { toggleRecord() }; return true }
        // Anything else with Ctrl, ⌘ or Alt belongs to the system (e.g. ⌘Q quits on a Mac).
        if (e.isControlDown || e.isMetaDown || e.isAltDown) return false
        // A control's key works when the control does: during the tutorial, only those it lights up.
        fun ok(control: String) = console.allows(control)
        when (k) {
            KeyEvent.VK_A -> if (ok("load0")) SwingUtilities.invokeLater { openLibrary(a) }
            KeyEvent.VK_B -> if (ok("load1")) SwingUtilities.invokeLater { openLibrary(b) }
            KeyEvent.VK_Q -> if (ok("cue0")) act { decks[a].cueDown(quantize) }
            KeyEvent.VK_W -> if (ok("play0")) play(a)
            KeyEvent.VK_E -> if (ok("sync0")) act { sync(a) }
            KeyEvent.VK_R -> if (ok("loop0_4")) act { decks[a].autoLoop(4.0, quantize) }
            KeyEvent.VK_T -> if (ok("fx0")) act { decks[a].fx.on = !decks[a].fx.on }
            KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4 ->
                if (ok("hc0_${k - KeyEvent.VK_1}")) act { decks[a].hotCue(k - KeyEvent.VK_1, quantize) }
            KeyEvent.VK_O -> if (ok("cue1")) act { decks[b].cueDown(quantize) }
            KeyEvent.VK_P -> if (ok("play1")) play(b)
            KeyEvent.VK_I -> if (ok("sync1")) act { sync(b) }
            KeyEvent.VK_U -> if (ok("loop1_4")) act { decks[b].autoLoop(4.0, quantize) }
            KeyEvent.VK_Y -> if (ok("fx1")) act { decks[b].fx.on = !decks[b].fx.on }
            KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9 ->
                if (ok("hc1_${k - KeyEvent.VK_7}")) act { decks[b].hotCue(k - KeyEvent.VK_7, quantize) }
            KeyEvent.VK_0 -> if (ok("hc1_3")) act { decks[b].hotCue(3, quantize) }
            KeyEvent.VK_LEFT -> if (ok("xfader")) act { crossfader = (crossfader - 0.1f).coerceAtLeast(-1f) }
            KeyEvent.VK_RIGHT -> if (ok("xfader")) act { crossfader = (crossfader + 0.1f).coerceAtMost(1f) }
            KeyEvent.VK_DOWN -> if (ok("xfader")) act { crossfader = 0f }
            // Like the pads: press again to stop.
            KeyEvent.VK_Z, KeyEvent.VK_X, KeyEvent.VK_C, KeyEvent.VK_V -> {
                val n = listOf(KeyEvent.VK_Z, KeyEvent.VK_X, KeyEvent.VK_C, KeyEvent.VK_V).indexOf(k)
                if (ok("pad$n")) act { if (sampler.isPlaying(n)) sampler.stop(n) else sampler.trigger(n) }
            }
            KeyEvent.VK_H -> console.helpMode = !console.helpMode
            // After the key event, so a dialog doesn't open inside the key dispatcher.
            KeyEvent.VK_F1 -> SwingUtilities.invokeLater { openGuide() }
            KeyEvent.VK_N -> if (ok("next")) SwingUtilities.invokeLater { suggestNext(console.nextPlan()) }
            KeyEvent.VK_M -> if (ok("mix")) console.mixTapped()
            // Esc closes the labels first; only then the tutorial.
            KeyEvent.VK_ESCAPE -> if (console.helpMode) console.helpMode = false else console.tutorial = null
            else -> return false
        }
        return true
    }

    /** W / P: play or pause; on an empty deck, pick a song for it (like ▶ on the console). */
    private fun play(deck: Int) {
        if (!mixer.decks[deck].loaded && console.tutorial == null && loadingText(deck) == null) SwingUtilities.invokeLater { openLibrary(deck) }
        else act { playPressed(deck) }
    }

    /** The key that does the same as the console control [key]: shown in a key cap under its label. */
    override fun shortcut(key: String) = Shortcuts.of(key)

    private fun keyUp(k: Int): Boolean {
        when (k) {
            KeyEvent.VK_Q -> act { decks[0].cueUp() }
            KeyEvent.VK_O -> act { decks[1].cueUp() }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ loading songs

    /** LOAD: straight to the library. Picking asks first if the deck is playing (PickCheck). */
    override fun openLibrary(deck: Int) {
        // Suggestions follow the other deck's song, else the song being replaced.
        val after = listOf(1 - deck, deck).firstOrNull { mixer.decks[it].loaded }
        LibraryDialog(this, deck, after = after) { pick, placement -> fetchAndLoad(pick, placement) }.isVisible = true
    }

    override fun suggestNext(next: Console.NextPlan) {
        val after = next.after
        if (after == null) {
            val r = JOptionPane.showOptionDialog(frame,
                "Load a song on a deck first. Then NEXT suggests songs that fit after it:\n" +
                    "a key that blends, a tempo close enough to sync, and the energy you want.",
                "What to play next", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                arrayOf("Pick a song for deck A", "OK"), "Pick a song for deck A")
            if (r == 0) openLibrary(0)
            return
        }
        LibraryDialog(this, next.target, after = after, waiting = next.waiting, suggest = true) { pick, placement ->
            fetchAndLoad(pick, placement)
        }.isVisible = true
    }

    override fun deckStarted(deck: Int) {
        val id = deckTrackIds[deck] ?: return
        loader.execute { store.markPlayed(id) }
    }

    private fun live(deck: Int) = PickCheck.live(mixer, deck)

    /**
     * Gets [pick] onto this computer, downloading it on the deck while the music carries on
     * (progress on the deck's strip, or after "Up next" while that deck plays), then [place]s it.
     * Call on the Swing thread.
     */
    fun fetchAndLoad(pick: SongPick, placement: Placement) {
        val deck = placement.deck
        val g = gen.incrementAndGet(deck)
        fetching[deck] = null
        queued.clear(deck) // a newer pick replaces the queued one
        if (pick is SongPick.Saved) return place(pick.id, placement)
        val f = Fetch(pick.title)
        fun show(s: SongFetcher.Step) {
            if (gen.get(deck) != g) throw CancellationException("Stopped")
            if (live(deck)) { f.strip = null; f.short = s.short } else { f.short = null; f.strip = s.text }
        }
        show(SongFetcher.Step(pick.title, if (pick is SongPick.Spotify) null else 0f))
        fetching[deck] = f
        downloads.execute {
            try {
                val id = SongFetcher.fetchSteps(store, pick, ::show)
                SwingUtilities.invokeLater {
                    if (gen.get(deck) != g) return@invokeLater
                    fetching[deck] = null
                    place(id, placement.afterDownload(otherLive = live(1 - deck)), fromDownload = true)
                }
            } catch (e: Throwable) {
                SwingUtilities.invokeLater {
                    if (gen.get(deck) != g) return@invokeLater
                    fetching[deck] = null
                    if (e !is CancellationException) console.say(Problems.downloadFailed(pick.title, e), deck, error = true)
                }
            }
        }
    }

    /** Stops the download for [deck], if any (deck menu "Stop downloading"). Returns the deck's new generation. */
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
        val t = store.get(id) ?: return console.say("That song isn't on this computer any more", deck, error = true)
        queue(deck, UpNextQueue.Entry(id, t.title, p.sync))
    }

    /** [e] waits as "Up next" and loads when [deck]'s song ends. */
    private fun queue(deck: Int, e: UpNextQueue.Entry) {
        console.say(queued.add(deck, e), deck)
        queueTimer.restart()
    }

    private fun pollQueue() {
        for ((k, q) in queued.due()) loadTrack(k, q.id, q.sync, readyNotice = UpNextQueue.readyNotice(k, q), whenToLoad = Placement.When.ENDS)
        if (!queued.waiting) queueTimer.stop()
    }

    override fun upNext(deck: Int): String? = queued[deck]?.title ?: fetching[deck]?.short

    /**
     * Loads song [id] onto [deck] now; with [sync], matches its tempo to the other deck once
     * loaded. It replaces any queued or downloading song for that deck. [readyNotice] replaces the
     * usual notice once it's loaded. [whenToLoad] is checked again once the song is decoded: if the
     * deck went live meanwhile (▶ again after a quick stop, MIX starting it), it waits as "Up next"
     * instead, unless the user chose to stop the deck (REPLACE). [fromDownload]: REPLACE only if the
     * other deck still plays then ([Placement.afterDownload]), so the music never stops. Call on the Swing thread.
     */
    fun loadTrack(deck: Int, id: String, sync: Boolean = false, readyNotice: String? = null,
                  whenToLoad: Placement.When = Placement.When.NOW, fromDownload: Boolean = false) {
        val g = cancelFetch(deck)
        queued.clear(deck)
        val t = store.get(id) ?: return console.say("That song isn't on this computer any more", deck, error = true)
        loading[deck] = "Loading ${t.title}…"
        loader.execute {
            try {
                val pcm = Ffmpeg.decode(store.audioFile(id)) { p -> loading[deck] = "Decoding ${(p * 100).toInt()}%" }
                // Beat grid, waveform, key and energy (only what this song is still missing).
                val (track, analysis) = suggester.analyze(t, pcm) { p -> loading[deck] = "Listening ${(p * 100).toInt()}%" }
                val notice = synchronized(mixer.lock) {
                    // A newer pick for this deck came while this decoded: it wins.
                    if (gen.get(deck) != g) return@execute
                    val p = Placement(deck, sync, whenToLoad).let { if (fromDownload) it.afterDownload(live(1 - deck)) else it }
                    if (queued.mustWait(p)) {
                        SwingUtilities.invokeLater { if (gen.get(deck) == g) queue(deck, UpNextQueue.Entry(id, t.title, sync)) }
                        return@execute
                    }
                    val d = mixer.decks[deck]; val other = mixer.decks[1 - deck]
                    val replacedBpm = if (d.loaded && d.bpm > 0) d.bpm * (1 + d.pitch) else 0.0
                    d.load(pcm, analysis, track.title, track.artist, track.loudnessDb)
                    // Match the other deck's song, or the song this one replaced when the other deck is
                    // empty (the suggestions followed that one). No beat to match: normal speed, as the
                    // advice assumed. synced = the tempo change, or null when it couldn't sync.
                    val synced = if (!sync) null else {
                        val target = if (other.loaded) (if (other.bpm > 0) other.bpm * (1 + other.pitch) else 0.0) else replacedBpm
                        when {
                            d.bpm <= 0 || target <= 0 -> { d.pitch = 0.0; null }
                            other.loaded -> { mixer.sync(deck); d.pitch }
                            else -> { d.matchTempo(target); d.pitch }
                        }
                    }
                    Console.loadedNotice(mixer, deck, synced, computer = true)
                }
                deckTrackIds[deck] = id
                SwingUtilities.invokeLater { console.say(readyNotice ?: notice, deck); markLoaded() }
            } catch (e: Throwable) {
                SwingUtilities.invokeLater {
                    console.say(Problems.loadFailed(t.title, e), deck, error = true)
                    // The tutorial hides notices: its first step offers Try again instead.
                    console.tutorial?.takeIf { it.index == 0 }?.let { it.failed = true; it.status = Menus.tutorialSongsFailed(Problems.explain(e), computer = true) }
                }
            } finally {
                loading[deck] = null
            }
        }
    }

    /** A song loaded: from now on the Start card greets a returning user. */
    private fun markLoaded() {
        console.newcomer = false
        if (prefs.getBoolean("everLoaded", false)) return
        prefs.putBoolean("everLoaded", true)
        runCatching { prefs.flush() }
    }

    // ------------------------------------------------------------------ menus

    override fun deckMenu(deck: Int) {
        val d = mixer.decks[deck]
        // During the tutorial an empty deck's strip does nothing (its song is on the way).
        if (console.tutorial != null && !d.loaded) return
        // The song waiting for this deck, or downloading for it, comes first.
        val first = ArrayList<Pair<String, () -> Unit>>()
        queued[deck]?.let { q -> first += "Cancel up next: ${q.title}" to { queued.clear(deck) } }
        fetching[deck]?.let { f ->
            first += "Stop downloading \"${f.title}\"" to { cancelFetch(deck) }
            first += "Pick another song" to { openLibrary(deck) }
        }
        if (!d.loaded) {
            if (first.isEmpty()) openLibrary(deck) else MenuDialog(frame, "Deck ${deckName(deck)}", first).isVisible = true
            return
        }
        val fx = d.fx
        fun grid(change: () -> Unit) {
            synchronized(mixer.lock) { change() }
            val id = deckTrackIds[deck]; val a = d.analysis
            if (id != null && a != null) store.updateGrid(id, a.bpm, a.firstBeatSec)
        }
        MenuDialog(frame, "Deck ${deckName(deck)}: ${d.title}", first + listOf(
            "Double the BPM (${"%.1f".format(d.bpm * 2)})" to { grid { d.analysis?.let { if (it.bpm > 0) d.setAnalysis(it.withBpm(it.bpm * 2)) } } },
            "Halve the BPM (${"%.1f".format(d.bpm / 2)})" to { grid { d.analysis?.let { if (it.bpm > 0) d.setAnalysis(it.withBpm(it.bpm / 2)) } } },
            "Put the first beat here" to { grid { d.analysis?.let { d.setAnalysis(it.withBpm(if (it.bpm > 0) it.bpm else 120.0, d.positionSec)) } } },
            "Echo/flanger timing: ${beatsLabel(fx.beats)} beat" to { act { fx.beats = when (fx.beats) { 0.5 -> 0.75; 0.75 -> 1.0; 1.0 -> 0.25; else -> 0.5 } } },
            "Reset pitch to 0%" to { act { d.pitch = 0.0 } },
            "Eject" to { act { d.eject() }; deckTrackIds[deck] = null },
        )).isVisible = true
    }

    private fun beatsLabel(b: Double) = when (b) { 0.25 -> "¼"; 0.5 -> "½"; 0.75 -> "¾"; else -> "1" }

    /** ☰: the same short menu as the phone's (Menus.mainMenu). */
    override fun mainMenu() {
        val items = Menus.mainMenu(updater.menuLabel(), Version.name, computer = true).map { (item, label) ->
            label to when (item) {
                MenuItem.UPDATE -> { { updater.menuPick() } }
                MenuItem.LEARN -> { { startTutorial() } }
                MenuItem.HELP -> { { openGuide() } }
                MenuItem.MIXES -> { { MixesDialog(this).isVisible = true } }
                MenuItem.SETTINGS -> { { SettingsDialog(this).isVisible = true } }
                MenuItem.MIDI -> { { MidiDialog(this).isVisible = true } }
                MenuItem.CHECK -> { { updater.check(manual = true) } }
            }
        }
        MenuDialog(frame, Menus.title(Version.name), items).isVisible = true
    }

    /** Help & guide (☰, F1, or the labels banner). */
    override fun openGuide() = SwingUtilities.invokeLater { HelpDialog(this).isVisible = true }

    /** Don't ask about split cue again until MixDeck is opened again. */
    private var splitCueAsked = false

    /** 🎧 went on while split cue is off: pre-listening needs it, so offer to turn it on. */
    override fun splitCueOff() {
        if (splitCueAsked) return
        splitCueAsked = true
        SwingUtilities.invokeLater {
            val c = JOptionPane.showOptionDialog(frame, Menus.SplitCue.MESSAGE, Menus.SplitCue.TITLE, JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, arrayOf(Menus.SplitCue.TURN_ON, Menus.SplitCue.NOT_NOW), Menus.SplitCue.TURN_ON)
            if (c == 0) { act { splitCue = true }; saveSettings() }
        }
    }

    internal fun saveSettings() {
        prefs.putFloat("master", mixer.master); prefs.putFloat("sampler", mixer.sampler.volume)
        prefs.putBoolean("scratchCurve", mixer.curve == XfadeCurve.SCRATCH); prefs.putBoolean("quantize", mixer.quantize)
        prefs.putBoolean("splitCue", mixer.splitCue)
        prefs.putInt("mixBars", mixer.autoMix.bars)
        runCatching { prefs.flush() }
    }

    override fun settingsChanged() = saveSettings()

    // ------------------------------------------------------------------ MIDI controllers

    /** A plugged-in DJ controller drives the console (☰ → MIDI controller… links its controls). */
    val midi by lazy {
        com.mixdeck.midi.MidiRouter(mixer, console,
            openLibrary = { d -> SwingUtilities.invokeLater { if (loadingText(d) == null) openLibrary(d) } },
            next = { SwingUtilities.invokeLater { suggestNext(console.nextPlan()) } },
            record = { SwingUtilities.invokeLater { toggleRecord() } },
        ).also { it.map = com.mixdeck.midi.MidiMap.decode(prefs.get("midiMap", "")) }
    }
    val midiIn by lazy {
        DesktopMidi(midi) { name -> console.say(com.mixdeck.midi.MidiRouter.plugged(name, !midi.map.isEmpty), ms = 6000) }
    }

    internal fun saveMidi() {
        prefs.put("midiMap", midi.map.encode())
        runCatching { prefs.flush() }
    }

    private fun restoreSettings() = synchronized(mixer.lock) {
        mixer.master = prefs.getFloat("master", 0.85f)
        mixer.sampler.volume = prefs.getFloat("sampler", 0.7f)
        mixer.curve = if (prefs.getBoolean("scratchCurve", false)) XfadeCurve.SCRATCH else XfadeCurve.SMOOTH
        mixer.quantize = prefs.getBoolean("quantize", true)
        mixer.splitCue = prefs.getBoolean("splitCue", false)
        mixer.autoMix.bars = prefs.getInt("mixBars", 8)
    }

    // ------------------------------------------------------------------ tutorial

    override fun startTutorial() {
        val ask = Menus.tutorialStart(mixer.decks.any { it.playing }, recorder.recording,
            picksWaiting = queued.waiting || fetching.any { it != null })
        if (ask != null && JOptionPane.showConfirmDialog(frame, ask, "Start the tutorial?",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        // A running recording is saved first, so the tutorial isn't recorded into the mix.
        if (recorder.recording) stopRecord()
        synchronized(mixer.lock) {
            for (d in mixer.decks) {
                d.pause(); d.eqLow = 0f; d.eqMid = 0f; d.eqHigh = 0f; d.filter = 0f; d.trimDb = 0.0
                d.volume = 1f; d.pitch = 0.0; d.fx.on = false; d.fx.type = FxType.ECHO
                d.fx.amount = 0.5f; d.vinylMode = true; d.headphone = false
                if (d.loopOn) d.loopToggle()
                for (h in 0..3) d.clearHotCue(h)
                // Empty decks: step 1 waits for Nelly's songs, not whatever was on the decks.
                d.eject()
            }
            mixer.crossfader = 0f
        }
        deckTrackIds.fill(null)
        // No song picked earlier may land in the middle of the tutorial.
        for (k in 0..1) { cancelFetch(k); queued.clear(k) }
        val t = Tutorials.nelly()
        console.tutorial = t
        fetchTutorialSongs(t, onlyMissing = false)
    }

    /** Step 1 couldn't get the songs and "Try again" was tapped: fetch the ones still missing. */
    override fun retryTutorialSongs() {
        val t = console.tutorial ?: return
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
                        if (console.tutorial !== t) throw CancellationException("The tutorial was closed")
                        tutLoading[deck] = msg
                        t.status = "Song ${deck + 1} of 2 · $msg"
                    }
                    show("Getting ${song.title}…")
                    val id = SongFetcher.findOrDownload(store, song, ::show)
                    show("Loading onto deck ${deckName(deck)}…")
                    SwingUtilities.invokeLater {
                        if (console.tutorial === t) loadTrack(deck, id, whenToLoad = Placement.When.REPLACE)
                        tutLoading[deck] = null
                    }
                }
                t.status = null
            } catch (e: Throwable) {
                for ((deck, _) in todo) if (deck >= current) tutLoading[deck] = null
                if (e is CancellationException || console.tutorial !== t) return@Thread
                t.status = Menus.tutorialSongsFailed(Problems.explain(e), computer = true)
                t.failed = true
            }
        }, "tutorial songs").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ recording

    /** REC (or Ctrl+R / ⌘R): starts recording, or stops and saves it. */
    override fun toggleRecord() {
        saving?.let { console.say(Menus.recWaitsForSave(it)); return }
        if (recorder.recording) return stopRecord()
        runCatching { recorder.start() }.onFailure { console.say("Couldn't start recording: ${it.message}", error = true) }
    }

    /**
     * REC held while recording: stops and saves the mix straight away as "Mix 14 Nov 2026 21.30"
     * (rename it in My mixes). No dialogs, so the music carries on. Too short to be a mix: thrown away.
     */
    override fun stopRecord() {
        if (saving != null) return
        val wav = recorder.stop() ?: return
        val secs = (wav.length() - 44) / 4.0 / mixer.sampleRate
        if (secs < Menus.MIN_RECORDING_SECONDS) { wav.delete(); console.say(Menus.tooShort(secs)); return }
        saveMixes(listOf(wav to "Mix ${stamp()}"))
    }

    /** The recording couldn't be written any more (disk full): it stopped, so save what was recorded. */
    private fun recordingFailed() {
        if (!recorder.recording) return
        console.say(Menus.recordingStopped(recorder.seconds, computer = true), error = true)
        stopRecord()
    }

    /**
     * Recordings an earlier run never saved (MixDeck crashed or was forced to quit while recording,
     * or the save failed): saved now as "Recovered mix <when it started>". One another MixDeck
     * window is still recording is written to all the time, so it's left alone (so is one written to in the
     * last minute, so it runs again a minute after the start).
     */
    private fun recoverMixes() {
        val files = Recordings.leftovers(listOf(Places.temp), idleMs = 60_000)
        if (files.isNotEmpty()) saveMixes(files.map { it to "Recovered mix ${stamp(Recordings.startedAt(it) ?: it.lastModified())}" }, recovered = true)
    }

    /**
     * Saves recorded WAVs as mixes, one after the other, in the background (REC shows SAVING), and
     * says how each went. A WAV that can't be saved stays, and is tried again at the next start.
     */
    private fun saveMixes(mixes: List<Pair<File, String>>, recovered: Boolean = false) {
        saving = "SAVING"
        Thread({
            var ok = true
            for ((wav, name) in mixes) {
                // A recording cut off before it was closed gets a proper header first.
                val secs = runCatching { WavWriter.repair(wav) }.getOrDefault(0.0)
                if (secs < Menus.MIN_RECORDING_SECONDS) { wav.delete(); continue }
                val r = runCatching { MixFiles.save(wav, name) { p -> saving = "${(p * 100).toInt()}%" } }
                ok = ok && r.isSuccess
                SwingUtilities.invokeLater {
                    r.onSuccess { f ->
                        console.say(if (recovered) Menus.recoveredMix(f.nameWithoutExtension, secs) else Menus.savedMix(f.nameWithoutExtension, secs), ms = 6000)
                    }.onFailure { console.say(Menus.saveFailed(it.message), error = true) }
                }
            }
            saving = null
            SwingUtilities.invokeLater {
                // Quit (or "Restart now") was chosen while it saved: now it can. Not after a failure,
                // so the error can be read (and a Mac's shut down waits for you).
                val restart = quitWhenSaved
                quitWhenSaved = null
                if (restart != null && ok) quit(restart)
                systemQuit?.cancelQuit()
                systemQuit = null
            }
        }, "save mix").start()
    }

    override fun recording() = recorder.recording
    override fun recordSeconds() = recorder.seconds
    override fun loadingText(deck: Int) = loading[deck] ?: fetching[deck]?.strip ?: tutLoading[deck]
    override fun savingText() = saving

    // ------------------------------------------------------------------ helpers

    fun error(msg: String) = JOptionPane.showMessageDialog(frame, msg, "MixDeck", JOptionPane.ERROR_MESSAGE)
    fun info(title: String, msg: String) = JOptionPane.showMessageDialog(frame, msg, title, JOptionPane.INFORMATION_MESSAGE)
    private fun deckName(i: Int) = if (i == 0) "A" else "B"
    private fun stamp(ms: Long = System.currentTimeMillis()) = SimpleDateFormat("d MMM yyyy HH.mm", Locale.getDefault()).format(Date(ms))

    companion object {
        init { YouTube.init() }
    }
}

/** Opens a file or folder with the computer's default app. */
fun openFile(f: File) {
    runCatching { java.awt.Desktop.getDesktop().open(f) }.onFailure {
        // Linux without a desktop integration: try xdg-open.
        runCatching { ProcessBuilder("xdg-open", f.path).start() }
    }
}

fun browse(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }.onFailure {
        runCatching { ProcessBuilder("xdg-open", url).start() }
    }
}
