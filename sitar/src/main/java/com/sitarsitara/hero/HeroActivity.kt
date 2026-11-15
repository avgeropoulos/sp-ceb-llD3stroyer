package com.sitarsitara.hero

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
import com.bouzoukihero.core.Charts
import com.bouzoukihero.core.Difficulty
import com.bouzoukihero.core.DisplayLead
import com.bouzoukihero.core.Samples
import com.bouzoukihero.core.Session
import com.bouzoukihero.core.SessionListener
import com.bouzoukihero.core.SourceNote
import com.bouzoukihero.core.SyncStore
import com.bouzoukihero.core.Synth
import com.bouzoukihero.core.Theme
import com.bouzoukihero.core.VIEW_W
import com.sitarsitara.ScreenTiming
import com.sitarsitara.Settings
import com.sitarsitara.SongJobs
import com.sitarsitara.core.Reading
import com.sitarsitara.core.Sargam
import com.sitarsitara.core.SitarAnalysis
import java.io.File
import kotlin.concurrent.thread

/**
 * Sitar Superstar: the Bouzouki Hero game played on a song's sitar line. The video plays at the top; the notes come
 * down the neck named as sargam (with Sa where the song screen has it), lower swaras to the left. Hit them in time.
 */
class HeroActivity : Activity(), HeroView.Host, SessionListener {
    @Volatile override lateinit var session: Session
    @Volatile override var clock: SongClock? = null
    /** Song seconds the notes are shifted by: minus the song's sync (sync > 0 shows notes earlier). */
    @Volatile override var latency = 0.0
    private var syncKey = ""

    private lateinit var view: HeroView
    private lateinit var scoreKey: String
    private lateinit var difficulty: Difficulty
    private var pool: SoundPool? = null
    @Volatile private var missId = 0
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        difficulty = Difficulty.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_DIFFICULTY) } ?: Difficulty.EASY
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val song = SongJobs.cached(this, id) ?: return finish()
        val settings = Settings(this)
        val root = FrameLayout(this)
        setContentView(root)
        loadSounds()
        ScreenTiming.restateSyncs(this)
        scoreKey = "s:$id"

        // The notes as the song screen reads them, less those the analysis is too unsure of for this level.
        val reading = Reading.of(SitarAnalysis.line(song), settings.sa(id), difficulty.confidenceFloor)
        val script = settings.script
        val notes = reading.notes.map { SourceNote(it.start, it.end - it.start, it.midi, it.note.fast, it.note.confidence) }
        val gems = Charts.build(notes, difficulty, label = { midi -> Sargam.swara(midi, reading.sa).text(script) })
        val s = Session(
            song.title.ifBlank { "YouTube song" }, difficulty, gems, hasVideo = true,
            tempo = beatTempo(song.beats), bestScore = best(this, scoreKey, difficulty), listener = this, theme = Theme.SITAR,
        )
        s.state = Session.State.LOADING
        s.message = if (gems.isEmpty()) "This song has no sitar notes to play." else "Loading the video…"
        if (gems.isEmpty()) s.state = Session.State.ERROR
        s.topInset = VIEW_W * 9 / 16
        session = s
        loadSync(SyncStore.SONG + id, SyncStore.DEFAULT_SONG)
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

    override fun onAction(action: Session.Action) {
        val c = clock
        when (action) {
            Session.Action.PAUSE -> pauseGame()
            Session.Action.RESUME -> { c?.resume(); session.state = Session.State.PLAYING }
            Session.Action.RESTART -> { c?.restart(); session.restart() }
            Session.Action.DONE -> session.finish()
            Session.Action.QUIT, Session.Action.PLAY_CHART -> finish()
            Session.Action.SYNC_EARLIER -> moveSync(+SYNC_STEP)
            Session.Action.SYNC_LATER -> moveSync(-SYNC_STEP)
            // The player's own taps say how far off the notes are: taps late by x, sync down by x.
            Session.Action.SYNC_FIT -> moveSync(session.syncFix())
            Session.Action.OPA, Session.Action.NONE -> {}
        }
    }

    /**
     * The sync between the notes and the sound, kept per song; a song not tuned yet starts from the sync last
     * chosen for any song (most of the delay is the phone's own).
     */
    private fun loadSync(key: String, defaultKey: String) {
        syncKey = key
        val sync = try {
            val p = getSharedPreferences("sitar", MODE_PRIVATE)
            if (p.contains(key)) p.getFloat(key, 0f).toDouble() else p.getFloat(defaultKey, 0f).toDouble()
        } catch (_: Exception) {
            0.0
        }
        latency = if (sync.isNaN()) 0.0 else -sync.coerceIn(-DisplayLead.MAX_SYNC, DisplayLead.MAX_SYNC)
        showSync()
    }

    private fun moveSync(by: Double) {
        if (by == 0.0 || by.isNaN() || syncKey.isEmpty()) return
        val old = -latency
        val sync = (Math.round((old + by) * 1000) / 1000.0).coerceIn(-DisplayLead.MAX_SYNC, DisplayLead.MAX_SYNC)
        latency = -sync
        try {
            getSharedPreferences("sitar", MODE_PRIVATE).edit()
                .putFloat(syncKey, sync.toFloat()).putFloat(SyncStore.DEFAULT_SONG, sync.toFloat()).apply()
        } catch (_: Exception) {
        }
        session.syncMoved(sync - old)
        showSync()
    }

    private fun showSync() {
        val ms = Math.round(-latency * 1000)
        session.syncText = when {
            ms > 0 -> "$ms ms earlier"
            ms < 0 -> "${-ms} ms later"
            else -> "in step"
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
        @Suppress("DEPRECATION")
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
        const val EXTRA_ID = "id"
        const val EXTRA_DIFFICULTY = "difficulty"
        private const val SYNC_STEP = 0.025

        fun intent(ctx: Context, id: String, d: Difficulty): Intent =
            Intent(ctx, HeroActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_DIFFICULTY, d.name)

        /** Tempo from the song's beat times, for the frets sliding down the neck. */
        private fun beatTempo(beats: List<Double>): Double {
            if (beats.size < 4) return 0.0
            val gaps = beats.zipWithNext { a, b -> b - a }.sorted()
            val g = gaps[gaps.size / 2]
            return if (g > 0) 60.0 / g else 0.0
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("hero", Context.MODE_PRIVATE)
        fun difficulty(ctx: Context): Difficulty = Difficulty.entries.firstOrNull { it.name == prefs(ctx).getString("difficulty", null) } ?: Difficulty.EASY
        fun setDifficulty(ctx: Context, d: Difficulty) = prefs(ctx).edit().putString("difficulty", d.name).apply()
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
