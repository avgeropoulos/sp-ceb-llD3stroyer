package com.bouzouki

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.bouzouki.core.Cell
import com.bouzouki.core.song.NoteEvent
import com.bouzouki.core.song.SongAnalysis
import com.bouzouki.core.song.SongAnalyzer

/**
 * Play along with a song, Chordify-style: give it a YouTube link (or an audio file), it
 * prepares the song once — removing the voice and transcribing the bouzouki line — and then
 * plays the video with that line marked on the neck and on a moving tab strip.
 */
class SongActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private val settings by lazy { Settings(this) }
    private val audio = AudioEngine()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var board: FretboardView
    private lateinit var scroller: ScrollView
    private lateinit var titleText: TextView
    private lateinit var link: EditText
    private lateinit var linkRow: LinearLayout
    private lateinit var web: WebView
    private lateinit var fileRow: LinearLayout
    private lateinit var filePlayButton: Button
    private lateinit var fileTimeText: TextView
    private lateinit var prepPanel: LinearLayout
    private lateinit var prepText: TextView
    private lateinit var prepBar: ProgressBar
    private lateinit var status: TextView
    private lateinit var noteText: TextView
    private lateinit var dromosText: TextView
    private lateinit var useDromosButton: Button
    private lateinit var tab: TabStripView
    private val speedButtons = LinkedHashMap<Float, Button>()
    private lateinit var soundButton: Button

    /** What's loaded: a YouTube id or a file id, and the prepared song once there is one. */
    private var currentId: String? = null
    private var song: SongAnalysis? = null
    private var fileUri: Uri? = null
    private var player: MediaPlayer? = null
    private var speed = 1f
    private var bouzoukiSound = false
    private var lastNote = -1
    private var playing = false

    private val jobListener: (SongJobs.State) -> Unit = { onJob(it) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = GREEK_BLUE
        window.navigationBarColor = NAVY
        val px = ui::px

        board = FretboardView(this, audio.synth).apply {
            instrument = settings.instrument
            dromos = settings.dromos
            rootPc = settings.rootPc
            names = settings.names
            showAllNotes = settings.showAllNotes
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAVY)
        }

        // Title bar.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(GREEK_BLUE)
            setPadding(px(4), px(4), px(8), px(4))
            addView(Button(context).apply {
                text = "←"; textSize = 22f; setTextColor(WHITE); background = null
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(px(48), px(46)))
            titleText = TextView(context).apply {
                text = "Τραγούδι · Play along"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.chip("🔗") { linkRow.visibility = if (linkRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE },
                LinearLayout.LayoutParams(px(42), px(40)).apply { marginEnd = px(4) })
            addView(wireZoom(ui, settings, board, "song", defaultWhole = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)))
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(6), px(4), px(6), px(2))
        }
        root.addView(controls)

        // The link, an audio file, or a saved song.
        link = EditText(this).apply {
            hint = "Paste a YouTube link"
            setHintTextColor(0xFF7F93AB.toInt())
            setTextColor(WHITE)
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = ui.rounded()
            setPadding(px(10), 0, px(10), 0)
            setOnEditorActionListener { _, _, _ -> loadLink(); true }
        }
        linkRow = ui.row()
        controls.addView(linkRow.apply {
            addView(link, ui.weighted(3f).apply { height = px(44) })
            addView(ui.chip("Paste") { pasteAndLoad() }, ui.weighted(1.1f))
            addView(ui.chip("Load") { loadLink() }, ui.weighted(1f))
        })
        controls.addView(ui.row().apply {
            addView(ui.chip("📁 Audio file") { pickFile() }, ui.weighted())
            addView(ui.chip("🕘 My songs") { showSaved() }, ui.weighted())
        }.also { it.tag = "extra" })

        // The video (YouTube) or a simple player (audio file).
        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            addJavascriptInterface(YouTubeBridge(), "Android")
            visibility = View.GONE
        }
        controls.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(170)))
        fileRow = ui.row().apply { visibility = View.GONE }
        filePlayButton = ui.chip("▶ Play") { toggleFilePlayback() }
        fileTimeText = TextView(this).apply { setTextColor(WHITE); textSize = 14f; gravity = Gravity.CENTER }
        fileRow.addView(filePlayButton, ui.weighted())
        fileRow.addView(fileTimeText, ui.weighted())
        controls.addView(fileRow)

        // Preparing the song.
        prepPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(px(4), px(4), px(4), px(4))
        }
        prepText = TextView(this).apply { setTextColor(0xFFE8EEF6.toInt()); textSize = 13f }
        prepBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        prepPanel.addView(prepText)
        prepPanel.addView(ui.row().apply {
            addView(prepBar, ui.weighted(3f))
            addView(ui.chip("Cancel") { SongJobs.cancel() }, ui.weighted(1f))
        })
        controls.addView(prepPanel)

        status = TextView(this).apply {
            setTextColor(0xFF9FB4CC.toInt())
            textSize = 12f
            setPadding(px(4), px(2), px(4), px(2))
        }
        controls.addView(status)

        // The note now, the dromos, speed and sound.
        noteText = TextView(this).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFD54F.toInt())
            gravity = Gravity.CENTER
            minWidth = px(64)
            text = "–"
        }
        dromosText = TextView(this).apply { textSize = 12f; setTextColor(0xFFE8EEF6.toInt()); maxLines = 2 }
        useDromosButton = ui.chip("Use") { useDromos() }.apply { visibility = View.GONE }
        controls.addView(ui.row().apply {
            addView(noteText)
            addView(dromosText, ui.weighted(3f))
            addView(useDromosButton, ui.weighted(1f))
        })
        controls.addView(ui.row().apply {
            for (r in listOf(0.5f, 0.75f, 1f)) {
                val b = ui.chip(if (r == 1f) "1×" else "$r×".replace("0.", ".")) { setSpeed(r) }
                speedButtons[r] = b
                addView(b, ui.weighted())
            }
            soundButton = ui.chip("🔊 Bouzouki") { bouzoukiSound = !bouzoukiSound; refreshButtons() }
            addView(soundButton, ui.weighted(1.6f))
        })

        tab = TabStripView(this).apply {
            names = settings.names
            onSeek = { t -> seek(t) }
        }
        controls.addView(tab)

        scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(board)
        }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        refreshButtons()
        showDromos()
        status.text = "Paste a YouTube link (or Share → Μπουζούκι in the YouTube app). The first time, a song takes a few minutes to prepare; after that it opens instantly."

        // Back to a song that's being prepared (e.g. from the notification).
        (SongJobs.state as? SongJobs.State.Working)?.let { w ->
            if (!w.id.startsWith("file-")) openYouTube(w.id, startJob = false)
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        val shared = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT) ?: return
        link.setText(shared)
        loadLink()
    }

    private fun pasteAndLoad() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) { status.text = "The clipboard is empty. In YouTube: Share → Copy link."; return }
        link.setText(text)
        loadLink()
    }

    private fun loadLink() {
        val id = youTubeId(link.text.toString())
        if (id == null) { status.text = "That doesn't look like a YouTube link."; return }
        openYouTube(id, startJob = true)
    }

    // --- loading a song ---

    private fun openYouTube(id: String, startJob: Boolean) {
        stopFile()
        fileUri = null
        currentId = id
        linkRow.visibility = View.GONE
        web.visibility = View.VISIBLE
        fileRow.visibility = View.GONE
        // YouTube wants to know which app is embedding it (an origin), or it refuses with error 152/153.
        web.loadDataWithBaseURL(ORIGIN, playerHtml(id), "text/html", "utf-8", null)
        prepareOrShow(id, SongJobs.Source.YouTube(id), startJob)
    }

    private fun openFile(uri: Uri, name: String) {
        web.loadUrl("about:blank")
        web.visibility = View.GONE
        stopFile()
        fileUri = uri
        val id = "file-" + Integer.toHexString(uri.toString().hashCode())
        currentId = id
        linkRow.visibility = View.GONE
        fileRow.visibility = View.VISIBLE
        player = MediaPlayer().apply {
            setDataSource(this@SongActivity, uri)
            setOnCompletionListener { playing = false; refreshButtons() }
            prepareAsync()
        }
        titleText.text = name
        prepareOrShow(id, SongJobs.Source.AudioFile(uri, name), startJob = true)
    }

    private fun prepareOrShow(id: String, source: SongJobs.Source, startJob: Boolean) {
        showSong(null)
        val saved = SongJobs.cached(this, id)
        if (saved != null) { showSong(forInstrument(saved)); return }
        val s = SongJobs.state
        if (s is SongJobs.State.Working && s.id == id) return // already on it
        if (!startJob) return
        if (SongJobs.isBusy()) {
            status.text = "Another song is still being prepared. Wait for it to finish, or cancel it."
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) // for the progress notification; optional
        }
        SongJobs.start(this, source, settings.instrument)
    }

    /** A saved song re-fingered for the bouzouki chosen now, if it was prepared for the other one. */
    private fun forInstrument(s: SongAnalysis): SongAnalysis {
        val inst = settings.instrument
        if (s.instrument == inst) return s
        val events = s.notes.map { NoteEvent(it.start, it.end, it.midi, 1.0) }
        return SongAnalyzer.build(s.id, s.title, events, inst).copy(dromos = s.dromos, rootPc = s.rootPc)
    }

    private fun onJob(s: SongJobs.State) {
        val id = currentId
        when (s) {
            is SongJobs.State.Working -> if (s.id == id) {
                prepPanel.visibility = View.VISIBLE
                prepText.text = "Preparing the song: ${s.step}…  ${(s.fraction * 100).toInt()}%"
                prepBar.progress = (s.fraction * 1000).toInt()
                status.text = "You can watch the video meanwhile, or leave the app: it keeps working."
            }
            is SongJobs.State.Done -> if (s.song.id == id) showSong(forInstrument(s.song))
            is SongJobs.State.Failed -> if (s.id == id) {
                prepPanel.visibility = View.GONE
                status.text = "Couldn't prepare this song: ${s.message}\nYou can try again with Load, or use 📁 Audio file."
            }
            SongJobs.State.Idle -> prepPanel.visibility = View.GONE
        }
    }

    private fun showSong(s: SongAnalysis?) {
        song = s
        tab.song = s
        lastNote = -1
        board.liveCell = null
        board.nextCell = null
        noteText.text = "–"
        if (s != null) {
            prepPanel.visibility = View.GONE
            if (s.title.isNotBlank()) titleText.text = s.title
            status.text = if (s.notes.isEmpty()) "No clear bouzouki line was found in this song."
            else "Ready: ${s.notes.size} bouzouki notes. Press play; the next note has a hollow ring on the neck."
            s.notes.firstOrNull()?.let { board.nextCell = Cell(it.course, it.fret) }
        }
        showDromos()
    }

    private fun showDromos() {
        val s = song
        val d = s?.dromos
        if (s == null || d == null) {
            dromosText.text = "Neck: ${settings.names.name(board.rootPc)} ${board.dromos.title}"
            useDromosButton.visibility = View.GONE
            return
        }
        val same = d.semitones.contentEquals(board.dromos.semitones) && s.rootPc == board.rootPc
        dromosText.text = "Song sounds like ${settings.names.name(s.rootPc)} ${d.title}" + if (same) " ✓" else ""
        useDromosButton.visibility = if (same) View.GONE else View.VISIBLE
    }

    private fun useDromos() {
        val s = song ?: return
        val d = s.dromos ?: return
        board.dromos = d
        board.rootPc = s.rootPc
        settings.dromos = d
        settings.rootPc = s.rootPc
        showDromos()
    }

    private fun showSaved() {
        val songs = SongJobs.recent(this).filter { !it.id.startsWith("file-") }
        if (songs.isEmpty()) { status.text = "No saved songs yet. Prepared songs are kept here for next time."; return }
        AlertDialog.Builder(this)
            .setTitle("My songs")
            .setItems(songs.map { if (it.current) it.title else "${it.title}  ·  prepare again" }.toTypedArray()) { _, i ->
                val s = songs[i]
                link.setText("https://youtu.be/${s.id}")
                // Saved by an older version of the analysis: prepare it again with this one.
                openYouTube(s.id, startJob = !s.current)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // --- audio files ---

    private fun pickFile() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), REQ_FILE)
    }

    @Deprecated("Activity result API needs AndroidX; this is the platform way")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_FILE || resultCode != RESULT_OK || uri == null) return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Audio file"
        openFile(uri, name.substringBeforeLast('.'))
    }

    private fun toggleFilePlayback() {
        val p = player ?: return
        try {
            if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true; tickFile() }
        } catch (_: IllegalStateException) { status.text = "The file is still loading…" }
        refreshButtons()
    }

    private val fileTicker = Runnable { tickFile() }

    private fun tickFile() {
        val p = player ?: return
        val t = try { p.currentPosition / 1000.0 } catch (_: IllegalStateException) { return }
        fileTimeText.text = "%d:%02d".format(t.toInt() / 60, t.toInt() % 60)
        onTime(t, playing)
        if (playing) main.postDelayed(fileTicker, 33)
    }

    private fun stopFile() {
        main.removeCallbacks(fileTicker)
        player?.release()
        player = null
        playing = false
    }

    // --- playback and sync ---

    private fun setSpeed(r: Float) {
        speed = r
        if (fileUri != null) {
            player?.let { p -> try { p.playbackParams = PlaybackParams().setSpeed(r) } catch (_: Exception) {} }
        } else {
            web.evaluateJavascript("player && player.setPlaybackRate($r)", null)
        }
        refreshButtons()
    }

    private fun seek(t: Double) {
        if (fileUri != null) player?.let { p -> try { p.seekTo((t * 1000).toInt()); tickFile() } catch (_: Exception) {} }
        else web.evaluateJavascript("player && player.seekTo($t, true)", null)
    }

    /** Called about 20 times a second while the song plays, with the playback time in seconds. */
    private fun onTime(t: Double, isPlaying: Boolean) {
        tab.time = t
        val s = song ?: return
        val i = s.noteAt(t)
        if (i >= 0) {
            val n = s.notes[i]
            board.liveCell = Cell(n.course, n.fret)
            noteText.text = settings.names.name(n.midi)
            if (i != lastNote) {
                if (bouzoukiSound && isPlaying && t - n.start < 0.2) {
                    val inst = board.instrument
                    audio.synth.pluck(n.course, inst.midiAt(n.course, n.fret), inst.courses[n.course].octavePair, 0.85f)
                }
                val (top, bottom) = board.cellSpan(Cell(n.course, n.fret))
                keepVisible(top, bottom)
            }
        } else {
            board.liveCell = null
            noteText.text = "–"
        }
        lastNote = i
        s.notes.getOrNull(s.nextAfter(t))?.let { board.nextCell = Cell(it.course, it.fret) } ?: run { board.nextCell = null }
    }

    private fun keepVisible(top: Int, bottom: Int) {
        val visibleTop = scroller.scrollY
        if (top < visibleTop || bottom > visibleTop + scroller.height) {
            scroller.smoothScrollTo(0, (top - scroller.height / 3).coerceAtLeast(0))
        }
    }

    private fun refreshButtons() {
        for ((r, b) in speedButtons) ui.style(b, r == speed)
        ui.style(soundButton, bouzoukiSound)
        filePlayButton.text = if (playing) "⏸ Pause" else "▶ Play"
    }

    private fun playerHtml(id: String) = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body{margin:0;height:100%;background:#000}#p{width:100%;height:100%}</style>
        </head><body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
          var player = null;
          function onYouTubeIframeAPIReady() {
            player = new YT.Player('p', {
              width: '100%', height: '100%', videoId: '$id',
              playerVars: { playsinline: 1, rel: 0, origin: '$ORIGIN' },
              events: {
                onReady: function () {
                  setInterval(function () {
                    if (player && player.getCurrentTime) Android.onTime(player.getCurrentTime(), player.getPlayerState());
                  }, 50);
                },
                onError: function (e) { Android.onError(e.data); }
              }
            });
          }
        </script></body></html>
    """.trimIndent()

    private inner class YouTubeBridge {
        @JavascriptInterface
        fun onTime(t: Double, state: Int) = main.post {
            if (fileUri == null) onTime(t, state == 1)
        }

        @JavascriptInterface
        fun onError(code: Int) = main.post {
            status.text = when (code) {
                101, 150 -> "The owner of this video doesn't allow it to play inside other apps. The song can still be prepared; play it from 📁 Audio file if you have it."
                100 -> "That video was not found (removed or private)."
                else -> "YouTube couldn't play this here (error $code)."
            }
        }
    }

    override fun onResume() {
        super.onResume()
        audio.start()
        SongJobs.listen(jobListener)
    }

    override fun onPause() {
        SongJobs.unlisten(jobListener)
        audio.stop()
        if (playing && fileUri != null) toggleFilePlayback()
        super.onPause()
    }

    override fun onDestroy() {
        stopFile()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_FILE = 3
        private const val ORIGIN = "https://com.bouzouki"

        private val ID = Regex("""(?:v=|youtu\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})""")

        /** The 11-character video id from any kind of YouTube link (or a bare id). */
        fun youTubeId(text: String): String? {
            val t = text.trim()
            ID.find(t)?.let { return it.groupValues[1] }
            return t.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
        }
    }
}
