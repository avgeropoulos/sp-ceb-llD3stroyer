package com.bouzouki.core.eval

import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.SongNote
import java.util.Locale
import kotlin.math.abs

/**
 * What went wrong, note by note: every note played with whether it was found and, if not, what the transcription
 * has there instead ([Played]); every note found with whether it is right and, if not, what it most likely is
 * ([Found]). With the band of a synthetic mix ([SynthSet.band]) the wrong notes are told apart by whose pitch they
 * have: the voice's (an octave under the bouzouki, 100 ms late), a strum's string, the bass's.
 */
object ErrorAnalysis {
    /** A note played: [found] within the scoring window; [error] its onset error if found within the wider timing one; [why] not found; [traits] to group by. */
    class Played(val item: EvalItem, val note: RefNote, val found: Boolean, val error: Double?, val why: String, val traits: Map<String, String>)

    /** A note the transcription has: [right] or else [what] it most likely is (the first of [flags] that holds), and [where] it starts. */
    class Found(val item: EvalItem, val note: SongNote, val right: Boolean, val played: Articulation?, val what: String, val flags: List<String>, val where: String)

    /** [silent]: the seconds of the recording's [seconds] in which the bouzouki doesn't play (see [silence]). */
    class Notes(val played: List<Played>, val found: List<Found>, val seconds: Double = 0.0, val silent: Double = 0.0)

    /**
     * The seconds of a recording [seconds] long without the bouzouki: before its first note, after its last one, and
     * in its rests of 0.3 s or more, each less 0.1 s at either end (as [Found.where] tells a note "while the bouzouki
     * plays" from the others).
     */
    fun silence(ref: List<RefNote>, seconds: Double): Double {
        if (ref.isEmpty()) return seconds
        var s = (ref.first().start - 0.1).coerceAtLeast(0.0) + (seconds - ref.maxOf { it.end } - 0.1).coerceAtLeast(0.0)
        var end = ref.first().end
        for (r in ref.drop(1)) {
            if (r.start - end >= 0.3) s += (r.start - end - 0.2).coerceAtLeast(0.0)
            end = maxOf(end, r.end)
        }
        return s
    }

    fun register(midi: Int) = when {
        midi < 57 -> "1: under A3 (midi < 57)"
        midi < 62 -> "2: A3 to C#4 (57-61)"
        midi < 69 -> "3: D4 to G#4 (62-68)"
        midi < 76 -> "4: A4 to D#5 (69-75)"
        else -> "5: E5 and up (76+)"
    }

    private fun length(r: RefNote): String {
        if (r.articulation == Articulation.TREMOLO) return "5: tremolo, held"
        val slot = (r.end - r.start) / 0.92
        return when {
            slot < 0.14 -> "1: sixteenth (125 ms)"
            slot < 0.2 -> "2: triplet (167 ms)"
            slot < 0.3 -> "3: eighth (250 ms)"
            else -> "4: quarter (500 ms) or longer"
        }
    }

