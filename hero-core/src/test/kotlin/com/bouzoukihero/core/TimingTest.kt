package com.bouzoukihero.core

import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Timing: what's drawn against what's judged, and what the player's taps say about the sync. */
class TimingTest {
    private val frame = 1 / 60.0

    /** What the game draws ahead by on a 60 Hz screen. */
    private val lead = DisplayLead.seconds(60f)

    private fun note(time: Double, lane: Int = 0, end: Double = time) = Gem(time, end, lane, "", false)

    private fun session(gems: List<Gem>, d: Difficulty = Difficulty.MEDIUM) =
        Session("Test", d, gems).also { it.viewH = 640f }

    /** Runs the game's frames up to song time [until], drawing [lead] ahead as the phone does. */
    private fun play(s: Session, from: Double, until: Double) {
        var t = from
        while (t < until) {
            s.update(t + lead, t, frame.toFloat())
            t += frame
        }
        s.update(until + lead, until, frame.toFloat())
    }

    @Test fun displayLeadFollowsTheScreen() {
        // Two refreshes of pipeline (at least 25 ms) and 0.6 of one for the scan down to the hit line.
        assertEquals(0.04333, DisplayLead.seconds(60f), 1e-5)
        assertEquals(0.03167, DisplayLead.seconds(90f), 1e-5)
        assertEquals(0.03000, DisplayLead.seconds(120f), 1e-5)
        // A screen that won't say (or says nonsense) is taken as 60 Hz.
        for (odd in listOf(0f, -1f, 1f, Float.NaN, Float.POSITIVE_INFINITY, 5000f)) {
            assertEquals(DisplayLead.seconds(60f), DisplayLead.seconds(odd), 0.0, "rate $odd")
        }
        assertTrue(DisplayLead.seconds(60f) > DisplayLead.OLD)
    }

    @Test fun tunedSyncsKeepTheirMeaningUnderTheNewLead() {
        // Never tuned: stays untuned, at any rate.
        assertEquals(0.0, DisplayLead.migrated(0.0, 60f), 0.0)
        // The notes show 18 ms earlier by themselves at 60 Hz, so a tuned sync gives 18 ms back.
        assertEquals(0.007, DisplayLead.migrated(0.025, 60f), 1e-9)
        assertEquals(-0.068, DisplayLead.migrated(-0.05, 60f), 1e-9)
        assertEquals(0.02, DisplayLead.migrated(0.025, 120f), 1e-9)
        assertEquals(-0.055, DisplayLead.migrated(-0.05, 120f), 1e-9)
        // What was drawn before is what's drawn after: old sync + old lead = new sync + new lead.
        for (hz in listOf(60f, 90f, 120f, 144f)) for (sync in listOf(-0.3, -0.025, 0.1, 0.475)) {
            assertEquals(sync + DisplayLead.OLD, DisplayLead.migrated(sync, hz) + DisplayLead.seconds(hz), 0.00051, "$sync at $hz Hz")
        }
        assertEquals(-1.0, DisplayLead.migrated(-0.995, 60f), 0.0, "stays within the sync's range")
    }

    @Test fun storedSyncsAreRestatedAndDefaultsStayWithTheirKind() {
        // A "later" chosen on a built-in tune was the shared default, and made YouTube songs late.
        val stored = mapOf<String, Any>(
            "song_sync_abcdefghijk" to 0.05f, "song_sync_untuned0000" to 0f, "hero_sync_b:zeibekiko" to -0.075f,
            "hero_sync_last" to -0.075f, "song_page" to 1, "song_last" to "abcdefghijk", "song_detail_abcdefghijk" to "x",
        )
        // The YouTube song keeps its notes where they were (18 ms of sync given back at 60 Hz). The
        // built-in tune starts afresh: its clock no longer runs ahead of the sound, which is what
        // its "later" made up for. And nothing of it is left to make YouTube songs late.
        assertEquals(
            mapOf("song_sync_abcdefghijk" to 0.032f, "hero_sync_b:zeibekiko" to null, "hero_sync_last" to null),
            SyncStore.restated(stored, 60f),
        )
        // The other way round: tuned on a YouTube song last, so YouTube songs keep starting from it.
        assertEquals(
            mapOf("song_sync_a" to -0.105f, "song_sync_b" to 0.02f, "hero_sync_b:x" to null, "hero_sync_default_song" to -0.105f, "hero_sync_last" to null),
            SyncStore.restated(mapOf("song_sync_a" to -0.1f, "song_sync_b" to 0.025f, "hero_sync_b:x" to 0.05f, "hero_sync_last" to -0.1f), 120f),
        )
        // A shared default that is no YouTube song's own sync can't be told apart: it's dropped.
        assertEquals(mapOf<String, Float?>("song_sync_a" to 0.007f, "hero_sync_last" to null),
            SyncStore.restated(mapOf("song_sync_a" to 0.025f, "hero_sync_last" to -0.2f), 60f))
        // Nothing stored, nothing to do; and the new keys are never mistaken for a song's or a tune's.
        assertEquals(emptyMap(), SyncStore.restated(emptyMap<String, Any>(), 60f))
        for (key in listOf(SyncStore.DEFAULT_SONG, SyncStore.DEFAULT_BUILTIN, SyncStore.RESTATED)) {
            assertFalse(key.startsWith(SyncStore.SONG) || key.startsWith(SyncStore.BUILTIN) || key == SyncStore.OLD_SHARED_DEFAULT, key)
        }
    }

