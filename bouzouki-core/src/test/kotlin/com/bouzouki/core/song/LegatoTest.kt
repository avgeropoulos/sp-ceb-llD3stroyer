package com.bouzouki.core.song

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bouzouki.core.Dromos
import com.bouzouki.core.Instrument
import com.bouzouki.core.midiToHz
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Renders bouzouki phrases with hammer-ons, pull-offs and slides, like the scratch evaluation's synth.py: a pair of
 * Karplus-Strong strings whose delay length follows a pitch curve while they sound (a jump for a hammer-on or
 * pull-off, a glide for a slide), plus a small excitation without a pick for a hammer-on (a dull fingertip strike)
 * or a pull-off (the finger plucking the string as it leaves), and a plectrum's click and bright attack for a pick.
 */
internal class LegatoPhrase(private val fs: Int = BasicPitch.SAMPLE_RATE, seed: Long = 7) {
    enum class Kind { PICK, HAMMER, PULL, SLIDE }

    /** A note of the phrase: [time] it is picked or arrives at (s), its [midi] pitch and how it is sounded. */
    data class Note(val time: Double, val midi: Int, val kind: Kind)

    private val rnd = Random(seed)
    private val notes = ArrayList<Note>()

    val score: List<Note> get() = notes

    fun pick(time: Double, midi: Int) = apply { notes += Note(time, midi, Kind.PICK) }
    fun hammer(time: Double, midi: Int) = apply { notes += Note(time, midi, Kind.HAMMER) }
    fun pull(time: Double, midi: Int) = apply { notes += Note(time, midi, Kind.PULL) }
    fun slide(time: Double, midi: Int) = apply { notes += Note(time, midi, Kind.SLIDE) }

    /** The phrase on one course (each pick damps the string before), [seconds] long. */
    fun render(seconds: Double): FloatArray {
        val out = FloatArray((seconds * fs).toInt())
        val picks = notes.indices.filter { notes[it].kind == Kind.PICK }
        for ((p, i) in picks.withIndex()) {
            val end = if (p + 1 < picks.size) picks[p + 1] else notes.size
            val stop = if (p + 1 < picks.size) notes[picks[p + 1]].time else seconds
            string(out, notes.subList(i, end), stop, detune = -2.2)
            string(out, notes.subList(i, end), stop, detune = 2.2)
        }
        return out
    }

    /** One string of the pair: picked at figure[0], the rest of the figure sounded on it without a pick. */
    private fun string(out: FloatArray, figure: List<Note>, stop: Double, detune: Double) {
        val start = (figure[0].time * fs).toInt()
        val len = min(out.size - start, ((stop - figure[0].time + 0.03) * fs).toInt())
        val curve = DoubleArray(len)
        var cur = figure[0].midi.toDouble()
        var k = 1
        for (n in 0 until len) {
            val t = n.toDouble() / fs + figure[0].time
            while (k < figure.size && t >= figure[k].time) { cur = figure[k].midi.toDouble(); k++ }
            var m = cur
            if (k < figure.size && figure[k].kind == Kind.SLIDE && t >= figure[k].time - GLIDE) {
                m = cur + (figure[k].midi - cur) * (t - (figure[k].time - GLIDE)) / GLIDE
            }
            curve[n] = m + detune / 100
        }
        val vel = 0.85
        val f0 = hz(curve[0])
        val excitations = ArrayList<Pair<Int, DoubleArray>>()
        excitations += 0 to excitation(f0, vel, 1.0, bright = 0.12 + 0.38 * vel, comb = 0.14)
        for (note in figure.drop(1)) {
            val at = ((note.time - figure[0].time) * fs).toInt()
            when (note.kind) {
                Kind.HAMMER -> excitations += at to excitation(hz(note.midi.toDouble()), vel, 0.12, bright = 0.04 + 0.08 * vel, comb = 0.5)
                Kind.PULL -> excitations += at to excitation(hz(note.midi.toDouble()), vel, 0.25, bright = 0.10 + 0.25 * vel, comb = 0.12)
                else -> {}
            }
        }
        val damp = ((stop - figure[0].time) * fs).toInt()
        val a = 0.06 + 0.10 * (1 - vel) + 0.04 * min(1.0, f0 / 1200.0)
        val t60 = (7.5 * sqrt(130.0 / f0)).coerceIn(1.6, 9.0)
        val size = 4096
        val buf = DoubleArray(size)
        var w = 0
        var z1 = 0.0; var z2 = 0.0
        var clickLeft = (fs * 0.006).toInt(); var clickAmp = 0.22 * vel; var clickPrev = 0.0
        for (n in 0 until len) {
            val f = hz(curve[n])
            var g = 10.0.pow(-3.0 / (t60 * f))
            if (n >= damp) g = min(g, 10.0.pow(-3.0 / (0.025 * f)))
            val pos = w - (fs / f - 1.0)
            val ip = floor(pos).toInt()
            val d = pos - ip
            fun at(i: Int) = buf[Math.floorMod(i, size)]
            val zc = (-d * (d - 1) * (d - 2) / 6.0) * at(ip - 1) + ((d + 1) * (d - 1) * (d - 2) / 2.0) * at(ip) +
                (-(d + 1) * d * (d - 2) / 2.0) * at(ip + 1) + ((d + 1) * d * (d - 1) / 6.0) * at(ip + 2)
            var s = g * (a * zc + (1 - 2 * a) * z1 + a * z2)
            z2 = z1; z1 = zc
            for ((from, e) in excitations) if (n >= from && n - from < e.size) s += e[n - from]
            buf[w % size] = s
            w++
            var o = s
            if (clickLeft > 0) { // the plectrum's click: a short burst of high-passed noise
                clickLeft--
                val nz = (rnd.nextDouble() * 2 - 1) * clickAmp
                o += nz - clickPrev
                clickPrev = nz
                clickAmp *= 0.9993.pow(48000.0 / fs)
            }
            out[start + n] += (0.35 * o).toFloat()
        }
    }

