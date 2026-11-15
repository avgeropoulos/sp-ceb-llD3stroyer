package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A band's recording against the bouzouki alone ([PostConfig.solo]): what the line leaves out as the band's (a string of
 * a strum, what it would follow while the bouzouki rests), and how sure it says it is of each note
 * ([NoteEvent.confidence]). Activations made by hand, with the energy each note's attack adds set by hand too.
 */
class BandLineTest {
    private val tetra = Instrument.TETRACHORDO
    private val solo = PostConfig(solo = true)

    /** Activations made by hand: 1100 frames (12.8 s). */
    private class Hand {
        val frames = Array(1100) { FloatArray(BasicPitch.N_KEYS) }
        val onsets = Array(1100) { FloatArray(BasicPitch.N_KEYS) }

        /** [midi] struck at [seconds], sounding 15 frames at [level] with an onset of [onset]: a clear note as the bouzouki's are, by default. */
        fun note(midi: Int, seconds: Double, level: Float = 0.6f, onset: Float = 0.8f) {
            val f = (seconds * BasicPitch.ANNOTATIONS_FPS).roundToInt()
            for (t in f until f + 15) frames[t][midi - BasicPitch.MIDI_OFFSET] = level
            onsets[f][midi - BasicPitch.MIDI_OFFSET] = onset
        }

        /** A string struck at [seconds] that doesn't sound long enough to be a candidate: only its onset shows. */
        fun strike(midi: Int, seconds: Double) {
            onsets[(seconds * BasicPitch.ANNOTATIONS_FPS).roundToInt()][midi - BasicPitch.MIDI_OFFSET] = 0.8f
        }

        val act get() = BasicPitch.Activations(frames, onsets)
    }

    /** No sharp attacks anywhere, and the energy each candidate adds as it starts: [faint] times a clear note's for those under its level. */
    private fun attacks(act: BasicPitch.Activations, faint: Double): Attacks {
        val n = (BasicPitch.frameTime(act.frames.size) * Attacks.SAMPLE_RATE / Attacks.HOP).toInt() + 8
        val energies = FloatArray(n)
        for (x in SongAnalyzer.candidates(act)) {
            val i = ((x.start * Attacks.SAMPLE_RATE - Attacks.FFT / 2) / Attacks.HOP).roundToInt()
            for (j in i - 1..i + 1) energies[j] = if (x.salience >= 0.59) 1f else faint.toFloat()
        }
        return Attacks(FloatArray(n) { 1f }, energies)
    }

