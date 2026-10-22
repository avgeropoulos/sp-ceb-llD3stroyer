package com.herculesapollo.walkapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import com.herculesapollo.walk.WalkAudio
import com.herculesapollo.walk.WalkSongs
import com.herculesapollo.walk.WalkSound
import com.herculesapollo.walk.WalkSynth
import com.herculesapollo.walk.WalkTune
import com.roccoquest.game.Chiptune
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Sound effects synthesized at startup (no audio assets), written to small WAV
 * files and played through a [SoundPool]; looping chiptune music streamed to an
 * [AudioTrack]; and the phone's text-to-speech for the occasional "Good dogs!".
 */
class WalkSoundFx(context: Context) : WalkAudio {
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = IntArray(WalkSound.entries.size)

    @Volatile private var wanted = WalkTune.NONE
    @Volatile private var foreground = true
    @Volatile private var released = false
    private val songs = HashMap<WalkTune, ShortArray>()

    @Volatile private var ttsReady = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                t.setLanguage(Locale.US)
                t.setPitch(1.1f)
                ttsReady = true
            }
        }
        val dir = context.cacheDir
        thread(name = "sfx-synth") {
            for (s in WalkSound.entries) {
                try {
                    val file = File(dir, "walk_${s.name.lowercase()}.wav")
                    writeWav(file, WalkSynth.render(s))
                    ids[s.ordinal] = pool.load(file.path, 1)
                } catch (_: Exception) {
                    // Sound is optional; the game plays fine without it.
                }
            }
        }
        thread(name = "music", priority = Thread.MAX_PRIORITY) { musicLoop() }
    }

    override fun play(s: WalkSound) {
        val id = ids[s.ordinal]
        if (id != 0) pool.play(id, 0.6f, 0.6f, 1, 0, 1f)
    }

    override fun music(t: WalkTune) { wanted = t }

    override fun say(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "walk")
    }

    fun setForeground(on: Boolean) { foreground = on }

    fun release() {
        released = true
        pool.release()
        tts?.shutdown()
    }

    private fun song(t: WalkTune): ShortArray? =
        songs.getOrPut(t) { Chiptune.render(WalkSongs.of(t) ?: return null) }

    private fun musicLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            Chiptune.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(Chiptune.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, Chiptune.RATE / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return // No audio output available; play silently.
        }
        track.setVolume(0.45f)

        // Synthesize every song up front so switching tracks never stutters.
        for (t in WalkTune.entries) song(t)

        val chunk = ShortArray(1024)
        var current = WalkTune.NONE
        var pos = 0
        try {
            while (!released) {
                val t = if (foreground) wanted else WalkTune.NONE
                if (t != current) {
                    track.pause()
                    track.flush()
                    current = t
                    pos = 0
                    if (t != WalkTune.NONE) track.play()
                }
                val data = song(current)
                if (data == null || data.isEmpty()) {
                    Thread.sleep(30)
                    continue
                }
                for (i in chunk.indices) {
                    chunk[i] = data[pos]
                    pos++
                    if (pos >= data.size) pos = 0
                }
                track.write(chunk, 0, chunk.size)
            }
        } catch (_: Exception) {
        } finally {
            track.release()
        }
    }

    private fun writeWav(file: File, samples: FloatArray) {
        val rate = Chiptune.RATE
        val dataLen = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataLen)
        for (v in samples) buf.putShort((v.coerceIn(-1f, 1f) * 32000f).toInt().toShort())
        FileOutputStream(file).use { it.write(buf.array()) }
    }
}
