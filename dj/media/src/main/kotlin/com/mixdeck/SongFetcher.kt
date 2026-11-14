package com.mixdeck

import com.mixdeck.ui.TutorialSong
import java.io.IOException

/**
 * Gets picked songs onto the device: [fetch] downloads a library pick (on the deck, while you carry
 * on), [findOrDownload] finds a tutorial song.
 */
object SongFetcher {
    /** One step of [fetchSteps]: finding [title] on YouTube ([fraction] null), or downloading it. */
    class Step(val title: String, val fraction: Float?) {
        private val pct get() = ((fraction ?: 0f) * 100).toInt()
        /** For the deck's song strip: "Downloading Grillz… 37%", "Finding Grillz on YouTube…". */
        val text get() = if (fraction == null) "Finding $title on YouTube…" else "Downloading $title… $pct%"
        /** After "Up next: " on a playing deck: "Grillz · downloading 42%". */
        val short get() = if (fraction == null) "$title · finding it on YouTube" else "$title · downloading $pct%"
    }

    /**
     * Makes sure [pick] is saved in [store] and returns its song id. Reports progress as text
     * ("Downloading Grillz… 37%"). To cancel, throw from [progress] (a half download is deleted).
     */
    fun fetch(store: TrackStore, pick: SongPick, progress: (String) -> Unit): String = fetchSteps(store, pick) { progress(it.text) }

    /** [fetch], reporting each [Step]. */
    fun fetchSteps(store: TrackStore, pick: SongPick, progress: (Step) -> Unit): String = when (pick) {
        is SongPick.Saved -> pick.id
        is SongPick.YouTube -> {
            val id = "yt_" + pick.videoId
            // The same song picked again while it downloads waits here, then finds it saved. Waiting,
            // it still reports, so a pick changed meanwhile can stop it (progress throws).
            store.withLock(id, poll = { progress(Step(pick.title, 0f)) }) {
                if (!store.has(id)) {
                    progress(Step(pick.title, 0f))
                    val info = YouTube.downloadAudio(pick.url, store.audioFile(id)) { p -> progress(Step(pick.title, p)) }
                    store.save(Track(id, info.title.ifEmpty { pick.title }, info.artist.ifEmpty { pick.artist }, pick.url, info.durationSec.toDouble()))
                }
            }
            id
        }
        is SongPick.Spotify -> {
            val t = pick.track
            // Saved before (under Spotify's names, and as long): no need to ask YouTube again.
            store.findByName(t.title, t.artist, t.durationSec.toDouble())?.id ?: run {
                progress(Step(t.title, null))
                val match = Spotify.matchOnYouTube(t)
                val id = "yt_" + match.id
                store.withLock(id, poll = { progress(Step(t.title, 0f)) }) {
                    if (!store.has(id)) {
                        progress(Step(t.title, 0f))
                        YouTube.downloadAudio(match.url, store.audioFile(id)) { p -> progress(Step(t.title, p)) }
                        // Keep Spotify's clean title and artist names, and its length (the upload's may differ).
                        store.save(Track(id, t.title, t.artist, match.url, match.durationSec.toDouble(), listedSec = t.durationSec.toDouble()))
                    }
                }
                id
            }
        }
    }

    /**
     * Finds a song already on the phone, or finds it on YouTube and downloads it. Prefers the
     * artist's own upload, then any full-length upload; skips short clips.
     */
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
        store.withLock(id, poll = { progress("Downloading ${song.title}…") }) {
            if (!store.has(id)) {
                YouTube.downloadAudio(pick.url, store.audioFile(id)) { p -> progress("Downloading ${song.title}… ${(p * 100).toInt()}%") }
                store.save(Track(id, song.title, song.artist, pick.url, pick.durationSec.toDouble()))
            }
        }
        return id
    }
}

