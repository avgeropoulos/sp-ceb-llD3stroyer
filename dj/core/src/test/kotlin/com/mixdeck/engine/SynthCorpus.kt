package com.mixdeck.engine

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Renders short synthetic songs (44.1 kHz stereo) with a known key, tempo and arrangement style.
 *
 * Used by the feature tests: key detection in all 24 keys over typical pop / EDM / hip-hop / R&B /
 * rock / blues progressions (band-limited saw and square pads, piano-like decaying partials,
 * plucks, 808s, distorted power chords, bass lines, melodies that sometimes use the minor leading
 * tone, kick / snare / clap / hats), detuned by up to ±40 cents, with varied mix balance; plus hard
 * cases (drums only, relative-key ambiguity, dominant-heavy loops, a modulation halfway) and
 * reference tracks for the energy and timbre features.
 *
 * Everything is deterministic for a given [Spec].
 */
object SynthCorpus {
    const val SR = 44100

    enum class Style { POP, EDM, HOUSE, HIPHOP, TRAP, RNB, ROCK, BLUES, DNB, AMBIENT, ACOUSTIC }

    class Chord(val root: Int, val tones: IntArray, val name: String)
    class Progression(val name: String, val minor: Boolean, val chords: List<Chord>)

    data class Spec(
        val key: MusicalKey,
        val style: Style,
        val bpm: Double,
        val seconds: Double = 26.0,
        /** Whole-song tuning offset from A = 440 Hz. */
        val tuningCents: Double = 0.0,
        /** Note-level randomness (melody, voicings, humanising). */
        val seed: Long = 1,
        /** Arrangement choices (which patches / patterns the style uses); same value = same arrangement. */
        val arrangement: Long = seed,
        /** Progression name (see [MAJOR_PROGRESSIONS] / [MINOR_PROGRESSIONS]); null = one typical of the style. */
        val progression: String? = null,
        /** Extra drum level in dB (+10 = drums dominate the mix). */
        val drumGainDb: Double = 0.0,
        val drumsOnly: Boolean = false,
        /** If set, the second half of the song is in this key. */
        val modulateTo: MusicalKey? = null,
        /** Final level in dB (features must not depend on it). */
        val masterGainDb: Double = 0.0,
        /** Whether the style's lead melody plays. */
        val melody: Boolean = true,
        /** Random chromatic chords and melody: pitched, but in no key. */
        val atonal: Boolean = false,
        /** Bars of drums only before the music comes in (a "DJ intro"). */
        val introBars: Int = 0,
    )

    class Song(val spec: Spec, val pcm: ShortArrayPcm, val progression: String) {
        val label: String
            get() = buildString {
                append(spec.style).append(' ').append(spec.key.camelot).append(' ')
                append(if (spec.drumsOnly) "drums-only" else if (spec.atonal) "atonal" else progression)
                append(" @").append(spec.bpm.toInt()).append("bpm")
                if (spec.tuningCents != 0.0) append(" %+.0fc".format(spec.tuningCents))
                if (spec.drumGainDb > 5) append(" drums+%.0fdB".format(spec.drumGainDb))
                if (spec.modulateTo != null) append(" ->").append(spec.modulateTo.camelot)
                if (spec.introBars > 0) append(" intro").append(spec.introBars)
            }
    }

    // ------------------------------------------------------------------------------- harmony

    private val MAJ = intArrayOf(0, 4, 7); private val MIN = intArrayOf(0, 3, 7); private val DIM = intArrayOf(0, 3, 6)
    private val DOM7 = intArrayOf(0, 4, 7, 10); private val MAJ7 = intArrayOf(0, 4, 7, 11)
    private val MIN7 = intArrayOf(0, 3, 7, 10); private val HDIM7 = intArrayOf(0, 3, 6, 10)

    private val CHORDS: Map<String, Chord> = listOf(
        Chord(0, MAJ, "I"), Chord(2, MIN, "ii"), Chord(4, MIN, "iii"), Chord(5, MAJ, "IV"), Chord(7, MAJ, "V"),
        Chord(9, MIN, "vi"), Chord(10, MAJ, "bVII"), Chord(0, DOM7, "I7"), Chord(5, DOM7, "IV7"), Chord(7, DOM7, "V7"),
        Chord(0, MAJ7, "Imaj7"), Chord(5, MAJ7, "IVmaj7"), Chord(2, MIN7, "ii7"), Chord(4, MIN7, "iii7"), Chord(9, MIN7, "vi7"),
        Chord(0, MIN, "i"), Chord(2, DIM, "iio"), Chord(2, HDIM7, "iio7"), Chord(3, MAJ, "III"), Chord(5, MIN, "iv"),
        Chord(7, MIN, "v"), Chord(8, MAJ, "VI"), Chord(10, MAJ, "VII"), Chord(0, MIN7, "i7"), Chord(5, MIN7, "iv7"),
        Chord(8, MAJ7, "VImaj7"), Chord(3, MAJ7, "IIImaj7"),
    ).associateBy { it.name }

    private fun prog(name: String, minor: Boolean, chords: String) =
        Progression(name, minor, chords.split(' ').map { CHORDS.getValue(it) })

    val MAJOR_PROGRESSIONS: List<Progression> = listOf(
        prog("I-V-vi-IV", false, "I V vi IV"),
        prog("vi-IV-I-V", false, "vi IV I V"),
        prog("I-vi-IV-V", false, "I vi IV V"),
        prog("I-IV-V-IV", false, "I IV V IV"),
        prog("ii-V-I-vi", false, "ii7 V7 Imaj7 vi7"),
        prog("IV-V-iii-vi", false, "IVmaj7 V7 iii7 vi7"),
        prog("I-bVII-IV-I", false, "I bVII IV I"),
        prog("I-IV loop", false, "Imaj7 IVmaj7"),
        prog("12-bar blues", false, "I7 I7 I7 I7 IV7 IV7 I7 I7 V7 IV7 I7 V7"),
        prog("dominant-heavy", false, "V7 V7 I V7"),
    )
    val MINOR_PROGRESSIONS: List<Progression> = listOf(
        prog("i-VI-III-VII", true, "i VI III VII"),
        prog("i-iv-v-i", true, "i iv v i"),
        prog("i-iv-V-i", true, "i iv V7 i"),
        prog("i-VII-VI-VII", true, "i VII VI VII"),
        prog("i-VI-VII-i", true, "i VI VII i"),
        prog("i-VII loop", true, "i VII"),
        prog("i-iv loop", true, "i7 iv7"),
        prog("i-VI loop", true, "i VI"),
        prog("ii-V-i", true, "iio7 V7 i7 i7"),
        prog("VI-III-VII-i", true, "VI III VII i"),
        prog("minor blues", true, "i7 i7 i7 i7 iv7 iv7 i7 i7 VI V7 i7 V7"),
        prog("dominant-heavy", true, "V7 V7 i V7"),
    )

    fun progression(name: String, minor: Boolean): Progression =
        (if (minor) MINOR_PROGRESSIONS else MAJOR_PROGRESSIONS).first { it.name == name }

    private class StyleInfo(val bpm: IntRange, val major: List<String>, val minor: List<String>)

