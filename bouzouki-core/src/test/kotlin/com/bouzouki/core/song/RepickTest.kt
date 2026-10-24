package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The line where the plectrum picks one pitch again and again (tremolo, repeated sixteenths, picked triplets), where a
 * held note hides a re-pick or a quick turn, and where the band strums: hand-made activations, with the audio of
 * clicks where the picks are (made-up phrases).
 */
class RepickTest {
    private val fs = BasicPitch.SAMPLE_RATE
    private val tetra = Instrument.TETRACHORDO

    /** The audio of hand-made activations: a quiet room, and a click where each of [picks] (frames) starts. */
    private fun clicks(frames: Int, picks: List<Int>, level: Double = 0.3): Attacks {
        val rnd = Random(5)
        val x = FloatArray((BasicPitch.frameTime(frames) * fs).toInt() + fs) { (0.001 * rnd.nextGaussian()).toFloat() }
        for (f in picks) {
            val at = ((BasicPitch.frameTime(f) + BasicPitch.ONSET_LATENCY) * fs).toInt()
            for (i in 0 until (0.006 * fs).toInt()) x[at + i] += ((rnd.nextDouble() * 2 - 1) * level).toFloat()
        }
        return Attacks.of(x)
    }

    /** Activations made by hand: [n] frames, notes drawn into them with [sound]. */
    private class Hand(n: Int) {
        val frames = Array(n) { FloatArray(88) }
        val onsets = Array(n) { FloatArray(88) }
        val contours = Array(n) { ByteArray(BasicPitch.N_CONTOUR_BINS) }

        /** [midi] sounding from frame [from] until [to] at [level], with an onset of [onset] at [from]. */
        fun sound(midi: Int, from: Int, to: Int, level: Float = 0.75f, onset: Float = 0.95f) {
            for (t in from until to) {
                frames[t][midi - 21] = level
                contours[t][3 * (midi - 21) + 1] = BasicPitch.quantize(level)
            }
            onsets[from][midi - 21] = onset
        }

        val act get() = BasicPitch.Activations(frames, onsets, contours)
    }

