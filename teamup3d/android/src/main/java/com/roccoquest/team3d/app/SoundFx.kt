package com.roccoquest.team3d.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.roccoquest.game.Chiptune
import com.roccoquest.game.Music
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.SoundSynth
import com.roccoquest.game.Voice
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Retro sound effects synthesized at startup (no audio assets needed), written to
 * small WAV files in the cache directory and played through a [SoundPool].
 * Background music is handled by [MusicPlayer].
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
    private val musicPlayer = MusicPlayer()

    /** The phone's voice learns the theme song's words in the background, then sings them. */
    @Suppress("unused")
    private val singer = ThemeSinger(context) { words -> musicPlayer.singTheme(words) }

    /** Everybody's talking (Rocco, Ring-Ding Frog, villagers, Pokemon...): the phone's own voices. */
    private val speaker = Speaker(context)

    init {
        val dir = context.cacheDir
        thread(name = "sfx-synth", priority = Thread.MIN_PRIORITY) {
            for (s in Sound.entries) {
                try {
                    val file = File(dir, "sfx_${s.name.lowercase()}.wav")
                    writeWav(file, SoundSynth.render(s))
                    ids[s.ordinal] = pool.load(file.path, 1)
                } catch (_: Exception) {
                    // Sound is optional; the game plays fine without it.
                }
            }
        }
    }

    /** Starting a sound can take the phone a few milliseconds, so it happens off the game thread. */
    private val sfxThread = android.os.HandlerThread("sfx-play").apply { start() }
    private val sfxHandler = android.os.Handler(sfxThread.looper)

    override fun play(s: Sound) {
        val id = ids[s.ordinal]
        if (id != 0) sfxHandler.post { try { pool.play(id, 0.55f, 0.55f, 1, 0, 1f) } catch (_: Exception) {} }
    }

    override fun music(m: Music) = musicPlayer.play(m)

    override fun say(text: String) = sayAs(text, Voice.FROG)

    override fun sayAs(text: String, voice: Voice) = speaker.say(text, voice)

    fun setForeground(on: Boolean) = musicPlayer.setForeground(on)

    fun release() {
        speaker.release()
        sfxThread.quit()
        pool.release()
        musicPlayer.release()
    }

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
    }
}
