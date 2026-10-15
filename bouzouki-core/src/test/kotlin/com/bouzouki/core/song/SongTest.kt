package com.bouzouki.core.song

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bouzouki.core.BouzoukiSynth
import com.bouzouki.core.Dromos
import com.bouzouki.core.Instrument
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DspTest {
    private fun naiveDft(re: DoubleArray, im: DoubleArray, k: Int): Pair<Double, Double> {
        var sr = 0.0; var si = 0.0
        val n = re.size
        for (j in 0 until n) {
            val a = -2 * PI * k * j / n
            sr += re[j] * cos(a) - im[j] * sin(a)
            si += re[j] * sin(a) + im[j] * cos(a)
        }
        return sr to si
    }

    @Test
    fun fftMatchesTheDefinitionForPowerOfTwoAndThreeTimesPowerOfTwo() {
        val rnd = java.util.Random(3)
        for (n in listOf(8, 12, 2048, 6144)) {
            val re = DoubleArray(n) { rnd.nextGaussian() }
            val im = DoubleArray(n) { rnd.nextGaussian() }
            val r2 = re.copyOf(); val i2 = im.copyOf()
            Fft(n).forward(r2, i2)
            for (k in listOf(0, 1, 5, n / 3, n / 2, n - 1)) {
                val (er, ei) = naiveDft(re, im, k)
                assertTrue(abs(er - r2[k]) < 1e-6 * n && abs(ei - i2[k]) < 1e-6 * n, "n=$n k=$k")
            }
            Fft(n).inverse(r2, i2)
            for (j in 0 until n) assertTrue(abs(r2[j] - re[j]) < 1e-9 && abs(i2[j] - im[j]) < 1e-9)
        }
    }

    @Test
    fun resampleKeepsPitchAndLevel() {
        val tone = FloatArray(48000) { (0.5 * sin(2 * PI * 1000 * it / 48000)).toFloat() }
        val out = resample(tone, 48000, 44100)
        assertEquals(44100, out.size)
        // Compare against an ideal 1 kHz tone at the new rate, away from the edges.
        var err = 0.0
        for (i in 1000 until 43000) err = maxOf(err, abs(out[i] - 0.5 * sin(2 * PI * 1000 * i / 44100)))
        assertTrue(err < 0.01, "error $err")
        val down = resample(tone, 48000, 22050)
        var energy = 0.0
        for (i in 1000 until 21000) energy += down[i] * down[i]
        assertTrue(abs(sqrt(energy / 20000) - 0.5 / sqrt(2.0)) < 0.01)
    }
}

class BouzoukiLineTest {
    private val tetra = Instrument.TETRACHORDO

    /** A note as the transcriber reports it: [sal] note activation, [onset] onset activation. */
    private fun heard(start: Double, end: Double, midi: Int, sal: Double = 0.75, onset: Double = 0.9) =
        NoteEvent(start, end, midi, sal * 0.8, onset = onset, salience = sal)

    private fun monophonic(line: List<NoteEvent>) = line.zipWithNext().all { (a, b) -> b.start >= a.end - 1e-9 }

    @Test
    fun keepsTheMelodyOverChordsAndBass() {
        val notes = listOf(
            heard(0.0, 2.0, 50, 0.5, 0.75), heard(0.0, 2.0, 57, 0.5, 0.75), heard(0.01, 2.0, 62, 0.45, 0.72), // a strummed chord
            heard(0.1, 0.5, 69), heard(0.5, 0.9, 71), heard(0.9, 1.4, 72),
            heard(1.0, 1.2, 40, 0.9, 0.95), // bass, below the bouzouki
        )
        val line = SongAnalyzer.bouzoukiLine(notes, tetra)
        assertEquals(listOf(69, 71, 72), line.map { it.midi }, "line: $line")
        assertTrue(monophonic(line), "monophonic: $line")
    }

    @Test
    fun chordTonesInsideAHeldNoteAreNotMelody() {
        val notes = listOf(
            heard(0.0, 1.0, 69, 0.8), // held melody note
            heard(0.5, 1.0, 57, 0.45, 0.6), heard(0.505, 1.0, 60, 0.45, 0.6), heard(0.51, 1.0, 64, 0.45, 0.6), // strum
            heard(1.0, 1.5, 71), heard(1.5, 2.0, 72),
        )
        assertEquals(listOf(69, 71, 72), SongAnalyzer.bouzoukiLine(notes, tetra).map { it.midi })
    }

