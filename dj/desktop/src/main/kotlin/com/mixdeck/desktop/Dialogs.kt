package com.mixdeck.desktop

import com.mixdeck.Net
import com.mixdeck.ui.Guide
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/** A list of choices, like the phone's menus. Click one (or press Enter) to pick it; Esc closes. */
class MenuDialog(owner: java.awt.Frame, title: String, items: List<Pair<String, () -> Unit>>) : JDialog(owner, title, true) {
    init {
        val list = JList(items.map { it.first }.toTypedArray()).apply {
            fixedCellHeight = 38
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
            font = font.deriveFont(14f)
        }
        fun pick() {
            val i = list.selectedIndex
            if (i < 0) return
            dispose()
            SwingUtilities.invokeLater { items[i].second() }
        }
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseReleased(e: java.awt.event.MouseEvent) { pick() }
        })
        list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "pick")
        list.actionMap.put("pick", object : javax.swing.AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) = pick() })
        rootPane.registerKeyboardAction({ dispose() }, javax.swing.KeyStroke.getKeyStroke("ESCAPE"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
        contentPane.add(list)
        pack()
        setSize(maxOf(width, 460), height)
        setLocationRelativeTo(owner)
    }
}

/** Saved mixes in ~/Music/MixDeck. */
object MixFiles {
    fun list(): List<File> = Places.mixes.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("m4a", "wav", "mp3") }
        .orEmpty().sortedByDescending { it.lastModified() }

    /** Saves a recorded WAV as "[name].m4a" (falls back to WAV if encoding fails). Deletes [wav]. */
    fun save(wav: File, name: String, progress: (Float) -> Unit): File {
        val clean = clean(name)
        try {
            val out = unique(clean, "m4a")
            return try {
                Ffmpeg.wavToM4a(wav, out, progress); out
            } catch (e: Throwable) {
                out.delete()
                unique(clean, "wav").also { wav.copyTo(it) }
            }
        } finally { wav.delete() }
    }

    fun rename(f: File, name: String): Boolean = f.renameTo(unique(clean(name), f.extension))

    private fun clean(s: String) = s.trim().replace(Regex("""[\\/:*?"<>|]"""), "-").ifEmpty { "Mix" }

    private fun unique(name: String, ext: String): File {
        var f = File(Places.mixes, "$name.$ext"); var n = 2
        while (f.exists()) { f = File(Places.mixes, "$name ($n).$ext"); n++ }
        return f
    }
}

/** Your recorded mixes: play (in your music player), show in folder, rename, delete. */
class MixesDialog(private val app: App) : JDialog(app.frame, "My mixes", true) {
    private val model = DefaultListModel<Item>()
    private val list = JList(model).apply { fixedCellHeight = 46 }

    private class Item(val f: File) {
        private val label: String = run {
            val ms = Ffmpeg.durationMs(f)
            val bits = listOfNotNull(
                if (ms > 0) "%d:%02d".format(ms / 60000, ms / 1000 % 60) else null,
                "%.1f MB".format(f.length() / 1_048_576.0),
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastModified())),
            ).joinToString("  ·  ")
            "<html><b>${LibraryDialog.esc(f.nameWithoutExtension)}</b><br><span style='color:#9a9aa8'>$bits</span></html>"
        }
        override fun toString() = label
    }

    init {
        val p = JPanel(BorderLayout(8, 8)).apply { border = BorderFactory.createEmptyBorder(12, 12, 12, 12) }
        p.add(JLabel("<html>Saved in <b>${LibraryDialog.esc(Places.mixes.path)}</b>. Double-click a mix to play it.</html>"), BorderLayout.NORTH)
        p.add(JScrollPane(list), BorderLayout.CENTER)
        fun sel() = list.selectedValue?.f
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttons.add(JButton("Play").apply { addActionListener { sel()?.let(::openFile) } })
        buttons.add(JButton("Show in folder").apply { addActionListener { openFile(Places.mixes) } })
        buttons.add(JButton("Rename…").apply {
            addActionListener {
                val f = sel() ?: return@addActionListener
                val n = JOptionPane.showInputDialog(this@MixesDialog, "New name:", f.nameWithoutExtension) ?: return@addActionListener
                if (!MixFiles.rename(f, n)) app.error("Couldn't rename it")
                refresh()
            }
        })
        buttons.add(JButton("Delete").apply {
            addActionListener {
                val f = sel() ?: return@addActionListener
                if (JOptionPane.showConfirmDialog(this@MixesDialog, "Delete \"${f.nameWithoutExtension}\"? This can't be undone.",
                        "Delete", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) { f.delete(); refresh() }
            }
        })
        buttons.add(JButton("Close").apply { addActionListener { dispose() } })
        p.add(buttons, BorderLayout.SOUTH)
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { if (e.clickCount == 2) sel()?.let(::openFile) }
        })
        contentPane.add(p)
        refresh()
        size = Dimension(760, 520)
        setLocationRelativeTo(app.frame)
    }

    private fun refresh() {
        model.clear()
        MixFiles.list().forEach { model.addElement(Item(it)) }
    }
}

