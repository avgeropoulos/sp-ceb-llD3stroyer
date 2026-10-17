package com.bouzouki.core.song

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bouzouki.core.BouzoukiSynth
import com.bouzouki.core.Instrument
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Calibration on the app's synth: a tetrachordo tuned 30 cents flat, each open course plucked three times. */
class CalibrationTest {
    private val model = File("../bouzouki/src/main/assets/basic_pitch.onnx")

    private fun plucks(inst: Instrument, course: Int, cents: Double): FloatArray {
        val fs = BasicPitch.SAMPLE_RATE
        val synth = BouzoukiSynth(fs).apply { tuningCents = cents }
        val audio = FloatArray(4 * fs)
        var pos = 0
        for (k in 0 until 3) {
            val at = (0.2 + k * 1.2) * fs
            synth.render(audio, pos, at.toInt() - pos); pos = at.toInt()
            synth.pluck(course, inst.courses[course].openMidi, inst.courses[course].octavePair)
        }
        synth.render(audio, pos, audio.size - pos)
        val rnd = java.util.Random(course.toLong())
        for (i in audio.indices) audio[i] += 0.002f * rnd.nextGaussian().toFloat()
        return audio
    }

    @Test
    fun measuresTheTuningThePairsAndTheNoise() {
        val inst = Instrument.TETRACHORDO
        val env = OrtEnvironment.getEnvironment()
        val cal = env.createSession(model.path, OrtSession.SessionOptions()).use { s ->
            val silence = FloatArray(2 * BasicPitch.SAMPLE_RATE) { (0.002 * java.util.Random(it.toLong()).nextGaussian()).toFloat() }
            Calibration.measure(silence, inst.courses.indices.map { plucks(inst, it, -30.0) }, inst, BasicPitch(env, s))
        }
        println(cal)
        assertTrue(abs(cal.cents + 30) < 3, "tuning ${cal.cents}")
        assertEquals(inst, cal.tuning)
        assertEquals(listOf(true, true, false, false), cal.courses.map { it.octavePair })
        val ratio = assertNotNull(cal.octaveRatio)
        assertTrue(ratio in 0.2..1.2, "octave ratio $ratio")
        assertTrue(cal.noiseFloor < 0.3, "noise floor ${cal.noiseFloor}")
        assertEquals(cal, Calibration.fromJson(cal.toJson()))
        // What it gives the post-processing.
        val p = cal.post(PostConfig(solo = true))
        assertEquals(-30.0, p.tuningHint!!, 3.0)
        assertTrue(p.octaveMaxRatio >= 1.5)
    }
}
