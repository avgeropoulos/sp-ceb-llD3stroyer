package com.critters.game

import com.roccoquest.game.Chiptune
import com.roccoquest.game.Song
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

enum class Sfx { SELECT, BUMP, DOOR, HIT, HIT_SUPER, HIT_WEAK, THROW, SHAKE, CAUGHT, LEVEL_UP, HEAL, ITEM, EXCLAIM, LEDGE, SAVE, RUN, BADGE, BUY, FAINT }

enum class Tune { NONE, TITLE, TOWN, ROUTE, CITY, FOREST, CAVE, BATTLE, BOSS, VICTORY, CENTER, GYM, ENDING }

/** Where the game sends its sounds. The default does nothing (tests, silent mode). */
interface Audio {
    fun sfx(s: Sfx) {}
    fun music(t: Tune) {}
    fun cry(species: Int) {}

    object Silent : Audio
}

/** Original chiptune songs, in the same note notation as Rocco's Quest. */
object Tunes {
    private fun oomPah(chords: List<String>) = chords.joinToString(" ") {
        val (r, f) = it.split(' ')
        "$r/2 -/2 $f/2 -/2 $r/2 -/2 $f/2 -/2"
    }

    private fun offbeats(chords: List<String>) = chords.joinToString(" ") {
        val p = it.split(' ')
        "-/2 ${p[2]}/2 -/2 ${p[3]}/2 -/2 ${p[2]}/2 -/2 ${p[3]}/2"
    }

    private fun driving(chords: List<String>) = chords.joinToString(" ") {
        val (r, f) = it.split(' ')
        "$r/2 $r/2 $f/2 $r/2 $r/2 $r/2 $f/2 $r/2"
    }

    private fun held(chords: List<String>) = chords.joinToString(" ") {
        val p = it.split(' ')
        "${p[2]}/8 ${p[3]}/8"
    }

    private fun arps(chords: List<String>) = chords.joinToString(" ") {
        val p = it.split(' ')
        "${p[2]}/2 ${p[3]}/2 ${p[2]}/2 ${p[3]}/2 ${p[2]}/2 ${p[3]}/2 ${p[2]}/2 ${p[3]}/2"
    }

    private fun walking(chords: List<String>) = chords.joinToString(" ") {
        val (r, f) = it.split(' ')
        "$r/4 $f/4 $r/4 $f/4"
    }

    private const val C = "C3 G3 E4 G4"
    private const val Dm = "D3 A3 F4 A4"
    private const val F = "F2 C3 A4 C5"
    private const val G = "G2 D3 B4 D5"
    private const val Am = "A2 E3 C4 E4"
    private const val E = "E2 B2 G#4 B4"
    private const val Bb = "Bb2 F3 D4 F4"
    private const val A = "A2 E3 C#4 E4"
    private const val D = "D3 A3 F#4 A4"
    private const val Em = "E2 B2 G4 B4"
    private const val B = "B1 F#2 D#4 F#4"
    private const val Cg = "C3 G3 E4 G4"

    private val titleLead = """
        G4/2 C5/2 E5/2 G5/6 E5/2 G5/2
        A5/4 G5/2 E5/2 C5/4 D5/4
        E5/2 F5/2 G5/4 C6/4 B5/2 A5/2
        G5/12 -/4
        F5/2 G5/2 A5/4 G5/2 F5/2 E5/4
        D5/2 E5/2 F5/4 E5/2 D5/2 C5/4
        D5/2 E5/2 F5/2 A5/2 G5/4 B4/4
        C5/12 -/4
    """
    private val titleChords = listOf(C, F, C, G, F, Dm, G, C)

    val title = Song(150, titleLead, offbeats(titleChords), oomPah(titleChords), "k...s...k.k.s...")
    val ending = Song(100, titleLead, held(titleChords), walking(titleChords), "k.......s.......", 0.5f)

    private val townLead = """
        A4/2 C5/2 F5/4 E5/2 D5/2 C5/4
        D5/2 F5/2 Bb5/4 A5/2 G5/2 F5/4
        G5/2 A5/2 G5/2 F5/2 E5/4 C5/4
        F5/8 -/4 C5/4
        A4/2 C5/2 F5/4 E5/2 D5/2 C5/4
        D5/2 F5/2 Bb5/4 A5/2 G5/2 F5/4
        G5/2 F5/2 E5/2 D5/2 E5/4 G5/4
        F5/12 -/4
    """
    private val townChords = listOf(F, Bb, Cg, F, F, Bb, Cg, F)
    val town = Song(112, townLead, held(townChords), oomPah(townChords), "k...h...s...h...", 0.5f)

