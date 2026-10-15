package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The tab aligner on the scratch evaluation's synthesized songs, whose exact bouzouki notes are known. Tabs are written
 * from the ground truth ([TabText]) and spoiled the way real tabs are (notes left out or added, wrong frets, an octave
 * or a key off, written for the other bouzouki, rhythm only roughly kept, only the first verse, repeats written once),
 * read back with [TabParser] and lined up with each song's cached activations; each also with bar lines (", bars": every
 * four beats of the written notes, as a PDF score or a text tab with | has them). Reports how far the aligned notes are
 * from where they're played, how many land within 50 ms, what happens in parts the tab doesn't cover, and the time;
 * checks that no kind of tab leaves the song's line worse than the prepared line.
 * TAB_EVAL_DIR names the folder holding kotlin_legato/ (songs without a form) and kotlin_tabs/ (songs with intro,
 * verses and choruses repeated); only runs where it's set.
 */
class TabEvalTest {
    private val dir = System.getenv("TAB_EVAL_DIR")?.let { File(it) }

    private class Gt(val start: Double, val end: Double, val midi: Int, val articulation: Articulation, val section: String, val play: Int)

    private class Song(val name: String, val act: BasicPitch.Activations, val attacks: Attacks?, val gt: List<Gt>, val bpm: Double, val meta: Map<*, *>?)

