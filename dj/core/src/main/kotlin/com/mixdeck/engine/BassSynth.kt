package com.mixdeck.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The bass keyboard's sounds. Six are a plucked bass string played different ways; 808 and SUB
 * are the deep electronic ones. [string] says which are strings.
 */
enum class BassSound(val label: String, val string: Boolean) {
    FINGER("FINGER", true), PICK("PICK", true), SLAP("SLAP", true), MUTED("MUTED", true),
    UPRIGHT("UPRIGHT", true), FRETLESS("FRETLESS", true), B808("808", false), SUB("SUB", false);

    /** One line for the sound picker. */
    val about: String get() = when (this) {
        FINGER -> "Jazz bass played with the fingers: warm and deep"
        PICK -> "Played with a pick: brighter, more attack"
        SLAP -> "Slap and pop: funky, punchy"
        MUTED -> "Palm-muted: short and thumpy"
        UPRIGHT -> "Upright (double) bass: woody, soft attack"
        FRETLESS -> "Fretless: smooth, singing"
        B808 -> "808: deep hip-hop boom"
        SUB -> "Sub: a pure deep tone that holds"
    }
}

/**
 * A small bass you play over the mix (the BASS keys on the console, a computer's keys or a MIDI
 * keyboard). The string sounds are a model of a real string: a pluck goes round and round a delay
 * one wave long, losing a little treble each time, the way a string rings and mellows. Under it
 * sits a pure tone at the note's own pitch for weight, and a little warm drive adds the overtones
 * that let small speakers (phones, laptops) carry the bass too. A few notes can sound at once.
 * Call everything under the mixer lock; [render] adds to the mix, so the bass is heard and
 * recorded like the sampler.
 */
class BassSynth(private val sr: Int) {
    var sound = BassSound.FINGER
    var volume = 0.85f

    /** How each string sound is played: pluck, how long it rings, its tone, and how much deep tone is under it. */
    private class Feel(
        val bright: Double, val pickAt: Double, val pickDepth: Double, val damp: Double, val ring: Double,
        val cutoff: Double, val q: Double, val sub: Double, val drive: Double, val attack: Double,
        val pop: Double = 0.0, val body: Double = 0.0, val swell: Double = 0.0,
    )

    private fun feel(s: BassSound) = when (s) {
        BassSound.FINGER -> Feel(0.14, 0.24, 0.45, 0.24, 6.5, 950.0, 0.8, 0.85, 2.2, 0.004)
        BassSound.PICK -> Feel(0.5, 0.12, 0.55, 0.12, 6.0, 2300.0, 0.9, 0.65, 2.4, 0.001)
        BassSound.SLAP -> Feel(0.75, 0.08, 0.35, 0.18, 3.5, 3600.0, 1.1, 0.8, 2.8, 0.0005, pop = 0.9)
        BassSound.MUTED -> Feel(0.2, 0.2, 0.4, 0.5, 0.42, 650.0, 0.75, 1.0, 2.0, 0.003)
        BassSound.UPRIGHT -> Feel(0.08, 0.3, 0.3, 0.32, 2.6, 800.0, 0.7, 0.85, 1.7, 0.012, body = 0.6)
        BassSound.FRETLESS -> Feel(0.12, 0.25, 0.4, 0.1, 8.0, 1300.0, 2.2, 0.75, 2.0, 0.006, swell = 0.12)
        else -> Feel(0.1, 0.2, 0.4, 0.2, 6.0, 900.0, 0.8, 1.0, 2.0, 0.004)
    }

    private inner class Voice {
        var note = -1; var gate = false; var t = 0.0; var rel = 0.0; var born = 0L
        var snd = BassSound.FINGER
        lateinit var f: Feel
        // The string: one wave of it, going round.
        val buf = FloatArray(sr / 18 + 8)
        var len = 0.0; var pos = 0; var loopLp = 0.0
        val tone = Biquad(); val bodyLo = Biquad(); val bodyHi = Biquad()
        var dcX = 0.0; var dcY = 0.0
        // The deep tone under it (and the whole of SUB and 808).
        var phase = 0.0; var level = 0.0; var subEnv = 0.0
    }
    private val voices = Array(4) { Voice() }
    private var clock = 0L
    private var seed = 12345

