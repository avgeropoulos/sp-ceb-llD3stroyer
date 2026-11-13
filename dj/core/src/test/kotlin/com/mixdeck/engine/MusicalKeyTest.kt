package com.mixdeck.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class MusicalKeyTest {
    @Test fun camelotWheelMatchesTheStandardChart() {
        val chart = mapOf(
            "C major" to "8B", "G major" to "9B", "D major" to "10B", "A major" to "11B", "E major" to "12B", "B major" to "1B",
            "F♯ major" to "2B", "C♯ major" to "3B", "A♭ major" to "4B", "E♭ major" to "5B", "B♭ major" to "6B", "F major" to "7B",
            "A minor" to "8A", "E minor" to "9A", "B minor" to "10A", "F♯ minor" to "11A", "C♯ minor" to "12A", "A♭ minor" to "1A",
            "E♭ minor" to "2A", "B♭ minor" to "3A", "F minor" to "4A", "C minor" to "5A", "G minor" to "6A", "D minor" to "7A",
        )
        for (i in 0 until 24) {
            val k = MusicalKey.fromIndex(i)
            assertEquals(chart[k.name], k.camelot, k.name)
            assertEquals(k, MusicalKey.fromCamelot(k.camelot))
        }
    }

    @Test fun relativeKeysShareTheCamelotNumber() {
        for (i in 0 until 24) {
            val k = MusicalKey.fromIndex(i)
            assertEquals(k.camelotNumber, k.relative.camelotNumber)
            assertEquals(k, k.relative.relative)
        }
    }

    @Test fun speedToSemitones() {
        assertEquals(1.0, MusicalKey.semitonesForSpeed(1.0594630943592953), 1e-9)
        assertEquals(0.0, MusicalKey.semitonesForSpeed(1.0), 1e-12)
        assertEquals(1, MusicalKey.wholeSemitones(MusicalKey.semitonesForSpeed(1.06)))
        assertEquals(null, MusicalKey.wholeSemitones(0.5))
    }
}
