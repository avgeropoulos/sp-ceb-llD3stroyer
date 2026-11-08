package com.mixdeck

import com.mixdeck.engine.Mixer
import java.io.IOException
import kotlin.math.abs

/**
 * One step of a guided tutorial: which controls to highlight, what to say, and (optionally) how
 * to tell that the user did it, so the tutorial can move on by itself.
 */
class TutorialStep(
    val keys: List<String>,
    val title: String,
    val text: String,
    val done: ((Mixer) -> Boolean)? = null,
)

class Tutorial(val name: String, val steps: List<TutorialStep>) {
    var index = 0
    /** When the current step's goal was reached (0 = not yet). */
    var doneAt = 0L
    /** Extra line under the step text, e.g. download progress or an error. */
    @Volatile var status: String? = null

    val step get() = steps[index]
}

/** A song the tutorial needs, and how to find a good copy of it. */
class TutorialSong(
    val query: String,
    val words: List<String>,
    val officialChannel: String,
    val title: String,
    val artist: String,
)

/**
 * Finds a song already on the phone, or finds it on YouTube and downloads it. Prefers the artist's
 * own upload, then any full-length upload; skips short clips.
 */
object SongFetcher {
    fun findOrDownload(store: TrackStore, song: TutorialSong, progress: (String) -> Unit): String {
        fun matches(title: String, artist: String) = (title + " " + artist).lowercase().let { t -> song.words.all { t.contains(it) } }
        store.all().firstOrNull { matches(it.title, it.artist) }?.let { return it.id }
        progress("Searching YouTube for ${song.title}…")
        val videos = runCatching { YouTube.Search(song.query + " official audio", songsOnly = false).nextPage() }.getOrDefault(emptyList())
        val full = { r: YtResult -> r.durationSec !in 1..119 && !r.title.lowercase().let { it.contains("lyric") || it.contains("dance") || it.contains("choreo") } }
        val pick = videos.firstOrNull { it.artist.equals(song.officialChannel, true) && matches(it.title, it.artist) && full(it) }
            ?: videos.firstOrNull { matches(it.title, it.artist) && full(it) }
            ?: YouTube.Search(song.query, songsOnly = true).nextPage().firstOrNull { matches(it.title, it.artist) && full(it) }
            ?: throw IOException("Couldn't find \"${song.title}\" on YouTube")
        val id = "yt_" + pick.id
        if (!store.has(id)) {
            YouTube.downloadAudio(pick.url, store.audioFile(id)) { p -> progress("Downloading ${song.title}… ${(p * 100).toInt()}%") }
            store.save(Track(id, song.title, song.artist, pick.url, pick.durationSec.toDouble()))
        }
        return id
    }
}

object Tutorials {
    val NELLY_A = TutorialSong("Nelly Air Force Ones", listOf("air force", "nelly"), "Nelly",
        "Air Force Ones", "Nelly ft. Kyjuan, Ali & Murphy Lee")
    val NELLY_B = TutorialSong("Nelly Grillz", listOf("grillz", "nelly"), "Nelly",
        "Grillz", "Nelly ft. Paul Wall, Ali & Gipp")

