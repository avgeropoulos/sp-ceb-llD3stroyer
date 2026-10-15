package com.bouzouki.core.song

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bouzouki.core.Dromos
import com.bouzouki.core.Instrument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Lining tabs up with recordings: on made-up activations, and on a phrase rendered and heard by the bundled model. */
class TabAlignerTest {
    private val tetra = Instrument.TETRACHORDO

    /** A note the "recording" plays: when, its pitch, and (for the tab) where it's fingered on the tetrachordo. */
    private data class Played(val time: Double, val midi: Int)

    /** Frame where a note starting at [t] has its onset peak (the model hears plucks ONSET_LATENCY early). */
    private fun frameOf(t: Double): Int {
        var f = 0
        while (BasicPitch.frameTime(f + 1) <= t - BasicPitch.ONSET_LATENCY) f++
        return if (abs(BasicPitch.frameTime(f + 1) - (t - BasicPitch.ONSET_LATENCY)) < abs(BasicPitch.frameTime(f) - (t - BasicPitch.ONSET_LATENCY))) f + 1 else f
    }

    /** When the aligner will say a note at [t] starts: its onset frame's time, plus the latency. */
    private fun heardAt(t: Double) = BasicPitch.frameTime(frameOf(t)) + BasicPitch.ONSET_LATENCY

    /**
     * Activations of [melody] (clear onsets, the note held until the next) over an accompaniment of softer chord
     * tones struck every half second and some random onsets, [seconds] long.
     */
    private fun activations(melody: List<Played>, seconds: Double, seed: Long = 3): BasicPitch.Activations {
        val n = frameOf(seconds) + 1
        val frames = Array(n) { FloatArray(BasicPitch.N_KEYS) }
        val onsets = Array(n) { FloatArray(BasicPitch.N_KEYS) }
        val rnd = Random(seed)
        fun strike(f: Int, midi: Int, onset: Float, level: Float, length: Int) {
            val k = midi - BasicPitch.MIDI_OFFSET
            if (f !in 0 until n) return
            onsets[f][k] = maxOf(onsets[f][k], onset)
            if (f > 0) onsets[f - 1][k] = maxOf(onsets[f - 1][k], onset / 2)
            if (f + 1 < n) onsets[f + 1][k] = maxOf(onsets[f + 1][k], onset / 2)
            for (x in f until minOf(n, f + length)) frames[x][k] = maxOf(frames[x][k], level)
        }
        var t = 0.3
        while (t < seconds - 0.5) { // the band: chord tones in the bouzouki's range too
            for (m in listOf(50, 57, 62, 65)) strike(frameOf(t), m, 0.45f, 0.4f, 30)
            t += 0.5
        }
        repeat((seconds * 2).toInt()) { strike(rnd.nextInt(n), 55 + rnd.nextInt(25), 0.3f + 0.3f * rnd.nextFloat(), 0.3f, 8) }
        for ((i, p) in melody.withIndex()) {
            val f = frameOf(p.time)
            val next = melody.getOrNull(i + 1)?.let { frameOf(it.time) } ?: (f + 40)
            strike(f, p.midi, 0.9f, 0.8f, minOf(40, next - f - 1).coerceAtLeast(3))
        }
        return BasicPitch.Activations(frames, onsets)
    }

    /** The phrases of a little song in Ousak on D (made up): an intro and a verse. */
    private val intro = listOf(62, 63, 65, 67, 65, 63, 62, 60)
    private val verse = listOf(67, 69, 70, 69, 67, 65, 63, 65, 67, 62)
    private val solo = listOf(74, 72, 70, 72, 74, 75, 74, 72, 70, 69)

    /** Plays [notes] from [start] at [step] seconds a note (every fourth a little longer). */
    private fun phrase(notes: List<Int>, start: Double, step: Double) = notes.mapIndexed { i, m -> Played(start + i * step + (i / 4) * step / 2, m) }

    private fun tabNotes(notes: List<Played>, inst: Instrument = tetra, shift: Int = 0): List<TabText.Note> {
        val cells = SongAnalyzer.fingering(notes.map { it.midi + shift }, notes.map { it.time }, inst)
        return notes.indices.map { TabText.Note(cells[it].first, cells[it].second, Articulation.PICK, notes[it].time) }
    }