    // Loops that are rotations of each other (I-V-vi-IV vs vi-IV-I-V = i-VI-III-VII) have identical pitch
    // statistics; the label follows the chord the loop starts on. Rotations that would contradict another
    // style's label (vi-IV-I-V as major, VI-III-VII-i as minor, IV-V-iii-vi) only appear in [ambiguous].
    private val STYLES = mapOf(
        Style.POP to StyleInfo(95..125, listOf("I-V-vi-IV", "I-vi-IV-V", "I-IV-V-IV", "ii-V-I-vi"), listOf("i-VI-III-VII", "i-VII-VI-VII", "i-iv-V-i", "i-VI-VII-i")),
        Style.EDM to StyleInfo(124..130, listOf("I-V-vi-IV", "I-IV loop", "I-vi-IV-V"), listOf("i-VI-III-VII", "i-VII-VI-VII", "i-VI-VII-i", "i-iv loop")),
        Style.HOUSE to StyleInfo(118..126, listOf("ii-V-I-vi", "I-IV loop", "I-V-vi-IV"), listOf("i-iv loop", "i-VII loop", "i-VI-III-VII")),
        Style.HIPHOP to StyleInfo(80..98, listOf("ii-V-I-vi", "I-vi-IV-V", "I-IV loop"), listOf("i-iv loop", "i-VII loop", "i-VI-III-VII", "i-iv-v-i")),
        Style.TRAP to StyleInfo(130..150, listOf("I-V-vi-IV", "I-vi-IV-V"), listOf("i-VI loop", "i-iv-v-i", "i-VII-VI-VII", "i-iv-V-i")),
        Style.RNB to StyleInfo(65..95, listOf("ii-V-I-vi", "I-IV loop", "I-vi-IV-V"), listOf("i-iv loop", "ii-V-i", "i-VI-VII-i")),
        Style.ROCK to StyleInfo(100..150, listOf("I-IV-V-IV", "I-bVII-IV-I", "I-V-vi-IV", "12-bar blues"), listOf("i-VII-VI-VII", "i-VI-VII-i", "i-iv-v-i")),
        Style.BLUES to StyleInfo(80..120, listOf("12-bar blues"), listOf("minor blues")),
        Style.DNB to StyleInfo(170..176, listOf("I-V-vi-IV", "I-vi-IV-V"), listOf("i-VI-III-VII", "i-iv-v-i", "i-VII-VI-VII")),
        Style.AMBIENT to StyleInfo(60..80, listOf("I-IV loop", "I-V-vi-IV", "I-vi-IV-V"), listOf("i-VI loop", "i-VI-III-VII", "i-iv loop")),
        Style.ACOUSTIC to StyleInfo(80..110, listOf("I-V-vi-IV", "I-IV-V-IV", "I-vi-IV-V"), listOf("i-VI-III-VII", "i-VII-VI-VII", "i-iv-v-i")),
    )

    fun bpmRange(style: Style): IntRange = STYLES.getValue(style).bpm

    // ------------------------------------------------------------------------------- corpora

    /**
     * [perKey] songs in each of the 24 keys: styles, tempos, progressions, tuning (±40 cents),
     * mix balance (about one in five has drums 8-12 dB louder) and output level all vary.
     */
    fun keyCorpus(perKey: Int, seconds: Double = 26.0, seed: Long = 1): List<Spec> {
        val rnd = Random(seed)
        val styles = Style.entries.toTypedArray()
        val out = ArrayList<Spec>()
        var styleIdx = rnd.nextInt(styles.size)
        for (rep in 0 until perKey) for (k in 0 until 24) {
            val key = MusicalKey.fromIndex(k)
            val style = styles[styleIdx++ % styles.size]
            val range = bpmRange(style)
            val bpm = (range.first + rnd.nextInt(range.last - range.first + 1)).toDouble()
            val tuning = if (rnd.nextDouble() < 0.2) 0.0 else (rnd.nextDouble() * 80 - 40)
            val r = rnd.nextDouble()
            val drums = when {
                style == Style.AMBIENT -> 0.0
                r < 0.2 -> 8 + rnd.nextDouble() * 4
                r < 0.3 -> -6.0
                else -> rnd.nextDouble() * 6 - 3
            }
            val djStyle = style in setOf(Style.EDM, Style.HOUSE, Style.DNB, Style.TRAP, Style.HIPHOP)
            // A DJ intro, but at most 30 % of the song (real tracks: 16-32 bars of a 150-200 bar track).
            val maxIntro = (0.3 * seconds / (240.0 / bpm)).toInt()
            val intro = if (djStyle && rnd.nextDouble() < 0.35) minOf(if (bpm > 110) 8 else 4, maxIntro) else 0
            out += Spec(key, style, bpm, seconds, tuning, seed = rnd.nextLong(), drumGainDb = drums,
                masterGainDb = -rnd.nextDouble() * 12, introBars = intro)
        }
        return out
    }

    /** Drums-only tracks in several styles (expected: low confidence or no key). */
    fun drumsOnly(count: Int, seconds: Double = 26.0, seed: Long = 7): List<Spec> {
        val rnd = Random(seed)
        val styles = Style.entries.filter { it != Style.AMBIENT }
        return List(count) { i ->
            val style = styles[i % styles.size]
            val range = bpmRange(style)
            Spec(MusicalKey(rnd.nextInt(12), rnd.nextBoolean()), style, (range.first + rnd.nextInt(range.last - range.first + 1)).toDouble(),
                seconds, seed = rnd.nextLong(), drumsOnly = true)
        }
    }

    /** Pitched but keyless songs: random chromatic chords, bass and melody (expected: low confidence). */
    fun atonal(count: Int, seconds: Double = 26.0, seed: Long = 17): List<Spec> {
        val rnd = Random(seed)
        val styles = listOf(Style.POP, Style.EDM, Style.AMBIENT, Style.HIPHOP, Style.ROCK)
        return List(count) { i ->
            val style = styles[i % styles.size]
            val range = bpmRange(style)
            Spec(MusicalKey(rnd.nextInt(12), rnd.nextBoolean()), style, (range.first + rnd.nextInt(range.last - range.first + 1)).toDouble(),
                seconds, seed = rnd.nextLong(), atonal = true)
        }
    }

    /** Relative-key ambiguity and dominant-heavy loops, labelled with the key a musician would name. */
    fun ambiguous(count: Int, seconds: Double = 26.0, seed: Long = 11): List<Spec> {
        val rnd = Random(seed)
        val cases = listOf(
            Triple(false, "vi-IV-I-V", Style.POP), Triple(true, "VI-III-VII-i", Style.EDM),
            Triple(false, "dominant-heavy", Style.HOUSE), Triple(true, "dominant-heavy", Style.TRAP),
            Triple(false, "IV-V-iii-vi", Style.RNB), Triple(false, "vi-IV-I-V", Style.EDM),
        )
        return List(count) { i ->
            val (minor, p, style) = cases[i % cases.size]
            val range = bpmRange(style)
            Spec(MusicalKey(rnd.nextInt(12), minor), style, (range.first + rnd.nextInt(range.last - range.first + 1)).toDouble(),
                seconds, tuningCents = rnd.nextDouble() * 60 - 30, seed = rnd.nextLong(), progression = p)
        }
    }

    /** Songs that change key halfway (up a tone, up a fourth, or to the relative key). */
    fun modulating(count: Int, seconds: Double = 32.0, seed: Long = 13): List<Spec> {
        val rnd = Random(seed)
        val styles = listOf(Style.POP, Style.EDM, Style.ROCK, Style.HOUSE, Style.ACOUSTIC)
        return List(count) { i ->
            val key = MusicalKey(rnd.nextInt(12), rnd.nextBoolean())
            val to = when (i % 3) { 0 -> key.transpose(2); 1 -> key.transpose(5); else -> key.relative }
            val style = styles[i % styles.size]
            val range = bpmRange(style)
            Spec(key, style, (range.first + rnd.nextInt(range.last - range.first + 1)).toDouble(), seconds,
                seed = rnd.nextLong(), modulateTo = to)
        }
    }

    // ------------------------------------------------------------------------------- rendering

