package com.mixdeck.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A style to borrow a "twist" from: a genre, era or scene with its usual tempo and energy, and the
 * searches that find its songs. [name] is what the DJ reads ("Twist: 70s disco at the same 118 BPM").
 */
data class TwistBucket(
    val id: String,
    val name: String,
    /** The style's usual tempo range at normal speed. */
    val minBpm: Double,
    val maxBpm: Double,
    /** Its usual energy on MixDeck's 1..10 scale. */
    val minEnergy: Float,
    val maxEnergy: Float,
    /** Searches that find its songs (one is picked per run, so runs vary). */
    val queries: List<String>,
    /** Words in a song's title or artist that say it's already this style (lower case). */
    val words: List<String> = emptyList(),
)

/** A style chosen for the playing song: how its tempo lines up (0 same, +1 double, −1 half) and how far off its range is. */
data class TwistPlan(val bucket: TwistBucket, val halfDouble: Int, val tempoOff: Double, val energyOff: Double)

/**
 * "Twists": songs from a different genre, era or scene that still mix well with the playing song —
 * a tempo that syncs (the same, or half/double time), a key next door on the Camelot wheel, and a
 * similar energy. A 2000s hip-hop song at 107 BPM can go to a funk record or an Afrobeats song at
 * the same tempo; at 87 BPM, to drum & bass at double time.
 *
 * Finding them works in two steps:
 *  1. [plan] picks a few styles whose usual tempo (or half/double) and energy are near the playing
 *     song's, leaving out the style the song itself belongs to. Media code searches YouTube for songs
 *     in those styles, keeping only artists outside the playing song's scene.
 *  2. Once a candidate is downloaded and analysed, [fits] checks it with [NextTrack]: only songs
 *     that really sync, sit in a neighbouring key and keep the energy become twists, with a short
 *     [reason] the DJ sees.
 */
object Twists {
    /** Most tempo change SYNC may need for a twist (6% ≈ one semitone on vinyl-style decks). */
    const val MAX_TEMPO = 0.06

    /** Most energy difference (1..10 scale) for a twist. */
    const val MAX_ENERGY = 2f

    /** Keys trusted below this confidence don't count as "in a neighbouring key". */
    const val KEY_TRUST = 0.3f

    /** A style may sit this far (as a fraction) from the playing tempo and still be tried. */
    const val PLAN_TEMPO = 0.05

    /** … and this many energy levels away. */
    const val PLAN_ENERGY = 2.5

    /** Extra cost for styles that only line up at half/double time: a groove change. */
    private const val HALF_DOUBLE_COST = 0.02

    private fun b(
        id: String, name: String, bpm: ClosedFloatingPointRange<Double>, energy: ClosedFloatingPointRange<Float>,
        queries: List<String>, words: List<String> = emptyList(),
    ) = TwistBucket(id, name, bpm.start, bpm.endInclusive, energy.start, energy.endInclusive, queries, words)

