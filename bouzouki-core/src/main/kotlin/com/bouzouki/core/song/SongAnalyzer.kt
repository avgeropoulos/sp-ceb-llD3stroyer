package com.bouzouki.core.song

import com.bouzouki.core.Dromos
import com.bouzouki.core.DromosGuess
import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How a note is sounded: picked, or legato from the note before (on the same course), or held with tremolo. */
enum class Articulation {
    PICK, HAMMER, PULL, SLIDE, TREMOLO;

    /** Sounded from the previous note without a new pick, so it must stay on that note's course. */
    val isLegato get() = this == HAMMER || this == PULL || this == SLIDE
}

/** One note of the bouzouki line, placed on the neck. */
data class SongNote(
    val start: Double,
    val end: Double,
    val midi: Int,
    val course: Int,
    val fret: Int,
    val articulation: Articulation = Articulation.PICK,
)

/** A prepared song: the bouzouki line with frets, the dromos it seems to be in, and its beats (seconds). */
data class SongAnalysis(
    val id: String,
    val title: String,
    val instrument: Instrument,
    val notes: List<SongNote>,
    val dromos: Dromos?,
    val rootPc: Int,
    val beats: List<Double> = emptyList(),
) {
    /**
     * The same song fingered for [inst] (the other bouzouki, or with a newer fingering): notes outside
     * its range move by octaves, and every note keeps its time and articulation.
     */
    fun forInstrument(inst: Instrument): SongAnalysis {
        val low = inst.courses.first().openMidi
        val high = inst.courses.last().openMidi + inst.frets
        val midis = notes.map { n -> var m = n.midi; while (m < low) m += 12; while (m > high) m -= 12; m }
        val cells = SongAnalyzer.fingering(midis, notes.map { it.start }, inst, notes.map { it.articulation.isLegato })
        return copy(instrument = inst, notes = notes.mapIndexed { i, n -> n.copy(midi = midis[i], course = cells[i].first, fret = cells[i].second) })
    }

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
        "notes" to notes.joinToString(",", "[", "]") { n -> "[${fmt(n.start)},${fmt(n.end)},${n.midi},${n.course},${n.fret},${n.articulation.ordinal}]" },
        "beats" to beats.joinToString(",", "[", "]") { fmt(it) },
    )

    /** A saved song as the song list shows it: [current] is false when an older version wrote it (prepare it again). */
    data class Saved(val id: String, val title: String, val current: Boolean)

    companion object {
        /**
         * Bump when the analysis changes: [fromJson] then refuses songs saved before [OLDEST_READ], so they
         * are prepared again, and [saved] still lists them meanwhile (as not [Saved.current]).
         */
        const val FORMAT = 3

        /**
         * The oldest cache [fromJson] still reads. Version 2 (before articulations were detected) has the same
         * fields: its notes all read as picked.
         */
        const val OLDEST_READ = 2

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

        /** The id and title in a cache file of any version, and whether this version wrote it; null if damaged. */
        fun saved(json: String): Saved? = try {
            val p = MiniJson(json).parse() as Map<*, *>
            Saved(p["id"] as String, p["title"] as String, (p["version"] as Number).toInt() == FORMAT)
        } catch (_: Exception) { null }

        /**
         * Reads what [toJson] wrote (versions [OLDEST_READ]..[FORMAT]); null if it's from an older or newer
         * version, or damaged. Unknown fields are ignored.
         */
        fun fromJson(json: String): SongAnalysis? = try {
            val p = MiniJson(json).parse() as Map<*, *>
            fun num(key: String) = p[key] as Number
            val version = num("version").toInt()
            if (version !in OLDEST_READ..FORMAT) null else SongAnalysis(
                id = p["id"] as String,
                title = p["title"] as String,
                instrument = Instrument.valueOf(p["instrument"] as String),
                notes = (p["notes"] as List<*>).map {
                    val a = (it as List<*>).map { v -> v as Number }
                    val art = if (version < 3) null else a.getOrNull(5)?.toInt()?.let { Articulation.entries.getOrNull(it) }
                    SongNote(a[0].toDouble(), a[1].toDouble(), a[2].toInt(), a[3].toInt(), a[4].toInt(), art ?: Articulation.PICK)
                },
                dromos = (p["dromos"] as String?)?.let { Dromos.valueOf(it) },
                rootPc = num("root").toInt(),
                beats = (p["beats"] as List<*>?)?.map { (it as Number).toDouble() } ?: emptyList(),
            )
        } catch (_: Exception) { null }
    }
}

