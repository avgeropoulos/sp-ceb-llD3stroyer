package com.mixdeck.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Musical features of one track, for choosing what to mix next.
 *
 * [key] is null when the track has no clear tonal content (drums only, noise, speech, silence).
 * [keyConfidence] is 0..1: high when the chroma fits one key well, the runner-up key is clearly
 * behind and the key holds for the whole track; below 0.3 for drum tracks, atonal music, near-ties
 * (often the relative major/minor, which shares the Camelot number) and songs that change key.
 * [tuningCents] is the offset of the track's tuning from A = 440 Hz, -50..+50.
 * [energy] is 1..10 like DJ software's energy rating (1 = calm ambient, 10 = peak-time); it does not
 * depend on playback level. NaN when the track is too short or silent.
 * [timbre] is a small normalised description of the sound (see [FeatureExtractor.timbreSimilarity]).
 */
class TrackFeatures(val key: MusicalKey?, val keyConfidence: Float, val tuningCents: Float, val energy: Float, val timbre: FloatArray) {
    override fun toString(): String =
        "TrackFeatures(key=${key ?: "none"}, conf=%.2f, tuning=%+.0fc, energy=%.1f)".format(keyConfidence, tuningCents, energy)
}

/**
 * Key, energy and timbre extraction. Pure Kotlin, no locks and no shared mutable state: [extract]
 * can run on any background thread and several calls can run at once. One pass over the audio
 * (≈0.5 s for a 5-minute 44.1 kHz track on a desktop JVM), memory proportional to the duration
 * (≈4 MB for 5 minutes, plus the caller's PCM).
 *
 * Key: mono, anti-aliased and decimated to ~11 kHz; 4096-point Hann STFT (hop 2048, ≈186 ms);
 * magnitudes mapped to 1/3-semitone bins (triangular log-frequency mapping); tuning estimated from
 * spectral peaks (parabolic interpolation, circular mean of cent offsets, re-estimated on tonal
 * frames) and compensated; per-frame semitone spectrum E2..C7 folded to a unit-length chroma
 * vector and weighted by the frame's spectral peakiness (drum-only and noisy frames count ~0);
 * the track chroma is matched (cosine) against 24 rotated key templates (a blend of Sha'ath's
 * KeyFinder profile and a triad-weighted diatonic template, convolved with a 6-partial harmonic
 * series so the templates look like audio chroma, not like a score).
 *
 * Energy (1..10): peak-to-loudness ratio (dense, limited masters score high), tempo, brightness
 * (share above 4.5 kHz), percussiveness (share of band energy in transients), a percussive steady
 * pulse (onset autocorrelation at the beat period), onset activity and loudness consistency. All
 * are ratios, so the result does not depend on the playback level.
 *
 * Timbre: 9 band-energy shares (dB; 30 Hz..4.5 kHz in octaves, 4.5-9 kHz, above 9 kHz),
 * percussiveness, onsets per beat, stereo width, peak-to-loudness ratio and tonal peakiness, each
 * standardised against typical values.
 */
object FeatureExtractor {
    /** Length of [TrackFeatures.timbre]. */
    const val TIMBRE_SIZE = 14

    // ------------------------------------------------------------------------------ parameters

    private const val TARGET_SR = 11025.0
    private const val N = 4096
    private const val HOP = 2048
    private const val SUB = 3 // log-frequency bins per semitone
    private const val MAP_LO = 38 // MIDI range of the stored log-frequency spectrum (with margin)
    private const val MAP_HI = 98
    private const val SEMI_LO = 40 // semitone spectrum
    private const val SEMI_HI = 96
    private const val CHROMA_LO = 40 // E2: chroma is folded from E2..C7
    private const val CHROMA_HI = 96 // C7
    private const val PEAKY_LO = 48 // C3: peakiness (tonal-frame weight) is measured over C3..C7
    private const val LOCAL = 6 // ± semitones for the local mean in the peakiness measure
    private const val PEAK_LO = 0.15 // frame weight ramps from 0 at this peakiness …
    private const val PEAK_HI = 0.30 // … to 1 here
    private const val SEG_SEC = 8.0
    private const val TUNE_F_LO = 100.0
    private const val TUNE_F_HI = 2000.0
    private const val ENV_RATE = 100 // envelope frames per second
    private val BAND_EDGES = doubleArrayOf(30.0, 80.0, 160.0, 320.0, 640.0, 1280.0, 2560.0, 4500.0)
    private const val HF1 = 4500.0
    private const val HF2 = 9000.0
    private const val NSEMI = SEMI_HI - SEMI_LO + 1
    private const val NSUB = (MAP_HI - MAP_LO + 1) * SUB

