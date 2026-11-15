package com.bouzouki.hero

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.bouzouki.core.PlaybackClock
import com.bouzoukihero.core.Synth
import kotlin.concurrent.thread
import kotlin.math.abs

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
 * hardware's own play position, less the time the sound then takes to come out (where the
 * phone can say), so the notes stay locked to what you hear.
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

    /**
     * The audio's play position moves in buffer-sized steps on many phones, which made the notes
     * stutter. Μπουζούκι's [PlaybackClock] runs smoothly between those steps.
     */
    private val smooth = PlaybackClock()

    // --- when the sound is heard ---
    //
    // The play position counts samples handed to the phone's mixer. They are heard a little
    // later (a few hundredths of a second from the speaker, far more over Bluetooth), so a clock
    // read off it alone shows the notes that much early. Where the phone says when a sample
    // actually comes out (AudioTrack.getTimestamp), the audio thread measures that delay now and
    // then and it is taken off the clock. Where the phone doesn't say, or says something that
    // can't be right, the delay stays 0 and the clock is the play position, as it always was.

    /** Seconds between a sample reaching the mixer and being heard; 0 until the phone has said. */
    @Volatile private var outputDelay = 0.0
    /** When the sound last started or started again: timestamps need a moment to settle after it. */
    @Volatile private var steadySince = System.nanoTime()

    // The rest is the audio thread's own.
    private val stamp = AudioTimestamp()
    private var stampSeen = false
    private var stampFrames = 0L
    private var stampNanos = 0L
    private var polledNanos = 0L
    private var easedNanos = 0L
    private val delays = DoubleArray(DELAY_SAMPLES)
    private val sorted = DoubleArray(DELAY_SAMPLES)
    private var delayCount = 0
    private var delayGoal = 0.0

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
                    steadySince = System.nanoTime()
                    if (!paused) t.play()
                }
                if (paused || pos >= song.lead.size) {
                    Thread.sleep(10)
                    continue
                }
                followOutputDelay(t)
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

    /**
     * Keeps [outputDelay] up to date; called by the audio thread while it is feeding the track.
     * Nothing here may disturb the sound: anything odd and the delay is simply left as it is.
     */
    private fun followOutputDelay(t: AudioTrack) {
        val now = System.nanoTime()
        // Ease toward the measured delay rather than jump to it, so the notes never jerk.
        val step = ((now - easedNanos) / 1e9).coerceIn(0.0, 0.05) * DELAY_EASE
        easedNanos = now
        val d = outputDelay
        if (d != delayGoal) outputDelay = if (abs(delayGoal - d) <= step) delayGoal else if (delayGoal > d) d + step else d - step

        val every = if (delayCount < DELAY_SAMPLES) POLL_FIRST_NANOS else POLL_NANOS
        if (now - polledNanos < every || now - steadySince < SETTLE_NANOS) return
        polledNanos = now
        try {
            if (!t.getTimestamp(stamp)) return
            val frames = stamp.framePosition
            val at = stamp.nanoTime
            // Trust a timestamp only if the one before it agrees: both taken since the sound
            // last (re)started, and the frames between them played at the song's own rate
            // (they don't across a pause, a restart or a gap in the sound).
            val steady = stampSeen && stampNanos >= steadySince && at > stampNanos &&
                abs((frames - stampFrames) / ((at - stampNanos) / 1e9) / rate - 1.0) < 0.1
            stampSeen = true
            stampFrames = frames
            stampNanos = at
            val age = System.nanoTime() - at
            if (!steady || age < 0 || age > MAX_STAMP_AGE_NANOS) return
            // Heard by now: the frame of the timestamp, plus what has played since it was taken.
            val heard = ((frames and 0xFFFFFFFFL) - headBase) / rate.toDouble() + age / 1e9
            val delay = mixerNow() - heard
            if (delay.isNaN() || delay < 0.0 || delay > MAX_OUTPUT_DELAY) return
            // The middle one of the last few, so one odd reading changes nothing.
            delays[delayCount % DELAY_SAMPLES] = delay
            delayCount++
            val n = minOf(delayCount, DELAY_SAMPLES)
            System.arraycopy(delays, 0, sorted, 0, n)
            java.util.Arrays.sort(sorted, 0, n)
            delayGoal = sorted[n / 2]
        } catch (_: Exception) {
        }
    }

    /** The song as it is heard: where the mixer is up to, less the time the sound takes to come out. */
    override fun now(): Double = mixerNow() - outputDelay

    /** Where the phone's mixer is up to in the song (the sound itself comes out a little later). */
    private fun mixerNow(): Double {
        val t = track ?: return wallNow()
        if (restartWanted) return 0.0
        var head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (head < headBase) {
            // Some devices reset the head to 0 on flush.
            headBase = 0
            head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }
        val nanos = android.os.SystemClock.elapsedRealtimeNanos()
        return synchronized(smooth) {
            smooth.report((head - headBase).toDouble() / rate, !paused, 1.0, nanos)
            smooth.now(nanos)
        }
    }

    private fun wallNow(): Double {
        val ref = if (paused) wallPausedAt else System.nanoTime()
        return (ref - wallStart) / 1e9
    }

    /** By the mixer's position, which runs to the very end; what's heard trails it and would stop short. */
    override val ended: Boolean
        get() = mixerNow() >= song.seconds - 0.05

    override fun pause() {
        if (paused) return
        paused = true
        wallPausedAt = System.nanoTime()
        try { track?.pause() } catch (_: Exception) {}
    }

    override fun resume() {
        if (!paused) return
        wallStart += System.nanoTime() - wallPausedAt
        steadySince = System.nanoTime()
        paused = false
        try { track?.play() } catch (_: Exception) {}
    }

    override fun restart() {
        wallStart = System.nanoTime()
        wallPausedAt = wallStart
        paused = false
        synchronized(smooth) { smooth.jump(0.0, android.os.SystemClock.elapsedRealtimeNanos()) }
        restartWanted = true
    }

    override fun release() {
        released = true
        if (track != null) try { track.pause() } catch (_: Exception) {}
    }

    private companion object {
        /** Readings of the output delay kept; their middle one is used. */
        const val DELAY_SAMPLES = 7
        /** The phone is asked this often until there are enough readings, then seldom (it isn't free). */
        const val POLL_FIRST_NANOS = 250_000_000L
        const val POLL_NANOS = 2_000_000_000L
        /** After the sound starts, timestamps aren't asked for until it has run this long. */
        const val SETTLE_NANOS = 300_000_000L
        /** A timestamp older than this says too little about now. */
        const val MAX_STAMP_AGE_NANOS = 500_000_000L
        /** No real output is slower than this (seconds); a bigger reading is a mistake. */
        const val MAX_OUTPUT_DELAY = 0.6
        /** Seconds of delay taken up per second: the clock runs at most this much off speed meanwhile. */
        const val DELAY_EASE = 0.2
    }
}
