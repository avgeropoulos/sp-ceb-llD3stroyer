package com.mixdeck

import com.mixdeck.engine.TrackAnalysis
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** A song saved on the phone, ready to load onto a deck. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val source: String,
    val durationSec: Double = 0.0,
    val bpm: Double = 0.0,
    val firstBeatSec: Double = 0.0,
    val loudnessDb: Double = Double.NaN,
    val added: Long = System.currentTimeMillis(),
) {
    val analyzed get() = !loudnessDb.isNaN()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("artist", artist).put("source", source)
        .put("durationSec", durationSec).put("bpm", bpm).put("firstBeatSec", firstBeatSec)
        .put("loudnessDb", if (loudnessDb.isNaN()) JSONObject.NULL else loudnessDb).put("added", added)

    companion object {
        fun fromJson(o: JSONObject) = Track(
            o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("source"),
            o.optDouble("durationSec", 0.0), o.optDouble("bpm", 0.0), o.optDouble("firstBeatSec", 0.0),
            if (o.isNull("loudnessDb")) Double.NaN else o.optDouble("loudnessDb", Double.NaN), o.optLong("added"),
        )
    }
}

/**
 * Downloaded songs, their analysis (BPM, beat grid, waveform), and imported Spotify lists, kept
 * under [baseDir] (the app's files folder on the phone, a MixDeck folder on the computer).
 */
class TrackStore(baseDir: File) {
    private val dir = File(baseDir, "tracks").apply { mkdirs() }
    private val spotifyFile = File(baseDir, "spotify.json")

    fun audioFile(id: String) = File(dir, "$id.audio")
    private fun metaFile(id: String) = File(dir, "$id.json")
    private fun waveFile(id: String) = File(dir, "$id.wave")

    fun has(id: String) = audioFile(id).exists() && metaFile(id).exists()

    fun get(id: String): Track? = runCatching { Track.fromJson(JSONObject(metaFile(id).readText())) }.getOrNull()

    fun save(t: Track) = metaFile(t.id).writeText(t.toJson().toString())

    fun all(): List<Track> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { Track.fromJson(JSONObject(f.readText())) }.getOrNull() }
        .filter { audioFile(it.id).exists() }
        .sortedByDescending { it.added }

    fun delete(id: String) { audioFile(id).delete(); metaFile(id).delete(); waveFile(id).delete() }

    fun saveAnalysis(t: Track, a: TrackAnalysis, loudnessDb: Double, durationSec: Double): Track {
        val updated = t.copy(bpm = a.bpm, firstBeatSec = a.firstBeatSec, loudnessDb = loudnessDb, durationSec = durationSec)
        save(updated)
        DataOutputStream(waveFile(t.id).outputStream().buffered()).use { out ->
            out.writeInt(1); out.writeInt(a.binsPerSecond); out.writeInt(a.peak.size)
            for (arr in arrayOf(a.peak, a.bass, a.treble)) {
                val b = ByteArray(arr.size) { (arr[it].coerceIn(0f, 1f) * 255).toInt().toByte() }
                out.write(b)
            }
        }
        return updated
    }

    fun loadAnalysis(t: Track): TrackAnalysis? {
        if (!t.analyzed || !waveFile(t.id).exists()) return null
        return runCatching {
            DataInputStream(waveFile(t.id).inputStream().buffered()).use { inp ->
                if (inp.readInt() != 1) return null
                val bps = inp.readInt(); val n = inp.readInt()
                val arrs = Array(3) {
                    val b = ByteArray(n); inp.readFully(b)
                    FloatArray(n) { i -> (b[i].toInt() and 0xFF) / 255f }
                }
                TrackAnalysis(t.bpm, t.firstBeatSec, bps, arrs[0], arrs[1], arrs[2])
            }
        }.getOrNull()
    }

    /** Remembers a beat grid the user fixed by hand (e.g. doubled the BPM). */
    fun updateGrid(id: String, bpm: Double, firstBeatSec: Double) {
        val t = get(id) ?: return
        save(t.copy(bpm = bpm, firstBeatSec = firstBeatSec))
    }

    fun spotifyLists(): List<SpotifyList> = runCatching {
        val arr = JSONArray(spotifyFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ts = o.getJSONArray("tracks")
            SpotifyList(o.getString("url"), o.getString("name"), (0 until ts.length()).map { j ->
                val t = ts.getJSONObject(j)
                SpotifyTrack(t.getString("title"), t.optString("artist"), t.optLong("durationSec"))
            })
        }
    }.getOrDefault(emptyList())

    fun saveSpotifyList(list: SpotifyList) {
        val lists = listOf(list) + spotifyLists().filter { it.url != list.url }
        val arr = JSONArray()
        lists.take(30).forEach { l ->
            val ts = JSONArray()
            l.tracks.forEach { ts.put(JSONObject().put("title", it.title).put("artist", it.artist).put("durationSec", it.durationSec)) }
            arr.put(JSONObject().put("url", l.url).put("name", l.name).put("tracks", ts))
        }
        spotifyFile.writeText(arr.toString())
    }
}
