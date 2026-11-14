package com.mixdeck.desktop

import com.mixdeck.LibraryMemory
import com.mixdeck.LibraryRules
import com.mixdeck.LibraryTab
import com.mixdeck.Placement
import com.mixdeck.Problems
import com.mixdeck.SongPick
import com.mixdeck.Spotify
import com.mixdeck.SpotifyList
import com.mixdeck.SpotifyTrack
import com.mixdeck.Track
import com.mixdeck.YouTube
import com.mixdeck.YtResult
import com.mixdeck.ui.Console
import com.mixdeck.ui.PickCheck
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.Executors
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Picks a song for a deck: suggestions that fit after deck [after]'s song, songs on this
 * computer, YouTube search, a Spotify link, or an audio file. Opens on "Suggested" when [suggest]
 * is set. A pick closes the window at once: [onPick] gets the song (one that still needs
 * downloading downloads on the deck) and where it goes, after asking if the deck is playing.
 */
class LibraryDialog(
    private val app: App, private val deck: Int,
    private val after: Int? = null, private val waiting: Boolean = false, private val suggest: Boolean = false,
    private val onPick: (SongPick, Placement) -> Unit,
) : JDialog(app.frame, "Load into Deck ${Console.deckName(deck)}", true) {

    private val store = app.store
    private val prefs = Preferences.userRoot().node("mixdeck")
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "library").apply { isDaemon = true } }
    /** The status line; it wraps to a second line rather than cutting a hint short. */
    private val status = javax.swing.JTextArea(" ").apply {
        isEditable = false; isFocusable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
        font = UIManager.getFont("Label.font")
    }
    /** Copying a file (the only work done in this window). */
    @Volatile private var busy = false
    private val accent = Color(if (deck == 0) 0x29B6F6 else 0xFF7043)

    private class Row(val title: String, val sub: String, val data: Any) {
        override fun toString() = "<html><b>${esc(title)}</b><br><span style='color:#9a9aa8'>${esc(sub)}</span></html>"
    }

    // ---------------------------------------------------------------- YouTube

    private val ytField = JTextField(LibraryMemory.query).apply {
        putClientProperty("JTextField.placeholderText", "Search songs, or paste a YouTube or Spotify link")
    }

    private fun youTubeTab(): JPanel {
        val p = JPanel(BorderLayout(6, 6)).apply { border = BorderFactory.createEmptyBorder(10, 10, 10, 10) }
        val field = ytField
        // [Songs | Videos]: the active half is filled. Switching runs the search again.
        val songs = JButton("Songs"); val videos = JButton("Videos")
        fun styleMode() { segment(songs, LibraryMemory.songsOnly); segment(videos, !LibraryMemory.songsOnly) }
        val go = JButton("Search")
        val model = DefaultListModel<Row>()
        val list = rowList(model)
        val more = JButton("More results")
        // The list shows LibraryMemory's results, also a page that arrives after the window that
        // asked for it closed. More asks for one page at a time, whichever window asked.
        fun redraw() {
            val r = LibraryMemory.results
            // A page was added: only it is added to the list (a selected row stays). A new search: start over.
            if (model.size() > r.size || (0 until model.size()).any { model[it].data !== r[it] }) model.clear()
            for (i in model.size() until r.size) model.addElement(ytRow(r[i]))
            more.isEnabled = LibraryMemory.search?.hasMore == true && r.isNotEmpty()
        }
        redraw()
        redrawResults = ::redraw
        LibraryMemory.watch(redrawResults)
        // Before the first search: what to type, and two songs to start with.
        val cards = CardLayout()
        val center = JPanel(cards)
        center.add(JScrollPane(list), "list")
        center.add(emptyCard(LibraryRules.SEARCH_TITLE, LibraryRules.SEARCH_BODY,
            LibraryRules.STARTERS.map { (label, q) -> label to { field.text = q; runSearch(q) { cards.show(center, "list") } } }), "empty")
        if (LibraryMemory.search == null && LibraryMemory.results.isEmpty()) cards.show(center, "empty")

        fun run() = runSearch(field.text) { cards.show(center, "list") }
        more.addActionListener {
            val s = LibraryMemory.search ?: return@addActionListener
            if (LibraryMemory.fetching) return@addActionListener
            LibraryMemory.fetching = true
            worker.execute {
                val r = runCatching { s.nextPage() }
                SwingUtilities.invokeLater {
                    if (!LibraryMemory.pageArrived(s, r.getOrDefault(emptyList())) || !isDisplayable) return@invokeLater
                    more.isEnabled = s.hasMore
                    r.onFailure { setStatus("Couldn't load more results: ${Problems.explain(it)}") }
                }
            }
        }
        field.addActionListener { run() }
        go.addActionListener { run() }
        songs.addActionListener { if (!LibraryMemory.songsOnly) { LibraryMemory.songsOnly = true; styleMode(); run() } }
        videos.addActionListener { if (LibraryMemory.songsOnly) { LibraryMemory.songsOnly = false; styleMode(); run() } }
        styleMode()
        onPick(list) { row -> pickYouTube(row.data as YtResult) }

        val top = JPanel(BorderLayout(6, 0))
        top.add(field, BorderLayout.CENTER)
        top.add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            add(songs); add(videos); add(javax.swing.Box.createHorizontalStrut(6)); add(go)
        }, BorderLayout.EAST)
        p.add(top, BorderLayout.NORTH)
        p.add(center, BorderLayout.CENTER)
        p.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(more) }, BorderLayout.SOUTH)
        return p
    }

    /** LibraryMemory's results changed: the YouTube tab redraws its list (see [youTubeTab]). */
    private var redrawResults: () -> Unit = {}

    /** Searches YouTube for [text] (or follows a pasted link); every search is remembered in LibraryMemory. */
    private fun runSearch(text: String, showList: () -> Unit) {
        val q = text.trim()
        if (q.isEmpty()) return
        // A Spotify link pasted here: the Spotify tab imports it.
        if (Spotify.isSpotifyUrl(q)) { tabs.selectedComponent = spotifyPanel; importSpotify(q); return }
        if (YouTube.isYouTubeUrl(q)) { YouTube.videoId(q)?.let { pickYouTube(YtResult(q, it, "YouTube video", "", 0)) }; return }
        val s = runCatching { YouTube.Search(q, LibraryMemory.songsOnly) }.getOrElse { setStatus("Search failed: ${Problems.explain(it)}"); return }
        LibraryMemory.newSearch(q, s)
        showList()
        setStatus("Searching YouTube for \"$q\"…")
        worker.execute {
            val r = runCatching { s.nextPage() }
            SwingUtilities.invokeLater {
                if (!LibraryMemory.pageArrived(s, r.getOrDefault(emptyList())) || !isDisplayable) return@invokeLater
                r.onSuccess { items -> setStatus(if (items.isEmpty()) "Nothing found." else "Double-click a song to load it.") }
                    .onFailure { setStatus("Search failed: ${Problems.explain(it)}") }
            }
        }
    }

    private fun ytRow(r: YtResult) = Row(r.title, listOf(r.artist, fmt(r.durationSec), if (store.has("yt_" + r.id)) "on this computer" else "")
        .filter { it.isNotEmpty() }.joinToString("  ·  "), r)

    /** Saved already: load it. Otherwise the console downloads it on the deck. */
    private fun pickYouTube(r: YtResult) {
        val id = "yt_" + r.id
        if (store.has(id)) return finishWith(SongPick.Saved(id, store.get(id)?.title ?: r.title))
        finishWith(SongPick.YouTube(r.url, r.id, r.title, r.artist, r.durationSec))
    }

    // ---------------------------------------------------------------- Spotify

    private val spotifyModel = DefaultListModel<Row>()
    private val spotifyTitle = JLabel(" ").apply { font = font.deriveFont(Font.BOLD, 15f) }
    private val savedLists = JComboBox<String>()
    /** Saved songs by name, worked out once per list ("on this computer" rows load offline). */
    private var spotifyHave: Map<String, List<Track>> = emptyMap()

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
        val first = store.spotifyLists().firstOrNull()
        if (first != null) showSpotify(first) else {
            // No list yet: how to get a link out of Spotify.
            spotifyTitle.font = spotifyTitle.font.deriveFont(Font.PLAIN, 13f)
            spotifyTitle.text = "<html><span style='color:#b0b0bc'>${esc(SPOTIFY_EMPTY)}</span></html>"
        }
        return p
    }

    private fun refreshSavedLists() {
        savedLists.removeAllItems()
        store.spotifyLists().forEach { savedLists.addItem("${it.name} (${it.tracks.size})") }
    }

    private fun showSpotify(l: SpotifyList) {
        spotifyHave = LibraryRules.savedNames(store)
        spotifyTitle.font = spotifyTitle.font.deriveFont(Font.BOLD, 15f)
        spotifyTitle.text = "${l.name}  (${l.tracks.size} songs)"
        spotifyModel.clear()
        l.tracks.forEach {
            val have = LibraryRules.savedId(spotifyHave, it) != null
            spotifyModel.addElement(Row(it.title, "${it.artist}  ·  ${fmt(it.durationSec)}" + if (have) "  ·  on this computer" else "", it))
        }
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

    /** Saved already ("on this computer"): load it, offline. Otherwise the console finds and downloads it on the deck. */
    private fun pickSpotify(t: SpotifyTrack) {
        val id = LibraryRules.savedId(spotifyHave, t)
        finishWith(if (id != null) SongPick.Saved(id, t.title) else SongPick.Spotify(t))
    }

    // ---------------------------------------------------------------- My songs

    private fun mySongsTab(): JPanel {
        val p = JPanel(BorderLayout(6, 6)).apply { border = BorderFactory.createEmptyBorder(10, 10, 10, 10) }
        val filter = JTextField(LibraryMemory.mySongsFilter)
        val model = DefaultListModel<Row>()
        val list = rowList(model)
        fun refresh() {
            LibraryMemory.mySongsFilter = filter.text
            val all = store.all()
            filter.putClientProperty("JTextField.placeholderText", LibraryRules.filterHint(all.size))
            model.clear()
            // Songs on a deck last, marked "on deck A"; the filter also takes a key like 8A.
            LibraryRules.mySongs(all, (0..1).map(app::trackOn), filter.text).forEach { (t, onDeck) ->
                model.addElement(Row(t.title, LibraryRules.songSubtitle(t, onDeck), t))
            }
        }
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
        refresh()
        fun load(t: Track) = finishWith(SongPick.Saved(t.id, t.title))
        onPick(list) { row -> load(row.data as Track) }
        val delete = JButton("Delete").apply {
            addActionListener {
                val row = list.selectedValue ?: return@addActionListener
                val t = row.data as Track
                if (JOptionPane.showConfirmDialog(this@LibraryDialog, "Delete \"${t.title}\" from this computer?", "Delete",
                        JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) { store.delete(t.id); refresh() }
            }
        }
        val loadB = JButton("Load").apply { addActionListener { list.selectedValue?.let { load(it.data as Track) } } }
        p.add(filter, BorderLayout.NORTH)
        p.add(JScrollPane(list), BorderLayout.CENTER)
        p.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(delete); add(loadB) }, BorderLayout.SOUTH)
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
        if (busy) { setStatus("Still working on the last one…"); return }
        busy = true
        setStatus("Copying ${f.name}…")
        worker.execute {
            val r = runCatching {
                val id = "file_" + Integer.toHexString((f.name + f.length()).hashCode())
                if (!store.has(id)) {
                    f.copyTo(store.audioFile(id), overwrite = true)
                    store.save(Track(id, f.nameWithoutExtension, "", f.path))
                }
                id
            }
            SwingUtilities.invokeLater {
                busy = false
                r.onSuccess { finishWith(SongPick.Saved(it, store.get(it)?.title ?: f.nameWithoutExtension)) }
                    .onFailure { setStatus("Couldn't copy it: ${Problems.explain(it)}") }
            }
        }
    }

    // ---------------------------------------------------------------- picking

    /** The deck other than [target] holds a song with a beat (so there's a tempo to SYNC to). */
    private fun otherHasTempo(target: Int) = app.mixer.decks[1 - target].let { it.loaded && it.bpm > 0 }

    /**
     * SYNC for a song loaded onto [target]: the box when it shows; while it's hidden, Suggested
     * picks keep the choice (they match the song they replace) and other tabs don't sync.
     */
    private fun syncFor(target: Int, fromSuggested: Boolean) =
        prefs.getBoolean("nextSync", true) && (otherHasTempo(target) || fromSuggested)

    /**
     * A song was picked: if its deck is playing, ask where it should go (PickCheck), then hand it
     * over and close at once. [fromSuggested]: picked on the Suggested tab (SYNC rule).
     */
    private fun finishWith(pick: SongPick, fromSuggested: Boolean = false) {
        if (!isDisplayable) return
        val sync = syncFor(deck, fromSuggested)
        val q = synchronized(app.mixer.lock) { PickCheck.question(app.mixer, deck, pick.title, needsDownload = pick !is SongPick.Saved) }
        var placement = Placement(deck, sync)
        if (q != null) {
            val labels = q.labelList.toTypedArray()
            val r = JOptionPane.showOptionDialog(this, q.message, q.title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, labels, labels[0])
            placement = q.options.getOrNull(r)?.let { Placement.of(it, deck, sync, syncFor(1 - deck, fromSuggested)) } ?: return
        }
        dispose()
        onPick(pick, placement)
    }

    // ---------------------------------------------------------------- helpers

    private val tabs = JTabbedPane()
    private lateinit var suggested: SuggestedPanel
    private lateinit var minePanel: JPanel
    private lateinit var youTubePanel: JPanel
    private lateinit var spotifyPanel: JPanel
    private lateinit var filesPanel: JPanel
    /** "SYNC to deck A's tempo", in the status row on every tab. */
    private val syncBox = JCheckBox("SYNC to deck ${Console.deckName(1 - deck)}'s tempo", prefs.getBoolean("nextSync", true)).apply {
        addActionListener { prefs.putBoolean("nextSync", isSelected) }
    }
    private val ticker = Timer(1000) { refreshLive() }

    /** The title's countdown ("Load into Deck B — Deck A ends in 0:43") and whether SYNC applies. */
    private fun refreshLive() {
        val cd = PickCheck.countdown(app.mixer, deck)
        title = "Load into Deck ${Console.deckName(deck)}" + (cd?.let { " — ${it.text}" } ?: "")
        syncBox.isVisible = otherHasTempo(deck)
    }

    /** Each tab's hint in the status line. */
    private fun tabChanged() {
        when (tabs.selectedComponent) {
            suggested -> { suggested.start(); setStatus(suggested.status) }
            minePanel -> setStatus("Songs on this computer load instantly. Double-click one to load it.")
            youTubePanel -> setStatus(LibraryRules.YOUTUBE_HINT)
            spotifyPanel -> setStatus("Paste a Spotify playlist, album or song link. Each song is matched to its audio on YouTube.")
            filesPanel -> setStatus("Pick an audio file from this computer.")
        }
    }

    /** Runs however the window closes (a pick, the close button, Esc): stops the Suggested tab's work. */
    override fun dispose() {
        LibraryMemory.unwatch(redrawResults)
        ticker.stop()
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

    /** One half of [Songs | Videos]: the active half is filled with the deck's colour. */
    private fun segment(b: JButton, on: Boolean) {
        b.background = if (on) accent else UIManager.getColor("Button.background")
        b.foreground = if (on) Color.BLACK else UIManager.getColor("Button.foreground")
        b.font = b.font.deriveFont(if (on) Font.BOLD else Font.PLAIN)
    }

    /** A title, a line of advice and buttons that lead somewhere (label to action), centred. */
    private fun emptyCard(title: String, body: String, buttons: List<Pair<String, () -> Unit>>): JComponent {
        val p = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply { gridx = 0; insets = Insets(6, 6, 6, 6) }
        p.add(JLabel(title).apply { font = font.deriveFont(Font.BOLD, 18f) }, c)
        c.gridy = 1
        p.add(JLabel("<html><div style='text-align:center;width:460px;color:#b0b0bc'>${esc(body)}</div></html>", JLabel.CENTER), c)
        c.gridy = 2
        p.add(JPanel(FlowLayout(FlowLayout.CENTER, 10, 0)).apply {
            for ((label, action) in buttons) add(JButton(label).apply { addActionListener { action() } })
        }, c)
        return p
    }

    private fun setStatus(s: String) = SwingUtilities.invokeLater { status.text = s; status.revalidate() }

    // Runs after all the properties above exist (Kotlin initialises in source order).
    init {
        suggested = SuggestedPanel(app, deck, after, waiting, setStatus = { setStatus(it) },
            searchYouTube = { tabs.selectedComponent = youTubePanel; ytField.requestFocusInWindow() }) { id, title ->
            finishWith(SongPick.Saved(id, title), fromSuggested = true)
        }
        minePanel = mySongsTab(); youTubePanel = youTubeTab(); spotifyPanel = spotifyTab(); filesPanel = filesTab()
        // Suggested | My songs | YouTube | Spotify | Files
        tabs.addTab("Suggested", suggested)
        tabs.addTab("My songs", minePanel)
        tabs.addTab("YouTube", youTubePanel)
        tabs.addTab("Spotify", spotifyPanel)
        tabs.addTab("Files", filesPanel)
        val first = LibraryRules.defaultTab(suggest, LibraryRules.hasSavedNotOnDeck(store.all(), (0..1).map(app::trackOn)))
        tabs.selectedComponent = when (first) {
            LibraryTab.SUGGESTED -> suggested
            LibraryTab.MINE -> minePanel
            LibraryTab.SPOTIFY -> spotifyPanel
            LibraryTab.FILES -> filesPanel
            LibraryTab.YOUTUBE -> youTubePanel
        }
        tabs.addChangeListener { tabChanged() }
        tabChanged()
        // YouTube first (no saved songs): type straight away.
        if (first == LibraryTab.YOUTUBE) addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) { ytField.requestFocusInWindow(); ytField.selectAll() }
        })
        status.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
        status.foreground = Color(0xB0, 0xB0, 0xBC)
        contentPane.add(tabs, BorderLayout.CENTER)
        // Bottom row: the status line, and on the right "SYNC to deck A's tempo" (every tab).
        contentPane.add(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 10, 2)).apply { add(syncBox) }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        refreshLive()
        ticker.start()
        size = Dimension(900, 620)
        setLocationRelativeTo(app.frame)
        // The close button must dispose (not just hide) the window, or its background work keeps running.
        defaultCloseOperation = DISPOSE_ON_CLOSE
        rootPane.registerKeyboardAction({ dispose() }, javax.swing.KeyStroke.getKeyStroke("ESCAPE"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
    }

    companion object {
        fun fmt(sec: Long) = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** The Spotify tab before any list was imported (computer wording: "double-click"). */
        val SPOTIFY_EMPTY = LibraryRules.SPOTIFY_EMPTY.replace("when you tap it", "when you double-click it")
    }
}
