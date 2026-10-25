package com.roccoquest.team

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HousesLookTest {
    /** Every house style, normal and painted, plus its inside colors (build/screens/all-houses.png). */
    @Test fun allHouses() {
        val types = HouseType.entries
        val cols = 8
        val cellW = 160f; val cellH = 200f
        val rows = (types.size + cols - 1) / cols
        val scale = 2f
        val img = BufferedImage((cols * cellW * scale).toInt(), (rows * cellH * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = AwtGfx(img, scale)
        g.rect(0f, 0f, cols * cellW, rows * cellH, 0xFF9AD8F0.toInt())
        for ((i, t) in types.withIndex()) {
            val cx = (i % cols) * cellW; val cy = (i / cols) * cellH
            g.rect(cx, cy + 150f, cellW, 6f, 0xFF5AB84A.toInt())
            g.rect(cx + cellW - 1, cy, 1f, cellH, 0x55000000)
            g.rect(cx, cy + cellH - 1, cellW, 1f, 0x55000000)
            // Normal on the left, painted purple at half size on the right
            Homes.drawHouse(g, t, cx + 20f, cy + 150f, 1.3f)
            Homes.drawHouse(ScaledGfx(g, 0.45f, cx + 122f, cy + 100f), t, 0f, 110f, 1.3f, Homes.PAINTS[6])
            val (wall, stripe, floor) = Homes.roomColors(t)
            g.rect(cx + 4, cy + 162f, 30f, 12f, wall); g.rect(cx + 12, cy + 162f, 6f, 12f, stripe); g.rect(cx + 4, cy + 174f, 30f, 4f, floor)
            g.text("${t.ordinal} ${t.label}", cx + 38f, cy + 172f, 7f, 0xFF1A1A1A.toInt())
        }
        File("build/screens").mkdirs()
        ImageIO.write(img, "png", File("build/screens/all-houses.png"))
        assertEquals(46, types.size)
        assertEquals(types.size, types.map { it.label }.toSet().size, "house names are all different")
    }

    /** The inside of every room can be drawn too. */
    @Test fun allRooms() {
        val img = BufferedImage(480, 240, BufferedImage.TYPE_INT_ARGB)
        val g = AwtGfx(img, 1f)
        for (t in HouseType.entries) {
            Homes.drawRoom(g, t, 0f, 480f, 240f)
            Homes.drawRoom(g, t, -400f, 480f, 240f, Homes.PAINTS[3])
        }
        val sheet = BufferedImage(6 * 240, 4 * 120, BufferedImage.TYPE_INT_ARGB)
        val sg = AwtGfx(sheet, 0.5f)
        for ((i, t) in HouseType.entries.drop(23).withIndex()) {
            val sx = ScaledGfx(sg, 1f, (i % 6) * 480f, (i / 6) * 240f)
            Homes.drawRoom(sx, t, 0f, 480f, 240f)
            sx.text(t.label, 10f, 20f, 16f, 0xFF1A1A1A.toInt())
        }
        File("build/screens").mkdirs()
        ImageIO.write(sheet, "png", File("build/screens/new-house-rooms.png"))
        assertTrue(HouseType.entries.all { Homes.roomColors(it).third ushr 24 == 0xFF })
    }
}
