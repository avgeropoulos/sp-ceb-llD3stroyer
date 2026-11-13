package com.mixdeck.ui

import com.mixdeck.engine.Deck
import com.mixdeck.engine.MusicalKey
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How keys look on screen, the same on the phone and the computer: the Camelot code ("8A") in the
 * key wheel's colour. Keys next to each other on the wheel get neighbouring colours, and a key and
 * its relative (8A and 8B) share one, so songs that mix well also look alike.
 */
object KeyDisplay {
    /** Below this key confidence the key isn't shown (the same cut-off as the ranker's). */
    const val MIN_CONFIDENCE = 0.15f

    /** The wheel's colour for [k]: 1 = cyan, 4 = yellow-green, 7 = red, 10 = purple, … */
    fun color(k: MusicalKey): Int = hsv((180f - (k.camelotNumber - 1) * 30f + 360f) % 360f, 0.5f, 1f)

    /**
     * The key a deck sounds in right now, e.g. "8A"; "3A↑" when the tempo slider has moved it up
     * (no key lock: +6% ≈ one semitone); "~8A" when the slider leaves it between two keys.
     * Null when the key is unknown or unreliable.
     */
    fun label(d: Deck): String? = sounding(d.key, d.analysis?.keyConfidence ?: 0f, d.pitch)?.let { (k, shift, between) ->
        (if (between) "~" else "") + k.camelot + when { shift > 0 -> "↑"; shift < 0 -> "↓"; else -> "" }
    }

    /** The colour for [label]'s key, or [fallback]. */
    fun labelColor(d: Deck, fallback: Int): Int =
        sounding(d.key, d.analysis?.keyConfidence ?: 0f, d.pitch)?.let { color(it.first) } ?: fallback

    /** (key you hear, whole semitones it moved, whether it sits between two keys). */
    private fun sounding(key: MusicalKey?, confidence: Float, pitch: Double): Triple<MusicalKey, Int, Boolean>? {
        if (key == null || confidence < MIN_CONFIDENCE) return null
        val st = MusicalKey.semitonesForSpeed(1 + pitch)
        val n = st.roundToInt()
        return Triple(key.transpose(n), n, MusicalKey.wholeSemitones(st) == null)
    }

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
