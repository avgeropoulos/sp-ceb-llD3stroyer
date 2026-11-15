package com.bouzouki.core.eval

import com.bouzouki.core.Instrument
import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.SongNote
import com.bouzoukihero.core.Difficulty
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
    fun saysHowWellTheConfidenceRanksTheNotes() {
        val item = EvalItem("x", File("x.wav"), emptyList(), Instrument.TETRACHORDO, "mix", "x", null)
        fun found(right: Boolean, confidence: Float) = ErrorAnalysis.Found(item, note(0.0, 62).copy(confidence = confidence), right, null, "", emptyList(), "")
        // The wrong notes are the least sure ones: a perfect ranking.
        val ranked = listOf(found(true, 1f), found(true, 0.8f), found(true, 0.5f), found(false, 0.3f), found(false, 0f))
        assertEquals(1.0, ErrorAnalysis.averagePrecision(ranked), 1e-9)
        assertEquals(1.0, ErrorAnalysis.ranksAbove(ranked), 1e-9)
        // Every note as sure: no ranking at all, and the area is about the share that is right.
        val flat = ranked.map { found(it.right, 1f) }
        assertEquals(0.5, ErrorAnalysis.ranksAbove(flat), 1e-9)
        assertTrue(abs(ErrorAnalysis.averagePrecision(flat) - 0.6) < 0.05, "${ErrorAnalysis.averagePrecision(flat)}")
        // The wrong notes the surest: the worst ranking.
        val upsideDown = listOf(found(false, 1f), found(false, 0.9f), found(true, 0.5f), found(true, 0.2f), found(true, 0.1f))
        assertEquals(0.0, ErrorAnalysis.ranksAbove(upsideDown), 1e-9)
        assertEquals((1.0 / 3 + 2.0 / 4 + 3.0 / 5) / 3, ErrorAnalysis.averagePrecision(upsideDown), 1e-9)
        assertTrue("| 0.50 | 0 | 0.0% | 2 | 100.0% |" in ErrorAnalysis.confidence(ranked), ErrorAnalysis.confidence(ranked))
    }

    @Test
    fun scoresTheLineAsTheGamePlaysIt() {
        val inst = Instrument.TETRACHORDO
        // Twelve notes a second apart from 2 s on, then nothing up to 30 s.
        val ref = (0 until 12).map { RefNote(2.0 + it, 2.2 + it, 62 + it % 5, 3, it % 5) }
        // Found: all of them but the fifth, the second 60 ms late, and two notes of the band's, in doubt: one between
        // two of the bouzouki's, one long after its last.
        val est = (ref.filterIndexed { i, _ -> i != 4 }.mapIndexed { i, r -> note(r.start + if (i == 1) 0.06 else 0.0, r.midi) } +
            note(8.5, 60, 2, 3).copy(confidence = 0.05f) + note(20.0, 60, 2, 3).copy(confidence = 0.05f)).sortedBy { it.start }
        val all = GameScore.of(ref, est, inst, 30.0, Difficulty.EXPERT, 0f)
        assertEquals(listOf(13, 11, 1), listOf(all.gems, all.hit, all.phantom))
        assertEquals(listOf(12, 11), listOf(all.ideal, all.covered))
        assertEquals(11.0 / 13, all.precision, 1e-9)
        assertEquals(11.0 / 12, all.recall, 1e-9)
        // Silent: the first second, and from a second after the last note ends.
        assertEquals(1.0 + (30.0 - 14.2), all.silent, 1e-9)
        assertEquals(60.0 / 16.8, all.phantomsAMinute, 1e-9)
        // A floor leaves the two in doubt out; every difficulty's own floor does, and "no floor" doesn't.
        val sure = GameScore.of(ref, est, inst, 30.0, Difficulty.EXPERT, 0.2f)
        assertEquals(listOf(11, 11, 0), listOf(sure.gems, sure.hit, sure.phantom))
        val result = GameScore.of(ref, est, inst, 30.0)
        for (d in Difficulty.entries) {
            assertEquals(1.0, result.game.getValue(d).precision, 1e-9, "$d")
            assertEquals(13, result.noFloor.getValue(d).gems, "$d")
            assertEquals(13, GameScore.of(ref, est, inst, 30.0, d, GameScore.BEFORE).gems, "$d")
        }
        // A line prepared as the bouzouki alone is played whole: there is no band to leave out.
        assertEquals(13, GameScore.of(ref, est, inst, 30.0, Difficulty.EASY, 0.4f, solo = true).gems)
        assertEquals(13, GameScore.of(ref, est, inst, 30.0, solo = true).game.getValue(Difficulty.EASY).gems)
        // A reference that doesn't say where its notes are played makes the same chart.
        val bare = ref.map { RefNote(it.start, it.end, it.midi) }
        assertEquals(12, GameScore.of(bare, est, inst, 30.0, Difficulty.EASY, 0f).ideal)

        // The report and the CSV carry it, and a run's dump read back scores the same.
        val item = EvalItem("x-1", File("x-1.wav"), ref, inst, "mix", "x", null)
        val run = ItemResult(item, Metrics.score(ref, est), Timings(), 30.0, 0.0, 0.0, ErrorAnalysis.of(item, ref, est, null, 30.0), null, result)
        val md = Evaluator.markdown("t", listOf(run))
        assertTrue("## Bouzouki Hero" in md && "| Expert | 0.10 | 11 | 12 | 0.957 | 1.000 | 0.917 | 0 | 0.0 |" in md, md)
        assertTrue("| Expert | none | 13 | 12 |" in md, md)
        val dir = Files.createTempDirectory("gamescore").toFile()
        val csv = Evaluator.csv("t", listOf(run))
        assertEquals(csv.lines()[0].split(',').size, csv.lines()[1].split(',').size)
        assertTrue(csv.lines()[0].endsWith("game_expert_gems,game_expert_hit,game_expert_phantom,game_expert_ideal,game_expert_covered"), csv)
        assertTrue(csv.lines()[1].endsWith(",11,11,0,12,11"), csv)
        File(dir, "t.csv").writeText(csv)
        File(dir, "t.notes.csv").writeText(ErrorAnalysis.csv(listOf(run.notes!!)))
        val back = GameScore.read(File(dir, "t.notes.csv"), File(dir, "t.csv"))
        assertEquals(1, back.size)
        assertEquals(est.map { it.confidence }, back[0].found.map { it.confidence })
        assertEquals(listOf(ref.size, est.size), listOf(back[0].played.size, back[0].found.size))
        val table = GameScore.compare(back, listOf(GameScore.BEFORE, 0f, 0.2f))
        assertTrue("| Expert | none | 13 | 12 | 0.880 | 0.846 | 0.917 | 1 | 3.6 |" in table, table)
        assertTrue("| Expert | 0.10 (the game's) | 11 | 12 |" in table && "| Expert | confidence not used | 13 | 12 |" in table, table)
        dir.deleteRecursively()
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
