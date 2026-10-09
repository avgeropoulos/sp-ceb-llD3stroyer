package com.roccoquest

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import java.util.Locale
import com.roccoquest.game.Chiptune
import com.roccoquest.game.Music
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.SoundSynth
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

    /** Rocco's voice: the phone's text-to-speech, with an Italian accent where available. */
    @Volatile private var ttsReady = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                val italian = t.setLanguage(Locale.ITALY)
                if (italian == TextToSpeech.LANG_MISSING_DATA || italian == TextToSpeech.LANG_NOT_SUPPORTED) {
                    t.setLanguage(Locale.US)
                }
                t.setPitch(1.25f)
                t.setSpeechRate(0.95f)
                ttsReady = true
            }
        }
    }

    init {
        val dir = context.cacheDir
        thread(name = "sfx-synth") {
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

    override fun play(s: Sound) {
        val id = ids[s.ordinal]
        if (id != 0) pool.play(id, 0.55f, 0.55f, 1, 0, 1f)
    }

    override fun music(m: Music) = musicPlayer.play(m)

    override fun say(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rocco")
    }

    fun setForeground(on: Boolean) = musicPlayer.setForeground(on)

    fun release() {
        pool.release()
        musicPlayer.release()
        tts?.shutdown()
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