    private val routeLead = """
        D5/2 G5/2 B5/2 A5/2 G5/4 D5/4
        E5/2 G5/2 C6/2 B5/2 A5/8
        D5/2 F#5/2 A5/2 G5/2 F#5/4 D5/4
        G5/2 A5/2 B5/2 A5/2 G5/8
        B5/2 C6/2 D6/4 B5/2 G5/2 D5/4
        C6/2 B5/2 A5/4 E5/2 F#5/2 G5/4
        A5/2 B5/2 A5/2 G5/2 F#5/2 E5/2 D5/4
        G5/12 -/4
    """
    private val routeChords = listOf(G, Cg, D, G, G, Am, D, G)
    val route = Song(150, routeLead, offbeats(routeChords), oomPah(routeChords), "k.h.s.h.k.k.s.h.")

    private val cityLead = """
        F#5/3 F#5/1 A5/2 F#5/2 E5/2 D5/2 E5/4
        B4/2 D5/2 G5/4 F#5/2 E5/2 D5/4
        A4/2 C#5/2 E5/4 D5/2 C#5/2 B4/2 C#5/2
        D5/8 A4/4 -/4
        F#5/3 F#5/1 A5/2 F#5/2 E5/2 D5/2 E5/4
        B4/2 D5/2 G5/4 B5/2 A5/2 G5/4
        F#5/2 E5/2 D5/2 C#5/2 E5/4 A4/4
        D5/12 -/4
    """
    private val cityChords = listOf(D, "G2 D3 B3 D4", A, D, D, "G2 D3 B3 D4", A, D)
    val city = Song(132, cityLead, offbeats(cityChords), oomPah(cityChords), "k...s.h.k.h.s...")

    private val forestLead = """
        E5/4 G5/2 B5/2 A5/4 G5/4
        F#5/4 D5/4 E5/8
        C5/4 E5/2 G5/2 F#5/4 E5/4
        D#5/8 B4/8
        E5/4 G5/2 B5/2 C6/4 B5/4
        A5/2 G5/2 F#5/4 G5/8
        C5/2 E5/2 A5/4 G5/2 F#5/2 E5/4
        E5/12 -/4
    """
    private val forestChords = listOf(Em, D, Cg, B, Em, D, Am, Em)
    val forest = Song(108, forestLead, arps(forestChords), walking(forestChords), "k.......k...h...", 0.5f)

    private val caveLead = """
        A4/4 -/2 C5/2 B4/4 -/4
        A4/4 -/2 E5/2 D#5/8
        A4/4 -/2 C5/2 B4/2 A4/2 G#4/4
        A4/8 -/8
        E5/4 -/2 F5/2 E5/4 -/4
        D5/4 -/2 C5/2 B4/8
        C5/2 B4/2 A4/2 G#4/2 B4/4 E4/4
        A4/8 -/8
    """
    private val caveChords = listOf(Am, B, E, Am, Am, Dm, E, Am)
    val cave = Song(96, caveLead, "", walking(caveChords), "k...h...k.h.h...", 0.5f)

    private val battleLead = """
        A5/1 G#5/1 A5/1 G#5/1 A5/2 E5/2 C5/2 E5/2 A4/4
        B4/2 C5/2 D5/2 E5/2 F5/4 E5/4
        D5/2 F5/2 A5/2 F5/2 D5/2 F5/2 A5/2 B5/2
        G#5/4 E5/4 B4/4 E5/4
        A5/1 G#5/1 A5/1 G#5/1 A5/2 E5/2 C5/2 E5/2 A4/4
        C5/2 D5/2 E5/2 F5/2 G5/4 F5/4
        E5/2 F5/2 E5/2 D5/2 C5/2 B4/2 C5/2 D5/2
        E5/8 E4/8
    """
    private val battleChords = listOf(Am, F, Dm, E, Am, Cg, Am, E)
    val battle = Song(168, battleLead, offbeats(battleChords), driving(battleChords), "k.hsk.hsk.hsk.ss")

