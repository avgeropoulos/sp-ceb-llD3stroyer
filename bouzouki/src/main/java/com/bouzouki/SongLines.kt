package com.bouzouki

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.bouzouki.core.Instrument
import com.bouzouki.core.song.AnalysisData
import com.bouzouki.core.song.Detail
import com.bouzouki.core.song.ImageTab
import com.bouzouki.core.song.ParsedTab
import com.bouzouki.core.song.PdfTab
import com.bouzouki.core.song.SongAnalysis
import com.bouzouki.core.song.SongAnalyzer
import com.bouzouki.core.song.TabAligner
import com.bouzouki.core.song.TabParser
import java.io.File
import java.io.IOException
import java.lang.ref.SoftReference
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A saved song's line derived again, off the main thread, from what it was derived from (its
 * [AnalysisData], saved next to it by [SongJobs]): with fewer or more notes ([Spec.Detected]), or
 * made of the notes of a tab the player pasted or opened (text or PDF), lined up with the recording
 * ([Spec.FromTab]).
 * One at a time, in the order asked; a line no longer wanted (another line of the song was asked for
 * since, or [settle]d on) isn't worked out, and one being worked out stops. Lines are kept for a while
 * (the last few), so going back and forth is instant, and handed on the main thread to the screens
 * listening. The work holds no screen, so leaving or turning the screen meanwhile leaks nothing (a
 * line being worked out goes on, for next time).
 */
object SongLines {
    /** Which line of song [id]. */
    sealed interface Spec {
        val id: String

        /** The line found in the recording, with [detail] notes. */
        data class Detected(override val id: String, val detail: Detail) : Spec

        /**
         * The notes of the tab in [source] at the times they're heard, for [instrument] (the bouzouki the player
         * plays): the tab is read for it first (see [readTab], [readPdf]), and its notes keep the tab's frets on it.
         */
        data class FromTab(override val id: String, val source: TabSource, val instrument: Instrument) : Spec {
            constructor(id: String, text: String, instrument: Instrument) : this(id, TabSource.Text(text), instrument)

            override fun toString() = "FromTab($id, $source, $instrument)"
        }
    }

    /** Where a tab comes from: its text (pasted, or a text file), or a PDF file, read when its line is worked out. */
    sealed interface TabSource {
        data class Text(val text: String) : TabSource {
            override fun toString() = "${text.length} chars"
        }

        /**
         * A PDF tab in [file], known by [stamp] (when it was written, and its size: see [SongJobs.stamp]). Moving
         * the file to where it's kept for its song doesn't change that, so its line stays the one worked out.
         */
        class Pdf(val file: File, val stamp: Long = SongJobs.stamp(file)) : TabSource {
            override fun equals(other: Any?) = other is Pdf && other.stamp == stamp
            override fun hashCode() = stamp.hashCode()
            override fun toString() = "PDF $stamp"
        }
    }

    /**
     * How a tab lined up: [tab] as read, and [matched] of its [total] notes found in the recording, not
     * those in [missing]: [differs] of those were heard, but at another pitch ([moved] of them shown at the
     * pitch heard, the others as written), the rest not at all ([notHeard]). [sources] says which tab note
     * each note of the line comes from (-1: a detected note, kept where the tab isn't heard). The recording
     * is [transpose] semitones (and [octave] octaves) above the tab. See [TabAlignment].
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
        val moved: List<Int> = emptyList(),
    ) {
        /** The tab notes not heard in the recording at all (those heard at another pitch aren't). */
        val notHeard: List<Int> = differs.toHashSet().let { off -> missing.filter { it !in off } }

        /** The line's notes (by index) from tab notes not heard in the recording, and from those heard at another pitch. */
        val unheard: Set<Int> = notesFrom(notHeard)
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

