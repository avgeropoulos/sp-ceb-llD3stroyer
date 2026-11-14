package com.mixdeck

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mixdeck.ui.Guide
import com.mixdeck.ui.Menus
import com.mixdeck.ui.Guide.green
import com.mixdeck.ui.Guide.yellow

/** Help & guide: a "your first mix" walkthrough and a key for every control. */
class HelpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(24)) }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Help & guide"; setTextColor(yellow); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "▶ Nelly tutorial"; isAllCaps = false; textSize = 14f; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF00E676.toInt()) }
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { setResult(RESULT_TUTORIAL); finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) })
        header.addView(Button(this).apply {
            text = "Show labels"; isAllCaps = false; textSize = 14f; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(yellow) }
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { setResult(RESULT_SHOW_KEY); finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) })
        header.addView(Button(this).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF2A2A31.toInt()) }
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(40)))
        col.addView(header)

        // Interactive tutorial.
        col.addView(heading("Try it hands-on", green))
        col.addView(row("▶", green, "Tap \"▶ Nelly tutorial\" (top right, or ☰ → Learn to mix) for a guided mix of Nelly's " +
            "\"Air Force Ones\" into \"Grillz\". It loads both songs, lights up each button you need, " +
            "and moves on when you've done each step.", round = true))

        // Your first mix.
        col.addView(heading("Your first mix in 8 steps", green))
        Guide.firstMix(computer = false).forEachIndexed { i, step ->
            val tip = step.startsWith("Tip")
            col.addView(row(if (tip) "★" else "${i + 1}", if (tip) yellow else green, step, round = true))
        }

        for (s in Guide.sections(computer = false)) {
            col.addView(heading(s.title, s.color))
            for (it in s.items) col.addView(row(it.chip, it.color, it.text))
        }

        col.addView(TextView(this).apply {
            text = Menus.guideFooter(BuildConfig.VERSION_NAME)
            setTextColor(0xFF77778A.toInt()); textSize = 12f; setPadding(0, dp(20), 0, 0)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0E0E11.toInt()); addView(col) })
    }

    private fun heading(t: String, color: Int) = TextView(this).apply {
        text = t; setTextColor(color); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(6))
    }

    /** One line of the key: a chip that looks like the control, then what it does. */
    private fun row(chip: String, color: Int, body: String, round: Boolean = false): LinearLayout {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
        r.addView(TextView(this).apply {
            text = chip; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(if (round) 16 else 7).toFloat()
                setColor(0xFF2A2A31.toInt()); setStroke(dp(2), color)
            }
            minWidth = dp(if (round) 32 else 104)
        }, LinearLayout.LayoutParams(if (round) dp(32) else dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(TextView(this).apply {
            text = body; setTextColor(0xFFE6E6EC.toInt()); textSize = 15f; setLineSpacing(0f, 1.15f)
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val RESULT_SHOW_KEY = 7
        const val RESULT_TUTORIAL = 8
    }
}
