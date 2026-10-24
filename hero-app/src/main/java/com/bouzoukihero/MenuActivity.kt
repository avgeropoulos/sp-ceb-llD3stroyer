package com.bouzoukihero

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.bouzoukihero.core.BouzoukiTabs
import com.bouzoukihero.core.Buttons
import com.bouzoukihero.core.CustomSong
import com.bouzoukihero.core.Difficulty
import com.bouzoukihero.core.Tunes
import com.bouzoukihero.core.YouTube
import com.bouzoukihero.core.parseClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The song list: pick a difficulty and a song, or add one from YouTube. */
class MenuActivity : Activity() {
    private lateinit var store: Store
    private lateinit var list: LinearLayout
    @Volatile private var transcribing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        scroll.addView(list)
        setContentView(scroll)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    /** A YouTube link shared from the YouTube app (Share > Bouzouki Hero). */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        i.action = null
        list.post { addSongDialog(text) }
    }

    // ---- The list ----

    private fun build() {
        list.removeAllViews()
        list.addView(text("BOUZOUKI HERO", 34f, GOLD, bold = true).apply { gravity = Gravity.CENTER })
        list.addView(text("Tap the notes as they reach the bottom. Opa!", 14f, SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(18))
        })

        header("Difficulty")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (d in Difficulty.entries) {
            val selected = d == store.difficulty
            val b = chip(d.label, selected) {
                store.difficulty = d
                build()
            }
            row.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        list.addView(row)
        list.addView(text(describe(store.difficulty), 12f, SUB).apply { setPadding(dp(4), dp(8), dp(4), 0) })

        header("Songs")
        val d = store.difficulty
        for (t in Tunes.all) {
            val key = "b:${t.id}"
            card(t.title, t.style, scoreLine(key, d)) {
                startActivity(PlayActivity.playIntent(this, PlayActivity.KIND_BUILTIN, t.id, d))
            }
        }

        header("Your YouTube songs")
        val songs = store.songs()
        if (songs.isEmpty()) {
            list.addView(text(
                "Add any song from YouTube. Get its notes from your Bouzouki Tabs app, " +
                    "or tap along to the video to make the chart yourself.", 13f, SUB,
            ).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
        }
        for (s in songs) {
            val status = when {
                !s.hasChart -> "No chart yet. Tap to listen and make one"
                s.source == CustomSong.SOURCE_TABS -> "Chart from Bouzouki Tabs · ${s.notes.size} notes"
                s.source == CustomSong.SOURCE_AUTO -> "Chart heard by the app · ${s.notes.size} notes"
                else -> "Chart you tapped in · ${s.notes.size} notes"
            }
            card(s.title, status, if (s.hasChart) scoreLine("c:${s.id}", d) else "", onLong = { songMenu(s) }) {
                if (s.hasChart) startActivity(PlayActivity.playIntent(this, PlayActivity.KIND_CUSTOM, s.id, d))
                else listen(s)
            }
        }
        list.addView(bigButton("\uD83D\uDD0D  Search YouTube", ACCENT) {
            startActivityForResult(Intent(this, SearchActivity::class.java), REQ_SEARCH)
        })
        list.addView(bigButton("+  Paste a YouTube link", CARD) { addSongDialog("") })
        list.addView(text("Long-press a YouTube song for more options.", 12f, SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        val real = store.samples().size
        list.addView(bigButton(
            if (real > 0) "Bouzouki sound: $real real note${if (real == 1) "" else "s"}" else "Bouzouki sound: record your bouzouki",
            CARD,
        ) { startActivity(Intent(this, SoundActivity::class.java)) })
        list.addView(bigButton("Settings", CARD) { settingsDialog() })
    }

    private fun describe(d: Difficulty) = when (d) {
        Difficulty.EASY -> "3 buttons, slower notes, only the main notes of the tune."
        Difficulty.MEDIUM -> "4 buttons and more of the melody."
        Difficulty.HARD -> "5 buttons, fast runs included."
        Difficulty.EXPERT -> "5 buttons, every note, tight timing."
    }

    private fun scoreLine(key: String, d: Difficulty): String {
        val best = store.best(key, d)
        if (best == 0) return "Not played on ${d.label} yet"
        val stars = store.stars(key, d)
        return "★".repeat(stars) + "☆".repeat(5 - stars) + "   Best %,d".format(best)
    }

    // ---- YouTube songs ----

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SEARCH || resultCode != RESULT_OK || data == null) return
        val id = data.getStringExtra(SearchActivity.RESULT_ID) ?: return
        list.post { addSongDialog(YouTube.watchUrl(id), data.getStringExtra(SearchActivity.RESULT_TITLE)) }
    }

    private fun addSongDialog(prefill: String, knownTitle: String? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val link = field("YouTube link", prefill)
        val start = field("Start at (optional, e.g. 0:42)", "", InputType.TYPE_CLASS_TEXT)
        val end = field("End at (optional, e.g. 2:30)", "", InputType.TYPE_CLASS_TEXT)
        box.addView(link)
        box.addView(text("Only want the bouzouki solo? Set where it starts and ends.", 12f, SUB).apply { setPadding(0, dp(10), 0, 0) })
        box.addView(start)
        box.addView(end)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(knownTitle ?: "Add a YouTube song")
            .setView(box)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = YouTube.videoId(link.text.toString())
                if (id == null) {
                    link.error = "That doesn't look like a YouTube link"
                    return@setOnClickListener
                }
                val (s, e) = try {
                    parseClock(start.text.toString()) to parseClock(end.text.toString())
                } catch (ex: IllegalArgumentException) {
                    toast(ex.message ?: "Bad time")
                    return@setOnClickListener
                }
                if (s != null && e != null && e <= s) {
                    end.error = "The end must be after the start"
                    return@setOnClickListener
                }
                dialog.dismiss()
                val song = CustomSong(UUID.randomUUID().toString().take(12), knownTitle ?: "YouTube song", id, start = s ?: 0.0, end = e)
                store.save(song)
                build()
                if (knownTitle == null) thread {
                    val title = Net.videoTitle(YouTube.watchUrl(id))
                    if (title != null) {
                        store.song(song.id)?.let { store.save(it.copy(title = title)) }
                        runOnUiThread {
                            if (!isFinishing) build()
                        }
                    }
                }
                // Straight to the automatic chart: listen once, then play.
                listen(song)
            }
        }
        dialog.show()
    }

    /** The video plays once while the app hears it and writes the chart. */
    private fun listen(song: CustomSong) {
        startActivity(PlayActivity.playIntent(this, PlayActivity.KIND_CUSTOM, song.id, Difficulty.EXPERT, listen = true))
    }

    private fun songMenu(song: CustomSong) {
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (song.hasChart) {
            options.add("Play (${store.difficulty.label})" to {
                startActivity(PlayActivity.playIntent(this, PlayActivity.KIND_CUSTOM, song.id, store.difficulty))
            })
        }
        options.add("Make the chart automatically (listen)" to { listen(song) })
        options.add("Get the chart from Bouzouki Tabs" to { transcribe(song) })
        options.add("Tap along to make the chart" to { record(song) })
        options.add("Rename" to { renameDialog(song) })
        options.add("Delete" to {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Delete \"${song.title}\"?")
                .setPositiveButton("Delete") { _, _ -> store.delete(song.id); build() }
                .setNegativeButton("Cancel", null)
                .show()
        })
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(song.title)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun renameDialog(song: CustomSong) {
        val f = field("Title", song.title)
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Rename")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = f.text.toString().trim()
                if (t.isNotEmpty()) store.song(song.id)?.let { store.save(it.copy(title = t)); build() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun record(song: CustomSong) {
        startActivity(PlayActivity.playIntent(this, PlayActivity.KIND_CUSTOM, song.id, Difficulty.EXPERT, record = true))
    }

    /** Sends the video to the Bouzouki Tabs web app and waits for the notes. */
    private fun transcribe(song: CustomSong) {
        val server = store.server
        if (server.isBlank()) {
            settingsDialog("First, enter the address of your Bouzouki Tabs app.") { transcribe(song) }
            return
        }
        if (transcribing) return
        transcribing = true
        val status = text("Sending the video to Bouzouki Tabs…", 14f, 0xFFFFFFFF.toInt())
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
            addView(status)
            addView(bar)
            addView(text("This takes a minute or two. You can leave this screen open.", 12f, SUB).apply { setPadding(0, dp(8), 0, 0) })
        }
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(song.title)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .show()
        thread(name = "transcribe") {
            var error: String? = null
            var done: CustomSong? = null
            try {
                val id = Net.startJob(server, YouTube.watchUrl(song.videoId), song.start.takeIf { it > 0 }, song.end)
                while (!cancelled.get()) {
                    val job = Net.job(server, id)
                    runOnUiThread {
                        status.text = job.stage.ifBlank { "Working…" }
                        bar.progress = (job.progress * 1000).toInt()
                    }
                    if (job.status == "error") {
                        error = job.error ?: "Bouzouki Tabs couldn't transcribe this song."
                        break
                    }
                    if (job.status == "done") {
                        val result = job.result ?: throw IllegalStateException("No result")
                        val notes = BouzoukiTabs.notes(result)
                        if (notes.size < 4) {
                            error = "Bouzouki Tabs didn't find a bouzouki melody in this song. Try setting a start and end around a bouzouki solo, or tap along instead."
                        } else {
                            val current = store.song(song.id) ?: song
                            val title = (result["title"] as? String)?.takeIf { current.title == "YouTube song" && it.isNotBlank() }
                            val updated = current.copy(
                                notes = notes, source = CustomSong.SOURCE_TABS,
                                tempo = BouzoukiTabs.tempo(result), title = title ?: current.title,
                            )
                            store.save(updated)
                            done = updated
                        }
                        break
                    }
                    Thread.sleep(1500)
                }
            } catch (e: Exception) {
                error = "Couldn't reach Bouzouki Tabs at ${Net.normalizeServer(server)}: ${e.message ?: e.javaClass.simpleName}.\n\n" +
                    "Start it with:  bouzouki-tabs-web --host 0.0.0.0\nand make sure your phone is on the same Wi-Fi."
            }
            runOnUiThread {
                transcribing = false
                if (isFinishing) return@runOnUiThread
                if (dialog.isShowing) dialog.dismiss()
                build()
                val ready = done
                when {
                    cancelled.get() -> {}
                    error != null -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("No chart yet")
                        .setMessage(error)
                        .setPositiveButton("OK", null)
                        .show()
                    ready != null -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Chart ready!")
                        .setMessage("Found ${ready.notes.size} bouzouki notes in \"${ready.title}\".")
                        .setPositiveButton("Play now") { _, _ ->
                            startActivity(PlayActivity.playIntent(this, PlayActivity.KIND_CUSTOM, ready.id, store.difficulty))
                        }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
        }
    }

    // ---- Settings ----

    private fun settingsDialog(note: String? = null, then: (() -> Unit)? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        if (note != null) box.addView(text(note, 14f, GOLD).apply { setPadding(0, 0, 0, dp(8)) })
        box.addView(text("Bouzouki Tabs address", 14f, 0xFFFFFFFF.toInt(), bold = true))
        val server = field("http://192.168.1.20:8000", store.server, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        box.addView(server)
        box.addView(text(
            "Run Bouzouki Tabs on a computer on the same Wi-Fi with\n  bouzouki-tabs-web --host 0.0.0.0\n" +
                "then enter that computer's address and port.", 12f, SUB,
        ))
        box.addView(text("Buttons", 14f, 0xFFFFFFFF.toInt(), bold = true).apply { setPadding(0, dp(18), 0, 0) })
        var buttons = store.buttons
        val buttonsToggle = text("", 14f, GOLD, bold = true).apply {
            setPadding(0, dp(8), 0, dp(4))
            fun show() {
                text = if (buttons == Buttons.TAB) "\u25C9 The tab: open string + fingers 1-4 (tap to change)"
                else "\u25C9 Notes: low on the left, high on the right (tap to change)"
            }
            show()
            setOnClickListener {
                buttons = if (buttons == Buttons.TAB) Buttons.NOTES else Buttons.TAB
                show()
            }
        }
        box.addView(buttonsToggle)
        box.addView(text(
            "The tab is worked out like Bouzouki Tabs does it. The buttons show which frets they are " +
                "and follow your hand up and down the neck; each note shows its course and fret (D7 = D course, fret 7).",
            12f, SUB,
        ))
        box.addView(text("YouTube API key (optional)", 14f, 0xFFFFFFFF.toInt(), bold = true).apply { setPadding(0, dp(18), 0, 0) })
        val apiKey = field("Leave empty to search without one", store.apiKey, InputType.TYPE_CLASS_TEXT)
        box.addView(apiKey)
        box.addView(text(
            "Search works without a key. If it stops working, a free YouTube Data API key from " +
                "console.cloud.google.com makes it reliable.", 12f, SUB,
        ))
        val latencyLabel = text("", 14f, 0xFFFFFFFF.toInt(), bold = true).apply { setPadding(0, dp(18), 0, 0) }
        box.addView(latencyLabel)
        val seek = SeekBar(this).apply {
            max = 40 // -100 .. +300 ms in 10 ms steps
            progress = ((store.latencyMs + 100) / 10).coerceIn(0, 40)
        }
        fun label() { latencyLabel.text = "Sound delay: ${seek.progress * 10 - 100} ms" }
        label()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = label()
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        box.addView(seek)
        box.addView(text(
            "If your taps are always a bit late (common with Bluetooth headphones), raise this.", 12f, SUB,
        ))
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                store.server = server.text.toString()
                store.apiKey = apiKey.text.toString()
                store.buttons = buttons
                store.latencyMs = seek.progress * 10 - 100
                if (then != null && store.server.isNotBlank()) then()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Little view helpers ----

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
        list.addView(text(s.uppercase(), 13f, SUB, bold = true).apply {
            letterSpacing = 0.12f
            setPadding(dp(4), dp(22), 0, dp(8))
        })
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = text(label, 14f, 0xFFFFFFFF.toInt(), bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(if (selected) ACCENT else CARD, 22, if (selected) GOLD else 0)
        setOnClickListener { onClick() }
    }

    private fun card(title: String, line1: String, line2: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        c.addView(text(title, 17f, 0xFFFFFFFF.toInt(), bold = true))
        c.addView(text(line1, 13f, SUB))
        if (line2.isNotEmpty()) c.addView(text(line2, 13f, GOLD).apply { setPadding(0, dp(4), 0, 0) })
        list.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(10))
        })
    }

    private fun bigButton(label: String, color: Int, onClick: () -> Unit): View =
        text(label, 16f, 0xFFFFFFFF.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(color, 26)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                setMargins(0, dp(14), 0, 0)
            }
        }

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = type
            setSingleLine()
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQ_SEARCH = 10
        const val BG = 0xFF0B1530.toInt()
        const val CARD = 0xFF19254F.toInt()
        const val ACCENT = 0xFF2F6FD6.toInt()
        const val GOLD = 0xFFFFD23F.toInt()
        const val SUB = 0xFFB9C6EE.toInt()
    }
}
