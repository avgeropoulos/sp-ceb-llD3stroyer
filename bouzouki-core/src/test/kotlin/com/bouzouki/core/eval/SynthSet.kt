package com.bouzouki.core.eval

import com.bouzouki.core.BouzoukiSynth
import com.bouzouki.core.Dromos
import com.bouzouki.core.Instrument
import com.bouzouki.core.song.Articulation
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * A synthetic evaluation set made with the app's own bouzouki synth, standing in for real recordings until there are
 * some (see docs/eval-recordings.md): the same groups a self-recorded set has, for both bouzoukia, played "to a click":
 *
 * - `open`: each open course plucked four times (what the calibration screen asks for);
 * - `frets`: every fret of every course, one after the other;
 * - `chromatic`: chromatic runs up and down each course, and across the neck;
 * - `random`: random phrases in random dromoi, with mixed note lengths, fingered as a player might;
 * - `tremolo`: held notes picked fast (12 to 16 strokes a second), between picked ones;
 * - `detuned`: random phrases on a bouzouki tuned 20 to 45 cents off A = 440 Hz;
 * - `mix`: random phrases inside a band (bass, a strummed accompaniment, drums and a voice-like lead), to test
 *   separation; every mix has its `solo` twin, the very same bouzouki part alone.
 *
 * The synth is not a real bouzouki, nor are its rooms real rooms: scores on it tell changes apart, they don't
 * promise the same on recordings.
 */
object SynthSet {
    const val RATE = 44100

    /** A note to play: when, how long, where, and how (tremolo is re-picked through its length). */
    private data class Play(val start: Double, val dur: Double, val course: Int, val fret: Int, val articulation: Articulation = Articulation.PICK)

    /**
     * Writes the set into [dir] (WAVs and their .ref.csv); [small] makes a few short items only, for tests. With
     * [stems], what each part of the band adds to a mix is written too ([writeStems]); the mixes are the same either way.
     */
    fun write(dir: File, small: Boolean = false, seed: Long = 1, stems: Boolean = false) {
        dir.mkdirs()
        val rnd = Random(seed)
        for (inst in Instrument.entries) {
            val tag = inst.name.lowercase().take(5)
            fun item(group: String, name: String, plays: List<Play>, cents: Double = 0.0, kind: String = "solo", band: Boolean = false) {
                val bouzouki = render(inst, plays, cents)
                val parts = if (band && stems) LinkedHashMap<String, Array<FloatArray>>() else null
                val audio = if (band) mix(bouzouki, plays, inst, Random(rnd.nextLong()), parts) else arrayOf(bouzouki, bouzouki.copyOf())
                Wav.write16(File(dir, "$group-$tag-$name.wav"), audio, RATE)
                if (parts != null) writeStems(dir, "$group-$tag-$name", parts)
                EvalSet.writeRef(File(dir, "$group-$tag-$name.ref.csv"), refs(inst, plays),
                    mapOf("instrument" to inst.name, "kind" to kind, "group" to group, "cents" to "$cents"))
            }
            item("open", "courses", openCourses(inst))
            if (small) {
                item("random", "1", randomPhrase(inst, rnd, 12))
                item("tremolo", "1", tremoloPhrase(inst, rnd, 3))
                val p = randomPhrase(inst, rnd, 12)
                item("solo", "1", p)
                item("mix", "1", p, kind = "mix", band = true)
                continue
            }
            item("frets", "all", everyFret(inst))
            item("chromatic", "courses", chromaticCourses(inst))
            item("chromatic", "across", chromaticAcross(inst))
            for (k in 1..4) item("random", "$k", randomPhrase(inst, rnd, 40))
            for (k in 1..2) item("tremolo", "$k", tremoloPhrase(inst, rnd, 8))
            for ((k, c) in listOf(-45.0, -20.0, 25.0).withIndex()) item("detuned", "${k + 1}", randomPhrase(inst, rnd, 30), cents = c)
            for (k in 1..3) {
                val p = randomPhrase(inst, rnd, 40)
                item("solo", "$k", p)
                item("mix", "$k", p, kind = "mix", band = true)
            }
        }
    }

