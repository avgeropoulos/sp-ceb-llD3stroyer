package com.mixdeck.ui

/** The rows of the ☰ menu, the same on the phone and the computer. */
enum class MenuItem { UPDATE, LEARN, HELP, MIXES, SETTINGS, MIDI, CHECK }

/** The ☰ menu and the Settings panel's words, shared by both apps so they never drift apart. */
object Menus {
    /** The ☰ menu's title, e.g. "MixDeck 1.123". */
    fun title(version: String) = "MixDeck $version"

    /**
     * The ☰ menu, top to bottom: the update row ([updateLabel], the updater's menu label, when
     * there is update news), the tutorial, the guide, My mixes, Settings, the MIDI controller setup, and "Check for updates"
     * when there's no update news (the update row does that job then).
     */
    fun mainMenu(updateLabel: String?, version: String, computer: Boolean): List<Pair<MenuItem, String>> = listOfNotNull(
        updateLabel?.let { MenuItem.UPDATE to it },
        MenuItem.LEARN to "▶ Learn to mix (guided, about 5 min)",
        MenuItem.HELP to if (computer) "Help & guide  (F1)" else "Help & guide",
        MenuItem.MIXES to "My mixes",
        MenuItem.SETTINGS to "Settings…",
        MenuItem.MIDI to com.mixdeck.midi.MidiRouter.MENU,
        if (updateLabel == null) MenuItem.CHECK to "Check for updates (you have $version)" else null,
    )

    /** The Settings panel (☰ → Settings…). Every change applies at once. */
    object Settings {
        const val TITLE = "Settings"
        const val DONE = "Done"
        const val SOUND = "Sound"
        const val MASTER = "Master volume"
        const val SAMPLER = "Sampler volume"
        const val MIXING = "Mixing"
        const val CROSSFADER = "Crossfader"
        const val SMOOTH = "Smooth (mixing)"
        const val SHARP = "Sharp (scratching)"
        const val QUANTIZE = "Quantize: ▶, cues and loops snap to the beat"
        const val MIX_LENGTH = "MIX length"
        const val SPLIT_CUE = "Split cue (headphones): left ear = mix, right ear = 🎧 decks"
        const val SPLIT_CUE_NOTE = "Headphones only: a speaker would play the cue too."
        const val CHECK = "Check for updates"
        const val OPEN_MIXES = "Open mixes folder"
        const val PERSONAL_USE = "YouTube downloads are for personal use only."

        /** "85%" next to a volume slider. */
        fun percent(v: Float) = "${(v * 100).toInt()}%"

        /** The choices for MIX's length, e.g. "8 bars". */
        fun barsLabel(bars: Int) = "$bars bars"

        /** The caption under the MIX length choices. */
        fun mixCaption(bars: Int) = "MIX blends over $bars bars"

        fun songsFolder(path: String) = "Songs: $path"
        fun mixesFolder(path: String) = "Mixes: $path"
    }

    /** The question 🎧 asks when split cue is off. */
    object SplitCue {
        const val TITLE = "Hear 🎧 in your headphones?"
        const val MESSAGE = "Pre-listen needs split cue. With headphones plugged in, your LEFT ear hears the mix and your " +
            "RIGHT ear hears the decks with 🎧 lit, so you can get the next song ready before anyone hears it.\n\n" +
            "Don't use it through a speaker: the speaker would play the cue too."
        const val TURN_ON = "Turn on split cue"
        const val NOT_NOW = "Not now"
    }

    /** The footer of the guide on both apps. */
    fun guideFooter(version: String) = "MixDeck $version · YouTube downloads are for personal use only."

    // ------------------------------------------------------------------ recording

    /** Recordings shorter than this are thrown away when REC stops. */
    const val MIN_RECORDING_SECONDS = 3.0

    /** REC tapped while recording; on a computer, [key] (Ctrl+R or ⌘R) stops it too. */
    fun recordingHint(seconds: Double, key: String? = null) =
        "Recording ${Console.fmtTime(seconds)} · hold REC${key?.let { " or press $it" } ?: ""} to stop"

    /** A recording too short to keep was thrown away. */
    fun tooShort(seconds: Double) = "Recording too short to keep (${seconds.toInt()} s)"

    /** A mix was saved. */
    fun savedMix(name: String, seconds: Double) = "Saved \"$name\" (${Console.fmtTime(seconds)}) · rename it in ☰ → My mixes"

    /** A mix the last run never saved (it was closed or crashed while recording) is saved now. */
    fun recoveredMix(name: String, seconds: Double) =
        "Saved your unsaved mix from last time as \"$name\" (${Console.fmtTime(seconds)}) · it's in ☰ → My mixes"

    /** Saving a mix failed ([detail]: what went wrong). The recording is kept and saved at the next start. */
    fun saveFailed(detail: String?) =
        "Couldn't save the mix (${detail?.trim()?.removeSuffix(".") ?: "unknown error"}). It's kept: MixDeck tries again next time it opens"

    /** REC pressed while an earlier mix is still saving ([progress]: "SAVING" or "37%"): it works again once that's saved. */
    fun recWaitsForSave(progress: String) = "Saving your last mix ($progress) · REC works again once it's saved"

    /** Recording stopped by itself after [seconds]: the phone or [computer] ran out of space. What was recorded is saved. */
    fun recordingStopped(seconds: Double, computer: Boolean = false) =
        "Recording stopped at ${Console.fmtTime(seconds)}: ${if (computer) "the disk is full" else "the phone is out of space"}. " +
            "Saving what was recorded"

    // ------------------------------------------------------------------ tutorial start

    /**
     * What starting the tutorial does to what's going on, or null when there's nothing to ask
     * about: a deck [playing] stops, a mix [recording] is saved first (or the tutorial would be
     * recorded into it), and songs still downloading or up next ([picksWaiting]) are dropped.
     */
    fun tutorialStart(playing: Boolean, recording: Boolean, picksWaiting: Boolean): String? {
        if (!playing && !recording && !picksWaiting) return null
        val songs = "Nelly's \"Air Force Ones\" and \"Grillz\""
        return listOfNotNull(
            if (playing) "This stops both decks and loads $songs." else "This loads $songs onto the decks.",
            if (recording) "Your recording stops and is saved in My mixes first." else null,
            if (picksWaiting) "Songs still downloading or up next are dropped." else null,
        ).joinToString(" ")
    }

    // ------------------------------------------------------------------ tutorial songs

    /** The tutorial couldn't get its songs: what went wrong ([why], Problems.explain) and what to do (on a [computer]: click). */
    fun tutorialSongsFailed(why: String, computer: Boolean = false): String {
        val tap = if (computer) "click" else "tap"
        return "Couldn't get the songs (${why.trim().removeSuffix(".")}). Check your internet and $tap Try again, " +
            "or $tap the lit LOAD buttons and search \"Nelly Air Force Ones\" and \"Nelly Grillz\"."
    }
}
