package com.mixdeck

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.mixdeck.engine.Direction
import com.mixdeck.engine.Suggestion
import com.mixdeck.ui.Console
import com.mixdeck.ui.KeyDisplay
import com.mixdeck.ui.PickCheck
import java.io.File
import java.util.concurrent.Executors

/**
 * Picks a song for a deck: suggestions that fit, songs already on the phone, YouTube search, a
 * Spotify playlist/album/track link, or an audio file. A pick that needs downloading is returned
 * at once (the console downloads it on the deck); a link shared to the app downloads here.
 */
class LibraryActivity : Activity() {
    private lateinit var store: TrackStore
    private var deck = 0
    private var shared = false
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** Copying a file, or downloading a shared link (the only work done on this screen). */
    @Volatile private var busy = false

    private val accent get() = if (deck == 0) 0xFF29B6F6.toInt() else 0xFFFF7043.toInt()
    private val bg = 0xFF0E0E11.toInt()
    private val card = 0xFF1A1A1F.toInt()

    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private lateinit var heading: TextView
    /** "SYNC to deck A's tempo", in the status row on every tab. */
    private lateinit var syncBox: CheckBox
    private enum class Tab(val label: String, val kind: LibraryTab) {
        SUGGESTED("Suggested", LibraryTab.SUGGESTED), MINE("My songs", LibraryTab.MINE), YOUTUBE("YouTube", LibraryTab.YOUTUBE),
        SPOTIFY("Spotify", LibraryTab.SPOTIFY), FILES("Phone files", LibraryTab.FILES),
    }
    private val tabButtons = LinkedHashMap<Tab, Button>()

    // Suggested tab
    /** The deck whose song the suggestions follow, or -1. */
    private var after = -1
    /** The target deck already holds a song that hasn't been played yet. */
    private var waiting = false
    private var next: NextSongs? = null
    private var nextState: NextSongs.State? = null
    private var expanded: String? = null
    /** Set when a tap opens a row, so its reasons are scrolled into view once it's laid out. */
    private var scrollToOpen = false
    private var suggestRows: LinearLayout? = null
    private var suggestBusy: LinearLayout? = null
    private var suggestBar: ProgressBar? = null
    private var findButton: Button? = null
    private val dirButtons = ArrayList<Button>()
    private val prefs by lazy { getSharedPreferences("mixdeck", MODE_PRIVATE) }

