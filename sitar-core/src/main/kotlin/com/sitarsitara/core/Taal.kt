package com.sitarsitara.core

/**
 * The taals (rhythm cycles) the notation can be laid out in. A taal is a cycle of matras (beats) grouped into
 * vibhags, each marked the way it is clapped: X for sam, the first beat of the cycle; a number for a tali
 * (clap); 0 for a khali (wave).
 */
enum class Taal(val title: String, val vibhags: List<Int>, val marks: List<String>, val hint: String) {
    TEENTAAL("Teentaal", listOf(4, 4, 4, 4), listOf("X", "2", "0", "3"), "most classical gats"),
    KEHERWA("Keherwa", listOf(4, 4), listOf("X", "0"), "most film songs"),
    DADRA("Dadra", listOf(3, 3), listOf("X", "0"), "light songs, ghazals"),
    // Rupak's sam falls on a khali; it is written X here like the others.
    RUPAK("Rupak", listOf(3, 2, 2), listOf("X", "2", "3"), ""),
    JHAPTAAL("Jhaptaal", listOf(2, 3, 2, 3), listOf("X", "2", "0", "3"), ""),
    EKTAAL("Ektaal", listOf(2, 2, 2, 2, 2, 2), listOf("X", "0", "2", "0", "3", "4"), "slow khayal"),
    DEEPCHANDI("Deepchandi", listOf(3, 4, 3, 4), listOf("X", "2", "0", "3"), "thumri"),
    ;

    val matras get() = vibhags.sum()

    /** "Teentaal (16 matras, 4+4+4+4)". */
    val label get() = "$title ($matras matras, ${vibhags.joinToString("+")})"

    companion object {
        val DEFAULT = TEENTAAL
        fun byName(name: String?) = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
