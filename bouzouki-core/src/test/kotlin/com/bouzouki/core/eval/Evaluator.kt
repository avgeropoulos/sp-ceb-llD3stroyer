package com.bouzouki.core.eval

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bouzouki.core.song.BasicPitch
import com.bouzouki.core.song.DemucsSeparator
import com.bouzouki.core.song.Detail
import com.bouzouki.core.song.LightSeparator
import com.bouzouki.core.song.MdxSeparator
import com.bouzouki.core.song.NoSeparation
import com.bouzouki.core.song.PostConfig
import com.bouzouki.core.song.Separator
import com.bouzouki.core.song.SongAnalyzer
import com.bouzouki.core.song.SongNote
import com.bouzouki.core.song.resample
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/** Seconds each stage of the transcription took, by name, in order. */
class Timings : LinkedHashMap<String, Double>() {
    val total get() = values.sum()
}

/**
 * A recording transcribed: its placed notes, how far it was found to be tuned from A = 440 Hz (cents), and what the
 * note model was given ([separated]: the separator's output, mono at 44.1 kHz) when the pipeline keeps it. [solo]:
 * prepared as the bouzouki alone ([com.bouzouki.core.song.SongAnalysis.solo]).
 */
class Transcript(val notes: List<SongNote>, val cents: Double = Double.NaN, val separated: FloatArray? = null, val solo: Boolean = false)

/** A way to transcribe a recording into placed notes, and how long each of its stages took. */
fun interface Pipeline {
    fun transcribe(item: EvalItem, left: FloatArray, right: FloatArray, timings: Timings): Transcript
}

/**
 * The app's pipeline ([SongAnalyzer]): a solo recording straight to the note model, a mix through [mixSeparator]
 * first (none given: the mix as it is). Timed by the analyzer's stages.
 */
class AppPipeline(
    private val pitch: BasicPitch,
    private val mixSeparator: Separator? = null,
    /** The post-processing settings for each recording (by default: solo takes as solo, [PostConfig.solo]). */
    private val post: (EvalItem) -> PostConfig = { PostConfig(solo = it.kind == "solo") },
    /**
     * Null: the line as the song is prepared. Else the line derived again from the saved data with this many notes
     * ([SongAnalyzer.rebuild], the song screen's "Notes: fewer / normal / more"), timed as the stage "rebuilding".
     */
    private val detail: Detail? = null,
) : Pipeline {
    override fun transcribe(item: EvalItem, left: FloatArray, right: FloatArray, timings: Timings): Transcript {
        val separator = if (item.kind == "mix") mixSeparator ?: NoSeparation else NoSeparation
        var stage: String? = null
        var since = System.nanoTime()
        fun lap(next: String?) {
            val now = System.nanoTime()
            stage?.let { timings[it] = (timings[it] ?: 0.0) + (now - since) / 1e9 }
            stage = next; since = now
        }
        var heard: FloatArray? = null
        val (prepared, data) = SongAnalyzer(separator, pitch, post(item)).analyzeWithData(
            item.name, item.name, left, right, item.instrument,
            progress = { s, _ -> val name = s.name.lowercase(Locale.ROOT); if (name != stage) lap(name) },
            separated = { heard = it },
        )
        var song = prepared
        if (detail != null) {
            lap("rebuilding")
            song = SongAnalyzer.rebuild(prepared, data, detail, post(item))
        }
        lap(null)
        return Transcript(song.notes, song.tuningCents, heard, song.solo)
    }

    companion object {
        val MODEL = File("../bouzouki/src/main/assets/basic_pitch.onnx")

        /** Runs [block] with the bundled Basic Pitch model in an ONNX Runtime session. */
        fun <T> withBasicPitch(threads: Int = Runtime.getRuntime().availableProcessors(), block: (BasicPitch) -> T): T {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return env.createSession(MODEL.path, options).use { block(BasicPitch(env, it)) }
        }
    }
}