    /** One period of filtered noise: [bright] is the low-pass coefficient, [comb] where the string is struck. */
    private fun excitation(f: Double, vel: Double, level: Double, bright: Double, comb: Double): DoubleArray {
        val n0 = max(2, (fs / f).toInt())
        var y = 0.0
        val ex = DoubleArray(n0) { y += bright * (rnd.nextDouble() * 2 - 1 - y); y }
        val pick = max(1, Math.round(n0 * comb).toInt())
        val e = DoubleArray(n0) { ex[it] - ex[Math.floorMod(it - pick, n0)] }
        val mean = e.average()
        var peak = 1e-9
        for (i in e.indices) { e[i] -= mean; peak = max(peak, abs(e[i])) }
        val scale = level * (0.35 + 0.65 * vel) / peak
        return DoubleArray(n0) { e[it] * scale }
    }

    private fun hz(midi: Double) = 440.0 * 2.0.pow((midi - 69) / 12)

    companion object {
        /** How long a slide glides before it arrives. */
        const val GLIDE = 0.06
    }
}

/** Hammer-ons, pull-offs and slides found by the whole line analysis, with the bundled Basic Pitch model. */
class LegatoTest {
    private val model = File("../bouzouki/src/main/assets/basic_pitch.onnx")

    /**
     * [phrase] as a recording has it: over a noise floor (a clean render has none to measure attacks against), with a
     * softer bass picked under it at [bass] (time, MIDI pitch below the bouzouki's range).
     */
    private fun withBand(phrase: FloatArray, bass: List<Pair<Double, Int>>): FloatArray {
        val b = LegatoPhrase(seed = 11)
        for ((t, m) in bass) b.pick(t, m)
        val low = b.render(phrase.size.toDouble() / BasicPitch.SAMPLE_RATE)
        val rnd = Random(5)
        return FloatArray(phrase.size) { (phrase[it] + 0.25 * low[it] + 3e-4 * rnd.nextGaussian()).toFloat() }
    }

    private fun analyse(audio: FloatArray): Pair<BasicPitch.Activations, Attacks> {
        val env = OrtEnvironment.getEnvironment()
        return env.createSession(model.path, OrtSession.SessionOptions()).use { session ->
            BasicPitch(env, session).activations(audio) to Attacks.of(audio)
        }
    }

    /** The line note starting within 30 ms of [n] with its pitch, or null. */
    private fun find(line: List<NoteEvent>, n: LegatoPhrase.Note) = line.firstOrNull { it.midi == n.midi && abs(it.start - n.time) < 0.03 }

