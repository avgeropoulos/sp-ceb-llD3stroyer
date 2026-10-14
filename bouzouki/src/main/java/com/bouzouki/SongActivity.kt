package com.bouzouki

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bouzouki.core.Cell
import com.bouzouki.core.DromosGuess
import com.bouzouki.core.NoteTracker

/**
 * Paste (or share from the YouTube app) a song, watch it here, and the melody's notes
 * light up on the bouzouki as they play. The app listens either to the video's own sound
 * or through the microphone, so it also works for music playing anywhere nearby.
 */
class SongActivity : Activity() {
    private val ui by lazy { Ui(this) }
    private val settings by lazy { Settings(this) }
    private val audio = AudioEngine()

    private lateinit var board: FretboardView
    private lateinit var scroller: ScrollView
    private lateinit var link: EditText
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var videoButton: Button
    private lateinit var micButton: Button
    private lateinit var noteText: TextView
    private lateinit var trailText: TextView
    private lateinit var guessText: TextView
    private lateinit var useGuessButton: Button

    private var mic: PitchListener? = null
    private var pending: (() -> Unit)? = null // what to do once the microphone permission is granted
    private val trail = ArrayDeque<Int>()
    private var lastFret = 3
    private var guess: DromosGuess.Guess? = null

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

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(GREEK_BLUE)
            setPadding(px(4), px(4), px(12), px(4))
            addView(Button(context).apply {
                text = "←"; textSize = 22f; setTextColor(WHITE); background = null
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(px(52), px(46)))
            addView(TextView(context).apply {
                text = "Τραγούδι · Play along with a song"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(WHITE)
            })
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), px(4), px(8), px(2))
        }
        root.addView(controls)

        // The link.
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
        controls.addView(ui.row().apply {
            addView(link, ui.weighted(3f).apply { height = px(44) })
            addView(ui.chip("Paste") { pasteAndLoad() }, ui.weighted(1f))
            addView(ui.chip("Load") { loadLink() }, ui.weighted(1f))
        })

        // The video.
        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            addJavascriptInterface(YouTubeBridge(), "Android")
        }
        controls.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(200)))

        status = TextView(this).apply {
            setTextColor(0xFF9FB4CC.toInt())
            textSize = 12f
            setPadding(px(4), px(4), px(4), 0)
        }
        controls.addView(status)

        // How to listen.
        videoButton = ui.chip("🎧 Listen to the video") { toggleVideoListening() }
        micButton = ui.chip("🎤 Listen with mic") { toggleMic() }
        controls.addView(ui.row().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) addView(videoButton, ui.weighted())
            addView(micButton, ui.weighted())
        })

        // What's playing now.
        noteText = TextView(this).apply {
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFD54F.toInt())
            gravity = Gravity.CENTER
            minWidth = px(84)
            text = "–"
        }
        trailText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFE8EEF6.toInt())
            maxLines = 1
        }
        controls.addView(ui.row().apply {
            addView(noteText)
            addView(trailText, ui.weighted())
        })

        guessText = TextView(this).apply { textSize = 13f; setTextColor(0xFFE8EEF6.toInt()) }
        useGuessButton = ui.chip("Use") { useGuess() }.apply { visibility = android.view.View.GONE }
        controls.addView(ui.row().apply {
            addView(guessText, ui.weighted(3f))
            addView(useGuessButton, ui.weighted(1f))
        })

        scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(board)
        }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        showGuess()
        refreshButtons()
        status.text = "Paste a link, or tap Share → Μπουζούκι in the YouTube app. Then press play and tap 🎧."
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
        if (text.isNullOrBlank()) { status.text = "The clipboard is empty. Copy the link in YouTube first (Share → Copy link)."; return }
        link.setText(text)
        loadLink()
    }

    private fun loadLink() {
        val id = youTubeId(link.text.toString())
        if (id == null) { status.text = "That doesn't look like a YouTube link."; return }
        status.text = "Loading… press play on the video, then tap 🎧 Listen to the video."
        resetSong()
        // YouTube wants to know which app is embedding it (an origin), or it refuses with error 152/153.
        web.loadDataWithBaseURL(ORIGIN, playerHtml(id), "text/html", "utf-8", null)
    }

    private fun playerHtml(id: String) = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body{margin:0;height:100%;background:#000}#p{width:100%;height:100%}</style>
        </head><body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
          function onYouTubeIframeAPIReady() {
            new YT.Player('p', {
              width: '100%', height: '100%', videoId: '$id',
              playerVars: { playsinline: 1, rel: 0, origin: '$ORIGIN' },
              events: {
                onError: function (e) { Android.onError(e.data); },
                onStateChange: function (e) { Android.onState(e.data); }
              }
            });
          }
        </script></body></html>
    """.trimIndent()

    private inner class YouTubeBridge {
        @JavascriptInterface
        fun onError(code: Int) = runOnUiThread {
            status.text = when (code) {
                101, 150 -> "The owner of this video doesn't allow it to play in other apps. Play it in the YouTube app and use 🎤 Listen with mic."
                100 -> "That video was not found (removed or private)."
                else -> "YouTube couldn't play this here (error $code). Play it in the YouTube app and use 🎤 Listen with mic."
            }
        }

        @JavascriptInterface
        fun onState(state: Int) = runOnUiThread {
            if (state == 1 && !CaptureService.running && mic == null) {
                status.text = "Playing. Tap 🎧 Listen to the video to see its notes on the bouzouki."
            }
        }
    }

    // --- listening ---

    private fun withMicPermission(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { action(); return }
        pending = action
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_MIC) return
        val action = pending
        pending = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.invoke()
        else status.text = "Without audio permission the app can't hear the song."
    }

    private fun toggleVideoListening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (CaptureService.running) { stopService(Intent(this, CaptureService::class.java)); return }
        stopMic()
        // Android needs RECORD_AUDIO and an on-screen "start recording/casting" consent for this.
        withMicPermission {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            status.text = "Android will ask to share or record the screen: tap Start. Only the sound is used, nothing is recorded."
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        }
    }

    @Deprecated("Activity result API needs AndroidX; this is the platform way")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { status.text = "Listening cancelled. You can use 🎤 Listen with mic instead."; return }
        val start = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
        startForegroundService(start)
        status.text = "Listening to the video…"
    }

    private fun toggleMic() {
        if (mic != null) { stopMic(); return }
        if (CaptureService.running) stopService(Intent(this, CaptureService::class.java))
        withMicPermission {
            try {
                mic = PitchListener(PitchListener.microphone()) { e, tally -> onNote(e, tally) }.also { it.start() }
                status.text = "Listening through the microphone. Turn the music up!"
            } catch (e: Exception) {
                status.text = "Couldn't open the microphone: ${e.message}"
            }
            refreshButtons()
        }
    }

    private fun stopMic() {
        mic?.stop()
        mic = null
        refreshButtons()
    }

    private fun refreshButtons() {
        ui.style(videoButton, CaptureService.running)
        videoButton.text = if (CaptureService.running) "■ Stop listening" else "🎧 Listen to the video"
        ui.style(micButton, mic != null)
        micButton.text = if (mic != null) "■ Stop mic" else "🎤 Listen with mic"
    }

    // --- showing the notes ---

    private fun onNote(e: NoteTracker.Event, tally: DoubleArray) {
        when (e) {
            is NoteTracker.NoteOn -> {
                val inst = board.instrument
                // Bring voices and bass lines into the bouzouki's range by octaves.
                val low = inst.courses.first().openMidi
                val high = inst.courses.last().openMidi + inst.frets
                var midi = e.midi
                while (midi < low) midi += 12
                while (midi > high) midi -= 12
                val (c, f) = inst.position(midi, lastFret) ?: return
                lastFret = f
                val cell = Cell(c, f)
                board.liveCell = cell
                noteText.text = settings.names.name(midi)
                trail.addLast(midi)
                while (trail.size > 14) trail.removeFirst()
                trailText.text = trail.joinToString("  ") { settings.names.name(it) }
                val (top, bottom) = board.cellSpan(cell)
                keepVisible(top, bottom)
            }
            NoteTracker.NoteOff -> {
                board.liveCell = null
                noteText.text = "–"
            }
        }
        guess = DromosGuess.rank(tally).firstOrNull()?.takeIf { tally.sum() > 4.0 }
        showGuess()
    }

    private fun showGuess() {
        val g = guess
        if (g == null) {
            guessText.text = "Sounds like: (listening…)  ·  Neck shows ${settings.names.name(board.rootPc)} ${board.dromos.title}"
            useGuessButton.visibility = android.view.View.GONE
        } else {
            val same = g.dromos.semitones.contentEquals(board.dromos.semitones) && g.rootPc == board.rootPc
            guessText.text = "Sounds like: ${settings.names.name(g.rootPc)} ${g.dromos.title}" +
                if (same) "  ✓ on the neck" else ""
            useGuessButton.visibility = if (same) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private fun useGuess() {
        val g = guess ?: return
        board.dromos = g.dromos
        board.rootPc = g.rootPc
        settings.dromos = g.dromos
        settings.rootPc = g.rootPc
        showGuess()
    }

    private fun resetSong() {
        trail.clear()
        trailText.text = ""
        noteText.text = "–"
        board.liveCell = null
        guess = null
        showGuess()
    }

    private fun keepVisible(top: Int, bottom: Int) {
        val visibleTop = scroller.scrollY
        if (top < visibleTop || bottom > visibleTop + scroller.height) {
            scroller.smoothScrollTo(0, (top - scroller.height / 3).coerceAtLeast(0))
        }
    }

    override fun onResume() {
        super.onResume()
        audio.start()
        CaptureService.onEvent = { e, tally -> onNote(e, tally) }
        CaptureService.onStateChanged = { runOnUiThread { refreshButtons() } }
        CaptureService.onError = { msg -> runOnUiThread { status.text = "Couldn't listen to the video: $msg. Try 🎤 Listen with mic." } }
        refreshButtons()
    }

    override fun onPause() {
        audio.stop()
        super.onPause()
    }

    override fun onDestroy() {
        stopMic()
        if (CaptureService.running) stopService(Intent(this, CaptureService::class.java))
        CaptureService.onEvent = null
        CaptureService.onStateChanged = null
        CaptureService.onError = null
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 1
        private const val REQ_CAPTURE = 2
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
