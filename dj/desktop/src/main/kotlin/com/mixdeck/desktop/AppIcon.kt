package com.mixdeck.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO

/** The MixDeck icon (a turntable, same as the phone's), drawn at any size. */
object AppIcon {
    fun render(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val s = size / 108f
        g.scale(s.toDouble(), s.toDouble())
        g.color = Color(0x15, 0x15, 0x1A)
        g.fill(RoundRectangle2D.Float(2f, 2f, 104f, 104f, 24f, 24f))
        g.color = Color.BLACK
        g.fill(Ellipse2D.Float(14f, 14f, 80f, 80f))
        g.color = Color(0x2A, 0x2A, 0x33); g.stroke = BasicStroke(1.2f)
        g.draw(Ellipse2D.Float(22f, 22f, 64f, 64f)); g.draw(Ellipse2D.Float(30f, 30f, 48f, 48f))
        g.color = Color(0x29, 0xB6, 0xF6); g.fill(Ellipse2D.Float(40f, 40f, 28f, 28f))
        g.color = Color(0xFF, 0x70, 0x43); g.fill(Arc2D.Float(40f, 40f, 28f, 28f, 0f, 90f, Arc2D.PIE))
        g.color = Color.WHITE; g.fill(Ellipse2D.Float(51f, 51f, 6f, 6f))
        g.color = Color(0xDD, 0xDD, 0xDD)
        g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Path2D.Float().apply { moveTo(88f, 18f); lineTo(84f, 62f); lineTo(70f, 74f) })
        g.fill(Ellipse2D.Float(83f, 13f, 10f, 10f))
        g.dispose()
        return img
    }

    /**
     * Writes icon files for packaging: icon.png (Linux), icon.ico (Windows) and MixDeck.iconset/
     * (turned into icon.icns with macOS's iconutil).
     */
    fun writeAll(dir: File) {
        dir.mkdirs()
        ImageIO.write(render(512), "png", File(dir, "icon.png"))
        // An .ico file can hold PNG images directly.
        val sizes = listOf(16, 32, 48, 256)
        val pngs = sizes.map { sz -> ByteArrayOutputStream().also { ImageIO.write(render(sz), "png", it) }.toByteArray() }
        DataOutputStream(File(dir, "icon.ico").outputStream().buffered()).use { out ->
            fun le16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
            fun le32(v: Int) { le16(v and 0xFFFF); le16(v ushr 16) }
            le16(0); le16(1); le16(sizes.size)
            var offset = 6 + 16 * sizes.size
            sizes.forEachIndexed { k, sz ->
                out.write(if (sz >= 256) 0 else sz); out.write(if (sz >= 256) 0 else sz)
                out.write(0); out.write(0); le16(1); le16(32); le32(pngs[k].size); le32(offset)
                offset += pngs[k].size
            }
            pngs.forEach { out.write(it) }
        }
        val set = File(dir, "MixDeck.iconset").apply { mkdirs() }
        for (sz in listOf(16, 32, 128, 256, 512)) {
            ImageIO.write(render(sz), "png", File(set, "icon_${sz}x$sz.png"))
            ImageIO.write(render(sz * 2), "png", File(set, "icon_${sz}x$sz@2x.png"))
        }
    }
}