    /** The styles twists come from. Tempo and energy are typical, not strict: [fits] checks the real song. */
    val BUCKETS: List<TwistBucket> = listOf(
        b("disco", "70s disco", 110.0..128.0, 6f..8f, listOf("70s disco classics", "disco hits"), listOf("disco")),
        b("funk", "70s funk", 95.0..116.0, 5f..8f, listOf("70s funk classics", "funk classics"), listOf("funk")),
        b("soul", "60s soul", 95.0..130.0, 4f..7f, listOf("motown classics", "60s soul hits"), listOf("motown", "soul")),
        b("boogie", "80s boogie", 105.0..122.0, 5f..7f, listOf("80s boogie funk", "80s funk hits"), listOf("boogie")),
        b("synthpop", "80s synth-pop", 110.0..132.0, 5f..7f, listOf("80s synth pop hits", "80s new wave hits"), listOf("synth", "new wave")),
        b("italo", "Italo disco", 115.0..128.0, 6f..8f, listOf("italo disco classics"), listOf("italo")),
        b("newjack", "new jack swing", 100.0..116.0, 6f..8f, listOf("new jack swing hits"), listOf("new jack")),
        b("boombap", "90s hip-hop", 84.0..98.0, 5f..7f, listOf("90s hip hop classics", "golden era hip hop"), listOf("hip hop", "rap")),
        b("rnb", "2000s R&B", 85.0..105.0, 5f..7f, listOf("2000s r&b hits"), listOf("r&b", "rnb")),
        b("house", "90s house", 120.0..128.0, 6f..9f, listOf("90s house classics", "classic house anthems"), listOf("house")),
        b("deephouse", "deep house", 118.0..124.0, 5f..7f, listOf("deep house vocal", "deep house classics"), listOf("deep house")),
        b("nudisco", "nu-disco", 115.0..124.0, 6f..8f, listOf("nu disco", "french house classics"), listOf("nu disco", "french house")),
        b("garage", "UK garage", 128.0..136.0, 6f..8f, listOf("uk garage classics", "2 step garage"), listOf("garage", "2 step")),
        b("dnb", "drum & bass", 168.0..178.0, 7f..9f, listOf("liquid drum and bass", "drum and bass anthems"), listOf("drum and bass", "dnb", "jungle")),
        b("techno", "techno", 124.0..138.0, 7f..10f, listOf("melodic techno", "techno classics"), listOf("techno")),
        b("bigbeat", "big beat", 120.0..138.0, 7f..9f, listOf("big beat classics"), listOf("big beat", "breakbeat")),
        b("afrobeats", "Afrobeats", 98.0..118.0, 5f..8f, listOf("afrobeats hits", "naija hits"), listOf("afrobeat", "afro", "naija")),
        b("amapiano", "amapiano", 108.0..116.0, 5f..7f, listOf("amapiano hits"), listOf("amapiano")),
        b("reggaeton", "reggaeton", 88.0..100.0, 6f..8f, listOf("reggaeton classics", "reggaeton hits"), listOf("reggaeton", "perreo")),
        b("dancehall", "dancehall", 90.0..105.0, 6f..8f, listOf("dancehall hits", "dancehall classics"), listOf("dancehall")),
        b("reggae", "roots reggae", 70.0..85.0, 3f..6f, listOf("roots reggae classics"), listOf("reggae")),
        b("moombahton", "moombahton", 104.0..112.0, 6f..8f, listOf("moombahton"), listOf("moombah")),
        b("cumbia", "cumbia", 85.0..100.0, 5f..7f, listOf("cumbia clasica", "cumbia hits"), listOf("cumbia")),
        b("baile", "baile funk", 125.0..135.0, 7f..9f, listOf("baile funk", "funk brasileiro"), listOf("baile", "funk carioca")),
        b("electroswing", "electro swing", 120.0..130.0, 6f..8f, listOf("electro swing"), listOf("swing")),
        b("indiedance", "indie dance", 115.0..130.0, 6f..8f, listOf("indie dance classics", "2000s indie rock hits"), listOf("indie")),
        b("kpop", "K-pop", 100.0..130.0, 6f..8f, listOf("k-pop hits"), listOf("k-pop", "kpop")),
        b("acidjazz", "acid jazz", 95.0..115.0, 4f..6f, listOf("acid jazz classics"), listOf("jazz")),
    )

    fun bucket(id: String): TwistBucket? = BUCKETS.firstOrNull { it.id == id }

    /**
     * How a style's tempo lines up with [bpm] (the playing tempo): (half/double, how far outside its
     * range as a fraction, 0 = inside). The same tempo wins over half/double unless clearly better.
     */
    fun tempoMatch(bpm: Double, bucket: TwistBucket): Pair<Int, Double>? {
        if (!(bpm > 0) || !bpm.isFinite()) return null
        return listOf(0, 1, -1).map { hd ->
            val x = when (hd) { 1 -> bpm * 2; -1 -> bpm / 2; else -> bpm }
            val off = when {
                x < bucket.minBpm -> (bucket.minBpm - x) / bucket.minBpm
                x > bucket.maxBpm -> (x - bucket.maxBpm) / bucket.maxBpm
                else -> 0.0
            }
            hd to off
        }.minByOrNull { (hd, off) -> off + if (hd != 0) HALF_DOUBLE_COST else 0.0 }
    }

    /** Energy levels outside the style's usual range (0 inside; 0 when unknown). */
    fun energyOff(energy: Float, bucket: TwistBucket): Double = when {
        energy.isNaN() -> 0.0
        energy < bucket.minEnergy -> (bucket.minEnergy - energy).toDouble()
        energy > bucket.maxEnergy -> (energy - bucket.maxEnergy).toDouble()
        else -> 0.0
    }