    // Key templates: index 0 = tonic. Sha'ath (KeyFinder) profile and a triad-weighted diatonic
    // template (tonic 3, other triad notes 2, scale notes 1; minor uses the natural minor scale
    // plus a weak leading tone), each normalised to mean 1, averaged, then convolved with the
    // pitch classes of partials 1..6 (0, 0, +7, 0, +4, +7) with amplitude 0.5^(h-1).
    private val SHAATH_MAJOR = doubleArrayOf(6.6, 2.0, 3.5, 2.3, 4.6, 4.0, 2.5, 5.2, 2.4, 3.7, 2.3, 3.4)
    private val SHAATH_MINOR = doubleArrayOf(6.5, 2.7, 3.5, 5.4, 2.6, 3.5, 2.5, 5.2, 4.0, 2.7, 4.3, 3.2)
    private val TRIAD_MAJOR = doubleArrayOf(3.0, 0.0, 1.0, 0.0, 2.0, 1.0, 0.0, 2.0, 0.0, 1.0, 0.0, 1.0)
    private val TRIAD_MINOR = doubleArrayOf(3.0, 0.0, 1.0, 2.0, 0.0, 1.0, 0.0, 2.0, 1.0, 0.0, 1.0, 0.5)
    private val TEMPLATES: Array<DoubleArray> = run {
        fun norm(p: DoubleArray): DoubleArray { val m = p.average(); return DoubleArray(12) { p[it] / m } }
        fun harmonics(p: DoubleArray): DoubleArray {
            val pcs = intArrayOf(0, 0, 7, 0, 4, 7)
            val out = DoubleArray(12)
            for (n in 0 until 12) { var a = 1.0; for (h in pcs) { out[(n + h) % 12] += p[n] * a; a *= 0.5 } }
            return out
        }
        val major = harmonics(DoubleArray(12) { (norm(SHAATH_MAJOR)[it] + norm(TRIAD_MAJOR)[it]) / 2 })
        val minor = harmonics(DoubleArray(12) { (norm(SHAATH_MINOR)[it] + norm(TRIAD_MINOR)[it]) / 2 })
        Array(24) { k -> val p = if (k < 12) major else minor; DoubleArray(12) { p[Math.floorMod(it - k % 12, 12)] } }
    }

    // ------------------------------------------------------------------------------ API

    fun extract(pcm: Pcm, bpm: Double, progress: (Float) -> Unit = {}): TrackFeatures = analyze(pcm, bpm, progress).features

    /**
     * Similarity of two timbre vectors, 0..1 (1 = sounds the same). The vectors are already
     * standardised per dimension, so this is a cosine of the two vectors blended with how close
     * they are (two tracks near the "average sound" would otherwise get an arbitrary angle).
     */
    fun timbreSimilarity(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        var ab = 0.0; var aa = 0.0; var bb = 0.0; var d2 = 0.0
        for (i in 0 until n) {
            val x = a[i].toDouble(); val y = b[i].toDouble()
            if (x.isNaN() || y.isNaN()) return 0f
            ab += x * y; aa += x * x; bb += y * y; d2 += (x - y) * (x - y)
        }
        val cosine = if (aa > 1e-12 && bb > 1e-12) ab / sqrt(aa * bb) else 0.0
        val close = exp(-d2 / n / 0.5)
        return (0.5 * (0.5 + 0.5 * cosine) + 0.5 * close).toFloat().coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------------------ analysis

    /** Everything [extract] computes, for tests and tuning. */
    internal class Analysis(
        val features: TrackFeatures,
        val keyScores: DoubleArray,
        val chroma: DoubleArray,
        val tuneR: Double, val tonalFrac: Double, val peakiness: Double, val keyFit: Double, val margin: Double, val segAgreement: Double,
        val energyParts: Map<String, Double>,
        val timbreRaw: DoubleArray?,
    )

    internal fun analyze(pcm: Pcm, bpm: Double, progress: (Float) -> Unit = {}): Analysis {
        val sr = pcm.sampleRate
        val total = pcm.frames
        val d = max(1, (sr / TARGET_SR).roundToInt())
        val fs = sr.toDouble() / d
        val decLen = total / d
        val nFrames = if (decLen < N) 0 else (decLen - N) / HOP + 1

        // ---- per-STFT-frame storage
        val lf = FloatArray(nFrames * NSUB)
        val ftc = DoubleArray(nFrames); val fts = DoubleArray(nFrames); val ftw = DoubleArray(nFrames)
        val bandPow = DoubleArray(BAND_EDGES.size - 1)
        // ---- 100 Hz envelopes
        val hopE = max(1, (sr.toDouble() / ENV_RATE).roundToInt())
        val nEnv = total / hopE
        val eTot = FloatArray(nEnv); val eSide = FloatArray(nEnv); val eHf = FloatArray(nEnv); val eVhf = FloatArray(nEnv)
        val eLow = FloatArray(nEnv); val pk = FloatArray(nEnv)

        val fft = Fft(N)
        val map = LogMap(fs)
        val win = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / N) }
        val frame = DoubleArray(N); val power = DoubleArray(N / 2 + 1); val mag = DoubleArray(N / 2 + 1)
        val ring = FloatArray(N)
        var ringPos = 0; var decCount = 0L; var nextFrameAt = N.toLong(); var fi = 0
        val binHz = fs / N
        val kT0 = max(3, (TUNE_F_LO / binHz).toInt()); val kT1 = min(N / 2 - 3, (TUNE_F_HI / binHz).toInt())
        val bandK = IntArray(BAND_EDGES.size) { min(N / 2, (BAND_EDGES[it] / binHz).roundToInt()) }

