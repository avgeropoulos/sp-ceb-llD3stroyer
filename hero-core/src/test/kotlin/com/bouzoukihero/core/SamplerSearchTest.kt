package com.bouzoukihero.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SamplerSearchTest {
    /** A stand-in "recording": half a second of room noise, then a plucked note. */
    private fun recording(freq: Double, rate: Int): FloatArray {
        val out = FloatArray(rate * 3)
        val note = FloatArray(Synth.RATE * 2)
        Synth.pluck(note, 0.0, freq, 2.0, 0.5f, bright = 1f)
        val up = Samples.resample(note, Synth.RATE, rate)
        var seed = 99
        for (i in out.indices) {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            out[i] = ((seed and 0xFFFF) / 32768f - 1f) * 0.002f
            val j = i - rate / 2
            if (j in up.indices) out[i] += up[j]
        }
        return out
    }

    private fun midiOf(freq: Double) = 69 + 12 * ln(freq / 440.0) / ln(2.0)

    @Test fun findsThePitchOfARecordedNote() {
        // Open D course, tuned 20 cents flat, recorded at the phone's usual 44.1 kHz.
        val f = 293.66 * Math.pow(2.0, -0.2 / 12)
        val s = Samples.prepare(recording(f, 44100), 44100)
        assertEquals(62 - 0.2, s.root, 0.08)
        assertEquals("D4", s.name)
        // The pluck is at the start, not half a second in.
        assertTrue(s.pcm.take(400).maxOf { abs(it) } > 0.3f)
        for (freq in listOf(130.81, 174.61, 220.0, 587.33, 880.0)) {
            val p = assertNotNullD(Samples.pitch(Samples.prepare(recording(freq, 22050), 22050).pcm, Synth.RATE))
            assertEquals(midiOf(freq), midiOf(p), 0.1, "$freq Hz")
        }
        assertFailsWith<Samples.NoNote> { Samples.prepare(FloatArray(44100), 44100) }
    }

    private fun assertNotNullD(d: Double?): Double { assertTrue(d != null); return d!! }

    @Test fun samplerPlaysNotesAtTheRightPitch() {
        val sampler = Sampler(listOf(
            Samples.prepare(recording(146.83, 22050), 22050), // D3
            Samples.prepare(recording(440.0, 22050), 22050), // A4
        ))
        assertEquals(50, Math.round(sampler.nearest(52).root).toInt())
        for (midi in listOf(52, 62, 67, 74)) {
            val out = FloatArray(Synth.RATE)
            sampler.note(out, 0.0, midi, 1.0, 0.36f)
            val p = assertNotNullD(Samples.pitch(out, Synth.RATE))
            assertEquals(midi.toDouble(), midiOf(p), 0.12, "note $midi")
        }
        val song = Synth.render(Tunes.all[0], sampler)
        val first = song.notes.first().time
        val i0 = (first * Synth.RATE).toInt()
        assertTrue((i0 until i0 + Synth.RATE).maxOf { abs(song.lead[it]) } > 0.05f)
        assertTrue(song.lead.indices.all { abs(song.lead[it] + song.backing[it]) <= 1f })
    }

    @Test fun wavRoundTrip() {
        val pcm = FloatArray(1000) { (it % 50) / 50f - 0.5f }
        val (back, rate) = Samples.readWav(Samples.writeWav(pcm))
        assertEquals(Synth.RATE, rate)
        assertEquals(pcm.size, back.size)
        assertTrue(pcm.indices.all { abs(pcm[it] - back[it]) < 0.002f })
    }

    @Test fun parsesYouTubeResultsPage() {
        // The shape of the data YouTube embeds in its search results page.
        val data = """
          {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
            {"itemSectionRenderer":{"contents":[
              {"adSlotRenderer":{}},
              {"videoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"Bouzouki solo \"live\" {1975}"}]},
                "ownerText":{"runs":[{"text":"Some Channel"}]},"lengthText":{"simpleText":"3:42"}}},
              {"videoRenderer":{"videoId":"bcdefghijkl","title":{"simpleText":"Taximi"},
                "longBylineText":{"runs":[{"text":"Other"}]}}},
              {"lockupViewModel":{"contentId":"cdefghijklm","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                "metadata":{"lockupMetadataViewModel":{"title":{"content":"Zeibekiko"}}}}},
              {"lockupViewModel":{"contentId":"PLxyz","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST"}}
            ]}}]}}}}}
        """.trimIndent()
        val html = "<html><script>var foo = {};</script><script nonce=\"x\">var ytInitialData = $data;</script></html>"
        val v = YouTubeSearch.parsePage(html)
        assertEquals(listOf("abcdefghijk", "bcdefghijkl", "cdefghijklm"), v.map { it.id })
        assertEquals("Bouzouki solo \"live\" {1975}", v[0].title)
        assertEquals("Some Channel", v[0].channel)
        assertEquals("3:42", v[0].duration)
        assertEquals("Other", v[1].channel)
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", v[0].thumbnail)
        assertEquals(emptyList(), YouTubeSearch.parsePage("<html>consent page</html>").map { it.id })
    }

    @Test fun parsesYouTubeApiReply() {
        val reply = """{"items":[{"id":{"kind":"youtube#video","videoId":"abcdefghijk"},
            "snippet":{"title":"Ta &quot;Matia&quot;","channelTitle":"Ch &amp; Co"}}]}"""
        val v = YouTubeSearch.parseApi(reply)
        assertEquals("Ta \"Matia\"", v[0].title)
        assertEquals("Ch & Co", v[0].channel)
        assertFailsWith<IllegalStateException> { YouTubeSearch.parseApi("""{"error":{"message":"API key not valid"}}""") }
        assertTrue(YouTubeSearch.apiUrl("ζεϊμπέκικο", "k").contains("q=%CE%B6"))
    }
}
