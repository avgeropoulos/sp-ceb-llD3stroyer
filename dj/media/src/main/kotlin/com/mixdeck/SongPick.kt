package com.mixdeck

import com.mixdeck.ui.PickCheck

/**
 * A song picked in the library. A [Saved] song loads straight away; a [YouTube] or [Spotify] pick
 * still has to be downloaded, which the console does on the deck (the library closes at once).
 */
sealed class SongPick {
    abstract val title: String

    /** A song already saved on this device. */
    data class Saved(val id: String, override val title: String = "") : SongPick()

    /** A YouTube search result or link. */
    data class YouTube(val url: String, val videoId: String, override val title: String, val artist: String, val durationSec: Long) : SongPick()

    /** A song from a Spotify list: matched to its audio on YouTube when it's fetched. */
    data class Spotify(val track: SpotifyTrack) : SongPick() {
        override val title get() = track.title
    }

    /** Plain strings, so the pick can travel in an Android Intent. */
    fun toExtras(): Map<String, String> = when (this) {
        is Saved -> mapOf(KIND to "saved", ID to id, TITLE to title)
        is YouTube -> mapOf(KIND to "youtube", URL to url, VIDEO to videoId, TITLE to title, ARTIST to artist, DURATION to durationSec.toString())
        is Spotify -> mapOf(KIND to "spotify", TITLE to track.title, ARTIST to track.artist, DURATION to track.durationSec.toString())
    }

    companion object {
        const val KIND = "pick"
        /** The saved song's id. The same key as the library's older "track" extra. */
        const val ID = "track"
        const val URL = "pickUrl"
        const val VIDEO = "pickVideo"
        const val TITLE = "pickTitle"
        const val ARTIST = "pickArtist"
        const val DURATION = "pickDuration"
        val KEYS = listOf(KIND, ID, URL, VIDEO, TITLE, ARTIST, DURATION)

        /** The pick written by [toExtras], or null. A bare song id (no kind) is a [Saved] song. */
        fun fromExtras(get: (String) -> String?): SongPick? {
            val title = get(TITLE) ?: ""
            val duration = get(DURATION)?.toLongOrNull() ?: 0L
            return when (get(KIND)) {
                "youtube" -> {
                    val url = get(URL) ?: return null
                    val video = get(VIDEO) ?: return null
                    YouTube(url, video, title, get(ARTIST) ?: "", duration)
                }
                "spotify" -> Spotify(SpotifyTrack(title.ifEmpty { return null }, get(ARTIST) ?: "", duration))
                "saved", null -> get(ID)?.let { Saved(it, title) }
                else -> null
            }
        }
    }
}

/** Where and when a picked song goes: onto [deck] now, replacing what plays, or when it ends. */
data class Placement(val deck: Int, val sync: Boolean, val whenToLoad: When = When.NOW) {
    /**
     * Where a song that had to be downloaded goes once it's saved. "Stop A once it's downloaded"
     * (asked while both decks played) stops A now if the other deck still plays, so the music
     * carries on. If the other deck has stopped meanwhile, the song waits for A's song to end
     * instead: a download never cuts the music off at whatever moment it happens to finish.
     */
    fun afterDownload(otherLive: Boolean): Placement =
        if (whenToLoad == When.REPLACE && !otherLive) copy(whenToLoad = When.ENDS) else this

    enum class When {
        /** Load now; but if the deck is playing by the time the song is ready, wait until it stops. */
        NOW,
        /** Stop the deck's song and load (the user said so). */
        REPLACE,
        /** Load when the deck's song ends (or is stopped). */
        ENDS,
    }

    companion object {
        /**
         * The placement for an answer to [PickCheck.question] about [deck], or null for Cancel.
         * [sync] says whether to SYNC a song loaded on [deck]; [syncOther] the same for the other deck.
         */
        fun of(option: PickCheck.Option, deck: Int, sync: Boolean, syncOther: Boolean = sync): Placement? = when (option) {
            PickCheck.Option.OTHER_DECK -> Placement(1 - deck, syncOther, When.NOW)
            PickCheck.Option.REPLACE -> Placement(deck, sync, When.REPLACE)
            PickCheck.Option.WHEN_ENDS -> Placement(deck, sync, When.ENDS)
            PickCheck.Option.CANCEL -> null
        }
    }
}
