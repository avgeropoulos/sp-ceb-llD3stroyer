package com.goalrush.game

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthesizes all sound effects as mono samples, so the game needs no audio files. */
object Synth {
    const val RATE = 22050

    private class Buf(seconds: Float) {
        val s = FloatArray((RATE * seconds).toInt())
        val rnd = Random(11)

        fun tone(at: Float, dur: Float, f0: Float, f1: Float, vol: Float, decay: Float = 6f, square: Boolean = false) {
            val start = (at * RATE).toInt()
            val n = (dur * RATE).toInt()
            var ph = 0.0
            for (i in 0 until n) {
                val j = start + i
                if (j >= s.size) break
                val t = i.toFloat() / RATE
                val f = f0 + (f1 - f0) * (i.toFloat() / n)
                ph += 2 * PI * f / RATE
                val w = if (square) (if (sin(ph) > 0) 1f else -1f) * 0.5f else sin(ph).toFloat()
                val env = exp(-t * decay) * minOf(1f, i / (RATE * 0.004f))
                s[j] += w * vol * env
            }
        }

        /** Filtered noise; [smooth] near 1 gives a dull roar, near 0 a hiss. */
        fun noise(at: Float, dur: Float, vol: Float, smooth: Float, attack: Float = 0.01f, release: Float = 0.3f) {
            val start = (at * RATE).toInt()
            val n = (dur * RATE).toInt()
            var last = 0f
            for (i in 0 until n) {
                val j = start + i
                if (j >= s.size) break
                last = last * smooth + (rnd.nextFloat() * 2f - 1f) * (1f - smooth)
                val t = i.toFloat() / RATE
                val env = minOf(1f, t / attack) * minOf(1f, (dur - t) / release)
                s[j] += last * vol * env / (1f - smooth * 0.9f)
            }
        }
    }

    fun render(sound: Sound): FloatArray = when (sound) {
        Sound.KICK -> Buf(0.25f).apply {
            tone(0f, 0.18f, 160f, 50f, 0.9f, decay = 22f)
            noise(0f, 0.05f, 0.5f, 0.3f, release = 0.04f)
        }
        Sound.BOUNCE -> Buf(0.15f).apply { tone(0f, 0.12f, 120f, 60f, 0.5f, decay = 30f) }
        Sound.GOAL -> Buf(2.6f).apply {
            noise(0f, 2.6f, 0.45f, 0.82f, attack = 0.25f, release = 1.2f)
            noise(0f, 2.4f, 0.15f, 0.2f, attack = 0.3f, release = 1.0f)
            // A horn fanfare.
            val notes = floatArrayOf(523f, 659f, 784f, 1047f)
            notes.forEachIndexed { i, f -> tone(0.1f + i * 0.12f, 0.35f, f, f, 0.18f, decay = 3f, square = true) }
        }
        Sound.SAVE -> Buf(0.4f).apply {
            tone(0f, 0.2f, 110f, 70f, 0.8f, decay = 18f)
            noise(0f, 0.12f, 0.6f, 0.5f, release = 0.1f)
        }
        Sound.POST -> Buf(1.0f).apply {
            tone(0f, 1.0f, 880f, 870f, 0.35f, decay = 5f)
            tone(0f, 1.0f, 1320f, 1310f, 0.2f, decay = 7f)
            tone(0f, 0.1f, 200f, 90f, 0.5f, decay = 25f)
        }
        Sound.MISS -> Buf(1.4f).apply {
            // The crowd groans: a falling, dull roar.
            noise(0f, 1.4f, 0.35f, 0.9f, attack = 0.15f, release = 0.8f)
            tone(0f, 1.2f, 260f, 160f, 0.08f, decay = 1.5f)
        }
        Sound.WHISTLE -> Buf(0.45f).apply {
            tone(0f, 0.4f, 2900f, 2850f, 0.25f, decay = 2f)
            // Warble from the pea in the whistle.
            for (j in s.indices) s[j] *= 0.75f + 0.25f * sin(j * 2 * PI.toFloat() * 30f / RATE)
        }
        Sound.CLICK -> Buf(0.05f).apply { tone(0f, 0.04f, 1200f, 900f, 0.3f, decay = 60f) }
        Sound.WIN -> Buf(1.6f).apply {
            val notes = floatArrayOf(523f, 659f, 784f, 1047f, 784f, 1047f)
            val times = floatArrayOf(0f, 0.12f, 0.24f, 0.36f, 0.6f, 0.72f)
            notes.forEachIndexed { i, f -> tone(times[i], if (i == notes.lastIndex) 0.8f else 0.2f, f, f, 0.2f, decay = 2.5f, square = true) }
            noise(0f, 1.6f, 0.2f, 0.85f, attack = 0.3f, release = 0.8f)
        }
        Sound.FAIL -> Buf(1.2f).apply {
            val notes = floatArrayOf(392f, 370f, 349f, 330f)
            notes.forEachIndexed { i, f -> tone(i * 0.22f, if (i == 3) 0.6f else 0.22f, f, f * (if (i == 3) 0.9f else 1f), 0.2f, decay = 2f, square = true) }
        }
    }.s.also { normalize(it) }

    /** A loopable stadium crowd murmur. */
    fun crowdLoop(): FloatArray {
        val b = Buf(4f)
        b.noise(0f, 4f, 0.2f, 0.93f, attack = 0.001f, release = 0.001f)
        b.noise(0f, 4f, 0.05f, 0.6f, attack = 0.001f, release = 0.001f)
        // Slow swells.
        for (j in b.s.indices) b.s[j] *= 0.8f + 0.2f * sin(j * 2 * PI.toFloat() * 0.5f / RATE)
        // Cross-fade the ends so it loops without a click.
        val fade = RATE / 5
        for (i in 0 until fade) {
            val t = i.toFloat() / fade
            val end = b.s.size - fade + i
            b.s[i] = b.s[i] * t + b.s[end] * (1f - t)
        }
        return b.s.copyOf(b.s.size - fade).also { normalize(it, 0.35f) }
    }

    private fun normalize(s: FloatArray, peak: Float = 0.9f) {
        var m = 0f
        for (v in s) if (kotlin.math.abs(v) > m) m = kotlin.math.abs(v)
        if (m > 0f) for (i in s.indices) s[i] = s[i] / m * peak
    }
}
