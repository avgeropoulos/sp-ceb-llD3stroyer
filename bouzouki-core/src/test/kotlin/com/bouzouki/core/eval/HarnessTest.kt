package com.bouzouki.core.eval

import com.bouzouki.core.Instrument
import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.SongNote
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarnessTest {
    private fun note(t: Double, midi: Int, course: Int = 3, fret: Int = midi - 62, a: Articulation = Articulation.PICK) =
        SongNote(t, t + 0.2, midi, course, fret, a)

    @Test
    fun scoresNotesOneToOne() {
        val ref = listOf(RefNote(0.0, 0.2, 62, 3, 0), RefNote(0.5, 0.7, 64, 3, 2), RefNote(1.0, 1.2, 66, 3, 4), RefNote(1.5, 1.7, 48, 0, 0))
        val est = listOf(
            note(0.01, 62),            // right
            note(0.02, 62),            // the same note twice: one of them only
            note(0.53, 64, 2, 7),      // right pitch, another course
            note(1.2, 66),             // too late
            note(1.51, 60, 0, 12),     // an octave up
        )
        val s = Metrics.score(ref, est)
        assertEquals(2, s.tp)
        assertEquals(3, s.tpOctave)
        assertEquals(2.0 * 2 / 9, s.note, 1e-9)
        assertEquals(0.5, s.course, 1e-9)
    }

    @Test
    fun findsWhereAClickPlanStarts() {
        val ref = (0 until 16).map { RefNote(it * 0.5, it * 0.5 + 0.4, 62 + it % 5) }
        val est = ref.map { note(it.start + 2.345, it.midi) }
        assertTrue(abs(Metrics.bestOffset(ref, est) - 2.345) < 0.002)
    }

    @Test
    fun readsWhatItWrites() {
        val dir = Files.createTempDirectory("evalset").toFile()
        val notes = listOf(RefNote(0.25, 0.5, 62, 3, 0, Articulation.TREMOLO))
        EvalSet.writeRef(File(dir, "x-1.ref.csv"), notes, mapOf("instrument" to "TRICHORDO", "kind" to "mix"))
        Wav.write16(File(dir, "x-1.wav"), arrayOf(FloatArray(4410) { 0.5f * kotlin.math.sin(it * 0.1f) }), 44100)
        File(dir, "y-1.plan.csv").writeText("# bpm=120\n# offset=1.0\n0,1,2,3\n2,0.5,1,0,PICK\n")
        Wav.write16(File(dir, "y-1.wav"), arrayOf(FloatArray(10)), 22050)
        val items = EvalSet.load(dir)
        assertEquals(listOf("x-1", "y-1"), items.map { it.name })
        assertEquals(notes.map { it.copy(start = 0.25, end = 0.5) }, items[0].ref)
        assertEquals(Instrument.TRICHORDO, items[0].instrument)
        assertEquals("mix", items[0].kind)
        val plan = items[1].ref
        assertEquals(listOf(1.0, 2.0), plan.map { it.start })
        assertEquals(listOf(Instrument.TETRACHORDO.midiAt(2, 3), Instrument.TETRACHORDO.midiAt(1, 0)), plan.map { it.midi })
        val wav = Wav.read(File(dir, "x-1.wav"))
        assertEquals(4410, wav.channels[0].size)
        assertTrue(abs(wav.channels[0][10] - 0.5f * kotlin.math.sin(1f)) < 1e-3)
        dir.deleteRecursively()
    }

    /** The whole pipeline on a small synthetic set: a floor that catches a pipeline gone badly wrong. */
    @Test
    fun transcribesTheSmallSynthSet() {
        val dir = Files.createTempDirectory("synthset").toFile()
        SynthSet.write(dir, small = true)
        val items = EvalSet.load(dir).filter { it.kind == "solo" }
        val results = AppPipeline.withBasicPitch { bp -> Evaluator.run(items, AppPipeline(bp)) { println(it) } }
        val all = Score().also { s -> results.forEach { s.add(it.score) } }
        println(all.summary())
        assertTrue(all.note > 0.6, all.summary())
        dir.deleteRecursively()
    }
}
