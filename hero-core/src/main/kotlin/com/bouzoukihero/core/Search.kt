package com.bouzoukihero.core

import java.net.URLEncoder

/**
 * Searching YouTube. Without an API key the app reads YouTube's own search results
 * page (the data it embeds as `ytInitialData`); with a YouTube Data API key it uses
 * the official search API instead.
 */
object YouTubeSearch {
    class Video(val id: String, val title: String, val channel: String, val duration: String, val thumbnail: String)

    fun pageUrl(query: String) =
        "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8") + "&hl=en"

    fun apiUrl(query: String, key: String) =
        "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=25&videoEmbeddable=true&q=" +
            URLEncoder.encode(query, "UTF-8") + "&key=" + URLEncoder.encode(key, "UTF-8")

    fun thumbnailFor(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    /** Videos from a results page's HTML. Empty if the page has none (or changed shape). */
    fun parsePage(html: String): List<Video> {
        val data = embeddedJson(html, "ytInitialData") ?: return emptyList()
        val root = try { Json.parse(data) } catch (_: Json.ParseError) { return emptyList() }
        val out = LinkedHashMap<String, Video>()
        walk(root) { m ->
            (m["videoRenderer"] as? Map<*, *>)?.let { v -> fromRenderer(v)?.let { out.putIfAbsent(it.id, it) } }
            (m["lockupViewModel"] as? Map<*, *>)?.let { v -> fromLockup(v)?.let { out.putIfAbsent(it.id, it) } }
        }
        return out.values.toList()
    }

    /** Videos from a YouTube Data API v3 `search.list` reply. */
    fun parseApi(json: String): List<Video> {
        val m = Json.parse(json) as? Map<*, *> ?: return emptyList()
        (m["error"] as? Map<*, *>)?.let { throw IllegalStateException(it.str("message") ?: "YouTube API error") }
        return (m["items"] as? List<*>).orEmpty().mapNotNull { item ->
            val it = item as? Map<*, *> ?: return@mapNotNull null
            val id = (it["id"] as? Map<*, *>)?.str("videoId") ?: return@mapNotNull null
            val sn = it["snippet"] as? Map<*, *> ?: return@mapNotNull null
            Video(id, unescapeHtml(sn.str("title") ?: ""), unescapeHtml(sn.str("channelTitle") ?: ""), "", thumbnailFor(id))
        }
    }

    private fun fromRenderer(v: Map<*, *>): Video? {
        val id = v.str("videoId") ?: return null
        if (!YouTube.isId(id)) return null
        return Video(id, text(v["title"]) ?: return null, text(v["ownerText"]) ?: text(v["longBylineText"]) ?: "", text(v["lengthText"]) ?: "", thumbnailFor(id))
    }

    private fun fromLockup(v: Map<*, *>): Video? {
        if (v.str("contentType") != null && v.str("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = v.str("contentId") ?: return null
        if (!YouTube.isId(id)) return null
        val meta = ((v["metadata"] as? Map<*, *>)?.get("lockupMetadataViewModel") as? Map<*, *>) ?: return null
        val title = (meta["title"] as? Map<*, *>)?.str("content") ?: return null
        return Video(id, title, "", "", thumbnailFor(id))
    }

    /** YouTube text is either {"simpleText": "..."} or {"runs": [{"text": "..."}, ...]}. */
    private fun text(o: Any?): String? {
        val m = o as? Map<*, *> ?: return null
        m.str("simpleText")?.let { return it }
        val runs = m["runs"] as? List<*> ?: return null
        return runs.joinToString("") { (it as? Map<*, *>)?.str("text") ?: "" }.ifEmpty { null }
    }

    private fun walk(o: Any?, visit: (Map<*, *>) -> Unit) {
        when (o) {
            is Map<*, *> -> { visit(o); for (v in o.values) walk(v, visit) }
            is List<*> -> for (v in o) walk(v, visit)
        }
    }

    /** The JSON object assigned to [name] in one of the page's scripts. */
    fun embeddedJson(html: String, name: String): String? {
        var i = html.indexOf(name)
        while (i >= 0) {
            val brace = html.indexOf('{', i)
            val eq = html.indexOf('=', i)
            if (brace > 0 && eq in (i + 1) until brace && html.substring(eq + 1, brace).isBlank()) {
                return objectAt(html, brace)
            }
            i = html.indexOf(name, i + name.length)
        }
        return null
    }

    /** The balanced {...} starting at [start], skipping braces inside strings. */
    private fun objectAt(s: String, start: Int): String? {
        var depth = 0
        var inStr = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
            i++
        }
        return null
    }

    private fun unescapeHtml(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
}