    private val bossLead = """
        D5/1 -/1 D5/1 -/1 F5/2 D5/2 G5/2 F5/2 E5/2 C#5/2
        D5/4 A4/4 D5/2 -/6
        Bb5/2 A5/2 G5/2 F5/2 G5/2 F5/2 E5/2 D5/2
        C#5/4 E5/4 A5/4 -/4
        D5/1 -/1 D5/1 -/1 F5/2 D5/2 G5/2 F5/2 E5/2 C#5/2
        D5/4 A4/4 D5/2 -/6
        Bb5/2 C6/2 D6/2 C6/2 Bb5/2 A5/2 G5/2 F5/2
        E5/2 C#5/2 A4/2 C#5/2 D5/4 -/4
    """
    private val bossChords = listOf(Dm, Dm, Bb, A, Dm, Dm, Bb, A)
    val boss = Song(176, bossLead, offbeats(bossChords), driving(bossChords), "k.k.s.k.k.k.s.hs")

    private val victoryLead = """
        C5/2 E5/2 G5/2 C6/6 B5/2 C6/2
        D6/4 C6/2 B5/2 A5/4 G5/4
        F5/2 A5/2 C6/2 F6/6 E6/2 D6/2
        C6/8 G5/4 -/4
    """
    private val victoryChords = listOf(Cg, G, F, Cg)
    val victory = Song(140, victoryLead, offbeats(victoryChords), oomPah(victoryChords), "k.h.s.h.k.h.s.h.")

    private val centerLead = """
        B4/2 D5/2 G5/2 D5/2 B4/2 D5/2 G5/4
        C5/2 E5/2 A5/2 E5/2 C5/2 E5/2 A5/4
        B4/2 D5/2 G5/2 B5/2 A5/4 F#5/4
        G5/8 -/8
    """
    private val centerChords = listOf(G, Am, D, G)
    val center = Song(120, centerLead, held(centerChords), oomPah(centerChords), "k...h...s...h...", 0.125f)

    private val gymLead = """
        G4/2 G4/2 C5/4 G4/2 C5/2 E5/4
        F5/2 E5/2 D5/2 C5/2 D5/8
        E5/2 E5/2 F5/4 G5/2 A5/2 G5/4
        F5/2 E5/2 D5/2 B4/2 C5/8
    """
    private val gymChords = listOf(Cg, G, F, Cg)
    val gym = Song(140, gymLead + gymLead, offbeats(gymChords + gymChords), driving(gymChords + gymChords), "k.s.k.s.k.s.k.ss")

    fun of(t: Tune): Song? = when (t) {
        Tune.NONE -> null
        Tune.TITLE -> title
        Tune.TOWN -> town
        Tune.ROUTE -> route
        Tune.CITY -> city
        Tune.FOREST -> forest
        Tune.CAVE -> cave
        Tune.BATTLE -> battle
        Tune.BOSS -> boss
        Tune.VICTORY -> victory
        Tune.CENTER -> center
        Tune.GYM -> gym
        Tune.ENDING -> ending
    }

    fun render(t: Tune): ShortArray? = of(t)?.let { Chiptune.render(it) }
}

/** Synthesizes the sound effects and critter cries as mono samples at [RATE]. */
object Synth {
    const val RATE = Chiptune.RATE

    private class B {
        val out = ArrayList<Float>()

        fun tone(f0: Float, f1: Float, dur: Float, vol: Float = 0.3f, square: Boolean = true, decay: Boolean = true, duty: Float = 0.5f) {
            val n = (RATE * dur).toInt()
            var phase = 0f
            for (i in 0 until n) {
                val t = i.toFloat() / n
                val f = f0 + (f1 - f0) * t
                phase = (phase + f / RATE) % 1f
                val v = if (f <= 0f) 0f else if (square) (if (phase < duty) 1f else -1f) else 4f * abs(phase - 0.5f) - 1f
                val env = if (decay) 1f - t * 0.85f else if (t > 0.85f) (1f - t) / 0.15f else 1f
                out += v * vol * env
            }
        }

        fun notes(freqs: FloatArray, each: Float, vol: Float = 0.25f, last: Float = 0f) {
            freqs.forEachIndexed { i, f ->
                tone(f, f, if (i == freqs.lastIndex && last > 0f) last else each, vol, decay = i == freqs.lastIndex, duty = 0.25f)
            }
        }

        fun noise(dur: Float, vol: Float = 0.35f, smooth: Float = 0f, seed: Int = 7) {
            val r = Random(seed)
            val n = (RATE * dur).toInt()
            var last = 0f
            for (i in 0 until n) {
                last = last * smooth + (r.nextFloat() * 2f - 1f) * (1f - smooth)
                out += last * vol * (1f - i.toFloat() / n)
            }
        }

