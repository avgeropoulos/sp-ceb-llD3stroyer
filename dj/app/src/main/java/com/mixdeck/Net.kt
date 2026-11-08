package com.mixdeck

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Small HTTP helpers on top of HttpURLConnection (no extra libraries). */
object Net {
    const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    fun open(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.requestMethod = method
        c.instanceFollowRedirects = true
        if (headers.keys.none { it.equals("User-Agent", true) }) c.setRequestProperty("User-Agent", BROWSER_UA)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = open(url, headers)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    /**
     * Downloads [url] to [dest] in ranged chunks (YouTube throttles or refuses big single
     * requests). Reports progress 0..1.
     */
    fun download(
        url: String, dest: File, headers: Map<String, String> = emptyMap(),
        chunk: Long = 2L * 1024 * 1024, progress: (Float) -> Unit = {},
    ) {
        val tmp = File(dest.path + ".part")
        tmp.delete()
        var total = -1L
        var done = 0L
        tmp.outputStream().buffered(1 shl 16).use { out ->
            while (total < 0 || done < total) {
                val end = if (total > 0) minOf(total - 1, done + chunk - 1) else done + chunk - 1
                val c = open(url, headers + ("Range" to "bytes=$done-$end"))
                try {
                    val code = c.responseCode
                    if (code != 200 && code != 206) throw IOException("Download failed (HTTP $code)")
                    if (total < 0) {
                        total = c.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                            ?: c.contentLengthLong.takeIf { code == 200 && it > 0 } ?: -1L
                    }
                    val n = c.inputStream.use { copy(it, out) }
                    if (n == 0L) break
                    done += n
                    if (code == 200) total = done // server ignored the range and sent everything
                    if (total > 0) progress(done.toFloat() / total)
                } finally { c.disconnect() }
            }
        }
        if (done == 0L) throw IOException("Download was empty")
        if (!tmp.renameTo(dest)) throw IOException("Could not save the download")
    }

    private fun copy(input: InputStream, out: java.io.OutputStream): Long {
        val buf = ByteArray(1 shl 16)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r); n += r
        }
        return n
    }
}

/** The HTTP client NewPipe Extractor uses to talk to YouTube. */
object NewPipeDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val c = Net.open(request.url(), method = request.httpMethod())
        try {
            request.headers().forEach { (k, values) ->
                c.setRequestProperty(k, null)
                values.forEach { c.addRequestProperty(k, it) }
            }
            val body = request.dataToSend()
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code == 429) throw ReCaptchaException("YouTube is rate limiting this phone, try again later", request.url())
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val headers = c.headerFields.filterKeys { it != null }
            return Response(code, c.responseMessage, headers, text, c.url.toString())
        } finally { c.disconnect() }
    }
}