/** The scores and times of one recording, and the tuning found ([cents]). */
class ItemResult(
    val item: EvalItem, val score: Score, val timings: Timings, val seconds: Double, val offset: Double, val cents: Double,
    /** Every note played and found with what the comparison made of it, when asked for (`dump`). */
    val notes: ErrorAnalysis.Notes? = null,
    /** What the separator let through of each part of the mix, for a synthetic mix with its parts kept ([SeparationCheck]). */
    val passed: SeparationCheck.Passed? = null,
    /** How the line plays in Bouzouki Hero, at each difficulty ([GameScore]). */
    val game: GameScore.Result? = null,
)

/** Runs a [Pipeline] over an evaluation set and reports how it did, overall, by group and kind, and per recording. */
object Evaluator {
    /**
     * With [dump], each recording's notes missed and found wrongly are printed too, with what each most likely is
     * ([ErrorAnalysis]); [band] then gives what else plays in a recording of so many seconds (a synthetic mix's band).
     */
    fun run(
        items: List<EvalItem>, pipeline: Pipeline, dump: Boolean = false,
        band: (EvalItem, List<RefNote>, Double) -> List<SynthSet.BandNote>? = { _, _, _ -> null }, log: (String) -> Unit = {},
    ): List<ItemResult> = items.map { item ->
        val wav = Wav.read(item.audio)
        val l0 = wav.channels[0]
        val r0 = wav.channels.getOrElse(1) { l0 }
        val left = if (wav.rate == Separator.SAMPLE_RATE) l0 else resample(l0, wav.rate, Separator.SAMPLE_RATE)
        val right = if (r0 === l0) left else if (wav.rate == Separator.SAMPLE_RATE) r0 else resample(r0, wav.rate, Separator.SAMPLE_RATE)
        val timings = Timings()
        val transcript = pipeline.transcribe(item, left, right, timings)
        val est = transcript.notes
        // A click plan without its offset: beat 0 is placed where the transcription says, then centred on the matches.
        val plan = File(item.audio.parentFile, item.name + ".plan.csv")
        var offset = 0.0
        var ref = item.ref
        if (plan.isFile && !EvalSet.hasOffset(plan.readLines())) {
            offset = Metrics.bestOffset(ref, est)
            ref = ref.map { it.copy(start = it.start + offset, end = it.end + offset) }
        }
        val score = Metrics.score(ref, est)
        val seconds = left.size.toDouble() / Separator.SAMPLE_RATE
        var notes: ErrorAnalysis.Notes? = null
        if (dump) {
            notes = ErrorAnalysis.of(item, ref, est, band(item, ref, seconds), seconds)
            for (p in notes.played) if (!p.found) log(String.format(Locale.US, "    missed %7.3f midi %d (course %d fret %d %s): %s", p.note.start, p.note.midi, p.note.course, p.note.fret, p.note.articulation, p.why))
            for (f in notes.found) if (!f.right) log(String.format(Locale.US, "    extra  %7.3f midi %d (course %d fret %d %s): %s", f.note.start, f.note.midi, f.note.course, f.note.fret, f.note.articulation, f.flags.joinToString(" + ")))
        }
        val tuning = if (item.cents != null) String.format(Locale.US, " · tuning %+.1f¢ (true %+.0f¢)", transcript.cents, item.cents) else ""
        log(String.format(Locale.US, "%-28s %s · %d a semitone off%s · %.2f s for %.1f s", item.name, score.summary(),
            score.tpSemitone - score.tp, tuning, timings.total, seconds))
        val passed = if (item.kind == "mix") transcript.separated?.let { SeparationCheck.of(item, it) } else null
        ItemResult(item, score, timings, seconds, offset, transcript.cents, notes, passed, GameScore.of(ref, est, item.instrument, seconds, transcript.solo))
    }

