package com.mixdeck

import com.mixdeck.engine.TwistBucket
import com.mixdeck.engine.TwistPlan
import java.io.IOException
import kotlin.random.Random

/**
 * Finds songs to try as "twists" (see [com.mixdeck.engine.Twists]): songs from other styles whose
 * tempo and energy are usually near the playing song's, by artists outside its scene.
 *
 * [find] is the thin network part: each style's search and the playing song's "like this" list
 * (its scene) are asked in parallel, with one deadline; a style that fails or is slow is skipped.
 * [pick] is the pure part, tested offline.
 */
object TwistSearch {
    /** A song to try, from [bucket]'s search, by [artist]. */
    data class Candidate(val r: YtResult, val bucket: TwistBucket, val artist: String)

    /** Songs tried per style at most. */
    @Volatile var perBucket = 3

    /** How long the searches get, all together. */
    @Volatile var timeoutMs = 10_000L

    /** Songs from this far down a style's list can be picked (the best known, varied between runs). */
    const val TOP = 25

    /** A style whose songs include this many artists from the playing song's scene is that scene: no twist. */
    const val SCENE_OVERLAP = 2

    /** How many "like this" songs make the scene (the same number "Find new songs like this" asks for, so it's usually cached). */
    const val SCENE_SIZE = 18

    /**
     * Candidates for [seed] from the styles in [plans], best first and spread across styles. Throws
     * [IOException] only when no style could be reached at all.
     */
    fun find(seed: Track, plans: List<TwistPlan>, skipIds: Set<String>, random: Random = Random.Default): List<Candidate> {
        if (plans.isEmpty()) return emptyList()
        val tasks = plans.map { p -> val q = p.bucket.queries.random(random); { Discovery.styleSongs(q) } } + listOf({ scene(seed) })
        val res = Discovery.inParallel(tasks, timeoutMs)
        val styles = plans.indices.mapNotNull { i -> res[i].getOrNull()?.let { plans[i].bucket to it } }
        if (styles.isEmpty()) throw IOException("Couldn't reach YouTube for twists", res.firstNotNullOfOrNull { it.exceptionOrNull() })
        val scene = res.last().getOrNull().orEmpty().map { Discovery.songOf(it).artist }
        return pick(styles, seed.artist, scene, skipIds, perBucket, random)
    }

    private fun scene(seed: Track): List<YtResult> =
        if (YouTube.isYouTubeUrl(seed.source)) Discovery.related(seed.source, SCENE_SIZE)
        else Discovery.relatedTo(seed.title, seed.artist, SCENE_SIZE)

    /**
     * Chooses songs to try from each style's search results ([styles], best known first):
     *  - junk (mixes, live, covers, …) and [skipIds] (YouTube video ids) are dropped;
     *  - a style is dropped whole when it is the playing song's own scene: the seed's artist
     *    ([seedArtist]) is in it, or [SCENE_OVERLAP] or more of the [scene] artists are;
     *  - songs by the seed's artist or a [scene] artist are dropped (not a twist, just more of the same);
     *  - one song per artist, [perBucket] per style, picked at random among the first [TOP];
     *  - the styles take turns, so the first few songs tried are all different styles.
     */
    fun pick(
        styles: List<Pair<TwistBucket, List<YtResult>>>, seedArtist: String, scene: Collection<String>,
        skipIds: Set<String>, perBucket: Int, random: Random = Random.Default,
    ): List<Candidate> {
        val seedKeys = artistKeys(seedArtist)
        val sceneKeys = scene.flatMap(::artistKeys).distinct()
        val perStyle = styles.mapNotNull { (b, songs) ->
            val ok = songs.filter { it.id !in skipIds && Discovery.junkReason(it) == null }
                .map { it to Discovery.songOf(it) }
                .filter { (_, id) -> id.artist.isNotBlank() && id.title.isNotBlank() }
            val artists = ok.map { Discovery.key(it.second.artist) }.distinct()
            if (artists.any { a -> seedKeys.any { sameArtist(a, it) } }) return@mapNotNull null
            if (artists.count { a -> sceneKeys.any { sameArtist(a, it) } } >= SCENE_OVERLAP) return@mapNotNull null
            val fresh = ok.filter { (_, id) -> artistKeys(id.artist).none { a -> (seedKeys + sceneKeys).any { sameArtist(a, it) } } }
                .distinctBy { Discovery.key(it.second.artist) }
            b to fresh.take(TOP).shuffled(random).take(perBucket).map { (r, id) -> Candidate(r, b, id.artist) }
        }
        val out = ArrayList<Candidate>()
        val usedArtists = ArrayList<String>()
        val usedSongs = ArrayList<Discovery.SongId>()
        var round = 0
        while (true) {
            var any = false
            for ((_, list) in perStyle) {
                val c = list.getOrNull(round) ?: continue
                any = true
                val a = Discovery.key(c.artist)
                val song = Discovery.SongId(c.artist, Discovery.songOf(c.r).title)
                if (usedArtists.any { sameArtist(it, a) } || usedSongs.any { Discovery.sameSong(it, song) }) continue
                usedArtists += a; usedSongs += song
                out += c
            }
            if (!any) break
            round++
        }
        return out
    }

    /** Comparison keys of everyone credited: "Jay-Z & Beyoncé" → "jay z", "beyonce". */
    private fun artistKeys(artist: String): List<String> =
        (listOf(artist) + artist.split(ARTIST_SPLIT)).map(Discovery::key).filter { it.isNotEmpty() }.distinct()

    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|\+|/|\s(?:x|X|and|feat\.?|ft\.?|featuring|with|vs\.?)\s)\s*""")

    /** "nelly" and "nelly" or "st louis nelly": one name's words all in the other's. */
    fun sameArtist(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val wa = a.split(' ').filter { it.length >= 2 }.toSet()
        val wb = b.split(' ').filter { it.length >= 2 }.toSet()
        return wa.isNotEmpty() && wb.isNotEmpty() && (wa.containsAll(wb) || wb.containsAll(wa))
    }
}
