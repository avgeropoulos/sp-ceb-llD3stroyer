package com.mixdeck

import com.mixdeck.ui.TutorialSong
import java.io.IOException

/**
 * Finds a song already on the phone, or finds it on YouTube and downloads it. Prefers the artist's
 * own upload, then any full-length upload; skips short clips.
 */
object SongFetcher {
    fun findOrDownload(store: TrackStore, song: TutorialSong, progress: (String) -> Unit): String {
        fun matches(title: String, artist: String) = (title + " " + artist).lowercase().let { t -> song.words.all { t.contains(it) } }
        store.all().firstOrNull { matches(it.title, it.artist) }?.let { return it.id }
        progress("Searching YouTube for ${song.title}…")
        val videos = runCatching { YouTube.Search(song.query + " official audio", songsOnly = false).nextPage() }.getOrDefault(emptyList())
        val full = { r: YtResult -> r.durationSec !in 1..119 && !r.title.lowercase().let { it.contains("lyric") || it.contains("dance") || it.contains("choreo") } }
        val pick = videos.firstOrNull { it.artist.equals(song.officialChannel, true) && matches(it.title, it.artist) && full(it) }
            ?: videos.firstOrNull { matches(it.title, it.artist) && full(it) }
            ?: YouTube.Search(song.query, songsOnly = true).nextPage().firstOrNull { matches(it.title, it.artist) && full(it) }
            ?: throw IOException("Couldn't find \"${song.title}\" on YouTube")
        val id = "yt_" + pick.id
        if (!store.has(id)) {
            YouTube.downloadAudio(pick.url, store.audioFile(id)) { p -> progress("Downloading ${song.title}… ${(p * 100).toInt()}%") }
            store.save(Track(id, song.title, song.artist, pick.url, pick.durationSec.toDouble()))
        }
        return id
    }
}