    /** The line last asked for, for each song: the worker skips (or stops) work on any other. */
    private val latest = ConcurrentHashMap<String, Spec>()

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
        latest[spec.id] = spec
        val stamp = SongJobs.dataStamp(app, spec.id)
        if (stamp == 0L) return Outcome.NoData(spec)
        val key = Key(spec, stamp)
        done[key]?.let { return it }
        if (!running.add(key)) return null
        worker.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val stale = { latest[spec.id] != spec }
            val out = if (stale()) null else try {
                derive(app, base, spec, stamp, stale)
            } catch (_: CancellationException) {
                null // asked for another line of the song meanwhile
            } catch (_: OutOfMemoryError) {
                lastData = null
                Outcome.Failed(spec, when {
                    spec !is Spec.FromTab -> "The song is too long for the phone's memory."
                    spec.source is TabSource.Pdf -> "The PDF is too big for the phone's memory."
                    else -> "The tab is too long for the phone's memory: paste a shorter part of it."
                })
            } catch (e: Exception) {
                Outcome.Failed(spec, e.message ?: e.javaClass.simpleName)
            }
            main.post {
                running -= key
                if (out == null) return@post
                if (out is Outcome.Line) done[key] = out
                for (l in listeners.toList()) l(out)
            }
        }
        return null
    }

    /** The song [id] shows its prepared line now: work on any other line of it is no longer wanted. */
    fun settle(id: String) { latest.remove(id) }

    private fun derive(app: Context, base: SongAnalysis, spec: Spec, stamp: Long, stale: () -> Boolean): Outcome {
        val data = data(app, spec.id, stamp)
        if (data == null) {
            SongJobs.dropData(app, spec.id, stamp) // it can't be read: the song asks to be prepared again
            return Outcome.NoData(spec)
        }
        return when (spec) {
            is Spec.Detected -> Outcome.Line(spec, SongAnalyzer.rebuild(base, data, spec.detail))
            is Spec.FromTab -> {
                val tab = when (val s = spec.source) {
                    is TabSource.Text -> readTab(s.text, spec.instrument)
                    is TabSource.Pdf -> readPdf(s.file, spec.instrument) ?: return Outcome.Failed(spec, "the PDF isn't on the phone any more.")
                }
                // Lining up takes time and memory growing faster than the notes: a whole songbook pasted would stall the phone.
                if (tab.notes.size > MAX_TAB_NOTES) {
                    return Outcome.Failed(spec, "it has ${tab.notes.size} notes, more than the $MAX_TAB_NOTES a tab can have here. " +
                        if (spec.source is TabSource.Pdf) "Use a PDF of just this song's bouzouki part." else "Copy just this song's bouzouki part.")
                }
                // Lined up against the song fingered for the player's bouzouki, so the line keeps the tab's frets on it.
                val a = TabAligner.align(tab, data, base.forInstrument(spec.instrument), cancelled = stale)
                Outcome.Line(spec, a.song, TabReport(tab, a.matched, a.total, a.missing, a.sources, a.differs, a.transpose, a.octave, a.moved))
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

    /**
     * Reads the PDF tab in [file] for [instrument] (the tab's own bouzouki, told by its lines, is in the result), or
     * null if the file is gone. A PDF without tab numbers as text whose pages are pictures (a scan, a tab saved as
     * an image, a screenshot that came in) is read from its pictures ([TabPictures]). Off the main thread only: a
     * long score takes a moment, pictures a few seconds.
     */
    fun readPdf(file: File, instrument: Instrument): ParsedTab? {
        if (!file.isFile) return null
        if (file.length() > TabFiles.MAX_BYTES) return ParsedTab(instrument, emptyList(), listOf("This PDF is too big to be a tab"))
        val bytes = try { file.readBytes() } catch (_: IOException) { return null }
        val tab = PdfTab.read(bytes, instrument)
        if (PdfTab.PICTURES !in tab.warnings) return tab
        return try {
            TabPictures.readPictures(bytes, instrument) ?: tab
        } catch (_: OutOfMemoryError) {
            ParsedTab(instrument, emptyList(), listOf(ImageTab.PICTURE, "The pictures in this PDF are too big for the phone's memory"))
        }
    }

    /** Lines kept. */
    private const val KEEP = 8

    /** The most notes a tab lined up may have (a long song's tab has several hundred). */
    const val MAX_TAB_NOTES = 1500
}
