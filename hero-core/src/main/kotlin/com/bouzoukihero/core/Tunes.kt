package com.bouzoukihero.core

import kotlin.math.pow

/**
 * A built-in song, written for this game. The melody is the bouzouki line you play;
 * the backing (bass, chords and percussion) is generated from the chord and rhythm
 * patterns.
 *
 * Melody tokens are `NOTE/LEN`, LEN in sixteenth notes, `~` after it for tremolo
 * (e.g. `A4/8~`) and `-/LEN` for a rest. `|` separates bars. Each phrase is four bars
 * and has one chord per bar. The per-bar patterns are one character per sixteenth:
 * [bass] `R` root, `F` fifth, `O` octave; [strum] `x` a chord; [drums] `D` doum
 * (low drum), `t` tek (high), `k` ka (soft high).
 */
class Tune(
    val id: String,
    val title: String,
    val style: String,
    val bpm: Int,
    val unitsPerBar: Int,
    val phrases: Map<Char, String>,
    val chords: Map<Char, String>,
    val structure: String,
    val bass: String,
    val strum: String,
    val drums: String,
) {
    class Note(val midi: Int, val start: Int, val len: Int, val tremolo: Boolean)

    val unitSec get() = 60.0 / bpm / 4.0
    val barSec get() = unitSec * unitsPerBar

    /** Bars of backing before the melody comes in. */
    val introBars get() = 2

    fun bars(): List<String> = structure.filter { !it.isWhitespace() }.flatMap { p ->
        (phrases[p] ?: error("$id: no phrase '$p'")).split('|').map { it.trim() }
    }

    fun barChords(): List<String> = structure.filter { !it.isWhitespace() }.flatMap { p ->
        (chords[p] ?: error("$id: no chords for '$p'")).trim().split(Regex("\\s+"))
    }

    /** The melody, with start times in sixteenths from the first melody bar. */
    fun melody(): List<Note> {
        val out = ArrayList<Note>()
        for ((b, bar) in bars().withIndex()) {
            var u = b * unitsPerBar
            for (tok in bar.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val (name, lenStr) = tok.split('/').also { require(it.size == 2) { "$id: bad token '$tok'" } }
                val tremolo = lenStr.endsWith("~")
                val len = lenStr.removeSuffix("~").toInt()
                if (name != "-") out.add(Note(midiOf(name), u, len, tremolo))
                u += len
            }
            require(u == (b + 1) * unitsPerBar) { "$id: bar ${b + 1} ('$bar') is ${u - b * unitsPerBar} sixteenths, not $unitsPerBar" }
        }
        return out
    }

    /** The melody as [SourceNote]s, timed to match [Synth.render] (which adds [LEAD_IN]). */
    fun sourceNotes(): List<SourceNote> {
        val offset = LEAD_IN + introBars * barSec
        return melody().map { SourceNote(offset + it.start * unitSec, it.len * unitSec, it.midi, it.tremolo) }
    }

    companion object {
        /** Seconds of silence before the music starts, so the first notes can scroll in. */
        const val LEAD_IN = 1.5

        private val noteRe = Regex("""([A-G])([#b]?)(\d)""")
        private val semis = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

        fun midiOf(name: String): Int {
            val m = noteRe.matchEntire(name) ?: error("Bad note '$name'")
            var s = semis.getValue(m.groupValues[1][0])
            if (m.groupValues[2] == "#") s++
            if (m.groupValues[2] == "b") s--
            return 12 * (m.groupValues[3].toInt() + 1) + s
        }

        fun freq(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)
    }
}

