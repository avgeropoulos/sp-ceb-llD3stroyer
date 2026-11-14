package com.mixdeck

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two downloads of the same song (both decks, or a cancelled one and its retry) never spoil each other. */
class NetDownloadTest {
    private val data = Random(5).nextBytes(512 * 1024)
    private val chunk = 32L * 1024

    /** A slow server that answers ranged requests, like YouTube's. */
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/song") { ex ->
            val (a, b) = ex.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-').map { it.toInt() }
            val end = minOf(b, data.size - 1)
            Thread.sleep(15)
            ex.responseHeaders.add("Content-Range", "bytes $a-$end/${data.size}")
            ex.sendResponseHeaders(206, (end - a + 1).toLong())
            ex.responseBody.use { it.write(data, a, end - a + 1) }
        }
        executor = java.util.concurrent.Executors.newCachedThreadPool()
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/song"
    private val dir: File = Files.createTempDirectory("mixdeck").toFile()

    @AfterTest fun stop() { server.stop(0) }

    private fun leftovers() = dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }

    @Test fun twoDownloadsOfTheSameSongBothFinish() {
        val dest = File(dir, "yt_x.audio")
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val threads = (1..2).map { Thread { runCatching { Net.download(url, dest, chunk = chunk) }.onFailure { errors += it } } }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }
        assertEquals(emptyList(), errors, "both downloads finish")
        assertContentEquals(data, dest.readBytes(), "the whole song")
        assertEquals(emptyList(), leftovers())
    }

    @Test fun leftoversNobodyWritesToAreDeleted() {
        val now = System.currentTimeMillis()
        val old = File(dir, "MixDeck-windows.zip.1.part").apply { writeBytes(ByteArray(4)); setLastModified(now - 120_000) }
        val running = File(dir, "MixDeck-windows.zip.2.part").apply { writeBytes(ByteArray(4)); setLastModified(now) }
        val pkg = File(dir, "MixDeck-windows.zip").apply { writeBytes(ByteArray(4)); setLastModified(now - 120_000) }
        Net.deleteLeftovers(dir, idleMs = 60_000, now = now)
        assertEquals(listOf(running.name), leftovers().map { it.name })
        assertTrue(!old.exists() && pkg.exists(), "only .part files go")
        Net.deleteLeftovers(dir, idleMs = 0, now = now)
        assertEquals(emptyList(), leftovers())
    }

    @Test fun aCancelledDownloadLeavesItsRetryAlone() {
        val dest = File(dir, "yt_x.audio")
        val retryStarted = CountDownLatch(1)
        var retry: Throwable? = null
        lateinit var second: Thread
        val first = runCatching {
            var cancelled = false
            Net.download(url, dest, chunk = chunk) {
                if (!cancelled) {
                    cancelled = true
                    // "Stop downloading", then the same song is picked again at once.
                    second = Thread { retry = runCatching { Net.download(url, dest, chunk = chunk) { retryStarted.countDown() } }.exceptionOrNull() }
                    second.start()
                    retryStarted.await(5, TimeUnit.SECONDS)
                    throw CancellationException("Stopped")
                }
            }
        }
        assertTrue(first.exceptionOrNull() is CancellationException)
        second.join(20_000)
        assertEquals(null, retry, "the retry finishes")
        assertContentEquals(data, dest.readBytes())
        assertEquals(emptyList(), leftovers())
    }
}
