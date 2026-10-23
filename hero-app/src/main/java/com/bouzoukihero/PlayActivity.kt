package com.bouzoukihero

import android.app.Activity
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
import com.bouzoukihero.core.Session
import com.bouzoukihero.core.SessionListener
import com.bouzoukihero.core.Synth
import com.bouzoukihero.core.Tunes
import com.bouzoukihero.core.VIEW_W
import kotlin.concurrent.thread

/**
 * Plays one song: a built-in tune with synthesized audio, or a YouTube song with the
 * video at the top. In record mode the player taps along to make the chart.
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
            session.message = "Tuning the bouzouki…"
            view = HeroView(this, this)
            root.addView(view)
            thread(name = "synth") {
                val audio = Synth.render(tune)
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
                tempo = song.tempo, bestScore = store.best(scoreKey, difficulty), listener = this,
            )
            session.state = Session.State.LOADING
            session.message = "Loading the video…"
            view = HeroView(this, this)
            root.addView(view)

            val web = WebView(this)
            val videoH = resources.displayMetrics.widthPixels * 9 / 16
            root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, videoH, Gravity.TOP))
            session.topInset = VIEW_W * 9 / 16
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
        if (session.record) {
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
        const val KIND_BUILTIN = "builtin"
        const val KIND_CUSTOM = "custom"

        fun playIntent(ctx: Context, kind: String, id: String, d: Difficulty, record: Boolean = false) =
            Intent(ctx, PlayActivity::class.java)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_DIFFICULTY, d.name)
                .putExtra(EXTRA_RECORD, record)
    }
}
