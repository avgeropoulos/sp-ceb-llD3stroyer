package com.bouzoukihero

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.bouzoukihero.core.Charts
import com.bouzoukihero.core.CustomSong
import com.bouzoukihero.core.Difficulty
import com.bouzoukihero.core.LiveTranscriber
import com.bouzoukihero.core.Session
import com.bouzoukihero.core.SessionListener
import com.bouzoukihero.core.Sampler
import com.bouzoukihero.core.Synth
import com.bouzoukihero.core.Tunes
import com.bouzoukihero.core.VIEW_W
import kotlin.concurrent.thread

/**
 * Plays one song: a built-in tune with synthesized audio, or a YouTube song with the
 * video at the top. In record mode the player taps along to make the chart; in listen
 * mode the app hears the video and finds the bouzouki notes itself.
 */
class PlayActivity : Activity(), HeroView.Host, SessionListener {
    override lateinit var session: Session
    @Volatile override var clock: SongClock? = null
    override var latency = 0.0

    private lateinit var store: Store
    private lateinit var sfx: Sfx
    private lateinit var view: HeroView
    private var custom: CustomSong? = null
    private lateinit var scoreKey: String
    private lateinit var difficulty: Difficulty
    private var record = false
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = Store(this)
        sfx = Sfx(this)
        latency = store.latencyMs / 1000.0
        difficulty = Difficulty.valueOf(intent.getStringExtra(EXTRA_DIFFICULTY) ?: Difficulty.EASY.name)
        record = intent.getBooleanExtra(EXTRA_RECORD, false)
        listen = record && intent.getBooleanExtra(EXTRA_LISTEN, false)
        val id = intent.getStringExtra(EXTRA_ID) ?: ""

        val root = FrameLayout(this)
        setContentView(root)

