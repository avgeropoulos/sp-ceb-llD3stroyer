package com.bouzouki.core.eval

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

/**
 * What a separator lets through of each part of a synthetic mix whose parts are kept apart
 * (`stems/<mix name>.<part>.wav` beside the mix: [SynthSet.write] and [SynthSet.writeMixes] with stems): the
 * separator's output is fitted as a sum of the parts, each with one gain (least squares over the whole recording).
 * A gain of 1 (0 dB) is the part as loud as in the plain mono mix, 0.1 is it 20 dB down. A separator is no fixed
 * gain, so this is the part of each instrument that comes through in phase, and [Passed.other] what the fit leaves
 * (the separator's own artefacts, and the parts changed beyond a gain).
 */
object SeparationCheck {
    val PARTS = listOf("bouzouki", "bass", "strum", "drums", "voice")

    /**
     * For one mix: each part's [gains] in the separator's output; the bouzouki's share of the energy before
     * ([bouzoukiIn], in the mono mix) and after ([bouzoukiOut], in the output); [other], the share of the output's
     * energy that is none of the parts.
     */
    class Passed(val gains: Map<String, Double>, val bouzoukiIn: Double, val bouzoukiOut: Double, val other: Double)

    fun of(item: EvalItem, separated: FloatArray): Passed? {
        val dir = File(item.audio.parentFile, "stems")
        val files = PARTS.map { File(dir, "${item.name}.$it.wav") }
        if (files.any { !it.isFile }) return null
        val parts = files.map { f -> Wav.read(f).let { w -> if (w.channels.size == 1) w.channels[0] else FloatArray(w.channels[0].size) { i -> 0.5f * (w.channels[0][i] + w.channels[1][i]) } } }
        val n = minOf(separated.size, parts.minOf { it.size })
        val k = parts.size
        // Normal equations of out = sum of g[p] * part[p].
        val a = Array(k) { DoubleArray(k + 1) }
        for (p in 0 until k) {
            for (q in p until k) { var s = 0.0; for (i in 0 until n) s += parts[p][i].toDouble() * parts[q][i]; a[p][q] = s; a[q][p] = s }
            var s = 0.0; for (i in 0 until n) s += parts[p][i].toDouble() * separated[i]; a[p][k] = s
        }
        val energy = DoubleArray(k) { a[it][it] }
        for (c in 0 until k) { // Gauss-Jordan with pivoting
            val piv = (c until k).maxByOrNull { abs(a[it][c]) }!!
            val t = a[c]; a[c] = a[piv]; a[piv] = t
            if (abs(a[c][c]) < 1e-12) continue
            for (r in 0 until k) if (r != c) { val f = a[r][c] / a[c][c]; for (j in c..k) a[r][j] -= f * a[c][j] }
        }
        val g = DoubleArray(k) { if (abs(a[it][it]) < 1e-12) 0.0 else a[it][k] / a[it][it] }
        var out = 0.0; var rest = 0.0; var mix = 0.0
        for (i in 0 until n) {
            var fit = 0.0; var all = 0.0
            for (p in 0 until k) { fit += g[p] * parts[p][i]; all += parts[p][i] }
            out += separated[i].toDouble() * separated[i]
            rest += (separated[i] - fit) * (separated[i] - fit)
            mix += all * all
        }
        return Passed(PARTS.indices.associate { PARTS[it] to g[it] }, energy[0] / mix.coerceAtLeast(1e-12),
            g[0] * g[0] * energy[0] / out.coerceAtLeast(1e-12), rest / out.coerceAtLeast(1e-12))
    }

    private fun db(gain: Double) = if (gain <= 1e-4) "under -80" else String.format(Locale.US, "%+.1f", 20 * log10(gain))

    /** The report's table: every mix, and the mean over them (gains averaged in dB). */
    fun markdown(rows: List<Pair<String, Passed>>): String = buildString {
        append("\n## What the separator lets through\n\n")
        append("Each part's level in what the separator gives, against its level in the plain mono mix (dB: 0 is untouched, -20 a tenth), ")
        append("from a least-squares fit of the output as a sum of the mix's parts; the bouzouki's share of the energy before and after; ")
        append("and the share of the output that is none of the parts (artefacts).\n\n")
        append("| | " + PARTS.joinToString(" | ") { "$it dB" } + " | bouzouki share in | bouzouki share out | none of the parts |\n")
        append("|---|" + "---|".repeat(PARTS.size + 3) + "\n")
        fun line(name: String, gains: List<Double>, bIn: Double, bOut: Double, other: Double) =
            append("| $name | " + gains.joinToString(" | ") { db(it) } + String.format(Locale.US, " | %.1f%% | %.1f%% | %.1f%% |\n", 100 * bIn, 100 * bOut, 100 * other))
        if (rows.size > 1) {
            // The mean of the gains in dB (a part taken out entirely counts as -80 dB).
            val mean = PARTS.map { p -> Math.pow(10.0, rows.map { 20 * log10(it.second.gains.getValue(p).coerceAtLeast(1e-4)) }.average() / 20) }
            line("**mean**", mean, rows.map { it.second.bouzoukiIn }.average(), rows.map { it.second.bouzoukiOut }.average(), rows.map { it.second.other }.average())
        }
        for ((name, p) in rows) line(name, PARTS.map { p.gains.getValue(it) }, p.bouzoukiIn, p.bouzoukiOut, p.other)
    }
}
