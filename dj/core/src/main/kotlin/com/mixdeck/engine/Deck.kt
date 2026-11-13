package com.mixdeck.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * One turntable. It plays a decoded track at a variable speed (like vinyl, so tempo and pitch
 * move together), can be scratched, and has a cue point, four hot cues, beat loops, a trim,
 * a three-band EQ, a filter and an effect.
 *
 * All methods must be called while holding [Mixer.lock]; the mixer's audio thread holds it while
 * it renders.
 */
class Deck(val index: Int, private val sr: Int) {
    var pcm: Pcm? = null; private set
    var analysis: TrackAnalysis? = null; private set
    var title: String = ""; private set
    var artist: String = ""; private set

    /** Play head, in frames of the track's own sample rate. */
    var position = 0.0; private set
    var playing = false; private set
    /** Tempo adjustment as a fraction (0.05 = +5%). */
    var pitch = 0.0
        set(v) { field = v.coerceIn(-pitchRange, pitchRange) }
    var pitchRange = 0.08
        set(v) { field = v; pitch = pitch }

    /** Vinyl mode: touching the top of the jog wheel scratches. Off: it bends the tempo. */
    var vinylMode = true
    var scratching = false; private set
    private var scratchTarget = 0.0
    private var bend = 0.0
    private var step = 0.0

    var cuePoint = 0.0; private set
    private var cuePreview = false
    /** Playing only while CUE is held (a pre-listen, not a real start). */
    val previewing: Boolean get() = cuePreview
    val hotCues = DoubleArray(4) { Double.NaN }

    var loopIn = -1.0; private set
    var loopOut = -1.0; private set
    var loopOn = false; private set
    /** Length of the current loop in beats, or 0 for a manual loop. */
    var loopBeats = 0.0; private set

    /** Trim in dB, on top of the automatic gain that evens out loud and quiet tracks. */
    var trimDb = 0.0
    var autoGainDb = 0.0; private set
    /** Channel fader, 0..1. */
    var volume = 1f
    var eqLow = 0f; var eqMid = 0f; var eqHigh = 0f
    var filter = 0f
    /** Send this deck to the headphones (split-cue mode). */
    var headphone = false

    val eq = ThreeBandEq(sr)
    private val djFilter = DjFilter(sr)
    val fx = EffectUnit(sr)

    /** Recent peak level after the EQ, 0..1, for the meters. */
    var level = 0f; private set

    val loaded get() = pcm != null
    val bpm get() = analysis?.bpm ?: 0.0
    val trackRate get() = pcm?.sampleRate ?: sr
    val durationSec get() = (pcm?.frames ?: 0) / trackRate.toDouble()
    val positionSec get() = position / trackRate
    /** The tempo the deck is playing at right now. */
    val effectiveBpm get() = bpm * (1 + pitch) * (1 + bend)
    val framesPerBeat get() = if (bpm > 0) trackRate * 60.0 / bpm else 0.0

    /** The song's key at normal speed, if known. */
    val key: MusicalKey? get() = analysis?.key

    /** How far the tempo slider has moved the pitch, in semitones (no key lock, like vinyl). */
    val pitchSemitones: Double get() = MusicalKey.semitonesForSpeed(1 + pitch)

    /**
     * The key you hear right now: the song's key moved by the tempo slider. Null when unknown, or
     * when the pitch sits between two keys (then it's out of tune with songs at standard pitch).
     */
    val soundingKey: MusicalKey? get() {
        val k = key ?: return null
        val shift = MusicalKey.wholeSemitones(pitchSemitones) ?: return null
        return k.transpose(shift)
    }
    private val firstBeatFrames get() = (analysis?.firstBeatSec ?: 0.0) * trackRate

    fun load(track: Pcm, info: TrackAnalysis, title: String, artist: String, loudnessDb: Double) {
        pcm = track; analysis = info; this.title = title; this.artist = artist
        playing = false; cuePreview = false; scratching = false; step = 0.0; bend = 0.0
        loopOn = false; loopIn = -1.0; loopOut = -1.0; loopBeats = 0.0
        hotCues.fill(Double.NaN)
        // Start at the first beat so the first press of PLAY lands on the downbeat.
        cuePoint = if (info.bpm > 0) firstBeatFrames % framesPerBeat else 0.0
        position = cuePoint
        autoGainDb = (-14.0 - loudnessDb).coerceIn(-12.0, 12.0)
        fx.clear()
    }

    fun eject() {
        pcm = null; analysis = null; title = ""; artist = ""; playing = false; position = 0.0
    }

    /** Replaces the beat grid, e.g. after the user doubles or halves a detected tempo. */
    fun setAnalysis(a: TrackAnalysis) { analysis = a }

    fun playPause() {
        if (!loaded) return
        if (cuePreview) { cuePreview = false; return } // keep playing from the cue preview
        playing = !playing
    }

    fun play() { if (loaded) { playing = true; cuePreview = false } }
    fun pause() { playing = false; cuePreview = false }

