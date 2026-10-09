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

        voice(mix, parse(song.lead), perUnit, 0.17f) { phase, _ -> if (phase < song.leadDuty) 1f else -1f }
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
