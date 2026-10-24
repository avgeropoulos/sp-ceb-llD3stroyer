package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the bouzouki plays and in which register: hand-made activations of a production whose loudest parts are a
 * keyboard pad and a chord strummed again and again on the C and F courses' register, with a quieter picked melody, and
 * the audio of clicks where the plectrum picks (made-up phrases).
 */
class PresenceTest {
    private val fs = BasicPitch.SAMPLE_RATE
    private val tetra = Instrument.TETRACHORDO

    /** The audio of hand-made activations: a quiet room, and a click where each of [picks] (frames) starts. */
    private fun clicks(frames: Int, picks: List<Int>, level: Double = 0.3): Attacks {
        val rnd = Random(7)
        val x = FloatArray((BasicPitch.frameTime(frames) * fs).toInt() + fs) { (0.001 * rnd.nextGaussian()).toFloat() }
        for (f in picks) {
            val at = ((BasicPitch.frameTime(f) + BasicPitch.ONSET_LATENCY) * fs).toInt()
            for (i in 0 until (0.006 * fs).toInt()) x[at + i] += ((rnd.nextDouble() * 2 - 1) * level).toFloat()
        }
        return Attacks.of(x)
    }

    /** Activations made by hand: [n] frames. */
    private class Hand(n: Int) {
        val frames = Array(n) { FloatArray(88) }
        val onsets = Array(n) { FloatArray(88) }
        val contours = Array(n) { ByteArray(BasicPitch.N_CONTOUR_BINS) }

        /** [midi] sounding from frame [from] until [to] at [level], with an onset of [onset] at [from]. */
        fun sound(midi: Int, from: Int, to: Int, level: Float, onset: Float = 0.9f) {
            for (t in from until to) {
                frames[t][midi - 21] = maxOf(frames[t][midi - 21], level)
                contours[t][3 * (midi - 21) + 1] = BasicPitch.quantize(level)
            }
            onsets[from][midi - 21] = onset
        }

        val act get() = BasicPitch.Activations(frames, onsets, contours)
    }

    /**
     * A pad of held chords (E3 G#3 B3, then F#3 A3 C#4) struck again every [restrike] frames without a pick's click, from
     * frame 0 until [until]; its strikes as onsets of the chord's notes.
     */
    private fun pad(h: Hand, until: Int, restrike: Int = 43) {
        val chords = listOf(listOf(52, 56, 59), listOf(54, 57, 61))
        var f = 0
        var k = 0
        while (f < until) {
            val end = minOf(until, f + 4 * restrike)
            for (m in chords[k % 2]) {
                h.sound(m, f, end, 0.8f)
                for (s in f + restrike until end step restrike) h.onsets[s][m - 21] = 0.75f
            }
            f = end
            k++
        }
    }

    /** A quieter picked melody in D4-A4 from frame [from], a note every [every] frames: (frames picked, notes). */
    private fun melody(h: Hand, from: Int, until: Int, every: Int = 22): Pair<List<Int>, List<Int>> {
        val tune = listOf(62, 64, 65, 67, 69, 67, 65, 64)
        val picks = ArrayList<Int>()
        val notes = ArrayList<Int>()
        var i = 0
        for (f in from until until - every step every) {
            val m = tune[i++ % tune.size]
            h.sound(m, f, f + every - 4, 0.5f, 0.8f)
            picks += f
            notes += m
        }
        return picks to notes
    }

