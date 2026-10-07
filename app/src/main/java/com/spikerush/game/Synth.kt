package com.spikerush.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Waveforms used by both the music voices and the sound effects. */
enum class Wave { SQUARE25, SQUARE50, TRIANGLE, NOISE }

/** One piece of a sound effect: a pitch sweep from [f0] to [f1] Hz. */
class SfxSegment(val f0: Float, val f1: Float, val seconds: Float, val wave: Wave, val volume: Float)

enum class Sfx(vararg val segments: SfxSegment) {
    JUMP(SfxSegment(260f, 640f, 0.14f, Wave.SQUARE50, 0.45f)),
    HURT(SfxSegment(720f, 110f, 0.38f, Wave.SQUARE25, 0.6f)),
    STOMP(SfxSegment(520f, 120f, 0.07f, Wave.SQUARE50, 0.6f), SfxSegment(950f, 320f, 0.12f, Wave.SQUARE50, 0.5f)),
    BOSS_HIT(SfxSegment(220f, 900f, 0.16f, Wave.SQUARE25, 0.45f), SfxSegment(900f, 160f, 0.22f, Wave.SQUARE25, 0.45f)),
    THROW(SfxSegment(1400f, 300f, 0.16f, Wave.NOISE, 0.3f)),
    PLANT(SfxSegment(170f, 55f, 0.09f, Wave.TRIANGLE, 0.8f)),
    COIN(SfxSegment(988f, 988f, 0.07f, Wave.SQUARE50, 0.4f), SfxSegment(1319f, 1319f, 0.26f, Wave.SQUARE50, 0.4f)),
    POOF(SfxSegment(3000f, 800f, 0.12f, Wave.NOISE, 0.18f)),
    POWER_UP(SfxSegment(392f, 392f, 0.05f, Wave.SQUARE50, 0.4f), SfxSegment(523f, 523f, 0.05f, Wave.SQUARE50, 0.4f),
        SfxSegment(659f, 659f, 0.05f, Wave.SQUARE50, 0.4f), SfxSegment(784f, 784f, 0.05f, Wave.SQUARE50, 0.4f),
        SfxSegment(1047f, 1047f, 0.05f, Wave.SQUARE50, 0.4f), SfxSegment(1319f, 1568f, 0.2f, Wave.SQUARE50, 0.4f)),
    POWER_DOWN(SfxSegment(800f, 600f, 0.08f, Wave.SQUARE25, 0.45f), SfxSegment(600f, 400f, 0.08f, Wave.SQUARE25, 0.45f),
        SfxSegment(400f, 200f, 0.18f, Wave.SQUARE25, 0.45f)),
    ITEM_APPEAR(SfxSegment(300f, 900f, 0.25f, Wave.TRIANGLE, 0.5f)),
    FIRE_SHOT(SfxSegment(900f, 250f, 0.09f, Wave.SQUARE25, 0.35f)),
    ICE_SHOT(SfxSegment(1800f, 2600f, 0.08f, Wave.TRIANGLE, 0.4f), SfxSegment(2600f, 1500f, 0.08f, Wave.TRIANGLE, 0.3f)),
    FREEZE(SfxSegment(2200f, 3200f, 0.06f, Wave.SQUARE50, 0.2f), SfxSegment(3200f, 1800f, 0.14f, Wave.TRIANGLE, 0.4f)),
    SHATTER(SfxSegment(5000f, 2000f, 0.15f, Wave.NOISE, 0.25f)),
    BRO(SfxSegment(523f, 523f, 0.08f, Wave.SQUARE50, 0.4f), SfxSegment(659f, 659f, 0.08f, Wave.SQUARE50, 0.4f),
        SfxSegment(784f, 784f, 0.08f, Wave.SQUARE50, 0.4f), SfxSegment(659f, 659f, 0.08f, Wave.SQUARE50, 0.4f),
        SfxSegment(784f, 1047f, 0.3f, Wave.SQUARE50, 0.4f)),
    BOOMERANG(SfxSegment(500f, 900f, 0.07f, Wave.TRIANGLE, 0.45f), SfxSegment(900f, 500f, 0.07f, Wave.TRIANGLE, 0.45f),
        SfxSegment(500f, 900f, 0.07f, Wave.TRIANGLE, 0.35f)),
    SHELL(SfxSegment(200f, 700f, 0.1f, Wave.SQUARE50, 0.4f), SfxSegment(1500f, 400f, 0.25f, Wave.NOISE, 0.25f)),
    DRILL(SfxSegment(90f, 140f, 0.3f, Wave.SQUARE25, 0.35f), SfxSegment(600f, 200f, 0.12f, Wave.NOISE, 0.3f)),
    LIFE(SfxSegment(660f, 660f, 0.08f, Wave.SQUARE50, 0.4f), SfxSegment(880f, 880f, 0.08f, Wave.SQUARE50, 0.4f),
        SfxSegment(1320f, 1320f, 0.2f, Wave.SQUARE50, 0.4f)),
}

