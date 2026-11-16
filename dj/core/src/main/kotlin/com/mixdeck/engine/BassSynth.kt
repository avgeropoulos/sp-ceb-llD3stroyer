package com.mixdeck.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The bass keyboard's sounds. The first three are a plucked bass-guitar string (played with a
 * finger, with a pick, or muted with the palm); SUB is a plain deep tone under everything.
 */
enum class BassSound(val label: String) { FINGER("FINGER"), PICK("PICK"), MUTED("MUTED"), SUB("SUB") }

/**
 * A small bass you play over the mix (the BASS keys on the console, a computer's keys or a MIDI
 * keyboard). The guitar sounds are a model of a real string: a short pluck goes round and round a
 * delay as long as the note's wave, losing a little treble each time, the way a string rings and
 * mellows. A few notes can sound at once. Call everything under the mixer lock; [render] adds to
 * the mix, so the bass is heard and recorded like the sampler.
 */
class BassSynth(private val sr: Int) {
    var sound = BassSound.FINGER
    var volume = 0.8f

    private inner class Voice {
        var note = -1; var gate = false; var t = 0.0; var rel = 0.0; var born = 0L
        var snd = BassSound.FINGER
        // The string: one wave of it, going round.
        val buf = FloatArray(sr / 18 + 8)
        var len = 0.0; var pos = 0; var loopLp = 0.0; var toneLp = 0.0; var toneLp2 = 0.0; var energy = 0.0
        // SUB
        var phase = 0.0; var level = 0.0
    }
    private val voices = Array(4) { Voice() }
    private var clock = 0L
    private var seed = 12345

    /** Any note sounding (held or still ringing out). */
    val active get() = voices.any { it.note >= 0 }
    fun isHeld(note: Int) = voices.any { it.note == note && it.gate }

