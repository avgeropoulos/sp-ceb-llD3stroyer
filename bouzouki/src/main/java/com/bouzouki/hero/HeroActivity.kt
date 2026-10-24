package com.bouzouki.hero

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.bouzouki.Settings
import com.bouzouki.SongJobs
import com.bouzouki.core.song.Articulation
import com.bouzoukihero.core.Charts
import com.bouzoukihero.core.Difficulty
import com.bouzoukihero.core.Samples
import com.bouzoukihero.core.Session
import com.bouzoukihero.core.SessionListener
import com.bouzoukihero.core.Synth
import com.bouzoukihero.core.Tunes
import com.bouzoukihero.core.VIEW_W
import java.io.File
import kotlin.concurrent.thread

/**
 * Bouzouki Hero: play a song's bouzouki line by tapping the buttons as the notes come down
 * the neck. A built-in tune plays with its own audio; a song prepared in Μπουζούκι plays its
 * YouTube video at the top, and the notes are the tab Μπουζούκι worked out for it.
 */
class HeroActivity : Activity(), HeroView.Host, SessionListener {
    override lateinit var session: Session
    @Volatile override var clock: SongClock? = null
    override val latency = 0.0

    private lateinit var view: HeroView
    private lateinit var scoreKey: String
    private lateinit var difficulty: Difficulty
    private var pool: SoundPool? = null
    @Volatile private var missId = 0
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        difficulty = Difficulty.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_DIFFICULTY) } ?: Difficulty.EASY
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val root = FrameLayout(this)
        setContentView(root)
        loadSounds()

        if (intent.getStringExtra(EXTRA_KIND) == KIND_BUILTIN) {
            val tune = Tunes.byId(id) ?: return finish()
            scoreKey = "b:$id"
            session = Session(
                tune.title, difficulty, Charts.fromTab(tabOf(tune), difficulty, Charts.courseLabels(TETRA)),
                tempo = tune.bpm.toDouble(), bestScore = best(this, scoreKey, difficulty), listener = this,
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
            // A song prepared in Μπουζούκι, fingered for the bouzouki chosen there.
            val inst = Settings(this).instrument
            val song = SongJobs.cached(this, id)?.forInstrument(inst) ?: return finish()
            scoreKey = "s:$id"
            val notes = song.notes.map {
                Charts.TabNote(it.start, it.end - it.start, it.midi, it.course, it.fret, it.articulation == Articulation.TREMOLO)
            }
            val gems = Charts.fromTab(notes, difficulty, Charts.courseLabels(inst.courses.map { it.openMidi }))
            session = Session(
                song.title.ifBlank { "YouTube song" }, difficulty, gems, hasVideo = true,
                tempo = beatTempo(song.beats), bestScore = best(this, scoreKey, difficulty), listener = this,
            )
            session.state = Session.State.LOADING
            session.message = if (gems.isEmpty()) "This song has no bouzouki notes to play." else "Loading the video…"
            if (gems.isEmpty()) session.state = Session.State.ERROR
            session.topInset = VIEW_W * 9 / 16
            view = HeroView(this, this)
            root.addView(view)
            val web = WebView(this)
            root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, resources.displayMetrics.widthPixels * 9 / 16, Gravity.TOP))
            // Start a few seconds before the first note, so it has time to come down the neck.
            val start = maxOf(0.0, (gems.firstOrNull()?.time ?: 0.0) - 4.0)
            clock = YouTubeSong(web, id, start, null, object : YouTubeSong.Events {
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
            Session.Action.RESUME -> { c?.resume(); session.state = Session.State.PLAYING }
            Session.Action.RESTART -> { c?.restart(); session.restart() }
            Session.Action.QUIT, Session.Action.DONE, Session.Action.PLAY_CHART -> finish()
            Session.Action.OPA, Session.Action.NONE -> {}
        }
    }

    private fun pauseGame() {
        if (session.state != Session.State.PLAYING) return
        clock?.pause()
        session.releaseAll(view.songTime())
        session.state = Session.State.PAUSED
    }

    override fun miss() {
        val id = missId
        if (id != 0) pool?.play(id, 0.5f, 0.5f, 1, 0, 1f)
    }

    override fun finished(session: Session) {
        clock?.pause()
        if (session.gems.isNotEmpty()) saveResult(this, scoreKey, difficulty, session.score, session.stars)
    }

    private fun loadSounds() {
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        pool = p
        val dir = cacheDir
        thread(name = "hero-sfx") {
            try {
                val f = File(dir, "hero_miss.wav")
                f.writeBytes(Samples.writeWav(Synth.missSound()))
                missId = p.load(f.path, 1)
            } catch (_: Exception) {
            }
        }
    }

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
        pool?.release()
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
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        }
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
        const val EXTRA_DIFFICULTY = "difficulty"
        const val KIND_BUILTIN = "builtin"
        const val KIND_SONG = "song"
        private val TETRA = listOf(48, 53, 57, 62)

        fun intent(ctx: Context, kind: String, id: String, d: Difficulty): Intent =
            Intent(ctx, HeroActivity::class.java).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_ID, id).putExtra(EXTRA_DIFFICULTY, d.name)

        /** The built-in tunes' tab, as Bouzouki Tabs' fingering search places them on a tetrachordo. */
        private fun tabOf(tune: com.bouzoukihero.core.Tune): List<Charts.TabNote> {
            val notes = tune.sourceNotes()
            val midis = notes.map { com.bouzoukihero.core.Fretboard.fitToRange(it.pitch) }
            val pos = com.bouzoukihero.core.Fretboard.assign(midis, notes.map { it.time })
            return notes.indices.map { Charts.TabNote(notes[it].time, notes[it].duration, midis[it], pos[it].course, pos[it].fret, notes[it].tremolo) }
        }

        /** Tempo from the song's beat times, for the frets sliding down the neck. */
        private fun beatTempo(beats: List<Double>): Double {
            if (beats.size < 4) return 0.0
            val gaps = beats.zipWithNext { a, b -> b - a }.sorted()
            val g = gaps[gaps.size / 2]
            return if (g > 0) 60.0 / g else 0.0
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("hero", Context.MODE_PRIVATE)
        fun best(ctx: Context, key: String, d: Difficulty) = prefs(ctx).getInt("best_${key}_${d.name}", 0)
        fun stars(ctx: Context, key: String, d: Difficulty) = prefs(ctx).getInt("stars_${key}_${d.name}", 0)

        fun saveResult(ctx: Context, key: String, d: Difficulty, score: Int, stars: Int) {
            val e = prefs(ctx).edit()
            if (score > best(ctx, key, d)) e.putInt("best_${key}_${d.name}", score)
            if (stars > stars(ctx, key, d)) e.putInt("stars_${key}_${d.name}", stars)
            e.apply()
        }
    }
}
