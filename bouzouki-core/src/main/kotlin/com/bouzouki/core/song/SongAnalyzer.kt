package com.bouzouki.core.song

import com.bouzouki.core.Dromos
import com.bouzouki.core.DromosGuess
import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.ln
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
     * The same song fingered for [inst] (the other bouzouki, or with a newer fingering): every note keeps
     * its time and articulation, and notes outside its range move by octaves. They move a passage at a
     * time (the notes of a phrase that move by step, a legato note always with the one before), so the
     * tune keeps its shape and a hammer-on, pull-off or slide its direction. Only a passage wider than
     * the instrument moves in pieces (each picked note with its legato notes, failing that note by note),
     * and a legato note moved apart from the note before is then picked.
     */
    fun forInstrument(inst: Instrument): SongAnalysis {
        val shift = octaveShifts(inst.courses.first().openMidi, inst.courses.last().openMidi + inst.frets)
        val moved = notes.mapIndexed { i, n ->
            val apart = i > 0 && shift[i] != shift[i - 1]
            n.copy(midi = n.midi + shift[i], articulation = if (apart && n.articulation.isLegato) Articulation.PICK else n.articulation)
        }
        val cells = SongAnalyzer.fingering(moved.map { it.midi }, moved.map { it.start }, inst,
            moved.map { it.articulation.isLegato }, moved.map { it.articulation == Articulation.SLIDE })
        return copy(instrument = inst, notes = moved.mapIndexed { i, n -> n.copy(course = cells[i].first, fret = cells[i].second) })
    }

    /** How far (whole octaves, in semitones) each note moves to lie between [low] and [high], as [forInstrument] says. */
    private fun octaveShifts(low: Int, high: Int): IntArray {
        val shift = IntArray(notes.size)
        // Moves notes [from, to) by the same octaves, if that brings them all in range.
        fun together(from: Int, to: Int): Boolean {
            val lo = (from until to).minOf { notes[it].midi }
            val hi = (from until to).maxOf { notes[it].midi }
            val k = if (lo < low) (low - lo + 11) / 12 * 12 else if (hi > high) -((hi - high + 11) / 12 * 12) else 0
            if (lo + k < low || hi + k > high) return false
            for (i in from until to) shift[i] = k
            return true
        }
        // Cuts [from, to) before each note [cut] picks and hands each piece to [each].
        fun pieces(from: Int, to: Int, cut: (Int) -> Boolean, each: (Int, Int) -> Unit) {
            var a = from
            while (a < to) { var b = a + 1; while (b < to && !cut(b)) b++; each(a, b); a = b }
        }
        val phrase = Fingering.phraseStarts(notes.map { it.start })
        val picked = { i: Int -> !notes[i].articulation.isLegato }
        pieces(0, notes.size, { i -> picked(i) && (phrase[i] || abs(notes[i].midi - notes[i - 1].midi) > PASSAGE_STEP) }) { a, b ->
            if (!together(a, b)) pieces(a, b, picked) { c, d -> if (!together(c, d)) for (i in c until d) together(i, i + 1) }
        }
        return shift
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
         * Bump when the analysis changes: [fromJson] then refuses songs saved before, so they are
         * prepared again, and [saved] still lists them meanwhile. 4: the line finds more of the notes (attacks and
         * harmonics weigh in), and a song prepared since has its [AnalysisData] to change the detail with.
         */
        const val FORMAT = 4

        /** The widest step (semitones) inside a passage that [forInstrument] moves as one: a fourth. */
        private const val PASSAGE_STEP = 5

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

        /** The id and title in a cache file of any version, and whether [fromJson] can read it; null if damaged. */
        fun saved(json: String): Saved? = try {
            val p = MiniJson(json).parse() as Map<*, *>
            Saved(p["id"] as String, p["title"] as String, (p["version"] as Number).toInt() == FORMAT)
        } catch (_: Exception) { null }

        /**
         * Reads what [toJson] wrote; null if it's from an older version (the app then prepares the song again,
         * e.g. version 3, from before the line weighed the plectrum's attacks) or damaged. Unknown fields are ignored.
         */
        fun fromJson(json: String): SongAnalysis? = try {
            val p = MiniJson(json).parse() as Map<*, *>
            fun num(key: String) = p[key] as Number
            if (num("version").toInt() != FORMAT) null else SongAnalysis(
                id = p["id"] as String,
                title = p["title"] as String,
                instrument = Instrument.valueOf(p["instrument"] as String),
                notes = (p["notes"] as List<*>).map {
                    val a = (it as List<*>).map { v -> v as Number }
                    SongNote(a[0].toDouble(), a[1].toDouble(), a[2].toInt(), a[3].toInt(), a[4].toInt(),
                        a.getOrNull(5)?.toInt()?.let { Articulation.entries.getOrNull(it) } ?: Articulation.PICK)
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
    ): SongAnalysis = analyzeWithData(id, title, left, right, instrument, progress, cancelled).first

    /** Like [analyze], also returning what the line was derived from, to save with the song (see [rebuild]). */
    fun analyzeWithData(
        id: String,
        title: String,
        left: FloatArray,
        right: FloatArray,
        instrument: Instrument,
        progress: (Stage, Float) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): Pair<SongAnalysis, AnalysisData> {
        progress(Stage.SEPARATING, 0f)
        val instrumental = separator.instrumentalMono(left, right, { progress(Stage.SEPARATING, it) }, cancelled)
        progress(Stage.TRANSCRIBING, 0f)
        val audio = resample(instrumental, MdxSeparator.SAMPLE_RATE, BasicPitch.SAMPLE_RATE)
        val raw = pitch.activations(audio, { progress(Stage.TRANSCRIBING, it * 0.9f) }, cancelled)
        progress(Stage.PLACING, 0f)
        // The beats lay the tablature out in bars; a song without a clear pulse just gets none.
        val beats = try { Beats.estimate(Beats.envelope(raw), Beats.FPS) } catch (_: Exception) { emptyList() }
        // The line comes from the activations as they are saved, so rebuild() gives this very line again.
        val data = AnalysisData.of(raw, Attacks.of(audio))
        return build(id, title, candidates(data.act), instrument, data.act, data.attacks).copy(beats = beats) to data
    }

    companion object {
        /**
         * Every note the line could be made of: Basic Pitch notes that start at an onset peak of 0.5 or more (the
         * line tracker weighs how clear), as short as 3 frames (≈ 35 ms) so fast runs and trills survive, without the
         * melodia trick (its onset-less notes are mostly accompaniment). [Detail.MORE] also lets a note go on while its
         * activation stays above 0.25 (0.3 otherwise), which keeps quieter notes the accompaniment covers. Weaker
         * onsets add little: under 0.5 only about one candidate in fifty is a melody note.
         */
        fun candidates(act: BasicPitch.Activations, detail: Detail = Detail.NORMAL): List<NoteEvent> =
            BasicPitch.notes(act, onsetThresh = 0.5f, frameThresh = if (detail == Detail.MORE) 0.25f else 0.3f, minNoteLen = 2, melodia = false)

        /**
         * The line of a prepared song derived again from its saved [data] with [detail], for the bouzouki it was
         * prepared for (show it through [SongAnalysis.forInstrument] as usual); title, beats and dromos stay.
         * With [Detail.NORMAL] it is the line [analyzeWithData] made.
         */
        fun rebuild(base: SongAnalysis, data: AnalysisData, detail: Detail = Detail.NORMAL): SongAnalysis =
            build(base.id, base.title, candidates(data.act, detail), base.instrument, data.act, data.attacks, detail)
                .copy(beats = base.beats, dromos = base.dromos, rootPc = base.rootPc)

        /**
         * Everything after transcription: the line with its articulations, the frets ([fingering] is told which notes
         * are legato) and the dromos guess. [act] sharpens the line when given, [attacks] (with [act]) finds the notes
         * sounded without a pick; [detail] is how many notes the line keeps ([bouzoukiLine]).
         */
        fun build(
            id: String,
            title: String,
            all: List<NoteEvent>,
            instrument: Instrument,
            act: BasicPitch.Activations? = null,
            attacks: Attacks? = null,
            detail: Detail = Detail.NORMAL,
        ): SongAnalysis {
            val line = bouzoukiLine(all, instrument, act, attacks, detail)
            val cells = fingering(line.map { it.midi }, line.map { it.start }, instrument,
                line.map { it.articulation.isLegato }, line.map { it.articulation == Articulation.SLIDE })
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
         * implausible movement (each semitone of a jump costs, leaps past an octave cost extra, less
         * so after a pause; picking the same pitch again costs a little, since that is mostly a held
         * note detected twice). With [act], a note that starts while the line's previous note still
         * sounds strongly is taken for accompaniment unless its evidence outweighs that, and one whose
         * octave below sounds strongly at the same time counts less (likely a harmonic of that lower note).
         * Then tremolo (fast same-pitch re-picks) is joined into one held note, as tab writes it ([Articulation.TREMOLO]).
         * With [attacks] too (where the plectrum strikes): a note with a sharp attack counts more (the bouzouki is
         * the plucked, bright instrument of the band; a soft attack costs nothing, since reverb and lossy audio blur
         * picks); a candidate that continues one of the same pitch without a pick of its own is that note detected
         * twice (a bend, vibrato), so they are joined; a note without a pick that starts while the line's note still
         * sounds costs twice as much (likely accompaniment, the way a long held note gets split); and, with the
         * contours of [act], the notes sounded without a pick are marked hammer-on, pull-off or slide ([articulate]).
         * [Detail.FEWER] is this line without its weak notes ([fewer]). [Detail.MORE] (with [act]) keeps every note
         * of the normal line, from [act]'s normal [candidates], and lets weaker notes in between them.
         * Notes without onset evidence (made by hand, e.g. a saved line) are always kept.
         */
        fun bouzoukiLine(
            notes: List<NoteEvent>,
            instrument: Instrument,
            act: BasicPitch.Activations? = null,
            attacks: Attacks? = null,
            detail: Detail = Detail.NORMAL,
        ): List<NoteEvent> {
            val low = instrument.courses.first().openMidi
            val high = instrument.courses.last().openMidi + instrument.frets
            fun inRange(list: List<NoteEvent>) = list.filter { it.midi in low..high }.sortedBy { it.start }
            fun joined(c: List<NoteEvent>) = if (attacks != null) joinRedetections(c, act, attacks) else c
            var c = inRange(notes)
            if (c.isEmpty()) return emptyList()
            if (c.all { it.onset.isNaN() }) return trimEnds(c) // a line chosen before: keep it as it is
            c = joined(c)
            var forced: Set<Long>? = null
            if (detail == Detail.MORE && act != null) {
                // The normal line's notes stay (one missing from these candidates joins them); weaker ones fill in.
                val normal = trimEnds(track(joined(inRange(candidates(act, Detail.NORMAL))), act, attacks, EVIDENCE_NEEDED, null).first)
                val have = c.mapTo(HashSet()) { key(it) }
                c = (c + normal.filter { key(it) !in have }).sortedBy { it.start }
                forced = normal.mapTo(HashSet()) { key(it) }
            }
            val (path, worth) = track(c, act, attacks, if (detail == Detail.MORE) EVIDENCE_NEEDED_MORE else EVIDENCE_NEEDED, forced)
            var line = joinTremolo(trimEnds(path))
            if (detail == Detail.FEWER) line = fewer(line, path.indices.associate { path[it].start to worth[it] }, act, attacks)
            return if (act?.contours != null && attacks != null) articulate(line, act, attacks) else line
        }

        /** A candidate's identity across candidate lists (its pitch and first frame). */
        private fun key(n: NoteEvent) = n.frame.toLong() * 256 + n.midi

        /**
         * The Viterbi pass of [bouzoukiLine] over [c] (in range, in time order): the chosen notes and the evidence each
         * brought ([needed] is what a note must reach; a note in [forced] gets [GIVEN_NOTE] more, so it stays).
         */
        private fun track(
            c: List<NoteEvent>,
            act: BasicPitch.Activations?,
            attacks: Attacks?,
            needed: Double,
            forced: Set<Long>?,
        ): Pair<List<NoteEvent>, DoubleArray> {
            if (c.isEmpty()) return emptyList<NoteEvent>() to DoubleArray(0)
            // What each note's attack adds to its evidence (0 when the attacks are unknown or soft), and the evidence.
            val worth = DoubleArray(c.size) { i ->
                val attack = if (attacks == null || c[i].onset.isNaN()) 0.0
                    else ATTACK_WEIGHT * max(0.0, min(ATTACK_CAP, ln(1 + attacks.rise(c[i].start)) - ln(1 + PICK_ATTACK)))
                evidence(c[i], needed) + attack + harmonic(c[i], act)
            }
            val gain = if (forced == null) worth else DoubleArray(c.size) { i -> worth[i] + if (key(c[i]) in forced) GIVEN_NOTE else 0.0 }
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
                best[i] = gain[i] + b
            }
            val path = ArrayList<NoteEvent>()
            val brought = ArrayList<Double>()
            var i = best.indices.maxBy { best[it] }
            while (i >= 0) {
                path += c[i]
                brought += worth[i]
                i = back[i]
            }
            path.reverse()
            brought.reverse()
            return path to brought.toDoubleArray()
        }

        /**
         * [line] without its weak notes, for [Detail.FEWER]: weak is under [FEWER_KEEP] or, where most notes are weaker
         * than that (a quiet or blurred recording), among the weakest [FEWER_SHARE] of the line, so a song never loses
         * more than about that share. [worth] is each note's evidence by its start. Tremolo stays, and so does a
         * hammer-on, pull-off or slide whose note before it stays (the two are played as one).
         */
        private fun fewer(line: List<NoteEvent>, worth: Map<Double, Double>, act: BasicPitch.Activations?, attacks: Attacks?): List<NoteEvent> {
            val values = line.filter { it.articulation != Articulation.TREMOLO }.map { worth[it.start] ?: GIVEN_NOTE }.sorted()
            val bar = if (values.isEmpty()) FEWER_KEEP else min(FEWER_KEEP, values[min(values.size - 1, (FEWER_SHARE * values.size).toInt())])
            val marked = if (act != null && attacks != null && act.contours != null) articulate(line, act, attacks) else line
            var kept = false
            return line.filterIndexed { k, n ->
                kept = n.articulation == Articulation.TREMOLO || (worth[n.start] ?: GIVEN_NOTE) >= bar || (kept && marked[k].articulation.isLegato)
                kept
            }
        }

        /**
         * Candidates without a pick of their own that start where one of the same pitch ends ([REDETECTION_GAP]),
         * or within [BRIDGE_TIME] of its end while the contour shows the string still sounding near the pitch, are
         * that note detected again (after a bend or vibrato, or the pair of strings beating): each is joined into it.
         * Unless the line went to a neighbouring note and back meanwhile, as in a trill or mordent ([leftFor]).
         */
        private fun joinRedetections(c: List<NoteEvent>, act: BasicPitch.Activations?, attacks: Attacks): List<NoteEvent> {
            val out = ArrayList<NoteEvent>(c.size)
            val latest = ArrayList<NoteEvent>(c.size) // the last candidate joined into each note of out (or itself)
            val last = HashMap<Int, Int>() // pitch -> index in out of its latest note
            for (n in c) {
                val k = last[n.midi]
                if (k != null && !n.onset.isNaN() && attacks.rise(n.start) < PICK_ATTACK) {
                    val p = out[k]
                    if ((p.end >= n.start - REDETECTION_GAP || (p.end >= n.start - BRIDGE_TIME && sounds(act, n.midi, p.endFrame, n.frame))) &&
                        !leftFor(out, latest[k], n, act)) {
                        out[k] = p.copy(end = max(p.end, n.end), endFrame = max(p.endFrame, n.endFrame))
                        latest[k] = n
                        continue
                    }
                }
                last[n.midi] = out.size
                out += n
                latest += n
            }
            return out
        }

        /**
         * Whether, after [from] and before [n] (same pitch, both [SEPARATION] away), a note within [LEGATO_REACH] of
         * them started in [out] and [n]'s pitch stopped sounding as it did (its activation under
         * [TRILL_LEVEL] in the [TRILL_FRAMES] after; without [act], any such note): the line left the pitch
         * and came back.
         */
        private fun leftFor(out: List<NoteEvent>, from: NoteEvent, n: NoteEvent, act: BasicPitch.Activations?): Boolean {
            val key = n.midi - BasicPitch.MIDI_OFFSET
            for (i in out.indices.reversed()) {
                val y = out[i]
                if (y.start <= from.start + SEPARATION) break // out is in time order
                if (y.start >= n.start - SEPARATION || abs(y.midi - n.midi) !in 1..LEGATO_REACH) continue
                if (act == null || y.frame < 0) return true
                val end = min(act.frames.size, max(y.frame + 1, min(n.frame, y.frame + TRILL_FRAMES)))
                var low = Float.MAX_VALUE
                for (t in y.frame until end) low = min(low, act.frames[t][key])
                if (low < TRILL_LEVEL) return true
            }
            return false
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
         * from A when it is within [LEGATO_REACH] semitones ([SLIDE_REACH] for a slide); A sounds up to B (its note
         * reaches B, its activation just before B is at least [HANDOVER_BEFORE]) and fades as B takes over (3-8 frames
         * into B, to at most [HANDOVER_FADE] of that while B reaches [LEGATO_STRONG], or to at most [HANDOVER_CLEAR] of
         * it while B reaches [LEGATO_WEAK]); and B's attack adds less than [LEGATO_ENERGY] of the energy the median
         * line note within [LOCAL_TIME] adds (nearby notes, so a fade-out or a quiet verse isn't taken for legato).
         * B is a [Articulation.SLIDE] when the pitch contour glides from A to B (or, within [LEGATO_REACH], B adds
         * almost no energy and its onset is soft), else a [Articulation.HAMMER] (up) or [Articulation.PULL] (down),
         * which need a clear onset.
         */
        fun articulate(line: List<NoteEvent>, act: BasicPitch.Activations, attacks: Attacks): List<NoteEvent> {
            if (line.size < 2 || act.contours == null) return line
            val frames = act.frames
            val n = frames.size
            val energy = line.map { attacks.energy(it.start) }
            val typical = localMedians(line, energy)
            val out = line.toMutableList()
            for (j in 1 until out.size) {
                val a = out[j - 1]
                val b = out[j]
                val f = b.frame
                if (b.articulation != Articulation.PICK || f < 1 || f >= n || b.onset.isNaN() || typical[j] <= 0) continue
                val d = b.midi - a.midi
                if (abs(d) !in 1..SLIDE_REACH || a.end < b.start - LEGATO_GAP) continue
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
                val added = energy[j] / typical[j]
                if (added >= LEGATO_ENERGY) continue
                val glide = glides(act, a.midi, b.midi, f)
                if (!glide && abs(d) > LEGATO_REACH) continue // only a slide reaches further
                val slide = glide || (added < SLIDE_ENERGY && b.onset < LEGATO_ONSET)
                if (!slide && b.onset < LEGATO_ONSET) continue
                out[j] = b.copy(articulation = if (slide) Articulation.SLIDE else if (d > 0) Articulation.HAMMER else Articulation.PULL)
            }
            return out
        }

        /** For each note of [line], the median of [values] over the line's notes starting within [LOCAL_TIME] of it. */
        private fun localMedians(line: List<NoteEvent>, values: List<Double>): DoubleArray {
            val out = DoubleArray(line.size)
            var lo = 0
            var hi = 0 // the window is [lo, hi): the line is in time order
            for (j in line.indices) {
                while (line[lo].start < line[j].start - LOCAL_TIME) lo++
                while (hi < line.size && line[hi].start <= line[j].start + LOCAL_TIME) hi++
                out[j] = median(values.subList(lo, hi))
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

        /** How much a candidate is worth to the line before its attack and harmonics: [needed] is what it must reach. */
        private fun evidence(n: NoteEvent, needed: Double) =
            if (n.onset.isNaN()) GIVEN_NOTE else n.salience + ONSET_WEIGHT * n.onset - needed

        /**
         * What a candidate loses for the octave below sounding with it ([HARMONIC_FRAMES] into it): a melody note an
         * octave above a lower note is less likely than that note's harmonic.
         */
        private fun harmonic(n: NoteEvent, act: BasicPitch.Activations?): Double {
            val key = n.midi - BasicPitch.MIDI_OFFSET
            if (act == null || n.frame < 0 || n.onset.isNaN() || key < 12) return 0.0
            return HARMONIC_WEIGHT * act.frames[min(act.frames.size - 1, n.frame + HARMONIC_FRAMES)][key - 12]
        }

        /** What it costs the line to move from [a] to [b] ([picked]: b brings a pick of its own). */
        private fun transitionCost(a: NoteEvent, b: NoteEvent, act: BasicPitch.Activations?, picked: Boolean = true): Double {
            val d = abs(b.midi - a.midi)
            val gap = b.start - max(a.end, a.start)
            val pause = max(PAUSE_FLOOR, 1 - max(0.0, gap) / PAUSE_TIME) // jumps after a pause are cheaper
            var cost = (STEP_COST * d + (if (d > 12) LEAP_COST else 0.0)) * pause
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

        // The line tracker's weights, chosen on synthesized bouzouki songs, both clean and blurred the way recordings
        // are (reverb, low-pass, noise, lossy coding): scratch evaluation, mir_eval F-measure.
        private const val EVIDENCE_NEEDED = 0.5
        /** What a note needs with [Detail.MORE]: a fuller line, weaker notes too. */
        private const val EVIDENCE_NEEDED_MORE = 0.4
        /** The evidence a line note keeps with [Detail.FEWER] (counted as for the normal line)... */
        private const val FEWER_KEEP = 0.4
        /** ... or, where most notes have less, the share of the line's weakest notes [Detail.FEWER] leaves out. */
        private const val FEWER_SHARE = 0.2
        private const val ONSET_WEIGHT = 0.5
        private const val GIVEN_NOTE = 10.0
        /** Per natural-log step of a note's attack over [PICK_ATTACK] (more evidence; a softer attack adds none)... */
        private const val ATTACK_WEIGHT = 0.2
        /** ... counting at most this many steps (a percussion hit on top of a pick adds nothing more). */
        private const val ATTACK_CAP = 1.0
        /** Per unit of the octave below's activation [HARMONIC_FRAMES] into a note. */
        private const val HARMONIC_WEIGHT = -0.25
        private const val HARMONIC_FRAMES = 2
        private const val STEP_COST = 0.05
        private const val REPEAT_COST = 0.2
        private const val LEAP_COST = 0.6
        private const val PAUSE_TIME = 1.0
        private const val PAUSE_FLOOR = 0.3
        private const val STILL_SOUNDING_FRAMES = 2
        private const val STILL_SOUNDING_LEVEL = 0.35
        private const val STILL_SOUNDING_COST = 0.75
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
        /** How soon a note's pitch must stop sounding (fall under [TRILL_LEVEL]) after a neighbour starts, for the line to have left it. */
        private const val TRILL_FRAMES = 6
        private const val TRILL_LEVEL = 0.3
        private const val LEGATO_REACH = 4
        /** A slide (the contour glides) may reach this far: up to a fifth along one course. */
        private const val SLIDE_REACH = 7
        private const val LEGATO_GAP = 0.02
        private const val HANDOVER_BEFORE = 0.2
        private const val HANDOVER_FADE = 0.8
        private const val HANDOVER_CLEAR = 0.5
        private const val LEGATO_STRONG = 0.6
        private const val LEGATO_WEAK = 0.45
        private const val LEGATO_ENERGY = 0.4
        /** How far (seconds) the notes a note's attack is compared with may lie (levels change over a song). */
        private const val LOCAL_TIME = 6.0
        private const val LEGATO_ONSET = 0.85
        private const val SLIDE_ENERGY = 0.05
        private const val GLIDE_FRAMES = 8
        private const val GLIDE_LEVEL = 0.2
        private const val GLIDE_MARGIN = 0.25

        /**
         * The course and fret of each note of a line, as a Greek player fingers a melody: on the top two
         * courses, shifting up and down the neck rather than crossing to the bass courses ([Fingering]).
         * [legato]`[i]` marks a note hammered, pulled or slid from the one before: it stays on that note's
         * course whenever the course has it. [slide]`[i]` marks the slides among them (never from or to an
         * open string); without it, legato notes are taken for hammer-ons and pull-offs.
         */
        fun fingering(
            midis: List<Int>,
            starts: List<Double>,
            inst: Instrument,
            legato: List<Boolean>? = null,
            slide: List<Boolean>? = null,
        ): List<Pair<Int, Int>> = Fingering.place(midis, starts, inst, legato, slide)
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
