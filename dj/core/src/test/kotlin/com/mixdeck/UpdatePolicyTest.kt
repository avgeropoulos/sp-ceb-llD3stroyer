package com.mixdeck

import com.mixdeck.UpdatePolicy.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatePolicyTest {
    @Test fun manualChecksAlwaysOffer() {
        assertTrue(UpdatePolicy.shouldOffer(manual = true, code = 130, laterVersion = 0, busy = false, quiet = false))
        assertTrue(UpdatePolicy.shouldOffer(manual = true, code = 130, laterVersion = 130, busy = true, quiet = true))
        assertTrue(UpdatePolicy.shouldOffer(manual = true, code = 130, laterVersion = 200, busy = true, quiet = false))
    }

    @Test fun automaticOfferWhenNothingIsGoingOn() {
        assertTrue(UpdatePolicy.shouldOffer(manual = false, code = 130, laterVersion = 0, busy = false, quiet = false))
    }

    @Test fun neverOverTheMusicOrARecording() {
        assertFalse(UpdatePolicy.shouldOffer(manual = false, code = 130, laterVersion = 0, busy = true, quiet = false))
    }

    @Test fun neverDuringTheTutorialOrStartCard() {
        assertFalse(UpdatePolicy.shouldOffer(manual = false, code = 130, laterVersion = 0, busy = false, quiet = true))
        assertFalse(UpdatePolicy.shouldOffer(manual = false, code = 130, laterVersion = 0, busy = true, quiet = true))
    }

    @Test fun laterSkipsThatVersionAndOlderOnesButNotNewerOnes() {
        assertFalse(UpdatePolicy.shouldOffer(manual = false, code = 130, laterVersion = 130, busy = false, quiet = false))
        assertFalse(UpdatePolicy.shouldOffer(manual = false, code = 129, laterVersion = 130, busy = false, quiet = false))
        assertTrue(UpdatePolicy.shouldOffer(manual = false, code = 131, laterVersion = 130, busy = false, quiet = false))
    }

    @Test fun recheckEverySixHours() {
        val h = 60L * 60 * 1000
        val last = 1_000_000_000_000L
        assertTrue(UpdatePolicy.checkDue(last, 0L), "never checked")
        assertFalse(UpdatePolicy.checkDue(last + h, last))
        assertFalse(UpdatePolicy.checkDue(last + 6 * h, last))
        assertTrue(UpdatePolicy.checkDue(last + 6 * h + 1, last))
        assertTrue(UpdatePolicy.checkDue(last - h, last), "the clock went back")
    }

    @Test fun offlineRetriesWaitTenMinutes() {
        val min = 60L * 1000
        val now = 1_000_000_000_000L
        val longAgo = now - 7 * 60 * min
        assertFalse(UpdatePolicy.checkDue(now, longAgo, lastTry = now - 5 * min))
        assertTrue(UpdatePolicy.checkDue(now, longAgo, lastTry = now - 11 * min))
        assertFalse(UpdatePolicy.checkDue(now, now - min, lastTry = now - 11 * min), "checked a minute ago")
    }

    @Test fun phoneMenuRows() {
        assertEquals("⬆ Update to 1.130", UpdatePolicy.phoneMenuLabel(Stage.AVAILABLE, "1.130"))
        assertEquals("⬆ Downloading 1.130… 42%", UpdatePolicy.phoneMenuLabel(Stage.DOWNLOADING, "1.130", 42))
        assertEquals("⬆ Downloading 1.130… 100%", UpdatePolicy.phoneMenuLabel(Stage.DOWNLOADING, "1.130", 140))
        assertEquals("⬆ Install 1.130", UpdatePolicy.phoneMenuLabel(Stage.DOWNLOADED, "1.130"))
    }

    @Test fun whatsNewParagraph() {
        assertEquals("What's new: MIX button", UpdatePolicy.whatsNew("What's new: MIX button"))
        assertEquals("What's new: MIX button", UpdatePolicy.whatsNew("MIX button"))
        assertEquals("", UpdatePolicy.whatsNew("  "))
        assertEquals("", UpdatePolicy.whatsNew("What's new: "))
    }

    /** A held-back install says what it waits for, or "now" when only the tutorial or Start card held it back. */
    @Test fun readyWhenSaysWhatItWaitsFor() {
        assertEquals("when the music stops", UpdatePolicy.readyWhen(playing = true, recording = true, saving = true))
        assertEquals("once you stop recording", UpdatePolicy.readyWhen(playing = false, recording = true, saving = false))
        assertEquals("once your mix is saved", UpdatePolicy.readyWhen(playing = false, recording = false, saving = true))
        assertEquals("now", UpdatePolicy.readyWhen(playing = false, recording = false, saving = false))
    }
}
