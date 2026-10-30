package com.roccoquest.team

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewMonsLookTest {
    /** The 40 newest Pokemon at their in-game sizes, with Rocco for scale (build/screens/new-mons-2.png). */
    @Test fun fortyNewMons() {
        val newest = Mon.entries.dropWhile { it != Mon.CINDERCUB }
        assertEquals(40, newest.size)
        // Every one is built from parts and has its own look.
        assertTrue(newest.all { it in Pokedex.specs })
        val looks = newest.map { Pokedex.specs.getValue(it).let { s -> listOf(s.main, s.body, s.ears, s.tail, s.extras) } }
        assertEquals(looks.size, looks.toSet().size, "no two look the same")
        // Each new first form joins (later than all older ones), in level order.
        val roots = newest.filter { it.stage == 1 }
        assertTrue(roots.all { it in NEW_MONS })
        val w = 10 * 52f
        val h = 4 * 62f
        val k = 3f
        val img = BufferedImage((w * k).toInt(), (h * k).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = AwtGfx(img, k)
        g.rect(0f, 0f, w, h, 0xFFDDEEFF.toInt())
        newest.forEachIndexed { i, m ->
            val x = 26f + (i % 10) * 52f
            val y = 42f + (i / 10) * 62f
            g.rect(x - 24f, y, 48f, 1f, 0xFF8AA0B8.toInt())
            TeamArt.mon(g, m, x, y, 0.3f, 1, false)
            if (i % 10 == 0) g.sprite(TeamArt.rocco(Power.NONE, 0), x - 26f, y - 24f * ROCCO_SCALE, w = 16f * ROCCO_SCALE, h = 24f * ROCCO_SCALE)
            g.text(m.label, x, y + 8f, 4.2f, 0xFF202020.toInt(), 1)
            val evo = m.nextForm?.let { "-> ${it.label} Lv${it.evolveLevel}" } ?: if (m.isNewLegend) "LEGEND" else ""
            g.text(evo, x, y + 14f, 3.4f, 0xFF3A5A8A.toInt(), 1)
        }
        File("build/screens").mkdirs()
        ImageIO.write(img, "png", File("build/screens/new-mons-2.png"))
    }
}
