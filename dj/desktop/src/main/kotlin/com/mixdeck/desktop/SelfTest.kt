package com.mixdeck.desktop

import com.mixdeck.engine.Analyzer
import com.mixdeck.engine.FeatureExtractor
import com.mixdeck.engine.Mixer
import com.mixdeck.engine.ShortArrayPcm
import com.mixdeck.engine.WavWriter
import com.mixdeck.ui.Console
import com.mixdeck.ui.Menus
import com.mixdeck.ui.TextMetrics
import com.mixdeck.ui.Tutorials
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
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
        // 3b. Key detection on a chord loop in A minor (Am Dm E Am) over the beat: should be 8A, or 8B (its relative).
        val chords = listOf(intArrayOf(57, 60, 64), intArrayOf(50, 53, 57), intArrayOf(52, 56, 59), intArrayOf(57, 60, 64))
        val kx = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var s = 0.0
            for (note in chords[t.toInt() % 4]) {
                val f = 440.0 * 2.0.pow((note - 69) / 12.0)
                for (h in 1..4) s += sin(2 * PI * f * h * t) / (h * h)
            }
            kx[i] = (s * 0.08 + x[i] * 0.5).toFloat()
        }
        val kp = ShortArrayPcm.fromFloats(kx, kx, sr)
        val t0 = System.nanoTime()
        val f = FeatureExtractor.extract(kp, 120.0)
        println("features: key ${f.key} (confidence ${"%.2f".format(f.keyConfidence)}), energy ${"%.1f".format(f.energy)}, " +
            "${(System.nanoTime() - t0) / 1_000_000} ms")
        if (f.key?.camelotNumber != 8) { println("FAIL: wrong key"); return false }
        val m = Mixer(48000)
        m.decks[0].load(pcm, a.withKey(f.key, f.keyConfidence), "Self test", "MixDeck", Analyzer.loudnessDb(pcm))
        m.decks[1].load(kp, Analyzer.analyze(kp).withKey(f.key, f.keyConfidence), "Second deck", "MixDeck", -14.0)
        m.decks[0].play(); m.decks[0].autoLoop(4.0, true)
        m.decks[1].pitch = 0.06 // one semitone up, so the key readout shows the arrow
        val buf = FloatArray(512)
        repeat(500) { m.render(buf, 256) }
        // 3c. MIX: deck A plays, MIX blends into deck B; drawn halfway (bar 5 of 8, just after the bass swap).
        val mm = Mixer(48000)
        mm.decks[0].load(pcm, a, "Self test", "MixDeck", Analyzer.loudnessDb(pcm))
        mm.decks[1].load(kp, Analyzer.analyze(kp).withKey(f.key, f.keyConfidence), "Second deck", "MixDeck", -14.0)
        mm.decks[0].play()
        repeat(48000 / 256) { mm.render(buf, 256) }
        mm.mixPressed()
        var guard = 0
        while ((mm.autoMix.state != com.mixdeck.engine.AutoMix.State.RUNNING || mm.autoMix.done < 16.5) && guard++ < 48000 * 15 / 256) mm.render(buf, 256)
        println("mix: ${mm.autoMix.state} bar ${mm.autoMix.current()} of ${mm.autoMix.runBars}, crossfader ${"%.2f".format(mm.crossfader)}")
        if (mm.autoMix.state != com.mixdeck.engine.AutoMix.State.RUNNING || mm.autoMix.current() != 5) { println("FAIL: MIX didn't run"); return false }
        // 4. Draw the console (plain, key, tutorial) to check fonts and drawing.
        TextMetrics.current = Fonts
        // "Up next" on a song strip's second line: a song queued on deck A, one downloading for deck B.
        var upNext = arrayOfNulls<String>(2)
        var loading = arrayOfNulls<String>(2)
        val host = object : Console.Host {
            override fun openLibrary(deck: Int) {}; override fun deckMenu(deck: Int) {}; override fun mainMenu() {}
            override fun toggleRecord() {}; override fun recording() = false; override fun recordSeconds() = 0.0
            override fun loadingText(deck: Int): String? = loading[deck]; override fun savingText(): String? = null
            override fun upNext(deck: Int) = upNext[deck]
            override fun shortcut(key: String) = Shortcuts.of(key, mac = false)
        }
        // name to size: the window's usual size, and its smallest for the notice line.
        // "mix-upnext": MIX running with a song queued (or downloading) for the deck it is leaving, as after NEXT → "Load when A ends".
        // "start": the Start card a newcomer sees (both decks empty); "start-returning": the same card for someone who has loaded songs before;
        // "start-notice": the newcomer's card with a notice over its title.
        // "key": the button labels with their key caps. "tutorial-wait" / "tutorial-failed": step 1 getting its songs, or failing to.
        // Every mode is drawn at the window's usual size (mode.png) and at its smallest (mode-small.png).
        val modes = listOf("console", "key", "tutorial", "notice", "notice-long", "mix", "upnext", "mix-upnext",
            "start", "start-returning", "start-notice", "tutorial-wait", "tutorial-failed")
            .flatMap { listOf(Triple(it, it, 1600 to 820), Triple(it, "$it-small", 1100 to 560)) }
        val empty = Mixer(48000)
        for ((mode, file, size) in modes) {
            val (w, h) = size
            val small = file.endsWith("-small")
            val mixing = mode.startsWith("mix")
            val starting = mode.startsWith("start") || mode.startsWith("tutorial-")
            val c = Console(if (mixing) mm else if (starting) empty else m, host, computer = true)
            c.newcomer = mode.startsWith("start") && !mode.contains("returning")
            loading = when (mode) {
                "tutorial-wait" -> arrayOf("Downloading Air Force Ones… 37%", "Up next: Grillz")
                else -> arrayOfNulls(2)
            }
            if (mode.startsWith("tutorial-")) c.tutorial = Tutorials.nelly().also { t ->
                if (mode == "tutorial-wait") t.status = "Song 1 of 2 · Downloading Air Force Ones… 37%"
                else { t.failed = true; t.status = Menus.tutorialSongsFailed(com.mixdeck.Problems.NO_INTERNET, computer = true) }
            }
            // The tutorial hides notices, but not an error (here a song picked with the lit LOAD buttons).
            if (mode == "tutorial-failed") c.say(com.mixdeck.Problems.downloadFailed("Grillz", java.net.UnknownHostException("youtube.com")), 1, error = true)
            upNext = when (mode) {
                "upnext" -> arrayOf("Hot in Herre", "Grillz · downloading 42%")
                "mix-upnext" -> if (small) arrayOf("Hot in Herre · downloading 42%", null) else arrayOf("Hot in Herre", null)
                else -> arrayOfNulls(2)
            }
            if (mode.contains("upnext")) c.say("Up next: \"Hot in Herre\" loads on deck A when \"Self test\" ends", 0)
            else if (mixing) c.say(mm.autoMix.message ?: "", 1)
            if (mode.startsWith("key")) c.helpMode = true
            if (mode == "tutorial") c.tutorial = Tutorials.nelly().also { it.index = 14 }
            // The notice line: a load notice edged in deck B's colour, and a long error that wraps to two lines.
            if (mode == "notice") c.say(Console.loadedNotice(m, 1, null, computer = true), 1)
            // An update finished downloading while a newcomer's Start card shows: the notice stands in for its title.
            if (mode == "start-notice") c.say("MixDeck 1.130 is downloaded · restart from ☰ now")
            // The advice comes first: a long title is what gets cut off.
            if (mode == "notice-long") c.say(com.mixdeck.Problems.downloadFailed("Air Force Ones (feat. Kyjuan, Ali & Murphy Lee)",
                org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException("private")), 0, error = true)
            c.layout(w, h)
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            Java2DCanvas().apply { begin(g); c.draw(this) }
            g.dispose()
            ImageIO.write(img, "png", File(dir, "$file.png"))
        }
        println("drew console to ${dir.path}")
        // 4b. The Settings panel (☰ → Settings…), as the computer shows it.
        runCatching {
            com.formdev.flatlaf.FlatDarkLaf.setup()
            val sp = SettingsPanel(m, saved = {}, checkUpdates = {}, openMixes = {}, done = {})
            sp.size = java.awt.Dimension(maxOf(560, sp.preferredSize.width), maxOf(400, sp.preferredSize.height))
            fun layoutAll(c: java.awt.Component) { if (c is java.awt.Container) { c.doLayout(); c.components.forEach(::layoutAll) } }
            layoutAll(sp)
            val img = BufferedImage(sp.width, sp.height, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            sp.paint(g); g.dispose()
            ImageIO.write(img, "png", File(dir, "settings.png"))
            println("settings: ${sp.width}x${sp.height}")
        }.onFailure { println("settings: not drawn (${it.message})") }
        // 5. Optional: a real YouTube search (needs the internet; CI turns it on).
        if (System.getenv("MIXDECK_SELFTEST_NET") == "1") {
            val r = runCatching { com.mixdeck.YouTube.Search("Nelly Air Force Ones", songsOnly = true).nextPage() }
            println("youtube: " + r.fold({ "${it.size} results, first: ${it.firstOrNull()?.title}" }, { "FAILED ${it.message}" }))
        }
        println("OK")
        return true
    }
}
