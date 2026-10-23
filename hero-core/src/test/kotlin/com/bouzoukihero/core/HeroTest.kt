package com.bouzoukihero.core

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeroTest {
    private fun scale(from: Double, n: Int, gap: Double, pitches: List<Int>) =
        (0 until n).map { SourceNote(from + it * gap, gap * 0.8, pitches[it % pitches.size]) }

    @Test fun tunesParseAndBarsAddUp() {
        assertEquals(4, Tunes.all.size)
        for (t in Tunes.all) {
            val m = t.melody() // throws if a bar is the wrong length
            assertTrue(m.size > 40, "${t.id} has a real melody")
            assertEquals(t.bars().size, t.barChords().size, "${t.id}: one chord per bar")
            assertEquals(t.unitsPerBar, t.bass.length, "${t.id} bass pattern")
            assertEquals(t.unitsPerBar, t.strum.length, "${t.id} strum pattern")
            assertEquals(t.unitsPerBar, t.drums.length, "${t.id} drum pattern")
            val secs = t.bars().size * t.barSec
            assertTrue(secs in 35.0..80.0, "${t.id} lasts $secs s")
        }
        assertEquals(62, Tune.midiOf("D4"))
        assertEquals(70, Tune.midiOf("Bb4"))
        assertEquals(66, Tune.midiOf("F#4"))
    }

    @Test fun easierDifficultiesHaveFewerNotesAndLanes() {
        val notes = Tunes.all[3].sourceNotes()
        val counts = Difficulty.entries.map { d ->
            val gems = Charts.build(notes, d)
            assertTrue(gems.all { it.lane in 0 until d.lanes }, "$d lanes")
            assertTrue(gems.map { it.lane }.distinct().size == d.lanes, "$d uses every lane")
            gems.zipWithNext().forEach { (a, b) -> assertTrue(b.time - a.time >= d.minGap - 1e-9) }
            gems.size
        }
        assertTrue(counts.zipWithNext().all { (a, b) -> a < b }, "note counts $counts")
    }

    @Test fun higherNotesGoRight() {
        val gems = Charts.build(scale(0.0, 8, 0.5, listOf(62, 64, 65, 67, 69, 70, 72, 74)), Difficulty.HARD)
        assertEquals(gems.map { it.lane }, gems.map { it.lane }.sorted())
        assertEquals(0, gems.first().lane)
        assertEquals(4, gems.last().lane)
        assertEquals("D", gems.first().label)
        assertEquals("Bb", gems[5].label)
        // Up and down the tune means right and left on the buttons, even with few lanes.
        val tune = listOf(62, 64, 65, 67, 65, 64, 62, 67, 69, 70, 69, 65)
        val easy = Charts.build(scale(0.0, tune.size, 0.5, tune), Difficulty.EASY)
        easy.zipWithNext().forEachIndexed { i, (a, b) ->
            val dir = Integer.signum(tune[i + 1] - tune[i])
            val moved = Integer.signum(b.lane - a.lane)
            assertTrue(moved == dir || (moved == 0 && (b.lane == 0 || b.lane == 2)), "note ${i + 1}: ${easy.map { it.lane }}")
        }
    }

    @Test fun tremoloAndLongNotesBecomeHolds() {
        val notes = listOf(
            SourceNote(1.0, 0.1, 62), SourceNote(2.0, 1.2, 64, tremolo = true), SourceNote(4.0, 0.1, 65),
        )
        val gems = Charts.build(notes, Difficulty.MEDIUM)
        assertFalse(gems[0].isHold)
        assertTrue(gems[1].isHold && gems[1].tremolo)
        assertTrue(abs(gems[1].end - 3.2) < 1e-9)
    }

    private fun session(gems: List<Gem>, d: Difficulty = Difficulty.MEDIUM) =
        Session("Test", d, gems).also { it.viewH = 640f }

    @Test fun hittingAndMissingNotes() {
        val gems = Charts.build(scale(1.0, 4, 1.0, listOf(60, 62, 64, 65)), Difficulty.MEDIUM)
        val s = session(gems)
        s.update(0.9, 0.016f)
        s.press(gems[0].lane, 1.01)
        assertEquals(Judgement.PERFECT, gems[0].judged)
        assertEquals(1, s.combo)
        assertEquals(100, s.score)
        // Wrong button: nothing happens, the note is still there.
        s.release(gems[0].lane, 1.05)
        s.press((gems[1].lane + 1) % 4, 2.0)
        assertNull(gems[1].judged)
        s.update(2.5, 0.016f)
        assertEquals(Judgement.MISS, gems[1].judged)
        assertEquals(0, s.combo)
        assertFalse(s.leadAudible)
        s.press(gems[2].lane, 3.0 + Difficulty.MEDIUM.window * 0.9)
        assertEquals(Judgement.GOOD, gems[2].judged)
        assertTrue(s.leadAudible)
        s.update(10.0, 0.016f)
        assertEquals(Session.State.FINISHED, s.state)
        assertEquals(4, s.judgedCount)
    }

    @Test fun multiplierAndOpa() {
        val gems = Charts.build(scale(1.0, 60, 0.3, listOf(60, 62, 64, 65, 67)), Difficulty.HARD)
        val s = session(gems, Difficulty.HARD)
        for (g in gems.take(40)) {
            s.update(g.time, 0.016f)
            s.press(g.lane, g.time)
            s.release(g.lane, g.time + 0.05)
        }
        assertEquals(40, s.combo)
        assertEquals(4, s.multiplier)
        assertTrue(s.opaMeter >= 0.5f, "meter ${s.opaMeter}")
        // The OPA meter is a button in the HUD.
        s.render(AwtGfx(BufferedImage(360, 640, BufferedImage.TYPE_INT_ARGB), 1f))
        assertEquals(Session.Action.OPA, s.tap(225f, 20f))
        assertTrue(s.opaActive)
        assertEquals(8, s.multiplier)
        val before = s.score
        val g = gems[40]
        s.update(g.time, 0.016f)
        s.press(g.lane, g.time)
        assertEquals(before + 800, s.score)
    }

    @Test fun holdNotes() {
        val gems = Charts.build(listOf(SourceNote(1.0, 2.0, 62, tremolo = true), SourceNote(4.0, 0.1, 64)), Difficulty.MEDIUM)
        val s = session(gems)
        s.update(1.0, 0.016f)
        s.press(gems[0].lane, 1.0)
        var t = 1.0
        while (t < 3.1) { t += 1 / 60.0; s.update(t, 1 / 60f) }
        assertTrue(gems[0].holdDone)
        assertTrue(s.score > 100 + 50, "hold scored ${s.score}")
        // Letting go early breaks the hold.
        val s2 = session(Charts.build(listOf(SourceNote(1.0, 2.0, 62)), Difficulty.MEDIUM))
        s2.update(1.0, 0.016f)
        s2.press(s2.gems[0].lane, 1.0)
        s2.update(1.5, 0.016f)
        s2.release(s2.gems[0].lane, 1.5)
        assertTrue(s2.gems[0].holdBroken)
    }

    @Test fun recordingAChart() {
        val s = Session("Rec", Difficulty.EXPERT, emptyList(), record = true, hasVideo = true)
        s.viewH = 700f
        s.topInset = 202f
        assertEquals(2, s.laneAt(180f, 650f))
        assertEquals(0, s.laneAt(5f, 650f))
        assertEquals(-1, s.laneAt(180f, 300f))
        s.update(1.0, 0.016f)
        s.press(0, 1.0); s.release(0, 1.1)
        s.press(4, 2.0); s.release(4, 3.0)
        s.press(2, 3.5)
        s.finish()
        val notes = s.recordedNotes()
        assertEquals(listOf(0, 4, 2), notes.map { it.pitch })
        assertEquals(1.0, notes[1].duration, 1e-9)
        val song = CustomSong("x", "Rec", "dQw4w9WgXcQ", notes, CustomSong.SOURCE_RECORDED)
        val easy = Charts.build(song.notes, Difficulty.EASY, labels = song.labelled)
        assertEquals(listOf(0, 2, 1), easy.map { it.lane })
        assertEquals("", easy[0].label)
    }

    @Test fun jsonAndSongsRoundTrip() {
        val song = CustomSong(
            "abc", "Ta \"Matia\" σου", "abcdefghijk",
            listOf(SourceNote(1.25, 0.5, 62, true), SourceNote(2.0, 0.1, 64)), CustomSong.SOURCE_TABS, 118.5, 12.0, 90.0,
        )
        assertEquals(song, CustomSong.fromJson(song.toJson()))
        val parsed = Json.parse("""{"a":[1,2.5,-3e2,true,null,"xA\n"],"b":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0, true, null, "xA\n"), parsed["a"])
    }

    @Test fun bouzoukiTabsResult() {
        // The shape of GET /api/jobs/{id} from the Bouzouki Tabs web app.
        val reply = """
            {"id":"f00","status":"done","stage":"Done","progress":1.0,"video_id":"abcdefghijk","title":"Song",
             "result":{"title":"Song","tempo":121.3,"notes":[
               {"start":12.5,"end":12.75,"midi":62,"name":"D4","course":"D","fret":0,"tremolo":false},
               {"start":13.0,"end":14.2,"midi":65,"name":"F4","course":"D","fret":3,"tremolo":true}]}}
        """.trimIndent()
        val job = BouzoukiTabs.parseJob(reply)
        assertEquals("done", job.status)
        val notes = BouzoukiTabs.notes(assertNotNull(job.result))
        assertEquals(2, notes.size)
        assertEquals(SourceNote(13.0, 1.2, 65, true).pitch, notes[1].pitch)
        assertTrue(notes[1].tremolo && abs(notes[1].duration - 1.2) < 1e-9)
        assertEquals(121.3, BouzoukiTabs.tempo(job.result!!))
        val running = BouzoukiTabs.parseJob("""{"status":"running","stage":"Removing vocals","progress":0.4}""")
        assertEquals(0.4, running.progress)
        val req = Json.parse(BouzoukiTabs.request("https://youtu.be/abcdefghijk", 30.0, null)) as Map<*, *>
        assertEquals(30.0, req["start"])
        assertFalse("end" in req)
    }

    @Test fun youtubeLinks() {
        val id = "dQw4w9WgXcQ"
        for (link in listOf(
            "https://www.youtube.com/watch?v=$id", "https://youtube.com/watch?feature=share&v=$id&t=42",
            "https://youtu.be/$id?si=abc", "https://m.youtube.com/watch?v=$id", "https://www.youtube.com/shorts/$id",
            "https://music.youtube.com/watch?v=$id&list=x", "https://www.youtube.com/embed/$id", id,
        )) assertEquals(id, YouTube.videoId(link), link)
        assertNull(YouTube.videoId("https://vimeo.com/123"))
        assertEquals(90.0, parseClock("1:30"))
        assertEquals(3723.0, parseClock("1:02:03"))
        assertNull(parseClock(" "))
    }

    @Test fun synthMakesSound() {
        val r = Synth.render(Tunes.all[0])
        assertEquals(r.lead.size, r.backing.size)
        val last = r.notes.maxOf { it.time + it.duration }
        assertTrue(r.seconds > last, "audio (${r.seconds}s) outlasts the melody ($last s)")
        fun rms(a: FloatArray, from: Double, to: Double): Double {
            val i0 = (from * Synth.RATE).toInt()
            val i1 = (to * Synth.RATE).toInt()
            return Math.sqrt((i0 until i1).sumOf { a[it].toDouble() * a[it] } / (i1 - i0))
        }
        val first = r.notes.first().time
        assertTrue(rms(r.lead, 0.0, first - 0.05) < 1e-6, "no bouzouki in the intro")
        assertTrue(rms(r.lead, first, first + 2) > 0.02)
        assertTrue(rms(r.backing, Tune.LEAD_IN, first) > 0.01)
        assertTrue((r.lead.indices).all { abs(r.lead[it] + r.backing[it]) <= 1f })
        File("build/music").mkdirs()
        for (t in Tunes.all) {
            val x = Synth.render(t)
            writeWav(File("build/music/${t.id}.wav"), FloatArray(x.lead.size) { x.lead[it] + x.backing[it] })
        }
    }

    private fun writeWav(f: File, s: FloatArray) {
        val out = java.io.DataOutputStream(f.outputStream().buffered())
        fun le32(v: Int) = out.writeInt(Integer.reverseBytes(v))
        fun le16(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
        out.writeBytes("RIFF"); le32(36 + s.size * 2); out.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1)
        le32(Synth.RATE); le32(Synth.RATE * 2); le16(2); le16(16); out.writeBytes("data"); le32(s.size * 2)
        for (v in s) le16((v.coerceIn(-1f, 1f) * 32000).toInt())
        out.close()
    }

    /** Renders a few frames to build/screens for eyeballing. */
    @Test fun screenshots() {
        val dir = File("build/screens").apply { mkdirs() }
        fun shot(name: String, s: Session, h: Int = 740) {
            s.viewH = h.toFloat()
            val scale = 2f
            val img = BufferedImage((VIEW_W * scale).toInt(), (h * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
            s.render(AwtGfx(img, scale))
            ImageIO.write(img, "png", File(dir, "$name.png"))
        }
        val tune = Tunes.all[1]
        for (d in listOf(Difficulty.EASY, Difficulty.EXPERT)) {
            val gems = Charts.build(tune.sourceNotes(), d)
            val s = Session(tune.title, d, gems, tempo = tune.bpm.toDouble())
            val t0 = gems[6].time
            var t = t0 - 2.0
            while (t < t0) {
                s.update(t, 1 / 60f)
                for (g in gems) if (g.judged == null && abs(g.time - t) < 0.01 && g.lane != 1) s.press(g.lane, g.time)
                t += 1 / 60.0
            }
            s.press(gems[7].lane, t)
            s.update(t + 0.05, 0.05f)
            shot("play_${d.name.lowercase()}", s)
        }
        // A YouTube song with the video on top, mid-tremolo.
        val notes = Tunes.all[0].sourceNotes()
        val gems = Charts.build(notes, Difficulty.MEDIUM)
        val s = Session("Some YouTube Song", Difficulty.MEDIUM, gems, hasVideo = true, tempo = 100.0)
        s.topInset = 202.5f
        val hold = gems.first { it.isHold && it.tremolo }
        var t = hold.time - 3
        while (t < hold.time + 0.4) {
            s.update(t, 1 / 60f)
            for (g in gems) if (g.judged == null && abs(g.time - t) < 0.01) s.press(g.lane, g.time)
            t += 1 / 60.0
        }
        shot("youtube_medium", s)

        val done = Session(tune.title, Difficulty.HARD, Charts.build(tune.sourceNotes(), Difficulty.HARD), bestScore = 12000)
        for (g in done.gems) {
            done.update(g.time, 0.016f)
            if (g.lane != 2) done.press(g.lane, g.time + 0.02)
            done.release(g.lane, g.time + 0.1)
        }
        done.update(done.gems.last().end + 3, 0.016f)
        shot("results", done)

        val rec = Session("Recording", Difficulty.EXPERT, emptyList(), record = true, hasVideo = true)
        rec.topInset = 202.5f
        for (i in 0 until 12) { rec.update(i * 0.12, 0.016f); rec.press(i % 5, i * 0.12); rec.release(i % 5, i * 0.12 + 0.05) }
        rec.update(1.5, 0.016f)
        shot("record", rec)

        val paused = Session(tune.title, Difficulty.MEDIUM, Charts.build(tune.sourceNotes(), Difficulty.MEDIUM))
        paused.update(5.0, 0.016f)
        paused.state = Session.State.PAUSED
        shot("paused", paused)
    }
}
