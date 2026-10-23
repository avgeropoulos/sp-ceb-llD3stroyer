package com.bouzoukihero

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import com.bouzoukihero.core.BouzoukiTabs
import com.bouzoukihero.core.CustomSong
import com.bouzoukihero.core.Difficulty
import com.bouzoukihero.core.Json
import com.bouzoukihero.core.Synth
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** The player's YouTube songs (one JSON file each), best scores and settings. */
class Store(context: Context) {
    private val dir = File(context.filesDir, "songs").apply { mkdirs() }
    val prefs: SharedPreferences = context.getSharedPreferences("hero", Context.MODE_PRIVATE)

    fun songs(): List<CustomSong> = (dir.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".json") }
        .sortedBy { it.lastModified() }
        .mapNotNull { f -> try { CustomSong.fromJson(f.readText()) } catch (_: Exception) { null } }

    fun song(id: String): CustomSong? = try {
        CustomSong.fromJson(File(dir, "$id.json").readText())
    } catch (_: Exception) {
        null
    }

    fun save(song: CustomSong) {
        val f = File(dir, "${song.id}.json")
        val modified = if (f.exists()) f.lastModified() else System.currentTimeMillis()
        val tmp = File(dir, "${song.id}.tmp")
        tmp.writeText(song.toJson())
        tmp.renameTo(f)
        // Keep the song's place in the list when it's updated.
        f.setLastModified(modified)
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    fun best(key: String, d: Difficulty) = prefs.getInt("best_${key}_${d.name}", 0)
    fun stars(key: String, d: Difficulty) = prefs.getInt("stars_${key}_${d.name}", 0)

    fun saveResult(key: String, d: Difficulty, score: Int, stars: Int) {
        val e = prefs.edit()
        if (score > best(key, d)) e.putInt("best_${key}_${d.name}", score)
        if (stars > stars(key, d)) e.putInt("stars_${key}_${d.name}", stars)
        e.apply()
    }

    var difficulty: Difficulty
        get() = Difficulty.entries.firstOrNull { it.name == prefs.getString("difficulty", null) } ?: Difficulty.EASY
        set(v) { prefs.edit().putString("difficulty", v.name).apply() }

    /** Address of the Bouzouki Tabs web app, e.g. http://192.168.1.20:8000 */
    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(v) { prefs.edit().putString("server", v.trim()).apply() }

    /** How late the sound reaches your ears (e.g. Bluetooth headphones), in ms. */
    var latencyMs: Int
        get() = prefs.getInt("latency", 0)
        set(v) { prefs.edit().putInt("latency", v).apply() }
}

/** Talks to the Bouzouki Tabs web app (and YouTube, for video titles). */
object Net {
    fun normalizeServer(s: String): String {
        var u = s.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u
    }

    private fun request(url: String, method: String = "GET", body: String? = null, timeoutMs: Int = 15_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                // FastAPI puts the reason in "detail".
                val detail = try { (Json.parse(text) as? Map<*, *>)?.get("detail") as? String } catch (_: Exception) { null }
                throw IOException(detail ?: "The server answered $code")
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    /** Starts transcribing a video; returns the job id. */
    fun startJob(server: String, videoUrl: String, start: Double?, end: Double?): String {
        val reply = request("${normalizeServer(server)}/api/jobs", "POST", BouzoukiTabs.request(videoUrl, start, end))
        return (Json.parse(reply) as? Map<*, *>)?.get("id") as? String ?: throw IOException("Unexpected reply from the server")
    }

    fun job(server: String, id: String): BouzoukiTabs.Job =
        BouzoukiTabs.parseJob(request("${normalizeServer(server)}/api/jobs/$id"))

    /** The video's title from YouTube's oEmbed endpoint, or null. */
    fun videoTitle(videoUrl: String): String? = try {
        val reply = request("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(videoUrl, "UTF-8"), timeoutMs = 8000)
        (Json.parse(reply) as? Map<*, *>)?.get("title") as? String
    } catch (_: Exception) {
        null
    }
}

/** The miss "thunk" and the recording tick, synthesized at startup. */
class Sfx(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    @Volatile private var missId = 0
    @Volatile private var tickId = 0

    init {
        val dir = context.cacheDir
        thread(name = "sfx") {
            try {
                missId = pool.load(wav(File(dir, "miss.wav"), Synth.missSound()).path, 1)
                tickId = pool.load(wav(File(dir, "tick.wav"), Synth.tickSound()).path, 1)
            } catch (_: Exception) {
                // Sound effects are optional.
            }
        }
    }

    fun miss() { if (missId != 0) pool.play(missId, 0.5f, 0.5f, 1, 0, 1f) }
    fun tick() { if (tickId != 0) pool.play(tickId, 0.35f, 0.35f, 1, 0, 1f) }
    fun release() = pool.release()

    private fun wav(file: File, samples: FloatArray): File {
        val rate = Synth.RATE
        val len = samples.size * 2
        val buf = ByteBuffer.allocate(44 + len).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + len).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(len)
        for (v in samples) buf.putShort((v.coerceIn(-1f, 1f) * 32000f).toInt().toShort())
        FileOutputStream(file).use { it.write(buf.array()) }
        return file
    }
}