    /**
     * Mix Nelly's "Air Force Ones" (deck A) into "Grillz" (deck B), learning every main control on
     * the way. Each step checks what you did and moves on by itself; Skip always works too.
     */
    fun nelly(): Tutorial {
        val a = 0; val b = 1
        var scratched = false
        var loopWasOn = false
        var hornFired = false
        var syncWasHit = false
        fun Mixer.da() = decks[a]
        fun Mixer.db() = decks[b]
        val steps = listOf(
            TutorialStep(listOf("info0", "info1"), "Getting the songs",
                "We're putting Nelly – \"Air Force Ones\" on deck A (blue) and \"Grillz\" on deck B (orange). " +
                    "The first time, MixDeck downloads them from YouTube, which takes a minute. Next time they load instantly.",
                done = { m -> m.da().loaded && m.db().loaded }),
            TutorialStep(listOf("info0", "jog0"), "Meet deck A",
                "Deck A has \"Air Force Ones\". The top shows the song, the time left and its tempo in BPM (beats per minute). " +
                    "The BPM is also in the middle of the jog wheel. Hip-hop like this usually sits around 80–100 BPM."),
            TutorialStep(listOf("xfader"), "The crossfader",
                "This slider chooses what the crowd hears: left = deck A, right = deck B, middle = both. " +
                    "Slide it all the way LEFT so only deck A plays.",
                done = { m -> m.crossfader < -0.9f }),
            TutorialStep(listOf("play0"), "Press play",
                "Press ▶ on deck A to start \"Air Force Ones\".",
                done = { m -> m.da().playing }),
            TutorialStep(listOf("lane0", "ov0"), "Reading the waveform",
                "This is the song scrolling past the white line. The tall coloured shapes are the kick drums, and the ticks along " +
                    "the top are the beats. Long ticks are every 4th beat, the start of a bar. The strip above is the whole song: tap it to jump."),
            TutorialStep(listOf("jog0", "vinyl0"), "Scratch!",
                "With VINYL lit, put your finger in the middle of the jog wheel and rub it back and forth like a record. " +
                    "Let go and the song carries on. Give it a go!",
                done = { m -> if (m.da().scratching) scratched = true; scratched && !m.da().scratching }),
            TutorialStep(listOf("hc0_0"), "Hot cues",
                "Hot cues bookmark spots in a song. Tap 1 to save the spot you're at now. " +
                    "Later, tap 1 again and the song jumps straight back there. Great for replaying the hook. (Hold it to clear.)",
                done = { m -> !m.da().hotCues[0].isNaN() }),
            TutorialStep(listOf("hc0_0"), "Jump back",
                "Now tap 1 again: the song jumps back to your bookmark, on the beat."),
            TutorialStep(listOf("loop0_4", "half0", "double0"), "Loops",
                "Tap 4 to repeat the last 4 beats over and over. That's how DJs stretch a part of a song. " +
                    "Try ½ and ×2 to make the loop shorter or longer, then tap 4 again to let the song continue.",
                done = { m -> if (m.da().loopOn) loopWasOn = true; loopWasOn && !m.da().loopOn }),
            TutorialStep(listOf("info1", "jog1"), "Deck B: Grillz",
                "\"Grillz\" is waiting on deck B. Don't press play yet. First we get it ready so it blends in smoothly."),
            TutorialStep(listOf("sync1"), "Match the tempo",
                "The two songs have different tempos. Tap SYNC on deck B and it speeds up or slows down \"Grillz\" " +
                    "to match \"Air Force Ones\". SYNC lights up when they match.",
                done = { m ->
                    val x = m.da(); val y = m.db()
                    if (x.bpm > 0 && y.bpm > 0 && abs(x.effectiveBpm - y.effectiveBpm) < 0.05) syncWasHit = true
                    syncWasHit || x.bpm <= 0 || y.bpm <= 0
                }),
            TutorialStep(listOf("pitch1"), "What SYNC did",
                "See the tempo slider and the % under it? SYNC moved it for you. You can also drag it yourself: " +
                    "down is faster, up is slower. Double-tap it to reset."),
            TutorialStep(listOf("low1"), "Take out the bass",
                "Two kick drums at once sound messy. Drag deck B's LOW knob all the way DOWN (anticlockwise) to remove its bass for now. " +
                    "Knobs turn when you drag up or down.",
                done = { m -> m.db().eqLow < -0.9f }),
            TutorialStep(listOf("phones1"), "Pre-listen (optional)",
                "With headphones in and split cue on (☰ menu), 🎧 lets you hear deck B in your right ear before the crowd does. " +
                    "No headphones? Just tap Next."),
            TutorialStep(listOf("play1", "lane0", "lane1"), "Drop it in",
                "Watch deck A's waveform. When a long tick (the start of a bar) reaches the white line, press ▶ on deck B. " +
                    "If the beats drift apart later, tap SYNC on B again.",
                done = { m -> m.db().playing }),
            TutorialStep(listOf("xfader"), "Blend",
                "Slowly slide the crossfader to the MIDDLE. Now the crowd hears both songs together, in time.",
                done = { m -> abs(m.crossfader) < 0.15f }),
            TutorialStep(listOf("low0", "low1"), "Swap the bass",
                "This is the classic DJ move. On a strong beat, turn deck A's LOW all the way down and deck B's LOW back up to the middle. " +
                    "The bass jumps from \"Air Force Ones\" to \"Grillz\".",
                done = { m -> m.da().eqLow < -0.9f && m.db().eqLow > -0.15f }),
            TutorialStep(listOf("fxtype0", "fx0", "amt0"), "Echo out",
                "Give deck A a send-off: ECHO is selected, so tap FX to switch the echo on. AMT sets how strong it is.",
                done = { m -> m.da().fx.on }),
            TutorialStep(listOf("xfader"), "Finish the mix",
                "Slide the crossfader all the way RIGHT. Deck A fades out with its echo trailing off, and \"Grillz\" takes over.",
                done = { m -> m.crossfader > 0.9f }),
            TutorialStep(listOf("play0", "fx0"), "Tidy up deck A",
                "Stop deck A with ❚❚ and tap FX to switch the echo off. Deck A is now free for the next song.",
                done = { m -> !m.da().playing && !m.da().fx.on }),
            TutorialStep(listOf("pad0", "pad1", "pad2", "pad3"), "Celebrate",
                "Hit the HORN! The sampler pads play sound effects over the mix. Tap one again to stop it.",
                done = { m -> if ((0..3).any { m.sampler.isPlaying(it) }) hornFired = true; hornFired }),
            TutorialStep(listOf("rec", "load0"), "You did it!",
                "That's a full mix: sync, cut the bass, blend, swap, echo out. Next time tap REC first and your set is saved in My mixes. " +
                    "Load another song on deck A and keep going! (Tap ? any time to see what every button does.)"),
        )
        return Tutorial("Nelly: Air Force Ones → Grillz", steps)
    }
}
