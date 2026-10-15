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
import kotlin.math.roundToInt
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
        // 120 BPM: D D D D as 16ths, E F G as 8ths, A A A as 16ths, D held (a fifth down, then a fourth up: the line
        // keeps a leap out of and back into a held note); then 160 BPM 8ths G G G G F E E E D.
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
    fun aSharpPickCountsMoreAndASoftAttackNoLess() {
        val n = 300
        val act = BasicPitch.Activations(Array(n) { FloatArray(BasicPitch.N_KEYS) { 0.1f } }, Array(n) { FloatArray(BasicPitch.N_KEYS) { 0.05f } })
        fun note(midi: Int, from: Int, to: Int, salience: Double, onset: Double) =
            NoteEvent(BasicPitch.frameTime(from), BasicPitch.frameTime(to), midi, 0.5, onset = onset, salience = salience, frame = from, endFrame = to)
        // The line of these notes where the plectrum strikes the notes [picked] (indices) and nothing else rises.
        fun line(notes: List<NoteEvent>, picked: List<Int>): List<Int> {
            val rises = FloatArray(1300) { 1f }
            for (k in picked) {
                val i = ((notes[k].start * Attacks.SAMPLE_RATE - Attacks.FFT / 2) / Attacks.HOP).roundToInt()
                for (j in i - 1..i + 1) rises[j] = 100f
            }
            return SongAnalyzer.bouzoukiLine(notes, tetra, act, Attacks(rises, FloatArray(rises.size))).map { it.midi }
        }
        val clear = note(62, 20, 60, 0.6, 0.8)
        val faint = note(64, 150, 200, 0.3, 0.2) // a little short of the evidence a note needs
        assertEquals(listOf(62), line(listOf(clear, faint), picked = listOf(0)))
        // A sharp pick makes up for it.
        assertEquals(listOf(62, 64), line(listOf(clear, faint), picked = listOf(0, 1)))
        // A clear note without a pick (a hammer-on, a slide, or a pick the recording blurred) loses nothing for that.
        assertEquals(listOf(62, 64), line(listOf(clear, note(64, 150, 200, 0.6, 0.8)), picked = listOf(0)))
    }

    @Test
    fun anOnsetPeakWithAFlatTopIsOneNote() {
        // Activations saved in 1/255 steps tie at a peak; the note must not be lost.
        val n = 40
        val frames = Array(n) { FloatArray(BasicPitch.N_KEYS) }
        val onsets = Array(n) { FloatArray(BasicPitch.N_KEYS) }
        for (t in 10 until 30) frames[t][41] = 0.7f
        onsets[9][41] = 0.2f; onsets[10][41] = 0.8f; onsets[11][41] = 0.8f; onsets[12][41] = 0.3f
        frames[30][60] = 1f; frames[31][60] = 1f // a frame jump elsewhere, too short for a note, sets the inferred onsets' scale
        val notes = BasicPitch.notes(BasicPitch.Activations(frames, onsets), onsetThresh = 0.5f, minNoteLen = 2, melodia = false)
        assertEquals(listOf(62 to 10), notes.map { it.midi to it.frame })
    }

    @Test
    fun damagedAnalysisDataReadsAsNothing() {
        // A file as AnalysisData writes it: [n] frames said, the columns of [frames] frames there, [m] attack frames said.
        fun file(n: Int, m: Int, frames: Int = n): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(java.util.zip.DeflaterOutputStream(out)).use { d ->
                d.writeInt(0x425A4144) // "BZAD"
                d.writeInt(AnalysisData.VERSION)
                d.writeInt(n)
                d.writeInt(BasicPitch.N_KEYS)
                d.write(ByteArray(2 * BasicPitch.N_KEYS * frames))
                d.writeBoolean(false) // no contours
                d.writeBoolean(true)
                d.writeInt(m)
                repeat(2 * minOf(m, 64)) { d.writeFloat(0f) }
            }
            return out.toByteArray()
        }
        assertNotNull(AnalysisData.read(file(10, 40).inputStream()))
        // Lengths a damaged file may say are refused before anything is allocated for them (or memory would run out):
        // an attack track longer than the frames allow, more frames than a song has, more than the file holds.
        assertEquals(null, AnalysisData.read(file(10, Int.MAX_VALUE - 8).inputStream()))
        assertEquals(null, AnalysisData.read(file(0, Int.MAX_VALUE - 8).inputStream()))
        assertEquals(null, AnalysisData.read(file(900_000, 0, frames = 0).inputStream()))
        assertEquals(null, AnalysisData.read(file(150_000, 0, frames = 10).inputStream()))
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
        ), tetra).copy(beats = listOf(0.512, 1.04, 1.6))
        val back = assertNotNull(SongAnalysis.fromJson(a.toJson()))
        assertEquals(listOf(0.512, 1.04, 1.6), back.beats)
        // A cache file without beats still reads, with none.
        val noBeats = a.toJson().replace(",\"beats\":[0.512,1.040,1.600]", "")
        assertEquals(emptyList(), assertNotNull(SongAnalysis.fromJson(noBeats)).beats)
        assertEquals(a.copy(notes = emptyList()), back.copy(notes = emptyList()))
        assertEquals(a.notes.map { it.midi to it.fret }, back.notes.map { it.midi to it.fret })
        assertEquals(1, back.noteAt(1.2))
        assertEquals(-1, back.noteAt(0.2))
        assertEquals(2, back.nextAfter(1.2))
    }

    @Test
    fun articulationsSurviveTheCacheAndTheOtherBouzouki() {
        val a = SongAnalysis("id", "t", tetra, listOf(
            SongNote(0.5, 0.9, 48, 0, 0), // C3: below the trichordo's lowest D3
            SongNote(1.0, 1.4, 62, 3, 0),
            SongNote(1.4, 1.8, 64, 3, 2, Articulation.HAMMER),
            SongNote(1.8, 2.6, 62, 3, 0, Articulation.PULL),
            SongNote(3.0, 4.0, 69, 3, 7, Articulation.TREMOLO),
        ), Dromos.MATZORE, 2)
        val back = assertNotNull(SongAnalysis.fromJson(a.toJson()))
        assertEquals(a.notes.map { it.articulation }, back.notes.map { it.articulation })
        // A note written before articulations were stored reads as picked.
        val old = a.toJson().replace(",0]", "]")
        assertEquals(Articulation.PICK, assertNotNull(SongAnalysis.fromJson(old)).notes[0].articulation)

        val tri = a.forInstrument(Instrument.TRICHORDO)
        assertEquals(Instrument.TRICHORDO, tri.instrument)
        assertEquals(60, tri.notes[0].midi) // moved up an octave into range
        assertEquals(a.notes.map { it.articulation }, tri.notes.map { it.articulation })
        tri.notes.forEach { n -> assertEquals(n.midi, Instrument.TRICHORDO.midiAt(n.course, n.fret)) }
    }

    @Test
    fun cacheFromAnOlderVersionIsAnalysedAgain() {
        val json = SongAnalyzer.build("x", "Σ x", listOf(NoteEvent(0.5, 0.9, 62, 0.8)), tetra).toJson()
        assertNotNull(SongAnalysis.fromJson(json))
        val old = json.replace("\"version\":${SongAnalysis.FORMAT}", "\"version\":1")
        assertEquals(null, SongAnalysis.fromJson(old))
        // Version 3 (before the line weighed the plectrum's attacks, and without saved analysis data) is prepared again too.
        assertEquals(null, SongAnalysis.fromJson(json.replace("\"version\":${SongAnalysis.FORMAT}", "\"version\":3")))
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

    /**
     * Renders [plucks] with the app's bouzouki synth at Basic Pitch's rate, each pluck on its exact sample, over a
     * faint hiss (about -55 dB) as any recording has, so the attack measure sees a pick against something.
     */
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
        val hiss = java.util.Random(7)
        for (i in audio.indices) audio[i] += 0.002f * hiss.nextGaussian().toFloat()
        return audio
    }

    private fun line(audio: FloatArray): List<NoteEvent> {
        val env = OrtEnvironment.getEnvironment()
        return env.createSession(model.path, OrtSession.SessionOptions()).use { session ->
            // As the analysis does it: the activations as saved, and where the plectrum strikes.
            val data = AnalysisData.of(BasicPitch(env, session).activations(audio), Attacks.of(audio))
            val line = SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(data.act), Instrument.TETRACHORDO, data.act, data.attacks)
            // Every note here is picked: nothing is taken for legato.
            assertTrue(line.none { it.articulation.isLegato }, "line: $line")
            line
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
    fun savedAnalysisDataGivesTheSameLineAgain() {
        val melody = listOf(62, 63, 66, 67, 69, 70, 72, 74)
        val audio = render(melody.mapIndexed { i, m -> Pluck(0.25 + i * 0.5, 3, m) }, (melody.size + 1) / 2.0 + 0.25)
        val env = OrtEnvironment.getEnvironment()
        env.createSession(model.path, OrtSession.SessionOptions()).use { session ->
            val act = BasicPitch(env, session).activations(audio)
            // As analyzeWithData does: the line comes from the activations as they are saved.
            val data = AnalysisData.of(act, Attacks.of(audio))
            val first = SongAnalyzer.build("id", "t", SongAnalyzer.candidates(data.act), Instrument.TETRACHORDO, data.act, data.attacks)
            assertEquals(melody, first.notes.map { it.midi })
            val bytes = java.io.ByteArrayOutputStream().also { data.write(it) }.toByteArray()
            val back = assertNotNull(AnalysisData.read(bytes.inputStream()))
            assertEquals(act.frames.size, back.act.frames.size)
            for (t in act.frames.indices) {
                assertTrue(data.act.frames[t].contentEquals(back.act.frames[t]) && data.act.onsets[t].contentEquals(back.act.onsets[t]))
                assertTrue(act.contours!![t].contentEquals(back.act.contours!![t]))
            }
            // The saved data gives the very same line again.
            assertEquals(first.notes, SongAnalyzer.rebuild(first, back).notes)
            // More detail never loses the notes the normal line has, fewer never adds any (EvalTest checks real songs).
            assertTrue(keeps(SongAnalyzer.rebuild(first, back, Detail.MORE).notes, first.notes))
            assertTrue(keeps(first.notes, SongAnalyzer.rebuild(first, back, Detail.FEWER).notes))
            assertEquals(null, AnalysisData.read(bytes.copyOf(40).inputStream()))
        }
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
        assertEquals(Articulation.TREMOLO, line[run.size].articulation)
        assertTrue(line.filterIndexed { i, _ -> i != run.size }.all { it.articulation == Articulation.PICK })
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
 * export_kotlin.py into kotlin_legato/: activations and pitch contours, the instrumental Basic Pitch
 * heard, the Python model's line with its articulations, the ground truth and the mix of each song).
 * Given the same activations and audio, the Kotlin line must be the Python one, articulations included
 * (an older export without contours and audio, like kotlin/, compares the line without attacks); with
 * EVAL_FULL and BOUZOUKI_ML_DIR set, the whole analysis (vocal removal included) runs on every mix and is
 * scored. Only runs where EVAL_DIR points at the exported songs.
 */
class EvalTest {
    private val dir = System.getenv("EVAL_DIR")?.let { File(it) }

    private fun songs(d: File) = d.listFiles()!!.filter { File(it, "gt.csv").exists() }.sortedBy { it.name }

    private fun floats(f: File, cols: Int): Array<FloatArray> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return Array(b.remaining() / cols) { FloatArray(cols).also { row -> b.get(row) } }
    }

    private fun samples(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private fun bytes(f: File, cols: Int): Array<ByteArray> {
        val all = f.readBytes()
        return Array(all.size / cols) { all.copyOfRange(it * cols, (it + 1) * cols) }
    }

    /** A note of a start,end,midi[,kind][,articulation] file (no articulation: as the synth's kind says, else picked). */
    private class Row(val start: Double, val midi: Int, val articulation: Articulation)

    private val kinds = mapOf("hammer" to Articulation.HAMMER, "pull" to Articulation.PULL, "slideB" to Articulation.SLIDE, "tremolo" to Articulation.TREMOLO)

    private fun rows(f: File) = f.readLines().filter { it.isNotBlank() }.map { it.split(",") }.map { c ->
        Row(c[0].toDouble(), c[2].toInt(), Articulation.entries.firstOrNull { it.name == c.last() } ?: kinds[c.last()] ?: Articulation.PICK)
    }

    private fun rows(notes: List<NoteEvent>) = notes.map { Row(it.start, it.midi, it.articulation) }

    /** One-to-one matching (same pitch, onsets within [tol], closest pairs first): (ref index, est index, onset error). */
    private fun match(ref: List<Row>, est: List<Row>, tol: Double = 0.05): List<Triple<Int, Int, Double>> {
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        for ((i, r) in ref.withIndex()) for ((j, e) in est.withIndex()) {
            if (r.midi == e.midi && abs(e.start - r.start) <= tol) pairs += Triple(i, j, e.start - r.start)
        }
        val usedR = HashSet<Int>(); val usedE = HashSet<Int>(); val out = ArrayList<Triple<Int, Int, Double>>()
        for (p in pairs.sortedBy { abs(it.third) }) {
            if (p.first !in usedR && p.second !in usedE) { usedR += p.first; usedE += p.second; out += p }
        }
        return out
    }

    /** Line scores against the ground truth, and how well the legato notes are found and marked. */
    private class Score {
        var tp = 0; var est = 0; var ref = 0; val errors = ArrayList<Double>()
        var legato = 0; var found = 0; var marked = 0; var estLegato = 0; var estLegatoRight = 0; var right = 0

        fun add(gt: List<Row>, line: List<Row>, pairs: List<Triple<Int, Int, Double>>) {
            tp += pairs.size; est += line.size; ref += gt.size; errors += pairs.map { it.third }
            val byGt = pairs.associate { it.first to it.second }
            val byLine = pairs.associate { it.second to it.first }
            for ((i, g) in gt.withIndex()) if (g.articulation.isLegato) {
                legato++
                val j = byGt[i] ?: continue
                found++
                if (line[j].articulation == g.articulation) marked++
            }
            for ((j, n) in line.withIndex()) if (n.articulation.isLegato) {
                estLegato++
                if (byLine[j]?.let { gt[it].articulation } == n.articulation) estLegatoRight++
            }
            right += pairs.count { (i, j, _) -> gt[i].articulation == line[j].articulation }
        }

        fun add(o: Score) {
            tp += o.tp; est += o.est; ref += o.ref; errors += o.errors
            legato += o.legato; found += o.found; marked += o.marked; estLegato += o.estLegato; estLegatoRight += o.estLegatoRight; right += o.right
        }

        val f get() = 2.0 * tp / (est + ref)

        override fun toString(): String {
            val ms = errors.map { it * 1000 }
            val mean = ms.average()
            val std = sqrt(ms.sumOf { (it - mean) * (it - mean) } / ms.size)
            return "P %.3f R %.3f F %.3f | onset error mean %+.1f ms, |mean| %.1f ms, std %.1f ms".format(
                tp.toDouble() / est, tp.toDouble() / ref, f, mean, ms.map { abs(it) }.average(), std) +
                " | legato found %d/%d, marked %d, marks right %d/%d, articulations right %d/%d".format(
                    found, legato, marked, estLegatoRight, estLegato, right, tp)
        }
    }

    private fun activations(song: File): BasicPitch.Activations {
        val contours = File(song, "contours.u8").takeIf { it.exists() }?.let { bytes(it, BasicPitch.N_CONTOUR_BINS) }
        return BasicPitch.Activations(floats(File(song, "frames.f32"), 88), floats(File(song, "onsets.f32"), 88), contours)
    }

    private fun attacks(song: File) = File(song, "audio22.f32").takeIf { it.exists() }?.let { Attacks.of(samples(it)) }

    @Test
    fun kotlinLineIsThePythonLine() {
        val d = dir ?: return
        // The line at each detail level the export has (line.csv is the normal one; line_MORE.csv and so on).
        val bySet = LinkedHashMap<Pair<Detail, String>, Score>()
        for (song in songs(d)) {
            val act = activations(song)
            val attacks = attacks(song)
            if (attacks != null) {
                // The attack tracks are the Python ones (float32 rounding apart).
                for ((mine, theirs) in listOf(attacks.rises to samples(File(song, "rise.f32")), attacks.energies to samples(File(song, "energy.f32")))) {
                    assertEquals(theirs.size, mine.size, "${song.name}: attack frames")
                    val scale = theirs.maxOrNull() ?: 1f
                    val err = mine.indices.maxOfOrNull { abs(mine[it] - theirs[it]) } ?: 0f
                    assertTrue(err <= 1e-4 * scale, "${song.name}: attack track differs by $err (max $scale)")
                }
            }
            val lines = Detail.entries.associateWith { detail ->
                SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(act, detail), Instrument.TETRACHORDO, act, attacks, detail)
            }
            // More detail keeps every note of the normal line, fewer adds none.
            assertTrue(keeps(lines.getValue(Detail.MORE), lines.getValue(Detail.NORMAL)), "${song.name}: MORE lost notes of NORMAL")
            assertTrue(keeps(lines.getValue(Detail.NORMAL), lines.getValue(Detail.FEWER)), "${song.name}: FEWER has notes NORMAL hasn't")
            for (detail in Detail.entries) {
                val file = File(song, if (detail == Detail.NORMAL) "line.csv" else "line_${detail.name}.csv")
                if (!file.exists()) continue
                val line = rows(lines.getValue(detail))
                val python = rows(file)
                val same = match(python, line, 0.001)
                val sameArticulation = same.count { (i, j, _) -> python[i].articulation == line[j].articulation }
                val gt = rows(File(song, "gt.csv"))
                val s = Score().apply { add(gt, line, match(gt, line)) }
                val set = File(song, "set.txt").takeIf { it.exists() }?.readText()?.trim() ?: "songs"
                bySet.getOrPut(detail to set) { Score() }.add(s)
                println("%-28s %-6s same as python %d/%d (kotlin %d), articulations %d | %s".format(
                    song.name, detail, same.size, python.size, line.size, sameArticulation, s))
                assertTrue(same.size >= python.size * 0.98 && same.size >= line.size * 0.98, "${song.name} $detail: ${same.size} of ${python.size}/${line.size}")
                // An export from before articulations (start,end,midi only) has none to compare.
                val withArticulations = file.useLines { l -> l.firstOrNull()?.split(",")?.size ?: 0 } >= 4
                if (withArticulations) assertTrue(sameArticulation >= same.size * 0.98, "${song.name}: articulations $sameArticulation of ${same.size}")
            }
        }
        for (detail in Detail.entries) {
            val earlier = Score()
            val all = Score()
            for ((key, s) in bySet) {
                if (key.first != detail) continue
                println("%-6s %-18s %s".format(detail, key.second, s))
                if (key.second in listOf("songs", "songs_test", "songs_rep")) earlier.add(s)
                all.add(s)
            }
            if (earlier.ref > 0) println("%-6s 13 earlier songs:  %s".format(detail, earlier))
            if (all.ref > 0) println("%-6s all songs:         %s".format(detail, all))
        }
    }

    @Test
    fun savedDataGivesTheSameLine() {
        val d = dir ?: return
        val list = songs(d).filter { File(it, "audio22.f32").exists() && File(it, "contours.u8").exists() }
        for (song in list) {
            val data = AnalysisData.of(activations(song), attacks(song))
            val fresh = SongAnalyzer.build(song.name, song.name, SongAnalyzer.candidates(data.act), Instrument.TETRACHORDO, data.act, data.attacks)
            val bytes = java.io.ByteArrayOutputStream().also { data.write(it) }.toByteArray()
            val back = assertNotNull(AnalysisData.read(bytes.inputStream()))
            assertEquals(fresh.notes, SongAnalyzer.rebuild(fresh, back).notes, song.name)
        }
        // A 4-minute song (the songs strung together): its file size, and how long reading it and deriving its line take.
        val parts = list.map { activations(it) to samples(File(it, "audio22.f32")) }
        val frames = ArrayList<FloatArray>(); val onsets = ArrayList<FloatArray>(); val contours = ArrayList<ByteArray>()
        val audio = ArrayList<FloatArray>()
        var seconds = 0.0
        for ((act, a) in parts) {
            if (seconds >= 240) break
            frames += act.frames; onsets += act.onsets; contours += act.contours!!
            audio += a
            seconds += act.frames.size.toDouble() / BasicPitch.ANNOTATIONS_FPS
        }
        val all = FloatArray(audio.sumOf { it.size }).also { var p = 0; for (a in audio) { a.copyInto(it, p); p += a.size } }
        val act = BasicPitch.Activations(frames.toTypedArray(), onsets.toTypedArray(), contours.toTypedArray())
        val data = AnalysisData.of(act, Attacks.of(all))
        val base = SongAnalyzer.build("long", "long", SongAnalyzer.candidates(data.act), Instrument.TETRACHORDO, data.act, data.attacks)
        val bytes = java.io.ByteArrayOutputStream().also { data.write(it) }.toByteArray()
        var best = Double.MAX_VALUE
        repeat(3) {
            val t0 = System.nanoTime()
            val again = SongAnalyzer.rebuild(base, assertNotNull(AnalysisData.read(bytes.inputStream())))
            best = minOf(best, (System.nanoTime() - t0) / 1e9)
            assertEquals(base.notes, again.notes)
        }
        println("%.0f s of song: %.2f MB saved (%.2f MB a minute), read + rebuild %.2f s, %d notes".format(
            seconds, bytes.size / 1e6, bytes.size / 1e6 / (seconds / 60), best, base.notes.size))
        assertTrue(bytes.size < 5e6 * seconds / 240, "${bytes.size} bytes for $seconds s")
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
                    val line = a.notes.map { Row(it.start, it.midi, it.articulation) }
                    val gt = rows(File(song, "gt.csv"))
                    val s = Score().apply { add(gt, line, match(gt, line)) }
                    total.add(s)
                    println("%-28s %.0f s | %s".format(song.name, (System.nanoTime() - t0) / 1e9, s))
                }
            }
        }
        println("all songs, whole analysis: $total")
        assertTrue(total.f > 0.65, "F ${total.f}")
    }

    private fun model() = File("../bouzouki/src/main/assets/basic_pitch.onnx")
}

/**
 * Whether every note of [some] is in [line]: a note of its pitch starts within 30 ms of it, or a tremolo of its pitch
 * sounds as it starts (where more re-picks were found, they join into one held note).
 */
internal fun keeps(line: List<NoteEvent>, some: List<NoteEvent>) = some.all { n ->
    line.any { it.midi == n.midi && (abs(it.start - n.start) < 0.03 || (it.articulation == Articulation.TREMOLO && it.start <= n.start && n.start < it.end)) }
}

@JvmName("keepsSongNotes")
internal fun keeps(line: List<SongNote>, some: List<SongNote>): Boolean {
    fun events(l: List<SongNote>) = l.map { NoteEvent(it.start, it.end, it.midi, 0.0, articulation = it.articulation) }
    return keeps(events(line), events(some))
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