    /**
     * A larger set of mixes only, to tell changes on the mix path apart (the 6 mixes of [write] are 240 notes: one note
     * is 0.4 points of recall): [phrases] random phrases for each bouzouki inside the band (group `mix`) with their solo
     * twins (group `solo`), and [tremolos] phrases of held tremolo notes inside the band (group `mixtremolo`, with twins
     * in `solotremolo`). Other phrases than [write]'s. With [stems], what each part of the band adds to a mix is
     * written too ([writeStems]).
     */
    fun writeMixes(dir: File, phrases: Int, tremolos: Int = 0, seed: Long = 2, stems: Boolean = true) {
        dir.mkdirs()
        val rnd = Random(seed)
        for (inst in Instrument.entries) {
            val tag = inst.name.lowercase().take(5)
            fun pair(solo: String, group: String, name: String, plays: List<Play>) {
                val bouzouki = render(inst, plays, 0.0)
                Wav.write16(File(dir, "$solo-$tag-$name.wav"), arrayOf(bouzouki, bouzouki.copyOf()), RATE)
                EvalSet.writeRef(File(dir, "$solo-$tag-$name.ref.csv"), refs(inst, plays),
                    mapOf("instrument" to inst.name, "kind" to "solo", "group" to solo, "cents" to "0.0"))
                val parts = if (stems) LinkedHashMap<String, Array<FloatArray>>() else null
                Wav.write16(File(dir, "$group-$tag-$name.wav"), mix(bouzouki, plays, inst, Random(rnd.nextLong()), parts), RATE)
                EvalSet.writeRef(File(dir, "$group-$tag-$name.ref.csv"), refs(inst, plays),
                    mapOf("instrument" to inst.name, "kind" to "mix", "group" to group, "cents" to "0.0"))
                if (parts != null) writeStems(dir, "$group-$tag-$name", parts)
            }
            for (k in 1..phrases) pair("solo", "mix", "$k", randomPhrase(inst, rnd, 40))
            for (k in 1..tremolos) pair("solotremolo", "mixtremolo", "$k", tremoloPhrase(inst, rnd, 6))
        }
    }

    /**
     * Mixes in which the bouzouki sits out a sung verse (group `mixverse`, with solo twins in `soloverse`): [verses] for
     * each bouzouki, each a phrase of 20 notes, about 9 seconds in which only the band plays on (the voice sings a
     * phrase of its own, the bass and the strum follow it), and another phrase of 20 notes. The line the band follows
     * is written as `<name>.sung.csv` (for [band]). To count the notes found where no bouzouki plays.
     */
    fun writeVerses(dir: File, verses: Int, seed: Long = 3, stems: Boolean = true) {
        dir.mkdirs()
        val rnd = Random(seed)
        for (inst in Instrument.entries) {
            val tag = inst.name.lowercase().take(5)
            for (k in 1..verses) {
                fun after(prev: List<Play>, next: List<Play>) = (prev.maxOf { it.start + it.dur } + BEAT).let { at -> next.map { it.copy(start = it.start - 0.3 + at) } }
                val first = randomPhrase(inst, rnd, 20)
                val verse = after(first, randomPhrase(inst, rnd, 32))
                val second = after(verse, randomPhrase(inst, rnd, 20))
                val plays = first + second
                val bouzouki = render(inst, plays, 0.0)
                Wav.write16(File(dir, "soloverse-$tag-$k.wav"), arrayOf(bouzouki, bouzouki.copyOf()), RATE)
                EvalSet.writeRef(File(dir, "soloverse-$tag-$k.ref.csv"), refs(inst, plays),
                    mapOf("instrument" to inst.name, "kind" to "solo", "group" to "soloverse", "cents" to "0.0"))
                val parts = if (stems) LinkedHashMap<String, Array<FloatArray>>() else null
                Wav.write16(File(dir, "mixverse-$tag-$k.wav"), mix(bouzouki, plays, inst, Random(rnd.nextLong()), parts, first + verse + second), RATE)
                EvalSet.writeRef(File(dir, "mixverse-$tag-$k.ref.csv"), refs(inst, plays),
                    mapOf("instrument" to inst.name, "kind" to "mix", "group" to "mixverse", "cents" to "0.0"))
                EvalSet.writeRef(File(dir, "mixverse-$tag-$k.sung.csv"), refs(inst, first + verse + second))
                if (parts != null) writeStems(dir, "mixverse-$tag-$k", parts)
            }
        }
    }

