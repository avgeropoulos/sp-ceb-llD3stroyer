package com.bouzouki.core.song

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.PriorityQueue
import kotlin.math.floor
import kotlin.math.max

/**
 * A transcribed note: start and end in seconds, MIDI pitch, and loudness 0..1.
 * Notes from [BasicPitch.notes] also carry the evidence the melody tracker weighs:
 * [onset] is the onset activation where the note starts (0 if it has no clear onset) and
 * [salience] the strongest note activation in its first frames; [frame] is its first model frame and [endFrame] the
 * frame it ends at.
 * A note made by hand (onset = NaN) is one that was already chosen, e.g. from a saved line.
 * The bouzouki line marks how each of its notes is sounded in [articulation].
 */
data class NoteEvent(
    val start: Double,
    val end: Double,
    val midi: Int,
    val amplitude: Double,
    val onset: Double = Double.NaN,
    val salience: Double = amplitude,
    val frame: Int = -1,
    val endFrame: Int = -1,
    val articulation: Articulation = Articulation.PICK,
)

/**
 * Note transcription with Spotify's Basic Pitch model (Apache-2.0, github.com/spotify/basic-pitch).
 * The windowing and the note decoding below follow basic_pitch/inference.py and
 * basic_pitch/note_creation.py, ported to Kotlin; the note start times are sharper than
 * basic_pitch's (exact window offsets, onsets refined between frames, latency corrected).
 */
class BasicPitch(private val env: OrtEnvironment, private val session: OrtSession) {
    /**
     * Frame-by-frame note and onset activations, [frame][key] for 88 piano keys from A0, and the pitch
     * [contours] (null when not computed): [frame][bin], 3 bins a semitone with MIDI m at bin 3·(m − 21) + 1,
     * each activation 0..1 stored as 0..255 (a quarter of the memory of floats; read with [contour]).
     */
    class Activations(val frames: Array<FloatArray>, val onsets: Array<FloatArray>, val contours: Array<ByteArray>? = null) {
        /** The contour activation 0..1 at [frame] and [bin]; 0 without contours. */
        fun contour(frame: Int, bin: Int): Double = contours?.let { (it[frame][bin].toInt() and 0xFF) / 255.0 } ?: 0.0
    }

