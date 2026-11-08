package com.mixdeck

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

data class SpotifyTrack(val title: String, val artist: String, val durationSec: Long)
data class SpotifyList(val url: String, val name: String, val tracks: List<SpotifyTrack>)

/**
 * Reads the track list of a Spotify playlist, album or track link from Spotify's public embed
 * page, so no Spotify login or developer account is needed. Spotify doesn't allow its audio to
 * be mixed, so each song is then matched to its audio on YouTube Music.
 */
object Spotify {
    private val linkRe = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(?:embed/)?(playlist|album|track)/([A-Za-z0-9]+)""")

    fun isSpotifyUrl(s: String) = linkRe.containsMatchIn(s) || s.contains("spotify.link/")

    fun fetch(link: String): SpotifyList {
        var url = Regex("""https?://\S+""").find(link)?.value ?: link
        if (url.contains("spotify.link/")) url = resolveRedirect(url)
        val m = linkRe.find(url) ?: throw IOException("That doesn't look like a Spotify playlist, album or track link")
        val (kind, id) = m.destructured
        val html = Net.getText("https://open.spotify.com/embed/$kind/$id", mapOf("Accept-Language" to "en"))
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw IOException("Spotify changed its page; couldn't read the track list")
        return parseEmbed(JSONObject(json), "https://open.spotify.com/$kind/$id")
    }

    internal fun parseEmbed(root: JSONObject, url: String): SpotifyList {
        val entity = findObject(root) { it.has("trackList") || (it.optString("type") == "track" && it.has("name")) }
            ?: throw IOException("Couldn't find any songs at that link")
        val name = entity.optString("name").ifEmpty { entity.optString("title") }
        val tracks = mutableListOf<SpotifyTrack>()
        val list = entity.optJSONArray("trackList")
        if (list != null) {
            for (i in 0 until list.length()) {
                val t = list.optJSONObject(i) ?: continue
                val title = t.optString("title").ifEmpty { t.optString("name") }
                val artist = t.optString("subtitle").ifEmpty { artistsOf(t) }
                if (title.isNotEmpty()) tracks += SpotifyTrack(title, artist.replace(" ", " "), t.optLong("duration") / 1000)
            }
        } else {
            tracks += SpotifyTrack(name, artistsOf(entity), entity.optLong("duration") / 1000)
        }
        return SpotifyList(url, name.ifEmpty { "Spotify" }, tracks)
    }

    private fun artistsOf(o: JSONObject): String {
        val a = o.optJSONArray("artists") ?: return ""
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString(", ")
    }

    private fun findObject(v: Any?, pred: (JSONObject) -> Boolean): JSONObject? = when (v) {
        is JSONObject -> if (pred(v)) v else v.keys().asSequence().firstNotNullOfOrNull { findObject(v.opt(it), pred) }
        is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { findObject(v.opt(it), pred) }
        else -> null
    }

    private fun resolveRedirect(url: String): String {
        val c = Net.open(url)
        c.instanceFollowRedirects = false
        try {
            c.responseCode
            return c.getHeaderField("Location") ?: url
        } finally { c.disconnect() }
    }

    /** Finds the YouTube Music recording that best matches a Spotify song. */
    fun matchOnYouTube(t: SpotifyTrack): YtResult {
        val firstArtist = t.artist.split(",").first().trim()
        val results = YouTube.Search("$firstArtist ${t.title}", songsOnly = true).nextPage().take(8)
        if (results.isEmpty()) throw IOException("Couldn't find \"${t.title}\" on YouTube")
        val words = normalize(t.title).split(' ').filter { it.length > 1 }.toSet()
        return results.maxBy { r ->
            val titleWords = normalize(r.title).split(' ').toSet()
            val overlap = if (words.isEmpty()) 0.0 else words.count { it in titleWords }.toDouble() / words.size
            val artistHit = if (normalize(r.artist).contains(normalize(firstArtist))) 1.0 else 0.0
            val durDiff = if (t.durationSec > 0 && r.durationSec > 0) abs(t.durationSec - r.durationSec) else 30
            overlap * 3 + artistHit * 2 - (durDiff.coerceAtMost(60) / 15.0)
        }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N} ]"""), " ").replace(Regex(" +"), " ").trim()
}
