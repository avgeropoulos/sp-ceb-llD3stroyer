package com.bouzouki

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.bouzouki.core.BouzoukiSynth
import com.bouzouki.core.Instrument
import com.bouzouki.core.song.BasicPitch
import com.bouzouki.core.song.OnnxPitchModel
import com.bouzouki.core.song.PitchModel
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale

/**
 * The two engines that can run Basic Pitch on the phone: ONNX Runtime (basic_pitch.onnx) or LiteRT, Google's
 * TensorFlow Lite runtime (basic_pitch.tflite, Spotify's own conversion of the same network: their outputs agree to
 * within 1e-6). Which is faster depends on the phone; [benchmark] measures both, and the faster one is kept.
 */
object PitchEngines {
    enum class Engine(val label: String) { ONNX("ONNX Runtime"), LITERT("LiteRT") }

    /** A ready model and what to close after it. */
    class Opened(val pitch: BasicPitch, private val close: () -> Unit) : Closeable {
        override fun close() = close.invoke()
    }

    fun chosen(context: Context): Engine =
        Engine.entries.getOrElse(prefs(context).getInt(PREF_ENGINE, Engine.ONNX.ordinal)) { Engine.ONNX }

    fun choose(context: Context, e: Engine) = prefs(context).edit().putInt(PREF_ENGINE, e.ordinal).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("bouzouki", Context.MODE_PRIVATE)

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    fun open(context: Context, engine: Engine = chosen(context)): Opened = when (engine) {
        Engine.ONNX -> {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads())
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val bytes = context.assets.open("basic_pitch.onnx").use { it.readBytes() }
            val session = env.createSession(bytes, options)
            Opened(BasicPitch(OnnxPitchModel(env, session))) { session.close(); options.close() }
        }
        Engine.LITERT -> {
            val model = LiteRtPitchModel(context)
            Opened(BasicPitch(model)) { model.close() }
        }
    }

    /**
     * Times both engines on [seconds] of synthesized bouzouki (after a first warm-up run each) and keeps the faster;
     * the report says how long each took and how closely their outputs agree. Call it off the main thread.
     */
    fun benchmark(context: Context, seconds: Double = 20.0): String {
        val audio = testAudio(seconds)
        val lines = ArrayList<String>()
        val times = HashMap<Engine, Double>()
        val acts = HashMap<Engine, BasicPitch.Activations>()
        for (e in Engine.entries) {
            try {
                open(context, e).use { m ->
                    m.pitch.activations(audio.copyOfRange(0, BasicPitch.AUDIO_N_SAMPLES)) // warm-up
                    val t0 = System.nanoTime()
                    acts[e] = m.pitch.activations(audio)
                    times[e] = (System.nanoTime() - t0) / 1e9
                }
                lines += String.format(Locale.US, "%s: %.2f s for %.0f s of audio (%.3f s per second)", e.label, times[e], seconds, times[e]!! / seconds)
            } catch (t: Throwable) {
                lines += "${e.label}: failed (${t.javaClass.simpleName}: ${t.message})"
            }
        }
        val a = acts[Engine.ONNX]; val b = acts[Engine.LITERT]
        if (a != null && b != null) {
            var diff = 0f
            for (f in a.frames.indices) for (k in 0 until BasicPitch.N_KEYS) diff = maxOf(diff, kotlin.math.abs(a.frames[f][k] - b.frames[f][k]))
            lines += String.format(Locale.US, "Largest difference between their note outputs: %.1e", diff)
        }
        times.minByOrNull { it.value }?.key?.let { choose(context, it); lines += "Using ${it.label} from now on." }
        lines += "${Runtime.getRuntime().availableProcessors()} cores, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}"
        return lines.joinToString("\n")
    }

    /** A bouzouki phrase from the app's synth at Basic Pitch's rate. */
    private fun testAudio(seconds: Double): FloatArray {
        val fs = BasicPitch.SAMPLE_RATE
        val synth = BouzoukiSynth(fs)
        val audio = FloatArray((seconds * fs).toInt())
        val melody = intArrayOf(62, 63, 66, 67, 69, 70, 72, 74, 72, 70, 69, 67, 66, 63)
        var pos = 0
        var k = 0
        while (true) {
            val at = (k * 0.25 * fs).toInt()
            if (at >= audio.size) break
            synth.render(audio, pos, at - pos); pos = at
            synth.pluck(3, melody[k % melody.size], Instrument.TETRACHORDO.courses[3].octavePair)
            k++
        }
        synth.render(audio, pos, audio.size - pos)
        return audio
    }

    private const val PREF_ENGINE = "pitch_engine"
}

/** Basic Pitch's TensorFlow Lite model (icassp_2022 nmp.tflite) in a LiteRT interpreter, with its batch size resized as asked. */
class LiteRtPitchModel(context: Context) : PitchModel, Closeable {
    private val interpreter: Interpreter
    private var batchSize = -1
    /** Output tensor index for the note, onset and contour outputs (found by name: their order isn't fixed). */
    private val noteIndex: Int
    private val onsetIndex: Int
    private val contourIndex: Int

    init {
        val bytes = context.assets.open("basic_pitch.tflite").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8)))
        fun index(name: String) = (0 until interpreter.outputTensorCount).first { interpreter.getOutputTensor(it).name() == name }
        noteIndex = index("StatefulPartitionedCall:1")
        onsetIndex = index("StatefulPartitionedCall:2")
        contourIndex = index("StatefulPartitionedCall:0")
    }

    override fun run(input: FloatArray, batch: Int): PitchModel.Output {
        if (batch != batchSize) {
            interpreter.resizeInput(0, intArrayOf(batch, BasicPitch.AUDIO_N_SAMPLES, 1))
            interpreter.allocateTensors()
            batchSize = batch
        }
        fun buffer(n: Int) = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val inBuf = buffer(input.size).apply { put(input); rewind() }
        val keys = batch * BasicPitch.ANNOT_N_FRAMES * BasicPitch.N_KEYS
        val notes = buffer(keys); val onsets = buffer(keys)
        val contours = buffer(batch * BasicPitch.ANNOT_N_FRAMES * BasicPitch.N_CONTOUR_BINS)
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inBuf), mapOf<Int, Any>(noteIndex to notes, onsetIndex to onsets, contourIndex to contours))
        fun array(b: FloatBuffer) = FloatArray(b.capacity()).also { b.rewind(); b.get(it) }
        return PitchModel.Output(array(notes), array(onsets), array(contours))
    }

    override fun close() = interpreter.close()
}
