package com.bouzouki.core.song

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import java.security.MessageDigest
import com.bouzouki.core.song.DemucsSeparator.Companion.BINS
import com.bouzouki.core.song.DemucsSeparator.Companion.FRAMES
import com.bouzouki.core.song.DemucsSeparator.Companion.HOP
import com.bouzouki.core.song.DemucsSeparator.Companion.N_FFT
import com.bouzouki.core.song.DemucsSeparator.Companion.PAD
import com.bouzouki.core.song.DemucsSeparator.Companion.SEGMENT
import kotlin.math.sqrt

/**
 * Separates a song into stems with Demucs v4 (htdemucs_6s, MIT licence, from Meta's demucs):
 * drums, bass, other, vocals, guitar and piano. The bouzouki usually lands in "guitar", partly
 * in "other", so by default those two are summed. The 4-stem htdemucs works too ("other" only).
 *
 * The ONNX graph (made by tools/demucs/export_onnx.py) is the network alone, for one segment
 * of 7.8 s: it takes the mix and its spectrogram and returns the time branch's stems and the
 * spectral branch's complex spectrograms. Everything around it is here and matches demucs:
 * the track normalisation of demucs.api, the overlapping segments with triangular weights of
 * demucs.apply.apply_model (overlap 0.25, no shifts), HTDemucs._spec and HTDemucs._ispec.
 */
class DemucsSeparator(private val env: OrtEnvironment, private val session: OrtSession) {
    /** Stem names in the model's output order, read from the graph's output shape. */
    val sources: List<String> = when (val s = (session.outputInfo["time"]?.info as? ai.onnxruntime.TensorInfo)?.shape?.get(1)) {
        6L -> SOURCES_6S
        4L -> SOURCES_4S
        else -> error("not a Demucs export (time output has $s sources)")
    }

    /** How long one segment took: [dspMillis] for STFT, iSTFT and mixing, [modelMillis] in ONNX Runtime. */
    data class SegmentTiming(val index: Int, val count: Int, val dspMillis: Long, val modelMillis: Long)

    private val stft = DemucsStft()
    private val weight = FloatArray(SEGMENT) { (if (it < SEGMENT / 2) it + 1 else SEGMENT - it).toFloat() / (SEGMENT / 2) }

