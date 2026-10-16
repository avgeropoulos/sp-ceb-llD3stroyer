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

/**
 * A prepared song: the bouzouki line with frets, the dromos it seems to be in, its beats (seconds), and how
 * far the recording is tuned from A = 440 Hz ([tuningCents], + sharp, - flat; old records often are a little
 * off, and a player tunes to them to play along). [line] is the version of [SongAnalyzer] that derived the line
 * ([LINE] for a line derived now): a song with an older line and its [AnalysisData] gets the new line from that
 * ([SongAnalyzer.rebuild]) without preparing it again.
 */
data class SongAnalysis(
    val id: String,
    val title: String,
    val instrument: Instrument,
    val notes: List<SongNote>,
    val dromos: Dromos?,
    val rootPc: Int,
    val beats: List<Double> = emptyList(),
    val tuningCents: Double = 0.0,
    val line: Int = LINE,
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
        "tuning" to fmt(tuningCents),
        "line" to line.toString(),
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

        /**
         * The version of the line derivation: bump when [SongAnalyzer] derives a different line from the same saved
         * [AnalysisData] (songs saved before have an older [line] and are derived again from their data, see
         * [isLineOld]); 0 is the line of songs saved before it was recorded. 1: tremolo from the picks, repeated
         * notes, quick turns, and the accompaniment (bass, baglamas, strums) kept out of the line.
         */
        const val LINE = 1

        /** Whether [song]'s line is older than what [SongAnalyzer.rebuild] derives now from its data. */
        fun isLineOld(song: SongAnalysis): Boolean = song.line < LINE

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
                tuningCents = (p["tuning"] as Number?)?.toDouble() ?: 0.0,
                line = (p["line"] as Number?)?.toInt() ?: 0,
            )
        } catch (_: Exception) { null }
    }
}

