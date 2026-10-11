package com.critters.game

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Helpers that save pictures to build/ so the art can be checked by eye. */
object Shots {
    val dir = File("build/critter-shots").apply { mkdirs() }

    fun save(name: String, px: IntArray, w: Int, h: Int, scale: Int = 3) {
        val img = BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h * scale) for (x in 0 until w * scale) img.setRGB(x, y, px[(y / scale) * w + x / scale])
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    fun screen(name: String, s: Screen) = save(name, s.px, SW, SH)

    /** All species, front and back, on one sheet. */
    fun critterSheet() {
        val cols = 8
        val n = Dex.all.size
        val rows = (n + cols - 1) / cols * 2
        val w = cols * 50
        val h = rows * 50
        val px = IntArray(w * h) { 0xFFF8F8F0.toInt() }
        fun put(s: Sprite, ox: Int, oy: Int) {
            for (y in 0 until s.h) for (x in 0 until s.w) { val c = s.px[y * s.w + x]; if (c != 0) px[(oy + y) * w + ox + x] = c }
        }
        Dex.all.forEachIndexed { i, sp ->
            val cx = i % cols * 50 + 1
            val cy = i / cols * 100 + 1
            put(CritterArt.front(sp.id), cx, cy)
            put(CritterArt.back(sp.id), cx, cy + 50)
        }
        save("critters", px, w, h, 3)
    }
}
