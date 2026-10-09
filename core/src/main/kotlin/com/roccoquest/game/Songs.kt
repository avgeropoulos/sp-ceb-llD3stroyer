package com.roccoquest.game

/** Original background tunes in a bouncy 8-bit platformer style. */
object Songs {
    /**
     * One chord per bar: "root fifth upperA upperB". The bass alternates root and
     * fifth; the harmony voice plays the two upper notes on the off-beats.
     */
    private fun oomPahBass(chords: List<String>) = chords.joinToString(" ") {
        val (r, f) = it.split(' ')
        "$r/2 -/2 $f/2 -/2 $r/2 -/2 $f/2 -/2"
    }

    private fun offbeats(chords: List<String>) = chords.joinToString(" ") {
        val p = it.split(' ')
        "-/2 ${p[2]}/2 -/2 ${p[3]}/2 -/2 ${p[2]}/2 -/2 ${p[3]}/2"
    }

    private fun drivingBass(chords: List<String>) = chords.joinToString(" ") {
        val (r, f) = it.split(' ')
        "$r/2 $r/2 $f/2 $r/2 $r/2 $r/2 $f/2 $r/2"
    }

    private fun sustained(chords: List<String>) = chords.joinToString(" ") {
        val p = it.split(' ')
        "${p[2]}/8 ${p[3]}/8"
    }

    private const val C = "C3 G3 E4 G4"
    private const val F = "F2 C3 F4 A4"
    private const val Dm = "D3 A3 F4 A4"
    private const val G = "G2 D3 B3 D4"
    private const val Am = "A2 E3 C4 E4"

    // ------------------------------------------------------------------ Meadow theme (C major)
    private val overworldA = """
        E5/2 G5/2 -/1 C6/3 B5/2 G5/2 -/2 E5/2
        F5/2 A5/2 -/2 G5/2 E5/4 -/4
        D5/2 F5/2 -/1 A5/3 G5/2 F5/2 -/2 D5/2
        E5/2 G5/2 -/2 C5/2 D5/4 -/4
        E5/2 G5/2 -/1 C6/3 B5/2 G5/2 -/2 C6/2
        D6/2 C6/2 -/2 A5/2 G5/4 -/2 E5/2
        F5/2 E5/2 D5/2 G5/2 -/2 G4/2 B4/2 D5/2
        C5/6 -/2 G4/2 C5/2 -/4
    """
    private val overworldB = """
        A5/2 -/2 A5/2 G5/2 -/2 F5/2 -/2 E5/2
        F5/4 C5/2 D5/2 F5/4 -/4
        G5/2 -/2 G5/2 F5/2 -/2 E5/2 -/2 D5/2
        E5/4 C5/2 E5/2 G5/4 -/4
        A5/2 -/2 C6/2 A5/2 -/2 G5/2 -/2 F5/2
        E5/2 G5/2 C6/4 -/2 B5/2 A5/2 G5/2
        F5/2 A5/2 G5/2 F5/2 E5/2 D5/2 -/2 B4/2
        C5/4 E5/2 G5/2 C6/4 -/4
    """
    private val overworldChordsA = listOf(C, F, Dm, G, C, F, G, C)
    private val overworldChordsB = listOf(F, F, G, C, F, C, G, C)

    val overworld = Song(
        bpm = 160,
        lead = overworldA + overworldB + overworldA,
        harmony = offbeats(overworldChordsA + overworldChordsB + overworldChordsA),
        bass = oomPahBass(overworldChordsA + overworldChordsB + overworldChordsA),
        drums = "k.h.s.h.k.k.s.h.",
    )

    // ------------------------------------------------------------------ Cavern theme (A minor)
    private val undergroundLead = """
        A4/1 -/1 A5/1 -/1 E5/1 -/3 G5/1 -/1 F5/1 -/1 E5/1 -/3
        D5/1 -/1 E5/1 -/1 C5/2 -/2 B4/2 -/6
        A4/1 -/1 A5/1 -/1 E5/1 -/3 G5/1 -/1 A5/1 -/1 B5/1 -/3
        C6/2 B5/2 A5/2 E5/2 G#5/4 -/4
        A4/1 -/1 A5/1 -/1 E5/1 -/3 G5/1 -/1 F5/1 -/1 E5/1 -/3
        D5/1 -/1 E5/1 -/1 C5/2 -/2 B4/2 -/6
        A4/1 -/1 A5/1 -/1 E5/1 -/3 G5/1 -/1 A5/1 -/1 B5/1 -/3
        A5/2 E5/2 C5/2 B4/2 A4/4 -/4
    """
    private val undergroundRoots = listOf("A2", "D2", "A2", "E2", "A2", "D2", "E2", "A2")

    val underground = Song(
        bpm = 140,
        lead = undergroundLead,
        harmony = "",
        bass = undergroundRoots.joinToString(" ") { r ->
            val up = r.replace('2', '3')
            "$r/1 -/1 $up/1 -/1 $r/1 -/1 $up/1 -/1 $r/1 -/1 $up/1 -/1 $r/1 -/1 $up/1 -/1"
        },
        drums = "k...h.k.s...h...",
        leadDuty = 0.5f,
    )

    // ------------------------------------------------------------------ Sky theme (G major)
    private val skyLead = """
        B4/2 D5/2 G5/4 F#5/2 G5/2 A5/4
        B5/6 A5/2 G5/4 -/4
        C5/2 E5/2 A5/4 G5/2 A5/2 B5/4
        C6/6 B5/2 A5/4 -/4
        B5/2 A5/2 G5/2 E5/2 D5/4 G5/4
        E5/2 D5/2 C5/2 E5/2 D5/4 -/4
        A4/2 B4/2 C5/2 D5/2 E5/2 F#5/2 G5/2 A5/2
        G5/8 -/8
    """
    private val skyChords = listOf(
        "G2 D3 B4 D5", "E2 B2 G4 B4", "C3 G3 E4 A4", "D3 A3 F#4 A4",
        "G2 D3 B4 D5", "C3 G3 E4 G4", "D3 A3 F#4 C5", "G2 D3 B4 D5",
    )

