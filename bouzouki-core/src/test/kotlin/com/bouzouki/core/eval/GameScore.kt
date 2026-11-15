package com.bouzouki.core.eval

import com.bouzouki.core.Instrument
import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.SongNote
import com.bouzoukihero.core.Charts
import com.bouzoukihero.core.Difficulty
import com.bouzoukihero.core.Gem
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How a recording's line plays in Bouzouki Hero. For each difficulty the game's chart of the line found (made as the
 * game makes it: [Charts.fromTab] of the notes with their frets, tremolo and confidence) is set against the chart the
 * game would make of the notes really played (the ideal chart: the same, every note without doubt):
 * - gem precision: the found chart's gems with a played note starting within the difficulty's hit window, at any
 *   pitch (the player hears the bouzouki where the button comes);
 * - gem recall: the ideal chart's gems with a gem of the found chart within the window;
 * - phantom gems: the found chart's gems where the bouzouki is silent (none of its notes sounding within
 *   [QUIET] seconds either side), counted a minute of such silence.
 * The note scores ask for the right pitch within 50 ms; these ask what a player notices: a button with no note
 * to it, and a note of the tune with no button.
 */
object GameScore {
    /** How far from every played note (seconds, from its start to its end) the bouzouki counts as silent. */
    const val QUIET = 1.0

    /** One difficulty's charts compared, for one recording or summed over several ([add]). */
    class Counts {
        /** Gems in the found chart; those of them with a played note starting within the window; those where the bouzouki is silent. */
        var gems = 0; var hit = 0; var phantom = 0
        /** Gems in the ideal chart; those of them with a found gem within the window. */
        var ideal = 0; var covered = 0
        /** Seconds in which the bouzouki is silent. */
        var silent = 0.0

        val precision get() = if (gems == 0) 1.0 else hit.toDouble() / gems
        val recall get() = if (ideal == 0) 1.0 else covered.toDouble() / ideal
        val f get() = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        val phantomsAMinute get() = if (silent <= 0.0) Double.NaN else 60.0 * phantom / silent

        fun add(o: Counts) {
            gems += o.gems; hit += o.hit; phantom += o.phantom; ideal += o.ideal; covered += o.covered; silent += o.silent
        }
    }

    /** A recording's counts for every difficulty: with the game's confidence floors ([game]) and with none ([noFloor]). */
    class Result(val game: Map<Difficulty, Counts>, val noFloor: Map<Difficulty, Counts>)

    fun of(ref: List<RefNote>, est: List<SongNote>, instrument: Instrument, seconds: Double, solo: Boolean = false) = Result(
        Difficulty.entries.associateWith { of(ref, est, instrument, seconds, it, it.confidenceFloor, solo) },
        Difficulty.entries.associateWith { of(ref, est, instrument, seconds, it, 0f, solo) },
    )

    /**
     * The found chart of [est] for [difficulty], leaving out the notes less sure than [floor], against the ideal chart
     * of [ref]. A [floor] under 0 ([BEFORE]) is the game from before the notes said how sure they are: every note
     * taken as without doubt, so neither left out nor preferred for it. So is a line prepared as the bouzouki alone
     * ([solo]), as in the game: there is no band whose notes could be in it.
     */
    fun of(
        ref: List<RefNote>, est: List<SongNote>, instrument: Instrument, seconds: Double, difficulty: Difficulty, floor: Float, solo: Boolean = false,
    ): Counts {
        val sure = floor < 0f || solo
        val found = chart(est.map { tabNote(it.start, it.end, it.midi, it.course, it.fret, it.articulation, if (sure) 1f else it.confidence) },
            instrument, difficulty, floor.coerceAtLeast(0f))
        val ideal = chart(ref.map { tabNote(it.start, it.end, it.midi, it.course, it.fret, it.articulation, 1f) }, instrument, difficulty, 0f)
        val played = ref.map { it.start }
        val w = difficulty.window
        val c = Counts()
        c.gems = found.size
        c.hit = found.count { g -> played.any { abs(it - g.time) <= w } }
        c.phantom = found.count { g -> ref.none { g.time >= it.start - QUIET && g.time <= it.end + QUIET } }
        c.ideal = ideal.size
        c.covered = ideal.count { g -> found.any { abs(it.time - g.time) <= w } }
        c.silent = silence(ref, seconds)
        return c
    }

