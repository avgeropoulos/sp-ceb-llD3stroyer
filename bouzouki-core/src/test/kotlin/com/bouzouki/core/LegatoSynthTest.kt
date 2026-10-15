package com.bouzouki.core

import com.bouzouki.core.BouzoukiSynth.Legato
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class LegatoSynthTest {
    private val fs = 48000

    private fun BouzoukiSynth.run(seconds: Double): FloatArray {
        val out = FloatArray((fs * seconds).toInt())
        var i = 0
        while (i < out.size) { val n = minOf(256, out.size - i); render(out, i, n); i += n }
        return out
    }

    /** Picks [from], lets it ring [before] s, then plays [to] with [kind] (null: picks it), and renders [after] s more. */
    private fun twoNotes(from: Int, to: Int, kind: Legato?, octavePair: Boolean = false, before: Double = 0.3, after: Double = 0.6): FloatArray {
        val synth = BouzoukiSynth(fs)
        synth.pluck(1, from, octavePair, 0.85f)
        val a = synth.run(before)
        if (kind == null) synth.pluck(1, to, octavePair, 0.85f) else synth.legato(1, to, octavePair, kind, 0.8f)
        return a + synth.run(after)
    }

    /** Fundamental (Hz) over [len] samples from [start], by autocorrelation near [expect], refined between lags. */
    private fun pitch(x: FloatArray, start: Int, len: Int, expect: Double): Double {
        val lo = (fs / (expect * 1.25)).toInt()
        val hi = (fs / (expect / 1.25)).toInt()
        val r = DoubleArray(hi + 2)
        for (lag in lo - 1..hi + 1) {
            var s = 0.0
            var e = 0.0
            for (i in start until start + len) { s += x[i] * x[i + lag]; e += x[i + lag] * x[i + lag] }
            r[lag] = s / sqrt(e + 1e-12)
        }
        var best = lo
        for (lag in lo..hi) if (r[lag] > r[best]) best = lag
        val a = r[best - 1]; val b = r[best]; val c = r[best + 1]
        val shift = 0.5 * (a - c) / (a - 2 * b + c)
        return fs / (best + shift)
    }

    private fun cents(f: Double, midi: Int) = 1200 * ln(f / midiToHz(midi)) / ln(2.0)

    /**
     * Semitones above midi [ref] of the pitch over [len] samples from [start], searched over a
     * wide range (midi [lo] to [hi]) so the answer isn't steered towards any expected note: the
     * first peak of the normalised autocorrelation that comes close to its highest.
     */
    private fun semitonesWide(x: FloatArray, start: Int, len: Int, ref: Int, lo: Int, hi: Int): Double {
        val minLag = (fs / midiToHz(hi)).toInt()
        val maxLag = (fs / midiToHz(lo)).toInt() + 1
        val r = DoubleArray(maxLag + 2)
        var e0 = 0.0
        for (i in start until start + len) e0 += x[i].toDouble() * x[i]
        for (lag in minLag - 1..maxLag + 1) {
            var s = 0.0
            var e = 0.0
            for (i in start until start + len) { s += x[i] * x[i + lag]; e += x[i + lag].toDouble() * x[i + lag] }
            r[lag] = s / sqrt(e0 * e + 1e-12)
        }
        val top = (minLag..maxLag).maxOf { r[it] }
        val best = (minLag..maxLag).first { r[it] >= 0.9 * top && r[it] >= r[it - 1] && r[it] >= r[it + 1] }
        val a = r[best - 1]; val b = r[best]; val c = r[best + 1]
        val lag = best + 0.5 * (a - c) / (a - 2 * b + c)
        return 12 * ln(fs / lag / midiToHz(ref)) / ln(2.0)
    }

    /** RMS of [x] over [len] samples from [start]. */
    private fun rms(x: FloatArray, start: Int, len: Int) = sqrt((start until start + len).sumOf { x[it].toDouble() * x[it] } / len)

    /** Peak of the signal's second difference (its clicks) over [len] samples from [start]. */
    private fun edge(x: FloatArray, start: Int, len: Int): Double {
        var peak = 0.0
        for (i in start until start + len) peak = maxOf(peak, abs(x[i] - 2.0 * x[i - 1] + x[i - 2]))
        return peak
    }

    @Test
    fun everyLegatoReachesItsPitch() {
        val cases = listOf(
            Triple(62, 64, Legato.HAMMER),
            Triple(64, 62, Legato.PULL),
            Triple(64, 57, Legato.PULL),
            Triple(62, 67, Legato.SLIDE),
            Triple(69, 62, Legato.SLIDE),
            Triple(62, 74, Legato.SLIDE), // a whole octave up
        )
        for ((from, to, kind) in cases) for (octave in listOf(false, true)) {
            val x = twoNotes(from, to, kind, octave)
            val at = (fs * 0.3).toInt()
            val f = pitch(x, at + fs / 5, fs / 5, midiToHz(to))
            assertTrue(abs(cents(f, to)) < 8, "$kind $from→$to (octave course $octave): $f Hz, ${cents(f, to)} cents")
            // And it was really the first note before.
            val f0 = pitch(x, at - fs / 5, fs / 6, midiToHz(from))
            assertTrue(abs(cents(f0, from)) < 8, "before $kind: $f0 Hz")
        }
    }

    @Test
    fun theTuningMovesPickedAndLegatoNotesAlike() {
        // Playing along with a record 20 cents flat: the picked note and the hammer-on after it sound 20 cents lower.
        fun played(tuning: Double): Pair<Double, Double> {
            val synth = BouzoukiSynth(fs)
            synth.tuningCents = tuning
            synth.pluck(1, 62, false, 0.85f)
            val a = synth.run(0.3)
            synth.legato(1, 64, false, Legato.HAMMER, 0.8f)
            val x = a + synth.run(0.6)
            val at = (fs * 0.3).toInt()
            return cents(pitch(x, at - fs / 5, fs / 6, midiToHz(62)), 62) to cents(pitch(x, at + fs / 5, fs / 5, midiToHz(64)), 64)
        }
        val (picked, hammered) = played(0.0)
        val (pickedFlat, hammeredFlat) = played(-20.0)
        assertTrue(abs(pickedFlat - picked + 20) < 2.5, "picked: $picked -> $pickedFlat cents")
        assertTrue(abs(hammeredFlat - hammered + 20) < 2.5, "hammered: $hammered -> $hammeredFlat cents")
        // A tuning that isn't a number plays at A = 440.
        val (pickedNaN, _) = played(Double.NaN)
        assertTrue(abs(pickedNaN - picked) < 0.01, "NaN: $pickedNaN cents")
    }

    @Test
    fun aSlideGlidesThroughTheNotesBetween() {
        val at = (fs * 0.3).toInt()
        val glide = (BouzoukiSynth.slideSeconds(12) * fs).toInt()
        val win = 240
        // Short looks along the glide, each searched from below the first note to above the second.
        fun track(x: FloatArray) = listOf(0.15, 0.3, 0.5, 0.7, 0.85).map { f ->
            semitonesWide(x, at + (f * glide).toInt() - win / 2, win, 62, lo = 58, hi = 78)
        }
        val slide = track(twoNotes(62, 74, Legato.SLIDE, after = 0.4))
        val what = "semitones up along the slide: " + slide.joinToString { "%.2f".format(it) }
        // It rises all the way, through the notes between: not there at once, nor still at the start.
        for (k in 1 until slide.size) assertTrue(slide[k] > slide[k - 1] + 0.3, what)
        assertTrue(slide.first() < 4.0 && slide.last() > 8.0, what)
        assertTrue(slide[2] in 3.0..9.0, what)
        // The same looks after a hammer-on hear the new note already, so they would tell a jump.
        val hammer = track(twoNotes(62, 74, Legato.HAMMER, after = 0.4))
        assertTrue(hammer.all { abs(it - 12) < 0.5 }, "after a hammer-on: " + hammer.joinToString { "%.2f".format(it) })
    }

    @Test
    fun legatoNotesHaveNoPickClick() {
        val at = (fs * 0.3).toInt()
        val win = fs / 50 // the 20 ms after the note
        for ((from, to, kind) in listOf(Triple(62, 64, Legato.HAMMER), Triple(64, 62, Legato.PULL), Triple(62, 67, Legato.SLIDE))) {
            for (octave in listOf(false, true)) {
                val x = twoNotes(from, to, kind, octave)
                val ringing = edge(x, at - win, win)
                val legato = edge(x, at, win)
                val picked = edge(twoNotes(from, to, null, octave), at, win)
                val what = "$kind (octave course $octave): ${"%.4f".format(legato)} after the note, ${"%.4f".format(ringing)} " +
                    "while ringing, ${"%.4f".format(picked)} for a pick"
                // A slide adds nothing sharp at all; a hammer-on or pull-off only the finger's soft knock.
                if (kind == Legato.SLIDE) assertTrue(legato < 2 * ringing, what)
                else assertTrue(legato < 0.25 * picked, what)
                // Nor does the sound jump as it does when the string is picked again.
                val before = x.copyOfRange(at - win, at).maxOf { abs(it) }
                val after = x.copyOfRange(at, at + win).maxOf { abs(it) }
                assertTrue(after < 1.7f * before, "$kind (octave course $octave): $before -> $after")
            }
        }
    }

    @Test
    fun manyLegatoNotesStayFiniteBoundedAndDie() {
        val synth = BouzoukiSynth(fs)
        val rnd = java.util.Random(7)
        val out = ArrayList<FloatArray>()
        for (course in 0..1) synth.pluck(course, 50 + course * 7, course == 0, 1f)
        // Two seconds of fast legato runs on two courses, every kind, up and down the neck.
        for (k in 0 until 80) {
            val course = k % 2
            val open = 50 + course * 7
            synth.legato(course, open + rnd.nextInt(25), course == 0, Legato.entries[rnd.nextInt(3)], 0.5f + rnd.nextFloat() / 2)
            out += synth.run(0.025)
        }
        out += synth.run(4.0)
        val x = out.fold(FloatArray(0)) { a, b -> a + b }
        assertTrue(x.all { it.isFinite() && abs(it) <= 1f }, "finite and within -1..1")
        fun rms(from: Double, to: Double): Double {
            val a = (from * fs).toInt(); val b = (to * fs).toInt()
            return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
        }
        val early = rms(0.5, 1.5)
        val late = rms(5.0, 6.0)
        assertTrue(early > 0.01, "the runs are heard: $early")
        assertTrue(late < early * 0.5, "and then die away: $early -> $late")
    }

    @Test
    fun tuningCentsTunesPickedAndLegatoNotes() {
        for (tuning in listOf(-40.0, 30.0, 0.0)) {
            val synth = BouzoukiSynth(fs)
            synth.tuningCents = tuning
            synth.pluck(1, 62, false, 0.85f)
            val a = synth.run(0.3)
            synth.legato(1, 64, false, Legato.HAMMER, 0.8f)
            val x = a + synth.run(0.6)
            val at = (fs * 0.3).toInt()
            val picked = cents(pitch(x, at - fs / 5, fs / 6, midiToHz(62)), 62)
            val hammered = cents(pitch(x, at + fs / 5, fs / 5, midiToHz(64)), 64)
            assertTrue(abs(picked - tuning) < 8, "picked, tuned $tuning cents: $picked")
            assertTrue(abs(hammered - tuning) < 8, "hammered on, tuned $tuning cents: $hammered")
        }
        val synth = BouzoukiSynth(fs)
        synth.tuningCents = Double.NaN
        assertTrue(synth.tuningCents == 0.0, "no tuning for a NaN")
    }

    @Test
    fun aLegatoNoteWithNothingRingingIsPickedSoftly() {
        val synth = BouzoukiSynth(fs)
        synth.legato(2, 64, false, Legato.HAMMER, 0.8f)
        val x = synth.run(0.5)
        assertTrue(x.copyOfRange(fs / 20, fs / 5).maxOf { abs(it) } > 0.02f, "heard")
        assertTrue(abs(cents(pitch(x, fs / 5, fs / 5, midiToHz(64)), 64)) < 8)
        // Softer than the same note picked as hard.
        val picked = BouzoukiSynth(fs).apply { pluck(2, 64, false, 0.8f) }.run(0.5)
        val soft = rms(x, 0, fs / 4)
        val full = rms(picked, 0, fs / 4)
        assertTrue(soft < 0.9 * full, "picked softly: RMS ${"%.4f".format(soft)}, picked ${"%.4f".format(full)}")
    }
}
