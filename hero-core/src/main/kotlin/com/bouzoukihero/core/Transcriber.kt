package com.bouzoukihero.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the bouzouki melody in a song's audio, on the phone, the way Bouzouki Tabs
 * does on a computer but without its neural network:
 *
 * 1. A high-pass filter drops the bass and the kick drum.
 * 2. Plucks are found as sudden jumps in the bright part of the spectrum (a plectrum
 *    hitting metal strings), which singing and pads don't have.
 * 3. Each pluck's pitch comes from summing the harmonics of every candidate note
 *    just after the attack, where the string's tone is clearest.
 * 4. Fast repeated plucks on one note are merged into a tremolo note.
 *
 * Input is mono audio at [Synth.RATE]; note times are [t0] plus the position in it.
 */
object Transcriber {
    private const val N = 2048
    private const val NO = 512
    const val HOP = 256
    private const val RATE = Synth.RATE
    private const val LOW_MIDI = 48.0 // C3, the bouzouki's lowest course
    private const val HIGH_MIDI = 91.0

    private val hann = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val hannO = FloatArray(NO) { (0.5 - 0.5 * cos(2 * PI * it / (NO - 1))).toFloat() }

    /** A possible note, with what was measured about it. */
    internal class Candidate(
        val note: SourceNote,
        /** The pitch before rounding, as a fractional MIDI number. */
        val frac: Double,
        /** Share of the pitch frames that agreed on the note. */
        val agree: Double,
        /** How much of the sound the note's harmonics explain (tonal vs. noisy). */
        val tonal: Double,
        /** Loudness right after the pluck, against the loud parts of the song nearby. */
        val loud: Double,
        val flux: Double,
    )

    fun transcribe(input: FloatArray, t0: Double = 0.0): List<SourceNote> {
        val cands = candidates(input, t0)
        val seconds = input.size.toDouble() / RATE
        // Strictest first: only the clear bouzouki line. On busy records (a loud singer, a
        // full band) that can leave almost nothing, so loosen step by step until there are
        // enough notes to play, ending with every pitched pluck in time with the music.
        for (tier in TIERS) {
            val kept = cands.filter { tier.keeps(it) }
            if (kept.size >= seconds * MIN_NOTES_PER_SECOND || tier === TIERS.last()) {
                return mergeTremolo(retune(kept))
            }
        }
        return emptyList()
    }

    /**
     * Records are often a little sharp or flat of A440 (Bouzouki Tabs does the same):
     * find the band's tuning from how far the notes sit from the nearest semitone, and
     * round each note relative to that.
     */
    private fun retune(kept: List<Candidate>): List<SourceNote> {
        if (kept.isEmpty()) return emptyList()
        val devs = kept.map { it.frac - Math.round(it.frac) }.sorted()
        val tuning = devs[devs.size / 2]
        return kept.map { c -> c.note.copy(pitch = Fretboard.fitToRange(Math.round(c.frac - tuning).toInt())) }
    }

    internal class Tier(val agree: Double, val tonal: Double, val loud: Double) {
        fun keeps(c: Candidate) = c.agree >= agree && c.tonal >= tonal && c.loud >= loud
    }

    /**
     * Strict: a steady, tonal note about as loud as the loudest playing around it (the
     * melody, not the band strumming behind it or a drum). Then looser; the last one only
     * rejects noise (drums), which measures about 3.4 for tonality.
     */
    internal val TIERS = listOf(Tier(0.55, 5.0, 0.85), Tier(0.5, 4.2, 0.6), Tier(0.45, 3.9, 0.3))

    /** Below this, a stretch of music is treated as too sparse and the filter loosens. */
    private const val MIN_NOTES_PER_SECOND = 0.6

    internal fun keep(c: Candidate) = TIERS.first().keeps(c)

