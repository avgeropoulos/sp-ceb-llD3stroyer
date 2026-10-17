package com.bouzouki.core.song

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test

/**
 * Cost of the separators on this machine, run by hand (one model per run, so the peak memory
 * is that model's): SEPARATION_BENCH=<demucs .onnx> or "light", SEPARATION_BENCH_SECONDS=60.
 * SEPARATION_BENCH_ARENA=0 turns off ONNX Runtime's memory arena and memory patterns.
 * Prints seconds per second of audio and the process's peak resident memory (VmHWM).
 */
class SeparationBench {
    @Test
    fun bench() {
        val what = System.getenv("SEPARATION_BENCH") ?: return
        val seconds = System.getenv("SEPARATION_BENCH_SECONDS")?.toInt() ?: 60
        val threads = System.getenv("SEPARATION_BENCH_THREADS")?.toInt() ?: 4
        val n = seconds * 44100
        val rnd = Random(1)
        val l = FloatArray(n) { (0.2 * sin(2 * PI * 440 * it / 44100) + 0.05 * rnd.nextDouble()).toFloat() }
        val r = FloatArray(n) { (0.2 * sin(2 * PI * 330 * it / 44100) + 0.05 * rnd.nextDouble()).toFloat() }
        val before = peakKb()
        val t0 = System.nanoTime()
        if (what == "light") {
            LightSeparator().bouzoukiMono(l, r)
        } else {
            val env = OrtEnvironment.getEnvironment()
            val arena = System.getenv("SEPARATION_BENCH_ARENA") != "0"
            val opts = if (arena) OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) } else DemucsSeparator.sessionOptions(threads)
            val load0 = System.nanoTime()
            env.createSession(what, opts).use { session ->
                println("bench: session load %.2f s".format((System.nanoTime() - load0) / 1e9))
                val sep = DemucsSeparator(env, session)
                val start = System.nanoTime()
                sep.stemMono(l, r, timing = { println("bench: segment ${it.index + 1}/${it.count} dsp ${it.dspMillis} ms model ${it.modelMillis} ms") })
                println("bench: separation only %.3f s per audio second".format((System.nanoTime() - start) / 1e9 / seconds))
            }
        }
        val total = (System.nanoTime() - t0) / 1e9
        println("bench: $what, $seconds s audio, $threads threads: %.3f s per audio second (incl. load), peak RSS %d MB (before %d MB), file %.1f MB"
            .format(total / seconds, peakKb() / 1024, before / 1024, if (what == "light") 0.0 else File(what).length() / 1e6))
    }

    private fun peakKb(): Long = File("/proc/self/status").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("VmHWM") }?.filter { it.isDigit() }?.toLong() ?: -1
}
