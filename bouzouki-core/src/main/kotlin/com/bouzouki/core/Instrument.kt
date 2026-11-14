package com.bouzouki.core

import java.util.Locale

/**
 * One course of the bouzouki: a pair of strings plucked together.
 * [octavePair] courses have a thin string tuned an octave above the thick one,
 * the rest are a unison pair.
 */
data class Course(val openMidi: Int, val octavePair: Boolean)

/**
 * A bouzouki and its tuning: its courses from the lowest (bass) to the highest, and how many frets it has. The two
 * Greek bouzoukia are [TRICHORDO] and [TETRACHORDO] (the default); any other tuning, any number of courses and up to
 * [MAX_FRETS] frets, is [custom]. [name] identifies it (and, for a custom one, says its tuning: [valueOf] reads it back).
 */
class Instrument private constructor(
    val name: String,
    val greekName: String,
    val englishName: String,
    val courses: List<Course>,
    val frets: Int,
) {
    /** Its place in [entries] (-1 for a custom tuning). */
    val ordinal: Int get() = entries.indexOf(this)

    /** Whether it is one of the two standard bouzoukia. */
    val standard: Boolean get() = ordinal >= 0

    fun midiAt(course: Int, fret: Int) = courses[course].openMidi + fret

    /**
     * The course and fret to play [midi] on, close to the nut, preferring the course whose
     * fret falls nearest to [handFret]. Returns null if the note is out of range.
     */
    fun position(midi: Int, handFret: Int = 0): Pair<Int, Int>? =
        courses.indices
            .map { it to midi - courses[it].openMidi }
            .filter { (_, f) -> f in 0..frets }
            .minByOrNull { (_, f) -> kotlin.math.abs(f - handFret) * 10 + f }

    /** The tuning as courses, low to high: "C3/C4 F3/F4 A3 D4" (an octave pair as both its pitches). */
    val tuning: String get() = courses.joinToString(" ") { c -> pitchName(c.openMidi) + if (c.octavePair) "/" + pitchName(c.openMidi + 12) else "" }

    override fun equals(other: Any?) = other is Instrument && other.name == name
    override fun hashCode() = name.hashCode()
    override fun toString() = name

    companion object {
        const val MAX_FRETS = 30

        /** Three-course bouzouki in D-A-D (D3/D4, A3/A3, D4/D4), the classic rebetiko instrument. */
        val TRICHORDO = Instrument(
            "TRICHORDO", "Τρίχορδο", "Trichordo · 3 courses · D A D",
            listOf(Course(50, octavePair = true), Course(57, octavePair = false), Course(62, octavePair = false)),
            frets = 26,
        )

        /** Four-course bouzouki in C-F-A-D (C3/C4, F3/F4, A3/A3, D4/D4), popular since the 1950s. */
        val TETRACHORDO = Instrument(
            "TETRACHORDO", "Τετράχορδο", "Tetrachordo · 4 courses · C F A D",
            listOf(
                Course(48, octavePair = true), Course(53, octavePair = true),
                Course(57, octavePair = false), Course(62, octavePair = false),
            ),
            frets = 27,
        )

        /** The standard bouzoukia, in the order the app has always listed (and saved) them. */
        val entries: List<Instrument> = listOf(TRICHORDO, TETRACHORDO)

        /**
         * A bouzouki tuned some other way: [courses] low to high (1 to 8 of them, each in MIDI 24..96), [frets] up to
         * [MAX_FRETS]. Its name spells the tuning ("CUSTOM 48+ 53+ 57 62 / 26": "+" an octave pair), so [valueOf] knows it.
         */
        fun custom(courses: List<Course>, frets: Int): Instrument {
            require(courses.size in 1..8) { "a bouzouki has 1 to 8 courses" }
            require(courses.all { it.openMidi in 24..96 }) { "open strings between C1 and C7" }
            require(frets in 1..MAX_FRETS) { "1 to $MAX_FRETS frets" }
            val name = "CUSTOM " + courses.joinToString(" ") { "${it.openMidi}" + if (it.octavePair) "+" else "" } + " / $frets"
            entries.firstOrNull { it.courses == courses && it.frets == frets }?.let { return it }
            val label = courses.joinToString(" ") { c -> pitchName(c.openMidi) + if (c.octavePair) "/" + pitchName(c.openMidi + 12) else "" }
            return Instrument(name, "Κούρδισμα", "Custom · ${courses.size} courses · $label", courses, frets)
        }

        /** The instrument [name] names: a standard one, or a [custom] tuning by its name. */
        fun valueOf(name: String): Instrument {
            entries.firstOrNull { it.name == name }?.let { return it }
            require(name.startsWith("CUSTOM ")) { "No instrument $name" }
            val (spec, frets) = name.removePrefix("CUSTOM ").split("/").map { it.trim() }
            return custom(spec.split(" ").filter { it.isNotBlank() }.map { Course(it.removeSuffix("+").toInt(), it.endsWith("+")) }, frets.toInt())
        }

        /**
         * A tuning as a player writes it, courses low to high separated by spaces, each a note name with its octave
         * ("C3", "F#3", "Bb2") and, for an octave pair, its octave string too ("C3/C4"): "C3/C4 F3/F4 A3 D4". Null if
         * it can't be read.
         */
        fun parseTuning(text: String, frets: Int): Instrument? = try {
            val courses = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { c ->
                val parts = c.split("/")
                val low = midiOf(parts[0]) ?: return null
                val pair = parts.getOrNull(1)?.let { midiOf(it) ?: return null }
                if (pair != null && pair != low + 12 && pair != low) return null
                Course(low, octavePair = pair == low + 12)
            }
            custom(courses, frets)
        } catch (_: IllegalArgumentException) { null }

        private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        /** "C3" for MIDI 48. */
        fun pitchName(midi: Int) = NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)

        /** MIDI of a note name (Latin letters) with its octave ("C3", "F#3", "Bb2"), or null. */
        fun midiOf(s: String): Int? {
            val m = Regex("^([A-Ga-g])([#♯b♭]?)(-?\\d)$").find(s.trim()) ?: return null
            val base = NAMES.indexOf(m.groupValues[1].uppercase(Locale.ROOT))
            val acc = when (m.groupValues[2]) { "#", "♯" -> 1; "b", "♭" -> -1; else -> 0 }
            return (m.groupValues[3].toInt() + 1) * 12 + base + acc
        }
    }
}

/** Equal-tempered frequency of a MIDI note, A4 = 440 Hz. */
fun midiToHz(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