    fun render(spec: Spec): Song {
        val info = STYLES.getValue(spec.style)
        val arr = Random(spec.arrangement)
        fun pick(minor: Boolean): Progression {
            val names = if (minor) info.minor else info.major
            return progression(spec.progression ?: names[arr.nextInt(names.size)], minor)
        }
        val p1 = pick(spec.key.minor)
        val p2 = spec.modulateTo?.let { if (it.minor == spec.key.minor) p1 else pick(it.minor) }
        val r = Renderer(spec, arr, p1, p2)
        val pcm = r.run()
        return Song(spec, pcm, p1.name + (p2?.let { if (it !== p1) " / " + it.name else "" } ?: ""))
    }

    private const val TL = 2048
    private const val ZONES = 11
    private const val SINE = 0; private const val SAW = 1; private const val SQUARE = 2; private const val TRI = 3

    /** Band-limited wavetables [wave][zone]; zone z is safe for fundamentals up to 40·2^z Hz. */
    private val tables: Array<Array<FloatArray>> by lazy {
        Array(4) { w ->
            Array(ZONES) { z ->
                val hMax = if (w == SINE) 1 else max(1, (19000 / (40.0 * 2.0.pow(z))).toInt())
                val t = DoubleArray(TL)
                for (h in 1..hMax) {
                    val a = when (w) {
                        SINE -> 1.0
                        SAW -> 2 / PI / h
                        SQUARE -> if (h % 2 == 1) 4 / PI / h else 0.0
                        else -> if (h % 2 == 1) 8 / (PI * PI) / (h * h) * (if ((h / 2) % 2 == 0) 1 else -1) else 0.0
                    }
                    if (a == 0.0) continue
                    for (i in 0 until TL) t[i] += a * sin(2 * PI * h * i / TL)
                }
                FloatArray(TL + 1) { t[it % TL].toFloat() }
            }
        }
    }

    private fun zoneFor(f: Double): Int {
        var z = 0
        var top = 40.0
        while (f > top && z < ZONES - 1) { z++; top *= 2 }
        return z
    }

    private const val OSC = 0; private const val PIANO = 1; private const val EPIANO = 2; private const val BELL = 3; private const val K808 = 4

    private class Patch(
        val kind: Int = OSC, val wave: Int = SAW, val voices: Int = 1, val detune: Double = 0.0, val width: Double = 0.0,
        val attack: Double = 0.005, val decay: Double = 0.3, val sustain: Double = 0.8, val release: Double = 0.15,
        val cutoff: Double = 20000.0, val envCut: Double = 0.0, val cutDecay: Double = 0.2, val q: Double = 0.7,
        val vibrato: Double = 0.0, val drive: Double = 0.0, val gain: Double = 1.0,
    )

    private class Bus(n: Int) { val l = FloatArray(n); val r = FloatArray(n) }

    /** Topology-preserving state-variable filter (lowpass / bandpass / highpass outputs). */
    private class Svf {
        private var ic1 = 0.0; private var ic2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0; private var a3 = 0.0; private var k = 1.0
        var low = 0.0; var band = 0.0; var high = 0.0
        fun set(fc: Double, q: Double) {
            val g = tan(PI * min(fc, 0.45 * SR) / SR)
            k = 1 / q; a1 = 1 / (1 + g * (g + k)); a2 = g * a1; a3 = g * a2
        }
        fun tick(x: Double) {
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2
            low = v2; band = v1; high = x - k * v1 - v2
        }
    }

    private class Ev(val beat: Double, val dur: Double, val midi: Int, val vel: Double)

    private class Renderer(val spec: Spec, val arr: Random, val p1: Progression, val p2: Progression?) {
        val rnd = Random(spec.seed)
        val n = (spec.seconds * SR).toInt()
        val beat = 60.0 / spec.bpm
        val barSec = 4 * beat
        val bars = ceil(spec.seconds / barSec).toInt() + 1
        val modBar = if (spec.modulateTo != null) Math.round(spec.seconds / 2 / barSec).toInt() else Int.MAX_VALUE
        val barsPerChord = when {
            spec.style == Style.AMBIENT -> 2
            spec.style == Style.BLUES || p1.chords.size >= 12 -> 1
            spec.bpm >= 140 && spec.style != Style.TRAP -> 2
            else -> if (arr.nextDouble() < 0.2 && spec.bpm > 100) 2 else 1
        }
        val tuneMul = 2.0.pow(spec.tuningCents / 1200)
        val drums = Bus(n); val music = Bus(n); val bassBus = Bus(n); val send = Bus(n)

        fun keyAt(bar: Int): MusicalKey = if (bar >= modBar) spec.modulateTo!! else spec.key
        fun chordAt(bar: Int): Chord {
            val (p, b) = if (bar >= modBar) p2!! to bar - modBar else p1 to bar
            return p.chords[(b / barsPerChord) % p.chords.size]
        }
        fun chordPcs(bar: Int): IntArray {
            if (spec.atonal) { val r = Random(spec.seed * 31 + bar); return (0 until 12).shuffled(r).take(3 + r.nextInt(2)).toIntArray() }
            val k = keyAt(bar); val c = chordAt(bar); return IntArray(c.tones.size) { (k.tonic + c.root + c.tones[it]) % 12 }
        }
        fun rootPc(bar: Int): Int = if (spec.atonal) chordPcs(bar)[0] else (keyAt(bar).tonic + chordAt(bar).root) % 12
        fun freq(midi: Double) = 440.0 * 2.0.pow((midi - 69) / 12) * tuneMul
        fun t(bar: Int, beatInBar: Double) = bar * barSec + beatInBar * beat
        fun barOf(time: Double) = (time / barSec).toInt()

        fun run(): ShortArrayPcm {
            val s = spec.style
            if (!spec.drumsOnly) arrange(s)
            drumsFor(s)
            if (spec.introBars > 0) {
                val end = min(n, (spec.introBars * barSec * SR).toInt())
                val fade = (0.05 * SR).toInt()
                for (bus in listOf(music, bassBus, send)) for (i in 0 until min(n, end + fade)) {
                    val g = if (i < end) 0f else (i - end).toFloat() / fade
                    bus.l[i] *= g; bus.r[i] *= g
                }
            }
            return master(s)
        }

        // ---------------------------------------------------------------------------- notes

        fun play(bus: Bus, time: Double, dur: Double, midi: Double, p: Patch, vel: Double, pan: Double, sendAmt: Double = 0.0) {
            when (p.kind) {
                OSC -> osc(bus, time, dur, midi, p, vel, pan, sendAmt)
                PIANO -> {
                    val f = freq(midi)
                    val np = max(1, min(14, (5500 / f).toInt()))
                    val ratios = DoubleArray(np) { val h = it + 1.0; h * sqrt(1 + 0.0004 * h * h) }
                    val amps = DoubleArray(np) { if (it == 0) 1.0 else (0.55 + 0.6 * rnd.nextDouble()) / (it + 1.0).pow(1.1) }
                    val t0 = (1.6 * sqrt(220 / f)).coerceIn(0.35, 4.0)
                    val decays = DoubleArray(np) { t0 / (1 + 0.4 * it) }
                    additive(bus, time, dur, f, ratios, amps, decays, 0.12, vel * p.gain, pan, sendAmt)
                }
                EPIANO -> {
                    val f = freq(midi)
                    additive(bus, time, dur, f, doubleArrayOf(1.0, 2.0, 3.0, 7.0), doubleArrayOf(1.0, 0.3, 0.08, 0.12),
                        doubleArrayOf(2.2, 0.9, 0.5, 0.06), 0.25, vel * p.gain, pan, sendAmt)
                }
                BELL -> {
                    val f = freq(midi)
                    additive(bus, time, dur, f, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.4), doubleArrayOf(1.0, 0.45, 0.25, 0.12, 0.08),
                        doubleArrayOf(1.6, 1.0, 0.6, 0.35, 0.2), 0.6, vel * p.gain, pan, sendAmt)
                }
                K808 -> e808(bus, time, dur, midi, vel * p.gain)
            }
        }

