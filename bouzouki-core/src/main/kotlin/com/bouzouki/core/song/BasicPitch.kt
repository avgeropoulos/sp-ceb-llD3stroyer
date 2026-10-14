package com.bouzouki.core.song

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.PriorityQueue
import kotlin.math.floor
import kotlin.math.max

/** A transcribed note: start and end in seconds, MIDI pitch, and loudness 0..1. */
data class NoteEvent(val start: Double, val end: Double, val midi: Int, val amplitude: Double)

/**
 * Note transcription with Spotify's Basic Pitch model (Apache-2.0, github.com/spotify/basic-pitch).
 * The windowing and the note decoding below follow basic_pitch/inference.py and
 * basic_pitch/note_creation.py (with the default settings), ported to Kotlin.
 */
class BasicPitch(private val env: OrtEnvironment, private val session: OrtSession) {
    /** Frame-by-frame note and onset activations, [frame][key] for 88 piano keys from A0. */
    class Activations(val frames: Array<FloatArray>, val onsets: Array<FloatArray>)

    /** Runs the model over [audio] (mono, 22050 Hz). */
    fun activations(audio: FloatArray, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Activations {
        val overlapLen = OVERLAP_FRAMES * FFT_HOP
        val hop = AUDIO_N_SAMPLES - overlapLen
        val padded = FloatArray(overlapLen / 2 + audio.size).also { System.arraycopy(audio, 0, it, overlapLen / 2, audio.size) }
        val windows = (padded.size + hop - 1) / hop
        val nOut = floor(audio.size * (ANNOTATIONS_FPS.toDouble() / SAMPLE_RATE)).toInt()
        val frames = ArrayList<FloatArray>(nOut)
        val onsets = ArrayList<FloatArray>(nOut)
        val olap = OVERLAP_FRAMES / 2
        var w = 0
        while (w < windows) {
            if (cancelled()) throw InterruptedException()
            val batch = minOf(BATCH, windows - w)
            val input = FloatArray(batch * AUDIO_N_SAMPLES)
            for (b in 0 until batch) {
                val start = (w + b) * hop
                val len = minOf(AUDIO_N_SAMPLES, padded.size - start)
                if (len > 0) System.arraycopy(padded, start, input, b * AUDIO_N_SAMPLES, len)
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(batch.toLong(), AUDIO_N_SAMPLES.toLong(), 1)).use { tensor ->
                session.run(mapOf(INPUT to tensor), setOf(NOTE_OUT, ONSET_OUT)).use { res ->
                    val note = (res.get(NOTE_OUT).get() as OnnxTensor).floatBuffer
                    val onset = (res.get(ONSET_OUT).get() as OnnxTensor).floatBuffer
                    for (b in 0 until batch) {
                        // Drop half the overlapping frames at each side of every window.
                        for (f in olap until ANNOT_N_FRAMES - olap) {
                            if (frames.size >= nOut) break
                            val base = (b * ANNOT_N_FRAMES + f) * N_KEYS
                            frames += FloatArray(N_KEYS) { note.get(base + it) }
                            onsets += FloatArray(N_KEYS) { onset.get(base + it) }
                        }
                    }
                }
            }
            w += batch
            progress(w.toFloat() / windows)
        }
        return Activations(frames.toTypedArray(), onsets.toTypedArray())
    }

    companion object {
        const val SAMPLE_RATE = 22050
        const val FFT_HOP = 256
        const val AUDIO_N_SAMPLES = SAMPLE_RATE * 2 - FFT_HOP // 43844
        const val ANNOTATIONS_FPS = SAMPLE_RATE / FFT_HOP     // 86
        const val ANNOT_N_FRAMES = ANNOTATIONS_FPS * 2        // 172
        const val N_KEYS = 88
        const val MIDI_OFFSET = 21
        private const val OVERLAP_FRAMES = 30
        private const val BATCH = 4
        private const val INPUT = "serving_default_input_2:0"
        private const val NOTE_OUT = "StatefulPartitionedCall:1"
        private const val ONSET_OUT = "StatefulPartitionedCall:2"

        /** Seconds at the start of model frame [frame] (model_frames_to_time). */
        fun frameTime(frame: Int): Double {
            val windowOffset = (FFT_HOP.toDouble() / SAMPLE_RATE) * (ANNOT_N_FRAMES - AUDIO_N_SAMPLES.toDouble() / FFT_HOP) + 0.0018
            return frame * FFT_HOP.toDouble() / SAMPLE_RATE - windowOffset * floor(frame.toDouble() / ANNOT_N_FRAMES)
        }

        /**
         * Turns activations into notes (output_to_notes_polyphonic with infer_onsets and the
         * "melodia trick", onset 0.5, frame 0.3, minimum length 11 frames ≈ 128 ms).
         */
        fun notes(
            act: Activations,
            onsetThresh: Float = 0.5f,
            frameThresh: Float = 0.3f,
            minNoteLen: Int = 11,
            energyTol: Int = 11,
        ): List<NoteEvent> {
            val frames = act.frames
            val nFrames = frames.size
            if (nFrames < 3) return emptyList()
            val onsets = inferOnsets(act.onsets, frames)
            val remaining = Array(nFrames) { frames[it].copyOf() }
            val events = ArrayList<IntArray>()      // start, end, key
            val amps = ArrayList<Double>()

            // Onset peaks (local maxima in time) above the threshold, latest first.
            val peaks = ArrayList<Long>()
            for (t in 1 until nFrames - 1) for (k in 0 until N_KEYS) {
                val v = onsets[t][k]
                if (v > onsets[t - 1][k] && v > onsets[t + 1][k] && v >= onsetThresh) peaks += t.toLong() * N_KEYS + k
            }
            for (p in peaks.indices.reversed()) {
                val start = (peaks[p] / N_KEYS).toInt()
                val k = (peaks[p] % N_KEYS).toInt()
                if (start >= nFrames - 1) continue
                var i = start + 1
                var low = 0
                while (i < nFrames - 1 && low < energyTol) {
                    if (remaining[i][k] < frameThresh) low++ else low = 0
                    i++
                }
                i -= low
                if (i - start <= minNoteLen) continue
                for (t in start until i) {
                    remaining[t][k] = 0f
                    if (k < N_KEYS - 1) remaining[t][k + 1] = 0f
                    if (k > 0) remaining[t][k - 1] = 0f
                }
                events += intArrayOf(start, i, k)
                amps += mean(frames, start, i, k)
            }

            // "Melodia trick": follow the strongest leftover energy into notes without a clear onset.
            // Activations only ever get zeroed, so a lazily checked max-heap stands in for repeated argmax.
            class Peak(val value: Float, val id: Long)
            val heap = PriorityQueue<Peak>(compareByDescending<Peak> { it.value }.thenBy { it.id })
            for (t in 0 until nFrames) for (k in 0 until N_KEYS) {
                val v = remaining[t][k]
                if (v > frameThresh) heap += Peak(v, t.toLong() * N_KEYS + k)
            }
            while (heap.isNotEmpty()) {
                val peak = heap.poll()
                val mid = (peak.id / N_KEYS).toInt()
                val k = (peak.id % N_KEYS).toInt()
                if (remaining[mid][k] != peak.value) continue // zeroed since it was queued
                remaining[mid][k] = 0f
                var i = mid + 1
                var low = 0
                while (i < nFrames - 1 && low < energyTol) {
                    if (remaining[i][k] < frameThresh) low++ else low = 0
                    zeroAround(remaining, i, k)
                    i++
                }
                val end = i - 1 - low
                i = mid - 1
                low = 0
                while (i > 0 && low < energyTol) {
                    if (remaining[i][k] < frameThresh) low++ else low = 0
                    zeroAround(remaining, i, k)
                    i--
                }
                val start = i + 1 + low
                if (end - start <= minNoteLen) continue
                events += intArrayOf(start, end, k)
                amps += mean(frames, start, end, k)
            }

            return events.indices.map {
                val (s, e, k) = events[it]
                NoteEvent(frameTime(s), frameTime(e), k + MIDI_OFFSET, amps[it])
            }.sortedBy { it.start }
        }

        private fun zeroAround(m: Array<FloatArray>, t: Int, k: Int) {
            m[t][k] = 0f
            if (k < N_KEYS - 1) m[t][k + 1] = 0f
            if (k > 0) m[t][k - 1] = 0f
        }

        private fun mean(frames: Array<FloatArray>, from: Int, to: Int, k: Int): Double {
            var s = 0.0
            for (t in from until to) s += frames[t][k]
            return if (to > from) s / (to - from) else 0.0
        }

        /** Adds onsets where the note activations jump up (get_infered_onsets, n_diff = 2). */
        private fun inferOnsets(onsets: Array<FloatArray>, frames: Array<FloatArray>): Array<FloatArray> {
            val n = frames.size
            val diff = Array(n) { FloatArray(N_KEYS) }
            var maxDiff = 0f
            var maxOnset = 0f
            for (t in 0 until n) for (k in 0 until N_KEYS) {
                maxOnset = max(maxOnset, onsets[t][k])
                if (t < 2) continue
                val d = minOf(frames[t][k] - frames[t - 1][k], frames[t][k] - frames[t - 2][k]).coerceAtLeast(0f)
                diff[t][k] = d
                maxDiff = max(maxDiff, d)
            }
            val scale = if (maxDiff > 0f) maxOnset / maxDiff else 0f
            return Array(n) { t -> FloatArray(N_KEYS) { k -> max(onsets[t][k], diff[t][k] * scale) } }
        }
    }
}