    /**
     * What each part adds to the mix [name], in mono (left and right halved and summed, as a separator's output is
     * compared): `stems/<name>.<part>.wav` for the bouzouki, bass, strum, drums and voice, which sum to the mono mix.
     * For measuring what a separator lets through ([SeparationCheck]).
     */
    private fun writeStems(dir: File, name: String, parts: Map<String, Array<FloatArray>>) {
        val to = File(dir, "stems").apply { mkdirs() }
        for ((part, audio) in parts) Wav.write16(File(to, "$name.$part.wav"), arrayOf(FloatArray(audio[0].size) { 0.5f * (audio[0][it] + audio[1][it]) }), RATE)
    }

    /** A note of the band around the bouzouki in a mix: [part] is "bass", "strum" or "voice" (with its pitch), or a drum ("kick", "snare", "hat": [midi] -1). */
    data class BandNote(val part: String, val start: Double, val end: Double, val midi: Int)

    /**
     * What the band plays around the bouzouki part [ref] in a mix [seconds] long, as [mix] renders it (kept in step
     * with it by hand): for error analysis, to tell which of the band's notes the transcription took for the bouzouki's.
     */
    fun band(bouzouki: List<RefNote>, seconds: Double, sung: List<RefNote>? = null): List<BandNote> {
        // The line the band follows: the bouzouki's, or the one written beside a mix with a verse ([writeVerses]).
        val ref = sung ?: bouzouki
        val out = ArrayList<BandNote>()
        val bar = 4 * BEAT
        var t = 0.0
        while (t < seconds - 1) {
            val inBar = ref.filter { it.start in t..(t + bar) }.map { it.midi }
            val root = (inBar.minOrNull() ?: ref.first().midi).let { 36 + Math.floorMod(it - 36, 12) }
            for (beat in 0 until 4) {
                val bt = t + beat * BEAT
                out += BandNote("bass", bt, bt + 0.45, root)
                // Each string of the strum rings till it is strummed again, half a second on.
                for (iv in listOf(12, 19, 24)) out += BandNote("strum", bt + BEAT / 2, bt + BEAT / 2 + BEAT, root + iv)
                out += BandNote(if (beat % 2 == 0) "kick" else "snare", bt, bt + 0.2, -1)
                out += BandNote("hat", bt, bt + 0.05, -1)
                out += BandNote("hat", bt + BEAT / 2, bt + BEAT / 2 + 0.05, -1)
            }
            t += bar
        }
        for ((k, r) in ref.withIndex()) out += BandNote("voice", r.start + 0.1, (ref.getOrNull(k + 1)?.start?.plus(0.1) ?: seconds), r.midi - 12)
        return out.sortedBy { it.start }
    }

    // --- what is played ---

    private const val BEAT = 0.5 // 120 bpm

    private fun openCourses(inst: Instrument) = inst.courses.indices.flatMap { c -> (0 until 4).map { k -> Play((c * 4 + k) * 2 * BEAT, 1.8 * BEAT, c, 0) } }

    private fun everyFret(inst: Instrument): List<Play> {
        var t = 0.0
        val out = ArrayList<Play>()
        for (c in inst.courses.indices) for (f in 0..inst.frets) { out += Play(t, 0.9 * BEAT, c, f); t += BEAT }
        return out
    }

    private fun chromaticCourses(inst: Instrument): List<Play> {
        var t = 0.0
        val out = ArrayList<Play>()
        val step = BEAT / 2
        for (c in inst.courses.indices) {
            for (f in (0..12) + (11 downTo 0)) { out += Play(t, 0.9 * step, c, f); t += step }
            t += BEAT
        }
        return out
    }