/** The songs that come with the game. All of them are original tunes. */
object Tunes {
    val all = listOf(
        Tune(
            id = "kafeneio",
            title = "Kafeneio Tsifteteli",
            style = "Tsifteteli 4/4 · Ousak on D",
            bpm = 100,
            unitsPerBar = 16,
            phrases = mapOf(
                'A' to "D5/4 C5/2 Bb4/2 A4/4 G4/4 | A4/2 Bb4/2 A4/2 G4/2 F4/4 Eb4/4 | D4/2 Eb4/2 F4/2 G4/2 A4/4 Bb4/4 | A4/16~",
                'B' to "F4/2 G4/2 A4/4 G4/2 F4/2 Eb4/4 | F4/4 Eb4/4 D4/8~ | G4/2 A4/2 Bb4/2 C5/2 D5/4 C5/4 | Bb4/4 A4/4 G4/8",
                'C' to "A4/1 Bb4/1 A4/1 G4/1 A4/4 D5/4 C5/4 | Bb4/2 C5/2 Bb4/2 A4/2 G4/8~ | G4/1 A4/1 G4/1 F4/1 G4/4 C5/4 Bb4/4 | A4/2 G4/2 F4/2 Eb4/2 D4/8",
            ),
            chords = mapOf('A' to "Gm Cm Dm Dm", 'B' to "Dm Dm Gm Gm", 'C' to "Dm Gm C Dm"),
            structure = "ABACBCA",
            bass = "R.....F.R...F...",
            strum = "..x...x...x...x.",
            drums = "D..t..t.D.t.t..k",
        ),
        Tune(
            id = "harbour",
            title = "Harbour Hasapiko",
            style = "Hasapiko 2/4 · Hitzaz on D",
            bpm = 112,
            unitsPerBar = 8,
            phrases = mapOf(
                'A' to "A4/4 Bb4/2 A4/2 | G4/2 F#4/2 G4/4 | A4/2 Bb4/2 C5/2 Bb4/2 | A4/8~",
                'B' to "D5/4 C5/2 Bb4/2 | A4/2 Bb4/2 A4/2 G4/2 | F#4/2 G4/2 Eb4/2 F#4/2 | D4/8~",
                'C' to "D4/1 Eb4/1 F#4/1 G4/1 A4/2 A4/2 | A4/1 Bb4/1 C5/1 D5/1 Eb5/2 D5/2 | C5/2 Bb4/2 A4/2 G4/2 | A4/4 -/4",
                'D' to "G4/2 A4/2 Bb4/4 | A4/2 G4/2 F#4/4 | Eb4/2 F#4/2 G4/2 F#4/2 | D4/8~",
            ),
            chords = mapOf('A' to "D Gm Gm D", 'B' to "Gm D Cm D", 'C' to "D D Cm D", 'D' to "Gm D Cm D"),
            structure = "ABADCCBDABCD",
            bass = "R...F...",
            strum = "..x...x.",
            drums = "D.t.k.t.",
        ),
        Tune(
            id = "kalamatiano",
            title = "Kalamatiano by the Sea",
            style = "Kalamatianos 7/8 · D major",
            bpm = 140,
            unitsPerBar = 14,
            phrases = mapOf(
                'A' to "A4/6 B4/4 A4/4 | G4/2 F#4/4 E4/4 F#4/4 | G4/6 A4/4 B4/4 | A4/14~",
                'B' to "D5/6 C#5/4 B4/4 | A4/6 B4/2 A4/2 G4/4 | F#4/6 G4/4 E4/4 | D4/14~",
                'C' to "D4/2 E4/2 F#4/2 G4/4 A4/4 | B4/2 C#5/2 D5/2 E5/4 D5/4 | C#5/6 B4/4 A4/4 | B4/6 A4/4 -/4",
            ),
            chords = mapOf('A' to "D Em G A", 'B' to "Bm D A D", 'C' to "D G A A"),
            structure = "ABABCCABA",
            bass = "R.....F...R...",
            strum = "..x...x...x...",
            drums = "D...k.t...t.k.",
        ),
        Tune(
            id = "serviko",
            title = "Fast Hands Serviko",
            style = "Hasaposerviko 2/4 · Hitzaz on D",
            bpm = 150,
            unitsPerBar = 8,
            phrases = mapOf(
                'A' to "D5/1 C5/1 Bb4/1 A4/1 G4/1 A4/1 Bb4/1 A4/1 | G4/2 F#4/2 G4/2 A4/2 | Bb4/1 A4/1 G4/1 F#4/1 G4/1 F#4/1 Eb4/1 F#4/1 | D4/8~",
                'B' to "D4/2 F#4/2 A4/2 D5/2 | C5/1 D5/1 C5/1 Bb4/1 A4/4 | G4/2 Bb4/2 A4/2 G4/2 | F#4/4 A4/4",
                'C' to "A4/1 Bb4/1 A4/1 Bb4/1 C5/2 D5/2 | Eb5/2 D5/2 C5/2 Bb4/2 | A4/1 G4/1 F#4/1 G4/1 A4/2 F#4/2 | G4/4 D5/4",
            ),
            chords = mapOf('A' to "Gm Gm Cm D", 'B' to "D D Gm D", 'C' to "D Cm D Gm"),
            structure = "AABBCCABCCAA",
            bass = "R...F...",
            strum = "..x...x.",
            drums = "D.t.D.t.",
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
