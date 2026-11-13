package com.mixdeck.engine

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the next song should do to the room: keep the vibe, build energy, or cool down. */
enum class Direction { KEEP, UP, DOWN }

/**
 * The song on the deck that is playing now. [pitch] is that deck's tempo change as a fraction
 * (0.03 = +3%). The decks have no key lock, so it moves the song's pitch too (+6% ≈ +1 semitone).
 */
data class NowPlaying(val track: TrackProfile, val pitch: Double)

/** One plain-English line about a suggestion; [good] = a point in its favour. */
data class Reason(val text: String, val good: Boolean)

data class Suggestion(
    val track: TrackProfile,
    /** 0..100. */
    val score: Float,
    /** 1..5. */
    val stars: Int,
    /** Tempo change the new deck needs for SYNC, as a fraction (after the half/double-time choice). */
    val pitchToSync: Double,
    /** 0 = normal, +1 = matched at double time, -1 = matched at half time. */
    val halfDouble: Int,
    /** The candidate's sounding key once synced; null if unknown or it falls between keys. */
    val keyAfterSync: MusicalKey?,
    /** Short label: "same key", "+1", "−1", "relative", "+2 (energy boost)", "+7 (semitone lift)", "clash", … */
    val camelotMove: String?,
    val reasons: List<Reason>,
    /** One short line, e.g. "Perfect key match · +2.1% tempo · a little more energy". */
    val summary: String,
    /** The playing song's key as heard, and this song's key as it will sound next to it (null if unknown). */
    val keyFrom: MusicalKey? = null,
    val keyTo: MusicalKey? = null,
    /** True when the two will be in tune with each other (not between keys). */
    val keyInTune: Boolean = false,
)

/**
 * Suggests what to mix next so it makes musical sense: the keys fit once the new song is synced,
 * the tempo change is small enough not to mangle voices, and the energy goes where the DJ wants.
 *
 * The score is a weighted sum of four fits, each 0..1 (0.5 = "don't know"):
 *
 *     KEEP:    score = 45·key + 30·tempo + 15·energy + 10·timbre
 *     UP/DOWN: score = 40·key + 25·tempo + 25·energy + 10·timbre
 *
 * then capped for tempo stretches no key match can rescue, and turned into 1..5 stars.
 *
 * **Key.** The decks are vinyl-style, so SYNC moves the new song's pitch. Once both decks run at
 * the same tempo their pitch gap is 12·log2(bpmNow / bpmNew) semitones (plus whole octaves for
 * half/double time), whatever the playing deck's slider says. The gap is read as a whole number
 * of semitones (which transposes the new song's key) plus a leftover detune. The transposed pair
 * is rated on the Camelot wheel ([relation]); the detune removes harmonic credit
 * ([DETUNE_X]/[DETUNE_Y]); low key confidence shrinks the result towards 0.5.
 *
 * **Tempo.** Penalises the tempo change SYNC needs ([TEMPO_X]/[TEMPO_Y]): with no key lock, 6%
 * is a whole semitone and voices start to sound wrong; 10%+ is chipmunk / slow-motion territory.
 *
 * **Energy.** 1..10 levels; KEEP wants the same level, UP wants +1 (+2 is fine), DOWN wants −1.
 *
 * **Timbre.** Cosine similarity of the opaque sound vectors ("sounds like the same kind of music").
 */
object NextTrack {

    // ------------------------------------------------------------------ weights

    /** Key, tempo, energy, timbre weights when keeping the vibe (sum 100). */
    private val WEIGHTS_KEEP = doubleArrayOf(45.0, 30.0, 15.0, 10.0)

    /** … and when the DJ asked to build up or cool down: energy becomes a main goal. */
    private val WEIGHTS_MOVE = doubleArrayOf(40.0, 25.0, 25.0, 10.0)

    // ------------------------------------------------------------------ key

    /** Key confidence at or above this is trusted fully … */
    const val KEY_CONF_FULL = 0.6

    /** … and at or below this not at all (the key fit becomes the neutral 0.5). */
    const val KEY_CONF_NONE = 0.15

    /** Below this trust the reasons say the key is uncertain. */
    private const val KEY_TRUST_WARN = 0.6

    /**
     * Two songs whose pitch gap is within this many semitones of a whole number count as in tune
     * with each other: the tolerance [MusicalKey.wholeSemitones] uses.
     */
    const val IN_TUNE = 0.25

    /** Fit of a clash; detuning never makes a pair worse than this. */
    private const val CLASH_FLOOR = 0.03

    /**
     * Detune between the two songs (semitones) → share of the harmonic credit kept. ≤10 cents is
     * inaudible; up to 25 cents still counts as "in key"; 25–42 cents sounds sour on held notes;
     * 50 cents is a quarter tone, right between two keys; past that the other reading is nearer.
     */
    private val DETUNE_X = doubleArrayOf(0.0, 0.10, 0.25, 0.35, 0.42, 0.50, 0.60, 0.75)
    private val DETUNE_Y = doubleArrayOf(1.0, 1.00, 0.85, 0.70, 0.50, 0.30, 0.15, 0.0)

    // ------------------------------------------------------------------ tempo

    /** |tempo change| in % → fit. */
    private val TEMPO_X = doubleArrayOf(0.0, 1.0, 3.0, 6.0, 10.0, 16.0)
    private val TEMPO_Y = doubleArrayOf(1.0, 1.0, 0.92, 0.62, 0.25, 0.0)