    @Test
    fun hammerOnsPullOffsAndSlidesAreMarkedAndPicksAreNot() {
        // On the D course of a tetrachordo: trills, a slide, pull-offs down and hammer-ons up between picked notes,
        // and a picked run at the end, like the legato figures of a laiko line.
        val ph = LegatoPhrase()
            .pick(0.30, 62).hammer(0.52, 64).pull(0.74, 62)
            .pick(1.20, 65).slide(1.50, 67)
            .pick(2.10, 69).pull(2.32, 67).pull(2.54, 65)
            .pick(3.00, 64).hammer(3.22, 65).hammer(3.44, 67)
            .pick(3.90, 62).hammer(4.12, 63).pull(4.34, 62)
        listOf(64, 65, 67, 69, 67, 65, 64, 62).forEachIndexed { i, m -> ph.pick(4.80 + i * 0.2, m) }
        val audio = withBand(ph.render(6.8), listOf(0.30 to 43, 1.20 to 38, 2.10 to 41, 3.00 to 36, 3.90 to 43, 4.80 to 40, 5.60 to 45))
        val (act, attacks) = analyse(audio)
        val line = SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(act), Instrument.TETRACHORDO, act, attacks)
        val report = ph.score.map { n -> "${n.kind}@${n.time}:${n.midi} -> ${find(line, n)?.articulation}" }
        println(report.joinToString("\n"))

        // Every note is in the line, and every picked note is marked picked.
        for (n in ph.score) assertNotNull(find(line, n), "missing $n in $line")
        for (n in ph.score.filter { it.kind == LegatoPhrase.Kind.PICK }) assertEquals(Articulation.PICK, find(line, n)!!.articulation, "$n")
        // Most legato notes are marked as they were played (a few hammer-ons may come out as slides and so on).
        val legato = ph.score.filter { it.kind != LegatoPhrase.Kind.PICK }
        val marked = legato.count { n -> find(line, n)!!.articulation.isLegato }
        val exact = legato.count { n -> find(line, n)!!.articulation.name == n.kind.name }
        assertTrue(marked >= legato.size * 0.75, "legato marked $marked of ${legato.size}:\n${report.joinToString("\n")}")
        assertTrue(exact >= legato.size * 0.6, "marked as played $exact of ${legato.size}:\n${report.joinToString("\n")}")
        assertTrue(line.none { it.articulation.isLegato && ph.score.none { n -> find(line, n) === it } }, "no extra legato notes: $line")

        // build() keeps the articulations in the song, which the cache stores.
        val song = SongAnalyzer.build("x", "x", SongAnalyzer.candidates(act), Instrument.TETRACHORDO, act, attacks)
        assertEquals(line.map { it.articulation }, song.notes.map { it.articulation })
        song.notes.forEach { assertEquals(it.midi, Instrument.TETRACHORDO.midiAt(it.course, it.fret)) }
        assertEquals(song.notes.map { it.articulation }, assertNotNull(SongAnalysis.fromJson(song.toJson())).notes.map { it.articulation })
    }

    @Test
    fun aLongNoteWithVibratoOrABendIsOneNoteAndAPickedAgainIsTwo() {
        // Tied notes held across beats: one plain, one with vibrato, one with a small bend. Each stays one note, while
        // the plain one and the bent one are then picked again on the same pitch: those are new notes.
        val fs = BasicPitch.SAMPLE_RATE
        val audio = FloatArray(fs * 7)
        fun held(start: Double, midi: Int, dur: Double, cents: (Double) -> Double) {
            val ph = LegatoPhrase(seed = (start * 100).toLong())
            val one = ph.pick(0.0, midi).render(dur)
            val damp = (0.03 * fs).toInt() // the hand (or the next pick) damps the string at the end
            for (k in 0 until damp) one[one.size - 1 - k] *= k.toFloat() / damp
            // Bend the rendered note by resampling it along the pitch curve (time-varying playback rate).
            var pos = 0.0
            var i = (start * fs).toInt()
            while (pos < one.size - 2 && i < audio.size) {
                val k = pos.toInt(); val fr = pos - k
                audio[i++] += (one[k] * (1 - fr) + one[k + 1] * fr).toFloat()
                pos += 2.0.pow(cents(pos / fs) / 1200)
            }
        }
        held(0.3, 62, 1.2) { 0.0 }
        held(1.5, 62, 0.6) { 0.0 }
        held(2.1, 65, 1.6) { t -> if (t < 0.3) 0.0 else 30 * sin(2 * PI * 5.5 * (t - 0.3)) }
        held(3.9, 69, 1.6) { t -> 60 * ((t - 0.4) / 0.1).coerceIn(0.0, 1.0) - 60 * ((t - 0.75) / 0.15).coerceIn(0.0, 1.0) }
        held(5.5, 69, 1.0) { 0.0 }
        val (act, attacks) = analyse(withBand(audio, listOf(0.3 to 43, 2.1 to 38, 3.9 to 40, 5.5 to 40)))
        val line = SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(act), Instrument.TETRACHORDO, act, attacks)
        assertEquals(listOf(62, 62, 65, 69, 69), line.map { it.midi }, "line: $line")
        assertEquals(listOf(0.3, 1.5, 2.1, 3.9, 5.5), line.map { it.start }.map { Math.round(it * 10) / 10.0 }, "line: $line")
        assertTrue(line.all { it.articulation == Articulation.PICK }, "line: $line")
    }
}

