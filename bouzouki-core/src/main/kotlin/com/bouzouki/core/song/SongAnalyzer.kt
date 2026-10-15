package com.bouzouki.core.song

import com.bouzouki.core.Dromos
import com.bouzouki.core.DromosGuess
import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One note of the bouzouki line, placed on the neck. */
data class SongNote(val start: Double, val end: Double, val midi: Int, val course: Int, val fret: Int)

/** A prepared song: the bouzouki line with frets, and the dromos it seems to be in. */
data class SongAnalysis(
    val id: String,
    val title: String,
    val instrument: Instrument,
    val notes: List<SongNote>,
    val dromos: Dromos?,
    val rootPc: Int,
) {
    /** Index of the note sounding at [time], or -1. */
    fun noteAt(time: Double): Int {
        var lo = 0
        var hi = notes.size - 1
        var found = -1
        while (lo <= hi) { // last note starting at or before `time`
            val mid = (lo + hi) / 2
            if (notes[mid].start <= time) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return if (found >= 0 && time < notes[found].end + 0.05) found else -1
    }

    /** Index of the first note starting after [time]. */
    fun nextAfter(time: Double): Int {
        var lo = 0
        var hi = notes.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (notes[mid].start <= time) lo = mid + 1 else hi = mid }
        return lo
    }

    /** The cache file: one JSON object, one field per property (new fields can simply be added). */
    fun toJson(): String = jsonObject(
        "version" to FORMAT.toString(),
        "id" to quote(id),
        "title" to quote(title),
        "instrument" to quote(instrument.name),
        "dromos" to (dromos?.let { quote(it.name) } ?: "null"),
        "root" to rootPc.toString(),
        "notes" to notes.joinToString(",", "[", "]") { n -> "[${fmt(n.start)},${fmt(n.end)},${n.midi},${n.course},${n.fret}]" },
    )

    companion object {
        /** Bump when the analysis changes, so old cached songs get analysed again. */
        const val FORMAT = 2

        private fun jsonObject(vararg fields: Pair<String, String>) = fields.joinToString(",", "{", "}") { (k, v) -> quote(k) + ":" + v }
        private fun fmt(d: Double) = String.format(java.util.Locale.US, "%.3f", d)
        private fun quote(s: String) = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
            append('"')
        }

        /** Reads what [toJson] wrote; null if it's from an older version or damaged. Unknown fields are ignored. */
        fun fromJson(json: String): SongAnalysis? = try {
            val p = MiniJson(json).parse() as Map<*, *>
            fun num(key: String) = p[key] as Number
            if (num("version").toInt() != FORMAT) null else SongAnalysis(
                id = p["id"] as String,
                title = p["title"] as String,
                instrument = Instrument.valueOf(p["instrument"] as String),
                notes = (p["notes"] as List<*>).map {
                    val a = (it as List<*>).map { v -> v as Number }
                    SongNote(a[0].toDouble(), a[1].toDouble(), a[2].toInt(), a[3].toInt(), a[4].toInt())
                },
                dromos = (p["dromos"] as String?)?.let { Dromos.valueOf(it) },
                rootPc = num("root").toInt(),
            )
        } catch (_: Exception) { null }
    }
}

/**
 * Turns a song into its bouzouki line:
 * 1. remove the vocals (MDX-Net),
 * 2. transcribe the rest into notes (Basic Pitch),
 * 3. follow the melodic line in the bouzouki's range (it usually carries the lead),
 * 4. choose frets that keep the hand moving as little as possible.
 */
class SongAnalyzer(private val separator: MdxSeparator, private val pitch: BasicPitch) {
    enum class Stage(val label: String) {
        SEPARATING("Removing the singer's voice"),
        TRANSCRIBING("Finding the bouzouki notes"),
        PLACING("Placing them on the neck"),
    }

    /** [left]/[right] are the song at 44.1 kHz. [progress] gets the stage and 0..1 within it. */
    fun analyze(
        id: String,
        title: String,
        left: FloatArray,
        right: FloatArray,
        instrument: Instrument,
        progress: (Stage, Float) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): SongAnalysis {
        progress(Stage.SEPARATING, 0f)
        val instrumental = separator.instrumentalMono(left, right, { progress(Stage.SEPARATING, it) }, cancelled)
        progress(Stage.TRANSCRIBING, 0f)
        val audio = resample(instrumental, MdxSeparator.SAMPLE_RATE, BasicPitch.SAMPLE_RATE)
        val act = pitch.activations(audio, { progress(Stage.TRANSCRIBING, it * 0.9f) }, cancelled)
        progress(Stage.PLACING, 0f)
        return build(id, title, candidates(act), instrument, act)
    }