    private fun freq(note: Int) = 440.0 * 2.0.pow((note - 69) / 12.0)
    private fun noise(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 9) and 0xFFFF) / 32768f - 1f }

    /** Starts MIDI note [note] (36 = C2). Playing a note that is still ringing plucks it again. */
    fun noteOn(note: Int) {
        val v = voices.firstOrNull { it.note == note } ?: voices.firstOrNull { it.note < 0 }
            ?: voices.filter { !it.gate }.minByOrNull { it.born } ?: voices.minByOrNull { it.born }!!
        if (v.note < 0) { v.phase = 0.0; v.level = 0.0; v.toneLp = 0.0; v.toneLp2 = 0.0 }
        v.note = note; v.gate = true; v.t = 0.0; v.rel = 0.0; v.born = ++clock; v.snd = sound
        if (v.snd != BassSound.SUB) pluck(v)
    }

    /**
     * Sets the string going: its shape right after the finger or pick lets go. A finger gives a
     * round start, a pick a brighter one; plucking near the neck end (a jazz bass played over the
     * front pickup) takes out some of the harmonics, which is what makes it sound deep and woody.
     */
    private fun pluck(v: Voice) {
        // The loop's own filter delays the wave by about half a sample: take it off the length.
        v.len = (sr / freq(v.note) - 0.5).coerceIn(8.0, (v.buf.size - 4).toDouble())
        val n = v.len.toInt() + 2
        val bright = when (v.snd) { BassSound.PICK -> 0.55; BassSound.MUTED -> 0.22; else -> 0.16 }
        var lp = 0.0; var lp2 = 0.0; var peak = 1e-6f
        for (i in 0 until n) {
            lp += (noise() - lp) * bright; lp2 += (lp - lp2) * bright
            v.buf[i] = lp2.toFloat()
        }
        // Where it was plucked: about a fifth of the way along the string.
        val at = (n * 0.21).toInt().coerceAtLeast(1)
        val shaped = FloatArray(n) { v.buf[it] - 0.85f * v.buf[(it + n - at) % n] }
        for (x in shaped) if (abs(x) > peak) peak = abs(x)
        for (i in 0 until n) v.buf[i] = shaped[i] / peak
        v.pos = 0; v.loopLp = 0.0; v.energy = 1.0
    }

    /** Lets go of [note]: the string is damped (the tone fades out quickly). */
    fun noteOff(note: Int) { for (v in voices) if (v.note == note) v.gate = false }
    fun allOff() { for (v in voices) v.gate = false }

    fun render(out: FloatArray, frames: Int, master: Float) {
        val dt = 1.0 / sr
        val g = 0.55 * volume * master
        for (v in voices) {
            if (v.note < 0) continue
            if (v.snd == BassSound.SUB) renderSub(v, out, frames, g, dt) else renderString(v, out, frames, g)
        }
    }

    private fun renderString(v: Voice, out: FloatArray, frames: Int, g: Double) {
        val f = freq(v.note)
        // How long the note rings (seconds to fade away), held and after letting go.
        val ring = if (!v.gate) 0.10 else when (v.snd) { BassSound.MUTED -> 0.45; BassSound.PICK -> 7.0; else -> 6.0 }
        val keep = exp(-6.9 / (f * ring))                    // loudness kept each time round
        val damp = if (!v.gate) 0.75 else when (v.snd) { BassSound.MUTED -> 0.5; BassSound.PICK -> 0.12; else -> 0.22 }
        // The tone control and the amp: a jazz bass with the treble rolled back.
        val cutoff = when (v.snd) { BassSound.PICK -> 2400.0; BassSound.MUTED -> 700.0; else -> 1100.0 }
        val a = (2 * PI * cutoff / sr).coerceAtMost(1.0)
        val n = v.len.toInt(); val frac = v.len - n
        val size = n + 1
        var peak = 0.0
        for (i in 0 until frames) {
            // Read one wave back (between two samples, so the note is in tune).
            val r0 = v.pos; val r1 = if (r0 + 1 >= size) 0 else r0 + 1
            val y = v.buf[r0] * frac + v.buf[r1] * (1 - frac)
            // Each time round the string loses a little treble and a little loudness.
            v.loopLp += (y - v.loopLp) * (1 - damp)
            v.buf[r0] = (v.loopLp * keep).toFloat()
            v.pos = r1
            v.toneLp += (y - v.toneLp) * a; v.toneLp2 += (v.toneLp - v.toneLp2) * a
            val s = tanh(v.toneLp2 * 2.2) * 0.95
            if (abs(s) > peak) peak = abs(s)
            val x = (s * g).toFloat()
            out[i * 2] += x; out[i * 2 + 1] += x
        }
        v.t += frames.toDouble() / sr
        if (!v.gate) v.rel += frames.toDouble() / sr
        v.energy = peak
        if ((peak < 0.0006 && v.t > 0.05) || v.rel > 1.0) { v.note = -1; v.gate = false }
    }

    private fun renderSub(v: Voice, out: FloatArray, frames: Int, g: Double, dt: Double) {
        val f = freq(v.note)
        for (i in 0 until frames) {
            val want = if (v.gate) 1.0 else 0.0
            val rate = if (!v.gate) dt / 0.14 * 4 else 0.004 * 44100 / sr
            v.level += (want - v.level) * rate.coerceAtMost(1.0)
            v.phase += f * dt
            if (v.phase >= 1) v.phase -= 1
            val ph = v.phase * 2 * PI
            val x = ((sin(ph) + 0.22 * sin(2 * ph)) * v.level * g).toFloat()
            out[i * 2] += x; out[i * 2 + 1] += x
            if (!v.gate) v.rel += dt
        }
        if (!v.gate && (v.level < 0.0008 || v.rel > 1.0)) v.note = -1
    }

    companion object {
        /** Semitone steps of a major and a natural minor scale, with the octave on top: eight keys. */
        private val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
        private val MINOR = intArrayOf(0, 2, 3, 5, 7, 8, 10, 12)

        /**
         * The eight notes the bass keys play for a song in [key] (A minor when the key isn't
         * known): its scale from the root up, low enough to be a bass line. [octave] 1 is one
         * octave higher.
         */
        fun scale(key: MusicalKey?, octave: Int = 0): IntArray {
            val k = key ?: MusicalKey(9, true)
            val root = (if (k.tonic >= 4) 24 else 36) + k.tonic + 12 * octave
            return (if (k.minor) MINOR else MAJOR).map { root + it }.toIntArray()
        }

        fun noteName(note: Int) = MusicalKey.NAMES[Math.floorMod(note, 12)]
    }
}