/**
 * Turns a song into its bouzouki line:
 * 1. remove the vocals (MDX-Net),
 * 2. transcribe the rest into notes (Basic Pitch), put in tune first if the recording is off A = 440 Hz ([Tuning]),
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

    /**
     * Like [analyze], also returning what the line was derived from, to save with the song (see [rebuild]).
     * [separated] sees the song without its voice (mono, 44.1 kHz) once it's ready; the app doesn't need it.
     */
    fun analyzeWithData(
        id: String,
        title: String,
        left: FloatArray,
        right: FloatArray,
        instrument: Instrument,
        progress: (Stage, Float) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
        separated: (FloatArray) -> Unit = {},
    ): Pair<SongAnalysis, AnalysisData> {
        progress(Stage.SEPARATING, 0f)
        val instrumental = separator.instrumentalMono(left, right, { progress(Stage.SEPARATING, it) }, cancelled)
        separated(instrumental)
        return analyzeInstrumental(id, title, instrumental, instrument, progress, cancelled)
    }

    /**
     * [analyzeWithData] once the voice is removed, from the song without it ([instrumental]: mono, 44.1 kHz): measures
     * how far the recording is tuned from A = 440 Hz ([SongAnalysis.tuningCents], [AnalysisData.tuning]), transcribes it
     * (put in tune first when it is [Tuning.MIN_CORRECTION] or more off, with every time mapped back to the
     * recording's), finds the beats and derives the line.
     */
    fun analyzeInstrumental(
        id: String,
        title: String,
        instrumental: FloatArray,
        instrument: Instrument,
        progress: (Stage, Float) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): Pair<SongAnalysis, AnalysisData> {
        progress(Stage.TRANSCRIBING, 0f)
        val audio = resample(instrumental, MdxSeparator.SAMPLE_RATE, BasicPitch.SAMPLE_RATE)
        val tuning = Tuning.estimate(audio)
        val raw = transcribe(audio, tuning, { progress(Stage.TRANSCRIBING, it * 0.9f) }, cancelled)
        progress(Stage.PLACING, 0f)
        // The beats lay the tablature out in bars; a song without a clear pulse just gets none.
        val beats = try { Beats.estimate(Beats.envelope(raw), Beats.FPS) } catch (_: Exception) { emptyList() }
        // The line comes from the activations as they are saved, so rebuild() gives this very line again.
        val data = AnalysisData.of(raw, Attacks.of(audio), tuning)
        return build(id, title, candidates(data.act), instrument, data.act, data.attacks).copy(beats = beats, tuningCents = tuning) to data
    }

    /**
     * Basic Pitch's activations of [audio] (22050 Hz) on its own time. A recording [tuning] cents off (by
     * [Tuning.MIN_CORRECTION] or more) is [Tuning.retune]d into tune first, and the activations mapped back.
     */
    private fun transcribe(audio: FloatArray, tuning: Double, progress: (Float) -> Unit, cancelled: () -> Boolean): BasicPitch.Activations {
        if (abs(tuning) < Tuning.MIN_CORRECTION) return pitch.activations(audio, progress, cancelled)
        val rate = Tuning.rateFor(tuning)
        val act = pitch.activations(Tuning.retune(audio, rate), progress, cancelled)
        return Tuning.toRealTime(act, rate, BasicPitch.frameCount(audio.size))
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
         * How far the recording behind [data] is tuned from A = 440 Hz (cents, + sharp, - flat) as measured when it
         * was prepared; null for data saved before the tuning was measured. Its contours can tell such a song was
         * far off ([transcribedOutOfTune]), but not by how much: their reading can be 15 cents out (and from about 45
         * cents flat it can even point the other way), no tuning to show a player or tune to.
         */
        fun tuningOf(data: AnalysisData): Double? = data.tuning

        /**
         * Whether [data] was transcribed from a recording out of tune as it was (saved before the tuning was measured,
         * and its contours read [Tuning.RETUNE_HINT] cents off or more): Basic Pitch then missed notes, so preparing
         * the song again finds more of them. A yes/no hint only: it misses records up to about 12 cents sharp and
         * some 5 flat, which read like records in tune (a real recording 5 flat lost a fifth of its notes), so every
         * song saved before the tuning was measured ([AnalysisData.tuning] null) may gain a little from preparing again.
         */
        fun transcribedOutOfTune(data: AnalysisData): Boolean =
            data.tuning == null && (Tuning.fromContours(data.act)?.let { abs(it) >= Tuning.RETUNE_HINT } ?: false)

        /**
         * The line of a prepared song derived again from its saved [data] with [detail], for the bouzouki it was
         * prepared for (show it through [SongAnalysis.forInstrument] as usual); title, beats, dromos and tuning stay.
         * With [Detail.NORMAL] it is the line [analyzeWithData] made, or makes now for a song saved with an older line
         * ([SongAnalysis.isLineOld]: the result is the current [SongAnalysis.LINE]).
         */
        fun rebuild(base: SongAnalysis, data: AnalysisData, detail: Detail = Detail.NORMAL): SongAnalysis =
            build(base.id, base.title, candidates(data.act, detail), base.instrument, data.act, data.attacks, detail)
                .copy(beats = base.beats, dromos = base.dromos, rootPc = base.rootPc, tuningCents = base.tuningCents, line = SongAnalysis.LINE)

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
         * The band is kept out of the line: a candidate that starts with others of which one is louder (a strum's string,
         * [strumCue]) costs, as does one whose attack adds less energy than the typical candidate's ([energyCue]: a
         * strummed or bowed accompaniment, a bass) unless it is sounded legato from the line's note before
         * ([legatoFrom]), and moving on while the note before rings on well into the next one ([LATE_COST]: the
         * bouzouki stops a note it moves on from, a baglamas chord tone doesn't stop the melody's note). Repeated picks
         * of a pitch stay notes of their own: a re-pick Basic Pitch heard only as a weak onset splits the note
         * ([splitRepicks]), a same-pitch candidate with an attack is not joined ([REPICK_ATTACK]) and a picked repeat
         * costs less ([REPEAT_PICKED_COST]); fast even picking of one pitch becomes tremolo ([pickedTremolo]); a quick
         * turn's neighbour may follow closer than other notes ([ORNAMENT_SEPARATION]), and one hidden in a held note
         * comes out ([ornaments]).
         * [Detail.FEWER] is this line without its weak notes ([fewer]). [Detail.MORE] (with [act]) keeps every note
         * of the normal line, from [act]'s normal [candidates], and lets weaker notes in between them ([withNormal]).
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
            fun joined(c: List<NoteEvent>) =
                if (attacks != null) joinRedetections(if (act != null) splitRepicks(c, act, attacks) else c, act, attacks) else c
            var c = inRange(notes)
            if (c.isEmpty()) return emptyList()
            if (c.all { it.onset.isNaN() }) return trimEnds(c) // a line chosen before: keep it as it is
            c = joined(c)
            // Tremolo becomes one note, and quick turns hidden in held notes come out.
            fun finished(path: List<NoteEvent>): List<NoteEvent> {
                var l = joinTremolo(trimEnds(path))
                if (attacks != null && act != null) l = pickedTremolo(l, act, attacks)
                if (attacks != null && act?.contours != null) l = ornaments(l, act, attacks)
                return l
            }
            var forced: Set<Long>? = null
            var normalLine: List<NoteEvent>? = null
            if (detail == Detail.MORE && act != null) {
                // The normal line's notes stay (one missing from these candidates joins them); weaker ones fill in.
                val normal = trimEnds(track(joined(inRange(candidates(act, Detail.NORMAL))), act, attacks, EVIDENCE_NEEDED, null).first)
                val have = c.mapTo(HashSet()) { key(it) }
                c = (c + normal.filter { key(it) !in have }).sortedBy { it.start }
                forced = normal.mapTo(HashSet()) { key(it) }
                normalLine = finished(normal)
            }
            val (path, worth) = track(c, act, attacks, if (detail == Detail.MORE) EVIDENCE_NEEDED_MORE else EVIDENCE_NEEDED, forced)
            var line = finished(path)
            if (normalLine != null) line = withNormal(line, normalLine)
            if (detail == Detail.FEWER) line = fewer(line, path.indices.associate { path[it].start to worth[it] }, act, attacks)
            return if (act?.contours != null && attacks != null) articulate(line, act, attacks) else line
        }

        /**
         * Candidates cut where the plectrum strikes their pitch again while it still sounds: the onset activation at
         * their key peaks again (at [SPLIT_ONSET] or more, [SPLIT_BUMP] over its low since the last peak) with a sharp
         * attack ([SPLIT_ATTACK]) while the note's activation is at least [SPLIT_LEVEL]. Basic Pitch often gives a
         * re-picked note (repeated sixteenths, the last notes of a picked triplet) a weak second onset only.
         */
        private fun splitRepicks(c: List<NoteEvent>, act: BasicPitch.Activations, attacks: Attacks): List<NoteEvent> {
            val out = ArrayList<NoteEvent>(c.size)
            val frames = act.frames
            val onsets = act.onsets
            for (n in c) {
                if (n.onset.isNaN() || n.frame < 0 || n.endFrame <= n.frame) { out += n; continue }
                val k = n.midi - BasicPitch.MIDI_OFFSET
                var piece = n
                var low = Float.MAX_VALUE
                for (f in n.frame + 1 until min(n.endFrame - 2, onsets.size - 1)) {
                    val o = onsets[f][k]
                    low = min(low, o)
                    if (f - piece.frame < SPLIT_FRAMES || o < SPLIT_ONSET || o < onsets[f - 1][k] || o <= onsets[f + 1][k]) continue
                    if (o - low < SPLIT_BUMP || frames[f][k] < SPLIT_LEVEL) continue
                    val t = BasicPitch.onsetTime(act, f, k)
                    if (attacks.rise(t) < SPLIT_ATTACK) continue
                    out += piece.copy(end = BasicPitch.frameTime(f), endFrame = f)
                    var sal = 0f
                    for (u in f until min(f + 4, piece.endFrame)) sal = max(sal, frames[u][k])
                    var sum = 0.0
                    for (u in f until piece.endFrame) sum += frames[u][k]
                    piece = piece.copy(start = t, frame = f, onset = o.toDouble(), salience = sal.toDouble(), amplitude = sum / (piece.endFrame - f))
                    low = Float.MAX_VALUE
                }
                out += piece
            }
            return if (out.size == c.size) c else out.sortedBy { it.start }
        }

        /**
         * Held notes picked fast and evenly become one [Articulation.TREMOLO] note: from a line note on, while its pitch
         * keeps sounding (activation [TREMOLO_LEVEL] or more), the attack track shows picks of that pitch ([Attacks.picks],
         * [atPitch]) no further apart than [TREMOLO_MAX_GAP], [TREMOLO_PICKS] or more of them at a median interval of
         * [TREMOLO_PICK_GAP] or less (picked sixteenths and triplets are slower, or too few). Line notes that start among
         * those picks are part of it (the same pitch picked again, or the accompaniment heard through it). The tremolo
         * starts where the picks come evenly (a note picked once before it stays a note of its own) and reaches back
         * over the even picks of its pitch the line missed ([TREMOLO_BACK]).
         */
        private fun pickedTremolo(line: List<NoteEvent>, act: BasicPitch.Activations, attacks: Attacks): List<NoteEvent> {
            val out = ArrayList<NoteEvent>(line.size)
            val frames = act.frames
            var i = 0
            while (i < line.size) {
                val n = line[i]
                if (n.onset.isNaN() || n.frame < 0 || n.articulation == Articulation.TREMOLO) { out += n; i++; continue }
                val k = n.midi - BasicPitch.MIDI_OFFSET
                var f = n.frame
                var below = 0
                while (f < frames.size && below <= 2) { if (frames[f][k] < TREMOLO_LEVEL) below++ else below = 0; f++ }
                var until = BasicPitch.frameTime(f - below)
                // A note of another pitch starting more clearly than this one sounds on ends it (that note took over).
                for (j in i + 1 until line.size) {
                    val o = line[j]
                    if (o.start >= until) break
                    if (o.midi != n.midi && o.frame >= 0 && onsetNear(act, o.midi - BasicPitch.MIDI_OFFSET, o.frame) > onsetNear(act, k, o.frame) &&
                        o.end - o.start >= TREMOLO_TAKEOVER) { until = o.start; break }
                }
                val all = attacks.picks(n.start - Attacks.WINDOW, until, TREMOLO_PICK).filter { t -> atPitch(act, k, t) }
                if (all.size < TREMOLO_PICKS) { out += n; i++; continue }
                var m = 0
                while (m + 1 < all.size && all[m + 1] - all[m] <= TREMOLO_MAX_GAP) m++
                val chain = all.subList(0, m + 1)
                if (chain.size < TREMOLO_PICKS || abs(chain[0] - n.start) > Attacks.WINDOW * 2) { out += n; i++; continue }
                val gaps = chain.zipWithNext { a, b -> b - a }.sorted()
                val gap = gaps[gaps.size / 2]
                if (gap > TREMOLO_PICK_GAP) { out += n; i++; continue }
                // The tremolo proper starts where the picks come evenly (a note picked once before it is a note of its own).
                var first = 0
                while (first + 1 < chain.size && chain[first + 1] - chain[first] > TREMOLO_EVEN * gap) first++
                val picks = chain.subList(first, chain.size)
                if (picks.size < TREMOLO_PICKS) { out += n; i++; continue }
                val end = max(n.end, min(until, picks.last() + gap))
                var j = i + 1
                while (j < line.size && line[j].start < picks.last() + Attacks.WINDOW) j++
                // The tremolo may have started before the line found it: earlier picks of this pitch, as evenly spaced.
                var start = picks[0]
                if (first == 0) {
                    val earlier = attacks.picks(n.start - TREMOLO_BACK, n.start - Attacks.PICK_SPACING, TREMOLO_PICK)
                    for (t in earlier.reversed()) {
                        if (start - t > TREMOLO_EVEN * gap || frames[frameAt(t)][k] < TREMOLO_LEVEL || !atPitch(act, k, t)) break
                        start = t
                    }
                    while (start < n.start - Attacks.WINDOW && out.isNotEmpty() && out.last().start >= start - Attacks.WINDOW &&
                        out.last().articulation != Articulation.TREMOLO) out.removeAt(out.size - 1)
                }
                val tail = max(end, line[j - 1].end.takeIf { line[j - 1].midi == n.midi } ?: end)
                if (start > n.start + Attacks.WINDOW) {
                    // Picked once, then tremolo: two notes; the line's notes between them are the accompaniment heard.
                    out += n.copy(end = start)
                    out += n.copy(start = start, end = tail, frame = frameAt(start), articulation = Articulation.TREMOLO)
                } else {
                    val s0 = min(start, n.start)
                    out += (if (s0 < n.start) n.copy(start = s0, frame = frameAt(s0)) else n).copy(end = tail, articulation = Articulation.TREMOLO)
                }
                i = j
            }
            return trimEnds(out)
        }

        /**
         * Whether a pick at [t] (seconds) sounds key [k]: its onset activation peaks within 2 frames at [TREMOLO_ONSET]
         * or more and at no less than [TREMOLO_DOMINANCE] times any other key's in the bouzouki's range (an other note
         * picked while [k] rings on starts its own key more clearly).
         */
        private fun atPitch(act: BasicPitch.Activations, k: Int, t: Double): Boolean {
            val f0 = frameAt(t)
            var mine = 0f
            var other = 0f
            for (f in max(0, f0 - 2)..min(act.onsets.size - 1, f0 + 2)) {
                val row = act.onsets[f]
                mine = max(mine, row[k])
                for (j in IN_RANGE_KEYS) if (abs(j - k) > 1) other = max(other, row[j])
            }
            return mine >= TREMOLO_ONSET && mine >= TREMOLO_DOMINANCE * other
        }

        /** The strongest onset activation of key [k] within 2 frames of frame [f]. */
        private fun onsetNear(act: BasicPitch.Activations, k: Int, f: Int): Float {
            var m = 0f
            for (t in max(0, f - 2)..min(act.onsets.size - 1, f + 2)) m = max(m, act.onsets[t][k])
            return m
        }

        /** The model frame whose time is nearest [t] (seconds). */
        internal fun frameAt(t: Double): Int {
            var f = max(0, (t * BasicPitch.ANNOTATIONS_FPS).toInt() - 2)
            while (BasicPitch.frameTime(f + 1) <= t) f++
            return if (t - BasicPitch.frameTime(f) > BasicPitch.frameTime(f + 1) - t) f + 1 else f
        }

        /**
         * Quick turns inside a held note that Basic Pitch heard as one note (a mordent's neighbour is often too short for
         * a note of its own): early in a line note, the pitch contour sits on a neighbour (1-2 semitones away) for
         * [ORNAMENT_MIN_FRAMES] to [ORNAMENT_MAX_FRAMES] frames (with an onset of its own, [ORNAMENT_ONSET], not sounding
         * before, its activation reaching [ORNAMENT_PEAK] while the note's dips), then comes back to the note; or the
         * neighbour's onset jumps without a pick ([bump]). Vibrato and bends stay within half a semitone and slides
         * don't come back, so neither counts. The note becomes three: the note, the neighbour (hammer-on up, pull-off
         * down) and the note again.
         */
        private fun ornaments(line: List<NoteEvent>, act: BasicPitch.Activations, attacks: Attacks): List<NoteEvent> {
            val out = ArrayList<NoteEvent>(line.size)
            val n = act.frames.size
            for (a in line) {
                if (a.onset.isNaN() || a.frame < 0 || a.articulation != Articulation.PICK) { out += a; continue }
                val endF = min(n, frameAt(a.end))
                val last = min(endF - ORNAMENT_MIN_FRAMES - 1, frameAt(a.start + ORNAMENT_REACH_TIME))
                var f = a.frame + 1
                var found: Triple<Int, Int, Int>? = null // start frame, end frame, neighbour
                // the contour's semitone (relative to a) frame by frame, or null where it is weak or further away
                fun step(t: Int): Int? = contourStep(act, a.midi, t)
                while (f <= last && found == null) {
                    val s0 = step(f)
                    if (s0 == null || s0 == 0 || abs(s0) > ORNAMENT_REACH || step(f - 1) != 0) { f++; continue }
                    var e = f
                    while (e < endF && step(e) == s0) e++
                    val len = e - f
                    if (len in ORNAMENT_MIN_FRAMES..ORNAMENT_MAX_FRAMES && e + 1 < endF && step(e) == 0 && step(e + 1) == 0) {
                        val kb = a.midi + s0 - BasicPitch.MIDI_OFFSET
                        val ka = a.midi - BasicPitch.MIDI_OFFSET
                        var peak = 0f
                        var onset = 0f
                        var dip = Float.MAX_VALUE
                        for (t in f until e) { peak = max(peak, act.frames[t][kb]); dip = min(dip, act.frames[t][ka]) }
                        for (t in max(0, f - 2)..min(n - 1, f + 2)) onset = max(onset, act.onsets[t][kb])
                        var before = 0f // the neighbour must not be sounding already (a note before it ringing on)
                        for (t in max(0, a.frame - 2) until f - 1) before = max(before, act.frames[t][kb])
                        if (peak >= ORNAMENT_PEAK && onset >= ORNAMENT_ONSET && before < ORNAMENT_BEFORE * peak &&
                            dip < ORNAMENT_DIP * act.frames[f - 1][ka]) found = Triple(f, e, a.midi + s0)
                    }
                    f = e + 1
                }
                if (found == null) found = bump(act, attacks, a, endF)
                if (found == null) { out += a; continue }
                val (fb, fc, mb) = found
                val tb = BasicPitch.frameTime(fb)
                val tc = BasicPitch.frameTime(fc)
                val up = mb > a.midi
                out += a.copy(end = tb)
                out += a.copy(start = tb, end = tc, midi = mb, frame = fb, endFrame = fc, articulation = if (up) Articulation.HAMMER else Articulation.PULL)
                out += a.copy(start = tc, frame = fc, articulation = if (up) Articulation.PULL else Articulation.HAMMER)
            }
            return out
        }

        /**
         * A neighbour struck without a pick early in held note [a] (until frame [endF]): its key's onset activation
         * jumps from under [BUMP_LOW] to [BUMP_ONSET] or more with a soft attack ([BUMP_ATTACK]), its note activation
         * rises a little ([BUMP_RISE]) and falls back soon ([BUMP_FALL]), while [a] keeps sounding ([BUMP_KEEP]).
         * (start frame, end frame, neighbour) or null.
         */
        private fun bump(act: BasicPitch.Activations, attacks: Attacks, a: NoteEvent, endF: Int): Triple<Int, Int, Int>? {
            val ka = a.midi - BasicPitch.MIDI_OFFSET
            val n = act.frames.size
            val last = min(endF - 6, frameAt(a.start + ORNAMENT_REACH_TIME))
            var best: Triple<Int, Int, Int>? = null
            var bestPeak = 0f
            for (d in intArrayOf(-2, -1, 1, 2)) {
                val kb = ka + d
                if (kb < 0 || kb >= BasicPitch.N_KEYS) continue
                var low = Float.MAX_VALUE
                var lowFrame = Float.MAX_VALUE
                for (f in a.frame until min(n - 1, last + 1)) {
                    val o = act.onsets[f][kb]
                    if (f >= a.frame + 2 && o >= BUMP_ONSET && o >= act.onsets[f - 1][kb] && o > act.onsets[f + 1][kb] &&
                        low <= BUMP_LOW && o > bestPeak) {
                        var peak = 0f
                        for (t in f - 1..min(n - 1, f + 4)) peak = max(peak, act.frames[t][kb])
                        val after = act.frames[min(n - 1, f + 8)][kb]
                        val keeps = act.frames[min(n - 1, f + 6)][ka] >= BUMP_KEEP * act.frames[f - 1][ka]
                        val soft = attacks.rise(BasicPitch.onsetTime(act, f, kb)) < BUMP_ATTACK
                        if (peak >= lowFrame + BUMP_RISE && after <= BUMP_FALL * peak && keeps && soft) {
                            var e = f + 1
                            val mid = (peak + lowFrame) / 2
                            while (e < f + 6 && act.frames[e][kb] >= mid) e++
                            best = Triple(f, max(f + 2, e), a.midi + d)
                            bestPeak = o
                        }
                    }
                    low = min(low, o)
                    lowFrame = min(lowFrame, act.frames[f][kb])
                }
            }
            return best
        }

        /** The semitone (relative to [midi]) the pitch contour peaks at in frame [t], within 2 semitones; null if weak. */
        private fun contourStep(act: BasicPitch.Activations, midi: Int, t: Int): Int? {
            if (t < 0 || t >= act.frames.size) return null
            val c = 3 * (midi - BasicPitch.MIDI_OFFSET) + 1
            var best = -1
            var v = 0.0
            for (j in max(0, c - 7)..min(BasicPitch.N_CONTOUR_BINS - 1, c + 7)) {
                val x = act.contour(t, j)
                if (x > v) { v = x; best = j }
            }
            if (best < 0 || v < ORNAMENT_CONTOUR) return null
            val o = best - c
            val st = Math.round(o / 3.0).toInt()
            return if (abs(o) < 3 * abs(st)) null else st // between two semitones (a bend, wide vibrato): neither
        }

        /**
         * [more] (the [Detail.MORE] line) with every note of [normal] (the normal line) in it as the normal line has it:
         * the tremolo and turns found in each can differ where the fuller line has a note more. Notes of [more] that the
         * normal line has, or that start during one of its tremolos, give way to the normal line's.
         */
        private fun withNormal(more: List<NoteEvent>, normal: List<NoteEvent>): List<NoteEvent> {
            val tremolos = normal.filter { it.articulation == Articulation.TREMOLO }
            val extra = more.filter { m ->
                normal.none { it.midi == m.midi && abs(it.start - m.start) < SEPARATION } &&
                    tremolos.none { m.start >= it.start - SEPARATION && m.start < it.end } &&
                    !(m.articulation == Articulation.TREMOLO && normal.any { it.start >= m.start && it.start < m.end })
            }
            return trimEnds((normal + extra).sortedBy { it.start })
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
            // The energy a typical candidate's attack adds (the bouzouki's picks add more than the accompaniment's).
            val energyRef = if (attacks == null) 0.0 else median(c.filter { !it.onset.isNaN() }.map { attacks.energy(it.start) })
            // What each note's attack adds to its evidence (0 when the attacks are unknown or soft), and the evidence.
            val worth = DoubleArray(c.size) { i ->
                val attack = if (attacks == null || c[i].onset.isNaN()) 0.0
                    else ATTACK_WEIGHT * max(0.0, min(ATTACK_CAP, ln(1 + attacks.rise(c[i].start)) - ln(1 + PICK_ATTACK)))
                evidence(c[i], needed) + attack + harmonic(c[i], act) + energyCue(c[i], attacks, energyRef) + strumCue(c, i)
            }
            // What a legato note (sounded from the line's note before, still sounding: [legatoFrom]) gets back of the
            // accompaniment cues: it brings no pick, so it adds little energy, and it starts as the note before ends.
            val legatoRefund = DoubleArray(c.size) { i ->
                if (attacks == null || c[i].onset.isNaN()) 0.0
                else -(min(0.0, energyCue(c[i], attacks, energyRef)) + strumCue(c, i) + harmonic(c[i], act) * (1 - HARMONIC_LEGATO / HARMONIC_WEIGHT))
            }
            // Whether each note brings a clear pick of its own (the attacks known): picking a pitch again costs less then.
            val repicked = BooleanArray(c.size) { i -> attacks != null && !c[i].onset.isNaN() && attacks.rise(c[i].start) >= PICK_ATTACK }
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
                    // An ornament's neighbour may follow closer than other notes.
                    val apart = if (abs(c[i].midi - c[j].midi) in 1..ORNAMENT_REACH) ORNAMENT_SEPARATION else SEPARATION
                    if (c[i].start - c[j].start < apart && !(c[i].onset.isNaN() && c[j].onset.isNaN())) continue
                    var v = best[j] - transitionCost(c[j], c[i], act, picked, repicked[i])
                    if (legatoRefund[i] > 0 && legatoFrom(c[j], c[i], act)) v += legatoRefund[i]
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
                if (k != null && !n.onset.isNaN() && attacks.rise(n.start) < REPICK_ATTACK) {
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
         * Whether, after [from] and before [n] (same pitch, both [SEPARATION] away, [ORNAMENT_SEPARATION] for a
         * neighbour within [ORNAMENT_REACH]), a note within [LEGATO_REACH] of them started in [out] and [n]'s pitch
         * stopped sounding as it did (its activation under [TRILL_LEVEL] in the [TRILL_FRAMES] after; without [act], any
         * such note) or, for a neighbour within [ORNAMENT_REACH], sounded louder than it for a frame (a quick turn,
         * where the note never quite fades): the line left the pitch and came back.
         */
        private fun leftFor(out: List<NoteEvent>, from: NoteEvent, n: NoteEvent, act: BasicPitch.Activations?): Boolean {
            val key = n.midi - BasicPitch.MIDI_OFFSET
            for (i in out.indices.reversed()) {
                val y = out[i]
                if (y.start <= from.start + ORNAMENT_SEPARATION) break // out is in time order
                val sep = if (abs(y.midi - n.midi) <= ORNAMENT_REACH) ORNAMENT_SEPARATION else SEPARATION
                if (y.start <= from.start + sep || y.start >= n.start - sep || abs(y.midi - n.midi) !in 1..LEGATO_REACH) continue
                if (act == null || y.frame < 0) return true
                val end = min(act.frames.size, max(y.frame + 1, min(n.frame, y.frame + TRILL_FRAMES)))
                var low = Float.MAX_VALUE
                var over = 0 // frames where the neighbour sounds louder than the note (a quick turn: the note never fades)
                val ky = y.midi - BasicPitch.MIDI_OFFSET
                for (t in y.frame until end) {
                    low = min(low, act.frames[t][key])
                    if (act.frames[t][ky] > act.frames[t][key]) over++
                }
                if (low < TRILL_LEVEL) return true
                if (abs(y.midi - n.midi) <= ORNAMENT_REACH && over > 0) return true
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
                if (abs(d) <= ORNAMENT_REACH && energy[j] / typical[j] < ORNAMENT_ENERGY) {
                    // A quick turn to a neighbour and back (mordent, trill): the neighbour and the return sound off one pick.
                    val c = out.getOrNull(j + 1)
                    val turn = c != null && c.midi == a.midi && c.start - b.start <= ORNAMENT_NOTE && b.end >= c.start - LEGATO_GAP
                    val back = j >= 2 && out[j - 1].articulation.isLegato && out[j - 2].midi == b.midi && b.start - a.start <= ORNAMENT_NOTE
                    if (turn || back) {
                        out[j] = b.copy(articulation = if (d > 0) Articulation.HAMMER else Articulation.PULL)
                        continue
                    }
                }
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

        /** Whether [b] may be sounded from [a] without a pick: near, and [a] still sounds just before [b] starts. */
        private fun legatoFrom(a: NoteEvent, b: NoteEvent, act: BasicPitch.Activations?): Boolean {
            val d = abs(b.midi - a.midi)
            if (act == null || d !in 1..SLIDE_REACH || b.frame < 1 || a.end < b.start - LEGATO_FROM_GAP) return false
            val k = a.midi - BasicPitch.MIDI_OFFSET
            var before = 0f
            for (t in max(0, b.frame - 3) until min(act.frames.size, b.frame)) before = max(before, act.frames[t][k])
            return before >= HANDOVER_BEFORE
        }

        /**
         * What the energy [n]'s attack adds says, against [ref] (the typical candidate's): the bouzouki is the band's
         * plucked, bright instrument, its picks add more than a strummed or bowed accompaniment's (per natural-log step,
         * [ENERGY_WEIGHT], at most [ENERGY_CAP] steps either way). 0 without the attacks.
         */
        private fun energyCue(n: NoteEvent, attacks: Attacks?, ref: Double): Double {
            if (attacks == null || ref <= 0 || n.onset.isNaN()) return 0.0
            return ENERGY_WEIGHT * max(-ENERGY_CAP, min(ENERGY_CAP, ln((attacks.energy(n.start) + ENERGY_OFFSET * ref) / ref)))
        }

        /**
         * What it costs candidate [i] of [c] (in time order) to start with others ([STRUM_TIME]) of which one sounds
         * louder ([STRUM_LOUDER]): a strum (guitar, baglamas) sounds several strings at once, the bouzouki's melody one,
         * and its note stands out of a strum it coincides with ([STRUM_COST] for each other one).
         */
        private fun strumCue(c: List<NoteEvent>, i: Int): Double {
            val n = c[i]
            if (n.onset.isNaN()) return 0.0
            var others = 0
            var louder = false // another string of the strum sounds clearly louder: this one is the accompaniment's
            val mine = n.salience + ONSET_WEIGHT * n.onset
            fun see(o: NoteEvent) {
                if (o.onset.isNaN()) return
                others++
                if (o.salience + ONSET_WEIGHT * o.onset > mine + STRUM_LOUDER) louder = true
            }
            var j = i - 1
            while (j >= 0 && n.start - c[j].start <= STRUM_TIME) { see(c[j]); j-- }
            j = i + 1
            while (j < c.size && c[j].start - n.start <= STRUM_TIME) { see(c[j]); j++ }
            if (!louder) return 0.0
            return -STRUM_COST * others
        }

        /**
         * What it costs the line to move from [a] to [b] ([picked]: b brings a pick of its own, or the attacks are
         * unknown; [repicked]: the attacks show b's pick, so b's pitch picked again is a repeated note more likely than a
         * held one detected twice).
         */
        private fun transitionCost(a: NoteEvent, b: NoteEvent, act: BasicPitch.Activations?, picked: Boolean = true, repicked: Boolean = false): Double {
            val d = abs(b.midi - a.midi)
            val gap = b.start - max(a.end, a.start)
            val pause = max(PAUSE_FLOOR, 1 - max(0.0, gap) / PAUSE_TIME) // jumps after a pause are cheaper
            var cost = (STEP_COST * d + (if (d > 12) LEAP_COST else 0.0)) * pause
            if (d == 0) cost += if (repicked) REPEAT_PICKED_COST else REPEAT_COST
            if (act != null && d != 0 && b.frame >= 0) {
                val t = min(act.frames.size - 1, b.frame + STILL_SOUNDING_FRAMES)
                val weight = if (picked) STILL_SOUNDING_COST else STILL_SOUNDING_COST * (1 + UNPICKED_EXTRA)
                cost += weight * max(0.0, act.frames[t][a.midi - BasicPitch.MIDI_OFFSET] - STILL_SOUNDING_LEVEL)
                if (d >= LATE_MIN_STEP) { // and while it rings on well into b (the bouzouki stops a note it moves on from)
                    val u = min(act.frames.size - 1, b.frame + LATE_FRAMES)
                    cost += LATE_COST * max(0.0, act.frames[u][a.midi - BasicPitch.MIDI_OFFSET] - LATE_LEVEL)
                }
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
        /** Per unit of the octave below's activation [HARMONIC_FRAMES] into a note (a harmonic, or a baglamas doubling the
         * melody an octave up)... */
        private const val HARMONIC_WEIGHT = -0.4
        /** ... of which a legato note pays only this much. */
        private const val HARMONIC_LEGATO = -0.25
        /** Per natural-log step of the energy a note's attack adds over the typical candidate's ([energyCue])... */
        private const val ENERGY_WEIGHT = 0.3
        /** ... counting at most this many steps either way ... */
        private const val ENERGY_CAP = 1.0
        /** ... with this share of the typical energy added to every attack (so no attack counts as nothing). */
        private const val ENERGY_OFFSET = 0.05
        /** Candidates starting within this (seconds) of each other sound together, as a strum's strings do ([strumCue])... */
        private const val STRUM_TIME = 0.03
        /** ... and each other one costs this much, when one of them is louder by this much (evidence before attacks): the
         * melody's note, the loudest of a strum it is played with, pays nothing. */
        private const val STRUM_COST = 0.08
        private const val STRUM_LOUDER = 0.1
        /** A legato note's note before must reach this close (seconds) to it ([legatoFrom]). */
        private const val LEGATO_FROM_GAP = 0.03
        /** Picking a pitch again with a clear pick of its own costs this ([REPEAT_COST] otherwise). */
        private const val REPEAT_PICKED_COST = 0.1
        /** Moving on by this many semitones or more ... */
        private const val LATE_MIN_STEP = 3
        /** ... while the note before still sounds this many frames into the next one ... */
        private const val LATE_FRAMES = 8
        /** ... costs this much per unit of its activation over [LATE_LEVEL]. */
        private const val LATE_COST = 0.5
        private const val LATE_LEVEL = 0.2
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
        /** A re-pick found inside a candidate starts at least this many frames after the candidate (or piece) does ... */
        private const val SPLIT_FRAMES = 4
        /** ... at an onset peak of this ([splitRepicks]) ... */
        private const val SPLIT_ONSET = 0.35
        /** ... this much over the lowest onset activation since the candidate (or piece) started ... */
        private const val SPLIT_BUMP = 0.1
        /** ... with this sharp an attack (times the song's median frame) ... */
        private const val SPLIT_ATTACK = 30.0
        /** ... while the note's activation is at least this. */
        private const val SPLIT_LEVEL = 0.3
        /** A same-pitch candidate whose attack is under this (times the song's median frame) brought no pick: [joinRedetections]. */
        private const val REPICK_ATTACK = 12.0
        /** Tremolo ([pickedTremolo]): its pitch's activation stays at this or more ... */
        private const val TREMOLO_LEVEL = 0.25
        /** ... the picks counted rise this much (times the song's median frame) ... */
        private const val TREMOLO_PICK = 12.0
        /** ... at a median interval of this (seconds; 11 picks a second and up) ... */
        private const val TREMOLO_PICK_GAP = 0.09
        /** ... none further apart than this ... */
        private const val TREMOLO_MAX_GAP = 0.18
        /** ... evenly: no further apart than this many median intervals where the tremolo starts ... */
        private const val TREMOLO_EVEN = 1.8
        /** ... ended by a note of another pitch that starts more clearly and lasts this long (seconds) ... */
        private const val TREMOLO_TAKEOVER = 0.1
        /** ... reaching back this far (seconds) over picks the line missed. */
        private const val TREMOLO_BACK = 0.6
        /** A pick sounds a key ([atPitch]) when its onset activation reaches this ... */
        private const val TREMOLO_ONSET = 0.2
        /** ... and this share of any other key's. */
        private const val TREMOLO_DOMINANCE = 0.5
        /** An ornament (mordent, turn, trill) goes to a neighbour at most this many semitones away. */
        private const val ORNAMENT_REACH = 2
        /** ... and each of its notes lasts at most this long (seconds). */
        private const val ORNAMENT_NOTE = 0.12
        /** A turn hidden in a held note starts this soon after it (seconds)... */
        private const val ORNAMENT_REACH_TIME = 0.25
        /** ... and its neighbour lasts this many frames (2-8: 23-93 ms) ... */
        private const val ORNAMENT_MIN_FRAMES = 2
        private const val ORNAMENT_MAX_FRAMES = 8
        /** ... with its onset activation at this or more ... */
        private const val ORNAMENT_ONSET = 0.3
        /** ... its activation at this or more, having been under [ORNAMENT_BEFORE] of that before ... */
        private const val ORNAMENT_PEAK = 0.3
        private const val ORNAMENT_BEFORE = 0.6
        /** ... while the note's activation dips under this share of what it was ... */
        private const val ORNAMENT_DIP = 0.9
        /** ... and the pitch contour at this or more. */
        private const val ORNAMENT_CONTOUR = 0.3
        /** A neighbour's onset ([bump]) jumps to this from under [BUMP_LOW] ... */
        private const val BUMP_ONSET = 0.7
        private const val BUMP_LOW = 0.3
        /** ... with an attack under this (times the song's median frame: no pick) ... */
        private const val BUMP_ATTACK = 14.0
        /** ... its activation rising this much and down to [BUMP_FALL] of its peak 8 frames on ... */
        private const val BUMP_RISE = 0.05
        private const val BUMP_FALL = 0.8
        /** ... while the note keeps this share of its activation. */
        private const val BUMP_KEEP = 0.9
        /** An ornament's neighbour may start this soon (seconds) after the note before ([SEPARATION] otherwise). */
        private const val ORNAMENT_SEPARATION = 0.035
        /** A turn's neighbour and return add less than this share of the energy nearby notes' attacks add ([articulate]). */
        private const val ORNAMENT_ENERGY = 0.7
        /** The keys of the bouzouki's range (C3 to the top of the tetrachordo's D course), where tremolo picks are compared. */
        private val IN_RANGE_KEYS = (48 - BasicPitch.MIDI_OFFSET)..(89 - BasicPitch.MIDI_OFFSET)

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