    // YouTube tab: the search and its results live in LibraryMemory, so they're still there next time.
    // Every library screen's list shows that one list: each open screen redraws when it changes.
    private val ytResults get() = LibraryMemory.results
    private var ytInput: EditText? = null
    private var ytList: View? = null
    private var ytEmpty: View? = null
    // Spotify tab
    private var spotify: SpotifyList? = null
    /** Saved songs by name, worked out once per list ("on phone" rows load offline). */
    private var spotifyHave: Map<String, List<Track>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibraryMemory.watch(redrawResults)
        store = TrackStore(filesDir)
        deck = intent.getIntExtra(EXTRA_DECK, 0)
        shared = intent.action == Intent.ACTION_SEND
        // NEXT says which deck to follow. LOAD follows the other deck's song, else the one being replaced.
        val decks = Engine.mixer?.decks
        after = intent.getIntExtra(EXTRA_AFTER, -1).takeIf { it in 0..1 }
            ?: listOf(1 - deck, deck).firstOrNull { decks?.get(it)?.loaded == true } ?: -1
        waiting = intent.getBooleanExtra(EXTRA_WAITING, false)
        window.decorView.setBackgroundColor(bg)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(4)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading = TextView(this).apply {
            setTextColor(accent); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setSingleLine()
            setPadding(0, 0, dp(16), 0)
        }
        header.addView(heading)
        for (t in Tab.entries) {
            if (t == Tab.SUGGESTED && shared) continue
            val b = button(t.label) { showTab(t) }
            tabButtons[t] = b
            header.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
        }
        header.addView(button("✕") { finish() }, LinearLayout.LayoutParams(dp(48), dp(40)))
        root.addView(header)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        // Bottom row: the status line, and on the right "SYNC to deck A's tempo" (every tab).
        val bottom = hbox()
        status = TextView(this).apply { setTextColor(0xFFB0B0BC.toInt()); textSize = 14f; setPadding(dp(4), dp(4), dp(4), dp(2)) }
        bottom.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        syncBox = CheckBox(this).apply {
            text = "SYNC to deck ${Console.deckName(1 - deck)}'s tempo"; setTextColor(Color.WHITE); textSize = 14f
            buttonTintList = ColorStateList.valueOf(accent)
            isChecked = prefs.getBoolean("nextSync", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("nextSync", on).apply() }
        }
        bottom.addView(syncBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bottom)
        setContentView(root)
        refreshLive()

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        when {
            sharedText != null && Spotify.isSpotifyUrl(sharedText) -> { showTab(Tab.SPOTIFY); importSpotify(sharedText) }
            sharedText != null && YouTube.isYouTubeUrl(sharedText) -> { showTab(Tab.YOUTUBE); YouTube.videoId(sharedText)?.let { pickYouTube(YtResult(sharedText, it, "Shared video", "", 0)) } }
            else -> {
                val onDecks = Engine.deckTrackIds.toList()
                val tab = LibraryRules.defaultTab(intent.getBooleanExtra(EXTRA_SUGGEST, false) && !shared,
                    LibraryRules.hasSavedNotOnDeck(store.all(), onDecks))
                showTab(Tab.entries.first { it.kind == tab }, focus = tab == LibraryTab.YOUTUBE)
            }
        }
    }

    /** Every second: the countdown in the header, and whether SYNC applies. */
    private val ticker = object : Runnable {
        override fun run() { refreshLive(); main.postDelayed(this, 1000) }
    }

    override fun onResume() { super.onResume(); main.removeCallbacks(ticker); main.postDelayed(ticker, 1000) }
    override fun onPause() { super.onPause(); main.removeCallbacks(ticker) }

    override fun onDestroy() {
        super.onDestroy()
        LibraryMemory.unwatch(redrawResults)
        main.removeCallbacks(ticker)
        next?.close()
    }

    /** "Load into Deck B  ·  Deck A ends in 0:43" (red under 30 s), and the SYNC box when it applies. */
    private fun refreshLive() {
        val t = SpannableStringBuilder(if (shared) "Add to MixDeck" else "Load into Deck ${deckName()}")
        val cd = if (shared) null else Engine.mixer?.let { PickCheck.countdown(it, deck) }
        if (cd != null) {
            val start = t.length
            t.append("  ·  ").append(cd.text)
            t.setSpan(ForegroundColorSpan(if (cd.urgent) 0xFFFF5252.toInt() else 0xFFB0B0BC.toInt()), start, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            t.setSpan(RelativeSizeSpan(0.85f), start, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        heading.text = t
        syncBox.visibility = if (!shared && otherHasTempo(deck)) View.VISIBLE else View.GONE
    }

    /** The deck other than [target] holds a song with a beat (so there's a tempo to SYNC to). */
    private fun otherHasTempo(target: Int) = Engine.mixer?.decks?.get(1 - target)?.let { it.loaded && it.bpm > 0 } == true

    /**
     * SYNC for a song loaded onto [target]: the box when it shows; while it's hidden, Suggested
     * picks keep the choice (they match the song they replace) and other tabs don't sync.
     */
    private fun syncFor(target: Int, fromSuggested: Boolean) =
        prefs.getBoolean("nextSync", true) && (otherHasTempo(target) || fromSuggested)

    private fun showTab(t: Tab, focus: Boolean = false) {
        tabButtons.forEach { (k, b) -> styleButton(b, k == t) }
        content.removeAllViews()
        suggestRows = null
        ytInput = null
        content.addView(when (t) {
            Tab.SUGGESTED -> suggestedTab()
            Tab.YOUTUBE -> youTubeTab()
            Tab.SPOTIFY -> spotifyTab()
            Tab.MINE -> mySongsTab()
            Tab.FILES -> phoneTab()
        })
        if (!busy) status.text = when (t) {
            Tab.SUGGESTED -> nextState?.let(::suggestStatus) ?: if (next != null) SUGGEST_HINT else ""
            Tab.YOUTUBE -> LibraryRules.YOUTUBE_HINT
            Tab.SPOTIFY -> "Paste a Spotify playlist, album or song link. Each song is matched to its audio on YouTube."
            Tab.MINE -> "Songs on your phone load instantly. Hold one to delete it."
            Tab.FILES -> "Pick an audio file from your phone."
        }
        if (focus) ytInput?.let { e ->
            e.post {
                e.requestFocus(); e.setSelection(e.text.length)
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    // ---------------------------------------------------------------- Suggested

    private fun suggestedTab(): View {
        val box = vbox()
        val mixer = Engine.mixer
        val seed = if (after >= 0) Engine.deckTrackIds[after]?.let(store::get) else null
        if (seed == null || mixer == null) return emptyState(
            "Load a song first",
            "Suggestions are songs that fit after the one that's playing: a key that blends, a tempo close enough to sync, " +
                "and the energy you want. Pick a song for this deck from YouTube or My songs to get started.")
        val n = next ?: NextSongs(store, Suggester(store) { f, p -> Decoder.decode(f, p) }, seed, mixer.decks[after].pitch,
            onDecks = Engine.deckTrackIds.filterNotNull().toSet(),
            waiting = if (waiting) Engine.deckTrackIds[deck]?.let(store::get) else null,
            direction = Direction.entries[prefs.getInt("nextDirection", 0).coerceIn(0, 2)],
        ) { st -> main.post { showSuggestions(st) } }.also { next = it; it.start() }

        // "After: Air Force Ones · 93 BPM · 8A"   [Keep the vibe] [Build up ↑] [Cool down ↓]
        val head = hbox()
        val bpm = if (seed.bpm > 0) "%.0f BPM".format(seed.bpm * (1 + mixer.decks[after].pitch)) else null
        head.addView(TextView(this).apply {
            text = (listOf("After: ${seed.title}") + listOfNotNull(bpm, n.seedKeyLabel())).joinToString("  ·  ")
            setTextColor(Color.WHITE); textSize = 15f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dirButtons.clear()
        listOf("Keep the vibe", "Build up ↑", "Cool down ↓").forEachIndexed { i, label ->
            val b = button(label) {
                prefs.edit().putInt("nextDirection", i).apply()
                dirButtons.forEachIndexed { k, x -> styleButton(x, k == i) }
                n.setDirection(Direction.entries[i])
            }
            // The stored choice: NextSongs only reports a new direction once it has re-ranked.
            styleButton(b, i == prefs.getInt("nextDirection", 0).coerceIn(0, 2))
            dirButtons += b
            head.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(6) })
        }
        box.addView(head)

        // Progress while checking songs or finding new ones: [=====-----] [Stop]
        val busyRow = hbox().apply { visibility = View.GONE; setPadding(0, dp(4), 0, 0) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(accent)
        }
        busyRow.addView(bar, LinearLayout.LayoutParams(0, dp(8), 1f))
        busyRow.addView(button("Stop") { n.stop(); setStatus("Stopping after this song…") },
            LinearLayout.LayoutParams(dp(80), dp(36)).apply { marginStart = dp(8) })
        box.addView(busyRow)
        suggestBusy = busyRow; suggestBar = bar

        val rows = vbox()
        box.addView(ScrollView(this).apply { addView(rows) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        suggestRows = rows

        //                                                      [Find new songs like this]
        // (SYNC is in the status row below, on every tab.)
        val foot = hbox().apply { setPadding(0, dp(4), 0, 0); gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        findButton = button("Find new songs like this") { findNew(n, seed) }
        foot.addView(findButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        box.addView(foot)

        nextState?.let(::showSuggestions)
        return box
    }

    /** First time: say what it downloads. */
    private fun findNew(n: NextSongs, seed: Track) {
        if (prefs.getBoolean("findOk", false)) return n.findNew()
        AlertDialog.Builder(this).setTitle("Find new songs like this?")
            .setMessage("MixDeck asks YouTube for songs that go with \"${seed.title}\", downloads up to 6 of them " +
                "(about 30 MB, so Wi-Fi is best) and checks which ones fit. They're saved in My songs.")
            .setPositiveButton("Find songs") { _, _ -> prefs.edit().putBoolean("findOk", true).apply(); n.findNew() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showSuggestions(st: NextSongs.State) {
        nextState = st
        val rows = suggestRows ?: return
        rows.removeAllViews()
        val seedKey = next?.seedKeyLabel()?.filter { it.isLetterOrDigit() }
        val seedEnergy = next?.seedTrack?.energy ?: Float.NaN
        st.waiting?.let { rows.addView(suggestionRow(it, onDeck = true, isNew = false, seedKey, seedEnergy)) }
        for (s in st.list) rows.addView(suggestionRow(s, onDeck = false, isNew = s.track.id in st.found, seedKey, seedEnergy))
        if (st.list.isEmpty() && st.checking == null) rows.addView(emptyState(
            if (st.librarySize == 0) "No other songs yet" else "You've played them all",
            if (st.librarySize == 0) "Tap \"Find new songs like this\" and MixDeck fetches a few songs that go with the one that's playing."
            else "Every song on your phone was played in the last few hours. Tap \"Find new songs like this\" for fresh ones.",
            "Search YouTube" to { showTab(Tab.YOUTUBE, focus = true) }))
        val c = st.checking
        suggestBusy?.visibility = if (c != null || st.finding != null) View.VISIBLE else View.GONE
        suggestBar?.apply {
            isIndeterminate = c == null
            if (c != null) { max = c.total; progress = c.done }
        }
        findButton?.isEnabled = st.finding == null
        if (!busy) status.text = suggestStatus(st)
    }

    private fun suggestStatus(st: NextSongs.State): String = st.finding
        ?: st.checking?.let { "Checking your songs for key and energy… ${it.done + 1} of ${it.total}" }
        ?: st.note ?: SUGGEST_HINT

    /** stars · title + artist/summary · key move · tempo · energy · [Load]; tap to see why. */
    private fun suggestionRow(s: Suggestion, onDeck: Boolean, isNew: Boolean, seedKey: String?, seedEnergy: Float): View {
        val id = s.track.id
        val open = expanded == id
        val row = vbox().apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(if (open) card else bg) }
        }
        val line = hbox()
        line.addView(TextView(this).apply {
            text = "★".repeat(s.stars) + "☆".repeat(5 - s.stars); setTextColor(0xFFFFD54F.toInt()); textSize = 15f
        }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
        val texts = vbox()
        texts.addView(TextView(this).apply {
            val t = SpannableStringBuilder()
            val badge = when { onDeck -> "ON DECK ${deckName()} NOW  "; isNew -> "NEW  "; else -> null }
            if (badge != null) {
                t.append(badge)
                t.setSpan(ForegroundColorSpan(if (onDeck) 0xFF1DE9B6.toInt() else 0xFF00E676.toInt()), 0, badge.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            t.append(s.track.title)
            text = t; setTextColor(Color.WHITE); textSize = 16f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(this).apply {
            text = listOf(s.track.artist, s.summary).filter { it.isNotEmpty() }.joinToString("  ·  ")
            setTextColor(0xFF9A9AA8.toInt()); textSize = 13f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
        })
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(chip(KeyDisplay.moveLabel(s, showFrom = seedKey != null),
            KeyDisplay.moveColor(s) ?: 0xFF5A5A66.toInt(), Color.BLACK))
        val pct = s.pitchToSync * 100
        line.addView(chip(if (s.track.bpm > 0) "%+.1f%%".format(pct).replace('-', '−') else "no beat", 0xFF2A2A31.toInt(),
            when { kotlin.math.abs(pct) >= 10 -> 0xFFFF5252.toInt(); kotlin.math.abs(pct) >= 6 -> 0xFFFFB300.toInt(); else -> Color.WHITE }))
        line.addView(TextView(this).apply {
            text = energyText(seedEnergy, s.track.energy); setTextColor(0xFFB0B0BC.toInt()); textSize = 13f
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT))
        if (!onDeck) line.addView(button("Load") { pickSuggestion(s) }.also { styleButton(it, true) },
            LinearLayout.LayoutParams(dp(72), dp(40)).apply { marginStart = dp(8) })
        else line.addView(View(this), LinearLayout.LayoutParams(dp(80), dp(40)))
        row.addView(line)
        if (open && scrollToOpen) {
            scrollToOpen = false
            row.post { row.requestRectangleOnScreen(android.graphics.Rect(0, 0, row.width, row.height), false) }
        }
        if (open) {
            for (r in s.reasons) row.addView(TextView(this).apply {
                val t = SpannableStringBuilder(if (r.good) "✓  " else "✗  ")
                t.setSpan(ForegroundColorSpan(if (r.good) 0xFF00E676.toInt() else 0xFFFF8A65.toInt()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                t.append(r.text)
                text = t; setTextColor(0xFFE6E6EC.toInt()); textSize = 14f; setPadding(dp(84), dp(4), dp(8), 0)
            })
            row.addView(TextView(this).apply {
                text = "Fits ${s.score.toInt()} out of 100" + if (onDeck) " · already on deck ${deckName()}" else ""
                setTextColor(0xFF77778A.toInt()); textSize = 12f; setPadding(dp(84), dp(4), 0, 0)
            })
        }
        row.setOnClickListener { expanded = if (open) null else id; scrollToOpen = !open; nextState?.let(::showSuggestions) }
        return row
    }

    private fun pickSuggestion(s: Suggestion) = finishWith(SongPick.Saved(s.track.id, s.track.title), fromSuggested = true)

    private fun chip(label: String, bgColor: Int, fg: Int) = TextView(this).apply {
        text = label; setTextColor(fg); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(bgColor) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
    }

    /** A title, a line of advice, and buttons that lead somewhere (label to action). */
    private fun emptyState(title: String, body: String, vararg buttons: Pair<String, () -> Unit>) = vbox().apply {
        gravity = Gravity.CENTER; setPadding(dp(24), dp(32), dp(24), dp(32))
        addView(TextView(context).apply { text = title; setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        addView(TextView(context).apply { text = body; setTextColor(0xFFB0B0BC.toInt()); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        if (buttons.isNotEmpty()) addView(hbox().apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0)
            for ((label, action) in buttons) addView(button(label, action), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(6); marginEnd = dp(6) })
        })
    }

    private fun deckName() = if (deck == 0) "A" else "B"

    private fun energyText(now: Float, e: Float): String {
        if (e.isNaN()) return "energy ?"
        val arrow = when { now.isNaN() -> ""; e - now >= 0.5f -> " ↑"; now - e >= 0.5f -> " ↓"; else -> " →" }
        return "energy ${Math.round(e)}$arrow"
    }

    // ---------------------------------------------------------------- YouTube

    private fun youTubeTab(): View {
        val box = vbox()
        val row = hbox()
        val input = edit("Search songs, or paste a YouTube or Spotify link").apply { setText(LibraryMemory.query) }
        ytInput = input
        // [Songs | Videos]: the active half is filled. Switching runs the search again.
        val songs = button("Songs") {}; val videos = button("Videos") {}
        fun styleMode() { styleSegment(songs, LibraryMemory.songsOnly, left = true); styleSegment(videos, !LibraryMemory.songsOnly, left = false) }
        fun mode(songsOnly: Boolean) {
            if (LibraryMemory.songsOnly == songsOnly) return
            LibraryMemory.songsOnly = songsOnly; styleMode(); search(input.text.toString())
        }
        songs.setOnClickListener { mode(true) }; videos.setOnClickListener { mode(false) }
        styleMode()
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(songs, LinearLayout.LayoutParams(dp(84), dp(46)).apply { marginStart = dp(6) })
        row.addView(videos, LinearLayout.LayoutParams(dp(84), dp(46)))
        row.addView(button("Search") { search(input.text.toString()) }, LinearLayout.LayoutParams(dp(96), dp(46)).apply { marginStart = dp(6) })
        box.addView(row)
        onEnter(input) { search(input.text.toString()) }
        val adapter = rows(ytResults, { it.title }, { "${it.artist}  ·  ${fmt(it.durationSec)}" + if (store.has("yt_" + it.id)) "  ·  on phone" else "" })
        val list = listView(adapter) { pos -> pickYouTube(ytResults[pos]) }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, s: Int) {}
            override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) {
                if (total > 0 && first + visible >= total - 2) loadMore()
            }
        })
        // Before the first search: what to type, and two songs to start with.
        val empty = ScrollView(this).apply {
            addView(emptyState(LibraryRules.SEARCH_TITLE, LibraryRules.SEARCH_BODY,
                *LibraryRules.STARTERS.map { (label, q) -> label to { input.setText(q); search(q) } }.toTypedArray()))
        }
        val area = FrameLayout(this)
        area.addView(list); area.addView(empty)
        box.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        ytList = list; ytEmpty = empty
        searchAdapter = adapter
        showYtEmpty()
        return box
    }

    /** The empty state shows until the first search. */
    private fun showYtEmpty() {
        val none = LibraryMemory.search == null && ytResults.isEmpty()
        ytEmpty?.visibility = if (none) View.VISIBLE else View.GONE
        ytList?.visibility = if (none) View.GONE else View.VISIBLE
    }

    private var searchAdapter: BaseAdapter? = null

    /**
     * LibraryMemory's results changed (this screen's search, or another library screen's, maybe
     * one that has closed since): this screen's list shows them, so it must be told at once.
     */
    private val redrawResults = { searchAdapter?.notifyDataSetChanged(); showYtEmpty() }

    private fun search(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        hideKeyboard()
        if (YouTube.isYouTubeUrl(query)) { YouTube.videoId(query)?.let { pickYouTube(YtResult(query, it, "YouTube video", "", 0)) }; return }
        if (Spotify.isSpotifyUrl(query)) { showTab(Tab.SPOTIFY); importSpotify(query); return }
        val s = runCatching { YouTube.Search(query, LibraryMemory.songsOnly) }.getOrElse { return setStatus("Search failed: ${Problems.explain(it)}") }
        LibraryMemory.newSearch(query, s)
        setStatus("Searching YouTube for \"$query\"…")
        worker.execute {
            val r = runCatching { s.nextPage() }
            main.post {
                if (!LibraryMemory.pageArrived(s, r.getOrDefault(emptyList())) || isDestroyed) return@post
                r.onSuccess { setStatus(if (it.isEmpty()) "Nothing found." else "Tap a song to load it.") }
                    .onFailure { setStatus("Search failed: ${Problems.explain(it)}") }
            }
        }
    }

    private fun loadMore() {
        val s = LibraryMemory.search ?: return
        if (LibraryMemory.fetching || !s.hasMore || ytResults.isEmpty()) return
        LibraryMemory.fetching = true
        worker.execute {
            val r = runCatching { s.nextPage() }
            main.post {
                // A failed page redraws nothing (so it isn't asked for again at once): the next scroll tries again.
                if (!LibraryMemory.pageArrived(s, r.getOrDefault(emptyList())) || isDestroyed) return@post
                // A page that loads after a failed one clears that error (other news on the line stays).
                r.onSuccess { if (status.text.startsWith(MORE_FAILED)) setStatus("Tap a song to load it.") }
                    .onFailure { setStatus("$MORE_FAILED: ${Problems.explain(it)}") }
            }
        }
    }

    /** Saved already: load it. Otherwise return the pick; the console downloads it on the deck. */
    private fun pickYouTube(r: YtResult) {
        val id = "yt_" + r.id
        if (store.has(id)) return finishWith(SongPick.Saved(id, store.get(id)?.title ?: r.title))
        val pick = SongPick.YouTube(r.url, r.id, r.title, r.artist, r.durationSec)
        // A link shared from another app downloads here, then asks which deck.
        if (shared) runInBackground("Downloading ${r.title}…") { SongFetcher.fetch(store, pick) { setStatus(it) } }
        else finishWith(pick)
    }

    // ---------------------------------------------------------------- Spotify

    private val spotifyTracks = ArrayList<SpotifyTrack>()
    private var spotifyAdapter: BaseAdapter? = null
    private var spotifyTitle: TextView? = null

    private fun spotifyTab(): View {
        val box = vbox()
        val row = hbox()
        val input = edit("Paste a Spotify playlist, album or song link")
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Import") { importSpotify(input.text.toString()) }, LinearLayout.LayoutParams(dp(96), dp(46)).apply { marginStart = dp(6) })
        row.addView(button("Saved ▾") { savedSpotifyLists() }, LinearLayout.LayoutParams(dp(100), dp(46)).apply { marginStart = dp(6) })
        onEnter(input) { importSpotify(input.text.toString()) }
        box.addView(row)
        val title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(4), dp(8), 0, dp(4)) }
        box.addView(title)
        spotifyTitle = title
        val adapter = rows(spotifyTracks, { it.title }, {
            "${it.artist}  ·  ${fmt(it.durationSec)}" + if (LibraryRules.savedId(spotifyHave, it) != null) "  ·  on phone" else ""
        })
        spotifyAdapter = adapter
        box.addView(listView(adapter) { pos -> pickSpotify(spotifyTracks[pos]) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        showSpotifyList(spotify ?: store.spotifyLists().firstOrNull())
        return box
    }

    private fun showSpotifyList(l: SpotifyList?) {
        spotify = l
        spotifyTracks.clear()
        if (l != null) spotifyTracks.addAll(l.tracks)
        if (l != null) spotifyHave = LibraryRules.savedNames(store)
        spotifyTitle?.apply {
            // No list yet: how to get a link out of Spotify.
            text = l?.let { "${it.name}  (${it.tracks.size} songs)" } ?: LibraryRules.SPOTIFY_EMPTY
            typeface = if (l != null) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (l != null) Color.WHITE else 0xFFB0B0BC.toInt())
            textSize = if (l != null) 16f else 15f
        }
        spotifyAdapter?.notifyDataSetChanged()
    }

    private fun importSpotify(link: String) {
        if (link.isBlank()) return
        hideKeyboard()
        setStatus("Reading the Spotify link…")
        worker.execute {
            val r = runCatching { Spotify.fetch(link).also { store.saveSpotifyList(it) } }
            main.post {
                r.onSuccess { showSpotifyList(it); setStatus("Tap a song to find it on YouTube and load it.") }
                    .onFailure { setStatus("Couldn't import: ${it.message}") }
            }
        }
    }

    private fun savedSpotifyLists() {
        val lists = store.spotifyLists()
        if (lists.isEmpty()) return toast("No saved Spotify lists yet")
        AlertDialog.Builder(this).setTitle("Spotify lists")
            .setItems(lists.map { "${it.name} (${it.tracks.size})" }.toTypedArray()) { _, i -> showSpotifyList(lists[i]) }
            .show()
    }

    /** Saved already ("on phone"): load it, offline. Otherwise the console finds and downloads it on the deck. */
    private fun pickSpotify(t: SpotifyTrack) {
        LibraryRules.savedId(spotifyHave, t)?.let { return finishWith(SongPick.Saved(it, t.title)) }
        val pick = SongPick.Spotify(t)
        if (shared) runInBackground("Finding ${t.title} on YouTube…") { SongFetcher.fetch(store, pick) { setStatus(it) } }
        else finishWith(pick)
    }

    // ---------------------------------------------------------------- My songs

    private fun mySongsTab(): View {
        val box = vbox()
        val all = store.all()
        val onDecks = Engine.deckTrackIds.toList()
        // Songs on a deck last, marked "on deck A"; the filter also takes a key like 8A.
        val shown = ArrayList(LibraryRules.mySongs(all, onDecks, LibraryMemory.mySongsFilter))
        val filter = edit(LibraryRules.filterHint(all.size)).apply { setText(LibraryMemory.mySongsFilter) }
        box.addView(filter)
        val adapter = rows(shown, { it.first.title }, { LibraryRules.songSubtitle(it.first, it.second) })
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                LibraryMemory.mySongsFilter = s.toString()
                shown.clear(); shown.addAll(LibraryRules.mySongs(all, onDecks, LibraryMemory.mySongsFilter))
                adapter.notifyDataSetChanged()
            }
        })
        val list = listView(adapter) { pos -> shown[pos].first.let { finishWith(SongPick.Saved(it.id, it.title)) } }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val t = shown[pos].first
            AlertDialog.Builder(this).setMessage("Delete \"${t.title}\" from the phone?")
                .setPositiveButton("Delete") { _, _ -> store.delete(t.id); showTab(Tab.MINE) }
                .setNegativeButton("Cancel", null).show()
            true
        }
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        return box
    }

    // ---------------------------------------------------------------- Phone files

    private fun phoneTab(): View {
        val box = vbox().apply { gravity = Gravity.CENTER }
        box.addView(TextView(this).apply {
            text = "Load an MP3, M4A, FLAC, WAV or other audio file from your phone."
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16))
        })
        box.addView(button("Choose a file") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), REQ_FILE)
        }, LinearLayout.LayoutParams(dp(220), dp(56)))
        return box
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_FILE && resultCode == RESULT_OK && uri != null) importFile(uri)
    }

    private fun importFile(uri: Uri) {
        runInBackground("Copying the file…") {
            var name = "Audio file"; var size = 0L
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                }
            }
            val id = "file_" + Integer.toHexString((name + size).hashCode())
            if (!store.has(id)) {
                val dest = store.audioFile(id)
                contentResolver.openInputStream(uri)!!.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                val (title, artist) = readTags(dest, name)
                store.save(Track(id, title, artist, uri.toString()))
            }
            id
        }
    }

    private fun readTags(f: File, fallback: String): Pair<String, String> {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val t = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val a = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
            (t ?: fallback.substringBeforeLast('.')) to (a ?: "")
        } catch (e: Exception) {
            fallback.substringBeforeLast('.') to ""
        } finally { r.release() }
    }

    // ---------------------------------------------------------------- finishing

    /** Copies a file or downloads a shared link here (job returns the song id), then [finishWith] it. */
    private fun runInBackground(what: String, job: () -> String) {
        if (busy) return toast("Still working on the last one…")
        busy = true
        setStatus(what)
        worker.execute {
            val r = runCatching(job)
            main.post {
                busy = false
                r.onSuccess { finishWith(SongPick.Saved(it, store.get(it)?.title ?: "")) }
                    .onFailure { setStatus("Couldn't get it: ${Problems.explain(it)}") }
            }
        }
    }

    /**
     * A song was picked: if its deck is playing, ask where it should go (PickCheck), then hand it to
     * the console and close at once. [fromSuggested]: picked on the Suggested tab (SYNC rule).
     */
    private fun finishWith(pick: SongPick, fromSuggested: Boolean = false) {
        if (isFinishing || isDestroyed) return
        if (shared) return shareTo(pick)
        val sync = syncFor(deck, fromSuggested)
        val mixer = Engine.mixer
        val q = mixer?.let { synchronized(it.lock) { PickCheck.question(it, deck, pick.title, needsDownload = pick !is SongPick.Saved) } }
        if (q == null) return done(pick, Placement(deck, sync))
        ask(q) { option -> Placement.of(option, deck, sync, syncFor(1 - deck, fromSuggested))?.let { done(pick, it) } }
    }

    /** Shows a PickCheck question: the default answer, the other one, Cancel. */
    private fun ask(q: PickCheck.Question, answer: (PickCheck.Option) -> Unit) {
        AlertDialog.Builder(this).setTitle(q.title).setMessage(q.message)
            .setPositiveButton(q.label(q.options[0])) { _, _ -> answer(q.options[0]) }
            .setNeutralButton(q.label(q.options[1])) { _, _ -> answer(q.options[1]) }
            .setNegativeButton(q.label(PickCheck.Option.CANCEL), null).show()
    }

    private fun Intent.putPick(pick: SongPick, p: Placement): Intent {
        pick.toExtras().forEach { (k, v) -> putExtra(k, v) }
        return putExtra(EXTRA_DECK, p.deck).putExtra(EXTRA_SYNC, p.sync).putExtra(EXTRA_WHEN, p.whenToLoad.name)
    }

    private fun done(pick: SongPick, p: Placement) {
        setResult(RESULT_OK, Intent().putPick(pick, p))
        finish()
    }

    /** Opened from the share menu (the song is saved by now): which deck, if any? */
    private fun shareTo(pick: SongPick) {
        val title = pick.title.ifEmpty { (pick as? SongPick.Saved)?.id?.let(store::get)?.title ?: "Load" }
        val mixer = Engine.mixer
        fun state(i: Int): String {
            val d = mixer?.decks?.get(i)
            return when {
                d == null || !d.loaded -> "empty"
                // During a MIX, the time until MIX stops it (as in the question that follows).
                PickCheck.live(mixer, i) -> "playing, ${Console.fmtTime(PickCheck.secondsLeft(mixer, i))} left"
                else -> "not playing"
            }
        }
        fun send(p: Placement) {
            startActivity(Intent(this, MainActivity::class.java).putPick(pick, p).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(arrayOf("Load into Deck A · ${state(0)}", "Load into Deck B · ${state(1)}", "Just save it")) { _, which ->
                if (which == 2) { toast("Saved to My songs"); finish(); return@setItems }
                val q = Engine.mixer?.let { m -> synchronized(m.lock) { PickCheck.question(m, which, title) } }
                if (q == null) send(Placement(which, false))
                else ask(q) { option -> Placement.of(option, which, false)?.let(::send) }
            }.show()
    }

    // ---------------------------------------------------------------- tiny UI kit

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        setOnClickListener { onClick() }
        styleButton(this, false)
    }

    private fun styleButton(b: Button, selected: Boolean) {
        b.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (selected) accent else 0xFF2A2A31.toInt())
        }
        b.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        b.setPadding(dp(8), 0, dp(8), 0)
    }

    /** One half of a two-part toggle: the active half is filled with the deck's colour. */
    private fun styleSegment(b: Button, on: Boolean, left: Boolean) {
        val r = dp(8).toFloat()
        b.background = GradientDrawable().apply {
            cornerRadii = if (left) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            setColor(if (on) accent else 0xFF2A2A31.toInt())
            if (!on) setStroke(dp(1), 0xFF3A3A44.toInt())
        }
        b.setTextColor(if (on) Color.BLACK else Color.WHITE)
        b.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        b.setPadding(dp(8), 0, dp(8), 0)
    }

    private fun edit(hint: String) = EditText(this).apply {
        this.hint = hint; setSingleLine(); textSize = 16f
        setTextColor(Color.WHITE); setHintTextColor(0xFF77778A.toInt())
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(card) }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun onEnter(e: EditText, f: () -> Unit) {
        e.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { f(); true } else false
        }
    }

    private fun <T> rows(items: List<T>, title: (T) -> String, sub: (T) -> String) = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(TextView(context).apply { setTextColor(0xFF9A9AA8.toInt()); textSize = 13f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            (v.getChildAt(0) as TextView).text = title(items[p])
            (v.getChildAt(1) as TextView).text = sub(items[p])
            return v
        }
    }

    private fun listView(adapter: BaseAdapter, onPick: (Int) -> Unit) = ListView(this).apply {
        this.adapter = adapter
        divider = GradientDrawable().apply { setColor(0xFF24242B.toInt()) }
        dividerHeight = 1
        setOnItemClickListener { _, _, pos, _ -> onPick(pos) }
    }

    private fun setStatus(s: String) { main.post { status.text = s } }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }
    private fun fmt(sec: Long) = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)

    companion object {
        private const val MORE_FAILED = "Couldn't load more results"
        const val EXTRA_DECK = "deck"
        /** In the result: when to load it, a Placement.When name. The song itself is in SongPick's extras. */
        const val EXTRA_WHEN = "when"
        /** Open on the Suggested tab (NEXT). */
        const val EXTRA_SUGGEST = "suggest"
        /** The deck whose song the suggestions follow. */
        const val EXTRA_AFTER = "after"
        /** The target deck already holds an unplayed song (shown as "On deck B now"). */
        const val EXTRA_WAITING = "waiting"
        /** In the result: SYNC the song to the other deck once it's loaded. */
        const val EXTRA_SYNC = "sync"
        const val SUGGEST_HINT = "Best first. Tap a song to see why it fits. Keys are on the key wheel (Camelot): next-door keys mix smoothly."
        private const val REQ_FILE = 2
    }
}
