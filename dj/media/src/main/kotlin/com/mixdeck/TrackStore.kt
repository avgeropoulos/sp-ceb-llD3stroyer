package com.mixdeck

import com.mixdeck.engine.MusicalKey
import com.mixdeck.engine.TrackAnalysis
import com.mixdeck.engine.TrackProfile
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
    /** Musical key as MusicalKey.index (0..23), or -1 if unknown. */
    val keyIndex: Int = -1,
    val keyConfidence: Float = 0f,
    val tuningCents: Float = 0f,
    /** 1..10, NaN if not analysed. */
    val energy: Float = Float.NaN,
    val timbre: FloatArray? = null,
    /** Which version of the key/energy analysis produced the fields above (0 = never analysed). */
    val featuresVersion: Int = 0,
    /** Downloaded by "Find new songs like this" rather than picked by the user. */
    val discovered: Boolean = false,
) {
    val analyzed get() = !loudnessDb.isNaN()
    val hasFeatures get() = analyzed && featuresVersion >= FEATURES_VERSION
    val key: MusicalKey? get() = if (keyIndex in 0..23) MusicalKey.fromIndex(keyIndex) else null

    /** What the next-song suggester needs to know about this song. */
    fun profile() = TrackProfile(id, title, artist, bpm, key, keyConfidence, energy, timbre, durationSec,
        if (tuningCents.isFinite()) tuningCents else 0f)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("artist", artist).put("source", source)
        .put("durationSec", durationSec).put("bpm", bpm).put("firstBeatSec", firstBeatSec)
        .put("loudnessDb", if (loudnessDb.isNaN()) JSONObject.NULL else loudnessDb).put("added", added)
        .put("keyIndex", keyIndex).put("keyConfidence", keyConfidence.toDouble()).put("tuningCents", tuningCents.toDouble())
        .put("energy", if (energy.isNaN()) JSONObject.NULL else energy.toDouble())
        .put("timbre", timbre?.let { t -> JSONArray().apply { t.forEach { put(it.toDouble()) } } } ?: JSONObject.NULL)
        .put("featuresVersion", featuresVersion).put("discovered", discovered)

    companion object {
        /** Bump when key/energy/timbre analysis changes so older songs get re-analysed. */
        const val FEATURES_VERSION = 1

        fun fromJson(o: JSONObject) = Track(
            o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("source"),
            o.optDouble("durationSec", 0.0), o.optDouble("bpm", 0.0), o.optDouble("firstBeatSec", 0.0),
            if (o.isNull("loudnessDb")) Double.NaN else o.optDouble("loudnessDb", Double.NaN), o.optLong("added"),
            keyIndex = o.optInt("keyIndex", -1),
            keyConfidence = o.optDouble("keyConfidence", 0.0).toFloat(),
            tuningCents = o.optDouble("tuningCents", 0.0).toFloat(),
            energy = if (o.isNull("energy") || !o.has("energy")) Float.NaN else o.optDouble("energy", Double.NaN).toFloat(),
            timbre = o.optJSONArray("timbre")?.let { a -> FloatArray(a.length()) { a.optDouble(it, 0.0).toFloat() } },
            featuresVersion = o.optInt("featuresVersion", 0),
            discovered = o.optBoolean("discovered", false),
        )
    }
}

/**
 * Downloaded songs, their analysis (BPM, beat grid, waveform), and imported Spotify lists, kept
 * under [baseDir] (the app's files folder on the phone, a MixDeck folder on the computer).
 */
class TrackStore(private val baseDir: File) {
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
        val updated = (get(t.id) ?: t).copy(bpm = a.bpm, firstBeatSec = a.firstBeatSec, loudnessDb = loudnessDb, durationSec = durationSec)
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
                TrackAnalysis(t.bpm, t.firstBeatSec, bps, arrs[0], arrs[1], arrs[2], t.key, t.keyConfidence)
            }
        }.getOrNull()
    }

    /** Stores the key/energy/timbre analysis for a song. */
    fun saveFeatures(t: Track, key: MusicalKey?, keyConfidence: Float, tuningCents: Float, energy: Float, timbre: FloatArray): Track {
        val updated = (get(t.id) ?: t).copy(
            keyIndex = key?.index ?: -1, keyConfidence = keyConfidence, tuningCents = tuningCents,
            energy = energy, timbre = timbre, featuresVersion = Track.FEATURES_VERSION,
        )
        save(updated)
        return updated
    }

    private val historyFile = File(baseDir, "history.json")

    /** Remembers that a song was loaded onto a deck (so suggestions don't repeat it). */
    @Synchronized fun markPlayed(id: String, now: Long = System.currentTimeMillis()) {
        val arr = JSONArray()
        (listOf(id to now) + history().filter { it.first != id }).take(100).forEach { (i, t) -> arr.put(JSONObject().put("id", i).put("t", t)) }
        runCatching { historyFile.writeText(arr.toString()) }
    }

    /** Recently loaded songs, newest first, as (id, time in ms). */
    @Synchronized fun history(): List<Pair<String, Long>> = runCatching {
        val arr = JSONArray(historyFile.readText())
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("id") to o.getLong("t") } }
    }.getOrDefault(emptyList())

    /** Songs loaded within the last [withinMs] (default 3 hours): a DJ set rarely repeats a song. */
    fun recentlyPlayed(withinMs: Long = 3 * 3600_000L, now: Long = System.currentTimeMillis()): Set<String> =
        history().filter { now - it.second <= withinMs }.map { it.first }.toSet()

    /** Remembers a beat grid the user fixed by hand (e.g. doubled the BPM). */
    fun updateGrid(id: String, bpm: Double, firstBeatSec: Double) {
        val t = get(id) ?: return
        // Energy is measured against the beat, so a corrected tempo means checking the song again.
        val tempoChanged = kotlin.math.abs(bpm - t.bpm) > 0.5
        save(t.copy(bpm = bpm, firstBeatSec = firstBeatSec, featuresVersion = if (tempoChanged) 0 else t.featuresVersion))
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