    /** A chromatic scale from the lowest open course up two octaves and more, changing course at its next one's pitch. */
    private fun chromaticAcross(inst: Instrument): List<Play> {
        val out = ArrayList<Play>()
        val low = inst.courses.first().openMidi
        val high = inst.courses.last().openMidi + 12
        val step = BEAT / 2
        for ((k, m) in (low..high).withIndex()) {
            val c = inst.courses.indices.last { inst.courses[it].openMidi <= m }
            out += Play(k * step, 0.9 * step, c, m - inst.courses[c].openMidi)
        }
        return out
    }

    /**
     * About [notes] notes in a random dromos and root, mostly by step with some leaps, in eighths, sixteenths, triplets,
     * quarters and the odd rest, on the courses a player would use: the melody on the top two, moving along a course
     * rather than across, with the hand shifting as the line climbs.
     */
    private fun randomPhrase(inst: Instrument, rnd: Random, notes: Int): List<Play> {
        val d = Dromos.entries[rnd.nextInt(Dromos.entries.size)]
        val root = 50 + rnd.nextInt(12)
        val scale = (0..36).map { root + it }.filter { d.contains(Math.floorMod(root, 12), it) }
            .filter { it >= inst.courses.first().openMidi && it <= inst.courses.last().openMidi + 19 }
        var idx = scale.indexOfFirst { it >= 62 }.coerceAtLeast(0)
        var t = 0.3
        var course = inst.courses.lastIndex
        val out = ArrayList<Play>()
        val lengths = doubleArrayOf(BEAT / 2, BEAT / 2, BEAT / 4, BEAT / 4, BEAT / 3, BEAT)
        repeat(notes) {
            val r = rnd.nextDouble()
            idx = (idx + when {
                r < 0.35 -> 1; r < 0.7 -> -1; r < 0.8 -> 2; r < 0.9 -> -2; r < 0.95 -> 4; else -> -4
            }).coerceIn(0, scale.lastIndex)
            val m = scale[idx]
            // Stay on the course while the note is on it (up to 12 frets up); else the nearest course that has it, high first.
            val here = m - inst.courses[course].openMidi
            if (here !in 0..12) {
                course = inst.courses.indices.reversed().firstOrNull { m - inst.courses[it].openMidi in 0..12 }
                    ?: inst.courses.indices.reversed().first { m - inst.courses[it].openMidi in 0..inst.frets }
            }
            val len = lengths[rnd.nextInt(lengths.size)]
            out += Play(t, len * 0.92, course, m - inst.courses[course].openMidi)
            t += len + if (rnd.nextDouble() < 0.06) BEAT else 0.0
        }
        return out
    }

    /** [held] tremolo notes on the top courses, each one to two seconds, with a few picked notes between. */
    private fun tremoloPhrase(inst: Instrument, rnd: Random, held: Int): List<Play> {
        val out = ArrayList<Play>()
        var t = 0.3
        repeat(held) {
            val c = inst.courses.lastIndex - rnd.nextInt(2)
            val dur = 1.0 + rnd.nextDouble()
            out += Play(t, dur, c, 2 + rnd.nextInt(8), Articulation.TREMOLO)
            t += dur + 0.05
            repeat(2) { out += Play(t, BEAT / 2 * 0.9, inst.courses.lastIndex, rnd.nextInt(10)); t += BEAT / 2 }
        }
        return out
    }

    private fun refs(inst: Instrument, plays: List<Play>) = plays.map {
        RefNote(it.start, it.start + it.dur, inst.midiAt(it.course, it.fret), it.course, it.fret, it.articulation)
    }

    // --- sound ---

