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

        /** A plucked string, like a lyre: a bright attack that rings away. Each note overlaps the last. */
        fun pluck(freqs: FloatArray, each: Float, ring: Float = 0.6f, vol: Float = 0.35f) {
            val start = samples.size
            val total = (RATE * (each * (freqs.size - 1) + ring)).toInt()
            repeat(total) { samples += 0f }
            freqs.forEachIndexed { k, f ->
                if (f <= 0f) return@forEachIndexed
                val off = start + (RATE * each * k).toInt()
                val n = (RATE * ring).toInt()
                for (i in 0 until n) {
                    if (off + i >= samples.size) break
                    val t = i.toFloat() / RATE
                    val env = kotlin.math.exp(-t * 6f)
                    val w = 2f * PI.toFloat() * f * t
                    val v = sin(w) + 0.35f * sin(2f * w) + 0.15f * sin(3f * w)
                    samples[off + i] = samples[off + i] + v * vol * env * 0.6f
                }
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
            Sound.WHOOSH -> whoosh(0.5f, 0.42f)
            Sound.THUD -> { tone(150f, 55f, 0.14f, 0.55f, square = false); noise(0.08f, 0.3f, 0.6f) }
            Sound.HIT -> pluck(floatArrayOf(587f, 880f), 0.09f)
            Sound.BULLSEYE -> pluck(floatArrayOf(587f, 740f, 880f, 1175f), 0.08f, ring = 0.9f)
            Sound.CHEER -> cheer(1.8f, 0.45f)
            Sound.TICK -> tone(1250f, 1250f, 0.03f, 0.25f)
            Sound.CLEAR -> pluck(floatArrayOf(440f, 554f, 659f, 880f, 0f, 659f, 880f, 1109f), 0.13f, ring = 1.2f)
            Sound.FAIL -> pluck(floatArrayOf(440f, 415f, 392f, 330f), 0.18f, ring = 0.8f)
            Sound.GAME_OVER -> pluck(floatArrayOf(392f, 370f, 349f, 330f, 0f, 262f, 247f, 196f), 0.18f, ring = 1.2f)
        }
    }.samples.toFloatArray()
}
