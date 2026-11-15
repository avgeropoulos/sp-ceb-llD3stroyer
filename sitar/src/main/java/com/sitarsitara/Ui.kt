package com.sitarsitara

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.sitarsitara.core.Script
import com.sitarsitara.core.Taal

const val NIGHT = 0xFF1C0A10.toInt()
const val MAROON = 0xFF7A1E2C.toInt()
const val SAFFRON = 0xFFF29A1F.toInt()
const val MARIGOLD = 0xFFFFC24A.toInt()
const val PEACOCK = 0xFF27B3A8.toInt()
const val WHITE = 0xFFFFFFFF.toInt()
const val SOFT = 0xFFE9D3C3.toInt()
const val MUTED = 0xFFB59A8E.toInt()
const val CARD = 0xFF33141E.toInt()
private const val CARD_EDGE = 0xFF5A2A36.toInt()

/** What the player chose, kept between visits: the script, and per song its taal and where Sa is. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("sitar", Context.MODE_PRIVATE)

    var script: Script
        get() = if (prefs.getString("script", null) == Script.DEVANAGARI.name) Script.DEVANAGARI else Script.LATIN
        set(v) = prefs.edit().putString("script", v.name).apply()

    /** The taal song [id] is laid out in (the last one chosen for any song, for a song not set yet). */
    fun taal(id: String): Taal = Taal.byName(prefs.getString("taal_$id", null) ?: prefs.getString("taal_last", null))
    fun setTaal(id: String, t: Taal) = prefs.edit().putString("taal_$id", t.name).putString("taal_last", t.name).apply()

    /** Sa of song [id] as a pitch class the player set, or null: worked out from the notes. */
    fun sa(id: String): Int? = prefs.getInt("sa_$id", -1).takeIf { it in 0..11 }
    fun setSa(id: String, pc: Int?) = prefs.edit().apply { if (pc == null) remove("sa_$id") else putInt("sa_$id", pc) }.apply()

    fun forgetSong(id: String) = prefs.edit().remove("taal_$id").remove("sa_$id").remove("song_sync_$id").apply()
}

/**
 * Keeps a screen clear of the status and navigation bars, which Android 15 draws over every app: the screen's
 * first view (its title bar) grows by the status bar's height with its colour behind it. Call after setContentView.
 */
fun fitSystemBars(activity: Activity, root: ViewGroup) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = Color.TRANSPARENT
    }
    val top = root.getChildAt(0) ?: return
    val topPad = top.paddingTop
    val left = root.paddingLeft
    val right = root.paddingRight
    val bottom = root.paddingBottom
    root.setOnApplyWindowInsetsListener { _, insets ->
        val (l, t, r, b) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        top.setPadding(top.paddingLeft, topPad + t, top.paddingRight, top.paddingBottom)
        root.setPadding(left + l, root.paddingTop, right + r, bottom + b)
        insets
    }
    root.requestApplyInsets()
}

/** Small view builders shared by the screens. */
class Ui(private val activity: Activity) {
    val dp = activity.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(3), 0, px(3))
    }

    fun weighted(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply {
        marginStart = px(3); marginEnd = px(3)
    }

    fun wide(top: Int = 8) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = px(top)
    }

    fun label(s: String) = TextView(activity).apply {
        text = s
        textSize = 12f
        setTextColor(MUTED)
        setPadding(px(4), px(10), 0, px(3))
    }

    fun text(s: String, size: Float = 15f, color: Int = SOFT) = TextView(activity).apply {
        text = s
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.2f)
    }

    fun chip(s: String, onClick: () -> Unit) = Button(activity).apply {
        text = s
        isAllCaps = false
        textSize = 14f
        minHeight = px(44)
        minimumHeight = px(44)
        minWidth = 0
        minimumWidth = 0
        setPadding(px(8), 0, px(8), 0)
        stateListAnimator = null
        setOnClickListener { onClick() }
        style(this, false)
    }

    /** A chip that stands out (the main action, or the one chosen). */
    fun style(b: Button, on: Boolean) {
        b.background = rounded(if (on) SAFFRON else CARD, if (on) MARIGOLD else CARD_EDGE)
        b.setTextColor(if (on) 0xFF2A1205.toInt() else WHITE)
    }

    fun rounded(fill: Int = CARD, stroke: Int = CARD_EDGE) = GradientDrawable().apply {
        cornerRadius = 12 * dp
        setColor(fill)
        setStroke(px(1), stroke)
    }

    /** The title bar: the app's name in English and Devanagari, and a line under it. */
    fun titleBar(title: String, sub: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(MAROON)
        setPadding(px(16), px(14), px(16), px(10))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(TextView(activity).apply {
                text = title
                textSize = 28f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                setTextColor(WHITE)
            })
            addView(TextView(activity).apply {
                text = "  सितार सितारा"
                textSize = 18f
                setTextColor(MARIGOLD)
            })
        })
        addView(TextView(activity).apply {
            text = sub
            textSize = 13f
            setTextColor(0xFFF3D9C6.toInt())
        })
    }
}

/** A string of marigolds under the title bar, the way a doorway is garlanded (a toran). */
class ToranView(context: Context) : View(context) {
    private val flower = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SAFFRON }
    private val centre = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARIGOLD }
    private val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3E8E41.toInt() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(MAROON)
        val h = height.toFloat()
        val r = h * 0.36f
        val step = r * 2.6f
        var x = r
        var i = 0
        while (x < width + r) {
            if (i % 4 == 3) {
                canvas.drawCircle(x, h * 0.5f, r * 0.55f, leaf)
            } else {
                canvas.drawCircle(x, h * 0.5f, r, flower)
                canvas.drawCircle(x, h * 0.5f, r * 0.45f, centre)
            }
            x += step
            i++
        }
    }
}
