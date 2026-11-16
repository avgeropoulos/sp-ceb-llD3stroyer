package com.mixdeck.engine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** The bass keyboard's sounds. */
enum class BassSound(val label: String) { SUB("SUB"), B808("808"), PLUCK("PLUCK") }

/**
 * A small bass synth you play over the mix (the BASS keys on the console, a computer's keys or a
 * MIDI keyboard). A few notes can sound at once. Call everything under the mixer lock; [render]
 * adds to the mix, so the bass is heard and recorded like the sampler.
 */
class BassSynth(private val sr: Int) {
    var sound = BassSound.SUB
    var volume = 0.8f

    private class Voice {
        var note = -1; var gate = false; var phase = 0.0; var t = 0.0; var rel = 0.0; var level = 0.0; var lp = 0.0
        var born = 0L
    }
    private val voices = Array(4) { Voice() }
    private var clock = 0L

    /** Any note sounding (held or still ringing out). */
    val active get() = voices.any { it.note >= 0 }
    fun isHeld(note: Int) = voices.any { it.note == note && it.gate }

    /** Starts MIDI note [note] (36 = C2). Playing a note that is still ringing starts it again. */
    fun noteOn(note: Int) {
        val v = voices.firstOrNull { it.note == note } ?: voices.firstOrNull { it.note < 0 }
            ?: voices.filter { !it.gate }.minByOrNull { it.born } ?: voices.minByOrNull { it.born }!!
        // Keep the phase when re-using a sounding voice, so the new note doesn't click.
        if (v.note < 0) { v.phase = 0.0; v.lp = 0.0; v.level = 0.0 }
        v.note = note; v.gate = true; v.t = 0.0; v.rel = 0.0; v.born = ++clock
    }

    /** Lets go of [note]: it fades out quickly. */
    fun noteOff(note: Int) { for (v in voices) if (v.note == note) v.gate = false }
    fun allOff() { for (v in voices) v.gate = false }

    fun render(out: FloatArray, frames: Int, master: Float) {
        val dt = 1.0 / sr
        val g = 0.55 * volume * master
        for (v in voices) {
            if (v.note < 0) continue
            val base = 440.0 * 2.0.pow((v.note - 69) / 12.0)
            val snd = sound
            val relTime = if (snd == BassSound.PLUCK) 0.08 else 0.14
            for (i in 0 until frames) {
                val t = v.t
                // How loud the note wants to be now; the voice glides there so nothing clicks.
                val want = when {
                    !v.gate -> 0.0
                    snd == BassSound.SUB -> 1.0
                    snd == BassSound.B808 -> exp(-t / 1.4)
                    else -> exp(-t / 0.55)
                }
                val rate = if (!v.gate) dt / relTime * 4 else 0.004 * 44100 / sr
                v.level += (want - v.level) * rate.coerceAtMost(1.0)
                val freq = if (snd == BassSound.B808) base * (1 + 1.6 * exp(-t / 0.035)) else base
                v.phase += freq * dt
                if (v.phase >= 1) v.phase -= 1
                val ph = v.phase * 2 * PI
                val s = when (snd) {
                    BassSound.SUB -> sin(ph) + 0.22 * sin(2 * ph)
                    BassSound.B808 -> tanh(1.9 * sin(ph)) * 0.9
                    BassSound.PLUCK -> {
                        // A saw through a low-pass that closes as the note dies away.
                        val saw = 2 * v.phase - 1
                        val cutoff = 180 + 2600 * exp(-t / 0.16)
                        val a = (2 * PI * cutoff * dt).coerceAtMost(1.0)
                        v.lp += (saw - v.lp) * a
                        v.lp * 1.3
                    }
                }
                val x = (s * v.level * g).toFloat()
                out[i * 2] += x; out[i * 2 + 1] += x
                v.t += dt
                if (!v.gate) v.rel += dt
            }
            if (!v.gate && (v.level < 0.0008 || v.rel > 1.0)) v.note = -1
            // A decaying note that has died away while held frees its voice too.
            if (v.gate && snd != BassSound.SUB && v.t > 8.0) { v.gate = false }
        }
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
