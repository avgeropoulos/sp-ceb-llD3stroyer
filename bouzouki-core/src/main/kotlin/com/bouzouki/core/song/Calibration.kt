package com.bouzouki.core.song

import com.bouzouki.core.Course
import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What calibration measured of the player's bouzouki and room: each open course plucked a few times, and a moment of
 * silence. For each course its pitch ([CourseReading.midi], [CourseReading.cents] from it), whether it sounds an
 * octave pair, and how loud its octave string is against its own ([CourseReading.octaveRatio]); for the bouzouki its
 * tuning ([cents], [tuning]); and the noise floor, as Basic Pitch hears silence ([noiseFloor], a note activation) and
 * as a level ([noiseDb], dB full scale). [post] applies it to the transcription's post-processing.
 */
data class Calibration(
    val courses: List<CourseReading>,
    val cents: Double,
    val noiseFloor: Double,
    val noiseDb: Double,
    val frets: Int,
) {
    data class CourseReading(val midi: Int, val cents: Double, val octavePair: Boolean, val octaveRatio: Double?)

    /** The tuning heard: a standard bouzouki's if it is one, else a custom one. */
    val tuning: Instrument get() = Instrument.custom(courses.map { Course(it.midi, it.octavePair) }, frets)

    /** How loud the octave strings sound against their courses' own strings (the median over the pair courses). */
    val octaveRatio: Double? get() = courses.mapNotNull { it.octaveRatio }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /** [base] with what was measured: the octave ratio, the noise floor and the tuning (for recordings that can't tell). */
    fun post(base: PostConfig): PostConfig = base.calibrated(octaveRatio, noiseFloor).copy(tuningHint = cents)

    fun toJson(): String = buildString {
        append("{\"cents\":").append(cents).append(",\"noiseFloor\":").append(noiseFloor).append(",\"noiseDb\":").append(noiseDb)
        append(",\"frets\":").append(frets).append(",\"courses\":[")
        append(courses.joinToString(",") { "[${it.midi},${it.cents},${if (it.octavePair) 1 else 0},${it.octaveRatio ?: -1.0}]" })
        append("]}")
    }

    companion object {
        fun fromJson(json: String): Calibration? = try {
            val p = MiniJson(json).parse() as Map<*, *>
            fun d(k: String) = (p[k] as Number).toDouble()
            Calibration(
                courses = (p["courses"] as List<*>).map { c ->
                    val a = (c as List<*>).map { (it as Number).toDouble() }
                    CourseReading(a[0].toInt(), a[1], a[2] > 0.5, a[3].takeIf { it >= 0 })
                },
                cents = d("cents"), noiseFloor = d("noiseFloor"), noiseDb = d("noiseDb"), frets = d("frets").toInt(),
            )
        } catch (_: Exception) { null }

        /**
         * Measures [silence] and [plucks] (one clip per course, low to high, of the open course plucked a few times; all
         * mono, 22050 Hz) with Basic Pitch ([pitch]). [expected] is the bouzouki the player said they have: each course
         * is looked for within a fourth of its pitch there, and keeps its frets.
         */
        fun measure(silence: FloatArray, plucks: List<FloatArray>, expected: Instrument, pitch: BasicPitch): Calibration {
            val quiet = pitch.activations(silence)
            // The note activation silence reaches (the 95th percentile over every key and frame).
            val levels = quiet.frames.flatMap { f -> f.map { it.toDouble() } }.sorted()
            val noiseFloor = if (levels.isEmpty()) 0.0 else levels[min(levels.size - 1, (levels.size * 0.95).toInt())]
            val rms = sqrt(silence.sumOf { it.toDouble() * it } / max(1, silence.size))
            val readings = plucks.mapIndexed { c, clip ->
                val e = expected.courses.getOrNull(c)
                course(clip, e?.openMidi ?: 62, e?.octavePair ?: false, pitch)
            }
            val cents = readings.map { it.cents }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
            return Calibration(readings, cents, noiseFloor, 20 * log10(max(rms, 1e-9)), expected.frets)
        }

        /**
         * One open course's reading from [clip], near [expectedMidi]; [expectedPair] (the player's bouzouki has an octave
         * pair here) asks less of the octave above to call it a pair: an open unison course's second harmonic shows too.
         */
        private fun course(clip: FloatArray, expectedMidi: Int, expectedPair: Boolean, pitch: BasicPitch): CourseReading {
            val act = pitch.activations(clip)
            val frames = act.frames
            fun total(key: Int) = if (key !in 0 until BasicPitch.N_KEYS) 0.0 else frames.sumOf { it[key].toDouble() }
            val e = expectedMidi - BasicPitch.MIDI_OFFSET
            var main = (e - 5..e + 5).maxBy { total(it) }
            // The octave string may sound louder than the course's own: the pitch an octave below, strong too, is the course's.
            if (total(main - 12) >= OCTAVE_BELOW * total(main) && abs(main - 12 - e) <= 5) main -= 12
            // The octave above, at the plucks (each onset peak of the course's pitch, its first frames).
            val onsets = (1 until act.onsets.size - 1).filter { f ->
                val o = act.onsets[f][main]
                o >= ONSET && o >= act.onsets[f - 1][main] && o > act.onsets[f + 1][main]
            }
            val ratios = onsets.mapNotNull { f ->
                if (main + 12 >= BasicPitch.N_KEYS) return@mapNotNull null
                var low = 0f; var high = 0f
                for (t in f until min(frames.size, f + 6)) { low = max(low, frames[t][main]); high = max(high, frames[t][main + 12]) }
                if (low < 0.2f) null else (high / low).toDouble()
            }.sorted()
            val ratio = if (ratios.isEmpty()) null else ratios[ratios.size / 2]
            val pair = ratio != null && ratio >= if (expectedPair) PAIR_RATIO else UNISON_RATIO
            val midi = main + BasicPitch.MIDI_OFFSET
            val cents = Tuning.estimateOrNull(clip) ?: yinCents(clip, midi) ?: 0.0
            return CourseReading(midi, cents, pair, if (pair) ratio else null)
        }

        /** How far from [midi] the clip's steady pitch is (cents), by YIN over its frames near that note; null if none. */
        private fun yinCents(clip: FloatArray, midi: Int): Double? {
            val yin = com.bouzouki.core.Yin(BasicPitch.SAMPLE_RATE, window = 2048)
            val target = com.bouzouki.core.midiToHz(midi)
            val cents = ArrayList<Double>()
            var off = 0
            while (off + yin.frameSize <= clip.size) {
                yin.detect(clip, off)?.let { hz ->
                    val c = 1200 * kotlin.math.ln(hz / target) / kotlin.math.ln(2.0)
                    if (abs(c) < 60) cents += c
                }
                off += 512
            }
            return cents.sorted().let { if (it.size < 5) null else it[it.size / 2] }
        }

        /** A pitch an octave below at this share of the loudest one's activation is the course's own (an octave pair). */
        private const val OCTAVE_BELOW = 0.35
        /** The octave above at this share or more of the course's pitch at its plucks: the course is an octave pair. */
        private const val PAIR_RATIO = 0.1
        /** ... and this much where the player's bouzouki has a unison pair (a real octave string, not a harmonic). */
        private const val UNISON_RATIO = 0.45
        private const val ONSET = 0.4
    }
}
