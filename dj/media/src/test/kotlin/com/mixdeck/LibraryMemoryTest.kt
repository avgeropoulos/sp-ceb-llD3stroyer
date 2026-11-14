package com.mixdeck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The library's remembered search: pages that arrive late, after the screen closed or a newer search. */
class LibraryMemoryTest {
    private fun r(id: String) = YtResult("https://youtu.be/$id", id, id, "Nelly", 200)

    @Test fun onlyTheLastSearchsPagesAreKept() {
        val first = YouTube.Search("nelly", songsOnly = true)
        LibraryMemory.newSearch("nelly", first)
        assertTrue(LibraryMemory.fetching, "the first page is on its way")
        assertTrue(LibraryMemory.pageArrived(first, listOf(r("a"), r("b"))))
        assertFalse(LibraryMemory.fetching, "the next page may be asked for")
        assertEquals(listOf("a", "b"), LibraryMemory.results.map { it.id })
        // A page asked for before a newer search arrives late: it's dropped.
        LibraryMemory.fetching = true
        val second = YouTube.Search("grillz", songsOnly = true)
        LibraryMemory.newSearch("grillz", second)
        assertFalse(LibraryMemory.pageArrived(first, listOf(r("c"))))
        assertTrue(LibraryMemory.results.isEmpty())
        assertTrue(LibraryMemory.fetching, "still waiting for the new search's first page")
        assertTrue(LibraryMemory.pageArrived(second, emptyList()), "a failed page still ends the wait")
        assertFalse(LibraryMemory.fetching)
    }

    /**
     * Two library screens open at once (one opened from Share): both redraw whenever the shared
     * results change, whichever screen asked, and also after the other one closed. A failed page
     * changes nothing, so no screen redraws (a redraw at the bottom of the list would ask again).
     */
    @Test fun everyOpenScreenRedrawsOnEveryChange() {
        var lower = 0; var upper = 0
        val l: () -> Unit = { lower++ }; val u: () -> Unit = { upper++ }
        LibraryMemory.watch(l); LibraryMemory.watch(u)
        try {
            val s = YouTube.Search("nelly", songsOnly = true)
            LibraryMemory.newSearch("nelly", s)
            assertEquals(1 to 1, lower to upper, "the cleared list")
            LibraryMemory.pageArrived(s, listOf(r("a")))
            assertEquals(2 to 2, lower to upper)
            // The upper screen closes; the lower one's "more results" page still reaches it.
            LibraryMemory.unwatch(u)
            LibraryMemory.fetching = true
            LibraryMemory.pageArrived(s, listOf(r("b")))
            assertEquals(3 to 2, lower to upper)
            // A failed page: no redraw, but the next scroll may ask again.
            LibraryMemory.fetching = true
            assertTrue(LibraryMemory.pageArrived(s, emptyList()))
            assertEquals(3, lower)
            assertFalse(LibraryMemory.fetching)
            // A late page of an older search: dropped, no redraw.
            LibraryMemory.newSearch("grillz", YouTube.Search("grillz", songsOnly = true))
            assertFalse(LibraryMemory.pageArrived(s, listOf(r("c"))))
            assertEquals(4, lower)
        } finally {
            LibraryMemory.unwatch(l); LibraryMemory.unwatch(u)
        }
    }
}
