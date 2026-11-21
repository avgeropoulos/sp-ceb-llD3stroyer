package com.mixdeck

import com.mixdeck.engine.Direction
import com.mixdeck.engine.MusicalKey
import com.mixdeck.engine.NextTrack
import com.mixdeck.engine.NowPlaying
import com.mixdeck.engine.Suggestion
import com.mixdeck.engine.TwistBucket
import com.mixdeck.engine.Twists
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * What the "Suggested" tab shows, the same on the phone and the computer: songs on this device
 * ranked as the next song after [seed], plus the slow jobs behind it — checking songs that have no
 * key yet (the ones that could fit first), finding new songs like [seed] on YouTube, and finding
 * "twists": songs from another style that still mix in ([Twists]).
 *
 * All slow work runs on one background thread. [onChange] gets a fresh [State] (on that thread)
 * every time something changes: post it to the UI thread and redraw from it.
 */
class NextSongs(
    private val store: TrackStore,
    private val suggester: Suggester,
    /** The song the suggestions follow (the one playing), or null when both decks are empty. */
    seed: Track?,
    /** That deck's tempo change as a fraction (no key lock, so it moves the key too). */
    private val seedPitch: Double,
    /** Songs on the decks now: never suggested. */
    private val onDecks: Set<String>,
    /** A song already waiting, unplayed, on the target deck: rated on its own ("On deck B now"). */
    waiting: Track?,
    direction: Direction,
    private val onChange: (State) -> Unit,
) {
    class Progress(val done: Int, val total: Int, val title: String)

    /** A twist found by "Find twists": how it fits, and the line saying why it's a twist ("Twist: 70s disco at the same 107 BPM, 8A → 9A"). */
    data class Twist(val s: Suggestion, val reason: String)

    data class State(
        val direction: Direction,
        /** Best first. Twists are not in it: they're in [twists]. */
        val list: List<Suggestion> = emptyList(),
        /** Songs found by "Find twists", best first: shown first, with a TWIST badge and their reason. */
        val twists: List<Twist> = emptyList(),
        /** How well the song already on the target deck fits, if there is one. */
        val waiting: Suggestion? = null,
        /** Songs added by "Find new songs like this" (shown with a NEW badge). */
        val found: Set<String> = emptySet(),
        /** "Checking your songs… 3 of 12", or null. */
        val checking: Progress? = null,
        /** What "Find new songs like this" or "Find twists" is doing now, or null when neither is running. */
        val finding: String? = null,
        /** The last thing that happened, e.g. "Found 5 new songs." or an error. */
        val note: String? = null,
        /** How many other songs are on the device at all (for the empty state). */
        val librarySize: Int = 0,
    )

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "next songs").apply { isDaemon = true } }
    /** Counts Stop presses: a job stops when this differs from the value it started with. */
    @Volatile private var stops = 0
    @Volatile private var closed = false
    /** The chosen direction. Set at once, so the very next ranking (even mid-check) uses it. */
    @Volatile private var direction: Direction = direction
    @Volatile private var findQueued = false
    private var seed: Track? = seed
    private var waiting: Track? = waiting
    private var tracks: List<Track> = emptyList()
    private var found = LinkedHashSet<String>()
    /** Twists found: song id → its style. */
    private val twistStyles = LinkedHashMap<String, TwistBucket>()
    /** Styles already searched for twists, so the next press tries others. */
    private val triedStyles = HashSet<String>()
    @Volatile var state = State(direction); private set

    val seedTrack: Track? get() = seed

    /** The playing song's key as you hear it ("8A", "3A↑", "~8A"), for the tab's header. */
    fun seedKeyLabel(): String? {
        val k = seed?.key ?: return null
        if ((seed?.keyConfidence ?: 0f) < 0.15f) return null
        val st = MusicalKey.semitonesForSpeed(1 + seedPitch)
        val n = Math.round(st).toInt()
        return (if (MusicalKey.wholeSemitones(st) == null) "~" else "") + k.transpose(n).camelot +
            when { n > 0 -> "↑"; n < 0 -> "↓"; else -> "" }
    }

    /** Ranks what's here straight away, then checks the songs that still need a key. */
    fun start() = worker.execute {
        tracks = store.all()
        publish(rank())
        checkLibrary()
    }

    fun setDirection(d: Direction) {
        direction = d
        worker.execute { publish(rank()) }
    }

    /** Asks YouTube for songs like the playing one and adds up to [max] of them, one by one. */
    fun findNew(max: Int = 6) = runFind(
        waitText = "I'll look for new songs when the check is done…",
        startText = { "Asking YouTube for songs like ${it.title}…" },
        error = "Couldn't find new songs",
    ) { s, cancelled, status ->
        val n = suggester.discover(s, max, cancelled, onStatus = status) { t ->
            found += t.id
            tracks = listOf(t) + tracks.filter { it.id != t.id }
            publish(rank())
        }
        if (n == 0) "No new songs found this time." else "Found $n new song${if (n == 1) "" else "s"}: they're marked NEW and saved in My songs."
    }

    /**
     * Looks for up to [max] "twists": songs from another style or era that still mix in after the
     * playing song (same tempo or half/double, a key next door, similar energy). Downloads and checks
     * at most [maxTries] songs. Each press tries other styles.
     */
    fun findTwists(max: Int = MAX_TWISTS, maxTries: Int = TWIST_TRIES) = runFind(
        waitText = "I'll look for twists when the check is done…",
        startText = { "Looking for twists…" },
        error = "Couldn't find twists",
    ) { s, cancelled, status ->
        if (!(s.bpm > 0)) return@runFind "Twists match the playing song's tempo, but it has no steady beat."
        val run = suggester.findTwists(s, seedPitch, max, maxTries, triedStyles, cancelled, onStatus = status, onTwist = { t, b ->
            twistStyles[t.id] = b
            found += t.id
            tracks = listOf(t) + tracks.filter { it.id != t.id }
            publish(rank())
        })
        triedStyles += run.styles
        when {
            run.found > 0 -> "Found ${run.found} twist${if (run.found == 1) "" else "s"}: another style that still mixes in. " +
                "They're at the top, marked TWIST, and saved in My songs."
            run.tried == 0 -> "No twists to try for this tempo. Try again later."
            else -> "None of the ${run.tried} songs tried fit well enough. Try again for other styles."
        }
    }

    /**
     * Runs a "find" job on the worker: one at a time, cancelled by Stop. [job] gets the seed, the
     * cancel check and a status callback, and returns the note shown when it's done.
     */
    private fun runFind(
        waitText: String, startText: (Track) -> String, error: String,
        job: (Track, () -> Boolean, (String) -> Unit) -> String,
    ) {
        val s = seed ?: return
        if (state.finding != null || findQueued) return
        findQueued = true
        val asked = stops // a Stop pressed after this click cancels it, even while it waits its turn
        val cancelled = { closed || stops != asked }
        // Shown at once; the worker may still be checking songs.
        if (!closed) onChange(state.copy(finding = waitText, note = null))
        worker.execute {
            findQueued = false
            if (closed) return@execute
            if (cancelled()) { publish(state.copy(finding = null)); return@execute }
            val sd = seed ?: s // the check may have given it a key meanwhile
            publish(state.copy(finding = startText(sd), note = null))
            val r = runCatching { job(sd, cancelled) { msg -> if (!closed) publish(state.copy(finding = msg)) } }
            if (cancelled()) { publish(state.copy(finding = null, note = "Stopped.")); return@execute }
            publish(state.copy(finding = null, note = r.fold({ it }, { e -> "$error: ${Problems.explain(e)}" })))
        }
    }

    /** Stops checking (after the song being checked) and finding (drops the song downloading). */
    fun stop() {
        stops++
    }

    /** Call when the screen closes. */
    fun close() {
        closed = true
        stop()
        worker.shutdown()
    }

    // ------------------------------------------------------------------ inside the worker

    private fun publish(s: State) {
        // A direction picked since the last ranking re-ranks at once (ranking is quick; checking isn't).
        val fresh = if (s.direction != direction) rank(s) else s
        state = fresh
        if (!closed) onChange(fresh)
    }

    private fun rank(base: State = state): State {
        val dir = direction
        val sd = seed ?: return base.copy(direction = dir, list = emptyList(), waiting = null, librarySize = tracks.size)
        val now = NowPlaying(sd.profile(), seedPitch)
        val exclude = onDecks + sd.id
        val list = suggester.rank(now, tracks.filter { it.id !in twistStyles }, dir, exclude).take(MAX_SHOWN)
        val w = waiting?.takeIf { it.analyzed }?.let { NextTrack.score(now, it.profile(), dir) }
        val playingBpm = sd.bpm * (1 + seedPitch)
        val twists = tracks.filter { it.id in twistStyles && it.analyzed && it.id !in exclude }.map { t ->
            val s = NextTrack.score(now, t.profile(), dir)
            Twist(s, Twists.reason(twistStyles.getValue(t.id), s, playingBpm))
        }.sortedByDescending { it.s.score }
        return base.copy(direction = dir, list = list, twists = twists, waiting = w, found = found.toSet(),
            librarySize = tracks.count { it.id !in exclude })
    }

    /**
     * Gives songs a key and energy, most useful first: the playing song, the waiting song, songs
     * never analysed, then songs whose tempo is close enough to sync, closest first. Songs that
     * would need more than [CHECK_TEMPO] tempo change can't rank well anyway, so they're skipped.
     */
    private fun checkLibrary() {
        val sd = seed ?: return
        val recent = store.recentlyPlayed()
        val playingBpm = sd.bpm * (1 + seedPitch)
        val queue = buildList {
            if (!sd.hasFeatures) add(sd)
            waiting?.let { if (!it.hasFeatures) add(it) }
            val rest = tracks.filter { !it.hasFeatures && it.id != sd.id && it.id != waiting?.id && it.id !in onDecks && it.id !in recent }
            addAll(rest.filter { !it.analyzed })
            addAll(rest.filter { it.analyzed && tempoChange(playingBpm, it.bpm) <= CHECK_TEMPO }
                .sortedBy { tempoChange(playingBpm, it.bpm) })
        }
        if (queue.isEmpty()) return
        val asked = stops
        for ((n, t) in queue.withIndex()) {
            if (stops != asked || closed) break
            publish(state.copy(checking = Progress(n, queue.size, t.title)))
            // Closing the screen (e.g. after picking a song) drops the song being checked at once,
            // so it doesn't decode alongside the deck load. Stop finishes the current song first.
            val updated = runCatching {
                suggester.ensureFeatures(t) { if (closed) throw CancellationException("closed") }
            }.getOrNull() ?: continue
            when (t.id) {
                sd.id -> seed = updated
                waiting?.id -> waiting = updated
                else -> tracks = tracks.map { if (it.id == t.id) updated else it }
            }
            publish(rank())
        }
        val stopped = stops != asked
        publish(state.copy(checking = null, note = if (stopped) "Stopped. Songs not checked yet show \"key ?\"." else state.note))
    }

    companion object {
        const val MAX_SHOWN = 30
        /** Twists one "Find twists" press looks for … */
        const val MAX_TWISTS = 3
        /** … and the most songs it downloads to check. */
        const val TWIST_TRIES = 8
        /** Songs needing more tempo change than this (after half/double time) aren't checked. */
        const val CHECK_TEMPO = 0.12

        /** The tempo change SYNC would need, after choosing half or double time like Deck.syncTo. */
        fun tempoChange(playingBpm: Double, bpm: Double): Double {
            if (playingBpm <= 0 || bpm <= 0) return 0.0
            var target = playingBpm
            while (target / bpm > 1.5) target /= 2
            while (target / bpm < 0.67) target *= 2
            return abs(target / bpm - 1)
        }
    }
}
