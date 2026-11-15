package com.bouzoukihero.core

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The game dressed as another instrument (Sitar Sitara's), and Bouzouki Hero left as it was. */
class ThemeTest {
    private class Texts : Gfx {
        val all = ArrayList<String>()
        var lines = 0
        override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {}
        override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {}
        override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {}
        override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) { all += s }
        override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) { lines++ }
    }

    private val notes = (0 until 8).map { SourceNote(1.0 + it * 0.5, 0.3, 61 + it) }

    private fun frame(theme: Theme): Texts {
        val s = Session("Song", Difficulty.MEDIUM, Charts.build(notes, Difficulty.MEDIUM), hasVideo = true, tempo = 120.0, theme = theme)
        s.viewH = 640f
        s.update(0.5, 0.016f)
        return Texts().also { s.render(it) }
    }

    @Test fun theBoostIsCalledWhatTheThemeCallsIt() {
        assertTrue("WAH" in frame(Theme.SITAR).all)
        assertFalse("OPA" in frame(Theme.SITAR).all)
        assertTrue("OPA" in frame(Theme.BOUZOUKI).all)
    }

    @Test fun aSitarHasOneStringALaneABouzoukiAPair() {
        val sitar = frame(Theme.SITAR).lines
        val bouzouki = frame(Theme.BOUZOUKI).lines
        assertEquals(Difficulty.MEDIUM.lanes, bouzouki - sitar)
    }

    @Test fun notesCanBeNamedAnotherWay() {
        val sargam = listOf("S", "r", "R", "g", "G", "m", "M", "P", "d", "D", "n", "N")
        val gems = Charts.build(notes, Difficulty.EXPERT, label = { sargam[(it - 61).mod(12)] })
        assertEquals(listOf("S", "r", "R", "g", "G", "m", "M", "P"), gems.map { it.label })
        assertEquals(listOf("C#", "D", "Eb"), Charts.build(notes, Difficulty.EXPERT).take(3).map { it.label })
    }

    /** Renders the game as Sitar Sitara plays it, to build/screens/sitar.png for eyeballing. */
    @Test fun sitarScreenshot() {
        val sargam = listOf("S", "r", "R", "g", "G", "m", "M", "P", "d", "D", "n", "N")
        fun name(m: Int) = sargam[(m - 61).mod(12)] + if (m - 61 >= 12) "'" else ""
        // Yaman with Sa on C#, up and down.
        val melody = listOf(0, 2, 4, 6, 7, 9, 11, 12, 11, 9, 7, 6, 4, 2, 0, 2, 4, 7, 4, 2, 0).map { 61 + it }
        val tune = melody.mapIndexed { i, m -> SourceNote(1.0 + i * 0.4, if (i % 7 == 6) 0.8 else 0.3, m) }
        val gems = Charts.build(tune, Difficulty.HARD, label = ::name)
        val s = Session("Raga Yaman", Difficulty.HARD, gems, tempo = 150.0, theme = Theme.SITAR)
        s.viewH = 740f
        var t = 0.0
        val stop = gems[9].time + 0.05
        while (t < stop) {
            for (g in gems) if (g.judged == null && kotlin.math.abs(g.time - t) < 0.01) { s.press(g.lane, g.time); s.release(g.lane, g.time + 0.05) }
            s.update(t, 1 / 60f)
            t += 1 / 60.0
        }
        val img = BufferedImage(720, 1480, BufferedImage.TYPE_INT_ARGB)
        s.render(AwtGfx(img, 2f))
        ImageIO.write(img, "png", File(File("build/screens").apply { mkdirs() }, "sitar.png"))
    }
}
