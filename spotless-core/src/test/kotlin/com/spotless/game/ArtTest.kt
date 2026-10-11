package com.spotless.game

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

class ArtTest {
    /** Renders every outfit side by side to build/screens/outfits.png. */
    @Test fun outfits() {
        val dir = File("build/screens").apply { mkdirs() }
        val n = Story.levels.size
        val img = BufferedImage(1400, 1000, BufferedImage.TYPE_INT_ARGB)
        val p = AwtPainter(img)
        p.rect(0f, 0f, VW, VH, rgb(0xFF, 0xF0, 0xF5))
        Story.levels.forEachIndexed { i, lv ->
            val cx = VW / n * (i + 0.5f)
            Bella.draw(p, lv.outfit, cx, 1180f, 0.105f * 1.0f, Pose(mood = i / 7f, time = 1f))
        }
        ImageIO.write(img, "png", File(dir, "outfits.png"))
        for ((i, lv) in Story.levels.withIndex()) {
            val one = BufferedImage(540, 960, BufferedImage.TYPE_INT_ARGB)
            val q = AwtPainter(one)
            q.rect(0f, 0f, VW, VH, rgb(0xFF, 0xF0, 0xF5))
            Bella.draw(q, lv.outfit, VW / 2, 1200f, 1.2f, Pose(mood = i / 7f, time = 1f, wave = i == 1 || i == 4, face = if (i == 5) 4 else if (i % 2 == 0) 3 else 0, hip = i >= 2))
            ImageIO.write(one, "png", File(dir, "bella_$i.png"))
        }
    }
}
