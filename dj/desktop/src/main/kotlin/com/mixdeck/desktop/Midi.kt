package com.mixdeck.desktop

import com.mixdeck.midi.MidiAction
import com.mixdeck.midi.MidiParser
import com.mixdeck.midi.MidiRouter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Sequencer
import javax.sound.midi.Synthesizer
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Listens to every MIDI controller plugged into the computer and hands what they send to the
 * [router]. Looks for new and unplugged controllers every few seconds, so nothing has to be
 * chosen: plug it in and it works. [plugged] is told a new controller's name.
 */
class DesktopMidi(private val router: MidiRouter, private val plugged: (String) -> Unit) {
    private val open = HashMap<String, MidiDevice>()

    /** The controllers connected now. */
    val names: List<String> get() = synchronized(open) { open.keys.sorted() }

    fun start() {
        Thread({
            while (true) {
                runCatching { scan() }
                Thread.sleep(3000)
            }
        }, "midi-scan").apply { isDaemon = true }.start()
    }

    private fun scan() {
        val seen = HashSet<String>()
        for (info in MidiSystem.getMidiDeviceInfo()) {
            val dev = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: continue
            // Only things that send: not the computer's own synth, sequencer or loop-back port.
            if (dev is Sequencer || dev is Synthesizer || dev.maxTransmitters == 0) continue
            val name = info.name.substringBefore(" [").trim().ifEmpty { info.description }
            if (name.contains("Through", ignoreCase = true)) continue
            seen += name
            if (synchronized(open) { name in open }) continue
            runCatching {
                if (!dev.isOpen) dev.open()
                val parser = MidiParser(router::handle)
                dev.transmitter.receiver = object : Receiver {
                    override fun send(m: MidiMessage, timeStamp: Long) { runCatching { parser.feed(m.message, 0, m.length) } }
                    override fun close() {}
                }
                synchronized(open) { open[name] = dev }
                plugged(name)
            }
        }
        val gone = synchronized(open) { open.keys.filter { it !in seen } }
        for (name in gone) {
            val dev = synchronized(open) { open.remove(name) }
            runCatching { dev?.close() }
            router.releaseAll()
        }
    }
}

/**
 * ☰ → MIDI controller…: link the controller's knobs, faders and buttons to the console. Click a
 * row, move the control; the list moves on to the next row so a whole controller is quick to do.
 */
class MidiDialog(private val app: App) : JDialog(app.frame, MidiRouter.TITLE, true) {
    private val router = app.midi
    private val model = DefaultListModel<String>()
    private val list = JList(model)
    private val status = JLabel(" ")
    private val devices = JLabel(" ")
    private val grey = Color(0x9A9AA8)

    init {
        fill()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.fixedCellHeight = 26
        list.addListSelectionListener { if (!it.valueIsAdjusting) arm() }

        val remove = JButton(MidiRouter.CLEAR_ONE).apply {
            addActionListener {
                val i = list.selectedIndex
                if (i >= 0) { router.map.unbind(MidiAction.ALL[i]); app.saveMidi(); fill(); arm() }
            }
        }
        val removeAll = JButton(MidiRouter.CLEAR_ALL).apply {
            addActionListener {
                if (JOptionPane.showConfirmDialog(this@MidiDialog, MidiRouter.CLEAR_ASK, MidiRouter.TITLE, JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    router.map.clear(); app.saveMidi(); fill()
                }
            }
        }
        val done = JButton("Done").apply { addActionListener { dispose() } }

        val top = JPanel(BorderLayout(0, 6)).apply {
            add(devices.apply { font = font.deriveFont(java.awt.Font.BOLD) }, BorderLayout.NORTH)
            add(JLabel("<html><div style='width:350px'>${LibraryDialog.esc(MidiRouter.HOW)}</div></html>").apply { foreground = grey }, BorderLayout.CENTER)
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(status, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 6)).apply { add(remove); add(javax.swing.Box.createHorizontalStrut(8)); add(removeAll) }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 6)).apply { add(done) }, BorderLayout.EAST)
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
        }
        (contentPane as JComponent).border = BorderFactory.createEmptyBorder(14, 16, 10, 16)
        contentPane.add(top, BorderLayout.NORTH)
        contentPane.add(JScrollPane(list), BorderLayout.CENTER)
        contentPane.add(bottom, BorderLayout.SOUTH)
        rootPane.registerKeyboardAction({ dispose() }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW)

        router.onLearned = { b ->
            SwingUtilities.invokeLater {
                app.saveMidi()
                fill()   // the control may have been taken from another row
                status.text = MidiRouter.learned(b)
                val i = MidiAction.ALL.indexOf(b.action)
                if (i + 1 < model.size()) { list.selectedIndex = i + 1; list.ensureIndexIsVisible(i + 1) }
            }
        }
        val tick = Timer(700) { showDevices() }.apply { start() }
        showDevices()
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent) { tick.stop(); router.learning = null; router.onLearned = null }
        })
        defaultCloseOperation = DISPOSE_ON_CLOSE
        size = Dimension(520, 620)
        setLocationRelativeTo(app.frame)
    }

    private fun row(i: Int) = MidiAction.ALL[i].let { MidiRouter.row(it, router.map.controlFor(it)?.control) }

    private fun fill() {
        val keep = list.selectedIndex
        model.clear()
        for (i in MidiAction.ALL.indices) model.addElement(row(i))
        if (keep >= 0) list.selectedIndex = keep
    }

    /** The selected row waits for its control. */
    private fun arm() {
        val a = list.selectedIndex.takeIf { it >= 0 }?.let { MidiAction.ALL[it] }
        router.learning = a
        status.text = a?.let { MidiRouter.waiting(it) } ?: " "
    }

    private fun showDevices() {
        val names = app.midiIn.names
        devices.text = if (names.isEmpty()) MidiRouter.NONE else MidiRouter.connected(names)
    }
}
