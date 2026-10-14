package com.bouzouki

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gets a YouTube video's soundtrack onto the phone for analysis, with NewPipe Extractor
 * (the library behind the NewPipe app). The audio stays on this phone and is deleted
 * once the bouzouki line has been worked out.
 */
object YouTubeAudio {
    class Song(val title: String, val file: File)

    @Volatile private var initialised = false

    private fun init() {
        if (initialised) return
        synchronized(this) {
            if (!initialised) { NewPipe.init(HttpDownloader); initialised = true }
        }
    }

    /** Downloads the best audio stream of [videoId] into [dir]. [progress] gets 0..1. */
    fun download(videoId: String, dir: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Song {
        init()
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        val title = try { extractor.name } catch (_: Exception) { videoId }
        val streams = extractor.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val best = pick(streams) ?: throw IOException("YouTube didn't offer a downloadable audio track for this video")
        val file = File(dir, "$videoId.${best.format?.suffix ?: "m4a"}")
        fetch(best.content, file, progress, cancelled)
        return Song(title, file)
    }

    /** AAC (m4a) decodes everywhere; otherwise Opus/WebM. Highest quality of the kind. */
    private fun pick(streams: List<AudioStream>): AudioStream? {
        val m4a = streams.filter { it.format == MediaFormat.M4A }
        val pool = m4a.ifEmpty { streams.filter { it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.WEBMA || it.format == MediaFormat.OPUS } }
            .ifEmpty { streams }
        return pool.maxByOrNull { it.averageBitrate }
    }

    /** Downloads in 1 MB ranges: YouTube slows down single big requests. */
    private fun fetch(url: String, file: File, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val chunk = 1L shl 20
        var done = 0L
        var total = -1L
        file.outputStream().use { out ->
            while (total < 0 || done < total) {
                if (cancelled()) throw InterruptedException()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Range", "bytes=$done-${done + chunk - 1}")
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("YouTube refused the audio download ($code)")
                if (total < 0) {
                    total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: conn.contentLengthLong.takeIf { code == 200 } ?: -1L
                }
                var got = 0L
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                    }
                }
                done += got
                if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                if (got == 0L || code == 200) break // server ignored the range: that was the whole file
            }
        }
    }

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    /** The HTTP client NewPipe Extractor uses for YouTube's web pages and APIs. */
    private object HttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val conn = URL(request.url()).openConnection() as HttpURLConnection
            conn.requestMethod = request.httpMethod()
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            for ((name, values) in request.headers()) {
                conn.setRequestProperty(name, values.joinToString(", "))
            }
            request.dataToSend()?.let { body ->
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val headers = HashMap<String, List<String>>()
            for ((k, v) in conn.headerFields) if (k != null) headers[k] = v
            return Response(code, conn.responseMessage ?: "", headers, text, conn.url.toString())
        }
    }
}
