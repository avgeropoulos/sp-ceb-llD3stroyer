package com.mixdeck

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Finds new songs on YouTube that would plausibly come next after a song.
 *
 * Sources for one video ("a round", about 0.7-1.5 s, the first two asked in parallel):
 *  - YouTube Music radio for the song (playlist RDAMVM<id>, asked from music.youtube.com directly,
 *    because NewPipe has no extractor for it). The best source: same genre, era and mood, ~50 songs.
 *  - The YouTube "Mix" for the video (playlist RD<id>, NewPipe's mix extractor). Good, a bit more
 *    same-artist, and drifts towards kids' hits for big pop songs. It only exists for videos that
 *    play here, so its failure is the first hint that the video is blocked or removed.
 *  - Only when the mix failed or both lists are short: the watch page (NewPipe stream extractor).
 *    Its failure confirms the video can't be played here, in which case the radio for it is junk
 *    too (YouTube Music then serves whatever is popular locally); its related videos are noisy
 *    (long mixes, unrelated videos), so they only fill gaps, and only with things that look like songs.
 * Results are merged by weighted reciprocal-rank fusion (songs several sources agree on rise),
 * junk is dropped (shorts, mixes over 10 min, live, karaoke, covers, nightcore/slowed/8D, ...),
 * every upload of the same song is collapsed into its best upload, the seed song (and covers of
 * it) is removed, and no artist appears more than [maxPerArtist] times unless the list would
 * otherwise be short.
 *
 * Fallbacks: if the video can't be played here (blocked label video, removed upload) or gives too
 * few songs, another upload of the same song is found by search and used instead; as a last resort
 * the list is topped up with other songs by the same artist.
 *
 * All calls block (network), so call them off the UI thread. They throw [IOException] only when
 * nothing at all could be reached (offline, rate limited); otherwise they return what was found,
 * possibly an empty list.
 */
object Discovery {
    /** Who sings a song and what it is called, cleaned for display ("Nelly", "Ride Wit Me"). */
    data class SongId(val artist: String, val title: String)

    /** Shortest and longest song kept, in seconds: drops shorts and clips, and mixes/compilations. */
    @Volatile var minSec = 90L
    @Volatile var maxSec = 600L

    /** At most this many songs by one artist (counting the seed's artist), unless the list would be short. */
    @Volatile var maxPerArtist = 2

    /** Give up on a round of sources after this long. */
    @Volatile var timeoutMs = 12_000L

    /**
     * Also ask the watch page for related videos when the radio and mix already worked. Off by
     * default: it costs a heavy extra request (the player) and its videos are mostly noise.
     */
    @Volatile var alwaysUseWatchPage = false

    /** How long a song's suggestions are reused before asking YouTube again. */
    @Volatile var cacheMs = 30 * 60_000L

    /** Optional trace of what happened (sources, timings, why songs were dropped). */
    @Volatile var log: ((String) -> Unit)? = null

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "discovery").apply { isDaemon = true } }

    // ---------------------------------------------------------------- public API

    /** Songs that would plausibly come next after this YouTube video, best first, junk filtered, duplicates and the seed song removed. */
    fun related(videoUrl: String, limit: Int = 20): List<YtResult> {
        val id = YouTube.videoId(videoUrl) ?: throw IllegalArgumentException("Not a YouTube video link: $videoUrl")
        YouTube.init()
        return cached("v:$id:$limit") { discover(id, null, limit, null) }
    }

    /** For songs we only know by title/artist (e.g. a file from the phone): find the song on YouTube, then return related(). */
    fun relatedTo(title: String, artist: String, limit: Int = 20): List<YtResult> {
        YouTube.init()
        val hint = SongId(artist.trim(), stripDecorations(title.trim().replace(TRACK_NO, "")))
        return cached("t:${key(hint.artist)}|${key(hint.title)}:$limit") { relatedToUncached(hint, limit) }
    }

    /** Forgets cached suggestions (e.g. after the user asks for fresh ones). */
    fun clearCache() = synchronized(resultCache) { resultCache.clear() }

    /** The song in a result, for display and for comparing with the library: artist and title without "(Official Video)", "ft. ..." etc. */
    fun songOf(r: YtResult): SongId = synchronized(parsedCache) { parsedCache[r.id] }?.let { SongId(it.artist, it.song) }
        ?: parse(r.title, r.artist, null, emptyMap(), emptyList(), emptyList()).let { SongId(it.artist, it.song) }

    /** True if two songs are the same song (any upload, any language/script of the same title, covers included). */
    fun sameSong(a: SongId, b: SongId): Boolean = same(parsedHint(a), parsedHint(b))

    /** Title without decorations: "Nelly - Grillz ft. Paul Wall (Official Video) [HD]" -> "Nelly - Grillz". */
    fun stripDecorations(title: String): String = cleanTitle(title)

    /** Comparison key for song or artist names: lower case, no accents or punctuation, Greek transliterated. */
    fun key(s: String): String = skeleton(normalize(s))

    /** Why a result is not a usable song (short, compilation, live, karaoke, cover, slowed, ...), or null if it is fine. */
    fun junkReason(r: YtResult): String? = junkReason(r.title, r.durationSec, false, null)

    // ---------------------------------------------------------------- cache

    private val resultCache = LinkedHashMap<String, Pair<Long, List<YtResult>>>()
    private val TRACK_NO = Regex("""^\s*\d{1,3}\s*[-._)]\s*""")

    private fun cached(k: String, compute: () -> List<YtResult>): List<YtResult> {
        val now = System.currentTimeMillis()
        synchronized(resultCache) { resultCache[k]?.let { (t, v) -> if (now - t < cacheMs) return v } }
        val v = compute()
        if (v.isNotEmpty()) synchronized(resultCache) {
            resultCache[k] = now to v
            if (resultCache.size > 64) resultCache.remove(resultCache.keys.first())
        }
        return v
    }

    private fun relatedToUncached(hint: SongId, limit: Int): List<YtResult> {
        val uploads = findSong(hint)
        trace("relatedTo '${hint.artist} - ${hint.title}': ${uploads.size} matching uploads" +
            uploads.take(3).joinToString("") { "\n   ${it.id} ${it.title} | ${it.artist}" })
        if (uploads.isEmpty()) {
            // Not on YouTube under that name: other songs by the artist are still a sensible guess.
            if (hint.artist.isBlank()) return emptyList()
            val cands = runCatching { artistSongs(hint.artist) }.getOrElse { throw IOException("YouTube search failed", it) }
            return rank(cands, emptySet(), parsedHint(hint), limit)
        }
        return discover(uploads.first().id, hint, limit, uploads.drop(1))
    }

    // ---------------------------------------------------------------- the search

    private enum class Src(val weight: Double) { RADIO(1.0), MIX(0.7), RELATED(0.35), ARTIST(0.15) }

    /** A song offered by one source, at a rank (1 = first) in that source's list; [w] scales the source's weight. */
    private data class Cand(
        val r: YtResult, val src: Src, val rank: Int,
        val channel: String, val ytmArtist: String? = null, val videoType: String? = null, val short: Boolean = false,
        val w: Double = 1.0,
    )

    /** One video's sources. Strong = a real radio or mix came back (not just the noisy related videos). */
    private class Round(val cands: List<Cand>, val seed: YtResult?, val unavailable: Boolean, val strong: Boolean, val failures: List<Throwable>, val asked: Int)

    private fun discover(seedId: String, hint: SongId?, limit: Int, knownUploads: List<YtResult>?): List<YtResult> {
        val t0 = System.nanoTime()
        val rounds = ArrayList<Round>()
        val extra = ArrayList<Cand>()
        val seedIds = mutableSetOf(seedId)
        val failures = ArrayList<Throwable>()
        var asked = 0
        val enough = minOf(limit, 12)
        // A video that can't be played here gets a radio of whatever is popular locally: ignore it.
        // Once some upload gave a real radio/mix, the weak rounds' (noisy) songs count for less.
        fun candidates(): List<Cand> {
            val anyStrong = rounds.any { it.strong }
            return rounds.flatMap { r ->
                val c = if (r.unavailable) r.cands.filter { it.src != Src.RADIO } else r.cands
                if (anyStrong && !r.strong) c.map { it.copy(w = 0.3) } else c
            } + extra
        }

        val first = round(seedId)
        rounds += first; failures += first.failures; asked += first.asked
        val seed = parsedSeed(hint, first.seed, candidates())
        var result = rank(candidates(), seedIds, seed, limit)

        if (!first.strong || result.size < enough) {
            trace(if (first.unavailable) "seed $seedId is not playable here: trying other uploads" else "weak sources (${result.size} songs): trying other uploads")
            val uploads = knownUploads ?: runCatching { otherUploads(seed, seedId) }.getOrElse { failures += it; emptyList() }
            for (u in uploads.filter { it.id !in seedIds }.take(3)) {
                if (elapsedMs(t0) > timeoutMs * 2) break
                seedIds += u.id
                val r = round(u.id)
                rounds += r; failures += r.failures; asked += r.asked
                result = rank(candidates(), seedIds, seed, limit)
                if (r.strong && result.size >= enough) break
            }
        }
        if (result.size < limit && seed.artist.isNotBlank()) {
            asked++
            runCatching { artistSongs(seed.artist) }.onSuccess { extra += it }.onFailure { failures += it }
            result = rank(candidates(), seedIds, seed, limit)
        }
        trace("done in ${elapsedMs(t0)} ms: ${result.size} songs, ${failures.size}/$asked sources failed")
        if (result.isEmpty() && asked > 0 && failures.size >= asked) {
            throw IOException("Couldn't reach YouTube for suggestions", failures.firstOrNull())
        }
        return result
    }

    /** Asks the radio, the mix and the watch page's related videos for one video, in parallel. */
    private fun round(id: String): Round {
        val t0 = System.nanoTime()
        val deadline = t0 + timeoutMs * 1_000_000
        val radioF = pool.submit(Callable { ytmRadio(id) })
        val mixF = pool.submit(Callable { mix(id) })
        var relF = if (alwaysUseWatchPage) pool.submit(Callable { watchRelated(id) }) else null
        val radio = await(radioF, deadline)
        val mix = await(mixF, deadline)
        val radioN = radio.getOrNull()?.second?.size ?: 0
        val mixN = mix.getOrNull()?.size ?: 0
        // A mix exists only for videos that play here. Without one, the watch page tells us whether
        // the video is playable at all (if not, its radio is junk) and adds its related videos.
        val needWatchPage = mix.isFailure || (radioN < 15 && mixN < 10)
        if (relF == null && needWatchPage) relF = pool.submit(Callable { watchRelated(id) })
        val rel = relF?.let { await(it, if (needWatchPage) deadline else minOf(deadline, System.nanoTime() + 1_500_000_000L)) }
        val unavailable = rel?.exceptionOrNull() is ContentNotAvailableException
        val asked = listOfNotNull(radio, mix, rel)
        val failures = asked.mapNotNull { it.exceptionOrNull() }.filter { it !is ContentNotAvailableException && it !is TimeoutException }
        trace("round $id in ${elapsedMs(t0)} ms: radio ${desc(radio)}, mix ${desc(mix)}, watch page ${rel?.let { desc(it) } ?: "not needed"}" +
            if (unavailable) " -> not playable here" else "")
        val seed = rel?.getOrNull()?.first ?: radio.getOrNull()?.first ?: mix.getOrNull()?.firstOrNull { it.r.id == id }?.r
        val strong = !unavailable && (radioN >= 15 || mixN >= 10)
        val cands = radio.getOrNull()?.second.orEmpty() + mix.getOrNull().orEmpty() + rel?.getOrNull()?.second.orEmpty()
        return Round(cands, seed, unavailable, strong, failures, asked.size)
    }

    private fun <T> await(f: Future<T>, deadlineNanos: Long): Result<T> = try {
        Result.success(f.get(maxOf(1L, (deadlineNanos - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS))
    } catch (e: ExecutionException) {
        Result.failure(e.cause ?: e)
    } catch (e: TimeoutException) {
        f.cancel(true); Result.failure(e)
    } catch (e: InterruptedException) {
        f.cancel(true); Thread.currentThread().interrupt(); Result.failure(e)
    }

    private fun desc(r: Result<*>): String = r.fold(
        { v -> val n = when (v) { is Pair<*, *> -> (v.second as? List<*>)?.size; is List<*> -> v.size; else -> null }; "${n ?: "?"} items" },
        { e -> "failed (${e.javaClass.simpleName}: ${e.message?.take(80)})" })

    // ---------------------------------------------------------------- sources

    /** YouTube Music's radio for a video: (the seed as YouTube Music describes it, the queue after it). */
    private fun ytmRadio(id: String): Pair<YtResult?, List<Cand>> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX").put("clientVersion", ytmVersion())
                .put("hl", "en").put("gl", runCatching { NewPipe.getPreferredContentCountry().countryCode }.getOrNull() ?: "US")))
            .put("videoId", id).put("playlistId", "RDAMVM$id")
            .put("isAudioOnly", true).put("enablePersistentPlaylistPanel", true)
        val headers = mapOf(
            "Content-Type" to listOf("application/json"),
            "Origin" to listOf("https://music.youtube.com"),
            "Referer" to listOf("https://music.youtube.com/"),
            "X-YouTube-Client-Name" to listOf("67"),
            "X-YouTube-Client-Version" to listOf(ytmVersion()),
        )
        val resp = NewPipe.getDownloader().post("https://music.youtube.com/youtubei/v1/next?prettyPrint=false", headers, body.toString().toByteArray(Charsets.UTF_8))
        if (resp.responseCode() !in 200..299) throw IOException("YouTube Music radio: HTTP ${resp.responseCode()}")
        val root = JSONObject(resp.responseBody())
        val panels = mutableListOf<JSONObject>()
        collect(root, "playlistPanelRenderer", panels)
        val contents = panels.firstOrNull()?.optJSONArray("contents") ?: return null to emptyList()
        var seed: YtResult? = null
        val out = mutableListOf<Cand>()
        var rank = 0
        for (i in 0 until contents.length()) {
            val renderers = mutableListOf<JSONObject>()
            collect(contents.opt(i), "playlistPanelVideoRenderer", renderers) // a song and its video counterpart share a rank
            if (renderers.isEmpty()) continue
            var counted = false
            for (v in renderers) {
                val vid = v.optString("videoId")
                if (vid.length != 11) continue
                val title = runs(v.optJSONObject("title"))
                val byline = v.optJSONObject("longBylineText") ?: v.optJSONObject("shortBylineText")
                val artist = bylineArtist(byline)
                val dur = parseDuration(runs(v.optJSONObject("lengthText")))
                val type = v.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                    ?.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")
                    ?.optString("musicVideoType")?.removePrefix("MUSIC_VIDEO_TYPE_")?.ifBlank { null }
                val r = YtResult("https://www.youtube.com/watch?v=$vid", vid, title, artist, dur)
                if (vid == id) { if (seed == null) seed = r; continue }
                if (!counted) { rank++; counted = true }
                out += Cand(r, Src.RADIO, rank, artist, ytmArtist = if (type == "ATV" || type == "OMV") artist else null, videoType = type)
            }
        }
        return seed to out
    }

    /** YouTube's "Mix" for a video (first page, ~25 videos). */
    private fun mix(id: String): List<Cand> {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id&list=RD$id")
        return info.relatedItems.mapIndexedNotNull { i, item -> streamCand(item, Src.MIX, i + 1) }
    }

    /** The watch page: (the video itself, its related videos). Throws ContentNotAvailableException when it can't be played here. */
    private fun watchRelated(id: String): Pair<YtResult, List<Cand>> {
        val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$id")
        ex.fetchPage()
        val seed = YtResult("https://www.youtube.com/watch?v=$id", id, ex.name ?: "", cleanChannel(ex.uploaderName ?: ""), ex.length)
        val items = runCatching { ex.relatedItems?.items.orEmpty() }.getOrDefault(emptyList())
        var rank = 0
        return seed to items.mapNotNull { if (it is StreamInfoItem) streamCand(it, Src.RELATED, ++rank) else null }
    }

    /** Other songs by the artist, a weak last resort. */
    private fun artistSongs(artist: String): List<Cand> {
        val items = search(artist, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
            .ifEmpty { search(artist, YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS) }
        val a = key(artist)
        return items.mapIndexedNotNull { i, item -> streamCand(item, Src.ARTIST, i + 1) }
            .filter { a.isEmpty() || containsWords(key(it.r.title + " " + it.channel), a) }
    }

    private fun streamCand(item: Any?, src: Src, rank: Int): Cand? {
        if (item !is StreamInfoItem) return null
        val id = YouTube.videoId(item.url ?: "") ?: return null
        val channel = item.uploaderName ?: ""
        val r = YtResult(item.url, id, item.name ?: "", cleanChannel(channel), item.duration)
        return Cand(r, src, rank, channel, short = item.isShortFormContent)
    }

    private fun search(q: String, filter: String): List<StreamInfoItem> {
        val ex = ServiceList.YouTube.getSearchExtractor(q, listOf(filter), "")
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>()
    }

    /** Uploads of a song found by search, best match first (YouTube Music songs, then music videos, then all videos). */
    private fun findSong(hint: SongId): List<YtResult> {
        val q = listOf(hint.artist, hint.title).filter { it.isNotBlank() }.joinToString(" ")
        if (q.isBlank()) return emptyList()
        val p = parsedHint(hint)
        for (filter in listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS, YoutubeSearchQueryHandlerFactory.VIDEOS)) {
            val items = runCatching { search(q, filter) }.getOrElse { if (filter == YoutubeSearchQueryHandlerFactory.VIDEOS) throw IOException("YouTube search failed", it) else emptyList() }
            val scored = items.mapIndexedNotNull { i, item ->
                val c = streamCand(item, Src.ARTIST, i + 1) ?: return@mapIndexedNotNull null
                if (junkReason(c.r.title, c.r.durationSec, c.short, null) != null) return@mapIndexedNotNull null
                val text = key(c.r.title + " " + c.channel)
                val titleCov = coverage(key(p.song), text)
                val artistCov = if (p.artist.isBlank()) 1.0 else
                    (listOf(p.artist) + p.artist.split(ARTIST_SPLIT)).filter { it.isNotBlank() }.maxOf { coverage(key(it), text) }
                if (titleCov < 0.6 || artistCov < 0.5) return@mapIndexedNotNull null
                val q2 = uploadQuality(c, parse(c.r.title, c.channel, null, emptyMap(), listOf(hint.artist), listOf(p.songKey)))
                val views = if (item.viewCount > 0) Math.log10(item.viewCount.toDouble()) else 3.0
                c.r to (2 * titleCov + artistCov + 0.3 * q2 + 0.1 * views - 0.03 * i)
            }
            if (scored.isNotEmpty()) return scored.sortedByDescending { it.second }.map { it.first }
        }
        return emptyList()
    }

    /** Other uploads of the seed song, most viewed first: their radios work when the seed's doesn't. */
    private fun otherUploads(seed: Parsed, seedId: String): List<YtResult> {
        if (seed.song.isBlank()) return emptyList()
        val q = listOf(seed.artist, seed.song).filter { it.isNotBlank() }.joinToString(" ")
        val items = search(q, YoutubeSearchQueryHandlerFactory.VIDEOS)
        return items.mapIndexedNotNull { i, item ->
            val c = streamCand(item, Src.ARTIST, i + 1) ?: return@mapIndexedNotNull null
            if (c.r.id == seedId || junkReason(c.r.title, c.r.durationSec, c.short, null) != null) return@mapIndexedNotNull null
            val p = parse(c.r.title, c.channel, null, emptyMap(), listOf(seed.artist), listOf(seed.songKey))
            if (!same(seed, p) && coverage(seed.songKey, key(c.r.title)) < 0.99) return@mapIndexedNotNull null
            c.r to item.viewCount
        }.sortedByDescending { it.second }.map { it.first }
    }

    // ---------------------------------------------------------------- merging and ranking

    private class Group(var best: Cand, var bestQ: Double, val parsed: Parsed) {
        var shown = parsed // the parse of [best], for display and the per-artist limit
        val bySrc = HashMap<Src, Double>() // best score per source
        var lyricOnly = true
    }

    private fun rank(cands: List<Cand>, seedIds: Set<String>, seed: Parsed, limit: Int): List<YtResult> {
        // 1. junk
        val ok = ArrayList<Cand>()
        val dropped = LinkedHashMap<String, Int>()
        for (c in cands) {
            if (c.r.id in seedIds) continue
            val why = junkReason(c.r.title, c.r.durationSec, c.short, c.videoType)
                ?: if (c.src == Src.RELATED && !looksLikeSong(c) && cands.none { it.src != Src.RELATED && it.r.id == c.r.id }) "not a song" else null
            if (why != null) { dropped[why] = (dropped[why] ?: 0) + 1; continue }
            ok += c
        }
        // 2. which title parts are artists: names that recur across many titles
        val freq = partFrequencies(ok.map { it.r.id to it.r.title }.distinctBy { it.first }.map { it.second })
        // The seed's own title is re-read now that we know which names recur (= artists) around it.
        val seedP = if (seed.fromTitle != null) parse(seed.fromTitle, seed.channel, null, freq, emptyList(), emptyList()) else seed
        val hints = listOfNotNull(seedP.artist.ifBlank { null })
        val songHints = listOfNotNull(seedP.songKey.takeIf { it.replace(" ", "").length >= 3 && it != seedP.artistKey })
        val parsedById = HashMap<String, Parsed>()
        for (c in ok) parsedById.getOrPut(c.r.id) { parse(c.r.title, c.channel, c.ytmArtist, freq, hints, songHints) }
        // 3. the seed song and its covers
        var strict = 0
        var loose = 0
        val keep = ok.filter { c ->
            val p = parsedById[c.r.id]!!
            when {
                same(seedP, p) -> { strict++; false }
                seedLooseMatch(seedP, p, freq, seed.artistGiven) -> { loose++; false }
                else -> true
            }
        }
        val afterSeed = if (loose > ok.size * 0.4) ok.filter { !same(seedP, parsedById[it.r.id]!!) } else keep
        // 4. one entry per song, keeping its best upload
        val groups = ArrayList<Group>()
        for (c in afterSeed.sortedBy { it.rank.toDouble() / (it.src.weight * it.w) }) {
            val p = parsedById[c.r.id]!!
            val q = uploadQuality(c, p)
            val g = groups.firstOrNull { same(it.parsed, p) }
                ?: Group(c, q, p).also { groups += it }
            val sc = c.src.weight * c.w / (8.0 + c.rank)
            if (sc > (g.bySrc[c.src] ?: 0.0)) g.bySrc[c.src] = sc
            if (!isLyric(c.r.title)) g.lyricOnly = false
            if (q > g.bestQ) { g.best = c; g.bestQ = q; g.shown = p }
        }
        val scored = groups.map { g ->
            var s = g.bySrc.values.sum()
            if (g.lyricOnly) s *= 0.85
            g to s
        }.sortedByDescending { it.second }
        // 5. variety: not too many songs by one artist (the seed's artist counts as already played once).
        // "Καζαντζίδης", "Στέλιος Καζαντζίδης" and "ΚΑΖΑΝΤΖΙΔΗΣ ΣΤΕΛΙΟΣ" are one artist.
        val canon = ArrayList<Set<String>>()
        fun artistOf(k: String): String {
            if (k.isEmpty()) return k
            val w = k.split(' ').filter { it.length >= 2 }.toSet()
            if (w.isEmpty()) return k
            val i = canon.indexOfFirst { c -> c.containsAll(w) || w.containsAll(c) }
            if (i >= 0) return "#$i"
            canon += w
            return "#${canon.size - 1}"
        }
        val perArtist = HashMap<String, Int>()
        artistOf(seedP.artistKey).takeIf { it.isNotEmpty() }?.let { perArtist[it] = 1 }
        val picked = ArrayList<Group>()
        val skipped = ArrayList<Group>()
        for ((g, _) in scored) {
            val a = artistOf(g.shown.artistKey)
            val n = perArtist[a] ?: 0
            if (a.isNotEmpty() && n >= maxPerArtist) { skipped += g; continue }
            perArtist[a] = n + 1
            picked += g
            if (picked.size >= limit) break
        }
        if (picked.size < limit) picked += skipped.take(limit - picked.size)
        val out = picked.take(limit)
        for (g in out) rememberParse(g.best.r.id, g.shown)
        if (dropped.isNotEmpty() || strict + loose > 0) trace("rank: ${cands.size} candidates, dropped $dropped, seed song x${strict + loose}, ${groups.size} distinct songs")
        return out.map { it.best.r }
    }

    private fun seedLooseMatch(seed: Parsed, p: Parsed, freq: Map<String, Int>, trusted: Boolean): Boolean {
        val s = seed.songKey
        if (s.replace(" ", "").length < 5 || s == seed.artistKey) return false
        // An untrusted seed title might have artist and song mixed up: never wipe out everything by an artist.
        if (!trusted && (freq[s] ?: 0) >= 3 && (freq[s] ?: 0) > (freq[seed.artistKey] ?: 0)) return false
        return containsWords(p.fullKey, s)
    }

    /** How good an upload is to download and mix: album audio > official audio > artist's channel > anything > lyric video. */
    private fun uploadQuality(c: Cand, p: Parsed): Double {
        val low = lower(c.r.title)
        var q = 1.0
        if (c.videoType == "ATV") q = 3.0
        if (c.channel.endsWith(" - Topic")) q = maxOf(q, 2.8)
        if (OFFICIAL_AUDIO.containsMatchIn(low)) q = maxOf(q, 2.6)
        val ch = compact(cleanChannel(c.channel))
        val ar = compact(p.artist)
        if (c.videoType == "OMV" || c.channel.lowercase(Locale.ROOT).endsWith("vevo") ||
            (ch.isNotEmpty() && ar.isNotEmpty() && (ch == ar || ch.startsWith(ar) || ar.startsWith(ch)))) q = maxOf(q, 2.2)
        if ("official" in low) q = maxOf(q, 1.8)
        if (isLyric(c.r.title)) q = minOf(q, 0.6)
        if (OTHER_VERSION.containsMatchIn(low)) q = minOf(q, 0.8)
        return q
    }

    private fun looksLikeSong(c: Cand): Boolean {
        val t = c.r.title
        if (c.channel.endsWith(" - Topic") || c.channel.lowercase(Locale.ROOT).endsWith("vevo")) return true
        if (SONGISH_PUNCT.containsMatchIn(t)) return true
        if (splitParts(cleanTitle(t)).size >= 2) return true
        return SONGISH_WORDS.containsMatchIn(lower(t))
    }

    private val OFFICIAL_AUDIO = rx("""official\s+(hd\s+)?audio|audio\s+oficial|official\s+visuali[sz]er""")
    private val OTHER_VERSION = rx("""\b(acoustic|piano\s+version|orchestral|reggae\s+version)\b""")
    private val SONGISH_PUNCT = Regex("""\s[-–—|]\s|["“”«»]""")
    private val SONGISH_WORDS = rx("""\b(official|audio|lyrics?|visuali[sz]er|m/?v|music\s+video|remix)\b""")

    /** A regex whose \\b also works next to Greek and other non-ASCII letters, on the JVM and on Android alike. */
    private fun rx(pattern: String, ignoreCase: Boolean = false): Regex {
        val b = """(?:(?<=[\p{L}\p{N}])(?![\p{L}\p{N}])|(?<![\p{L}\p{N}])(?=[\p{L}\p{N}]))"""
        val p = pattern.replace("""\b""", b)
        return if (ignoreCase) Regex(p, RegexOption.IGNORE_CASE) else Regex(p)
    }

    // ---------------------------------------------------------------- junk

    private val ALTERED = rx("""\b(nightcore|daycore|slowed|reverb|sped\s*up|speed\s*up|spedup|8d|8\s*d\s*audio|16d|bass\s*boost(ed)?|pitched|chipmunk|tiktok\s+version|chopped|screwed)\b""")
    private val KARAOKE = rx("""\b(karaoke|instrumental|backing\s+track|minus\s+one|acapella|a\s*cappella|type\s+beat|no\s+vocals|vocals\s+only|ringtone)\b""")
    private val COVER = rx("""[(\[【][^)\]】]*\bcover\b|\bcover\s+(by|version|de|από)\b|\b(guitar|piano|acoustic|female|male|violin|sax|cello|ukulele|vocal|band|metal|rock|punk|jazz|lofi|kids?|ai)\s+cover\b|[-|–—:]\s*cover\s*([)\]]|$)|\bcover\s*$|\bcover\s*[|/]|\b(video|music)\s+cover\b|\bcovered\s+by\b""")
    private val NOT_A_SONG = rx("""\b(reaction|reacts|reacting|first\s+time\s+hearing|review|tutorial|how\s+to\s+(play|sing|dance|make)|chords|tabs|explained|analysis|interview|documentary|behind\s+the\s+scenes|making\s+of|trailer|teaser|snippet|preview|episode|challenge|parody|meme|dance\s+(video|practice|cover|tutorial)|choreograph(y|ed)|choreo|fancam|amv|fmv|fan\s*made|sing\s*-?\s*along|movie\s+clip|scene|anime\s+music\s+video|(special|dance|stage|band|choir)\s+performance|performance\s+video|gameplay|just\s+dance)\b""")
    private val MASHUP_X = Regex("""\s[xX×]\s""")
    private val COMPILATION = rx("""\b(full\s+album|album\s+completo|full\s+ep|megamix|mash\s*-?\s*up|medley|non\s*-?\s*stop|playlist|compilation|greatest\s+hits|top\s+\d+|\d+\s+hours?|hour\s+loop|mix\s+\d{4}|mixtape)\b""")
    private val KIDS = rx("""\b(nursery\s+rhymes?|kids\s+songs?|songs?\s+for\s+kids|for\s+toddlers|baby\s+songs?|cocomelon|pinkfong|baby\s+shark|bebefinn|minions?|cartoons?|animated\s+music\s+video|chicken\s+banana|peppa|paw\s+patrol|kids\s+version|for\s+children)\b""")
    private val LIVE = rx("""[(\[]\s*live\b|[-–—|:,]\s*live\s*([)\]]|$)|\blive\s+(at|from|performance|session|version|concert|stream|recording)\b|\blive\s*[)\]]|\blive$|\b(en\s+vivo|ao\s+vivo|en\s+directo|in\s+concert|concierto|unplugged|ζωντανα|ζωντανη\s+ηχογραφηση)\b""")
    private val LIVE_CAPS = Regex("""\bLIVE\b""")
    private val SHORTS = rx("""#shorts?\b""")

    private fun junkReason(title: String, durationSec: Long, short: Boolean, videoType: String?): String? {
        val low = lower(title)
        return when {
            short || SHORTS.containsMatchIn(low) -> "short"
            videoType == "PODCAST_EPISODE" -> "podcast"
            durationSec <= 0 -> "no duration (live stream?)"
            durationSec < minSec -> "too short"
            durationSec > maxSec -> "too long (mix/compilation)"
            ALTERED.containsMatchIn(low) -> "slowed/sped up/8D"
            KARAOKE.containsMatchIn(low) -> "karaoke/instrumental"
            COVER.containsMatchIn(low) -> "cover"
            LIVE.containsMatchIn(low) || LIVE_CAPS.containsMatchIn(title) -> "live"
            COMPILATION.containsMatchIn(low) -> "compilation"
            KIDS.containsMatchIn(low) -> "kids"
            MASHUP_X.containsMatchIn(title) && splitParts(cleanTitle(title)).size < 2 -> "mashup"
            NOT_A_SONG.containsMatchIn(low) -> "not a song"
            else -> null
        }
    }

    private val LYRIC = rx("""\b(lyrics?|lyric\s+video|letra|στιχοι|paroles|testo|legendado|traducida|traduzione|sub\s+espanol|subtitulado)\b""")
    private fun isLyric(title: String) = LYRIC.containsMatchIn(lower(title))

    // ---------------------------------------------------------------- song identity

    /** A parsed title. Keys are for comparing; artist/song are for display. */
    private class Parsed(
        val artist: String, val song: String,
        val artistKey: String, val songKey: String, val fullKey: String, val parts: Set<String>,
        val fromTitle: String? = null, val channel: String = "", val artistGiven: Boolean = false,
    )

    private fun parsed(artist: String, song: String, parts: List<String>, full: String, fromTitle: String? = null, channel: String = "", given: Boolean = false) =
        Parsed(artist.trim(), song.trim(), key(firstArtist(artist)), key(song), key(full), parts.map { key(it) }.filter { it.isNotEmpty() }.toSet(), fromTitle, channel, given)

    private fun parsedHint(h: SongId): Parsed {
        val song = cleanTitle(h.title)
        return parsed(h.artist, song, listOf(h.artist, song), "${h.artist} $song", given = h.artist.isNotBlank())
    }

    /** The seed: the caller's title/artist if given, else the video's title (re-parsed in [rank] once we know the artist names around it). */
    private fun parsedSeed(hint: SongId?, video: YtResult?, cands: List<Cand>): Parsed {
        if (hint != null) return parsedHint(hint)
        if (video == null) return parsed("", "", emptyList(), "")
        val freq = partFrequencies((cands.map { it.r.title } + video.title).distinct())
        val p = parse(video.title, video.artist, null, freq, emptyList(), emptyList())
        return Parsed(p.artist, p.song, p.artistKey, p.songKey, p.fullKey, p.parts, video.title, video.artist, false)
    }

    private val parsedCache = object : LinkedHashMap<String, Parsed>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parsed>?) = size > 2000
    }
    private fun rememberParse(id: String, p: Parsed) = synchronized(parsedCache) { parsedCache[id] = p }

    /**
     * Splits a video title into artist and song. Uses, in order: YouTube Music's own artist for
     * songs, the seed's song title, the part matching the channel or the seed's artist, the part
     * that recurs in many titles (an artist's name repeats; song names don't), else "Artist - Song".
     */
    private fun parse(title: String, channel: String, ytmArtist: String?, freq: Map<String, Int>, hints: List<String>, songHints: List<String>): Parsed {
        val clean = cleanTitle(title)
        var parts = splitParts(clean)
        val chan = cleanChannel(channel)
        val known = (listOfNotNull(ytmArtist, chan) + hints.flatMap { listOf(it) + it.split(ARTIST_SPLIT) })
            .map { key(it) }.filter { it.replace(" ", "").length >= 2 }.distinct()
        fun isKnown(p: String) = key(p).let { k -> known.any { nameMatch(k, it) } }
        fun isSong(p: String) = key(p).let { k -> songHints.any { h -> k == h || (h.replace(" ", "").length >= 4 && containsWords(k, h)) } }

        if (parts.size <= 1) {
            val one = parts.firstOrNull() ?: clean
            // 'KATSEYE (캣츠아이) "Animal" Official MV'
            (QUOTED_SONG.find(one) ?: QUOTED_SONG_SINGLE.find(one))?.let { m ->
                val after = FEAT.replace(" " + m.groupValues[3], "").trim().trim('-', '–', '—', '|', ':', ',', ' ')
                val a = m.groupValues[1].trim().ifEmpty { after.ifEmpty { ytmArtist ?: chan } }
                return parsed(a, m.groupValues[2], listOf(a, m.groupValues[2]), "$a ${m.groupValues[2]}")
            }
            // "ΖΗΛΕΥΩ ΤΑ ΠΟΥΛΙΑ ΣΤΕΛΙΟΣ ΚΑΖΑΝΤΖΙΔΗΣ", "Nelly   errtime": a known or recurring artist at either end
            val names = (known + freq.filter { (k, n) -> n >= 2 && k.replace(" ", "").length >= 4 }.keys).distinct()
                .filter { n -> songHints.none { it == n } }.sortedByDescending { it.length }
            for (n in names) splitByName(one, n)?.let { (a, song) -> return parsed(a, song, listOf(a, song), "$a $song") }
            val song = unquote(one)
            val artist = ytmArtist ?: chan
            return parsed(artist, song, listOf(artist, song), "$artist $song")
        }
        // A channel name tacked on at the end ("Song | Greek Lyric Videos") is a tag, not the artist.
        if (parts.size >= 3 && nameMatch(key(parts.last()), key(chan)) && hints.none { nameMatch(key(it), key(chan)) }) parts = parts.dropLast(1)
        // "Shakira 'Zoo' | Lyritix": the channel's name after a title that names artist and song itself
        if (parts.size == 2 && nameMatch(key(parts[1]), key(chan)) && (QUOTED_SONG.matches(parts[0]) || QUOTED_SONG_SINGLE.matches(parts[0])) && !QUOTED.matches(parts[0].trim())) {
            return parse(parts[0], "", ytmArtist, freq, hints, songHints)
        }
        val songIdx = parts.indexOfFirst { isSong(it) }
        val quoted = parts.indexOfFirst { QUOTED.matches(it.trim()) }
        var ai = parts.indices.firstOrNull { it != songIdx && it != quoted && isKnown(parts[it]) } ?: -1
        if (ai < 0) {
            // "Artist - Song" is the usual order, so another part must recur clearly more to be the artist
            // (a song covered by many singers recurs too).
            val f = parts.mapIndexed { i, p -> if (i == songIdx || i == quoted) -1 else freq[key(p)] ?: 0 }
            val best = f.indices.maxByOrNull { f[it] } ?: 0
            if (f[best] >= 3 && f.count { it == f[best] } == 1 && (best == 0 || f[best] >= 2 * maxOf(1, f[0]))) ai = best
        }
        if (ai < 0) ai = parts.indices.first { it != songIdx && it != quoted }
        val si = when {
            songIdx >= 0 && songIdx != ai -> songIdx
            quoted >= 0 && quoted != ai -> quoted
            else -> parts.indices.first { it != ai }
        }
        return parsed(parts[ai], unquote(parts[si]), parts, clean)
    }

    /** Whether two name keys name the same artist: "nelly" ~ "nelly", "rose and bruno mars" ~ "rose", "kazadzidis stelios" ~ "stelios kazadzidis". */
    private fun nameMatch(a: String, b: String): Boolean {
        val ac = a.replace(" ", ""); val bc = b.replace(" ", "")
        if (ac.isEmpty() || bc.isEmpty()) return false
        if (ac == bc || (bc.length >= 4 && ac.contains(bc)) || (ac.length >= 4 && bc.contains(ac))) return true
        val aw = a.split(' ').filter { it.length >= 3 }.toSet()
        val bw = b.split(' ').filter { it.length >= 3 }.toSet()
        return aw.isNotEmpty() && bw.isNotEmpty() && (aw.containsAll(bw) || bw.containsAll(aw)) && (aw + bw).any { it.length >= 4 }
    }

    /** If [title] starts or ends with the name whose key is [nameKey], returns (name as written, rest). */
    private fun splitByName(title: String, nameKey: String): Pair<String, String>? {
        val words = title.split(SPACES).filter { it.isNotEmpty() }
        val keys = words.map { key(it) }
        val target = nameKey.replace(" ", "")
        for (fromStart in listOf(true, false)) {
            var acc = ""
            for (n in 1 until words.size) {
                val i = if (fromStart) n - 1 else words.size - n
                acc = if (fromStart) acc + keys[i].replace(" ", "") else keys[i].replace(" ", "") + acc
                if (acc.length > target.length) break
                if (acc == target) {
                    val name = if (fromStart) words.take(n) else words.takeLast(n)
                    val rest = if (fromStart) words.drop(n) else words.dropLast(n)
                    val song = rest.joinToString(" ").replace(DANGLING_FEAT, "").trim().trim('-', '–', '—', '|', ':', ',', '.', ' ')
                    return if (song.count { it.isLetterOrDigit() } >= 2) name.joinToString(" ") to song else null
                }
            }
        }
        return null
    }

    /**
     * For each title part, with how many different other parts it appears. An artist appears next to
     * many different songs; a song (even with many uploads) only next to its own artist.
     */
    private fun partFrequencies(titles: List<String>): Map<String, Int> {
        val partners = HashMap<String, MutableSet<String>>()
        for (t in titles) {
            val ks = splitParts(cleanTitle(t)).map { key(it) }.filter { it.isNotEmpty() }.distinct()
            for (k in ks) { val set = partners.getOrPut(k) { HashSet() }; for (o in ks) if (o != k) set += o }
        }
        return partners.mapValues { it.value.size }
    }

    private fun same(a: Parsed, b: Parsed): Boolean {
        if (a.songKey.isEmpty() || b.songKey.isEmpty()) return false
        // Short titles are often shared by different songs ("Yeah!" by Usher and by 50 Cent): then the artists must agree too.
        if (a.songKey == b.songKey) return a.songKey.split(' ').size >= 3 || a.songKey.length >= 15 || a.artistKey.isEmpty() || b.artistKey.isEmpty() ||
            nameMatch(a.artistKey, b.artistKey) || a.parts.any { p -> b.parts.any { nameMatch(it, p) && it != a.songKey } }
        if (a.parts.size >= 2 && a.parts == b.parts) return true
        // "One More Time" vs "One More Time (Radio Edit)", "Grillz" vs "Nelly Grillz"
        val (s, l) = if (a.songKey.length <= b.songKey.length) a.songKey to b.songKey else b.songKey to a.songKey
        if (s.replace(" ", "").length >= 6 && s.length * 2 >= l.length && containsWords(l, s)) return true
        // Title order swapped ("Grillz - Nelly" vs "Nelly - Grillz"): same song if each one's song is in the other's parts.
        return a.songKey in b.parts && b.songKey in a.parts && a.artistKey.isNotEmpty() && (a.artistKey in b.parts || b.artistKey in a.parts)
    }

    // ---------------------------------------------------------------- text helpers

    private val BRACKETS = Regex("""\s*[(\[【{（]([^()\[\]【】{}（）]*)[)\]】}）]""")
    private val DECOR = rx("""\b(official|video|audio|lyrics?|letra|στιχοι|visuali[sz]er|hd|hq|4k|8k|1080p|720p|remaster(ed)?|explicit|clean|dirty|uncensored|censored|m/?v|color\s+coded|subs?|subtitulado|legendado|traduzione|traducida|prod|produced|ft|feat|featuring|from|full\s+song|clip|videoclip|clipe|oficial|officiel|ufficiale|vinyl|tiktok|song|new|single|release|records|music|premiere|out\s+now|\d{4})\b""")
    private val VERSION = rx("""\b(remix|mix|edit|version|extended|acoustic|live|dub|bootleg|rework|vip|instrumental|cover|slowed|sped|nightcore|karaoke|8d)\b""")
    private val TRAILING_DECOR = rx("""(\s*[-–—|:/]?\s*\b(official\s+(music\s+)?(video|audio)(\s+(clip|release))?|official\s+lyric\s+video|lyric\s+video|(color\s+coded\s+)?lyrics?|(w/?|with)\s+lyrics?|legendado|tradu[cç][aã]o|traducida(\s+al)?(\s+espa[nñ]ol)?|(sub(titulado|titulada|s)?\s+)?(al\s+)?espa[nñ]ol|letra|subtitulado|paroles|color\s+coded|with\s+lyrics?|video\s*clip|visuali[sz]er|official|hd|hq|4k|m/?v|audio|explicit|remastered|uncensored|lyrics?\s+video)\b[!.]*)+\s*$""", true)
    private val FEAT = rx("""\s+[(\[]?\s*(ft|feat|featuring)\b\.?\s.*$""", true)
    private val DECOR_PART = rx("""^(official.*|(with\s+)?(color\s+coded\s+)?lyrics?(\s+video)?|letra|hd|hq|4k|audio|video|m/?v|visuali[sz]er|video\s+clip|explicit|clean|dirty|remaster(ed)?|\d{4}|topic|.*official audio release.*)$""", true)

    /** Removes "(Official Video)", "[HD]", "ft. ...", "| Official Audio Release", hashtags, emoji. Keeps version info like "(Radio Edit)". */
    private fun cleanTitle(title: String): String {
        var t = title.replace(HASHTAG, " ").replace(SYMBOLS, " ").replace(INVISIBLE, "").replace(FILE_EXT, " ")
        // bracketed decoration; twice for nested brackets
        repeat(2) {
            t = BRACKETS.replace(t) { m ->
                val inner = lower(m.groupValues[1])
                if (inner.isBlank() || (DECOR.containsMatchIn(inner) && !VERSION.containsMatchIn(inner))) " " else m.value
            }
        }
        // clean each "Artist - Song | Tag" piece on its own, then put them back together
        val pieces = ArrayList<String>()
        val seps = ArrayList<String>()
        var last = 0
        for (m in SEP_SPACED.findAll(t)) { pieces += t.substring(last, m.range.first); seps += m.value; last = m.range.last + 1 }
        pieces += t.substring(last)
        val sb = StringBuilder()
        for ((i, raw) in pieces.withIndex()) {
            // "Artist ft. X - Song": drop the featured artists. In a title with no separator the
            // words after "ft." may be the song ("Yelawolf Ft. Paul Wall Hustle"), so keep them.
            val p = TRAILING_DECOR.replace(if (pieces.size > 1) FEAT.replace(raw, "") else raw, "").trim()
            if (p.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(seps[i - 1].let { if (it.isBlank()) " - " else it })
            sb.append(p)
        }
        return sb.toString().replace(SPACES, " ").replace(SPACE_PUNCT, "$1").replace(SPACE_CLOSE_QUOTE, "$1").trim().trim('-', '–', '—', '|', ':', ',', ' ')
    }

    private val SEP_SPACED = Regex("""\s+(?:-{1,2}|[–—‒]|\|{1,2}|//)\s+|\s+[-–—]+(?=\p{L})|(?<=[\p{L}\p{N}.!?)])[-–—]+\s+|\s*~+\s*""")
    private val DANGLING_FEAT = rx("""^(ft|feat|featuring|with|x|and|&)\.?\s+|\s+(ft|feat|featuring|with|x|and|&)\.?$""", true)
    private val SPACE_CLOSE_QUOTE = Regex("""\s+(["”’'»])(?=\s|$)""")
    private val SPACE_PUNCT = Regex("""\s+([.,!?])(?=\s|$)""")
    private val FILE_EXT = rx("""\.(avi|mp4|mpg|mpeg|wmv|flv|mkv|mov|mp3|m4a|wav|flac|3gp)\b""", true)
    private val QUOTED = Regex("""^["“”'‘’«»].+["“”'‘’«»]$""")
    private val QUOTED_SONG = Regex("""^(.*?)(?:^|\s)["“«]([^"“”«»]{1,80})["”»](?=\s|$)(.*)$""")
    private val QUOTED_SONG_SINGLE = Regex("""^(.*?)(?:^|\s)['‘]([^'‘’]{1,80})['’](?=\s|$)(.*)$""")
    private val HASHTAG = Regex("""#\S+""")
    private val SYMBOLS = Regex("""[\p{So}\p{Cn}\p{Co}]""")
    private val INVISIBLE = Regex("""[\uFE0F\u200B-\u200D]""")
    private val SPACES = Regex("""\s+""")
    private val MARKS = Regex("""\p{Mn}+""")
    private val APOSTROPHES = Regex("""['’`´]""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val REPEATS = Regex("""([a-z])\1+""")
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|\+|/|\s(?:x|X|and|και|feat\.?|ft\.?|featuring|with|vs\.?)\s)\s*""")

    /** "Artist - Song | Tag" -> [Artist, Song, Tag], dropping parts that are only decoration. */
    private fun splitParts(clean: String): List<String> {
        var parts = clean.split(SEP_SPACED)
        if (parts.size == 1) {
            // "ΣΥΝΝΕΦΙΑΣΜΕΝΗ ΚΥΡΙΑΚΗ-ΣΤΕΛΙΟΣ ΚΑΖΑΝΤΖΙΔΗΣ": split at a bare hyphen with words of 2+ letters on both
            // sides and a multi-word side; never "Jay-Z", "K-Pop", "Wu-Tang". The last such hyphen wins.
            val letters = { x: String -> x.count { it.isLetter() } }
            for (i in clean.indices.reversed()) {
                if (clean[i] != '-' && clean[i] != '–' && clean[i] != '—') continue
                val l = clean.substring(0, i).trim(); val r = clean.substring(i + 1).trim()
                if (letters(l) >= 4 && letters(r) >= 4 && (l.contains(' ') || r.contains(' ')) &&
                    letters(l.substringAfterLast(' ').substringAfterLast('-')) >= 2 && letters(r.substringBefore(' ').substringBefore('-')) >= 2) {
                    parts = listOf(l, r); break
                }
            }
        }
        return parts.map { it.trim().trim('-', '–', '—', '|', ':', ',', ' ') }.filter { it.isNotEmpty() && !DECOR_PART.matches(it) }
    }

    private fun unquote(s: String) = s.trim().trim('"', '“', '”', '\'', '‘', '’', '«', '»').trim()

    /** "Nelly ft. X", "ROSÉ & Bruno Mars", "A x B", "A, B" -> the first artist. */
    private fun firstArtist(a: String): String =
        a.split(ARTIST_SPLIT).firstOrNull()?.trim()?.ifEmpty { null } ?: a.trim()

    private fun cleanChannel(c: String): String = c.trim().removeSuffix(" - Topic").replace(Regex("""(?i)vevo$"""), "")
        .replace(Regex("""(?i)\s*\b(official|oficial)\b\s*"""), " ").trim()

    private fun bylineArtist(byline: JSONObject?): String {
        val a = byline?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until a.length()) {
            val t = a.optJSONObject(i)?.optString("text") ?: continue
            if (t.trim() == "•") break
            sb.append(t)
        }
        return cleanChannel(sb.toString())
    }

    private fun lower(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(MARKS, "").lowercase(Locale.ROOT).replace('ς', 'σ')

    /** Lower case, no accents, '&' -> "and", punctuation -> spaces. */
    private fun normalize(s: String): String = lower(s).replace("&", " and ").replace(APOSTROPHES, "")
        .replace(NON_WORD, " ").trim()

    private val GREEK = mapOf(
        'α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i", 'θ' to "th", 'ι' to "i",
        'κ' to "k", 'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "x", 'ο' to "o", 'π' to "p", 'ρ' to "r", 'σ' to "s",
        'τ' to "t", 'υ' to "y", 'φ' to "f", 'χ' to "ch", 'ψ' to "ps", 'ω' to "o",
    )

    /** Greek to Latin plus a few spelling-insensitive rules, so "Συννεφιασμένη" == "Sinnefiasmeni" and "Νταλάρας" == "Dalaras". */
    private fun skeleton(norm: String): String {
        val sb = StringBuilder(norm.length + 8)
        for (ch in norm) sb.append(GREEK[ch] ?: ch)
        var s = sb.toString()
            .replace("mp", "b").replace("nt", "d").replace("gk", "g").replace("ch", "h").replace("kh", "h")
            .replace('x', 'h').replace('y', 'i').replace("ou", "u").replace("ph", "f")
        s = s.replace(REPEATS, "$1")
        return s
    }

    private fun compact(s: String) = key(s).replace(" ", "")

    private fun containsWords(haystack: String, needle: String): Boolean =
        needle.isNotEmpty() && " $haystack ".contains(" $needle ")

    /** Fraction of the words of [needle] found in [haystack]. */
    private fun coverage(needle: String, haystack: String): Double {
        val w = needle.split(' ').filter { it.isNotEmpty() }
        if (w.isEmpty()) return 0.0
        val h = haystack.split(' ').toSet()
        val hc = haystack.replace(" ", "")
        return w.count { it in h || (it.length >= 4 && hc.contains(it)) }.toDouble() / w.size
    }

    private fun runs(o: JSONObject?): String {
        val a = o?.optJSONArray("runs") ?: return o?.optString("simpleText") ?: ""
        val sb = StringBuilder()
        for (i in 0 until a.length()) sb.append(a.optJSONObject(i)?.optString("text") ?: "")
        return sb.toString()
    }

    /** Finds every object stored under [key], depth first, in document order. Uses keys() so it works with Android's org.json. */
    private fun collect(v: Any?, key: String, out: MutableList<JSONObject>) {
        when (v) {
            is JSONObject -> {
                val it = v.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val x = v.opt(k)
                    if (k == key && x is JSONObject) out += x else collect(x, key, out)
                }
            }
            is JSONArray -> for (i in 0 until v.length()) collect(v.opt(i), key, out)
        }
    }

    private fun parseDuration(s: String): Long {
        if (s.isBlank()) return -1
        var total = 0L
        for (p in s.trim().split(':')) total = total * 60 + (p.trim().toLongOrNull() ?: return -1)
        return total
    }

    @Volatile private var ytmClientVersion: String? = null
    private fun ytmVersion(): String = ytmClientVersion ?: synchronized(this) {
        ytmClientVersion ?: (runCatching { YoutubeParsingHelper.getYoutubeMusicClientVersion() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "1.20251110.01.00").also { ytmClientVersion = it }
    }

    private fun elapsedMs(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    private fun trace(s: String) { log?.invoke(s) }
}
