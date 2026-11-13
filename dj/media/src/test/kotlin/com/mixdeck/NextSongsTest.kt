package com.mixdeck

import com.mixdeck.engine.Direction
import com.mixdeck.engine.MusicalKey
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NextSongsTest {
    private val dir: File = Files.createTempDirectory("nextsongs").toFile()
    private val store = TrackStore(dir)
    private val decoded = Collections.synchronizedList(ArrayList<String>())
    private val suggester = Suggester(store) { f, _ -> decoded += f.nameWithoutExtension; throw IOException("no audio in tests") }

    private fun add(id: String, bpm: Double, key: String?, energy: Float, features: Boolean = true, analysed: Boolean = true): Track {
        store.audioFile(id).writeBytes(ByteArray(4))
        val t = Track(id, "Title $id", "Artist $id", "file", 200.0, bpm, 0.0, if (analysed) -14.0 else Double.NaN,
            keyIndex = key?.let { MusicalKey.fromCamelot(it)!!.index } ?: -1, keyConfidence = 0.8f, energy = energy,
            featuresVersion = if (features) Track.FEATURES_VERSION else 0)
        store.save(t)
        return t
    }

    @Test fun ranksStraightAwayAndChecksOnlySongsThatCouldFit() {
        val seed = add("seed", 93.0, "8A", 6f)
        val grillz = add("grillz", 98.0, "3A", 7f)
        add("near", 94.0, "9A", 6f)
        add("far", 128.0, "8A", 8f)
        add("old_close", 95.0, null, Float.NaN, features = false)
        add("old_far", 140.0, null, Float.NaN, features = false)
        add("never", 0.0, null, Float.NaN, features = false, analysed = false)
        var last: NextSongs.State? = null
        val n = NextSongs(store, suggester, seed, 0.0, onDecks = setOf("seed", "grillz"), waiting = grillz,
            direction = Direction.KEEP) { last = it }
        n.start()
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until && (last?.checking != null || decoded.size < 2)) Thread.sleep(20)
        n.close()
        val s = last!!
        assertEquals("near", s.list.first().track.id)
        assertTrue(s.list.none { it.track.id == "seed" || it.track.id == "grillz" }, "songs on the decks aren't suggested")
        assertEquals("grillz", s.waiting?.track?.id)
        // Never-analysed first, then songs close enough in tempo; 140 BPM (33% away) is skipped.
        assertEquals(listOf("never", "old_close"), decoded.toList())
    }

    @Test fun tempoChangeUsesHalfAndDoubleTime() {
        assertEquals(0.0, NextSongs.tempoChange(93.0, 93.0), 1e-9)
        assertEquals(93.0 * 2 / 174 - 1, NextSongs.tempoChange(93.0, 174.0), 1e-9)
        assertEquals(0.0, NextSongs.tempoChange(0.0, 120.0), 1e-9)
    }
}
