package com.sitarsitara.core

/**
 * One note of the sitar line as the song analysis found it, in seconds of the recording. [meend]: reached from
 * the note before without a new stroke (the string pulled, or the left hand alone: meend, krintan). [fast]: fast
 * repeated strokes on the note (diri, jhala). [confidence]: how sure the analysis is the sitar plays it, 0..1.
 */
data class LineNote(
    val start: Double,
    val end: Double,
    val midi: Int,
    val meend: Boolean = false,
    val fast: Boolean = false,
    val confidence: Float = 1f,
) {
    val duration get() = end - start
}

/** A [LineNote] read against Sa: its [swara] and the [string] it is played on. */
data class SitarNote(val note: LineNote, val midi: Int, val swara: Swara, val string: SitarString) {
    val start get() = note.start
    val end get() = note.end

    /** "~G", "S'*": the swara with its octave marks, ~ for meend before it and * for fast strokes after it. */
    fun text(script: Script = Script.LATIN) = (if (note.meend) "~" else "") + swara.text(script) + (if (note.fast) "*" else "")
}

/**
 * A sitar line read as sargam: where Sa is ([saPc], [sa]; [chosen] when the player set it, else worked out from
 * the notes), the likeliest scales ([guesses], best first) and every note named from Sa. [folded] notes lay
 * outside the sitar's range and were moved by an octave.
 */
class Reading(
    val saPc: Int,
    val chosen: Boolean,
    val guesses: List<ScaleGuess>,
    val notes: List<SitarNote>,
    val folded: Int,
) {
    val sa get() = Sargam.saMidi(saPc)
    val saName get() = Sargam.noteName(saPc)
    val best get() = guesses.firstOrNull()

    companion object {
        /** Where most sitars are tuned, for a line with no notes to judge by. */
        const val DEFAULT_SA = 1 // C#

        /**
         * Reads [line] against Sa on pitch class [saPc], or the Sa its notes point to when that is null. Notes the
         * analysis is less sure of than [minConfidence] are left out.
         */
        fun of(line: List<LineNote>, saPc: Int? = null, minConfidence: Float = 0f): Reading {
            val kept = line.filter { it.confidence >= minConfidence }.sortedBy { it.start }
            // Each note counts for as long as it sounds and as sure as the analysis is of it.
            val guesses = Thaat.detect(kept.map { it.midi }, kept.map { it.duration * it.confidence }, saPc)
            val pc = saPc?.let { Math.floorMod(it, 12) } ?: guesses.firstOrNull()?.saPc ?: DEFAULT_SA
            val sa = Sargam.saMidi(pc)
            var folded = 0
            val notes = kept.map { n ->
                val (m, moved) = Sargam.fitToRange(n.midi, sa)
                if (moved) folded++
                SitarNote(n, m, Sargam.swara(m, sa), Sargam.string(m, sa) ?: SitarString.KHARAJ)
            }
            return Reading(pc, saPc != null, guesses, notes, folded)
        }
    }
}
