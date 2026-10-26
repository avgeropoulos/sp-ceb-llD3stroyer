package com.roccoquest.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a spoken word into a sung note: finds how high the voice is, retunes it to the note
 * and fits it into the note's time. Used to make the phone's own voice sing the theme song's
 * words (see [Chiptune.render]'s `words`).
 */
object Autotune {
    /** The voice's pitch in Hz (from its loudest, steadiest part), or 0 if it can't tell. */
    fun pitch(pcm: FloatArray, rate: Int): Float {
        val win = (rate * 0.04f).toInt()
        if (pcm.size < win * 2) return 0f
        val minLag = rate / 500
        val maxLag = rate / 70
        val found = ArrayList<Float>()
        var start = 0
        while (start + win + maxLag < pcm.size) {
            var energy = 0f
            for (i in start until start + win) energy += pcm[i] * pcm[i]
            if (energy / win > 1e-4f) {
                val corr = FloatArray(maxLag + 1)
                var best = 0f
                for (lag in minLag..maxLag) {
                    var c = 0f; var e2 = 0f
                    for (i in start until start + win) { c += pcm[i] * pcm[i + lag]; e2 += pcm[i + lag] * pcm[i + lag] }
                    corr[lag] = c / sqrt(energy * e2 + 1e-9f)
                    best = maxOf(best, corr[lag])
                }
                // The first peak that's nearly as strong as the best one is the real pitch
                // (twice the period matches just as well, and would sing an octave too low).
                var lag = minLag + 1
                var pick = 0
                while (lag < maxLag) {
                    if (corr[lag] >= best * 0.9f && corr[lag] >= corr[lag - 1] && corr[lag] >= corr[lag + 1]) { pick = lag; break }
                    lag++
                }
                if (best > 0.6f && pick > 0) found += rate.toFloat() / pick
            }
            start += win / 2
        }
        if (found.isEmpty()) return 0f
        found.sort()
        return found[found.size / 2]
    }

    /** Cuts quiet bits off the start and end. */
    fun trim(pcm: FloatArray): FloatArray {
        var peak = 0f
        for (v in pcm) peak = maxOf(peak, kotlin.math.abs(v))
        if (peak == 0f) return pcm
        val t = peak * 0.04f
        var a = 0
        while (a < pcm.size && kotlin.math.abs(pcm[a]) < t) a++
        var b = pcm.size - 1
        while (b > a && kotlin.math.abs(pcm[b]) < t) b--
        return pcm.copyOfRange(a, b + 1)
    }

    /**
     * [pcm] (at [rate] Hz) retuned to [freq] Hz and fitted into [seconds], at [Chiptune.RATE].
     * The retuning is kept gentle (at most half an octave) so the words stay clear: the voice
     * itself is asked to speak near the right pitch first.
     */
    fun fit(pcm: FloatArray, rate: Int, freq: Float, seconds: Float): FloatArray {
        val voice = trim(pcm)
        val f0 = pitch(voice, rate)
        val shift = if (f0 > 0f) (freq / f0).coerceIn(0.7f, 1.42f) else 1f
        // Reading the voice faster raises its pitch; also convert to the music's sample rate.
        val step = shift * rate / Chiptune.RATE
        val outLen = min((voice.size / step).toInt(), (seconds * Chiptune.RATE).toInt())
        val out = FloatArray(outLen.coerceAtLeast(0))
        for (i in out.indices) {
            val p = i * step
            val k = p.toInt()
            val f = p - k
            val a = voice[k]
            val b = if (k + 1 < voice.size) voice[k + 1] else a
            out[i] = a + (b - a) * f
        }
        // Soft start and end, so no clicks.
        val fadeIn = min(out.size / 4, (Chiptune.RATE * 0.01f).toInt())
        val fadeOut = min(out.size / 3, (Chiptune.RATE * 0.06f).toInt())
        for (i in 0 until fadeIn) out[i] *= i.toFloat() / fadeIn
        for (i in 0 until fadeOut) out[out.size - 1 - i] *= i.toFloat() / fadeOut
        return out
    }

    /** A test tone (used by tests): a voice-like buzz at [freq] Hz. */
    fun tone(freq: Float, seconds: Float, rate: Int): FloatArray =
        FloatArray((seconds * rate).toInt()) { i ->
            val t = i.toFloat() / rate
            (0.5f * kotlin.math.sin(2 * PI.toFloat() * freq * t) + 0.25f * cos(4 * PI.toFloat() * freq * t)) * 0.6f
        }
}
