package com.mixdeck

import com.mixdeck.engine.Twists
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline tests of the pure parts of "like this" variety and twist picking. */
class TwistSearchTest {
    private var n = 0
    private fun song(artist: String, title: String, sec: Long = 210) =
        YtResult("https://www.youtube.com/watch?v=v${n}xxxxxxxx".take(43), "v%010d".format(n++), title, artist, sec)

    private val disco = Twists.bucket("disco")!!
    private val funk = Twists.bucket("funk")!!
    private val afro = Twists.bucket("afrobeats")!!

    private val discoSongs = listOf(
        song("Chic", "Le Freak"), song("Sister Sledge", "We Are Family"), song("Donna Summer", "Hot Stuff"),
        song("Chic", "Good Times"), song("Bee Gees", "Stayin' Alive"), song("Mix Channel", "70s Disco Mega Mix", 3600),
    )
    private val funkSongs = listOf(song("James Brown", "Get Up Offa That Thing"), song("Kool & The Gang", "Jungle Boogie"), song("Parliament", "Flash Light"))
    private val afroSongs = listOf(song("Burna Boy", "Last Last"), song("Wizkid", "Essence"), song("Rema", "Calm Down"))

    @Test fun picksAreSpreadAcrossStylesOnePerArtist() {
        val got = TwistSearch.pick(listOf(disco to discoSongs, funk to funkSongs, afro to afroSongs), "Nelly", emptyList(), emptySet(), perBucket = 2, random = Random(1))
        assertEquals(6, got.size)
        assertEquals(listOf(disco, funk, afro), got.take(3).map { it.bucket }, "styles take turns")
        assertEquals(got.size, got.map { Discovery.key(it.artist) }.toSet().size, "one song per artist")
        assertTrue(got.none { "Mega Mix" in it.r.title }, "junk is dropped")
    }

    @Test fun theSeedsOwnSceneIsNoTwist() {
        val hipHop = Twists.bucket("boombap")!!
        val hipHopSongs = listOf(song("Nelly", "Ride Wit Me"), song("Ludacris", "Stand Up"), song("Chingy", "Right Thurr"))
        // The seed's artist is in the style's songs: that's the seed's own scene.
        val got = TwistSearch.pick(listOf(hipHop to hipHopSongs, funk to funkSongs), "Nelly", emptyList(), emptySet(), 3, Random(2))
        assertTrue(got.all { it.bucket == funk })
        // Two of the seed's "like this" artists in a style: same scene, dropped whole.
        val got2 = TwistSearch.pick(listOf(afro to afroSongs, funk to funkSongs), "Nelly", listOf("Burna Boy", "Wizkid ft. Tems"), emptySet(), 3, Random(2))
        assertTrue(got2.all { it.bucket == funk })
        // One scene artist: just that song goes.
        val got3 = TwistSearch.pick(listOf(funk to funkSongs), "Nelly", listOf("James Brown"), emptySet(), 3, Random(2))
        assertEquals(setOf("Kool & The Gang", "Parliament"), got3.map { it.artist }.toSet())
    }

    @Test fun skipsSongsAskedToSkipAndLimitsEachStyle() {
        val skip = setOf(discoSongs[0].id, discoSongs[1].id)
        val got = TwistSearch.pick(listOf(disco to discoSongs), "Nelly", emptyList(), skip, perBucket = 5, random = Random(3))
        assertTrue(got.none { it.r.id in skip })
        assertEquals(setOf("Donna Summer", "Chic", "Bee Gees"), got.map { it.artist }.toSet())
        assertEquals(1, TwistSearch.pick(listOf(disco to discoSongs), "Nelly", emptyList(), emptySet(), perBucket = 1, random = Random(3)).size)
    }

    @Test fun sameArtistMatchesWholeNamesOnly() {
        assertTrue(TwistSearch.sameArtist("nelly", "nelly"))
        assertTrue(TwistSearch.sameArtist("kool and the gang", "kool and the gang"))
        assertFalse(TwistSearch.sameArtist("dj snake", "dj khaled"))
        assertFalse(TwistSearch.sameArtist("", "nelly"))
    }

    @Test fun likeThisGivesTheSeedArtistLittleRoom() {
        val artists = listOf("nelly", "ludacris", "nelly", "nelly", "chingy", "ludacris", "ludacris", "t i", "nelly")
        // Seed artist at most once, others twice; the third Ludacris and the extra Nellys wait.
        assertEquals(listOf(0, 1, 4, 5, 7), Discovery.varietyPick(artists, "nelly", limit = 5, maxSeed = 1, maxOther = 2))
        // Short list: other artists come back first, then the seed's, only up to half the limit.
        assertEquals(listOf(0, 1, 4, 5, 7, 6), Discovery.varietyPick(artists, "nelly", limit = 10, maxSeed = 1, maxOther = 2))
        assertEquals(listOf(0, 1, 2), Discovery.varietyPick(listOf("nelly", "nelly", "nelly", "nelly"), "nelly", limit = 6, maxSeed = 1, maxOther = 2))
        // "Στέλιος Καζαντζίδης" and "Καζαντζίδης" are one artist.
        val k = listOf(Discovery.key("Καζαντζίδης"), Discovery.key("Στέλιος Καζαντζίδης"), Discovery.key("Νταλάρας"))
        assertEquals(listOf(0, 2), Discovery.varietyPick(k, Discovery.key("Στέλιος Καζαντζίδης"), limit = 2, maxSeed = 1, maxOther = 2))
    }

    @Test fun mashupsAndReversedSongsAreJunk() {
        assertEquals("mashup", Discovery.junkReason(song("Video Game Songs", "Nelly vs Warren G - Hot In Herre vs Regulate [DJ Hero 2 | No Crowd]")))
        assertEquals("mashup", Discovery.junkReason(song("wazzumz", "DJ Hero 2 - Informer vs. ABC")))
        assertEquals("slowed/sped up/8D", Discovery.junkReason(song("Lewis Birmingham", "Nelly - Hot In Herre [REVERSE]")))
        assertEquals("not a song", Discovery.junkReason(song("Real Ones Know Media", "Nelly performs Hot in Herre at Fat Joe & Ja Rule Verzuz")))
        assertEquals(null, Discovery.junkReason(song("x", "\"I'm Back\" Murphy Lee vs Jay E ft Yak Boy Fresh")))
        assertEquals(null, Discovery.junkReason(song("x", "Reverse - The Song")))
    }
}
