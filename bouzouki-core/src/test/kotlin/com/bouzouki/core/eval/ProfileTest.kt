package com.bouzouki.core.eval

import com.bouzouki.core.Instrument
import com.bouzouki.core.song.AnalysisData
import com.bouzouki.core.song.Attacks
import com.bouzouki.core.song.BasicPitch
import com.bouzouki.core.song.Beats
import com.bouzouki.core.song.SongAnalyzer
import com.bouzouki.core.song.Tuning
import com.bouzouki.core.song.resample
import java.io.File
import kotlin.test.Test

/** Where the transcription's time goes, step by step, on one recording (PROFILE_WAV), several times over. */
class ProfileTest {
    @Test
    fun profile() {
        val wav = System.getenv("PROFILE_WAV")?.let { File(it) } ?: return
        val mono = Wav.read(wav).channels[0]
        AppPipeline.withBasicPitch { bp ->
            repeat(3) { round ->
                val t = LinkedHashMap<String, Double>()
                fun <T> time(name: String, f: () -> T): T { val s = System.nanoTime(); val r = f(); t[name] = (System.nanoTime() - s) / 1e9; return r }
                val audio = time("resample") { resample(mono, 44100, BasicPitch.SAMPLE_RATE) }
                val cents = time("tuning") { Tuning.estimate(audio) }
                val act = time("basic pitch") { bp.activations(audio) }
                time("beats") { Beats.estimate(Beats.envelope(act), Beats.FPS) }
                val attacks = time("attacks") { Attacks.of(audio) }
                val data = time("data") { AnalysisData.of(act, attacks, cents) }
                time("line+fingering") { SongAnalyzer.build("x", "x", SongAnalyzer.candidates(data.act), Instrument.TETRACHORDO, data.act, data.attacks) }
                val secs = mono.size / 44100.0
                println("round $round, ${"%.1f".format(secs)} s audio: " + t.entries.joinToString("  ") { (k, v) -> "$k %.3f s (%.4f/s)".format(v, v / secs) })
            }
        }
    }
}
