package com.bouzoukihero.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Plays one bouzouki note into a buffer at [Synth.RATE]. */
fun interface LeadVoice {
    fun note(out: FloatArray, t: Double, midi: Int, len: Double, amp: Float)
}

/**
 * One recorded bouzouki note: mono audio at [Synth.RATE] that starts right at the
 * pluck, and the pitch it was played at (as a MIDI number, fractional for strings
 * that aren't tuned to A440).
 */
class Sample(val pcm: FloatArray, val root: Double) {
    val name get() = Charts.noteName(root.roundToInt()) + (root.roundToInt() / 12 - 1)
}

/**
 * Plays the melody with real recorded bouzouki notes. Each note uses the recording
 * closest in pitch, sped up or slowed down to the right note, so a few recordings
 * (one per course is plenty) cover the whole neck.
 */
class Sampler(samples: List<Sample>) : LeadVoice {
    val samples = samples.sortedBy { it.root }
    private var level = 1f

    init {
        require(samples.isNotEmpty()) { "No samples" }
        // Match the synth's loudness so the band's balance stays the same.
        val ref = FloatArray(Synth.RATE)
        val mine = FloatArray(Synth.RATE)
        Synth.synthVoice.note(ref, 0.0, 69, 0.8, 0.36f)
        note(mine, 0.0, 69, 0.8, 0.36f)
        val r = rms(mine)
        if (r > 1e-6) level = (rms(ref) / r).toFloat().coerceIn(0.05f, 20f)
    }

    fun nearest(midi: Int): Sample = samples.minBy { abs(it.root - midi) }

    override fun note(out: FloatArray, t: Double, midi: Int, len: Double, amp: Float) {
        val s = nearest(midi)
        val ratio = 2.0.pow((midi - s.root) / 12.0)
        val start = (t * Synth.RATE).toInt()
        if (start < 0 || start >= out.size) return
        val n = min(out.size - start, (len * Synth.RATE).toInt())
        val fade = min(n, (0.04 * Synth.RATE).toInt())
        val g = amp / 0.36f * level
        var p = 0.0
        for (i in 0 until n) {
            val k = p.toInt()
            if (k + 1 >= s.pcm.size) break
            val f = (p - k).toFloat()
            val v = s.pcm[k] + (s.pcm[k + 1] - s.pcm[k]) * f
            val env = if (i > n - fade) (n - i).toFloat() / fade else 1f
            out[start + i] += v * g * env
            p += ratio
        }
    }

    private fun rms(a: FloatArray) = sqrt(a.sumOf { it.toDouble() * it } / a.size)
}

/** Turns a raw recording into a [Sample]: finds the pluck, trims it and hears its pitch. */
object Samples {
    /** The longest a note is kept. Longer notes in a song just fade out. */
    const val MAX_SECONDS = 3.5

    class NoNote(msg: String) : Exception(msg)

    fun prepare(raw: FloatArray, rate: Int): Sample {
        val pcm = if (rate == Synth.RATE) raw else resample(raw, rate, Synth.RATE)
        val peak = pcm.maxOfOrNull { abs(it) } ?: 0f
        if (peak < 0.02f) throw NoNote("That was too quiet. Hold the phone near the bouzouki and pluck firmly.")
        // The pluck: the first moment the sound gets loud. Start a few ms before it.
        val threshold = peak * 0.25f
        var onset = pcm.indexOfFirst { abs(it) >= threshold }
        onset = max(0, onset - (0.004 * Synth.RATE).toInt())
        val end = min(pcm.size, onset + (MAX_SECONDS * Synth.RATE).toInt())
        val note = pcm.copyOfRange(onset, end)
        val freq = pitch(note, Synth.RATE)
            ?: throw NoNote("Couldn't hear a clear note. Pluck one string (or one course) and let it ring.")
        val root = 69 + 12 * ln(freq / 440.0) / ln(2.0)
        if (root < 40 || root > 96) throw NoNote("That doesn't sound like a bouzouki note (heard %.0f Hz).".format(freq))
        // Normalize, and fade the tail so it never clicks.
        val g = 0.9f / note.maxOf { abs(it) }
        val fade = min(note.size, (0.15 * Synth.RATE).toInt())
        for (i in note.indices) {
            note[i] *= g
            if (i >= note.size - fade) note[i] *= (note.size - i).toFloat() / fade
        }
        // A tiny fade-in avoids a click if the recording started mid-wave.
        for (i in 0 until min(note.size, 40)) note[i] *= i / 40f
        return Sample(note, root)
    }

