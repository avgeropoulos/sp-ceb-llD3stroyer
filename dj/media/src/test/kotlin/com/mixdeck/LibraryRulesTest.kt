package com.mixdeck

import com.mixdeck.engine.MusicalKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRulesTest {
    private val afo = Track("yt_afo", "Air Force Ones", "Nelly", "u", 226.0, 101.2, keyIndex = MusicalKey.fromCamelot("8A")!!.index, keyConfidence = 0.6f, added = 3)
    private val grillz = Track("yt_grillz", "Grillz", "Nelly", "u", 263.0, 82.0, keyIndex = MusicalKey.fromCamelot("4A")!!.index, keyConfidence = 0.05f, added = 2)
    private val file = Track("file_1", "Untitled", "", "u", added = 1)

    @Test fun defaultTab() {
        assertEquals(LibraryTab.SUGGESTED, LibraryRules.defaultTab(suggest = true, hasSavedNotOnDeck = true))
        assertEquals(LibraryTab.SUGGESTED, LibraryRules.defaultTab(suggest = true, hasSavedNotOnDeck = false))
        assertEquals(LibraryTab.MINE, LibraryRules.defaultTab(suggest = false, hasSavedNotOnDeck = true))
        assertEquals(LibraryTab.YOUTUBE, LibraryRules.defaultTab(suggest = false, hasSavedNotOnDeck = false))
    }

    @Test fun savedNotOnDeck() {
        assertFalse(LibraryRules.hasSavedNotOnDeck(emptyList(), listOf(null, null)))
        // The only saved song is already on a deck: YouTube is more useful.
        assertFalse(LibraryRules.hasSavedNotOnDeck(listOf(afo), listOf("yt_afo", null)))
        assertTrue(LibraryRules.hasSavedNotOnDeck(listOf(afo, grillz), listOf("yt_afo", null)))
    }

    @Test fun tabOrder() {
        assertEquals(listOf(LibraryTab.SUGGESTED, LibraryTab.MINE, LibraryTab.YOUTUBE, LibraryTab.SPOTIFY, LibraryTab.FILES), LibraryTab.entries.toList())
    }

    @Test fun subtitleShowsTempoKeyAndLength() {
        assertEquals("Nelly  ·  101 BPM  ·  8A  ·  3:46", LibraryRules.songSubtitle(afo))
        // An unreliable key isn't shown.
        assertEquals("Nelly  ·  82 BPM  ·  4:23", LibraryRules.songSubtitle(grillz))
        assertEquals("Nelly  ·  101 BPM  ·  8A  ·  3:46  ·  on deck B", LibraryRules.songSubtitle(afo, onDeck = 1))
        assertEquals("", LibraryRules.songSubtitle(file))
    }

    @Test fun filterMatchesTitleArtistOrAnExactKey() {
        assertTrue(LibraryRules.matches(afo, "force"))
        assertTrue(LibraryRules.matches(afo, "NELLY"))
        assertTrue(LibraryRules.matches(afo, "8a"))
        assertTrue(LibraryRules.matches(afo, " 8A "))
        assertFalse(LibraryRules.matches(afo, "8"))
        assertFalse(LibraryRules.matches(afo, "9A"))
        assertFalse(LibraryRules.matches(grillz, "4A"), "unreliable keys don't match")
        assertTrue(LibraryRules.matches(file, ""))
        assertEquals("Filter my 128 songs (title, artist or a key like 8A)", LibraryRules.filterHint(128))
    }

    @Test fun songsOnADeckSortLast() {
        val list = LibraryRules.mySongs(listOf(afo, grillz, file), listOf("yt_afo", null), "")
        assertEquals(listOf("yt_grillz", "file_1", "yt_afo"), list.map { it.first.id })
        assertEquals(listOf(null, null, 0), list.map { it.second })
        assertEquals(listOf("yt_afo"), LibraryRules.mySongs(listOf(afo, grillz, file), listOf(null, null), "8A").map { it.first.id })
    }

    @Test fun spotifyRowsKnowWhatsSaved() {
        val store = TrackStore(Files.createTempDirectory("mixdeck").toFile())
        store.save(grillz); store.audioFile(grillz.id).writeBytes(ByteArray(4))
        val names = LibraryRules.savedNames(store)
        assertEquals("yt_grillz", LibraryRules.savedId(names, SpotifyTrack("grillz ", "NELLY", 263)))
        assertEquals("yt_grillz", LibraryRules.savedId(names, SpotifyTrack("Grillz", "Nelly", 258)), "a few seconds apart")
        assertNull(LibraryRules.savedId(names, SpotifyTrack("Grillz", "Nelly", 220)), "another version")
        assertNull(LibraryRules.savedId(names, SpotifyTrack("Air Force Ones", "Nelly", 304)))
    }
}
