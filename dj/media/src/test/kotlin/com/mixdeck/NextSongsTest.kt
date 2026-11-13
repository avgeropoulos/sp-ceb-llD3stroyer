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

    /** A Suggester whose decoding takes a while and then fails, so checks are slow but harmless. */
    private fun slowSuggester() = Suggester(store) { f, _ -> decoded += f.nameWithoutExtension; Thread.sleep(250); throw IOException("no audio in tests") }

    private fun waitUntil(ms: Long = 10_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until && !done()) Thread.sleep(20)
    }

    @Test fun stopCancelsAFindThatIsWaitingForTheCheck() {
        val seed = add("seed", 93.0, "8A", 6f)
        for (i in 1..4) add("old$i", 94.0, null, Float.NaN, features = false)
        val states = java.util.concurrent.CopyOnWriteArrayList<NextSongs.State>()
        val n = NextSongs(store, slowSuggester(), seed, 0.0, onDecks = setOf("seed"), waiting = null,
            direction = Direction.KEEP) { states += it }
        n.start()
        waitUntil { states.any { it.checking != null } }
        n.findNew()          // queued behind the check
        n.stop()             // ...and stopped before it starts
        waitUntil { states.lastOrNull()?.let { it.checking == null && it.finding == null } == true && decoded.isNotEmpty() }
        Thread.sleep(300)
        n.close()
        assertTrue(states.none { it.finding?.startsWith("Asking YouTube") == true }, "the stopped find never asked YouTube")
        assertEquals(null, states.last().finding)
        assertTrue(decoded.size < 4, "the check stopped early")
    }

    @Test fun directionChangesApplyWhileSongsAreBeingChecked() {
        val seed = add("seed", 93.0, "8A", 6f)
        add("calm", 93.0, "8A", 4f)
        add("lift", 93.0, "8A", 7f)
        for (i in 1..10) add("old$i", 94.0, null, Float.NaN, features = false) // a check long enough to change direction during
        val states = java.util.concurrent.CopyOnWriteArrayList<NextSongs.State>()
        val n = NextSongs(store, slowSuggester(), seed, 0.0, onDecks = setOf("seed"), waiting = null,
            direction = Direction.KEEP) { states += it }
        n.start()
        waitUntil { states.any { it.checking != null } }
        n.setDirection(Direction.UP)
        waitUntil(3_000) { states.any { it.checking != null && it.direction == Direction.UP } }
        n.close()
        val mid = states.firstOrNull { it.checking != null && it.direction == Direction.UP }
        assertTrue(mid != null, "a ranking during the check already uses Build up")
        val during = states.last { it.direction == Direction.UP && it.list.isNotEmpty() }
        assertEquals("lift", during.list.first().track.id, "Build up puts the higher-energy song first")
    }

    @Test fun aFailedDownloadLeavesNoPartFile() {
        val dest = File(dir, "x.audio")
        val port = java.net.ServerSocket(0).use { it.localPort }  // nothing listens here any more
        runCatching { Net.download("http://127.0.0.1:$port/song", dest) }
        assertTrue(!File(dest.path + ".part").exists(), "no .part file left behind")
        assertTrue(!dest.exists())
    }

    @Test fun fixingTheTempoMeansCheckingEnergyAgain() {
        add("t", 87.0, "8A", 6f)
        store.updateGrid("t", 87.0, 0.25)            // first beat moved: still checked
        assertTrue(store.get("t")!!.hasFeatures)
        store.updateGrid("t", 174.0, 0.25)           // doubled: energy needs a new look
        assertTrue(!store.get("t")!!.hasFeatures)
        assertEquals(MusicalKey.fromCamelot("8A"), store.get("t")!!.key, "the key is kept meanwhile")
    }

    @Test fun tempoChangeUsesHalfAndDoubleTime() {
        assertEquals(0.0, NextSongs.tempoChange(93.0, 93.0), 1e-9)
        assertEquals(93.0 * 2 / 174 - 1, NextSongs.tempoChange(93.0, 174.0), 1e-9)
        assertEquals(0.0, NextSongs.tempoChange(0.0, 120.0), 1e-9)
    }
}