    /** Any note sounding (held or still ringing out). */
    val active get() = voices.any { it.note >= 0 }
    fun isHeld(note: Int) = voices.any { it.note == note && it.gate }

    private fun freq(note: Int) = 440.0 * 2.0.pow((note - 69) / 12.0)
    private fun noise(): Double { seed = seed * 1103515245 + 12345; return ((seed ushr 9) and 0xFFFF) / 32768.0 - 1.0 }

    /** Starts MIDI note [note] (36 = C2). Playing a note that is still ringing plucks it again. */
    fun noteOn(note: Int) {
        val v = voices.firstOrNull { it.note == note } ?: voices.firstOrNull { it.note < 0 }
            ?: voices.filter { !it.gate }.minByOrNull { it.born } ?: voices.minByOrNull { it.born }!!
        if (v.note < 0) { v.phase = 0.0; v.level = 0.0; v.subEnv = 0.0; v.tone.reset(); v.bodyLo.reset(); v.bodyHi.reset(); v.dcX = 0.0; v.dcY = 0.0 }
        v.note = note; v.gate = true; v.t = 0.0; v.rel = 0.0; v.born = ++clock; v.snd = sound
        v.f = feel(sound)
        if (sound.string) {
            pluck(v)
            v.tone.lowPass(sr, v.f.cutoff, v.f.q)
            v.bodyLo.peaking(sr, 105.0, 1.4, 6.0 * v.f.body)
            v.bodyHi.peaking(sr, 240.0, 2.0, 4.0 * v.f.body)
        }
    }

    /**
     * Sets the string going: its shape right after the finger lets go. Mostly a smooth bow shape
     * (that is what carries the deep fundamental) with some noise for the finger's grit, plucked
     * part-way along the string (which thins some overtones out, as on a real bass).
     */
    private fun pluck(v: Voice) {
        // The loop's own filter delays the wave by about half a sample: take it off the length.
        v.len = (sr / freq(v.note) - 0.5).coerceIn(8.0, (v.buf.size - 4).toDouble())
        val n = v.len.toInt() + 2
        val f = v.f
        var lp = 0.0; var lp2 = 0.0
        val raw = DoubleArray(n)
        for (i in 0 until n) {
            lp += (noise() - lp) * f.bright; lp2 += (lp - lp2) * f.bright
            // The string pulled aside at the plucking point: a triangle with its corner there.
            val x = i.toDouble() / n
            val shape = if (x < f.pickAt) x / f.pickAt else (1 - x) / (1 - f.pickAt)
            raw[i] = (shape - 0.5) * 1.6 + lp2 * 2.5
        }
        val at = (n * f.pickAt).toInt().coerceAtLeast(1)
        var mean = 0.0
        val shaped = DoubleArray(n) { raw[it] - f.pickDepth * raw[(it + n - at) % n] }
        for (x in shaped) mean += x
        mean /= n
        var peak = 1e-6
        for (i in 0 until n) { shaped[i] -= mean; if (abs(shaped[i]) > peak) peak = abs(shaped[i]) }
        for (i in 0 until n) v.buf[i] = (shaped[i] / peak).toFloat()
        v.pos = 0; v.loopLp = 0.0
    }

    /** Lets go of [note]: the string is damped (the tone fades out quickly). */
    fun noteOff(note: Int) { for (v in voices) if (v.note == note) v.gate = false }
    fun allOff() { for (v in voices) v.gate = false }

    fun render(out: FloatArray, frames: Int, master: Float) {
        val dt = 1.0 / sr
        val g = 0.95 * volume * master
        for (v in voices) {
            if (v.note < 0) continue
            when {
                v.snd.string -> renderString(v, out, frames, g, dt)
                v.snd == BassSound.B808 -> render808(v, out, frames, g, dt)
                else -> renderSub(v, out, frames, g, dt)
            }
        }
    }

