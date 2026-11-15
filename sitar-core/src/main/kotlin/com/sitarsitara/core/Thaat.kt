package com.sitarsitara.core

import kotlin.math.sqrt

/**
 * Sa and the scale family (thaat) guessed from the notes that were played.
 *
 * A raga is more than its scale: its own phrases, its own notes to rest on, its own way up and down. None of
 * that is looked at here. The notes are matched against the ten thaats and a few well-known five-note ragas,
 * and ragas that use the winning scale are named. A starting point for a player, not an identification.
 */
enum class Scale(val title: String, val degrees: List<Int>, val ragas: List<String>) {
    BILAWAL("Bilawal thaat", listOf(0, 2, 4, 5, 7, 9, 11), listOf("Alhaiya Bilawal", "Bihag", "Deshkar")),
    KALYAN("Kalyan thaat", listOf(0, 2, 4, 6, 7, 9, 11), listOf("Yaman", "Shuddha Kalyan", "Hindol")),
    KHAMAJ("Khamaj thaat", listOf(0, 2, 4, 5, 7, 9, 10), listOf("Khamaj", "Desh", "Jhinjhoti", "Tilak Kamod")),
    KAFI("Kafi thaat", listOf(0, 2, 3, 5, 7, 9, 10), listOf("Kafi", "Bageshree", "Bhimpalasi", "Pilu")),
    ASAVARI("Asavari thaat", listOf(0, 2, 3, 5, 7, 8, 10), listOf("Asavari", "Jaunpuri", "Darbari Kanada")),
    BHAIRAVI("Bhairavi thaat", listOf(0, 1, 3, 5, 7, 8, 10), listOf("Bhairavi", "Bilaskhani Todi")),
    BHAIRAV("Bhairav thaat", listOf(0, 1, 4, 5, 7, 8, 11), listOf("Bhairav", "Jogiya", "Kalingda")),
    POORVI("Poorvi thaat", listOf(0, 1, 4, 6, 7, 8, 11), listOf("Poorvi", "Puriya Dhanashree", "Shree")),
    MARWA("Marwa thaat", listOf(0, 1, 4, 6, 7, 9, 11), listOf("Marwa", "Puriya", "Sohini")),
    TODI("Todi thaat", listOf(0, 1, 3, 6, 7, 8, 11), listOf("Miyan ki Todi", "Multani", "Gujari Todi")),
    BHUPALI("Raga Bhupali", listOf(0, 2, 4, 7, 9), listOf("Bhupali", "Deshkar")),
    MALKAUNS("Raga Malkauns", listOf(0, 3, 5, 8, 10), listOf("Malkauns")),
    DURGA("Raga Durga", listOf(0, 2, 5, 7, 9), listOf("Durga")),
    HAMSADHWANI("Raga Hamsadhwani", listOf(0, 2, 4, 7, 11), listOf("Hamsadhwani")),
    SHIVARANJANI("Raga Shivaranjani", listOf(0, 2, 3, 7, 9), listOf("Shivaranjani")),
    MEGH("Raga Megh", listOf(0, 2, 5, 7, 10), listOf("Megh", "Madhmad Sarang")),
    ;

    val isThaat get() = title.endsWith("thaat")
}

/** One guess: Sa on pitch class [saPc], in [scale], with how well it fits ([score], higher is better). */
data class ScaleGuess(val saPc: Int, val scale: Scale, val score: Double) {
    val sa get() = Sargam.noteName(saPc)
}

object Thaat {
    private fun template(degrees: List<Int>) = DoubleArray(12).also { t ->
        for (d in degrees) t[d] = 1.0
        t[0] = 2.0 // Sa is where phrases settle, and what the drone strings ring
        if (7 in degrees) t[7] = 1.3
    }

    /**
     * Ranks (Sa, scale) pairs by how well they fit notes [midis] held [durations] seconds. With [saPc] (the
     * player knows the tuning), only the scale is guessed.
     */
    fun detect(midis: List<Int>, durations: List<Double>, saPc: Int? = null, top: Int = 3): List<ScaleGuess> {
        if (midis.isEmpty()) return emptyList()
        val hist = DoubleArray(12)
        for (i in midis.indices) hist[Math.floorMod(midis[i], 12)] += durations[i].coerceAtLeast(0.0)
        val played = hist.copyOf()
        val playedSum = played.sum()
        // Phrases come home to Sa, so weight the final note.
        hist[Math.floorMod(midis.last(), 12)] += 0.15 * hist.sum()
        if (playedSum <= 0 || std(hist) == 0.0) return emptyList()

        val tonics = if (saPc == null) (0 until 12).toList() else listOf(Math.floorMod(saPc, 12))
        val scores = ArrayList<ScaleGuess>()
        for (scale in Scale.entries) {
            val t = template(scale.degrees)
            for (tonic in tonics) {
                val fit = DoubleArray(12) { t[Math.floorMod(it - tonic, 12)] }
                // A scale has to account for the notes that were played: time spent outside it counts against
                // it. Without this a five-note raga wins whenever a seven-note one leans on the same five.
                val outside = (0 until 12).filter { fit[it] == 0.0 }.sumOf { played[it] } / playedSum
                scores += ScaleGuess(tonic, scale, corr(hist, fit) - outside)
            }
        }
        return scores.sortedWith(compareBy<ScaleGuess>({ -it.score }, { it.saPc }, { it.scale.ordinal })).take(top)
    }

    private fun std(a: DoubleArray): Double {
        val m = a.average()
        return sqrt(a.sumOf { (it - m) * (it - m) } / a.size)
    }

    private fun corr(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            num += (a[i] - ma) * (b[i] - mb)
            da += (a[i] - ma) * (a[i] - ma)
            db += (b[i] - mb) * (b[i] - mb)
        }
        return if (da == 0.0 || db == 0.0) 0.0 else num / sqrt(da * db)
    }
}