    internal fun candidates(input: FloatArray, t0: Double = 0.0): List<Candidate> {
        if (input.size < N + 4 * HOP) return emptyList()
        val x = highpass(input, 170.0)
        val frames = (x.size - N) / HOP + 1
        val flux = FloatArray(frames)
        val energy = FloatArray(frames)
        val re = FloatArray(NO)
        val im = FloatArray(NO)
        var prev = FloatArray(NO / 2)
        var cur = FloatArray(NO / 2)
        val binHz = RATE.toFloat() / NO
        val lo = (150 / binHz).toInt()
        val hi = min(NO / 2 - 1, (9000 / binHz).toInt())
        for (f in 0 until frames) {
            val s = f * HOP + N / 2 - NO / 2
            var e = 0f
            for (i in 0 until NO) {
                val v = x[s + i]
                re[i] = v * hannO[i]
                im[i] = 0f
                e += v * v
            }
            energy[f] = sqrt(e / NO)
            fft(re, im)
            var fl = 0f
            for (b in lo..hi) {
                val m = ln(1f + 200f * sqrt(re[b] * re[b] + im[b] * im[b]))
                cur[b] = m
                val d = m - prev[b]
                if (d > 0 && f > 0) fl += if (b * binHz > 1500) d else 0.4f * d
            }
            flux[f] = fl
            val t = prev; prev = cur; cur = t
        }

        val onsets = pickOnsets(flux, energy)
        val spec = FloatArray(N / 2)
        val notes = ArrayList<Candidate>()
        // Loud parts of the song: 90th percentile of energy in a 4-second neighbourhood.
        val span = (2.0 * RATE / HOP).toInt()
        for ((k, on) in onsets.withIndex()) {
            val next = onsets.getOrElse(k + 1) { frames }
            // Pitch from 45 ms to 180 ms after the pluck, while the string rings.
            val from = on + 4
            val to = min(next - 1, on + 15)
            if (to < from || to >= frames) continue
            val votes = HashMap<Int, Double>()
            val sums = HashMap<Int, Double>()
            var total = 0.0
            var tonal = 0.0
            var count = 0
            for (f in from..to) {
                spectrum(x, f * HOP, spec)
                val (m, sal) = bestPitch(spec)
                if (m < 0 || sal <= 0) continue
                val r = m.roundToInt()
                votes[r] = (votes[r] ?: 0.0) + sal
                sums[r] = (sums[r] ?: 0.0) + sal * m
                total += sal
                tonal += sal
                count++
            }
            val best = votes.maxByOrNull { it.value } ?: continue
            var midi = best.key
            while (midi < LOW_MIDI) midi += 12
            while (midi > HIGH_MIDI) midi -= 12
            // The note lasts until the next pluck or until it has died away.
            val peak = (on..min(frames - 1, on + 5)).maxOf { energy[it] }
            var end = on + 1
            val maxEnd = min(next, on + (3.0 * RATE / HOP).toInt())
            while (end < maxEnd && energy[end] > peak * 0.18f) end++
            val around = energy.copyOfRange(max(0, on - span), min(frames, on + span)).sortedArray()
            val ref = around[(around.size * 0.9).toInt().coerceAtMost(around.size - 1)]
            notes.add(
                Candidate(
                    SourceNote(t0 + frameTime(on), (end - on).toDouble() * HOP / RATE, midi),
                    frac = (sums[best.key] ?: 0.0) / best.value,
                    agree = best.value / total,
                    tonal = tonal / count,
                    loud = (peak / max(ref, 1e-9f)).toDouble(),
                    flux = flux[on].toDouble(),
                )
            )
        }
        return notes
    }

    private fun frameTime(f: Int) = (f * HOP + N / 2).toDouble() / RATE

    /** Peaks in the onset curve that clearly stand out from what's around them. */
    private fun pickOnsets(flux: FloatArray, energy: FloatArray): List<Int> {
        val out = ArrayList<Int>()
        if (flux.isEmpty()) return out
        val loud = energy.maxOrNull() ?: 0f
        if (loud < 1e-4f) return out
        val sorted = flux.sortedArray()
        val floor = sorted[(sorted.size * 0.6).toInt().coerceAtMost(sorted.size - 1)]
        val top = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)]
        val minGap = (0.055 * RATE / HOP).toInt()
        var last = -minGap
        for (f in 2 until flux.size - 2) {
            val v = flux[f]
            if (v < flux[f - 1] || v < flux[f - 2] || v <= flux[f + 1] || v < flux[f + 2]) continue
            // Compare with the recent past: a pluck stands well above its surroundings.
            var sum = 0f
            var n = 0
            for (j in max(0, f - 16)..min(flux.size - 1, f + 4)) { sum += flux[j]; n++ }
            val local = sum / n
            if (v < local * 1.6f || v < floor + 0.12f * (top - floor)) continue
            if (energy[min(energy.size - 1, f + 3)] < loud * 0.03f) continue
            if (f - last < minGap) {
                if (out.isNotEmpty() && v > flux[out.last()]) { out[out.size - 1] = f; last = f }
                continue
            }
            out.add(f)
            last = f
        }
        return out
    }

    private val re2 = ThreadLocal.withInitial { FloatArray(N) }
    private val im2 = ThreadLocal.withInitial { FloatArray(N) }

    private fun spectrum(x: FloatArray, start: Int, out: FloatArray) {
        val re = re2.get()
        val im = im2.get()
        for (i in 0 until N) {
            re[i] = if (start + i < x.size) x[start + i] * hann[i] else 0f
            im[i] = 0f
        }
        fft(re, im)
        for (b in 0 until N / 2) out[b] = sqrt(sqrt(re[b] * re[b] + im[b] * im[b]))
    }

    /** The note whose harmonics add up strongest (harmonic summation), and how strong. */
    private fun bestPitch(spec: FloatArray): Pair<Double, Double> {
        val binHz = RATE.toDouble() / N
        var best = -1.0
        var bestS = 0.0
        var m = LOW_MIDI - 0.5
        while (m <= HIGH_MIDI + 0.5) {
            val s = salience(spec, m, binHz)
            if (s > bestS) { bestS = s; best = m }
            m += 0.1
        }
        if (best < 0) return -1.0 to 0.0
        // Everything in the frame, to judge how much of it this note explains.
        var all = 0.0
        for (b in (100 / binHz).toInt() until min(spec.size, (6000 / binHz).toInt())) all += spec[b]
        return best to bestS / max(all, 1e-9) * 100
    }

    private fun salience(spec: FloatArray, midi: Double, binHz: Double): Double {
        val f0 = 440.0 * 2.0.pow((midi - 69) / 12)
        var s = 0.0
        var w = 1.0
        for (h in 1..8) {
            val b = f0 * h / binHz
            if (b >= spec.size - 2) break
            val c = b.roundToInt()
            s += w * max(spec[c], max(spec[c - 1], spec[c + 1]) * 0.8f)
            w *= 0.82
        }
        return s
    }

    /** Fast repeated plucks of one note become a single tremolo note. */
    fun mergeTremolo(notes: List<SourceNote>): List<SourceNote> {
        val out = ArrayList<SourceNote>()
        var i = 0
        val s = notes.sortedBy { it.time }
        while (i < s.size) {
            var j = i
            // Tremolo strokes come every 60-100 ms; often only every other one is heard.
            while (j + 1 < s.size && s[j + 1].pitch == s[i].pitch &&
                (s[j + 1].time - s[j].time < 0.22 || (s[j].tremolo && s[j + 1].time - (s[j].time + s[j].duration) < 0.25))
            ) j++
            if (j - i >= 3 || (j > i && s.subList(i, j + 1).any { it.tremolo })) {
                val end = s[j].time + s[j].duration
                out.add(SourceNote(s[i].time, end - s[i].time, s[i].pitch, tremolo = true))
            } else {
                for (k in i..j) out.add(s[k])
            }
            i = j + 1
        }
        return out
    }

    fun highpass(x: FloatArray, hz: Double): FloatArray {
        // Second-order Butterworth high-pass, run twice for a steeper slope.
        val w = 2 * PI * hz / RATE
        val q = 0.7071
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        val a0 = 1 + alpha
        val b0 = ((1 + c) / 2 / a0).toFloat()
        val b1 = (-(1 + c) / a0).toFloat()
        val a1 = (-2 * c / a0).toFloat()
        val a2 = ((1 - alpha) / a0).toFloat()
        var y = x
        repeat(2) {
            val out = FloatArray(y.size)
            var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
            for (i in y.indices) {
                val v = y[i]
                val r = b0 * v + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
                out[i] = r
                x2 = x1; x1 = v; y2 = y1; y1 = r
            }
            y = out
        }
        return y
    }

    /** In-place radix-2 FFT; the size must be a power of two. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Transcribes while the song plays: audio comes in a chunk at a time with the song
 * time it ended at, and notes come out a second or so behind the music.
 */