    private fun floats(f: File, cols: Int): Array<FloatArray> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return Array(b.remaining() / cols) { FloatArray(cols).also { row -> b.get(row) } }
    }

    private fun samples(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private val kinds = mapOf("hammer" to Articulation.HAMMER, "pull" to Articulation.PULL, "slideB" to Articulation.SLIDE, "tremolo" to Articulation.TREMOLO)

    private fun load(d: File): Song {
        val contours = File(d, "contours.u8").takeIf { it.exists() }?.let { f ->
            val all = f.readBytes()
            Array(all.size / BasicPitch.N_CONTOUR_BINS) { all.copyOfRange(it * BasicPitch.N_CONTOUR_BINS, (it + 1) * BasicPitch.N_CONTOUR_BINS) }
        }
        val act = BasicPitch.Activations(floats(File(d, "frames.f32"), 88), floats(File(d, "onsets.f32"), 88), contours)
        val attacks = File(d, "audio22.f32").takeIf { it.exists() }?.let { Attacks.of(samples(it)) }
        val gt = File(d, "gt.csv").readLines().filter { it.isNotBlank() }.map { l ->
            val c = l.split(",")
            Gt(c[0].toDouble(), c[1].toDouble(), c[2].toInt(), kinds[c[3]] ?: Articulation.PICK,
                if (c.size >= 6) c[4] else "all", if (c.size >= 6) c[5].toInt() else 0)
        }.sortedBy { it.start }
        val meta = File(d, "meta.json").takeIf { it.exists() }?.let { MiniJson(it.readText()).parse() as Map<*, *> }
        val bpm = (meta?.get("bpm") as Number?)?.toDouble() ?: d.name.split("_").mapNotNull { it.toIntOrNull() }.first().toDouble()
        return Song(d.name, act, attacks, gt, bpm, meta)
    }

    /** One tab variant of a song: its text, and for each note written, the times it's played in the song (by ref). */
    private class Variant(val name: String, val text: String, val written: List<TabText.Note>, val occurrences: Map<Int, List<Double>>, val uncovered: List<ClosedFloatingPointRange<Double>>)

    private fun cells(notes: List<Gt>, inst: Instrument): List<Pair<Int, Int>> =
        SongAnalyzer.fingering(notes.map { it.midi }, notes.map { it.start }, inst, notes.map { it.articulation.isLegato }, notes.map { it.articulation == Articulation.SLIDE })

    private fun tabNotes(notes: List<Gt>, inst: Instrument, refs: List<Int>, shift: Int = 0): List<TabText.Note> {
        val moved = notes.map { g ->
            var m = g.midi + shift
            val low = inst.courses.first().openMidi
            val high = inst.courses.last().openMidi + inst.frets
            while (m < low) m += 12
            while (m > high) m -= 12
            m
        }
        val c = SongAnalyzer.fingering(moved, notes.map { it.start }, inst, notes.map { it.articulation.isLegato }, notes.map { it.articulation == Articulation.SLIDE })
        return notes.indices.map { TabText.Note(c[it].first, c[it].second, notes[it].articulation, notes[it].start, refs[it]) }
    }

    /** Leaves out, adds and mis-frets notes as hand-written tabs do. */
    private fun spoil(notes: List<TabText.Note>, inst: Instrument, rnd: Random, drop: Double = 0.08, extra: Double = 0.05, wrong: Double = 0.08): List<TabText.Note> {
        val out = ArrayList<TabText.Note>()
        for ((k, n) in notes.withIndex()) {
            if (k > 0 && rnd.nextDouble() < drop) continue
            var x = n
            if (rnd.nextDouble() < wrong) {
                val f = (n.fret + (if (rnd.nextBoolean()) 1 else -1) * (1 + rnd.nextInt(2))).coerceIn(0, inst.frets)
                x = n.copy(fret = f, articulation = if (n.articulation.isLegato) Articulation.PICK else n.articulation)
            }
            out += x
            val next = notes.getOrNull(k + 1)
            if (next != null && rnd.nextDouble() < extra && next.time - n.time > 0.15) {
                out += TabText.Note(n.course, (n.fret + rnd.nextInt(5) - 2).coerceIn(0, inst.frets), Articulation.PICK, (n.time + next.time) / 2, -1)
            }
        }
        // A legato note whose note before was left out or changed course is picked.
        return out.mapIndexed { k, n -> if (n.articulation.isLegato && (k == 0 || out[k - 1].course != n.course || out[k - 1].ref != n.ref - 1)) n.copy(articulation = Articulation.PICK) else n }
    }

    private fun variants(song: Song, rnd: Random): List<Variant> {
        val tetra = Instrument.TETRACHORDO
        val step = 60.0 / song.bpm / 4
        val out = ArrayList<Variant>()
        val form = song.meta?.get("form") as List<*>?
        if (form == null) {
            val refs = song.gt.indices.toList()
            val occ = song.gt.indices.associateWith { listOf(song.gt[it].start) }
            val clean = tabNotes(song.gt, tetra, refs)
            out += Variant("clean", TabText.write(clean, tetra, step), clean, occ, emptyList())
            out += Variant("rough", TabText.write(clean, tetra, step, rnd = rnd, names = TabText.Names.NONE), clean, occ, emptyList())
            val bad = spoil(clean, tetra, rnd)
            out += Variant("spoiled", TabText.write(bad, tetra, step, rnd = rnd), bad, occ, emptyList())
            val part = clean.take((clean.size * 0.4).toInt())
            val partEnd = song.gt[part.size - 1].end
            out += Variant("first 40%", TabText.write(part, tetra, step, rnd = rnd), part, occ, listOf(partEnd + 0.3..1e9))
            val low = tabNotes(song.gt, tetra, refs, -12)
            out += Variant("octave low", TabText.write(low, tetra, step, rnd = rnd), low, occ, emptyList())
            val tri = tabNotes(song.gt, Instrument.TRICHORDO, refs)
            out += Variant("trichordo", TabText.write(tri, Instrument.TRICHORDO, step, rnd = rnd, names = TabText.Names.GREEK), tri, occ, emptyList())
            val key = tabNotes(song.gt, tetra, refs, -2)
            out += Variant("key -2", TabText.write(key, tetra, step, rnd = rnd), key, occ, emptyList())
            // One kind of mistake at a time.
            val wrong = spoil(clean, tetra, rnd, drop = 0.0, extra = 0.0)
            out += Variant("wrong frets", TabText.write(wrong, tetra, step, rnd = rnd), wrong, occ, emptyList())
            val dropped = spoil(clean, tetra, rnd, extra = 0.0, wrong = 0.0)
            out += Variant("left out", TabText.write(dropped, tetra, step, rnd = rnd), dropped, occ, emptyList())
            val extra = spoil(clean, tetra, rnd, drop = 0.0, wrong = 0.0)
            out += Variant("added", TabText.write(extra, tetra, step, rnd = rnd), extra, occ, emptyList())
            return out
        }
        // Songs with a form: each section's notes (first play) and when every play sounds.
        val untabbed = (song.meta["untabbed"] as List<*>).map { it as String }.toSet()
        val bySection = song.gt.groupBy { it.section to it.play }
        val sections = song.gt.map { it.section }.distinct()
        val ref = HashMap<String, Int>() // section -> ref of its first note (refs: section index * 1000 + position)
        val occ = HashMap<Int, List<Double>>()
        for ((si, s) in sections.withIndex()) {
            ref[s] = si * 1000
            val plays = bySection.keys.filter { it.first == s }.map { it.second }.sorted()
            val first = bySection[s to 0]!!
            for (pos in first.indices) occ[si * 1000 + pos] = plays.mapNotNull { p -> bySection[s to p]?.getOrNull(pos)?.start }
        }
        fun notesOf(s: String, play: Int = 0, shift: Int = 0): List<TabText.Note> {
            val g = bySection[s to play]!!
            return tabNotes(g, tetra, g.indices.map { ref[s]!! + it }, shift)
        }
        fun spans(skip: Set<String>) = bySection.filterKeys { it.first in skip }.values.map { g -> g.first().start - 0.05..g.last().end }
        val names = mapOf("I" to "Intro", "A" to "Verse", "B" to "Chorus", "C" to "Solo")
        val plays = form.filterIsInstance<String>()
        val tabbed = plays.filter { it !in untabbed }.distinct()
        // Every play written out, in order.
        run {
            val counter = HashMap<String, Int>()
            val all = ArrayList<TabText.Note>()
            val occOne = HashMap<Int, List<Double>>()
            var r = 0
            for (s in plays) {
                val p = counter.getOrDefault(s, 0)
                counter[s] = p + 1
                if (s in untabbed) continue
                val g = bySection[s to p]!!
                val n = tabNotes(g, tetra, g.indices.map { r + it })
                for ((i, x) in g.withIndex()) occOne[r + i] = listOf(x.start)
                r += 1000
                all += n
            }
            out += Variant("written out", TabText.write(all, tetra, step, rnd = rnd), all, occOne, spans(untabbed))
        }
        // Each section once, with headings, "x2" and headings naming a part again.
        run {
            val text = StringBuilder()
            val written = ArrayList<TabText.Note>()
            val seen = HashSet<String>()
            var k = 0
            while (k < plays.size) {
                val s = plays[k]
                var times = 1
                while (k + times < plays.size && plays[k + times] == s) times++
                k += times
                if (s in untabbed) continue
                text.append(names[s]).append(if (times > 1) " x$times" else "").append("\n")
                if (seen.add(s)) {
                    val n = notesOf(s)
                    written += n
                    text.append(TabText.write(n, tetra, step, rnd = rnd))
                }
                text.append("\n")
            }
            out += Variant("once, marked", text.toString(), written, occ, spans(untabbed))
        }
        // Each section once, as it first comes, no headings or marks.
        val once = tabbed.flatMap { notesOf(it) }
        out += Variant("once, unmarked", tabbed.joinToString("\n") { TabText.write(notesOf(it), tetra, step, rnd = rnd) }, once, occ, spans(untabbed))
        // Only the first verse.
        val verse = notesOf("A")
        out += Variant("first verse", TabText.write(verse, tetra, step, rnd = rnd), verse, occ, spans(sections.toSet() - "A"))
        // Once, unmarked, spoiled; and an octave low.
        val bad = tabbed.map { spoil(notesOf(it), tetra, rnd) }
        out += Variant("once, spoiled", bad.joinToString("\n") { TabText.write(it, tetra, step, rnd = rnd) }, bad.flatten(), occ, spans(untabbed))
        val low = tabbed.map { notesOf(it, shift = -12) }
        out += Variant("once, octave low", low.joinToString("\n") { TabText.write(it, tetra, step, rnd = rnd) }, low.flatten(), occ, spans(untabbed))
        return out
    }

    /** Totals of one variant over songs. */
    private class Score {
        val errors = ArrayList<Double>()   // |placed - played| of each occurrence placed within 0.5 s
        var occurrences = 0
        var within50 = 0
        var placements = 0
        var stray = 0
        var inUncovered = 0
        var matched = 0
        var total = 0
        var ms = 0.0
        var songs = 0
        var lineTp = 0; var lineEst = 0; var baseTp = 0; var baseEst = 0; var ref = 0
        // The same, only in the parts the tab doesn't cover.
        var uLineTp = 0; var uLineEst = 0; var uBaseTp = 0; var uBaseEst = 0; var uRef = 0

        fun add(o: Score) {
            errors += o.errors; occurrences += o.occurrences; within50 += o.within50; placements += o.placements; stray += o.stray
            inUncovered += o.inUncovered; matched += o.matched; total += o.total; ms += o.ms; songs += o.songs
            lineTp += o.lineTp; lineEst += o.lineEst; baseTp += o.baseTp; baseEst += o.baseEst; ref += o.ref
            uLineTp += o.uLineTp; uLineEst += o.uLineEst; uBaseTp += o.uBaseTp; uBaseEst += o.uBaseEst; uRef += o.uRef
        }

        /** The F-measure of the aligned song's line, and of the prepared line, over the song or only the parts the tab doesn't cover. */
        fun lineF() = 2.0 * lineTp / (lineEst + ref)
        fun baseF() = 2.0 * baseTp / (baseEst + ref)
        fun uncoveredF() = 2.0 * uLineTp / (uLineEst + uRef)
        fun uncoveredBaseF() = 2.0 * uBaseTp / (uBaseEst + uRef)

        override fun toString(): String {
            val e = errors.map { it * 1000 }.sorted()
            fun q(p: Double) = if (e.isEmpty()) Double.NaN else e[((e.size - 1) * p).toInt()]
            val uncovered = if (uRef == 0) "" else " | uncovered parts: F %.3f (base %.3f)".format(uncoveredF(), uncoveredBaseF())
            return ("within 50 ms %5.1f%% | error median %4.1f ms, p90 %5.1f ms | placed %5.1f%% | stray %4.1f%% | in uncovered %3d | matched %4.1f%% | " +
                "F line %.3f (base %.3f) | %4.0f ms/song").format(
                100.0 * within50 / occurrences, q(0.5), q(0.9), 100.0 * errors.size / occurrences, 100.0 * stray / placements.coerceAtLeast(1),
                inUncovered, 100.0 * matched / total, lineF(), baseF(), ms / songs) + uncovered
        }
    }

    /** One-to-one matches of [est] to [gt] (same pitch, onsets within 50 ms). */
    private fun hits(gt: List<Gt>, est: List<SongNote>): Int {
        val used = BooleanArray(est.size)
        var tp = 0
        val sorted = est.indices.sortedBy { est[it].start }
        for (g in gt) {
            var best = -1
            for (j in sorted) {
                if (used[j] || est[j].midi != g.midi) continue
                val d = abs(est[j].start - g.start)
                if (d <= 0.05 && (best < 0 || d < abs(est[best].start - g.start))) best = j
            }
            if (best >= 0) { used[best] = true; tp++ }
        }
        return tp
    }

    /**
     * The tab [read] from [v] with its bars: each note's bar counted every four beats from the song's first note, by the
     * times the notes are written for (a note a little early still in the bar it starts).
     */
    private fun barred(read: ParsedTab, v: Variant, song: Song): ParsedTab {
        if (read.notes.size != v.written.size) return read
        val bar = 240.0 / song.bpm
        val t0 = song.gt.first().start
        return read.copy(notes = read.notes.mapIndexed { j, n -> n.copy(bar = Math.floor((v.written[j].time - t0) / bar + 0.02).toInt()) })
    }

    private fun score(song: Song, v: Variant, base: SongAnalysis, par: TabAligner.Params, bars: Boolean): Score {
        val read = TabParser.parse(v.text, Instrument.TETRACHORDO)
        val tab = if (bars) barred(read, v, song) else read
        val s = Score()
        if (tab.notes.size != v.written.size) println("  ${song.name} ${v.name}: wrote ${v.written.size} notes, read ${tab.notes.size}; ${tab.warnings}")
        val data = AnalysisData(song.act, song.attacks)
        val t0 = System.nanoTime()
        val a = TabAligner.align(tab, data, base, true, par)
        s.ms = (System.nanoTime() - t0) / 1e6
        s.songs = 1
        s.matched = a.matched
        s.total = a.total
        // Placements of each written note (the tab's notes are the written ones, in order).
        val placedAt = HashMap<Int, MutableList<Double>>()
        for ((k, src) in a.sources.withIndex()) if (src >= 0) placedAt.getOrPut(src) { ArrayList() } += a.song.notes[k].start
        val count = minOf(tab.notes.size, v.written.size)
        if (TabAligner.log != null) {
            // Looking at one tab: where each written part starts and when it's played.
            TabAligner.log?.invoke("written parts: " + (0 until count).filter { it == 0 || v.written[it].ref / 1000 != v.written[it - 1].ref / 1000 }
                .joinToString(" ") { j -> "#$j@" + (v.occurrences[v.written[j].ref] ?: emptyList()).joinToString("/") { "%.1f".format(it) } })
        }
        for (j in 0 until count) {
            val r = v.written[j].ref
            val mine = placedAt[j] ?: emptyList()
            s.placements += mine.size
            if (r < 0) continue
            val occ = v.occurrences[r] ?: continue
            for (t in mine) {
                if (occ.none { abs(it - t) < 0.5 }) s.stray++
                if (v.uncovered.any { t in it }) s.inUncovered++
            }
        }
        // Each occurrence of a written note: the nearest placement of that note.
        val counted = HashSet<Pair<Int, Int>>()
        for (j in 0 until count) {
            val r = v.written[j].ref
            if (r < 0) continue
            val occ = v.occurrences[r] ?: continue
            for ((oi, t) in occ.withIndex()) {
                if (!counted.add(r to oi)) continue
                s.occurrences++
                val err = (placedAt[j] ?: emptyList<Double>()).minOfOrNull { abs(it - t) } ?: Double.MAX_VALUE
                if (err <= 0.5) s.errors += err
                if (err <= 0.05) s.within50++
            }
        }
        s.ref = song.gt.size
        s.lineTp = hits(song.gt, a.song.notes)
        s.lineEst = a.song.notes.size
        s.baseTp = hits(song.gt, base.notes)
        s.baseEst = base.notes.size
        if (v.uncovered.isNotEmpty()) {
            fun inside(t: Double) = v.uncovered.any { t in it }
            val gt = song.gt.filter { inside(it.start) }
            val mine = a.song.notes.filter { inside(it.start) }
            val theirs = base.notes.filter { inside(it.start) }
            s.uRef = gt.size; s.uLineTp = hits(gt, mine); s.uLineEst = mine.size; s.uBaseTp = hits(gt, theirs); s.uBaseEst = theirs.size
        }
        return s
    }

    @Test
    fun tabsLineUpWithTheSongs() {
        val d = dir ?: return
        val songs = listOf("kotlin_legato", "kotlin_tabs").flatMap { sub ->
            File(d, sub).listFiles()?.filter { File(it, "gt.csv").exists() }?.sortedBy { it.name } ?: emptyList()
        }
        val sets = tabParamSets()
        val totals = LinkedHashMap<String, Score>()
        val report = StringBuilder()
        for (f in songs) {
            val song = load(f)
            val base = SongAnalyzer.build(song.name, song.name, SongAnalyzer.candidates(song.act), Instrument.TETRACHORDO, song.act, song.attacks)
            val rnd = Random(song.name.hashCode().toLong())
            for (v in variants(song, rnd)) for (bars in listOf(false, true)) for ((setName, par) in sets) {
                val name = v.name + if (bars) ", bars" else ""
                // TAB_EVAL_ONLY="song name:variant name" looks at one tab, with the aligner's log.
                val only = System.getenv("TAB_EVAL_ONLY")?.split(":")
                if (only != null && (only[0] != song.name || only.getOrNull(1)?.let { it != name } == true)) continue
                if (only != null) TabAligner.log = { report.append("  aligner: ").append(it).append('\n') }
                val s = score(song, v, base, par, bars)
                TabAligner.log = null
                val key = (if (sets.size > 1) "[$setName] " else "") + (if (song.meta?.get("form") != null) "form: " else "") + name
                totals.getOrPut(key) { Score() }.add(s)
                if (sets.size == 1) {
                    val line = "%-26s %-24s %s".format(song.name, name, s)
                    println(line)
                    report.append(line).append('\n')
                }
            }
        }
        report.append('\n')
        for ((setName, _) in sets) {
            val all = Score()
            val sameOctave = Score() // the line's F-measure compares pitches: leave out the tabs kept an octave off
            val barred = Score()
            for ((k, s) in totals) if (sets.size == 1 || k.startsWith("[$setName] ")) {
                all.add(s)
                if ("octave" !in k) sameOctave.add(s)
                if (k.endsWith(", bars")) barred.add(s)
                val line = "%-30s %s".format(k, s)
                println(line)
                report.append(line).append('\n')
            }
            val prefix = if (sets.size > 1) "[$setName] " else ""
            val line = "%-30s %s\n%-30s %s\n%-30s %s\n".format(prefix + "ALL", all, prefix + "ALL but octave", sameOctave, prefix + "ALL with bars", barred)
            println(line)
            report.append(line).append('\n')
        }
        System.getenv("TAB_EVAL_OUT")?.let { File(it).writeText(report.toString()) }
        if (sets.size > 1) return
        val clean = totals["clean"] ?: return
        assertTrue(clean.within50 > clean.occurrences * 0.8, "clean tabs: $clean")
        // However spoiled, partial or loosely written, a tab leaves the song's line no worse than the prepared line, and
        // hardly worse in the parts it doesn't cover. (A tab an octave off is kept as written: the F-measure compares pitches.)
        for ((k, s) in totals) {
            if ("octave" in k) continue
            assertTrue(s.lineF() >= s.baseF(), "$k: $s")
            if (s.uRef > 0) assertTrue(s.uncoveredF() >= s.uncoveredBaseF() - 0.03, "$k: $s")
        }
    }
}

/** Sets of aligner weights from TAB_PARAMS ("skip=0.6,beam=4;skip=1"), to compare them (the defaults without it). */
internal fun tabParamSets(): List<Pair<String, TabAligner.Params>> {
    val all = System.getenv("TAB_PARAMS")?.takeIf { it.isNotBlank() } ?: return listOf("default" to TabAligner.Params())
    return all.split(";").map { it to tabParams(it) }
}

/** [TabAligner.Params] with the weights [spec] names ("skip=0.6,beam=4": any of its Float or Double fields) changed. */
private fun tabParams(spec: String): TabAligner.Params {
    val p = TabAligner.Params()
    for (kv in spec.split(",").filter { it.contains("=") }) {
        val (k, v) = kv.split("=").map { it.trim() }
        val f = try { p.javaClass.getDeclaredField(k) } catch (e: NoSuchFieldException) { error("unknown aligner weight $k") }
        f.isAccessible = true
        when (f.type) {
            java.lang.Float.TYPE -> f.setFloat(p, v.toFloat())
            java.lang.Double.TYPE -> f.setDouble(p, v.toDouble())
            else -> error("aligner weight $k is a ${f.type}")
        }
    }
    return p
}
