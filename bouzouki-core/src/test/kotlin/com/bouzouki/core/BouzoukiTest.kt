package com.bouzouki.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DromosTest {
    @Test
    fun titlesPutTheGreekNameFirstAndTheEnglishInParentheses() {
        assertEquals("Hitzaz (Phrygian Dominant)", Dromos.HITZAZ.title)
        assertEquals("Nihavent (Harmonic Minor)", Dromos.NIHAVENT.title)
    }

    @Test
    fun everyDromosHasSevenAscendingDegreesFromTheRoot() {
        for (d in Dromos.entries) {
            assertEquals(7, d.semitones.size, d.name)
            assertEquals(0, d.semitones[0], d.name)
            assertTrue(d.semitones.toList().zipWithNext().all { (a, b) -> b > a && b < 12 }, d.name)
        }
    }

    @Test
    fun hitzazOnDIsSpelledWithOneLetterPerDegree() {
        assertEquals(listOf("D", "E♭", "F♯", "G", "A", "B♭", "C"), Dromos.HITZAZ.spell(2, NoteNames.WESTERN))
        assertEquals(listOf("1", "♭2", "3", "4", "5", "♭6", "♭7"), Dromos.HITZAZ.degreeLabels())
        assertEquals("Ρε", NoteNames.GREEK.name(62))
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G♯"), Dromos.NIHAVENT.spell(9, NoteNames.WESTERN))
    }

    @Test
    fun spellingNeverProducesDoubleAccidentals() {
        for (d in Dromos.entries) for (root in 0 until 12) for (n in NoteNames.entries) {
            val names = d.spell(root, n)
            assertTrue(names.none { "𝄪" in it || "𝄫" in it }, "$d on $root: $names")
        }
    }

    @Test
    fun scaleMembership() {
        assertTrue(Dromos.HITZAZ.contains(2, 63))  // E♭ in D Hitzaz
        assertTrue(!Dromos.HITZAZ.contains(2, 64)) // E is not
    }
}

class InstrumentTest {
    @Test
    fun tunings() {
        assertEquals(listOf(50, 57, 62), Instrument.TRICHORDO.courses.map { it.openMidi })
        assertEquals(listOf(48, 53, 57, 62), Instrument.TETRACHORDO.courses.map { it.openMidi })
    }

    @Test
    fun positionFindsAPlayableCourse() {
        val (course, fret) = assertNotNull(Instrument.TETRACHORDO.position(62))
        assertEquals(62, Instrument.TETRACHORDO.midiAt(course, fret))
        assertEquals(null, Instrument.TRICHORDO.position(40))
    }
}

class SynthTest {
    private val fs = 48000

    private fun renderNote(midi: Int, octavePair: Boolean, seconds: Double = 1.0): FloatArray {
        val synth = BouzoukiSynth(fs)
        synth.pluck(0, midi, octavePair, 0.9f)
        val out = FloatArray((fs * seconds).toInt())
        var i = 0
        while (i < out.size) { val n = minOf(256, out.size - i); synth.render(out, i, n); i += n }
        return out
    }

    /** Fundamental by autocorrelation over a window after the attack. */
    private fun pitch(x: FloatArray, lo: Double, hi: Double): Double {
        val start = fs / 5
        val len = fs / 4
        var best = 0; var bestR = Double.NEGATIVE_INFINITY
        for (lag in (fs / hi).toInt()..(fs / lo).toInt()) {
            var r = 0.0
            for (i in start until start + len) r += x[i] * x[i + lag]
            if (r > bestR) { bestR = r; best = lag }
        }
        return fs.toDouble() / best
    }

    private fun centsOff(f: Double, target: Double) = 1200 * ln(f / target) / ln(2.0)

    @Test
    fun unisonCourseIsInTune() {
        val f = pitch(renderNote(62, octavePair = false), 200.0, 400.0)
        assertTrue(abs(centsOff(f, midiToHz(62))) < 12, "D4 measured $f Hz")
    }

    @Test
    fun highFretsAreInTune() {
        val midi = 62 + 24
        val f = pitch(renderNote(midi, octavePair = false), 800.0, 1600.0)
        assertTrue(abs(centsOff(f, midiToHz(midi))) < 15, "D6 measured $f Hz")
    }

    @Test
    fun outputIsFiniteBoundedAndRings() {
        val x = renderNote(48, octavePair = true, seconds = 2.0)
        assertTrue(x.all { it.isFinite() && abs(it) <= 1f })
        val early = x.copyOfRange(fs / 10, fs / 5).maxOf { abs(it) }
        val late = x.copyOfRange(fs * 3 / 2, fs * 2).maxOf { abs(it) }
        assertTrue(early > 0.05f, "audible attack: $early")
        assertTrue(late > 0.005f && late < early, "long but decaying sustain: $early -> $late")
    }

    @Test
    fun muteSilencesTheStrings() {
        val synth = BouzoukiSynth(fs)
        synth.pluck(0, 57, false)
        val buf = FloatArray(fs / 2)
        synth.render(buf)
        synth.muteAll()
        synth.render(buf)
        synth.render(buf)
        assertTrue(buf.copyOfRange(buf.size / 2, buf.size).maxOf { abs(it) } < 0.01f)
    }
}