    private fun base(act: BasicPitch.Activations) =
        SongAnalyzer.build("song", "A song", SongAnalyzer.candidates(act), tetra, act).copy(beats = listOf(1.0, 1.5, 2.0), dromos = Dromos.OUSAK)

    /** The song note placed nearest [t] with pitch [midi] (from the tab when [fromTab]). */
    private fun near(a: TabAlignment, t: Double, midi: Int, fromTab: Boolean = true): SongNote? =
        a.song.notes.indices.filter { a.song.notes[it].midi == midi && (!fromTab || a.sources[it] >= 0) }
            .map { a.song.notes[it] }.minByOrNull { abs(it.start - t) }?.takeIf { abs(it.start - t) < 0.3 }

    @Test
    fun aTabWithTheVerseWrittenOnceIsFoundEveryTimeTheVerseIsPlayed() {
        // Intro, the verse twice, a pause, an untabbed solo, the verse again.
        val song = phrase(intro, 1.0, 0.3) + phrase(verse, 4.5, 0.3) + phrase(verse, 8.4, 0.3) + phrase(solo, 15.0, 0.25) + phrase(verse, 19.0, 0.3)
        val act = activations(song, 24.0)
        val b = base(act)
        val text = "Intro\n" + TabText.write(tabNotes(phrase(intro, 1.0, 0.3)), tetra, 0.075, rnd = Random(1)) +
            "\nVerse\n" + TabText.write(tabNotes(phrase(verse, 4.5, 0.3)), tetra, 0.075, rnd = Random(2))
        val tab = TabParser.parse(text, tetra)
        assertEquals(18, tab.notes.size)
        val a = TabAligner.align(tab, AnalysisData(act, null), b)
        assertEquals(18, a.matched)
        assertTrue(a.missing.isEmpty())
        // Every verse note is there each of the three times, where it's heard.
        for (start in listOf(4.5, 8.4, 19.0)) for (p in phrase(verse, start, 0.3)) {
            val x = assertNotNull(near(a, p.time, p.midi), "verse note at ${p.time}")
            assertTrue(abs(x.start - heardAt(p.time)) < 0.012, "verse note at ${p.time} placed at ${x.start}")
        }
        // The solo isn't in the tab: the prepared line stays there, no tab note is put in it.
        val inSolo = a.song.notes.indices.filter { a.song.notes[it].start in 14.9..17.8 }
        assertTrue(inSolo.isNotEmpty() && inSolo.all { a.sources[it] == -1 }, "solo: ${inSolo.map { a.sources[it] }}")
        assertTrue(a.passes.size >= 3)
        // Title, beats and dromos are the prepared song's; the tab's frets are kept.
        assertEquals("A song", a.song.title)
        assertEquals(listOf(1.0, 1.5, 2.0), a.song.beats)
        assertEquals(Dromos.OUSAK, a.song.dromos)
        for ((k, n) in a.song.notes.withIndex()) if (a.sources[k] >= 0) {
            assertEquals(tab.notes[a.sources[k]].course, n.course)
            assertEquals(tab.notes[a.sources[k]].fret, n.fret)
        }
        assertTrue(a.song.notes.zipWithNext().all { (x, y) -> x.start < y.start && x.end <= y.start + 1e-9 })
    }

