package com.bouzouki.core.song

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bouzouki.core.Instrument
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Developer tool: analyses a song (a 44.1 kHz WAV) exactly as the app does, to look into wrong or
 * missing notes on real recordings. Writes the saved song (song.json) and what its line comes from
 * (song.data); with a fourth argument, also the song without its voice there as a 16-bit mono WAV, for
 * close-up spectrograms (deleted with the audio by .github/workflows/analyze-song.yml).
 * `gradle :bouzouki-core:analyzeSong -PsongAudio=<wav> -PsongModel=<mdx.onnx> -PsongOut=<dir> [-PsongStem=<wav>]`
 */
fun main(args: Array<String>) {
    require(args.size >= 3) { "usage: analyzeSong <song.wav> <mdx.onnx> <out dir> [stem.wav]" }
    val (audio, model, out) = args
    val stem = args.getOrNull(3)?.takeIf { it.isNotBlank() }
    val wav = readWav(File(audio))
    val left = wav[0]
    val right = if (wav.size > 1) wav[1] else wav[0]
    val env = OrtEnvironment.getEnvironment()
    val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors()) }
    val started = System.nanoTime()
    fun seconds() = "%.0f s".format((System.nanoTime() - started) / 1e9)
    env.createSession(model, options).use { mdx ->
        env.createSession(File("../bouzouki/src/main/assets/basic_pitch.onnx").path, options).use { bp ->
            var lastStage: SongAnalyzer.Stage? = null
            val (song, data) = SongAnalyzer(MdxSeparator(env, mdx), BasicPitch(env, bp)).analyzeWithData(
                "song", "song", left, right, Instrument.TETRACHORDO,
                progress = { stage, _ -> if (stage != lastStage) { lastStage = stage; println("${seconds()}: ${stage.label}") } },
                separated = { mono -> if (stem != null) writeWav16(File(stem), mono, MdxSeparator.SAMPLE_RATE) },
            )
            val dir = File(out).apply { mkdirs() }
            File(dir, "song.json").writeText(song.toJson())
            File(dir, "song.data").outputStream().buffered().use { data.write(it) }
            println("%.1f s of audio, %d notes, %d beats, analysed in %s".format(left.size / 44100.0, song.notes.size, song.beats.size, seconds()))
        }
    }
}

private fun writeWav16(f: File, mono: FloatArray, rate: Int) {
    val peak = mono.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1e-6f) ?: 1f
    val gain = if (peak > 1f) 1f / peak else 1f
    DataOutputStream(f.outputStream().buffered()).use { out ->
        val n = mono.size
        val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        head.put("RIFF".toByteArray()).putInt(36 + 2 * n).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(2 * n)
        out.write(head.array())
        val buf = ByteBuffer.allocate(2 * 4096).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < n) {
            buf.clear()
            val end = minOf(n, i + 4096)
            for (k in i until end) buf.putShort((mono[k] * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            out.write(buf.array(), 0, 2 * (end - i))
            i = end
        }
    }
}
