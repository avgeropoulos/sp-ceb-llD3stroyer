package com.mixdeck.desktop

import com.mixdeck.NextSongs
import com.mixdeck.engine.Direction
import com.mixdeck.engine.Suggestion
import com.mixdeck.ui.Console
import com.mixdeck.ui.KeyDisplay
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import kotlin.math.abs

/**
 * The library's "Suggested" tab: songs on this computer that fit after the one on deck [after],
 * best first, with the reasons. [pick] gets the song id and whether to SYNC it after loading.
 */
class SuggestedPanel(
    private val app: App,
    private val deck: Int,
    private val after: Int?,
    waiting: Boolean,
    /** Ask before replacing a playing song (NEXT; LOAD has already asked). */
    private val confirmPlaying: Boolean,
    private val setStatus: (String) -> Unit,
    private val pick: (id: String, sync: Boolean) -> Unit,
) : JPanel(BorderLayout(6, 6)) {

    /** One row: a suggestion, or the song already waiting on the deck. */
    private class Item(val s: Suggestion, val onDeck: Boolean, val isNew: Boolean)

    private val prefs = Preferences.userRoot().node("mixdeck")
    private val deckName = if (deck == 0) "A" else "B"
    private val model = DefaultListModel<Item>()
    private val list = JList(model)
    private val details = JEditorPane("text/html", "").apply { isEditable = false; isOpaque = false }
    private val cards = CardLayout()
    private val center = JPanel(cards)
    private val empty = JLabel("", JLabel.CENTER)
    private val direction = JComboBox(arrayOf("Keep the vibe", "Build up ↑", "Cool down ↓"))
    private val progress = JProgressBar().apply { isVisible = false; isStringPainted = false }
    private val stop = JButton("Stop").apply { isVisible = false }
    private val find = JButton("Find new songs like this")
    private val sync = JCheckBox("SYNC it for me", prefs.getBoolean("nextSync", true))
    private val load = JButton("Load on Deck $deckName").apply { isEnabled = false }
    private val songs: NextSongs?
    /** This tab's status line, shown whenever the tab is. */
    private var status = HINT

    init {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        val store = app.store
        val seed = after?.let { app.trackOn(it) }?.let(store::get)
        val waitingTrack = if (waiting) app.trackOn(deck)?.let(store::get) else null
        direction.selectedIndex = prefs.getInt("nextDirection", 0).coerceIn(0, 2)
        songs = seed?.let {
            NextSongs(store, app.suggester, it, app.mixer.decks[after!!].pitch,
                onDecks = (0..1).mapNotNull(app::trackOn).toSet(), waiting = waitingTrack,
                direction = Direction.entries[direction.selectedIndex]) { s -> SwingUtilities.invokeLater { show(s) } }
        }

        // Top: what the suggestions follow, and the direction.
        val header = JLabel(seed?.let {
            "<html>After <b>${LibraryDialog.esc(it.title)}</b> <span style='color:#9a9aa8'>" +
                LibraryDialog.esc(listOfNotNull(it.artist.ifEmpty { null },
                    if (it.bpm > 0) "%.0f BPM".format(it.bpm * (1 + app.mixer.decks[after!!].pitch)) else null,
                    songs?.seedKeyLabel()).joinToString(" · ")) + "</span></html>"
        } ?: "Nothing is playing yet")
        header.font = header.font.deriveFont(14f)
        val top = JPanel(BorderLayout(8, 0))
        top.add(header, BorderLayout.CENTER)
        top.add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { add(JLabel("Direction:")); add(direction) }, BorderLayout.EAST)
        top.add(JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createEmptyBorder(6, 0, 0, 0)
            add(progress, BorderLayout.CENTER); add(stop, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        add(top, BorderLayout.NORTH)

        // Middle: the list (or an empty-state message) and the reasons for the selected song.
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.fixedCellHeight = 54
        list.cellRenderer = Cell()
        empty.foreground = Color(0xB0, 0xB0, 0xBC)
        center.add(JScrollPane(list), "list")
        center.add(empty, "empty")
        add(JPanel(BorderLayout(6, 6)).apply {
            add(center, BorderLayout.CENTER)
            add(JScrollPane(details).apply { preferredSize = Dimension(100, 130); border = BorderFactory.createEmptyBorder() }, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)

        // Bottom: SYNC option, find new songs, load.
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(sync); add(find) }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { add(load) }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)

        list.addListSelectionListener { showDetails(list.selectedValue) }
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentShown(e: java.awt.event.ComponentEvent) = setStatus(status)
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) loadSelected() }
        })
        list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "pick")
        list.actionMap.put("pick", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = loadSelected()
        })
        load.addActionListener { loadSelected() }
        direction.addActionListener {
            prefs.putInt("nextDirection", direction.selectedIndex)
            songs?.setDirection(Direction.entries[direction.selectedIndex])
        }
        sync.addActionListener { prefs.putBoolean("nextSync", sync.isSelected) }
        stop.addActionListener { songs?.stop(); setStatus("Stopping after this song…") }
        find.isEnabled = songs != null
        find.addActionListener { songs?.findNew() }

        if (songs == null) {
            empty.text = "<html><div style='text-align:center;width:420px'>Load a song on a deck first.<br><br>" +
                "Then this tab suggests what to play after it: songs whose key, tempo and energy fit.</div></html>"
            cards.show(center, "empty")
            direction.isEnabled = false
        } else songs.start()
    }

    /** Call when the dialog closes. */
    fun close() = songs?.close()

    private fun show(s: NextSongs.State) {
        val keep = list.selectedValue?.s?.track?.id
        model.clear()
        s.waiting?.let { model.addElement(Item(it, onDeck = true, isNew = false)) }
        s.list.forEach { model.addElement(Item(it, onDeck = false, isNew = it.track.id in s.found)) }
        (0 until model.size()).firstOrNull { model[it].s.track.id == keep }?.let { list.selectedIndex = it }
        if (model.isEmpty) {
            empty.text = "<html><div style='text-align:center;width:420px'>" + when {
                s.checking != null -> "Checking your songs…"
                s.librarySize == 0 -> "No other songs on this computer yet.<br><br>Click <b>Find new songs like this</b> and MixDeck " +
                    "fetches a few that go with the playing song."
                else -> "You've played all your songs in the last few hours.<br><br>Click <b>Find new songs like this</b> for fresh ones."
            } + "</div></html>"
            cards.show(center, "empty")
        } else cards.show(center, "list")
        if (list.selectedIndex < 0 && !model.isEmpty) list.selectedIndex = 0

        val busy = s.checking != null || s.finding != null
        progress.isVisible = busy; stop.isVisible = busy
        val c = s.checking
        if (c != null) { progress.isIndeterminate = false; progress.maximum = c.total; progress.value = c.done }
        else progress.isIndeterminate = s.finding != null
        find.isEnabled = s.finding == null
        status = s.finding
            ?: c?.let { "Checking your songs for key and energy… ${it.done + 1} of ${it.total}: ${it.title}" }
            ?: s.note ?: HINT
        if (isVisible) setStatus(status)  // JTabbedPane hides the tabs that aren't selected
    }

    private fun showDetails(item: Item?) {
        load.isEnabled = item != null && !item.onDeck
        if (item == null) { details.text = ""; return }
        val s = item.s
        fun hex(c: Int) = "#%06x".format(c and 0xFFFFFF)
        val html = StringBuilder("<html><body style='font-family:sans-serif;font-size:12px;color:#e6e6ec'>")
        html.append("<b>${LibraryDialog.esc(s.track.title)}</b> &nbsp;<span style='color:#ffd54f'>${stars(s.stars)}</span>" +
            " &nbsp;<span style='color:#9a9aa8'>fits ${s.score.toInt()}/100</span>")
        if (item.onDeck) html.append(" &nbsp;<span style='color:#9a9aa8'>(already on deck $deckName)</span>")
        for (r in s.reasons) {
            val c = if (r.good) 0x00E676 else 0xFF8A65
            html.append("<br><span style='color:${hex(c)}'>${if (r.good) "✓" else "✗"}</span> ${LibraryDialog.esc(r.text)}")
        }
        details.text = html.append("</body></html>").toString()
        details.caretPosition = 0
    }

    private fun loadSelected() {
        val item = list.selectedValue ?: return
        if (item.onDeck) return
        val d = app.mixer.decks[deck]
        if (confirmPlaying && d.playing && JOptionPane.showConfirmDialog(this,
                "Deck $deckName is still playing (${Console.fmtTime((d.durationSec - d.positionSec) / (1 + d.pitch))} left).\n" +
                    "Stop it and load \"${item.s.track.title}\" now?", "Load on Deck $deckName",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
        pick(item.s.track.id, sync.isSelected)
    }

    /** One suggestion: stars · title, artist, summary · key move, tempo change, energy. */
    private inner class Cell : JPanel(BorderLayout(10, 0)), ListCellRenderer<Item> {
        private val starsL = JLabel().apply { preferredSize = Dimension(84, 20); foreground = Color(0xFF, 0xD5, 0x4F); font = font.deriveFont(15f) }
        private val text = JLabel()
        private val keyChip = JLabel("", JLabel.CENTER).apply {
            isOpaque = true; foreground = Color.BLACK; font = font.deriveFont(Font.BOLD, 12f)
            border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
        }
        private val tempo = JLabel("", JLabel.RIGHT).apply { preferredSize = Dimension(64, 20) }
        private val energy = JLabel("", JLabel.RIGHT).apply { preferredSize = Dimension(84, 20) }

        init {
            border = BorderFactory.createEmptyBorder(4, 10, 4, 10)
            add(starsL, BorderLayout.WEST)
            add(text, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 10, 8)).apply { isOpaque = false; add(keyChip); add(tempo); add(energy) }, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(l: JList<out Item>, item: Item, index: Int, selected: Boolean, focus: Boolean): Component {
            val s = item.s
            background = if (selected) l.selectionBackground else l.background
            starsL.text = stars(s.stars)
            val badge = when {
                item.onDeck -> "<span style='color:#1de9b6'>ON DECK $deckName NOW</span> &nbsp;"
                item.isNew -> "<span style='color:#00e676'>NEW</span> &nbsp;"
                else -> ""
            }
            text.text = "<html>$badge<b>${LibraryDialog.esc(s.track.title)}</b> &nbsp;<span style='color:#9a9aa8'>" +
                "${LibraryDialog.esc(s.track.artist)}</span><br><span style='color:#c8c8d0'>${LibraryDialog.esc(s.summary)}</span></html>"
            keyChip.text = KeyDisplay.moveLabel(s, showFrom = songs?.seedKeyLabel() != null)
            keyChip.background = Color(KeyDisplay.moveColor(s) ?: 0xFF5A5A66.toInt())
            val pct = s.pitchToSync * 100
            tempo.text = if (s.track.bpm > 0) "%+.1f%%".format(pct).replace('-', '−') else "no beat"
            tempo.foreground = when { abs(pct) >= 10 -> Color(0xFF, 0x52, 0x52); abs(pct) >= 6 -> Color(0xFF, 0xB3, 0x00); else -> Color(0xE6, 0xE6, 0xEC) }
            energy.text = energyText(songs?.seedTrack?.energy ?: Float.NaN, s.track.energy)
            return this
        }
    }

    companion object {
        const val HINT = "Best first. Double-click a song to load it. Keys are on the key wheel (Camelot): next-door keys mix smoothly."

        fun stars(n: Int) = "★".repeat(n.coerceIn(0, 5)) + "☆".repeat(5 - n.coerceIn(0, 5))

        /** "energy 7 ↑" against the playing song's energy. */
        fun energyText(now: Float, e: Float): String {
            if (e.isNaN()) return "energy ?"
            val arrow = when { now.isNaN() -> ""; e - now >= 0.5f -> " ↑"; now - e >= 0.5f -> " ↓"; else -> " →" }
            return "energy ${Math.round(e)}$arrow"
        }
    }
}