/** The full guide (same content as the phone), plus the keyboard shortcuts. */
class HelpDialog(private val app: App) : JDialog(app.frame, "How MixDeck works", true) {
    init {
        val html = StringBuilder("<html><body style='font-family:sans-serif;font-size:13px;color:#e6e6ec;background:#0e0e11;margin:12px'>")
        fun hex(c: Int) = "#%06x".format(c and 0xFFFFFF)
        fun heading(t: String, c: Int) = html.append("<h2 style='color:${hex(c)};margin-top:18px;margin-bottom:6px'>${LibraryDialog.esc(t)}</h2>")
        fun row(chip: String, c: Int, text: String) = html.append(
            "<tr><td valign='top' width='120'><div style='border:2px solid ${hex(c)};padding:4px;text-align:center;font-weight:bold'>" +
                "${LibraryDialog.esc(chip)}</div></td><td style='padding-left:12px'>${LibraryDialog.esc(text)}</td></tr>")

        heading("Try it hands-on", Guide.green)
        html.append("<p>Click <b>Start the Nelly tutorial</b> below (or ☰ → Tutorial) for a guided mix of Nelly's " +
            "\"Air Force Ones\" into \"Grillz\". It loads both songs, lights up each control you need, and moves on when you've done each step.</p>")
        heading("Keyboard shortcuts", Guide.yellow)
        html.append("<table cellpadding='4'>")
        for ((k, v) in shortcuts) row(k, Guide.yellow, v)
        html.append("</table><p>Mouse: drag knobs and faders up/down, or use the scroll wheel over them. Double-click a knob or fader to reset it.</p>")
        heading("Your first mix in 8 steps", Guide.green)
        html.append("<ol>")
        Guide.firstMix.filter { !it.startsWith("Tip") }.forEach { html.append("<li style='margin-bottom:4px'>${LibraryDialog.esc(it)}</li>") }
        html.append("</ol>")
        for (s in Guide.sections) {
            heading(s.title, s.color)
            html.append("<table cellpadding='4'>")
            for (it in s.items) row(it.chip, it.color, it.text)
            html.append("</table>")
        }
        html.append("<p style='color:#77778a'>MixDeck ${Version.name}. YouTube downloads are for personal use only.</p></body></html>")

        val pane = JEditorPane("text/html", html.toString()).apply { isEditable = false; caretPosition = 0 }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttons.add(JButton("▶ Start the Nelly tutorial").apply { addActionListener { dispose(); app.startTutorial() } })
        buttons.add(JButton("Show labels").apply { addActionListener { dispose(); app.console.helpMode = true } })
        buttons.add(JButton("Close").apply { addActionListener { dispose() } })
        contentPane.add(JScrollPane(pane), BorderLayout.CENTER)
        contentPane.add(buttons, BorderLayout.SOUTH)
        size = Dimension(900, 720)
        setLocationRelativeTo(app.frame)
        SwingUtilities.invokeLater { pane.caretPosition = 0 }
    }

    companion object {
        val shortcuts = listOf(
            "Q  W  E" to "Deck A: CUE (hold to preview), Play/Pause, SYNC",
            "1  2  3  4" to "Deck A hot cues",
            "R  T" to "Deck A: 4-beat loop on/off, effect on/off",
            "O  P  I" to "Deck B: CUE, Play/Pause, SYNC",
            "7  8  9  0" to "Deck B hot cues",
            "U  Y" to "Deck B: 4-beat loop on/off, effect on/off",
            "← →  ↓" to "Crossfader left / right, ↓ = centre",
            "Z X C V" to "Sampler: horn, siren, laser, drop",
            "N" to "Suggest the next song (what fits after the playing one)",
            "H  or  F1" to "Show / hide the button labels",
            "Esc" to "Close the labels or the tutorial",
        )
    }
}

/**
 * Checks GitHub for a newer build, like the phone app. On the computer it opens the download for
 * this kind of computer in the browser; install it over the old one.
 */
class DesktopUpdater(private val app: App) {
    private val base = "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/mixdeck-latest/"

    private fun assetFor(platform: String) = when {
        platform.startsWith("macosx-arm64") -> "MixDeck-mac-apple-silicon.dmg"
        platform.startsWith("macosx") -> "MixDeck-mac-intel.dmg"
        platform.startsWith("windows") -> "MixDeck-windows.zip"
        else -> "MixDeck-linux.tar.gz"
    }

    fun check(manual: Boolean) {
        thread(isDaemon = true, name = "update check") {
            val r = runCatching { JSONObject(Net.getText(base + "version.json?t=" + System.currentTimeMillis())) }
            SwingUtilities.invokeLater {
                val o = r.getOrNull()
                when {
                    o == null -> if (manual) app.error("Couldn't check for updates: ${r.exceptionOrNull()?.message}")
                    o.optInt("versionCode") > Version.code -> offer(o.optString("versionName"), o.optString("notes"))
                    manual -> app.info("Up to date", "MixDeck is up to date (version ${Version.name}).")
                }
            }
        }
    }

    private fun offer(name: String, notes: String) {
        val choice = JOptionPane.showOptionDialog(app.frame,
            "MixDeck $name is out (you have ${Version.name}).\n$notes\n\nDownload it now? Then quit MixDeck and install it over this one.\n" +
                "Your songs, mixes and settings are kept.",
            "Update available", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
            arrayOf("Download update", "Later"), "Download update")
        if (choice == 0) browse(base + assetFor(Version.platform))
    }
}
