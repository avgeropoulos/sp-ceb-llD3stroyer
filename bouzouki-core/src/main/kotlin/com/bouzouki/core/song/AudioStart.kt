package com.bouzouki.core.song

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Where a song's sound really starts in its decoded audio.
 *
 * An AAC file begins with a little audio that is not part of the song (the encoder's "priming", 20 to 50 ms), and
 * says so in its edit list: "play from this sample". A video player obeys it, so its clock's zero is that sample.
 * A decoder may or may not: some phones drop the priming, some hand it over. A song decoded with it has every note
 * that much late against the player, on those phones only. So the app looks for itself: [mp4Edit] reads what the file
 * says, and [drop] tells from the first decoded pieces whether the decoder already dropped it.
 */
object AudioStart {
    /** No real priming is longer than this many samples; anything beyond is not believed. */
    const val MAX_SAMPLES = 8192

    /** How many of the first decoded pieces [drop] looks at, and how many it needs. */
    const val PIECES = 16
    private const val NEEDED = 8

    /** The decoder dropped the priming if what is missing from its first pieces is this close to it (samples). */
    private const val CLOSE = 64

    /** An audio track's edit: its presentation starts at [mediaTime], counted in [timescale] units a second. */
    data class Edit(val mediaTime: Long, val timescale: Int) {
        /** The edit as samples at [rate]; 0 when it is not a believable priming. */
        fun samples(rate: Int): Int {
            if (mediaTime <= 0 || timescale <= 0 || rate <= 0) return 0
            val s = (mediaTime.toDouble() * rate / timescale).roundToLong()
            return if (s in 1..MAX_SAMPLES) s.toInt() else 0
        }
    }

    /** What to do with the start of the decoded audio: [samples] to drop, and [why] in a few words (kept with the song). */
    data class Drop(val samples: Int, val why: String)

    /**
     * How many samples to drop from the start of the decoded audio, given the priming the file declares ([delay]
     * samples at the decoded [rate]), the time stamp of the first decoded piece ([firstPtsUs], microseconds; negative
     * if unknown) and the sizes in samples of the first decoded pieces in order ([sizes], up to [PIECES]).
     *
     * A decoder that hands the priming over gives full pieces from time zero: then all of [delay] goes. One that
     * dropped it shows it: its first piece is short, or starts later, by just that much: then nothing goes. Anything
     * else is unclear, and nothing goes either (the audio stays as decoders always gave it).
     */
    fun drop(delay: Int, rate: Int, firstPtsUs: Long, sizes: IntArray): Drop {
        if (delay !in 1..MAX_SAMPLES) return Drop(0, "the file declares no lead-in")
        if (rate <= 0 || sizes.size < NEEDED) return Drop(0, "lead-in $delay, too little audio to tell")
        if (firstPtsUs < 0) return Drop(0, "lead-in $delay, no time stamps")
        val late = (firstPtsUs.toDouble() * rate / 1e6).roundToLong()
        if (late > MAX_SAMPLES) return Drop(0, "lead-in $delay, audio starts late")
        val full = sizes.max()
        val missing = (0 until NEEDED).sumOf { full - sizes[it] }
        val gone = late.toInt() + missing
        return when {
            gone == 0 -> Drop(delay, "lead-in $delay, the decoder kept it, dropped here")
            abs(gone - delay) <= CLOSE -> Drop(0, "lead-in $delay, the decoder dropped it")
            else -> Drop(0, "lead-in $delay, unclear ($gone missing), left alone")
        }
    }

    /**
     * The edit of the first audio track of an MP4 file (m4a, YouTube's included), or null when the file is not an
     * MP4, has no audio track, or its edit list is not the plain "start here" kind (one entry, played at normal speed).
     * A track without an edit list gives an edit of 0. Reads only the few small boxes it needs.
     */
    fun mp4Edit(file: File): Edit? = try {
        RandomAccessFile(file, "r").use { f -> mp4Edit(f) }
    } catch (_: Exception) {
        null
    }

    private fun mp4Edit(f: RandomAccessFile): Edit? {
        val moov = find(f, 0, f.length(), "moov") ?: return null
        var result: Edit? = null
        each(f, moov.first, moov.second) { type, from, to ->
            if (type != "trak" || result != null) return@each
            val mdia = find(f, from, to, "mdia") ?: return@each
            val hdlr = find(f, mdia.first, mdia.second, "hdlr")?.let { read(f, it, 12) } ?: return@each
            if (hdlr.size < 12 || String(hdlr, 8, 4, Charsets.ISO_8859_1) != "soun") return@each
            val timescale = find(f, mdia.first, mdia.second, "mdhd")?.let { read(f, it, 32) }?.let { b ->
                if (b.isEmpty()) null else if (b[0].toInt() == 1) int(b, 20) else int(b, 12)
            }?.toInt() ?: return@each
            if (timescale <= 0) return@each
            val edts = find(f, from, to, "edts")
            val elst = edts?.let { find(f, it.first, it.second, "elst") }?.let { read(f, it, 4096) }
            result = if (elst == null) Edit(0, timescale) else mediaTime(elst)?.let { Edit(it, timescale) }
            if (result == null) result = NONE // an edit list this does not read: say so rather than try the next track
        }
        return result.takeIf { it !== NONE }
    }

    private val NONE = Edit(-1, -1)

    /** The media time of an edit list that is one plain entry (after empty ones that only delay the track: not read). */
    private fun mediaTime(b: ByteArray): Long? {
        if (b.size < 8) return null
        val v1 = b[0].toInt() == 1
        val count = int(b, 4) ?: return null
        if (count != 1L) return null
        val at = 8
        val time: Long
        val rate: Long
        if (v1) {
            if (b.size < at + 20) return null
            time = long(b, at + 8)
            rate = int(b, at + 16) ?: return null
        } else {
            if (b.size < at + 12) return null
            time = (int(b, at + 4) ?: return null).toInt().toLong() // signed: -1 is an empty edit
            rate = int(b, at + 8) ?: return null
        }
        if (time < 0 || rate != 0x10000L) return null
        return time
    }

    /** The content of the first box of [type] among those between [from] and [to]: where it starts and ends. */
    private fun find(f: RandomAccessFile, from: Long, to: Long, type: String): Pair<Long, Long>? {
        var found: Pair<Long, Long>? = null
        each(f, from, to) { t, a, b -> if (found == null && t == type) found = a to b }
        return found
    }

    /** Walks the boxes between [from] and [to]: [visit] gets a box's type and where its content starts and ends. */
    private inline fun each(f: RandomAccessFile, from: Long, to: Long, visit: (String, Long, Long) -> Unit) {
        val head = ByteArray(16)
        var at = from
        var boxes = 0
        while (at + 8 <= to && boxes++ < 10_000) {
            f.seek(at)
            f.readFully(head, 0, 8)
            var size = int(head, 0)!!
            var body = at + 8
            if (size == 1L) {
                if (at + 16 > to) return
                f.readFully(head, 8, 8)
                size = long(head, 8)
                body = at + 16
            } else if (size == 0L) {
                size = to - at
            }
            val end = at + size
            if (size < body - at || end > to) return
            visit(String(head, 4, 4, Charsets.ISO_8859_1), body, end)
            at = end
        }
    }

    private fun read(f: RandomAccessFile, box: Pair<Long, Long>, most: Int): ByteArray {
        val n = minOf(box.second - box.first, most.toLong()).toInt()
        val b = ByteArray(n)
        f.seek(box.first)
        f.readFully(b)
        return b
    }

    private fun int(b: ByteArray, at: Int): Long? =
        if (at + 4 > b.size) null
        else ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun long(b: ByteArray, at: Int): Long = (int(b, at)!! shl 32) or int(b, at + 4)!!
}
