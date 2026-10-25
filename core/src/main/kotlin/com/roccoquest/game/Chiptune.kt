package com.roccoquest.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A looping song for a tiny NES-style synth: two square-wave voices, a triangle
 * bass and a noise drum kit.
 *
 * Melodic tracks are space-separated tokens `NOTE/LEN`, e.g. `C#5/2`, where LEN is
 * in sixteenth notes and `-` is a rest. [drums] is a one-bar (16 step) pattern that
 * repeats: `k` kick, `s` snare, `h` hi-hat, `.` silence.
 */
class Song(
    val bpm: Int,
    val lead: String,
    val harmony: String,
    val bass: String,
    val drums: String,
    val leadDuty: Float = 0.25f,
    /** An optional SUNG melody (same NOTE/LEN tokens), one syllable per note. */
    val vocal: String = "",
    /** The vowel each sung note is sung on (a, e, i, o, u), one letter per note. */
    val vowels: String = "",
)

object Chiptune {
    const val RATE = 22050

    class Note(val freq: Float, val len: Int)

    private val noteRegex = Regex("""([A-G])([#b]?)(\d)""")
    private val semis = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    fun parse(track: String): List<Note> = track.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { tok ->
        val (name, lenStr) = tok.split('/').also { require(it.size == 2) { "Bad token '$tok'" } }
        val len = lenStr.toInt()
        if (name == "-") return@map Note(0f, len)
        val m = noteRegex.matchEntire(name) ?: error("Bad note '$tok'")
        var semi = semis.getValue(m.groupValues[1][0])
        if (m.groupValues[2] == "#") semi++
        if (m.groupValues[2] == "b") semi--
        val midi = 12 * (m.groupValues[3].toInt() + 1) + semi
        Note((440.0 * 2.0.pow((midi - 69) / 12.0)).toFloat(), len)
    }

    fun length(track: String) = parse(track).sumOf { it.len }