    /** The bouzouki part: each pluck on its sample (tremolo re-picked, alternating stroke loudness), damped at its end. */
    private fun render(inst: Instrument, plays: List<Play>, cents: Double): FloatArray {
        val synth = BouzoukiSynth(RATE).apply { tuningCents = cents }
        val end = (plays.maxOf { it.start + it.dur } + 1.5)
        val audio = FloatArray((end * RATE).toInt())
        data class Ev(val t: Double, val course: Int, val midi: Int, val vel: Float)
        val events = ArrayList<Ev>()
        val rnd = Random(plays.size.toLong())
        for (p in plays) {
            val m = inst.midiAt(p.course, p.fret)
            if (p.articulation == Articulation.TREMOLO) {
                val rate = 12.0 + 4 * rnd.nextDouble()
                var t = p.start
                var up = false
                while (t < p.start + p.dur - 0.03) { events += Ev(t, p.course, m, if (up) 0.62f else 0.74f); up = !up; t += 1 / rate }
            } else {
                events += Ev(p.start + 0.004 * rnd.nextGaussian(), p.course, m, (0.7 + 0.25 * rnd.nextDouble()).toFloat())
            }
        }
        var pos = 0
        for (e in events.sortedBy { it.t }) {
            val at = (e.t.coerceAtLeast(0.0) * RATE).toInt().coerceAtMost(audio.size)
            synth.render(audio, pos, at - pos)
            pos = at
            synth.pluck(e.course, e.midi, inst.courses[e.course].octavePair, e.vel)
        }
        synth.render(audio, pos, audio.size - pos)
        val hiss = Random(9)
        for (i in audio.indices) audio[i] += 0.0015f * hiss.nextGaussian().toFloat()
        return audio
    }

    /**
     * The bouzouki part (a little right of centre) in a band: a bass on the phrase's roots, a strummed, darker
     * plucked accompaniment, drums (kick, snare and hi-hat) and a breathy voice-like lead with vibrato in the
     * middle, at levels like a Greek band's mix (the bouzouki about as loud as the voice).
     */
    private fun mix(
        bouzouki: FloatArray, plays: List<Play>, inst: Instrument, rnd: Random, parts: MutableMap<String, Array<FloatArray>>? = null,
        /** The line the band follows (the voice sings it, the bass and the strum take their roots from it): the bouzouki's, unless it sits out a part of the song. */
        sung: List<Play> = plays,
    ): Array<FloatArray> {
        val n = bouzouki.size
        val l = FloatArray(n); val r = FloatArray(n)
        for (i in 0 until n) { l[i] = 0.75f * bouzouki[i]; r[i] = 0.95f * bouzouki[i] }
        // What each part adds, apart ([parts]): the mix itself is summed exactly as without them.
        fun part(name: String) = parts?.let { p -> Array(2) { FloatArray(n) }.also { p[name] = it } }
        part("bouzouki")?.let { for (i in 0 until n) { it[0][i] = l[i]; it[1][i] = r[i] } }
        val bassPart = part("bass"); val strumPart = part("strum"); val drumPart = part("drums"); val voicePart = part("voice")
        val bar = 4 * BEAT
        val roots = sung.map { inst.midiAt(it.course, it.fret) }
        // Bass and accompaniment: a root a bar from the bouzouki's notes in that bar, an octave or two down.
        val acc = BouzoukiSynth(RATE)
        val accAudio = FloatArray(n)
        var pos = 0
        var t = 0.0
        while (t < n.toDouble() / RATE - 1) {
            val inBar = roots.filterIndexed { k, _ -> sung[k].start in t..(t + bar) }
            val root = (inBar.minOrNull() ?: roots.first()).let { 36 + Math.floorMod(it - 36, 12) }
            for (beat in 0 until 4) {
                val bt = t + beat * BEAT
                val s = (bt * RATE).toInt().coerceAtMost(n)
                // Bass: a sine with a quick decay.
                val f = 440.0 * 2.0.pow((root - 69) / 12.0)
                for (i in s until minOf(n, s + (0.45 * RATE).toInt())) {
                    val x = (i - s).toDouble() / RATE
                    val v = (0.22 * sin(2 * PI * f * x) * exp(-x * 5)).toFloat()
                    l[i] += v; r[i] += v
                    if (bassPart != null) { bassPart[0][i] += v; bassPart[1][i] += v }
                }
                // Accompaniment: a soft chord strum (root, fifth, octave) on the off-beats.
                val at = ((bt + BEAT / 2) * RATE).toInt().coerceAtMost(n)
                acc.render(accAudio, pos, at - pos); pos = at
                for ((k, iv) in listOf(12, 19, 24).withIndex()) acc.pluck(k, root + iv, false, 0.35f)
                // Drums: kick on 1 and 3, snare on 2 and 4, hats on every eighth.
                drum(l, r, s, if (beat % 2 == 0) Drum.KICK else Drum.SNARE, rnd, drumPart)
                drum(l, r, s, Drum.HAT, rnd, drumPart)
                drum(l, r, ((bt + BEAT / 2) * RATE).toInt().coerceAtMost(n), Drum.HAT, rnd, drumPart)
            }
            t += bar
        }
        acc.render(accAudio, pos, n - pos)
        for (i in 0 until n) { l[i] += 0.55f * accAudio[i]; r[i] += 0.35f * accAudio[i] }
        if (strumPart != null) for (i in 0 until n) { strumPart[0][i] = 0.55f * accAudio[i]; strumPart[1][i] = 0.35f * accAudio[i] }
        // A voice-like lead: a sawtooth through two formants, with vibrato, singing the phrase's notes an octave down,
        // late and legato (a singer's line under the bouzouki's).
        var phase = 0.0
        val f1 = Formant(700.0); val f2 = Formant(1200.0)
        for (i in 0 until n) {
            val tt = i.toDouble() / RATE
            val k = sung.indexOfLast { it.start <= tt - 0.1 }
            if (k < 0) continue
            val m = roots[k] - 12
            val f = 440.0 * 2.0.pow((m - 69) / 12.0) * (1 + 0.006 * sin(2 * PI * 5.5 * tt))
            phase += f / RATE
            val saw = 2 * (phase - kotlin.math.floor(phase)) - 1
            val v = (0.05 * (f1.process(saw) + 0.6 * f2.process(saw)) + 0.004 * rnd.nextGaussian()).toFloat()
            l[i] += v; r[i] += v
            if (voicePart != null) { voicePart[0][i] = v; voicePart[1][i] = v }
        }
        return arrayOf(l, r)
    }

