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
import com.mixdeck.engine.Direction
import com.mixdeck.engine.Suggestion
import com.mixdeck.ui.Console
import com.mixdeck.ui.KeyDisplay
import java.io.File
import java.util.concurrent.Executors

/**
 * Picks a song for a deck: search YouTube, import a Spotify playlist/album/track link, choose
 * from songs already on the phone, or pick an audio file. Also handles links shared to the app.
 */
class LibraryActivity : Activity() {
    private lateinit var store: TrackStore
    private var deck = 0
    private var shared = false
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private val accent get() = if (deck == 0) 0xFF29B6F6.toInt() else 0xFFFF7043.toInt()
    private val bg = 0xFF0E0E11.toInt()
    private val card = 0xFF1A1A1F.toInt()

    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private enum class Tab(val label: String) { SUGGESTED("Suggested"), YOUTUBE("YouTube"), SPOTIFY("Spotify"), MINE("My songs"), FILES("Phone files") }
    private val tabButtons = LinkedHashMap<Tab, Button>()

    // Suggested tab
    /** The deck whose song the suggestions follow, or -1. */
    private var after = -1
    /** The target deck already holds a song that hasn't been played yet. */
    private var waiting = false
    private var next: NextSongs? = null
    private var nextState: NextSongs.State? = null
    private var expanded: String? = null
    private var suggestRows: LinearLayout? = null
    private var suggestBusy: LinearLayout? = null
    private var suggestBar: ProgressBar? = null
    private var findButton: Button? = null
    private val dirButtons = ArrayList<Button>()
    private val prefs by lazy { getSharedPreferences("mixdeck", MODE_PRIVATE) }

