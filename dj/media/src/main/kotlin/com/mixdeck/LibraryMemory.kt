package com.mixdeck

/**
 * What the library remembers while the app runs, so reopening it (LOAD on the other deck right
 * after a download) finds the last search still there. Use it from the UI thread only.
 */
object LibraryMemory {
    /** The YouTube box's text. */
    var query = ""
    /** [Songs | Videos]: YouTube Music songs, or all videos. */
    var songsOnly = true
    /** The results of the last search, including pages loaded with "more". */
    val results = ArrayList<YtResult>()
    /** The last search (it loads further pages), or null before the first one. */
    var search: YouTube.Search? = null
    /** A page of [search]'s results is on its way (one at a time, whichever library screen asked). */
    var fetching = false
    /** My songs' filter text. */
    var mySongsFilter = ""

    /** Every open library screen's "redraw your results list" (see [watch]). */
    private val watchers = LinkedHashSet<() -> Unit>()

    /**
     * Calls [w] whenever [results] change, even when another screen (or one that has closed)
     * asked for the change: every library screen's list shows [results] and must redraw. Stop with [unwatch].
     */
    fun watch(w: () -> Unit) { watchers += w }

    fun unwatch(w: () -> Unit) { watchers -= w }

    private fun changed() = watchers.toList().forEach { it() }

    /** Starts remembering a new search (clears the old results); its first page is on its way. */
    fun newSearch(q: String, s: YouTube.Search) {
        query = q; search = s; results.clear(); fetching = true
        changed()
    }

    /**
     * A page of [s]'s results arrived ([page] is empty if it failed). It's added to [results] only
     * while [s] is still the last search, even when the screen that asked for it has closed, and
     * the open screens redraw. An empty page changes nothing, so nothing redraws: a redraw at the
     * bottom of the list would ask for the failed page again at once. Returns whether it was kept.
     */
    fun pageArrived(s: YouTube.Search, page: List<YtResult>): Boolean {
        if (search !== s) return false
        fetching = false
        if (page.isNotEmpty()) {
            results.addAll(page)
            changed()
        }
        return true
    }
}