        // Filters: anti-alias (6th-order Butterworth at 0.4·fs), high bands at the full rate, bass at fs.
        val aa1 = Bq.lowPass(sr.toDouble(), 0.4 * fs, 0.5176); val aa2 = Bq.lowPass(sr.toDouble(), 0.4 * fs, 0.7071); val aa3 = Bq.lowPass(sr.toDouble(), 0.4 * fs, 1.9319)
        val hasHf = sr > 2.4 * HF1; val hasVhf = sr > 2.4 * HF2
        val hp1 = Bq.highPass(sr.toDouble(), if (hasHf) HF1 else sr * 0.4, 0.7071)
        val hp2 = Bq.highPass(sr.toDouble(), if (hasVhf) HF2 else sr * 0.45, 0.7071)
        val lowLp = Bq.lowPass(fs, 150.0, 0.7071)

        var aT = 0.0; var aS = 0.0; var aH = 0.0; var aV = 0.0; var aL = 0.0; var aP = 0.0
        var h = 0; var inHop = 0; var decPhase = 0
        val progressStep = max(1, total / 50)
        var progressLeft = progressStep
        for (i in 0 until total) {
            val l = pcm.left(i).toDouble(); val r = pcm.right(i).toDouble()
            val m = 0.5 * (l + r); val s = 0.5 * (l - r)
            aT += m * m; aS += s * s
            if (hasHf) { val y = hp1.run(m); aH += y * y }
            if (hasVhf) { val y = hp2.run(m); aV += y * y }
            val am = abs(m); if (am > aP) aP = am
            val x = if (d > 1) aa3.run(aa2.run(aa1.run(m))) else m
            if (decPhase == 0) {
                val lo = lowLp.run(x); aL += lo * lo * d
                ring[ringPos] = x.toFloat(); ringPos = (ringPos + 1) and (N - 1)
                decCount++
                if (decCount == nextFrameAt && fi < nFrames) {
                    // Oldest sample is at ringPos.
                    for (k in 0 until N) frame[k] = ring[(ringPos + k) and (N - 1)] * win[k]
                    fft.power(frame, power)
                    for (b in 0 until bandPow.size) { var e = 0.0; for (k in bandK[b] until bandK[b + 1]) e += power[k]; bandPow[b] += e }
                    tuningPeaks(power, kT0, kT1, binHz, fi, ftc, fts, ftw)
                    for (k in 0..N / 2) mag[k] = sqrt(power[k])
                    map.apply(mag, lf, fi * NSUB)
                    fi++
                    nextFrameAt += HOP
                }
            }
            if (++decPhase == d) decPhase = 0
            if (++inHop == hopE) {
                if (h < nEnv) { eTot[h] = aT.toFloat(); eSide[h] = aS.toFloat(); eHf[h] = aH.toFloat(); eVhf[h] = aV.toFloat(); eLow[h] = aL.toFloat(); pk[h] = aP.toFloat() }
                h++; inHop = 0
                aT = 0.0; aS = 0.0; aH = 0.0; aV = 0.0; aL = 0.0; aP = 0.0
            }
            if (--progressLeft == 0) { progressLeft = progressStep; progress(0.9f * i / total) }
        }

