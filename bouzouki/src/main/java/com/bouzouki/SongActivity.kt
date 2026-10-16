package com.bouzouki

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import android.util.TypedValue
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.bouzouki.core.BouzoukiSynth
import com.bouzouki.core.Cell
import com.bouzouki.core.Instrument
import com.bouzouki.core.PlaybackClock
import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.Detail
import com.bouzouki.core.song.SongAnalysis
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Play along with a song, Chordify-style: give it a YouTube link (or an audio file), it
 * prepares the song once — removing the voice and transcribing the bouzouki line — and then
 * plays the video with that line marked on the neck (with a moving tab strip) or on the whole
 * song's tablature. A [PlaybackClock] smooths the player's coarse time so both follow it at
 * every frame, with a per-song sync offset. The title bar's switch fingers the song for the
 * τετράχορδο or the τρίχορδο at once, and the bouzouki sound plays hammer-ons, pull-offs and
 * slides without picking again, and tremolo notes picked fast for as long as they last.
 * Each song's line can have fewer or more notes (Notes), or be made of the notes of a tab found
 * online and pasted in (📝 Tab), lined up with the recording; both are worked out in the
 * background by [SongLines] and remembered per song. Turned sideways (or with 🪕) it shows the
 * whole bouzouki with the video above its neck ([SongLandscape]), following the same clock.
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
    /** The screen turned sideways: the whole bouzouki, with the video above its neck. */
    private lateinit var whole: SongLandscape
    private lateinit var fileRow: LinearLayout
    private lateinit var filePlayButton: Button
    private lateinit var fileTimeText: TextView
    private lateinit var prepPanel: LinearLayout
    private lateinit var prepText: TextView
    private lateinit var prepBar: ProgressBar
    private lateinit var status: TextView
    private lateinit var statusSpinner: ProgressBar
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
    private lateinit var instrumentSwitch: InstrumentSwitch
    private lateinit var speedSwitch: Segmented
    private lateinit var detailSwitch: Segmented
    private lateinit var tabButton: Button
    private lateinit var soundButton: Button
    private var soundIconOnly = false
    private val prefs by lazy { getSharedPreferences("bouzouki", Context.MODE_PRIVATE) }

    /**
     * What's loaded: a YouTube id or a file id, and once it's prepared the song as it was
     * prepared ([source]) and as it's shown, fingered for the bouzouki chosen now ([song]).
     */
    private var currentId: String? = null
    private var currentSource: SongJobs.Source? = null
    private var source: SongAnalysis? = null
    private var song: SongAnalysis? = null
    /**
     * The line shown instead of the prepared one (more or fewer notes, or the tab's) and what it is
     * ([lineSpec]), or null for the prepared line; with a tab's, how the tab lined up. [wanted] is the
     * line being worked out in the background, shown when it comes unless something else was asked
     * for meanwhile. [pendingTab] is a tab just pasted: it's kept for its song (replacing the tab kept
     * before) only once its notes are read and found in the recording; till then, and if they aren't,
     * the tab kept before and the line chosen stay as they were.
     */
    private var line: SongAnalysis? = null
    private var lineSpec: SongLines.Spec? = null
    private var tabReport: SongLines.TabReport? = null
    private var wanted: SongLines.Spec? = null
    private var pendingTab: SongLines.Spec.FromTab? = null
    /** The song whose tab's notes gave way to the detected ones just now (the status says how to get them back). */
    private var tabGaveWay: String? = null
    /** The song being prepared while this screen watched: its song, once ready, replaces the one shown. */
    private var awaited: String? = null
    private var fileUri: Uri? = null
    private var player: MediaPlayer? = null
    private var speed = 1f
    private var bouzoukiSound = false
    private var soundOn = false // the audio engine runs (onResume..onPause)
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
    /** The last note the bouzouki sound played (a legato note goes on from its string), or -1. */
    private var lastSounded = -1
    /** The tremolo note being picked again and again, or -1, and when (song time) it's picked next. */
    private var tremoloNote = -1
    private var nextTremolo = 0.0
    private var tremoloUp = false
    private var shownSecond = -1

    private val jobListener: (SongJobs.State) -> Unit = { onJob(it) }
    private val lineListener: (SongLines.Outcome) -> Unit = { onLine(it) }

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
                maxLines = 2 // a long title wraps beside the bouzouki switch before it's cut short
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // Which bouzouki: the song is fingered again for it at once.
            instrumentSwitch = InstrumentSwitch(context) { inst -> setInstrument(inst) }.apply { instrument = settings.instrument }
            addView(instrumentSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = px(4); marginEnd = px(4)
            })
            addView(ui.chip("🔗") { showLinkRows(linkRow.visibility != View.VISIBLE) },
                LinearLayout.LayoutParams(px(42), px(40)).apply { marginEnd = px(4) })
            // The whole bouzouki, sideways (turning the phone does it too). A long press says so.
            addView(ui.chip("🪕") { whole.turnSideways() }.apply {
                contentDescription = "Whole bouzouki, sideways"
                if (Build.VERSION.SDK_INT >= 26) tooltipText = "The whole bouzouki, sideways (or turn the phone)"
            },
                LinearLayout.LayoutParams(px(38), px(40)).apply { marginEnd = px(3) })
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
        // In a Dock, so it can move into the sideways screen and back without stopping.
        controls.addView(Dock(this).apply { addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(170))) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        whole = SongLandscape(this, audio.synth, settings, web, { titleText.text }, { status.text }, { fileUri != null }, { playing }) { toggleFilePlayback() }
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
        // Turns while a line is worked out in the background (more notes, or a tab lined up).
        statusSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply { visibility = View.GONE }
        controls.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusSpinner, LinearLayout.LayoutParams(px(16), px(16)).apply { marginStart = px(4) })
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        // The note now, the dromos and the tab; speed, how many notes, and sound.
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
        tabButton = ui.chip("📝 Tab") { showTabMenu() }
        fun wrapped() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = px(3); marginEnd = px(3)
        }
        controls.addView(ui.row().apply {
            // A fixed size: a ♯/♭ comes from a taller fallback font, and a growing row would make
            // the neck below re-fit (and jump) at every accidental.
            addView(noteText, LinearLayout.LayoutParams(px(64), px(40)))
            addView(dromosText, ui.weighted(3f))
            addView(useDromosButton, wrapped())
            addView(tabButton, wrapped())
        })
        speedSwitch = Segmented(this, "Speed", SPEEDS.map { if (it == 1f) "1×" else "$it×".replace("0.", ".") }) { k -> setSpeed(SPEEDS[k]) }
        detailSwitch = Segmented(this, "Notes", listOf("fewer", "normal", "more")) { k -> pickDetail(Detail.entries[k]) }
        soundButton = ui.chip(SOUND_LABEL) { bouzoukiSound = !bouzoukiSound; pluckReset = true; refreshButtons() }.apply {
            // One line, smaller when a big system font leaves it too little room beside the switches.
            maxLines = 1
            contentDescription = "Bouzouki sound"
            if (Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val speedParams = wrapped()
        val detailParams = wrapped()
        controls.addView(object : LinearLayout(this) {
            // A big system font makes the switches want the whole row: they give up width (their labels
            // shrink) so the sound chip keeps room for its icon at least, and shows just that.
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                speedParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                detailParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                val short = px(MIN_SOUND_DP) - soundButton.measuredWidth
                val narrow = short > 0
                if (narrow != soundIconOnly) {
                    soundIconOnly = narrow
                    soundButton.text = if (narrow) "🔊" else SOUND_LABEL
                }
                if (!narrow) return
                // The switches share what's left as their full widths do (measured unhindered: the first pass
                // gave the second only what the first left).
                val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                speedSwitch.measure(free, free)
                detailSwitch.measure(free, free)
                val a = speedSwitch.measuredWidth
                val b = detailSwitch.measuredWidth
                var room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - px(MIN_SOUND_DP)
                for (k in 0 until childCount) (getChildAt(k).layoutParams as MarginLayoutParams).let { room -= it.marginStart + it.marginEnd }
                room = room.coerceIn(0, a + b)
                speedParams.width = room * a / (a + b).coerceAtLeast(1)
                detailParams.width = room - speedParams.width
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL // as ui.row()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(3), 0, px(3))
            addView(speedSwitch, speedParams)
            addView(detailSwitch, detailParams)
            addView(soundButton, ui.weighted(1f))
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
        fitSystemBars(this, root)
        showPage(prefs.getInt(PREF_PAGE, PAGE_NECK))
        showSync()
        refreshButtons()
        showDromos()
        refreshLineControls()
        SongLines.listen(lineListener)
        status.text = "Paste a YouTube link (or Share → Μπουζούκι in the YouTube app). The first time, a song takes a few minutes to prepare; after that it opens instantly."

        // Back to a song that's being prepared (e.g. from the notification).
        (SongJobs.state as? SongJobs.State.Working)?.let { w ->
            if (!w.id.startsWith("file-")) openYouTube(w.id, startJob = false)
        }
        handleShare(intent)
        whole.follow(resources.configuration)
    }

    // Turned sideways (or upright again): the screen isn't made again (the manifest says so), it switches.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        whole.follow(newConfig)
    }

    // Back while the whole bouzouki shows turns the screen upright, as ⤺ does, instead of closing the song.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!whole.back()) @Suppress("DEPRECATION") super.onBackPressed()
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
        if (id != currentId) pendingTab = null
        currentId = id
        currentSource = SongJobs.Source.YouTube(id)
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
        if (id != currentId) pendingTab = null
        currentId = id
        currentSource = SongJobs.Source.AudioFile(uri, name)
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

    /** Asks (once, on Android 13+) to show the preparing song's progress notification; optional. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun prepareOrShow(id: String, source: SongJobs.Source, startJob: Boolean) {
        showSong(null)
        val saved = SongJobs.cached(this, id)
        if (saved != null) { showSong(saved); return }
        val s = SongJobs.state
        if (s is SongJobs.State.Working && s.id == id) return // already on it
        if (!startJob) return
        if (SongJobs.isBusy()) {
            status.text = "Another song is still being prepared. Wait for it to finish, or cancel it."
            return
        }
        askForNotifications()
        SongJobs.start(this, source, settings.instrument)
    }

    /**
     * Switches the bouzouki: the neck, and the song (if one is open) fingered again for it at once,
     * on the neck, the tab strip and the tablature, from where it is now. Remembered for next time.
     */
    private fun setInstrument(inst: Instrument) {
        settings.instrument = inst
        instrumentSwitch.instrument = inst
        if (board.instrument != inst) board.instrument = inst
        whole.bouzouki.instrument = inst
        val src = source
        if (src != null) {
            showLine() // the note now and the next one, on the new neck
            sheet.showNow()
        }
        // Once the neck has its new frets, bring the note playing back into view.
        board.post { board.liveCell?.let { keepVisible(it) } }
        val courses = inst.courses.joinToString(" ") { settings.names.name(it.openMidi) }
        status.text = "${inst.greekName} ($courses)" + if (src != null) ": the song is fingered for it now." else "."
        // A tab's line is lined up again for this bouzouki, so it keeps the tab's frets on it too.
        pendingTab = pendingTab?.copy(instrument = inst)
        if (src != null && (wanted ?: lineSpec) is SongLines.Spec.FromTab) chooseLine(announce = false)
    }

    private fun onJob(s: SongJobs.State) {
        val id = currentId
        when (s) {
            is SongJobs.State.Working -> if (s.id == id) {
                awaited = id
                prepPanel.visibility = View.VISIBLE
                prepText.text = "Preparing the song: ${s.step}…  ${(s.fraction * 100).toInt()}%"
                prepBar.progress = (s.fraction * 1000).toInt()
                status.text = "You can watch the video meanwhile, or leave the app: it keeps working."
            }
            // (A finished job's state is told again each time the screen comes back: shown only if it was awaited.)
            is SongJobs.State.Done -> if (s.song.id == id && (awaited == id || source == null)) {
                awaited = null
                showSong(s.song)
            }
            is SongJobs.State.Failed -> if (s.id == id) {
                awaited = null
                prepPanel.visibility = View.GONE
                status.text = "Couldn't prepare this song: ${s.message}\nYou can try again with Load, or use 📁 Audio file."
            }
            SongJobs.State.Idle -> prepPanel.visibility = View.GONE
        }
    }

    /**
     * Shows [prepared] (the song as it was prepared, or null for none), fingered for the bouzouki chosen
     * now, then the line chosen for it (more or fewer notes, or its tab's) once that's worked out.
     */
    private fun showSong(prepared: SongAnalysis?) {
        source = prepared
        if (prepared != null && prepared.id == awaited && (SongJobs.state as? SongJobs.State.Working)?.id != prepared.id) awaited = null
        line = null
        lineSpec = null
        tabReport = null
        wanted = null
        tabGaveWay = null
        busy(null)
        sheet.beats = if (prepared != null) beatsFor(prepared) else emptyList()
        showLine()
        val s = song
        if (s != null) {
            prepPanel.visibility = View.GONE
            if (s.title.isNotBlank()) titleText.text = s.title
            val spec = lineSpecFor(s.id)
            if (spec == null) {
                status.text = if (s.notes.isEmpty()) "No clear bouzouki line was found in this song."
                else "Ready: ${s.notes.size} bouzouki notes; the hollow ring shows the next. Turn the phone sideways (or 🪕) " +
                    "for the whole bouzouki. 📜 Tablature: the whole song, tap a note to play from there."
            }
            sheet.showNow()
            chooseLine(announce = false)
            // The line chosen for the song came at once (worked out before): say what's shown.
            if (spec != null && wanted == null && lineSpec == spec) status.text = lineText()
        }
        showDromos()
        refreshLineControls()
    }

    /**
     * Shows the line (the one chosen, else the prepared one) fingered for the bouzouki chosen now, with
     * the current fingering rules (saved songs too), on the neck, the tab strip and the tablature, from
     * where the song is now.
     */
    private fun showLine() {
        val shown = line ?: source
        val s = shown?.let { fingered(it) }
        song = s
        // A tab's line: its notes not heard in the recording show faint, those heard at another pitch marked
        // (by index: fingering it again keeps the notes in order).
        val r = tabReport?.takeIf { line != null && lineSpec is SongLines.Spec.FromTab && s != null && it.sources.size == s.notes.size }
        tab.unheard = r?.unheard ?: emptySet()
        tab.song = s
        sheet.unheard = r?.unheard ?: emptySet()
        sheet.offPitch = r?.offPitch ?: emptySet()
        sheet.song = s
        resetMarks()
        if (s != null) render(clock.now(SystemClock.elapsedRealtimeNanos()))
    }

    /**
     * [shown] for the bouzouki chosen now. A tab's line keeps the tab's frets on the bouzouki the tab
     * is for (it says where to play); a line just found again with fewer or more notes is fingered for
     * the bouzouki it was found for already; every other line is fingered again ([SongAnalysis.forInstrument]).
     */
    private fun fingered(shown: SongAnalysis): SongAnalysis {
        val inst = settings.instrument
        val asWritten = shown === line && lineSpec is SongLines.Spec.FromTab && shown.instrument == inst &&
            shown.notes.all { it.course in inst.courses.indices && it.fret in 0..inst.frets && inst.midiAt(it.course, it.fret) == it.midi }
        val fresh = shown === line && lineSpec is SongLines.Spec.Detected && shown.instrument == inst
        return if (asWritten || fresh) shown else shown.forInstrument(inst)
    }

    // --- the line: fewer or more notes, or a tab's ---

    /**
     * The line chosen for song [id]: a tab just pasted for it while it's tried ([pendingTab]); else its
     * tab's if one is kept and chosen (lined up for the bouzouki played now); else the detected one with
     * its detail (null for normal: the prepared line).
     */
    private fun lineSpecFor(id: String): SongLines.Spec? {
        pendingTab?.takeIf { it.id == id }?.let { return it }
        if (settings.usesTab(id)) {
            val text = SongJobs.tabText(this, id)
            if (text != null) return SongLines.Spec.FromTab(id, text, settings.instrument)
        }
        val d = settings.detail(id)
        return if (d == Detail.NORMAL) null else SongLines.Spec.Detected(id, d)
    }

    /** Shows the line chosen for the song open (see [showSpec]). */
    private fun chooseLine(announce: Boolean) {
        val src = source ?: return
        showSpec(lineSpecFor(src.id), announce)
    }

    /**
     * Shows line [spec] of the song open (null: the prepared line). One not worked out yet is worked out
     * in the background (the spinner turns meanwhile, and the line shown till then stays); [announce] says
     * what's shown once it is. It's remembered as the song's choice once it has come ([keep]).
     */
    private fun showSpec(spec: SongLines.Spec?, announce: Boolean) {
        val src = source ?: return
        if (spec == null) {
            wanted = null
            SongLines.settle(src.id)
            busy(null)
            if (line != null) {
                line = null
                lineSpec = null
                tabReport = null
                showLine()
            }
            if (announce) status.text = lineText()
            refreshLineControls()
            return
        }
        wanted = spec
        val ready = SongLines.request(this, src, spec)
        if (ready != null) onLine(ready, announce)
        else {
            busy(when (spec) {
                is SongLines.Spec.FromTab -> "Lining the tab up with the recording…"
                is SongLines.Spec.Detected -> "Finding the notes again: ${detailName(spec)}…"
            })
            refreshLineControls()
        }
    }

    /**
     * A line worked out in the background (or ready at once): shown if it's still the one wanted. A tab just
     * pasted is kept for the song only if its notes were read and found; how it lined up is reported.
     */
    private fun onLine(out: SongLines.Outcome, announce: Boolean = true) {
        val spec = out.spec
        if (spec != wanted) return // asked for before something else was, or for another song
        wanted = null
        busy(null)
        val pasted = spec is SongLines.Spec.FromTab && spec == pendingTab
        when (out) {
            is SongLines.Outcome.Line -> {
                val r = out.report
                if (spec is SongLines.Spec.FromTab && (r == null || r.matched == 0 || out.song.notes.isEmpty())) {
                    // Nothing of the tab could be read, or found. A tab just pasted is dropped (the tab kept
                    // before and the line chosen stay); a tab kept before gives way to the detected notes.
                    val unread = r == null || r.tab.notes.isEmpty()
                    if (pasted) pendingTab = null else settings.setUsesTab(spec.id, false)
                    chooseLine(announce = false)
                    status.text = when {
                        pasted && unread -> "No tab notes could be read from what was pasted: nothing changed."
                        pasted -> "None of the pasted tab's notes could be found in the recording: nothing changed."
                        unread -> "The tab kept for this song can't be read: the detected notes are shown."
                        else -> "None of the tab's notes could be found in the recording: the detected notes are shown."
                    }
                    if (pasted && r != null) showTabReport(r)
                    refreshLineControls()
                    return
                }
                var keepIt = true
                if (pasted && spec is SongLines.Spec.FromTab) {
                    pendingTab = null
                    try {
                        SongJobs.saveTab(this, spec.id, spec.text) // replaces the tab kept before
                    } catch (e: IOException) {
                        keepIt = false
                        status.text = "Couldn't keep the tab on the phone (${e.message}): it's shown till the song is closed."
                    }
                }
                if (keepIt) keep(spec)
                if (out.song !== line) {
                    line = out.song
                    lineSpec = spec
                    tabReport = r
                    showLine()
                }
                if (announce && keepIt) status.text = lineText()
                if (pasted && r != null) showTabReport(r)
            }
            is SongLines.Outcome.NoData -> {
                // Prepared before what tabs and detail levels need was kept, or that can't be read (it's
                // deleted then): a tab just pasted waits for the song prepared again.
                tabGaveWay = null
                if (announce) askToPrepareAgain() else status.text = PREPARE_AGAIN
            }
            is SongLines.Outcome.Failed -> {
                tabGaveWay = null
                val message = (if (spec is SongLines.Spec.FromTab) "Couldn't line the tab up: " else "Couldn't find the notes again: ") + out.message
                if (pasted) {
                    pendingTab = null
                    chooseLine(announce = false) // the line chosen before
                    AlertDialog.Builder(this).setTitle("📝 Tab").setMessage("$message\n\nNothing changed.").setPositiveButton("OK", null).show()
                }
                status.text = message
            }
        }
        refreshLineControls()
    }

    /** Remembers line [spec] as the one chosen for its song (once it has come). */
    private fun keep(spec: SongLines.Spec) {
        when (spec) {
            is SongLines.Spec.Detected -> {
                settings.setDetail(spec.id, spec.detail)
                settings.setUsesTab(spec.id, false)
            }
            is SongLines.Spec.FromTab -> settings.setUsesTab(spec.id, true)
        }
    }

    /** What the line shown is, in a sentence. */
    private fun lineText(): String {
        val s = song ?: return ""
        val spec = lineSpec
        val r = tabReport
        return when {
            spec is SongLines.Spec.FromTab && r != null -> buildString {
                append("From your tab: lined up ${r.matched} of ${r.total} tab notes.")
                if (sheet.unheard.isNotEmpty()) append(" Faint in 📜 Tablature: not heard.")
                if (sheet.offPitch.isNotEmpty()) append(" Marked $OFF_PITCH: heard at another pitch.")
            }
            spec is SongLines.Spec.Detected -> "${s.notes.size} bouzouki notes: ${detailName(spec)}." + tabKept()
            else -> "${s.notes.size} bouzouki notes." + tabKept()
        }
    }

    /** After the tab's notes gave way to the detected ones (once): that the tab is kept, and how to show it again. */
    private fun tabKept(): String {
        if (tabGaveWay == null || tabGaveWay != source?.id) return ""
        tabGaveWay = null
        return " Detected notes now; your tab is kept: 📝 Tab → From tab shows its notes again."
    }

    private fun detailName(spec: SongLines.Spec.Detected) = when (spec.detail) {
        Detail.FEWER -> "fewer, only the clearest"
        Detail.NORMAL -> "the usual detail"
        Detail.MORE -> "more, quieter and quicker ones too"
    }

    /** The spinner by the status line, turning with [text] while a line is worked out; null stops it. */
    private fun busy(text: String?) {
        statusSpinner.visibility = if (text != null) View.VISIBLE else View.GONE
        if (text != null) status.text = text
    }

    /** The Notes switch and the 📝 Tab button as the line shown (or coming) is. */
    private fun refreshLineControls() {
        val src = source
        val spec = wanted ?: lineSpec
        val fromTab = spec is SongLines.Spec.FromTab
        detailSwitch.available = src != null && SongJobs.hasData(this, src.id)
        detailSwitch.selected = when (spec) {
            is SongLines.Spec.FromTab -> -1
            is SongLines.Spec.Detected -> spec.detail.ordinal
            null -> Detail.NORMAL.ordinal
        }
        detailSwitch.caption = if (fromTab) "Notes · from the tab" else "Notes"
        ui.style(tabButton, fromTab)
    }

    /**
     * Fewer or more notes in the detected line; it replaces the tab's notes if those were shown. Remembered
     * for the song once that line has come.
     */
    private fun pickDetail(d: Detail) {
        val src = source
        if (src == null) {
            status.text = "Open a song first. Then: fewer notes keeps only the clearest, more adds quieter and quicker ones."
            return
        }
        if (!SongJobs.hasData(this, src.id)) { askToPrepareAgain(); return }
        pendingTab = null // a tab being tried gives way
        // The tab's notes shown give way: the status then says the tab is kept, and how to show them again.
        tabGaveWay = if (lineSpec is SongLines.Spec.FromTab && SongJobs.tabText(this, src.id) != null) src.id else null
        if (d == Detail.NORMAL) {
            settings.setDetail(src.id, d)
            settings.setUsesTab(src.id, false)
            showSpec(null, announce = true)
        } else {
            showSpec(SongLines.Spec.Detected(src.id, d), announce = true)
        }
    }

    /** 📝 Tab: find a tab online, paste one, choose between its notes and the detected ones, or remove it. */
    private fun showTabMenu() {
        val id = currentId
        if (id == null) { status.text = "Open a song first; then find its bouzouki tab online, copy it and paste it here."; return }
        val hasTab = SongJobs.tabText(this, id) != null
        val fromTab = (wanted ?: lineSpec) is SongLines.Spec.FromTab
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "🔎 Find a tab online" to { findTabOnline(greek = false) }
        items += "🔎 Βρες ταμπλατούρα (Greek search)" to { findTabOnline(greek = true) }
        items += (if (hasTab) "📋 Paste another tab" else "📋 Paste tab") to { pasteTab() }
        if (hasTab) {
            items += "From tab" + (if (fromTab) "  ✓" else "") to { chooseTab(true) }
            items += "Detected" + (if (fromTab) "" else "  ✓") to { chooseTab(false) }
            items += "🗑 Remove tab" to { removeTab() }
        }
        if (source != null) items += "📤 Send song data (to improve its notes)" to { sendSongData() }
        val r = tabReport
        val title = if (fromTab && r != null) "📝 Tab · ${r.matched} of ${r.total} notes lined up" else "📝 Bouzouki tab"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Shares the song's data (its notes and what the note detector heard, never audio) with the
     * playback time and a note from the player, so wrong or missing notes can be looked into.
     */
    private fun sendSongData() {
        val id = currentId ?: return
        val src = source ?: return
        val time = clock.now(SystemClock.elapsedRealtimeNanos())
        val note = EditText(this).apply {
            hint = "What's wrong? e.g. notes missing at ${SongExport.clock(time)}"
            setText("Around ${SongExport.clock(time)}: ")
            setSelection(text.length)
            setTextColor(WHITE)
            setHintTextColor(0xFF9FB4CC.toInt())
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(this).apply { setPadding(ui.px(20), 0, ui.px(20), 0); addView(note) }
        AlertDialog.Builder(this)
            .setTitle("📤 Send song data")
            .setMessage("Sends this song's notes and what the app heard (no audio), its link, any tab you added, " +
                "and the playback time (${SongExport.clock(time)}). Play to the part that's wrong first.")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                val yt = currentSource as? SongJobs.Source.YouTube
                val info = SongExport.Info(
                    id = id,
                    title = src.title.ifBlank { titleText.text.toString() },
                    link = yt?.let { "https://youtu.be/${it.videoId}" },
                    instrument = settings.instrument.greekName,
                    detail = settings.detail(id).name.lowercase(Locale.ROOT),
                    lineFrom = if (lineSpec is SongLines.Spec.FromTab) "tab" else "detected",
                    sync = syncOffset,
                    time = time,
                    note = note.text.toString(),
                )
                status.text = "Packing the song's data…"
                SongExport.share(this, info) { error -> status.text = error ?: "Pick where to send it (for example, upload it to the chat)." }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Opens a web search for the song's tab (in English or Greek) in the browser; the player copies it from there. */
    private fun findTabOnline(greek: Boolean) {
        val known = source?.title?.takeIf { it.isNotBlank() } ?: if (fileUri != null) titleText.text.toString() else null
        val title = known?.let { searchTitle(it) }?.takeIf { it.isNotBlank() }
        if (title == null) { status.text = "The song's title is known once it's prepared; search for its tab then."; return }
        val query = if (greek) "$title ταμπλατούρα μπουζούκι" else "$title bouzouki tab"
        val uri = Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q", query).build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            status.text = "Found one? Select the tab's lines, copy them, come back, and 📝 Tab → Paste tab."
        } catch (_: ActivityNotFoundException) {
            status.text = "No web browser found to search with."
        }
    }

    /** Pastes a tab from the clipboard, after a look at it. */
    private fun pasteTab() {
        val id = currentId ?: return
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.replace("\r\n", "\n")?.replace('\r', '\n')?.trim('\n')
        if (text.isNullOrBlank()) {
            status.text = "Nothing to paste. In the browser, select all the tab's lines, Copy, come back, then 📝 Tab → Paste tab."
            return
        }
        if (text.length > MAX_TAB_CHARS) { status.text = "That's too long for one song's tab: copy just the tab's lines."; return }
        val lines = text.lines()
        val preview = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFFE8EEF6.toInt())
            setPadding(ui.px(24), ui.px(4), ui.px(24), ui.px(4)) // in line with the dialog's text
            this.text = lines.take(PREVIEW_LINES).joinToString("\n") + if (lines.size > PREVIEW_LINES) "\n… (${lines.size - PREVIEW_LINES} more lines)" else ""
        }
        val box = ScrollView(this).apply { addView(HorizontalScrollView(this@SongActivity).apply { addView(preview) }) }
        // One view for the words and the preview (a message besides a view would let a long preview push the
        // buttons off a small screen): the preview gets the room the buttons leave, and scrolls.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SongActivity).apply {
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                setPadding(ui.px(24), ui.px(8), ui.px(24), ui.px(8))
                this.text = "It's lined up with the recording, and kept on this phone for this song if its notes are found there."
            })
            addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        AlertDialog.Builder(this)
            .setTitle("Paste this tab?")
            .setView(content)
            .setPositiveButton("Use this tab") { _, _ -> useTab(id, text) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Lines tab [text] up with song [id] and shows its notes. It's kept for the song (in place of the tab
     * kept before) only once they're read and found in the recording ([pendingTab], [onLine]).
     */
    private fun useTab(id: String, text: String) {
        if (id != currentId) return
        pendingTab = SongLines.Spec.FromTab(id, text, settings.instrument)
        if (source == null) {
            status.text = "The tab is lined up with the recording once the song is ready (leaving this screen meanwhile forgets it)."
            return
        }
        if (!SongJobs.hasData(this, id)) { askToPrepareAgain(); return }
        chooseLine(announce = true)
    }

    /** The tab's notes ([use]) or the detected ones, for the song open (remembered once they're shown). */
    private fun chooseTab(use: Boolean) {
        val id = currentId ?: return
        pendingTab = null
        if (source == null) { settings.setUsesTab(id, use); return }
        if (use) {
            if (!SongJobs.hasData(this, id)) { askToPrepareAgain(); return }
            val text = SongJobs.tabText(this, id) ?: return
            showSpec(SongLines.Spec.FromTab(id, text, settings.instrument), announce = true)
        } else {
            val d = settings.detail(id)
            if (d == Detail.NORMAL) settings.setUsesTab(id, false)
            showSpec(if (d == Detail.NORMAL) null else SongLines.Spec.Detected(id, d), announce = true)
        }
    }

    private fun removeTab() {
        val id = currentId ?: return
        AlertDialog.Builder(this)
            .setTitle("Remove the tab?")
            .setMessage("The tab pasted for this song is removed from the phone, and the detected notes are shown.")
            .setPositiveButton("Remove") { _, _ ->
                if (id == currentId) {
                    pendingTab = null
                    SongJobs.removeTab(this, id)
                    settings.setUsesTab(id, false)
                    chooseLine(announce = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * How a tab just pasted lined up: how many of its notes were found (and what shows where they weren't),
     * those heard at another pitch, a key or an octave apart, and what couldn't be read.
     */
    private fun showTabReport(r: SongLines.TabReport) {
        val tab = r.tab
        val message = buildString {
            when {
                tab.notes.isEmpty() -> append("No tab notes could be read from what was pasted, so nothing changed. Copy just the tab's lines, one per course (like D|--5-7--|).")
                r.matched == 0 -> append("None of the tab's ${r.total} notes could be found in the recording, so nothing changed. Is it a tab of this song?")
                else -> {
                    if (tab.unread > 0) {
                        append(if (tab.unread == 1) "1 line that looks like tab couldn't be read (below): its notes are missing. "
                            else "${tab.unread} lines that look like tab couldn't be read (below): their notes are missing. ")
                    }
                    append(if (tab.unread > 0) "Lined up ${r.matched} of the ${r.total} tab notes read." else "Lined up ${r.matched} of ${r.total} tab notes.")
                    val placed = r.sources.toHashSet()
                    val faint = r.notHeard.count { it in placed }
                    if (faint == 1) append(" The one not heard in the recording shows faint in 📜 Tablature, where the notes around it put it.")
                    if (faint > 1) append(" The $faint not heard in the recording show faint in 📜 Tablature, where the notes around them put them.")
                    val left = r.notHeard.size - faint
                    if (left > 0) append(" $left more ${if (left == 1) "wasn't" else "weren't"} placed: parts of the tab not found in the recording.")
                    val off = r.differs.size
                    if (off > 0) {
                        // A note a fret or two off is shown at the fret heard (the tab's is likely wrong); an octave off,
                        // or another note of its chord, as the tab writes it.
                        val moved = r.moved.size
                        val written = off - moved
                        append("\n\n" + (if (off == 1) "1 sounds" else "$off sound") + " at another pitch in the recording: marked $OFF_PITCH in 📜 Tablature")
                        when {
                            off == 1 && moved == 1 -> append(", and shown at the fret heard, a fret or two from the tab's (likely a wrong fret in the tab).")
                            off == 1 -> append(", and shown as the tab writes it (an octave off, or another note of its chord).")
                            written == 0 -> append(", and shown at the frets heard, a fret or two from the tab's (likely wrong frets in the tab).")
                            moved == 0 -> append(", and shown as the tab writes them (an octave off, or other notes of their chords).")
                            else -> append(". Shown at the fret${if (moved == 1) "" else "s"} heard: $moved a fret or two off (likely ${if (moved == 1) "a wrong fret" else "wrong frets"} " +
                                "in the tab). Shown as the tab writes ${if (written == 1) "it: 1 an octave off, or another note of its chord"
                                else "them: $written an octave off, or other notes of their chords"}.")
                        }
                        append(" Check by ear.")
                    }
                    if (r.transpose != 0) {
                        val n = abs(r.transpose)
                        append("\n\nThe recording is $n semitone${if (n > 1) "s" else ""} ${if (r.transpose > 0) "higher" else "lower"} than the tab: ")
                        append("its notes are moved to match, so the frets shown differ from the tab's.")
                    }
                    if (r.octave != 0) {
                        val n = abs(r.octave)
                        append("\n\nThe recording sounds ${if (n == 1) "an octave" else "$n octaves"} ${if (r.octave > 0) "higher" else "lower"} than the tab is written: the tab's frets are kept.")
                    }
                    if (r.sources.any { it < 0 }) append("\n\nWhere the tab isn't heard, the detected notes stay.")
                    if (tab.instrument != settings.instrument) append("\n\nRead as a tab for the ${tab.instrument.greekName}.")
                }
            }
            if (tab.warnings.isNotEmpty()) {
                append("\n")
                for (w in tab.warnings.take(MAX_WARNINGS)) append("\n• ").append(w)
                if (tab.warnings.size > MAX_WARNINGS) append("\n• … and ${tab.warnings.size - MAX_WARNINGS} more")
            }
        }
        AlertDialog.Builder(this).setTitle("📝 Tab").setMessage(message).setPositiveButton("OK", null).show()
    }

    /** Asks to prepare a song again that was prepared before what tabs and detail levels need was kept. */
    private fun askToPrepareAgain() {
        status.text = PREPARE_AGAIN
        AlertDialog.Builder(this)
            .setTitle("Prepare again?")
            .setMessage("This song was prepared before tabs and detail levels came. Prepare again to use tabs and detail levels: it takes a few minutes, like the first time. A pasted tab is kept.")
            .setPositiveButton("Prepare again") { _, _ -> prepareAgain() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun prepareAgain() {
        val src = currentSource ?: return
        if (SongJobs.isBusy()) { status.text = "Another song is still being prepared. Wait for it to finish, or cancel it."; return }
        askForNotifications()
        SongJobs.start(this, src, settings.instrument) // the song shown stays until the new one is ready
    }

    /** Forgets what the neck and the bouzouki sound showed and played, for a new or newly fingered song. */
    private fun resetMarks() {
        lastNote = -1
        lastNext = -1
        lastFrameMedia = Double.NaN
        pluckReset = true
        board.liveCell = null
        board.nextCell = null
        board.legatoFrom = null
        whole.live(null, null, false)
        whole.next(null)
        noteText.text = "–"
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
        val inst = s.instrument
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
        val s = source // the song's, whichever line is shown
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
        val s = source ?: return
        val d = s.dromos ?: return
        board.dromos = d
        board.rootPc = s.rootPc
        whole.bouzouki.dromos = d
        whole.bouzouki.rootPc = s.rootPc
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
        if (s != null && clock.playing) { restartSound(s, target + syncOffset); pluckReset = false } else pluckReset = true
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
        val s = song ?: return whole.status(null, media, false)
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
                // Hammered, pulled or slid from the note before: the neck shows the finger's way there.
                board.legatoFrom = if (s.joinsPrevious(i)) s.notes[i - 1].let { Cell(it.course, it.fret) } else null
                board.liveCell = cell
                whole.live(board.legatoFrom, cell, n.articulation == Articulation.TREMOLO)
                noteText.text = settings.names.name(n.midi)
                keepVisible(cell)
            } else {
                board.legatoFrom = null
                board.liveCell = null
                whole.live(null, null, false)
                noteText.text = "–"
            }
        }
        val next = s.nextAfter(t)
        if (next != lastNext) {
            lastNext = next
            board.nextCell = s.notes.getOrNull(next)?.let { Cell(it.course, it.fret) }
            whole.next(board.nextCell)
        }
        whole.status(noteText.text, media, clock.playing)
    }

    /**
     * The bouzouki sound: plucks each note a little ahead of time [t] (the synth takes a moment
     * to reach the speaker), once, and never a note the song is already well into. A hammer-on,
     * pull-off or slide moves the string still ringing from the note before instead of picking
     * it (a slide sets off a little early, to arrive on time), and a tremolo note is picked again
     * about [TREMOLO_HZ] times a second for as long as it lasts.
     */
    private fun pluck(s: SongAnalysis, t: Double) {
        // Not while the audio engine is stopped (paused but visible): the plucks would pile up and burst out on resume.
        if (!bouzoukiSound || !clock.playing || !soundOn) { pluckReset = true; return }
        if (clock.holding) return // a seek on its way: nothing before the player gets there
        if (pluckReset) { restartSound(s, t); pluckReset = false }
        val ahead = t + PLUCK_LEAD * clock.rate
        val inst = s.instrument
        while (nextPluck < s.notes.size) {
            val i = nextPluck
            val n = s.notes[i]
            val legato = if (lastSounded == i - 1) s.legatoInto(i) else null
            val early = if (legato == Articulation.SLIDE)
                0.5 * BouzoukiSynth.slideSeconds(n.midi - s.notes[i - 1].midi) * clock.rate else 0.0
            if (n.start - early > ahead) break
            if (ahead - n.start < 0.25 && n.course in inst.courses.indices) sound(s, i, legato)
            else if (i > tremoloNote) tremoloNote = -1 // a later note has come (too late to sound): the tremolo is over
            nextPluck++
        }
        // Tremolo: down and up strokes, evenly in real time whatever the speed.
        val tr = tremoloNote
        if (tr >= 0 && tr < s.notes.size) {
            val n = s.notes[tr]
            // Over when its next stroke, or the song itself (after a stall), reaches the note's end.
            if (nextTremolo >= n.end - 0.03 || ahead >= n.end - 0.03) tremoloNote = -1
            else if (ahead >= nextTremolo && n.course in inst.courses.indices) {
                tremoloUp = !tremoloUp
                audio.synth.pluck(n.course, inst.midiAt(n.course, n.fret), inst.courses[n.course].octavePair, if (tremoloUp) 0.62f else 0.74f)
                val step = clock.rate / TREMOLO_HZ
                nextTremolo += step
                if (nextTremolo < ahead) nextTremolo = ahead + step / 2 // after a stall, no burst of the strokes missed
            }
        }
    }

    /**
     * Plays note [i]: moves the string still ringing from the note before as the [legato]
     * articulation says (see [legatoInto]), or picks it when that's null.
     */
    private fun sound(s: SongAnalysis, i: Int, legato: Articulation?) {
        val n = s.notes[i]
        val inst = s.instrument
        val midi = inst.midiAt(n.course, n.fret)
        val octave = inst.courses[n.course].octavePair
        val kind = when (legato) {
            Articulation.HAMMER -> BouzoukiSynth.Legato.HAMMER
            Articulation.PULL -> BouzoukiSynth.Legato.PULL
            Articulation.SLIDE -> BouzoukiSynth.Legato.SLIDE
            else -> null
        }
        if (kind != null) audio.synth.legato(n.course, midi, octave, kind, 0.8f)
        else audio.synth.pluck(n.course, midi, octave, if (n.articulation == Articulation.TREMOLO) 0.74f else 0.85f)
        lastSounded = i
        if (n.articulation == Articulation.TREMOLO) {
            tremoloNote = i
            tremoloUp = false
            nextTremolo = n.start + clock.rate / TREMOLO_HZ
        } else {
            tremoloNote = -1
        }
    }

    /**
     * The bouzouki sound starts again from time [t] (song time), after a jump: from the next note,
     * or within a tremolo note already under way, which simply goes on being picked from here.
     */
    private fun restartSound(s: SongAnalysis, t: Double) {
        nextPluck = s.nextAfter(t)
        lastSounded = -1
        tremoloNote = -1
        val now = s.noteAt(t)
        if (now >= 0 && s.notes[now].articulation == Articulation.TREMOLO && s.notes[now].end - t > 0.15) {
            tremoloNote = now
            lastSounded = now
            nextTremolo = t
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
        speedSwitch.selected = SPEEDS.indexOf(speed)
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
        soundOn = true
        SongJobs.listen(jobListener)
    }

    override fun onPause() {
        SongJobs.unlisten(jobListener)
        soundOn = false
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
        SongLines.unlisten(lineListener) // a line still being worked out is kept for next time, not shown here
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
        /** Strokes per second of the bouzouki sound's tremolo. */
        private const val TREMOLO_HZ = 12.0
        /** A tapped note plays from this many seconds before it (a little more than YouTube's time step). */
        private const val NOTE_PREROLL = 0.3
        /** The playback speeds offered. */
        private val SPEEDS = listOf(0.5f, 0.75f, 1f)
        /**
         * The longest text taken for a tab: a long song's tab is a few thousand characters (and lining up
         * takes time and memory growing faster than its notes; see [SongLines.MAX_TAB_NOTES]).
         */
        private const val MAX_TAB_CHARS = 30_000
        /** Lines of a tab shown before pasting it. */
        private const val PREVIEW_LINES = 40
        /** Problems reading a tab listed in its report. */
        private const val MAX_WARNINGS = 4
        /** The sound chip's label, and the least width it keeps beside the switches (dp; then it shows the icon only). */
        private const val SOUND_LABEL = "🔊 Bouzouki"
        private const val MIN_SOUND_DP = 64
        /** By a tab note heard at another pitch, in the tablature. */
        private const val OFF_PITCH = "?"
        private const val PREPARE_AGAIN = "Prepare again to use tabs and detail levels."

        private val ID = Regex("""(?:v=|youtu\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})""")
        private val BRACKETS = Regex("""[(\[{【][^)\]}】]*[)\]}】]""")
        private val NOISE = Regex("""(?<![\p{L}\p{N}])(official|video|videoclip|clip|lyrics?|audio|hd|hq|4k|remaster(ed)?|στίχοι|βίντεο)(?![\p{L}\p{N}])""",
            RegexOption.IGNORE_CASE) // Greek capitals too: Kotlin's Regex makes it Unicode-aware

        /** A video title as a search for its tab wants it: without "(Official Video)", "[HD]", "| Lyrics" and the like. */
        fun searchTitle(title: String): String = title
            .replace(BRACKETS, " ")
            .replace(NOISE, " ")
            .replace(Regex("""[|_•·"«»“”]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '—', ':')

        /** The 11-character video id from any kind of YouTube link (or a bare id). */
        fun youTubeId(text: String): String? {
            val t = text.trim()
            ID.find(t)?.let { return it.groupValues[1] }
            return t.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
        }
    }
}