        fun osc(bus: Bus, time: Double, dur: Double, midi: Double, p: Patch, vel: Double, pan: Double, sendAmt: Double) {
            val s0 = (time * SR).toInt()
            if (s0 >= n || s0 < 0) return
            val durS = (dur * SR).toInt()
            val len = min(n - s0, durS + (p.release * 6.9 * SR).toInt())
            if (len <= 0) return
            val f0 = freq(midi)
            val nv = p.voices
            val inc = DoubleArray(nv); val ph = DoubleArray(nv); val gl = DoubleArray(nv); val gr = DoubleArray(nv)
            for (v in 0 until nv) {
                val off = if (nv == 1) 0.0 else v.toDouble() / (nv - 1) * 2 - 1
                inc[v] = f0 * 2.0.pow(off * p.detune / 1200) / SR
                ph[v] = rnd.nextDouble()
                val pp = (pan + off * p.width).coerceIn(-1.0, 1.0)
                gl[v] = cos((pp + 1) * PI / 4); gr[v] = sin((pp + 1) * PI / 4)
            }
            val tb = tables[p.wave][zoneFor(f0 * 2.0.pow((p.detune + p.vibrato) / 1200) * 1.01)]
            val fl = Svf(); val fr = Svf()
            val useFilter = p.cutoff < 15000 || p.envCut > 0
            val aS = max(1.0, p.attack * SR)
            val dK = exp(-1.0 / (max(0.001, p.decay) * SR)); val rK = exp(-1.0 / (max(0.001, p.release) * SR))
            var dl = 1 - p.sustain; var env = 0.0; var rel = 1.0; var offLevel = 0.0
            val amp = vel * p.gain / sqrt(nv.toDouble())
            val drive = p.drive; val dNorm = if (drive > 0) 1 / tanh(drive) else 1.0
            var vib = 1.0
            for (i in 0 until len) {
                if (i and 31 == 0) {
                    val tt = i.toDouble() / SR
                    if (useFilter) { val fc = p.cutoff + p.envCut * exp(-tt / p.cutDecay); fl.set(fc, p.q); fr.set(fc, p.q) }
                    if (p.vibrato > 0) vib = 1 + p.vibrato * 0.000578 * min(1.0, max(0.0, tt - 0.2) * 3) * sin(2 * PI * 5.5 * tt)
                }
                var sl = 0.0; var sr = 0.0
                for (v in 0 until nv) {
                    var q = ph[v] + inc[v] * vib
                    if (q >= 1) q -= 1
                    ph[v] = q
                    val x = q * TL; val k = x.toInt()
                    val w = tb[k] + (tb[k + 1] - tb[k]) * (x - k)
                    sl += w * gl[v]; sr += w * gr[v]
                }
                if (i < durS) {
                    env = if (i < aS) i / aS * (p.sustain + dl) else { dl *= dK; p.sustain + dl }
                    offLevel = env
                } else { rel *= rK; env = offLevel * rel }
                if (drive > 0) { sl = tanh(drive * sl) * dNorm; sr = tanh(drive * sr) * dNorm }
                if (useFilter) { fl.tick(sl); sl = fl.low; fr.tick(sr); sr = fr.low }
                val g = amp * env
                val j = s0 + i
                bus.l[j] += (sl * g).toFloat(); bus.r[j] += (sr * g).toFloat()
                if (sendAmt > 0) { send.l[j] += (sl * g * sendAmt).toFloat(); send.r[j] += (sr * g * sendAmt).toFloat() }
            }
        }

        /** Decaying partials rendered with rotating phasors (piano, electric piano, bells). */
        fun additive(bus: Bus, time: Double, dur: Double, f: Double, ratios: DoubleArray, amps: DoubleArray, decays: DoubleArray,
                     release: Double, gain: Double, pan: Double, sendAmt: Double) {
            val s0 = (time * SR).toInt()
            if (s0 >= n || s0 < 0) return
            val durS = (dur * SR).toInt()
            val maxDecay = decays.max()
            val len = min(n - s0, min(durS + (release * 6.9 * SR).toInt(), (maxDecay * 6.9 * SR).toInt()))
            val gl = cos((pan + 1) * PI / 4) * gain; val gr = sin((pan + 1) * PI / 4) * gain
            val buf = DoubleArray(len)
            for (h in ratios.indices) {
                val fh = f * ratios[h]
                if (fh > 0.42 * SR) continue
                val w = 2 * PI * fh / SR; val c = cos(w); val s = sin(w)
                var x = 1.0; var y = 0.0
                var a = amps[h]
                val dk = exp(-1.0 / (decays[h] * SR)); val rk = exp(-1.0 / (min(decays[h], release) * SR))
                for (i in 0 until len) {
                    val nx = x * c - y * s; y = x * s + y * c; x = nx
                    a *= if (i < durS) dk else rk
                    buf[i] += a * y
                    if (i and 1023 == 1023) { val m = 1 / sqrt(x * x + y * y); x *= m; y *= m }
                }
            }
            val att = (0.002 * SR).toInt()
            for (i in 0 until len) {
                val v = buf[i] * (if (i < att) i.toDouble() / att else 1.0)
                val j = s0 + i
                bus.l[j] += (v * gl).toFloat(); bus.r[j] += (v * gr).toFloat()
                if (sendAmt > 0) { send.l[j] += (v * gl * sendAmt).toFloat(); send.r[j] += (v * gr * sendAmt).toFloat() }
            }
        }

        /** 808-style sub bass: sine with a short pitch drop, long decay and some saturation. */
        fun e808(bus: Bus, time: Double, dur: Double, midi: Double, gain: Double) {
            val s0 = (time * SR).toInt()
            if (s0 >= n || s0 < 0) return
            val durS = (dur * SR).toInt()
            val len = min(n - s0, durS + (0.05 * 6.9 * SR).toInt())
            val f = freq(midi)
            var ph = 0.0
            val tau = (dur * 0.7).coerceIn(0.2, 1.5)
            val drv = 2.0
            for (i in 0 until len) {
                val tt = i.toDouble() / SR
                val fi = f * (1 + 0.8 * exp(-tt / 0.02))
                ph += 2 * PI * fi / SR
                var env = exp(-tt / tau) * min(1.0, tt / 0.002)
                if (i >= durS) env *= exp(-(i - durS).toDouble() / (0.05 * SR))
                val v = tanh(drv * sin(ph)) / tanh(drv) * env * gain
                val j = s0 + i
                bus.l[j] += v.toFloat(); bus.r[j] += v.toFloat()
            }
        }

        // ---------------------------------------------------------------------------- arrangement

        fun voicing(pcs: IntArray, center: Int): IntArray =
            IntArray(pcs.size) { val lo = center - 6; lo + Math.floorMod(pcs[it] - lo, 12) }.sortedArray()

        fun bassMidi(pc: Int, low: Int) = low + Math.floorMod(pc - low, 12)

        /** Chords held for a bar (or as written by [rhythm]: (beat, length) pairs within the bar). */
        fun chords(bus: Bus, p: Patch, center: Int, vel: Double, rhythm: List<Pair<Double, Double>>, pan: Double = 0.0,
                   sendAmt: Double = 0.0, spread: Double = 0.3, fromBar: Int = 0, addRoot: Boolean = false, everyBar: Int = 1) {
            for (b in fromBar until bars step everyBar) {
                val notes = voicing(chordPcs(b), center).toMutableList()
                if (addRoot) notes += bassMidi(rootPc(b), center - 12)
                for ((st, len) in rhythm) {
                    val tt = t(b, st) + humanize()
                    for ((vi, m) in notes.withIndex()) {
                        val pp = pan + spread * (vi.toDouble() / max(1, notes.size - 1) * 2 - 1)
                        play(bus, tt, len * beat, m.toDouble(), p, vel * (0.9 + 0.2 * rnd.nextDouble()), pp, sendAmt)
                    }
                }
            }
        }

