package com.javelinstrike.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Synthesizes the sound effects as mono float samples at [RATE], so the app needs no audio files. */
object SoundSynth {
    const val RATE = 22050

    private class Builder {
        val samples = ArrayList<Float>()
        val rnd = Random(3)

        fun tone(f0: Float, f1: Float, dur: Float, vol: Float = 0.35f, square: Boolean = true, decay: Boolean = true) {
            val n = (RATE * dur).toInt()
            var phase = 0f
            for (i in 0 until n) {
                val t = i.toFloat() / n
                val f = f0 + (f1 - f0) * t
                phase = (phase + f / RATE) % 1f
                val v = if (f <= 0f) 0f else if (square) {
                    if (phase < 0.5f) 1f else -1f
                } else {
                    4f * abs(phase - 0.5f) - 1f
                }
                val env = if (decay) 1f - t else if (t > 0.9f) (1f - t) * 10f else 1f
                samples += v * vol * env
            }
        }

        fun notes(freqs: FloatArray, each: Float, vol: Float = 0.3f, lastHold: Float = 0f) {
            freqs.forEachIndexed { i, f ->
                val d = if (i == freqs.lastIndex && lastHold > 0f) lastHold else each
                tone(f, f, d, vol, decay = i == freqs.lastIndex)
            }
        }

        /** Filtered noise. [swell] fades in and out instead of just fading out. */
        fun noise(dur: Float, vol: Float = 0.4f, smooth: Float = 0f, swell: Boolean = false) {
            val n = (RATE * dur).toInt()
            var last = 0f
            for (i in 0 until n) {
                val t = i.toFloat() / n
                last = last * smooth + (rnd.nextFloat() * 2f - 1f) * (1f - smooth)
                val env = if (swell) sin(PI.toFloat() * t) else 1f - t
                samples += last * vol * env
            }
        }

        /** A whoosh: noise whose brightness rises then falls. */
        fun whoosh(dur: Float, vol: Float) {
            val n = (RATE * dur).toInt()
            var last = 0f
            for (i in 0 until n) {
                val t = i.toFloat() / n
                val bright = 0.97f - 0.25f * sin(PI.toFloat() * t)
                last = last * bright + (rnd.nextFloat() * 2f - 1f) * (1f - bright)
                samples += last * vol * 3f * sin(PI.toFloat() * t)
            }
        }

        /** Lots of voices at once: a crowd cheering. */
        fun cheer(dur: Float, vol: Float) {
            val n = (RATE * dur).toInt()
            var last = 0f
            for (i in 0 until n) {
                val t = i.toFloat() / n
                last = last * 0.75f + (rnd.nextFloat() * 2f - 1f) * 0.25f
                val wobble = 0.75f + 0.25f * sin(i * 0.0021f) * sin(i * 0.0007f)
                val env = if (t < 0.15f) t / 0.15f else 1f - (t - 0.15f) / 0.85f
                samples += last * vol * env * wobble
            }
        }
    }

    fun render(s: Sound): FloatArray = Builder().apply {
        when (s) {
            Sound.CLICK -> tone(900f, 900f, 0.03f, 0.25f)
            Sound.GRIP -> tone(300f, 450f, 0.06f, 0.2f, square = false)
            Sound.DRAWN -> tone(600f, 1000f, 0.09f, 0.25f)
            Sound.WHOOSH -> whoosh(0.5f, 0.6f)
            Sound.THUD -> { tone(150f, 55f, 0.14f, 0.55f, square = false); noise(0.08f, 0.3f, 0.6f) }
            Sound.HIT -> notes(floatArrayOf(880f, 1320f), 0.07f, lastHold = 0.25f)
            Sound.BULLSEYE -> notes(floatArrayOf(784f, 988f, 1175f, 1568f), 0.07f, lastHold = 0.4f)
            Sound.CHEER -> cheer(1.8f, 0.45f)
            Sound.TICK -> tone(1250f, 1250f, 0.03f, 0.25f)
            Sound.CLEAR -> notes(floatArrayOf(523f, 659f, 784f, 1047f, 0f, 784f, 1047f, 1319f), 0.1f, lastHold = 0.6f)
            Sound.FAIL -> notes(floatArrayOf(440f, 415f, 392f, 330f), 0.16f, lastHold = 0.4f)
            Sound.GAME_OVER -> notes(floatArrayOf(392f, 370f, 349f, 330f, 0f, 262f, 247f, 196f), 0.15f, lastHold = 0.7f)
        }
    }.samples.toFloatArray()
}
