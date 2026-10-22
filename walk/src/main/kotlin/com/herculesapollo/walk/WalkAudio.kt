package com.herculesapollo.walk

import com.roccoquest.game.SfxBuilder
import com.roccoquest.game.Song

enum class WalkSound { GAV, ARP, CATCH, TREAT, JUMP, BELL, OUCH, HISS, SKUNK, WHOA, TEAM, HOME }

/** DAY is the walk with both dogs; HERC and APOLLO are each dog's own stroll when you play as them. */
enum class WalkTune { NONE, DAY, NIGHT, HERC, APOLLO }

/** Where the walk sends its sounds; the platform layer plays them. */
fun interface WalkAudio {
    fun play(s: WalkSound)

    /** Switches the looping background track; [WalkTune.NONE] silences it. */
    fun music(t: WalkTune) {}

    /** Speaks a line out loud where the platform supports it. */
    fun say(text: String) {}
}

/** Synthesizes the walk's sound effects as mono float samples at Chiptune.RATE. */
object WalkSynth {
    fun render(s: WalkSound): FloatArray = SfxBuilder().apply {
        when (s) {
            // Hercules says "GAV GAV!" (a gruff double bark); Apollo says "ARP!" (one bright, quick bark).
            WalkSound.GAV -> {
                noise(0.025f, 0.35f, 0.5f); tone(330f, 180f, 0.14f, 0.42f)
                tone(0f, 0f, 0.06f)
                noise(0.025f, 0.3f, 0.5f); tone(300f, 160f, 0.16f, 0.38f)
            }
            WalkSound.ARP -> { noise(0.02f, 0.3f, 0.3f); tone(620f, 880f, 0.04f, 0.32f, decay = false); tone(880f, 480f, 0.11f, 0.32f) }
            WalkSound.CATCH -> notes(floatArrayOf(660f, 880f, 1320f), 0.05f, 0.28f, lastHold = 0.12f)
            WalkSound.TREAT -> { tone(1175f, 1175f, 0.05f, decay = false); tone(1568f, 1568f, 0.18f) }
            WalkSound.JUMP -> tone(300f, 560f, 0.12f, 0.25f)
            WalkSound.BELL -> {
                tone(2093f, 2093f, 0.12f, 0.18f, square = false); tone(2093f, 2093f, 0.2f, 0.18f, square = false)
            }
            WalkSound.OUCH -> { noise(0.06f, 0.45f, 0.3f); tone(500f, 160f, 0.3f, 0.35f) }
            WalkSound.HISS -> noise(0.4f, 0.3f, 0.05f)
            WalkSound.SKUNK -> { noise(0.5f, 0.4f, 0.9f); tone(140f, 70f, 0.35f, 0.3f) }
            WalkSound.WHOA -> tone(620f, 260f, 0.25f, 0.25f, square = false)
            WalkSound.TEAM -> notes(floatArrayOf(784f, 988f, 1175f, 1568f), 0.06f, lastHold = 0.2f)
            WalkSound.HOME -> notes(
                floatArrayOf(523f, 659f, 784f, 659f, 784f, 1047f, 0f, 988f, 1047f), 0.12f, 0.28f, lastHold = 0.7f,
            )
        }
    }.samples.toFloatArray()
}

/** Original looping tunes: a strolling daytime song and a hushed night-time one. */
object WalkSongs {
    private val day = Song(
        bpm = 132,
        lead = """
            C5/2 E5/2 G5/2 E5/2 F5/2 A5/2 G5/4
            E5/2 G5/2 C6/2 G5/2 A5/2 G5/2 E5/4
            F5/2 E5/2 D5/2 E5/2 F5/2 A5/2 G5/2 F5/2
            E5/2 D5/2 C5/2 D5/2 E5/4 C5/4
            A4/2 C5/2 F5/2 C5/2 A4/2 C5/2 F5/4
            G4/2 B4/2 D5/2 G5/2 F5/2 D5/2 B4/4
            C5/2 E5/2 G5/2 C6/2 B5/2 G5/2 A5/2 B5/2
            C6/4 G5/2 E5/2 C5/8
        """,
        harmony = """
            -/2 E4/2 -/2 G4/2 -/2 A4/2 -/2 G4/2
            -/2 E4/2 -/2 G4/2 -/2 C5/2 -/2 G4/2
            -/2 A4/2 -/2 C5/2 -/2 B4/2 -/2 D5/2
            -/2 G4/2 -/2 E4/2 -/2 G4/2 -/2 E4/2
            -/2 A4/2 -/2 C5/2 -/2 A4/2 -/2 C5/2
            -/2 B4/2 -/2 D5/2 -/2 B4/2 -/2 G4/2
            -/2 E4/2 -/2 G4/2 -/2 E4/2 -/2 G4/2
            -/2 E4/2 -/2 G4/2 -/8
        """,
        bass = """
            C3/4 G3/4 C3/4 G3/4
            C3/4 G3/4 A2/4 G2/4
            F2/4 C3/4 G2/4 D3/4
            C3/4 G2/4 C3/4 C3/4
            F2/4 C3/4 F2/4 C3/4
            G2/4 D3/4 G2/4 B2/4
            C3/4 G3/4 E3/4 G3/4
            C3/4 G2/4 C3/8
        """,
        drums = "k.h.s.h.k.k.s.h.",
        leadDuty = 0.5f,
    )

