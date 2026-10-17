package com.bouzouki.core.eval

import com.bouzouki.core.song.BasicPitch
import com.bouzouki.core.song.resample
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * What an octave-paired course's pluck looks like to Basic Pitch, against a note on a unison course with a pair
 * course's pitch an octave below: the lower pitch's onset and level, and the upper's level over the lower's.
 * Run on the synthetic set (OCTAVE_STATS_DIR), to set [com.bouzouki.core.song.PostConfig]'s defaults.
 */
class OctaveStatsTest {
    @Test
    fun stats() {
        val dir = System.getenv("OCTAVE_STATS_DIR")?.let { File(it) } ?: return
        val items = EvalSet.load(dir).filter { it.kind == "solo" }
        val pair = ArrayList<DoubleArray>(); val unison = ArrayList<DoubleArray>()
        AppPipeline.withBasicPitch { bp ->
            for (item in items) {
                val wav = Wav.read(item.audio)
                val act = bp.activations(resample(wav.channels[0], wav.rate, BasicPitch.SAMPLE_RATE))
                val inst = item.instrument
                for (r in item.ref) {
                    if (r.course < 0) continue
                    val f0 = (r.start * BasicPitch.ANNOTATIONS_FPS).toInt()
                    if (f0 + 6 >= act.frames.size) continue
                    fun level(key: Int) = (f0 until f0 + 6).maxOf { act.frames[it][key].toDouble() }
                    fun onset(key: Int) = (max(0, f0 - 2)..min(act.onsets.size - 1, f0 + 3)).maxOf { act.onsets[it][key].toDouble() }
                    val isPair = inst.courses[r.course].octavePair
                    // lower = the course's pitch on a pair; on a unison course the pitch an octave below
                    val low = (if (isPair) r.midi else r.midi - 12) - BasicPitch.MIDI_OFFSET
                    if (low < 0 || low + 12 >= BasicPitch.N_KEYS) continue
                    val row = doubleArrayOf(onset(low), level(low), onset(low + 12), level(low + 12))
                    if (isPair) pair += row
                    else if (inst.courses.any { it.octavePair && r.midi - 12 - it.openMidi in 0..inst.frets }) unison += row
                }
            }
        }
        fun q(xs: List<Double>, p: Double) = xs.sorted().let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * p).toInt()] }
        for ((name, rows) in listOf("pair pluck" to pair, "unison note" to unison)) {
            val lowOn = rows.map { it[0] }; val lowLv = rows.map { it[1] }
            val ratio = rows.map { it[3] / max(1e-3, it[1]) }
            println("$name (${rows.size}): lower onset p10/50/90 %.2f %.2f %.2f · lower level %.2f %.2f %.2f · upper/lower %.2f %.2f %.2f".format(
                q(lowOn, .1), q(lowOn, .5), q(lowOn, .9), q(lowLv, .1), q(lowLv, .5), q(lowLv, .9), q(ratio, .1), q(ratio, .5), q(ratio, .9)))
        }
    }
}