    /** The report as Markdown: the totals (and by kind and group), then each recording. */
    fun markdown(label: String, results: List<ItemResult>): String = buildString {
        fun total(rs: List<ItemResult>) = Score().also { s -> rs.forEach { s.add(it.score) } }
        fun rtf(rs: List<ItemResult>) = rs.sumOf { it.timings.total } / rs.sumOf { it.seconds }.coerceAtLeast(1e-9)
        fun row(name: String, rs: List<ItemResult>) {
            val s = total(rs)
            append(String.format(Locale.US, "| %s | %d | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %s | %s | %s | %.3f |\n", name, s.ref, s.est,
                s.note, s.precision, s.recall, s.octave, s.offset, Score.pct(s.course), Score.pct(s.fret),
                if (s.refTremolo == 0) "–" else "${s.tremoloRight}/${s.refTremolo} (${s.estTremolo} marked)", rtf(rs)))
        }
        append("# Evaluation: $label\n\n")
        append("Note F-measure: same pitch, onsets within ${(Metrics.ONSET_TOL * 1000).toInt()} ms. Octave-F: same pitch class. ")
        append("Offset-F: the end too (within 20% of the note, at least 50 ms). Course/fret: of the notes found, placed as played. ")
        append("RTF: processing seconds per second of audio (this machine, ${Runtime.getRuntime().availableProcessors()} cores).\n\n")
        val head = "| | notes | found | F | P | R | octave-F | offset-F | course | fret | tremolo | RTF |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n"
        append(head)
        row("**all**", results)
        for ((k, rs) in results.groupBy { it.item.kind }) row("kind: $k", rs)
        for ((g, rs) in results.groupBy { it.item.group }) row("group: $g", rs)
        for ((i, rs) in results.groupBy { it.item.instrument }) row("bouzouki: ${i.name.lowercase(Locale.ROOT)}", rs)
        val tuned = results.filter { it.item.cents != null && !it.cents.isNaN() }
        if (tuned.isNotEmpty()) {
            val err = tuned.map { abs(it.cents - it.item.cents!!) }
            append(String.format(Locale.US, "\nTuning found: mean error %.1f cents, worst %.1f cents, over %d recordings; %d notes a semitone off in all.\n",
                err.average(), err.max(), tuned.size, results.sumOf { it.score.tpSemitone - it.score.tp }))
        }
        append(GameScore.markdown(results.mapNotNull { r -> r.game?.let { r.item to it } }))
        append(timing(results))
        append("\n## Time by stage\n\n| stage | seconds | per audio second |\n|---|---|---|\n")
        val audio = results.sumOf { it.seconds }
        val stages = LinkedHashMap<String, Double>()
        for (r in results) for ((k, v) in r.timings) stages[k] = (stages[k] ?: 0.0) + v
        for ((k, v) in stages) append(String.format(Locale.US, "| %s | %.2f | %.4f |\n", k, v, v / audio))
        append(String.format(Locale.US, "| **total** | %.2f | %.4f |\n", stages.values.sum(), stages.values.sum() / audio))
        append("\n## Recordings\n\n").append(head)
        for (r in results) row(r.item.name, listOf(r))
        val passed = results.mapNotNull { r -> r.passed?.let { r.item.name to it } }
        if (passed.isNotEmpty()) append(SeparationCheck.markdown(passed))
        val notes = results.mapNotNull { it.notes }
        if (notes.isNotEmpty()) append(ErrorAnalysis.markdown(notes))
    }

