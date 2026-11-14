package com.mixdeck.ui

import com.mixdeck.engine.AutoMix
import com.mixdeck.engine.Mixer

/**
 * Picking a song never stops the music by surprise. When a song is picked for a deck that is
 * playing, [question] asks where it should go instead; the same words on the phone and the computer.
 */
object PickCheck {
    enum class Option { OTHER_DECK, REPLACE, WHEN_ENDS, CANCEL }

    /** What to ask. [options] are in button order; the first one is the default. */
    class Question(val title: String, val message: String, val options: List<Option>, private val labels: Map<Option, String>) {
        fun label(o: Option): String = labels.getValue(o)
        val labelList get() = options.map(::label)
        override fun toString() = "$title / $message / $labelList"
    }

    /**
     * The question for loading "[title]" onto [deck], or null when there's nothing to ask (the deck
     * isn't playing: load it now). [otherTitle] is the song on the other deck, if it holds one.
     * [needsDownload]: the song isn't saved yet. Then "Stop A and load" can't happen now: with the
     * other deck free it becomes "Load when A ends" (stopping A once the download is done would
     * cut the music off at a random moment); with both decks playing it stops A once the song is
     * downloaded (the other deck keeps the music going): "Stop A after the download".
     * Read under the mixer lock, or accept a value one audio block old.
     */
    fun question(
        mixer: Mixer, deck: Int, title: String, needsDownload: Boolean = false,
        otherTitle: String? = mixer.decks[1 - deck].takeIf { it.loaded }?.title,
    ): Question? {
        if (!live(mixer, deck)) return null
        val me = Console.deckName(deck); val other = Console.deckName(1 - deck)
        val song = "\"$title\""
        val left = Console.fmtTime(secondsLeft(mixer, deck))
        if (!live(mixer, 1 - deck) && !mixStarting(mixer, 1 - deck)) {
            val comesOff = if (otherTitle != null) "; \"$otherTitle\" comes off it." else "."
            val second = if (needsDownload) Option.WHEN_ENDS else Option.REPLACE
            return Question(
                "Deck $me is playing ($left left)",
                "Load $song on deck $other instead? Deck $other isn't playing$comesOff",
                listOf(Option.OTHER_DECK, second, Option.CANCEL),
                mapOf(Option.OTHER_DECK to "Load on deck $other", Option.REPLACE to "Stop $me and load",
                    Option.WHEN_ENDS to "Load when $me ends", Option.CANCEL to "Cancel"),
            )
        }
        return Question(
            "Both decks are playing",
            "Deck $me ends in $left. Load $song there when it ends?",
            listOf(Option.WHEN_ENDS, Option.REPLACE, Option.CANCEL),
            mapOf(Option.WHEN_ENDS to "Load when $me ends",
                Option.REPLACE to if (needsDownload) "Stop $me after the download" else "Stop $me and load now",
                Option.CANCEL to "Cancel"),
        )
    }

    /** Deck [k] is really playing (holding CUE to pre-listen doesn't count). */
    fun live(mixer: Mixer, k: Int) = mixer.decks[k].let { it.playing && !it.previewing }

    /**
     * MIX is about to start deck [k] (it waits for the bar): the question treats it as playing, so
     * a pick made then never lands on the song MIX is bringing in.
     */
    private fun mixStarting(mixer: Mixer, k: Int) =
        mixer.autoMix.let { it.state == AutoMix.State.ARMED && it.to == k }

    /**
     * Real seconds left on deck [k] at its current tempo; while MIX is mixing out of [k], the time
     * until MIX stops it, if that comes first.
     */
    fun secondsLeft(mixer: Mixer, k: Int): Double {
        val d = mixer.decks[k]
        val song = (d.durationSec - d.positionSec).coerceAtLeast(0.0) / (1 + d.pitch)
        val am = mixer.autoMix
        val mix = if (am.from == k) am.secondsToFinish() else null
        return if (mix != null) minOf(song, mix) else song
    }

    /**
     * The library's countdown, shown while a deck plays: "Deck A ends in 0:43" when the other deck
     * plays, "it ends in 0:43" when [deck] itself does; null when neither plays. [Countdown.urgent]
     * under 30 seconds (shown in red).
     */
    fun countdown(mixer: Mixer, deck: Int): Countdown? {
        val k = when {
            live(mixer, deck) -> deck
            live(mixer, 1 - deck) -> 1 - deck
            else -> return null
        }
        val left = secondsLeft(mixer, k)
        val who = if (k == deck) "it" else "Deck ${Console.deckName(k)}"
        return Countdown("$who ends in ${Console.fmtTime(left)}", left < 30)
    }

    class Countdown(val text: String, val urgent: Boolean)
}