/** The pick-attack measure on simple signals, and the parts of the legato analysis that need no model. */
class AttacksTest {
    private val fs = BasicPitch.SAMPLE_RATE

    @Test
    fun aPickAttacksAndAPitchChangeDoesNot() {
        // A bright harmonic tone that jumps a whole tone at 1.0 s (a hammer-on without a strike), then a click at 2.0 s.
        val rnd = Random(1)
        val x = FloatArray(fs * 3)
        var phase = 0.0
        for (i in x.indices) {
            val f = if (i < fs) midiToHz(62) else midiToHz(64)
            phase += 2 * PI * f / fs
            var s = 0.0
            for (h in 1..30) s += sin(h * phase) / h
            x[i] = (0.1 * s + 0.001 * rnd.nextGaussian()).toFloat()
        }
        for (i in 0 until (0.006 * fs).toInt()) x[2 * fs + i] += ((rnd.nextDouble() * 2 - 1) * 0.3).toFloat()
        val a = Attacks.of(x)
        assertTrue(a.rise(2.0) > 10 * a.rise(1.0), "click ${a.rise(2.0)} vs pitch change ${a.rise(1.0)}")
        assertTrue(a.energy(2.0) > 20 * a.energy(1.0), "click ${a.energy(2.0)} vs pitch change ${a.energy(1.0)}")
        // Frame times: frame i is centred at (64 i + 128) / 22050 s, the window is ±15 ms.
        assertEquals(0.0, Attacks.of(FloatArray(100)).rise(0.0), 0.0)
    }

    @Test
    fun withoutANoiseFloorAnyRiseIsAPick() {
        // A clean render: most frames rise not at all (the median is 0), so a click is a pick however small, and
        // silence is none.
        val rnd = Random(4)
        val x = FloatArray(fs)
        for (i in 0 until (0.006 * fs).toInt()) x[fs / 2 + i] = ((rnd.nextDouble() * 2 - 1) * 0.01).toFloat()
        val a = Attacks.of(x)
        assertEquals(Double.POSITIVE_INFINITY, a.rise(0.5))
        assertEquals(0.0, a.rise(0.2), 0.0)
    }

    @Test
    fun aRepeatedPitchWithoutAPickIsOneNote() {
        // Basic Pitch heard a held note twice (a second onset 0.4 s in, where nothing was picked) and a re-pick at 1.0 s.
        val x = FloatArray(fs * 2)
        val rnd = Random(2)
        for (i in x.indices) x[i] = (0.001 * rnd.nextGaussian()).toFloat() // a quiet room
        for (t in listOf(0.2, 1.0)) for (i in 0 until (0.006 * fs).toInt()) x[(t * fs).toInt() + i] += ((rnd.nextDouble() * 2 - 1) * 0.3).toFloat()
        val attacks = Attacks.of(x)
        fun heard(s: Double, e: Double, onset: Double = 0.9) = NoteEvent(s, e, 62, 0.6, onset = onset, salience = 0.75, frame = (s * 86).toInt())
        val notes = listOf(heard(0.2, 0.6), heard(0.6, 1.0, 0.7), heard(1.0, 1.6))
        val plain = SongAnalyzer.bouzoukiLine(notes, Instrument.TETRACHORDO)
        assertEquals(3, plain.size)
        val line = SongAnalyzer.bouzoukiLine(notes, Instrument.TETRACHORDO, attacks = attacks)
        assertEquals(listOf(0.2, 1.0), line.map { it.start }, "line: $line")
        assertEquals(1.0, line[0].end, 1e-9)
    }