    /** The classic CDJ cue button, pressed. */
    fun cueDown(quantize: Boolean) {
        if (!loaded) return
        if (playing) {
            playing = false; position = cuePoint
        } else if (abs(position - cuePoint) < 1) {
            playing = true; cuePreview = true
        } else {
            cuePoint = if (quantize) nearestBeat(position) else position
            position = cuePoint
        }
    }

    /** The cue button, released: ends a cue preview. */
    fun cueUp() {
        if (cuePreview) { cuePreview = false; playing = false; position = cuePoint }
    }

    /** Sets an empty hot cue, or jumps to a set one. */
    fun hotCue(i: Int, quantize: Boolean) {
        if (!loaded) return
        if (hotCues[i].isNaN()) {
            hotCues[i] = if (quantize) nearestBeat(position) else position
        } else {
            jumpTo(hotCues[i], quantize)
            if (!playing) cuePoint = hotCues[i]
        }
    }

    fun clearHotCue(i: Int) { hotCues[i] = Double.NaN }

    /** Jumps keeping the beat phase if quantize is on and the deck is playing. */
    private fun jumpTo(target: Double, quantize: Boolean) {
        if (quantize && playing && bpm > 0) {
            val fpb = framesPerBeat
            val phaseNow = floorMod(position - firstBeatFrames, fpb)
            val phaseTarget = floorMod(target - firstBeatFrames, fpb)
            var d = phaseNow - phaseTarget
            if (d > fpb / 2) d -= fpb
            position = target + d
        } else {
            position = target
        }
        if (loopOn && (position < loopIn || position > loopOut)) loopOn = false
    }

    fun seekFraction(f: Double) {
        val p = pcm ?: return
        position = (f.coerceIn(0.0, 1.0) * p.frames)
        if (loopOn && (position < loopIn || position > loopOut)) loopOn = false
    }

    /** Moves the play head by a number of beats (beat jump). */
    fun beatJump(beats: Double) {
        if (!loaded) return
        val d = if (bpm > 0) beats * framesPerBeat else beats * trackRate * 0.5
        position = (position + d).coerceAtLeast(0.0)
        if (loopOn) { loopIn += d; loopOut += d }
    }

    /** Starts a loop of [beats] beats from the nearest beat, or turns the active loop off. */
    fun autoLoop(beats: Double, quantize: Boolean) {
        if (!loaded) return
        if (loopOn && loopBeats == beats) { loopOn = false; return }
        val fpb = if (bpm > 0) framesPerBeat else trackRate * 0.5
        val start = when {
            loopOn -> loopIn
            quantize && bpm > 0 -> beatAtOrBefore(position)
            else -> position
        }
        loopIn = start; loopOut = start + beats * fpb; loopBeats = beats; loopOn = true
        if (position >= loopOut) position = loopIn + floorMod(position - loopIn, loopOut - loopIn)
    }

    fun loopInHere(quantize: Boolean) {
        if (!loaded) return
        loopIn = if (quantize && bpm > 0) nearestBeat(position) else position
        loopOn = false; loopOut = -1.0; loopBeats = 0.0
    }

    fun loopOutHere(quantize: Boolean) {
        if (!loaded || loopIn < 0) return
        val out = if (quantize && bpm > 0) nearestBeat(position) else position
        if (out <= loopIn + trackRate * 0.01) return
        loopOut = out; loopOn = true; loopBeats = if (bpm > 0) (out - loopIn) / framesPerBeat else 0.0
    }

    fun loopToggle() {
        if (loopOut > loopIn && loopIn >= 0) {
            loopOn = !loopOn
            if (loopOn && position > loopOut) position = loopIn
        }
    }

    /** Halves (0.5) or doubles (2.0) the loop length. */
    fun loopResize(factor: Double) {
        if (loopOut <= loopIn) return
        val len = (loopOut - loopIn) * factor
        if (len < trackRate * 0.005) return
        loopOut = loopIn + len
        if (loopBeats > 0) loopBeats *= factor
        if (loopOn && position >= loopOut) position = loopIn + floorMod(position - loopIn, len)
    }

    /** Jog wheel touched: scratches in vinyl mode (or always, with [forceScratch], for the waveform). */
    fun jogTouch(down: Boolean, forceScratch: Boolean = false) {
        if (!loaded) return
        if (down && (vinylMode || forceScratch)) { scratching = true; scratchTarget = position }
        if (!down) scratching = false
    }

    /**
     * The jog wheel turned by [revolutions] (positive = clockwise). One turn is 1.8 seconds of
     * audio, the same as a record at 33⅓ RPM.
     */
    fun jogMove(revolutions: Double) {
        if (!loaded) return
        if (scratching) {
            scratchTarget += revolutions * 1.8 * trackRate
        } else {
            // Nudge: speed up or slow down briefly to line the beats up by ear.
            bend = (bend + revolutions * 0.6).coerceIn(-0.3, 0.3)
            if (!playing) position = (position + revolutions * 1.8 * trackRate).coerceAtLeast(0.0)
        }
    }

    fun beatAtOrBefore(pos: Double): Double {
        val fpb = framesPerBeat
        if (fpb <= 0) return pos
        return firstBeatFrames + floor((pos - firstBeatFrames) / fpb) * fpb
    }

