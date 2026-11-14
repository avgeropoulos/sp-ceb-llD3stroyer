package com.mixdeck

import com.mixdeck.ui.PickCheck
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fetching picks without the network: songs that are already saved come straight back. */
class SongFetchTest {
    private fun tmp(): File = Files.createTempDirectory("mixdeck").toFile()

    private fun TrackStore.add(t: Track): Track { save(t); audioFile(t.id).writeBytes(ByteArray(10)); return t }

    @Test fun savedComesStraightBack() {
        val store = TrackStore(tmp())
        val steps = ArrayList<String>()
        assertEquals("yt_abc", SongFetcher.fetch(store, SongPick.Saved("yt_abc", "Grillz")) { steps += it })
        assertTrue(steps.isEmpty())
    }

    @Test fun youTubeAlreadySavedNeedsNoDownload() {
        val store = TrackStore(tmp())
        store.add(Track("yt_aaaaaaaaaaa", "Grillz", "Nelly", "https://youtu.be/aaaaaaaaaaa"))
        val steps = ArrayList<String>()
        val id = SongFetcher.fetch(store, SongPick.YouTube("https://youtu.be/aaaaaaaaaaa", "aaaaaaaaaaa", "Grillz", "Nelly", 263)) { steps += it }
        assertEquals("yt_aaaaaaaaaaa", id)
        assertTrue(steps.isEmpty())
    }

    @Test fun spotifySongSavedUnderTheSameNamesLoadsOffline() {
        val store = TrackStore(tmp())
        store.add(Track("yt_old", "Hot In Herre", "Nelly", "https://youtu.be/old"))
        val saved = store.add(Track("yt_grillz", "Grillz", "Nelly, Paul Wall, Ali & Gipp", "https://youtu.be/grillz"))
        val steps = ArrayList<String>()
        // Different case and stray spaces still match; no "Finding … on YouTube" step (no network).
        val pick = SongPick.Spotify(SpotifyTrack(" grillz", "Nelly, Paul Wall, Ali & Gipp ", 263))
        assertEquals(saved.id, SongFetcher.fetch(store, pick) { steps += it })
        assertTrue(steps.isEmpty(), "steps: $steps")
    }

    @Test fun findByNameNeedsTitleAndArtist() {
        val store = TrackStore(tmp())
        store.add(Track("yt_a", "Grillz", "Nelly", "u"))
        assertEquals("yt_a", store.findByName("GRILLZ", "nelly")?.id)
        assertNull(store.findByName("Grillz", "Someone else"))
        assertNull(store.findByName("Grillz (Remix)", "Nelly"))
        // A song whose audio is gone isn't "saved".
        store.audioFile("yt_a").delete()
        assertNull(store.findByName("Grillz", "Nelly"))
    }

    /** Another version saved under the same names (a live take) isn't the Spotify song. */
    @Test fun findByNameChecksTheLength() {
        val store = TrackStore(tmp())
        store.add(Track("yt_live", "Hotel California", "Eagles", "u", durationSec = 432.0))
        assertNull(store.findByName("Hotel California", "Eagles", 390.0), "7:12 isn't 6:30")
        assertEquals("yt_live", store.findByName("Hotel California", "Eagles", 428.0)?.id, "a few seconds apart is the same")
        assertEquals("yt_live", store.findByName("Hotel California", "Eagles")?.id, "length unknown")
        val pick = SongPick.Spotify(SpotifyTrack("Hotel California", "Eagles", 390))
        assertNull(LibraryRules.savedId(LibraryRules.savedNames(store), pick.track))
    }

    /**
     * A Spotify song saved by MixDeck keeps Spotify's length: the YouTube upload it was matched to
     * (and the decoded length that replaces it) may be 13 s longer, yet the song still counts as
     * "on phone" and loads offline. Analysing it keeps that length.
     */
    @Test fun aSavedSpotifySongKeepsItsListedLength() {
        val store = TrackStore(tmp())
        val spotify = SpotifyTrack("Grillz", "Nelly", 232)
        store.add(Track("yt_grillz", "Grillz", "Nelly", "https://youtu.be/grillz", 245.0, listedSec = 232.0))
        val a = com.mixdeck.engine.TrackAnalysis(95.0, 0.1, 10, FloatArray(10), FloatArray(10), FloatArray(10))
        store.saveAnalysis(store.get("yt_grillz")!!, a, -14.0, 245.3)
        assertEquals(232.0, store.get("yt_grillz")!!.listedSec, "kept through the JSON and the analysis")
        assertEquals("yt_grillz", store.findByName(spotify.title, spotify.artist, spotify.durationSec.toDouble())?.id)
        assertEquals("yt_grillz", LibraryRules.savedId(LibraryRules.savedNames(store), spotify))
        val steps = ArrayList<String>()
        assertEquals("yt_grillz", SongFetcher.fetch(store, SongPick.Spotify(spotify)) { steps += it }, "offline: no YouTube search")
        assertTrue(steps.isEmpty())
        // Another version saved from YouTube (no Spotify length) is still told apart by its own length.
        store.add(Track("yt_live", "Hotel California", "Eagles", "u", durationSec = 432.0))
        assertNull(store.findByName("Hotel California", "Eagles", 390.0))
    }

