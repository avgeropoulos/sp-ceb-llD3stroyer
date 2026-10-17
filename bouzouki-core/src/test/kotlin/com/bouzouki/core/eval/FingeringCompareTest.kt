package com.bouzouki.core.eval

import com.bouzouki.core.Instrument
import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.Fingering
import com.bouzouki.core.song.FingeringMetrics
import com.bouzouki.core.song.TuttutFingering
import java.io.File
import java.util.Locale
import kotlin.test.Test

/**
 * The fingering engines side by side (FINGERING_COMPARE_DIR: an evaluation set whose references say where each note
 * was played): how often each places a note where it was played, and how it plays (FingeringMetrics), for the HMM
 * engine, tuttut as ported (no course costs) and tuttut with the bouzouki's course costs.
 */
class FingeringCompareTest {
    @Test
    fun compare() {
        val dir = System.getenv("FINGERING_COMPARE_DIR")?.let { File(it) } ?: return
        val items = EvalSet.load(dir).filter { it.kind == "solo" && it.ref.all { r -> r.course >= 0 } }
        val engines = linkedMapOf<String, (List<RefNote>, Instrument) -> List<Pair<Int, Int>>>(
            "hmm" to { r, inst -> Fingering.place(r.map { it.midi }, r.map { it.start }, inst, r.map { it.articulation.isLegato }, r.map { it.articulation == Articulation.SLIDE }) },
            "tuttut" to { r, inst -> TuttutFingering.place(r.map { it.midi }, inst, r.map { it.articulation.isLegato }, TuttutFingering.Weights()) },
            "tuttut+courses" to { r, inst -> TuttutFingering.place(r.map { it.midi }, inst, r.map { it.articulation.isLegato }) },
        )
        for (group in listOf("random", "solo", "chromatic", "tremolo", "all")) {
            val its = items.filter { group == "all" || it.group == group }
            if (its.isEmpty()) continue
            for ((name, place) in engines) {
                var placed = 0; var course = 0; var fret = 0
                val reports = ArrayList<FingeringMetrics.Report>()
                var ms = 0.0
                for (it in its) {
                    val t0 = System.nanoTime()
                    val cells = place(it.ref, it.instrument)
                    ms += (System.nanoTime() - t0) / 1e6
                    for ((k, r) in it.ref.withIndex()) { placed++; if (cells[k].first == r.course) { course++; if (cells[k].second == r.fret) fret++ } }
                    reports += FingeringMetrics.of(it.ref.map { r -> r.midi }, it.ref.map { r -> r.start }, cells, it.instrument, it.ref.map { r -> r.articulation })
                }
                val top2 = 100 * reports.sumOf { it.topTwo * it.notes } / reports.sumOf { it.notes }
                val changes = reports.sumOf { it.stringChanges * it.notes } / reports.sumOf { it.notes }
                val jump = reports.sumOf { it.meanJump * it.notes } / reports.sumOf { it.notes }
                val span = reports.sumOf { it.spanViolations }
                println(String.format(Locale.US, "%-10s %-15s notes %4d · as played: course %5.1f%% fret %5.1f%% · top two %5.1f%% · course changes/note %.2f · mean jump %.2f · span violations %d · %.0f ms",
                    group, name, placed, 100.0 * course / placed, 100.0 * fret / placed, top2, changes, jump, span, ms))
            }
        }
    }
}
