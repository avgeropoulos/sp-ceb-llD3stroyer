package com.mixdeck.engine

/**
 * Decoded stereo audio for one track. Samples are floats in -1..1.
 * Implementations decide how the data is stored (a ShortArray in tests, an off-heap
 * buffer on the phone so two long songs do not blow the Java heap).
 */
interface Pcm {
    val sampleRate: Int
    val frames: Int
    fun left(frame: Int): Float
    fun right(frame: Int): Float
}

/** Interleaved 16-bit stereo held in a ShortArray. */
class ShortArrayPcm(private val data: ShortArray, override val sampleRate: Int) : Pcm {
    override val frames: Int = data.size / 2
    override fun left(frame: Int): Float = data[frame * 2] * (1f / 32768f)
    override fun right(frame: Int): Float = data[frame * 2 + 1] * (1f / 32768f)

    companion object {
        /** Builds a track from separate float channels (handy for tests and synthesised sounds). */
        fun fromFloats(left: FloatArray, right: FloatArray = left, sampleRate: Int): ShortArrayPcm {
            val out = ShortArray(left.size * 2)
            for (i in left.indices) {
                out[i * 2] = toShort(left[i])
                out[i * 2 + 1] = toShort(right[i])
            }
            return ShortArrayPcm(out, sampleRate)
        }

        fun toShort(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }
}
