package com.bouzouki.core.song

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bouzouki.core.song.DemucsSeparator.Companion.BINS
import com.bouzouki.core.song.DemucsSeparator.Companion.FRAMES
import com.bouzouki.core.song.DemucsSeparator.Companion.SEGMENT
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemucsTest {
    @Test
    fun spectrogramRoundTrip() {
        // Tones well below Nyquist, so dropping the Nyquist bin (as HTDemucs does) costs nothing.
        // The first and last 1536 samples don't come back: _spec drops the frames centred
        // there and _ispec puts empty ones in their place (the time branch covers for it).
        // Full-length (7.8 s) and the short-segment exports' lengths (3.9 s, 2.0 s).
        for (seg in listOf(SEGMENT, 171_990, 88_200)) {
            val rnd = Random(3)
            val mix = FloatArray(2 * seg)
            val freqs = DoubleArray(6) { 50.0 + rnd.nextDouble() * 8000 }
            for (i in 0 until seg) {
                var l = 0.0; var r = 0.0
                for ((k, f) in freqs.withIndex()) {
                    val s = sin(2 * PI * f * i / 44100 + k)
                    if (k % 2 == 0) l += s else r += s
                    l += 0.1 * s
                }
                mix[i] = (l / 6).toFloat(); mix[seg + i] = (r / 6).toFloat()
            }
            val stft = DemucsStft(seg)
            val spec = FloatArray(4 * BINS * ((seg + 1023) / 1024))
            stft.spectrogram(mix, spec)
            val outL = DoubleArray(seg)
            val outR = DoubleArray(seg)
            stft.waveform(spec, 0, outL, outR)
            var err = 0.0
            for (i in 1536 until seg - 1536) err = maxOf(err, abs(outL[i] - mix[i]), abs(outR[i] - mix[seg + i]))
            assertTrue(err < 1e-4, "round trip error $err at $seg") // float32 spectrogram, sidelobes at Nyquist
        }
    }

    @Test
    fun spectrogramOfASineSitsInItsBin() {
        // Bin k of a 4096-point frame is k·44100/4096 Hz; a normalised Hann STFT gives |X| = A·√4096/4 there.
        val k = 100
        val mix = FloatArray(2 * SEGMENT) { if (it < SEGMENT) sin(2 * PI * k * it / 4096).toFloat() else 0f }
        val spec = FloatArray(4 * BINS * FRAMES)
        DemucsStft().spectrogram(mix, spec)
        val t = FRAMES / 2
        val o = k * FRAMES + t
        val plane = BINS * FRAMES
        val mag = kotlin.math.hypot(spec[o].toDouble(), spec[plane + o].toDouble())
        assertEquals(16.0, mag, 1e-3)
        assertTrue(abs(spec[2 * plane + o]) < 1e-6)
    }

    @Test
    fun checksumFile() {
        val sums = DemucsSeparator.parseChecksums(
            "${"ab".repeat(32)}  htdemucs_6s.onnx\n${"CD".repeat(32)} *htdemucs_6s_fp16.onnx\n\n# comment\n",
        )
        assertEquals("ab".repeat(32), sums[DemucsSeparator.MODEL_6S])
        assertEquals("cd".repeat(32), sums[DemucsSeparator.MODEL_6S_FP16])
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DemucsSeparator.sha256("abc".byteInputStream()),
        )
    }

    /**
     * The whole Kotlin path against torch's demucs.apply.apply_model on the reference vector
     * that tools/demucs/export_onnx.py --ref-dir writes (random weights work as well as real).
     */
    @Test
    fun matchesPythonReference() {
        val dir = System.getenv("DEMUCS_REF_DIR")?.let(::File) ?: return
        val meta = File(dir, "meta.txt").readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        val n = meta.getValue("samples").toInt()
        val sources = meta.getValue("sources").split(',')
        val input = readF32(File(dir, "input.f32"))
        val expected = readF32(File(dir, "stems.f32"))
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        env.createSession(File(dir, meta.getValue("model")).path, opts).use { session ->
            val sep = DemucsSeparator(env, session)
            println("segment ${sep.segment} samples, ${sep.frames} frames, keepsBass ${sep.keepsBass}")
            assertEquals(sources, sep.sources)
            val times = mutableListOf<DemucsSeparator.SegmentTiming>()
            val got = sep.separate(input.copyOfRange(0, n), input.copyOfRange(n, 2 * n), sources, timing = { times += it })
            println("segments: " + times.joinToString { "${it.index}: dsp ${it.dspMillis} ms, model ${it.modelMillis} ms" })
            for ((s, name) in sources.withIndex()) {
                var sig = 0.0; var noise = 0.0
                for (c in 0..1) {
                    val g = got.getValue(name)[c]
                    for (i in 0 until n) {
                        val e = expected[(s * 2 + c) * n + i].toDouble()
                        sig += e * e; noise += (g[i] - e) * (g[i] - e)
                    }
                }
                val snr = 10 * log10(sig / noise)
                println("$name: SNR %.1f dB".format(snr))
                assertTrue(snr > 40, "$name SNR $snr dB")
            }
            val mono = sep.stemMono(input.copyOfRange(0, n), input.copyOfRange(n, 2 * n))
            assertEquals(n, mono.size)
        }
    }

    private fun readF32(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }
}
