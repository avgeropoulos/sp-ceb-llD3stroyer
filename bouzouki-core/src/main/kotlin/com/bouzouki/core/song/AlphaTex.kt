package com.bouzouki.core.song

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A song's line as alphaTex, the text alphaTab (github.com/CoderLine/alphaTab, MPL-2.0) reads its tablature from: one
 * track, a tab staff with a string per course (the course's own pitch; alphaTab has no octave pairs), bars of 4/4
 * at the song's tempo. Note times are quantized to 32nds of the beat grid ([SongAnalysis.beats], else [DEFAULT_BPM]);
 * each note lasts until the next (a longer gap is a note then rests), split at bar lines. A hammer-on or pull-off is
 * marked on the note it starts from (`h`), as alphaTab writes it, a slide too (`sl`), and tremolo as tremolo picking
 * (`tp 16`). Checked against alphaTab's own parser (bouzouki-core's AlphaTexTest).
 */
object AlphaTex {
    const val DEFAULT_BPM = 100.0

    /** 32nds a bar of 4/4 has. */
    private const val BAR = 32

    /** Note lengths alphaTab writes, in 32nds, longest first: its duration and whether it is dotted. */
    private val LENGTHS = listOf(32 to (1 to false), 24 to (2 to true), 16 to (2 to false), 12 to (4 to true), 8 to (4 to false),
        6 to (8 to true), 4 to (8 to false), 3 to (16 to true), 2 to (16 to false), 1 to (32 to false))

    /** The tempo the song's beats say (their median spacing), or [DEFAULT_BPM]. */
    fun bpm(song: SongAnalysis): Double {
        val gaps = song.beats.zipWithNext { a, b -> b - a }.filter { it in 0.2..1.5 }.sorted()
        return if (gaps.size < 4) DEFAULT_BPM else 60.0 / gaps[gaps.size / 2]
    }

    fun of(song: SongAnalysis, bpm: Double = bpm(song)): String {
        val inst = song.instrument
        val notes = song.notes.sortedBy { it.start }
        val sb = StringBuilder()
        sb.append("\\title \"").append(song.title.replace("\\", "\\\\").replace("\"", "\\\"").replace('\n', ' ')).append("\"\n")
        sb.append("\\tempo ").append(bpm.roundToInt().coerceIn(30, 300)).append('\n')
        sb.append("\\track \"Bouzouki\"\n\\staff {tabs}\n")
        sb.append("\\tuning (").append(inst.courses.reversed().joinToString(" ") { com.bouzouki.core.Instrument.pitchName(it.openMidi) }).append(")\n")
        if (notes.isEmpty()) return sb.append(":1 r |\n").toString()
        // Where each note starts (32nds from the first beat, or the first note), strictly in order.
        val step = 60.0 / bpm / 8
        val origin = song.beats.firstOrNull()?.takeIf { it <= notes.first().start } ?: notes.first().start
        val at = IntArray(notes.size)
        for (i in notes.indices) {
            at[i] = ((notes[i].start - origin) / step).roundToInt().coerceAtLeast(0)
            if (i > 0 && at[i] <= at[i - 1]) at[i] = at[i - 1] + 1
        }
        var pos = 0 // 32nds written so far
        var lastDuration = -1
        fun duration(d: Int) { if (d != lastDuration) { sb.append(':').append(d).append(' '); lastDuration = d } }
        /** The pieces [len] 32nds from [pos] take: as alphaTab writes lengths, split at bar lines. */
        fun pieces(len: Int): List<Pair<Int, Pair<Int, Boolean>>> {
            val out = ArrayList<Pair<Int, Pair<Int, Boolean>>>()
            var left = len
            var p = pos
            while (left > 0) {
                val take = LENGTHS.first { it.first <= minOf(left, BAR - p % BAR) }
                out += take
                p += take.first
                left -= take.first
            }
            return out
        }
        /** Writes [len] 32nds of one note or rest ([write] gets the piece's index and how many there are). */
        fun emit(len: Int, write: (index: Int, count: Int) -> String) {
            val ps = pieces(len)
            for ((k, piece) in ps.withIndex()) {
                val (d, dotted) = piece.second
                duration(d)
                sb.append(write(k, ps.size)).append(if (dotted) " {d} " else " ")
                pos += piece.first
                if (pos % BAR == 0) sb.append("|\n")
            }
        }
        emit(at[0]) { _, _ -> "r" }
        for ((i, n) in notes.withIndex()) {
            val next = if (i + 1 < notes.size) at[i + 1] else at[i] + max(1, ((n.end - n.start) / step).roundToInt()).coerceAtMost(BAR)
            val gap = next - at[i]
            val nextNote = notes.getOrNull(i + 1)
            // A note hammered, pulled or slid from rings on into it (no rest between).
            val leadsOn = nextNote != null && nextNote.course == n.course && nextNote.articulation.isLegato
            val sounding = if (leadsOn) gap else max(1, ((n.end - n.start) / step).roundToInt()).coerceAtMost(gap)
            val props = buildList {
                if (nextNote != null && nextNote.course == n.course) when (nextNote.articulation) {
                    Articulation.HAMMER, Articulation.PULL -> add("h")
                    Articulation.SLIDE -> add("sl")
                    else -> {}
                }
            }
            val string = inst.courses.size - n.course
            val tremolo = n.articulation == Articulation.TREMOLO
            // The note (its first piece; a long one is tied on, so it rings, and the last piece leads on to the next note),
            // then rests for the rest of the gap.
            emit(sounding) { k, count ->
                val note = (if (k == 0) "${n.fret}.$string" else "-.$string") +
                    if (k == count - 1 && props.isNotEmpty()) props.joinToString(" ", "{", "}") else ""
                note + if (tremolo) " {tp 16}" else ""
            }
            emit(gap - sounding) { _, _ -> "r" }
        }
        if (pos % BAR != 0) emit(BAR - pos % BAR) { _, _ -> "r" }
        return sb.toString().trimEnd() + "\n"
    }
}
