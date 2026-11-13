package com.mixdeck.engine

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A musical key: a tonic pitch class (0 = C, 1 = C♯/D♭, … 11 = B) and major or minor.
 *
 * DJs usually talk about keys with the Camelot wheel: 12 numbered positions arranged in fifths,
 * "B" for major and "A" for minor. C major is 8B and its relative minor, A minor, is 8A. Moving one
 * number around the wheel (8A → 9A) or switching letter at the same number (8A ↔ 8B) gives keys
 * that share almost all their notes, so they mix smoothly.
 */
data class MusicalKey(val tonic: Int, val minor: Boolean) {
    init { require(tonic in 0..11) { "tonic must be 0..11" } }

    /** Camelot wheel number, 1..12. */
    val camelotNumber: Int get() = majorNumber(if (minor) (tonic + 3) % 12 else tonic)

    /** 'A' for minor keys, 'B' for major keys. */
    val camelotLetter: Char get() = if (minor) 'A' else 'B'

    /** e.g. "8A". */
    val camelot: String get() = "$camelotNumber$camelotLetter"

    /** e.g. "A minor", "F♯ major", "B♭ minor". */
    val name: String get() = "${NAMES[tonic]} ${if (minor) "minor" else "major"}"

    /** Index 0..23: tonic for major keys, 12 + tonic for minor keys. */
    val index: Int get() = tonic + if (minor) 12 else 0

    /** The key moved by [semitones] (positive = up). */
    fun transpose(semitones: Int): MusicalKey = MusicalKey(Math.floorMod(tonic + semitones, 12), minor)

    /** The relative major/minor (same notes, same Camelot number, other letter). */
    val relative: MusicalKey get() = if (minor) MusicalKey((tonic + 3) % 12, false) else MusicalKey((tonic + 9) % 12, true)

    override fun toString() = "$camelot ($name)"

    companion object {
        /** Flats for the keys DJs usually write with flats, sharps otherwise. */
        val NAMES = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

        private fun majorNumber(majorTonic: Int): Int = (majorTonic * 7 % 12 + 7) % 12 + 1

        fun fromIndex(i: Int) = MusicalKey(i % 12, i >= 12)

        /** Parses "8A", "12B", … */
        fun fromCamelot(s: String): MusicalKey? {
            val m = Regex("""^\s*(\d{1,2})\s*([AaBb])\s*$""").find(s) ?: return null
            val n = m.groupValues[1].toInt()
            if (n !in 1..12) return null
            val minor = m.groupValues[2].uppercase() == "A"
            val majorTonic = (0..11).first { majorNumber(it) == n }
            return if (minor) MusicalKey((majorTonic + 9) % 12, true) else MusicalKey(majorTonic, false)
        }

        /**
         * How many semitones the pitch moves when a deck plays at [speed] (1.0 = normal) with no
         * key lock, as on a vinyl turntable. +6% ≈ +1 semitone.
         */
        fun semitonesForSpeed(speed: Double): Double = 12 * ln(speed) / ln(2.0)

        /** The nearest whole number of semitones, if [semitones] is within [tolerance] of it. */
        fun wholeSemitones(semitones: Double, tolerance: Double = 0.25): Int? {
            val r = semitones.roundToInt()
            return if (kotlin.math.abs(semitones - r) <= tolerance) r else null
        }
    }
}

/**
 * What the app knows about a song for choosing what to mix next. Unknown values: bpm = 0,
 * key = null, energy = NaN, timbre = null.
 */
data class TrackProfile(
    val id: String,
    val title: String,
    val artist: String,
    /** Tempo at normal speed. 0 = unknown / no steady beat. */
    val bpm: Double,
    val key: MusicalKey?,
    /** 0..1: how clearly the music sits in [key]. Low for drum tracks, atonal or key-changing songs. */
    val keyConfidence: Float,
    /** 1..10, like DJ software: 1 = calm/ambient, 10 = peak-time intensity. NaN = unknown. */
    val energy: Float,
    /** A small feature vector describing the sound (for "sounds similar"), or null. */
    val timbre: FloatArray?,
    val durationSec: Double,
) {
    override fun equals(other: Any?) = other is TrackProfile && other.id == id
    override fun hashCode() = id.hashCode()
}