        fun humanize() = (rnd.nextDouble() - 0.5) * 0.008

        fun arp(bus: Bus, p: Patch, center: Int, vel: Double, stepBeats: Double, pan: Double, sendAmt: Double, fromBar: Int = 0) {
            val steps = (4 / stepBeats).toInt()
            val pattern = arr.nextInt(3)
            for (b in fromBar until bars) {
                val v = voicing(chordPcs(b), center)
                val up = v + v.map { it + 12 }
                for (s in 0 until steps) {
                    val idx = when (pattern) {
                        0 -> s % up.size
                        1 -> { val c = 2 * up.size - 2; val q = s % c; if (q < up.size) q else c - q }
                        else -> intArrayOf(0, 2, 1, 3, 2, 4, 3, 5)[s % 8] % up.size
                    }
                    play(bus, t(b, s * stepBeats), stepBeats * beat * 0.9, up[idx].toDouble(), p, vel * (if (s % 4 == 0) 1.0 else 0.8), pan + 0.3 * sin(s * 0.7), sendAmt)
                }
            }
        }

        fun bassLine(p: Patch, low: Int, pattern: List<Triple<Double, Double, Int>>, vel: Double, fromBar: Int = 0) {
            // pattern: (beat, length in beats, interval above root: 0 root, 7 fifth, 12 octave)
            for (b in fromBar until bars) {
                val root = bassMidi(rootPc(b), low)
                for ((st, len, iv) in pattern) {
                    val m = root + iv
                    play(bassBus, t(b, st) + humanize(), len * beat, m.toDouble(), p, vel * (0.9 + 0.15 * rnd.nextDouble()), 0.0)
                }
            }
        }

        fun walkingBass(p: Patch, low: Int, vel: Double) {
            for (b in 0 until bars) {
                val root = bassMidi(rootPc(b), low)
                val minorChord = chordAt(b).tones[1] == 3
                val steps = intArrayOf(0, if (minorChord) 3 else 4, 7, 9)
                for (q in 0 until 4) play(bassBus, t(b, q.toDouble()) + humanize(), beat * 0.9, (root + steps[q]).toDouble(), p, vel, 0.0)
            }
        }

        /** A scale-aware melody: chord tones on strong beats, mostly stepwise motion, phrases of 4 bars. */
        fun melody(bus: Bus, p: Patch, center: Int, vel: Double, pan: Double, sendAmt: Double, density: Int, fromBar: Int = 1,
                   pentatonic: Boolean = false, blues: Boolean = false, octaveDouble: Boolean = false) {
            val rhythms = when (density) {
                0 -> listOf(intArrayOf(8), intArrayOf(4, 4), intArrayOf(-4, 4), intArrayOf(6, 2))
                1 -> listOf(intArrayOf(2, 2, 2, 2), intArrayOf(3, 1, 2, 2), intArrayOf(2, 1, 1, 4), intArrayOf(-2, 2, 2, 2), intArrayOf(4, 2, 2), intArrayOf(2, -1, 1, 4), intArrayOf(3, 3, 2))
                else -> listOf(intArrayOf(1, 1, 2, 1, 1, 2), intArrayOf(1, 1, 1, 1, 2, 2), intArrayOf(2, 1, 1, 2, 1, 1), intArrayOf(-1, 1, 1, 1, 2, 2), intArrayOf(1, 1, 1, 1, 1, 1, 1, 1))
            }
            val leadingTone = arr.nextDouble() < 0.5
            var prev = center
            for (b in fromBar until bars) {
                if (b % 8 == 7 && rnd.nextDouble() < 0.5) continue // breathe
                val key = keyAt(b)
                val chord = chordAt(b)
                val chordPcs = chordPcs(b).toSet()
                val scale = when {
                    spec.atonal -> IntArray(12) { it }
                    blues -> if (key.minor) intArrayOf(0, 3, 5, 6, 7, 10) else intArrayOf(0, 2, 3, 4, 7, 9, 10)
                    pentatonic -> if (key.minor) intArrayOf(0, 3, 5, 7, 10) else intArrayOf(0, 2, 4, 7, 9)
                    key.minor && (chord.root == 7 && chord.tones[1] == 4 || leadingTone && rnd.nextDouble() < 0.15) -> intArrayOf(0, 2, 3, 5, 7, 8, 11)
                    key.minor -> intArrayOf(0, 2, 3, 5, 7, 8, 10)
                    else -> intArrayOf(0, 2, 4, 5, 7, 9, 11)
                }
                val scalePcs = scale.map { (key.tonic + it) % 12 }.toSet() + (if (chord.root == 7 && chord.tones[1] == 4) setOf((key.tonic + 11) % 12) else emptySet())
                val allowed = (center - 9..center + 14).filter { (it % 12) in scalePcs }
                val chordNotes = (center - 9..center + 14).filter { (it % 12) in chordPcs }
                val rhythm = rhythms[rnd.nextInt(rhythms.size)]
                val loopBars = (if (b >= modBar) p2!! else p1).chords.size * barsPerChord
                val loopPos = (if (b >= modBar) b - modBar else b) % loopBars
                val tonicNotes = (center - 9..center + 14).filter { it % 12 == key.tonic }
                val lastNoteIdx = rhythm.indexOfLast { it > 0 }
                var pos = 0
                for ((ri, r) in rhythm.withIndex()) {
                    val len = kotlin.math.abs(r)
                    if (r > 0) {
                        val strong = pos % 4 == 0
                        // Melodies tend to start a loop on the tonic and to end phrases on it.
                        val home = !spec.atonal && (loopPos == 0 && pos == 0 && rnd.nextDouble() < 0.6) ||
                            (loopPos == loopBars - 1 && ri == lastNoteIdx && rnd.nextDouble() < 0.5)
                        val cand = if (home) tonicNotes else if (strong || rnd.nextDouble() < 0.3) chordNotes else allowed
                        val target = if (strong) prev else prev + (if (rnd.nextDouble() < 0.7) (if (rnd.nextBoolean()) 2 else -2) else (if (rnd.nextBoolean()) 4 else -4))
                        val pull = (center - prev) * 0.15
                        val m = cand.minByOrNull { kotlin.math.abs(it - target - pull) + rnd.nextDouble() * 1.5 } ?: prev
                        val tt = t(b, pos * 0.5) + humanize()
                        val v = vel * (if (strong) 1.0 else 0.8) * (0.9 + 0.2 * rnd.nextDouble())
                        play(bus, tt, len * 0.5 * beat * 0.92, m.toDouble(), p, v, pan, sendAmt)
                        if (octaveDouble) play(bus, tt, len * 0.5 * beat * 0.92, m + 12.0, p, v * 0.4, -pan, sendAmt)
                        prev = m
                    }
                    pos += len
                }
            }
        }

        fun powerChords(vel: Double, eighths: Boolean) {
            val buf = DoubleArray(n)
            val tb = tables[SAW]
            for (b in 0 until bars) {
                val root = bassMidi(rootPc(b), 40)
                val hits = if (eighths) (0 until 8).map { it * 0.5 to 0.45 } else listOf(0.0 to 1.9, 2.0 to 1.9)
                for ((st, len) in hits) {
                    val s0 = (t(b, st) * SR).toInt(); if (s0 >= n) continue
                    val l = min(n - s0, (len * beat * SR).toInt())
                    for (m in intArrayOf(root, root + 7, root + 12)) for (d in intArrayOf(-1, 1)) {
                        val f = freq(m + d * 0.08)
                        val tab = tb[zoneFor(f)]
                        val inc = f / SR
                        var q = rnd.nextDouble()
                        for (i in 0 until l) {
                            q += inc; if (q >= 1) q -= 1
                            val x = q * TL; val k = x.toInt()
                            val e = min(1.0, i / 200.0) * (if (eighths) exp(-i / (0.25 * SR)) else 1.0) * min(1.0, (l - i) / 300.0)
                            buf[s0 + i] += (tab[k] + (tab[k + 1] - tab[k]) * (x - k)) * e * 0.25
                        }
                    }
                }
            }
            val f = Svf(); f.set(3200.0, 0.7)
            val hp = Svf(); hp.set(90.0, 0.7)
            val gL = vel * 0.75; val gR = vel * 0.65
            for (i in 0 until n) {
                val d = tanh(4 * buf[i]) // distortion: intermodulation and extra harmonics
                f.tick(d); hp.tick(f.low)
                val y = hp.high
                // Pseudo double-tracking: right side a few ms later.
                music.l[i] += (y * gL).toFloat()
                val j = i + 400
                if (j < n) music.r[j] += (y * gR).toFloat()
            }
        }

