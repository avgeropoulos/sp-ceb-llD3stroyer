package com.bouzouki.hero

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.bouzoukihero.core.Synth
import kotlin.concurrent.thread

/** Where the music is up to, in song seconds, plus its transport controls. */
interface SongClock {
    fun now(): Double
    fun pause()
    fun resume()
    fun restart()
    fun release()

    /** True once the music has played to the end. */
    val ended: Boolean
}

/**
 * Plays a built-in song. The bouzouki and the backing band are mixed live, so the
 * bouzouki can drop out while you're missing notes. The clock is the audio
 * hardware's own play position, so the notes stay locked to what you hear.
 */
class SynthSong(private val song: Synth.Rendered, private val leadOn: () -> Boolean) : SongClock {
    private val rate = Synth.RATE
    private val track: AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(
                maxOf(
                    AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                    rate / 8 * 2,
                )
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    } catch (_: Exception) {
        null // No audio output: the game still runs on the wall clock below.
    }

    @Volatile private var released = false
    @Volatile private var paused = false
    @Volatile private var restartWanted = false
    @Volatile private var pos = 0
    @Volatile private var headBase = 0L
    private var gain = 1f

    // Fallback clock when there's no audio device.
    private var wallStart = System.nanoTime()
    private var wallPausedAt = 0L

    init {
        track?.play()
        if (track != null) thread(name = "song-audio", priority = Thread.MAX_PRIORITY) { pump(track) }
    }

    private fun pump(t: AudioTrack) {
        val chunk = ShortArray(512)
        try {
            while (!released) {
                if (restartWanted) {
                    t.pause()
                    t.flush()
                    pos = 0
                    headBase = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    restartWanted = false
                    if (!paused) t.play()
                }
                if (paused || pos >= song.lead.size) {
                    Thread.sleep(10)
                    continue
                }
                val n = minOf(chunk.size, song.lead.size - pos)
                val target = if (leadOn()) 1f else 0.08f
                var g = gain
                for (i in 0 until n) {
                    g += (target - g) * 0.004f
                    val v = song.backing[pos + i] + song.lead[pos + i] * g
                    chunk[i] = (v.coerceIn(-1f, 1f) * 32000f).toInt().toShort()
                }
                val written = t.write(chunk, 0, n, AudioTrack.WRITE_NON_BLOCKING)
                if (written > 0) {
                    // Only advance the mix state by what was actually taken.
                    pos += written
                    gain = g
                } else {
                    Thread.sleep(4)
                }
            }
        } catch (_: Exception) {
        } finally {
            t.release()
        }
    }

    override fun now(): Double {
        val t = track ?: return wallNow()
        if (restartWanted) return 0.0
        var head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (head < headBase) {
            // Some devices reset the head to 0 on flush.
            headBase = 0
            head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }
        return (head - headBase).toDouble() / rate
    }

    private fun wallNow(): Double {
        val ref = if (paused) wallPausedAt else System.nanoTime()
        return (ref - wallStart) / 1e9
    }

    override val ended: Boolean
        get() = now() >= song.seconds - 0.05

    override fun pause() {
        if (paused) return
        paused = true
        wallPausedAt = System.nanoTime()
        try { track?.pause() } catch (_: Exception) {}
    }

    override fun resume() {
        if (!paused) return
        wallStart += System.nanoTime() - wallPausedAt
        paused = false
        try { track?.play() } catch (_: Exception) {}
    }

    override fun restart() {
        wallStart = System.nanoTime()
        wallPausedAt = wallStart
        paused = false
        restartWanted = true
    }

    override fun release() {
        released = true
        if (track != null) try { track.pause() } catch (_: Exception) {}
    }
}
