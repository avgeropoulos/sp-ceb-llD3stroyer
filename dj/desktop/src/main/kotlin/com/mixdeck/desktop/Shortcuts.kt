package com.mixdeck.desktop

/**
 * The computer's keyboard shortcuts: which key does the same as each console control (named by
 * its tutorial key, e.g. "play0"). The labels (? or H) show them as key caps.
 */
object Shortcuts {
    private val keys = mapOf(
        // Deck A
        "load0" to "A", "cue0" to "Q", "play0" to "W", "sync0" to "E", "loop0_4" to "R", "fx0" to "T",
        "hc0_0" to "1", "hc0_1" to "2", "hc0_2" to "3", "hc0_3" to "4",
        // Deck B
        "load1" to "B", "cue1" to "O", "play1" to "P", "sync1" to "I", "loop1_4" to "U", "fx1" to "Y",
        "hc1_0" to "7", "hc1_1" to "8", "hc1_2" to "9", "hc1_3" to "0",
        // Top bar
        "next" to "N", "mix" to "M", "help" to "H",
        // Mixer
        "xfader" to "← ↓ →", "pad0" to "Z", "pad1" to "X", "pad2" to "C", "pad3" to "V",
    )

    /** "Ctrl+R", or "⌘R" on a Mac: start or stop recording. */
    fun record(mac: Boolean = Install.isMac) = if (mac) "⌘R" else "Ctrl+R"

    /** The key for the console control called [control], or null when it has none. */
    fun of(control: String, mac: Boolean = Install.isMac): String? = if (control == "rec") record(mac) else keys[control]

}