class LiveTranscriber(private val latency: Double) {
    private val rate = Synth.RATE
    private var buf = FloatArray(rate * 12)
    private var n = 0
    private var bufT0 = 0.0
    private var committed = Double.NEGATIVE_INFINITY
    private var pending = 0
    private val notes = ArrayList<SourceNote>()

    val count get() = notes.size

    /** [pcm] is mono at [Synth.RATE]; [songTimeAtEnd] is the song time when it was read. */
    @Synchronized fun feed(pcm: FloatArray, songTimeAtEnd: Double): List<SourceNote> {
        if (pcm.isEmpty()) return emptyList()
        val end = songTimeAtEnd - latency
        val expected = bufT0 + (n + pcm.size).toDouble() / rate
        var flushed: List<SourceNote> = emptyList()
        if (n == 0 || abs(expected - end) > 0.35) {
            // A seek, pause or gap: finish what we have and start fresh.
            if (n > 0) flushed = process(final = true)
            n = 0
            bufT0 = end - pcm.size.toDouble() / rate
        } else {
            bufT0 += (end - expected) * 0.05 // follow slow drift between the clocks
        }
        if (n + pcm.size > buf.size) buf = buf.copyOf(max(buf.size * 2, n + pcm.size))
        System.arraycopy(pcm, 0, buf, n, pcm.size)
        n += pcm.size
        pending += pcm.size
        if (pending < rate * 1.5) return flushed
        pending = 0
        return flushed + process(final = false)
    }

    /** All the notes heard so far, with tremolo merged across chunks. */
    @Synchronized fun finish(): List<SourceNote> {
        if (n > 0) process(final = true)
        n = 0
        return Transcriber.mergeTremolo(notes)
    }

    private fun process(final: Boolean): List<SourceNote> {
        val found = Transcriber.transcribe(buf.copyOf(n), bufT0)
        val bufEnd = bufT0 + n.toDouble() / rate
        // The last stretch isn't final yet: a pluck there may still be ringing.
        val limit = if (final) Double.MAX_VALUE else bufEnd - 1.2
        val new = found.filter { it.time > committed + 0.04 && it.time <= limit }
        notes.addAll(new)
        if (!final) {
            committed = max(committed, limit)
            // Keep 3 seconds of context before the unfinished part.
            val keepFrom = ((limit - 3.0 - bufT0) * rate).toInt()
            if (keepFrom > 0) {
                System.arraycopy(buf, keepFrom, buf, 0, n - keepFrom)
                n -= keepFrom
                bufT0 += keepFrom.toDouble() / rate
            }
        } else if (new.isNotEmpty()) {
            committed = max(committed, new.last().time)
        }
        return new
    }
}