        val key = keyFrom(lf, nFrames, ftc, fts, ftw, fs)
        progress(0.95f)
        val env = Envelopes(eTot, eSide, eHf, eVhf, eLow, pk, hasHf, hasVhf, nEnv, hopE, hopE.toDouble() / sr)
        val shape = shapeOf(env, bpm)
        val (energy, parts) = energyOf(shape, bpm)
        val traw = timbreRaw(shape, bandPow, bpm, key.peakiness)
        val timbre = timbreOf(traw)
        progress(1f)
        val features = TrackFeatures(key.key, key.confidence.toFloat(), key.tuning.toFloat(), energy, timbre)
        return Analysis(features, key.scores, key.chroma, key.tuneR, key.tonalFrac, key.peakiness, key.fit, key.margin, key.segAgreement, parts, traw)
    }

    // ------------------------------------------------------------------------------ key

    private class KeyOut(
        val key: MusicalKey?, val confidence: Double, val tuning: Double, val scores: DoubleArray, val chroma: DoubleArray,
        val tuneR: Double, val tonalFrac: Double, val peakiness: Double, val fit: Double, val margin: Double, val segAgreement: Double,
        val framePeaky: DoubleArray,
    )

    /** Accumulates the cent offsets (from equal temperament at A = 440) of the frame's spectral peaks as unit phasors. */
    private fun tuningPeaks(p: DoubleArray, k0: Int, k1: Int, binHz: Double, fi: Int, ftc: DoubleArray, fts: DoubleArray, ftw: DoubleArray) {
        var pmax = 0.0
        for (k in k0..k1) if (p[k] > pmax) pmax = p[k]
        if (pmax <= 0) return
        val thr = pmax * 1e-4
        var c = 0.0; var s = 0.0; var w = 0.0
        for (k in k0..k1) {
            val v = p[k]
            if (v > thr && v > p[k - 1] && v >= p[k + 1] && v > p[k - 2] && v >= p[k + 2]) {
                val a = ln(p[k - 1] + 1e-30); val b = ln(v + 1e-30); val cc = ln(p[k + 1] + 1e-30)
                val den = a - 2 * b + cc
                val off = if (den < 0) 0.5 * (a - cc) / den else 0.0
                val cents = 1200 * ln((k + off) * binHz / 440.0) / LN2
                val ang = 2 * PI * (cents - 100 * Math.rint(cents / 100)) / 100
                val mg = sqrt(v)
                c += mg * cos(ang); s += mg * sin(ang); w += mg
            }
        }
        ftc[fi] = c; fts[fi] = s; ftw[fi] = w
    }

    /** Semitone spectrum (MIDI SEMI_LO..SEMI_HI) of every frame for a tuning offset, into [out]. */
    private fun semitones(lf: FloatArray, frames: Int, tuneCents: Double, out: FloatArray) {
        // Each semitone is a triangular kernel (half-width SUB sub-bins) around its tuned centre.
        val idx = IntArray(NSEMI * (2 * SUB + 1)); val w = FloatArray(NSEMI * (2 * SUB + 1)); val cnt = IntArray(NSEMI)
        for (m in 0 until NSEMI) {
            val c = (m + SEMI_LO - MAP_LO) * SUB + (SUB - 1) / 2.0 + tuneCents * SUB / 100
            var q = 0
            var j = kotlin.math.ceil(c - SUB).toInt()
            while (j <= kotlin.math.floor(c + SUB).toInt()) {
                val wt = 1 - abs(j - c) / SUB
                if (wt > 0 && j in 0 until NSUB) { idx[m * (2 * SUB + 1) + q] = j; w[m * (2 * SUB + 1) + q] = wt.toFloat(); q++ }
                j++
            }
            cnt[m] = q
        }
        for (t in 0 until frames) {
            val off = t * NSUB
            for (m in 0 until NSEMI) {
                var s = 0f
                val base = m * (2 * SUB + 1)
                for (q in 0 until cnt[m]) s += w[base + q] * lf[off + idx[base + q]]
                out[t * NSEMI + m] = s
            }
        }
    }

    /** Share of each frame's C3..C7 energy that stands above its ±6-semitone neighbourhood (tonal ≈ 0.3+, noise ≈ 0.1). */
    private fun peakiness(semi: FloatArray, frames: Int, out: DoubleArray, totals: DoubleArray) {
        var raw = 0.0; var white = 0.0
        for (t in 0 until frames) {
            val off = t * NSEMI
            // running window sums
            var fr = 0.0; var fw = 0.0
            for (m in PEAKY_LO - SEMI_LO..CHROMA_HI - SEMI_LO) {
                val a = max(0, m - LOCAL); val b = min(NSEMI - 1, m + LOCAL)
                var sum = 0.0
                for (q in a..b) sum += semi[off + q]
                val v = semi[off + m].toDouble()
                fr += v; fw += max(0.0, v - sum / (b - a + 1))
            }
            out[t] = if (fr > 0) fw / fr else 0.0
            raw += fr; white += fw
        }
        totals[0] = raw; totals[1] = white
    }

    private fun weightOf(peaky: Double) = ((peaky - PEAK_LO) / (PEAK_HI - PEAK_LO)).coerceIn(0.0, 1.0)

    private fun keyFrom(lf: FloatArray, frames: Int, ftc: DoubleArray, fts: DoubleArray, ftw: DoubleArray, fs: Double): KeyOut {
        val none = KeyOut(null, 0.0, 0.0, DoubleArray(24), DoubleArray(12), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, DoubleArray(frames))
        if (frames == 0) return none
        // 1. Tuning from all frames' peaks.
        var c = 0.0; var s = 0.0; var w = 0.0
        for (t in 0 until frames) { c += ftc[t]; s += fts[t]; w += ftw[t] }
        if (w <= 0) return none
        val tuneR = sqrt(c * c + s * s) / w
        var tune = atan2(s, c) / (2 * PI) * 100
        val semi = FloatArray(frames * NSEMI)
        val peaky = DoubleArray(frames)
        val totals = DoubleArray(2)
        // 2. Re-estimate the tuning from tonal frames only (drum hits and noise have peaks off the grid).
        semitones(lf, frames, tune, semi)
        peakiness(semi, frames, peaky, totals)
        c = 0.0; s = 0.0; w = 0.0
        for (t in 0 until frames) { val wt = weightOf(peaky[t]); c += wt * ftc[t]; s += wt * fts[t]; w += wt * ftw[t] }
        if (w > 0) {
            tune = atan2(s, c) / (2 * PI) * 100
            semitones(lf, frames, tune, semi)
            peakiness(semi, frames, peaky, totals)
        }
        // 3. Weighted sum of unit-length frame chroma vectors; 8-second segments for a stability check.
        val chroma = DoubleArray(12); val seg = DoubleArray(12); val fc = DoubleArray(12)
        val segFrames = max(1, (SEG_SEC * fs / HOP).toInt())
        val segBest = ArrayList<Int>(); val segW = ArrayList<Double>()
        var inSeg = 0; var segWsum = 0.0; var wsum = 0.0
        for (t in 0 until frames) {
            fc.fill(0.0)
            val off = t * NSEMI
            for (m in CHROMA_LO..CHROMA_HI) fc[m % 12] += semi[off + m - SEMI_LO]
            var nrm = 0.0
            for (v in fc) nrm += v * v
            nrm = sqrt(nrm)
            val wt = weightOf(peaky[t])
            wsum += wt; segWsum += wt
            if (nrm > 0 && wt > 0) for (i in 0 until 12) { val v = wt * fc[i] / nrm; chroma[i] += v; seg[i] += v }
            if (++inSeg == segFrames || t == frames - 1) {
                if (inSeg >= segFrames / 2 && segWsum > 0) { segBest += bestKey(seg); segW += segWsum }
                seg.fill(0.0); inSeg = 0; segWsum = 0.0
            }
        }
        val tonalFrac = wsum / frames
        val peakinessAll = if (totals[0] > 0) totals[1] / totals[0] else 0.0
        if (chroma.sum() <= 0) return KeyOut(null, 0.0, 0.0, DoubleArray(24), chroma, tuneR, tonalFrac, peakinessAll, 0.0, 0.0, 0.0, peaky)
        val scores = DoubleArray(24) { cosine(chroma, TEMPLATES[it]) }
        val best = scores.indices.maxBy { scores[it] }
        var second = -1.0
        for (k in 0 until 24) if (k != best && scores[k] > second) second = scores[k]
        val margin = scores[best] - second
        val fit = pearson(chroma, TEMPLATES[best])
        // No tonal content: peaks off any tuning grid, few tonal frames, flat spectrum, and the
        // chroma does not even look like a key.
        if (tuneR < 0.25 && tonalFrac < 0.4 && peakinessAll < 0.2 && fit < 0.70)
            return KeyOut(null, 0.0, 0.0, scores, chroma, tuneR, tonalFrac, peakinessAll, fit, margin, 0.0, peaky)
        val bestKey = MusicalKey.fromIndex(best)
        var agree = 0.0; var aw = 0.0
        for (i in segBest.indices) { aw += segW[i]; if (MusicalKey.fromIndex(segBest[i]).camelotNumber == bestKey.camelotNumber) agree += segW[i] }
        val segAgreement = if (aw > 0) agree / aw else 0.0
        // Confidence: tonal content × fit to the key template × lead over the runner-up × stability.
        val gate = max((tuneR - 0.2) / 0.2, (tonalFrac - 0.35) / 0.3).coerceIn(0.0, 1.0)
        val fitTerm = ((fit - 0.70) / 0.25).coerceIn(0.0, 1.0)
        val sepTerm = sqrt((margin / 0.05).coerceIn(0.0, 1.0))
        val stab = ((segAgreement - 0.3) / 0.6).coerceIn(0.0, 1.0)
        val conf = gate * fitTerm * sepTerm * (0.1 + 0.9 * stab)
        return KeyOut(bestKey, conf, tune, scores, chroma, tuneR, tonalFrac, peakinessAll, fit, margin, segAgreement, peaky)
    }

    private fun bestKey(chroma: DoubleArray): Int {
        var best = 0; var bs = -2.0
        for (k in 0 until 24) { val v = cosine(chroma, TEMPLATES[k]); if (v > bs) { bs = v; best = k } }
        return best
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until 12) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa <= 0 || bb <= 0) 0.0 else ab / sqrt(aa * bb)
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in 0 until 12) { val x = a[i] - ma; val y = b[i] - mb; ab += x * y; aa += x * x; bb += y * y }
        return if (aa <= 0 || bb <= 0) 0.0 else ab / sqrt(aa * bb)
    }

    // ------------------------------------------------------------------------------ energy & timbre

    private class Envelopes(
        val tot: FloatArray, val side: FloatArray, val hf: FloatArray, val vhf: FloatArray, val low: FloatArray, val peak: FloatArray,
        val hasHf: Boolean, val hasVhf: Boolean, val n: Int, val hopLen: Int, val hopSec: Double,
    )

    private class Shape(val onsetRate: Double, val percussive: Double, val lowShare: Double, val hfShare: Double, val vhfShare: Double,
                        val plrDb: Double, val loudFrac: Double, val width: Double, val pulse: Double)

    private fun shapeOf(env: Envelopes, bpm: Double): Shape? {
        val n = env.n
        if (n < ENV_RATE) return null
        var sumT = 0.0; var sumS = 0.0; var sumH = 0.0; var sumV = 0.0; var sumL = 0.0
        for (i in 0 until n) { sumT += env.tot[i]; sumS += env.side[i]; sumH += env.hf[i]; sumV += env.vhf[i]; sumL += env.low[i] }
        if (sumT <= 1e-12 * n) return null
        // Gate: hops within 40 dB of the loud part (ignores silence at the ends).
        val sorted = env.tot.copyOf(n); sorted.sort()
        val loud = sorted[(n * 0.95).toInt().coerceAtMost(n - 1)].toDouble()
        val gate = loud * 1e-4
        // Bands for onsets and percussiveness, each normalised by its own mean (level independent).
        val low = DoubleArray(n); val mid = DoubleArray(n); val high = DoubleArray(n)
        val mL = max(sumL / n, 1e-20); val mH = max(sumH / n, 1e-20)
        for (i in 0 until n) {
            low[i] = env.low[i] / mL
            high[i] = if (env.hasHf) env.hf[i] / mH else 0.0
            mid[i] = max(0.0, env.tot[i].toDouble() - env.low[i] - env.hf[i])
        }
        val mM = max(mid.average(), 1e-20)
        for (i in 0 until n) mid[i] /= mM
        // Onset strength: rises in smoothed log band energies, compared with the louder of the
        // two frames 20-30 ms earlier (so slow swells, vibrato and beating count for little).
        val onset = DoubleArray(n)
        val bw = doubleArrayOf(1.0, 1.0, 0.7)
        val hist = DoubleArray(4) // last four log values of the current band
        for ((bi, x) in arrayOf(low, mid, high).withIndex()) {
            for (i in 0 until n) {
                val g = ln(1 + 20 * (x[max(0, i - 1)] + 2 * x[i] + x[min(n - 1, i + 1)]) / 4)
                hist[i and 3] = g
                if (i >= 3) onset[i] += bw[bi] * max(0.0, g - max(hist[(i - 2) and 3], hist[(i - 3) and 3]))
            }
        }
        var active = 0
        for (i in 0 until n) if (env.tot[i] > gate) active++
        // Onsets: local maxima (±40 ms) of the onset strength above an absolute rise (≈ +4 dB in a band).
        var onsets = 0
        for (i in 0 until n) {
            val v = onset[i]
            if (v < 0.9) continue
            var isMax = true
            for (q in max(0, i - 4)..min(n - 1, i + 4)) if (onset[q] > v || (onset[q] == v && q < i)) { isMax = false; break }
            if (isMax) onsets++
        }
        val activeSec = max(1.0, active * env.hopSec)
        // Percussiveness: energy above a ±150 ms moving average, per band.
        fun transient(x: DoubleArray): Double {
            val w = 15
            val p = DoubleArray(n + 1)
            for (i in 0 until n) p[i + 1] = p[i] + x[i]
            var above = 0.0; var all = 0.0
            for (i in 0 until n) {
                val a = max(0, i - w); val b = min(n, i + w + 1)
                val avg = (p[b] - p[a]) / (b - a)
                above += max(0.0, x[i] - avg); all += x[i]
            }
            return if (all > 0) above / all else 0.0
        }
        val pLow = transient(low); val pMid = transient(mid); val pHigh = if (env.hasHf) transient(high) else pMid
        val lowShare = sumL / sumT; val hfShare = if (env.hasHf) sumH / sumT else 0.0; val vhfShare = if (env.hasVhf) sumV / sumT else 0.0
        val percussive = (pLow * lowShare + pMid * (1 - lowShare - hfShare).coerceAtLeast(0.0) + pHigh * hfShare) /
            max(1e-9, lowShare + (1 - lowShare - hfShare).coerceAtLeast(0.0) + hfShare)
        // Peak-to-loudness ratio over the active part, and how much of the track is near its loud level.
        val pks = env.peak.copyOf(n); pks.sort()
        val peak = pks[(n * 0.999).toInt().coerceAtMost(n - 1)].toDouble()
        var aSum = 0.0; var aCnt = 0
        for (i in 0 until n) if (env.tot[i] > gate) { aSum += env.tot[i]; aCnt++ }
        val rmsSample = sqrt(aSum / max(1, aCnt) / env.hopLen)
        val plr = if (rmsSample > 0 && peak > 0) 20 * log10(peak / rmsSample) else 0.0
        // Loudness consistency: 1-second windows within 4 dB of the 90th percentile.
        val win = ENV_RATE
        val wins = n / win
        var loudFrac = 0.0
        if (wins >= 2) {
            val we = DoubleArray(wins) { k -> var e = 0.0; for (q in k * win until (k + 1) * win) e += env.tot[q]; e }
            val ws = we.sortedArray()
            val ref = ws[(wins * 0.9).toInt().coerceAtMost(wins - 1)]
            loudFrac = we.count { it > ref * 0.398 }.toDouble() / wins
        }
        val width = sumS / (sumS + sumT)
        return Shape(onsets / activeSec, percussive, lowShare, hfShare, vhfShare, plr, loudFrac, width, pulseClarity(onset, bpm))
    }

    /**
     * How strongly the onset envelope repeats at the beat period (normalised autocorrelation, best
     * of beat / half-beat / two-beat lags within ±2 % of [bpm]; 70..180 BPM searched when unknown).
     */
    private fun pulseClarity(o: DoubleArray, bpm: Double): Double {
        val n = o.size
        if (n < 4 * ENV_RATE) return 0.0
        val mean = o.average()
        var den = 0.0
        for (v in o) den += (v - mean) * (v - mean)
        if (den <= 0) return 0.0
        fun ac(lag: Double): Double {
            val l0 = lag.toInt(); val f = lag - l0
            if (l0 + 1 >= n) return 0.0
            var s = 0.0
            for (i in 0 until n - l0 - 1) s += (o[i] - mean) * ((1 - f) * o[i + l0] + f * o[i + l0 + 1] - mean)
            return s / den
        }
        val beats = if (bpm > 0) doubleArrayOf(bpm) else doubleArrayOf(75.0, 90.0, 105.0, 120.0, 135.0, 150.0, 170.0)
        var best = 0.0
        for (b in beats) {
            val lag = 60.0 * ENV_RATE / b
            val range = if (bpm > 0) 0.02 else 0.07
            for (mult in doubleArrayOf(1.0, 0.5, 2.0)) {
                var q = -range
                while (q <= range + 1e-9) { best = max(best, ac(lag * mult * (1 + q))); q += 0.01 }
            }
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun c01(x: Double) = x.coerceIn(0.0, 1.0)

    private fun energyOf(sh: Shape?, bpm: Double): Pair<Float, Map<String, Double>> {
        if (sh == null) return Float.NaN to emptyMap()
        // Each part is 0..1; none depends on the playback level.
        val density = c01((14.5 - sh.plrDb) / 6)            // limited / compressed masters are dense
        val tempo = if (bpm > 0) c01((bpm - 60) / 80) else 0.0
        val bright = c01((log10(sh.hfShare + 1e-6) + 3.2) / 2.0)
        val perc = c01((sh.percussive - 0.10) / 0.15)
        val activity = c01(sh.onsetRate / 4)
        val pulse = c01(sh.pulse / 0.6)
        val steady = sh.loudFrac
        // A steady pulse only drives the energy when it is percussive (a calm arpeggio is regular too).
        val raw = 0.28 * density + 0.20 * tempo + 0.15 * bright + 0.17 * perc + 0.12 * pulse * perc + 0.05 * activity + 0.03 * steady
        val e = (1 + 9 * raw).coerceIn(1.0, 10.0)
        val parts = linkedMapOf(
            "density" to density, "tempo" to tempo, "bright" to bright, "perc" to perc, "pulse" to pulse, "activity" to activity, "steady" to steady,
            "plrDb" to sh.plrDb, "hfShare" to sh.hfShare, "percussive" to sh.percussive, "onsetRate" to sh.onsetRate, "pulseRaw" to sh.pulse,
            "width" to sh.width, "lowShare" to sh.lowShare, "loudFrac" to sh.loudFrac,
        )
        return e.toFloat() to parts
    }

    /** Raw timbre descriptors before standardisation (see [TIMBRE_MEAN] / [TIMBRE_SCALE]). */
    private fun timbreRaw(sh: Shape?, bandPow: DoubleArray, bpm: Double, peakiness: Double): DoubleArray? {
        if (sh == null) return null
        val raw = DoubleArray(TIMBRE_SIZE)
        var tot = 0.0
        for (v in bandPow) tot += v
        val below = (1 - sh.hfShare).coerceAtLeast(0.0)
        for (b in bandPow.indices) raw[b] = 10 * log10((if (tot > 0) bandPow[b] / tot else 0.0) * below + 1e-6)
        raw[7] = 10 * log10((sh.hfShare - sh.vhfShare).coerceAtLeast(0.0) + 1e-6)
        raw[8] = 10 * log10(sh.vhfShare + 1e-6)
        raw[9] = sh.percussive
        raw[10] = if (bpm > 0) sh.onsetRate * 60 / bpm else sh.onsetRate / 2   // onsets per beat
        raw[11] = sh.width
        raw[12] = sh.plrDb
        raw[13] = peakiness
        return raw
    }

    // Typical value and spread of each raw timbre descriptor (measured over a broad synthetic
    // corpus of 11 styles); the stored vector is (raw - mean) / scale, clipped to ±4.
    private val TIMBRE_MEAN = doubleArrayOf(-8.7, -9.7, -6.6, -8.6, -13.4, -18.9, -24.9, -24.5, -25.0, 0.21, 2.3, 0.08, 11.8, 0.33)
    private val TIMBRE_SCALE = doubleArrayOf(12.0, 8.6, 2.6, 3.4, 3.3, 3.1, 4.5, 4.7, 8.2, 0.046, 0.92, 0.076, 1.57, 0.093)

    private fun timbreOf(raw: DoubleArray?): FloatArray {
        if (raw == null) return FloatArray(0)
        return FloatArray(TIMBRE_SIZE) { ((raw[it] - TIMBRE_MEAN[it]) / TIMBRE_SCALE[it]).coerceIn(-4.0, 4.0).toFloat() }
    }

    // ------------------------------------------------------------------------------ DSP helpers

    private val LN2 = ln(2.0)

    /** Biquad, transposed direct form II, doubles. */
    private class Bq(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var z1 = 0.0; private var z2 = 0.0
        fun run(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
        companion object {
            fun lowPass(sr: Double, f: Double, q: Double): Bq {
                val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
                return Bq((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
            }
            fun highPass(sr: Double, f: Double, q: Double): Bq {
                val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
                return Bq((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
            }
        }
    }

    /** Radix-2 FFT of a real frame (via a half-size complex FFT); writes the power spectrum. */
    private class Fft(val n: Int) {
        private val half = n / 2
        private val cosT = DoubleArray(half / 2) { cos(2 * PI * it / half) }
        private val sinT = DoubleArray(half / 2) { sin(2 * PI * it / half) }
        private val wc = DoubleArray(half + 1) { cos(2 * PI * it / n) }
        private val ws = DoubleArray(half + 1) { sin(2 * PI * it / n) }
        private val rev = IntArray(half)
        private val re = DoubleArray(half); private val im = DoubleArray(half)

        init {
            val bits = Integer.numberOfTrailingZeros(half)
            for (i in 0 until half) rev[i] = Integer.reverse(i) ushr (32 - bits)
        }

        fun power(x: DoubleArray, out: DoubleArray) {
            for (m in 0 until half) { val r = rev[m]; re[r] = x[2 * m]; im[r] = x[2 * m + 1] }
            var len = 2
            while (len <= half) {
                val hl = len / 2; val step = half / len
                var i = 0
                while (i < half) {
                    var k = 0
                    for (j in i until i + hl) {
                        val c = cosT[k]; val s = sinT[k]
                        val ur = re[j + hl]; val ui = im[j + hl]
                        val tr = ur * c + ui * s; val ti = ui * c - ur * s
                        re[j + hl] = re[j] - tr; im[j + hl] = im[j] - ti
                        re[j] += tr; im[j] += ti
                        k += step
                    }
                    i += len
                }
                len *= 2
            }
            for (k in 0..half) {
                val a = re[k % half]; val b = im[k % half]
                val c = re[(half - k) % half]; val d = im[(half - k) % half]
                val xr = 0.5 * ((a + c) + wc[k] * (b + d) - ws[k] * (a - c))
                val xi = 0.5 * ((b - d) - wc[k] * (a - c) - ws[k] * (b + d))
                out[k] = xr * xr + xi * xi
            }
        }
    }

    /** Sparse triangular mapping of FFT magnitudes onto 1/3-semitone bins (MAP_LO..MAP_HI). */
    private class LogMap(fs: Double) {
        val kLo: Int; val kHi: Int
        private val start: IntArray; private val idx: IntArray; private val w: FloatArray

        init {
            val binHz = fs / N
            fun midi(f: Double) = 69 + 12 * ln(f / 440) / LN2
            fun freq(m: Double) = 440 * 2.0.pow((m - 69) / 12)
            kLo = max(1, (freq(MAP_LO - 0.5) / binHz).toInt()); kHi = min(N / 2 - 1, (freq(MAP_HI + 0.5) / binHz).toInt() + 1)
            val st = IntArray(kHi - kLo + 2)
            val ix = ArrayList<Int>(); val ww = ArrayList<Float>()
            for (k in kLo..kHi) {
                st[k - kLo] = ix.size
                val f = k * binHz
                val p = (midi(f) - MAP_LO) * SUB + (SUB - 1) / 2.0
                val hw = max(1.0, (midi(f + binHz / 2) - midi(f - binHz / 2)) * SUB)
                var sum = 0.0
                val from = ix.size
                var j = kotlin.math.ceil(p - hw).toInt()
                while (j <= kotlin.math.floor(p + hw).toInt()) {
                    val wt = 1 - abs(j - p) / hw
                    if (wt > 0 && j in 0 until NSUB) { ix += j; ww += wt.toFloat(); sum += wt }
                    j++
                }
                for (q in from until ix.size) ww[q] = (ww[q] / max(sum, 1e-9)).toFloat()
            }
            st[kHi - kLo + 1] = ix.size
            start = st; idx = ix.toIntArray(); w = ww.toFloatArray()
        }

        fun apply(mag: DoubleArray, out: FloatArray, off: Int) {
            for (k in kLo..kHi) {
                val v = mag[k].toFloat()
                if (v == 0f) continue
                for (q in start[k - kLo] until start[k - kLo + 1]) out[off + idx[q]] += v * w[q]
            }
        }
    }
}