    private fun line(act: BasicPitch.Activations, attacks: Attacks, detail: Detail = Detail.NORMAL, post: PostConfig = PostConfig.DEFAULT) =
        SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(act, detail), tetra, act, attacks, detail, post)

    private fun has(line: List<NoteEvent>, n: NoteEvent) = line.any { it.midi == n.midi && abs(it.start - n.start) < 1e-6 }

    private val tune = listOf(64, 66, 67, 69, 67, 66, 64, 66)

    /**
     * Two phrases of eight notes (from 0.25 s and from 8 s), all clear but the fifth of the first (at 1.25 s), and three
     * faint notes in the six seconds between them.
     */
    private fun twoPhrases() = Hand().also { h ->
        tune.forEachIndexed { i, m -> h.note(m, 0.25 + 0.25 * i, level = if (i == 4) 0.5f else 0.6f); h.note(m, 8.0 + 0.25 * i) }
        for ((m, t) in listOf(65 to 4.0, 64 to 5.0, 65 to 6.0)) h.note(m, t, level = 0.5f)
    }.act

    /** The faint note inside the first phrase of [twoPhrases]. */
    private fun faintInPhrase(n: NoteEvent) = n.start in 1.2..1.3

    @Test
    fun whileTheBouzoukiRestsABandsLineRestsToo() {
        val act = twoPhrases()
        val attacks = attacks(act, faint = 0.4)
        // The bouzouki alone: the tracker takes every candidate, the faint ones between the phrases too.
        val alone = line(act, attacks, post = solo)
        assertEquals(19, alone.size, "solo: $alone")
        assertEquals(listOf(65, 64, 65), alone.filter { it.start in 3.0..7.0 }.map { it.midi }, "solo: $alone")
        // In a band those have none of the bouzouki's notes around them: more likely the band's, and left out. The
        // faint note inside the phrase stays, and so does every clear note.
        val band = line(act, attacks)
        assertEquals(alone.filter { it.start !in 3.0..7.0 }.map { it.midi }, band.map { it.midi }, "band: $band")
        assertTrue(band.any { faintInPhrase(it) }, "band: $band")
        // The other detail levels judge by the same notes, so the lines nest.
        val more = line(act, attacks, Detail.MORE)
        val fewer = line(act, attacks, Detail.FEWER)
        assertTrue(band.all { has(more, it) }, "MORE lost notes of NORMAL: $more")
        assertTrue(fewer.all { has(band, it) }, "FEWER has notes NORMAL hasn't: $fewer")
        assertTrue(more.none { it.start in 3.0..7.0 }, "MORE between the phrases: $more")
    }

    @Test
    fun aSlowMelodyOfClearNotesStays() {
        // Clear notes two seconds apart: none has another within reach, and each stays for what it brings itself.
        val h = Hand()
        tune.take(5).forEachIndexed { i, m -> h.note(m, 0.5 + 2.0 * i) }
        val slow = line(h.act, attacks(h.act, faint = 0.4))
        assertEquals(tune.take(5), slow.map { it.midi }, "line: $slow")
    }

    @Test
    fun aStringStruckWithAnotherAboveItIsTheBandsStrum() {
        // Between the melody's notes (E4 and F#4 in turn), a weaker F4 each time: once on its own, once with the onset
        // of its own octave (its harmonic), and twice with a C5 struck at the same moment (a strum, of which only the
        // F4 sounds long enough to be a candidate).
        val h = Hand()
        for (i in 0 until 10) h.note(if (i % 2 == 0) 64 else 66, 0.25 + 0.5 * i)
        for (t in listOf(0.5, 1.5, 2.5, 3.5)) h.note(65, t, level = 0.5f, onset = 0.6f)
        h.strike(77, 1.5)
        h.strike(72, 2.5)
        h.strike(72, 3.5)
        val attacks = attacks(h.act, faint = 1.0)
        fun f4s(line: List<NoteEvent>) = line.filter { it.midi == 65 }.map { Math.round(it.start * 2) / 2.0 }
        val alone = line(h.act, attacks, post = solo)
        assertEquals(listOf(0.5, 1.5, 2.5, 3.5), f4s(alone), "solo: $alone")
        val band = line(h.act, attacks)
        assertEquals(listOf(0.5, 1.5), f4s(band), "band: $band")
        assertEquals(10, band.count { it.midi != 65 }, "band: $band")
        // The bouzouki alone keeps the strummed ones, and says it is not sure of them.
        val sure = alone.filter { it.midi == 65 }.map { it.confidence }
        assertTrue(sure[2] < sure[0] && sure[3] < sure[0] && sure[1] == sure[0], "solo: $alone")
    }

    @Test
    fun everyNoteSaysHowSureTheLineIsOfIt() {
        val act = twoPhrases()
        val attacks = attacks(act, faint = 0.4)
        val normal = line(act, attacks)
        for (detail in Detail.entries) {
            val line = line(act, attacks, detail)
            assertTrue(line.isNotEmpty() && line.all { it.confidence in 0f..1f }, "$detail: $line")
            // A clear note among the bouzouki's is surer than a faint one, and a note is as sure at every detail.
            val clear = line.filter { !faintInPhrase(it) }
            assertTrue(clear.all { it.confidence >= 0.4f }, "$detail: $line")
            line.firstOrNull { faintInPhrase(it) }?.let { f -> assertTrue(clear.all { it.confidence > f.confidence }, "$detail: $line") }
            for (n in line) normal.firstOrNull { it.midi == n.midi && it.start == n.start }?.let { assertEquals(it.confidence, n.confidence, "$detail: $n") }
        }
        // The bouzouki alone keeps the faint notes between the phrases, with no confidence in them.
        val alone = line(act, attacks, post = solo)
        assertTrue(alone.filter { it.start in 3.0..7.0 }.let { it.size == 3 && it.all { n -> n.confidence == 0f } }, "solo: $alone")
        // A line chosen before (no onset evidence) is not in doubt, nor is a tremolo.
        val given = SongAnalyzer.bouzoukiLine(normal.map { NoteEvent(it.start, it.end, it.midi, 0.5) }, tetra, act, attacks)
        assertTrue(given.size == normal.size && given.all { it.confidence == 1f }, "given: $given")
        val picked = Hand().also { h -> for (i in 0 until 12) h.note(69, 0.5 + 0.08 * i); tune.take(4).forEachIndexed { i, m -> h.note(m, 2.0 + 0.25 * i) } }.act
        val tremolo = SongAnalyzer.bouzoukiLine(SongAnalyzer.candidates(picked), tetra)
        assertEquals(Articulation.TREMOLO, tremolo.first().articulation, "tremolo: $tremolo")
        assertEquals(1f, tremolo.first().confidence)
        // The song keeps each note's confidence: saved and read back, and derived again from its saved data.
        val data = AnalysisData.of(act, attacks)
        val song = SongAnalyzer.build("id", "t", SongAnalyzer.candidates(data.act), tetra, data.act, data.attacks)
        assertTrue(song.notes.any { it.confidence < 1f } && song.notes.any { it.confidence > 0.4f }, "song: ${song.notes}")
        assertEquals(song.notes.map { it.confidence }, assertNotNull(SongAnalysis.fromJson(song.toJson())).notes.map { it.confidence })
        assertEquals(song.notes, SongAnalyzer.rebuild(song, data).notes)
    }
}