    @Test
    fun descendingRunOverRingingStringsIsKept() {
        // Each note keeps ringing under the next one (open strings, other courses): a skyline drops them.
        val run = listOf(76, 74, 72, 71, 69, 67, 65, 64)
        val notes = run.mapIndexed { i, m -> heard(i * 0.11, 1.5, m) }
        val line = SongAnalyzer.bouzoukiLine(notes, tetra)
        assertEquals(run, line.map { it.midi })
        assertTrue(monophonic(line))
    }

    @Test
    fun tremoloIsOneHeldNote() {
        // 12 picks a second on one pitch for a second, then the melody moves on.
        val picks = (0 until 12).map { heard(it / 12.0, it / 12.0 + 0.1, 69, 0.7, if (it % 2 == 0) 0.95 else 0.8) }
        val line = SongAnalyzer.bouzoukiLine(picks + heard(1.0, 1.4, 67) + heard(1.4, 1.8, 69) + heard(1.6, 2.0, 69), tetra)
        assertEquals(listOf(69, 67, 69, 69), line.map { it.midi }, "line: $line")
        assertEquals(0.0, line[0].start, 1e-9)
        assertEquals(1.0, line[0].end, 1e-9)
    }

    @Test
    fun repeatedNotesAreNotTremolo() {
        // 120 BPM: D D D D as 16ths, E F G as 8ths, A A A as 16ths, D; then 160 BPM 8ths G G G G F E E E D.
        val notes = ArrayList<NoteEvent>()
        var t = 0.0
        fun play(midi: Int, dur: Double) { notes += heard(t, t + dur, midi, if (notes.size % 2 == 0) 0.75 else 0.65); t += dur }
        repeat(4) { play(62, 0.125) }
        for (m in listOf(64, 65, 67)) play(m, 0.25)
        repeat(3) { play(69, 0.125) }
        play(62, 1.0)
        for (m in listOf(67, 67, 67, 67, 65, 64, 64, 64, 62)) play(m, 60.0 / 160 / 2)
        val line = SongAnalyzer.bouzoukiLine(notes, tetra)
        assertEquals(notes.map { it.midi }, line.map { it.midi }, "line: $line")
        assertEquals(notes.map { it.start }, line.map { it.start })
    }

    @Test
    fun aHarmonicAboveTheMelodyIsNotTaken() {
        val notes = listOf(
            heard(0.0, 0.25, 62), heard(0.25, 0.5, 64), heard(0.255, 0.45, 76, 0.55, 0.75), // 76: the octave above 64
            heard(0.5, 0.75, 65), heard(0.75, 1.0, 67),
        )
        assertEquals(listOf(62, 64, 65, 67), SongAnalyzer.bouzoukiLine(notes, tetra).map { it.midi })
    }

    @Test
    fun aLongPauseDoesNotCutTheLine() {
        val notes = listOf(heard(0.0, 0.3, 62), heard(0.3, 0.6, 64), heard(6.0, 6.3, 65), heard(6.3, 6.6, 67), heard(6.31, 6.5, 48, 0.4, 0.7))
        assertEquals(listOf(62, 64, 65, 67), SongAnalyzer.bouzoukiLine(notes, tetra).map { it.midi })
    }

    @Test
    fun aSavedLineIsKeptWhole() {
        // Re-fingering a saved song for the other bouzouki: notes without onset evidence all stay, leaps and repeats too.
        val saved = listOf(62, 86, 62, 62, 62, 50, 74).mapIndexed { i, m -> NoteEvent(i * 0.15, i * 0.15 + 0.15, m, 1.0) }
        assertEquals(saved.map { it.midi }, SongAnalyzer.bouzoukiLine(saved, tetra).map { it.midi })
        // Notes just 50 ms apart in the cache (rounded to ms, so the difference can come out a hair under 0.05).
        val close = listOf(1.0 to 62, 1.235 to 64, 1.285 to 65, 1.6 to 67).map { (s, m) -> NoteEvent(s, s + 0.3, m, 1.0) }
        val again = SongAnalyzer.bouzoukiLine(close, Instrument.TRICHORDO)
        assertEquals(close.map { it.start to it.midi }, again.map { it.start to it.midi })
        assertTrue(monophonic(again), "monophonic: $again")
    }