    /** The game's chart, as its activity builds it from a prepared song (HeroActivity). */
    fun chart(notes: List<Charts.TabNote>, instrument: Instrument, difficulty: Difficulty, floor: Float): List<Gem> =
        Charts.fromTab(notes, difficulty, Charts.courseLabels(instrument.courses.map { it.openMidi }), floor)

    // A reference without its place on the neck still makes a chart: where a note is played doesn't change when.
    private fun tabNote(start: Double, end: Double, midi: Int, course: Int, fret: Int, a: Articulation, confidence: Float) =
        Charts.TabNote(start, end - start, midi, max(course, 0), max(fret, 0), a == Articulation.TREMOLO, confidence)

    /** The seconds of a recording [seconds] long that are more than [QUIET] from every note played. */
    fun silence(ref: List<RefNote>, seconds: Double): Double {
        var sounding = 0.0
        var upTo = 0.0
        for (r in ref.sortedBy { it.start }) {
            val from = max(upTo, r.start - QUIET)
            val to = min(seconds, r.end + QUIET)
            if (to > from) { sounding += to - from; upTo = to }
        }
        return (seconds - sounding).coerceAtLeast(0.0)
    }

    private fun sum(all: List<Map<Difficulty, Counts>>, d: Difficulty) = Counts().also { s -> all.forEach { m -> m[d]?.let(s::add) } }

    private const val HEAD = "| | floor | gems | ideal gems | gem F | gem P | gem R | phantom gems | a minute of silence |\n|---|---|---|---|---|---|---|---|---|\n"

    private fun row(name: String, floor: String, c: Counts) = String.format(Locale.US, "| %s | %s | %d | %d | %.3f | %.3f | %.3f | %d | %s |\n",
        name, floor, c.gems, c.ideal, c.f, c.precision, c.recall, c.phantom, if (c.phantomsAMinute.isNaN()) "–" else String.format(Locale.US, "%.1f", c.phantomsAMinute))

    /** As a floor: the confidence not used at all (see [of]). */
    const val BEFORE = -1f

    private fun floorName(f: Float) = if (f < 0f) "confidence not used" else if (f == 0f) "none" else String.format(Locale.US, "%.2f", f)

    private const val LEGEND = "The chart Bouzouki Hero makes of the line found, against the chart it would make of the notes really played " +
        "(the ideal chart). Gem P: the found chart's gems with a played note starting within the difficulty's hit window, at any pitch. " +
        "Gem R: the ideal chart's gems with a found gem within the window. Phantom gems: found gems where the bouzouki is silent " +
        "(none of its notes sounding within 1 s either side), and how many that is a minute of such silence. " +
        "A line prepared as the bouzouki alone is played whole, whatever the floor.\n\n"

    /** The report's part: every difficulty with the game's floor and with none, over all the recordings and by kind. */
    fun markdown(results: List<Pair<EvalItem, Result>>): String = buildString {
        if (results.isEmpty()) return@buildString
        append("\n## Bouzouki Hero\n\n").append(LEGEND)
        append(String.format(Locale.US, "The bouzouki is silent for %.1f s of these recordings.\n", results.sumOf { it.second.game.values.first().silent }))
        val groups = listOf("all" to results) + results.groupBy { "kind: " + it.first.kind }.toList().takeIf { it.size > 1 }.orEmpty()
        for ((name, rs) in groups) {
            append("\n**$name** (${rs.size} recordings)\n\n").append(HEAD)
            for (d in Difficulty.entries) {
                append(row(d.label, floorName(d.confidenceFloor), sum(rs.map { it.second.game }, d)))
                if (d.confidenceFloor > 0f) append(row(d.label, "none", sum(rs.map { it.second.noFloor }, d)))
            }
        }
    }

    /** The CSV's columns for the game's charts (with the game's floors), and a recording's values for them. */
    val csvHead: String = "game_silent_s," + Difficulty.entries.joinToString(",") { d ->
        val k = d.name.lowercase(Locale.ROOT)
        "game_${k}_gems,game_${k}_hit,game_${k}_phantom,game_${k}_ideal,game_${k}_covered"
    }

    fun csv(r: Result): String = String.format(Locale.US, "%.2f,", r.game.values.first().silent) + Difficulty.entries.joinToString(",") { d ->
        val c = r.game.getValue(d)
        "${c.gems},${c.hit},${c.phantom},${c.ideal},${c.covered}"
    }

