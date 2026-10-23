package com.bouzoukihero

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bouzoukihero.core.Synth

/** Shared look for the menu screens: dark blue with gold, rounded cards and buttons. */
object Ui {
    const val BG = 0xFF0B1530.toInt()
    const val CARD = 0xFF19254F.toInt()
    const val ACCENT = 0xFF2F6FD6.toInt()
    const val GOLD = 0xFFFFD23F.toInt()
    const val SUB = 0xFFB9C6EE.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val RED = 0xFF8A2F3A.toInt()

    fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun Activity.rounded(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    fun Activity.label(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun Activity.button(s: String, color: Int, onClick: () -> Unit) = label(s, 16f, WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(color, 26)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(0, dp(12), 0, 0) }
    }

    /** A scrolling dark page; returns the column to add views to. */
    fun Activity.page(): LinearLayout {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        scroll.addView(col)
        setContentView(scroll)
        return col
    }

    private var playing: AudioTrack? = null

    /** Plays a short clip once (for trying out recorded notes). */
    fun play(pcm: FloatArray) {
        playing?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        val data = ShortArray(pcm.size) { (pcm[it].coerceIn(-1f, 1f) * 32000f).toInt().toShort() }
        if (data.isEmpty()) return
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Synth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(data, 0, data.size)
            t.play()
            playing = t
        } catch (_: Exception) {
        }
    }
}
