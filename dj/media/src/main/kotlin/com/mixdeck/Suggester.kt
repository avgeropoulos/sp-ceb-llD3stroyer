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
import java.util.concurrent.atomic.AtomicBoolean

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
    private val cancelled = AtomicBoolean(false)

    /** Stops whatever long job is running (checked between songs). */
    fun cancel() = cancelled.set(true)
    val isCancelled get() = cancelled.get()

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
     * All songs on this device with their key and energy, analysing the ones that need it first.
     * [onProgress] gets (songs done, songs to analyse, title). Stops early if cancelled.
     */
    fun library(onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): List<Track> {
        cancelled.set(false)
        val all = store.all()
        val todo = all.filter { !it.hasFeatures }
        var done = 0
        val updated = HashMap<String, Track>()
        for (t in todo) {
            if (cancelled.get()) break
            onProgress(done, todo.size, t.title)
            runCatching { ensureFeatures(t) }.onSuccess { updated[t.id] = it }
            done++
        }
        onProgress(done, todo.size, "")
        return all.map { updated[it.id] ?: it }
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
     * Returns how many songs were added.
     */
    fun discover(seed: Track, max: Int, onStatus: (String) -> Unit, onTrack: (Track) -> Unit): Int {
        cancelled.set(false)
        onStatus("Asking YouTube for songs like ${seed.title}…")
        val related = runCatching {
            if (YouTube.isYouTubeUrl(seed.source)) Discovery.related(seed.source, limit = max * 3)
            else Discovery.relatedTo(seed.title, seed.artist, limit = max * 3)
        }.getOrElse { throw it }
        var added = 0
        for (r in related) {
            if (added >= max || cancelled.get()) break
            val id = "yt_" + r.id
            val existing = store.get(id)
            if (existing != null && store.has(id)) {
                // Already on the device: just make sure it's analysed and offer it.
                runCatching { ensureFeatures(existing) }.onSuccess { onTrack(it); added++ }
                continue
            }
            val n = added + 1
            try {
                onStatus("Downloading $n of $max: ${r.title}…")
                val info = YouTube.downloadAudio(r.url, store.audioFile(id)) { p ->
                    onStatus("Downloading $n of $max: ${r.title}… ${(p * 100).toInt()}%")
                }
                if (cancelled.get()) { store.delete(id); break }
                var t = Track(id, info.title.ifEmpty { r.title }, info.artist.ifEmpty { r.artist }, r.url,
                    info.durationSec.toDouble(), discovered = true)
                store.save(t)
                onStatus("Listening to $n of $max: ${t.title}…")
                t = ensureFeatures(t)
                onTrack(t)
                added++
            } catch (e: Throwable) {
                store.delete(id)
                onStatus("Skipped ${r.title}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        onStatus(if (cancelled.get()) "Stopped." else "Found $added new song${if (added == 1) "" else "s"}.")
        return added
    }
}
