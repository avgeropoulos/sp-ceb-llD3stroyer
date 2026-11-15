package com.sitarsitara.core

/**
 * Pitches named the way a sitar player reads them: as sargam, relative to Sa.
 *
 * A sitar isn't read from fret numbers. Its frets are movable and named after the swara they sound, and the
 * melody lives almost entirely on one string, so the useful answer to "what do I play?" is the swara and its
 * octave (saptak). Which string a note falls on is said too, since anything below the open main string is
 * played on the lower ones.
 */
enum class Script { LATIN, DEVANAGARI }

/** A swara: [degree] semitones above Sa (0..11) in [octave] (0 madhya, -1 mandra, +1 taar). */
data class Swara(val degree: Int, val octave: Int) {
    val komal get() = degree in KOMAL
    val tivra get() = degree == TIVRA

    /** "Sa", "komal Re", "tivra Ma (taar)". */
    val name: String
        get() = NAMES[degree] + when {
            octave == 0 -> ""
            octave == -1 -> " (mandra)"
            octave == 1 -> " (taar)"
            octave < 0 -> " (ati-mandra)"
            else -> " (ati-taar)"
        }

    /** The bare letter(s), without octave marks: "S", "r", "M" or "सा", "रे", "म". */
    fun letter(script: Script): String = if (script == Script.LATIN) LATIN[degree] else DEVANAGARI[degree].let { it.first + it.second }

    /**
     * As written in text: Latin "S", "r", "M", with ' per octave up and , per octave down ("S'", "N,"); Devanagari
     * with a line below for komal, a stroke above for tivra Ma, a dot above for taar and a dot below for mandra.
     */
    fun text(script: Script = Script.LATIN): String {
        if (script == Script.LATIN) return LATIN[degree] + if (octave > 0) "'".repeat(octave) else ",".repeat(-octave)
        val (consonant, vowel) = DEVANAGARI[degree]
        return buildString {
            append(consonant)
            if (octave < 0) append(NUKTA)
            append(vowel)
            if (komal) append(ANUDATTA) else if (tivra) append(UDATTA)
            if (octave > 0) append(ANUSVARA)
        }
    }

    companion object {
        /** Lower case is komal (flat); "M" is tivra (sharp) Ma. */
        val LATIN = listOf("S", "r", "R", "g", "G", "m", "M", "P", "d", "D", "n", "N")
        val NAMES = listOf("Sa", "komal Re", "Re", "komal Ga", "Ga", "Ma", "tivra Ma", "Pa", "komal Dha", "Dha", "komal Ni", "Ni")
        // (consonant, vowel sign): the octave dot below goes on the consonant, before the vowel sign.
        private val DEVANAGARI = listOf(
            "स" to "ा", "र" to "े", "र" to "े", "ग" to "", "ग" to "", "म" to "",
            "म" to "", "प" to "", "ध" to "", "ध" to "", "न" to "ि", "न" to "ि",
        )
        private val KOMAL = setOf(1, 3, 8, 10)
        private const val TIVRA = 6
        private const val ANUDATTA = '॒' // line below: komal
        private const val UDATTA = '॑' // stroke above: tivra
        private const val NUKTA = '़' // dot below: mandra saptak
        private const val ANUSVARA = 'ं' // dot above: taar saptak
    }
}

/** One of the four playing strings of a kharaj-pancham sitar, its open note [open] semitones from madhya Sa. */
enum class SitarString(val label: String, val open: Int) {
    BAJ("baj tar (main string)", -7),
    JOD("jod tar", -12),
    PANCHAM("pancham tar", -17),
    KHARAJ("kharaj tar", -24),
}

object Sargam {
    val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Lowest note: the open kharaj string, two octaves under Sa. */
    const val LOWEST = -24

    /** The top fret is around taar Ga or Ma; pulling the string reaches a fourth or so beyond. */
    const val HIGHEST = 24

    /**
     * The madhya (middle) Sa of a sitar tuned to pitch class [pc] (0 = C). Sitars put Sa around C#4 or D4; for
     * any other key, the Sa between G3 and F#4 keeps the open strings near where the instrument is built to sit.
     */
    fun saMidi(pc: Int): Int {
        val p = Math.floorMod(pc, 12)
        return (if (p >= 7) 48 else 60) + p
    }

    fun swara(midi: Int, sa: Int) = Swara(Math.floorMod(midi - sa, 12), Math.floorDiv(midi - sa, 12))

    /** The string [midi] is played on with Sa at [sa]: the main string whenever it can be. Null below the sitar. */
    fun string(midi: Int, sa: Int): SitarString? = SitarString.entries.firstOrNull { midi - sa >= it.open }

    /** [midi] moved by octaves into the sitar's range; the second value says whether it moved. */
    fun fitToRange(midi: Int, sa: Int): Pair<Int, Boolean> {
        var m = midi
        while (m < sa + LOWEST) m += 12
        while (m > sa + HIGHEST) m -= 12
        return m to (m != midi)
    }

    /** "C#" for pitch class 1. */
    fun noteName(pc: Int) = NOTE_NAMES[Math.floorMod(pc, 12)]
}