    fun of(item: EvalItem, ref: List<RefNote>, est: List<SongNote>, band: List<SynthSet.BandNote>?, seconds: Double = 0.0): Notes {
        val exact = Metrics.match(ref, est) { r, e -> r.midi == e.midi }
        val wide = Metrics.match(ref, est, Metrics.TIMING_TOL) { r, e -> r.midi == e.midi }
        val exactR = exact.associate { it.first to it.second }
        val exactE = exact.associate { it.second to it.first }
        val wideR = wide.associate { it.first to it.second }
        val wideE = wide.associate { it.second to it.first }
        val strums = band?.filter { it.part == "strum" } ?: emptyList()
        val beats = band?.filter { it.part == "bass" } ?: emptyList()
        val voice = band?.filter { it.part == "voice" } ?: emptyList()

        // What a wrong note most likely is: everything that holds, the likeliest first.
        fun flags(j: Int): List<String> {
            val e = est[j]
            val out = ArrayList<String>()
            wideE[j]?.takeIf { j !in exactE && exactR[it] == null }?.let {
                out += if (e.start > ref[it].start) "the right note, 50-100 ms late" else "the right note, 50-100 ms early"
            }
            val near = ref.withIndex().filter { (_, r) -> r.midi == e.midi && abs(r.start - e.start) <= 0.25 }
            if (out.isEmpty() && near.any { (i, _) -> i !in exactR }) out += "the right note, 100-250 ms off"
            if (ref.withIndex().any { (i, r) -> r.midi == e.midi && e.start >= r.start - 0.05 && e.start <= r.end + 0.05 && exactR[i].let { it != null && it != j } })
                out += "a bouzouki note found twice (still sounding)"
            if (ref.any { r -> r.midi - 12 == e.midi && abs(r.start - e.start) <= 0.04 }) out += "an octave under a bouzouki note, at its onset"
            if (voice.any { v -> v.midi == e.midi && e.start > v.start - 0.06 && e.start <= v.end }) out += "the voice's pitch (an octave under the bouzouki, later than its onset)"
            if (ref.any { r -> r.midi + 12 == e.midi && e.start >= r.start - 0.05 && e.start <= r.end + 0.05 }) {
                val pair = ref.any { r -> r.midi + 12 == e.midi && e.start >= r.start - 0.05 && e.start <= r.end + 0.05 && r.course >= 0 && item.instrument.courses[r.course].octavePair }
                out += if (pair) "an octave over a bouzouki note (an octave-paired course)" else "an octave over a bouzouki note (a unison course)"
            }
            if (strums.any { s -> s.midi == e.midi && abs(s.start - e.start) <= 0.06 }) out += "a strum's string, at the strum"
            else if (strums.any { s -> s.midi == e.midi && e.start > s.start && e.start <= s.end }) out += "a strum's string, ringing"
            if (beats.any { b -> Math.floorMod(b.midi - e.midi, 12) == 0 && e.start >= b.start - 0.05 && e.start <= b.end }) out += "the bass's pitch class"
            if (ref.any { r -> abs(r.midi - e.midi) == 1 && abs(r.start - e.start) <= 0.05 }) out += "a semitone off a bouzouki note"
            if (ref.any { r -> r.midi == e.midi && e.start > r.end + 0.05 && e.start <= r.end + 0.5 }) out += "a bouzouki note's pitch again, after it ended"
            if (out.isEmpty()) out += "none of these"
            return out
        }
        fun where(t: Double): String {
            if (ref.isEmpty() || t < ref.first().start - 0.1) return "before the bouzouki's first note"
            if (t > ref.maxOf { it.end } + 0.1) return "after the bouzouki's last note"
            val before = ref.lastOrNull { it.start <= t + 0.1 }
            val after = ref.firstOrNull { it.start > t + 0.1 }
            return if (before != null && after != null && t > before.end + 0.1 && after.start - before.end >= 0.3) "in a rest of the bouzouki" else "while the bouzouki plays"
        }
        val found = est.indices.map { j ->
            val right = j in exactE
            val f = if (right) emptyList() else flags(j)
            Found(item, est[j], right, exactE[j]?.let { ref[it].articulation }, f.firstOrNull() ?: "", f, where(est[j].start))
        }

        val extras = est.indices.filter { it !in exactE }
        fun why(i: Int): String {
            val r = ref[i]
            wideR[i]?.let { return if (est[it].start > r.start) "found 50-100 ms late" else "found 50-100 ms early" }
            val at = extras.filter { abs(est[it].start - r.start) <= 0.05 }
            at.firstOrNull { Math.floorMod(est[it].midi - r.midi, 12) == 0 }?.let { return if (est[it].midi > r.midi) "found an octave up" else "found an octave down" }
            at.firstOrNull { abs(est[it].midi - r.midi) == 1 }?.let { return "found a semitone off" }
            at.firstOrNull()?.let { return "another pitch there instead: " + found[it].what }
            if (est.any { e -> e.midi == r.midi && e.start < r.start - 0.05 && e.start >= r.start - 1.5 && e.end >= r.start - 0.03 }) return "none there: taken into the note of its pitch before it (a re-pick not split)"
            if (extras.any { est[it].midi == r.midi && abs(est[it].start - r.start) <= 0.25 }) return "found 100-250 ms off"
            return if (est.any { e -> e.start < r.start - 0.05 && e.end > r.start + 0.05 }) "none there: the line holds another note through it" else "none there: the line is silent"
        }
        fun bandAt(t: Double): String {
            if (band == null) return "no band"
            val s = strums.minOfOrNull { abs(it.start - t) } ?: 9.0
            val b = beats.minOfOrNull { abs(it.start - t) } ?: 9.0
            return when {
                s <= 0.035 && s <= b -> "1: a strum within 35 ms"
                b <= 0.035 -> "2: a beat (bass and drum) within 35 ms"
                s <= 0.07 && s <= b -> "3: a strum 35-70 ms away"
                b <= 0.07 -> "4: a beat (bass and drum) 35-70 ms away"
                else -> "5: clear of the band's attacks (over 70 ms)"
            }
        }
        val played = ref.indices.map { i ->
            val r = ref[i]
            val prev = ref.getOrNull(i - 1)
            val traits = LinkedHashMap<String, String>()
            traits["register"] = register(r.midi)
            traits["course"] = if (r.course < 0) "unknown" else if (item.instrument.courses[r.course].octavePair) "octave-paired course" else "unison course"
            traits["length"] = length(r)
            traits["played"] = r.articulation.name.lowercase(Locale.ROOT)
            traits["step from the note before"] = when {
                prev == null -> "0: first note"
                r.start - prev.end > 0.3 -> "5: after a rest"
                abs(r.midi - prev.midi) == 0 -> "1: the same pitch again"
                abs(r.midi - prev.midi) <= 2 -> "2: a step (1-2 semitones)"
                abs(r.midi - prev.midi) <= 7 -> "3: 3 to 7 semitones"
                else -> "4: 8 semitones or more"
            }
            if (band != null) {
                traits["the band at its onset"] = bandAt(r.start)
                traits["its pitch in the strum ringing then"] = if (strums.any { s -> s.midi == r.midi && r.start > s.start - 0.06 && r.start <= s.end }) "yes" else "no"
            }
            Played(item, r, i in exactR, wideR[i]?.let { est[it].start - r.start }, if (i in exactR) "" else why(i), traits)
        }
        return Notes(played, found, seconds, silence(ref, seconds))
    }