    private enum class Drum { KICK, SNARE, HAT }

    private fun drum(l: FloatArray, r: FloatArray, s: Int, d: Drum, rnd: Random, part: Array<FloatArray>? = null) {
        val len = when (d) { Drum.KICK -> 0.25; Drum.SNARE -> 0.18; Drum.HAT -> 0.05 }
        var hp = 0.0; var last = 0.0
        for (i in s until minOf(l.size, s + (len * RATE).toInt())) {
            val x = (i - s).toDouble() / RATE
            val noise = rnd.nextGaussian()
            hp = 0.95 * (hp + noise - last); last = noise
            val v = when (d) {
                Drum.KICK -> 0.35 * sin(2 * PI * (50 + 80 * exp(-x * 30)) * x) * exp(-x * 12)
                Drum.SNARE -> (0.12 * noise + 0.08 * sin(2 * PI * 190 * x)) * exp(-x * 22)
                Drum.HAT -> 0.05 * hp * exp(-x * 70)
            }.toFloat()
            l[i] += v; r[i] += v * (if (d == Drum.HAT) 0.7f else 1f)
            if (part != null) { part[0][i] += v; part[1][i] += v * (if (d == Drum.HAT) 0.7f else 1f) }
        }
    }

    /** A two-pole resonator, for the voice's formants. */
    private class Formant(f: Double, bw: Double = 120.0) {
        private val rr = exp(-PI * bw / RATE)
        private val a1 = 2 * rr * kotlin.math.cos(2 * PI * f / RATE)
        private val a2 = -rr * rr
        private var y1 = 0.0; private var y2 = 0.0
        fun process(x: Double): Double { val y = (1 - rr) * x + a1 * y1 + a2 * y2; y2 = y1; y1 = y; return y }
    }
}
