package com.princessjump.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Music and sound effects, all synthesized in code (no audio files).
 *
 * The music is a little music-box waltz that loops seamlessly. Sound effects
 * are rendered to small WAV files in the cache folder and played with SoundPool.
 */
class Sound(context: Context) {

    enum class Sfx { JUMP, DOUBLE_JUMP, STAR, CRASH, LEVEL_UP }

    private companion object {
        const val RATE = 22050
        const val TEMPO = 132.0
        const val MUSIC_VOLUME = 0.55f

        // A cute original waltz in C major: (MIDI note, length in beats)
        val MELODY = listOf(
            76 to 1.0, 79 to 1.0, 84 to 1.0,
            83 to 1.5, 81 to 0.5, 79 to 1.0,
            81 to 1.0, 77 to 1.0, 81 to 1.0,
            79 to 3.0,
            76 to 1.0, 79 to 1.0, 84 to 1.0,
            86 to 1.5, 84 to 0.5, 83 to 1.0,
            81 to 1.0, 83 to 1.0, 86 to 1.0,
            84 to 3.0,
            88 to 1.0, 86 to 1.0, 84 to 1.0,
            83 to 1.0, 81 to 1.0, 79 to 1.0,
            81 to 1.0, 84 to 1.0, 81 to 1.0,
            79 to 3.0,
            77 to 1.0, 81 to 1.0, 86 to 1.0,
            84 to 1.0, 83 to 1.0, 81 to 1.0,
            79 to 1.0, 83 to 1.0, 86 to 1.0,
            84 to 2.0, 72 to 1.0,
        )

        // One chord per bar: root note (bass) and two chord tones (accompaniment)
        val C = intArrayOf(48, 64, 67)
        val G = intArrayOf(43, 62, 67)
        val F = intArrayOf(41, 65, 69)
        val AM = intArrayOf(45, 64, 69)
        val CHORDS = listOf(C, G, F, C, C, G, G, C, C, G, F, C, F, AM, G, C)

        fun freq(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("princess_jump", Context.MODE_PRIVATE)
    private val lock = Any()

    var muted = prefs.getBoolean("muted", false)
        private set

    private var music: AudioTrack? = null
    private var foreground = false
    @Volatile private var released = false

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val sfxIds = IntArray(Sfx.entries.size)

    init {
        Thread {
            loadEffects()
            val track = buildMusic()
            synchronized(lock) {
                if (released) {
                    track?.release()
                } else {
                    music = track
                    updateMusic()
                }
            }
        }.start()
    }

    // ---------- Public controls ----------
    fun onResume() = synchronized(lock) {
        foreground = true
        updateMusic()
    }

    fun onPause() = synchronized(lock) {
        foreground = false
        updateMusic()
    }

    fun toggleMute() = synchronized(lock) {
        muted = !muted
        prefs.edit().putBoolean("muted", muted).apply()
        updateMusic()
    }

    fun play(sfx: Sfx) {
        if (muted) return
        val id = sfxIds[sfx.ordinal]
        if (id != 0) soundPool.play(id, 0.8f, 0.8f, 1, 0, 1f)
    }

    fun release() = synchronized(lock) {
        released = true
        music?.let {
            try {
                it.pause()
                it.flush()
            } catch (e: IllegalStateException) {
                // Already stopped
            }
            it.release()
        }
        music = null
        soundPool.release()
    }

    private fun updateMusic() {
        val track = music ?: return
        try {
            if (foreground && !muted) {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            } else if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
        } catch (e: IllegalStateException) {
            // The audio system refused; the game simply stays quiet.
        }
    }

    // ---------- Music ----------
    private fun buildMusic(): AudioTrack? {
        val beat = 60.0 / TEMPO
        val totalBeats = MELODY.sumOf { it.second }
        val length = (totalBeats * beat * RATE).toInt()
        val mix = FloatArray(length)

        // Melody on a music box
        var t = 0.0
        for ((note, beats) in MELODY) {
            addBell(mix, (t * beat * RATE).toInt(), freq(note), 0.32f, 1.4)
            t += beats
        }
        // "Oom-pah-pah" accompaniment
        for ((bar, chord) in CHORDS.withIndex()) {
            val start = bar * 3 * beat
            addSoft(mix, (start * RATE).toInt(), freq(chord[0]), 0.28f, 0.9)
            for (b in 1..2) {
                val at = ((start + b * beat) * RATE).toInt()
                addSoft(mix, at, freq(chord[1]), 0.09f, 0.35)
                addSoft(mix, at, freq(chord[2]), 0.09f, 0.35)
            }
        }

        val pcm = toPcm(mix)
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer, RATE))
                .build()
            track.setVolume(MUSIC_VOLUME)
            // Feed the loop forever; write() blocks while the track is paused or full.
            Thread {
                var pos = 0
                while (true) {
                    val written = track.write(pcm, pos, min(2048, pcm.size - pos))
                    if (written < 0 || released) break
                    pos = (pos + written) % pcm.size
                }
            }.apply { isDaemon = true }.start()
            track
        } catch (e: Exception) {
            null
        }
    }

    /** A bright music-box/celesta tone. The tail wraps around so the loop is seamless. */
    private fun addBell(out: FloatArray, start: Int, f: Double, amp: Float, seconds: Double) {
        val n = (seconds * RATE).toInt()
        for (i in 0 until n) {
            val time = i.toDouble() / RATE
            val env = min(1.0, time / 0.004) * exp(-time * 3.2)
            val w = 2 * PI * f * time
            val v = sin(w) + 0.35 * sin(2 * w) * exp(-time * 6) + 0.12 * sin(3 * w) * exp(-time * 10)
            out[(start + i) % out.size] += (v * env * amp).toFloat()
        }
    }

    /** A soft, round tone for the bass and chords. */
    private fun addSoft(out: FloatArray, start: Int, f: Double, amp: Float, seconds: Double) {
        val n = (seconds * RATE).toInt()
        for (i in 0 until n) {
            val time = i.toDouble() / RATE
            val env = min(1.0, time / 0.01) * exp(-time * 4.0)
            val w = 2 * PI * f * time
            val v = sin(w) + 0.2 * sin(2 * w)
            out[(start + i) % out.size] += (v * env * amp).toFloat()
        }
    }

    // ---------- Sound effects ----------
    private fun loadEffects() {
        for (sfx in Sfx.entries) {
            try {
                val file = File(appContext.cacheDir, "sfx_v1_${sfx.name.lowercase()}.wav")
                if (!file.exists()) writeWav(file, toPcm(renderEffect(sfx)))
                synchronized(lock) {
                    if (!released) sfxIds[sfx.ordinal] = soundPool.load(file.path, 1)
                }
            } catch (e: Exception) {
                // Missing sound effects are not worth crashing the game over.
            }
        }
    }

    private fun renderEffect(sfx: Sfx): FloatArray = when (sfx) {
        Sfx.JUMP -> sweep(520.0, 880.0, 0.13, 0.45f)
        Sfx.DOUBLE_JUMP -> sweep(780.0, 1320.0, 0.13, 0.4f)
        Sfx.CRASH -> sweep(620.0, 140.0, 0.45, 0.5f)
        Sfx.STAR -> FloatArray((0.5 * RATE).toInt()).also {
            addBell(it, 0, freq(88), 0.45f, 0.5)
            addBell(it, (0.07 * RATE).toInt(), freq(95), 0.45f, 0.43)
        }
        Sfx.LEVEL_UP -> FloatArray((0.9 * RATE).toInt()).also {
            listOf(84, 88, 91, 96).forEachIndexed { i, note ->
                addBell(it, (i * 0.1 * RATE).toInt(), freq(note), 0.38f, 0.9 - i * 0.1)
            }
        }
    }

    private fun sweep(from: Double, to: Double, seconds: Double, amp: Float): FloatArray {
        val n = (seconds * RATE).toInt()
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val f = from + (to - from) * p
            phase += 2 * PI * f / RATE
            val env = min(1.0, i / (0.005 * RATE)) * (1 - p)
            out[i] = ((sin(phase) + 0.25 * sin(2 * phase)) * env * amp).toFloat()
        }
        return out
    }

    // ---------- Helpers ----------
    private fun toPcm(mix: FloatArray): ShortArray {
        var peak = 0.0001f
        for (v in mix) peak = maxOf(peak, kotlin.math.abs(v))
        val gain = if (peak > 0.95f) 0.95f / peak else 1f
        return ShortArray(mix.size) { (mix[it] * gain * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val dataSize = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataSize)
        for (s in pcm) buf.putShort(s)
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { it.write(buf.array()) }
        tmp.renameTo(file)
    }
}