        fun arrange(s: Style) {
            val c = 60 + arr.nextInt(7) - 3 // chord register centre
            val leadC = 72 + arr.nextInt(5) - 2
            val bassLow = intArrayOf(28, 31, 33)[arr.nextInt(3)]
            val melodyOn = spec.melody
            when (s) {
                Style.POP -> {
                    if (arr.nextBoolean()) chords(music, Patch(kind = PIANO, gain = 0.5), c, 0.9, listOf(0.0 to 0.9, 1.0 to 0.9, 2.0 to 0.9, 3.0 to 0.9), sendAmt = 0.2)
                    else chords(music, Patch(wave = SAW, voices = 3, detune = 12.0, width = 0.6, attack = 0.08, cutoff = 2200.0, release = 0.3, gain = 0.35), c, 1.0, listOf(0.0 to 4.0), sendAmt = 0.3)
                    bassLine(Patch(wave = SAW, cutoff = 700.0, envCut = 800.0, cutDecay = 0.08, decay = 0.2, sustain = 0.6, gain = 0.55), bassLow,
                        (0 until 8).map { Triple(it * 0.5, 0.45, if (it == 7 && arr.nextBoolean()) 12 else 0) }, 1.0)
                    if (melodyOn) melody(music, Patch(wave = if (arr.nextBoolean()) SQUARE else TRI, voices = 2, detune = 6.0, cutoff = 3000.0, vibrato = 15.0, attack = 0.02, gain = 0.35), leadC, 1.0, 0.1, 0.25, 1)
                }
                Style.EDM -> {
                    chords(music, Patch(wave = SAW, voices = 7, detune = 28.0, width = 0.9, attack = 0.01, cutoff = 5500.0, release = 0.2, gain = 0.5), c, 1.0, listOf(0.0 to 4.0), sendAmt = 0.25, spread = 0.0, addRoot = true)
                    arp(music, Patch(wave = SAW, voices = 2, detune = 8.0, width = 0.4, cutoff = 600.0, envCut = 5000.0, cutDecay = 0.06, decay = 0.15, sustain = 0.2, gain = 0.3), c + 12, 1.0, 0.25, 0.0, 0.3)
                    bassLine(Patch(wave = SAW, voices = 2, detune = 10.0, cutoff = 900.0, envCut = 1200.0, cutDecay = 0.05, gain = 0.6), bassLow + 12,
                        (0 until 4).map { Triple(it + 0.5, 0.45, 0) }, 1.0)
                    if (melodyOn) melody(music, Patch(wave = SAW, voices = 3, detune = 15.0, width = 0.5, cutoff = 4000.0, envCut = 3000.0, cutDecay = 0.1, gain = 0.3), leadC + 5, 1.0, 0.0, 0.3, 2)
                }
                Style.HOUSE -> {
                    chords(music, Patch(kind = PIANO, gain = 0.5), c, 1.0, listOf(0.5 to 0.3, 1.75 to 0.3, 2.5 to 0.3, 3.75 to 0.25), sendAmt = 0.2)
                    chords(music, Patch(wave = TRI, voices = 2, detune = 8.0, width = 0.7, attack = 0.2, release = 0.4, gain = 0.2), c - 12, 1.0, listOf(0.0 to 4.0), sendAmt = 0.4)
                    bassLine(Patch(wave = SQUARE, cutoff = 500.0, envCut = 600.0, cutDecay = 0.05, decay = 0.1, sustain = 0.5, gain = 0.5), bassLow,
                        listOf(Triple(0.5, 0.3, 0), Triple(1.5, 0.25, 0), Triple(1.75, 0.2, 12), Triple(2.5, 0.3, 0), Triple(3.5, 0.3, 7)), 1.0)
                    if (melodyOn && arr.nextBoolean()) melody(music, Patch(kind = BELL, gain = 0.25), leadC, 1.0, 0.2, 0.3, 1, fromBar = 2)
                }
                Style.HIPHOP -> {
                    val keys = if (arr.nextBoolean()) Patch(kind = EPIANO, gain = 0.5) else Patch(kind = PIANO, gain = 0.45)
                    chords(music, keys, c, 1.0, listOf(0.0 to 1.5, 1.75 to 0.5, 2.5 to 1.4), sendAmt = 0.15)
                    bassLine(Patch(wave = SINE, attack = 0.005, decay = 0.6, sustain = 0.6, release = 0.08, gain = 0.8), bassLow,
                        listOf(Triple(0.0, 1.25, 0), Triple(1.75, 0.5, 0), Triple(2.5, 1.0, 0)), 1.0)
                    if (melodyOn) melody(music, Patch(kind = BELL, gain = 0.25), leadC, 1.0, -0.2, 0.2, 1, pentatonic = arr.nextBoolean())
                }
                Style.TRAP -> {
                    chords(music, Patch(wave = SQUARE, voices = 2, detune = 10.0, width = 0.6, attack = 0.3, cutoff = 1200.0, release = 0.5, gain = 0.25), c, 1.0, listOf(0.0 to 4.0), sendAmt = 0.4)
                    bassLine(Patch(kind = K808, gain = 0.7), bassLow, listOf(Triple(0.0, 1.5, 0), Triple(1.75, 0.6, 0), Triple(2.5, 1.4, if (arr.nextBoolean()) 7 else 0)), 1.0)
                    if (melodyOn) melody(music, Patch(kind = BELL, gain = 0.3), leadC, 1.0, 0.15, 0.35, 2, pentatonic = arr.nextBoolean())
                }
                Style.RNB -> {
                    chords(music, Patch(kind = EPIANO, gain = 0.5), c, 1.0, listOf(0.0 to 1.8, 2.0 to 1.4, 3.5 to 0.5), sendAmt = 0.2)
                    chords(music, Patch(wave = SAW, voices = 2, detune = 7.0, width = 0.5, attack = 0.25, cutoff = 1500.0, release = 0.5, gain = 0.15), c + 5, 1.0, listOf(0.0 to 4.0), sendAmt = 0.4)
                    bassLine(Patch(wave = TRI, attack = 0.01, decay = 0.5, sustain = 0.7, cutoff = 600.0, gain = 0.7), bassLow,
                        listOf(Triple(0.0, 1.5, 0), Triple(2.0, 1.0, 7), Triple(3.5, 0.5, 0)), 1.0)
                    if (melodyOn) melody(music, Patch(wave = SQUARE, cutoff = 1800.0, vibrato = 25.0, attack = 0.04, release = 0.2, gain = 0.3), leadC - 3, 1.0, 0.0, 0.35, 1)
                }
                Style.ROCK -> {
                    powerChords(0.5, arr.nextBoolean())
                    bassLine(Patch(wave = SAW, cutoff = 900.0, envCut = 600.0, cutDecay = 0.06, decay = 0.2, sustain = 0.7, gain = 0.5), bassLow,
                        (0 until 8).map { Triple(it * 0.5, 0.45, 0) }, 1.0)
                    if (melodyOn) melody(music, Patch(wave = SAW, cutoff = 2800.0, drive = 3.0, vibrato = 20.0, attack = 0.01, gain = 0.25), leadC, 1.0, 0.3, 0.2, 1)
                }
                Style.BLUES -> {
                    chords(music, Patch(kind = PIANO, gain = 0.45), c, 1.0, listOf(0.66 to 0.3, 1.66 to 0.3, 2.66 to 0.3, 3.66 to 0.3), sendAmt = 0.2)
                    walkingBass(Patch(wave = TRI, cutoff = 900.0, decay = 0.3, sustain = 0.5, gain = 0.7), bassLow + 5, 1.0)
                    if (melodyOn) melody(music, Patch(kind = PIANO, gain = 0.4), leadC + 2, 1.0, 0.2, 0.2, 1, blues = true)
                }
                Style.DNB -> {
                    chords(music, Patch(wave = SAW, voices = 3, detune = 10.0, width = 0.8, attack = 0.3, cutoff = 2000.0, release = 0.8, gain = 0.3), c, 1.0, listOf(0.0 to 4.0), sendAmt = 0.5)
                    bassLine(Patch(wave = SAW, voices = 2, detune = 18.0, cutoff = 420.0, attack = 0.01, release = 0.05, gain = 0.6), bassLow,
                        listOf(Triple(0.0, 2.4, 0), Triple(2.75, 1.0, 0)), 1.0)
                    if (melodyOn) melody(music, Patch(kind = BELL, gain = 0.25), leadC, 1.0, 0.1, 0.4, 1)
                }
                Style.AMBIENT -> {
                    chords(music, Patch(wave = SAW, voices = 4, detune = 9.0, width = 0.9, attack = 1.5, decay = 2.0, sustain = 0.9, cutoff = 1100.0, release = 2.0, gain = 0.3), c, 0.8, listOf(0.0 to 8.0), sendAmt = 0.7, everyBar = 2)
                    chords(music, Patch(wave = TRI, voices = 2, detune = 5.0, width = 0.6, attack = 2.0, sustain = 1.0, release = 2.0, gain = 0.2), c + 12, 0.8, listOf(0.0 to 8.0), sendAmt = 0.7, everyBar = 2)
                    if (arr.nextBoolean()) bassLine(Patch(wave = SINE, attack = 0.8, release = 1.5, gain = 0.4), bassLow + 5, listOf(Triple(0.0, 4.0, 0)), 1.0)
                    if (melodyOn) melody(music, Patch(kind = BELL, gain = 0.18), leadC, 1.0, 0.3, 0.8, 0, fromBar = 2)
                }
                Style.ACOUSTIC -> {
                    arp(music, Patch(wave = SAW, cutoff = 350.0, envCut = 3500.0, cutDecay = 0.05, decay = 0.6, sustain = 0.0, release = 0.3, gain = 0.4), c - 5, 0.9, 0.5, -0.2, 0.15)
                    if (arr.nextBoolean()) chords(music, Patch(kind = PIANO, gain = 0.3), c + 2, 0.7, listOf(0.0 to 3.8), sendAmt = 0.25, pan = 0.2)
                    bassLine(Patch(wave = TRI, attack = 0.01, decay = 0.6, sustain = 0.3, cutoff = 700.0, gain = 0.45), bassLow + 5,
                        listOf(Triple(0.0, 1.8, 0), Triple(2.0, 1.8, 7)), 1.0)
                    if (melodyOn) melody(music, Patch(wave = TRI, attack = 0.03, cutoff = 2500.0, vibrato = 12.0, gain = 0.3), leadC, 1.0, 0.1, 0.25, 1)
                }
            }
        }