    companion object {
        /**
         * Every note the line could be made of: Basic Pitch notes that start at a clear onset
         * (onset 0.6; the line tracker weighs how clear), as short as 4 frames (≈ 46 ms) so fast
         * 16th runs survive, without the melodia trick (its onset-less notes are mostly accompaniment).
         */
        fun candidates(act: BasicPitch.Activations): List<NoteEvent> =
            BasicPitch.notes(act, onsetThresh = 0.6f, minNoteLen = 4, melodia = false)

        /** Everything after transcription: the line, the frets and the dromos guess. [act] sharpens the line when given. */
        fun build(id: String, title: String, all: List<NoteEvent>, instrument: Instrument, act: BasicPitch.Activations? = null): SongAnalysis {
            val line = bouzoukiLine(all, instrument, act)
            val cells = fingering(line.map { it.midi }, line.map { it.start }, instrument)
            val notes = line.mapIndexed { i, n -> SongNote(n.start, n.end, n.midi, cells[i].first, cells[i].second) }
            val tally = DoubleArray(12)
            for (n in line) tally[Math.floorMod(n.midi, 12)] += (n.end - n.start) * n.amplitude
            val guess = DromosGuess.rank(tally).firstOrNull()
            return SongAnalysis(id, title, instrument, notes, guess?.dromos, guess?.rootPc ?: 2)
        }

        /**
         * The single melodic line a bouzouki would play, from overlapping candidate notes in the
         * instrument's range: a dynamic programme (Viterbi over the notes in time order) picks the
         * chain that collects the most evidence (strong note and onset activations) for the least
         * implausible movement (each semitone of a jump costs, octave jumps and bigger leaps cost
         * extra, less so after a pause). With [act], a note that starts while the line's previous
         * note still sounds strongly is taken for accompaniment unless its evidence outweighs that.
         * Then quick same-pitch re-picks (tremolo) are joined into one held note, as tab writes them.
         * Notes without onset evidence (made by hand, e.g. a saved line) are always kept.
         */
        fun bouzoukiLine(notes: List<NoteEvent>, instrument: Instrument, act: BasicPitch.Activations? = null): List<NoteEvent> {
            val low = instrument.courses.first().openMidi
            val high = instrument.courses.last().openMidi + instrument.frets
            val c = notes.filter { it.midi in low..high }.sortedBy { it.start }
            if (c.isEmpty()) return emptyList()
            val best = DoubleArray(c.size)
            val back = IntArray(c.size) { -1 }
            var near = 0 // first note that started less than HORIZON before c[i]
            var far = -1 // the best line ending on a note before that, so long pauses don't cut the line
            for (i in c.indices) {
                while (c[i].start - c[near].start >= HORIZON) {
                    if (far < 0 || best[near] > best[far]) far = near
                    near++
                }
                var b = 0.0 // or start the line afresh here
                for (j in i - 1 downTo near) {
                    if (c[i].start - c[j].start < SEPARATION) continue
                    val v = best[j] - transitionCost(c[j], c[i], act)
                    if (v > b) { b = v; back[i] = j }
                }
                if (far >= 0) {
                    val v = best[far] - transitionCost(c[far], c[i], act)
                    if (v > b) { b = v; back[i] = far }
                }
                best[i] = evidence(c[i]) + b
            }
            val path = ArrayList<NoteEvent>()
            var i = best.indices.maxBy { best[it] }
            while (i >= 0) { path += c[i]; i = back[i] }
            path.reverse()
            val line = path.mapIndexed { k, n -> if (k + 1 < path.size && path[k + 1].start < n.end) n.copy(end = path[k + 1].start) else n }
            return joinTremolo(line)
        }

        /** How much a candidate is worth to the line; the bouzouki usually leads above the accompaniment. */
        private fun evidence(n: NoteEvent) =
            if (n.onset.isNaN()) GIVEN_NOTE
            else n.salience + ONSET_WEIGHT * n.onset - EVIDENCE_NEEDED + HIGHER_BONUS * (n.midi - 60) / 12.0

        /** What it costs the line to move from [a] to [b]. */
        private fun transitionCost(a: NoteEvent, b: NoteEvent, act: BasicPitch.Activations?): Double {
            val d = abs(b.midi - a.midi)
            val gap = b.start - max(a.end, a.start)
            val pause = max(PAUSE_FLOOR, 1 - max(0.0, gap) / PAUSE_TIME) // jumps after a pause are cheaper
            var cost = (STEP_COST * d + (if (d == 12) OCTAVE_COST else 0.0) + (if (d > 12) LEAP_COST else 0.0)) * pause
            if (act != null && d != 0 && b.frame >= 0) {
                val t = min(act.frames.size - 1, b.frame + STILL_SOUNDING_FRAMES)
                cost += STILL_SOUNDING_COST * max(0.0, act.frames[t][a.midi - BasicPitch.MIDI_OFFSET] - STILL_SOUNDING_LEVEL)
            }
            return cost
        }

        /** Three or more picks of one pitch, each within [TREMOLO_GAP] of the last, become one note. */
        private fun joinTremolo(line: List<NoteEvent>): List<NoteEvent> {
            val out = ArrayList<NoteEvent>()
            var i = 0
            while (i < line.size) {
                var j = i
                while (j + 1 < line.size && !line[j + 1].onset.isNaN() && line[j + 1].midi == line[i].midi &&
                    line[j + 1].start - line[j].start < TREMOLO_GAP) j++
                if (j - i + 1 >= TREMOLO_PICKS && !line[i].onset.isNaN()) {
                    val run = line.subList(i, j + 1)
                    out += line[i].copy(end = run.maxOf { it.end }, amplitude = run.maxOf { it.amplitude })
                    i = j + 1
                } else {
                    out += line[i++]
                }
            }
            return out
        }

        // The line tracker's weights, chosen on synthesized bouzouki songs (scratch evaluation, mir_eval F-measure).
        private const val EVIDENCE_NEEDED = 0.5
        private const val ONSET_WEIGHT = 0.5
        private const val HIGHER_BONUS = 0.1 // per octave above middle C
        private const val GIVEN_NOTE = 10.0
        private const val STEP_COST = 0.05
        private const val OCTAVE_COST = 0.3
        private const val LEAP_COST = 0.6
        private const val PAUSE_TIME = 1.0
        private const val PAUSE_FLOOR = 0.3
        private const val STILL_SOUNDING_FRAMES = 2
        private const val STILL_SOUNDING_LEVEL = 0.3
        private const val STILL_SOUNDING_COST = 1.0
        private const val SEPARATION = 0.05 // two line notes start at least this far apart
        private const val HORIZON = 3.0     // how far back a note looks for its predecessor
        private const val TREMOLO_GAP = 0.2
        private const val TREMOLO_PICKS = 3

        /**
         * Frets for a line of notes, by dynamic programming over every playable position:
         * small moves along the neck are cheap, big jumps cost more (less after a pause),
         * and very high frets cost a little extra.
         */
        fun fingering(midis: List<Int>, starts: List<Double>, inst: Instrument): List<Pair<Int, Int>> {
            if (midis.isEmpty()) return emptyList()
            val options = midis.map { m ->
                inst.courses.indices.mapNotNull { c -> (m - inst.courses[c].openMidi).takeIf { it in 0..inst.frets }?.let { c to it } }
                    .ifEmpty { listOf(inst.position(m.coerceIn(inst.courses.first().openMidi, inst.courses.last().openMidi + inst.frets)) ?: (0 to 0)) }
            }
            fun placeCost(p: Pair<Int, Int>) = if (p.second > 12) (p.second - 12) * 0.15 else 0.0
            val cost = ArrayList<DoubleArray>()
            val back = ArrayList<IntArray>()
            cost += DoubleArray(options[0].size) { placeCost(options[0][it]) }
            back += IntArray(options[0].size)
            for (i in 1 until midis.size) {
                val gap = starts[i] - starts[i - 1]
                val moveWeight = if (gap > 1.0) 0.3 else 1.0
                val prev = options[i - 1]
                val here = options[i]
                val c = DoubleArray(here.size)
                val b = IntArray(here.size)
                for ((j, p) in here.withIndex()) {
                    var best = Double.MAX_VALUE
                    for ((q, pp) in prev.withIndex()) {
                        // Open strings don't move the hand.
                        val fretMove = if (p.second == 0 || pp.second == 0) 0 else abs(p.second - pp.second)
                        val v = cost[i - 1][q] + moveWeight * fretMove + 0.4 * abs(p.first - pp.first)
                        if (v < best) { best = v; b[j] = q }
                    }
                    c[j] = best + placeCost(p)
                }
                cost += c
                back += b
            }
            val path = IntArray(midis.size)
            path[midis.size - 1] = cost.last().indices.minBy { cost.last()[it] }
            for (i in midis.size - 1 downTo 1) path[i - 1] = back[i][path[i]]
            return path.mapIndexed { i, j -> options[i][j] }
        }
    }
}

/** Just enough JSON parsing for the song cache (objects, arrays, strings, numbers, null). */
internal class MiniJson(private val s: String) {
    private var i = 0

    fun parse(): Any? { val v = value(); ws(); return v }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            'n' -> { i += 4; null }
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            else -> if (c == '-' || c.isDigit()) num() else error("bad json at $i")
        }
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++; ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws(); val k = str(); ws(); i++ // ':'
            m[k] = value(); ws()
            if (s[i++] == '}') return m
        }
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        i++; ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l += value(); ws()
            if (s[i++] == ']') return l
        }
    }

    private fun str(): String {
        val b = StringBuilder()
        i++
        while (s[i] != '"') {
            val c = s[i++]
            if (c == '\\') {
                when (val e = s[i++]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
            } else b.append(c)
        }
        i++
        return b.toString()
    }

    private fun num(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(start, i).toDouble()
    }
}
