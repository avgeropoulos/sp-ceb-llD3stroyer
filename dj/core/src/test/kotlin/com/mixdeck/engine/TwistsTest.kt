package com.mixdeck.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwistsTest {
    private fun key(c: String) = MusicalKey.fromCamelot(c)!!

    private fun track(id: String, bpm: Double, camelot: String?, energy: Float = 7f, conf: Float = 0.8f) =
        TrackProfile(id, "Song $id", "Artist $id", bpm, camelot?.let(::key), conf, energy, null, 200.0)

    private val seed = track("seed", 107.0, "8A")
    private val now = NowPlaying(seed, 0.0)
    private fun bucket(id: String) = Twists.bucket(id)!!

    @Test fun tempoLinesUpAtTheSameTempoOrHalfDouble() {
        assertEquals(0 to 0.0, Twists.tempoMatch(107.0, bucket("funk")))
        val (hd, off) = Twists.tempoMatch(107.0, bucket("disco"))!!
        assertEquals(0, hd); assertEquals(3.0 / 110, off, 1e-9)
        assertEquals(1 to 0.0, Twists.tempoMatch(87.0, bucket("dnb")), "87 BPM meets drum & bass at double time")
        assertEquals(-1 to 0.0, Twists.tempoMatch(160.0, bucket("reggae")), "160 BPM meets reggae at half time")
        assertNull(Twists.tempoMatch(0.0, bucket("disco")))
    }

    @Test fun planPicksStylesNearTheTempoAndEnergy() {
        val plans = Twists.plan(107.0, 7f, count = 4, random = Random(3))
        assertEquals(4, plans.size)
        assertEquals(4, plans.map { it.bucket.id }.distinct().size)
        for (p in plans) {
            assertTrue(p.tempoOff <= Twists.PLAN_TEMPO, "${p.bucket.name} is near 107 BPM")
            assertTrue(p.energyOff <= Twists.PLAN_ENERGY)
        }
        // Styles far from 107 BPM never come up, whatever the luck.
        val seen = (0 until 50).flatMap { Twists.plan(107.0, 7f, count = 4, random = Random(it)) }.map { it.bucket.id }.toSet()
        assertFalse("house" in seen || "techno" in seen || "reggae" in seen, "seen: $seen")
        assertTrue("funk" in seen && "afrobeats" in seen && "newjack" in seen, "seen: $seen")
    }

    @Test fun planLeavesOutExcludedStylesAndVaries() {
        val all = Twists.BUCKETS.map { it.id }.toSet()
        assertTrue(Twists.plan(107.0, 7f, exclude = all).isEmpty())
        val plans = Twists.plan(107.0, 7f, exclude = setOf("funk", "afrobeats"), count = 10, random = Random(1))
        assertTrue(plans.none { it.bucket.id == "funk" || it.bucket.id == "afrobeats" })
        val firsts = (0 until 30).map { Twists.plan(107.0, 7f, count = 1, random = Random(it)).single().bucket.id }.toSet()
        assertTrue(firsts.size >= 3, "different runs bring different styles: $firsts")
        assertTrue(Twists.plan(0.0, 7f).isEmpty(), "no tempo, no twists")
    }

    @Test fun planKnowsDrumAndBassAtDoubleTime() {
        val plans = Twists.plan(86.0, 8f, count = 20, random = Random(5))
        val dnb = plans.first { it.bucket.id == "dnb" }
        assertEquals(1, dnb.halfDouble)
    }

    @Test fun sceneOfTheSongFromItsTitle() {
        assertEquals(setOf("deephouse", "house"), Twists.sceneOf("Daft Punk - One More Time (Deep House Remix)"))
        assertTrue("reggaeton" in Twists.sceneOf("Daddy Yankee - Gasolina (Reggaeton Mix)"))
        assertTrue(Twists.sceneOf("Nelly - Hot in Herre").isEmpty())
    }

    @Test fun camelotNeighbours() {
        assertTrue(Twists.camelotNeighbours(key("8A"), key("8A")))
        assertTrue(Twists.camelotNeighbours(key("8A"), key("9A")))
        assertTrue(Twists.camelotNeighbours(key("8A"), key("7A")))
        assertTrue(Twists.camelotNeighbours(key("8A"), key("8B")))
        assertTrue(Twists.camelotNeighbours(key("12A"), key("1A")))
        assertFalse(Twists.camelotNeighbours(key("8A"), key("9B")))
        assertFalse(Twists.camelotNeighbours(key("8A"), key("10A")))
        assertFalse(Twists.camelotNeighbours(key("8A"), key("3A")))
    }

    @Test fun onlySongsThatReallyMixBecomeTwists() {
        fun misfit(c: TrackProfile) = Twists.misfit(NextTrack.score(now, c), seed)
        assertNull(misfit(track("ok", 107.0, "9A")))
        assertNull(misfit(track("relative", 107.0, "8B")))
        assertNull(misfit(track("dnb", 214.0, "8A")), "double time is fine")
        assertEquals("tempo", misfit(track("fast", 120.0, "8A")))
        assertEquals("tempo", misfit(track("nobeat", 0.0, "8A")))
        assertEquals("key", misfit(track("clash", 107.0, "3A")))
        assertEquals("key", misfit(track("far", 107.0, "10A")))
        assertEquals("energy", misfit(track("calm", 107.0, "8A", energy = 3f)))
        // An unreliable key only passes when everything else fits very well.
        val unsure = track("unsure", 107.0, "3A", conf = 0.1f)
        val s = NextTrack.score(now, unsure)
        assertEquals(if (s.stars >= 4) null else "key", Twists.misfit(s, seed))
    }

    @Test fun reasonSaysStyleTempoAndKey() {
        val disco = bucket("disco")
        assertEquals("Twist: 70s disco at the same 107 BPM, 8A → 9A",
            Twists.reason(disco, NextTrack.score(now, track("a", 107.0, "9A")), 107.0))
        assertEquals("Twist: drum & bass at double time (87 → 174 BPM), same key 8A",
            Twists.reason(bucket("dnb"), NextTrack.score(NowPlaying(track("s", 87.0, "8A"), 0.0), track("b", 174.0, "8A")), 87.0))
        assertEquals("Twist: roots reggae at half time (160 → 80 BPM), 8A → 8B",
            Twists.reason(bucket("reggae"), NextTrack.score(NowPlaying(track("s", 160.0, "8A"), 0.0), track("c", 80.0, "8B")), 160.0))
        // Out of tune after the tempo change, or key unknown: the key isn't claimed.
        val nudged = NextTrack.score(now, track("d", 107.0 / 1.03, "8A"))
        assertEquals("Twist: 70s disco at 107 BPM (+3%)", Twists.reason(disco, nudged, 107.0))
        assertEquals("Twist: 70s disco at the same 107 BPM", Twists.reason(disco, NextTrack.score(now, track("e", 107.0, null)), 107.0))
    }

    @Test fun everyStyleIsUsable() {
        for (b in Twists.BUCKETS) {
            assertTrue(b.minBpm in 60.0..b.maxBpm && b.maxBpm <= 200, b.id)
            assertTrue(b.minEnergy <= b.maxEnergy, b.id)
            assertTrue(b.queries.isNotEmpty() && b.name.isNotBlank(), b.id)
            assertNotNull(Twists.bucket(b.id))
        }
        assertEquals(Twists.BUCKETS.size, Twists.BUCKETS.map { it.id }.toSet().size)
    }
}
