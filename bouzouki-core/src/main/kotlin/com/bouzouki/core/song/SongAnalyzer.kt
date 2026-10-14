package com.bouzouki.core.song

import com.bouzouki.core.Dromos
import com.bouzouki.core.DromosGuess
import com.bouzouki.core.Instrument
import kotlin.math.abs

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

    fun toJson(): String = buildString {
        append("{\"version\":").append(FORMAT).append(",\"id\":").append(quote(id))
        append(",\"title\":").append(quote(title))
        append(",\"instrument\":").append(quote(instrument.name))
        append(",\"dromos\":").append(dromos?.let { quote(it.name) } ?: "null")
        append(",\"root\":").append(rootPc)
        append(",\"notes\":[")
        notes.forEachIndexed { i, n ->
            if (i > 0) append(',')
            append('[').append(fmt(n.start)).append(',').append(fmt(n.end)).append(',')
                .append(n.midi).append(',').append(n.course).append(',').append(n.fret).append(']')
        }
        append("]}")
    }

    companion object {
        /** Bump when the analysis changes, so old cached songs get analysed again. */
        const val FORMAT = 1

        private fun fmt(d: Double) = String.format(java.util.Locale.US, "%.3f", d)
        private fun quote(s: String) = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
            append('"')
        }

        /** Reads what [toJson] wrote; null if it's from an older version or damaged. */
        fun fromJson(json: String): SongAnalysis? = try {
            val p = MiniJson(json).parse() as Map<*, *>
            if ((p["version"] as Number).toInt() != FORMAT) null else SongAnalysis(
                id = p["id"] as String,
                title = p["title"] as String,
                instrument = Instrument.valueOf(p["instrument"] as String),
                notes = (p["notes"] as List<*>).map {
                    val a = it as List<*>
                    SongNote((a[0] as Number).toDouble(), (a[1] as Number).toDouble(), (a[2] as Number).toInt(),
                        (a[3] as Number).toInt(), (a[4] as Number).toInt())
                },
                dromos = (p["dromos"] as String?)?.let { Dromos.valueOf(it) },
                rootPc = (p["root"] as Number).toInt(),
            )
        } catch (_: Exception) { null }
    }
}

/**
 * Turns a song into its bouzouki line:
 * 1. remove the vocals (MDX-Net),
 * 2. transcribe the rest into notes (Basic Pitch),
 * 3. keep the top melodic line in the bouzouki's range (it usually carries the lead),
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
        val all = BasicPitch.notes(act)
        progress(Stage.PLACING, 0f)
        return build(id, title, all, instrument)
    }

    companion object {
        /** Everything after transcription: the line, the frets and the dromos guess. */
        fun build(id: String, title: String, all: List<NoteEvent>, instrument: Instrument): SongAnalysis {
            val line = bouzoukiLine(all, instrument)
            val cells = fingering(line.map { it.midi }, line.map { it.start }, instrument)
            val notes = line.mapIndexed { i, n -> SongNote(n.start, n.end, n.midi, cells[i].first, cells[i].second) }
            val tally = DoubleArray(12)
            for (n in line) tally[Math.floorMod(n.midi, 12)] += (n.end - n.start) * n.amplitude
            val guess = DromosGuess.rank(tally).firstOrNull()
            return SongAnalysis(id, title, instrument, notes, guess?.dromos, guess?.rootPc ?: 2)
        }

        /**
         * The single melodic line a bouzouki would play: notes in the instrument's range,
         * keeping the higher (or much louder) note where notes overlap — the "skyline".
         * Chords from guitars sit lower, and the bouzouki usually leads on top.
         */
        fun bouzoukiLine(notes: List<NoteEvent>, instrument: Instrument, minAmplitude: Double = 0.25): List<NoteEvent> {
            val low = instrument.courses.first().openMidi
            val high = instrument.courses.last().openMidi + instrument.frets
            val candidates = notes
                .filter { it.midi in low..high && it.amplitude >= minAmplitude }
                .sortedWith(compareBy<NoteEvent> { it.start }.thenByDescending { it.midi })
            val out = ArrayList<NoteEvent>()
            for (n in candidates) {
                val last = out.lastOrNull()
                if (last == null || n.start >= last.end - 0.02) { out += n; continue }
                // Overlap: the new note takes over if it's higher or clearly stronger.
                if (n.midi > last.midi || n.amplitude > last.amplitude * 1.6) {
                    if (n.start - last.start < MIN_NOTE) out.removeAt(out.size - 1)
                    else out[out.size - 1] = last.copy(end = n.start)
                    out += n
                } else if (n.end > last.end + MIN_NOTE && n.midi == last.midi) {
                    out[out.size - 1] = last.copy(end = n.end) // same pitch rings on
                }
            }
            return out.filter { it.end - it.start >= MIN_NOTE }
        }

        private const val MIN_NOTE = 0.07

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
