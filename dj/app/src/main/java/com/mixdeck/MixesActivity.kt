package com.mixdeck

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/** Your recorded mixes: play them back, share, rename or delete. */
class MixesActivity : Activity() {
    private lateinit var store: MixStore
    private val mixes = ArrayList<Mix>()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
    private lateinit var playBtn: Button
    private lateinit var nowPlaying: TextView
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private var player: MediaPlayer? = null
    private var current: Mix? = null
    private val main = Handler(Looper.getMainLooper())
    private val accent = 0xFFFF1744.toInt()

    private val tick = object : Runnable {
        override fun run() {
            player?.let { p ->
                runCatching {
                    if (!seekDragging) seek.progress = p.currentPosition
                    time.text = "${fmt(p.currentPosition.toLong())} / ${fmt(p.duration.toLong())}"
                }
            }
            main.postDelayed(this, 250)
        }
    }
    private var seekDragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MixStore(this)
        window.decorView.setBackgroundColor(0xFF0E0E11.toInt())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "My mixes"; setTextColor(accent); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("✕", false) { finish() }, LinearLayout.LayoutParams(dp(48), dp(42)))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Saved in Music/MixDeck on your phone, so music players and file apps can see them too. " +
                "Tap a mix to play it, hold it to share, rename or delete."
            setTextColor(0xFF9A9AA8.toInt()); textSize = 13f; setPadding(0, dp(2), 0, dp(6))
        })

        empty = TextView(this).apply {
            text = "No mixes yet.\n\nOn the DJ screen, tap REC to start recording, play your set, then tap REC again to save it."
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setPadding(dp(20), dp(30), dp(20), dp(30))
        }
        root.addView(empty)

        adapter = object : BaseAdapter() {
            override fun getCount() = mixes.size
            override fun getItem(p: Int) = mixes[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val v = (convert as? LinearLayout) ?: LinearLayout(this@MixesActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
                    addView(TextView(context).apply { setTextColor(0xFF9A9AA8.toInt()); textSize = 13f })
                }
                val m = mixes[p]
                val playing = current?.uri == m.uri
                (v.getChildAt(0) as TextView).apply {
                    text = (if (playing) "▶  " else "") + m.name
                    setTextColor(if (playing) accent else Color.WHITE)
                }
                (v.getChildAt(1) as TextView).text = listOfNotNull(
                    if (m.durationMs > 0) fmt(m.durationMs) else null,
                    "%.1f MB".format(m.size / 1_048_576.0),
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.dateSec * 1000)),
                ).joinToString("  ·  ")
                return v
            }
        }
        val list = ListView(this).apply {
            adapter = this@MixesActivity.adapter
            divider = GradientDrawable().apply { setColor(0xFF24242B.toInt()) }; dividerHeight = 1
            setOnItemClickListener { _, _, pos, _ -> play(mixes[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> actions(mixes[pos]); true }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Player bar.
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF1A1A1F.toInt()) }
        }
        playBtn = button("▶", true) { togglePlay() }
        bar.addView(playBtn, LinearLayout.LayoutParams(dp(56), dp(44)))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), 0) }
        nowPlaying = TextView(this).apply { text = "Nothing playing"; setTextColor(Color.WHITE); textSize = 14f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        seek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar) { seekDragging = true }
            override fun onStopTrackingTouch(s: SeekBar) { seekDragging = false; player?.seekTo(s.progress) }
        })
        mid.addView(nowPlaying); mid.addView(seek)
        bar.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        time = TextView(this).apply { setTextColor(0xFF9A9AA8.toInt()); textSize = 13f; text = "" }
        bar.addView(time)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        setContentView(root)
        refresh()
        main.post(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(tick)
        player?.release(); player = null
    }

    private fun refresh() {
        mixes.clear()
        mixes.addAll(runCatching { store.list() }.getOrDefault(emptyList()))
        adapter.notifyDataSetChanged()
        empty.visibility = if (mixes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun play(m: Mix) {
        player?.release()
        current = m
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(this, m.uri)
            p.prepare()
            p.setOnCompletionListener { playBtn.text = "▶" }
            p.start()
            player = p
            seek.max = p.duration
            nowPlaying.text = m.name
            playBtn.text = "❚❚"
        } catch (e: Exception) {
            p.release(); player = null
            toast("Can't play this mix: ${e.message}")
        }
        adapter.notifyDataSetChanged()
    }

    private fun togglePlay() {
        val p = player ?: run { mixes.firstOrNull()?.let { play(it) }; return }
        if (p.isPlaying) { p.pause(); playBtn.text = "▶" } else { p.start(); playBtn.text = "❚❚" }
    }

    private fun actions(m: Mix) {
        AlertDialog.Builder(this).setTitle(m.name)
            .setItems(arrayOf("Share…", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> share(m)
                    1 -> rename(m)
                    2 -> AlertDialog.Builder(this).setMessage("Delete \"${m.name}\"? This can't be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            if (current?.uri == m.uri) { player?.release(); player = null; current = null; nowPlaying.text = "Nothing playing"; playBtn.text = "▶" }
                            if (!runCatching { store.delete(m) }.getOrDefault(false)) toast("Couldn't delete it")
                            refresh()
                        }.setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun share(m: Mix) {
        if (m.file != null) return toast("Sharing needs Android 10 or newer. The file is at ${m.file.path}")
        val send = Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, m.uri)
            .putExtra(Intent.EXTRA_TITLE, m.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share ${m.name}"))
    }

    private fun rename(m: Mix) {
        val input = EditText(this).apply { setText(m.name); setSelectAllOnFocus(true); setSingleLine() }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this).setTitle("Rename mix").setView(box)
            .setPositiveButton("Rename") { _, _ ->
                if (!runCatching { store.rename(m, input.text.toString()) }.getOrDefault(false)) toast("Couldn't rename it")
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(if (filled) accent else 0xFF2A2A31.toInt()) }
        setTextColor(if (filled) Color.BLACK else Color.WHITE)
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        fun fmt(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