    @Test fun theHitWindowIsAsLongLateAsItIsEarly() {
        for (d in Difficulty.entries) {
            val w = d.window
            fun tapAt(offset: Double): Judgement? {
                val g = note(5.0)
                val s = session(listOf(g), d)
                // The frames keep coming up to the moment of the tap, drawn ahead of the song.
                play(s, 4.0, 5.0 + offset)
                s.press(0, 5.0 + offset)
                return g.judged
            }
            assertEquals(Judgement.GOOD, tapAt(-(w - 0.001)), "$d: just inside, early")
            assertEquals(Judgement.GOOD, tapAt(w - 0.001), "$d: just inside, late")
            assertNull(tapAt(-(w + 0.002)), "$d: just outside, early")
            assertNull(tapAt(w + 0.002), "$d: just outside, late")
            assertEquals(Judgement.PERFECT, tapAt(0.0), "$d: on the note")
            // Drawing ahead takes nothing off the late side: the whole lead before the edge still hits.
            assertEquals(Judgement.GOOD, tapAt(w - lead / 2), "$d: late by less than the window")
        }
    }

    @Test fun aTapMadeInTimeCountsEvenIfItArrivesLate() {
        val d = Difficulty.HARD
        val g = note(5.0)
        val s = session(listOf(g), d)
        // The finger went down just inside the window; the game hears of it 40 ms later.
        play(s, 4.0, 5.0 + d.window - 0.001 + 0.04)
        assertNull(g.judged, "not called missed while a tap may still be on its way")
        s.press(0, 5.0 + d.window - 0.001)
        assertEquals(Judgement.GOOD, g.judged)

        // With no tap, it is a miss once the window and the wait for a tap are over.
        val g2 = note(5.0)
        val s2 = session(listOf(g2), d)
        play(s2, 4.0, 5.0 + d.window + Session.TAP_GRACE - 0.005)
        assertNull(g2.judged)
        play(s2, 5.0 + d.window + Session.TAP_GRACE - 0.005, 5.0 + d.window + Session.TAP_GRACE + 0.02)
        assertEquals(Judgement.MISS, g2.judged)
        // And a tap after the window never hits, however long the note waited.
        val g3 = note(5.0)
        val s3 = session(listOf(g3), d)
        play(s3, 4.0, 5.0 + d.window + 0.01)
        s3.press(0, 5.0 + d.window + 0.01)
        assertNull(g3.judged)
    }

    @Test fun holdsAreScoredOnTheSongNotOnWhatIsDrawn() {
        val g = note(2.0, end = 4.0)
        val s = session(listOf(g))
        play(s, 1.0, 2.0)
        s.press(0, 2.0)
        // The drawing is past the end of the hold, the song isn't yet.
        play(s, 2.0, 4.0 - lead / 2)
        assertTrue(s.time > g.end && s.judgedTime < g.end)
        assertFalse(g.holdDone)
        play(s, 4.0 - lead / 2, 4.01)
        assertTrue(g.holdDone)
        // Held for the 2 seconds at 30 points a second, plus the hit and the bonus at the end.
        assertTrue(abs(s.score - (100 + 60 + 50)) <= 2, "score ${s.score}")
    }

    /** Plays [offsets].size notes half a second apart, each tapped that far from its time. */
    private fun tapped(offsets: List<Double>, d: Difficulty = Difficulty.EASY): Session {
        val gems = offsets.indices.map { note(2.0 + it * 0.5, it % d.lanes) }
        val s = session(gems, d)
        for ((i, g) in gems.withIndex()) {
            play(s, g.time - 0.3, g.time + offsets[i])
            s.press(g.lane, g.time + offsets[i])
            s.release(g.lane, g.time + offsets[i] + 0.05)
        }
        return s
    }

