package com.bouzouki.core.song

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Removes the vocals from a song with an MDX-Net model (UVR-MDX-NET-Inst_HQ_3, from the
 * Ultimate Vocal Remover project). The model works on stereo spectrogram chunks of
 * 3072 frequency bins × 256 frames and returns the instrumental part.
 *
 * The STFT matches torch.stft(center=True, periodic Hann) as the model was trained with.
 * Both channels go through one complex FFT (left as the real part, right as the imaginary).
 */
class MdxSeparator(private val env: OrtEnvironment, private val session: OrtSession) {
    private val fft = Fft(N_FFT)
    private val window = hann(N_FFT)
    private val re = DoubleArray(N_FFT)
    private val im = DoubleArray(N_FFT)

    /**
     * The instrumental part of a 44.1 kHz stereo song, mixed down to mono.
     * [progress] gets 0..1; [cancelled] is polled between chunks.
     */
    fun instrumentalMono(left: FloatArray, right: FloatArray, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): FloatArray {
        val n = left.size
        val trim = N_FFT / 2
        val chunk = HOP * (DIM_T - 1)
        val gen = chunk - 2 * trim
        val chunks = (n + gen - 1) / gen
        val out = FloatArray(n)
        val input = FloatArray(4 * DIM_F * DIM_T)
        val segL = FloatArray(chunk)
        val segR = FloatArray(chunk)
        val outL = DoubleArray(chunk + N_FFT)
        val outR = DoubleArray(chunk + N_FFT)
        val norm = DoubleArray(chunk + N_FFT)
        for (c in 0 until chunks) {
            if (cancelled()) throw InterruptedException()
            // The song, padded with `trim` zeros in front, cut into overlapping chunks.
            val start = c * gen - trim
            for (j in 0 until chunk) {
                val idx = start + j
                segL[j] = if (idx in 0 until n) left[idx] else 0f
                segR[j] = if (idx in 0 until n) right[idx] else 0f
            }
            spectrogram(segL, segR, input)
            val result = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 4, DIM_F.toLong(), DIM_T.toLong())).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { res ->
                    val buf = (res[0] as OnnxTensor).floatBuffer
                    FloatArray(buf.remaining()).also { buf.get(it) }
                }
            }
            waveform(result, outL, outR, norm)
            // Keep the middle of the chunk: samples [c*gen, c*gen + gen) of the song.
            val pad = N_FFT / 2
            for (j in 0 until gen) {
                val dst = c * gen + j
                if (dst >= n) break
                val src = pad + trim + j
                val w = if (norm[src] > 1e-8) norm[src] else 1.0
                out[dst] = (((outL[src] + outR[src]) / 2) / w).toFloat()
            }
            progress((c + 1).toFloat() / chunks)
        }
        return out
    }

    /** Fills [dst] (4 × DIM_F × DIM_T: left re, left im, right re, right im) from one chunk. */
    private fun spectrogram(l: FloatArray, r: FloatArray, dst: FloatArray) {
        val pad = N_FFT / 2
        val len = l.size
        val plane = DIM_F * DIM_T
        for (t in 0 until DIM_T) {
            for (j in 0 until N_FFT) {
                // Reflect padding at both ends, like torch.stft(center=True).
                var idx = t * HOP + j - pad
                if (idx < 0) idx = -idx
                if (idx >= len) idx = 2 * (len - 1) - idx
                re[j] = l[idx] * window[j]
                im[j] = r[idx] * window[j]
            }
            fft.forward(re, im)
            for (k in 0 until DIM_F) {
                val nk = if (k == 0) 0 else N_FFT - k
                val zr = re[k]; val zi = im[k]; val wr = re[nk]; val wi = im[nk]
                // Separate the two real channels from the shared transform.
                val lr = (zr + wr) / 2; val li = (zi - wi) / 2
                val rr = (zi + wi) / 2; val ri = -(zr - wr) / 2
                val o = k * DIM_T + t
                dst[o] = lr.toFloat()
                dst[plane + o] = li.toFloat()
                dst[2 * plane + o] = rr.toFloat()
                dst[3 * plane + o] = ri.toFloat()
            }
        }
    }

    /** Inverse STFT of the model output into [outL]/[outR] with overlap-add; [norm] gets the window sum. */
    private fun waveform(spec: FloatArray, outL: DoubleArray, outR: DoubleArray, norm: DoubleArray) {
        outL.fill(0.0); outR.fill(0.0); norm.fill(0.0)
        val plane = DIM_F * DIM_T
        val half = N_FFT / 2
        for (t in 0 until DIM_T) {
            re.fill(0.0); im.fill(0.0)
            for (k in 0 until DIM_F) {
                val o = k * DIM_T + t
                val lr = spec[o].toDouble(); val li = spec[plane + o].toDouble()
                val rr = spec[2 * plane + o].toDouble(); val ri = spec[3 * plane + o].toDouble()
                // Z = L + iR, with L and R Hermitian-symmetric (real signals).
                re[k] = lr - ri; im[k] = li + rr
                if (k in 1 until half) {
                    re[N_FFT - k] = lr + ri; im[N_FFT - k] = -li + rr
                }
            }
            fft.inverse(re, im)
            val base = t * HOP
            for (j in 0 until N_FFT) {
                val w = window[j]
                outL[base + j] += re[j] * w
                outR[base + j] += im[j] * w
                norm[base + j] += w * w
            }
        }
    }

    companion object {
        const val N_FFT = 6144
        const val HOP = 1024
        const val DIM_F = 3072
        const val DIM_T = 256
        const val SAMPLE_RATE = 44100

        /** Where the app downloads the model from (about 67 MB). */
        const val MODEL_URL = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Inst_HQ_3.onnx"
        const val MODEL_BYTES = 66_759_214L
    }
}