    /**
     * Pitch in Hz using the YIN method, measured a little after the pluck where the
     * string's tone has settled. Null if no clear pitch is found.
     */
    fun pitch(pcm: FloatArray, rate: Int): Double? {
        val win = (0.06 * rate).toInt()
        val maxTau = rate / 60
        val minTau = rate / 1400
        val starts = listOf(0.08, 0.15, 0.25).map { (it * rate).toInt() }
        val found = ArrayList<Double>()
        for (s0 in starts) {
            if (s0 + win + maxTau >= pcm.size) continue
            val d = DoubleArray(maxTau + 1)
            for (tau in 1..maxTau) {
                var sum = 0.0
                for (i in 0 until win) {
                    val x = pcm[s0 + i] - pcm[s0 + i + tau]
                    sum += x * x
                }
                d[tau] = sum
            }
            // Cumulative mean normalized difference.
            val c = DoubleArray(maxTau + 1)
            c[0] = 1.0
            var running = 0.0
            for (tau in 1..maxTau) {
                running += d[tau]
                c[tau] = if (running > 0) d[tau] * tau / running else 1.0
            }
            var tau = minTau
            var best = -1
            while (tau < maxTau) {
                if (c[tau] < 0.15) {
                    while (tau + 1 < maxTau && c[tau + 1] < c[tau]) tau++
                    best = tau
                    break
                }
                tau++
            }
            if (best < 0) continue
            // Parabolic interpolation for a fractional period.
            val a = c[best - 1]
            val b = c[best]
            val cc = c[best + 1]
            val den = a - 2 * b + cc
            val shift = if (abs(den) > 1e-12) 0.5 * (a - cc) / den else 0.0
            found.add(rate / (best + shift))
        }
        if (found.isEmpty()) return null
        return found.sorted()[found.size / 2]
    }

    fun resample(pcm: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || pcm.isEmpty()) return pcm
        // Average neighbours first when shrinking, so high tones don't fold back down.
        val src = if (from > to) {
            val k = max(1, from / to)
            FloatArray(pcm.size) { i ->
                var s = 0f
                var n = 0
                for (j in i until min(pcm.size, i + k)) { s += pcm[j]; n++ }
                s / n
            }
        } else pcm
        val n = (pcm.size.toLong() * to / from).toInt()
        val step = from.toDouble() / to
        return FloatArray(n) { i ->
            val p = i * step
            val k = p.toInt()
            if (k + 1 >= src.size) src[src.size - 1] else src[k] + (src[k + 1] - src[k]) * (p - k).toFloat()
        }
    }

    /** Reads a 16-bit PCM WAV (mono or stereo). */
    fun readWav(bytes: ByteArray): Pair<FloatArray, Int> {
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Not a WAV file" }
        var pos = 12
        var channels = 1
        var rate = Synth.RATE
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = b.getInt(pos + 4)
            if (id == "fmt ") {
                channels = b.getShort(pos + 10).toInt()
                rate = b.getInt(pos + 12)
                bits = b.getShort(pos + 22).toInt()
            } else if (id == "data") {
                require(bits == 16) { "Only 16-bit WAV files are supported" }
                val frames = min(len, bytes.size - pos - 8) / (2 * channels)
                val out = FloatArray(frames)
                for (f in 0 until frames) {
                    var s = 0f
                    for (ch in 0 until channels) s += b.getShort(pos + 8 + (f * channels + ch) * 2) / 32768f
                    out[f] = s / channels
                }
                return out to rate
            }
            pos += 8 + len + (len and 1)
        }
        throw IllegalArgumentException("WAV file has no audio")
    }

    /** Writes mono 16-bit PCM WAV at [Synth.RATE]. */
    fun writeWav(pcm: FloatArray): ByteArray {
        val len = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + len).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + len).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(Synth.RATE).putInt(Synth.RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(len)
        for (v in pcm) b.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }
}