    val sky = Song(
        bpm = 150,
        lead = skyLead,
        harmony = offbeats(skyChords),
        bass = oomPahBass(skyChords),
        drums = "k...s.h.k.h.s...",
        leadDuty = 0.125f,
    )

    // ------------------------------------------------------------------ Castle theme (D minor)
    private val castleLead = """
        D5/2 F5/2 A5/2 G#5/2 A5/4 -/4
        D5/2 F5/2 A5/2 Bb5/2 A5/4 -/4
        C6/2 Bb5/2 A5/2 G5/2 F5/2 E5/2 D5/2 C#5/2
        D5/8 -/8
        D5/2 F5/2 A5/2 G#5/2 A5/4 -/4
        D5/2 F5/2 A5/2 C6/2 Bb5/4 -/4
        A5/2 G5/2 F5/2 E5/2 F5/2 E5/2 D5/2 C#5/2
        D5/4 A4/4 D4/4 -/4
    """
    private val castleChords = listOf(
        "D2 A2 F4 A4", "D2 A2 F4 Bb4", "A2 E3 E4 G4", "D2 A2 F4 A4",
        "D2 A2 F4 A4", "Bb1 F2 D4 F4", "A1 E2 C#4 E4", "D2 A2 D4 F4",
    )

    val castle = Song(
        bpm = 128,
        lead = castleLead,
        harmony = sustained(castleChords),
        bass = drivingBass(castleChords),
        drums = "k.k.s.k.k.k.s.h.",
    )

    // ------------------------------------------------------------------ King Krag battle (E minor)
    private val bossLead = """
        E5/1 -/1 E5/1 -/1 G5/2 E5/2 A5/2 G5/2 F#5/2 D#5/2
        E5/4 B4/4 E5/2 -/6
        C6/2 B5/2 A5/2 G5/2 A5/2 G5/2 F#5/2 E5/2
        D#5/4 F#5/4 B5/4 -/4
        E5/1 -/1 E5/1 -/1 G5/2 E5/2 A5/2 G5/2 F#5/2 D#5/2
        E5/4 B4/4 E5/2 -/6
        C6/2 D6/2 E6/2 D6/2 C6/2 B5/2 A5/2 G5/2
        F#5/2 D#5/2 B4/2 D#5/2 E5/4 -/4
    """
    private val bossChords = listOf(
        "E2 B2 G4 B4", "E2 B2 G4 B4", "A2 E3 A4 C5", "B1 F#2 D#4 F#4",
        "E2 B2 G4 B4", "E2 B2 G4 B4", "C2 G2 E4 G4", "B1 F#2 D#4 F#4",
    )

    val boss = Song(
        bpm = 172,
        lead = bossLead,
        harmony = offbeats(bossChords),
        bass = drivingBass(bossChords),
        drums = "k.hsk.hsk.hsk.ss",
    )

    // ------------------------------------------------------------------ Super Star (F major, fast)
    private val starLead = """
        F5/2 A5/2 C6/2 A5/2 F5/2 A5/2 C6/4
        Bb5/2 G5/2 E5/2 G5/2 Bb5/2 D6/2 C6/4
        F5/2 A5/2 C6/2 F6/2 E6/2 C6/2 A5/4
        G5/2 Bb5/2 A5/2 G5/2 F5/4 -/4
    """
    private val starChords = listOf("F2 C3 A4 C5", "C2 G2 G4 Bb4", "F2 C3 A4 C5", "C2 G2 E4 G4")

    val star = Song(
        bpm = 196,
        lead = starLead + starLead,
        harmony = offbeats(starChords + starChords),
        bass = drivingBass(starChords + starChords),
        drums = "k.hsk.hsk.hsk.hs",
        leadDuty = 0.5f,
    )

    // ------------------------------------------------------------------ Wonder (dreamy, augmented chords)
    private val wonderLead = """
        C5/2 E5/2 G#5/2 C6/2 G#5/2 E5/2 C5/4
        D5/2 F#5/2 A#5/2 D6/2 A#5/2 F#5/2 D5/4
        E5/4 G5/2 B5/2 E6/4 D6/2 B5/2
        C6/6 B5/2 G5/4 -/4
        C5/2 E5/2 G#5/2 C6/2 G#5/2 E5/2 C5/4
        D5/2 F#5/2 A#5/2 D6/2 A#5/2 F#5/2 D5/4
        F5/2 A5/2 C6/2 F6/2 E6/2 C6/2 A5/2 G5/2
        C6/8 -/8
    """
    private val wonderChords = listOf(
        "C3 G3 E4 G#4", "D3 A3 F#4 A#4", "E3 B3 G4 B4", "C3 G3 E4 G4",
        "C3 G3 E4 G#4", "D3 A3 F#4 A#4", "F2 C3 A4 C5", "C3 G3 E4 G4",
    )

    val wonder = Song(
        bpm = 140,
        lead = wonderLead,
        harmony = offbeats(wonderChords),
        bass = oomPahBass(wonderChords),
        drums = "k..hs.h.k.hhs..h",
        leadDuty = 0.125f,
    )

    fun of(m: Music): Song? = when (m) {
        Music.NONE -> null
        Music.OVERWORLD -> overworld
        Music.UNDERGROUND -> underground
        Music.SKY -> sky
        Music.CASTLE -> castle
        Music.BOSS -> boss
        Music.STAR -> star
        Music.WONDER -> wonder
    }
}
