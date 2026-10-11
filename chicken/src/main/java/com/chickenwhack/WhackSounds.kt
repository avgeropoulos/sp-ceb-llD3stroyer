package com.chickenwhack

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.roccoquest.game.Chiptune
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.SoundSynth
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Bonks, bawks and jingles, synthesized at startup (no audio files), written to small
 * WAV files in the cache directory and played through a [SoundPool].
 */
class WhackSounds(context: Context) : SoundSink {
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
            for (s in USED) {
                try {
                    val file = File(dir, "whack_${s.name.lowercase()}.wav")
                    writeWav(file, SoundSynth.render(s))
                    ids[s.ordinal] = pool.load(file.path, 1)
                } catch (_: Exception) {
                    // Sound is optional; the game plays fine without it.
                }
            }
        }
    }

    override fun play(s: Sound) {
        val id = ids[s.ordinal]
        if (id != 0) pool.play(id, 0.6f, 0.6f, 1, 0, 1f)
    }

    fun release() = pool.release()

    private fun writeWav(file: File, samples: FloatArray) {
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
        const val RATE = Chiptune.RATE
        val USED = listOf(Sound.STOMP, Sound.BAWK, Sound.COIN, Sound.HURT, Sound.BUMP, Sound.POWERUP, Sound.CLEAR, Sound.VICTORY)
    }
}
