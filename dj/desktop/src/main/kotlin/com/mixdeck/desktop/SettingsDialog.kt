package com.mixdeck.desktop

import com.mixdeck.engine.AutoMix
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.XfadeCurve
import com.mixdeck.ui.Menus.Settings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSlider
import javax.swing.JToggleButton
import javax.swing.KeyStroke

/** ☰ → Settings…: the [SettingsPanel] in a window. */
class SettingsDialog(app: App) : JDialog(app.frame, Settings.TITLE, true) {
    init {
        val panel = SettingsPanel(app.mixer, saved = { app.settingsChanged() }, checkUpdates = { app.updater.check(manual = true) },
            openMixes = { openFile(Places.mixes) }, done = { dispose() })
        contentPane.add(panel)
        rootPane.defaultButton = panel.done
        rootPane.registerKeyboardAction({ dispose() }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW)
        pack()
        size = Dimension(maxOf(width, 560), maxOf(height, 400))
        setLocationRelativeTo(app.frame)
    }
}

/**
 * Volumes, crossfader curve, Quantize, MIX length and split cue in one place. Every change applies
 * at once (under the mixer lock) and is [saved]; Done just closes it.
 */
class SettingsPanel(
    private val mixer: Mixer, private val saved: () -> Unit, checkUpdates: () -> Unit, openMixes: () -> Unit, done: () -> Unit,
) : JPanel(BorderLayout()) {
    private val grey = Color(0x9A9AA8)
    val done = JButton(Settings.DONE).apply { addActionListener { done() } }

    /** Changes a setting under the mixer lock, then saves the settings. */
    private fun change(f: Mixer.() -> Unit) {
        synchronized(mixer.lock) { mixer.f() }
        saved()
    }

    init {
        val cols = JPanel(GridLayout(1, 2, 24, 0))
        cols.add(column(Settings.SOUND,
            slider(Settings.MASTER, mixer.master) { v -> change { master = v } },
            slider(Settings.SAMPLER, mixer.sampler.volume) { v -> change { sampler.volume = v } },
        ))
        cols.add(column(Settings.MIXING, *mixing().toTypedArray()))

        val footer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        footer.add(row(JLabel("MixDeck ${Version.name}").bold(), JButton(Settings.CHECK).apply { addActionListener { checkUpdates() } }))
        footer.add(row(small(Settings.songsFolder(Places.data.path))))
        footer.add(row(small(Settings.mixesFolder(Places.mixes.path)), JButton(Settings.OPEN_MIXES).apply { addActionListener { openMixes() } }))
        footer.add(row(small(Settings.PERSONAL_USE)))

        val bottom = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0x3A3A44)),
                BorderFactory.createEmptyBorder(8, 0, 0, 0))
            add(footer, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(this@SettingsPanel.done) }, BorderLayout.SOUTH)
        }
        border = BorderFactory.createEmptyBorder(16, 18, 12, 18)
        add(cols, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply { border = BorderFactory.createEmptyBorder(12, 0, 0, 0); add(bottom) }, BorderLayout.SOUTH)
    }

    private fun mixing(): List<JComponent> {
        val smooth = JRadioButton(Settings.SMOOTH, mixer.curve == XfadeCurve.SMOOTH)
        val sharp = JRadioButton(Settings.SHARP, mixer.curve == XfadeCurve.SCRATCH)
        ButtonGroup().apply { add(smooth); add(sharp) }
        smooth.addActionListener { change { curve = XfadeCurve.SMOOTH } }
        sharp.addActionListener { change { curve = XfadeCurve.SCRATCH } }

        val quantize = JCheckBox(wrapped(Settings.QUANTIZE), mixer.quantize).apply {
            addActionListener { val on = isSelected; change { quantize = on } }
        }

        val caption = small(Settings.mixCaption(mixer.autoMix.bars))
        val group = ButtonGroup()
        val lengths = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        for (bars in AutoMix.LENGTHS) {
            val b = JToggleButton(Settings.barsLabel(bars), mixer.autoMix.bars == bars)
            group.add(b)
            b.addActionListener { change { autoMix.bars = bars }; caption.text = Settings.mixCaption(bars) }
            lengths.add(b)
        }

        val split = JCheckBox(wrapped(Settings.SPLIT_CUE), mixer.splitCue).apply {
            addActionListener { val on = isSelected; change { splitCue = on } }
        }
        return listOf(
            label(Settings.CROSSFADER), indent(smooth), indent(sharp), gap(),
            quantize, gap(),
            label(Settings.MIX_LENGTH), lengths, indent(caption), gap(),
            split, indent(small(Settings.SPLIT_CUE_NOTE).apply { text = wrapped(text) }),
        )
    }

    private fun slider(title: String, value: Float, set: (Float) -> Unit): JComponent {
        val pct = JLabel(Settings.percent(value))
        val s = JSlider(0, 100, (value * 100).toInt()).apply {
            addChangeListener { pct.text = Settings.percent(this.value / 100f); set(this.value / 100f) }
        }
        return JPanel(BorderLayout(8, 2)).apply {
            add(JLabel(title), BorderLayout.NORTH)
            add(s, BorderLayout.CENTER)
            add(pct.apply { preferredSize = Dimension(44, preferredSize.height) }, BorderLayout.EAST)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            border = BorderFactory.createEmptyBorder(0, 0, 14, 0)
        }
    }

    private fun column(title: String, vararg parts: JComponent) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JLabel(title).apply { font = font.deriveFont(Font.BOLD, font.size2D + 3f); border = BorderFactory.createEmptyBorder(0, 0, 10, 0) })
        for (p in parts) { p.alignmentX = Component.LEFT_ALIGNMENT; add(p) }
        add(Box.createVerticalGlue())
    }

    /** Long texts wrap inside their column. */
    private fun wrapped(s: String) = "<html><div style='width:220px'>${LibraryDialog.esc(s)}</div></html>"
    private fun label(s: String) = JLabel(s).bold()
    private fun JLabel.bold() = apply { font = font.deriveFont(Font.BOLD) }
    private fun small(s: String) = JLabel(s).apply { foreground = grey; font = font.deriveFont(font.size2D - 1f) }
    private fun indent(c: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        border = BorderFactory.createEmptyBorder(0, 4, 0, 0); add(c)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
    private fun gap() = Box.createVerticalStrut(10) as JComponent
    private fun row(vararg c: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        for (x in c) add(x)
    }
}
