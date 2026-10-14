package com.bouzouki

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.bouzouki.core.Dromos
import com.bouzouki.core.Instrument
import com.bouzouki.core.NoteNames

const val GREEK_BLUE = 0xFF0D5EAF.toInt()
const val NAVY = 0xFF0B1A2C.toInt()
const val WHITE = 0xFFFFFFFF.toInt()
private const val CHIP = 0xFF15304F.toInt()
private const val CHIP_EDGE = 0xFF2C4A6E.toInt()

/** The choices made on the setup screen, shared with the play screen through preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("bouzouki", Context.MODE_PRIVATE)

    var instrument: Instrument
        get() = Instrument.entries.getOrElse(prefs.getInt("instrument", 1)) { Instrument.TETRACHORDO }
        set(v) = prefs.edit().putInt("instrument", v.ordinal).apply()
    var dromos: Dromos
        get() = Dromos.entries.getOrElse(prefs.getInt("dromos", Dromos.HITZAZ.ordinal)) { Dromos.HITZAZ }
        set(v) = prefs.edit().putInt("dromos", v.ordinal).apply()
    var rootPc: Int
        get() = prefs.getInt("root", 2)
        set(v) = prefs.edit().putInt("root", v).apply()
    var names: NoteNames
        get() = NoteNames.entries.getOrElse(prefs.getInt("names", 0)) { NoteNames.WESTERN }
        set(v) = prefs.edit().putInt("names", v.ordinal).apply()
    var showAllNotes: Boolean
        get() = prefs.getBoolean("all", false)
        set(v) = prefs.edit().putBoolean("all", v).apply()
    var acrossTheNeck: Boolean
        get() = prefs.getBoolean("across", false)
        set(v) = prefs.edit().putBoolean("across", v).apply()
    var arpeggio: Boolean
        get() = prefs.getBoolean("arpeggio", false)
        set(v) = prefs.edit().putBoolean("arpeggio", v).apply()
    var course: Int
        get() = prefs.getInt("course", 0)
        set(v) = prefs.edit().putInt("course", v).apply()

    /** Neck zoom per screen ("play" or "song"); 0 means "whole neck". */
    fun zoom(screen: String, default: Float) = prefs.getFloat("zoom_$screen", default)
    fun setZoom(screen: String, z: Float) = prefs.edit().putFloat("zoom_$screen", z).apply()
}

/** Small view builders shared by both screens. */
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

    fun label(s: String) = TextView(activity).apply {
        text = s
        textSize = 12f
        setTextColor(0xFF9FB4CC.toInt())
        setPadding(px(4), px(6), 0, px(2))
    }

    fun chip(s: String, onClick: () -> Unit) = Button(activity).apply {
        text = s
        isAllCaps = false
        textSize = 14f
        minHeight = px(44)
        minimumHeight = px(44)
        minWidth = 0
        minimumWidth = 0
        setPadding(px(6), 0, px(6), 0)
        stateListAnimator = null
        setOnClickListener { onClick() }
        style(this, false)
    }

    fun style(b: Button, on: Boolean) {
        b.background = rounded(if (on) GREEK_BLUE else CHIP, if (on) WHITE else CHIP_EDGE)
        b.setTextColor(WHITE)
    }

    fun rounded(fill: Int = CHIP, stroke: Int = CHIP_EDGE) = GradientDrawable().apply {
        cornerRadius = 10 * dp
        setColor(fill)
        setStroke(px(1), stroke)
    }
}

/**
 * Sets up the neck's zoom for [screen] (remembered between visits, 0 = whole neck) and
 * returns a title-bar button that toggles between the whole neck and normal size.
 */
fun wireZoom(ui: Ui, settings: Settings, board: FretboardView, screen: String, defaultWhole: Boolean): Button {
    val saved = settings.zoom(screen, if (defaultWhole) 0f else 1f)
    if (saved <= 0f) board.wholeNeck = true else board.zoom = saved
    val button = ui.chip("") {}
    fun show() {
        button.text = if (board.wholeNeck) "⤡ Bigger" else "⤢ Whole neck"
        ui.style(button, board.wholeNeck)
    }
    button.setOnClickListener {
        if (board.wholeNeck) { board.wholeNeck = false; board.zoom = 1f; settings.setZoom(screen, 1f) }
        else { board.wholeNeck = true; settings.setZoom(screen, 0f); (board.parent as? View)?.scrollTo(0, 0) }
        show()
    }
    board.onZoomChanged = { z -> settings.setZoom(screen, z); show() }
    button.textSize = 12f
    show()
    return button
}

/** A strip of Greek key (meander) pattern. */
class MeanderView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE; style = Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density * 1.6f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(GREEK_BLUE)
        val h = height.toFloat()
        val u = h / 5 // one unit of the key
        val path = Path()
        var x = 0f
        path.moveTo(0f, h - u)
        while (x < width) {
            path.lineTo(x + 4 * u, h - u)
            path.lineTo(x + 4 * u, u)
            path.lineTo(x + u, u)
            path.lineTo(x + u, 3 * u)
            path.lineTo(x + 3 * u, 3 * u)
            path.lineTo(x + 3 * u, 2 * u)
            path.moveTo(x + 4 * u, h - u)
            path.lineTo(x + 5 * u, h - u)
            x += 5 * u
        }
        canvas.drawPath(path, paint)
    }
}
