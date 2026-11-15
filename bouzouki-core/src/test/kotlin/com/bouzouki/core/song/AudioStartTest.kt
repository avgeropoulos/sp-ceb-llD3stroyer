package com.bouzouki.core.song

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioStartTest {
    private fun box(type: String, vararg parts: ByteArray, large: Boolean = false): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            if (large) { writeInt(1); writeBytes(type); writeLong(body.size + 16L) } else { writeInt(body.size + 8); writeBytes(type) }
            write(body)
        }
        return out.toByteArray()
    }

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).write() }.toByteArray()

    private fun mdhd(timescale: Int, v1: Boolean = false) = box("mdhd", bytes {
        if (v1) { writeInt(1 shl 24); writeLong(0); writeLong(0); writeInt(timescale); writeLong(0) }
        else { writeInt(0); writeInt(0); writeInt(0); writeInt(timescale); writeInt(0) }
        writeInt(0)
    })

    private fun hdlr(kind: String) = box("hdlr", bytes { writeInt(0); writeInt(0); writeBytes(kind); write(ByteArray(13)) })

    /** An edit list of [entries]: (segment duration, media time, rate as 16.16). */
    private fun elst(vararg entries: Triple<Long, Long, Int>, v1: Boolean = false) = box("elst", bytes {
        writeInt(if (v1) 1 shl 24 else 0)
        writeInt(entries.size)
        for ((dur, time, rate) in entries) {
            if (v1) { writeLong(dur); writeLong(time) } else { writeInt(dur.toInt()); writeInt(time.toInt()) }
            writeInt(rate)
        }
    })

    private fun trak(kind: String, timescale: Int, elst: ByteArray? = null, v1: Boolean = false) = box(
        "trak", box("tkhd", ByteArray(84)), *(if (elst != null) arrayOf(box("edts", elst)) else emptyArray()),
        box("mdia", mdhd(timescale, v1), hdlr(kind), box("minf", ByteArray(40))),
    )

    private fun file(vararg boxes: ByteArray): File = File.createTempFile("start", ".m4a").apply {
        deleteOnExit()
        writeBytes(boxes.fold(ByteArray(0)) { a, b -> a + b })
    }

    private val ftyp = box("ftyp", "dash".toByteArray(), ByteArray(12))
    private val mvhd = box("mvhd", ByteArray(100))
    private val normal = 0x10000

    @Test
    fun aYouTubeStyleFileGivesItsEdit() {
        // The init segment first, as YouTube's audio has it: an edit of 1600 samples at 44.1 kHz, then fragments.
        val f = file(ftyp, box("moov", mvhd, trak("soun", 44100, elst(Triple(0L, 1600L, normal)))), box("moof", ByteArray(64)), box("mdat", ByteArray(5000)))
        assertEquals(AudioStart.Edit(1600, 44100), AudioStart.mp4Edit(f))
        assertEquals(1600, AudioStart.mp4Edit(f)!!.samples(44100))
    }

    @Test
    fun theEditIsFoundBehindTheAudioAndInTheLongForms() {
        // An ordinary m4a keeps its index after the audio; version 1 boxes and a 64-bit box size are read too.
        val moov = box("moov", mvhd, trak("vide", 90000, elst(Triple(0L, 3003L, normal))), trak("soun", 48000, elst(Triple(0L, 2112L, normal), v1 = true), v1 = true))
        val f = file(ftyp, box("mdat", ByteArray(70_000), large = true), box("free", ByteArray(8)), moov)
        assertEquals(AudioStart.Edit(2112, 48000), AudioStart.mp4Edit(f))
        // The same edit at the rate the decoder gives (an HE-AAC track counts at half its output rate).
        assertEquals(1056, AudioStart.Edit(528, 22050).samples(44100))
    }

    @Test
    fun aTrackWithoutAnEditStartsAtZero() {
        val f = file(ftyp, box("moov", mvhd, trak("soun", 44100)))
        assertEquals(AudioStart.Edit(0, 44100), AudioStart.mp4Edit(f))
        assertEquals(0, AudioStart.mp4Edit(f)!!.samples(44100))
    }

    @Test
    fun whatIsNotAPlainStartIsNotGuessed() {
        // Two entries, an empty edit (a delayed track), half speed, no audio track, not an MP4, a cut-off file.
        assertNull(AudioStart.mp4Edit(file(ftyp, box("moov", mvhd, trak("soun", 44100, elst(Triple(10L, 0L, normal), Triple(10L, 5000L, normal)))))))
        assertNull(AudioStart.mp4Edit(file(ftyp, box("moov", mvhd, trak("soun", 44100, elst(Triple(441L, -1L, normal)))))))
        assertNull(AudioStart.mp4Edit(file(ftyp, box("moov", mvhd, trak("soun", 44100, elst(Triple(0L, 1600L, normal / 2)))))))
        assertNull(AudioStart.mp4Edit(file(ftyp, box("moov", mvhd, trak("vide", 90000)))))
        assertNull(AudioStart.mp4Edit(file("OggS not an mp4 at all, just some bytes that are long enough".toByteArray())))
        val whole = ftyp + box("moov", mvhd, trak("soun", 44100, elst(Triple(0L, 1600L, normal))))
        assertNull(AudioStart.mp4Edit(file(whole.copyOf(whole.size - 30))))
        assertNull(AudioStart.mp4Edit(File("/nonexistent/song.m4a")))
        // A lead-in too long to be an encoder's is not believed.
        assertEquals(0, AudioStart.Edit(44100L * 30, 44100).samples(44100))
    }

    @Test
    fun theLeadInGoesUnlessTheDecoderAlreadyDroppedIt() {
        val full = IntArray(16) { 1024 }
        // Full pieces from time zero: the lead-in is in the audio, so all of it goes.
        assertEquals(1600, AudioStart.drop(1600, 44100, 0, full).samples)
        // The same whatever the time stamps say when they are of no use (none, or shifted before zero by the edit).
        assertEquals(1600, AudioStart.drop(1600, 44100, -1, full).samples)
        assertEquals(1600, AudioStart.drop(1600, 44100, -36_281, full).samples)
        // A decoder that dropped it all the same: the first piece left is the 448 samples that end the second frame,
        // stamped with that frame's time. Nothing more goes.
        val cut = intArrayOf(448) + IntArray(15) { 1024 }
        assertEquals(0, AudioStart.drop(1600, 44100, 23_220, cut).samples)
        // The same when it re-stamps what is left from zero, and when the lead-in is under a frame.
        assertEquals(0, AudioStart.drop(576, 44100, 0, cut).samples)
        // A whole frame dropped shows only in the time stamp.
        assertEquals(0, AudioStart.drop(1024, 44100, 23_220, full).samples)
        // A decoder that swallowed just its first frame: the rest of the lead-in goes.
        assertEquals(576, AudioStart.drop(1600, 44100, 23_220, full).samples)
        // More missing than the lead-in, or a stream that starts late: left alone.
        assertEquals(0, AudioStart.drop(576, 44100, 46_440, full).samples)
        assertEquals(0, AudioStart.drop(1600, 44100, 5_000_000, full).samples)
        // Pieces of uneven size, or too few of them, say nothing about the start: the lead-in goes.
        assertEquals(1600, AudioStart.drop(1600, 44100, 0, intArrayOf(2048, 4096, 1024, 4096, 4096, 4096, 4096, 4096)).samples)
        assertEquals(1600, AudioStart.drop(1600, 44100, 0, intArrayOf(4096) + IntArray(15) { 1024 }).samples)
        assertEquals(1600, AudioStart.drop(1600, 44100, 0, IntArray(3) { 1024 }).samples)
        // No lead-in declared, or an unbelievable one: nothing goes.
        assertEquals(0, AudioStart.drop(0, 44100, 0, full).samples)
        assertEquals(0, AudioStart.drop(100_000, 44100, 0, full).samples)
        // Bigger pieces of even size (a decoder handing over several frames at once) are full pieces too.
        assertEquals(2112, AudioStart.drop(2112, 48000, 0, IntArray(8) { 4096 }).samples)
    }
}
