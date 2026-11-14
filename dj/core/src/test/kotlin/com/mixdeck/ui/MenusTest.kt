package com.mixdeck.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The short ☰ menu, the same on both apps. */
class MenusTest {
    @Test fun withoutUpdateNews() {
        val m = Menus.mainMenu(null, "1.123", computer = false)
        assertEquals(listOf(MenuItem.LEARN, MenuItem.HELP, MenuItem.MIXES, MenuItem.SETTINGS, MenuItem.MIDI, MenuItem.CHECK), m.map { it.first })
        assertEquals(listOf(
            "▶ Learn to mix (guided, about 5 min)",
            "Help & guide",
            "My mixes",
            "Settings…",
            "MIDI controller…",
            "Check for updates (you have 1.123)",
        ), m.map { it.second })
    }

    @Test fun theUpdateRowComesFirstAndReplacesCheck() {
        val m = Menus.mainMenu("⬆ Update to 1.130", "1.123", computer = false)
        assertEquals(listOf(MenuItem.UPDATE, MenuItem.LEARN, MenuItem.HELP, MenuItem.MIXES, MenuItem.SETTINGS, MenuItem.MIDI), m.map { it.first })
        assertEquals("⬆ Update to 1.130", m[0].second)
    }

    @Test fun theComputerShowsF1() {
        val m = Menus.mainMenu(null, "1.123", computer = true)
        assertEquals("Help & guide  (F1)", m.first { it.first == MenuItem.HELP }.second)
        assertEquals(6, m.size)
        assertEquals("MixDeck 1.123", Menus.title("1.123"))
    }

    /** Learn to mix says what it does to what's going on: the decks, a recording, songs on their way. */
    @Test fun tutorialStartSaysWhatStops() {
        assertNull(Menus.tutorialStart(playing = false, recording = false, picksWaiting = false), "nothing to ask")
        assertEquals("This stops both decks and loads Nelly's \"Air Force Ones\" and \"Grillz\".",
            Menus.tutorialStart(playing = true, recording = false, picksWaiting = false))
        assertEquals("This loads Nelly's \"Air Force Ones\" and \"Grillz\" onto the decks. " +
            "Your recording stops and is saved in My mixes first. Songs still downloading or up next are dropped.",
            Menus.tutorialStart(playing = false, recording = true, picksWaiting = true))
    }

    /** A recording is never lost without a word: storage full, a failed save, a run that was cut off. */
    @Test fun recordingTrouble() {
        assertEquals("Recording stopped at 52:10: the phone is out of space. Saving what was recorded", Menus.recordingStopped(3130.0))
        assertEquals("Recording stopped at 52:10: the disk is full. Saving what was recorded", Menus.recordingStopped(3130.0, computer = true))
        assertEquals("Couldn't save the mix (No space left on device). It's kept: MixDeck tries again next time it opens",
            Menus.saveFailed("No space left on device."))
        assertEquals("Saved your unsaved mix from last time as \"Recovered mix 14 Nov 2026 21.30\" (62:05) · it's in ☰ → My mixes",
            Menus.recoveredMix("Recovered mix 14 Nov 2026 21.30", 3725.0))
    }

    @Test fun settingsWords() {
        assertEquals("85%", Menus.Settings.percent(0.85f))
        assertEquals("MIX blends over 8 bars", Menus.Settings.mixCaption(8))
        assertEquals("MixDeck 1.123 · YouTube downloads are for personal use only.", Menus.guideFooter("1.123"))
    }

    /** REC pressed while an earlier mix saves (e.g. one recovered at the start) says why nothing happens. */
    @Test fun recWaitsForTheSave() {
        assertEquals("Saving your last mix (37%) · REC works again once it's saved", Menus.recWaitsForSave("37%"))
    }

    /** The Settings heading uses the button's name, like the guide and the caption. */
    @Test fun mixLengthIsNamedAfterMix() {
        assertEquals("MIX length", Menus.Settings.MIX_LENGTH)
    }
}