    /**
     * How early or late the notes found are: the start found minus the start played, in ms (+ late, - early), over the
     * notes found at the pitch played within [Metrics.TIMING_TOL] (wider than the scoring window, for this only).
     */
    fun timing(results: List<ItemResult>): String = buildString {
        fun stats(rs: List<ItemResult>, keep: (Timed) -> Boolean = { true }) = TimingStats(rs.flatMap { r -> r.score.timing.filter(keep).map { it.error } })
        append("\n## Onset timing\n\nThe start found minus the start played, in ms (+ is late, - is early), for the notes found at the pitch played ")
        append("within ${(Metrics.TIMING_TOL * 1000).toInt()} ms (a wider window than the scores', for this table only). ")
        append("A frame of the note model is ${String.format(Locale.US, "%.1f", 1000.0 * BasicPitch.FFT_HOP / BasicPitch.SAMPLE_RATE)} ms.\n\n")
        append(TimingStats.HEAD)
        append(stats(results).row("**all**"))
        for ((k, rs) in results.groupBy { it.item.kind }) append(stats(rs).row("kind: $k"))
        for ((g, rs) in results.groupBy { it.item.group }) append(stats(rs).row("group: $g"))
        for (a in com.bouzouki.core.song.Articulation.entries) {
            val st = stats(results) { it.found == a }
            if (st.n > 0) append(st.row("marked ${a.name.lowercase(Locale.ROOT)}"))
        }
        for ((k, ts) in results.flatMap { it.score.timing }.groupBy { ErrorAnalysis.register(it.midi) }.toSortedMap())
            append(TimingStats(ts.map { it.error }).row("register $k"))
        append("\nBy recording:\n\n").append(TimingStats.HEAD)
        for (r in results) append(stats(listOf(r)).row(r.item.name))
    }

    /** One CSV line per recording (and "all"), to compare runs. */
    fun csv(label: String, results: List<ItemResult>): String = buildString {
        append("label,item,kind,group,instrument,ref,est,tp,tp_octave,tp_offset,tp_semitone,placed,same_course,same_fret,ref_tremolo,est_tremolo,tremolo_right,cents,true_cents,seconds,processing,timed,onset_mean_ms,onset_median_ms,onset_std_ms,onset_p10_ms,onset_p90_ms,${GameScore.csvHead}\n")
        for (r in results) {
            val s = r.score
            val t = TimingStats(s.timing.map { it.error })
            append(String.format(Locale.US, "%s,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%s,%.3f,%.3f,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%s\n", label, r.item.name, r.item.kind, r.item.group,
                r.item.instrument.name, s.ref, s.est, s.tp, s.tpOctave, s.tpOffset, s.tpSemitone, s.placed, s.sameCourse, s.sameFret,
                s.refTremolo, s.estTremolo, s.tremoloRight, r.cents, r.item.cents?.toString() ?: "", r.seconds, r.timings.total,
                t.n, t.mean, t.median, t.std, t.p10, t.p90, r.game?.let(GameScore::csv) ?: ""))
        }
    }
}

/**
 * Developer tool: scores the app's transcription on an evaluation set (see [EvalSet]).
 * `gradle :bouzouki-core:evalSet -PevalSet=<dir> -PevalOut=<dir> -PevalLabel=<name>`; with `-PevalSet=synth` it makes
 * the synthetic set ([SynthSet]) into build/eval/synth first, and with `-PevalSet=synthmix:<phrases>[:<tremolos>[:<seed>]]`
 * a larger set of mixes only ([SynthSet.writeMixes]: so many random phrases, and tremolo phrases, for each bouzouki,
 * in a band and alone) into build/eval/synthmix-<phrases>-<tremolos>-<seed>, and with `-PevalSet=synthverse:<n>[:<seed>]`
 * mixes in which the bouzouki sits out a sung verse ([SynthSet.writeVerses]). Writes <label>.md and <label>.csv.
 * `-PevalOnly=<regex>` runs only the recordings whose names match, and `-PevalDump=true` prints the notes missed and
 * the extra ones with what each most likely is, adds the error analysis to the report ([ErrorAnalysis]) and writes
 * every note to <label>.notes.csv. For the synthetic mixes the report also says how much of each part of the band
 * the separator lets through ([SeparationCheck]). Every report also says how the line plays in Bouzouki Hero at
 * each difficulty ([GameScore]: the game's chart of the line against its chart of the notes played).
 *
 * Environment: EVAL_SEPARATOR (what the mixes go through: [withMixSeparator]), EVAL_STEM_CACHE (a folder that keeps
 * what the separator gives for each recording, so that later runs skip it: [CachedSeparator]), EVAL_DETAIL (fewer,
 * normal or more: the line derived again with that many notes, as the song screen does), EVAL_POST and EVAL_FINGERING.
 */