    private fun renderString(v: Voice, out: FloatArray, frames: Int, g: Double, dt: Double) {
        val f = v.f
        val hz = freq(v.note)
        val ring = if (!v.gate) 0.09 else f.ring
        val keep = exp(-6.9 / (hz * ring))                    // loudness kept each time round
        val damp = if (!v.gate) 0.75 else f.damp
        val n = v.len.toInt(); val frac = v.len - n
        val size = n + 1
        // The deep tone fades with the string (fast when let go).
        val subFall = exp(-6.9 * dt / (if (!v.gate) 0.08 else f.ring * 0.8))
        val subRise = 1 - exp(-dt / maxOf(f.attack, 0.002))
        var peak = 0.0
        for (i in 0 until frames) {
            val r0 = v.pos; val r1 = if (r0 + 1 >= size) 0 else r0 + 1
            val y = v.buf[r0] * frac + v.buf[r1] * (1 - frac)
            v.loopLp += (y - v.loopLp) * (1 - damp)
            v.buf[r0] = (v.loopLp * keep).toFloat()
            v.pos = r1
            // A fretless "mwah": the tone opens up just after the note starts.
            if (f.swell > 0 && i % 32 == 0) v.tone.lowPass(sr, f.cutoff * (0.55 + 0.45 * (1 - exp(-v.t / f.swell))), f.q)
            var s = v.tone.process(y, 0)
            if (f.body > 0) s = v.bodyHi.process(v.bodyLo.process(s, 0), 0)
            // Slap: the thumb's snap on top of the first few milliseconds.
            if (f.pop > 0 && v.t < 0.03) s += f.pop * noise() * exp(-v.t / 0.004) * 0.6
            // Attack, then the deep tone that follows the string down.
            val atk = if (v.t < f.attack) v.t / f.attack else 1.0
            v.subEnv = if (v.t < f.attack * 2) v.subEnv + (1 - v.subEnv) * subRise else v.subEnv * subFall
            v.phase += hz * dt; if (v.phase >= 1) v.phase -= 1
            val sub = sin(v.phase * 2 * PI) * v.subEnv * f.sub
            val x = warm(v, (s * 0.8 * atk + sub) * f.drive) * g
            if (abs(x) > peak) peak = abs(x)
            out[i * 2] += x.toFloat(); out[i * 2 + 1] += x.toFloat()
            v.t += dt
            if (!v.gate) v.rel += dt
        }
        if ((peak < 0.0006 && v.t > 0.1) || v.rel > 1.0) { v.note = -1; v.gate = false }
    }

    /**
     * Warm drive: a soft, slightly lopsided clip. The lopsided part adds the octave above the
     * note, so a 41 Hz E still comes through a phone speaker that can't play 41 Hz. Then the
     * offset that leaves is taken out again.
     */
    private fun warm(v: Voice, x: Double): Double {
        val y = tanh(x + 0.4 * x * x) / 1.45
        v.dcY = y - v.dcX + 0.9993 * v.dcY; v.dcX = y
        return v.dcY
    }

    private fun render808(v: Voice, out: FloatArray, frames: Int, g: Double, dt: Double) {
        val hz = freq(v.note)
        for (i in 0 until frames) {
            val t = v.t
            val want = if (v.gate) exp(-t / 1.8) else 0.0
            val rate = if (!v.gate) dt / 0.06 else if (t < 0.002) 0.5 else 1.0
            v.level += (want - v.level) * rate.coerceAtMost(1.0)
            v.phase += hz * (1 + 1.3 * exp(-t / 0.03)) * dt
            if (v.phase >= 1) v.phase -= 1
            val x = warm(v, sin(v.phase * 2 * PI) * v.level * 2.4) * g
            out[i * 2] += x.toFloat(); out[i * 2 + 1] += x.toFloat()
            v.t += dt
            if (!v.gate) v.rel += dt
        }
        if ((v.level < 0.0008 && v.t > 0.05) || v.rel > 1.0) v.note = -1
    }

    private fun renderSub(v: Voice, out: FloatArray, frames: Int, g: Double, dt: Double) {
        val hz = freq(v.note)
        for (i in 0 until frames) {
            val want = if (v.gate) 1.0 else 0.0
            val rate = if (!v.gate) dt / 0.035 else 0.004 * 44100 / sr
            v.level += (want - v.level) * rate.coerceAtMost(1.0)
            v.phase += hz * dt
            if (v.phase >= 1) v.phase -= 1
            val x = warm(v, sin(v.phase * 2 * PI) * v.level * 1.8) * g
            out[i * 2] += x.toFloat(); out[i * 2 + 1] += x.toFloat()
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