    /**
     * The chosen [stems] of a 44.1 kHz stereo song summed and mixed down to mono.
     * [progress] gets 0..1; [cancelled] is polled between segments; [timing] hears about each segment.
     */
    fun stemMono(
        left: FloatArray, right: FloatArray, stems: Collection<String> = DEFAULT_STEMS,
        progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }, timing: (SegmentTiming) -> Unit = {},
    ): FloatArray {
        val sep = separate(left, right, stems, progress, cancelled, timing)
        val out = FloatArray(left.size)
        for ((l, r) in sep.values) for (i in out.indices) out[i] += (l[i] + r[i]) / 2
        return out
    }

    /**
     * The chosen [stems] of a 44.1 kHz stereo song, each as (left, right).
     * Only the requested stems are kept, so a long song needs memory for those alone.
     */
    fun separate(
        left: FloatArray, right: FloatArray, stems: Collection<String> = DEFAULT_STEMS,
        progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }, timing: (SegmentTiming) -> Unit = {},
    ): Map<String, Array<FloatArray>> {
        val n = left.size
        val wanted = stems.distinct().map { name -> sources.indexOf(name).also { require(it >= 0) { "no stem $name in $sources" } } }
        val out = Array(wanted.size) { Array(2) { FloatArray(n) } }
        if (n == 0) return stems.distinct().zip(out.toList()).toMap()

        // Normalise the whole song like demucs.api.Separator: by the mono mix's mean and (unbiased) deviation.
        var mean = 0.0
        for (i in 0 until n) mean += (left[i] + right[i]) / 2.0
        mean /= n
        var v = 0.0
        for (i in 0 until n) { val d = (left[i] + right[i]) / 2.0 - mean; v += d * d }
        val std = (if (n > 1) sqrt(v / (n - 1)) else 0.0) + 1e-8

        val stride = (0.75 * SEGMENT).toInt()
        val count = (n + stride - 1) / stride
        val total = FloatArray(n)
        val mix = FloatArray(2 * SEGMENT)
        val spec = FloatArray(4 * BINS * FRAMES)
        val outL = DoubleArray(SEGMENT)
        val outR = DoubleArray(SEGMENT)
        for (c in 0 until count) {
            if (cancelled()) throw InterruptedException()
            val t0 = System.nanoTime()
            // A short last segment is centred in a full one, with the song around it as context.
            val off = c * stride
            val length = minOf(SEGMENT, n - off)
            val lead = (SEGMENT - length) / 2
            val start = off - lead
            for (j in 0 until SEGMENT) {
                val idx = start + j
                val inside = idx in 0 until n
                mix[j] = if (inside) ((left[idx] - mean) / std).toFloat() else 0f
                mix[SEGMENT + j] = if (inside) ((right[idx] - mean) / std).toFloat() else 0f
            }
            stft.spectrogram(mix, spec)
            val t1 = System.nanoTime()
            val (time, zout) = run(mix, spec)
            val t2 = System.nanoTime()
            for ((w, s) in wanted.withIndex()) {
                stft.waveform(zout, s, outL, outR)
                val tb = s * 2 * SEGMENT
                for (j in 0 until length) {
                    val src = lead + j
                    val dst = off + j
                    out[w][0][dst] += weight[j] * (time[tb + src] + outL[src].toFloat())
                    out[w][1][dst] += weight[j] * (time[tb + SEGMENT + src] + outR[src].toFloat())
                }
            }
            for (j in 0 until length) total[off + j] += weight[j]
            timing(SegmentTiming(c, count, (t1 - t0 + System.nanoTime() - t2) / 1_000_000, (t2 - t1) / 1_000_000))
            progress((c + 1).toFloat() / count)
        }
        for (stem in out) for (ch in stem) for (i in 0 until n) ch[i] = (ch[i] / total[i] * std + mean).toFloat()
        return stems.distinct().zip(out.toList()).toMap()
    }

    /** One segment through the network: the time-branch stems and the spectral-branch output. */
    private fun run(mix: FloatArray, spec: FloatArray): Pair<FloatArray, FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mix), longArrayOf(1, 2, SEGMENT.toLong())).use { m ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), longArrayOf(1, 4, BINS.toLong(), FRAMES.toLong())).use { z ->
                session.run(mapOf("mix" to m, "spec" to z)).use { res ->
                    fun read(name: String): FloatArray {
                        val buf = (res.get(name).get() as OnnxTensor).floatBuffer
                        return FloatArray(buf.remaining()).also { buf.get(it) }
                    }
                    return read("time") to read("zout")
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 44100
        const val N_FFT = 4096
        const val HOP = 1024
        const val BINS = N_FFT / 2
        /** Samples per segment: the models' training length, 39/5 s. */
        const val SEGMENT = 343_980
        const val FRAMES = (SEGMENT + HOP - 1) / HOP
        internal const val PAD = HOP / 2 * 3

        val SOURCES_6S = listOf("drums", "bass", "other", "vocals", "guitar", "piano")
        val SOURCES_4S = listOf("drums", "bass", "other", "vocals")
        /** Where the bouzouki ends up; [stemMono] sums these unless told otherwise. */
        val DEFAULT_STEMS = listOf("guitar", "other")

        /** The models, built and published by .github/workflows/models.yml. */
        const val RELEASE_URL = "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/bouzouki-models-v1/"
        const val MODEL_6S = "htdemucs_6s.onnx"
        const val MODEL_6S_FP16 = "htdemucs_6s_fp16.onnx"
        /** `sha256sum` output for every file of the release. */
        const val CHECKSUMS = "SHA256SUMS"
        fun url(file: String) = RELEASE_URL + file

        /**
         * Hashes pinned in the app, which win over the release's [CHECKSUMS] file. Fill them in
         * from the first release; until then the downloaded checksum file is all there is.
         */
        val PINNED_SHA256: Map<String, String> = emptyMap()

        /** Parses `sha256sum` output ("<hex>  <name>" per line, optionally "*<name>"). */
        fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[0].length == 64) parts[1].removePrefix("*") to parts[0].lowercase() else null
            }.toMap()

        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) {
                val k = input.read(buf)
                if (k < 0) break
                md.update(buf, 0, k)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(file: File): String = file.inputStream().use { sha256(it) }

        /** Whether [file] (downloaded as [name]) has the pinned hash, or else the one in [checksums]. */
        fun verify(file: File, name: String, checksums: Map<String, String> = emptyMap()): Boolean {
            val expected = PINNED_SHA256[name] ?: checksums[name] ?: return false
            return sha256(file).equals(expected, ignoreCase = true)
        }
    }
}