fun main(args: Array<String>) {
    val setArg = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "synth"
    val out = File(args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "build/eval").apply { mkdirs() }
    val label = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "run"
    val synthetic = setArg == "synth" || setArg.startsWith("synthmix:") || setArg.startsWith("synthverse:")
    val dir = when {
        // With the band's parts of every mix (stems), also for a set made before they were kept (the audio comes out the same).
        setArg == "synth" -> File("build/eval/synth").also {
            if (!File(it, "open-tetra-courses.wav").exists() || !File(it, "stems").isDirectory) SynthSet.write(it, stems = true)
        }
        setArg.startsWith("synthmix:") -> {
            val n = setArg.removePrefix("synthmix:").split(':')
            val phrases = n[0].toInt(); val tremolos = n.getOrNull(1)?.toInt() ?: 0; val seed = n.getOrNull(2)?.toLong() ?: 2
            File("build/eval/synthmix-$phrases-$tremolos-$seed").also {
                if (!File(it, "done").exists()) { SynthSet.writeMixes(it, phrases, tremolos, seed); File(it, "done").writeText("") }
            }
        }
        setArg.startsWith("synthverse:") -> {
            val n = setArg.removePrefix("synthverse:").split(':')
            val verses = n[0].toInt(); val seed = n.getOrNull(1)?.toLong() ?: 3
            File("build/eval/synthverse-$verses-$seed").also {
                if (!File(it, "done").exists()) { SynthSet.writeVerses(it, verses, seed); File(it, "done").writeText("") }
            }
        }
        else -> File(setArg)
    }
    val only = args.getOrNull(3)?.takeIf { it.isNotBlank() }?.let(::Regex)
    val dump = args.getOrNull(4) == "true"
    val items = EvalSet.load(dir).filter { only == null || only.containsMatchIn(it.name) }
    require(items.isNotEmpty()) { "no recordings with a reference in $dir" }
    // EVAL_POST picks a variant of the post-processing to compare: "plain" (no octave merge, band cues everywhere),
    // "merge" (octave merge, band cues everywhere), "solo" (band cues off for solo takes, no merge), or the default (both).
    val post: (EvalItem) -> PostConfig = when (System.getenv("EVAL_POST")) {
        "plain" -> { _ -> PostConfig(octaveMerge = false) }
        "merge" -> { _ -> PostConfig() }
        "solo" -> { it -> PostConfig(octaveMerge = false, solo = it.kind == "solo") }
        // As calibration on the synth measures it (CalibrationTest): octave strings 0.76x, noise floor 0.14.
        "calibrated" -> { it -> if (it.kind == "solo") PostConfig(solo = true).calibrated(0.76, 0.14) else PostConfig().calibrated(0.76, null) }
        // To tell where the mixes lose notes: "bandmerge" merges octaves in a band's mix too (whatever the separator
        // keeps), "assolo" takes a mix for a solo recording (the band's cues off, octaves merged).
        "bandmerge" -> { it -> PostConfig(octaveMerge = true, solo = it.kind == "solo") }
        "assolo" -> { _ -> PostConfig(solo = true) }
        else -> { it -> PostConfig(solo = it.kind == "solo") }
    }
    // EVAL_FINGERING=TUTTUT fingers with the tuttut engine instead of the HMM one.
    System.getenv("EVAL_FINGERING")?.let { com.bouzouki.core.song.Fingering.engine = com.bouzouki.core.song.Fingering.Engine.valueOf(it) }
    // EVAL_DETAIL=fewer|normal|more: the line derived again from the saved data with that many notes (unset: as prepared).
    val detail = System.getenv("EVAL_DETAIL")?.takeIf { it.isNotBlank() }?.let { Detail.valueOf(it.uppercase(Locale.ROOT)) }
    // EVAL_SEPARATOR picks what the mixes go through (see withMixSeparator); EVAL_STEM_CACHE keeps what it gives.
    val spec = System.getenv("EVAL_SEPARATOR")
    val cache = System.getenv("EVAL_STEM_CACHE")?.takeIf { it.isNotBlank() }?.let(::File)
    val started = System.nanoTime()
    val results = AppPipeline.withBasicPitch { bp ->
        withMixSeparator(spec) { made ->
            val sep = if (made != null && cache != null) CachedSeparator(made, cache, spec ?: "") else made
            println("mix separator: ${made?.javaClass?.simpleName ?: "none"}" +
                ((made as? DemucsSeparator)?.let { " ${it.mixStems}, ${it.segment} samples a segment" } ?: "") +
                (if (sep is CachedSeparator) ", kept in $cache" else "") + (detail?.let { ", detail $it" } ?: ""))
            Evaluator.run(items, AppPipeline(bp, mixSeparator = sep, post = post, detail = detail), dump,
                band = { item, ref, seconds ->
                    // The line the band follows, when it isn't the bouzouki's (a mix with a sung verse).
                    val sung = File(item.audio.parentFile, item.name + ".sung.csv").takeIf { it.isFile }?.let { EvalSet.refNotes(it.readLines()) }
                    if (synthetic && item.kind == "mix") SynthSet.band(ref, seconds, sung) else null
                }) { println(it) }
                .also { if (sep is CachedSeparator) println("separated ${sep.made} recordings, ${sep.reused} taken from $cache") }
        }
    }
    val wall = (System.nanoTime() - started) / 1e9
    val md = Evaluator.markdown(label, results) + String.format(Locale.US,
        "\nRun: set %s, separator %s, detail %s, post %s; %d recordings, %.1f s of audio; %.1f s in all (models loaded, read, transcribed, scored).\n",
        setArg, spec ?: "none", detail ?: "as prepared", System.getenv("EVAL_POST") ?: "default", results.size, results.sumOf { it.seconds }, wall)
    File(out, "$label.md").writeText(md)
    File(out, "$label.csv").writeText(Evaluator.csv(label, results))
    results.mapNotNull { it.notes }.takeIf { it.isNotEmpty() }?.let { File(out, "$label.notes.csv").writeText(ErrorAnalysis.csv(it)) }
    println(md.lines().take(20).joinToString("\n"))
    println(Evaluator.timing(results).lines().take(8 + results.groupBy { it.item.kind }.size + results.groupBy { it.item.group }.size).joinToString("\n"))
    println(md.lines().last { it.startsWith("Run:") })
}

