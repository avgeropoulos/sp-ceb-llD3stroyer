package com.mixdeck.desktop

import com.mixdeck.Spotify
import com.mixdeck.SpotifyList
import com.mixdeck.SpotifyTrack
import com.mixdeck.Track
import com.mixdeck.YouTube
import com.mixdeck.YtResult
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.Executors
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import com.mixdeck.ui.Console
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Picks a song for a deck: suggestions that fit after deck [after]'s song, YouTube search, Spotify
 * link, songs on this computer, or an audio file. Opens on "Suggested" when [suggest] is set.
 * [onPick] gets the song id and whether to SYNC it once loaded.
 */
class LibraryDialog(
    private val app: App, private val deck: Int,
    private val after: Int? = null, private val waiting: Boolean = false, private val suggest: Boolean = false,
    private val onPick: (String, Boolean) -> Unit,
) : JDialog(app.frame, "Load into Deck ${if (deck == 0) "A" else "B"}", true) {

    private val store = app.store
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "library").apply { isDaemon = true } }
    private val status = JLabel(" ")
    @Volatile private var busy = false

    private class Row(val title: String, val sub: String, val data: Any) {
        override fun toString() = "<html><b>${esc(title)}</b><br><span style='color:#9a9aa8'>${esc(sub)}</span></html>"
    }

    // ---------------------------------------------------------------- YouTube

    private fun youTubeTab(): JPanel {
        val p = JPanel(BorderLayout(6, 6)).apply { border = BorderFactory.createEmptyBorder(10, 10, 10, 10) }
        val field = JTextField().apply { putClientProperty("JTextField.placeholderText", "Search songs, or paste a YouTube or Spotify link") }
        val songs = JToggleButton("Songs only", true)
        val go = JButton("Search")
        val model = DefaultListModel<Row>()
        val list = rowList(model)
        var search: YouTube.Search? = null
        var loadingMore = false
        val more = JButton("More results").apply { isEnabled = false }

        fun run() {
            val q = field.text.trim()
            if (q.isEmpty()) return
            if (Spotify.isSpotifyUrl(q)) { importSpotify(q); return }
            if (YouTube.isYouTubeUrl(q)) { YouTube.videoId(q)?.let { pickYouTube(YtResult(q, it, "YouTube video", "", 0)) }; return }
            val s = YouTube.Search(q, songs.isSelected)
            search = s; model.clear(); more.isEnabled = false
            setStatus("Searching YouTube for \"$q\"…")
            worker.execute {
                val r = runCatching { s.nextPage() }
                SwingUtilities.invokeLater {
                    if (search !== s) return@invokeLater
                    r.onSuccess { items -> items.forEach { model.addElement(ytRow(it)) }; more.isEnabled = s.hasMore
                        setStatus(if (items.isEmpty()) "Nothing found." else "Double-click a song to load it.") }
                        .onFailure { setStatus("Search failed: ${it.message}") }
                }
            }
        }
        more.addActionListener {
            val s = search ?: return@addActionListener
            if (loadingMore) return@addActionListener
            loadingMore = true
            worker.execute {
                val r = runCatching { s.nextPage() }
                SwingUtilities.invokeLater {
                    loadingMore = false
                    r.onSuccess { items -> items.forEach { model.addElement(ytRow(it)) }; more.isEnabled = s.hasMore }
                }
            }
        }
        field.addActionListener { run() }
        go.addActionListener { run() }
        songs.addActionListener { songs.text = if (songs.isSelected) "Songs only" else "All videos"; run() }
        onPick(list) { row -> pickYouTube(row.data as YtResult) }

        val top = JPanel(BorderLayout(6, 0))
        top.add(field, BorderLayout.CENTER)
        top.add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { add(songs); add(go) }, BorderLayout.EAST)
        p.add(top, BorderLayout.NORTH)
        p.add(JScrollPane(list), BorderLayout.CENTER)
        p.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(more) }, BorderLayout.SOUTH)
        return p
    }

    private fun ytRow(r: YtResult) = Row(r.title, listOf(r.artist, fmt(r.durationSec), if (store.has("yt_" + r.id)) "on this computer" else "")
        .filter { it.isNotEmpty() }.joinToString("  ·  "), r)

    private fun pickYouTube(r: YtResult) {
        val id = "yt_" + r.id
        if (store.has(id)) return finishWith(id)
        background("Downloading ${r.title}…") {
            val info = YouTube.downloadAudio(r.url, store.audioFile(id)) { p -> setStatus("Downloading ${r.title}… ${(p * 100).toInt()}%") }
            store.save(Track(id, info.title.ifEmpty { r.title }, info.artist.ifEmpty { r.artist }, r.url, info.durationSec.toDouble()))
            id
        }
    }

    // ---------------------------------------------------------------- Spotify

    private val spotifyModel = DefaultListModel<Row>()
    private val spotifyTitle = JLabel(" ").apply { font = font.deriveFont(Font.BOLD, 15f) }
    private val savedLists = JComboBox<String>()

    private fun spotifyTab(): JPanel {
        val p = JPanel(BorderLayout(6, 6)).apply { border = BorderFactory.createEmptyBorder(10, 10, 10, 10) }
        val field = JTextField().apply { putClientProperty("JTextField.placeholderText", "Paste a Spotify playlist, album or song link") }
        val go = JButton("Import")
        field.addActionListener { importSpotify(field.text) }
        go.addActionListener { importSpotify(field.text) }
        val list = rowList(spotifyModel)
        onPick(list) { row -> pickSpotify(row.data as SpotifyTrack) }
        refreshSavedLists()
        savedLists.addActionListener {
            val i = savedLists.selectedIndex
            store.spotifyLists().getOrNull(i)?.let { showSpotify(it) }
        }
        val top = JPanel(BorderLayout(6, 6))
        top.add(JPanel(BorderLayout(6, 0)).apply { add(field, BorderLayout.CENTER); add(go, BorderLayout.EAST) }, BorderLayout.NORTH)
        top.add(JPanel(BorderLayout(6, 0)).apply {
            add(spotifyTitle, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { add(JLabel("Saved lists:")); add(savedLists) }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        p.add(top, BorderLayout.NORTH)
        p.add(JScrollPane(list), BorderLayout.CENTER)
        p.add(JLabel("<html><span style='color:#9a9aa8'>Spotify doesn't let apps mix its audio, so each song is matched to its audio on YouTube Music.</span></html>"), BorderLayout.SOUTH)
        store.spotifyLists().firstOrNull()?.let { showSpotify(it) }
        return p
    }

    private fun refreshSavedLists() {
        savedLists.removeAllItems()
        store.spotifyLists().forEach { savedLists.addItem("${it.name} (${it.tracks.size})") }
    }

    private fun showSpotify(l: SpotifyList) {
        spotifyTitle.text = "${l.name}  (${l.tracks.size} songs)"
        spotifyModel.clear()
        l.tracks.forEach { spotifyModel.addElement(Row(it.title, "${it.artist}  ·  ${fmt(it.durationSec)}", it)) }
    }

    private fun importSpotify(link: String) {
        if (link.isBlank()) return
        setStatus("Reading the Spotify link…")
        worker.execute {
            val r = runCatching { Spotify.fetch(link).also { store.saveSpotifyList(it) } }
            SwingUtilities.invokeLater {
                r.onSuccess { showSpotify(it); refreshSavedLists(); setStatus("Double-click a song to find it on YouTube and load it.") }
                    .onFailure { setStatus("Couldn't import: ${it.message}") }
            }
        }
    }

    private fun pickSpotify(t: SpotifyTrack) {
        background("Finding ${t.title} on YouTube…") {
            val match = Spotify.matchOnYouTube(t)
            val id = "yt_" + match.id
            if (!store.has(id)) {
                YouTube.downloadAudio(match.url, store.audioFile(id)) { p -> setStatus("Downloading ${t.title}… ${(p * 100).toInt()}%") }
                store.save(Track(id, t.title, t.artist, match.url, match.durationSec.toDouble()))
            }
            id
        }
    }

    // ---------------------------------------------------------------- My songs

    private fun mySongsTab(): JPanel {
        val p = JPanel(BorderLayout(6, 6)).apply { border = BorderFactory.createEmptyBorder(10, 10, 10, 10) }
        val filter = JTextField().apply { putClientProperty("JTextField.placeholderText", "Filter my songs") }
        val model = DefaultListModel<Row>()
        val list = rowList(model)
        fun refresh() {
            val q = filter.text.lowercase()
            model.clear()
            store.all().filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }.forEach {
                model.addElement(Row(it.title, listOf(it.artist, if (it.bpm > 0) "%.1f BPM".format(it.bpm) else "",
                    if (it.durationSec > 0) fmt(it.durationSec.toLong()) else "").filter { s -> s.isNotEmpty() }.joinToString("  ·  "), it))
            }
        }
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
        refresh()
        onPick(list) { row -> finishWith((row.data as Track).id) }
        val delete = JButton("Delete").apply {
            addActionListener {
                val row = list.selectedValue ?: return@addActionListener
                val t = row.data as Track
                if (JOptionPane.showConfirmDialog(this@LibraryDialog, "Delete \"${t.title}\" from this computer?", "Delete",
                        JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) { store.delete(t.id); refresh() }
            }
        }
        val load = JButton("Load").apply { addActionListener { list.selectedValue?.let { finishWith((it.data as Track).id) } } }
        p.add(filter, BorderLayout.NORTH)
        p.add(JScrollPane(list), BorderLayout.CENTER)
        p.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(delete); add(load) }, BorderLayout.SOUTH)
        return p
    }

    // ---------------------------------------------------------------- Files

    private fun filesTab(): JPanel {
        val p = JPanel(BorderLayout()).apply { border = BorderFactory.createEmptyBorder(40, 40, 40, 40) }
        p.add(JLabel("<html><div style='text-align:center;font-size:13px'>Load an MP3, M4A, FLAC, WAV, OGG or other audio file from this computer.</div></html>", JLabel.CENTER), BorderLayout.CENTER)
        p.add(JPanel().apply {
            add(JButton("Choose a file…").apply {
                preferredSize = Dimension(220, 44)
                addActionListener {
                    val fc = JFileChooser(File(System.getProperty("user.home"), "Music").takeIf { it.isDirectory })
                    fc.fileFilter = FileNameExtensionFilter("Audio files", "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "aiff", "aif", "wma", "webm", "mp4")
                    if (fc.showOpenDialog(this@LibraryDialog) == JFileChooser.APPROVE_OPTION) importFile(fc.selectedFile)
                }
            })
        }, BorderLayout.SOUTH)
        return p
    }

    private fun importFile(f: File) {
        background("Copying ${f.name}…") {
            val id = "file_" + Integer.toHexString((f.name + f.length()).hashCode())
            if (!store.has(id)) {
                f.copyTo(store.audioFile(id), overwrite = true)
                store.save(Track(id, f.nameWithoutExtension, "", f.path))
            }
            id
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun background(what: String, job: () -> String) {
        if (busy) { setStatus("Still working on the last one…"); return }
        busy = true
        setStatus(what)
        worker.execute {
            val r = runCatching(job)
            SwingUtilities.invokeLater {
                busy = false
                r.onSuccess { finishWith(it) }.onFailure { setStatus("Failed: ${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }

    private fun finishWith(id: String, sync: Boolean = false) {
        if (!isDisplayable) return // closed while a download was finishing
        // LOAD already asked before opening this window; NEXT asks now, whichever tab the song came from.
        val d = app.mixer.decks[deck]
        if (suggest && d.playing) {
            val name = if (deck == 0) "A" else "B"
            val title = store.get(id)?.title ?: "this song"
            if (JOptionPane.showConfirmDialog(this,
                    "Deck $name is still playing (${Console.fmtTime((d.durationSec - d.positionSec) / (1 + d.pitch))} left).\n" +
                        "Stop it and load \"$title\" now?", "Load on Deck $name",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        }
        dispose()
        onPick(id, sync)
    }

    private lateinit var suggested: SuggestedPanel

    /** Runs however the window closes (a pick, the close button, Esc): stops the Suggested tab's work. */
    override fun dispose() {
        if (::suggested.isInitialized) suggested.close()
        worker.shutdown()
        super.dispose()
    }

    private fun rowList(model: DefaultListModel<Row>) = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = 46
    }

    private fun onPick(list: JList<Row>, f: (Row) -> Unit) {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) list.selectedValue?.let(f)
            }
        })
        list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "pick")
        list.actionMap.put("pick", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { list.selectedValue?.let(f) }
        })
    }

    private fun setStatus(s: String) = SwingUtilities.invokeLater { status.text = s }

    // Runs after all the properties above exist (Kotlin initialises in source order).
    init {
        val tabs = JTabbedPane()
        suggested = SuggestedPanel(app, deck, after, waiting, confirmPlaying = false, setStatus = { setStatus(it) }) { id, sync -> finishWith(id, sync) }
        tabs.addTab("Suggested", suggested)
        tabs.addTab("YouTube", youTubeTab())
        tabs.addTab("Spotify", spotifyTab())
        tabs.addTab("My songs", mySongsTab())
        tabs.addTab("Files", filesTab())
        tabs.selectedIndex = when {
            suggest -> 0
            store.all().isNotEmpty() -> 3
            else -> 1
        }
        status.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
        status.foreground = Color(0xB0, 0xB0, 0xBC)
        contentPane.add(tabs, BorderLayout.CENTER)
        contentPane.add(status, BorderLayout.SOUTH)
        size = Dimension(900, 620)
        setLocationRelativeTo(app.frame)
        // The close button must dispose (not just hide) the window, or its background work keeps running.
        defaultCloseOperation = DISPOSE_ON_CLOSE
        rootPane.registerKeyboardAction({ dispose() }, javax.swing.KeyStroke.getKeyStroke("ESCAPE"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
        if (!suggest) setStatus("Pick a song for deck ${if (deck == 0) "A" else "B"}. Double-click a song to load it.")
    }

    companion object {
        fun fmt(sec: Long) = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