/**
 * Turns a song into its bouzouki line:
 * 1. remove the vocals (MDX-Net),
 * 2. transcribe the rest into notes (Basic Pitch),
 * 3. follow the melodic line in the bouzouki's range (it usually carries the lead), and tell the notes
 *    the plectrum picks from the hammer-ons, pull-offs and slides (where it strikes: [Attacks]),
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
        val attacks = Attacks.of(audio)
        // The beats lay the tablature out in bars; a song without a clear pulse just gets none.
        val beats = try { Beats.estimate(Beats.envelope(act), Beats.FPS) } catch (_: Exception) { emptyList() }
        return build(id, title, candidates(act), instrument, act, attacks).copy(beats = beats)
    }

    companion object {
        /**
         * Every note the line could be made of: Basic Pitch notes that start at a clear onset
         * (onset 0.6; the line tracker weighs how clear), as short as 4 frames (≈ 46 ms) so fast
         * 16th runs survive, without the melodia trick (its onset-less notes are mostly accompaniment).
         */
        fun candidates(act: BasicPitch.Activations): List<NoteEvent> =
            BasicPitch.notes(act, onsetThresh = 0.6f, minNoteLen = 4, melodia = false)

        /**
         * Everything after transcription: the line with its articulations, the frets (legato notes on the course of
         * the note before) and the dromos guess. [act] sharpens the line when given, [attacks] (with [act]) finds the
         * notes sounded without a pick.
         */
        fun build(
            id: String,
            title: String,
            all: List<NoteEvent>,
            instrument: Instrument,
            act: BasicPitch.Activations? = null,
            attacks: Attacks? = null,
        ): SongAnalysis {
            val line = bouzoukiLine(all, instrument, act, attacks)
            val cells = fingering(line.map { it.midi }, line.map { it.start }, instrument, line.map { it.articulation.isLegato })
            val notes = line.mapIndexed { i, n -> SongNote(n.start, n.end, n.midi, cells[i].first, cells[i].second, n.articulation) }
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
         * extra, less so after a pause; picking the same pitch again costs a little, since that is
         * mostly a held note detected twice). With [act], a note that starts while the line's previous
         * note still sounds strongly is taken for accompaniment unless its evidence outweighs that.
         * Then tremolo (fast same-pitch re-picks) is joined into one held note, as tab writes it ([Articulation.TREMOLO]).
         * With [attacks] too (where the plectrum strikes): a candidate that continues one of the same pitch without
         * a pick of its own is that note detected twice (a bend, vibrato), so they are joined; a note without a pick
         * that starts while the line's note still sounds costs twice as much (likely accompaniment, the way a long
         * held note gets split); and, with the contours of [act], the notes sounded without a pick are marked
         * hammer-on, pull-off or slide ([articulate]).
         * Notes without onset evidence (made by hand, e.g. a saved line) are always kept.
         */
        fun bouzoukiLine(
            notes: List<NoteEvent>,
            instrument: Instrument,
            act: BasicPitch.Activations? = null,
            attacks: Attacks? = null,
        ): List<NoteEvent> {
            val low = instrument.courses.first().openMidi
            val high = instrument.courses.last().openMidi + instrument.frets
            var c = notes.filter { it.midi in low..high }.sortedBy { it.start }
            if (c.isEmpty()) return emptyList()
            if (c.all { it.onset.isNaN() }) return trimEnds(c) // a line chosen before: keep it as it is
            if (attacks != null) c = joinRedetections(c, act, attacks)
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
                // Whether c[i] brings a pick of its own (attacks unknown: as if it did).
                val picked = attacks == null || c[i].onset.isNaN() || attacks.rise(c[i].start) >= PICK_ATTACK
                for (j in i - 1 downTo near) {
                    if (c[i].start - c[j].start < SEPARATION && !(c[i].onset.isNaN() && c[j].onset.isNaN())) continue
                    val v = best[j] - transitionCost(c[j], c[i], act, picked)
                    if (v > b) { b = v; back[i] = j }
                }
                if (far >= 0) {
                    val v = best[far] - transitionCost(c[far], c[i], act, picked)
                    if (v > b) { b = v; back[i] = far }
                }
                best[i] = evidence(c[i]) + b
            }
            val path = ArrayList<NoteEvent>()
            var i = best.indices.maxBy { best[it] }
            while (i >= 0) { path += c[i]; i = back[i] }
            path.reverse()
            val line = joinTremolo(trimEnds(path))
            return if (act?.contours != null && attacks != null) articulate(line, act, attacks) else line
        }

        /**
         * Candidates without a pick of their own that start where one of the same pitch ends ([REDETECTION_GAP]),
         * or within [BRIDGE_TIME] of its end while the contour shows the string still sounding near the pitch, are
         * that note detected again (after a bend or vibrato, or the pair of strings beating): each is joined into it.
         */
        private fun joinRedetections(c: List<NoteEvent>, act: BasicPitch.Activations?, attacks: Attacks): List<NoteEvent> {
            val out = ArrayList<NoteEvent>(c.size)
            val last = HashMap<Int, Int>() // pitch -> index in out of its latest note
            for (n in c) {
                val k = last[n.midi]
                if (k != null && !n.onset.isNaN() && attacks.rise(n.start) < PICK_ATTACK) {
                    val p = out[k]
                    if (p.end >= n.start - REDETECTION_GAP || (p.end >= n.start - BRIDGE_TIME && sounds(act, n.midi, p.endFrame, n.frame))) {
                        out[k] = p.copy(end = max(p.end, n.end), endFrame = max(p.endFrame, n.endFrame))
                        continue
                    }
                }
                last[n.midi] = out.size
                out += n
            }
            return out
        }

        /** Whether the contour stays within 4 bins of [midi], at [BRIDGE_LEVEL] or more, in every frame [from] until [to]. */
        private fun sounds(act: BasicPitch.Activations?, midi: Int, from: Int, to: Int): Boolean {
            if (act == null || from < 0 || to < 0) return false
            if (to > from && act.contours == null) return false
            val c = 3 * (midi - BasicPitch.MIDI_OFFSET) + 1
            for (t in max(0, from) until min(act.frames.size, to)) {
                var m = 0.0
                for (k in max(0, c - 4)..min(BasicPitch.N_CONTOUR_BINS - 1, c + 4)) m = max(m, act.contour(t, k))
                if (m < BRIDGE_LEVEL) return false
            }
            return true
        }

        /**
         * Marks the line's notes sounded without a new pick, from the note before on the same string. B is legato
         * from A when it is within [LEGATO_REACH] semitones; A sounds up to B (its note reaches B, its activation
         * just before B is at least [HANDOVER_BEFORE]) and fades as B takes over (3-8 frames into B, to at most
         * [HANDOVER_FADE] of that while B reaches [LEGATO_STRONG], or to at most [HANDOVER_CLEAR] of it while B
         * reaches [LEGATO_WEAK]); and B's attack adds less than [LEGATO_ENERGY] of the energy the line's median note
         * adds. B is a [Articulation.SLIDE] when the pitch contour glides from A to B (or B adds almost no energy and
         * its onset is soft), else a [Articulation.HAMMER] (up) or [Articulation.PULL] (down), which need a clear onset.
         */
        fun articulate(line: List<NoteEvent>, act: BasicPitch.Activations, attacks: Attacks): List<NoteEvent> {
            if (line.size < 2 || act.contours == null) return line
            val frames = act.frames
            val n = frames.size
            val energy = line.map { attacks.energy(it.start) }
            val typical = median(energy)
            if (typical <= 0) return line
            val out = line.toMutableList()
            for (j in 1 until out.size) {
                val a = out[j - 1]
                val b = out[j]
                val f = b.frame
                if (b.articulation != Articulation.PICK || f < 1 || f >= n || b.onset.isNaN()) continue
                val d = b.midi - a.midi
                if (abs(d) !in 1..LEGATO_REACH || a.end < b.start - LEGATO_GAP) continue
                val ka = a.midi - BasicPitch.MIDI_OFFSET
                val kb = b.midi - BasicPitch.MIDI_OFFSET
                var before = 0.0
                for (t in max(0, f - 3) until f) before = max(before, frames[t][ka].toDouble())
                var after = Double.MAX_VALUE
                for (t in min(n - 1, f + 3) until min(n, f + 9)) after = min(after, frames[t][ka].toDouble())
                var strong = 0.0
                for (t in f until min(n, f + 5)) strong = max(strong, frames[t][kb].toDouble())
                if (before < HANDOVER_BEFORE || after > HANDOVER_FADE * before) continue
                if (strong < LEGATO_STRONG && !(strong >= LEGATO_WEAK && after <= HANDOVER_CLEAR * before)) continue
                val added = energy[j] / typical
                if (added >= LEGATO_ENERGY) continue
                val slide = glides(act, a.midi, b.midi, f) || (added < SLIDE_ENERGY && b.onset < LEGATO_ONSET)
                if (!slide && b.onset < LEGATO_ONSET) continue
                out[j] = b.copy(articulation = if (slide) Articulation.SLIDE else if (d > 0) Articulation.HAMMER else Articulation.PULL)
            }
            return out
        }

        /**
         * Whether the pitch contour glided from [a] to [b] just before frame [f]: in the [GLIDE_FRAMES] before it
         * (and the one after), its peak between the two pitches (a semitone of room) sat clearly between them.
         */
        private fun glides(act: BasicPitch.Activations, a: Int, b: Int, f: Int): Boolean {
            val c = act.contours ?: return false
            val lo = max(0, 3 * (min(a, b) - BasicPitch.MIDI_OFFSET) - 2)
            val hi = min(BasicPitch.N_CONTOUR_BINS - 1, 3 * (max(a, b) - BasicPitch.MIDI_OFFSET) + 4)
            val d = (b - a).toDouble()
            val margin = GLIDE_MARGIN / abs(d)
            for (t in max(0, f - GLIDE_FRAMES) until min(c.size, f + 2)) {
                var j = lo
                for (k in lo + 1..hi) if (act.contour(t, k) > act.contour(t, j)) j = k
                val v = act.contour(t, j)
                if (v < GLIDE_LEVEL) continue
                var off = 0.0
                if (j > lo && j < hi) {
                    val x = act.contour(t, j - 1)
                    val z = act.contour(t, j + 1)
                    val den = x - 2 * v + z
                    if (den < -1e-9) off = 0.5 * (x - z) / den
                }
                val pitch = (j + off - 1) / 3.0 + BasicPitch.MIDI_OFFSET // MIDI m sits at bin 3·(m − 21) + 1
                val u = (pitch - a) / d
                if (u > margin && u < 1 - margin) return true
            }
            return false
        }

        private fun median(v: List<Double>): Double {
            if (v.isEmpty()) return 0.0
            val s = v.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }

        /** Each note of a line ends where the next one starts, at the latest. */
        private fun trimEnds(line: List<NoteEvent>) =
            line.mapIndexed { k, n -> if (k + 1 < line.size && line[k + 1].start < n.end) n.copy(end = line[k + 1].start) else n }

        /** How much a candidate is worth to the line. */
        private fun evidence(n: NoteEvent) =
            if (n.onset.isNaN()) GIVEN_NOTE else n.salience + ONSET_WEIGHT * n.onset - EVIDENCE_NEEDED

        /** What it costs the line to move from [a] to [b] ([picked]: b brings a pick of its own). */
        private fun transitionCost(a: NoteEvent, b: NoteEvent, act: BasicPitch.Activations?, picked: Boolean = true): Double {
            val d = abs(b.midi - a.midi)
            val gap = b.start - max(a.end, a.start)
            val pause = max(PAUSE_FLOOR, 1 - max(0.0, gap) / PAUSE_TIME) // jumps after a pause are cheaper
            var cost = (STEP_COST * d + (if (d == 12) OCTAVE_COST else 0.0) + (if (d > 12) LEAP_COST else 0.0)) * pause
            if (d == 0) cost += REPEAT_COST
            if (act != null && d != 0 && b.frame >= 0) {
                val t = min(act.frames.size - 1, b.frame + STILL_SOUNDING_FRAMES)
                val weight = if (picked) STILL_SOUNDING_COST else STILL_SOUNDING_COST * (1 + UNPICKED_EXTRA)
                cost += weight * max(0.0, act.frames[t][a.midi - BasicPitch.MIDI_OFFSET] - STILL_SOUNDING_LEVEL)
            }
            return cost
        }

        /**
         * Tremolo becomes one held note: [TREMOLO_PICKS] or more picks of one pitch, each within
         * [TREMOLO_REACH] of the last (a missed pick or two doesn't break it), at a median interval of
         * [TREMOLO_GAP] or less (10 picks a second and up). Slower repeats stay separate notes:
         * 8ths, and 16ths below 150 BPM (and 4 repeated 16ths at any tempo).
         */
        private fun joinTremolo(line: List<NoteEvent>): List<NoteEvent> {
            val out = ArrayList<NoteEvent>()
            var i = 0
            while (i < line.size) {
                var j = i
                while (j + 1 < line.size && !line[j + 1].onset.isNaN() && line[j + 1].midi == line[i].midi &&
                    line[j + 1].start - line[j].start < TREMOLO_REACH) j++
                val run = line.subList(i, j + 1)
                if (run.size >= TREMOLO_PICKS && !line[i].onset.isNaN() && medianGap(run) <= TREMOLO_GAP) {
                    out += line[i].copy(end = run.maxOf { it.end }, amplitude = run.maxOf { it.amplitude }, articulation = Articulation.TREMOLO)
                    i = j + 1
                } else {
                    out += line[i++]
                }
            }
            return out
        }

        private fun medianGap(run: List<NoteEvent>): Double {
            val gaps = run.zipWithNext { a, b -> b.start - a.start }.sorted()
            val m = gaps.size / 2
            return if (gaps.size % 2 == 1) gaps[m] else (gaps[m - 1] + gaps[m]) / 2
        }

        // The line tracker's weights, chosen on synthesized bouzouki songs (scratch evaluation, mir_eval F-measure).
        private const val EVIDENCE_NEEDED = 0.5
        private const val ONSET_WEIGHT = 0.5
        private const val GIVEN_NOTE = 10.0
        private const val STEP_COST = 0.05
        private const val REPEAT_COST = 0.15
        private const val OCTAVE_COST = 0.3
        private const val LEAP_COST = 0.6
        private const val PAUSE_TIME = 1.0
        private const val PAUSE_FLOOR = 0.3
        private const val STILL_SOUNDING_FRAMES = 2
        private const val STILL_SOUNDING_LEVEL = 0.3
        private const val STILL_SOUNDING_COST = 1.0
        private const val SEPARATION = 0.05 // two line notes start at least this far apart
        private const val HORIZON = 3.0     // how far back a note looks for its predecessor
        private const val TREMOLO_PICKS = 5
        private const val TREMOLO_GAP = 0.1
        private const val TREMOLO_REACH = 0.4

        // Picks and legato, chosen on synthesized songs with hammer-ons, pull-offs, slides and ties (Attacks has the measures).
        /** A note whose sharpest attack is under this (times the song's median frame) brought no pick of its own. */
        private const val PICK_ATTACK = 24.0
        /** How much more a note without a pick costs the line while the line's note still sounds. */
        private const val UNPICKED_EXTRA = 1.0
        /** A same-pitch candidate starting within this of the last one's end, without a pick, is the same note. */
        private const val REDETECTION_GAP = 0.03
        /** ... or within this of its end while the contour stays near the pitch at [BRIDGE_LEVEL] or more. */
        private const val BRIDGE_TIME = 0.6
        private const val BRIDGE_LEVEL = 0.3
        private const val LEGATO_REACH = 4
        private const val LEGATO_GAP = 0.02
        private const val HANDOVER_BEFORE = 0.2
        private const val HANDOVER_FADE = 0.8
        private const val HANDOVER_CLEAR = 0.5
        private const val LEGATO_STRONG = 0.6
        private const val LEGATO_WEAK = 0.45
        private const val LEGATO_ENERGY = 0.35
        private const val LEGATO_ONSET = 0.85
        private const val SLIDE_ENERGY = 0.05
        private const val GLIDE_FRAMES = 8
        private const val GLIDE_LEVEL = 0.2
        private const val GLIDE_MARGIN = 0.25

        /**
         * Frets for a line of notes, by dynamic programming over every playable position:
         * small moves along the neck are cheap, big jumps cost more (less after a pause),
         * and very high frets cost a little extra.
         */
        fun fingering(midis: List<Int>, starts: List<Double>, inst: Instrument, legato: List<Boolean>? = null): List<Pair<Int, Int>> {
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
