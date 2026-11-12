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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
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
    private val tabButtons = ArrayList<Button>()

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
        window.decorView.setBackgroundColor(bg)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(4)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = if (shared) "Add to MixDeck" else "Load into Deck ${if (deck == 0) "A" else "B"}"
            setTextColor(accent); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(16), 0)
        })
        listOf("YouTube", "Spotify", "My songs", "Phone files").forEachIndexed { i, name ->
            val b = button(name) { showTab(i) }
            tabButtons += b
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
            sharedText != null && Spotify.isSpotifyUrl(sharedText) -> { showTab(1); importSpotify(sharedText) }
            sharedText != null && YouTube.isYouTubeUrl(sharedText) -> { showTab(0); YouTube.videoId(sharedText)?.let { pickYouTube(YtResult(sharedText, it, "Shared video", "", 0)) } }
            store.all().isNotEmpty() && !shared -> showTab(2)
            else -> showTab(0)
        }
    }

    private fun showTab(i: Int) {
        tabButtons.forEachIndexed { k, b -> styleButton(b, k == i) }
        content.removeAllViews()
        content.addView(when (i) {
            0 -> youTubeTab()
            1 -> spotifyTab()
            2 -> mySongsTab()
            else -> phoneTab()
        })
        if (!busy) status.text = when (i) {
            0 -> "Search YouTube Music for songs, or switch to Videos for remixes, live sets and mashups."
            1 -> "Paste a Spotify playlist, album or song link. Each song is matched to its audio on YouTube."
            2 -> "Songs already on your phone load instantly. Hold one to delete it."
            else -> ""
        }
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
        if (Spotify.isSpotifyUrl(query)) { showTab(1); importSpotify(query); return }
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
                .setPositiveButton("Delete") { _, _ -> store.delete(t.id); showTab(2) }
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

    private fun finishWith(trackId: String) {
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
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TRACK, trackId).putExtra(EXTRA_DECK, deck))
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
        private const val REQ_FILE = 2
    }
}
