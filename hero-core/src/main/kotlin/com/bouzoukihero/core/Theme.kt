package com.bouzoukihero.core

/**
 * What the game is dressed as: the instrument it names, the word shouted when the boost goes off, and how the neck
 * looks. Bouzouki Hero is [BOUZOUKI]; another app plays the same game as its own instrument.
 */
class Theme(
    val instrument: String,
    /** Shouted when the boost (double points) goes off, and on its meter. */
    val boost: String,
    val boostShort: String,
    /** A pair of strings down every lane (a bouzouki's courses), or one. */
    val pairedStrings: Boolean,
    val board: Int,
    val binding: Int,
) {
    // Made once, not every frame.
    val tapBoost = "TAP: $boost"
    val wholePart = "That's the whole $instrument part!"
    val notesVs = "Notes vs. the $instrument: "
    val couldntHear = "Couldn't hear the $instrument."

    companion object {
        val BOUZOUKI = Theme("bouzouki", "OPA!", "OPA", true, 0xFF3A2216.toInt(), 0xFFE9DFC6.toInt())

        /** Wah! is what an Indian audience calls out when a phrase lands. Teak neck, bone-white binding. */
        val SITAR = Theme("sitar", "WAH!", "WAH", false, 0xFF5B3014.toInt(), 0xFFF2E6CC.toInt())
    }
}