    @Test
    fun fingeringStaysInOnePlaceWhenItCan() {
        val melody = listOf(62, 64, 65, 67, 69, 67, 65, 64, 62) // D E F G A G F E D
        val cells = SongAnalyzer.fingering(melody, melody.indices.map { it * 0.3 }, tetra)
        cells.forEachIndexed { i, (c, f) -> assertEquals(melody[i], tetra.midiAt(c, f)) }
        val frets = cells.map { it.second }.filter { it > 0 }
        assertTrue(frets.max() - frets.min() <= 5, "one hand position: $cells")
    }

    @Test
    fun analysisSurvivesTheCache() {
        val a = SongAnalyzer.build("abc123def45", "Τραγούδι \"test\"", listOf(
            NoteEvent(0.5, 0.9, 62, 0.8), NoteEvent(1.0, 1.4, 63, 0.8), NoteEvent(1.5, 2.0, 66, 0.8),
            NoteEvent(2.0, 2.4, 67, 0.8), NoteEvent(2.5, 3.5, 62, 0.9),
        ), tetra)
        val back = assertNotNull(SongAnalysis.fromJson(a.toJson()))
        assertEquals(a.copy(notes = emptyList()), back.copy(notes = emptyList()))
        assertEquals(a.notes.map { it.midi to it.fret }, back.notes.map { it.midi to it.fret })
        assertEquals(1, back.noteAt(1.2))
        assertEquals(-1, back.noteAt(0.2))
        assertEquals(2, back.nextAfter(1.2))
    }

    @Test
    fun cacheFromAnOlderVersionIsAnalysedAgain() {
        val json = SongAnalyzer.build("x", "Σ x", listOf(NoteEvent(0.5, 0.9, 62, 0.8)), tetra).toJson()
        assertNotNull(SongAnalysis.fromJson(json))
        val old = json.replace("\"version\":${SongAnalysis.FORMAT}", "\"version\":1")
        assertEquals(null, SongAnalysis.fromJson(old))
        // The song list still shows it, to be prepared again.
        assertEquals(SongAnalysis.Saved("x", "Σ x", current = false), SongAnalysis.saved(old))
        assertEquals(SongAnalysis.Saved("x", "Σ x", current = true), SongAnalysis.saved(json))
        assertEquals(null, SongAnalysis.saved(json.take(20)))
        // Fields this version doesn't know about (added later) are ignored.
        assertNotNull(SongAnalysis.fromJson(json.dropLast(1) + ",\"later\":[1,2,{\"a\":null}]}"))
    }
}

/** Runs the real Basic Pitch model (bundled with the app) on notes from our own bouzouki synth. */
class BasicPitchTest {
    private val model = File("../bouzouki/src/main/assets/basic_pitch.onnx")

    /** A pluck: when, which course, which note. */
    private data class Pluck(val time: Double, val course: Int, val midi: Int, val velocity: Float = 0.9f)

    /** Renders [plucks] with the app's bouzouki synth at Basic Pitch's rate, each pluck on its exact sample. */
    private fun render(plucks: List<Pluck>, seconds: Double): FloatArray {
        val fs = BasicPitch.SAMPLE_RATE
        val synth = BouzoukiSynth(fs)
        val audio = FloatArray((seconds * fs).toInt())
        var pos = 0
        for (p in plucks.sortedBy { it.time }) {
            val at = (p.time * fs).toInt()
            synth.render(audio, pos, at - pos)
            pos = at
            synth.pluck(p.course, p.midi, Instrument.TETRACHORDO.courses[p.course].octavePair, p.velocity)
        }
        synth.render(audio, pos, audio.size - pos)
        return audio
    }