    /** Renders one full loop of [song] as 16-bit mono PCM at [RATE]. */
    fun render(song: Song): ShortArray {
        val units = length(song.lead)
        require(length(song.bass) == units) { "bass is ${length(song.bass)} steps, lead is $units" }
        require(song.harmony.isBlank() || length(song.harmony) == units) { "harmony length mismatch" }
        require(units % song.drums.length == 0) { "drums pattern must tile the song" }
        val perUnit = RATE * 60.0 / song.bpm / 4.0
        val total = (units * perUnit).toInt()
        val mix = FloatArray(total)

        // With a singer, the lead plays softly underneath.
        val leadVol = if (song.vocal.isNotBlank()) 0.05f else 0.17f
        voice(mix, parse(song.lead), perUnit, leadVol) { phase, _ -> if (phase < song.leadDuty) 1f else -1f }
        if (song.vocal.isNotBlank()) {
            require(length(song.vocal) == units) { "vocal is ${length(song.vocal)} steps, lead is $units" }
            sing(mix, parse(song.vocal), song.vowels, perUnit)
        }
        if (song.harmony.isNotBlank()) {
            voice(mix, parse(song.harmony), perUnit, 0.07f) { phase, _ -> if (phase < 0.5f) 1f else -1f }
        }
        voice(mix, parse(song.bass), perUnit, 0.30f) { phase, _ -> 4f * abs(phase - 0.5f) - 1f }
        drums(mix, song.drums, units, perUnit)

        return ShortArray(total) { (mix[it].coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }

    private inline fun voice(out: FloatArray, notes: List<Note>, perUnit: Double, vol: Float, wave: (Float, Float) -> Float) {
        var unit = 0
        for (n in notes) {
            val start = (unit * perUnit).toInt()
            val end = min(out.size, ((unit + n.len) * perUnit).toInt())
            unit += n.len
            if (n.freq <= 0f) continue
            val len = end - start
            // Short gap at the end so repeated notes stay distinct.
            val gate = len - min(len / 6, (RATE * 0.025f).toInt())
            var phase = 0f
            for (i in 0 until len) {
                val t = i.toFloat() / RATE
                val vib = if (t > 0.15f) 1f + 0.006f * sin(2f * PI.toFloat() * 5.5f * t) else 1f
                phase = (phase + n.freq * vib / RATE) % 1f
                val attack = min(1f, t / 0.004f)
                val decay = 1f - 0.25f * min(1f, t / 0.12f)
                val release = if (i < gate) 1f else 1f - (i - gate).toFloat() / (len - gate).coerceAtLeast(1)
                out[start + i] += wave(phase, t) * vol * attack * decay * release
            }
        }
    }

    /** Vowel sounds: the two loudest "formant" pitches of a singing voice. */
    private fun formants(v: Char) = when (v) {
        'e' -> 440f to 1950f
        'i' -> 330f to 2300f
        'o' -> 500f to 850f
        'u' -> 350f to 700f
        else -> 760f to 1200f // 'a'
    }

    /**
     * A singing voice: a soft buzzy source shaped by two vowel resonances (like a mouth), gentle vibrato
     * that grows on long notes, smooth glides between notes, and a second voice a tiny bit out of tune
     * singing along (so it sounds like people, not a robot), plus a little room echo.
     */
    private fun sing(out: FloatArray, notes: List<Note>, vowels: String, perUnit: Double) {
        val voc = FloatArray(out.size)
        var unit = 0
        var sung = 0
        var prevFreq = 0f
        for (n in notes) {
            val start = (unit * perUnit).toInt()
            val end = min(out.size, ((unit + n.len) * perUnit).toInt())
            unit += n.len
            if (n.freq <= 0f) { prevFreq = 0f; continue }
            val vowel = vowels.getOrElse(sung++) { 'a' }
            val (f1, f2) = formants(vowel)
            val len = end - start
            val gate = len - min(len / 5, (RATE * 0.05f).toInt())
            for (copy in 0..1) {
                val detune = if (copy == 0) 1f else 1.0045f
                val vol = if (copy == 0) 0.55f else 0.35f
                // Two resonators per copy (the vowel), as simple two-pole filters.
                var y1a = 0f; var y2a = 0f; var y1b = 0f; var y2b = 0f
                fun coeffs(f: Float, bw: Float): Pair<Float, Float> {
                    val r = kotlin.math.exp(-PI.toFloat() * bw / RATE)
                    return (2f * r * kotlin.math.cos(2f * PI.toFloat() * f / RATE)) to (-r * r)
                }
                val (a1a, a2a) = coeffs(f1, 90f)
                val (a1b, a2b) = coeffs(f2, 120f)
                var phase = copy * 0.37f
                for (i in 0 until len) {
                    val t = i.toFloat() / RATE
                    // Slide up from the last note for the first 40 ms, like a real singer.
                    val glide = if (prevFreq > 0f && t < 0.04f) prevFreq + (n.freq - prevFreq) * (t / 0.04f) else n.freq
                    val depth = 0.004f + 0.012f * min(1f, t / 0.5f)
                    val vib = 1f + depth * sin(2f * PI.toFloat() * 5.2f * t + copy)
                    phase = (phase + glide * detune * vib / RATE) % 1f
                    // A soft source: a rounded sawtooth (less harsh than a square wave).
                    val src = (2f * phase - 1f) - (2f * phase - 1f) * (2f * phase - 1f) * (2f * phase - 1f) * 0.3f
                    val ya = src * 0.12f + a1a * y1a + a2a * y2a; y2a = y1a; y1a = ya
                    val yb = src * 0.08f + a1b * y1b + a2b * y2b; y2b = y1b; y1b = yb
                    val attack = min(1f, t / 0.035f)
                    val release = if (i < gate) 1f else 1f - (i - gate).toFloat() / (len - gate).coerceAtLeast(1)
                    voc[start + i] += (ya + yb * 0.7f) * vol * attack * release
                }
            }
            prevFreq = n.freq
        }
        // Set the singer's loudness from how loud the voice really came out (the vowel filters ring a lot).
        var sum = 0.0
        var count = 0
        for (v in voc) if (v != 0f) { sum += v * v; count++ }
        val rms = if (count == 0) 1.0 else kotlin.math.sqrt(sum / count)
        val gain = (0.16 / rms).toFloat()
        // A short room echo, then into the mix (gently squashed so it never crackles).
        val delay = (RATE * 0.09f).toInt()
        for (i in voc.indices) {
            val wet = if (i >= delay) voc[i - delay] * 0.25f else 0f
            val x = (voc[i] + wet) * gain
            out[i] += x / (1f + abs(x))
        }
    }

    private fun drums(out: FloatArray, pattern: String, units: Int, perUnit: Double) {
        var seed = 0x2545F491
        fun noise(): Float {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            return (seed and 0xFFFF) / 32768f - 1f
        }
        for (u in 0 until units) {
            val c = pattern[u % pattern.length]
            if (c == '.') continue
            val start = (u * perUnit).toInt()
            val dur = when (c) { 'k' -> 0.11f; 's' -> 0.13f; else -> 0.035f }
            val n = min((dur * RATE).toInt(), out.size - start)
            var phase = 0f
            var prev = 0f
            for (i in 0 until n) {
                val t = i.toFloat() / n
                val env = (1f - t) * (1f - t)
                out[start + i] += when (c) {
                    'k' -> {
                        phase += (130f - 90f * t) / RATE
                        sin(2f * PI.toFloat() * phase) * 0.38f * env
                    }
                    's' -> {
                        phase += 190f / RATE
                        (noise() * 0.16f + sin(2f * PI.toFloat() * phase) * 0.08f) * env
                    }
                    else -> {
                        val x = noise()
                        val hp = x - prev
                        prev = x
                        hp * 0.05f * env
                    }
                }
            }
        }
    }
}
