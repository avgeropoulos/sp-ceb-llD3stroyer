package com.roccoquest.team3d.app

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

    /** Ring-Ding Frog's singing: the phone's text-to-speech, sped up and pitched high. */
    @Volatile private var ttsReady = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                t.setLanguage(Locale.US)
                t.setPitch(1.9f)
                t.setSpeechRate(1.15f)
                ttsReady = true
            }
        }
    }

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

    /**
     * Talking to the phone's speech engine can take a while (it's another app), so it happens on its
     * own thread and never holds up the game.
     */
    private val speech = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "speech").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    override fun sayAs(text: String, voice: Voice) {
        if (tts == null || !ttsReady) return
        try { speech.execute { try { speakNow(text, voice) } catch (_: Exception) {} } } catch (_: Exception) {}
    }

    /** Each voice gets its own pitch and speed so villagers sound clear and Poseidon sounds deep. */
    private fun speakNow(text: String, voice: Voice) {
        val t = tts ?: return
        val (pitch, rate) = when (voice) {
            Voice.FROG -> 1.9f to 1.15f
            Voice.VILLAGER -> 0.95f to 0.85f
            Voice.GOD -> 0.62f to 0.82f
            Voice.MON -> 1.5f to 1.05f
            // Rocco: an Italian guy (an Italian voice reading English sounds just like him).
            Voice.HERO -> 0.95f to 0.92f
            // Spotty: a squeaky, shouty mushroom voice.
            Voice.TOAD -> 2.0f to 1.3f
        }
        var say = text
        val greek = text.any { it in '\u0370'..'\u03FF' || it in '\u1F00'..'\u1FFF' }
        if (greek && !useGreek(t)) {
            // No Greek voice on this phone: say it in English instead.
            say = "I am Poseidon! I am the strongest guy of the sea!"
            useEnglish(t)
        } else if (!greek && voice == Voice.HERO) {
            if (!useItalian(t)) useEnglish(t)
        } else if (!greek) useEnglish(t)
        t.setPitch(pitch)
        t.setSpeechRate(rate)
        t.speak(say, TextToSpeech.QUEUE_FLUSH, null, voice.name)
    }

    private val greekLocale = Locale("el", "GR")
    private var englishVoice: android.speech.tts.Voice? = null

    private fun useEnglish(t: TextToSpeech) {
        if (t.voice?.locale?.language == "en") return
        val v = englishVoice
        if (v != null) t.setVoice(v) else t.setLanguage(Locale.US)
    }

    private var italianVoice: android.speech.tts.Voice? = null

    /**
     * Switches to an Italian voice (a man's voice if the phone has one: Google's are it-it-x-itc / itd) for
     * Rocco. Returns false if the phone has no Italian voice.
     */
    private fun useItalian(t: TextToSpeech): Boolean {
        if (t.voice?.locale?.language == "it") return true
        if (englishVoice == null && t.voice?.locale?.language == "en") englishVoice = t.voice
        if (italianVoice == null) italianVoice = try {
            t.voices?.filter { it.locale.language == "it" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ?.maxByOrNull { v ->
                    val n = v.name.lowercase()
                    val male = "male" in n && "female" !in n || "itc" in n || "itd" in n
                    v.quality + (if (male) 200 else 0) - (if (v.isNetworkConnectionRequired) 50 else 0)
                }
        } catch (_: Exception) { null }
        italianVoice?.let { t.setVoice(it); return true }
        val r = t.setLanguage(Locale.ITALY)
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** Switches to the most natural-sounding Greek voice the phone has. Returns false if there is none. */
    private fun useGreek(t: TextToSpeech): Boolean {
        if (t.voice?.locale?.language == "el") return true
        if (englishVoice == null && t.voice?.locale?.language == "en") englishVoice = t.voice
        val best = try {
            t.voices?.filter { it.locale.language == "el" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ?.maxByOrNull { it.quality - (if (it.isNetworkConnectionRequired) 50 else 0) }
        } catch (_: Exception) { null }
        if (best != null) { t.setVoice(best); return true }
        val r = t.setLanguage(greekLocale)
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun setForeground(on: Boolean) = musicPlayer.setForeground(on)

    fun release() {
        speech.shutdownNow()
        sfxThread.quit()
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