    private fun line(audio: FloatArray): List<NoteEvent> {
        val env = OrtEnvironment.getEnvironment()
        return env.createSession(model.path, OrtSession.SessionOptions()).use { session ->
            val act = BasicPitch(env, session).activations(audio)
            SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(act), Instrument.TETRACHORDO, act)
        }
    }

    @Test
    fun transcribesBouzoukiNotes() {
        // The first pluck is on the very first sample (its onset peak is on the first frame).
        val melody = listOf(62, 63, 66, 67, 69, 70, 72, 74) // D Hitzaz up
        val line = line(render(melody.mapIndexed { i, m -> Pluck(i * 0.5, 3, m) }, (melody.size + 1) / 2.0))
        assertEquals(melody, line.map { it.midi }, "line: $line")
        // Starts are refined between model frames and corrected for the model's latency: within a few ms.
        line.forEachIndexed { i, n -> assertTrue(abs(n.start - i * 0.5) < 0.012, "note $i starts at ${n.start}") }
    }

    @Test
    fun repeatedNotesStaySeparate() {
        // 120 BPM: D D D D as 16ths, E F G as 8ths, A A A as 16ths, a held F; then 160 BPM 8ths G G G G F E E E D.
        val plucks = ArrayList<Pluck>()
        var t = 0.3
        fun play(midi: Int, dur: Double) { plucks += Pluck(t, 3, midi, if (plucks.size % 2 == 0) 0.9f else 0.75f); t += dur }
        repeat(4) { play(62, 0.125) }
        for (m in listOf(64, 65, 67)) play(m, 0.25)
        repeat(3) { play(69, 0.125) }
        play(65, 1.0)
        for (m in listOf(67, 67, 67, 67, 65, 64, 64, 64, 62)) play(m, 60.0 / 160 / 2)
        val line = line(render(plucks, t + 0.6))
        assertEquals(plucks.map { it.midi }, line.map { it.midi }, "line: $line")
        val errors = line.mapIndexed { i, n -> n.start - plucks[i].time }
        assertTrue(errors.all { abs(it) < 0.015 }, "onset errors $errors")
    }

    @Test
    fun fastRunTremoloAndSlowNotesKeepTheirTiming() {
        // 140 BPM: a 16th run (107 ms a note) down the top courses, a tremolo held for two beats, then 8ths.
        val beat = 60.0 / 140
        val plucks = ArrayList<Pluck>()
        val expected = ArrayList<Pair<Double, Int>>()
        val run = listOf(74, 72, 70, 69, 67, 66, 63, 62)
        run.forEachIndexed { i, m ->
            val t = 0.3 + i * beat / 4
            plucks += Pluck(t, if (m >= 62) 3 else 2, m)
            expected += t to m
        }
        val tremStart = 0.3 + 2 * beat
        for (k in 0 until 10) plucks += Pluck(tremStart + k / 12.5, 2, 60, if (k % 2 == 0) 0.85f else 0.65f)
        expected += tremStart to 60
        listOf(62, 63, 66, 67).forEachIndexed { i, m ->
            val t = tremStart + 2 * beat + i * beat / 2
            plucks += Pluck(t, 3, m)
            expected += t to m
        }
        val line = line(render(plucks, tremStart + 4 * beat + 0.6))
        assertEquals(expected.map { it.second }, line.map { it.midi }, "line: $line")
        val errors = line.mapIndexed { i, n -> n.start - expected[i].first }
        assertTrue(errors.all { abs(it) < 0.015 }, "onset errors $errors")
        assertTrue(errors.map { abs(it) }.sorted()[errors.size / 2] < 0.005, "median onset error, errors $errors")
        assertTrue(line[run.size].end > tremStart + 2 * beat - 0.1, "the tremolo is held: ${line[run.size]}")
    }

    @Test
    fun frameTimesFollowTheWindows() {
        // Output frame 142 is the first kept frame of the second window, which starts 36164 samples in.
        assertEquals(0.0, BasicPitch.frameTime(0), 1e-12)
        assertEquals(141 * 256.0 / 22050, BasicPitch.frameTime(141), 1e-12)
        assertEquals(36164.0 / 22050, BasicPitch.frameTime(142), 1e-12)
    }
}

/**
 * Checks the Kotlin vocal removal and transcription against the Python reference
 * (scratch/ml/ref.py and basic_pitch.predict). Needs the 67 MB model and reference files,
 * so it only runs where BOUZOUKI_ML_DIR points at them.
 */
