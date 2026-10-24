package com.bouzouki.core

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * YIN pitch detection (de Cheveigné & Kawahara, 2002) for one frame of audio.
 * Finds the fundamental of the loudest single melody line, which for rebetiko
 * is usually the bouzouki or the voice.
 */
class Yin(
    private val sampleRate: Int,
    private val window: Int = 1024,
    minHz: Double = 75.0,
    maxHz: Double = 1500.0,
    private val threshold: Double = 0.15,
) {
    private val minLag = (sampleRate / maxHz).toInt().coerceAtLeast(2)
    private val maxLag = (sampleRate / minHz).toInt()
    private val diff = DoubleArray(maxLag + 2)

    /** Samples a frame needs. */
    val frameSize = window + maxLag + 2

    /** The pitch of [frame] (at least [frameSize] samples from [offset]) in Hz, or null if it isn't a clear note. */
    fun detect(frame: FloatArray, offset: Int = 0): Double? {
        for (lag in 1..maxLag + 1) {
            var sum = 0.0
            for (j in 0 until window) {
                val d = frame[offset + j] - frame[offset + j + lag]
                sum += d * d
            }
            diff[lag] = sum
        }
        // Cumulative mean normalized difference.
        var running = 0.0
        diff[0] = 1.0
        for (lag in 1..maxLag + 1) {
            running += diff[lag]
            diff[lag] = if (running == 0.0) 1.0 else diff[lag] * lag / running
        }
        var lag = minLag
        while (lag <= maxLag) {
            if (diff[lag] < threshold) {
                while (lag + 1 <= maxLag && diff[lag + 1] < diff[lag]) lag++
                // Parabolic interpolation around the dip for sub-sample accuracy.
                val a = diff[lag - 1]; val b = diff[lag]; val c = diff[lag + 1]
                val denom = a - 2 * b + c
                val shift = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
                return sampleRate / (lag + shift.coerceIn(-1.0, 1.0))
            }
            lag++
        }
        return null
    }
}

/** MIDI note nearest to [hz]. */
fun hzToMidi(hz: Double): Int = (69 + 12 * ln(hz / 440.0) / ln(2.0)).roundToInt()

/**
 * Follows the melody of a stream of audio and turns it into notes: feed it samples as they
 * arrive and it reports when a new note starts and when the melody falls silent. It also
 * keeps a tally of how long each pitch class sounded, for guessing the dromos.
 */
class NoteTracker(sampleRate: Int) {
    sealed interface Event
    data class NoteOn(val midi: Int) : Event
    data object NoteOff : Event

    private val yin = Yin(sampleRate)
    private val hop = 1024
    private val frame = yin.frameSize
    private val buf = FloatArray(frame + hop * 8)
    private var write = 0
    private var sinceHop = 0

    private var candidate = -1
    private var candidateFrames = 0
    private var current = -1
    private var silentFrames = 0

    /** Seconds each pitch class (0 = C) has sounded as a melody note. */
    val pitchClassTime = DoubleArray(12)
    private val secondsPerHop = hop.toDouble() / sampleRate

    /** Feeds [count] samples; returns the events they produced, oldest first. */
    fun process(samples: FloatArray, count: Int = samples.size): List<Event> {
        val events = ArrayList<Event>(1)
        for (i in 0 until count) {
            if (write == buf.size) { // keep only the latest frame, every few hops
                System.arraycopy(buf, write - frame, buf, 0, frame)
                write = frame
            }
            buf[write++] = samples[i]
            if (write >= frame && ++sinceHop >= hop) {
                sinceHop = 0
                analyse(write - frame)?.let { events.add(it) }
            }
        }
        return events
    }

    fun reset() {
        write = 0; sinceHop = 0; candidate = -1; candidateFrames = 0; current = -1; silentFrames = 0
        pitchClassTime.fill(0.0)
    }

    private fun analyse(offset: Int): Event? {
        var energy = 0.0
        for (j in offset until offset + frame) energy += buf[j] * buf[j]
        val rms = sqrt(energy / frame)
        val hz = if (rms > 0.008) yin.detect(buf, offset) else null
        if (hz == null) {
            candidate = -1; candidateFrames = 0
            if (current >= 0 && ++silentFrames >= 6) { current = -1; return NoteOff }
            return null
        }
        silentFrames = 0
        val midi = hzToMidi(hz)
        if (midi == candidate) candidateFrames++ else { candidate = midi; candidateFrames = 1 }
        if (candidateFrames >= 2) pitchClassTime[Math.floorMod(midi, 12)] += secondsPerHop
        // A note has to hold for two frames (about 40 ms) before it counts, to skip glitches.
        if (candidateFrames == 2 && midi != current) {
            current = midi
            return NoteOn(midi)
        }
        return null
    }
}

/** Guesses the dromos and root from how long each pitch class sounded. */
object DromosGuess {
    data class Guess(val dromos: Dromos, val rootPc: Int, val score: Double)

    /** Best guesses first. Dromoi with the same notes (Ousak and Kiourdi) are listed once. */
    fun rank(pitchClassTime: DoubleArray): List<Guess> {
        val total = pitchClassTime.sum()
        if (total <= 0.0) return emptyList()
        val p = DoubleArray(12) { pitchClassTime[it] / total }
        val seen = HashSet<List<Int>>()
        val guesses = ArrayList<Guess>()
        for (d in Dromos.entries) {
            if (!seen.add(d.semitones.toList())) continue
            for (root in 0 until 12) {
                var score = 0.0
                for (pc in 0 until 12) {
                    score += if (Math.floorMod(pc - root, 12) in d.semitones) p[pc] else -1.5 * p[pc]
                }
                // The tonic is where the melody keeps returning, so it weighs extra.
                score += 0.6 * p[root] + 0.2 * p[(root + 7) % 12]
                guesses.add(Guess(d, root, score))
            }
        }
        return guesses.sortedByDescending { it.score }
    }
}
