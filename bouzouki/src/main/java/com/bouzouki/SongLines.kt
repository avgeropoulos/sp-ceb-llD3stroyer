package com.bouzouki

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.bouzouki.core.Instrument
import com.bouzouki.core.song.AnalysisData
import com.bouzouki.core.song.Detail
import com.bouzouki.core.song.ParsedTab
import com.bouzouki.core.song.SongAnalysis
import com.bouzouki.core.song.SongAnalyzer
import com.bouzouki.core.song.TabAligner
import com.bouzouki.core.song.TabParser
import java.lang.ref.SoftReference
import java.util.concurrent.Executors

/**
 * A saved song's line derived again, off the main thread, from what it was derived from (its
 * [AnalysisData], saved next to it by [SongJobs]): with fewer or more notes ([Spec.Detected]), or
 * made of the notes of a tab the player pasted, lined up with the recording ([Spec.FromTab]).
 * One at a time, in the order asked. Lines are kept for a while (the last few), so going back and
 * forth is instant, and handed on the main thread to the screens listening. The work holds no
 * screen, so leaving or turning the screen meanwhile leaks nothing.
 */
object SongLines {
    /** Which line of song [id]. */
    sealed interface Spec {
        val id: String

        /** The line found in the recording, with [detail] notes. */
        data class Detected(override val id: String, val detail: Detail) : Spec

        /**
         * The notes of the tab [text] at the times they're heard, for [instrument] (the bouzouki the player
         * plays): the tab is read for it first (see [readTab]), and its notes keep the tab's frets on it.
         */
        data class FromTab(override val id: String, val text: String, val instrument: Instrument) : Spec {
            override fun toString() = "FromTab($id, ${text.length} chars, $instrument)"
        }
    }

    /**
     * How a tab lined up: [tab] as read, and [matched] of its [total] notes found in the recording, not
     * those in [missing]; [differs] were heard only at another pitch. [sources] says which tab note each
     * note of the line comes from (-1: a detected note, kept where the tab isn't heard). The recording is
     * [transpose] semitones (and [octave] octaves) above the tab. See [TabAlignment].
     */
    class TabReport(
        val tab: ParsedTab,
        val matched: Int,
        val total: Int,
        val missing: List<Int>,
        val sources: List<Int>,
        val differs: List<Int>,
        val transpose: Int,
        val octave: Int,
    ) {
        /** The line's notes (by index) from tab notes not heard in the recording, and from those heard at another pitch. */
        val unheard: Set<Int> = notesFrom(missing)
        val offPitch: Set<Int> = notesFrom(differs)

        private fun notesFrom(tabNotes: List<Int>): Set<Int> {
            if (tabNotes.isEmpty()) return emptySet()
            val wanted = tabNotes.toHashSet()
            return sources.indices.filterTo(HashSet()) { sources[it] >= 0 && sources[it] in wanted }
        }
    }

    sealed interface Outcome {
        val spec: Spec

        /** The line, for the bouzouki it was made for (the player's for a tab's line, else the one the song was prepared for). */
        class Line(override val spec: Spec, val song: SongAnalysis, val report: TabReport? = null) : Outcome

        /** The song was prepared before what its line comes from was kept (or that can't be read): it needs preparing again. */
        class NoData(override val spec: Spec) : Outcome

        class Failed(override val spec: Spec, val message: String) : Outcome
    }