    /** Runs the model over [audio] (mono, 22050 Hz). */
    fun activations(audio: FloatArray, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Activations {
        val overlapLen = OVERLAP_FRAMES * FFT_HOP
        val hop = AUDIO_N_SAMPLES - overlapLen
        val padded = FloatArray(overlapLen / 2 + audio.size).also { System.arraycopy(audio, 0, it, overlapLen / 2, audio.size) }
        val windows = (padded.size + hop - 1) / hop
        val nOut = floor(audio.size * (ANNOTATIONS_FPS.toDouble() / SAMPLE_RATE)).toInt()
        val frames = ArrayList<FloatArray>(nOut)
        val onsets = ArrayList<FloatArray>(nOut)
        val contours = ArrayList<ByteArray>(nOut)
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
                session.run(mapOf(INPUT to tensor), setOf(NOTE_OUT, ONSET_OUT, CONTOUR_OUT)).use { res ->
                    val note = (res.get(NOTE_OUT).get() as OnnxTensor).floatBuffer
                    val onset = (res.get(ONSET_OUT).get() as OnnxTensor).floatBuffer
                    val contour = (res.get(CONTOUR_OUT).get() as OnnxTensor).floatBuffer
                    for (b in 0 until batch) {
                        // Drop half the overlapping frames at each side of every window.
                        for (f in olap until ANNOT_N_FRAMES - olap) {
                            if (frames.size >= nOut) break
                            val base = (b * ANNOT_N_FRAMES + f) * N_KEYS
                            frames += FloatArray(N_KEYS) { note.get(base + it) }
                            onsets += FloatArray(N_KEYS) { onset.get(base + it) }
                            val cBase = (b * ANNOT_N_FRAMES + f) * N_CONTOUR_BINS
                            contours += ByteArray(N_CONTOUR_BINS) { quantize(contour.get(cBase + it)) }
                        }
                    }
                }
            }
            w += batch
            progress(w.toFloat() / windows)
        }
        return Activations(frames.toTypedArray(), onsets.toTypedArray(), contours.toTypedArray())
    }

    companion object {
        const val SAMPLE_RATE = 22050
        const val FFT_HOP = 256
        const val AUDIO_N_SAMPLES = SAMPLE_RATE * 2 - FFT_HOP // 43844
        const val ANNOTATIONS_FPS = SAMPLE_RATE / FFT_HOP     // 86
        const val ANNOT_N_FRAMES = ANNOTATIONS_FPS * 2        // 172
        const val N_KEYS = 88
        const val MIDI_OFFSET = 21
        const val N_CONTOUR_BINS = 264
        private const val OVERLAP_FRAMES = 30
        private const val BATCH = 4
        private const val INPUT = "serving_default_input_2:0"
        private const val NOTE_OUT = "StatefulPartitionedCall:1"
        private const val ONSET_OUT = "StatefulPartitionedCall:2"
        private const val CONTOUR_OUT = "StatefulPartitionedCall:0"

        /** A contour activation 0..1 as the byte 0..255 (rounded). */
        fun quantize(v: Float): Byte = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

        /** Output frames kept from each model window (half the overlap is dropped at each side). */
        private const val KEPT_FRAMES = ANNOT_N_FRAMES - OVERLAP_FRAMES

        /**
         * The model's onsets come out a little early for plucked strings: on synthesized bouzouki
         * lines the onset peaks sit 6-7 ms before the pick, so note starts are moved this much later.
         */
        const val ONSET_LATENCY = 0.007

        /**
         * Seconds at model frame [frame], exactly where [activations] cut it from: window w = frame / 142
         * starts w·hop samples into the song (basic_pitch's model_frames_to_time approximates this with
         * a 172-frame window and a fudge term, which drifts up to ±8 ms within every 2 s window).
         */
        fun frameTime(frame: Int): Double {
            val w = Math.floorDiv(frame, KEPT_FRAMES)
            val hop = AUDIO_N_SAMPLES - OVERLAP_FRAMES * FFT_HOP
            return (w.toDouble() * hop + (frame - w * KEPT_FRAMES).toDouble() * FFT_HOP) / SAMPLE_RATE
        }

        /**
         * When a note starting at model frame [frame] on [key] begins: the onset activation peak, refined
         * between frames with a parabola through its neighbours, and corrected for [ONSET_LATENCY].
         */
        fun onsetTime(act: Activations, frame: Int, key: Int): Double {
            val o = act.onsets
            var d = 0.0
            if (frame > 0 && frame < o.size - 1) {
                val a = o[frame - 1][key].toDouble(); val b = o[frame][key].toDouble(); val c = o[frame + 1][key].toDouble()
                val den = a - 2 * b + c
                if (den < -1e-6) d = (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
            }
            return frameTime(frame) + d * FFT_HOP / SAMPLE_RATE + ONSET_LATENCY
        }

        /**
         * Turns activations into notes (output_to_notes_polyphonic with infer_onsets; by default with the
         * "melodia trick", onset 0.5, frame 0.3, minimum length 11 frames ≈ 128 ms, like basic_pitch).
         * Note starts are refined with [onsetTime]; ends are frame times.
         */
        fun notes(
            act: Activations,
            onsetThresh: Float = 0.5f,
            frameThresh: Float = 0.3f,
            minNoteLen: Int = 11,
            energyTol: Int = 11,
            melodia: Boolean = true,
        ): List<NoteEvent> {
            val frames = act.frames
            val nFrames = frames.size
            if (nFrames < 3) return emptyList()
            val onsets = inferOnsets(act.onsets, frames)
            val remaining = Array(nFrames) { frames[it].copyOf() }
            val events = ArrayList<IntArray>()      // start, end, key, 1 if it starts at an onset peak

            // Onset peaks (local maxima in time) above the threshold, latest first. The first frame
            // counts too (it has no frame before it), or audio that starts on a pluck loses that note.
            // A flat top counts once, at its first frame: activations saved in steps (AnalysisData) often
            // tie at a peak, which would otherwise lose the note.
            val peaks = ArrayList<Long>()
            for (t in 0 until nFrames - 1) for (k in 0 until N_KEYS) {
                val v = onsets[t][k]
                if (v < onsetThresh || (t > 0 && v <= onsets[t - 1][k])) continue
                var next = t + 1
                while (next < nFrames && onsets[next][k] == v) next++
                if (next == nFrames || v > onsets[next][k]) peaks += t.toLong() * N_KEYS + k
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
                events += intArrayOf(start, i, k, 1)
            }

            if (melodia) melodiaTrick(remaining, events, frameThresh, minNoteLen, energyTol)

            return events.map { (s, e, k, fromOnset) ->
                var sal = 0f
                for (t in s until minOf(s + SALIENCE_FRAMES, e + 1, nFrames)) sal = max(sal, frames[t][k])
                NoteEvent(
                    start = if (fromOnset == 1) onsetTime(act, s, k) else frameTime(s),
                    end = frameTime(e),
                    midi = k + MIDI_OFFSET,
                    amplitude = mean(frames, s, e, k),
                    onset = if (fromOnset == 1) onsets[s][k].toDouble() else 0.0,
                    salience = sal.toDouble(),
                    frame = s,
                    endFrame = e,
                )
            }.sortedBy { it.start }
        }

        /** Frames at a note's start where its salience (strongest activation) is looked for. */
        private const val SALIENCE_FRAMES = 4

        private fun melodiaTrick(remaining: Array<FloatArray>, events: MutableList<IntArray>, frameThresh: Float, minNoteLen: Int, energyTol: Int) {
            val nFrames = remaining.size
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
                events += intArrayOf(start, end, k, 0)
            }
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
