package com.sitarsitara.core

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bouzouki.core.song.BasicPitch
import com.bouzouki.core.song.NoSeparation
import com.bouzouki.core.song.SongAnalysis
import com.bouzouki.core.song.SongAnalyzer
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A synthetic sitar through the app's real note finding: the Basic Pitch model the app ships, and Μπουζούκι's line
 * following, as a phone runs them on a song with no singer to remove. Real sitar recordings haven't been tried.
 */
class AnalysisTest {
    private val rate = 44100
    private val model = File("../sitar/src/main/assets/basic_pitch.onnx")

    /** One stroke of the mizrab: [pitch] gives the MIDI pitch at every sample (a meend moves it). */
    private fun stroke(pitch: DoubleArray, seed: Int): FloatArray {
        val rnd = java.util.Random(seed.toLong())
        val out = FloatArray(pitch.size)
        val phase = DoubleArray(25) { rnd.nextDouble() * 2 * PI }
        var ph = 0.0
        val top = pitch.max()
        for (i in pitch.indices) {
            val f = 440.0 * 2.0.pow((pitch[i] - 69) / 12)
            ph += 2 * PI * f / rate
            val t = i.toDouble() / rate
            var s = 0.0
            var k = 1
            // The jawari (curved bridge) keeps the upper harmonics buzzing long after a guitar's die away.
            while (k * 440.0 * 2.0.pow((top - 69) / 12) < 0.45 * rate && k <= 24) {
                s += exp(-t * (1.6 + 0.22 * k)) * sin(k * ph + phase[k]) / k.toDouble().pow(0.7)
                k++
            }
            s += 0.6 * rnd.nextGaussian() * exp(-t / 0.004) // the mizrab's click
            out[i] = s.toFloat()
        }
        return out
    }

    /** A pitch curve that holds each pitch of [points] and glides to the next (a meend). */
    private fun glide(points: List<Double>, dur: Double, slide: Double = 0.12): DoubleArray {
        val n = (dur * rate).toInt()
        if (points.size == 1) return DoubleArray(n) { points[0] }
        val hold = (dur - slide * (points.size - 1)) / points.size
        val curve = ArrayList<Double>()
        for ((a, b) in points.zipWithNext()) {
            repeat((hold * rate).toInt()) { curve += a }
            val m = (slide * rate).toInt()
            for (j in 0 until m) { val x = j.toDouble() / m; curve += a + (b - a) * (3 * x * x - 2 * x * x * x) }
        }
        while (curve.size < n) curve += points.last()
        return DoubleArray(n) { curve[it] }
    }

    private class Stroke(val start: Double, val dur: Double, val pitch: DoubleArray)

    private fun render(strokes: List<Stroke>): FloatArray {
        val total = strokes.maxOf { it.start + it.dur } + 0.5
        val y = FloatArray((total * rate).toInt())
        for ((k, s) in strokes.withIndex()) {
            val tone = stroke(s.pitch, k)
            val i0 = (s.start * rate).toInt()
            for (j in tone.indices) {
                val damp = minOf(1.0, (tone.size - j) / (tone.size / 40.0)) // damped at the end
                if (i0 + j < y.size) y[i0 + j] += (tone[j] * damp).toFloat()
            }
        }
        val peak = y.maxOf { kotlin.math.abs(it) }
        return FloatArray(y.size) { y[it] * 0.4f / peak }
    }

    private fun analyse(audio: FloatArray): SongAnalysis {
        val env = OrtEnvironment.getEnvironment()
        return env.createSession(model.path, OrtSession.SessionOptions()).use { session ->
            SongAnalyzer(NoSeparation, BasicPitch(env, session)).analyzeInstrumental("t", "test", audio, SitarAnalysis.RANGE).first
        }
    }

    private val sa = 61.0 // C#4

    @Test
    fun aYamanRunIsReadAsSargam() {
        // Up and down Yaman with Sa on C#, a note every half second.
        val degrees = listOf(0, 2, 4, 6, 7, 9, 11, 12, 11, 9, 7, 6, 4, 2, 0)
        val song = analyse(render(degrees.mapIndexed { i, d -> Stroke(0.3 + i * 0.5, 0.48, glide(listOf(sa + d), 0.48)) }))
        val reading = Reading.of(SitarAnalysis.line(song))
        val heard = reading.notes.map { it.text() }
        assertEquals("C#", reading.saName, "heard $heard")
        assertEquals(Scale.KALYAN, reading.best?.scale, "heard $heard")
        assertEquals("S R G M P D N S' N D P M G R S".split(" "), heard.map { it.removePrefix("~") })
        // Every note here is struck. The analysis takes the step down from taar Sa to Ni for a pull-off (a
        // bouzouki's left hand would do that), so one may come out as a meend; no more.
        assertTrue(heard.count { it.startsWith("~") } <= 1, "heard $heard")
    }

    @Test
    fun aMeendIsOneStroke() {
        // Re struck and pulled up to Ga; then Ga struck and pulled through Ma to Pa; then Sa struck.
        val audio = render(listOf(
            Stroke(0.3, 0.9, glide(listOf(sa + 2, sa + 4), 0.9)),
            Stroke(1.3, 0.9, glide(listOf(sa + 4, sa + 7), 0.9, slide = 0.15)),
            Stroke(2.3, 0.6, glide(listOf(sa), 0.6)),
        ))
        val reading = Reading.of(SitarAnalysis.line(analyse(audio)), saPc = 1)
        // One stroke on Re, a meend up to Ga; a stroke on Ga, a meend up to Pa (nothing of the Ma it passes); Sa.
        assertEquals(listOf("R", "~G", "G", "~P", "S"), reading.notes.map { it.text() })
    }
}
