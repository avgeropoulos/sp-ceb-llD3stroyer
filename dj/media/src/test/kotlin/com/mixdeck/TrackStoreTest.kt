package com.mixdeck

import com.mixdeck.engine.MusicalKey
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackStoreTest {
    private fun tmp(): File = Files.createTempDirectory("mixdeck").toFile()

    @Test fun featuresSurviveSavingAndOldFilesStillLoad() {
        val store = TrackStore(tmp())
        val t = Track("yt_x", "Song", "Artist", "https://www.youtube.com/watch?v=xxxxxxxxxxx", 200.0, 93.0, 0.1, -14.0)
        store.save(t)
        store.audioFile(t.id).writeBytes(ByteArray(10))
        assertFalse(store.get(t.id)!!.hasFeatures, "a song analysed before keys existed needs re-analysis")
        val key = MusicalKey.fromCamelot("8A")
        store.saveFeatures(t, key, 0.8f, -12f, 6.5f, floatArrayOf(0.1f, 0.2f))
        val back = store.get(t.id)!!
        assertTrue(back.hasFeatures)
        assertEquals(key, back.key)
        assertEquals(6.5f, back.energy)
        assertEquals(2, back.timbre!!.size)
        assertEquals(93.0, back.profile().bpm)
        assertEquals("8A", back.profile().key!!.camelot)

        // A file written by the previous app version (no feature fields) still loads.
        val old = org.json.JSONObject("""{"id":"old","title":"Old","artist":"","source":"","durationSec":1,"bpm":120,"firstBeatSec":0,"loudnessDb":-12,"added":1}""")
        val o = Track.fromJson(old)
        assertNull(o.key)
        assertTrue(o.energy.isNaN())
        assertFalse(o.hasFeatures)
    }

    @Test fun historyRemembersRecentSongs() {
        val store = TrackStore(tmp())
        store.markPlayed("a", now = 1_000)
        store.markPlayed("b", now = 2_000)
        store.markPlayed("a", now = 3_000)
        assertEquals(listOf("a", "b"), store.history().map { it.first })
        assertEquals(setOf("a", "b"), store.recentlyPlayed(withinMs = 10_000, now = 4_000))
        assertEquals(setOf("a"), store.recentlyPlayed(withinMs = 1_500, now = 4_000))
    }
}