    private fun line(h: Hand, picks: List<Int>, detail: Detail = Detail.NORMAL) =
        SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(h.act, detail), tetra, h.act, clicks(h.frames.size, picks), detail)

    @Test
    fun fastEvenPicksOfOnePitchAreTremolo() {
        // E4 picked 17 times a second for a second (a pick every 5 frames), then G4 and A4 picked once each.
        val h = Hand(220)
        val picks = (20 until 110 step 5).toList()
        h.sound(64, 20, 110)
        for (f in picks.drop(1)) h.onsets[f][64 - 21] = 0.6f // Basic Pitch hears most re-picks, weaker
        h.sound(67, 110, 150)
        h.sound(69, 150, 200)
        val line = line(h, picks + listOf(110, 150))
        assertEquals(listOf(64, 67, 69), line.map { it.midi }, "line: $line")
        assertEquals(Articulation.TREMOLO, line[0].articulation)
        assertEquals(BasicPitch.frameTime(20), line[0].start, 0.02)
        assertTrue(line[0].end >= BasicPitch.frameTime(105), "held to its last picks: ${line[0]}")
        // Every detail level shows it as tremolo, and the fuller line keeps it.
        for (d in Detail.entries) assertEquals(Articulation.TREMOLO, line(h, picks + listOf(110, 150), d)[0].articulation)
    }

    @Test
    fun repeatedSixteenthsAndPickedTripletsStaySeparateNotes() {
        // D5 picked eight times, a sixteenth (10 frames, 116 ms) apart, then a picked triplet C5 C5 C5 (6 frames,
        // 70 ms apart) and a held A4: each pick is a note of its own, none of it tremolo.
        val h = Hand(220)
        val sixteenths = (20 until 100 step 10).toList()
        for ((i, f) in sixteenths.withIndex()) h.sound(74, f, f + 10, onset = if (i % 2 == 0) 0.95f else 0.8f)
        val triplet = listOf(100, 106, 112)
        for (f in triplet) h.sound(72, f, f + 6)
        h.sound(69, 118, 200)
        val line = line(h, sixteenths + triplet + 118)
        assertEquals(List(8) { 74 } + List(3) { 72 } + 69, line.map { it.midi }, "line: $line")
        assertTrue(line.all { it.articulation == Articulation.PICK }, "line: $line")
    }

    @Test
    fun aPickHeardOnlyAsAWeakOnsetSplitsTheHeldNote() {
        // E4 picked at frame 20 and again at frame 50 while it still sounds: Basic Pitch gives the second pick an
        // onset of 0.45 only (under the 0.5 a candidate needs), so it heard one long note. The attack says otherwise.
        val h = Hand(120)
        h.sound(64, 20, 90)
        for (t in 21 until 90) h.onsets[t][64 - 21] = 0.1f
        h.onsets[50][64 - 21] = 0.45f
        val split = line(h, listOf(20, 50))
        assertEquals(listOf(20, 50), split.map { it.frame }, "line: $split")
        assertEquals(listOf(64, 64), split.map { it.midi })
        // Without a pick there, the bump is the note swelling (a bend, vibrato): one note.
        val held = line(h, listOf(20))
        assertEquals(listOf(20), held.map { it.frame }, "line: $held")
    }

    @Test
    fun aQuickTurnComesOutAsHammerOnAndPullOff() {
        // Five picked notes, then D4 picked at frame 120, E4 hammered on at 125 and D4 pulled back at 129, held: a
        // mordent. D fades a little under the E, which has a clear onset but no attack.
        val h = Hand(220)
        val run = listOf(67, 65, 64, 65, 64)
        run.forEachIndexed { i, m -> h.sound(m, 20 + 20 * i, 40 + 20 * i) }
        h.sound(62, 120, 125)
        h.sound(64, 125, 129, onset = 0.9f)
        for (t in 125 until 129) h.frames[t][62 - 21] = 0.4f
        h.sound(62, 129, 200, onset = 0.9f)
        val line = line(h, (0 until 5).map { 20 + 20 * it } + 120)
        assertEquals(run + listOf(62, 64, 62), line.map { it.midi }, "line: $line")
        assertEquals(listOf(Articulation.PICK, Articulation.HAMMER, Articulation.PULL), line.takeLast(3).map { it.articulation }, "line: $line")
    }

    @Test
    fun vibratoAndBendsMakeNoTurn() {
        // A held D4 whose contour wobbles a third of a semitone either way, then bends two thirds up and back:
        // still one note, with nothing hidden in it.
        val h = Hand(220)
        h.sound(62, 20, 200)
        val c = 3 * (62 - 21) + 1
        for (t in 30 until 200) {
            h.contours[t][c] = 0
            val off = when {
                t in 120..135 -> 2 // the bend
                (t / 4) % 2 == 0 -> 1
                else -> -1
            }
            h.contours[t][c + off] = BasicPitch.quantize(0.75f)
            if (t in 120..135) h.frames[t][63 - 21] = 0.3f // the bend leaks into the semitone above
        }
        val line = line(h, listOf(20))
        assertEquals(listOf(62), line.map { it.midi }, "line: $line")
        assertEquals(Articulation.PICK, line[0].articulation)
    }

    @Test
    fun aSongWithAnOlderLineGetsTheNewOneFromItsData() {
        // A song prepared before the line version was saved reads as line 0: old, so it is derived again from its data.
        val h = Hand(120)
        h.sound(62, 20, 60)
        h.sound(64, 60, 100)
        val data = AnalysisData.of(h.act, clicks(120, listOf(20, 60)))
        val song = SongAnalyzer.build("id", "Σ", SongAnalyzer.candidates(data.act), tetra, data.act, data.attacks)
        assertEquals(SongAnalysis.LINE, song.line)
        val json = song.toJson()
        assertEquals(SongAnalysis.LINE, assertNotNull(SongAnalysis.fromJson(json)).line)
        val old = assertNotNull(SongAnalysis.fromJson(json.replace(",\"line\":${SongAnalysis.LINE}", "")))
        assertEquals(0, old.line)
        assertTrue(SongAnalysis.isLineOld(old))
        val fresh = SongAnalyzer.rebuild(old, data)
        assertEquals(SongAnalysis.LINE, fresh.line)
        assertTrue(!SongAnalysis.isLineOld(fresh))
        assertEquals(song.notes, fresh.notes)
    }

    @Test
    fun aStrummedChordGivesWayToTheLoneNote() {
        // Two candidates as strong as each other start together: A4 alone, and D5 with two louder strings (lower, out of
        // the tune's way) within 20 ms, a strum it is a string of. The melody before and after is as near to both; the
        // line takes the lone note. The loudest note of a strum pays nothing: the melody played with the band's chord.
        fun heard(s: Double, e: Double, m: Int, sal: Double = 0.75) = NoteEvent(s, e, m, 0.6, onset = 0.9, salience = sal)
        val notes = listOf(
            heard(0.0, 0.47, 71),
            heard(0.47, 1.0, 69), heard(0.5, 1.0, 74), heard(0.51, 1.0, 50, 0.9), heard(0.52, 1.0, 57, 0.9),
            heard(1.0, 1.5, 72),
        )
        val line = SongAnalyzer.bouzoukiLine(notes, tetra)
        assertEquals(listOf(71, 69, 72), line.map { it.midi }, "line: $line")
        // The melody's own note as the loudest of a strum stays in the line.
        val withChord = listOf(heard(0.0, 0.5, 71), heard(0.5, 1.0, 74, 0.9), heard(0.51, 1.0, 62), heard(0.52, 1.0, 66), heard(1.0, 1.5, 72))
        assertEquals(listOf(71, 74, 72), SongAnalyzer.bouzoukiLine(withChord, tetra).map { it.midi })
    }
}