    /** A table of every difficulty with each of [floors] (and the game's own, marked), over [recordings]: played, found, bouzouki, seconds. */
    fun compare(recordings: List<Recording>, floors: List<Float>): String = buildString {
        append(HEAD)
        for (d in Difficulty.entries) {
            for (f in (floors + d.confidenceFloor).distinct().sorted()) {
                val s = Counts()
                for (r in recordings) s.add(of(r.played, r.found, r.instrument, r.seconds, d, f, r.solo))
                append(row(d.label, floorName(f) + if (f == d.confidenceFloor) " (the game's)" else "", s))
            }
        }
    }

    /** A recording of a run made before, read back from its notes dump; [solo] if it was prepared as the bouzouki alone. */
    class Recording(
        val name: String, val instrument: Instrument, val seconds: Double, val played: List<RefNote>, val found: List<SongNote>,
        val solo: Boolean = false,
    )

    /**
     * The recordings of a run from its notes dump ([ErrorAnalysis.csv]: every note played and found) and its
     * summary beside it ([Evaluator.csv]: each recording's bouzouki, length and kind: a "solo" one is taken as
     * prepared as the bouzouki alone, as the harness does by default). A dump from before the notes carried a
     * confidence reads as notes without doubt.
     */
    fun read(notes: File, summary: File): List<Recording> {
        val head = summary.readLines().first().split(',')
        val about = summary.readLines().drop(1).map { it.split(',') }.associate { it[head.indexOf("item")] to it }
        val played = LinkedHashMap<String, ArrayList<RefNote>>()
        val found = LinkedHashMap<String, ArrayList<SongNote>>()
        val lines = notes.readLines()
        val withConfidence = lines.first().endsWith(",confidence")
        for (line in lines.drop(1)) {
            // The first eight fields are plain; the texts after them may hold commas, the confidence is the last.
            val p = line.split(",", limit = 9)
            if (p.size < 9) continue
            val a = Articulation.valueOf(p[7])
            if (p[1] == "played") played.getOrPut(p[0]) { ArrayList() } += RefNote(p[2].toDouble(), p[3].toDouble(), p[4].toInt(), p[5].toInt(), p[6].toInt(), a)
            else found.getOrPut(p[0]) { ArrayList() } += SongNote(p[2].toDouble(), p[3].toDouble(), p[4].toInt(), p[5].toInt(), p[6].toInt(), a,
                if (withConfidence) line.substringAfterLast(',').toFloatOrNull() ?: 1f else 1f)
        }
        return (played.keys + found.keys).distinct().mapNotNull { name ->
            val row = about[name] ?: return@mapNotNull null
            Recording(name, Instrument.valueOf(row[head.indexOf("instrument")]), row[head.indexOf("seconds")].toDouble(),
                played[name].orEmpty(), found[name].orEmpty(), row[head.indexOf("kind")] == "solo")
        }
    }
}

/**
 * Developer tool: the game score ([GameScore]) of a run made before, from its notes dump, with each of a list of
 * confidence floors: to choose the floors, and to score the line of an earlier engine without running it again.
 * `gradle :bouzouki-core:gameScore -PgameNotes=<label>.notes.csv [-PgameFloors=-1,0,0.1,0.2] [-PgameOnly=<regex>]`
 * (the dump is written by `evalSet -PevalDump=true`; `<label>.csv` must be beside it). A floor of -1 is the game
 * as it was before it used the confidence ([GameScore.BEFORE]); each difficulty's own floor is always shown.
 */
object GameScoreTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val notes = File(args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: error("gameNotes: the <label>.notes.csv of a run"))
        val floors = (args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "0").split(',').map { it.trim().toFloat() }
        val only = args.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::Regex)
        val summary = File(notes.parentFile, notes.name.removeSuffix(".notes.csv") + ".csv")
        val recordings = GameScore.read(notes, summary).filter { only == null || only.containsMatchIn(it.name) }
        require(recordings.isNotEmpty()) { "no recordings in $notes" }
        println("Game score of ${notes.name}: ${recordings.size} recordings, ${recordings.sumOf { it.found.size }} notes found, " +
            "${recordings.sumOf { it.played.size }} played; the bouzouki is silent for " +
            String.format(Locale.US, "%.1f s of %.1f s.", recordings.sumOf { GameScore.silence(it.played, it.seconds) }, recordings.sumOf { it.seconds }))
        println()
        print(GameScore.compare(recordings, floors))
    }
}
