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

class ScalePathTest {
    private val tetra = Instrument.TETRACHORDO
    private val tri = Instrument.TRICHORDO

    private fun midis(inst: Instrument, cells: List<Cell>) = cells.map { inst.midiAt(it.course, it.fret) }

    @Test
    fun arpeggioIsTheFirstThirdFifthAndSeventh() {
        assertEquals(listOf(0, 4, 7, 10), ScalePath.arpeggio(Dromos.HITZAZ).toList())
        assertEquals(listOf(0, 3, 7, 11), ScalePath.arpeggio(Dromos.NIHAVENT).toList())
    }

    @Test
    fun downTheNeckStaysOnOneCourseFromRootToRoot() {
        val cells = ScalePath.downTheNeck(tetra, 3, 2, Dromos.HITZAZ.semitones) // D Hitzaz on the D course
        assertTrue(cells.all { it.course == 3 })
        assertEquals(0, cells.first().fret)
        assertEquals(24, cells.last().fret) // two octaves up the neck
        assertEquals(15, cells.size)
        val m = midis(tetra, cells)
        assertTrue(m.zipWithNext().all { (a, b) -> b > a })
        assertTrue(m.all { Dromos.HITZAZ.contains(2, it) })
    }

    @Test
    fun downTheNeckStartsAtTheFirstRootOnTheCourse() {
        val cells = ScalePath.downTheNeck(tetra, 0, 2, Dromos.MATZORE.semitones) // D major on the C course
        assertEquals(2, cells.first().fret)
        assertEquals(26, cells.last().fret)
    }

    @Test
    fun acrossTheNeckClimbsTheCoursesWithoutGaps() {
        for (inst in Instrument.entries) for (d in Dromos.entries) for (root in 0 until 12) {
            val hand = ScalePath.defaultHandFret(inst, root)
            val cells = ScalePath.acrossTheNeck(inst, root, d.semitones, hand)
            val m = midis(inst, cells)
            assertTrue(m.size >= 8, "$inst $d $root: $m")
            assertEquals(0, Math.floorMod(m.first() - root, 12))
            assertEquals(0, Math.floorMod(m.last() - root, 12))
            assertTrue(m.zipWithNext().all { (a, b) -> b > a })
            assertTrue(cells.map { it.course }.zipWithNext().all { (a, b) -> b >= a })
            assertTrue(cells.all { it.fret in ScalePath.window(inst, hand) })
            // Every scale note between the first and last root is played.
            val expected = (m.first()..m.last()).filter { d.contains(root, it) }
            assertEquals(expected, m, "$inst $d $root")
        }
    }

    @Test
    fun acrossTheNeckOnTheTrichordoInDStartsOpen() {
        val cells = ScalePath.acrossTheNeck(tri, 2, Dromos.HITZAZ.semitones, ScalePath.defaultHandFret(tri, 2))
        assertEquals(Cell(0, 0), cells.first())
        assertEquals(62, midis(tri, cells).last()) // D3 up to D4
    }

    @Test
    fun arpeggioAcrossTheNeck() {
        val cells = ScalePath.acrossTheNeck(tetra, 2, ScalePath.arpeggio(Dromos.MINORE), 2)
        assertEquals(listOf(50, 53, 57, 60, 62), midis(tetra, cells)) // one octave fits in the 2nd-fret position
    }
}

class PitchTest {
    private val fs = 48000

    private fun sine(hz: Double, seconds: Double, amp: Double = 0.3) =
        FloatArray((fs * seconds).toInt()) { (amp * kotlin.math.sin(2 * Math.PI * hz * it / fs)).toFloat() }

    @Test
    fun yinFindsTheFundamentalOfTonesAcrossTheNeck() {
        val yin = Yin(fs)
        for (midi in listOf(48, 55, 62, 69, 76, 84, 89)) {
            val hz = midiToHz(midi)
            // A bright tone: fundamental plus harmonics, like a plucked string.
            val x = FloatArray(yin.frameSize) { i ->
                val t = i.toDouble() / fs
                (0.3 * kotlin.math.sin(2 * Math.PI * hz * t) + 0.2 * kotlin.math.sin(4 * Math.PI * hz * t) +
                    0.1 * kotlin.math.sin(6 * Math.PI * hz * t)).toFloat()
            }
            val found = assertNotNull(yin.detect(x), "midi $midi")
            assertEquals(midi, hzToMidi(found), "midi $midi: $found Hz")
        }
    }

    @Test
    fun yinIgnoresNoise() {
        val rnd = java.util.Random(1)
        val yin = Yin(fs)
        assertEquals(null, yin.detect(FloatArray(yin.frameSize) { (rnd.nextGaussian() * 0.3).toFloat() }))
    }

    @Test
    fun trackerFollowsAMelodyAndFallsSilent() {
        val tracker = NoteTracker(fs)
        val melody = listOf(62, 63, 66, 67)
        val events = ArrayList<NoteTracker.Event>()
        for (m in melody) events += tracker.process(sine(midiToHz(m), 0.25))
        events += tracker.process(FloatArray(fs / 2))
        assertEquals(melody.map { NoteTracker.NoteOn(it) } + NoteTracker.NoteOff, events)
    }

    @Test
    fun trackerHearsTheBouzoukiSynth() {
        val synth = BouzoukiSynth(fs)
        val tracker = NoteTracker(fs)
        val heard = ArrayList<Int>()
        val buf = FloatArray(512)
        for (m in listOf(62, 65, 69, 74)) {
            synth.pluck(3, m, false)
            repeat(fs * 4 / 10 / buf.size) {
                synth.render(buf)
                tracker.process(buf).filterIsInstance<NoteTracker.NoteOn>().forEach { heard += it.midi }
            }
        }
        assertEquals(listOf(62, 65, 69, 74), heard.distinct().filter { it in listOf(62, 65, 69, 74) })
        assertTrue(heard.size <= 6, "few spurious notes: $heard")
    }

    @Test
    fun guessesTheDromosFromTheMelody() {
        // A melody in D Hitzaz that keeps coming back to D.
        val time = DoubleArray(12)
        for ((pc, t) in listOf(2 to 5.0, 3 to 2.0, 6 to 2.5, 7 to 2.0, 9 to 3.0, 10 to 1.5, 0 to 1.0)) time[pc] = t
        val best = DromosGuess.rank(time).first()
        assertEquals(Dromos.HITZAZ, best.dromos)
        assertEquals(2, best.rootPc)
    }
}
