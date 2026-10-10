package com.critters

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import com.critters.game.Audio
import com.critters.game.Dex
import com.critters.game.Sfx
import com.critters.game.Synth
import com.critters.game.Tune
import com.critters.game.Tunes
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Sound effects and critter cries, synthesized at startup (there are no audio files),
 * played through a [SoundPool]; music streams through [MusicPlayer].
 */
class SoundFx(context: Context) : Audio {
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val sfxIds = IntArray(Sfx.entries.size)
    private val cryIds = IntArray(Dex.all.size + 1)
    private val music = MusicPlayer()

    init {
        val dir = context.cacheDir
        thread(name = "sfx-synth") {
            try {
                for (s in Sfx.entries) {
                    val f = File(dir, "sfx_${s.name.lowercase()}.wav")
                    writeWav(f, Synth.sfx(s))
                    sfxIds[s.ordinal] = pool.load(f.path, 1)
                }
                for (sp in Dex.all) {
                    val f = File(dir, "cry_${sp.id}.wav")
                    writeWav(f, Synth.cry(sp.id))
                    cryIds[sp.id] = pool.load(f.path, 1)
                }
            } catch (_: Exception) {
                // Sound is optional; the game plays fine without it.
            }
        }
    }

    override fun sfx(s: Sfx) {
        val id = sfxIds[s.ordinal]
        if (id != 0) pool.play(id, 0.6f, 0.6f, 1, 0, 1f)
    }

    override fun cry(species: Int) {
        val id = cryIds.getOrElse(species) { 0 }
        if (id != 0) pool.play(id, 0.7f, 0.7f, 1, 0, 1f)
    }

    override fun music(t: Tune) = music.play(t)

    fun setForeground(on: Boolean) = music.setForeground(on)

    fun release() {
        pool.release()
        music.release()
    }

    private fun writeWav(file: File, samples: FloatArray) {
        val dataLen = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(Synth.RATE).putInt(Synth.RATE * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataLen)
        for (v in samples) buf.putShort((v.coerceIn(-1f, 1f) * 32000f).toInt().toShort())
        FileOutputStream(file).use { it.write(buf.array()) }
    }
}

/** Streams the looping background music, rendering each song the first time it's needed. */
class MusicPlayer {
    @Volatile private var wanted = Tune.NONE
    @Volatile private var foreground = true
    @Volatile private var released = false
    private val songs = HashMap<Tune, ShortArray?>()

    init {
        thread(name = "music", priority = Thread.MAX_PRIORITY) { loop() }
    }

    fun play(t: Tune) { wanted = t }

    fun setForeground(on: Boolean) { foreground = on }

    fun release() { released = true }

    private fun song(t: Tune): ShortArray? = songs.getOrPut(t) { Tunes.render(t) }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
                        .setSampleRate(Synth.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, Synth.RATE / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return // No audio output; play silently.
        }
        track.setVolume(0.4f)
        val chunk = ShortArray(1024)
        var current = Tune.NONE
        var pos = 0
        try {
            while (!released) {
                val t = if (foreground) wanted else Tune.NONE
                if (t != current) {
                    track.pause()
                    track.flush()
                    current = t
                    pos = 0
                    if (t != Tune.NONE) track.play()
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
}
