package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads sheet music (standard notation on 5-line staves, no tab) from the pages of a PDF, for [PdfTab]: as Sibelius,
 * MuseScore, Finale, Dorico or LilyPond export it, and turns its notes into a bouzouki tab with the app's own
 * fingering, so a score lines up with a recording ([TabAligner]) and shows as tablature like any tab.
 *
 * 1. Staves are the 5-line runs [PdfTab] finds. Staves joined by a barline make one system; when a system has several
 *    (a voice over the bouzouki, a piano's two hands), only the top one is read, with a warning.
 * 2. The music font's glyphs are recognised by font and character: Sibelius's Opus (and the other Sonata-style fonts,
 *    Finale's Maestro and Petrucci, Helsinki, Inkpen, Reprise), the SMuFL fonts (Bravura, Leland, Petaluma, MuseJazz,
 *    Finale Maestro's SMuFL version, by code point) and LilyPond's Emmentaler (by glyph name). Noteheads may also be
 *    filled ellipses drawn as shapes (a score whose text was turned into outlines).
 * 3. A note's pitch is its height on the staff, read with the clef (treble, treble 8vb, bass, C clefs; clef changes),
 *    the key signature (and key changes), and accidentals, which hold to the end of the bar (courtesy ones agree).
 *    A tied note (a curve to the same note) isn't picked again, so it is left out. Notes on one stem are a chord: the
 *    top one is the melody, as a tab's chords are kept.
 * 4. Rhythm: each note's length from its head (black, half, whole), beams and flags on its stem, dots and tuplet
 *    numbers; rests; time signatures and barlines. A bar whose lengths don't add up to its time signature has its notes
 *    placed by where they are printed in it. Notes become columns of one timeline, [COLUMNS_PER_BEAT] per crotchet.
 * 5. Octave: bouzouki music is written in the treble clef, often meaning an octave lower than written. The notes are
 *    read at the octave (as written, or octaves away) where most fit the bouzouki and sit on its D and A courses, which
 *    the summary says; any still out of reach are moved an octave.
 * 6. Strings and frets come from [Fingering] (the app's own choice for a melody), on the player's bouzouki; the score's
 *    form (repeats, endings, D.S.) comes from [PdfForm], as for a tab.
 */
internal object PdfNotation {
    /** Where the reader says what it found, bar by bar (for tests and tuning); null normally. */
    internal var log: ((String) -> Unit)? = null
    /** Columns of the tab's timeline per crotchet: a quaver is 2, as a text tab writes them about. */
    private const val COLUMNS_PER_BEAT = 4
    /** The tempo assumed when the score has no metronome mark, crotchets per minute. */
    private const val DEFAULT_BPM = 100.0
    /** Caps that keep a hostile file from making the reading slow (real staves are far below them). */
    private const val MAX_SYMBOLS_PER_STAFF = 5_000
    private const val MAX_NOTES = 50_000
    private const val MAX_TUPLETS = 300
    /** The longest beam looked at, in line spacings. */
    private const val MAX_BEAM = 60.0
    /** How far above and below a staff its notes may be (ledger lines), in line spacings. */
    private const val REACH = 6.0

    // ---- What the glyphs are ----

    private enum class Kind { HEAD, CLEF, ACCIDENTAL, REST, FLAG, DOT, TIME, DIGIT }

    /**
     * A music symbol on the page: its kind, extent, the height its meaning is read at ([y]: a glyph's baseline, a
     * shape's middle) and size. [v]: a head's type (0 black, 1 half, 2 whole, 3 breve), a clef's sign (0 G, 1 F, 2 C),
     * an accidental's alteration (0 natural), a rest's length in 64ths of a crotchet, a flag's number of tails, a digit's
     * value (a time signature's common time 100, cut time 101). [v2]: a clef's octave (-1 for an 8 below).
     */
    private class Sym(val kind: Kind, val x0: Double, val x1: Double, val y: Double, val size: Double, val v: Int, val v2: Int = 0) {
        val cx get() = (x0 + x1) / 2
    }

    private const val G = 0
    private const val F = 1
    private const val C = 2
    private const val COMMON = 100
    private const val CUT = 101

    /** Fonts whose glyphs are laid out as Sonata's (the classic music font): Opus, Maestro, Petrucci and their kin. */
    private val SONATA = listOf("opus", "helsinki", "inkpen", "reprise", "norfolk", "maestro", "petrucci", "sonata", "engraver",
        "broadway", "jazz", "goldenage", "toccata", "susato", "tamburo", "anastasia")
    /** Parts of those families' names that aren't notes (text, chord symbols and the like). */
    private val NOT_NOTES = listOf("text", "chord", "figured", "function", "roman", "metronome", "percussion", "ornament",
        "lyric", "tab", "tuplet", "symbol", "sans")

    private fun family(font: String) = font.substringAfter('+').lowercase().filter { it.isLetterOrDigit() }

    /** What [g] draws, or null if it isn't a music symbol read here. */
    private fun classify(g: PdfGlyph): Sym? {
        val t = g.text
        val cp = if (t.isNotEmpty()) t.codePointAt(0) else -1
        val font = family(g.font)
        fun sym(kind: Kind, v: Int, v2: Int = 0) = Sym(kind, g.x0, g.x1, g.baseline, g.size, v, v2)
        if (t.length == 1 && cp in 0xE000..0xF8FF) smufl(cp)?.let { (k, v, v2) -> return sym(k, v, v2) }
        g.name?.let { lily(it) }?.let { (k, v, v2) -> return sym(k, v, v2) }
        val lilyFont = "emmentaler" in font || "feta" in font || "lilyjazz" in font
        if (SONATA.any { font.startsWith(it) } && NOT_NOTES.none { it in font }) {
            if ("special" in font || "extra" in font) {
                return if (t == "™" || t == ".") sym(Kind.DOT, 0) else null
            }
            val c = t.singleOrNull()?.takeIf { sonata(it) != null }
                ?: g.code.takeIf { it in 0..255 }?.let { PdfEncodings.MAC_ROMAN[it]?.singleOrNull() }?.takeIf { sonata(it) != null }
            c?.let { sonata(it) }?.let { (k, v, v2) -> return sym(k, v, v2) }
        }
        if (t.length == 1 && t[0] in '0'..'9') return sym(if (lilyFont) Kind.TIME else Kind.DIGIT, t[0] - '0')
        return null
    }

    /** A SMuFL code point's meaning (Bravura, Leland, Petaluma, MuseJazz, Finale Maestro). */
    private fun smufl(cp: Int): Triple<Kind, Int, Int>? = when (cp) {
        0xE0A0, 0xE0A1 -> Triple(Kind.HEAD, 3, 0)
        0xE0A2 -> Triple(Kind.HEAD, 2, 0)
        0xE0A3 -> Triple(Kind.HEAD, 1, 0)
        0xE0A4 -> Triple(Kind.HEAD, 0, 0)
        0xE050, 0xE07A -> Triple(Kind.CLEF, G, 0)
        0xE051 -> Triple(Kind.CLEF, G, -2)
        0xE052 -> Triple(Kind.CLEF, G, -1)
        0xE053 -> Triple(Kind.CLEF, G, 1)
        0xE054 -> Triple(Kind.CLEF, G, 2)
        0xE05C, 0xE07B -> Triple(Kind.CLEF, C, 0)
        0xE05D -> Triple(Kind.CLEF, C, -1)
        0xE062, 0xE07C -> Triple(Kind.CLEF, F, 0)
        0xE063 -> Triple(Kind.CLEF, F, -2)
        0xE064 -> Triple(Kind.CLEF, F, -1)
        0xE065 -> Triple(Kind.CLEF, F, 1)
        0xE066 -> Triple(Kind.CLEF, F, 2)
        0xE260 -> Triple(Kind.ACCIDENTAL, -1, 0)
        0xE261 -> Triple(Kind.ACCIDENTAL, 0, 0)
        0xE262 -> Triple(Kind.ACCIDENTAL, 1, 0)
        0xE263 -> Triple(Kind.ACCIDENTAL, 2, 0)
        0xE264 -> Triple(Kind.ACCIDENTAL, -2, 0)
        in 0xE4E2..0xE4EA -> Triple(Kind.REST, 512 shr (cp - 0xE4E2), 0)
        in 0xE240..0xE24F -> Triple(Kind.FLAG, (cp - 0xE240) / 2 + 1, 0)
        0xE1E7 -> Triple(Kind.DOT, 0, 0)
        in 0xE080..0xE089 -> Triple(Kind.TIME, cp - 0xE080, 0)
        0xE08A -> Triple(Kind.TIME, COMMON, 0)
        0xE08B -> Triple(Kind.TIME, CUT, 0)
        in 0xE880..0xE889 -> Triple(Kind.DIGIT, cp - 0xE880, 0)
        else -> null
    }

    /** A Sonata-style font's character's meaning (Opus, Maestro, Petrucci ...). */
    private fun sonata(c: Char): Triple<Kind, Int, Int>? = when (c) {
        'œ' -> Triple(Kind.HEAD, 0, 0)
        '˙' -> Triple(Kind.HEAD, 1, 0)
        'w' -> Triple(Kind.HEAD, 2, 0)
        'W' -> Triple(Kind.HEAD, 3, 0)
        '&' -> Triple(Kind.CLEF, G, 0)
        'V' -> Triple(Kind.CLEF, G, -1)
        '?' -> Triple(Kind.CLEF, F, 0)
        'B' -> Triple(Kind.CLEF, C, 0)
        '#' -> Triple(Kind.ACCIDENTAL, 1, 0)
        'b' -> Triple(Kind.ACCIDENTAL, -1, 0)
        'n' -> Triple(Kind.ACCIDENTAL, 0, 0)
        'Ü' -> Triple(Kind.ACCIDENTAL, 2, 0)
        'º' -> Triple(Kind.ACCIDENTAL, -2, 0)
        '∑', '·' -> Triple(Kind.REST, 256, 0)
        'Ó' -> Triple(Kind.REST, 128, 0)
        'Œ' -> Triple(Kind.REST, 64, 0)
        '‰' -> Triple(Kind.REST, 32, 0)
        '≈' -> Triple(Kind.REST, 16, 0)
        '®' -> Triple(Kind.REST, 8, 0)
        'j', 'J' -> Triple(Kind.FLAG, 1, 0)
        'r', 'R' -> Triple(Kind.FLAG, 2, 0)
        '.', '™' -> Triple(Kind.DOT, 0, 0)
        in '0'..'9' -> Triple(Kind.TIME, c - '0', 0)
        'c' -> Triple(Kind.TIME, COMMON, 0)
        'C' -> Triple(Kind.TIME, CUT, 0)
        else -> null
    }

    /** A LilyPond (Emmentaler, feta) glyph name's meaning. */
    private fun lily(name: String): Triple<Kind, Int, Int>? {
        val n = name.lowercase()
        return when {
            n.startsWith("noteheads.sm1") -> Triple(Kind.HEAD, 3, 0)
            n.startsWith("noteheads.s0") -> Triple(Kind.HEAD, 2, 0)
            n.startsWith("noteheads.s1") -> Triple(Kind.HEAD, 1, 0)
            n.startsWith("noteheads.s2") -> Triple(Kind.HEAD, 0, 0)
            n == "clefs.gg" || n == "clefs.tenorg" -> Triple(Kind.CLEF, G, -1)
            n.startsWith("clefs.g") -> Triple(Kind.CLEF, G, 0)
            n.startsWith("clefs.f") -> Triple(Kind.CLEF, F, 0)
            n.startsWith("clefs.c") -> Triple(Kind.CLEF, C, 0)
            n.startsWith("accidentals.doublesharp") -> Triple(Kind.ACCIDENTAL, 2, 0)
            n.startsWith("accidentals.flatflat") -> Triple(Kind.ACCIDENTAL, -2, 0)
            n.startsWith("accidentals.sharp") -> Triple(Kind.ACCIDENTAL, 1, 0)
            n.startsWith("accidentals.flat") -> Triple(Kind.ACCIDENTAL, -1, 0)
            n.startsWith("accidentals.natural") -> Triple(Kind.ACCIDENTAL, 0, 0)
            n.startsWith("rests.") -> {
                val d = n.removePrefix("rests.").trimEnd('o')
                val k = if (d == "m1") -1 else d.toIntOrNull() ?: return null
                if (k in -1..6) Triple(Kind.REST, 256 shr k.coerceAtLeast(0) shl (if (k < 0) 1 else 0), 0) else null
            }
            n.startsWith("flags.u") || n.startsWith("flags.d") -> n.substring(7).toIntOrNull()?.takeIf { it in 3..8 }?.let { Triple(Kind.FLAG, it - 2, 0) }
            n == "dots.dot" -> Triple(Kind.DOT, 0, 0)
            n == "timesig.c44" -> Triple(Kind.TIME, COMMON, 0)
            n == "timesig.c22" -> Triple(Kind.TIME, CUT, 0)
            else -> null
        }
    }

    // ---- A page, indexed ----

    /** Items sorted by a key (a height), to pick out the ones in a band quickly. */
    private class ByKey<T>(items: List<T>, key: (T) -> Double) {
        private val sorted = items.sortedBy(key)
        private val keys = DoubleArray(sorted.size) { key(sorted[it]) }
        fun band(lo: Double, hi: Double): List<T> = sorted.subList(first(lo), first(Math.nextUp(hi)))
        private fun first(v: Double): Int {
            var a = 0
            var b = keys.size
            while (a < b) { val m = (a + b) ushr 1; if (keys[m] < v) a = m + 1 else b = m }
            return a
        }
    }

    /** A beam: a filled bar from [xl] to [xr], its middle at heights [yl] and [yr] at its ends. */
    private class Beam(val xl: Double, val xr: Double, val yl: Double, val yr: Double, val thick: Double) {
        fun yAt(x: Double) = if (xr - xl < 1e-6) yl else yl + (yr - yl) * ((x - xl) / (xr - xl)).coerceIn(0.0, 1.0)
    }

    private class Page(val content: PdfPageContent) {
        val syms = ByKey(content.glyphs.filter { it.upright && it.size > 0 }.mapNotNull { classify(it) }) { it.y }
        val glyphs = ByKey(content.glyphs.filter { it.upright && it.size > 0 && it.text.isNotBlank() }) { it.baseline }
        val arcs = ByKey(content.arcs) { (it.y0 + it.y1) / 2 }
        val vlines = ByKey(PdfForm.joinVertical(content.vlines)) { it.y0 }
        val lines = ByKey(content.lines) { it.y }
        val beams = ByKey(content.polys.mapNotNull { p ->
            // A sloping beam: its two left corners and two right corners.
            val order = (0 until 4).sortedBy { p.xs[it] }
            val l0 = order[0]; val l1 = order[1]; val r0 = order[2]; val r1 = order[3]
            if (abs(p.xs[l0] - p.xs[l1]) > 0.5 || abs(p.xs[r0] - p.xs[r1]) > 0.5) return@mapNotNull null
            val thick = (abs(p.ys[l0] - p.ys[l1]) + abs(p.ys[r0] - p.ys[r1])) / 2
            Beam((p.xs[l0] + p.xs[l1]) / 2, (p.xs[r0] + p.xs[r1]) / 2, (p.ys[l0] + p.ys[l1]) / 2, (p.ys[r0] + p.ys[r1]) / 2, thick)
        }) { min(it.yl, it.yr) }
    }

    // ---- Reading a staff ----

    /** A notehead: extent, middle height, type (0 black, 1 half, 2 whole, 3 breve), size, and what's found for it. */
    private class Head(val x0: Double, val x1: Double, val y: Double, val type: Int, val size: Double) {
        val cx get() = (x0 + x1) / 2
        var pos = 0
        var step = 0 // diatonic step from C0 (C4 = 28)
        var accidental: Int? = null
        var midi = 0
        var tied = false
    }

    /** Heads on one stem (or one stemless note). */
    private class Chord(val heads: MutableList<Head>, val stem: PdfVLine?) {
        val x get() = heads.sumOf { it.cx } / heads.size
    }

    /** Chords struck together (a chord, or two voices at one x): their heads, length in crotchets, and how it was read. */
    private class Onset(val chords: MutableList<Chord>) {
        val heads get() = chords.flatMap { it.heads }
        val x0 get() = chords.minOf { c -> c.heads.minOf { it.x0 } }
        val x1 get() = chords.maxOf { c -> c.heads.maxOf { it.x1 } }
        val x get() = chords.sumOf { c -> c.heads.sumOf { it.cx } } / chords.sumOf { it.heads.size }
        var length = 1.0
        var grace = false
        var tuplet = 0
        var beat = 0.0
        var bar = 0
    }

    private class Rest(val x: Double, val length: Double, val whole: Boolean)

    /** What carries from one staff of the part to the next: the clef, key and time signature, and the last notes (for ties). */
    private class Carry {
        var clef = G
        var clefOctave = 0
        var clefPos = 2
        var key = 0
        var measure: Double? = null
        var last: Onset? = null
        val clefs = LinkedHashSet<String>()
        val keys = LinkedHashSet<Int>()
        var clefFound = false
    }

    private class Counts {
        var ties = 0
        var chords = 0
        var graces = 0
        var loose = 0
    }

    /** One note read: its beat on the timeline, its pitches (highest first), its system and bar, and whether it's a grace note. */
    private class Read(val beat: Double, val midis: IntArray, val system: Int, val bar: Int, val grace: Boolean)

    /** The diatonic step (C0 = 0) at staff position [pos] (0 = bottom line, 1 = the space over it) with a clef. */
    private fun stepAt(pos: Int, clef: Int, clefPos: Int, octave: Int): Int {
        val ref = when (clef) { F -> 24; C -> 28; else -> 32 } // F3, C4, G4 on the clef's line
        return ref + pos - clefPos + 7 * octave
    }

    private val SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val SHARPS = intArrayOf(3, 0, 4, 1, 5, 2, 6) // F C G D A E B
    private val FLATS = intArrayOf(6, 2, 5, 1, 4, 0, 3) // B E A D G C F

    private fun midiOf(step: Int, alter: Int) = 12 * (Math.floorDiv(step, 7) + 1) + SEMITONES[Math.floorMod(step, 7)] + alter

    /** The alteration key signature [key] (sharps > 0, flats < 0) gives diatonic step [step]. */
    private fun keyAlter(key: Int, step: Int): Int {
        val letter = Math.floorMod(step, 7)
        if (key > 0) for (i in 0 until min(key, 7)) if (SHARPS[i] == letter) return 1
        if (key < 0) for (i in 0 until min(-key, 7)) if (FLATS[i] == letter) return -1
        return 0
    }

    /**
     * Reads the sheet music on [pages] (its 5-line staves among [all], the staves found on every page) as a bouzouki tab
     * for [instrument]. Problems and a summary go to [warnings].
     */
    fun read(pages: List<PdfPageContent>, all: List<PdfTab.Staff>, instrument: Instrument, warnings: MutableSet<String>): ParsedTab {
        val five = all.filter { it.lines == 5 }
        if (five.isEmpty()) return ParsedTab(instrument, emptyList(), (warnings + PdfTab.NO_NUMBERS).toList())
        val indexed = HashMap<Int, Page>()
        fun page(i: Int) = indexed.getOrPut(i) { Page(pages[i]) }
        // Systems: staves joined by a barline; the top one of each is the part read.
        val systems = PdfTab.systems(pages, all, five)
        var parts = 1
        val staves = ArrayList<PdfTab.Staff>()
        for (system in systems) {
            staves += system[0]
            if (system.size > 1) parts = max(parts, system.size)
        }
        if (parts > 1) warnings += "This score has $parts staves in each system; only the top one was read"
        val carry = Carry()
        val counts = Counts()
        val reads = ArrayList<Read>()
        val systemsForm = ArrayList<PdfForm.System>()
        var cursor = 0.0
        var bar = 0
        var bpm: Double? = null
        var firstBar = true
        for ((k, s) in staves.withIndex()) {
            if (reads.size >= MAX_NOTES) break
            val p = page(s.page)
            if (bpm == null) bpm = tempo(p, s)
            // The staff's band: up to halfway to the staves above and below it.
            val others = all.filter { it.page == s.page && it !== s && it.x0 < s.x1 && it.x1 > s.x0 }
            val above = others.filter { it.bottom < s.top }.maxOfOrNull { it.bottom }
            val below = others.filter { it.top > s.bottom }.minOfOrNull { it.top }
            val top = max(s.top - REACH * s.spacing, above?.let { (it + s.top) / 2 } ?: Double.NEGATIVE_INFINITY)
            val bottom = min(s.bottom + REACH * s.spacing, below?.let { (it + s.bottom) / 2 } ?: Double.POSITIVE_INFINITY)
            val staff = StaffReader(p, s, top, bottom, carry, counts)
            val onsets = staff.read()
            // Bars and beats.
            val placed = staff.time(onsets, cursor, bar, firstBar)
            cursor = placed.first
            if (onsets.any { !it.grace }) firstBar = false
            val firstNote = reads.size
            val xs = ArrayList<Double>()
            for (o in onsets) {
                val heads = o.heads.filter { !it.tied }.sortedByDescending { it.midi }
                if (heads.isEmpty()) continue
                val midis = heads.map { it.midi }.distinct().toIntArray()
                if (midis.size > 1) counts.chords++
                if (o.grace) counts.graces++
                reads += Read(o.beat, midis, k, o.bar, o.grace)
                xs += o.x
            }
            bar = placed.second
            systemsForm += PdfForm.System(s.page, s.ys, null, s.x0, s.x1, firstNote, xs.toDoubleArray())
        }
        if (reads.isEmpty()) return ParsedTab(instrument, emptyList(), (warnings + PdfTab.NO_NOTES).toList())
        return toTab(pages, reads, systemsForm, carry, counts, bpm ?: DEFAULT_BPM, instrument, warnings)
    }

    /** The metronome mark over staff [s] ("♩ = 115", Sibelius's "q = 115"), in crotchets per minute, or null. */
    private fun tempo(p: Page, s: PdfTab.Staff): Double? {
        val glyphs = p.glyphs.band(s.top - 12 * s.spacing, s.top).filter { (it.x0 + it.x1) / 2 in s.x0 - 2 * s.spacing..s.x1 }
        val rows = ArrayList<MutableList<PdfGlyph>>()
        for (g in glyphs) {
            val row = rows.lastOrNull()
            if (row != null && abs(row[0].baseline - g.baseline) < 0.6 * max(row[0].size, g.size)) row += g else rows += mutableListOf(g)
        }
        for (row in rows) {
            row.sortBy { it.x0 }
            val text = row.joinToString("") { g ->
                val c = g.text.codePointAt(0)
                // The note of the mark (SMuFL's, Unicode's, or a letter of Sibelius's text font) as <q>, <e> or <h>.
                val opusText = family(g.font).let { f -> "opus" in f || "metronome" in f }
                when {
                    c == 0xECA5 || c == 0xECA4 || c == 0x2669 || c == 0x1D15F || (g.text == "q" && opusText) -> "<q>"
                    c == 0xECA7 || c == 0xECA6 || c == 0x266A || c == 0x1D160 || (g.text == "e" && opusText) -> "<e>"
                    c == 0xECA3 || c == 0xECA2 || c == 0x1D15E || (g.text == "h" && opusText) -> "<h>"
                    c == 0xECB7 || c == 0xE1E7 -> "."
                    else -> g.text
                }
            }
            val m = TEMPO.find(text) ?: continue
            val bpm = m.groupValues[3].toDoubleOrNull() ?: continue
            val unit = when (m.groupValues[1]) { "<e>" -> 0.5; "<h>" -> 2.0; else -> 1.0 } * (if (m.groupValues[2].isNotEmpty()) 1.5 else 1.0)
            val q = bpm * unit
            if (q in 20.0..400.0) return q
        }
        return null
    }

    private val TEMPO = Regex("""(<q>|<e>|<h>)\s*(\.?)\s*=\s*(?:c\.?|ca\.?|~)?\s*(\d{2,3})""")

    /**
     * Reads one staff: [p] its page, [s] the staff, [top] to [bottom] the band of the page that belongs to it; [carry]
     * what the staves before it left, [counts] what's told in the warnings.
     */
    private class StaffReader(val p: Page, val s: PdfTab.Staff, val top: Double, val bottom: Double, val carry: Carry, val counts: Counts) {
        val sp = s.spacing
        val syms = p.syms.band(top, bottom).filter { it.cx in s.x0 - sp..s.x1 + sp }.take(MAX_SYMBOLS_PER_STAFF)
        var bars = DoubleArray(0)
        var mainSize = 4 * sp
        /** Time signatures on the staff: x and the bar's length in crotchets. */
        val times = ArrayList<Pair<Double, Double>>()
        /** Key changes on the staff: x and the new key. */
        val keys = ArrayList<Pair<Double, Int>>()
        val clefs = ArrayList<Sym>()
        val rests = ArrayList<Rest>()

        fun posOf(y: Double) = ((s.bottom - y) / (sp / 2)).roundToInt()

        /** The staff's onsets in order, with pitches (none on a staff of rests). */
        fun read(): List<Onset> {
            var heads = syms.filter { it.kind == Kind.HEAD && it.size in 1.2 * sp..7 * sp }.map { Head(it.x0, it.x1, it.y, it.v, it.size) }
            if (heads.isNotEmpty()) {
                val sizes = heads.map { it.size }.sorted()
                mainSize = sizes[sizes.size / 2]
                if (mainSize < 2.5 * sp) mainSize = 4 * sp // only grace notes: the font's usual size
            } else {
                heads = shapeHeads()
                if (heads.isNotEmpty()) mainSize = heads.map { it.x1 - it.x0 }.sorted()[heads.size / 2]
            }
            val glyphHeads = heads.isNotEmpty() && syms.any { it.kind == Kind.HEAD }
            heads = heads.filter { it.y in top..bottom }.sortedBy { it.x0 }.take(MAX_SYMBOLS_PER_STAFF)
            val graceOf = { h: Head -> if (glyphHeads) h.size < 0.8 * mainSize else (h.x1 - h.x0) < 0.8 * mainSize }
            for (h in heads) h.pos = posOf(h.y)
            // A head off the staff has ledger lines (else it's a metronome mark's or a cue's, not the melody's).
            val ledgers = ByKey(p.lines.band(top, bottom).filter { it.x1 - it.x0 < 4 * sp && it.thickness <= 0.4 * sp }) { it.y }
            heads = heads.filter { h ->
                if (h.pos in -1..9) return@filter true
                val ly = s.bottom - (if (h.pos > 9) 10 else -2) * sp / 2
                ledgers.band(ly - 0.3 * sp, ly + 0.3 * sp).any { it.x0 < h.x1 && it.x1 > h.x0 }
            }
            val headW = heads.filter { !graceOf(it) }.map { it.x1 - it.x0 }.sorted().let { if (it.isEmpty()) 1.2 * sp else it[it.size / 2] }
            headsByX = ByKey(heads) { it.x0 }
            widest = heads.maxOfOrNull { it.x1 - it.x0 } ?: 0.0

            bars = barlines(heads)
            val onsets = onsets(heads, graceOf, headW)
            clefsAndKey(onsets)
            timeSignatures()
            pitches(onsets)
            lengths(onsets)
            restsOn()
            tuplets(onsets)
            ties(onsets)
            carry.last = onsets.lastOrNull { o -> !o.grace } ?: carry.last
            return onsets
        }

        /** The heads by their left edge, and the widest one's width: to find the heads near an x quickly. */
        private var headsByX = ByKey(emptyList<Head>()) { it.x0 }
        private var widest = 0.0

        /** The heads whose extent may reach from [xa] to [xb]. */
        private fun headsNear(xa: Double, xb: Double) = headsByX.band(xa - widest, xb)

        /** Noteheads drawn as shapes: filled ellipses (black) and rings or outlines (half and whole notes) a line spacing high. */
        private fun shapeHeads(): MutableList<Head> {
            val out = ArrayList<Head>()
            for (a in p.arcs.band(top, bottom)) {
                if (a.inner) continue
                val w = a.x1 - a.x0
                val h = a.y1 - a.y0
                if (w !in 0.9 * sp..1.8 * sp || h !in 0.65 * sp..1.2 * sp || w < 1.0 * h || w > 1.9 * h) continue
                if ((a.x0 + a.x1) / 2 !in s.x0..s.x1 + sp) continue
                val type = if (a.filled && !a.hollow) 0 else if (w > 1.6 * sp) 2 else 1
                // The size a glyph of the same width would have.
                out += Head(a.x0, a.x1, (a.y0 + a.y1) / 2, type, w / (1.2 * sp) * 4 * sp)
            }
            return out
        }

        /** Barlines across the staff (not stems), joined when close (double bars), not counting the staff's opening line. */
        private fun barlines(heads: List<Head>): DoubleArray {
            val tol = max(1.0, 0.25 * sp)
            val across = p.vlines.band(top - 20 * sp, s.top + tol).filter { v ->
                v.y0 <= s.top + tol && v.y1 >= s.bottom - tol && v.x in s.x0 - tol..s.x1 + tol && v.thickness <= 0.8 * sp
            }.filter { v ->
                // A stem from a note on the staff can run from its bottom line to its top one: not a barline.
                headsNear(v.x - 0.12 * sp, v.x + 0.15 * sp).none { h -> isStem(v, h) }
            }.map { it.x }.sorted()
            val out = ArrayList<Double>()
            for (x in across) if (out.isEmpty() || x - out.last() > 1.2 * sp) out += x else out[out.size - 1] = x
            return out.filter { it > s.x0 + sp }.toDoubleArray()
        }

        /** Whether [v] could be [h]'s stem: up from its right side, or down from its left side. */
        private fun isStem(v: PdfVLine, h: Head): Boolean {
            if (v.x < h.x0 - 0.15 * sp || v.x > h.x1 + 0.12 * sp) return false
            return (v.x >= h.cx && abs(v.y1 - h.y) <= 0.7 * sp) || (v.x <= h.cx && abs(v.y0 - h.y) <= 0.7 * sp)
        }

        /** Stems, heads grouped by stem into chords, and chords at one x into onsets, in order. */
        private fun onsets(heads: List<Head>, graceOf: (Head) -> Boolean, headW: Double): List<Onset> {
            val stems = p.vlines.band(top - 4 * sp, bottom).filter { v ->
                v.y1 - v.y0 >= 1.2 * sp && v.y1 - v.y0 <= 9 * sp && v.thickness <= 0.3 * sp && v.x in s.x0..s.x1 + sp &&
                    bars.none { abs(it - v.x) < 0.2 * sp }
            }.sortedBy { it.x }
            val stemX = DoubleArray(stems.size) { stems[it].x }
            val byStem = LinkedHashMap<PdfVLine, MutableList<Head>>()
            val loose = ArrayList<Head>()
            val pending = ArrayList<Head>()
            for (h in heads) {
                // A stem at the head's right going up from it, or at its left going down from it.
                var best: PdfVLine? = null
                var bestCost = Double.MAX_VALUE
                var i = firstAtLeast(stemX, h.x0 - 0.15 * sp)
                while (i < stems.size && stems[i].x <= h.x1 + 0.12 * sp) {
                    val v = stems[i++]
                    if (!isStem(v, h)) continue
                    val up = v.x >= h.cx
                    val cost = (if (up) abs(v.y1 - h.y) else abs(v.y0 - h.y)) + min(abs(v.x - h.x0), abs(v.x - h.x1))
                    if (cost < bestCost) { bestCost = cost; best = v }
                }
                if (best != null) byStem.getOrPut(best) { ArrayList() } += h else pending += h
            }
            // Heads in the middle of a chord: on a stem found for another head.
            val found = ByKey(byStem.keys.toList()) { it.x }
            for (h in pending) {
                val v = found.band(h.x0 - 0.15 * sp, h.x1 + 0.12 * sp).firstOrNull { v -> h.y in v.y0 - 0.7 * sp..v.y1 + 0.7 * sp }
                if (v != null) byStem.getValue(v) += h else loose += h
            }
            val chords = ArrayList<Chord>()
            for ((v, hs) in byStem) chords += Chord(hs, v)
            // Heads without a stem (whole notes) at one x are a chord.
            var stemless: Chord? = null
            for (h in loose.sortedBy { it.cx }) {
                val g = stemless?.takeIf { abs(it.x - h.cx) < 0.6 * headW }
                if (g != null) g.heads += h else chords += Chord(mutableListOf(h), null).also { stemless = it }
            }
            chords.sortBy { it.x }
            // Chords of two voices struck at one x make one onset; grace notes stay apart from the others.
            val out = ArrayList<Onset>()
            for (c in chords) {
                val grace = c.heads.all { graceOf(it) }
                val o = out.lastOrNull()
                if (o != null && o.grace == grace && abs(o.x - c.x) < 0.5 * headW) o.chords += c
                else out += Onset(mutableListOf(c)).also { it.grace = grace }
            }
            return out
        }

        /** The clef in force, the key signature at the start (and key changes), clef changes; sets [Carry]. */
        private fun clefsAndKey(onsets: List<Onset>) {
            clefs += syms.filter { it.kind == Kind.CLEF && it.y in s.top - 2 * sp..s.bottom + 2 * sp }.sortedBy { it.x0 }
            val firstX = onsets.firstOrNull()?.x0 ?: s.x1
            val start = clefs.firstOrNull { it.x0 < firstX && it.x0 < s.x0 + 8 * sp }
            if (start != null) setClef(start)
            // The key signature: accidentals right after the clef, close together, in a key signature's order.
            val from = start?.x1 ?: s.x0
            val accs = syms.filter { it.kind == Kind.ACCIDENTAL && it.y in s.top - 1.5 * sp..s.bottom + 1.5 * sp }.sortedBy { it.x0 }
            val run = keyRun(accs, from, firstX).let { r -> if (r.size == 1 && attachable(r[0])) emptyList() else r }
            carry.key = if (run.isEmpty()) 0 else run.count { it.v > 0 } - run.count { it.v < 0 }
            used.addAll(run)
            // Key changes: accidentals not by a note, after a barline.
            val accX = DoubleArray(accs.size) { accs[it].x0 }
            for (b in bars) {
                val from = firstAtLeast(accX, b - 0.5 * sp)
                val r = keyRun(accs.subList(from, min(accs.size, from + 16)).filter { it !in used }, b, s.x1)
                if (r.isNotEmpty() && r.none { a -> attachable(a) }) {
                    keys += b to (r.count { it.v > 0 } - r.count { it.v < 0 })
                    used.addAll(r)
                }
            }
            carry.keys += carry.key
            for ((_, k) in keys) carry.keys += k
        }

        val used = HashSet<Sym>()

        private fun setClef(c: Sym) {
            carry.clef = c.v
            carry.clefFound = true
            // An 8 under (or over) a G or F clef: an octave down (up).
            val eight = syms.firstOrNull { d ->
                (d.kind == Kind.DIGIT || d.kind == Kind.TIME) && d.v == 8 && d.size < 0.75 * max(c.size, mainSize) && d.cx in c.x0 - 0.5 * sp..c.x1 + 0.5 * sp &&
                    (d.y in s.bottom + 0.5 * sp..s.bottom + 5 * sp || d.y in s.top - 4 * sp..s.top - 0.2 * sp)
            }
            carry.clefOctave = c.v2.takeIf { it != 0 } ?: when {
                eight == null -> 0
                eight.y > s.bottom -> -1
                else -> 1
            }
            carry.clefPos = when (c.v) {
                F -> 6
                C -> (posOf(c.y) / 2 * 2).coerceIn(0, 8).let { if (it == 0) 4 else it }
                else -> 2
            }
            carry.clefs += when (c.v) {
                F -> "bass"
                C -> if (carry.clefPos == 4) "alto" else "C"
                else -> if (carry.clefOctave < 0) "treble 8vb" else if (carry.clefOctave > 0) "treble 8va" else "treble"
            }
        }

        /** Whether accidental [a] has a note right of it at its height (so it's that note's, not a key signature's). */
        private fun attachable(a: Sym) = headsByX.band(a.x1 - 0.5 * sp, a.x1 + 1.2 * sp).any { h -> h.pos == posOf(a.y) }

        /**
         * A key signature starting within 3 line spacings right of [from] (and left of [limit]): sharps, or flats, close
         * together at the heights the clef puts them (naturals before them cancel the old key; naturals alone give C).
         */
        private fun keyRun(accs: List<Sym>, from: Double, limit: Double): List<Sym> {
            val out = ArrayList<Sym>()
            for (a in accs) {
                if (a.x0 < from - 0.5 * sp) continue
                if (a.x0 > limit) break
                if (out.isEmpty()) { if (a.x0 - from > 3 * sp) break; out += a; continue }
                if (a.x0 - out.last().x1 > 1.0 * sp) break
                out += a
            }
            // Naturals first, then one kind of accidental in the order of a key signature.
            val naturals = out.takeWhile { it.v == 0 }
            val rest = out.drop(naturals.size)
            val sign = rest.firstOrNull()?.v ?: return naturals
            if (sign != 1 && sign != -1) return naturals
            val order = if (sign > 0) SHARPS else FLATS
            val kept = ArrayList<Sym>(naturals)
            for ((i, a) in rest.withIndex()) {
                if (a.v != sign || i >= 7) break
                val letter = Math.floorMod(stepAt(posOf(a.y), carry.clef, carry.clefPos, carry.clefOctave), 7)
                if (letter != order[i]) break
                kept += a
            }
            return kept
        }

        /** Time signatures: digits of the music font's full size on the staff, numerator over denominator (or C, cut C). */
        private fun timeSignatures() {
            val digits = syms.filter { it.kind == Kind.TIME && it.size >= 0.7 * mainSize && it.y in s.top - 0.5 * sp..s.bottom + 1.5 * sp }.sortedBy { it.x0 }
            var i = 0
            while (i < digits.size) {
                var j = i + 1
                while (j < digits.size && digits[j].x0 < digits[j - 1].x1 + 0.3 * sp) j++
                val group = digits.subList(i, j)
                val x = group.first().x0
                when {
                    group.any { it.v == COMMON } -> times += x to 4.0
                    group.any { it.v == CUT } -> times += x to 4.0
                    else -> {
                        // Numerator over denominator, whichever height a font draws its digits from.
                        val split = (group.minOf { it.y } + group.maxOf { it.y }) / 2
                        val num = group.filter { it.y < split }.sortedBy { it.x0 }.joinToString("") { it.v.toString() }.toIntOrNull()
                        val den = group.filter { it.y > split }.sortedBy { it.x0 }.joinToString("") { it.v.toString() }.toIntOrNull()
                        if (num != null && den != null && num in 1..32 && den in setOf(1, 2, 4, 8, 16, 32)) times += x to num * 4.0 / den
                    }
                }
                i = j
            }
        }

        /** Every head's pitch: clef (and clef changes), key (and key changes), accidentals held to the end of the bar. */
        private fun pitches(onsets: List<Onset>) {
            val accs = syms.filter { it.kind == Kind.ACCIDENTAL && it !in used }.sortedBy { it.x0 }
            // Each accidental goes to the nearest note right of it at its height.
            val all = onsets.flatMap { it.heads }.sortedBy { it.x0 }
            val x0s = DoubleArray(all.size) { all[it].x0 }
            for (a in accs) {
                val pos = posOf(a.y)
                var i = firstAtLeast(x0s, a.x1 - 0.5 * sp)
                while (i < all.size && all[i].x0 <= a.x1 + 4 * sp) {
                    val h = all[i++]
                    if (h.pos == pos && h.accidental == null) { h.accidental = a.v; break }
                }
            }
            var key = carry.key
            var nextKey = 0
            var nextClef = 0
            val laterClefs = clefs.filter { c -> c.x0 > (onsets.firstOrNull()?.x0 ?: s.x1) }
            val held = HashMap<Int, Int>()
            var b = 0
            for (o in onsets) {
                while (b < bars.size && bars[b] < o.x) { b++; held.clear() }
                while (nextKey < keys.size && keys[nextKey].first < o.x) key = keys[nextKey++].second
                while (nextClef < laterClefs.size && laterClefs[nextClef].x1 < o.x0 + 0.2 * sp) setClef(laterClefs[nextClef++])
                for (h in o.heads) {
                    h.step = stepAt(h.pos, carry.clef, carry.clefPos, carry.clefOctave)
                    val alter = h.accidental?.also { held[h.step] = it } ?: held[h.step] ?: keyAlter(key, h.step)
                    h.midi = midiOf(h.step, alter)
                }
            }
            carry.key = keys.lastOrNull()?.second ?: carry.key
        }

        /** Each onset's length in crotchets: head, beams or flags on its stem, dots. */
        private fun lengths(onsets: List<Onset>) {
            val flags = ByKey(syms.filter { it.kind == Kind.FLAG }) { it.x0 }
            val dots = syms.filter { it.kind == Kind.DOT }.toMutableList()
            // Dots drawn as small filled circles.
            for (a in p.arcs.band(top, bottom)) {
                val w = a.x1 - a.x0
                val h = a.y1 - a.y0
                if (a.filled && !a.inner && w in 0.2 * sp..0.75 * sp && h in 0.2 * sp..0.75 * sp && w < 1.6 * h && h < 1.6 * w && dots.size < MAX_SYMBOLS_PER_STAFF) {
                    dots += Sym(Kind.DOT, a.x0, a.x1, (a.y0 + a.y1) / 2, w, 0)
                }
            }
            val beamList = ArrayList<Beam>()
            for (l in p.lines.band(top - 4 * sp, bottom + 4 * sp)) {
                if (l.thickness in 0.25 * sp..0.9 * sp && l.x1 - l.x0 in 0.6 * sp..MAX_BEAM * sp && l.x1 > s.x0 && l.x0 < s.x1 + sp) beamList += Beam(l.x0, l.x1, l.y, l.y, l.thickness)
            }
            for (bm in p.beams.band(top - 8 * sp, bottom + 4 * sp)) {
                if (bm.thick in 0.25 * sp..0.9 * sp && bm.xr - bm.xl in 0.6 * sp..MAX_BEAM * sp && bm.xr > s.x0 && bm.xl < s.x1 + sp) beamList += bm
            }
            val beams = ByKey(beamList.take(MAX_SYMBOLS_PER_STAFF)) { it.xl }
            // Repeat dots (two, a space apart, by a barline) aren't a note's.
            val byX = ByKey(dots) { it.x0 }
            val gone = HashSet<Sym>()
            for (d in dots) {
                if (bars.any { abs(it - d.cx) < 2 * sp } && byX.band(d.x0 - 0.3 * sp, d.x0 + 0.3 * sp).any { e -> e !== d && abs(abs(e.y - d.y) - sp) < 0.3 * sp }) gone += d
            }
            for (o in onsets) {
                o.length = o.chords.minOf { length(it, flags, beams) }
                // Dots: right of the rightmost head, at a head's height (or in the space over it).
                val right = o.heads.maxOf { it.x1 }
                val row = byX.band(right - 0.3 * sp, right + 2.0 * sp).filter { d -> d !in gone && o.heads.any { h -> abs(d.y - h.y) <= 0.75 * sp } }
                if (row.isNotEmpty()) {
                    val first = row.minBy { it.x0 }
                    val second = row.any { it.x0 > first.x1 && it.x0 - first.x1 < 1.2 * sp && abs(it.y - first.y) < 0.3 * sp }
                    o.length *= if (second) 1.75 else 1.5
                    gone.addAll(row)
                }
            }
        }

        private fun length(o: Chord, flags: ByKey<Sym>, beams: ByKey<Beam>): Double {
            val type = o.heads.minOf { it.type }
            if (type == 3) return 8.0
            if (type == 2) return 4.0
            val base = if (type == 1) 2.0 else 1.0
            val v = o.stem ?: return base
            if (type == 1) return base
            // The stem's tip: its end away from the heads.
            val mean = o.heads.sumOf { it.y } / o.heads.size
            val up = abs(v.y0 - mean) > abs(v.y1 - mean)
            val tip = if (up) v.y0 else v.y1
            val dir = if (up) 1.0 else -1.0 // towards the heads
            val centres = ArrayList<Double>()
            for (bm in beams.band(v.x - MAX_BEAM * sp - 0.4, v.x + 0.4)) {
                if (v.x > bm.xr + 0.4) continue
                val d = (bm.yAt(v.x) - tip) * dir
                if (d in -0.6 * sp..2.9 * sp) centres += d
            }
            centres.sort()
            var n = 0
            if (centres.isNotEmpty() && centres[0] <= 0.7 * sp) {
                var last = Double.NEGATIVE_INFINITY
                for (c in centres) if (c - last > 0.4 * sp) { n++; last = c }
            }
            if (n == 0) {
                n = flags.band(v.x - 0.6 * sp, v.x + 0.8 * sp).filter { f -> abs(f.y - tip) <= 3.5 * sp }.maxOfOrNull { it.v } ?: 0
            }
            return base / (1 shl min(n, 6))
        }

        /** Rests on the staff. */
        private fun restsOn() {
            for (r in syms) {
                if (r.kind != Kind.REST || r.y !in s.top - 2 * sp..s.bottom + 2 * sp || r.size < 0.6 * mainSize) continue
                rests += Rest(r.cx, r.v / 64.0, r.v >= 256)
            }
        }

        /** Tuplet numbers (3, 5, 6, 7) by a group of notes: each note of the group plays in that fraction of its length. */
        private fun tuplets(onsets: List<Onset>) {
            val main = onsets.filter { !it.grace }
            if (main.size < 2) return
            val xs = main.map { it.x }
            var found = 0
            for (d in syms) {
                if (d.kind != Kind.DIGIT && !(d.kind == Kind.TIME && d.size < 0.7 * mainSize)) continue
                val n = d.v
                if (n !in setOf(3, 5, 6, 7) || d.cx < xs.first() - sp || d.cx > xs.last() + sp) continue
                if (d.y in s.top + 0.3 * sp..s.bottom - 0.3 * sp && d.size >= 0.7 * mainSize) continue
                if (clefs.any { c -> d.cx in c.x0 - sp..c.x1 + sp }) continue
                // A digit of a number or of words ("= 60", a bar number) isn't a tuplet's.
                if (p.glyphs.band(d.y - 0.3 * d.size, d.y + 0.3 * d.size).any { g ->
                        abs(g.x0 - d.x0) > 0.01 && g.x0 < d.x1 + 0.5 * d.size && g.x1 > d.x0 - 0.5 * d.size
                    }) continue
                if (main.size < n) continue
                // The n notes in a row whose middle is nearest the number.
                var best = -1
                var bestDist = Double.MAX_VALUE
                for (i in 0..main.size - n) {
                    val mid = (xs[i] + xs[i + n - 1]) / 2
                    val dist = abs(mid - d.cx)
                    if (dist < bestDist) { bestDist = dist; best = i }
                }
                if (best < 0 || bestDist > 3 * sp || xs[best + n - 1] - xs[best] > n * 8 * sp) continue
                val group = main.subList(best, best + n)
                if (group.any { it.tuplet != 0 }) continue
                val ratio = (Integer.highestOneBit(n)).toDouble() / n
                for (o in group) { o.tuplet = n; o.length *= ratio }
                if (++found >= MAX_TUPLETS) return
            }
        }

        /** Ties: a curve from a note to the next one at the same height (or from the start of the staff to its first). */
        private fun ties(onsets: List<Onset>) {
            val arcs = p.arcs.band(top, bottom).filter { a ->
                val w = a.x1 - a.x0
                val h = a.y1 - a.y0
                !a.inner && w >= 0.8 * sp && h <= 1.4 * sp && w > 1.5 * h && a.x1 > s.x0 && a.x0 < s.x1 + sp
            }.take(MAX_SYMBOLS_PER_STAFF)
            if (arcs.isEmpty()) return
            val byEnd = ByKey(arcs) { it.x1 }
            val firstX = onsets.firstOrNull()?.x0 ?: return
            val firstMain = onsets.indexOfFirst { !it.grace }
            for ((i, o) in onsets.withIndex()) {
                if (o.grace) continue
                for (h in o.heads) {
                    for (a in byEnd.band(h.x0 - 1.5 * sp, h.x1 + 0.3 * sp)) {
                        if ((a.y0 + a.y1) / 2 !in h.y - 1.8 * sp..h.y + 1.8 * sp) continue
                        // From a note before it (one of the three onsets before) at the same step.
                        var tiedTo: Head? = null
                        for (j in i - 1 downTo max(0, i - 3)) {
                            val prev = onsets[j]
                            if (prev.grace) continue
                            val ph = prev.heads.firstOrNull { it.step == h.step } ?: continue
                            if (a.x0 in ph.x0 - 0.3 * sp..ph.x1 + 1.5 * sp) { tiedTo = ph; break }
                        }
                        if (tiedTo != null) { h.tied = true; h.midi = tiedTo.midi; break }
                        // From the end of the staff before.
                        if (a.x0 < firstX - 0.3 * sp && i == firstMain) {
                            val ph = carry.last?.heads?.firstOrNull { it.midi == h.midi || it.step == h.step }
                            if (ph != null) { h.tied = true; h.midi = ph.midi; break }
                        }
                    }
                    if (h.tied) counts.ties++
                }
            }
        }

        /**
         * Places the onsets on the timeline: bar by bar from beat [start] and bar number [bar] (the staff's first),
         * by their lengths where a bar adds up to its time signature, else by where they're printed. [pickup]: this may
         * be the score's first bar, which may be short. The beat and bar after the staff.
         */
        fun time(onsets: List<Onset>, start: Double, bar: Int, pickup: Boolean): Pair<Double, Int> {
            // Bars: from the staff's start to each barline, and from the last to the staff's end.
            val edges = ArrayList<Double>()
            edges += s.x0
            for (b in bars) edges += b
            if (bars.isEmpty() || s.x1 - bars.last() > 2 * sp) edges += s.x1 + sp
            var cursor = start
            var n = bar
            var first = pickup
            val onsetsByX = ByKey(onsets) { it.x }
            val restsByX = ByKey(rests) { it.x }
            for (k in 0 until edges.size - 1) {
                val a = edges[k]
                val b = edges[k + 1]
                val notes = onsetsByX.band(a, Math.nextDown(b))
                val rs = restsByX.band(a, Math.nextDown(b))
                // The time signature in force.
                times.lastOrNull { it.first < b - 0.5 * sp }?.let { carry.measure = it.second }
                if (notes.isEmpty() && rs.isEmpty()) {
                    // An empty stretch: the opening (clef, key, time) before the first barline, or a bar of rests drawn as shapes.
                    if (k == 0 || b - a < 4 * sp) continue
                }
                val measure = carry.measure
                val main = notes.filter { !it.grace }
                // Notes and rests in order; a whole rest alone in a bar rests the whole bar (one with notes is another voice's).
                val events = (main.map { it.x to it.length } + rs.filter { !it.whole }.map { it.x to it.length } +
                    (if (main.isEmpty()) rs.filter { it.whole }.take(1).map { it.x to (measure ?: it.length) } else emptyList())).sortedBy { it.first }
                val sum = events.sumOf { it.second }
                val fits = when {
                    measure == null -> sum > 0
                    abs(sum - measure) < 0.02 -> true
                    first && main.isNotEmpty() && sum < measure && sum > 0 -> true // a pickup bar
                    else -> false
                }
                val length = when {
                    measure == null -> if (sum > 0) sum else 4.0
                    fits -> if (sum < measure - 0.02) sum else measure
                    else -> measure
                }
                if (!fits && main.isNotEmpty()) counts.loose++
                log?.invoke("bar $n page ${s.page + 1} x ${"%.1f".format(a)}-${"%.1f".format(b)} measure $measure sum $sum ${if (fits) "fits" else "LOOSE"}: " +
                    events.joinToString(" ") { "%.1f:%.3g".format(it.first, it.second) } + " | " +
                    notes.joinToString(" ") { o -> o.heads.joinToString("+") { "${it.midi}" } + (if (o.grace) "g" else "") + (if (o.tuplet > 0) "t${o.tuplet}" else "") })
                // Each note's beat in the bar.
                var t = 0.0
                var e = 0
                val firstX = events.firstOrNull()?.first ?: a
                for (o in notes.sortedBy { it.x }) {
                    o.bar = n
                    if (fits) {
                        while (e < events.size && events[e].first < o.x - 0.01) { t += events[e].second; e++ }
                        o.beat = cursor + t
                    } else {
                        o.beat = cursor + length * ((o.x - firstX) / max(1e-6, b - firstX)).coerceIn(0.0, 0.999)
                    }
                }
                cursor += length
                n++
                if (main.isNotEmpty()) first = false
            }
            return cursor to n
        }
    }

    private fun firstAtLeast(xs: DoubleArray, v: Double): Int {
        var lo = 0
        var hi = xs.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (xs[m] < v) lo = m + 1 else hi = m }
        return lo
    }

    // ---- To a tab ----

    private fun toTab(
        pages: List<PdfPageContent>, reads: List<Read>, systems: List<PdfForm.System>, carry: Carry, counts: Counts, bpm: Double,
        instrument: Instrument, warnings: MutableSet<String>,
    ): ParsedTab {
        val lowest = instrument.courses.first().openMidi
        val highest = instrument.courses.last().openMidi + instrument.frets
        // The octave: the one nearest the clef's where the notes fit the bouzouki and sit on its D and A courses (a note
        // out of reach counts ten times one under the A course or in the last few frets).
        val aCourse = instrument.courses[instrument.courses.size - 2].openMidi
        val all = reads.flatMap { it.midis.asList() }
        var shift = 0
        var bestCost = Int.MAX_VALUE
        for (k in listOf(0, -1, 1, -2, 2)) {
            var cost = 0
            for (m0 in all) {
                val m = m0 + 12 * k
                cost += if (m !in lowest..highest) 10 else if (m < aCourse || m > highest - 5) 1 else 0
            }
            if (cost < bestCost) { bestCost = cost; shift = 12 * k }
        }
        var moved = 0
        fun fit(m0: Int): Int {
            var m = m0 + shift
            while (m < lowest) m += 12
            while (m > highest) m -= 12
            if (m != m0 + shift) moved++
            return m
        }
        val melody = reads.map { fit(it.midis[0]) }
        val starts = reads.map { it.beat * 60.0 / bpm }
        val cells = Fingering.place(melody, starts, instrument, null, null)
        // Columns: COLUMNS_PER_BEAT per crotchet from the first note, one at least between notes; grace notes just before theirs.
        val raw = IntArray(reads.size) { (reads[it].beat * COLUMNS_PER_BEAT).roundToInt() }
        val first = raw.firstOrNull() ?: 0
        val cols = IntArray(reads.size)
        var prev = Int.MIN_VALUE
        var i = 0
        while (i < reads.size) {
            var j = i
            while (j < reads.size && reads[j].grace) j++
            // Grace notes i until j come before note j.
            val target = if (j < reads.size) raw[j] - first else raw[i] - first
            for (g in i until j) {
                val c = max(prev + 1, target - (j - g))
                cols[g] = c; prev = c
            }
            if (j < reads.size) { cols[j] = max(prev + 1, raw[j] - first); prev = cols[j] }
            i = j + 1
        }
        var dropped = 0
        val notes = reads.mapIndexed { k, r ->
            val (course, fret) = cells[k]
            // The other notes of a chord: each on a course of its own, near the melody's fret.
            val used = HashSet<Int>().also { it += course }
            val stack = ArrayList<Pair<Int, Int>>()
            for (m0 in r.midis.drop(1)) {
                val m = fit(m0)
                val at = instrument.courses.indices.filter { it !in used && m - instrument.courses[it].openMidi in 0..instrument.frets }
                    .minByOrNull { c -> abs(m - instrument.courses[c].openMidi - fret) * 10 + (m - instrument.courses[c].openMidi) }
                if (at == null) { dropped++; continue }
                used += at
                stack += at to (m - instrument.courses[at].openMidi)
            }
            TabNote(course, fret, cols[k], Articulation.PICK, stack, r.system, r.bar)
        }
        // The form: repeats, endings, jumps.
        val form = try { PdfForm.read(pages, systems, notes.size, warnings) } catch (e: RuntimeException) {
            warnings += "Couldn't make sense of the score's repeat signs: the music is read in written order"
            null
        }
        val sections = form?.sections ?: listOf(TabSection(0, notes.size))

        val out = LinkedHashSet<String>()
        val clef = carry.clefs.ifEmpty { setOf("treble") }.joinToString(" and ") + (if (carry.clefs.size > 1) " clefs" else " clef")
        val k = carry.keys.firstOrNull() ?: 0
        val key = when {
            k > 0 -> "$k sharp${if (k > 1) "s" else ""}"
            k < 0 -> "${-k} flat${if (k < -1) "s" else ""}"
            else -> "no sharps or flats"
        }
        val clefOctave = if (carry.clefs.any { it.endsWith("8vb") }) -12 else if (carry.clefs.any { it.endsWith("8va") }) 12 else 0
        val written = shift + clefOctave
        val octave = when (written) {
            0 -> "as written"
            -12 -> "an octave lower than written"
            12 -> "an octave higher than written"
            else -> "${abs(written) / 12} octaves ${if (written < 0) "lower" else "higher"} than written"
        }
        val n = notes.size
        out += "Read $n note${if (n == 1) "" else "s"} ${PdfTab.FROM_SHEET_MUSIC} ($clef, $key, $octave); strings and frets chosen by the app"
        when {
            clefOctave != 0 && written == clefOctave -> out += "Played $octave, as the clef's 8 says"
            clefOctave != 0 -> out += "Played $octave (not as the clef's 8 says), where the notes fit the bouzouki best"
            written != 0 -> out += "Played $octave, where the notes fit the bouzouki best"
        }
        if (!carry.clefFound) out += "No clef was found: the music is read in the treble clef"
        if (carry.keys.size > 1) out += "The key changes in the score; each part is read in its own key"
        if (moved > 0) out += "Moved $moved note${if (moved > 1) "s" else ""} by an octave to fit the bouzouki"
        if (counts.chords > 0) out += "Kept ${counts.chords} chord${if (counts.chords > 1) "s" else ""} (notes played together): the top note is the melody"
        if (dropped > 0) out += "Left out $dropped note${if (dropped > 1) "s" else ""} of chords that don't fit on the bouzouki with the melody"
        if (counts.ties > 0) out += "Left out ${counts.ties} tied note${if (counts.ties > 1) "s" else ""} (held on, not picked again)"
        if (counts.graces > 0) out += "Kept ${counts.graces} grace note${if (counts.graces > 1) "s" else ""}"
        if (counts.loose > 0) {
            out += "The rhythm of ${counts.loose} bar${if (counts.loose > 1) "s" else ""} couldn't be read exactly: " +
                "${if (counts.loose > 1) "their" else "its"} notes are spaced as printed"
        }
        if (reads.size >= MAX_NOTES) out += "This score is very long: only its first $MAX_NOTES notes were read"
        out += warnings
        return ParsedTab(instrument, notes, out.toList(), sections)
    }
}
