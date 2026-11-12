package com.mixdeck.desktop

import com.mixdeck.engine.Analyzer
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.ShortArrayPcm
import com.mixdeck.engine.WavWriter
import com.mixdeck.ui.Console
import com.mixdeck.ui.TextMetrics
import com.mixdeck.ui.Tutorials
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * `MixDeck --selftest [dir]`: checks this build works on this computer without opening a window.
 * Encodes and decodes audio with FFmpeg, runs the engine, and draws the console to PNG files.
 * CI runs it on every OS before publishing.
 */
object SelfTest {
    fun run(dir: File): Boolean {
        dir.mkdirs()
        val sr = 44100
        // 1. A 20-second synthetic 120 BPM beat, written as WAV.
        val n = sr * 20
        val x = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr; val tb = t % 0.5
            x[i] = (sin(2 * PI * (50 * tb + 2 * (1 - exp(-tb * 30)))) * exp(-tb * 8) * 0.7 + sin(2 * PI * 220 * t) * 0.1).toFloat()
        }
        val wav = File(dir, "beat.wav")
        WavWriter(wav, sr).use { w ->
            val st = FloatArray(n * 2) { x[it / 2] }
            w.write(st, n)
        }
        // 2. Encode to AAC and decode it back through FFmpeg.
        val m4a = File(dir, "beat.m4a")
        Ffmpeg.wavToM4a(wav, m4a) {}
        val pcm = Ffmpeg.decode(m4a)
        val secs = pcm.frames / pcm.sampleRate.toDouble()
        println("ffmpeg: m4a ${m4a.length()} bytes, decoded ${"%.2f".format(secs)} s at ${pcm.sampleRate} Hz")
        if (secs < 19.5 || secs > 20.5) { println("FAIL: wrong length"); return false }
        // 3. Analyse and mix.
        val a = Analyzer.analyze(pcm)
        println("analysis: ${"%.2f".format(a.bpm)} BPM")
        if (kotlin.math.abs(a.bpm - 120) > 0.5) { println("FAIL: wrong BPM"); return false }
        val m = Mixer(48000)
        m.decks[0].load(pcm, a, "Self test", "MixDeck", Analyzer.loudnessDb(pcm))
        val p2 = ShortArrayPcm.fromFloats(x, x, sr)
        m.decks[1].load(p2, Analyzer.analyze(p2), "Second deck", "MixDeck", -14.0)
        m.decks[0].play(); m.decks[0].autoLoop(4.0, true); m.decks[1].syncTo(m.decks[0])
        val buf = FloatArray(512)
        repeat(500) { m.render(buf, 256) }
        // 4. Draw the console (plain, key, tutorial) to check fonts and drawing.
        TextMetrics.current = Fonts
        val host = object : Console.Host {
            override fun openLibrary(deck: Int) {}; override fun deckMenu(deck: Int) {}; override fun mainMenu() {}
            override fun toggleRecord() {}; override fun recording() = false; override fun recordSeconds() = 0.0
            override fun loadingText(deck: Int): String? = null; override fun savingText(): String? = null
        }
        for (mode in listOf("console", "key", "tutorial")) {
            val c = Console(m, host)
            if (mode == "key") c.helpMode = true
            if (mode == "tutorial") c.tutorial = Tutorials.nelly().also { it.index = 14 }
            c.layout(1600, 820)
            val img = BufferedImage(1600, 820, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            Java2DCanvas().apply { begin(g); c.draw(this) }
            g.dispose()
            ImageIO.write(img, "png", File(dir, "$mode.png"))
        }
        println("drew console to ${dir.path}")
        // 5. Optional: a real YouTube search (needs the internet; CI turns it on).
        if (System.getenv("MIXDECK_SELFTEST_NET") == "1") {
            val r = runCatching { com.mixdeck.YouTube.Search("Nelly Air Force Ones", songsOnly = true).nextPage() }
            println("youtube: " + r.fold({ "${it.size} results, first: ${it.firstOrNull()?.title}" }, { "FAILED ${it.message}" }))
        }
        println("OK")
        return true
    }
}