/** The spectrogram and its inverse exactly as HTDemucs computes them, for one segment. Not thread-safe. */
internal class DemucsStft {
    private val fft = Fft(N_FFT)
    private val window = hann(N_FFT)
    private val re = DoubleArray(N_FFT)
    private val im = DoubleArray(N_FFT)
    private val envelope = envelope()

    /**
     * HTDemucs._spec + _magnitude of one segment ([mix]: left then right) into [dst]
     * (4 × BINS × FRAMES: left re, left im, right re, right im). Frame t windows samples
     * [t·1024 − 1536, t·1024 + 2560), reflected at the ends; the STFT is normalised (÷ 64)
     * and the Nyquist bin dropped. Both channels share one complex FFT.
     */
    fun spectrogram(mix: FloatArray, dst: FloatArray) {
        val plane = BINS * FRAMES
        val scale = 1.0 / sqrt(N_FFT.toDouble())
        for (t in 0 until FRAMES) {
            for (j in 0 until N_FFT) {
                var idx = t * HOP - PAD + j
                if (idx < 0) idx = -idx
                if (idx >= SEGMENT) idx = 2 * (SEGMENT - 1) - idx
                re[j] = mix[idx] * window[j]
                im[j] = mix[SEGMENT + idx] * window[j]
            }
            fft.forward(re, im)
            for (k in 0 until BINS) {
                val nk = if (k == 0) 0 else N_FFT - k
                val zr = re[k]; val zi = im[k]; val wr = re[nk]; val wi = im[nk]
                val o = k * FRAMES + t
                dst[o] = ((zr + wr) / 2 * scale).toFloat()
                dst[plane + o] = ((zi - wi) / 2 * scale).toFloat()
                dst[2 * plane + o] = ((zi + wi) / 2 * scale).toFloat()
                dst[3 * plane + o] = (-(zr - wr) / 2 * scale).toFloat()
            }
        }
    }

    /**
     * HTDemucs._mask + _ispec for source [s] of [zout] (S × 4 × BINS × FRAMES) into
     * [outL]/[outR] (one segment): a zero Nyquist bin, the imaginary part of DC ignored as
     * irfft does, overlap-add at t·1024 − 1536 and division by the squared-window sum.
     */
    fun waveform(zout: FloatArray, s: Int, outL: DoubleArray, outR: DoubleArray) {
        outL.fill(0.0); outR.fill(0.0)
        val plane = BINS * FRAMES
        val base = s * 4 * plane
        val scale = sqrt(N_FFT.toDouble())
        for (t in 0 until FRAMES) {
            for (k in 0 until BINS) {
                val o = base + k * FRAMES + t
                val lr = zout[o].toDouble(); val li = zout[o + plane].toDouble()
                val rr = zout[o + 2 * plane].toDouble(); val ri = zout[o + 3 * plane].toDouble()
                // Z = L + iR, with L and R Hermitian-symmetric (real signals).
                if (k == 0) { re[0] = lr; im[0] = rr; continue }
                re[k] = lr - ri; im[k] = li + rr
                re[N_FFT - k] = lr + ri; im[N_FFT - k] = -li + rr
            }
            re[BINS] = 0.0; im[BINS] = 0.0
            fft.inverse(re, im)
            val pos = t * HOP - PAD
            for (j in maxOf(0, -pos) until minOf(N_FFT, SEGMENT - pos)) {
                val w = window[j] * scale
                outL[pos + j] += re[j] * w
                outR[pos + j] += im[j] * w
            }
        }
        for (i in 0 until SEGMENT) { outL[i] /= envelope[i]; outR[i] /= envelope[i] }
    }

    /** torch.istft's squared-window sum, over the frame sequence _ispec pads with two empty frames at each end. */
    private fun envelope(): DoubleArray {
        val e = DoubleArray(SEGMENT)
        for (f in 0 until FRAMES + 4) {
            val pos = (f - 2) * HOP - PAD
            for (j in maxOf(0, -pos) until minOf(N_FFT, SEGMENT - pos)) e[pos + j] += window[j] * window[j]
        }
        return e
    }
}