    private val night = Song(
        bpm = 100,
        lead = """
            A4/4 C5/2 E5/2 D5/4 C5/4
            B4/4 D5/2 G5/2 E5/8
            F5/4 E5/2 D5/2 C5/4 B4/4
            A4/4 B4/2 C5/2 A4/8
            E5/4 A5/2 G5/2 E5/4 D5/4
            C5/4 D5/2 E5/2 G4/8
            F4/2 A4/2 C5/2 F5/2 E5/4 D5/4
            C5/4 B4/2 G#4/2 A4/8
        """,
        harmony = "",
        bass = """
            A2/8 E3/8
            G2/8 E2/8
            F2/8 G2/8
            A2/8 E2/4 A2/4
            A2/8 E3/8
            C3/8 G2/8
            F2/8 D3/8
            E2/8 A2/8
        """,
        drums = "k...h...s...h...",
    )

    /** Hercules' stroll: the same bouncy style, a bit lower and struttier, in F. */
    private val herc = Song(
        bpm = 124,
        lead = """
            F4/2 A4/2 C5/2 A4/2 Bb4/2 D5/2 C5/4
            A4/2 C5/2 F5/2 C5/2 D5/2 C5/2 A4/4
            Bb4/2 A4/2 G4/2 A4/2 Bb4/2 D5/2 C5/2 Bb4/2
            A4/2 G4/2 F4/2 G4/2 A4/4 F4/4
            D5/2 F5/2 D5/2 Bb4/2 C5/2 E5/2 G5/4
            F5/2 E5/2 D5/2 C5/2 Bb4/2 A4/2 G4/4
            F4/2 A4/2 C5/2 F5/2 E5/2 C5/2 D5/2 E5/2
            F5/4 C5/2 A4/2 F4/8
        """,
        harmony = """
            -/2 A3/2 -/2 C4/2 -/2 D4/2 -/2 C4/2
            -/2 A3/2 -/2 C4/2 -/2 F4/2 -/2 C4/2
            -/2 D4/2 -/2 F4/2 -/2 E4/2 -/2 G4/2
            -/2 C4/2 -/2 A3/2 -/2 C4/2 -/2 A3/2
            -/2 D4/2 -/2 F4/2 -/2 E4/2 -/2 G4/2
            -/2 D4/2 -/2 F4/2 -/2 C4/2 -/2 E4/2
            -/2 A3/2 -/2 C4/2 -/2 A3/2 -/2 C4/2
            -/2 A3/2 -/2 C4/2 -/8
        """,
        bass = """
            F2/4 C3/4 F2/4 C3/4
            F2/4 C3/4 D3/4 C3/4
            Bb2/4 F2/4 C3/4 G2/4
            F2/4 C3/4 F2/4 F2/4
            Bb2/4 F3/4 C3/4 G3/4
            Bb2/4 F3/4 C3/4 E3/4
            F2/4 C3/4 A2/4 C3/4
            F2/4 C3/4 F2/8
        """,
        drums = "k...s.h.k.k.s.h.",
        leadDuty = 0.5f,
    )

    /** Apollo's stroll: the same bouncy style, quicker and brighter, in G. */
    private val apollo = Song(
        bpm = 150,
        lead = """
            G5/2 D5/2 B4/2 D5/2 G5/2 A5/2 B5/4
            A5/2 G5/2 E5/2 G5/2 D5/4 B4/4
            C5/2 E5/2 A5/2 G5/2 F#5/2 A5/2 D5/4
            G5/2 F#5/2 E5/2 D5/2 B4/4 G4/4
            E5/2 G5/2 B5/2 G5/2 C6/2 B5/2 A5/4
            D5/2 F#5/2 A5/2 F#5/2 B5/2 A5/2 G5/4
            E5/2 F#5/2 G5/2 A5/2 B5/2 A5/2 G5/2 F#5/2
            G5/4 D5/2 B4/2 G4/8
        """,
        harmony = """
            -/2 B3/2 -/2 D4/2 -/2 B3/2 -/2 D4/2
            -/2 C4/2 -/2 E4/2 -/2 B3/2 -/2 D4/2
            -/2 C4/2 -/2 E4/2 -/2 A3/2 -/2 D4/2
            -/2 B3/2 -/2 D4/2 -/2 B3/2 -/2 D4/2
            -/2 C4/2 -/2 E4/2 -/2 C4/2 -/2 E4/2
            -/2 D4/2 -/2 F#4/2 -/2 B3/2 -/2 D4/2
            -/2 C4/2 -/2 E4/2 -/2 D4/2 -/2 F#4/2
            -/2 B3/2 -/2 D4/2 -/8
        """,
        bass = """
            G2/4 D3/4 G2/4 D3/4
            C3/4 G2/4 G2/4 D3/4
            A2/4 E3/4 D3/4 A2/4
            G2/4 D3/4 G2/4 G2/4
            C3/4 G3/4 C3/4 G3/4
            D3/4 A2/4 G2/4 B2/4
            C3/4 E3/4 D3/4 F#3/4
            G2/4 D3/4 G2/8
        """,
        drums = "k.h.s.hhk.h.s.hh",
        leadDuty = 0.25f,
    )

    fun of(t: WalkTune): Song? = when (t) {
        WalkTune.NONE -> null
        WalkTune.DAY -> day
        WalkTune.NIGHT -> night
        WalkTune.HERC -> herc
        WalkTune.APOLLO -> apollo
    }
}
