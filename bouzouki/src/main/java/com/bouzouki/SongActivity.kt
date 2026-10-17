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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
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
import com.bouzouki.core.song.ImageTab
import com.bouzouki.core.song.ParsedTab
import com.bouzouki.core.song.PdfTab
import com.bouzouki.core.song.SongAnalysis
import com.bouzouki.core.song.TabParser
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Play along with a song: give it an audio file of yours (or a clip heard with 🎧 Listen), it
 * prepares the song once on the phone — separating the instruments and transcribing the bouzouki
 * line — and then plays it with that line marked on the neck (with a moving tab strip) or on the
 * whole song's tablature. A song whose audio isn't on the phone (a clip, which is deleted once its
 * tab is made, or a file that can't be read any more) plays on the app's bouzouki sound, playing its tab.
 * A [PlaybackClock] smooths the player's coarse time so both follow it at
 * every frame, with a per-song sync offset. The title bar's switch fingers the song for the
 * τετράχορδο or the τρίχορδο at once, and the bouzouki sound plays hammer-ons, pull-offs and
 * slides without picking again, and tremolo notes picked fast for as long as they last.
 * Each song's line can have fewer or more notes (Notes), or be made of the notes of a tab (📝 Tab:
 * pasted in, or a PDF or text file of yours opened here or shared from another app), lined
 * up with the recording; both are worked out in the background by [SongLines] and remembered per
 * song. A record tuned clearly away from A = 440 Hz says so, to tune the bouzouki to it. Turned
 * sideways (or with 🪕) it shows the whole bouzouki ([SongLandscape]), following the same clock.
 * Songs and tabs are kept only in the app's own storage on this phone; nothing is shared or uploaded.
 */
class SongActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private val settings by lazy { Settings(this) }
    private val audio = AudioEngine()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var board: FretboardView
    private lateinit var scroller: ScrollView
    private lateinit var titleText: TextView
    private lateinit var extraRow: LinearLayout
    /** Where a video would go on the sideways screen: there is none, so it stays hidden (the title and ▶ show). */
    private lateinit var video: View
    /** The screen turned sideways: the whole bouzouki. */
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
    private lateinit var tuningText: TextView
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
     * What's loaded: a song id (an audio file's, or a clip's), and once it's prepared the song as it was
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
     * for meanwhile. [pendingTab] is a tab just pasted or opened (text or PDF): it's kept for its song
     * (replacing the tab kept before) only once its notes are read and found in the recording; till
     * then, and if they aren't, the tab kept before and the line chosen stay as they were.
     */
    private var line: SongAnalysis? = null
    private var lineSpec: SongLines.Spec? = null
    private var tabReport: SongLines.TabReport? = null
    private var wanted: SongLines.Spec? = null
    private var pendingTab: SongLines.Spec.FromTab? = null
    /**
     * A tab that came in (shared, or a file read) while it's offered for a song, its dialog up; it comes back
     * with the screen when that's recreated (see [onSaveInstanceState]).
     */
    private var offered: TabFiles.Incoming? = null
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
    private var playing = false // the audio file's player, or the bouzouki sound playing the tab alone
    private var filePrepared = false
    /**
     * Playing the tab on the bouzouki sound alone (no audio for the song on the phone): the song time at
     * [virtualStart] (elapsed-realtime nanos), going on at [speed] while [playing].
     */
    private var virtualBase = 0.0
    private var virtualStart = 0L

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened straight from a share or a notification: the Terms of Use come first here too.
        if (!Legal.accepted(this)) Legal.requireTerms(this)
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
            addView(ui.chip("➕") { showLinkRows(extraRow.visibility != View.VISIBLE) },
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

        // A clip heard on the phone, an audio file, or a saved song.
        extraRow = ui.row().apply {
            addView(ui.chip("📁 Audio file") { pickFile() }, ui.weighted())
            addView(ui.chip("🕘 My songs") { showSaved() }, ui.weighted())
        }
        controls.addView(extraRow)

        video = View(this).apply { visibility = View.GONE }
        controls.addView(Dock(this).apply { addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        whole = SongLandscape(this, audio.synth, settings, video, { titleText.text }, { status.text }, { source != null || fileUri != null }, { playing }) { toggleFilePlayback() }
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
        // How far the record is tuned from A = 440 Hz, only when that's clearly off (old records often are).
        tuningText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFE082.toInt())
            // One line, smaller when a big system font leaves it too little room; tapped, it says more.
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            setPadding(px(4), 0, px(4), px(2))
            visibility = View.GONE
            setOnClickListener { explainTuning() }
        }
        controls.addView(tuningText)
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
        status.text = "Open 📁 an audio file of yours. Preparing it takes a minute or " +
            "two, on the phone; after that it opens instantly from 🕘 My songs."

        live = WeakReference(this)
        // Back to a song that's being prepared (e.g. from the notification).
        (SongJobs.state as? SongJobs.State.Working)?.let { w -> openSaved(w.id) }
        // Recreated (dark mode, font size, or back after the app was closed): the song open, and a tab that came in for it.
        if (savedInstanceState != null) restore(savedInstanceState)
        TabFiles.sweep(this)
        handleShare(intent, fresh = savedInstanceState == null)
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
        handleShare(intent, fresh = true)
    }

    /**
     * What another app shared with this screen (or opened with it): a tab of yours, as a PDF, text file or
     * picture, or as a tab's text (several lines that look like tab). A tab is offered for a song.
     */
    private fun handleShare(intent: Intent?, fresh: Boolean) {
        intent ?: return
        // A tab isn't offered again when the screen is recreated or comes back from the recent apps: one still being
        // offered comes back with the screen ([restore]), and a shared file's permission to read it is gone by then.
        val again = !fresh || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val file = sharedFile(intent)
        if (file != null) {
            if (!again) receiveTabFile(file, intent.type, fromMenu = false)
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (again) return
        if (isTab(shared)) offerTab(TabFiles.Incoming.Text("", shared)) // no file name
        else status.text = "That isn't a tab: share a tab's lines (like D|--5-7--|), a tab PDF or a picture of a tab."
    }

    /**
     * Whether shared [text] is a tab: the tab reader finds notes in it (in as much of it as a tab
     * can have; a text longer than that is then too long, which the look at it says).
     */
    private fun isTab(text: String) = TabParser.parse(text.take(MAX_TAB_CHARS), settings.instrument).notes.isNotEmpty()

    /** The file shared with this screen (Share → Μπουζούκι) or opened with it (Open with), if any. */
    private fun sharedFile(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> {
            val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            // Some apps put the file only in the clip (where its read permission comes with it).
            stream ?: intent.clipData?.takeIf { it.itemCount > 0 && !intent.hasExtra(Intent.EXTRA_TEXT) }?.getItemAt(0)?.uri
        }
        else -> null
    }

    // --- loading a song ---

    /**
     * Opens saved song [id] (or the one being prepared): with its audio file when the phone still lets the app read it
     * ([PREF_URI]), else on the bouzouki sound alone, playing its tab.
     */
    private fun openSaved(id: String) {
        val uri = prefs.getString(PREF_URI + id, null)?.let { Uri.parse(it) }
        if (uri != null && readable(uri)) {
            openFile(uri, prefs.getString(PREF_NAME + id, null) ?: "Audio file", startJob = false)
            return
        }
        stopFile()
        fileUri = null
        if (id != currentId) dropPendingTab()
        currentId = id
        currentSource = null
        prefs.edit().putString(PREF_LAST_SONG, id).apply() // offered first for a tab shared later
        loadSync(id)
        showLinkRows(false) // more room for the neck; ➕ brings them back
        fileRow.visibility = View.VISIBLE
        prepareOrShow(id, null, startJob = false)
    }

    /** Whether the app may still read [uri] (a file picked before; the phone can take that back). */
    private fun readable(uri: Uri) = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) { false }

    private fun openFile(uri: Uri, name: String, startJob: Boolean = true, solo: Boolean = prefs.getBoolean(PREF_SOLO + songId(uri), false)) {
        stopFile()
        fileUri = uri
        val id = songId(uri)
        if (id != currentId) dropPendingTab()
        currentId = id
        currentSource = SongJobs.Source.AudioFile(uri, name, solo)
        prefs.edit().putString(PREF_LAST_SONG, id).putString(PREF_URI + id, uri.toString()).putString(PREF_NAME + id, name)
            .putBoolean(PREF_SOLO + id, solo).apply()
        loadSync(id)
        showLinkRows(false) // more room for the neck; ➕ brings them back
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
        prepareOrShow(id, SongJobs.Source.AudioFile(uri, name, solo), startJob)
    }

    /** Asks (once, on Android 13+) to show the preparing song's progress notification; optional. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun prepareOrShow(id: String, source: SongJobs.Source?, startJob: Boolean) {
        showSong(null)
        val saved = SongJobs.cached(this, id)
        if (saved != null) {
            showSong(saved)
            if (SongAnalysis.isLineOld(saved) && SongJobs.hasData(this, id)) {
                // Saved by an older analysis: derive its line again from its data (a second or so), then show that.
                val app = applicationContext
                thread(isDaemon = true) {
                    val fresh = SongJobs.withCurrentLine(app, saved) ?: return@thread
                    runOnUiThread { if (!isFinishing && !isDestroyed && this@SongActivity.source === saved) showSong(fresh) }
                }
            }
            return
        }
        val s = SongJobs.state
        if (s is SongJobs.State.Working && s.id == id) return // already on it
        if (!startJob || source == null) return
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
                status.text = "You can leave the app meanwhile: it keeps working, on the phone."
            }
            // (A finished job's state is told again each time the screen comes back: shown only if it was awaited.)
            is SongJobs.State.Done -> if (s.song.id == id && (awaited == id || source == null)) {
                awaited = null
                showSong(s.song)
            }
            is SongJobs.State.Failed -> if (s.id == id) {
                awaited = null
                prepPanel.visibility = View.GONE
                status.text = "Couldn't prepare this song: ${s.message}\nTry again with ➕ → 📁 Audio file."
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
     * The line chosen for song [id]: a tab just pasted or opened for it while it's tried ([pendingTab]); else
     * its tab's if one is kept and chosen (lined up for the bouzouki played now; a PDF is only read then, in
     * the background); else the detected one with its detail (null for normal: the prepared line).
     */
    private fun lineSpecFor(id: String): SongLines.Spec? {
        pendingTab?.takeIf { it.id == id }?.let { return it }
        if (settings.usesTab(id)) {
            SongJobs.tab(this, id)?.let { return SongLines.Spec.FromTab(id, it, settings.instrument) }
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
                is SongLines.Spec.FromTab ->
                    if (spec.source is SongLines.TabSource.Pdf) "Reading the PDF and lining its tab up with the recording…"
                    else "Lining the tab up with the recording…"
                is SongLines.Spec.Detected -> "Finding the notes again: ${detailName(spec)}…"
            })
            refreshLineControls()
        }
    }

    /**
     * A line worked out in the background (or ready at once): shown if it's still the one wanted. A tab just
     * pasted or opened is kept for the song only if its notes were read and found (and, in place of a tab kept
     * before, only if a fair share of them were, or the player says so); how it lined up is reported.
     */
    private fun onLine(out: SongLines.Outcome, announce: Boolean = true) {
        val spec = out.spec
        if (spec != wanted) return // asked for before something else was, or for another song
        wanted = null
        busy(null)
        val pasted = spec is SongLines.Spec.FromTab && spec == pendingTab
        val pdf = spec is SongLines.Spec.FromTab && spec.source is SongLines.TabSource.Pdf
        when (out) {
            is SongLines.Outcome.Line -> {
                val r = out.report
                if (spec is SongLines.Spec.FromTab && (r == null || r.matched == 0 || out.song.notes.isEmpty())) {
                    // Nothing of the tab could be read, or found. A tab just pasted is dropped (the tab kept
                    // before and the line chosen stay); a tab kept before gives way to the detected notes.
                    val unread = r == null || r.tab.notes.isEmpty()
                    if (pasted) dropPendingTab() else settings.setUsesTab(spec.id, false)
                    chooseLine(announce = false)
                    status.text = when {
                        pasted && unread && pdf -> when (r?.let { TabFiles.pdfTrouble(it.tab) }) {
                            TabFiles.PdfTrouble.LOCKED -> "This PDF is locked with a password: nothing changed."
                            TabFiles.PdfTrouble.DAMAGED -> "This file is damaged, or not really a PDF: nothing changed."
                            null -> "No tab could be read from the PDF: nothing changed."
                        }
                        pasted && unread -> "No tab notes could be read from it: nothing changed."
                        pasted -> "None of the new tab's notes could be found in the recording: nothing changed."
                        unread -> "The tab kept for this song can't be read: the detected notes are shown."
                        else -> "None of the tab's notes could be found in the recording: the detected notes are shown."
                    }
                    if (pasted && r != null) showTabReport(r, pdf)
                    refreshLineControls()
                    return
                }
                val replaces = pasted && spec is SongLines.Spec.FromTab && SongJobs.hasTab(this, spec.id)
                // Few of its notes found where the song has a tab already (a tab of another song, likely): shown, but
                // kept in place of that one only if the player says so.
                val ask = replaces && r != null && weak(r)
                val kept = when {
                    ask -> false
                    pasted && spec is SongLines.Spec.FromTab -> keepPending(spec)
                    else -> { keep(spec); true }
                }
                if (out.song !== line) {
                    line = out.song
                    lineSpec = spec
                    tabReport = r
                    showLine()
                }
                if ((announce && kept) || ask) status.text = lineText()
                if (ask && r != null && spec is SongLines.Spec.FromTab) askToReplace(spec, r, pdf)
                else if (pasted && r != null) showTabReport(r, pdf, replaced = replaces && kept)
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
                    dropPendingTab()
                    chooseLine(announce = false) // the line chosen before
                    AlertDialog.Builder(this).setTitle("📝 Tab").setMessage("$message\n\nNothing changed.").setPositiveButton("OK", null).show()
                }
                status.text = message
            }
        }
        refreshLineControls()
    }

    /**
     * Forgets the tab being tried ([pendingTab]), which won't be kept, for [next] (another one to try, or none): a PDF
     * that came in for it is deleted.
     */
    private fun dropPendingTab(next: SongLines.Spec.FromTab? = null) {
        val file = (pendingTab?.source as? SongLines.TabSource.Pdf)?.file
        pendingTab = next
        if (file != null && file != (next?.source as? SongLines.TabSource.Pdf)?.file) TabFiles.release(this, file)
    }

    /**
     * Keeps the tab just tried, [spec], for its song in place of the tab kept before (text or PDF), and remembers its
     * line as the song's choice; false (and the status says so) if it couldn't be written.
     */
    private fun keepPending(spec: SongLines.Spec.FromTab): Boolean {
        if (pendingTab == spec) pendingTab = null
        val file = (spec.source as? SongLines.TabSource.Pdf)?.file
        return try {
            when (val t = spec.source) {
                is SongLines.TabSource.Text -> SongJobs.saveTab(this, spec.id, t.text)
                is SongLines.TabSource.Pdf -> SongJobs.keepTabPdf(this, spec.id, t.file)
            }
            keep(spec)
            true
        } catch (e: IOException) {
            status.text = "Couldn't keep the tab on the phone (${e.message}): it's shown till the song is closed."
            false
        } finally {
            if (file != null) TabFiles.release(this, file) // moved into place, or not kept
        }
    }

    /** Whether few of a tab's notes were found in the recording: too few to be sure it's a tab of this song. */
    private fun weak(r: SongLines.TabReport) = r.matched * 4 < r.total || r.matched < minOf(MIN_SURE_NOTES, (r.total + 1) / 2)

    /**
     * A tab just tried ([spec]) of which few notes were found, for a song that has a tab already: its line is shown,
     * and the player says which tab to keep (closing the question keeps the old one).
     */
    private fun askToReplace(spec: SongLines.Spec.FromTab, r: SongLines.TabReport, pdf: Boolean) {
        var answered = false
        AlertDialog.Builder(this)
            .setTitle(if (pdf) "📄 Keep this tab instead?" else "📝 Keep this tab instead?")
            .setMessage("Only ${r.matched} of its ${r.total} notes were found in the recording: is it a tab of this song? It's shown now; " +
                "the tab kept for this song stays unless you keep this one in its place.\n\n" + tabReportText(r, pdf, sure = false))
            .setPositiveButton("Keep this one") { _, _ ->
                answered = true
                if (pendingTab == spec && keepPending(spec)) status.text = lineText()
            }
            .setNegativeButton("Keep the old one") { _, _ -> answered = true; keepOldTab(spec) }
            .setOnDismissListener { if (!answered && !isChangingConfigurations && !isDestroyed) keepOldTab(spec) }
            .show()
    }

    /** The tab just tried ([spec]) isn't kept: the song's line goes back to the one chosen before. */
    private fun keepOldTab(spec: SongLines.Spec.FromTab) {
        if (pendingTab != spec) return
        dropPendingTab()
        chooseLine(announce = true)
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
        dropPendingTab() // a tab being tried gives way
        // The tab's notes shown give way: the status then says the tab is kept, and how to show them again.
        tabGaveWay = if (lineSpec is SongLines.Spec.FromTab && SongJobs.hasTab(this, src.id)) src.id else null
        if (d == Detail.NORMAL) {
            settings.setDetail(src.id, d)
            settings.setUsesTab(src.id, false)
            showSpec(null, announce = true)
        } else {
            showSpec(SongLines.Spec.Detected(src.id, d), announce = true)
        }
    }

    /**
     * 📝 Tab: paste one or open a tab file of yours (PDF, text or picture), choose between its notes and
     * the detected ones, or remove it. The tab stays on this phone, with its song.
     */
    private fun showTabMenu() {
        val id = currentId
        if (id == null) { status.text = "Open a song first; then paste its bouzouki tab here, or open a tab file of yours."; return }
        val kept = SongJobs.tab(this, id)
        val hasTab = kept != null
        val fromTab = (wanted ?: lineSpec) is SongLines.Spec.FromTab
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += (if (hasTab) "📋 Paste another tab" else "📋 Paste tab") to { pasteTab() }
        items += "📄 Open a tab file (PDF, text, picture or sheet music)" to { openTabFile() }
        if (hasTab) {
            val kind = if (kept is SongLines.TabSource.Pdf) " (PDF)" else ""
            items += "From tab$kind" + (if (fromTab) "  ✓" else "") to { chooseTab(true) }
            items += "Detected" + (if (fromTab) "" else "  ✓") to { chooseTab(false) }
            items += "🗑 Remove tab" to { removeTab() }
        }
        val r = tabReport
        val title = if (fromTab && r != null) "📝 Tab · ${r.matched} of ${r.total} notes lined up" else "📝 Bouzouki tab"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Pastes a tab from the clipboard, after a look at it. */
    private fun pasteTab() {
        val id = currentId ?: return
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            status.text = "Nothing to paste. Select all the tab's lines, Copy, come back, then 📝 Tab → Paste tab."
            return
        }
        offerTabText(id, text, "Paste this tab?")
    }

    /**
     * Shows tab [raw] (pasted, a text file's, or shared) before it's used for song [id]: "Use this tab" lines
     * it up ([useTab]); [other], if given, offers it for another song instead. [incoming] is the tab that came in
     * (a file or shared), if it did: [offered] while the dialog is up.
     */
    private fun offerTabText(id: String, raw: String, title: String, other: (() -> Unit)? = null, incoming: TabFiles.Incoming? = null) {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim('\n')
        if (text.isBlank()) { status.text = "There's no tab in it: it's empty."; settle(incoming); return }
        if (text.length > MAX_TAB_CHARS) { status.text = "That's too long for one song's tab: keep just the tab's lines."; settle(incoming); return }
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
                this.text = "It's lined up with the recording, and kept on this phone for this song if its notes are found there" +
                    (if (SongJobs.hasTab(this@SongActivity, id)) ", in place of the tab kept now." else ".")
            })
            addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        if (incoming != null) offered = incoming
        var answered = false
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setPositiveButton("Use this tab") { _, _ -> answered = true; useTab(id, SongLines.TabSource.Text(text)); settle(incoming) }
            .apply { if (other != null) setNeutralButton("Another song…") { _, _ -> answered = true; other() } }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (!answered) settle(incoming) }
            .show()
    }

    /**
     * Tab [tab] that came in is no longer offered (used for a song, or not wanted): forgotten as [offered], and a PDF's
     * copy deleted unless it's being tried for a song now. Not while the screen is being recreated: it comes back then.
     */
    private fun settle(tab: TabFiles.Incoming?) {
        tab ?: return
        if (isChangingConfigurations || isDestroyed) return
        if (offered === tab) offered = null
        val tried = (pendingTab?.source as? SongLines.TabSource.Pdf)?.file
        if (tab is TabFiles.Incoming.Pdf && tab.file != tried) TabFiles.release(this, tab)
    }

    /**
     * 📄 Open a tab file: a PDF, a text file or a picture (a screenshot of a tab), picked with the phone's file picker
     * (Files, Drive, Downloads, Photos…).
     */
    private fun openTabFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(TabFiles.PDF, "text/plain", "text/*", "image/*"))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_TAB)
        } catch (_: ActivityNotFoundException) {
            status.text = "No file picker found on this phone. Share the tab to Μπουζούκι from the app it's in instead."
        }
    }

    /**
     * Reads the tab file [uri] (of MIME [type], if known) at once, in the background, while the app may
     * read it. One picked from the song's 📝 Tab menu ([fromMenu]) is for that song; one shared from another
     * app is offered for a song ([offerTab]).
     */
    private fun receiveTabFile(uri: Uri, type: String?, fromMenu: Boolean) {
        status.text = "Reading the tab file…"
        val song = if (fromMenu) currentId else null
        TabFiles.read(this, uri, type) { result ->
            // The screen may have been recreated meanwhile (dark mode, font size): the new one takes the tab.
            val to = if (!isDestroyed) this else live?.get()?.takeIf { !it.isDestroyed }
            if (to == null || to.isFinishing) {
                result.getOrNull()?.let { TabFiles.release(applicationContext, it) }
                return@read
            }
            to.tabRead(result, song)
        }
    }

    /** A tab file read ([receiveTabFile]): for song [forSong] if it's still open (picked from its 📝 Tab menu), else offered. */
    private fun tabRead(result: Result<TabFiles.Incoming>, forSong: String?) {
        val tab = result.getOrElse { e ->
            status.text = e.message
            AlertDialog.Builder(this).setTitle("📝 Tab").setMessage(e.message).setPositiveButton("OK", null).show()
            return
        }
        status.text = "Got the tab “${tab.name}”."
        val id = currentId
        if (forSong == null || forSong != id) { offerTab(tab); return }
        when (tab) {
            is TabFiles.Incoming.Text -> offerTabText(id, tab.text, "Use this tab file?", incoming = tab)
            is TabFiles.Incoming.Pdf -> useTab(id, SongLines.TabSource.Pdf(tab.file))
        }
    }

    /**
     * A tab another app shared (a file, or a tab's text): offered for the song open, if there is one (the
     * player may pick another), else for one of the saved songs ([pickSongFor]).
     */
    private fun offerTab(tab: TabFiles.Incoming) {
        val id = currentId ?: run { pickSongFor(tab); return }
        val song = source?.title?.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: "this song"
        val other = { pickSongFor(tab) }.takeIf { savedSongs().any { it.id != id } }
        when (tab) {
            is TabFiles.Incoming.Text -> offerTabText(id, tab.text, "Use this tab for $song?", other, incoming = tab)
            is TabFiles.Incoming.Pdf -> {
                offered = tab
                var answered = false
                val instead = if (SongJobs.hasTab(this, id)) ", in place of the tab kept now," else ""
                AlertDialog.Builder(this)
                    .setTitle(if (tab.picture) "📄 Use this tab picture?" else "📄 Use this PDF tab?")
                    .setMessage("“${tab.name}” for $song: its notes are lined up with the recording, and it's kept for the song$instead if they're found there.")
                    .setPositiveButton("Use for this song") { _, _ -> answered = true; useTab(id, SongLines.TabSource.Pdf(tab.file)); settle(tab) }
                    .apply { if (other != null) setNeutralButton("Another song…") { _, _ -> answered = true; other() } }
                    .setNegativeButton("Cancel", null)
                    .setOnDismissListener { if (!answered) settle(tab) }
                    .show()
            }
        }
    }

    /**
     * Lets the player pick which saved song a tab that came in is for (not the one open), opens it, and
     * uses the tab for it (a tab's text after a look at it, as when it's pasted). With no saved song,
     * it says to prepare the song first.
     */
    private fun pickSongFor(tab: TabFiles.Incoming) {
        // The song opened last first: likely the one being played, whose tab was found just now.
        val last = prefs.getString(PREF_LAST_SONG, null)
        val songs = savedSongs().filter { it.id != currentId }.sortedByDescending { it.id == last }
        if (songs.isEmpty()) {
            settle(tab)
            status.text = "Prepare the song first, then share the tab again."
            AlertDialog.Builder(this)
                .setTitle("Prepare the song first")
                .setMessage("A tab goes with a prepared song. Prepare the song first (📁 Audio file) " +
                    "and let it get ready; then share the tab again, or open it with 📝 Tab → Open a tab file.")
                .setPositiveButton("OK") { _, _ -> showLinkRows(true) }
                .show()
            return
        }
        offered = tab
        var answered = false
        AlertDialog.Builder(this)
            .setTitle(if (tab.name.isEmpty()) "Which song is this tab for?" else "Which song is “${tab.name}” for?")
            .setItems(songs.map { if (it.current) it.title else "${it.title}  ·  prepare again" }.toTypedArray()) { _, i ->
                answered = true
                val s = songs[i]
                openSaved(s.id)
                when (tab) {
                    is TabFiles.Incoming.Text -> offerTabText(s.id, tab.text, "Use this tab for “${s.title}”?", incoming = tab)
                    is TabFiles.Incoming.Pdf -> { useTab(s.id, SongLines.TabSource.Pdf(tab.file)); settle(tab) }
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (!answered) settle(tab) }
            .show()
    }

    /** The saved songs (one whose audio isn't on the phone plays on the bouzouki sound). */
    private fun savedSongs() = SongJobs.recent(this)

    /**
     * Lines tab [tab] (text or a PDF) up with song [id] and shows its notes. It's kept for the song (in place of
     * the tab kept before) only once they're read and found in the recording ([pendingTab], [onLine]).
     */
    private fun useTab(id: String, tab: SongLines.TabSource) {
        if (id != currentId) {
            if (tab is SongLines.TabSource.Pdf) TabFiles.release(this, tab.file)
            return
        }
        dropPendingTab(SongLines.Spec.FromTab(id, tab, settings.instrument))
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
        dropPendingTab()
        if (source == null) { settings.setUsesTab(id, use); return }
        if (use) {
            if (!SongJobs.hasData(this, id)) { askToPrepareAgain(); return }
            val tab = SongJobs.tab(this, id) ?: return
            showSpec(SongLines.Spec.FromTab(id, tab, settings.instrument), announce = true)
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
            .setMessage("The tab kept for this song (pasted, or opened from a file) is removed from the phone, and the detected notes are shown.")
            .setPositiveButton("Remove") { _, _ ->
                if (id == currentId) {
                    dropPendingTab()
                    SongJobs.removeTab(this, id)
                    settings.setUsesTab(id, false)
                    chooseLine(announce = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * How a tab just pasted or opened lined up ([tabReportText]), and that it's kept in place of the tab kept for the
     * song before ([replaced]).
     */
    private fun showTabReport(r: SongLines.TabReport, pdf: Boolean = false, replaced: Boolean = false) {
        var message = tabReportText(r, pdf)
        if (replaced) {
            // Before the list of what couldn't be read, if there is one.
            val k = message.indexOf("\n\n• ").takeIf { it >= 0 } ?: message.length
            message = message.substring(0, k) + "\n\nKept for this song, in place of the tab kept before." + message.substring(k)
        }
        val title = when {
            !pdf -> "📝 Tab"
            PdfTab.sheetMusicSummary(r.tab) != null -> "📄 Tab from the sheet music"
            ImageTab.unsureIn(r.tab) != null -> "📄 Tab from the picture"
            else -> "📄 Tab from the PDF"
        }
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    /**
     * How a tab lined up: what was read from a PDF ([pdf]), how many of its notes were found (and what shows where
     * they weren't; with [sure], asks if it's a tab of this song when few were), those heard at another pitch, a key
     * or an octave apart, and what couldn't be read.
     */
    private fun tabReportText(r: SongLines.TabReport, pdf: Boolean, sure: Boolean = true): String {
        val tab = r.tab
        // A PDF that couldn't be opened (locked, damaged) or has no numbers to read (a scan, a photo) is explained,
        // not listed with the other problems.
        val trouble = if (pdf) TabFiles.pdfTrouble(tab) else null
        val scanned = pdf && trouble == null && tab.notes.isEmpty() && PdfTab.NO_NUMBERS in tab.warnings
        // Sheet music's summary of what was read opens the report instead of being listed.
        val summary = if (pdf) PdfTab.sheetMusicSummary(tab) else null
        // A tab read from pictures (a screenshot, a PDF of pictures): what it says of itself is in the summary.
        val picture = pdf && ImageTab.unsureIn(tab) != null
        val warnings = when {
            picture -> tab.warnings.filterNot {
                it == ImageTab.PICTURE || it == ImageTab.NO_STAFFS || it == ImageTab.NO_NUMBERS || ImageTab.unsureWarning(it)
            }
            trouble != null -> tab.warnings.filterNot { it == PdfTab.NO_NUMBERS || TabFiles.troubleWarning(it) }
            scanned -> tab.warnings - PdfTab.NO_NUMBERS - PdfTab.PICTURES
            else -> tab.warnings
        }.filterNot { it == summary }
        return buildString {
            if (pdf && tab.notes.isNotEmpty()) append(pdfSummary(tab)).append("\n\n")
            when {
                picture && tab.notes.isEmpty() -> append("No tab could be read from the picture, so nothing changed. The app reads clear " +
                    "pictures of printed tabs (screenshots, PDFs of tab pages): photos of paper and handwritten tabs can't be read.")
                pdf && tab.notes.isEmpty() -> {
                    append("No tab could be read from this PDF, so nothing changed.")
                    when {
                        trouble == TabFiles.PdfTrouble.LOCKED -> append(" It's locked with a password (or a lock the app can't open). " +
                            "Open it where you can, save or print a copy without the lock (Print → Save as PDF), and open that here.")
                        trouble == TabFiles.PdfTrouble.DAMAGED -> append(" The file is damaged, or isn't really a PDF. " +
                            "Download or save it again, and open the new copy here.")
                        scanned -> append(" It has no tab numbers or notes the app can read: it may be a scan or a photo. A PDF made on a computer " +
                            "(a tab or sheet music saved from Sibelius, MuseScore, Finale or Guitar Pro, or a web page's tab printed to PDF) can be read.")
                    }
                }
                tab.notes.isEmpty() -> append("No tab notes could be read from it, so nothing changed. A tab has one line per course (like D|--5-7--|): keep just the tab's lines.")
                r.matched == 0 -> append("None of the tab's ${r.total} notes could be found in the recording, so nothing changed. Is it a tab of this song?")
                else -> {
                    if (tab.unread > 0) {
                        append(if (tab.unread == 1) "1 line that looks like tab couldn't be read (below): its notes are missing. "
                            else "${tab.unread} lines that look like tab couldn't be read (below): their notes are missing. ")
                    }
                    append(if (tab.unread > 0) "Lined up ${r.matched} of the ${r.total} tab notes read." else "Lined up ${r.matched} of ${r.total} tab notes.")
                    if (sure && weak(r)) append(" Only a few were found: is it a tab of this song?")
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
                    // (A PDF's own warnings say which bouzouki its tab is for.)
                    if (tab.instrument != settings.instrument && !pdf) append("\n\nRead as a tab for the ${tab.instrument.greekName}.")
                }
            }
            if (warnings.isNotEmpty()) {
                append("\n")
                for (w in warnings.take(MAX_WARNINGS)) append("\n• ").append(w)
                if (warnings.size > MAX_WARNINGS) append("\n• … and ${warnings.size - MAX_WARNINGS} more")
            }
        }
    }

    /**
     * What was read from a PDF tab: "Read 240 notes from the PDF (4-course tab, 8 lines of music)."; from a picture,
     * how many of its numbers were hard to make out.
     */
    private fun pdfSummary(tab: ParsedTab): String {
        // Sheet music: its own summary ("Read 412 notes from the sheet music (treble clef, 2 sharps, …); strings and frets chosen by the app").
        PdfTab.sheetMusicSummary(tab)?.let { return "$it." }
        val n = tab.notes.size
        val lines = tab.notes.mapTo(HashSet()) { it.system }.size // when the reader numbers its systems
        val unsure = ImageTab.unsureIn(tab)
        return "Read $n note${if (n == 1) "" else "s"} from the ${if (unsure != null) "picture" else "PDF"} (${tab.instrument.courses.size}-course tab" +
            (if (lines > 1) ", $lines lines of music" else "") + ")" +
            (if (unsure != null && unsure > 0) "; $unsure number${if (unsure == 1) "" else "s"} unsure" else "") + "."
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
        val src = currentSource ?: run {
            status.text = "This song's audio isn't on the phone any more: prepare it again from 📁 Audio file."
            return
        }
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

    /** The row to open a song (a file, a saved song), folded away while a song is open. */
    private fun showLinkRows(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
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
        showTuning()
        if (s == null || d == null) {
            dromosText.text = "Neck: ${settings.names.name(board.rootPc)} ${board.dromos.title}"
            useDromosButton.visibility = View.GONE
            return
        }
        val same = d.semitones.contentEquals(board.dromos.semitones) && s.rootPc == board.rootPc
        dromosText.text = "Song sounds like ${settings.names.name(s.rootPc)} ${d.title}" + if (same) " ✓" else ""
        useDromosButton.visibility = if (same) View.GONE else View.VISIBLE
    }

    /**
     * The line under the dromos saying how the record is tuned, shown only when that's clearly off A = 440 Hz. The
     * bouzouki sound (and the notes tapped on the neck and the tablature) plays in the record's tuning, to sound with it.
     */
    private fun showTuning() {
        val cents = source?.tuningCents?.takeIf { it.isFinite() } ?: 0.0
        audio.synth.tuningCents = cents
        val line = tuningLine(cents)
        tuningText.text = line ?: ""
        tuningText.visibility = if (line != null) View.VISIBLE else View.GONE
    }

    /** The tuning line tapped: what it means, and how to tune to the record (by ear, or a tuner's A). */
    private fun explainTuning() {
        val cents = source?.tuningCents?.takeIf { it.isFinite() } ?: return
        val off = abs(cents).roundToInt()
        val flat = cents < 0
        val a = 440.0 * Math.pow(2.0, cents / 1200)
        AlertDialog.Builder(this)
            .setTitle("Tuning")
            .setMessage("This recording sounds $off cents ${if (flat) "lower" else "higher"} than standard pitch (A = 440 Hz; " +
                "a semitone is 100 cents), as old records often do.\n\nTo play along in tune, tune your bouzouki " +
                "${if (flat) "down" else "up"}${if (off < 30) " a little" else ""}: by ear to the record, or with a tuner set to A = ${a.roundToInt()} Hz. " +
                "The notes and frets shown stay the same, and the app's bouzouki sound already plays in the record's tuning.")
            .setPositiveButton("OK", null)
            .show()
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
        val songs = savedSongs()
        if (songs.isEmpty()) { status.text = "No saved songs yet. Prepared songs are kept here, on this phone only."; return }
        AlertDialog.Builder(this)
            .setTitle("My songs")
            .setItems(songs.map { if (it.current) it.title else "${it.title}  ·  prepare again" }.toTypedArray()) { _, i ->
                openSaved(songs[i].id)
            }
            .setNeutralButton("🗑 Delete…") { _, _ -> deleteSongs(songs) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Lets the player delete saved songs from the phone (their notes, tabs and settings; no audio is kept anyway). */
    private fun deleteSongs(songs: List<SongAnalysis.Saved>) {
        val picked = BooleanArray(songs.size)
        AlertDialog.Builder(this)
            .setTitle("Delete from this phone")
            .setMultiChoiceItems(songs.map { it.title }.toTypedArray(), picked) { _, i, on -> picked[i] = on }
            .setPositiveButton("Delete") { _, _ ->
                for ((i, s) in songs.withIndex()) if (picked[i]) {
                    SongJobs.delete(this, s.id)
                    prefs.edit().remove(PREF_URI + s.id).remove(PREF_NAME + s.id).remove(PREF_SYNC + s.id).remove(PREF_SOLO + s.id).apply()
                    if (s.id == currentId) { stopFile(); currentId = null; currentSource = null; showSong(null); titleText.text = "Τραγούδι · Play along" }
                }
                status.text = "Deleted ${picked.count { it }} song(s) from the phone."
            }
            .setNegativeButton("Cancel", null)
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
        // A tab file: read now, while the picker's permission to read it lasts (it's copied, not kept as a link).
        if (requestCode == REQ_TAB && resultCode == RESULT_OK && uri != null) { receiveTabFile(uri, data.type, fromMenu = true); return }
        if (requestCode != REQ_FILE || resultCode != RESULT_OK || uri == null) return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Audio file"
        askWhatsInIt(uri, name.substringBeforeLast('.'))
    }

    /** The song id of the audio file [uri]. */
    private fun songId(uri: Uri) = SongJobs.idFor(SongJobs.Source.AudioFile(uri, ""))

    /**
     * Asks what a new recording holds: the bouzouki alone (a practice take: transcribed as it is, every course followed,
     * each octave-paired course's pluck one note) or a band's song (the instruments separated first). A song prepared
     * before opens at once.
     */
    private fun askWhatsInIt(uri: Uri, name: String) {
        if (SongJobs.cached(this, songId(uri)) != null) { openFile(uri, name); return }
        AlertDialog.Builder(this)
            .setTitle("What's in “$name”?")
            .setItems(arrayOf("🪕 The bouzouki alone (my own recording)", "🎶 A song with a band")) { _, i -> openFile(uri, name, solo = i == 0) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleFilePlayback() {
        val p = player ?: return toggleVirtual()
        if (!filePrepared) { status.text = "The file is still loading…"; return }
        try {
            if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
        } catch (_: IllegalStateException) { status.text = "The file is still loading…" }
        tickFile()
        refreshButtons()
    }

    /**
     * ▶ for a song without audio on the phone: the tab plays on the bouzouki sound alone (switched on for it),
     * from where it is, until its last note has rung.
     */
    private fun toggleVirtual() {
        val s = song ?: run { status.text = "Open a song first."; return }
        val nanos = SystemClock.elapsedRealtimeNanos()
        if (playing) {
            virtualBase = virtualTime(nanos)
            playing = false
        } else {
            if (virtualBase >= virtualEnd(s)) virtualBase = 0.0
            virtualStart = nanos
            playing = true
            if (!bouzoukiSound) { bouzoukiSound = true; pluckReset = true }
            status.text = "Playing the tab on the bouzouki sound (this song's audio isn't kept on the phone)."
        }
        tickFile()
        refreshButtons()
    }

    /** Where the tab played alone is at [nanos] (song seconds). */
    private fun virtualTime(nanos: Long) = if (playing) virtualBase + (nanos - virtualStart) / 1e9 * speed else virtualBase

    /** When the tab played alone is over: its last note's end and a moment more. */
    private fun virtualEnd(s: SongAnalysis) = (s.notes.lastOrNull()?.end ?: 0.0) + 1.0

    private val fileTicker = Runnable { tickFile() }

    /** Reports the file's position (or the tab's, played alone) to the clock, ten times a second while it plays. */
    private fun tickFile() {
        main.removeCallbacks(fileTicker)
        val p = player
        if (p == null) {
            if (fileUri != null || currentId == null) return
            val nanos = SystemClock.elapsedRealtimeNanos()
            val s = song
            if (playing && (s == null || virtualTime(nanos) >= virtualEnd(s))) {
                virtualBase = s?.let { virtualEnd(it) } ?: 0.0
                playing = false
                refreshButtons()
            }
            onReport(virtualTime(nanos), playing, speed.toDouble(), nanos)
            if (playing) main.postDelayed(fileTicker, 100)
            return
        }
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
        virtualBase = 0.0
        // Whatever plays next starts from a stopped clock.
        clock.report(0.0, false, speed.toDouble(), SystemClock.elapsedRealtimeNanos())
        pendingSeek = Double.NaN
        shownSecond = -1
    }

    // --- playback and sync ---

    private fun setSpeed(r: Float) {
        val nanos = SystemClock.elapsedRealtimeNanos()
        if (player == null) { virtualBase = virtualTime(nanos); virtualStart = nanos } // the tab alone goes on from here
        speed = r
        player?.let { p -> try { p.playbackParams = PlaybackParams().setSpeed(r) } catch (_: Exception) {} }
        tickFile()
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
        val p = player
        if (p != null) p.takeIf { filePrepared }?.let { try { it.seekTo((target * 1000).toInt()) } catch (_: Exception) {} }
        else { virtualBase = target; virtualStart = nanos; pendingSeek = Double.NaN; tickFile() }
        render(target)
    }

    /** A time report from the player (the file, or the tab played alone), taken at [nanos]. */
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
        if (currentId != null) {
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
        if (playing) toggleFilePlayback()
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

    /**
     * Kept when the screen may be recreated (dark mode, font size, or the app closed in the background): the song open,
     * a tab that came in and is being tried for it, and one being offered.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentId?.let { outState.putString(STATE_SONG, it) }
        val p = pendingTab
        when (val t = p?.source) {
            is SongLines.TabSource.Pdf -> if (TabFiles.isIncoming(this, t.file)) outState.putString(STATE_PENDING_PDF, t.file.path)
            is SongLines.TabSource.Text -> outState.putString(STATE_PENDING_TEXT, t.text)
            null -> {}
        }
        if (p != null) outState.putString(STATE_PENDING_SONG, p.id)
        val o = offered
        when (o) {
            is TabFiles.Incoming.Pdf -> outState.putString(STATE_OFFER_PDF, o.file.path)
            is TabFiles.Incoming.Text -> if (o.text.length <= MAX_TAB_CHARS) outState.putString(STATE_OFFER_TEXT, o.text)
            null -> {}
        }
        if (o != null) outState.putString(STATE_OFFER_NAME, o.name)
    }

    /**
     * The screen recreated from [state] ([onSaveInstanceState]): the song open again, the tab being tried for it tried
     * again (or offered, if its song isn't open), and the tab being offered offered again.
     */
    private fun restore(state: Bundle) {
        state.getString(STATE_SONG)?.let { id ->
            if (id != currentId) openSaved(id)
        }
        fun pdf(path: String?) = path?.let { File(it) }?.takeIf { it.isFile && TabFiles.isIncoming(this, it) }?.also { TabFiles.hold(it) }
        val pendingSong = state.getString(STATE_PENDING_SONG)
        val pendingPdf = pdf(state.getString(STATE_PENDING_PDF))
        val pending = pendingPdf?.let { SongLines.TabSource.Pdf(it) } ?: state.getString(STATE_PENDING_TEXT)?.let { SongLines.TabSource.Text(it) }
        val name = state.getString(STATE_OFFER_NAME) ?: ""
        var tab: TabFiles.Incoming? = pdf(state.getString(STATE_OFFER_PDF))?.let { TabFiles.Incoming.Pdf(name, it) }
            ?: state.getString(STATE_OFFER_TEXT)?.let { TabFiles.Incoming.Text(name, it) }
        if (pending != null && pendingSong != null && pendingSong == currentId) useTab(pendingSong, pending)
        else if (pendingPdf != null) {
            // Tried for a song that didn't come back with the screen: offered again (unless another is offered).
            if (tab == null) tab = TabFiles.Incoming.Pdf("", pendingPdf) else TabFiles.release(this, pendingPdf)
        }
        if (tab != null) offerTab(tab)
    }

    override fun onDestroy() {
        SongLines.unlisten(lineListener) // a line still being worked out is kept for next time, not shown here
        if (live?.get() === this) live = null
        // Closed for good: a PDF that came in and wasn't kept goes (one the screen comes back with stays, see onSaveInstanceState).
        if (isFinishing) {
            (pendingTab?.source as? SongLines.TabSource.Pdf)?.let { TabFiles.release(this, it.file) }
            offered?.let { TabFiles.release(this, it) }
        }
        stopFile()
        super.onDestroy()
    }

    companion object {
        /** The song screen now (a share or a file read finishing after the screen was recreated goes to the new one). */
        private var live: WeakReference<SongActivity>? = null
        private const val REQ_FILE = 3
        private const val REQ_TAB = 4
        private const val PAGE_NECK = 0
        private const val PAGE_SHEET = 1
        private const val PREF_PAGE = "song_page"
        private const val PREF_SYNC = "song_sync_"
        private const val PREF_LAST_SONG = "song_last"
        /** An audio file song's file (a content link the app keeps permission to read) and name. */
        private const val PREF_URI = "song_uri_"
        private const val PREF_NAME = "song_name_"
        /** Whether a song's recording is the bouzouki alone. */
        private const val PREF_SOLO = "song_solo_"
        private const val STATE_SONG = "song"
        private const val STATE_PENDING_SONG = "tab_pending_song"
        private const val STATE_PENDING_PDF = "tab_pending_pdf"
        private const val STATE_PENDING_TEXT = "tab_pending_text"
        private const val STATE_OFFER_NAME = "tab_offer_name"
        private const val STATE_OFFER_PDF = "tab_offer_pdf"
        private const val STATE_OFFER_TEXT = "tab_offer_text"
        private const val SYNC_STEP = 0.025
        private const val MAX_SYNC = 1.0
        /** Seconds between drawing a frame and seeing it. */
        private const val DISPLAY_LEAD = 0.025
        /** Seconds between plucking the synth and hearing it. */
        private const val PLUCK_LEAD = 0.06
        /** Strokes per second of the bouzouki sound's tremolo. */
        private const val TREMOLO_HZ = 12.0
        /** A tapped note plays from this many seconds before it. */
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
        /**
         * Tab notes that must be found in the recording (and at least a quarter of them, and half of a short tab's) to be
         * sure a tab is of this song: a tab of another song gets a few found by chance, and replaces the song's tab only
         * if the player says so.
         */
        private const val MIN_SURE_NOTES = 8
        /** The sound chip's label, and the least width it keeps beside the switches (dp; then it shows the icon only). */
        private const val SOUND_LABEL = "🔊 Bouzouki"
        private const val MIN_SOUND_DP = 64
        /** By a tab note heard at another pitch, in the tablature. */
        private const val OFF_PITCH = "?"
        private const val PREPARE_AGAIN = "Prepare again to use tabs and detail levels."

        /** How far off A = 440 Hz a record must be (cents) for the song to say how to tune to it. */
        private const val TUNING_SHOWN = 10

        /**
         * The song's tuning line for a record tuned [cents] from A = 440 Hz (+ sharp, - flat), or null when it's
         * close enough to play along without retuning (and for songs prepared before the tuning was measured: 0).
         */
        fun tuningLine(cents: Double): String? {
            if (!cents.isFinite()) return null
            val off = abs(cents).roundToInt()
            if (off < TUNING_SHOWN) return null
            val flat = cents < 0
            return "Record tuned $off cents ${if (flat) "flat" else "sharp"}: tune ${if (flat) "down" else "up"}" +
                (if (off < 30) " a little" else "") + " to play along"
        }
    }
}
