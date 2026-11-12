package com.mixdeck

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.IOException

/** A song found on YouTube. */
data class YtResult(val url: String, val id: String, val title: String, val artist: String, val durationSec: Long)

/**
 * YouTube search and audio download, done on the phone with NewPipe Extractor.
 * For personal use: downloading from YouTube is against YouTube's terms of service.
 */
object YouTube {
    @Volatile private var ready = false

    fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) { NewPipe.init(NewPipeDownloader, Localization("en", "US")); ready = true }
        }
    }

    /** A search that can load more results page by page. */
    class Search(private val query: String, songsOnly: Boolean) {
        private var extractor: SearchExtractor
        private var next: Page? = null
        private var started = false

        init {
            init()
            val filter = if (songsOnly) YoutubeSearchQueryHandlerFactory.MUSIC_SONGS else YoutubeSearchQueryHandlerFactory.VIDEOS
            extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        }

        val hasMore get() = !started || next != null

        fun nextPage(): List<YtResult> {
            val page = if (!started) {
                started = true
                extractor.fetchPage()
                var first = extractor.initialPage
                // YouTube Music's "songs" results sometimes come back empty when YouTube changes
                // its pages; its "music videos" results (official audio and videos) still work.
                if (first.items.isEmpty() && extractor.linkHandler.contentFilters.contains(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)) {
                    extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS), "")
                    extractor.fetchPage()
                    first = extractor.initialPage
                }
                first
            } else {
                val p = next ?: return emptyList()
                extractor.getPage(p)
            }
            next = if (page.hasNextPage()) page.nextPage else null
            return page.items.mapNotNull { toResult(it) }
        }
    }

    private fun toResult(item: InfoItem): YtResult? {
        if (item !is StreamInfoItem) return null
        val id = videoId(item.url) ?: return null
        return YtResult(item.url, id, item.name ?: "", cleanArtist(item.uploaderName ?: ""), item.duration)
    }

    /** YouTube Music "Topic" channels are named "Artist - Topic". */
    private fun cleanArtist(s: String) = s.removeSuffix(" - Topic")

    fun videoId(url: String): String? {
        val m = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})""").find(url)
        return m?.groupValues?.get(1)
    }

    fun isYouTubeUrl(s: String) = Regex("""(youtube\.com|youtu\.be)/""").containsMatchIn(s) && videoId(s) != null

    /**
     * Looks up a song's audio and downloads it to [dest]. Picks the best AAC (m4a) stream,
     * because every Android phone can decode it; falls back to Opus.
     */
    fun downloadAudio(url: String, dest: File, progress: (Float) -> Unit): YtResult {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val streams = info.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL)
        }.ifEmpty { info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP } }
        val best = pickStream(streams) ?: throw IOException("No downloadable audio for this video")
        val headers = mutableMapOf<String, String>()
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(best.content)) {
            headers["User-Agent"] = YoutubeParsingHelper.getVisionOsUserAgent(Localization("en", "US"))
        } else {
            headers["User-Agent"] = Net.BROWSER_UA
            headers["Origin"] = "https://www.youtube.com"
            headers["Referer"] = "https://www.youtube.com/"
        }
        Net.download(best.content, dest, headers, progress = progress)
        return YtResult(url, info.id, info.name ?: "", cleanArtist(info.uploaderName ?: ""), info.duration)
    }

    private fun pickStream(streams: List<AudioStream>): AudioStream? {
        val m4a = streams.filter { it.format == MediaFormat.M4A }
        val pool = m4a.ifEmpty { streams }
        return pool.maxByOrNull { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate }
    }
}