    /** Styles a song already belongs to, going by words in its title and artist ("… (Deep House Remix)"). */
    fun sceneOf(text: String): Set<String> {
        val low = " " + text.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}&]+"""), " ") + " "
        return BUCKETS.filter { b -> b.words.any { w -> " $w " in low } }.map { it.id }.toSet()
    }

    /**
     * Up to [count] styles to look for twists in, for a song playing at [bpm] with [energy]: styles
     * whose usual tempo (or half/double) is within [PLAN_TEMPO] and energy within [PLAN_ENERGY].
     * [exclude] (style ids) are left out: the playing song's own style and ones tried already.
     * Closer styles are likelier, but [random] varies the choice so each run brings other styles.
     */
    fun plan(bpm: Double, energy: Float, exclude: Set<String> = emptySet(), count: Int = 4, random: Random = Random.Default): List<TwistPlan> {
        val pool = BUCKETS.filter { it.id !in exclude }.mapNotNull { b ->
            val (hd, off) = tempoMatch(bpm, b) ?: return@mapNotNull null
            val e = energyOff(energy, b)
            if (off > PLAN_TEMPO || e > PLAN_ENERGY) null else TwistPlan(b, hd, off, e)
        }.toMutableList()
        val out = ArrayList<TwistPlan>()
        while (out.size < count && pool.isNotEmpty()) {
            val w = pool.map { p -> exp(-(p.tempoOff * 40 + (if (p.halfDouble != 0) 0.6 else 0.0) + p.energyOff * 0.5)) }
            var r = random.nextDouble() * w.sum()
            var i = 0
            while (i < pool.size - 1 && r >= w[i]) { r -= w[i]; i++ }
            out += pool.removeAt(i)
        }
        return out
    }

    /** True when keys [a] and [b] are neighbours on the Camelot wheel: the same, its relative (8A/8B), or one step (8A/9A). */
    fun camelotNeighbours(a: MusicalKey, b: MusicalKey): Boolean {
        val d = Math.floorMod(a.camelotNumber - b.camelotNumber, 12).let { minOf(it, 12 - it) }
        return d == 0 || (d == 1 && a.minor == b.minor)
    }

    /** Why a scored candidate is not a twist ("tempo", "key", "energy", "fit"), or null when it is one. */
    fun misfit(s: Suggestion, now: TrackProfile): String? {
        val c = s.track
        if (!(c.bpm > 0) || !(now.bpm > 0) || abs(s.pitchToSync) > MAX_TEMPO) return "tempo"
        val from = s.keyFrom
        val to = s.keyTo
        val trusted = now.keyConfidence >= KEY_TRUST && c.keyConfidence >= KEY_TRUST
        if (from != null && to != null && trusted) {
            if (!s.keyInTune || !camelotNeighbours(from, to)) return "key"
        } else if (s.stars < 4) return "key" // key unknown: only a strong all-round fit will do
        if (!now.energy.isNaN() && !c.energy.isNaN() && abs(now.energy - c.energy) > MAX_ENERGY) return "energy"
        if (s.stars < 3) return "fit"
        return null
    }

    /** True when a scored candidate mixes well enough to be offered as a twist. */
    fun fits(s: Suggestion, now: TrackProfile): Boolean = misfit(s, now) == null

    /**
     * The line the DJ sees, e.g. "Twist: 70s disco at the same 107 BPM, 8A → 9A",
     * "Twist: drum & bass at double time (87 → 174 BPM), same key 8A". [playingBpm] is the playing
     * song's tempo as it plays now (its deck's tempo change included).
     */
    fun reason(bucket: TwistBucket, s: Suggestion, playingBpm: Double): String {
        val bpm = playingBpm.roundToInt()
        val tempo = when {
            !(playingBpm > 0) || !(s.track.bpm > 0) -> null
            s.halfDouble > 0 -> "at double time ($bpm → ${bpm * 2} BPM)"
            s.halfDouble < 0 -> "at half time ($bpm → ${(playingBpm / 2).roundToInt()} BPM)"
            abs(s.pitchToSync) < 0.02 -> "at the same $bpm BPM"
            else -> "at $bpm BPM (${pct(s.pitchToSync)})"
        }
        val from = s.keyFrom
        val to = s.keyTo
        val key = when {
            from == null || to == null || !s.keyInTune -> null
            from == to -> "same key ${to.camelot}"
            else -> "${from.camelot} → ${to.camelot}"
        }
        return "Twist: " + bucket.name + (tempo?.let { " $it" } ?: "") + (key?.let { ", $it" } ?: "")
    }

    private fun pct(f: Double): String {
        val v = (f * 100 * 10).roundToInt() / 10.0
        val s = if (v == v.toLong().toDouble()) "${abs(v).toLong()}" else String.format(Locale.ROOT, "%.1f", abs(v))
        return (if (v < 0) "−" else "+") + s + "%"
    }
}
