package com.mixdeck

/** When the phone and computer updaters may speak up, shared so both apps behave the same. */
object UpdatePolicy {
    /** While the app stays open, automatic checks run at most this often (6 hours). */
    const val RECHECK_MS = 6L * 60 * 60 * 1000

    /**
     * Whether to pop up the offer for version [code]. [manual] means the user asked (☰), so always.
     * Otherwise never over the music or a recording ([busy]), during the tutorial or the newcomer
     * Start card ([quiet]), or for a version the user already said "Later" to ([laterVersion]).
     * The ☰ badge and menu row show the update either way.
     */
    fun shouldOffer(manual: Boolean, code: Int, laterVersion: Int, busy: Boolean, quiet: Boolean): Boolean =
        manual || (!busy && !quiet && code > laterVersion)

    /** After a check that failed (offline), the next automatic try waits at least this long (10 minutes). */
    const val RETRY_MS = 10L * 60 * 1000

    /**
     * An automatic check is due when more than [RECHECK_MS] passed since the last successful one,
     * [lastCheck] (or the clock went back), and the last try, [lastTry], was more than [RETRY_MS] ago.
     */
    fun checkDue(now: Long, lastCheck: Long, lastTry: Long = 0L): Boolean =
        (now - lastCheck > RECHECK_MS || now < lastCheck) && (now - lastTry > RETRY_MS || now < lastTry)

    /** Where the phone's update is: found, downloading in the background, or downloaded and ready to install. */
    enum class Stage { AVAILABLE, DOWNLOADING, DOWNLOADED }

    /** The phone's ☰ row for an update to [name] at [stage]; [percent] counts while downloading. */
    fun phoneMenuLabel(stage: Stage, name: String, percent: Int = 0): String = when (stage) {
        Stage.AVAILABLE -> "⬆ Update to $name"
        Stage.DOWNLOADING -> "⬆ Downloading $name… ${percent.coerceIn(0, 100)}%"
        Stage.DOWNLOADED -> "⬆ Install $name"
    }

    /**
     * When to install (phone) or restart (computer) a downloaded update whose question was held
     * back: after what's going on, music [playing], a [recording] or a mix [saving], or "now" when
     * only the tutorial or the Start card held it back.
     */
    fun readyWhen(playing: Boolean, recording: Boolean, saving: Boolean): String = when {
        playing -> "when the music stops"
        recording -> "once you stop recording"
        saving -> "once your mix is saved"
        else -> "now"
    }

    /** Release notes as a "What's new: …" paragraph, or "" when there are none. */
    fun whatsNew(notes: String): String =
        notes.trim().removePrefix("What's new:").trim().let { if (it.isEmpty()) "" else "What's new: $it" }
}
