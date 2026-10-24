package com.bouzoukihero.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class TranscriberTest {
    private class Score(val recall: Double, val precision: Double, val pitchOk: Double) {
        override fun toString() = "recall %.2f precision %.2f pitch %.2f".format(recall, precision, pitchOk)
    }

    /** Matches found notes to the real ones: onset within 70 ms; pitch right up to an octave. */
    private fun score(truth: List<SourceNote>, found: List<SourceNote>): Score {
        // Tremolo notes count once, at their start.
        val used = BooleanArray(found.size)
        var hit = 0
        var pitched = 0
        for (t in truth) {
            val i = found.indices.firstOrNull { !used[it] && abs(found[it].time - t.time) < 0.07 } ?: continue
            used[i] = true
            hit++
            if ((found[i].pitch - t.pitch) % 12 == 0) pitched++
        }
        return Score(hit.toDouble() / truth.size, hit.toDouble() / found.size.coerceAtLeast(1), pitched.toDouble() / hit.coerceAtLeast(1))
    }

    /** The expected chart: tremolo notes are one note. */
    private fun truthOf(t: Tune) = t.sourceNotes()

    /** A singer: smooth onsets, vibrato, a different melody in the same range. */
    private fun singer(out: FloatArray, t: Tune, level: Float) {
        val notes = t.sourceNotes()
        var i = 0
        while (i < notes.size) {
            val n = notes[i]
            val len = min(1.2, notes.drop(i).take(3).sumOf { it.duration })
            val f = Tune.freq(n.pitch - 5)
            val s0 = (n.time * Synth.RATE).toInt()
            val cnt = min(out.size - s0, (len * Synth.RATE).toInt())
            var ph = 0.0
            for (k in 0 until cnt) {
                val tt = k.toDouble() / Synth.RATE
                ph += f * (1 + 0.012 * sin(2 * PI * 5.5 * tt)) / Synth.RATE
                val env = min(1.0, tt / 0.12) * min(1.0, (cnt - k).toDouble() / 2000)
                out[s0 + k] += (level * env * (sin(2 * PI * ph) + 0.4 * sin(4 * PI * ph) + 0.2 * sin(6 * PI * ph))).toFloat()
            }
            i += 3
        }
    }

    @Test fun findsTheBouzoukiInAFullMix() {
        val report = StringBuilder()
        for (tune in Tunes.all) {
            val r = Synth.render(tune)
            val clean = FloatArray(r.lead.size) { r.lead[it] }
            val mix = FloatArray(r.lead.size) { r.lead[it] + r.backing[it] }
            singer(mix, tune, 0.12f)
            val truth = truthOf(tune)
            val c = score(truth, Transcriber.transcribe(clean))
            val m = score(truth, Transcriber.transcribe(mix))
            report.append("${tune.id}: clean $c | band+singer $m\n")
            assertTrue(c.recall > 0.85 && c.precision > 0.85 && c.pitchOk > 0.85, "${tune.id} clean: $c")
            assertTrue(m.recall > 0.7 && m.precision > 0.7 && m.pitchOk > 0.7, "${tune.id} mix: $m")
        }
        println(report)
    }

    @Test fun liveMatchesWholeSong() {
        val tune = Tunes.all[1]
        val r = Synth.render(tune)
        val mix = FloatArray(r.lead.size) { r.lead[it] + r.backing[it] }
        val live = LiveTranscriber(latency = 0.1)
        val chunk = 1024
        var i = 0
        while (i < mix.size) {
            val n = min(chunk, mix.size - i)
            // The clock says where the song was when this chunk was read (plus capture latency).
            live.feed(mix.copyOfRange(i, i + n), (i + n).toDouble() / Synth.RATE + 0.1)
            i += n
        }
        val s = score(truthOf(tune), live.finish())
        println("live: $s")
        assertTrue(s.recall > 0.8 && s.precision > 0.8, "live: $s")
    }

    @Test fun silenceAndDrumsGiveNoNotes() {
        assertTrue(Transcriber.transcribe(FloatArray(Synth.RATE * 3)).isEmpty())
        val drums = FloatArray(Synth.RATE * 4)
        var seed = 5
        for (b in 0 until 16) {
            val s0 = b * Synth.RATE / 4
            for (k in 0 until 1500) {
                seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
                drums[s0 + k] += ((seed and 0xFFFF) / 32768f - 1f) * 0.4f * (1 - k / 1500f)
            }
        }
        val found = Transcriber.transcribe(drums)
        assertTrue(found.size <= 3, "drums gave ${found.size} notes")
    }
}
