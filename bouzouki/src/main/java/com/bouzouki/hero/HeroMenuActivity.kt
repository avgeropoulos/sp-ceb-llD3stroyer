package com.bouzouki.hero

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bouzouki.SongActivity
import com.bouzouki.SongJobs
import com.bouzoukihero.core.Difficulty
import com.bouzoukihero.core.Tunes

/**
 * Bouzouki Hero's song list: the built-in tunes and every YouTube song prepared in
 * Μπουζούκι's 🎵 Play along screen, whose tab becomes the notes to play.
 */
class HeroMenuActivity : Activity() {
    private lateinit var col: LinearLayout
    private val prefs by lazy { getSharedPreferences("hero", MODE_PRIVATE) }

    private var difficulty: Difficulty
        get() = Difficulty.entries.firstOrNull { it.name == prefs.getString("difficulty", null) } ?: Difficulty.EASY
        set(v) { prefs.edit().putString("difficulty", v.name).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        scroll.addView(col)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        col.removeAllViews()
        col.addView(text("BOUZOUKI HERO", 32f, GOLD, bold = true).apply { gravity = Gravity.CENTER })
        col.addView(text("Tap the notes as they reach the bottom. The buttons follow the tab. Opa!", 14f, SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(14))
        })

        header("Difficulty")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (d in Difficulty.entries) {
            val sel = d == difficulty
            row.addView(text(d.label, 14f, WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(if (sel) ACCENT else CARD, 22, if (sel) GOLD else 0)
                setOnClickListener { difficulty = d; build() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        col.addView(row)
        col.addView(text(describe(difficulty), 12f, SUB).apply { setPadding(dp(4), dp(8), dp(4), 0) })

        header("Your YouTube songs")
        val songs = SongJobs.recent(this).filter { !it.id.startsWith("file-") }
        if (songs.isEmpty()) {
            col.addView(text(
                "Prepare a song in 🎵 Play along with a YouTube song: Μπουζούκι works out its bouzouki tab, " +
                    "and it shows up here to play.", 13f, SUB,
            ).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        }
        val d = difficulty
        for (s in songs) {
            card(s.title.ifBlank { "YouTube song" }, scoreLine("s:${s.id}", d)) {
                startActivity(HeroActivity.intent(this, HeroActivity.KIND_SONG, s.id, d))
            }
        }
        col.addView(button("🎵  Prepare a YouTube song", ACCENT) {
            startActivity(Intent(this, SongActivity::class.java))
        })

        header("Built-in songs")
        for (t in Tunes.all) {
            card(t.title, t.style + "\n" + scoreLine("b:${t.id}", d)) {
                startActivity(HeroActivity.intent(this, HeroActivity.KIND_BUILTIN, t.id, d))
            }
        }
    }

    private fun describe(d: Difficulty) = when (d) {
        Difficulty.EASY -> "3 buttons, slower notes, only the main notes of the tune."
        Difficulty.MEDIUM -> "4 buttons and more of the melody."
        Difficulty.HARD -> "5 buttons (open string, fingers 1-4), fast runs included."
        Difficulty.EXPERT -> "5 buttons, every note, tight timing."
    }

    private fun scoreLine(key: String, d: Difficulty): String {
        val best = HeroActivity.best(this, key, d)
        if (best == 0) return "Not played on ${d.label} yet"
        val stars = HeroActivity.stars(this, key, d)
        return "★".repeat(stars) + "☆".repeat(5 - stars) + "   Best %,d".format(best)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 14, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun header(s: String) {
        col.addView(text(s.uppercase(), 13f, SUB, bold = true).apply {
            letterSpacing = 0.12f
            setPadding(dp(4), dp(20), 0, dp(8))
        })
    }

    private fun card(title: String, line: String, onClick: () -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        c.addView(text(title, 17f, WHITE, bold = true))
        c.addView(text(line, 13f, GOLD).apply { setPadding(0, dp(4), 0, 0) })
        col.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(10))
        })
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = text(label, 16f, WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(color, 26)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(0, dp(6), 0, 0) }
    }

    private companion object {
        const val BG = 0xFF0B1530.toInt()
        const val CARD = 0xFF19254F.toInt()
        const val ACCENT = 0xFF2F6FD6.toInt()
        const val GOLD = 0xFFFFD23F.toInt()
        const val SUB = 0xFFB9C6EE.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
