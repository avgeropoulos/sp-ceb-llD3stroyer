package com.roccoquest.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
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

    /** How far the tuning may move a voice: 4 semitones down or up. More starts to sound like a cartoon. */
    const val MIN_SHIFT = 0.79f
    const val MAX_SHIFT = 1.26f

    /**
     * How far the tuning may go when the gentle one would leave a note more than [CLOSE_ENOUGH]
     * off: about 6 semitones down or up (as far as it used to). A wrong note sounds worse than a
     * word that's a little cartoony.
     */
    const val WIDE_MIN_SHIFT = 0.7f
    const val WIDE_MAX_SHIFT = 1.42f

    /** Semitones a note may be off and still sound right (half a semitone off sounds like a wrong note). */
    const val CLOSE_ENOUGH = 0.25f

    /**
     * How far the voice itself may be asked to move (its text-to-speech pitch) for one sung note: a
     * fifth (7 semitones) down or up. Most notes only get the talking voices' small nudge
     * ([VoiceCast.MIN_TWEAK]..[VoiceCast.MAX_TWEAK]); only the outer notes that the small nudge plus
     * the gentle tuning can't reach go further (see [nudge]).
     */
    const val SING_MIN_NUDGE = 0.67f
    const val SING_MAX_NUDGE = 1.5f

    // A note (times the voice's own pitch) that a voice can sing in tune: with the biggest nudge
    // and tuning, and with the gentle ones only. The theme spans 14 semitones and an octave is 12,
    // so the full reach (about 26 semitones) has room for every note in one of the octaves, for
    // any voice from about 80 Hz (a deep man) to 600 Hz (a child squeaking).
    private const val REACH_LOW = SING_MIN_NUDGE * WIDE_MIN_SHIFT
    private const val REACH_HIGH = SING_MAX_NUDGE * WIDE_MAX_SHIFT
    private const val GENTLE_LOW = VoiceCast.MIN_TWEAK * MIN_SHIFT
    private const val GENTLE_HIGH = VoiceCast.MAX_TWEAK * MAX_SHIFT

    /** A word may ring on a little into the next note (smoother than stopping dead on the beat). */
    const val LEGATO = 1.1f

    /** A long word is sped up at most this much to fit its note; past that its end is faded out. */
    const val MIN_SQUEEZE = 0.5f

    /**
     * The pitch to ask a voice that speaks at [voiceHz] for (its text-to-speech pitch, 1 = normal)
     * when it should sing [freq] Hz. Most notes get only the talking voices' small nudge
     * ([VoiceCast.MIN_TWEAK]..[VoiceCast.MAX_TWEAK]; big ones sound robotic) and [fit] tunes the
     * rest of the way gently. A note too far out for that (the top or bottom of the tune) is
     * nudged just far enough for the gentle tuning to finish the job, at most
     * [SING_MIN_NUDGE]..[SING_MAX_NUDGE], so it's still sung in tune.
     */
    fun nudge(freq: Float, voiceHz: Float): Float {
        if (freq <= 0f || voiceHz <= 0f) return 1f
        val want = freq / voiceHz
        val small = want.coerceIn(VoiceCast.MIN_TWEAK, VoiceCast.MAX_TWEAK)
        return when {
            want > small * MAX_SHIFT -> (want / MAX_SHIFT).coerceAtMost(SING_MAX_NUDGE)
            want < small * MIN_SHIFT -> (want / MIN_SHIFT).coerceAtLeast(SING_MIN_NUDGE)
            else -> small
        }
    }

    /**
     * How much [fit] retunes a word it hears at [f0] Hz to sing it at [freq] Hz (more than 1 =
     * higher). Gentle ([MIN_SHIFT]..[MAX_SHIFT]) when that gets within [CLOSE_ENOUGH] of the note;
     * otherwise as far as it takes, up to [WIDE_MIN_SHIFT]..[WIDE_MAX_SHIFT], so no note is sung
     * off-key just to keep the tuning small. 1 when the pitch is unknown.
     */
    fun shift(freq: Float, f0: Float): Float {
        if (freq <= 0f || f0 <= 0f) return 1f
        val exact = freq / f0
        val gentle = exact.coerceIn(MIN_SHIFT, MAX_SHIFT)
        return if (abs(semitones(exact / gentle)) <= CLOSE_ENOUGH) gentle else exact.coerceIn(WIDE_MIN_SHIFT, WIDE_MAX_SHIFT)
    }

    /** How many semitones apart two pitches [ratio] apart are (12 = an octave). */
    private fun semitones(ratio: Float) = 12f * ln(ratio) / ln(2f)

    /**
     * [pcm] (at [rate] Hz) retuned to [freq] Hz and fitted into [seconds] (plus a little [LEGATO]),
     * at [Chiptune.RATE]. The retuning is kept gentle ([shift]) so the words stay clear and human:
     * the voice itself is asked to speak near the right pitch first ([nudge]). A word too
     * long for its note is sped up without changing its pitch ([squeeze]) instead of having its
     * end chopped off, so "friends" doesn't come out as "frie".
     */
    fun fit(pcm: FloatArray, rate: Int, freq: Float, seconds: Float): FloatArray {
        val voice = trim(pcm)
        val f0 = pitch(voice, rate)
        val by = shift(freq, f0)
        // Reading the voice faster raises its pitch; also convert to the music's sample rate.
        val step = by * rate / Chiptune.RATE
        var out = FloatArray((voice.size / step).toInt().coerceAtLeast(0))
        for (i in out.indices) {
            val p = i * step
            val k = p.toInt()
            val f = p - k
            val a = voice[k]
            val b = if (k + 1 < voice.size) voice[k + 1] else a
            out[i] = a + (b - a) * f
        }
        val room = (seconds * LEGATO * Chiptune.RATE).toInt()
        if (out.size > room) {
            val sungHz = if (f0 > 0f) f0 * by else freq
            out = squeeze(out, maxOf(MIN_SQUEEZE, room.toFloat() / out.size), (Chiptune.RATE / sungHz).toInt())
            if (out.size > room) out = out.copyOf(room)
        }
        // Soft, rounded start and end, so no clicks and no hard stops.
        val fadeIn = min(out.size / 4, (Chiptune.RATE * 0.012f).toInt())
        val fadeOut = min(out.size / 3, (Chiptune.RATE * 0.08f).toInt())
        for (i in 0 until fadeIn) out[i] *= ease(i.toFloat() / fadeIn)
        for (i in 0 until fadeOut) out[out.size - 1 - i] *= ease(i.toFloat() / fadeOut)
        return out
    }

    /** 0 -> 0, 1 -> 1, smoothly (a raised cosine). */
    private fun ease(t: Float) = 0.5f - 0.5f * cos(PI.toFloat() * t)

    /**
     * [pcm] made [factor] times as long (less than 1 = shorter) without changing its pitch. Slices
     * two voice periods long are laid back down one period apart; each one is taken from near where
     * it belongs, at the spot whose wave best carries on from the slice before it (so the joins
     * don't buzz, even when the voice's pitch wanders). [period] is the voice's period in samples.
     */
    fun squeeze(pcm: FloatArray, factor: Float, period: Int): FloatArray {
        val win = period * 2
        val outLen = (pcm.size * factor).toInt()
        if (factor >= 1f) return pcm
        if (period < 8 || pcm.size < win * 3) return pcm.copyOf(outLen)
        val out = FloatArray(outLen + win)
        val window = FloatArray(win) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / win) }
        var prev = -1
        var o = 0
        while (o < outLen) {
            val nominal = (o / factor).toInt().coerceIn(0, pcm.size - win)
            var src = nominal
            if (prev >= 0) {
                // The last slice's second half is still sounding: carry on from where it was in the voice.
                val want = prev + period
                var best = -Float.MAX_VALUE
                for (d in -period / 2..period / 2) {
                    val s = nominal + d
                    if (s < 0 || s + win > pcm.size) continue
                    var c = 0f
                    for (i in 0 until period) c += pcm[s + i] * pcm[want + i]
                    if (c > best) { best = c; src = s }
                }
            }
            for (i in 0 until win) out[o + i] += pcm[src + i] * window[i]
            prev = src
            o += period
        }
        return out.copyOf(outLen)
    }

    /**
     * How many times lower than the tune ([notes], in Hz) a voice that speaks at [voiceHz] should
     * sing: 2, 4 or 8 (whole octaves, so it's still the same tune; a man sings an octave below a
     * woman). It's the one where the most notes can be sung in tune ([nudge] and [shift] at their
     * furthest), then the one where the most need only the gentle nudge and tuning, then the one
     * whose middle note is closest to the voice's own pitch, so it sounds the most like itself.
     * (Just picking the octave nearest the middle note left the top or bottom notes out of reach.)
     */
    fun octavesDown(notes: List<Float>, voiceHz: Float): Float {
        val sung = notes.filter { it > 0f }.sorted()
        if (voiceHz <= 0f || sung.isEmpty()) return 4f
        val middle = sung[sung.size / 2]
        fun within(down: Float, low: Float, high: Float) = sung.count { it / down / voiceHz in low..high }
        return listOf(2f, 4f, 8f).maxWith(
            compareBy<Float>(
                { within(it, REACH_LOW, REACH_HIGH) },
                { within(it, GENTLE_LOW, GENTLE_HIGH) },
                { -abs(ln(middle / it / voiceHz)) },
            )
        )
    }

    /** A test tone (used by tests): a voice-like buzz at [freq] Hz. */
    fun tone(freq: Float, seconds: Float, rate: Int): FloatArray =
        FloatArray((seconds * rate).toInt()) { i ->
            val t = i.toFloat() / rate
            (0.5f * kotlin.math.sin(2 * PI.toFloat() * freq * t) + 0.25f * cos(4 * PI.toFloat() * freq * t)) * 0.6f
        }
}