    /** Every screen's TrackStore shares one lock per song, so a song is downloaded once at a time. */
    @Test fun oneLockPerSong() {
        val dir = tmp()
        assertTrue(TrackStore(dir).lockFor("yt_a") === TrackStore(dir).lockFor("yt_a"))
        assertTrue(TrackStore(dir).lockFor("yt_a") !== TrackStore(dir).lockFor("yt_b"))
    }

    /**
     * A pick waiting for another download of the same song still reports, so it can be stopped
     * (its deck got another pick) instead of holding a download thread until the other one ends.
     */
    @Test fun waitingForTheSameSongCanBeStopped() {
        val store = TrackStore(tmp())
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val other = Thread { store.withLock("yt_aaaaaaaaaaa") { holding.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) } }
        other.start()
        holding.await(5, java.util.concurrent.TimeUnit.SECONDS)
        val steps = ArrayList<String>()
        val pick = SongPick.YouTube("https://youtu.be/aaaaaaaaaaa", "aaaaaaaaaaa", "Grillz", "Nelly", 263)
        val r = runCatching {
            SongFetcher.fetch(store, pick) { steps += it; if (steps.size >= 2) throw java.util.concurrent.CancellationException("Stopped") }
        }
        assertTrue(r.exceptionOrNull() is java.util.concurrent.CancellationException, "stopped while the other download held the song")
        assertEquals("Downloading Grillz… 0%", steps.first())
        release.countDown(); other.join(5000)
        assertTrue(store.lockFor("yt_aaaaaaaaaaa").tryLock(), "the lock is free again")
    }

    @Test fun stepTexts() {
        assertEquals("Downloading Grillz… 37%", SongFetcher.Step("Grillz", 0.375f).text)
        assertEquals("Grillz · downloading 42%", SongFetcher.Step("Grillz", 0.42f).short)
        assertEquals("Finding Grillz on YouTube…", SongFetcher.Step("Grillz", null).text)
    }

    @Test fun picksSurviveTheirExtras() {
        val picks = listOf(
            SongPick.Saved("yt_abc", "Grillz"),
            SongPick.Saved("file_1f"),
            SongPick.YouTube("https://www.youtube.com/watch?v=aaaaaaaaaaa", "aaaaaaaaaaa", "Grillz", "Nelly", 263),
            SongPick.Spotify(SpotifyTrack("Air Force Ones", "Nelly, Kyjuan, Ali, Murphy Lee", 304)),
        )
        for (p in picks) {
            val extras = p.toExtras()
            assertTrue(extras.keys.all { it in SongPick.KEYS })
            assertEquals(p, SongPick.fromExtras(extras::get))
            assertEquals(p.title, SongPick.fromExtras(extras::get)!!.title)
        }
        // The older result: just a song id.
        assertEquals(SongPick.Saved("yt_x"), SongPick.fromExtras(mapOf("track" to "yt_x")::get))
        assertNull(SongPick.fromExtras(emptyMap<String, String>()::get))
    }

    @Test fun placementsFromTheAnswers() {
        assertEquals(Placement(1, true, Placement.When.NOW), Placement.of(PickCheck.Option.OTHER_DECK, 0, sync = false, syncOther = true))
        assertEquals(Placement(0, false, Placement.When.REPLACE), Placement.of(PickCheck.Option.REPLACE, 0, false))
        assertEquals(Placement(1, true, Placement.When.ENDS), Placement.of(PickCheck.Option.WHEN_ENDS, 1, true))
        assertNull(Placement.of(PickCheck.Option.CANCEL, 1, true))
    }

    /**
     * "Stop A after the download" stops A once the song is saved while the other deck still plays
     * (the music carries on); if the other deck has stopped by then, it waits for A's song to end.
     */
    @Test fun aDownloadNeverCutsTheMusicOff() {
        assertEquals(Placement(0, false, Placement.When.REPLACE), Placement(0, false, Placement.When.REPLACE).afterDownload(otherLive = true))
        assertEquals(Placement(0, false, Placement.When.ENDS), Placement(0, false, Placement.When.REPLACE).afterDownload(otherLive = false))
        for (live in listOf(true, false)) {
            assertEquals(Placement(1, true, Placement.When.NOW), Placement(1, true, Placement.When.NOW).afterDownload(live))
            assertEquals(Placement(1, true, Placement.When.ENDS), Placement(1, true, Placement.When.ENDS).afterDownload(live))
        }
    }
}
