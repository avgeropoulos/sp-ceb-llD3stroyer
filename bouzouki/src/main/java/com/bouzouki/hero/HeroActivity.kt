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
import com.bouzouki.ScreenTiming
import com.bouzouki.Settings
import com.bouzouki.SongJobs
import com.bouzouki.SongLines
import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.Detail
import com.bouzouki.core.song.SongAnalysis
import com.bouzoukihero.core.Charts
import com.bouzoukihero.core.Difficulty
import com.bouzoukihero.core.DisplayLead
import com.bouzoukihero.core.Samples
import com.bouzoukihero.core.Session
import com.bouzoukihero.core.SessionListener
import com.bouzoukihero.core.SyncStore
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
    /** (Replaced once, on the main thread, when a song's notes had to be found again first; the game's thread reads it.) */
    @Volatile override lateinit var session: Session
    @Volatile override var clock: SongClock? = null
    /** Song seconds the notes are shifted by: minus the song's sync (sync > 0 shows notes earlier). */
    @Volatile override var latency = 0.0
    private var syncKey = ""
    private var syncDefaultKey = ""
    /** Seconds this song's notes are stored late (see [SongJobs.storedLate]): part of its sync until it has its own, never of the default. */
    private var songLate = 0.0

    private lateinit var view: HeroView
    private lateinit var scoreKey: String
    private lateinit var difficulty: Difficulty
    private var pool: SoundPool? = null
    @Volatile private var missId = 0
    @Volatile private var destroyed = false
    private var inFront = false
    /** What starts the song once the game is on screen again (its notes came while it wasn't): see [whenInFront]. */
    private var waiting: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        difficulty = Difficulty.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_DIFFICULTY) } ?: Difficulty.EASY
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val root = FrameLayout(this)
        setContentView(root)
        loadSounds()
        ScreenTiming.restateSyncs(this) // before any sync is read

        if (intent.getStringExtra(EXTRA_KIND) == KIND_BUILTIN) {
            val tune = Tunes.byId(id) ?: return finish()
            scoreKey = "b:$id"
            session = Session(
                tune.title, difficulty, Charts.fromTab(tabOf(tune), difficulty, Charts.courseLabels(TETRA)),
                tempo = tune.bpm.toDouble(), bestScore = best(this, scoreKey, difficulty), listener = this,
            )
            session.state = Session.State.LOADING
            session.message = "Tuning the bouzouki…"
            loadSync(SyncStore.BUILTIN + id, SyncStore.DEFAULT_BUILTIN)
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
            val settings = Settings(this)
            val inst = settings.instrument
            // The notes the song screen last showed (its chosen line, or the tab attached to the
            // song), so the game plays exactly what you saw there; else the analysis itself.
            val song = shownLine(this, id)
                ?: SongJobs.cached(this, id)?.forInstrument(inst) ?: return finish()
            scoreKey = "s:$id"
            view = HeroView(this, this) // (it draws nothing before the screen is up, and the game is set by then)
            root.addView(view)
            if (!foundByAnOlderAnalysis(song, settings)) return playSong(root, id, song)
            // A song not opened on its screen since the app learned to find its notes better: they are
            // found again from what the song keeps, as its screen does when it opens (a second or so),
            // while the game says so. Should that fail in any way, the notes as they were are played.
            session = Session(
                song.title.ifBlank { "YouTube song" }, difficulty, emptyList(), hasVideo = true,
                bestScore = best(this, scoreKey, difficulty), listener = this,
            )
            session.state = Session.State.LOADING
            session.message = "Finding the notes again…"
            session.topInset = VIEW_W * 9 / 16
            val app = applicationContext
            val detail = try { settings.detail(id) } catch (_: Exception) { Detail.NORMAL }
            thread(name = "hero-notes") {
                val fresh = try {
                    // (A line found again without a note is no line to play: the old one stays.)
                    SongJobs.currentLine(app, id, detail)?.forInstrument(inst)?.takeIf { it.notes.isNotEmpty() }
                } catch (_: Throwable) {
                    null // a damaged file, too little memory...
                }
                // Kept as the line shown, so the next game needn't find it again (the song's screen shows the same).
                if (fresh != null) saveShownLine(app, fresh)
                runOnUiThread { whenInFront { playSong(root, id, fresh ?: song) } }
            }
        }
    }

    /**
     * Whether [song] (the line the game is about to play) was found by an older analysis than the app's now and can
     * be found again from what the song keeps: not a line changed by hand, nor the song's tab's (those are the
     * player's own notes, whatever the analysis is). False if that can't be told.
     */
    private fun foundByAnOlderAnalysis(song: SongAnalysis, settings: Settings): Boolean = try {
        SongAnalysis.isLineOld(song) && SongJobs.hasData(this, song.id) &&
            SongLines.chosen(this, settings, song.id) !is SongLines.Spec.FromTab
    } catch (_: Exception) {
        false
    }

    /** Starts the game on [song] (YouTube video [id]): its notes for the difficulty, and its video at the top. */
    private fun playSong(root: FrameLayout, id: String, song: SongAnalysis) {
        // Each note with how sure the analysis is of it: the easier levels leave out the ones in doubt. A song
        // prepared as the bouzouki alone has no band whose notes could be taken for its own: all of it is played.
        val notes = song.notes.map {
            Charts.TabNote(
                it.start, it.end - it.start, it.midi, it.course, it.fret, it.articulation == Articulation.TREMOLO,
                if (song.solo) 1f else it.confidence,
            )
        }
        val gems = Charts.fromTab(notes, difficulty, Charts.courseLabels(song.instrument.courses.map { it.openMidi }))
        val s = Session(
            song.title.ifBlank { "YouTube song" }, difficulty, gems, hasVideo = true,
            tempo = beatTempo(song.beats), bestScore = best(this, scoreKey, difficulty), listener = this,
        )
        s.state = Session.State.LOADING
        s.message = if (gems.isEmpty()) "This song has no bouzouki notes to play." else "Loading the video…"
        if (gems.isEmpty()) s.state = Session.State.ERROR
        s.topInset = VIEW_W * 9 / 16
        session = s
        // Μπουζούκι's song screen keeps each song's sync under this key.
        loadSync(SyncStore.SONG + id, SyncStore.DEFAULT_SONG, SongJobs.storedLate(this, id))
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

    /** Runs [start] now if the game is on screen, else when it next is; never once it's closed. */
    private fun whenInFront(start: () -> Unit) {
        if (destroyed) return
        if (inFront) start() else waiting = start
    }

    override fun onAction(action: Session.Action) {
        val c = clock
        when (action) {
            Session.Action.PAUSE -> pauseGame()
            Session.Action.RESUME -> { c?.resume(); session.state = Session.State.PLAYING }
            Session.Action.RESTART -> { c?.restart(); session.restart() }
            // SCORE: the results now; the song plays on underneath until you leave.
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
     * The sync between the notes and the sound. For a YouTube song it's the same setting as the
     * −/+ on Μπουζούκι's song screen, so tuning it in either place fixes both. A song not tuned
     * yet starts from the sync last chosen for a song of its kind, kept under [defaultKey] (most
     * of the delay is the phone's own). Built-in tunes and YouTube songs each have their own:
     * they run on different clocks, and a "later" chosen for one used to make the other late.
     * A song whose notes are stored [late] starts that much earlier than the default.
     */
    private fun loadSync(key: String, defaultKey: String, late: Double = 0.0) {
        syncKey = key
        syncDefaultKey = defaultKey
        songLate = late
        val sync = try {
            val p = getSharedPreferences("bouzouki", MODE_PRIVATE)
            if (p.contains(key)) p.getFloat(key, 0f).toDouble() else p.getFloat(defaultKey, 0f) + late
        } catch (_: Exception) {
            0.0
        }
        latency = if (sync.isNaN()) 0.0 else -sync.coerceIn(-DisplayLead.MAX_SYNC, DisplayLead.MAX_SYNC)
        showSync()
    }

    /**
     * Moves the sync by [by] seconds (positive shows the notes earlier), and keeps it for this
     * song and as where the next untuned song of its kind starts.
     */
    private fun moveSync(by: Double) {
        if (by == 0.0 || by.isNaN() || syncKey.isEmpty()) return
        val old = -latency
        val sync = (Math.round((old + by) * 1000) / 1000.0).coerceIn(-DisplayLead.MAX_SYNC, DisplayLead.MAX_SYNC)
        latency = -sync
        try {
            getSharedPreferences("bouzouki", MODE_PRIVATE).edit()
                .putFloat(syncKey, sync.toFloat()).putFloat(syncDefaultKey, (sync - songLate).toFloat()).apply()
        } catch (_: Exception) {
        }
        // The taps so far were judged under the old sync: restate them, so what the menu says
        // of them stays true and a fix just applied isn't offered again.
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
        inFront = true
        hideSystemUi()
        view.resume()
        waiting?.let { waiting = null; it() }
    }

    override fun onPause() {
        inFront = false
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
        private const val SYNC_STEP = 0.025
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

        private fun linesDir(ctx: Context) = File(ctx.filesDir, "hero-lines").apply { mkdirs() }

        /**
         * Keeps the line the song screen is showing for [song], for the game to play the same notes (each with how
         * sure the analysis is of it: the song's file format carries that).
         */
        fun saveShownLine(ctx: Context, song: SongAnalysis) {
            val app = ctx.applicationContext
            thread(name = "hero-line") {
                try {
                    // One at a time: the song screen and the game may both keep a song's line at the same moment.
                    synchronized(lineFiles) {
                        val f = File(linesDir(app), "${song.id}.json")
                        val tmp = File(linesDir(app), "${song.id}.tmp")
                        tmp.writeText(song.toJson())
                        tmp.renameTo(f)
                    }
                } catch (_: Exception) {
                }
            }
        }

        private val lineFiles = Any()

        private fun shownLine(ctx: Context, id: String): SongAnalysis? = try {
            File(linesDir(ctx), "$id.json").takeIf { it.exists() }?.readText()?.let { SongAnalysis.fromJson(it) }
        } catch (_: Exception) {
            null
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