/**
 * Runs [block] with the separator [spec] names (EVAL_SEPARATOR); null for none:
 * - `none` (the default): the mix as it is;
 * - `light[:<centre depth>]`: [LightSeparator];
 * - `mdx:<onnx path>`: [MdxSeparator], the voice removed, as the app prepares a YouTube song (its session options too);
 * - `demucs:<onnx path>[:<stem>,<stem>...]`: [DemucsSeparator], by default its guitar + other;
 * - `mdx+demucs:<mdx onnx path>:<demucs onnx path>[:<stem>,...]`: the voice removed by MDX (in stereo), then
 *   Demucs's stems of what is left ([MdxThenDemucs]).
 */
fun <T> withMixSeparator(spec: String?, block: (Separator?) -> T): T {
    val env = OrtEnvironment.getEnvironment()
    val threads = Runtime.getRuntime().availableProcessors()
    // The app's session for the voice-removal model (SongJobs.prepare): 2 to 8 threads, every graph optimisation.
    fun mdxOptions() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads.coerceIn(2, 8))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    // "<path>[:guitar,other]": a trailing part names the stems; anything with a dot or slash is still the path.
    fun demucs(rest: String): Pair<String, List<String>?> {
        val cut = rest.lastIndexOf(':')
        val tail = if (cut > 0) rest.substring(cut + 1) else ""
        return if (cut > 0 && '.' !in tail && '/' !in tail)
            rest.substring(0, cut) to tail.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        else rest to null
    }
    return when {
        spec.isNullOrBlank() || spec == "none" -> block(null)
        spec == "light" -> block(LightSeparator())
        // "light:<depth>": the centre cancel at another depth (0 turns it off).
        spec.startsWith("light:") -> block(LightSeparator(centreDepth = spec.removePrefix("light:").toDouble()))
        spec.startsWith("mdx:") -> env.createSession(spec.removePrefix("mdx:"), mdxOptions()).use { block(MdxSeparator(env, it)) }
        spec.startsWith("demucs:") -> {
            val (path, stems) = demucs(spec.removePrefix("demucs:"))
            env.createSession(path, DemucsSeparator.sessionOptions(threads)).use { block(DemucsSeparator(env, it, stems)) }
        }
        spec.startsWith("mdx+demucs:") -> {
            val rest = spec.removePrefix("mdx+demucs:")
            val cut = rest.indexOf(':')
            require(cut > 0) { "EVAL_SEPARATOR=mdx+demucs:<mdx onnx>:<demucs onnx>[:stems], not $spec" }
            val (path, stems) = demucs(rest.substring(cut + 1))
            env.createSession(rest.substring(0, cut), mdxOptions()).use { mdx ->
                env.createSession(path, DemucsSeparator.sessionOptions(threads)).use { d ->
                    block(MdxThenDemucs(MdxSeparator(env, mdx), DemucsSeparator(env, d, stems)))
                }
            }
        }
        else -> error("EVAL_SEPARATOR must be none, light, mdx:<onnx>, demucs:<onnx>[:stems] or mdx+demucs:<mdx onnx>:<demucs onnx>[:stems], not $spec")
    }
}

