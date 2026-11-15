package com.sitarsitara.hero

/** Where the music is up to, in song seconds, plus its transport controls. */
interface SongClock {
    fun now(): Double
    fun pause()
    fun resume()
    fun restart()
    fun release()

    /** True once the music has played to the end. */
    val ended: Boolean
}
