package com.spikerush.game

class Note(val step: Int, val midi: Int, val len: Int)
class Chord(val step: Int, val notes: IntArray, val len: Int)

/** A song is a grid of 16th-note steps with a lead, a bass, arpeggiated chords and drums. */
class Song(
    val bpm: Float,
    val steps: Int,
    val loop: Boolean,
    lead: List<Note>,
    bass: List<Note>,
    chords: List<Chord>,
    val drums: IntArray,
) {
    val leadAt = arrayOfNulls<Note>(steps).also { a -> lead.forEach { if (it.step < steps) a[it.step] = it } }
    val bassAt = arrayOfNulls<Note>(steps).also { a -> bass.forEach { if (it.step < steps) a[it.step] = it } }
    val chordAt = arrayOfNulls<Chord>(steps).also { a -> chords.forEach { if (it.step < steps) a[it.step] = it } }

    companion object {
        const val KICK = 1
        const val SNARE = 2
        const val HAT = 4
    }
}

/**
 * Original compositions in the spirit of a cocky, bouncy "villain kid" boss theme:
 * minor key, march-like oom-pah bass, offbeat stabs and a sneaky chromatic bridge.
 */
object Music {

    /** Parses "C5:2 D#5:1 -:2 ..." (note:length-in-16ths, "-" = rest) starting at [start]. */
    private fun seq(start: Int, text: String): List<Note> {
        val out = ArrayList<Note>()
        var s = start
        for (tok in text.trim().split(Regex("\\s+"))) {
            val (name, len) = tok.split(":").let { it[0] to it[1].toInt() }
            if (name != "-") out.add(Note(s, midi(name), len))
            s += len
        }
        return out
    }

    private fun midi(name: String): Int {
        val base = when (name[0]) {
            'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B' -> 11
            else -> error("bad note $name")
        }
        var i = 1
        var acc = 0
        if (name[i] == '#') { acc = 1; i++ } else if (name[i] == 'b') { acc = -1; i++ }
        val octave = name.substring(i).toInt()
        return 12 * (octave + 1) + base + acc
    }

    private val CM = intArrayOf(60, 63, 67)
    private val FM = intArrayOf(60, 65, 68)
    private val AB = intArrayOf(60, 63, 68)
    private val G7 = intArrayOf(59, 62, 65, 67)
    private val DB = intArrayOf(61, 65, 68)
    private val CMAJ = intArrayOf(60, 64, 67)
    private val BB = intArrayOf(62, 65, 70)

    val main: Song by lazy { buildMain() }
    val clear: Song by lazy { buildClear() }
    val gameOver: Song by lazy { buildGameOver() }

    private fun buildMain(): Song {
        val bars = listOf(
            // A
            "G4:2 C5:2 D#5:2 C5:2 G5:3 F#5:1 G5:2 -:2",
            "F5:2 D#5:2 D5:2 C5:2 D5:4 -:2 G4:2",
            "G#4:2 C5:2 D#5:2 G#5:2 G5:2 F5:2 D#5:2 C5:2",
            "D5:2 B4:2 G4:2 B4:2 D5:4 G5:2 -:2",
            // A'
            "G4:2 C5:2 D#5:2 C5:2 G5:3 F#5:1 G5:2 -:2",
            "F5:2 G5:2 G#5:2 G5:2 F5:4 -:2 D#5:2",
            "G#5:2 G5:2 F5:2 D#5:2 F5:2 D#5:2 D5:2 C5:2",
            "D5:2 F5:2 D5:2 B4:2 C5:4 -:4",
            // B (sneaky bridge)
            "C5:2 C5:1 C5:1 G#4:2 F4:2 G#4:2 C5:2 F5:4",
            "D#5:2 D#5:1 D#5:1 C5:2 G4:2 C5:2 D#5:2 G5:4",
            "F5:2 C#5:2 G#4:2 C#5:2 F5:2 G#5:2 F5:2 C#5:2",
            "D5:2 F5:2 B5:2 G5:2 B4:1 C5:1 D5:1 F5:1 G5:4",
            // B'
            "C5:2 C5:1 C5:1 G#4:2 F4:2 G#4:2 C5:2 F5:4",
            "D#5:2 D#5:1 D#5:1 C5:2 G4:2 C5:2 D#5:2 G5:4",
            "F5:2 C#5:2 G#4:2 C#5:2 F5:2 G#5:2 F5:2 C#5:2",
            "G5:2 F5:2 D5:2 B4:2 G4:2 A4:1 B4:1 D5:2 -:2",
        )
        val chords = listOf(CM, CM, AB, G7, CM, FM, AB, G7, FM, CM, DB, G7, FM, CM, DB, G7)
        // Bass roots per bar (C2 = 36).
        val roots = intArrayOf(36, 36, 44, 43, 36, 41, 44, 43, 41, 36, 37, 43, 41, 36, 37, 43)

        val lead = ArrayList<Note>()
        val bass = ArrayList<Note>()
        val chordList = ArrayList<Chord>()
        val drums = IntArray(16 * 16)

        for (b in 0 until 16) {
            val s = b * 16
            lead += seq(s, bars[b])

            val r = roots[b]
            val pattern = when (b) {
                7 -> intArrayOf(43, 55, 50, 55, 36, 48, 43, 48) // V -> i
                11, 15 -> intArrayOf(43, 55, 43, 55, 43, 45, 47, 50) // walk up
                else -> intArrayOf(r, r + 12, r + 7, r + 12, r, r + 12, r + 7, r + 12)
            }
            pattern.forEachIndexed { i, m -> bass += Note(s + i * 2, m, 2) }

            // Offbeat "brass" stabs.
            for (o in intArrayOf(2, 6, 10, 14)) chordList += Chord(s + o, chords[b], 2)

            for (i in 0 until 16) {
                var d = 0
                if (i % 2 == 0) d = d or Song.HAT
                if (i == 0 || i == 8) d = d or Song.KICK
                if (b >= 8 && i == 10) d = d or Song.KICK
                if (i == 4 || i == 12) d = d or Song.SNARE
                if ((b == 7 || b == 15) && i >= 12) d = d or Song.SNARE
                drums[s + i] = d
            }
        }
        return Song(148f, 256, true, lead, bass, chordList, drums)
    }

    private fun buildClear(): Song {
        val lead = seq(0, "G4:1 C5:1 E5:1 G5:1 C6:4 A#5:2 G#5:2 A#5:2 C6:10 -:8")
        val bass = seq(0, "C3:8 G#2:4 A#2:4 C3:12 -:4")
        val chords = listOf(Chord(0, CMAJ, 8), Chord(8, AB, 4), Chord(12, BB, 4), Chord(16, CMAJ, 12))
        val drums = IntArray(32)
        drums[0] = Song.KICK; drums[8] = Song.SNARE; drums[12] = Song.SNARE
        drums[14] = Song.SNARE; drums[15] = Song.SNARE; drums[16] = Song.KICK or Song.SNARE
        return Song(160f, 32, false, lead, bass, chords, drums)
    }

    private fun buildGameOver(): Song {
        val lead = seq(0, "C5:2 B4:2 A#4:2 A4:2 G#4:4 G4:4 C4:8 -:8")
        val bass = seq(0, "C3:8 C#3:4 G2:4 C2:8 -:8")
        val drums = IntArray(32)
        drums[24] = Song.KICK
        return Song(120f, 32, false, lead, bass, emptyList(), drums)
    }
}
