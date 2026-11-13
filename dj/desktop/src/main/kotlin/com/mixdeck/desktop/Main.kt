package com.mixdeck.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.mixdeck.SongFetcher
import com.mixdeck.TrackStore
import com.mixdeck.YouTube
import com.mixdeck.engine.Analyzer
import com.mixdeck.engine.FxType
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.XfadeCurve
import com.mixdeck.ui.Console
import com.mixdeck.ui.TextMetrics
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
import java.util.concurrent.Executors
import java.util.prefs.Preferences
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--make-icons") {
        AppIcon.writeAll(File(args.getOrElse(1) { "icons" }))
        exitProcess(0)
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
    private val deckTrackIds = arrayOfNulls<String>(2)
    /** The id of the song on [deck], or null. */
    fun trackOn(deck: Int): String? = deckTrackIds[deck]
    @Volatile private var saving: String? = null
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "MixDeck loader").apply { isDaemon = true } }

    val frame = JFrame("MixDeck")
    private val panel = ConsolePanel()
    val console get() = panel.console

    fun show() {
        audio.start()
        restoreSettings()
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = quit()
        })
        frame.contentPane.add(panel, BorderLayout.CENTER)
        frame.iconImages = listOf(16, 32, 64, 128, 256).map { AppIcon.render(it) }
        runCatching { java.awt.Taskbar.getTaskbar().iconImage = AppIcon.render(512) }
        frame.minimumSize = Dimension(1100, 560)
        frame.size = Dimension(1600, 820)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        installKeys()
        if (!prefs.getBoolean("seenKey", false)) {
            console.helpMode = true
            prefs.putBoolean("seenKey", true)
            runCatching { prefs.flush() }
        }
        DesktopUpdater(this).check(manual = false)
        audio.error?.let { error(it) }
    }

    private fun quit() {
        if (recorder.recording) {
            val r = JOptionPane.showConfirmDialog(frame, "You're recording. Save the mix before quitting?", "MixDeck",
                JOptionPane.YES_NO_CANCEL_OPTION)
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return
            val wav = recorder.stop()
            if (r == JOptionPane.YES_OPTION && wav != null) {
                runCatching { MixFiles.save(wav, "Mix ${stamp()}") {} }
            } else wav?.delete()
        }
        saveSettings()
        audio.stop()
        exitProcess(0)
    }

    // ------------------------------------------------------------------ the console view

    inner class ConsolePanel : JPanel() {
        val console = Console(mixer, this@App)
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
            override fun windowLostFocus(e: WindowEvent) { held.clear() }
        })
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (!frame.isFocused) return@addKeyEventDispatcher false
            when (e.id) {
                KeyEvent.KEY_PRESSED -> if (held.add(e.keyCode)) keyDown(e.keyCode) else false
                KeyEvent.KEY_RELEASED -> { held.remove(e.keyCode); keyUp(e.keyCode) }
                else -> false
            }
        }
    }

    private inline fun act(crossinline f: Mixer.() -> Unit) = synchronized(mixer.lock) { mixer.f() }

    /** Keyboard shortcuts. Returns true if the key did something. */
    private fun keyDown(k: Int): Boolean {
        val a = 0; val b = 1
        when (k) {
            KeyEvent.VK_Q -> act { decks[a].cueDown(quantize) }
            KeyEvent.VK_W -> act { decks[a].playPause() }
            KeyEvent.VK_E -> act { sync(a) }
            KeyEvent.VK_R -> act { decks[a].autoLoop(4.0, quantize) }
            KeyEvent.VK_T -> act { decks[a].fx.on = !decks[a].fx.on }
            KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4 -> act { decks[a].hotCue(k - KeyEvent.VK_1, quantize) }
            KeyEvent.VK_O -> act { decks[b].cueDown(quantize) }
            KeyEvent.VK_P -> act { decks[b].playPause() }
            KeyEvent.VK_I -> act { sync(b) }
            KeyEvent.VK_U -> act { decks[b].autoLoop(4.0, quantize) }
            KeyEvent.VK_Y -> act { decks[b].fx.on = !decks[b].fx.on }
            KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9 -> act { decks[b].hotCue(k - KeyEvent.VK_7, quantize) }
            KeyEvent.VK_0 -> act { decks[b].hotCue(3, quantize) }
            KeyEvent.VK_LEFT -> act { crossfader = (crossfader - 0.1f).coerceAtLeast(-1f) }
            KeyEvent.VK_RIGHT -> act { crossfader = (crossfader + 0.1f).coerceAtMost(1f) }
            KeyEvent.VK_DOWN -> act { crossfader = 0f }
            KeyEvent.VK_Z -> act { sampler.trigger(0) }
            KeyEvent.VK_X -> act { sampler.trigger(1) }
            KeyEvent.VK_C -> act { sampler.trigger(2) }
            KeyEvent.VK_V -> act { sampler.trigger(3) }
            KeyEvent.VK_H, KeyEvent.VK_F1 -> console.helpMode = !console.helpMode
            // After the key event, so the dialog doesn't open inside the key dispatcher.
            KeyEvent.VK_N -> SwingUtilities.invokeLater { suggestNext(console.nextPlan()) }
            KeyEvent.VK_ESCAPE -> { console.helpMode = false; console.tutorial = null }
            else -> return false
        }
        return true
    }

    private fun keyUp(k: Int): Boolean {
        when (k) {
            KeyEvent.VK_Q -> act { decks[0].cueUp() }
            KeyEvent.VK_O -> act { decks[1].cueUp() }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ loading songs

    override fun openLibrary(deck: Int) {
        if (mixer.decks[deck].playing && JOptionPane.showConfirmDialog(frame,
                "Deck ${deckName(deck)} is playing. Load a new song onto it anyway?", "MixDeck",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        // Suggestions follow the other deck's song, else the song being replaced.
        val after = listOf(1 - deck, deck).firstOrNull { mixer.decks[it].loaded }
        LibraryDialog(this, deck, after = after) { id, sync -> loadTrack(deck, id, sync) }.isVisible = true
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
        LibraryDialog(this, next.target, after = after, waiting = next.waiting, suggest = true) { id, sync ->
            loadTrack(next.target, id, sync)
        }.isVisible = true
    }

    override fun deckStarted(deck: Int) {
        val id = deckTrackIds[deck] ?: return
        loader.execute { store.markPlayed(id) }
    }

    /** Loads song [id] onto [deck]; with [sync], matches its tempo to the other deck once loaded. */
    fun loadTrack(deck: Int, id: String, sync: Boolean = false) {
        val t = store.get(id) ?: return error("That song isn't on this computer any more")
        loading[deck] = "Loading ${t.title}…"
        loader.execute {
            try {
                val pcm = Ffmpeg.decode(store.audioFile(id)) { p -> loading[deck] = "Decoding ${(p * 100).toInt()}%" }
                // Beat grid, waveform, key and energy (only what this song is still missing).
                val (track, analysis) = suggester.analyze(t, pcm) { p -> loading[deck] = "Listening ${(p * 100).toInt()}%" }
                synchronized(mixer.lock) {
                    mixer.decks[deck].load(pcm, analysis, track.title, track.artist, track.loudnessDb)
                    if (sync) mixer.sync(deck)
                }
                deckTrackIds[deck] = id
            } catch (e: Throwable) {
                SwingUtilities.invokeLater { error("Couldn't load ${t.title}: ${e.message}") }
            } finally {
                loading[deck] = null
            }
        }
    }

    // ------------------------------------------------------------------ menus

    override fun deckMenu(deck: Int) {
        val d = mixer.decks[deck]
        if (!d.loaded) { openLibrary(deck); return }
        val fx = d.fx
        fun grid(change: () -> Unit) {
            synchronized(mixer.lock) { change() }
            val id = deckTrackIds[deck]; val a = d.analysis
            if (id != null && a != null) store.updateGrid(id, a.bpm, a.firstBeatSec)
        }
        MenuDialog(frame, "Deck ${deckName(deck)}: ${d.title}", listOf(
            "Double the BPM (${"%.1f".format(d.bpm * 2)})" to { grid { d.analysis?.let { if (it.bpm > 0) d.setAnalysis(it.withBpm(it.bpm * 2)) } } },
            "Halve the BPM (${"%.1f".format(d.bpm / 2)})" to { grid { d.analysis?.let { if (it.bpm > 0) d.setAnalysis(it.withBpm(it.bpm / 2)) } } },
            "Put the first beat here" to { grid { d.analysis?.let { d.setAnalysis(it.withBpm(if (it.bpm > 0) it.bpm else 120.0, d.positionSec)) } } },
            "Echo/flanger timing: ${beatsLabel(fx.beats)} beat" to { act { fx.beats = when (fx.beats) { 0.5 -> 0.75; 0.75 -> 1.0; 1.0 -> 0.25; else -> 0.5 } } },
            "Reset pitch to 0%" to { act { d.pitch = 0.0 } },
            "Eject" to { act { d.eject() }; deckTrackIds[deck] = null },
        )).isVisible = true
    }

    private fun beatsLabel(b: Double) = when (b) { 0.25 -> "¼"; 0.5 -> "½"; 0.75 -> "¾"; else -> "1" }

    override fun mainMenu() {
        fun onOff(b: Boolean) = if (b) "on" else "off"
        val items = listOf<Pair<String, () -> Unit>>(
            "▶ Tutorial: mix Nelly – Air Force Ones → Grillz" to { startTutorial() },
            "My mixes" to { MixesDialog(this).isVisible = true },
            "How it works (guide and keyboard shortcuts)" to { HelpDialog(this).isVisible = true },
            "Show button labels  (H)" to { console.helpMode = true },
            "Master volume…" to { slider("Master volume", mixer.master) { mixer.master = it } },
            "Sampler volume…" to { slider("Sampler volume", mixer.sampler.volume) { mixer.sampler.volume = it } },
            "Crossfader curve: ${if (mixer.curve == XfadeCurve.SMOOTH) "smooth (mixing)" else "sharp (scratching)"}" to {
                act { curve = if (curve == XfadeCurve.SMOOTH) XfadeCurve.SCRATCH else XfadeCurve.SMOOTH }; saveSettings()
            },
            "Quantize (snap cues & loops to the beat): ${onOff(mixer.quantize)}" to { act { quantize = !quantize }; saveSettings() },
            "Headphone pre-listen (split cue): ${onOff(mixer.splitCue)}" to {
                act { splitCue = !splitCue }; saveSettings()
                if (mixer.splitCue) info("Split cue is on",
                    "With headphones, your LEFT ear hears the mix and your RIGHT ear hears the decks with the headphones button lit.\n" +
                        "Don't use this through speakers: they'd play the cue too.")
            },
            "Open the Music/MixDeck folder" to { openFile(Places.mixes) },
            "Check for updates" to { DesktopUpdater(this).check(manual = true) },
            "About MixDeck" to { info("MixDeck ${Version.name}", "A two-deck DJ app. Your songs are kept in\n${Places.data}\nand your mixes in\n${Places.mixes}\n\nYouTube downloads are for personal use only.") },
        )
        MenuDialog(frame, "MixDeck ${Version.name}", items).isVisible = true
    }

    private fun slider(title: String, value: Float, set: (Float) -> Unit) {
        val s = JSlider(0, 100, (value * 100).toInt()).apply {
            majorTickSpacing = 25; paintTicks = true; paintLabels = true; preferredSize = Dimension(360, 60)
            addChangeListener { synchronized(mixer.lock) { set(this.value / 100f) } }
        }
        JOptionPane.showMessageDialog(frame, s, title, JOptionPane.PLAIN_MESSAGE)
        saveSettings()
    }

    private fun saveSettings() {
        prefs.putFloat("master", mixer.master); prefs.putFloat("sampler", mixer.sampler.volume)
        prefs.putBoolean("scratchCurve", mixer.curve == XfadeCurve.SCRATCH); prefs.putBoolean("quantize", mixer.quantize)
        prefs.putBoolean("splitCue", mixer.splitCue)
        runCatching { prefs.flush() }
    }

    private fun restoreSettings() = synchronized(mixer.lock) {
        mixer.master = prefs.getFloat("master", 0.85f)
        mixer.sampler.volume = prefs.getFloat("sampler", 0.7f)
        mixer.curve = if (prefs.getBoolean("scratchCurve", false)) XfadeCurve.SCRATCH else XfadeCurve.SMOOTH
        mixer.quantize = prefs.getBoolean("quantize", true)
        mixer.splitCue = prefs.getBoolean("splitCue", false)
    }

    // ------------------------------------------------------------------ tutorial

    fun startTutorial() {
        if (mixer.decks.any { it.playing } && JOptionPane.showConfirmDialog(frame,
                "This stops both decks and loads Nelly's \"Air Force Ones\" and \"Grillz\".", "Start the tutorial?",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        synchronized(mixer.lock) {
            for (d in mixer.decks) {
                d.pause(); d.eqLow = 0f; d.eqMid = 0f; d.eqHigh = 0f; d.filter = 0f; d.trimDb = 0.0
                d.volume = 1f; d.pitch = 0.0; d.fx.on = false; d.fx.type = FxType.ECHO
                d.fx.amount = 0.5f; d.vinylMode = true; d.headphone = false
                if (d.loopOn) d.loopToggle()
                for (h in 0..3) d.clearHotCue(h)
            }
            mixer.crossfader = 0f
        }
        val t = Tutorials.nelly()
        console.tutorial = t
        Thread({
            try {
                for ((deck, song) in listOf(0 to Tutorials.NELLY_A, 1 to Tutorials.NELLY_B)) {
                    val id = SongFetcher.findOrDownload(store, song) { msg -> t.status = msg }
                    t.status = "Loading onto deck ${deckName(deck)}…"
                    loadTrack(deck, id)
                }
                t.status = null
            } catch (e: Throwable) {
                t.status = "Couldn't get the songs (${e.message}). Check your internet and try again, " +
                    "or load them yourself with LOAD (search \"Nelly Air Force Ones\" and \"Nelly Grillz\")."
            }
        }, "tutorial songs").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ recording

    override fun toggleRecord() {
        if (saving != null) return
        if (!recorder.recording) {
            runCatching { recorder.start() }.onFailure { error("Couldn't start recording: ${it.message}") }
            return
        }
        val wav = recorder.stop() ?: return
        val secs = (wav.length() - 44) / 4.0 / mixer.sampleRate
        val name = JOptionPane.showInputDialog(frame, "Name your mix (${Console.fmtTime(secs)}):", "Save your mix",
            JOptionPane.PLAIN_MESSAGE, null, null, "Mix ${stamp()}") as String?
        if (name == null) {
            if (JOptionPane.showConfirmDialog(frame, "Throw this recording away?", "Discard", JOptionPane.YES_NO_OPTION) ==
                JOptionPane.YES_OPTION) { wav.delete(); return }
        }
        saving = "SAVING"
        Thread({
            val r = runCatching { MixFiles.save(wav, name ?: "Mix ${stamp()}") { p -> saving = "${(p * 100).toInt()}%" } }
            saving = null
            SwingUtilities.invokeLater {
                r.onSuccess { f ->
                    val open = JOptionPane.showOptionDialog(frame, "\"${f.nameWithoutExtension}\" is saved in My mixes\n(${f.parent}).",
                        "Mix saved", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                        arrayOf("Open My mixes", "OK"), "OK")
                    if (open == 0) MixesDialog(this).isVisible = true
                }.onFailure { error("Couldn't save the mix: ${it.message}") }
            }
        }, "save mix").start()
    }

    override fun recording() = recorder.recording
    override fun recordSeconds() = recorder.seconds
    override fun loadingText(deck: Int) = loading[deck]
    override fun savingText() = saving

    // ------------------------------------------------------------------ helpers

    fun error(msg: String) = JOptionPane.showMessageDialog(frame, msg, "MixDeck", JOptionPane.ERROR_MESSAGE)
    fun info(title: String, msg: String) = JOptionPane.showMessageDialog(frame, msg, title, JOptionPane.INFORMATION_MESSAGE)
    private fun deckName(i: Int) = if (i == 0) "A" else "B"
    private fun stamp() = SimpleDateFormat("d MMM yyyy HH.mm", Locale.getDefault()).format(Date())

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