    @Test
    fun wrongFretsMissingAndExtraNotesDontThrowItOff() {
        val song = phrase(intro, 1.0, 0.3) + phrase(verse, 4.5, 0.3)
        val act = activations(song, 9.0)
        val good = tabNotes(song)
        val spoiled = good.toMutableList()
        spoiled[3] = spoiled[3].copy(fret = spoiled[3].fret + 1) // a wrong fret
        spoiled.removeAt(10) // a note left out
        spoiled.add(6, spoiled[5].copy(fret = spoiled[5].fret + 2, time = (spoiled[5].time + spoiled[6].time) / 2)) // one added
        val tab = TabParser.parse(TabText.write(spoiled, tetra, 0.075, rnd = Random(4)), tetra)
        val a = TabAligner.align(tab, AnalysisData(act, null), base(act))
        // The mis-fretted note is placed where its played note is, at the pitch heard, and said to differ.
        val wrong = a.song.notes.indices.single { a.sources[it] == 3 }
        assertTrue(abs(a.song.notes[wrong].start - heardAt(song[3].time)) < 0.012)
        assertEquals(song[3].midi, a.song.notes[wrong].midi)
        assertEquals(tetra.midiAt(a.song.notes[wrong].course, a.song.notes[wrong].fret), song[3].midi)
        assertTrue(3 in a.differs && 3 in a.missing, "${a.differs} ${a.missing}")
        assertEquals(a.total, a.matched + a.missing.size)
        // The note left out of the tab is still there, from the prepared line.
        assertNotNull(near(a, song[10].time, song[10].midi, fromTab = false)?.takeIf { abs(it.start - heardAt(song[10].time)) < 0.012 })
        // Everything else written lands on its note.
        var ok = 0
        for ((i, p) in song.withIndex()) if (i != 3 && i != 10 && near(a, p.time, p.midi)?.let { abs(it.start - heardAt(p.time)) < 0.012 } == true) ok++
        assertTrue(ok >= song.size - 3, "$ok of ${song.size - 2}")
    }

    @Test
    fun notesTheTabLeavesOutStayInTheSong() {
        // Tabs leave out ornaments and fills: where the prepared line hears those notes clearly, they stay.
        val song = phrase(intro, 1.0, 0.3) + phrase(verse, 4.5, 0.3)
        val act = activations(song, 9.0)
        val b = base(act)
        fun has(notes: List<SongNote>, p: Played) = notes.any { it.midi == p.midi && abs(it.start - heardAt(p.time)) < 0.012 }
        assertTrue(song.all { has(b.notes, it) })
        val all = tabNotes(song)
        for (drop in listOf(listOf(10), listOf(5, 12), listOf(2, 6, 11, 15))) {
            val tab = TabParser.parse(TabText.write(all.filterIndexed { i, _ -> i !in drop }, tetra, 0.075, rnd = Random(4)), tetra)
            val a = TabAligner.align(tab, AnalysisData(act, null), b)
            assertEquals(song.size - drop.size, a.matched, "$drop")
            assertTrue(song.all { has(a.song.notes, it) }, "$drop: ${song.filter { !has(a.song.notes, it) }}")
            for (i in drop) assertEquals(-1, a.sources[a.song.notes.indexOfFirst { it.midi == song[i].midi && abs(it.start - heardAt(song[i].time)) < 0.012 }])
            assertEquals(song.size, a.song.notes.size, "$drop: no other line notes come in")
        }
    }

    @Test
    fun aTabAnOctaveLowOrInAnotherKeyIsStillFound() {
        val song = phrase(intro, 1.0, 0.3) + phrase(verse, 4.5, 0.3) + phrase(verse, 8.4, 0.3)
        val act = activations(song, 13.0)
        val b = base(act)
        val written = phrase(intro, 1.0, 0.3) + phrase(verse, 4.5, 0.3)

        val low = TabAligner.align(TabParser.parse(TabText.write(tabNotes(written, shift = -12), tetra, 0.075), tetra), AnalysisData(act, null), b)
        assertEquals(1, low.octave)
        assertEquals(0, low.transpose)
        for (p in song) {
            // Played as the tab writes it, an octave below the recording.
            val x = assertNotNull(near(low, p.time, p.midi - 12), "note at ${p.time}")
            assertTrue(abs(x.start - heardAt(p.time)) < 0.012)
        }

        val key = TabAligner.align(TabParser.parse(TabText.write(tabNotes(written, shift = -2), tetra, 0.075), tetra), AnalysisData(act, null), b)
        assertEquals(2, key.transpose)
        for (p in song) {
            // Moved to the recording's key.
            val x = assertNotNull(near(key, p.time, p.midi), "note at ${p.time}")
            assertTrue(abs(x.start - heardAt(p.time)) < 0.012)
        }
    }

