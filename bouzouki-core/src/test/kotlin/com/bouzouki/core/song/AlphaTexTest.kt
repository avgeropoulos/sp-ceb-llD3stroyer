package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlphaTexTest {
    private fun song(inst: Instrument, seed: Long, notes: Int = 60): SongAnalysis {
        val rnd = java.util.Random(seed)
        var t = 0.37
        val list = ArrayList<SongNote>()
        val arts = Articulation.entries
        repeat(notes) {
            val c = rnd.nextInt(inst.courses.size)
            val f = rnd.nextInt(inst.frets + 1)
            val len = listOf(0.125, 0.25, 0.375, 0.5, 1.0, 1.7)[rnd.nextInt(6)]
            val a = if (rnd.nextDouble() < 0.25) arts[rnd.nextInt(arts.size)] else Articulation.PICK
            list += SongNote(t, t + len * 0.9, inst.midiAt(c, f), c, f, a)
            t += len + if (rnd.nextDouble() < 0.1) 0.6 else 0.0
        }
        return SongAnalysis("id", "Song \"$seed\"", inst, list, null, 2, beats = (0 until 200).map { 0.37 + it * 0.5 })
    }

    @Test
    fun writesTheTabAsAlphaTexOfFullBars() {
        val tex = AlphaTex.of(song(Instrument.TETRACHORDO, 1))
        assertTrue(tex.startsWith("\\title \"Song \\\"1\\\"\"\n\\tempo 120\n"), tex.take(80))
        assertTrue("\\tuning (D4 A3 F3 C3)" in tex, tex.take(200))
        assertEquals(60, Regex("(?<![-\\d])\\d+\\.\\d").findAll(tex).count(), "notes struck")
    }

    /** With ALPHATAB_NODE (a folder with @coderline/alphatab installed and check.mjs), alphaTab itself reads it back. */
    @Test
    fun alphaTabReadsItBack() {
        val dir = System.getenv("ALPHATAB_NODE")?.let { File(it) } ?: return
        for ((k, inst) in listOf(Instrument.TETRACHORDO, Instrument.TRICHORDO, Instrument.parseTuning("C3/C4 G3/G4 D4 A4 E5", 20)!!).withIndex()) {
            val s = song(inst, 10L + k)
            val f = Files.createTempFile("song", ".tex").toFile().apply { writeText(AlphaTex.of(s)) }
            val p = ProcessBuilder("node", "check.mjs", f.path).directory(dir).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), out)
            @Suppress("UNCHECKED_CAST")
            val r = MiniJson(out.trim()).parse() as Map<String, Any?>
            assertEquals(inst.courses.reversed().map { it.openMidi }, (r["tuning"] as List<*>).map { (it as Number).toInt() })
            for (b in r["bars"] as List<*>) assertTrue(abs((b as Number).toDouble() - 1.0) < 1e-9, "a bar of ${b}: not 4/4")
            val notes = (r["notes"] as List<*>).map { (it as List<*>).map { v -> (v as Number).toInt() } }
            assertEquals(s.notes.size, notes.size)
            for ((i, n) in s.notes.withIndex()) {
                val (string, fret, hammer, slide, tremolo) = notes[i]
                // (alphaTex numbers strings from the top, alphaTab's model from the bottom: string 1 is the lowest course.)
                assertEquals(n.course + 1, string, "note $i's string")
                assertEquals(n.fret, fret, "note $i's fret")
                val next = s.notes.getOrNull(i + 1)?.takeIf { it.course == n.course }?.articulation
                assertEquals(if (next == Articulation.HAMMER || next == Articulation.PULL) 1 else 0, hammer, "note $i hammer/pull")
                assertEquals(next == Articulation.SLIDE, slide != 0, "note $i slide")
                assertEquals(n.articulation == Articulation.TREMOLO, tremolo == 1, "note $i tremolo")
            }
            f.delete()
        }
    }
}
