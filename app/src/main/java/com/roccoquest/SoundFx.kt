package com.roccoquest

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Retro sound effects synthesized at startup (no audio assets needed), written to
 * small WAV files in the cache directory and played through a [SoundPool].
 */
class SoundFx(context: Context) : SoundSink {
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = IntArray(Sound.entries.size)

    init {
        val dir = context.cacheDir
        thread(name = "sfx-synth") {
            for (s in Sound.entries) {
                try {
                    val file = File(dir, "sfx_${s.name.lowercase()}.wav")
                    writeWav(file, synth(s))
                    ids[s.ordinal] = pool.load(file.path, 1)
                } catch (_: Exception) {
                    // Sound is optional; the game plays fine without it.
                }
            }
        }
    }

    override fun play(s: Sound) {
        val id = ids[s.ordinal]
        if (id != 0) pool.play(id, 0.55f, 0.55f, 1, 0, 1f)
    }

    fun release() = pool.release()

    // ------------------------------------------------------------------ synthesis

    private class Builder {
        val samples = ArrayList<Float>()

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
                    4f * kotlin.math.abs(phase - 0.5f) - 1f
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

        fun noise(dur: Float, vol: Float = 0.4f, smooth: Float = 0f) {
            val n = (RATE * dur).toInt()
            var last = 0f
            for (i in 0 until n) {
                val raw = Random.nextFloat() * 2f - 1f
                last = last * smooth + raw * (1f - smooth)
                samples += last * vol * (1f - i.toFloat() / n)
            }
        }
    }

    private fun synth(s: Sound): List<Float> = Builder().apply {
        when (s) {
            Sound.JUMP -> tone(280f, 640f, 0.14f)
            Sound.COIN -> { tone(988f, 988f, 0.06f, decay = false); tone(1319f, 1319f, 0.3f) }
            Sound.FIRE -> tone(900f, 250f, 0.08f, 0.3f)
            Sound.STOMP -> tone(500f, 120f, 0.12f, 0.4f)
            Sound.KICK -> tone(700f, 350f, 0.07f, 0.35f)
            Sound.POWERUP -> notes(floatArrayOf(523f, 659f, 784f, 1047f, 784f, 1047f, 1319f), 0.06f)
            Sound.HURT -> tone(700f, 200f, 0.35f, 0.35f)
            Sound.BUMP -> tone(160f, 90f, 0.08f, 0.45f)
            Sound.BREAK -> noise(0.18f, 0.5f, 0.3f)
            Sound.BOSS_HIT -> { tone(220f, 70f, 0.2f, 0.45f); noise(0.08f, 0.3f) }
            Sound.BOSS_FIRE -> noise(0.45f, 0.45f, 0.85f)
            Sound.DIE -> notes(floatArrayOf(784f, 740f, 698f, 0f, 523f, 494f, 440f, 392f), 0.12f, lastHold = 0.4f)
            Sound.CLEAR -> notes(floatArrayOf(523f, 659f, 784f, 1047f, 1319f, 1568f, 2093f), 0.08f, lastHold = 0.5f)
            Sound.VICTORY -> notes(
                floatArrayOf(784f, 784f, 784f, 1047f, 0f, 988f, 1047f, 1175f, 1319f, 1568f), 0.14f, 0.28f, lastHold = 0.9f,
            )
            Sound.ONEUP -> notes(floatArrayOf(659f, 784f, 1319f, 1047f, 1175f, 1568f), 0.08f)
        }
    }.samples

    private fun writeWav(file: File, samples: List<Float>) {
        val dataLen = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataLen)
        for (v in samples) buf.putShort((v.coerceIn(-1f, 1f) * 32000f).toInt().toShort())
        FileOutputStream(file).use { it.write(buf.array()) }
    }

    private companion object {
        const val RATE = 22050
    }
}