class ReferenceTest {
    private val dir = System.getenv("BOUZOUKI_ML_DIR")?.let { File(it) }

    @Test
    fun matchesThePythonReference() {
        val d = dir ?: return
        val mix = readWav(File(d, "example.wav"))
        val ref = readWav(File(d, "inst.wav"))
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors()) }
        env.createSession(File(d, "mdx_inst.onnx").path, opts).use { mdx ->
            val t0 = System.nanoTime()
            val inst = MdxSeparator(env, mdx).instrumentalMono(mix[0], mix[1])
            println("separation took %.1f s for %.1f s of audio".format((System.nanoTime() - t0) / 1e9, mix[0].size / 44100.0))
            var err = 0.0; var refEnergy = 0.0
            for (i in inst.indices) {
                val r = (ref[0][i] + ref[1][i]) / 2
                err += (inst[i] - r) * (inst[i] - r); refEnergy += r * r
            }
            val snr = 10 * kotlin.math.log10(refEnergy / err)
            println("instrumental vs python: SNR %.1f dB".format(snr))
            assertTrue(snr > 40, "SNR $snr")

            env.createSession(File(d, "nmp.onnx").path, OrtSession.SessionOptions()).use { bpSession ->
                val act = BasicPitch(env, bpSession).activations(resample(inst, 44100, 22050))
                val notes = BasicPitch.notes(act) // basic_pitch's own settings, to compare with its output
                println("kotlin: ${notes.size} notes; first: ${notes.take(5)}")
                val pyRef = File(d, "inst_notes.txt").takeIf { it.exists() }?.readLines()?.map { l ->
                    val (s, e, m) = l.split(" "); Triple(s.toDouble(), e.toDouble(), m.toInt())
                }
                if (pyRef != null) {
                    println("python: ${pyRef.size} notes")
                    // Same note at (about) the same time, for most notes.
                    val matched = pyRef.count { (s, _, m) -> notes.any { it.midi == m && abs(it.start - s) < 0.05 } }
                    println("matched $matched of ${pyRef.size}")
                    assertTrue(matched >= pyRef.size * 0.85, "matched $matched of ${pyRef.size}")
                }
                val a = SongAnalyzer.build("x", "x", SongAnalyzer.candidates(act), Instrument.TETRACHORDO, act)
                println("bouzouki line: ${a.notes.size} notes, dromos guess ${a.dromos} on ${a.rootPc}")
            }
        }
        assertTrue(Dromos.entries.isNotEmpty())
    }
}

/**
 * The bouzouki line on the synthesized evaluation songs (written by the scratch evaluation's
 * export_kotlin.py: activations, the Python model's line, ground truth and the mix of each song).
 * Given the same activations the Kotlin line must be the Python one; with EVAL_FULL and
 * BOUZOUKI_ML_DIR set, the whole analysis (vocal removal included) runs on every mix and is scored.
 * Only runs where EVAL_DIR points at the exported songs.
 */
class EvalTest {
    private val dir = System.getenv("EVAL_DIR")?.let { File(it) }

    private fun songs(d: File) = d.listFiles()!!.filter { File(it, "gt.csv").exists() }.sortedBy { it.name }

