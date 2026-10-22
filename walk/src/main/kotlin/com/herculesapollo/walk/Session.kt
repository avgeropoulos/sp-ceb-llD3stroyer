package com.herculesapollo.walk

/** What the scenery renderer needs from a game in progress. */
interface WalkScene {
    val course: Course
    val camX: Float
    val viewW: Float
    /** Seconds of play, for animations. */
    val time: Float
}

/** A walk in progress, for the HUD and the end-of-walk screens. Shared by both game modes. */
interface WalkSession : WalkScene {
    val walkIndex: Int
    val score: Int
    val hearts: Int
    val chased: Int
    val treatsGot: Int
    val bonus: Int
    val best: Int
    val stateTime: Float

    /** Line under the walk's name on its intro card. */
    val introLine: String
    /** Line under "ALL WALKS DONE!". */
    val doneLine: String

    /** 1 to 3 stars for how many critters got chased on this walk. */
    val stars get() = course.total.let { total ->
        val r = if (total == 0) 1f else chased.toFloat() / total
        when { r >= 0.8f -> 3; r >= 0.5f -> 2; else -> 1 }
    }
}
