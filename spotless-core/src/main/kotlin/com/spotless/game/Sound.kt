package com.spotless.game

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class Sound { SWISH, SCRUB, POP, PICK, WRONG, CHIME, HEART, COIN, CLICK, SPARKLE, TICK }

/** Where the game sends its sound effects. */
interface SoundSink {
    fun play(s: Sound)
}

/** Synthesizes the little sound effects, so the game needs no audio files. */
object Synth {
    const val RATE = 22050

    fun render(s: Sound): ShortArray = when (s) {
        Sound.SWISH -> noise(0.12f, 0.35f, lowpass = 0.25f)
        Sound.SCRUB -> noise(0.08f, 0.3f, lowpass = 0.6f)
        Sound.POP -> sweep(500f, 1100f, 0.09f, 0.6f)
        Sound.PICK -> seq(floatArrayOf(660f, 990f), 0.06f, 0.5f)
        Sound.WRONG -> seq(floatArrayOf(220f, 185f), 0.1f, 0.4f, square = true)
        Sound.CHIME -> seq(floatArrayOf(784f, 988f, 1175f, 1568f), 0.1f, 0.5f, tail = 0.35f)
        Sound.HEART -> seq(floatArrayOf(880f, 1320f), 0.08f, 0.45f, tail = 0.2f)
        Sound.COIN -> seq(floatArrayOf(988f, 1319f), 0.05f, 0.4f, square = true)
        Sound.CLICK -> sweep(900f, 600f, 0.03f, 0.35f)
        Sound.SPARKLE -> seq(floatArrayOf(1047f, 1319f, 1568f, 2093f, 1568f, 2093f, 2637f), 0.07f, 0.35f, tail = 0.4f)
        Sound.TICK -> sweep(1500f, 1400f, 0.02f, 0.3f)
    }

    private fun env(i: Int, n: Int, attack: Int = 60): Float {
        val a = if (i < attack) i / attack.toFloat() else 1f
        return a * (1f - i / n.toFloat())
    }

    private fun noise(len: Float, vol: Float, lowpass: Float): ShortArray {
        val n = (RATE * len).toInt()
        val r = Random(7)
        var y = 0f
        return ShortArray(n) { i ->
            y += (r.nextFloat() * 2f - 1f - y) * lowpass
            (y * vol * env(i, n, 200) * 32767).toInt().toShort()
        }
    }

    private fun sweep(f0: Float, f1: Float, len: Float, vol: Float): ShortArray {
        val n = (RATE * len).toInt()
        var ph = 0.0
        return ShortArray(n) { i ->
            val f = f0 + (f1 - f0) * i / n
            ph += 2 * PI * f / RATE
            (sin(ph) * vol * env(i, n) * 32767).toInt().toShort()
        }
    }

    private fun seq(notes: FloatArray, step: Float, vol: Float, square: Boolean = false, tail: Float = 0.05f): ShortArray {
        val stepN = (RATE * step).toInt()
        val n = stepN * notes.size + (RATE * tail).toInt()
        val out = ShortArray(n)
        notes.forEachIndexed { k, f ->
            var ph = 0.0
            val start = k * stepN
            val len = n - start
            for (i in 0 until len) {
                ph += 2 * PI * f / RATE
                val w = if (square) (if (sin(ph) >= 0) 0.6 else -0.6) else sin(ph)
                val e = exp(-i / (RATE * 0.09)) * (if (i < 40) i / 40.0 else 1.0)
                val v = out[start + i] + (w * vol * e * 32767 / 1.6).toInt()
                out[start + i] = v.coerceIn(-32767, 32767).toShort()
            }
        }
        return out
    }
}
