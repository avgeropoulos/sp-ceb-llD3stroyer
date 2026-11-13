package com.mixdeck.engine

import com.mixdeck.engine.SynthCorpus.Spec
import com.mixdeck.engine.SynthCorpus.Style
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackFeaturesTest {
    private class Done(val spec: Spec, val label: String, val pcm: ShortArrayPcm?, val a: FeatureExtractor.Analysis)

    companion object {
        private fun run(specs: List<Spec>, keepPcm: Boolean = false): List<Done> =
            specs.parallelStream().map { spec ->
                val song = SynthCorpus.render(spec)
                Done(spec, song.label, if (keepPcm) song.pcm else null, FeatureExtractor.analyze(song.pcm, spec.bpm))
            }.toList()

        /** 48 songs: every key twice, all styles, ±40 cent tuning, drum-heavy mixes, DJ intros. */
        private val keySongs by lazy { run(SynthCorpus.keyCorpus(2, seconds = 20.0, seed = 42)) }
        private val drumSongs by lazy { run(SynthCorpus.drumsOnly(8, seconds = 20.0, seed = 43)) }
        private val atonalSongs by lazy { run(SynthCorpus.atonal(6, seconds = 20.0, seed = 44)) }
        private val modulatingSongs by lazy { run(SynthCorpus.modulating(6, seconds = 32.0, seed = 45)) }

        fun mirex(est: MusicalKey?, truth: MusicalKey): Double {
            if (est == null) return 0.0
            if (est == truth) return 1.0
            val d = Math.floorMod(est.tonic - truth.tonic, 12)
            if (est.minor == truth.minor && (d == 7 || d == 5)) return 0.5
            if (est == truth.relative) return 0.3
            if (est.tonic == truth.tonic) return 0.2
            return 0.0
        }

        fun scaled(pcm: ShortArrayPcm, gain: Float): ShortArrayPcm {
            val l = FloatArray(pcm.frames) { pcm.left(it) * gain }; val r = FloatArray(pcm.frames) { pcm.right(it) * gain }
            return ShortArrayPcm.fromFloats(l, r, pcm.sampleRate)
        }

        fun resampled(pcm: ShortArrayPcm, sr: Int): ShortArrayPcm {
            val n = (pcm.frames.toLong() * sr / pcm.sampleRate).toInt()
            val step = pcm.sampleRate.toDouble() / sr
            val l = FloatArray(n); val r = FloatArray(n)
            for (i in 0 until n) {
                val x = i * step; val j = x.toInt(); val f = (x - j).toFloat(); val k = minOf(j + 1, pcm.frames - 1)
                l[i] = pcm.left(j) * (1 - f) + pcm.left(k) * f; r[i] = pcm.right(j) * (1 - f) + pcm.right(k) * f
            }
            return ShortArrayPcm.fromFloats(l, r, sr)
        }
    }

    @Test fun detectsKeysInAll24Keys() {
        val scores = keySongs.map { mirex(it.a.features.key, it.spec.key) }
        val mirex = scores.average()
        val exact = scores.count { it == 1.0 }.toDouble() / scores.size
        // Exact, a fifth away or the relative key: all mix harmonically (same or adjacent Camelot number).
        val compatible = scores.count { it >= 0.3 }.toDouble() / scores.size
        println("key: MIREX %.3f exact %.0f%% Camelot-compatible %.0f%% (n=%d)".format(mirex, 100 * exact, 100 * compatible, scores.size))
        for (d in keySongs) if (mirex(d.a.features.key, d.spec.key) < 0.3) println("  miss: ${d.label} -> ${d.a.features}")
        assertTrue(mirex >= 0.75, "MIREX score $mirex")
        assertTrue(exact >= 0.65, "exact $exact")
        assertTrue(compatible >= 0.85, "compatible $compatible")
    }

    @Test fun confidenceTracksCorrectness() {
        val conf = keySongs.map { it.a.features.keyConfidence }
        val right = keySongs.filter { it.a.features.key == it.spec.key }.map { it.a.features.keyConfidence.toDouble() }
        val wrong = keySongs.filter { it.a.features.key != it.spec.key }.map { it.a.features.keyConfidence.toDouble() }
        println("confidence: right %.2f (n=%d) wrong %.2f (n=%d)".format(right.average(), right.size, wrong.average(), wrong.size))
        assertTrue(conf.all { it in 0f..1f })
        assertTrue(right.average() > wrong.average() + 0.15, "confident when right")
        val sure = keySongs.filter { it.a.features.keyConfidence >= 0.6f }
        println("conf >= 0.6: ${sure.count { it.a.features.key == it.spec.key }} of ${sure.size} exactly right")
        assertTrue(sure.size >= 5 && sure.count { it.a.features.key == it.spec.key } >= 0.9 * sure.size, "conf >= 0.6 should be right")
    }

    @Test fun nearTiedKeysGiveLowConfidence() {
        val all = keySongs + modulatingSongs + atonalSongs
        val tied = all.filter { it.a.features.key != null && it.a.margin < 0.003 }
        println("near ties: ${tied.size}")
        for (d in tied) assertTrue(d.a.features.keyConfidence < 0.3f, "${d.label}: conf ${d.a.features.keyConfidence} with margin ${d.a.margin}")
    }

    @Test fun drumsOnlyHasNoKey() {
        for (d in drumSongs) {
            println("drums: ${d.label} -> ${d.a.features}")
            assertTrue(d.a.features.key == null || d.a.features.keyConfidence < 0.3f, d.label)
        }
        assertTrue(drumSongs.count { it.a.features.key == null } >= drumSongs.size - 1, "drum tracks should have no key")
    }

    @Test fun atonalMusicHasLowConfidence() {
        val c = atonalSongs.map { it.a.features.keyConfidence }.sorted()
        println("atonal confidence: $c")
        assertTrue(c[c.size / 2] < 0.3f)
        assertTrue(c.count { it < 0.3f } >= c.size - 1)
    }

    @Test fun keyChangeLowersConfidence() {
        val mod = modulatingSongs.map { it.a.features.keyConfidence.toDouble() }
        val single = keySongs.map { it.a.features.keyConfidence.toDouble() }
        for (d in modulatingSongs) println("modulating: ${d.label} -> ${d.a.features}")
        println("modulating mean confidence %.2f vs single-key mean %.2f".format(mod.average(), single.average()))
        assertTrue(mod.average() < single.average() - 0.1)
        // A change to a key that does not mix with the first one (two or more Camelot steps away) must not look confident.
        for (d in modulatingSongs) {
            val a = d.spec.key; val b = d.spec.modulateTo!!
            val steps = Math.floorMod(a.camelotNumber - b.camelotNumber, 12).let { minOf(it, 12 - it) }
            if (steps >= 2) assertTrue(d.a.features.keyConfidence < 0.35f, d.label)
        }
        // When it does sound sure, the key it reports is one of the two keys or a neighbour of one.
        for (d in modulatingSongs) if (d.a.features.keyConfidence >= 0.3f)
            assertTrue(maxOf(mirex(d.a.features.key, d.spec.key), mirex(d.a.features.key, d.spec.modulateTo!!)) >= 0.3, d.label)
    }

    @Test fun estimatesTuning() {
        val errs = keySongs.filter { it.a.features.key != null }.map {
            var e = it.a.features.tuningCents - it.spec.tuningCents
            e -= 100 * Math.round(e / 100.0)
            abs(e)
        }
        println("tuning error: mean %.1f max %.1f cents".format(errs.average(), errs.max()))
        assertTrue(errs.average() < 3.0)
        assertTrue(errs.count { it > 8 } <= 1)
    }

    @Test fun energyFollowsIntensityNotLevel() {
        val specs = listOf(
            Spec(MusicalKey(0, false), Style.AMBIENT, 70.0, 24.0, seed = 1),
            Spec(MusicalKey(2, false), Style.ACOUSTIC, 85.0, 24.0, seed = 3),
            Spec(MusicalKey(5, true), Style.HIPHOP, 90.0, 24.0, seed = 5),
            Spec(MusicalKey(9, true), Style.EDM, 128.0, 24.0, seed = 7, drumGainDb = 2.0),
        )
        val e = run(specs, keepPcm = true)
        val v = e.map { it.a.features.energy }
        println("energy: ambient %.1f, acoustic %.1f, hip-hop %.1f, EDM drop %.1f".format(v[0], v[1], v[2], v[3]))
        assertTrue(v[0] < 4f, "ambient pad")
        assertTrue(v[1] in 2.5f..5.5f, "sparse acoustic")
        assertTrue(v[2] in 4.5f..7.5f, "hip-hop groove")
        assertTrue(v[3] >= 7.8f, "EDM drop")
        assertTrue(v[0] < v[1] && v[1] < v[2] && v[2] < v[3])
        // 18 dB quieter: same energy, same timbre, same key.
        for (d in e) {
            val quiet = FeatureExtractor.extract(scaled(d.pcm!!, 0.125f), d.spec.bpm)
            assertEquals(d.a.features.energy, quiet.energy, 0.3f, d.label)
            assertTrue(FeatureExtractor.timbreSimilarity(d.a.features.timbre, quiet.timbre) > 0.95f, d.label)
            assertEquals(d.a.features.key, quiet.key, d.label)
        }
    }

    @Test fun timbreRanksSameArrangementAboveOtherStyles() {
        val rnd = Random(21)
        val styles = listOf(Style.EDM, Style.HIPHOP, Style.ROCK, Style.AMBIENT, Style.RNB, Style.HOUSE)
        val specs = styles.flatMapIndexed { si, st ->
            List(3) {
                val range = SynthCorpus.bpmRange(st)
                Spec(MusicalKey(rnd.nextInt(12), rnd.nextBoolean()), st, (range.first + rnd.nextInt(range.last - range.first + 1)).toDouble(), 18.0,
                    seed = rnd.nextLong(), arrangement = 100L + si, masterGainDb = -rnd.nextDouble() * 10)
            }
        }
        val d = run(specs)
        var nn = 0; var pairs = 0; var ordered = 0.0
        val same = ArrayList<Double>(); val diff = ArrayList<Double>()
        for (i in d.indices) {
            val sims = d.indices.filter { it != i }.map { j -> j to FeatureExtractor.timbreSimilarity(d[i].a.features.timbre, d[j].a.features.timbre).toDouble() }
            if (d[sims.maxBy { it.second }.first].spec.style == d[i].spec.style) nn++
            val s = sims.filter { d[it.first].spec.style == d[i].spec.style }.map { it.second }
            val o = sims.filter { d[it.first].spec.style != d[i].spec.style }.map { it.second }
            same += s; diff += o
            for (a in s) for (b in o) { pairs++; ordered += if (a > b) 1.0 else 0.0 }
        }
        println("timbre: nearest neighbour same style %d/%d, mean same %.2f, different %.2f, AUC %.3f".format(nn, d.size, same.average(), diff.average(), ordered / pairs))
        assertTrue(nn >= d.size - 2)
        assertTrue(ordered / pairs > 0.9)
        assertTrue(same.average() > diff.average() + 0.25)
    }

    @Test fun timbreSimilarityBasics() {
        val a = keySongs[0].a.features.timbre; val b = keySongs[1].a.features.timbre
        assertEquals(FeatureExtractor.TIMBRE_SIZE, a.size)
        assertEquals(1f, FeatureExtractor.timbreSimilarity(a, a), 1e-5f)
        assertEquals(FeatureExtractor.timbreSimilarity(a, b), FeatureExtractor.timbreSimilarity(b, a), 1e-6f)
        assertTrue(FeatureExtractor.timbreSimilarity(a, b) in 0f..1f)
        assertEquals(0f, FeatureExtractor.timbreSimilarity(a, FloatArray(0)))
    }

    @Test fun silenceAndTinyInputs() {
        val silent = FeatureExtractor.extract(ShortArrayPcm(ShortArray(44100 * 2 * 5), 44100), 0.0)
        assertNull(silent.key); assertEquals(0f, silent.keyConfidence); assertTrue(silent.energy.isNaN()); assertEquals(0, silent.timbre.size)
        val rnd = Random(1)
        val tiny = FeatureExtractor.extract(ShortArrayPcm(ShortArray(2 * 3000) { (rnd.nextInt(2000) - 1000).toShort() }, 44100), 120.0)
        assertNull(tiny.key); assertTrue(tiny.energy.isNaN())
        val empty = FeatureExtractor.extract(ShortArrayPcm(ShortArray(0), 44100), 0.0)
        assertNull(empty.key)
    }

    @Test fun otherSampleRatesGiveTheSameKey() {
        val d = run(listOf(Spec(MusicalKey(4, true), Style.POP, 110.0, 20.0, tuningCents = 20.0, seed = 9)), keepPcm = true)[0]
        val base = d.a.features
        assertEquals(d.spec.key, base.key)
        for (sr in intArrayOf(48000, 22050, 32000)) {
            val f = FeatureExtractor.extract(resampled(d.pcm!!, sr), d.spec.bpm)
            println("$sr Hz: $f")
            assertEquals(base.key, f.key, "$sr Hz")
            assertEquals(base.tuningCents, f.tuningCents, 3f)
            assertEquals(base.energy, f.energy, 1.0f)
        }
    }

    @Test fun reportsProgressAndIsFastOnAFiveMinuteTrack() {
        val loop = SynthCorpus.render(Spec(MusicalKey(9, true), Style.EDM, 128.0, 20.0, seed = 5)).pcm
        val n = 300 * 44100
        val data = ShortArray(n * 2)
        for (i in 0 until n) { val j = i % loop.frames; data[2 * i] = ShortArrayPcm.toShort(loop.left(j)); data[2 * i + 1] = ShortArrayPcm.toShort(loop.right(j)) }
        val pcm = ShortArrayPcm(data, 44100)
        FeatureExtractor.extract(pcm, 128.0) // warm up the JIT
        val seen = ArrayList<Float>()
        val t0 = System.nanoTime()
        val f = FeatureExtractor.extract(pcm, 128.0) { seen += it }
        val ms = (System.nanoTime() - t0) / 1e6
        println("5-minute track: %.0f ms, $f".format(ms))
        assertEquals(MusicalKey(9, true), f.key)
        assertTrue(seen.isNotEmpty() && seen.zipWithNext().all { (a, b) -> b >= a } && seen.last() == 1f)
        assertTrue(ms < 4000, "took $ms ms (budget 1500 ms on a desktop)")
    }
}
