package com.bouzoukihero.core

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * How long a frame takes to reach the eye, so the notes can be drawn where the song will be
 * when they are seen. The game and Μπουζούκι's song screen both use this one formula, because
 * a song's sync is shared between them: the same sync must mean the same thing on both.
 */
object DisplayLead {
    /** The lead both screens assumed before it followed the screen's refresh rate. */
    const val OLD = 0.025

    /** The refresh rate assumed when the screen won't say. */
    const val DEFAULT_HZ = 60f

    /** A drawn frame is shown two screen refreshes after the tick (vsync) it was drawn for... */
    private const val PIPELINE_FRAMES = 2.0
    /** ...but never sooner than this, however fast the screen. */
    private const val MIN_PIPELINE = 0.025
    /** The screen lights up top to bottom over one refresh; the hit line is this far down it. */
    private const val SCAN_OUT = 0.6

    /** The furthest a sync can be set either way, in seconds. */
    const val MAX_SYNC = 1.0

    /** The rate to work with: [reported], or 60 Hz when it is missing or makes no sense. */
    fun hz(reported: Float): Float = if (reported >= 20f && reported <= 500f) reported else DEFAULT_HZ

    /**
     * Seconds from a frame's vsync (the screen's tick the frame is drawn on) to the moment the
     * hit line of that frame lights up, on a screen refreshing [refreshHz] times a second.
     */
    fun seconds(refreshHz: Float): Double {
        val period = 1.0 / hz(refreshHz)
        return max(PIPELINE_FRAMES * period, MIN_PIPELINE) + SCAN_OUT * period
    }

    /**
     * A sync tuned while the lead was [OLD], restated for [seconds]: the notes now show earlier
     * by the difference on their own, so the sync gives that much back and they stay where the
     * player put them. A sync of 0 was never tuned and stays 0.
     */
    fun migrated(sync: Double, refreshHz: Float): Double {
        if (sync == 0.0 || sync.isNaN()) return 0.0
        val moved = sync - (seconds(refreshHz) - OLD)
        return ((moved * 1000).roundToLong() / 1000.0).coerceIn(-MAX_SYNC, MAX_SYNC)
    }
}

/**
 * Where each song's sync is kept (as floats, in seconds, in the app's "bouzouki" preferences),
 * and the one-time restating of the syncs tuned before the lead followed the screen.
 */
object SyncStore {
    /** + the YouTube id: a prepared song's sync, shared with Μπουζούκι's song screen. */
    const val SONG = "song_sync_"

    /** + the tune's id: a built-in tune's sync. */
    const val BUILTIN = "hero_sync_b:"

    /**
     * Where a song not tuned yet starts from: the sync last chosen for a song of its kind.
     * Built-in tunes and YouTube songs run on different clocks (the phone's audio output, the
     * video player), so what suits one kind says nothing about the other.
     */
    const val DEFAULT_SONG = "hero_sync_default_song"
    const val DEFAULT_BUILTIN = "hero_sync_default_builtin"

    /** The one default both kinds used to share. No longer read; taken away by [restated]. */
    const val OLD_SHARED_DEFAULT = "hero_sync_last"

    /** Set once [restated] has been applied, so it happens once. */
    const val RESTATED = "sync_restated_for_display_lead"

    /**
     * The changes that restate everything in [stored] (the preferences as they are) for a screen
     * refreshing [refreshHz] times a second: each key to its new value, or to null to remove it.
     *
     * A YouTube song's tuned sync (not 0) moves by the change in lead ([DisplayLead.migrated]),
     * so its notes stay where the player put them.
     *
     * A built-in tune's sync is dropped instead. Its clock used to run ahead of the sound by the
     * phone's audio output delay, which is what the player tuned away; the clock now takes that
     * delay off by itself (by an amount only known once the tune plays), so the old figure would
     * count it twice and show the notes late. An untuned tune is the better start.
     *
     * The old shared default goes. Where it is plainly a YouTube song's sync (it was written
     * together with that song's own), YouTube songs not tuned yet go on starting from it; a
     * figure chosen on a built-in tune never crosses over to them.
     */
    fun restated(stored: Map<String, *>, refreshHz: Float): Map<String, Float?> {
        val changes = LinkedHashMap<String, Float?>()
        val shared = stored[OLD_SHARED_DEFAULT] as? Float
        for ((key, value) in stored) {
            if (key == OLD_SHARED_DEFAULT || key.startsWith(BUILTIN)) {
                changes[key] = null
            } else if (key.startsWith(SONG) && value is Float && value != 0f && !value.isNaN()) {
                val moved = DisplayLead.migrated(value.toDouble(), refreshHz).toFloat()
                changes[key] = moved
                if (value == shared) changes[DEFAULT_SONG] = moved
            }
        }
        return changes
    }
}