/**
 * The voice removed by [mdx] (the app's vocal remover, kept in stereo: [MdxSeparator.instrumentalStereo]), then
 * [demucs]'s stems (by default guitar + other) of what is left: the two separators in a row.
 */
class MdxThenDemucs(private val mdx: MdxSeparator, private val demucs: DemucsSeparator) : Separator {
    override val keepsBass: Boolean get() = demucs.keepsBass

    override fun instrumentalMono(left: FloatArray, right: FloatArray, progress: (Float) -> Unit, cancelled: () -> Boolean): FloatArray {
        val (l, r) = mdx.instrumentalStereo(left, right, { progress(0.5f * it) }, cancelled)
        return demucs.instrumentalMono(l, r, { progress(0.5f + 0.5f * it) }, cancelled)
    }
}

/**
 * [inner] with what it gives kept in [dir], one file for each recording and [spec] (the recording is known by its
 * samples' hash, so a changed recording is separated again; a changed model under the same path is not: clear the
 * folder then). A run that only changes what comes after the separation then takes seconds instead of minutes.
 * The processing times of such a run don't count the separation: time the separators with the cache off or empty.
 */
class CachedSeparator(private val inner: Separator, private val dir: File, private val spec: String) : Separator {
    var made = 0; private set
    var reused = 0; private set
    override val keepsBass: Boolean get() = inner.keepsBass

    override fun instrumentalMono(left: FloatArray, right: FloatArray, progress: (Float) -> Unit, cancelled: () -> Boolean): FloatArray {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(spec.toByteArray())
        for (ch in listOf(left, right)) digest.update(ByteBuffer.allocate(4 * ch.size).order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(ch) })
        val file = File(dir, digest.digest().joinToString("") { "%02x".format(it) } + ".f32")
        if (file.isFile && file.length() == 4L * left.size) {
            reused++
            progress(1f)
            return FloatArray(left.size).also { ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        }
        val out = inner.instrumentalMono(left, right, progress, cancelled)
        dir.mkdirs()
        val part = File(dir, file.name + ".part")
        part.writeBytes(ByteBuffer.allocate(4 * out.size).order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(out) }.array())
        part.renameTo(file)
        made++
        return out
    }
}
