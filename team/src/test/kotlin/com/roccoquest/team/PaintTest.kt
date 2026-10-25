package com.roccoquest.team

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class PaintTest {
    /** Every house in a few paints, so the paint can be eyeballed (build/screens/house-paints.png). */
    /** Rocco next to the first 10 Pokemon, to check sizes (build/screens/mon-sizes.png). */
    @Test fun monSizes() {
        val img = BufferedImage(11 * 40 * 3, 50 * 3, BufferedImage.TYPE_INT_ARGB)
        val g = AwtGfx(img, 3f)
        g.rect(0f, 0f, 440f, 50f, 0xFF9AD8F0.toInt())
        g.rect(0f, 40f, 440f, 10f, 0xFF5AB84A.toInt())
        g.sprite(TeamArt.rocco(Power.NONE, 0), 10f, 40f - 24f * ROCCO_SCALE, w = 16f * ROCCO_SCALE, h = 24f * ROCCO_SCALE)
        for ((i, m) in Mon.entries.take(10).withIndex()) TeamArt.mon(g, m, 60f + i * 38f, 40f, 0f, 1, false)
        File("build/screens").mkdirs()
        ImageIO.write(img, "png", File("build/screens/mon-sizes.png"))
    }

    @Test fun paintedHouses() {
        val types = HouseType.entries
        val paints = listOf(0, 1, 3, 5, 7, 9)
        val img = BufferedImage(types.size * 110, paints.size * 140, BufferedImage.TYPE_INT_ARGB)
        val g = AwtGfx(img, 1f)
        g.rect(0f, 0f, img.width.toFloat(), img.height.toFloat(), 0xFF9AD8F0.toInt())
        for ((row, p) in paints.withIndex()) for ((i, t) in types.withIndex())
            Homes.drawHouse(g, t, i * 110f + 20f, row * 140f + 130f, 0f, Homes.PAINTS[p], 1 + row % 2)
        File("build/screens").mkdirs()
        ImageIO.write(img, "png", File("build/screens/house-paints.png"))
        // Red paint really turns the walls red: lots of reddish pixels in the red row.
        var red = 0
        for (y in 140 until 280) for (x in 0 until img.width) {
            val c = img.getRGB(x, y); val r = (c shr 16) and 255; val gg = (c shr 8) and 255; val b = c and 255
            if (r > gg + 60 && r > b + 60) red++
        }
        assertTrue(red > 2000, "red pixels $red")
    }
}
