package com.bouzouki.core

/**
 * A Greek dromos (δρόμος, "road"): the scales of rebetiko and laïko.
 * [semitones] are the seven degrees above the root. On the fretted bouzouki the
 * dromoi are played in equal temperament, so some (Ousak and Kiourdi) share the
 * same notes and differ only in how the melody moves.
 */
enum class Dromos(val greekName: String, val englishName: String, val semitones: IntArray) {
    MATZORE("Ματζόρε", "Major", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    MINORE("Μινόρε", "Natural Minor", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    NIHAVENT("Νιαβέντ", "Harmonic Minor", intArrayOf(0, 2, 3, 5, 7, 8, 11)),
    HITZAZ("Χιτζάζ", "Phrygian Dominant", intArrayOf(0, 1, 4, 5, 7, 8, 10)),
    HITZAZKIAR("Χιτζαζκιάρ", "Double Harmonic", intArrayOf(0, 1, 4, 5, 7, 8, 11)),
    OUSAK("Ουσάκ", "Phrygian", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
    KIOURDI("Κιουρντί", "Phrygian", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
    RAST("Ραστ", "Mixolydian", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
    SABAH("Σαμπάχ", "Minor with flat 4th", intArrayOf(0, 2, 3, 4, 7, 8, 10)),
    HOUZAM("Χουζάμ", "Major with sharp 2nd", intArrayOf(0, 3, 4, 5, 7, 9, 11)),
    SEGIAH("Σεγκιά", "Harmonic Major with sharp 2nd", intArrayOf(0, 3, 4, 5, 7, 8, 11)),
    PIREOTIKOS("Πειραιώτικος", "Double Harmonic with sharp 4th", intArrayOf(0, 1, 4, 6, 7, 8, 11)),
    NIKRIZ("Νικρίζ", "Ukrainian Dorian", intArrayOf(0, 2, 3, 6, 7, 9, 10)),
    TSINGANIKOS("Τσιγγάνικος", "Hungarian Minor", intArrayOf(0, 2, 3, 6, 7, 8, 11)),
    KARTSIGAR("Καρσιγάρ", "Dorian with flat 5th", intArrayOf(0, 2, 3, 5, 6, 9, 10));

    /** e.g. "Hitzaz (Phrygian Dominant)". */
    val title: String get() = "${latinName()} ($englishName)"

    fun latinName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

    fun contains(rootPc: Int, midi: Int) = Math.floorMod(midi - rootPc, 12) in semitones

    /** Degree labels like "1 ♭2 3 4 5 ♭6 ♭7". */
    fun degreeLabels(): List<String> = semitones.mapIndexed { i, s ->
        accidental(s - MAJOR[i]) + (i + 1)
    }

    /** Note names of the scale spelled from [rootPc], one letter per degree. */
    fun spell(rootPc: Int, names: NoteNames): List<String> {
        val rootLetter = ROOT_LETTERS[rootPc]
        val rootAcc = rootPc - NATURAL_PC[rootLetter]
        return semitones.mapIndexed { i, s ->
            val letter = (rootLetter + i) % 7
            val acc = wrap(rootAcc + s - (NATURAL_PC[letter] - NATURAL_PC[rootLetter] + 12) % 12)
            if (acc in -1..1) names.letter(letter) + accidental(acc)
            else names.name(rootPc + s) // avoid double sharps and flats
        }
    }

    companion object {
        private val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        private val NATURAL_PC = intArrayOf(0, 2, 4, 5, 7, 9, 11)

        /** Letter (0 = C) used to spell each root pitch class: C C♯ D E♭ E F F♯ G A♭ A B♭ B. */
        private val ROOT_LETTERS = intArrayOf(0, 0, 1, 2, 2, 3, 3, 4, 5, 5, 6, 6)

        private fun wrap(a: Int): Int = ((a + 6) % 12 + 12) % 12 - 6

        fun accidental(a: Int) = when (a) {
            -2 -> "𝄫"; -1 -> "♭"; 1 -> "♯"; 2 -> "𝄪"; else -> ""
        }
    }
}

/** Western letters (C D E) or Greek solfège (Ντο Ρε Μι). */
enum class NoteNames(private val letters: List<String>) {
    WESTERN(listOf("C", "D", "E", "F", "G", "A", "B")),
    GREEK(listOf("Ντο", "Ρε", "Μι", "Φα", "Σολ", "Λα", "Σι"));

    fun letter(index: Int) = letters[index]

    /** Name of a pitch class (or MIDI note), with the same spelling as the root picker. */
    fun name(pcOrMidi: Int): String {
        val pc = Math.floorMod(pcOrMidi, 12)
        val letter = ROOTS[pc]
        return letters[letter] + Dromos.accidental(pc - NATURALS[letter])
    }

    private companion object {
        val ROOTS = intArrayOf(0, 0, 1, 2, 2, 3, 3, 4, 5, 5, 6, 6)
        val NATURALS = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    }
}
