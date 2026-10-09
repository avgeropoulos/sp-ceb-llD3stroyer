package com.roccoquest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.roccoquest.game.Chiptune
import com.roccoquest.game.Music
import com.roccoquest.game.Songs
import kotlin.concurrent.thread

/**
 * Streams the looping chiptune background music. Songs are synthesized once (on
 * this player's own thread) and then written to an [AudioTrack] chunk by chunk.
 */
class MusicPlayer {
    @Volatile private var wanted = Music.NONE
    @Volatile private var foreground = true
    @Volatile private var released = false
    private val songs = HashMap<Music, ShortArray>()

    init {
        thread(name = "music", priority = Thread.MAX_PRIORITY) { loop() }
    }

    fun play(m: Music) { wanted = m }

    fun setForeground(on: Boolean) { foreground = on }

    fun release() { released = true }

    private fun song(m: Music): ShortArray? =
        songs.getOrPut(m) { Chiptune.render(Songs.of(m) ?: return null) }

    private fun loop() {
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
        for (m in Music.entries) song(m)

        val chunk = ShortArray(1024)
        var current = Music.NONE
        var pos = 0
        try {
            while (!released) {
                val m = if (foreground) wanted else Music.NONE
                if (m != current) {
                    track.pause()
                    track.flush()
                    current = m
                    pos = 0
                    if (m != Music.NONE) track.play()
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