    private fun StringBuilder.tally(title: String, first: String, rows: List<Pair<String, Int>>, total: Int) {
        append("\n$title\n\n| $first | notes | share |\n|---|---|---|\n")
        for ((k, n) in rows) append(String.format(Locale.US, "| %s | %d | %.1f%% |\n", k, n, 100.0 * n / total.coerceAtLeast(1)))
    }

    /** The tables of the report: what is missed (and how often, by trait), what is extra, and how the notes found were marked. */
    fun markdown(all: List<Notes>): String = buildString {
        val played = all.flatMap { it.played }
        val found = all.flatMap { it.found }
        val missed = played.filter { !it.found }
        val extra = found.filter { !it.right }
        append("\n## Error analysis\n\n")
        append("${played.size} notes played, ${missed.size} missed; ${found.size} notes found, ${extra.size} extra (not a played note's pitch within ${(Metrics.ONSET_TOL * 1000).toInt()} ms).\n")
        tally("### Missed notes: what the transcription has there", "what happened", missed.groupingBy { it.why }.eachCount().toList().sortedByDescending { it.second }, missed.size)
        append("\n### Missed notes: how often, by what the note is\n\n| trait | value | played | missed | miss rate |\n|---|---|---|---|---|\n")
        for (trait in played.flatMap { it.traits.keys }.distinct()) {
            for ((value, ps) in played.filter { trait in it.traits }.groupBy { it.traits.getValue(trait) }.toSortedMap()) {
                val m = ps.count { !it.found }
                append(String.format(Locale.US, "| %s | %s | %d | %d | %.1f%% |\n", trait, value, ps.size, m, 100.0 * m / ps.size))
            }
        }
        tally("### Extra notes: what each most likely is (the first that holds)", "what it is", extra.groupingBy { it.what }.eachCount().toList().sortedByDescending { it.second }, extra.size)
        tally("### Extra notes: everything that holds (a note can be in several rows)", "what holds", extra.flatMap { it.flags }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }, extra.size)
        tally("### Extra notes: where they start", "where", extra.groupingBy { it.where }.eachCount().toList().sortedByDescending { it.second }, extra.size)
        val silent = all.sumOf { it.silent }
        if (silent > 0) {
            val there = extra.count { it.where != "while the bouzouki plays" }
            append(String.format(Locale.US, "\nThe bouzouki is silent for %.1f s of %.1f s (before its first note, after its last one, in rests of 0.3 s or more): %d notes found there, %.1f a minute of silence. While it plays: %d extra notes in %.1f s, %.1f a minute.\n",
                silent, all.sumOf { it.seconds }, there, 60.0 * there / silent, extra.size - there, all.sumOf { it.seconds } - silent, 60.0 * (extra.size - there) / (all.sumOf { it.seconds } - silent).coerceAtLeast(1e-9)))
            append("\n| recording | seconds | silent seconds | notes found in the silence | a minute |\n|---|---|---|---|---|\n")
            for (n in all.filter { it.silent >= 3.0 }) {
                val k = n.found.count { !it.right && it.where != "while the bouzouki plays" }
                append(String.format(Locale.US, "| %s | %.1f | %.1f | %d | %.1f |\n", n.played.firstOrNull()?.item?.name ?: "?", n.seconds, n.silent, k, 60.0 * k / n.silent))
            }
        }
        append("\n### Extra notes: by register and by how they were marked\n\n| | found | extra | share of found that is extra |\n|---|---|---|---|\n")
        for ((k, fs) in found.groupBy { register(it.note.midi) }.toSortedMap())
            append(String.format(Locale.US, "| register %s | %d | %d | %.1f%% |\n", k, fs.size, fs.count { !it.right }, 100.0 * fs.count { !it.right } / fs.size))
        for ((k, fs) in found.groupBy { it.note.articulation }.toSortedMap())
            append(String.format(Locale.US, "| marked %s | %d | %d | %.1f%% |\n", k.name.lowercase(Locale.ROOT), fs.size, fs.count { !it.right }, 100.0 * fs.count { !it.right } / fs.size))
        append("\n### Notes found right: how they were played and how they were marked\n\n| played | found | marked pick | hammer | pull | slide | tremolo |\n|---|---|---|---|---|---|---|\n")
        for ((p, fs) in found.filter { it.right }.groupBy { it.played!! }.toSortedMap()) {
            append("| ${p.name.lowercase(Locale.ROOT)} | ${fs.size} |")
            for (a in listOf(Articulation.PICK, Articulation.HAMMER, Articulation.PULL, Articulation.SLIDE, Articulation.TREMOLO)) append(" ${fs.count { it.note.articulation == a }} |")
            append("\n")
        }
    }

    /** Every note played and found, one per line, for other tools. */
    fun csv(all: List<Notes>): String = buildString {
        append("item,side,start,end,midi,course,fret,articulation,ok,onset_error_ms,what,where_or_traits\n")
        fun q(s: String) = "\"" + s.replace("\"", "'") + "\""
        for (n in all) {
            for (p in n.played) append(String.format(Locale.US, "%s,played,%.4f,%.4f,%d,%d,%d,%s,%d,%s,%s,%s\n", p.item.name, p.note.start, p.note.end, p.note.midi,
                p.note.course, p.note.fret, p.note.articulation, if (p.found) 1 else 0, p.error?.let { String.format(Locale.US, "%.1f", it * 1000) } ?: "",
                q(p.why), q(p.traits.entries.joinToString("; ") { "${it.key}=${it.value}" })))
            for (f in n.found) append(String.format(Locale.US, "%s,found,%.4f,%.4f,%d,%d,%d,%s,%d,,%s,%s\n", f.item.name, f.note.start, f.note.end, f.note.midi,
                f.note.course, f.note.fret, f.note.articulation, if (f.right) 1 else 0, q(f.flags.joinToString(" + ")), q(f.where)))
        }
    }
}
