package com.mixdeck

import com.mixdeck.engine.Analyzer
import com.mixdeck.engine.Direction
import com.mixdeck.engine.FeatureExtractor
import com.mixdeck.engine.NextTrack
import com.mixdeck.engine.NowPlaying
import com.mixdeck.engine.Pcm
import com.mixdeck.engine.Suggestion
import com.mixdeck.engine.TrackAnalysis
import java.io.File
import java.util.concurrent.CancellationException

/**
 * "What should I play next?" for both apps: makes sure songs have a key, energy and timbre,
 * ranks them against the song that's playing, and finds new songs like it on YouTube.
 *
 * Everything here is slow (decoding, analysis, downloads): call it from a background thread.
 * [decode] is the platform's decoder (MediaCodec on the phone, FFmpeg on the computer).
 */
class Suggester(
    private val store: TrackStore,
    private val decode: (File, (Float) -> Unit) -> Pcm,
) {
    private companion object {
        /**
         * One "find new songs" at a time in the whole app (every screen makes its own Suggester on
         * the phone), so two jobs never download the same song into the same file.
         */
        val discoverLock = Any()
    }

    /**
     * Fills in everything a decoded song is missing (beat grid, waveform, key, energy, timbre) and
     * saves it. Also used when a song is loaded onto a deck, since it's decoded then anyway.
     */
    fun analyze(t: Track, pcm: Pcm, progress: (Float) -> Unit = {}): Pair<Track, TrackAnalysis> {
        var track = store.get(t.id) ?: t
        var analysis = store.loadAnalysis(track)
        if (analysis == null) {
            analysis = Analyzer.analyze(pcm) { p -> progress(p * 0.6f) }
            track = store.saveAnalysis(track, analysis, Analyzer.loudnessDb(pcm), pcm.frames / pcm.sampleRate.toDouble())
        }
        if (!track.hasFeatures) {
            val f = FeatureExtractor.extract(pcm, track.bpm) { p -> progress(0.6f + p * 0.4f) }
            track = store.saveFeatures(track, f.key, f.keyConfidence, f.tuningCents, f.energy, f.timbre)
        }
        progress(1f)
        return track to (store.loadAnalysis(track) ?: analysis)
    }

    /** Decodes and analyses a song if it hasn't got key/energy yet. */
    fun ensureFeatures(t: Track, progress: (Float) -> Unit = {}): Track {
        if (t.hasFeatures) return t
        val pcm = decode(store.audioFile(t.id)) { p -> progress(p * 0.5f) }
        return analyze(t, pcm) { p -> progress(0.5f + p * 0.5f) }.first
    }

    /**
     * Ranks [candidates] as the next song after [now]. Songs played in the last few hours and the
     * songs on the decks ([exclude]) are left out.
     */
    fun rank(now: NowPlaying, candidates: List<Track>, direction: Direction, exclude: Collection<String> = emptyList()): List<Suggestion> {
        val recent = store.recentlyPlayed() + exclude
        return NextTrack.rank(now, candidates.filter { it.analyzed }.map { it.profile() }, direction, recent, limit = 50)
    }

    /**
     * Finds songs YouTube thinks go with [seed], then downloads and analyses up to [max] new ones,
     * handing each to [onTrack] as soon as it's ready. [onStatus] gets short progress messages.
     * Songs already on the device are skipped (they're ranked anyway). [isCancelled] belongs to the
     * caller's job: once it says yes, the song being downloaded is dropped and nothing more is fetched.
     * Returns how many songs were added.
     */
    fun discover(seed: Track, max: Int, isCancelled: () -> Boolean, onStatus: (String) -> Unit, onTrack: (Track) -> Unit): Int =
        synchronized(discoverLock) { discoverLocked(seed, max, isCancelled, onStatus, onTrack) }

    private fun discoverLocked(seed: Track, max: Int, isCancelled: () -> Boolean, onStatus: (String) -> Unit, onTrack: (Track) -> Unit): Int {
        if (isCancelled()) return 0
        onStatus("Asking YouTube for songs like ${seed.title}…")
        val related = runCatching {
            if (YouTube.isYouTubeUrl(seed.source)) Discovery.related(seed.source, limit = max * 3)
            else Discovery.relatedTo(seed.title, seed.artist, limit = max * 3)
        }.getOrElse { throw it }
        var added = 0
        for (r in related) {
            if (added >= max || isCancelled()) break
            val id = "yt_" + r.id
            // The deck may be downloading the same song: wait for it (Stop still works), then it's simply saved already.
            val t = store.withLock(id, poll = { if (isCancelled()) throw CancellationException("Stopped") }) {
                if (store.has(id)) null // already on the device, so it's in the list already
                else fetchNew(r, id, "${added + 1} of $max", isCancelled, onStatus)
            } ?: continue
            onTrack(t)
            added++
        }
        onStatus(if (isCancelled()) "Stopped." else "Found $added new song${if (added == 1) "" else "s"}.")
        return added
    }

    /**
     * Downloads and analyses one new song ([which]: "2 of 5"), or returns null when it failed or
     * was stopped. Call it holding [TrackStore.withLock] [id], having found it isn't saved: whatever
     * it leaves behind on failure is its own, so it's deleted.
     */
    private fun fetchNew(r: YtResult, id: String, which: String, isCancelled: () -> Boolean, onStatus: (String) -> Unit): Track? = try {
        onStatus("Downloading $which: ${r.title}…")
        val info = YouTube.downloadAudio(r.url, store.audioFile(id)) { p ->
            if (isCancelled()) throw CancellationException("Stopped")
            onStatus("Downloading $which: ${r.title}… ${(p * 100).toInt()}%")
        }
        if (isCancelled()) throw CancellationException("Stopped")
        val t = Track(id, info.title.ifEmpty { r.title }, info.artist.ifEmpty { r.artist }, r.url,
            info.durationSec.toDouble(), discovered = true)
        store.save(t)
        onStatus("Listening to $which: ${t.title}…")
        ensureFeatures(t) { if (isCancelled()) throw CancellationException("Stopped") }
    } catch (e: Throwable) {
        store.delete(id)
        if (!isCancelled()) onStatus("Skipped ${r.title}: ${Problems.explain(e)}")
        null
    }
}