        fun silence(dur: Float) { repeat((RATE * dur).toInt()) { out += 0f } }
    }

    fun sfx(s: Sfx): FloatArray = B().apply {
        when (s) {
            Sfx.SELECT -> tone(1760f, 1760f, 0.045f, 0.2f, duty = 0.25f)
            Sfx.BUMP -> tone(110f, 80f, 0.09f, 0.35f)
            Sfx.DOOR -> { noise(0.06f, 0.3f, 0.5f); tone(300f, 120f, 0.12f, 0.2f) }
            Sfx.HIT -> { noise(0.12f, 0.45f, 0.2f); tone(180f, 60f, 0.08f, 0.3f) }
            Sfx.HIT_SUPER -> { noise(0.06f, 0.5f, 0.1f); noise(0.18f, 0.5f, 0.3f, 3); tone(260f, 50f, 0.12f, 0.35f) }
            Sfx.HIT_WEAK -> { noise(0.08f, 0.25f, 0.6f); tone(120f, 80f, 0.05f, 0.2f) }
            Sfx.THROW -> tone(400f, 1200f, 0.22f, 0.18f, square = false)
            Sfx.SHAKE -> { tone(220f, 180f, 0.06f, 0.3f); silence(0.05f); tone(200f, 160f, 0.05f, 0.25f) }
            Sfx.CAUGHT -> notes(floatArrayOf(523f, 659f, 784f, 659f, 784f, 1047f), 0.09f, last = 0.4f)
            Sfx.LEVEL_UP -> notes(floatArrayOf(784f, 784f, 784f, 1047f, 0f, 1175f, 1319f), 0.08f, last = 0.35f)
            Sfx.HEAL -> notes(floatArrayOf(659f, 784f, 1047f, 784f, 1047f, 1319f, 1568f), 0.11f, last = 0.45f)
            Sfx.ITEM -> notes(floatArrayOf(784f, 988f, 1175f, 1568f), 0.1f, last = 0.35f)
            Sfx.EXCLAIM -> { tone(1200f, 1200f, 0.05f, 0.25f, decay = false); tone(1600f, 1600f, 0.12f, 0.25f) }
            Sfx.LEDGE -> tone(300f, 600f, 0.1f, 0.25f, square = false)
            Sfx.SAVE -> notes(floatArrayOf(1047f, 1319f, 1568f, 2093f), 0.07f, last = 0.2f)
            Sfx.RUN -> { tone(400f, 900f, 0.08f, 0.2f); tone(600f, 1200f, 0.12f, 0.2f) }
            Sfx.BADGE -> notes(floatArrayOf(523f, 523f, 523f, 659f, 784f, 0f, 659f, 784f, 1047f), 0.1f, last = 0.6f)
            Sfx.BUY -> { tone(1319f, 1319f, 0.05f, 0.2f, decay = false); tone(1760f, 1760f, 0.2f, 0.2f) }
            Sfx.FAINT -> tone(600f, 80f, 0.5f, 0.3f)
        }
    }.out.toFloatArray()

    /** Each species gets its own little cry, built from its number. Evolved forms sound deeper. */
    fun cry(species: Int): FloatArray = B().apply {
        val sp = Dex[species]
        val evolved = Dex.all.any { it.evolveTo == species }
        val base = (380f + (species * 97 % 420)) * if (evolved) 0.7f else 1f
        val r = Random(species * 31 + 5)
        val parts = 2 + r.nextInt(2)
        for (i in 0 until parts) {
            val f0 = base * (0.8f + r.nextFloat() * 0.6f)
            val f1 = f0 * (if (r.nextBoolean()) 1.4f else 0.6f)
            tone(f0, f1, 0.08f + r.nextFloat() * 0.1f, 0.22f, square = sp.id % 3 != 0, duty = if (i % 2 == 0) 0.25f else 0.5f)
        }
        if (Type.ROCK in sp.types || Type.GHOST in sp.types) noise(0.12f, 0.2f, 0.7f, species)
        // A touch of vibrato on the tail of the cry.
        val n = (RATE * 0.12f).toInt()
        val f = base * 0.9f
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            out += sin(2f * PI.toFloat() * f * t * (1f + 0.03f * sin(40f * t))) * 0.18f * (1f - i.toFloat() / n)
        }
    }.out.toFloatArray()
}
