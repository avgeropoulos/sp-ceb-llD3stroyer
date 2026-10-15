package com.bouzouki.core.song

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bouzouki.core.BouzoukiSynth
import com.bouzouki.core.Instrument
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuningTest {
    private val sr = BasicPitch.SAMPLE_RATE
    private val tetra = Instrument.TETRACHORDO

    /** A pluck of the test phrases: when (s), course, MIDI note. */
    private data class Pluck(val time: Double, val course: Int, val midi: Int)

    /**
     * An original phrase (a random walk up and down D Hitzaz, 8ths and 16ths at 112 BPM, now and then a double stop on
     * the course below) on the bouzouki synth.
     */
    private fun phrase(seconds: Double, seed: Long): List<Pluck> {
        val rnd = java.util.Random(seed)
        val scale = listOf(62, 63, 66, 67, 69, 70, 72, 74, 75, 78, 79)
        val beat = 60.0 / 112
        val out = ArrayList<Pluck>()
        var t = 0.2
        var i = 3
        while (t < seconds - 0.6) {
            i = (i + rnd.nextInt(5) - 2).coerceIn(0, scale.size - 1)
            out += Pluck(t, 3, scale[i])
            if (rnd.nextInt(6) == 0) out += Pluck(t, 2, scale[i] - 5)
            t += if (rnd.nextInt(3) == 0) beat / 4 else beat / 2
        }
        return out
    }

    private fun render(plucks: List<Pluck>, seconds: Double, rate: Int): FloatArray {
        val synth = BouzoukiSynth(rate)
        val audio = FloatArray((seconds * rate).toInt())
        var pos = 0
        for (p in plucks.sortedBy { it.time }) {
            val at = (p.time * rate).toInt()
            synth.render(audio, pos, at - pos)
            pos = at
            synth.pluck(p.course, p.midi, tetra.courses[p.course].octavePair, 0.85f)
        }
        synth.render(audio, pos, audio.size - pos)
        val hiss = java.util.Random(5)
        for (i in audio.indices) audio[i] += 0.002f * hiss.nextGaussian().toFloat()
        return audio
    }

    /** [audio] (at [rate]) as a record played [cents] fast (+) or slow (-): pitch and time change by the same ratio. */
    private fun offSpeed(audio: FloatArray, rate: Int, cents: Double): Pair<FloatArray, Double> {
        val to = (rate * 2.0.pow(-cents / 1200)).roundToInt()
        return resample(audio, rate, to) to 1200 * kotlin.math.log2(rate.toDouble() / to)
    }

    /** The distance (cents) between two tunings, around the circle of a semitone. */
    private fun apart(a: Double, b: Double): Double = abs((a - b + 50.0).mod(100.0) - 50.0)

    @Test
    fun estimatesTheTuningOfPhrasesAtSeveralOffsets() {
        val audio = render(phrase(12.0, 1), 12.0, sr)
        for (c in listOf(-45.0, -30.0, -20.0, -10.0, 0.0, 15.0, 35.0, 49.0)) {
            val (x, cents) = offSpeed(audio, sr, c)
            val e = Tuning.estimate(x)
            assertTrue(apart(e, cents) < 1.5, "recording $cents cents off, estimate $e")
            assertTrue(e >= -50.0 && e < 50.0)
        }
    }

    @Test
    fun staysRobustWithDrumsAVoiceAndVibrato() {
        val seconds = 16.0
        val plucks = phrase(seconds, 2)
        val band = render(plucks, seconds, sr)
        val rnd = java.util.Random(9)
        // Drums: a noise burst every beat (a kick-like thump on the first of four), as loud as the bouzouki.
        val beat = 60.0 / 112
        var t = 0.2
        var k = 0
        while (t < seconds - 0.3) {
            val at = (t * sr).toInt()
            for (i in 0 until (0.12 * sr).toInt()) {
                if (at + i >= band.size) break
                val env = exp(-i / (0.025 * sr))
                band[at + i] += (0.25 * env * rnd.nextGaussian() + if (k % 4 == 0) 0.4 * env * sin(2 * PI * 60 * i / sr) else 0.0).toFloat()
            }
            t += beat
            k++
        }
        // A voice the separation left in: a melody of its own, wide vibrato (40 cents at 5.5 Hz), slides into notes,
        // and sung 10 cents flat on average; half as loud as the bouzouki.
        val notes = listOf(57, 58, 62, 61, 62, 57, 55, 57)
        var phase = 0.0
        for (i in band.indices) {
            val s = i.toDouble() / sr
            val n = (s / 2).toInt()
            val within = s - 2 * n
            val glide = if (within < 0.15) (within / 0.15 - 1) * 0.5 else 0.0
            val midi = notes[n % notes.size] + glide - 0.1 + 0.4 * sin(2 * PI * 5.5 * s) * minOf(1.0, within / 0.5)
            phase += 2 * PI * 440.0 * 2.0.pow((midi - 69) / 12) / sr
            var v = 0.0
            for (h in 1..6) v += sin(h * phase) / h
            band[i] += (0.04 * v).toFloat()
        }
        for (c in listOf(-25.0, 0.0, 35.0)) {
            val (x, cents) = offSpeed(band, sr, c)
            val e = Tuning.estimate(x)
            assertTrue(apart(e, cents) < 2.5, "recording $cents cents off, estimate $e")
        }
    }

    @Test
    fun silenceNoiseAndShortAudioSayInTune() {
        assertEquals(0.0, Tuning.estimate(FloatArray(0)))
        assertEquals(0.0, Tuning.estimate(FloatArray(1000)))
        assertEquals(0.0, Tuning.estimate(FloatArray(sr * 5)))
        val rnd = java.util.Random(3)
        assertEquals(0.0, Tuning.estimate(FloatArray(sr * 5) { (0.1 * rnd.nextGaussian()).toFloat() }))
    }

    @Test
    fun retuneMovesPitchAndTimeTogether() {
        val f = 440.0 * 2.0.pow(-25.0 / 1200) // A4, 25 cents flat
        val tone = FloatArray(sr * 2) { (0.5 * sin(2 * PI * f * it / sr)).toFloat() }
        val rate = Tuning.rateFor(-25.0)
        assertTrue(rate < sr)
        val out = Tuning.retune(tone, rate)
        assertEquals((tone.size.toLong() * rate / sr).toInt(), out.size)
        // Played at 22050 Hz, it is A = 440 again (to the rate's whole hertz): compare with that tone away from the edges.
        val heard = f * sr / rate
        assertTrue(abs(heard - 440.0) < 0.02, "heard at $heard Hz")
        var err = 0.0
        for (i in 1000 until out.size - 1000) err = maxOf(err, abs(out[i] - 0.5 * sin(2 * PI * heard * i / sr)))
        assertTrue(err < 0.005, "error $err")
        // The tabulated kernel gives what resample() gives.
        val slow = resample(tone, sr, rate)
        var diff = 0.0
        for (i in out.indices) diff = maxOf(diff, abs(out[i] - slow[i]).toDouble())
        assertTrue(diff < 1e-4, "differs from resample by $diff")
        assertEquals(sr, Tuning.rateFor(0.0))
        assertTrue(Tuning.retune(tone, sr).contentEquals(tone))
    }

    @Test
    fun activationsGoBackOnTheRecordingsTimeWithoutLosingOnsets() {
        val frame = BasicPitch.FFT_HOP.toDouble() / sr
        // Onset peaks as Basic Pitch draws them, a few frames wide, at [times] (s) as heard in the audio the frames are of.
        fun bumps(times: List<Double>, at: Double) = times.maxOf { c -> 0.9 * exp(-0.5 * ((at - c) / frame).pow(2)) }.toFloat()
        // Where the peaks are found in [a] (refined between frames, as the line does), and how high they are.
        fun peaks(a: BasicPitch.Activations) = (1 until a.onsets.size - 1).filter { f ->
            val o = a.onsets[f][40]
            o >= 0.5f && o > a.onsets[f - 1][40] && o >= a.onsets[f + 1][40]
        }.map { (BasicPitch.onsetTime(a, it, 40) - BasicPitch.ONSET_LATENCY) to a.onsets[it][40] }
        val real = 2000 // frames of the recording
        val end = BasicPitch.frameTime(real - 3)
        // The same peaks in a recording in tune, straight from its frames: how close refining between frames gets.
        val notes = (0 until 220).map { 0.1 + it * 0.105 + 0.0013 * (it % 7) }.filter { it < end }
        val inTune = peaks(BasicPitch.Activations(Array(real) { FloatArray(BasicPitch.N_KEYS) },
            Array(real) { f -> FloatArray(BasicPitch.N_KEYS).also { it[40] = bumps(notes, BasicPitch.frameTime(f)) } }))
        assertEquals(notes.size, inTune.size)
        val inTuneError = notes.indices.maxOf { abs(inTune[it].first - notes[it]) }
        for (cents in listOf(-45.0, -20.0, 15.0, 40.0)) {
            val rate = Tuning.rateFor(cents)
            val scale = sr.toDouble() / rate
            val nt = (BasicPitch.frameTime(real - 1) / scale * 87).toInt() + 5 // the retuned audio's frames (87 a second at most)
            // The retuned audio plays every note [scale] times sooner; a ramp on the note plane.
            val onsets = Array(nt) { u -> FloatArray(BasicPitch.N_KEYS).also { it[40] = bumps(notes.map { t -> t / scale }, BasicPitch.frameTime(u)) } }
            val frames = Array(nt) { u -> FloatArray(BasicPitch.N_KEYS).also { it[40] = u.toFloat() / nt } }
            val contours = Array(nt) { u -> ByteArray(BasicPitch.N_CONTOUR_BINS).also { it[121] = (u % 200).toByte() } }
            val act = Tuning.toRealTime(BasicPitch.Activations(frames, onsets, contours), rate, real)
            assertEquals(real, act.frames.size)
            assertEquals(real, act.onsets.size)
            assertEquals(real, act.contours!!.size)
            // Every peak is there once, about as high (in tune its frames reach 0.79 to 0.9), and found within a
            // millisecond of how close it is found in tune.
            val found = peaks(act)
            assertEquals(notes.size, found.size, "cents $cents")
            for ((i, p) in found.withIndex()) {
                assertTrue(p.second > 0.7f, "cents $cents: peak ${p.second}")
                assertTrue(abs(p.first - notes[i]) < inTuneError + 0.001, "cents $cents: peak at ${p.first}, played at ${notes[i]} (in tune within $inTuneError)")
            }
            // The ramp keeps time: each real frame has the value the retuned audio has at that moment.
            for (f in listOf(10, 141, 142, 500, 1500, real - 5)) {
                val t = BasicPitch.frameTime(f) / scale
                var u = 0
                while (BasicPitch.frameTime(u + 1) < t) u++
                val at = u + (t - BasicPitch.frameTime(u)) / (BasicPitch.frameTime(u + 1) - BasicPitch.frameTime(u))
                assertTrue(abs(act.frames[f][40] * nt - at) < 0.01, "cents $cents frame $f: ${act.frames[f][40] * nt} vs $at")
            }
        }
        val empty = Tuning.toRealTime(BasicPitch.Activations(emptyArray(), emptyArray(), null), 21000, 10)
        assertEquals(0, empty.frames.size)
    }

    @Test
    fun contoursOfARecordingOutOfTuneSayWhichWay() {
        // Contours as Basic Pitch draws them: a bump three bins wide around each note's pitch.
        fun contours(cents: Double): Array<ByteArray> = Array(400) { t ->
            val midi = 50 + (t / 20) % 25
            val centre = 3 * (midi - BasicPitch.MIDI_OFFSET) + 1 + 3 * cents / 100
            ByteArray(BasicPitch.N_CONTOUR_BINS) { b -> (220 * exp(-0.5 * ((b - centre) / 0.8).pow(2))).roundToInt().toByte() }
        }
        fun act(c: Array<ByteArray>) = BasicPitch.Activations(Array(c.size) { FloatArray(BasicPitch.N_KEYS) }, Array(c.size) { FloatArray(BasicPitch.N_KEYS) }, c)
        for (cents in listOf(-20.0, 0.0, 12.0)) {
            val e = assertNotNull(Tuning.fromContours(act(contours(cents))))
            assertTrue(apart(e, cents) < 6, "$cents cents: $e")
        }
        assertNull(Tuning.fromContours(BasicPitch.Activations(emptyArray(), emptyArray(), null)))
        assertNull(Tuning.fromContours(act(Array(400) { ByteArray(BasicPitch.N_CONTOUR_BINS) })))
        // Saved before the tuning was measured: the contours tell it was off, but give no tuning; measured: the data
        // says so.
        val flat = act(contours(-20.0))
        assertTrue(SongAnalyzer.transcribedOutOfTune(AnalysisData(flat, null)))
        assertTrue(!SongAnalyzer.transcribedOutOfTune(AnalysisData(act(contours(0.0)), null)))
        assertTrue(!SongAnalyzer.transcribedOutOfTune(AnalysisData(flat, null, -20.0)))
        assertEquals(-20.0, SongAnalyzer.tuningOf(AnalysisData(flat, null, -20.0)))
        assertNull(SongAnalyzer.tuningOf(AnalysisData(flat, null)))
    }

    @Test
    fun analysisDataKeepsTheTuningAndReadsOlderFiles() {
        val n = 30
        val act = BasicPitch.Activations(Array(n) { t -> FloatArray(BasicPitch.N_KEYS) { k -> ((t * 7 + k) % 255) / 255f } },
            Array(n) { FloatArray(BasicPitch.N_KEYS) }, Array(n) { ByteArray(BasicPitch.N_CONTOUR_BINS) })
        fun roundTrip(d: AnalysisData) = AnalysisData.read(ByteArrayOutputStream().also { d.write(it) }.toByteArray().inputStream())
        assertEquals(-23.25, assertNotNull(roundTrip(AnalysisData.of(act, null, -23.25))).tuning)
        assertNull(assertNotNull(roundTrip(AnalysisData.of(act, null))).tuning)
        // A file version 2 wrote (no tuning after the attacks) reads, as not measured.
        fun file(version: Int, tail: (DataOutputStream) -> Unit): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(DeflaterOutputStream(out)).use { d ->
                d.writeInt(0x425A4144)
                d.writeInt(version)
                d.writeInt(n)
                d.writeInt(BasicPitch.N_KEYS)
                d.write(ByteArray(2 * BasicPitch.N_KEYS * n))
                d.writeBoolean(false) // no contours
                d.writeBoolean(false) // no attacks
                tail(d)
            }
            return out.toByteArray()
        }
        val v2 = assertNotNull(AnalysisData.read(file(2) {}.inputStream()))
        assertNull(v2.tuning)
        assertEquals(n, v2.act.frames.size)
        assertEquals(12.5, assertNotNull(AnalysisData.read(file(3) { it.writeBoolean(true); it.writeDouble(12.5) }.inputStream())).tuning)
        // A version 3 file without its tuning field, a tuning past half a semitone, or another version: damaged.
        assertNull(AnalysisData.read(file(3) {}.inputStream()))
        assertNull(AnalysisData.read(file(3) { it.writeBoolean(true); it.writeDouble(80.0) }.inputStream()))
        assertNull(AnalysisData.read(file(3) { it.writeBoolean(true); it.writeDouble(Double.NaN) }.inputStream()))
        assertNull(AnalysisData.read(file(2) { it.writeBoolean(true); it.writeDouble(12.5) }.inputStream()))
        assertNull(AnalysisData.read(file(4) { it.writeBoolean(false) }.inputStream()))
    }

    @Test
    fun aRecordOffSpeedIsTranscribedInTuneOnItsOwnTime() {
        val model = File("../bouzouki/src/main/assets/basic_pitch.onnx")
        val rate = 44100
        val seconds = 10.0
        val plucks = phrase(seconds, 4).filter { it.course == 3 }
        val inTune = render(plucks, seconds, rate)
        val env = OrtEnvironment.getEnvironment()
        env.createSession(model.path, OrtSession.SessionOptions()).use { session ->
            val pitch = BasicPitch(env, session)
            val analyzer = SongAnalyzer(MdxSeparator(env, session), pitch) // the separator isn't asked
            // What Basic Pitch made of a record as it was, before the tuning was measured (the data of older songs).
            fun asItWas(instrumental: FloatArray) = AnalysisData.of(pitch.activations(resample(instrumental, rate, sr)), null)
            for (c in listOf(-30.0, 20.0)) {
                val (instrumental, cents) = offSpeed(inTune, rate, c)
                val k = 2.0.pow(-cents / 1200) // the record's time per second of the phrase
                val (song, data) = analyzer.analyzeInstrumental("id", "t", instrumental, tetra)
                assertTrue(apart(song.tuningCents, cents) < 1.5, "recording $cents cents off, measured ${song.tuningCents}")
                assertEquals(song.tuningCents, data.tuning)
                assertEquals(BasicPitch.frameCount(instrumental.size / 2), data.act.frames.size)
                // Every note, at its own pitch, when the record plays it.
                assertEquals(plucks.map { it.midi }, song.notes.map { it.midi }, "$cents cents: ${song.notes}")
                val errors = song.notes.mapIndexed { i, n -> n.start - plucks[i].time * k }
                assertTrue(errors.all { abs(it) < 0.015 }, "$cents cents: onset errors $errors")
                // The saved data gives the same line again (and the tuning stays with the song).
                val back = assertNotNull(AnalysisData.read(ByteArrayOutputStream().also { data.write(it) }.toByteArray().inputStream()))
                assertEquals(data.tuning, back.tuning)
                val again = SongAnalyzer.rebuild(song, back)
                assertEquals(song.notes, again.notes)
                assertEquals(song.tuningCents, again.tuningCents)
                assertTrue(!SongAnalyzer.transcribedOutOfTune(back))
            }
            // Data saved before the tuning was measured, of records 10 flat or 15 sharp or more, is reported out of
            // tune (the model pulls their contours towards its bins: here 10 flat reads about 7 flat, 15 sharp about 7
            // sharp), but no tuning is made up for it.
            for (c in listOf(-30.0, -10.0, 15.0, 20.0)) {
                val old = asItWas(offSpeed(inTune, rate, c).first)
                assertTrue(SongAnalyzer.transcribedOutOfTune(old), "$c cents: contours read ${Tuning.fromContours(old.act)}")
                assertNull(SongAnalyzer.tuningOf(old))
            }
            // In tune: transcribed as it is, the same activations as Basic Pitch gives the audio itself; and data saved
            // before the tuning was measured isn't reported out of tune.
            val (song, data) = analyzer.analyzeInstrumental("id", "t", inTune, tetra)
            assertTrue(abs(song.tuningCents) < Tuning.MIN_CORRECTION)
            val direct = asItWas(inTune)
            assertTrue(direct.act.onsets.indices.all { direct.act.onsets[it].contentEquals(data.act.onsets[it]) })
            assertTrue(!SongAnalyzer.transcribedOutOfTune(direct), "in tune: contours read ${Tuning.fromContours(direct.act)}")
        }
    }
}