    @Test
    fun aTrichordoTabIsPlacedOnTheTetrachordo() {
        val song = phrase(intro, 1.0, 0.3) + phrase(verse, 4.5, 0.3)
        val act = activations(song, 9.0)
        val text = TabText.write(tabNotes(song, Instrument.TRICHORDO), Instrument.TRICHORDO, 0.075, names = TabText.Names.GREEK)
        val tab = TabParser.parse(text, tetra)
        assertEquals(Instrument.TRICHORDO, tab.instrument)
        val a = TabAligner.align(tab, AnalysisData(act, null), base(act))
        assertEquals(tetra, a.song.instrument)
        for ((k, n) in a.song.notes.withIndex()) if (a.sources[k] >= 0) {
            assertEquals(n.midi, tetra.midiAt(n.course, n.fret))
            // The trichordo's A and D courses are the tetrachordo's top two: same fret, one course up.
            val t = tab.notes[a.sources[k]]
            if (t.course > 0) assertEquals(t.course + 1 to t.fret, n.course to n.fret)
        }
        assertEquals(song.size, a.matched)
    }

    @Test
    fun aFourMinuteSongIsLinedUpQuickly() {
        // Verse after verse for four minutes, the tab written out in full.
        val song = ArrayList<Played>()
        var t = 1.0
        val rnd = Random(9)
        while (t < 235) {
            val notes = if (rnd.nextBoolean()) verse else solo
            song += phrase(notes, t, 0.25 + 0.05 * rnd.nextInt(3))
            t = song.last().time + 0.8 + rnd.nextDouble()
        }
        val act = activations(song, 240.0)
        val b = base(act)
        val tab = TabParser.parse(TabText.write(tabNotes(song), tetra, 0.07, rnd = Random(5)), tetra)
        TabAligner.align(tab, AnalysisData(act, null), b) // warm up
        val t0 = System.nanoTime()
        val a = TabAligner.align(tab, AnalysisData(act, null), b)
        val ms = (System.nanoTime() - t0) / 1e6
        println("4-minute song, ${tab.notes.size} tab notes: %.0f ms".format(ms))
        assertTrue(ms < 1500, "took $ms ms")
        var ok = 0
        for (p in song) if (near(a, p.time, p.midi)?.let { abs(it.start - heardAt(p.time)) < 0.012 } == true) ok++
        assertTrue(ok >= song.size * 0.95, "$ok of ${song.size}")

        // A figure on a few pitches over and over (an ostinato), written out: every candidate fits hundreds of tab notes.
        val figure = listOf(62, 62, 65, 62, 62, 62, 67, 65)
        val ostinato = ArrayList<Played>()
        t = 1.0
        while (t < 235) { for (m in figure) { ostinato += Played(t, m); t += 0.2 }; t += 0.2 }
        val act2 = activations(ostinato, 240.0)
        val b2 = base(act2)
        val tab2 = TabParser.parse(TabText.write(tabNotes(ostinato), tetra, 0.05, rnd = Random(5)), tetra)
        TabAligner.align(tab2, AnalysisData(act2, null), b2)
        val t1 = System.nanoTime()
        val a2 = TabAligner.align(tab2, AnalysisData(act2, null), b2)
        val ms2 = (System.nanoTime() - t1) / 1e6
        println("4-minute ostinato, ${tab2.notes.size} tab notes: %.0f ms".format(ms2))
        assertTrue(ms2 < 5000, "took $ms2 ms")
        var own = 0
        for ((i, p) in ostinato.withIndex()) {
            val k = a2.sources.indexOf(i)
            if (k >= 0 && abs(a2.song.notes[k].start - heardAt(p.time)) < 0.012) own++
        }
        assertTrue(own >= ostinato.size * 0.95, "$own of ${ostinato.size} on their own note")
    }

    @Test
    fun withoutAPreparedLineTheTabIsStillFound() {
        val song = phrase(intro, 1.0, 0.3) + phrase(verse, 4.5, 0.3) + phrase(verse, 8.4, 0.3)
        val act = activations(song, 13.0)
        val empty = SongAnalysis("song", "A song", tetra, emptyList(), null, 2)
        val tab = TabParser.parse(TabText.write(tabNotes(phrase(intro, 1.0, 0.3) + phrase(verse, 4.5, 0.3)), tetra, 0.075, rnd = Random(6)), tetra)
        val a = TabAligner.align(tab, AnalysisData(act, null), empty)
        var ok = 0
        for (p in song) if (near(a, p.time, p.midi)?.let { abs(it.start - heardAt(p.time)) < 0.012 } == true) ok++
        assertTrue(ok >= song.size - 2, "$ok of ${song.size}")
    }

