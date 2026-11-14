package com.mixdeck

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.mixdeck.engine.AutoMix
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.XfadeCurve
import com.mixdeck.ui.Menus.Settings

/**
 * ☰ → Settings…: volumes, crossfader curve, Quantize, MIX length and split cue in one place, in
 * two columns. Every change applies at once (under the mixer lock) and is [saved]; Done closes it.
 */
object SettingsPanel {
    fun show(a: Activity, mixer: Mixer, saved: () -> Unit, checkUpdates: () -> Unit): AlertDialog {
        val view = build(a, mixer, saved, checkUpdates)
        return AlertDialog.Builder(a).setTitle(Settings.TITLE).setView(view).setPositiveButton(Settings.DONE, null).show()
    }

    /** The panel's view (also used to draw it in tests). */
    fun build(a: Activity, mixer: Mixer, saved: () -> Unit, checkUpdates: () -> Unit): View {
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        val grey = 0xFF9A9AA8.toInt()
        val teal = 0xFF1DE9B6.toInt()
        fun change(f: Mixer.() -> Unit) { synchronized(mixer.lock) { mixer.f() }; saved() }
        fun heading(s: String) = TextView(a).apply {
            text = s; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(2))
        }
        fun label(s: String) = TextView(a).apply { text = s; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, 0) }
        fun small(s: String) = TextView(a).apply { text = s; textSize = 13f; setTextColor(grey) }
        fun column() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        fun slider(title: String, value: Float, set: (Float) -> Unit): View {
            val pct = TextView(a).apply { text = Settings.percent(value); textSize = 14f; minWidth = dp(48); gravity = Gravity.END }
            val bar = SeekBar(a).apply {
                max = 100; progress = (value * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        pct.text = Settings.percent(p / 100f)
                        if (fromUser) set(p / 100f)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            return column().apply {
                addView(label(title))
                addView(LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(pct)
                })
            }
        }

        val sound = column().apply {
            addView(heading(Settings.SOUND))
            addView(slider(Settings.MASTER, mixer.master) { v -> change { master = v } })
            addView(slider(Settings.SAMPLER, mixer.sampler.volume) { v -> change { sampler.volume = v } })
        }

        val mixing = column().apply {
            addView(heading(Settings.MIXING))
            addView(label(Settings.CROSSFADER))
            addView(RadioGroup(a).apply {
                val smooth = RadioButton(a).apply { id = View.generateViewId(); text = Settings.SMOOTH }
                val sharp = RadioButton(a).apply { id = View.generateViewId(); text = Settings.SHARP }
                addView(smooth); addView(sharp)
                check(if (mixer.curve == XfadeCurve.SCRATCH) sharp.id else smooth.id)
                setOnCheckedChangeListener { _, id -> val c = if (id == sharp.id) XfadeCurve.SCRATCH else XfadeCurve.SMOOTH; change { curve = c } }
            })
            addView(Switch(a).apply {
                text = Settings.QUANTIZE; textSize = 14f; isChecked = mixer.quantize; setPadding(0, dp(10), 0, 0)
                setOnCheckedChangeListener { _, on -> change { quantize = on } }
            })
            addView(label(Settings.MIX_LENGTH))
            val caption = small(Settings.mixCaption(mixer.autoMix.bars))
            // 4 / 8 / 16 / 32 as a segmented row (just the numbers: the caption says "bars"), so it fits the column.
            val chips = ArrayList<Pair<Int, TextView>>()
            fun paint() = chips.forEach { (bars, v) ->
                val on = bars == mixer.autoMix.bars
                v.setTextColor(if (on) Color.BLACK else Color.WHITE)
                v.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat(); setColor(if (on) teal else 0xFF2A2A31.toInt()); setStroke(dp(1), if (on) teal else grey)
                }
            }
            addView(LinearLayout(a).apply {
                setPadding(0, dp(6), 0, dp(4))
                for (bars in AutoMix.LENGTHS) {
                    val v = TextView(a).apply {
                        text = "$bars"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                        setOnClickListener { change { autoMix.bars = bars }; caption.text = Settings.mixCaption(bars); paint() }
                    }
                    chips += bars to v
                    addView(v, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (bars != AutoMix.LENGTHS.last()) marginEnd = dp(6) })
                }
            })
            paint()
            addView(caption)
            addView(Switch(a).apply {
                text = Settings.SPLIT_CUE; textSize = 14f; isChecked = mixer.splitCue; setPadding(0, dp(14), 0, 0)
                setOnCheckedChangeListener { _, on -> change { splitCue = on } }
            })
            addView(small(Settings.SPLIT_CUE_NOTE))
        }

        // The footer goes under the shorter Sound column, so the panel needs less scrolling in landscape.
        val footer = column().apply {
            setPadding(0, dp(20), 0, 0)
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(a).apply { text = "MixDeck ${BuildConfig.VERSION_NAME}"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD })
                addView(Button(a).apply { text = Settings.CHECK; isAllCaps = false; setOnClickListener { checkUpdates() } },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
            })
            addView(small(Settings.PERSONAL_USE))
        }
        sound.addView(footer)
        val body = LinearLayout(a).apply {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(sound, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(24) })
            addView(mixing, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return ScrollView(a).apply { addView(body) }
    }
}