/**
 * Tiny real-time chiptune synthesizer. Runs on its own thread and streams into an AudioTrack.
 * The game talks to it only through a lock-free command queue.
 */
class Synth {
    private val sr = 44100
    private val commands = ConcurrentLinkedQueue<Any>()

    @Volatile private var running = false
    private var thread: Thread? = null

    private class SongCmd(val song: Song?, val tempo: Float, val then: Song?, val thenTempo: Float)

    // ---- sequencer state (touched only by the audio thread) ----
    private var song: Song? = null
    private var tempo = 1f
    private var nextSong: Song? = null
    private var nextTempo = 1f
    private var step = 0
    private var stepCounter = 0.0

    private val lead = Voice(Wave.SQUARE25).apply { vibrato = true }
    private val arp = Voice(Wave.SQUARE50)
    private val bass = Voice(Wave.TRIANGLE)
    private var arpNotes = IntArray(0)
    private var arpClock = 0

    private var kickT = -1
    private var snareT = -1
    private var hatT = -1
    private var kickPhase = 0.0
    private var snarePhase = 0.0
    private var noiseState = 0x1234567
    private var lastNoise = 0f

    private class SfxVoice(val sfx: Sfx) {
        var seg = 0
        var t = 0
        var phase = 0.0
    }
    private val sfxVoices = ArrayList<SfxVoice>()

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "synth").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(600)
        thread = null
    }

    fun play(sfx: Sfx) {
        if (running) commands.add(sfx)
    }

    /** Switch to [song] (null = silence). When a non-looping song ends, [then] starts. */
    fun playSong(song: Song?, tempo: Float = 1f, then: Song? = null, thenTempo: Float = 1f) {
        commands.add(SongCmd(song, tempo, then, thenTempo))
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            running = false
            return
        }
        track.play()
        val buf = ShortArray(512)
        while (running) {
            drainCommands()
            render(buf)
            track.write(buf, 0, buf.size)
        }
        track.pause()
        track.flush()
        track.release()
    }

    private fun drainCommands() {
        while (true) {
            when (val c = commands.poll() ?: return) {
                is Sfx -> if (sfxVoices.size < 8) sfxVoices.add(SfxVoice(c))
                is SongCmd -> {
                    startSong(c.song, c.tempo)
                    nextSong = c.then
                    nextTempo = c.thenTempo
                }
            }
        }
    }

    private fun startSong(s: Song?, t: Float) {
        song = s
        tempo = t
        if (s != null) lead.wave = s.leadWave
        step = 0
        stepCounter = 0.0
        lead.release(); arp.release(); bass.release()
        arpNotes = IntArray(0)
        if (s != null) triggerStep(s, 0)
    }

    private fun samplesPerStep(s: Song) = sr * 60.0 / (s.bpm * tempo * 4.0)

    private fun triggerStep(s: Song, i: Int) {
        val sps = samplesPerStep(s)
        s.leadAt[i]?.let { lead.noteOn(midiToHz(it.midi), (it.len * sps * 0.92).toInt(), 1f) }
        s.bassAt[i]?.let { bass.noteOn(midiToHz(it.midi), (it.len * sps * 0.7).toInt(), 1f) }
        s.chordAt[i]?.let {
            arpNotes = it.notes
            arp.noteOn(midiToHz(it.notes[0]), (it.len * sps * 0.8).toInt(), 1f)
            arpClock = 0
        }
        val d = s.drums[i]
        if (d and Song.KICK != 0) { kickT = 0; kickPhase = 0.0 }
        if (d and Song.SNARE != 0) snareT = 0
        if (d and Song.HAT != 0) hatT = 0
    }

    private fun advanceSequencer() {
        val s = song ?: return
        stepCounter += 1.0
        val sps = samplesPerStep(s)
        if (stepCounter < sps) return
        stepCounter -= sps
        step++
        if (step >= s.steps) {
            if (s.loop) {
                step = 0
            } else {
                val n = nextSong
                nextSong = null
                startSong(n, nextTempo)
                return
            }
        }
        triggerStep(s, step)
    }

    private fun noise(): Float {
        var x = noiseState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        noiseState = x
        return (x and 0xFFFF) / 32768f - 1f
    }

    private fun render(buf: ShortArray) {
        for (i in buf.indices) {
            advanceSequencer()

            // Fast chord arpeggio, classic chiptune style.
            if (arpNotes.isNotEmpty()) {
                val idx = (arpClock / (sr / 45)) % arpNotes.size
                arp.freq = midiToHz(arpNotes[idx])
                arpClock++
            }

            var music = lead.sample(sr) * 0.20f + arp.sample(sr) * 0.085f + bass.sample(sr) * 0.34f
            music += drums()

            var fx = 0f
            if (sfxVoices.isNotEmpty()) {
                var k = sfxVoices.size - 1
                while (k >= 0) {
                    val v = sfxVoices[k]
                    val out = sfxSample(v)
                    if (out.isNaN()) sfxVoices.removeAt(k) else fx += out
                    k--
                }
            }

            var mix = music * 0.85f + fx * 0.32f
            mix = mix / (1f + abs(mix) * 0.6f) // soft clip
            buf[i] = (mix.coerceIn(-1f, 1f) * 30000f).toInt().toShort()
        }
    }

    private fun drums(): Float {
        var out = 0f
        if (kickT >= 0) {
            val t = kickT.toFloat() / sr
            val len = 0.16f
            if (t > len) kickT = -1 else {
                val f = 42.0 + 120.0 * exp(-t * 28.0)
                kickPhase += f / sr
                val env = (1f - t / len).pow(2)
                out += sin(kickPhase * 2 * PI).toFloat() * env * 0.55f
                kickT++
            }
        }
        if (snareT >= 0) {
            val t = snareT.toFloat() / sr
            val len = 0.15f
            if (t > len) snareT = -1 else {
                snarePhase += 185.0 / sr
                val env = (1f - t / len).pow(2)
                val tone = (4.0 * abs(snarePhase % 1.0 - 0.5) - 1.0).toFloat()
                out += (noise() * 0.30f + tone * 0.14f) * env
                snareT++
            }
        }
        if (hatT >= 0) {
            val t = hatT.toFloat() / sr
            val len = 0.035f
            if (t > len) hatT = -1 else {
                val n = noise()
                val hp = n - lastNoise
                lastNoise = n
                out += hp * (1f - t / len) * 0.07f
                hatT++
            }
        }
        return out
    }

    /** Returns NaN when the effect is finished. */
    private fun sfxSample(v: SfxVoice): Float {
        val segs = v.sfx.segments
        if (v.seg >= segs.size) return Float.NaN
        val s = segs[v.seg]
        val total = (s.seconds * sr).toInt()
        val p = v.t.toFloat() / total
        val f = s.f0 + (s.f1 - s.f0) * p
        v.phase += f / sr
        val ph = v.phase % 1.0
        val w = when (s.wave) {
            Wave.SQUARE25 -> if (ph < 0.25) 1f else -1f
            Wave.SQUARE50 -> if (ph < 0.5) 1f else -1f
            Wave.TRIANGLE -> (4.0 * abs(ph - 0.5) - 1.0).toFloat()
            Wave.NOISE -> noise()
        }
        val env = 1f - 0.7f * p
        v.t++
        if (v.t >= total) {
            v.seg++
            v.t = 0
        }
        return w * env * s.volume
    }

    private class Voice(var wave: Wave) {
        var freq = 0f
        var phase = 0.0
        var gate = 0
        var env = 0f
        var vol = 0f
        var age = 0
        var vibrato = false

        fun noteOn(f: Float, lenSamples: Int, v: Float) {
            freq = f
            gate = lenSamples
            vol = v
            age = 0
        }

        fun release() {
            gate = 0
        }

        fun sample(sr: Int): Float {
            val target = if (gate > 0) 1f else 0f
            env += (target - env) * (if (target > env) 0.03f else 0.0025f)
            if (gate > 0) gate--
            if (env < 0.0005f && gate <= 0) return 0f
            age++
            var f = freq.toDouble()
            if (vibrato && age > sr / 6) f *= 1.0 + 0.010 * sin(age * 2.0 * PI * 5.5 / sr)
            phase += f / sr
            if (phase >= 1.0) phase -= 1.0
            val s = when (wave) {
                Wave.SQUARE25 -> if (phase < 0.25) 1f else -1f
                Wave.SQUARE50 -> if (phase < 0.5) 1f else -1f
                Wave.TRIANGLE -> (4.0 * abs(phase - 0.5) - 1.0).toFloat()
                Wave.NOISE -> 0f
            }
            return s * env * vol
        }
    }

    companion object {
        fun midiToHz(m: Int): Float = (440.0 * 2.0.pow((m - 69) / 12.0)).toFloat()
    }
}