    /** The audio of hand-made activations: a quiet room, and a click (of [level]) where each of [picks] starts. */
    private fun clicks(frames: Int, picks: List<Int>, level: (Int) -> Double = { 0.3 }): Attacks {
        val rnd = Random(3)
        val x = FloatArray((BasicPitch.frameTime(frames) * fs).toInt() + fs) { (0.001 * rnd.nextGaussian()).toFloat() }
        for (f in picks) {
            val at = ((BasicPitch.frameTime(f) + BasicPitch.ONSET_LATENCY) * fs).toInt()
            for (i in 0 until (0.006 * fs).toInt()) x[at + i] += ((rnd.nextDouble() * 2 - 1) * level(f)).toFloat()
        }
        return Attacks.of(x)
    }

    @Test
    fun aTrillComesBackToTheNoteRatherThanContinuingIt() {
        // D4 picked, then E4, D4, E4 by hammer-on and pull-off, with no attacks. Basic Pitch stretched the first D
        // across the short E, so the D after it starts where that one ends, as a re-detection would. But D stopped
        // sounding while E played: the line went to E and back, a trill, and keeps all four notes.
        fun at(f: Int) = BasicPitch.frameTime(f) + BasicPitch.ONSET_LATENCY
        fun heard(m: Int, f: Int, e: Int) = NoteEvent(at(f), at(e), m, 0.6, onset = 0.9, salience = 0.8, frame = f, endFrame = e)
        val notes = listOf(heard(62, 20, 40), heard(64, 30, 40), heard(62, 40, 50), heard(64, 50, 70))
        fun line(dHeld: Boolean): List<NoteEvent> {
            val frames = Array(100) { FloatArray(88) }
            for ((m, f, e) in listOf(Triple(62, 20, 30), Triple(64, 30, 40), Triple(62, 40, 50), Triple(64, 50, 70))) {
                for (t in f until e) frames[t][m - 21] = 0.8f
            }
            if (dHeld) for (t in 20 until 50) frames[t][62 - 21] = 0.8f
            val act = BasicPitch.Activations(frames, Array(100) { FloatArray(88) })
            return SongAnalyzer.bouzoukiLine(notes, Instrument.TETRACHORDO, act, clicks(100, listOf(20)))
        }
        val trill = line(dHeld = false)
        assertEquals(listOf(62, 64, 62, 64), trill.map { it.midi }, "line: $trill")
        // While D keeps sounding through the E (another voice passing by), the second D is still D heard again.
        assertTrue(line(dHeld = true).count { it.midi == 62 } == 1, "line: ${line(dHeld = true)}")
    }