    @Test
    fun anEmptyTabLeavesTheSongAsItIs() {
        val act = activations(phrase(verse, 1.0, 0.3), 5.0)
        val b = base(act)
        val a = TabAligner.align(TabParser.parse("no tab here", tetra), AnalysisData(act, null), b)
        assertEquals(b.notes, a.song.notes)
        assertEquals(0, a.total)
    }

    private val model = File("../bouzouki/src/main/assets/basic_pitch.onnx")

    @Test
    fun aRenderedPhraseHeardByTheModelLinesUpWithItsTab() {
        // A made-up phrase on the top course, played twice with a pause between (picks, a hammer-on, a slide).
        val step = 0.28
        val figure = listOf(62 to LegatoPhrase.Kind.PICK, 65 to LegatoPhrase.Kind.PICK, 67 to LegatoPhrase.Kind.PICK, 69 to LegatoPhrase.Kind.HAMMER,
            67 to LegatoPhrase.Kind.PICK, 65 to LegatoPhrase.Kind.PICK, 64 to LegatoPhrase.Kind.PICK, 65 to LegatoPhrase.Kind.PICK,
            67 to LegatoPhrase.Kind.PICK, 70 to LegatoPhrase.Kind.SLIDE, 69 to LegatoPhrase.Kind.PICK, 67 to LegatoPhrase.Kind.PICK)
        val phrase = LegatoPhrase(seed = 21)
        val played = ArrayList<Played>()
        for (start in listOf(0.8, 6.0)) for ((i, nk) in figure.withIndex()) {
            val time = start + i * step + (if (i >= 6) step / 2 else 0.0)
            when (nk.second) {
                LegatoPhrase.Kind.PICK -> phrase.pick(time, nk.first)
                LegatoPhrase.Kind.HAMMER -> phrase.hammer(time, nk.first)
                LegatoPhrase.Kind.SLIDE -> phrase.slide(time, nk.first)
                LegatoPhrase.Kind.PULL -> phrase.pull(time, nk.first)
            }
            played += Played(time, nk.first)
        }
        val rnd = Random(2)
        val audio = phrase.render(11.0).let { a -> FloatArray(a.size) { (a[it] + 3e-4 * rnd.nextGaussian()).toFloat() } }
        val env = OrtEnvironment.getEnvironment()
        val act = env.createSession(model.path, OrtSession.SessionOptions()).use { BasicPitch(env, it).activations(audio) }
        // What the app keeps with the song, written and read back.
        val bytes = ByteArrayOutputStream().also { AnalysisData(act, Attacks.of(audio)).write(it) }.toByteArray()
        val data = assertNotNull(AnalysisData.read(ByteArrayInputStream(bytes)))
        val base = SongAnalyzer.build("p", "Phrase", SongAnalyzer.candidates(data.act), tetra, data.act, data.attacks)
        // The tab: the phrase once, marked x2, with its hammer-on and slide.
        val once = played.take(figure.size)
        val notes = tabNotes(once).mapIndexed { i, n ->
            n.copy(articulation = when (figure[i].second) { LegatoPhrase.Kind.HAMMER -> Articulation.HAMMER; LegatoPhrase.Kind.SLIDE -> Articulation.SLIDE; else -> Articulation.PICK })
        }
        val text = "Verse x2\n" + TabText.write(notes, tetra, step / 4, rnd = Random(3))
        val tab = TabParser.parse(text, tetra)
        assertEquals(figure.size, tab.notes.size)
        assertEquals(Articulation.HAMMER, tab.notes[3].articulation)
        val a = TabAligner.align(tab, data, base)
        var ok = 0
        val errors = ArrayList<Double>()
        for (p in played) {
            val x = near(a, p.time, p.midi) ?: continue
            errors += abs(x.start - p.time)
            if (abs(x.start - p.time) < 0.03) ok++
        }
        println("rendered phrase: $ok of ${played.size} within 30 ms, errors (ms) ${errors.map { (it * 1000).toInt() }}")
        assertTrue(ok >= played.size * 0.9, "$ok of ${played.size}")
        assertTrue(a.matched >= figure.size - 1)
        // Both times through, the hammer-on keeps its mark.
        assertEquals(2, a.song.notes.count { it.articulation == Articulation.HAMMER })
    }
}
