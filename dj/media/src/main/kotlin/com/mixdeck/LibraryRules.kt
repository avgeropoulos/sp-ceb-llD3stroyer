package com.mixdeck

import com.mixdeck.ui.KeyDisplay

/** The library's tabs, in the order both apps show them. */
enum class LibraryTab { SUGGESTED, MINE, YOUTUBE, SPOTIFY, FILES }

/** Library behaviour shared by the phone and the computer (pure functions, so they're testable). */
object LibraryRules {
    /**
     * Where the library opens: Suggested for NEXT ([suggest]); My songs when it has a song that
     * isn't on a deck already; otherwise YouTube, with the last search still there.
     */
    fun defaultTab(suggest: Boolean, hasSavedNotOnDeck: Boolean): LibraryTab = when {
        suggest -> LibraryTab.SUGGESTED
        hasSavedNotOnDeck -> LibraryTab.MINE
        else -> LibraryTab.YOUTUBE
    }

    /** True when [songs] holds a song that isn't one of [onDecks] (the decks' song ids). */
    fun hasSavedNotOnDeck(songs: List<Track>, onDecks: Collection<String?>) = songs.any { it.id !in onDecks }

    /** The YouTube tab's hint, the same on both. */
    const val YOUTUBE_HINT = "Search YouTube Music, or switch to Videos for remixes and live sets. A new song downloads on the deck while you carry on."

    /** The YouTube tab before any search. */
    const val SEARCH_TITLE = "Search for a song"
    const val SEARCH_BODY = "Type an artist and a song, like Nelly Air Force Ones. You can also paste a YouTube or Spotify link."
    /** The starter chips: label to query. */
    val STARTERS = listOf("Nelly – Air Force Ones" to "Nelly Air Force Ones", "Nelly – Grillz" to "Nelly Grillz")

    /** The Spotify tab before any list was imported (phone wording: "tap"). */
    const val SPOTIFY_EMPTY = "In Spotify: open a playlist, album or song → Share → Copy link, then paste it above. " +
        "Each song is matched to its audio on YouTube when you tap it."

    /** "Filter my 128 songs (title, artist or a key like 8A)". */
    fun filterHint(count: Int) = "Filter my $count song${if (count == 1) "" else "s"} (title, artist or a key like 8A)"

    /** The song's key for display ("8A"), or null when unknown or unreliable. */
    fun keyLabel(t: Track): String? = t.key?.takeIf { t.keyConfidence >= KeyDisplay.MIN_CONFIDENCE }?.camelot

    /** My songs' second line: "Nelly  ·  101 BPM  ·  8A  ·  3:46", plus "  ·  on deck A" when it's on a deck. */
    fun songSubtitle(t: Track, onDeck: Int? = null): String = listOfNotNull(
        t.artist.ifBlank { null },
        if (t.bpm > 0) "%.0f BPM".format(t.bpm) else null,
        keyLabel(t),
        if (t.durationSec > 0) clock(t.durationSec.toLong()) else null,
        onDeck?.let { "on deck ${if (it == 0) "A" else "B"}" },
    ).joinToString("  ·  ")

    /** My songs' filter: title or artist contains [query], or [query] is the song's key ("8a" = "8A"). */
    fun matches(t: Track, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        if (t.title.lowercase().contains(q) || t.artist.lowercase().contains(q)) return true
        return keyLabel(t)?.lowercase() == q
    }

    /** My songs, filtered by [query], with the songs on a deck last; each with the deck it's on (or null). */
    fun mySongs(all: List<Track>, onDecks: List<String?>, query: String): List<Pair<Track, Int?>> =
        all.filter { matches(it, query) }
            .map { t -> t to onDecks.indexOf(t.id).takeIf { it >= 0 } }
            .sortedBy { it.second != null } // stable: newest first within each group

    /** Every saved song by its key ([TrackStore.nameKey]), worked out once per Spotify list. */
    fun savedNames(store: TrackStore): Map<String, List<Track>> = store.all().groupBy { TrackStore.nameKey(it.title, it.artist) }

    /**
     * The id of the saved song matching a Spotify song, from [savedNames], or null: the same names
     * and about the same length (another version with the same names isn't it), as [TrackStore.findByName].
     */
    fun savedId(names: Map<String, List<Track>>, t: SpotifyTrack): String? =
        names[TrackStore.nameKey(t.title, t.artist)]?.firstOrNull { TrackStore.sameLength(it.listedLength, t.durationSec.toDouble()) }?.id

    fun clock(sec: Long) = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)
}
