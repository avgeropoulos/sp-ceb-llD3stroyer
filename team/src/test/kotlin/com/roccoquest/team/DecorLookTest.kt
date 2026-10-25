package com.roccoquest.team

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecorLookTest {
    private val cols = 8
    private val cellW = 170f
    private val cellH = 190f
    /** Room y shown at the top of each cell (the room is drawn from y = 20 to 210). */
    private val top = 20f

    /** Every decoration with its name, wall band and floor line (build/screens/all-decor.png and pages). */
    @Test
    fun allDecorPicture() {
        val items = DecorItem.entries
        File("build/screens").mkdirs()
        render(items, 1.5f, File("build/screens/all-decor.png"))
        // Bigger pages of 24, easier to look at one by one.
        items.chunked(24).forEachIndexed { i, page -> render(page, 2f, File("build/screens/all-decor-${i + 1}.png")) }
        assertTrue(File("build/screens/all-decor.png").length() > 0)
    }

    @Test
    fun decorCountsAndPacking() {
        assertEquals(152, DecorItem.entries.size)
        assertTrue(DecorItem.entries.size <= 256)
        assertEquals(DecorItem.entries.size, DecorItem.entries.map { it.label }.toSet().size, "labels must be unique")
        for (d in DecorItem.entries) {
            val p = Decor.unpack(Decor.pack(Placed(d, 1234f, if (d.yard) Decor.YARD else 3)))!!
            assertEquals(d, p.item); assertEquals(1234f, p.x)
        }
    }

    private fun render(items: List<DecorItem>, s: Float, file: File) {
        val rows = (items.size + cols - 1) / cols
        val img = BufferedImage((cols * cellW * s).toInt(), (rows * cellH * s).toInt(), BufferedImage.TYPE_INT_ARGB)
        val gfx = AwtGfx(img, s)
        gfx.rect(0f, 0f, cols * cellW, rows * cellH, 0xFF202020.toInt())
        items.forEachIndexed { i, d ->
            val cx = (i % cols) * cellW; val cy = (i / cols) * cellH
            val g = ScaledGfx(gfx, 1f, cx, cy - top)
            val fy = Homes.FLOOR_Y
            if (d.yard) {
                g.rect(1f, top + 1, cellW - 2, fy - top - 1, 0xFF9AD4F4.toInt())
                g.rect(1f, fy, cellW - 2, top + cellH - fy - 1, 0xFF6AB85A.toInt())
            } else {
                g.rect(1f, top + 1, cellW - 2, fy - top - 1, 0xFFF4E4C8.toInt())
                g.rect(1f, fy, cellW - 2, top + cellH - fy - 1, 0xFFB08050.toInt())
            }
            val x = (cellW - d.w) / 2
            Decor.draw(g, d, x, fy, 1.3f)
            g.rect(x, fy - 1, d.w, 1f, 0x55FF0000) // the item's width on the floor line
            g.text("${d.ordinal} ${d.label}", 4f, top + cellH - 6f, 7f, 0xFFFFFFFF.toInt())
            g.text("L${d.layer} ${d.coins}c", cellW - 4f, top + 10f, 6f, 0xFF202020.toInt(), 2)
        }
        ImageIO.write(img, "png", file)
    }
}
