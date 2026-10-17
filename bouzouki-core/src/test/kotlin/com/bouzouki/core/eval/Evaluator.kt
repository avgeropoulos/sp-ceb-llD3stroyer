package com.bouzouki.core.eval

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bouzouki.core.song.BasicPitch
import com.bouzouki.core.song.NoSeparation
import com.bouzouki.core.song.Separator
import com.bouzouki.core.song.SongAnalyzer
import com.bouzouki.core.song.SongNote
import com.bouzouki.core.song.resample
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** Seconds each stage of the transcription took, by name, in order. */
class Timings : LinkedHashMap<String, Double>() {
    val total get() = values.sum()
}

/** A recording transcribed: its placed notes, and how far it was found to be tuned from A = 440 Hz (cents). */
class Transcript(val notes: List<SongNote>, val cents: Double = Double.NaN)

/** A way to transcribe a recording into placed notes, and how long each of its stages took. */
fun interface Pipeline {
    fun transcribe(item: EvalItem, left: FloatArray, right: FloatArray, timings: Timings): Transcript
}

/**
 * The app's pipeline ([SongAnalyzer]): a solo recording straight to the note model, a mix through [mixSeparator]
 * first (none given: the mix as it is). Timed by the analyzer's stages.
 */
class AppPipeline(private val pitch: BasicPitch, private val mixSeparator: Separator? = null) : Pipeline {
    override fun transcribe(item: EvalItem, left: FloatArray, right: FloatArray, timings: Timings): Transcript {
        val separator = if (item.kind == "mix") mixSeparator ?: NoSeparation else NoSeparation
        var stage: String? = null
        var since = System.nanoTime()
        fun lap(next: String?) {
            val now = System.nanoTime()
            stage?.let { timings[it] = (timings[it] ?: 0.0) + (now - since) / 1e9 }
            stage = next; since = now
        }
        val (song, _) = SongAnalyzer(separator, pitch).analyzeWithData(
            item.name, item.name, left, right, item.instrument,
            progress = { s, _ -> val name = s.name.lowercase(Locale.ROOT); if (name != stage) lap(name) },
        )
        lap(null)
        return Transcript(song.notes, song.tuningCents)
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
class ItemResult(val item: EvalItem, val score: Score, val timings: Timings, val seconds: Double, val offset: Double, val cents: Double)

/** Runs a [Pipeline] over an evaluation set and reports how it did, overall, by group and kind, and per recording. */
object Evaluator {
    /** With [dump], each recording's notes missed and found wrongly are printed too. */
    fun run(items: List<EvalItem>, pipeline: Pipeline, dump: Boolean = false, log: (String) -> Unit = {}): List<ItemResult> = items.map { item ->
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
        if (dump) {
            val pairs = Metrics.match(ref, est) { r, e -> r.midi == e.midi }
            val hitR = pairs.map { it.first }.toSet(); val hitE = pairs.map { it.second }.toSet()
            for ((i, r) in ref.withIndex()) if (i !in hitR) log(String.format(Locale.US, "    missed %7.3f midi %d (course %d fret %d %s)", r.start, r.midi, r.course, r.fret, r.articulation))
            for ((j, e) in est.withIndex()) if (j !in hitE) log(String.format(Locale.US, "    extra  %7.3f midi %d (course %d fret %d %s)", e.start, e.midi, e.course, e.fret, e.articulation))
        }
        val seconds = left.size.toDouble() / Separator.SAMPLE_RATE
        val tuning = if (item.cents != null) String.format(Locale.US, " · tuning %+.1f¢ (true %+.0f¢)", transcript.cents, item.cents) else ""
        log(String.format(Locale.US, "%-28s %s · %d a semitone off%s · %.2f s for %.1f s", item.name, score.summary(),
            score.tpSemitone - score.tp, tuning, timings.total, seconds))
        ItemResult(item, score, timings, seconds, offset, transcript.cents)
    }

    /** The report as Markdown: the totals (and by kind and group), then each recording. */
    fun markdown(label: String, results: List<ItemResult>): String = buildString {
        fun total(rs: List<ItemResult>) = Score().also { s -> rs.forEach { s.add(it.score) } }
        fun rtf(rs: List<ItemResult>) = rs.sumOf { it.timings.total } / rs.sumOf { it.seconds }.coerceAtLeast(1e-9)
        fun row(name: String, rs: List<ItemResult>) {
            val s = total(rs)
            append(String.format(Locale.US, "| %s | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %s | %s | %s | %.3f |\n", name, s.ref,
                s.note, s.precision, s.recall, s.octave, s.offset, Score.pct(s.course), Score.pct(s.fret),
                if (s.refTremolo == 0) "–" else "${s.tremoloRight}/${s.refTremolo} (${s.estTremolo} marked)", rtf(rs)))
        }
        append("# Evaluation: $label\n\n")
        append("Note F-measure: same pitch, onsets within ${(Metrics.ONSET_TOL * 1000).toInt()} ms. Octave-F: same pitch class. ")
        append("Offset-F: the end too (within 20% of the note, at least 50 ms). Course/fret: of the notes found, placed as played. ")
        append("RTF: processing seconds per second of audio (this machine, ${Runtime.getRuntime().availableProcessors()} cores).\n\n")
        val head = "| | notes | F | P | R | octave-F | offset-F | course | fret | tremolo | RTF |\n|---|---|---|---|---|---|---|---|---|---|---|\n"
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
        append("\n## Time by stage\n\n| stage | seconds | per audio second |\n|---|---|---|\n")
        val audio = results.sumOf { it.seconds }
        val stages = LinkedHashMap<String, Double>()
        for (r in results) for ((k, v) in r.timings) stages[k] = (stages[k] ?: 0.0) + v
        for ((k, v) in stages) append(String.format(Locale.US, "| %s | %.2f | %.4f |\n", k, v, v / audio))
        append(String.format(Locale.US, "| **total** | %.2f | %.4f |\n", stages.values.sum(), stages.values.sum() / audio))
        append("\n## Recordings\n\n").append(head)
        for (r in results) row(r.item.name, listOf(r))
    }

    /** One CSV line per recording (and "all"), to compare runs. */
    fun csv(label: String, results: List<ItemResult>): String = buildString {
        append("label,item,kind,group,instrument,ref,est,tp,tp_octave,tp_offset,tp_semitone,placed,same_course,same_fret,ref_tremolo,est_tremolo,tremolo_right,cents,true_cents,seconds,processing\n")
        for (r in results) {
            val s = r.score
            append(String.format(Locale.US, "%s,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%s,%.3f,%.3f\n", label, r.item.name, r.item.kind, r.item.group,
                r.item.instrument.name, s.ref, s.est, s.tp, s.tpOctave, s.tpOffset, s.tpSemitone, s.placed, s.sameCourse, s.sameFret,
                s.refTremolo, s.estTremolo, s.tremoloRight, r.cents, r.item.cents?.toString() ?: "", r.seconds, r.timings.total))
        }
    }
}

/**
 * Developer tool: scores the app's transcription on an evaluation set (see [EvalSet]).
 * `gradle :bouzouki-core:evalSet -PevalSet=<dir> -PevalOut=<dir> -PevalLabel=<name>`; with `-PevalSet=synth` it makes
 * the synthetic set ([SynthSet]) into build/eval/synth first. Writes <label>.md and <label>.csv. `-PevalOnly=<regex>`
 * runs only the recordings whose names match, and `-PevalDump=true` prints the notes missed and the extra ones.
 */
fun main(args: Array<String>) {
    val setArg = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "synth"
    val out = File(args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "build/eval").apply { mkdirs() }
    val label = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "run"
    val dir = if (setArg == "synth") File("build/eval/synth").also { if (!File(it, "open-tetra-courses.wav").exists()) SynthSet.write(it) } else File(setArg)
    val only = args.getOrNull(3)?.takeIf { it.isNotBlank() }?.let(::Regex)
    val dump = args.getOrNull(4) == "true"
    val items = EvalSet.load(dir).filter { only == null || only.containsMatchIn(it.name) }
    require(items.isNotEmpty()) { "no recordings with a reference in $dir" }
    val results = AppPipeline.withBasicPitch { bp -> Evaluator.run(items, AppPipeline(bp), dump) { println(it) } }
    File(out, "$label.md").writeText(Evaluator.markdown(label, results))
    File(out, "$label.csv").writeText(Evaluator.csv(label, results))
    println(Evaluator.markdown(label, results).lines().take(20).joinToString("\n"))
}