        // ---------------------------------------------------------------------------- drums

        fun noise() = rnd.nextDouble() * 2 - 1

        fun oneShot(sec: Double, f: (Int, Double) -> Double): FloatArray = FloatArray((sec * SR).toInt()) { f(it, it.toDouble() / SR).toFloat() }

        fun kick(): FloatArray {
            val fs = 130 + rnd.nextDouble() * 90; val fe = 42 + rnd.nextDouble() * 18
            val tp = 0.02 + rnd.nextDouble() * 0.03; val ta = 0.12 + rnd.nextDouble() * 0.25
            var ph = 0.0
            return oneShot(min(1.2, ta * 6)) { _, tt ->
                ph += 2 * PI * (fe + (fs - fe) * exp(-tt / tp)) / SR
                tanh(1.6 * (sin(ph) * exp(-tt / ta) + noise() * 0.25 * exp(-tt / 0.003)))
            }
        }

        fun snare(): FloatArray {
            val ft = 170 + rnd.nextDouble() * 60
            val bp = Svf(); bp.set(2500 + rnd.nextDouble() * 2000, 0.6)
            val tn = 0.08 + rnd.nextDouble() * 0.12
            return oneShot(tn * 6) { _, tt -> bp.tick(noise()); sin(2 * PI * ft * tt) * 0.6 * exp(-tt / 0.05) + bp.band * 1.4 * exp(-tt / tn) }
        }

        fun clap(): FloatArray {
            val bp = Svf(); bp.set(1100 + rnd.nextDouble() * 500, 1.3)
            return oneShot(0.6) { _, tt ->
                bp.tick(noise())
                val e = when { tt < 0.01 -> exp(-tt / 0.003); tt < 0.02 -> exp(-(tt - 0.01) / 0.003); else -> exp(-(tt - 0.02) / 0.1) }
                bp.band * 2.5 * e
            }
        }

        fun hat(open: Boolean): FloatArray {
            val hp = Svf(); hp.set(6500 + rnd.nextDouble() * 2500, 0.7)
            val tau = if (open) 0.15 + rnd.nextDouble() * 0.15 else 0.02 + rnd.nextDouble() * 0.03
            return oneShot(tau * 6) { _, tt -> hp.tick(noise()); hp.high * exp(-tt / tau) }
        }