    @Test
    fun articulationsFollowTheHandoverAndTheContour() {
        // Activations made by hand: D4 picked at frame 10, E4 by hammer-on at frame 40 (D fades as E takes over, no
        // attack), D4 picked again at frame 70, F4 at frame 100 reached by a slide (the contour glides), C5 a fifth
        // up at 130 without a pick but without a glide either, back down to F4 at 160 by a slide, then picks.
        val n = 320
        val frames = Array(n) { FloatArray(88) }
        val onsets = Array(n) { FloatArray(88) }
        val contours = Array(n) { ByteArray(BasicPitch.N_CONTOUR_BINS) }
        fun sound(midi: Int, from: Int, to: Int) {
            for (t in from until to) {
                frames[t][midi - 21] = 0.8f
                contours[t][3 * (midi - 21) + 1] = BasicPitch.quantize(0.8f)
            }
            onsets[from][midi - 21] = 0.95f
        }
        fun glide(from: Int, to: Int, frame: Int) { // the contour's peak moves from one pitch to the other
            val steps = 7
            for (k in 0 until steps) {
                val t = frame - steps + k
                contours[t][3 * (from - 21) + 1] = 0
                contours[t][3 * (from - 21) + 1 + (3 * (to - from) * (k + 1)) / steps] = BasicPitch.quantize(0.6f)
            }
        }
        val plan = listOf(62 to 10, 64 to 40, 62 to 70, 65 to 100, 72 to 130, 65 to 160, 64 to 190, 62 to 220, 64 to 250, 65 to 280)
        plan.forEachIndexed { i, (m, f) -> sound(m, f, if (i + 1 < plan.size) plan[i + 1].second else 310) }
        glide(62, 65, 100)
        glide(72, 65, 160)
        val act = BasicPitch.Activations(frames, onsets, contours)
        // Attacks: clicks at the picks only.
        val line = SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(act), Instrument.TETRACHORDO, act, clicks(n, listOf(10, 70, 190, 220, 250, 280)))
        assertEquals(plan.map { it.first }, line.map { it.midi }, "line: $line")
        val p = Articulation.PICK
        assertEquals(listOf(p, Articulation.HAMMER, p, Articulation.SLIDE, p, Articulation.SLIDE, p, p, p, p), line.map { it.articulation })
        // Without the attacks nothing can be told apart: all picked.
        assertTrue(SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(act), Instrument.TETRACHORDO, act).all { it.articulation == Articulation.PICK })
    }

    @Test
    fun aQuietPassageIsNotTakenForLegato() {
        // Stepwise notes, all with a handover the way legato has one: sixteen loud picks, then, after a pause, the
        // band drops 20 dB and eight quieter picks follow, one of them (frame 1100) a hammer-on with no attack. A
        // quiet pick adds far less energy than the song's typical note, but as much as the notes around it.
        val loud = (0 until 16).map { 40 + 20 * it }
        val quiet = (0 until 8).map { 1040 + 20 * it }
        val n = quiet.last() + 40
        val frames = Array(n) { FloatArray(88) }
        val onsets = Array(n) { FloatArray(88) }
        val contours = Array(n) { ByteArray(BasicPitch.N_CONTOUR_BINS) }
        val steps = listOf(62, 64, 65, 67, 65, 64)
        for (part in listOf(loud, quiet)) part.forEachIndexed { i, f ->
            val m = steps[i % steps.size]
            for (t in f until f + 20) { frames[t][m - 21] = 0.8f; contours[t][3 * (m - 21) + 1] = BasicPitch.quantize(0.8f) }
            onsets[f][m - 21] = 0.95f
        }
        val hammer = quiet[3]
        val attacks = clicks(n, loud + quiet.filter { it != hammer }) { f -> if (f < 1000) 0.3 else 0.03 }
        val act = BasicPitch.Activations(frames, onsets, contours)
        val line = SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(act), Instrument.TETRACHORDO, act, attacks)
        assertEquals(24, line.size, "line: $line")
        assertEquals(listOf(hammer), line.filter { it.articulation.isLegato }.map { it.frame }, "line: $line")
        assertEquals(Articulation.HAMMER, line.first { it.frame == hammer }.articulation)
    }

    @Test
    fun songsSavedBeforeArticulationsArePreparedAgain() {
        val a = SongAnalysis("id", "Σ", Instrument.TETRACHORDO, listOf(
            SongNote(1.0, 1.4, 62, 3, 0), SongNote(1.4, 1.8, 64, 3, 2, Articulation.HAMMER),
        ), Dromos.MATZORE, 2)
        val json = a.toJson()
        assertEquals(a.notes, assertNotNull(SongAnalysis.fromJson(json)).notes)
        assertEquals(SongAnalysis.Saved("id", "Σ", current = true), SongAnalysis.saved(json))
        // Version 2 (before articulations were detected) isn't read, so the app prepares the song again (it opens a
        // song from the cache only when this reads it), and the song list shows it as one to prepare again.
        val v2 = json.replace("\"version\":${SongAnalysis.FORMAT}", "\"version\":2")
        assertEquals(null, SongAnalysis.fromJson(v2))
        assertEquals(SongAnalysis.Saved("id", "Σ", current = false), SongAnalysis.saved(v2))
        // Newer than this version: not read either.
        assertEquals(null, SongAnalysis.fromJson(json.replace("\"version\":${SongAnalysis.FORMAT}", "\"version\":${SongAnalysis.FORMAT + 1}")))
    }
}
