package com.bouzouki.core

/**
 * One course of the bouzouki: a pair of strings plucked together.
 * [octavePair] courses have a thin string tuned an octave above the thick one,
 * the rest are a unison pair.
 */
data class Course(val openMidi: Int, val octavePair: Boolean)

/** The two Greek bouzoukia. Courses are listed from the lowest (bass) to the highest. */
enum class Instrument(val greekName: String, val englishName: String, val courses: List<Course>, val frets: Int) {
    /** Three-course bouzouki in D-A-D, the classic rebetiko instrument. */
    TRICHORDO(
        "Τρίχορδο", "Trichordo · 3 courses · D A D",
        listOf(Course(50, octavePair = true), Course(57, octavePair = false), Course(62, octavePair = false)),
        frets = 26,
    ),

    /** Four-course bouzouki in C-F-A-D, popular since the 1950s. */
    TETRACHORDO(
        "Τετράχορδο", "Tetrachordo · 4 courses · C F A D",
        listOf(
            Course(48, octavePair = true), Course(53, octavePair = true),
            Course(57, octavePair = false), Course(62, octavePair = false),
        ),
        frets = 27,
    );

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
}

/** Equal-tempered frequency of a MIDI note, A4 = 440 Hz. */
fun midiToHz(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