    fun nearestBeat(pos: Double): Double {
        val fpb = framesPerBeat
        if (fpb <= 0) return pos
        val k = ((pos - firstBeatFrames) / fpb).roundToLong()
        return (firstBeatFrames + k * fpb).coerceAtLeast(0.0)
    }

    /** Where in the current beat the play head is, 0..1. */
    fun beatPhase(): Double {
        val fpb = framesPerBeat
        if (fpb <= 0) return 0.0
        return floorMod(position - firstBeatFrames, fpb) / fpb
    }

    /** Index of the beat under the play head (beat 0 is the grid anchor). */
    fun beatIndex(): Long {
        val fpb = framesPerBeat
        if (fpb <= 0) return 0
        return floor((position - firstBeatFrames) / fpb).toLong()
    }

    /** Matches this deck's tempo and beat phase to [master]. */
    /**
     * Sets the tempo slider so this deck plays at [targetBpm] (or half/double it if that's closer,
     * e.g. 87 against 174), widening the slider's range if needed. Does nothing without a BPM.
     */
    fun matchTempo(targetBpm: Double) {
        if (bpm <= 0 || !(targetBpm > 0) || targetBpm.isInfinite()) return
        var target = targetBpm
        while (target / bpm > 1.5) target /= 2
        while (target / bpm < 0.67) target *= 2
        val needed = target / bpm - 1
        if (abs(needed) > pitchRange) pitchRange = if (abs(needed) <= 0.16) 0.16 else 0.5
        pitch = needed
    }

    fun syncTo(master: Deck) {
        if (bpm <= 0 || master.bpm <= 0) return
        matchTempo(master.bpm * (1 + master.pitch))
        if (playing && master.playing) {
            var d = master.beatPhase() - beatPhase()
            if (d > 0.5) d -= 1 else if (d < -0.5) d += 1
            position += d * framesPerBeat
        }
    }

    /**
     * Renders [frames] frames of this deck, after trim, EQ, filter and effect but before the
     * channel fader, into [out] (interleaved stereo, overwritten).
     */
    fun render(out: FloatArray, frames: Int) {
        val p = pcm
        if (p == null) { out.fill(0f, 0, frames * 2); level *= 0.9f; return }
        val ratio = p.sampleRate.toDouble() / sr
        val follow = 1 - exp(-1.0 / (0.012 * sr))
        val glide = 1 - exp(-1.0 / (0.02 * sr))
        val bendDecay = exp(-1.0 / (0.15 * sr))
        val gain = dbToGain(autoGainDb + trimDb)
        eq.set(eqLow, eqMid, eqHigh)
        djFilter.set(filter)
        val bpmNow = effectiveBpm
        fx.beatSeconds = if (bpmNow > 0) 60.0 / bpmNow else 0.5
        var peak = 0f

        for (i in 0 until frames) {
            val target = when {
                scratching -> ((scratchTarget - position) / (0.015 * sr)).coerceIn(-8 * ratio, 8 * ratio)
                playing -> (1 + pitch) * (1 + bend) * ratio
                else -> 0.0
            }
            step += (target - step) * if (scratching) follow else glide
            bend *= bendDecay

            val speed = abs(step) / ratio
            var l = 0.0; var r = 0.0
            if (speed > 1e-4) {
                val amp = (speed * 25).coerceAtMost(1.0)
                l = cubic(p, position, 0) * amp * gain
                r = cubic(p, position, 1) * amp * gain
                l = djFilter.process(eq.process(l, 0), 0)
                r = djFilter.process(eq.process(r, 1), 1)
            }
            out[i * 2] = l.toFloat(); out[i * 2 + 1] = r.toFloat()
            val al = abs(l).toFloat(); if (al > peak) peak = al

            position += step
            if (loopOn && step > 0 && position >= loopOut) position -= (loopOut - loopIn)
            if (position < 0) position = 0.0
            if (position >= p.frames) {
                position = p.frames.toDouble(); playing = false; cuePreview = false
            }
        }
        fx.process(out, frames)
        level = maxOf(peak.coerceAtMost(1f), level * 0.85f)
    }

    private fun cubic(p: Pcm, pos: Double, ch: Int): Double {
        val i = floor(pos).toInt()
        val t = pos - i
        val y0 = sample(p, i - 1, ch); val y1 = sample(p, i, ch)
        val y2 = sample(p, i + 1, ch); val y3 = sample(p, i + 2, ch)
        val c1 = 0.5 * (y2 - y0)
        val c2 = y0 - 2.5 * y1 + 2 * y2 - 0.5 * y3
        val c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2)
        return ((c3 * t + c2) * t + c1) * t + y1
    }

    private fun sample(p: Pcm, i: Int, ch: Int): Double {
        if (i < 0 || i >= p.frames) return 0.0
        return (if (ch == 0) p.left(i) else p.right(i)).toDouble()
    }

    private fun floorMod(a: Double, m: Double): Double {
        val r = a % m
        return if (r < 0) r + m else r
    }
}
