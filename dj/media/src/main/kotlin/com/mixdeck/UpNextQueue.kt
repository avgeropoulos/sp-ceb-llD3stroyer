package com.mixdeck

import com.mixdeck.engine.Mixer
import com.mixdeck.ui.Console
import com.mixdeck.ui.PickCheck

/**
 * Songs waiting to load on a deck until its song ends, so a pick never stops the music. Each host
 * keeps one, calls [due] every 250 ms while [waiting] and loads what it returns.
 *
 * During a MIX the deck being mixed out plays until MIX finishes and pauses it, so a song picked
 * mid-blend with "Load when A ends" loads as soon as the blend is done.
 *
 * Use it on the UI thread. It reads the decks without the mixer lock, like the console does.
 */
class UpNextQueue(private val mixer: Mixer) {
    /** A saved song waiting for its deck; [sync] matches its tempo to the other deck once loaded. */
    class Entry(val id: String, val title: String, val sync: Boolean)

    private val q = arrayOfNulls<Entry>(2)

    /** The song waiting for [deck], or null. */
    operator fun get(deck: Int): Entry? = q[deck]

    /** Forgets the song waiting for [deck] (a newer pick, a load, or "Cancel up next"). */
    fun clear(deck: Int) { q[deck] = null }

    /** True while a song waits: keep polling. */
    val waiting: Boolean get() = q.any { it != null }

    /**
     * Whether a song placed with [p] has to wait. REPLACE never does (the user chose to stop the
     * deck). ENDS waits while the deck plays, a CUE preview included. NOW waits only while the
     * deck is really playing (it went live while the song downloaded). The hosts ask again once a
     * song is decoded, as the deck may have started meanwhile: it reads only the decks, so the
     * loader may call it under the mixer lock.
     */
    fun mustWait(p: Placement): Boolean = when (p.whenToLoad) {
        Placement.When.REPLACE -> false
        Placement.When.ENDS -> mixer.decks[p.deck].playing
        Placement.When.NOW -> PickCheck.live(mixer, p.deck)
    }

    /** Queues [e] for [deck], replacing any song waiting there, and returns the notice to show. */
    fun add(deck: Int, e: Entry): String {
        q[deck] = e
        return "Up next: \"${e.title}\" loads on deck ${Console.deckName(deck)} when \"${mixer.decks[deck].title}\" ends"
    }

    /** The waiting songs whose deck has stopped, taken off the queue: load each one now. */
    fun due(): List<Pair<Int, Entry>> = (0..1).mapNotNull { k ->
        val e = q[k]
        if (e == null || mixer.decks[k].playing) null else { q[k] = null; k to e }
    }

    companion object {
        /** The notice once a waiting song has loaded on [deck]. */
        fun readyNotice(deck: Int, e: Entry) = "Up next is ready: \"${e.title}\" on deck ${Console.deckName(deck)}"
    }
}