    private fun floats(f: File, cols: Int): Array<FloatArray> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return Array(b.remaining() / cols) { FloatArray(cols).also { row -> b.get(row) } }
    }

    /** (onset, midi) of each row of a start,end,midi[,kind] file. */
    private fun csv(f: File) = f.readLines().filter { it.isNotBlank() }.map { it.split(",") }.map { it[0].toDouble() to it[2].toInt() }

    /** One-to-one matching (same pitch, onsets within [tol], closest pairs first); the onset errors of the pairs. */
    private fun match(ref: List<Pair<Double, Int>>, est: List<Pair<Double, Int>>, tol: Double = 0.05): List<Double> {
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        for ((i, r) in ref.withIndex()) for ((j, e) in est.withIndex()) {
            if (r.second == e.second && abs(e.first - r.first) <= tol) pairs += Triple(i, j, e.first - r.first)
        }
        val usedR = HashSet<Int>(); val usedE = HashSet<Int>(); val errors = ArrayList<Double>()
        for ((i, j, d) in pairs.sortedBy { abs(it.third) }) {
            if (i !in usedR && j !in usedE) { usedR += i; usedE += j; errors += d }
        }
        return errors
    }

    private class Score {
        var tp = 0; var est = 0; var ref = 0; val errors = ArrayList<Double>()
        fun add(e: List<Double>, nEst: Int, nRef: Int) { tp += e.size; est += nEst; ref += nRef; errors += e }
        val f get() = 2.0 * tp / (est + ref)
        override fun toString(): String {
            val ms = errors.map { it * 1000 }
            val mean = ms.average()
            val std = sqrt(ms.sumOf { (it - mean) * (it - mean) } / ms.size)
            return "P %.3f R %.3f F %.3f | onset error mean %+.1f ms, |mean| %.1f ms, std %.1f ms".format(
                tp.toDouble() / est, tp.toDouble() / ref, f, mean, ms.map { abs(it) }.average(), std)
        }
    }

    @Test
    fun kotlinLineIsThePythonLine() {
        val d = dir ?: return
        val total = Score()
        for (song in songs(d)) {
            val act = BasicPitch.Activations(floats(File(song, "frames.f32"), 88), floats(File(song, "onsets.f32"), 88))
            val line = SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(act), Instrument.TETRACHORDO, act).map { it.start to it.midi }
            val python = csv(File(song, "line.csv"))
            val same = match(python, line, 0.001).size
            val gt = csv(File(song, "gt.csv"))
            val s = Score().apply { add(match(gt, line), line.size, gt.size) }
            total.add(match(gt, line), line.size, gt.size)
            println("%-28s same as python %d/%d (kotlin %d) | %s".format(song.name, same, python.size, line.size, s))
            assertTrue(same >= python.size * 0.98 && same >= line.size * 0.98, "${song.name}: $same of ${python.size}/${line.size}")
        }
        println("all songs: $total")
    }

    @Test
    fun wholeAnalysisOnTheMixes() {
        val d = dir ?: return
        val ml = System.getenv("BOUZOUKI_ML_DIR")?.let { File(it) } ?: return
        if (System.getenv("EVAL_FULL") == null) return
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors()) }
        val total = Score()
        env.createSession(File(ml, "mdx_inst.onnx").path, opts).use { mdx ->
            env.createSession(model().path, OrtSession.SessionOptions()).use { bp ->
                val analyzer = SongAnalyzer(MdxSeparator(env, mdx), BasicPitch(env, bp))
                for (song in songs(d)) {
                    val mix = readWav(File(song, "mix.wav"))
                    val t0 = System.nanoTime()
                    val a = analyzer.analyze(song.name, song.name, mix[0], mix[1], Instrument.TETRACHORDO)
                    val line = a.notes.map { it.start to it.midi }
                    val gt = csv(File(song, "gt.csv"))
                    val errors = match(gt, line)
                    total.add(errors, line.size, gt.size)
                    println("%-28s %.0f s | %s".format(song.name, (System.nanoTime() - t0) / 1e9, Score().apply { add(errors, line.size, gt.size) }))
                }
            }
        }
        println("all songs, whole analysis: $total")
        assertTrue(total.f > 0.65, "F ${total.f}")
    }

    private fun model() = File("../bouzouki/src/main/assets/basic_pitch.onnx")
}

/** Reads a 16-bit, 32-bit float or 64-bit float WAV file into one array per channel. */
internal fun readWav(f: File): Array<FloatArray> {
    val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    var p = 12
    var channels = 2; var bits = 16; var fmt = 1
    while (p < b.limit()) {
        val id = String(ByteArray(4) { b.get(p + it) }); val size = b.getInt(p + 4)
        if (id == "fmt ") { fmt = b.getShort(p + 8).toInt(); channels = b.getShort(p + 10).toInt(); bits = b.getShort(p + 22).toInt() }
        if (id == "data") {
            val frames = size / (channels * bits / 8)
            return Array(channels) { c -> FloatArray(frames) { i ->
                val o = p + 8 + (i * channels + c) * bits / 8
                if (fmt == 3 && bits == 32) b.getFloat(o) else if (bits == 64) b.getDouble(o).toFloat() else b.getShort(o) / 32768f
            } }
        }
        p += 8 + size + (size and 1)
    }
    error("no data")
}