    // Everything but the work itself runs on the main thread.
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "song-lines").apply { isDaemon = true } }
    private val listeners = LinkedHashSet<(Outcome) -> Unit>()

    /** A line asked for: its [spec] and the data file it comes from (by when it was written), so a song prepared again gets new lines. */
    private data class Key(val spec: Spec, val stamp: Long)
    private val done = object : LinkedHashMap<Key, Outcome>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Outcome>) = size > KEEP
    }
    private val running = HashSet<Key>()

    // The last song's data, for the worker only: reading it again takes a moment.
    private var lastData: Triple<String, Long, SoftReference<AnalysisData>>? = null

    fun listen(l: (Outcome) -> Unit) { listeners += l }
    fun unlisten(l: (Outcome) -> Unit) { listeners -= l }

    /**
     * The line [spec] of [base] (the song as prepared) if it's ready, or null when it's being worked
     * out: it then comes to the listeners. [Outcome.NoData] at once for a song prepared before its
     * data was kept; a data file that can't be read is deleted (so [SongJobs.hasData] says so) and
     * [Outcome.NoData] comes to the listeners.
     */
    fun request(context: Context, base: SongAnalysis, spec: Spec): Outcome? {
        val app = context.applicationContext
        val stamp = SongJobs.dataStamp(app, spec.id)
        if (stamp == 0L) return Outcome.NoData(spec)
        val key = Key(spec, stamp)
        done[key]?.let { return it }
        if (!running.add(key)) return null
        worker.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val out = try {
                derive(app, base, spec, stamp)
            } catch (_: OutOfMemoryError) {
                lastData = null
                Outcome.Failed(spec, if (spec is Spec.FromTab) "The tab is too long for the phone's memory: paste a shorter part of it."
                    else "The song is too long for the phone's memory.")
            } catch (e: Exception) {
                Outcome.Failed(spec, e.message ?: e.javaClass.simpleName)
            }
            main.post {
                running -= key
                if (out is Outcome.Line) done[key] = out
                for (l in listeners.toList()) l(out)
            }
        }
        return null
    }

    private fun derive(app: Context, base: SongAnalysis, spec: Spec, stamp: Long): Outcome {
        val data = data(app, spec.id, stamp)
        if (data == null) {
            SongJobs.dropData(app, spec.id, stamp) // it can't be read: the song asks to be prepared again
            return Outcome.NoData(spec)
        }
        return when (spec) {
            is Spec.Detected -> Outcome.Line(spec, SongAnalyzer.rebuild(base, data, spec.detail))
            is Spec.FromTab -> {
                val tab = readTab(spec.text, spec.instrument)
                // Lining up takes time and memory growing faster than the notes: a whole songbook pasted would stall the phone.
                if (tab.notes.size > MAX_TAB_NOTES) {
                    return Outcome.Failed(spec, "it has ${tab.notes.size} notes, more than the $MAX_TAB_NOTES a tab can have here. Copy just this song's bouzouki part.")
                }
                // Lined up against the song fingered for the player's bouzouki, so the line keeps the tab's frets on it.
                val a = TabAligner.align(tab, data, base.forInstrument(spec.instrument))
                Outcome.Line(spec, a.song, TabReport(tab, a.matched, a.total, a.missing, a.sources, a.differs, a.transpose, a.octave))
            }
        }
    }

    private fun data(app: Context, id: String, stamp: Long): AnalysisData? {
        lastData?.let { (lastId, lastStamp, ref) -> if (lastId == id && lastStamp == stamp) ref.get()?.let { return it } }
        lastData = null
        val data = SongJobs.data(app, id) ?: return null
        lastData = Triple(id, stamp, SoftReference(data))
        return data
    }

    /**
     * Reads a tab for [preferred] (the bouzouki it was pasted for); if that leaves notes out or
     * finds none, for the other bouzouki too, and keeps the reading with the most notes (then the
     * fewest problems): a tab for three courses read for four, or the other way round, shows it.
     */
    fun readTab(text: String, preferred: Instrument): ParsedTab {
        val first = TabParser.parse(text, preferred)
        if (first.notes.isNotEmpty() && first.warnings.isEmpty()) return first
        val other = TabParser.parse(text, Instrument.entries.first { it != preferred })
        val better = other.notes.size > first.notes.size ||
            (other.notes.size == first.notes.size && other.notes.isNotEmpty() && other.warnings.size < first.warnings.size)
        return if (better) other else first
    }

    /** Lines kept. */
    private const val KEEP = 8

    /** The most notes a tab lined up may have (a long song's tab has several hundred). */
    const val MAX_TAB_NOTES = 1500
}
