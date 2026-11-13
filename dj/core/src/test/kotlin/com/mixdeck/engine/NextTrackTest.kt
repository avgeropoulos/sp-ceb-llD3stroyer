package com.mixdeck.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextTrackTest {

    private fun key(c: String) = MusicalKey.fromCamelot(c)!!

    private fun track(
        id: String, bpm: Double, camelot: String?, energy: Float = 6f, conf: Float = 0.9f,
        title: String = "Song $id", artist: String = "Artist $id", timbre: FloatArray? = null,
    ) = TrackProfile(id, title, artist, bpm, camelot?.let(::key), conf, energy, timbre, 200.0)

    /** The BPM a song needs so that SYNC to [nowBpm] is exactly [pitch] (0.02 = +2%). */
    private fun bpmFor(nowBpm: Double, pitch: Double) = nowBpm / (1 + pitch)

    private fun ids(list: List<Suggestion>) = list.map { it.track.id }

    private val now124 = track("now", 124.0, "8A")
    private val playing = NowPlaying(now124, 0.0)

    // ------------------------------------------------------------------ ordering

    @Test fun keyMatchBeatsSmallTempoDifferences() {
        val same1 = track("same1", bpmFor(124.0, 0.01), "8A")
        val rel1 = track("rel1", bpmFor(124.0, 0.01), "8B")
        val plus5 = track("plus5", bpmFor(124.0, 0.05), "2A") // +5% raises 2A a semitone, to 9A
        val clash1 = track("clash1", bpmFor(124.0, 0.01), "2A")
        val ranked = NextTrack.rank(playing, listOf(clash1, plus5, rel1, same1))
        assertEquals(listOf("same1", "rel1", "plus5", "clash1"), ids(ranked))

        val p5 = ranked[2]
        assertEquals(key("9A"), p5.keyAfterSync)
        assertEquals("+1", p5.camelotMove)
        assertEquals(0.05, p5.pitchToSync, 1e-9)
        assertEquals("same key", ranked[0].camelotMove)
        assertEquals("relative", ranked[1].camelotMove)
        assertEquals("clash", ranked[3].camelotMove)
        assertEquals(5, ranked[0].stars)
        assertTrue(ranked[3].stars <= 2, "a clash is at most 2 stars")
    }

    @Test fun twoPercentOnVinylDecksIsABitSour() {
        // +2% without key lock = +0.34 semitone: the nearest key still matches but it sounds sour.
        val same1 = track("same1", bpmFor(124.0, 0.01), "8A")
        val same2 = track("same2", bpmFor(124.0, 0.02), "8A")
        val rel2 = track("rel2", bpmFor(124.0, 0.02), "8B")
        val clash1 = track("clash1", bpmFor(124.0, 0.01), "2A")
        val ranked = NextTrack.rank(playing, listOf(clash1, rel2, same2, same1))
        assertEquals(listOf("same1", "same2", "rel2", "clash1"), ids(ranked))

        val s2 = ranked[1]
        assertNull(s2.keyAfterSync, "0.34 semitone up falls between keys")
        assertEquals("same key (detuned)", s2.camelotMove)
        assertTrue(s2.reasons.any { !it.good && "sour" in it.text })
        assertTrue(s2.summary.startsWith("Slightly out of tune"))
        assertEquals(4, s2.stars)
    }

    @Test fun syncThatTurnsAClashIntoTheSameKeyBecomesTheTopPick() {
        // 1A clashes with 8A, but +6% raises it a semitone: 1A → 8A (Camelot +7 = one semitone up).
        val fix6 = track("fix6", bpmFor(124.0, 0.06), "1A")
        val others = listOf(
            track("clash0", 124.0, "3B"),
            track("same2", bpmFor(124.0, 0.02), "8A"),
            track("boost1", bpmFor(124.0, 0.01), "10A"),
            track("rel3", bpmFor(124.0, 0.03), "8B"),
            track("unknown0", 124.0, null),
        )
        val ranked = NextTrack.rank(playing, others + fix6)
        val top = ranked.first()
        assertEquals("fix6", top.track.id)
        assertEquals(key("8A"), top.keyAfterSync)
        assertEquals("same key", top.camelotMove)
        assertTrue(top.summary.startsWith("Key fits after sync"), top.summary)
        assertTrue(top.reasons.any { it.good && "clashes" in it.text && "sync" in it.text }, top.reasons.toString())

        // And the other way round: a same-key song that needs +6% gets pushed a semitone off.
        val spoil6 = NextTrack.score(playing, track("spoil6", bpmFor(124.0, 0.06), "8A"))
        assertEquals(key("3A"), spoil6.keyAfterSync)
        assertEquals("+7 (semitone lift)", spoil6.camelotMove)
        assertTrue(spoil6.reasons.any { !it.good && "would fit" in it.text })
        assertTrue(spoil6.score < top.score - 25)
    }

    @Test fun upPrefersOneOrTwoLevelsMoreEnergy() {
        val now = NowPlaying(track("now", 124.0, "8A", energy = 5f), 0.0)
        val cands = listOf(3f, 4f, 5f, 6f, 7f, 9f).map { track("energy${it.toInt()}", 124.0, "8A", energy = it) }

        val up = ids(NextTrack.rank(now, cands, Direction.UP))
        assertEquals(listOf("energy6", "energy7"), up.take(2))
        assertEquals(setOf("energy4", "energy3"), up.takeLast(2).toSet())

        val down = ids(NextTrack.rank(now, cands, Direction.DOWN))
        assertEquals(listOf("energy4", "energy3"), down.take(2))
        assertEquals(setOf("energy7", "energy9"), down.takeLast(2).toSet())

        assertEquals("energy5", ids(NextTrack.rank(now, cands, Direction.KEEP)).first())

        val s = NextTrack.score(now, cands[3], Direction.UP)
        assertTrue("a little more energy" in s.summary, s.summary)
        assertTrue(s.reasons.any { it.good && "builds" in it.text })
    }

    @Test fun upPrefersAnEnergyBoostKeyChangeOverDroppingEnergy() {
        val now = NowPlaying(track("now", 93.0, "8A", energy = 6f), 0.0)
        val boost = track("boost", 93.0, "10A", energy = 7f)
        val calmer = track("calmer", 93.0, "8A", energy = 4f)
        assertEquals(listOf("boost", "calmer"), ids(NextTrack.rank(now, listOf(calmer, boost), Direction.UP)))
        assertEquals("+2 (energy boost)", NextTrack.score(now, boost, Direction.UP).camelotMove)
    }

    // ------------------------------------------------------------------ tempo

    @Test fun halfAndDoubleTimeAreDetected() {
        val hh87 = track("hh87", 87.0, "4A")
        val dnb174 = track("dnb174", 174.0, "4A")

        val dbl = NextTrack.score(NowPlaying(hh87, 0.0), dnb174)
        assertEquals(1, dbl.halfDouble)
        assertEquals(0.0, dbl.pitchToSync, 1e-9)
        assertEquals(key("4A"), dbl.keyAfterSync)
        assertEquals("same key", dbl.camelotMove)
        assertTrue(dbl.reasons.any { "double time" in it.text })
        assertTrue("double time" in dbl.summary)

        val half = NextTrack.score(NowPlaying(dnb174, 0.0), hh87)
        assertEquals(-1, half.halfDouble)
        assertEquals(0.0, half.pitchToSync, 1e-9)
        assertTrue(half.reasons.any { "half time" in it.text })

        val dnb172 = NextTrack.score(NowPlaying(hh87, 0.0), track("dnb172", 172.0, "4A"))
        assertEquals(1, dnb172.halfDouble)
        assertEquals(174.0 / 172.0 - 1, dnb172.pitchToSync, 1e-9)
    }

    @Test fun syncFollowsTheDeckRule() {
        // Same folding as Deck.syncTo: ratio kept within 0.67..1.5, the playing deck's pitch included.
        assertEquals(93.0 / 128.0 - 1, NextTrack.score(NowPlaying(track("a", 93.0, "8A"), 0.0), track("b", 128.0, "8A")).pitchToSync, 1e-9)
        assertEquals(124.0 * 1.04 / 120.0 - 1, NextTrack.score(NowPlaying(now124, 0.04), track("c", 120.0, "8A")).pitchToSync, 1e-9)
        val s = NextTrack.score(NowPlaying(track("d", 60.0, "8A"), 0.0), track("e", 250.0, "8A"))
        assertEquals(2, s.halfDouble) // 60 → 120 → 240 against 250
        assertEquals(240.0 / 250.0 - 1, s.pitchToSync, 1e-9)
    }

    @Test fun bigTempoStretchesAreNotRescuedByAKeyMatch() {
        val hh = NowPlaying(track("hh", 93.0, "8A"), 0.0)
        val house = NextTrack.score(hh, track("house", 128.0, "8A")) // −27%
        assertEquals(1, house.stars)
        assertTrue(house.reasons.any { "sluggish" in it.text })
        val fourteen = NextTrack.score(hh, track("slow", 81.0, "8A")) // +14.8%
        assertTrue(fourteen.stars <= 2)
        assertTrue(fourteen.reasons.any { "chipmunky" in it.text })
    }

    @Test fun unknownBpmMeansNoSync() {
        val s = NextTrack.score(playing, track("ambient", 0.0, "8A"))
        assertEquals(0.0, s.pitchToSync)
        assertEquals(0, s.halfDouble)
        assertEquals(key("8A"), s.keyAfterSync)
        assertTrue(s.score <= 60f)
        assertTrue(s.reasons.any { "SYNC can't" in it.text })
        assertTrue("no beat sync" in s.summary)

        val fromAmbient = NextTrack.score(NowPlaying(track("amb", Double.NaN, "8A"), 0.0), track("beat", 124.0, "8A"))
        assertTrue(fromAmbient.reasons.any { "playing song" in it.text && "SYNC can't" in it.text })
    }

    // ------------------------------------------------------------------ key details

    @Test fun camelotMovesAreClassifiedLikeTheChart() {
        fun move(a: String, b: String) = NextTrack.relation(key(a), key(b)).move
        assertEquals(NextTrack.Move.SAME, move("8A", "8A"))
        assertEquals(NextTrack.Move.STEP, move("8A", "9A"))
        assertEquals(NextTrack.Move.STEP, move("8A", "7A"))
        assertEquals(NextTrack.Move.STEP, move("12B", "1B"))
        assertEquals(NextTrack.Move.RELATIVE, move("8A", "8B"))
        assertEquals(NextTrack.Move.DIAGONAL_NEAR, move("8A", "7B"))
        assertEquals(NextTrack.Move.DIAGONAL_FAR, move("8A", "9B"))
        assertEquals(NextTrack.Move.DIAGONAL_NEAR, move("8B", "9A"))
        assertEquals(NextTrack.Move.DIAGONAL_FAR, move("8B", "7A"))
        assertEquals(NextTrack.Move.BOOST, move("8A", "10A"))
        assertEquals(NextTrack.Move.DROP, move("8A", "6A"))
        assertEquals(NextTrack.Move.SEMITONE_LIFT, move("8A", "3A"))
        assertEquals(NextTrack.Move.SEMITONE_LIFT, move("8B", "3B"))
        assertEquals(NextTrack.Move.PARALLEL, move("8A", "11B")) // A minor → A major
        assertEquals(NextTrack.Move.PARALLEL, move("8B", "5A")) // C major → C minor
        assertEquals(NextTrack.Move.CLASH, move("8A", "2A"))
        assertEquals(NextTrack.Move.CLASH, move("8A", "3B"))

        assertEquals(7, NextTrack.sharedNotes(key("8A"), key("8B")))
        assertEquals(6, NextTrack.sharedNotes(key("8A"), key("9A")))
        assertEquals(5, NextTrack.sharedNotes(key("8A"), key("10A")))
        assertEquals(2, NextTrack.sharedNotes(key("8A"), key("3A")))
        // +7 on the wheel is exactly one semitone up, which is what a +6% vinyl pitch does.
        assertEquals(key("3A"), key("8A").transpose(1))
    }

    @Test fun unknownKeyIsNeutral() {
        val same = track("same0", 124.0, "8A")
        val unknown = track("unknown0", 124.0, null)
        val clash = track("clash0", 124.0, "2A")
        val ranked = NextTrack.rank(playing, listOf(clash, unknown, same))
        assertEquals(listOf("same0", "unknown0", "clash0"), ids(ranked))
        val u = ranked[1]
        assertNull(u.camelotMove)
        assertNull(u.keyAfterSync)
        assertEquals(72.5f, u.score, 0.01f) // half the key points, everything else perfect
        assertTrue(u.reasons.any { "Key unknown" in it.text })

        // Playing song's key unknown: key can't tell candidates apart.
        val nowUnknown = NowPlaying(track("nowU", 124.0, null), 0.0)
        assertEquals(NextTrack.score(nowUnknown, same).score, NextTrack.score(nowUnknown, clash).score)
    }

    @Test fun lowKeyConfidenceTurnsTheKeyAdviceDown() {
        val sure = NextTrack.score(playing, track("clashSure", 124.0, "2A", conf = 0.9f))
        val unsure = NextTrack.score(playing, track("clashUnsure", 124.0, "2A", conf = 0.35f))
        val noIdea = NextTrack.score(playing, track("clashNoIdea", 124.0, "2A", conf = 0.1f))
        assertTrue(sure.score < unsure.score && unsure.score < noIdea.score)
        assertEquals(72.5f, noIdea.score, 0.01f)
        assertTrue(unsure.reasons.any { "uncertain" in it.text })
        assertTrue("key unsure" in unsure.summary)
        assertTrue(sure.reasons.none { "uncertain" in it.text })
    }

    @Test fun pitchedPlayingDeckIsAccountedFor() {
        // At +6% the playing 8A song sounds a semitone higher, in 3A.
        val up6 = NowPlaying(now124, 0.06)
        val in3A = NextTrack.score(up6, track("in3A", 124.0 * 1.06, "3A"))
        assertEquals("same key", in3A.camelotMove)
        assertEquals(key("3A"), in3A.keyAfterSync)
        assertTrue(in3A.reasons.any { "sounds in 3A" in it.text })
        assertEquals("clash", NextTrack.score(up6, track("in8A", 124.0 * 1.06, "8A")).camelotMove)

        // At +3% it sits between keys: a same-BPM 8A song synced up the same amount stays in tune …
        val up3 = NowPlaying(now124, 0.03)
        val synced = NextTrack.score(up3, track("same124", 124.0, "8A"))
        assertEquals("same key", synced.camelotMove)
        assertNull(synced.keyAfterSync)
        assertTrue(synced.reasons.any { it.good && "stay in tune" in it.text })
        // … but an 8A song already at that tempo plays at 0% and is half a semitone off.
        val unshifted = NextTrack.score(up3, track("same127", 124.0 * 1.03, "8A"))
        assertEquals("same key (detuned)", unshifted.camelotMove)
        assertTrue(unshifted.score < synced.score - 10)
    }

    @Test fun timbreSimilarityBreaksTies() {
        val now = NowPlaying(track("now", 124.0, "8A", timbre = floatArrayOf(1f, 0.5f, 0.2f, 0f)), 0.0)
        val similar = track("similar", 124.0, "8A", timbre = floatArrayOf(0.9f, 0.55f, 0.25f, 0.05f))
        val noVector = track("novector", 124.0, "8A")
        val different = track("different", 124.0, "8A", timbre = floatArrayOf(0f, 0.2f, 0.4f, 1f))
        assertEquals(listOf("similar", "novector", "different"), ids(NextTrack.rank(now, listOf(different, noVector, similar))))
        assertTrue("similar sound" in NextTrack.score(now, similar).summary)
        // Mismatched lengths are treated as unknown, not as an error.
        assertEquals(
            NextTrack.score(now, noVector).score,
            NextTrack.score(now, track("short", 124.0, "8A", timbre = floatArrayOf(1f, 2f))).score,
        )
    }

    // ------------------------------------------------------------------ library handling

    @Test fun recentlyPlayedAndOtherUploadsAreLeftOut() {
        val now = NowPlaying(track("np", 171.0, "4A", title = "Blinding Lights", artist = "The Weeknd"), 0.0)
        val cands = listOf(
            now.track,
            track("reup", 171.0, "4A", title = "The Weeknd - Blinding Lights (Official Audio)", artist = "TheWeekndVEVO"),
            track("sy1", 118.0, "1B", title = "Save Your Tears", artist = "The Weeknd"),
            track("sy2", 118.0, "1B", title = "The Weeknd - Save Your Tears (Lyrics)", artist = "Pop Lyrics Hub"),
            track("toma", 169.0, "4A", title = "Take On Me", artist = "a-ha"),
            track("tomb", 169.0, "4A", title = "a-ha - Take On Me (Official Video)", artist = "a-ha"),
            track("lev", 103.0, "6B", title = "Levitating", artist = "Dua Lipa"),
        )
        val ranked = NextTrack.rank(now, cands, recentlyPlayed = listOf("sy1"))
        val got = ids(ranked)
        assertEquals(2, got.size, got.toString())
        assertTrue(got[0] == "toma" || got[0] == "tomb")
        assertEquals("lev", got[1])

        // Free text works for songs that aren't in the list.
        val ranked2 = NextTrack.rank(now, cands, recentlyPlayed = listOf("Dua Lipa - Levitating"))
        assertTrue("lev" !in ids(ranked2))
    }

    @Test fun songIdentityIgnoresUploadNoiseButNotRemixes() {
        fun same(a: Pair<String, String>, b: Pair<String, String>) =
            NextTrack.songKeys(a.first, a.second).any { it in NextTrack.songKeys(b.first, b.second) }
        val plain = "God's Plan" to "Drake"
        assertTrue(same(plain, "Drake - God's Plan (Official Video)" to "DrakeVEVO"))
        assertTrue(same(plain, "Drake - God’s Plan [Lyrics]" to "Rap Hits 2024"))
        assertTrue(same(plain, "God's Plan" to "Drake - Topic"))
        assertTrue(same("One Kiss (with Dua Lipa)" to "Calvin Harris", "Calvin Harris, Dua Lipa - One Kiss (Official Video)" to "Calvin Harris"))
        assertTrue(same("Beyoncé - Halo (Remastered 2019)" to "", "Halo" to "Beyonce"))
        assertTrue(!same(plain, "God's Plan (Skrillex Remix)" to "Drake"))
        assertTrue(!same(plain, "Hotline Bling" to "Drake"))
        assertTrue(!same("Titanium" to "Sia", "Chandelier" to "Sia"))
    }

    @Test fun limitIsRespected() {
        val cands = (1..10).map { track("song$it", 124.0 + it, "8A") }
        assertEquals(3, NextTrack.rank(playing, cands, limit = 3).size)
        assertTrue(NextTrack.rank(playing, cands, limit = 0).isEmpty())
        val all = NextTrack.rank(playing, cands)
        assertEquals(all.sortedByDescending { it.score }.map { it.score }, all.map { it.score })
    }

    // ------------------------------------------------------------------ robustness

    @Test fun scoresStarsAndTextsStaySaneOnMessyData() {
        val rnd = Random(7)
        fun maybeKey() = if (rnd.nextInt(6) == 0) null else MusicalKey.fromIndex(rnd.nextInt(24))
        fun randomTrack(i: Int) = TrackProfile(
            "t$i", "Title $i", "Artist $i",
            when (rnd.nextInt(8)) { 0 -> 0.0; 1 -> Double.NaN; else -> 60 + rnd.nextDouble() * 140 },
            maybeKey(),
            if (rnd.nextInt(8) == 0) Float.NaN else rnd.nextFloat(),
            if (rnd.nextInt(5) == 0) Float.NaN else 1 + rnd.nextFloat() * 9,
            when (rnd.nextInt(4)) { 0 -> null; 1 -> FloatArray(3) { rnd.nextFloat() }; else -> FloatArray(8) { rnd.nextFloat() } },
            180.0,
        )
        repeat(3000) { i ->
            val pitch = if (i % 50 == 0) Double.NaN else (rnd.nextDouble() - 0.5) * 0.3
            val s = NextTrack.score(NowPlaying(randomTrack(-i), pitch), randomTrack(i), Direction.entries[i % 3])
            assertTrue(s.score in 0f..100f, "score ${s.score}")
            assertTrue(s.stars in 1..5)
            assertTrue(!s.pitchToSync.isNaN())
            assertTrue(s.summary.isNotBlank() && "NaN" !in s.summary)
            assertTrue(s.reasons.isNotEmpty() && s.reasons.none { "NaN" in it.text || "Infinity" in it.text })
            assertTrue(s.reasons.sumOf { r -> Regex("piano key").findAll(r.text).count() } <= 1, "semitone explained once")
        }
    }
}
