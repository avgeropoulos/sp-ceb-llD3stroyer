package com.roccoquest.team

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

class StyleLookTest {
    /** Every bed, outfit, skin and costume, to eyeball (build/screens/all-styles.png). */
    @Test fun allStyles() {
        val img = BufferedImage(900 * 2, 300 * 2, BufferedImage.TYPE_INT_ARGB)
        val g = AwtGfx(img, 2f)
        g.rect(0f, 0f, 900f, 300f, 0xFFE8D8B0.toInt())
        for ((i, b) in BedStyle.entries.withIndex()) Homes.drawBed(ScaledGfx(g, 0.8f, 10f + i * 55f, 70f - Homes.FLOOR_Y * 0.8f), b, 0f)
        for ((i, o) in RoccoOutfit.entries.withIndex()) {
            val px = 20f + i * 38f
            val sp = if (o == RoccoOutfit.CLASSIC) com.roccoquest.game.Sprites.bigStand else TeamArt.roccoOutfit(o, 0)
            Style.hat(g, o.hat, px, 100f, 12f, 1, 0f, behind = true)
            g.sprite(sp, px - 8f, 100f)
            Style.hat(g, o.hat, px, 100f, 12f, 1, 0f)
        }
        for ((i, k) in BlockySkin.entries.withIndex()) {
            val px = 20f + i * 45f
            g.sprite(TeamArt.blockySkin(k, false), px - 8f, 160f)
            Style.hat(g, k.hat, px, 160f, 12f, 1, 0f)
        }
        for ((i, c) in MonCostume.entries.withIndex()) {
            val px = 20f + i * 42f
            TeamArt.mon(g, Mon.BLAZE, px, 270f, 0f, 1, false)
            Style.drawCostume(g, Mon.BLAZE, px, 270f, 1, 0f, c)
        }
        File("build/screens").mkdirs()
        ImageIO.write(img, "png", File("build/screens/all-styles.png"))
    }
}