        if (intent.getStringExtra(EXTRA_KIND) == KIND_BUILTIN) {
            val tune = Tunes.byId(id) ?: return finish()
            scoreKey = "b:$id"
            session = Session(
                tune.title, difficulty, Charts.build(tune.sourceNotes(), difficulty),
                tempo = tune.bpm.toDouble(), bestScore = store.best(scoreKey, difficulty), listener = this,
            )
            session.state = Session.State.LOADING
            session.message = "Tuning the bouzouki\u2026"
            view = HeroView(this, this)
            root.addView(view)
            thread(name = "synth") {
                // Real recorded bouzouki notes if the player has any, else the synth.
                val samples = store.samples()
                val audio = Synth.render(tune, if (samples.isNotEmpty()) Sampler(samples) else Synth.synthVoice)
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    clock = SynthSong(audio) { session.leadAudible }
                    session.state = Session.State.PLAYING
                }
            }
        } else {
            val song = store.song(id) ?: return finish()
            custom = song
            scoreKey = "c:$id"
            val gems = if (record) emptyList() else Charts.build(song.notes, difficulty, labels = song.labelled)
            session = Session(
                song.title, difficulty, gems, record = record, hasVideo = true,
                tempo = song.tempo, bestScore = store.best(scoreKey, difficulty), listener = this, listen = listen,
            )
            session.state = Session.State.LOADING
            session.message = if (listen) "Getting ready to listen\u2026" else "Loading the video\u2026"
            session.topInset = VIEW_W * 9 / 16
            view = HeroView(this, this)
            root.addView(view)
            this.root = root
            // When listening, the video waits until the app can hear it.
            if (listen) startListening() else startVideo()
        }
    }

    private var root: FrameLayout? = null

    private fun startVideo() {
        val song = custom ?: return
        val r = root ?: return
        if (clock != null || destroyed) return
        session.message = "Loading the video\u2026"
        val web = WebView(this)
        val videoH = resources.displayMetrics.widthPixels * 9 / 16
        r.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, videoH, Gravity.TOP))
        val gems = session.gems
        // Start a few seconds before the first note so it has time to scroll down.
        val start = if (record || gems.isEmpty()) song.start else maxOf(0.0, gems.first().time - 4.0)
        clock = YouTubeSong(web, song.videoId, start, if (record) song.end else null, object : YouTubeSong.Events {
            override fun onPlaying() {
                if (session.state == Session.State.LOADING) session.state = Session.State.PLAYING
            }

            override fun onVideoError(message: String) {
                if (session.state == Session.State.FINISHED) return
                clock?.pause()
                session.message = message
                session.state = Session.State.ERROR
            }
        })
    }

    // ---- Listening: the app finds the notes itself ----

    private var listen = false
    @Volatile private var live: LiveTranscriber? = null
    private var mic: MicListener? = null
    @Volatile private var capturing = false
    @Volatile private var quietSince = -1.0
    @Volatile private var loudest = 0.0

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            session.message = "Allow Bouzouki Hero to capture its own sound, so it can hear the song."
            val mpm = getSystemService(MediaProjectionManager::class.java)
            try {
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
                return
            } catch (_: Exception) {
            }
        }
        startMic()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else {
            session.message = "Bouzouki Hero needs the audio permission to hear the song. You can tap along instead."
            session.state = Session.State.ERROR
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            startMic()
            return
        }
        live = LiveTranscriber(latency = 0.06)
        CaptureService.sink = AudioSink { onAudio(it) }
        CaptureService.started = {
            capturing = true
            session.banner = ""
            startVideo()
        }
        CaptureService.failed = { startMic() }
        val i = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    /** The fallback: listen to the phone's speaker with the microphone. */
    private fun startMic() {
        if (destroyed) return
        stopCapture()
        live = LiveTranscriber(latency = 0.15)
        val m = MicListener { onAudio(it) }
        if (!m.ok) {
            session.message = "Couldn't open the microphone. You can tap along instead."
            session.state = Session.State.ERROR
            return
        }
        mic = m
        session.banner = "Listening with the microphone: turn the volume up"
        startVideo()
    }

    private fun stopCapture() {
        if (capturing || CaptureService.sink != null) stopService(Intent(this, CaptureService::class.java))
        capturing = false
        CaptureService.sink = null
        CaptureService.started = null
        CaptureService.failed = null
    }

    /** Called on the listening thread with each chunk of what the app hears. */
    private fun onAudio(pcm: FloatArray) {
        val c = clock ?: return
        val l = live ?: return
        if (session.state != Session.State.PLAYING) return
        val t = c.now()
        var e = 0.0
        for (v in pcm) e += v * v
        val rms = Math.sqrt(e / pcm.size.coerceAtLeast(1))
        session.level = (rms * 8).toFloat().coerceIn(0f, 1f)
        if (rms > loudest) loudest = rms
        if (capturing) {
            // Hearing nothing while the video plays: this phone won't let us capture it.
            if (rms < 1e-4) {
                if (quietSince < 0) quietSince = t
                else if (t - quietSince > 5) {
                    quietSince = -1.0
                    runOnUiThread { switchToMic() }
                    return
                }
            } else quietSince = -1.0
        }
        val found = l.feed(pcm, t)
        if (found.isNotEmpty()) session.heard(found)
    }

    private fun switchToMic() {
        if (!capturing || destroyed) return
        stopCapture()
        live = LiveTranscriber(latency = 0.15)
        val m = MicListener { onAudio(it) }
        if (m.ok) {
            mic = m
            session.banner = "Couldn't capture the sound: using the microphone"
        }
    }

    override fun onAction(action: Session.Action) {
        val c = clock
        when (action) {
            Session.Action.PAUSE -> pauseGame()
            Session.Action.RESUME -> {
                c?.resume()
                session.state = Session.State.PLAYING
            }
            Session.Action.RESTART -> {
                c?.restart()
                session.restart()
                if (listen) live = LiveTranscriber(latency = if (capturing) 0.06 else 0.15)
            }
            Session.Action.QUIT -> finish()
            Session.Action.DONE -> session.finish()
            Session.Action.PLAY_CHART -> {
                startActivity(playIntent(this, KIND_CUSTOM, custom?.id ?: return, store.difficulty, record = false))
                finish()
            }
            Session.Action.OPA, Session.Action.NONE -> {}
        }
    }

    private fun pauseGame() {
        if (session.state != Session.State.PLAYING) return
        clock?.pause()
        session.releaseAll(view.songTime())
        session.state = Session.State.PAUSED
    }

    // ---- SessionListener (called on the game thread) ----

    override fun miss() = sfx.miss()

    override fun tick() = sfx.tick()

    override fun finished(session: Session) {
        clock?.pause()
        if (session.listen) {
            val song = custom ?: return
            mic?.stop()
            val notes = live?.finish().orEmpty()
            runOnUiThread { stopCapture() }
            if (notes.size >= 4) {
                store.save(song.copy(notes = notes, source = CustomSong.SOURCE_AUTO, tempo = 0.0))
                // Straight into the game with the new chart.
                session.doneNote = "Found ${notes.size} notes. Starting the game\u2026"
                runOnUiThread {
                    window.decorView.postDelayed({ if (!destroyed) onAction(Session.Action.PLAY_CHART) }, 1800)
                }
            } else {
                session.doneNote = if (loudest < 0.002) "The app couldn't hear the video's sound."
                else "Heard the music, but no clear notes."
            }
        } else if (session.record) {
            val song = custom ?: return
            val notes = session.recordedNotes()
            if (notes.size >= 4) {
                store.save(song.copy(notes = notes, source = CustomSong.SOURCE_RECORDED, tempo = 0.0))
            }
        } else if (session.gems.isNotEmpty()) {
            store.saveResult(scoreKey, difficulty, session.score, session.stars)
        }
    }

    // ---- Lifecycle ----

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        view.resume()
    }

    override fun onPause() {
        pauseGame()
        view.pause()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (session.state == Session.State.PLAYING) pauseGame() else finish()
    }

    override fun onDestroy() {
        destroyed = true
        mic?.stop()
        if (listen) stopCapture()
        clock?.release()
        sfx.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        }
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
        const val EXTRA_DIFFICULTY = "difficulty"
        const val EXTRA_RECORD = "record"
        const val EXTRA_LISTEN = "listen"
        private const val REQ_MIC = 1
        private const val REQ_CAPTURE = 2
        const val KIND_BUILTIN = "builtin"
        const val KIND_CUSTOM = "custom"

        fun playIntent(ctx: Context, kind: String, id: String, d: Difficulty, record: Boolean = false, listen: Boolean = false) =
            Intent(ctx, PlayActivity::class.java)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_DIFFICULTY, d.name)
                .putExtra(EXTRA_RECORD, record || listen)
                .putExtra(EXTRA_LISTEN, listen)
    }
}