    // YouTube tab
    private var ytSearch: YouTube.Search? = null
    private val ytResults = ArrayList<YtResult>()
    private var songsOnly = true
    // Spotify tab
    private var spotify: SpotifyList? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        header.addView(TextView(this).apply {
            text = if (shared) "Add to MixDeck" else "Load into Deck ${if (deck == 0) "A" else "B"}"
            setTextColor(accent); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(16), 0)
        })
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
        status = TextView(this).apply { setTextColor(0xFFB0B0BC.toInt()); textSize = 14f; setPadding(dp(4), dp(4), dp(4), dp(2)) }
        root.addView(status)
        setContentView(root)

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        when {
            sharedText != null && Spotify.isSpotifyUrl(sharedText) -> { showTab(Tab.SPOTIFY); importSpotify(sharedText) }
            sharedText != null && YouTube.isYouTubeUrl(sharedText) -> { showTab(Tab.YOUTUBE); YouTube.videoId(sharedText)?.let { pickYouTube(YtResult(sharedText, it, "Shared video", "", 0)) } }
            intent.getBooleanExtra(EXTRA_SUGGEST, false) && !shared -> showTab(Tab.SUGGESTED)
            store.all().isNotEmpty() && !shared -> showTab(Tab.MINE)
            else -> showTab(Tab.YOUTUBE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        next?.close()
    }

    private fun showTab(t: Tab) {
        tabButtons.forEach { (k, b) -> styleButton(b, k == t) }
        content.removeAllViews()
        suggestRows = null
        content.addView(when (t) {
            Tab.SUGGESTED -> suggestedTab()
            Tab.YOUTUBE -> youTubeTab()
            Tab.SPOTIFY -> spotifyTab()
            Tab.MINE -> mySongsTab()
            Tab.FILES -> phoneTab()
        })
        if (!busy) status.text = when (t) {
            Tab.SUGGESTED -> nextState?.let(::suggestStatus) ?: SUGGEST_HINT
            Tab.YOUTUBE -> "Search YouTube Music for songs, or switch to Videos for remixes, live sets and mashups."
            Tab.SPOTIFY -> "Paste a Spotify playlist, album or song link. Each song is matched to its audio on YouTube."
            Tab.MINE -> "Songs already on your phone load instantly. Hold one to delete it."
            Tab.FILES -> ""
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
            styleButton(b, i == n.state.direction.ordinal)
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

        // [x] SYNC it for me                                   [Find new songs like this]
        val foot = hbox().apply { setPadding(0, dp(4), 0, 0) }
        foot.addView(CheckBox(this).apply {
            text = "SYNC it for me"; setTextColor(Color.WHITE); textSize = 14f
            buttonTintList = ColorStateList.valueOf(accent)
            isChecked = prefs.getBoolean("nextSync", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("nextSync", on).apply() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
            else "Every song on your phone was played in the last few hours. Tap \"Find new songs like this\" for fresh ones."))
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
        val to = s.keyAfterSync?.camelot ?: s.track.key?.let { "~" + it.camelot }
        line.addView(chip(if (to == null) "key ?" else if (seedKey != null) "$seedKey→$to" else to,
            s.keyAfterSync?.let(KeyDisplay::color) ?: 0xFF5A5A66.toInt(), Color.BLACK))
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
        row.setOnClickListener { expanded = if (open) null else id; nextState?.let(::showSuggestions) }
        return row
    }

    private fun pickSuggestion(s: Suggestion) {
        val d = Engine.mixer?.decks?.get(deck)
        val sync = prefs.getBoolean("nextSync", true)
        // LOAD already asked before opening this screen; NEXT asks now.
        if (d != null && d.playing && intent.getBooleanExtra(EXTRA_SUGGEST, false)) {
            val left = (d.durationSec - d.positionSec) / (1 + d.pitch)
            AlertDialog.Builder(this)
                .setMessage("Deck ${deckName()} is still playing (${Console.fmtTime(left)} left). Stop it and load \"${s.track.title}\" now?")
                .setPositiveButton("Load now") { _, _ -> finishWith(s.track.id, sync) }
                .setNegativeButton("Cancel", null).show()
        } else finishWith(s.track.id, sync)
    }

    private fun chip(label: String, bgColor: Int, fg: Int) = TextView(this).apply {
        text = label; setTextColor(fg); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(bgColor) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
    }

    private fun emptyState(title: String, body: String) = vbox().apply {
        gravity = Gravity.CENTER; setPadding(dp(24), dp(32), dp(24), dp(32))
        addView(TextView(context).apply { text = title; setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        addView(TextView(context).apply { text = body; setTextColor(0xFFB0B0BC.toInt()); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
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
        val input = edit("Search songs, or paste a YouTube link")
        val mode = button(if (songsOnly) "Songs" else "Videos") {}
        mode.setOnClickListener { songsOnly = !songsOnly; mode.text = if (songsOnly) "Songs" else "Videos"; search(input.text.toString()) }
        styleButton(mode, true)
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(mode, LinearLayout.LayoutParams(dp(96), dp(46)).apply { marginStart = dp(6) })
        row.addView(button("Search") { search(input.text.toString()) }, LinearLayout.LayoutParams(dp(96), dp(46)).apply { marginStart = dp(6) })
        box.addView(row)
        onEnter(input) { search(input.text.toString()) }
        val adapter = rows(ytResults, { it.title }, { "${it.artist}  ·  ${fmt(it.durationSec)}" + if (store.has("yt_" + it.id)) "  ·  on phone" else "" })
        val list = listView(adapter) { pos -> pickYouTube(ytResults[pos]) }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, s: Int) {}
            override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) {
                if (total > 0 && first + visible >= total - 2) loadMore(adapter)
            }
        })
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        searchAdapter = adapter
        return box
    }

    private var searchAdapter: BaseAdapter? = null
    @Volatile private var loadingMore = false

    private fun search(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        hideKeyboard()
        if (YouTube.isYouTubeUrl(query)) { YouTube.videoId(query)?.let { pickYouTube(YtResult(query, it, "YouTube video", "", 0)) }; return }
        if (Spotify.isSpotifyUrl(query)) { showTab(Tab.SPOTIFY); importSpotify(query); return }
        val s = runCatching { YouTube.Search(query, songsOnly) }.getOrElse { return setStatus("Search failed: ${it.message}") }
        ytSearch = s
        ytResults.clear(); searchAdapter?.notifyDataSetChanged()
        setStatus("Searching YouTube for \"$query\"…")
        loadingMore = true
        worker.execute {
            val r = runCatching { s.nextPage() }
            main.post {
                loadingMore = false
                if (ytSearch !== s) return@post
                r.onSuccess { ytResults.addAll(it); searchAdapter?.notifyDataSetChanged(); setStatus(if (it.isEmpty()) "Nothing found." else "Tap a song to load it.") }
                    .onFailure { setStatus("Search failed: ${it.message}") }
            }
        }
    }

    private fun loadMore(adapter: BaseAdapter) {
        val s = ytSearch ?: return
        if (loadingMore || !s.hasMore || ytResults.isEmpty()) return
        loadingMore = true
        worker.execute {
            val r = runCatching { s.nextPage() }
            main.post {
                loadingMore = false
                if (ytSearch === s) r.onSuccess { ytResults.addAll(it); adapter.notifyDataSetChanged() }
            }
        }
    }

    private fun pickYouTube(r: YtResult) {
        val id = "yt_" + r.id
        if (store.has(id)) return finishWith(id)
        runInBackground("Downloading ${r.title}…") {
            val file = store.audioFile(id)
            val info = YouTube.downloadAudio(r.url, file) { p -> setStatus("Downloading ${r.title}… ${(p * 100).toInt()}%") }
            store.save(Track(id, info.title.ifEmpty { r.title }, info.artist.ifEmpty { r.artist }, r.url, info.durationSec.toDouble()))
            id
        }
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
        val adapter = rows(spotifyTracks, { it.title }, { "${it.artist}  ·  ${fmt(it.durationSec)}" })
        spotifyAdapter = adapter
        box.addView(listView(adapter) { pos -> pickSpotify(spotifyTracks[pos]) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        showSpotifyList(spotify ?: store.spotifyLists().firstOrNull())
        return box
    }

    private fun showSpotifyList(l: SpotifyList?) {
        spotify = l
        spotifyTracks.clear()
        if (l != null) spotifyTracks.addAll(l.tracks)
        spotifyTitle?.text = l?.let { "${it.name}  (${it.tracks.size} songs)" } ?: "No Spotify lists yet"
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

    private fun pickSpotify(t: SpotifyTrack) {
        runInBackground("Finding ${t.title} on YouTube…") {
            val match = Spotify.matchOnYouTube(t)
            val id = "yt_" + match.id
            if (!store.has(id)) {
                YouTube.downloadAudio(match.url, store.audioFile(id)) { p -> setStatus("Downloading ${t.title}… ${(p * 100).toInt()}%") }
                // Keep Spotify's clean title and artist names.
                store.save(Track(id, t.title, t.artist, match.url, match.durationSec.toDouble()))
            }
            id
        }
    }

    // ---------------------------------------------------------------- My songs

    private fun mySongsTab(): View {
        val box = vbox()
        val all = store.all()
        val shown = ArrayList(all)
        val filter = edit("Filter my ${all.size} songs")
        box.addView(filter)
        val adapter = rows(shown, { it.title }, {
            listOf(it.artist, if (it.bpm > 0) "%.1f BPM".format(it.bpm) else null, if (it.durationSec > 0) fmt(it.durationSec.toLong()) else null)
                .filterNotNull().filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
        })
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().lowercase()
                shown.clear(); shown.addAll(all.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) })
                adapter.notifyDataSetChanged()
            }
        })
        val list = listView(adapter) { pos -> finishWith(shown[pos].id) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val t = shown[pos]
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

    private fun runInBackground(what: String, job: () -> String) {
        if (busy) return toast("Still working on the last one…")
        busy = true
        setStatus(what)
        worker.execute {
            val r = runCatching(job)
            main.post {
                busy = false
                r.onSuccess { finishWith(it) }.onFailure { setStatus("Failed: ${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }

    private fun finishWith(trackId: String, sync: Boolean = false) {
        if (shared) {
            // Opened from the share menu: ask which deck to load into.
            AlertDialog.Builder(this).setTitle(store.get(trackId)?.title ?: "Load")
                .setItems(arrayOf("Load into Deck A", "Load into Deck B", "Just save it")) { _, which ->
                    if (which < 2) startActivity(Intent(this, MainActivity::class.java)
                        .putExtra(EXTRA_TRACK, trackId).putExtra(EXTRA_DECK, which)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    else toast("Saved to My songs")
                    finish()
                }.show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TRACK, trackId).putExtra(EXTRA_DECK, deck).putExtra(EXTRA_SYNC, sync))
        finish()
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
        const val EXTRA_DECK = "deck"
        const val EXTRA_TRACK = "track"
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
