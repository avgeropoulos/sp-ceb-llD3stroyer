package com.critters.game

/** Turns the game's all-caps screen text into words a text-to-speech voice reads well. */
object Speech {
    private val money = Regex("\\$(\\d+)")
    private val level = Regex(":L(\\d+)")
    private val count = Regex("\\bX(\\d+)\\b")
    private val spaces = Regex("\\s+")

    fun clean(text: String): String {
        var s = text.replace('\n', ' ').replace("^", "").replace(">", "")
        s = money.replace(s) { "${it.groupValues[1]} dollars" }
        s = level.replace(s) { " level ${it.groupValues[1]}" }
        s = count.replace(s) { "times ${it.groupValues[1]}" }
        s = s.replace("PROF.", "PROFESSOR")
            .replace(Regex("\\bHP\\b"), "H.P.")
            .replace(Regex("\\bPP\\b"), "P.P.")
            .replace(Regex("\\bPC\\b"), "P.C.")
            .replace(Regex("\\bXP\\b"), "X.P.")
        return spaces.replace(s.lowercase(), " ").trim()
    }
}
