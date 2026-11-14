package com.mixdeck.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The guide is written for the device it's read on. */
class GuideTest {
    private fun text(computer: Boolean) =
        (Guide.sections(computer).flatMap { s -> listOf(s.title) + s.items.flatMap { listOf(it.chip, it.text) } } +
            Guide.firstMix(computer)).joinToString("\n")

    @Test fun theComputerGuideTalksAboutAComputer() {
        val t = text(computer = true)
        assertFalse("Hold one to delete" in t)
        assertFalse(Guide.sections(true).any { s -> s.items.any { it.chip == "Share" } }, "no Share item")
        // "headphones" is fine; a phone isn't.
        assertFalse(Regex("(?<!head)phone", RegexOption.IGNORE_CASE).containsMatchIn(t), "no phone in:\n" +
            t.lines().filter { Regex("(?<!head)phone", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        assertFalse(Regex("\\btap", RegexOption.IGNORE_CASE).containsMatchIn(t), "click, not tap")
        assertTrue("Select one and click Delete to remove it." in t)
        assertTrue(Guide.sections(true).any { s -> s.items.any { it.chip == "Files" } })
    }

    @Test fun thePhoneGuideKeepsSharing() {
        val items = Guide.sections(false).flatMap { it.items }
        assertTrue(items.any { it.chip == "Share" })
        assertTrue(items.any { it.chip == "Phone files" })
        assertTrue(items.first { it.chip == "My songs" }.text.endsWith("Hold one to delete it."))
    }

    @Test fun updateAndMenuItems() {
        fun item(computer: Boolean, chip: String) = Guide.sections(computer).flatMap { it.items }.first { it.chip == chip }.text
        // The notification only comes with notifications on: otherwise you open MixDeck again yourself.
        assertTrue(item(false, "⬆ Update").contains("open it again when it's done (with notifications on, a notification brings you back)"))
        assertTrue(item(true, "⬆ Update").contains("Restart now or When I quit"))
        assertTrue(item(true, "⬆ Update").endsWith("When I quit puts the update in as MixDeck closes."), "only Restart now opens it again")
        assertTrue(item(false, "NEXT").contains("about a minute plus the MIX time"), "NEXT blinks a minute plus the MIX early")
        assertEquals("My mixes, Settings (volumes, crossfader curve, Quantize, MIX length, split cue), MIDI controller setup, this guide, the tutorial, and updates.",
            item(true, "☰"))
        assertEquals(item(true, "☰"), item(false, "☰"))
        assertEquals("Labels every control and shows its keyboard key. Press H or Esc, or click anywhere, to close.", item(true, "?"))
        assertEquals("Drag up or down, or scroll the mouse wheel over them. Double-click to reset.", item(true, "Knobs"))
        assertTrue(item(false, "REC").startsWith("Starts recording everything you hear. Hold REC to stop"))
    }

    @Test fun firstMixSaysClickOnAComputer() {
        assertTrue(Guide.firstMix(false)[0].startsWith("Tap the left LOAD"))
        assertTrue(Guide.firstMix(true)[0].startsWith("Click the left LOAD"))
        assertEquals(Guide.firstMix(false).size, Guide.firstMix(true).size)
    }
}
