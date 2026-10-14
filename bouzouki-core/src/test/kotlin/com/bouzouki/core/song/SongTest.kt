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

    @Test
    fun keepsTheMelodyOnTopOfChords() {
        val notes = listOf(
            NoteEvent(0.0, 2.0, 50, 0.6), NoteEvent(0.0, 2.0, 57, 0.6), // a held chord underneath
            NoteEvent(0.1, 0.5, 69, 0.7), NoteEvent(0.5, 0.9, 71, 0.7), NoteEvent(0.9, 1.4, 72, 0.7),
            NoteEvent(1.0, 1.2, 40, 0.9), // bass, below the bouzouki
        )
        val line = SongAnalyzer.bouzoukiLine(notes, tetra)
        assertEquals(listOf(69, 71, 72), line.map { it.midi }.filter { it > 60 })
        assertTrue(line.none { it.midi == 40 })
        assertTrue(line.zipWithNext().all { (a, b) -> b.start >= a.end - 0.03 }, "monophonic: $line")
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
}

/** Runs the real Basic Pitch model (bundled with the app) on notes from our own bouzouki synth. */
class BasicPitchTest {
    private val model = File("../bouzouki/src/main/assets/basic_pitch.onnx")

    @Test
    fun transcribesBouzoukiNotes() {
        val env = OrtEnvironment.getEnvironment()
        env.createSession(model.path, OrtSession.SessionOptions()).use { session ->
            val fs = BasicPitch.SAMPLE_RATE
            val synth = BouzoukiSynth(fs)
            val melody = listOf(62, 63, 66, 67, 69, 70, 72, 74) // D Hitzaz up
            val audio = FloatArray(fs * (melody.size + 1) / 2)
            var pos = 0
            for (m in melody) {
                synth.pluck(3, m, false, 0.9f)
                synth.render(audio, pos, fs / 2)
                pos += fs / 2
            }
            synth.render(audio, pos, audio.size - pos)
            val bp = BasicPitch(env, session)
            val notes = BasicPitch.notes(bp.activations(audio))
            val line = SongAnalyzer.bouzoukiLine(notes, Instrument.TETRACHORDO)
            assertEquals(melody, line.map { it.midi }, "line: $line")
            line.forEachIndexed { i, n -> assertTrue(abs(n.start - i * 0.5) < 0.08, "note $i starts at ${n.start}") }
        }
    }
}

/**
 * Checks the Kotlin vocal removal and transcription against the Python reference
 * (scratch/ml/ref.py and basic_pitch.predict). Needs the 67 MB model and reference files,
 * so it only runs where BOUZOUKI_ML_DIR points at them.
 */
class ReferenceTest {
    private val dir = System.getenv("BOUZOUKI_ML_DIR")?.let { File(it) }

    private fun readWav(f: File): Array<FloatArray> {
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
                val notes = BasicPitch.notes(BasicPitch(env, bpSession).activations(resample(inst, 44100, 22050)))
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
                val a = SongAnalyzer.build("x", "x", notes, Instrument.TETRACHORDO)
                println("bouzouki line: ${a.notes.size} notes, dromos guess ${a.dromos} on ${a.rootPc}")
            }
        }
        assertTrue(Dromos.entries.isNotEmpty())
    }
}