    /** Tempo fit when SYNC is impossible (no steady beat on one side). */
    private const val TEMPO_NO_SYNC = 0.25

    /** Half/double time changes the groove's feel: costs a little unless that's the plan (UP/DOWN). */
    private const val HALF_DOUBLE_FEEL = 0.9

    /** |tempo change| in % → the most a suggestion can score: no key match rescues a huge stretch. */
    private val CAP_X = doubleArrayOf(8.0, 12.0, 16.0, 20.0)
    private val CAP_Y = doubleArrayOf(100.0, 60.0, 40.0, 35.0)

    /** Most a suggestion can score when it can't be beat-synced. */
    private const val CAP_NO_SYNC = 60.0

    // ------------------------------------------------------------------ energy

    /** KEEP: |energy change| → fit. */
    private val KEEP_X = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 5.0)
    private val KEEP_Y = doubleArrayOf(1.0, 0.85, 0.5, 0.25, 0.0)

    /** UP: energy change → fit (DOWN uses the mirror image). Best is +1, as Mixed In Key advises. */
    private val UP_X = doubleArrayOf(-3.0, -1.0, 0.0, 1.0, 2.0, 3.0, 4.0, 6.0)
    private val UP_Y = doubleArrayOf(0.0, 0.2, 0.55, 1.0, 0.9, 0.6, 0.35, 0.1)

    /** Extra energy fit for double time when building up (or half time when cooling down). */
    private const val HALF_DOUBLE_ENERGY_BONUS = 0.1

    // ------------------------------------------------------------------ timbre

    /** Cosine similarity at or below this = fit 0, at or above [TIMBRE_COS_HIGH] = fit 1. */
    const val TIMBRE_COS_LOW = 0.5
    const val TIMBRE_COS_HIGH = 0.95

    /** Minimum score for 5, 4, 3 and 2 stars. */
    private val STAR_MIN = doubleArrayOf(85.0, 74.0, 60.0, 45.0)

    // ================================================================== public API

    /** Rates one candidate as the next song after [now]. */
    fun score(now: NowPlaying, candidate: TrackProfile, direction: Direction = Direction.KEEP): Suggestion {
        val nowTrack = now.track
        val nowPitch = if (now.pitch.isFinite() && now.pitch > -1) now.pitch else 0.0
        val sync = sync(nowTrack.bpm, nowPitch, candidate.bpm)
        val nowShift = MusicalKey.semitonesForSpeed(1 + nowPitch)
        val candShift = if (sync.known) MusicalKey.semitonesForSpeed(sync.speed) else 0.0
        val reasons = ArrayList<Reason>()

        val key = keyPart(nowTrack, candidate, nowPitch, nowShift, candShift, sync, direction, reasons)
        val keyAfterSync = candidate.key?.let { k -> MusicalKey.wholeSemitones(candShift)?.let { k.transpose(it) } }

        val pct = abs(sync.speed - 1) * 100
        var tempoFit = if (sync.known) interp(pct, TEMPO_X, TEMPO_Y) else TEMPO_NO_SYNC
        if (sync.halfDouble != 0 && !halfDoubleSuits(sync.halfDouble, direction)) tempoFit *= HALF_DOUBLE_FEEL
        tempoReasons(nowTrack, nowPitch, candidate, sync, direction, reasons)

        val energyFit = energyFit(nowTrack.energy, candidate.energy, direction, sync.halfDouble)
        energyReasons(nowTrack.energy, candidate.energy, direction, reasons)

        val cos = cosine(nowTrack.timbre, candidate.timbre)
        val timbreFit = cos?.let { ((it - TIMBRE_COS_LOW) / (TIMBRE_COS_HIGH - TIMBRE_COS_LOW)).coerceIn(0.0, 1.0) }
        if (cos != null) {
            if (cos >= 0.9) reasons += Reason("Similar sound and production — keeps the same vibe.", true)
            else if (cos < 0.7) reasons += Reason("Quite a different sound — a change of style.", false)
        }

        val w = if (direction == Direction.KEEP) WEIGHTS_KEEP else WEIGHTS_MOVE
        var total = w[0] * key.fit + w[1] * tempoFit + w[2] * (energyFit ?: 0.5) + w[3] * (timbreFit ?: 0.5)
        val cap = when {
            !sync.known -> CAP_NO_SYNC
            pct > CAP_X[0] -> interp(pct, CAP_X, CAP_Y)
            else -> 100.0
        }
        total = min(total, cap).coerceIn(0.0, 100.0)
        val score = ((total * 10).roundToInt() / 10.0).toFloat()

        val summary = listOfNotNull(
            key.phrase,
            tempoPhrase(sync),
            energyPhrase(nowTrack.energy, candidate.energy),
            if (cos != null && cos >= 0.9) "similar sound" else null,
        ).joinToString(" · ")

        return Suggestion(
            track = candidate,
            score = score,
            stars = stars(score.toDouble()),
            pitchToSync = if (sync.known) sync.speed - 1 else 0.0,
            halfDouble = sync.halfDouble,
            keyAfterSync = keyAfterSync,
            camelotMove = key.label,
            reasons = glossSemitone(reasons),
            summary = summary,
            keyFrom = key.from,
            keyTo = key.to,
            keyInTune = key.inTune,
        )
    }

    /**
     * The best next songs, best first. Leaves out the playing song, anything in [recentlyPlayed]
     * (track ids; free text such as "Artist - Title" works too) and other uploads of those songs,
     * and shows only the best-scoring upload when the library has the same song more than once.
     */
    fun rank(
        now: NowPlaying,
        candidates: List<TrackProfile>,
        direction: Direction = Direction.KEEP,
        recentlyPlayed: Collection<String> = emptyList(),
        limit: Int = 30,
    ): List<Suggestion> {
        if (limit <= 0) return emptyList()
        val byId = HashMap<String, TrackProfile>()
        for (c in candidates) byId[c.id] = c
        byId[now.track.id] = now.track

        val blockedIds = HashSet<String>(recentlyPlayed).apply { add(now.track.id) }
        val blockedSongs = HashSet<String>(songKeys(now.track.title, now.track.artist))
        for (r in recentlyPlayed) {
            val t = byId[r]
            blockedSongs += if (t != null) songKeys(t.title, t.artist) else songKeys(r, "")
        }

        val scored = candidates.asSequence()
            .filter { it.id !in blockedIds }
            .map { it to songKeys(it.title, it.artist) }
            .filter { (_, keys) -> keys.none { it in blockedSongs } }
            .map { (t, keys) -> score(now, t, direction) to keys }
            .sortedWith(
                compareByDescending<Pair<Suggestion, Set<String>>> { it.first.score }
                    .thenBy { if (it.first.track.bpm > 0 && now.track.bpm > 0) abs(it.first.pitchToSync) else 1.0 }
                    .thenBy { it.first.track.title.lowercase(Locale.ROOT) }
                    .thenBy { it.first.track.id },
            )
            .toList()

        // The same song uploaded twice: keep only the better-scoring copy.
        val seen = HashSet<String>()
        val out = ArrayList<Suggestion>()
        for ((s, keys) in scored) {
            if (keys.any { it in seen }) continue
            seen += keys
            out += s
            if (out.size >= limit) break
        }
        return out
    }

    // ================================================================== key

    internal enum class Move(val fit: Double) {
        SAME(1.00),
        STEP(0.88),
        RELATIVE(0.88),
        DIAGONAL_NEAR(0.72),
        DIAGONAL_FAR(0.64),
        BOOST(0.52),
        DROP(0.45),
        PARALLEL(0.40),
        SEMITONE_LIFT(0.30),
        RISKY(0.25),
        CLASH(CLASH_FLOOR),
    }

    /** How key b relates to key a on the Camelot wheel. [dn] is b's number minus a's, −5..6. */
    internal class Relation(val move: Move, val dn: Int, val shared: Int, val fit: Double)

    internal fun relation(a: MusicalKey, b: MusicalKey): Relation {
        var dn = Math.floorMod(b.camelotNumber - a.camelotNumber, 12)
        if (dn > 6) dn -= 12
        val shared = sharedNotes(a, b)
        val named: Move? = if (a.minor == b.minor) {
            when (dn) {
                0 -> Move.SAME
                1, -1 -> Move.STEP
                2 -> Move.BOOST
                -2 -> Move.DROP
                -5 -> Move.SEMITONE_LIFT // +7 on the wheel = everything one semitone up
                else -> null
            }
        } else {
            // From a minor key the nearer diagonal is −1 (8A → 7B, A minor → F major: F-A-C shares
            // two notes with A-C-E); from a major key it is +1 (8B → 9A, C major → E minor).
            val near = if (a.minor) -1 else 1
            when (dn) {
                0 -> Move.RELATIVE
                near -> Move.DIAGONAL_NEAR
                -near -> Move.DIAGONAL_FAR
                -3 * near -> Move.PARALLEL // 8A → 11B (A minor → A major), 8B → 5A (C major → C minor)
                else -> null
            }
        }
        val fit = when {
            named != null -> named.fit
            shared >= 5 -> Move.RISKY.fit
            shared == 4 -> 0.12
            shared == 3 -> 0.06
            else -> CLASH_FLOOR
        }
        return Relation(named ?: if (shared >= 5) Move.RISKY else Move.CLASH, dn, shared, fit)
    }

    /** How many of the 7 scale notes two keys share (natural minor = its relative major's notes). */
    internal fun sharedNotes(a: MusicalKey, b: MusicalKey): Int {
        val sa = scale(a)
        val sb = scale(b)
        return (0..11).count { sa[it] && sb[it] }
    }

    private val MAJOR_STEPS = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    private fun scale(k: MusicalKey): BooleanArray {
        val root = if (k.minor) (k.tonic + 3) % 12 else k.tonic
        val s = BooleanArray(12)
        for (st in MAJOR_STEPS) s[(root + st) % 12] = true
        return s
    }

    internal fun moveLabel(r: Relation): String = when (r.move) {
        Move.SAME -> "same key"
        Move.STEP -> if (r.dn > 0) "+1" else "−1"
        Move.RELATIVE -> "relative"
        Move.DIAGONAL_NEAR, Move.DIAGONAL_FAR -> if (r.dn > 0) "+1 diagonal" else "−1 diagonal"
        Move.BOOST -> "+2 (energy boost)"
        Move.DROP -> "−2"
        Move.PARALLEL -> "parallel"
        Move.SEMITONE_LIFT -> "+7 (semitone lift)"
        Move.RISKY -> "risky"
        Move.CLASH -> "clash"
    }

    /** Key moves that push the energy the way the DJ wants get a little extra. */
    private fun directionBonus(r: Relation, from: MusicalKey, dir: Direction): Double = when (dir) {
        Direction.KEEP -> 0.0
        Direction.UP -> when {
            r.move == Move.STEP && r.dn > 0 -> 0.04
            r.move == Move.BOOST -> 0.15
            r.move == Move.SEMITONE_LIFT -> 0.20
            r.move == Move.RELATIVE && from.minor -> 0.03 // minor → major brightens
            else -> 0.0
        }
        Direction.DOWN -> when {
            r.move == Move.STEP && r.dn < 0 -> 0.04
            r.move == Move.DROP -> 0.12
            r.move == Move.RELATIVE && !from.minor -> 0.03 // major → minor darkens
            else -> 0.0
        }
    }

    private fun tuned(fit: Double, detune: Double): Double =
        if (fit <= CLASH_FLOOR) fit else CLASH_FLOOR + (fit - CLASH_FLOOR) * interp(detune, DETUNE_X, DETUNE_Y)

    private fun trust(conf: Float): Double {
        val c = if (conf.isNaN()) 0.5 else conf.toDouble()
        return ((c - KEY_CONF_NONE) / (KEY_CONF_FULL - KEY_CONF_NONE)).coerceIn(0.0, 1.0)
    }

    /** One way of hearing the new song against the playing one: shifted by [n] whole semitones, [detune] off. */
    private class Reading(val rel: Relation, val n: Int, val detune: Double, val fit: Double)

    /**
     * The best reading of a pitch gap of [gap] semitones between keys [nk] (playing) and [ck] (new):
     * the whole-semitone shift below or above the gap, whichever sounds better once its detune is
     * counted (normally the nearer one; near a quarter tone a consonant reading can win).
     */
    private fun read(nk: MusicalKey, ck: MusicalKey, gap: Double, dir: Direction): Reading {
        val lo = floor(gap).toInt()
        var best: Reading? = null
        for (n in lo..lo + 1) {
            val rel = relation(nk, ck.transpose(n))
            val d = abs(gap - n)
            val r = Reading(rel, n, d, tuned(min(1.0, rel.fit + directionBonus(rel, nk, dir)), d))
            val b = best
            if (b == null || r.fit > b.fit + 1e-9 || (abs(r.fit - b.fit) <= 1e-9 && r.detune < b.detune)) best = r
        }
        return best!!
    }

    private class KeyResult(
        val fit: Double, val label: String?, val phrase: String,
        val from: MusicalKey? = null, val to: MusicalKey? = null, val inTune: Boolean = false,
    )

    /** The difference in tuning between two recordings, in semitones (e.g. one tuned 30 cents sharp). */
    private fun tuningGap(now: TrackProfile, cand: TrackProfile): Double {
        val g = (cand.tuningCents - now.tuningCents).toDouble() / 100
        return if (g.isFinite()) g.coerceIn(-1.0, 1.0) else 0.0
    }

    private fun keyPart(
        nowTrack: TrackProfile, cand: TrackProfile, nowPitch: Double, nowShift: Double, candShift: Double,
        sync: Sync, dir: Direction, reasons: MutableList<Reason>,
    ): KeyResult {
        val nk = nowTrack.key
        val ck = cand.key
        if (nk == null || ck == null) {
            val who = when {
                nk == null && ck == null -> "either song"
                nk == null -> "the playing song"
                else -> "this song"
            }
            reasons += Reason("Key unknown for $who, so I can't check the notes fit — listen in your headphones first.", false)
            return KeyResult(0.5, null, "Key unknown")
        }

        // Pitch gap between the two songs as they will sound together, in semitones. Recordings
        // tuned away from A = 440 Hz count too: two "8A" songs 40 cents apart are not in tune.
        val tune = tuningGap(nowTrack, cand)
        val after = read(nk, ck, candShift - nowShift + tune, dir)
        val t = min(trust(nowTrack.keyConfidence), trust(cand.keyConfidence))
        val fit = 0.5 + (after.fit - 0.5) * t
        val detuned = after.detune > IN_TUNE && after.rel.move != Move.CLASH

        // Camelot codes to show: the playing song's sounding key and the new song's key as it will
        // sound next to it. When the playing deck sits between keys, pick the frame that agrees
        // with keyAfterSync.
        val nowWhole = MusicalKey.wholeSemitones(nowShift)
        val b = nowWhole ?: MusicalKey.wholeSemitones(candShift)?.let { it - after.n } ?: nowShift.roundToInt()
        val from = nk.transpose(b)
        val to = ck.transpose(after.n + b)
        val pctSync = fmtPct(sync.speed - 1)

        if (nowWhole != null && nowWhole != 0) {
            reasons += Reason(
                "Your deck's ${fmtPct(nowPitch)} ${if (nowWhole > 0) "raises" else "lowers"} the playing song ${semitoneCount(abs(nowWhole))}: it sounds in ${from.camelot}, not ${nk.camelot}.",
                true,
            )
        }

        if (nowWhole == null) {
            val lo = floor(nowShift).toInt()
            val between = "Your deck's ${fmtPct(nowPitch)} puts the playing song between ${nk.transpose(lo).camelot} and ${nk.transpose(lo + 1).camelot}"
            // Credit SYNC only if its shift alone keeps the two in tune; otherwise the recordings' tuning did.
            val syncAlone = candShift - nowShift
            val syncKeepsTune = sync.known && abs(syncAlone - Math.rint(syncAlone)) <= IN_TUNE
            reasons += when {
                detuned -> Reason("$between.", false)
                syncKeepsTune -> Reason("$between — but SYNC shifts this song the same way, so the two stay in tune.", true)
                else -> Reason("$between — but this recording is tuned a little differently, which makes up the difference, so the two stay in tune.", true)
            }
        }

        if (!detuned) {
            reasons += Reason(moveText(after.rel, from, to), after.fit >= 0.6)
        } else {
            val whenTxt = when {
                !sync.known -> "at its normal speed"
                abs(sync.speed - 1) < 0.0005 -> "once synced"
                else -> "after the $pctSync sync"
            }
            reasons += if (after.detune <= 0.42) Reason(
                "${from.camelot} → ${to.camelot} would be ${moveShort(after.rel)}, but $whenTxt this song sits ${fraction(after.detune)} off the playing song — between keys, so held notes will sound a bit sour together. Keep the blend short or mix over drums.",
                false,
            ) else Reason(
                "${from.camelot} → ${to.camelot} would be ${moveShort(after.rel)}, but $whenTxt this song lands right between two keys, ${fraction(after.detune)} off the playing song, so held notes will sound out of tune together. Mix it over drums or with a quick cut.",
                false,
            )
        }

        // Compared with the new song at its normal speed: did SYNC rescue or spoil the key?
        var fixedBySync = false
        if (sync.known) {
            val natural = read(nk, ck, -nowShift + tune, dir)
            if (natural.n != after.n) {
                val natCode = ck.transpose(natural.n + b).camelot
                val steps = after.n - natural.n
                val moved = "${if (steps > 0) "raises" else "lowers"} it ${semitoneCount(abs(steps))}"
                when {
                    detuned -> {
                        val by = "${if (candShift > 0) "raises" else "lowers"} it by ${"%.1f".format(Locale.ROOT, abs(candShift))} semitones"
                        reasons += if (natural.fit >= 0.6) {
                            Reason("At its normal speed it would fit ($natCode), but the $pctSync sync $by, leaving it between keys.", false)
                        } else {
                            Reason("The $pctSync sync $by: $natCode ends up near ${to.camelot}.", after.fit >= natural.fit)
                        }
                    }
                    natural.fit < 0.3 && after.fit >= 0.6 -> {
                        fixedBySync = true
                        reasons += Reason(
                            "At its normal speed it's in $natCode, which clashes with ${from.camelot} — but the $pctSync sync $moved to ${to.camelot}, ${fitWord(after.rel)}.",
                            true,
                        )
                    }
                    natural.fit >= 0.6 && after.fit < 0.45 -> reasons += Reason(
                        "At its normal speed it would fit ($natCode), but the $pctSync sync $moved to ${to.camelot}, which doesn't sit well with ${from.camelot}.",
                        false,
                    )
                    else -> reasons += Reason("The $pctSync sync $moved: $natCode becomes ${to.camelot}.", after.fit >= natural.fit)
                }
            }
        }

        if (t < KEY_TRUST_WARN) {
            val lowNow = trust(nowTrack.keyConfidence) < KEY_TRUST_WARN
            val lowCand = trust(cand.keyConfidence) < KEY_TRUST_WARN
            val who = when {
                lowNow && lowCand -> "both songs"
                lowNow -> "the playing song"
                else -> "this song"
            }
            reasons += Reason("The key is uncertain for $who (no clear key detected), so take the key advice lightly.", false)
        }

        val label = if (detuned && after.rel.move != Move.RISKY) "${moveLabel(after.rel)} (detuned)" else moveLabel(after.rel)
        var phrase = when {
            after.rel.move == Move.CLASH -> "Keys clash"
            after.detune > 0.42 -> "Out of tune"
            detuned -> "Slightly out of tune"
            fixedBySync -> "Key fits after sync"
            else -> when (after.rel.move) {
                Move.SAME -> "Perfect key match"
                Move.STEP -> "Smooth key change"
                Move.RELATIVE -> "Same notes, new mood"
                Move.DIAGONAL_NEAR, Move.DIAGONAL_FAR -> "Compatible key"
                Move.BOOST -> "Energy-boost key change"
                Move.DROP -> "Key steps down"
                Move.PARALLEL -> "Mood flip"
                Move.SEMITONE_LIFT -> "Semitone key lift"
                Move.RISKY -> "Distant key"
                Move.CLASH -> "Keys clash"
            }
        }
        if (t < KEY_TRUST_WARN) phrase += " (key unsure)"
        return KeyResult(fit, label, phrase, from, to, after.detune <= IN_TUNE)
    }

    private fun moveText(r: Relation, a: MusicalKey, b: MusicalKey): String {
        val ab = "${a.camelot} → ${b.camelot}"
        return when (r.move) {
            Move.SAME -> "$ab: the same key, so every note fits — the safest mix there is."
            Move.STEP -> if (r.dn > 0) "$ab: one step round the key wheel, a smooth change with a little lift."
            else "$ab: one step back round the key wheel, a smooth change that relaxes a little."
            Move.RELATIVE -> if (a.minor) "$ab: the same notes, minor to major — the mood brightens."
            else "$ab: the same notes, major to minor — the mood gets deeper."
            Move.DIAGONAL_NEAR, Move.DIAGONAL_FAR ->
                "$ab: a diagonal step on the key wheel — 6 of 7 notes shared, so it works, with a change of mood."
            Move.BOOST -> "$ab: two steps up the key wheel, the music jumps a whole tone higher — an \"energy boost\". Great for lifting the room, just don't overuse it."
            Move.DROP -> "$ab: two steps down the key wheel (a whole tone lower) — brings the energy down a notch; mix it fairly quickly."
            Move.PARALLEL -> "$ab: the same home note but ${if (a.minor) "minor to major" else "major to minor"} — a dramatic mood flip. Keep the overlap short."
            Move.SEMITONE_LIFT -> "$ab: everything moves up one semitone — the classic pop \"key change\" lift. Swap quickly at a drop; a long blend will clash."
            Move.RISKY -> "$ab: a distant key (5 of 7 notes shared) — some notes will rub, so keep the blend short."
            Move.CLASH -> "$ab: these keys clash (only ${r.shared} of 7 notes shared) — layered together it'll sound off. Use a quick cut or mix over drums only."
        }
    }

    private fun moveShort(r: Relation) = when (r.move) {
        Move.SAME -> "the same key"
        Move.STEP -> "one step round the key wheel"
        Move.RELATIVE -> "the relative key (same notes, other mood)"
        Move.DIAGONAL_NEAR, Move.DIAGONAL_FAR -> "a diagonal step on the key wheel"
        Move.BOOST -> "two steps up the key wheel"
        Move.DROP -> "two steps down the key wheel"
        Move.PARALLEL -> "the same home note in the other mood"
        Move.SEMITONE_LIFT -> "one semitone up"
        Move.RISKY -> "a distant key"
        Move.CLASH -> "a clash"
    }

    private fun fitWord(r: Relation) = when (r.move) {
        Move.SAME -> "a perfect match"
        Move.STEP, Move.RELATIVE -> "a smooth fit"
        Move.DIAGONAL_NEAR, Move.DIAGONAL_FAR -> "a good fit"
        else -> "which works"
    }

    private fun semitoneCount(k: Int) = if (k == 1) "a semitone" else "$k semitones"

    private fun fraction(d: Double) = when {
        d < 0.3 -> "about a quarter of a semitone"
        d < 0.42 -> "about a third of a semitone"
        else -> "about half a semitone"
    }

    // ================================================================== tempo

    /** The tempo change SYNC applies to the new deck, worked out exactly as [Deck.syncTo] does. */
    internal class Sync(val speed: Double, val halfDouble: Int, val known: Boolean)

    internal fun sync(nowBpm: Double, nowPitch: Double, candBpm: Double): Sync {
        if (!(nowBpm > 0) || !(candBpm > 0) || !nowBpm.isFinite() || !candBpm.isFinite() || !(1 + nowPitch > 0)) {
            return Sync(1.0, 0, false)
        }
        var target = nowBpm * (1 + nowPitch)
        var hd = 0
        while (target / candBpm > 1.5) { target /= 2; hd-- }
        while (target / candBpm < 0.67) { target *= 2; hd++ }
        return Sync(target / candBpm, hd, true)
    }

    /** Double time when building energy, or half time when cooling down, is the point, not a cost. */
    private fun halfDoubleSuits(hd: Int, dir: Direction) =
        (hd > 0 && dir == Direction.UP) || (hd < 0 && dir == Direction.DOWN)

    private fun tempoReasons(
        nowTrack: TrackProfile, nowPitch: Double, cand: TrackProfile, sync: Sync, dir: Direction,
        reasons: MutableList<Reason>,
    ) {
        if (!sync.known) {
            val who = if (!(cand.bpm > 0)) "this song" else "the playing song"
            reasons += Reason("No steady beat found in $who, so SYNC can't line it up — bring it in over a break or with a quick cut.", false)
            return
        }
        val p = sync.speed - 1
        val pct = fmtPct(p)
        val ap = abs(p) * 100
        val playing = nowTrack.bpm * (1 + nowPitch)
        val bpms = "${fmtBpm(cand.bpm)} → ${fmtBpm(cand.bpm * sync.speed)} BPM"
        val st = abs(MusicalKey.semitonesForSpeed(sync.speed))
        reasons += when {
            ap < 0.3 -> Reason("Same tempo — it needs only $pct to sync.", true)
            ap <= 3 -> Reason("Needs $pct to match the beat ($bpms) — a small nudge.", true)
            ap <= 6 -> Reason(
                "Needs $pct to match the beat ($bpms) — noticeable: voices will sound slightly ${if (p > 0) "higher" else "lower"}.",
                ap <= 4.5,
            )
            ap <= 10 -> Reason(
                "Needs $pct to match the beat — on these vinyl-style decks voices go about ${"%.1f".format(Locale.ROOT, st)} semitones ${if (p > 0) "higher" else "lower"}, which people will hear.",
                false,
            )
            else -> Reason(
                "Needs $pct to match the beat — it'll sound ${if (p > 0) "chipmunky" else "slow and sluggish"}. Better as a quick cut than a long blend.",
                false,
            )
        }
        if (sync.halfDouble > 0) {
            reasons += Reason(
                "Matched at double time: its ${fmtBpm(cand.bpm)} BPM is about twice the playing ${fmtBpm(playing)}, so its beats come twice as fast — the groove speeds up.",
                dir != Direction.DOWN,
            )
        } else if (sync.halfDouble < 0) {
            reasons += Reason(
                "Matched at half time: its ${fmtBpm(cand.bpm)} BPM is about half the playing ${fmtBpm(playing)}, so every other beat lines up — the groove feels slower.",
                dir != Direction.UP,
            )
        }
    }

    private fun tempoPhrase(sync: Sync): String {
        if (!sync.known) return "no beat sync"
        val p = sync.speed - 1
        val base = if (abs(p) < 0.0005) "same tempo" else "${fmtPct(p)} tempo"
        return when {
            sync.halfDouble > 0 -> "$base at double time"
            sync.halfDouble < 0 -> "$base at half time"
            else -> base
        }
    }

    // ================================================================== energy

    internal fun energyFit(now: Float, cand: Float, dir: Direction, halfDouble: Int = 0): Double? {
        if (now.isNaN() || cand.isNaN()) return null
        val d = (cand - now).toDouble()
        var f = when (dir) {
            Direction.KEEP -> interp(abs(d), KEEP_X, KEEP_Y)
            Direction.UP -> interp(d, UP_X, UP_Y)
            Direction.DOWN -> interp(-d, UP_X, UP_Y)
        }
        if (halfDouble != 0 && halfDoubleSuits(halfDouble, dir)) f = min(1.0, f + HALF_DOUBLE_ENERGY_BONUS)
        return f
    }

    private fun energyReasons(now: Float, cand: Float, dir: Direction, reasons: MutableList<Reason>) {
        if (now.isNaN() || cand.isNaN()) {
            if (dir != Direction.KEEP) {
                reasons += Reason("Energy level unknown, so I can't tell if it ${if (dir == Direction.UP) "builds" else "cools"} the room.", false)
            }
            return
        }
        val d = (cand - now).toDouble()
        val a = fmtEnergy(now)
        val b = fmtEnergy(cand)
        val same = abs(d) < 0.5
        reasons += when (dir) {
            Direction.KEEP -> when {
                same -> Reason("Same energy ($b/10) — keeps the vibe going.", true)
                abs(d) < 1.5 -> Reason("${if (d > 0) "Slightly more energy" else "Slightly calmer"} ($a → $b) — still keeps the vibe.", true)
                abs(d) < 2.5 -> Reason("${if (d > 0) "More energy" else "Calmer"} ($a → $b) — a noticeable change of pace.", false)
                else -> Reason("Big energy ${if (d > 0) "jump" else "drop"} ($a → $b) — it'll feel sudden.", false)
            }
            Direction.UP -> when {
                same -> Reason("Same energy ($b/10) — holds the level rather than building.", false)
                d < 0 -> Reason("Lower energy ($a → $b) — the opposite of building up.", false)
                d < 1.5 -> Reason("A little more energy ($a → $b) — builds the set one step at a time.", true)
                d < 2.5 -> Reason("More energy ($a → $b) — a bold push up.", true)
                else -> Reason("Big energy jump ($a → $b) — a sharp push; can feel sudden.", false)
            }
            Direction.DOWN -> when {
                same -> Reason("Same energy ($b/10) — holds the level rather than cooling down.", false)
                d > 0 -> Reason("Higher energy ($a → $b) — the opposite of cooling down.", false)
                d > -1.5 -> Reason("A little calmer ($a → $b) — eases the room down gently.", true)
                d > -2.5 -> Reason("Calmer ($a → $b) — a clear step down.", true)
                else -> Reason("Big energy drop ($a → $b) — a sharp cool-down; can feel like the party stopped.", false)
            }
        }
    }

    private fun energyPhrase(now: Float, cand: Float): String? {
        if (now.isNaN() || cand.isNaN()) return null
        val d = (cand - now).toDouble()
        return when {
            d >= 2.5 -> "big energy jump"
            d >= 1.5 -> "more energy"
            d >= 0.5 -> "a little more energy"
            d > -0.5 -> "same energy"
            d > -1.5 -> "a little calmer"
            d > -2.5 -> "calmer"
            else -> "big energy drop"
        }
    }

    // ================================================================== timbre

    internal fun cosine(a: FloatArray?, b: FloatArray?): Double? {
        if (a == null || b == null || a.isEmpty() || a.size != b.size) return null
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            if (!x.isFinite() || !y.isFinite()) return null
            dot += x * y; na += x * x; nb += y * y
        }
        if (na <= 0 || nb <= 0) return null
        return (dot / sqrt(na * nb)).coerceIn(-1.0, 1.0)
    }

    // ================================================================== same song, other upload

    private val SPACES = Regex("\\s+")
    private val MARKS = Regex("\\p{M}+")
    private val APOSTROPHES = Regex("['’‘`´]")
    private val BRACKETS = Regex("""[(\[{]([^)\]}]*)[)\]}]""")
    private val SEPARATORS = Regex("""\s+(?:-|–|—|\||//|~)\s+""")
    private val FEAT_TAIL = Regex("""\s(?:feat\.?|ft\.?|featuring)\s.*$""")
    private val TOPIC = Regex("""\s*-\s*topic$""")
    private val VEVO = Regex("""vevo$""")
    private val OFFICIAL = Regex("""\bofficial\b""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|\+|/|;|\bx\b|\band\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\bvs\.?)\s*""")
    private val FEAT_WORDS = setOf("feat", "ft", "featuring", "with", "prod")
    private val NOISE_WORDS = setOf(
        "official", "video", "videoclip", "clip", "audio", "lyric", "lyrics", "visualizer", "visualiser",
        "hd", "hq", "4k", "8k", "remaster", "remastered", "explicit", "clean", "dirty", "mv", "m", "v",
        "music", "full", "song", "version", "radio", "edit", "extended", "original", "mix", "live",
        "performance", "uncut", "high", "quality", "topic", "new", "the", "out", "now", "single",
    )
    private val STOP_WORDS = setOf("the", "a", "an", "and", "x", "feat", "ft", "featuring")

    /**
     * Keys that identify a song whatever the upload: "God's Plan" by "Drake" and
     * "Drake - God's Plan (Official Video)" by "DrakeVEVO" share one. Remixes stay different songs.
     */
    internal fun songKeys(title: String, artist: String): Set<String> {
        val t = fold(title)
        val a = OFFICIAL.replace(VEVO.replace(TOPIC.replace(fold(artist), ""), ""), "").trim()

        val noBrackets = BRACKETS.replace(t) { m ->
            val inner = m.groupValues[1]
            val w = words(inner, keepStop = true)
            if (w.isEmpty() || w.first() in FEAT_WORDS || w.all { isNoise(it) }) " " else " $inner "
        }
        val segments = SEPARATORS.split(noBrackets)
            .map { FEAT_TAIL.replace(" $it ", " ").trim() }
            .filter { s -> words(s, keepStop = true).let { w -> w.isNotEmpty() && !w.all { isNoise(it) } } }
        val titleWords = segments.flatMap { words(it) }

        val keys = HashSet<String>()
        fun add(ws: Collection<String>) {
            val set = ws.toSortedSet()
            if (set.size >= 2) keys += set.joinToString(" ")
        }
        add(words(a) + titleWords)
        add(words(primary(a)) + titleWords)
        if (segments.size >= 2) {
            val left = segments.first()
            val right = segments.drop(1).flatMap { words(it) }
            add(words(left) + right)
            add(words(primary(left)) + right)
        }
        return keys
    }

    private fun isNoise(w: String) = w in NOISE_WORDS || w.all(Char::isDigit)

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(MARKS, "")
        .lowercase(Locale.ROOT).replace(APOSTROPHES, "").replace(SPACES, " ").trim()

    private fun primary(artist: String) = ARTIST_SPLIT.split(artist).firstOrNull { it.isNotBlank() }?.trim() ?: ""

    private fun words(s: String, keepStop: Boolean = false): List<String> =
        NON_WORD.split(s).filter { it.isNotEmpty() && (keepStop || it !in STOP_WORDS) }

    // ================================================================== helpers

    internal fun stars(score: Double): Int = when {
        score >= STAR_MIN[0] -> 5
        score >= STAR_MIN[1] -> 4
        score >= STAR_MIN[2] -> 3
        score >= STAR_MIN[3] -> 2
        else -> 1
    }

    /** Piecewise-linear lookup, flat beyond the ends. */
    private fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (x.isNaN()) return ys.last()
        if (x <= xs[0]) return ys[0]
        for (i in 1 until xs.size) {
            if (x <= xs[i]) {
                val f = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
                return ys[i - 1] + f * (ys[i] - ys[i - 1])
            }
        }
        return ys.last()
    }

    /** "+2.1%", "−4.0%", "±0.0%". */
    internal fun fmtPct(fraction: Double): String {
        val v = fraction * 100
        val sign = when {
            v >= 0.05 -> "+"
            v <= -0.05 -> "−"
            else -> "±"
        }
        return sign + "%.1f".format(Locale.ROOT, abs(v)) + "%"
    }

    private fun fmtBpm(bpm: Double): String =
        if (abs(bpm - bpm.roundToInt()) < 0.05) bpm.roundToInt().toString() else "%.1f".format(Locale.ROOT, bpm)

    private fun fmtEnergy(e: Float): String =
        if (abs(e - e.roundToInt()) < 0.05f) e.roundToInt().toString() else "%.1f".format(Locale.ROOT, e)

    private val SEMITONE_WORD = Regex("""(of a |half a |a |one |\d+(?:\.\d+)? )?(semitones?)\b""")

    /** Explains "semitone" the first time a suggestion's reasons use it. */
    private fun glossSemitone(reasons: List<Reason>): List<Reason> {
        var done = false
        return reasons.map { r ->
            if (done) return@map r
            val m = SEMITONE_WORD.find(r.text) ?: return@map r
            done = true
            val gloss = when {
                m.groupValues[1] == "of a " || m.groupValues[1] == "half a " -> " (a semitone is one piano key)"
                m.groupValues[2] == "semitones" -> " (piano keys)"
                else -> " (one piano key)"
            }
            r.copy(text = r.text.substring(0, m.range.last + 1) + gloss + r.text.substring(m.range.last + 1))
        }
    }
}
