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
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.Choreographer
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.bouzouki.core.Cell
import com.bouzouki.core.PlaybackClock
import com.bouzouki.core.song.SongAnalysis
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Play along with a song, Chordify-style: give it a YouTube link (or an audio file), it
 * prepares the song once — removing the voice and transcribing the bouzouki line — and then
 * plays the video with that line marked on the neck (with a moving tab strip) or on the whole
 * song's tablature. A [PlaybackClock] smooths the player's coarse time so both follow it at
 * every frame, with a per-song sync offset.
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
    private lateinit var extraRow: LinearLayout
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
    private lateinit var neckPage: LinearLayout
    private lateinit var sheet: TabSheetView
    private lateinit var sheetScroller: ScrollView
    private lateinit var neckTab: Button
    private lateinit var sheetTab: Button
    private lateinit var syncText: TextView
    private val speedButtons = LinkedHashMap<Float, Button>()
    private lateinit var soundButton: Button
    private val prefs by lazy { getSharedPreferences("bouzouki", Context.MODE_PRIVATE) }

    /** What's loaded: a YouTube id or a file id, and the prepared song once there is one. */
    private var currentId: String? = null
    private var song: SongAnalysis? = null
    private var fileUri: Uri? = null
    private var player: MediaPlayer? = null
    private var speed = 1f
    private var bouzoukiSound = false
    private var lastNote = -1
    private var lastNext = -1
    private var playing = false // the audio file's player
    private var filePrepared = false

    /** Playback time between the player's reports, and what follows it at every frame. */
    private val clock = PlaybackClock()
    private var frameScheduled = false
    private var visible = false // started: drawn on screen, even when another window has the focus
    private var lastFrameMedia = Double.NaN
    /** Seconds added to the playback time when looking up notes (per song, see [nudgeSync]). */
    private var syncOffset = 0.0
    /** After a seek the player still reports the old time for a moment: ignore it until then. */
    private var pendingSeek = Double.NaN
    private var pendingSeekNanos = 0L
    /** The next note to pluck with the bouzouki sound; recomputed after jumps. */
    private var nextPluck = 0
    private var pluckReset = true
    private var shownSecond = -1

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
            addView(ui.chip("🔗") { showLinkRows(linkRow.visibility != View.VISIBLE) },
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
        extraRow = ui.row().apply {
            addView(ui.chip("📁 Audio file") { pickFile() }, ui.weighted())
            addView(ui.chip("🕘 My songs") { showSaved() }, ui.weighted())
        }
        controls.addView(extraRow)

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
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
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
            soundButton = ui.chip("🔊 Bouzouki") { bouzoukiSound = !bouzoukiSound; pluckReset = true; refreshButtons() }
            addView(soundButton, ui.weighted(1.6f))
        })

        // Neck or tablature, and the sync between the notes and the sound.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6), px(2), px(6), px(4))
            fun slim(b: Button) = b.apply { minHeight = px(38); minimumHeight = px(38) } // this row stays low
            neckTab = slim(ui.chip("🎸 Neck") { showPage(PAGE_NECK) })
            sheetTab = slim(ui.chip("📜 Tablature") { showPage(PAGE_SHEET) })
            addView(neckTab, ui.weighted(1f))
            addView(sheetTab, ui.weighted(1.35f))
            addView(slim(ui.chip("−") { nudgeSync(-SYNC_STEP) }), LinearLayout.LayoutParams(px(38), px(38)).apply { marginStart = px(6) })
            syncText = TextView(context).apply {
                textSize = 11f
                setTextColor(0xFFE8EEF6.toInt())
                gravity = Gravity.CENTER
                minWidth = px(56)
                setOnClickListener { status.text = "Sync: if the notes come after you hear them, press +; if they come early, press −. Kept for this song." }
            }
            addView(syncText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(slim(ui.chip("+") { nudgeSync(SYNC_STEP) }), LinearLayout.LayoutParams(px(38), px(38)).apply { marginEnd = px(3) })
        })

        // The neck, with the moving tab strip above it.
        tab = TabStripView(this).apply {
            names = settings.names
            onSeek = { t -> seek(t - syncOffset) }
        }
        scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(board)
        }
        neckPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = px(6); marginEnd = px(6); bottomMargin = px(2)
            })
            addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // The whole song's tablature (the strip would only repeat it, so it stays with the neck).
        sheet = TabSheetView(this).apply {
            names = settings.names
            onNoteTap = { i -> playNoteAt(i) }
            onSeek = { t -> seek(t - syncOffset) }
        }
        sheetScroller = ScrollView(this).apply {
            isFillViewport = true
            addView(sheet)
        }
        root.addView(FrameLayout(this).apply {
            addView(neckPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(sheetScroller, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        showPage(prefs.getInt(PREF_PAGE, PAGE_NECK))
        showSync()
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
        loadSync(id)
        showLinkRows(false) // more room for the neck; 🔗 brings them back
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
        loadSync(id)
        showLinkRows(false) // more room for the neck; 🔗 brings them back
        fileRow.visibility = View.VISIBLE
        player = MediaPlayer().apply {
            setDataSource(this@SongActivity, uri)
            // The speed chosen carries over; set before preparing, it doesn't start playback.
            if (speed != 1f) try { playbackParams = PlaybackParams().setSpeed(speed) } catch (_: Exception) {}
            setOnPreparedListener { filePrepared = true; tickFile() }
            setOnCompletionListener { playing = false; refreshButtons(); tickFile() }
            setOnSeekCompleteListener { tickFile() }
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

    /** A song fingered for the bouzouki chosen now (and with the current fingering rules). */
    private fun forInstrument(s: SongAnalysis): SongAnalysis = s.forInstrument(settings.instrument)

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
        sheet.beats = if (s != null) beatsFor(s) else emptyList()
        sheet.song = s
        lastNote = -1
        lastNext = -1
        lastFrameMedia = Double.NaN
        pluckReset = true
        board.liveCell = null
        board.nextCell = null
        noteText.text = "–"
        if (s != null) {
            prepPanel.visibility = View.GONE
            if (s.title.isNotBlank()) titleText.text = s.title
            status.text = if (s.notes.isEmpty()) "No clear bouzouki line was found in this song."
            else "Ready: ${s.notes.size} bouzouki notes. The hollow ring shows the next one. " +
                "📜 Tablature shows the whole song: tap a note to hear it and play from there."
            s.notes.firstOrNull()?.let { board.nextCell = Cell(it.course, it.fret) }
            sheet.showNow()
        }
        showDromos()
    }

    /**
     * Beat times (seconds) that lay the tablature out in bars of 4 beats, two to a row; empty
     * (no clear pulse) means rows of 4 seconds.
     */
    private fun beatsFor(song: SongAnalysis): List<Double> = song.beats

    /** Shows the neck (with the tab strip) or the whole song's tablature, and remembers which. */
    private fun showPage(page: Int) {
        val sheetPage = page == PAGE_SHEET
        neckPage.visibility = if (sheetPage) View.GONE else View.VISIBLE
        sheetScroller.visibility = if (sheetPage) View.VISIBLE else View.GONE
        ui.style(neckTab, !sheetPage)
        ui.style(sheetTab, sheetPage)
        prefs.edit().putInt(PREF_PAGE, page).apply()
        if (sheetPage) sheet.showNow()
        else board.post { board.liveCell?.let { keepVisible(it) } } // after the neck's first layout
    }

    /** The link box and the file/saved-songs row, folded away while a song is open. */
    private fun showLinkRows(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        linkRow.visibility = v
        extraRow.visibility = v
    }

    /** A tapped note on the tablature: play it, and the song from just before it. */
    private fun playNoteAt(i: Int) {
        val s = song ?: return
        val n = s.notes.getOrNull(i) ?: return
        seek(n.start - NOTE_PREROLL - syncOffset)
        if (bouzoukiSound && clock.playing) return // the bouzouki sound plays it in time, once the player is there
        val inst = board.instrument
        if (n.course in inst.courses.indices) audio.synth.pluck(n.course, inst.midiAt(n.course, n.fret), inst.courses[n.course].octavePair, 0.85f)
    }

    // --- sync between the notes and the sound ---

    private fun loadSync(id: String) {
        syncOffset = prefs.getFloat(PREF_SYNC + id, 0f).toDouble()
        showSync()
    }

    /** Positive: the notes show (and the bouzouki sound plays) that much earlier. */
    private fun nudgeSync(by: Double) {
        val id = currentId ?: run { status.text = "Load a song first; the sync is kept for each song."; return }
        syncOffset = (((syncOffset + by) * 1000).roundToInt() / 1000.0).coerceIn(-MAX_SYNC, MAX_SYNC)
        prefs.edit().putFloat(PREF_SYNC + id, syncOffset.toFloat()).apply()
        showSync()
        val ms = abs(syncOffset * 1000).roundToInt()
        status.text = when {
            syncOffset > 0 -> "Notes now show $ms ms earlier than the sound."
            syncOffset < 0 -> "Notes now show $ms ms later than the sound."
            else -> "Notes in step with the sound (no sync offset)."
        }
        pluckReset = true
        render(clock.now(SystemClock.elapsedRealtimeNanos()))
    }

    private fun showSync() {
        val v = String.format(Locale.US, "%+.3f", syncOffset).let { if (it.endsWith("0")) it.dropLast(1) else it }
        syncText.text = "Sync\n" + (if (syncOffset == 0.0) "0.00" else v) + " s"
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
        if (!filePrepared) { status.text = "The file is still loading…"; return }
        try {
            if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
        } catch (_: IllegalStateException) { status.text = "The file is still loading…" }
        tickFile()
        refreshButtons()
    }

    private val fileTicker = Runnable { tickFile() }

    /** Reports the file's position to the clock, ten times a second while it plays. */
    private fun tickFile() {
        main.removeCallbacks(fileTicker)
        val p = player ?: return
        if (!filePrepared) return // asking for the position before that puts MediaPlayer in its error state
        val t = try { p.currentPosition / 1000.0 } catch (_: IllegalStateException) { return }
        val nowPlaying = try { p.isPlaying } catch (_: IllegalStateException) { false }
        if (nowPlaying != playing) { playing = nowPlaying; refreshButtons() } // e.g. a speed change can start it
        // The speed the player really uses, which isn't the button's if setting it failed.
        val rate = try { p.playbackParams.speed.toDouble() } catch (_: Exception) { speed.toDouble() }
        onReport(t, playing, rate, SystemClock.elapsedRealtimeNanos())
        if (playing) main.postDelayed(fileTicker, 100)
    }

    private fun stopFile() {
        main.removeCallbacks(fileTicker)
        player?.release()
        player = null
        playing = false
        filePrepared = false
        // Whatever plays next starts from a stopped clock.
        clock.report(0.0, false, speed.toDouble(), SystemClock.elapsedRealtimeNanos())
        pendingSeek = Double.NaN
        shownSecond = -1
    }

    // --- playback and sync ---

    private fun setSpeed(r: Float) {
        speed = r
        if (fileUri != null) {
            player?.let { p -> try { p.playbackParams = PlaybackParams().setSpeed(r) } catch (_: Exception) {} }
            tickFile()
        } else {
            web.evaluateJavascript("player && player.setPlaybackRate($r)", null)
        }
        refreshButtons()
    }

    /**
     * Moves the song to [t] (seconds of media). The clock goes there at once and waits for the
     * player, and the bouzouki sound picks up from there when the player does.
     */
    private fun seek(t: Double) {
        val target = t.coerceAtLeast(0.0)
        val nanos = SystemClock.elapsedRealtimeNanos()
        clock.jump(target, nanos)
        pendingSeek = target
        pendingSeekNanos = nanos
        val s = song
        if (s != null && clock.playing) { nextPluck = s.nextAfter(target + syncOffset); pluckReset = false } else pluckReset = true
        if (fileUri != null) player?.takeIf { filePrepared }?.let { p -> try { p.seekTo((target * 1000).toInt()) } catch (_: Exception) {} }
        else web.evaluateJavascript("player && player.seekTo($target, true)", null)
        render(target)
    }

    /** A time report from the player (YouTube or the file), taken at [nanos]. */
    private fun onReport(t: Double, isPlaying: Boolean, rate: Double, nanos: Long) {
        if (!pendingSeek.isNaN()) {
            // Near the target, whether it has played on since or is still loading: the seek has landed.
            val since = (nanos - pendingSeekNanos) / 1e9
            val furthest = pendingSeek + if (clock.playing) since * clock.rate else 0.0
            if ((t < pendingSeek - 0.3 || t > furthest + 0.3) && since < 2.0) return // still the old position
            pendingSeek = Double.NaN
        }
        if (clock.report(t, isPlaying, rate, nanos)) pluckReset = true
        // Frame-by-frame drawing only once there are notes to follow; before that, the reports will do.
        if (clock.playing && song != null) scheduleFrame() else render(clock.now(nanos))
    }

    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        if (clock.playing && song != null) {
            val t = clock.now(SystemClock.elapsedRealtimeNanos())
            if (t != lastFrameMedia) render(t) // the clock stands still while a seek lands or the player stalls
            scheduleFrame()
        }
    }

    /** Draws at every frame (vsync) while the song plays and the screen shows it. */
    private fun scheduleFrame() {
        if (frameScheduled || !visible) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** Shows the song at media time [media]: the neck, the note, the tab strip and the tablature. */
    private fun render(media: Double) {
        lastFrameMedia = media
        if (fileUri != null) {
            val sec = media.toInt()
            if (sec != shownSecond) { shownSecond = sec; fileTimeText.text = "%d:%02d".format(sec / 60, sec % 60) }
        }
        // What's drawn now is on screen a frame or two later.
        val s = song ?: return
        val t = media + syncOffset + if (clock.playing) DISPLAY_LEAD * clock.rate else 0.0
        tab.time = t
        sheet.time = t
        pluck(s, media + syncOffset)
        val i = s.noteAt(t)
        if (i != lastNote) {
            lastNote = i
            if (i >= 0) {
                val n = s.notes[i]
                val cell = Cell(n.course, n.fret)
                board.liveCell = cell
                noteText.text = settings.names.name(n.midi)
                keepVisible(cell)
            } else {
                board.liveCell = null
                noteText.text = "–"
            }
        }
        val next = s.nextAfter(t)
        if (next != lastNext) {
            lastNext = next
            board.nextCell = s.notes.getOrNull(next)?.let { Cell(it.course, it.fret) }
        }
    }

    /**
     * The bouzouki sound: plucks each note a little ahead of time [t] (the synth takes a moment
     * to reach the speaker), once, and never a note the song is already well into.
     */
    private fun pluck(s: SongAnalysis, t: Double) {
        if (!bouzoukiSound || !clock.playing) { pluckReset = true; return }
        if (clock.holding) return // a seek on its way: nothing before the player gets there
        if (pluckReset) { nextPluck = s.nextAfter(t); pluckReset = false }
        val ahead = t + PLUCK_LEAD * clock.rate
        val inst = board.instrument
        while (nextPluck < s.notes.size && s.notes[nextPluck].start <= ahead) {
            val n = s.notes[nextPluck]
            if (ahead - n.start < 0.25 && n.course in inst.courses.indices) {
                audio.synth.pluck(n.course, inst.midiAt(n.course, n.fret), inst.courses[n.course].octavePair, 0.85f)
            }
            nextPluck++
        }
    }

    /** Scrolls the neck to [cell] if it's off screen (only while the neck page shows: it isn't laid out otherwise). */
    private fun keepVisible(cell: Cell) {
        if (!scroller.isShown) return
        val (top, bottom) = board.cellSpan(cell)
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
          var player = null, lastT = -1, lastS = -9, lastR = -1, lastSent = 0;
          // Polled often but passed on only when something changed (or every 250 ms), so the app
          // sees each new time soon after the player has it.
          function report(force) {
            if (!player || !player.getCurrentTime) return;
            var t = player.getCurrentTime(), s = player.getPlayerState();
            var r = player.getPlaybackRate ? player.getPlaybackRate() : 1;
            var now = Date.now();
            if (force || t !== lastT || s !== lastS || r !== lastR || now - lastSent > 250) {
              lastT = t; lastS = s; lastR = r; lastSent = now;
              Android.onTime(t, s, r);
            }
          }
          function onYouTubeIframeAPIReady() {
            player = new YT.Player('p', {
              width: '100%', height: '100%', videoId: '$id',
              playerVars: { playsinline: 1, rel: 0, origin: '$ORIGIN' },
              events: {
                onReady: function () { setInterval(report, 20); },
                onStateChange: function () { report(true); },
                onPlaybackRateChange: function () { report(true); },
                onError: function (e) { Android.onError(e.data); }
              }
            });
          }
        </script></body></html>
    """.trimIndent()

    private inner class YouTubeBridge {
        @JavascriptInterface
        fun onTime(t: Double, state: Int, rate: Double) {
            val nanos = SystemClock.elapsedRealtimeNanos() // when the page read it, not when the UI thread gets to it
            main.post { if (fileUri == null) onReport(t, state == YT_PLAYING, rate, nanos) }
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

    override fun onStart() {
        super.onStart()
        visible = true
        if (clock.playing) scheduleFrame()
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

    // Drawing stops when the screen is hidden, not on pause: paused but still visible (split screen,
    // a dialog on top), the video plays on and so does the drawing.
    override fun onStop() {
        visible = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
        super.onStop()
    }

    override fun onDestroy() {
        stopFile()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_FILE = 3
        private const val ORIGIN = "https://com.bouzouki"
        private const val YT_PLAYING = 1
        private const val PAGE_NECK = 0
        private const val PAGE_SHEET = 1
        private const val PREF_PAGE = "song_page"
        private const val PREF_SYNC = "song_sync_"
        private const val SYNC_STEP = 0.025
        private const val MAX_SYNC = 1.0
        /** Seconds between drawing a frame and seeing it. */
        private const val DISPLAY_LEAD = 0.025
        /** Seconds between plucking the synth and hearing it. */
        private const val PLUCK_LEAD = 0.06
        /** A tapped note plays from this many seconds before it (a little more than YouTube's time step). */
        private const val NOTE_PREROLL = 0.3

        private val ID = Regex("""(?:v=|youtu\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})""")

        /** The 11-character video id from any kind of YouTube link (or a bare id). */
        fun youTubeId(text: String): String? {
            val t = text.trim()
            ID.find(t)?.let { return it.groupValues[1] }
            return t.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
        }
    }
}