        fun crash(): FloatArray {
            val hp = Svf(); hp.set(4000.0, 0.7)
            val partials = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0).map { it * (1.5 + rnd.nextDouble()) }
            return oneShot(2.5) { _, tt ->
                var m = 0.0
                for (f in partials) m += if (sin(2 * PI * f * tt) > 0) 0.15 else -0.15
                hp.tick(noise() + m); hp.high * exp(-tt / 0.6)
            }
        }

        fun shaker(): FloatArray {
            val bp = Svf(); bp.set(6000 + rnd.nextDouble() * 2000, 1.0)
            return oneShot(0.15) { _, tt -> bp.tick(noise()); bp.band * min(1.0, tt / 0.008) * exp(-tt / 0.03) }
        }

        fun hit(sample: FloatArray, time: Double, vel: Double, pan: Double = 0.0) {
            val s0 = (time * SR).toInt()
            if (s0 < 0 || s0 >= n) return
            val gl = cos((pan + 1) * PI / 4) * vel; val gr = sin((pan + 1) * PI / 4) * vel
            val len = min(sample.size, n - s0)
            for (i in 0 until len) { drums.l[s0 + i] += (sample[i] * gl).toFloat(); drums.r[s0 + i] += (sample[i] * gr).toFloat() }
        }

        fun drumsFor(s: Style) {
            if (s == Style.AMBIENT && !spec.drumsOnly) return
            val k = kick(); val sn = snare(); val cl = clap(); val ch = hat(false); val oh = hat(true); val cr = crash(); val sh = shaker()
            val st = beat / 4 // one sixteenth
            val swing = when (s) { Style.HIPHOP -> 0.33; Style.BLUES -> 0.66; Style.RNB -> 0.25; Style.HOUSE -> 0.12; else -> 0.0 }
            fun at(bar: Int, step: Int): Double {
                var tt = bar * barSec + step * st
                if (step % 2 == 1) tt += swing * st * 0.5
                if (swing >= 0.3 && step % 4 == 2) tt += swing * st * 0.66
                return tt + humanize() * 0.5
            }
            val intro = if (!spec.drumsOnly && spec.introBars == 0 && arr.nextDouble() < 0.4) 1 else 0
            for (b in intro until bars) {
                when (s) {
                    Style.EDM, Style.HOUSE -> {
                        for (q in 0 until 4) hit(k, at(b, q * 4), 1.0)
                        hit(cl, at(b, 4), 0.7); hit(cl, at(b, 12), 0.7)
                        for (q in 0 until 4) hit(oh, at(b, q * 4 + 2), 0.35, 0.2)
                        for (x in 0 until 16) if (x % 2 == 1) hit(ch, at(b, x), 0.18 + 0.05 * (x % 4 / 3), -0.25)
                        if (b % 8 == 0) hit(cr, at(b, 0), 0.3, 0.3)
                    }
                    Style.POP -> {
                        hit(k, at(b, 0), 1.0); hit(k, at(b, 8), 0.9); if (b % 2 == 1) hit(k, at(b, 10), 0.7)
                        hit(sn, at(b, 4), 0.8); hit(sn, at(b, 12), 0.8)
                        for (x in 0 until 16 step 2) hit(ch, at(b, x), 0.25 + if (x % 4 == 0) 0.08 else 0.0, 0.25)
                        if (b % 4 == 0) hit(cr, at(b, 0), 0.25, -0.3)
                    }
                    Style.HIPHOP -> {
                        hit(k, at(b, 0), 1.0); hit(k, at(b, 7), 0.7); hit(k, at(b, 10), 0.85)
                        hit(sn, at(b, 4), 0.9); hit(sn, at(b, 12), 0.9)
                        for (x in 0 until 16 step 2) hit(ch, at(b, x), 0.22 + rnd.nextDouble() * 0.08, 0.2)
                    }
                    Style.TRAP -> {
                        hit(k, at(b, 0), 1.0); if (rnd.nextBoolean()) hit(k, at(b, 11), 0.8)
                        hit(cl, at(b, 8), 0.8); hit(sn, at(b, 8), 0.5)
                        for (x in 0 until 16) {
                            if (x >= 12 && b % 2 == 1) { for (r in 0 until 3) hit(ch, at(b, x) + r * st / 3, 0.18) } else hit(ch, at(b, x), 0.2 + (if (x % 2 == 0) 0.06 else 0.0), 0.15)
                        }
                        if (b % 4 == 3) hit(oh, at(b, 14), 0.25)
                    }
                    Style.RNB -> {
                        hit(k, at(b, 0), 0.8); hit(k, at(b, 9), 0.6)
                        hit(sn, at(b, 4), 0.55); hit(sn, at(b, 12), 0.55)
                        for (x in 0 until 16 step 2) hit(ch, at(b, x), 0.15, 0.2)
                    }
                    Style.ROCK -> {
                        hit(k, at(b, 0), 1.0); hit(k, at(b, 8), 0.95); hit(k, at(b, 6), 0.6)
                        hit(sn, at(b, 4), 1.0); hit(sn, at(b, 12), 1.0)
                        for (x in 0 until 16 step 2) hit(ch, at(b, x), 0.3, 0.3)
                        if (b % 4 == 0) hit(cr, at(b, 0), 0.4, -0.4)
                    }
                    Style.BLUES -> {
                        hit(k, at(b, 0), 0.8); hit(k, at(b, 8), 0.7)
                        hit(sn, at(b, 4), 0.6); hit(sn, at(b, 12), 0.6)
                        for (x in 0 until 16 step 2) hit(ch, at(b, x), 0.2, 0.25)
                    }
                    Style.DNB -> {
                        hit(k, at(b, 0), 1.0); hit(k, at(b, 10), 0.9)
                        hit(sn, at(b, 4), 1.0); hit(sn, at(b, 12), 1.0); hit(sn, at(b, 7), 0.3); hit(sn, at(b, 15), 0.25)
                        for (x in 0 until 16 step 2) hit(ch, at(b, x), 0.25, 0.2)
                        if (b % 4 == 0) hit(cr, at(b, 0), 0.3)
                    }
                    Style.ACOUSTIC -> {
                        for (x in 0 until 16) hit(sh, at(b, x), if (x % 2 == 0) 0.25 else 0.15, 0.3)
                        if (arr.nextDouble() < 0.6) { hit(k, at(b, 0), 0.4); hit(sn, at(b, 12), 0.2) }
                    }
                    Style.AMBIENT -> { hit(k, at(b, 0), 0.6); for (x in 0 until 16 step 4) hit(oh, at(b, x + 2), 0.2) }
                }
            }
        }

        // ---------------------------------------------------------------------------- master

        fun reverb(wetGain: Double) {
            val combL = intArrayOf(1116, 1188, 1277, 1356); val combR = intArrayOf(1139, 1211, 1300, 1379)
            val apL = intArrayOf(556, 441); val apR = intArrayOf(579, 464)
            fun process(input: FloatArray, combs: IntArray, aps: IntArray, out: FloatArray) {
                val cb = combs.map { FloatArray(it) }; val ci = IntArray(combs.size); val lp = DoubleArray(combs.size)
                val ab = aps.map { FloatArray(it) }; val ai = IntArray(aps.size)
                for (i in 0 until n) {
                    var acc = 0.0
                    val x = input[i] * 0.25
                    for (c in cb.indices) {
                        val buf = cb[c]; val y = buf[ci[c]]
                        lp[c] = y * 0.7 + lp[c] * 0.3
                        buf[ci[c]] = (x + lp[c] * 0.84).toFloat()
                        ci[c] = (ci[c] + 1) % buf.size
                        acc += y
                    }
                    for (a in ab.indices) {
                        val buf = ab[a]; val b = buf[ai[a]]
                        val y = -acc + b
                        buf[ai[a]] = (acc + b * 0.5).toFloat()
                        ai[a] = (ai[a] + 1) % buf.size
                        acc = y
                    }
                    out[i] += (acc * wetGain).toFloat()
                }
            }
            process(send.l, combL, apL, music.l); process(send.r, combR, apR, music.r)
        }

        fun master(s: Style): ShortArrayPcm {
            if (!spec.drumsOnly) reverb(0.6)
            val duck = s == Style.EDM || s == Style.HOUSE
            val dg = 10.0.pow(spec.drumGainDb / 20)
            val l = FloatArray(n); val r = FloatArray(n)
            for (i in 0 until n) {
                var g = 1.0
                if (duck) { val tb = (i.toDouble() / SR) % beat; g = 1 - 0.6 * exp(-tb / 0.09) * min(1.0, tb / 0.004 + 0.2) }
                l[i] = ((music.l[i] + bassBus.l[i]) * g + drums.l[i] * dg).toFloat()
                r[i] = ((music.r[i] + bassBus.r[i]) * g + drums.r[i] * dg).toFloat()
            }
            var sum = 0.0
            for (i in 0 until n) sum += l[i] * l[i] + r[i] * r[i]
            val rms = sqrt(sum / (2 * n)).coerceAtLeast(1e-9)
            val target = when (s) { Style.EDM, Style.DNB -> 0.36; Style.HOUSE, Style.TRAP, Style.ROCK -> 0.25; Style.AMBIENT, Style.ACOUSTIC -> 0.1; else -> 0.18 }
            val pre = target / rms
            val out = 10.0.pow(spec.masterGainDb / 20) * 0.95
            for (i in 0 until n) {
                l[i] = (tanh(l[i] * pre) * out).toFloat()
                r[i] = (tanh(r[i] * pre) * out).toFloat()
            }
            return ShortArrayPcm.fromFloats(l, r, SR)
        }
    }
}
