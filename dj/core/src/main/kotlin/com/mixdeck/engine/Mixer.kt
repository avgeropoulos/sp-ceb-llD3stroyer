package com.mixdeck.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

enum class XfadeCurve { SMOOTH, SCRATCH }

/**
 * Two decks, a crossfader, a master limiter and a sampler. The audio thread calls [render];
 * the UI changes settings inside `synchronized(mixer.lock) { ... }`.
 */
class Mixer(val sampleRate: Int) {
    val lock = Any()
    val decks = arrayOf(Deck(0, sampleRate), Deck(1, sampleRate))
    val sampler = Sampler(sampleRate)

    /** -1 = all deck A, +1 = all deck B. */
    var crossfader = 0f
    var curve = XfadeCurve.SMOOTH
    var master = 0.85f
    var quantize = true

    /**
     * Split cue for a single pair of headphones: the left ear gets the mix and the right ear
     * gets the decks marked [Deck.headphone], so you can line up the next track.
     */
    var splitCue = false

    /** Called with the finished master mix (interleaved stereo) on the audio thread, e.g. to record. */
    var tap: ((FloatArray, Int) -> Unit)? = null

    var masterLevel = 0f; private set

    private val limiter = Limiter(sampleRate)
    private var bufA = FloatArray(0)
    private var bufB = FloatArray(0)
    private var bufCue = FloatArray(0)
    private var smoothXa = 1f
    private var smoothXb = 1f

    /** Gains for decks A and B for a crossfader position. */
    fun crossfaderGains(x: Float = crossfader, c: XfadeCurve = curve): Pair<Float, Float> {
        val t = ((x + 1) / 2).coerceIn(0f, 1f)
        return when (c) {
            // Constant power, with both decks at full volume in the middle.
            XfadeCurve.SMOOTH -> (cos(t * PI / 2) * SQRT2).toFloat().coerceAtMost(1f) to
                (sin(t * PI / 2) * SQRT2).toFloat().coerceAtMost(1f)
            // A fast cut near each end, full volume for both everywhere else.
            XfadeCurve.SCRATCH -> ((1 - t) / 0.06f).coerceIn(0f, 1f) to (t / 0.06f).coerceIn(0f, 1f)
        }
    }

    /** MIX: the one-tap blend into the song waiting on the other deck. */
    val autoMix = AutoMix(this)

    /** MIX was tapped: start a blend, or take over the one that's running. */
    fun mixPressed() = autoMix.pressed()

    /** Matches deck [i]'s tempo and phase to the other deck. */
    fun sync(i: Int) = decks[i].syncTo(decks[1 - i])

    /** Deck [k] is really playing (holding CUE to pre-listen doesn't count). */
    private fun live(k: Int) = decks[k].let { it.playing && !it.previewing }

    /** The other deck's beat when a waiting ▶ was pressed (it starts when this changes). */
    private val pendingBeat = LongArray(2)
    /**
     * The other deck's place in its beat at the last check: in a loop of a beat or less the beat
     * never changes, so a waiting ▶ also starts when this wraps round (as MIX does).
     */
    private val pendingPhase = DoubleArray(2)

    /**
     * ▶ on deck [i] (the console's button and the keyboard). Plays or pauses, except when the
     * other deck plays in time with this one and quantize is on: then the deck starts exactly on
     * the other deck's beat, now if that beat has only just gone by, else on the next one
     * ([Deck.pendingStart]; a second press cancels the wait). Does nothing on an empty deck.
     */
    fun playPressed(i: Int) {
        val d = decks[i]; val o = decks[1 - i]
        if (!d.loaded) return
        if (d.pendingStart) { d.pendingStart = false; return }
        if (d.previewing || d.playing) { d.playPause(); return }
        val bp = d.beatPosition()
        val inTime = quantize && live(1 - i) && d.bpm > 0 && o.bpm > 0 &&
            abs(d.effectiveBpm / o.effectiveBpm - 1) < 0.005 && abs(bp - Math.round(bp)) < 0.02
        if (!inTime) { d.playPause(); return }
        val phase = o.beatPhase()
        if (phase <= 0.2) d.startAt(d.nearestBeat(d.position) + phase * d.framesPerBeat)
        else { d.pendingStart = true; pendingBeat[i] = floor(o.beatPosition()).toLong(); pendingPhase[i] = phase }
    }

    /** Starts a waiting ▶ when the other deck reaches its next beat (or stops playing). */
    private fun startPending() {
        for (k in 0..1) {
            val d = decks[k]
            if (!d.pendingStart) continue
            val o = decks[1 - k]
            when {
                d.playing || !d.loaded -> d.pendingStart = false
                !live(1 - k) -> d.startAt(d.position)
                // Checked before this block is rendered, so both decks move on together from here.
                // Starting from the beat the deck sits on (within 2% of one) puts the two exactly in phase.
                else -> {
                    val ph = o.beatPhase()
                    if (floor(o.beatPosition()).toLong() != pendingBeat[k] || ph < pendingPhase[k]) {
                        d.startAt(d.nearestBeat(d.position) + ph * d.framesPerBeat)
                    } else pendingPhase[k] = ph
                }
            }
        }
    }

    fun render(out: FloatArray, frames: Int) {
        if (bufA.size < frames * 2) {
            bufA = FloatArray(frames * 2); bufB = FloatArray(frames * 2); bufCue = FloatArray(frames * 2)
        }
        startPending()
        autoMix.step(frames)
        val a = decks[0]; val b = decks[1]
        a.render(bufA, frames)
        b.render(bufB, frames)
        // MIX blends with the smooth curve even when the sharp one (for scratching) is chosen.
        val (xa, xb) = crossfaderGains(c = if (autoMix.state == AutoMix.State.RUNNING) XfadeCurve.SMOOTH else curve)
        val va = a.volume * a.volume; val vb = b.volume * b.volume
        val anyCue = a.headphone || b.headphone
        val m = master
        var peak = 0f
        for (i in 0 until frames) {
            // De-zipper the crossfader so fast cuts don't click.
            smoothXa += (xa - smoothXa) * 0.02f
            smoothXb += (xb - smoothXb) * 0.02f
            for (ch in 0..1) {
                val k = i * 2 + ch
                out[k] = (bufA[k] * va * smoothXa + bufB[k] * vb * smoothXb) * m
                if (splitCue) {
                    bufCue[k] = if (anyCue) {
                        (if (a.headphone) bufA[k] else 0f) + (if (b.headphone) bufB[k] else 0f)
                    } else out[k]
                }
            }
        }
        sampler.render(out, frames, m)
        limiter.process(out, frames)
        for (i in 0 until frames * 2) { val v = abs(out[i]); if (v > peak) peak = v }
        masterLevel = maxOf(peak, masterLevel * 0.85f)
        tap?.invoke(out, frames)
        if (splitCue) {
            for (i in 0 until frames) {
                val mono = (out[i * 2] + out[i * 2 + 1]) * 0.5f
                val cue = ((bufCue[i * 2] + bufCue[i * 2 + 1]) * 0.5f).coerceIn(-1f, 1f)
                out[i * 2] = mono
                out[i * 2 + 1] = cue
            }
        }
    }
}

private const val SQRT2 = 1.4142135623730951
