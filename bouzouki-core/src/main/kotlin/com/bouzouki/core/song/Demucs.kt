package com.bouzouki.core.song

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.bouzouki.core.song.DemucsSeparator.Companion.BINS
import com.bouzouki.core.song.DemucsSeparator.Companion.HOP
import com.bouzouki.core.song.DemucsSeparator.Companion.N_FFT
import com.bouzouki.core.song.DemucsSeparator.Companion.PAD
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Separates a song into stems with Demucs v4 (htdemucs_6s, MIT licence, from Meta's demucs):
 * drums, bass, other, vocals, guitar and piano. The bouzouki usually lands in "guitar", partly
 * in "other", so by default those two are summed. The 4-stem htdemucs works too ("other" only).
 *
 * The ONNX graph (made by tools/demucs/export_onnx.py) is the network alone, for one segment
 * (7.8 s, the training length, or a shorter one: the length is read from the graph): it takes the mix and its spectrogram and returns the time branch's stems and the
 * spectral branch's complex spectrograms. Everything around it is here and matches demucs:
 * the track normalisation of demucs.api, the overlapping segments with triangular weights of
 * demucs.apply.apply_model (overlap 0.25, no shifts), HTDemucs._spec and HTDemucs._ispec.
 *
 * One 7.8 s segment needs a lot of memory in ONNX Runtime (about 2.5 GB at peak on desktop x86
 * without its memory arena, twice that with it), so open the session with [sessionOptions], and
 * on a phone prefer a short-segment export ([MODEL_6S_SEG39], [MODEL_6S_SEG20]).
 *
 * As a [Separator] it gives the [defaultStems] (or [mixStems]) summed, in mono.
 */
class DemucsSeparator(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    mixStems: Collection<String>? = null,
) : Separator {
    /** Stem names in the model's output order, read from the graph's output shape. */
    val sources: List<String> = when (val s = (session.outputInfo["time"]?.info as? TensorInfo)?.shape?.get(1)) {
        6L -> SOURCES_6S
        4L -> SOURCES_4S
        else -> error("not a Demucs export (time output has $s sources)")
    }

    /** The [DEFAULT_STEMS] this model has: guitar and other for htdemucs_6s, other for htdemucs. */
    val defaultStems: List<String> = DEFAULT_STEMS.filter { it in sources }

    /** What [instrumentalMono] gives: the stems asked for, or else the [defaultStems]. */
    val mixStems: List<String> = mixStems?.distinct()?.also { s -> require(s.all { it in sources }) { "stems $s not all in $sources" } } ?: defaultStems

    /** Samples per segment, from the graph's mix input: [SEGMENT] for the full-length export. */
    val segment: Int = ((session.inputInfo["mix"]?.info as? TensorInfo)?.shape?.get(2) ?: SEGMENT.toLong()).toInt()

    /** Spectrogram frames per segment. */
    val frames: Int = (segment + HOP - 1) / HOP

    override val keepsBass: Boolean get() = "bass" in mixStems

    override fun instrumentalMono(left: FloatArray, right: FloatArray, progress: (Float) -> Unit, cancelled: () -> Boolean): FloatArray =
        stemMono(left, right, mixStems, progress, cancelled)

    /** How long one segment took: [dspMillis] for STFT, iSTFT and mixing, [modelMillis] in ONNX Runtime. */
    data class SegmentTiming(val index: Int, val count: Int, val dspMillis: Long, val modelMillis: Long)

    private val stft = DemucsStft(segment)
    // apply_model's transition weights: a triangle rising to 1 in the middle of the segment.
    private val weight = FloatArray(segment) { (if (it < segment / 2) it + 1 else segment - it).toFloat() / (segment - segment / 2) }

    /**
     * The chosen [stems] of a 44.1 kHz stereo song summed and mixed down to mono.
     * [progress] gets 0..1; [cancelled] is polled between segments; [timing] hears about each segment.
     */
    fun stemMono(
        left: FloatArray, right: FloatArray, stems: Collection<String> = defaultStems,
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
        left: FloatArray, right: FloatArray, stems: Collection<String> = defaultStems,
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

        val stride = (0.75 * segment).toInt()
        val count = (n + stride - 1) / stride
        val total = FloatArray(n)
        val mix = FloatArray(2 * segment)
        val spec = FloatArray(4 * BINS * frames)
        val outL = DoubleArray(segment)
        val outR = DoubleArray(segment)
        for (c in 0 until count) {
            if (cancelled()) throw InterruptedException()
            val t0 = System.nanoTime()
            // A short last segment is centred in a full one, with the song around it as context.
            val off = c * stride
            val length = minOf(segment, n - off)
            val lead = (segment - length) / 2
            val start = off - lead
            for (j in 0 until segment) {
                val idx = start + j
                val inside = idx in 0 until n
                mix[j] = if (inside) ((left[idx] - mean) / std).toFloat() else 0f
                mix[segment + j] = if (inside) ((right[idx] - mean) / std).toFloat() else 0f
            }
            stft.spectrogram(mix, spec)
            val t1 = System.nanoTime()
            val (time, zout) = run(mix, spec)
            val t2 = System.nanoTime()
            for ((w, s) in wanted.withIndex()) {
                stft.waveform(zout, s, outL, outR)
                val tb = s * 2 * segment
                for (j in 0 until length) {
                    val src = lead + j
                    val dst = off + j
                    out[w][0][dst] += weight[j] * (time[tb + src] + outL[src].toFloat())
                    out[w][1][dst] += weight[j] * (time[tb + segment + src] + outR[src].toFloat())
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
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mix), longArrayOf(1, 2, segment.toLong())).use { m ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), longArrayOf(1, 4, BINS.toLong(), frames.toLong())).use { z ->
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
        /** Samples per segment of the full-length export: the models' training length, 39/5 s. */
        const val SEGMENT = 343_980
        const val FRAMES = (SEGMENT + HOP - 1) / HOP
        internal const val PAD = HOP / 2 * 3

        /** Session options that keep the peak memory down (no arena, no memory patterns), ~30% slower. */
        fun sessionOptions(threads: Int = 4) = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }

        val SOURCES_6S = listOf("drums", "bass", "other", "vocals", "guitar", "piano")
        val SOURCES_4S = listOf("drums", "bass", "other", "vocals")
        /** Where the bouzouki ends up; [stemMono] sums these unless told otherwise. */
        val DEFAULT_STEMS = listOf("guitar", "other")

        /** The models, built and published by .github/workflows/models.yml. */
        const val RELEASE_URL = "https://github.com/avgeropoulos/sp-ceb-llD3stroyer/releases/download/bouzouki-models-v1/"
        const val MODEL_6S = "htdemucs_6s.onnx"
        const val MODEL_6S_FP16 = "htdemucs_6s_fp16.onnx"
        /** 3.9 s segments: about a third of the peak memory, slightly less context. */
        const val MODEL_6S_SEG39 = "htdemucs_6s_seg39.onnx"
        /** 2.0 s segments: the least memory, the least context. */
        const val MODEL_6S_SEG20 = "htdemucs_6s_seg20.onnx"
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
internal class DemucsStft(private val segment: Int = DemucsSeparator.SEGMENT) {
    private val frames = (segment + HOP - 1) / HOP
    private val fft = Fft(N_FFT)
    private val window = hann(N_FFT)
    private val re = DoubleArray(N_FFT)
    private val im = DoubleArray(N_FFT)
    private val envelope = envelope()

    /**
     * HTDemucs._spec + _magnitude of one segment ([mix]: left then right) into [dst]
     * (4 × BINS × frames: left re, left im, right re, right im). Frame t windows samples
     * [t·1024 − 1536, t·1024 + 2560), reflected at the ends; the STFT is normalised (÷ 64)
     * and the Nyquist bin dropped. Both channels share one complex FFT.
     */
    fun spectrogram(mix: FloatArray, dst: FloatArray) {
        val plane = BINS * frames
        val scale = 1.0 / sqrt(N_FFT.toDouble())
        for (t in 0 until frames) {
            for (j in 0 until N_FFT) {
                var idx = t * HOP - PAD + j
                if (idx < 0) idx = -idx
                if (idx >= segment) idx = 2 * (segment - 1) - idx
                re[j] = mix[idx] * window[j]
                im[j] = mix[segment + idx] * window[j]
            }
            fft.forward(re, im)
            for (k in 0 until BINS) {
                val nk = if (k == 0) 0 else N_FFT - k
                val zr = re[k]; val zi = im[k]; val wr = re[nk]; val wi = im[nk]
                val o = k * frames + t
                dst[o] = ((zr + wr) / 2 * scale).toFloat()
                dst[plane + o] = ((zi - wi) / 2 * scale).toFloat()
                dst[2 * plane + o] = ((zi + wi) / 2 * scale).toFloat()
                dst[3 * plane + o] = (-(zr - wr) / 2 * scale).toFloat()
            }
        }
    }

    /**
     * HTDemucs._mask + _ispec for source [s] of [zout] (S × 4 × BINS × frames) into
     * [outL]/[outR] (one segment): a zero Nyquist bin, the imaginary part of DC ignored as
     * irfft does, overlap-add at t·1024 − 1536 and division by the squared-window sum.
     */
    fun waveform(zout: FloatArray, s: Int, outL: DoubleArray, outR: DoubleArray) {
        outL.fill(0.0); outR.fill(0.0)
        val plane = BINS * frames
        val base = s * 4 * plane
        val scale = sqrt(N_FFT.toDouble())
        for (t in 0 until frames) {
            for (k in 0 until BINS) {
                val o = base + k * frames + t
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
            for (j in maxOf(0, -pos) until minOf(N_FFT, segment - pos)) {
                val w = window[j] * scale
                outL[pos + j] += re[j] * w
                outR[pos + j] += im[j] * w
            }
        }
        for (i in 0 until segment) { outL[i] /= envelope[i]; outR[i] /= envelope[i] }
    }

    /** torch.istft's squared-window sum, over the frame sequence _ispec pads with two empty frames at each end. */
    private fun envelope(): DoubleArray {
        val e = DoubleArray(segment)
        for (f in 0 until frames + 4) {
            val pos = (f - 2) * HOP - PAD
            for (j in maxOf(0, -pos) until minOf(N_FFT, segment - pos)) e[pos + j] += window[j] * window[j]
        }
        return e
    }
}
