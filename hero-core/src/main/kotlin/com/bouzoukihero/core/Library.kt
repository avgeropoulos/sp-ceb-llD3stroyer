package com.bouzoukihero.core

/**
 * A song the player added from YouTube. Its chart is heard by the app itself
 * ([SOURCE_AUTO]), comes from the Bouzouki Tabs app ([SOURCE_TABS]), or is tapped
 * along to the video ([SOURCE_RECORDED]).
 */
data class CustomSong(
    val id: String,
    val title: String,
    val videoId: String,
    val notes: List<SourceNote> = emptyList(),
    val source: String = SOURCE_NONE,
    val tempo: Double = 0.0,
    /** Part of the video to transcribe/play, in seconds (0 / null = all of it). */
    val start: Double = 0.0,
    val end: Double? = null,
) {
    val hasChart get() = notes.isNotEmpty()

    /** Charts tapped in by hand have lanes, not real pitches, so no note names. */
    val labelled get() = source == SOURCE_TABS || source == SOURCE_AUTO

    fun toJson(): String = Json.write(
        mapOf(
            "id" to id,
            "title" to title,
            "videoId" to videoId,
            "source" to source,
            "tempo" to tempo,
            "start" to start,
            "end" to end,
            "notes" to notes.map {
                mapOf(
                    "t" to Math.round(it.time * 1000) / 1000.0,
                    "d" to Math.round(it.duration * 1000) / 1000.0,
                    "p" to it.pitch,
                    "tr" to it.tremolo,
                )
            },
        )
    )

    companion object {
        const val SOURCE_NONE = "none"
        const val SOURCE_TABS = "tabs"
        const val SOURCE_RECORDED = "recorded"

        /** Heard by the app itself while the video played ([LiveTranscriber]). */
        const val SOURCE_AUTO = "auto"

        fun fromJson(text: String): CustomSong {
            val m = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Not a song")
            val notes = (m["notes"] as? List<*>).orEmpty().mapNotNull { n ->
                val o = n as? Map<*, *> ?: return@mapNotNull null
                SourceNote(o.num("t") ?: return@mapNotNull null, o.num("d") ?: 0.0, (o.num("p") ?: 0.0).toInt(), o["tr"] == true)
            }
            return CustomSong(
                id = m.str("id") ?: throw Json.ParseError("Song has no id"),
                title = m.str("title") ?: "YouTube song",
                videoId = m.str("videoId") ?: throw Json.ParseError("Song has no video"),
                notes = notes,
                source = m.str("source") ?: SOURCE_NONE,
                tempo = m.num("tempo") ?: 0.0,
                start = m.num("start") ?: 0.0,
                end = m.num("end"),
            )
        }
    }
}

object YouTube {
    private val idRe = Regex("^[A-Za-z0-9_-]{11}$")
    private val patterns = listOf(
        Regex("""(?:youtube\.com|youtube-nocookie\.com)/(?:watch\?(?:.*&)?v=|embed/|shorts/|live/|v/)([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
    )

    /** The 11-character video id in a YouTube link (or a bare id), or null. */
    fun videoId(link: String): String? {
        val s = link.trim()
        if (idRe.matches(s)) return s
        for (p in patterns) p.find(s)?.let { return it.groupValues[1] }
        return null
    }

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    fun isId(s: String) = idRe.matches(s)
}

/** Reads the result of a Bouzouki Tabs job (`GET /api/jobs/{id}` on its web app). */
object BouzoukiTabs {
    class Job(val status: String, val stage: String, val progress: Double, val error: String?, val result: Map<*, *>?)

    fun parseJob(text: String): Job {
        val m = Json.parse(text) as? Map<*, *> ?: throw Json.ParseError("Unexpected reply")
        return Job(
            status = m.str("status") ?: "unknown",
            stage = m.str("stage") ?: "",
            progress = m.num("progress") ?: 0.0,
            error = m.str("error"),
            result = m["result"] as? Map<*, *>,
        )
    }

    /** The melody notes from a finished job's result. */
    fun notes(result: Map<*, *>): List<SourceNote> =
        (result["notes"] as? List<*>).orEmpty().mapNotNull { n ->
            val o = n as? Map<*, *> ?: return@mapNotNull null
            val start = o.num("start") ?: return@mapNotNull null
            val end = o.num("end") ?: start
            val midi = o.num("midi") ?: return@mapNotNull null
            SourceNote(start, (end - start).coerceAtLeast(0.0), midi.toInt(), o["tremolo"] == true)
        }

    fun tempo(result: Map<*, *>): Double = result.num("tempo") ?: 0.0

    /** The request body for `POST /api/jobs`. */
    fun request(url: String, start: Double?, end: Double?): String = Json.write(
        buildMap {
            put("url", url)
            if (start != null && start > 0) put("start", start)
            if (end != null) put("end", end)
            put("separate", true)
        }
    )
}

/** Parses "1:30", "90" or "1:02:03" into seconds; blank gives null. */
fun parseClock(text: String): Double? {
    val s = text.trim()
    if (s.isEmpty()) return null
    var total = 0.0
    for (part in s.split(':')) {
        val v = part.trim().toDoubleOrNull() ?: throw IllegalArgumentException("'$text' isn't a time like 1:30")
        total = total * 60 + v
    }
    return total
}
