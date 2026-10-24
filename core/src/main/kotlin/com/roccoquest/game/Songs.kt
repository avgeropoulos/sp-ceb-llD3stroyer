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

    // ------------------------------------------------------------------ Ring-Ding Frog's crazy song
    private val frogLead = """
        A4/1 A5/1 -/1 A4/1 A5/1 -/1 G5/1 E5/1 -/2 C5/2 D5/2 E5/2
        F5/1 F5/1 -/1 F5/1 A5/2 G5/2 F5/1 E5/1 -/2 D5/4
        A4/1 A5/1 -/1 A4/1 A5/1 -/1 C6/1 B5/1 -/2 A5/2 G5/2 E5/2
        D5/2 E5/2 G5/2 A5/2 A5/1 -/1 A5/1 -/1 A5/4
    """
    private val frogChords = listOf("A2 E3 C4 E4", "D2 A2 D4 F4", "A2 E3 C4 E4", "E2 B2 B3 E4")

    val frog = Song(
        bpm = 172,
        lead = frogLead + frogLead,
        harmony = offbeats(frogChords + frogChords),
        bass = drivingBass(frogChords + frogChords),
        drums = "k.k.s.k.k.k.s.hs",
        leadDuty = 0.25f,
    )

    // ------------------------------------------------------------------ King Krag storms in (crazy, very fast, E minor)
    private val kragLead = """
        E5/1 F5/1 E5/1 D#5/1 E5/2 B4/2 E5/1 F5/1 E5/1 D#5/1 E5/2 G5/2
        A5/1 G5/1 F5/1 E5/1 D5/2 E5/2 F5/4 E5/4
        E5/1 F5/1 E5/1 D#5/1 E5/2 B4/2 E5/1 F5/1 E5/1 D#5/1 E5/2 C6/2
        B5/2 A5/2 G#5/2 A5/2 B5/4 -/4
        E6/1 D6/1 C6/1 B5/1 A5/1 G5/1 F5/1 E5/1 D#5/2 E5/2 F#5/2 G#5/2
        A5/4 C6/2 A5/2 E5/4 A4/4
        F5/1 F5/1 F5/1 F5/1 E5/2 E5/2 D#5/2 D#5/2 D5/2 D5/2
        E5/4 E6/4 E5/2 -/2 E5/1 E5/1 -/2
    """
    private val kragChords = listOf(
        "E2 B2 E4 G4", "D2 A2 F4 A4", "E2 B2 E4 G4", "E2 B2 G#4 B4",
        "C2 G2 E4 G4", "A2 E3 C4 E4", "F2 C3 A4 C5", "E2 B2 G#4 B4",
    )

    val krag = Song(
        bpm = 208,
        lead = kragLead,
        harmony = offbeats(kragChords),
        bass = drivingBass(kragChords),
        drums = "kkhskkhskkhskshs",
        leadDuty = 0.5f,
    )

    // ------------------------------------------------------------------ The Warden (slow, dark, heartbeat drums)
    private val wardenLead = """
        C4/4 -/4 C#4/4 -/4
        C4/2 G3/2 -/4 F#3/8
        C4/4 -/4 D#4/4 D4/4
        C4/12 -/4
    """
    private val wardenChords = listOf("C2 G2 C3 D#3", "C2 F#2 C3 F#3", "C2 G2 C3 D#3", "C2 G2 C3 C3")

    val warden = Song(
        bpm = 96,
        lead = wardenLead + wardenLead,
        harmony = sustained(wardenChords + wardenChords),
        bass = List(8) { "C2/1 -/1 C2/1 -/13" }.joinToString(" "),
        drums = "k.k.........h...",
        leadDuty = 0.5f,
    )

    // ------------------------------------------------------------------ Meadow (F major, bouncy)
    private val meadowChords = listOf(
        "F2 C3 A4 C5", "D2 A2 F4 A4", "Bb1 F2 D4 F4", "C2 G2 E4 G4",
        "F2 C3 A4 C5", "D2 A2 F4 A4", "C2 G2 E4 G4", "F2 C3 A4 C5",
    )
    val meadow = Song(
        bpm = 140,
        lead = """
            F5/2 A5/2 C6/4 A5/2 F5/2 G5/4
            A5/2 G5/2 F5/2 D5/2 C5/8
            Bb4/2 D5/2 F5/4 E5/2 D5/2 C5/4
            D5/4 E5/4 F5/8
            F5/2 A5/2 C6/4 A5/2 F5/2 G5/4
            C6/2 D6/2 C6/2 A5/2 F5/8
            G5/2 A5/2 Bb5/2 G5/2 E5/2 F5/2 G5/4
            F5/8 -/8
        """,
        harmony = offbeats(meadowChords),
        bass = oomPahBass(meadowChords),
        drums = "k...s.h.k.k.s.h.",
        leadDuty = 0.125f,
    )

    // ------------------------------------------------------------------ Jungle (A minor, drums!)
    private val jungleChords = listOf(
        "A2 E3 C4 E4", "G2 D3 B3 D4", "A2 E3 C4 E4", "E2 B2 G4 B4",
        "F2 C3 A4 C5", "D2 A2 F4 A4", "G2 D3 B3 D4", "A2 E3 C4 E4",
    )
    val jungle = Song(
        bpm = 156,
        lead = """
            A4/2 C5/2 E5/2 A5/2 G5/4 E5/4
            D5/2 E5/2 G5/2 E5/2 D5/4 C5/4
            A4/2 C5/2 E5/2 A5/2 B5/4 A5/4
            G5/2 E5/2 D5/2 E5/2 A4/8
            C6/2 B5/2 A5/2 G5/2 A5/4 E5/4
            F5/2 E5/2 D5/2 C5/2 D5/4 E5/4
            A5/2 G5/2 E5/2 D5/2 C5/2 D5/2 E5/4
            A4/8 -/8
        """,
        harmony = offbeats(jungleChords),
        bass = drivingBass(jungleChords),
        drums = "k..hk.k.s.hkk.s.",
    )

    // ------------------------------------------------------------------ Snow (G major, slow and twinkly)
    private val snowChords = listOf(
        "G2 D3 B3 D4", "E2 B2 G4 B4", "C3 G3 E4 G4", "D2 A2 F#4 A4",
        "G2 D3 B3 D4", "A2 E3 C4 E4", "D2 A2 F#4 A4", "G2 D3 B3 D4",
    )
    val snow = Song(
        bpm = 118,
        lead = """
            D5/4 G5/4 B5/4 A5/4
            G5/6 F#5/2 E5/8
            C5/4 E5/4 G5/4 F#5/4
            E5/6 D5/2 D5/8
            D5/4 G5/4 B5/4 D6/4
            C6/4 B5/4 A5/4 G5/4
            E5/4 A5/4 G5/4 F#5/4
            G5/12 -/4
        """,
        harmony = sustained(snowChords),
        bass = oomPahBass(snowChords),
        drums = "k.......h.......",
        leadDuty = 0.125f,
    )

    // ------------------------------------------------------------------ Desert (D, sandy and mysterious)
    private val desertChords = listOf(
        "D2 A2 F#4 A4", "Eb2 Bb2 G4 Bb4", "D2 A2 F#4 A4", "D2 A2 F#4 A4",
        "D2 A2 F#4 A4", "Eb2 Bb2 G4 Bb4", "G2 D3 G4 Bb4", "D2 A2 F#4 A4",
    )
    val desert = Song(
        bpm = 136,
        lead = """
            D5/2 Eb5/2 F#5/2 G5/2 A5/4 G5/4
            F#5/2 Eb5/2 D5/4 -/8
            A5/2 Bb5/2 A5/2 G5/2 F#5/4 Eb5/4
            D5/12 -/4
            D5/2 F#5/2 A5/2 D6/2 C6/4 Bb5/4
            A5/2 G5/2 F#5/2 Eb5/2 D5/8
            G5/2 F#5/2 Eb5/2 D5/2 Eb5/2 F#5/2 G5/4
            D5/8 -/8
        """,
        harmony = offbeats(desertChords),
        bass = drivingBass(desertChords),
        drums = "k.h.s.h.k.k.s.h.",
    )

    // ------------------------------------------------------------------ Night (C minor, quiet and spooky)
    private val nightChords = listOf(
        "C2 G2 Eb4 G4", "F2 C3 Ab4 C5", "C2 G2 Eb4 G4", "C2 G2 Eb4 G4",
        "C2 G2 Eb4 G4", "F2 C3 Ab4 C5", "G2 D3 B3 D4", "C2 G2 Eb4 G4",
    )
    val night = Song(
        bpm = 108,
        lead = """
            C5/4 Eb5/4 G5/4 Eb5/4
            D5/4 F5/4 Ab5/8
            G5/4 F5/4 Eb5/4 D5/4
            C5/12 -/4
            Eb5/4 G5/4 C6/4 Bb5/4
            Ab5/4 G5/4 F5/8
            Eb5/4 D5/4 B4/4 D5/4
            C5/12 -/4
        """,
        harmony = sustained(nightChords),
        bass = oomPahBass(nightChords),
        drums = "k.......s.......",
        leadDuty = 0.25f,
    )

    // ------------------------------------------------------------------ Beach (C major, island style)
    private val beachChords = listOf(
        "C3 G3 E4 G4", "A2 E3 C4 E4", "D3 A3 F4 A4", "G2 D3 B3 D4",
        "C3 G3 E4 G4", "E2 B2 G4 B4", "F2 C3 A4 C5", "C3 G3 E4 G4",
    )
    val beach = Song(
        bpm = 132,
        lead = """
            E5/2 G5/2 C6/2 G5/2 A5/4 G5/4
            F5/2 E5/2 D5/2 E5/2 C5/8
            D5/2 F5/2 A5/2 F5/2 G5/4 F5/4
            E5/2 D5/2 C5/2 D5/2 G4/8
            E5/2 G5/2 C6/2 G5/2 A5/4 C6/4
            B5/2 A5/2 G5/2 F5/2 E5/4 G5/4
            A5/2 G5/2 F5/2 E5/2 D5/2 E5/2 F5/4
            C5/8 -/8
        """,
        harmony = offbeats(beachChords),
        bass = oomPahBass(beachChords),
        drums = "k..sk.s.k..sk.s.",
        leadDuty = 0.125f,
    )

    // ------------------------------------------------------------------ Doom (B minor, an ominous boss battle)
    private val doomChords = listOf(
        "B1 F#2 D4 F#4", "C2 G2 E4 G4", "B1 F#2 D4 F#4", "B1 F#2 D4 F#4",
        "E2 B2 G4 B4", "C2 G2 E4 G4", "B1 F#2 D4 F#4", "B1 F#2 D4 F#4",
    )
    val doom = Song(
        bpm = 150,
        lead = """
            B4/1 -/1 B4/1 -/1 C5/2 B4/2 F5/4 E5/4
            D5/2 C5/2 B4/4 -/8
            B4/1 -/1 B4/1 -/1 D5/2 C5/2 G5/4 F#5/4
            F5/4 E5/4 D5/4 C5/4
            B5/2 A5/2 G5/2 F5/2 E5/2 D5/2 C5/2 B4/2
            C5/4 B4/4 F5/8
            B4/1 C5/1 B4/1 C5/1 B4/1 C5/1 B4/1 C5/1 D5/4 F5/4
            B4/8 -/8
        """,
        harmony = sustained(doomChords),
        bass = drivingBass(doomChords),
        drums = "k.k.k.s.k.k.k.s.",
        leadDuty = 0.5f,
    )

    // ------------------------------------------------------------------ Sea shanty (D minor, for the pirate ships)
    private val shantyChords = listOf(
        "D2 A2 F4 A4", "C2 G2 E4 G4", "C2 G2 E4 G4", "D2 A2 F4 A4",
        "F2 C3 A4 C5", "Bb1 F2 D4 F4", "C2 G2 E4 G4", "D2 A2 D4 F4",
    )
    val shanty = Song(
        bpm = 150,
        lead = """
            D5/2 D5/2 D5/2 A4/2 D5/2 F5/2 A5/4
            G5/2 F5/2 E5/2 C5/2 E5/8
            C5/2 C5/2 C5/2 G4/2 C5/2 E5/2 G5/4
            F5/2 E5/2 D5/2 A4/2 D5/8
            A5/2 A5/2 G5/2 F5/2 G5/4 A5/4
            Bb5/2 A5/2 G5/2 F5/2 E5/8
            D5/2 F5/2 A5/2 F5/2 E5/2 C5/2 E5/4
            D5/8 -/8
        """,
        harmony = offbeats(shantyChords),
        bass = oomPahBass(shantyChords),
        drums = "k..sk..sk..sk.s.",
        leadDuty = 0.25f,
    )

    // ------------------------------------------------------------------ Pokemon Park: a happy bouncy tune
    private val parkChords = listOf(
        "C3 G3 E4 G4",
        "A2 E3 C4 E4",
        "F2 C3 F4 A4",
        "G2 D3 B3 D4",
        "C3 G3 E4 G4",
        "A2 E3 C4 E4",
        "F2 C3 A4 C5",
        "G2 D3 B3 D4",
    )
    val park = Song(
        bpm = 168,
        lead = """
            C5/2 E5/2 G5/2 E5/2 C6/4 G5/4
            A5/2 G5/2 E5/2 C5/2 E5/4 -/4
            F5/2 A5/2 C6/2 A5/2 F5/2 A5/2 C6/4
            B5/2 A5/2 G5/2 D5/2 G5/8
            C6/2 B5/2 A5/2 G5/2 E5/2 G5/2 C6/4
            A5/2 C6/2 E6/2 C6/2 A5/4 -/4
            F5/2 G5/2 A5/2 C6/2 A5/2 G5/2 F5/4
            D5/2 E5/2 F5/2 B4/2 C5/8
        """,
        harmony = offbeats(parkChords),
        bass = oomPahBass(parkChords),
        drums = "k.h.s.h.k.k.s.hh",
        leadDuty = 0.25f,
    )

    // ------------------------------------------------------------------ Home: calm and cozy
    private val cozyChords = listOf(
        "F2 C3 A4 C5",
        "D3 A3 F4 A4",
        "Bb1 F2 D4 F4",
        "C2 G2 E4 G4",
        "F2 C3 A4 C5",
        "A2 E3 C4 E4",
        "Bb1 F2 D4 F4",
        "C2 G2 E4 G4",
    )
    val cozy = Song(
        bpm = 96,
        lead = """
            A5/4 C6/4 A5/4 F5/4
            F5/4 A5/4 D5/8
            D5/4 F5/4 Bb5/4 A5/4
            G5/8 E5/8
            A5/4 C6/4 F6/4 E6/4
            E6/4 C6/4 A5/8
            Bb5/4 A5/4 G5/4 F5/4
            E5/4 G5/4 F5/8
        """,
        harmony = sustained(cozyChords),
        bass = oomPahBass(cozyChords),
        drums = "k.......s.......",
        leadDuty = 0.5f,
    )

    // ------------------------------------------------------------------ World map: off on an adventure
    private val adventureChords = listOf(
        "D3 A3 F4 A4",
        "Bb1 F2 D4 F4",
        "C2 G2 E4 G4",
        "A2 E3 C#4 E4",
        "D3 A3 F4 A4",
        "Bb1 F2 D4 F4",
        "G2 D3 Bb3 D4",
        "A2 E3 C#4 E4",
    )
    val adventure = Song(
        bpm = 140,
        lead = """
            D5/4 A5/4 F5/2 E5/2 D5/4
            Bb4/2 D5/2 F5/4 Bb5/8
            C5/2 E5/2 G5/4 E5/2 C5/2 G5/4
            A5/4 G5/2 F5/2 E5/8
            F5/2 A5/2 D6/4 C6/2 A5/2 F5/4
            D6/4 Bb5/4 F5/4 D5/4
            G5/2 Bb5/2 D6/4 C6/2 Bb5/2 G5/4
            A5/4 C#6/4 E6/8
        """,
        harmony = offbeats(adventureChords),
        bass = drivingBass(adventureChords),
        drums = "k.h.s.h.k.h.s.h.",
        leadDuty = 0.25f,
    )

    // ------------------------------------------------------------------ Brave and heroic
    private val heroicChords = listOf(
        "C3 G3 E4 G4",
        "Ab2 Eb3 C4 Eb4",
        "Bb2 F3 D4 F4",
        "C3 G3 E4 G4",
        "F2 C3 A4 C5",
        "Ab2 Eb3 C4 Eb4",
        "Bb2 F3 D4 F4",
        "G2 D3 B3 D4",
    )
    val heroic = Song(
        bpm = 150,
        lead = """
            C5/2 C5/2 G5/4 C6/6 -/2
            Eb6/4 C6/2 Ab5/2 Eb5/8
            D5/2 F5/2 Bb5/4 D6/4 C6/4
            C6/8 G5/8
            F5/2 A5/2 C6/4 F6/4 E6/4
            Eb6/4 C6/4 Ab5/4 C6/4
            D6/2 C6/2 Bb5/2 F5/2 D6/4 F6/4
            G6/8 D6/4 B5/4
        """,
        harmony = offbeats(heroicChords),
        bass = drivingBass(heroicChords),
        drums = "k.h.s.hkk.h.s.h.",
        leadDuty = 0.25f,
    )

    // ------------------------------------------------------------------ Funky and groovy
    private val funkyChords = listOf(
        "E2 B2 G4 B4",
        "E2 B2 G4 B4",
        "A2 E3 C4 E4",
        "B2 F#3 D#4 F#4",
        "E2 B2 G4 B4",
        "D3 A3 F#4 A4",
        "C3 G3 E4 G4",
        "B2 F#3 D#4 F#4",
    )
    val funky = Song(
        bpm = 118,
        lead = """
            E5/2 -/2 E5/1 G5/1 A5/2 -/2 B5/2 A5/2 G5/2
            E5/4 -/2 D5/2 E5/8
            A5/2 -/2 A5/1 B5/1 C6/2 -/2 B5/2 A5/2 E5/2
            F#5/4 D#5/4 B4/8
            E5/2 G5/2 B5/2 E6/2 -/2 D6/2 B5/4
            A5/2 F#5/2 D5/2 F#5/2 A5/8
            G5/2 E5/2 C5/2 E5/2 G5/2 C6/2 B5/4
            F#5/2 A5/2 B5/2 D#6/2 E6/8
        """,
        harmony = offbeats(funkyChords),
        bass = drivingBass(funkyChords),
        drums = "k..hs.hkk.hhs.h.",
        leadDuty = 0.125f,
    )

    // ------------------------------------------------------------------ Floating in space
    private val spaceChords = listOf(
        "A2 E3 C4 E4",
        "F2 C3 A4 C5",
        "C3 G3 E4 G4",
        "G2 D3 B3 D4",
        "A2 E3 C4 E4",
        "F2 C3 A4 C5",
        "D3 A3 F4 A4",
        "E2 B2 G#4 B4",
    )
    val space = Song(
        bpm = 110,
        lead = """
            E5/2 A5/2 C6/2 E6/2 D6/4 C6/4
            A5/8 F5/8
            G5/2 C6/2 E6/2 G6/2 E6/8
            D6/4 B5/4 G5/8
            C6/2 E6/2 A6/4 G6/2 E6/2 C6/4
            F6/4 E6/4 C6/8
            D6/2 F6/2 A6/4 F6/4 D6/4
            E6/8 G#5/8
        """,
        harmony = sustained(spaceChords),
        bass = oomPahBass(spaceChords),
        drums = "k...h...s...h...",
        leadDuty = 0.5f,
    )

    // ------------------------------------------------------------------ Sparkly ice and crystals
    private val crystalChords = listOf(
        "E2 B2 G4 B4",
        "C3 G3 E4 G4",
        "D3 A3 F#4 A4",
        "B2 F#3 D4 F#4",
        "E2 B2 G4 B4",
        "C3 G3 E4 G4",
        "A2 E3 C4 E4",
        "B2 F#3 D#4 F#4",
    )
    val crystal = Song(
        bpm = 132,
        lead = """
            B5/1 G5/1 E5/1 G5/1 B5/2 E6/2 D6/4 B5/4
            C6/2 G5/2 E5/2 G5/2 C6/8
            A5/1 F#5/1 D5/1 F#5/1 A5/2 D6/2 C6/4 A5/4
            B5/8 F#5/8
            E6/2 D6/2 B5/2 G5/2 E5/4 G5/4
            G5/2 C6/2 E6/2 G6/2 E6/8
            C6/2 A5/2 E5/2 A5/2 C6/4 E6/4
            D#6/4 B5/4 F#5/4 B5/4
        """,
        harmony = offbeats(crystalChords),
        bass = oomPahBass(crystalChords),
        drums = "k.h.s.h.k.h.shhh",
        leadDuty = 0.125f,
    )

    // ------------------------------------------------------------------ Sunny beach islands
    private val tropicalChords = listOf(
        "G2 D3 B3 D4",
        "C3 G3 E4 G4",
        "D3 A3 F#4 A4",
        "G2 D3 B3 D4",
        "E2 B2 G4 B4",
        "C3 G3 E4 G4",
        "D3 A3 F#4 A4",
        "G2 D3 B3 D4",
    )
    val tropical = Song(
        bpm = 126,
        lead = """
            D5/2 G5/2 B5/3 A5/1 G5/4 D5/4
            E5/2 G5/2 C6/3 B5/1 A5/4 G5/4
            F#5/2 A5/2 D6/3 C6/1 A5/4 F#5/4
            G5/8 -/4 D5/4
            E5/2 G5/2 B5/3 A5/1 G5/4 E5/4
            C6/3 B5/1 A5/2 G5/2 E5/4 C5/4
            D5/2 F#5/2 A5/2 C6/2 B5/2 A5/2 F#5/4
            G5/8 B5/4 D6/4
        """,
        harmony = offbeats(tropicalChords),
        bass = oomPahBass(tropicalChords),
        drums = "k..sk.h.k..sk.h.",
        leadDuty = 0.25f,
    )

    // ------------------------------------------------------------------ Title: a big happy fanfare
    private val fanfareChords = listOf(
        "C3 G3 E4 G4",
        "F2 C3 A4 C5",
        "G2 D3 B3 D4",
        "C3 G3 E4 G4",
        "A2 E3 C4 E4",
        "F2 C3 A4 C5",
        "D3 A3 F4 A4",
        "G2 D3 B3 D4",
    )
    val fanfare = Song(
        bpm = 152,
        lead = """
            G4/2 C5/2 E5/2 G5/2 C6/6 G5/2
            A5/4 F5/4 C6/4 A5/4
            B5/2 G5/2 D5/2 G5/2 B5/4 D6/4
            C6/8 -/4 G5/4
            A5/2 C6/2 E6/4 C6/2 A5/2 E5/4
            F5/2 A5/2 C6/2 F6/2 E6/4 C6/4
            D6/2 C6/2 A5/2 F5/2 D5/4 F5/4
            G5/2 B5/2 D6/2 G6/2 F6/4 D6/4
        """,
        harmony = offbeats(fanfareChords),
        bass = drivingBass(fanfareChords),
        drums = "k.h.s.h.k.k.s.h.",
        leadDuty = 0.25f,
    )

    // ------------------------------------------------------------------ Title: soft and dreamy
    private val dreamyChords = listOf(
        "C3 G3 E4 G4",
        "E2 B2 G4 B4",
        "A2 E3 C4 E4",
        "F2 C3 A4 C5",
        "C3 G3 E4 G4",
        "E2 B2 G4 B4",
        "F2 C3 A4 C5",
        "G2 D3 B3 D4",
    )
    val dreamy = Song(
        bpm = 100,
        lead = """
            E5/2 G5/2 C6/4 B5/4 G5/4
            B5/2 G5/2 E5/4 G5/8
            A5/2 C6/2 E6/4 D6/4 C6/4
            A5/8 F5/8
            G5/2 C6/2 E6/4 D6/2 C6/2 B5/4
            B5/4 G5/4 E5/8
            F5/2 A5/2 C6/4 F6/4 E6/4
            D6/8 B5/8
        """,
        harmony = sustained(dreamyChords),
        bass = oomPahBass(dreamyChords),
        drums = "k...h...s...h...",
        leadDuty = 0.5f,
    )

    // ------------------------------------------------------------------ Ghost levels: spooky but silly
    private val spookyChords = listOf(
        "E2 B2 G4 B4",
        "E2 B2 G4 B4",
        "C3 G3 E4 G4",
        "B2 F#3 D#4 F#4",
        "E2 B2 G4 B4",
        "A2 E3 C4 E4",
        "C3 G3 E4 G4",
        "B2 F#3 D#4 F#4",
    )
    val spooky = Song(
        bpm = 112,
        lead = """
            E5/3 G5/1 B5/4 A#5/2 A5/2 G5/4
            E5/2 F#5/2 G5/2 B5/2 E6/8
            C6/3 B5/1 G5/4 E5/2 G5/2 C6/4
            B5/4 A5/2 F#5/2 D#5/8
            E6/2 D6/2 B5/2 G5/2 E5/2 G5/2 B5/4
            A5/2 C6/2 E6/4 C6/2 A5/2 E5/4
            G5/2 E5/2 C5/2 E5/2 G5/2 B5/2 C6/4
            B5/8 -/4 B4/4
        """,
        harmony = offbeats(spookyChords),
        bass = oomPahBass(spookyChords),
        drums = "k...s..hk...s.hh",
        leadDuty = 0.125f,
    )

    // ------------------------------------------------------------------ The witch's cackling boss song
    private val witchyChords = listOf(
        "D3 A3 F4 A4",
        "D3 A3 F4 A4",
        "Bb2 F3 D4 F4",
        "A2 E3 C#4 E4",
        "D3 A3 F4 A4",
        "G2 D3 Bb3 D4",
        "Bb2 F3 D4 F4",
        "A2 E3 C#4 E4",
    )
    val witchy = Song(
        bpm = 138,
        lead = """
            D5/2 F5/2 A5/2 D6/2 C#6/2 D6/2 A5/4
            F5/2 E5/2 D5/2 F5/2 A5/8
            Bb5/2 A5/2 F5/2 D5/2 Bb5/4 D6/4
            C#6/4 E6/4 A5/8
            D6/2 C6/2 A5/2 F5/2 D5/2 F5/2 A5/4
            G5/2 Bb5/2 D6/4 C6/2 Bb5/2 G5/4
            F5/2 D5/2 Bb4/2 D5/2 F5/4 Bb5/4
            A5/2 C#6/2 E6/2 A6/2 G6/4 E6/4
        """,
        harmony = offbeats(witchyChords),
        bass = drivingBass(witchyChords),
        drums = "k.hhs.hkk.hhs.hs",
        leadDuty = 0.25f,
    )

    // ------------------------------------------------------------------ Funny scenes: a silly circus tune
    private val sillyChords = listOf(
        "F2 C3 A4 C5",
        "C2 G2 E4 G4",
        "F2 C3 A4 C5",
        "C2 G2 E4 G4",
        "Bb1 F2 D4 F4",
        "F2 C3 A4 C5",
        "C2 G2 E4 G4",
        "F2 C3 A4 C5",
    )
    val silly = Song(
        bpm = 176,
        lead = """
            F5/1 -/1 F5/1 -/1 A5/2 C6/2 F6/4 C6/4
            E5/2 G5/2 C6/2 E6/2 D6/4 -/4
            A5/1 -/1 A5/1 -/1 C6/2 A5/2 F5/4 A5/4
            G5/2 E5/2 C5/2 E5/2 G5/8
            Bb5/2 D6/2 F6/4 D6/2 Bb5/2 F5/4
            A5/2 F5/2 C5/2 F5/2 A5/4 C6/4
            G5/2 Bb5/2 C6/2 E6/2 G6/4 E6/4
            F6/4 C6/4 F5/8
        """,
        harmony = offbeats(sillyChords),
        bass = oomPahBass(sillyChords),
        drums = "k.s.k.s.k.s.k.ss",
        leadDuty = 0.25f,
    )

    // ------------------------------------------------------------------ Fast and racing
    private val raceChords = listOf(
        "A2 E3 C4 E4",
        "F2 C3 A4 C5",
        "G2 D3 B3 D4",
        "E2 B2 G#4 B4",
        "A2 E3 C4 E4",
        "F2 C3 A4 C5",
        "D3 A3 F4 A4",
        "E2 B2 G#4 B4",
    )
    val race = Song(
        bpm = 184,
        lead = """
            A5/1 B5/1 C6/2 E6/2 A6/2 G6/2 E6/2 C6/4
            F5/1 G5/1 A5/2 C6/2 F6/2 E6/2 C6/2 A5/4
            G5/1 A5/1 B5/2 D6/2 G6/2 F6/2 D6/2 B5/4
            G#5/4 B5/4 E6/8
            E6/2 C6/2 A5/2 C6/2 E6/2 A6/2 G6/4
            F6/2 C6/2 A5/2 C6/2 F6/2 A6/2 G6/4
            F6/2 D6/2 A5/2 D6/2 F6/2 A6/2 F6/4
            E6/4 G#6/4 B6/8
        """,
        harmony = offbeats(raceChords),
        bass = drivingBass(raceChords),
        drums = "khs.khs.khs.khss",
        leadDuty = 0.25f,
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
        Music.FROG -> frog
        Music.KRAG -> krag
        Music.WARDEN -> warden
        Music.MEADOW -> meadow
        Music.JUNGLE -> jungle
        Music.SNOW -> snow
        Music.DESERT -> desert
        Music.NIGHT -> night
        Music.BEACH -> beach
        Music.DOOM -> doom
        Music.SHANTY -> shanty
        Music.PARK -> park
        Music.COZY -> cozy
        Music.ADVENTURE -> adventure
        Music.HEROIC -> heroic
        Music.FUNKY -> funky
        Music.SPACE -> space
        Music.CRYSTAL -> crystal
        Music.TROPICAL -> tropical
        Music.FANFARE -> fanfare
        Music.DREAMY -> dreamy
        Music.SPOOKY -> spooky
        Music.WITCHY -> witchy
        Music.SILLY -> silly
        Music.RACE -> race
    }
}