    private fun line(h: Hand, picks: List<Int>, detail: Detail = Detail.NORMAL) =
        SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(h.act, detail), tetra, h.act, clicks(h.frames.size, picks), detail)

    @Test
    fun anIntroWithoutTheBouzoukiHasNoNotes() {
        // Ten seconds of the pad alone, then the melody comes in over it for seven: the intro gets no notes (the
        // line followed the pad's strikes before), the melody all of its own, at every detail level.
        val n = 1500
        val start = 860 // 10 s
        val h = Hand(n)
        pad(h, n)
        val (picks, tune) = melody(h, start, n)
        for (d in Detail.entries) {
            val line = line(h, picks, d)
            val early = line.filter { it.start < BasicPitch.frameTime(start) - 3.0 }
            assertTrue(early.isEmpty(), "$d: notes in the intro: $early")
            val found = picks.indices.count { i -> line.any { it.midi == tune[i] && kotlin.math.abs(it.start - BasicPitch.frameTime(picks[i])) < 0.05 } }
            // (the fewer line leaves out its weakest notes: all of them are as weak here)
            if (d != Detail.FEWER) assertTrue(found >= picks.size * 0.9, "$d: melody found $found of ${picks.size}: $line")
        }
    }

    @Test
    fun theQuieterPickedMelodyWinsOverTheLouderChordsBelowIt() {
        // Chords on the C and F courses' register, strummed again and again with a plectrum (clicks), sound all through,
        // louder than the picked melody above them: the line is the melody, not the chords' strums.
        val n = 900
        val h = Hand(n)
        pad(h, n)
        val (picks, _) = melody(h, 40, n)
        val strums = (0 until n step 43).toList()
        val line = SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(h.act), tetra, h.act, clicks(n, (picks + strums).distinct().sorted()))
        val low = line.count { it.midi < 62 }
        // (a third of the line before; the chords' top notes on the A course, B3 and C#4, may still slip in)
        assertTrue(low <= line.size * 0.15, "notes under D4: $low of ${line.size}: $line")
        assertTrue(line.size >= picks.size * 0.9, "melody: ${line.size} of ${picks.size}")
    }

    @Test
    fun theLinesStillNestAndRebuildGivesTheSameLine() {
        val n = 1200
        val h = Hand(n)
        pad(h, n)
        val (picks, _) = melody(h, 500, n)
        val data = AnalysisData.of(h.act, clicks(n, picks))
        val lines = Detail.entries.associateWith { SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(data.act, it), tetra, data.act, data.attacks, it) }
        fun has(big: List<NoteEvent>, small: List<NoteEvent>) = small.all { s -> big.any { it.midi == s.midi && kotlin.math.abs(it.start - s.start) < 1e-6 } }
        assertTrue(has(lines.getValue(Detail.MORE), lines.getValue(Detail.NORMAL)), "MORE lost notes of NORMAL")
        assertTrue(has(lines.getValue(Detail.NORMAL), lines.getValue(Detail.FEWER)), "FEWER has notes NORMAL hasn't")
        val song = SongAnalyzer.build("id", "Σ", SongAnalyzer.candidates(data.act), tetra, data.act, data.attacks)
        assertEquals(song.notes, SongAnalyzer.rebuild(song, data).notes)
    }

    @Test
    fun aQuietSoloWithClearPicksIsStillPlayed() {
        // The band drops out and the bouzouki goes on alone and quietly (a tenth of the level), its picks still sharp
        // against the quiet: the line keeps it.
        val n = 1000
        val h = Hand(n)
        val (loud, _) = melody(h, 20, 480)
        val (quiet, tune) = melody(h, 500, n)
        val rnd = Random(9)
        val x = FloatArray((BasicPitch.frameTime(n) * fs).toInt() + fs) { (0.001 * rnd.nextGaussian()).toFloat() }
        for (f in loud + quiet) {
            val at = ((BasicPitch.frameTime(f) + BasicPitch.ONSET_LATENCY) * fs).toInt()
            val level = if (f < 500) 0.3 else 0.03
            for (i in 0 until (0.006 * fs).toInt()) x[at + i] += ((rnd.nextDouble() * 2 - 1) * level).toFloat()
        }
        val line = SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(h.act), tetra, h.act, Attacks.of(x))
        val found = quiet.indices.count { i -> line.any { it.midi == tune[i] && kotlin.math.abs(it.start - BasicPitch.frameTime(quiet[i])) < 0.05 } }
        assertTrue(found >= quiet.size * 0.9, "quiet passage: $found of ${quiet.size}")
    }
}
