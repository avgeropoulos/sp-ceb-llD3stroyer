package com.mixdeck.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Optional: NextTrack's sync maths must equal what Deck.syncTo really does. */
class NextTrackDeckSyncTest {
    private fun deck(i: Int, bpm: Double): Deck {
        val d = Deck(i, 44100)
        val pcm = ShortArrayPcm(ShortArray(44100 * 2), 44100)
        val a = TrackAnalysis(bpm, 0.0, 150, FloatArray(150), FloatArray(150), FloatArray(150))
        d.load(pcm, a, "t$i", "a$i", -14.0)
        return d
    }

    @Test fun pitchToSyncEqualsDeckSyncTo() {
        val rnd = Random(3)
        repeat(5000) {
            val masterBpm = 60 + rnd.nextDouble() * 140
            val candBpm = 60 + rnd.nextDouble() * 140
            val masterPitch = (rnd.nextDouble() - 0.5) * 0.16
            val master = deck(0, masterBpm).apply { pitch = masterPitch }
            val other = deck(1, candBpm)
            other.syncTo(master)
            val now = TrackProfile("m", "m", "m", masterBpm, null, 0f, Float.NaN, null, 1.0)
            val cand = TrackProfile("c", "c", "c", candBpm, null, 0f, Float.NaN, null, 1.0)
            val s = NextTrack.score(NowPlaying(now, master.pitch), cand)
            assertEquals(other.pitch, s.pitchToSync, 1e-12, "master $masterBpm@${master.pitch} cand $candBpm")
            val octaves = Math.log(other.effectiveBpm / master.effectiveBpm) / Math.log(2.0)
            assertEquals(s.halfDouble.toDouble(), octaves, 1e-9)
        }
    }

    @Test fun keyAfterSyncEqualsDeckSoundingKey() {
        val rnd = Random(5)
        repeat(3000) {
            val masterBpm = 70 + rnd.nextDouble() * 110
            val candBpm = 70 + rnd.nextDouble() * 110
            val k = MusicalKey.fromIndex(rnd.nextInt(24))
            val master = deck(0, masterBpm)
            val other = deck(1, candBpm)
            other.setAnalysis(other.analysis!!.withKey(k, 0.9f))
            other.syncTo(master)
            val now = TrackProfile("m", "m", "m", masterBpm, MusicalKey.fromIndex(0), 0.9f, Float.NaN, null, 1.0)
            val cand = TrackProfile("c", "c", "c", candBpm, k, 0.9f, Float.NaN, null, 1.0)
            assertEquals(other.soundingKey, NextTrack.score(NowPlaying(now, 0.0), cand).keyAfterSync)
        }
    }
}