    @Test fun theTapsSayHowFarOffTheSyncIs() {
        // Too few taps to tell.
        val few = tapped(List(Session.MIN_TAPS - 1) { 0.04 })
        assertEquals(Session.MIN_TAPS - 1, few.tapCount)
        assertNull(few.tapOffset())
        assertEquals("", few.tapWords())
        assertEquals(0.0, few.syncFix(), 0.0)
        assertEquals("", few.fixLabel())

        // Steadily late, with a little scatter and a couple of wild taps: the middle one counts.
        val late = tapped(List(40) { i -> if (i % 13 == 5) -0.12 else 0.032 + (i % 5 - 2) * 0.004 })
        assertEquals(40, late.tapCount)
        assertEquals(0.032, assertNotNull(late.tapOffset()), 1e-9)
        assertEquals("32 ms late", late.tapWords())
        // Taps late: the sync goes down (the notes show later), in steps of 5 ms.
        assertEquals(-0.030, late.syncFix(), 1e-9)
        assertEquals("Move the notes 30 ms later", late.fixLabel())

        val early = tapped(List(25) { -0.0176 })
        assertEquals(-0.0176, assertNotNull(early.tapOffset()), 1e-9)
        assertEquals("18 ms early", early.tapWords())
        assertEquals(0.020, early.syncFix(), 1e-9)
        assertEquals("Move the notes 20 ms earlier", early.fixLabel())

        // Close enough: said, but nothing to fix.
        val close = tapped(List(30) { 0.012 })
        assertEquals("12 ms late", close.tapWords())
        assertEquals(0.0, close.syncFix(), 0.0)
        assertEquals("", close.fixLabel())
        assertEquals("on time", tapped(List(30) { 0.0002 }).tapWords())
        // Misses are no taps: only notes that were hit count.
        val missed = tapped(List(30) { i -> if (i < 15) 0.03 else 0.4 })
        assertEquals(15, missed.tapCount)
        assertNull(missed.tapOffset())
    }

    @Test fun applyingTheFixIsNotOfferedTwice() {
        val s = tapped(List(40) { 0.047 })
        val fix = s.syncFix()
        assertEquals(-0.045, fix, 1e-9)
        // The host moves the sync by the fix: the taps are now 2 ms late, and that's that.
        s.syncMoved(fix)
        assertEquals(0.002, assertNotNull(s.tapOffset()), 1e-9)
        assertEquals("2 ms late", s.tapWords())
        assertEquals(0.0, s.syncFix(), 0.0)
        // A nudge by hand restates them the same way: notes 25 ms earlier, taps 25 ms later.
        s.syncMoved(0.025)
        assertEquals("27 ms late", s.tapWords())
        assertEquals(-0.025, s.syncFix(), 1e-9)
        // A new go starts counting again.
        s.restart()
        assertEquals(0, s.tapCount)
        assertNull(s.tapOffset())
        assertEquals("", s.tapWords())
    }

    @Test fun theFixButtonIsOnTheResultsAndInThePauseMenu() {
        val img = BufferedImage(360, 640, BufferedImage.TYPE_INT_ARGB)
        fun buttonsOf(s: Session): Set<Session.Action> {
            s.render(AwtGfx(img, 1f))
            val found = HashSet<Session.Action>()
            for (y in 0 until 640 step 4) for (x in 0 until 360 step 4) found.add(s.tap(x.toFloat(), y.toFloat()))
            return found
        }
        val s = tapped(List(40) { 0.04 })
        s.syncText = "in step" // the host keeps a sync for this song
        s.state = Session.State.PAUSED
        val paused = buttonsOf(s)
        assertTrue(Session.Action.SYNC_FIT in paused, "pause menu: $paused")
        assertTrue(paused.containsAll(listOf(Session.Action.RESUME, Session.Action.RESTART, Session.Action.QUIT,
            Session.Action.SYNC_EARLIER, Session.Action.SYNC_LATER)), "pause menu: $paused")
        s.state = Session.State.PLAYING
        s.finish()
        val results = buttonsOf(s)
        assertTrue(results.containsAll(listOf(Session.Action.SYNC_FIT, Session.Action.RESTART, Session.Action.QUIT)), "results: $results")
        // Once applied, the button is gone from both.
        s.syncMoved(s.syncFix())
        assertFalse(Session.Action.SYNC_FIT in buttonsOf(s))
        // No sync kept by the host, or taps close enough: no button.
        val plain = tapped(List(40) { 0.04 })
        plain.finish()
        assertFalse(Session.Action.SYNC_FIT in buttonsOf(plain))
        val close = tapped(List(40) { 0.01 })
        close.syncText = "in step"
        close.finish()
        assertFalse(Session.Action.SYNC_FIT in buttonsOf(close))

        // On a short screen with the video on top, the whole pause menu still fits.
        val short = tapped(List(40) { 0.04 })
        short.topInset = 202.5f
        short.syncText = "in step"
        short.perfText = "Smoothness: 60 fps, slowest frame 17 ms\nScreen 60 Hz, notes drawn 43 ms ahead"
        short.state = Session.State.PAUSED
        val all = buttonsOf(short)
        assertTrue(all.containsAll(listOf(Session.Action.RESUME, Session.Action.RESTART, Session.Action.QUIT,
            Session.Action.SYNC_EARLIER, Session.Action.SYNC_LATER, Session.Action.SYNC_FIT)), "short screen: $all")
    }
}
