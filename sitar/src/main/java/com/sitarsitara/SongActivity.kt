package com.sitarsitara

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.bouzouki.core.song.SongAnalysis
import com.bouzoukihero.core.YouTube
import com.sitarsitara.core.Reading
import com.sitarsitara.core.Sargam
import com.sitarsitara.core.Script
import com.sitarsitara.core.SitarAnalysis
import com.sitarsitara.core.Taal
import com.sitarsitara.core.TaalGrid
import com.sitarsitara.hero.HeroActivity
import com.sitarsitara.hero.YouTubeSong

/**
 * Find a song (search YouTube, paste or share a link, or pick an audio file), have it prepared, then read its
 * sitar line as sargam on the taal while the video plays, the matra being played lit.
 */
class SongActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private val settings by lazy { Settings(this) }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var picker: ScrollView
    private lateinit var songPanel: LinearLayout

    // The picker.
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var cancelButton: Button
    private lateinit var songList: LinearLayout
    private val search by lazy { SongSearch(this, ui, { id -> SongJobs.cached(this, id) != null }) { hit -> prepareOrOpen(hit.id) } }

    // The song open.
    private var song: SongAnalysis? = null
    private var reading: Reading? = null
    private var grid: List<TaalGrid.Line> = emptyList()
    private lateinit var videoBox: FrameLayout
    private var video: YouTubeSong? = null
    private lateinit var info: TextView
    private lateinit var taalSpinner: Spinner
    private lateinit var saSpinner: Spinner
    private lateinit var scriptButton: Button
    private lateinit var notesScroll: ScrollView
    private lateinit var sargam: SargamView
    private var settingUp = false

    private val jobListener: (SongJobs.State) -> Unit = { onJob(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.navigationBarColor = NIGHT
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(NIGHT) }
        title = TextView(this).apply {
            text = "Sitar Sitara · play along"
            textSize = 18f
            setTextColor(WHITE)
            setBackgroundColor(MAROON)
            setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(12))
            maxLines = 2
        }
        root.addView(title)
        root.addView(ToranView(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(12)))
        buildPicker()
        buildSongPanel()
        root.addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(songPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        fitSystemBars(this, root)
        showPicker()
        SongJobs.listen(jobListener)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** A video shared from the YouTube app (or a link from anywhere): prepare it, or open it if it's ready. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val id = linkId(text)
        if (id == null) { query.setText(text); toast("That isn't a YouTube link. Searching for it instead."); runSearch(); return }
        prepareOrOpen(id)
    }

    // --- the picker ---

    private fun buildPicker() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(14), ui.px(8), ui.px(14), ui.px(20)) }
        picker = ScrollView(this).apply { addView(col) }
        col.addView(ui.label("Search YouTube, or paste a link"))
        query = EditText(this).apply {
            hint = "e.g. Ravi Shankar Raga Yaman"
            setHintTextColor(MUTED)
            setTextColor(WHITE)
            background = ui.rounded()
            setPadding(ui.px(12), ui.px(10), ui.px(12), ui.px(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) { runSearch(); true } else false
            }
        }
        col.addView(query, ui.wide(2))
        col.addView(ui.row().apply {
            addView(ui.chip("🔎  Search") { runSearch() }.also { ui.style(it, true) }, ui.weighted())
            addView(ui.chip("🎵  Audio file") { pickFile() }, ui.weighted())
        }, ui.wide(6))
        status = ui.text("", 14f, SOFT).apply { setPadding(ui.px(4), ui.px(10), ui.px(4), ui.px(2)) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        cancelButton = ui.chip("Stop preparing") { SongJobs.cancel() }.apply { visibility = View.GONE }
        col.addView(status)
        col.addView(progress, ui.wide(4))
        col.addView(cancelButton, ui.wide(6))
        col.addView(ui.text(
            "A song takes a few minutes the first time: the app downloads its audio, removes the singer, and listens " +
                "for the sitar. Only the notes are kept. You can leave the app meanwhile.", 13f, MUTED,
        ).apply { setPadding(ui.px(4), ui.px(10), ui.px(4), ui.px(4)) })
        col.addView(ui.label("Your songs (long-press to remove one)"))
        songList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(songList)
    }

    private fun runSearch() {
        val text = query.text.toString().trim()
        if (text.isEmpty()) { toast("Type a song, raga or artist, or paste a YouTube link"); return }
        val id = linkId(text)
        if (id != null) { prepareOrOpen(id); return }
        hideKeyboard()
        search.show(text) { msg -> status.text = msg }
    }

    /** The video in [text] if it is a YouTube link, or a bare video id (which, unlike a word, has a digit, - or _). */
    private fun linkId(text: String): String? {
        val t = text.trim()
        if (t.contains("youtu", ignoreCase = true)) return YouTube.videoId(t)
        return t.takeIf { YouTube.isId(it) && it.any { c -> c.isDigit() || c == '-' || c == '_' } }
    }

    private fun prepareOrOpen(id: String) {
        val cached = SongJobs.cached(this, id)
        if (cached != null) { open(cached); return }
        startJob(SongJobs.Source.YouTube(id))
    }

    private fun startJob(source: SongJobs.Source) {
        if (SongJobs.isBusy()) { toast("Another song is being prepared. Wait for it, or stop it."); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        showPicker()
        SongJobs.start(this, source)
    }

    private fun pickFile() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), REQ_FILE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_FILE || resultCode != RESULT_OK || uri == null) return
        startJob(SongJobs.Source.AudioFile(uri, displayName(uri)))
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Audio file"
    } catch (_: Exception) {
        "Audio file"
    }

    private fun onJob(s: SongJobs.State) {
        when (s) {
            is SongJobs.State.Working -> {
                status.text = s.step
                progress.visibility = View.VISIBLE
                progress.progress = (s.fraction * 1000).toInt()
                cancelButton.visibility = View.VISIBLE
            }
            is SongJobs.State.Done -> {
                progress.visibility = View.GONE
                cancelButton.visibility = View.GONE
                status.text = "Ready: ${s.song.title}"
                refreshList()
                // Opened only if nothing else is open: a song you're reading stays put.
                if (song == null) open(s.song)
            }
            is SongJobs.State.Failed -> {
                progress.visibility = View.GONE
                cancelButton.visibility = View.GONE
                status.text = "Couldn't prepare the song: ${s.message}"
            }
            SongJobs.State.Idle -> {
                progress.visibility = View.GONE
                cancelButton.visibility = View.GONE
            }
        }
    }

    private fun refreshList() {
        songList.removeAllViews()
        val songs = SongJobs.recent(this)
        if (songs.isEmpty()) {
            songList.addView(ui.text("None yet. Search for one above.", 14f, MUTED).apply { setPadding(ui.px(4), ui.px(4), 0, 0) })
        }
        for (s in songs) {
            val row = ui.chip((if (s.current) "▶  " else "↻  ") + s.title.ifBlank { s.id }) {
                val cached = SongJobs.cached(this, s.id)
                when {
                    cached != null -> open(cached)
                    s.id.startsWith("file-") -> toast("Pick the audio file again to prepare it with this version of the app")
                    else -> startJob(SongJobs.Source.YouTube(s.id))
                }
            }
            row.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            row.setPadding(ui.px(12), ui.px(6), ui.px(12), ui.px(6))
            row.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle("Remove this song?").setMessage(s.title)
                    .setPositiveButton("Remove") { _, _ -> SongJobs.delete(this, s.id); refreshList() }
                    .setNegativeButton("Keep", null).show()
                true
            }
            songList.addView(row, ui.wide(6))
        }
    }

    private fun showPicker() {
        closeVideo()
        song = null
        title.text = "Sitar Sitara · play along"
        songPanel.visibility = View.GONE
        picker.visibility = View.VISIBLE
        refreshList()
    }

    // --- the song open ---

    private fun buildSongPanel() {
        songPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val w = resources.displayMetrics.widthPixels
        videoBox = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        songPanel.addView(videoBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, w * 9 / 16))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.px(10), ui.px(4), ui.px(10), 0) }
        taalSpinner = spinner(Taal.entries.map { it.title + if (it.hint.isNotEmpty()) " · ${it.hint}" else " (${it.matras})" }) { pos ->
            song?.let { settings.setTaal(it.id, Taal.entries[pos]); render() }
        }
        saSpinner = spinner(listOf("Sa: work it out") + Sargam.NOTE_NAMES.map { "Sa = $it" }) { pos ->
            song?.let { settings.setSa(it.id, if (pos == 0) null else pos - 1); render() }
        }
        controls.addView(ui.row().apply {
            addView(taalSpinner, LinearLayout.LayoutParams(0, ui.px(44), 1.3f).apply { marginEnd = ui.px(4) })
            addView(saSpinner, LinearLayout.LayoutParams(0, ui.px(44), 1f))
        })
        scriptButton = ui.chip("") {
            settings.script = if (settings.script == Script.LATIN) Script.DEVANAGARI else Script.LATIN
            render()
        }
        controls.addView(ui.row().apply {
            addView(scriptButton, ui.weighted())
            addView(ui.chip("🎮  Play it") { song?.let { startActivity(HeroActivity.intent(this@SongActivity, it.id, HeroActivity.difficulty(this@SongActivity))) } }
                .also { ui.style(it, true) }, ui.weighted())
            addView(ui.chip("Share") { share() }, ui.weighted(0.7f))
        })
        info = ui.text("", 13f, SOFT).apply { setPadding(ui.px(4), ui.px(4), ui.px(4), ui.px(4)) }
        controls.addView(info)
        songPanel.addView(controls)

        sargam = SargamView(this) { t -> video?.seek(maxOf(0.0, t - 0.15)) }
        notesScroll = ScrollView(this).apply { addView(sargam); isFillViewport = true }
        songPanel.addView(notesScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun spinner(items: List<String>, onPick: (Int) -> Unit) = Spinner(this).apply {
        background = ui.rounded()
        adapter = object : ArrayAdapter<String>(this@SongActivity, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(WHITE); textSize = 14f; setPadding(ui.px(10), 0, ui.px(6), 0); maxLines = 1
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (!settingUp) onPick(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun open(s: SongAnalysis) {
        hideKeyboard()
        song = s
        title.text = s.title.ifBlank { "Your song" }
        picker.visibility = View.GONE
        songPanel.visibility = View.VISIBLE
        settingUp = true
        taalSpinner.setSelection(settings.taal(s.id).ordinal)
        saSpinner.setSelection(settings.sa(s.id)?.let { it + 1 } ?: 0)
        main.post { settingUp = false } // the spinners report their first selection after this
        render()
        notesScroll.scrollTo(0, 0)
        closeVideo()
        val isVideo = !s.id.startsWith("file-")
        videoBox.visibility = if (isVideo) View.VISIBLE else View.GONE
        if (isVideo) {
            val web = WebView(this)
            videoBox.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val start = maxOf(0.0, (reading?.notes?.firstOrNull()?.start ?: 0.0) - 2.0)
            video = YouTubeSong(web, s.id, start, null, object : YouTubeSong.Events {
                override fun onPlaying() {}
                override fun onVideoError(message: String) { toast(message) }
            })
            main.post(tick)
        }
    }

    /** Works out the reading and the grid for the song as it is set (Sa, taal, script) and shows them. */
    private fun render() {
        val s = song ?: return
        val r = Reading.of(SitarAnalysis.line(s), settings.sa(s.id))
        val taal = settings.taal(s.id)
        reading = r
        grid = TaalGrid.layout(r.notes, taal, s.beats, 0.0)
        val script = settings.script
        scriptButton.text = if (script == Script.LATIN) "S R G → सा रे ग" else "सा रे ग → S R G"
        sargam.script = script
        sargam.show(grid, r.notes)
        info.text = describe(r, s)
    }

    private fun describe(r: Reading, s: SongAnalysis): String {
        val sb = StringBuilder()
        sb.append("Sa = ${r.saName}")
        if (!r.chosen) sb.append(" (worked out from the notes)")
        val cents = Math.round(s.tuningCents)
        if (cents != 0L) sb.append(", the recording ${if (cents > 0) "+" else ""}$cents cents from A440")
        r.best?.let { b ->
            sb.append("\nScale: ${b.scale.title}")
            if (b.scale.isThaat) sb.append(" · ragas such as ${b.scale.ragas.joinToString(", ")}")
            val others = r.guesses.drop(1).joinToString(" · ") { g -> g.scale.title + if (g.saPc != r.saPc) " on ${g.sa}" else "" }
            if (others.isNotEmpty()) sb.append("\nOr maybe: $others")
        }
        sb.append("\n${r.notes.size} notes, ${r.notes.count { it.note.meend }} by meend (~). Tap a matra to play from there.")
        if (r.folded > 0) sb.append("\n${r.folded} note(s) were outside the sitar's range and moved by an octave.")
        return sb.toString()
    }

    /** Follows the video: lights the matra playing and keeps its line in view. */
    private val tick = object : Runnable {
        private var lastRow = -1
        override fun run() {
            val v = video ?: return
            val t = v.now()
            sargam.now = t
            if (v.playing) {
                val top = sargam.rowTopAt(t)
                if (top >= 0 && top != lastRow) {
                    lastRow = top
                    val y = notesScroll.scrollY
                    if (top < y || top > y + notesScroll.height * 0.6) notesScroll.smoothScrollTo(0, top)
                }
            }
            main.postDelayed(this, 50)
        }
    }

    private fun closeVideo() {
        main.removeCallbacks(tick)
        video?.release()
        video = null
        if (::videoBox.isInitialized) videoBox.removeAllViews()
    }

    private fun share() {
        val s = song ?: return
        val r = reading ?: return
        val taal = settings.taal(s.id)
        val script = settings.script
        val header = buildList {
            add(s.title.ifBlank { "Sitar sargam" })
            if (!s.id.startsWith("file-")) add("https://youtu.be/${s.id}")
            add("Sa = ${r.saName}   Taal: ${taal.label}")
            r.best?.let { add("Scale (best guess): ${it.scale.title}" + if (it.scale.isThaat) " (ragas such as ${it.scale.ragas.joinToString(", ")})" else "") }
            add(if (script == Script.LATIN) "S R G m P D N = shuddha   r g d n = komal   M = tivra Ma   ' = taar saptak   , = mandra saptak"
                else "line below = komal   stroke above = tivra Ma   dot above = taar saptak   dot below = mandra saptak")
            add("~ = meend (no new stroke)   * = fast strokes   - = hold   X = sam   0 = khali")
            add("Written by Sitar Sitara, a first draft to correct by ear")
        }
        val text = TaalGrid.text(grid, r.notes, header, script)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share the sargam"))
    }

    // --- the rest ---

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (song != null) showPicker() else super.onBackPressed()
    }

    override fun onPause() {
        video?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (song == null) refreshList()
    }

    override fun onDestroy() {
        SongJobs.unlisten(jobListener)
        closeVideo()
        super.onDestroy()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQ_FILE = 7
    }
}
