package com.bouzouki.core.song

/**
 * Takes a song (44.1 kHz stereo) down to the part the bouzouki line is transcribed from, in mono: without the
 * voice, or only the instruments the bouzouki sounds among ([MdxSeparator], [DemucsSeparator]), or just the
 * recording itself for a bouzouki playing alone ([NoSeparation]). [progress] gets 0..1; [cancelled] is polled.
 */
interface Separator {
    fun instrumentalMono(left: FloatArray, right: FloatArray, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): FloatArray

    companion object {
        /** The rate every separator takes and gives (and the song is decoded to). */
        const val SAMPLE_RATE = 44100
    }
}

/** A recording of the bouzouki alone (a practice take, a calibration): nothing to separate, only mixed to mono. */
object NoSeparation : Separator {
    override fun instrumentalMono(left: FloatArray, right: FloatArray, progress: (Float) -> Unit, cancelled: () -> Boolean): FloatArray {
        val n = minOf(left.size, right.size)
        progress(1f)
        return FloatArray(n) { 0.5f * (left[it] + right[it]) }
    }
}
