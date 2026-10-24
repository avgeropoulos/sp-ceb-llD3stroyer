package com.bouzoukihero.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Where each note is played on a tetrachordo (C3 F3 A3 D4) bouzouki: a port of
 * Bouzouki Tabs' `fretboard.py`, so the game's buttons follow the same tab that app
 * writes. Each note gets a course and a fret, picked for the whole melody at once
 * (Viterbi) to keep the hand still, favour the top two courses where bouzouki
 * melodies live, and only shift position when there's time to.
 */
object Fretboard {
    class Position(val course: Int, val fret: Int)

    /** Course labels and open-string notes, lowest course first. */
    val labels = listOf("C", "F", "A", "D")
    val openMidi = intArrayOf(48, 53, 57, 62)
    /** How willing a player is to use each course for melody (0 = happiest). */
    private val coursePenalty = doubleArrayOf(0.9, 0.45, 0.1, 0.0)
    const val MAX_FRET = 24

    val lowest get() = openMidi.min()
    val highest get() = openMidi.max() + MAX_FRET

    /** Folds a pitch into the playable range by octaves. */
    fun fitToRange(midi: Int): Int {
        var m = midi
        while (m < lowest) m += 12
        while (m > highest) m -= 12
        return m
    }

    private fun candidates(midi: Int) = openMidi.indices
        .filter { midi - openMidi[it] in 0..MAX_FRET }
        .map { Position(it, midi - openMidi[it]) }

    private fun positionCost(p: Position) = coursePenalty[p.course] + 0.04 * p.fret

    private fun transitionCost(a: Position, b: Position, gap: Double): Double {
        var cost = 0.15 * abs(a.course - b.course)
        if (a.fret != 0 && b.fret != 0) {
            // Within a 4-fret span the hand doesn't need to move.
            val shift = max(0, abs(a.fret - b.fret) - 3)
            // Shifting is easier when there's time to do it.
            cost += shift * 0.6 / (1.0 + gap / 0.25)
        }
        return cost
    }

    /** A course and fret for each note, minimizing hand movement. Pitches must be in range. */
    fun assign(midis: List<Int>, times: List<Double>): List<Position> {
        if (midis.isEmpty()) return emptyList()
        val layers = midis.map { m -> candidates(m).also { require(it.isNotEmpty()) { "$m is outside the bouzouki's range" } } }
        var costs = layers[0].map { positionCost(it) }
        val back = ArrayList<IntArray>()
        for (i in 1 until layers.size) {
            val gap = max(0.0, times[i] - times[i - 1])
            val prev = layers[i - 1]
            val newCosts = ArrayList<Double>(layers[i].size)
            val pointers = IntArray(layers[i].size)
            for ((k, pos) in layers[i].withIndex()) {
                var bestJ = 0
                var best = Double.MAX_VALUE
                for (j in prev.indices) {
                    val c = costs[j] + transitionCost(prev[j], pos, gap)
                    if (c < best) { best = c; bestJ = j }
                }
                pointers[k] = bestJ
                newCosts.add(best + positionCost(pos))
            }
            costs = newCosts
            back.add(pointers)
        }
        var j = costs.indices.minBy { costs[it] }
        val path = IntArray(layers.size)
        path[layers.size - 1] = j
        for (i in back.indices.reversed()) {
            j = back[i][j]
            path[i] = j
        }
        return layers.indices.map { layers[it][path[it]] }
    }
}
