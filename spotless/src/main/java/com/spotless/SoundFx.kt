package com.spotless

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.spotless.game.Sound
import com.spotless.game.SoundSink
import com.spotless.game.Synth
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Sound effects synthesized at startup (no audio assets needed), written to small WAV
 * files in the cache directory and played through a [SoundPool].
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
                    writeWav(file, Synth.render(s))
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

    private fun writeWav(file: File, pcm: ShortArray) {
        val data = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(Synth.RATE).putInt(Synth.RATE * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(data)
        for (v in pcm) buf.putShort(v)
        FileOutputStream(file).use { it.write(buf.array()) }
    }
}
